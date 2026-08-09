package com.norfold.app.editor.recovery

import com.norfold.app.domain.BlockDocument
import com.norfold.app.domain.InlineText
import com.norfold.app.domain.ParagraphBlock
import com.norfold.app.editor.document.DocumentSceneController
import com.norfold.app.editor.flow.FlowEditController
import com.norfold.app.editor.history.EditTarget
import com.norfold.app.editor.history.TransactionOrigin
import com.norfold.app.editor.presentation.AdaptivePresentationRuntime
import com.norfold.app.editor.presentation.FormFactor
import com.norfold.app.editor.surface.SurfaceCoordinator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A restore is an edit, and the history has to know about it (§13.1, §13.4; §38 review).
 *
 * The defect these tests exist for: [RecoverySnapshotManager.restore] used to write documents straight into
 * the workspace. Nothing was wrong on screen — the restored content appeared — but the History Engine still
 * held inverses computed against the document that had just been replaced, and the redo stack still offered
 * to replay edits from before the restore. The next Undo would then apply a stale inverse to restored
 * content, and the two would disagree about what the document is. That is the exact failure §13.1's "one
 * authoritative history" exists to prevent, and it is invisible until a user presses Undo after restoring.
 *
 * So the property here is not "restore works" — the restore suites already prove that. It is that after a
 * restore the history is still a truthful account of the document.
 */
class RecoveryHistoryCoherenceTest {

    private fun para(text: String, id: String) = ParagraphBlock(id = id, content = listOf(InlineText(text)))

    private fun doc(text: String) = BlockDocument(listOf(para(text, "p0")))

    private fun text(document: BlockDocument): String =
        (document.blocks.single() as ParagraphBlock).content.joinToString("") { it.plainText() }

    private data class Fixture(val controller: FlowEditController, val browser: RecoveryBrowserRuntime)

    private fun fixture(): Fixture {
        val controller = FlowEditController(EditTarget("note:restore"), doc("original"))
        return Fixture(
            controller = controller,
            browser = RecoveryBrowserRuntime(
                manager = controller.recovery(),
                surfaces = SurfaceCoordinator(),
                presentation = AdaptivePresentationRuntime(FormFactor(400, 800)),
            ),
        )
    }

    private fun edit(f: Fixture, label: String, value: String) {
        assertTrue(f.controller.mutateDocument(label) { doc(value) })
    }

    /** Confirms the restore of an already-created point. */
    private fun restore(f: Fixture, id: String): RecoveryRestoreOutcome {
        val prompt = f.browser.requestRestore(id)
        assertTrue("the point must be restorable", prompt is RecoveryRestorePrompt.Ready)
        return f.browser.confirmRestore()
    }

    /**
     * Names a point over the *current* document and restores it straight back.
     *
     * Nothing changes, by construction — that is what the no-op test wants and what the others must avoid.
     */
    private fun restoreCurrentState(f: Fixture, name: String): RecoveryRestoreOutcome =
        restore(f, checkNotNull(f.browser.createNamedPoint(name)).id)

    // ── The restore is a transaction ────────────────────────────────────────────────────────────────

    @Test
    fun aRestoreCommitsAsOneTransactionOnTheCanonicalHistory() {
        val f = fixture()
        val created = checkNotNull(f.browser.createNamedPoint("Before the rewrite"))
        edit(f, "Typing", "rewritten")
        val depthBefore = f.controller.historyHost().appliedHistory().size

        assertTrue(f.browser.requestRestore(created.id) is RecoveryRestorePrompt.Ready)
        assertTrue(f.browser.confirmRestore() is RecoveryRestoreOutcome.Restored)

        val applied = f.controller.historyHost().appliedHistory()
        assertEquals("a restore adds exactly one step", depthBefore + 1, applied.size)
        assertEquals("Restore Before the rewrite", applied.last().label)
        // Tagged as a restore, so History's origin filter can tell it from something the user typed.
        assertEquals(TransactionOrigin.RECOVERY_RESTORE, applied.last().origin)
    }

    @Test
    fun undoAfterARestorePutsBackWhatTheRestoreReplacedRatherThanApplyingAStaleInverse() {
        val f = fixture()
        val created = checkNotNull(f.browser.createNamedPoint("Before the rewrite"))
        edit(f, "Typing", "rewritten")
        assertEquals("rewritten", text(f.controller.document()))

        assertTrue(f.browser.requestRestore(created.id) is RecoveryRestorePrompt.Ready)
        assertTrue(f.browser.confirmRestore() is RecoveryRestoreOutcome.Restored)
        assertEquals("original", text(f.controller.document()))

        // This is the assertion the old direct-write path failed: the top of the undo stack was "Typing",
        // whose inverse restores "original" — so undoing a restore appeared to do nothing, and the step the
        // user actually wanted back was silently consumed.
        assertNotNull(f.controller.historyHost().undo())
        assertEquals("undoing the restore returns the document the restore replaced", "rewritten", text(f.controller.document()))

        assertNotNull(f.controller.historyHost().undo())
        assertEquals("and the step before it is still reachable", "original", text(f.controller.document()))
    }

    @Test
    fun aRestoreClearsRedoLikeEveryOtherCommit() {
        val f = fixture()
        // Taken first, so restoring it genuinely rewrites the document rather than matching what is there.
        val created = checkNotNull(f.browser.createNamedPoint("Checkpoint"))
        edit(f, "Typing", "one")
        edit(f, "Typing again", "two")
        assertNotNull(f.controller.historyHost().undo())
        assertEquals("there is something to redo", 1, f.controller.historyHost().undoneHistory().size)

        assertTrue(restore(f, created.id) is RecoveryRestoreOutcome.Restored)

        // Redo after a restore would replay an edit computed against a document that no longer exists.
        assertTrue(
            "a restore is a new branch of history",
            f.controller.historyHost().undoneHistory().isEmpty(),
        )
    }

