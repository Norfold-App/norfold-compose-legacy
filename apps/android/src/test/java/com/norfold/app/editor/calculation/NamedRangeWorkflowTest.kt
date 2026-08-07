package com.norfold.app.editor.calculation

import com.norfold.app.domain.BlockDocument
import com.norfold.app.editor.history.EditTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The defined-name workflow end to end: define, list, find, rename, delete.
 *
 * Defining was the only route that existed. A name could be created and then never seen again — not listed,
 * not renamed, not removed, not navigated to — while every formula that used it kept depending on it. The
 * tests below pin the parts that make it a workflow rather than a one-way door, and in particular the two
 * places where getting it wrong damages a document: a rename must carry the formulas with it, and a delete
 * must leave an honest `#NAME?` rather than silently resolving to something else.
 */
class NamedRangeWorkflowTest {

    private fun runtime(): SpreadsheetHostRuntime = SpreadsheetHostRuntime(
        EditTarget("sheet:names"),
        BlockDocument(listOf(SpreadsheetWorkbookFactory.createBlock())),
    )

    private fun seed(runtime: SpreadsheetHostRuntime) {
        runtime.select(GridAddress(0, 0)); runtime.commitFormulaBar("2")
        runtime.select(GridAddress(1, 0)); runtime.commitFormulaBar("3")
    }

    private fun display(runtime: SpreadsheetHostRuntime, row: Int, column: Int): String =
        runtime.engine().displayAt(runtime.state().activeSheet.id, row, column)

    // ---- Define -----------------------------------------------------------------------------------

    @Test fun definingANameOverTheSelectionMakesItUsableInAFormula() {
        val runtime = runtime()
        seed(runtime)
        runtime.select(GridAddress(0, 0)); runtime.extendSelection(1, 0)
        assertTrue(runtime.defineNamedRange("Totals"))
        runtime.select(GridAddress(3, 0))
        assertTrue(runtime.commitFormulaBar("=SUM(Totals)").accepted)
        assertEquals("5", display(runtime, 3, 0))
    }

    @Test fun everyRefusalExplainsItselfRatherThanReturningASilentFalse() {
        val runtime = runtime()
        seed(runtime)
        // No selected range at all.
        runtime.selectAll()
        assertFalse(runtime.defineNamedRange("Whole"))
        assertNotNull(runtime.state().lastMessage)

        runtime.select(GridAddress(0, 0))
        assertFalse(runtime.defineNamedRange("has space"))
        assertTrue(runtime.state().lastMessage!!.contains("name must start"))

        assertTrue(runtime.defineNamedRange("Alpha"))
        assertFalse(runtime.defineNamedRange("alpha"))
        // Names are case-insensitive, so the second definition is a collision rather than a new name.
        assertTrue(runtime.state().lastMessage!!.contains("already exists"))
        assertEquals(1, runtime.namedRanges().size)
    }

    @Test fun aDismissedNoticeStaysDismissed() {
        val runtime = runtime()
        runtime.select(GridAddress(0, 0))
        runtime.defineNamedRange("bad name")
        assertNotNull(runtime.state().lastMessage)
        runtime.dismissMessage()
        assertNull(runtime.state().lastMessage)
    }

    // ---- List and find ----------------------------------------------------------------------------

    @Test fun theListReportsWhereEachNamePoints() {
        val runtime = runtime()
        seed(runtime)
        runtime.select(GridAddress(0, 0)); runtime.extendSelection(1, 0)
        assertTrue(runtime.defineNamedRange("Totals"))
        runtime.select(GridAddress(4, 2))
        assertTrue(runtime.defineNamedRange("Single"))

        val summaries = runtime.namedRangeSummaries()
        assertEquals(listOf("Single", "Totals"), summaries.map { it.name })
        val sheetName = runtime.state().activeSheet.name
        assertEquals("$sheetName!A1:A2", summaries.first { it.name == "Totals" }.location)
        // A single cell reads as one address, not as `C5:C5`.
        assertEquals("$sheetName!C5", summaries.first { it.name == "Single" }.location)
        assertTrue(summaries.all { it.resolvable })
    }

    @Test fun goToSelectsTheCellsTheNamePointsAt() {
        val runtime = runtime()
        seed(runtime)
        runtime.select(GridAddress(0, 0)); runtime.extendSelection(1, 0)
        assertTrue(runtime.defineNamedRange("Totals"))
        runtime.select(GridAddress(7, 4))

        val id = runtime.namedRanges().single().id
        assertTrue(runtime.goToNamedRange(id))
        val selection = runtime.state().selection as TableSelectionState.RangeSelected
        assertEquals(GridAddress(0, 0), selection.anchor)
        assertEquals(GridAddress(1, 0), selection.focus)
    }

    @Test fun goToAcrossSheetsActivatesTheSheetItLivesOn() {
        val runtime = runtime()
        runtime.select(GridAddress(2, 1))
        assertTrue(runtime.defineNamedRange("OnFirst"))
        val first = runtime.state().activeSheet.id
        assertTrue(runtime.addSheet())
        assertTrue("the new sheet is the active one", runtime.state().activeSheet.id != first)

        assertTrue(runtime.goToNamedRange(runtime.namedRanges().single().id))
        assertEquals("navigating a name must move the view to its sheet", first, runtime.state().activeSheet.id)
        assertEquals(GridAddress(2, 1), (runtime.state().selection as TableSelectionState.CellSelected).cell)
    }

