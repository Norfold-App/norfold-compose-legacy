package com.norfold.app.editor.shape

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Node, handle, snap and primitive geometry in the local shape canvas.
 *
 * The recurring assertion is that an edit preserves the outline it claims not to change. Sampling two
 * outlines and comparing distances only resolves to the precision of the sampled nearest-point search,
 * which is coarser than the error worth catching, so these compare control points: they *are* the curve.
 */
class ShapeGeometryTest {
    @Test
    fun addPointSplitsTheSegmentExactlyAndLeavesEveryOtherSegmentAlone() {
        val base = circle()
        val subpath = base.subpaths.first()
        val before = subpath.segments()
        for (t in listOf(0.13f, 0.25f, 0.5f, 0.62f, 0.87f)) {
            val split = ShapePathEditor.insertNode(base, subpath.id, 0, t)
            assertEquals("t=$t adds exactly one node", base.nodeCount + 1, split.nodeCount)
            val after = split.subpaths.first().segments()
            val (left, right) = before[0].splitAt(t)
            assertSegmentEquals("t=$t left half", left, after[0])
            assertSegmentEquals("t=$t right half", right, after[1])
            for (i in 1 until before.size) assertSegmentEquals("t=$t segment $i", before[i], after[i + 1])
        }
    }

    @Test
    fun repeatedInsertsStayExactRatherThanAccumulatingDrift() {
        val base = circle()
        val before = base.subpaths.first().segments()
        val baseArea = absoluteArea(base)
        var repeated = base
        repeat(6) { repeated = ShapePathEditor.insertNode(repeated, repeated.subpaths.first().id, 0, 0.4f) }
        assertEquals(base.nodeCount + 6, repeated.nodeCount)

        // Area is the blunt cross-check, and the tolerance is flattening noise: adding a node changes where
        // the adaptive subdivision lands, which moves the sampled polygon slightly without moving the curve.
        assertEquals(baseArea, absoluteArea(repeated), 2f)

        // Splitting segment 0 never touches the tail of the ring, so it must still be identical.
        val after = repeated.subpaths.first().segments()
        for (i in 1 until before.size) {
            assertSegmentEquals("tail segment $i", before[i], after[after.size - before.size + i])
        }
    }

    @Test
    fun splittingBesideSymmetricNodesRelaxesThemWithoutMovingTheirFarSides() {
        val path = symmetricDiamond()
        val subpath = path.subpaths.first()
        val before = subpath.segments()
        val split = ShapePathEditor.insertNode(path, subpath.id, 1, 0.3f)
        val after = split.subpaths.first().segments()
        val (left, right) = before[1].splitAt(0.3f)
        assertSegmentEquals("left half", left, after[1])
        assertSegmentEquals("right half", right, after[2])

        // The whole point of relaxing the neighbours: the segments on their far sides must not be dragged in.
        assertSegmentEquals("far side of the previous node", before[0], after[0])
        assertSegmentEquals("far side of the next node", before[2], after[3])
        val types = split.subpaths.first().nodes.map { it.type }
        assertEquals(ShapeNodeType.SMOOTH, types[1])
        assertEquals(ShapeNodeType.SMOOTH, types[3])
    }

    @Test
    fun handleConstraintsHoldForCornerSmoothAndSymmetricNodes() {
        val path = triangle()
        val ref = ShapeNodeRef(path.subpaths.first().id, "a")

        val corner = ShapePathEditor.moveHandle(path, ref, ShapeHandleSide.OUT, ShapePoint(40f, 40f))
        val cornerNode = corner.subpathById(ref.subpathId)!!.nodeById("a")!!
        assertEquals("a corner keeps its other handle", -8f, cornerNode.controlIn.x, 1e-4f)
        assertEquals(0f, cornerNode.controlIn.y, 1e-4f)

        val symmetric = ShapePathEditor.mirrorNode(path, ref)
        val draggedOut = ShapePathEditor.moveHandle(symmetric, ref, ShapeHandleSide.OUT, ShapePoint(40f, 40f))
            .subpathById(ref.subpathId)!!.nodeById("a")!!
        assertEquals(-draggedOut.controlOut.x, draggedOut.controlIn.x, 1e-3f)
        assertEquals(-draggedOut.controlOut.y, draggedOut.controlIn.y, 1e-3f)

        // The IN drag is the case that has to survive normalization deriving controlIn from controlOut.
        val draggedIn = ShapePathEditor.moveHandle(symmetric, ref, ShapeHandleSide.IN, ShapePoint(22f, 44f))
            .subpathById(ref.subpathId)!!.nodeById("a")!!
        assertEquals(22f - 30f, draggedIn.controlIn.x, 1e-3f)
        assertEquals(44f - 50f, draggedIn.controlIn.y, 1e-3f)

        val smooth = ShapePathEditor.setNodeType(path, ref, ShapeNodeType.SMOOTH)
        val smoothNode = ShapePathEditor.moveHandle(smooth, ref, ShapeHandleSide.IN, ShapePoint(20f, 60f))
            .subpathById(ref.subpathId)!!.nodeById("a")!!
        assertEquals(20f - 30f, smoothNode.controlIn.x, 1e-3f)
        assertEquals(60f - 50f, smoothNode.controlIn.y, 1e-3f)
        assertEquals("smooth preserves the opposite handle's length", 8f, smoothNode.controlOut.length, 1e-3f)
        val cross = smoothNode.controlIn.x * smoothNode.controlOut.y - smoothNode.controlIn.y * smoothNode.controlOut.x
        assertTrue("smooth handles stay collinear, cross=$cross", abs(cross) < 1e-2f)
    }

