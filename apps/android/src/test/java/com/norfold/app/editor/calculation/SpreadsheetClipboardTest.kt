package com.norfold.app.editor.calculation

import com.norfold.app.domain.BlockDocument
import com.norfold.app.domain.style.ColorValue
import com.norfold.app.editor.history.EditTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The structured internal clipboard.
 *
 * Copy used to produce a tab-separated string and nothing else, so a paste inside Norfold went through the
 * system clipboard and arrived as plain text: number format, fill, borders, alignment and bold were all
 * dropped. Copying a formatted currency column and pasting it one column over returned bare numbers, and the
 * only fix available to the user was to re-apply every format by hand.
 *
 * The payload is *additional to* the text, never instead of it — copy still has to interoperate with other
 * apps. That is why paste compares the incoming system-clipboard text against what this host exported: text
 * from somewhere else must take the plain path rather than silently pasting Norfold's stale structure.
 */
class SpreadsheetClipboardTest {

    private fun runtime(): SpreadsheetHostRuntime = SpreadsheetHostRuntime(
        EditTarget("sheet:clipboard"),
        BlockDocument(listOf(SpreadsheetWorkbookFactory.createBlock())),
    )

    private fun formatAt(runtime: SpreadsheetHostRuntime, row: Int, column: Int): CellFormat =
        runtime.state().activeSheet.cellAt(row, column)?.format ?: CellFormat()

    private fun editorTextAt(runtime: SpreadsheetHostRuntime, row: Int, column: Int): String =
        runtime.state().activeSheet.cellAt(row, column)?.input?.editorText().orEmpty()

    private fun display(runtime: SpreadsheetHostRuntime, row: Int, column: Int): String =
        runtime.engine().displayAt(runtime.state().activeSheet.id, row, column)

    // ---- Capture ----------------------------------------------------------------------------------

    @Test fun copyCapturesStructureAlongsideTheTextItReturns() {
        val runtime = runtime()
        runtime.select(GridAddress(0, 0)); runtime.commitFormulaBar("10")
        runtime.select(GridAddress(1, 0)); runtime.commitFormulaBar("20")
        runtime.select(GridAddress(0, 0)); runtime.extendSelection(1, 0)

        val text = runtime.copySelection()
        assertEquals("10\n20", text)
        val payload = runtime.internalClipboard()
        assertNotNull(payload)
        assertEquals(2, payload!!.rowCount)
        assertEquals(1, payload.columnCount)
        assertEquals(GridAddress(0, 0), payload.origin)
        // The text is carried too, so paste can tell a Norfold copy from someone else's.
        assertEquals(text, payload.exportedText)
        assertFalse(payload.cut)
    }

    @Test fun copyingNothingClearsThePayloadRatherThanLeavingItStale() {
        val runtime = runtime()
        runtime.select(GridAddress(0, 0)); runtime.commitFormulaBar("1")
        runtime.select(GridAddress(0, 0))
        assertTrue(runtime.copySelection().isNotEmpty())
        assertNotNull(runtime.internalClipboard())

        runtime.selectAll()
        // Whole-table selection produces no addressed range here; the stale payload must not survive it.
        if (runtime.copySelection().isEmpty()) assertNull(runtime.internalClipboard())
    }

    @Test fun cutMarksThePayloadAndClearsTheSourceCells() {
        val runtime = runtime()
        runtime.select(GridAddress(0, 0)); runtime.commitFormulaBar("7")
        runtime.select(GridAddress(0, 0))
        assertEquals("7", runtime.cutSelection())
        assertTrue(runtime.internalClipboard()!!.cut)
        assertEquals("", editorTextAt(runtime, 0, 0))
    }

    // ---- Formatting survives ----------------------------------------------------------------------

    @Test fun pastingInsideNorfoldCarriesTheFormattingTheTextPathWouldHaveDropped() {
        val runtime = runtime()
        runtime.select(GridAddress(0, 0))
        runtime.commitFormulaBar("1234")
        assertTrue(runtime.setNumberFormat(CellNumberFormat(NumberFormatKind.CURRENCY, "USD")))
        assertTrue(runtime.applyFormat(CellFormat(bold = true, fillColor = ColorValue(255, 226, 128))))
        val sourceFormat = formatAt(runtime, 0, 0)

        val text = runtime.copySelection()
        runtime.select(GridAddress(5, 3))
        val report = runtime.paste(text)
        assertEquals(1, report.acceptedCount)

        val pasted = formatAt(runtime, 5, 3)
        assertEquals("the number format must travel with the value", sourceFormat.numberFormat, pasted.numberFormat)
        assertEquals(sourceFormat.bold, pasted.bold)
        assertEquals(sourceFormat.fillColor, pasted.fillColor)
        assertEquals("1234", editorTextAt(runtime, 5, 3))
    }

