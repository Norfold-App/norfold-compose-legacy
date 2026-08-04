package com.norfold.app.editor.calculation

import com.norfold.app.editor.command.ApplicabilityPolicy
import com.norfold.app.editor.command.Capability
import com.norfold.app.editor.command.CommandContext
import com.norfold.app.editor.command.CommandRegistry
import com.norfold.app.editor.command.CommandResult
import com.norfold.app.editor.command.EditorCommand

/**
 * Semantic command ids for table and spreadsheet work.
 *
 * The four insertion ids are deliberately the *existing* `flow.table.*` strings. Phase 4 attaches
 * formula-aware structural semantics to the routes that already existed rather than minting a parallel
 * `spreadsheet.table.insertRowAbove` family, which is what §21 forbids: two nearly identical command
 * families is how a keyboard chord and a dock button end up doing subtly different things.
 */
object SpreadsheetCommandIds {
    const val INSERT_ROW_ABOVE = "flow.table.insertRowAbove"
    const val INSERT_ROW_BELOW = "flow.table.insertRowBelow"
    const val INSERT_COLUMN_LEFT = "flow.table.insertColumnLeft"
    const val INSERT_COLUMN_RIGHT = "flow.table.insertColumnRight"
    const val DELETE_ROW = "table.deleteRow"
    const val DELETE_COLUMN = "table.deleteColumn"
    const val MOVE_ROW_EARLIER = "table.moveRowEarlier"
    const val MOVE_ROW_LATER = "table.moveRowLater"
    const val MOVE_COLUMN_EARLIER = "table.moveColumnEarlier"
    const val MOVE_COLUMN_LATER = "table.moveColumnLater"
    const val FIT_ROW_HEIGHT = "table.fitRowHeight"
    const val BEGIN_EDIT = "table.beginEdit"
    const val COMMIT_EDIT = "table.commitEdit"
    const val CANCEL_EDIT = "table.cancelEdit"
    const val SELECT_ROW = "table.selectRow"
    const val SELECT_COLUMN = "table.selectColumn"
    const val SELECT_WHOLE_TABLE = "table.selectWholeTable"
    const val EXTEND_SELECTION_RIGHT = "table.extendSelectionRight"
    const val EXTEND_SELECTION_DOWN = "table.extendSelectionDown"
    const val CLEAR_CELLS = "table.clearCells"
    const val MERGE = "table.merge"
    const val SPLIT = "table.split"
    const val FREEZE = "spreadsheet.freezeAtSelection"
    const val SORT_ASCENDING = "table.sortAscending"
    const val SORT_DESCENDING = "table.sortDescending"
    const val CLEAR_FILTERS = "table.clearFilters"
    const val OPEN_FILL_STUDIO = "table.openFillStudio"
    const val OPEN_ADVANCED_TOOLS = "table.openAdvancedTools"
    const val ADD_SHEET = "spreadsheet.addSheet"
    const val DUPLICATE_SHEET = "spreadsheet.duplicateSheet"
    const val DELETE_SHEET = "spreadsheet.deleteSheet"
    const val MOVE_SHEET_EARLIER = "spreadsheet.moveSheetEarlier"
    const val MOVE_SHEET_LATER = "spreadsheet.moveSheetLater"
    const val APPLY_TABLE_STYLE = "table.applyTableStyle"
    const val TOGGLE_BOLD = "table.toggleBold"
    const val UNDO = "history.undo"
    const val REDO = "history.redo"
}

