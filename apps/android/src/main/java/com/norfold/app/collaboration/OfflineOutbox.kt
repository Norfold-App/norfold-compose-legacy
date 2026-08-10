package com.norfold.app.collaboration

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

enum class OutboxFailureKind { TRANSIENT, AUTH_EXPIRED, PERMANENT }

@Serializable
data class OutboxRecord(
    val envelope: StoredEnvelope,
    val enqueuedAt: Long,
    val attempts: Int = 0,
    val nextAttemptAt: Long = 0,
    val failure: OutboxFailureKind? = null,
    val failureCode: String? = null,
)

@Serializable
data class StoredEnvelope(
    val room: String,
    val actor: String,
    val device: String,
    val operationId: String,
    val batchId: String?,
    val eventClass: String,
    val eventType: String,
    val targetId: String?,
    val sequence: Long,
    val clientSentAt: Long,
    val protocolVersion: Int,
    val semanticVersion: Int,
    val artifactSchemaVersion: Int,
    val keyEpoch: Long,
    val contentType: String,
    val payloadHash: String,
    val payload: JsonElement?,
    val serverRevision: Long? = null,
    val revisionHash: String? = null,
) {
    fun envelope() = OutboundEnvelope(
        roomId = RoomId(room), actorId = ActorId(actor), deviceId = DeviceId(device),
        operationId = OperationId(operationId), batchId = batchId,
        eventClass = CollabEventClass.valueOf(eventClass), eventType = eventType, targetId = targetId,
        seq = CollabSeq(sequence), clientSentAt = clientSentAt, protocolVersion = protocolVersion,
        semanticVersion = semanticVersion, artifactSchemaVersion = artifactSchemaVersion,
        keyEpoch = keyEpoch, contentType = contentType, payloadHash = payloadHash, payload = payload,
        serverRevision = serverRevision, revisionHash = revisionHash,
    )

    companion object {
        fun from(envelope: OutboundEnvelope) = StoredEnvelope(
            envelope.roomId.value, envelope.actorId.value, envelope.deviceId.value,
            envelope.operationId.value, envelope.batchId, envelope.eventClass.name,
            envelope.eventType, envelope.targetId, envelope.seq.value, envelope.clientSentAt,
            envelope.protocolVersion, envelope.semanticVersion, envelope.artifactSchemaVersion,
            envelope.keyEpoch, envelope.contentType, envelope.payloadHash, envelope.payload,
            envelope.serverRevision, envelope.revisionHash,
        )
    }
}

sealed interface OutboxEnqueueResult {
    data object Enqueued : OutboxEnqueueResult
    data object AlreadyPending : OutboxEnqueueResult
    data object OperationIdCollision : OutboxEnqueueResult
    data class Blocked(val pendingCount: Int, val pendingBytes: Long) : OutboxEnqueueResult
}

interface OutboxPersistence {
    /** True only when [replace] survives process death and a clean application restart. */
    val isProcessDurable: Boolean
    fun load(): List<OutboxRecord>
    fun replace(records: List<OutboxRecord>)
}

class MemoryOutboxPersistence : OutboxPersistence {
    override val isProcessDurable: Boolean = false
    private var records = emptyList<OutboxRecord>()
    override fun load(): List<OutboxRecord> = records
    override fun replace(records: List<OutboxRecord>) { this.records = records.toList() }
}

