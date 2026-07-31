package com.norfold.app.domain.style

import java.util.Locale
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * The canonical colour model (roadmap Phase 3 §2–§4, §37.1–§37.12).
 *
 * The invariant every test here defends is that **one** sRGBA value is stored and HEX/RGB/HSL/HSV are
 * *derivations* of it. That is what makes "switch to HSL and back" a no-op: there is nothing else to drift
 * against, because a view change writes nothing.
 */
class ColorValueTest {

    private val defaultLocale = Locale.getDefault()

    @After
    fun restoreLocale() {
        Locale.setDefault(defaultLocale)
    }

    // ── §37.1 round-trips ────────────────────────────────────────────────────────────────────────

    @Test
    fun `hex round trip is exact for every channel value`() {
        for (v in 0..255) {
            val color = ColorValue(v, 255 - v, (v * 7) % 256)
            val reparsed = ColorValue.parseHex(color.toHex())
            assertEquals("hex round trip at channel $v", color, reparsed)
        }
    }

    @Test
    fun `alpha survives the hex round trip`() {
        val color = ColorValue(18, 52, 86, 128)
        assertEquals("#12345680", color.toHex())
        assertEquals(color, ColorValue.parseHex("#12345680"))
    }

    /**
     * HSL is a derivation, so a full round trip must return the *same stored bytes*.
     *
     * A tolerance is unavoidable (HSL is float-valued and 8-bit RGB is not a bijection with it), but the
     * tolerance must be ±1 per channel, not "close enough" — anything looser means repeated view switching
     * walks the colour, which is exactly the §4 drift defect.
     */
    @Test
    fun `hsl round trip stays within one unit per channel`() {
        var worst = 0
        for (r in 0..255 step 5) {
            for (g in 0..255 step 17) {
                for (b in 0..255 step 29) {
                    val original = ColorValue(r, g, b)
                    val back = original.withHsl(original.toHsl())
                    worst = maxOf(
                        worst,
                        abs(back.red - r),
                        abs(back.green - g),
                        abs(back.blue - b),
                    )
                }
            }
        }
        assertTrue("worst HSL round-trip error was $worst", worst <= 1)
    }

    @Test
    fun `hsv round trip stays within one unit per channel`() {
        var worst = 0
        for (r in 0..255 step 7) {
            for (g in 0..255 step 13) {
                for (b in 0..255 step 23) {
                    val original = ColorValue(r, g, b)
                    val hsv = original.toHsv()
                    val back = ColorValue.fromHsv(hsv.hue, hsv.saturation, hsv.value, hsv.alpha)
                    worst = maxOf(worst, abs(back.red - r), abs(back.green - g), abs(back.blue - b))
                }
            }
        }
        assertTrue("worst HSV round-trip error was $worst", worst <= 1)
    }

    /** §37.2: repeated view switching must not accumulate error, because views do not write. */
    @Test
    fun `switching representations twenty times does not drift`() {
        var color = ColorValue(203, 87, 41, 200)
        val original = color
        repeat(20) {
            color = color.withHsl(color.toHsl())
            val hsv = color.toHsv()
            color = ColorValue.fromHsv(hsv.hue, hsv.saturation, hsv.value, hsv.alpha)
            color = ColorValue.parseHex(color.toHex())!!
        }
        assertTrue(
            "drifted from $original to $color after 20 conversions",
            abs(color.red - original.red) <= 1 &&
                abs(color.green - original.green) <= 1 &&
                abs(color.blue - original.blue) <= 1 &&
                color.alpha == original.alpha,
        )
    }

    // ── §37.3 grey and achromatic handling ───────────────────────────────────────────────────────

    /**
     * Pure grey has undefined hue. The model must not invent one and must not lose the stored value.
     *
     * The failure this catches: reporting hue 0 for grey and then *writing that back* turns grey into a
     * red-tinted grey the moment the user touches an unrelated slider.
     */
    @Test
    fun `grey reports achromatic and survives a hue edit unchanged`() {
        for (v in 0..255) {
            val grey = ColorValue(v, v, v)
            val hsl = grey.toHsl()
            assertTrue("grey $v should be achromatic", hsl.achromatic)
            assertEquals("grey $v saturation", 0f, hsl.saturation, 0.0001f)
            assertEquals("grey $v must round trip", grey, grey.withHsl(hsl))
        }
    }

