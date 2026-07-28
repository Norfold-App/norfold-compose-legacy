package com.norfold.app.editor.surface

/**
 * The six surface classes a tool can be presented as (spec §5.2). The class fixes the *default* dismissal
 * policy and back behavior; presentation (phone popover / sheet / tablet pane / inspector) is orthogonal
 * and chosen by the Adaptive Presentation runtime (§5.1), never here.
 */
enum class SurfaceClass(val defaultDismissal: DismissalPolicy) {
    /** One-shot actions; normally closes after execution. */
    COMMAND_MENU(DismissalPolicy.AFTER_COMMAND),

    /** Repeated adjustment; remains open. */
    CONTINUOUS_TOOL(DismissalPolicy.OUTSIDE_OR_BACK),

    /** Comparison or repeated choice; remains available. */
    PICKER_BROWSER(DismissalPolicy.OUTSIDE_OR_BACK),

    /** Structural movement; remains open or collapses to a useful detent. */
    NAVIGATOR(DismissalPolicy.CONTEXT_CONTROLLED),

    /** Manipulation-heavy workflow; requires Done or Cancel. */
    FOCUS_TOOL(DismissalPolicy.EXPLICIT_DONE),

    /** Blocks only for a genuine decision. */
    DECISION_MODAL(DismissalPolicy.BACK_ONLY),
}

/**
 * How a surface may be dismissed (spec §5.3). A universal close-on-any-click handler is explicitly rejected;
 * each surface declares exactly one policy so dismissal is predictable per surface class.
 */
enum class DismissalPolicy {
    /** Closes automatically once its command executes. */
    AFTER_COMMAND,

    /** Closes on an outside tap or a Back gesture. */
    OUTSIDE_OR_BACK,

    /** Closes only via Back; outside taps pass through. */
    BACK_ONLY,

    /** Requires an explicit Done/Cancel; outside taps and Back alone do not close it. */
    EXPLICIT_DONE,

    /** May be pinned open by the user; closes only when unpinned. */
    PINNABLE,

    /** Dismissal is decided by host context (e.g. a navigator collapsing to a detent). */
    CONTEXT_CONTROLLED,
}

/** A gesture/event that may dismiss a surface. */
enum class DismissTrigger {
    COMMAND_EXECUTED,
    OUTSIDE_TAP,
    BACK,
    DONE,
    CANCEL,
    UNPIN,
}
