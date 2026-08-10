package com.norfold.app.collaboration.merge

import com.norfold.app.collaboration.protocol.SemanticMutation
import com.norfold.app.collaboration.protocol.SemanticOperationCodec
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import java.util.TreeMap
import java.security.MessageDigest

/**
 * Repository-owned hybrid operation set: stable ordered intents plus deterministic LWW registers.
 * History supplies inverses; this engine only converges local and remote semantic operations.
 */
class SemanticOpSetMergeEngine(
    private val maxOperations: Int = 50_000,
) : CollaborationMergeEngine {
    override val engineId = "norfold-semantic-opset-v1"
    override val supportsSelectiveUndo = false
    override val supportsBinarySync = true

    override fun createDocument(artifactId: String, actorId: String): MergeDocument =
        SemanticOpSetDocument(artifactId, maxOperations)

    override fun loadDocument(artifactId: String, actorId: String, snapshot: Snapshot): MergeDocument {
        require(snapshot.engineId == engineId) { "Snapshot engine mismatch" }
        return SemanticOpSetDocument.fromSnapshot(artifactId, maxOperations, snapshot)
    }

    override fun selectiveUndoFor(document: MergeDocument): SelectiveUndo? = null
}

@Serializable
private data class StoredOperation(
    val opId: String,
    val actorId: String,
    val deviceId: String,
    val commandId: String,
    val logicalClock: Long,
    val mutation: SemanticMutation,
) {
    val stamp: MergeStamp get() = MergeStamp(logicalClock, actorId, deviceId, opId)
}

private data class MergeStamp(val clock: Long, val actor: String, val device: String, val operation: String) : Comparable<MergeStamp> {
    override fun compareTo(other: MergeStamp): Int = compareValuesBy(this, other, MergeStamp::clock, MergeStamp::actor, MergeStamp::device, MergeStamp::operation)
}

@Serializable private data class OpSetSnapshot(val formatVersion: Int = 1, val operations: List<StoredOperation>)

