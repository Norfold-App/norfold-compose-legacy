package com.norfold.app.editor.calculation

import com.norfold.app.domain.BlockDocument
import com.norfold.app.editor.history.EditTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Spreadsheet editing state that must outlive a recomposition.
 *
 * Three pieces of state lived in Compose `remember` blocks keyed on values the surface does not control: the
 * in-cell edit draft, the formula-bar text, and the scroll position. Every one of them is discarded by a
 * recomposition the surface cannot predict — a reflow, a theme change, a configuration change, a
 * process-death restore — and each re-seeded from the committed value afterwards. The user-visible result
 * was silent loss: a half-typed formula reverting to the old value, or a large grid jumping back to A1.
 *
 * These tests exercise the runtime directly, which is the point: if the state is reachable and assertable
 * without a Compose harness, it is by definition not being held by the composition.
 */
class SpreadsheetUiStateOwnershipTest {

    private fun runtime(): SpreadsheetHostRuntime = SpreadsheetHostRuntime(
        EditTarget("sheet:ui-state"),
        BlockDocument(listOf(SpreadsheetWorkbookFactory.createBlock())),
    )

    // ---- In-cell draft ----------------------------------------------------------------------------

    @Test fun theInCellDraftIsRuntimeStateAndSurvivesAnyRecomposition() {
        val runtime = runtime()
        runtime.select(GridAddress(0, 0))
        runtime.commitFormulaBar("5")
        runtime.select(GridAddress(1, 0))
        runtime.beginEdit()
        "=A1*3".forEachIndexed { index, _ -> runtime.updateDraft("=A1*3".take(index + 1)) }

        // Re-reading the state is what a recomposition does. The draft has to still be there.
        val editing = runtime.state().selection as TableSelectionState.Editing
        assertEquals("=A1*3", editing.draft)
        assertEquals(GridAddress(1, 0), editing.cell)
    }

    @Test fun typingIntoACellWritesNothingUntilItIsCommitted() {
        val runtime = runtime()
        runtime.select(GridAddress(0, 0))
        runtime.commitFormulaBar("5")
        val depth = runtime.controller.undoDepth()
        runtime.select(GridAddress(1, 0))
        runtime.beginEdit()
        runtime.updateDraft("=A1*")
        runtime.updateDraft("=A1*3")

        assertEquals("no keystroke may consume history", depth, runtime.controller.undoDepth())
        assertEquals("", runtime.state().activeSheet.cellAt(1, 0)?.input?.editorText().orEmpty())
        assertTrue(runtime.commitActiveEdit())
        assertEquals(depth + 1, runtime.controller.undoDepth())
        assertEquals("15", runtime.engine().displayAt(runtime.state().activeSheet.id, 1, 0))
    }

    @Test fun beginningAnEditSeedsTheDraftFromTheStoredSourceNotTheRenderedValue() {
        val runtime = runtime()
        runtime.select(GridAddress(0, 0)); runtime.commitFormulaBar("4")
        runtime.select(GridAddress(1, 0)); runtime.commitFormulaBar("=A1*2")
        assertEquals("8", runtime.engine().displayAt(runtime.state().activeSheet.id, 1, 0))

        runtime.select(GridAddress(1, 0))
        runtime.beginEdit()
        // Seeding from the rendered value would replace the formula with its own result on the next commit.
        assertEquals("=A1*2", (runtime.state().selection as TableSelectionState.Editing).draft)
    }

    // ---- Formula bar ------------------------------------------------------------------------------

    @Test fun theFormulaBarDraftIsRuntimeStateAndIsMarkedDirty() {
        val runtime = runtime()
        runtime.select(GridAddress(0, 0))
        runtime.commitFormulaBar("10")
        assertFalse(runtime.state().formulaBarDirty)

        runtime.updateFormulaBarDraft("=1+")
        assertEquals("=1+", runtime.state().formulaBarText)
        assertTrue(runtime.state().formulaBarDirty)
        // Nothing was written.
        assertEquals("10", runtime.state().activeSheet.cellAt(0, 0)?.input?.editorText())
    }

