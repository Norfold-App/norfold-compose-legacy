package com.norfold.app.ui.editor.history

import com.norfold.app.domain.BlockDocument
import com.norfold.app.domain.InlineText
import com.norfold.app.domain.ParagraphBlock
import com.norfold.app.editor.flow.FlowEditController
import com.norfold.app.editor.history.EditOperation
import com.norfold.app.editor.history.EditTarget
import com.norfold.app.editor.history.EditTransaction
import com.norfold.app.editor.history.FocusHistoryOwner
import com.norfold.app.editor.history.HistoryDirection
import com.norfold.app.editor.history.HistoryDomain
import com.norfold.app.editor.history.HistoryEngine
import com.norfold.app.editor.history.HistoryFilter
import com.norfold.app.editor.history.HistoryHost
import com.norfold.app.editor.history.HistoryMode
import com.norfold.app.editor.history.HistoryPosition
import com.norfold.app.editor.history.InMemoryEditWorkspace
import com.norfold.app.editor.history.HistoryRuntime
import com.norfold.app.editor.history.HistoryScope
import com.norfold.app.editor.presentation.AdaptivePresentationRuntime
import com.norfold.app.editor.presentation.FormFactor
import com.norfold.app.editor.recovery.InMemoryRecoverySnapshotStore
import com.norfold.app.editor.recovery.RecoveryBrowserRuntime
import com.norfold.app.editor.recovery.RecoveryReason
import com.norfold.app.editor.recovery.RecoveryRestorePrompt
import com.norfold.app.editor.recovery.RecoverySnapshotManager
import com.norfold.app.editor.surface.SurfaceCoordinator
import com.norfold.app.ui.editor.recovery.confirmTitle
import com.norfold.app.ui.editor.recovery.consequenceLine
import com.norfold.app.ui.editor.recovery.detailLine
import com.norfold.app.ui.editor.recovery.statusLabel
import com.norfold.app.ui.editor.recovery.summaryLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase-10 accessibility semantics (§37 items 72–76).
 *
 * Everything a screen reader is told about History and recovery is text, and the non-device gate cannot
 * render a Compose tree — so the announced strings are derived by pure functions in the surface files and
 * asserted here directly. That is the reason those functions exist: accessibility text reachable only by
 * rendering is accessibility text nothing in CI ever checks.
 *
 * The property under test throughout is *truthfulness*, not presence. A control announced as "Undo" while it
 * would reverse "Delete page" passes any has-a-content-description check and still misleads the one user who
 * cannot see the list to verify. So each test pins the announcement against what the command would actually
 * do at that moment.
 */
class HistoryAccessibilityPhase10Test {

    // ── Fixtures ────────────────────────────────────────────────────────────────────────────────────

    private class Focus(
        var active: Boolean = true,
        var localUndo: Int = 0,
        var localRedo: Int = 0,
    ) {
        fun owner(): FocusHistoryOwner = FocusHistoryOwner.of(
            focusId = "focus.a11y",
            canUndo = { localUndo > 0 },
            canRedo = { localRedo > 0 },
            undo = { if (localUndo > 0) { localUndo--; localRedo++; true } else false },
            redo = { if (localRedo > 0) { localRedo--; localUndo++; true } else false },
            isActive = { active },
        )
    }

    private data class Fixture(val controller: FlowEditController, val history: HistoryRuntime)

    private fun fixture(focus: Focus? = null): Fixture {
        val controller = FlowEditController(EditTarget("note:a11y"), BlockDocument(listOf(para("start", "p0"))))
        val history = HistoryRuntime(
            host = controller.historyHost(),
            surfaces = SurfaceCoordinator(),
            presentation = AdaptivePresentationRuntime(FormFactor(400, 800)),
            focusOwner = { focus?.owner()?.takeIf { focus.active } },
        )
        return Fixture(controller, history)
    }

    private fun para(text: String, id: String) = ParagraphBlock(id = id, content = listOf(InlineText(text)))

    private fun edit(f: Fixture, label: String, text: String) {
        assertTrue(f.controller.mutateDocument(label) { BlockDocument(listOf(para(text, "p0"))) })
    }

    private data class BrowserFixture(
        val controller: FlowEditController,
        val manager: RecoverySnapshotManager,
        val runtime: RecoveryBrowserRuntime,
    )

