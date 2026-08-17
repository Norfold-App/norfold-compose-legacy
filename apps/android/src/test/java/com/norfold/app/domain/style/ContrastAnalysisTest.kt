package com.norfold.app.domain.style

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * The contrast engine, and the chrome derivation the editors build on it.
 *
 * This file exists because the numbers it asserts were previously only claimed. Editor chrome colours are
 * derived at runtime from the user's accent and the page's own colour, so the ratio a selection outline
 * actually achieves is a *computed* property of two values neither the designer nor the reviewer can see. The
 * old approach documented a measurement in a comment; a comment cannot fail. Every figure quoted in
 * [ContrastAnalysis] and in the two editors' chrome documentation is pinned here instead.
 */
class ContrastAnalysisTest {

    // ── The model itself ─────────────────────────────────────────────────────────────────────────────

    /**
     * The published WCAG reference pairs, to two decimals.
     *
     * A contrast implementation that is subtly wrong produces plausible numbers, so this is the calibration
     * step: if these five do not match, nothing else in this file means anything. `#949494` is the one
     * documented case where the reference value is rounded — it is published as 3.00:1 and computes to
     * 3.03:1 — so it is asserted at its computed value with a wider tolerance rather than silently skipped.
     */
    @Test
    fun `matches published WCAG reference ratios`() {
        assertEquals(21.00, ratioOf(0xFFFFFF, 0x000000), 0.005)
        assertEquals(4.54, ratioOf(0x767676, 0xFFFFFF), 0.005)
        assertEquals(7.00, ratioOf(0x595959, 0xFFFFFF), 0.005)
        assertEquals(8.59, ratioOf(0x0000FF, 0xFFFFFF), 0.005)
        assertEquals(3.03, ratioOf(0x949494, 0xFFFFFF), 0.02)
    }

    /** Contrast is symmetric and bounded: a ratio is a property of a pair, not of an order. */
    @Test
    fun `ratio is symmetric and stays in range`() {
        val samples = listOf(0xFFFFFF, 0x000000, 0x3D7C45, 0xE08A12, 0x767676, 0x191919)
        samples.forEach { a ->
            samples.forEach { b ->
                val forward = ratioOf(a, b)
                assertEquals(forward, ratioOf(b, a), 1e-9)
                assertTrue("$forward out of range", forward >= 1.0 && forward <= 21.0)
            }
        }
        assertEquals(1.0, ratioOf(0x3D7C45, 0x3D7C45), 1e-9)
    }

    // ── inkFor ───────────────────────────────────────────────────────────────────────────────────────

    /**
     * The band where a luminance threshold picks the wrong ink.
     *
     * `luminance() > 0.5` is the conventional shortcut and it is wrong across 70 consecutive greys: black
     * overtakes white at `#767676`, but luminance does not cross 0.5 until `#BCBCBC`. This asserts the two
     * boundaries directly, because they are the specific claim [ContrastAnalysis.inkFor]'s documentation
     * makes, and a future "simplification" back to a threshold should fail here rather than dim 70 shades of
     * page silently.
     */
    @Test
    fun `ink is chosen by measurement, not by a luminance threshold`() {
        // The measured crossover.
        assertEquals(ColorValue.WHITE, ContrastAnalysis.inkFor(grey(0x75)))
        assertEquals(ColorValue.BLACK, ContrastAnalysis.inkFor(grey(0x76)))

        // Every grey in the disputed band prefers black, while the threshold would still say white.
        (0x76..0xBB).forEach { v ->
            assertEquals(
                "grey #%02X should take black ink".format(v),
                ColorValue.BLACK,
                ContrastAnalysis.inkFor(grey(v)),
            )
            assertTrue("grey #%02X should be below the 0.5 luminance line".format(v), grey(v).relativeLuminance() <= 0.5)
        }
    }

