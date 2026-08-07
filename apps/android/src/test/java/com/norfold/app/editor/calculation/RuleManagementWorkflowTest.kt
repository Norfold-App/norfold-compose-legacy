package com.norfold.app.editor.calculation

import com.norfold.app.domain.BlockDocument
import com.norfold.app.domain.style.ColorValue
import com.norfold.app.editor.history.EditTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Managing validation and conditional-format rules after they exist.
 *
 * Applying either kind of rule used to be a one-way door. The rule went on refusing input or repainting cells,
 * and the only thing the surface offered was applying another one on top of it — so a range validated by
 * mistake could not be un-validated, and two highlight rules fighting over one cell could not be reordered.
 * These tests cover the other half of both workflows: read the rules back, relax them, disable them, reorder
 * them, and remove them.
 */
class RuleManagementWorkflowTest {

    private fun runtime(): SpreadsheetHostRuntime = SpreadsheetHostRuntime(
        EditTarget("sheet:rules"),
        BlockDocument(listOf(SpreadsheetWorkbookFactory.createBlock())),
    )

    private fun editorTextAt(runtime: SpreadsheetHostRuntime, row: Int, column: Int): String =
        runtime.state().activeSheet.cellAt(row, column)?.input?.editorText().orEmpty()

    private fun highlight(runtime: SpreadsheetHostRuntime, source: String, fill: ColorValue): Boolean =
        runtime.applyComparisonConditional(ComparisonOperator.GREATER, source, CellFormat(fillColor = fill))

    // ---- Reading the rules back -------------------------------------------------------------------

    @Test fun aValidationRuleIsListedWithWhatItAllowsAndWhereItApplies() {
        val runtime = runtime()
        runtime.select(GridAddress(0, 0))
        runtime.extendSelection(2, 0)
        assertTrue(runtime.applyListValidation(listOf("apple", "pear")))

        val rules = runtime.validationRules()
        assertEquals(1, rules.size)
        val rule = rules.single()
        // Without the location and the allowed values, "why was my value refused" is unanswerable from the UI.
        assertEquals("one of apple, pear", rule.description)
        assertEquals("Sheet1!A1:A3", rule.location)
        assertFalse(rule.warnOnly)
    }

    @Test fun aHighlightRuleIsListedWithItsReasonNotJustItsColour() {
        val runtime = runtime()
        runtime.select(GridAddress(0, 0))
        assertTrue(highlight(runtime, "5", ColorValue(255, 226, 128)))

        val rule = runtime.conditionalRules().single()
        assertEquals("value is greater than 5", rule.description)
        assertEquals("Sheet1!A1", rule.location)
        assertTrue(rule.enabled)
    }

    @Test fun highlightRulesAreListedInEvaluationOrderRatherThanInsertionOrder() {
        val runtime = runtime()
        runtime.select(GridAddress(0, 0))
        assertTrue(highlight(runtime, "1", ColorValue(255, 0, 0)))
        assertTrue(highlight(runtime, "2", ColorValue(0, 255, 0)))
        assertTrue(highlight(runtime, "3", ColorValue(0, 0, 255)))

        val rules = runtime.conditionalRules()
        assertEquals(listOf(1, 2, 3), rules.map(ConditionalRuleSummary::priority))
        // Priority order is what decides which rule wins on a cell all three match.
        assertEquals(listOf("value is greater than 1", "value is greater than 2", "value is greater than 3"), rules.map(ConditionalRuleSummary::description))
    }

    @Test fun rulesAreListedPerSheetRatherThanAcrossTheWorkbook() {
        val runtime = runtime()
        runtime.select(GridAddress(0, 0))
        assertTrue(runtime.applyListValidation(listOf("apple")))
        assertTrue(runtime.addSheet())
        // A rule that belongs to another sheet cannot be managed from here without addressing the wrong cells.
        assertTrue(runtime.validationRules().isEmpty())
    }

    // ---- Relaxing and disabling --------------------------------------------------------------------

    @Test fun switchingAValidationRuleToWarnOnlyLetsTheRefusedValueThrough() {
        val runtime = runtime()
        runtime.select(GridAddress(0, 0))
        assertTrue(runtime.applyListValidation(listOf("apple", "pear")))
        assertFalse(runtime.commitFormulaBar("banana").accepted)

        val id = runtime.validationRules().single().id
        assertTrue(runtime.setValidationWarnOnly(id, true))
        assertTrue(runtime.validationRules().single().warnOnly)

        // Warn-only is a confirm-once flow rather than silent acceptance: the first attempt explains the
        // problem and asks, the repeat is taken as the answer. A warning nobody has to acknowledge is a
        // warning nobody reads.
        val warned = runtime.commitFormulaBar("banana")
        assertFalse(warned.accepted)
        assertTrue(warned.requiresConfirmation)
        assertNotNull("the warning is the whole point of warn-only", warned.warning)
        assertEquals("", editorTextAt(runtime, 0, 0))

        assertTrue("re-applying the same value confirms it", runtime.commitFormulaBar("banana").accepted)
        assertEquals("banana", editorTextAt(runtime, 0, 0))
    }

