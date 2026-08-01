package com.norfold.app.domain.style

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ColorDomainContractTest {
    @Test fun `canonical domain set contains exactly the five locked domains`() {
        assertEquals(
            setOf(
                ColorDomain.APP_CHROME,
                ColorDomain.CONTENT,
                ColorDomain.DATA_VISUALIZATION,
                ColorDomain.COLLABORATOR_IDENTITY,
                ColorDomain.SEMANTIC_SIGNAL,
            ),
            ColorDomain.entries.toSet(),
        )
    }

    @Test fun `data visualization keeps its stable serialized value and chart role`() {
        val encoded = Json.encodeToString(ColorDomain.DATA_VISUALIZATION)
        assertEquals("\"dataviz\"", encoded)
        assertEquals(ColorDomain.DATA_VISUALIZATION, Json.decodeFromString<ColorDomain>(encoded))
        assertEquals(ColorDomain.DATA_VISUALIZATION, ColorRole.CHART_SERIES.domain)
    }

    @Test fun `only content and data visualization are document token compatible`() {
        assertTrue(ColorDomain.CONTENT.tokenizable)
        assertTrue(ColorDomain.DATA_VISUALIZATION.tokenizable)
        assertFalse(ColorDomain.APP_CHROME.tokenizable)
        assertFalse(ColorDomain.COLLABORATOR_IDENTITY.tokenizable)
        assertFalse(ColorDomain.SEMANTIC_SIGNAL.tokenizable)
    }

    @Test fun `ordinary stylesheet rejects non document color domains`() {
        val color = ColorValue(12, 34, 56)
        var sheet = Stylesheet()
        sheet = sheet.withToken(DocumentToken.color("Content", color, ColorDomain.CONTENT, "content"))
        sheet = sheet.withToken(DocumentToken.color("Series", color, ColorDomain.DATA_VISUALIZATION, "series"))
        sheet = sheet.withToken(DocumentToken.color("Chrome", color, ColorDomain.APP_CHROME, "chrome"))
        sheet = sheet.withToken(DocumentToken.color("Person", color, ColorDomain.COLLABORATOR_IDENTITY, "person"))
        sheet = sheet.withToken(DocumentToken.color("Signal", color, ColorDomain.SEMANTIC_SIGNAL, "signal"))

        assertEquals(setOf("content", "series"), sheet.tokens.map { it.id }.toSet())
        assertEquals(ColorDomain.CONTENT, ColorRole.TEXT.domain)
        assertFalse(
            "Selection is a UI/selection-engine concern, not a persisted color domain",
            ColorDomain.entries.any { it.name.contains("SELECTION") },
        )
    }
}
