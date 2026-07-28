package com.norfold.app.editor.command

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 0 D4 validation-gate coverage for the Command Registry and universal command contract
 * (spec §4.1–§4.4, §4.10). Proves one semantic command behaves identically across surfaces, capability-
 * driven eligibility, presentation-independent state resolution, applicability policies, and registry
 * lookup/dispatch. Pure JVM.
 */
class CommandRegistryTest {

    /** A minimal command that records every context it was performed against. */
    private class FakeCommand(
        override val id: String,
        override val requiredCapabilities: Set<Capability>,
        override val applicability: ApplicabilityPolicy = ApplicabilityPolicy.ALL_TARGETS,
        override val label: String = id,
        override val aliases: List<String> = emptyList(),
    ) : EditorCommand {
        override val description = "desc of $id"
        override val icon: String? = null
        override val category = "test"
        val performedWith = mutableListOf<CommandContext>()
        override fun perform(context: CommandContext): CommandResult {
            performedWith += context
            return CommandResult.Success(affectedCount = context.targetCount)
        }
    }

    private fun ctx(
        offered: Set<Capability> = emptySet(),
        targetCount: Int = 1,
        active: Set<Capability> = emptySet(),
        mixed: Set<Capability> = emptySet(),
        surface: CommandSurface = CommandSurface.DOCK,
    ) = CommandContext(offered, targetCount, hasPrimary = targetCount > 0, active, mixed, surface)

    // --- One semantic command, many surfaces (§4.1) -------------------------------------------------

    @Test fun sameCommand_behavesIdenticallyAcrossEverySurface() {
        val bold = FakeCommand("text.bold", setOf(Capability.TEXT_EDITING))
        val registry = CommandRegistry().register(bold)
        val results = CommandSurface.entries.map { surface ->
            registry.execute("text.bold", ctx(offered = setOf(Capability.TEXT_EDITING), surface = surface))
        }
        // Every route produced the same successful result; the route never gates eligibility.
        assertTrue(results.all { it is CommandResult.Success })
        assertEquals(CommandSurface.entries.size, bold.performedWith.size)
        assertEquals(CommandSurface.entries.toSet(), bold.performedWith.map { it.surface }.toSet())
    }

    // --- Capability-driven eligibility (§4.3) -------------------------------------------------------

    @Test fun command_hiddenWhenSelectionLacksAllTargetsCapability() {
        val crop = FakeCommand("image.crop", setOf(Capability.CROP))
        val registry = CommandRegistry().register(crop)
        val status = registry.statuses(ctx(offered = setOf(Capability.TEXT_EDITING))).getValue(crop)
        assertEquals(CommandState.HIDDEN, status.state)
    }

    @Test fun eligibleSubsetCommand_disabledWithReason_whenNoTargetSupportsIt() {
        val fill = FakeCommand("object.fill", setOf(Capability.FILL), ApplicabilityPolicy.ELIGIBLE_SUBSET)
        val registry = CommandRegistry().register(fill)
        val status = registry.statuses(ctx(offered = setOf(Capability.TEXT_EDITING))).getValue(fill)
        assertEquals(CommandState.DISABLED, status.state)
        assertTrue(status.reason!!.contains("FILL"))
    }

    @Test fun command_hiddenWhenNothingSelected() {
        val group = FakeCommand("object.group", setOf(Capability.GROUPING))
        val registry = CommandRegistry().register(group)
        assertEquals(CommandState.HIDDEN, registry.statuses(ctx(targetCount = 0)).getValue(group).state)
    }

    @Test fun capabilityFreeCommand_alwaysAvailable() {
        val paste = FakeCommand("edit.paste", emptySet())
        val registry = CommandRegistry().register(paste)
        assertEquals(CommandState.AVAILABLE, registry.statuses(ctx(targetCount = 0)).getValue(paste).state)
    }

    // --- Command state resolution (§4.2, §4.11) -----------------------------------------------------

    @Test fun command_activeWhenCapabilityIsOn() {
        val bold = FakeCommand("text.bold", setOf(Capability.TEXT_EDITING))
        val registry = CommandRegistry().register(bold)
        val status = registry.statuses(
            ctx(offered = setOf(Capability.TEXT_EDITING), active = setOf(Capability.TEXT_EDITING)),
        ).getValue(bold)
        assertEquals(CommandState.ACTIVE, status.state)
    }

