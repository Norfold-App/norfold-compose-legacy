package com.norfold.app.domain

import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

enum class ChartType(val label: String, val mark: String) {
    Bar("Bar", "bar"),
    Line("Line", "line"),
    Pie("Pie", "arc"),
    Scatter("Scatter", "point"),
    Histogram("Histogram", "bar"),
    Area("Area", "area"),
}

enum class ChartPlacement { Image, Code }

enum class ChartSeriesMark(val label: String, val mark: String) {
    Bar("Bar", "bar"),
    Line("Line", "line"),
    Area("Area", "area"),
    Scatter("Scatter", "point"),
}

enum class ChartOrientation(val label: String) {
    Vertical("Vertical"),
    Horizontal("Horizontal"),
}

data class ChartSeriesStyle(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "Series 1",
    val mark: ChartSeriesMark = ChartSeriesMark.Bar,
)

data class ChartDataRow(
    val id: String = UUID.randomUUID().toString(),
    val label: String = "Item",
    val value: String = "1",
    val series: String = "Series 1",
    val color: String = "",
)

data class ChartBuilderModel(
    val type: ChartType = ChartType.Bar,
    val title: String = "Chart",
    val caption: String = "",
    val xAxis: String = "Category",
    val yAxis: String = "Value",
    val showLegend: Boolean = true,
    val color: String = "",
    val rows: List<ChartDataRow> = listOf(
        ChartDataRow(label = "A", value = "10"),
        ChartDataRow(label = "B", value = "20"),
    ),
    val series: List<ChartSeriesStyle> = listOf(ChartSeriesStyle()),
    val thickness: Float = 0.55f,
    val padding: Float = 0.18f,
    val orientation: ChartOrientation = ChartOrientation.Vertical,
)

/**
 * Vega-Lite codec for both simple and layered charts.
 *
 * The `norfold` object is deliberately self-contained. Vega-Lite consumers ignore it, while
 * Norfold can round-trip row identities/colors, layer order and visual controls without trying
 * to reverse-engineer arbitrary transforms.
 */
object ChartSpecCodec {
    private val json = Json { ignoreUnknownKeys = true }

    fun encode(model: ChartBuilderModel): String {
        val styles = normalizedSeries(model)
        val values = buildJsonArray {
            model.rows.forEach { row ->
                val item = if (styles.size > 1) "${row.series} · ${row.label}" else row.label
                add(buildJsonObject {
                    put("rowId", row.id)
                    put("x", row.label)
                    put("y", row.value.toDoubleOrNull() ?: 0.0)
                    put("series", row.series)
                    put("item", item)
                    row.color.ifBlank { model.color }.takeIf(String::isNotBlank)?.let { put("color", it) }
                })
            }
        }

        return buildJsonObject {
            put("${'$'}schema", "https://vega.github.io/schema/vega-lite/v5.json")
            if (model.caption.isBlank()) {
                put("title", model.title)
            } else {
                put("title", buildJsonObject {
                    put("text", model.title)
                    put("subtitle", model.caption)
                })
            }
            put("data", buildJsonObject { put("values", values) })
            when (model.type) {
                ChartType.Pie -> {
                    put("mark", buildMark(ChartSeriesMark.Bar, model, overrideType = "arc"))
                    put("encoding", pieEncoding(model))
                }
                ChartType.Histogram -> {
                    put("mark", buildMark(ChartSeriesMark.Bar, model))
                    put("encoding", histogramEncoding(model))
                }
                else -> put("layer", buildJsonArray {
                    styles.forEach { style ->
                        add(buildJsonObject {
                            put("transform", buildJsonArray {
                                add(buildJsonObject {
                                    put("filter", buildJsonObject {
                                        put("field", "series")
                                        put("equal", style.name)
                                    })
                                })
                            })
                            put("mark", buildMark(style.mark, model))
                            put("encoding", cartesianEncoding(model, style.mark))
                        })
                    }
                })
            }
            put("norfold", metadata(model, styles))
        }.toString()
    }

