package com.norfold.app.editor.history

import com.norfold.app.editor.presentation.AdaptivePresentationRuntime
import com.norfold.app.editor.presentation.CompactPreference
import com.norfold.app.editor.presentation.FormFactor
import com.norfold.app.editor.presentation.PresentationMode
import com.norfold.app.editor.presentation.ToolPresentationState
import com.norfold.app.editor.surface.DismissTrigger
import com.norfold.app.editor.surface.DismissalPolicy
import com.norfold.app.editor.surface.SurfaceClass
import com.norfold.app.editor.surface.SurfaceCoordinator
import com.norfold.app.editor.surface.ToolSurface

/**
 * Everything the History browser needs to draw itself, and nothing more.
 *
 * Deliberately free of document content: an entry is identified by its sequence number, and what the list
 * shows comes from [HistoryEntry], which carries labels and ids only. Restoring this state after a
 * form-factor change therefore cannot leak text that the new layout does not display.
 */
data class HistoryViewState(
    val open: Boolean = false,
    val filter: HistoryFilter = HistoryFilter(),
    /** The entry the user has selected, by [HistoryEntry.sequence]. Null means "none selected". */
    val selectedSequence: Int? = null,
    val scrollIndex: Int = 0,
    val detailOpen: Boolean = false,
)

/** How undo/redo is currently framed for the user (§13.2 vs §13.3). */
enum class HistoryMode {
    /** The list is unfiltered: Undo reverses the newest transaction in the artifact. */
    GLOBAL,

    /** A filter is active: Undo reverses the newest transaction *in that scope*, walking back to it. */
    CONTEXTUAL,
}

/**
 * What Undo and Redo will do right now, phrased for a person (Phase-10 §7).
 *
 * Every field is derived, never cached — an affordance that lags the history is worse than no affordance,
 * because it invites the user to press something that will do a different thing than it says.
 * [undoReason]/[redoReason] are non-null exactly when the corresponding action is unavailable, so the UI
 * always has something honest to show instead of a silently dead control.
 */
data class HistoryAvailability(
    val mode: HistoryMode,
    val canUndo: Boolean,
    val canRedo: Boolean,
    /** e.g. "Undo Typing". Null when unavailable. */
    val undoDescription: String?,
    val redoDescription: String?,
    val undoReason: String?,
    val redoReason: String?,
    /** True when the next Undo reaches past what the user pointed at and must be disclosed first. */
    val undoRequiresDisclosure: Boolean = false,
    val redoRequiresDisclosure: Boolean = false,
)

/** Content-free counters for Phase-10 performance evidence. Never records labels, ids or text. */
data class HistoryMetrics(
    val projectionsBuilt: Long,
    val filterChanges: Long,
    val plansComputed: Long,
    val stepsExecuted: Long,
    val entriesMoved: Long,
    val stalePlansRejected: Long,
    val focusRoutedSteps: Long,
    val focusBlockedSteps: Long,
)

private class MutableHistoryMetrics {
    var projectionsBuilt = 0L
    var filterChanges = 0L
    var plansComputed = 0L
    var stepsExecuted = 0L
    var entriesMoved = 0L
    var stalePlansRejected = 0L
    var focusRoutedSteps = 0L
    var focusBlockedSteps = 0L
    fun snapshot() = HistoryMetrics(
        projectionsBuilt, filterChanges, plansComputed, stepsExecuted,
        entriesMoved, stalePlansRejected, focusRoutedSteps, focusBlockedSteps,
    )
}

/**
 * The result of asking the runtime to undo or redo.
 *
 * [route] is reported even on refusal, because "nothing happened" and "the crop tool has undo right now"
 * are different answers and the user needs the second one.
 */
data class HistoryCommandOutcome(
    val route: HistoryRoute,
    val direction: HistoryDirection,
    /** Sequences of the entries that moved, in execution order. Empty unless [route] is GLOBAL. */
    val moved: List<Int> = emptyList(),
    val blocker: HistoryStepBlocker? = null,
    /** Non-null when the step started and could not finish; the position was rewound. */
    val failure: String? = null,
    /** Set when the step was refused pending user confirmation; the plan describes what it would do. */
    val pendingDisclosure: HistoryStepPlan.Ready? = null,
) {
    val succeeded: Boolean
        get() = failure == null && pendingDisclosure == null &&
            (route == HistoryRoute.GLOBAL || route == HistoryRoute.LOCAL_FOCUS)
}