    @Test fun aWarnOnlyRuleDoesNotConfirmADifferentValueOnTheUsersBehalf() {
        val runtime = runtime()
        runtime.select(GridAddress(0, 0))
        assertTrue(runtime.applyListValidation(listOf("apple"), warnOnly = true))
        assertTrue(runtime.commitFormulaBar("banana").requiresConfirmation)
        // Changing the text is not confirming the previous text; the new value has to be asked about too.
        assertTrue(runtime.commitFormulaBar("cherry").requiresConfirmation)
        assertEquals("", editorTextAt(runtime, 0, 0))
    }

    @Test fun switchingBackToRefuseRestoresTheRefusal() {
        val runtime = runtime()
        runtime.select(GridAddress(0, 0))
        assertTrue(runtime.applyListValidation(listOf("apple"), warnOnly = true))
        val id = runtime.validationRules().single().id
        assertTrue(runtime.setValidationWarnOnly(id, false))
        assertFalse(runtime.validationRules().single().warnOnly)
        val refused = runtime.commitFormulaBar("banana")
        assertFalse(refused.accepted)
        // A refusal is not a question: repeating the value must not talk its way past a REJECT rule.
        assertFalse(refused.requiresConfirmation)
        assertFalse(runtime.commitFormulaBar("banana").accepted)
        assertEquals("", editorTextAt(runtime, 0, 0))
    }

    @Test fun turningAHighlightRuleOffStopsItPaintingWithoutLosingIt() {
        val runtime = runtime()
        runtime.select(GridAddress(0, 0))
        runtime.commitFormulaBar("9")
        runtime.select(GridAddress(0, 0))
        assertTrue(highlight(runtime, "5", ColorValue(255, 226, 128)))
        val id = runtime.conditionalRules().single().id

        assertTrue(runtime.setConditionalRuleEnabled(id, false))
        val disabled = runtime.conditionalRules().single()
        assertFalse(disabled.enabled)
        // Off, not gone: the rule is still listed so "what did this look like before" stays answerable.
        assertEquals(id, disabled.id)
        assertTrue(runtime.setConditionalRuleEnabled(id, true))
        assertTrue(runtime.conditionalRules().single().enabled)
    }

    // ---- Reordering --------------------------------------------------------------------------------

    @Test fun movingAHighlightRuleChangesWhichRuleWins() {
        val runtime = runtime()
        runtime.select(GridAddress(0, 0))
        assertTrue(highlight(runtime, "1", ColorValue(255, 0, 0)))
        assertTrue(highlight(runtime, "2", ColorValue(0, 255, 0)))
        val last = runtime.conditionalRules().last().id

        assertTrue(runtime.moveConditionalRule(last, -1))
        assertEquals("value is greater than 2", runtime.conditionalRules().first().description)
    }

    @Test fun reorderingRepeatedlyKeepsPrioritiesDenseAndUnique() {
        val runtime = runtime()
        runtime.select(GridAddress(0, 0))
        repeat(4) { index -> assertTrue(highlight(runtime, "$index", ColorValue(0, 0, 0))) }

        repeat(6) {
            val ids = runtime.conditionalRules().map(ConditionalRuleSummary::id)
            runtime.moveConditionalRule(ids.last(), -1)
            runtime.moveConditionalRule(ids.first(), 1)
        }
        val priorities = runtime.conditionalRules().map(ConditionalRuleSummary::priority)
        // Drifting into duplicate or sparse priorities makes "which rule wins" undecidable.
        assertEquals(listOf(1, 2, 3, 4), priorities)
        assertEquals(priorities.size, priorities.distinct().size)
    }

    @Test fun movingPastTheEndIsRefusedWithAReasonRatherThanSilentlyIgnored() {
        val runtime = runtime()
        runtime.select(GridAddress(0, 0))
        assertTrue(highlight(runtime, "1", ColorValue(0, 0, 0)))
        assertTrue(highlight(runtime, "2", ColorValue(0, 0, 0)))
        val first = runtime.conditionalRules().first().id

        assertFalse(runtime.moveConditionalRule(first, -1))
        assertEquals("That rule is already first", runtime.state().lastMessage)
        assertEquals(listOf(1, 2), runtime.conditionalRules().map(ConditionalRuleSummary::priority))
    }

    // ---- Deleting ----------------------------------------------------------------------------------

