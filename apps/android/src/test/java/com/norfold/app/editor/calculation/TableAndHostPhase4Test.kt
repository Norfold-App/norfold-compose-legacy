package com.norfold.app.editor.calculation

import com.norfold.app.domain.BlockDocument
import com.norfold.app.domain.BlockDocumentJson
import com.norfold.app.domain.DocumentPageBlock
import com.norfold.app.domain.InlineText
import com.norfold.app.domain.SceneGeometry
import com.norfold.app.domain.SceneObject
import com.norfold.app.domain.SceneObjectKind
import com.norfold.app.domain.TableAlignment
import com.norfold.app.domain.TableBlock
import com.norfold.app.domain.TableCell
import com.norfold.app.domain.UnknownBlock
import com.norfold.app.domain.findById
import com.norfold.app.domain.withFreshIds
import com.norfold.app.domain.style.ColorValue
import com.norfold.app.editor.document.DocumentHostRuntime
import com.norfold.app.editor.document.DocumentSceneController
import com.norfold.app.editor.document.LayoutRepresentation
import com.norfold.app.editor.document.WorkingIntent
import com.norfold.app.editor.flow.FlowCommands
import com.norfold.app.editor.flow.FlowCommandScope
import com.norfold.app.editor.flow.FlowEditController
import com.norfold.app.editor.flow.FlowHostRuntime
import com.norfold.app.editor.flow.FlowTableCursor
import com.norfold.app.editor.history.EditTarget
import com.norfold.app.editor.presentation.FormFactor
import com.norfold.app.editor.command.CommandResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TableAndHostPhase4Test {
    private fun legacyTable(id: String = "table"): TableBlock = TableBlock(
        id = id,
        headers = listOf(TableCell(listOf(InlineText("Name"))), TableCell(listOf(InlineText("Value")))),
        rows = listOf(
            listOf(TableCell(listOf(InlineText("Alpha"))), TableCell(listOf(InlineText("10")))),
            listOf(TableCell(listOf(InlineText("Beta"))), TableCell(listOf(InlineText("20")))),
        ),
        columnWidthsDp = listOf(140f, 90f),
        columnAlignments = listOf(TableAlignment.Start, TableAlignment.End),
    )

    @Test fun legacyDecodeAndEncodingRemainCompatibleBeforeUpgrade() {
        val table = legacyTable()
        val encoded = BlockDocumentJson.encode(BlockDocument(listOf(table)))
        assertFalse(encoded.contains("calculationPayload"))
        val restored = BlockDocumentJson.decode(encoded).blocks.single() as TableBlock
        assertEquals(table, restored)
        assertNull(restored.calculationPayload)
        assertEquals("Name\tValue\nAlpha\t10\nBeta\t20", restored.plainText())
    }

    @Test fun copyOnWriteUpgradePreservesLegacyPreimageWidthsAlignmentsAndIsIdempotent() {
        val original = legacyTable()
        val upgraded = TableCalculationBridge.upgrade(original) as TableUpgradeResult.Success
        assertNotNull(upgraded.block.calculationPayload)
        assertEquals(original.headers, upgraded.block.legacyRecovery?.headers)
        assertEquals(original.rows, upgraded.block.legacyRecovery?.rows)
        assertEquals(original.columnWidthsDp, upgraded.block.legacyRecovery?.columnWidthsDp)
        assertEquals(original.columnAlignments, upgraded.block.legacyRecovery?.columnAlignments)
        val repeated = TableCalculationBridge.upgrade(upgraded.block) as TableUpgradeResult.Success
        assertEquals(upgraded.block, repeated.block)
        assertEquals(original, TableCalculationBridge.restoreLegacy(upgraded.block))
    }

    @Test fun duplicateLegacyHeadersUpgradeWithoutDataLoss() {
        val duplicate = legacyTable().copy(headers = listOf(TableCell(listOf(InlineText("Value"))), TableCell(listOf(InlineText("Value")))))
        val upgraded = TableCalculationBridge.upgrade(duplicate) as TableUpgradeResult.Success
        val columns = upgraded.view.workbook.embeddedTables.single().columns.map { it.name }
        assertEquals(2, columns.distinct().size)
        assertEquals(duplicate.headers, upgraded.block.legacyRecovery?.headers)
    }

    @Test fun fullTypedPayloadRoundTripsAndDerivedCacheIsDiscardable() {
        val upgraded = TableCalculationBridge.upgrade(legacyTable()) as TableUpgradeResult.Success
        var workbook = upgraded.view.workbook
        val sheet = workbook.activeSheet()
        val engine = CalculationEngine(workbook)
        assertTrue(engine.commitInput(sheet.id, 1, 1, CellInput.Number("5")).accepted)
        assertTrue(engine.commitInput(sheet.id, 2, 1, CellInput.Formula("=B2*2")).accepted)
        workbook = engine.workbook().copy(
            namedRanges = listOf(NamedRange(name = "Values", target = StableCellRange(sheet.coordinateAt(1, 1)!!, sheet.coordinateAt(2, 1)!!))),
        )
        val configuredSheet = workbook.activeSheet().copy(
            validations = listOf(ValidationRule(target = StableCellRange(sheet.coordinateAt(1, 1)!!, sheet.coordinateAt(2, 1)!!), condition = ValidationCondition.NumberRange("0", "100"))),
            conditionalFormats = listOf(ConditionalFormatRule(
                target = StableCellRange(sheet.coordinateAt(1, 1)!!, sheet.coordinateAt(2, 1)!!),
                condition = ConditionalCondition.Comparison(ComparisonOperator.GREATER, "7"),
                priority = 1,
                formatDelta = CellFormat(fillColor = ColorValue(255, 220, 100)),
                accessibleDescription = "Values above seven are highlighted",
            )),
            mergedRegions = listOf(MergedRegion(range = StableCellRange(sheet.coordinateAt(1, 0)!!, sheet.coordinateAt(1, 1)!!))),
        )
        workbook = workbook.withSheet(configuredSheet)
        val persisted = TableCalculationBridge.persist(upgraded.block, workbook, upgraded.view.tableId)
        val document = BlockDocument(listOf(persisted, UnknownBlock("future", """{"kind":"future","id":"future","raw":"keep"}""")))
        val restored = BlockDocumentJson.decode(BlockDocumentJson.encode(document))
        val restoredTable = restored.blocks.first() as TableBlock
        val restoredWorkbook = TableCalculationBridge.view(restoredTable).workbook
        assertEquals(workbook, restoredWorkbook)
        assertEquals("10", CalculationEngine(restoredWorkbook).displayAt(sheet.id, 2, 1))
        assertEquals((document.blocks[1] as UnknownBlock).rawJson, (restored.blocks[1] as UnknownBlock).rawJson)
    }

    @Test fun selectionStateMachineReachesEveryStateAndEscapeUnwindsHierarchy() {
        val controller = TableSelectionController()
        controller.hover(GridAddress(0, 0)); assertTrue(controller.state is TableSelectionState.Hovered)
        controller.selectCell(GridAddress(0, 0)); assertTrue(controller.state is TableSelectionState.CellSelected)
        controller.edit(GridAddress(0, 0), "draft"); assertTrue(controller.state is TableSelectionState.Editing)
        assertTrue(controller.stepBack()); assertTrue(controller.state is TableSelectionState.CellSelected)
        controller.extendTo(GridAddress(2, 2)); assertTrue(controller.state is TableSelectionState.RangeSelected)
        controller.selectRow(2); assertTrue(controller.state is TableSelectionState.RowSelected)
        controller.selectColumn(3); assertTrue(controller.state is TableSelectionState.ColumnSelected)
        controller.selectWholeTable(); assertEquals(TableSelectionState.WholeTable, controller.state)
        assertTrue(controller.stepBack()); assertEquals(TableSelectionState.Inactive, controller.state)
        assertFalse(controller.stepBack())
    }

    @Test fun cancelledRangeDragRestoresBaseline_andSelectedAddressesAreHonest() {
        val controller = TableSelectionController()
        controller.selectCell(GridAddress(1, 1))
        controller.beginRangeDrag(GridAddress(1, 1))
        controller.updateRangeDrag(GridAddress(3, 2))
        assertTrue(controller.state is TableSelectionState.RangeSelected)
        controller.cancelRangeDrag()
        assertEquals(TableSelectionState.CellSelected(GridAddress(1, 1)), controller.state)
        controller.extendTo(GridAddress(2, 2))
        assertEquals(4, controller.selectedAddresses(CalculationSheet()).size)
    }

    @Test fun mergeSplitNeverDeletesUnderlyingCellRecords() {
        val sheet = CalculationSheet()
        var workbook = CalculationWorkbook(sheets = listOf(sheet), activeSheetId = sheet.id)
        val engine = CalculationEngine(workbook)
        engine.commitInput(sheet.id, 0, 0, CellInput.Text("A"))
        engine.commitInput(sheet.id, 0, 1, CellInput.Text("B"))
        workbook = engine.workbook()
        val before = workbook.activeSheet().cells
        val range = StableCellRange(sheet.coordinateAt(0, 0)!!, sheet.coordinateAt(0, 1)!!)
        workbook = CalculationMutations.merge(workbook, range)
        assertEquals(before, workbook.activeSheet().cells)
        val id = workbook.activeSheet().mergedRegions.single().id
        workbook = CalculationMutations.split(workbook, id)
        assertEquals(before, workbook.activeSheet().cells)
        assertTrue(workbook.activeSheet().mergedRegions.isEmpty())
    }

    @Test fun pasteValidationReportsPerCellAndRuntimeUndoRestoresExactGrid() {
        val block = SpreadsheetWorkbookFactory.createBlock()
        val runtime = SpreadsheetHostRuntime(EditTarget("sheet:paste"), BlockDocument(listOf(block)))
        runtime.select(GridAddress(0, 0))
        val before = runtime.state().workbook
        val report = runtime.paste("1\t2\n3\t4")
        assertEquals(4, report.acceptedCount)
        assertNotEquals(before, runtime.state().workbook)
        assertEquals(1, runtime.controller.undoDepth())
        assertTrue(runtime.undo())
        assertEquals(before, runtime.state().workbook)
        assertTrue(runtime.redo())
        assertEquals("4", CalculationEngine(runtime.state().workbook).displayAt(runtime.state().activeSheet.id, 1, 1))
    }

    @Test fun fillUsesRelativeFormulaReferencesAndSeriesSupportsNumbersAndDates() {
        val block = SpreadsheetWorkbookFactory.createBlock()
        val runtime = SpreadsheetHostRuntime(EditTarget("sheet:fill"), BlockDocument(listOf(block)))
        runtime.select(GridAddress(0, 0)); runtime.commitFormulaBar("1")
        runtime.select(GridAddress(0, 1)); runtime.commitFormulaBar("=A1+1")
        assertTrue(runtime.fillTo(listOf(GridAddress(1, 1), GridAddress(2, 1))))
        val sheet = runtime.state().activeSheet
        assertEquals("=A2+1", (sheet.cellAt(1, 1)?.input as CellInput.Formula).source)
        assertEquals("=A3+1", (sheet.cellAt(2, 1)?.input as CellInput.Formula).source)
        val series = CalculationMutations.fillSeries(runtime.state().workbook, sheet.id, listOf(GridAddress(3, 0), GridAddress(4, 0)), CellInput.Number("10"), java.math.BigDecimal("2"))
        assertEquals("12", series.activeSheet().cellAt(4, 0)?.input?.editorText())
    }

    @Test fun typedSortIsStableAndFilterHidesWithoutDeleting() {
        val sheet = CalculationSheet()
        var workbook = CalculationWorkbook(sheets = listOf(sheet), activeSheetId = sheet.id)
        val engine = CalculationEngine(workbook)
        listOf("Header", "10", "2", "10").forEachIndexed { row, value -> engine.commitInput(sheet.id, row, 0, CellInputParser.parse(value)) }
        workbook = engine.workbook()
        val range = StableCellRange(sheet.coordinateAt(0, 0)!!, sheet.coordinateAt(3, 0)!!)
        workbook = CalculationMutations.sort(workbook, sheet.id, range, sheet.columns[0].id, SortDirection.ASCENDING)
        val sorted = CalculationEngine(workbook)
        assertEquals(listOf("Header", "2", "10", "10"), (0..3).map { sorted.displayAt(sheet.id, it, 0) })
        val beforeCells = workbook.activeSheet().cells
        workbook = CalculationMutations.filter(workbook, sheet.id, range, sheet.columns[0].id, FilterCondition.Values(listOf("10")))
        // Filtering hides rows; it never removes a cell. The cell list must be byte-identical.
        assertEquals(beforeCells, workbook.activeSheet().cells)
        // Sorted order is Header, 2, 10, 10 — so the "2" row hides and both "10" rows stay, and the
        // header is never hidden by a header-aware filter.
        assertFalse(workbook.activeSheet().rows[0].hidden)
        assertTrue(workbook.activeSheet().rows[1].hidden)
        assertFalse(workbook.activeSheet().rows[2].hidden)
        assertFalse(workbook.activeSheet().rows[3].hidden)
        // Clearing restores every row without touching data.
        val cleared = CalculationMutations.clearFilters(workbook, sheet.id)
        assertTrue(cleared.activeSheet().rows.none { it.hidden })
        assertEquals(beforeCells, cleared.activeSheet().cells)
    }

    @Test fun viewportPlansBoundedTwoDimensionalCompositionWithFrozenPanes() {
        val sheet = CalculationSheet(rowCount = 1_000, columnCount = 200, rows = List(1_000) { CalculationRow() }, columns = List(200) { CalculationColumn() }, frozenRows = 1, frozenColumns = 1)
        val viewport = SpreadsheetViewportPlanner.plan(sheet, 10_000f, 5_000f, 600f, 800f)
        assertTrue(viewport.lastRow - viewport.firstRow < 50)
        assertTrue(viewport.lastColumn - viewport.firstColumn < 20)
        assertTrue(viewport.composedCellCount < 1_000)
        assertEquals(1, viewport.frozenRows)
        assertEquals(1, viewport.frozenColumns)
        assertEquals(0, sheet.cells.size)
    }

    @Test fun rowColumnReorderAndResizeAreSingleRuntimeTransactionsAndReferencesFollowStableCells() {
        val runtime = SpreadsheetHostRuntime(EditTarget("sheet:axes"), BlockDocument(listOf(SpreadsheetWorkbookFactory.createBlock())))
        runtime.select(GridAddress(0, 0)); runtime.commitFormulaBar("5")
        runtime.select(GridAddress(1, 1)); runtime.commitFormulaBar("=A1")
        val beforeMoveDepth = runtime.controller.undoDepth()
        runtime.select(GridAddress(0, 0)); assertTrue(runtime.reorderSelectedRow(2))
        assertEquals(beforeMoveDepth + 1, runtime.controller.undoDepth())
        assertEquals("=A3", (runtime.state().activeSheet.cellAt(0, 1)?.input as CellInput.Formula).source)
        runtime.select(GridAddress(2, 0)); assertTrue(runtime.reorderSelectedColumn(2))
        assertEquals("=C3", (runtime.state().activeSheet.cellAt(0, 0)?.input as CellInput.Formula).source)
        val resizeDepth = runtime.controller.undoDepth()
        runtime.select(GridAddress(0, 2)); assertTrue(runtime.setSelectedColumnWidth(180f))
        assertEquals(resizeDepth + 1, runtime.controller.undoDepth())
        assertEquals(180f, runtime.state().activeSheet.columns[2].widthPoints)
        assertTrue(runtime.setSelectedRowHeight(72f))
        assertEquals(72f, runtime.state().activeSheet.rows[0].heightPoints)
    }

    @Test fun mixedFormattingTableStyleInheritanceAndLocalOverridesAreHonest() {
        val block = SpreadsheetWorkbookFactory.createBlock()
        val runtime = SpreadsheetHostRuntime(EditTarget("sheet:format"), BlockDocument(listOf(block)))
        runtime.select(GridAddress(0, 0)); runtime.applyFormat(CellFormat(bold = true))
        runtime.select(GridAddress(0, 0)); runtime.extendSelection(0, 1)
        assertTrue(runtime.state().mixedFormat.mixed)
        runtime.select(GridAddress(0, 0)); assertTrue(runtime.applyBuiltInTableStyle())
        val workbook = runtime.state().workbook
        val table = workbook.embeddedTables.single()
        // The spreadsheet grid is deliberately headerRow = false, so row 0 is body + first-column emphasis,
        // not a header band. Asserting header colours here would demand a header the table does not declare.
        assertFalse("the spreadsheet grid must not claim a header row", table.headerRow)
        val firstColumn = TableStyleResolver.effectiveCellFormat(workbook, table, 0, 0, CellFormat())
        assertEquals("first-column emphasis comes from the style, not the cell", true, firstColumn.bold)
        val plainBody = TableStyleResolver.effectiveCellFormat(workbook, table, 0, 3, CellFormat())
        assertEquals("an interior body cell inherits no emphasis", null, plainBody.bold)
        // Banded rows are derived from the style definition and never written into any cell.
        val banded = TableStyleResolver.effectiveCellFormat(workbook, table, 1, 3, CellFormat())
        assertEquals(ColorValue(239, 235, 255), banded.fillColor)
        assertEquals("banding must not be materialised as durable cell formatting", null, workbook.activeSheet().cellAt(1, 3)?.format?.fillColor)

        // Header semantics themselves, on a table that actually declares a header row.
        val headed = workbook.copy(embeddedTables = listOf(table.copy(headerRow = true)))
        val header = TableStyleResolver.effectiveCellFormat(headed, headed.embeddedTables.single(), 0, 0, CellFormat())
        assertEquals(true, header.bold)
        assertEquals(ColorValue(255, 255, 255), header.textColor)

        // A local override wins over the style, and changing the style definition afterwards does not erase it.
        runtime.select(GridAddress(0, 0))
        runtime.applyFormat(CellFormat(fillColor = ColorValue(1, 2, 3)))
        val afterStyleEdit = runtime.state().workbook
        val local = afterStyleEdit.activeSheet().cellAt(0, 0)?.format ?: CellFormat()
        assertEquals(ColorValue(1, 2, 3), local.fillColor)
        assertEquals(ColorValue(1, 2, 3), TableStyleResolver.effectiveCellFormat(afterStyleEdit, afterStyleEdit.embeddedTables.single(), 0, 0, local).fillColor)
    }

    @Test fun sheetDuplicationRetainsMetadataAndHideShowKeepsOneVisibleSheet() {
        val block = SpreadsheetWorkbookFactory.createBlock()
        val runtime = SpreadsheetHostRuntime(EditTarget("sheet:duplicate"), BlockDocument(listOf(block)))
        runtime.select(GridAddress(0, 0)); runtime.commitFormulaBar("1")
        runtime.select(GridAddress(0, 0)); runtime.applyListValidation(listOf("1", "2"))
        runtime.select(GridAddress(0, 0)); runtime.applyComparisonConditional(ComparisonOperator.GREATER, "0", CellFormat(bold = true))
        runtime.select(GridAddress(0, 0)); runtime.freezeAtSelection()
        assertTrue(runtime.duplicateActiveSheet())
        val duplicate = runtime.state().activeSheet
        assertEquals(1, duplicate.validations.size)
        assertEquals(1, duplicate.conditionalFormats.size)
        assertTrue(runtime.setActiveSheetHidden(true))
        assertFalse(runtime.state().activeSheet.hidden)
        assertFalse(runtime.setActiveSheetHidden(true))
    }

    @Test fun numberFormatsDoNotMutateStoredValuesAndColorScaleIsDerivedWithTextMeaning() {
        val block = SpreadsheetWorkbookFactory.createBlock()
        val runtime = SpreadsheetHostRuntime(EditTarget("sheet:number-format"), BlockDocument(listOf(block)))
        runtime.select(GridAddress(0, 0)); runtime.commitFormulaBar("0.5")
        runtime.setNumberFormat(CellNumberFormat(NumberFormatKind.PERCENTAGE))
        val workbook = runtime.state().workbook
        assertEquals("0.5", (workbook.activeSheet().cellAt(0, 0)?.input as CellInput.Number).canonical)
        assertEquals("50%", CalculationEngine(workbook).displayAt(workbook.activeSheetId, 0, 0))
        val sheet = workbook.activeSheet()
        val target = StableCellRange(sheet.coordinateAt(0, 0)!!, sheet.coordinateAt(1, 0)!!)
        val configured = workbook.withSheet(sheet.copy(conditionalFormats = listOf(
            ConditionalFormatRule(
                target = target,
                condition = ConditionalCondition.ColorScale(ColorValue(255, 255, 255), ColorValue(255, 255, 0), ColorValue(255, 0, 0)),
                priority = 1,
                accessibleDescription = "Low to high value scale",
            ),
        )))
        val presentation = CalculationEngine(configured).conditionalFormat(sheet.id, 0, 0)
        assertNotNull(presentation.format.fillColor)
        assertTrue(presentation.descriptions.single().contains("positioned on the range colour scale"))
        assertTrue("the scale must announce the value, not only the colour", presentation.descriptions.single().contains("Low to high value scale"))
        assertEquals(null, configured.activeSheet().cellAt(0, 0)?.format?.fillColor)
    }

    @Test fun validationWarningRequiresASecondIdenticalCommit() {
        val block = SpreadsheetWorkbookFactory.createBlock()
        val original = TableCalculationBridge.view(block)
        val sheet = original.workbook.activeSheet()
        val target = StableCellRange(sheet.coordinateAt(0, 0)!!, sheet.coordinateAt(0, 0)!!)
        val configured = original.workbook.withSheet(sheet.copy(validations = listOf(
            ValidationRule(target = target, condition = ValidationCondition.AllowedValues(listOf("safe")), policy = ValidationPolicy.WARN, errorMessage = "Unusual value"),
        )))
        val persisted = TableCalculationBridge.persist(block, configured, original.tableId)
        val runtime = SpreadsheetHostRuntime(EditTarget("sheet:warn"), BlockDocument(listOf(persisted)))
        runtime.select(GridAddress(0, 0))
        val first = runtime.commitFormulaBar("other")
        assertFalse(first.accepted)
        assertTrue(first.requiresConfirmation)
        assertEquals("", runtime.state().formulaBarText)
        val second = runtime.commitFormulaBar("other")
        assertTrue(second.accepted)
        assertEquals("other", runtime.state().formulaBarText)
    }

    @Test fun preferredEmbeddedTableIdentityIsEditedWithoutCopyingSiblingData() {
        val first = legacyTable().copy(id = "first")
        val second = legacyTable().copy(id = "second")
        val document = BlockDocument(listOf(first, second))
        val runtime = SpreadsheetHostRuntime(EditTarget("sheet:preferred"), document, tableBlockId = second.id)
        runtime.select(GridAddress(1, 1)); runtime.commitFormulaBar("77")
        val savedFirst = runtime.controller.document().findById(first.id) as TableBlock
        val savedSecond = runtime.controller.document().findById(second.id) as TableBlock
        assertNull(savedFirst.calculationPayload)
        assertNotNull(savedSecond.calculationPayload)
        assertEquals("77", TableCalculationBridge.editorText(savedSecond, 0, 1))
    }

    @Test fun viewportInstrumentationReportsRequestedAndBoundedComposedCounts() {
        val sheet = CalculationSheet(rowCount = 1_000, columnCount = 200, rows = List(1_000) { CalculationRow() }, columns = List(200) { CalculationColumn() })
        val engine = CalculationEngine(CalculationWorkbook(sheets = listOf(sheet), activeSheetId = sheet.id))
        val plan = SpreadsheetViewportPlanner.plan(sheet, 0f, 0f, 600f, 800f)
        engine.recordViewport(200_000, plan.composedCellCount)
        val metrics = engine.instrumentation()
        assertEquals(200_000L, metrics.viewportCellsRequested)
        assertTrue(metrics.viewportCellsComposed < 1_000)
    }

    @Test fun directionalFlowCommandsKeepIdsUseEngineSelectInsertedTargetAndSnapshotLegacy() {
        val table = legacyTable()
        val controller = FlowEditController(EditTarget("note:flow-table"), BlockDocument(listOf(table)))
        val scope = FlowCommandScope(controller)
        scope.selectBlocks(listOf(table.id))
        scope.tableCursor = FlowTableCursor(table.id, 0, 0)
        val registry = FlowCommands.registry(scope)
        assertNotNull(registry.get(SpreadsheetCommandIds.INSERT_ROW_ABOVE))
        assertNotNull(registry.get(SpreadsheetCommandIds.INSERT_ROW_BELOW))
        assertNotNull(registry.get(SpreadsheetCommandIds.INSERT_COLUMN_LEFT))
        assertNotNull(registry.get(SpreadsheetCommandIds.INSERT_COLUMN_RIGHT))
        assertTrue(registry.execute(SpreadsheetCommandIds.INSERT_ROW_BELOW, scope.commandContext()) is CommandResult.Success)
        val changed = controller.document().findById(table.id) as TableBlock
        assertNotNull(changed.calculationPayload)
        assertEquals(1, controller.recoverySnapshots().size)
        assertEquals(1, scope.tableCursor?.row)
        assertEquals(1, controller.undoDepth())
        assertTrue(registry.execute(SpreadsheetCommandIds.INSERT_ROW_BELOW, scope.commandContext()) is CommandResult.Success)
        assertEquals(2, scope.tableCursor?.row)
        assertEquals(2, controller.undoDepth())
        assertEquals(1, controller.recoverySnapshots().size)
    }

    @Test fun flowAndSpreadsheetUseTheSamePersistedIdentityAndCalculatedDisplay() {
        val table = legacyTable()
        val flow = FlowHostRuntime(FlowEditController(EditTarget("note:shared"), BlockDocument(listOf(table))), FormFactor(400, 800))
        val result = flow.editTableCell(FlowTableCursor(table.id, 0, 1), "=10+5")
        assertTrue(result.accepted)
        val afterFlow = flow.controller.document()
        val spreadsheet = SpreadsheetHostRuntime(EditTarget("note:shared-reopen"), afterFlow)
        val workbookId = spreadsheet.state().workbook.id
        spreadsheet.select(GridAddress(1, 1))
        assertEquals("=10+5", spreadsheet.state().formulaBarText)
        spreadsheet.commitFormulaBar("=20+5")
        val afterSpreadsheet = spreadsheet.controller.document().findById(table.id) as TableBlock
        val reopened = TableCalculationBridge.view(afterSpreadsheet)
        assertEquals(workbookId, reopened.workbook.id)
        assertEquals("25", CalculationEngine(reopened.workbook).displayAt(reopened.workbook.activeSheetId, 1, 1))
    }

    @Test fun documentTableCommitsOnlyInEditIntentAndReadModeRefuses() {
        val table = legacyTable()
        val objectId = "object"
        val page = DocumentPageBlock(
            id = "page",
            objects = listOf(SceneObject(id = objectId, kind = SceneObjectKind.Table, geometry = SceneGeometry(0f, 0f, 300f, 180f), content = table)),
        ).normalized()
        val runtime = DocumentHostRuntime(DocumentSceneController(EditTarget("doc:table"), BlockDocument(listOf(page))), FormFactor(800, 1_000))
        runtime.scope.activePageId = page.id
        runtime.setRepresentation(LayoutRepresentation.ORIGINAL)
        runtime.setIntent(WorkingIntent.READ)
        assertFalse(runtime.editObjectTableCell(objectId, 0, 1, "=1+1").accepted)
        runtime.setIntent(WorkingIntent.EDIT)
        assertTrue(runtime.beginContentEdit(objectId))
        assertTrue(runtime.editObjectTableCell(objectId, 0, 1, "=1+1").accepted)
        val changed = runtime.controller.findObject(objectId)?.second?.content as TableBlock
        assertNotNull(changed.calculationPayload)
        assertEquals(1, runtime.controller.recoverySnapshots().size)
    }

    @Test fun spreadsheetHostIsDistinctSupportsSheetsFormulaBarCommandsFreezeAndOfflineReopen() {
        val block = SpreadsheetWorkbookFactory.createBlock("Budget")
        val runtime = SpreadsheetHostRuntime(EditTarget("sheet:host"), BlockDocument(listOf(block)))
        runtime.select(GridAddress(0, 0)); assertTrue(runtime.commitFormulaBar("7").accepted)
        assertTrue(runtime.addSheet())
        val second = runtime.state().workbook.activeSheetId
        runtime.select(GridAddress(0, 0)); assertTrue(runtime.commitFormulaBar("=Sheet1!A1*2").accepted)
        assertEquals("14", CalculationEngine(runtime.state().workbook).displayAt(second, 0, 0))
        runtime.select(GridAddress(2, 2)); assertTrue(runtime.freezeAtSelection())
        assertEquals(2, runtime.state().activeSheet.frozenRows)
        assertEquals(2, runtime.state().activeSheet.frozenColumns)
        // §32: every semantic action must be reachable through the shared registry, not only through a
        // direct UI callback — otherwise a keyboard chord, a dock button and TalkBack are three
        // implementations rather than three routes into one command. A count assertion would pass while
        // the wrong commands were registered, so the ids themselves are named.
        val required = listOf(
            SpreadsheetCommandIds.INSERT_ROW_ABOVE, SpreadsheetCommandIds.INSERT_ROW_BELOW,
            SpreadsheetCommandIds.INSERT_COLUMN_LEFT, SpreadsheetCommandIds.INSERT_COLUMN_RIGHT,
            SpreadsheetCommandIds.DELETE_ROW, SpreadsheetCommandIds.DELETE_COLUMN,
            SpreadsheetCommandIds.MOVE_ROW_EARLIER, SpreadsheetCommandIds.MOVE_COLUMN_EARLIER,
            SpreadsheetCommandIds.FIT_ROW_HEIGHT, SpreadsheetCommandIds.BEGIN_EDIT,
            SpreadsheetCommandIds.COMMIT_EDIT, SpreadsheetCommandIds.CANCEL_EDIT,
            SpreadsheetCommandIds.SELECT_ROW, SpreadsheetCommandIds.SELECT_COLUMN,
            SpreadsheetCommandIds.SELECT_WHOLE_TABLE, SpreadsheetCommandIds.EXTEND_SELECTION_RIGHT,
            SpreadsheetCommandIds.EXTEND_SELECTION_DOWN, SpreadsheetCommandIds.CLEAR_CELLS,
            SpreadsheetCommandIds.MERGE, SpreadsheetCommandIds.SPLIT, SpreadsheetCommandIds.FREEZE,
            SpreadsheetCommandIds.SORT_ASCENDING, SpreadsheetCommandIds.SORT_DESCENDING,
            SpreadsheetCommandIds.CLEAR_FILTERS, SpreadsheetCommandIds.OPEN_FILL_STUDIO,
            SpreadsheetCommandIds.OPEN_ADVANCED_TOOLS, SpreadsheetCommandIds.ADD_SHEET,
            SpreadsheetCommandIds.DUPLICATE_SHEET, SpreadsheetCommandIds.DELETE_SHEET,
            SpreadsheetCommandIds.MOVE_SHEET_EARLIER, SpreadsheetCommandIds.MOVE_SHEET_LATER,
            SpreadsheetCommandIds.APPLY_TABLE_STYLE, SpreadsheetCommandIds.TOGGLE_BOLD,
            SpreadsheetCommandIds.UNDO, SpreadsheetCommandIds.REDO,
        )
        val absent = required.filter { runtime.commandRegistry.get(it) == null }
        assertTrue("semantic commands missing from the registry: $absent", absent.isEmpty())
        // And invoking through the registry genuinely mutates, rather than merely resolving.
        runtime.select(GridAddress(4, 4))
        val rowsBefore = runtime.state().activeSheet.rowCount
        assertTrue(runtime.invoke(SpreadsheetCommandIds.INSERT_ROW_ABOVE) is CommandResult.Success)
        assertEquals(rowsBefore + 1, runtime.state().activeSheet.rowCount)
        val payload = BlockDocumentJson.encode(runtime.controller.document())
        val reopened = SpreadsheetHostRuntime(EditTarget("sheet:host-reopen"), BlockDocumentJson.decode(payload))
        assertEquals("14", CalculationEngine(reopened.state().workbook).displayAt(second, 0, 0))
    }

    /**
     * A corrupt typed payload opens read-only and can never be overwritten.
     *
     * The defect this pins is the worst possible one: a malformed payload that crashes on open, "recovered"
     * by writing a blank workbook over the user's only copy. So `view` must produce something renderable,
     * and `upgrade` must *refuse* — the bytes stay on disk for a later repair.
     */
    @Test fun aCorruptTypedPayloadIsViewableButCanNeverBeOverwritten() {
        val legacy = legacyTable()
        val corrupt = legacy.copy(
            calculationPayload = "{\"sheets\":[ this is not json",
            calculationTableId = "missing-table",
            calculationSchemaVersion = TableCalculationBridge.SCHEMA_VERSION,
        )
        // Viewable: the legacy projection still renders every cell the user typed.
        val view = TableCalculationBridge.view(corrupt)
        assertFalse("a corrupt payload must not claim to be an upgraded workbook", view.upgraded)
        assertEquals("Alpha", CalculationEngine(view.workbook).displayAt(view.workbook.activeSheetId, 1, 0))
        // Not editable: the refusal is what protects the bytes.
        val refused = TableCalculationBridge.upgrade(corrupt)
        assertTrue("editing a corrupt payload must be refused", refused is TableUpgradeResult.Failure)
        // And a payload whose identity is simply absent from an otherwise valid workbook is refused too.
        val valid = TableCalculationBridge.upgrade(legacy) as TableUpgradeResult.Success
        val mismatched = valid.block.copy(calculationTableId = "not-in-this-workbook")
        assertTrue(TableCalculationBridge.upgrade(mismatched) is TableUpgradeResult.Failure)
        assertFalse(TableCalculationBridge.view(mismatched).upgraded)
    }

    /**
     * A duplicated table block is an independent workbook even though its payload ids are unchanged.
     *
     * Duplication refreshes the *block* id only. That is safe precisely because each block decodes its own
     * payload into its own object graph, so identical internal ids never collide — this test is the proof,
     * because if they were ever shared, editing the copy would move the original's value too.
     */
    @Test fun duplicatingATableBlockIsolatesItsWorkbookWithoutReidentifyingThePayload() {
        val source = TableCalculationBridge.upgrade(legacyTable("source")) as TableUpgradeResult.Success
        val duplicate = source.block.withFreshIds() as TableBlock
        assertNotEquals("the block identity must be fresh", source.block.id, duplicate.id)
        assertEquals(
            "the payload is copied verbatim; re-identifying it would have to rewrite every formula",
            source.block.calculationPayload,
            duplicate.calculationPayload,
        )

        val document = BlockDocument(listOf(source.block, duplicate))
        val runtime = FlowHostRuntime(FlowEditController(EditTarget("dup:isolation"), document), FormFactor(400, 800))
        assertTrue(runtime.editTableCell(FlowTableCursor(duplicate.id, 0, 1), "999").accepted)

        val editedCopy = runtime.controller.document().findById(duplicate.id) as TableBlock
        val untouched = runtime.controller.document().findById(source.block.id) as TableBlock
        assertEquals("999", TableCalculationBridge.editorText(editedCopy, 0, 1))
        assertEquals("the source table must not move when its duplicate is edited", "10", TableCalculationBridge.editorText(untouched, 0, 1))
        assertEquals(source.block.calculationPayload, untouched.calculationPayload)
    }

    /**
     * A headerless table must not gain a phantom leading row when it enters the calculation model.
     *
     * The legacy projection used to prepend `block.headers` unconditionally, so a table with no headers got an
     * empty row 0 while its `headerRow` flag stayed false. Every legacy row index then resolved one row too
     * high: typing into data row 1 overwrote data row 0, `=A1` addressed a blank instead of the first value,
     * and the first save grew the table by a row the user never typed.
     */
    @Test fun aHeaderlessTableKeepsItsRowIndicesWhenProjectedIntoTheCalculationModel() {
        val headerless = TableBlock(
            id = "headerless",
            rows = listOf(
                listOf(TableCell(listOf(InlineText("4")))),
                listOf(TableCell(listOf(InlineText("")))),
            ),
        )
        val view = TableCalculationBridge.view(headerless)
        assertEquals("no header row is declared", false, view.workbook.embeddedTables.single().headerRow)
        assertEquals("two rows in, two rows out", 2, view.workbook.activeSheet().rowCount)
        assertEquals("the first typed value stays at row 0", "4", TableCalculationBridge.editorText(headerless, 0, 0))

        val controller = FlowEditController(EditTarget("headerless:rows"), BlockDocument(listOf(headerless)))
        val runtime = FlowHostRuntime(controller, FormFactor(400, 800))
        assertTrue(runtime.editTableCell(FlowTableCursor(headerless.id, 1, 0), "=A1*3").accepted)

        val stored = controller.document().blocks.single() as TableBlock
        assertEquals("the table must not grow a row", 2, stored.rows.size)
        assertTrue("the header projection stays empty", stored.headers.isEmpty())
        assertEquals("the addressed cell is the one that changed", "4", TableCalculationBridge.editorText(stored, 0, 0))
        val storedView = TableCalculationBridge.view(stored)
        assertEquals(
            "the formula resolves against the real first row",
            java.math.BigDecimal(12),
            (CalculationEngine(storedView.workbook).valueAt(storedView.workbook.activeSheetId, 1, 0) as CalculatedValue.Number).value,
        )
    }

    /**
     * The active sheet is never a hidden sheet, in either direction.
     *
     * The grid renders the active sheet, so an active-and-hidden sheet is a state where the model says "not
     * shown" and the screen shows it anyway. Hiding the active sheet moves activation away; activating a hidden
     * sheet reveals it. Both happen in one transaction, so undo restores sheet and flag together.
     */
    @Test fun activatingAHiddenSheetRevealsItSoTheActiveSheetIsNeverHidden() {
        val runtime = SpreadsheetHostRuntime(EditTarget("sheets:hidden"), BlockDocument(listOf(SpreadsheetWorkbookFactory.createBlock())))
        val first = runtime.state().workbook.activeSheetId
        assertTrue(runtime.addSheet())
        val second = runtime.state().workbook.activeSheetId
        assertNotEquals(first, second)

        assertTrue("hiding the active sheet is allowed while another is visible", runtime.setActiveSheetHidden(true))
        assertEquals("activation moved off the hidden sheet", first, runtime.state().workbook.activeSheetId)
        assertTrue("the sheet really is hidden", runtime.state().workbook.sheet(second)!!.hidden)

        runtime.activateSheet(second)
        val afterActivate = runtime.state().workbook
        assertEquals("the requested sheet is active", second, afterActivate.activeSheetId)
        assertFalse("activating it revealed it rather than leaving a contradictory state", afterActivate.sheet(second)!!.hidden)
        assertTrue("no sheet is both active and hidden", afterActivate.sheets.none { it.hidden && it.id == afterActivate.activeSheetId })

        assertTrue(runtime.undo())
        val undone = runtime.state().workbook
        assertTrue("undo restored the hidden flag with the activation", undone.sheet(second)!!.hidden)
        assertNotEquals("and moved activation back off it", second, undone.activeSheetId)
    }

    /**
     * Deleting the last visible sheet reveals a survivor instead of leaving every sheet hidden.
     *
     * Without this, deleting the only visible sheet in a workbook whose others are hidden would leave an active
     * sheet the model calls hidden — the same contradiction as above, reached from the other direction.
     */
    @Test fun deletingTheLastVisibleSheetRevealsASurvivor() {
        val visible = CalculationSheet(name = "Visible")
        val hidden = CalculationSheet(name = "Hidden", hidden = true)
        val workbook = CalculationWorkbook(sheets = listOf(visible, hidden), activeSheetId = visible.id)
        val after = CalculationMutations.deleteSheet(workbook, visible.id)
        assertEquals("one sheet survives", 1, after.sheets.size)
        assertFalse("the survivor was revealed", after.sheets.single().hidden)
        assertEquals("and is active", after.sheets.single().id, after.activeSheetId)
    }

    /**
     * One cell edit is one narrow invalidation, not a whole-workbook recalculation.
     *
     * The session used to discard its engine on every persist, and the hosts used to construct a fresh engine
     * per keystroke. Either turns every edit into a full rebuild of the dependency graph plus a recalculation
     * of every formula in the workbook. This measures the engine's own counters rather than asserting on source
     * text, so it fails if that regresses however it is reintroduced.
     *
     * The window deliberately starts *after* the first write to the cell: a previously sparse coordinate has no
     * CellId for dependency edges to target, so its first durable value rebuilds the graph once by design
     * (see `CalculationEngine.commitInput`). Typing again into a cell that now exists is the narrow path, and
     * that is the case a user hits on every keystroke after the first.
     */
    @Test fun editingOneCellDoesNotRecalculateTheWholeWorkbook() {
        val runtime = SpreadsheetHostRuntime(EditTarget("perf:narrow"), BlockDocument(listOf(SpreadsheetWorkbookFactory.createBlock())))
        // Ten independent formulas, none of which depends on the cell edited afterwards.
        repeat(10) { row ->
            runtime.select(GridAddress(row, 0)); runtime.commitFormulaBar("${row + 1}")
            runtime.select(GridAddress(row, 1)); runtime.commitFormulaBar("=A${row + 1}*2")
        }
        assertTrue(
            "the host's engine must survive persistence rather than being rebuilt per edit",
            runtime.engine() === runtime.engine(),
        )
        val engineBeforeEdits = runtime.engine()

        runtime.select(GridAddress(15, 5))
        runtime.commitFormulaBar("41")
        val before = runtime.engine().instrumentation().formulasRecalculated
        runtime.select(GridAddress(15, 5))
        runtime.commitFormulaBar("42")
        val recalculated = runtime.engine().instrumentation().formulasRecalculated - before

        assertTrue(
            "the engine identity must be stable across edits, otherwise the counters below mean nothing",
            engineBeforeEdits === runtime.engine(),
        )
        assertEquals(
            "editing a cell nothing depends on recalculated $recalculated formulas; it must touch none",
            0,
            recalculated,
        )
        assertEquals(
            "and the value that was typed is the value that is stored",
            "42",
            runtime.engine().displayAt(runtime.state().activeSheet.id, 15, 5),
        )
    }

    /**
     * One formula edit is exactly one undo step, in every host.
     *
     * The failure modes on both sides are real: zero transactions means the edit is unreachable by undo, and
     * several per edit means the user presses undo, sees the cell only partly revert, and has to guess how many
     * more presses it takes. Undo *depth* is asserted, not just "canUndo", because a per-keystroke history
     * would still satisfy the weaker check.
     */
    @Test fun oneFormulaEditIsExactlyOneUndoStepInEveryHost() {
        // Flow. The legacy table upgrades on first edit, so the baseline is taken after the upgrade and the
        // measured edit is a second, ordinary one — otherwise the migration transaction would be miscounted.
        // The measured edit addresses a *different* cell on purpose: consecutive commits to one cell coalesce
        // into a single undo step by design, which is the typing contract, not a missing transaction.
        val flowTable = legacyTable("flow-one-txn")
        val flowController = FlowEditController(EditTarget("txn:flow"), BlockDocument(listOf(flowTable)))
        val flow = FlowHostRuntime(flowController, FormFactor(400, 800))
        assertTrue(flow.editTableCell(FlowTableCursor(flowTable.id, 0, 1), "1").accepted)
        val flowBaselineDepth = flowController.undoDepth()
        val flowBefore = flowController.document().blocks.single() as TableBlock

        assertTrue(flow.editTableCell(FlowTableCursor(flowTable.id, 0, 0), "=5+5").accepted)
        val flowEdited = TableCalculationBridge.view(flowController.document().blocks.single() as TableBlock)
        assertEquals("10", CalculationEngine(flowEdited.workbook).displayAt(flowEdited.workbook.activeSheetId, 1, 0))
        assertEquals("one formula edit, one transaction", flowBaselineDepth + 1, flowController.undoDepth())
        assertTrue(flowController.undo())
        assertEquals("and one undo lands exactly on the pre-edit state", flowBefore, flowController.document().blocks.single())

        // Spreadsheet.
        val sheetRuntime = SpreadsheetHostRuntime(EditTarget("txn:sheet"), BlockDocument(listOf(SpreadsheetWorkbookFactory.createBlock())))
        sheetRuntime.select(GridAddress(0, 0))
        assertTrue(sheetRuntime.commitFormulaBar("6").accepted)
        val sheetBaselineDepth = sheetRuntime.controller.undoDepth()
        val sheetBefore = sheetRuntime.state().workbook

        sheetRuntime.select(GridAddress(1, 0))
        assertTrue(sheetRuntime.commitFormulaBar("=A1*7").accepted)
        assertEquals("42", sheetRuntime.engine().displayAt(sheetRuntime.state().activeSheet.id, 1, 0))
        assertEquals("one formula edit, one transaction", sheetBaselineDepth + 1, sheetRuntime.controller.undoDepth())
        assertTrue(sheetRuntime.undo())
        assertEquals("and one undo lands exactly on the pre-edit state", sheetBefore, sheetRuntime.state().workbook)

        // Document.
        val docTable = legacyTable("doc-one-txn")
        val objectId = "obj-one-txn"
        val page = DocumentPageBlock(
            id = "page-one-txn",
            objects = listOf(
                SceneObject(id = objectId, kind = SceneObjectKind.Table, geometry = SceneGeometry(0f, 0f, 300f, 200f), content = docTable),
            ),
        ).normalized()
        val docController = DocumentSceneController(EditTarget("txn:doc"), BlockDocument(listOf(page)))
        val doc = DocumentHostRuntime(docController, FormFactor(800, 1_000))
        doc.scope.activePageId = page.id
        doc.setRepresentation(LayoutRepresentation.ORIGINAL)
        doc.setIntent(WorkingIntent.EDIT)
        assertTrue(doc.beginContentEdit(objectId))
        assertTrue(doc.editObjectTableCell(objectId, 0, 1, "1").accepted)
        val docBaselineDepth = docController.undoDepth()
        val docBefore = docController.findObject(objectId)?.second?.content as TableBlock

        assertTrue(doc.editObjectTableCell(objectId, 0, 0, "=2*11").accepted)
        val docEdited = TableCalculationBridge.view(docController.findObject(objectId)?.second?.content as TableBlock)
        assertEquals("22", CalculationEngine(docEdited.workbook).displayAt(docEdited.workbook.activeSheetId, 1, 0))
        assertEquals("one formula edit, one transaction", docBaselineDepth + 1, docController.undoDepth())
        assertTrue(docController.undo())
        assertEquals(
            "and one undo lands exactly on the pre-edit state",
            docBefore,
            docController.findObject(objectId)?.second?.content as TableBlock,
        )
    }

    @Test fun phaseFiveRangeSeamReadsCalculatedValuesWithoutCreatingChartState() {
        val block = SpreadsheetWorkbookFactory.createBlock()
        val workbook = TableCalculationBridge.view(block).workbook
        val sheet = workbook.activeSheet()
        val binding = CalculationRangeBinding(workbook.id, StableCellRange(sheet.coordinateAt(0, 0)!!, sheet.coordinateAt(1, 1)!!))
        val snapshot = CalculationRangeReader.read(workbook, binding)
        assertEquals(2, snapshot.rows.size)
        assertEquals(2, snapshot.rows.first().size)
        assertEquals(workbook.revision, snapshot.workbookRevision)
    }
}
