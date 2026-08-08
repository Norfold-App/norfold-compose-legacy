package com.norfold.app.editor.chart

import com.norfold.app.domain.style.ColorValue
import com.norfold.app.editor.calculation.CalculationRangeBinding
import java.util.UUID
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

private fun chartId(prefix: String): String = "$prefix-${UUID.randomUUID()}"

@Serializable
enum class ChartType {
    BAR,
    STACKED_BAR,
    LINE,
    AREA,
    SCATTER,
    PIE,
    DONUT,
    HISTOGRAM,
}

@Serializable
enum class ChartDataOrientation { COLUMNS_AS_SERIES, ROWS_AS_SERIES }

@Serializable
enum class ChartHeaderMode { AUTO, FIRST_ROW, NONE }

@Serializable
enum class ChartColumnKind { TEXT, NUMBER, DATE, AUTO }

@Serializable
data class ChartColumn(
    val id: String = chartId("column"),
    val name: String = "Column",
    val kind: ChartColumnKind = ChartColumnKind.AUTO,
)

@Serializable
data class ChartDataRow(
    val id: String = chartId("row"),
    /** Values follow [ManualChartSource.columns] order. Extra values are preserved and ignored by rendering. */
    val values: List<String> = emptyList(),
)

@Serializable
sealed interface ChartDataSource {
    @Serializable
    @SerialName("manual")
    data class Manual(
        val columns: List<ChartColumn> = defaultColumns(),
        val rows: List<ChartDataRow> = defaultRows(),
    ) : ChartDataSource {
        companion object {
            fun defaultColumns(): List<ChartColumn> = listOf(
                ChartColumn(id = "category", name = "Category", kind = ChartColumnKind.TEXT),
                ChartColumn(id = "value", name = "Value", kind = ChartColumnKind.NUMBER),
            )

            fun defaultRows(): List<ChartDataRow> = listOf(
                ChartDataRow(id = "row-a", values = listOf("A", "10")),
                ChartDataRow(id = "row-b", values = listOf("B", "20")),
            )
        }
    }

    @Serializable
    @SerialName("calculation_range")
    data class CalculationRange(
        val binding: CalculationRangeBinding,
        val headerMode: ChartHeaderMode = ChartHeaderMode.AUTO,
        val orientation: ChartDataOrientation = ChartDataOrientation.COLUMNS_AS_SERIES,
    ) : ChartDataSource
}

@Serializable
enum class ChartLinePattern { SOLID, DASHED, DOTTED }

@Serializable
data class ChartSeries(
    val id: String = chartId("series"),
    val name: String = "Series 1",
    val categoryColumnId: String? = "category",
    val xColumnId: String? = null,
    val valueColumnId: String = "value",
    val color: ColorValue = ColorValue(103, 80, 164),
    val visible: Boolean = true,
    /** A redundant non-colour distinction for line/area/scatter series. */
    val linePattern: ChartLinePattern = ChartLinePattern.SOLID,
)

@Serializable
data class ChartEncoding(
    val orientation: ChartDataOrientation = ChartDataOrientation.COLUMNS_AS_SERIES,
    val categoryColumnId: String? = "category",
    val xColumnId: String? = null,
    val valueColumnIds: List<String> = listOf("value"),
)

@Serializable
enum class ChartAxisPosition { BOTTOM, LEFT, TOP, RIGHT }

@Serializable
data class ChartAxis(
    val id: String,
    val title: String = "",
    val position: ChartAxisPosition,
    val visible: Boolean = true,
    val minimum: Double? = null,
    val maximum: Double? = null,
    val showGrid: Boolean = true,
)

@Serializable
data class ChartAxes(
    val x: ChartAxis = ChartAxis(id = "x", title = "Category", position = ChartAxisPosition.BOTTOM),
    val y: ChartAxis = ChartAxis(id = "y", title = "Value", position = ChartAxisPosition.LEFT),
)

@Serializable
enum class ChartLegendPosition { TOP, BOTTOM, START, END }

@Serializable
data class ChartLegend(
    val visible: Boolean = true,
    val position: ChartLegendPosition = ChartLegendPosition.BOTTOM,
)