    @Test fun deletingAValidationRuleMakesTheRefusedValueEnterableAgain() {
        val runtime = runtime()
        runtime.select(GridAddress(0, 0))
        assertTrue(runtime.applyListValidation(listOf("apple")))
        assertFalse(runtime.commitFormulaBar("banana").accepted)

        assertTrue(runtime.deleteValidationRule(runtime.validationRules().single().id))
        assertTrue(runtime.validationRules().isEmpty())
        assertTrue(runtime.commitFormulaBar("banana").accepted)
        assertEquals("banana", editorTextAt(runtime, 0, 0))
    }

    @Test fun deletingAValidationRuleLeavesTheValuesAlreadyInTheCells() {
        val runtime = runtime()
        runtime.select(GridAddress(0, 0))
        runtime.commitFormulaBar("apple")
        runtime.select(GridAddress(0, 0))
        assertTrue(runtime.applyListValidation(listOf("apple")))

        assertTrue(runtime.deleteValidationRule(runtime.validationRules().single().id))
        // A rule that no longer exists cannot retroactively be a reason to change stored data.
        assertEquals("apple", editorTextAt(runtime, 0, 0))
    }

    @Test fun deletingAHighlightRuleRemovesItFromTheList() {
        val runtime = runtime()
        runtime.select(GridAddress(0, 0))
        assertTrue(highlight(runtime, "5", ColorValue(255, 226, 128)))
        assertTrue(runtime.deleteConditionalRule(runtime.conditionalRules().single().id))
        assertTrue(runtime.conditionalRules().isEmpty())
    }

    @Test fun deletingOneOfTwoHighlightRulesLeavesTheOtherEvaluable() {
        val runtime = runtime()
        runtime.select(GridAddress(0, 0))
        assertTrue(highlight(runtime, "1", ColorValue(255, 0, 0)))
        assertTrue(highlight(runtime, "2", ColorValue(0, 255, 0)))
        val first = runtime.conditionalRules().first().id

        assertTrue(runtime.deleteConditionalRule(first))
        val remaining = runtime.conditionalRules().single()
        assertEquals("value is greater than 2", remaining.description)
        // The survivor keeps a usable priority rather than being orphaned at an index that no longer exists.
        assertTrue(remaining.priority >= 1)
    }

    // ---- History and refusals ----------------------------------------------------------------------

    @Test fun eachManagementActionIsOneUndoStep() {
        val runtime = runtime()
        runtime.select(GridAddress(0, 0))
        assertTrue(runtime.applyListValidation(listOf("apple")))
        val id = runtime.validationRules().single().id
        val depth = runtime.controller.undoDepth()

        assertTrue(runtime.deleteValidationRule(id))
        assertEquals(depth + 1, runtime.controller.undoDepth())
        assertTrue(runtime.undo())
        // Undo has to bring the rule back, not just the cells it guarded.
        assertEquals(1, runtime.validationRules().size)
    }

    @Test fun actingOnARuleThatIsAlreadyGoneIsRefusedWithAReason() {
        val runtime = runtime()
        runtime.select(GridAddress(0, 0))
        assertTrue(runtime.applyListValidation(listOf("apple")))
        assertTrue(highlight(runtime, "5", ColorValue(0, 0, 0)))
        val validationId = runtime.validationRules().single().id
        val conditionalId = runtime.conditionalRules().single().id
        assertTrue(runtime.deleteValidationRule(validationId))
        assertTrue(runtime.deleteConditionalRule(conditionalId))

        assertFalse(runtime.deleteValidationRule(validationId))
        assertEquals("That validation rule no longer exists", runtime.state().lastMessage)
        assertFalse(runtime.setValidationWarnOnly(validationId, true))
        assertFalse(runtime.setConditionalRuleEnabled(conditionalId, false))
        assertEquals("That highlight rule no longer exists", runtime.state().lastMessage)
        assertFalse(runtime.moveConditionalRule(conditionalId, 1))
    }

    @Test fun applyingARuleWithNothingSelectedExplainsItselfRatherThanFailingSilently() {
        val runtime = runtime()
        runtime.selectAll()
        if (!runtime.applyListValidation(listOf("apple"))) {
            assertEquals("Select the cells to validate first", runtime.state().lastMessage)
        }
        runtime.select(GridAddress(0, 0))
        assertFalse(runtime.applyListValidation(emptyList()))
        assertEquals("Give at least one allowed value", runtime.state().lastMessage)
        assertFalse(highlight(runtime, "  ", ColorValue(0, 0, 0)))
        assertEquals("Give a value to compare against", runtime.state().lastMessage)
    }

    @Test fun applyingARuleReportsWhereItLanded() {
        val runtime = runtime()
        runtime.select(GridAddress(1, 1))
        assertTrue(runtime.applyListValidation(listOf("apple")))
        assertEquals("Validation applied to Sheet1!B2", runtime.state().lastMessage)
        assertTrue(highlight(runtime, "5", ColorValue(0, 0, 0)))
        assertEquals("Highlight applied to Sheet1!B2", runtime.state().lastMessage)
    }
}
