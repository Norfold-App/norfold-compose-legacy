package com.norfold.app.collaboration

import com.norfold.app.collaboration.editor.CollaborationHistoryBridge
import com.norfold.app.collaboration.editor.EditorDomainMutationAdapter
import com.norfold.app.collaboration.editor.integrateRemote
import com.norfold.app.collaboration.protocol.SemanticBatch
import com.norfold.app.domain.BlockDocument
import com.norfold.app.domain.ParagraphBlock
import com.norfold.app.editor.history.EditOperation
import com.norfold.app.editor.history.EditTarget
import com.norfold.app.editor.history.EditTransaction
import com.norfold.app.editor.history.HistoryEngine
import com.norfold.app.editor.history.InMemoryEditWorkspace
import com.norfold.app.editor.history.TransactionOrigin
import org.junit.Assert.*
import org.junit.Test

class CollaborationHistoryIntegrationTest {
    @Test fun `remote operation updates live state without local undo entry`() {
        val target = EditTarget("note-1")
        val workspace = InMemoryEditWorkspace(mapOf(target to BlockDocument(listOf(ParagraphBlock(id = "a")))))
        val engine = HistoryEngine(workspace)
        engine.integrateRemote(EditTransaction(
            "remote", "Remote insert",
            listOf(EditOperation.InsertBlock(target, 1, ParagraphBlock(id = "remote-block"))),
            origin = TransactionOrigin.REMOTE_MERGE,
        ))
        assertEquals(listOf("a", "remote-block"), workspace.documentOf(target).blocks.map { it.id })
        assertEquals(0, engine.undoDepth())
    }

    @Test fun `local undo emits compensating semantic transaction`() {
        val target = EditTarget("note-1")
        val workspace = InMemoryEditWorkspace(mapOf(target to BlockDocument(listOf(ParagraphBlock(id = "a")))))
        val batches = mutableListOf<SemanticBatch>()
        val adapter = EditorDomainMutationAdapter { editTarget, index -> workspace.documentOf(editTarget).blocks.getOrNull(index) }
        val bridge = CollaborationHistoryBridge(adapter, batches::add)
        val engine = HistoryEngine(workspace, bridge, bridge)
        engine.commit(EditTransaction("local", "Insert", listOf(EditOperation.InsertBlock(target, 1, ParagraphBlock(id = "b")))))
        engine.undo()
        assertEquals(2, batches.size)
        assertEquals("block.insert", batches[0].operations.single().action)
        assertEquals("block.delete", batches[1].operations.single().action)
        assertEquals("b", batches[1].operations.single().payload["itemId"]?.toString()?.trim('"'))
    }

    @Test fun `remote unrelated edit does not clear local undo or redo`() {
        val target = EditTarget("note-1")
        val workspace = InMemoryEditWorkspace(mapOf(target to BlockDocument(listOf(ParagraphBlock(id = "a")))))
        val engine = HistoryEngine(workspace)
        engine.commit(EditTransaction("local", "Insert", listOf(EditOperation.InsertBlock(target, 1, ParagraphBlock(id = "b")))))
        engine.integrateRemote(EditTransaction(
            "remote", "Remote append", listOf(EditOperation.InsertBlock(target, 2, ParagraphBlock(id = "c"))),
            origin = TransactionOrigin.REMOTE_MERGE,
        ))
        assertTrue(engine.canUndo())
        engine.undo()
        assertEquals(listOf("a", "c"), workspace.documentOf(target).blocks.map { it.id })
        assertTrue(engine.canRedo())
    }

    @Test fun `cross target history transaction stays one atomic collaboration batch`() {
        val a = EditTarget("note-a")
        val b = EditTarget("note-b")
        val workspace = InMemoryEditWorkspace(mapOf(
            a to BlockDocument(listOf(ParagraphBlock(id = "a1"))),
            b to BlockDocument(listOf(ParagraphBlock(id = "b1"))),
        ))
        val batches = mutableListOf<SemanticBatch>()
        val adapter = EditorDomainMutationAdapter { target, index -> workspace.documentOf(target).blocks.getOrNull(index) }
        val bridge = CollaborationHistoryBridge(adapter, batches::add)
        val engine = HistoryEngine(workspace, bridge, bridge)
        engine.commit(EditTransaction("cross", "Cross target", listOf(
            EditOperation.InsertBlock(a, 1, ParagraphBlock(id = "a2")),
            EditOperation.InsertBlock(b, 1, ParagraphBlock(id = "b2")),
        )))
        assertEquals(1, batches.size)
        assertEquals(2, batches.single().operations.size)
        assertTrue(batches.single().atomic)
    }

