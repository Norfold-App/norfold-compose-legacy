package com.norfold.app.editor.history

import com.norfold.app.domain.BlockDocument
import com.norfold.app.domain.ChartBlock
import com.norfold.app.domain.DocumentBlock
import com.norfold.app.domain.InlineText
import com.norfold.app.domain.ParagraphBlock
import com.norfold.app.editor.command.CommandContext
import com.norfold.app.editor.command.CommandRegistry
import com.norfold.app.editor.command.CommandResult
import com.norfold.app.editor.command.CommandState
import com.norfold.app.editor.command.CommandStatus
import com.norfold.app.editor.command.CommandSurface
import com.norfold.app.editor.flags.EditorFeatureFlag
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
 * Phase-10 coverage for [HistoryRuntime]: global vs contextual undo, disclosure before a reversal reaches
 * past what the user pointed at, stale-plan refusal, focus-session routing, and browser view state
 * (spec §13.2–§13.6; §4.1–§4.4 for the command surface).
 *
 * The runtime owns no transactions. Every test that moves history asserts against the engine it was built
 * over, so a regression that grew a second stack here would show up as the two disagreeing.
 *
 * Pure JVM: no Android, no Compose, no persistence, no collaboration.
 */
class HistoryRuntimeTest {

    private val noteA = EditTarget("note:A")
    private val noteB = EditTarget("note:B")

    private fun para(id: String, text: String = "x") =
        ParagraphBlock(id = id, content = listOf(InlineText(text)))

    private fun insert(block: DocumentBlock, target: EditTarget = noteA, index: Int = 0) =
        EditOperation.InsertBlock(target, index, block)

    private fun tx(id: String, vararg operations: EditOperation, label: String = id) =
        EditTransaction(id = id, label = label, operations = operations.toList())

    /** A focus session with a scripted local history, standing in for Image Focus or a chart editor. */
    private class Focus(
        var localUndo: Int = 0,
        var localRedo: Int = 0,
        var active: Boolean = true,
    ) : FocusHistoryOwner {
        override val focusId: String = "focus.test"
        override fun isActive(): Boolean = active
        override fun canUndoLocally(): Boolean = localUndo > 0
        override fun canRedoLocally(): Boolean = localRedo > 0
        override fun undoLocally(): Boolean {
            if (localUndo <= 0) return false
            localUndo--; localRedo++
            return true
        }
        override fun redoLocally(): Boolean {
            if (localRedo <= 0) return false
            localRedo--; localUndo++
            return true
        }
    }

    private data class Fixture(
        val runtime: HistoryRuntime,
        val engine: HistoryEngine,
        val workspace: InMemoryEditWorkspace,
        val focus: Focus,
        val presentation: AdaptivePresentationRuntime,
        val surfaces: SurfaceCoordinator,
    )

    private fun fixture(
        focus: Focus? = null,
        formFactor: FormFactor = FormFactor(390, 844),
    ): Fixture {
        // Empty rather than the BlockDocument default (one placeholder paragraph), so "the document is back
        // to where it started" is an assertion about the undo and not about the default.
        val workspace = InMemoryEditWorkspace(
            mapOf(noteA to BlockDocument(emptyList()), noteB to BlockDocument(emptyList())),
        )
        val engine = HistoryEngine(workspace)
        val surfaces = SurfaceCoordinator()
        val presentation = AdaptivePresentationRuntime(formFactor)
        val session = focus ?: Focus(active = false)
        return Fixture(
            runtime = HistoryRuntime(
                host = HistoryHost.of(engine),
                surfaces = surfaces,
                presentation = presentation,
                focusOwner = { session.takeIf { it.active } },
            ),
            engine = engine,
            workspace = workspace,
            focus = session,
            presentation = presentation,
            surfaces = surfaces,
        )
    }

    /** Commits chart → text → text, so a contextual undo has something to walk back past. */
    private fun Fixture.seed() {
        engine.commit(tx("chart", insert(ChartBlock(id = "c1"), index = 0), label = "Insert chart"))
        engine.commit(tx("text1", insert(para("p1"), index = 1), label = "Typing"))
        engine.commit(tx("text2", insert(para("p2"), index = 2), label = "Typing"))
    }

