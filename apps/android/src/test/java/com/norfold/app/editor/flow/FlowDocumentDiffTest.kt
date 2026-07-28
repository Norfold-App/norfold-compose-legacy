package com.norfold.app.editor.flow

import com.norfold.app.domain.BlockDocument
import com.norfold.app.domain.DocumentBlock
import com.norfold.app.domain.HeadingBlock
import com.norfold.app.domain.InlineText
import com.norfold.app.domain.ParagraphBlock
import com.norfold.app.editor.history.EditTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 1 coverage for [FlowDocumentDiff] — the seam that turns a `before → after` top-level block
 * change into canonical History operations. Proves the core invariant (applying the diff to `before`
 * yields `after`) across insert / remove / replace / move / reorder / mixed cases, so the History
 * Engine can commit and invert any Flow mutation.
 */
class FlowDocumentDiffTest {

    private val target = EditTarget("note:diff")

    private fun para(id: String, text: String = ""): ParagraphBlock =
        ParagraphBlock(id = id, content = if (text.isEmpty()) emptyList() else listOf(InlineText(text)))

    private fun heading(id: String, text: String) =
        HeadingBlock(id = id, level = 1, content = listOf(InlineText(text)))

    private fun doc(vararg blocks: DocumentBlock) = BlockDocument(blocks.toList())

    /** Applies a diff's ops in order to [before] and returns the resulting document. */
    private fun apply(before: BlockDocument, ops: List<com.norfold.app.editor.history.EditOperation>): BlockDocument =
        ops.fold(before) { acc, op -> op.applyTo(acc) }

    private fun assertDiffReconstructs(before: BlockDocument, after: BlockDocument) {
        val ops = FlowDocumentDiff.diff(target, before, after)
        assertEquals("diff must reconstruct `after` exactly", after.blocks, apply(before, ops).blocks)
        // And the inverse must reconstruct `before` (History undo correctness).
        val forward = apply(before, ops)
        val back = ops.asReversed().fold(forward) { acc, op -> op.inverse().applyTo(acc) }
        assertEquals("inverse diff must restore `before` exactly", before.blocks, back.blocks)
    }

    @Test fun equalDocuments_produceNoOps() {
        val d = doc(para("a", "x"), para("b", "y"))
        assertTrue(FlowDocumentDiff.diff(target, d, d).isEmpty())
    }

    @Test fun pureInsert_atEnd() {
        assertDiffReconstructs(doc(para("a")), doc(para("a"), para("b", "new")))
    }

    @Test fun pureInsert_atStart() {
        assertDiffReconstructs(doc(para("a")), doc(para("z", "new"), para("a")))
    }

    @Test fun pureRemove_middle() {
        assertDiffReconstructs(doc(para("a"), para("b"), para("c")), doc(para("a"), para("c")))
    }

    @Test fun removeAll_leavesEmpty() {
        assertDiffReconstructs(doc(para("a"), para("b")), doc())
    }

    @Test fun contentReplace_sameId() {
        assertDiffReconstructs(doc(para("a", "old")), doc(para("a", "new")))
    }

    @Test fun typeConversion_sameId_isReplace() {
        val before = doc(para("a", "text"))
        val after = doc(heading("a", "text"))
        assertDiffReconstructs(before, after)
        val ops = FlowDocumentDiff.diff(target, before, after)
        assertEquals(1, ops.size)
        assertTrue(ops.first() is com.norfold.app.editor.history.EditOperation.ReplaceBlock)
    }

    @Test fun reorder_swapAdjacent() {
        assertDiffReconstructs(doc(para("a"), para("b")), doc(para("b"), para("a")))
    }

    @Test fun reorder_moveFirstToLast() {
        assertDiffReconstructs(
            doc(para("a"), para("b"), para("c"), para("d")),
            doc(para("b"), para("c"), para("d"), para("a")),
        )
    }

    @Test fun mixed_insertRemoveReorderReplace() {
        val before = doc(para("a", "1"), para("b", "2"), para("c", "3"), para("d", "4"))
        val after = doc(para("c", "3!"), para("a", "1"), para("e", "new"), para("d", "4"))
        assertDiffReconstructs(before, after)
    }

    @Test fun fullReplacement_noSharedIds() {
        assertDiffReconstructs(doc(para("a"), para("b")), doc(para("x"), para("y"), para("z")))
    }
}
