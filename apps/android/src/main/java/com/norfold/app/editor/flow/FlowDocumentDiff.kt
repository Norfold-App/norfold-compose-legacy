package com.norfold.app.editor.flow

import com.norfold.app.domain.BlockDocument
import com.norfold.app.domain.DocumentBlock
import com.norfold.app.editor.history.EditOperation
import com.norfold.app.editor.history.EditTarget

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
        val beforeBlocks = before.blocks
        val afterBlocks = after.blocks
        if (beforeBlocks == afterBlocks) return emptyList()

        val afterIds = afterBlocks.mapTo(HashSet()) { it.id }
        val beforeIds = beforeBlocks.mapTo(HashSet()) { it.id }

        val ops = mutableListOf<EditOperation>()

        // A working list of blocks that mirrors the live document as each op is (conceptually) applied,
        // so every emitted index is valid against the state the History Engine will actually see.
        val working = beforeBlocks.toMutableList()

        // --- Phase A: remove blocks that are gone in `after` (highest index first keeps indices valid).
        run {
            var i = working.lastIndex
            while (i >= 0) {
                val block = working[i]
                if (block.id !in afterIds) {
                    ops += EditOperation.RemoveBlock(target, i, block)
                    working.removeAt(i)
                }
                i--
            }
        }

        // --- Phase B: walk target positions left to right, fixing id-at-position then content.
        for (i in afterBlocks.indices) {
            val targetBlock = afterBlocks[i]
            val currentAtI = working.getOrNull(i)

            if (currentAtI != null && currentAtI.id == targetBlock.id) {
                // Right block already at the right slot; only content may differ.
                if (currentAtI != targetBlock) {
                    ops += EditOperation.ReplaceBlock(target, i, before = currentAtI, after = targetBlock)
                    working[i] = targetBlock
                }
                continue
            }

            if (targetBlock.id !in beforeIds) {
                // A brand-new block: insert it at its final position.
                ops += EditOperation.InsertBlock(target, i, targetBlock)
                working.add(i, targetBlock)
                continue
            }

            // A kept block that is out of place: it must live at some j > i (everything < i already
            // matches `after`). Move it into position, then reconcile its content.
            val j = working.indexOfFirst { it.id == targetBlock.id }
            check(j > i) { "diff invariant: kept block ${targetBlock.id} expected after index $i but found at $j" }
            ops += EditOperation.MoveBlock(target, fromIndex = j, toIndex = i)
            val moved = working.removeAt(j)
            working.add(i, moved)
            if (moved != targetBlock) {
                ops += EditOperation.ReplaceBlock(target, i, before = moved, after = targetBlock)
                working[i] = targetBlock
            }
        }

        return ops
    }

    /**
     * The top-level block ids that differ between [saved] and [current] — new or content-changed
     * blocks. This is the precise dirty set FlowHost hands to persistence
     * ([com.norfold.app.data] `NotesRepository.saveDocument`), which additionally deletes rows for ids
     * that vanished, so removals need not be listed here.
     */
    fun dirtyTopLevelIds(saved: BlockDocument, current: BlockDocument): Set<String> {
        val savedById = saved.blocks.associateBy(DocumentBlock::id)
        return current.blocks.mapNotNullTo(linkedSetOf()) { block ->
            val old = savedById[block.id]
            if (old == null || old != block) block.id else null
        }
    }
}
