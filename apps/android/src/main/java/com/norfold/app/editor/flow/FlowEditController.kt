package com.norfold.app.editor.flow

import com.norfold.app.domain.BlockCursor
import com.norfold.app.domain.BlockDocument
import com.norfold.app.domain.BlockEditorSession
import com.norfold.app.editor.history.EditTarget
import com.norfold.app.editor.history.EditTransaction
import com.norfold.app.editor.history.EditWorkspace
import com.norfold.app.editor.history.HistoryEngine
import com.norfold.app.editor.history.HistoryListener
import com.norfold.app.editor.history.InMemoryEditWorkspace

/**
 * The authoritative edit controller for one Flow note (roadmap Phase 1 D1/D9; spec §13).
 *
 * This is where FlowHost genuinely departs from the legacy host: every Flow mutation is expressed
 * through the proven [BlockEditorSession] vocabulary but committed to the **canonical**
 * [HistoryEngine], and the session's private snapshot-deque undo is never used. The controller
 *
 *  1. keeps the live document in an [EditWorkspace] keyed by one [EditTarget];
 *  2. runs a requested mutation against a *throwaway* session seeded from the live document, so the
 *     mutation vocabulary is reused verbatim without inheriting its undo model;
 *  3. diffs `before → after` into canonical [com.norfold.app.editor.history.EditOperation]s
 *     ([FlowDocumentDiff]) and commits them as one [EditTransaction];
 *  4. delegates undo/redo to the History Engine, which inverts transactions atomically.
 *
 * Typing coalescing is preserved (continuous typing in one block is a single undo unit) but is now
 * driven by the History Engine's coalescing-key merge rather than a per-session deque. The 1.2 s
 * window and per-run keying are computed here against an injectable [nanoTime] clock so behavior is
 * deterministic under test.
 *
 * Persistence dirty/deleted sets are derived by diffing the live document against the last-saved one,
 * which matches the repository's one-Room-row-per-top-level-block model exactly (§17.1). No Android,
 * persistence, or collaboration dependency — the host wires those in.
 */
