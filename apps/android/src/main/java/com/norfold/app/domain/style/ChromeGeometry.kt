package com.norfold.app.domain.style

/**
 * The geometry every piece of floating interaction chrome in Norfold shares, as plain numbers.
 *
 * This is the substrate half of the substrate/semantics split: what a surface *looks and measures* like,
 * with nothing in it about what the surface means. A destructive confirmation, a segmented mode strip, a
 * position pill, a loading placeholder and an object inspector are five different conversations with the
 * user; they are one rectangle with one corner radius, one edge weight and one resting elevation.
 *
 * **Why plain numbers in the domain layer rather than `Dp` in the UI layer.** The values below were chosen
 * against a measured inventory, and the only way a chosen number stays chosen is if a test can read it.
 * `Dp` is a Compose type and the unit source set has no Compose test harness, so a tokens object built out
 * of `Dp` can be *documented* as consistent and never *asserted* as consistent — which is exactly how the
 * tree arrived at nine distinct shadow elevations and four distinct chrome radii. `ChromeTokens` in the UI
 * layer is a thin `.dp` translation of this, the same way [ContrastAnalysis] carries the arithmetic that
 * `EditorChromeColors` only translates.
 *
 * **What the inventory found.** Across `apps/android/src/main`, floating chrome used **9 distinct
 * `shadowElevation` values** over 25 sites (1dp x7, 8dp x4, 6dp x2, 2dp x2, 16dp x2, 14dp x2, 12dp x2, 5dp,
 * 0dp) and **8 distinct `tonalElevation` values** (3dp x16, 1dp x11, 2dp x10, 8dp x9, 6dp x6, 4dp x5, 10dp,
 * 0dp), with four unrelated chrome radii already in play: the popup card's 12 dp, `PopupStyle`'s 28 dp, the
 * Flow object frame's 14/16 dp, and the Flow dock's 22 dp. None of those spreads encodes a distinction a
 * user could name. A 12 dp and a 14 dp shadow on two panels that appear in the same session is not two
 * meanings; it is two afternoons.
 *
 * The tiers below are the distinctions that *are* real, and there are only three of them.
 */
object ChromeGeometry {

    /**
     * How far a piece of chrome is lifted off what it covers.
     *
     * Three tiers, because there are three answers to "what does this chrome interrupt": nothing (it is
     * part of the page), the content under it (it floats but the page is still the subject), or the whole
     * surface (the page is suspended until this is dealt with). A fourth tier would have to name a fourth
     * relationship, and the inventory's nine values do not contain one.
     */
    enum class Lift(val shadowDp: Float, val tonalDp: Float) {

        /**
         * Chrome that belongs to the page: a framed object, an inline card, a settings group.
         *
         * No shadow at all. Its boundary is stated by [RESTING_EDGE_RATIO] instead, which is a claim about
         * *containment* rather than about height — an object frame that casts a shadow is asserting that the
         * paragraph beside it is further away, which is false.
         */
        Flat(shadowDp = 0f, tonalDp = 0f),

        /**
         * Chrome that floats over content the user is still reading: a dock, a toolbar, a position pill,
         * an inspector sheet, a menu.
         *
         * The common case, and the one the inventory spread widest. 8 dp was already the most-used
         * non-trivial shadow (4 sites, including the Flow dock) so this consolidates toward what the tree
         * had rather than toward a fresh number.
         */
        Floating(shadowDp = 8f, tonalDp = 2f),

        /**
         * Chrome that suspends the surface: a modal dialog, a destructive confirmation.
         *
         * 16 dp, which the FAB already used and which reads as unmistakably separate from [Floating] at
         * arm's length. The gap between 8 and 16 is the point — 12 dp against 8 dp is a difference the
         * renderer can draw and a user cannot see, which is why the inventory's 12 and 14 dp values
         * collapse into one of these two rather than becoming tiers of their own.
         */
        Modal(shadowDp = 16f, tonalDp = 3f),
    }

    /**
     * The corner radius for a piece of chrome, keyed to what reaches the corner.
     *
     * Deliberately three values, not the seven the tree held. The split is by whether content touches the
     * curve, which is the only thing a radius can be wrong about:
     *
     * - [MEDIA_RADIUS] — content is full-bleed, so the frame's corner *is* the content's corner and a
     *   larger radius visibly crops a photograph.
     * - [CARD_RADIUS] — content is inset by its own padding, so the radius belongs to the container.
     * - [CAPSULE_RADIUS] — the shape is the affordance: a pill, a dock, a segmented strip. Large enough to
     *   read as a capsule rather than as a rounded rectangle.
     *
     * The Flow object frame already shipped the first two at these exact values, so this generalises a
     * decision that was made and reviewed rather than making a new one.
     */
    enum class Corner(val radiusDp: Float) {
        Media(MEDIA_RADIUS),
        Card(CARD_RADIUS),
        Capsule(CAPSULE_RADIUS),
    }

