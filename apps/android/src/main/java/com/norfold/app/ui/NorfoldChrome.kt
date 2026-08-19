package com.norfold.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.norfold.app.domain.style.ChromeGeometry
import com.norfold.app.domain.style.ChromeGeometry.Corner
import com.norfold.app.domain.style.ChromeGeometry.Lift

/**
 * The one interaction-chrome substrate: what a floating surface in Norfold measures, and nothing about
 * what it means.
 *
 * A destructive confirmation, a segmented mode strip, a position pill, a loading placeholder and an object
 * inspector are five separate semantic primitives. They are also one rectangle. This is the rectangle. Each
 * of those primitives is expected to consume [NorfoldChromeSurface] and then say its own thing inside it —
 * what a delete costs, how a mode is named, what a skeleton stands in for. None of that appears here, and a
 * parameter that would let it appear here belongs in the primitive instead.
 *
 * **What this deliberately does not own: motion.** The substrate is described as surface, elevation, shape,
 * padding, edge, tint, focus ring *and* motion classes — and the motion half already exists and is correct.
 * `MotionSemantics` bands every duration by [com.norfold.app.editor.motion.MotionLevel] and [NorfoldMotion]
 * resolves it against the device reduce-motion preference in exactly one place. A third motion vocabulary
 * beside those two would be the drift this file exists to stop. Chrome that animates calls `NorfoldMotion`.
 *
 * **The translation boundary.** Every number here comes from [ChromeGeometry], which is domain code with
 * unit tests, and this file only turns `Float` into `Dp` and measures colour through [EditorChromeColors].
 * That split is not ceremony: the unit source set has no Compose harness, so a tokens object built out of
 * `Dp` can be documented as consistent and never asserted as consistent, which is how the tree arrived at
 * nine shadow elevations and four chrome radii in the first place. The same arrangement already holds for
 * [com.norfold.app.domain.style.ContrastAnalysis] and [EditorChromeColors].
 */
object NorfoldChrome {

    /** The corner tier as a [Shape]. */
    fun shape(corner: Corner): Shape = RoundedCornerShape(corner.radiusDp.dp)

    /** Interior padding that clears the corner tier's own curve. */
    fun padding(corner: Corner): Dp = ChromeGeometry.paddingFor(corner).dp

    /**
     * The resting edge colour for chrome drawn on [backdrop].
     *
     * Derived from the theme's own `outline`, moved in lightness only until it measures
     * [ChromeGeometry.RESTING_EDGE_RATIO] against that backdrop. Theme-following because the user's mode
     * still supplies the input; measured because the output is solved rather than picked.
     *
     * The reason it cannot simply *be* `outline`: on the shipped palette `outline` measures 1.17:1 on the
     * light page and 1.41:1 on the dark one, and `outlineVariant` measures 1.07:1 and 1.24:1. A non-text
     * boundary needs roughly 1.5:1 to be visible at all, so both tokens draw an edge that states a boundary
     * the user cannot see.
     *
     * [backdrop] is composited onto the page first, for the reason [focusRing] states at length: a
     * translucent backdrop is not a colour the measurement can use, and an edge solved against one is solved
     * against a pixel that is never painted. This matters more here than it looks, because #62 will route
     * the tree's 39 invisible `outlineVariant` borders through this function and a majority of those sites
     * sit on translucent cards.
     */
    @Composable
    @ReadOnlyComposable
    fun restingEdge(backdrop: Color = MaterialTheme.colorScheme.background): Color =
        EditorChromeColors.legible(
            MaterialTheme.colorScheme.outline,
            EditorChromeColors.opaque(backdrop, MaterialTheme.colorScheme.background),
            ChromeGeometry.RESTING_EDGE_RATIO,
        )

