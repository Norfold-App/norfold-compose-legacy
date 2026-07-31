package com.norfold.app.editor.flow

import com.norfold.app.domain.BlockDocument
import com.norfold.app.domain.DocumentBlock
import com.norfold.app.editor.history.EditOperation
import com.norfold.app.editor.history.EditTarget
import com.norfold.app.editor.history.BlockDocumentDiff

/**
 * Turns a `before → after` change in one target's **top-level** block list into a deterministic,
 * ordered list of canonical [EditOperation]s (roadmap Phase 1 D9 / spec §13.1–§13.4).
 *
 * This is the seam that lets FlowHost keep the proven [com.norfold.app.domain.BlockEditorSession]
 * mutation vocabulary while retiring its private snapshot-deque undo: a mutation is run against a
 * throwaway session to compute the resulting document, and the difference is expressed as History
 * operations that the canonical [com.norfold.app.editor.history.HistoryEngine] commits and can invert.
 *
 * Granularity is the **top-level block** — the same unit the repository persists at
 * ([com.norfold.app.data] `DocumentBlockEntity`, one Room row per top-level block). A change inside a
 * nested container surfaces as a [EditOperation.ReplaceBlock] of the *top-level ancestor*, which is
 * exactly what persistence needs (the whole top-level row is rewritten) and what History needs (the
 * replaced block's `before`/`after` give a symmetric inverse).
 *
 * The produced sequence is designed to apply, in order, on top of [before] to yield [after]; that is
 * precisely how [com.norfold.app.editor.history.HistoryEngine.commit] replays it. The algorithm is
 * O(n²) in the top-level block count, which is irrelevant at document scale and keeps it obviously
 * correct rather than clever.
 */
object FlowDocumentDiff {

    /**
     * Computes the operation sequence transforming [before] into [after] for [target]. Returns an
     * empty list when the two documents are already equal (a no-op edit commits nothing).
     *
     * Invariant proven by construction and by test: applying the returned ops in order to [before]
     * produces exactly [after], and each op carries enough recorded state for a deterministic inverse.
     */
    fun diff(target: EditTarget, before: BlockDocument, after: BlockDocument): List<EditOperation> {
        return BlockDocumentDiff.diff(target, before, after)
    }

    /**
     * The top-level block ids that differ between [saved] and [current] — new or content-changed
     * blocks. This is the precise dirty set FlowHost hands to persistence
     * ([com.norfold.app.data] `NotesRepository.saveDocument`), which additionally deletes rows for ids
     * that vanished, so removals need not be listed here.
     */
    fun dirtyTopLevelIds(saved: BlockDocument, current: BlockDocument): Set<String> {
        return BlockDocumentDiff.dirtyTopLevelIds(saved, current)
    }
}
