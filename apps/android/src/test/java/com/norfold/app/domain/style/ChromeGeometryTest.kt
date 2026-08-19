package com.norfold.app.domain.style

import java.io.File
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
        val lightOutline = hex(0xE4E6EF)
        val lightOutlineVariant = hex(0xEEF0F5)
        val darkOutline = hex(0x252C3A)
        val darkOutlineVariant = hex(0x1B2230)
        val darkPage = hex(0x070A12)

        val measured = mapOf(
            "light outline on page" to ContrastAnalysis.ratio(lightOutline, hex(lightPage)),
            "light outlineVariant on page" to ContrastAnalysis.ratio(lightOutlineVariant, hex(lightPage)),
            "dark outline on page" to ContrastAnalysis.ratio(darkOutline, darkPage),
            "dark outlineVariant on page" to ContrastAnalysis.ratio(darkOutlineVariant, darkPage),
        )

        assertEquals("light outline", 1.1734, measured.getValue("light outline on page"), 0.0001)
        assertEquals("light outlineVariant", 1.0744, measured.getValue("light outlineVariant on page"), 0.0001)
        assertEquals("dark outline", 1.4135, measured.getValue("dark outline on page"), 0.0001)
        assertEquals("dark outlineVariant", 1.2421, measured.getValue("dark outlineVariant on page"), 0.0001)

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
     * Nine of the 50 pairs reach their minimum at alpha 0.116 rather than 0.120. That is not a counterexample —
     * the ratio there *equals* the settled value to four decimals, because 8-bit channels put both alphas on the
     * same composited pixel. It is the same quantization that made `sheet nav selected` 4 of 10 rather than 3,
     * seen from the other side: alpha resolution is coarser than the arithmetic suggests, which flattens the tail
     * of the sweep into a plateau.
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
            "dark page" to hex(0x070A12),
            "dark surface" to hex(0x0E131F),
        )
        val settledAlpha = 0.12f
        var pairs = 0

        for ((hostName, host) in hosts) {
            for (accent in shippedAccents) {
                val tint = hex(accent)
                val settled = tint.withAlphaFraction(settledAlpha).compositedOver(host)
                val ring = ContrastAnalysis.legibleChrome(tint, settled, ChromeGeometry.FOCUS_RING_RATIO)
                val settledRatio = ContrastAnalysis.ratio(ring, settled)
                var previous = Double.MAX_VALUE
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
                }
            }
        }

        assertEquals("every accent in every host the row appears on", 50, pairs)
    }

    // ── The container correction ─────────────────────────────────────────────────────────────────────

    /**
     * Material's baseline container tokens, which `NorfoldTheme` leaves unset, so these are what a dialog and a
     * modal sheet actually paint today: `surfaceContainerHigh` and `surfaceContainerLow`. `secondaryContainer` is
     * the same story and is what a selected [com.norfold.app.ui.components.NorfoldPopupRow] fills with at 55%.
     *
     * Written as literals rather than read from a theme for the reason [shippedAccents] is: no Compose harness.
     * The stronger reason here is that these are not Norfold's colours at all — they are the fallbacks a theme
     * that never set them inherits, and the point of the test is that chrome is measured against *whatever is
     * really painted*. Pinning them means the day the owner picks real container colours, these figures change
     * and the test says so, which is the notification worth having.
     */
    private val containerHigh = 0xECE6F0
    private val containerLow = 0xF7F2FA
    private val secondaryContainer = 0xE8DEF8

    /** The light page, from `NorfoldTheme` — and the colour the substrate used to measure everything against. */
    private val lightPage = 0xF7F8FC

    /**
     * The four populations `LocalChromeContainer` was added for, each one a real host in the shipped tree.
     *
     * Each case carries what the ring is drawn on, what the substrate used to solve against, and what it solves
     * against now. The last two differ in exactly the way the local fixes: a row cannot know its own host, so
     * before the local every one of these resolved to the page.
     */
    private fun containerCases(): List<ContainerCase> = buildList {
        for (accent in shippedAccents) {
            val tint = hex(accent)
            val page = hex(lightPage)

            // 1. A ChoiceRow inside NorfoldDialog. No fill of its own, so drawn == container.
            add(ContainerCase("dialog row resting", tint, hex(containerHigh), page, hex(containerHigh)))

            // 2. A resting SideNavItem inside NorfoldBottomSheet. Also no fill.
            add(ContainerCase("sheet nav resting", tint, hex(containerLow), page, hex(containerLow)))

            // 3. The same destination selected: a 12% accent pill. The row *did* pass its pill as an explicit
            //    backdrop, and it was still wrong, because the substrate composited that translucent pill onto
            //    the page rather than onto the sheet. This is the case that shows the two halves of the fix are
            //    not the same fix.
            val pillOnSheet = tint.withAlphaFraction(0.12f).compositedOver(hex(containerLow))
            val pillOnPage = tint.withAlphaFraction(0.12f).compositedOver(page)
            add(ContainerCase("sheet nav selected", tint, pillOnSheet, pillOnPage, pillOnSheet))

            // 4. A selected NorfoldPopupRow: secondaryContainer at 55% over the card, and the card is `surface`
            //    plus 3 dp of tonal tint — which on this app is a wash of the user's own accent, because
            //    NorfoldTheme never sets surfaceTint. Two corrections stacked, which is why the popup card
            //    resolves its container through EditorChromeColors.elevated rather than naming a token.
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
     * This is the arithmetic behind `LocalChromeContainer`, and both halves are asserted because the good number
     * alone would not establish that the change was load-bearing. Failures are asserted as **exact counts per
     * population** rather than as "some fail", so a change that fixes some and breaks others cannot pass:
     *
     *  - dialog row resting — **3 of 10**, worst Gold 2.6024:1
     *  - sheet nav resting — **2 of 10**, worst Gold 2.8897:1
     *  - sheet nav selected — **4 of 10**, worst Gold 2.9430:1
     *  - popup row selected — **1 of 10**, worst Sunset 2.9536:1
     *
     * Note how narrow the two selected populations are: they fall short by 0.057 and 0.046 respectively, which is
     * why they survived review for as long as they did and why the count matters more than the floor. A tolerance
     * loose enough to feel comfortable would erase both findings. The dialog row is the outlier in the other
     * direction — 0.40 short, a plainly invisible ring — and it is also the population with the most call sites.
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
        for (case in containerCases()) {
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
                "popup row selected" to 1,
            ),
            failures,
        )
        // Four decimals, not two. Candy fails this population at 2.9999964:1, which a 0.01 tolerance on a 2.96
        // claim would have accepted — and did, until this test ran. The floors are the instrument that catches a
        // model drifting from the app, so they are quoted at the precision that makes the drift visible.
        assertEquals("dialog row floor", 2.6024, worst.getValue("dialog row resting"), 0.0001)
        assertEquals("sheet nav resting floor", 2.8897, worst.getValue("sheet nav resting"), 0.0001)
        assertEquals("sheet nav selected floor", 2.9430, worst.getValue("sheet nav selected"), 0.0001)
        assertEquals("popup row selected floor", 2.9536, worst.getValue("popup row selected"), 0.0001)
    }

    /**
     * Solving against the real container clears the target in all four hosts, for all ten accents.
     *
     * The counterpart to the test above, and the reason the fix is a resolution rather than a per-host tolerance:
     * no host and no accent needs an exception. The hue is checked too, because a ring that passed by discarding
     * the user's accent would satisfy 1.4.11 and still be the wrong fix.
     *
     * **The margins split cleanly, and the split is the interesting part.** On 6 of the 10 accents the solver
     * returns the accent unchanged, because it already clears 3:1 — those pass by 0.004 (Midnight on the sheet
     * pill) up to 7.66 (Graphite on a resting sheet row, a near-black on a near-white sheet). On the 4 it does
     * move — Sunset, Forest, Candy, Gold — it never clears by more than **0.14**, because the lightness search
     * steps in hundredths and stops at the first value that satisfies the requirement. So the accents the fix
     * actually acts on all sit within a tenth of the boundary, which is why the tolerance here is one hundredth
     * and not one tenth: a looser one would stop distinguishing a solved ring from an unsolved one.
     *
     * The tightest figure in the whole set is Midnight's 3.0039:1 on the sheet's selected pill, and it passes
     * *unchanged* — the accent is natively 0.004 clear of the bar. It is the reason the sibling test's floors are
     * quoted to four decimals rather than two.
     */
    @Test
    fun `chrome solved against its real container clears the target in every host`() {
        for (case in containerCases()) {
            val ring = ContrastAnalysis.legibleChrome(
                case.accent, case.rightBackdrop, ChromeGeometry.FOCUS_RING_RATIO,
            )
            val measured = ContrastAnalysis.ratio(ring, case.drawn)
            assertTrue(
                "%s: accent %s on %s measures %.4f:1".format(
                    case.label, case.accent.toHex(), case.drawn.toHex(), measured,
                ),
                measured >= ChromeGeometry.FOCUS_RING_RATIO - 0.01,
            )

            val before = case.accent.toHsl()
            val after = ring.toHsl()
            if (!before.achromatic && !after.achromatic) {
                val drift = abs(after.hue - before.hue).let { minOf(it, 360f - it) }
                assertTrue(
                    "%s: accent %s became %s — hue moved %.2f degrees".format(
                        case.label, case.accent.toHex(), ring.toHex(), drift,
                    ),
                    drift <= 1f,
                )
            }
        }
    }

    /**
     * `surface` is not a usable stand-in for "some container", which is what the old [ChoiceRow] default assumed.
     *
     * Before the local, `ChoiceRow` defaulted its backdrop to `colorScheme.surface` — a plausible guess, since a
     * row usually sits on *something* raised. Measured, it is worse than the page default it replaced: solving
     * against white and drawing on a dialog floors at **2.49:1** where the page floors at **2.60:1**. Both fail
     * 3 of 10. The lesson pinned here is not that one literal beat another; it is that no literal can be right,
     * because the row's host is not a property of the row.
     */
    @Test
    fun `guessing surface as the container is worse than guessing the page`() {
        val dialog = hex(containerHigh)
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

        assertEquals("both guesses fail the same number of accents", 3, failFromSurface)
        assertEquals("both guesses fail the same number of accents", 3, failFromPage)
        assertEquals("the surface guess floors at 2.49:1", 2.49, worstFromSurface, 0.01)
        assertEquals("the page guess floors at 2.60:1", 2.60, worstFromPage, 0.01)
        assertTrue(
            "surface must measure worse than the page here, or the ChoiceRow default was not the regression it " +
                "is documented as",
            worstFromSurface < worstFromPage,
        )
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
        assertEquals("3 dp floor", 2.9213, floors.getValue(3f), 0.0001)
        assertEquals("4 dp floor", 2.8816, floors.getValue(4f), 0.0001)
        assertEquals("6 dp floor", 2.8442, floors.getValue(6f), 0.0001)
        assertEquals("the floor is Sunset at every elevation that fails", "#F75C7A", worstAccent)
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
     * 3.74:1 at the deepest elevation either way. Without this test the sibling tests above would read as though
     * the defect spanned both themes.
     *
     * The stronger reason to pin it: it means dark mode is passing by *margin*, not by construction. If a future
     * accent or a deeper elevation eroded that margin, the solver would start moving the colour and the two
     * paths would diverge — and this test would fail, which is exactly when someone should look.
     */
    @Test
    fun `the dialog mis-solve is invisible in dark mode because the solver moves nothing`() {
        val darkPage = hex(0x070A12)
        val darkSurface = hex(0x0E131F)
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
        assertEquals("dark floors at 3.74:1 at 6 dp, well clear of the bar", 3.7446, floor, 0.0001)
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
     * The ten accents as `NorfoldTheme` actually hands them to a dark-mode focus ring.
     *
     * `darkScheme` sets `primary = accent.mix(White, 0.35f)`, so the colour the substrate solves from on a dark
     * page is never the raw palette entry — a graphite accent would vanish on `#070A12` without the lift.
     *
     * **These are measured outputs, not a formula, because the formula is not the one it looks like.**
     * `NorfoldTheme.mix` is `androidx.compose.ui.graphics.lerp`, which converts both endpoints to **Oklab**,
     * interpolates there, and converts back. It is not a per-channel sRGB mix, and the difference is not
     * academic: writing `red + (255 - red) * 0.35f` by hand puts Graphite at `#7A7D89` and claims a **4.53:1**
     * floor, where the shipped colour is `#747785` and the real floor is **4.17:1**. That wrong figure reached a
     * KDoc before this table replaced it. Oklab also leaves the sRGB gamut and clips on the way back — Sunset's
     * red channel saturates at `FF` — which no channel-wise mix reproduces at all.
     *
     * Hard-coded for the reason [shippedAccents] is: this source set has no Compose harness, so `lerp` is not
     * callable from here. The values were taken from `androidx.compose.ui.graphics.lerp` itself, read back
     * through `Color.toArgb`, against the `ui-graphics` build the app resolves. The cost is that a change to
     * either the palette or the 0.35f lift does not automatically reach this test; the benefit is that the test
     * runs at all, and it is the same trade the rest of this file already makes.
     */
    private val liftedAccents = listOf(
        0x81A983, 0xC08FD5, 0xFF9AA7, 0x7AABF1, 0x7EC0A9,
        0xF4897A, 0xCD9ECE, 0x85B5C1, 0xEDB47A, 0x747785,
    )

    /**
     * A dark-mode measurement must lift the accent the way the theme does, or it manufactures failures.
     *
     * This is pinned because feeding the raw accent into a dark measurement produced a *false* failure during
     * this work: `SettingsRow` on the dark card measured **2.88:1** where the shipped ring is **4.17:1**. That
     * 2.88 needed both mistakes at once — the raw accent *and* the page as the backdrop — which is why it is
     * worth keeping as a figure rather than paraphrasing. A false failure is more expensive than a missed one,
     * because someone fixes it.
     *
     * Both directions are asserted: the lift must change the answer (or it would not be worth stating) and the
     * lifted figure must be the passing one. Note that the raw accent *also* clears 3:1 once it is at least
     * measured against the right backdrop — floor 3.11:1 — because the solver searches until it does. The claim
     * here is not that one input fails and the other passes; it is that the two are not interchangeable, and
     * 1.06 of ratio separates them.
     */
    @Test
    fun `a dark mode ring must be solved from the lifted primary, not the raw accent`() {
        val darkCard = hex(0x0E131F)
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
        assertEquals("the raw accent floors at Graphite 3.11:1", 3.11, worstRaw, 0.01)
        assertEquals(
            "the lifted primary floors at Graphite 4.17:1 — the Oklab figure, not the 4.53:1 a per-channel " +
                "mix would predict",
            4.17,
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
     * explicitly**, which is all but a handful of the 116 chrome sites in `ui/`.
     *
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
        assertEquals("the fourteen defaulting parameters, all on the sentinel", 14, defaults.size)
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

    private fun hex(rgb: Int) = ColorValue.of((rgb shr 16) and 0xFF, (rgb shr 8) and 0xFF, rgb and 0xFF)
}
