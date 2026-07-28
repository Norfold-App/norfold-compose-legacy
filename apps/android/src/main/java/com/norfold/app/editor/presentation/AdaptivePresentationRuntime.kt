package com.norfold.app.editor.presentation

/**
 * Chooses the [PresentationMode] for a tool given the current [FormFactor], and — critically — re-presents a
 * running tool across form-factor changes **without resetting its state** (spec §2.6, §3, §5.1, §5.8).
 *
 * The runtime holds each tool's presentation-independent [ToolPresentationState] keyed by tool id. When the
 * window changes (rotation, resize, fold, moving to a larger screen) [onFormFactorChanged] recomputes the
 * mode for every open tool but carries the exact same state object forward — selected tab, query, scroll and
 * values are preserved. This is the Phase-0 D7 gate: *tool state survives a simulated form-factor change*.
 *
 * Pure-JVM, not thread-safe (editor loop serializes access). No Compose/Android dependency; hosts render the
 * chosen mode.
 */
class AdaptivePresentationRuntime(initial: FormFactor) {

    var formFactor: FormFactor = initial
        private set

    private val states = LinkedHashMap<String, ToolPresentationState>()

    /**
     * Registers/opens a tool with [state] and returns how it should be presented now. If the tool is already
     * open, its existing state is kept (re-opening does not clobber values).
     */
    fun present(state: ToolPresentationState, compact: CompactPreference = CompactPreference.POPOVER): PresentedTool {
        val effective = states.getOrPut(state.toolId) { state }
        return PresentedTool(effective, modeFor(effective.toolId, compact))
    }

    /** Updates the stored state for a tool (e.g. the user typed in its query or scrolled). */
    fun update(state: ToolPresentationState) {
        require(states.containsKey(state.toolId)) { "Tool '${state.toolId}' is not open" }
        states[state.toolId] = state
    }

    fun stateOf(toolId: String): ToolPresentationState? = states[toolId]

    fun openToolIds(): Set<String> = states.keys.toSet()

    /** Chooses the presentation mode for [toolId] under the current form factor and [compact] preference. */
    fun modeFor(toolId: String, compact: CompactPreference = CompactPreference.POPOVER): PresentationMode = when {
        formFactor.isExpanded -> PresentationMode.EXPANDED_THREE_PANEL
        formFactor.isTablet -> PresentationMode.TABLET_PANE
        // Phone: a large-content tool uses a sheet; a small action tool uses a popover (§2.8).
        compact == CompactPreference.SHEET -> PresentationMode.PHONE_SHEET
        else -> PresentationMode.PHONE_POPOVER
    }

    /**
     * Applies a form-factor change and re-presents every open tool. Every returned [PresentedTool] carries
     * the *same* state instance it had before — only [PresentedTool.mode] may differ. This is the method the
     * D7 gate exercises: values do not reset when the shell reflows.
     *
     * @return the re-presented tools keyed by tool id, in open order.
     */
    fun onFormFactorChanged(
        next: FormFactor,
        compact: (String) -> CompactPreference = { CompactPreference.POPOVER },
    ): Map<String, PresentedTool> {
        formFactor = next
        return states.mapValues { (toolId, state) ->
            // State is copied forward verbatim; only the mode is recomputed.
            PresentedTool(state, modeFor(toolId, compact(toolId)))
        }
    }

    /** Closes a tool, dropping its presentation state (the tool is finished, not merely reflowed). */
    fun close(toolId: String) { states.remove(toolId) }

    fun closeAll() = states.clear()
}