    @Test
    fun `black and white round trip exactly`() {
        assertEquals(ColorValue.BLACK, ColorValue.BLACK.withHsl(ColorValue.BLACK.toHsl()))
        assertEquals(ColorValue.WHITE, ColorValue.WHITE.withHsl(ColorValue.WHITE.toHsl()))
        assertEquals(0f, ColorValue.BLACK.toHsl().lightness, 0.0001f)
        assertEquals(1f, ColorValue.WHITE.toHsl().lightness, 0.0001f)
    }

    // ── §37.4 malformed input ────────────────────────────────────────────────────────────────────

    @Test
    fun `malformed hex is rejected rather than guessed`() {
        val bad = listOf(
            "", "#", "#1", "#12", "#12345", "#1234567", "#123456789",
            "#GGGGGG", "#12 34 56", "12345g", "#-12345", "rgb(1,2,3", "null", "   ",
        )
        for (input in bad) {
            assertNull("'$input' must not parse", ColorValue.parseHex(input))
        }
        assertNull(ColorValue.parseHex(null))
    }

    @Test
    fun `shorthand hex expands correctly`() {
        assertEquals(ColorValue(0xAA, 0xBB, 0xCC), ColorValue.parseHex("#abc"))
        assertEquals(ColorValue(0xAA, 0xBB, 0xCC, 0xDD), ColorValue.parseHex("#abcd"))
        // Expansion must duplicate the nibble, not pad with zero: #abc is #AABBCC, never #A0B0C0.
        assertEquals("#AABBCC", ColorValue.parseHex("#abc")!!.toHex())
    }

    @Test
    fun `out of range rgb is rejected not clamped`() {
        assertNull("256 is not a channel value", ColorValue.parseRgb("256", "0", "0"))
        assertNull(ColorValue.parseRgb("-1", "0", "0"))
        assertNull(ColorValue.parseRgb("1e3", "0", "0"))
        assertNotNull(ColorValue.parseRgb("255", "0", "0"))
        assertNotNull(ColorValue.parseRgb("0", "0", "0"))
    }

    // ── §37.5 locale independence ────────────────────────────────────────────────────────────────

    /**
     * Parsing and formatting must be locale-independent (§4).
     *
     * Load-bearing, not theoretical: this app ships Bangla, and under `bn-BD` a naive
     * `Integer.parseInt`/`String.format` pipeline produces Bengali digits it then cannot read back — a
     * colour field that silently stops working in one language.
     */
    @Test
    fun `hex and rgb parse identically under a bengali locale`() {
        val expected = ColorValue(0x2A, 0xB4, 0x7C, 0x80)
        val locales = listOf(
            Locale.forLanguageTag("bn-BD"),
            Locale.forLanguageTag("ar-EG"),
            Locale.forLanguageTag("de-DE"),
            Locale.forLanguageTag("tr-TR"),
            Locale.US,
        )
        for (locale in locales) {
            Locale.setDefault(locale)
            assertEquals("hex under $locale", expected, ColorValue.parseHex("#2ab47c80"))
            assertEquals("hex format under $locale", "#2AB47C80", expected.toHex())
            assertEquals("rgb under $locale", ColorValue(42, 180, 124), ColorValue.parseRgb("42", "180", "124"))
            // Uppercasing under tr-TR is the classic trap: 'i' becomes 'İ', so a locale-sensitive
            // uppercase in the formatter would emit an unparseable string.
            assertEquals(expected, ColorValue.parseHex(expected.toHex()))
        }
    }

    /**
     * HSL text parses the same way in every locale — including the bare-integer form the UI displays.
     *
     * The `"50"`/`"50%"`/`"0.5"` triple must agree, because those are the three shapes the same value takes
     * as it moves between the slider readout, a pasted CSS value, and a fraction.
     */
    @Test
    fun `hsl parsing is locale independent`() {
        for (tag in listOf("bn-BD", "de-DE", "fr-FR", "en-US")) {
            Locale.setDefault(Locale.forLanguageTag(tag))
            val bare = ColorValue.parseHsl("210", "50", "40")
            assertNotNull("hsl under $tag", bare)
            val hsl = bare!!.toHsl()
            assertEquals("hue under $tag", 210f, hsl.hue, 1.5f)
            assertEquals("saturation under $tag", 0.5f, hsl.saturation, 0.02f)
            assertEquals("lightness under $tag", 0.4f, hsl.lightness, 0.02f)
            // The three notations for one value must agree.
            assertEquals("percent form under $tag", bare, ColorValue.parseHsl("210", "50%", "40%"))
            assertEquals("fraction form under $tag", bare, ColorValue.parseHsl("210", "0.5", "0.4"))
        }
    }

