package com.norfold.app.ui.components

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.TweenSpec
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import com.norfold.app.editor.motion.MotionCurve
import com.norfold.app.editor.motion.MotionLevel
import com.norfold.app.editor.motion.MotionPlan
import com.norfold.app.editor.motion.MotionProperty
import com.norfold.app.editor.motion.MotionSemantics
import com.norfold.app.editor.motion.MotionSurfaceClass

/**
 * The device's reduce-motion preference, provided once by the theme so no screen has to thread it
 * down. It is a `static` local because it changes only when the user changes the setting, and a
 * recomposition of every reader is exactly what should happen then.
 *
 * Defaulting to `false` is deliberate: a composable previewed or tested outside the theme animates
 * normally, which is the state a developer is trying to look at.
 */
val LocalReduceMotion = staticCompositionLocalOf { false }

private val EmphasizedEasing = CubicBezierEasing(0.32f, 0.72f, 0f, 1f)
private val DecelerateEasing = CubicBezierEasing(0.16f, 1f, 0.3f, 1f)
private val CaretEasing = CubicBezierEasing(0.2f, 0.9f, 0.2f, 1f)
private val OvershootEasing = CubicBezierEasing(0.34f, 1.56f, 0.64f, 1f)

/** The Compose easing for a semantic curve. Instances are shared rather than allocated per frame. */
val MotionCurve.easing: Easing
    get() = when (this) {
        MotionCurve.EMPHASIZED -> EmphasizedEasing
        MotionCurve.DECELERATE -> DecelerateEasing
        MotionCurve.CARET -> CaretEasing
        MotionCurve.OVERSHOOT -> OvershootEasing
        MotionCurve.LINEAR -> LinearEasing
    }

/** Turns a resolved plan into an animation spec. Every policy decision is already baked in. */
fun <T> MotionPlan.spec(): TweenSpec<T> =
    tween(durationMillis = durationMillis, delayMillis = delayMillis, easing = curve.easing)

/**
 * The editor's animation vocabulary. Every editor animation goes through here, which is what makes
 * the reduce-motion guarantee hold: the setting is read once, in one place, and a call site cannot
 * opt out of it by forgetting.
 *
 * Callers describe meaning, not numbers:
 *
 * ```
 * val alpha by animateFloatAsState(
 *     targetValue = if (visible) 1f else 0f,
 *     animationSpec = NorfoldMotion.spec(
 *         level = MotionLevel.STATE,
 *         property = MotionProperty.OPACITY,
 *         surface = MotionSurfaceClass.CHROME,
 *     ),
 * )
 * ```
 */
object NorfoldMotion {
    /** Resolves a plan against the current reduce-motion preference. */
    @Composable
    @ReadOnlyComposable
    fun plan(
        level: MotionLevel,
        property: MotionProperty = MotionProperty.COMPOSITE,
        surface: MotionSurfaceClass = MotionSurfaceClass.CHROME,
        curve: MotionCurve = MotionSemantics.defaultCurve(level),
        durationMillis: Int? = null,
        delayMillis: Int = 0,
    ): MotionPlan = MotionSemantics.plan(
        level = level,
        property = property,
        surface = surface,
        curve = curve,
        durationMillis = durationMillis,
        delayMillis = delayMillis,
        reduceMotion = LocalReduceMotion.current,
    )

    /** The plan as an animation spec, for the common case of feeding `animate*AsState` directly. */
    @Composable
    @ReadOnlyComposable
    fun <T> spec(
        level: MotionLevel,
        property: MotionProperty = MotionProperty.COMPOSITE,
        surface: MotionSurfaceClass = MotionSurfaceClass.CHROME,
        curve: MotionCurve = MotionSemantics.defaultCurve(level),
        durationMillis: Int? = null,
        delayMillis: Int = 0,
    ): TweenSpec<T> = plan(level, property, surface, curve, durationMillis, delayMillis).spec()

    /**
     * True when motion is suppressed. Read this only to choose a *different composition* — an
     * immediate swap instead of a slide, say. Do not use it to hand-roll a shorter duration; that is
     * what [plan] is for, and a second answer to the same question is how the app drifts.
     */
    val isReduced: Boolean
        @Composable @ReadOnlyComposable get() = LocalReduceMotion.current
}
