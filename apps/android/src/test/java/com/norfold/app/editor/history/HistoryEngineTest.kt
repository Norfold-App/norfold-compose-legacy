package com.norfold.app.editor.history

import com.norfold.app.domain.BlockDocument
import com.norfold.app.domain.HeadingBlock
import com.norfold.app.domain.InlineText
import com.norfold.app.domain.ParagraphBlock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 0 D2 validation-gate coverage for the canonical [HistoryEngine] (spec §13.1–§13.4).
 *
 * Proves: deterministic op-inverse round-trip identity for every [EditOperation]; atomic (all-or-nothing)
 * commit with rollback; atomic cross-target undo/redo; single authoritative linear ordering; and typing
 * coalescing. Pure JVM — no Android, persistence, or collaboration dependency.
 */
class HistoryEngineTest {

    private fun para(id: String, text: String = ""): ParagraphBlock =
        ParagraphBlock(id = id, content = if (text.isEmpty()) emptyList() else listOf(InlineText(text)))

    private fun heading(id: String, text: String, level: Int = 1): HeadingBlock =
        HeadingBlock(id = id, level = level, content = listOf(InlineText(text)))

    private fun docOf(vararg blocks: com.norfold.app.domain.DocumentBlock) = BlockDocument(blocks.toList())

    private val noteA = EditTarget("note:A")
    private val noteB = EditTarget("note:B")

    private fun tx(id: String, vararg ops: EditOperation, coalescingKey: String? = null) =
        EditTransaction(id = id, label = id, operations = ops.toList(), coalescingKey = coalescingKey)

    // --- Deterministic inverse round-trip: op.inverse().applyTo(op.applyTo(d)) == d --------------------

    @Test fun insertBlock_inverse_roundTrips() {
        val start = docOf(para("p1", "one"))
        val op = EditOperation.InsertBlock(noteA, index = 1, block = para("p2", "two"))
        val after = op.applyTo(start)
        assertEquals(2, after.blocks.size)
        assertEquals(start, op.inverse().applyTo(after))
    }

    @Test fun removeBlock_inverse_roundTrips() {
        val removed = para("p2", "two")
        val start = docOf(para("p1", "one"), removed, para("p3", "three"))
        val op = EditOperation.RemoveBlock(noteA, index = 1, block = removed)
        val after = op.applyTo(start)
        assertEquals(listOf("p1", "p3"), after.blocks.map { it.id })
        assertEquals(start, op.inverse().applyTo(after))
    }

    @Test fun replaceBlock_inverse_roundTrips() {
        val before = para("p1", "old")
        val after = heading("p1", "new")
        val start = docOf(before, para("p2", "keep"))
        val op = EditOperation.ReplaceBlock(noteA, index = 0, before = before, after = after)
        val applied = op.applyTo(start)
        assertTrue(applied.blocks[0] is HeadingBlock)
        assertEquals(start, op.inverse().applyTo(applied))
    }

    @Test fun moveBlock_inverse_roundTrips() {
        val start = docOf(para("p1"), para("p2"), para("p3"))
        val op = EditOperation.MoveBlock(noteA, fromIndex = 0, toIndex = 2)
        val after = op.applyTo(start)
        assertEquals(listOf("p2", "p3", "p1"), after.blocks.map { it.id })
        assertEquals(start, op.inverse().applyTo(after))
    }

    // --- Precondition enforcement -------------------------------------------------------------------

    @Test fun insertBlock_outOfBounds_throws() {
        val doc = docOf(para("p1"))
        assertThrows(HistoryStateException::class.java) {
            EditOperation.InsertBlock(noteA, index = 5, block = para("p2")).applyTo(doc)
        }
    }

    @Test fun removeBlock_idMismatch_throws() {
        val doc = docOf(para("p1"))
        assertThrows(HistoryStateException::class.java) {
            EditOperation.RemoveBlock(noteA, index = 0, block = para("pX")).applyTo(doc)
        }
    }

    @Test fun insertBlock_duplicateId_throws() {
        val doc = docOf(para("p1"))
        assertThrows(HistoryStateException::class.java) {
            EditOperation.InsertBlock(noteA, index = 1, block = para("p1")).applyTo(doc)
        }
    }

    // --- Commit / undo / redo through the engine ----------------------------------------------------

    @Test fun commit_thenUndo_thenRedo_restoresState() {
        val workspace = InMemoryEditWorkspace(mapOf(noteA to docOf(para("p1", "one"))))
        val engine = HistoryEngine(workspace)

        engine.commit(tx("t1", EditOperation.InsertBlock(noteA, 1, para("p2", "two"))))
        assertEquals(2, workspace.documentOf(noteA).blocks.size)
        assertTrue(engine.canUndo())
        assertFalse(engine.canRedo())

        engine.undo()
        assertEquals(1, workspace.documentOf(noteA).blocks.size)
        assertFalse(engine.canUndo())
        assertTrue(engine.canRedo())

        engine.redo()
        assertEquals(2, workspace.documentOf(noteA).blocks.size)
        assertTrue(engine.canUndo())
        assertFalse(engine.canRedo())
    }

