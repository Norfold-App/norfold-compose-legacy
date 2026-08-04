package com.norfold.app.editor.calculation

/** Phase-5 seam only: a stable range identity with no chart model, renderer, command, or UI. */
data class CalculationRangeBinding(
    val workbookId: WorkbookId,
    val range: StableCellRange,
    val includeHeaders: Boolean = true,
)

data class CalculationRangeSnapshot(
    val binding: CalculationRangeBinding,
    val rows: List<List<CalculatedValue>>,
    val workbookRevision: Long,
)

object CalculationRangeReader {
    fun read(workbook: CalculationWorkbook, binding: CalculationRangeBinding): CalculationRangeSnapshot {
        require(binding.workbookId == workbook.id) { "Range binding belongs to another workbook" }
        val sheet = workbook.sheet(binding.range.start.sheetId) ?: error("Bound sheet no longer exists")
        val startRow = sheet.rowIndex(binding.range.start.rowId)
        val endRow = sheet.rowIndex(binding.range.end.rowId)
        val startColumn = sheet.columnIndex(binding.range.start.columnId)
        val endColumn = sheet.columnIndex(binding.range.end.columnId)
        require(minOf(startRow, endRow, startColumn, endColumn) >= 0) { "Bound range no longer exists" }
        val engine = CalculationEngine(workbook)
        val rows = (minOf(startRow, endRow)..maxOf(startRow, endRow)).map { row ->
            (minOf(startColumn, endColumn)..maxOf(startColumn, endColumn)).map { column ->
                engine.valueAt(sheet.id, row, column)
            }
        }
        return CalculationRangeSnapshot(binding, rows, workbook.revision)
    }
}