    @Test
    fun nodeTypeActionsRetractRoundAndRefuseToLieAboutNoOps() {
        val path = triangle()
        val refA = ShapeNodeRef(path.subpaths.first().id, "a")
        val refB = ShapeNodeRef(path.subpaths.first().id, "b")

        val smoothed = ShapePathEditor.setNodeType(path, refA, ShapeNodeType.SMOOTH)
        val sharpened = ShapePathEditor.sharpenNode(smoothed, refA)
        assertTrue(sharpened.subpathById(refA.subpathId)!!.nodeById("a")!!.isSharp)

        val rounded = ShapePathEditor.roundNode(path, refB).subpathById(refB.subpathId)!!.nodeById("b")!!
        assertFalse("round gives a retracted node real handles", rounded.isSharp)
        assertEquals(ShapeNodeType.SYMMETRIC, rounded.type)

        // Smoothing a fully retracted node has nothing to work with. A documented no-op beats a button
        // that reports success and changes nothing.
        assertEquals(path, ShapePathEditor.smoothNode(path, refB))
    }

    @Test
    fun deleteMoveAndTopologyEditsKeepTheRingCoherent() {
        val path = triangle()
        val subpathId = path.subpaths.first().id
        val refA = ShapeNodeRef(subpathId, "a")

        assertEquals(2, ShapePathEditor.deleteNode(path, ShapeNodeRef(subpathId, "b")).nodeCount)
        val collapsed = ShapePathEditor.deleteNodes(
            path,
            listOf(ShapeNodeRef(subpathId, "b"), ShapeNodeRef(subpathId, "c")),
        )
        assertTrue("deleting past two nodes drops the subpath", collapsed.subpaths.isEmpty())

        val moved = ShapePathEditor.moveNode(path, refA, ShapePoint(35f, 55f))
            .subpathById(subpathId)!!.nodeById("a")!!
        assertEquals(35f, moved.point.x, 1e-4f)
        assertEquals("moving a node carries its handles", -8f, moved.controlIn.x, 1e-4f)

        val refs = path.subpaths.first().nodes.map { ShapeNodeRef(subpathId, it.id) }
        val shifted = ShapePathEditor.moveNodesBy(path, refs, ShapePoint(5f, 5f))
        shifted.subpaths.first().nodes.zip(path.subpaths.first().nodes).forEach { (after, before) ->
            assertEquals("each node moves exactly once", before.point.x + 5f, after.point.x, 1e-4f)
            assertEquals(before.point.y + 5f, after.point.y, 1e-4f)
        }

        val opened = ShapePathEditor.openSubpath(path, subpathId, "a")
        assertFalse(opened.subpaths.first().closed)
        assertEquals("the cut rotates to the ends", "a", opened.subpaths.first().nodes.last().id)
        assertEquals(path.nodeCount, opened.nodeCount)
        val reclosed = ShapePathEditor.closeSubpath(opened, opened.subpaths.first().id)
        assertEquals(path.subpaths.first().segments().size, reclosed.subpaths.first().segments().size)

        val reversed = ShapePathEditor.reverseSubpath(path, subpathId)
        assertEquals("c", reversed.subpaths.first().nodes.first().id)
        assertEquals(8f, reversed.subpathById(subpathId)!!.nodeById("a")!!.controlIn.x, 1e-4f)
        assertTrue("reversing flips the winding", signedArea(reversed) * signedArea(path) < 0f)

        val begun = ShapePathEditor.beginSubpath(path, ShapePoint(10f, 10f))
        assertEquals(path.subpaths.size + 1, begun.subpaths.size)
        assertEquals(2, ShapePathEditor.appendNode(begun, begun.subpaths.last().id, ShapePoint(20f, 10f)).subpaths.last().nodes.size)
    }