    /** Media corners: a larger radius crops full-bleed content. */
    const val MEDIA_RADIUS = 14f

    /** Card corners: what the chart card, the callout wrapper and the container wrapper already use. */
    const val CARD_RADIUS = 16f

    /**
     * Capsule corners.
     *
     * 22 dp rather than the 28 dp `PopupStyle` used, because 28 dp on the 68 dp-high nav bar is only 6 dp
     * short of a true capsule and reads as one already, while 28 dp on the Flow dock's shorter row *would*
     * be a full capsule and starts eating the outermost button's tap target. 22 dp is what the dock
     * shipped and it survives both heights.
     */
    const val CAPSULE_RADIUS = 22f

    /**
     * A resting edge states a boundary. It is not a rule.
     *
     * One weight, not the five the inventory found (0.25, 1, 2, 3, 5 dp), because 60 of the 65 border
     * declarations in the tree were already 1 dp — the spread was four outliers, not a vocabulary.
     */
    const val RESTING_EDGE_WIDTH = 1f

    /**
     * A selection edge has to read at arm's length, which one dp of extra weight does not.
     *
     * Selection also changes the *colour*, and the colour is the part that carries; the weight is what
     * keeps it legible when the accent is close to the page.
     */
    const val SELECTED_EDGE_WIDTH = 3f

    /**
     * The selection edge for a **picker cell**, where the border is the entire selection signal.
     *
     * Lighter than [SELECTED_EDGE_WIDTH] because a picker cell is small and packed — a 52 dp emoji tile, a
     * 58 dp cover thumbnail — and three dp on a 52 dp box reads as a frame rather than as a choice. Two dp is
     * what all five picker populations already drew, so this records a number the tree had rather than
     * imposing a new one on chrome that was consistent.
     *
     * **It exists as a constant because two things must agree on it.** A picker draws this at its own edge
     * *and* has to tell its focus ring to stand off by exactly that much, or the ring merges into the
     * selection border and focus becomes invisible on the one cell already selected. Two literals cannot be
     * kept equal by review; one constant read twice cannot drift. See
     * [com.norfold.app.ui.selectableChrome]'s `boundaryWidthDp`.
     */
    const val PICKER_SELECTED_EDGE_WIDTH = 2f

    /**
     * What a resting edge has to measure against its own backdrop to be seen without shouting.
     *
     * **Neither theme outline token can do this job, on any surface, in either mode.** Measured on the
     * shipped palette, `outline` reaches 1.17:1 on the light page, 1.25:1 on `surface` and 1.11:1 on
     * `surfaceVariant`; `outlineVariant` reaches 1.07:1, 1.14:1 and 1.02:1. Dark is better and still not
     * enough: 1.41/1.33/1.23 and 1.24/1.16/1.08. Below roughly 1.5:1 a non-text boundary is not visible at
     * all, so **every one of the 39 `BorderStroke(1.dp, outlineVariant)` sites in the tree is drawing an
     * edge that states a boundary the user cannot see** — and the eight sites that additionally reduce the
     * alpha (0.7 down to 0.18) land between 1.02:1 and 1.11:1, which is a border that exists only in the
     * source.
     *
     * So the edge is *derived*, not picked: the theme's own `outline`, moved in lightness only until it
     * measures this ratio against the backdrop it sits on. Theme-following, because the user's mode still
     * drives the input; measured, because the output is solved rather than chosen.
     *
     * 2.2:1 is a deliberate target and not a maximum. At 3:1 and above a resting edge reads as a rule
     * competing with its content, which is how a page of framed objects starts looking like a spreadsheet.
     * This is the value the Flow object frame and the Document object edge already share, and generalising
     * it here is what keeps a third surface from picking a fourth number.
     */
    const val RESTING_EDGE_RATIO = 2.2

    /**
     * What a *focus* ring has to measure. Not the same question as a resting edge, and not the same answer.
     *
     * A resting edge may be quiet because the user is not looking for it. A focus ring is the only thing
     * telling a keyboard or switch-access user where their next action lands, so it is held to WCAG
     * 1.4.11's [ContrastAnalysis.UI_COMPONENT] — 3:1 — with no negotiation. This is the one number in this
     * object taken from a standard rather than from the inventory, because the inventory had nothing to
     * take: **the tree has no focus-visible affordance anywhere.** One `.focusable()` call, no
     * `collectIsFocusedAsState` outside text-commit bookkeeping, and 58 hand-rolled `.clickable` chrome
     * sites that therefore show nothing at all when focused. The 559 Material buttons get a ring from the
     * library; the 58 do not.
     */
    const val FOCUS_RING_RATIO = ContrastAnalysis.UI_COMPONENT