    private fun browser(): BrowserFixture {
        val controller = FlowEditController(EditTarget("note:a11y"), BlockDocument(listOf(para("start", "p0"))))
        val manager = RecoverySnapshotManager(controller.workspace, InMemoryRecoverySnapshotStore())
        return BrowserFixture(
            controller = controller,
            manager = manager,
            runtime = RecoveryBrowserRuntime(
                manager = manager,
                surfaces = SurfaceCoordinator(),
                presentation = AdaptivePresentationRuntime(FormFactor(400, 800)),
            ),
        )
    }

    private fun readyPrompt(runtime: RecoveryBrowserRuntime, id: String): RecoveryRestorePrompt.Ready {
        val prompt = runtime.requestRestore(id)
        assertTrue("a healthy point must arm the confirmation", prompt is RecoveryRestorePrompt.Ready)
        return prompt as RecoveryRestorePrompt.Ready
    }

    // ── 72. The pending undo/redo action is textual ─────────────────────────────────────────────────

    @Test
    fun undoAndRedoAnnounceTheStepTheyWouldActuallyTake() {
        val f = fixture()
        edit(f, "Typing", "one")
        edit(f, "Delete page", "two")

        val armed = f.history.availability()
        // Not "Undo": the announcement names the transaction, so a user who cannot see the list still knows
        // what the button is about to reverse.
        assertEquals("Undo Delete page", armed.undoAnnouncement())
        assertEquals("Available", availabilityStateDescription(armed.canUndo))

        assertTrue(f.history.undo().succeeded)
        val afterUndo = f.history.availability()
        assertEquals("Undo Typing", afterUndo.undoAnnouncement())
        assertEquals("Redo Delete page", afterUndo.redoAnnouncement())
    }

    @Test
    fun anUnavailableStepAnnouncesAGenericVerbAndSaysWhyItIsUnavailable() {
        val f = fixture()
        val idle = f.history.availability()
        assertFalse(idle.canUndo)
        // The announcement degrades to the plain verb rather than naming a step that does not exist.
        assertEquals("Undo", idle.undoAnnouncement())
        assertEquals("Redo", idle.redoAnnouncement())
        assertEquals("Unavailable", availabilityStateDescription(idle.canUndo))
        // Disabled is not an explanation, so the reason is separate readable text.
        assertEquals("There is nothing to undo yet.", idle.undoReason)
        assertEquals("There is nothing to redo.", idle.redoReason)
    }

    @Test
    fun theEmptyListDistinguishesNoEditsFromAFilterThatHidesThemAll() {
        val f = fixture()
        assertEquals("Nothing has been changed yet.", HistoryFilter().emptyListMessage())

        edit(f, "Typing", "one")
        val hiding = HistoryFilter(scope = HistoryScope.OneDomain(HistoryDomain.CHART))
        assertTrue(f.history.projection().filter(hiding).isEmpty)
        // It says the rest is *still there* — a filtered-out history that reads as empty is how a user
        // concludes their work was lost and starts redoing it.
        assertEquals(
            "No changes match this filter. The rest of the history is still there.",
            hiding.emptyListMessage(),
        )
    }

    // ── 73. Contextual vs global state is textual ───────────────────────────────────────────────────

    @Test
    fun theHeaderSaysWhichUndoBehaviourIsInForce() {
        val f = fixture()
        edit(f, "Typing", "one")
        assertEquals(HistoryMode.GLOBAL, f.history.availability().mode)
        assertEquals(
            "Every change to this document, newest first",
            f.history.availability().mode.summaryLine(),
        )

        f.history.setScope(HistoryScope.OneDomain(HistoryDomain.TEXT))
        assertEquals(HistoryMode.CONTEXTUAL, f.history.availability().mode)
        // The two modes reverse different things, so the difference is stated rather than left to be inferred
        // from which chips happen to look selected.
        assertEquals(
            "Filtered view — Undo applies to what is shown",
            f.history.availability().mode.summaryLine(),
        )
    }

    @Test
    fun eachRowAnnouncesItsPositionAsStateRatherThanAsPartOfItsName() {
        val f = fixture()
        edit(f, "Typing", "one")
        edit(f, "Formatting", "two")
        assertTrue(f.history.undo().succeeded)

        val entries = f.history.projection().entries
        val applied = entries.single { it.label == "Typing" }
        val undone = entries.single { it.label == "Formatting" }

        // The row's *name* is the label alone, so traversing the list does not re-read status on every row.
        assertEquals("Typing", applied.label)
        assertEquals("Current position", applied.rowStateDescription())
        assertEquals(HistoryPosition.UNDONE, undone.position)
        assertEquals("Undone", undone.rowStateDescription())
        assertTrue(applied.rowDetailLine().startsWith("Current position · "))
    }