    /**
     * The focus-ring colour for chrome drawn on [backdrop].
     *
     * The accent, raised to [ChromeGeometry.FOCUS_RING_RATIO] — 3:1, WCAG 1.4.11 — rather than to the
     * resting edge's quieter target. A resting edge may be quiet because the user is not looking for it; a
     * focus ring is the only thing telling a keyboard or switch-access user where their next action lands.
     *
     * The ordinary case adjusts nothing, because a user accent that already contrasts with the page is
     * returned exactly as chosen.
     *
     * **[backdrop] is composited onto the page before it is measured.** A translucent backdrop is a colour
     * the arithmetic cannot use at all: [EditorChromeColors.legible] can neither measure it nor search
     * against it, so it abandons the accent and returns flat black or white chosen from a colour that is
     * never painted. 42 of the app's 88 interactive `Surface`s paint a translucent or transparent fill, and on
     * the worst of them — a workspace object tile at 16% alpha over the dark page — that ink draws at
     * **1.22:1**, with 60 of the 120 accent-by-tile cases failing 3:1. [EditorChromeColors.opaque] states the
     * arithmetic and the measured cases; `ChromeGeometryTest` pins them.
     */
    @Composable
    @ReadOnlyComposable
    fun focusRing(
        accent: Color = MaterialTheme.colorScheme.primary,
        backdrop: Color = MaterialTheme.colorScheme.background,
    ): Color = EditorChromeColors.legible(
        accent,
        EditorChromeColors.opaque(backdrop, MaterialTheme.colorScheme.background),
        ChromeGeometry.FOCUS_RING_RATIO,
    )

    /**
     * A resting edge on this modifier, at the substrate's one weight and measured colour.
     *
     * An extension rather than a factory returning a `Modifier`, because a `@Composable` function with no
     * receiver that returns a `Modifier` is what lint's `ModifierFactoryExtensionFunction` catches. Callers
     * outside this file open `with(NorfoldChrome) { ... }`, and inside that block the edge chains like every
     * other modifier.
     */
    @Composable
    @ReadOnlyComposable
    fun Modifier.chromeEdge(
        corner: Corner,
        backdrop: Color = MaterialTheme.colorScheme.background,
    ): Modifier = border(ChromeGeometry.RESTING_EDGE_WIDTH.dp, restingEdge(backdrop), shape(corner))

    /**
     * A focus ring outside the shape it decorates, concentric with it, reserving its own space whether or
     * not [focused].
     *
     * For chrome that floats — a dock, a dialog, an inspector — and therefore owns empty space on every
     * side. Chrome packed flush against its siblings or clipped by its parent cannot use this; it wants
     * [innerFocusRing], which spends no layout space at all.
     *
     * Two decisions worth stating, because both are the difference between a ring and an artefact:
     *
     * The ring sits **outside** the shape with [ChromeGeometry.FOCUS_RING_GAP] between them. Drawn flush, a
     * ring on any of the tree's 39 outlined chrome sites would read as the existing border changing weight
     * rather than as focus arriving — the user would see a slightly thicker box, not a destination.
     *
     * The space is reserved **unconditionally**, so focus does not resize or reflow the chrome it lands on.
     * A ring that pushes its own content inward by 4 dp on arrival is a layout jump at exactly the moment
     * the user needs the target to hold still, and it cascades: in a row of focusable chrome, one item
     * growing moves every sibling.
     *
     * The outer radius is the inner radius plus the inset, which is what keeps the two curves concentric
     * instead of leaving a ring that pinches at the corners.
     */
    @Composable
    @ReadOnlyComposable
    fun Modifier.focusRing(
        focused: Boolean,
        corner: Corner,
        accent: Color = MaterialTheme.colorScheme.primary,
        backdrop: Color = MaterialTheme.colorScheme.background,
    ): Modifier {
        val width = ChromeGeometry.FOCUS_RING_WIDTH.dp
        val inset = width + ChromeGeometry.FOCUS_RING_GAP.dp
        val outer = RoundedCornerShape(corner.radiusDp.dp + inset)
        return border(
            width = width,
            color = if (focused) focusRing(accent, backdrop) else Color.Transparent,
            shape = outer,
        ).padding(inset)
    }

