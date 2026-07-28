package com.norfold.app.editor.recovery

import com.norfold.app.domain.BlockDocument
import com.norfold.app.domain.CodeBlock
import com.norfold.app.domain.HeadingBlock
import com.norfold.app.domain.InlineText
import com.norfold.app.domain.ParagraphBlock
import com.norfold.app.domain.TableBlock
import com.norfold.app.domain.TableCell
import com.norfold.app.domain.UnknownBlock
import com.norfold.app.editor.history.EditTarget
import com.norfold.app.editor.history.InMemoryEditWorkspace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 0 D3 validation-gate coverage: recovery snapshot create/restore proven against real documents
 * (spec §13.7). These tests are the recovery-protection proof surface — no later-phase migration may run
 * until they pass. Round trips go through the production [com.norfold.app.domain.BlockDocumentJson] codec.
 */
class RecoverySnapshotManagerTest {

    private val noteA = EditTarget("note:1")
    private val noteB = EditTarget("note:2")

    private fun para(id: String, text: String) =
        ParagraphBlock(id = id, content = listOf(InlineText(text)))

    /** A deterministic id generator so snapshot ids are predictable in tests. */
    private class SeqIds {
        private var n = 0
        fun next(): String = "snap-${n++}"
    }

    private fun manager(
        workspace: InMemoryEditWorkspace,
        store: RecoverySnapshotStore = InMemoryRecoverySnapshotStore(),
        ids: SeqIds = SeqIds(),
        now: Long = 1000L,
    ) = RecoverySnapshotManager(workspace, store, clock = { now }, idGenerator = ids::next)

    // --- Create + restore on real documents ---------------------------------------------------------

    @Test fun snapshot_thenMutate_thenRestore_recoversRealDocument() {
        val original = BlockDocument(listOf(para("p1", "hello"), HeadingBlock(id = "h1", content = listOf(InlineText("Title")))))
        val workspace = InMemoryEditWorkspace(mapOf(noteA to original))
        val mgr = manager(workspace)

        val snap = mgr.snapshot(RecoveryReason.MANUAL_CHECKPOINT, listOf(noteA))

        // Simulate a destructive edit.
        workspace.setDocument(noteA, BlockDocument(listOf(para("p9", "wiped"))))
        assertNotEquals(original, workspace.documentOf(noteA))

        val restored = mgr.restore(snap)
        assertEquals(setOf(noteA), restored)
        assertEquals("Restored document equals the original after codec round trip", original.normalized(), workspace.documentOf(noteA))
    }

    @Test fun restore_reconstructsComplexBlocks_throughCodec() {
        val table = TableBlock(
            id = "t1",
            headers = listOf(TableCell(listOf(InlineText("H")))),
            rows = listOf(listOf(TableCell(listOf(InlineText("v"))))),
        )
        val code = CodeBlock(id = "c1", language = "kotlin", code = "val x = 1")
        val doc = BlockDocument(listOf(table, code))
        val workspace = InMemoryEditWorkspace(mapOf(noteA to doc))
        val mgr = manager(workspace)

        val snap = mgr.snapshot(RecoveryReason.MIGRATION, listOf(noteA))
        workspace.setDocument(noteA, BlockDocument(listOf(para("x", "gone"))))
        mgr.restore(snap)

        assertEquals(doc.normalized(), workspace.documentOf(noteA))
    }

    @Test fun restore_preservesUnknownBlockPayloadVerbatim() {
        val unknown = UnknownBlock(id = "u1", rawJson = """{"v":99,"block":{"kind":"future","id":"u1","data":42}}""")
        val doc = BlockDocument(listOf(para("p1", "before"), unknown))
        val workspace = InMemoryEditWorkspace(mapOf(noteA to doc))
        val mgr = manager(workspace)

        val snap = mgr.snapshot(RecoveryReason.MIGRATION, listOf(noteA))
        workspace.setDocument(noteA, BlockDocument(listOf(para("z", "z"))))
        mgr.restore(snap)

        val recovered = workspace.documentOf(noteA).blocks.filterIsInstance<UnknownBlock>().single()
        assertEquals("Unknown payload survives snapshot round trip", unknown.rawJson, recovered.rawJson)
    }

    // --- Cross-target snapshots restore as one unit -------------------------------------------------

    @Test fun snapshot_spansMultipleTargets_andRestoresAll() {
        val docA = BlockDocument(listOf(para("a1", "A")))
        val docB = BlockDocument(listOf(para("b1", "B")))
        val workspace = InMemoryEditWorkspace(mapOf(noteA to docA, noteB to docB))
        val mgr = manager(workspace)

        val snap = mgr.snapshot(RecoveryReason.MIGRATION, listOf(noteA, noteB))
        assertEquals(2, snap.documents.size)

        workspace.setDocument(noteA, BlockDocument(listOf(para("x", "x"))))
        workspace.setDocument(noteB, BlockDocument(listOf(para("y", "y"))))

        val restored = mgr.restore(snap)
        assertEquals(setOf(noteA, noteB), restored)
        assertEquals(docA.normalized(), workspace.documentOf(noteA))
        assertEquals(docB.normalized(), workspace.documentOf(noteB))
    }

    @Test fun snapshot_defaultsToAllKnownTargets() {
        val workspace = InMemoryEditWorkspace(
            mapOf(noteA to BlockDocument(listOf(para("a", "A"))), noteB to BlockDocument(listOf(para("b", "B")))),
        )
        val mgr = manager(workspace)
        val snap = mgr.snapshot(RecoveryReason.MANUAL_CHECKPOINT)
        assertEquals(setOf(noteA.id, noteB.id), snap.documents.keys)
    }

