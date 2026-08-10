package com.norfold.app.editor.calculation

import com.norfold.app.domain.TableBlock
import kotlin.math.abs

sealed interface TableSelectionState {
    data object Inactive : TableSelectionState
    data class Hovered(val cell: GridAddress) : TableSelectionState
    data class CellSelected(val cell: GridAddress) : TableSelectionState
    data class Editing(val cell: GridAddress, val draft: String) : TableSelectionState
    data class RangeSelected(val anchor: GridAddress, val focus: GridAddress) : TableSelectionState
    data class RowSelected(val row: Int) : TableSelectionState
    data class ColumnSelected(val column: Int) : TableSelectionState
    data object WholeTable : TableSelectionState
}

data class RangeDrag(
    val baseline: TableSelectionState,
    val anchor: GridAddress,
    val current: GridAddress,
)

class TableSelectionController {
    var state: TableSelectionState = TableSelectionState.Inactive
        private set
    private var drag: RangeDrag? = null

    fun hover(cell: GridAddress?) {
        if (state is TableSelectionState.Editing || state !is TableSelectionState.Inactive && state !is TableSelectionState.Hovered) return
        state = cell?.let(TableSelectionState::Hovered) ?: TableSelectionState.Inactive
    }

    fun selectCell(cell: GridAddress) { state = TableSelectionState.CellSelected(cell); drag = null }
    fun edit(cell: GridAddress, draft: String) { state = TableSelectionState.Editing(cell, draft); drag = null }
    fun updateDraft(value: String) {
        val editing = state as? TableSelectionState.Editing ?: return
        state = editing.copy(draft = value)
    }
    fun selectRow(row: Int) { state = TableSelectionState.RowSelected(row); drag = null }
    fun selectColumn(column: Int) { state = TableSelectionState.ColumnSelected(column); drag = null }
    fun selectWholeTable() { state = TableSelectionState.WholeTable; drag = null }

    fun extendTo(cell: GridAddress) {
        val anchor = when (val current = state) {
            is TableSelectionState.CellSelected -> current.cell
            is TableSelectionState.RangeSelected -> current.anchor
            is TableSelectionState.Editing -> current.cell
            else -> cell
        }
        state = TableSelectionState.RangeSelected(anchor, cell)
    }

    fun beginRangeDrag(handle: GridAddress) {
        val anchor = when (val current = state) {
            is TableSelectionState.RangeSelected -> if (handle == current.anchor) current.focus else current.anchor
            is TableSelectionState.CellSelected -> current.cell
            else -> handle
        }
        drag = RangeDrag(state, anchor, handle)
    }

    fun updateRangeDrag(cell: GridAddress) {
        val current = drag ?: return
        drag = current.copy(current = cell)
        state = TableSelectionState.RangeSelected(current.anchor, cell)
    }

    fun finishRangeDrag() { drag = null }
    fun cancelRangeDrag() { drag?.let { state = it.baseline }; drag = null }

    /** Back/Escape: edit -> selected cell -> table object/inactive. */
    fun stepBack(): Boolean {
        state = when (val current = state) {
            is TableSelectionState.Editing -> TableSelectionState.CellSelected(current.cell)
            is TableSelectionState.RangeSelected -> TableSelectionState.CellSelected(current.focus)
            is TableSelectionState.RowSelected -> TableSelectionState.CellSelected(GridAddress(current.row, 0))
            is TableSelectionState.ColumnSelected -> TableSelectionState.CellSelected(GridAddress(0, current.column))
            is TableSelectionState.CellSelected,
            is TableSelectionState.Hovered,
            TableSelectionState.WholeTable,
            -> TableSelectionState.Inactive
            TableSelectionState.Inactive -> return false
        }
        drag = null
        return true
    }

    fun selectedAddresses(sheet: CalculationSheet): List<GridAddress> = when (val current = state) {
        is TableSelectionState.CellSelected -> listOf(current.cell)
        is TableSelectionState.Editing -> listOf(current.cell)
        is TableSelectionState.RangeSelected -> buildList {
            for (row in minOf(current.anchor.row, current.focus.row)..maxOf(current.anchor.row, current.focus.row)) {
                for (column in minOf(current.anchor.column, current.focus.column)..maxOf(current.anchor.column, current.focus.column)) {
                    if (row in 0 until sheet.rowCount && column in 0 until sheet.columnCount) add(GridAddress(row, column))
                }
            }
        }
        is TableSelectionState.RowSelected -> (0 until sheet.columnCount).map { GridAddress(current.row, it) }
        is TableSelectionState.ColumnSelected -> (0 until sheet.rowCount).map { GridAddress(it, current.column) }
        TableSelectionState.WholeTable -> buildList {
            for (row in 0 until sheet.rowCount) for (column in 0 until sheet.columnCount) add(GridAddress(row, column))
        }
        else -> emptyList()
    }

