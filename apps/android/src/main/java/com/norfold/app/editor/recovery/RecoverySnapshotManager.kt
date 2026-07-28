package com.norfold.app.editor.recovery

import com.norfold.app.domain.BlockDocumentJson
import com.norfold.app.editor.history.EditTarget
import com.norfold.app.editor.history.EditWorkspace
import java.util.UUID

/**
 * Captures and restores [RecoverySnapshot]s over an [EditWorkspace] (roadmap Phase 0 D3 / spec §13.7).
 *
 * This is the recovery-protection safety net: **before any risky operation** (migration, destructive edit,
 * overwriting import) a caller takes a snapshot; if the operation goes wrong, [restore] puts every captured
 * document back exactly as it was. Capture serializes each target's live [com.norfold.app.domain.BlockDocument]
 * through the production [BlockDocumentJson] codec, and restore decodes it back, so the round trip is proven
 * against real document payloads — not a shallow object copy that could mask a serialization bug in the very
 * path a migration relies on.
 *
 * The recovery-protection rule (roadmap §5 Phase 0 D3): no later-phase migration may execute unless snapshot
 * create and restore are proven functional. This manager is that proof surface.
 *
 * [idGenerator] and [clock] are injectable so tests are deterministic; production uses random UUIDs and wall
 * time. Not thread-safe — the editor loop serializes access, matching the History Engine.
 */
class RecoverySnapshotManager(
    private val workspace: EditWorkspace,
    private val store: RecoverySnapshotStore = InMemoryRecoverySnapshotStore(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val idGenerator: () -> String = { UUID.randomUUID().toString() },
) {
    /**
     * Captures the current documents for [targets] into a new snapshot and stores it.
     *
     * @param targets the documents to protect; defaults to every known target in the workspace.
     * @throws IllegalArgumentException if [targets] is empty (nothing to protect).
     * @throws com.norfold.app.editor.history.HistoryStateException if a requested target is unknown.
     */
    fun snapshot(
        reason: RecoveryReason,
        targets: Collection<EditTarget> = workspace.knownTargets(),
        label: String = "",
    ): RecoverySnapshot {
        require(targets.isNotEmpty()) { "Cannot snapshot an empty target set" }
        val payloads = targets.associate { target ->
            target.id to BlockDocumentJson.encodeDocumentPayload(workspace.documentOf(target))
        }
        val snapshot = RecoverySnapshot(
            id = idGenerator(),
            reason = reason,
            createdAt = clock(),
            documents = payloads,
            label = label,
        )
        store.put(snapshot)
        return snapshot
    }

    /**
     * Restores every document captured in [snapshot] back into the workspace, decoding each payload with the
     * production codec. All-or-nothing: if any payload fails to decode, no target is modified.
     *
     * @return the set of targets that were restored.
     */
    fun restore(snapshot: RecoverySnapshot): Set<EditTarget> {
        // Decode everything first so a single corrupt payload cannot leave a half-restored set.
        val decoded = snapshot.documents.mapValues { (_, payload) ->
            BlockDocumentJson.decodeDocumentPayload(payload)
        }
        decoded.forEach { (targetId, document) ->
            workspace.setDocument(EditTarget(targetId), document)
        }
        return decoded.keys.map { EditTarget(it) }.toSet()
    }

    /** Restores the snapshot with [snapshotId]; returns the restored targets, or null if it was not found. */
    fun restoreById(snapshotId: String): Set<EditTarget>? {
        val snapshot = store.get(snapshotId) ?: return null
        return restore(snapshot)
    }

    /** Restores the most recent snapshot; returns the restored targets, or null if the store is empty. */
    fun restoreLatest(): Set<EditTarget>? {
        val snapshot = store.latest() ?: return null
        return restore(snapshot)
    }

    /**
     * Runs [block] with a recovery snapshot taken first; if [block] throws, the snapshot is restored before
     * the exception propagates. This is the intended call shape for any risky operation: the caller never has
     * to remember to roll back by hand.
     */
    fun <T> guarded(
        reason: RecoveryReason,
        targets: Collection<EditTarget> = workspace.knownTargets(),
        label: String = "",
        block: () -> T,
    ): T {
        val snapshot = snapshot(reason, targets, label)
        return try {
            block()
        } catch (t: Throwable) {
            restore(snapshot)
            throw t
        }
    }

    fun snapshots(): List<RecoverySnapshot> = store.all()
}