    // --- The flag is off in production, and off means nothing is constructed --------------------------

    @Test fun historyUiFlagDefaultsOffAndDisabledIntegrationConstructsNothing() {
        assertFalse(EditorFeatureFlag.HISTORY_UI.defaultEnabled)
        var constructed = false
        assertNull(HistoryIntegration.create(false) { constructed = true; fixture().runtime })
        assertFalse("A disabled History UI builds no runtime at all", constructed)
        assertNotNull(HistoryIntegration.create(true) { fixture().runtime })
    }

    // --- Global undo (§13.2) --------------------------------------------------------------------------

    @Test fun globalUndoReversesTheNewestTransactionAndReportsIt() {
        val f = fixture()
        f.seed()

        val outcome = f.runtime.undo()

        assertTrue(outcome.succeeded)
        assertEquals(HistoryRoute.GLOBAL, outcome.route)
        assertEquals(listOf(2), outcome.moved)
        assertEquals(2, f.engine.undoDepth())
        assertEquals("text2", f.engine.peekRedo()?.id)
        assertNull("A plain global undo needs no disclosure", outcome.pendingDisclosure)
    }

    @Test fun globalRedoReappliesWhatWasJustUndone() {
        val f = fixture()
        f.seed()
        f.runtime.undo()

        val outcome = f.runtime.redo()

        assertTrue(outcome.succeeded)
        assertEquals(HistoryRoute.GLOBAL, outcome.route)
        assertEquals(3, f.engine.undoDepth())
        assertEquals(0, f.engine.redoDepth())
    }

    @Test fun undoOnAnEmptyHistoryIsUnavailableWithAReasonNotASilentNoOp() {
        val f = fixture()
        val outcome = f.runtime.undo()

        assertFalse(outcome.succeeded)
        assertEquals(HistoryRoute.UNAVAILABLE, outcome.route)
        assertEquals(HistoryStepBlocker.NOTHING_RECORDED, outcome.blocker)
        assertEquals("There is nothing to undo yet.", f.runtime.availability().undoReason)
    }

    @Test fun availabilityDescribesTheActualNextStepAndUpdatesAfterIt() {
        val f = fixture()
        f.seed()

        val before = f.runtime.availability()
        assertEquals(HistoryMode.GLOBAL, before.mode)
        assertTrue(before.canUndo)
        assertFalse(before.canRedo)
        assertEquals("Undo Typing", before.undoDescription)
        assertNull(before.undoReason)

        f.runtime.undo()
        f.runtime.undo()
        val after = f.runtime.availability()

        assertEquals("Undo Insert chart", after.undoDescription)
        assertEquals("Redo Typing", after.redoDescription)
        assertTrue(after.canRedo)
    }

    // --- Contextual undo (§13.3): one history, seen through a lens -------------------------------------

    @Test fun aFilterChangesTheModeButNeverTheDocumentOrThePosition() {
        val f = fixture()
        f.seed()
        val historyBefore = f.engine.history()

        f.runtime.setScope(HistoryScope.OneDomain(HistoryDomain.CHART))

        assertEquals(HistoryMode.CONTEXTUAL, f.runtime.mode())
        assertEquals("Looking is not editing", historyBefore, f.engine.history())
        assertEquals(3, f.engine.undoDepth())
        assertEquals(1, f.runtime.visibleProjection().entries.size)
        assertEquals(3, f.runtime.projection().entries.size)
    }

    @Test fun contextualUndoRefusesUntilTheBlastRadiusHasBeenDisclosed() {
        val f = fixture()
        f.seed()
        f.runtime.setScope(HistoryScope.OneDomain(HistoryDomain.CHART))

        val refused = f.runtime.undo()

        assertFalse(refused.succeeded)
        assertEquals(HistoryRoute.UNAVAILABLE, refused.route)
        val disclosed = refused.pendingDisclosure
        assertNotNull(disclosed)
        assertEquals(listOf("text2", "text1"), disclosed!!.intervening.map { it.transactionId })
        assertEquals("chart", disclosed.requested.transactionId)
        assertEquals("Nothing moved while the disclosure is pending", 3, f.engine.undoDepth())
    }