    /**
     * What the accessibility tree announces for the current selection.
     *
     * This is the *only* producer of that sentence. A host that formatted its own would drift from it
     * silently — announcements are the one output nothing else in the app reads, so a divergence produces no
     * failing test and no visible symptom, and is only ever discovered by a screen-reader user.
     *
     * A range names both ends *and* its extent: "A1 through D3" alone leaves a user who cannot see the tint
     * counting corners to learn how much is selected.
     */
    fun accessibilityDescription(sheet: CalculationSheet, engine: CalculationEngine): String = when (val current = state) {
        TableSelectionState.Inactive -> "Table not selected"
        is TableSelectionState.Hovered -> "Cell ${current.cell.a1()}"
        is TableSelectionState.CellSelected -> "Selected ${current.cell.a1()}, ${engine.displayAt(sheet.id, current.cell.row, current.cell.column).ifBlank { "blank" }}"
        is TableSelectionState.Editing -> "Editing ${current.cell.a1()}"
        is TableSelectionState.RangeSelected -> {
            val rows = abs(current.focus.row - current.anchor.row) + 1
            val columns = abs(current.focus.column - current.anchor.column) + 1
            "Selected range ${current.anchor.a1()} through ${current.focus.a1()}, " +
                "$rows row${if (rows == 1) "" else "s"} by $columns column${if (columns == 1) "" else "s"}"
        }
        is TableSelectionState.RowSelected -> "Selected row ${current.row + 1}"
        is TableSelectionState.ColumnSelected -> "Selected column ${columnName(current.column)}"
        TableSelectionState.WholeTable -> "Selected entire table"
    }
}

data class GridViewport(
    val firstRow: Int,
    val lastRow: Int,
    val firstColumn: Int,
    val lastColumn: Int,
    val frozenRows: Int,
    val frozenColumns: Int,
) {
    val composedCellCount: Int
        get() = ((lastRow - firstRow + 1).coerceAtLeast(0) * (lastColumn - firstColumn + 1).coerceAtLeast(0)) +
            frozenRows * (lastColumn - firstColumn + 1).coerceAtLeast(0) +
            frozenColumns * (lastRow - firstRow + 1).coerceAtLeast(0) +
            frozenRows * frozenColumns
}

object SpreadsheetViewportPlanner {
    fun plan(
        sheet: CalculationSheet,
        verticalOffsetPoints: Float,
        horizontalOffsetPoints: Float,
        viewportWidthPoints: Float,
        viewportHeightPoints: Float,
        defaultRowHeightPoints: Float = 24f,
        defaultColumnWidthPoints: Float = 96f,
        overscan: Int = 2,
    ): GridViewport {
        val rowSizes = sheet.rows.map { if (it.hidden) 0f else it.heightPoints ?: defaultRowHeightPoints }
        val columnSizes = sheet.columns.map { if (it.hidden) 0f else it.widthPoints ?: defaultColumnWidthPoints }
        val firstRow = visibleIndex(rowSizes, verticalOffsetPoints)
        val firstColumn = visibleIndex(columnSizes, horizontalOffsetPoints)
        val visibleRows = visibleEnd(rowSizes, firstRow, viewportHeightPoints)
        val visibleColumns = visibleEnd(columnSizes, firstColumn, viewportWidthPoints)
        return GridViewport(
            firstRow = (firstRow - overscan).coerceAtLeast(sheet.frozenRows),
            lastRow = (visibleRows + overscan).coerceAtMost(sheet.rowCount - 1),
            firstColumn = (firstColumn - overscan).coerceAtLeast(sheet.frozenColumns),
            lastColumn = (visibleColumns + overscan).coerceAtMost(sheet.columnCount - 1),
            frozenRows = sheet.frozenRows,
            frozenColumns = sheet.frozenColumns,
        )
    }

