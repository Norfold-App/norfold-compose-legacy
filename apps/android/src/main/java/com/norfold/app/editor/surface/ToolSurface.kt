package com.norfold.app.editor.surface

/**
 * One open tool surface on the coordinator's stack (spec §5.2, §5.5). Holds the surface's identity, class,
 * the effective [dismissal] policy (defaulting to the class default but overridable, e.g. a pinned picker),
 * its retarget-compatible domains for §5.6, and preserved parent/child context (§5.5): selected sides,
 * values, target, query, and scroll survive drilling into a child.
 */
data class ToolSurface(
    val id: String,
    val surfaceClass: SurfaceClass,
    val dismissal: DismissalPolicy = surfaceClass.defaultDismissal,
    /** Domains this tool can retarget onto without collapsing (spec §5.6); empty = not a retargeting tool. */
    val compatibleDomains: Set<String> = emptySet(),
    /** Preserved interaction context carried across child open / retarget (spec §5.5). */
    val preserved: PreservedContext = PreservedContext(),
    val pinned: Boolean = false,
) {
    val requiresExplicitDone: Boolean get() = dismissal == DismissalPolicy.EXPLICIT_DONE
}

/**
 * Interaction context that must survive opening a child tool or retargeting (spec §5.5): the tool's
 * selected sides/values, its current target, an in-progress query, and scroll position. This is deliberately
 * opaque key/value plus the few named fields the spec calls out, so any tool can preserve its own state
 * without the coordinator knowing tool internals.
 */
data class PreservedContext(
    val targetId: String? = null,
    val query: String = "",
    val scroll: Int = 0,
    val values: Map<String, String> = emptyMap(),
)

/** Result of a retarget attempt (spec §5.6). */
sealed interface RetargetOutcome {
    /** The tool retargeted onto the new compatible target. */
    data class Retargeted(val targetId: String) : RetargetOutcome

    /** The new target is incompatible; the tool collapses (closes) rather than showing a wrong context. */
    data object Collapsed : RetargetOutcome

    /** A multi-selection retarget: the tool shows mixed state and how many targets are affected. */
    data class MixedState(val affected: Int, val total: Int) : RetargetOutcome
}
