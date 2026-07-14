package com.norfold.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChartSpecCodecTest {
    @Test
    fun `all chart types preserve editable builder data`() {
        ChartType.entries.forEach { type ->
            val input = ChartBuilderModel(
                type = type,
                title = "Quarterly ${type.label}",
                xAxis = "Quarter",
                yAxis = "Revenue",
                showLegend = false,
                color = "#00A896",
                rows = listOf(
                    ChartDataRow(label = "Q1", value = "12.5", series = "Actual"),
                    ChartDataRow(label = "Q2", value = "18", series = "Forecast"),
                ),
            )

            val encoded = ChartSpecCodec.encode(input)
            val decoded = ChartSpecCodec.decode(encoded)

            assertEquals(type, decoded.type)
            assertEquals(input.title, decoded.title)
            assertEquals(input.xAxis, decoded.xAxis)
            assertEquals(input.yAxis, decoded.yAxis)
            assertFalse(decoded.showLegend)
            assertEquals(input.color, decoded.color)
            assertEquals(listOf("Q1", "Q2"), decoded.rows.map { it.label })
            assertEquals(listOf(12.5, 18.0), decoded.rows.map { it.value.toDouble() })
            assertEquals(listOf("Actual", "Forecast"), decoded.rows.map { it.series })
            assertTrue(encoded.contains("vega-lite"))
        }
    }

    @Test
    fun `standard Vega Lite mark decodes without Norfold metadata`() {
        val decoded = ChartSpecCodec.decode(
            """{"title":"Imported","mark":"line","data":{"values":[{"x":"Mon","y":4}]}}""",
        )

        assertEquals(ChartType.Line, decoded.type)
        assertEquals("Imported", decoded.title)
        assertEquals("Mon", decoded.rows.single().label)
        assertEquals(4.0, decoded.rows.single().value.toDouble(), 0.0)
    }

    @Test
    fun `caption round trips through the spec and Vega title subtitle`() {
        val input = ChartBuilderModel(
            title = "Revenue",
            caption = "FY26 actuals vs forecast",
            rows = listOf(ChartDataRow(label = "Q1", value = "10")),
        )

        val encoded = ChartSpecCodec.encode(input)
        val decoded = ChartSpecCodec.decode(encoded)

        assertEquals("Revenue", decoded.title)
        assertEquals("FY26 actuals vs forecast", decoded.caption)
        assertTrue(encoded.contains("\"subtitle\":\"FY26 actuals vs forecast\""))
    }

    @Test
    fun `subtitle only specs still surface a caption`() {
        val decoded = ChartSpecCodec.decode(
            """{"title":{"text":"Imported","subtitle":"From Vega"},"mark":"bar","data":{"values":[{"x":"A","y":1}]}}""",
        )

        assertEquals("Imported", decoded.title)
        assertEquals("From Vega", decoded.caption)
    }

    @Test
    fun `per row colors round trip and drive the color scale range`() {
        val input = ChartBuilderModel(
            type = ChartType.Pie,
            color = "#123456",
            rows = listOf(
                ChartDataRow(label = "A", value = "3", color = "#FF0000"),
                ChartDataRow(label = "B", value = "7", color = ""),
            ),
        )

        val encoded = ChartSpecCodec.encode(input)
        val decoded = ChartSpecCodec.decode(encoded)

        assertEquals(listOf("#FF0000", ""), decoded.rows.map { it.color })
        // Blank row colors inherit the global default inside the encoded color scale.
        assertTrue(encoded.contains("\"range\":[\"#FF0000\",\"#123456\"]"))
    }

    @Test
    fun `malformed source falls back to a usable chart`() {
        val decoded = ChartSpecCodec.decode("not-json")

        assertEquals(ChartType.Bar, decoded.type)
        assertEquals("Chart", decoded.title)
        assertTrue(decoded.rows.isNotEmpty())
    }
}
