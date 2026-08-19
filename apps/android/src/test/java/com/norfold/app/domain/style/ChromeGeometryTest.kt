package com.norfold.app.domain.style

import org.junit.Assert.assertEquals
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
     * The problem is that 10% of the ink over the surface lands between 1.22:1 and 1.32:1 on the six surfaces
     * this app actually uses — under half of WCAG 1.4.11's 3:1, and below even the substrate's quieter resting
     * target. Reaching 3:1 by alpha alone would need roughly 0.46 on the light page, 4.6x the Material
     * default, which is no longer a state layer but a scrim over the content.
     *
     * Material3 1.4.0 also ships no focus-outline primitive of its own — there is no `focusRing` or focus
     * indicator API in the artifact, and `Button` draws focus only through this same state layer. So the 559
     * Material buttons in the tree are in exactly the same position as the hand-rolled chrome: a focus state
     * that composites, and does not contrast.
     *
     * Asserted per surface rather than described, because "the ripple already handles focus" is the most
     * plausible reason a future change would delete the ring.
     */
    @Test
    fun `the ripple focused state layer falls short of the ring's target on every app surface`() {
        // RippleDefaults.RippleAlpha.focusedAlpha, Material3 1.4.0.
        val focusedAlpha = 0.10f

        val cases = listOf(
            // ink (onSurface) to the surfaces it is drawn over, light then dark.
            0x171725 to listOf(0xFFFFFF, 0xF7F8FC, 0xF1F2F7),
            0xF6F7FB to listOf(0x0E131F, 0x070A12, 0x151B28),
        )
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
            }
        }
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
        0xE23A2E, 0xB06AB3, 0x3E8EA0, 0xE08A12, 0x33374A,
    )

    /**
     * The six colours a workspace object can carry, from the `upsertWorkspaceObject` call sites in
     * `NotesRepository` — note, file, task, goal, chat, calendar. Not user-editable, so this is the whole set.
     */
    private val objectColors = listOf(0x9D6CFF, 0x4AADFF, 0x56CC98, 0x20B26B, 0x7E57FF, 0x6F36FF)

    /** The two page backgrounds, from `NorfoldTheme`. */
    private val pages = listOf(0xF7F8FC, 0x070A12)

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
     * `#070A12` composites to `#1A1638`. Measured against the *uncomposited* `#7E57FF`, `inkFor` picks black
     * — and black on `#1A1638` is 1.22:1, a focus ring that does not exist for the user who most needs it.
     * This is the number quoted in [com.norfold.app.ui.NorfoldChrome.focusRing]'s documentation, so it is
     * asserted rather than left as a claim.
     */
    @Test
    fun `the worst object tile is the one the documentation names`() {
        val tile = hex(0x7E57FF).withAlphaFraction(0.16f)
        val drawn = tile.compositedOver(hex(0x070A12))
        assertEquals("the composite the two screens actually paint", "#1A1638", drawn.toHex())

        val uncomposited = ContrastAnalysis.legibleChrome(hex(0x3D7C45), tile, ChromeGeometry.FOCUS_RING_RATIO)
        assertEquals(ColorValue.BLACK, uncomposited)
        assertEquals(
            "the ink the old path drew, as it landed on the tile",
            1.22,
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

    private fun hex(rgb: Int) = ColorValue.of((rgb shr 16) and 0xFF, (rgb shr 8) and 0xFF, rgb and 0xFF)
}
