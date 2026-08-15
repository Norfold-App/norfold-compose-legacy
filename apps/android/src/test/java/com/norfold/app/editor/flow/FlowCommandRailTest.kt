package com.norfold.app.editor.flow

import com.norfold.app.domain.BlockDocument
import com.norfold.app.domain.CodeBlock
import com.norfold.app.domain.InlineText
import com.norfold.app.domain.ParagraphBlock
import com.norfold.app.domain.findById
import com.norfold.app.editor.command.CommandStatus
import com.norfold.app.editor.history.EditTarget
import com.norfold.app.editor.presentation.FormFactor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The command rail's behaviour table, asserted on the desktop JVM.
 *
 * The rail is the interesting half of the slash redesign precisely because it is a *table*, not a drawing:
 * what a bare `/`, a typed character, a backspace, an arrow, a hardware keyboard and a wide window each do.
 * All of that is decided in [FlowCommandRail] and [FlowHostRuntime], neither of which imports Compose, so it
 * is checkable here rather than read off a device screenshot.
 *
 * The property these tests exist to protect is that the rail and the list are one session wearing two
 * presentations. Every assertion about switching between them also asserts that the session survived it.
 */
class FlowCommandRailTest {

    private val phone = FormFactor(widthDp = 400, heightDp = 900)

    private fun runtimeOf(
        vararg blocks: com.norfold.app.domain.DocumentBlock,
        formFactor: FormFactor = phone,
    ): FlowHostRuntime = FlowHostRuntime(
        controller = FlowEditController(EditTarget("note:rail"), BlockDocument(blocks.toList())),
        initialFormFactor = formFactor,
    )

    private fun para(id: String, text: String) =
        ParagraphBlock(id = id, content = listOf(InlineText(text)))

    /** A stand-in for one entry of the runtime's visible set, for asserting [FlowCommandRail.build] directly. */
    private fun item(id: String) = FlowCommandItem(
        id = id,
        label = id,
        description = "",
        category = "",
        status = CommandStatus.Available,
        shortcut = null,
    )

    /** Opens a slash session by typing [text] into `p0`, exactly as the surface reports it. */
    private fun FlowHostRuntime.type(text: String): FlowSlashSession? {
        setCaret("p0", text.length)
        return onTextInput("p0", FlowSlashInput(text = text, caret = text.length))
    }

    private fun railOf(runtime: FlowHostRuntime): FlowRailState =
        requireNotNull(runtime.slashRail()) { "no slash session is open" }

    // ── Presentation: which form the one session wears ───────────────────────────────────────────────

    @Test
    fun bareSlash_opensTheRail() {
        val runtime = runtimeOf(para("p0", "/"))
        assertNotNull(runtime.type("/"))
        assertEquals(FlowSlashPresentation.RAIL, runtime.slashPresentation())
        assertTrue(railOf(runtime).isRail)
    }

    @Test
    fun typedQuery_promotesToTheList() {
        val runtime = runtimeOf(para("p0", "/h"))
        runtime.type("/h")
        assertEquals(FlowSlashPresentation.LIST, runtime.slashPresentation())
    }

    /**
     * The bare-`/` rail is recoverable by deleting the query. This is the pair of the promotion above: the
     * presentation follows the text, so the user is never stranded in the list by a typo they backed out of.
     */
    @Test
    fun backspacingToABareSlash_returnsToTheRail() {
        val runtime = runtimeOf(para("p0", "/h"))
        runtime.type("/h")
        runtime.type("/")
        assertEquals(FlowSlashPresentation.RAIL, runtime.slashPresentation())
    }

    /**
     * …unless the user opened the list themselves. A presentation the user chose outranks one the text
     * implies, or tapping the chevron and then fixing a typo would yank the list away.
     */
    @Test
    fun aListTheUserOpened_survivesBackspacingToABareSlash() {
        val runtime = runtimeOf(para("p0", "/"))
        runtime.type("/")
        assertTrue(runtime.openSlashList())
        runtime.type("/")
        assertEquals(FlowSlashPresentation.LIST, runtime.slashPresentation())
    }

    @Test
    fun aHardwareKeyboard_goesStraightToTheList() {
        val runtime = runtimeOf(para("p0", "/"), formFactor = phone.copy(hardwareKeyboard = true))
        runtime.type("/")
        assertEquals(FlowSlashPresentation.LIST, runtime.slashPresentation())
    }

    @Test
    fun anExpandedWindow_goesStraightToTheList() {
        val runtime = runtimeOf(para("p0", "/"), formFactor = FormFactor(widthDp = 1100, heightDp = 800))
        runtime.type("/")
        assertEquals(FlowSlashPresentation.LIST, runtime.slashPresentation())
    }

