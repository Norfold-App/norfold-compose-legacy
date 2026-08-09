package com.norfold.app.editor.history

/** Which way a planned history step moves along the one linear order. */
enum class HistoryDirection { UNDO, REDO }

/** Why a requested undo/redo cannot be offered. Presented to the user as a reason, never as a silent no-op. */
enum class HistoryStepBlocker {
    /** The artifact history is empty in this direction. */
    NOTHING_RECORDED,

    /** There are entries, but none of them touched the thing the user is scoped to. */
    NOTHING_IN_SCOPE,

    /** A focus session owns undo right now; its own history must be exhausted or committed first (§13.5). */
    FOCUS_SESSION_ACTIVE,
}

/**
 * A planned, *disclosed* history step (spec §13.3, §13.4).
 *
 * The rule this type encodes: a contextual undo may never reach into the middle of the stack and quietly
 * remove one transaction. If the entry the user asked for is not the newest one, every transaction newer
 * than it must be reversed too — and [intervening] names them so the UI can say so before anything moves.
 * The alternative (cutting a transaction out of the middle) would leave workspace state that no sequence of
 * recorded operations produces, which is exactly the corruption Phase 10 must not introduce.
 */
sealed interface HistoryStepPlan {
    val direction: HistoryDirection

    data class Unavailable(
        override val direction: HistoryDirection,
        val blocker: HistoryStepBlocker,
    ) : HistoryStepPlan

    data class Ready(
        override val direction: HistoryDirection,
        /** The entry the user actually asked for. */
        val requested: HistoryEntry,
        /** Entries that must move first, in the exact order they will be applied. May be empty. */
        val intervening: List<HistoryEntry>,
        /** Targets outside the current scope that this step will also change. */
        val extraTargets: List<EditTarget>,
    ) : HistoryStepPlan {
        /** Every entry this step moves, in execution order. */
        val steps: List<HistoryEntry> get() = intervening + requested

        /** True when the step reaches beyond what the user pointed at and must be confirmed first. */
        val requiresDisclosure: Boolean get() = intervening.isNotEmpty() || extraTargets.isNotEmpty()
    }
}

/** Outcome of executing a [HistoryStepPlan.Ready]. */
data class HistoryStepResult(
    val direction: HistoryDirection,
    /** Entries successfully moved. */
    val moved: List<String>,
    /** Non-null when the walk stopped early; the position was rewound to where it started. */
    val failure: String? = null,
) {
    val succeeded: Boolean get() = failure == null
}

/**
 * Plans undo/redo steps over a [HistoryProjection] without touching it.
 *
 * Pure functions of a projection and a filter. Planning is separated from execution so the UI can *show*
 * what a command will do — including its blast radius — before the user commits to it.
 */
object HistoryStepPlanner {

    fun planUndo(projection: HistoryProjection, filter: HistoryFilter = HistoryFilter()): HistoryStepPlan {
        val applied = projection.entries.filter { it.position == HistoryPosition.APPLIED }
        if (applied.isEmpty()) {
            return HistoryStepPlan.Unavailable(HistoryDirection.UNDO, HistoryStepBlocker.NOTHING_RECORDED)
        }
        if (!filter.scope.isContextual) {
            return ready(HistoryDirection.UNDO, applied.last(), emptyList(), filter)
        }
        val index = applied.indexOfLast(filter::matches)
        if (index < 0) {
            return HistoryStepPlan.Unavailable(HistoryDirection.UNDO, HistoryStepBlocker.NOTHING_IN_SCOPE)
        }
        // Undo walks backwards, so everything newer than the match reverses first — newest one first.
        val intervening = applied.subList(index + 1, applied.size).reversed()
        return ready(HistoryDirection.UNDO, applied[index], intervening, filter)
    }

    fun planRedo(projection: HistoryProjection, filter: HistoryFilter = HistoryFilter()): HistoryStepPlan {
        val undone = projection.entries.filter { it.position == HistoryPosition.UNDONE }
        if (undone.isEmpty()) {
            return HistoryStepPlan.Unavailable(HistoryDirection.REDO, HistoryStepBlocker.NOTHING_RECORDED)
        }
        if (!filter.scope.isContextual) {
            return ready(HistoryDirection.REDO, undone.first(), emptyList(), filter)
        }
        val index = undone.indexOfFirst(filter::matches)
        if (index < 0) {
            return HistoryStepPlan.Unavailable(HistoryDirection.REDO, HistoryStepBlocker.NOTHING_IN_SCOPE)
        }
        // Redo walks forwards, so everything between the current position and the match reapplies first.
        val intervening = undone.subList(0, index)
        return ready(HistoryDirection.REDO, undone[index], intervening, filter)
    }