    @Test fun newCommit_clearsRedoStack() {
        val workspace = InMemoryEditWorkspace(mapOf(noteA to docOf(para("p1"))))
        val engine = HistoryEngine(workspace)
        engine.commit(tx("t1", EditOperation.InsertBlock(noteA, 1, para("p2"))))
        engine.undo()
        assertTrue(engine.canRedo())
        engine.commit(tx("t2", EditOperation.InsertBlock(noteA, 1, para("p3"))))
        assertFalse("A fresh edit forks history and clears redo", engine.canRedo())
    }

    @Test fun undo_onEmptyHistory_returnsNull() {
        val engine = HistoryEngine(InMemoryEditWorkspace(mapOf(noteA to docOf(para("p1")))))
        assertNull(engine.undo())
        assertNull(engine.redo())
    }

    // --- Atomic commit: all-or-nothing with rollback ------------------------------------------------

    @Test fun commit_failingOperation_rollsBackAllTargets_andLeavesHistoryUnchanged() {
        val docA = docOf(para("a1"))
        val docB = docOf(para("b1"))
        val workspace = InMemoryEditWorkspace(mapOf(noteA to docA, noteB to docB))
        val engine = HistoryEngine(workspace)

        // First op valid on A, second op invalid on B (index out of bounds) -> whole tx must roll back.
        val bad = tx(
            "bad",
            EditOperation.InsertBlock(noteA, 1, para("a2")),
            EditOperation.RemoveBlock(noteB, 9, para("bX")),
        )
        assertThrows(HistoryStateException::class.java) { engine.commit(bad) }

        assertEquals("Target A rolled back to pre-commit document", docA, workspace.documentOf(noteA))
        assertEquals("Target B untouched", docB, workspace.documentOf(noteB))
        assertFalse("Failed commit records no history", engine.canUndo())
    }

    // --- Atomic cross-target undo/redo (spec §13.4) -------------------------------------------------

    @Test fun crossTargetTransaction_undoAndRedo_areAtomicAcrossTargets() {
        val workspace = InMemoryEditWorkspace(
            mapOf(noteA to docOf(para("a1")), noteB to docOf(para("b1"))),
        )
        val engine = HistoryEngine(workspace)

        val cross = tx(
            "cross",
            EditOperation.InsertBlock(noteA, 1, para("a2")),
            EditOperation.InsertBlock(noteB, 1, para("b2")),
        )
        assertTrue(cross.isCrossTarget)
        engine.commit(cross)
        assertEquals(2, workspace.documentOf(noteA).blocks.size)
        assertEquals(2, workspace.documentOf(noteB).blocks.size)

        engine.undo()
        assertEquals("Undo reverts A", 1, workspace.documentOf(noteA).blocks.size)
        assertEquals("Undo reverts B in the same step", 1, workspace.documentOf(noteB).blocks.size)

        engine.redo()
        assertEquals(2, workspace.documentOf(noteA).blocks.size)
        assertEquals(2, workspace.documentOf(noteB).blocks.size)
    }

    @Test fun singleUndo_undoesEntireCrossTargetTransaction_notPerTarget() {
        val workspace = InMemoryEditWorkspace(
            mapOf(noteA to docOf(para("a1")), noteB to docOf(para("b1"))),
        )
        val engine = HistoryEngine(workspace)
        engine.commit(
            tx(
                "cross",
                EditOperation.ReplaceBlock(noteA, 0, para("a1"), para("a1", "edited")),
                EditOperation.ReplaceBlock(noteB, 0, para("b1"), para("b1", "edited")),
            ),
        )
        assertEquals(1, engine.undoDepth())
        engine.undo()
        assertEquals("One undo depth consumed for the whole cross-target step", 0, engine.undoDepth())
        assertEquals("", workspace.documentOf(noteA).plainText())
        assertEquals("", workspace.documentOf(noteB).plainText())
    }

    // --- Single authoritative linear ordering -------------------------------------------------------

    @Test fun undo_appliesTransactionsInReverseCommitOrder() {
        val workspace = InMemoryEditWorkspace(mapOf(noteA to docOf(para("p0"))))
        val engine = HistoryEngine(workspace)
        engine.commit(tx("t1", EditOperation.InsertBlock(noteA, 1, para("p1"))))
        engine.commit(tx("t2", EditOperation.InsertBlock(noteA, 2, para("p2"))))
        engine.commit(tx("t3", EditOperation.InsertBlock(noteA, 3, para("p3"))))
        assertEquals(listOf("p0", "p1", "p2", "p3"), workspace.documentOf(noteA).blocks.map { it.id })

        assertEquals("t3", engine.undo()!!.id)
        assertEquals(listOf("p0", "p1", "p2"), workspace.documentOf(noteA).blocks.map { it.id })
        assertEquals("t2", engine.undo()!!.id)
        assertEquals("t1", engine.undo()!!.id)
        assertEquals(listOf("p0"), workspace.documentOf(noteA).blocks.map { it.id })
    }

