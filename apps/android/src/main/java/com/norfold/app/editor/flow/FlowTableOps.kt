package com.norfold.app.editor.flow

import com.norfold.app.domain.TableAlignment
import com.norfold.app.domain.TableBlock
import com.norfold.app.domain.TableCell
import com.norfold.app.editor.calculation.CalculationMutations
import com.norfold.app.editor.calculation.TableCalculationBridge
import com.norfold.app.editor.calculation.TableUpgradeResult

/**
 * Directional row/column structure operations on a [TableBlock] (roadmap Phase 1 deliverable 8; §4.15,
 * §7.4–§7.5).
 *
 * The locked requirement is that table structure edits are **directional and unambiguous**: there is no
 * `addRow()` that silently means "at the end". Every operation here is expressed relative to an explicit
 * cell address, and the commands that call them are disabled — with a reason — when no cell is addressed.
 *
 * Phase 1 owns the *hooks*: correct geometry, correct id/width/alignment bookkeeping, registered through
 * the shared command model. Phase 4 attaches the calculation engine, formula reference rewriting, and the
 * spreadsheet semantics on top of these same commands, so the surfaces do not need to change again.
 */
object FlowTableOps {

    /** Column count implied by the header row and the widest data row. */
    fun columnCount(table: TableBlock): Int =
        maxOf(table.headers.size, table.rows.maxOfOrNull { it.size } ?: 0)

    /** Data-row count (the header row is not a data row). */
    fun rowCount(table: TableBlock): Int = table.rows.size

    /**
     * Inserts an empty data row at [index] (0-based, clamped to `0..rowCount`). The header row is never
     * displaced: `index = 0` inserts immediately below the header.
     */
    fun insertRow(table: TableBlock, index: Int): TableBlock {
        if (table.calculationPayload == null) {
            val upgraded = TableCalculationBridge.upgrade(table) as? TableUpgradeResult.Success
            if (upgraded != null) return insertRow(upgraded.block, index)
        }
        if (table.calculationPayload != null) {
            val view = TableCalculationBridge.view(table)
            val definition = view.workbook.embeddedTables.first { it.id == view.tableId }
            val sheet = view.workbook.sheet(definition.range.start.sheetId) ?: return table
            val tableStart = sheet.rowIndex(definition.range.start.rowId)
            val at = tableStart + (if (definition.headerRow) 1 else 0) + index.coerceAtLeast(0)
            val result = CalculationMutations.insertRow(view.workbook, sheet.id, at)
            val nextSheet = result.workbook.sheet(sheet.id) ?: return table
            val oldEnd = sheet.rowIndex(definition.range.end.rowId)
            val newEndIndex = if (at <= oldEnd) oldEnd + 1 else at
            val updatedDefinition = definition.copy(
                range = definition.range.copy(end = nextSheet.coordinateAt(newEndIndex.coerceAtMost(nextSheet.rowCount - 1), sheet.columnIndex(definition.range.end.columnId))!!),
            )
            val workbook = result.workbook.copy(
                embeddedTables = result.workbook.embeddedTables.map { if (it.id == definition.id) updatedDefinition else it },
                revision = result.workbook.revision + 1,
            )
            return TableCalculationBridge.persist(table, workbook, view.tableId)
        }
        val at = index.coerceIn(0, table.rows.size)
        val blank = List(columnCount(table)) { TableCell() }
        return table.copy(rows = table.rows.subList(0, at) + listOf(blank) + table.rows.subList(at, table.rows.size))
    }

    /**
     * Inserts an empty column at [index] (0-based, clamped to `0..columnCount`), padding short rows so the
     * grid stays rectangular, and keeping per-column widths and alignments aligned with their columns.
     */
    fun insertColumn(table: TableBlock, index: Int): TableBlock {
        if (table.calculationPayload == null) {
            val upgraded = TableCalculationBridge.upgrade(table) as? TableUpgradeResult.Success
            if (upgraded != null) return insertColumn(upgraded.block, index)
        }
        if (table.calculationPayload != null) {
            val view = TableCalculationBridge.view(table)
            val definition = view.workbook.embeddedTables.first { it.id == view.tableId }
            val sheet = view.workbook.sheet(definition.range.start.sheetId) ?: return table
            val tableStart = sheet.columnIndex(definition.range.start.columnId)
            val at = tableStart + index.coerceAtLeast(0)
            val result = CalculationMutations.insertColumn(view.workbook, sheet.id, at)
            val nextSheet = result.workbook.sheet(sheet.id) ?: return table
            val oldEnd = sheet.columnIndex(definition.range.end.columnId)
            val newEndIndex = if (at <= oldEnd) oldEnd + 1 else at
            val newColumn = nextSheet.columns[at]
            val updatedDefinition = definition.copy(
                range = definition.range.copy(end = nextSheet.coordinateAt(sheet.rowIndex(definition.range.end.rowId), newEndIndex.coerceAtMost(nextSheet.columnCount - 1))!!),
                columns = definition.columns.toMutableList().apply {
                    add(index.coerceIn(0, size), com.norfold.app.editor.calculation.EmbeddedTableColumn(newColumn.id, "Column${size + 1}"))
                },
            )
            val workbook = result.workbook.copy(
                embeddedTables = result.workbook.embeddedTables.map { if (it.id == definition.id) updatedDefinition else it },
                revision = result.workbook.revision + 1,
            )
            return TableCalculationBridge.persist(table, workbook, view.tableId)
        }
        val columns = columnCount(table)
        val at = index.coerceIn(0, columns)
        // An empty widths/alignments list means "unset" and must stay unset; a populated one is padded to
        // the current column count first so the inserted entry lands against the right column.
        fun <T> List<T>.paddedWithInserted(value: T): List<T> {
            if (isEmpty()) return emptyList()
            val padded = this + List((columns - size).coerceAtLeast(0)) { value }
            return padded.subList(0, at) + listOf(value) + padded.subList(at, padded.size)
        }
        fun List<TableCell>.cellsWithInserted(): List<TableCell> {
            val padded = this + List((columns - size).coerceAtLeast(0)) { TableCell() }
            return padded.subList(0, at) + listOf(TableCell()) + padded.subList(at, padded.size)
        }
        return table.copy(
            headers = table.headers.cellsWithInserted(),
            rows = table.rows.map { it.cellsWithInserted() },
            columnWidthsDp = table.columnWidthsDp.paddedWithInserted(DEFAULT_COLUMN_WIDTH_DP),
            columnAlignments = table.columnAlignments.paddedWithInserted(TableAlignment.Start),
        )
    }

    /**
     * Width given to a column created by [insertColumn] when the table already tracks explicit widths.
     * Not a benchmark value — it is the neutral default the existing table UI uses for an unsized column.
     */
    const val DEFAULT_COLUMN_WIDTH_DP = 120f
}
