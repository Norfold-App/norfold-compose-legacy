package com.norfold.app.editor.shape

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Boolean combination: Union, Subtract, Intersect and Exclude.
 *
 * Area is the assertion of choice because it is the one property that survives every legitimate
 * difference in how a result is expressed — ring order, start node, subdivision — while still failing
 * loudly when the combination is actually wrong. Ring counts pin the topology that area alone would miss.
 */
class ShapeBooleanTest {
    @Test
    fun overlappingSquaresCombineToTheExpectedAreasAndRingCounts() {
        val a = square(10f, 10f, 40f)
        val b = square(30f, 30f, 40f)

        val union = ShapeBooleans.combine(a, b, ShapeBooleanOperation.UNION)
        assertEquals(2 * SINGLE - OVERLAP, area(union.path), TOLERANCE)
        assertEquals(1, union.path.subpaths.size)
        assertEquals(ShapeBooleanStatus.RESOLVED, union.status)

        val intersect = ShapeBooleans.combine(a, b, ShapeBooleanOperation.INTERSECT)
        assertEquals(OVERLAP, area(intersect.path), TOLERANCE)
        assertEquals(1, intersect.path.subpaths.size)

        assertEquals(SINGLE - OVERLAP, area(ShapeBooleans.combine(a, b, ShapeBooleanOperation.SUBTRACT).path), TOLERANCE)

        val exclude = ShapeBooleans.combine(a, b, ShapeBooleanOperation.EXCLUDE)
        assertEquals(2 * (SINGLE - OVERLAP), area(exclude.path), TOLERANCE)
        assertEquals("exclude leaves the two disjoint corners", 2, exclude.path.subpaths.size)
    }

    @Test
    fun disjointOperandsUnionIntoTwoRingsAndIntersectIntoNothing() {
        val a = square(10f, 10f, 40f)
        val far = square(70f, 70f, 20f)

        val union = ShapeBooleans.combine(a, far, ShapeBooleanOperation.UNION)
        assertEquals(SINGLE + 400f, area(union.path), TOLERANCE)
        assertEquals(2, union.path.subpaths.size)

        // An empty intersection is reported, not silently returned as an empty shape the user cannot explain.
        val intersect = ShapeBooleans.combine(a, far, ShapeBooleanOperation.INTERSECT)
        assertEquals(ShapeBooleanStatus.CANCELLED, intersect.status)
        assertEquals(0, intersect.path.subpaths.size)
    }

    @Test
    fun subtractingAContainedOperandProducesARealHole() {
        val outer = square(10f, 10f, 80f)
        val inner = square(30f, 30f, 20f)
        val holed = ShapeBooleans.combine(outer, inner, ShapeBooleanOperation.SUBTRACT)

        assertEquals(2, holed.path.subpaths.size)
        assertEquals(6_400f - 400f, area(holed.path), TOLERANCE)
        val windings = ShapeCurves.flatten(holed.path).map { ShapeCurves.signedArea(it) }
        assertTrue("the hole must wind against the ring", windings.any { it < 0f } && windings.any { it > 0f })

        // The hole is the point: a fill rule that got the winding wrong would report this point as inside.
        assertTrue("the hole is empty", !ShapeCurves.contains(holed.path, ShapePoint(40f, 40f)))
        assertTrue("the ring is solid", ShapeCurves.contains(holed.path, ShapePoint(15f, 15f)))
    }

    @Test
    fun containedAndCoincidentOperandsDegradeHonestly() {
        val outer = square(10f, 10f, 80f)
        val inner = square(30f, 30f, 20f)

        assertEquals(6_400f, area(ShapeBooleans.combine(inner, outer, ShapeBooleanOperation.UNION).path), TOLERANCE)
        assertEquals(
            "subtracting a superset leaves nothing, and says so",
            ShapeBooleanStatus.CANCELLED,
            ShapeBooleans.combine(inner, outer, ShapeBooleanOperation.SUBTRACT).status,
        )

        val a = square(10f, 10f, 40f)
        assertEquals(SINGLE, area(ShapeBooleans.combine(a, square(10f, 10f, 40f), ShapeBooleanOperation.UNION).path), TOLERANCE)
        assertEquals(0f, area(ShapeBooleans.combine(a, square(10f, 10f, 40f), ShapeBooleanOperation.SUBTRACT).path), TOLERANCE)
    }

