package com.norfold.app.editor.style

import com.norfold.app.domain.BlockDocument
import com.norfold.app.domain.BlockDocumentJson
import com.norfold.app.domain.DocumentPageBlock
import com.norfold.app.domain.HeadingBlock
import com.norfold.app.domain.PageGeometry
import com.norfold.app.domain.ParagraphBlock
import com.norfold.app.domain.ShapeBlock
import com.norfold.app.domain.findById
import com.norfold.app.domain.stylesheet
import com.norfold.app.domain.style.ColorPaint
import com.norfold.app.domain.style.ColorRole
import com.norfold.app.domain.style.ColorValue
import com.norfold.app.domain.style.DocumentToken
import com.norfold.app.domain.style.FontIdentity
import com.norfold.app.domain.style.StyleAssignment
import com.norfold.app.domain.style.StyleProperty
import com.norfold.app.domain.style.StyleValue
import com.norfold.app.domain.style.Stylesheet
import com.norfold.app.domain.style.resolvedPageBackground
import com.norfold.app.domain.style.resolvedShapeFill
import com.norfold.app.domain.style.resolvedShapeStroke
import com.norfold.app.editor.flow.FlowEditController
import com.norfold.app.editor.flow.FlowHostRuntime
import com.norfold.app.editor.history.EditTarget
import com.norfold.app.editor.presentation.FormFactor
import com.norfold.app.ui.editor.style.collapsedBlockSpacing
import com.norfold.app.ui.editor.style.resolvedBlockMetrics
import com.norfold.app.ui.editor.style.resolvedLineHeightSp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TokenMixedSemanticMetricsTest {
    private fun runtime(document: BlockDocument): FlowHostRuntime = FlowHostRuntime(
        FlowEditController(EditTarget("phase3"), document),
        FormFactor(390, 840),
    )

    @Test fun `shape fill and stroke TokenRefs resolve and update without rewriting shape`() {
        val fill = DocumentToken.color("Fill", ColorValue(1, 2, 3), id = "fill")
        val stroke = DocumentToken.color("Stroke", ColorValue(4, 5, 6), id = "stroke")
        val shape = ShapeBlock(
            id = "shape",
            style = StyleAssignment()
                .override(StyleProperty.FILL, StyleValue.token(fill.id))
                .override(StyleProperty.STROKE_COLOR, StyleValue.token(stroke.id)),
        )
        val rt = runtime(BlockDocument(listOf(shape)))
        assertTrue(rt.styleController.createToken(fill))
        assertTrue(rt.styleController.createToken(stroke))
        val storedBefore = rt.controller.document().findById(shape.id)
        assertEquals(ColorPaint.Flat(ColorValue(1, 2, 3)), resolvedShapeFill(shape, rt.styleController.stylesheet()))
        assertEquals(ColorPaint.Flat(ColorValue(4, 5, 6)), resolvedShapeStroke(shape, rt.styleController.stylesheet()))
        assertTrue(rt.styleController.setTokenColor(fill.id, ColorValue(9, 8, 7)))
        val storedAfter = rt.controller.document().findById(shape.id)
        assertEquals(storedBefore, storedAfter)
        assertEquals(
            ColorPaint.Flat(ColorValue(9, 8, 7)),
            resolvedShapeFill(storedAfter as ShapeBlock, rt.styleController.stylesheet()),
        )
    }

    @Test fun `page token binds renames updates and round trips`() {
        val page = DocumentPageBlock(id = "page", geometry = PageGeometry.a4())
        val token = DocumentToken.color("Paper", ColorValue(20, 30, 40), id = "paper")
        val rt = runtime(BlockDocument(listOf(page)))
        assertTrue(rt.styleController.createToken(token))
        val depth = rt.controller.undoDepth()
        assertEquals(1, rt.styleController.bindPageBackgroundToToken(listOf(page.id), token.id))
        assertEquals(depth + 1, rt.controller.undoDepth())
        assertTrue(rt.styleController.renameToken(token.id, "Canvas"))
        assertTrue(rt.styleController.setTokenColor(token.id, ColorValue(60, 70, 80)))
        val bound = rt.controller.document().findById(page.id) as DocumentPageBlock
        assertEquals(token.id, bound.geometry.backgroundTokenId)
        assertEquals(ColorPaint.Flat(ColorValue(60, 70, 80)), resolvedPageBackground(bound, rt.styleController.stylesheet()))
        val reopened = BlockDocumentJson.decodeDocumentPayload(BlockDocumentJson.encodeDocumentPayload(rt.controller.document()))
        val roundTripped = reopened.findById(page.id) as DocumentPageBlock
        assertEquals(token.id, roundTripped.geometry.backgroundTokenId)
        assertEquals(ColorPaint.Flat(ColorValue(60, 70, 80)), resolvedPageBackground(roundTripped, reopened.stylesheet()))
    }

    @Test fun `referenced page token refuses unresolved deletion and detach preserves paint`() {
        val page = DocumentPageBlock(id = "page")
        val token = DocumentToken.color("Paper", ColorValue(2, 4, 8), id = "paper")
        val rt = runtime(BlockDocument(listOf(page)))
        rt.styleController.createToken(token)
        rt.styleController.bindPageBackgroundToToken(listOf(page.id), token.id)
        assertFalse(rt.styleController.deleteToken(token.id, detach = false))
        assertTrue(rt.styleController.deleteToken(token.id, detach = true))
        val detached = rt.controller.document().findById(page.id) as DocumentPageBlock
        assertNull(detached.geometry.backgroundTokenId)
        assertEquals(ColorPaint.Flat(ColorValue(2, 4, 8)), resolvedPageBackground(detached, rt.styleController.stylesheet()))
    }

    @Test fun `detaching a shape token preserves its last resolved paint`() {
        val token = DocumentToken.color("Fill", ColorValue(11, 22, 33), id = "fill")
        val shape = ShapeBlock(
            id = "shape",
            fillPaint = ColorPaint.Flat(ColorValue(99, 99, 99)),
            style = StyleAssignment().override(StyleProperty.FILL, StyleValue.token(token.id)),
        )
        val rt = runtime(BlockDocument(listOf(shape)))
        rt.styleController.createToken(token)
        assertTrue(rt.styleController.deleteToken(token.id, detach = true))
        val detached = rt.controller.document().findById(shape.id) as ShapeBlock
        assertEquals(ColorPaint.Flat(ColorValue(11, 22, 33)), resolvedShapeFill(detached, rt.styleController.stylesheet()))
    }

    @Test fun `page token replacement repoints every selected page atomically`() {
        val pages = listOf(DocumentPageBlock(id = "p1"), DocumentPageBlock(id = "p2"))
        val old = DocumentToken.color("Old", ColorValue(1, 1, 1), id = "old")
        val replacement = DocumentToken.color("New", ColorValue(7, 7, 7), id = "new")
        val rt = runtime(BlockDocument(pages))
        rt.styleController.createToken(old)
        rt.styleController.createToken(replacement)
        val beforeBind = rt.controller.undoDepth()
        assertEquals(2, rt.styleController.bindPageBackgroundToToken(pages.map { it.id }, old.id))
        assertEquals(beforeBind + 1, rt.controller.undoDepth())
        assertTrue(rt.styleController.deleteToken(old.id, detach = false, replacementId = replacement.id))
        pages.forEach { page ->
            assertEquals(replacement.id, (rt.controller.document().findById(page.id) as DocumentPageBlock).geometry.backgroundTokenId)
        }
    }

    @Test fun `mixed colors fonts and gradients are represented honestly`() {
        val p1 = ParagraphBlock(id = "p1", style = StyleAssignment().override(StyleProperty.TEXT_COLOR, StyleValue.of(ColorValue(1, 2, 3))))
        val p2 = ParagraphBlock(
            id = "p2",
            style = StyleAssignment()
                .override(StyleProperty.TEXT_COLOR, StyleValue.of(ColorValue(4, 5, 6)))
                .override(StyleProperty.FONT_FAMILY, StyleValue.of(FontIdentity.builtIn("jetbrains-mono", "JetBrains Mono"))),
        )
        val s1 = ShapeBlock(id = "s1", fillPaint = ColorPaint.Flat(ColorValue(10, 10, 10)))
        val s2 = ShapeBlock(id = "s2", fillPaint = ColorPaint.Flat(ColorValue(20, 20, 20)))
        val rt = runtime(BlockDocument(listOf(p1, p2, s1, s2)))
        rt.scope.selectBlocks(listOf(p1.id, p2.id))
        assertTrue(rt.editorStyle.openColor(ColorRole.TEXT))
        assertTrue(rt.editorStyle.colorStudio.state().isMixed)
        assertTrue(rt.editorStyle.openFont())
        assertTrue(rt.editorStyle.fontBrowser.state().mixedFonts)
        assertNull(rt.editorStyle.fontBrowser.state().selected)
        rt.scope.selectBlocks(listOf(s1.id, s2.id))
        assertTrue(rt.editorStyle.openColor(ColorRole.FILL))
        assertTrue(rt.editorStyle.colorStudio.state().isMixed)
    }

    @Test fun `mixed page backgrounds are not seeded from the first page`() {
        val p1 = DocumentPageBlock(id = "p1", geometry = PageGeometry(backgroundPaint = ColorPaint.Flat(ColorValue(1, 2, 3))))
        val p2 = DocumentPageBlock(id = "p2", geometry = PageGeometry(backgroundPaint = ColorPaint.Flat(ColorValue(4, 5, 6))))
        val rt = runtime(BlockDocument(listOf(p1, p2)))
        val editor = EditorStyleRuntime(
            host = "document",
            controller = rt.styleController,
            registry = EditorStyleRuntime.EMPTY_REGISTRY,
            selection = { EditorStyleSelection(pageIds = listOf(p1.id, p2.id)) },
            onChanged = {},
        )
        assertTrue(editor.openColor(ColorRole.PAGE_BACKGROUND))
        assertTrue(editor.colorStudio.state().isMixed)
    }

    @Test fun `explicit common color across mixed selection is one transaction`() {
        val p1 = ParagraphBlock(id = "p1", style = StyleAssignment().override(StyleProperty.TEXT_COLOR, StyleValue.of(ColorValue(1, 2, 3))))
        val p2 = ParagraphBlock(id = "p2", style = StyleAssignment().override(StyleProperty.TEXT_COLOR, StyleValue.of(ColorValue(4, 5, 6))))
        val rt = runtime(BlockDocument(listOf(p1, p2)))
        rt.scope.selectBlocks(listOf(p1.id, p2.id))
        rt.editorStyle.openColor(ColorRole.TEXT)
        val depth = rt.controller.undoDepth()
        assertTrue(rt.editorStyle.colorInteractions.chooseExplicitColor(ColorValue(30, 40, 50)))
        assertEquals(depth + 1, rt.controller.undoDepth())
        assertFalse(rt.editorStyle.colorStudio.state().isMixed)
    }

    @Test fun `semantic styles refuse incompatible structures atomically`() {
        val paragraph = ParagraphBlock(id = "p")
        val heading1 = HeadingBlock(id = "h1", level = 1)
        val heading2 = HeadingBlock(id = "h2", level = 2)
        val rt = runtime(BlockDocument(listOf(paragraph, heading1, heading2)))
        assertFalse(rt.styleController.styleApplicationStatus(listOf(paragraph.id), Stylesheet.headingId(1)).enabled)
        assertFalse(rt.styleController.styleApplicationStatus(listOf(heading1.id), Stylesheet.headingId(2)).enabled)
        assertFalse(rt.styleController.styleApplicationStatus(listOf(heading1.id), Stylesheet.BODY).enabled)
        val depth = rt.controller.undoDepth()
        assertEquals(0, rt.styleController.applyStyle(listOf(heading1.id, heading2.id), Stylesheet.headingId(1)))
        assertEquals(depth, rt.controller.undoDepth())
        assertEquals(0, rt.styleController.applyStyle(listOf(heading1.id), Stylesheet.headingId(1)))
        assertEquals("The implicit matching semantic style is already active", depth, rt.controller.undoDepth())
    }

    @Test fun `line height ratios and block spacing resolve with max collapse`() {
        assertEquals(16.5f, resolvedLineHeightSp(1.5f, 11f)!!, 0.001f)
        assertEquals(36.8f, resolvedLineHeightSp(1.15f, 32f)!!, 0.001f)
        assertEquals(23.4f, resolvedLineHeightSp(1.3f, 18f)!!, 0.001f)
        assertEquals(30f, resolvedLineHeightSp(1.5f, 20f)!!, 0.001f)
        val body = ParagraphBlock(id = "body")
        val heading = HeadingBlock(id = "heading", level = 1)
        val sheet = Stylesheet.defaults()
        val bodyMetrics = resolvedBlockMetrics(body, sheet)
        val headingMetrics = resolvedBlockMetrics(heading, sheet)
        assertEquals(8f, bodyMetrics.afterDp, 0.001f)
        assertEquals(16f, headingMetrics.beforeDp, 0.001f)
        assertEquals(16f, collapsedBlockSpacing(bodyMetrics.afterDp, headingMetrics.beforeDp), 0.001f)
        val updated = sheet.withToken(sheet.token(Stylesheet.TOKEN_SPACING_BLOCK)!!.copy(value = "24"))
        assertEquals(24f, resolvedBlockMetrics(body, updated).afterDp, 0.001f)
    }
}