    /**
     * A focus ring drawn *inside* the bounds of the chrome it marks, taking no layout space at all.
     *
     * **This is the one the app actually needs**, and the reason it did not exist is worth recording: the
     * substrate shipped [focusRing] — outward, space-reserving, correct for a floating panel — and it found
     * zero consumers, because a floating panel is not what Norfold's focusable population is made of. Of the
     * 138 non-editor interactive sites, 126 reach the user through four shared composables, and all four are
     * shapes with no outside to draw on: a settings row is a flush band inside a card that clips it, a
     * sidebar destination and a nav bar destination are flush-packed siblings, and a bare content card sits
     * in a grid whose spacing belongs to the grid. An outward ring on any of them is drawn where the clip
     * deletes it or on top of the neighbour.
     *
     * **What it costs in layout: nothing.** [focusRing] must reserve its inset unconditionally or focus
     * would reflow the page; this one is drawn in space the chrome already occupies, so it adds no padding,
     * changes no measurement, and cannot move a sibling. That is what lets it go on a row inside a clipped
     * group without the group having to know.
     *
     * **Why it is not simply a thinner border.** The gap is the whole signal. Three of the four populations
     * already draw something at their own boundary — a group border, a selection pill, a selection tint — so
     * a ring flush against that boundary reads as the boundary thickening. Inset by
     * [ChromeGeometry.FOCUS_RING_INSET] with [ChromeGeometry.focusRingRadius], the ring's arc centre lands
     * on the container's own, which makes the two curves concentric and the gap identical on the straights
     * and through the corners. A ring that pinches at one corner reads as a rendering fault, not as focus.
     *
     * @param corner the radius of the shape being marked, not of the ring — the ring's own radius is
     *   derived, because it has to be for the curves to stay concentric.
     */
    @Composable
    fun Modifier.innerFocusRing(
        focused: Boolean,
        corner: Corner,
        accent: Color = MaterialTheme.colorScheme.primary,
        backdrop: Color = MaterialTheme.colorScheme.background,
    ): Modifier = innerFocusRing(focused, corner.radiusDp, accent, backdrop)

    /**
     * The same inward ring for chrome whose radius is not one of the three [Corner] tiers.
     *
     * **Circles are why this overload exists.** A circle's radius is half its own size, so the accent
     * swatch (30 dp → 15 dp) and the workspace avatar (46 dp → 23 dp) need different radii, and a
     * `Corner.Circle` tier would be wrong at every size but one. The caller passes `size / 2` and the
     * concentricity rule does the rest.
     *
     * **[inset] is why it takes an inset at all.** [ChromeGeometry.FOCUS_RING_INSET] is derived from the
     * substrate's one edge weight, so it is correct for chrome that draws a substrate edge. A component
     * drawing something heavier at its own boundary — the accent swatch's 2.5 dp selection border — needs
     * [ChromeGeometry.focusRingInsetOver] instead, or the ring's outer edge lands half a dp inside the
     * border and the two merge into one heavier stroke. On the swatch that merge would be worse than
     * cosmetic: the border *is* the selection signal, so a ring that thickens it says "selected" instead of
     * "focused".
     *
     * @param containerRadiusDp the radius of the shape being marked. For a circle, half its size.
     * @param backdrop what the ring is measured against — **the surface the ring is drawn on**, which for a
     *   colour swatch is the swatch's own fill and not the page. See [ChromeFocus.ringOnCircle].
     */
    @Composable
    fun Modifier.innerFocusRing(
        focused: Boolean,
        containerRadiusDp: Float,
        accent: Color = MaterialTheme.colorScheme.primary,
        backdrop: Color = MaterialTheme.colorScheme.background,
        inset: Float = ChromeGeometry.FOCUS_RING_INSET,
    ): Modifier {
        // Read before the early return so the measured colour is resolved on every composition rather
        // than only on the focused ones — a @Composable call inside a branch is a conditional call.
        val ringColor = focusRing(accent, backdrop)
        if (!focused) return this
        return drawWithContent {
            drawContent()
            val insetPx = inset.dp.toPx()
            val stroke = ChromeGeometry.FOCUS_RING_WIDTH.dp.toPx()
            // The stroke straddles the path, so the path itself sits half a stroke further in than the
            // gap: inset is the distance to the ring's *outer* edge, which is what the eye measures.
            val offset = insetPx + stroke / 2f
            // Concentric with the container: radius shrinks by exactly what the ring moved inward.
            val radius = (containerRadiusDp - inset).coerceAtLeast(0f).dp.toPx()
            drawRoundRect(
                color = ringColor,
                topLeft = Offset(offset, offset),
                size = Size(
                    (size.width - offset * 2f).coerceAtLeast(0f),
                    (size.height - offset * 2f).coerceAtLeast(0f),
                ),
                cornerRadius = CornerRadius(radius),
                style = Stroke(width = stroke),
            )
        }
    }

