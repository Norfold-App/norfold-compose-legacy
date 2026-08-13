package com.norfold.app.editor.chart

import com.norfold.app.editor.calculation.CalculatedValue
import com.norfold.app.editor.calculation.CalculationRangeBinding
import com.norfold.app.editor.calculation.CalculationRangeSnapshot
import java.math.BigDecimal

data class ResolvedChartCell(
    val text: String,
    val number: Double? = null,
    val error: String? = null,
)

data class ResolvedChartTable(
    val columns: List<ChartColumn>,
    val rows: List<List<ResolvedChartCell>>,
    val omittedRowCount: Int = 0,
)

data class ResolvedChartPoint(
    val rowId: String,
    val category: String,
    val x: Double,
    val y: Double,
    val sourceRowIndex: Int,
)

data class ResolvedChartSeries(
    val id: String,
    val name: String,
    val color: com.norfold.app.domain.style.ColorValue,
    val linePattern: ChartLinePattern,
    val points: List<ResolvedChartPoint>,
    val markType: ChartType = ChartType.BAR,
    val axisId: String = "y",
    val strokeWidth: Float? = null,
    val pointRadius: Float? = null,
    val barWidthFraction: Float? = null,
    val opacity: Float? = null,
)

data class ResolvedChartData(
    val table: ResolvedChartTable,
    val series: List<ResolvedChartSeries>,
    val warnings: List<String> = emptyList(),
)

sealed interface ChartDataResolution {
    data class Ready(val data: ResolvedChartData) : ChartDataResolution
    data class BrokenBinding(val message: String, val binding: CalculationRangeBinding) : ChartDataResolution
    data class Invalid(val message: String) : ChartDataResolution
}

fun interface ChartCalculationDataProvider {
    fun read(binding: CalculationRangeBinding): CalculationRangeSnapshot
}

object ChartDataResolver {
    fun resolve(
        document: ChartDocument,
        calculationProvider: ChartCalculationDataProvider? = null,
    ): ChartDataResolution {
        val table = when (val source = document.source) {
            is ChartDataSource.Manual -> manualTable(source)
            is ChartDataSource.CalculationRange -> {
                val provider = calculationProvider
                    ?: return ChartDataResolution.BrokenBinding("The bound workbook is not available.", source.binding)
                val snapshot = runCatching { provider.read(source.binding) }.getOrElse { failure ->
                    return ChartDataResolution.BrokenBinding(
                        failure.message ?: "The bound range no longer exists.",
                        source.binding,
                    )
                }
                calculationTable(source, snapshot)
            }
        }
        if (table.columns.isEmpty()) return ChartDataResolution.Invalid("Add at least one data column.")
        val series = resolveSeries(document, table)
        val warnings = buildList {
            if (table.rows.isEmpty()) add("No data rows")
            if (series.isEmpty() && table.rows.isNotEmpty()) add("Choose a numeric value column")
            if (table.omittedRowCount > 0) add("${table.omittedRowCount} source rows exceed the preview resource limit and were not resolved")
            val errors = table.rows.sumOf { row -> row.count { it.error != null } }
            if (errors > 0) add("$errors source ${if (errors == 1) "cell contains" else "cells contain"} an error and ${if (errors == 1) "was" else "were"} skipped")
        }
        return ChartDataResolution.Ready(ResolvedChartData(table, series, warnings))
    }

    private fun manualTable(source: ChartDataSource.Manual): ResolvedChartTable {
        val columns = source.columns.distinctBy { it.id }.take(MAX_COLUMNS)
        val rowLimit = minOf(MAX_ROWS, MAX_RESOLVED_CELLS / columns.size.coerceAtLeast(1)).coerceAtLeast(1)
        val rows = source.rows.take(rowLimit).map { row ->
            columns.indices.map { index -> row.values.getOrNull(index).orEmpty().toCell() }
        }
        return ResolvedChartTable(columns, rows, (source.rows.size - rows.size).coerceAtLeast(0))
    }

