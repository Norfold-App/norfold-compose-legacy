package com.norfold.app.editor.calculation

import com.norfold.app.domain.BlockCursor
import com.norfold.app.domain.BlockDocument
import com.norfold.app.domain.TableBlock
import com.norfold.app.domain.walk
import com.norfold.app.domain.style.ColorDomain
import com.norfold.app.domain.style.ColorPaint
import com.norfold.app.domain.style.ColorRole
import com.norfold.app.domain.style.ColorTarget
import com.norfold.app.domain.style.ColorValue
import com.norfold.app.editor.flow.FlowEditController
import com.norfold.app.editor.history.EditTarget
import com.norfold.app.editor.style.ColorStudioEngine
import com.norfold.app.editor.style.ColorStudioHost
import com.norfold.app.editor.style.ColorStudioInteractionController
import com.norfold.app.editor.style.ColorStudioState

data class SpreadsheetHostState(
    val revision: Long,
    val workbook: CalculationWorkbook,
    val activeSheet: CalculationSheet,
    val selection: TableSelectionState,
    val formulaBarText: String,
    val canUndo: Boolean,
    val canRedo: Boolean,
    val advancedToolsVisible: Boolean,
    val mixedFormat: MixedValue<CellFormat>,
    val activeFilterCount: Int,
    val lastMessage: String? = null,
    /** Shared Phase-3 Color Studio state, so cell fill is the same colour engine both editors use. */
    val colorStudio: ColorStudioState = ColorStudioState.CLOSED,
)

object SpreadsheetWorkbookFactory {
    fun createBlock(name: String = "Workbook"): TableBlock {
        val sheet = CalculationSheet(name = "Sheet1")
        val range = StableCellRange(sheet.coordinateAt(0, 0)!!, sheet.coordinateAt(19, 7)!!)
        val table = EmbeddedTableDefinition(
            name = "Table1",
            range = range,
            columns = sheet.columns.take(8).mapIndexed { index, column -> EmbeddedTableColumn(column.id, "Column${index + 1}") },
            headerRow = false,
        )
        val workbook = CalculationWorkbook(
            displayName = name,
            sheets = listOf(sheet),
            activeSheetId = sheet.id,
            embeddedTables = listOf(table),
        )
        val seed = TableBlock()
        val upgraded = TableCalculationBridge.upgrade(seed) as TableUpgradeResult.Success
        return TableCalculationBridge.persist(upgraded.block, workbook, table.id)
    }
}

/**
 * A distinct spreadsheet product runtime. It shares only the calculation model and canonical History
 * substrate with embedded tables; it does not wrap either FlowHost or DocumentHost.
 */
