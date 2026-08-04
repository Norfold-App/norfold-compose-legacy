package com.norfold.app.editor.calculation

import com.norfold.app.domain.BlockDocument
import com.norfold.app.domain.BlockDocumentJson
import com.norfold.app.domain.InlineText
import com.norfold.app.domain.TableBlock
import com.norfold.app.domain.TableCell
import com.norfold.app.domain.UnknownBlock
import com.norfold.app.domain.plainText
import com.norfold.app.domain.style.ColorValue
import com.norfold.app.editor.flags.EditorFeatureFlag
import com.norfold.app.editor.flags.EditorFeatureFlags
import com.norfold.app.editor.history.EditTarget
import java.io.File
import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Executable ledger for the owner's numbered Phase-4 validations (1..178). */
class Phase4RequiredValidationTest {
    private val exercised = linkedSetOf<Int>()

    private fun case(id: Int, condition: Boolean, detail: String = "case $id") {
        assertTrue(detail, condition)
        exercised += id
    }

    private fun input(workbook: CalculationWorkbook, sheetId: SheetId, row: Int, column: Int, raw: String): CalculationWorkbook {
        val engine = CalculationEngine(workbook)
        val result = engine.commitInput(sheetId, row, column, CellInputParser.parse(raw), confirmWarning = true)
        assertTrue(result.accepted)
        return engine.workbook()
    }

    private fun eval(formula: String, setup: List<Triple<Int, Int, String>> = emptyList()): CalculatedValue {
        var workbook = CalculationWorkbook()
        val sheet = workbook.activeSheet()
        setup.forEach { (row, column, raw) -> workbook = input(workbook, sheet.id, row, column, raw) }
        workbook = input(workbook, sheet.id, 20, 20, formula)
        return CalculationEngine(workbook).valueAt(sheet.id, 20, 20)
    }

    private fun legacy(id: String = "legacy") = TableBlock(
        id = id,
        headers = listOf(TableCell(listOf(InlineText("Name"))), TableCell(listOf(InlineText("Value")))),
        rows = listOf(listOf(TableCell(listOf(InlineText("Alpha"))), TableCell(listOf(InlineText("10"))))),
        columnWidthsDp = listOf(120f, 80f),
    )

    @Test fun cases001Through042_formulaParserValuesReferencesFunctionsAndSecurity() {
        case(1, eval("=7").displayText() == "7")
        case(2, eval("=1.25").displayText() == "1.25")
        case(3, eval("=\"text\"").displayText() == "text")
        case(4, eval("=TRUE").displayText() == "TRUE")
        case(5, eval("=-2").displayText() == "-2")
        case(6, eval("=1+2*3").displayText() == "7")
        case(7, eval("=(1+2)*3").displayText() == "9")
        case(8, eval("=2>=1").displayText() == "TRUE")
        case(9, eval("=\"Nor\"&\"fold\"").displayText() == "Norfold")
        val malformed = CellInput.Formula("=1+")
        var malformedBook = CalculationWorkbook()
        val malformedSheet = malformedBook.activeSheet()
        malformedBook = input(malformedBook, malformedSheet.id, 0, 0, malformed.source)
        case(10, malformedBook.activeSheet().cellAt(0, 0)?.input == malformed && (CalculationEngine(malformedBook).valueAt(malformedSheet.id, 0, 0) as CalculatedValue.ErrorValue).error.kind == CalculationErrorKind.PARSE)
        case(11, runCatching { CellInput.Number("1,2") }.isFailure)
        case(12, listOf("NaN", "Infinity", "-Infinity").all { runCatching { CellInput.Number(it) }.isFailure })

        val setup = listOf(Triple(0, 0, "2"), Triple(1, 0, "3"), Triple(0, 1, "x"))
        case(13, eval("=A1", setup).displayText() == "2")
        case(14, eval("=\$A\$1", setup).displayText() == "2")
        case(15, eval("=A\$2+\$A1", setup).displayText() == "5")
        case(16, eval("=SUM(A1:A2)", setup).displayText() == "5")
        val first = CalculationSheet(name = "Main")
        val second = CalculationSheet(name = "Quarter One")
        var cross = CalculationWorkbook(sheets = listOf(first, second), activeSheetId = first.id)
        cross = input(cross, second.id, 0, 0, "9")
        cross = input(cross, first.id, 0, 0, "='Quarter One'!A1")
        case(17, CalculationEngine(cross).displayAt(first.id, 0, 0) == "9")
        case(18, (cross.sheet(first.id)!!.cellAt(0, 0)?.input as CellInput.Formula).source.contains("'Quarter One'"))
        case(19, eval("=Z99+0").displayText() == "0")
        case(20, (eval("=Missing!A1") as CalculatedValue.ErrorValue).error.kind == CalculationErrorKind.REFERENCE)

        case(21, eval("=SUM(A1:A2)", setup).displayText() == "5")
        case(22, eval("=AVERAGE(A1:A2)", setup).displayText() == "2.5")
        case(23, eval("=MIN(A1:A2)+MAX(A1:A2)", setup).displayText() == "5")
        case(24, eval("=COUNT(A1:B2)+COUNTA(A1:B2)", setup).displayText() == "5")
        case(25, eval("=IF(A1=2,\"yes\",\"no\")", setup).displayText() == "yes")
        case(26, eval("=AND(A1=2,OR(A2=3,NOT TRUE))", setup).displayText() == "TRUE")
        case(27, eval("=IFERROR(1/0,\"safe\")").displayText() == "safe")
        // 1.3 (half-up) + 1.3 (away from zero) + 1.2 (toward zero). Each mode must differ from the others:
        // if ROUNDUP and ROUNDDOWN both used HALF_UP the sum would be 1.3 + 1.2 + 1.3 = 3.8 as well, so the
        // directions are pinned individually below.
        case(28, eval("=ROUND(1.25,1)+ROUNDUP(1.21,1)+ROUNDDOWN(1.29,1)").displayText() == "3.8")
        case(28, eval("=ROUNDUP(1.21,1)").displayText() == "1.3" && eval("=ROUNDDOWN(1.29,1)").displayText() == "1.2")
        case(28, eval("=ROUNDUP(-1.21,1)").displayText() == "-1.3" && eval("=ROUNDDOWN(-1.29,1)").displayText() == "-1.2")
        val criteria = setup + listOf(Triple(0, 2, "10"), Triple(1, 2, "20"))
        case(29, eval("=SUMIF(A1:A2,\">2\",C1:C2)+COUNTIF(A1:A2,\">1\")", criteria).displayText() == "22")
        case(30, eval("=LEN(TRIM(UPPER(\" a  b \")))+LEN(LEFT(\"abcd\",2))+LEN(RIGHT(\"abcd\",2))+LEN(MID(\"abcd\",2,2))").displayText() == "9")
        case(31, eval("=INDEX(A1:A2,MATCH(3,A1:A2,0))", setup).displayText() == "3")
        case(32, eval("=XLOOKUP(3,A1:A2,B1:B2,\"missing\")", setup).displayText().isEmpty())
        case(33, eval("=YEAR(DATE(2026,8,1))+MONTH(DATE(2026,8,1))+DAY(DATE(2026,8,1))").displayText() == "2035")
        case(34, (eval("=ABS(1,2)") as CalculatedValue.ErrorValue).error.kind == CalculationErrorKind.VALUE)
        case(35, (eval("=ABS(\"word\")") as CalculatedValue.ErrorValue).error.kind == CalculationErrorKind.VALUE)
        case(36, (eval("=NOT_A_FUNCTION(1)") as CalculatedValue.ErrorValue).error.kind == CalculationErrorKind.NAME)
        case(37, (eval("=1+(1/0)") as CalculatedValue.ErrorValue).error.kind == CalculationErrorKind.DIVIDE_BY_ZERO)

        // Security is asserted two ways, because either alone is weak.
        //
        // Behaviourally first: formulas that *look* like a URL, a path or a class reference must evaluate to
        // ordinary data or a name error — never a fetch, a read or a lookup. This is what actually matters,
        // and it would still hold if the source scan below were somehow bypassed.
        case(38, eval("=\"https://example.com/secret\"").displayText() == "https://example.com/secret")
        case(38, (eval("=HTTP(\"https://example.com\")") as CalculatedValue.ErrorValue).error.kind == CalculationErrorKind.NAME)
        case(38, (eval("=WEBSERVICE(\"https://example.com\")") as CalculatedValue.ErrorValue).error.kind == CalculationErrorKind.NAME)
        case(39, eval("=\"/etc/passwd\"").displayText() == "/etc/passwd")
        case(39, (eval("=READFILE(\"/etc/passwd\")") as CalculatedValue.ErrorValue).error.kind == CalculationErrorKind.NAME)
        case(39, (eval("=IMPORTDATA(\"file:///etc/passwd\")") as CalculatedValue.ErrorValue).error.kind == CalculationErrorKind.NAME)
        case(40, (eval("=EVAL(\"1+1\")") as CalculatedValue.ErrorValue).error.kind == CalculationErrorKind.NAME)
        case(40, (eval("=java.lang.Runtime(1)") as CalculatedValue.ErrorValue).error.kind == CalculationErrorKind.NAME)

        // And structurally: the package must not even link against the capabilities. The directory check is
        // not decorative — without it a wrong path would make every `!in` trivially true and this whole
        // group would pass while scanning nothing.
        val calculationDir = File("src/main/java/com/norfold/app/editor/calculation")
        assertTrue("the calculation package must be where this scan looks", calculationDir.isDirectory)
        val calculationFiles = calculationDir.walkTopDown().filter { it.extension == "kt" }.toList()
        assertTrue("the scan must actually read the package", calculationFiles.size >= 10)
        val calculationSources = calculationFiles.joinToString("\n") { it.readText() }
        case(38, "java.net" !in calculationSources && "okhttp" !in calculationSources.lowercase())
        case(39, "java.io.File" !in calculationSources && "kotlin.io" !in calculationSources)
        case(40, "kotlin.reflect" !in calculationSources && "ProcessBuilder" !in calculationSources && "Runtime.getRuntime" !in calculationSources)
        case(41, FormulaParser(maxDepth = 4).parse("=((((((1))))))") is FormulaParseResult.Failure)
        val huge = CalculationSheet(rowCount = 101, columnCount = 2, rows = List(101) { CalculationRow() }, columns = List(2) { CalculationColumn() })
        var hugeBook = CalculationWorkbook(sheets = listOf(huge), activeSheetId = huge.id)
        hugeBook = input(hugeBook, huge.id, 0, 1, "=SUM(A1:A101)")
        case(42, (CalculationEngine(hugeBook, CalculationLimits(maximumRangeCells = 100)).valueAt(huge.id, 0, 1) as CalculatedValue.ErrorValue).error.kind == CalculationErrorKind.RESOURCE_LIMIT)
        assertEquals((1..42).toSet(), exercised)
    }