    private fun calculationTable(
        source: ChartDataSource.CalculationRange,
        snapshot: CalculationRangeSnapshot,
    ): ResolvedChartTable {
        val sourceWidth = snapshot.rows.maxOfOrNull(List<CalculatedValue>::size)?.coerceIn(1, MAX_COLUMNS) ?: 1
        val rowLimit = minOf(MAX_ROWS, MAX_RESOLVED_CELLS / sourceWidth).coerceAtLeast(1)
        val raw = snapshot.rows.take(rowLimit).map { row -> row.take(MAX_COLUMNS).map(::calculatedCell) }
        val omitted = (snapshot.rows.size - raw.size).coerceAtLeast(0)
        val wantsHeader = when (source.headerMode) {
            ChartHeaderMode.FIRST_ROW -> true
            ChartHeaderMode.NONE -> false
            ChartHeaderMode.AUTO -> source.binding.includeHeaders && looksLikeHeader(raw)
        }
        val width = raw.maxOfOrNull(List<ResolvedChartCell>::size) ?: 0
        val header = raw.firstOrNull().orEmpty()
        val columns = List(width) { index ->
            ChartColumn(
                id = "range-column-$index",
                name = if (wantsHeader) header.getOrNull(index)?.text.orEmpty().ifBlank { "Column ${index + 1}" }
                    else "Column ${index + 1}",
                kind = ChartColumnKind.AUTO,
            )
        }
        val body = if (wantsHeader) raw.drop(1) else raw
        val normalized = if (source.orientation == ChartDataOrientation.COLUMNS_AS_SERIES) body else transpose(body)
        return if (source.orientation == ChartDataOrientation.COLUMNS_AS_SERIES) {
            ResolvedChartTable(columns, normalized, omitted)
        } else {
            val transposedColumns = normalized.firstOrNull()?.indices?.map { index ->
                ChartColumn("range-row-$index", "Row ${index + 1}", ChartColumnKind.AUTO)
            }.orEmpty()
            ResolvedChartTable(transposedColumns, normalized, omitted)
        }
    }

    private fun resolveSeries(document: ChartDocument, table: ResolvedChartTable): List<ResolvedChartSeries> {
        if (table.rows.isEmpty()) return emptyList()
        val columnsById = table.columns.withIndex().associate { it.value.id to it.index }
        val firstTextColumn = table.columns.indices.firstOrNull { index ->
            table.rows.any { row -> row.getOrNull(index)?.number == null && row.getOrNull(index)?.error == null }
        }
        val numericColumns = table.columns.indices.filter { index -> table.rows.any { it.getOrNull(index)?.number != null } }
        val palette = document.style.palette.ifEmpty { ChartStyle.defaultPalette() }
        val candidates = document.series.filter(ChartSeries::visible).take(MAX_SERIES)
        val effective = if (candidates.any { columnsById[it.valueColumnId] != null }) candidates else {
            numericColumns.mapIndexed { index, columnIndex ->
                ChartSeries(
                    id = "auto-series-$columnIndex",
                    name = table.columns[columnIndex].name,
                    categoryColumnId = firstTextColumn?.let { table.columns[it].id },
                    xColumnId = null,
                    valueColumnId = table.columns[columnIndex].id,
                    color = palette[index % palette.size],
                    linePattern = ChartLinePattern.entries[index % ChartLinePattern.entries.size],
                )
            }
        }
        return effective.mapNotNull { series ->
            val yIndex = columnsById[series.valueColumnId] ?: return@mapNotNull null
            val categoryIndex = series.categoryColumnId?.let(columnsById::get) ?: firstTextColumn
            val xIndex = series.xColumnId?.let(columnsById::get)
            val points = table.rows.mapIndexedNotNull { rowIndex, row ->
                val y = row.getOrNull(yIndex)?.number ?: return@mapIndexedNotNull null
                val x = xIndex?.let { row.getOrNull(it)?.number } ?: rowIndex.toDouble()
                if (!x.isFinite() || !y.isFinite()) return@mapIndexedNotNull null
                ResolvedChartPoint(
                    rowId = when (val source = document.source) {
                        is ChartDataSource.Manual -> source.rows.getOrNull(rowIndex)?.id ?: "row-$rowIndex"
                        is ChartDataSource.CalculationRange ->
                            "${source.binding.range.start.sheetId}:${source.binding.range.start.rowId}:$rowIndex"
                    },
                    category = categoryIndex?.let { row.getOrNull(it)?.text }.orEmpty().ifBlank { (rowIndex + 1).toString() },
                    x = x,
                    y = y,
                    sourceRowIndex = rowIndex,
                )
            }
            ResolvedChartSeries(
                id = series.id,
                name = series.name,
                color = series.color,
                linePattern = series.linePattern,
                markType = series.markType ?: document.type,
                axisId = series.axisId,
                strokeWidth = series.strokeWidth,
                pointRadius = series.pointRadius,
                barWidthFraction = series.barWidthFraction,
                opacity = series.opacity,
                points = points,
            )
        }
    }

