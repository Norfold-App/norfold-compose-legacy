package com.norfold.app.editor.surface

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 0 D6 validation-gate coverage for the [SurfaceCoordinator] (spec §5.2–§5.6). Proves each surface
 * class's dismissal policy (§5.3, no universal close-on-click), the one-level-at-a-time back hierarchy
 * (§5.4), parent/child context preservation (§5.5), and retargeting outcomes (§5.6). Pure JVM.
 */
class SurfaceCoordinatorTest {

    private fun surface(
        id: String,
        klass: SurfaceClass,
        dismissal: DismissalPolicy = klass.defaultDismissal,
        compatible: Set<String> = emptySet(),
        preserved: PreservedContext = PreservedContext(),
        pinned: Boolean = false,
    ) = ToolSurface(id, klass, dismissal, compatible, preserved, pinned)

    // --- Dismissal policies per surface class (§5.2, §5.3) ------------------------------------------

    @Test fun commandMenu_closesAfterCommand_notOnOutsideTap() {
        val c = SurfaceCoordinator()
        val menu = surface("menu", SurfaceClass.COMMAND_MENU)
        assertTrue(c.shouldDismiss(menu, DismissTrigger.COMMAND_EXECUTED))
        assertFalse("Command menu ignores outside taps", c.shouldDismiss(menu, DismissTrigger.OUTSIDE_TAP))
    }

    @Test fun continuousTool_staysOpenAfterCommand_closesOnOutsideOrBack() {
        val c = SurfaceCoordinator()
        val tool = surface("tool", SurfaceClass.CONTINUOUS_TOOL)
        assertFalse("Continuous tool remains open after a command", c.shouldDismiss(tool, DismissTrigger.COMMAND_EXECUTED))
        assertTrue(c.shouldDismiss(tool, DismissTrigger.OUTSIDE_TAP))
        assertTrue(c.shouldDismiss(tool, DismissTrigger.BACK))
    }

    @Test fun focusTool_requiresExplicitDone_swallowsBackAndOutside() {
        val c = SurfaceCoordinator()
        val focus = surface("focus", SurfaceClass.FOCUS_TOOL)
        assertTrue(focus.requiresExplicitDone)
        assertFalse(c.shouldDismiss(focus, DismissTrigger.OUTSIDE_TAP))
        assertFalse("Back alone does not close a focus tool", c.shouldDismiss(focus, DismissTrigger.BACK))
        assertTrue(c.shouldDismiss(focus, DismissTrigger.DONE))
        assertTrue(c.shouldDismiss(focus, DismissTrigger.CANCEL))
    }

    @Test fun decisionModal_closesOnlyOnBackOrCancel() {
        val c = SurfaceCoordinator()
        val modal = surface("modal", SurfaceClass.DECISION_MODAL)
        assertFalse(c.shouldDismiss(modal, DismissTrigger.OUTSIDE_TAP))
        assertFalse(c.shouldDismiss(modal, DismissTrigger.COMMAND_EXECUTED))
        assertTrue(c.shouldDismiss(modal, DismissTrigger.BACK))
    }

    @Test fun navigator_isContextControlled_notClosedByOutsideTap() {
        val c = SurfaceCoordinator()
        val nav = surface("nav", SurfaceClass.NAVIGATOR)
        assertFalse(c.shouldDismiss(nav, DismissTrigger.OUTSIDE_TAP))
        assertTrue(c.shouldDismiss(nav, DismissTrigger.BACK))
    }

    @Test fun pinnableSurface_staysUntilUnpinned() {
        val c = SurfaceCoordinator()
        val pinned = surface("p", SurfaceClass.PICKER_BROWSER, dismissal = DismissalPolicy.PINNABLE, pinned = true)
        assertFalse("Pinned surface ignores outside tap", c.shouldDismiss(pinned, DismissTrigger.OUTSIDE_TAP))
        assertTrue(c.shouldDismiss(pinned, DismissTrigger.UNPIN))
        val unpinned = pinned.copy(pinned = false)
        assertTrue(c.shouldDismiss(unpinned, DismissTrigger.OUTSIDE_TAP))
    }

    @Test fun dispatch_retainsSurfaceWhenPolicyDoesNotDismiss() {
        val c = SurfaceCoordinator()
        c.open(surface("focus", SurfaceClass.FOCUS_TOOL))
        val result = c.dispatch(DismissTrigger.OUTSIDE_TAP)
        assertTrue(result is DispatchResult.Retained)
        assertEquals(1, c.depth)
    }

    @Test fun dispatch_onEmptyStack_reportsNoSurface() {
        assertEquals(DispatchResult.NoSurface, SurfaceCoordinator().dispatch(DismissTrigger.BACK))
    }

    // --- Back hierarchy unwinds one level at a time (§5.4) ------------------------------------------

