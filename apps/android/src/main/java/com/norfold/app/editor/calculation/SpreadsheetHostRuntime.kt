package com.norfold.app.editor.calculation

import com.norfold.app.domain.BlockCursor
import com.norfold.app.domain.BlockDocument
import com.norfold.app.domain.TableBlock
import com.norfold.app.domain.ChartBlock
import com.norfold.app.domain.walk
import com.norfold.app.domain.findById
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
import com.norfold.app.editor.chart.ChartBlockAdapter
import com.norfold.app.editor.chart.ChartCalculationDataProvider
import com.norfold.app.editor.chart.ChartDataSource
import com.norfold.app.editor.chart.ChartDocument

/**
 * One defined name as a management surface should show it.
 *
 * [resolvable] is carried rather than inferred from [location] because a name whose sheet or cells were
 * deleted must be *visibly* broken and still removable — hiding it would leave `#NAME?` errors in formulas
 * with nothing in the UI to explain or clear them.
 */
data class NamedRangeSummary(
    val id: NamedRangeId,
    val name: String,
    val location: String,
    val resolvable: Boolean,
)

/** One validation rule as a management surface should show it. */
data class ValidationRuleSummary(
    val id: ValidationRuleId,
    /** What the rule allows, in words — the colour-free half of "why was my value refused". */
    val description: String,
    val location: String,
    val warnOnly: Boolean,
    val allowBlank: Boolean,
)

/** One conditional-format rule as a management surface should show it, in evaluation order. */
data class ConditionalRuleSummary(
    val id: ConditionalRuleId,
    val description: String,
    val location: String,
    val priority: Int,
    val enabled: Boolean,
    val stopIfTrue: Boolean,
)

