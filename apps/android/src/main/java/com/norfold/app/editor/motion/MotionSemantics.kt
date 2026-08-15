package com.norfold.app.editor.motion

/**
 * The editor's motion vocabulary as pure data, so it can be reasoned about and tested without a
 * device, a Compose runtime, or a screenshot.
 *
 * Why this is a layer rather than `tween(...)` literals at each call site:
 *
 *  1. **Reduced motion has to be structural.** `reduceMotion` is a persisted device setting the app
 *     shell already honours, and no file under `editor/` honoured it. Answering that with a
 *     conditional at each animation site guarantees the next animation forgets. Here the collapse is
 *     the one function every editor animation passes through, so forgetting is not reachable.
 *  2. **Some curves are wrong for some things.** Overshoot on a node handle, a ruler, or a page
 *     metric reads as inaccuracy: the geometry visibly passes the value the user asked for and comes
 *     back. That ban is encoded in [MotionSemantics.plan] instead of trusted to reviewers.
 *  3. **A level is a claim about meaning.** "This is a surface transition" is reviewable. "This is
 *     `tween(320)`" is not.
 *
 * Durations come from the motion-language reference: micro response ~80-140ms, state change
 * ~160-240ms, surface transition ~260-400ms, spatial transition ~300-500ms.
 */
enum class MotionLevel(val minMillis: Int, val defaultMillis: Int, val maxMillis: Int) {
    /** Press, selection, checkbox/toggle, snap acquisition, small icon state. */
    MICRO(minMillis = 80, defaultMillis = 120, maxMillis = 140),

    /** Inspector sections, context menus, selected-object chrome, tabs, local tool modes. */
    STATE(minMillis = 160, defaultMillis = 200, maxMillis = 240),

    /** Dock to inspector, object to focus tool, menu to expanded controls, sheet detents. */
    SURFACE(minMillis = 260, defaultMillis = 320, maxMillis = 400),

    /** Page navigation, navigator jumps, focus-mode entry and exit, fit-to-selection. */
    SPATIAL(minMillis = 300, defaultMillis = 380, maxMillis = 500),
    ;

    /**
     * Coerces a caller's explicit duration into this level's band rather than silently accepting it.
     * A caller who wants 900ms is not asking for a slow surface transition, they are asking for a
     * spatial one, and the level should change instead of the number.
     */
    fun clamp(durationMillis: Int): Int = durationMillis.coerceIn(minMillis, maxMillis)
}

/**
 * Curves named for what they do rather than for their control points. The control points are the ones
 * the motion studies actually used, ranked by how often they appeared, so the editor inherits one
 * house feel instead of four unrelated bezier guesses.
 */
enum class MotionCurve(val a: Float, val b: Float, val c: Float, val d: Float) {
    /** The default: decisive start, long tail. The study set's most-used curve. */
    EMPHASIZED(0.32f, 0.72f, 0f, 1f),

    /** Arrives and settles with no visible ramp-in. For content entering from off-surface. */
    DECELERATE(0.16f, 1f, 0.3f, 1f),

    /** The caret-origin curve: what a surface growing from the caret and the IME dock both use. */
    CARET(0.2f, 0.9f, 0.2f, 1f),

    /** Overshoots by design. Allowed for emphasis, never for geometry a user is reading a value off. */
    OVERSHOOT(0.34f, 1.56f, 0.64f, 1f),

    /** No easing. What reduced motion collapses to, and the only honest curve for a 1ms change. */
    LINEAR(0f, 0f, 1f, 1f),
    ;

    val overshoots: Boolean get() = this == OVERSHOOT
}

/**
 * What is moving. The only behavioural distinction here is whether the thing carries a value the user
 * is reading off the screen, because that is exactly what turns overshoot from a flourish into a
 * defect.
 */
enum class MotionSurfaceClass(val precision: Boolean) {
    /** Blocks, cards, rows, sheets: authored content and its containers. */
    CONTENT(precision = false),

    /** Toolbars, rails, docks, menus, inspectors: the controls around the content. */
    CHROME(precision = false),

    /** Selection outlines, multi-select chrome, focus rings. */
    SELECTION(precision = false),

