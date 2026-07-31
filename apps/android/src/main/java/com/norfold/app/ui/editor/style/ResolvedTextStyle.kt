package com.norfold.app.ui.editor.style

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.sp
import com.norfold.app.domain.DocumentBlock
import com.norfold.app.domain.ShapeBlock
import com.norfold.app.domain.effectiveFill
import com.norfold.app.domain.effectiveStroke
import com.norfold.app.domain.effectiveStyleAssignment
import com.norfold.app.domain.style.ColorValue
import com.norfold.app.domain.style.ColorPaint
import com.norfold.app.domain.style.ColorRole
import com.norfold.app.domain.style.FontIdentity
import com.norfold.app.domain.style.StyleCategory
import com.norfold.app.domain.style.StyleProperty
import com.norfold.app.domain.style.StyleResolver
import com.norfold.app.domain.style.Stylesheet
import com.norfold.app.ui.fonts.OfflineFontLibrary

/** Resolves the stored style reference + local overrides into Compose presentation. */
@Composable
fun resolvedTextStyle(
    block: DocumentBlock,
    sheet: Stylesheet,
    base: TextStyle,
    previewRole: ColorRole? = null,
    previewPaint: ColorPaint? = null,
    previewTargetIds: Set<String> = emptySet(),
): TextStyle {
    val values = StyleResolver.resolveAll(sheet, block.effectiveStyleAssignment(), StyleCategory.TEXT)
    val weight = values[StyleProperty.FONT_WEIGHT]?.toIntOrNull()?.coerceIn(100, 900)
    val identity = values[StyleProperty.FONT_FAMILY]?.let(FontIdentity::decode)
    val decorations = buildList {
        if (values[StyleProperty.UNDERLINE].toBoolean()) add(TextDecoration.Underline)
        if (values[StyleProperty.STRIKETHROUGH].toBoolean()) add(TextDecoration.LineThrough)
    }
    return base.copy(
        color = previewPaint?.representative()
            ?.takeIf { block.id in previewTargetIds && previewRole == ColorRole.TEXT }
            ?.let { Color(it.toArgb()) }
            ?: values[StyleProperty.TEXT_COLOR]?.let(ColorValue::parse)?.let { Color(it.toArgb()) }
            ?: base.color,
        background = previewPaint?.representative()
            ?.takeIf { block.id in previewTargetIds && previewRole == ColorRole.HIGHLIGHT }
            ?.let { Color(it.toArgb()) }
            ?: values[StyleProperty.HIGHLIGHT]?.let(ColorValue::parse)?.let { Color(it.toArgb()) }
            ?: base.background,
        fontFamily = identity?.let(OfflineFontLibrary::fontFor) ?: base.fontFamily,
        fontSize = values[StyleProperty.FONT_SIZE]?.toFloatOrNull()?.takeIf { it in 4f..512f }?.sp
            ?: base.fontSize,
        fontWeight = weight?.let(::FontWeight) ?: base.fontWeight,
        fontStyle = if (values[StyleProperty.ITALIC].toBoolean()) FontStyle.Italic else base.fontStyle,
        textDecoration = decorations.takeIf { it.isNotEmpty() }?.let(TextDecoration::combine)
            ?: base.textDecoration,
        lineHeight = values[StyleProperty.LINE_HEIGHT]?.toFloatOrNull()?.takeIf { it in 4f..1024f }?.sp
            ?: base.lineHeight,
        letterSpacing = values[StyleProperty.LETTER_SPACING]?.toFloatOrNull()?.takeIf { it in -20f..100f }?.sp
            ?: base.letterSpacing,
        textAlign = when (values[StyleProperty.TEXT_ALIGN]?.lowercase()) {
            "center" -> TextAlign.Center
            "end", "right" -> TextAlign.End
            "justify" -> TextAlign.Justify
            "start", "left" -> TextAlign.Start
            else -> base.textAlign
        },
    )
}

/** Named object styles resolve through the same stylesheet/token chain as text styles. */
fun resolvedShapeFill(block: ShapeBlock, sheet: Stylesheet): ColorPaint {
    val assignment = block.effectiveStyleAssignment()
    if (assignment.overrides(StyleProperty.FILL)) return block.effectiveFill()
    val value = StyleResolver.resolve(sheet, assignment, StyleProperty.FILL)
    return value?.let(ColorValue::parse)?.let(ColorPaint::Flat) ?: block.effectiveFill()
}

fun resolvedShapeStroke(block: ShapeBlock, sheet: Stylesheet): ColorPaint {
    val assignment = block.effectiveStyleAssignment()
    if (assignment.overrides(StyleProperty.STROKE_COLOR)) return block.effectiveStroke()
    val value = StyleResolver.resolve(sheet, assignment, StyleProperty.STROKE_COLOR)
    return value?.let(ColorValue::parse)?.let(ColorPaint::Flat) ?: block.effectiveStroke()
}

data class ResolvedShapeMetrics(
    val strokeWidthDp: Float,
    val cornerRadiusDp: Float,
    val paddingDp: Float,
    val shadowDp: Float,
    val opacity: Float,
)

fun resolvedShapeMetrics(block: ShapeBlock, sheet: Stylesheet): ResolvedShapeMetrics {
    val values = StyleResolver.resolveAll(sheet, block.effectiveStyleAssignment(), StyleCategory.OBJECT)
    fun scalar(property: StyleProperty, fallback: Float, range: ClosedFloatingPointRange<Float>): Float =
        values[property]?.toFloatOrNull()?.takeIf { it in range } ?: fallback
    return ResolvedShapeMetrics(
        strokeWidthDp = scalar(StyleProperty.STROKE_WIDTH, block.strokeWidthDp, 0f..64f),
        cornerRadiusDp = scalar(StyleProperty.CORNER_RADIUS, 8f, 0f..256f),
        paddingDp = scalar(StyleProperty.PADDING, 6f, 0f..128f),
        shadowDp = scalar(StyleProperty.SHADOW, 0f, 0f..64f),
        opacity = scalar(StyleProperty.OPACITY, 1f, 0f..1f),
    )
}
