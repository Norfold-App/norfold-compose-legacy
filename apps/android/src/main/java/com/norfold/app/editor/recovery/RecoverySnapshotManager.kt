package com.norfold.app.editor.recovery

import com.norfold.app.domain.BlockDocument
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
/**
 * How a restore reaches the workspace when there is a history to reach it through.
 *
 * A restore rewrites documents the History Engine already holds inverses for. Writing them straight into the
 * workspace would leave those inverses describing a document that no longer exists: the next Undo would apply
 * a stale inverse to restored content, and the redo stack would still be offering to replay edits from before
 * the restore. So a host that owns a history hands one of these in, and the restore becomes an ordinary
 * transaction — atomic across every target it touches, undoable like any other step, and clearing redo the way
 * every other commit does (§13.1, §13.4).
 *
 * The host implements it rather than the manager reaching for an engine, because the host is what knows the
 * document invariants that must hold after the write.
 */
fun interface RecoveryHistorySink {
    /**
     * Commits [documents] as one canonical transaction labelled [label].
     *
     * Returning false means there was nothing to commit — the restored content already matched what was
     * there. A commit that cannot be applied throws, and [RecoverySnapshotManager.restoreSafely] converts
     * that into a reported outcome.
     */
    fun commitRestore(label: String, documents: Map<EditTarget, BlockDocument>): Boolean
}