    @Test
    fun curvedOperandsCombineWithoutCollapsingOrDoubling() {
        val circleA = ShapePrimitives.generate(ShapePrimitiveKind.ELLIPSE, ShapeParameters())
        val circleB = circleA.mapPoints { ShapePoint(it.x + 40f, it.y) }
        val union = ShapeBooleans.combine(circleA, circleB, ShapeBooleanOperation.UNION)
        val one = area(circleA)
        val combined = area(union.path)

        assertEquals("two overlapping circles union into one ring", 1, union.path.subpaths.size)
        assertTrue("the union is larger than one circle: $combined vs $one", combined > one * 1.4f)
        assertTrue("the union is smaller than two circles: $combined vs ${one * 2f}", combined < one * 2f)
    }

    @Test
    fun groupsFoldLeftToRightAndSkipHiddenChildren() {
        val outer = square(10f, 10f, 80f)
        val group = ShapeBooleanGroup(
            operation = ShapeBooleanOperation.SUBTRACT,
            children = listOf(
                layer(outer),
                layer(square(30f, 30f, 20f)),
                layer(square(60f, 60f, 20f)),
            ),
        )
        val folded = ShapeBooleans.compute(group)
        assertEquals(3, folded.path.subpaths.size)
        assertEquals(6_400f - 800f, area(folded.path), TOLERANCE)
        assertEquals(3, folded.resolvedCount)

        // A hidden child is not an operand. Folding it anyway would make the eye and the geometry disagree.
        val withHidden = group.copy(children = group.children + ShapeLayer(visible = false))
        assertEquals(1, ShapeBooleans.compute(withHidden).skippedCount)
    }

    @Test
    fun aGroupWithFewerThanTwoOperandsPassesThroughUnchanged() {
        val outer = square(10f, 10f, 80f)
        val lone = ShapeBooleanGroup(children = listOf(layer(outer)))
        val computed = ShapeBooleans.compute(lone)
        assertEquals(ShapeBooleanStatus.PASSTHROUGH, computed.status)
        assertEquals(6_400f, area(computed.path), TOLERANCE)
        assertEquals(ShapeBooleanStatus.PASSTHROUGH, ShapeBooleans.compute(ShapeBooleanGroup()).status)
    }

    @Test
    fun combinationIsDeterministic() {
        val a = square(10f, 10f, 40f)
        val b = square(30f, 30f, 40f)
        assertEquals(
            ShapeCurves.flatten(ShapeBooleans.combine(a, b, ShapeBooleanOperation.UNION).path),
            ShapeCurves.flatten(ShapeBooleans.combine(a, b, ShapeBooleanOperation.UNION).path),
        )
    }

    private fun square(left: Float, top: Float, side: Float): ShapePath = ShapePath.of(
        ShapeSubpath.polygon(
            listOf(
                ShapePoint(left, top),
                ShapePoint(left + side, top),
                ShapePoint(left + side, top + side),
                ShapePoint(left, top + side),
            ),
        ),
    )

    private fun layer(path: ShapePath) = ShapeLayer(content = ShapePathLayer(path))

    /** Unsigned, because a correct result may wind either way depending on which operand led. */
    private fun area(path: ShapePath): Float =
        abs(ShapeCurves.flatten(path).sumOf { ShapeCurves.signedArea(it).toDouble() }.toFloat())

    private companion object {
        const val SINGLE = 1_600f
        const val OVERLAP = 400f

        /** Flattening noise on a curve-capable path, not slack in the combination itself. */
        const val TOLERANCE = 0.5f
    }
}