    @Test fun theDisclosedStepRunsCompletelyOnceConfirmed() {
        val f = fixture()
        f.seed()
        f.runtime.setScope(HistoryScope.OneDomain(HistoryDomain.CHART))
        val pending = f.runtime.undo().pendingDisclosure!!

        val outcome = f.runtime.execute(pending)

        assertTrue(outcome.succeeded)
        assertEquals(listOf(2, 1, 0), outcome.moved)
        assertEquals("Every newer transaction reversed too — nothing cut from the middle", 0, f.engine.undoDepth())
        assertEquals(3, f.engine.redoDepth())
        assertTrue(f.workspace.documentOf(noteA).blocks.isEmpty())
    }

    @Test fun confirmingInlineRunsTheSameStepWithoutASecondRoundTrip() {
        val f = fixture()
        f.seed()
        f.runtime.setScope(HistoryScope.OneDomain(HistoryDomain.CHART))

        val outcome = f.runtime.undo(confirmed = true)

        assertTrue(outcome.succeeded)
        assertEquals(listOf(2, 1, 0), outcome.moved)
        assertEquals(0, f.engine.undoDepth())
    }

    @Test fun aPendingDisclosureIsHeldByTheRuntimeSoEveryRouteSeesTheSameOne() {
        val f = fixture()
        f.seed()
        f.runtime.setScope(HistoryScope.OneDomain(HistoryDomain.CHART))
        assertNull(f.runtime.pendingDisclosure())

        f.runtime.undo()
        assertNotNull("The chord and the toolbar read the same pending step", f.runtime.pendingDisclosure())

        f.runtime.dismissDisclosure()
        assertNull(f.runtime.pendingDisclosure())
        assertEquals("Dismissing a disclosure takes no step", 3, f.engine.undoDepth())
    }

    @Test fun changingTheFilterDropsAPendingStepThatNoLongerMatchesTheList() {
        val f = fixture()
        f.seed()
        f.runtime.setScope(HistoryScope.OneDomain(HistoryDomain.CHART))
        f.runtime.undo()
        assertNotNull(f.runtime.pendingDisclosure())

        f.runtime.clearFilter()

        assertNull("A plan derived under the old filter must not survive it", f.runtime.pendingDisclosure())
        assertEquals(HistoryMode.GLOBAL, f.runtime.mode())
    }

    @Test fun aFilterThatMatchesNothingSaysSoInsteadOfLookingBroken() {
        val f = fixture()
        f.seed()
        f.runtime.setScope(HistoryScope.OneBlock("never-touched"))

        val outcome = f.runtime.undo()

        assertEquals(HistoryStepBlocker.NOTHING_IN_SCOPE, outcome.blocker)
        assertEquals(
            "Nothing in the current filter was changed. Clear the filter to see the whole history.",
            f.runtime.availability().undoReason,
        )
        assertEquals(3, f.engine.undoDepth())
    }

    @Test fun undoingBackToAChosenEntryReversesEverythingNewerInOrder() {
        val f = fixture()
        f.seed()

        val plan = f.runtime.planUndoTo(0) as HistoryStepPlan.Ready
        assertEquals(listOf("text2", "text1", "chart"), plan.steps.map { it.transactionId })

        val outcome = f.runtime.undoTo(0, confirmed = true)

        assertTrue(outcome.succeeded)
        assertEquals(0, f.engine.undoDepth())
        assertEquals(listOf("chart", "text1", "text2"), f.engine.redoHistory().map { it.id }.reversed())
    }

    @Test fun undoingBackToAnEntryThatIsNotAppliedIsRefused() {
        val f = fixture()
        f.seed()
        f.runtime.undo()

        assertTrue(f.runtime.planUndoTo(2) is HistoryStepPlan.Unavailable)
        assertEquals(HistoryStepBlocker.NOTHING_IN_SCOPE, (f.runtime.planUndoTo(2) as HistoryStepPlan.Unavailable).blocker)
    }