    @Test fun cases043Through057_dependencyGraphAndIncrementalRecalculation() {
        var workbook = CalculationWorkbook()
        val sheet = workbook.activeSheet()
        workbook = input(workbook, sheet.id, 0, 0, "1")
        workbook = input(workbook, sheet.id, 0, 1, "=A1+1")
        workbook = input(workbook, sheet.id, 0, 2, "=B1+1")
        workbook = input(workbook, sheet.id, 5, 5, "=40+2")
        val engine = CalculationEngine(workbook)
        val before = engine.instrumentation()
        engine.commitInput(sheet.id, 0, 0, CellInput.Number("4"))
        val after = engine.instrumentation()
        case(43, engine.displayAt(sheet.id, 0, 1) == "5")
        case(44, engine.displayAt(sheet.id, 0, 2) == "6")
        // The independent `=40+2` at F6 must not be re-evaluated. Counting against the *current* sheet is
        // the point: `sheet` was captured while the workbook was still empty, so measuring against it would
        // compare a delta to zero and pass for the wrong reason.
        val liveCellCount = engine.workbook().activeSheet().cells.size
        case(45, after.evaluatedCellCount - before.evaluatedCellCount < liveCellCount)
        engine.commitInput(sheet.id, 0, 1, CellInput.Formula("=10"))
        val edgeMetrics = engine.instrumentation()
        case(46, edgeMetrics.dependencyEdgesRemoved > before.dependencyEdgesRemoved)

        var rangeBook = engine.workbook()
        rangeBook = input(rangeBook, sheet.id, 1, 0, "=SUM(A1:A1)")
        val rangeEngine = CalculationEngine(rangeBook)
        rangeEngine.commitInput(sheet.id, 0, 0, CellInput.Number("8"))
        case(47, rangeEngine.displayAt(sheet.id, 1, 0) == "8")
        val other = CalculationSheet(name = "Other")
        var cross = CalculationWorkbook(sheets = listOf(sheet, other), activeSheetId = sheet.id)
        cross = input(cross, other.id, 0, 0, "2")
        cross = input(cross, sheet.id, 0, 0, "=Other!A1")
        val crossEngine = CalculationEngine(cross)
        crossEngine.commitInput(other.id, 0, 0, CellInput.Number("3"))
        case(48, crossEngine.displayAt(sheet.id, 0, 0) == "3")
        val named = NamedRange(name = "Value", target = StableCellRange(other.coordinateAt(0, 0)!!, other.coordinateAt(0, 0)!!))
        cross = crossEngine.workbook().copy(namedRanges = listOf(named))
        cross = input(cross, sheet.id, 0, 1, "=Value")
        case(49, CalculationEngine(cross).displayAt(sheet.id, 0, 1) == "3")
        // `headerRow = false`: the target is one cell, so declaring a header would leave zero data rows and
        // the sum would legitimately be 0 — which would pass a `!= error` check while proving nothing.
        val table = EmbeddedTableDefinition(
            name = "Data",
            range = named.target,
            columns = listOf(EmbeddedTableColumn(other.columns[0].id, "Amount")),
            headerRow = false,
        )
        cross = cross.copy(embeddedTables = listOf(table))
        cross = input(cross, sheet.id, 0, 2, "=SUM(Data[Amount])")
        case(50, CalculationEngine(cross).displayAt(sheet.id, 0, 2) == "3")
        // And the dependency edge is live: editing the source cell recalculates the structured reference.
        val structuredEngine = CalculationEngine(cross)
        structuredEngine.commitInput(other.id, 0, 0, CellInput.Number("11"))
        case(50, structuredEngine.displayAt(sheet.id, 0, 2) == "11")

        var cycle = CalculationWorkbook()
        val cycleSheet = cycle.activeSheet()
        cycle = input(cycle, cycleSheet.id, 0, 0, "=B1")
        cycle = input(cycle, cycleSheet.id, 0, 1, "=A1")
        case(51, (CalculationEngine(cycle).valueAt(cycleSheet.id, 0, 0) as CalculatedValue.ErrorValue).error.kind == CalculationErrorKind.CYCLE)
        cycle = input(cycle, cycleSheet.id, 0, 2, "=A1")
        case(52, (CalculationEngine(cycle).valueAt(cycleSheet.id, 0, 2) as CalculatedValue.ErrorValue).error.kind == CalculationErrorKind.CYCLE)
        cycle = input(cycle, cycleSheet.id, 0, 1, "9")
        case(53, CalculationEngine(cycle).displayAt(cycleSheet.id, 0, 0) == "9")
        val batch = CalculationEngine(CalculationWorkbook())
        val batchSheet = batch.workbook().activeSheet()
        val batchesBefore = batch.instrumentation().batchCount
        batch.commitBatch(batchSheet.id, listOf(CellMutation(0, 0, CellInput.Number("1")), CellMutation(0, 1, CellInput.Number("2"))))
        case(54, batch.instrumentation().batchCount == batchesBefore + 1)
        val encoded = CalculationWorkbookJson.encode(batch.workbook())
        case(55, "calculated" !in encoded && "cache" !in encoded)
        val deterministicA = CalculationEngine(cycle).instrumentation().formulasRecalculated
        val deterministicB = CalculationEngine(cycle).instrumentation().formulasRecalculated
        case(56, deterministicA == deterministicB)
        var branch = cycle
        branch = input(branch, cycleSheet.id, 5, 5, "=1+1")
        case(57, CalculationEngine(branch).displayAt(cycleSheet.id, 5, 5) == "2")
        assertEquals((43..57).toSet(), exercised)
    }