    @Test
    fun hitTestingFindsNodesHandlesAndOutlinesWithoutShadowingThem() {
        val path = triangle()
        assertTrue(ShapePathEditor.hitTest(path, ShapePoint(30.5f, 50.2f)) is ShapeHit.Node)
        assertTrue(ShapePathEditor.hitTest(path, ShapePoint(38.2f, 50f)) is ShapeHit.Handle)
        assertTrue(ShapePathEditor.hitTest(path, ShapePoint(50f, 50.4f)) is ShapeHit.Segment)
        assertTrue(ShapePathEditor.hitTest(path, ShapePoint(5f, 95f)) is ShapeHit.None)

        // A retracted handle sits exactly on its node, so it must not take the tap meant for the node.
        val sharp = ShapePath.of(
            ShapeSubpath.polygon(listOf(ShapePoint(30f, 30f), ShapePoint(70f, 30f), ShapePoint(50f, 70f))),
        )
        assertNull(ShapePathEditor.nearestHandle(sharp, ShapePoint(30f, 30f)))
        assertTrue(ShapePathEditor.hitTest(sharp, ShapePoint(30f, 30f)) is ShapeHit.Node)

        val marquee = ShapePathEditor.nodesIn(path, ShapeRect(0f, 0f, 60f, 60f))
        assertEquals(listOf("a"), marquee.map { it.nodeId })

        assertEquals("adding a point in open space is a no-op", path, ShapePathEditor.insertNodeNear(path, ShapePoint(5f, 95f)))
        assertEquals(path.nodeCount + 1, ShapePathEditor.insertNodeNear(path, ShapePoint(50f, 50.3f)).nodeCount)
    }

    @Test
    fun snappingPrefersCanvasGeometryOverTheGridAndReportsWhatItUsed() {
        val path = triangle()
        val canvas = ShapeNodeSnap.snap(ShapePoint(0.4f, 49.6f), path)
        assertEquals(0f, canvas.point.x, 1e-4f)
        assertEquals(50f, canvas.point.y, 1e-4f)
        assertEquals(2, canvas.guides.size)
        assertTrue(
            "a canvas edge outranks the grid, kinds=${canvas.guides.map { it.kind }}",
            canvas.guides.any { it.kind == ShapeNodeSnap.Kind.CANVAS_EDGE },
        )

        val sibling = ShapeNodeSnap.snap(ShapePoint(69.4f, 20.2f), path, grid = false)
        assertEquals(70f, sibling.point.x, 1e-4f)
        assertTrue(sibling.guides.any { it.kind == ShapeNodeSnap.Kind.NODE })

        // The node being dragged is excluded, or it would snap to where it already is.
        val excluded = ShapeNodeSnap.snap(
            ShapePoint(69.4f, 20.2f),
            path,
            exclude = listOf(ShapeNodeRef(path.subpaths.first().id, "b")),
            grid = false,
        )
        assertEquals(69.4f, excluded.point.x, 1e-4f)

        val unsnapped = ShapeNodeSnap.snap(ShapePoint(37f, 63f), path, grid = false)
        assertTrue("no guide is drawn without a snap", unsnapped.guides.isEmpty())
        assertEquals(37f, unsnapped.point.x, 1e-4f)
        assertEquals(63f, unsnapped.point.y, 1e-4f)

        val grid = ShapeNodeSnap.snap(ShapePoint(30.6f, 19.5f), ShapePath())
        assertEquals(30f, grid.point.x, 1e-4f)
        assertEquals(20f, grid.point.y, 1e-4f)
    }

    @Test
    fun constrainedDraggingLocksToHorizontalVerticalAndDiagonal() {
        val origin = ShapePoint(50f, 50f)
        val horizontal = ShapeNodeSnap.constrain(origin, ShapePoint(70f, 52f))
        assertEquals(70f, horizontal.x, 1e-4f)
        assertEquals(50f, horizontal.y, 1e-4f)

        val vertical = ShapeNodeSnap.constrain(origin, ShapePoint(52f, 70f))
        assertEquals(50f, vertical.x, 1e-4f)
        assertEquals(70f, vertical.y, 1e-4f)

        val diagonal = ShapeNodeSnap.constrain(origin, ShapePoint(70f, 68f))
        assertEquals(diagonal.x - 50f, diagonal.y - 50f, 1e-3f)
    }