    // --- Stale plans are refused, never applied to a stack the user never saw --------------------------

    @Test fun aPlanIsRefusedWhenHistoryMovedWhileTheDisclosureWasOpen() {
        val f = fixture()
        f.seed()
        f.runtime.setScope(HistoryScope.OneDomain(HistoryDomain.CHART))
        val shown = f.runtime.undo().pendingDisclosure!!

        // An autosave-driven edit lands while the sheet is on screen.
        f.engine.commit(tx("late", insert(para("p3"), index = 3), label = "Typing"))

        val outcome = f.runtime.execute(shown)

        assertFalse(outcome.succeeded)
        assertEquals(HistoryRuntime.STALE_PLAN_REASON, outcome.failure)
        assertEquals("Nothing was reversed against the newer stack", 4, f.engine.undoDepth())
        assertEquals(1, f.runtime.metrics().stalePlansRejected)
    }

    @Test fun aPlanIsRefusedWhenTheRequestedEntryIsNoLongerTheOneInScope() {
        val f = fixture()
        f.seed()
        f.runtime.setScope(HistoryScope.OneDomain(HistoryDomain.CHART))
        val shown = f.runtime.undo().pendingDisclosure!!

        // A second surface undid one step; the intervening list the user was shown is now wrong.
        f.engine.undo()

        val outcome = f.runtime.execute(shown)

        assertFalse(outcome.succeeded)
        assertEquals(HistoryRuntime.STALE_PLAN_REASON, outcome.failure)
        assertEquals(2, f.engine.undoDepth())
    }

    // --- Focus sessions own undo while they are open (§13.5) ------------------------------------------

    @Test fun anOpenFocusSessionTakesUndoInsteadOfTheArtifact() {
        val focus = Focus(localUndo = 2)
        val f = fixture(focus)
        f.seed()

        val outcome = f.runtime.undo()

        assertEquals(HistoryRoute.LOCAL_FOCUS, outcome.route)
        assertTrue(outcome.succeeded)
        assertEquals("The artifact history did not move", 3, f.engine.undoDepth())
        assertEquals(1, focus.localUndo)
        assertEquals(1, f.runtime.metrics().focusRoutedSteps)
    }

    @Test fun anExhaustedFocusSessionBlocksRatherThanFallingThroughToTheArtifact() {
        val focus = Focus(localUndo = 0)
        val f = fixture(focus)
        f.seed()

        val outcome = f.runtime.undo()

        assertEquals(HistoryRoute.BLOCKED_BY_FOCUS, outcome.route)
        assertFalse(outcome.succeeded)
        assertEquals(HistoryStepBlocker.FOCUS_SESSION_ACTIVE, outcome.blocker)
        assertEquals("An edit made before the session opened must not vanish", 3, f.engine.undoDepth())
        assertEquals(1, f.runtime.metrics().focusBlockedSteps)
    }

    @Test fun focusRedoRoutesLocallyAndThenBlocksWhenItRunsOut() {
        val focus = Focus(localUndo = 1)
        val f = fixture(focus)
        f.seed()
        f.runtime.undo()

        assertEquals(HistoryRoute.LOCAL_FOCUS, f.runtime.redo().route)
        assertEquals(HistoryRoute.BLOCKED_BY_FOCUS, f.runtime.redo().route)
        assertEquals(3, f.engine.undoDepth())
    }

    @Test fun availabilityDuringAFocusSessionDescribesTheToolNotTheArtifact() {
        val focus = Focus(localUndo = 1)
        val f = fixture(focus)
        f.seed()

        val availability = f.runtime.availability()

        assertTrue(availability.canUndo)
        assertEquals("Undo step in this tool", availability.undoDescription)
        assertFalse(availability.canRedo)
        assertEquals(HistoryRuntime.FOCUS_NOTHING_REDO_REASON, availability.redoReason)
    }

