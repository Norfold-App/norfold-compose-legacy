package com.norfold.app.domain

import com.norfold.app.domain.style.ColorPaint
import com.norfold.app.domain.style.ColorValue
import com.norfold.app.domain.style.Gradient
import com.norfold.app.domain.style.GradientKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StyleSceneRoundTripTest {
    @Test fun `scene object content participates in canonical tree lookup and replacement`() {
        val text = ParagraphBlock(id = "inner", content = listOf(InlineText("before")))
        val scene = SceneObject(id = "object", kind = SceneObjectKind.TextFrame, content = text)
        val page = DocumentPageBlock(id = "page", objects = listOf(scene))
        val document = BlockDocument(listOf(page))
        assertNotNull(document.findById("inner"))
        assertTrue("inner" in document.allIds())
        val updated = BlockDocument(document.blocks.updateBlock("inner") {
            (it as ParagraphBlock).copy(content = listOf(InlineText("after")))
        })
        assertEquals("after", (updated.findById("inner") as ParagraphBlock).content.plainText())
    }

    @Test fun `page gradient survives production document payload round trip`() {
        val paint = ColorPaint.Ramp(
            Gradient.of(ColorValue(1, 2, 3), ColorValue(200, 210, 220), GradientKind.RADIAL),
        )
        val page = DocumentPageBlock(
            id = "page",
            geometry = PageGeometry.a4().copy(backgroundPaint = paint),
        )
        val decoded = BlockDocumentJson.decodeDocumentPayload(
            BlockDocumentJson.encodeDocumentPayload(BlockDocument(listOf(page))),
        )
        val reopened = decoded.findById("page") as DocumentPageBlock
        assertEquals(paint, reopened.geometry.backgroundPaint)
        assertEquals(paint, reopened.geometry.effectiveBackgroundPaint())
    }
}