    private fun visibleIndex(sizes: List<Float>, offset: Float): Int {
        var remaining = offset.coerceAtLeast(0f)
        sizes.forEachIndexed { index, size ->
            if (size > 0f && remaining < size) return index
            remaining -= size
        }
        return (sizes.size - 1).coerceAtLeast(0)
    }

    private fun visibleEnd(sizes: List<Float>, start: Int, extent: Float): Int {
        var remaining = extent.coerceAtLeast(0f)
        for (index in start until sizes.size) {
            if (sizes[index] <= 0f) continue
            remaining -= sizes[index]
            if (remaining <= 0f) return index
        }
        return (sizes.size - 1).coerceAtLeast(0)
    }
}

/** Host-neutral edit session. The supplied commit callback routes every action into canonical History. */
class CalculationTableSession(
    initialBlock: TableBlock,
    private val commitBlock: (label: String, before: TableBlock, after: TableBlock) -> Boolean,
    private val beforeLegacyUpgrade: (() -> Unit)? = null,
) {
    private var blockState = initialBlock
    private var viewState = TableCalculationBridge.view(initialBlock)
    private var engineState = CalculationEngine(viewState.workbook)
    val selection = TableSelectionController()
    private var pendingWarning: Triple<GridAddress, CellInput, String>? = null

    fun block(): TableBlock = blockState
    fun view(): TableWorkbookView = viewState
    fun engine(): CalculationEngine = engineState
    fun workbook(): CalculationWorkbook = engineState.workbook()

    fun replaceFromOwner(block: TableBlock) {
        if (block == blockState) return
        blockState = block
        viewState = TableCalculationBridge.view(block)
        if (viewState.workbook != engineState.workbook()) engineState = CalculationEngine(viewState.workbook)
    }

    fun commitCell(row: Int, column: Int, input: CellInput): CellCommitResult {
        ensureUpgraded("Upgrade table") ?: return CellCommitResult(false, error = CalculationError(CalculationErrorKind.INTERNAL, "Table upgrade failed"))
        val sheet = engineState.workbook().activeSheet()
        val address = GridAddress(row, column)
        val confirm = pendingWarning?.let { it.first == address && it.second == input } == true
        val result = engineState.commitInput(sheet.id, row, column, input, confirmWarning = confirm)
        if (!result.accepted) {
            pendingWarning = if (result.requiresConfirmation) Triple(address, input, result.warning.orEmpty()) else null
            return if (result.requiresConfirmation) result.copy(warning = "${result.warning.orEmpty()} Apply the same value again to confirm.") else result
        }
        pendingWarning = null
        persist("Edit ${GridAddress(row, column).a1()}")
        return result
    }

    fun transform(label: String, mutation: (CalculationWorkbook) -> CalculationMutationResult): StableCellCoordinate? {
        ensureUpgraded(label) ?: return null
        val result = mutation(engineState.workbook())
        engineState.replaceWorkbook(result.workbook)
        persist(label)
        return result.activeCell
    }

    fun replaceWorkbook(label: String, workbook: CalculationWorkbook): Boolean {
        ensureUpgraded(label) ?: return false
        engineState.replaceWorkbook(workbook)
        return persist(label)
    }

    private fun ensureUpgraded(label: String): TableBlock? {
        if (viewState.upgraded) return blockState
        beforeLegacyUpgrade?.invoke()
        val upgraded = TableCalculationBridge.upgrade(blockState) as? TableUpgradeResult.Success ?: return null
        if (!commitBlock(label, blockState, upgraded.block)) return null
        blockState = upgraded.block
        viewState = upgraded.view
        engineState = CalculationEngine(viewState.workbook)
        return blockState
    }

    private fun persist(label: String): Boolean {
        val after = TableCalculationBridge.persist(blockState, engineState.workbook(), viewState.tableId)
        if (after == blockState) return false
        if (!commitBlock(label, blockState, after)) return false
        blockState = after
        val next = TableCalculationBridge.view(after)
        viewState = next
        // Persisting is a serialize/deserialize round trip of the workbook the engine already holds. When the
        // round trip is value-identical — the normal case — the engine keeps its parsed formulas, dependency
        // edges and calculated values, so a one-cell edit stays a narrow invalidation instead of becoming a
        // full-workbook recalculation on every keystroke. A rebuild happens only if the round trip actually
        // changed something, which would mean the engine's in-memory model no longer matches what was stored.
        if (next.workbook != engineState.workbook()) engineState = CalculationEngine(next.workbook)
        return true
    }
}
