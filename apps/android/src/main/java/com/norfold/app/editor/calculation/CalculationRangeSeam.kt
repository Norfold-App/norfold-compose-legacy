package com.norfold.app.editor.calculation

import kotlinx.serialization.Serializable

/** Phase-5 seam only: a stable range identity with no chart model, renderer, command, or UI. */
@Serializable
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
        return read(workbook, binding, CalculationEngine(workbook))
    }

    /** Reads through an existing engine so range consumers never have to rebuild the dependency graph. */
    fun read(
        workbook: CalculationWorkbook,
        binding: CalculationRangeBinding,
        engine: CalculationEngine,
    ): CalculationRangeSnapshot {
        require(binding.workbookId == workbook.id) { "Range binding belongs to another workbook" }
        require(engine.workbook() == workbook) { "Calculation engine does not match the requested workbook revision" }
        val sheet = workbook.sheet(binding.range.start.sheetId) ?: error("Bound sheet no longer exists")
        val startRow = sheet.rowIndex(binding.range.start.rowId)
        val endRow = sheet.rowIndex(binding.range.end.rowId)
        val startColumn = sheet.columnIndex(binding.range.start.columnId)
        val endColumn = sheet.columnIndex(binding.range.end.columnId)
        require(minOf(startRow, endRow, startColumn, endColumn) >= 0) { "Bound range no longer exists" }
        val rows = (minOf(startRow, endRow)..maxOf(startRow, endRow)).map { row ->
            (minOf(startColumn, endColumn)..maxOf(startColumn, endColumn)).map { column ->
                engine.valueAt(sheet.id, row, column)
            }
        }
        return CalculationRangeSnapshot(binding, rows, workbook.revision)
    }
}

/** Evidence exposed by the cache for performance regression tests and diagnostics. */
data class CalculationRangeRuntimeStats(
    val engineBuilds: Long = 0,
    val rangeReads: Long = 0,
    val cellReads: Long = 0,
)

/**
 * Session-scoped range runtime. A workbook revision builds at most one [CalculationEngine], and identical
 * range reads in that revision reuse the immutable snapshot. Compose receives snapshots from its owner; it
 * never constructs an engine while drawing.
 */
class CalculationRangeRuntime {
    private var cachedWorkbook: CalculationWorkbook? = null
    private var cachedEngine: CalculationEngine? = null
    private val snapshots = LinkedHashMap<CalculationRangeBinding, CalculationRangeSnapshot>()
    private var counters = CalculationRangeRuntimeStats()

    fun read(workbook: CalculationWorkbook, binding: CalculationRangeBinding): CalculationRangeSnapshot {
        val engine = engineFor(workbook)
        snapshots[binding]?.takeIf { it.workbookRevision == workbook.revision }?.let { return it }
        val snapshot = CalculationRangeReader.read(workbook, binding, engine)
        snapshots[binding] = snapshot
        counters = counters.copy(
            rangeReads = counters.rangeReads + 1,
            cellReads = counters.cellReads + snapshot.rows.sumOf { it.size },
        )
        return snapshot
    }

    fun stats(): CalculationRangeRuntimeStats = counters

    fun clear() {
        cachedWorkbook = null
        cachedEngine = null
        snapshots.clear()
    }

    private fun engineFor(workbook: CalculationWorkbook): CalculationEngine {
        cachedEngine?.takeIf { cachedWorkbook == workbook }?.let { return it }
        return CalculationEngine(workbook).also {
            cachedWorkbook = workbook
            cachedEngine = it
            snapshots.clear()
            counters = counters.copy(engineBuilds = counters.engineBuilds + 1)
        }
    }
}