    @Test fun `remote deletion makes local undo an idempotent no-op instead of a failure`() {
        val target = EditTarget("note-1")
        val a = ParagraphBlock(id = "a")
        val b = ParagraphBlock(id = "b")
        val workspace = InMemoryEditWorkspace(mapOf(target to BlockDocument(listOf(a))))
        val batches = mutableListOf<SemanticBatch>()
        val adapter = EditorDomainMutationAdapter { editTarget, index -> workspace.documentOf(editTarget).blocks.getOrNull(index) }
        val bridge = CollaborationHistoryBridge(adapter, batches::add)
        val engine = HistoryEngine(workspace, bridge, bridge)
        engine.commit(EditTransaction("local", "Insert", listOf(EditOperation.InsertBlock(target, 1, b))))
        engine.integrateRemote(EditTransaction(
            "remote", "Remote delete", listOf(EditOperation.RemoveBlock(target, 1, b)),
            origin = TransactionOrigin.REMOTE_MERGE,
        ))
        assertNotNull(engine.undo())
        assertEquals(listOf("a"), workspace.documentOf(target).blocks.map { it.id })
        assertEquals(1, batches.size)
        assertFalse(engine.canRedo())
        assertNull(engine.redo())
        assertEquals(listOf("a"), workspace.documentOf(target).blocks.map { it.id })
    }

    @Test fun `remote insertion shifts indices but local undo still targets stable block id`() {
        val target = EditTarget("note-1")
        val a = ParagraphBlock(id = "a")
        val local = ParagraphBlock(id = "local")
        val remote = ParagraphBlock(id = "remote")
        val workspace = InMemoryEditWorkspace(mapOf(target to BlockDocument(listOf(a))))
        val adapter = EditorDomainMutationAdapter { editTarget, index -> workspace.documentOf(editTarget).blocks.getOrNull(index) }
        val bridge = CollaborationHistoryBridge(adapter) { }
        val engine = HistoryEngine(workspace, bridge, bridge)
        engine.commit(EditTransaction("local", "Insert", listOf(EditOperation.InsertBlock(target, 1, local))))
        engine.integrateRemote(EditTransaction(
            "remote", "Remote prepend", listOf(EditOperation.InsertBlock(target, 0, remote)),
            origin = TransactionOrigin.REMOTE_MERGE,
        ))
        engine.undo()
        assertEquals(listOf("remote", "a"), workspace.documentOf(target).blocks.map { it.id })
    }

    @Test fun `partial conflict redo replays only the operation actually undone`() {
        val target = EditTarget("note-1")
        val a = ParagraphBlock(id = "a")
        val b = ParagraphBlock(id = "b")
        val c = ParagraphBlock(id = "c")
        val workspace = InMemoryEditWorkspace(mapOf(target to BlockDocument(listOf(a))))
        val adapter = EditorDomainMutationAdapter { editTarget, index -> workspace.documentOf(editTarget).blocks.getOrNull(index) }
        val bridge = CollaborationHistoryBridge(adapter) { }
        val engine = HistoryEngine(workspace, bridge, bridge)
        engine.commit(EditTransaction("local", "Insert two", listOf(
            EditOperation.InsertBlock(target, 1, b),
            EditOperation.InsertBlock(target, 2, c),
        )))
        engine.integrateRemote(EditTransaction(
            "remote", "Delete one", listOf(EditOperation.RemoveBlock(target, 1, b)),
            origin = TransactionOrigin.REMOTE_MERGE,
        ))
        engine.undo()
        assertEquals(listOf("a"), workspace.documentOf(target).blocks.map { it.id })
        assertTrue(engine.canRedo())
        engine.redo()
        assertEquals(listOf("a", "c"), workspace.documentOf(target).blocks.map { it.id })
    }
}