private class SemanticOpSetDocument(
    private val artifactId: String,
    private val maxOperations: Int,
    initial: List<StoredOperation> = emptyList(),
) : MergeDocument {
    override val engineId = "norfold-semantic-opset-v1"
    /** One winner/tombstone per semantic conflict register; superseded operations are compacted eagerly. */
    private val operations = TreeMap<String, StoredOperation>().apply {
        initial.forEach { operation ->
            val key = conflictKey(operation)
            val current = get(key)
            if (current == null || current.stamp < operation.stamp) put(key, operation)
        }
    }
    private val operationsById = operations.values.associateByTo(HashMap()) { it.opId }
    private val revisionAccumulator = ByteArray(32).also { accumulator ->
        operations.values.forEach { operation -> xorInto(accumulator, digest(operation)) }
    }

    override fun currentRevision(): Revision = if (operations.isEmpty()) Revision.EMPTY else Revision(listOf(revisionAccumulator.toHex()))

    override fun validateRemote(operations: List<RemoteOperation>): String? {
        val byId = operations.groupBy { it.opId }
        if (byId.values.any { group -> group.distinct().size > 1 }) return "operation_id_collision"
        val candidate = TreeMap(this.operations)
        operations.forEach { incoming ->
            val existing = operationsById[incoming.opId]
            if (existing == null) {
                mergeCandidate(candidate, StoredOperation(
                    incoming.opId, incoming.actorId, incoming.deviceId,
                    incoming.commandId, incoming.logicalClock, incoming.op,
                ))
                return@forEach
            }
            val candidate = StoredOperation(
                incoming.opId, incoming.actorId, incoming.deviceId,
                incoming.commandId, incoming.logicalClock, incoming.op,
            )
            if (existing != candidate) return "operation_id_collision"
        }
        if (candidate.size > maxOperations) return "merge_operation_bound"
        return null
    }

    override fun integrateRemoteBatch(
        operations: List<RemoteOperation>,
        reconcile: (List<SemanticMutation>) -> Boolean,
    ): RemoteBatchResult {
        validateRemote(operations)?.let { return RemoteBatchResult.Rejected(it) }
        val candidate = TreeMap(this.operations)
        operations.forEach { incoming ->
            mergeCandidate(candidate, StoredOperation(
                incoming.opId, incoming.actorId, incoming.deviceId,
                incoming.commandId, incoming.logicalClock, incoming.op,
            ))
        }
        if (!reconcile(project(candidate))) {
            return RemoteBatchResult.Rejected("domain_invariant_rejected")
        }
        operations.forEach { incoming ->
            apply(StoredOperation(
                incoming.opId, incoming.actorId, incoming.deviceId,
                incoming.commandId, incoming.logicalClock, incoming.op,
            ))
        }
        return RemoteBatchResult.Applied(currentRevision())
    }

    override fun canonicalOperations(): List<SemanticMutation> = project(operations)

    override fun applyLocal(op: LocalOperation): Revision = apply(
        StoredOperation(op.opId, op.actorId, op.deviceId, op.commandId, op.logicalClock, op.op),
    )

    override fun applyRemote(op: RemoteOperation): Revision = apply(
        StoredOperation(op.opId, op.actorId, op.deviceId, op.commandId, op.logicalClock, op.op),
    )

    private fun apply(operation: StoredOperation): Revision {
        operationsById[operation.opId]?.let { existing ->
            require(existing == operation) { "Operation id collision" }
            return currentRevision()
        }
        val key = conflictKey(operation)
        val current = operations[key]
        if (current != null && current.stamp >= operation.stamp) return currentRevision()
        if (current == null) require(operations.size < maxOperations) { "Merge operation bound exceeded" }
        if (current != null) {
            operationsById.remove(current.opId)
            xorInto(revisionAccumulator, digest(current))
        }
        operations[key] = operation
        operationsById[operation.opId] = operation
        xorInto(revisionAccumulator, digest(operation))
        return currentRevision()
    }

    override fun snapshot(): Snapshot = Snapshot(engineId, JSON.encodeToString(OpSetSnapshot(operations = operations.values.toList())).toByteArray())

    override fun syncSince(theirRevision: Revision): SyncPayload =
        if (theirRevision == currentRevision()) SyncPayload(engineId, ByteArray(0))
        else SyncPayload(engineId, snapshot().bytes)

    override fun integrate(payload: SyncPayload): Revision {
        require(payload.engineId == engineId) { "Sync engine mismatch" }
        if (payload.bytes.isEmpty()) return currentRevision()
        val incoming = JSON.decodeFromString(OpSetSnapshot.serializer(), payload.bytes.toString(Charsets.UTF_8))
        require(incoming.operations.size <= maxOperations) { "Incoming sync exceeds operation bound" }
        incoming.operations.sortedBy { it.opId }.forEach(::apply)
        return currentRevision()
    }

    override fun integrateSnapshot(
        payload: SyncPayload,
        reconcile: (List<SemanticMutation>) -> Boolean,
    ): RemoteBatchResult {
        require(payload.engineId == engineId) { "Sync engine mismatch" }
        if (payload.bytes.isEmpty()) return RemoteBatchResult.Applied(currentRevision())
        val incoming = JSON.decodeFromString(OpSetSnapshot.serializer(), payload.bytes.toString(Charsets.UTF_8))
        require(incoming.formatVersion == 1 && incoming.operations.size <= maxOperations) {
            "Incoming snapshot exceeds operation bound"
        }
        val candidate = TreeMap(operations)
        incoming.operations.forEach { mergeCandidate(candidate, it) }
        if (candidate.size > maxOperations) return RemoteBatchResult.Rejected("merge_operation_bound")
        if (!reconcile(project(candidate))) return RemoteBatchResult.Rejected("domain_invariant_rejected")
        incoming.operations.forEach(::apply)
        return RemoteBatchResult.Applied(currentRevision())
    }

    companion object {
        private val JSON = Json { encodeDefaults = true; explicitNulls = false; ignoreUnknownKeys = false }
        fun fromSnapshot(artifactId: String, maxOperations: Int, snapshot: Snapshot): SemanticOpSetDocument {
            val decoded = JSON.decodeFromString(OpSetSnapshot.serializer(), snapshot.bytes.toString(Charsets.UTF_8))
            require(decoded.formatVersion == 1 && decoded.operations.size <= maxOperations)
            return SemanticOpSetDocument(artifactId, maxOperations, decoded.operations)
        }

        private fun digest(operation: StoredOperation): ByteArray {
            val element = JSON.encodeToJsonElement(StoredOperation.serializer(), operation)
            return MessageDigest.getInstance("SHA-256")
                .digest(SemanticOperationCodec.canonicalize(element).toString().toByteArray())
        }

        private fun xorInto(target: ByteArray, value: ByteArray) {
            for (index in target.indices) target[index] = (target[index].toInt() xor value[index].toInt()).toByte()
        }

        private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

        private fun mergeCandidate(target: TreeMap<String, StoredOperation>, operation: StoredOperation) {
            val key = conflictKey(operation)
            val current = target[key]
            if (current == null || current.stamp < operation.stamp) target[key] = operation
        }

        private fun conflictKey(operation: StoredOperation): String {
            val mutation = operation.mutation
            val explicitProperty = (mutation.payload["property"] as? JsonPrimitive)?.content
            val property = when {
                mutation.domain == "spreadsheet" && mutation.action in setOf(
                    "cell.write", "formula.write", "value.write", "cell.formula",
                ) -> "input"
                mutation.domain == "comment" && mutation.action in setOf("thread.resolve", "thread.reopen") -> "resolution"
                mutation.domain == "image" && mutation.action == "edit.commit" -> "edit"
                mutation.domain == "chart" && mutation.action == "document.set" -> "document"
                else -> explicitProperty ?: mutation.action
            }
            val member = listOf("itemId", "cellId", "seriesId", "commentId", "relationshipId")
                .firstNotNullOfOrNull { key -> (mutation.payload[key] as? JsonPrimitive)?.content }
                .orEmpty()
            val category = when {
                mutation.action == "text.insert" -> "2:text-insert:${operation.opId}"
                mutation.action == "text.delete" -> "2:text-delete:${mutation.payload["fromAnchor"]}:${mutation.payload["toAnchor"]}"
                mutation.action == "text.mark" -> "2:text-mark:${mutation.payload["fromAnchor"]}:${mutation.payload["toAnchor"]}:$property"
                mutation.action.endsWith(".insert") || mutation.action.endsWith(".create") ||
                    mutation.action.endsWith(".delete") || mutation.action.endsWith(".remove") -> "0:existence"
                mutation.action.endsWith(".move") || mutation.action.contains("reparent") ||
                    mutation.action.contains("z-order") -> "1:position"
                else -> "2:property:$property"
            }
            return "${mutation.domain}|${mutation.targetId}|$member|$category"
        }

        private fun entityKey(mutation: SemanticMutation): String {
            val member = listOf("itemId", "cellId", "seriesId", "commentId", "relationshipId")
                .firstNotNullOfOrNull { key -> (mutation.payload[key] as? JsonPrimitive)?.content }
                .orEmpty()
            return "${mutation.domain}|${mutation.targetId}|$member"
        }

        private fun project(source: Map<String, StoredOperation>): List<SemanticMutation> {
            val tombstones = source.values.asSequence()
                .filter { isEntityDelete(it.mutation) }
                .map { entityKey(it.mutation) }
                .toSet()
            return source.values.asSequence()
                .map { it.mutation }
                .filter { mutation ->
                    entityKey(mutation) !in tombstones ||
                        isEntityDelete(mutation)
                }
                .toList()
        }

        private fun isEntityDelete(mutation: SemanticMutation): Boolean =
            mutation.action != "text.delete" &&
                (mutation.action.endsWith(".delete") || mutation.action.endsWith(".remove"))
    }
}