    /** Whatever ink is chosen, it is the better of the two — asserted over every grey, not a sample. */
    @Test
    fun `ink is always the higher-contrast of black and white`() {
        (0..255).forEach { v ->
            val bg = grey(v)
            val chosen = ContrastAnalysis.ratio(ContrastAnalysis.inkFor(bg), bg)
            val other = maxOf(
                ContrastAnalysis.ratio(ColorValue.WHITE, bg),
                ContrastAnalysis.ratio(ColorValue.BLACK, bg),
            )
            assertEquals("grey #%02X".format(v), other, chosen, 1e-9)
        }
    }

    // ── legibleChrome ────────────────────────────────────────────────────────────────────────────────

    /**
     * The guarantee the editors rely on: chrome reaches its target on **every** shipped accent and page.
     *
     * Ten accents, seven page colours, both themes — 140 cases, and the reason this is exhaustive rather
     * than sampled is that the failure it guards against is invisible. Chrome one accent short of legible on
     * one page colour looks like a rendering opinion, not a defect, so nobody files it.
     */
    @Test
    fun `every shipped accent reaches the UI component target on every page colour`() {
        var worst = Double.MAX_VALUE
        ACCENTS.forEach { accent ->
            listOf(accent, liftedForDark(accent)).forEach { base ->
                PAGES.forEach { page ->
                    val chrome = ContrastAnalysis.legibleChrome(base, page)
                    val achieved = ContrastAnalysis.ratio(chrome, page)
                    assertTrue(
                        "${base.toHexRgb()} on ${page.toHexRgb()} only reached $achieved",
                        achieved >= ContrastAnalysis.UI_COMPONENT - 1e-9,
                    )
                    worst = minOf(worst, achieved)
                }
            }
        }
        // The bound is tight: at least one case lands exactly on the target rather than comfortably past it,
        // which is what shows the adjustment is minimal rather than heavy-handed.
        assertEquals(ContrastAnalysis.UI_COMPONENT, worst, 0.05)
    }

    /**
     * An accent that is already legible is returned untouched.
     *
     * This is what keeps the feature invisible in the common case: on the white page that most documents use,
     * the selection outline is the exact colour the user picked in settings, not an approximation of it.
     */
    @Test
    fun `an accent that already passes is not adjusted at all`() {
        val page = ColorValue.WHITE
        val alreadyFine = ACCENTS.filter { ContrastAnalysis.ratio(it, page) >= ContrastAnalysis.UI_COMPONENT }
        assertTrue("expected most shipped accents to pass on white", alreadyFine.size >= 8)
        alreadyFine.forEach { accent ->
            assertEquals(accent, ContrastAnalysis.legibleChrome(accent, page))
        }
    }

    /**
     * Only lightness moves — asserted exactly, not within a tolerance.
     *
     * The point of adjusting rather than substituting is that the user's accent stays recognisably theirs. A
     * green accent on a dark page must still be green; if chrome fell back to a neutral, a user who chose a
     * brand colour would see the app ignore it exactly where the page was most unusual.
     *
     * The assertion is that the result is reproducible from the accent's *own* hue and saturation at some
     * lightness. That is stronger than comparing round-tripped HSL within a tolerance, because it admits no
     * slack at all: if a future change mixed toward a neutral, blended in the page colour, or nudged
     * saturation to reach the target more cheaply, no lightness of the original hue could reproduce the
     * output and this fails. A tolerance-based version would pass a small amount of exactly that.
     */
    @Test
    fun `adjustment moves lightness and nothing else`() {
        ACCENTS.forEach { accent ->
            PAGES.forEach { page ->
                val hsl = accent.toHsl()
                val actual = ContrastAnalysis.legibleChrome(accent, page)
                // The search steps outward from the accent's *own* lightness in 1/100 increments, so the
                // candidates are offsets from it rather than absolute hundredths. The accent itself is in the
                // set because an already-passing colour is returned verbatim, not round-tripped through HSL.
                val reachable = buildList {
                    add(accent)
                    (0..100).forEach { step ->
                        listOf(-1, 1).forEach { direction ->
                            val lightness = hsl.lightness + direction * step / 100f
                            if (lightness in 0f..1f) {
                                add(ColorValue.fromHsl(hsl.hue, hsl.saturation, lightness, hsl.alpha))
                            }
                        }
                    }
                }
                assertTrue(
                    "${accent.toHexRgb()} on ${page.toHexRgb()} produced ${actual.toHexRgb()}, " +
                        "which is not this hue at any lightness",
                    reachable.any { it.red == actual.red && it.green == actual.green && it.blue == actual.blue },
                )
            }
        }
    }

