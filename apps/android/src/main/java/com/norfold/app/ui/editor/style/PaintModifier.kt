package com.norfold.app.ui.editor.style

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.rotate
import com.norfold.app.domain.style.ColorPaint
import com.norfold.app.domain.style.GradientKind
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The canonical paint-to-[Brush] mapping.
 *
 * One function, because a second one is how a gradient ends up looking different depending on whether it
 * was painted behind a block or into a vector outline. Callers that fill a path use it directly; callers
 * that fill a rectangle go through [backgroundPaint], which is this plus the conic draw-time rotation.
 */
fun ColorPaint.toBrush(size: Size): Brush {
    val flat = this as? ColorPaint.Flat
    if (flat != null) return SolidColor(Color(flat.color.toArgb()))
    val gradient = (this as ColorPaint.Ramp).gradient.normalized()
    val stops = gradient.sortedStops.map { it.position to Color(it.effectiveColor().toArgb()) }.toTypedArray()
    val center = gradientCenter(size)
    return when (gradient.kind) {
        GradientKind.LINEAR -> {
            val radians = Math.toRadians(gradient.angle.toDouble())
            val distance = sqrt(size.width * size.width + size.height * size.height) / 2f
            val direction = Offset(sin(radians).toFloat(), -cos(radians).toFloat())
            Brush.linearGradient(
                colorStops = stops,
                start = center - direction * distance,
                end = center + direction * distance,
            )
        }
        GradientKind.RADIAL -> Brush.radialGradient(
            colorStops = stops,
            center = center,
            radius = maxOf(size.width, size.height) * gradient.radius,
        )
        GradientKind.CONIC -> Brush.sweepGradient(colorStops = stops, center = center)
    }
}

/** Where a ramp's geometry is anchored; the centre of the box for a flat paint, which never reads it. */
fun ColorPaint.gradientCenter(size: Size): Offset {
    val gradient = (this as? ColorPaint.Ramp)?.gradient?.normalized()
        ?: return Offset(size.width / 2f, size.height / 2f)
    return Offset(size.width * gradient.centerX, size.height * gradient.centerY)
}

/**
 * The draw-time rotation a paint needs, in degrees.
 *
 * Compose's sweep gradient has no angle, so a conic ramp's angle is applied by rotating the draw rather
 * than the brush. Every other paint needs none, and returns zero.
 */
fun ColorPaint.sweepRotationDegrees(): Float {
    val gradient = (this as? ColorPaint.Ramp)?.gradient?.normalized() ?: return 0f
    return if (gradient.kind == GradientKind.CONIC) gradient.angle else 0f
}

/** Draws canonical flat/linear/radial/conic paint without leaking Compose Brush into persistence. */
fun Modifier.backgroundPaint(paint: ColorPaint): Modifier = drawWithCache {
    val brush = paint.toBrush(size)
    val rotation = paint.sweepRotationDegrees()
    val center = paint.gradientCenter(size)
    onDrawBehind {
        if (rotation != 0f) rotate(rotation, center) { drawRect(brush) } else drawRect(brush)
    }
}
