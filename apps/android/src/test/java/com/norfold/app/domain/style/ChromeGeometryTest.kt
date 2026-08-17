package com.norfold.app.domain.style

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The interaction-chrome substrate's geometry, pinned.
 *
 * This file is the whole reason [ChromeGeometry] holds `Float` in the domain layer instead of `Dp` in the UI
 * layer. The substrate exists because the tree had accumulated nine distinct `shadowElevation` values, eight
 * `tonalElevation` values, seven chrome radii and five border widths, none of which encoded a distinction a
 * user could name. Consolidating those into three lifts, three corners and two weights only *stays*
 * consolidated if a test can read the numbers — and the unit source set has no Compose harness, so a `Dp`
 * would be documented as consolidated and never asserted as consolidated. That is exactly how the spread
 * happened the first time.
 *
 * What these tests can and cannot see: they assert the *values and their relationships*, which is where the
 * regression risk is. They cannot assert that a composable draws them, because rendering is device-only.
 */
class ChromeGeometryTest {

    // ── Lift ─────────────────────────────────────────────────────────────────────────────────────────

    /**
     * Three tiers, strictly ordered, and no two of them within a distance a user cannot see.
     *
     * The ordering is the substance. The inventory's problem was not that it had 9 values; it was that 12 dp
     * and 14 dp both existed while meaning the same thing. Anything closer than a doubling reads as noise at
     * arm's length, so each tier must be at least twice its predecessor rather than merely greater.
     */
    @Test
    fun `lift tiers are ordered and separated by more than a rendering difference`() {
        val tiers = ChromeGeometry.Lift.entries
        assertEquals(3, tiers.size)

        val shadows = tiers.map { it.shadowDp }
        assertEquals(listOf(0f, 8f, 16f), shadows)

        // Every non-flat step at least doubles, so no two tiers differ only in a way the renderer can draw.
        tiers.zipWithNext { lower, higher ->
            assertTrue(
                "${higher.name} must be clearly above ${lower.name}",
                higher.shadowDp >= lower.shadowDp * 2 || lower.shadowDp == 0f,
            )
            assertTrue("${higher.name} tonal must not fall below ${lower.name}", higher.tonalDp >= lower.tonalDp)
        }
    }

    /**
     * Page-level chrome casts no shadow at all.
     *
     * Not a stylistic preference. A shadow is a claim about height, and a framed object that casts one is
     * asserting that the paragraph beside it is further away — which is false. Containment is stated by the
     * edge instead.
     */
    @Test
    fun `flat chrome has no elevation of either kind`() {
        assertEquals(0f, ChromeGeometry.Lift.Flat.shadowDp, 0f)
        assertEquals(0f, ChromeGeometry.Lift.Flat.tonalDp, 0f)
    }

    // ── Corner and padding ───────────────────────────────────────────────────────────────────────────

    /**
     * Three corner tiers, and each keeps the value the tree had already reviewed.
     *
     * The Flow object frame shipped 14 dp for media and 16 dp for cards after review, and the Flow dock
     * shipped 22 dp; generalising those is a smaller claim than inventing three new numbers, and it is why
     * the object frame can delegate here without any visual change.
     */
    @Test
    fun `corner tiers keep the radii that were already reviewed`() {
        assertEquals(3, ChromeGeometry.Corner.entries.size)
        assertEquals(14f, ChromeGeometry.Corner.Media.radiusDp, 0f)
        assertEquals(16f, ChromeGeometry.Corner.Card.radiusDp, 0f)
        assertEquals(22f, ChromeGeometry.Corner.Capsule.radiusDp, 0f)
    }

    /**
     * Padding clears its own curve.
     *
     * Content set closer to a corner than its radius is visibly clipped by the arc, so padding is not a free
     * choice beside a radius — it is bounded below by it. Media is the deliberate exception: full-bleed
     * content has no padding by definition, and the clip is the point.
     */
    @Test
    fun `card and capsule padding clears the corner arc`() {
        assertEquals(0f, ChromeGeometry.paddingFor(ChromeGeometry.Corner.Media), 0f)

        for (corner in listOf(ChromeGeometry.Corner.Card, ChromeGeometry.Corner.Capsule)) {
            val padding = ChromeGeometry.paddingFor(corner)
            assertTrue("$corner padding must be positive", padding > 0f)
            // The arc intrudes by r*(1 - 1/sqrt(2)) at 45 degrees; padding must clear that.
            val intrusion = corner.radiusDp * (1f - 1f / kotlin.math.sqrt(2f))
            assertTrue(
                "$corner padding $padding must clear its own arc intrusion $intrusion",
                padding >= intrusion,
            )
        }
    }

    // ── Edges ────────────────────────────────────────────────────────────────────────────────────────

    /** A selection edge has to read at arm's length, which one dp of extra weight does not. */
    @Test
    fun `selection edge is meaningfully heavier than a resting edge`() {
        assertEquals(1f, ChromeGeometry.RESTING_EDGE_WIDTH, 0f)
        assertEquals(3f, ChromeGeometry.SELECTED_EDGE_WIDTH, 0f)
        assertTrue(
            "a selection edge one dp heavier than resting is not legible on a phone",
            ChromeGeometry.SELECTED_EDGE_WIDTH >= ChromeGeometry.RESTING_EDGE_WIDTH * 2,
        )
    }

