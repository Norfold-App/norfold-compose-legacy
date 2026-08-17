package com.norfold.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
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
     */
    @Composable
    @ReadOnlyComposable
    fun restingEdge(backdrop: Color = MaterialTheme.colorScheme.background): Color =
        EditorChromeColors.legible(
            MaterialTheme.colorScheme.outline,
            backdrop,
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
     */
    @Composable
    @ReadOnlyComposable
    fun focusRing(
        accent: Color = MaterialTheme.colorScheme.primary,
        backdrop: Color = MaterialTheme.colorScheme.background,
    ): Color = EditorChromeColors.legible(accent, backdrop, ChromeGeometry.FOCUS_RING_RATIO)

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
