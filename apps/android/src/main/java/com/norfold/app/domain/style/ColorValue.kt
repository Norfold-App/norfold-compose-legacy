package com.norfold.app.domain.style

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The one canonical color model for the whole product (roadmap Phase 3 §2, spec §8).
 *
 * There is deliberately **one** stored form — 8-bit sRGB channels plus 8-bit alpha — and every other
 * representation (HEX, RGB, HSL, HSV) is a *derivation* of it. That single choice is what makes the
 * spec's central invariant hold: switching the representation a user is looking at cannot alter the
 * color, because switching a view does not write anything. The defect this design exists to prevent is
 * the chained conversion
 *
 *     RGB → HSL → RGB → HEX → RGB
 *
 * where each hop rounds and the color visibly crawls away from what the user picked. Here, an edit in
 * *any* representation parses once into a new canonical value; a mere *view* change parses nothing.
 *
 * **Why 8-bit sRGB and not float HSL.** Three reasons, in order of weight:
 *
 *  1. it is the form every consumer wants — Compose `Color`, PDF/print export, the `#RRGGBB` a user
 *     types, and the `Long` the existing block schema already persists;
 *  2. it is exactly representable, so equality and serialization are total and stable. A float HSL
 *     canonical form would make `red == red` depend on how the red was arrived at;
 *  3. HSL/HSV are *cylindrical projections* of RGB with genuinely ambiguous inverses — the hue of pure
 *     black is undefined, and every greyscale value has infinitely many (hue, saturation) origins.
 *     Storing a projection means storing that ambiguity, and then a user who nudges lightness on a grey
 *     gets whatever hue the projection happened to invent.
 *
 * The cost is that HSL/HSV round trips are exact only to the 8-bit grid, which is why [withHsl] and
 * friends carry a documented tolerance rather than promising bit-exactness. That is the honest trade:
 * a bounded, non-accumulating quantization instead of an unbounded drift.
 *
 * Channels are always in `0..255`; the constructor coerces rather than throwing, because this type is
 * constructed from *persisted* data during migration and a document must always open.
 */