    /**
     * The resting target sits in the band where a boundary is visible but is not a rule.
     *
     * Below roughly 1.5:1 a non-text boundary cannot be seen at all; at 3:1 and above it competes with the
     * content it frames, which is how a page of framed objects starts reading as a spreadsheet.
     */
    @Test
    fun `resting edge target is visible without competing with content`() {
        assertTrue(ChromeGeometry.RESTING_EDGE_RATIO >= 1.5)
        assertTrue(ChromeGeometry.RESTING_EDGE_RATIO < ContrastAnalysis.UI_COMPONENT)
    }

    /**
     * A focus ring is held to the standard, not to the substrate's quieter resting target.
     *
     * A resting edge may be quiet because the user is not looking for it. A focus ring is the only thing
     * telling a keyboard or switch-access user where their next action lands, so it takes WCAG 1.4.11's 3:1
     * with no negotiation.
     */
    @Test
    fun `focus ring is held to the WCAG non-text contrast minimum`() {
        assertEquals(ContrastAnalysis.UI_COMPONENT, ChromeGeometry.FOCUS_RING_RATIO, 0.0)
        assertTrue(
            "a focus ring must not be quieter than a resting edge",
            ChromeGeometry.FOCUS_RING_RATIO > ChromeGeometry.RESTING_EDGE_RATIO,
        )
        assertTrue("a focus ring needs weight the edge it decorates does not", ChromeGeometry.FOCUS_RING_WIDTH > ChromeGeometry.RESTING_EDGE_WIDTH)
        assertTrue("a ring drawn flush reads as a thicker border, not as focus", ChromeGeometry.FOCUS_RING_GAP > 0f)
    }

    // ── The finding that motivated the derived edge ──────────────────────────────────────────────────

    /**
     * **Neither theme outline token can state a boundary, on any surface, in either mode.**
     *
     * This is the measurement the substrate rests on, and it is asserted rather than described because it is
     * the justification for deriving the edge instead of using `outline` directly. On the shipped palette
     * every one of the twelve token/surface combinations lands below the ~1.5:1 a non-text boundary needs to
     * be seen at all — so the 39 `BorderStroke(1.dp, outlineVariant)` sites in the tree are drawing an edge
     * that exists in the source and not on the screen.
     */
    @Test
    fun `no theme outline token reaches visibility on any app surface`() {
        val lightSurfaces = listOf(0xF7F8FC, 0xFFFFFF, 0xF1F2F7)
        val darkSurfaces = listOf(0x070A12, 0x0E131F, 0x151B28)

        for ((outline, surfaces) in listOf(
            listOf(0xE4E6EF, 0xEEF0F5) to lightSurfaces,
            listOf(0x252C3A, 0x1B2230) to darkSurfaces,
        )) {
            for (token in outline) {
                for (surface in surfaces) {
                    val measured = ContrastAnalysis.ratio(hex(token), hex(surface))
                    assertTrue(
                        "outline %06X on %06X measures %.2f:1, which would be visible".format(token, surface, measured),
                        measured < 1.5,
                    )
                }
            }
        }
    }

    /**
     * Reducing a border's alpha makes an already-invisible edge worse, not softer.
     *
     * Eight sites in the tree apply an alpha between 0.18 and 0.7 to an outline token. Composited on their
     * own surface every one of them lands between roughly 1.02:1 and 1.11:1 — flatter than the opaque token
     * they started from, which was already below visibility. Pinned so a future "soften this border" change
     * has to argue with a number.
     */
    @Test
    fun `alpha on an outline token only flattens it further`() {
        val surface = hex(0xFFFFFF)
        val token = hex(0xEEF0F5)
        val opaque = ContrastAnalysis.ratio(token, surface)

        for (alpha in listOf(0.7, 0.55, 0.5, 0.4, 0.36, 0.35, 0.30, 0.18)) {
            val faded = token.withAlpha((alpha * 255).toInt()).compositedOver(surface)
            val measured = ContrastAnalysis.ratio(faded, surface)
            assertTrue(
                "alpha $alpha measures %.2f:1, which is not below the opaque %.2f:1".format(measured, opaque),
                measured < opaque,
            )
            assertTrue("alpha $alpha at %.2f:1 is nowhere near visible".format(measured), measured < 1.2)
        }
    }

    /**
     * The derived edge reaches its target on every app surface, in both modes — which is the thing the
     * tokens could not do.
     *
     * Exhaustive over the six surfaces rather than sampled, because the point of deriving is that the answer
     * holds wherever the chrome lands. The tolerance is one hundredth: [ContrastAnalysis.suggestAccessible]
     * searches lightness in discrete steps, so it clears the target rather than hitting it exactly.
     */
    @Test
    fun `the derived resting edge reaches its target on every app surface`() {
        val cases = listOf(
            0xE4E6EF to listOf(0xF7F8FC, 0xFFFFFF, 0xF1F2F7),
            0x252C3A to listOf(0x070A12, 0x0E131F, 0x151B28),
        )
        for ((outline, surfaces) in cases) {
            for (surface in surfaces) {
                val backdrop = hex(surface)
                val edge = ContrastAnalysis.legibleChrome(hex(outline), backdrop, ChromeGeometry.RESTING_EDGE_RATIO)
                val measured = ContrastAnalysis.ratio(edge, backdrop)
                assertTrue(
                    "derived edge on %06X measures %.2f:1, below the %.2f:1 target".format(surface, measured, ChromeGeometry.RESTING_EDGE_RATIO),
                    measured >= ChromeGeometry.RESTING_EDGE_RATIO - 0.01,
                )
            }
        }
    }

    private fun hex(rgb: Int) = ColorValue.of((rgb shr 16) and 0xFF, (rgb shr 8) and 0xFF, rgb and 0xFF)
}
