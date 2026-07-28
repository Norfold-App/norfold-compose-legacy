package com.norfold.app.editor.presentation

/**
 * The presentation-independent state of a tool (spec §2.6, §5.1). This is the "state" side of "state is
 * separate from presentation": selected tab, query, scroll, and arbitrary values live here and are shared
 * across every [PresentationMode]. Re-presenting the tool (phone popover → tablet pane → three-panel) copies
 * this forward untouched — that is the invariant the D7 gate checks.
 *
 * It is immutable; mutations return a new instance, so a form-factor change can never accidentally mutate
 * values, and history/undo semantics elsewhere are unaffected.
 */
data class ToolPresentationState(
    val toolId: String,
    val selectedTab: String? = null,
    val query: String = "",
    val scroll: Int = 0,
    val values: Map<String, String> = emptyMap(),
) {
    fun withTab(tab: String?) = copy(selectedTab = tab)
    fun withQuery(q: String) = copy(query = q)
    fun withScroll(s: Int) = copy(scroll = s)
    fun withValue(key: String, value: String) = copy(values = values + (key to value))
}

/**
 * A tool as currently presented: its shared [state] plus the [mode] it is being rendered in. Produced by
 * [AdaptivePresentationRuntime]; the [mode] changes on a form-factor change while [state] is carried over
 * verbatim.
 */
data class PresentedTool(
    val state: ToolPresentationState,
    val mode: PresentationMode,
)