    @Test fun cases058Through089_structuralEditsSheetsAndNames() {
        val block = SpreadsheetWorkbookFactory.createBlock()
        val runtime = SpreadsheetHostRuntime(EditTarget("validation:structure"), BlockDocument(listOf(block)))
        runtime.select(GridAddress(1, 1)); runtime.commitFormulaBar("=A1+\$A\$1+A\$1+\$A1")
        runtime.select(GridAddress(1, 1))
        case(58, runtime.insertRowAbove())
        case(59, runtime.insertRowBelow())
        case(60, runtime.insertColumnLeft())
        case(61, runtime.insertColumnRight())
        // Registered *and* executing: resolving the id proves only that a string was registered.
        case(62, listOf(SpreadsheetCommandIds.INSERT_ROW_ABOVE, SpreadsheetCommandIds.INSERT_ROW_BELOW, SpreadsheetCommandIds.INSERT_COLUMN_LEFT, SpreadsheetCommandIds.INSERT_COLUMN_RIGHT).all { runtime.commandRegistry.get(it) != null })
        runtime.select(GridAddress(1, 1))
        val rowsBeforeCommand = runtime.state().activeSheet.rowCount
        case(62, runtime.invoke(SpreadsheetCommandIds.INSERT_ROW_ABOVE) is com.norfold.app.editor.command.CommandResult.Success)
        case(62, runtime.state().activeSheet.rowCount == rowsBeforeCommand + 1)
        case(63, runtime.state().selection is TableSelectionState.CellSelected)
        val depth = runtime.controller.undoDepth()
        case(64, runtime.insertRowBelow() && runtime.controller.undoDepth() == depth + 1)
        val source = runtime.state().activeSheet.cells.mapNotNull { (it.input as? CellInput.Formula)?.source }.first()
        case(65, "A" in source)
        case(66, "\$A\$" in source)
        case(67, "A\$" in source && "\$A" in source)

        val sheet = CalculationSheet()
        var workbook = CalculationWorkbook(sheets = listOf(sheet), activeSheetId = sheet.id)
        workbook = input(workbook, sheet.id, 3, 3, "=SUM(A1:A3)")
        val expanded = CalculationMutations.insertRow(workbook, sheet.id, 1).workbook
        case(68, (expanded.activeSheet().cellAt(4, 3)?.input as CellInput.Formula).source == "=SUM(A1:A4)")
        val other = CalculationSheet(name = "Other")
        var cross = CalculationWorkbook(sheets = listOf(sheet, other), activeSheetId = sheet.id)
        cross = input(cross, other.id, 0, 0, "=Sheet1!A1")
        val inserted = CalculationMutations.insertRow(cross, sheet.id, 0).workbook
        case(69, (inserted.sheet(other.id)!!.cellAt(0, 0)?.input as CellInput.Formula).source == "=Sheet1!A2")
        val named = NamedRange(name = "Named", target = StableCellRange(sheet.coordinateAt(0, 0)!!, sheet.coordinateAt(2, 0)!!))
        val table = EmbeddedTableDefinition(name = "TableA", range = StableCellRange(sheet.coordinateAt(0, 0)!!, sheet.coordinateAt(2, 1)!!), columns = listOf(EmbeddedTableColumn(sheet.columns[0].id, "One"), EmbeddedTableColumn(sheet.columns[1].id, "Two")))
        val validation = ValidationRule(target = named.target, condition = ValidationCondition.TextLength(0, 4))
        val conditional = ConditionalFormatRule(target = named.target, condition = ConditionalCondition.TextContains("x"), priority = 1, accessibleDescription = "Contains x")
        val merged = MergedRegion(range = StableCellRange(sheet.coordinateAt(0, 0)!!, sheet.coordinateAt(0, 1)!!))
        val richSheet = sheet.copy(validations = listOf(validation), conditionalFormats = listOf(conditional), mergedRegions = listOf(merged))
        val rich = CalculationWorkbook(sheets = listOf(richSheet), activeSheetId = sheet.id, namedRanges = listOf(named), embeddedTables = listOf(table))
        val richInserted = CalculationMutations.insertRow(rich, sheet.id, 1).workbook
        case(70, richInserted.namedRanges.single().target == named.target)
        case(71, richInserted.embeddedTables.single().columns.map { it.id } == table.columns.map { it.id })
        case(72, richInserted.activeSheet().validations.single().target == validation.target)
        case(73, richInserted.activeSheet().conditionalFormats.single().target == conditional.target)
        case(74, richInserted.activeSheet().mergedRegions.single().range == merged.range)
        // A *range* spanning the deleted row survives, shrunk: A1:A3 becomes A1:A2. Only a reference whose
        // whole target is gone becomes #REF!, so both halves are pinned — asserting the error on the range
        // would have demanded the wrong (Excel-incompatible, data-destroying) behaviour.
        val deletion = CalculationMutations.deleteRows(workbook, sheet.id, 0).workbook
        case(75, (deletion.activeSheet().cellAt(2, 3)?.input as CellInput.Formula).source == "=SUM(A1:A2)")
        val singleSheet = CalculationSheet()
        var single = CalculationWorkbook(sheets = listOf(singleSheet), activeSheetId = singleSheet.id)
        single = input(single, singleSheet.id, 3, 3, "=A2")
        val singleDeleted = CalculationMutations.deleteRows(single, singleSheet.id, 1).workbook
        case(75, (CalculationEngine(singleDeleted).valueAt(singleSheet.id, 2, 3) as CalculatedValue.ErrorValue).error.kind == CalculationErrorKind.REFERENCE)
        val beforeUndo = runtime.state().workbook
        runtime.deleteSelectedRow(); runtime.undo()
        case(76, runtime.state().workbook == beforeUndo && runtime.redo())
        case(77, runtime.controller.undoDepth() >= 1)

        var sheets = CalculationWorkbook()
        sheets = CalculationMutations.addSheet(sheets, "Data")
        case(78, sheets.sheets.size == 2)
        val firstSheet = sheets.sheets.first()
        sheets = input(sheets, sheets.sheets.last().id, 0, 0, "=Sheet1!A1")
        sheets = CalculationMutations.renameSheet(sheets, firstSheet.id, "Input")
        case(79, (sheets.activeSheet().cellAt(0, 0)?.input as CellInput.Formula).source == "=Input!A1")
        val reordered = CalculationMutations.reorderSheet(sheets, firstSheet.id, 1)
        case(80, (reordered.activeSheet().cellAt(0, 0)?.input as CellInput.Formula).source == "=Input!A1")
        val duplicated = CalculationMutations.duplicateSheet(reordered, reordered.activeSheetId)
        case(81, duplicated.sheets.size == 3 && duplicated.activeSheet().name.contains("Copy"))
        val deletedSheet = CalculationMutations.deleteSheet(reordered, firstSheet.id)
        case(82, (CalculationEngine(deletedSheet).valueAt(deletedSheet.activeSheetId, 0, 0) as CalculatedValue.ErrorValue).error.kind == CalculationErrorKind.REFERENCE)
        val sheetRuntime = SpreadsheetHostRuntime(EditTarget("validation:sheets"), BlockDocument(listOf(SpreadsheetWorkbookFactory.createBlock())))
        sheetRuntime.addSheet(); val originalSheetBook = sheetRuntime.state().workbook; sheetRuntime.deleteActiveSheet(); sheetRuntime.undo()
        case(83, sheetRuntime.state().workbook == originalSheetBook)
        case(84, CalculationMutations.addSheet(CalculationWorkbook(), "Sheet1").sheets.last().name != "Sheet1")
        val namedSheet = CalculationSheet()
        val namedRange = NamedRange(name = "Revenue", target = StableCellRange(namedSheet.coordinateAt(0, 0)!!, namedSheet.coordinateAt(1, 0)!!))
        var namedBook = CalculationWorkbook(sheets = listOf(namedSheet), activeSheetId = namedSheet.id, namedRanges = listOf(namedRange))
        namedBook = input(namedBook, namedSheet.id, 0, 0, "2")
        namedBook = input(namedBook, namedSheet.id, 3, 0, "=SUM(Revenue)")
        case(85, CalculationEngine(namedBook).displayAt(namedSheet.id, 3, 0) == "2")
        namedBook = CalculationMutations.renameNamedRange(namedBook, namedRange.id, "Income")
        case(86, (namedBook.activeSheet().cellAt(3, 0)?.input as CellInput.Formula).source == "=SUM(Income)")
        val namedMoved = CalculationMutations.insertRow(namedBook, namedSheet.id, 0).workbook
        case(87, namedMoved.namedRanges.single().target.start.rowId == namedRange.target.start.rowId)
        val namedDeleted = CalculationMutations.deleteNamedRange(namedBook, namedRange.id)
        case(88, (CalculationEngine(namedDeleted).valueAt(namedSheet.id, 3, 0) as CalculatedValue.ErrorValue).error.kind == CalculationErrorKind.NAME)
        val tableBook = rich.copy(sheets = listOf(richSheet.copy(cells = listOf(CalculationCell(rowId = sheet.rows[2].id, columnId = sheet.columns[2].id, input = CellInput.Formula("=SUM(TableA[One])"))))))
        val renamedTable = CalculationMutations.renameEmbeddedColumn(CalculationMutations.renameEmbeddedTable(tableBook, table.id, "Orders"), table.id, sheet.columns[0].id, "Amount")
        case(89, (renamedTable.activeSheet().cells.single().input as CellInput.Formula).source == "=SUM(Orders[Amount])")
        assertEquals((58..89).toSet(), exercised)
    }

