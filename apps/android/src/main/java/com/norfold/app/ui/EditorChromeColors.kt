package com.norfold.app.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.norfold.app.domain.style.ColorPaint
import com.norfold.app.domain.style.ContrastAnalysis
import com.norfold.app.domain.style.ColorValue

/**
 * Editor chrome that stays visible on a canvas the *user* colours.
 *
 * [SemanticColors] answers "which token should this chrome use" for chrome that sits on an app surface. It
 * cannot answer the canvas question, because a canvas is not an app surface: a Document page carries
 * `PageGeometry.background`, which the user sets to any colour or to a gradient. A token resolved from
 * `colorScheme` is correct against `colorScheme.surface` by construction and says nothing at all about a
 * cream sheet, a slate sheet, or a photograph.
 *
 * The failure is not hypothetical. Before this existed, the Document object edge was `Color.Gray` at 40%
 * alpha — **1.56:1** composited on a white sheet, where a non-text boundary needs 3:1 to satisfy WCAG 1.4.11
 * and about 1.5:1 to be seen at all. A *selected* object with the shipped orange accent measured **1.79:1**
 * on a light sheet and **1.45:1** on a dark one. Chrome that states "this object is selected" while being
 * invisible is a worse failure than chrome that is merely ugly, because the user acts on what they cannot
 * see.
 *
 * **Why this is a UI file over a domain function.** The arithmetic is [ContrastAnalysis.legibleChrome],
 * which is domain code with unit tests and no Compose dependency. What lives here is only the translation:
 * Compose [Color] to [ColorValue] and back, and the decision about which backdrop a given piece of chrome
 * is actually drawn against. Keeping the measurement in the domain layer is what lets CI assert the
 * contrast figures instead of a comment claiming them.
 *
 * **Why not derive by mixing in Compose.** `androidx.compose.ui.graphics.lerp` interpolates in **Oklab**,
 * not sRGB — it converts both endpoints with `Color.convert(ColorSpaces.Oklab)` before interpolating. A
 * ratio predicted by mixing sRGB components therefore does not describe what `lerp` puts on screen, and
 * the discrepancy is large enough to matter: the Flow resting edge was documented at 2.20:1 from an sRGB
 * calculation while actually rendering 2.31:1. Everything here routes through [ColorValue], whose
 * `relativeLuminance()` is plain WCAG on 8-bit sRGB channels, so the measured number and the drawn pixel
 * are the same arithmetic.
 */
object EditorChromeColors {

    /**
     * [base] adjusted in lightness until it clears [target] against [backdrop], hue and saturation intact.
     *
     * The ordinary case adjusts nothing: an accent that already contrasts with the page is returned exactly
     * as the user chose it, so this is invisible on the white sheet most documents use and only engages
     * where it has to.
     */
    fun legible(
        base: Color,
        backdrop: Color,
        target: Double = ContrastAnalysis.UI_COMPONENT,
    ): Color = ContrastAnalysis.legibleChrome(base.asColorValue(), backdrop.asColorValue(), target).asColor()

    /**
     * The backdrop a piece of page chrome is measured against, given the page's resolved paint.
     *
     * A gradient is reduced to its **worst** stop rather than its midpoint. `ColorPaint.representative()`
     * returns the colour at 0.5, which is the right answer for a swatch chip standing in for the whole ramp
     * and the wrong one here: chrome is drawn across the entire page, so the stop that matters is the one it
     * is least visible against. A white-to-black ramp has a mid grey that flatters every edge colour and two
     * ends that defeat most of them.
     */
    fun pageBackdrop(paint: ColorPaint): Color = when (paint) {
        is ColorPaint.Flat -> paint.color.asColor()
        is ColorPaint.Ramp -> worstStop(paint.colors()).asColor()
    }

    /**
     * Chrome drawn *over* content rather than on bare page — a lock badge, a marker dot.
     *
     * Uses the page's own best ink at full opacity. A translucent badge over unknown content is the
     * [ContrastAnalysis.analyzeUnknownBackdrop] case: its real ratio depends on the photograph behind it, so
     * no claim can honestly be made. Opaque ink on the page's own polarity is the strongest claim that *is*
     * available, and it measures 6.61:1 or better on every page colour tested.
     */
    fun badgeInk(backdrop: Color): Color = ContrastAnalysis.inkFor(backdrop.asColorValue()).asColor()

    /**
     * The stop of a ramp that chrome has the hardest time being seen on.
     *
     * Picked by distance from mid-luminance, not by darkness. The darkest and lightest stops bound what a
     * single chrome colour has to survive, and whichever sits further from the middle is the one that
     * decides — a near-black stop defeats dark chrome exactly as a near-white stop defeats light chrome.
     * Chrome legible against that extreme is legible against every stop between them, because luminance
     * varies monotonically along the ramp joining two stops.
     */
    private fun worstStop(stops: List<ColorValue>): ColorValue {
        if (stops.isEmpty()) return ColorValue.WHITE
        val fromMidpoint = { c: ColorValue -> kotlin.math.abs(c.relativeLuminance() - 0.5) }
        return stops.maxBy(fromMidpoint)
    }
}

/** Compose colour to the canonical domain value. Alpha survives; the domain measures on opaque channels. */
internal fun Color.asColorValue(): ColorValue =
    ColorValue.fromArgb(toArgb().toLong() and 0xFFFFFFFFL)

/** The canonical domain value back to a Compose colour. */
internal fun ColorValue.asColor(): Color = Color(toArgb().toInt())
