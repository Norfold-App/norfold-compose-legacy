package com.norfold.app.editor.flow

import com.norfold.app.domain.BlockDocument
import com.norfold.app.domain.BlockDocumentJson
import com.norfold.app.domain.ParagraphBlock
import com.norfold.app.domain.InlineText
import com.norfold.app.domain.UnknownBlock
import com.norfold.app.editor.history.EditTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 1 validation coverage for [FlowEditController] — the controller that routes every Flow
 * mutation through the canonical History Engine while reusing the [com.norfold.app.domain.BlockEditorSession]
 * vocabulary. Proves: structural/text edits commit as single undo units; undo/redo restore exact
 * state; typing coalesces on a deterministic clock; no-ops commit nothing; persistence dirty/deleted
 * derivation; and v:0/v:1/UnknownBlock round-trip survives an edit cycle.
 */
class FlowEditControllerTest {

    private val target = EditTarget("note:ctl")

    private fun para(id: String, text: String = ""): ParagraphBlock =
        ParagraphBlock(id = id, content = if (text.isEmpty()) emptyList() else listOf(InlineText(text)))

    private fun doc(vararg blocks: com.norfold.app.domain.DocumentBlock) = BlockDocument(blocks.toList())

    /** A controller with a controllable clock so coalescing is deterministic. */
    private class Clock(var now: Long = 0L) : () -> Long { override fun invoke(): Long = now }

    @Test fun structuralInsert_commitsOneTransaction_andUndoRestores() {
        val ctl = FlowEditController(target, doc(para("a", "one")))
        ctl.mutate("Insert block") { it.insertAfter("a", para("b", "two")) }

        assertEquals(2, ctl.document().blocks.size)
        assertEquals(1, ctl.undoDepth())
        assertTrue(ctl.canUndo())

        ctl.undo()
        assertEquals(listOf("a"), ctl.document().blocks.map { it.id })
        assertTrue(ctl.canRedo())

        ctl.redo()
        assertEquals(listOf("a", "b"), ctl.document().blocks.map { it.id })
    }

    @Test fun noOpMutation_commitsNothing() {
        val ctl = FlowEditController(target, doc(para("a", "one")))
        ctl.mutate("touch nothing") { com.norfold.app.domain.BlockCursor("a", 0) }
        assertFalse(ctl.canUndo())
        assertEquals(0, ctl.undoDepth())
    }

    @Test fun typing_sameKey_withinWindow_coalescesToOneUndoUnit() {
        val clock = Clock()
        val ctl = FlowEditController(target, doc(para("a", "")), nanoTime = clock)
        clock.now = 1_000
        ctl.mutate("type", coalescingKey = "block:a") { it.editText("a", "", "h") }
        clock.now = 100_000_000 // +0.1s
        ctl.mutate("type", coalescingKey = "block:a") { it.editText("a", "h", "he") }
        clock.now = 200_000_000 // +0.1s
        ctl.mutate("type", coalescingKey = "block:a") { it.editText("a", "he", "hel") }

        assertEquals("Three keystrokes in one block coalesce", 1, ctl.undoDepth())
        assertEquals("hel", ctl.document().blocks[0].plainText())
        ctl.undo()
        assertEquals("One undo reverts the whole typing run", "", ctl.document().blocks[0].plainText())
    }

    @Test fun typing_pastWindow_breaksIntoSeparateUndoUnits() {
        val clock = Clock()
        val ctl = FlowEditController(target, doc(para("a", "")), nanoTime = clock)
        clock.now = 0
        ctl.mutate("type", coalescingKey = "block:a") { it.editText("a", "", "h") }
        clock.now = FlowEditController.COALESCE_WINDOW_NANOS + 1 // past window
        ctl.mutate("type", coalescingKey = "block:a") { it.editText("a", "h", "he") }
        assertEquals("A pause past the window starts a new undo unit", 2, ctl.undoDepth())
    }