object SpreadsheetCommandCatalog {
    fun create(runtime: SpreadsheetHostRuntime): CommandRegistry = CommandRegistry().registerAll(
        TableRuntimeCommand(SpreadsheetCommandIds.INSERT_ROW_ABOVE, "Insert row above", runtime::insertRowAbove),
        TableRuntimeCommand(SpreadsheetCommandIds.INSERT_ROW_BELOW, "Insert row below", runtime::insertRowBelow),
        TableRuntimeCommand(SpreadsheetCommandIds.INSERT_COLUMN_LEFT, "Insert column left", runtime::insertColumnLeft),
        TableRuntimeCommand(SpreadsheetCommandIds.INSERT_COLUMN_RIGHT, "Insert column right", runtime::insertColumnRight),
        TableRuntimeCommand(SpreadsheetCommandIds.DELETE_ROW, "Delete row", runtime::deleteSelectedRow),
        TableRuntimeCommand(SpreadsheetCommandIds.DELETE_COLUMN, "Delete column", runtime::deleteSelectedColumn),
        TableRuntimeCommand(SpreadsheetCommandIds.MOVE_ROW_EARLIER, "Move row earlier") { runtime.reorderSelectedRow(-1) },
        TableRuntimeCommand(SpreadsheetCommandIds.MOVE_ROW_LATER, "Move row later") { runtime.reorderSelectedRow(1) },
        TableRuntimeCommand(SpreadsheetCommandIds.MOVE_COLUMN_EARLIER, "Move column earlier") { runtime.reorderSelectedColumn(-1) },
        TableRuntimeCommand(SpreadsheetCommandIds.MOVE_COLUMN_LATER, "Move column later") { runtime.reorderSelectedColumn(1) },
        TableRuntimeCommand(SpreadsheetCommandIds.FIT_ROW_HEIGHT, "Fit row height", runtime::fitSelectedRowHeight),
        TableRuntimeCommand(SpreadsheetCommandIds.BEGIN_EDIT, "Edit cell") { runtime.beginEdit(); true },
        TableRuntimeCommand(SpreadsheetCommandIds.COMMIT_EDIT, "Commit cell edit", runtime::commitActiveEdit),
        TableRuntimeCommand(SpreadsheetCommandIds.CANCEL_EDIT, "Cancel cell edit", runtime::back),
        TableRuntimeCommand(SpreadsheetCommandIds.SELECT_ROW, "Select row") {
            runtime.selectedRowIndex()?.let { runtime.selectRow(it); true } ?: false
        },
        TableRuntimeCommand(SpreadsheetCommandIds.SELECT_COLUMN, "Select column") {
            runtime.selectedColumnIndex()?.let { runtime.selectColumn(it); true } ?: false
        },
        TableRuntimeCommand(SpreadsheetCommandIds.SELECT_WHOLE_TABLE, "Select whole table") { runtime.selectAll(); true },
        TableRuntimeCommand(SpreadsheetCommandIds.EXTEND_SELECTION_RIGHT, "Extend selection right") { runtime.extendSelection(0, 1) },
        TableRuntimeCommand(SpreadsheetCommandIds.EXTEND_SELECTION_DOWN, "Extend selection down") { runtime.extendSelection(1, 0) },
        TableRuntimeCommand(SpreadsheetCommandIds.CLEAR_CELLS, "Clear cells", runtime::clearSelection),
        TableRuntimeCommand(SpreadsheetCommandIds.MERGE, "Merge cells", runtime::mergeSelection),
        TableRuntimeCommand(SpreadsheetCommandIds.SPLIT, "Split cells", runtime::splitSelection),
        TableRuntimeCommand(SpreadsheetCommandIds.FREEZE, "Freeze panes", runtime::freezeAtSelection),
        TableRuntimeCommand(SpreadsheetCommandIds.SORT_ASCENDING, "Sort ascending") { runtime.sortSelection(SortDirection.ASCENDING) },
        TableRuntimeCommand(SpreadsheetCommandIds.SORT_DESCENDING, "Sort descending") { runtime.sortSelection(SortDirection.DESCENDING) },
        TableRuntimeCommand(SpreadsheetCommandIds.CLEAR_FILTERS, "Clear filters", runtime::clearFilters),
        TableRuntimeCommand(SpreadsheetCommandIds.OPEN_FILL_STUDIO, "Cell fill…", runtime::openCellFillStudio),
        TableRuntimeCommand(SpreadsheetCommandIds.OPEN_ADVANCED_TOOLS, "Advanced table tools") { runtime.toggleAdvancedTools(); true },
        TableRuntimeCommand(SpreadsheetCommandIds.ADD_SHEET, "Add sheet", runtime::addSheet),
        TableRuntimeCommand(SpreadsheetCommandIds.DUPLICATE_SHEET, "Duplicate sheet", runtime::duplicateActiveSheet),
        TableRuntimeCommand(SpreadsheetCommandIds.DELETE_SHEET, "Delete sheet", runtime::deleteActiveSheet),
        TableRuntimeCommand(SpreadsheetCommandIds.MOVE_SHEET_EARLIER, "Move sheet earlier") { runtime.reorderActiveSheet(-1) },
        TableRuntimeCommand(SpreadsheetCommandIds.MOVE_SHEET_LATER, "Move sheet later") { runtime.reorderActiveSheet(1) },
        TableRuntimeCommand(SpreadsheetCommandIds.APPLY_TABLE_STYLE, "Apply table style", runtime::applyBuiltInTableStyle),
        TableRuntimeCommand(SpreadsheetCommandIds.TOGGLE_BOLD, "Bold") { runtime.applyFormat(CellFormat(bold = true)) },
        TableRuntimeCommand(SpreadsheetCommandIds.UNDO, "Undo", runtime::undo),
        TableRuntimeCommand(SpreadsheetCommandIds.REDO, "Redo", runtime::redo),
    )
}

private class TableRuntimeCommand(
    override val id: String,
    override val label: String,
    private val action: () -> Boolean,
) : EditorCommand {
    override val description: String = label
    override val icon: String? = null
    override val category: String = "Table"
    override val aliases: List<String> = listOf(label.lowercase())
    override val requiredCapabilities: Set<Capability> = setOf(Capability.TABLE_STRUCTURE)
    override val applicability: ApplicabilityPolicy = ApplicabilityPolicy.PRIMARY_TARGET
    override fun perform(context: CommandContext): CommandResult =
        if (action()) CommandResult.Success() else CommandResult.NoOp
}