    @Test fun aRefusedCommitKeepsTheTypedTextRatherThanRevertingIt() {
        val runtime = runtime()
        runtime.select(GridAddress(0, 0))
        assertTrue(runtime.applyListValidation(listOf("apple", "pear")))
        runtime.updateFormulaBarDraft("banana")
        val result = runtime.commitFormulaBar("banana")
        assertFalse(result.accepted)
        // The bar still shows what the user typed, so the correction is one edit away rather than a retype.
        assertEquals("banana", runtime.state().formulaBarText)
    }

    @Test fun anAcceptedCommitFinishesTheDraftAndTheBarFollowsTheCell() {
        val runtime = runtime()
        runtime.select(GridAddress(0, 0))
        runtime.updateFormulaBarDraft("=2+3")
        assertTrue(runtime.commitFormulaBar("=2+3").accepted)
        assertFalse(runtime.state().formulaBarDirty)
        assertEquals("=2+3", runtime.state().formulaBarText)

        runtime.select(GridAddress(4, 4))
        assertEquals("moving to an empty cell must clear the bar", "", runtime.state().formulaBarText)
    }

    @Test fun addressingAnotherCellAbandonsTheBarDraftRatherThanCarryingIt() {
        val runtime = runtime()
        runtime.select(GridAddress(0, 0))
        runtime.commitFormulaBar("1")
        runtime.select(GridAddress(1, 0))
        runtime.commitFormulaBar("2")

        runtime.select(GridAddress(0, 0))
        runtime.updateFormulaBarDraft("999")
        runtime.select(GridAddress(1, 0))
        // Carrying it across would write one cell's text into a different cell on the next commit.
        assertEquals("2", runtime.state().formulaBarText)
        assertFalse(runtime.state().formulaBarDirty)
    }

    @Test fun theBarAndTheCellShowOneDraftRatherThanTwo() {
        val runtime = runtime()
        runtime.select(GridAddress(2, 2))
        runtime.beginEdit()
        runtime.updateDraft("=1+1")
        // Two independent drafts for one cell is how the bar and the cell come to disagree.
        assertEquals("=1+1", runtime.state().formulaBarText)
        assertTrue(runtime.state().formulaBarDirty)
    }

    @Test fun cancellingTheBarDraftRestoresTheCommittedValue() {
        val runtime = runtime()
        runtime.select(GridAddress(0, 0))
        runtime.commitFormulaBar("42")
        runtime.updateFormulaBarDraft("nonsense")
        assertTrue(runtime.cancelFormulaBarDraft())
        assertEquals("42", runtime.state().formulaBarText)
        assertFalse(runtime.cancelFormulaBarDraft())
    }

    // ---- Viewport ---------------------------------------------------------------------------------

    @Test fun theScrollPositionIsRuntimeStateAndIsKeptPerSheet() {
        val runtime = runtime()
        val first = runtime.state().activeSheet.id
        runtime.setViewportOffset(320f, 180f)
        assertEquals(320f to 180f, runtime.viewportOffset())

        assertTrue(runtime.addSheet())
        val second = runtime.state().activeSheet.id
        assertNotEquals(first, second)
        // A new sheet starts at its own origin rather than inheriting the previous sheet's scroll.
        assertEquals(0f to 0f, runtime.viewportOffset())

        runtime.setViewportOffset(40f, 0f)
        runtime.activateSheet(first)
        // Coming back returns to where the user was.
        assertEquals(320f to 180f, runtime.viewportOffset())
        assertEquals(320f to 180f, runtime.state().viewportOffset)
    }

    @Test fun aNegativeScrollOffsetIsClampedRatherThanStored() {
        val runtime = runtime()
        runtime.setViewportOffset(-50f, -10f)
        assertEquals(0f to 0f, runtime.viewportOffset())
    }

    @Test fun scrollingDoesNotTouchTheDocumentOrHistory() {
        val runtime = runtime()
        runtime.select(GridAddress(0, 0))
        runtime.commitFormulaBar("1")
        val depth = runtime.controller.undoDepth()
        val document = runtime.controller.document()

        runtime.setViewportOffset(500f, 500f)
        assertEquals(depth, runtime.controller.undoDepth())
        assertEquals("a scroll gesture is not a document edit", document, runtime.controller.document())
    }
}