    @Test fun anExhaustedSessionExplainsWhyTheArtifactIsOutOfReach() {
        val f = fixture(Focus(localUndo = 0))
        f.seed()
        assertEquals(HistoryRuntime.FOCUS_EXHAUSTED_REASON, f.runtime.availability().undoReason)
    }

    @Test fun aClosedFocusSessionReleasesUndoBackToTheArtifact() {
        val focus = Focus(localUndo = 0)
        val f = fixture(focus)
        f.seed()
        assertEquals(HistoryRoute.BLOCKED_BY_FOCUS, f.runtime.undo().route)

        focus.active = false

        assertEquals("Ownership expires with the session", HistoryRoute.GLOBAL, f.runtime.undo().route)
        assertEquals(2, f.engine.undoDepth())
    }

    // --- Browser view state (§13.6, §11) --------------------------------------------------------------

    @Test fun openingAndClosingTheBrowserIsIdempotentAndUnwindsOneStepPerBackPress() {
        val f = fixture()
        f.seed()

        assertTrue(f.runtime.open())
        assertFalse("Opening an open browser is not a second open", f.runtime.open())
        assertTrue(f.runtime.select(1))
        assertTrue(f.runtime.openDetail())

        assertTrue(f.runtime.back())
        assertFalse(f.runtime.viewState().detailOpen)
        assertTrue(f.runtime.viewState().open)
        assertTrue(f.runtime.back())
        assertFalse(f.runtime.viewState().open)
        assertFalse("Back with nothing open is not consumed", f.runtime.back())
    }

    @Test fun selectionSurvivesAFilterThatStillShowsItAndIsDroppedByOneThatDoesNot() {
        val f = fixture()
        f.seed()
        f.runtime.open()
        f.runtime.select(0)

        f.runtime.setScope(HistoryScope.OneDomain(HistoryDomain.CHART))
        assertEquals("The chart entry is still visible", 0, f.runtime.viewState().selectedSequence)

        f.runtime.setScope(HistoryScope.OneDomain(HistoryDomain.TEXT))
        assertNull("A selection the filter hides would describe an invisible row", f.runtime.viewState().selectedSequence)
        assertFalse(f.runtime.viewState().detailOpen)
    }

    @Test fun selectingAnInvisibleEntryIsRefused() {
        val f = fixture()
        f.seed()
        f.runtime.open()
        f.runtime.setScope(HistoryScope.OneDomain(HistoryDomain.CHART))

        assertFalse(f.runtime.select(2))
        assertTrue(f.runtime.select(0))
        assertEquals("chart", f.runtime.selectedEntry()?.transactionId)
    }

    @Test fun takingAStepDropsASelectionWhoseSequenceNoLongerMeansTheSameThing() {
        val f = fixture()
        f.seed()
        f.runtime.open()
        f.runtime.select(2)
        f.runtime.openDetail()

        f.runtime.undo()

        assertNull(f.runtime.viewState().selectedSequence)
        assertFalse(f.runtime.viewState().detailOpen)
    }

    @Test fun aPastedQueryIsCappedRatherThanBecomingAScanCost() {
        val f = fixture()
        f.seed()

        val applied = f.runtime.setQuery("z".repeat(HistoryFilter.MAX_QUERY_LENGTH * 3))

        assertEquals(HistoryFilter.MAX_QUERY_LENGTH, applied.query.length)
        assertEquals(HistoryFilter.MAX_QUERY_LENGTH, f.runtime.viewState().filter.query.length)
    }

    @Test fun aRepeatedFilterIsNotCountedOrReapplied() {
        val f = fixture()
        f.seed()
        f.runtime.setOnlyMine(true)
        val after = f.runtime.metrics().filterChanges

        f.runtime.setOnlyMine(true)

        assertEquals(after, f.runtime.metrics().filterChanges)
    }

    @Test fun browserStateSurvivesAFormFactorChangeWithoutCarryingContent() {
        val f = fixture()
        f.seed()
        f.runtime.open()
        f.runtime.setQuery("Typing")
        f.runtime.select(1)
        f.runtime.setScrollIndex(4)

        val mode = f.runtime.onFormFactorChanged(FormFactor(1280, 900))

        assertEquals(PresentationMode.EXPANDED_THREE_PANEL, mode)
        assertEquals("Typing", f.runtime.viewState().filter.query)
        assertEquals(1, f.runtime.viewState().selectedSequence)
        assertEquals(4, f.runtime.viewState().scrollIndex)
    }