/** Atomic temp-file replacement with fsync. A prior valid file is never overwritten in place. */
class FileOutboxPersistence(
    private val file: File,
    private val json: Json = Json { encodeDefaults = true; explicitNulls = false; ignoreUnknownKeys = false },
) : OutboxPersistence {
    override val isProcessDurable: Boolean = true
    @Serializable private data class Snapshot(val formatVersion: Int = 1, val records: List<OutboxRecord>)

    override fun load(): List<OutboxRecord> {
        val temp = tempFile()
        decode(temp)?.let { return it }
        decode(file)?.let { return it }
        if (!file.exists() && !temp.exists()) return emptyList()
        throw OutboxCorruptionException("Pending collaboration edits could not be read")
    }

    override fun replace(records: List<OutboxRecord>) {
        file.parentFile?.mkdirs()
        val temp = tempFile()
        val encoded = json.encodeToString(Snapshot(records = records)).toByteArray(Charsets.UTF_8)
        RandomAccessFile(temp, "rw").use { target ->
            target.setLength(0)
            target.write(encoded)
            target.fd.sync()
        }
        try {
            Files.move(
                temp.toPath(), file.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (atomic: java.nio.file.AtomicMoveNotSupportedException) {
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
        file.absoluteFile.parentFile?.toPath()?.let { directory ->
            java.nio.channels.FileChannel.open(directory, StandardOpenOption.READ).use { it.force(true) }
        }
    }

    private fun tempFile(): File = File(file.absoluteFile.parentFile, "${file.name}.tmp")

    private fun decode(candidate: File): List<OutboxRecord>? {
        if (!candidate.isFile) return null
        return runCatching {
            val snapshot = json.decodeFromString(Snapshot.serializer(), candidate.readText())
            require(snapshot.formatVersion == 1) { "Unsupported outbox format" }
            snapshot.records
        }.getOrNull()
    }
}

class OutboxCorruptionException(message: String, cause: Throwable? = null) : IllegalStateException(message, cause)

/** Durable operations remain stored until a persistent server ACK names their operation IDs. */
class OfflineOutbox(
    private val maxSize: Int = 4096,
    private val maxBytes: Long = 16L * 1024 * 1024,
    private val persistence: OutboxPersistence = MemoryOutboxPersistence(),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val mutex = Mutex()
    private var records: MutableList<OutboxRecord> = persistence.load().toMutableList()

    val isProcessDurable: Boolean get() = persistence.isProcessDurable

    suspend fun enqueue(envelope: OutboundEnvelope): OutboxEnqueueResult {
        require(envelope.eventClass == CollabEventClass.DURABLE) { "Only durable operations may enter the outbox" }
        return mutex.withLock {
            records.firstOrNull { it.envelope.operationId == envelope.operationId.value }?.let { existing ->
                return@withLock if (existing.envelope == StoredEnvelope.from(envelope)) {
                    OutboxEnqueueResult.AlreadyPending
                } else {
                    OutboxEnqueueResult.OperationIdCollision
                }
            }
            val candidate = OutboxRecord(StoredEnvelope.from(envelope), clock())
            val bytes = serializedBytes(records) + serializedBytes(listOf(candidate))
            if (records.size >= maxSize || bytes > maxBytes) {
                return@withLock OutboxEnqueueResult.Blocked(records.size, serializedBytes(records))
            }
            records.add(candidate)
            persistence.replace(records)
            OutboxEnqueueResult.Enqueued
        }
    }

    suspend fun pending(roomId: RoomId? = null, now: Long = clock()): List<OutboundEnvelope> = mutex.withLock {
        records.asSequence()
            .filter { roomId == null || it.envelope.room == roomId.value }
            .filter { it.failure != OutboxFailureKind.PERMANENT && it.nextAttemptAt <= now }
            .sortedWith(compareBy<OutboxRecord> { it.enqueuedAt }.thenBy { it.envelope.sequence })
            .map { it.envelope.envelope() }
            .toList()
    }

    suspend fun acknowledge(operationIds: Collection<OperationId>): Int = mutex.withLock {
        val ids = operationIds.mapTo(HashSet()) { it.value }
        val before = records.size
        records.removeAll { it.envelope.operationId in ids }
        if (records.size != before) persistence.replace(records)
        before - records.size
    }

    suspend fun recordFailure(
        operationId: OperationId,
        kind: OutboxFailureKind,
        code: String,
        retryAt: Long = clock(),
    ) = mutex.withLock {
        val index = records.indexOfFirst { it.envelope.operationId == operationId.value }
        if (index >= 0) {
            val current = records[index]
            records[index] = current.copy(
                attempts = current.attempts + 1,
                nextAttemptAt = retryAt,
                failure = kind,
                failureCode = code.take(128),
            )
            persistence.replace(records)
        }
    }

    suspend fun size(): Int = mutex.withLock { records.size }
    suspend fun bytes(): Long = mutex.withLock { serializedBytes(records) }
    suspend fun attempts(operationId: OperationId): Int = mutex.withLock {
        records.firstOrNull { it.envelope.operationId == operationId.value }?.attempts ?: 0
    }

    /** Compatibility read: unlike the preliminary implementation this never removes pending data. */
    suspend fun drainAll(): List<OutboundEnvelope> = pending()

    private fun serializedBytes(value: List<OutboxRecord>): Long = value.sumOf {
        it.envelope.payload.toString().toByteArray(Charsets.UTF_8).size.toLong() +
            it.envelope.operationId.length + it.envelope.room.length + 384L
    }
}