    /**
     * The inward ring for a **pill** — chrome whose radius is half its own measured height.
     *
     * **Why a pill cannot use the [Float] overload.** A `CircleShape` on something wider than it is tall is
     * not a circle; [RoundedCornerShape] clamps the radius to half the smaller dimension, so the shape's real
     * radius is half the height. That height is decided by the content and its padding — a chip is "however
     * tall 13 sp plus 8 dp of padding comes out" — and nothing at the call site knows it. Passing a guess
     * produces exactly the artefact the concentricity rule exists to prevent: too large and the ring bulges
     * past the pill's own curve, too small and it cuts the corner short.
     *
     * The draw scope *does* know the height, so this resolves the radius there instead of asking the caller
     * for a number the caller cannot have. Four sites need it — the two `QuickChip` overloads, the database
     * chip and the object pill — and every future pill gets it without having to work this out again.
     *
     * The radius is derived from the ring's own box rather than the container's: the stroke is already inset,
     * so half of the *inset* height is what keeps it concentric with the pill's outer curve.
     */
    @Composable
    fun Modifier.innerFocusRingPill(
        focused: Boolean,
        accent: Color = MaterialTheme.colorScheme.primary,
        backdrop: Color = MaterialTheme.colorScheme.background,
        inset: Float = ChromeGeometry.FOCUS_RING_INSET,
    ): Modifier {
        val ringColor = focusRing(accent, backdrop)
        if (!focused) return this
        return drawWithContent {
            drawContent()
            val stroke = ChromeGeometry.FOCUS_RING_WIDTH.dp.toPx()
            val offset = inset.dp.toPx() + stroke / 2f
            val w = (size.width - offset * 2f).coerceAtLeast(0f)
            val h = (size.height - offset * 2f).coerceAtLeast(0f)
            drawRoundRect(
                color = ringColor,
                topLeft = Offset(offset, offset),
                size = Size(w, h),
                cornerRadius = CornerRadius(minOf(w, h) / 2f),
                style = Stroke(width = stroke),
            )
        }
    }
}

/**
 * A hoisted interaction source and the focus ring that reads it, as one value.
 *
 * **Why this exists rather than three lines at each call site.** A focus ring needs a
 * [MutableInteractionSource] that the `clickable` and the ring agree on. Written out, that is a `remember`,
 * a `collectIsFocusedAsState`, and a modifier — and getting any of the three subtly wrong produces chrome
 * that compiles, runs, and silently shows nothing when focused, which is indistinguishable from not having
 * done the work. The tree already demonstrates the failure: `collectIsFocusedAsState` appears nowhere in
 * `ui/`, so before this substrate no interactive site in the app drew a focus affordance that a keyboard or
 * switch-access user could actually see — only the ripple's 1.22:1 state layer, which the measurement below
 * shows does not reach the bar.
 *
 * Bundling the two means a caller cannot hold one without the other, and cannot pass the ring a source that
 * is not the one the click is wired to:
 *
 * ```
 * val focus = rememberChromeFocus()
 * Row(
 *     Modifier
 *         .clip(shape)
 *         .clickable(interactionSource = focus.source, indication = ripple(), onClick = onClick)
 *         .then(focus.ring(Corner.Card)),
 * )
 * ```
 *
 * The ring goes **after** the `clickable` in the chain so it draws over the ripple rather than under it, and
 * inside the `clip` so a clipped parent cannot delete it.
 *
 * **What the ripple already does, and why it is not enough.** A rippled target is not blind to focus:
 * `StateLayer` in `material-ripple` handles `FocusInteraction.Focus`, and Material3 1.4.0's
 * `RippleDefaults.RippleAlpha` carries `focusedAlpha = 0.10f`, so focus already tints the surface. Measured on
 * this app's six surfaces that tint lands between 1.22:1 and 1.32:1 — under half of WCAG 1.4.11's 3:1, and
 * below even the substrate's quieter resting-edge target. Material3 also ships no focus-outline primitive of
 * its own, so the 559 Material buttons in the tree are in the same position as the hand-rolled chrome beside
 * them. `ChromeGeometryTest` pins that measurement, because "the ripple already handles focus" is the most
 * plausible reason a future change would remove this.
 */
