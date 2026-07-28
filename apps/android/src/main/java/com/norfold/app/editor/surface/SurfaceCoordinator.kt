package com.norfold.app.editor.surface

/**
 * Owns the lifecycle of open tool surfaces (spec §5.2–§5.6). It is the single authority that decides whether
 * a given [DismissTrigger] actually closes a surface (per that surface's declared policy — a universal
 * close-on-click handler is rejected, §5.3), and it models the **back hierarchy** (§5.4): Back unwinds one
 * logical level at a time — child picker → parent tool → dock/selection mode → ordinary editor → previous
 * app screen.
 *
 * Surfaces form a stack (parent at the bottom, most-recent child on top). Opening a child preserves the
 * parent's context (§5.5); [retarget] applies the §5.6 rules. Pure-JVM, not thread-safe (editor loop
 * serializes access), no Android navigation dependency — hosts translate coordinator decisions into UI.
 */
class SurfaceCoordinator {

    private val stack = ArrayDeque<ToolSurface>()

    /** The surface currently receiving input (top of stack), or null if only the editor is showing. */
    fun active(): ToolSurface? = stack.lastOrNull()

    fun openSurfaces(): List<ToolSurface> = stack.toList()

    val depth: Int get() = stack.size

    /** Opens [surface] as a new top-level tool (its parent is the editor). */
    fun open(surface: ToolSurface): ToolSurface {
        stack.addLast(surface)
        return surface
    }

    /**
     * Opens [child] as a child of the current active surface, preserving the parent's context (§5.5). The
     * parent stays on the stack beneath the child so Back returns to it.
     */
    fun openChild(child: ToolSurface): ToolSurface {
        require(stack.isNotEmpty()) { "openChild requires an active parent surface" }
        stack.addLast(child)
        return child
    }

    /**
     * Decides whether [trigger] dismisses the [surface] per its policy (§5.3). Returns true iff the surface
     * should close. This never mutates state — it is the pure policy so it can be unit-tested exhaustively;
     * [dispatch] applies it to the stack.
     */
    fun shouldDismiss(surface: ToolSurface, trigger: DismissTrigger): Boolean = when (surface.dismissal) {
        DismissalPolicy.AFTER_COMMAND ->
            trigger == DismissTrigger.COMMAND_EXECUTED || trigger == DismissTrigger.BACK || trigger == DismissTrigger.CANCEL
        DismissalPolicy.OUTSIDE_OR_BACK ->
            trigger == DismissTrigger.OUTSIDE_TAP || trigger == DismissTrigger.BACK || trigger == DismissTrigger.CANCEL
        DismissalPolicy.BACK_ONLY ->
            trigger == DismissTrigger.BACK || trigger == DismissTrigger.CANCEL
        DismissalPolicy.EXPLICIT_DONE ->
            trigger == DismissTrigger.DONE || trigger == DismissTrigger.CANCEL
        DismissalPolicy.PINNABLE ->
            if (surface.pinned) trigger == DismissTrigger.UNPIN || trigger == DismissTrigger.CANCEL
            else trigger == DismissTrigger.OUTSIDE_TAP || trigger == DismissTrigger.BACK || trigger == DismissTrigger.CANCEL
        DismissalPolicy.CONTEXT_CONTROLLED ->
            // The host decides; only an explicit Back/Cancel closes it by default.
            trigger == DismissTrigger.BACK || trigger == DismissTrigger.CANCEL
    }

    /**
     * Applies [trigger] to the active surface. If policy says it dismisses, the surface is popped and the
     * result reports the newly active surface (its parent, per the back hierarchy). If nothing is open or
     * the trigger does not dismiss, the stack is unchanged.
     *
     * @return a [DispatchResult] describing what happened.
     */
    fun dispatch(trigger: DismissTrigger): DispatchResult {
        val current = stack.lastOrNull() ?: return DispatchResult.NoSurface
        if (!shouldDismiss(current, trigger)) {
            return DispatchResult.Retained(current)
        }
        stack.removeLast()
        return DispatchResult.Dismissed(closed = current, nowActive = stack.lastOrNull())
    }

    /**
     * Models one Back press against the whole hierarchy (§5.4). Back unwinds exactly one logical level: if a
     * surface is open it dismisses the top one (respecting BACK-eligibility); otherwise it reports the next
     * lower level so the host can step dock/selection-mode → editor → previous app screen.
     */
    fun back(hasSelectionMode: Boolean): BackOutcome {
        val current = stack.lastOrNull()
        if (current != null) {
            return if (shouldDismiss(current, DismissTrigger.BACK)) {
                stack.removeLast()
                BackOutcome.ClosedSurface(current, stack.lastOrNull())
            } else {
                // e.g. a focus tool that requires explicit Done swallows Back rather than closing.
                BackOutcome.Consumed(current)
            }
        }
        return if (hasSelectionMode) BackOutcome.ExitedSelectionMode else BackOutcome.LeftEditor
    }

    /**
     * Retargets the active continuous tool onto [newTargetDomain] (§5.6). A compatible single target
     * retargets; an incompatible one collapses the tool; a multi-selection reports mixed state with counts.
     */
    fun retarget(newTargetDomain: String, newTargetId: String, selectionCount: Int = 1): RetargetOutcome {
        val current = stack.lastOrNull() ?: return RetargetOutcome.Collapsed
        if (newTargetDomain !in current.compatibleDomains) {
            stack.removeLast()
            return RetargetOutcome.Collapsed
        }
        if (selectionCount > 1) {
            return RetargetOutcome.MixedState(affected = selectionCount, total = selectionCount)
        }
        stack[stack.lastIndex] = current.copy(preserved = current.preserved.copy(targetId = newTargetId))
        return RetargetOutcome.Retargeted(newTargetId)
    }

    /** Closes everything down to the editor (e.g. selection cleared, host torn down). */
    fun closeAll() = stack.clear()
}

/** Outcome of [SurfaceCoordinator.dispatch]. */
sealed interface DispatchResult {
    /** Nothing was open. */
    data object NoSurface : DispatchResult

    /** The trigger did not dismiss the active surface; it stays open. */
    data class Retained(val surface: ToolSurface) : DispatchResult

    /** The active surface closed; [nowActive] is its parent (or null = back to the editor). */
    data class Dismissed(val closed: ToolSurface, val nowActive: ToolSurface?) : DispatchResult
}

/** Outcome of one Back press against the back hierarchy (§5.4). */
sealed interface BackOutcome {
    /** A surface closed; [nowActive] is the parent it returned to (null = editor). */
    data class ClosedSurface(val closed: ToolSurface, val nowActive: ToolSurface?) : BackOutcome

    /** The active surface swallowed Back (e.g. requires explicit Done) and stayed open. */
    data class Consumed(val surface: ToolSurface) : BackOutcome

    /** No surface open; Back exited selection/dock mode to the ordinary editor. */
    data object ExitedSelectionMode : BackOutcome

    /** No surface and no selection mode; Back leaves the editor for the previous app screen. */
    data object LeftEditor : BackOutcome
}
