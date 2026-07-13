package com.norfold.app.domain

import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
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

data class ChartDataRow(
    val id: String = UUID.randomUUID().toString(),
    val label: String = "Item",
    val value: String = "1",
    val series: String = "Series 1",
)

data class ChartBuilderModel(
    val type: ChartType = ChartType.Bar,
    val title: String = "Chart",
    val xAxis: String = "Category",
    val yAxis: String = "Value",
    val showLegend: Boolean = true,
    val color: String = "",
    val rows: List<ChartDataRow> = listOf(
        ChartDataRow(label = "A", value = "10"),
        ChartDataRow(label = "B", value = "20"),
    ),
)

object ChartSpecCodec {
    private val json = Json { ignoreUnknownKeys = true }

    fun encode(model: ChartBuilderModel): String = buildJsonObject {
        put("${'$'}schema", "https://vega.github.io/schema/vega-lite/v5.json")
        put("title", model.title)
        put("mark", buildJsonObject {
            put("type", model.type.mark)
            if (model.color.isNotBlank()) put("color", model.color)
            if (model.type == ChartType.Line || model.type == ChartType.Area) put("point", true)
        })
        put("data", buildJsonObject {
            put("values", buildJsonArray {
                model.rows.forEach { row ->
                    add(buildJsonObject {
                        put("x", row.label)
                        put("y", row.value.toDoubleOrNull() ?: 0.0)
                        put("series", row.series)
                    })
                }
            })
        })
        put("encoding", encoding(model))
        put("norfold", buildJsonObject {
            put("chartType", model.type.name)
            put("xAxis", model.xAxis)
            put("yAxis", model.yAxis)
            put("showLegend", model.showLegend)
        })
    }.toString()

    fun decode(spec: String?): ChartBuilderModel {
        if (spec.isNullOrBlank()) return ChartBuilderModel()
        return runCatching {
            val root = json.parseToJsonElement(spec).jsonObject
            val norfold = root["norfold"]?.jsonObject
            val mark = root["mark"]?.let { element ->
                runCatching { element.jsonObject["type"]?.jsonPrimitive?.contentOrNull }.getOrNull()
                    ?: runCatching { element.jsonPrimitive.contentOrNull }.getOrNull()
            }
            val type = norfold?.get("chartType")?.jsonPrimitive?.contentOrNull
                ?.let { value -> ChartType.entries.firstOrNull { it.name.equals(value, true) } }
                ?: when (mark) {
                    "line" -> ChartType.Line
                    "arc" -> ChartType.Pie
                    "point" -> ChartType.Scatter
                    "area" -> ChartType.Area
                    else -> ChartType.Bar
                }
            val values = root["data"]?.jsonObject?.get("values")?.jsonArray ?: JsonArray(emptyList())
            ChartBuilderModel(
                type = type,
                title = root["title"]?.jsonPrimitive?.contentOrNull ?: "Chart",
                xAxis = norfold?.get("xAxis")?.jsonPrimitive?.contentOrNull ?: "Category",
                yAxis = norfold?.get("yAxis")?.jsonPrimitive?.contentOrNull ?: "Value",
                showLegend = norfold?.get("showLegend")?.jsonPrimitive?.booleanOrNull ?: true,
                color = root["mark"]?.let { element ->
                    runCatching { element.jsonObject["color"]?.jsonPrimitive?.contentOrNull }.getOrNull()
                }.orEmpty(),
                rows = values.map { value ->
                    val row = value.jsonObject
                    ChartDataRow(
                        label = row["x"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                        value = row["y"]?.jsonPrimitive?.doubleOrNull?.toString() ?: "0",
                        series = row["series"]?.jsonPrimitive?.contentOrNull ?: "Series 1",
                    )
                }.ifEmpty { ChartBuilderModel().rows },
            )
        }.getOrDefault(ChartBuilderModel())
    }

    private fun encoding(model: ChartBuilderModel): JsonObject = buildJsonObject {
        when (model.type) {
            ChartType.Pie -> {
                put("theta", field("y", "quantitative", model.yAxis))
                put("color", field("x", "nominal", model.xAxis, model.showLegend))
            }
            ChartType.Histogram -> {
                put("x", buildJsonObject {
                    put("field", "y")
                    put("type", "quantitative")
                    put("bin", true)
                    put("title", model.xAxis)
                })
                put("y", buildJsonObject {
                    put("aggregate", "count")
                    put("type", "quantitative")
                    put("title", model.yAxis)
                })
            }
            else -> {
                put("x", field("x", if (model.type == ChartType.Scatter) "quantitative" else "nominal", model.xAxis))
                put("y", field("y", "quantitative", model.yAxis))
                if (model.rows.map(ChartDataRow::series).distinct().size > 1) {
                    put("color", field("series", "nominal", "Series", model.showLegend))
                }
            }
        }
    }

    private fun field(name: String, type: String, title: String, legend: Boolean = true): JsonObject = buildJsonObject {
        put("field", name)
        put("type", type)
        put("title", title)
        if (!legend) put("legend", JsonNull)
    }
}
