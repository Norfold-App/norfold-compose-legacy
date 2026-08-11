package com.norfold.app.editor.recovery

import com.norfold.app.domain.BlockDocument
import com.norfold.app.domain.BlockDocumentJson
import com.norfold.app.domain.InlineText
import com.norfold.app.domain.ParagraphBlock
import com.norfold.app.editor.flags.EditorFeatureFlag
import com.norfold.app.editor.history.EditTarget
import com.norfold.app.editor.history.EditWorkspace
import com.norfold.app.editor.history.InMemoryEditWorkspace
import com.norfold.app.editor.presentation.AdaptivePresentationRuntime
import com.norfold.app.editor.presentation.FormFactor
import com.norfold.app.editor.presentation.PresentationMode
import com.norfold.app.editor.surface.SurfaceCoordinator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase-10 coverage for the restore lifecycle and the recovery browser (spec §13.7; §37 items 49–63).
 *
 * Restoring is the one operation in the editor that destroys work on purpose, so the tests here are written
 * around what a person loses when a step is skipped rather than around the shape of the API. Every restore
 * path asserts the workspace afterwards, not just the returned outcome: an outcome that says `Restored` over
 * a document that did not change would pass a weaker test and fail a user.
 *
 * The browser tests hold one line especially hard — **a filter is a view, never a deletion**. Each of them
 * checks the store's own count alongside the visible rows, because the failure being guarded against is a
 * filter implementation that "hides" a restore point by removing it.
 *
 * Pure JVM: no Android, no Compose, no persistence, no collaboration.
 */
class RecoveryRestoreTest {

    private val noteA = EditTarget("note:A")
    private val noteB = EditTarget("note:B")

    private fun para(id: String, text: String) = ParagraphBlock(id = id, content = listOf(InlineText(text)))

    private fun doc(text: String) = BlockDocument(listOf(para("p1", text)))

    private fun workspace(vararg entries: Pair<EditTarget, String>) =
        InMemoryEditWorkspace(entries.associate { (target, text) -> target to doc(text) })

    private fun textOf(workspace: EditWorkspace, target: EditTarget) = workspace.documentOf(target).plainText()

    private class Clock(private var now: Long = 1_000L) {
        fun next(): Long = now++
    }

    private class Ids {
        private var n = 0
        fun next(): String = "snap-${n++}"
    }

    private fun manager(
        workspace: EditWorkspace,
        store: RecoverySnapshotStore = InMemoryRecoverySnapshotStore(),
    ) = RecoverySnapshotManager(workspace, store, Clock()::next, Ids()::next)

    /**
     * A workspace whose writes to one target can be made to fail, standing in for a host that refuses the
     * write, a target that turned read-only, or storage that went away mid-restore.
     *
     * [refusals] is a count rather than a flag so a test can distinguish the two cases that matter: a failure
     * that has passed by the time the rollback runs, and one that is still there.
     */
    private class BrittleWorkspace(
        private val delegate: InMemoryEditWorkspace,
        private val refuse: EditTarget,
        private var refusals: Int = 0,
    ) : EditWorkspace {
        fun refuseWrites(times: Int) { refusals = times }

        override fun documentOf(target: EditTarget) = delegate.documentOf(target)

        override fun setDocument(target: EditTarget, document: BlockDocument) {
            if (refusals > 0 && target == refuse) {
                refusals--
                throw IllegalStateException(REFUSAL)
            }
            delegate.setDocument(target, document)
        }

        override fun knownTargets() = delegate.knownTargets()

        companion object {
            const val REFUSAL = "The document would not take the write"
        }
    }

    private data class Browser(
        val runtime: RecoveryBrowserRuntime,
        val manager: RecoverySnapshotManager,
        val workspace: InMemoryEditWorkspace,
        val store: RecoverySnapshotStore,
        val surfaces: SurfaceCoordinator,
        val presentation: AdaptivePresentationRuntime,
    )