class RecoverySnapshotManager(
    private val workspace: EditWorkspace,
    internal val store: RecoverySnapshotStore = InMemoryRecoverySnapshotStore(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val idGenerator: () -> String = { UUID.randomUUID().toString() },
    /**
     * Null for the Phase-0 protection path, where a snapshot guards a migration that is not itself an
     * undoable edit and there is no history to keep coherent. The recovery browser always supplies one.
     */
    private val historySink: RecoveryHistorySink? = null,
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
        name: String? = null,
        description: String = "",
        scopeBlockIds: List<String> = emptyList(),
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
            name = name?.trim()?.takeIf(String::isNotBlank),
            description = description.trim(),
            scopeBlockIds = scopeBlockIds,
        )
        store.put(snapshot)
        return snapshot
    }

    /**
     * Creates a **named** restore point the user asked for (spec §13.7).
     *
     * Named points differ from automatic ones in exactly two ways that matter: they carry the user's own
     * words, and retention protects them from being evicted by automatic churn. Everything else — capture,
     * verification, restore — is the same path, so a manual point is never a second-class snapshot with its
     * own bugs.
     */
    fun createNamedSnapshot(
        name: String,
        description: String = "",
        targets: Collection<EditTarget> = workspace.knownTargets(),
        scopeBlockIds: List<String> = emptyList(),
    ): RecoverySnapshot? {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return null
        if (targets.isEmpty()) return null
        return snapshot(
            reason = RecoveryReason.MANUAL_CHECKPOINT,
            targets = targets,
            label = "",
            name = trimmed.take(MAX_NAME_LENGTH),
            description = description.trim().take(MAX_DESCRIPTION_LENGTH),
            scopeBlockIds = scopeBlockIds,
        )
    }

    /**
     * Restores every document captured in [snapshot] back into the workspace, decoding each payload with the
     * production codec. All-or-nothing: if any payload fails to decode, no target is modified.
     *
     * A [RecoverySnapshot.isScoped] snapshot rewrites only its scoped top-level blocks and leaves the rest
     * of each document exactly as the user left it — restoring one damaged chart should not also revert the
     * paragraph someone fixed afterwards.
     *
     * @return the set of targets that were restored.
     */
    fun restore(snapshot: RecoverySnapshot): Set<EditTarget> {
        val resolved = resolveRestore(snapshot)
        val sink = historySink
        if (sink == null) {
            resolved.forEach { (target, document) -> workspace.setDocument(target, document) }
        } else {
            sink.commitRestore("Restore ${snapshot.displayName()}", resolved)
        }
        return resolved.keys
    }

    /**
     * Decodes [snapshot] into the documents a restore would install, without writing anything.
     *
     * Everything is decoded before anything is written so a single corrupt payload cannot leave a
     * half-restored set.
     */
    private fun resolveRestore(snapshot: RecoverySnapshot): Map<EditTarget, BlockDocument> =
        snapshot.documents.entries.associate { (targetId, payload) ->
            val target = EditTarget(targetId)
            val captured = BlockDocumentJson.decodeDocumentPayload(payload)
            val document = if (!snapshot.isScoped) captured
            else mergeScoped(workspace.documentOf(target), captured, snapshot.scopeBlockIds)
            target to document
        }

    /**
     * Writes [snapshot] into the workspace without touching history.
     *
     * Only the rollback inside [restoreSafely] uses this. A forward restore that threw applied nothing — the
     * engine's commit is atomic — so putting the pre-image back is repairing the workspace, not performing an
     * edit, and committing it would leave the user with a history entry for something they never did.
     */
    private fun restoreRaw(snapshot: RecoverySnapshot): Set<EditTarget> {
        val resolved = resolveRestore(snapshot)
        resolved.forEach { (target, document) -> workspace.setDocument(target, document) }
        return resolved.keys
    }

    /**
     * Validates a snapshot without touching the workspace (Phase-10 §22: verify before mutating).
     *
     * A restore that discovers a bad payload halfway through has already destroyed the thing it was meant
     * to protect, so the check runs first and separately, and the UI can offer restore only when it will
     * actually work.
     *
     * Damage is decided by [BlockDocumentJson.isDocumentPayload] rather than by attempting a decode.
     * `decodeDocumentPayload` never throws — a truncated payload comes back as one unknown block and a blank
     * one comes back as an empty document — so a decode attempt would report every payload as readable and
     * this check would pass unconditionally. Restoring either of those over live content is exactly the
     * silent destruction validation exists to prevent.
     */
    fun validate(snapshot: RecoverySnapshot): RecoveryValidation {
        val unknown = snapshot.documents.keys.map(::EditTarget).filterNot { it in workspace.knownTargets() }
        val undecodable = snapshot.documents
            .filterNot { (_, payload) -> BlockDocumentJson.isDocumentPayload(payload) }
            .keys.toList()
        return RecoveryValidation(
            snapshotId = snapshot.id,
            unreadableTargetIds = undecodable,
            unknownTargetIds = unknown.map { it.id },
        )
    }

    /**
     * Restores [snapshot] after validating it and capturing the live state first (spec §13.7).
     *
     * The pre-image is the whole point: a restore is destructive, and a user who restores the wrong point
     * must be able to get back. The safety snapshot is taken only once validation has passed, so a refused
     * restore does not litter the store with checkpoints for something that never happened.
     *
     * The rollback is itself allowed to fail, and its failure is reported rather than thrown. Whatever stops
     * a workspace write — a host that refused it, a target that turned read-only — is almost certainly still
     * true a moment later when the pre-image is written back, so a rollback that threw would be the *likely*
     * path, not an exotic one. Letting it escape would hand the caller an exception where it was promised a
     * [RecoveryRestoreOutcome], and would leave the user with no report at all about what state their work is
     * in. [RecoveryRestoreOutcome.Failed.workspaceIntact] carries that answer instead.
     */
    fun restoreSafely(snapshot: RecoverySnapshot): RecoveryRestoreOutcome {
        val validation = validate(snapshot)
        if (!validation.isRestorable) return RecoveryRestoreOutcome.Refused(validation)
        val protectedTargets = snapshot.documents.keys.map(::EditTarget)
            .filter { it in workspace.knownTargets() }
        val safety = if (protectedTargets.isEmpty()) null else snapshot(
            reason = RecoveryReason.BEFORE_RESTORE,
            targets = protectedTargets,
            label = "Before restoring ${snapshot.displayName()}",
        )
        return try {
            RecoveryRestoreOutcome.Restored(restore(snapshot), safety)
        } catch (t: Throwable) {
            // Reported pessimistically: a restore that threw before writing anything is indistinguishable
            // here from one that wrote half, and overstating safety after a destructive operation is the
            // one direction to be wrong in.
            val rolledBack = safety != null && runCatching { restoreRaw(safety) }.isSuccess
            RecoveryRestoreOutcome.Failed(
                reason = t.message ?: DEFAULT_FAILURE_REASON,
                safetySnapshot = safety,
                workspaceIntact = rolledBack,
            )
        }
    }

    /**
     * Replaces only [scopeBlockIds] in [current] with their captured versions.
     *
     * A scoped block that no longer exists is re-inserted at the index it held in the capture (clamped),
     * because "restore this page" has to work after the page was deleted — that is the case people reach
     * for recovery in. Blocks outside the scope are untouched, in their current order.
     */
    private fun mergeScoped(
        current: BlockDocument,
        captured: BlockDocument,
        scopeBlockIds: List<String>,
    ): BlockDocument {
        val scope = scopeBlockIds.toSet()
        val capturedById = captured.blocks.associateBy { it.id }
        val blocks = current.blocks.map { block ->
            if (block.id in scope) capturedById[block.id] ?: block else block
        }.toMutableList()

        val presentIds = blocks.map { it.id }.toSet()
        captured.blocks.forEachIndexed { index, block ->
            if (block.id in scope && block.id !in presentIds) {
                blocks.add(index.coerceIn(0, blocks.size), block)
            }
        }
        return current.copy(blocks = blocks).normalized()
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

    /** Deletes the stored restore point with [snapshotId]; false if there was nothing to delete. */
    fun delete(snapshotId: String): Boolean = store.remove(snapshotId)

    companion object {
        /** Caps a user-supplied name so one pasted document cannot become a restore-point title. */
        const val MAX_NAME_LENGTH = 80

        /** Caps a user-supplied note for the same reason. */
        const val MAX_DESCRIPTION_LENGTH = 240

        /** Shown when a restore threw something with no message of its own. */
        const val DEFAULT_FAILURE_REASON = "The restore could not be completed"
    }
}