/**
 * The one History view-model over the one authoritative history (spec §13.1–§13.6).
 *
 * This runtime **owns no transactions**. It reads applied and undone history from a [HistoryHost], derives
 * a [HistoryProjection], and moves the position by calling the host's own undo/redo. There is no second
 * stack here, filtered or otherwise: filtering changes which entries are *shown* and which one a contextual
 * undo walks back to, never which transactions exist.
 *
 * Two safety properties are worth stating outright, because both are easy to get wrong and expensive to
 * discover later:
 *
 *  - **No mid-stack removal.** Undoing an older entry reverses every newer transaction first, in order.
 *    [HistoryStepPlan.Ready.intervening] names them so the UI can disclose the blast radius before anything
 *    moves. Cutting one transaction out of the middle would leave a document state no recorded sequence of
 *    operations produces.
 *  - **No stale execution.** A plan is re-derived and compared against live history immediately before it
 *    runs. If the history moved since the plan was shown — an autosave-driven edit, a merge, a second
 *    surface — the step is refused rather than applied to a different stack than the user saw.
 *
 * Pure JVM: no Compose, no Android, no persistence, no collaboration.
 */
class HistoryRuntime(
    private val host: HistoryHost,
    private val surfaces: SurfaceCoordinator,
    private val presentation: AdaptivePresentationRuntime,
    /** Supplies the focus session that owns undo right now, if any (§13.5). */
    private val focusOwner: () -> FocusHistoryOwner? = { null },
) {
    private val metrics = MutableHistoryMetrics()
    private var view = HistoryViewState()
    private var pending: HistoryStepPlan.Ready? = null

    fun viewState(): HistoryViewState = view
    fun metrics(): HistoryMetrics = metrics.snapshot()

    /**
     * The step that was refused pending confirmation, if any.
     *
     * Held here rather than by whichever caller happened to ask, because §4.1 makes the routes one command:
     * a reversal that the toolbar discloses and the keyboard chord runs silently is two different commands
     * wearing one name. Every route sets and reads this same field, so the disclosure appears either way.
     */
    fun pendingDisclosure(): HistoryStepPlan.Ready? = pending

    /** Drops a pending disclosure without running it. The step is simply not taken. */
    fun dismissDisclosure() {
        pending = null
    }

    /** The unfiltered projection of the whole authoritative history, oldest first. */
    fun projection(): HistoryProjection {
        metrics.projectionsBuilt++
        return HistoryProjection.of(host.appliedHistory(), host.undoneHistory())
    }

    /** The projection narrowed by the current filter. Same entries, fewer rows — never a different history. */
    fun visibleProjection(): HistoryProjection = projection().filter(view.filter)

    fun mode(): HistoryMode = if (view.filter.isDefault) HistoryMode.GLOBAL else HistoryMode.CONTEXTUAL

    // --- Browser state -------------------------------------------------------------------------------

    fun open(): Boolean {
        if (view.open) return false
        view = view.copy(open = true)
        openSurface()
        presentation.present(presentationState(), CompactPreference.SHEET)
        return true
    }

    fun close() {
        if (!view.open) return
        view = view.copy(open = false, detailOpen = false)
        pending = null
        if (surfaces.active()?.id == BROWSER_SURFACE) surfaces.dispatch(DismissTrigger.CANCEL)
        presentation.close(BROWSER_SURFACE)
    }

    /**
     * Replaces the active filter. **Never commits, never moves the position** — changing what you are
     * looking at must not change what the document says.
     */
    fun setFilter(filter: HistoryFilter): HistoryFilter {
        val safe = filter.copy(query = filter.query.take(HistoryFilter.MAX_QUERY_LENGTH))
        if (safe == view.filter) return view.filter
        metrics.filterChanges++
        // A plan derived under the old filter describes a step this filter would not propose. Keeping it
        // would let the user confirm a sheet that no longer matches what the list in front of them says.
        pending = null
        // A selection that the new filter hides would leave the detail pane describing an invisible row.
        val stillVisible = view.selectedSequence?.let { sequence ->
            projection().entryBySequence(sequence)?.let(safe::matches) == true
        } ?: false
        view = view.copy(
            filter = safe,
            selectedSequence = if (stillVisible) view.selectedSequence else null,
            detailOpen = if (stillVisible) view.detailOpen else false,
            scrollIndex = 0,
        )
        updatePresentationState()
        return safe
    }

    fun setScope(scope: HistoryScope): HistoryFilter = setFilter(view.filter.copy(scope = scope))

    fun setQuery(query: String): HistoryFilter = setFilter(view.filter.copy(query = query))

    fun setOnlyMine(onlyMine: Boolean): HistoryFilter = setFilter(view.filter.copy(onlyMine = onlyMine))

    fun clearFilter(): HistoryFilter = setFilter(HistoryFilter())

    /** Selects an entry by sequence. Returns false when it is not currently visible. */
    fun select(sequence: Int): Boolean {
        val entry = visibleProjection().entryBySequence(sequence) ?: return false
        view = view.copy(selectedSequence = entry.sequence)
        updatePresentationState()
        return true
    }

    fun clearSelection() {
        view = view.copy(selectedSequence = null, detailOpen = false)
        updatePresentationState()
    }

    fun selectedEntry(): HistoryEntry? = view.selectedSequence?.let { projection().entryBySequence(it) }

    fun openDetail(): Boolean {
        if (view.selectedSequence == null) return false
        view = view.copy(detailOpen = true)
        updatePresentationState()
        return true
    }

    fun closeDetail() {
        if (!view.detailOpen) return
        view = view.copy(detailOpen = false)
        updatePresentationState()
    }

    fun setScrollIndex(index: Int) {
        val safe = index.coerceAtLeast(0)
        if (safe == view.scrollIndex) return
        view = view.copy(scrollIndex = safe)
        updatePresentationState()
    }

    /** Back closes the detail pane first, then the browser — one step per press (§5.4). */
    fun back(): Boolean = when {
        view.detailOpen -> { closeDetail(); true }
        view.open -> { close(); true }
        else -> false
    }

    // --- Affordances ---------------------------------------------------------------------------------

    /**
     * What Undo and Redo would do right now, including why they are unavailable when they are.
     *
     * A live focus session answers first: while a crop or a chart edit owns undo, the artifact's own next
     * transaction is not what Undo will reverse, and saying otherwise would be a lie the user acts on.
     */
    fun availability(): HistoryAvailability {
        val focus = focusOwner()?.takeIf { it.isActive() }
        if (focus != null) {
            return HistoryAvailability(
                mode = mode(),
                canUndo = focus.canUndoLocally(),
                canRedo = focus.canRedoLocally(),
                undoDescription = if (focus.canUndoLocally()) "Undo step in this tool" else null,
                redoDescription = if (focus.canRedoLocally()) "Redo step in this tool" else null,
                undoReason = if (focus.canUndoLocally()) null else FOCUS_EXHAUSTED_REASON,
                redoReason = if (focus.canRedoLocally()) null else FOCUS_NOTHING_REDO_REASON,
            )
        }
        val current = projection()
        val undoPlan = HistoryStepPlanner.planUndo(current, view.filter)
        val redoPlan = HistoryStepPlanner.planRedo(current, view.filter)
        metrics.plansComputed += 2
        return HistoryAvailability(
            mode = mode(),
            canUndo = undoPlan is HistoryStepPlan.Ready,
            canRedo = redoPlan is HistoryStepPlan.Ready,
            undoDescription = (undoPlan as? HistoryStepPlan.Ready)?.let { "Undo ${it.requested.label}" },
            redoDescription = (redoPlan as? HistoryStepPlan.Ready)?.let { "Redo ${it.requested.label}" },
            undoReason = (undoPlan as? HistoryStepPlan.Unavailable)?.let { reasonFor(it.blocker, HistoryDirection.UNDO) },
            redoReason = (redoPlan as? HistoryStepPlan.Unavailable)?.let { reasonFor(it.blocker, HistoryDirection.REDO) },
            undoRequiresDisclosure = (undoPlan as? HistoryStepPlan.Ready)?.requiresDisclosure == true,
            redoRequiresDisclosure = (redoPlan as? HistoryStepPlan.Ready)?.requiresDisclosure == true,
        )
    }

    /** The undo step the current filter implies, without running it. For disclosure sheets and previews. */
    fun planUndo(): HistoryStepPlan {
        metrics.plansComputed++
        return HistoryStepPlanner.planUndo(projection(), view.filter)
    }

    fun planRedo(): HistoryStepPlan {
        metrics.plansComputed++
        return HistoryStepPlanner.planRedo(projection(), view.filter)
    }

    /** The step that undoing back to [sequence] implies, including every newer transaction it reverses. */
    fun planUndoTo(sequence: Int): HistoryStepPlan {
        metrics.plansComputed++
        val current = projection()
        val applied = current.entries.filter { it.position == HistoryPosition.APPLIED }
        if (applied.isEmpty()) {
            return HistoryStepPlan.Unavailable(HistoryDirection.UNDO, HistoryStepBlocker.NOTHING_RECORDED)
        }
        val index = applied.indexOfFirst { it.sequence == sequence }
        if (index < 0) {
            return HistoryStepPlan.Unavailable(HistoryDirection.UNDO, HistoryStepBlocker.NOTHING_IN_SCOPE)
        }
        return HistoryStepPlan.Ready(
            direction = HistoryDirection.UNDO,
            requested = applied[index],
            intervening = applied.subList(index + 1, applied.size).reversed(),
            extraTargets = emptyList(),
        )
    }

    // --- Commands ------------------------------------------------------------------------------------

    /**
     * Undo, routed correctly.
     *
     * When [confirmed] is false a step that reaches past what the user pointed at is returned as
     * [HistoryCommandOutcome.pendingDisclosure] instead of being run, so the caller can show the blast
     * radius. A plain global undo never needs confirmation — the engine reverses even a cross-target
     * transaction atomically, which is what §13.4 asks for.
     */
    fun undo(confirmed: Boolean = false): HistoryCommandOutcome =
        routed(HistoryDirection.UNDO, confirmed) { planUndo() }

    fun redo(confirmed: Boolean = false): HistoryCommandOutcome =
        routed(HistoryDirection.REDO, confirmed) { planRedo() }

    /** Undoes back to and including the entry at [sequence]. Always a global step; never routed to focus. */
    fun undoTo(sequence: Int, confirmed: Boolean = false): HistoryCommandOutcome =
        routed(HistoryDirection.UNDO, confirmed) { planUndoTo(sequence) }

    /**
     * Runs an already-derived plan after re-deriving it against live history.
     *
     * The re-derivation is the point: between showing a disclosure sheet and the user accepting it, an
     * autosave-driven edit or a second surface may have committed. Executing the shown plan against a
     * different stack would reverse transactions the user never saw.
     */
    fun execute(plan: HistoryStepPlan.Ready): HistoryCommandOutcome {
        pending = null
        val fresh = when (plan.direction) {
            HistoryDirection.UNDO -> planUndo()
            HistoryDirection.REDO -> planRedo()
        }
        if (!matches(fresh, plan)) {
            metrics.stalePlansRejected++
            return HistoryCommandOutcome(
                route = HistoryRoute.UNAVAILABLE,
                direction = plan.direction,
                failure = STALE_PLAN_REASON,
            )
        }
        return run(plan)
    }

    private fun routed(
        direction: HistoryDirection,
        confirmed: Boolean,
        plan: () -> HistoryStepPlan,
    ): HistoryCommandOutcome {
        // Whatever was awaiting confirmation is superseded by the step being asked for now.
        pending = null
        val focus = focusOwner()?.takeIf { it.isActive() }
        if (focus != null) {
            val handled = when (direction) {
                HistoryDirection.UNDO -> if (focus.canUndoLocally()) focus.undoLocally() else false
                HistoryDirection.REDO -> if (focus.canRedoLocally()) focus.redoLocally() else false
            }
            if (handled) {
                metrics.focusRoutedSteps++
                return HistoryCommandOutcome(HistoryRoute.LOCAL_FOCUS, direction)
            }
            // The session is open and out of local steps. Falling through to artifact history here would
            // reverse an edit made before the session opened, which the user cannot even see (§13.5).
            metrics.focusBlockedSteps++
            return HistoryCommandOutcome(
                route = HistoryRoute.BLOCKED_BY_FOCUS,
                direction = direction,
                blocker = HistoryStepBlocker.FOCUS_SESSION_ACTIVE,
            )
        }
        return when (val computed = plan()) {
            is HistoryStepPlan.Unavailable -> HistoryCommandOutcome(
                route = HistoryRoute.UNAVAILABLE,
                direction = direction,
                blocker = computed.blocker,
            )
            is HistoryStepPlan.Ready ->
                if (computed.requiresDisclosure && !confirmed) {
                    pending = computed
                    HistoryCommandOutcome(
                        route = HistoryRoute.UNAVAILABLE,
                        direction = direction,
                        pendingDisclosure = computed,
                    )
                } else {
                    run(computed)
                }
        }
    }

    /**
     * Walks the position one entry at a time, rewinding completely if any step refuses.
     *
     * A partial walk is the one outcome that must never survive: it would leave the document between two
     * transactions with no record of where it stopped. The rewind uses the host's own inverse direction, so
     * it is the same proven code path, not a bespoke repair.
     */
    private fun run(plan: HistoryStepPlan.Ready): HistoryCommandOutcome {
        metrics.stepsExecuted++
        val moved = mutableListOf<Int>()
        for (entry in plan.steps) {
            val applied = when (plan.direction) {
                HistoryDirection.UNDO -> host.undo()
                HistoryDirection.REDO -> host.redo()
            }
            if (applied == null) {
                repeat(moved.size) {
                    when (plan.direction) {
                        HistoryDirection.UNDO -> host.redo()
                        HistoryDirection.REDO -> host.undo()
                    }
                }
                return HistoryCommandOutcome(
                    route = HistoryRoute.GLOBAL,
                    direction = plan.direction,
                    failure = INTERRUPTED_REASON,
                )
            }
            moved += entry.sequence
        }
        metrics.entriesMoved += moved.size
        // Sequences shift as the position moves, so a selection pinned to one is no longer meaningful.
        view = view.copy(selectedSequence = null, detailOpen = false)
        updatePresentationState()
        return HistoryCommandOutcome(HistoryRoute.GLOBAL, plan.direction, moved)
    }

    private fun matches(fresh: HistoryStepPlan, shown: HistoryStepPlan.Ready): Boolean {
        val ready = fresh as? HistoryStepPlan.Ready ?: return false
        if (ready.requested.transactionId != shown.requested.transactionId) return false
        return ready.intervening.map { it.transactionId } == shown.intervening.map { it.transactionId }
    }

    private fun reasonFor(blocker: HistoryStepBlocker, direction: HistoryDirection): String = when (blocker) {
        HistoryStepBlocker.NOTHING_RECORDED ->
            if (direction == HistoryDirection.UNDO) "There is nothing to undo yet." else "There is nothing to redo."
        HistoryStepBlocker.NOTHING_IN_SCOPE ->
            "Nothing in the current filter was changed. Clear the filter to see the whole history."
        HistoryStepBlocker.FOCUS_SESSION_ACTIVE -> FOCUS_EXHAUSTED_REASON
    }

    // --- Presentation --------------------------------------------------------------------------------

    /**
     * Carries filter, selection, scroll and detail state across a form-factor change (§11).
     *
     * The values map holds sequence numbers and filter settings only — no labels, no document text — so the
     * state that survives a rotation is exactly the state the browser needs to look unchanged.
     */
    fun onFormFactorChanged(next: FormFactor): PresentationMode? {
        val represented = presentation.onFormFactorChanged(next) { CompactPreference.SHEET }
        return represented[BROWSER_SURFACE]?.mode
    }

    fun presentationMode(): PresentationMode? =
        presentation.stateOf(BROWSER_SURFACE)?.let { presentation.modeFor(BROWSER_SURFACE, CompactPreference.SHEET) }

    private fun presentationState() = ToolPresentationState(
        toolId = BROWSER_SURFACE,
        query = view.filter.query,
        scroll = view.scrollIndex,
        values = mapOf(
            "scope" to scopeKey(view.filter.scope),
            "onlyMine" to view.filter.onlyMine.toString(),
            "selected" to (view.selectedSequence?.toString() ?: ""),
            "detail" to view.detailOpen.toString(),
        ),
    )

    private fun updatePresentationState() {
        if (!view.open) return
        val state = presentationState()
        if (presentation.stateOf(BROWSER_SURFACE) == null) presentation.present(state, CompactPreference.SHEET)
        else presentation.update(state)
    }

    private fun scopeKey(scope: HistoryScope): String = when (scope) {
        HistoryScope.WholeArtifact -> "all"
        is HistoryScope.OneTarget -> "target:${scope.target.id}"
        is HistoryScope.OneBlock -> "block:${scope.blockId}"
        is HistoryScope.OnePage -> "page:${scope.pageId}"
        is HistoryScope.OneObject -> "object:${scope.objectId}"
        is HistoryScope.OneDomain -> "domain:${scope.domain.name}"
    }

    private fun openSurface() {
        if (surfaces.active()?.id == BROWSER_SURFACE) return
        val surface = ToolSurface(
            id = BROWSER_SURFACE,
            surfaceClass = SurfaceClass.PICKER_BROWSER,
            dismissal = DismissalPolicy.OUTSIDE_OR_BACK,
            compatibleDomains = setOf("history"),
        )
        if (surfaces.depth == 0) surfaces.open(surface) else surfaces.openChild(surface)
    }

    companion object {
        const val BROWSER_SURFACE = "history.surface.browser"

        const val FOCUS_EXHAUSTED_REASON =
            "This tool has its own undo. Finish or cancel it to change the rest of the document."
        const val FOCUS_NOTHING_REDO_REASON = "There is nothing to redo in this tool."
        const val STALE_PLAN_REASON =
            "The history changed while this was open. Look again before undoing."
        const val INTERRUPTED_REASON =
            "The step could not be completed, so nothing was changed."
    }
}

/** No construction, projection, or surface registration occurs while HISTORY_UI is disabled. */
object HistoryIntegration {
    fun create(enabled: Boolean, factory: () -> HistoryRuntime): HistoryRuntime? = if (enabled) factory() else null
}