    @Test
    fun `hue wraps but saturation and lightness do not`() {
        val wrapped = ColorValue.parseHsl("370", "50", "40")
        assertEquals(ColorValue.parseHsl("10", "50", "40"), wrapped)
        assertNull("saturation above 100 is not an angle", ColorValue.parseHsl("200", "101", "40"))
        assertNull(ColorValue.parseHsl("200", "50", "140"))
        assertNull("a localized digit must not parse", ColorValue.parseHsl("২১০", "50", "40"))
    }

    // ── §37.6 alpha ──────────────────────────────────────────────────────────────────────────────

    @Test
    fun `alpha is preserved through hsl and hsv edits`() {
        val translucent = ColorValue(120, 60, 200, 77)
        assertEquals(77, translucent.withHsl(translucent.toHsl()).alpha)
        assertEquals(77, translucent.withHue(30f).alpha)
        assertEquals(77, translucent.withSaturation(0.5f).alpha)
        assertEquals(77, translucent.withLightness(0.8f).alpha)
    }

    @Test
    fun `alpha compositing matches the manual blend`() {
        val half = ColorValue(255, 0, 0, 128)
        val over = half.compositedOver(ColorValue.WHITE)
        assertEquals(255, over.alpha)
        // 255*128/255 + 255*(1-128/255) ≈ 255 for red; green/blue lift toward white.
        assertEquals(255, over.red)
        assertTrue("green should be lifted toward white, was ${over.green}", over.green in 125..130)
        assertEquals(over.green, over.blue)
    }

    @Test
    fun `fully transparent over a background yields the background`() {
        val clear = ColorValue(10, 20, 30, 0)
        val bg = ColorValue(200, 100, 50)
        val result = clear.compositedOver(bg)
        assertEquals(bg.red, result.red)
        assertEquals(bg.green, result.green)
        assertEquals(bg.blue, result.blue)
    }

    // ── §37.7 argb interop ───────────────────────────────────────────────────────────────────────

    @Test
    fun `argb round trip preserves all four channels`() {
        val samples = listOf(
            ColorValue(0, 0, 0, 0),
            ColorValue(255, 255, 255, 255),
            ColorValue(1, 2, 3, 4),
            ColorValue(0x12, 0x34, 0x56, 0x78),
        )
        for (color in samples) {
            assertEquals(color, ColorValue.fromArgb(color.toArgb()))
        }
    }

    @Test
    fun `legacy opaque argb longs decode as opaque`() {
        // The existing document format stores 0xFFRRGGBB longs; those must not read as transparent.
        val decoded = ColorValue.fromArgb(0xFF3366CCL)
        assertEquals(ColorValue(0x33, 0x66, 0xCC, 255), decoded)
    }

    // ── Luminance and labels ─────────────────────────────────────────────────────────────────────

    @Test
    fun `relative luminance is monotonic in lightness`() {
        var previous = -1.0
        for (v in 0..255 step 5) {
            val luminance = ColorValue(v, v, v).relativeLuminance()
            assertTrue("luminance must increase with lightness at $v", luminance > previous)
            previous = luminance
        }
        assertEquals(0.0, ColorValue.BLACK.relativeLuminance(), 0.0001)
        assertEquals(1.0, ColorValue.WHITE.relativeLuminance(), 0.0001)
    }

    @Test
    fun `accessible label names the colour rather than reciting hex digits`() {
        val label = ColorValue(220, 40, 40).accessibleLabel()
        assertTrue("label was '$label'", label.isNotBlank())
        assertTrue("a screen reader label should not be only hex: '$label'", !label.startsWith("#"))
    }

    @Test
    fun `parse accepts every representation it emits`() {
        val color = ColorValue(90, 140, 210, 190)
        assertEquals(color, ColorValue.parse(color.toHex()))
        assertEquals(color, ColorValue.parse(color.toHex().lowercase()))
        assertNotNull(ColorValue.parse("rgb(90, 140, 210)"))
        assertNotNull(ColorValue.parse("rgba(90, 140, 210, 0.5)"))
        assertNull(ColorValue.parse("not a colour"))
    }
}
