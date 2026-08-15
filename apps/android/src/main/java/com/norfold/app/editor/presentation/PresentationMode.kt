package com.norfold.app.editor.presentation

/**
 * How a tool is physically presented (spec §2.6, §5.1). Presentation is a *rendering choice* derived from
 * the current form factor; it never owns the tool's values. The same [ToolPresentationState] can be shown
 * as any of these without a reset.
 */
enum class PresentationMode {
    /** Phone: compact popover anchored to the invoking control. */
    PHONE_POPOVER,

    /** Phone: partial/expandable supporting sheet. */
    PHONE_SHEET,

    /** Tablet: optional pane or anchored surface. */
    TABLET_PANE,

    /** Expanded: Navigator | Content | Inspector three-panel. */
    EXPANDED_THREE_PANEL,
}

/**
 * The physical window/device conditions that drive presentation selection (spec §2.6, §3). Kept as plain
 * data (width/height in dp, plus flags) so the runtime is pure-JVM and a "simulated form-factor change" in
 * tests is just constructing a new instance.
 */
data class FormFactor(
    val widthDp: Int,
    val heightDp: Int,
    val keyboardOpen: Boolean = false,
    /**
     * A physical keyboard is attached. Distinct from [keyboardOpen], which is the on-screen IME: a surface
     * that exists to sit above the IME has nothing to sit above here, and a user with arrow keys and Enter
     * is searching by name rather than aiming at an icon. Defaulted so every existing construction site —
     * and every test — keeps describing a touch device without being edited.
     */
    val hardwareKeyboard: Boolean = false,
) {
    val isExpanded: Boolean get() = widthDp >= EXPANDED_MIN_WIDTH_DP
    val isTablet: Boolean get() = widthDp in TABLET_MIN_WIDTH_DP until EXPANDED_MIN_WIDTH_DP
    val isPhone: Boolean get() = widthDp < TABLET_MIN_WIDTH_DP

    companion object {
        // Material-style breakpoints; the exact values are a design constant, not spec-fixed.
        const val TABLET_MIN_WIDTH_DP = 600
        const val EXPANDED_MIN_WIDTH_DP = 840
    }
}

/**
 * Whether a tool prefers a sheet over a popover when compact. A large-content tool (font/asset browser)
 * wants the sheet; a small action menu wants the popover (spec §2.8).
 */
enum class CompactPreference { POPOVER, SHEET }