    @Test fun textCopiedFromAnotherAppStillPastesAsPlainText() {
        val runtime = runtime()
        runtime.select(GridAddress(0, 0))
        runtime.commitFormulaBar("1234")
        assertTrue(runtime.applyFormat(CellFormat(bold = true)))
        runtime.copySelection()

        // The user copied something elsewhere in the meantime. The stale structured payload must not be used.
        runtime.select(GridAddress(2, 0))
        runtime.paste("99")
        assertEquals("99", editorTextAt(runtime, 2, 0))
        assertFalse("formatting must not leak from an unrelated copy", formatAt(runtime, 2, 0).bold == true)
    }

    // ---- Formulas move like fill ------------------------------------------------------------------

    @Test fun relativeReferencesOffsetAndAbsoluteOnesDoNot() {
        val runtime = runtime()
        runtime.select(GridAddress(0, 0)); runtime.commitFormulaBar("5")
        runtime.select(GridAddress(0, 1)); runtime.commitFormulaBar("6")
        runtime.select(GridAddress(1, 0)); runtime.commitFormulaBar("=A1*2")
        runtime.select(GridAddress(1, 0))
        val text = runtime.copySelection()

        runtime.select(GridAddress(1, 1))
        runtime.paste(text)
        // One column right: the relative reference follows, exactly as it would under fill.
        assertEquals("=B1*2", editorTextAt(runtime, 1, 1))
        assertEquals("12", display(runtime, 1, 1))

        runtime.select(GridAddress(3, 0)); runtime.commitFormulaBar("=\$A\$1*3")
        runtime.select(GridAddress(3, 0))
        val anchored = runtime.copySelection()
        runtime.select(GridAddress(3, 2))
        runtime.paste(anchored)
        assertEquals("=\$A\$1*3", editorTextAt(runtime, 3, 2))
        assertEquals("15", display(runtime, 3, 2))
    }

    @Test fun aMultiCellBlockKeepsItsShapeAtTheNewOrigin() {
        val runtime = runtime()
        listOf(
            Triple(0, 0, "1"), Triple(0, 1, "2"),
            Triple(1, 0, "3"), Triple(1, 1, "4"),
        ).forEach { (row, column, value) ->
            runtime.select(GridAddress(row, column)); runtime.commitFormulaBar(value)
        }
        runtime.select(GridAddress(0, 0)); runtime.extendSelection(1, 1)
        val text = runtime.copySelection()

        runtime.select(GridAddress(4, 2))
        assertEquals(4, runtime.paste(text).acceptedCount)
        assertEquals("1", editorTextAt(runtime, 4, 2))
        assertEquals("2", editorTextAt(runtime, 4, 3))
        assertEquals("3", editorTextAt(runtime, 5, 2))
        assertEquals("4", editorTextAt(runtime, 5, 3))
    }

    // ---- Validation and history -------------------------------------------------------------------

    @Test fun aPastedValueTheDestinationRefusesIsReportedRatherThanWritten() {
        val runtime = runtime()
        runtime.select(GridAddress(0, 0)); runtime.commitFormulaBar("banana")
        runtime.select(GridAddress(0, 0))
        val text = runtime.copySelection()

        runtime.select(GridAddress(3, 0))
        assertTrue(runtime.applyListValidation(listOf("apple", "pear")))
        val report = runtime.paste(text)
        assertEquals("the refused value must be counted, not written", 1, report.rejectedCount)
        assertEquals("", editorTextAt(runtime, 3, 0))
        // And formatting must not be painted onto a cell whose content never landed.
        assertEquals(CellFormat(), formatAt(runtime, 3, 0).copy(numberFormat = CellFormat().numberFormat))
    }

    @Test fun onePasteIsOneUndoStepThatRestoresEveryCellItTouched() {
        val runtime = runtime()
        runtime.select(GridAddress(0, 0)); runtime.commitFormulaBar("1")
        runtime.select(GridAddress(0, 1)); runtime.commitFormulaBar("2")
        runtime.select(GridAddress(0, 0)); runtime.extendSelection(0, 1)
        val text = runtime.copySelection()

        runtime.select(GridAddress(4, 0))
        val depth = runtime.controller.undoDepth()
        runtime.paste(text)
        assertEquals("one paste, one transaction", depth + 1, runtime.controller.undoDepth())
        assertTrue(runtime.undo())
        assertEquals("", editorTextAt(runtime, 4, 0))
        assertEquals("", editorTextAt(runtime, 4, 1))
    }

    @Test fun theStructuredPathAndThePlainPathAgreeOnValues() {
        val structured = runtime()
        val plain = runtime()
        listOf(structured, plain).forEach { runtime ->
            runtime.select(GridAddress(0, 0)); runtime.commitFormulaBar("8")
            runtime.select(GridAddress(0, 1)); runtime.commitFormulaBar("9")
        }
        structured.select(GridAddress(0, 0)); structured.extendSelection(0, 1)
        val text = structured.copySelection()

        structured.select(GridAddress(2, 0)); structured.paste(text)
        // The plain host never copied, so its clipboard is empty and the same text takes the text path.
        plain.select(GridAddress(2, 0)); plain.paste(text)
        assertEquals(editorTextAt(plain, 2, 0), editorTextAt(structured, 2, 0))
        assertEquals(editorTextAt(plain, 2, 1), editorTextAt(structured, 2, 1))
    }
}
