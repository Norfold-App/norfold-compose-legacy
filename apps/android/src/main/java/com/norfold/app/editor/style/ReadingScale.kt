package com.norfold.app.editor.style

import com.norfold.app.domain.EditorFontFamily
import com.norfold.app.domain.EditorLineWidth
import kotlin.math.roundToInt

/**
 * How large the editor's *content* is, and how wide a line of it is allowed to get.
 *
 * Pure Kotlin on purpose. Every rule here is a claim that can be wrong — that a heading stays a heading at
 * 2.0x, that the persisted default means "unchanged", that a wider window buys margin rather than line
 * length — and none of those are things a screenshot proves. They are asserted on the desktop JVM instead.
 *
 * **This scale multiplies; it never overrides.** Content sizes are returned in **sp**, so the platform
 * accessibility font scale and the app's own `fontScale` setting (already composed into `LocalDensity` by
 * `NorfoldTheme`) still apply on top. A user who has enlarged text system-wide and then sets 1.5x here gets
 * both, which is the only reading of "content scale" that does not silently undo an accessibility setting.
 *
 * **It never reaches chrome.** Toolbars, menus, dialogs and the command rail read the app typography; only
 * block renderers read the content typography built from this. The failure mode of a chrome composable that
 * forgets is therefore "this label is the normal app size", not "the toolbar is enormous".
 */
object ReadingScale {

    /**
     * Paragraph size at 1.0x, in sp.
     *
     * 18 sp rather than the 2.0x the earlier contract asked for as a *default*: at roughly 32 sp a 390 dp
     * phone fits about four words per line. That is a choice a reader may want — and [MAX] still reaches it —
     * but not one to ship as the default for long-form writing.
     */
    const val BASE_BODY_SP = 18f

    const val MIN = 0.5f
    const val MAX = 2f

    /**
     * The stored slider position that means 1.0x.
     *
     * `AppSettings.editorFontSize` has shipped with this default since before anything read it, so anchoring
     * 1.0x here is what makes this feature arrive without resizing a single existing note. The map is
     * piecewise-linear around the anchor rather than linear across the whole range, because a linear map
     * would put the untouched default at 1.325x and silently reflow every document on upgrade.
     */
    const val DEFAULT_FRACTION = 0.55f

    /**
     * Average glyph advance as a fraction of the font size, for turning a character budget into a width.
     *
     * A proportional face has no single character width, so any measure cap expressed in characters is an
     * estimate; 0.5 em is the conventional one for Latin prose. Stated as a constant rather than buried in
     * an expression because it is the assumption a wrong-looking measure should be blamed on first.
     */
    const val AVERAGE_GLYPH_EM = 0.5f

    /** The stored 0f..1f slider position as a multiplier in [MIN]..[MAX]. */
    fun multiplier(fraction: Float): Float {
        val f = if (fraction.isFinite()) fraction.coerceIn(0f, 1f) else DEFAULT_FRACTION
        return if (f <= DEFAULT_FRACTION) {
            MIN + (1f - MIN) * (f / DEFAULT_FRACTION)
        } else {
            1f + (MAX - 1f) * ((f - DEFAULT_FRACTION) / (1f - DEFAULT_FRACTION))
        }
    }

    /** The inverse of [multiplier], so a settings control can be driven in multiplier space. */
    fun fraction(multiplier: Float): Float {
        val m = if (multiplier.isFinite()) multiplier.coerceIn(MIN, MAX) else 1f
        return if (m <= 1f) {
            DEFAULT_FRACTION * ((m - MIN) / (1f - MIN))
        } else {
            DEFAULT_FRACTION + (1f - DEFAULT_FRACTION) * ((m - 1f) / (MAX - 1f))
        }
    }

    /** What the settings row shows. Rounded to whole percent: the slider is a preference, not a measurement. */
    fun percentLabel(fraction: Float): String = "${(multiplier(fraction) * 100f).roundToInt()}%"

    /** Paragraph size in sp at [fraction]. */
    fun bodySp(fraction: Float): Float = BASE_BODY_SP * multiplier(fraction)

    /** Size in sp for [role]. Every role is a ratio of the paragraph, so hierarchy survives every step. */
    fun sizeSp(role: ContentRole, fraction: Float): Float = bodySp(fraction) * role.sizeRatio