    private fun browser(
        formFactor: FormFactor = FormFactor(390, 844),
        retention: RecoveryRetentionPolicy = RecoveryRetentionPolicy(),
    ): Browser {
        val workspace = workspace(noteA to "original A", noteB to "original B")
        val store = InMemoryRecoverySnapshotStore()
        val manager = manager(workspace, store)
        val surfaces = SurfaceCoordinator()
        val presentation = AdaptivePresentationRuntime(formFactor)
        return Browser(
            runtime = RecoveryBrowserRuntime(manager, surfaces, presentation, retention),
            manager = manager,
            workspace = workspace,
            store = store,
            surfaces = surfaces,
            presentation = presentation,
        )
    }

    /** An automatic point over [target], captured from whatever the workspace holds now. */
    private fun Browser.capture(target: EditTarget = noteA) =
        manager.snapshot(RecoveryReason.DESTRUCTIVE_EDIT, listOf(target))

    // --- Validation runs before anything is overwritten (§13.7) ---------------------------------------

    @Test fun aGoodRestorePointValidatesCleanlyAndNamesNothingAsWrong() {
        val workspace = workspace(noteA to "original")
        val m = manager(workspace)
        val validation = m.validate(m.snapshot(RecoveryReason.MIGRATION, listOf(noteA)))
        assertTrue(validation.isRestorable)
        assertFalse(validation.isDamaged)
        assertTrue(validation.unreadableTargetIds.isEmpty())
        assertTrue(validation.unknownTargetIds.isEmpty())
    }

    @Test fun aDamagedPayloadIsCaughtByValidationAndNotByTheRestoreThatWouldHaveOverwrittenWork() {
        val workspace = workspace(noteA to "live work")
        val m = manager(workspace)
        val damaged = RecoverySnapshot(
            id = "bad",
            reason = RecoveryReason.IMPORT,
            createdAt = 1L,
            documents = mapOf(noteA.id to "this was never a document"),
        )

        val validation = m.validate(damaged)
        assertFalse("A payload that is not a document must not read as restorable", validation.isRestorable)
        assertTrue(validation.isDamaged)
        assertEquals(listOf(noteA.id), validation.unreadableTargetIds)

        val outcome = m.restoreSafely(damaged)
        assertTrue(outcome is RecoveryRestoreOutcome.Refused)
        assertEquals("Refusing means the live document is still the live document", "live work", textOf(workspace, noteA))
    }

    @Test fun aTargetThatIsNoLongerHereIsReportedSeparatelyFromDamage() {
        val workspace = workspace(noteA to "still here")
        val m = manager(workspace)
        val point = RecoverySnapshot(
            id = "gone",
            reason = RecoveryReason.RESTRUCTURE,
            createdAt = 1L,
            documents = mapOf(noteA.id to BlockDocumentJson.encodeDocumentPayload(doc("captured"))) +
                mapOf(noteB.id to BlockDocumentJson.encodeDocumentPayload(doc("deleted note"))),
        )

        val validation = m.validate(point)
        assertFalse(validation.isRestorable)
        assertFalse("A removed document is not damage; the user did that themselves", validation.isDamaged)
        assertEquals(listOf(noteB.id), validation.unknownTargetIds)
    }

    @Test fun oneDamagedPayloadRefusesTheWholeRestoreAndNotJustItsOwnTarget() {
        val workspace = workspace(noteA to "live A", noteB to "live B")
        val m = manager(workspace)
        val half = RecoverySnapshot(
            id = "half",
            reason = RecoveryReason.FORMAT_CONVERSION,
            createdAt = 1L,
            documents = linkedMapOf(
                noteA.id to BlockDocumentJson.encodeDocumentPayload(doc("captured A")),
                noteB.id to "<not a document>",
            ),
        )

        assertTrue(m.restoreSafely(half) is RecoveryRestoreOutcome.Refused)
        assertEquals("live A", textOf(workspace, noteA))
        assertEquals("live B", textOf(workspace, noteB))
    }

