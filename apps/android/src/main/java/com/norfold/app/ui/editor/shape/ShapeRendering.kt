package com.norfold.app.ui.editor.shape

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.translate
import com.norfold.app.editor.shape.ShapeBlendMode
import com.norfold.app.editor.shape.ShapeCanvas
import com.norfold.app.editor.shape.ShapeDocument
import com.norfold.app.editor.shape.ShapeFillRule
import com.norfold.app.editor.shape.ShapeLayer
import com.norfold.app.editor.shape.ShapeMask
import com.norfold.app.editor.shape.ShapeMaskScope
import com.norfold.app.editor.shape.ShapePath
import com.norfold.app.editor.shape.ShapePoint
import com.norfold.app.editor.shape.ShapeStrokeCap
import com.norfold.app.editor.shape.ShapeStrokeJoin
import com.norfold.app.ui.editor.style.toBrush

/**
 * Drawing a [ShapeDocument] with Compose.
 *
 * This file is the only place the canonical geometry meets the toolkit. Everything above it — documents,
 * paths, Boolean groups, the editor — is pure JVM and testable without a device, which is what lets the
 * shape model be certified off-device (§64). Nothing here decides *what* a shape is; it only paints one.
 */

/**
 * Maps the shape's local canvas onto a box on screen, and screen touches back onto the canvas.
 *
 * The mapping is deliberately non-uniform: a shape's interior lives in a [ShapeCanvas.SIDE] square that its
 * placement box stretches, so a rounded rectangle in a wide box has correspondingly wide corners. Editing
 * has to obey the same rule, or a node would land somewhere other than where it was dropped once the shape
 * was placed back on the page.
 */
data class ShapeViewport(val width: Float, val height: Float) {
    val scaleX: Float get() = if (width > 0f) width / ShapeCanvas.SIDE else 1f
    val scaleY: Float get() = if (height > 0f) height / ShapeCanvas.SIDE else 1f

    /** The scale for lengths that have no axis — hit radii, handle dots, stroke widths. */
    val uniformScale: Float get() = minOf(scaleX, scaleY)

    fun toScreen(point: ShapePoint): Offset = Offset(point.x * scaleX, point.y * scaleY)

    fun toCanvas(offset: Offset): ShapePoint = ShapePoint(offset.x / scaleX, offset.y / scaleY)

    /** A screen delta as a canvas delta. Deltas carry no origin, so they scale without translating. */
    fun toCanvasDelta(delta: Offset): ShapePoint = ShapePoint(delta.x / scaleX, delta.y / scaleY)

    val canvasSize: Size get() = Size(width, height)
}

/** Builds the toolkit path for one canonical path. Handles are offsets, so they resolve to absolutes here. */
fun ShapePath.toComposePath(viewport: ShapeViewport): Path {
    val path = Path()
    path.fillType = when (fillRule) {
        ShapeFillRule.NON_ZERO -> PathFillType.NonZero
        ShapeFillRule.EVEN_ODD -> PathFillType.EvenOdd
    }
    subpaths.filter { it.isRenderable }.forEach { subpath ->
        val first = viewport.toScreen(subpath.nodes.first().point)
        path.moveTo(first.x, first.y)
        subpath.segments().forEach { segment ->
            val c1 = viewport.toScreen(segment.control1)
            val c2 = viewport.toScreen(segment.control2)
            val end = viewport.toScreen(segment.end)
            path.cubicTo(c1.x, c1.y, c2.x, c2.y, end.x, end.y)
        }
        if (subpath.closed) path.close()
    }
    return path
}

fun ShapeBlendMode.composeBlendMode(): BlendMode = when (this) {
    ShapeBlendMode.NORMAL -> BlendMode.SrcOver
    ShapeBlendMode.MULTIPLY -> BlendMode.Multiply
    ShapeBlendMode.SCREEN -> BlendMode.Screen
    ShapeBlendMode.OVERLAY -> BlendMode.Overlay
    ShapeBlendMode.DARKEN -> BlendMode.Darken
    ShapeBlendMode.LIGHTEN -> BlendMode.Lighten
}

private fun ShapeStrokeCap.composeCap(): StrokeCap = when (this) {
    ShapeStrokeCap.BUTT -> StrokeCap.Butt
    ShapeStrokeCap.ROUND -> StrokeCap.Round
    ShapeStrokeCap.SQUARE -> StrokeCap.Square
}

private fun ShapeStrokeJoin.composeJoin(): StrokeJoin = when (this) {
    ShapeStrokeJoin.MITER -> StrokeJoin.Miter
    ShapeStrokeJoin.ROUND -> StrokeJoin.Round
    ShapeStrokeJoin.BEVEL -> StrokeJoin.Bevel
}

/** One layer prepared for drawing: its resolved outline, plus every mask that clips it. */
data class ShapeRenderLayer(
    val layer: ShapeLayer,
    val path: ShapePath,
    val clips: List<ShapeMask>,
)

/**
 * Resolves the stack into draw order, bottom first, with each layer's clipping already worked out.
 *
 * A mask scoped [ShapeMaskScope.BELOW] clips every layer beneath it — something a layer cannot discover
 * while it is being drawn, because the mask belongs to a layer that has not been reached yet. Resolving it
 * here, by looking at what sits above each layer, keeps the draw pass a single forward walk.
 *
 * Boolean layers resolve their whole group to produce an outline, which is real work — but the resolve is
 * memoised on the content, so calling this again for an unchanged stack costs a walk and no geometry.
 */
