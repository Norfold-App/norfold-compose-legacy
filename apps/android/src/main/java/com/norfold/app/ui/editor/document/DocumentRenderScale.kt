package com.norfold.app.ui.editor.document

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.norfold.app.ui.editor.style.ResolvedBlockMetrics

/** One page point is 1/72 inch while one Android dp is 1/160 inch. */
internal const val DP_PER_POINT = 160f / 72f

/**
 * The explicit conversion boundary for content authored in bounded-document page points.
 *
 * [zoom] is only the viewport/adaptive ratio. [dpPerPoint] is the independent physical conversion from the
 * model's 72-points-per-inch coordinate space to Compose's 160-dp-per-inch coordinate space. Keeping both
 * values named prevents the former `value * zoom` implementation from shrinking content relative to the page.
 * Scene x/y/width/height still use DocumentHostScreen's geometry boundary and never enter this type.
 */
class DocumentRenderScale private constructor(
    val zoom: Float,
    val dpPerPoint: Float,
) {
    val pointFactor: Float get() = zoom * dpPerPoint

    fun pointToDp(points: Float): Dp = (points * pointFactor).dp

    fun pointTextSize(points: Float): TextUnit = (points * pointFactor).sp

    fun pointTextStyle(style: TextStyle): TextStyle = style.copy(
        fontSize = style.fontSize.asPagePoints(),
        lineHeight = style.lineHeight.asPagePoints(),
        letterSpacing = style.letterSpacing.asPagePoints(),
    )

    fun pointBlockMetrics(metrics: ResolvedBlockMetrics): ResolvedBlockMetrics = ResolvedBlockMetrics(
        beforeDp = metrics.beforeDp * pointFactor,
        afterDp = metrics.afterDp * pointFactor,
    )

    internal fun todoMetrics(visualPoints: Float = DEFAULT_TODO_VISUAL_POINTS): DocumentTodoMetrics {
        val visible = pointToDp(visualPoints).value
        return DocumentTodoMetrics(
            visualDp = visible,
            contentFootprintDp = visible,
            minimumTouchTargetDp = MINIMUM_TODO_TOUCH_TARGET_DP,
        )
    }

    private fun TextUnit.asPagePoints(): TextUnit =
        if (this == TextUnit.Unspecified) this else pointTextSize(value)

    companion object {
        const val DEFAULT_TODO_VISUAL_POINTS = 12f
        const val MINIMUM_TODO_TOUCH_TARGET_DP = 48f

        val At100Percent = DocumentRenderScale(zoom = 1f, dpPerPoint = DP_PER_POINT)

        fun atZoom(zoom: Float, dpPerPoint: Float = DP_PER_POINT): DocumentRenderScale =
            DocumentRenderScale(
                zoom = zoom.takeIf { it.isFinite() && it > 0f } ?: 1f,
                dpPerPoint = dpPerPoint.takeIf { it.isFinite() && it > 0f } ?: DP_PER_POINT,
            )
    }
}

/** Pure layout evidence for the custom Document Todo marker. */
internal data class DocumentTodoMetrics(
    val visualDp: Float,
    val contentFootprintDp: Float,
    val minimumTouchTargetDp: Float,
)