class ChromeFocus internal constructor(
    val source: MutableInteractionSource,
    private val focused: Boolean,
) {
    /** True while this chrome holds focus, for a caller that wants to change something other than the ring. */
    val isFocused: Boolean get() = focused

    /**
     * The inward ring for chrome with the given [corner] radius.
     *
     * Inward is the default because it is what the app's chrome can actually use: a ring that takes no
     * layout space works on a row inside a clipped group and on a nav item packed flush against its
     * neighbour, which is nearly all of the population. Floating chrome that owns space on every side
     * should use `NorfoldChrome.focusRing` directly instead.
     */
    @Composable
    fun ring(corner: Corner, backdrop: Color = MaterialTheme.colorScheme.background): Modifier =
        with(NorfoldChrome) { Modifier.innerFocusRing(focused, corner, backdrop = backdrop) }

    /**
     * The inward ring for chrome whose radius is not one of the three [Corner] tiers.
     *
     * The tiers describe chrome the substrate shapes. A few components predate it and carry their own
     * radius — the segmented control's 7 dp option is the clearest case — and for those the honest move is
     * to derive the ring from the radius the component actually draws rather than to round it to a tier. A
     * ring built on a *larger* radius than its container bulges past the container's own curve at every
     * corner, which reads as a rendering fault; one built on a smaller radius cuts the corner short.
     *
     * @param containerRadiusDp the radius the container's own shape uses.
     * @param surface the colour the ring is drawn on, which is what it must be measured against.
     * @param boundaryWidthDp what this chrome already draws at its own edge, so the ring can clear it. The
     *   default is the substrate's one edge weight, which is right for chrome that draws a substrate edge.
     *   Rounded chrome with a heavier selected border needs its own value for the reason
     *   [ringOnCircle] documents: at the default inset a ring inside a 2 dp border lands half a dp from it,
     *   and the two strokes merge into one heavier edge that says "selected" rather than "focused". This is
     *   the same parameter [ringOnCircle] takes — a rounded rectangle needs it for the same reason a circle
     *   does, and only the radius differs.
     */
    @Composable
    fun ringOnShape(
        containerRadiusDp: Float,
        surface: Color,
        boundaryWidthDp: Float = ChromeGeometry.RESTING_EDGE_WIDTH,
    ): Modifier = with(NorfoldChrome) {
        Modifier.innerFocusRing(
            focused = focused,
            containerRadiusDp = containerRadiusDp,
            backdrop = surface,
            inset = ChromeGeometry.focusRingInsetOver(boundaryWidthDp),
        )
    }

    /**
     * The inward ring for chrome that is **filled with a colour of its own**, measured against that fill.
     *
     * **This exists because of a defect the default would have shipped.** [ring] measures the ring against
     * the page, which is right for chrome that sits on the page. A colour swatch does not: the ring is drawn
     * on top of the swatch's own fill. And the accent-picker swatch is the worst possible case of that — the
     * default ring colour is `primary`, the *currently selected accent*, and the selected swatch is filled
     * with exactly that colour. A ring measured against the page and drawn on the swatch would land at
     * 1.00:1 against what it is actually drawn on: perfectly invisible, on precisely the one swatch the user
     * is most likely to tab to first.
     *
     * Passing [surface] as the backdrop is not a workaround for that; it is the correct measurement. WCAG
     * 1.4.11 asks for 3:1 against *adjacent colours*, and the adjacent colour here is the fill.
     * [NorfoldChrome.focusRing] then solves for a lightness that clears the target against it, so a ring on
     * a dark swatch comes back light and a ring on a light swatch comes back dark.
     *
     * @param sizeDp the diameter, for a circle; the ring's radius is derived as half of it.
     * @param surface the colour the ring is drawn on top of.
     * @param boundaryWidthDp what this chrome already draws at its own edge, so the ring can clear it. The
     *   swatch's selection border is 2.5 dp, which the substrate's default inset would not clear.
     */
    @Composable
    fun ringOnCircle(
        sizeDp: Float,
        surface: Color,
        boundaryWidthDp: Float = ChromeGeometry.RESTING_EDGE_WIDTH,
    ): Modifier = with(NorfoldChrome) {
        Modifier.innerFocusRing(
            focused = focused,
            containerRadiusDp = sizeDp / 2f,
            backdrop = surface,
            inset = ChromeGeometry.focusRingInsetOver(boundaryWidthDp),
        )
    }

    /**
     * The inward ring for a pill, whose radius is half a height only the layout knows.
     *
     * See [NorfoldChrome.innerFocusRingPill]. Use this for anything shaped `CircleShape` that is wider than
     * it is tall — a chip, a tag, a filter pill — and [ringOnCircle] for something genuinely circular, where
     * the size is fixed and known at the call site.
     *
     * @param surface the colour the ring is drawn on. A selected chip filled with `primary` must pass that
     *   fill, for the reason [ringOnCircle] documents at length: an accent ring on an accent fill is 1.00:1.
     */
    @Composable
    fun ringOnPill(surface: Color): Modifier = with(NorfoldChrome) {
        Modifier.innerFocusRingPill(focused, backdrop = surface)
    }
}

