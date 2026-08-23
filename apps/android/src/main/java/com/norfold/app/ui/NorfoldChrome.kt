package com.norfold.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalAbsoluteTonalElevation
import androidx.compose.material3.LocalTonalElevationEnabled
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.norfold.app.domain.style.ChromeGeometry
import com.norfold.app.domain.style.ChromeGeometry.Corner
import com.norfold.app.domain.style.ChromeGeometry.Lift

/**
 * The colour of the container the chrome below this point is drawn inside.
 *
 * ## Why the substrate needs a local for this
 *
 * Everything the substrate measures is measured against *the pixel the chrome is drawn on*, and
 * `NorfoldChrome.resolvedBackdrop` already corrects for the two ways a declared colour differs from a painted
 * one: tonal elevation, and alpha. The alpha correction has to composite onto something, and until now that
 * something was `colorScheme.background` — the page. That is right for chrome on the page and wrong for chrome
 * inside a container, which is where a large part of the app's chrome lives.
 *
 * It is wrong twice over, and both were measured on the ten shipped accents in light mode:
 *
 * - **A translucent fill inside a container composites onto the container, not onto the page.** A selected
 *   sidebar destination is a 12% accent wash; inside [com.norfold.app.ui.components.NorfoldBottomSheet] the
 *   ring solved against wash-over-page and drawn on wash-over-sheet failed **4 of 10** (Gold 2.9430:1, Sunset
 *   2.9747:1, Forest 2.9936:1, Candy 2.9999964:1). A selected [com.norfold.app.ui.components.NorfoldPopupRow]
 *   is `secondaryContainer` at 55% over a 3 dp card, and failed **1 of 10** (Sunset 2.95:1).
 *
 *   Candy is quoted to seven decimals deliberately: it fails by 3.6 millionths, and every other figure in this
 *   file is quoted to two or four. It was counted as passing — this population was documented as 3 of 10 — until
 *   `ChromeGeometryTest` was actually run against `ColorValue`'s own arithmetic rather than a hand-computed
 *   model. `Color.copy(alpha = 0.12f)` composites at 31/255 = 0.121569, not at 0.12, because sRGB `Color` holds
 *   8-bit channels; on the narrowest population that 1.3% shifts one channel by one bit and one bit decides it.
 * - **An opaque row inside a container is drawn on the container, so the default is simply the wrong colour.**
 *   Rows inside [com.norfold.app.ui.components.NorfoldDialog] failed **3 of 10** against the page default
 *   (Gold 2.60:1, Sunset 2.61:1, Forest 2.74:1) and pass **10 of 10** against the real container.
 *
 * With this local provided, all of those populations pass 10 of 10, in both modes.
 *
 * ## Why a local rather than a parameter at each site
 *
 * The value is a property of the *host*, not of the chrome. A `SettingsRow` does not know whether it was
 * placed on a page, in a dialog or in a sheet — the sidebar's nav rows appear in all three — so asking each of
 * 116 substrate calls to name its container asks every caller to know something only its ancestor does, and to
 * keep knowing it when the ancestor changes. Threading it through the one place that *does* know is the same
 * arrangement Material already uses for [LocalAbsoluteTonalElevation], and for the same reason.
 *
 * **It defaults to `Color.Unspecified`, not to a colour**, so that "nobody said" stays distinguishable from
 * "the page". A default of `background` would read as an assertion, and the fallback belongs where the theme is
 * known — `resolvedBackdrop` resolves an unspecified value to `colorScheme.background`, which is the correct
 * answer for chrome that genuinely is on the page.
 */