    @Test
    fun aCrossTargetRowSaysItReachesDocumentsTheUserIsNotLookingAt() {
        val one = EditTarget("note:one")
        val two = EditTarget("note:two")
        val workspace = InMemoryEditWorkspace(
            mapOf(one to BlockDocument(emptyList()), two to BlockDocument(emptyList())),
        )
        val engine = HistoryEngine(workspace)
        val history = HistoryRuntime(
            host = HistoryHost.of(engine),
            surfaces = SurfaceCoordinator(),
            presentation = AdaptivePresentationRuntime(FormFactor(400, 800)),
        )
        engine.commit(
            EditTransaction(
                id = "t1",
                label = "Rename everywhere",
                operations = listOf(
                    EditOperation.InsertBlock(one, 0, para("A", "p0")),
                    EditOperation.InsertBlock(two, 0, para("B", "q0")),
                ),
            ),
        )

        val entry = checkNotNull(history.projection().nextUndo())
        assertTrue("this transaction spans two targets", entry.isCrossTarget)
        // Undoing this moves a document that is not on screen. Finding that out afterwards is the surprise
        // §13.4 atomicity is meant to be safe about, so it is announced beforehand.
        assertEquals("Current position. Affects 2 documents", entry.rowStateDescription())
    }

    @Test
    fun aMultiStepWalkNamesItsCostBeforeItRuns() {
        val f = fixture()
        edit(f, "Typing", "one")
        edit(f, "Formatting", "two")
        edit(f, "Delete page", "three")

        val oldest = f.history.projection().entries.first()
        val outcome = f.history.undoTo(oldest.sequence)
        val plan = checkNotNull(outcome.pendingDisclosure) { "reaching past one step must disclose first" }

        assertEquals("Undo more than one change?", plan.disclosureTitle())
        assertEquals(HistoryDirection.UNDO, plan.direction)
        assertEquals("Reaching \"Typing\" also affects 2 newer changes.", plan.disclosureSummary())
        assertNull("a single-target walk claims no extra documents", plan.extraTargetLine())
    }

    @Test
    fun theDisclosureSingularisesACountOfOne() {
        val f = fixture()
        edit(f, "Typing", "one")
        edit(f, "Formatting", "two")

        val oldest = f.history.projection().entries.first()
        val plan = checkNotNull(f.history.undoTo(oldest.sequence).pendingDisclosure)
        // "1 newer changes" reads as a bug and undermines the warning it carries.
        assertEquals("Reaching \"Typing\" also affects 1 newer change.", plan.disclosureSummary())
    }

    // ── 74. Snapshot semantics ──────────────────────────────────────────────────────────────────────

    @Test
    fun aRowSaysWhetherThePointWasNamedByTheUserOrTakenAutomatically() {
        val b = browser()
        assertNotNull(b.runtime.createNamedPoint("Before the rewrite"))
        val named = b.runtime.rows().single()
        assertEquals("Named by you", named.statusLabel())
        assertEquals("Before the rewrite", named.title)
        assertTrue(named.detailLine().startsWith("Named by you · "))
    }

    @Test
    fun anAutomaticPointSaysSoAndNamesTheReasonItWasTaken() {
        val b = browser()
        b.manager.snapshot(RecoveryReason.IMPORT)

        val row = b.runtime.rows().single()
        assertEquals("Automatic", row.statusLabel())
        // The reason is what makes an automatic point findable — "Before import" is how the user recognises
        // the moment they want back.
        assertEquals("Before import", row.title)
    }

    @Test
    fun aScopedPointSaysItCoversOnlyPartOfTheDocument() {
        val b = browser()
        assertNotNull(b.runtime.createNamedPoint("Chart only", scopeBlockIds = listOf("p0")))
        val row = b.runtime.rows().single()
        assertTrue(row.isScoped)
        assertTrue(row.detailLine().endsWith(" · Part of the document"))
    }