/**
 * The result of checking a snapshot before restoring it (Phase-10 §22).
 *
 * Two failure modes are distinguished because they mean different things to a person: a payload this build
 * cannot decode is damage, while a target that no longer exists is a document that was removed since the
 * capture. Both block a restore; only the second is the user's own doing, and the UI must be able to say so.
 *
 * The lists carry target **ids** only — never a payload, never document content.
 */
data class RecoveryValidation(
    val snapshotId: String,
    val unreadableTargetIds: List<String> = emptyList(),
    val unknownTargetIds: List<String> = emptyList(),
) {
    /** True when every captured document decodes and every captured target still exists. */
    val isRestorable: Boolean get() = unreadableTargetIds.isEmpty() && unknownTargetIds.isEmpty()

    val isDamaged: Boolean get() = unreadableTargetIds.isNotEmpty()
}

/**
 * What happened when a restore was attempted through [RecoverySnapshotManager.restoreSafely].
 *
 * [Restored] and [Failed] both carry the safety snapshot taken beforehand, so the caller can offer "undo
 * this restore" without having to remember an id it never asked for.
 */
sealed interface RecoveryRestoreOutcome {
    data class Restored(
        val targets: Set<EditTarget>,
        val safetySnapshot: RecoverySnapshot?,
    ) : RecoveryRestoreOutcome

    /** Validation refused the restore; the workspace was never touched. */
    data class Refused(val validation: RecoveryValidation) : RecoveryRestoreOutcome

    /**
     * The restore could not be carried out.
     *
     * [workspaceIntact] is the part a person needs: true means their work is where they left it — either
     * nothing was attempted, or the pre-image was put back — and false means the restore failed *and* the
     * rollback failed too, so the documents may be mid-restore. Only the second case warrants alarming
     * anybody, and collapsing the two would either cry wolf or hide a genuinely damaged workspace.
     */
    data class Failed(
        val reason: String,
        val safetySnapshot: RecoverySnapshot?,
        val workspaceIntact: Boolean = true,
    ) : RecoveryRestoreOutcome

    val succeeded: Boolean get() = this is Restored
}