    @Test fun cases090Through106_validationAndConditionalFormatting() {
        val sheet = CalculationSheet()
        val target = StableCellRange(sheet.coordinateAt(0, 0)!!, sheet.coordinateAt(4, 0)!!)
        fun engine(condition: ValidationCondition, policy: ValidationPolicy = ValidationPolicy.REJECT): CalculationEngine = CalculationEngine(
            CalculationWorkbook(sheets = listOf(sheet.copy(validations = listOf(ValidationRule(target = target, condition = condition, policy = policy)))), activeSheetId = sheet.id),
        )
        case(90, engine(ValidationCondition.AllowedValues(listOf("yes"))).commitInput(sheet.id, 0, 0, CellInput.Text("yes")).accepted)
        case(91, !engine(ValidationCondition.AllowedValues(listOf("yes"))).commitInput(sheet.id, 0, 0, CellInput.Text("no")).accepted)
        case(92, engine(ValidationCondition.NumberRange("1", "3")).commitInput(sheet.id, 0, 0, CellInput.Number("2")).accepted)
        case(93, !engine(ValidationCondition.IntegerRange(1, 3)).commitInput(sheet.id, 0, 0, CellInput.Number("2.5")).accepted)
        case(94, engine(ValidationCondition.DateRange("2026-01-01", "2026-12-31")).commitInput(sheet.id, 0, 0, CellInput.DateValue("2026-08-01")).accepted)
        case(95, !engine(ValidationCondition.TextLength(1, 3)).commitInput(sheet.id, 0, 0, CellInput.Text("long")).accepted)
        case(96, engine(ValidationCondition.CustomFormula("=1=1")).commitInput(sheet.id, 0, 0, CellInput.Text("anything")).accepted)
        val warning = engine(ValidationCondition.AllowedValues(listOf("yes")), ValidationPolicy.WARN).commitInput(sheet.id, 0, 0, CellInput.Text("no"))
        case(97, !warning.accepted && warning.requiresConfirmation)
        val validatedBook = engine(ValidationCondition.AllowedValues(listOf("yes"))).workbook()
        // The rule targets A1:A5, so the paste must run *down* that column for both cells to meet it.
        // A horizontal "yes\tno" would put "no" in B1, outside the rule, and pass for the wrong reason.
        val paste = CalculationMutations.pasteGrid(validatedBook, sheet.id, 0, 0, "yes\nno")
        case(98, paste.acceptedCount == 1 && paste.rejectedCount == 1)
        case(98, paste.outcomes.single { !it.accepted }.let { it.row == 1 && it.column == 0 && it.message != null })

        var base = CalculationWorkbook(sheets = listOf(sheet), activeSheetId = sheet.id)
        base = input(base, sheet.id, 0, 0, "5")
        base = input(base, sheet.id, 1, 0, "hello")
        base = input(base, sheet.id, 2, 0, "5")
        fun conditional(condition: ConditionalCondition, priority: Int = 1, stop: Boolean = false, fill: ColorValue = ColorValue(1, 2, 3)) = ConditionalFormatRule(
            target = target, condition = condition, priority = priority, stopIfTrue = stop,
            formatDelta = CellFormat(fillColor = fill), accessibleDescription = "Conditional rule",
        )
        var configured = base.withSheet(base.activeSheet().copy(conditionalFormats = listOf(conditional(ConditionalCondition.Comparison(ComparisonOperator.GREATER, "3")))))
        case(99, CalculationEngine(configured).conditionalFormat(sheet.id, 0, 0).format.fillColor == ColorValue(1, 2, 3))
        configured = base.withSheet(base.activeSheet().copy(conditionalFormats = listOf(conditional(ConditionalCondition.TextContains("ell")))))
        case(100, CalculationEngine(configured).conditionalFormat(sheet.id, 1, 0).descriptions.isNotEmpty())
        configured = base.withSheet(base.activeSheet().copy(conditionalFormats = listOf(conditional(ConditionalCondition.CustomFormula("=1=1")))))
        case(101, CalculationEngine(configured).conditionalFormat(sheet.id, 0, 0).descriptions.isNotEmpty())
        configured = base.withSheet(base.activeSheet().copy(conditionalFormats = listOf(conditional(ConditionalCondition.Duplicate), conditional(ConditionalCondition.Unique, 2))))
        case(102, CalculationEngine(configured).conditionalFormat(sheet.id, 0, 0).descriptions.first().contains("Duplicate"))
        configured = base.withSheet(base.activeSheet().copy(conditionalFormats = listOf(conditional(ConditionalCondition.ColorScale(ColorValue(0, 0, 0), null, ColorValue(255, 255, 255))))))
        case(103, CalculationEngine(configured).conditionalFormat(sheet.id, 0, 0).format.fillColor != null)
        val priorityRules = listOf(conditional(ConditionalCondition.Comparison(ComparisonOperator.GREATER, "0"), 1, stop = true, fill = ColorValue(1, 1, 1)), conditional(ConditionalCondition.Comparison(ComparisonOperator.GREATER, "0"), 2, fill = ColorValue(2, 2, 2)))
        configured = base.withSheet(base.activeSheet().copy(conditionalFormats = priorityRules))
        case(104, CalculationEngine(configured).conditionalFormat(sheet.id, 0, 0).format.fillColor == ColorValue(1, 1, 1))
        case(105, configured.activeSheet().cellAt(0, 0)?.format?.fillColor == null)
        val conditionalEngine = CalculationEngine(configured)
        val before = conditionalEngine.instrumentation().evaluatedCellCount
        conditionalEngine.commitInput(sheet.id, 4, 4, CellInput.Number("8"))
        case(106, conditionalEngine.instrumentation().evaluatedCellCount - before < configured.activeSheet().cells.size + 2)
        assertEquals((90..106).toSet(), exercised)
    }