    private fun ready(
        direction: HistoryDirection,
        requested: HistoryEntry,
        intervening: List<HistoryEntry>,
        filter: HistoryFilter,
    ): HistoryStepPlan.Ready {
        val scopeTarget = (filter.scope as? HistoryScope.OneTarget)?.target
        // Under the whole-artifact scope nothing is "extra": the user asked for everything, and the engine
        // already undoes a cross-target transaction atomically (§13.4). Disclosure is for the contextual
        // case, where the user pointed at one thing and the step reaches past it.
        val extraTargets = when {
            scopeTarget != null -> requested.targets.filterNot { it == scopeTarget }
            filter.scope.isContextual && requested.isCrossTarget -> requested.targets
            else -> emptyList()
        }
        return HistoryStepPlan.Ready(direction, requested, intervening, extraTargets)
    }
}

/**
 * The seam a History view uses to read and move the one authoritative history.
 *
 * Implemented by whatever owns the [HistoryEngine] for an artifact (the flow controller, the scene
 * controller). The runtime never holds an engine directly, which is what keeps it from being tempted to
 * grow a second stack of its own.
 */
interface HistoryHost {
    fun appliedHistory(): List<EditTransaction>
    fun undoneHistory(): List<EditTransaction>
    fun undo(): EditTransaction?
    fun redo(): EditTransaction?

    companion object {
        /** Adapts a raw engine. Used directly by tests and by hosts that expose their engine. */
        fun of(engine: HistoryEngine): HistoryHost = object : HistoryHost {
            override fun appliedHistory(): List<EditTransaction> = engine.history()
            override fun undoneHistory(): List<EditTransaction> = engine.redoHistory()
            override fun undo(): EditTransaction? = engine.undo()
            override fun redo(): EditTransaction? = engine.redo()
        }
    }
}

/**
 * A focus session that owns undo while it is open (spec §13.5).
 *
 * Image Focus and similar tools keep a temporary step-level history that is *not* artifact history: those
 * steps do not exist as transactions until the session commits. While such a session is active, undo must
 * route to it — and, critically, must **stop** when its local history runs out rather than falling through
 * to the artifact and reversing something the user cannot see.
 */
interface FocusHistoryOwner {
    /** Stable id for disclosure and metrics. Never a document value. */
    val focusId: String

    /**
     * False once the session has closed (committed or cancelled).
     *
     * Without this, a host that forgot to clear its registration would keep routing undo into a session that
     * no longer exists: the local history is empty, so every request answers [HistoryRoute.BLOCKED_BY_FOCUS],
     * and the chord is dead for the rest of the artifact's life. Ownership expires with the session, not with
     * the host's memory of it.
     */
    fun isActive(): Boolean = true

    fun canUndoLocally(): Boolean
    fun canRedoLocally(): Boolean
    fun undoLocally(): Boolean
    fun redoLocally(): Boolean

    companion object {
        fun of(
            focusId: String,
            canUndo: () -> Boolean,
            canRedo: () -> Boolean,
            undo: () -> Boolean,
            redo: () -> Boolean,
            isActive: () -> Boolean = { true },
        ): FocusHistoryOwner = object : FocusHistoryOwner {
            override val focusId: String = focusId
            override fun isActive(): Boolean = isActive()
            override fun canUndoLocally(): Boolean = canUndo()
            override fun canRedoLocally(): Boolean = canRedo()
            override fun undoLocally(): Boolean = undo()
            override fun redoLocally(): Boolean = redo()
        }
    }
}

/** Where an undo/redo request was actually sent. */
enum class HistoryRoute {
    /** Handled by the active focus session's local history. */
    LOCAL_FOCUS,

    /** Handled by the artifact's authoritative history. */
    GLOBAL,

    /**
     * Refused. A focus session is open but has nothing left to undo locally; the request must not escape
     * into artifact history, because the user would see an edit outside the session vanish.
     */
    BLOCKED_BY_FOCUS,

    /** Nothing to do in this direction. */
    UNAVAILABLE;

    /**
     * True when history actually moved.
     *
     * [BLOCKED_BY_FOCUS] is deliberately false: the request was consumed — the key must not fall through to
     * the IME — but no edit was reversed, and a caller that treats "consumed" as "moved" would republish a
     * caret and bump a revision for a step that never happened.
     */
    val moved: Boolean get() = this == LOCAL_FOCUS || this == GLOBAL
}
