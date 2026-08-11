package com.norfold.app.ui.editor.style

import androidx.compose.runtime.Composable
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.sp
import com.norfold.app.domain.DocumentBlock
import com.norfold.app.domain.ShapeBlock
import com.norfold.app.domain.effectiveStyleAssignment
import com.norfold.app.domain.semanticStyleRole
import com.norfold.app.domain.styleAssignment
import com.norfold.app.domain.style.ColorValue
import com.norfold.app.domain.style.ColorPaint
import com.norfold.app.domain.style.ColorRole
import com.norfold.app.domain.style.FontIdentity
import com.norfold.app.domain.style.StyleCategory
import com.norfold.app.domain.style.StyleProperty
import com.norfold.app.domain.style.StyleResolver
import com.norfold.app.domain.style.StyleValue
import com.norfold.app.domain.style.Stylesheet
import com.norfold.app.ui.fonts.OfflineFontLibrary
import kotlin.math.max

data class WrapperStyleSource(val blockId: String, val assignment: com.norfold.app.domain.style.StyleAssignment)

data class ResolvedBlockMetrics(val beforeDp: Float = 0f, val afterDp: Float = 0f)

/** Values in 0.5..4 are ratios; larger valid values retain the legacy absolute-sp interpretation. */
fun resolvedLineHeightSp(stored: Float?, resolvedFontSizeSp: Float): Float? = when {
    stored == null || !stored.isFinite() || !resolvedFontSizeSp.isFinite() || resolvedFontSizeSp <= 0f -> null
    stored in 0.5f..4f -> stored * resolvedFontSizeSp
    stored in 4f..1024f -> stored
    else -> null
}

fun resolvedTextValues(
    block: DocumentBlock,
    sheet: Stylesheet,
    wrappersNearestFirst: List<WrapperStyleSource> = emptyList(),
): Map<StyleProperty, String> = StyleResolver.resolveAllCascaded(
    sheet = sheet,
    child = block.styleAssignment(),
    implicitStyleId = block.semanticStyleRole()?.let(Stylesheet::defaultStyleIdFor),
    wrappersNearestFirst = wrappersNearestFirst.map(WrapperStyleSource::assignment),
    category = StyleCategory.TEXT,
)

fun resolvedBlockMetrics(
    block: DocumentBlock,
    sheet: Stylesheet,
    wrappersNearestFirst: List<WrapperStyleSource> = emptyList(),
): ResolvedBlockMetrics {
    val values = resolvedTextValues(block, sheet, wrappersNearestFirst)
    fun metric(property: StyleProperty): Float = values[property]
        ?.toFloatOrNull()
        ?.takeIf { it.isFinite() && it in 0f..4096f }
        ?: 0f
    return ResolvedBlockMetrics(
        beforeDp = metric(StyleProperty.SPACE_BEFORE),
        afterDp = metric(StyleProperty.SPACE_AFTER),
    )
}

/** Adjacent block margins collapse to the larger value; they are never summed. */
fun collapsedBlockSpacing(previousAfterDp: Float, nextBeforeDp: Float): Float =
    max(previousAfterDp.coerceAtLeast(0f), nextBeforeDp.coerceAtLeast(0f))

/** Read-only Text and BasicTextField both call this, so neither can replace an explicit resolved color. */
fun resolvedContentColor(resolved: Color, fallback: Color): Color =
    resolved.takeUnless { it == Color.Unspecified } ?: fallback

fun previewAffectsTextBlock(
    blockId: String,
    wrappersNearestFirst: List<WrapperStyleSource>,
    previewTargetIds: Set<String>,
): Boolean = blockId in previewTargetIds || wrappersNearestFirst.any { it.blockId in previewTargetIds }

/** Resolves the stored style reference + local overrides into Compose presentation. */
@Composable
fun resolvedTextStyle(
    block: DocumentBlock,
    sheet: Stylesheet,
    base: TextStyle,
    wrappersNearestFirst: List<WrapperStyleSource> = emptyList(),
    previewRole: ColorRole? = null,
    previewPaint: ColorPaint? = null,
    previewTargetIds: Set<String> = emptySet(),
    adaptThemeTextTokens: Boolean = false,
): TextStyle {
    val values = resolvedTextValues(block, sheet, wrappersNearestFirst)
    val weight = values[StyleProperty.FONT_WEIGHT]?.toIntOrNull()?.coerceIn(100, 900)
    val identity = values[StyleProperty.FONT_FAMILY]?.let(FontIdentity::decode)
    val resolvedFontSize = values[StyleProperty.FONT_SIZE]?.toFloatOrNull()?.takeIf { it in 4f..512f }?.sp
        ?: base.fontSize
    val fontSizeValue = resolvedFontSize.value.takeIf { it.isFinite() && it > 0f }
    val resolvedLineHeight = resolvedLineHeightSp(
        values[StyleProperty.LINE_HEIGHT]?.toFloatOrNull(),
        fontSizeValue ?: Float.NaN,
    )?.sp ?: base.lineHeight
    val previewApplies = previewAffectsTextBlock(block.id, wrappersNearestFirst, previewTargetIds)
    val rawTextColor = StyleResolver.rawValueCascaded(
        sheet = sheet,
        child = block.styleAssignment(),
        implicitStyleId = block.semanticStyleRole()?.let(Stylesheet::defaultStyleIdFor),
        wrappersNearestFirst = wrappersNearestFirst.map(WrapperStyleSource::assignment),
        property = StyleProperty.TEXT_COLOR,
    )
    val adaptiveThemeColor = if (!adaptThemeTextTokens) null else when ((rawTextColor as? StyleValue.TokenRef)?.tokenId) {
        Stylesheet.TOKEN_TEXT_PRIMARY -> MaterialTheme.colorScheme.onSurface
        Stylesheet.TOKEN_TEXT_SECONDARY -> MaterialTheme.colorScheme.onSurfaceVariant
        else -> null
    }
    val decorations = buildList {
        if (values[StyleProperty.UNDERLINE].toBoolean()) add(TextDecoration.Underline)
        if (values[StyleProperty.STRIKETHROUGH].toBoolean()) add(TextDecoration.LineThrough)
    }
    return base.copy(
        color = previewPaint?.representative()
            ?.takeIf { previewApplies && previewRole == ColorRole.TEXT }
            ?.let { Color(it.toArgb()) }
            ?: adaptiveThemeColor
            ?: values[StyleProperty.TEXT_COLOR]?.let(ColorValue::parse)?.let { Color(it.toArgb()) }
            ?: base.color,
        background = previewPaint?.representative()
            ?.takeIf { previewApplies && previewRole == ColorRole.HIGHLIGHT }
            ?.let { Color(it.toArgb()) }
            ?: values[StyleProperty.HIGHLIGHT]?.let(ColorValue::parse)?.let { Color(it.toArgb()) }
            ?: base.background,
        fontFamily = identity?.let(OfflineFontLibrary::fontFor) ?: base.fontFamily,
        fontSize = resolvedFontSize,
        fontWeight = weight?.let(::FontWeight) ?: base.fontWeight,
        fontStyle = if (values[StyleProperty.ITALIC].toBoolean()) FontStyle.Italic else base.fontStyle,
        textDecoration = decorations.takeIf { it.isNotEmpty() }?.let(TextDecoration::combine)
            ?: base.textDecoration,
        lineHeight = resolvedLineHeight,
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
