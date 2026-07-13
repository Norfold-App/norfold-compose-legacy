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
    fun `malformed source falls back to a usable chart`() {
        val decoded = ChartSpecCodec.decode("not-json")

        assertEquals(ChartType.Bar, decoded.type)
        assertEquals("Chart", decoded.title)
        assertTrue(decoded.rows.isNotEmpty())
    }
}