/**
 * A [ChromeFocus] for one piece of interactive chrome.
 *
 * The source is remembered, so it survives recomposition and stays the same object the `clickable` is
 * holding — a fresh source each composition would emit focus to something nothing is watching.
 *
 * @param source the source to observe, for chrome that already has one. Two populations do: the four sites
 *   that drive [com.norfold.app.ui.components.pressScale] from a source they hold, and the 38 that pass one
 *   to `Surface(onClick = …)`. Both need the ring to watch the *same* object the interaction is emitted on;
 *   a second source would observe focus that nothing reports. Omit it and one is remembered here.
 */
@Composable
fun rememberChromeFocus(source: MutableInteractionSource? = null): ChromeFocus {
    val resolved = source ?: remember { MutableInteractionSource() }
    val focused by resolved.collectIsFocusedAsState()
    return ChromeFocus(resolved, focused)
}

/**
 * The inward focus ring for chrome whose click is wired somewhere else.
 *
 * ## Why this exists beside [focusableChrome]
 *
 * [focusableChrome] owns the `clickable`, which is what lets it guarantee that the ring and the click share
 * one interaction source. Two populations cannot give it that ownership:
 *
 * - **The 38 `Surface(onClick = …)` sites.** `Surface`'s own click parameter is not the same thing as
 *   `Modifier.clickable`: it supplies `minimumInteractiveComponentSize`, which the modifier does not, so
 *   moving these to `focusableChrome` would silently shrink 38 targets below 48 dp to buy them a ring. That
 *   is a straight trade of one accessibility failure for another. They keep `onClick` and take the ring
 *   alone.
 * - **The four `pressScale` sites.** The scale animation reads presses off a source the caller already holds
 *   and hands to its `clickable`. A ring with its own source would leave the two watching different objects.
 *
 * So this takes the source rather than making one, and draws only the ring. The caller stays responsible for
 * the click, which it already was.
 *
 * @param source the interaction source the click is already reported on.
 * @param radiusDp the radius the chrome's own shape draws. For a circle, half its size.
 * @param surface what the ring is measured against — the colour it is drawn on.
 * @param boundaryWidthDp what this chrome already draws at its own edge, so the ring can clear it. See
 *   [ChromeFocus.ringOnShape]: chrome whose *selected* state is a heavier border needs its own value, or
 *   focusing an already-selected item just thickens the stroke that means "selected".
 */