    @Test
    fun generatedIdentityIsAFunctionOfTheDescriptionNotOfWhenItWasAskedFor() {
        // A parametric layer regenerates its path on every read. Ids minted per call would make a node ref
        // produced by a tap already stale by the time the selection is validated against the document.
        val first = ShapePrimitives.generate(ShapePrimitiveKind.POLYGON, ShapeParameters(pointCount = 6))
        val second = ShapePrimitives.generate(ShapePrimitiveKind.POLYGON, ShapeParameters(pointCount = 6))
        assertEquals(first, second)
        assertEquals(
            "ids are unique within a path",
            first.nodeCount,
            first.subpaths.flatMap { s -> s.nodes.map { "${s.id}/${it.id}" } }.toSet().size,
        )
        first.subpaths.forEach { subpath ->
            subpath.nodes.forEach { node ->
                assertNotNull(
                    "a ref from one generate resolves against the next",
                    second.subpathById(subpath.id)?.nodeById(node.id),
                )
            }
        }
    }

    @Test
    fun editsAreDeterministicAndBadReferencesAreNoOps() {
        val base = circle()
        val subpathId = base.subpaths.first().id
        assertEquals(
            ShapeCurves.flatten(ShapePathEditor.insertNode(base, subpathId, 0, 0.31f)),
            ShapeCurves.flatten(ShapePathEditor.insertNode(base, subpathId, 0, 0.31f)),
        )

        val path = triangle()
        val id = path.subpaths.first().id
        assertEquals(path, ShapePathEditor.insertNode(path, id, 99, 0.5f))
        assertEquals(path, ShapePathEditor.insertNode(path, "nope", 0, 0.5f))
        assertEquals(path, ShapePathEditor.moveNode(path, ShapeNodeRef(id, "zz"), ShapePoint(1f, 1f)))
        assertEquals(path, ShapePathEditor.moveNode(path, ShapeNodeRef("nope", "a"), ShapePoint(1f, 1f)))
        assertEquals(path, ShapePathEditor.moveNodesBy(path, emptyList(), ShapePoint(9f, 9f)))
    }

    private fun circle(): ShapePath = ShapePrimitives.generate(ShapePrimitiveKind.ELLIPSE, ShapeParameters())

    private fun triangle(): ShapePath = ShapePath.of(
        ShapeSubpath(
            nodes = listOf(
                ShapeNode(
                    id = "a", point = ShapePoint(30f, 50f),
                    controlIn = ShapePoint(-8f, 0f), controlOut = ShapePoint(8f, 0f),
                    type = ShapeNodeType.CORNER,
                ),
                ShapeNode(id = "b", point = ShapePoint(70f, 50f), type = ShapeNodeType.CORNER),
                ShapeNode(id = "c", point = ShapePoint(50f, 80f), type = ShapeNodeType.CORNER),
            ),
        ),
    )

    private fun symmetricDiamond(): ShapePath = ShapePath.of(
        ShapeSubpath(
            nodes = listOf(
                node(ShapePoint(20f, 50f), ShapePoint(0f, 12f), ShapePoint(0f, -12f)),
                node(ShapePoint(50f, 20f), ShapePoint(-12f, 0f), ShapePoint(12f, 0f)),
                node(ShapePoint(80f, 50f), ShapePoint(0f, -12f), ShapePoint(0f, 12f)),
                node(ShapePoint(50f, 80f), ShapePoint(12f, 0f), ShapePoint(-12f, 0f)),
            ),
        ),
    ).normalized()

    private fun node(point: ShapePoint, controlIn: ShapePoint, controlOut: ShapePoint) =
        ShapeNode(point = point, controlIn = controlIn, controlOut = controlOut, type = ShapeNodeType.SYMMETRIC)

    private fun signedArea(path: ShapePath): Float =
        ShapeCurves.flatten(path).sumOf { ShapeCurves.signedArea(it).toDouble() }.toFloat()

    private fun absoluteArea(path: ShapePath): Float =
        ShapeCurves.flatten(path).sumOf { abs(ShapeCurves.signedArea(it).toDouble()) }.toFloat()

    private fun assertSegmentEquals(label: String, expected: ShapeSegment, actual: ShapeSegment) {
        assertEquals("$label start.x", expected.start.x, actual.start.x, TOLERANCE)
        assertEquals("$label start.y", expected.start.y, actual.start.y, TOLERANCE)
        assertEquals("$label control1.x", expected.control1.x, actual.control1.x, TOLERANCE)
        assertEquals("$label control1.y", expected.control1.y, actual.control1.y, TOLERANCE)
        assertEquals("$label control2.x", expected.control2.x, actual.control2.x, TOLERANCE)
        assertEquals("$label control2.y", expected.control2.y, actual.control2.y, TOLERANCE)
        assertEquals("$label end.x", expected.end.x, actual.end.x, TOLERANCE)
        assertEquals("$label end.y", expected.end.y, actual.end.y, TOLERANCE)
    }

    private companion object {
        const val TOLERANCE = 1e-3f
    }
}