    @Test fun aRefusedRestoreLeavesNoSafetyPointBehindForSomethingThatNeverHappened() {
        val workspace = workspace(noteA to "live")
        val m = manager(workspace)
        val damaged = RecoverySnapshot(
            id = "bad",
            reason = RecoveryReason.RAW_SOURCE,
            createdAt = 1L,
            documents = mapOf(noteA.id to ""),
        )

        m.restoreSafely(damaged)
        assertTrue("A checkpoint for a restore that was refused is litter", m.snapshots().isEmpty())
    }

    // --- The pre-image (§13.7) -----------------------------------------------------------------------

    @Test fun aSafetyPointIsCapturedBeforeTheWriteAndSaysWhatItCameBefore() {
        val workspace = workspace(noteA to "original")
        val m = manager(workspace)
        val point = m.createNamedSnapshot("Before the risky bit")!!
        workspace.setDocument(noteA, doc("edited since"))

        val outcome = m.restoreSafely(point) as RecoveryRestoreOutcome.Restored
        assertNotNull("A restore without a pre-image is a one-way door", outcome.safetySnapshot)
        val safety = outcome.safetySnapshot!!

        assertEquals(RecoveryReason.BEFORE_RESTORE, safety.reason)
        assertEquals("Before restoring Before the risky bit", safety.label)
        assertFalse("The pre-image is the app's own doing, not a point the user named", safety.isManual)
        assertEquals(
            "The pre-image holds what was there, not what the restore put there",
            "edited since",
            BlockDocumentJson.decodeDocumentPayload(safety.documents.getValue(noteA.id)).plainText(),
        )
        assertEquals("original", textOf(workspace, noteA))
        assertEquals(setOf(noteA), outcome.targets)
    }

    @Test fun aRestoreThatFailsPartWayThroughIsPutBackFromItsOwnPreImage() {
        val backing = workspace(noteA to "original A", noteB to "original B")
        val brittle = BrittleWorkspace(backing, refuse = noteB)
        val m = manager(brittle)
        val point = m.snapshot(RecoveryReason.DESTRUCTIVE_EDIT, listOf(noteA, noteB))
        brittle.setDocument(noteA, doc("edited A"))
        brittle.setDocument(noteB, doc("edited B"))

        // Fails once: long enough to break the restore, gone by the time the rollback runs.
        brittle.refuseWrites(1)
        val outcome = m.restoreSafely(point) as RecoveryRestoreOutcome.Failed

        assertEquals(BrittleWorkspace.REFUSAL, outcome.reason)
        assertTrue("The pre-image went back, so the workspace is whole", outcome.workspaceIntact)
        assertEquals(
            "note A was written by the failed restore and must not have stayed that way",
            "edited A",
            textOf(brittle, noteA),
        )
        assertEquals("edited B", textOf(brittle, noteB))
        assertNotNull(outcome.safetySnapshot)
    }

    @Test fun aRollbackThatCannotRunIsReportedRatherThanThrown() {
        val backing = workspace(noteA to "original A")
        val brittle = BrittleWorkspace(backing, refuse = noteA)
        val m = manager(brittle)
        val point = m.snapshot(RecoveryReason.DESTRUCTIVE_EDIT, listOf(noteA))
        brittle.setDocument(noteA, doc("edited A"))

        // Still refusing when the rollback tries: the ordinary case, since whatever blocked the write is
        // rarely gone a millisecond later.
        brittle.refuseWrites(99)
        val outcome = m.restoreSafely(point) as RecoveryRestoreOutcome.Failed

        assertEquals(BrittleWorkspace.REFUSAL, outcome.reason)
        assertFalse("Claiming the workspace is whole when nothing could be put back is the lie", outcome.workspaceIntact)
        assertNotNull("The user still needs the pre-image to try again with", outcome.safetySnapshot)
    }

    // --- Scoped restore (§13.7) ----------------------------------------------------------------------