@Composable
fun Modifier.chromeFocusRing(
    source: MutableInteractionSource,
    radiusDp: Float,
    surface: Color = MaterialTheme.colorScheme.background,
    boundaryWidthDp: Float = ChromeGeometry.RESTING_EDGE_WIDTH,
): Modifier = this.then(rememberChromeFocus(source).ringOnShape(radiusDp, surface, boundaryWidthDp))

/**
 * The same ring for chrome shaped by one of the substrate's three [Corner] tiers.
 */
@Composable
fun Modifier.chromeFocusRing(
    source: MutableInteractionSource,
    corner: Corner,
    surface: Color = MaterialTheme.colorScheme.background,
): Modifier = chromeFocusRing(source, corner.radiusDp, surface)

/**
 * Make this chrome clickable **and** focus-visible in one call, for chrome that owns its own radius.
 *
 * ## Why a modifier and not another component
 *
 * The largest interactive population in the app is not a row or a card: it is 50 sites that put
 * `.clickable { … }` on a `Surface`'s own modifier, spread across eleven files and **twelve** distinct shapes —
 * 6, 8, 10, 12, 14, 16, 18, 20 dp, a circle, `MaterialTheme.shapes.medium`, a local constant, and one with no
 * shape at all. They are a workspace tile, a stat card, a quick chip, a search result, a shape swatch, an
 * onboarding choice, a board column header. No single component could absorb that without a parameter for
 * every difference, which is the overgrown chrome component the substrate/semantics split exists to prevent.
 *
 * What they *do* share is the mistake: `.clickable` with nothing that shows focus. So the shared thing is the
 * wiring, not the container — and the honest shape for shared wiring is a modifier the existing `Surface` can
 * take, leaving its shape, colour, border and elevation exactly where its author put them.
 *
 * ## Why it takes the radius rather than reading it
 *
 * A `Modifier` cannot see the `Surface` it is passed to, so it cannot discover that the shape is 18 dp; the
 * caller has to say. That is a real cost — a caller can pass a radius that does not match its shape, and the
 * ring will then bulge or pinch through the corners. It is accepted because the alternative is worse: the
 * ring must be concentric with the *actual* shape, and every way of guessing it is wrong more often than a
 * caller copying the number beside it. Pass the same value the `shape` argument uses, and for a circle pass
 * half the size.
 *
 * ```
 * Surface(
 *     Modifier.focusableChrome(radiusDp = 18f, surface = fill) { onClick() },
 *     shape = RoundedCornerShape(18.dp),
 * ) { … }
 * ```
 *
 * @param radiusDp the radius the chrome's own shape draws. For a circle, half its size.
 * @param surface what the ring is measured against — **the colour the ring is drawn on**. Chrome filled with
 *   an accent must pass that fill, or an accent-coloured ring lands at 1.00:1 against it. See
 *   [ChromeFocus.ringOnCircle] for the case that made this necessary.
 * @param enabled false leaves the chrome inert and unfocusable, matching `clickable`'s own contract.
 * @param onClick last, like `Modifier.clickable`'s own, so a site whose action is a block of statements can
 *   write it as a trailing lambda instead of a named argument holding a multi-line body.
 */
@Composable
fun Modifier.focusableChrome(
    radiusDp: Float,
    surface: Color = MaterialTheme.colorScheme.background,
    enabled: Boolean = true,
    onClickLabel: String? = null,
    role: Role? = null,
    onClick: () -> Unit,
): Modifier {
    val focus = rememberChromeFocus()
    val ring = focus.ringOnShape(radiusDp, surface)
    return this
        .clickable(
            interactionSource = focus.source,
            indication = ripple(),
            enabled = enabled,
            onClickLabel = onClickLabel,
            role = role,
            onClick = onClick,
        )
        .then(ring)
}