    @Test
    fun restoringSomethingIdenticalToTheLiveDocumentAddsNoHistoryStep() {
        val f = fixture()
        edit(f, "Typing", "one")
        val depth = f.controller.historyHost().appliedHistory().size

        assertTrue(restoreCurrentState(f, "Same as now") is RecoveryRestoreOutcome.Restored)

        // A no-op restore that pushed a step would give the user an Undo that does nothing — the same
        // complaint as an empty transaction anywhere else in the editor.
        assertEquals("nothing changed, so nothing is recorded", depth, f.controller.historyHost().appliedHistory().size)
    }

    // ── Undoing the restore from the browser ────────────────────────────────────────────────────────

    @Test
    fun theBrowsersOwnUndoRestoreIsAlsoAHistoryStepRatherThanAHiddenWrite() {
        val f = fixture()
        val created = checkNotNull(f.browser.createNamedPoint("Before the rewrite"))
        edit(f, "Typing", "rewritten")
        assertTrue(f.browser.requestRestore(created.id) is RecoveryRestorePrompt.Ready)
        val outcome = f.browser.confirmRestore()
        assertTrue(outcome is RecoveryRestoreOutcome.Restored)

        val depth = f.controller.historyHost().appliedHistory().size
        assertTrue(f.browser.undoRestore(outcome) is RecoveryRestoreOutcome.Restored)

        assertEquals("rewritten", text(f.controller.document()))
        assertEquals("going back is itself a step", depth + 1, f.controller.historyHost().appliedHistory().size)
        assertEquals(
            TransactionOrigin.RECOVERY_RESTORE,
            f.controller.historyHost().appliedHistory().last().origin,
        )
    }

    // ── The document host takes the same route ──────────────────────────────────────────────────────

    @Test
    fun theBoundedDocumentHostRestoresThroughItsHistoryToo() {
        // Recovery reaching the workspace behind history in *one* host would be the same defect, just harder
        // to find — so the second controller is pinned here rather than assumed to match.
        val controller = DocumentSceneController(EditTarget("doc:restore"), doc("original"))
        val browser = RecoveryBrowserRuntime(
            manager = controller.recovery(),
            surfaces = SurfaceCoordinator(),
            presentation = AdaptivePresentationRuntime(FormFactor(400, 800)),
        )
        val created = checkNotNull(browser.createNamedPoint("Before the rewrite"))
        assertTrue(controller.mutateDocument("Typing") { session -> session.replaceBlock(para("rewritten", "p0")) })
        assertEquals("rewritten", text(controller.document()))

        assertTrue(browser.requestRestore(created.id) is RecoveryRestorePrompt.Ready)
        assertTrue(browser.confirmRestore() is RecoveryRestoreOutcome.Restored)
        assertEquals("original", text(controller.document()))

        assertEquals(
            TransactionOrigin.RECOVERY_RESTORE,
            controller.historyHost().appliedHistory().last().origin,
        )
        assertNotNull(controller.historyHost().undo())
        assertEquals("rewritten", text(controller.document()))
    }

    // ── The Phase-0 protection path is unchanged ────────────────────────────────────────────────────

    @Test
    fun aManagerWithNoHistoryStillRestoresDirectlyForTheMigrationGuardPath() {
        // Phase 0 D3: a snapshot guards a migration, which is not an undoable edit and has no history to keep
        // coherent. That path must keep working exactly as it did, or every migration guard breaks.
        val workspace = com.norfold.app.editor.history.InMemoryEditWorkspace(
            mapOf(EditTarget("note:bare") to doc("original")),
        )
        val manager = RecoverySnapshotManager(workspace)
        val snapshot = manager.snapshot(RecoveryReason.MIGRATION)
        workspace.setDocument(EditTarget("note:bare"), doc("migrated"))

        assertEquals(setOf(EditTarget("note:bare")), manager.restore(snapshot))
        assertEquals("original", text(workspace.documentOf(EditTarget("note:bare"))))
    }

    @Test
    fun aFailedRestoreRollsBackWithoutLeavingAHistoryStepForSomethingThatNeverHappened() {
        val target = EditTarget("note:rollback")
        val workspace = com.norfold.app.editor.history.InMemoryEditWorkspace(mapOf(target to doc("original")))
        var refuse = false
        var commits = 0
        val manager = RecoverySnapshotManager(
            workspace = workspace,
            historySink = { _, documents ->
                if (refuse) throw IllegalStateException("The document could not be written.")
                commits++
                documents.forEach { (t, d) -> workspace.setDocument(t, d) }
                true
            },
        )
        val snapshot = manager.snapshot(RecoveryReason.MANUAL_CHECKPOINT)
        workspace.setDocument(target, doc("later"))

        refuse = true
        val outcome = manager.restoreSafely(snapshot)
        assertTrue("a sink that refuses is reported, not thrown", outcome is RecoveryRestoreOutcome.Failed)
        assertTrue("and the workspace is put back", (outcome as RecoveryRestoreOutcome.Failed).workspaceIntact)
        assertEquals("later", text(workspace.documentOf(target)))
        // The rollback repairs the workspace; it is not an edit the user made, so it must not be committed.
        assertEquals("the rollback took the raw path", 0, commits)
        assertFalse("and the failure is reported with a reason", outcome.reason.isBlank())
    }
}