    @Test fun aScopedRestoreRewritesItsOwnBlocksAndLeavesEveryOtherEditAlone() {
        val workspace = InMemoryEditWorkspace(
            mapOf(noteA to BlockDocument(listOf(para("p1", "one"), para("p2", "two"), para("p3", "three")))),
        )
        val m = manager(workspace)
        val point = m.snapshot(RecoveryReason.DESTRUCTIVE_EDIT, listOf(noteA), scopeBlockIds = listOf("p2"))
        workspace.setDocument(
            noteA,
            BlockDocument(listOf(para("p1", "one fixed"), para("p2", "two broken"), para("p3", "three fixed"))),
        )

        m.restore(point)

        assertEquals(
            "Restoring one block must not revert the paragraphs someone repaired afterwards",
            listOf("one fixed", "two", "three fixed"),
            workspace.documentOf(noteA).blocks.map { it.plainText() },
        )
    }

    @Test fun aScopedRestoreBringsBackADeletedBlockAtThePlaceItHeld() {
        val workspace = InMemoryEditWorkspace(
            mapOf(noteA to BlockDocument(listOf(para("p1", "one"), para("p2", "two"), para("p3", "three")))),
        )
        val m = manager(workspace)
        val point = m.snapshot(RecoveryReason.DESTRUCTIVE_EDIT, listOf(noteA), scopeBlockIds = listOf("p2"))
        workspace.setDocument(noteA, BlockDocument(listOf(para("p1", "one"), para("p3", "three"))))

        m.restore(point)

        assertEquals(
            "Deletion is the case people reach for recovery in; it has to come back where it was",
            listOf("one", "two", "three"),
            workspace.documentOf(noteA).blocks.map { it.plainText() },
        )
    }

    @Test fun aScopedBlockPastTheEndOfAShorterDocumentIsClampedRatherThanLost() {
        val workspace = InMemoryEditWorkspace(
            mapOf(
                noteA to BlockDocument(
                    listOf(para("p1", "one"), para("p2", "two"), para("p3", "three"), para("p4", "four")),
                ),
            ),
        )
        val m = manager(workspace)
        val point = m.snapshot(RecoveryReason.DESTRUCTIVE_EDIT, listOf(noteA), scopeBlockIds = listOf("p4"))
        workspace.setDocument(noteA, BlockDocument(listOf(para("p1", "one"))))

        m.restore(point)

        assertEquals(listOf("one", "four"), workspace.documentOf(noteA).blocks.map { it.plainText() })
    }

    @Test fun aScopedRestoreRecordsTheWholeDocumentSoTheScopeNarrowsWritingAndNotCapture() {
        val workspace = InMemoryEditWorkspace(
            mapOf(noteA to BlockDocument(listOf(para("p1", "one"), para("p2", "two")))),
        )
        val m = manager(workspace)
        val point = m.snapshot(RecoveryReason.DESTRUCTIVE_EDIT, listOf(noteA), scopeBlockIds = listOf("p2"))

        assertEquals(listOf("p2"), point.scopeBlockIds)
        assertTrue(point.isScoped)
        assertEquals(
            "one\ntwo",
            BlockDocumentJson.decodeDocumentPayload(point.documents.getValue(noteA.id)).plainText(),
        )
    }

    // --- Disabled means nothing is built, whatever the shipped default happens to be (§20) -----------

    @Test fun aDisabledRecoveryBrowserConstructsNothingAtAll() {
        assertTrue(EditorFeatureFlag.HISTORY_UI.defaultEnabled)
        assertTrue("the recovery engine ships on, so migrations are always snapshotted", EditorFeatureFlag.RECOVERY_SNAPSHOTS.defaultEnabled)
        var constructed = false
        assertNull(RecoveryBrowserIntegration.create(false) { constructed = true; browser().runtime })
        assertFalse("A disabled browser reads no index and registers no surface", constructed)
        assertNotNull(RecoveryBrowserIntegration.create(true) { browser().runtime })
    }

    // --- Browser surface and presentation (§5.1, §5.4) ------------------------------------------------

    @Test fun openingTheBrowserRegistersOneSurfaceAndClosingTakesItBackDown() {
        val b = browser()
        assertTrue(b.runtime.open())
        assertFalse("Opening an open browser is not a second surface", b.runtime.open())
        assertEquals(1, b.surfaces.depth)
        assertEquals(RecoveryBrowserRuntime.BROWSER_SURFACE, b.surfaces.active()?.id)
        assertEquals(PresentationMode.PHONE_SHEET, b.runtime.presentationMode())

        b.runtime.close()
        assertEquals(0, b.surfaces.depth)
        assertNull(b.runtime.presentationMode())
        assertFalse(b.runtime.state().open)
    }

