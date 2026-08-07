package com.norfold.app.editor.calculation

import com.norfold.app.domain.BlockDocument
import com.norfold.app.editor.history.EditTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Interactions between the newly runtime-owned editing state and the operations that change the document
 * underneath it.
 *
 * Moving the cell draft, the formula bar and the viewport into the runtime fixes silent loss on
 * recomposition, but it creates a second question the Compose-held versions never had to answer: what happens
 * to state that outlives a recomposition when the *document* changes beneath it? Undo, redo and a remote merge
 * all replace the stored value the draft was seeded from. A draft that survives those is worse than one that
 * dies, because it shows the user text that no longer corresponds to anything and writes it on the next commit.
 */
class ConsequenceReviewTest {

    private fun runtime(): SpreadsheetHostRuntime = SpreadsheetHostRuntime(
        EditTarget("sheet:consequence"),
        BlockDocument(listOf(SpreadsheetWorkbookFactory.createBlock())),
    )

    private fun editorTextAt(runtime: SpreadsheetHostRuntime, row: Int, column: Int): String =
        runtime.state().activeSheet.cellAt(row, column)?.input?.editorText().orEmpty()

    @Test fun undoDiscardsAFormulaBarDraftRatherThanLeavingItOverTheRestoredValue() {
        val runtime = runtime()
        runtime.select(GridAddress(0, 0))
        runtime.commitFormulaBar("first")
        runtime.commitFormulaBar("second")
        runtime.updateFormulaBarDraft("typing over it")

        assertTrue(runtime.undo())
        // The bar has to follow the value undo restored. Keeping the draft would write "typing over it" into a
        // cell the user just reverted.
        assertEquals("first", runtime.state().formulaBarText)
        assertFalse(runtime.state().formulaBarDirty)
    }

    @Test fun redoAlsoDiscardsTheDraft() {
        val runtime = runtime()
        runtime.select(GridAddress(0, 0))
        runtime.commitFormulaBar("first")
        runtime.commitFormulaBar("second")
        assertTrue(runtime.undo())
        runtime.updateFormulaBarDraft("stale")
        assertTrue(runtime.redo())
        assertEquals("second", runtime.state().formulaBarText)
        assertFalse(runtime.state().formulaBarDirty)
    }

    @Test fun undoDiscardsAnInCellDraftRatherThanCommittingItLater() {
        val runtime = runtime()
        runtime.select(GridAddress(0, 0))
        runtime.commitFormulaBar("10")
        runtime.beginEdit()
        runtime.updateDraft("=999")

        assertTrue(runtime.undo())
        // Committing after an undo must not resurrect a draft that was typed against the pre-undo document.
        assertFalse(runtime.state().selection is TableSelectionState.Editing)
        assertEquals("", editorTextAt(runtime, 0, 0))
    }

    @Test fun aRemoteMergeDiscardsTheDraftItWouldOtherwiseContradict() {
        val runtime = runtime()
        runtime.select(GridAddress(0, 0))
        runtime.commitFormulaBar("local")
        runtime.updateFormulaBarDraft("half-typed")

        val other = runtime()
        other.select(GridAddress(0, 0))
        other.commitFormulaBar("remote")
        if (runtime.mergeRemote(other.controller.document())) {
            assertFalse("a merged-in value must not sit under a stale draft", runtime.state().formulaBarDirty)
        }
    }

    @Test fun undoLeavesTheViewportAloneBecauseScrollIsNotDocumentState() {
        val runtime = runtime()
        runtime.select(GridAddress(0, 0))
        runtime.commitFormulaBar("1")
        runtime.setViewportOffset(240f, 90f)
        assertTrue(runtime.undo())
        // Undo reverts what was written, not where the user is looking.
        assertEquals(240f to 90f, runtime.viewportOffset())
    }

    @Test fun undoingPastACopyLeavesTheClipboardUsable() {
        val runtime = runtime()
        runtime.select(GridAddress(0, 0))
        runtime.commitFormulaBar("7")
        runtime.select(GridAddress(0, 0))
        val text = runtime.copySelection()
        assertTrue(runtime.undo())

        // The payload captured values, not references into the document, so it survives the source changing.
        runtime.select(GridAddress(3, 3))
        assertEquals(1, runtime.paste(text).acceptedCount)
        assertEquals("7", editorTextAt(runtime, 3, 3))
    }

    @Test fun deletingASheetDoesNotLeaveTheViewportPointingAtIt() {
        val runtime = runtime()
        val first = runtime.state().activeSheet.id
        runtime.setViewportOffset(500f, 500f)
        assertTrue(runtime.addSheet())
        runtime.setViewportOffset(120f, 30f)
        runtime.activateSheet(first)
        // The first sheet carries the embedded table the document itself lives in, so deleting it is refused
        // with a reason rather than allowed to destroy the workbook or crash the persistence layer.
        assertFalse(runtime.deleteActiveSheet())
        assertEquals("This sheet contains the table you are editing and cannot be deleted", runtime.state().lastMessage)

        // The second sheet is a real sheet and deletes normally; its offset must not leak onto the survivor.
        runtime.activateSheet(runtime.state().workbook.sheets.first { it.id != first }.id)
        assertTrue(runtime.deleteActiveSheet())
        runtime.activateSheet(first)
        assertEquals(500f to 500f, runtime.viewportOffset())
    }

    @Test fun deletingASheetDropsItsRulesFromTheManagementLists() {
        val runtime = runtime()
        val first = runtime.state().activeSheet.id
        runtime.select(GridAddress(0, 0))
        assertTrue(runtime.applyListValidation(listOf("apple")))
        assertTrue(runtime.addSheet())
        assertTrue(runtime.deleteActiveSheet())
        // Back on the first sheet, its own rule is the one listed — not an empty list, and not the other sheet's.
        assertEquals(first, runtime.state().activeSheet.id)
        assertEquals(1, runtime.validationRules().size)
    }

    @Test fun aNoticeFromOneActionDoesNotOutliveTheNextOne() {
        val runtime = runtime()
        runtime.select(GridAddress(0, 0))
        assertFalse(runtime.applyListValidation(emptyList()))
        assertEquals("Give at least one allowed value", runtime.state().lastMessage)
        runtime.dismissMessage()
        assertNull(runtime.state().lastMessage)
        assertTrue(runtime.applyListValidation(listOf("apple")))
        assertEquals("Validation applied to Sheet1!A1", runtime.state().lastMessage)
    }
}