    fun isValid(spec: String): Boolean = runCatching {
        json.parseToJsonElement(spec).jsonObject
    }.isSuccess

    fun decode(spec: String?): ChartBuilderModel = decodeOrNull(spec) ?: ChartBuilderModel()

    fun decodeOrNull(spec: String?): ChartBuilderModel? {
        if (spec.isNullOrBlank()) return null
        return runCatching {
            val root = json.parseToJsonElement(spec).jsonObject
            val norfold = root["norfold"].objectOrNull()
            val mark = root.markType()
            val type = norfold.text("chartType")
                ?.let { value -> ChartType.entries.firstOrNull { it.name.equals(value, true) } }
                ?: when (mark) {
                    "line" -> ChartType.Line
                    "arc" -> ChartType.Pie
                    "point" -> ChartType.Scatter
                    "area" -> ChartType.Area
                    else -> ChartType.Bar
                }

            val valueRows = root["data"].objectOrNull()?.get("values").arrayOrNull().orEmpty()
            val metadataRows = norfold?.get("rows").arrayOrNull().orEmpty()
            val rowsSource = metadataRows.ifEmpty { valueRows }
            val rows = rowsSource.mapIndexed { index, value ->
                val row = value.objectOrNull().orEmptyJsonObject()
                val fallback = valueRows.getOrNull(index).objectOrNull()
                ChartDataRow(
                    id = row.text("id") ?: row.text("rowId") ?: fallback.text("rowId") ?: UUID.randomUUID().toString(),
                    label = row.text("label") ?: row.text("x") ?: fallback.text("x").orEmpty(),
                    value = row.numberText("value") ?: row.numberText("y") ?: fallback.numberText("y") ?: "0",
                    series = row.text("series") ?: fallback.text("series") ?: "Series 1",
                    color = row.text("color") ?: fallback.text("color").orEmpty(),
                )
            }.ifEmpty { ChartBuilderModel().rows }

            val metadataSeries = norfold?.get("series").arrayOrNull().orEmpty().mapNotNull { element ->
                val entry = element.objectOrNull() ?: return@mapNotNull null
                val name = entry.text("name")?.takeIf(String::isNotBlank) ?: return@mapNotNull null
                ChartSeriesStyle(
                    id = entry.text("id") ?: UUID.randomUUID().toString(),
                    name = name,
                    mark = entry.text("mark")
                        ?.let { value -> ChartSeriesMark.entries.firstOrNull { it.name.equals(value, true) || it.mark.equals(value, true) } }
                        ?: type.defaultSeriesMark(),
                )
            }
            val layerMarks = root["layer"].arrayOrNull().orEmpty().mapNotNull { layer ->
                val layerObject = layer.objectOrNull() ?: return@mapNotNull null
                val seriesName = layerObject["transform"].arrayOrNull()
                    ?.firstOrNull().objectOrNull()
                    ?.get("filter").objectOrNull()
                    ?.text("equal")
                    ?: return@mapNotNull null
                val layerMark = layerObject.markType()
                ChartSeriesStyle(
                    name = seriesName,
                    mark = ChartSeriesMark.entries.firstOrNull { it.mark == layerMark } ?: type.defaultSeriesMark(),
                )
            }
            val series = metadataSeries.ifEmpty {
                layerMarks.ifEmpty {
                    rows.map(ChartDataRow::series).distinct().map { name ->
                        ChartSeriesStyle(name = name, mark = type.defaultSeriesMark())
                    }
                }
            }

            ChartBuilderModel(
                type = type,
                title = root.text("title") ?: root["title"].objectOrNull()?.text("text") ?: "Chart",
                caption = norfold.text("caption") ?: root["title"].objectOrNull()?.text("subtitle").orEmpty(),
                xAxis = norfold.text("xAxis") ?: "Category",
                yAxis = norfold.text("yAxis") ?: "Value",
                showLegend = norfold?.get("showLegend")?.primitiveBoolean() ?: true,
                color = norfold.text("defaultColor")
                    ?: root["mark"].objectOrNull()?.text("color")
                    ?: "",
                rows = rows,
                series = series,
                thickness = (norfold.number("thickness") ?: 0.55).toFloat().coerceIn(0.1f, 1f),
                padding = (norfold.number("padding") ?: 0.18).toFloat().coerceIn(0f, 0.8f),
                orientation = norfold.text("orientation")
                    ?.let { value -> ChartOrientation.entries.firstOrNull { it.name.equals(value, true) } }
                    ?: ChartOrientation.Vertical,
            )
        }.getOrNull()
    }