    /**
     * How far the 8-bit round trip can carry a hue, measured — because "unchanged" would be a lie.
     *
     * Hue and saturation are passed to `fromHsl` untouched, which the test above proves. Reading the hue back
     * out of the result is not free, though: a dark page can require a very dark chrome colour, and at that
     * lightness one integer step per channel is a large fraction of the channel. The worst case across every
     * shipped accent and page is the green accent on the slate page — `#3D7C45` needs lightness 0.363 → 0.102,
     * landing on `#112314`, whose hue reads back 2.38° away.
     *
     * That bound is asserted rather than described so the figure stays true. 2.5° is well inside what is
     * distinguishable in a 1 dp outline, and the alternative — refusing to darken far enough — would trade an
     * invisible hue shift for a genuinely invisible outline.
     */
    @Test
    fun `hue survives the round trip to within measurable quantisation`() {
        var worstHue = 0.0
        var worstSaturation = 0.0
        ACCENTS.forEach { accent ->
            PAGES.forEach { page ->
                val before = accent.toHsl()
                // A near-grey has no meaningful hue to preserve, so it is not evidence either way.
                if (before.saturation <= 0.08f) return@forEach
                val after = ContrastAnalysis.legibleChrome(accent, page).toHsl()
                val drift = abs(before.hue - after.hue).let { minOf(it, 360f - it) }
                worstHue = maxOf(worstHue, drift.toDouble())
                worstSaturation = maxOf(worstSaturation, abs(before.saturation - after.saturation).toDouble())
            }
        }
        assertTrue("hue drifted $worstHue°, more than quantisation explains", worstHue <= 2.5)
        assertTrue("saturation drifted $worstSaturation", worstSaturation <= 0.01)
        // And the bound is tight — if a future change made this comfortable, the figure above is stale.
        assertEquals(2.38, worstHue, 0.05)
    }

    /**
     * The Flow resting edge, pinned to the value its documentation quotes.
     *
     * These two colours are what [com.norfold.app.ui.editor.flow.FlowObjectFrame] renders on the Flow canvas,
     * whose theme replaces the whole colour scheme with paper neutrals. They were previously derived by
     * interpolating with `androidx.compose.ui.graphics.lerp` and two hand-solved factors — which mixes in
     * **Oklab**, so the shipped edge measured 2.31:1 light and 2.14:1 dark while the comment beside it
     * claimed 2.20 and 2.22. This test is the reason that cannot recur: the figure is now checked, not
     * asserted in prose.
     */
    @Test
    fun `the Flow resting edge measures its documented ratio`() {
        val lightEdge = ContrastAnalysis.legibleChrome(hex(0xDFDFDC), hex(0xFFFFFF), RESTING_EDGE)
        assertEquals("#AEAEA7", lightEdge.toHexRgb())
        assertEquals(2.23, ratio(lightEdge, hex(0xFFFFFF)), 0.01)

        val darkEdge = ContrastAnalysis.legibleChrome(hex(0x3F3F3F), hex(0x191919), RESTING_EDGE)
        assertEquals("#515151", darkEdge.toHexRgb())
        assertEquals(2.22, ratio(darkEdge, hex(0x191919)), 0.01)

        // The stated limit, kept honest: a frame nested on the deepest container surface is weaker than one
        // on the page, and the documentation says so rather than omitting it.
        assertEquals(1.85, ratio(lightEdge, hex(0xEAEAE8)), 0.01)
        assertEquals(1.62, ratio(darkEdge, hex(0x323232)), 0.01)
    }