    /** Chart, diagram, image and import previews, including their recompute states. */
    PREVIEW(precision = false),

    /** Nodes, handles, rulers, snap guides, page metrics, chart axes, crop rectangles. */
    PRECISION(precision = true),
}

/**
 * Which property is animating. Under reduced motion an opacity or colour change may still cross-fade
 * briefly, because it conveys no travel; anything geometric becomes an immediate bounds update. That
 * split is the reference checklist's own wording: spatial travel may become a short fade or an
 * immediate bounds update.
 */
enum class MotionProperty { OPACITY, COLOR, GEOMETRY, COMPOSITE }

/**
 * A resolved animation: the numbers a renderer needs, with every policy decision already applied.
 * Nothing downstream re-checks reduced motion or re-picks a curve, so a plan is safe to pass around.
 */
data class MotionPlan(
    val durationMillis: Int,
    val curve: MotionCurve,
    val delayMillis: Int = 0,
) {
    /** True when this plan is a state change rather than a motion — the reduced-motion outcome. */
    val isImmediate: Boolean get() = durationMillis <= MotionSemantics.ImmediateMillis

    val totalMillis: Int get() = durationMillis + delayMillis
}

object MotionSemantics {
    /**
     * Not zero. A zero-duration animation is a separate code path in several animation APIs and in at
     * least one of them it means "never finish"; 1ms is the smallest value that is unambiguously an
     * instant, completed transition. The reference studies collapse to `.001ms` for the same reason.
     */
    const val ImmediateMillis: Int = 1

    /**
     * Reduced motion keeps a short cross-fade for properties that do not travel. 90ms is the value the
     * app shell already uses for its own reduced-motion fade, so the editor does not introduce a
     * second answer to the same question.
     */
    const val ReducedFadeMillis: Int = 90

    /**
     * The single entry point. Callers state what the animation *means*; this decides what it does.
     *
     * Under [reduceMotion] the end state is unchanged — only the path to it is removed. That is the
     * requirement the acceptance checklist states as "it preserves the same state transition" and
     * "nothing requires watching motion to understand what happened".
     */
    fun plan(
        level: MotionLevel,
        property: MotionProperty = MotionProperty.COMPOSITE,
        surface: MotionSurfaceClass = MotionSurfaceClass.CHROME,
        curve: MotionCurve = defaultCurve(level),
        durationMillis: Int? = null,
        delayMillis: Int = 0,
        reduceMotion: Boolean = false,
    ): MotionPlan {
        if (reduceMotion) {
            val fades = property == MotionProperty.OPACITY || property == MotionProperty.COLOR
            // The delay goes too: a staggered reveal under reduced motion is still a reveal the user
            // has to wait through, and the stagger carried the meaning the fade no longer needs to.
            return MotionPlan(
                durationMillis = if (fades) ReducedFadeMillis else ImmediateMillis,
                curve = MotionCurve.LINEAR,
                delayMillis = 0,
            )
        }
        return MotionPlan(
            durationMillis = level.clamp(durationMillis ?: level.defaultMillis),
            curve = resolveCurve(curve, surface),
            delayMillis = delayMillis.coerceAtLeast(0),
        )
    }

    /**
     * The curve a level uses when a caller does not care. Surface transitions get the caret curve
     * because that is what the surface-growth studies used; spatial transitions decelerate because
     * they arrive from somewhere off-screen rather than growing out of a control.
     */
    fun defaultCurve(level: MotionLevel): MotionCurve = when (level) {
        MotionLevel.MICRO -> MotionCurve.EMPHASIZED
        MotionLevel.STATE -> MotionCurve.EMPHASIZED
        MotionLevel.SURFACE -> MotionCurve.CARET
        MotionLevel.SPATIAL -> MotionCurve.DECELERATE
    }

    /**
     * Substitutes a settling curve when a caller asks a precision surface to overshoot. This is a
     * silent correction on purpose: the alternative is throwing at runtime on a device, in a draw
     * path, over a styling choice. The substitution is asserted by test instead.
     */
    fun resolveCurve(curve: MotionCurve, surface: MotionSurfaceClass): MotionCurve =
        if (curve.overshoots && surface.precision) MotionCurve.DECELERATE else curve
}