    /** Line height in sp for [role], as a ratio of that role's own size rather than of the paragraph. */
    fun lineHeightSp(role: ContentRole, fraction: Float): Float = sizeSp(role, fraction) * role.lineRatio

    /**
     * The widest a line of body text may get, in sp-units, for a budget of [characters].
     *
     * Returned in sp rather than dp so the caller converts through the current density: at a larger platform
     * font scale the same character budget needs a proportionally wider box, and going through `Dp` directly
     * would quietly narrow the measure exactly for the users who enlarged their text.
     */
    fun measureSp(fraction: Float, characters: Int): Float =
        bodySp(fraction) * AVERAGE_GLYPH_EM * characters.coerceIn(20, 200)
}

/**
 * A content role, as a pair of ratios against the paragraph.
 *
 * Sizes are relative rather than absolute because the hierarchy is the point: an absolute table would make
 * headings *relatively* smaller at every step of the scale until 2.0x body text and an H3 were the same
 * size. `SECONDARY` and `SMALL` are for content that is genuinely subordinate — a caption, a table body cell
 * — not for chrome, which does not read this at all.
 */
enum class ContentRole(val sizeRatio: Float, val lineRatio: Float) {
    BODY(1f, 1.55f),
    SECONDARY(0.9f, 1.5f),
    SMALL(0.82f, 1.45f),
    /** Monospace content. Slightly smaller because a mono face reads larger at the same nominal size. */
    CODE(0.92f, 1.6f),
    HEADING_1(1.9f, 1.22f),
    HEADING_2(1.5f, 1.26f),
    HEADING_3(1.25f, 1.3f),
    HEADING_4(1.1f, 1.35f),
    HEADING_5(1f, 1.4f),
    HEADING_6(0.9f, 1.45f),
    ;

    companion object {
        /** Maps a [com.norfold.app.domain.HeadingBlock] level onto a role; out-of-range levels clamp. */
        fun heading(level: Int): ContentRole = when (level.coerceIn(1, 6)) {
            1 -> HEADING_1
            2 -> HEADING_2
            3 -> HEADING_3
            4 -> HEADING_4
            5 -> HEADING_5
            else -> HEADING_6
        }
    }
}

/**
 * The character budget behind each stored line-width preference.
 *
 * Typographic convention puts comfortable prose at 65–75 characters, so `Comfortable` sits mid-band and the
 * two neighbours step either side of it. This is the measure cap the plan asks for, and it is a *cap*: a
 * narrow window is unaffected, a wide one gains margin rather than line length.
 */
val EditorLineWidth.contentCharacters: Int
    get() = when (this) {
        EditorLineWidth.Narrow -> 58
        EditorLineWidth.Comfortable -> 70
        EditorLineWidth.Wide -> 88
    }

/**
 * The stored preferences that decide how a document reads, resolved into one value.
 *
 * A data class rather than loose parameters because it travels as a single `CompositionLocal`: the editor
 * needs the numbers together, and a surface that gets the size without the measure renders enormous text
 * across a 1200 dp tablet. Its defaults are the shipped defaults, so a composable that never receives one —
 * a preview, a test, a screen that forgot to provide it — renders at exactly 1.0x rather than at zero.
 *
 * [face] is kept as the domain enum rather than a resolved font: this file is pure Kotlin so the whole
 * reading policy stays JVM-testable, and mapping a face to a `FontFamily` is a Compose concern.
 */
data class ReadingPreference(
    val fraction: Float = ReadingScale.DEFAULT_FRACTION,
    val characters: Int = EditorLineWidth.Comfortable.contentCharacters,
    val face: EditorFontFamily = EditorFontFamily.Sans,
) {
    fun sizeSp(role: ContentRole): Float = ReadingScale.sizeSp(role, fraction)

    fun lineHeightSp(role: ContentRole): Float = ReadingScale.lineHeightSp(role, fraction)

    /** The measure cap, in sp-units for the caller to convert through the current density. */
    val measureSp: Float get() = ReadingScale.measureSp(fraction, characters)

    companion object {
        fun of(
            fraction: Float,
            lineWidth: EditorLineWidth,
            face: EditorFontFamily = EditorFontFamily.Sans,
        ) = ReadingPreference(
            fraction = fraction,
            characters = lineWidth.contentCharacters,
            face = face,
        )
    }
}