    @Test fun cases107Through131_tableInteractionFormattingAndHistory() {
        val selection = TableSelectionController()
        selection.hover(GridAddress(0, 0)); case(107, selection.state is TableSelectionState.Hovered)
        selection.selectCell(GridAddress(0, 0)); selection.edit(GridAddress(0, 0), "x"); case(108, selection.state is TableSelectionState.Editing)
        selection.selectRow(1); case(109, selection.state is TableSelectionState.RowSelected)
        selection.selectColumn(1); case(110, selection.state is TableSelectionState.ColumnSelected)
        selection.selectCell(GridAddress(0, 0)); selection.extendTo(GridAddress(1, 1)); case(111, (selection.state as TableSelectionState.RangeSelected).anchor == GridAddress(0, 0))
        selection.selectWholeTable(); case(112, selection.state == TableSelectionState.WholeTable)
        selection.edit(GridAddress(0, 0), "x"); case(113, selection.stepBack() && selection.state is TableSelectionState.CellSelected)
        val runtime = SpreadsheetHostRuntime(EditTarget("validation:table"), BlockDocument(listOf(SpreadsheetWorkbookFactory.createBlock())))
        runtime.select(GridAddress(0, 0)); case(114, runtime.commandRegistry.get(SpreadsheetCommandIds.MERGE)!!.status(com.norfold.app.editor.command.CommandContext(setOf(com.norfold.app.editor.command.Capability.TABLE_STRUCTURE), 1, true)).isInvokable)
        selection.selectCell(GridAddress(0, 0)); selection.beginRangeDrag(GridAddress(0, 0)); selection.updateRangeDrag(GridAddress(2, 2)); case(115, (selection.state as TableSelectionState.RangeSelected).focus == GridAddress(2, 2))
        selection.cancelRangeDrag(); case(116, selection.state == TableSelectionState.CellSelected(GridAddress(0, 0)))
        runtime.select(GridAddress(1, 1)); val rowDepth = runtime.controller.undoDepth(); runtime.reorderSelectedRow(1); case(117, runtime.controller.undoDepth() == rowDepth + 1)
        val colDepth = runtime.controller.undoDepth(); runtime.reorderSelectedColumn(1); case(118, runtime.controller.undoDepth() == colDepth + 1)
        val resizeDepth = runtime.controller.undoDepth(); runtime.setSelectedColumnWidth(144f); case(119, runtime.controller.undoDepth() == resizeDepth + 1)
        case(120, runtime.state().activeSheet.columns[2].widthPoints == 144f)
        runtime.setSelectedRowHeight(72f); runtime.fitSelectedRowHeight(); case(121, (runtime.state().activeSheet.rows[2].heightPoints ?: 0f) >= 48f)
        runtime.select(GridAddress(0, 0)); runtime.commitFormulaBar("A")
        runtime.select(GridAddress(0, 1)); runtime.commitFormulaBar("B")
        runtime.select(GridAddress(0, 0)); runtime.extendSelection(0, 1)
        val records = runtime.state().activeSheet.cells.toSet(); runtime.mergeSelection(); case(122, runtime.state().activeSheet.cells.toSet() == records)
        runtime.select(GridAddress(0, 0)); runtime.splitSelection(); case(123, runtime.state().activeSheet.cells.toSet() == records)
        runtime.select(GridAddress(3, 3)); val pasteBefore = runtime.state().workbook; runtime.paste("1\t2"); runtime.undo(); case(124, runtime.state().workbook == pasteBefore)
        runtime.redo(); runtime.select(GridAddress(4, 4)); runtime.commitFormulaBar("=D4"); runtime.fillTo(listOf(GridAddress(5, 4))); case(125, (runtime.state().activeSheet.cellAt(5, 4)?.input as CellInput.Formula).source == "=D5")
        var series = CalculationMutations.fillSeries(runtime.state().workbook, runtime.state().activeSheet.id, listOf(GridAddress(6, 0), GridAddress(7, 0)), CellInput.DateValue("2026-08-01"), BigDecimal.ONE)
        case(126, (series.activeSheet().cellAt(7, 0)?.input as CellInput.DateValue).isoDate == "2026-08-02")
        val sortSheet = series.activeSheet(); val sortRange = StableCellRange(sortSheet.coordinateAt(3, 3)!!, sortSheet.coordinateAt(4, 4)!!)
        series = CalculationMutations.sort(series, sortSheet.id, sortRange, sortSheet.columns[3].id, SortDirection.ASCENDING, headerAware = false)
        case(127, series.activeSheet().sorts.isNotEmpty())
        val cellsBeforeFilter = series.activeSheet().cells; series = CalculationMutations.filter(series, sortSheet.id, sortRange, sortSheet.columns[3].id, FilterCondition.Values(listOf("1")), headerAware = false)
        case(128, series.activeSheet().cells == cellsBeforeFilter && series.activeSheet().rows.any { it.hidden })
        runtime.select(GridAddress(0, 0)); runtime.applyFormat(CellFormat(bold = true)); runtime.extendSelection(0, 1); case(129, runtime.state().mixedFormat.mixed)
        val colorBefore = runtime.state().workbook; runtime.applyFormat(CellFormat(fillColor = ColorValue(9, 8, 7))); runtime.undo(); case(130, runtime.state().workbook == colorBefore)
        runtime.redo(); runtime.select(GridAddress(0, 0)); runtime.applyBuiltInTableStyle()
        val localOverride = ColorValue(12, 34, 56)
        runtime.applyFormat(CellFormat(fillColor = localOverride))
        val styled = runtime.state().workbook
        val table = styled.embeddedTables.single()
        val local = styled.activeSheet().cellAt(0, 0)?.format ?: CellFormat()
        case(131, TableStyleResolver.effectiveCellFormat(styled, table, 0, 0, local).fillColor == localOverride)
        assertEquals((107..131).toSet(), exercised)
    }

