package com.norfold.app.editor.presentation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 0 D7 validation-gate coverage for the [AdaptivePresentationRuntime] (spec §2.6, §3, §5.1). The
 * central gate item: **the same tool state survives a simulated form-factor change** — selected tab, query,
 * scroll and values are preserved while only the presentation mode adapts. Pure JVM.
 */
class AdaptivePresentationRuntimeTest {

    private val phone = FormFactor(widthDp = 400, heightDp = 800)
    private val tablet = FormFactor(widthDp = 700, heightDp = 900)
    private val expanded = FormFactor(widthDp = 1200, heightDp = 900)

    private fun toolState() = ToolPresentationState(
        toolId = "font-browser",
        selectedTab = "Sans",
        query = "inter",
        scroll = 120,
        values = mapOf("preview" to "Aa"),
    )

    // --- Mode selection by form factor (§2.6) -------------------------------------------------------

    @Test fun phone_smallTool_usesPopover_largeTool_usesSheet() {
        val runtime = AdaptivePresentationRuntime(phone)
        assertEquals(PresentationMode.PHONE_POPOVER, runtime.present(toolState(), CompactPreference.POPOVER).mode)
        val runtime2 = AdaptivePresentationRuntime(phone)
        assertEquals(PresentationMode.PHONE_SHEET, runtime2.present(toolState(), CompactPreference.SHEET).mode)
    }

    @Test fun tablet_usesPane_expanded_usesThreePanel() {
        assertEquals(PresentationMode.TABLET_PANE, AdaptivePresentationRuntime(tablet).present(toolState()).mode)
        assertEquals(
            PresentationMode.EXPANDED_THREE_PANEL,
            AdaptivePresentationRuntime(expanded).present(toolState()).mode,
        )
    }

    @Test fun formFactor_breakpointClassification() {
        assertTrue(phone.isPhone)
        assertTrue(tablet.isTablet)
        assertTrue(expanded.isExpanded)
    }

    // --- THE D7 GATE: state survives a form-factor change (§2.6, §5.1) ------------------------------

    @Test fun toolState_survivesFormFactorChange_phoneToExpanded() {
        val runtime = AdaptivePresentationRuntime(phone)
        val original = toolState()
        val presentedPhone = runtime.present(original, CompactPreference.SHEET)
        assertEquals(PresentationMode.PHONE_SHEET, presentedPhone.mode)

        // Simulate rotating/moving to a large screen.
        val after = runtime.onFormFactorChanged(expanded)
        val presented = after.getValue("font-browser")

        assertEquals("Mode adapts to the new form factor", PresentationMode.EXPANDED_THREE_PANEL, presented.mode)
        // Every value is preserved — this is the gate.
        assertEquals("Sans", presented.state.selectedTab)
        assertEquals("inter", presented.state.query)
        assertEquals(120, presented.state.scroll)
        assertEquals("Aa", presented.state.values["preview"])
        assertSame("The exact same state instance is carried forward", original, presented.state)
    }

    @Test fun toolState_survivesRepeatedReflows_inBothDirections() {
        val runtime = AdaptivePresentationRuntime(expanded)
        runtime.present(toolState())
        val toTablet = runtime.onFormFactorChanged(tablet).getValue("font-browser")
        assertEquals(PresentationMode.TABLET_PANE, toTablet.mode)
        val backToPhone = runtime.onFormFactorChanged(phone) { CompactPreference.SHEET }.getValue("font-browser")
        assertEquals(PresentationMode.PHONE_SHEET, backToPhone.mode)
        // After two reflows the values are still intact.
        assertEquals("inter", backToPhone.state.query)
        assertEquals(120, backToPhone.state.scroll)
    }

    @Test fun multipleOpenTools_allPreserveStateAcrossChange() {
        val runtime = AdaptivePresentationRuntime(phone)
        runtime.present(ToolPresentationState("fill", selectedTab = "Wheel", values = mapOf("hex" to "#ff0000")))
        runtime.present(ToolPresentationState("chart", selectedTab = "Series", scroll = 55))

        val after = runtime.onFormFactorChanged(expanded)
        assertEquals(setOf("fill", "chart"), after.keys)
        assertEquals("Wheel", after.getValue("fill").state.selectedTab)
        assertEquals("#ff0000", after.getValue("fill").state.values["hex"])
        assertEquals(55, after.getValue("chart").state.scroll)
        assertTrue(after.values.all { it.mode == PresentationMode.EXPANDED_THREE_PANEL })
    }

    // --- State updates + lifecycle ------------------------------------------------------------------

    @Test fun update_mutatesStoredState_andReopeningKeepsIt() {
        val runtime = AdaptivePresentationRuntime(phone)
        runtime.present(toolState())
        runtime.update(runtime.stateOf("font-browser")!!.withQuery("roboto").withScroll(300))
        // Re-presenting must not reset the updated values.
        val re = runtime.present(toolState())
        assertEquals("roboto", re.state.query)
        assertEquals(300, re.state.scroll)
    }

    @Test fun update_unknownTool_throws() {
        val runtime = AdaptivePresentationRuntime(phone)
        assertThrows(IllegalArgumentException::class.java) {
            runtime.update(ToolPresentationState("ghost"))
        }
    }

    @Test fun close_dropsState() {
        val runtime = AdaptivePresentationRuntime(phone)
        runtime.present(toolState())
        runtime.close("font-browser")
        assertNull(runtime.stateOf("font-browser"))
        assertTrue(runtime.openToolIds().isEmpty())
    }

    @Test fun formFactorChange_withNoOpenTools_isEmpty() {
        val runtime = AdaptivePresentationRuntime(phone)
        assertTrue(runtime.onFormFactorChanged(expanded).isEmpty())
        assertEquals(expanded, runtime.formFactor)
    }

    @Test fun toolStateBuilders_areImmutable() {
        val base = toolState()
        val modified = base.withQuery("x").withValue("k", "v")
        assertEquals("inter", base.query)
        assertEquals("x", modified.query)
        assertEquals("v", modified.values["k"])
    }
}
