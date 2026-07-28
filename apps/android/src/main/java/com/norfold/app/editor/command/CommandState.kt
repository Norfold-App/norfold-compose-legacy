package com.norfold.app.editor.command

/**
 * Where a command was invoked from (spec §4.1). One semantic command is defined once and reachable from
 * every relevant surface; the route is passed to the command only for telemetry/behavioral nuance, never
 * to change eligibility, state, execution, preview, or undo — those are identical across all routes.
 */
enum class CommandSurface {
    DOCK,
    SLASH,
    COMMAND_EXPLORER,
    KEYBOARD,
    POINTER_CONTEXT_MENU,
    INSPECTOR,
    ACCESSIBILITY,
}

/**
 * Runtime state of a command against the current context (spec §4.2). This is *presentation-independent*:
 * the same state drives the dock button, the slash row, the menu item, and the accessibility action.
 *
 *  - [HIDDEN]    — irrelevant to the current context; do not show.
 *  - [DISABLED]  — useful to show but currently unavailable; carries a human reason where helpful.
 *  - [AVAILABLE] — eligible and idle.
 *  - [ACTIVE]    — eligible and currently "on" for the selection (e.g. Bold on bold text).
 *  - [MIXED]     — eligible but the selection holds more than one value (spec §4.11).
 *  - [BUSY]      — executing (async).
 *  - [FAILED]    — last invocation failed; carries a reason.
 */
enum class CommandState {
    HIDDEN,
    DISABLED,
    AVAILABLE,
    ACTIVE,
    MIXED,
    BUSY,
    FAILED,
}

/**
 * Resolved state plus an optional human-facing reason. Disabled/failed states explain the actual cause
 * where helpful (spec §4.2) rather than silently greying out.
 */
data class CommandStatus(
    val state: CommandState,
    val reason: String? = null,
) {
    val isVisible: Boolean get() = state != CommandState.HIDDEN
    val isInvokable: Boolean get() = state == CommandState.AVAILABLE || state == CommandState.ACTIVE || state == CommandState.MIXED

    companion object {
        val Hidden = CommandStatus(CommandState.HIDDEN)
        val Available = CommandStatus(CommandState.AVAILABLE)
        val Active = CommandStatus(CommandState.ACTIVE)
        val Mixed = CommandStatus(CommandState.MIXED)
        fun disabled(reason: String? = null) = CommandStatus(CommandState.DISABLED, reason)
        fun failed(reason: String? = null) = CommandStatus(CommandState.FAILED, reason)
    }
}

/**
 * How a command applies to a multi-target selection (spec §4.10). Silent partial application is rejected:
 * a command must declare exactly one policy so the runtime can decide eligibility and report the affected
 * count honestly.
 */
enum class ApplicabilityPolicy {
    /** Every selected target must support the command, or it is disabled. */
    ALL_TARGETS,

    /** Affects only compatible targets and states how many were affected. */
    ELIGIBLE_SUBSET,

    /** Acts only on the primary target. */
    PRIMARY_TARGET,

    /** Treats the whole set as one temporary composite. */
    SELECTION_AS_UNIT,
}
