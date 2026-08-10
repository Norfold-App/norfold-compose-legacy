package com.norfold.app.collaboration.merge

/**
 * Provider-independent merge-engine SPI. The production editor imports only this boundary;
 * [SemanticOpSetMergeEngine] is selected by [CollaborationMergeEngineFactory].
 */

/** Opaque revision identifier(s). For CRDTs this wraps heads/state-vector bytes. */
data class Revision(val heads: List<String>) {
    companion object { val EMPTY = Revision(emptyList()) }
}

/**
 * A durable, semantic local operation produced by the editor. It is NOT a whole-document JSON
 * blob — it names a domain, a stable target, and an operation intent.
 */
data class LocalOperation(
    val opId: String,
    val actorId: String,
    /** Logical command this op belongs to — the unit of selective undo. */
    val commandId: String,
    val deviceId: String = "unknown-device",
    val logicalClock: Long = 0,
    val op: com.norfold.app.collaboration.protocol.SemanticMutation,
)

/** A remote operation received from a peer, already decoded from the transport envelope. */
data class RemoteOperation(
    val opId: String,
    val actorId: String,
    val commandId: String,
    val deviceId: String = "unknown-device",
    val logicalClock: Long = 0,
    val op: com.norfold.app.collaboration.protocol.SemanticMutation,
)

/** An opaque serialized snapshot of a merge document, for persistence/recovery. */
data class Snapshot(val engineId: String, val bytes: ByteArray) {
    override fun equals(other: Any?) = other is Snapshot && engineId == other.engineId && bytes.contentEquals(other.bytes)
    override fun hashCode() = 31 * engineId.hashCode() + bytes.contentHashCode()
}

/** A binary sync payload exchanged on reconnect (diff since peer's known heads). */
data class SyncPayload(val engineId: String, val bytes: ByteArray) {
    override fun equals(other: Any?) = other is SyncPayload && engineId == other.engineId && bytes.contentEquals(other.bytes)
    override fun hashCode() = 31 * engineId.hashCode() + bytes.contentHashCode()
}

/**
 * A live, mergeable document instance. One per artifact per session.
 */
interface MergeDocument {
    val engineId: String
    fun currentRevision(): Revision

    /** Preflight a complete atomic batch before the owning domain mutates live state. */
    fun validateRemote(operations: List<RemoteOperation>): String? = null

    /**
     * Integrate one semantic transaction. [reconcile] must apply the projected domain state
     * atomically or return false without changing it. The merge state commits only after it accepts.
     */
    fun integrateRemoteBatch(
        operations: List<RemoteOperation>,
        reconcile: (List<com.norfold.app.collaboration.protocol.SemanticMutation>) -> Boolean,
    ): RemoteBatchResult {
        validateRemote(operations)?.let { return RemoteBatchResult.Rejected(it) }
        operations.forEach(::applyRemote)
        return if (reconcile(canonicalOperations())) RemoteBatchResult.Applied(currentRevision())
        else RemoteBatchResult.Rejected("domain_invariant_rejected")
    }

    /** Canonical winning semantic state, ordered for deterministic domain reconciliation. */
    fun canonicalOperations(): List<com.norfold.app.collaboration.protocol.SemanticMutation> = emptyList()

    /** Apply a local semantic operation and return the resulting revision. */
    fun applyLocal(op: LocalOperation): Revision

    /** Apply a remote operation. Idempotent by opId; must never resurrect tombstoned content. */
    fun applyRemote(op: RemoteOperation): Revision

    /** Serialize a full snapshot for durable local storage. */
    fun snapshot(): Snapshot

    /**
     * Produce a sync payload containing everything the peer at [theirRevision] is missing.
     * If [theirRevision] is EMPTY, this is a full state transfer.
     */
    fun syncSince(theirRevision: Revision): SyncPayload

    /** Merge a peer's sync payload; return the new revision after integration. */
    fun integrate(payload: SyncPayload): Revision

    /** Recovery variant with the same validate/project/reconcile/commit boundary as live batches. */
    fun integrateSnapshot(
        payload: SyncPayload,
        reconcile: (List<com.norfold.app.collaboration.protocol.SemanticMutation>) -> Boolean,
    ): RemoteBatchResult = RemoteBatchResult.Rejected("atomic_snapshot_integration_unsupported")
}

sealed interface RemoteBatchResult {
    data class Applied(val revision: Revision) : RemoteBatchResult
    data class Rejected(val reason: String) : RemoteBatchResult
}

/**
 * Selective, user-intent undo. Reverses the current user's own [commandId] WITHOUT reverting
 * other users' later work. Not a whole-document snapshot rollback.
 *
 * High-level undo stays in Norfold's HistoryTransactionEngine; this SPI is what that engine calls
 * so the CRDT produces a correct inverse that respects concurrent edits.
 */
interface SelectiveUndo {
    /** True if [commandId] by [actorId] can currently be selectively undone. */
    fun canUndo(commandId: String, actorId: String): Boolean

    /**
     * Compute the inverse operations that undo [commandId] given the current document state.
     * Returns operations to apply locally (which then propagate as normal remote ops to peers).
     */
    fun buildUndo(commandId: String, actorId: String): List<LocalOperation>
}

/**
 * Factory for a merge engine. Registered engines are selected by id so benchmarks can swap them.
 */
interface CollaborationMergeEngine {
    val engineId: String
    val supportsSelectiveUndo: Boolean
    val supportsBinarySync: Boolean

    fun createDocument(artifactId: String, actorId: String): MergeDocument
    fun loadDocument(artifactId: String, actorId: String, snapshot: Snapshot): MergeDocument
    fun selectiveUndoFor(document: MergeDocument): SelectiveUndo?
}
