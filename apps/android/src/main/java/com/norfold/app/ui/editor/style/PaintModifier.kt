package com.norfold.app.ui.editor.style

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.rotate
import com.norfold.app.domain.style.ColorPaint
import com.norfold.app.domain.style.GradientKind
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Draws canonical flat/linear/radial/conic paint without leaking Compose Brush into persistence. */
fun Modifier.backgroundPaint(paint: ColorPaint): Modifier = drawWithCache {
    val flat = paint as? ColorPaint.Flat
    if (flat != null) {
        val color = Color(flat.color.toArgb())
        onDrawBehind { drawRect(color) }
    } else {
        val gradient = (paint as ColorPaint.Ramp).gradient.normalized()
        val stops = gradient.sortedStops.map { it.position to Color(it.effectiveColor().toArgb()) }.toTypedArray()
        val center = Offset(size.width * gradient.centerX, size.height * gradient.centerY)
        val brush = when (gradient.kind) {
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
        onDrawBehind {
            if (gradient.kind == GradientKind.CONIC) {
                rotate(gradient.angle, center) { drawRect(brush) }
            } else {
                drawRect(brush)
            }
        }
    }
}
