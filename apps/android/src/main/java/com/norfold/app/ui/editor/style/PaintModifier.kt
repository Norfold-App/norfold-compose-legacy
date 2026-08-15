package com.norfold.app.ui.editor.style

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import com.norfold.app.domain.style.ColorPaint
import com.norfold.app.domain.style.GradientKind
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The canonical paint-to-[Brush] mapping.
 *
 * One function, because a second one is how a gradient ends up looking different depending on whether it
 * was painted behind a block or into a vector outline. Every angle a ramp has is carried by the brush
 * itself — see [com.norfold.app.domain.style.Gradient.renderStops] for why a conic ramp's angle cannot be
 * applied by rotating the draw — so a caller only has to hand the brush to a fill.
 */
fun ColorPaint.toBrush(size: Size): Brush {
    val flat = this as? ColorPaint.Flat
    if (flat != null) return SolidColor(Color(flat.color.toArgb()))
    val gradient = (this as ColorPaint.Ramp).gradient.normalized()
    val stops = gradient.renderStops().map { (position, color) -> position to Color(color.toArgb()) }.toTypedArray()
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

/** Draws canonical flat/linear/radial/conic paint without leaking Compose Brush into persistence. */
fun Modifier.backgroundPaint(paint: ColorPaint): Modifier = drawWithCache {
    val brush = paint.toBrush(size)
    onDrawBehind { drawRect(brush) }
}