    @Test
    fun theStorageFooterReportsWhatIsActuallyStored() {
        val b = browser()
        assertNotNull(b.runtime.createNamedPoint("One"))
        val single = b.runtime.storageReport()
        assertEquals(1, single.snapshotCount)
        assertTrue("a single point is not '1 points'", single.summaryLine().startsWith("1 point · 1 named · "))

        assertNotNull(b.runtime.createNamedPoint("Two"))
        assertTrue(b.runtime.storageReport().summaryLine().startsWith("2 points · 2 named · "))
    }

    // ── 75. The destructive action is announced as destructive ──────────────────────────────────────

    @Test
    fun theRestoreConfirmationSaysWhatWillBeOverwrittenBeforeItHappens() {
        val b = browser()
        val created = checkNotNull(b.runtime.createNamedPoint("Yesterday"))
        val ready = readyPrompt(b.runtime, created.id)

        assertEquals("Restore \"Yesterday\"?", ready.confirmTitle())
        assertEquals("This replaces 1 document with the saved version.", ready.consequenceLine())
    }

    @Test
    fun aScopedRestoreSaysWhatItLeavesAlone() {
        val b = browser()
        val created = checkNotNull(b.runtime.createNamedPoint("Chart only", scopeBlockIds = listOf("p0")))
        val ready = readyPrompt(b.runtime, created.id)
        assertTrue(ready.isScoped)
        // The reassurance matters as much as the warning: without it a user assumes a scoped restore reverts
        // everything and declines a safe operation.
        assertEquals(
            "This replaces 1 part of the document. Everything else stays as it is now.",
            ready.consequenceLine(),
        )
    }

    // ── 76. Broken and corrupt state is stated, not silent ──────────────────────────────────────────

    @Test
    fun aDamagedPointIsAnnouncedAsDamagedRatherThanQuietlyDroppedFromTheList() {
        val b = browser()
        val healthy = checkNotNull(b.runtime.createNamedPoint("Healthy"))
        val row = b.runtime.rows().single { it.id == healthy.id }
        // Baseline: an intact point does not claim damage.
        assertEquals("Named by you", row.statusLabel())

        val damaged = row.copy(damaged = true)
        assertFalse("a damaged point must not offer Restore", damaged.isRestorable)
        assertEquals("Damaged", damaged.statusLabel())
        assertTrue(damaged.detailLine().startsWith("Damaged · "))

        val unsupported = row.copy(unsupported = true)
        assertFalse(unsupported.isRestorable)
        // Named by the user *and* unreadable: unusable comes first, because hearing "Named by you" and then
        // finding Restore disabled is the confusing order.
        assertEquals("Made by a newer version", unsupported.statusLabel())
    }

    @Test
    fun theRefusalReasonsAreSentencesRatherThanErrorCodes() {
        assertEquals(
            "This restore point is damaged and cannot be used.",
            RecoveryBrowserRuntime.DAMAGED_REASON,
        )
        assertEquals(
            "This restore point was made by a newer version of Norfold.",
            RecoveryBrowserRuntime.UNSUPPORTED_REASON,
        )
        assertTrue(RecoveryBrowserRuntime.NOT_CONFIRMED_REASON.endsWith("."))
        assertTrue(RecoveryBrowserRuntime.NO_SAFETY_POINT_REASON.endsWith("."))
    }

    @Test
    fun aBlockedFocusStepSaysWhyRatherThanFallingSilent() {
        val focus = Focus(active = true, localUndo = 0)
        val f = fixture(focus)
        edit(f, "Typing", "one")

        val availability = f.history.availability()
        assertFalse(availability.canUndo)
        assertEquals(HistoryRuntime.FOCUS_EXHAUSTED_REASON, availability.undoReason)
        assertEquals(HistoryRuntime.FOCUS_NOTHING_REDO_REASON, availability.redoReason)
        // And the generic verb, because there is no step for it to name.
        assertEquals("Undo", availability.undoAnnouncement())
    }

    @Test
    fun anActiveFocusSessionSaysTheStepBelongsToTheToolNotTheDocument() {
        val focus = Focus(active = true, localUndo = 1)
        val f = fixture(focus)
        edit(f, "Typing", "one")

        // Announcing "Undo Typing" here would be a lie the user acts on: the chord would undo a crop.
        assertEquals("Undo step in this tool", f.history.availability().undoAnnouncement())

        focus.active = false
        assertEquals("Undo Typing", f.history.availability().undoAnnouncement())
    }
}