@Serializable
data class ColorValue(
    @SerialName("r") val red: Int,
    @SerialName("g") val green: Int,
    @SerialName("b") val blue: Int,
    @SerialName("a") val alpha: Int = 255,
) {
    init {
        // Coercion happens in the factories; the constructor asserts the invariant so no code path can
        // introduce an out-of-range channel that would then serialize and reload as a different color.
        require(red in 0..255 && green in 0..255 && blue in 0..255 && alpha in 0..255) {
            "ColorValue channels must be 0..255 (got $red,$green,$blue,$alpha)"
        }
    }

    val isOpaque: Boolean get() = alpha == 255
    val isTransparent: Boolean get() = alpha == 0

    /** Alpha as `0f..1f`, for renderers that want a float. Never used as storage. */
    val alphaFraction: Float get() = alpha / 255f

    // ── Derived representations ──────────────────────────────────────────────────────────────────

    /** `#RRGGBB` when opaque, `#RRGGBBAA` otherwise — the shortest form that loses nothing. */
    fun toHex(): String = if (isOpaque) toHexRgb() else toHexRgba()

    /** Always `#RRGGBB`; alpha is dropped. Use when a field is documented as opaque-only. */
    fun toHexRgb(): String = "#%02X%02X%02X".format(red, green, blue)

    /** Always `#RRGGBBAA`. */
    fun toHexRgba(): String = "#%02X%02X%02X%02X".format(red, green, blue, alpha)

    /** `0xAARRGGBB`, the form the existing block schema persists colors in. */
    fun toArgb(): Long =
        (alpha.toLong() shl 24) or (red.toLong() shl 16) or (green.toLong() shl 8) or blue.toLong()

    fun toRgb(): RgbColor = RgbColor(red, green, blue, alpha)

    /**
     * HSL projection. Hue is `0..360` (exclusive of 360), saturation and lightness `0f..1f`.
     *
     * For an achromatic color the hue is genuinely undefined; [HslColor.hue] is reported as `0f` and
     * [HslColor.achromatic] is true, so a UI can keep showing the user's last hue instead of snapping the
     * wheel to red every time they drag lightness to 0.
     */
    fun toHsl(): HslColor {
        val r = red / 255f
        val g = green / 255f
        val b = blue / 255f
        val max = maxOf(r, g, b)
        val min = minOf(r, g, b)
        val lightness = (max + min) / 2f
        val delta = max - min
        if (delta == 0f) return HslColor(0f, 0f, lightness, alphaFraction, achromatic = true)
        val saturation = delta / (1f - abs(2f * lightness - 1f))
        return HslColor(hueOf(r, g, b, max, delta), saturation.coerceIn(0f, 1f), lightness, alphaFraction)
    }

    /**
     * HSV projection. Kept distinct from HSL rather than derived from it: the Color Studio's spectrum
     * plane is an HSV square, and computing it through HSL would round twice for no benefit.
     */
    fun toHsv(): HsvColor {
        val r = red / 255f
        val g = green / 255f
        val b = blue / 255f
        val max = maxOf(r, g, b)
        val min = minOf(r, g, b)
        val delta = max - min
        if (delta == 0f) return HsvColor(0f, 0f, max, alphaFraction, achromatic = true)
        return HsvColor(hueOf(r, g, b, max, delta), (delta / max).coerceIn(0f, 1f), max, alphaFraction)
    }

    private fun hueOf(r: Float, g: Float, b: Float, max: Float, delta: Float): Float {
        val hue = when (max) {
            r -> 60f * (((g - b) / delta) % 6f)
            g -> 60f * (((b - r) / delta) + 2f)
            else -> 60f * (((r - g) / delta) + 4f)
        }
        return ((hue % 360f) + 360f) % 360f
    }

    // ── Edits (each parses once into a new canonical value) ──────────────────────────────────────

    fun withAlpha(value: Int): ColorValue = copy(alpha = value.coerceIn(0, 255))

    fun withAlphaFraction(value: Float): ColorValue =
        withAlpha(((value.finiteOrZero()).coerceIn(0f, 1f) * 255f).roundToInt())

    /**
     * A new canonical value from an HSL edit, preserving this color's alpha unless [hsl] carries one.
     *
     * This is the only correct way to "change the lightness": the edit is applied in HSL and immediately
     * projected back to canonical, so exactly one rounding happens per user edit no matter how many
     * sliders they touch afterwards.
     */
    fun withHsl(hsl: HslColor): ColorValue = fromHsl(hsl.hue, hsl.saturation, hsl.lightness, hsl.alpha)

    fun withHsv(hsv: HsvColor): ColorValue = fromHsv(hsv.hue, hsv.saturation, hsv.value, hsv.alpha)

    fun withHue(hue: Float): ColorValue = toHsl().let { withHsl(it.copy(hue = normalizeHue(hue))) }

    fun withSaturation(saturation: Float): ColorValue =
        toHsl().let { withHsl(it.copy(saturation = saturation.finiteOrZero().coerceIn(0f, 1f))) }

    fun withLightness(lightness: Float): ColorValue =
        toHsl().let { withHsl(it.copy(lightness = lightness.finiteOrZero().coerceIn(0f, 1f))) }

    // ── Analysis ─────────────────────────────────────────────────────────────────────────────────

    /**
     * Relative luminance per WCAG 2.x, computed on the *opaque* channels.
     *
     * Alpha is deliberately ignored: luminance of a translucent color is meaningless without knowing what
     * is behind it, and silently compositing over an assumed white would produce a confident contrast
     * number that is wrong. [ContrastAnalysis] is where that honesty is enforced.
     */
    fun relativeLuminance(): Double {
        fun channel(value: Int): Double {
            val c = value / 255.0
            return if (c <= 0.03928) c / 12.92 else Math.pow((c + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * channel(red) + 0.7152 * channel(green) + 0.0722 * channel(blue)
    }

    /** This color composited over [background], as if painted on it. Used for honest contrast on known backgrounds. */
    fun compositedOver(background: ColorValue): ColorValue {
        if (isOpaque) return this
        val a = alphaFraction
        fun mix(fg: Int, bg: Int) = (fg * a + bg * (1f - a)).roundToInt().coerceIn(0, 255)
        return ColorValue(
            mix(red, background.red),
            mix(green, background.green),
            mix(blue, background.blue),
            alpha = background.alpha,
        )
    }

    /** A readable name for accessibility: the hex plus its rough hue family, never color alone (§31). */
    fun accessibleLabel(): String {
        val hsl = toHsl()
        val family = when {
            alpha == 0 -> "transparent"
            hsl.lightness >= 0.96f -> "white"
            hsl.lightness <= 0.04f -> "black"
            hsl.achromatic || hsl.saturation < 0.08f -> "grey"
            hsl.hue < 15f || hsl.hue >= 345f -> "red"
            hsl.hue < 45f -> "orange"
            hsl.hue < 70f -> "yellow"
            hsl.hue < 160f -> "green"
            hsl.hue < 200f -> "cyan"
            hsl.hue < 260f -> "blue"
            hsl.hue < 290f -> "violet"
            hsl.hue < 345f -> "magenta"
            else -> "grey"
        }
        return buildString {
            append(family)
            append(' ')
            append(toHexRgb())
            if (!isOpaque) append(", ${(alphaFraction * 100).roundToInt()}% opacity")
        }
    }

    companion object {
        val BLACK = ColorValue(0, 0, 0)
        val WHITE = ColorValue(255, 255, 255)
        val TRANSPARENT = ColorValue(0, 0, 0, 0)

        /** Coercing factory; use instead of the constructor when values come from arithmetic or storage. */
        fun of(red: Int, green: Int, blue: Int, alpha: Int = 255) =
            ColorValue(red.coerceIn(0, 255), green.coerceIn(0, 255), blue.coerceIn(0, 255), alpha.coerceIn(0, 255))

        /** From `0xAARRGGBB`. A value with no alpha bits set at all is read as fully opaque. */
        fun fromArgb(argb: Long): ColorValue = of(
            red = ((argb shr 16) and 0xFF).toInt(),
            green = ((argb shr 8) and 0xFF).toInt(),
            blue = (argb and 0xFF).toInt(),
            alpha = ((argb shr 24) and 0xFF).toInt(),
        )

        /**
         * Parses a HEX string, or returns null. Never throws and never guesses.
         *
         * Accepted: `#RGB`, `#RGBA`, `#RRGGBB`, `#RRGGBBAA`, with or without the leading `#`, in either
         * case. Short forms expand by digit duplication (`#0AF` → `#00AAFF`), which is the CSS rule and
         * the only expansion that keeps `#FFF` pure white.
         *
         * Parsing is explicitly **locale-independent**: digits are looked up in an ASCII table rather than
         * going through `Integer.parseInt` on a localized substring, so a Bengali or Arabic-Indic locale
         * cannot change what `#1A2B3C` means. That matters here because this app ships Bangla support.
         */
        fun parseHex(raw: String?): ColorValue? {
            val body = raw?.trim()?.removePrefix("#")?.removePrefix("0x")?.removePrefix("0X") ?: return null
            if (body.isEmpty()) return null
            val digits = IntArray(body.length)
            for (i in body.indices) {
                val d = hexDigit(body[i])
                if (d < 0) return null
                digits[i] = d
            }
            fun pair(hi: Int, lo: Int) = digits[hi] * 16 + digits[lo]
            fun dup(i: Int) = digits[i] * 17
            return when (body.length) {
                3 -> ColorValue(dup(0), dup(1), dup(2))
                4 -> ColorValue(dup(0), dup(1), dup(2), dup(3))
                6 -> ColorValue(pair(0, 1), pair(2, 3), pair(4, 5))
                8 -> ColorValue(pair(0, 1), pair(2, 3), pair(4, 5), pair(6, 7))
                else -> null
            }
        }

        private fun hexDigit(c: Char): Int = when (c) {
            in '0'..'9' -> c - '0'
            in 'a'..'f' -> c - 'a' + 10
            in 'A'..'F' -> c - 'A' + 10
            else -> -1
        }

        /**
         * Parses decimal RGB/RGBA input, or returns null.
         *
         * Rejects rather than clamps: a user typing `300` in a red field has made a mistake, and silently
         * showing them 255 teaches them the field accepted it. Clamping belongs at the *arithmetic*
         * boundary ([of]), not the *input* boundary.
         */
        fun parseRgb(red: String?, green: String?, blue: String?, alpha: String? = null): ColorValue? {
            val r = parseChannel(red) ?: return null
            val g = parseChannel(green) ?: return null
            val b = parseChannel(blue) ?: return null
            val a = if (alpha.isNullOrBlank()) 255 else parseChannel(alpha) ?: return null
            return ColorValue(r, g, b, a)
        }

        /** `rgb(12, 34, 56)` / `rgba(12,34,56,0.5)` / bare `12 34 56`. Null when the text is not that. */
        fun parseRgbFunction(raw: String?): ColorValue? {
            val text = raw?.trim()?.lowercase() ?: return null
            val inner = when {
                text.startsWith("rgba(") && text.endsWith(")") -> text.removePrefix("rgba(").removeSuffix(")")
                text.startsWith("rgb(") && text.endsWith(")") -> text.removePrefix("rgb(").removeSuffix(")")
                else -> text
            }
            val parts = inner.split(',', ' ', '/').map(String::trim).filter(String::isNotEmpty)
            if (parts.size !in 3..4) return null
            val r = parseChannel(parts[0]) ?: return null
            val g = parseChannel(parts[1]) ?: return null
            val b = parseChannel(parts[2]) ?: return null
            val a = if (parts.size == 3) 255 else parseAlphaComponent(parts[3]) ?: return null
            return ColorValue(r, g, b, a)
        }

        /**
         * `0..255` integer, ASCII-only. A leading `+`, a decimal point, or any non-ASCII digit is a
         * rejection — `١٢` is not `12` here, because a color field is a machine value, not prose.
         */
        private fun parseChannel(raw: String?): Int? {
            val text = raw?.trim() ?: return null
            if (text.isEmpty() || text.length > 3) return null
            var value = 0
            for (c in text) {
                if (c !in '0'..'9') return null
                value = value * 10 + (c - '0')
            }
            return value.takeIf { it in 0..255 }
        }

        /** Alpha as either `0..255`, `0..1` fraction, or `NN%`. */
        private fun parseAlphaComponent(raw: String): Int? {
            val text = raw.trim()
            if (text.endsWith("%")) {
                val pct = parseAsciiDouble(text.dropLast(1)) ?: return null
                return if (pct in 0.0..100.0) (pct / 100.0 * 255).roundToInt() else null
            }
            if ('.' in text) {
                val f = parseAsciiDouble(text) ?: return null
                return if (f in 0.0..1.0) (f * 255).roundToInt() else null
            }
            return parseChannel(text)
        }

        /** Locale-independent decimal parse: only ASCII digits and a single `.` are accepted. */
        private fun parseAsciiDouble(raw: String): Double? {
            val text = raw.trim()
            if (text.isEmpty() || text.count { it == '.' } > 1) return null
            if (text.any { it != '.' && it !in '0'..'9' }) return null
            return text.toDoubleOrNull()
        }

        /**
         * Parses HSL input, or returns null. Hue wraps (`370` → `10`) because a hue *is* an angle;
         * saturation and lightness are rejected out of range because they are not.
         */
        fun parseHsl(hue: String?, saturation: String?, lightness: String?, alpha: String? = null): ColorValue? {
            val h = parseAsciiDouble(hue?.trim().orEmpty().removeSuffix("°")) ?: return null
            val s = parsePercentOrFraction(saturation) ?: return null
            val l = parsePercentOrFraction(lightness) ?: return null
            val a = if (alpha.isNullOrBlank()) 1f else parsePercentOrFraction(alpha) ?: return null
            return fromHsl(normalizeHue(h.toFloat()), s, l, a)
        }

        /**
         * A saturation/lightness/alpha component, as `NN%`, a `0..1` fraction, or a bare `0..100` integer.
         *
         * The bare-integer form is accepted because it is what the UI *displays*: the Color Studio's S and L
         * fields render `50`, so a parser that demanded `50%` or `0.5` would reject the very text it just
         * showed the user. Integers and fractions are told apart by the decimal point rather than by
         * magnitude, so `1` is one percent and `1.0` is full — a magnitude rule would make `1` ambiguous and
         * pick differently depending on how the value arrived.
         */
        private fun parsePercentOrFraction(raw: String?): Float? {
            val text = raw?.trim() ?: return null
            if (text.endsWith("%")) {
                val pct = parseAsciiDouble(text.dropLast(1)) ?: return null
                return if (pct in 0.0..100.0) (pct / 100.0).toFloat() else null
            }
            val value = parseAsciiDouble(text) ?: return null
            return when {
                '.' in text -> if (value in 0.0..1.0) value.toFloat() else null
                else -> if (value in 0.0..100.0) (value / 100.0).toFloat() else null
            }
        }

        /** Canonical value from HSL. The single rounding point for every HSL-side edit. */
        fun fromHsl(hue: Float, saturation: Float, lightness: Float, alpha: Float = 1f): ColorValue {
            val h = normalizeHue(hue)
            val s = saturation.finiteOrZero().coerceIn(0f, 1f)
            val l = lightness.finiteOrZero().coerceIn(0f, 1f)
            val c = (1f - abs(2f * l - 1f)) * s
            val (r, g, b) = hueSector(h, c, l - c / 2f)
            return of(
                (r * 255f).roundToInt(),
                (g * 255f).roundToInt(),
                (b * 255f).roundToInt(),
                (alpha.finiteOrZero().coerceIn(0f, 1f) * 255f).roundToInt(),
            )
        }

        /** Canonical value from HSV. */
        fun fromHsv(hue: Float, saturation: Float, value: Float, alpha: Float = 1f): ColorValue {
            val h = normalizeHue(hue)
            val s = saturation.finiteOrZero().coerceIn(0f, 1f)
            val v = value.finiteOrZero().coerceIn(0f, 1f)
            val c = v * s
            val (r, g, b) = hueSector(h, c, v - c)
            return of(
                (r * 255f).roundToInt(),
                (g * 255f).roundToInt(),
                (b * 255f).roundToInt(),
                (alpha.finiteOrZero().coerceIn(0f, 1f) * 255f).roundToInt(),
            )
        }

        /** The shared hue-sector interpolation both HSL and HSV need, offset by [m]. */
        private fun hueSector(hue: Float, chroma: Float, m: Float): Triple<Float, Float, Float> {
            val hp = hue / 60f
            val x = chroma * (1f - abs((hp % 2f) - 1f))
            val (r1, g1, b1) = when {
                hp < 1f -> Triple(chroma, x, 0f)
                hp < 2f -> Triple(x, chroma, 0f)
                hp < 3f -> Triple(0f, chroma, x)
                hp < 4f -> Triple(0f, x, chroma)
                hp < 5f -> Triple(x, 0f, chroma)
                else -> Triple(chroma, 0f, x)
            }
            return Triple(r1 + m, g1 + m, b1 + m)
        }

        fun normalizeHue(hue: Float): Float {
            if (!hue.isFinite()) return 0f
            return ((hue % 360f) + 360f) % 360f
        }

        /**
         * Best-effort parse of anything a user or a legacy payload might supply: hex, `rgb()`, or a bare
         * decimal ARGB. Returns null when nothing matches — the caller decides what to do, so no field
         * silently becomes black.
         */
        fun parse(raw: String?): ColorValue? {
            val text = raw?.trim() ?: return null
            if (text.isEmpty()) return null
            parseHex(text)?.let { return it }
            parseRgbFunction(text)?.let { return it }
            // A bare decimal is only accepted when it fits an ARGB word; otherwise it is not a color.
            val decimal = text.toLongOrNull() ?: return null
            return if (decimal in 0L..0xFFFFFFFFL) fromArgb(decimal) else null
        }
    }
}

private fun Float.finiteOrZero(): Float = if (isFinite()) this else 0f

/** RGB view of a [ColorValue]. A view, never storage. */
data class RgbColor(val red: Int, val green: Int, val blue: Int, val alpha: Int = 255) {
    fun toColorValue() = ColorValue.of(red, green, blue, alpha)
}

/**
 * HSL view. [achromatic] records that the hue carries no information for this color, so the Color Studio
 * can keep the wheel where the user left it instead of snapping to 0° on every grey.
 */
data class HslColor(
    val hue: Float,
    val saturation: Float,
    val lightness: Float,
    val alpha: Float = 1f,
    val achromatic: Boolean = false,
) {
    fun toColorValue() = ColorValue.fromHsl(hue, saturation, lightness, alpha)

    /** Rounded for display: `hsl(210, 45%, 30%)`. */
    fun display(): String =
        "hsl(${hue.roundToInt()}, ${(saturation * 100).roundToInt()}%, ${(lightness * 100).roundToInt()}%)"
}

/** HSV view — the spectrum plane's native space. */
data class HsvColor(
    val hue: Float,
    val saturation: Float,
    val value: Float,
    val alpha: Float = 1f,
    val achromatic: Boolean = false,
) {
    fun toColorValue() = ColorValue.fromHsv(hue, saturation, value, alpha)
}