    @Test fun aFormFactorChangeCarriesEveryBrowserValueVerbatim() {
        val b = browser()
        b.capture()
        b.runtime.open()
        b.runtime.setListFilter(RecoveryListFilter.AUTOMATIC)
        b.runtime.select(b.runtime.rows().first().id)
        b.runtime.openDetail()
        b.runtime.setScrollIndex(7)
        val before = b.runtime.state()

        assertEquals(PresentationMode.EXPANDED_THREE_PANEL, b.runtime.onFormFactorChanged(FormFactor(1280, 800)))

        assertEquals("Reflowing the shell is not a reset", before, b.runtime.state())
        val presented = b.presentation.stateOf(RecoveryBrowserRuntime.BROWSER_SURFACE)!!
        assertEquals(RecoveryListFilter.AUTOMATIC.name, presented.selectedTab)
        assertEquals(7, presented.scroll)
        assertEquals(before.selectedId, presented.values["selected"])
    }

    // --- Rows and filtering (§13.6: a filter is a view) -----------------------------------------------

    @Test fun rowsAreNewestFirstAndCarryEverythingARowDrawsWithoutAPayload() {
        val b = browser()
        b.capture()
        val named = b.runtime.createNamedPoint("Milestone", "before the big edit")!!

        val rows = b.runtime.rows()
        assertEquals(2, rows.size)
        assertEquals("Newest first, because that is the one people want", named.id, rows.first().id)
        assertEquals("Milestone", rows.first().title)
        assertEquals("before the big edit", rows.first().description)
        assertTrue(rows.first().isManual)
        assertTrue(rows.first().isRestorable)
        assertFalse(rows.first().isScoped)
        assertTrue(rows.first().sizeBytes > 0)
        assertEquals(listOf(noteA.id, noteB.id), rows.first().targetIds)
        assertEquals(RecoveryReason.DESTRUCTIVE_EDIT.defaultLabel, rows.last().title)
    }

    @Test fun filteringIsAViewAndNeverRemovesARestorePoint() {
        val b = browser()
        b.capture()
        b.capture()
        b.runtime.createNamedPoint("Milestone")

        b.runtime.setListFilter(RecoveryListFilter.MANUAL)
        assertEquals(listOf("Milestone"), b.runtime.rows().map { it.title })
        assertEquals("Filtering must not delete", 3, b.store.all().size)

        b.runtime.setListFilter(RecoveryListFilter.AUTOMATIC)
        assertEquals(2, b.runtime.rows().size)
        assertTrue(b.runtime.rows().none { it.isManual })
        assertEquals(3, b.store.all().size)

        b.runtime.setListFilter(RecoveryListFilter.ALL)
        assertEquals(3, b.runtime.rows().size)
        assertEquals(3, b.store.all().size)
    }

    @Test fun aSelectionTheNewFilterHidesIsDroppedRatherThanLeftPointingOffScreen() {
        val b = browser()
        b.runtime.open()
        val automatic = b.capture()
        b.runtime.select(automatic.id)
        b.runtime.openDetail()

        b.runtime.setListFilter(RecoveryListFilter.MANUAL)

        assertNull(b.runtime.state().selectedId)
        assertFalse(b.runtime.state().detailOpen)
        assertNull(b.runtime.selectedRow())
    }

    @Test fun aSelectionTheNewFilterStillShowsSurvivesTheFilterChange() {
        val b = browser()
        b.runtime.open()
        b.capture()
        val named = b.runtime.createNamedPoint("Milestone")!!
        b.runtime.select(named.id)
        b.runtime.openDetail()

        b.runtime.setListFilter(RecoveryListFilter.MANUAL)

        assertEquals(named.id, b.runtime.state().selectedId)
        assertTrue(b.runtime.state().detailOpen)
        assertEquals("Milestone", b.runtime.selectedRow()?.title)
    }