    private fun calculatedCell(value: CalculatedValue): ResolvedChartCell = when (value) {
        CalculatedValue.Blank -> ResolvedChartCell("")
        is CalculatedValue.Text -> value.value.toCell()
        is CalculatedValue.Number -> ResolvedChartCell(value.value.toPlainString(), value.value.safeDouble())
        is CalculatedValue.BooleanValue -> ResolvedChartCell(value.value.toString())
        is CalculatedValue.DateValue -> ResolvedChartCell(value.value.toString())
        is CalculatedValue.DateTimeValue -> ResolvedChartCell(value.value.toString())
        is CalculatedValue.ErrorValue -> ResolvedChartCell(value.error.displayLabel, error = value.error.displayLabel)
    }

    private fun String.toCell(): ResolvedChartCell {
        val clean = trim()
        val number = runCatching { BigDecimal(clean) }.getOrNull()?.safeDouble()
        return ResolvedChartCell(this, number)
    }

    private fun BigDecimal.safeDouble(): Double? = toDouble().takeIf(Double::isFinite)

    private fun looksLikeHeader(rows: List<List<ResolvedChartCell>>): Boolean {
        val first = rows.firstOrNull() ?: return false
        if (rows.size < 2 || first.isEmpty()) return false
        return first.any { it.text.isNotBlank() && it.number == null && it.error == null } &&
            rows.drop(1).any { row -> row.any { it.number != null } }
    }

    private fun transpose(rows: List<List<ResolvedChartCell>>): List<List<ResolvedChartCell>> {
        val width = rows.maxOfOrNull(List<ResolvedChartCell>::size) ?: return emptyList()
        return List(width) { column -> rows.map { it.getOrNull(column) ?: ResolvedChartCell("") } }
    }

    private const val MAX_COLUMNS = 256
    private const val MAX_ROWS = 100_000
    private const val MAX_RESOLVED_CELLS = 1_000_000
    private const val MAX_SERIES = 64
}

data class AccessibleChartTable(
    val headers: List<String>,
    val rows: List<List<String>>,
)

object ChartAccessibility {
    fun description(document: ChartDocument, data: ResolvedChartData): String = buildString {
        document.description.takeIf(String::isNotBlank)?.let { append(it.trim()).append(' ') }
        append(document.type.name.lowercase().replace('_', ' ')).append(" chart")
        document.title.takeIf(String::isNotBlank)?.let { append(" titled ").append(it) }
        append(". ").append(data.series.size).append(if (data.series.size == 1) " series" else " series")
        append(", ").append(data.table.rows.size).append(if (data.table.rows.size == 1) " data row." else " data rows.")
        document.interaction.selectedSeriesId?.let { selectedId ->
            data.series.firstOrNull { it.id == selectedId }?.let { selected ->
                append(" Selected series: ").append(selected.name).append('.')
            }
        }
        document.interaction.selectedSourceRowIndex?.let { append(" Selected data row ").append(it + 1).append('.') }
        data.warnings.firstOrNull()?.let { append(' ').append(it).append('.') }
    }

    fun table(data: ResolvedChartData): AccessibleChartTable = AccessibleChartTable(
        headers = data.table.columns.map { it.name },
        rows = data.table.rows.map { row -> row.map { it.text } },
    )
}
