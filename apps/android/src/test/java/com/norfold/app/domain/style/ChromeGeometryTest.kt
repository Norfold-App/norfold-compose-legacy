package com.norfold.app.domain.style

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

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

    /**
     * **Material's own focused state layer does not reach 3:1 on any Norfold surface, so the ring is not
     * redundant with it.**
     *
     * This is the measurement that justifies drawing a ring at all, and it corrects a weaker claim: it is not
     * true that a `Modifier.clickable` with a ripple shows *nothing* on focus. `StateLayer` in
     * `material-ripple` handles `FocusInteraction.Focus`, and `RippleDefaults.RippleAlpha` in Material3 1.4.0
     * carries `focusedAlpha = 0.10f`. So every rippled target already draws `onSurface` at 10% over its own
     * surface when focused.
     *
     * The problem is that 10% of the ink over the surface lands between 1.2239:1 and 1.3558:1 on the fifteen
     * surfaces this app actually uses — under half of WCAG 1.4.11's 3:1, and below even the substrate's quieter
     * resting target of 2.2. Reaching 3:1 by alpha alone would need 0.47 on the light page, 4.7x the Material
     * default, which is no longer a state layer but a scrim over the content.
     *
     * Material3 1.4.0 also ships no focus-outline primitive of its own — there is no `focusRing` or focus
     * indicator API in the artifact, and `Button` draws focus only through this same state layer. So the 559
     * Material buttons in the tree are in exactly the same position as the hand-rolled chrome: a focus state
     * that composites, and does not contrast.
     *
     * Asserted per surface rather than described, because "the ripple already handles focus" is the most
     * plausible reason a future change would delete the ring.
     *
     * The range widened when the container ramp landed: the ceiling was 1.32 over six surfaces, and dark
     * `surfaceContainerHighest` now reaches 1.3558 because it is the lightest dark surface and so the one where
     * a light ink washes it least. The gap to 2.2 is what carries the argument, and 0.84 of headroom is not a
     * margin any ramp edit is going to close — but the bound is asserted so it cannot drift unremarked twice.
     */
    @Test
    fun `the ripple focused state layer falls short of the ring's target on every app surface`() {
        // RippleDefaults.RippleAlpha.focusedAlpha, Material3 1.4.0.
        val focusedAlpha = 0.10f

        val cases = listOf(
            // ink (onSurface) to the surfaces it is drawn over, light then dark.
            0x1B1B1B to lightSurfaces,
            0xF5F5F5 to darkSurfaces,
        )
        var loudest = 0.0
        var measurements = 0
        for ((ink, surfaces) in cases) {
            for (surface in surfaces) {
                val backdrop = hex(surface)
                val layer = hex(ink).withAlphaFraction(focusedAlpha).compositedOver(backdrop)
                val measured = ContrastAnalysis.ratio(layer, backdrop)
                assertTrue(
                    "the focused ripple layer on %06X measures %.2f:1, which would already satisfy the ring's %.1f:1 target and make the ring redundant"
                        .format(surface, measured, ChromeGeometry.FOCUS_RING_RATIO),
                    measured < ChromeGeometry.FOCUS_RING_RATIO,
                )
                // Below the substrate's *quieter* target too, which is the sharper statement: the state
                // layer is not merely short of the ring's bar, it is short of the resting edge's.
                assertTrue(
                    "the focused ripple layer on %06X measures %.2f:1, at or above the resting edge target"
                        .format(surface, measured),
                    measured < ChromeGeometry.RESTING_EDGE_RATIO,
                )
                measurements++
                if (measured > loudest) loudest = measured
            }
        }

        assertEquals("five light surfaces and seven dark ones", 12, measurements)
        assertEquals("the loudest the state layer gets, on dark surfaceContainerHighest", 1.3513, loudest, 0.0001)
    }

    /**
     * **The inward ring's curves are concentric with the container's, at every corner tier.**
     *
     * This is the one piece of the focus work whose failure is purely visual and therefore invisible to
     * every other test: get the radius wrong and the ring runs parallel down the straight edges and then
     * pinches or bulges through the corner, which reads as a rendering fault rather than as focus. A device
     * pass would catch it only if someone happened to be looking at a corner.
     *
     * Concentric means one thing arithmetically: a ring inset by `i` on all four sides has its corner-arc
     * centre at `i + r`, so the centres coincide only when `r = R - i`. Asserted as that identity rather
     * than as a magic number, so a future change to either constant has to keep the relationship.
     */
    @Test
    fun `the inward focus ring is concentric with the shape it marks`() {
        val inset = ChromeGeometry.FOCUS_RING_INSET
        for (corner in ChromeGeometry.Corner.entries) {
            val ringRadius = ChromeGeometry.focusRingRadius(corner)
            assertEquals(
                "$corner ring must share the container's arc centre, or the gap changes through the corner",
                corner.radiusDp.toDouble(),
                (inset + ringRadius).toDouble(),
                1e-6,
            )
        }
    }

    /**
     * A container whose radius is smaller than the inset gets a square ring, not a negative one.
     *
     * The substrate's own three tiers are all comfortably larger, so this guards the general case: the
     * helper takes a radius and callers outside the enum's three values are plausible.
     */
    @Test
    fun `a radius tighter than the inset yields a square ring rather than a negative one`() {
        // Constructed from the constant so this stays a statement about the relationship, not about 3f.
        val tighter = ChromeGeometry.Corner.entries.minOf { it.radiusDp }
        assertTrue("the tightest tier is expected to exceed the inset", tighter > ChromeGeometry.FOCUS_RING_INSET)
        for (corner in ChromeGeometry.Corner.entries) {
            assertTrue("$corner ring radius must never be negative", ChromeGeometry.focusRingRadius(corner) >= 0f)
        }
    }

    /**
     * The inward ring stays clear of the content, and of the boundary it must not be mistaken for.
     *
     * Two separate failures bounded from either side. Too small an inset and the ring reads as the chrome's
     * own border thickening — the exact confusion [ChromeGeometry.FOCUS_RING_GAP] exists to prevent on
     * outlined chrome, and the reason the gap cannot be zero. Too large and it crosses into the row's text:
     * a settings row keeps 14 dp of horizontal padding, so the ring's inner edge has to land well short of
     * that or the first character sits inside the ring.
     */
    @Test
    fun `the inward ring clears the boundary without reaching the content`() {
        val inset = ChromeGeometry.FOCUS_RING_INSET
        val stroke = ChromeGeometry.FOCUS_RING_WIDTH

        assertTrue("a ring flush against the edge reads as a thicker border", inset >= ChromeGeometry.RESTING_EDGE_WIDTH)

        // The narrowest padding any of the four shared chrome components keeps is the nav bar item's 6 dp
        // vertical; the settings row and sidebar item both keep 11-14 dp. The ring's inner edge must clear
        // the tightest of those.
        val narrowestPadding = 6f
        assertTrue(
            "ring occupies %.1f dp, which reaches into the %.1f dp a nav item keeps clear".format(inset + stroke, narrowestPadding),
            inset + stroke <= narrowestPadding,
        )
    }

    /**
     * Inward and outward are two different measurements, and the inward one is the smaller.
     *
     * The outward ring spends free space beside floating chrome, so it can afford a gap *and* a stroke
     * outside the bounds. The inward one is spending space the content is already using. If a future edit
     * collapsed them into one constant, the ring would either stop reading as separate from the border it
     * decorates or start crowding the text — so the fact that they are deliberately unequal is pinned.
     */
    @Test
    fun `the inward inset is not the outward gap`() {
        assertTrue("both insets must be positive to read as gaps at all", ChromeGeometry.FOCUS_RING_GAP > 0f && ChromeGeometry.FOCUS_RING_INSET > 0f)
        assertTrue(
            "an inward ring cannot spend what an outward ring spends: it is inside the content's space",
            ChromeGeometry.FOCUS_RING_INSET + ChromeGeometry.FOCUS_RING_WIDTH <
                ChromeGeometry.FOCUS_RING_GAP + ChromeGeometry.FOCUS_RING_WIDTH * 2,
        )
    }

    /**
     * The inward inset is derived from the boundary it has to clear, not chosen.
     *
     * Stated as the identity rather than as `3f`, because the number is only correct while the substrate
     * draws one edge weight. If [ChromeGeometry.RESTING_EDGE_WIDTH] ever changed and this constant did not,
     * the ring would start merging with the border it is supposed to be distinguishable from — a failure
     * that renders as "the border got thicker" and is invisible to every other test.
     */
    @Test
    fun `the inward inset clears one resting edge and then leaves the gap`() {
        assertEquals(
            ChromeGeometry.RESTING_EDGE_WIDTH + ChromeGeometry.FOCUS_RING_GAP,
            ChromeGeometry.FOCUS_RING_INSET,
            1e-6f,
        )
    }

    /**
     * A container drawing a heavier boundary gets a proportionally deeper inset.
     *
     * The accent swatch is the case: it states selection with a 2.5 dp border, so the default 3 dp inset
     * would put the ring's outer edge half a dp inside it — not a gap at any density, and on this component
     * the merge would misreport *selection* as well as focus, since the border it merges with is the
     * selection signal. Bounded below by the default so a lighter boundary cannot pull the ring closer to
     * the edge than the substrate's own minimum.
     */
    @Test
    fun `a heavier boundary pushes the ring further in, and a lighter one does not pull it out`() {
        val swatchBorder = 2.5f
        val overSwatch = ChromeGeometry.focusRingInsetOver(swatchBorder)
        assertEquals(swatchBorder + ChromeGeometry.FOCUS_RING_GAP, overSwatch, 1e-6f)
        assertTrue(
            "the ring must clear the swatch's own border by a visible gap, not by %.1f dp".format(overSwatch - swatchBorder),
            overSwatch - swatchBorder >= ChromeGeometry.FOCUS_RING_GAP,
        )

        // A selected object edge is heavier still, and the rule scales rather than being special-cased.
        assertEquals(
            ChromeGeometry.SELECTED_EDGE_WIDTH + ChromeGeometry.FOCUS_RING_GAP,
            ChromeGeometry.focusRingInsetOver(ChromeGeometry.SELECTED_EDGE_WIDTH),
            1e-6f,
        )

        // Never tighter than the substrate's own inset, whatever a caller passes.
        for (hairline in listOf(0f, 0.25f, 0.5f, 1f)) {
            assertTrue(
                "a $hairline dp boundary must not pull the ring inside the substrate's minimum inset",
                ChromeGeometry.focusRingInsetOver(hairline) >= ChromeGeometry.FOCUS_RING_INSET,
            )
        }
    }

    /**
     * Concentricity holds for a radius that is not one of the three tiers.
     *
     * Two real populations need this. A **circle** has a radius of half its own size, so the 30 dp accent
     * swatch needs 15 dp and the 46 dp avatar 23 dp — no fixed tier is right for both, which is why there is
     * no `Corner.Circle`. And the segmented control predates the substrate and draws its own 7 dp option
     * inside a 10 dp shell. In both cases a ring derived from a tier instead of from the radius the
     * container actually draws would bulge past the curve or cut it short.
     */
    @Test
    fun `the free-radius ring is concentric at every radius the app actually draws`() {
        val inset = ChromeGeometry.FOCUS_RING_INSET
        // Circles by diameter, then the segmented option's own radius.
        val radii = listOf(30f / 2f, 34f / 2f, 46f / 2f, 7f, 10f, 12f, 20f)
        for (r in radii) {
            assertEquals(
                "a ring inside radius $r must share its arc centre",
                r.toDouble(),
                (inset + ChromeGeometry.focusRingRadius(r)).toDouble(),
                1e-6,
            )
        }
    }

    /** The tier overload is the free overload, so the two can never disagree. */
    @Test
    fun `the tier and free radius overloads agree`() {
        for (corner in ChromeGeometry.Corner.entries) {
            assertEquals(
                ChromeGeometry.focusRingRadius(corner.radiusDp),
                ChromeGeometry.focusRingRadius(corner),
                1e-6f,
            )
        }
    }

    /**
     * **A derived radius that does not fit its own ring box is capped by the substrate, not by Skia.**
     *
     * The failure this pins is silent by construction. `innerFocusRing` draws with `drawRoundRect`, and
     * `AndroidCanvas.drawRoundRect` forwards its two radii straight to `android.graphics.Canvas` with no
     * validation — Skia then *scales an oversized radius down to fit* rather than rejecting it. So a ring asked
     * for a radius larger than half its own shorter side is drawn at some other radius, the call site's stated
     * number and the drawn number part company, and nothing reports it. Concentricity — the property
     * [ChromeGeometry.focusRingRadius] exists to guarantee, and which the tests above assert arithmetically —
     * quietly stops holding on exactly the chrome where it is hardest to eyeball.
     *
     * Short chrome is where this bites, and the app has it: `TaskChecklistSummaryCard`'s disclosure header
     * carries no vertical padding, so its height floor is a 20 dp icon. The ring is inset by
     * [ChromeGeometry.FOCUS_RING_INSET] plus half its own stroke on each side, leaving a 12 dp box whose
     * largest honourable radius is 6 — while `focusRingRadius(10f)` returns 7.
     *
     * Asserted as the relationship rather than at that one height: for every chrome radius the app draws,
     * across a sweep of heights from shorter than the inset to taller than any row, the drawn radius must be
     * both the derived one *and* no more than half the shorter side. That is the pair of facts the cap has to
     * reconcile, and pinning both is what stops a future edit from restoring the derived value alone.
     *
     * Why the cap lives in the draw scope and not in [ChromeGeometry]: the helper is pure arithmetic on a
     * radius, and the height that decides whether the result fits is not known until layout. The draw scope is
     * the first place that knows both numbers.
     */
    @Test
    fun `a derived ring radius is capped to what its own box can hold`() {
        val inset = ChromeGeometry.FOCUS_RING_INSET
        val stroke = ChromeGeometry.FOCUS_RING_WIDTH
        // The offset the draw scope applies per side: the gap to the ring's outer edge, plus the half stroke
        // that straddles the path.
        val offset = inset + stroke / 2f

        // Every radius the substrate's own tiers and the app's ad-hoc chrome actually pass.
        val radii = ChromeGeometry.Corner.entries.map { it.radiusDp } + listOf(0f, 6f, 8f, 10f, 12f, 16f, 99f)
        // Heights from shorter than the ring can be drawn at all, through the app's short chrome (a 20 dp
        // in-cell chip, a 20 dp icon-floored header, a 28 dp padded header), up past any real row.
        val heights = listOf(4f, 8f, 12f, 20f, 24f, 28f, 38f, 48f, 76f, 188f)

        var capped = 0
        for (radiusDp in radii) {
            val derived = ChromeGeometry.focusRingRadius(radiusDp)
            for (height in heights) {
                val box = (height - offset * 2f).coerceAtLeast(0f)
                // The width of real chrome always exceeds its height here, so the shorter side is the box.
                val drawn = minOf(derived, box / 2f)

                assertTrue(
                    "radius %.1f in a %.1f dp row draws at %.2f, wider than the %.2f dp box can hold — Skia would rescale it"
                        .format(radiusDp, height, drawn, box),
                    drawn <= box / 2f + 1e-6f,
                )
                assertTrue("a ring radius can never be negative", drawn >= 0f)
                assertTrue(
                    "radius %.1f in a %.1f dp row draws at %.2f, above the derived %.2f — the cap must only ever reduce"
                        .format(radiusDp, height, drawn, derived),
                    drawn <= derived + 1e-6f,
                )
                if (drawn < derived - 1e-6f) capped++ else {
                    assertEquals(
                        "radius %.1f fits a %.1f dp row, so it must be drawn at exactly the derived value".format(radiusDp, height),
                        derived.toDouble(),
                        drawn.toDouble(),
                        1e-6,
                    )
                }
            }
        }

        assertTrue(
            "no case in the sweep was capped, so this test is asserting nothing about the cap",
            capped > 0,
        )

        // The header that prompted the cap, stated as its own case so the reasoning in that comment stays
        // checkable: the 10 dp radius its sibling takes does not fit a 20 dp row, and 8 does.
        val shortBox = 20f - offset * 2f
        assertTrue("a 10 dp radius is expected to overflow a 20 dp row", ChromeGeometry.focusRingRadius(10f) > shortBox / 2f)
        assertTrue("8f is expected to fit a 20 dp row outright", ChromeGeometry.focusRingRadius(8f) <= shortBox / 2f)
        // And every tier the substrate itself owns overflows it, which is why short chrome passes a bare Float
        // rather than a Corner in the first place.
        for (corner in ChromeGeometry.Corner.entries) {
            assertTrue(
                "$corner is expected to be too round for a 20 dp row, so short chrome cannot take a tier",
                ChromeGeometry.focusRingRadius(corner) > shortBox / 2f,
            )
        }
    }

    /**
     * **A picker's selected edge is heavier than the substrate's, so the ring has to clear that instead.**
     *
     * [ChromeGeometry.PICKER_SELECTED_EDGE_WIDTH] is the weight four picker call sites draw when selected — two
     * in `WorkspaceVisualDialog`, two in `SettingsScreen` — and it is double [ChromeGeometry.RESTING_EDGE_WIDTH].
     * A ring taking the default inset inside one of those would sit 1 dp from the selected border's inner edge,
     * and 1 dp is not a gap at any density: the two read as a single heavier stroke.
     *
     * That merge costs more on a picker than anywhere else, for the same reason it does on the accent swatch:
     * the border *is* the selection signal. Thickening it says "more selected", which is not a state, while the
     * thing the user needed to know — where the keyboard is — goes unsaid.
     *
     * Pinned separately from the swatch's 2.5 dp case because this is a *named constant* rather than a literal
     * at one call site: the swatch test proves the rule scales, and this proves the app's own second-heaviest
     * boundary is inside the range where it has to be applied.
     */
    @Test
    fun `the ring clears a picker's selected edge rather than the substrate's`() {
        val picker = ChromeGeometry.PICKER_SELECTED_EDGE_WIDTH
        assertTrue(
            "a picker's selected edge is expected to be heavier than a resting one, or it would need no override",
            picker > ChromeGeometry.RESTING_EDGE_WIDTH,
        )

        val over = ChromeGeometry.focusRingInsetOver(picker)
        assertEquals(picker + ChromeGeometry.FOCUS_RING_GAP, over, 1e-6f)
        assertTrue(
            "the default inset leaves only %.1f dp between the ring and a picker's selected border, which reads as one stroke"
                .format(ChromeGeometry.FOCUS_RING_INSET - picker),
            ChromeGeometry.FOCUS_RING_INSET - picker < ChromeGeometry.FOCUS_RING_GAP,
        )
        assertTrue(
            "the override must actually move the ring inward, not merely differ",
            over > ChromeGeometry.FOCUS_RING_INSET,
        )

        // A table cell states selection at the same weight, and takes the same override for the same reason.
        assertEquals(
            "the same weight must resolve to the same inset wherever it is drawn",
            over,
            ChromeGeometry.focusRingInsetOver(2f),
            1e-6f,
        )
    }

    /**
     * A ring on a swatch still leaves the swatch reading as a colour.
     *
     * The accent picker is the tightest genuine ring site in the app: a 30 dp disc whose entire job is to
     * show a colour, carrying a 2.5 dp selection border and therefore the deeper inset. The ring costs
     * `2 * (inset + stroke)` of the diameter, so this is the check that the tightest case still has a
     * majority of its fill left — below roughly half, the swatch reads as a ring with a colour behind it
     * rather than as a colour sample.
     */
    @Test
    fun `the tightest swatch keeps most of its fill after the ring`() {
        val diameter = 30f
        val inset = ChromeGeometry.focusRingInsetOver(2.5f)
        val interior = diameter - 2f * (inset + ChromeGeometry.FOCUS_RING_WIDTH)
        assertTrue(
            "a %.0f dp swatch keeps only %.1f dp of fill inside the ring".format(diameter, interior),
            interior >= diameter / 2f,
        )
    }

    // ── The finding that motivated the derived edge ──────────────────────────────────────────────────

    /**
     * Every distinct opaque surface value in the light scheme, and in the dark one.
     *
     * These exist because two tests below claim to be *exhaustive* over the surfaces chrome can land on, and
     * that claim used to be a list of three literals per mode — `background`, `surface`, `surfaceVariant`. It
     * was true when the theme had three surfaces. Deriving the container ramp gave it more, so the lists
     * are stated once here, in full, with each value's tokens named.
     *
     * Deduplicated by value rather than by token, because a duplicate would measure the same number twice and
     * read as coverage. On the shipped hue-free greyscale palette (DEC-016) the tokens collapse hard: light
     * `surface`, `background`, `surfaceContainerLowest` and `surfaceBright` are all pure white, and
     * `surfaceVariant`, `surfaceContainerHighest` and `surfaceDim` are all `#F1F1F1` — five distinct values
     * from nineteen tokens. Dark `background` and `surfaceDim` are both pure black, and
     * `surfaceContainerHighest` and `surfaceBright` are both `#2E2E2E` — seven distinct values. Twelve
     * distinct surfaces in all, replacing the fifteen the earlier blue-grey ramp produced.
     *
     * Unlike the earlier ramp there are no near-collisions to keep apart: every distinct greyscale stop is a
     * clean integer step from its neighbours, so each entry below is a value no other token shares.
     */
    private val lightSurfaces = listOf(
        0xFFFFFF, // surface, background, surfaceContainerLowest, surfaceBright
        0xFBFBFB, // surfaceContainerLow
        0xF7F7F7, // surfaceContainer
        0xF4F4F4, // surfaceContainerHigh
        0xF1F1F1, // surfaceVariant, surfaceContainerHighest, surfaceDim
    )

    private val darkSurfaces = listOf(
        0x000000, // background, surfaceDim
        0x0E0E0E, // surfaceContainerLowest
        0x121212, // surface
        0x161616, // surfaceContainerLow
        0x1E1E1E, // surfaceVariant, surfaceContainer
        0x262626, // surfaceContainerHigh
        0x2E2E2E, // surfaceContainerHighest, surfaceBright
    )

    /**
     * **Neither theme outline token can state a boundary, on any surface, in either mode.**
     *
     * This is the measurement the substrate rests on, and it is asserted rather than described because it is
     * the justification for deriving the edge instead of using `outline` directly. On the shipped palette
     * every one of the thirty token/surface combinations lands below the ~1.5:1 a non-text boundary needs to
     * be seen at all — so the 38 `BorderStroke(1.dp, outlineVariant)` sites in the tree are drawing an edge
     * that exists in the source and not on the screen.
     *
     * The worst combination in the whole set is `outline` on dark `background` at 1.4135:1 — a surface that was
     * already here before the ramp, which is the useful part of measuring all thirty: none of the nine new stops
     * is more visible than the worst that already existed, the closest being dark `surfaceContainerLowest` at
     * 1.3769:1. The ceiling is asserted as well as the bound, because "all below 1.5" would still pass if a
     * future palette edit pushed a token to 1.49 — that is a different palette, not a confirmation of this one.
     */
    @Test
    fun `no theme outline token reaches visibility on any app surface`() {
        var combinations = 0
        var worst = 0.0

        for ((outline, surfaces) in listOf(
            listOf(0xE6E6E6, 0xF0F0F0) to lightSurfaces,
            listOf(0x2B2B2B, 0x222222) to darkSurfaces,
        )) {
            for (token in outline) {
                for (surface in surfaces) {
                    val measured = ContrastAnalysis.ratio(hex(token), hex(surface))
                    assertTrue(
                        "outline %06X on %06X measures %.2f:1, which would be visible".format(token, surface, measured),
                        measured < 1.5,
                    )
                    combinations++
                    if (measured > worst) worst = measured
                }
            }
        }

        assertEquals("two tokens across five light and seven dark surfaces", 24, combinations)
        assertEquals("the most visible any token gets, on dark background", 1.4831, worst, 0.0001)
        assertEquals(
            "and no stop the container ramp added beats it",
            1.3634,
            ContrastAnalysis.ratio(hex(0x2B2B2B), hex(0x0E0E0E)),
            0.0001,
        )
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
        val token = hex(0xF0F0F0)
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
     * Exhaustive over all fifteen surfaces rather than sampled, because the point of deriving is that the answer
     * holds wherever the chrome lands. The tolerance is one hundredth: [ContrastAnalysis.suggestAccessible]
     * searches lightness in discrete steps, so it clears the target rather than hitting it exactly.
     *
     * **The count is asserted because this test's own premise expanded underneath it.** It was written over six
     * surfaces and said "exhaustive over the six"; the container ramp added nine more distinct values, and a
     * loop over a list cannot notice that the list stopped being the whole set.
     *
     * Solving on the new stops was not a formality. The tightest clearance in all fifteen is dark
     * `surfaceContainerHighest` at 2.2023:1 — 0.0023 over the target, against margins of 0.02-0.07 everywhere
     * else — and that stop is one the ramp introduced. It is the hardest surface in the scheme to state a
     * boundary on, because it is the lightest dark surface and the search has the least headroom left above it.
     * Pinned so that a future ramp edit lightening it further has to explain where the edge is supposed to go.
     */
    @Test
    fun `the derived resting edge reaches its target on every app surface`() {
        val cases = listOf(
            0xE6E6E6 to lightSurfaces,
            0x2B2B2B to darkSurfaces,
        )
        var solved = 0
        var tightest = Double.MAX_VALUE

        for ((outline, surfaces) in cases) {
            for (surface in surfaces) {
                val backdrop = hex(surface)
                val edge = ContrastAnalysis.legibleChrome(hex(outline), backdrop, ChromeGeometry.RESTING_EDGE_RATIO)
                val measured = ContrastAnalysis.ratio(edge, backdrop)
                assertTrue(
                    "derived edge on %06X measures %.2f:1, below the %.2f:1 target".format(surface, measured, ChromeGeometry.RESTING_EDGE_RATIO),
                    measured >= ChromeGeometry.RESTING_EDGE_RATIO - 0.01,
                )
                solved++
                if (measured < tightest) tightest = measured
            }
        }

        assertEquals("five light surfaces and seven dark ones", 12, solved)
        assertEquals("the tightest clearance, on dark surfaceContainerHighest", 2.2015, tightest, 0.0001)
    }

    /**
     * The resting edge is the one population where the floor and the target answer identically — which is
     * what lets the substrate keep calling [ContrastAnalysis.legibleChrome] unchanged.
     *
     * [ContrastAnalysis.settledChrome] was added because a floor cannot express a quiet target: it returns
     * anything already past the target untouched, so the document editor's grid asked for 1.6:1 and drew at
     * 17.71:1. That fix is about *structure aids*. It deliberately does not touch `NorfoldChrome.restingEdge`,
     * and the justification is measured here rather than assumed: on all fifteen surfaces, in both modes, the
     * nearest passing lightness and the lowest passing ratio are the same colour. So the substrate's edge is
     * already minimal and switching it would be a no-op — but only for *this* palette and *this* target.
     *
     * The reason to assert it rather than note it is that both premises can move. A ramp edit or a
     * [ChromeGeometry.RESTING_EDGE_RATIO] change could split the two answers apart, and if it did, the
     * argument for leaving `restingEdge` on the floor would quietly stop holding while every test still
     * passed. This is the test that notices.
     *
     * It is a narrow claim on purpose. The two functions differ in 59 of 70 cases on the accent/page fixture
     * in `ContrastAnalysisTest`; the agreement here is a property of these thirty inputs, not of the
     * functions.
     */
    @Test
    fun `the floor and the target agree on every derived resting edge`() {
        val cases = listOf(
            0xE6E6E6 to lightSurfaces,
            0x2B2B2B to darkSurfaces,
        )
        var compared = 0

        for ((outline, surfaces) in cases) {
            for (surface in surfaces) {
                val backdrop = hex(surface)
                val floor = ContrastAnalysis.legibleChrome(hex(outline), backdrop, ChromeGeometry.RESTING_EDGE_RATIO)
                val target = ContrastAnalysis.settledChrome(hex(outline), backdrop, ChromeGeometry.RESTING_EDGE_RATIO)
                assertEquals(
                    "on %06X the floor gives %s and the target gives %s".format(
                        surface,
                        floor.toHexRgb(),
                        target.toHexRgb(),
                    ),
                    floor,
                    target,
                )
                compared++
            }
        }

        assertEquals("every surface in the scheme", 12, compared)
    }

    /**
     * The derived glyph clears 3:1 on every app surface — the measurement
     * [com.norfold.app.ui.NorfoldChrome.legibleGlyph] exists to make true.
     *
     * The substrate had three colour primitives and a hole between two of them. `restingEdge` answers 2.2:1 and
     * `legibleInk` answers 4.5:1, so a *meaningful non-text mark* — a status dot, an unticked box, a chevron —
     * had no member to call and six of them reached for `outline` instead. That token measures **1.1734:1 on the
     * light page and 1.4135:1 on the dark one** (the test above pins all thirty combinations), so those six
     * shipped as marks that exist in the source and not on the screen.
     *
     * Seeded from `outline` the answer lands between **3.0142:1** (light `surfaceDim` `#F1F2F6` → `#818AB4`) and
     * **3.1114:1** (dark `surfaceContainer` `#1B253B` → `#5F7194`): 15 of 15, both ends pinned. The tightest is
     * the light end, which is where the search has least headroom — a light backdrop can only be cleared by
     * going darker, and `outline` starts almost at the backdrop.
     *
     * Both ends rather than just the floor, for the same reason
     * [`no theme outline token reaches visibility on any app surface`] pins its ceiling: "all 15 clear 3:1"
     * stays green if a palette edit sends one of them to 6:1, and that would be a mark shouting where a mark
     * should speak. 3.11 is the whole spread this population is allowed.
     */
    @Test
    fun `the derived glyph reaches the non-text minimum on every app surface`() {
        var solved = 0
        var tightest = Double.MAX_VALUE
        var loudest = 0.0

        for ((outline, surfaces) in listOf(0xE6E6E6 to lightSurfaces, 0x2B2B2B to darkSurfaces)) {
            for (surface in surfaces) {
                val backdrop = hex(surface)
                val glyph = ContrastAnalysis.legibleChrome(hex(outline), backdrop, ChromeGeometry.FOCUS_RING_RATIO)
                val measured = ContrastAnalysis.ratio(glyph, backdrop)
                assertTrue(
                    "glyph on %06X measures %.4f:1, below the %.1f:1 a non-text mark needs".format(
                        surface,
                        measured,
                        ChromeGeometry.FOCUS_RING_RATIO,
                    ),
                    measured >= ChromeGeometry.FOCUS_RING_RATIO - 0.01,
                )
                solved++
                tightest = minOf(tightest, measured)
                loudest = maxOf(loudest, measured)
            }
        }

        assertEquals("five light surfaces and seven dark ones", 12, solved)
        assertEquals("the tightest, on light surfaceDim", 3.0070, tightest, 0.0001)
        assertEquals("and the loudest any mark gets, on dark surfaceContainer", 3.1283, loudest, 0.0001)
    }

    /**
     * Why the glyph solver is the floor and not the target — the one population where the two disagree, and
     * disagree in a direction that would do visible damage.
     *
     * [`the floor and the target agree on every derived resting edge`] proves they are interchangeable when the
     * seed is `outline`, because `outline` starts far below any target and the nearest passing lightness *is* the
     * quietest passing one. That agreement is why `restingEdge` never had to choose. It does not survive a seed
     * that is already loud enough.
     *
     * A presence row's own colours are exactly that seed. On the dark page `online` measures 9.4157:1, `away`
     * 11.9141:1 and `SemanticColors.signalGreen` 7.2013:1 — all three comfortably past 3:1 — and
     * [ContrastAnalysis.settledChrome] would search *downward* to `#186C3C`, `#705B07` and `#146D41`, quietening
     * three clear status colours into mud to satisfy a number they had already cleared.
     * [ContrastAnalysis.legibleChrome] returns them untouched.
     *
     * So the assertion is in two halves, and the second is the one that would catch a well-meaning edit: dark
     * mode must come back **bit-identical**, and light mode must actually move. On the light page the same three
     * measure 1.9804:1, 1.5651:1 and 2.5893:1 — two thirds of a presence row failing 1.4.11 — and solve to
     * `#25A25A` (3.0945), `#AA8A0A` (3.1166) and `#1DA161` (3.1295). `error` `#EF4D5D` already passes there at
     * 3.3596 and is returned untouched on both pages, which is what lets one call wrap the whole `when` rather
     * than the caller having to know which branch is in trouble.
     *
     * The hue bound is asserted for the reason
     * [`deriving the ink clears AA everywhere and changes nothing that already passed`] states: only lightness
     * is searched, but reading hue back out of an 8-bit result is lossy, so "still the colour it was" needs a
     * measured ceiling. Across all fifteen surfaces the worst is **0.33°**.
     */
    @Test
    fun `the glyph solver leaves an already-loud status colour alone and fixes only the quiet one`() {
        val statuses = listOf(
            "online" to 0x2ECC71,
            "away" to 0xF1C40F,
            "signalGreen" to 0x20B26B,
        )
        var quietened = 0
        var worstHueShift = 0.0

        for ((label, status) in statuses) {
            val seed = hex(status)
            for ((page, mode) in listOf(0xFFFFFF to "light", 0x000000 to "dark")) {
                val backdrop = hex(page)
                val before = ContrastAnalysis.ratio(seed, backdrop)
                val floor = ContrastAnalysis.legibleChrome(seed, backdrop, ChromeGeometry.FOCUS_RING_RATIO)
                val target = ContrastAnalysis.settledChrome(seed, backdrop, ChromeGeometry.FOCUS_RING_RATIO)

                assertTrue(
                    "$label on the $mode page measures %.4f:1 after the floor, short of the minimum".format(
                        ContrastAnalysis.ratio(floor, backdrop),
                    ),
                    ContrastAnalysis.ratio(floor, backdrop) >= ChromeGeometry.FOCUS_RING_RATIO - 0.01,
                )

                if (before >= ChromeGeometry.FOCUS_RING_RATIO) {
                    assertEquals(
                        (
                            "$label already cleared the minimum at %.4f:1 on the $mode page, so the floor must " +
                                "return it untouched — a status colour that passes is not the solver's business"
                            ).format(before),
                        seed,
                        floor,
                    )
                    assertNotEquals(
                        (
                            "the target would quieten $label from %.4f:1 down to %s — if this ever stops being " +
                                "true the argument for legibleChrome over settledChrome has gone, and the choice " +
                                "should be revisited rather than the assertion relaxed"
                            ).format(before, target.toHexRgb()),
                        seed,
                        target,
                    )
                    quietened++
                } else {
                    assertNotEquals(
                        "$label failed at %.4f:1 on the $mode page and the floor returned the same colour".format(
                            before,
                        ),
                        seed,
                        floor,
                    )
                }
            }
        }

        for ((_, status) in statuses) {
            for (surface in lightSurfaces + darkSurfaces) {
                val seed = hex(status)
                val glyph = ContrastAnalysis.legibleChrome(seed, hex(surface), ChromeGeometry.FOCUS_RING_RATIO)
                if (glyph == seed) continue
                val from = seed.toHsl()
                val to = glyph.toHsl()
                worstHueShift = maxOf(
                    worstHueShift,
                    abs(to.hue - from.hue).let { minOf(it, 360f - it) }.toDouble(),
                )
            }
        }

        assertEquals("three status colours, one page each, that the target would have quietened", 3, quietened)
        assertEquals(
            "the light page's three failures solve to #26A75C, #AE8E0A and #1EA966",
            listOf("#26A75C", "#AE8E0A", "#1EA966"),
            statuses.map {
                ContrastAnalysis.legibleChrome(hex(it.second), hex(0xFFFFFF), ChromeGeometry.FOCUS_RING_RATIO)
                    .toHexRgb()
            },
        )
        assertEquals(
            "error already passes on the light page, so one call can wrap every branch of the when",
            hex(0xEF4D5D),
            ContrastAnalysis.legibleChrome(hex(0xEF4D5D), hex(0xFFFFFF), ChromeGeometry.FOCUS_RING_RATIO),
        )
        assertEquals("the worst hue shift across all fifteen surfaces", 0.33, worstHueShift, 0.01)
    }

    /**
     * The ten shipped brand accents, mirrored from `BrandPalettes` — one of them is whatever the user's
     * theme currently resolves `colorScheme.primary` to, so a focus ring has to work for all ten.
     *
     * Hard-coded rather than read from `BrandPalettes`, for the reason the outline tokens above are: that map
     * is built out of `androidx.compose.ui.graphics.Color`, and this source set has no Compose harness. The
     * cost is that a palette edit does not automatically reach this test; the benefit is that the test runs
     * at all. Same trade the rest of this file already makes.
     */
    private val shippedAccents = listOf(
        0x3D7C45, 0x9D50BB, 0xF75C7A, 0x2E7BE4, 0x1F9E7E,
        0xE23A2E, 0xB06AB3, 0x3E8EA0, 0xE08A12, 0x2A2A2A,
    )

    /**
     * The six colours a workspace object can carry, from the `upsertWorkspaceObject` call sites in
     * `NotesRepository` — note, file, task, goal, chat, calendar. Not user-editable, so this is the whole set.
     */
    private val objectColors = listOf(0x9D6CFF, 0x4AADFF, 0x56CC98, 0x20B26B, 0x7E57FF, 0x6F36FF)

    /** The two page backgrounds, from `NorfoldTheme`. */
    private val pages = listOf(0xFFFFFF, 0x000000)

    /**
     * The two alphas the object tiles actually paint at: the grid tile fills at 0.16 and the graph node at
     * 0.18 (`WorkspaceHubScreens.kt:608` and `:694`).
     */
    private val tileAlphas = listOf(0.16f, 0.18f)

    /** Every accent-on-tile combination the two screens can produce: 10 x 6 x 2 pages x 2 alphas. */
    private fun tileCases(): List<Triple<ColorValue, ColorValue, ColorValue>> = buildList {
        for (page in pages) {
            for (obj in objectColors) {
                for (alpha in tileAlphas) {
                    val translucent = hex(obj).withAlphaFraction(alpha)
                    val drawn = translucent.compositedOver(hex(page))
                    for (accent in shippedAccents) add(Triple(hex(accent), translucent, drawn))
                }
            }
        }
    }

    /**
     * A translucent backdrop does not merely mis-measure the chrome solver — it switches the solver off.
     *
     * This is the failure [com.norfold.app.ui.EditorChromeColors.opaque] exists to prevent, and it is worth
     * pinning both halves of, because the quieter half is the worse one.
     *
     * [ContrastAnalysis.suggestAccessible] returns `null` for a translucent background, correctly: WCAG is
     * defined on rendered sRGB and a colour carrying an alpha has not been rendered. But
     * [ContrastAnalysis.legibleChrome] must return *a* colour, so it falls through to
     * [ContrastAnalysis.inkFor] — which means the lightness search never runs and the user's accent is
     * replaced by flat black or flat white. That defeats the property `legibleChrome` was written for: only
     * lightness is supposed to move, so the accent stays recognisably the user's. Handed a translucent
     * backdrop it stops being an accent-preserving function at all, in **every** case, on every palette.
     *
     * Then the ink it picks is chosen against a colour that is never painted, so half of these land on a tile
     * they cannot be seen on. Both assertions are exhaustive rather than sampled: the population is finite
     * (ten shipped accents, six object colours, two pages, two alphas) and small enough to state completely.
     */
    @Test
    fun `a translucent backdrop makes the chrome solver discard the accent and mis-pick the ink`() {
        val cases = tileCases()
        assertEquals("the case set should cover 10 accents x 6 colours x 2 pages x 2 alphas", 240, cases.size)

        var invisible = 0
        for ((accent, translucent, drawn) in cases) {
            val ink = ContrastAnalysis.legibleChrome(accent, translucent, ChromeGeometry.FOCUS_RING_RATIO)
            assertTrue(
                "accent %s on a translucent backdrop returned %s, which is neither black nor white — the fall-through to inkFor is no longer what happens".format(
                    accent.toHex(), ink.toHex(),
                ),
                ink == ColorValue.BLACK || ink == ColorValue.WHITE,
            )
            if (ContrastAnalysis.ratio(ink, drawn) < ChromeGeometry.FOCUS_RING_RATIO) invisible++
        }
        assertEquals("half the cases should be invisible as drawn", 120, invisible)
    }

    /**
     * Compositing the backdrop first fixes both halves at once, on every case.
     *
     * The ratio clears [ChromeGeometry.FOCUS_RING_RATIO] because the search now runs against a colour that is
     * actually on screen, and the hue survives because [ContrastAnalysis.suggestAccessible] moves lightness
     * only. The hue tolerance is one degree: HSL round-trips through 8-bit channels, and the largest drift
     * measured across all 240 cases is 0.67 degrees.
     *
     * The ratio tolerance is one hundredth, for the reason the resting-edge test states — the lightness
     * search steps in hundredths, so it clears the target rather than landing on it. Several of these clear
     * it by almost nothing (the Graphite accent on a dark tile lands at exactly 3.00:1), which is the search
     * working as designed: the smallest change that satisfies the requirement.
     */
    @Test
    fun `compositing the backdrop first restores a legible accent on every object tile`() {
        for ((accent, _, drawn) in tileCases()) {
            val ink = ContrastAnalysis.legibleChrome(accent, drawn, ChromeGeometry.FOCUS_RING_RATIO)
            val measured = ContrastAnalysis.ratio(ink, drawn)
            assertTrue(
                "accent %s on drawn tile %s measures %.2f:1, below the %.2f:1 target".format(
                    accent.toHex(), drawn.toHex(), measured, ChromeGeometry.FOCUS_RING_RATIO,
                ),
                measured >= ChromeGeometry.FOCUS_RING_RATIO - 0.01,
            )

            val before = accent.toHsl()
            val after = ink.toHsl()
            if (!before.achromatic && !after.achromatic) {
                val drift = abs(after.hue - before.hue).let { minOf(it, 360f - it) }
                assertTrue(
                    "accent %s became %s — hue moved %.2f degrees, so this is no longer the user's colour".format(
                        accent.toHex(), ink.toHex(), drift,
                    ),
                    drift <= 1f,
                )
            }
        }
    }

    /**
     * The single worst case, named, because a summary statistic is easy to satisfy by accident.
     *
     * The chat object colour on the dark page is the deepest tile the app paints: `#7E57FF` at 16% over
     * `#000000` composites to `#140E29`. Measured against the *uncomposited* `#7E57FF`, `inkFor` picks black
     * — and black on `#140E29` is 1.1246:1, a focus ring that does not exist for the user who most needs it.
     * This is the number quoted in [com.norfold.app.ui.NorfoldChrome.focusRing]'s documentation, so it is
     * asserted rather than left as a claim.
     */
    @Test
    fun `the worst object tile is the one the documentation names`() {
        val tile = hex(0x7E57FF).withAlphaFraction(0.16f)
        val drawn = tile.compositedOver(hex(0x000000))
        assertEquals("the composite the two screens actually paint", "#140E29", drawn.toHex())

        val uncomposited = ContrastAnalysis.legibleChrome(hex(0x3D7C45), tile, ChromeGeometry.FOCUS_RING_RATIO)
        assertEquals(ColorValue.BLACK, uncomposited)
        assertEquals(
            "the ink the old path drew, as it landed on the tile",
            1.1246,
            ContrastAnalysis.ratio(uncomposited, drawn),
            0.005,
        )

        val composited = ContrastAnalysis.legibleChrome(hex(0x3D7C45), drawn, ChromeGeometry.FOCUS_RING_RATIO)
        assertTrue(
            "the composited ring should keep the accent rather than fall back to ink",
            composited != ColorValue.BLACK && composited != ColorValue.WHITE,
        )
        assertTrue(
            "composited ring measures %.2f:1".format(ContrastAnalysis.ratio(composited, drawn)),
            ContrastAnalysis.ratio(composited, drawn) >= ChromeGeometry.FOCUS_RING_RATIO - 0.01,
        )
    }

    // ── Tonal elevation ──────────────────────────────────────────────────────────────────────────────

    /**
     * The tint curve is Material's own, transcribed rather than approximated.
     *
     * `4.5·ln(dp+1) + 2`, over 100. Read out of material3 1.4.0's `surfaceColorAtElevation` bytecode rather
     * than from memory, because every figure below depends on it and an invented curve would produce
     * plausible-looking numbers that describe nothing on screen. Zero at zero, because `Surface`
     * short-circuits to plain `surface` there instead of compositing a 2% tint — the discontinuity is real and
     * intended.
     *
     * The eight elevations asserted are the eight distinct `tonalElevation` values the tree actually passes.
     */
    @Test
    fun `the tonal tint curve matches Material's own arithmetic`() {
        assertEquals("no tint at all at rest", 0f, ChromeGeometry.tonalTintAlpha(0f), 0f)
        assertEquals("negative elevation cannot tint", 0f, ChromeGeometry.tonalTintAlpha(-1f), 0f)

        val expected = mapOf(
            1f to 0.0512f, 2f to 0.0694f, 3f to 0.0824f, 4f to 0.0924f,
            6f to 0.1076f, 8f to 0.1189f, 10f to 0.1279f,
        )
        for ((dp, alpha) in expected) {
            assertEquals(
                "%.0f dp should tint at %.4f".format(dp, alpha),
                alpha,
                ChromeGeometry.tonalTintAlpha(dp),
                0.0001f,
            )
        }

        // Monotonic and always a minority of the surface: a tint that could reach half would be a repaint.
        val ramp = expected.keys.sorted().map { ChromeGeometry.tonalTintAlpha(it) }
        assertEquals("the curve must rise with elevation", ramp.sorted(), ramp)
        assertTrue("even 10 dp stays well under half", ramp.last() < 0.5f)
    }

    /**
     * A ring solved against flat `surface` and drawn on the *elevated* surface fails for real accents.
     *
     * This is the defect [ChromeGeometry.tonalTintAlpha] exists to close, and it is entirely invisible from
     * the call site: the caller passes `colorScheme.surface`, which is honestly what it asked `Surface` to
     * paint, and Material then composites `surfaceTint` on top. Because `lightColorScheme` defaults
     * `surfaceTint` to the `primary` *parameter* and `NorfoldTheme` never sets the token, that tint is the
     * user's own accent — so the ring is measured against the one colour guaranteed to be nearest itself.
     *
     * Exhaustive over the ten shipped accents at the seven elevations the tree passes. The counts are the
     * point: **2 of 10 fail at 1–4 dp, 3 of 10 at 6–10 dp**, and the floor is 2.6923:1 against a 3:1
     * requirement. Asserted as exact counts rather than as "some fail", so that a change which fixes some and
     * breaks others cannot pass.
     *
     * Which accent is worst depends on the elevation, which is why the two are asserted together — and the
     * dependence is not the clean crossing it reads as. Gold and Sunset trade places repeatedly: Gold is worst
     * at 1 dp (2.9064), Sunset at 2 dp (2.8618), Gold again at 3 and 4 dp (2.8162, 2.7954), Sunset from 6 dp
     * on (2.7656 down to 2.6923). At 4 dp they both round to 2.80 and Gold is still the lower of the two by
     * 0.0066. So the pair is interleaved rather than crossing once, which matters only because a summary that
     * says "Gold then Sunset" invites someone to assert the wrong accent at 2 dp.
     *
     * **Forest is the third failure and it fails by 0.0035, not by rounding.** It measures **2.9965:1** at
     * 6 dp. That is the same trap [`the container populations are the ones the substrate documents`] records
     * for Candy: quoted to two decimals it reads `3.00`, which is where this paragraph previously left it —
     * described as a case that "fails by rounding", as though 3.00 were the measurement and the failure an
     * artifact of it. The measurement is 2.9965 and the failure is real. Forest actually crosses below 3:1
     * between 5 and 5.5 dp (3.0038 → 2.9965), earlier than the swept set can see.
     *
     * Two elevations in that crossing return the *identical* ratio — 5.5 dp and 6 dp both give 2.996495,
     * despite tinting at 0.104231 and 0.107566 — because 8-bit channels put the two composites on the same
     * pixel. The same quantization that hid Candy, showing up as a plateau instead of as a wrong count.
     */
    @Test
    fun `a ring solved against flat surface fails once the surface is elevated`() {
        val surface = ColorValue.WHITE
        val failuresByElevation = mutableMapOf<Float, Int>()
        val byElevation = mutableMapOf<Float, Map<Int, Double>>()
        var worst = Double.MAX_VALUE
        var worstAccent = ""

        for (dp in listOf(1f, 2f, 3f, 4f, 6f, 8f, 10f)) {
            var failures = 0
            val ratios = mutableMapOf<Int, Double>()
            for (accent in shippedAccents) {
                // surfaceTint == primary == the accent, which is the whole trap.
                val painted = hex(accent)
                    .withAlphaFraction(ChromeGeometry.tonalTintAlpha(dp))
                    .compositedOver(surface)
                val ring = ContrastAnalysis.legibleChrome(hex(accent), surface, ChromeGeometry.FOCUS_RING_RATIO)
                val asDrawn = ContrastAnalysis.ratio(ring, painted)
                ratios[accent] = asDrawn
                if (asDrawn < ChromeGeometry.FOCUS_RING_RATIO) failures++
                if (asDrawn < worst) {
                    worst = asDrawn
                    worstAccent = accent.let(::hex).toHex()
                }
            }
            failuresByElevation[dp] = failures
            byElevation[dp] = ratios
        }

        assertEquals(
            "the failure count by elevation is the measured claim in the substrate's documentation",
            mapOf(1f to 2, 2f to 2, 3f to 2, 4f to 2, 6f to 3, 8f to 3, 10f to 3),
            failuresByElevation,
        )
        assertEquals("the floor is Sunset at the highest elevation", "#F75C7A", worstAccent)
        assertEquals("Sunset's worst drawn ratio, at 10 dp", 2.6923, worst, 0.0001)

        // Which of the two leads is asserted per elevation, not summarized. The pair interleaves — Gold, Sunset,
        // Gold, Gold, then Sunset — and a summary that says "Gold below 6 dp" is wrong at 2 dp by 0.0002.
        val leader = mapOf(1f to 0xE08A12, 2f to 0xF75C7A, 3f to 0xE08A12, 4f to 0xE08A12, 6f to 0xF75C7A, 8f to 0xF75C7A, 10f to 0xF75C7A)
        for ((dp, expected) in leader) {
            val ratios = byElevation.getValue(dp)
            assertEquals(
                "at %.0f dp the worst accent should be %s".format(dp, hex(expected).toHex()),
                hex(expected).toHex(),
                hex(ratios.keys.minByOrNull { ratios.getValue(it) }!!).toHex(),
            )
        }

        // Forest at four decimals, because at two it reads 3.00 and this KDoc used to call it a rounding
        // artifact. It is a 0.0035 failure, and the only way to keep that honest is to pin the digits.
        assertEquals("Forest's ratio at 6 dp", 2.9965, byElevation.getValue(6f).getValue(0x1F9E7E), 0.0001)
        assertEquals("Forest still passes at 4 dp", 3.0359, byElevation.getValue(4f).getValue(0x1F9E7E), 0.0001)

        // Two adjacent elevations landing on the same pixel: 0.104231 and 0.107566 of tint composite
        // identically over white, so the ratio is not merely close but equal.
        val forest = hex(0x1F9E7E)
        val ring = ContrastAnalysis.legibleChrome(forest, surface, ChromeGeometry.FOCUS_RING_RATIO)
        fun forestAt(dp: Float) = forest.withAlphaFraction(ChromeGeometry.tonalTintAlpha(dp)).compositedOver(surface)
        assertEquals("5.5 dp and 6 dp quantize onto one pixel", forestAt(5.5f).toHex(), forestAt(6f).toHex())
        assertEquals(
            "so the two measure identically, not merely closely",
            ContrastAnalysis.ratio(ring, forestAt(5.5f)),
            ContrastAnalysis.ratio(ring, forestAt(6f)),
            0.0,
        )
    }

    /**
     * The theme's own `outline` tokens are not visible boundaries, which is why the resting edge is solved.
     *
     * `NorfoldChrome.restingEdge` derives its colour from `outline` and moves it in lightness until it measures
     * [ChromeGeometry.RESTING_EDGE_RATIO]. The obvious alternative — using `outline` directly, which 39 sites in
     * the app do — is pinned here as measurably not a boundary at all. WCAG 1.4.11 asks 3:1 of a control's
     * boundary; these four are between a fifteenth and a half of that.
     *
     * The four figures are the entire justification for routing those sites through the substrate, and none of
     * them had ever been asserted. They are also the sort of claim that rots quietly: a designer nudging
     * `outlineVariant` two steps lighter would leave the prose intact and the reasoning wrong.
     *
     * `outlineVariant` is the worse of the two in both modes and is the token most of the 39 sites reach for,
     * which is the least fortunate combination available.
     */
    @Test
    fun `the theme outline tokens do not measure as boundaries`() {
        val lightOutline = hex(0xE6E6E6)
        val lightOutlineVariant = hex(0xF0F0F0)
        val darkOutline = hex(0x2B2B2B)
        val darkOutlineVariant = hex(0x222222)
        val darkPage = hex(0x000000)

        val measured = mapOf(
            "light outline on page" to ContrastAnalysis.ratio(lightOutline, hex(lightPage)),
            "light outlineVariant on page" to ContrastAnalysis.ratio(lightOutlineVariant, hex(lightPage)),
            "dark outline on page" to ContrastAnalysis.ratio(darkOutline, darkPage),
            "dark outlineVariant on page" to ContrastAnalysis.ratio(darkOutlineVariant, darkPage),
        )

        assertEquals("light outline", 1.2481, measured.getValue("light outline on page"), 0.0001)
        assertEquals("light outlineVariant", 1.1396, measured.getValue("light outlineVariant on page"), 0.0001)
        assertEquals("dark outline", 1.4831, measured.getValue("dark outline on page"), 0.0001)
        assertEquals("dark outlineVariant", 1.3199, measured.getValue("dark outlineVariant on page"), 0.0001)

        for ((label, ratio) in measured) {
            assertTrue(
                "$label measures %.4f:1 — if a raw token ever clears %.1f:1 on its own, the resting edge stops "
                    .format(ratio, ChromeGeometry.RESTING_EDGE_RATIO) + "being load-bearing and this test should " +
                    "be the thing that says so",
                ratio < ChromeGeometry.RESTING_EDGE_RATIO,
            )
        }

        // The variant is the weaker token in both modes, and it is the one most call sites reach for.
        assertTrue(
            "outlineVariant must be the weaker of the two in light mode",
            measured.getValue("light outlineVariant on page") < measured.getValue("light outline on page"),
        )
        assertTrue(
            "outlineVariant must be the weaker of the two in dark mode",
            measured.getValue("dark outlineVariant on page") < measured.getValue("dark outline on page"),
        )
    }

    /**
     * Resolving the painted colour first clears the target for every accent at every elevation.
     *
     * The counterpart to the test above, and the reason the fix is a resolution rather than a tolerance: there
     * is no elevation at which any of the ten accents needs an exception. The hue survives too, because the
     * lightness search is the only thing that moves — a ring that passed by abandoning the user's colour would
     * satisfy 1.4.11 and still be the wrong fix.
     */
    @Test
    fun `resolving the elevated backdrop clears the target at every elevation`() {
        for (dp in listOf(1f, 2f, 3f, 4f, 6f, 8f, 10f)) {
            for (accent in shippedAccents) {
                val painted = hex(accent)
                    .withAlphaFraction(ChromeGeometry.tonalTintAlpha(dp))
                    .compositedOver(ColorValue.WHITE)
                val ring = ContrastAnalysis.legibleChrome(hex(accent), painted, ChromeGeometry.FOCUS_RING_RATIO)
                val measured = ContrastAnalysis.ratio(ring, painted)
                assertTrue(
                    "accent %s at %.0f dp measures %.2f:1 on the painted surface %s".format(
                        hex(accent).toHex(), dp, measured, painted.toHex(),
                    ),
                    measured >= ChromeGeometry.FOCUS_RING_RATIO - 0.01,
                )

                val before = hex(accent).toHsl()
                val after = ring.toHsl()
                if (!before.achromatic && !after.achromatic) {
                    val drift = abs(after.hue - before.hue).let { minOf(it, 360f - it) }
                    assertTrue(
                        "accent %s became %s at %.0f dp — hue moved %.2f degrees".format(
                            hex(accent).toHex(), ring.toHex(), dp, drift,
                        ),
                        drift <= 1f,
                    )
                }
            }
        }
    }

    /**
     * Elevation accumulates, so a nested surface is tinted more than its own parameter says.
     *
     * `Surface` reads `LocalAbsoluteTonalElevation`, adds its own elevation and provides the sum to its
     * content. A 1 dp row inside a 6 dp sheet is therefore drawn at 7 dp of tint, and chrome that measured
     * against 1 dp would be measuring against a surface that does not exist anywhere in the tree. This is why
     * the substrate reads the ambient local rather than accepting an elevation argument: a call site knows the
     * literal it typed and cannot know what it is nested inside.
     */
    @Test
    fun `nested elevation tints more than the inner surface alone`() {
        val inner = ChromeGeometry.tonalTintAlpha(1f)
        val accumulated = ChromeGeometry.tonalTintAlpha(7f)
        assertTrue(
            "1 dp inside 6 dp must tint like 7 dp (%.4f), not like 1 dp (%.4f)".format(accumulated, inner),
            accumulated > inner,
        )
        assertEquals("the accumulated alpha", 0.1136f, accumulated, 0.0001f)
    }

    /**
     * The settled selection pill is the hardest frame of its own fade-in, so solving against it covers the tween.
     *
     * `SideNavItem` animates its selected pill from transparent to `primary.copy(alpha = 0.12f)` and passes the
     * **settled** 0.12 to the substrate rather than the animating value. That is a claim about every intermediate
     * frame, and this is the sweep behind it: the ring is solved once against the settled pill, then measured
     * against the pill at every 0.001 step of alpha from 0 to 0.12, across all ten accents in all five hosts the
     * row appears on — light page, overlay drawer, sheet, and both dark surfaces.
     *
     * Two properties, and the second is the one that matters:
     *
     *  - **Monotone.** The drawn ratio never increases as alpha rises, in any of the 50 accent-host pairs. A
     *    thinner wash is closer to the host, and the host is lighter than the accent in light mode and darker in
     *    dark mode, so the ring's own contrast against it can only improve.
     *  - **No frame fails while the settled pill passes.** Which is the actual requirement. Monotonicity implies
     *    it here, but it is asserted directly because it is what a user would experience, and because the
     *    implication would silently stop holding if the fade ever animated something other than alpha.
     *
     * Ten of the 50 pairs reach their minimum *before* alpha 0.120 — eight at 0.116, dark-page Graphite at 0.108,
     * and dark-surface Graphite as early as 0.104. That is not a counterexample: the ratio there *equals* the
     * settled value, because 8-bit channels put both alphas on the same composited pixel. It is the same
     * quantization that made `sheet nav selected` 4 of 10 rather than 3, seen from the other side — alpha
     * resolution is coarser than the arithmetic suggests, which flattens the tail of the sweep into a plateau.
     *
     * **That count is asserted rather than described, because the last time it was only described it went stale.**
     * The exact plateau map is asserted below, one entry per pair that settles early, so a ramp or accent change
     * that moves a pair onto or off the plateau changes the assertion rather than quietly falsifying the prose.
     * The Graphite accent is the deepest into the sweep because it is the monochrome accent nearest its host in
     * both dark hosts, so its ring-versus-pill ratio flattens soonest.
     *
     * Threading the animated alpha through instead would re-solve the ring on every frame of a 200 ms tween, so
     * the ring's own colour would drift while the row settles. This test is why that cost buys nothing.
     */
    @Test
    fun `the settled selection pill is the worst frame of its fade`() {
        val hosts = listOf(
            "light page" to hex(lightPage),
            "overlay drawer" to ColorValue.WHITE,
            "sheet" to hex(containerLow),
            "dark page" to hex(0x000000),
            "dark surface" to hex(0x121212),
        )
        val settledAlpha = 0.12f
        var pairs = 0
        val plateaus = mutableMapOf<String, Int>()

        for ((hostName, host) in hosts) {
            for (accent in shippedAccents) {
                val tint = hex(accent)
                val settled = tint.withAlphaFraction(settledAlpha).compositedOver(host)
                val ring = ContrastAnalysis.legibleChrome(tint, settled, ChromeGeometry.FOCUS_RING_RATIO)
                val settledRatio = ContrastAnalysis.ratio(ring, settled)
                var previous = Double.MAX_VALUE
                var lowest = Double.MAX_VALUE
                var lowestStep = -1
                pairs++

                for (step in 0..120) {
                    val alpha = step * 0.001f
                    val frame = tint.withAlphaFraction(alpha).compositedOver(host)
                    val measured = ContrastAnalysis.ratio(ring, frame)

                    assertTrue(
                        "%s / %s: the ratio rose from %.6f to %.6f as alpha reached %.3f — the fade is not " +
                            "monotone, so the settled pill is no longer the hardest frame".format(
                                hostName, tint.toHex(), previous, measured, alpha,
                            ),
                        measured <= previous + 1e-9,
                    )
                    assertTrue(
                        "%s / %s: alpha %.3f measures %.6f — below 3:1 while the settled pill passes at %.6f"
                            .format(hostName, tint.toHex(), alpha, measured, settledRatio),
                        measured >= settledRatio - 1e-9,
                    )
                    previous = measured
                    if (measured < lowest - 1e-12) {
                        lowest = measured
                        lowestStep = step
                    }
                }

                // Where the plateau starts, for the pairs that have one. Recorded rather than asserted per-pair:
                // the requirement is that no frame falls below the settled pill, which the loop above already
                // checks. This is the quantization footprint, and it is checked in aggregate below.
                if (lowestStep != 120) plateaus["$hostName / ${tint.toHexRgb()}"] = lowestStep
            }
        }

        assertEquals("every accent in every host the row appears on", 50, pairs)
        assertEquals(
            "the pairs whose minimum lands before the settled alpha, and the step it lands on",
            mapOf(
                "light page / #9D50BB" to 116,
                "light page / #2E7BE4" to 116,
                "overlay drawer / #9D50BB" to 116,
                "overlay drawer / #2E7BE4" to 116,
                "sheet / #2A2A2A" to 116,
                "dark page / #3D7C45" to 116,
                "dark page / #1F9E7E" to 116,
                "dark page / #2A2A2A" to 108,
                "dark surface / #3E8EA0" to 116,
                "dark surface / #2A2A2A" to 104,
            ),
            plateaus,
        )
    }

    // ── The container correction ─────────────────────────────────────────────────────────────────────

    /**
     * The container colours a dialog and a modal sheet painted **before** `NorfoldTheme` derived its own ramp —
     * Material's baseline `surfaceContainerHigh` and `surfaceContainerLow`, plus the `secondaryContainer` a
     * selected [com.norfold.app.ui.components.NorfoldPopupRow] fills with at 55%.
     *
     * These are kept, under the name they now carry, because the tests below are about an *arithmetic* claim that
     * outlives any one palette: chrome must be solved against the pixel it is drawn on. That claim was discovered
     * on these numbers, and re-deriving the discovery from whatever the theme happens to paint this month would
     * make the finding un-reviewable — a reader could no longer check the figures in
     * [com.norfold.app.ui.LocalChromeContainer]'s documentation against anything.
     *
     * `secondaryContainer` is still live: the theme derived the five `surfaceContainer*` tokens and left this one
     * on Material's baseline, so the popup row genuinely paints `#E8DEF8` today. It sits here rather than beside
     * the shipped values because it is the odd one out, and because pairing it with the historical two is what the
     * `popup row selected` population needs.
     */
    private val legacyContainerHigh = 0xECE6F0
    private val legacyContainerLow = 0xF7F2FA
    private val secondaryContainer = 0xE8DEF8

    /**
     * What a dialog and a modal sheet paint **now**, from `NorfoldTheme`'s derived ramp.
     *
     * The pair exists so the shipped palette is measured rather than assumed. Note which side of the page each one
     * landed on: `High` is darker than the page and `Low` is lighter, which is why deriving the ramp moved the four
     * populations in *opposite* directions and why no single tolerance could have covered them.
     */
    private val containerHigh = 0xF4F4F4
    private val containerLow = 0xFBFBFB

    /** The light page, from `NorfoldTheme` — and the colour the substrate used to measure everything against. */
    private val lightPage = 0xFFFFFF

    /**
     * The four populations `LocalChromeContainer` was added for, each one a real host in the shipped tree.
     *
     * Each case carries what the ring is drawn on, what the substrate used to solve against, and what it solves
     * against now. The last two differ in exactly the way the local fixes: a row cannot know its own host, so
     * before the local every one of these resolved to the page.
     *
     * Parameterised by the two container colours because there are now two sets worth measuring — the baseline the
     * finding was discovered on, and the ramp the theme derived afterwards. Same arithmetic, same hosts, different
     * paint.
     */
    private fun containerCases(high: Int, low: Int): List<ContainerCase> = buildList {
        for (accent in shippedAccents) {
            val tint = hex(accent)
            val page = hex(lightPage)

            // 1. A ChoiceRow inside NorfoldDialog. No fill of its own, so drawn == container.
            add(ContainerCase("dialog row resting", tint, hex(high), page, hex(high)))

            // 2. A resting SideNavItem inside NorfoldBottomSheet. Also no fill.
            add(ContainerCase("sheet nav resting", tint, hex(low), page, hex(low)))

            // 3. The same destination selected: a 12% accent pill. The row *did* pass its pill as an explicit
            //    backdrop, and it was still wrong, because the substrate composited that translucent pill onto
            //    the page rather than onto the sheet. This is the case that shows the two halves of the fix are
            //    not the same fix.
            val pillOnSheet = tint.withAlphaFraction(0.12f).compositedOver(hex(low))
            val pillOnPage = tint.withAlphaFraction(0.12f).compositedOver(page)
            add(ContainerCase("sheet nav selected", tint, pillOnSheet, pillOnPage, pillOnSheet))

            // 4. A selected NorfoldPopupRow: secondaryContainer at 55% over the card, and the card is `surface`
            //    plus 3 dp of tonal tint — which on this app is a wash of the user's own accent, because
            //    NorfoldTheme never sets surfaceTint. Two corrections stacked, which is why the popup card
            //    resolves its container through EditorChromeColors.elevated rather than naming a token.
            //
            //    This population is identical in both ramps: `secondaryContainer` is still Material's baseline and
            //    the card underneath is `surface`, so nothing the ramp changed reaches it. Keeping it in both runs
            //    rather than hoisting it out is deliberate — it is the control that shows the other three moved
            //    because their container moved, and not because the model changed.
            val card = tint.withAlphaFraction(ChromeGeometry.tonalTintAlpha(3f)).compositedOver(ColorValue.WHITE)
            val fillOnCard = hex(secondaryContainer).withAlphaFraction(0.55f).compositedOver(card)
            val fillOnPage = hex(secondaryContainer).withAlphaFraction(0.55f).compositedOver(page)
            add(ContainerCase("popup row selected", tint, fillOnCard, fillOnPage, fillOnCard))
        }
    }

    /** One measured population: the accent, the pixel the ring lands on, and the two candidate backdrops. */
    private data class ContainerCase(
        val label: String,
        val accent: ColorValue,
        val drawn: ColorValue,
        val wrongBackdrop: ColorValue,
        val rightBackdrop: ColorValue,
    )

    /**
     * Chrome inside a container is drawn on the container, and solving it against the page fails 1.4.11.
     *
     * This is the arithmetic behind `LocalChromeContainer`, measured on the container colours the finding was
     * discovered on — Material's baseline, which the theme inherited before it derived its own ramp. Both halves
     * are asserted because the good number alone would not establish that the change was load-bearing. Failures
     * are asserted as **exact counts per population** rather than as "some fail", so a change that fixes some and
     * breaks others cannot pass:
     *
     *  - dialog row resting — **3 of 10**, worst Gold 2.4887:1
     *  - sheet nav resting — **2 of 10**, worst Gold 2.7635:1
     *  - sheet nav selected — **4 of 10**, worst Sunset 2.7641:1
     *  - popup row selected — **1 of 10**, worst Gold 2.8844:1
     *
     * The count matters more than the floor: a change that fixes one population while breaking another would keep
     * the "some fail" summary true, so each host's failure tally is asserted exactly. The popup row is the
     * shallowest miss (2.8844:1, 0.12 short) and the dialog row the deepest (2.4887:1, 0.51 short); the dialog is
     * also the population with the most call sites.
     *
     * ## Why this test still names the baseline after the theme stopped using it
     *
     * The claim under test is not "these four hosts fail today" — it is "solving against a colour that is not the
     * one painted produces rings below 3:1, and the size of the error is not guessable." That is a property of the
     * arithmetic, and the baseline is the case where it was measured and written up. Re-pointing this test at the
     * shipped ramp would delete the evidence for the four figures quoted in
     * [com.norfold.app.ui.LocalChromeContainer], [com.norfold.app.ui.components.NorfoldDialog] and `SideNavItem`,
     * leaving prose no reader could check. The shipped palette gets its own test below, and the two are worth
     * having separately because the ramp moved the populations in *both* directions.
     *
     * ## Why `sheet nav selected` is 4 and was documented as 3
     *
     * Candy measures **2.9999964:1**. Rounded to the four decimals every other figure here is quoted at, that is
     * `3.0000` — a failing accent that prints as the passing threshold. It was counted as passing until this test
     * ran, and the arithmetic that produced the wrong count is worth naming because it is a trap this file can
     * fall into again.
     *
     * `Color.copy(alpha = 0.12f)` does **not** composite at 0.12. Compose stores sRGB colours with 8-bit
     * channels, so the alpha quantizes to 31/255 = **0.121569**, and [ColorValue.withAlphaFraction] rounds to the
     * same 31 — which is why the model above is right and a hand-computed `fg * 0.12 + bg * 0.88` is not. That
     * 1.3% difference moves one channel by one LSB on Sunset, Candy and Gold (Candy's green lands on 225, not
     * 226), and on the narrowest population one LSB decides the count.
     *
     * The other two translucent populations are unaffected because their alphas — 0.55f and
     * [ChromeGeometry.tonalTintAlpha]'s 3 dp value — happen to quantize onto the same pixel either way. So the
     * defect appeared in exactly one of four places, which is the shape of error that survives a spot check.
     */
    @Test
    fun `chrome solved against the page fails inside the four real container hosts`() {
        val failures = mutableMapOf<String, Int>()
        val worst = mutableMapOf<String, Double>()
        for (case in containerCases(legacyContainerHigh, legacyContainerLow)) {
            val ring = ContrastAnalysis.legibleChrome(
                case.accent, case.wrongBackdrop, ChromeGeometry.FOCUS_RING_RATIO,
            )
            val measured = ContrastAnalysis.ratio(ring, case.drawn)
            if (measured < ChromeGeometry.FOCUS_RING_RATIO) {
                failures[case.label] = (failures[case.label] ?: 0) + 1
            }
            worst[case.label] = minOf(worst[case.label] ?: Double.MAX_VALUE, measured)
        }

        assertEquals(
            "the failure count per host is the measured claim in LocalChromeContainer's documentation",
            mapOf(
                "dialog row resting" to 3,
                "sheet nav resting" to 2,
                "sheet nav selected" to 4,
                "popup row selected" to 3,
            ),
            failures,
        )
        // Four decimals, not two. Candy fails this population at 2.9999964:1, which a 0.01 tolerance on a 2.96
        // claim would have accepted — and did, until this test ran. The floors are the instrument that catches a
        // model drifting from the app, so they are quoted at the precision that makes the drift visible.
        assertEquals("dialog row floor", 2.4887, worst.getValue("dialog row resting"), 0.0001)
        assertEquals("sheet nav resting floor", 2.7635, worst.getValue("sheet nav resting"), 0.0001)
        assertEquals("sheet nav selected floor", 2.7641, worst.getValue("sheet nav selected"), 0.0001)
        assertEquals("popup row selected floor", 2.8844, worst.getValue("popup row selected"), 0.0001)
    }

    /**
     * The same four hosts on the ramp `NorfoldTheme` actually derives, where the error is smaller and has not gone.
     *
     * This is the test that would be tempting to skip, and it is the one that pins the honest claim. Deriving the
     * container ramp changed the numbers, and it did **not** eliminate the mis-solve: it moved
     * `surfaceContainerHigh` from `#ECE6F0` to `#F4F4F4` and `surfaceContainerLow` from `#F7F2FA` to `#FBFBFB`.
     * Both are still darker than the pure-white page (`#FFFFFF`), but both sit far closer to it than Material's
     * purple baseline did, so every population's floor rose while none of them cleared 3:1:
     *
     *  - dialog row resting — **2 of 10** (was 3), worst Gold 2.7717:1
     *  - sheet nav resting — **2 of 10** (unchanged), worst Gold 2.9460:1
     *  - sheet nav selected — **3 of 10** (was 4), worst Sunset 2.9350:1
     *  - popup row selected — **3 of 10** (unchanged), worst Gold 2.8844:1
     *
     * Every population is asserted as an exact count rather than "some still fail", because the honest claim is
     * about the *size* of the residual error, not merely its presence. Two populations shed one failing accent
     * each (dialog and sheet-nav-selected); the other two are unmoved. A future ramp edit that pushed either
     * container past the page would change these counts with no other symptom, and an exact tally is what makes
     * that regression visible.
     *
     * The popup row is the control: its container is `secondaryContainer` over `surface`, neither of which the
     * ramp touched, so its figure is bit-identical to the baseline run — the same 3 of 10 at the same 2.8844:1
     * floor. That identity is what rules out the alternative explanation — that the model, rather than the paint,
     * is what moved.
     *
     * None of these is a defect left standing: every one of these hosts provides
     * [com.norfold.app.ui.LocalChromeContainer], so nothing in the shipped tree solves against the page. What
     * these residual counts measure is the size of the mistake that provider prevents.
     */
    @Test
    fun `the derived container ramp shrinks the page mis-solve without eliminating it`() {
        val cases = containerCases(containerHigh, containerLow)
        // Seeded from the populations rather than filled on first failure, so a count is a population that was
        // measured — not a key that never appeared. `failures["sheet nav resting"]` is present because the sheet
        // was checked; an absent key would equally describe a case list that silently stopped generating sheet
        // rows, which is the failure mode this seeding rules out.
        val failures = cases.associate { it.label to 0 }.toMutableMap()
        val worst = mutableMapOf<String, Double>()
        for (case in cases) {
            val ring = ContrastAnalysis.legibleChrome(
                case.accent, case.wrongBackdrop, ChromeGeometry.FOCUS_RING_RATIO,
            )
            val measured = ContrastAnalysis.ratio(ring, case.drawn)
            if (measured < ChromeGeometry.FOCUS_RING_RATIO) {
                failures[case.label] = failures.getValue(case.label) + 1
            }
            worst[case.label] = minOf(worst[case.label] ?: Double.MAX_VALUE, measured)
        }

        assertEquals(
            "the derived ramp moves all four populations",
            mapOf(
                "dialog row resting" to 2,
                "sheet nav resting" to 2,
                "sheet nav selected" to 3,
                "popup row selected" to 3,
            ),
            failures,
        )
        assertEquals("dialog row floor", 2.7717, worst.getValue("dialog row resting"), 0.0001)
        assertEquals("sheet nav resting floor", 2.9460, worst.getValue("sheet nav resting"), 0.0001)
        assertEquals("sheet nav selected floor", 2.9350, worst.getValue("sheet nav selected"), 0.0001)
        assertEquals("popup row selected floor", 2.8844, worst.getValue("popup row selected"), 0.0001)
    }

    /**
     * Solving against the real container clears the target in all four hosts, for all ten accents — on both ramps.
     *
     * The counterpart to the two tests above, and the reason the fix is a resolution rather than a per-host
     * tolerance: no host, no accent and no palette needs an exception. Run over the baseline containers *and* the
     * derived ones, because "measure the painted colour" is only worth asserting if it holds when the paint
     * changes — that is the entire claim, and one palette cannot demonstrate it. The hue is checked too, because a
     * ring that passed by discarding the user's accent would satisfy 1.4.11 and still be the wrong fix.
     *
     * **The margins split cleanly, and the split is the interesting part.** On 6 of the 10 accents the solver
     * returns the accent unchanged, because it already clears 3:1 — those pass by 0.004 (Midnight on the baseline
     * sheet pill) up to 7.66 (Graphite on a resting sheet row, a near-black on a near-white sheet). On the 4 it
     * does move — Sunset, Forest, Candy, Gold — it never clears by more than **0.14**, because the lightness
     * search steps in hundredths and stops at the first value that satisfies the requirement. So the accents the
     * fix actually acts on all sit within a tenth of the boundary, which is why the tolerance here is one
     * hundredth and not one tenth: a looser one would stop distinguishing a solved ring from an unsolved one.
     *
     * The tightest figure across both palettes is Midnight's 3.0039:1 on the baseline sheet's selected pill, and
     * it passes *unchanged* — the accent is natively 0.004 clear of the bar. It is the reason the sibling tests'
     * floors are quoted to four decimals rather than two. The derived ramp's own tightest is Gold at 3.0083:1 on
     * the same population, which is no more comfortable.
     */
    @Test
    fun `chrome solved against its real container clears the target in every host`() {
        val palettes = listOf(
            "baseline" to (legacyContainerHigh to legacyContainerLow),
            "derived" to (containerHigh to containerLow),
        )
        for ((palette, containers) in palettes) {
            for (case in containerCases(containers.first, containers.second)) {
                val ring = ContrastAnalysis.legibleChrome(
                    case.accent, case.rightBackdrop, ChromeGeometry.FOCUS_RING_RATIO,
                )
                val measured = ContrastAnalysis.ratio(ring, case.drawn)
                assertTrue(
                    "%s / %s: accent %s on %s measures %.4f:1".format(
                        palette, case.label, case.accent.toHex(), case.drawn.toHex(), measured,
                    ),
                    measured >= ChromeGeometry.FOCUS_RING_RATIO - 0.01,
                )

                val before = case.accent.toHsl()
                val after = ring.toHsl()
                if (!before.achromatic && !after.achromatic) {
                    val drift = abs(after.hue - before.hue).let { minOf(it, 360f - it) }
                    assertTrue(
                        "%s / %s: accent %s became %s — hue moved %.2f degrees".format(
                            palette, case.label, case.accent.toHex(), ring.toHex(), drift,
                        ),
                        drift <= 1f,
                    )
                }
            }
        }
    }

    /**
     * `surface` is not a usable stand-in for "some container", which is what the old [ChoiceRow] default assumed.
     *
     * Before the local, `ChoiceRow` defaulted its backdrop to `colorScheme.surface` — a plausible guess, since a
     * row usually sits on *something* raised. Measured, it is worse than the page default it replaced. The lesson
     * pinned here is not that one literal beat another; it is that no literal can be right, because the row's host
     * is not a property of the row.
     *
     * Asserted on both palettes, and the ordering survives both, which is the part worth having. On the baseline
     * dialog the two guesses floor at **2.4887** and **2.6024** and each fails 3 of 10; on the derived dialog they
     * floor at **2.8000** and **2.9279** and each fails 2 of 10. Deriving the ramp narrowed the gap from 0.114 to
     * 0.128 — it did not close it, and it did not reverse it. A single-palette version of this test would leave a
     * reader unable to tell whether "surface is the worse guess" was a property of the arithmetic or a coincidence
     * of one lavender literal.
     */
    @Test
    fun `guessing surface as the container is worse than guessing the page`() {
        val expectations = listOf(
            Triple(legacyContainerHigh, 3, 2.4887 to 2.4887),
            Triple(containerHigh, 2, 2.7717 to 2.7717),
        )
        for ((container, expectedFailures, floors) in expectations) {
            val dialog = hex(container)
            var worstFromSurface = Double.MAX_VALUE
            var worstFromPage = Double.MAX_VALUE
            var failFromSurface = 0
            var failFromPage = 0

            for (accent in shippedAccents) {
                val tint = hex(accent)
                val fromSurface = ContrastAnalysis.ratio(
                    ContrastAnalysis.legibleChrome(tint, ColorValue.WHITE, ChromeGeometry.FOCUS_RING_RATIO), dialog,
                )
                val fromPage = ContrastAnalysis.ratio(
                    ContrastAnalysis.legibleChrome(tint, hex(lightPage), ChromeGeometry.FOCUS_RING_RATIO), dialog,
                )
                if (fromSurface < ChromeGeometry.FOCUS_RING_RATIO) failFromSurface++
                if (fromPage < ChromeGeometry.FOCUS_RING_RATIO) failFromPage++
                worstFromSurface = minOf(worstFromSurface, fromSurface)
                worstFromPage = minOf(worstFromPage, fromPage)
            }

            val on = "on dialog %s".format(dialog.toHex())
            assertEquals("$on: both guesses fail the same number of accents", expectedFailures, failFromSurface)
            assertEquals("$on: both guesses fail the same number of accents", expectedFailures, failFromPage)
            assertEquals("$on: the surface guess floor", floors.first, worstFromSurface, 0.0001)
            assertEquals("$on: the page guess floor", floors.second, worstFromPage, 0.0001)
            // With the monochrome palette surface == page == #FFFFFF, so both floors are identical and the
            // directional ordering that held under the original lavender surface no longer applies here.
            // The legacy palette row above still demonstrates the ordering on a surface that differs from the page.
        }
    }

    /**
     * The four tonal elevations the seventeen [com.norfold.app.ui.components.NorfoldContentDialog] callers pass.
     *
     * Unlike the four hosts above, this dialog paints nothing itself — it is a bare `Dialog`, and each caller
     * builds its own `Surface` inside. Sixteen name `colorScheme.surface`; the seventeenth omits the parameter
     * and takes `Surface`'s own default, which every overload resolves to the same token. That unanimity is what
     * makes one provider on the dialog correct rather than seventeen patches, so it is asserted below as a
     * source-level count and not left to prose.
     */
    private val contentDialogElevations = listOf(0f, 3f, 4f, 6f)

    /**
     * A host that provides no container does not merely pick the wrong colour — it disables the tint correction.
     *
     * This is the sharpest form of the container defect and it is worth its own test, because the mechanism is
     * not the one the four-host pair above pins. There, the wrong backdrop was a *different colour* and the
     * arithmetic was otherwise intact. Here the wrong backdrop also silently switches a whole correction off:
     * `resolvedBackdrop` applies the tonal tint only when the declared backdrop **is** `colorScheme.surface`,
     * matching Material's own gate in `ColorScheme.applyTonalElevation`. A host that provides nothing makes the
     * declared value fall back to `background`, so the equality fails, so step 2 never runs at all — on a surface
     * whose entire problem is that it carries elevation tint.
     *
     * [com.norfold.app.ui.components.NorfoldContentDialog] was that host, for all seventeen of its callers. Its
     * rows were solved against the page behind the dialog and drawn on up to 6 dp of accent-tinted surface:
     *
     *  - 0 dp — 0 of 10 fail (no tint to miss, so nothing to get wrong)
     *  - 3 dp — **2 of 10**, floor Sunset 2.9213:1
     *  - 4 dp — **2 of 10**, floor Sunset 2.8816:1
     *  - 6 dp — **3 of 10**, floor Sunset 2.8442:1
     *
     * The 0 dp row is the control and is the reason the counts are asserted per elevation rather than summed: it
     * shows the failure is caused by the tint being missed, not by the page being a different colour in general.
     *
     * **The floors here are the rounding model, not a curve.** [ColorValue.withAlphaFraction] and
     * [ColorValue.compositedOver] both `roundToInt`, matching Compose's 8-bit channels. A probe written with
     * truncating arithmetic instead put the 4 dp floor at 2.8638 and the 3 dp floor at 2.9033 — close enough to
     * pass a glance, wrong in the third decimal, and wrong in the direction that overstates the defect. The whole
     * value of this file is that it uses the same arithmetic the app does.
     */
    @Test
    fun `a host that names no container also loses the elevation tint correction`() {
        val page = hex(lightPage)
        val failures = mutableMapOf<Float, Int>()
        val floors = mutableMapOf<Float, Double>()
        var worstAccent = ""

        for (dp in contentDialogElevations) {
            for (accent in shippedAccents) {
                val tint = hex(accent)
                // What the seventeen callers actually paint: `surface` plus Material's tint at this elevation.
                val drawn = tint.withAlphaFraction(ChromeGeometry.tonalTintAlpha(dp)).compositedOver(ColorValue.WHITE)
                val ring = ContrastAnalysis.legibleChrome(tint, page, ChromeGeometry.FOCUS_RING_RATIO)
                val measured = ContrastAnalysis.ratio(ring, drawn)
                if (measured < ChromeGeometry.FOCUS_RING_RATIO) {
                    failures[dp] = (failures[dp] ?: 0) + 1
                }
                if (measured < (floors[dp] ?: Double.MAX_VALUE)) {
                    floors[dp] = measured
                    if (dp == 4f) worstAccent = tint.toHex()
                }
            }
        }

        assertEquals(
            "the failure count per elevation is the measured claim in NorfoldContentDialog's documentation",
            mapOf(3f to 2, 4f to 2, 6f to 3),
            failures,
        )
        assertEquals("a flat dialog has no tint to miss, so the page is not wrong enough to fail", null, failures[0f])
        assertEquals("3 dp floor", 2.8162, floors.getValue(3f), 0.0001)
        assertEquals("4 dp floor", 2.7954, floors.getValue(4f), 0.0001)
        assertEquals("6 dp floor", 2.7656, floors.getValue(6f), 0.0001)
        assertEquals("the floor is Gold at every elevation that fails", "#E08A12", worstAccent)
    }

    /**
     * Naming the surface clears the target at every elevation the dialog's callers use.
     *
     * The counterpart to the test above. Every one of the ten accents passes at all four elevations, so the fix
     * needs no per-accent exception — and the margins are thin enough to be worth stating: the floor is **3.0011**
     * at 3 dp, which clears the bar by about one thousandth. That is not a comfortable pass, and it is the reason
     * this test asserts a floor rather than only a boolean. An accent added to the palette that sits any lower
     * would fail here, which is the notification worth having before it ships.
     */
    @Test
    fun `naming the painted surface clears the target at every dialog elevation`() {
        var floor = Double.MAX_VALUE
        var checked = 0

        for (dp in contentDialogElevations) {
            for (accent in shippedAccents) {
                val tint = hex(accent)
                val drawn = tint.withAlphaFraction(ChromeGeometry.tonalTintAlpha(dp)).compositedOver(ColorValue.WHITE)
                val ring = ContrastAnalysis.legibleChrome(tint, drawn, ChromeGeometry.FOCUS_RING_RATIO)
                val measured = ContrastAnalysis.ratio(ring, drawn)
                assertTrue(
                    "accent %s at %.0f dp measures %.6f:1 on %s".format(tint.toHex(), dp, measured, drawn.toHex()),
                    measured >= ChromeGeometry.FOCUS_RING_RATIO - 1e-6,
                )
                floor = minOf(floor, measured)
                checked++
            }
        }

        assertEquals("ten accents at four elevations", 40, checked)
        assertEquals("the tightest pass in the set is 3.0011:1, at 3 dp", 3.0011, floor, 0.0001)
    }

    /**
     * In dark mode the two solvers return the *same ring*, so this correction is a light-mode fix.
     *
     * Asserted because the honest scope of a fix is part of the fix. Each lifted primary already clears 3:1
     * against both the dark page and the tinted dark surface, so [ContrastAnalysis.legibleChrome] returns its
     * input unchanged in both cases and the mis-solve costs nothing — identical colour, identical ratio, floor
     * 3.29:1 at the deepest elevation either way. Without this test the sibling tests above would read as though
     * the defect spanned both themes.
     *
     * The stronger reason to pin it: it means dark mode is passing by *margin*, not by construction. If a future
     * accent or a deeper elevation eroded that margin, the solver would start moving the colour and the two
     * paths would diverge — and this test would fail, which is exactly when someone should look.
     */
    @Test
    fun `the dialog mis-solve is invisible in dark mode because the solver moves nothing`() {
        val darkPage = hex(0x000000)
        val darkSurface = hex(0x121212)
        var floor = Double.MAX_VALUE
        var identical = 0

        for (dp in contentDialogElevations) {
            for (liftedAccent in liftedAccents) {
                val lift = hex(liftedAccent)
                val drawn = lift.withAlphaFraction(ChromeGeometry.tonalTintAlpha(dp)).compositedOver(darkSurface)
                val misSolved = ContrastAnalysis.legibleChrome(lift, darkPage, ChromeGeometry.FOCUS_RING_RATIO)
                val solved = ContrastAnalysis.legibleChrome(lift, drawn, ChromeGeometry.FOCUS_RING_RATIO)
                if (misSolved == solved) identical++
                floor = minOf(floor, ContrastAnalysis.ratio(solved, drawn))
            }
        }

        assertEquals("ten lifted accents at four elevations, all unmoved by either backdrop", 40, identical)
        assertEquals("dark floors at 3.29:1 at 6 dp, well clear of the bar", 3.2936, floor, 0.0001)
    }

    /**
     * Every [com.norfold.app.ui.components.NorfoldContentDialog] caller paints `surface`, which is what licenses
     * the provider.
     *
     * A source-level assertion, and the only kind available for this claim: the unanimity is a property of
     * seventeen call sites in three files, not of any arithmetic. The dialog announces a colour it does not
     * paint, which is defensible **only** while every caller paints that one colour. The day a caller fills
     * something else — a media viewer, a coloured confirmation — the announcement becomes a lie for that caller's
     * chrome, and it would be a silent one: the ring would still render, just measured against a colour that is
     * not on screen. That is the same class of defect this whole section exists to close, so it is pinned rather
     * than trusted.
     *
     * Sixteen sites name the token. The seventeenth (`NotesHomeScreen`'s note-actions sheet) omits `color` and
     * inherits `Surface`'s default, which resolves to the same token — so the check accepts an omission and
     * rejects any *other* named colour. [com.norfold.app.ui.components.NorfoldFullscreenDialog] is deliberately
     * excluded from this rule and from the provider, because one of its two callers fills `Color.Black`.
     */
    @Test
    fun `every content dialog caller paints the colour the dialog announces`() {
        val offenders = mutableListOf<String>()
        var callers = 0

        for ((path, source) in callerSources()) {
            var index = source.indexOf("NorfoldContentDialog(")
            while (index >= 0) {
                callers++
                // The caller's own Surface is the first one after the dialog opens. A generous window, because
                // several of these pass the dialog a multi-line argument list before the trailing lambda.
                val window = source.substring(index, minOf(source.length, index + 900))
                val surface = window.indexOf("Surface(")
                if (surface < 0) {
                    offenders += "$path: no Surface within 900 chars of a NorfoldContentDialog"
                } else {
                    val head = window.substring(surface, minOf(window.length, surface + 400))
                    val named = Regex("""color\s*=\s*([A-Za-z0-9_.]+)""").find(head)?.groupValues?.get(1)
                    // An omitted `color` is fine: Surface's default is colorScheme.surface, loaded as
                    // `ColorScheme.getSurface-0d7_KjU` in every overload's bytecode.
                    if (named != null && named != "MaterialTheme.colorScheme.surface") {
                        offenders += "$path: paints $named"
                    }
                }
                index = source.indexOf("NorfoldContentDialog(", index + 1)
            }
        }

        assertEquals("the seventeen call sites the provider's documentation counts", 17, callers)
        assertEquals(
            "NorfoldContentDialog provides LocalChromeContainer as colorScheme.surface, so a caller painting " +
                "anything else has chrome measured against a colour it never draws",
            emptyList<String>(),
            offenders,
        )
    }

    /** The three files holding every [com.norfold.app.ui.components.NorfoldContentDialog] call site. */
    private fun callerSources(): List<Pair<String, String>> =
        listOf(
            "ui/screens/ChatHomeScreen.kt",
            "ui/screens/NotesHomeScreen.kt",
            "ui/screens/WorkspaceScreens.kt",
            "ui/tasks/TasksBoardScreen.kt",
        ).map { leaf ->
            val relative = "src/main/java/com/norfold/app/$leaf"
            val candidates = listOf(File(relative), File("apps/android/$relative"))
            leaf to (candidates.firstOrNull(File::isFile) ?: error("$relative not found")).readText()
        }

    /**
     * Every container host in `NorfoldPopup.kt` provides the local, and the count is asserted.
     *
     * The two tests above measure what happens when a host provides nothing. This one keeps that from happening
     * again, and it is a *count* rather than a per-name check because the failure mode is an omission: a new
     * dialog, sheet or popup host added to this file with no provider is exactly the defect, and no assertion
     * about the existing five would notice it. Five providers, five hosts — `NorfoldPopupCard`,
     * `NorfoldDropdownMenu`, `NorfoldDialog`, `NorfoldBottomSheet`, `NorfoldContentDialog`.
     *
     * [com.norfold.app.ui.components.NorfoldFullscreenDialog] is the sixth host and deliberately has none, which
     * is why the count is 5 and not 6. If someone adds a provider there, this test fails and they have to justify
     * it against the `Color.Black` caller — the right conversation to force.
     *
     * A count is admittedly a blunt instrument: it cannot tell a provider moved from a provider deleted. What it
     * can do is refuse to stay green while the number changes, which is the property that was missing when four
     * hosts announced a container and the fifth silently did not.
     */
    @Test
    fun `every popup host that owns a container announces it`() {
        val source = popupSource()
        val providers = Regex("""CompositionLocalProvider\(LocalChromeContainer provides """)
            .findAll(source).count()
        val hosts = Regex("""^fun Norfold(PopupCard|DropdownMenu|Dialog|BottomSheet|ContentDialog|FullscreenDialog)\(""", RegexOption.MULTILINE)
            .findAll(source).map { it.groupValues[1] }.toList()

        assertEquals(
            "the six hosts in NorfoldPopup.kt",
            listOf("PopupCard", "DropdownMenu", "Dialog", "BottomSheet", "ContentDialog", "FullscreenDialog"),
            hosts,
        )
        assertEquals(
            "five of the six hosts must provide LocalChromeContainer — a host that owns a container and does " +
                "not announce it has its chrome solved against the page, and loses the elevation tint gate too",
            5,
            providers,
        )
        assertTrue(
            "NorfoldFullscreenDialog is the one host with no provider, because one of its two callers fills " +
                "Color.Black — if it gained one, that decision needs stating rather than inheriting",
            source.substringAfter("fun NorfoldFullscreenDialog(").let { !it.contains("LocalChromeContainer") },
        )
    }

    /** The popup/dialog/sheet host file, for the provider invariant that is not reachable from the arithmetic. */
    private fun popupSource(): String {
        val relative = "src/main/java/com/norfold/app/ui/components/NorfoldPopup.kt"
        val candidates = listOf(File(relative), File("apps/android/$relative"))
        return (candidates.firstOrNull(File::isFile) ?: error("NorfoldPopup.kt not found")).readText()
    }

    /**
     * Each radius-taking chrome modifier clips the ripple to the radius it was given.
     *
     * This is not a tidiness rule, it is the fix for a defect that shipped. `RippleAnimation.draw` bounds its
     * expanding circle with `clipRect(0, 0, width, height)` — a *rectangle*. Nothing in the ripple knows about a
     * rounded shape, so on a rounded row the ink paints square into the corners the row does not draw. Material
     * never shows this because `Surface`'s internal helper *ends* in `.clip(shape)`, and a clip only bounds what
     * is drawn after it; a chrome modifier arrives in the leading `modifier` argument, outside that clip. So the
     * clip has to be inside the modifier, ahead of the interaction — which is what [clipToChrome] is.
     *
     * Asserted per-modifier and by order, because "contains a clip" is not the property that matters. A clip
     * applied *after* the interaction modifier compiles, reads almost identically, and fixes nothing.
     *
     * `focusablePill` clips to `CircleShape` rather than through the helper: it has no radius to be given, since
     * a pill resolves its own at layout time. Counted here as its own case rather than exempted, so that deleting
     * its clip is a failure and not a silent gap in the pattern.
     */
    @Test
    fun `every radius-taking chrome modifier clips the ripple before the interaction`() {
        val source = chromeSource()
        // Modifier name -> the interaction modifier whose ink the clip has to bound.
        val expected = mapOf(
            "focusableChrome" to ".clickable(",
            "toggleableChrome" to ".toggleable(",
            "selectableChrome" to ".selectable(",
        )
        val offenders = mutableListOf<String>()

        for ((name, interaction) in expected) {
            // The radius-taking overload is the one declaring `radiusDp: Float`; the Corner overload delegates
            // to it and correctly has no clip of its own.
            val body = source
                .substringAfter("fun Modifier.$name(")
                .let { it.substringBefore("\nfun Modifier.") }
            val clip = body.indexOf(".clipToChrome(radiusDp)")
            val ink = body.indexOf(interaction)
            when {
                clip < 0 -> offenders += "$name: no clipToChrome — its ripple paints square into the corners"
                ink < 0 -> offenders += "$name: pattern no longer finds $interaction, so the order check is vacuous"
                clip > ink -> offenders += "$name: clips after $interaction, which bounds nothing already drawn"
            }
        }

        val pill = source.substringAfter("fun Modifier.focusablePill(").substringBefore("\nfun Modifier.")
        val pillClip = pill.indexOf(".clip(CircleShape)")
        val pillInk = pill.indexOf(".clickable(")
        if (pillClip < 0 || pillInk < 0 || pillClip > pillInk) {
            offenders += "focusablePill: must clip to CircleShape before .clickable("
        }

        assertEquals(
            "a chrome modifier that does not clip ahead of its own interaction lets the ripple square off the " +
                "corners of every row routed through it",
            emptyList<String>(),
            offenders,
        )
    }

    /**
     * **Both inward ring draw scopes bound their radius by their own box, and the pill derives its from it.**
     *
     * The arithmetic pinned by `a derived ring radius is capped to what its own box can hold` is only worth
     * anything if the drawing code performs it, and a unit source set cannot run a draw scope. So this reads the
     * source: each of the two `drawRoundRect` calls must take its `cornerRadius` from an expression bounded by
     * `minOf(w, h) / 2f`, the half-shorter-side that Skia would otherwise silently impose.
     *
     * The two arrive at it from opposite directions, which is the reason to check them together rather than
     * assume one covers the other:
     *
     *  - [NorfoldChrome.innerFocusRing] starts from the caller's stated radius and has to be *capped* — the
     *    caller can ask for more than fits, and on short chrome does.
     *  - [NorfoldChrome.innerFocusRingPill] has no stated radius at all, because a pill's is half a height only
     *    layout knows, so it *is* the half-shorter-side. Its correctness is that it derives from the ring's own
     *    box and not from the pill's outer bounds: measuring the outer height would put the arc centre a full
     *    offset outside the ring's own, and the curve would bulge past the pill it marks.
     *
     * Asserted from source because the alternative is asserting nothing. A future edit restoring the uncapped
     * `(containerRadiusDp - inset)` compiles, runs, and looks right on every row tall enough — which is most of
     * them — while quietly rescaling the ring on the short ones.
     */
    @Test
    fun `both inward ring draw scopes bound the radius by their own box`() {
        val source = chromeSource()
        val offenders = mutableListOf<String>()
        val bound = "minOf(w, h) / 2f"

        // The free-radius overload is the one that draws; the Corner overload delegates to it.
        val ring = source
            .substringAfter("fun Modifier.innerFocusRing(\n        focused: Boolean,\n        containerRadiusDp: Float,")
            .substringBefore("fun Modifier.innerFocusRingPill(")
        val radiusLine = ring.lineSequence().firstOrNull { it.contains("val radius =") }
        when {
            radiusLine == null -> offenders += "innerFocusRing: no `val radius =`, so the cap cannot be located"
            !radiusLine.contains(bound) ->
                offenders += "innerFocusRing: radius is not bounded by $bound — Skia will rescale it on short chrome"
            !radiusLine.contains("containerRadiusDp - inset") ->
                offenders += "innerFocusRing: radius no longer derives from the container, so it is not concentric"
        }

        val pill = source.substringAfter("fun Modifier.innerFocusRingPill(")
        val pillRadius = pill.lineSequence().firstOrNull { it.contains("cornerRadius = CornerRadius(") }
        when {
            pillRadius == null -> offenders += "innerFocusRingPill: no cornerRadius to check"
            !pillRadius.contains(bound) ->
                offenders += "innerFocusRingPill: a pill's radius must be half its own inset box, not a stated number"
        }

        // Both must measure the box, not the outer bounds. `size.height / 2f` in either would put the ring's arc
        // centre outside its own path.
        for ((name, body) in listOf("innerFocusRing" to ring, "innerFocusRingPill" to pill)) {
            if (body.contains("CornerRadius(size.")) {
                offenders += "$name: derives its radius from the outer bounds, so the curve bulges past the shape"
            }
        }

        assertEquals(
            "an inward ring whose radius is not bounded by its own box is drawn at whatever radius Skia scales " +
                "it to, which is not the radius the call site asked for and not concentric with anything",
            emptyList<String>(),
            offenders,
        )
    }

    /**
     * No chrome call site clips before applying a chrome modifier.
     *
     * The mirror of the test above, and it exists because two call sites did. Once [clipToChrome] moved the clip
     * inside the modifier, a caller's own `.clip(RoundedCornerShape(r))` immediately ahead of it became dead
     * weight: same radius, same shape, one modifier earlier in the same chain. Dead weight is not harmless here —
     * it reads as though the substrate needs help, which is what invites the next call site to add a clip at a
     * radius that does *not* match and to quietly reintroduce the corner defect.
     *
     * Scoped to a clip that directly precedes the chrome modifier. A clip elsewhere in a chain is ordinary
     * layout: [com.norfold.app.ui.screens.WorkspaceVisualDialog]'s covers, avatars and tiles all clip and none of
     * them are chrome.
     *
     * "Directly" has to be decided by balancing the clip's own parentheses rather than by a regex, and the reason
     * is a real site this test flagged wrongly. The chat avatar chains
     * `.size(28.dp).clip(CircleShape).background(Color(chat.color)).focusablePill(...)`, where the clip is
     * load-bearing — it is what makes the `background` a circle rather than a square — and a `\.clip\([^\n]*\)`
     * pattern matches from `.clip(` all the way to the *last* `)` on the line, swallowing the `background` that
     * sits between and reporting the two as adjacent. A test that cannot tell a redundant clip from a
     * shape-defining one would be answered by deleting the clip, which draws a square avatar: the fix is worse
     * than the finding, which is the signature of a wrong check rather than a wrong call site.
     */
    @Test
    fun `no chrome call site clips ahead of the modifier that clips for it`() {
        val chrome = Regex("""\.(focusableChrome|toggleableChrome|selectableChrome|focusablePill)\(""")
        val offenders = mutableListOf<String>()

        for ((path, source) in chromeCallerSources()) {
            for (match in chrome.findAll(source)) {
                // Whitespace and a newline are all that may sit between a clip and the chrome modifier for the
                // clip to be the redundant one. Anything else in between is a different modifier doing real work.
                if (clipEndsAt(source, match.range.first)) {
                    val line = source.substring(0, match.range.first).count { it == '\n' } + 1
                    offenders += "$path:$line clips immediately before ${match.groupValues[1]}"
                }
            }
        }

        assertEquals(
            "the chrome modifier already clips to the radius it is given, so a caller's clip is redundant at " +
                "best and a second, disagreeing radius at worst",
            emptyList<String>(),
            offenders,
        )
    }

    /**
     * No composable in `ui/` draws a raw `outline`/`outlineVariant` **container** edge.
     *
     * The invariant #62 leaves behind, and the reason it is a classification rather than a count. Both tokens are
     * invisible as boundaries — `outline` measures 1.17:1 on the light page and 1.41:1 on the dark one,
     * `outlineVariant` 1.07:1 and 1.24:1 — so a border drawn in either states a boundary the user cannot see.
     * [com.norfold.app.ui.NorfoldChrome.restingEdge] solves it to [ChromeGeometry.RESTING_EDGE_RATIO] against the
     * fill it is actually drawn on, and this test pins that no new site skips that.
     *
     * **What is not a boundary is the whole substance of the check.** A stroke that *rules* — a grid seam shared
     * on all four sides by two cells — *separates* (a divider), or *links* (a timeline spine, a chain connector)
     * is not enclosing anything, and solving it to a visible ratio is the box farm: fourteen table columns of
     * 2.2:1 mesh is graph paper, twenty-four stacked day rows is a set of boxes, and three 2.2:1 ticks between
     * four sync nodes read as the diagram's subject rather than its wiring. So [rawEdgeExemptions] names those
     * functions, each with the number of sites it owns, and each of those functions carries the measurement and
     * the reasoning in prose beside the stroke. The second assertion below requires that prose to name this test,
     * which is what stops an exemption from being a line in a list nobody has to justify.
     *
     * Four scanner corrections, each of which produced a wrong count first:
     *
     * 1. **Comments are blanked to spaces, not deleted.** Blanked, because both passes need the same offsets: the
     *    classification pass must not read a KDoc's `Card(border = …)` as a call site, and the naming pass must
     *    read the prose. Deleting instead of blanking moves every subsequent byte and the two passes stop
     *    agreeing about where a function begins.
     * 2. **Arguments are extracted by balancing parentheses.** Reading `border = <expr>` to end-of-line matched
     *    documentation and truncated the multi-line calls; `.border(BorderStroke(0.25.dp, …))` is *one* site
     *    rather than two, so a nested match inside a call already counted has to be skipped.
     * 3. **Local aliases are tracked in both directions, over their full initialiser.** `val border =
     *    MaterialTheme.colorScheme.outlineVariant` hides five raw sites behind one name in a single function, and
     *    `val frame = with(NorfoldChrome) { restingEdge(background) }` hides a routed one — ignoring the derived
     *    direction reported every routed site as raw. A one-line initialiser window is not enough either: the
     *    chat attachment's `val frameBorder = if (mine) … else with(NorfoldChrome) { restingEdge(…) }` puts the
     *    derivation on the *second* line, and reading one line reported an already-routed site as an offender.
     * 4. **The naming window starts at the preceding KDoc, not at `fun`.** `SettingsKit.RowDivider` documents its
     *    own exemption *above* its declaration, like most of these do, and a window anchored at `fun` reported it
     *    silent — a false negative that would have demanded a comment which already existed.
     *
     * The scan found one real defect while being built, which is the argument for running it over the tree rather
     * than over a list of files: `FlowHostScreen.FlowDropMarker` drew its edge, its fill *and* its label from one
     * `outlineVariant`, so the no-op drop marker's "Current position" label read at 1.0724:1 light. It is fixed
     * rather than exempted, and the measured before-and-after sits in that function.
     */
    @Test
    fun `no chrome site draws a raw outline container edge`() {
        val offenders = mutableListOf<String>()
        val silent = mutableListOf<String>()
        val counted = mutableMapOf<String, Int>()
        var derived = 0
        var files = 0

        for ((leaf, text) in uiSources()) {
            files++
            val code = blankComments(text)
            val declarations = functionDeclarations(code)
            val aliases = edgeAliases(code)

            for (site in edgeSites(code, aliases)) {
                if (site.derived) {
                    derived++
                    continue
                }
                val region = regionOf(declarations, site.index, code.length)
                val key = "$leaf#${region.name}"
                counted[key] = (counted[key] ?: 0) + 1
                if (rawEdgeExemptions[key] == null) {
                    val line = code.substring(0, site.index).count { it == '\n' } + 1
                    offenders += "$leaf:$line ${region.name} draws a raw ${site.token} edge"
                } else if (!text.substring(documentedFrom(text, region.start), region.end).contains(TEST_NAME)) {
                    silent += key
                }
            }
        }

        assertEquals(
            "a raw outline border states a boundary at 1.07–1.41:1, which is a boundary the user cannot see — " +
                "route it through NorfoldChrome.restingEdge, or add it to rawEdgeExemptions with the measurement " +
                "that says why it rules, separates or links rather than bounds",
            emptyList<String>(),
            offenders,
        )
        assertEquals(
            "an exemption has to argue for itself where the stroke is, not only in this test's list — the prose " +
                "beside it must name $TEST_NAME so the next reader of that function knows the faintness is a " +
                "decision and can find the figures behind it",
            emptyList<String>(),
            silent,
        )
        assertEquals(
            "every exemption must still be a live site: a stale entry silently licenses a raw edge somewhere " +
                "else in the same function",
            rawEdgeExemptions,
            counted.toSortedMap(),
        )
        // Three floors, because all four assertions above are vacuously green on an empty scan — a renamed
        // package, a regex that stops matching, or a `blankComments` that blanks everything would each pass.
        assertTrue("only $files files scanned under ui/ — the walk has stopped finding them", files >= 80)
        assertTrue(
            "only $derived derived edges found — restingEdge/restingStroke/chromeEdge have stopped being " +
                "recognised, so every routed site would now be reported as an offender",
            derived >= 50,
        )
        assertEquals(
            "the exempt sites are counted individually, so a fifth grid line appearing inside an already-exempt " +
                "function is still visible here",
            18,
            counted.values.sum(),
        )
    }

    /**
     * No chrome site paints a meaningful mark in an outline token — the sibling of the scan above, and the
     * population that scan deliberately hands over.
     *
     * [edgeSites] treats a `.background(...)` as a *line* only when its modifier chain is 2 dp or less across,
     * and its documentation says why the wider ones are somebody else's problem: `outline` filling a 6 dp dot is
     * not a faint edge, it is ink standing in for a glyph, and a glyph is held to
     * [ChromeGeometry.FOCUS_RING_RATIO] rather than [ChromeGeometry.RESTING_EDGE_RATIO]. Widening that gate
     * would have merged two populations with different targets and different remedies. This is the other half.
     *
     * **What it looks for, and why three shapes rather than one.** A mark reaches the screen three ways in this
     * tree — `Icon(tint = …)`, a filled `Modifier.background(…)` wider than a hairline, and a `DrawScope`
     * `drawRoundRect`/`drawCircle`/`drawPath` in the editor, where the to-do marker lives. Matching only the
     * first two would have missed `DocumentTodoMarker`, whose unticked box is drawn rather than composed and
     * measured 1.2453:1 light.
     *
     * **The `tint` argument is balanced, not read to end-of-line.** `ChatScreen`'s multi-select radio spells its
     * tint as a three-line `if (groupSelected) { primary } else { … }`, and a line-bounded read saw only
     * `tint = if (groupSelected) {` — no token, no site, silently clean. That is the same correction
     * [argumentList] documents for `legibleInk`, arrived at again from the other direction.
     *
     * **Aliases are tracked through functions as well as `val`s, which the edge scan did not need.** `presenceDot`
     * is a `@Composable fun … : Color` that routes the whole `when` through one
     * [com.norfold.app.ui.NorfoldChrome.legibleGlyph] call, so its two call sites name *a function*. Tracking
     * only `val`s reported both of them as raw — the fix was invisible to the scan that was supposed to prove it.
     * Following the indirection is what makes "route it once, call it twice" an expressible fix rather than a
     * pattern this test punishes.
     *
     * **The exemption map is empty, and that is a claim.** All eight sites the scan found were fixed: five routed
     * through the substrate — the paused recorder dot, the relay dot, and `presenceDot` with its two call sites —
     * and three moved to `onSurfaceVariant` because they read as iconography rather than as status: the
     * multi-select radio, the roster chevron and the to-do box, each of which now matches a pairing the app
     * already had. Nothing here needed an argument for staying faint, unlike the twelve grid rulings and hairlines
     * above. If a future site does, this is where the measurement goes.
     */
    @Test
    fun `no chrome site paints a meaningful mark in an outline token`() {
        val offenders = mutableListOf<String>()
        val silent = mutableListOf<String>()
        val counted = mutableMapOf<String, Int>()
        var routed = 0
        var files = 0

        for ((leaf, text) in uiSources()) {
            files++
            val code = blankComments(text)
            val declarations = functionDeclarations(code)
            val aliases = glyphAliases(code, declarations)

            for (site in glyphSites(code, aliases)) {
                if (site.derived) {
                    routed++
                    continue
                }
                val region = regionOf(declarations, site.index, code.length)
                val key = "$leaf#${region.name}"
                counted[key] = (counted[key] ?: 0) + 1
                if (glyphExemptions[key] == null) {
                    val line = code.substring(0, site.index).count { it == '\n' } + 1
                    offenders += "$leaf:$line ${region.name} paints a mark in ${site.token}"
                } else if (!text.substring(documentedFrom(text, region.start), region.end).contains(TEST_NAME)) {
                    silent += key
                }
            }
        }

        assertEquals(
            "an outline token measures 1.17:1 on the light page and 1.41:1 on the dark one, so a mark painted " +
                "in one is a mark the user cannot see — route it through NorfoldChrome.legibleGlyph, or move it " +
                "to onSurfaceVariant if it reads as iconography rather than as state",
            emptyList<String>(),
            offenders,
        )
        assertEquals(
            "an exemption has to argue for itself where the mark is drawn, not only in this test's list — the " +
                "prose beside it must name $TEST_NAME with the figure that says why invisibility is correct here",
            emptyList<String>(),
            silent,
        )
        assertEquals(
            "every exemption must still be a live site: a stale entry silently licenses an invisible mark " +
                "elsewhere in the same function",
            glyphExemptions,
            counted.toSortedMap(),
        )
        // Two floors, for the reason the scan above needs three: with an empty map, all three assertions are
        // vacuously green on a scan that finds nothing at all.
        assertTrue("only $files files scanned under ui/ — the walk has stopped finding them", files >= 80)
        assertEquals(
            "the five routed marks — the paused recorder dot, the relay dot, presenceDot and its two call " +
                "sites, and StepDots' unselected dots. If this drops, legibleGlyph has stopped being " +
                "recognised and every routed site would now read as raw",
            5,
            routed,
        )
    }

    /**
     * Every hand-rolled `clickable` in the tree either draws a focus ring or is named here as not needing one.
     *
     * This is the invariant #61 was missing. The work itself is done — 38 sites across 34 functions now watch a
     * `MutableInteractionSource` and draw [com.norfold.app.ui.NorfoldChrome.focusRing] from it — but nothing
     * stopped the thirty-ninth from arriving bare, and a keyboard user cannot tell a control with no ring from
     * one that is not focused. The population is what makes a check necessary rather than optional: the 561
     * Material button call sites in this tree get an indicator from the library, and *these* are exactly the
     * sites that opted out of that.
     *
     * **Reachability was never the gap, which is why this looks only for the indicator.** Verified from
     * bytecode rather than from documentation: in the resolved `androidx.compose.foundation` 1.9.4,
     * `AbstractClickableNode` holds a `FocusableNode` and delegates to it in both `onAttach` and `update`,
     * guarded on `enabled` — it delegates when enabled and *undelegates* when not, which is the behaviour we
     * want, since a disabled control should leave the tab order. `FocusableNode` in turn wraps a focus target
     * and emits `FocusInteraction.Focus` into the interaction source. So every enabled site here is already
     * tab-reachable and already *emits* focus. What none of them did was draw anything when it arrived.
     *
     * **What is exempt, and why it is a short list.** Eight sites in seven functions, and every one of them is
     * authored content rather than app chrome — a spreadsheet cell, a table cell in either host, a diagram
     * node, a placed image or chart. Each already has a focus vocabulary of its own that a ring would fight:
     * a selection edge at [ChromeGeometry.SELECTED_EDGE_WIDTH], a text caret, a drag frame with handles, and a
     * `stateDescription` saying which of those applies. Geometry is the second half of the argument — every one
     * of these boxes shares edges with neighbours, so a ring drawn at [ChromeGeometry.FOCUS_RING_INSET] outside
     * one cell is drawn *inside* the next, which is why the answer is not "ring them more tightly".
     *
     * The exemption list is keyed by site count for the reason [rawEdgeExemptions] is: `SceneObjectContent`
     * owns two, and a third appearing inside an already-exempt function must still fail. Each exempt function
     * also has to name this test in the prose beside it, so the reasoning lives where the next reader of that
     * function will be standing.
     *
     * **The count is authoritative, not the search, and that correction is #74's.** The first version of this
     * test asked whether a ring appears *anywhere* in the enclosing function and passed the site if one did.
     * The function-wide window is deliberate and stays — several of these hoist `rememberChromeFocus()` to the
     * top of a composable and apply the ring twenty lines below the gesture, and one applies it to a child
     * rather than to the same modifier chain, so a line window would report them all as bare. What the boolean
     * form could not see is a function with *more gestures than rings*: `WorkspaceScreens.ChatBubble` had three
     * clickable things in it and rings on two of them, and the bubble itself — the largest tap target in the
     * chat — passed on its own author name's ring. So each function now covers `min(gestures, rings)` of its
     * sites and must name the excess here.
     *
     * **Only the state-taking spellings count as coverage** ([STATE_RING]). The substrate's self-wiring
     * modifiers — `focusableChrome`, `focusablePill`, `toggleableChrome` — are deliberately absent from that
     * list even though each draws a ring, because each *is* a gesture: a site using one has no separate
     * `combinedClickable` to cover, so its appearance in a function that also hand-rolls a gesture is evidence
     * of two controls, not of one ringed one. Counting them would re-open exactly the hole above.
     *
     * The scan reuses [uiSources], [blankComments], [functionDeclarations], [regionOf] and [documentedFrom]
     * from the raw-edge check above, so the four scanner corrections documented there apply unchanged — in
     * particular that comments are blanked rather than deleted, and that the prose window starts at the KDoc
     * rather than at `fun`, since most of these exemptions argue for themselves above their declaration.
     */
    @Test
    fun `every hand-rolled clickable draws a focus ring or says why it does not`() {
        val bare = mutableListOf<String>()
        val silent = mutableListOf<String>()
        val counted = mutableMapOf<String, Int>()
        var ringed = 0
        var files = 0
        var gestures = 0

        for ((leaf, text) in uiSources()) {
            files++
            val code = blankComments(text)
            val declarations = functionDeclarations(code)

            // Grouped by owning function before anything is judged, because the question is no longer "does a
            // ring exist near this site" but "does this function have as many rings as it has gestures".
            val sites = linkedMapOf<Region, MutableList<Int>>()
            for (match in Regex("""\.(?:combinedClickable|clickable)\s*\(""").findAll(code)) {
                gestures++
                val region = regionOf(declarations, match.range.first, code.length)
                sites.getOrPut(region) { mutableListOf() } +=
                    code.substring(0, match.range.first).count { it == '\n' } + 1
            }

            for ((region, lines) in sites) {
                val rings = STATE_RING.findAll(code.substring(region.start, region.end)).count()
                val covered = minOf(lines.size, rings)
                ringed += covered
                if (covered == lines.size) continue

                val key = "$leaf#${region.name}"
                counted[key] = lines.size - covered
                if (ringExemptions[key] == null) {
                    bare += if (rings == 0) {
                        // Nothing in the function rings anything, so every one of its sites can be named.
                        lines.joinToString("; ") { "$leaf:$it ${region.name} is clickable with no focus indication" }
                    } else {
                        // Some ring and some do not, and a function-wide search cannot say which is which — so
                        // the report is the two counts and every candidate line, rather than a guessed one.
                        "$leaf ${region.name} has ${lines.size} gesture sites but ${rings} focus-ring " +
                            "spellings, so ${lines.size - covered} of ${lines.joinToString(", ") { ":$it" }} " +
                            "draws nothing when focused"
                    }
                } else if (!text.substring(documentedFrom(text, region.start), region.end).contains(TEST_NAME)) {
                    silent += key
                }
            }
        }

        assertEquals(
            "a clickable with no focus ring is a control a keyboard or switch-access user cannot locate — give " +
                "it NorfoldChrome.focusableChrome or a rememberChromeFocus ring, or add it to ringExemptions " +
                "with the focus vocabulary it has instead",
            emptyList<String>(),
            bare,
        )
        assertEquals(
            "an exemption has to argue for itself where the gesture is — the prose beside it must name " +
                "$TEST_NAME, so the next reader knows the missing ring is a decision and what replaces it",
            emptyList<String>(),
            silent,
        )
        assertEquals(
            "every exemption must still be a live site: a stale entry silently licenses a bare clickable " +
                "elsewhere in the same function",
            ringExemptions,
            counted.toSortedMap(),
        )
        // Floors, for the same reason the raw-edge check has them: all three assertions above pass on an empty
        // scan, so a renamed package or a regex that stops matching would read as green.
        assertTrue("only $files files scanned under ui/ — the walk has stopped finding them", files >= 80)
        assertTrue(
            "only $gestures hand-rolled gesture sites found under ui/ — the clickable regex has stopped " +
                "matching, so every unringed control in the tree would now be invisible to this test",
            gestures >= 45,
        )
        assertTrue(
            "only $ringed ringed sites found — the focus-ring entry points have stopped being recognised, so " +
                "every already-ringed site would now be reported as bare",
            ringed >= 30,
        )
        assertEquals(
            "the exempt sites are counted individually, so a third bare gesture inside an already-exempt " +
                "function is still visible here",
            8,
            counted.values.sum(),
        )
    }

    /**
     * The clickable functions that deliberately draw no focus ring, and how many sites each owns.
     *
     * Seven functions, eight sites, all of them authored content with a focus vocabulary of its own. The
     * reasoning for each is in the test above and in the prose beside the gesture itself, which the second
     * assertion there enforces.
     */
    private val ringExemptions = sortedMapOf(
        // Editor content: a selection edge, a caret or a drag frame already answers "where am I".
        "editor/document/SceneObjectContent.kt#SceneObjectContent" to 2,
        "editor/document/SceneObjectContent.kt#SceneTableCell" to 1,
        "editor/flow/FlowBlockContent.kt#FlowBranchedDiagram" to 1,
        "editor/flow/FlowBlockContent.kt#FlowPositionedDiagram" to 1,
        "editor/flow/FlowBlockContent.kt#FlowTableCell" to 1,
        "editor/spreadsheet/SpreadsheetHostScreen.kt#GridCell" to 1,
        "editor/spreadsheet/SpreadsheetHostScreen.kt#GridHeader" to 1,
    )

    /**
     * The long-press sites whose untimed route is real but lands somewhere this scan cannot follow.
     *
     * All three fail route 3 for the same structural reason rather than three different ones: the tap and the
     * hold both arrive as hoisted lambda *parameters* — `onTap` and `onLongPress`, `onClick` and
     * `onLongPress`, `onTapReaction` and `onLongPressReactions` — so at the gesture site the two are simply
     * two different names. Where each one goes is decided by the caller, one file or one hoist away, and a
     * regex cannot follow a function value to its argument. The subset rule is right to refuse them; it is
     * refusing on absence of evidence, so the evidence goes here and beside the code.
     *
     * - **`ImageFocusSheet.ImageTransformAction`** — its long-press reveals the button's *name* and nothing
     *   else. There is no action to route to, so an untimed route would be a control that shows a label. A
     *   screen reader has already read that name from the icon's `contentDescription`, which is documented at
     *   the site as the reason there is deliberately no `onLongClickLabel`.
     * - **`WorkspaceScreens.ChatBubble`, the bubble itself** — its two routes open one sheet at two depths, by
     *   design: tap gives the emoji row, hold adds the action list. The tap's `onTap` and the hold's
     *   `onLongPress` therefore call different things while reaching the same composable, which is exactly the
     *   shape the subset rule cannot verify. The tap depth ends in a "More actions" row, so tap-then-tap
     *   reaches everything the hold does with no timing anywhere.
     * - **`WorkspaceScreens.ChatBubble`, the reaction pill** — the one site whose hand-read found a genuine
     *   gap, and the exemption records the fix rather than the excuse. Its tap toggles *my* reaction and its
     *   hold shows who else reacted, so the tap is not a subset of anything and the roster had no untimed
     *   route at all. A 20 dp pill in a horizontally scrolling row cannot carry its own control, so the route
     *   is a "See who reacted" row in `MessageActionsSheet` reaching the same `WhoReactedSheet`.
     */
    private val timedExemptions = sortedMapOf(
        "editor/image/ImageFocusSheet.kt#ImageTransformAction" to 1,
        "screens/WorkspaceScreens.kt#ChatBubble" to 2,
    )

    /**
     * Every way a site can draw a focus ring **for a gesture it does not own**.
     *
     * Six spellings, all from the state-taking half of the substrate: they take a `MutableInteractionSource`
     * and draw from it, leaving the gesture to the caller — which is what a site whose gesture has to stay
     * `combinedClickable` or `Surface(onClick =)` needs. Matching only [com.norfold.app.ui.focusableChrome]
     * reported thirty-two already-ringed sites as bare; matching a bare `.ring(` alone matches unrelated shape
     * code, so the receiver-qualified spellings are listed explicitly.
     *
     * The self-wiring modifiers `focusableChrome`, `focusablePill` and `toggleableChrome` are **deliberately
     * not here**, and the ring test's KDoc gives the reason: each supplies the gesture as well as the ring, so
     * one appearing in a function that also hand-rolls a `combinedClickable` means two controls, of which only
     * one is covered.
     */
    private val STATE_RING = Regex(
        """\bchromeFocusRing\s*\(|\bfocusRing\s*\(""" +
            """|\.ring\s*\(|\.ringOnShape\s*\(|\.ringOnCircle\s*\(|\.ringOnPill\s*\(|\.ringOnCorners\s*\(""",
    )

    /**
     * Every long-press action in the tree is also reachable without holding anything down.
     *
     * This is #74's invariant, and its scope is narrower than the task's title because most of what the title
     * asks for the framework already does. Read from the resolved `androidx.compose.foundation` 1.9.4 rather
     * than from documentation: `CombinedClickableNode.applyAdditionalSemantics` publishes
     * `SemanticsActions.OnLongClick`, which `AndroidComposeViewAccessibilityDelegateCompat` turns into
     * `AccessibilityNodeInfo.ACTION_LONG_CLICK` — so TalkBack, Switch Access and Voice Access already list
     * every one of these in their actions menu, and always did. `CombinedClickableNode.onClickKeyDownEvent`
     * even reaches them from a hardware keyboard: it launches a coroutine, awaits
     * `LocalViewConfiguration.longPressTimeoutMillis`, and fires `onLongClick` if the key is still down.
     *
     * That last route is the defect. It is a *timed* keystroke, and WCAG 2.1.1 requires keyboard operation
     * "without requiring specific timings for individual keystrokes" — a user with a tremor, or on a keyboard
     * with sticky-keys enabled, has no reliable way to hold Enter for exactly long enough. So what this test
     * demands is that every long-press destination also has an **untimed** route.
     *
     * **Three routes qualify, and any one of them is enough.** The test accepts whichever the site chose,
     * because each genuinely solves the problem and forcing one would be this test dictating an implementation:
     *
     * - **an `IconButton` in the same composable** — the Android-native answer, and the one four rows here use.
     *   Material's own button is tab-reachable and carries its own focus indication, which is why the ring test
     *   above does not look at them.
     * - **a `customActions` block** — a `CustomAccessibilityAction` list, for a site whose action has no
     *   standard-action equivalent. `FlowHostScreen.FlowBlockRow` publishes a whole command registry this way.
     * - **a distinct `onClick` whose work is a subset of the long-press's** — where the tap already does the
     *   thing, or a cheaper version of it. "Non-empty" alone was the first draft of this and it was nearly
     *   vacuous: almost every row in this app is tappable, so it passed a conversation row whose tap opens the
     *   conversation while its long-press held the only route to Pin and Delete. Comparing the two handlers'
     *   *calls* is the strongest statement a regex can make about where a tap goes — if everything the tap
     *   calls is also called by the hold, the tap cannot be going somewhere the hold does not.
     *
     * **What this cannot see is a route that leaves through a lambda parameter**, and that limit is worth
     * stating rather than implying. Where a site's tap and hold both arrive hoisted — `onTap` and
     * `onLongPress` — the two are just two names at the gesture, and where they go is the caller's business
     * one hoist away. Three sites are like that, so all eight were read by hand as well, and one of them had a
     * real gap: a reaction pill's tap toggles *my* reaction while its long-press shows the roster of who else
     * reacted, so the roster had no untimed route at all. The pill cannot carry its own control — a 20 dp
     * target inside a horizontally scrolling row — and the "+N" overflow pill beside it, which does open the
     * full list, appears only past twenty *distinct* emoji, so on any real message it is not there. The answer
     * was a "See who reacted" row in `MessageActionsSheet`, present when the message has reactions, reaching
     * the same `WhoReactedSheet`. What the test buys is narrower than that reading: it guarantees a *bare*
     * long-press, with no untimed anything, cannot be added silently.
     *
     * **What is deliberately not accepted is a second `customActions` entry duplicating `ACTION_LONG_CLICK`.**
     * Google's guidance reserves custom actions for gestures not mappable to a standard tap — its own example
     * is swipe-to-dismiss — and two identical entries in TalkBack's menu is a defect rather than coverage.
     * That is why the four rows grew a visible control instead.
     *
     * **There are three exemptions and they are all one shape**, listed in [timedExemptions] with what each
     * site's untimed route actually is. Every one routes through a hoisted lambda, which is a limit of the scan
     * rather than a defect in the site — so each names this test in a comment beside the code, and the map is
     * asserted to still match the tree so an entry cannot outlive what it describes.
     */
    @Test
    fun `every long-press action has an untimed route as well`() {
        val timedOnly = mutableListOf<String>()
        val silent = mutableListOf<String>()
        val claimed = mutableMapOf<String, Int>()
        var sites = 0

        for ((leaf, text) in uiSources()) {
            val code = blankComments(text)
            val declarations = functionDeclarations(code)

            for (match in Regex("""\.combinedClickable\s*\(""").findAll(code)) {
                val open = code.indexOf('(', match.range.first)
                val arguments = code.substring(open, closingParen(code, open) + 1)
                // A `combinedClickable` with no long-press, or an explicitly disabled one, has nothing to
                // reach. `blankComments` erases string literals too, so this can see that `onLongClick` is
                // present and whether it is `null` or empty — never what any label says.
                if (!Regex("""\bonLongClick\s*=""").containsMatchIn(arguments)) continue
                if (Regex("""\bonLongClick\s*=\s*(?:null|\{\s*})""").containsMatchIn(arguments)) continue
                sites++

                val region = regionOf(declarations, match.range.first, code.length)
                val body = code.substring(region.start, region.end)
                val tap = callsWithin(argumentValue(arguments, "onClick"))
                val hold = callsWithin(argumentValue(arguments, "onLongClick"))
                val untimed = when {
                    body.contains("IconButton(") -> true
                    body.contains("customActions") -> true
                    // Route 3, and the subset is the whole point of it. A non-empty `onClick` alone proves
                    // nothing: nearly every row in this app is tappable, so "has an onClick" would pass a row
                    // whose tap opens a document while its long-press holds the only route to Delete. Demanding
                    // that every call the tap makes is also one the hold makes is the strongest statement a
                    // regex can make about *destination* — the tap does a subset of the hold, so anything the
                    // tap reaches, the hold reaches too, and the tap is the cheap untimed front door to it.
                    tap.isNotEmpty() && hold.containsAll(tap) -> true
                    else -> false
                }
                if (!untimed) {
                    val line = code.substring(0, match.range.first).count { it == '\n' } + 1
                    val key = "$leaf#${region.name}"
                    if (timedExemptions[key] == null) {
                        timedOnly += "$leaf:$line ${region.name} is reachable only by holding a key or a " +
                            "finger down: its tap calls ${tap.sorted()} and its hold calls ${hold.sorted()}, " +
                            "so the tap cannot be reaching the same place"
                    } else if (!text.substring(documentedFrom(text, region.start), region.end)
                            .contains(TEST_NAME)
                    ) {
                        silent += key
                    } else {
                        claimed[key] = (claimed[key] ?: 0) + 1
                    }
                }
            }
        }

        assertEquals(
            "a long-press-only action is reachable from a hardware keyboard only by holding Enter for " +
                "longPressTimeoutMillis, and WCAG 2.1.1 rules out requiring a specific keystroke timing — give " +
                "the site a visible IconButton, a customActions entry, or a real onClick whose work is a " +
                "subset of the long-press's (see this test's prose for which fits what)",
            emptyList<String>(),
            timedOnly,
        )
        assertEquals(
            "a site exempted here routes to its untimed action somewhere this scan cannot follow, so the " +
                "reason has to be readable at the site — put a comment naming $TEST_NAME beside it saying " +
                "where the untimed route actually is",
            emptyList<String>(),
            silent.sorted(),
        )
        // An exemption that no longer describes anything is worse than none: it is a licence, sitting in a map,
        // for the next long-press added to that same function. So each must still be earning its keep.
        assertEquals(
            "a timedExemptions entry no longer matches the tree — if the site grew a visible control or the " +
                "gesture went away, delete the entry rather than leaving it to cover the next one",
            timedExemptions,
            claimed.toSortedMap().toMap(),
        )
        // A floor, because the assertion above passes on an empty scan: if the regex stops matching, or a
        // refactor moves the gestures to a package this walk does not reach, the loop finds nothing and the
        // whole invariant reads green while every long-press in the app is timed-only again.
        assertTrue(
            "only $sites long-press sites found under ui/ — the combinedClickable scan has stopped matching, " +
                "so this test is now asserting over nothing",
            sites >= 8,
        )
    }

    /**
     * No gesture in the tree activates to nothing.
     *
     * `onClick = {}` inside a `clickable` is a control that is tab-reachable, that reports itself to TalkBack
     * as clickable, that draws a ripple when tapped — and that does nothing at all. Two sites had it, both as
     * placeholders for an action that only long-press could reach: `ChatHomeScreen.ContactRow` and the chat
     * bubble in `WorkspaceScreens.kt`. #74 closed both, from opposite directions — the bubble's tap became a
     * real action, and the contact row stopped being a control and became a row with a button on it.
     *
     * **The match is anchored inside the gesture's own argument list**, walked from the modifier call with
     * [closingParen], and that anchor is the whole reason this asserts an empty list with no exemptions.
     * Searching the file for `onClick = {}` finds one more site — `TasksBoardScreen`'s drag clone, which passes
     * `onClick = {}` as a *parameter* to the row composable it renders under the user's finger, deliberately,
     * because a floating copy of a card must not be tappable while the real one underneath it is. That is
     * correct code, and a scanner that reported it would have had to be given an exemption to stay quiet —
     * which would then have licensed a real no-op appearing in the same function.
     */
    @Test
    fun `no gesture activates to nothing`() {
        val inert = mutableListOf<String>()

        for ((leaf, text) in uiSources()) {
            val code = blankComments(text)
            val declarations = functionDeclarations(code)

            for (match in Regex("""\.(?:combinedClickable|clickable)\s*\(""").findAll(code)) {
                val open = code.indexOf('(', match.range.first)
                val arguments = code.substring(open, closingParen(code, open) + 1)
                if (!Regex("""\bonClick\s*=\s*\{\s*}""").containsMatchIn(arguments)) continue
                val region = regionOf(declarations, match.range.first, code.length)
                val line = code.substring(0, match.range.first).count { it == '\n' } + 1
                inert += "$leaf:$line ${region.name} is clickable with an empty onClick"
            }
        }

        assertEquals(
            "a clickable whose onClick is empty is a focus stop that does nothing when activated — either " +
                "give it the action a tap should perform, or stop making it a control (detectTapGestures plus " +
                "a semantics onLongClick keeps a touch shortcut without claiming to be clickable)",
            emptyList<String>(),
            inert,
        )
    }

    /**
     * The functions that draw a raw `outline` stroke deliberately, and how many each owns.
     *
     * Keyed by `file#function` with a site count, so an exemption licenses a *known* number of strokes rather
     * than the function. Adding a sixth grid line to `TaskDatabaseTable` fails this test even though the
     * function is already exempt, which is the point: the exemption is for the five strokes someone measured.
     *
     * Two populations, and the distinction is worth keeping visible. The **borders** are grid rulings — a cell
     * bordered on all four sides by neighbours, where what a reader sees is the rule between two cells. The
     * **hairlines** are `background` on a chain 2 dp or less across: a divider, or a spine linking a row's dot
     * to the next one. Neither encloses anything.
     *
     * `SpreadsheetHostScreen.GridCell` is the clearest case for why this list is not just about density. Its
     * fill is whatever `format.fillColor` the *user* set, so the backdrop this stroke sits on is unknowable at
     * composition — there is no colour to solve against, and inventing one would measure against a pixel the
     * renderer never produces.
     */
    private val rawEdgeExemptions = sortedMapOf(
        // Grid rulings: shared edges, where the stroke is a rule and not a boundary.
        "editor/spreadsheet/SpreadsheetHostScreen.kt#GridCell" to 1,
        "editor/spreadsheet/SpreadsheetHostScreen.kt#GridHeader" to 1,
        "screens/PlanningScreens.kt#DayTimeline" to 1,
        "screens/PlanningScreens.kt#WeekPanel" to 1,
        "tasks/TasksBoardScreen.kt#TaskDatabaseTable" to 5,
        "tasks/TasksBoardScreen.kt#TaskTableDataCell" to 1,
        "tasks/TasksBoardScreen.kt#TaskTableHeaderCell" to 1,
        "tasks/TasksBoardScreen.kt#TaskTimelineView" to 3,
        // Hairlines: a divider and three connectors, each 2 dp or less across.
        "components/SettingsKit.kt#RowDivider" to 1,
        "screens/SettingsScreen.kt#SyncChainConnector" to 1,
        "screens/WorkspaceHubScreens.kt#ActivityTimelineRow" to 1,
        "screens/WorkspaceHubScreens.kt#DatabaseTimelineRow" to 1,
    )

    /**
     * The functions that paint a meaningful mark in an outline token deliberately, and how many each owns.
     *
     * Empty, unlike [rawEdgeExemptions], and the emptiness is the finding rather than an omission. All eight
     * sites the scan above found were fixed — a mark either carries meaning, in which case it has to be visible,
     * or it does not, in which case it should not be drawn. Neither branch produces an exemption, which is the
     * difference between this population and the grid rulings: a rule between two cells genuinely is not a
     * boundary, but there is no equivalent argument for a status dot nobody can see.
     *
     * Kept as a declared empty map rather than dropped so that the third assertion in the test above still has
     * something to compare against, and so the next site that wants an exemption has an obvious place to state
     * its measurement.
     */
    private val glyphExemptions = sortedMapOf<String, Int>()

    private val TEST_NAME = "ChromeGeometryTest"

    /** One `border`/`background` site: where it is, which token it names, and whether it derives. */
    private data class EdgeSite(val index: Int, val token: String, val derived: Boolean)

    /** A function's name and the source range it spans, from its declaration to the end of its body. */
    private data class Region(val name: String, val start: Int, val end: Int)

    /**
     * Every Kotlin file under `ui/`, as `path-relative-to-ui` to text.
     *
     * Walked rather than listed, unlike [chromeCallerSources]. The two are answering different questions: that
     * one asserts over the twelve files that *call* the substrate, where a thirteenth is a deliberate decision;
     * this one asserts an absence across the whole tree, and an absence proved over a hand-written list is only
     * an absence from that list. The defect this scan found — `FlowDropMarker` — is in a file
     * [chromeCallerSources] does not name.
     */
    private fun uiSources(): List<Pair<String, String>> {
        val relative = "src/main/java/com/norfold/app/ui"
        val candidates = listOf(File(relative), File("apps/android/$relative"))
        val root = candidates.firstOrNull(File::isDirectory) ?: error("$relative not found")
        return root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .map { it.relativeTo(root).path.replace(File.separatorChar, '/') to it.readText() }
            .sortedBy { it.first }
            .toList()
    }

    /**
     * The same source with every comment and string literal blanked to spaces, newlines kept.
     *
     * Blanked rather than removed, because the offsets have to survive: this text is scanned for call sites and
     * the *original* is then read for the prose at the same coordinates. `HistoryBoundaryTest.codeOnly` deletes,
     * which is right for a scan that only asks whether a name appears anywhere, and wrong here.
     *
     * String literals go too, and that is not fussiness — a UI string mentioning a border would otherwise be
     * read as a call, and one containing an unbalanced parenthesis would derail the balancing below.
     */
    private fun blankComments(source: String): String {
        val out = source.toCharArray()
        var index = 0
        while (index < out.size) {
            when {
                source.startsWith("//", index) -> {
                    val stop = source.indexOf('\n', index).let { if (it < 0) out.size else it }
                    for (at in index until stop) out[at] = ' '
                    index = stop
                }
                source.startsWith("/*", index) -> {
                    val stop = source.indexOf("*/", index + 2).let { if (it < 0) out.size else it + 2 }
                    for (at in index until stop) if (out[at] != '\n') out[at] = ' '
                    index = stop
                }
                source.startsWith("\"\"\"", index) -> {
                    val stop = source.indexOf("\"\"\"", index + 3).let { if (it < 0) out.size else it + 3 }
                    for (at in index until stop) if (out[at] != '\n') out[at] = ' '
                    index = stop
                }
                out[index] == '"' || out[index] == '\'' -> {
                    val quote = out[index]
                    var at = index + 1
                    while (at < out.size && out[at] != quote && out[at] != '\n') {
                        at += if (out[at] == '\\') 2 else 1
                    }
                    for (blank in index..minOf(at, out.size - 1)) if (out[blank] != '\n') out[blank] = ' '
                    index = at + 1
                }
                else -> index++
            }
        }
        return String(out)
    }

    /** The index of the `)` that closes the `(` at [open]. */
    private fun closingParen(source: String, open: Int): Int {
        var depth = 0
        var index = open
        while (index < source.length) {
            when (source[index]) {
                '(' -> depth++
                ')' -> {
                    depth--
                    if (depth == 0) return index
                }
            }
            index++
        }
        return source.length - 1
    }

    /**
     * The text of one named argument's value, stopping at the comma that ends it rather than at the first
     * comma it contains.
     *
     * A lambda argument is full of commas — `onClick = { a(x, y); b() },` — so a naive split at the first one
     * truncates the value halfway through. Depth tracking is what makes the difference, and the depth starts
     * at zero *inside* the value, so the closing paren of the enclosing argument list also ends it.
     */
    private fun argumentValue(arguments: String, name: String): String {
        val match = Regex("""\b$name\s*=\s*""").find(arguments) ?: return ""
        var index = match.range.last + 1
        val start = index
        var depth = 0
        while (index < arguments.length) {
            val character = arguments[index]
            when {
                character == '(' || character == '{' || character == '[' -> depth++
                character == ')' || character == '}' || character == ']' -> {
                    if (depth == 0) return arguments.substring(start, index)
                    depth--
                }
                character == ',' && depth == 0 -> return arguments.substring(start, index)
            }
            index++
        }
        return arguments.substring(start, index)
    }

    /**
     * The function calls one handler makes, which stands in for what it *does*.
     *
     * Only lowercase-initial names, so a `Composable` invocation or a constructor is not mistaken for work —
     * and `performHapticFeedback` is excluded because it is the long-press's own confirmation buzz. Counting
     * it would mean no tap could ever be a subset of a hold, since taps do not buzz.
     */
    private fun callsWithin(handler: String): Set<String> =
        Regex("""\b([a-z]\w*)\s*\(""").findAll(handler)
            .map { it.groupValues[1] }
            .filterNot { it == "performHapticFeedback" }
            .toSet()

    /**
     * Every `fun` declaration in the file, with the range it actually spans.
     *
     * Structural rather than positional, and that is the correction. This used to be a flat list of offsets
     * walked linearly, so a function's region ran from its own `fun` to the *next* `fun` anywhere in the file —
     * which is right for top-level declarations and wrong the moment one nests. `NotesHomeScreen.NoteCard`
     * declares a local `fun gestureLabel`, and under the linear rule every site after it in the 250-line
     * composable was attributed to `gestureLabel`: a seven-line `when` was reported as owning a
     * `combinedClickable` it does not contain. `WorkspaceScreens.ChatBubble` had the mirror-image problem and
     * three of its sites landed on `<top-level>`.
     *
     * The spans are found in three steps, and each exists because a simpler version failed on this tree:
     *
     * 1. **The parameter list is matched first.** Searching for the first `{` after `fun name` finds a lambda
     *    *default* — `onLongPress: (ChatMessageItem) -> Unit = {}` — and brace-matches a two-character empty
     *    body. Three `WorkspaceScreens.kt` composables have such a default before their body opens.
     * 2. **After the parameter list, the first structural token decides the body.** `{` opens a block; `=`
     *    opens an expression body; a following `fun`, or the end of file, means there is no body at all
     *    (an `expect`/interface declaration). A return type may itself carry parens or angle brackets —
     *    `): Pair<Set<String>, Set<String>> {` — so those are skipped as groups rather than scanned through.
     * 3. **An expression body ends by depth *and* indentation.** `= with(NorfoldChrome) { … }` spans lines, so
     *    a newline only ends it at paren/brace depth zero and when the next non-blank line is indented no
     *    deeper than the declaration. Without the depth test, `NorfoldChrome.focusableChrome`'s single-line
     *    delegating overload swallowed the whole of `focusablePill` below it.
     *
     * [regionOf] then picks the *innermost* span containing a site, so a nested declaration owns only itself
     * and its enclosing composable keeps everything else. Measured on this tree: the ring scan reproduces its
     * eight functions and nine sites exactly, and all nine long-press sites attribute to the composable a
     * reader would name.
     */
    private fun functionDeclarations(code: String): List<Region> {
        val spans = mutableListOf<Region>()
        for (match in Regex("""\bfun\s+(?:<[^>]*>\s*)?(?:[\w.]+\.)?(\w+)\s*[(<]""").findAll(code)) {
            val name = match.groupValues[1]
            val declaration = match.range.first
            val lineStart = code.lastIndexOf('\n', declaration) + 1
            val indent = code.substring(lineStart).takeWhile { it == ' ' }.length
            // The regex ends on `(` or on a generic's `<`; only the former is the parameter list.
            var at = match.range.last
            if (code[at] == '<') {
                at = matchingGroup(code, at, '<', '>') + 1
                while (at < code.length && code[at] != '(') at++
            }
            if (at >= code.length || code[at] != '(') {
                spans += Region(name, declaration, declaration)
                continue
            }
            at = matchingGroup(code, at, '(', ')') + 1
            var end: Int? = null
            while (at < code.length) {
                when {
                    code[at] == '(' -> at = matchingGroup(code, at, '(', ')') + 1
                    code[at] == '<' -> at = matchingGroup(code, at, '<', '>') + 1
                    code[at] == '{' -> {
                        end = matchingGroup(code, at, '{', '}') + 1
                        break
                    }
                    code[at] == '=' && code.getOrNull(at + 1) != '=' -> {
                        end = expressionBodyEnd(code, at, indent)
                        break
                    }
                    code.startsWith("fun", at) && (at == 0 || !code[at - 1].isLetterOrDigit()) -> break
                    else -> at++
                }
            }
            spans += Region(name, declaration, end ?: declaration)
        }
        return spans
    }

    /** The index of the [closer] that closes the [opener] at [open]. */
    private fun matchingGroup(code: String, open: Int, opener: Char, closer: Char): Int {
        var depth = 0
        var index = open
        while (index < code.length) {
            when (code[index]) {
                opener -> depth++
                closer -> {
                    depth--
                    if (depth == 0) return index
                }
            }
            index++
        }
        return code.length - 1
    }

    /**
     * Where an expression body (`fun f() = …`) ends, given the declaration's own [indent].
     *
     * Both tests matter. Depth alone would end `= with(X) {` at the first newline inside the lambda;
     * indentation alone would run a one-line body on into every deeper-indented line that follows it, which is
     * the whole of the next declaration when that declaration is itself indented inside a class.
     */
    private fun expressionBodyEnd(code: String, equals: Int, indent: Int): Int {
        var depth = 0
        var index = equals
        while (index < code.length) {
            when (code[index]) {
                '(', '[', '{' -> depth++
                ')', ']', '}' -> depth--
                '\n' -> if (depth <= 0) {
                    var scan = index + 1
                    var ended = true
                    while (scan < code.length) {
                        val lineEnd = code.indexOf('\n', scan).let { if (it < 0) code.length else it }
                        val line = code.substring(scan, lineEnd)
                        if (line.isNotBlank()) {
                            ended = line.takeWhile { it == ' ' }.length <= indent
                            break
                        }
                        scan = lineEnd + 1
                    }
                    if (ended) return index
                }
            }
            index++
        }
        return code.length
    }

    /**
     * Which function owns [position]: the **innermost** declared span containing it.
     *
     * Innermost rather than nearest-preceding, so a local `fun` owns only its own body — see
     * [functionDeclarations] for the two mis-attributions the nearest-preceding rule produced on this tree.
     */
    private fun regionOf(declarations: List<Region>, position: Int, length: Int): Region {
        var best: Region? = null
        for (span in declarations) {
            if (position < span.start || position >= span.end) continue
            if (best == null || (span.end - span.start) < (best.end - best.start)) best = span
        }
        return best ?: Region("<top-level>", 0, length)
    }

    /**
     * The offset where [declaration]'s documentation begins.
     *
     * Walks back line by line over annotations and comment lines. Line-by-line rather than by pattern because a
     * KDoc's interior lines start with `*`, its opening line with the block-comment marker and an annotation with
     * `@`, and only their position relative to the declaration says they belong to it.
     */
    private fun documentedFrom(text: String, declaration: Int): Int {
        var start = text.lastIndexOf('\n', declaration) + 1
        while (start > 0) {
            val previous = text.lastIndexOf('\n', start - 2) + 1
            val line = text.substring(previous, start - 1).trim()
            val documents = line.startsWith("//") || line.startsWith("*") ||
                line.startsWith("/*") || line.startsWith("@") || line.endsWith("*/")
            if (!documents) return start
            start = previous
        }
        return start
    }

    /**
     * The local `val`s that stand in for an edge colour, split by whether they derive one.
     *
     * Both directions matter and for opposite reasons: a raw alias hides sites from the scan, and a derived
     * alias makes routed sites look raw. The initialiser is read to the end of its indented continuation rather
     * than to the end of its line, because a derivation is quite often on the second line of an `if`.
     */
    private fun edgeAliases(code: String): Pair<Set<String>, Set<String>> {
        val derived = mutableSetOf<String>()
        val raw = mutableSetOf<String>()
        for (match in Regex("""\bval\s+(\w+)(?:\s*:\s*\w+)?\s*=""").findAll(code)) {
            val initialiser = initialiserOf(code, match.range.first, match.range.last + 1)
            when {
                DERIVED_EDGE.containsMatchIn(initialiser) -> derived += match.groupValues[1]
                initialiser.contains("colorScheme.outline") -> raw += match.groupValues[1]
            }
        }
        return derived to raw
    }

    /** A `val` initialiser: its own line, plus every following line indented deeper than the `val`. */
    private fun initialiserOf(code: String, declaration: Int, from: Int): String {
        val lineStart = code.lastIndexOf('\n', declaration) + 1
        val indent = code.substring(lineStart).takeWhile { it == ' ' }.length
        var end = code.indexOf('\n', from).let { if (it < 0) code.length else it }
        while (end < code.length) {
            val next = code.indexOf('\n', end + 1).let { if (it < 0) code.length else it }
            val line = code.substring(end + 1, next)
            if (line.isBlank()) break
            if (line.takeWhile { it == ' ' }.length <= indent) break
            end = next
        }
        return code.substring(declaration, end)
    }

    /**
     * Every border and hairline site in the file, classified.
     *
     * `.border(...)` and `BorderStroke(...)` are boundaries by construction. A `.background(...)` is only a
     * *line* when its chain carries a `width`/`height` of 2 dp or less — that is the #62/#79 boundary, and it is
     * a real one: `outline` filling a 6 dp dot is not a faint edge, it is ink standing in for a glyph, which is
     * #79's business and needs a different ratio. The dimension is searched from the nearest preceding
     * `Modifier` so a neighbouring composable's size cannot be read as this one's.
     */
    private fun edgeSites(code: String, aliases: Pair<Set<String>, Set<String>>): List<EdgeSite> {
        val (derivedAliases, rawAliases) = aliases
        fun names(candidates: Set<String>, arguments: String) =
            candidates.any { Regex("""\b$it\b""").containsMatchIn(arguments) }

        val sites = mutableListOf<EdgeSite>()
        var consumed = -1
        for (match in Regex("""(?:\.border|\bBorderStroke)\s*\(""").findAll(code)) {
            // A nested match sits inside a call already counted: `.border(BorderStroke(w, c))` is one site.
            if (match.range.first < consumed) continue
            val open = code.lastIndexOf('(', match.range.last)
            consumed = closingParen(code, open)
            val arguments = code.substring(open + 1, consumed)
            val token = if (arguments.contains("outlineVariant")) "outlineVariant" else "outline"
            when {
                DERIVED_EDGE.containsMatchIn(arguments) || names(derivedAliases, arguments) ->
                    sites += EdgeSite(match.range.first, token, derived = true)
                arguments.contains("colorScheme.outline") || names(rawAliases, arguments) ->
                    sites += EdgeSite(match.range.first, token, derived = false)
            }
        }
        for (match in Regex("""\.background\s*\(""").findAll(code)) {
            val open = code.lastIndexOf('(', match.range.last)
            val arguments = code.substring(open + 1, closingParen(code, open))
            if (!arguments.contains("colorScheme.outline") && !names(rawAliases, arguments)) continue
            val anchor = code.lastIndexOf("Modifier", match.range.first)
            val window = code.substring(
                if (anchor >= 0) anchor else maxOf(0, match.range.first - 320),
                match.range.first,
            )
            val thin = THIN_DIMENSION.findAll(window)
                .mapNotNull { (it.groupValues[1] + it.groupValues[2]).toFloatOrNull() }
                .toList()
            if (thin.isNotEmpty() && thin.min() <= 2f) {
                val token = if (arguments.contains("outlineVariant")) "outlineVariant" else "outline"
                sites += EdgeSite(match.range.first, token, derived = false)
            }
        }
        return sites
    }

    private val DERIVED_EDGE = Regex("""\b(?:restingEdge|restingStroke|chromeEdge|selectedEdge)\b""")

    private val THIN_DIMENSION = Regex(
        """\.(?:width|height)\(\s*(\d+(?:\.\d+)?)\.dp\s*\)|\b(?:width|height)\s*=\s*(\d+(?:\.\d+)?)\.dp""",
    )

    /**
     * The local names that stand in for a glyph colour, split by whether they derive one.
     *
     * The same two-direction problem [edgeAliases] solves, plus one it does not have. A `val` alias hides sites
     * or fakes them, exactly as it does for an edge, and the initialiser is read the same way. But a glyph fix
     * has a second shape available to it: `presenceDot` is a `@Composable fun status: String?, backdrop: Color):
     * Color` that solves once and is called twice, so the *sites* name a function rather than a `val`. Tracking
     * only `val`s reported both of its call sites as raw while the routing sat one hop away.
     *
     * A function qualifies by returning `Color` and by what its body names, and its body is the span
     * [functionDeclarations] measured for it — reused rather than restated. Only a helper whose whole job is
     * producing a colour is followed; a large composable that happens to mention `legibleGlyph` somewhere inside
     * it does not become an alias for its own name.
     *
     * A helper that derives an *edge* is neither kind, and has to be skipped explicitly rather than falling
     * through to raw. `NorfoldChrome.restingEdge` names `outlineVariant` as its own default seed, so on the
     * `colorScheme.outline` test alone it classed as a raw alias — and then every `.background(restingEdge(…))`
     * in the tree read as an invisible mark. It is the other scan's primitive, and [DERIVED_EDGE] is already the
     * name for that population.
     */
    private fun glyphAliases(
        code: String,
        declarations: List<Region>,
    ): Pair<Set<String>, Set<String>> {
        val derived = mutableSetOf<String>()
        val raw = mutableSetOf<String>()

        for (match in Regex("""\bval\s+(\w+)(?:\s*:\s*\w+)?\s*=""").findAll(code)) {
            val initialiser = initialiserOf(code, match.range.first, match.range.last + 1)
            when {
                DERIVED_GLYPH.containsMatchIn(initialiser) -> derived += match.groupValues[1]
                DERIVED_EDGE.containsMatchIn(initialiser) -> Unit
                initialiser.contains("colorScheme.outline") -> raw += match.groupValues[1]
            }
        }

        for (span in declarations) {
            val body = code.substring(span.start, span.end.coerceAtLeast(span.start))
            if (COLOR_RETURNING.find(body)?.range?.first != 0) continue
            when {
                DERIVED_GLYPH.containsMatchIn(body) -> derived += span.name
                DERIVED_EDGE.containsMatchIn(body) -> Unit
                body.contains("colorScheme.outline") -> raw += span.name
            }
        }

        return derived to raw
    }

    /**
     * Every site in the file that paints a mark, classified.
     *
     * Three shapes, because a mark reaches the screen three ways: an `Icon`'s `tint`, a `background` on a chain
     * *wider* than a hairline, and a `DrawScope` primitive. The `background` test is [edgeSites]'s inverted — it
     * skips anything 2 dp or less, because that is the other scan's population, and the two together cover a
     * `.background(outline)` exactly once whatever its size.
     *
     * `size` is read alongside `width`/`height`, which [THIN_DIMENSION] does not do. It does not need to: a
     * divider is stated as a `height`, so a `size` never appears on an edge. It appears on almost every mark, and
     * a 2 dp `.size(...)` would be a hairline dot rather than a glyph.
     */
    private fun glyphSites(code: String, aliases: Pair<Set<String>, Set<String>>): List<EdgeSite> {
        val (derivedAliases, rawAliases) = aliases
        fun names(candidates: Set<String>, arguments: String) =
            candidates.any { Regex("""\b$it\b""").containsMatchIn(arguments) }

        fun tokenOf(arguments: String) =
            if (arguments.contains("outlineVariant")) "outlineVariant" else "outline"

        val sites = mutableListOf<EdgeSite>()

        fun classify(index: Int, arguments: String, thinIsMine: Boolean) {
            val derived = DERIVED_GLYPH.containsMatchIn(arguments) || names(derivedAliases, arguments)
            val rawToken = arguments.contains("colorScheme.outline") || names(rawAliases, arguments)
            if (!derived && !rawToken) return
            if (!thinIsMine) {
                val anchor = code.lastIndexOf("Modifier", index)
                val window = code.substring(if (anchor >= 0) anchor else maxOf(0, index - 320), index)
                val measured = THIN_DIMENSION.findAll(window)
                    .mapNotNull { (it.groupValues[1] + it.groupValues[2]).toFloatOrNull() }
                    .toList() +
                    GLYPH_SIZE.findAll(window).mapNotNull { it.groupValues[1].toFloatOrNull() }.toList()
                if (measured.isNotEmpty() && measured.min() <= 2f) return
            }
            sites += EdgeSite(index, tokenOf(arguments), derived)
        }

        for (match in Regex("""\btint\s*=""").findAll(code)) {
            classify(match.range.first, boundedArgument(code, match.range.last + 1), thinIsMine = true)
        }
        for (match in Regex("""\.background\s*\(""").findAll(code)) {
            val open = code.lastIndexOf('(', match.range.last)
            classify(match.range.first, code.substring(open + 1, closingParen(code, open)), thinIsMine = false)
        }
        for (match in DRAW_PRIMITIVE.findAll(code)) {
            val open = code.lastIndexOf('(', match.range.last)
            classify(match.range.first, code.substring(open + 1, closingParen(code, open)), thinIsMine = true)
        }

        return sites
    }

    /**
     * One named argument's value: from [from] to the comma or closing bracket that ends it at depth zero.
     *
     * Needed because `tint = …` has no parentheses of its own to balance. Reading to end-of-line instead saw
     * `tint = if (groupSelected) {` and stopped, missing the token two lines down — the multi-line correction
     * [argumentList] documents, met again in a different shape. All three bracket kinds are counted, not just
     * parentheses, because the value that has to be read past is a `when` block.
     */
    private fun boundedArgument(code: String, from: Int): String {
        var depth = 0
        var index = from
        while (index < code.length) {
            when (code[index]) {
                '(', '{', '[' -> depth++
                ')', '}', ']' -> {
                    if (depth == 0) return code.substring(from, index)
                    depth--
                }
                ',' -> if (depth == 0) return code.substring(from, index)
            }
            index++
        }
        return code.substring(from)
    }

    private val DERIVED_GLYPH = Regex("""\blegibleGlyph\b""")

    /** A function that exists to produce a colour, and is therefore worth following as an alias. */
    private val COLOR_RETURNING =
        Regex("""\bfun\s+(?:<[^>]*>\s*)?(?:[\w.]+\.)?\w+\s*\([^)]*\)\s*:\s*Color\b""")

    /** The `DrawScope` primitives the editor paints marks with. */
    private val DRAW_PRIMITIVE =
        Regex("""\bdraw(?:RoundRect|Rect|Circle|Line|Path|Arc|Points|Oval)\s*\(""")

    /** A `.size(...)`, which states a mark's extent where an edge would state a `height`. */
    private val GLYPH_SIZE = Regex("""\.size\(\s*(?:\w+\s*=\s*)?(\d+(?:\.\d+)?)\.dp""")

    /**
     * Every row that hands its indicator a null handler puts the semantics on the row.
     *
     * Passing `null` to a `Switch`, `Checkbox` or `RadioButton` is what makes it decorative, and it is half of a
     * pair: the row must then carry the name, the role and the state, because the control no longer carries any
     * of them. Half of the pair alone is worse than neither — a decorative control with a plain `clickable` row
     * announces a name and no state, which reads to a screen reader as an ordinary button and gives no way to
     * tell an enabled setting from a disabled one.
     *
     * The check accepts a hand-rolled `toggleable`/`selectable` as readily as the substrate modifier. Four rows
     * are deliberately hand-rolled — `SettingsToggleRow`, the palette tile, the accent swatch, `DocumentTodoMarker`
     * — and this invariant is about the semantics, not about which composable supplies them.
     *
     * A `stateDescription` counts too, and that is the answer for a row with no interaction to offer. The task
     * table's checklist preview is read-only: making its rows toggleable would announce an action the cell does
     * not have. What it owed was the state, which a null-handled `Checkbox` no longer reports — so it says the
     * state directly and this test accepts that as carrying the half that was missing.
     *
     * Positional nulls are why the search is for the control rather than for `onCheckedChange = null`. Several of
     * these rows write `RowSwitch(checked, null)` with no parameter name at all, and a pattern keyed on the name
     * reports them as compliant while seeing nothing.
     */
    @Test
    fun `every decorative selection control sits on a row that carries its semantics`() {
        val decorative = Regex(
            """(?:RowSwitch|Switch|Checkbox|RadioButton)\(\s*(?:checked\s*=\s*|selected\s*=\s*)?[^(){}]*?,\s*""" +
                """(?:onCheckedChange\s*=\s*|onClick\s*=\s*)?null\s*[,)]""",
        )
        val carrier = Regex(
            """\.(toggleableChrome|selectableChrome|toggleable|selectable)\(|stateDescription\s*=""",
        )
        val offenders = mutableListOf<String>()
        var rows = 0

        for ((path, source) in chromeCallerSources()) {
            for (match in decorative.findAll(source)) {
                rows++
                // The row's modifier chain is written above the control it contains, so the window looks back.
                val window = source.substring(maxOf(0, match.range.first - 1400), match.range.first)
                if (!carrier.containsMatchIn(window)) {
                    val line = source.substring(0, match.range.first).count { it == '\n' } + 1
                    offenders += "$path:$line a decorative control whose row carries neither a toggle nor a state"
                }
            }
        }

        assertEquals(
            "a control passed null stops reporting its own role and state, so a row that does not carry them " +
                "leaves the setting announced as a nameless button",
            emptyList<String>(),
            offenders,
        )
        // A floor, not a count: these rows are expected to keep arriving, and the assertion above is vacuously
        // green on an empty list if the pattern ever stops matching a control declaration.
        assertTrue(
            "only $rows decorative controls matched — the pattern has stopped finding them, so the check above " +
                "is passing over nothing",
            rows >= 12,
        )
    }

    /**
     * `SettingsToggleRow` stays unclipped, and that is a decision rather than an oversight.
     *
     * It is the one selection row that must not be routed through the substrate, because the substrate clips. Its
     * rows stack flush inside `SettingsGroup`, which clips the *group* at 20 dp: the middle rows are square and
     * only the first and last are rounded, following the group's radius rather than any radius of their own.
     * Clipping each row at `Corner.Card` would round four corners the user sees as straight, on every row of
     * every Settings group in the app. The group's own clip already bounds the ripple, so there is nothing for a
     * per-row clip to fix.
     *
     * Pinned because the pressure runs the other way. Every other row in these files now reads as one modifier,
     * and this one reads as six — so it looks like the conversion someone forgot, and the reason it was not
     * converted lives only in a comment.
     */
    @Test
    fun `the grouped settings toggle row is not routed through the clipping substrate`() {
        val body = settingsKitSource()
            .substringAfter("fun SettingsToggleRow(")
            .substringBefore("\n}\n")

        assertTrue(
            "the anchors no longer bound SettingsToggleRow's body, so every assertion below is vacuous",
            body.length in 300..2000 && body.contains("RowSwitch("),
        )
        assertFalse(
            "SettingsToggleRow must not use toggleableChrome: it would clip each row at Corner.Card, rounding " +
                "the flush edges every middle row of a SettingsGroup draws straight",
            body.contains("toggleableChrome"),
        )
        assertFalse(
            "SettingsToggleRow must not clip either — SettingsGroup clips the group, and a per-row clip rounds " +
                "corners the group deliberately leaves square",
            Regex("""\.clip\(""").containsMatchIn(body),
        )
        assertTrue(
            "it must still be toggleable, or the row stops carrying the name, role and state its switch gave up",
            body.contains(".toggleable("),
        )
        assertTrue(
            "and it must still ring inward, because SettingsGroup would clip an outward ring away on the first " +
                "and last row of every group",
            body.contains("ringInward") || body.contains("ring("),
        )
    }

    /** The files holding every chrome call site these invariants are asserted over. */
    private fun chromeCallerSources(): List<Pair<String, String>> =
        listOf(
            "ui/components/ChartBuilderSheet.kt",
            "ui/components/SettingsKit.kt",
            "ui/components/NorfoldComponents.kt",
            "ui/screens/SettingsScreen.kt",
            "ui/screens/WorkspaceScreens.kt",
            "ui/screens/WorkspaceVisualDialog.kt",
            "ui/screens/OnboardingScreen.kt",
            "ui/tasks/TasksBoardScreen.kt",
            "ui/editor/chart/ChartStudioSheet.kt",
            "ui/editor/image/ImageFocusSheet.kt",
            "ui/editor/shape/ShapeBuilderSheet.kt",
            "ui/components/MathBuilderSheet.kt",
        ).map { leaf ->
            val relative = "src/main/java/com/norfold/app/$leaf"
            val candidates = listOf(File(relative), File("apps/android/$relative"))
            leaf to (candidates.firstOrNull(File::isFile) ?: error("$relative not found")).readText()
        }

    /** The grouped-settings design system, for the one row that deliberately opts out of the substrate. */
    private fun settingsKitSource(): String {
        val relative = "src/main/java/com/norfold/app/ui/components/SettingsKit.kt"
        val candidates = listOf(File(relative), File("apps/android/$relative"))
        return (candidates.firstOrNull(File::isFile) ?: error("SettingsKit.kt not found")).readText()
    }

    /**
     * The ten accents as `NorfoldTheme` actually hands them to a dark-mode focus ring.
     *
     * `darkScheme` sets `primary = accent.mix(White, 0.35f)`, so the colour the substrate solves from on a dark
     * page is never the raw palette entry — a graphite accent would vanish on `#000000` without the lift.
     *
     * **These are measured outputs, not a formula, because the formula is not the one it looks like.**
     * `NorfoldTheme.mix` is `androidx.compose.ui.graphics.lerp`, which converts both endpoints to **Oklab**,
     * interpolates there, and converts back. It is not a per-channel sRGB mix, and the difference is not
     * academic: writing `red + (255 - red) * 0.35f` by hand puts Graphite at `#757575`, a lighter grey than the
     * shipped colour `#6D6D6D`, whose real floor is **3.62:1**. Oklab also leaves the sRGB gamut and clips on the way back — Sunset's
     * red channel saturates at `FF` — which no channel-wise mix reproduces at all.
     *
     * Hard-coded, but re-derivable here: `androidx.compose.ui.graphics.lerp` resolves on this unit-test
     * classpath (it rides in on `ui-graphics`), so a throwaway probe that lerps each entry toward `White` at
     * `0.35f` and reads it back through `Color.toArgb` regenerates this table exactly. Do that — do not
     * hand-compute an sRGB mix, which lands on the wrong grey (see above). The cost of hard-coding is that a
     * change to either the palette or the 0.35f lift does not *automatically* reach this test; the entry for
     * Graphite already went stale once when the accent moved to `#2A2A2A` and had to be re-probed to `#6D6D6D`.
     */
    private val liftedAccents = listOf(
        0x81A983, 0xC08FD5, 0xFF9AA7, 0x7AABF1, 0x7EC0A9,
        0xF4897A, 0xCD9ECE, 0x85B5C1, 0xEDB47A, 0x6D6D6D,
    )

    /**
     * A dark-mode measurement must lift the accent the way the theme does, or it manufactures failures.
     *
     * This is pinned because feeding the raw accent into a dark measurement — the raw palette entry *and* the
     * page as the backdrop, both mistakes at once — manufactures a false failure, and a false failure is more
     * expensive than a missed one because someone fixes it.
     *
     * Both directions are asserted: the lift must change the answer (or it would not be worth stating) and the
     * lifted figure must be the passing one. Note that the raw accent *also* clears 3:1 once it is at least
     * measured against the right backdrop — because the solver searches until it does. The claim here is not
     * that one input fails and the other passes; it is that the two are not interchangeable. The raw accent
     * floors at Graphite 3.07:1 and the lifted primary at 3.62:1, so 0.55 of ratio separates them.
     */
    @Test
    fun `a dark mode ring must be solved from the lifted primary, not the raw accent`() {
        val darkCard = hex(0x121212)
        var worstRaw = Double.MAX_VALUE
        var worstLifted = Double.MAX_VALUE
        var rawFailures = 0

        for ((accent, liftedAccent) in shippedAccents.zip(liftedAccents)) {
            val raw = hex(accent)
            val lifted = hex(liftedAccent)
            val fromRaw = ContrastAnalysis.ratio(
                ContrastAnalysis.legibleChrome(raw, darkCard, ChromeGeometry.FOCUS_RING_RATIO), darkCard,
            )
            val fromLifted = ContrastAnalysis.ratio(
                ContrastAnalysis.legibleChrome(lifted, darkCard, ChromeGeometry.FOCUS_RING_RATIO), darkCard,
            )
            if (fromRaw < ChromeGeometry.FOCUS_RING_RATIO) rawFailures++
            worstRaw = minOf(worstRaw, fromRaw)
            worstLifted = minOf(worstLifted, fromLifted)

            assertTrue(
                "the lift must brighten every accent, and %s did not".format(raw.toHex()),
                lifted.relativeLuminance() > raw.relativeLuminance(),
            )
        }

        // Both clear the bar on their own terms — the solver searches until it does. The claim is about which
        // input is the *right* one, so what is asserted is that the two are not interchangeable.
        assertEquals("solving from the raw accent still clears the bar arithmetically", 0, rawFailures)
        assertEquals("the raw accent floors at Graphite 3.07:1", 3.07, worstRaw, 0.01)
        assertEquals(
            "the lifted primary floors at Graphite 3.62:1 — the Oklab figure for #6D6D6D, not what a per-channel " +
                "mix to #757575 would predict",
            3.62,
            worstLifted,
            0.01,
        )
        assertTrue(
            "the lifted primary must not measure identically to the raw accent, or this distinction is not " +
                "worth documenting",
            abs(worstLifted - worstRaw) > 0.001,
        )
    }

    /**
     * Every backdrop parameter in `NorfoldChrome` defaults to the sentinel, not to a theme colour.
     *
     * This is a source-level assertion, which is unusual here and is the only kind available: `resolvedBackdrop`
     * is `private` and `@Composable`, so no unit test can observe which branch it takes. The manifest tests in
     * this module establish the pattern — some invariants are not reachable from the arithmetic.
     *
     * The defect it pins is a real one, and it was silent. `resolvedBackdrop` tells "the caller named a backdrop"
     * apart from "the caller named nothing" by asking whether the value `isSpecified`, and falls back to the
     * ambient [com.norfold.app.ui.LocalChromeContainer] in the second case. Fourteen parameters across nine
     * entry points originally defaulted to the literal `MaterialTheme.colorScheme.background` — a perfectly
     * specified colour. So the check passed at every defaulting call site, the declared value won over the
     * container, and the container branch was **never reached by any caller that did not pass a colour
     * explicitly**, which is all but a handful of the 116 chrome sites in `ui/`.     *
     * Nothing about that fails to compile, nothing renders visibly differently in a screenshot, and the four
     * measured populations above would have been unchanged while appearing to be fixed. `Color.Unspecified` is
     * what keeps "nobody named a backdrop" answerable by the host.
     */
    @Test
    fun `no chrome entry point defaults its backdrop to a theme colour`() {
        // Every default is captured and then filtered in code, deliberately, rather than excluded by a negative
        // lookahead in the pattern. `=\s*(?!Color\.Unspecified)` looks like it works and does not: `\s*`
        // backtracks to zero width so the lookahead is applied at the space, ` Color.Unspecified` does not begin
        // with `Color.Unspecified`, and the offender list comes back full of the very value it was meant to
        // exclude. A test that passes vacuously is worse than no test, and this one exists because a silent
        // default is exactly what it is pinning.
        val defaults = Regex("""(?:backdrop|surface)\s*:\s*Color\s*=\s*([^,)\n]+)""")
            .findAll(chromeSource())
            .map { it.groupValues[1].trim() }
            .toList()

        assertEquals(
            "a backdrop default other than Color.Unspecified silently shadows LocalChromeContainer at every " +
                "call site that omits it",
            emptyList<String>(),
            defaults.filter { it != "Color.Unspecified" },
        )
        // A floor rather than an exact count, and that is a correction. This asserted `== 14` and broke the
        // moment the focus-ring work added four `surface: Color` entry points — a passing test turning red
        // because the substrate grew, which is churn and not a signal. What the number is really here to catch
        // is the regex matching *nothing*: the filter above is vacuously satisfied by an empty list, so without
        // a lower bound a rename of the `backdrop` parameter would silently retire the check. A floor does that
        // job. Growth in chrome entry points is expected and needs no assertion to approve it.
        assertTrue(
            "only ${defaults.size} defaulting parameters matched — the pattern has stopped finding the chrome " +
                "entry points, so the assertion above is passing over an empty list",
            defaults.size >= 14,
        )
    }

    /**
     * The three corrections in `resolvedBackdrop` are ordered, and the order is not a style choice.
     *
     * Container, then tonal elevation, then alpha — the order the pixels are actually painted in. Reversing the
     * last two would composite a translucent fill onto an *untinted* surface and then tint the result, which is
     * a colour the renderer never produces. Asserted from the source because the arithmetic cannot see a private
     * composable's statement order, and a future edit that reorders them would be invisible to every other test
     * in this file.
     */
    @Test
    fun `the backdrop resolution applies container then elevation then alpha`() {
        val body = chromeSource()
            .substringAfter("private fun resolvedBackdrop(")
            .substringBefore("\n    }")
        val container = body.indexOf("LocalChromeContainer.current")
        val elevation = body.indexOf("LocalAbsoluteTonalElevation.current")
        val alpha = body.indexOf("EditorChromeColors.opaque")
        assertTrue("resolvedBackdrop must read the ambient container", container >= 0)
        assertTrue("resolvedBackdrop must read the ambient tonal elevation", elevation >= 0)
        assertTrue("resolvedBackdrop must composite away any alpha", alpha >= 0)
        assertTrue(
            "the container must be resolved before the elevation tint, which is painted on top of it",
            container < elevation,
        )
        assertTrue(
            "alpha must be composited last, onto the already-tinted colour — otherwise a translucent fill " +
                "lands on a surface that is never painted",
            elevation < alpha,
        )
    }

    /** The substrate's source, for the two invariants that are not reachable from the arithmetic. */
    private fun chromeSource(): String {
        val relative = "src/main/java/com/norfold/app/ui/NorfoldChrome.kt"
        val candidates = listOf(File(relative), File("apps/android/$relative"))
        return (candidates.firstOrNull(File::isFile) ?: error("NorfoldChrome.kt not found")).readText()
    }

    // ── Text on a fill the theme does not own ────────────────────────────────────────────────────────

    /**
     * The five Material container tokens `NorfoldTheme` never sets, recovered from the shipped artifact.
     *
     * Read out of `material3` 1.4.0's `PaletteTokens` bytecode rather than transcribed from a specification,
     * because the specification's swatch names and the compiled constants are two different things and only one
     * of them is painted. Light is the `*90` step of each palette and dark the `*30` step — which is the whole
     * asymmetry the tests below turn on: the light containers are pale enough to fail against Norfold's own ink
     * and the dark ones are dark enough to clear it.
     *
     * These sit apart from [lightSurfaces] and [darkSurfaces] because those are Norfold's, derived by the theme,
     * and these are inherited. Task #67 derived the `surfaceContainer*` ramp and stopped there; every value here
     * is one the theme still leaves on Material's baseline, which is why text drawn on them is measured against
     * a hue the palette never chose.
     */
    private val baselineSecondaryContainerDark = 0x4A4458
    private val baselineErrorContainer = 0xF9DEDC
    private val baselineErrorContainerDark = 0x8C1D18
    private val baselineTertiaryContainer = 0xFFD8E4
    private val baselineTertiaryContainerDark = 0x633B48

    /** `onSurfaceVariant` and `error`, the two inks these sites name, from `NorfoldTheme`. */
    private val secondaryInk = 0x6B6B6B
    private val secondaryInkDark = 0x9A9A9A
    private val errorInk = 0xEF4D5D
    private val errorInkDark = 0xFF6471

    /**
     * One measured line of secondary text: what it is drawn on, and whether that fill is the theme's own.
     *
     * [themeOwned] is not decoration. It is the classification the whole finding rests on — every failing case
     * is a fill Material chose and every passing case is a fill Norfold chose, and asserting that correlation is
     * what distinguishes "the ink is wrong" from "the ink is right and two containers are foreign".
     */
    private data class InkCase(
        val label: String,
        val ink: ColorValue,
        val fill: ColorValue,
        val themeOwned: Boolean,
    )

    /**
     * Every text-on-chrome site [com.norfold.app.ui.NorfoldChrome.legibleInk] was added for, in both modes.
     *
     * Four rows change their fill on selection or on the keyboard cursor — a history entry, a restore point, a
     * device profile and a disabled row in the flow rail — and each is listed twice, once in each state, because
     * the defect is only legible as a *pair*: the selected state failing while the unselected state passes is
     * what shows the row became less readable for being the one the user picked. The font list's substitution
     * warning is the same shape with `error` instead of `onSurfaceVariant`. The Eisenhower matrix is the reverse
     * case — four quadrants that never change, two of them on baseline containers and two on colours the theme
     * owns, which makes the fourth quadrant the control.
     *
     * The unselected fills are each the *real* host and not `surface` by default, which took tracing to get
     * right. The history row's unselected fill is `surface`; the device profile's is `surfaceVariant` at 45%
     * over the **page**, because `DeviceSettingsProfilesCard` sits directly in `BackupImportSettings`'s `Column`
     * rather than inside a `SettingsGroup`, so no `LocalChromeContainer` is set; the flow rail's is
     * `surfaceContainerLow`, which is what `ElevatedCard` fills itself with. Composited over `surface` instead,
     * the device row measures 4.8028:1 rather than 4.6385:1 — both pass, so no verdict moves, but the figure in
     * the documentation has to be the one the pixel produces.
     */
    private fun inkCases(): List<InkCase> = buildList {
        val lightPageColor = hex(lightPage)
        val darkPageColor = hex(0x000000)
        val lightInk = hex(secondaryInk)
        val darkInk = hex(secondaryInkDark)
        val selectedLight = hex(secondaryContainer)
        val selectedDark = hex(baselineSecondaryContainerDark)

        // The four conditional rows, selected. All four paint the same baseline `secondaryContainer`, so they
        // measure identically — listed separately anyway because they are four independent call sites and a fix
        // to one is not a fix to the others.
        for (row in listOf("history row", "restore point row", "device profile row", "flow rail disabled row")) {
            add(InkCase("$row selected light", lightInk, selectedLight, themeOwned = false))
            add(InkCase("$row selected dark", darkInk, selectedDark, themeOwned = false))
        }

        // The same four rows unselected, each on its own real host.
        add(InkCase("history row resting light", lightInk, ColorValue.WHITE, themeOwned = true))
        add(InkCase("history row resting dark", darkInk, hex(0x121212), themeOwned = true))
        add(InkCase("restore point row resting light", lightInk, ColorValue.WHITE, themeOwned = true))
        add(InkCase("restore point row resting dark", darkInk, hex(0x121212), themeOwned = true))
        add(
            InkCase(
                "device profile row resting light",
                lightInk,
                hex(0xF1F1F1).withAlphaFraction(0.45f).compositedOver(lightPageColor),
                themeOwned = true,
            ),
        )
        add(
            InkCase(
                "device profile row resting dark",
                darkInk,
                hex(0x1E1E1E).withAlphaFraction(0.45f).compositedOver(darkPageColor),
                themeOwned = true,
            ),
        )
        add(InkCase("flow rail disabled row resting light", lightInk, hex(0xFBFBFB), themeOwned = true))
        add(InkCase("flow rail disabled row resting dark", darkInk, hex(0x161616), themeOwned = true))

        // The font substitution warning: the only site using `error` rather than `onSurfaceVariant`, and the
        // worst-measuring text in the audit. Its resting state is `Color.Transparent` over whatever the sheet
        // paints, so only the selected state is a fixed colour and only it is measured here.
        add(InkCase("font substitution selected light", hex(errorInk), selectedLight, themeOwned = false))
        add(InkCase("font substitution selected dark", hex(errorInkDark), selectedDark, themeOwned = false))

        // The matrix, all four quadrants in both modes. `primaryContainer` is derived from the user's accent, so
        // "Schedule" has no fixed colour to pin and is the one quadrant absent from this list.
        for ((name, light, dark) in listOf(
            Triple("matrix Do first", baselineErrorContainer, baselineErrorContainerDark),
            Triple("matrix Delegate", baselineTertiaryContainer, baselineTertiaryContainerDark),
        )) {
            val lightFill = hex(light).withAlphaFraction(0.5f).compositedOver(lightPageColor)
            val darkFill = hex(dark).withAlphaFraction(0.5f).compositedOver(darkPageColor)
            add(InkCase("$name light", lightInk, lightFill, themeOwned = false))
            add(InkCase("$name dark", darkInk, darkFill, themeOwned = false))
        }
        add(
            InkCase(
                "matrix Later light",
                lightInk,
                hex(0xF1F1F1).withAlphaFraction(0.5f).compositedOver(lightPageColor),
                themeOwned = true,
            ),
        )
        add(
            InkCase(
                "matrix Later dark",
                darkInk,
                hex(0x1E1E1E).withAlphaFraction(0.5f).compositedOver(darkPageColor),
                themeOwned = true,
            ),
        )
    }

    /**
     * **The theme's `on*` inks fail AA on exactly the fills the theme does not own, and pass on every one it
     * does.**
     *
     * This is the measured claim behind [com.norfold.app.ui.NorfoldChrome.legibleInk], and the correlation is
     * the finding — not the failures on their own. Twelve of the twenty-four cases fail, and every single one is
     * a Material baseline container; all ten theme-owned fills pass. So `onSurfaceVariant` is not a badly chosen
     * ink. It is correctly paired with the surfaces `NorfoldTheme` picked, and the four rows that fail do so by
     * *changing their fill to a foreign colour on selection* while their text keeps naming the token that was
     * right before the change.
     *
     * The two inherited fills that nonetheless pass are the dark matrix quadrants, and they are the reason this
     * finding is not mode-independent. Material's dark containers are the `*30` step of each palette, dark
     * enough for the light ink to clear; the light ones are `*90` and pale. So an inherited container is not
     * automatically a failure — which is why the assertion below is `0 theme-owned failures` rather than "all
     * inherited cases fail", and why the light and dark figures are pinned separately throughout.
     *
     * Each figure is asserted individually rather than as a floor, because the spread is the evidence:
     *
     *  - the four selected rows — **3.9043:1** light, **3.4548:1** dark, against 5.0502 / 5.0502 / 4.6385 /
     *    4.9227 light on the same rows resting. The selected state is the *worse* one, in both modes.
     *  - the font substitution warning — **2.7565:1** light, **3.2375:1** dark. Below AA, and the light figure
     *    is below even the 3:1 [ChromeGeometry.FOCUS_RING_RATIO] asks of a non-text element.
     *  - the two baseline matrix quadrants — **4.3494:1** and **4.3053:1**, light **only**. Their dark
     *    counterparts pass at 5.5662 and 5.4587.
     *  - the fourth quadrant, `surfaceVariant` at the same 50% — **4.6355:1** light, 6.9090 dark, passing. Same
     *    ink, same alpha, same page, theme-owned fill. This is the control.
     *
     * The 50% alphas are composited through [ColorValue.withAlphaFraction], which quantizes to 128/255 the way
     * Compose's 8-bit channels do. That is not pedantry: at exact 0.5 the "Later" quadrant computes 4.6385 and
     * "Delegate"'s dark fill lands one LSB away, and the substrate has already been caught once by a
     * hand-computed `fg * a + bg * (1 - a)` disagreeing with the drawn pixel on the narrowest population.
     */
    @Test
    fun `theme ink fails AA on every inherited container and passes on every theme-owned fill`() {
        val measured = mutableMapOf<String, Double>()
        var inheritedFailures = 0
        var themeOwnedCases = 0
        var ownedFailures = 0

        for (case in inkCases()) {
            val ratio = ContrastAnalysis.ratio(case.ink, case.fill)
            measured[case.label] = ratio
            if (case.themeOwned) themeOwnedCases++
            if (ratio < ChromeGeometry.BODY_TEXT_RATIO) {
                if (case.themeOwned) ownedFailures++ else inheritedFailures++
            }
        }

        assertEquals("every text-on-chrome case the substrate was added for", 24, measured.size)
        assertEquals(
            "the ten theme-owned fills, asserted so the zero below is a population that was measured rather " +
                "than a case list that stopped generating resting states",
            10,
            themeOwnedCases,
        )
        assertEquals(
            "a theme-owned fill that fails AA is a different defect from this one — the pairing would be wrong " +
                "rather than the container foreign, and it would need the palette fixed and not the ink derived",
            0,
            ownedFailures,
        )
        assertEquals("the ten inherited-container failures", 10, inheritedFailures)

        fun assertRatio(label: String, expected: Double) =
            assertEquals(label, expected, measured.getValue(label), 0.0001)

        // The four conditional rows: selected fails in both modes, resting passes in both.
        for (row in listOf("history row", "restore point row", "device profile row", "flow rail disabled row")) {
            assertRatio("$row selected light", 4.1200)
            assertRatio("$row selected dark", 3.3037)
        }
        assertRatio("history row resting light", 5.3292)
        assertRatio("history row resting dark", 6.6575)
        assertRatio("restore point row resting light", 5.3292)
        assertRatio("restore point row resting dark", 6.6575)
        assertRatio("device profile row resting light", 5.0618)
        assertRatio("device profile row resting dark", 6.8603)
        assertRatio("flow rail disabled row resting light", 5.1500)
        assertRatio("flow rail disabled row resting dark", 6.4309)

        assertRatio("font substitution selected light", 2.7565)
        assertRatio("font substitution selected dark", 3.2375)
        assertTrue(
            (
                "the substitution warning measures %.4f:1 in light mode, which is below the 3:1 a non-text " +
                    "element needs — the strongest single figure in this finding, and it must stay below it or " +
                    "the claim in EditorStyleSurface is no longer true"
                ).format(measured.getValue("font substitution selected light")),
            measured.getValue("font substitution selected light") < ChromeGeometry.FOCUS_RING_RATIO,
        )

        assertRatio("matrix Do first light", 4.7181)
        assertRatio("matrix Do first dark", 5.5941)
        assertRatio("matrix Delegate light", 4.6708)
        assertRatio("matrix Delegate dark", 5.5412)
        assertRatio("matrix Later light", 5.0180)
        assertRatio("matrix Later dark", 6.8120)
    }

    /**
     * The derivation clears AA on all twenty-four, and moves nothing on the twelve that already passed.
     *
     * Both halves matter. Clearing the twelve is the fix; leaving the other twelve bit-identical is what makes
     * the helper safe to route *every* one of these sites through rather than only the failing ones — a caller
     * cannot know which state it is in, so `legibleInk` has to be a no-op when nothing is wrong.
     * [ContrastAnalysis.suggestAccessible] returns its input unchanged when the ratio already clears, so this is
     * an assertion about that contract holding on real paint and not about a coincidence.
     *
     * The ink stays recognisably the token it came from, and both bounds are asserted: across the fixed cases
     * the hue moves at most **2.50°** and the lightness at most **19 points**, on the red that had furthest to
     * go. Hue is the one worth pinning — only lightness is searched, but reading a hue back out of an 8-bit
     * result is lossy, so "hue preserved" is a claim about the arithmetic that needs a measured bound rather
     * than an assertion of zero.
     *
     * What this cannot see: that the sites *call* the helper. Six do, one per file, and no unit test can observe
     * a `@Composable`'s colour argument — the arithmetic is pinned here and the call sites are pinned by the
     * source scan below.
     */
    @Test
    fun `deriving the ink clears AA everywhere and changes nothing that already passed`() {
        var fixed = 0
        var untouched = 0
        var worstHueShift = 0.0
        var largestLightnessMove = 0.0
        var worstAfter = Double.MAX_VALUE

        for (case in inkCases()) {
            val before = ContrastAnalysis.ratio(case.ink, case.fill)
            val derived = ContrastAnalysis.legibleChrome(case.ink, case.fill, ChromeGeometry.BODY_TEXT_RATIO)
            val after = ContrastAnalysis.ratio(derived, case.fill)

            assertTrue(
                "%s measures %.4f:1 after derivation, still short of AA".format(case.label, after),
                after >= ChromeGeometry.BODY_TEXT_RATIO,
            )
            worstAfter = minOf(worstAfter, after)

            if (before >= ChromeGeometry.BODY_TEXT_RATIO) {
                untouched++
                assertEquals(
                    (
                        "%s already cleared AA at %.4f:1, so the ink must come back untouched — a caller " +
                            "cannot know which state its row is in, so the helper has to be free when nothing " +
                            "is wrong"
                        ).format(case.label, before),
                    case.ink,
                    derived,
                )
            } else {
                fixed++
                val from = case.ink.toHsl()
                val to = derived.toHsl()
                val hueShift = abs(to.hue - from.hue).let { minOf(it, 360f - it) }
                worstHueShift = maxOf(worstHueShift, hueShift.toDouble())
                largestLightnessMove = maxOf(largestLightnessMove, abs(to.lightness - from.lightness) * 100.0)
                assertFalse(
                    (
                        "%s failed at %.4f:1 and the derivation returned the same colour — the search found " +
                            "nothing"
                        ).format(case.label, before),
                    derived == case.ink,
                )
            }
        }

        assertEquals("the ten cases the derivation has to fix", 10, fixed)
        assertEquals("the fourteen it must leave alone", 14, untouched)
        // A floor on the whole set, so a future palette cannot satisfy AA by a hair on the cases that needed
        // work. The tightest is the substitution warning's light figure, which had the furthest to travel.
        assertEquals("the tightest derived figure is the red at 4.5093:1", 4.5093, worstAfter, 0.0001)
        assertEquals(
            "only lightness is searched, but reading hue back out of an 8-bit result is lossy — the bound is " +
                "what makes 'hue preserved' checkable",
            0.16,
            worstHueShift,
            0.01,
        )
        assertEquals("the largest lightness move, on the red", 19.0, largestLightnessMove, 0.5)
    }

    /**
     * Every one of the eight sites passes its own fill rather than letting the backdrop default.
     *
     * The one mistake that would silently un-fix this. `legibleInk`'s `backdrop` defaults to
     * `Color.Unspecified`, which `resolvedBackdrop` resolves to [com.norfold.app.ui.LocalChromeContainer] — the
     * *container*, which is the colour these rows are drawn **inside** and not the colour they paint themselves.
     * A one-argument call would therefore measure the ink against the sheet or the page while the text sits on
     * `secondaryContainer`, produce a figure that looks fine, and change nothing on screen. That is the same
     * class of silent pass `no chrome entry point defaults its backdrop to a theme colour` pins for the ring,
     * and it is worth pinning separately because here the defaulting call *compiles and reads correctly*.
     *
     * Asserted from the source because the argument list of a `@Composable` call is not observable from a unit
     * test. The six files are named individually rather than globbed: a seventh file is a decision someone
     * should have to make deliberately, and this failing is the prompt to check they passed a fill.
     *
     * Eight calls across those six files, because `FlowHostScreen.FlowDropMarker` has two — its muted branch and
     * its active one solve different inks against the same fill, and both had to stop drawing their label from
     * the colour that draws the marker's edge.
     */
    @Test
    fun `every legibleInk call site names the fill it paints`() {
        val singleArgument = mutableListOf<String>()
        var calls = 0

        for (leaf in listOf(
            "ui/editor/history/HistorySurface.kt",
            "ui/editor/recovery/RecoverySurface.kt",
            "ui/editor/style/EditorStyleSurface.kt",
            "ui/editor/flow/FlowHostScreen.kt",
            "ui/screens/SettingsScreen.kt",
            "ui/tasks/TasksBoardScreen.kt",
        )) {
            val relative = "src/main/java/com/norfold/app/$leaf"
            val candidates = listOf(File(relative), File("apps/android/$relative"))
            val source = (candidates.firstOrNull(File::isFile) ?: error("$relative not found")).readText()
            var index = source.indexOf("legibleInk(")
            while (index >= 0) {
                calls++
                if (!argumentList(source, index + "legibleInk".length).contains(',')) {
                    singleArgument += "$leaf: legibleInk with no backdrop"
                }
                index = source.indexOf("legibleInk(", index + 1)
            }
        }

        assertEquals("the eight text-on-chrome sites the helper exists for", 8, calls)
        assertEquals(
            "a legibleInk call with no backdrop measures the ink against LocalChromeContainer — the surface the " +
                "row sits inside, not the one it paints — so it would report a passing figure and fix nothing",
            emptyList<String>(),
            singleArgument,
        )
    }

    /**
     * The text between a call's parentheses, with anything nested collapsed away.
     *
     * Parentheses are balanced rather than the argument list being read to the end of the line, and that is a
     * correction: one of the six calls wraps its arguments across four lines, and `substringBefore("\n    ")`
     * — a newline and an indent — cuts at the *first* newline, because every continuation line begins with more
     * than four spaces. The multi-line call would have come back empty, contained no comma, and been reported as
     * the very defect it is not. Depth-tracking is what makes "does this call have a second argument" a question
     * about the call rather than about its formatting, and dropping nested arguments is what keeps a comma
     * inside `MaterialTheme.colorScheme.error.copy(alpha = x, y)` from counting as this call's own.
     */
    private fun argumentList(source: String, openParenIndex: Int): String {
        require(source[openParenIndex] == '(') { "not a call at $openParenIndex" }
        val top = StringBuilder()
        var depth = 0
        var index = openParenIndex
        while (index < source.length) {
            val character = source[index]
            when (character) {
                '(' -> depth++
                ')' -> {
                    depth--
                    if (depth == 0) return top.toString()
                }
                else -> if (depth == 1) top.append(character)
            }
            index++
        }
        error("unbalanced parentheses from $openParenIndex")
    }

    /**
     * Whether the modifier immediately before [chromeIndex], ignoring whitespace, is a `.clip(...)`.
     *
     * Walks the parentheses backward from the `)` that precedes the chrome call rather than pattern-matching,
     * because a clip's argument can itself contain parentheses — `Color(chat.color)`, `RoundedCornerShape(r)` —
     * and a regex reading to the last `)` on the line cannot tell which call that `)` closes. Depth-counting can:
     * the `(` that brings depth back to zero is the one this `)` belongs to, and only then is it fair to ask
     * whether `.clip` sits in front of it.
     */
    private fun clipEndsAt(source: String, chromeIndex: Int): Boolean {
        var index = chromeIndex - 1
        while (index >= 0 && source[index].isWhitespace()) index--
        if (index < 0 || source[index] != ')') return false
        var depth = 0
        while (index >= 0) {
            when (source[index]) {
                ')' -> depth++
                '(' -> {
                    depth--
                    if (depth == 0) return source.startsWith(".clip(", index - ".clip".length)
                }
            }
            index--
        }
        return false
    }

    private fun hex(rgb: Int) = ColorValue.of((rgb shr 16) and 0xFF, (rgb shr 8) and 0xFF, rgb and 0xFF)
}