    @Test fun aRowTheListIsNotShowingCannotBeSelected() {
        val b = browser()
        val automatic = b.capture()
        b.runtime.setListFilter(RecoveryListFilter.MANUAL)

        assertFalse(b.runtime.select(automatic.id))
        assertFalse(b.runtime.select("no-such-point"))
        assertNull(b.runtime.state().selectedId)
    }

    @Test fun aNamedPointMadeUnderTheAutomaticFilterIsShownInsteadOfSelectedInvisibly() {
        val b = browser()
        b.runtime.open()
        b.capture()
        b.runtime.setListFilter(RecoveryListFilter.AUTOMATIC)

        val created = b.runtime.createNamedPoint("Milestone")!!

        assertEquals(RecoveryListFilter.ALL, b.runtime.state().listFilter)
        assertEquals(created.id, b.runtime.state().selectedId)
        assertEquals(
            "The selected point has to be one the list is drawing",
            created.id,
            b.runtime.selectedRow()?.id,
        )
    }

    @Test fun aBlankNameCreatesNothingAndChangesNoBrowserState() {
        val b = browser()
        b.runtime.open()
        b.runtime.setListFilter(RecoveryListFilter.AUTOMATIC)

        assertNull(b.runtime.createNamedPoint("   "))

        assertEquals(RecoveryListFilter.AUTOMATIC, b.runtime.state().listFilter)
        assertNull(b.runtime.state().selectedId)
        assertTrue(b.store.all().isEmpty())
        assertEquals(0L, b.runtime.metrics().namedPointsCreated)
    }

    // --- Storage accounting (§19) --------------------------------------------------------------------

    @Test fun storageAccountingSplitsTheTwoClassesAndReportsTheBudgetHonestly() {
        val b = browser()
        b.capture()
        b.capture()
        b.runtime.createNamedPoint("Milestone")

        val report = b.runtime.storageReport()
        assertEquals(3, report.snapshotCount)
        assertEquals(1, report.manualCount)
        assertEquals(2, report.automaticCount)
        assertEquals(b.store.all().sumOf { it.payloadBytes }, report.totalBytes)
        assertEquals(RecoveryRetentionPolicy.DEFAULT_MAX_TOTAL_BYTES, report.budgetBytes)
        assertFalse(report.isOverBudget)
    }

    @Test fun beingOverTheBudgetIsReportedRatherThanRoundedAway() {
        val b = browser(retention = RecoveryRetentionPolicy(maxTotalBytes = 1))
        b.capture()

        val report = b.runtime.storageReport()
        assertTrue(report.totalBytes > report.budgetBytes)
        assertTrue(report.isOverBudget)
    }

    // --- The restore is never one tap (§22) ----------------------------------------------------------

    @Test fun confirmingWithoutHavingAskedIsRefusedAndTouchesNothing() {
        val b = browser()
        b.capture()
        b.workspace.setDocument(noteA, doc("edited since"))

        val outcome = b.runtime.confirmRestore() as RecoveryRestoreOutcome.Failed

        assertEquals(RecoveryBrowserRuntime.NOT_CONFIRMED_REASON, outcome.reason)
        assertTrue(outcome.workspaceIntact)
        assertEquals("edited since", textOf(b.workspace, noteA))
        assertEquals(0L, b.runtime.metrics().restoresAttempted)
    }

    @Test fun askingForARestoreArmsTheConfirmationAndWritesNothingYet() {
        val b = browser()
        val point = b.capture()
        b.workspace.setDocument(noteA, doc("edited since"))

        val prompt = b.runtime.requestRestore(point.id) as RecoveryRestorePrompt.Ready

        assertEquals(point.id, prompt.snapshotId)
        assertEquals(listOf(noteA.id), prompt.targetIds)
        assertFalse(prompt.isScoped)
        assertEquals(0, prompt.scopedBlockCount)
        assertTrue(b.runtime.state().confirmingRestore)
        assertEquals("Arming a confirmation is not a restore", "edited since", textOf(b.workspace, noteA))
    }