    private fun metadata(model: ChartBuilderModel, styles: List<ChartSeriesStyle>): JsonObject = buildJsonObject {
        put("chartType", model.type.name)
        put("caption", model.caption)
        put("xAxis", model.xAxis)
        put("yAxis", model.yAxis)
        put("showLegend", model.showLegend)
        put("defaultColor", model.color)
        put("thickness", model.thickness.coerceIn(0.1f, 1f))
        put("padding", model.padding.coerceIn(0f, 0.8f))
        put("orientation", model.orientation.name)
        put("series", buildJsonArray {
            styles.forEach { style ->
                add(buildJsonObject {
                    put("id", style.id)
                    put("name", style.name)
                    put("mark", style.mark.name)
                })
            }
        })
        put("rows", buildJsonArray {
            model.rows.forEach { row ->
                add(buildJsonObject {
                    put("id", row.id)
                    put("label", row.label)
                    put("value", row.value)
                    put("series", row.series)
                    put("color", row.color)
                })
            }
        })
    }

    private fun buildMark(
        mark: ChartSeriesMark,
        model: ChartBuilderModel,
        overrideType: String? = null,
    ): JsonObject = buildJsonObject {
        val thickness = model.thickness.coerceIn(0.1f, 1f)
        put("type", overrideType ?: mark.mark)
        when (overrideType ?: mark.mark) {
            "bar" -> put("size", 6.0 + thickness * 42.0)
            "line" -> {
                put("strokeWidth", 1.0 + thickness * 7.0)
                put("point", true)
            }
            "area" -> {
                put("strokeWidth", 1.0 + thickness * 5.0)
                put("point", true)
                put("opacity", 0.62)
            }
            "point" -> put("size", 24.0 + thickness * 260.0)
            "arc" -> put("outerRadius", 65.0 + thickness * 55.0)
        }
        if (model.color.isNotBlank() && model.rows.none { it.color.isNotBlank() }) put("color", model.color)
    }

    private fun cartesianEncoding(model: ChartBuilderModel, mark: ChartSeriesMark): JsonObject = buildJsonObject {
        val categoryType = if (mark == ChartSeriesMark.Scatter && normalizedSeries(model).size == 1) "quantitative" else "nominal"
        val category = field("x", categoryType, model.xAxis, padding = model.padding)
        val value = field("y", "quantitative", model.yAxis)
        if (model.orientation == ChartOrientation.Vertical) {
            put("x", category)
            put("y", value)
        } else {
            put("x", value)
            put("y", category)
        }
        put("color", colorEncoding(model))
        put("tooltip", buildJsonArray {
            add(field("x", "nominal", model.xAxis))
            add(field("y", "quantitative", model.yAxis))
            add(field("series", "nominal", "Series"))
        })
    }

    private fun pieEncoding(model: ChartBuilderModel): JsonObject = buildJsonObject {
        put("theta", field("y", "quantitative", model.yAxis))
        put("color", colorEncoding(model))
        put("tooltip", buildJsonArray {
            add(field("x", "nominal", model.xAxis))
            add(field("y", "quantitative", model.yAxis))
        })
    }