    @Test
    fun aRotationIntoAnExpandedWindow_promotesTheOpenSession() {
        val runtime = runtimeOf(para("p0", "/"))
        runtime.type("/")
        assertEquals(FlowSlashPresentation.RAIL, runtime.slashPresentation())
        runtime.onFormFactorChanged(FormFactor(widthDp = 1100, heightDp = 800))
        assertEquals(FlowSlashPresentation.LIST, runtime.slashPresentation())
        assertNotNull("the session itself survives a presentation change", runtime.slashSession())
    }

    @Test
    fun noSession_hasNoRail() {
        val runtime = runtimeOf(para("p0", "plain text"))
        runtime.type("plain text")
        assertNull(runtime.slashRail())
        assertFalse(runtime.openSlashList())
        assertFalse(runtime.popRailLevel())
        assertFalse(runtime.moveRailFocus(1))
    }

    // ── Structure: what the rail holds ───────────────────────────────────────────────────────────────

    @Test
    fun theTopRail_leadsWithFeaturedCommands_thenGroups() {
        val runtime = runtimeOf(para("p0", "/"))
        runtime.type("/")
        val rail = railOf(runtime)
        assertTrue(rail.atTopLevel)
        val featured = rail.slots.takeWhile { it is FlowRailSlot.Command }
        val groups = rail.slots.drop(featured.size)
        assertTrue("featured slots come first", featured.isNotEmpty())
        assertTrue("groups follow and nothing follows them", groups.all { it is FlowRailSlot.Group })
        assertEquals(
            "featured order is the declared order, minus anything this context hides",
            FlowCommandRail.FEATURED.filter { id -> featured.any { it.id == id } },
            featured.map { it.id },
        )
    }

    /**
     * Every command the slash surface offers is behind exactly one group, so the rail's drilldowns cover the
     * catalogue. Without this, a command could be reachable only by typing its name — which is precisely the
     * discoverability the rail exists to provide.
     */
    @Test
    fun everyVisibleCommand_isReachableThroughExactlyOneGroup() {
        val runtime = runtimeOf(para("p0", "/"))
        runtime.type("/")
        val all = railOf(runtime).slots
            .filterIsInstance<FlowRailSlot.Group>()
            .flatMap { group ->
                runtime.openRailGroup(group.group)
                railOf(runtime).listItems.map { it.id }
            }
        assertEquals("no command appears in two groups", all.size, all.distinct().size)

        runtime.popRailLevel()
        val expected = railOf(runtime).slots.filterIsInstance<FlowRailSlot.Group>().sumOf { it.size }
        assertEquals("the groups account for every visible command", expected, all.size)
    }

    /**
     * A featured command the context hides is dropped rather than greyed out. An icon-only strip cannot say
     * why something is unavailable, and a row that can explain itself is exactly what the list is for.
     *
     * Asserted against [FlowCommandRail.build] directly, because the visible set is the input to the rail and
     * hiding a specific command through the runtime would mean driving the whole command context to produce
     * one absence. The runtime half of the claim — that no slot the rail actually carries is disabled — is
     * asserted on a real session below it.
     */
    @Test
    fun aFeaturedCommandTheContextHides_isDroppedNotDisabled() {
        val kept = FlowIds.convert(FlowBlockKind.QUOTE)
        val dropped = FlowIds.convert(FlowBlockKind.CODE)
        val rail = FlowCommandRail.build(visible = listOf(item(kept)))

        assertTrue("the visible featured command is on the rail", rail.slots.any { it.id == kept })
        assertTrue("the hidden one is absent, not present-and-grey", rail.slots.none { it.id == dropped })
        assertTrue("so the rail carries no disabled slot", rail.slots.all { it.enabled })
    }

    @Test
    fun aRealSession_neverPutsADisabledSlotOnTheRail() {
        val runtime = runtimeOf(para("p0", "/"), CodeBlock(id = "c0"))
        runtime.type("/")
        assertTrue(railOf(runtime).slots.all { it.enabled })
        runtime.openRailGroup(FlowCommandGroup.TURN_INTO)
        assertTrue(railOf(runtime).slots.all { it.enabled })
    }

    @Test
    fun aGroup_swapsTheRailInPlace_withBackFirstAndFocusPastIt() {
        val runtime = runtimeOf(para("p0", "/"))
        runtime.type("/")
        assertTrue(runtime.openRailGroup(FlowCommandGroup.TURN_INTO))

        val rail = railOf(runtime)
        assertEquals(FlowCommandGroup.TURN_INTO, rail.group)
        assertFalse(rail.atTopLevel)
        assertEquals(FlowRailSlot.Back, rail.slots.first())
        assertEquals("focus lands on the first command, not on Back", 1, rail.focusedIndex)
        assertTrue("a drilled-in rail is nothing but Back and commands", rail.slots.drop(1).all { it is FlowRailSlot.Command })
        assertTrue(
            "Turn into still contains Heading 1 even though it is also featured",
            rail.slots.any { it.id == FlowIds.convert(FlowBlockKind.HEADING_1) },
        )
        assertEquals("the drilldown scopes a promoted list", rail.slots.size - 1, rail.listItems.size)
        assertTrue(rail.scoped)
    }