    @Test fun cases132Through150_realHostIntegrationAndSharedIdentity() {
        val flowSource = File("src/main/java/com/norfold/app/ui/editor/flow/FlowBlockContent.kt").readText()
        val docSource = File("src/main/java/com/norfold/app/ui/editor/document/SceneObjectContent.kt").readText()
        val docRuntime = File("src/main/java/com/norfold/app/editor/document/DocumentHostRuntime.kt").readText()
        val spreadsheetSource = File("src/main/java/com/norfold/app/ui/editor/spreadsheet/SpreadsheetHostScreen.kt").readText()
        val rootSource = File("src/main/java/com/norfold/app/ui/NorfoldAppRoot.kt").readText()
        // Behavioural: the projection every host *renders* from must carry the calculated value, while the
        // text handed to the editor must be the formula source. Getting either backwards is a user-visible
        // defect — a grid showing "=5+5", or an editor that silently replaces a formula with its result.
        case(132, run {
            val table = TableBlock(
                id = "render-vs-edit",
                rows = listOf(listOf(TableCell(listOf(InlineText(""))), TableCell(listOf(InlineText(""))))),
            )
            val controller = com.norfold.app.editor.flow.FlowEditController(
                EditTarget("validation:render-vs-edit"),
                BlockDocument(listOf(table)),
            )
            val host = com.norfold.app.editor.flow.FlowHostRuntime(controller, com.norfold.app.editor.presentation.FormFactor(400, 800))
            assertTrue(host.editTableCell(com.norfold.app.editor.flow.FlowTableCursor(table.id, 0, 0), "=5+5").accepted)
            val stored = controller.document().blocks.single() as TableBlock
            // What the grid draws: the calculated value, refreshed by persist() — never the stale legacy text.
            val rendered = stored.rows.first().first().content.plainText()
            // What the editor is seeded with: the formula the user typed.
            val editable = TableCalculationBridge.editorText(stored, 0, 0)
            rendered == "10" && editable == "=5+5"
        })
        case(132, "TableCalculationBridge.editorText" in flowSource)
        case(133, "editTableCell(cursor, value)" in File("src/main/java/com/norfold/app/ui/editor/flow/FlowHostScreen.kt").readText())
        // Behavioural, not a source-string match: a formula typed into a Flow table cell must be *evaluated*
        // by the shared engine and persisted as a formula, which is the only thing "Flow commits through the
        // shared calculation engine" can mean. A grep for a constructor call would have passed while the host
        // used a private evaluator, and would fail for a refactor that changed nothing a user can see.
        case(134, run {
            val flowTable = TableBlock(
                id = "flow-shared-engine",
                rows = listOf(
                    listOf(TableCell(listOf(InlineText("4")))),
                    listOf(TableCell(listOf(InlineText("")))),
                ),
            )
            val flowController = com.norfold.app.editor.flow.FlowEditController(
                EditTarget("validation:flow-engine"),
                BlockDocument(listOf(flowTable)),
            )
            val flowHost = com.norfold.app.editor.flow.FlowHostRuntime(
                flowController,
                com.norfold.app.editor.presentation.FormFactor(400, 800),
            )
            val committed = flowHost.editTableCell(
                com.norfold.app.editor.flow.FlowTableCursor(flowTable.id, 1, 0),
                "=A1*3",
            )
            val stored = flowController.document().blocks.single() as TableBlock
            val view = TableCalculationBridge.view(stored)
            val storedInput = view.workbook.activeSheet().cellAt(1, 0)?.input
            val evaluated = CalculationEngine(view.workbook).valueAt(view.workbook.activeSheetId, 1, 0)
            committed.accepted &&
                storedInput is CellInput.Formula &&
                (evaluated as? CalculatedValue.Number)?.value?.compareTo(BigDecimal(12)) == 0
        })
        // Behavioural: inserting a row through the Flow directional op must *rewrite* the formulas that
        // reference cells below the insertion point. A grep for the mutation call name would pass while the
        // op spliced arrays positionally and silently broke every formula in the table.
        case(135, run {
            val base = TableBlock(
                id = "flow-insert-rewrites",
                rows = listOf(
                    listOf(TableCell(listOf(InlineText("5")))),
                    listOf(TableCell(listOf(InlineText("")))),
                ),
            )
            // Typed through the host, not seeded as legacy text: migration deliberately does not reinterpret
            // existing text as a formula, so a real formula has to be entered the way a user enters one.
            val flowController = com.norfold.app.editor.flow.FlowEditController(
                EditTarget("validation:flow-insert"),
                BlockDocument(listOf(base)),
            )
            val flowHost = com.norfold.app.editor.flow.FlowHostRuntime(
                flowController,
                com.norfold.app.editor.presentation.FormFactor(400, 800),
            )
            assertTrue(flowHost.editTableCell(com.norfold.app.editor.flow.FlowTableCursor(base.id, 1, 0), "=A1*2").accepted)
            val upgradedBase = flowController.document().blocks.single() as TableBlock
            assertEquals("10", TableCalculationBridge.editorText(upgradedBase, 1, 0).let {
                val seeded = TableCalculationBridge.view(upgradedBase)
                CalculationEngine(seeded.workbook).displayAt(seeded.workbook.activeSheetId, 1, 0)
            })
            val afterInsert = com.norfold.app.editor.flow.FlowTableOps.insertRow(upgradedBase, 0)
            val view = TableCalculationBridge.view(afterInsert)
            val moved = view.workbook.activeSheet().cellAt(2, 0)?.input as? CellInput.Formula
            // The referenced value moved from row 1 to row 2, so the formula must now say A2, not A1.
            moved?.source == "=A2*2" &&
                CalculationEngine(view.workbook).displayAt(view.workbook.activeSheetId, 2, 0) == "10"
        })
        // Behavioural counterpart for the Document host: same render-vs-edit contract, same failure modes.
        case(136, run {
            val docTable = TableBlock(
                id = "doc-render-vs-edit",
                rows = listOf(listOf(TableCell(listOf(InlineText(""))), TableCell(listOf(InlineText(""))))),
            )
            val objectId = "doc-object"
            val page = com.norfold.app.domain.DocumentPageBlock(
                id = "doc-page",
                objects = listOf(
                    com.norfold.app.domain.SceneObject(
                        id = objectId,
                        kind = com.norfold.app.domain.SceneObjectKind.Table,
                        geometry = com.norfold.app.domain.SceneGeometry(0f, 0f, 300f, 180f),
                        content = docTable,
                    ),
                ),
            ).normalized()
            val docHost = com.norfold.app.editor.document.DocumentHostRuntime(
                com.norfold.app.editor.document.DocumentSceneController(EditTarget("validation:doc-render"), BlockDocument(listOf(page))),
                com.norfold.app.editor.presentation.FormFactor(800, 1_000),
            )
            docHost.scope.activePageId = page.id
            docHost.setRepresentation(com.norfold.app.editor.document.LayoutRepresentation.ORIGINAL)
            docHost.setIntent(com.norfold.app.editor.document.WorkingIntent.EDIT)
            assertTrue(docHost.beginContentEdit(objectId))
            assertTrue(docHost.editObjectTableCell(objectId, 0, 0, "=6*7").accepted)
            val stored = docHost.controller.findObject(objectId)?.second?.content as TableBlock
            stored.rows.first().first().content.plainText() == "42" &&
                TableCalculationBridge.editorText(stored, 0, 0) == "=6*7"
        })
        case(136, "TableCalculationBridge.editorText" in docSource)
        case(137, "engine.commitInput" in docRuntime)
        case(138, "renderScale" in docSource && "pointToDp" in docSource)
        case(139, "editing = editing" in docSource && "if (editing && active)" in docSource)
        case(140, "activeCell" in docSource && "editingObjectId" in docRuntime)
        case(141, "class SpreadsheetHostRuntime" in File("src/main/java/com/norfold/app/editor/calculation/SpreadsheetHostRuntime.kt").readText())
        case(142, "Destination.SpreadsheetEditor" in rootSource && "SPREADSHEET_HOST" in File("src/main/java/com/norfold/app/ui/screens/SidebarScreen.kt").readText())
        val runtime = SpreadsheetHostRuntime(EditTarget("validation:host"), BlockDocument(listOf(SpreadsheetWorkbookFactory.createBlock())))
        runtime.select(GridAddress(0, 0)); runtime.commitFormulaBar("7"); runtime.addSheet(); val second = runtime.state().workbook.activeSheetId; runtime.select(GridAddress(0, 0)); runtime.commitFormulaBar("=Sheet1!A1*2"); runtime.select(GridAddress(1, 1)); runtime.freezeAtSelection()
        case(143, runtime.state().activeSheet.frozenRows == 1 && runtime.state().workbook.sheets.size == 2)
        case(144, CalculationEngine(runtime.state().workbook).displayAt(second, 0, 0) == "14")
        runtime.select(GridAddress(1, 1)); case(145, runtime.commitFormulaBar("=1+1").accepted && runtime.state().formulaBarText == "=1+1")
        val frozenPlan = SpreadsheetViewportPlanner.plan(runtime.state().activeSheet.copy(frozenRows = 1, frozenColumns = 1), 400f, 400f, 500f, 500f)
        case(146, frozenPlan.frozenRows == 1 && frozenPlan.firstRow >= 1)
        val registry = SpreadsheetSessionRegistry(); case(147, registry.size() == 0 && "remember(sessionKey)" in File("src/main/java/com/norfold/app/ui/editor/SpreadsheetEditorScreen.kt").readText())
        val shared = legacy("shared")
        val firstHost = SpreadsheetHostRuntime(EditTarget("validation:shared-a"), BlockDocument(listOf(shared)), shared.id)
        firstHost.select(GridAddress(1, 1)); firstHost.commitFormulaBar("21")
        val embedded = firstHost.controller.document().blocks.single() as TableBlock
        case(148, TableCalculationBridge.editorText(embedded, 0, 1) == "21")
        val secondHost = SpreadsheetHostRuntime(EditTarget("validation:shared-b"), firstHost.controller.document(), shared.id)
        secondHost.select(GridAddress(1, 1)); secondHost.commitFormulaBar("22")
        case(149, TableCalculationBridge.editorText(secondHost.controller.document().blocks.single() as TableBlock, 0, 1) == "22")
        case(150, (secondHost.controller.document().blocks.single() as TableBlock).calculationPayload == secondHost.state().workbook.let(CalculationWorkbookJson::encode))
        assertEquals((132..150).toSet(), exercised)
    }