    private fun histogramEncoding(model: ChartBuilderModel): JsonObject = buildJsonObject {
        val binned = buildJsonObject {
            put("field", "y")
            put("type", "quantitative")
            put("bin", true)
            put("title", model.xAxis)
        }
        val counted = buildJsonObject {
            put("aggregate", "count")
            put("type", "quantitative")
            put("title", model.yAxis)
        }
        if (model.orientation == ChartOrientation.Vertical) {
            put("x", binned)
            put("y", counted)
        } else {
            put("x", counted)
            put("y", binned)
        }
        put("color", colorEncoding(model))
    }

    private fun colorEncoding(model: ChartBuilderModel): JsonObject {
        val effective = model.rows.map { it.color.ifBlank { model.color } }
        return if (effective.all(String::isNotBlank)) {
            buildJsonObject {
                put("field", "item")
                put("type", "nominal")
                put("title", "Item")
                put("scale", buildJsonObject {
                    put("domain", buildJsonArray {
                        model.rows.forEach { row ->
                            add(JsonPrimitive(if (normalizedSeries(model).size > 1) "${row.series} · ${row.label}" else row.label))
                        }
                    })
                    put("range", buildJsonArray { effective.forEach { add(JsonPrimitive(it)) } })
                })
                if (!model.showLegend) put("legend", JsonNull)
            }
        } else {
            field("series", "nominal", "Series", model.showLegend)
        }
    }

    private fun field(
        name: String,
        type: String,
        title: String,
        legend: Boolean = true,
        padding: Float? = null,
    ): JsonObject = buildJsonObject {
        put("field", name)
        put("type", type)
        put("title", title)
        if (!legend) put("legend", JsonNull)
        padding?.let { amount ->
            put("scale", buildJsonObject {
                put("paddingInner", amount.coerceIn(0f, 0.8f))
                put("paddingOuter", (amount / 2f).coerceIn(0f, 0.4f))
            })
        }
    }

    private fun normalizedSeries(model: ChartBuilderModel): List<ChartSeriesStyle> {
        val names = (model.series.map(ChartSeriesStyle::name) + model.rows.map(ChartDataRow::series))
            .map(String::trim)
            .filter(String::isNotBlank)
            .distinct()
        return names.ifEmpty { listOf("Series 1") }.map { name ->
            model.series.firstOrNull { it.name == name }
                ?: ChartSeriesStyle(name = name, mark = model.type.defaultSeriesMark())
        }
    }

    private fun ChartType.defaultSeriesMark(): ChartSeriesMark = when (this) {
        ChartType.Line -> ChartSeriesMark.Line
        ChartType.Area -> ChartSeriesMark.Area
        ChartType.Scatter -> ChartSeriesMark.Scatter
        else -> ChartSeriesMark.Bar
    }

    private fun JsonObject.markType(): String? = this["mark"]?.let { element ->
        element.objectOrNull()?.text("type") ?: element.primitiveText()
    } ?: this["layer"].arrayOrNull()?.firstOrNull().objectOrNull()?.markType()

    private fun JsonElement?.objectOrNull(): JsonObject? = runCatching { this?.jsonObject }.getOrNull()

    private fun JsonElement?.arrayOrNull(): JsonArray? = runCatching { this?.jsonArray }.getOrNull()

    private fun JsonElement?.primitiveText(): String? = runCatching { this?.jsonPrimitive?.contentOrNull }.getOrNull()

    private fun JsonElement?.primitiveBoolean(): Boolean? = runCatching { this?.jsonPrimitive?.booleanOrNull }.getOrNull()

    private fun JsonObject?.text(key: String): String? = this?.get(key).primitiveText()

    private fun JsonObject?.number(key: String): Double? = runCatching { this?.get(key)?.jsonPrimitive?.doubleOrNull }.getOrNull()

    private fun JsonObject?.numberText(key: String): String? {
        val element = this?.get(key) ?: return null
        return element.primitiveText()
    }

    private fun JsonObject?.orEmptyJsonObject(): JsonObject = this ?: JsonObject(emptyMap())
}
