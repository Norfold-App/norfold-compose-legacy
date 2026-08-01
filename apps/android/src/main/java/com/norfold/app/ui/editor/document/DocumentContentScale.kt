package com.norfold.app.ui.editor.document

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.norfold.app.ui.editor.style.ResolvedBlockMetrics

/**
 * The single conversion boundary from bounded-document content units to visible Compose units.
 *
 * Scene-object model geometry is converted by DocumentHostScreen and never enters this type. Only content
 * inside the already-scaled frame uses it, which prevents both the old fixed-size-content defect and a
 * double-scaled x/y/width/height defect.
 */
@JvmInline
value class DocumentContentScale private constructor(val factor: Float) {
    fun scalar(value: Float): Float = value * factor

    fun dp(value: Float): Dp = scalar(value).dp

    fun textStyle(style: TextStyle): TextStyle = style.copy(
        fontSize = style.fontSize.scaled(),
        lineHeight = style.lineHeight.scaled(),
        letterSpacing = style.letterSpacing.scaled(),
    )

    fun blockMetrics(metrics: ResolvedBlockMetrics): ResolvedBlockMetrics = ResolvedBlockMetrics(
        beforeDp = scalar(metrics.beforeDp),
        afterDp = scalar(metrics.afterDp),
    )

    private fun TextUnit.scaled(): TextUnit =
        if (this == TextUnit.Unspecified) this else (value * factor).sp

    companion object {
        val One = DocumentContentScale(1f)

        fun of(factor: Float): DocumentContentScale = DocumentContentScale(
            factor.takeIf { it.isFinite() && it > 0f } ?: 1f,
        )
    }
}