    /**
     * What *text* on chrome has to measure. The third of the three ratios, and the strictest.
     *
     * [RESTING_EDGE_RATIO] and [FOCUS_RING_RATIO] answer 1.4.11, which is about non-text things being
     * findable. This one answers 1.4.3, which is about words being readable, and 1.4.3 does not negotiate
     * below [ContrastAnalysis.AA_NORMAL] for anything under 18 pt. Every site that needs it is 11–13 sp, so
     * the large-text relaxation never applies and there is no second value to pick.
     *
     * It is named here rather than read from [ContrastAnalysis] at the call site for the same reason the
     * other two are: a ratio that appears as a bare `4.5` in a composable is a number the next reader has to
     * re-derive. See [com.norfold.app.ui.NorfoldChrome.legibleInk] for what fails without it.
     */
    const val BODY_TEXT_RATIO = ContrastAnalysis.AA_NORMAL

    /** A focus ring sits outside the edge it decorates, so it needs weight the edge does not. */
    const val FOCUS_RING_WIDTH = 2f

    /**
     * The gap between a focus ring and the shape it surrounds.
     *
     * Non-zero so the ring is not mistaken for a thicker border on a shape that already has one — which
     * is the failure mode for all 39 outlined chrome sites, where a ring drawn flush would read as the
     * border changing weight rather than as focus arriving.
     */
    const val FOCUS_RING_GAP = 2f

    /**
     * How far *inside* its own bounds a ring sits when the chrome has no outside to use.
     *
     * **Why a second inset exists at all.** [FOCUS_RING_GAP] belongs to a ring drawn around chrome that
     * floats — a dock, a dialog, an inspector — which owns empty space on every side and can spend
     * [FOCUS_RING_WIDTH] plus the gap of it. Almost none of the app's focusable population is shaped like
     * that. A settings row is a flush band inside a card that clips it; a sidebar destination and a nav bar
     * destination are flush-packed siblings with nothing between them. For all three, a ring drawn outside
     * the bounds is drawn where the parent's clip deletes it, or on top of the neighbour. That is why the
     * substrate's outward ring shipped with zero consumers: it was a ring for the one shape the app almost
     * never has.
     *
     * So the ring comes inward instead, and inward is a different measurement from outward for one reason:
     * an outward ring can afford a gap *and* a stroke because the space is free, while an inward ring is
     * spending space the content is already using. Three dp is the smallest gap that still reads as a gap
     * rather than as the chrome's own border thickening — the distinction [FOCUS_RING_GAP] exists to
     * protect — and it lands well inside the 14 dp of horizontal padding a row already keeps clear, so the
     * ring never crowds the first character.
     *
     * **It is not a free number: it is [RESTING_EDGE_WIDTH] plus [FOCUS_RING_GAP].** The ring has to clear
     * whatever the container draws at its own boundary and *then* leave the gap, or the two read as one
     * thicker border. Since the substrate draws boundaries at exactly one weight, that sum is the inset, and
     * `ChromeGeometryTest` asserts the identity so a change to either input carries here instead of leaving
     * this constant silently wrong.
     *
     * **What makes it correct at the corners.** A ring inset by this much with a radius of
     * [focusRingRadius] puts its arc centre on exactly the same point as the container's own arc centre, so
     * the two curves are concentric and the gap is this value everywhere — on the straight edges and
     * through the corner alike. Get that wrong and the ring pinches at the corners or gets sliced by the
     * clip, either of which reads as a rendering fault rather than as focus. `ChromeGeometryTest` asserts
     * the concentricity rather than trusting this paragraph.
     *
     * **What it costs in layout: nothing.** This is the other half of why inward is right here. The
     * outward ring has to reserve its space unconditionally or focus would reflow the page; an inward ring
     * is drawn in space the chrome already occupies, so it needs no padding, changes no measurement, and
     * cannot move a sibling.
     */
    const val FOCUS_RING_INSET = 3f

    /**
     * The radius an inward focus ring takes inside a container whose own radius is [corner].
     *
     * Subtracting the inset is what makes the two curves concentric: a ring inset by
     * [FOCUS_RING_INSET] on every side has its arc centre at `inset + radius`, so choosing
     * `radius = R - inset` puts that centre at `R` — the container's own. Any other radius leaves a ring
     * that runs parallel to the straight edges and then converges or diverges through the corner.
     *
     * Floored at zero so a container with a radius smaller than the inset gets a square ring rather than
     * a negative one.
     */
    fun focusRingRadius(corner: Corner): Float = focusRingRadius(corner.radiusDp)