data class SpreadsheetHostState(
    val revision: Long,
    val workbook: CalculationWorkbook,
    val activeSheet: CalculationSheet,
    val selection: TableSelectionState,
    val formulaBarText: String,
    /** True when [formulaBarText] is an uncommitted draft rather than the stored value. */
    val formulaBarDirty: Boolean = false,
    /** Scroll position of the active sheet in points, as `vertical to horizontal`. */
    val viewportOffset: Pair<Float, Float> = 0f to 0f,
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

    /**
     * The last Norfold copy or cut, as structure rather than text.
     *
     * Host-scoped rather than global: it lives exactly as long as the open spreadsheet, which is the same
     * lifetime as the workbook its stable ids refer to.
     */
    private var clipboard: SpreadsheetClipboard? = null

    /**
     * The uncommitted formula-bar text, or null when the bar is showing the committed value.
     *
     * Runtime-owned for the same reason the cell draft is: a `remember` keyed on the selected address is
     * discarded by any recomposition the surface cannot predict — a reflow, a theme change, a configuration
     * change — and the bar then re-seeds from the *committed* value, silently throwing away a formula the
     * user was halfway through typing.
     */
    private var formulaBarDraft: String? = null

    /** Scroll position in points, per sheet. Losing it on a reflow loses the user's place in a large grid. */
    private val viewportOffsets = linkedMapOf<SheetId, Pair<Float, Float>>()
    private val tableSession: CalculationTableSession
    private val chartRanges = CalculationRangeRuntime()
    val commandRegistry by lazy { SpreadsheetCommandCatalog.create(this) }

    fun selectedChartBinding(): CalculationRangeBinding? = selectedStableRange()?.let { range ->
        CalculationRangeBinding(tableSession.workbook().id, range, includeHeaders = true)
    }

    fun chartCalculationProvider(): ChartCalculationDataProvider = ChartCalculationDataProvider { binding ->
        chartRanges.read(tableSession.workbook(), binding)
    }

    /** Creates a durable chart beside the workbook through the owner's canonical history. */
    fun createChartFromSelection(): String? {
        val binding = selectedChartBinding() ?: return null
        val chart = ChartDocument(source = ChartDataSource.CalculationRange(binding))
        val block = ChartBlockAdapter.save(ChartBlock(), chart)
        val anchor = tableBlockId ?: controller.document().blocks.filterIsInstance<TableBlock>().firstOrNull()?.id ?: return null
        val inserted = controller.mutate("Create chart from selection") { session -> session.insertAfter(anchor, block) } != null
        if (!inserted) return null
        revision++
        return block.id
    }

    fun updateChart(blockId: String, document: ChartDocument): Boolean {
        val before = controller.document().findById(blockId) as? ChartBlock ?: return false
        val after = ChartBlockAdapter.save(before, document)
        val changed = controller.mutate("Edit chart") { session ->
            session.replaceBlock(after)
            BlockCursor(blockId, 0)
        } != null
        if (changed) revision++
        return changed
    }

    /** Rebinds an existing chart to the current stable selection without copying workbook values. */
    fun rebindChartToSelection(blockId: String): Boolean {
        val binding = selectedChartBinding() ?: return false
        val block = controller.document().findById(blockId) as? ChartBlock ?: return false
        val loaded = ChartBlockAdapter.load(block)
        if (!loaded.isEditable) return false
        val previous = loaded.document.source as? ChartDataSource.CalculationRange
        return updateChart(
            blockId,
            loaded.document.copy(source = ChartDataSource.CalculationRange(
                binding = binding,
                headerMode = previous?.headerMode ?: com.norfold.app.editor.chart.ChartHeaderMode.AUTO,
                orientation = previous?.orientation ?: com.norfold.app.editor.chart.ChartDataOrientation.COLUMNS_AS_SERIES,
            )),
        )
    }

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
            formulaBarText = formulaBarDraft
                // While a cell is being typed into, the bar mirrors that draft. Two independent drafts for one
                // cell is how the bar and the cell come to disagree about what the user typed.
                ?: (tableSession.selection.state as? TableSelectionState.Editing)?.draft
                ?: selectedCell()?.let { sheet.cellAt(it.row, it.column)?.input?.editorText().orEmpty() }.orEmpty(),
            formulaBarDirty = formulaBarDraft != null || tableSession.selection.state is TableSelectionState.Editing,
            viewportOffset = viewportOffsets[sheet.id] ?: (0f to 0f),
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
        // The formula-bar draft belongs to the cell it was typed for. Addressing another cell abandons it
        // rather than carrying it across, which would write one cell's text into a different cell.
        if (selectedCell() != cell) formulaBarDraft = null
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
        if (result.accepted) {
            tableSession.selection.selectCell(cell)
            // Only an accepted write finishes the draft. A refusal keeps it so the user can correct the text
            // they typed rather than watching it revert to the old value.
            formulaBarDraft = null
        }
        message = result.error?.detail ?: result.warning
        revision++
        return result
    }

    /** Records a formula-bar keystroke. Does not write; [commitFormulaBar] is the only write. */
    fun updateFormulaBarDraft(value: String) {
        if (formulaBarDraft == value) return
        formulaBarDraft = value
        revision++
    }

    /** Abandons the formula-bar draft, restoring the committed value. */
    fun cancelFormulaBarDraft(): Boolean {
        if (formulaBarDraft == null) return false
        formulaBarDraft = null
        revision++
        return true
    }

    /**
     * Records the scroll position of the active sheet.
     *
     * Kept per sheet so switching tabs and coming back returns to where the user was, and kept in the runtime
     * so a reflow or configuration change does not silently scroll a large grid back to A1.
     */
    fun setViewportOffset(vertical: Float, horizontal: Float) {
        val sheetId = tableSession.workbook().activeSheetId
        val next = vertical.coerceAtLeast(0f) to horizontal.coerceAtLeast(0f)
        if (viewportOffsets[sheetId] == next) return
        viewportOffsets[sheetId] = next
        revision++
    }

    /** The active sheet's scroll position in points, as `vertical to horizontal`. */
    fun viewportOffset(): Pair<Float, Float> = viewportOffsets[tableSession.workbook().activeSheetId] ?: (0f to 0f)

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
    fun deleteActiveSheet(): Boolean {
        // The workbook here is the document's own table block, so the sheet it lives on cannot be deleted:
        // deleting it would destroy the very data being edited. Say so rather than letting the mutation no-op.
        val workbook = tableSession.workbook()
        if (workbook.embeddedTables.any { it.range.start.sheetId == workbook.activeSheetId }) {
            return refuse("This sheet contains the table you are editing and cannot be deleted")
        }
        return replace("Delete sheet") { CalculationMutations.deleteSheet(it, it.activeSheetId) }
    }
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

    /**
     * Pastes at the selection.
     *
     * [text] is whatever the *system* clipboard holds. When it still matches what this host last exported,
     * the structured payload is authoritative and formatting and formulas survive the round trip; when it
     * does not, the copy came from another app and the plain-text path is the honest one.
     */
    fun paste(text: String): PasteReport {
        val cell = selectedCell() ?: GridAddress(0, 0)
        val workbook = tableSession.workbook()
        val internal = clipboard?.takeIf { it.exportedText == text }
        val report = if (internal != null) {
            CalculationMutations.pasteStructured(workbook, workbook.activeSheetId, cell.row, cell.column, internal)
        } else {
            CalculationMutations.pasteGrid(workbook, workbook.activeSheetId, cell.row, cell.column, text)
        }
        tableSession.replaceWorkbook("Paste cells", report.workbook)
        message = when {
            report.rejectedCount > 0 -> "Pasted ${report.acceptedCount}; ${report.rejectedCount} values were refused by validation"
            else -> "Pasted ${report.acceptedCount} cells"
        }
        revision++
        return report
    }

    /**
     * Captures the selection into the internal clipboard and returns the text for the system clipboard.
     *
     * Both, not either: the text keeps copy/paste working with every other app, and the structured payload is
     * what carries number format, fill, borders and alignment to a paste inside Norfold. Returning only text
     * — which is what this did — silently dropped all of it.
     */
    fun copySelection(): String {
        val workbook = tableSession.workbook()
        val sheet = workbook.activeSheet()
        val addresses = tableSession.selection.selectedAddresses(sheet)
        if (addresses.isEmpty()) { clipboard = null; return "" }
        val rows = addresses.map(GridAddress::row).distinct().sorted()
        val columns = addresses.map(GridAddress::column).distinct().sorted()
        val origin = GridAddress(rows.first(), columns.first())
        val text = rows.joinToString("\n") { row ->
            columns.joinToString("\t") { column -> sheet.cellAt(row, column)?.input?.editorText().orEmpty() }
        }
        clipboard = SpreadsheetClipboard(
            cells = rows.flatMap { row ->
                columns.map { column ->
                    val stored = sheet.cellAt(row, column)
                    ClipboardCell(
                        rowOffset = row - origin.row,
                        columnOffset = column - origin.column,
                        input = stored?.input ?: CellInput.Blank,
                        format = stored?.format ?: CellFormat(),
                    )
                }
            },
            rowCount = rows.size,
            columnCount = columns.size,
            sheetId = sheet.id,
            origin = origin,
            cut = false,
            exportedText = text,
        )
        return text
    }

    fun cutSelection(): String {
        val copied = copySelection()
        if (copied.isNotEmpty()) {
            clearSelection()
            clipboard = clipboard?.copy(cut = true)
        }
        return copied
    }

    /** What the internal clipboard is holding, or null. Exposed for the paste route and for tests. */
    fun internalClipboard(): SpreadsheetClipboard? = clipboard

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

    /**
     * Defines a name for the selected range, or refuses and says why.
     *
     * Refusals used to be a silent `false`, which reads as the button being broken. Each branch now leaves a
     * [message] so "Name range" either works or explains itself.
     */
    fun defineNamedRange(name: String): Boolean {
        val trimmed = name.trim()
        val range = selectedStableRange() ?: return refuse("Select the cells to name first")
        if (!isValidCanonicalName(trimmed)) {
            return refuse("A name must start with a letter or underscore and contain no spaces or operators")
        }
        // A name that looks like a cell address would be ambiguous in every formula that used it.
        if (tableSession.workbook().namedRanges.any { it.canonicalName == canonicalName(trimmed) }) {
            return refuse("A range named \"$trimmed\" already exists")
        }
        return replace("Define named range") { workbook ->
            workbook.copy(namedRanges = workbook.namedRanges + NamedRange(name = trimmed, target = range), revision = workbook.revision + 1)
        }.also { if (it) { message = "Named \"$trimmed\""; revision++ } }
    }

    /** Every defined name with the range it points at, for a management surface to list. */
    fun namedRanges(): List<NamedRange> = tableSession.workbook().namedRanges.sortedBy { it.canonicalName }

    /**
     * The defined names as a management surface should show them: name, where it points, and whether it still
     * resolves. Rendered here rather than in Compose so "Sheet1!A1:B4" is testable without a device, and so a
     * name whose cells were deleted is *shown* as broken instead of silently listed as if it worked.
     */
    fun namedRangeSummaries(): List<NamedRangeSummary> {
        val workbook = tableSession.workbook()
        return namedRanges().map { named ->
            val sheet = workbook.sheet(named.target.start.sheetId)
            val start = sheet?.let { s ->
                val row = s.rowIndex(named.target.start.rowId)
                val column = s.columnIndex(named.target.start.columnId)
                if (row < 0 || column < 0) null else GridAddress(row, column)
            }
            val end = sheet?.let { s ->
                val row = s.rowIndex(named.target.end.rowId)
                val column = s.columnIndex(named.target.end.columnId)
                if (row < 0 || column < 0) null else GridAddress(row, column)
            }
            if (sheet == null || start == null || end == null) {
                NamedRangeSummary(named.id, named.name, "Unresolved", resolvable = false)
            } else {
                val a1 = if (start == end) start.a1() else "${start.a1()}:${end.a1()}"
                NamedRangeSummary(named.id, named.name, "${sheet.name}!$a1", resolvable = true)
            }
        }
    }

    /**
     * Renames a defined name, rewriting every formula that referenced it.
     *
     * The rewrite is the whole point: a rename that left `=SUM(Totals)` pointing at a name that no longer
     * exists would convert working formulas into `#NAME?` errors as a side effect of tidying up.
     */
    fun renameNamedRange(id: NamedRangeId, newName: String): Boolean {
        val trimmed = newName.trim()
        val existing = tableSession.workbook().namedRanges.firstOrNull { it.id == id }
            ?: return refuse("That name no longer exists")
        if (existing.name == trimmed) return true
        if (!isValidCanonicalName(trimmed)) {
            return refuse("A name must start with a letter or underscore and contain no spaces or operators")
        }
        if (tableSession.workbook().namedRanges.any { it.id != id && it.canonicalName == canonicalName(trimmed) }) {
            return refuse("A range named \"$trimmed\" already exists")
        }
        return replace("Rename named range") { CalculationMutations.renameNamedRange(it, id, trimmed) }
            .also { if (it) { message = "Renamed to \"$trimmed\""; revision++ } }
    }

    /**
     * Deletes a defined name. Formulas that referenced it become `#NAME?` rather than silently resolving to
     * something else — a deleted name has no value, and inventing one would be worse than the error.
     */
    fun deleteNamedRange(id: NamedRangeId): Boolean {
        val existing = tableSession.workbook().namedRanges.firstOrNull { it.id == id }
            ?: return refuse("That name no longer exists")
        return replace("Delete named range") { CalculationMutations.deleteNamedRange(it, id) }
            .also { if (it) { message = "Deleted \"${existing.name}\""; revision++ } }
    }

    /** Selects the cells a defined name points at, activating its sheet first when it lives on another one. */
    fun goToNamedRange(id: NamedRangeId): Boolean {
        val workbook = tableSession.workbook()
        val named = workbook.namedRanges.firstOrNull { it.id == id } ?: return refuse("That name no longer exists")
        val sheetId = named.target.start.sheetId
        if (sheetId != workbook.activeSheetId) {
            // Navigating to a name on another sheet has to move the view, or the selection would land on
            // coordinates the user cannot see.
            if (workbook.sheet(sheetId) == null) return refuse("\"${named.name}\" points at a sheet that no longer exists")
            activateSheet(sheetId)
            if (tableSession.workbook().activeSheetId != sheetId) {
                return refuse("\"${named.name}\" points at a sheet that cannot be shown")
            }
        }
        val sheet = tableSession.workbook().activeSheet()
        val startRow = sheet.rowIndex(named.target.start.rowId)
        val startColumn = sheet.columnIndex(named.target.start.columnId)
        val endRow = sheet.rowIndex(named.target.end.rowId)
        val endColumn = sheet.columnIndex(named.target.end.columnId)
        if (startRow < 0 || startColumn < 0 || endRow < 0 || endColumn < 0) {
            return refuse("\"${named.name}\" points at cells that no longer exist")
        }
        tableSession.selection.selectCell(GridAddress(startRow, startColumn))
        if (startRow != endRow || startColumn != endColumn) tableSession.selection.extendTo(GridAddress(endRow, endColumn))
        message = null
        revision++
        return true
    }

    /** Records why a route refused, so the surface can say it rather than appearing inert. */
    private fun refuse(reason: String): Boolean {
        message = reason
        revision++
        return false
    }

    /** Clears the notice. A route, not local surface state, so the banner has one owner. */
    fun dismissMessage() {
        if (message == null) return
        message = null
        revision++
    }

    fun applyListValidation(values: List<String>, warnOnly: Boolean = false): Boolean {
        val range = selectedStableRange() ?: return refuse("Select the cells to validate first")
        val cleaned = values.map(String::trim).filter(String::isNotEmpty).distinct()
        if (cleaned.isEmpty()) return refuse("Give at least one allowed value")
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
        }.also { if (it) { message = "Validation applied to ${describeRange(range)}"; revision++ } }
    }

    fun applyComparisonConditional(operator: ComparisonOperator, source: String, format: CellFormat): Boolean {
        val range = selectedStableRange() ?: return refuse("Select the cells to highlight first")
        if (source.isBlank()) return refuse("Give a value to compare against")
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
        }.also { if (it) { message = "Highlight applied to ${describeRange(range)}"; revision++ } }
    }

    // --- Rule management -----------------------------------------------------------------------------
    //
    // Applying a rule without being able to see, edit or remove it is the same one-way door named ranges
    // were: the rule keeps rejecting input or repainting cells, and the only thing the UI offers is applying
    // another one on top. These routes are the other half.

    /** Every validation rule on the active sheet, as a management surface should show it. */
    fun validationRules(): List<ValidationRuleSummary> {
        val sheet = tableSession.workbook().activeSheet()
        return sheet.validations.map { rule ->
            ValidationRuleSummary(
                id = rule.id,
                description = describeValidation(rule.condition),
                location = describeRange(rule.target),
                warnOnly = rule.policy == ValidationPolicy.WARN,
                allowBlank = rule.allowBlank,
            )
        }
    }

    /**
     * Every conditional-format rule on the active sheet, in the order they are evaluated.
     *
     * Priority order is shown rather than insertion order because it is what decides which rule wins on a
     * cell two rules both match — the question the user is actually asking when they open this list.
     */
    fun conditionalRules(): List<ConditionalRuleSummary> {
        val sheet = tableSession.workbook().activeSheet()
        return sheet.conditionalFormats.sortedBy(ConditionalFormatRule::priority).map { rule ->
            ConditionalRuleSummary(
                id = rule.id,
                description = rule.condition.describe(),
                location = describeRange(rule.target),
                priority = rule.priority,
                enabled = rule.enabled,
                stopIfTrue = rule.stopIfTrue,
            )
        }
    }

    /**
     * Deletes a validation rule.
     *
     * Values the rule previously refused become enterable again; values already in the cells are untouched,
     * because a rule that no longer exists cannot retroactively be a reason to change stored data.
     */
    fun deleteValidationRule(id: ValidationRuleId): Boolean {
        val sheet = tableSession.workbook().activeSheet()
        if (sheet.validations.none { it.id == id }) return refuse("That validation rule no longer exists")
        return replace("Delete validation") { workbook ->
            val active = workbook.activeSheet()
            workbook.withSheet(
                active.copy(
                    validations = active.validations.filterNot { it.id == id },
                    // The per-cell links have to go with it, or a cell keeps pointing at a rule that is gone.
                    cells = active.cells.map { it.copy(validationRuleIds = it.validationRuleIds - id) },
                ),
            )
        }.also { if (it) { message = "Validation rule removed"; revision++ } }
    }

    /** Switches a validation rule between refusing input and warning about it. */
    fun setValidationWarnOnly(id: ValidationRuleId, warnOnly: Boolean): Boolean {
        val sheet = tableSession.workbook().activeSheet()
        if (sheet.validations.none { it.id == id }) return refuse("That validation rule no longer exists")
        return replace(if (warnOnly) "Warn on invalid values" else "Reject invalid values") { workbook ->
            val active = workbook.activeSheet()
            workbook.withSheet(
                active.copy(
                    validations = active.validations.map {
                        if (it.id == id) it.copy(policy = if (warnOnly) ValidationPolicy.WARN else ValidationPolicy.REJECT) else it
                    },
                ),
            )
        }.also { if (it) revision++ }
    }

    fun deleteConditionalRule(id: ConditionalRuleId): Boolean {
        val sheet = tableSession.workbook().activeSheet()
        if (sheet.conditionalFormats.none { it.id == id }) return refuse("That highlight rule no longer exists")
        return replace("Delete conditional format") { workbook ->
            val active = workbook.activeSheet()
            workbook.withSheet(active.copy(conditionalFormats = active.conditionalFormats.filterNot { it.id == id }))
        }.also { if (it) { message = "Highlight rule removed"; revision++ } }
    }

    /** Turns a conditional rule off without deleting it, so "what did this look like before" is answerable. */
    fun setConditionalRuleEnabled(id: ConditionalRuleId, enabled: Boolean): Boolean {
        val sheet = tableSession.workbook().activeSheet()
        if (sheet.conditionalFormats.none { it.id == id }) return refuse("That highlight rule no longer exists")
        return replace(if (enabled) "Enable conditional format" else "Disable conditional format") { workbook ->
            val active = workbook.activeSheet()
            workbook.withSheet(
                active.copy(conditionalFormats = active.conditionalFormats.map { if (it.id == id) it.copy(enabled = enabled) else it }),
            )
        }.also { if (it) revision++ }
    }

    /**
     * Moves a conditional rule earlier or later in evaluation order.
     *
     * Priorities are renumbered densely from 1 afterwards, so reordering repeatedly cannot drift into
     * duplicate or sparse priorities where "which rule wins" stops being decidable.
     */
    fun moveConditionalRule(id: ConditionalRuleId, delta: Int): Boolean {
        val sheet = tableSession.workbook().activeSheet()
        val ordered = sheet.conditionalFormats.sortedBy(ConditionalFormatRule::priority)
        val index = ordered.indexOfFirst { it.id == id }
        if (index < 0) return refuse("That highlight rule no longer exists")
        val target = index + delta
        if (target !in ordered.indices) return refuse("That rule is already ${if (delta < 0) "first" else "last"}")
        val reordered = ordered.toMutableList().apply { add(target, removeAt(index)) }
        return replace("Reorder conditional format") { workbook ->
            val active = workbook.activeSheet()
            workbook.withSheet(
                active.copy(conditionalFormats = reordered.mapIndexed { position, rule -> rule.copy(priority = position + 1) }),
            )
        }.also { if (it) revision++ }
    }

    /** `Sheet1!A1:B4`, or `Sheet1!A1` for a single cell. Null coordinates read as unresolved, never as A1. */
    private fun describeRange(range: StableCellRange): String {
        val workbook = tableSession.workbook()
        val sheet = workbook.sheet(range.start.sheetId) ?: return "Unresolved"
        val startRow = sheet.rowIndex(range.start.rowId)
        val startColumn = sheet.columnIndex(range.start.columnId)
        val endRow = sheet.rowIndex(range.end.rowId)
        val endColumn = sheet.columnIndex(range.end.columnId)
        if (startRow < 0 || startColumn < 0 || endRow < 0 || endColumn < 0) return "Unresolved"
        val start = GridAddress(startRow, startColumn)
        val end = GridAddress(endRow, endColumn)
        return "${sheet.name}!" + if (start == end) start.a1() else "${start.a1()}:${end.a1()}"
    }

    private fun describeValidation(condition: ValidationCondition): String = when (condition) {
        is ValidationCondition.AllowedValues -> "one of ${condition.values.joinToString(", ")}"
        is ValidationCondition.NumberRange ->
            "a number ${condition.minimum?.let { "from $it" }.orEmpty()} ${condition.maximum?.let { "up to $it" }.orEmpty()}".trim()
        is ValidationCondition.IntegerRange ->
            "a whole number ${condition.minimum?.let { "from $it" }.orEmpty()} ${condition.maximum?.let { "up to $it" }.orEmpty()}".trim()
        is ValidationCondition.DateRange ->
            "a date ${condition.minimumIso?.let { "from $it" }.orEmpty()} ${condition.maximumIso?.let { "up to $it" }.orEmpty()}".trim()
        is ValidationCondition.TextLength ->
            "text ${condition.minimum?.let { "at least $it characters" }.orEmpty()} ${condition.maximum?.let { "at most $it characters" }.orEmpty()}".trim()
        is ValidationCondition.CustomFormula -> "values where ${condition.source} is true"
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

    /**
     * Re-read the document after something other than a local edit changed it, and drop the editing state that
     * change just invalidated.
     *
     * Undo, redo and a remote merge all replace the stored value a draft was seeded from. Now that drafts live
     * here rather than in a `remember`, they survive those operations unless this says otherwise — and a draft
     * that outlives its baseline is worse than one that dies, because the bar keeps showing text the document no
     * longer agrees with and writes it on the next commit. The scroll position is deliberately *not* cleared:
     * where the user is looking is not document state, so an undo must not also teleport the grid.
     */
    private fun resync() {
        val block = controller.document().firstTableBlock(tableBlockId) ?: return
        tableSession.replaceFromOwner(block)
        formulaBarDraft = null
        (tableSession.selection.state as? TableSelectionState.Editing)?.let { tableSession.selection.selectCell(it.cell) }
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