@Serializable
data class ChartLabels(
    val showCategory: Boolean = false,
    val showValue: Boolean = false,
    val showSeriesName: Boolean = false,
)

@Serializable
data class ChartStyle(
    val palette: List<ColorValue> = defaultPalette(),
    /** Null keeps this property bound to the active document/theme token. */
    val background: ColorValue? = null,
    /** Null keeps chart text and axes bound to the active on-surface token. */
    val foreground: ColorValue? = null,
    /** Null keeps grid lines bound to the active outline-variant token. */
    val grid: ColorValue? = null,
    val opacity: Float = 1f,
    val cornerRadius: Float = 4f,
    val strokeWidth: Float = 2f,
) {
    companion object {
        fun defaultPalette(): List<ColorValue> = listOf(
            ColorValue(103, 80, 164),
            ColorValue(0, 121, 107),
            ColorValue(21, 101, 192),
            ColorValue(239, 108, 0),
            ColorValue(173, 20, 87),
            ColorValue(69, 90, 100),
        )
    }
}

@Serializable
data class ChartInteraction(
    val selectionEnabled: Boolean = true,
    val valueEditingEnabled: Boolean = true,
    val showTooltips: Boolean = true,
    val selectedSeriesId: String? = null,
    val selectedSourceRowIndex: Int? = null,
)

/**
 * Canonical chart state. Every Studio control and direct manipulation edits this value; render state is
 * derived and never persisted. Unknown future payloads remain outside this type in the owning ChartBlock.
 */
@Serializable
data class ChartDocument(
    val id: String = chartId("chart"),
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val type: ChartType = ChartType.BAR,
    val title: String = "Chart",
    val subtitle: String = "",
    val caption: String = "",
    /** Author-provided non-visual explanation; generated data facts are appended at render time. */
    val description: String = "",
    val source: ChartDataSource = ChartDataSource.Manual(),
    val series: List<ChartSeries> = listOf(ChartSeries(id = "series-1")),
    val encoding: ChartEncoding = ChartEncoding(),
    val axes: ChartAxes = ChartAxes(),
    val legend: ChartLegend = ChartLegend(),
    val labels: ChartLabels = ChartLabels(),
    val style: ChartStyle = ChartStyle(),
    val interaction: ChartInteraction = ChartInteraction(),
) {
    companion object {
        const val CURRENT_SCHEMA_VERSION = 1
        private const val MAX_SERIES = 64
        private const val MAX_PALETTE = 64
        private const val MAX_TEXT = 256
        private const val MAX_CAPTION = 2_048
    }

    /** A total, non-throwing normalization boundary for persisted or imported data. */
    fun normalized(): ChartDocument {
        val cleanSeries = series.distinctBy { it.id }.take(MAX_SERIES).ifEmpty { listOf(ChartSeries(id = "series-1")) }
        return copy(
            schemaVersion = CURRENT_SCHEMA_VERSION,
            title = title.take(MAX_TEXT),
            subtitle = subtitle.take(MAX_TEXT),
            caption = caption.take(MAX_CAPTION),
            description = description.take(MAX_CAPTION),
            series = cleanSeries.map { entry ->
                entry.copy(name = entry.name.take(MAX_TEXT), color = entry.color)
            },
            style = style.copy(
                palette = style.palette.distinct().take(MAX_PALETTE).ifEmpty { ChartStyle.defaultPalette() },
                opacity = style.opacity.takeIf(Float::isFinite)?.coerceIn(0f, 1f) ?: 1f,
                cornerRadius = style.cornerRadius.takeIf(Float::isFinite)?.coerceIn(0f, 32f) ?: 4f,
                strokeWidth = style.strokeWidth.takeIf(Float::isFinite)?.coerceIn(0.5f, 12f) ?: 2f,
            ),
            interaction = interaction.copy(
                selectedSeriesId = interaction.selectedSeriesId?.takeIf { id -> cleanSeries.any { it.id == id } },
                selectedSourceRowIndex = interaction.selectedSourceRowIndex?.takeIf { it >= 0 },
            ),
        )
    }

}
