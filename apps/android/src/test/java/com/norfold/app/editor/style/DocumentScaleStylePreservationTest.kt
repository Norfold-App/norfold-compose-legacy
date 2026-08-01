package com.norfold.app.editor.style

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.sp
import com.norfold.app.domain.BlockDocument
import com.norfold.app.domain.CodeBlock
import com.norfold.app.domain.HeadingBlock
import com.norfold.app.domain.ParagraphBlock
import com.norfold.app.domain.QuoteBlock
import com.norfold.app.domain.SceneGeometry
import com.norfold.app.domain.ShapeBlock
import com.norfold.app.domain.effectiveStyleAssignment
import com.norfold.app.domain.findById
import com.norfold.app.domain.style.ColorPaint
import com.norfold.app.domain.style.ColorValue
import com.norfold.app.domain.style.DeleteStylePolicy
import com.norfold.app.domain.style.DocumentToken
import com.norfold.app.domain.style.Gradient
import com.norfold.app.domain.style.StyleAssignment
import com.norfold.app.domain.style.StyleProperty
import com.norfold.app.domain.style.StyleValue
import com.norfold.app.domain.style.Stylesheet
import com.norfold.app.domain.style.resolvedShapeFill
import com.norfold.app.domain.style.resolvedShapeStroke
import com.norfold.app.editor.flow.FlowEditController
import com.norfold.app.editor.flow.FlowHostRuntime
import com.norfold.app.editor.history.EditTarget
import com.norfold.app.editor.presentation.FormFactor
import com.norfold.app.ui.editor.document.DP_PER_POINT
import com.norfold.app.ui.editor.document.DocumentRenderScale
import com.norfold.app.ui.editor.style.ResolvedBlockMetrics
import com.norfold.app.ui.editor.style.resolvedBlockMetrics
import com.norfold.app.ui.editor.style.resolvedShapeMetrics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DocumentScaleStylePreservationTest {
    private fun runtime(document: BlockDocument): FlowHostRuntime = FlowHostRuntime(
        FlowEditController(EditTarget("phase3-scale-preservation"), document),
        FormFactor(390, 840),
    )

    @Test fun `page point conversion is 160 over 72 at every zoom`() {
        assertEquals(160f / 72f, DP_PER_POINT, 0.0001f)
        listOf(0.5f, 1f, 2f).forEach { zoom ->
            val scale = DocumentRenderScale.atZoom(zoom)
            assertEquals(DP_PER_POINT * zoom, scale.pointToDp(1f).value, 0.001f)
            assertEquals(DP_PER_POINT * zoom, scale.pointTextSize(1f).value, 0.001f)
        }
    }

    @Test fun `Document point typography and spacing convert exactly once`() {
        val base = TextStyle(fontSize = 12.sp, lineHeight = 18.sp, letterSpacing = 1.5.sp)
        val metrics = ResolvedBlockMetrics(beforeDp = 6f, afterDp = 10f)
        listOf(0.5f, 1f, 2f).forEach { zoom ->
            val factor = DP_PER_POINT * zoom
            val scale = DocumentRenderScale.atZoom(zoom)
            val rendered = scale.pointTextStyle(base)
            assertEquals(12f * factor, rendered.fontSize.value, 0.001f)
            assertEquals(18f * factor, rendered.lineHeight.value, 0.001f)
            assertEquals(1.5f * factor, rendered.letterSpacing.value, 0.001f)
            assertEquals(11f * factor, scale.pointTextSize(11f).value, 0.001f)
            assertEquals(12f * factor, scale.pointTextSize(12f).value, 0.001f)
            assertEquals(ResolvedBlockMetrics(6f * factor, 10f * factor), scale.pointBlockMetrics(metrics))
            assertEquals(8f * factor, scale.pointToDp(8f).value, 0.001f)
        }
    }

    @Test fun `font and frame preserve their model ratio in Original and Adaptive`() {
        val pageWidthPoints = 595f
        val fontPoints = 11f
        listOf(0.37f, 1f, 2f).forEach { zoom ->
            val scale = DocumentRenderScale.atZoom(zoom)
            val renderedRatio = scale.pointTextSize(fontPoints).value / scale.pointToDp(pageWidthPoints).value
            assertEquals(fontPoints / pageWidthPoints, renderedRatio, 0.00001f)
        }
    }

    @Test fun `Document content scale never mutates object model geometry or Flow metrics`() {
        val geometry = SceneGeometry(x = 10f, y = 20f, width = 300f, height = 120f, rotation = 17f)
        val paragraph = ParagraphBlock(id = "p")
        val flowMetrics = resolvedBlockMetrics(paragraph, Stylesheet.defaults())
        DocumentRenderScale.atZoom(0.5f).pointTextStyle(TextStyle(fontSize = 12.sp))
        DocumentRenderScale.atZoom(2f).pointBlockMetrics(flowMetrics)
        assertEquals(SceneGeometry(x = 10f, y = 20f, width = 300f, height = 120f, rotation = 17f), geometry)
        assertEquals(flowMetrics, resolvedBlockMetrics(paragraph, Stylesheet.defaults()))
    }

    @Test fun `read and edit consume the identical scaled text metrics`() {
        val scale = DocumentRenderScale.atZoom(1.75f)
        val resolved = TextStyle(fontSize = 10.sp, lineHeight = 15.sp, letterSpacing = 0.5.sp)
        val read = scale.pointTextStyle(resolved)
        val edit = scale.pointTextStyle(resolved)
        assertEquals(read.fontSize, edit.fontSize)
        assertEquals(read.lineHeight, edit.lineHeight)
        assertEquals(read.letterSpacing, edit.letterSpacing)
        assertEquals(scale.pointTextSize(12f), scale.pointTextStyle(TextStyle(fontSize = 12.sp)).fontSize)
    }

    @Test fun `Document Todo visual scales without inflating content footprint`() {
        listOf(0.5f, 1f, 2f).forEach { zoom ->
            val scale = DocumentRenderScale.atZoom(zoom)
            val metrics = scale.todoMetrics()
            val expected = DocumentRenderScale.DEFAULT_TODO_VISUAL_POINTS * DP_PER_POINT * zoom
            assertEquals(expected, metrics.visualDp, 0.001f)
            assertEquals(metrics.visualDp, metrics.contentFootprintDp, 0.001f)
            assertEquals(DocumentRenderScale.MINIMUM_TODO_TOUCH_TARGET_DP, metrics.minimumTouchTargetDp, 0.001f)
        }
    }

    @Test fun `shape style detach preserves fill stroke and exact undo redo representation`() {
        val blue = ColorPaint.Flat(ColorValue(0, 0, 255))
        val cyan = ColorPaint.Flat(ColorValue(0, 255, 255))
        val red = ColorValue(255, 0, 0)
        val green = ColorValue(0, 180, 0)
        val shape = ShapeBlock(
            id = "shape",
            fillPaint = blue,
            strokePaint = cyan,
            style = StyleAssignment()
                .override(StyleProperty.FILL, StyleValue.of(red))
                .override(StyleProperty.STROKE_COLOR, StyleValue.of(green))
                .override(StyleProperty.STROKE_WIDTH, StyleValue.of(5f))
                .override(StyleProperty.CORNER_RADIUS, StyleValue.of(14f))
                .override(StyleProperty.PADDING, StyleValue.of(9f))
                .override(StyleProperty.SHADOW, StyleValue.of(3f))
                .override(StyleProperty.OPACITY, StyleValue.of(0.75f)),
        )
        val rt = runtime(BlockDocument(listOf(shape)))
        val styleId = rt.styleController.createStyleFromBlock(shape.id, "Red card")!!
        val associated = rt.controller.document().findById(shape.id) as ShapeBlock
        assertEquals(blue, associated.fillPaint)
        val associatedMetrics = resolvedShapeMetrics(associated, rt.styleController.stylesheet())
        val depth = rt.controller.undoDepth()
        assertEquals(1, rt.styleController.detachStyle(listOf(shape.id)))
        assertEquals(depth + 1, rt.controller.undoDepth())
        val detached = rt.controller.document().findById(shape.id) as ShapeBlock
        assertEquals(ColorPaint.Flat(red), resolvedShapeFill(detached, rt.styleController.stylesheet()))
        assertEquals(ColorPaint.Flat(green), resolvedShapeStroke(detached, rt.styleController.stylesheet()))
        assertEquals(associatedMetrics, resolvedShapeMetrics(detached, rt.styleController.stylesheet()))
        assertEquals(null, detached.effectiveStyleAssignment().styleId)
        assertTrue(rt.controller.undo())
        assertEquals(associated, rt.controller.document().findById(shape.id))
        assertEquals(styleId, (rt.controller.document().findById(shape.id) as ShapeBlock).effectiveStyleAssignment().styleId)
        assertTrue(rt.controller.redo())
        assertEquals(detached, rt.controller.document().findById(shape.id))
    }

    @Test fun `style delete preserve keeps visible shape fill and stroke`() {
        val red = ColorValue(220, 20, 30)
        val green = ColorValue(20, 180, 70)
        val shape = ShapeBlock(
            id = "shape",
            fillPaint = ColorPaint.Flat(ColorValue(0, 0, 255)),
            strokePaint = ColorPaint.Flat(ColorValue(10, 20, 30)),
            style = StyleAssignment()
                .override(StyleProperty.FILL, StyleValue.of(red))
                .override(StyleProperty.STROKE_COLOR, StyleValue.of(green)),
        )
        val rt = runtime(BlockDocument(listOf(shape)))
        val styleId = rt.styleController.createStyleFromBlock(shape.id, "Disposable card")!!
        val depth = rt.controller.undoDepth()
        assertTrue(rt.styleController.deleteStyle(styleId, DeleteStylePolicy.PRESERVE_APPEARANCE))
        assertEquals(depth + 1, rt.controller.undoDepth())
        val preserved = rt.controller.document().findById(shape.id) as ShapeBlock
        assertEquals(ColorPaint.Flat(red), resolvedShapeFill(preserved, rt.styleController.stylesheet()))
        assertEquals(ColorPaint.Flat(green), resolvedShapeStroke(preserved, rt.styleController.stylesheet()))
        assertTrue(rt.controller.undo())
        assertEquals(styleId, (rt.controller.document().findById(shape.id) as ShapeBlock).effectiveStyleAssignment().styleId)
        assertTrue(rt.controller.redo())
        assertEquals(preserved, rt.controller.document().findById(shape.id))
    }

    @Test fun `token backed object style materializes current fill on detach`() {
        val token = DocumentToken.color("Brand", ColorValue(80, 90, 100), id = "brand")
        val shape = ShapeBlock(
            id = "shape",
            fillPaint = ColorPaint.Flat(ColorValue(0, 0, 255)),
            style = StyleAssignment().override(StyleProperty.FILL, StyleValue.token(token.id)),
        )
        val rt = runtime(BlockDocument(listOf(shape)))
        rt.styleController.createToken(token)
        rt.styleController.createStyleFromBlock(shape.id, "Token card")!!
        assertEquals(1, rt.styleController.detachStyle(listOf(shape.id)))
        val detached = rt.controller.document().findById(shape.id) as ShapeBlock
        assertEquals(ColorPaint.Flat(ColorValue(80, 90, 100)), resolvedShapeFill(detached, rt.styleController.stylesheet()))
        assertTrue(detached.style.overrides[StyleProperty.FILL.key] is StyleValue.Literal)
        rt.styleController.setTokenColor(token.id, ColorValue(200, 10, 20))
        assertEquals(ColorPaint.Flat(ColorValue(80, 90, 100)), resolvedShapeFill(detached, rt.styleController.stylesheet()))
    }

    @Test fun `direct gradient survives unrelated style detachment`() {
        val gradient = ColorPaint.Ramp(Gradient.of(ColorValue(10, 20, 30), ColorValue(200, 210, 220)))
        val shape = ShapeBlock(
            id = "shape",
            fillPaint = gradient,
            style = StyleAssignment().override(StyleProperty.CORNER_RADIUS, StyleValue.of(24f)),
        )
        val rt = runtime(BlockDocument(listOf(shape)))
        rt.styleController.createStyleFromBlock(shape.id, "Rounded")!!
        assertEquals(1, rt.styleController.detachStyle(listOf(shape.id)))
        val detached = rt.controller.document().findById(shape.id) as ShapeBlock
        assertEquals(gradient, detached.fillPaint)
        assertEquals(gradient, resolvedShapeFill(detached, rt.styleController.stylesheet()))
    }

    @Test fun `Title Subtitle and Caption apply atomically to paragraphs`() {
        listOf(Stylesheet.TITLE, Stylesheet.SUBTITLE, Stylesheet.CAPTION).forEach { styleId ->
            val first = ParagraphBlock(id = "first")
            val second = ParagraphBlock(id = "second")
            val rt = runtime(BlockDocument(listOf(first, second)))
            val depth = rt.controller.undoDepth()
            assertTrue(rt.styleController.styleApplicationStatus(listOf(first.id, second.id), styleId).enabled)
            assertEquals(2, rt.styleController.applyStyle(listOf(first.id, second.id), styleId))
            assertEquals(depth + 1, rt.controller.undoDepth())
            assertEquals(styleId, (rt.controller.document().findById(first.id) as ParagraphBlock).effectiveStyleAssignment().styleId)
            assertEquals(styleId, (rt.controller.document().findById(second.id) as ParagraphBlock).effectiveStyleAssignment().styleId)
            assertTrue(rt.controller.undo())
            assertEquals(Stylesheet.BODY, (rt.controller.document().findById(first.id) as ParagraphBlock).effectiveStyleAssignment().styleId)
            assertTrue(rt.controller.redo())
            assertEquals(styleId, (rt.controller.document().findById(second.id) as ParagraphBlock).effectiveStyleAssignment().styleId)
        }
    }

    @Test fun `presentational paragraph styles reject Heading Quote and Code`() {
        val paragraph = ParagraphBlock(id = "paragraph")
        val blocks = listOf(
            HeadingBlock(id = "heading", level = 1),
            QuoteBlock(id = "quote"),
            CodeBlock(id = "code"),
        )
        val rt = runtime(BlockDocument(listOf(paragraph) + blocks))
        listOf(Stylesheet.TITLE, Stylesheet.SUBTITLE, Stylesheet.CAPTION).forEach { styleId ->
            blocks.forEach { block ->
                assertFalse(rt.styleController.styleApplicationStatus(listOf(block.id), styleId).enabled)
                assertEquals(0, rt.styleController.applyStyle(listOf(block.id), styleId))
            }
        }
        assertFalse(rt.styleController.styleApplicationStatus(listOf("heading"), Stylesheet.headingId(2)).enabled)
        assertFalse(rt.styleController.styleApplicationStatus(listOf("quote"), Stylesheet.CODE).enabled)
        assertFalse(rt.styleController.styleApplicationStatus(listOf("code"), Stylesheet.QUOTE).enabled)
        val depth = rt.controller.undoDepth()
        assertFalse(rt.styleController.styleApplicationStatus(listOf(paragraph.id, "heading"), Stylesheet.TITLE).enabled)
        assertEquals(0, rt.styleController.applyStyle(listOf(paragraph.id, "heading"), Stylesheet.TITLE))
        assertEquals(depth, rt.controller.undoDepth())
    }

    @Test fun `Named Styles UI status matches controller eligibility`() {
        val paragraph = ParagraphBlock(id = "paragraph")
        val heading = HeadingBlock(id = "heading", level = 1)
        val rt = runtime(BlockDocument(listOf(paragraph, heading)))
        rt.scope.selectBlocks(listOf(paragraph.id))
        val paragraphState = rt.editorStyle.uiState().styleNames.associateBy { it.id }
        assertTrue(paragraphState.getValue(Stylesheet.TITLE).enabled)
        assertTrue(paragraphState.getValue(Stylesheet.SUBTITLE).enabled)
        assertTrue(paragraphState.getValue(Stylesheet.CAPTION).enabled)
        assertFalse(paragraphState.getValue(Stylesheet.headingId(1)).enabled)
        rt.scope.selectBlocks(listOf(heading.id))
        val headingState = rt.editorStyle.uiState().styleNames.associateBy { it.id }
        assertFalse(headingState.getValue(Stylesheet.TITLE).enabled)
        assertTrue(headingState.getValue(Stylesheet.headingId(1)).reason == "Already applied")
    }
}
