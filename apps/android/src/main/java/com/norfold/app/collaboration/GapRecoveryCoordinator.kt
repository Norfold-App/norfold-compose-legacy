package com.norfold.app.collaboration

import java.util.LinkedHashMap
import java.security.MessageDigest

sealed interface SequenceDecision {
    data object Accept : SequenceDecision
    data object Duplicate : SequenceDecision
    data class Gap(val expected: Long, val received: Long) : SequenceDecision
}

/** Durable ordering is tracked per actor/device stream, independent of event type and target. */
class DurableSequenceTracker(private val maxStreams: Int = 512) {
    private val lastByStream = object : LinkedHashMap<String, Long>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?): Boolean = size > maxStreams
    }
    private val seen = object : LinkedHashMap<String, Unit>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Unit>?): Boolean = size > maxStreams * 8
    }

    @Synchronized
    fun inspect(envelope: InboundEnvelope): SequenceDecision {
        if (envelope.eventClass != CollabEventClass.DURABLE) return SequenceDecision.Accept
        if (envelope.operationId.value in seen) return SequenceDecision.Duplicate
        val key = "${envelope.actorId.value}|${envelope.deviceId.value}"
        val last = lastByStream[key]
        val received = envelope.seq.value
        if (last != null && received > last + 1) return SequenceDecision.Gap(last + 1, received)
        seen[envelope.operationId.value] = Unit
        lastByStream[key] = maxOf(last ?: Long.MIN_VALUE, received)
        return SequenceDecision.Accept
    }

    @Synchronized fun markRecovered(actorId: ActorId, deviceId: DeviceId, sequence: Long) {
        val key = "${actorId.value}|${deviceId.value}"
        lastByStream[key] = maxOf(lastByStream[key] ?: Long.MIN_VALUE, sequence)
    }
}

data class ArtifactRevision(
    val serverRevision: Long = 0,
    val revisionHash: String = "",
)

sealed interface RecoveryState {
    data object Synchronized : RecoveryState
    data class Recovering(val fromRevision: Long) : RecoveryState
    data class SnapshotNeeded(val revision: Long, val revisionHash: String) : RecoveryState
    data class Incompatible(val reason: String) : RecoveryState
}

class GapRecoveryCoordinator(
    private val gateway: DurableOperationGateway,
    private val applyRecovered: suspend (InboundEnvelope) -> Unit,
    private val applySnapshot: suspend (CollaborationSnapshot) -> Boolean = { false },
) {
    suspend fun recover(roomId: RoomId, local: ArtifactRevision): Pair<RecoveryState, ArtifactRevision> {
        var revision = local
        repeat(MAX_WINDOWS) {
            when (val result = gateway.recover(RecoveryRequest(roomId, revision.serverRevision, revision.revisionHash))) {
                is RecoveryResult.Operations -> {
                    val ordered = result.envelopes.sortedBy { it.serverRevision }
                    val invalid = validateWindow(revision, ordered, result)
                    if (invalid != null) return RecoveryState.Incompatible(invalid) to revision
                    ordered.forEach { applyRecovered(it) }
                    revision = ArtifactRevision(result.serverRevision, result.revisionHash)
                    if (result.complete) return RecoveryState.Synchronized to revision
                }
                is RecoveryResult.SnapshotRequired -> {
                    val snapshot = gateway.fetchSnapshot(roomId, result.serverRevision)
                    if (snapshot is SnapshotResult.Available &&
                        snapshot.snapshot.serverRevision == result.serverRevision &&
                        snapshot.snapshot.revisionHash == result.revisionHash &&
                        snapshot.snapshot.protocolVersion == OutboundEnvelope.PROTOCOL_VERSION &&
                        applySnapshot(snapshot.snapshot)
                    ) {
                        val next = ArtifactRevision(result.serverRevision, result.revisionHash)
                        return RecoveryState.Synchronized to next
                    }
                    return RecoveryState.SnapshotNeeded(result.serverRevision, result.revisionHash) to revision
                }
                is RecoveryResult.Rejected -> return RecoveryState.Incompatible(result.code) to revision
            }
        }
        return RecoveryState.Incompatible("recovery_window_limit") to revision
    }

    private fun validateWindow(
        local: ArtifactRevision,
        ordered: List<InboundEnvelope>,
        result: RecoveryResult.Operations,
    ): String? {
        if (ordered.isEmpty()) {
            return if (result.serverRevision == local.serverRevision &&
                (local.serverRevision == 0L || result.revisionHash == local.revisionHash)
            ) null else "empty_recovery_window"
        }
        var expectedRevision = local.serverRevision + 1
        var previousHash = if (local.serverRevision == 0L) ZERO_HASH else local.revisionHash
        if (!previousHash.matches(HASH)) return "invalid_local_revision_hash"
        ordered.forEach { envelope ->
            val revision = envelope.serverRevision ?: return "missing_server_revision"
            if (revision != expectedRevision) return "non_contiguous_server_revision"
            if (envelope.wirePayloadHash != hashPayload(envelope.payload)) return "recovery_payload_hash_mismatch"
            val expectedHash = chainHash(previousHash, envelope.wirePayloadHash, envelope.operationId.value, revision)
            if (envelope.revisionHash != expectedHash) return "recovery_revision_hash_mismatch"
            previousHash = expectedHash
            expectedRevision++
        }
        if (result.serverRevision != expectedRevision - 1 || result.revisionHash != previousHash) {
            return "recovery_window_marker_mismatch"
        }
        return null
    }

    private fun chainHash(previous: String, payload: String, operation: String, revision: Long): String =
        MessageDigest.getInstance("SHA-256")
            .digest((previous + payload + operation + revision).toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    companion object {
        const val MAX_WINDOWS = 64
        private val HASH = Regex("[0-9a-f]{64}")
        private val ZERO_HASH = "0".repeat(64)
    }
}
