package com.norfold.app.editor.motion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Coverage for the editor's motion policy. These are the guarantees that let a reviewer trust a call
 * site that says only "this is a surface transition":
 *
 *  - reduced motion removes the path but never the end state, for every level;
 *  - a precision surface can never be handed an overshoot;
 *  - a duration outside a level's band is corrected rather than accepted.
 *
 * The layer is pure Kotlin precisely so these run on the desktop JVM, where the device-dependent parts
 * of this pass cannot.
 */
class MotionSemanticsTest {

    @Test
    fun `every level collapses to an immediate change under reduced motion`() {
        MotionLevel.entries.forEach { level ->
            val plan = MotionSemantics.plan(
                level = level,
                property = MotionProperty.GEOMETRY,
                reduceMotion = true,
            )
            assertEquals(MotionSemantics.ImmediateMillis, plan.durationMillis)
            assertEquals(MotionCurve.LINEAR, plan.curve)
            assertTrue("$level should report itself immediate", plan.isImmediate)
        }
    }

    @Test
    fun `reduced motion keeps a short fade for properties that do not travel`() {
        listOf(MotionProperty.OPACITY, MotionProperty.COLOR).forEach { property ->
            val plan = MotionSemantics.plan(
                level = MotionLevel.SPATIAL,
                property = property,
                reduceMotion = true,
            )
            assertEquals(MotionSemantics.ReducedFadeMillis, plan.durationMillis)
            assertEquals(MotionCurve.LINEAR, plan.curve)
            assertFalse("a 90ms fade is still a fade", plan.isImmediate)
        }
    }

    @Test
    fun `reduced motion drops the delay so a stagger is not merely slowed`() {
        val plan = MotionSemantics.plan(
            level = MotionLevel.STATE,
            property = MotionProperty.OPACITY,
            delayMillis = 280,
            reduceMotion = true,
        )
        assertEquals(0, plan.delayMillis)
        assertEquals(MotionSemantics.ReducedFadeMillis, plan.totalMillis)
    }

    @Test
    fun `a precision surface never overshoots`() {
        val plan = MotionSemantics.plan(
            level = MotionLevel.STATE,
            surface = MotionSurfaceClass.PRECISION,
            curve = MotionCurve.OVERSHOOT,
        )
        assertEquals(MotionCurve.DECELERATE, plan.curve)
        assertFalse(plan.curve.overshoots)
    }

    @Test
    fun `overshoot survives on surfaces where it is a flourish rather than a measurement`() {
        MotionSurfaceClass.entries.filterNot { it.precision }.forEach { surface ->
            val plan = MotionSemantics.plan(
                level = MotionLevel.MICRO,
                surface = surface,
                curve = MotionCurve.OVERSHOOT,
            )
            assertEquals("$surface should keep the requested overshoot", MotionCurve.OVERSHOOT, plan.curve)
        }
    }

    @Test
    fun `an out of band duration is corrected into the level it was asked for`() {
        val tooSlow = MotionSemantics.plan(level = MotionLevel.MICRO, durationMillis = 900)
        assertEquals(MotionLevel.MICRO.maxMillis, tooSlow.durationMillis)

        val tooFast = MotionSemantics.plan(level = MotionLevel.SPATIAL, durationMillis = 40)
        assertEquals(MotionLevel.SPATIAL.minMillis, tooFast.durationMillis)
    }

    @Test
    fun `default durations sit inside the documented bands and increase with scope`() {
        val ordered = listOf(MotionLevel.MICRO, MotionLevel.STATE, MotionLevel.SURFACE, MotionLevel.SPATIAL)
        assertEquals("the levels are declared narrowest first", ordered, MotionLevel.entries.toList())
        ordered.forEach { level ->
            assertTrue(
                "$level default ${level.defaultMillis} is outside ${level.minMillis}..${level.maxMillis}",
                level.defaultMillis in level.minMillis..level.maxMillis,
            )
        }
        ordered.zipWithNext { narrower, wider ->
            assertTrue(
                "${wider.name} should not be quicker than ${narrower.name}",
                wider.defaultMillis > narrower.defaultMillis,
            )
        }
    }

    @Test
    fun `no level is quick enough to be missed or slow enough to block interaction`() {
        MotionLevel.entries.forEach { level ->
            assertTrue("$level is too brief to read", level.minMillis >= 80)
            assertTrue("$level is long enough to feel like a wait", level.maxMillis <= 500)
        }
    }

    @Test
    fun `each level has a default curve and only one of them overshoots`() {
        MotionLevel.entries.forEach { level ->
            assertFalse(
                "$level should not overshoot by default",
                MotionSemantics.defaultCurve(level).overshoots,
            )
        }
        assertEquals(
            listOf(MotionCurve.OVERSHOOT),
            MotionCurve.entries.filter { it.overshoots },
        )
    }

    @Test
    fun `the linear curve is the identity so a collapsed plan cannot ease`() {
        val linear = MotionCurve.LINEAR
        assertEquals(0f, linear.a, 0f)
        assertEquals(0f, linear.b, 0f)
        assertEquals(1f, linear.c, 0f)
        assertEquals(1f, linear.d, 0f)
    }
}