val LocalChromeContainer = staticCompositionLocalOf { Color.Unspecified }

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
     * [backdrop] is resolved to the pixel actually on screen before it is measured — see [resolvedBackdrop]
     * for the two corrections and why each is needed. Both matter more here than they look, because #62 will
     * route the tree's 39 invisible `outlineVariant` borders through this function, a majority of those sites
     * sit on translucent cards, and a good number of the rest sit inside elevated sheets.
     */
    @Composable
    @ReadOnlyComposable
    fun restingEdge(backdrop: Color = Color.Unspecified): Color =
        EditorChromeColors.legible(
            MaterialTheme.colorScheme.outline,
            resolvedBackdrop(backdrop),
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
     * **[backdrop] is resolved to the pixel actually on screen before it is measured.** [resolvedBackdrop]
     * states both corrections; the reason each is load-bearing is that a ring solved against a colour that is
     * never painted is measured against nothing. A translucent backdrop is a colour the arithmetic cannot use
     * at all: [EditorChromeColors.legible] can neither measure it nor search against it, so it abandons the
     * accent and returns flat black or white chosen from a colour that is never painted. 42 of the app's 88
     * interactive `Surface`s paint a translucent or transparent fill, and on the worst of them — a workspace
     * object tile at 16% alpha over the dark page — that ink draws at **1.22:1**, with 60 of the 120
     * accent-by-tile cases failing 3:1. [EditorChromeColors.opaque] states the arithmetic and the measured
     * cases; `ChromeGeometryTest` pins them.
     */
    @Composable
    @ReadOnlyComposable
    fun focusRing(
        accent: Color = MaterialTheme.colorScheme.primary,
        backdrop: Color = Color.Unspecified,
    ): Color = EditorChromeColors.legible(
        accent,
        resolvedBackdrop(backdrop),
        ChromeGeometry.FOCUS_RING_RATIO,
    )

    /**
     * [ink] raised until it clears AA on [backdrop] — for text, not for chrome.
     *
     * The text sibling of [restingEdge] and [focusRing], and it exists because the theme's `on*` tokens are
     * paired with the surfaces the theme *chose*, and a row that changes its fill on selection changes which
     * surface its text is on without changing which token names its colour.
     *
     * **Six sites shipped that way, and on four of them selecting a row pushes its own secondary text below
     * AA.** A history entry, a restore point, a device profile and a disabled row in the flow rail each paint
     * `secondaryContainer` when selected or highlighted and read `onSurfaceVariant` for the line beneath the
     * title: **3.9043:1 light and 3.4548:1 dark**, against 5.0502 / 5.0502 / 4.6385 / 4.9227 light on the same
     * four rows unselected. So the row is *less* readable for being the one the user picked. The font list's
     * substitution warning is worse still — `error` on that fill measures **2.7565:1**, below even the 3:1 a
     * non-text element would need. The Eisenhower matrix fails from the other direction and **in light mode
     * only**: two quadrants paint `errorContainer` and `tertiaryContainer` at 50% and their subtitles land at
     * **4.3494:1** and **4.3053:1**, while the one quadrant filled with a theme-owned `surfaceVariant` passes
     * at 4.6355:1 — which is the tell. The ink is correctly paired with the surfaces Norfold chose and fails
     * only on the containers it inherited.
     *
     * [ChromeGeometry.BODY_TEXT_RATIO] rather than the chrome ratios, because this is text: 1.4.3 asks 4.5:1
     * of anything under 18 pt, and every one of the six sites is 11–13 sp. Only lightness moves, so the ink
     * stays recognisably the token it came from — across the six the hue shifts by at most 2.5° and the
     * largest lightness move is 19 points, on the red that had furthest to go.
     *
     * **Why the caller passes the fill rather than letting it default.** These are conditional fills; the
     * whole defect is that the painted colour and the named token disagree, so a call that names nothing
     * resolves to the *container* and measures against a surface the text is not on. Pass the same expression
     * the `Surface`'s `color` or the `background` gets. [resolvedBackdrop] then applies its three corrections,
     * which is what makes the 50%-alpha quadrants measurable at all.
     *
     * Nothing here is a substitute for choosing a legible pair. Where the theme owns both colours it should
     * pair them properly; this is for the case where the fill is Material's baseline and re-hueing it is a
     * product decision rather than a fix.
     */
    @Composable
    @ReadOnlyComposable
    fun legibleInk(ink: Color, backdrop: Color = Color.Unspecified): Color =
        EditorChromeColors.legible(
            ink,
            resolvedBackdrop(backdrop),
            ChromeGeometry.BODY_TEXT_RATIO,
        )

    /**
     * [backdrop] turned into the colour that is actually on screen behind the chrome.
     *
     * Three corrections, applied in the order the renderer applies them:
     *
     * 1. **The page is not always the page.** Whatever [LocalChromeContainer] names is what "behind this
     *    chrome" means here — the dialog's container inside a dialog, the sheet's inside a sheet, the theme's
     *    `background` on bare page. Every step below then works from that colour instead of from the page, and
     *    [LocalChromeContainer] states what it cost to get this wrong: five populations, up to 4 of 10 accents
     *    each, failing 1.4.11 in light mode. An unspecified [backdrop] resolves to it too, so a caller that
     *    names nothing gets its real host rather than an assumption about one.
     *
     *    **This is why every `backdrop` parameter in this file defaults to `Color.Unspecified` rather than to
     *    `colorScheme.background`.** The two look interchangeable and are not: `background` is a *specified*
     *    colour, so it would satisfy the check below and win over the container at every defaulting call site
     *    — which is all but a handful of the 116. A literal default would have made this whole step dead code
     *    for the callers that need it most, and the ten measured populations would have been unchanged while
     *    appearing to be fixed. The sentinel is what keeps "nobody named a backdrop" answerable by the host.
     *
     * 2. **Tonal elevation.** Material composites `surfaceTint` over `surface` on any elevated surface, and
     *    `lightColorScheme` defaults `surfaceTint` to the `primary` *parameter* — so on this app, which never
     *    sets the token, the tint is the user's own accent. Chrome inside an elevated `Surface` is therefore
     *    drawn on accent-shifted surface while its measurement believed it was on plain `surface`, and the
     *    ring is the one colour that suffers most from being solved against a paler version of itself:
     *    **2 of 10 shipped accents fail 3:1 at 1–4 dp and 3 of 10 at 6–10 dp**, against 0 of 10 once the
     *    painted colour is used. [ChromeGeometry.tonalTintAlpha] carries the curve and the figures.
     *
     *    Both of Material's own gates are honoured, because applying the tint where Material does not would
     *    be the same class of error in the other direction. The tint lands only when the backdrop **is**
     *    `colorScheme.surface` — a `Surface` painted `surfaceVariant` or `surface.copy(alpha = …)` gets none,
     *    which is why the app's four translucent-surface sites are corrected by step 3 and not by this one —
     *    and only while [LocalTonalElevationEnabled] is true. The elevation read is
     *    [LocalAbsoluteTonalElevation], the **accumulated** value `Surface` provides to its content, so a 1 dp
     *    row inside a 6 dp sheet resolves at 7 dp rather than at the literal its caller typed.
     *
     * 3. **Alpha.** Composited onto the resolved container, for the reason [EditorChromeColors.opaque] states
     *    at length: a translucent backdrop is not a colour the arithmetic can measure at all, so the search is
     *    abandoned and flat ink is returned instead — measured at 1.22:1 on the worst of the app's tiles.
     *
     * Ordered this way because that is the order the pixels arrive in: the container is painted, then the
     * elevated surface on top of it, then a translucent fill over that. Reversing the last two would
     * composite onto a surface that has not been tinted yet.
     */
    @Composable
    @ReadOnlyComposable
    private fun resolvedBackdrop(backdrop: Color): Color {
        val scheme = MaterialTheme.colorScheme
        // `if` rather than `takeIf { … } ?: …`: `Color` is a value class, so the elvis form boxes it into a
        // `Color?` on every composition of all 116 chrome sites to express something a branch expresses free.
        val ambient = LocalChromeContainer.current
        val container = if (ambient.isSpecified) ambient else scheme.background
        val declared = if (backdrop.isSpecified) backdrop else container
        val tinted = if (declared == scheme.surface && LocalTonalElevationEnabled.current) {
            EditorChromeColors.elevated(
                surface = declared,
                tint = scheme.surfaceTint,
                elevationDp = LocalAbsoluteTonalElevation.current.value,
            )
        } else {
            declared
        }
        return EditorChromeColors.opaque(tinted, container)
    }

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
        backdrop: Color = Color.Unspecified,
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
        backdrop: Color = Color.Unspecified,
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
        backdrop: Color = Color.Unspecified,
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
        backdrop: Color = Color.Unspecified,
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
            val w = (size.width - offset * 2f).coerceAtLeast(0f)
            val h = (size.height - offset * 2f).coerceAtLeast(0f)
            // Concentric with the container: radius shrinks by exactly what the ring moved inward — then
            // capped at half the ring's own shorter side.
            //
            // The cap is not defensive tidying; without it the concentricity this function exists to
            // guarantee silently stops holding on short chrome. `drawRoundRect` reaches Skia unmodified
            // (`AndroidCanvas.drawRoundRect` forwards straight to `android.graphics.Canvas`), and Skia
            // scales an oversized radius down to fit rather than rejecting it — so a 20 dp row asking for
            // a 7 dp ring radius inside a 12 dp box is quietly drawn at 6, and the caller's stated radius
            // and the drawn one part company with nothing reporting it.
            //
            // Capping here rather than at the call site is the point: [ChromeGeometry.focusRingRadius] is
            // pure arithmetic on a radius, and the height that decides whether that radius fits is not
            // known until layout. This is the first scope that knows both.
            val radius = minOf((containerRadiusDp - inset).coerceAtLeast(0f).dp.toPx(), minOf(w, h) / 2f)
            drawRoundRect(
                color = ringColor,
                topLeft = Offset(offset, offset),
                size = Size(w, h),
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
        backdrop: Color = Color.Unspecified,
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
    fun ring(corner: Corner, backdrop: Color = Color.Unspecified): Modifier =
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
    surface: Color = Color.Unspecified,
    boundaryWidthDp: Float = ChromeGeometry.RESTING_EDGE_WIDTH,
): Modifier = this.then(rememberChromeFocus(source).ringOnShape(radiusDp, surface, boundaryWidthDp))

/**
 * The same ring for chrome shaped by one of the substrate's three [Corner] tiers.
 */
@Composable
fun Modifier.chromeFocusRing(
    source: MutableInteractionSource,
    corner: Corner,
    surface: Color = Color.Unspecified,
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
 * @param boundaryWidthDp what this chrome already draws at its own edge, so the ring can clear it. The
 *   default is the substrate's one edge weight. Chrome whose *selected* state is a heavier border must pass
 *   that heavier weight, or focusing an already-selected item merely thickens the stroke that means
 *   "selected" — see [ChromeFocus.ringOnShape]. This is the same parameter [chromeFocusRing] takes, and it
 *   is here for the same reason: the picker population's whole selection signal is a 2 dp accent border.
 * @param onClick last, like `Modifier.clickable`'s own, so a site whose action is a block of statements can
 *   write it as a trailing lambda instead of a named argument holding a multi-line body.
 */
@Composable
fun Modifier.focusableChrome(
    radiusDp: Float,
    surface: Color = Color.Unspecified,
    enabled: Boolean = true,
    onClickLabel: String? = null,
    role: Role? = null,
    boundaryWidthDp: Float = ChromeGeometry.RESTING_EDGE_WIDTH,
    onClick: () -> Unit,
): Modifier {
    val focus = rememberChromeFocus()
    val ring = focus.ringOnShape(radiusDp, surface, boundaryWidthDp)
    return this
        .clipToChrome(radiusDp)
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
    surface: Color = Color.Unspecified,
    enabled: Boolean = true,
    onClickLabel: String? = null,
    role: Role? = null,
    boundaryWidthDp: Float = ChromeGeometry.RESTING_EDGE_WIDTH,
    onClick: () -> Unit,
): Modifier = focusableChrome(corner.radiusDp, surface, enabled, onClickLabel, role, boundaryWidthDp, onClick)

/**
 * The same wiring for a **pill** — chrome shaped `CircleShape` but wider than it is tall.
 *
 * Separate from the [Float] overload rather than a `radiusDp` a caller could supply, because the radius of a
 * pill is half a height the call site does not know: it falls out of the content and its padding at layout
 * time. See [NorfoldChrome.innerFocusRingPill].
 *
 * The same reason makes the ripple clip easier here than there — a pill's shape *is* `CircleShape`, which
 * resolves its own radius at layout time, so this clips to that rather than to a number. See [clipToChrome]
 * for why the clip has to come from the substrate at all.
 *
 * @param surface the colour the ring is drawn on. A chip that fills itself with `primary` when selected must
 *   pass that fill, or the ring is 1.00:1 against it on exactly the chip the user reaches first.
 */
@Composable
fun Modifier.focusablePill(
    surface: Color = Color.Unspecified,
    enabled: Boolean = true,
    onClickLabel: String? = null,
    role: Role? = null,
    onClick: () -> Unit,
): Modifier {
    val focus = rememberChromeFocus()
    val ring = focus.ringOnPill(surface)
    return this
        .clip(CircleShape)
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
 * Bound the ripple to the shape the chrome actually draws.
 *
 * **A ripple does not respect a rounded corner unless something clips it.**
 * `RippleAnimation.draw` clips its expanding circle with `clipRect(0, 0, width, height)` — a *rectangle*, the
 * node's own bounds — and nothing else. On chrome with a 12 dp radius that leaves four square corners of ink
 * outside the surface the user pressed. Material never shows this because `Surface` composes
 * `modifier → minimumInteractiveComponentSize → surface(…) → clickable`, and `surface()` ends in
 * `.clip(shape)`, so Material's own ripple is always inside a clip that Material put there.
 *
 * The substrate's modifiers arrive in that leading `modifier` — *outside* the clip. So the clip has to come
 * from here, and it has to come **before** the interaction modifier, because a clip only affects what is
 * drawn after it in the chain.
 *
 * A caller could write `.clip(RoundedCornerShape(12.dp))` before every one of these, and the KDoc used to say
 * so; the result was that 36 of 37 rounded sites did not. The radius is already a parameter of every one of
 * these modifiers — the caller has already said what shape it draws — so the clip is derivable and belongs
 * here, where it cannot be forgotten.
 *
 * Radius 0 returns [Modifier] untouched rather than clipping to a rectangle: the ripple's own `clipRect`
 * already bounds it to exactly that, so the clip would be a no-op node on every square site.
 *
 * **What this costs.** A clip discards anything the chrome's content draws outside its bounds, and it also
 * discards a shadow, since a shadow is drawn outside them. Nothing under this fix is affected — all 36 sites
 * are non-elevated `Surface`s with no badge, no negative offset and no overflow — but chrome that *needs* to
 * draw outside itself must not route through these modifiers, and should use [chromeFocusRing] on its own
 * interaction instead, which adds no clip.
 */
private fun Modifier.clipToChrome(radiusDp: Float): Modifier =
    if (radiusDp <= 0f) this else clip(RoundedCornerShape(radiusDp.dp))

/**
 * The touch-target floor a row must restate once its control stops being the target.
 *
 * Material attaches `minimumInteractiveComponentSize` from *inside* each selection control's interaction
 * modifier, so it arrives with the handler and leaves with it. Passing `null` to make the control decorative —
 * which is the whole mechanism behind [com.norfold.app.ui.components.SettingsToggleRow] — therefore removes the
 * 48 dp floor along with the semantics, and what is left is only what the control draws: a `Switch` is 32 dp
 * tall, a `Checkbox` and a `RadioButton` are 20 dp of icon inside 2 dp of padding, so 24. A row that relied on
 * its control to hold the line collapses below 48 dp the moment it is fixed, which is the failure mode this
 * constant exists to make hard to forget.
 *
 * **Where 48 comes from, stated precisely because it was cited wrongly here.** It is Android's own floor —
 * Material's `LocalMinimumInteractiveComponentSize` defaults to 48 dp, and the platform's
 * `android:minimumTouchTargetSize` agrees — and it is *above* what WCAG asks at any level, not equal to it.
 * WCAG 2.5.8 Target Size (Minimum) is Level **AA** at **24×24** CSS px; 44×44 is 2.5.5 Target Size (Enhanced),
 * Level **AAA**. So a 24 dp control is not a WCAG AA failure on its own, and the reason to hold 48 anyway is the
 * platform convention and the thumb, not the success criterion. A row measured against the wrong criterion gets
 * "fixed" to the wrong number, which is why the source of each figure is named.
 *
 * It is stated here rather than read from `LocalMinimumInteractiveComponentSize` because that local is
 * Material's, and it is being read at the moment the caller has deliberately stepped outside Material's wiring.
 */
const val MIN_TOUCH_TARGET_DP = 48

/**
 * Make this chrome **toggleable** and focus-visible in one call, as a single accessibility node.
 *
 * ## The defect this exists to stop recurring
 *
 * A row that wraps a live `Switch` or `Checkbox` is **two accessibility nodes for one action**, and it does not
 * look like it. `clickable` does declare that it merges its descendants, but the merge walk in
 * `SemanticsNode` stops descending at any child that *itself* merges — and every Material selection control
 * does, because each is built on `toggleable`, `triStateToggleable` or `selectable`. So the row carries the
 * name with no state, the control carries the state with no name, and a screen reader announces the label and
 * then, separately, an unnamed switch. That is WCAG 4.1.2 at Level A, and it was found at four sites plus five
 * near-identical private `ToggleRow` helpers in five different files, each of which had independently written
 * the same broken shape.
 *
 * The fix is not a wrapper around `Switch`. It is to move the interaction to the row — `toggleable` here, and
 * `null` for the control's own handler — so that one node carries the name, the role and the state. What makes
 * that worth a shared modifier rather than five more copies is the part every copy got wrong: nulling the
 * handler also removes Material's touch-target floor, so the row must restate it. This applies
 * [MIN_TOUCH_TARGET_DP] unconditionally, which is the one thing a caller cannot forget to do.
 *
 * ## What the caller still owes
 *
 * The control must be passed `null`. Nothing here can enforce that — a modifier cannot see the composable it is
 * applied around — so a caller that leaves a live handler in place gets a row that is *both* nodes and toggles
 * twice. [com.norfold.app.ui.components.RowSwitch] takes a nullable handler precisely so this is expressible.
 *
 * @param checked the state, which this reports as the node's own rather than the control's.
 * @param radiusDp the radius the row's own shape draws, so the ring stays concentric with it. This also
 *   clips the ripple to that radius — see [clipToChrome] — so the caller does not, and must not, clip first.
 * @param role [Role.Switch] for a switch, [Role.Checkbox] for a checkbox. It changes what a screen reader
 *   calls the node and must match what is drawn.
 * @param surface what the ring is measured against — the colour it is drawn on. Unspecified resolves to
 *   [LocalChromeContainer], which is right for a row that paints no fill of its own.
 * @param onValueChange last, matching `Modifier.toggleable`'s own parameter order.
 */
@Composable
fun Modifier.toggleableChrome(
    checked: Boolean,
    radiusDp: Float,
    role: Role,
    surface: Color = Color.Unspecified,
    enabled: Boolean = true,
    onValueChange: (Boolean) -> Unit,
): Modifier {
    val focus = rememberChromeFocus()
    val ring = focus.ringOnShape(radiusDp, surface)
    return this
        .heightIn(min = MIN_TOUCH_TARGET_DP.dp)
        .clipToChrome(radiusDp)
        .toggleable(
            value = checked,
            interactionSource = focus.source,
            indication = ripple(),
            enabled = enabled,
            role = role,
            onValueChange = onValueChange,
        )
        .then(ring)
}

/**
 * The same wiring for chrome shaped by one of the substrate's three [Corner] tiers.
 */
@Composable
fun Modifier.toggleableChrome(
    checked: Boolean,
    corner: Corner,
    role: Role,
    surface: Color = Color.Unspecified,
    enabled: Boolean = true,
    onValueChange: (Boolean) -> Unit,
): Modifier = toggleableChrome(checked, corner.radiusDp, role, surface, enabled, onValueChange)

/**
 * Make this chrome **selectable** and focus-visible in one call, as a single accessibility node.
 *
 * The same defect and the same fix as [toggleableChrome]; the difference is only that a selection is one of a
 * set rather than an independent on/off, so this reports `selected` rather than `checked` and takes an
 * `onClick`. Use it for a row holding a `RadioButton`, and put `selectableGroup()` on the parent **when the
 * parent composes every option** — a `Column` with `verticalScroll` does, and then the group's announced
 * position is true.
 *
 * **A lazy parent is the exception.** `CollectionInfo_android` derives an item's position from the parent's
 * *composed* children, and a `LazyColumn` composes only what is visible, so the group modifier would announce
 * "3 of 6" out of sixteen and renumber on every scroll. A lazy list already publishes its real
 * `totalItemsCount` with a `-1` column count that marks it lazy, and the platform delegate returns early on
 * that marker — so on a lazy parent, leave `selectableGroup()` off and let the list's own count stand.
 *
 * @param selected the state, reported as this node's own rather than the indicator's.
 * @param radiusDp the radius the row's own shape draws, which this also clips the ripple to. See
 *   [clipToChrome]; the caller does not clip first.
 * @param surface what the ring is drawn on. Unspecified resolves to [LocalChromeContainer].
 * @param boundaryWidthDp what this chrome draws at its own edge when selected, so the ring can clear it.
 *
 *   **The default is wrong for a whole population, which is why this is a parameter.** A settings row states
 *   its selection with a `RadioButton` and draws no border, so the substrate's one edge weight is right. The
 *   app's *pickers* — a cover, an emoji, a theme, an icon family, a folder icon — state it with a **2 dp
 *   accent border and nothing else**: no indicator, no tick. Inside a 2 dp border the default 3 dp inset puts
 *   the ring's outer edge half a dp from the border's inner edge, and at that distance the two strokes merge
 *   into a single heavier accent edge. On chrome whose border *is* the selection signal, that reads as
 *   "selected", not as "focused" — so on the one item a keyboard user is most likely to land on first, the
 *   focus indication says the thing that was already true. Pass the selected border's own weight and
 *   [ChromeGeometry.focusRingInsetOver] clears it before leaving the gap.
 * @param onClick the selection, last, matching `Modifier.selectable`'s own parameter order.
 */
@Composable
fun Modifier.selectableChrome(
    selected: Boolean,
    radiusDp: Float,
    surface: Color = Color.Unspecified,
    enabled: Boolean = true,
    boundaryWidthDp: Float = ChromeGeometry.RESTING_EDGE_WIDTH,
    onClick: () -> Unit,
): Modifier {
    val focus = rememberChromeFocus()
    val ring = focus.ringOnShape(radiusDp, surface, boundaryWidthDp)
    return this
        .heightIn(min = MIN_TOUCH_TARGET_DP.dp)
        .clipToChrome(radiusDp)
        .selectable(
            selected = selected,
            interactionSource = focus.source,
            indication = ripple(),
            enabled = enabled,
            role = Role.RadioButton,
            onClick = onClick,
        )
        .then(ring)
}

/**
 * The same wiring for chrome shaped by one of the substrate's three [Corner] tiers.
 */
@Composable
fun Modifier.selectableChrome(
    selected: Boolean,
    corner: Corner,
    surface: Color = Color.Unspecified,
    enabled: Boolean = true,
    boundaryWidthDp: Float = ChromeGeometry.RESTING_EDGE_WIDTH,
    onClick: () -> Unit,
): Modifier = selectableChrome(selected, corner.radiusDp, surface, enabled, boundaryWidthDp, onClick)

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
    backdrop: Color = Color.Unspecified,
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
