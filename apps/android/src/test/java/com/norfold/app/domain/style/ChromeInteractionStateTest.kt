package com.norfold.app.domain.style

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChromeInteractionStateTest {
    @Test
    fun `the shared vocabulary contains exactly the six promised states`() {
        assertEquals(
            listOf("Resting", "Focused", "Pressed", "Selected", "Disabled", "Error"),
            ChromeInteractionState.entries.map { it.name },
        )
    }

    @Test
    fun `focus is a separate channel and never replaces selection`() {
        val state = ChromeInteractionSnapshot(focused = true, selected = true)

        assertTrue(state.focusVisible)
        assertEquals(ChromeInteractionState.Selected, state.edgeState)
        assertEquals(ChromeInteractionState.Focused, state.dominantState)
    }

    @Test
    fun `press is transient while selection remains persistent`() {
        val state = ChromeInteractionSnapshot(pressed = true, selected = true)

        assertTrue(state.pressVisible)
        assertEquals(ChromeInteractionState.Selected, state.edgeState)
        assertEquals(ChromeInteractionState.Pressed, state.dominantState)
    }

    @Test
    fun `disabled suppresses transient interaction without erasing stored state`() {
        val state = ChromeInteractionSnapshot(
            enabled = false,
            focused = true,
            pressed = true,
            selected = true,
        )

        assertFalse(state.focusVisible)
        assertFalse(state.pressVisible)
        assertEquals(ChromeInteractionState.Selected, state.edgeState)
        assertEquals(ChromeInteractionState.Disabled, state.dominantState)
    }

    @Test
    fun `error boundary wins without deleting focus or selection`() {
        val state = ChromeInteractionSnapshot(focused = true, selected = true, error = true)

        assertTrue(state.focusVisible)
        assertTrue(state.selected)
        assertEquals(ChromeInteractionState.Error, state.edgeState)
        assertEquals(ChromeInteractionState.Error, state.dominantState)
    }

    @Test
    fun `focus changes only its ring and never impersonates a selected boundary`() {
        val resting = ChromeInteractionSnapshot().visualSpec()
        val focused = ChromeInteractionSnapshot(focused = true).visualSpec()

        assertEquals(resting.edgeState, focused.edgeState)
        assertEquals(resting.edgeWidthDp, focused.edgeWidthDp)
        assertFalse(resting.showFocusRing)
        assertTrue(focused.showFocusRing)
    }

    @Test
    fun `selection and error own the stronger persistent boundary`() {
        val selected = ChromeInteractionSnapshot(selected = true).visualSpec()
        val error = ChromeInteractionSnapshot(error = true).visualSpec()

        assertEquals(ChromeGeometry.SELECTED_EDGE_WIDTH, selected.edgeWidthDp)
        assertEquals(ChromeGeometry.SELECTED_EDGE_WIDTH, error.edgeWidthDp)
        assertEquals(ChromeInteractionState.Selected, selected.edgeState)
        assertEquals(ChromeInteractionState.Error, error.edgeState)
    }

    @Test
    fun `press changes only its transient state layer`() {
        val resting = ChromeInteractionSnapshot().visualSpec()
        val pressed = ChromeInteractionSnapshot(pressed = true).visualSpec()

        assertEquals(resting.edgeState, pressed.edgeState)
        assertEquals(resting.edgeWidthDp, pressed.edgeWidthDp)
        assertFalse(resting.showPressLayer)
        assertTrue(pressed.showPressLayer)
    }

    @Test
    fun `disabled appearance is bounded and suppresses transient channels`() {
        val disabled = ChromeInteractionSnapshot(
            enabled = false,
            focused = true,
            pressed = true,
        ).visualSpec()

        assertTrue(disabled.contentAlpha > 0f)
        assertTrue(disabled.contentAlpha < 1f)
        assertFalse(disabled.showFocusRing)
        assertFalse(disabled.showPressLayer)
    }
}
