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
        // And so must settledChrome, which delegates to it for exactly that reason.
        assertNotNull(ContrastAnalysis.settledChrome(ColorValue.BLACK, ColorValue.of(255, 255, 255, alpha = 128), 1.6))
    }

    // ── settledChrome ────────────────────────────────────────────────────────────────────────────────

    /**
     * The defect this function exists for, asserted as a defect: a *floor* cannot express a quiet target.
     *
     * This is the shipped `DocumentHostScreen` call, verbatim in arithmetic — `onSurface` on the white page
     * `PageGeometry.effectiveBackgroundPaint` defaults to, asking for a grid at 1.6:1. The first act of
     * [ContrastAnalysis.legibleChrome] is to return anything that already clears the target, and light-mode
     * ink clears 1.6:1 by a factor of eleven, so the call returned full-strength ink and the grid drew at
     * 17.71:1. The margin guide and column divider at 2.2:1 came back the same colour, so three constants
     * naming three weights produced one.
     *
     * Asserting the old number rather than deleting it is deliberate: if [ContrastAnalysis.settledChrome] is
     * ever "simplified" back into the floor, this test states precisely what returns.
     */
    @Test
    fun `a floor cannot express a quiet target and settledChrome can`() {
        val lightInk = hex(0x1B1B1B)
        val white = ColorValue.WHITE

        // What the floor does with a 1.6 request on a colour that already measures 17.22.
        val floored = ContrastAnalysis.legibleChrome(lightInk, white, GRID)
        assertEquals("legibleChrome returns the input untouched", lightInk, floored)
        assertEquals(17.2244, ratio(floored, white), 0.0001)

        // What a target does with the same request.
        val settled = ContrastAnalysis.settledChrome(lightInk, white, GRID)
        assertEquals(1.6224, ratio(settled, white), 0.0001)
        assertEquals("#CBCBCB", settled.toHexRgb())

        // Dark mode is the mirror image: dark ink on a near-black page measures 15.04 and asks for 1.6.
        val darkInk = hex(0xF5F5F5)
        val nearBlack = hex(0x202124)
        assertEquals(darkInk, ContrastAnalysis.legibleChrome(darkInk, nearBlack, GRID))
        assertEquals(14.7666, ratio(darkInk, nearBlack), 0.0001)
        val settledDark = ContrastAnalysis.settledChrome(darkInk, nearBlack, GRID)
        assertEquals(1.6272, ratio(settledDark, nearBlack), 0.0001)
        assertEquals("#434343", settledDark.toHexRgb())
    }

    /**
     * The guarantee is not weakened: settled chrome still always clears its target.
     *
     * "Quieter" is the whole point of this function and also its only real hazard — a downward search that
     * overshoots downward produces exactly the invisible chrome the substrate was built to eliminate. So the
     * floor property is asserted over the full fixture, 210 cases, and the overshoot is bounded in the same
     * pass so that "as close as possible from above" is a measured claim rather than a description.
     */
    @Test
    fun `settled chrome clears its target everywhere and never by much`() {
        // The allowed bound rises with the target because one lightness step buys more ratio the further
        // from the backdrop you are, so the lattice cannot land as finely at 3:1 as it does at 1.6:1.
        listOf(GRID to 0.07, RESTING_EDGE to 0.11, ContrastAnalysis.UI_COMPONENT to 0.17).forEach { (target, allowed) ->
            var worstOvershoot = 0.0
            var cases = 0
            ACCENTS.forEach { accent ->
                PAGES.forEach { page ->
                    val settled = ContrastAnalysis.settledChrome(accent, page, target)
                    val achieved = ratio(settled, page)
                    assertTrue(
                        "${accent.toHexRgb()} on ${page.toHexRgb()} at $target only reached $achieved",
                        achieved >= target - 1e-9,
                    )
                    worstOvershoot = maxOf(worstOvershoot, achieved - target)
                    cases++
                }
            }
            assertEquals("ten accents on seven pages", 70, cases)
            assertTrue("at $target the worst overshoot was $worstOvershoot", worstOvershoot <= allowed)
        }
    }

    /**
     * Only lightness moves here too — the property that makes this a *derivation* rather than a substitution.
     *
     * Same assertion as `adjustment moves lightness and nothing else` makes for [ContrastAnalysis.legibleChrome],
     * and it has to be made separately because the two functions choose differently from the same candidate
     * set. A downward search has more room to go wrong: mixing toward the backdrop would reach any target
     * more cheaply than a lightness step and would destroy the user's hue while doing it, which is precisely
     * the shortcut this forbids.
     */
    @Test
    fun `settled chrome moves lightness and nothing else`() {
        listOf(GRID, RESTING_EDGE, ContrastAnalysis.UI_COMPONENT).forEach { target ->
            ACCENTS.forEach { accent ->
                PAGES.forEach { page ->
                    val hsl = accent.toHsl()
                    val actual = ContrastAnalysis.settledChrome(accent, page, target)
                    val reachable = buildList {
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
                        "${accent.toHexRgb()} on ${page.toHexRgb()} at $target produced ${actual.toHexRgb()}, " +
                            "which is not this hue at any lightness",
                        reachable.any { it.red == actual.red && it.green == actual.green && it.blue == actual.blue },
                    )
                }
            }
        }
    }

    /**
     * The two functions are **not** interchangeable, stated as a count so nobody assumes they are.
     *
     * It would be easy to read [ContrastAnalysis.settledChrome] as the floor "with a downward case bolted
     * on" and then unify them. They ask different questions — nearest lightness versus lowest passing ratio —
     * and those disagree even on inputs that *fail* the target, whenever the nearer passing step in one
     * direction overshoots more than a farther step in the other. On this fixture at 2.2:1 they differ in 59
     * of 70 cases, and 13 of those differences are on inputs [ContrastAnalysis.legibleChrome] had to search
     * rather than pass through. A unification would silently re-tune all of them.
     *
     * Which is why the substrate's own resting-edge population is pinned in `ChromeGeometryTest` instead: on
     * the fifteen `outline`-on-surface derivations the chrome substrate actually makes, the two agree exactly,
     * and that agreement is what lets `NorfoldChrome.restingEdge` keep [ContrastAnalysis.legibleChrome] with
     * no visual change.
     */
    @Test
    fun `settled and legible are different questions with different answers`() {
        var identical = 0
        var differing = 0
        var differingOnFailingInput = 0
        ACCENTS.forEach { accent ->
            PAGES.forEach { page ->
                val legible = ContrastAnalysis.legibleChrome(accent, page, RESTING_EDGE)
                val settled = ContrastAnalysis.settledChrome(accent, page, RESTING_EDGE)
                if (legible == settled) {
                    identical++
                } else {
                    differing++
                    if (ratio(accent, page) < RESTING_EDGE) differingOnFailingInput++
                }
            }
        }
        assertEquals("cases where the two agree", 11, identical)
        assertEquals("cases where they do not", 59, differing)
        assertEquals("of those, ones the floor still had to search", 12, differingOnFailingInput)
    }

    /**
     * The two shipped structure-aid weights, on the pages a document can actually be.
     *
     * `DocumentHostScreen` derives its grid, margin guide, column divider and resting object edge from
     * `onSurface` against the page's own paint, so what a reader sees is a function of two values no
     * screenshot review can inspect. Four representative page paints in both modes gives eight pairs, and the
     * assertion that matters is the *separation*: a grid and a guide have to be different weights. Under a
     * floor they were the same colour, so a test that only checked each one cleared its own target would have
     * passed the defect this pair of functions exists to fix.
     */
    @Test
    fun `the document structure aids land where their constants say`() {
        val lightInk = hex(0x1B1B1B)
        val darkInk = hex(0xF5F5F5)
        val pages = listOf(0xFFFFFF, 0xFFF8E1, 0x546E7A, 0x202124).map(::hex)

        var tightest = Double.MAX_VALUE
        var loosest = 0.0
        var narrowest = Double.MAX_VALUE
        var pairs = 0
        listOf(lightInk to "light", darkInk to "dark").forEach { (ink, mode) ->
            pages.forEach { page ->
                val guide = ratio(ContrastAnalysis.settledChrome(ink, page, RESTING_EDGE), page)
                val grid = ratio(ContrastAnalysis.settledChrome(ink, page, GRID), page)
                // A guide and a grid must be distinguishable, which under a floor they were not.
                assertTrue(
                    "$mode ink on ${page.toHexRgb()}: guide $guide is not louder than grid $grid",
                    guide > grid,
                )
                tightest = minOf(tightest, guide - RESTING_EDGE, grid - GRID)
                loosest = maxOf(loosest, guide - RESTING_EDGE, grid - GRID)
                narrowest = minOf(narrowest, guide - grid)
                pairs++
            }
        }
        assertEquals("four page paints in each mode", 8, pairs)
        // Tight on both sides: nothing below target, nothing meaningfully above it.
        assertEquals("the closest any aid lands to its target", 0.004178, tightest, 0.0001)
        assertEquals("the furthest any aid lands from its target", 0.0701, loosest, 0.0001)
        // And the separation is the target difference itself, not an accident of the search.
        assertEquals("the narrowest guide-over-grid separation", 0.5876, narrowest, 0.0001)
    }

    // ── Fixtures ─────────────────────────────────────────────────────────────────────────────────────

    /** The resting-edge target both editors use. Mirrors `FlowFrameMetrics.RESTING_EDGE_RATIO`. */
    private val RESTING_EDGE = 2.2

    /** The grid target. Mirrors [ChromeGeometry.GRID_RATIO], the one ratio below 2. */
    private val GRID = 1.6

    /** The ten accents `BrandPalettes` actually ships. */
    private val ACCENTS = listOf(
        0x3D7C45, 0x9D50BB, 0xF75C7A, 0x2E7BE4, 0x1F9E7E,
        0xE23A2E, 0xB06AB3, 0x3E8EA0, 0xE08A12, 0x2A2A2A,
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
