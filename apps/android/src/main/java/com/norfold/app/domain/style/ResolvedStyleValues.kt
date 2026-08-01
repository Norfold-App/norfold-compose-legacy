package com.norfold.app.domain.style

import com.norfold.app.domain.DocumentPageBlock
import com.norfold.app.domain.ShapeBlock
import com.norfold.app.domain.effectiveBackgroundPaint
import com.norfold.app.domain.effectiveFill
import com.norfold.app.domain.effectiveStroke
import com.norfold.app.domain.effectiveStyleAssignment

/** Pure shared paint resolution used by both rendering hosts and editor tool state. */
fun resolvedShapeFill(block: ShapeBlock, sheet: Stylesheet): ColorPaint {
    val assignment = block.effectiveStyleAssignment()
    val raw = StyleResolver.rawValue(sheet, assignment, StyleProperty.FILL)
    if (raw is StyleValue.TokenRef) {
        return StyleResolver.literalOf(sheet, raw)?.let(ColorValue::parse)?.let(ColorPaint::Flat)
            ?: block.effectiveFill()
    }
    if (assignment.overrides(StyleProperty.FILL)) return block.effectiveFill()
    return StyleResolver.literalOf(sheet, raw)?.let(ColorValue::parse)?.let(ColorPaint::Flat)
        ?: block.effectiveFill()
}

fun resolvedShapeStroke(block: ShapeBlock, sheet: Stylesheet): ColorPaint {
    val assignment = block.effectiveStyleAssignment()
    val raw = StyleResolver.rawValue(sheet, assignment, StyleProperty.STROKE_COLOR)
    if (raw is StyleValue.TokenRef) {
        return StyleResolver.literalOf(sheet, raw)?.let(ColorValue::parse)?.let(ColorPaint::Flat)
            ?: block.effectiveStroke()
    }
    if (assignment.overrides(StyleProperty.STROKE_COLOR)) return block.effectiveStroke()
    return StyleResolver.literalOf(sheet, raw)?.let(ColorValue::parse)?.let(ColorPaint::Flat)
        ?: block.effectiveStroke()
}

fun resolvedPageBackground(page: DocumentPageBlock, sheet: Stylesheet): ColorPaint =
    page.geometry.backgroundTokenId
        ?.let(sheet::token)
        ?.colorValue()
        ?.let(ColorPaint::Flat)
        ?: page.geometry.effectiveBackgroundPaint()