    @Test fun cases151Through168_persistenceMigrationRecoveryAndFlags() {
        val original = legacy()
        val legacyJson = BlockDocumentJson.encode(BlockDocument(listOf(original)))
        val decoded = BlockDocumentJson.decode(legacyJson).blocks.single() as TableBlock
        case(151, decoded == original && decoded.calculationPayload == null)
        case(152, TableCalculationBridge.view(decoded).workbook.activeSheet().cells.isNotEmpty())
        val upgraded = TableCalculationBridge.upgrade(decoded) as TableUpgradeResult.Success
        case(153, upgraded.view.workbook.activeSheet().cells.size == 4)
        case(154, upgraded.view.workbook.activeSheet().columns.map { it.widthPoints } == listOf(90f, 60f))
        case(155, (TableCalculationBridge.upgrade(upgraded.block) as TableUpgradeResult.Success).block == upgraded.block)
        val controller = com.norfold.app.editor.flow.FlowEditController(EditTarget("validation:recovery"), BlockDocument(listOf(original)))
        val flow = com.norfold.app.editor.flow.FlowHostRuntime(controller, com.norfold.app.editor.presentation.FormFactor(400, 800))
        flow.editTableCell(com.norfold.app.editor.flow.FlowTableCursor(original.id, 0, 1), "11")
        case(156, controller.recoverySnapshots().size == 1)
        case(157, TableCalculationBridge.restoreLegacy(controller.document().blocks.single() as TableBlock) == original)
        var workbook = upgraded.view.workbook
        val sheet = workbook.activeSheet()
        workbook = input(workbook, sheet.id, 1, 1, "=1+1")
        case(158, (CalculationWorkbookJson.decode(CalculationWorkbookJson.encode(workbook)).activeSheet().cellAt(1, 1)?.input as CellInput.Formula).source == "=1+1")
        workbook = input(workbook, sheet.id, 0, 0, "TRUE")
        case(159, CalculationWorkbookJson.decode(CalculationWorkbookJson.encode(workbook)).activeSheet().cellAt(0, 0)?.input is CellInput.BooleanValue)
        val named = NamedRange(name = "Named", target = StableCellRange(sheet.coordinateAt(0, 0)!!, sheet.coordinateAt(1, 0)!!))
        workbook = workbook.copy(namedRanges = listOf(named), sheets = workbook.sheets + CalculationSheet(name = "Second"))
        case(160, CalculationWorkbookJson.decode(CalculationWorkbookJson.encode(workbook)).namedRanges.single().id == named.id)
        workbook = CalculationMutations.applyFormat(workbook, sheet.id, listOf(GridAddress(0, 0)), CellFormat(fillColor = ColorValue(1, 2, 3)))
        case(161, CalculationWorkbookJson.decode(CalculationWorkbookJson.encode(workbook)).sheet(sheet.id)!!.cellAt(0, 0)?.format?.fillColor == ColorValue(1, 2, 3))
        val range = named.target
        workbook = workbook.withSheet(workbook.sheet(sheet.id)!!.copy(
            validations = listOf(ValidationRule(target = range, condition = ValidationCondition.TextLength(0, 3))),
            conditionalFormats = listOf(ConditionalFormatRule(target = range, condition = ConditionalCondition.Unique, priority = 1, accessibleDescription = "Unique")),
            mergedRegions = listOf(MergedRegion(range = range)),
        ))
        val restored = CalculationWorkbookJson.decode(CalculationWorkbookJson.encode(workbook))
        case(162, restored.sheet(sheet.id)!!.validations.size == 1)
        case(163, restored.sheet(sheet.id)!!.conditionalFormats.size == 1)
        case(164, restored.sheet(sheet.id)!!.mergedRegions.size == 1)
        val unknown = UnknownBlock("future", """{"kind":"future","id":"future","x":1}""")
        val unknownRoundTrip = BlockDocumentJson.decode(BlockDocumentJson.encode(BlockDocument(listOf(unknown)))).blocks.single() as UnknownBlock
        case(165, unknownRoundTrip.rawJson == unknown.rawJson)
        val persisted = TableCalculationBridge.persist(upgraded.block, workbook.copy(activeSheetId = sheet.id), upgraded.view.tableId)
        case(166, CalculationEngine(TableCalculationBridge.view(BlockDocumentJson.decode(BlockDocumentJson.encode(BlockDocument(listOf(persisted)))).blocks.single() as TableBlock).workbook).displayAt(sheet.id, 1, 1) == "2")
        case(167, "calculated" !in persisted.calculationPayload.orEmpty())
        val flags = EditorFeatureFlags.productionDefaults()
        case(168, !flags.isEnabled(EditorFeatureFlag.SPREADSHEET_HOST) && decoded.plainText().contains("Alpha"))
        assertEquals((151..168).toSet(), exercised)
    }