    /**
     * What the Document editor shipped before this work, kept as the record of why it changed.
     *
     * A resting object edge at 1.56:1 is below the ~1.5:1 floor where a boundary becomes visible at all, and
     * a *selected* object at 1.79:1 is worse than most unselected chrome. These are asserted rather than
     * described so that "was this actually a problem?" has an answer that runs.
     */
    @Test
    fun `the chrome this replaced was measurably invisible`() {
        // Color.Gray at 40% alpha, composited on a white sheet — the old resting object edge.
        val oldResting = ColorValue.of(0x88, 0x88, 0x88, alpha = 102).compositedOver(ColorValue.WHITE)
        assertEquals(1.56, ratio(oldResting, ColorValue.WHITE), 0.01)

        // The old selected edge: Material baseline purple at 60%, on a light and a dark sheet.
        val purple = ColorValue.of(0x67, 0x50, 0xA4, alpha = 153)
        assertEquals(2.70, ratio(purple.compositedOver(ColorValue.WHITE), ColorValue.WHITE), 0.01)
        assertEquals(1.69, ratio(purple.compositedOver(hex(0x202124)), hex(0x202124)), 0.01)

        // And each is genuinely fixed, not merely different.
        PAGES.forEach { page ->
            val fixed = ContrastAnalysis.legibleChrome(ACCENTS.first(), page)
            assertTrue(ContrastAnalysis.ratio(fixed, page) >= ContrastAnalysis.UI_COMPONENT - 1e-9)
        }
    }

    /** A translucent backdrop is unanswerable, so [ContrastAnalysis.suggestAccessible] declines to answer. */
    @Test
    fun `a translucent backdrop yields no suggestion`() {
        assertNull(ContrastAnalysis.suggestAccessible(ColorValue.BLACK, ColorValue.of(255, 255, 255, alpha = 128)))
        // legibleChrome must still return something drawable, because chrome cannot skip a frame.
        assertNotNull(ContrastAnalysis.legibleChrome(ColorValue.BLACK, ColorValue.of(255, 255, 255, alpha = 128)))
    }

    // ── Fixtures ─────────────────────────────────────────────────────────────────────────────────────

    /** The resting-edge target both editors use. Mirrors `FlowFrameMetrics.RESTING_EDGE_RATIO`. */
    private val RESTING_EDGE = 2.2

    /** The ten accents `BrandPalettes` actually ships. */
    private val ACCENTS = listOf(
        0x3D7C45, 0x9D50BB, 0xF75C7A, 0x2E7BE4, 0x1F9E7E,
        0xE23A2E, 0xB06AB3, 0x3E8EA0, 0xE08A12, 0x33374A,
    ).map(::hex)

    /** Page colours spanning what a user can set a sheet to, white through black. */
    private val PAGES = listOf(0xFFFFFF, 0xFFF8E1, 0xD7CCC8, 0x9E9E9E, 0x546E7A, 0x202124, 0x000000).map(::hex)

    private fun hex(rgb: Int) = ColorValue.of((rgb shr 16) and 0xFF, (rgb shr 8) and 0xFF, rgb and 0xFF)

    private fun grey(v: Int) = ColorValue.of(v, v, v)

    private fun ratio(a: ColorValue, b: ColorValue) = ContrastAnalysis.ratio(a, b)

    private fun ratioOf(a: Int, b: Int) = ContrastAnalysis.ratio(hex(a), hex(b))

    /** How `NorfoldTheme` derives the dark-mode accent: 35% of the way to white. */
    private fun liftedForDark(c: ColorValue) = ColorValue.of(
        c.red + (255 - c.red) * 35 / 100,
        c.green + (255 - c.green) * 35 / 100,
        c.blue + (255 - c.blue) * 35 / 100,
    )
}