    // --- guarded(): risky-op wrapper auto-restores on failure ---------------------------------------

    @Test fun guarded_restoresWhenBlockThrows() {
        val original = BlockDocument(listOf(para("p1", "safe")))
        val workspace = InMemoryEditWorkspace(mapOf(noteA to original))
        val mgr = manager(workspace)

        val boom = assertThrows(IllegalStateException::class.java) {
            mgr.guarded(RecoveryReason.DESTRUCTIVE_EDIT, listOf(noteA)) {
                // Partially apply a destructive change, then fail.
                workspace.setDocument(noteA, BlockDocument(listOf(para("p2", "corrupt"))))
                throw IllegalStateException("migration failed midway")
            }
        }
        assertEquals("migration failed midway", boom.message)
        assertEquals("guarded restores the pre-op document on failure", original.normalized(), workspace.documentOf(noteA))
    }

    @Test fun guarded_keepsResultAndDocumentWhenBlockSucceeds() {
        val workspace = InMemoryEditWorkspace(mapOf(noteA to BlockDocument(listOf(para("p1", "v1")))))
        val mgr = manager(workspace)

        val result = mgr.guarded(RecoveryReason.MIGRATION, listOf(noteA)) {
            workspace.setDocument(noteA, BlockDocument(listOf(para("p1", "v2"))))
            "ok"
        }
        assertEquals("ok", result)
        assertEquals("v2", workspace.documentOf(noteA).plainText())
    }

    // --- restoreById / restoreLatest ----------------------------------------------------------------

    @Test fun restoreById_findsStoredSnapshot() {
        val workspace = InMemoryEditWorkspace(mapOf(noteA to BlockDocument(listOf(para("p1", "orig")))))
        val mgr = manager(workspace)
        val snap = mgr.snapshot(RecoveryReason.MANUAL_CHECKPOINT, listOf(noteA))
        workspace.setDocument(noteA, BlockDocument(listOf(para("p2", "changed"))))

        val restored = mgr.restoreById(snap.id)
        assertEquals(setOf(noteA), restored)
        assertEquals("orig", workspace.documentOf(noteA).plainText())
        assertNull("Unknown id returns null", mgr.restoreById("does-not-exist"))
    }

    @Test fun restoreLatest_usesMostRecentSnapshot() {
        val workspace = InMemoryEditWorkspace(mapOf(noteA to BlockDocument(listOf(para("p1", "one")))))
        val mgr = manager(workspace)
        mgr.snapshot(RecoveryReason.MANUAL_CHECKPOINT, listOf(noteA))
        workspace.setDocument(noteA, BlockDocument(listOf(para("p1", "two"))))
        mgr.snapshot(RecoveryReason.MANUAL_CHECKPOINT, listOf(noteA))
        workspace.setDocument(noteA, BlockDocument(listOf(para("p1", "three"))))

        mgr.restoreLatest()
        assertEquals("Latest snapshot captured 'two'", "two", workspace.documentOf(noteA).plainText())
    }

    @Test fun restoreLatest_onEmptyStore_returnsNull() {
        val mgr = manager(InMemoryEditWorkspace(mapOf(noteA to BlockDocument())))
        assertNull(mgr.restoreLatest())
    }

    // --- Validation + store behavior ----------------------------------------------------------------

    @Test fun snapshot_emptyTargetSet_isRejected() {
        val mgr = manager(InMemoryEditWorkspace())
        assertThrows(IllegalArgumentException::class.java) {
            mgr.snapshot(RecoveryReason.MANUAL_CHECKPOINT, emptyList())
        }
    }

    @Test fun store_evictsOldestBeyondCapacity() {
        val store = InMemoryRecoverySnapshotStore(capacity = 2)
        val workspace = InMemoryEditWorkspace(mapOf(noteA to BlockDocument(listOf(para("p1", "x")))))
        val mgr = manager(workspace, store)
        val s0 = mgr.snapshot(RecoveryReason.MANUAL_CHECKPOINT, listOf(noteA))
        val s1 = mgr.snapshot(RecoveryReason.MANUAL_CHECKPOINT, listOf(noteA))
        val s2 = mgr.snapshot(RecoveryReason.MANUAL_CHECKPOINT, listOf(noteA))

        assertEquals(2, store.size)
        assertNull("Oldest snapshot evicted", store.get(s0.id))
        assertTrue(store.get(s1.id) != null)
        assertTrue(store.get(s2.id) != null)
    }

    @Test fun store_removeAndClear() {
        val store = InMemoryRecoverySnapshotStore()
        val workspace = InMemoryEditWorkspace(mapOf(noteA to BlockDocument(listOf(para("p1", "x")))))
        val mgr = manager(workspace, store)
        val snap = mgr.snapshot(RecoveryReason.MANUAL_CHECKPOINT, listOf(noteA))
        assertTrue(store.remove(snap.id))
        assertFalse(store.remove(snap.id))
        mgr.snapshot(RecoveryReason.MANUAL_CHECKPOINT, listOf(noteA))
        store.clear()
        assertTrue(store.all().isEmpty())
    }

    @Test fun snapshot_isImmutableToLaterWorkspaceEdits() {
        val workspace = InMemoryEditWorkspace(mapOf(noteA to BlockDocument(listOf(para("p1", "captured")))))
        val mgr = manager(workspace)
        val snap = mgr.snapshot(RecoveryReason.MANUAL_CHECKPOINT, listOf(noteA))
        val capturedPayload = snap.documents.getValue(noteA.id)

        // Mutating the workspace afterwards must not change the already-captured payload string.
        workspace.setDocument(noteA, BlockDocument(listOf(para("p2", "later"))))
        assertEquals(capturedPayload, snap.documents.getValue(noteA.id))
    }
}
