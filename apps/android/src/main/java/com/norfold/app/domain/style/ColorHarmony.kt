package com.norfold.app.domain.style

/**
 * Harmony generation (roadmap Phase 3 §4).
 *
 * Harmonies are derived, never stored: each is a pure function of a base [ColorValue], so a harmony row
 * cannot go stale relative to the color it was derived from. Every generated color goes through
 * [ColorValue.fromHsl], which means a harmony swatch is an ordinary canonical color the moment it exists —
 * there is no "harmony color" type that would then need its own conversion path.
 *
 * Saturation and lightness are carried over from the base unchanged; only hue rotates. That is what makes
 * a harmony read as *the same palette* rather than a set of unrelated colors at the right angles.
 */
enum class ColorHarmony(val label: String, val description: String) {
    COMPLEMENTARY("Complementary", "The opposite hue — maximum separation"),
    ANALOGOUS("Analogous", "Neighbouring hues — quiet and cohesive"),
    TRIADIC("Triadic", "Three evenly spaced hues — balanced and vivid"),
    SPLIT_COMPLEMENTARY("Split complementary", "Either side of the opposite hue"),
    TETRADIC("Tetradic", "Two complementary pairs"),
    SQUARE("Square", "Four evenly spaced hues"),
    MONOCHROMATIC("Monochromatic", "One hue at several lightnesses"),
    ;

    /**
     * The harmony of [base], **including** the base itself as the first entry.
     *
     * Including the base is deliberate: a user comparing a harmony needs to see what it is a harmony *of*,
     * and a row that omits it forces them to hold the original in their head.
     */
    fun generate(base: ColorValue): List<ColorValue> {
        val hsl = base.toHsl()
        fun rotate(degrees: Float) = ColorValue.fromHsl(
            ColorValue.normalizeHue(hsl.hue + degrees),
            hsl.saturation,
            hsl.lightness,
            hsl.alpha,
        )
        return when (this) {
            COMPLEMENTARY -> listOf(base, rotate(180f))
            ANALOGOUS -> listOf(rotate(-30f), base, rotate(30f))
            TRIADIC -> listOf(base, rotate(120f), rotate(240f))
            SPLIT_COMPLEMENTARY -> listOf(base, rotate(150f), rotate(210f))
            TETRADIC -> listOf(base, rotate(60f), rotate(180f), rotate(240f))
            SQUARE -> listOf(base, rotate(90f), rotate(180f), rotate(270f))
            MONOCHROMATIC -> {
                // Lightness steps rather than hue steps, spread around the base and clamped so a very
                // light or very dark base still yields five visibly different swatches instead of
                // collapsing several of them onto white or black.
                val steps = listOf(-0.30f, -0.15f, 0f, 0.15f, 0.30f)
                steps.map { delta ->
                    ColorValue.fromHsl(
                        hsl.hue,
                        hsl.saturation,
                        (hsl.lightness + delta).coerceIn(0.08f, 0.94f),
                        hsl.alpha,
                    )
                }
            }
        }
    }

    /** Accessible description of the generated set — hex values, not swatches (§31). */
    fun describe(base: ColorValue): String =
        "$label: ${generate(base).joinToString(", ") { it.toHexRgb() }}"
}

/**
 * Tints and shades of one color — the ramp a user wants when building a token scale.
 *
 * Separate from [ColorHarmony.MONOCHROMATIC] because the intent differs: a harmony answers "what goes
 * with this", a ramp answers "what are the lighter and darker versions of this". Conflating them would
 * make the harmony row change length depending on which question the user was asking.
 */
object ColorRamp {

    /** [steps] evenly spaced lightness stops from near-white to near-black through [base]'s hue. */
    fun scale(base: ColorValue, steps: Int = 9): List<ColorValue> {
        val count = steps.coerceIn(2, 21)
        val hsl = base.toHsl()
        return (0 until count).map { index ->
            // 0.95 → 0.10 rather than 1.0 → 0.0: the endpoints of a usable scale are a very light and a
            // very dark version of the hue, not pure white and pure black, which carry no hue at all.
            val lightness = 0.95f - (0.85f * index / (count - 1))
            ColorValue.fromHsl(hsl.hue, hsl.saturation, lightness, hsl.alpha)
        }
    }

    /** Lighter versions of [base], toward white. */
    fun tints(base: ColorValue, steps: Int = 4): List<ColorValue> {
        val hsl = base.toHsl()
        return (1..steps.coerceIn(1, 10)).map { i ->
            val t = i.toFloat() / (steps + 1)
            ColorValue.fromHsl(hsl.hue, hsl.saturation, hsl.lightness + (1f - hsl.lightness) * t, hsl.alpha)
        }
    }

    /** Darker versions of [base], toward black. */
    fun shades(base: ColorValue, steps: Int = 4): List<ColorValue> {
        val hsl = base.toHsl()
        return (1..steps.coerceIn(1, 10)).map { i ->
            val t = i.toFloat() / (steps + 1)
            ColorValue.fromHsl(hsl.hue, hsl.saturation, hsl.lightness * (1f - t), hsl.alpha)
        }
    }
}