    @Test
    fun theTopRail_scopesNothing() {
        val runtime = runtimeOf(para("p0", "/"))
        runtime.type("/")
        val rail = railOf(runtime)
        assertTrue("at the top level a promoted list shows the ranked results", rail.listItems.isEmpty())
        assertFalse(rail.scoped)
    }

    // ── Focus ────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun focus_wrapsInBothDirections() {
        val runtime = runtimeOf(para("p0", "/"))
        runtime.type("/")
        val last = railOf(runtime).slots.lastIndex

        assertTrue(runtime.moveRailFocus(-1))
        assertEquals("stepping back from the first slot reaches the last", last, railOf(runtime).focusedIndex)
        assertTrue(runtime.moveRailFocus(1))
        assertEquals(0, railOf(runtime).focusedIndex)
    }

    /**
     * Focus is clamped by [FlowCommandRail.build] itself rather than validated at every mutation, because the
     * rail can shrink without anything touching the focus: the visible set is recomputed from the command
     * context on every read, so a selection change alone can pull slots out from under the highlight.
     */
    @Test
    fun focus_isClampedWhenTheRailShrinksUnderIt() {
        val wide = FlowCommandRail.build(visible = FlowCommandRail.FEATURED.map(::item), focusedIndex = 7)
        assertEquals(7, wide.focusedIndex)

        val narrow = FlowCommandRail.build(visible = listOf(item(FlowCommandRail.FEATURED.first())), focusedIndex = 7)
        assertEquals("focus never points past the end", narrow.slots.lastIndex, narrow.focusedIndex)
        assertNotNull(narrow.focused)
    }

    @Test
    fun focusSurvivesDroppingAPromotedListBackToTheRail() {
        val runtime = runtimeOf(para("p0", "/"))
        runtime.type("/")
        runtime.openRailGroup(FlowCommandGroup.INSERT)
        runtime.moveRailFocus(2)
        val focus = railOf(runtime).focusedIndex

        assertTrue(runtime.openSlashList())
        assertTrue(runtime.popRailLevel())
        assertEquals(FlowSlashPresentation.RAIL, runtime.slashPresentation())
        assertEquals("the highlight does not relocate", focus, railOf(runtime).focusedIndex)
        assertEquals("and the group is still open", FlowCommandGroup.INSERT, railOf(runtime).group)
    }

    // ── Popping: the Backspace contract ──────────────────────────────────────────────────────────────

    /**
     * The signal the surface needs in order *not* to intercept Backspace. At the top of the rail the `/` is
     * ordinary text and deleting it is what dismisses the session, so a `false` here is what keeps the key
     * from being swallowed into a dead press.
     */
    @Test
    fun popping_returnsFalseAtTheTopOfTheRail() {
        val runtime = runtimeOf(para("p0", "/"))
        runtime.type("/")
        assertFalse(runtime.popRailLevel())
        assertNotNull("and refusing to pop does not close the session", runtime.slashSession())
    }

    @Test
    fun popping_unwindsTheListThenTheGroupThenStops() {
        val runtime = runtimeOf(para("p0", "/"))
        runtime.type("/")
        runtime.openRailGroup(FlowCommandGroup.ACTIONS)
        runtime.openSlashList()

        assertTrue("first pop puts the names away", runtime.popRailLevel())
        assertEquals(FlowSlashPresentation.RAIL, runtime.slashPresentation())
        assertEquals(FlowCommandGroup.ACTIONS, railOf(runtime).group)

        assertTrue("second pop leaves the group", runtime.popRailLevel())
        assertTrue(railOf(runtime).atTopLevel)

        assertFalse("and then there is nothing left to pop", runtime.popRailLevel())
    }

    @Test
    fun openingTheListTwice_isNotASecondLevelToPop() {
        val runtime = runtimeOf(para("p0", "/"))
        runtime.type("/")
        assertTrue(runtime.openSlashList())
        assertFalse("already open", runtime.openSlashList())
        assertTrue(runtime.popRailLevel())
        assertEquals(FlowSlashPresentation.RAIL, runtime.slashPresentation())
    }

    // ── Session lifetime: the drilldown must not outlive its trigger ─────────────────────────────────

    /**
     * A `/` only opens where the line so far is blank — that is [FlowSlashResolver.detect]'s rule — so a
     * second invocation at a different offset is reached by indenting the line, not by typing further along
     * it. Either way the drilldown must not be inherited: the next `/` is a new invocation, not the first one
     * edited.
     */
    @Test
    fun aTriggerAtANewOffset_startsAtTheTopOfTheRail() {
        val runtime = runtimeOf(para("p0", "/"))
        runtime.type("/")
        runtime.openRailGroup(FlowCommandGroup.INSERT)
        assertEquals(FlowCommandGroup.INSERT, railOf(runtime).group)

        runtime.type("  /")
        assertEquals("the trigger moved", 2, runtime.slashSession()!!.query.triggerOffset)
        assertTrue("so the rail opens at the top", railOf(runtime).atTopLevel)
        assertEquals(0, railOf(runtime).focusedIndex)
    }

    /** The same guard on the path where the text stops being a command at all and then becomes one again. */
    @Test
    fun aSessionTheTextClosed_leavesNoDrilldownForTheNextOne() {
        val runtime = runtimeOf(para("p0", "/"))
        runtime.type("/")
        runtime.openRailGroup(FlowCommandGroup.INSERT)

        assertNull("no slash, no session", runtime.type("word"))
        assertNull(runtime.slashRail())

        runtime.type("/")
        assertTrue(railOf(runtime).atTopLevel)
        assertEquals(FlowSlashPresentation.RAIL, runtime.slashPresentation())
    }

    @Test
    fun closingTheSession_forgetsTheDrilldown() {
        val runtime = runtimeOf(para("p0", "/"))
        runtime.type("/")
        runtime.openRailGroup(FlowCommandGroup.TABLE)
        runtime.openSlashList()

        runtime.closeSlash()
        assertNull(runtime.slashRail())

        runtime.type("/")
        val rail = railOf(runtime)
        assertTrue("a closed session leaves nothing behind for the next one", rail.atTopLevel)
        assertEquals(FlowSlashPresentation.RAIL, rail.presentation)
    }

    // ── Activation ───────────────────────────────────────────────────────────────────────────────────

    @Test
    fun activating_aGroupDrillsIn_andBackPopsOut_neitherReportingACommandResult() {
        val runtime = runtimeOf(para("p0", "/"))
        runtime.type("/")
        val group = railOf(runtime).slots.filterIsInstance<FlowRailSlot.Group>().first()
        runtime.moveRailFocus(railOf(runtime).slots.indexOf(group))

        assertNull("drilling in is a move, not a command", runtime.activateRailFocus())
        assertEquals(group.group, railOf(runtime).group)

        runtime.moveRailFocus(-railOf(runtime).focusedIndex)
        assertEquals(FlowRailSlot.Back, railOf(runtime).focused)
        assertNull("and so is going back", runtime.activateRailFocus())
        assertTrue(railOf(runtime).atTopLevel)
    }

    /** Activating a command runs the *same* command the list would run, and takes the trigger with it. */
    @Test
    fun activating_aCommandRunsItAndErasesTheTrigger() {
        val runtime = runtimeOf(para("p0", "/"))
        runtime.type("/")
        runtime.openRailGroup(FlowCommandGroup.TURN_INTO)
        val target = railOf(runtime).slots
            .indexOfFirst { it.id == FlowIds.convert(FlowBlockKind.HEADING_1) }
        assertTrue("Turn into offers Heading 1", target > 0)
        runtime.moveRailFocus(target - railOf(runtime).focusedIndex)

        val result = runtime.activateRailFocus()
        assertTrue("a command slot reports its result", result is com.norfold.app.editor.command.CommandResult.Success)
        assertNull("running a command closes the session", runtime.slashRail())
        assertEquals(
            "the block converted and the `/` is gone",
            "",
            runtime.controller.document().findById("p0")!!.plainText(),
        )
    }

    // ── The group table itself ───────────────────────────────────────────────────────────────────────

    @Test
    fun groupMembership_followsTheIdNamespace() {
        assertEquals(FlowCommandGroup.TABLE, FlowCommandGroup.of("flow.table.addRowBelow"))
        assertEquals(FlowCommandGroup.TURN_INTO, FlowCommandGroup.of(FlowIds.convert(FlowBlockKind.QUOTE)))
        assertEquals(FlowCommandGroup.INSERT, FlowCommandGroup.of(FlowIds.insert(FlowBlockKind.TABLE)))
        assertEquals(FlowCommandGroup.INSERT, FlowCommandGroup.of(FlowIds.INSERT_IMAGE))
        assertEquals("anything else is an action", FlowCommandGroup.ACTIONS, FlowCommandGroup.of(FlowIds.DUPLICATE))
    }

    @Test
    fun buildingWithNothingVisible_yieldsAnEmptyRailRatherThanAnInvalidOne() {
        val rail = FlowCommandRail.build(visible = emptyList())
        assertTrue(rail.slots.isEmpty())
        assertEquals(0, rail.focusedIndex)
        assertNull(rail.focused)
        assertEquals(0, FlowCommandRail.moveFocus(rail, 1))
    }
}