    @Test fun cancellingTheConfirmationDisarmsItAndLeavesTheDocumentAlone() {
        val b = browser()
        val point = b.capture()
        b.workspace.setDocument(noteA, doc("edited since"))
        b.runtime.requestRestore(point.id)

        b.runtime.cancelRestore()

        assertFalse(b.runtime.state().confirmingRestore)
        assertEquals("edited since", textOf(b.workspace, noteA))
        val stale = b.runtime.confirmRestore() as RecoveryRestoreOutcome.Failed
        assertEquals("A tap on a re-rendered list must not restore what was cancelled", RecoveryBrowserRuntime.NOT_CONFIRMED_REASON, stale.reason)
        assertEquals("edited since", textOf(b.workspace, noteA))
    }

    @Test fun aConfirmedRestorePutsTheDocumentBackAndCountsItOnce() {
        val b = browser()
        val point = b.capture()
        b.workspace.setDocument(noteA, doc("edited since"))
        b.runtime.requestRestore(point.id)

        val outcome = b.runtime.confirmRestore() as RecoveryRestoreOutcome.Restored

        assertEquals("original A", textOf(b.workspace, noteA))
        assertEquals(setOf(noteA), outcome.targets)
        assertFalse("The confirmation is spent", b.runtime.state().confirmingRestore)
        val metrics = b.runtime.metrics()
        assertEquals(1L, metrics.restoresAttempted)
        assertEquals(1L, metrics.restoresSucceeded)
        assertEquals(0L, metrics.restoresRefused)
    }

    @Test fun aRestoreOfADamagedPointOffersNoConfirmationToPress() {
        val b = browser()
        b.store.put(
            RecoverySnapshot(
                id = "damaged",
                reason = RecoveryReason.IMPORT,
                createdAt = 1L,
                documents = mapOf(noteA.id to "<not a document>"),
            ),
        )

        val prompt = b.runtime.requestRestore("damaged")

        assertTrue(prompt is RecoveryRestorePrompt.Refused)
        assertTrue((prompt as RecoveryRestorePrompt.Refused).validation.isDamaged)
        assertFalse("A point that cannot restore must never arm the button", b.runtime.state().confirmingRestore)
        assertEquals("original A", textOf(b.workspace, noteA))
    }

    @Test fun aRestoreOfARecordThatIsNotThereSaysSoInsteadOfFailingLater() {
        val b = browser()

        val prompt = b.runtime.requestRestore("never-stored") as RecoveryRestorePrompt.Unreadable

        assertEquals("never-stored", prompt.snapshotId)
        assertEquals(RecoveryBrowserRuntime.DAMAGED_REASON, prompt.reason)
        assertFalse(b.runtime.state().confirmingRestore)
    }

    @Test fun aRestoreRefusedAtConfirmationTimeIsCountedAsRefusedAndNotAsSucceeded() {
        val b = browser()
        val point = b.manager.snapshot(RecoveryReason.DESTRUCTIVE_EDIT, listOf(noteA))
        b.runtime.requestRestore(point.id)
        // The record decays between arming and confirming: the same id, now unreadable.
        b.store.put(point.copy(documents = mapOf(noteA.id to "<not a document>")))

        val outcome = b.runtime.confirmRestore()

        assertTrue(outcome is RecoveryRestoreOutcome.Refused)
        assertEquals("original A", textOf(b.workspace, noteA))
        val metrics = b.runtime.metrics()
        assertEquals(1L, metrics.restoresAttempted)
        assertEquals(1L, metrics.restoresRefused)
        assertEquals(0L, metrics.restoresSucceeded)
    }

    // --- Undoing a restore (§22) ---------------------------------------------------------------------

    @Test fun undoingARestoreGoesBackThroughTheSameProvenPathAsAnyOtherRestore() {
        val b = browser()
        val point = b.capture()
        b.workspace.setDocument(noteA, doc("edited since"))
        b.runtime.requestRestore(point.id)
        val restored = b.runtime.confirmRestore()
        assertEquals("original A", textOf(b.workspace, noteA))

        val undone = b.runtime.undoRestore(restored)

        assertTrue(undone.succeeded)
        assertEquals("Undoing a restore returns the work the restore replaced", "edited since", textOf(b.workspace, noteA))
    }

