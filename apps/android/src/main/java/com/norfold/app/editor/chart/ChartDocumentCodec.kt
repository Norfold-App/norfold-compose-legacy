package com.norfold.app.editor.chart

import com.norfold.app.domain.ChartBlock
import com.norfold.app.domain.ChartSpecCodec
import com.norfold.app.domain.ChartType as LegacyChartType
import com.norfold.app.domain.style.ColorValue
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

@Serializable
private data class ChartEnvelope(
    val schemaVersion: Int,
    val document: ChartDocument,
)

sealed interface ChartDecodeResult {
    data class Success(val document: ChartDocument) : ChartDecodeResult
    data class UnsupportedVersion(val version: Int, val raw: String) : ChartDecodeResult
    data class Malformed(val raw: String) : ChartDecodeResult
}

/** Versioned deterministic persistence for the pure-JVM chart model. */
object ChartDocumentCodec {
    private val json = Json {
        encodeDefaults = true
        explicitNulls = false
        ignoreUnknownKeys = true
        classDiscriminator = "sourceKind"
    }

    fun encode(document: ChartDocument): String {
        val normalized = document.normalized()
        return json.encodeToString(ChartEnvelope(ChartDocument.CURRENT_SCHEMA_VERSION, normalized))
    }

    fun decode(raw: String): ChartDecodeResult {
        if (raw.isBlank()) return ChartDecodeResult.Malformed(raw)
        val version = runCatching {
            json.parseToJsonElement(raw).jsonObject["schemaVersion"]?.jsonPrimitive?.intOrNull
        }.getOrNull() ?: return ChartDecodeResult.Malformed(raw)
        if (version != ChartDocument.CURRENT_SCHEMA_VERSION) {
            return ChartDecodeResult.UnsupportedVersion(version, raw)
        }
        return runCatching { json.decodeFromString<ChartEnvelope>(raw).document.normalized() }
            .fold(
                onSuccess = ChartDecodeResult::Success,
                onFailure = { ChartDecodeResult.Malformed(raw) },
            )
    }
}

data class LoadedChartDocument(
    val document: ChartDocument,
    val origin: Origin,
    /** Opaque data that must remain byte-for-byte intact until an explicit successful edit replaces it. */
    val preservedPayload: String,
) {
    enum class Origin { CANONICAL, LEGACY_VEGA, EMPTY, UNSUPPORTED_CANONICAL, MALFORMED_CANONICAL }
    val isEditable: Boolean get() = origin != Origin.UNSUPPORTED_CANONICAL && origin != Origin.MALFORMED_CANONICAL
}

/** Additive adapter: loading never rewrites a block and saving never removes the legacy payload. */
object ChartBlockAdapter {
    fun load(block: ChartBlock): LoadedChartDocument {
        block.chartDocument?.let { canonical ->
            return when (val decoded = ChartDocumentCodec.decode(canonical)) {
                is ChartDecodeResult.Success -> LoadedChartDocument(
                    decoded.document,
                    LoadedChartDocument.Origin.CANONICAL,
                    canonical,
                )
                is ChartDecodeResult.UnsupportedVersion -> LoadedChartDocument(
                    ChartDocument(title = "Chart from a newer version"),
                    LoadedChartDocument.Origin.UNSUPPORTED_CANONICAL,
                    decoded.raw,
                )
                is ChartDecodeResult.Malformed -> LoadedChartDocument(
                    ChartDocument(title = "Chart could not be read"),
                    LoadedChartDocument.Origin.MALFORMED_CANONICAL,
                    decoded.raw,
                )
            }
        }
        if (block.vegaLiteSpec.isBlank() || block.vegaLiteSpec == "{}") {
            return LoadedChartDocument(ChartDocument(), LoadedChartDocument.Origin.EMPTY, block.vegaLiteSpec)
        }
        val migrated = migrateLegacy(block.vegaLiteSpec)
        return LoadedChartDocument(migrated, LoadedChartDocument.Origin.LEGACY_VEGA, block.vegaLiteSpec)
    }

    fun save(block: ChartBlock, document: ChartDocument): ChartBlock =
        block.copy(chartDocument = ChartDocumentCodec.encode(document))

    private fun migrateLegacy(raw: String): ChartDocument {
        val legacy = ChartSpecCodec.decode(raw)
        val names = legacy.rows.map { it.series.ifBlank { "Series 1" } }.distinct().ifEmpty { listOf("Series 1") }
        val valueColumns = names.mapIndexed { index, name ->
            ChartColumn(id = "legacy-series-${index + 1}", name = name, kind = ChartColumnKind.NUMBER)
        }
        val categories = legacy.rows.map { it.label }.distinct()
        val columns = listOf(ChartColumn(id = "category", name = "Category", kind = ChartColumnKind.TEXT)) + valueColumns
        val source = ChartDataSource.Manual(
            columns = columns,
            rows = categories.mapIndexed { rowIndex, category ->
                ChartDataRow(
                    id = "legacy-row-${rowIndex + 1}",
                    values = listOf(category) + names.map { name ->
                        legacy.rows.firstOrNull { it.label == category && it.series.ifBlank { "Series 1" } == name }?.value.orEmpty()
                    },
                )
            },
        )
        val series = names.mapIndexed { index, name ->
            val rowColor = legacy.rows.firstOrNull { it.series.ifBlank { "Series 1" } == name }?.color
            ChartSeries(
                id = legacy.series.firstOrNull { it.name == name }?.id ?: "series-${index + 1}",
                name = name,
                categoryColumnId = "category",
                valueColumnId = valueColumns[index].id,
                color = ColorValue.parse(rowColor ?: legacy.color)
                    ?: ChartStyle.defaultPalette()[index % ChartStyle.defaultPalette().size],
            )
        }
        return ChartDocument(
            type = when (legacy.type) {
                LegacyChartType.Bar -> ChartType.BAR
                LegacyChartType.Line -> ChartType.LINE
                LegacyChartType.Pie -> ChartType.PIE
                LegacyChartType.Scatter -> ChartType.SCATTER
                LegacyChartType.Histogram -> ChartType.HISTOGRAM
                LegacyChartType.Area -> ChartType.AREA
            },
            title = legacy.title,
            subtitle = legacy.caption,
            source = source,
            series = series,
            encoding = ChartEncoding(categoryColumnId = "category", valueColumnIds = valueColumns.map { it.id }),
            axes = ChartAxes(
                x = ChartAxis("x", legacy.xAxis, ChartAxisPosition.BOTTOM),
                y = ChartAxis("y", legacy.yAxis, ChartAxisPosition.LEFT),
            ),
            legend = ChartLegend(visible = legacy.showLegend),
            style = ChartStyle(opacity = 1f),
        ).normalized()
    }
}
