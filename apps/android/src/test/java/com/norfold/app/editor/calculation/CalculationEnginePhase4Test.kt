package com.norfold.app.editor.calculation

import com.norfold.app.domain.style.ColorValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CalculationEnginePhase4Test {
    private fun input(workbook: CalculationWorkbook, sheetId: SheetId, row: Int, column: Int, raw: String): CalculationWorkbook {
        val engine = CalculationEngine(workbook)
        assertTrue(engine.commitInput(sheetId, row, column, CellInputParser.parse(raw)).accepted)
        return engine.workbook()
    }

    private fun evaluate(formula: String, setup: List<Triple<Int, Int, String>> = emptyList()): CalculatedValue {
        var workbook = CalculationWorkbook()
        val sheet = workbook.activeSheet()
        setup.forEach { (row, column, raw) -> workbook = input(workbook, sheet.id, row, column, raw) }
        workbook = input(workbook, sheet.id, 9, 9, formula)
        return CalculationEngine(workbook).valueAt(sheet.id, 9, 9)
    }

    @Test fun parser_preservesTypedLiteralsPrecedenceParenthesesUnaryComparisonAndConcatenation() {
        assertEquals("7", evaluate("=1+2*3").displayText())
        assertEquals("9", evaluate("=(1+2)*3").displayText())
        assertEquals("-2", evaluate("=-5+3").displayText())
        assertEquals("TRUE", evaluate("=3>=2").displayText())
        assertEquals("Norfold", evaluate("=\"Nor\"&\"fold\"").displayText())
        assertEquals("FALSE", evaluate("=NOT TRUE").displayText())
        assertEquals("0.5", evaluate("=50%").displayText())
    }

    @Test fun invalidSyntaxPreservesSourceAndProducesParseError_withoutLocaleParsing() {
        val formula = CellInput.Formula("=1+")
        var workbook = CalculationWorkbook()
        val sheet = workbook.activeSheet()
        val engine = CalculationEngine(workbook)
        assertTrue(engine.commitInput(sheet.id, 0, 0, formula).accepted)
        workbook = engine.workbook()
        assertEquals(formula, workbook.activeSheet().cellAt(0, 0)?.input)
        assertEquals(CalculationErrorKind.PARSE, (CalculationEngine(workbook).valueAt(sheet.id, 0, 0) as CalculatedValue.ErrorValue).error.kind)
        assertTrue(runCatching { CellInput.Number("1,25") }.isFailure)
        assertTrue(runCatching { CellInput.Number("NaN") }.isFailure)
        assertTrue(runCatching { CellInput.Number("Infinity") }.isFailure)
    }

    @Test fun references_ranges_absoluteMixedAndMissingCellsResolveDeterministically() {
        val setup = listOf(Triple(0, 0, "2"), Triple(1, 0, "3"), Triple(2, 0, "5"))
        assertEquals("10", evaluate("=SUM(A1:A3)", setup).displayText())
        assertEquals("2", evaluate("=\$A\$1", setup).displayText())
        assertEquals("3", evaluate("=A\$2", setup).displayText())
        assertEquals("0", evaluate("=Z99+0", setup).displayText())
    }

    @Test fun crossSheetAndQuotedSheetReferencesWork_andMissingSheetIsExplicit() {
        val first = CalculationSheet(name = "Main")
        val second = CalculationSheet(name = "Quarter One")
        var workbook = CalculationWorkbook(sheets = listOf(first, second), activeSheetId = first.id)
        workbook = input(workbook, second.id, 0, 0, "42")
        workbook = input(workbook, first.id, 0, 0, "='Quarter One'!A1")
        assertEquals("42", CalculationEngine(workbook).displayAt(first.id, 0, 0))
        workbook = input(workbook, first.id, 0, 1, "=Missing!A1")
        assertEquals(CalculationErrorKind.REFERENCE, (CalculationEngine(workbook).valueAt(first.id, 0, 1) as CalculatedValue.ErrorValue).error.kind)
    }

    @Test fun aggregateLogicalErrorRoundingAndTextCatalogueEvaluates() {
        val values = listOf(Triple(0, 0, "1"), Triple(1, 0, "2"), Triple(2, 0, "3"), Triple(0, 1, "yes"))
        assertEquals("2", evaluate("=AVERAGE(A1:A3)", values).displayText())
        assertEquals("1", evaluate("=MIN(A1:A3)", values).displayText())
        assertEquals("3", evaluate("=MAX(A1:A3)", values).displayText())
        assertEquals("3", evaluate("=COUNT(A1:B3)", values).displayText())
        assertEquals("4", evaluate("=COUNTA(A1:B3)", values).displayText())
        assertEquals("yes", evaluate("=IF(A1=1,B1,\"no\")", values).displayText())
        assertEquals("TRUE", evaluate("=AND(A1=1,A2=2)", values).displayText())
        assertEquals("TRUE", evaluate("=OR(A1=9,A2=2)", values).displayText())
        assertEquals("safe", evaluate("=IFERROR(1/0,\"safe\")", values).displayText())
        assertEquals("3.14", evaluate("=ROUND(3.14159,2)", values).displayText())
        assertEquals("3.15", evaluate("=ROUNDUP(3.141,2)", values).displayText())
        assertEquals("3.14", evaluate("=ROUNDDOWN(3.149,2)", values).displayText())
        assertEquals("3", evaluate("=ABS(-3)", values).displayText())
        assertEquals("Norfold", evaluate("=CONCAT(\"Nor\",\"fold\")", values).displayText())
        assertEquals("7", evaluate("=LEN(\"Norfold\")", values).displayText())
        assertEquals("Nor", evaluate("=LEFT(\"Norfold\",3)", values).displayText())
        assertEquals("old", evaluate("=RIGHT(\"Norfold\",3)", values).displayText())
        assertEquals("rfo", evaluate("=MID(\"Norfold\",3,3)", values).displayText())
        assertEquals("a b", evaluate("=TRIM(\" a   b \")", values).displayText())
        assertEquals("NORFOLD", evaluate("=UPPER(\"Norfold\")", values).displayText())
        assertEquals("norfold", evaluate("=LOWER(\"Norfold\")", values).displayText())
    }

    @Test fun criteriaLookupAndDateCatalogueEvaluates() {
        val setup = listOf(
            Triple(0, 0, "1"), Triple(1, 0, "2"), Triple(2, 0, "3"),
            Triple(0, 1, "10"), Triple(1, 1, "20"), Triple(2, 1, "30"),
        )
        assertEquals("50", evaluate("=SUMIF(A1:A3,\">1\",B1:B3)", setup).displayText())
        assertEquals("2", evaluate("=COUNTIF(A1:A3,\">1\")", setup).displayText())
        assertEquals("2", evaluate("=INDEX(A1:A3,2)", setup).displayText())
        assertEquals("20", evaluate("=INDEX(A1:B3,2,2)", setup).displayText())
        assertEquals("2", evaluate("=MATCH(2,A1:A3,0)", setup).displayText())
        assertEquals("20", evaluate("=XLOOKUP(2,A1:A3,B1:B3,\"missing\")", setup).displayText())
        assertEquals("2026-08-01", evaluate("=DATE(2026,8,1)", setup).displayText())
        assertEquals("2026", evaluate("=YEAR(DATE(2026,8,1))", setup).displayText())
        assertEquals("8", evaluate("=MONTH(DATE(2026,8,1))", setup).displayText())
        assertEquals("1", evaluate("=DAY(DATE(2026,8,1))", setup).displayText())
    }

    @Test fun functionErrorsPropagateAndUnknownNamesDoNotCrashUnrelatedCells() {
        assertEquals(CalculationErrorKind.DIVIDE_BY_ZERO, (evaluate("=1/0") as CalculatedValue.ErrorValue).error.kind)
        assertEquals(CalculationErrorKind.NAME, (evaluate("=DOESNOTEXIST(1)") as CalculatedValue.ErrorValue).error.kind)
        assertEquals(CalculationErrorKind.VALUE, (evaluate("=ABS(1,2)") as CalculatedValue.ErrorValue).error.kind)
        assertEquals(CalculationErrorKind.VALUE, (evaluate("=1+\"word\"") as CalculatedValue.ErrorValue).error.kind)
    }

    @Test fun dependencyClosureIsIncrementalAndCyclesRecoverAfterCorrection() {
        var workbook = CalculationWorkbook()
        val sheet = workbook.activeSheet()
        workbook = input(workbook, sheet.id, 0, 0, "1")
        workbook = input(workbook, sheet.id, 0, 1, "=A1+1")
        workbook = input(workbook, sheet.id, 0, 2, "=B1+1")
        workbook = input(workbook, sheet.id, 5, 5, "=40+2")
        val engine = CalculationEngine(workbook)
        val before = engine.instrumentation().evaluatedCellCount
        assertTrue(engine.commitInput(sheet.id, 0, 0, CellInput.Number("5")).accepted)
        val delta = engine.instrumentation().evaluatedCellCount - before
        assertTrue("only the source dependency closure should recalculate", delta in 3..4)
        assertEquals("7", engine.displayAt(sheet.id, 0, 2))
        assertEquals("42", engine.displayAt(sheet.id, 5, 5))

        var cycle = engine.workbook()
        cycle = input(cycle, sheet.id, 1, 0, "=B2")
        cycle = input(cycle, sheet.id, 1, 1, "=A2")
        assertEquals(CalculationErrorKind.CYCLE, (CalculationEngine(cycle).valueAt(sheet.id, 1, 0) as CalculatedValue.ErrorValue).error.kind)
        cycle = input(cycle, sheet.id, 1, 1, "9")
        assertEquals("9", CalculationEngine(cycle).displayAt(sheet.id, 1, 0))
    }

    @Test fun structuralInsertionRewritesSourceStructurallyAndPreservesAbsoluteMarkers() {
        var workbook = CalculationWorkbook()
        val sheet = workbook.activeSheet()
        workbook = input(workbook, sheet.id, 0, 0, "10")
        workbook = input(workbook, sheet.id, 1, 1, "=A1+\$A\$1+A\$1+\$A1+SUM(A1:A2)")
        val inserted = CalculationMutations.insertRow(workbook, sheet.id, 0).workbook
        val source = (inserted.activeSheet().cellAt(2, 1)?.input as CellInput.Formula).source
        assertEquals("=A2+\$A\$2+A\$2+\$A2+SUM(A2:A3)", source)
        val deleted = CalculationMutations.deleteRows(inserted, sheet.id, 0).workbook
        assertEquals("=A1+\$A\$1+A\$1+\$A1+SUM(A1:A2)", (deleted.activeSheet().cellAt(1, 1)?.input as CellInput.Formula).source)
    }

    @Test fun otherSheetUnqualifiedReferencesDoNotMoveWhenDifferentSheetIsEdited() {
        val first = CalculationSheet(name = "First")
        val second = CalculationSheet(name = "Second")
        var workbook = CalculationWorkbook(sheets = listOf(first, second), activeSheetId = first.id)
        workbook = input(workbook, second.id, 0, 1, "=A1")
        val inserted = CalculationMutations.insertRow(workbook, first.id, 0).workbook
        assertEquals("=A1", (inserted.sheet(second.id)!!.cellAt(0, 1)?.input as CellInput.Formula).source)
    }

    @Test fun namedAndStructuredReferencesSurviveRenames() {
        val sheet = CalculationSheet(name = "Data")
        val named = NamedRange(name = "Revenue", target = StableCellRange(sheet.coordinateAt(0, 0)!!, sheet.coordinateAt(1, 0)!!))
        val table = EmbeddedTableDefinition(
            name = "Sales",
            range = StableCellRange(sheet.coordinateAt(0, 0)!!, sheet.coordinateAt(2, 1)!!),
            columns = listOf(EmbeddedTableColumn(sheet.columns[0].id, "Amount"), EmbeddedTableColumn(sheet.columns[1].id, "Tax")),
        )
        var workbook = CalculationWorkbook(sheets = listOf(sheet), activeSheetId = sheet.id, namedRanges = listOf(named), embeddedTables = listOf(table))
        workbook = input(workbook, sheet.id, 0, 0, "4")
        workbook = input(workbook, sheet.id, 1, 0, "6")
        workbook = input(workbook, sheet.id, 5, 0, "=SUM(Revenue)+SUM(Sales[Amount])")
        assertEquals("16", CalculationEngine(workbook).displayAt(sheet.id, 5, 0))
        workbook = CalculationMutations.renameNamedRange(workbook, named.id, "Income")
        workbook = CalculationMutations.renameEmbeddedTable(workbook, table.id, "Orders")
        workbook = CalculationMutations.renameEmbeddedColumn(workbook, table.id, sheet.columns[0].id, "Total")
        val source = (workbook.activeSheet().cellAt(5, 0)?.input as CellInput.Formula).source
        assertEquals("=SUM(Income)+SUM(Orders[Total])", source)
        assertEquals("16", CalculationEngine(workbook).displayAt(sheet.id, 5, 0))
    }

    @Test fun validationRejectAndWarnAreDistinct_andConditionalFormattingIsDerived() {
        val sheet = CalculationSheet()
        val target = StableCellRange(sheet.coordinateAt(0, 0)!!, sheet.coordinateAt(1, 0)!!)
        val direct = CellFormat(fillColor = ColorValue(1, 2, 3))
        val configured = sheet.copy(
            validations = listOf(
                ValidationRule(target = target, condition = ValidationCondition.AllowedValues(listOf("Open", "Closed")), policy = ValidationPolicy.REJECT),
                ValidationRule(target = target, condition = ValidationCondition.TextLength(1, 3), policy = ValidationPolicy.WARN, errorMessage = "Short labels work best"),
            ),
            conditionalFormats = listOf(
                ConditionalFormatRule(
                    target = target,
                    condition = ConditionalCondition.TextContains("Open"),
                    priority = 1,
                    formatDelta = CellFormat(bold = true),
                    accessibleDescription = "Open status is emphasized",
                ),
            ),
            cells = listOf(CalculationCell(rowId = sheet.rows[0].id, columnId = sheet.columns[0].id, format = direct)),
        )
        val engine = CalculationEngine(CalculationWorkbook(sheets = listOf(configured), activeSheetId = sheet.id))
        assertFalse(engine.commitInput(sheet.id, 0, 0, CellInput.Text("Invalid")).accepted)
        val warning = engine.commitInput(sheet.id, 0, 0, CellInput.Text("Open"))
        assertFalse(warning.accepted)
        assertTrue(warning.requiresConfirmation)
        val accepted = engine.commitInput(sheet.id, 0, 0, CellInput.Text("Open"), confirmWarning = true)
        assertTrue(accepted.accepted)
        assertEquals("Short labels work best", accepted.warning)
        val derived = engine.conditionalFormat(sheet.id, 0, 0)
        assertEquals(ColorValue(1, 2, 3), derived.format.fillColor)
        assertEquals(true, derived.format.bold)
        // The announcement carries the author's label *and* the machine-stated reason, so the meaning is
        // never conveyed by the fill colour alone even when the author names the rule badly.
        assertEquals(listOf("Open status is emphasized; text contains \"Open\""), derived.descriptions)
        assertEquals(direct.fillColor, engine.workbook().activeSheet().cellAt(0, 0)?.format?.fillColor)
    }

    @Test fun resourceBudgetsFailSafelyAndFormulaLanguageHasNoIoOrReflectionTokens() {
        val parser = FormulaParser(maxTokens = 8, maxDepth = 4)
        assertTrue(parser.parse("=1+1+1+1+1+1") is FormulaParseResult.Failure)
        assertTrue(parser.parse("=((((((1))))))") is FormulaParseResult.Failure)
        val network = evaluate("=HTTP(\"https://example.com\")") as CalculatedValue.ErrorValue
        val filesystem = evaluate("=FILE(\"/tmp/x\")") as CalculatedValue.ErrorValue
        assertEquals(CalculationErrorKind.NAME, network.error.kind)
        assertEquals(CalculationErrorKind.NAME, filesystem.error.kind)
    }

    /**
     * COUNT and COUNTA propagate errors instead of quietly counting past them.
     *
     * Neither function counts an error value, but "zero matching cells" and "the range could not be read" are
     * different answers and only one of them tells the user their formula did not run. Filtering errors out
     * turned a range refused for exceeding the resource limit into a confident `0`.
     */
    @Test fun countFunctionsSurfaceRangeFailuresRatherThanReportingZero() {
        val huge = CalculationSheet(rowCount = 100_000, columnCount = 2, rows = List(100_000) { CalculationRow() }, columns = List(2) { CalculationColumn() })
        var workbook = CalculationWorkbook(sheets = listOf(huge), activeSheetId = huge.id)
        val seed = CalculationEngine(workbook)
        assertTrue(seed.commitInput(huge.id, 0, 1, CellInput.Formula("=COUNT(A1:A100000)"), confirmWarning = true).accepted)
        assertTrue(seed.commitInput(huge.id, 1, 1, CellInput.Formula("=COUNTA(A1:A100000)"), confirmWarning = true).accepted)
        workbook = seed.workbook()

        // At the default limit the range is readable, and an empty column really does count zero.
        val allowed = CalculationEngine(workbook)
        assertEquals("a readable empty range counts zero", "0", allowed.displayAt(huge.id, 0, 1))

        // One cell past the limit, both must report the refusal rather than the same "0".
        val refusing = CalculationEngine(workbook, CalculationLimits(maximumRangeCells = 99_999))
        listOf(0 to "COUNT", 1 to "COUNTA").forEach { (row, function) ->
            val value = refusing.valueAt(huge.id, row, 1)
            assertTrue("$function must not report a count for a range it could not read", value is CalculatedValue.ErrorValue)
            assertEquals(
                "$function must report the resource limit",
                CalculationErrorKind.RESOURCE_LIMIT,
                (value as CalculatedValue.ErrorValue).error.kind,
            )
            assertNotEquals("$function must not display a plausible zero", "0", refusing.displayAt(huge.id, row, 1))
        }

        // Ordinary counting is unaffected.
        assertEquals("2", evaluate("=COUNT(A1:A2)", listOf(Triple(0, 0, "4"), Triple(1, 0, "5"))).displayText())
        assertEquals("3", evaluate("=COUNTA(A1:A3)", listOf(Triple(0, 0, "4"), Triple(1, 0, "x"), Triple(2, 0, "TRUE"))).displayText())
    }

    /**
     * A number too wide to render is refused or errors — it never crashes the app.
     *
     * `BigDecimal` accepts an exponent up to `Integer.MAX_VALUE`, so `1e2147483000` parses fine and then
     * throws `OutOfMemoryError` the moment anything calls `toPlainString()` on it: a two-billion-character
     * string from one keystroke. The bound has to cover *results* too, because `=A1*A1` doubles the scale
     * each time — a dozen ordinary squarings reach a scale of hundreds of millions from an accepted starting
     * value. All three entry points are checked, including the formula literal, which bypasses the durable
     * input parser entirely.
     */
    @Test fun numbersTooWideToRenderBecomeErrorsRatherThanCrashes() {
        // Durable input: kept as text rather than accepted as an unprintable number.
        assertTrue(
            "an absurd exponent must not be stored as a number",
            CellInputParser.parse("1e2147483000") is CellInput.Text,
        )
        assertTrue("an ordinary number is still a number", CellInputParser.parse("12.5") is CellInput.Number)
        assertTrue("a large but printable number is still a number", CellInputParser.parse("1e100") is CellInput.Number)

        // Formula literal: a structured parse failure, not a crash.
        assertTrue(
            "an unprintable literal must be a parse failure",
            FormulaParser().parse("=1e2147483000") is FormulaParseResult.Failure,
        )

        // Computed result: scale compounds, so the guard has to sit on the arithmetic too.
        var workbook = CalculationWorkbook()
        val sheet = workbook.activeSheet()
        workbook = input(workbook, sheet.id, 0, 0, "1e-90000")
        for (row in 1..12) workbook = input(workbook, sheet.id, row, 0, "=A$row*A$row")
        val engine = CalculationEngine(workbook)
        val wide = engine.valueAt(sheet.id, 12, 0)
        assertTrue("a result too wide to print must be an error value", wide is CalculatedValue.ErrorValue)
        assertEquals(
            "and must say why, as a resource limit",
            CalculationErrorKind.RESOURCE_LIMIT,
            (wide as CalculatedValue.ErrorValue).error.kind,
        )
        // The symptom was a 92-million-character display string; a short error label is the fix.
        assertTrue("displaying it must stay cheap", engine.displayAt(sheet.id, 12, 0).length < 32)
        assertEquals("arithmetic that stays in range is untouched", "4", evaluate("=A1*A1", listOf(Triple(0, 0, "2"))).displayText())
    }
}