    @Test fun undoingARefusedRestoreHasNowhereToGoAndSaysSoRatherThanGuessing() {
        val b = browser()
        val refused = RecoveryRestoreOutcome.Refused(
            RecoveryValidation(snapshotId = "bad", unreadableTargetIds = listOf(noteA.id)),
        )

        val outcome = b.runtime.undoRestore(refused) as RecoveryRestoreOutcome.Failed

        assertEquals(RecoveryBrowserRuntime.NO_SAFETY_POINT_REASON, outcome.reason)
        assertEquals("original A", textOf(b.workspace, noteA))
    }

    // --- Deleting, and Back (§5.4) -------------------------------------------------------------------

    @Test fun deletingARestorePointRemovesItFromTheListAndFromTheSelection() {
        val b = browser()
        val point = b.capture()
        b.runtime.open()
        b.runtime.select(point.id)
        b.runtime.openDetail()

        assertTrue(b.runtime.delete(point.id))

        assertTrue(b.runtime.rows().isEmpty())
        assertNull(b.runtime.state().selectedId)
        assertFalse(b.runtime.state().detailOpen)
        assertEquals(1L, b.runtime.metrics().deletions)
        assertFalse("Deleting twice is not two deletions", b.runtime.delete(point.id))
        assertEquals(1L, b.runtime.metrics().deletions)
    }

    @Test fun backUnwindsTheConfirmationThenTheDetailThenTheBrowser() {
        val b = browser()
        val point = b.capture()
        b.runtime.open()
        b.runtime.select(point.id)
        b.runtime.openDetail()
        b.runtime.requestRestore(point.id)

        assertTrue(b.runtime.back())
        assertFalse("Back cancels the confirmation before anything else", b.runtime.state().confirmingRestore)
        assertTrue(b.runtime.state().detailOpen)

        assertTrue(b.runtime.back())
        assertFalse(b.runtime.state().detailOpen)
        assertTrue(b.runtime.state().open)

        assertTrue(b.runtime.back())
        assertFalse(b.runtime.state().open)

        assertFalse("A closed browser does not swallow Back", b.runtime.back())
        assertEquals("Unwinding the browser restores nothing", "original A", textOf(b.workspace, noteA))
    }

    @Test fun closingTheBrowserDropsTheConfirmationSoItCannotBeReopenedArmed() {
        val b = browser()
        val point = b.capture()
        b.runtime.open()
        b.runtime.requestRestore(point.id)

        b.runtime.close()
        b.runtime.open()

        assertFalse(b.runtime.state().confirmingRestore)
        val outcome = b.runtime.confirmRestore() as RecoveryRestoreOutcome.Failed
        assertEquals(RecoveryBrowserRuntime.NOT_CONFIRMED_REASON, outcome.reason)
    }

    // --- Metrics carry counts and nothing else (§35) --------------------------------------------------

    @Test fun metricsCountWhatHappenedAndNeverCarryDocumentContent() {
        val b = browser()
        val point = b.capture()
        b.runtime.createNamedPoint("Milestone")
        val before = b.runtime.metrics().listBuilds
        b.runtime.rows()
        b.runtime.validate(point.id)
        b.runtime.delete(point.id)

        val metrics = b.runtime.metrics()
        assertEquals(before + 1, metrics.listBuilds)
        assertEquals(1L, metrics.validations)
        assertEquals(1L, metrics.namedPointsCreated)
        assertEquals(1L, metrics.deletions)
        // Counters are the whole payload of this type; anything readable in it would be a content leak.
        val rendered = metrics.toString()
        assertFalse("Metrics must never carry document text", rendered.contains("original"))
        assertFalse("Metrics must never carry a user's own words", rendered.contains("Milestone"))
    }

    @Test fun validatingAnUnreadableRecordAnswersNullRatherThanInventingAVerdict() {
        val b = browser()
        assertNull(b.runtime.validate("never-stored"))
        assertEquals(1L, b.runtime.metrics().validations)
    }
}