    @Test fun closingTheBrowserReleasesItsSurfaceAndAnyPendingStep() {
        val f = fixture()
        f.seed()
        f.runtime.setScope(HistoryScope.OneDomain(HistoryDomain.CHART))
        f.runtime.open()
        f.runtime.undo()
        assertEquals(HistoryRuntime.BROWSER_SURFACE, f.surfaces.active()?.id)

        f.runtime.close()

        assertNull(f.runtime.pendingDisclosure())
        assertNull(f.surfaces.active())
        assertNull(f.presentation.stateOf(HistoryRuntime.BROWSER_SURFACE))
    }

    @Test fun scrollAndDetailStateAreClampedAndCoherent() {
        val f = fixture()
        f.seed()
        f.runtime.open()

        f.runtime.setScrollIndex(3)
        f.runtime.setScrollIndex(-5)
        assertEquals("A negative scroll index is the top of the list, not a crash", 0, f.runtime.viewState().scrollIndex)
        assertFalse("Detail needs a selection", f.runtime.openDetail())

        f.runtime.select(0)
        assertTrue(f.runtime.openDetail())
        f.runtime.clearSelection()
        assertFalse(f.runtime.viewState().detailOpen)
    }

    // --- Command surface: one command per route (§4.1–§4.4) -------------------------------------------

    /** The palette route. §4.1 says the surface is telemetry only, so any surface must behave identically. */
    private fun context() = CommandContext(
        offeredCapabilities = emptySet(),
        surface = CommandSurface.COMMAND_EXPLORER,
    )

    private fun registry(runtime: HistoryRuntime) =
        CommandRegistry().also { HistoryCommands.register(it, runtime) }

    @Test fun registeredHistoryCommandsCoverEveryRouteAndReuseTheCanonicalIds() {
        val f = fixture()
        val registry = registry(f.runtime)

        HistoryCommandIds.core.forEach { assertNotNull("Missing command $it", registry.get(it)) }
        HistoryCommandIds.recovery.forEach { assertNull("No store, no restore-point command", registry.get(it)) }
        assertEquals(HistoryCommandIds.UNDO, registry.resolve("Undo")?.id)
        assertEquals(HistoryCommandIds.OPEN_BROWSER, registry.resolve("History")?.id)
    }

    @Test fun theUndoCommandReportsTheSameReasonTheToolbarShows() {
        val f = fixture()
        val undo = registry(f.runtime).get(HistoryCommandIds.UNDO)!!

        val disabled = undo.status(context())
        assertEquals(CommandState.DISABLED, disabled.state)
        assertEquals(f.runtime.availability().undoReason, disabled.reason)
        assertEquals("There is nothing to undo yet.", disabled.reason)

        f.seed()
        assertEquals(CommandStatus.Available, undo.status(context()))
        assertEquals("Undo Typing", undo.description)
    }

    @Test fun anIneligibleUndoIsRefusedByTheGuardRatherThanReachingTheHistory() {
        val f = fixture()
        val registry = registry(f.runtime)

        val result = registry.execute(HistoryCommandIds.UNDO, context())

        assertEquals(CommandResult.Failure("There is nothing to undo yet."), result)
        assertEquals(0, f.runtime.metrics().stepsExecuted)
    }

    @Test fun theUndoCommandTakesTheSameStepTheRuntimeWould() {
        val f = fixture()
        val registry = registry(f.runtime)
        f.seed()

        val result = registry.execute(HistoryCommandIds.UNDO, context())

        assertEquals(CommandResult.Success(1), result)
        assertEquals(2, f.engine.undoDepth())
        assertEquals(CommandResult.Success(1), registry.execute(HistoryCommandIds.REDO, context()))
        assertEquals(3, f.engine.undoDepth())
    }