fun ShapeDocument.renderLayers(): List<ShapeRenderLayer> {
    val visible = layers.filter { it.visible }
    val result = ArrayList<ShapeRenderLayer>(visible.size)
    visible.forEachIndexed { index, layer ->
        val path = layer.content.toPath()
        if (path.isEmpty) return@forEachIndexed
        val fromAbove = visible.drop(index + 1)
            .map { it.mask }
            .filter { it.enabled && it.scope == ShapeMaskScope.BELOW }
        val own = layer.mask.takeIf { it.enabled && it.scope == ShapeMaskScope.LAYER }
        result += ShapeRenderLayer(layer, path, fromAbove + listOfNotNull(own))
    }
    return result
}

/** Draws a prepared stack in order. Blend modes resolve against what is already in this draw scope. */
fun DrawScope.drawShapeLayers(layers: List<ShapeRenderLayer>, viewport: ShapeViewport) {
    layers.forEach { item ->
        withMasks(item.clips, viewport) { drawShapeLayer(item.layer, item.path, viewport) }
    }
}

private fun DrawScope.withMasks(masks: List<ShapeMask>, viewport: ShapeViewport, body: DrawScope.() -> Unit) {
    val mask = masks.firstOrNull() ?: return body()
    clipPath(maskPath(mask, viewport)) { withMasks(masks.drop(1), viewport, body) }
}

/**
 * An inverted mask is the box minus the mask outline, computed as a real path difference.
 *
 * Compose can clip to a path but not to its complement, and approximating the complement with a second
 * rectangle would leave the corners wrong on any non-convex mask.
 */
private fun maskPath(mask: ShapeMask, viewport: ShapeViewport): Path {
    val path = mask.path.toComposePath(viewport)
    if (!mask.inverted) return path
    val whole = Path().apply { addRect(Rect(0f, 0f, viewport.width, viewport.height)) }
    return Path().apply { op(whole, path, PathOperation.Difference) }
}

private fun DrawScope.drawShapeLayer(layer: ShapeLayer, canonical: ShapePath, viewport: ShapeViewport) {
    val blend = layer.blendMode.composeBlendMode()
    val alpha = layer.opacity
    val outline = canonical.toComposePath(viewport)
    val fillable = canonical.copy(subpaths = canonical.subpaths.filter { it.closed })
    val region = if (fillable.isEmpty) null else fillable.toComposePath(viewport)
    val effects = layer.effects

    if (effects.glow.enabled && region != null) {
        val glow = Color(effects.glow.color.toArgb())
        softenedEdge(region, glow, effects.glow.blurRadius * viewport.uniformScale, alpha * effects.glow.strength, blend)
    }
    if (effects.shadow.enabled && region != null) {
        val shadow = Color(effects.shadow.color.toArgb())
        translate(effects.shadow.offsetX * viewport.scaleX, effects.shadow.offsetY * viewport.scaleY) {
            drawPath(region, shadow, alpha = alpha, style = Fill, blendMode = blend)
            softenedEdge(region, shadow, effects.shadow.blurRadius * viewport.uniformScale, alpha, blend)
        }
    }
    if (region != null) {
        drawPath(region, layer.fill.toBrush(viewport.canvasSize), alpha = alpha, style = Fill, blendMode = blend)
        if (effects.blurRadius > 0f) {
            val edge = Color(layer.fill.representative().toArgb())
            softenedEdge(region, edge, effects.blurRadius * viewport.uniformScale, alpha, blend)
        }
    }
    if (layer.stroke.enabled && layer.stroke.width > 0f) {
        drawPath(
            outline,
            layer.stroke.paint.toBrush(viewport.canvasSize),
            alpha = alpha,
            style = Stroke(
                width = layer.stroke.width * viewport.uniformScale,
                cap = layer.stroke.cap.composeCap(),
                join = layer.stroke.join.composeJoin(),
            ),
            blendMode = blend,
        )
    }
}

/**
 * A soft halo around an outline, built from concentric translucent strokes.
 *
 * A true Gaussian blur inside a draw scope needs a platform mask filter, which is not dependable across the
 * API levels this build supports, and `Modifier.blur` only takes effect on API 31 and above — a shadow that
 * silently loses its softness on an older phone is worse than one that is honestly approximate everywhere.
 * These passes widen and fade with the radius, so the control does what it says on every device; a
 * device-quality blur is a follow-up, not a claim made here.
 */
private fun DrawScope.softenedEdge(path: Path, color: Color, radiusPx: Float, alpha: Float, blend: BlendMode) {
    if (radiusPx <= 0f || alpha <= 0f) return
    for (pass in 1..SOFT_EDGE_PASSES) {
        val fraction = pass.toFloat() / SOFT_EDGE_PASSES
        drawPath(
            path,
            color,
            alpha = alpha * (1f - fraction) * SOFT_EDGE_STRENGTH,
            style = Stroke(width = radiusPx * fraction * 2f, cap = StrokeCap.Round, join = StrokeJoin.Round),
            blendMode = blend,
        )
    }
}

private const val SOFT_EDGE_PASSES = 4
private const val SOFT_EDGE_STRENGTH = 0.55f

/** A read-only rendering of a whole document, for previews, pickers and the layer list. */
@Composable
fun ShapeDocumentPreview(document: ShapeDocument, modifier: Modifier = Modifier) {
    val prepared = remember(document) { document.renderLayers() }
    Canvas(modifier) {
        drawShapeLayers(prepared, ShapeViewport(size.width, size.height))
    }
}