    @Test fun structuralEdit_breaksCoalescingRun() {
        val clock = Clock()
        val ctl = FlowEditController(target, doc(para("a", "")), nanoTime = clock)
        ctl.mutate("type", coalescingKey = "block:a") { it.editText("a", "", "h") }
        ctl.mutate("insert") { it.insertAfter("a", para("b")) } // structural, null key
        ctl.mutate("type", coalescingKey = "block:a") { it.editText("a", "h", "hi") }
        assertEquals("type, structural, type = three units", 3, ctl.undoDepth())
    }

    @Test fun breakTypingGroup_seversRun() {
        val clock = Clock()
        val ctl = FlowEditController(target, doc(para("a", "")), nanoTime = clock)
        ctl.mutate("type", coalescingKey = "block:a") { it.editText("a", "", "h") }
        ctl.breakTypingGroup()
        ctl.mutate("type", coalescingKey = "block:a") { it.editText("a", "h", "he") }
        assertEquals(2, ctl.undoDepth())
    }

    @Test fun persistence_dirtyAndDeleted_derivedFromLastSaved() {
        val ctl = FlowEditController(target, doc(para("a", "one"), para("b", "two")))
        ctl.markSaved()
        assertTrue(ctl.dirtyBlockIds().isEmpty())
        assertTrue(ctl.deletedBlockIds().isEmpty())

        ctl.mutate("edit a") { it.editText("a", "one", "one!") }
        ctl.mutate("delete b") { it.delete("b") }
        ctl.mutate("insert c") { it.insertAfter("a", para("c", "three")) }

        assertEquals(setOf("a", "c"), ctl.dirtyBlockIds())
        assertEquals(setOf("b"), ctl.deletedBlockIds())

        ctl.markSaved()
        assertTrue("After save the baseline resets", ctl.dirtyBlockIds().isEmpty())
        assertTrue(ctl.deletedBlockIds().isEmpty())
    }

    @Test fun undoRedo_restoreExactDocumentState_bytewise() {
        val start = doc(para("a", "alpha"), para("b", "beta"))
        val ctl = FlowEditController(target, start)
        val encodedStart = BlockDocumentJson.encodeDocumentPayload(ctl.document())

        ctl.mutate("edit") { it.editText("a", "alpha", "ALPHA") }
        ctl.mutate("insert") { it.insertAfter("b", para("c", "gamma")) }
        ctl.undo()
        ctl.undo()

        assertEquals(
            "Byte-exact restoration through the canonical history",
            encodedStart,
            BlockDocumentJson.encodeDocumentPayload(ctl.document()),
        )
    }

    @Test fun unknownBlock_survivesEditCycle_roundTrip() {
        // A future/unknown payload must be preserved verbatim across an edit + undo cycle.
        val futureJson = """{"v":999,"block":{"kind":"quantum","id":"u1","payload":42}}"""
        val unknown = BlockDocumentJson.decodeBlock(futureJson)
        assertTrue(unknown is UnknownBlock)
        val ctl = FlowEditController(target, doc(para("a", "one"), unknown))

        ctl.mutate("edit a") { it.editText("a", "one", "two") }
        ctl.undo()

        val preserved = ctl.document().blocks.first { it.id == "u1" }
        assertTrue(preserved is UnknownBlock)
        assertEquals(
            "UnknownBlock payload preserved verbatim",
            futureJson,
            BlockDocumentJson.encodeBlock(preserved),
        )
    }

    @Test fun mergeRemote_keepsLocalDirtyBlock_andClearsHistory() {
        val ctl = FlowEditController(target, doc(para("a", "one"), para("b", "two")))
        ctl.markSaved()
        ctl.mutate("edit a locally") { it.editText("a", "one", "LOCAL") }

        // Remote snapshot changed b and does not know the local edit to a.
        val merged = ctl.mergeRemote(doc(para("a", "one"), para("b", "REMOTE")))
        assertTrue(merged)
        assertEquals("Local dirty block a is preserved", "LOCAL", ctl.document().blocks.first { it.id == "a" }.plainText())
        assertEquals("Clean remote block b is accepted", "REMOTE", ctl.document().blocks.first { it.id == "b" }.plainText())
        assertFalse("Remote merge forks the timeline; undo history is cleared", ctl.canUndo())
    }
}