    @Test fun command_mixedWhenSelectionHasMixedValues() {
        val bold = FakeCommand("text.bold", setOf(Capability.TEXT_EDITING))
        val registry = CommandRegistry().register(bold)
        val status = registry.statuses(
            ctx(offered = setOf(Capability.TEXT_EDITING), mixed = setOf(Capability.TEXT_EDITING)),
        ).getValue(bold)
        assertEquals(CommandState.MIXED, status.state)
    }

    @Test fun command_availableWhenEligibleButNeitherActiveNorMixed() {
        val bold = FakeCommand("text.bold", setOf(Capability.TEXT_EDITING))
        val registry = CommandRegistry().register(bold)
        assertEquals(
            CommandState.AVAILABLE,
            registry.statuses(ctx(offered = setOf(Capability.TEXT_EDITING))).getValue(bold).state,
        )
    }

    // --- Eligibility guard on execute (§4.17) -------------------------------------------------------

    @Test fun execute_isRejectedWhenIneligible_andCommandNeverRuns() {
        val crop = FakeCommand("image.crop", setOf(Capability.CROP))
        val registry = CommandRegistry().register(crop)
        val result = registry.execute("image.crop", ctx(offered = setOf(Capability.TEXT_EDITING)))
        assertTrue(result is CommandResult.Failure)
        assertTrue("Ineligible command must not perform", crop.performedWith.isEmpty())
    }

    @Test fun execute_unknownCommand_failsGracefully() {
        val registry = CommandRegistry()
        assertTrue(registry.execute("nope", ctx()) is CommandResult.Failure)
    }

    @Test fun execute_reportsAffectedCountHonestly() {
        val del = FakeCommand("object.delete", setOf(Capability.SELECTION))
        val registry = CommandRegistry().register(del)
        val result = registry.execute("object.delete", ctx(offered = setOf(Capability.SELECTION), targetCount = 3))
        assertEquals(CommandResult.Success(affectedCount = 3), result)
    }

    // --- Registry lookup / search (§4.4) ------------------------------------------------------------

    @Test fun duplicateId_isRejected() {
        val registry = CommandRegistry().register(FakeCommand("a", emptySet()))
        assertThrows(IllegalArgumentException::class.java) { registry.register(FakeCommand("a", emptySet())) }
    }

    @Test fun resolve_prefersIdThenAliasThenFuzzyPrefix() {
        val insertTable = FakeCommand("table.insert", emptySet(), label = "Insert table", aliases = listOf("grid"))
        val registry = CommandRegistry().register(insertTable)
        assertSame(insertTable, registry.resolve("table.insert"))
        assertSame(insertTable, registry.resolve("grid"))
        assertSame(insertTable, registry.resolve("GRID"))
        assertNull("No fuzzy without opt-in", registry.resolve("Insert t"))
        assertSame(insertTable, registry.resolve("Insert t", fuzzy = true))
    }

    @Test fun search_matchesIdLabelAndAlias_bySubstring() {
        val registry = CommandRegistry().registerAll(
            FakeCommand("text.bold", setOf(Capability.TEXT_EDITING), label = "Bold", aliases = listOf("strong")),
            FakeCommand("text.italic", setOf(Capability.TEXT_EDITING), label = "Italic"),
            FakeCommand("object.group", setOf(Capability.GROUPING), label = "Group"),
        )
        assertEquals(listOf("text.bold", "text.italic"), registry.search("text").map { it.id })
        assertEquals(listOf("text.bold"), registry.search("strong").map { it.id })
        assertTrue(registry.search("").isEmpty())
    }

    @Test fun visibleAndInvokable_filterByResolvedState() {
        val registry = CommandRegistry().registerAll(
            FakeCommand("text.bold", setOf(Capability.TEXT_EDITING)),
            FakeCommand("image.crop", setOf(Capability.CROP), ApplicabilityPolicy.ELIGIBLE_SUBSET),
            FakeCommand("object.group", setOf(Capability.GROUPING)),
        )
        val context = ctx(offered = setOf(Capability.TEXT_EDITING))
        // bold: available; crop: disabled (eligible-subset, still visible); group: hidden.
        assertEquals(setOf("text.bold", "image.crop"), registry.visible(context).map { it.id }.toSet())
        assertEquals(setOf("text.bold"), registry.invokable(context).map { it.id }.toSet())
    }

    @Test fun registrationOrder_isPreserved() {
        val registry = CommandRegistry().registerAll(
            FakeCommand("c", emptySet()), FakeCommand("a", emptySet()), FakeCommand("b", emptySet()),
        )
        assertEquals(listOf("c", "a", "b"), registry.all().map { it.id })
    }
}