    /**
     * The same concentricity rule for a container whose radius is not one of the three tiers.
     *
     * The tiers cover chrome the substrate shapes. They cannot cover a **circle**, because a circle's
     * radius is half its own size — a 30 dp accent swatch needs 15 dp and a 46 dp avatar needs 23 dp, and no
     * fixed tier is right for both. Rather than invent a `Corner.Circle` that would be a lie at every size
     * but one, the arithmetic is exposed directly and the circular caller passes `size / 2`.
     */
    fun focusRingRadius(containerRadiusDp: Float): Float =
        (containerRadiusDp - FOCUS_RING_INSET).coerceAtLeast(0f)

    /**
     * The inset a ring needs inside a container that draws [boundaryWidthDp] at its own edge.
     *
     * [FOCUS_RING_INSET] answers this for the substrate's one edge weight. A few components draw something
     * heavier — a selected accent swatch carries a 2.5 dp border, a selected object edge carries
     * [SELECTED_EDGE_WIDTH] — and inside those the default inset would put the ring's outer edge half a dp
     * from the border's inner edge. Half a dp is not a gap at any density; it is the two strokes merging
     * into one heavier ring, which is the exact confusion [FOCUS_RING_GAP] exists to prevent, and it would
     * land on the component where the confusion costs most: one whose boundary *is* its selection signal.
     *
     * So the rule generalises rather than special-cases: clear the boundary, then leave the gap.
     */
    fun focusRingInsetOver(boundaryWidthDp: Float): Float =
        (boundaryWidthDp + FOCUS_RING_GAP).coerceAtLeast(FOCUS_RING_INSET)

    /**
     * How much of the surface tint Material paints onto an elevated surface at [elevationDp].
     *
     * `4.5·ln(dp+1) + 2`, over 100 — Material3's own curve, transcribed rather than invented, so that a
     * backdrop resolved here is the colour `Surface` actually paints. Zero at zero elevation, because
     * `Surface` short-circuits to plain `surface` there rather than compositing a 2% tint.
     *
     * **Why the substrate has to know this at all.** A focus ring is solved against the colour it is drawn
     * on. Material composites `surfaceTint` over `surface` for any elevated surface, and
     * `lightColorScheme`/`darkColorScheme` default `surfaceTint` to *the `primary` parameter* — not to the
     * baseline M3 token. `NorfoldTheme` never sets `surfaceTint`, so on this app the tint **is the user's
     * accent**, and every elevated surface is quietly painted with it. A ring solved against the declared
     * `surface` is then measured against a colour that is never painted, and it is measured against the one
     * colour guaranteed to be closest to the ring itself.
     *
     * That is not a rounding error. Across the ten shipped accents on the light page, a ring solved against
     * flat `surface` and drawn on the painted surface **fails 1.4.11's 3:1 for 2 of 10 accents at 1–4 dp** and
     * **3 of 10 at 6–10 dp**, where Forest joins them at **2.9965:1**. Which accent is worst depends on the
     * elevation, and Gold and Sunset interleave rather than crossing once — Gold at 1 dp, Sunset at 2 dp, Gold
     * at 3 and 4 dp, Sunset from 6 dp — with the floor at 2.6923:1. Solved against the painted colour instead,
     * **all ten pass at every elevation**. Dark mode passes either way, because its tint is the accent lifted
     * 35% toward white over a near-black surface.
     *
     * Forest's figure is quoted to four decimals for the reason `ChromeGeometryTest` records: at two decimals it
     * reads `3.00`, and this paragraph previously said "exactly 3.00:1" — a figure that describes a pass. It is
     * a failure by 0.0035. Same shape of error as the container populations documented as 3 of 10 that measure 4.
     *
     * The elevation to pass is the **accumulated** one: `Surface` reads `LocalAbsoluteTonalElevation`, adds
     * its own, and provides the sum to its content, so a 1 dp row inside a 6 dp sheet is drawn at 7 dp of
     * tint. Callers read that local rather than the literal they typed.
     */
    fun tonalTintAlpha(elevationDp: Float): Float =
        if (elevationDp <= 0f) 0f else ((4.5f * kotlin.math.ln(elevationDp + 1f)) + 2f) / 100f

    /**
     * Interior padding for a piece of chrome at each corner tier.
     *
     * Padding tracks the radius rather than being chosen beside it: content set closer to a corner than
     * its radius gets visibly clipped by the curve, so a 16 dp card cannot carry 6 dp padding without its
     * first character crowding the arc. These are the smallest values that clear their own curve.
     */
    fun paddingFor(corner: Corner): Float = when (corner) {
        Corner.Media -> 0f
        Corner.Card -> 14f
        Corner.Capsule -> 8f
    }
}