    // --- Typing coalescing --------------------------------------------------------------------------

    @Test fun consecutiveCommits_sameCoalescingKey_mergeIntoOneUndoUnit() {
        val workspace = InMemoryEditWorkspace(mapOf(noteA to docOf(para("p0"))))
        val engine = HistoryEngine(workspace)
        engine.commit(tx("k1", EditOperation.InsertBlock(noteA, 1, para("p1")), coalescingKey = "type"))
        engine.commit(tx("k2", EditOperation.InsertBlock(noteA, 2, para("p2")), coalescingKey = "type"))
        engine.commit(tx("k3", EditOperation.InsertBlock(noteA, 3, para("p3")), coalescingKey = "type"))

        assertEquals("Three keystrokes coalesce to a single undo step", 1, engine.undoDepth())
        engine.undo()
        assertEquals("One undo reverts the whole coalesced run", listOf("p0"), workspace.documentOf(noteA).blocks.map { it.id })
    }

    @Test fun differentCoalescingKey_breaksTheRun() {
        val workspace = InMemoryEditWorkspace(mapOf(noteA to docOf(para("p0"))))
        val engine = HistoryEngine(workspace)
        engine.commit(tx("k1", EditOperation.InsertBlock(noteA, 1, para("p1")), coalescingKey = "type"))
        engine.commit(tx("k2", EditOperation.InsertBlock(noteA, 2, para("p2")), coalescingKey = "other"))
        assertEquals(2, engine.undoDepth())
    }

    @Test fun undo_breaksCoalescingRun_soNextCommitIsSeparate() {
        val workspace = InMemoryEditWorkspace(mapOf(noteA to docOf(para("p0"))))
        val engine = HistoryEngine(workspace)
        engine.commit(tx("k1", EditOperation.InsertBlock(noteA, 1, para("p1")), coalescingKey = "type"))
        engine.undo()
        engine.commit(tx("k2", EditOperation.InsertBlock(noteA, 1, para("p2")), coalescingKey = "type"))
        assertEquals("Undo severs the coalescing run", 1, engine.undoDepth())
        engine.commit(tx("k3", EditOperation.InsertBlock(noteA, 2, para("p3")), coalescingKey = "type"))
        assertEquals("k2 and k3 still coalesce after the break", 1, engine.undoDepth())
    }

    @Test fun nullCoalescingKey_neverCoalesces() {
        val workspace = InMemoryEditWorkspace(mapOf(noteA to docOf(para("p0"))))
        val engine = HistoryEngine(workspace)
        engine.commit(tx("t1", EditOperation.InsertBlock(noteA, 1, para("p1"))))
        engine.commit(tx("t2", EditOperation.InsertBlock(noteA, 2, para("p2"))))
        assertEquals(2, engine.undoDepth())
    }

    // --- Listener + clear ---------------------------------------------------------------------------

    @Test fun clear_dropsHistoryButLeavesDocuments() {
        val workspace = InMemoryEditWorkspace(mapOf(noteA to docOf(para("p0"))))
        val engine = HistoryEngine(workspace)
        engine.commit(tx("t1", EditOperation.InsertBlock(noteA, 1, para("p1"))))
        engine.clear()
        assertFalse(engine.canUndo())
        assertFalse(engine.canRedo())
        assertEquals("Documents survive a history clear", 2, workspace.documentOf(noteA).blocks.size)
    }

    @Test fun listener_receivesCommitUndoRedoCallbacks() {
        val events = mutableListOf<String>()
        val listener = object : HistoryListener {
            override fun onCommitted(transaction: EditTransaction) { events += "commit:${transaction.id}" }
            override fun onUndone(transaction: EditTransaction) { events += "undo:${transaction.id}" }
            override fun onRedone(transaction: EditTransaction) { events += "redo:${transaction.id}" }
        }
        val workspace = InMemoryEditWorkspace(mapOf(noteA to docOf(para("p0"))))
        val engine = HistoryEngine(workspace, listener)
        engine.commit(tx("t1", EditOperation.InsertBlock(noteA, 1, para("p1"))))
        engine.undo()
        engine.redo()
        assertEquals(listOf("commit:t1", "undo:t1", "redo:t1"), events)
    }

    @Test fun inMemoryWorkspace_unknownTarget_throws() {
        val workspace = InMemoryEditWorkspace()
        assertThrows(HistoryStateException::class.java) { workspace.documentOf(noteA) }
    }

    @Test fun transaction_inverse_reversesOperationOrder() {
        val t = tx(
            "t",
            EditOperation.InsertBlock(noteA, 0, para("p1")),
            EditOperation.InsertBlock(noteA, 1, para("p2")),
        )
        val inv = t.inverse()
        assertEquals(2, inv.operations.size)
        assertTrue("Last op inverts first", inv.operations[0] is EditOperation.RemoveBlock)
        assertSame(t.origin, inv.origin)
    }
}