    @Test fun theUndoCommandDisclosesInsteadOfSilentlyReversingMoreThanAsked() {
        val f = fixture()
        val registry = registry(f.runtime)
        f.seed()
        f.runtime.setScope(HistoryScope.OneDomain(HistoryDomain.CHART))

        val result = registry.execute(HistoryCommandIds.UNDO, context())

        assertEquals(CommandResult.Failure(HistoryCommands.DISCLOSURE_PENDING), result)
        assertNotNull("The palette route leaves the same pending step behind", f.runtime.pendingDisclosure())
        assertEquals(3, f.engine.undoDepth())
    }

    @Test fun clearingTheFilterIsOfferedOnlyWhileOneIsActive() {
        val f = fixture()
        val registry = registry(f.runtime)
        val clear = registry.get(HistoryCommandIds.CLEAR_FILTER)!!

        assertEquals(HistoryCommands.NOT_FILTERED, clear.status(context()).reason)
        assertEquals(
            CommandResult.Failure(HistoryCommands.NOT_FILTERED),
            registry.execute(HistoryCommandIds.CLEAR_FILTER, context()),
        )

        f.runtime.setOnlyMine(true)
        assertEquals(CommandStatus.Available, clear.status(context()))
        assertEquals(CommandResult.Success(1), registry.execute(HistoryCommandIds.CLEAR_FILTER, context()))
        assertEquals(HistoryMode.GLOBAL, f.runtime.mode())
        assertEquals(CommandResult.NoOp, clear.perform(context()))
    }

    @Test fun theBrowserCommandOpensOnceAndIsANoOpWhileAlreadyOpen() {
        val f = fixture()
        val registry = registry(f.runtime)

        assertEquals(CommandResult.Success(1), registry.execute(HistoryCommandIds.OPEN_BROWSER, context()))
        assertTrue(f.runtime.viewState().open)
        assertEquals(CommandResult.NoOp, registry.execute(HistoryCommandIds.OPEN_BROWSER, context()))
    }

    @Test fun stepCommandsCanBeOmittedForAHostWhoseCatalogAlreadyPublishesThem() {
        val f = fixture()
        val registry = CommandRegistry()
        registry.register(
            HistoryCommand(
                id = HistoryCommandIds.UNDO,
                label = "Undo",
                describe = { "The host's own row" },
                probe = { CommandStatus.Available },
                action = { CommandResult.Success() },
            ),
        )

        HistoryCommands.register(registry, f.runtime, includeStepCommands = false)

        assertEquals("The host's own row", registry.get(HistoryCommandIds.UNDO)?.description)
        assertNotNull(registry.get(HistoryCommandIds.OPEN_BROWSER))
        assertNull("Redo is the host's to publish too", registry.get(HistoryCommandIds.REDO))
    }

    // --- Metrics carry counts, never content ----------------------------------------------------------

    @Test fun metricsCountWorkWithoutRecordingWhatWasEdited() {
        val f = fixture()
        f.seed()
        f.runtime.setScope(HistoryScope.OneDomain(HistoryDomain.CHART))
        f.runtime.undo(confirmed = true)

        val metrics = f.runtime.metrics()

        assertEquals(1, metrics.stepsExecuted)
        assertEquals(3, metrics.entriesMoved)
        assertEquals(1, metrics.filterChanges)
        assertTrue(metrics.projectionsBuilt > 0)
        assertFalse(metrics.toString().contains("chart"))
    }

    @Test fun crossTargetTransactionsUndoAsOneStepUnderTheGlobalScope() {
        val f = fixture()
        f.engine.commit(
            tx(
                "cross",
                insert(para("p1"), target = noteA),
                insert(para("p2"), target = noteB),
                label = "Move between notes",
            ),
        )

        val outcome = f.runtime.undo()

        assertTrue(outcome.succeeded)
        assertEquals(listOf(0), outcome.moved)
        assertTrue(f.workspace.documentOf(noteA).blocks.isEmpty())
        assertTrue(f.workspace.documentOf(noteB).blocks.isEmpty())
    }
}
