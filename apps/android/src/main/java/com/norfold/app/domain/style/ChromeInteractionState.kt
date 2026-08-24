package com.norfold.app.domain.style

/** The six visual states shared by application chrome, never by authored editor content. */
enum class ChromeInteractionState {
    Resting,
    Focused,
    Pressed,
    Selected,
    Disabled,
    Error,
}

/**
 * The independent visual channels produced by a [ChromeInteractionSnapshot].
 *
 * Focus and press deliberately do not change [edgeWidthDp]. Focus owns a separate ring and press owns a
 * transient state layer, so neither can be mistaken for persistent selection. Keeping these relationships
 * here makes the six-state contract testable without a Compose runtime.
 */
data class ChromeInteractionVisualSpec(
    val edgeState: ChromeInteractionState,
    val edgeWidthDp: Float,
    val showFocusRing: Boolean,
    val showPressLayer: Boolean,
    val contentAlpha: Float,
)

/**
 * One composable interaction answer for a piece of Norfold chrome.
 *
 * Real controls can be selected, focused and in error at the same time, so reducing them to a single enum
 * silently discards information. This value preserves the concurrent facts and derives the visual channels
 * from them. The channels are intentionally independent: focus owns a separate ring, selection/error own
 * the boundary, and press owns the state layer. That is what prevents a focused selected control from
 * looking merely "more selected".
 */
data class ChromeInteractionSnapshot(
    val enabled: Boolean = true,
    val focused: Boolean = false,
    val pressed: Boolean = false,
    val selected: Boolean = false,
    val error: Boolean = false,
) {
    /** Disabled controls cannot remain actively focused or pressed. */
    val focusVisible: Boolean get() = enabled && focused
    val pressVisible: Boolean get() = enabled && pressed

    /** Selection remains visible through focus and press; an error boundary takes precedence over it. */
    val edgeState: ChromeInteractionState
        get() = when {
            error -> ChromeInteractionState.Error
            selected -> ChromeInteractionState.Selected
            else -> ChromeInteractionState.Resting
        }

    /**
     * A single label for diagnostics and tests. Rendering must use the independent channels above rather
     * than this priority value, or simultaneous selection/focus/error information would be lost.
     */
    val dominantState: ChromeInteractionState
        get() = when {
            !enabled -> ChromeInteractionState.Disabled
            error -> ChromeInteractionState.Error
            pressVisible -> ChromeInteractionState.Pressed
            focusVisible -> ChromeInteractionState.Focused
            selected -> ChromeInteractionState.Selected
            else -> ChromeInteractionState.Resting
        }

    /** Resolve this control's boundary, focus, press and disabled-content channels in one place. */
    fun visualSpec(): ChromeInteractionVisualSpec = ChromeInteractionVisualSpec(
        edgeState = edgeState,
        edgeWidthDp = when (edgeState) {
            ChromeInteractionState.Selected,
            ChromeInteractionState.Error,
            -> ChromeGeometry.SELECTED_EDGE_WIDTH

            else -> ChromeGeometry.RESTING_EDGE_WIDTH
        },
        showFocusRing = focusVisible,
        showPressLayer = pressVisible,
        contentAlpha = if (enabled) 1f else ChromeGeometry.DISABLED_CONTENT_ALPHA,
    )
}