class SpreadsheetHostRuntime(
    val controller: FlowEditController,
    private val tableBlockId: String? = null,
) {
    private var revision = 0L
    private var advanced = false
    private var message: String? = null
    private val tableSession: CalculationTableSession
    val commandRegistry by lazy { SpreadsheetCommandCatalog.create(this) }

    /**
     * Cell fill through the shared Phase-3 Color Studio — not a private hex field.
     *
     * §12 requires the canonical [com.norfold.app.domain.style.ColorValue] and the same studio the other two
     * hosts use. Reusing the engine (rather than reimplementing a picker here) is what keeps one colour truth:
     * preview paints without history, and only `commit` becomes one transaction.
     */
    val colorStudio = ColorStudioEngine(object : ColorStudioHost {
        override fun preview(target: ColorTarget, paint: ColorPaint) {
            previewFill = paint
            revision++
        }

        override fun commit(target: ColorTarget, paint: ColorPaint, label: String): Boolean {
            previewFill = null
            // A cell fill is a flat colour: the representative of a ramp is the honest single value, and a
            // gradient is not offered for this role in the first place.
            return applyFormat(CellFormat(fillColor = paint.representative()))
        }
    })

    /** Continuous preview paint for the addressed cells; never durable formatting. */
    var previewFill: ColorPaint? = null
        private set

    /** The same preview/commit action boundary Flow and Document use, so one gesture is one transaction. */
    val colorInteractions = ColorStudioInteractionController(colorStudio)

    init {
        val existing = controller.document().firstTableBlock(tableBlockId)
            ?: error("Spreadsheet artifact requires one TableBlock root")
        tableSession = CalculationTableSession(
            initialBlock = existing,
            beforeLegacyUpgrade = { controller.snapshotBeforeMigration("Upgrade spreadsheet artifact") },
            commitBlock = { label, before, after ->
                val committed = controller.mutate(label) { session ->
                    session.replaceBlock(after)
                    BlockCursor(after.id, 0)
                } != null
                if (committed) revision++
                committed
            },
        )
    }

    constructor(target: EditTarget, document: BlockDocument, tableBlockId: String? = null) : this(FlowEditController(target, document), tableBlockId)

    /**
     * The one live calculation engine for this host.
     *
     * The UI renders through *this* engine rather than constructing its own from the workbook. A second
     * engine keyed on the workbook would rebuild the whole dependency graph and recalculate every formula on
     * each edit — the exact full-workbook-per-one-cell-edit cost the incremental graph exists to avoid — and
     * would also drop the instrumentation counters that prove recalculation stayed narrow.
     */
    fun engine(): CalculationEngine = tableSession.engine()

    fun state(): SpreadsheetHostState {
        val workbook = tableSession.workbook()
        val sheet = workbook.activeSheet()
        val addresses = tableSession.selection.selectedAddresses(sheet)
        return SpreadsheetHostState(
            revision = revision,
            workbook = workbook,
            activeSheet = sheet,
            selection = tableSession.selection.state,
            formulaBarText = selectedCell()?.let { sheet.cellAt(it.row, it.column)?.input?.editorText().orEmpty() }.orEmpty(),
            canUndo = controller.canUndo(),
            canRedo = controller.canRedo(),
            advancedToolsVisible = advanced,
            mixedFormat = workbook.mixedFormat(sheet.id, addresses),
            activeFilterCount = sheet.filters.count(FilterDefinition::enabled),
            lastMessage = message,
            colorStudio = colorStudio.state(),
        )
    }

    /**
     * Opens Color Studio on the addressed cells' fill.
     *
     * The initial paint is the *common* fill or MIXED — never the first cell's colour presented as if it
     * were the whole selection's (§50).
     */
    fun openCellFillStudio(): Boolean {
        val workbook = tableSession.workbook()
        val sheet = workbook.activeSheet()
        val addresses = tableSession.selection.selectedAddresses(sheet)
        if (addresses.isEmpty()) return false
        val fills = addresses.map { sheet.cellAt(it.row, it.column)?.format?.fillColor }
        val common = fills.distinct().singleOrNull()
        colorStudio.open(
            ColorTarget(
                domain = ColorDomain.CONTENT,
                role = ColorRole.FILL,
                targetIds = addresses.map { it.a1() },
                host = "spreadsheet",
            ),
            ColorPaint.Flat(common ?: ColorValue.WHITE),
            mixed = common == null,
        )
        revision++
        return true
    }

    fun closeCellFillStudio() {
        colorStudio.cancelActiveAdjustment()
        colorStudio.close()
        previewFill = null
        revision++
    }

    fun invoke(commandId: String): com.norfold.app.editor.command.CommandResult = commandRegistry.execute(
        commandId,
        com.norfold.app.editor.command.CommandContext(
            offeredCapabilities = setOf(com.norfold.app.editor.command.Capability.TABLE_STRUCTURE),
            targetCount = if (tableSession.selection.state == TableSelectionState.Inactive) 0 else 1,
            hasPrimary = tableSession.selection.state != TableSelectionState.Inactive,
        ),
    )

    fun select(cell: GridAddress, extend: Boolean = false) {
        if (extend) tableSession.selection.extendTo(cell) else tableSession.selection.selectCell(cell)
        revision++
    }

    fun selectRow(row: Int) { tableSession.selection.selectRow(row); revision++ }
    fun selectColumn(column: Int) { tableSession.selection.selectColumn(column); revision++ }
    fun selectAll() { tableSession.selection.selectWholeTable(); revision++ }

    /** The selected row's address index, or null when the selection does not identify one. */
    fun selectedRowIndex(): Int? = when (val selected = tableSession.selection.state) {
        is TableSelectionState.RowSelected -> selected.row
        is TableSelectionState.CellSelected -> selected.cell.row
        is TableSelectionState.Editing -> selected.cell.row
        is TableSelectionState.RangeSelected -> selected.focus.row
        else -> null
    }

    /** The selected column's address index, or null when the selection does not identify one. */
    fun selectedColumnIndex(): Int? = when (val selected = tableSession.selection.state) {
        is TableSelectionState.ColumnSelected -> selected.column
        is TableSelectionState.CellSelected -> selected.cell.column
        is TableSelectionState.Editing -> selected.cell.column
        is TableSelectionState.RangeSelected -> selected.focus.column
        else -> null
    }
    fun extendSelection(rowDelta: Int, columnDelta: Int): Boolean = moveSelection(rowDelta, columnDelta, extend = true)
    fun beginEdit(draft: String? = null) {
        val cell = selectedCell() ?: return
        tableSession.selection.edit(cell, draft ?: tableSession.workbook().activeSheet().cellAt(cell.row, cell.column)?.input?.editorText().orEmpty())
        revision++
    }

    fun moveSelection(rowDelta: Int, columnDelta: Int, extend: Boolean = false): Boolean {
        val current = selectedCell() ?: GridAddress(0, 0)
        val sheet = tableSession.workbook().activeSheet()
        val next = GridAddress(
            (current.row + rowDelta).coerceIn(0, sheet.rowCount - 1),
            (current.column + columnDelta).coerceIn(0, sheet.columnCount - 1),
        )
        select(next, extend)
        return true
    }

    fun clearSelection(): Boolean {
        val workbook = tableSession.workbook()
        val addresses = tableSession.selection.selectedAddresses(workbook.activeSheet())
        if (addresses.isEmpty()) return false
        val engine = CalculationEngine(workbook)
        engine.commitBatch(workbook.activeSheetId, addresses.map { CellMutation(it.row, it.column, CellInput.Blank) })
        return tableSession.replaceWorkbook("Clear cells", engine.workbook()).also { if (it) revision++ }
    }

    fun handleShortcut(shortcut: SpreadsheetShortcut): Boolean = when (
        val action = SpreadsheetKeyboardMap.resolve(shortcut, tableSession.selection.state is TableSelectionState.Editing)
    ) {
        is SpreadsheetKeyAction.Move -> moveSelection(action.rowDelta, action.columnDelta, action.extend)
        SpreadsheetKeyAction.BeginOrCommitEdit -> {
            val editing = tableSession.selection.state as? TableSelectionState.Editing
            if (editing != null) commitFormulaBar(editing.draft).accepted else { beginEdit(); true }
        }
        SpreadsheetKeyAction.Clear -> clearSelection()
        SpreadsheetKeyAction.Back -> back()
        SpreadsheetKeyAction.Undo -> undo()
        SpreadsheetKeyAction.Redo -> redo()
        SpreadsheetKeyAction.Unhandled -> false
    }

    fun updateDraft(value: String) { tableSession.selection.updateDraft(value); revision++ }

    /**
     * Commits the draft currently being typed into a cell.
     *
     * Distinct from [commitFormulaBar]: this is the in-cell editor's own commit, so a command route (dock
     * button, keyboard chord, accessibility action) writes the text the user is actually looking at rather
     * than whatever the formula bar last showed.
     */
    fun commitActiveEdit(): Boolean {
        val editing = tableSession.selection.state as? TableSelectionState.Editing ?: return false
        return commitFormulaBar(editing.draft).accepted
    }

    fun commitFormulaBar(value: String): CellCommitResult {
        val cell = selectedCell() ?: return CellCommitResult(false, error = CalculationError(CalculationErrorKind.REFERENCE, "Select a cell first"))
        val result = tableSession.commitCell(cell.row, cell.column, CellInputParser.parse(value))
        if (result.accepted) tableSession.selection.selectCell(cell)
        message = result.error?.detail ?: result.warning
        revision++
        return result
    }

    fun insertRowAbove(): Boolean = insertRow(offset = 0)
    fun insertRowBelow(): Boolean = insertRow(offset = 1)
    fun insertColumnLeft(): Boolean = insertColumn(offset = 0)
    fun insertColumnRight(): Boolean = insertColumn(offset = 1)

    fun deleteSelectedRow(): Boolean {
        val cell = selectedCell() ?: return false
        val sheet = tableSession.workbook().activeSheet()
        val active = tableSession.transform("Delete row") { CalculationMutations.deleteRows(it, sheet.id, cell.row) } ?: return false
        val next = tableSession.workbook().activeSheet()
        tableSession.selection.selectCell(GridAddress(next.rowIndex(active.rowId), cell.column.coerceAtMost(next.columnCount - 1)))
        revision++
        return true
    }

    fun deleteSelectedColumn(): Boolean {
        val cell = selectedCell() ?: return false
        val sheet = tableSession.workbook().activeSheet()
        val active = tableSession.transform("Delete column") { CalculationMutations.deleteColumns(it, sheet.id, cell.column) } ?: return false
        val next = tableSession.workbook().activeSheet()
        tableSession.selection.selectCell(GridAddress(cell.row.coerceAtMost(next.rowCount - 1), next.columnIndex(active.columnId)))
        revision++
        return true
    }

    private fun insertRow(offset: Int): Boolean {
        val cell = selectedCell() ?: return false
        val sheetId = tableSession.workbook().activeSheetId
        val active = tableSession.transform(if (offset == 0) "Insert row above" else "Insert row below") {
            CalculationMutations.insertRow(it, sheetId, cell.row + offset)
        } ?: return false
        val sheet = tableSession.workbook().sheet(active.sheetId) ?: return false
        tableSession.selection.selectCell(GridAddress(sheet.rowIndex(active.rowId), cell.column))
        revision++
        return true
    }

    private fun insertColumn(offset: Int): Boolean {
        val cell = selectedCell() ?: return false
        val sheetId = tableSession.workbook().activeSheetId
        val active = tableSession.transform(if (offset == 0) "Insert column left" else "Insert column right") {
            CalculationMutations.insertColumn(it, sheetId, cell.column + offset)
        } ?: return false
        val sheet = tableSession.workbook().sheet(active.sheetId) ?: return false
        tableSession.selection.selectCell(GridAddress(cell.row, sheet.columnIndex(active.columnId)))
        revision++
        return true
    }

    fun addSheet(): Boolean = replace("Add sheet") { CalculationMutations.addSheet(it) }
    fun renameActiveSheet(name: String): Boolean = runCatching {
        replace("Rename sheet") { CalculationMutations.renameSheet(it, it.activeSheetId, name) }
    }.getOrElse { message = it.message ?: "Could not rename sheet"; revision++; false }
    fun reorderActiveSheet(index: Int): Boolean = replace("Reorder sheet") { CalculationMutations.reorderSheet(it, it.activeSheetId, index) }
    fun duplicateActiveSheet(): Boolean = replace("Duplicate sheet") { CalculationMutations.duplicateSheet(it, it.activeSheetId) }
    fun deleteActiveSheet(): Boolean = replace("Delete sheet") { CalculationMutations.deleteSheet(it, it.activeSheetId) }
    fun setActiveSheetHidden(hidden: Boolean): Boolean = replace(if (hidden) "Hide sheet" else "Show sheet") {
        CalculationMutations.setSheetHidden(it, it.activeSheetId, hidden)
    }

    /**
     * Activates a sheet, revealing it first when it is hidden.
     *
     * The workbook invariant this protects is that the active sheet is never a hidden sheet: the grid renders
     * the active sheet, so a hidden-and-active sheet would be a state where the model says "not shown" and the
     * screen shows it anyway. Hiding the active sheet already moves activation to the first visible sheet
     * (`CalculationMutations.setSheetHidden`); this is the other direction. Revealing and activating happen in
     * one transaction so undo returns to the previous sheet *and* its hidden flag together.
     */
    fun activateSheet(id: SheetId) {
        val workbook = tableSession.workbook()
        val target = workbook.sheet(id) ?: return
        val revealed = if (target.hidden) CalculationMutations.setSheetHidden(workbook, id, false) else workbook
        val label = if (target.hidden) "Show sheet" else "Switch sheet"
        tableSession.replaceWorkbook(label, revealed.copy(activeSheetId = id))
        tableSession.selection.selectCell(GridAddress(0, 0))
        revision++
    }

    fun paste(text: String): PasteReport {
        val cell = selectedCell() ?: GridAddress(0, 0)
        val workbook = tableSession.workbook()
        val report = CalculationMutations.pasteGrid(workbook, workbook.activeSheetId, cell.row, cell.column, text)
        tableSession.replaceWorkbook("Paste cells", report.workbook)
        message = when {
            report.rejectedCount > 0 -> "Pasted ${report.acceptedCount}; ${report.rejectedCount} values were refused by validation"
            else -> "Pasted ${report.acceptedCount} cells"
        }
        revision++
        return report
    }

    fun copySelection(): String {
        val workbook = tableSession.workbook()
        val sheet = workbook.activeSheet()
        val addresses = tableSession.selection.selectedAddresses(sheet)
        if (addresses.isEmpty()) return ""
        val rows = addresses.map(GridAddress::row).distinct().sorted()
        val columns = addresses.map(GridAddress::column).distinct().sorted()
        return rows.joinToString("\n") { row ->
            columns.joinToString("\t") { column -> sheet.cellAt(row, column)?.input?.editorText().orEmpty() }
        }
    }

    fun cutSelection(): String {
        val copied = copySelection()
        if (copied.isNotEmpty()) clearSelection()
        return copied
    }

    fun fillTo(targets: List<GridAddress>): Boolean {
        val source = selectedCell() ?: return false
        val workbook = tableSession.workbook()
        return tableSession.replaceWorkbook("Fill cells", CalculationMutations.fill(workbook, workbook.activeSheetId, source, targets)).also { if (it) revision++ }
    }

    fun fillSeriesTo(targets: List<GridAddress>, step: java.math.BigDecimal): Boolean {
        val source = selectedCell() ?: return false
        val workbook = tableSession.workbook()
        val input = workbook.activeSheet().cellAt(source.row, source.column)?.input ?: return false
        return tableSession.replaceWorkbook(
            "Fill series",
            CalculationMutations.fillSeries(workbook, workbook.activeSheetId, targets, input, step),
        ).also { if (it) revision++ }
    }

    fun applyFormat(format: CellFormat): Boolean {
        val workbook = tableSession.workbook()
        val addresses = tableSession.selection.selectedAddresses(workbook.activeSheet())
        if (addresses.isEmpty()) return false
        return tableSession.replaceWorkbook("Format cells", CalculationMutations.applyFormat(workbook, workbook.activeSheetId, addresses, format)).also { if (it) revision++ }
    }

    fun setNumberFormat(format: CellNumberFormat): Boolean {
        val workbook = tableSession.workbook()
        val addresses = tableSession.selection.selectedAddresses(workbook.activeSheet())
        if (addresses.isEmpty()) return false
        return tableSession.replaceWorkbook(
            "Set number format",
            CalculationMutations.setNumberFormat(workbook, workbook.activeSheetId, addresses, format),
        ).also { if (it) revision++ }
    }

    fun applyBuiltInTableStyle(): Boolean {
        val workbook = tableSession.workbook()
        val sheet = workbook.activeSheet()
        val cell = selectedCell() ?: return false
        val coordinate = sheet.coordinateAt(cell.row, cell.column) ?: return false
        val table = workbook.embeddedTables.firstOrNull { definition ->
            val startRow = sheet.rowIndex(definition.range.start.rowId)
            val endRow = sheet.rowIndex(definition.range.end.rowId)
            val startColumn = sheet.columnIndex(definition.range.start.columnId)
            val endColumn = sheet.columnIndex(definition.range.end.columnId)
            definition.range.start.sheetId == sheet.id && cell.row in minOf(startRow, endRow)..maxOf(startRow, endRow) && cell.column in minOf(startColumn, endColumn)..maxOf(startColumn, endColumn)
        } ?: return false
        val style = TableStyleDefinition(
            name = "Norfold Accent",
            header = CellFormat(bold = true, fillColor = com.norfold.app.domain.style.ColorValue(91, 68, 176), textColor = com.norfold.app.domain.style.ColorValue(255, 255, 255)),
            body = CellFormat(),
            firstColumn = CellFormat(bold = true),
            bandedRows = true,
            bandedRowFormat = CellFormat(fillColor = com.norfold.app.domain.style.ColorValue(239, 235, 255)),
        )
        return replace("Apply table style") { CalculationMutations.applyTableStyle(it, table.id, style) }
    }

    fun mergeSelection(): Boolean {
        val range = selectedStableRange() ?: return false
        return replace("Merge cells") { CalculationMutations.merge(it, range) }
    }

    fun splitSelection(): Boolean {
        val workbook = tableSession.workbook()
        val sheet = workbook.activeSheet()
        val cell = selectedCell() ?: return false
        val region = sheet.mergedRegions.firstOrNull { region ->
            val startRow = sheet.rowIndex(region.range.start.rowId)
            val endRow = sheet.rowIndex(region.range.end.rowId)
            val startColumn = sheet.columnIndex(region.range.start.columnId)
            val endColumn = sheet.columnIndex(region.range.end.columnId)
            cell.row in minOf(startRow, endRow)..maxOf(startRow, endRow) && cell.column in minOf(startColumn, endColumn)..maxOf(startColumn, endColumn)
        } ?: return false
        return replace("Split cells") { CalculationMutations.split(it, region.id) }
    }

    fun freezeAtSelection(): Boolean {
        val cell = selectedCell() ?: return false
        return replace("Freeze panes") { CalculationMutations.freeze(it, it.activeSheetId, cell.row, cell.column) }
    }

    fun reorderSelectedRow(delta: Int): Boolean {
        val sheet = tableSession.workbook().activeSheet()
        val row = selectedCell()?.row ?: return false
        val target = (row + delta).coerceIn(0, sheet.rowCount - 1)
        val active = tableSession.transform("Reorder row") { CalculationMutations.reorderRow(it, sheet.id, row, target) } ?: return false
        val next = tableSession.workbook().activeSheet()
        tableSession.selection.selectCell(GridAddress(next.rowIndex(active.rowId), selectedCell()?.column ?: 0))
        revision++
        return true
    }

    fun reorderSelectedColumn(delta: Int): Boolean {
        val sheet = tableSession.workbook().activeSheet()
        val column = selectedCell()?.column ?: return false
        val target = (column + delta).coerceIn(0, sheet.columnCount - 1)
        val active = tableSession.transform("Reorder column") { CalculationMutations.reorderColumn(it, sheet.id, column, target) } ?: return false
        val next = tableSession.workbook().activeSheet()
        tableSession.selection.selectCell(GridAddress(selectedCell()?.row ?: 0, next.columnIndex(active.columnId)))
        revision++
        return true
    }

    fun setSelectedColumnWidth(widthPoints: Float): Boolean {
        if (!widthPoints.isFinite() || widthPoints !in 16f..2_000f) { message = "Column width must be between 16 and 2000"; revision++; return false }
        val column = selectedCell()?.column ?: return false
        return replace("Set column width") { CalculationMutations.resizeColumn(it, it.activeSheetId, column, widthPoints) }
    }

    fun setSelectedRowHeight(heightPoints: Float): Boolean {
        if (!heightPoints.isFinite() || heightPoints !in 12f..2_000f) { message = "Row height must be between 12 and 2000"; revision++; return false }
        val row = selectedCell()?.row ?: return false
        return replace("Set row height") { CalculationMutations.resizeRow(it, it.activeSheetId, row, heightPoints) }
    }

    fun fitSelectedRowHeight(): Boolean {
        val row = selectedCell()?.row ?: return false
        return replace("Fit row height") { CalculationMutations.fitRowHeight(it, it.activeSheetId, row) }
    }

    fun defineNamedRange(name: String): Boolean {
        val range = selectedStableRange() ?: return false
        if (!isValidCanonicalName(name) || tableSession.workbook().namedRanges.any { it.name.equals(name, true) }) return false
        return replace("Define named range") { workbook ->
            workbook.copy(namedRanges = workbook.namedRanges + NamedRange(name = name, target = range), revision = workbook.revision + 1)
        }
    }

    fun applyListValidation(values: List<String>, warnOnly: Boolean = false): Boolean {
        val range = selectedStableRange() ?: return false
        val cleaned = values.map(String::trim).filter(String::isNotEmpty).distinct()
        if (cleaned.isEmpty()) return false
        return replace("Set validation") { workbook ->
            val sheet = workbook.activeSheet()
            workbook.withSheet(
                sheet.copy(
                    validations = sheet.validations + ValidationRule(
                        target = range,
                        condition = ValidationCondition.AllowedValues(cleaned),
                        policy = if (warnOnly) ValidationPolicy.WARN else ValidationPolicy.REJECT,
                    ),
                ),
            )
        }
    }

    fun applyComparisonConditional(operator: ComparisonOperator, source: String, format: CellFormat): Boolean {
        val range = selectedStableRange() ?: return false
        return replace("Set conditional format") { workbook ->
            val sheet = workbook.activeSheet()
            workbook.withSheet(
                sheet.copy(
                    conditionalFormats = sheet.conditionalFormats + ConditionalFormatRule(
                        target = range,
                        condition = ConditionalCondition.Comparison(operator, source),
                        priority = (sheet.conditionalFormats.maxOfOrNull(ConditionalFormatRule::priority) ?: 0) + 1,
                        formatDelta = format,
                        accessibleDescription = "Values ${operator.name.lowercase().replace('_', ' ')} $source",
                    ),
                ),
            )
        }
    }

    fun sortSelection(direction: SortDirection): Boolean {
        val range = selectedStableRange() ?: return false
        val sheet = tableSession.workbook().activeSheet()
        val key = selectedCell()?.let { sheet.column(it.column)?.id } ?: return false
        return replace("Sort cells") { CalculationMutations.sort(it, sheet.id, range, key, direction) }
    }

    fun filterSelectionTo(values: List<String>): Boolean {
        val range = selectedStableRange() ?: return false
        val sheet = tableSession.workbook().activeSheet()
        val column = selectedCell()?.let { sheet.column(it.column)?.id } ?: return false
        return replace("Filter cells") { CalculationMutations.filter(it, sheet.id, range, column, FilterCondition.Values(values)) }
    }

    fun clearFilters(): Boolean = replace("Clear filters") { CalculationMutations.clearFilters(it, it.activeSheetId) }

    fun toggleAdvancedTools() { advanced = !advanced; revision++ }
    fun consumeMessage(): String? = message.also { message = null; revision++ }

    fun undo(): Boolean {
        val changed = controller.undo()
        if (changed) resync()
        return changed
    }

    fun redo(): Boolean {
        val changed = controller.redo()
        if (changed) resync()
        return changed
    }

    fun mergeRemote(document: BlockDocument): Boolean {
        val changed = controller.mergeRemote(document)
        if (changed) resync()
        return changed
    }

    fun back(): Boolean {
        val handled = tableSession.selection.stepBack()
        if (handled) revision++
        return handled
    }

    private fun replace(label: String, transform: (CalculationWorkbook) -> CalculationWorkbook): Boolean =
        tableSession.replaceWorkbook(label, transform(tableSession.workbook())).also { if (it) revision++ }

    private fun selectedCell(): GridAddress? = when (val selected = tableSession.selection.state) {
        is TableSelectionState.CellSelected -> selected.cell
        is TableSelectionState.Editing -> selected.cell
        is TableSelectionState.RangeSelected -> selected.focus
        is TableSelectionState.RowSelected -> GridAddress(selected.row, 0)
        is TableSelectionState.ColumnSelected -> GridAddress(0, selected.column)
        TableSelectionState.WholeTable -> GridAddress(0, 0)
        else -> null
    }

    private fun selectedStableRange(): StableCellRange? {
        val sheet = tableSession.workbook().activeSheet()
        val state = tableSession.selection.state
        val (start, end) = when (state) {
            is TableSelectionState.RangeSelected -> state.anchor to state.focus
            is TableSelectionState.CellSelected -> state.cell to state.cell
            else -> return null
        }
        return StableCellRange(
            sheet.coordinateAt(start.row, start.column) ?: return null,
            sheet.coordinateAt(end.row, end.column) ?: return null,
        )
    }

    private fun resync() {
        val block = controller.document().firstTableBlock(tableBlockId) ?: return
        tableSession.replaceFromOwner(block)
        revision++
    }
}

private fun BlockDocument.firstTableBlock(preferredId: String? = null): TableBlock? {
    var found: TableBlock? = null
    walk { block ->
        if (block is TableBlock && (preferredId == null || block.id == preferredId) && found == null) found = block
    }
    return found
}