class FlowEditController(
    val target: EditTarget,
    initialDocument: BlockDocument,
    historyListener: HistoryListener? = null,
    private val nanoTime: () -> Long = System::nanoTime,
) {
    private val workspace: EditWorkspace =
        InMemoryEditWorkspace(mapOf(target to initialDocument.normalized()))

    private val history = HistoryEngine(workspace, historyListener)

    /** The document as last persisted; the baseline for [dirtyBlockIds]/[deletedBlockIds]. */
    private var lastSavedDocument: BlockDocument = workspace.documentOf(target)

    // Coalescing bookkeeping (History-Engine-driven; see class doc).
    private var lastRawKey: String? = null
    private var lastCommitNanos: Long = 0L
    private var coalesceGeneration: Int = 0
    private var transactionCounter: Long = 0L

    /** The live, authoritative document. */
    fun document(): BlockDocument = workspace.documentOf(target)

    fun canUndo(): Boolean = history.canUndo()
    fun canRedo(): Boolean = history.canRedo()
    fun undoDepth(): Int = history.undoDepth()

    /** Read-only history, oldest first, for a future History UI (Phase 10). */
    fun history(): List<EditTransaction> = history.history()

    /**
     * Runs [mutation] against a throwaway [BlockEditorSession] seeded from the live document and
     * commits the difference as one canonical transaction. All sub-operations the caller performs
     * inside [mutation] collapse into a single undo unit — one command, one transaction (§4.14).
     *
     * @param label human-facing description for a future History UI.
     * @param coalescingKey a typing key (e.g. `"block:<id>"`); consecutive commits with the same key
     *   inside the coalescing window merge into one undo step. `null` (the default) never coalesces
     *   and forces the next keyed run to start fresh — the right choice for structural edits.
     * @return the [BlockCursor] the mutation reports (for caret placement), or `null` on a no-op.
     */
    fun mutate(
        label: String,
        coalescingKey: String? = null,
        mutation: (BlockEditorSession) -> BlockCursor,
    ): BlockCursor? {
        val before = document()
        val session = BlockEditorSession(before)
        val cursor = mutation(session)
        val after = session.document
        val ops = FlowDocumentDiff.diff(target, before, after)
        if (ops.isEmpty()) {
            // A no-op edit must not disturb the coalescing run or the history.
            return cursor
        }
        val effectiveKey = resolveCoalescingKey(coalescingKey)
        history.commit(
            EditTransaction(
                id = "flow-${transactionCounter++}",
                label = label,
                operations = ops,
                coalescingKey = effectiveKey,
            ),
        )
        return cursor
    }

    /**
     * Ends any in-progress typing run so the next edit starts a new undo unit. Called when the caret
     * leaves a block, the selection changes, focus is lost, or a structural command runs — mirroring
     * [BlockEditorSession.breakTypingGroup] but for the canonical history.
     */
    fun breakTypingGroup() {
        lastRawKey = null
        lastCommitNanos = 0L
    }

    /** Undoes the most recent transaction atomically; returns true if anything was undone. */
    fun undo(): Boolean {
        breakTypingGroup()
        return history.undo() != null
    }

    /** Redoes the most recently undone transaction; returns true if anything was redone. */
    fun redo(): Boolean {
        breakTypingGroup()
        return history.redo() != null
    }

    // --- Persistence hand-off ---------------------------------------------------------------------

    /**
     * Top-level block ids whose content is new or changed since the last save. The repository also
     * deletes rows for ids in [deletedBlockIds], so removals are not listed here (§17.1).
     */
    fun dirtyBlockIds(): Set<String> = FlowDocumentDiff.dirtyTopLevelIds(lastSavedDocument, document())

    /** Top-level block ids present at last save but gone now — their Room rows must be deleted. */
    fun deletedBlockIds(): Set<String> {
        val current = document().blocks.mapTo(HashSet()) { it.id }
        return lastSavedDocument.blocks.mapNotNullTo(linkedSetOf()) { it.id.takeIf { id -> id !in current } }
    }

    fun hasUnsavedChanges(): Boolean = document() != lastSavedDocument

    /** Marks the live document as persisted; resets the dirty/deleted baseline. */
    fun markSaved() {
        lastSavedDocument = document()
    }

    /**
     * Applies a remote snapshot with block-safe convergence, preserving locally-dirty top-level
     * blocks (§14.7 — this is last-saved-writer-wins block merge, **not** a CRDT). Undo history is
     * conservatively cleared because remote state forked the timeline. Returns true if the document
     * changed.
     */
    fun mergeRemote(remote: BlockDocument): Boolean {
        val before = document()
        val session = BlockEditorSession(before).apply {
            dirtyBlockIds += this@FlowEditController.dirtyBlockIds()
            deletedBlockIds += this@FlowEditController.deletedBlockIds()
        }
        if (!session.mergeRemote(remote)) return false
        val merged = session.document
        if (merged == before) return false
        workspace.setDocument(target, merged)
        history.clear()
        breakTypingGroup()
        // Remote convergence is the new persisted baseline for anything it accepted cleanly.
        lastSavedDocument = merged
        return true
    }

    private fun resolveCoalescingKey(rawKey: String?): String? {
        val now = nanoTime()
        if (rawKey == null) {
            // Structural edit: never coalesces, and forces the next typing run to open a new unit.
            lastRawKey = null
            lastCommitNanos = 0L
            return null
        }
        val continues = rawKey == lastRawKey && now - lastCommitNanos <= COALESCE_WINDOW_NANOS
        if (!continues) coalesceGeneration++
        lastRawKey = rawKey
        lastCommitNanos = now
        return "$rawKey#$coalesceGeneration"
    }

    companion object {
        /** Continuous typing within this window in one block collapses to a single undo unit. */
        const val COALESCE_WINDOW_NANOS = 1_200_000_000L
    }
}