    @Test fun back_unwindsChildThenParentThenSelectionThenEditor() {
        val c = SurfaceCoordinator()
        val parent = surface("parent", SurfaceClass.CONTINUOUS_TOOL)
        val child = surface("child", SurfaceClass.PICKER_BROWSER)
        c.open(parent)
        c.openChild(child)

        // 1) child picker -> parent tool
        val step1 = c.back(hasSelectionMode = true)
        assertTrue(step1 is BackOutcome.ClosedSurface)
        assertEquals("child", (step1 as BackOutcome.ClosedSurface).closed.id)
        assertEquals("parent", step1.nowActive!!.id)

        // 2) parent tool -> editor (dock/selection level still active)
        val step2 = c.back(hasSelectionMode = true)
        assertEquals("parent", (step2 as BackOutcome.ClosedSurface).closed.id)
        assertNull(step2.nowActive)

        // 3) dock/selection mode -> ordinary editor
        assertEquals(BackOutcome.ExitedSelectionMode, c.back(hasSelectionMode = true))

        // 4) ordinary editor -> previous app screen
        assertEquals(BackOutcome.LeftEditor, c.back(hasSelectionMode = false))
    }

    @Test fun back_isConsumedByFocusToolRequiringDone() {
        val c = SurfaceCoordinator()
        c.open(surface("focus", SurfaceClass.FOCUS_TOOL))
        val outcome = c.back(hasSelectionMode = false)
        assertTrue("Focus tool consumes Back instead of closing", outcome is BackOutcome.Consumed)
        assertEquals(1, c.depth)
    }

    // --- Parent/child context preservation (§5.5) --------------------------------------------------

    @Test fun openingChild_preservesParentContextOnStack() {
        val c = SurfaceCoordinator()
        val parent = surface(
            "parent", SurfaceClass.CONTINUOUS_TOOL,
            preserved = PreservedContext(targetId = "obj-1", query = "blu", scroll = 40, values = mapOf("side" to "left")),
        )
        c.open(parent)
        c.openChild(surface("swatch", SurfaceClass.PICKER_BROWSER))

        c.dispatch(DismissTrigger.BACK) // close child
        val restoredParent = c.active()!!
        assertEquals("obj-1", restoredParent.preserved.targetId)
        assertEquals("blu", restoredParent.preserved.query)
        assertEquals(40, restoredParent.preserved.scroll)
        assertEquals("left", restoredParent.preserved.values["side"])
    }

    @Test fun openChild_withoutParent_throws() {
        assertThrows(IllegalArgumentException::class.java) {
            SurfaceCoordinator().openChild(surface("orphan", SurfaceClass.PICKER_BROWSER))
        }
    }

    // --- Retargeting (§5.6) -------------------------------------------------------------------------

    @Test fun retarget_compatibleTarget_retargetsAndPreservesTool() {
        val c = SurfaceCoordinator()
        c.open(surface("fill", SurfaceClass.CONTINUOUS_TOOL, compatible = setOf("DOCUMENT", "CHART")))
        val outcome = c.retarget("CHART", "chart-9")
        assertEquals(RetargetOutcome.Retargeted("chart-9"), outcome)
        assertEquals("Tool stays open, retargeted", "chart-9", c.active()!!.preserved.targetId)
        assertEquals(1, c.depth)
    }

    @Test fun retarget_incompatibleTarget_collapsesTool() {
        val c = SurfaceCoordinator()
        c.open(surface("fill", SurfaceClass.CONTINUOUS_TOOL, compatible = setOf("DOCUMENT")))
        val outcome = c.retarget("RELATIONSHIP", "rel-2")
        assertEquals(RetargetOutcome.Collapsed, outcome)
        assertEquals("Incompatible retarget closes the tool", 0, c.depth)
    }

    @Test fun retarget_multiSelection_showsMixedStateWithCounts() {
        val c = SurfaceCoordinator()
        c.open(surface("fill", SurfaceClass.CONTINUOUS_TOOL, compatible = setOf("DOCUMENT")))
        val outcome = c.retarget("DOCUMENT", "obj-x", selectionCount = 4)
        assertEquals(RetargetOutcome.MixedState(4, 4), outcome)
    }

    @Test fun defaultDismissalPerClass_matchesSpec() {
        assertEquals(DismissalPolicy.AFTER_COMMAND, SurfaceClass.COMMAND_MENU.defaultDismissal)
        assertEquals(DismissalPolicy.EXPLICIT_DONE, SurfaceClass.FOCUS_TOOL.defaultDismissal)
        assertEquals(DismissalPolicy.BACK_ONLY, SurfaceClass.DECISION_MODAL.defaultDismissal)
        assertEquals(DismissalPolicy.CONTEXT_CONTROLLED, SurfaceClass.NAVIGATOR.defaultDismissal)
    }

    @Test fun closeAll_clearsStack() {
        val c = SurfaceCoordinator()
        c.open(surface("a", SurfaceClass.CONTINUOUS_TOOL))
        c.openChild(surface("b", SurfaceClass.PICKER_BROWSER))
        c.closeAll()
        assertEquals(0, c.depth)
        assertNull(c.active())
    }
}