    @Test fun cases169Through178_sparseVirtualizedIncrementalInstrumentation() {
        val sheet = CalculationSheet(rowCount = 1_000, columnCount = 200, rows = List(1_000) { CalculationRow() }, columns = List(200) { CalculationColumn() }, frozenRows = 2, frozenColumns = 2)
        case(169, sheet.cells.isEmpty())
        val viewport = SpreadsheetViewportPlanner.plan(sheet, 8_000f, 4_000f, 600f, 800f)
        case(170, viewport.lastRow - viewport.firstRow < 50)
        case(171, viewport.lastColumn - viewport.firstColumn < 20)
        case(172, viewport.composedCellCount < 1_000)
        val gridSource = File("src/main/java/com/norfold/app/ui/editor/spreadsheet/SpreadsheetHostScreen.kt").readText()
        case(173, "rowsToCompose" in gridSource && "columnsToCompose" in gridSource && "for (row in rowsToCompose)" in gridSource)
        var workbook = CalculationWorkbook()
        val active = workbook.activeSheet()
        workbook = input(workbook, active.id, 0, 0, "1")
        workbook = input(workbook, active.id, 0, 1, "=A1+1")
        workbook = input(workbook, active.id, 0, 2, "=B1+1")
        workbook = input(workbook, active.id, 5, 5, "=40+2")
        val engine = CalculationEngine(workbook)
        val before = engine.instrumentation().evaluatedCellCount
        engine.commitInput(active.id, 0, 0, CellInput.Number("2"))
        case(174, engine.instrumentation().evaluatedCellCount - before < workbook.activeSheet().cells.size)
        val scrollEngine = CalculationEngine(workbook); val scrollBefore = scrollEngine.instrumentation().formulasRecalculated; scrollEngine.recordViewport(200_000, viewport.composedCellCount)
        case(175, scrollEngine.instrumentation().formulasRecalculated == scrollBefore)
        val selection = TableSelectionController(); val payloadBefore = CalculationWorkbookJson.encode(workbook); selection.selectCell(GridAddress(2, 2))
        case(176, CalculationWorkbookJson.encode(workbook) == payloadBefore)
        val exactly100k = CalculationSheet(rowCount = 100_000, columnCount = 2, rows = List(100_000) { CalculationRow() }, columns = List(2) { CalculationColumn() })
        var allowed = CalculationWorkbook(sheets = listOf(exactly100k), activeSheetId = exactly100k.id)
        allowed = input(allowed, exactly100k.id, 0, 1, "=COUNT(A1:A100000)")
        case(177, CalculationEngine(allowed).displayAt(exactly100k.id, 0, 1) == "0")
        engine.recordViewport(2_600, 120); val metrics = engine.instrumentation()
        case(178, metrics.parsedFormulaCount > 0 && metrics.dependencyEdgesAdded > 0 && metrics.viewportCellsRequested > 0 && metrics.viewportCellsComposed > 0)
        assertEquals((169..178).toSet(), exercised)
    }
}