/**
 * The same wiring for chrome shaped by one of the substrate's three [Corner] tiers.
 *
 * Preferred where it applies, because a tier cannot disagree with the shape the way a loose `Float` can: the
 * caller passes the tier to both `NorfoldChrome.shape(corner)` and here, so there is one number.
 */
@Composable
fun Modifier.focusableChrome(
    corner: Corner,
    surface: Color = MaterialTheme.colorScheme.background,
    enabled: Boolean = true,
    onClickLabel: String? = null,
    role: Role? = null,
    onClick: () -> Unit,
): Modifier = focusableChrome(corner.radiusDp, surface, enabled, onClickLabel, role, onClick)

/**
 * The same wiring for a **pill** — chrome shaped `CircleShape` but wider than it is tall.
 *
 * Separate from the [Float] overload rather than a `radiusDp` a caller could supply, because the radius of a
 * pill is half a height the call site does not know: it falls out of the content and its padding at layout
 * time. See [NorfoldChrome.innerFocusRingPill].
 *
 * @param surface the colour the ring is drawn on. A chip that fills itself with `primary` when selected must
 *   pass that fill, or the ring is 1.00:1 against it on exactly the chip the user reaches first.
 */
@Composable
fun Modifier.focusablePill(
    surface: Color = MaterialTheme.colorScheme.background,
    enabled: Boolean = true,
    onClickLabel: String? = null,
    role: Role? = null,
    onClick: () -> Unit,
): Modifier {
    val focus = rememberChromeFocus()
    val ring = focus.ringOnPill(surface)
    return this
        .clickable(
            interactionSource = focus.source,
            indication = ripple(),
            enabled = enabled,
            onClickLabel = onClickLabel,
            role = role,
            onClick = onClick,
        )
        .then(ring)
}

/**
 * A piece of floating interaction chrome: the substrate's single surface.
 *
 * Everything a caller can vary is geometric. There is no `destructive`, no `title`, no `mode`, no
 * `loading` — a surface that knew about any of those would be the overgrown chrome component the
 * substrate/semantics split exists to prevent, and it is the reason this takes a [Lift] and a [Corner]
 * rather than a shadow in dp and a radius in dp. Two callers who both want "a dock" cannot accidentally
 * pick 8 dp and 12 dp; they pick [Lift.Floating] and get the same answer.
 *
 * @param lift what this chrome interrupts — the page, the content, or the whole surface.
 * @param corner what reaches the corner: full-bleed media, an inset card, or a capsule.
 * @param edge false for chrome that paints its own boundary, so the substrate does not double it.
 * @param backdrop what the *edge* is measured against, which is not always what sits behind the surface.
 *   For [Lift.Flat] chrome that is genuinely the page, and the default is right. For chrome floating over
 *   arbitrary content the honest answer is that no single ratio can be claimed — the content underneath is
 *   unknown — so the default measures against the page rather than pretending to measure against a
 *   photograph. A caller that knows its real backdrop should pass it.
 */
@Composable
fun NorfoldChromeSurface(
    modifier: Modifier = Modifier,
    lift: Lift = Lift.Floating,
    corner: Corner = Corner.Card,
    edge: Boolean = true,
    color: Color = MaterialTheme.colorScheme.surface,
    backdrop: Color = MaterialTheme.colorScheme.background,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = modifier,
        shape = NorfoldChrome.shape(corner),
        color = color,
        shadowElevation = lift.shadowDp.dp,
        tonalElevation = lift.tonalDp.dp,
        border = if (edge) {
            BorderStroke(
                ChromeGeometry.RESTING_EDGE_WIDTH.dp,
                NorfoldChrome.restingEdge(backdrop),
            )
        } else {
            null
        },
        content = content,
    )
}
