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
import com.norfold.app.editor.history.PersistableEditController
import com.norfold.app.editor.recovery.RecoveryReason
import com.norfold.app.editor.recovery.RecoverySnapshot
import com.norfold.app.editor.recovery.RecoverySnapshotManager
import com.norfold.app.editor.interchange.updateInterchangeFidelity

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
    override val target: EditTarget,
    initialDocument: BlockDocument,
    historyListener: HistoryListener? = null,
    private val nanoTime: () -> Long = System::nanoTime,
) : PersistableEditController {
    private var documentInvariant: (BlockDocument) -> BlockDocument = { it }

    fun setDocumentInvariant(transform: (BlockDocument) -> BlockDocument) { documentInvariant = transform }

    val workspace: EditWorkspace =
        InMemoryEditWorkspace(mapOf(target to initialDocument.normalized()))

    private val history = HistoryEngine(workspace, historyListener)
    private val recovery = RecoverySnapshotManager(workspace)

    /** The document as last persisted; the baseline for [dirtyBlockIds]/[deletedBlockIds]. */
    private var lastSavedDocument: BlockDocument = workspace.documentOf(target)

    /**
     * Advances every time [lastSavedDocument] moves, so an in-flight save can tell whether the baseline it
     * measured itself against is still the current one. See [markSavedIfCurrent].
     */
    private var baselineGeneration: Long = 0L

    // Coalescing bookkeeping (History-Engine-driven; see class doc).
    private var lastRawKey: String? = null
    private var lastCommitNanos: Long = 0L
    private var coalesceGeneration: Int = 0
    private var transactionCounter: Long = 0L

    // Composite-transaction bookkeeping (see `transaction`).
    private var batchDepth: Int = 0
    private var batchBefore: BlockDocument? = null

    /** The live, authoritative document. */
    override fun document(): BlockDocument = workspace.documentOf(target)

    fun canUndo(): Boolean = history.canUndo()
    fun canRedo(): Boolean = history.canRedo()
    fun undoDepth(): Int = history.undoDepth()

    /** Read-only history, oldest first, for a future History UI (Phase 10). */
    fun history(): List<EditTransaction> = history.history()

    /** Durable-codec pre-image required before a copy-on-write legacy table upgrade. */
    fun snapshotBeforeMigration(label: String): RecoverySnapshot =
        recovery.snapshot(RecoveryReason.MIGRATION, listOf(target), label)

    fun recoverySnapshots(): List<RecoverySnapshot> = recovery.snapshots()

    fun snapshotBeforeDestructiveEdit(label: String): RecoverySnapshot =
        recovery.snapshot(RecoveryReason.DESTRUCTIVE_EDIT, listOf(target), label)

    fun snapshotBeforeImport(label: String): RecoverySnapshot =
        recovery.snapshot(RecoveryReason.IMPORT, listOf(target), label)

    fun snapshotBeforeRawSource(label: String): RecoverySnapshot =
        recovery.snapshot(RecoveryReason.RAW_SOURCE, listOf(target), label)

    /** Canonical whole-artifact mutation seam for durable editor metadata such as Phase-7 discussion. */
    fun mutateDocument(label: String, edit: (BlockDocument) -> BlockDocument): Boolean {
        val before = document()
        val after = documentInvariant(edit(before).normalized()).normalized().updateInterchangeFidelity(before)
        if (batchDepth > 0) {
            if (after != before) workspace.setDocument(target, after)
            return after != before
        }
        val ops = FlowDocumentDiff.diff(target, before, after)
        if (ops.isEmpty()) return false
        history.commit(
            EditTransaction(
                id = "flow-${transactionCounter++}",
                label = label,
                operations = ops,
                coalescingKey = resolveCoalescingKey(null),
            ),
        )
        return true
    }

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
        afterTransform: (BlockDocument) -> BlockDocument = { it },
        mutation: (BlockEditorSession) -> BlockCursor,
    ): BlockCursor? {
        val before = document()
        val session = BlockEditorSession(before)
        val cursor = mutation(session)
        val after = documentInvariant(afterTransform(session.document).normalized()).normalized().updateInterchangeFidelity(before)
        if (batchDepth > 0) {
            // Inside a composite: apply to the live document now, but let the enclosing `transaction`
            // record the single transaction covering the whole composite.
            if (after != before) workspace.setDocument(target, after)
            return cursor
        }
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
     * Runs [body] as one **composite transaction**: every [mutate] inside it applies to the live document
     * immediately, but only a single [EditTransaction] labelled [label] is recorded, covering the whole
     * composite. One user-facing action is therefore always one undo step even when it is assembled from
     * several vocabulary operations (§4.14) — the slash resolver, which removes the typed `/query` *and*
     * runs the chosen command, is the motivating case.
     *
     * Nesting is supported (only the outermost call records). If [body] throws, the document is restored to
     * its pre-composite state so a half-applied composite is never observable, matching the History Engine's
     * own all-or-nothing guarantee.
     */
    fun <T> transaction(label: String, body: () -> T): T {
        if (batchDepth == 0) batchBefore = document()
        batchDepth++
        val result = try {
            body()
        } catch (t: Throwable) {
            batchDepth--
            if (batchDepth == 0) {
                batchBefore?.let { workspace.setDocument(target, it) }
                batchBefore = null
            }
            throw t
        }
        batchDepth--
        if (batchDepth == 0) {
            val before = batchBefore ?: document()
            batchBefore = null
            val ops = FlowDocumentDiff.diff(target, before, document())
            if (ops.isNotEmpty()) {
                // The body already advanced the live document so each step could see the previous one's
                // result. Rewind to the pre-composite state so the History Engine applies the composite's
                // operations exactly once — committing on top of the advanced document would replay them.
                workspace.setDocument(target, before)
                history.commit(
                    EditTransaction(
                        id = "flow-${transactionCounter++}",
                        label = label,
                        operations = ops,
                        coalescingKey = resolveCoalescingKey(null),
                    ),
                )
            }
        }
        return result
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
    override fun dirtyBlockIds(): Set<String> =
        FlowDocumentDiff.dirtyTopLevelIds(lastSavedDocument, document())

    /** Top-level block ids present at last save but gone now — their Room rows must be deleted. */
    override fun deletedBlockIds(): Set<String> {
        val current = document().blocks.mapTo(HashSet()) { it.id }
        return lastSavedDocument.blocks.mapNotNullTo(linkedSetOf()) { it.id.takeIf { id -> id !in current } }
    }

    override fun hasUnsavedChanges(): Boolean = document() != lastSavedDocument

    /**
     * The current baseline generation. A save attempt reads this before starting and hands it back to
     * [markSavedIfCurrent] on success, which is how a completion that raced a remote merge is recognised as
     * describing a baseline that no longer exists.
     */
    override fun baselineGeneration(): Long = baselineGeneration

    /** Marks the live document as persisted; resets the dirty/deleted baseline. */
    fun markSaved() {
        setBaseline(document())
    }

    /**
     * Marks [persisted] — the exact snapshot the repository accepted — as the new baseline.
     *
     * Persistence is asynchronous, so the live document can already be several keystrokes ahead of what was
     * written. Baselining on the live document instead would mark those keystrokes saved without ever having
     * sent them, and a process death then loses them silently. Baselining on the accepted snapshot leaves them
     * dirty, so the next save picks them up.
     */
    fun markSaved(persisted: BlockDocument) {
        setBaseline(persisted)
    }

    /**
     * Marks [persisted] as the baseline **only if** the baseline has not moved since [expectedGeneration].
     * Returns true when it was applied.
     *
     * This is the stale-completion guard. A write started against baseline P can complete after a remote
     * snapshot has already advanced the baseline to R; applying P then would declare content persisted that R
     * never contained, and the local edits R protected would silently go clean. Declining leaves everything
     * dirty, so the next attempt reconciles against the current baseline — one redundant write is a far cheaper
     * outcome than losing an edit.
     */
    override fun markSavedIfCurrent(persisted: BlockDocument, expectedGeneration: Long): Boolean {
        if (expectedGeneration != baselineGeneration) return false
        setBaseline(persisted)
        return true
    }

    private fun setBaseline(next: BlockDocument) {
        lastSavedDocument = next
        baselineGeneration++
    }

    /**
     * Applies a remote snapshot with block-safe convergence, preserving locally-dirty top-level
     * blocks (§14.7 — this is last-saved-writer-wins block merge, **not** a CRDT). Undo history is
     * conservatively cleared because remote state forked the timeline. Returns true if the document
     * changed.
     *
     * The **working** document becomes the merge result — remote's clean changes plus the local changes that
     * were protected from it. The **baseline** becomes the accepted remote snapshot, which is the only document
     * anyone has evidence for. Baselining the merge result instead (the defect this replaces) declared the
     * protected local edits persisted purely because the merge kept them, so a process death lost content that
     * had never been written and had stopped being marked dirty.
     *
     * Diffing working-against-remote then re-derives exactly the protected changes as dirty, while a remote
     * block the user had not touched is clean on both sides and stays clean. Where remote turns out to carry
     * the same content as a local dirty block, the diff finds no difference and that block legitimately goes
     * clean — the content genuinely is persisted now.
     */
    fun mergeRemote(remote: BlockDocument): Boolean {
        val before = document()
        val normalizedRemote = remote.normalized()
        val session = BlockEditorSession(before).apply {
            dirtyBlockIds += this@FlowEditController.dirtyBlockIds()
            deletedBlockIds += this@FlowEditController.deletedBlockIds()
        }
        val documentChanged = session.mergeRemote(remote) && session.document != before
        if (documentChanged) {
            workspace.setDocument(target, session.document)
            history.clear()
            breakTypingGroup()
        }
        // The baseline advances even when the working document does not, because it is a statement about what
        // is *persisted*, not about what is shown. A remote snapshot that already matches a locally-dirty block
        // is exactly how that block legitimately goes clean; without this the editor would keep resaving
        // content the server already has. Skipped when remote matches the baseline already — the echo of our
        // own write must not churn the generation and invalidate a save that is still in flight.
        if (normalizedRemote != lastSavedDocument) setBaseline(normalizedRemote)
        return documentChanged
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