    // ---- Rename -----------------------------------------------------------------------------------

    @Test fun renamingCarriesEveryFormulaThatUsedTheOldName() {
        val runtime = runtime()
        seed(runtime)
        runtime.select(GridAddress(0, 0)); runtime.extendSelection(1, 0)
        assertTrue(runtime.defineNamedRange("Totals"))
        runtime.select(GridAddress(3, 0))
        assertTrue(runtime.commitFormulaBar("=SUM(Totals)").accepted)

        assertTrue(runtime.renameNamedRange(runtime.namedRanges().single().id, "GrandTotal"))
        // The formula follows the rename; leaving it behind would turn a working sheet into #NAME? errors.
        assertEquals(
            "=SUM(GrandTotal)",
            runtime.state().activeSheet.cellAt(3, 0)?.input?.editorText(),
        )
        assertEquals("5", display(runtime, 3, 0))
    }

    @Test fun renameRefusesACollisionOrAnInvalidNameAndSaysWhy() {
        val runtime = runtime()
        runtime.select(GridAddress(0, 0)); assertTrue(runtime.defineNamedRange("Alpha"))
        runtime.select(GridAddress(1, 0)); assertTrue(runtime.defineNamedRange("Beta"))
        val alpha = runtime.namedRanges().first { it.name == "Alpha" }.id

        assertFalse(runtime.renameNamedRange(alpha, "Beta"))
        assertTrue(runtime.state().lastMessage!!.contains("already exists"))
        assertFalse(runtime.renameNamedRange(alpha, "not valid"))
        assertTrue(runtime.state().lastMessage!!.contains("name must start"))
        // Neither refusal changed anything.
        assertEquals(listOf("Alpha", "Beta"), runtime.namedRanges().map { it.name })
    }

    @Test fun renamingToTheSameNameIsAcceptedWithoutConsumingHistory() {
        val runtime = runtime()
        runtime.select(GridAddress(0, 0))
        assertTrue(runtime.defineNamedRange("Alpha"))
        val depth = runtime.controller.undoDepth()
        assertTrue(runtime.renameNamedRange(runtime.namedRanges().single().id, "Alpha"))
        assertEquals(depth, runtime.controller.undoDepth())
    }

    // ---- Delete -----------------------------------------------------------------------------------

    @Test fun deletingLeavesAnHonestNameErrorRatherThanASilentValue() {
        val runtime = runtime()
        seed(runtime)
        runtime.select(GridAddress(0, 0)); runtime.extendSelection(1, 0)
        assertTrue(runtime.defineNamedRange("Totals"))
        runtime.select(GridAddress(3, 0))
        assertTrue(runtime.commitFormulaBar("=SUM(Totals)").accepted)
        assertEquals("5", display(runtime, 3, 0))

        assertTrue(runtime.deleteNamedRange(runtime.namedRanges().single().id))
        assertTrue(runtime.namedRanges().isEmpty())
        // The dependent formula must report the loss. Quietly evaluating to 0 would be a wrong answer
        // presented as a right one.
        assertEquals("#NAME?", display(runtime, 3, 0))
    }

    @Test fun deleteAndRenameAreBothUndoable() {
        val runtime = runtime()
        seed(runtime)
        runtime.select(GridAddress(0, 0)); runtime.extendSelection(1, 0)
        assertTrue(runtime.defineNamedRange("Totals"))
        runtime.select(GridAddress(3, 0))
        assertTrue(runtime.commitFormulaBar("=SUM(Totals)").accepted)

        assertTrue(runtime.renameNamedRange(runtime.namedRanges().single().id, "Renamed"))
        assertTrue(runtime.undo())
        assertEquals("Totals", runtime.namedRanges().single().name)
        assertEquals("5", display(runtime, 3, 0))

        assertTrue(runtime.deleteNamedRange(runtime.namedRanges().single().id))
        assertTrue(runtime.undo())
        assertEquals("Totals", runtime.namedRanges().single().name)
        assertEquals("5", display(runtime, 3, 0))
    }

    @Test fun operatingOnAnAlreadyDeletedNameRefusesInsteadOfThrowing() {
        val runtime = runtime()
        runtime.select(GridAddress(0, 0))
        assertTrue(runtime.defineNamedRange("Alpha"))
        val id = runtime.namedRanges().single().id
        assertTrue(runtime.deleteNamedRange(id))

        assertFalse(runtime.deleteNamedRange(id))
        assertFalse(runtime.renameNamedRange(id, "Beta"))
        assertFalse(runtime.goToNamedRange(id))
        assertTrue(runtime.state().lastMessage!!.contains("no longer exists"))
    }

    @Test fun aNameWhoseSheetWasDeletedIsListedAsBrokenAndStillRemovable() {
        val runtime = runtime()
        assertTrue(runtime.addSheet())
        runtime.select(GridAddress(0, 0))
        assertTrue(runtime.defineNamedRange("OnSecond"))
        assertTrue(runtime.deleteActiveSheet())

        val summaries = runtime.namedRangeSummaries()
        if (summaries.isNotEmpty()) {
            // Deleting a sheet may drop its names outright; if one survives, it must be shown as broken and
            // must still be removable, never listed as if it worked.
            val broken = summaries.single()
            assertFalse(broken.resolvable)
            assertFalse(runtime.goToNamedRange(broken.id))
            assertTrue(runtime.deleteNamedRange(broken.id))
        }
        assertTrue(runtime.namedRanges().isEmpty())
    }
}
