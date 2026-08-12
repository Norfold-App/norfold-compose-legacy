package com.norfold.app.editor.shape

import com.norfold.app.domain.ShapeBlock
import com.norfold.app.domain.ShapeKind
import com.norfold.app.domain.style.ColorPaint
import com.norfold.app.domain.style.ColorValue
import com.norfold.app.domain.style.Gradient
import com.norfold.app.domain.style.GradientStop
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Hostile input, degenerate geometry and the edges nobody draws on purpose.
 *
 * The happy-path suites already prove the editor does the right thing with shapes a person would make.
 * This one exists for the opposite: coincident nodes, non-finite coordinates, operands that touch at
 * exactly one point, groups nested deeper than the resolver will ever resolve, and controls reached
 * through a locked layer. Every case here is either a defect that was found and fixed, or a bound that is
 * asserted rather than assumed — because a ceiling nobody tests is a ceiling nobody knows is enforced.
 */
class ShapeAdversarialTest {

    // ── Degenerate geometry ─────────────────────────────────────────────────────────────────────────

    @Test
    fun degenerateOutlinesNormalizeToFiniteGeometryRatherThanNaN() {
        val coincident = ShapeSubpath(
            nodes = listOf(
                ShapeNode.at(20f, 20f),
                ShapeNode.at(20f, 20f),
                ShapeNode.at(20f, 20f),
                ShapeNode.at(60f, 60f),
            ),
        )
        val retracted = ShapeSubpath(
            nodes = listOf(
                ShapeNode(point = ShapePoint(30f, 30f), type = ShapeNodeType.SYMMETRIC),
                ShapeNode(point = ShapePoint(30f, 30f), type = ShapeNodeType.SMOOTH),
            ),
        )
        val stray = ShapeSubpath(nodes = listOf(ShapeNode.at(70f, 10f)))
        val path = ShapePath.of(coincident, retracted, stray).normalized()

        assertFinite("degenerate path", path)
        // A zero-length segment still has to answer geometry queries rather than divide by its own length.
        assertTrue(path.bounds().width.isFinite())
        path.subpaths.flatMap { it.segments() }.forEach { segment ->
            for (step in 0..8) {
                val point = segment.pointAt(step / 8f)
                assertTrue("sampled a non-finite point on a zero-length segment", point.x.isFinite())
            }
        }
        assertEquals(ShapeHit.None, ShapePathEditor.hitTest(path, ShapePoint(95f, 95f)))

        // Deleting down to the minimum removes the run rather than leaving a point that renders as nothing
        // and still absorbs taps.
        val twoNode = path.subpaths.first { it.nodes.size == 2 }
        val emptied = ShapePathEditor.deleteNode(path, ShapeNodeRef(twoNode.id, twoNode.nodes.first().id))
        assertTrue("a subpath below two nodes must go, not linger", emptied.subpaths.none { it.id == twoNode.id })
    }

    @Test
    fun closingAnAlmostZeroPathIsStillAValidOperandRatherThanACrash() {
        val hair = ShapePath.of(
            ShapeSubpath(
                closed = false,
                nodes = listOf(ShapeNode.at(50f, 50f), ShapeNode.at(50f + 1e-4f, 50f)),
            ),
        )
        val closed = ShapePathEditor.closeSubpath(hair, hair.subpaths.single().id)
        assertFinite("hair-thin closed path", closed)

        // An area-less region passed to a region operation is empty, not a sliver and not an exception.
        val result = ShapeBooleans.combine(closed, square(20f, 20f, 30f), ShapeBooleanOperation.INTERSECT)
        assertFinite("intersect against an area-less operand", result.path)
        assertTrue("an area-less intersection has no region", result.path.isEmpty)
        assertNotEquals(ShapeBooleanStatus.RESOLVED, result.status)
    }

    // ── Numeric safety ──────────────────────────────────────────────────────────────────────────────

    @Test
    fun aNonFinitePaintCannotReachCanonicalStateOrThePersistedPayload() {
        val hostile = ColorPaint.Ramp(
            Gradient(
                stops = listOf(
                    GradientStop(id = "a", color = ColorValue(255, 0, 0), position = Float.NaN),
                    GradientStop(
                        id = "b",
                        color = ColorValue(0, 0, 255),
                        position = Float.POSITIVE_INFINITY,
                        opacity = Float.NaN,
                    ),
                ),
                angle = Float.NaN,
                centerX = Float.NEGATIVE_INFINITY,
                centerY = Float.NaN,
                radius = Float.POSITIVE_INFINITY,
            ),
        )
        val document = ShapeDocument(
            layers = listOf(
                ShapeLayer(
                    content = ShapePathLayer(square(10f, 10f, 40f)),
                    fill = hostile,
                    stroke = ShapeStroke(paint = hostile, width = Float.NaN),
                    opacity = Float.NaN,
                ),
            ),
        ).normalized()

        val layer = document.layers.single()
        assertFinitePaint("fill", layer.fill)
        assertFinitePaint("stroke paint", layer.stroke.paint)
        assertTrue(layer.stroke.width.isFinite())
        assertTrue(layer.opacity.isFinite())

        // The serializer rejects a non-finite float outright, so a payload that comes back at all is itself
        // the proof that normalization caught every one of them before persistence.
        val raw = ShapeDocumentCodec.encode(document)
        assertTrue(ShapeDocumentCodec.decode(raw) is ShapeDecodeResult.Success)
    }

    @Test
    fun aNonFiniteCoordinateInAStoredPayloadIsRefusedAtTheDoorRatherThanDecoded() {
        val raw = """{"schemaVersion":1,"document":{"layers":[{"id":"l1","content":{"type":"path",""" +
            """"path":{"subpaths":[{"id":"s1","nodes":[{"id":"n1","point":{"x":1e40,"y":0.0}}]}]}}}]}}"""
        assertTrue("a payload holding an out-of-range float is malformed, not silently clamped",
            ShapeDocumentCodec.decode(raw) is ShapeDecodeResult.Malformed)
    }

    @Test
    fun hostileCoordinatesAndParametersNormalizeIntoTheCanvasRatherThanOutOfIt() {
        val hostilePoints = listOf(
            ShapePoint(Float.NaN, 10f),
            ShapePoint(Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY),
            ShapePoint(-1e30f, 1e30f),
            ShapePoint(-40f, -40f),
            ShapePoint(1e-42f, -1e-42f),
        )
        val path = ShapePath.of(ShapeSubpath.polygon(hostilePoints)).normalized()
        assertFinite("hostile coordinates", path)
        assertTrue("normalization must not fabricate the same point for every input",
            path.subpaths.single().nodes.map { it.point }.distinct().size > 1)

        val extremes = listOf(
            ShapeParameters(
                cornerRadius = Float.NaN, smoothness = Float.POSITIVE_INFINITY, pointCount = Int.MIN_VALUE,
                innerRadius = 0f, warp = -1e9f, tension = Float.NaN, rotation = -123_456.75f, seed = Int.MIN_VALUE,
            ),
            ShapeParameters(
                cornerRadius = 1e9f, smoothness = -1e9f, pointCount = Int.MAX_VALUE,
                innerRadius = Float.NaN, warp = 1e9f, tension = 1e9f, rotation = Float.NEGATIVE_INFINITY,
                seed = Int.MAX_VALUE,
            ),
        )
        for (kind in ShapePrimitiveKind.entries) {
            for (parameters in extremes) {
                val normalized = parameters.normalized()
                assertTrue("$kind rotation left the ordinary range", normalized.rotation in 0f..360f)
                assertTrue("$kind point count left its bounds",
                    normalized.pointCount in ShapeParameters.MIN_POINTS..ShapeParameters.MAX_POINTS)
                val generated = ShapePrimitives.generate(kind, parameters)
                assertFinite("$kind from hostile parameters", generated)
                assertTrue("$kind produced no outline at all", generated.subpaths.isNotEmpty())
            }
        }
    }

    // ── Primitive parameters ────────────────────────────────────────────────────────────────────────

    @Test
    fun everyParameterAKindClaimsToUseChangesItAndEveryOneItHidesDoesNot() {
        // `parametersUsedBy` is the sheet's promise about which sliders it shows, so it is also the sheet's
        // promise that each of them does something. Both halves are checked: a claimed knob that moves
        // nothing is an inert control, and a hidden knob that moves something is geometry the user can see
        // and cannot reach.
        //
        // A claimed knob is asked to change the outline *somewhere* in its range rather than between one
        // chosen pair of values, because some of them saturate. Corner radius is a fraction of the canvas
        // side while the rounding a corner can actually take is half its shorter edge, so on the default
        // six-point star every value past roughly 0.155 draws the same fully rounded star. That is honest
        // geometry — a corner cannot round further than its edges allow — but it does mean one pair of
        // samples proves nothing in either direction. The hidden half is held to the stricter reading: no
        // value anywhere in the range may move anything.
        val base = ShapeParameters()
        val sweeps: Map<ShapeParameterKey, List<ShapeParameters>> = mapOf(
            ShapeParameterKey.CORNER_RADIUS to listOf(0f, 0.02f, 0.18f, 0.5f).map { base.copy(cornerRadius = it) },
            ShapeParameterKey.SMOOTHNESS to listOf(0f, 0.05f, 0.6f, 1f).map { base.copy(smoothness = it) },
            ShapeParameterKey.POINT_COUNT to listOf(3, 6, 11, 64).map { base.copy(pointCount = it) },
            ShapeParameterKey.INNER_RADIUS to listOf(0.05f, 0.5f, 1f).map { base.copy(innerRadius = it) },
            ShapeParameterKey.WARP to listOf(-1f, 0f, 0.7f, 1f).map { base.copy(warp = it) },
            ShapeParameterKey.TENSION to listOf(0f, 0.15f, 1f, 2f).map { base.copy(tension = it) },
            ShapeParameterKey.ROTATION to listOf(0f, 41f, 180f, 359f).map { base.copy(rotation = it) },
            ShapeParameterKey.SEED to listOf(1, 2, 47).map { base.copy(seed = it) },
        )

        for (kind in ShapePrimitiveKind.entries) {
            for ((key, values) in sweeps) {
                val drawn = values.map { ShapePrimitives.generate(kind, it) }.distinct()
                if (key in kind.parametersUsedBy) {
                    assertTrue(
                        "$kind shows a ${key.displayName} slider that changes nothing anywhere in its range",
                        drawn.size > 1,
                    )
                } else {
                    assertEquals(
                        "$kind hides ${key.displayName} yet its geometry follows it: ${drawn.size} outlines",
                        1, drawn.size,
                    )
                }
            }
        }
    }

    @Test
    fun noGeneratedPrimitiveHandsTheEditorTwoNodesInTheSamePlace() {
        // Corner trims are clamped to half the shorter adjacent edge, so at full rounding two neighbouring
        // corners meet exactly and the naive emission leaves a pair of nodes at one point with a zero-length
        // segment between them. It is invisible while the shape is just drawn, and a trap the moment the user
        // taps Edit points: the outline shows twice the nodes there are corners, and dragging one of a pair
        // opens a seam nobody could see coming. It is reachable without trying — the shipped Squircle preset
        // saturates on all four edges, and so does a rectangle with the corner slider at the top of its range.
        //
        // The whole parameter space is swept rather than the presets, because the user gets to move the
        // sliders, and the degenerate case lives at the end of their travel.
        val sweep = buildList {
            for (radius in listOf(0f, 0.05f, 0.18f, 0.34f, 0.5f)) {
                for (points in listOf(3, 5, 6, 12, 64)) {
                    for (inner in listOf(0.05f, 0.42f, 0.5f, 1f)) {
                        for (smoothness in listOf(0f, 0.6f, 1f)) {
                            add(
                                ShapeParameters(
                                    cornerRadius = radius, pointCount = points,
                                    innerRadius = inner, smoothness = smoothness,
                                ),
                            )
                        }
                    }
                }
            }
        }

        for (kind in ShapePrimitiveKind.entries) {
            for (parameters in sweep) {
                val path = ShapePrimitives.generate(kind, parameters)
                for (subpath in path.subpaths) {
                    subpath.nodes.zipWithNext { first, second ->
                        assertTrue(
                            "$kind at radius ${parameters.cornerRadius}, ${parameters.pointCount} points: " +
                                "nodes ${first.id} and ${second.id} are both at ${first.point}",
                            (second.point - first.point).length > 1e-3f,
                        )
                    }
                    if (subpath.closed && subpath.nodes.size > 2) {
                        val ends = (subpath.nodes.last().point - subpath.nodes.first().point).length
                        assertTrue(
                            "$kind at radius ${parameters.cornerRadius}: the ring closes onto its own " +
                                "first node at ${subpath.nodes.first().point}",
                            ends > 1e-3f,
                        )
                    }
                }
            }
        }
    }

    @Test
    fun aFullyRoundedRectangleIsAnEllipseRatherThanACircleWithFourExtraNodes() {
        // The merge has to leave a usable path behind, not just a shorter one. At the top of the corner
        // slider a rectangle's four arcs are quarter circles meeting at the edge midpoints, which is an
        // ellipse — so that is what the editor should hand back: four smooth nodes on the midpoints, the
        // same count and the same positions the dedicated ellipse generator produces.
        val rounded = ShapePrimitives.generate(
            ShapePrimitiveKind.ROUNDED_RECTANGLE,
            ShapeParameters(cornerRadius = ShapeParameters.MAX_CORNER_FRACTION),
        )
        val ellipse = ShapePrimitives.generate(ShapePrimitiveKind.ELLIPSE)
        val roundedNodes = rounded.subpaths.single().nodes
        assertEquals("a fully rounded rectangle needs four nodes, not eight", 4, roundedNodes.size)
        assertEquals(
            "and they belong on the edge midpoints",
            ellipse.subpaths.single().nodes.map { it.point }.sortedBy { it.x * 1000f + it.y },
            roundedNodes.map { it.point }.sortedBy { it.x * 1000f + it.y },
        )
        assertTrue(
            "each one carries a handle on both sides, or the corners are not round any more",
            roundedNodes.all { it.controlIn.length > 1f && it.controlOut.length > 1f },
        )
    }

    @Test
    fun changingOneParameterLeavesTheOthersExactlyWhereTheUserPutThem() {
        // The failure this guards is a caller that builds a fresh `ShapeParameters` instead of copying the
        // current one: the knob it meant to set lands, and every other knob silently returns to its default.
        val authored = ShapeParameters(
            cornerRadius = 0.31f, smoothness = 0.22f, pointCount = 9, innerRadius = 0.37f,
            warp = 0.44f, tension = 0.66f, rotation = 123f, seed = 5,
        )
        val document = ShapeDocument(
            layers = listOf(ShapeLayer(id = "blob", content = ShapePrimitiveLayer(ShapePrimitiveKind.BLOB, authored))),
        ).normalized()

        val reseeded = ShapeLayerEditor.setParameters(document, "blob", authored.copy(seed = authored.seed + 1))
        val after = (reseeded.layers.single().content as ShapePrimitiveLayer).parameters
        assertEquals("the knob that was asked for", authored.seed + 1, after.seed)
        assertEquals(authored.copy(seed = after.seed), after)
        assertNotEquals("a new seed has to actually redraw the blob", document, reseeded)
    }

    // ── Boolean operations ──────────────────────────────────────────────────────────────────────────

    @Test
    fun hostileBooleanOperandsDegradeHonestlyWithoutProducingNaN() {
        val left = square(10f, 10f, 40f)
        val touchingEdge = square(50f, 10f, 40f)
        val touchingCorner = square(50f, 50f, 40f)
        val figureEight = ShapePath.of(
            ShapeSubpath.polygon(
                listOf(
                    ShapePoint(10f, 10f), ShapePoint(50f, 50f),
                    ShapePoint(10f, 50f), ShapePoint(50f, 10f),
                ),
            ),
        )

        for (operation in ShapeBooleanOperation.entries) {
            for ((label, clip) in listOf(
                "touching edge" to touchingEdge,
                "touching corner" to touchingCorner,
                "self-intersecting" to figureEight,
                "identical" to left,
                "sliver" to square(10f + 40f - 1e-5f, 10f, 40f),
            )) {
                val result = ShapeBooleans.combine(left, clip, operation)
                assertFinite("$operation against a $label operand", result.path)
                if (result.path.isEmpty) {
                    assertNotEquals(
                        "$operation against a $label operand reported a region it does not have",
                        ShapeBooleanStatus.RESOLVED, result.status,
                    )
                }
            }
        }

        // Touching along an edge is one region of twice the area, not two overlapping ones.
        val fused = ShapeBooleans.combine(left, touchingEdge, ShapeBooleanOperation.UNION)
        assertEquals(ShapeBooleanStatus.RESOLVED, fused.status)
        assertEquals(2f * SQUARE_AREA, area(fused.path), TOLERANCE)

        // An overlap thinner than the sliver floor is numerical noise, not a region someone drew.
        val sliver = ShapeBooleans.combine(left, square(49.99999f, 10f, 40f), ShapeBooleanOperation.INTERSECT)
        assertTrue("a sub-tolerance overlap must not survive as a sliver", sliver.path.isEmpty)
    }

    @Test
    fun anEmptyOrHiddenOperandLeavesTheGroupHonestRatherThanPretendingToResolve() {
        val visible = layer(square(10f, 10f, 40f))
        val empty = layer(ShapePath())
        val hidden = layer(square(30f, 30f, 40f)).copy(visible = false)

        val withEmpty = ShapeBooleans.compute(group(ShapeBooleanOperation.UNION, visible, empty))
        assertEquals(ShapeBooleanStatus.PASSTHROUGH, withEmpty.status)
        assertEquals(1, withEmpty.resolvedCount)
        assertEquals("a child that resolves to nothing is reported skipped", 1, withEmpty.skippedCount)
        assertEquals(SQUARE_AREA, area(withEmpty.path), TOLERANCE)

        val allHidden = ShapeBooleans.compute(
            group(ShapeBooleanOperation.INTERSECT, hidden, layer(square(0f, 0f, 20f)).copy(visible = false)),
        )
        assertEquals(ShapeBooleanStatus.PASSTHROUGH, allHidden.status)
        assertTrue(allHidden.path.isEmpty)

        // A group that genuinely cancels out is CANCELLED, and every downstream query has to survive it.
        val cancelled = ShapeBooleans.compute(group(ShapeBooleanOperation.SUBTRACT, visible, layer(square(10f, 10f, 40f))))
        assertEquals(ShapeBooleanStatus.CANCELLED, cancelled.status)
        assertTrue(cancelled.path.isEmpty)
        val document = ShapeDocument(
            layers = listOf(ShapeLayer(content = ShapeBooleanLayer(group(ShapeBooleanOperation.SUBTRACT, visible, layer(square(10f, 10f, 40f)))))),
        ).normalized()
        // `isRenderable` is the cheap structural question — "is there a visible layer with operands" —
        // and it stays true here on purpose, because answering it exactly would mean running the solver on
        // every predicate call. What has to hold is that the geometry the renderer actually consumes is
        // empty, and that nothing downstream trips over an empty outline.
        val outline = document.layers.single().content.toPath()
        assertTrue("a cancelled group resolves to nothing to draw", outline.isEmpty)
        assertEquals(ShapeHit.None, ShapePathEditor.hitTest(outline, ShapePoint(30f, 30f)))
        // Bounds of nothing are a finite degenerate rect, not the `MAX_VALUE` accumulator sentinel — that
        // sentinel reaching a layout or an export would be an absurd allocation, not an empty selection.
        val emptyBounds = outline.bounds()
        assertTrue(
            "empty bounds must stay finite, got $emptyBounds",
            listOf(emptyBounds.left, emptyBounds.top, emptyBounds.right, emptyBounds.bottom)
                .all { it.isFinite() && abs(it) <= ShapeCanvas.LIMIT },
        )
        assertEquals(0f, emptyBounds.width, 0f)
        assertEquals(0f, emptyBounds.height, 0f)
        assertTrue(ShapeDocumentCodec.decode(ShapeDocumentCodec.encode(document)) is ShapeDecodeResult.Success)
    }

    @Test
    fun aNestedGroupTheResolverRefusesIsReportedRatherThanQuietlyDropped() {
        // The innermost group sits exactly one level past the ceiling, so resolving it is refused.
        val past = ShapeBooleans.compute(nested(ShapeBooleans.MAX_DEPTH))
        assertEquals(
            "a group whose child could not be resolved must not report a finished region",
            ShapeBooleanStatus.TOO_COMPLEX, past.status,
        )

        // And the ceiling is a ceiling, not a blanket: nesting the editor allows still resolves.
        val within = ShapeBooleans.compute(nested(ShapeBooleans.MAX_DEPTH - 1))
        assertNotEquals(ShapeBooleanStatus.TOO_COMPLEX, within.status)
        assertFalse("legal nesting still produces a region", within.path.isEmpty)
    }

    @Test
    fun mergingCannotBuildNestingDeeperThanTheResolverWillEverResolve() {
        var document = ShapeDocument(layers = listOf(layer(square(10f, 10f, 40f)), layer(square(30f, 30f, 40f))))
        var refusals = 0
        repeat(ShapeBooleans.MAX_DEPTH + 4) {
            document = ShapeLayerEditor.append(document, layer(square(20f, 20f, 40f)))
            val merged = ShapeLayerEditor.groupAsBoolean(
                document, document.layers.map { it.id }.takeLast(2), ShapeBooleanOperation.UNION,
            )
            if (merged.layers.size == document.layers.size) refusals++
            document = merged
        }

        assertTrue("the ceiling has to actually be reached for this to prove anything", refusals > 0)
        val deepest = document.layers.mapNotNull { (it.content as? ShapeBooleanLayer)?.group }
        assertTrue(deepest.isNotEmpty())
        for (group in deepest) {
            assertTrue(
                "the stack holds a group nested past the resolver's ceiling",
                ShapeBooleans.depthOf(group) < ShapeBooleans.MAX_DEPTH,
            )
            val result = ShapeBooleans.compute(group)
            assertNotEquals(ShapeBooleanStatus.TOO_COMPLEX, result.status)
            assertFalse("a group the editor allowed the user to build must resolve", result.path.isEmpty)
        }
    }

    // ── Curve subdivision ───────────────────────────────────────────────────────────────────────────

    @Test
    fun addPointPreservesTheVisibleCurveAtEveryTSampleNotJustThePointCount() {
        val original = ShapePrimitives.generate(ShapePrimitiveKind.ELLIPSE, ShapeParameters())
        val subpath = original.subpaths.single()
        val before = subpath.segments()
        val split = 0.37f

        val divided = ShapePathEditor.insertNode(original, subpath.id, 0, split)
        val after = divided.subpaths.single().segments()
        assertEquals(before.size + 1, after.size)

        // The two halves have to trace the original segment, sample for sample.
        for (step in 0..64) {
            val t = step / 64f
            val expected = before.first().pointAt(t)
            val actual = if (t <= split) after[0].pointAt(t / split)
            else after[1].pointAt((t - split) / (1f - split))
            assertNear("t=$t", expected, actual)
        }

        // And no other segment may move: subdividing one curve is not licence to redraw its neighbours.
        for (index in 1 until before.size) {
            val expected = before[index]
            val actual = after[index + 1]
            for (step in 0..16) {
                val t = step / 16f
                assertNear("neighbour segment $index at t=$t", expected.pointAt(t), actual.pointAt(t))
            }
        }
    }

    // ── Round-trip drift ────────────────────────────────────────────────────────────────────────────

    @Test
    fun repeatedRoundTripsDoNotAccumulateGeometryDrift() {
        val start = ShapeDocument(
            layers = listOf(
                ShapeLayer(content = ShapePrimitiveLayer(ShapePrimitiveKind.STAR, ShapeParameters(pointCount = 9, warp = 0.4f))),
                ShapeLayer(content = ShapePathLayer(ShapePrimitives.generate(ShapePrimitiveKind.SQUIRCLE, ShapeParameters()))),
                ShapeLayer(content = ShapeBooleanLayer(group(ShapeBooleanOperation.SUBTRACT, layer(square(10f, 10f, 60f)), layer(square(30f, 30f, 40f))))),
            ),
        ).normalized()

        var current = start
        repeat(12) {
            val decoded = ShapeDocumentCodec.decode(ShapeDocumentCodec.encode(current))
            current = (decoded as ShapeDecodeResult.Success).document
        }
        assertEquals("twelve round trips is still the same document", start, current)

        // Normalization is idempotent, and re-generating a primitive's outline is too.
        assertEquals(start, start.normalized().normalized())
        val parametric = start.layers.first().content
        repeat(6) { assertEquals(parametric.toPath(), parametric.normalized().toPath()) }
    }

    // ── Resource bounds ─────────────────────────────────────────────────────────────────────────────

    @Test
    fun hostilePayloadCapsAreEnforcedRatherThanTrusted() {
        val flood = ShapeDocument(
            layers = List(ShapeDocument.MAX_LAYERS * 3) {
                ShapeLayer(
                    id = "x".repeat(ShapeLayer.MAX_ID * 4),
                    name = "n".repeat(ShapeLayer.MAX_NAME * 4),
                    content = ShapePathLayer(
                        ShapePath(
                            subpaths = List(ShapePath.MAX_SUBPATHS + 40) {
                                ShapeSubpath(nodes = List(6) { i -> ShapeNode.at(i * 3f, i * 4f) })
                            },
                        ),
                    ),
                )
            },
            accessibility = ShapeAccessibility(alternativeText = "z".repeat(ShapeAccessibility.MAX_ALT_TEXT * 3)),
        ).normalized()

        assertEquals(ShapeDocument.MAX_LAYERS, flood.layers.size)
        assertEquals("duplicate ids are re-minted, so the cap cannot collapse the stack",
            flood.layers.size, flood.layers.map { it.id }.distinct().size)
        assertEquals(ShapeAccessibility.MAX_ALT_TEXT, flood.accessibility.alternativeText.length)
        for (layer in flood.layers) {
            assertTrue(layer.id.length <= ShapeLayer.MAX_ID)
            assertTrue(layer.name.length <= ShapeLayer.MAX_NAME)
            assertTrue((layer.content as ShapePathLayer).path.subpaths.size <= ShapePath.MAX_SUBPATHS)
        }

        val longRun = ShapeSubpath(nodes = List(ShapeSubpath.MAX_NODES + 500) { ShapeNode.at(it % 100f, (it * 7) % 100f) })
        assertEquals(ShapeSubpath.MAX_NODES, ShapePath.of(longRun).normalized().subpaths.single().nodes.size)

        val crowded = ShapeBooleanGroup(children = List(ShapeBooleanGroup.MAX_CHILDREN + 20) { layer(square(it.toFloat(), 0f, 20f)) })
        assertEquals(ShapeBooleanGroup.MAX_CHILDREN, crowded.normalized().children.size)
    }

    /**
     * A gradient arrives from bytes, so its stop count is attacker-chosen too.
     *
     * Layers, nodes, subpaths, Boolean children, ids and alt text were all bounded; the stop list was the
     * one collection in a stored shape that was not, and it is the one that gets walked *per frame* on the
     * way to a shader. The route tested here is the whole one — a payload in the additive block field,
     * decoded, normalized, then asked for its render stops — because a cap that only holds in the editor is
     * no cap at all against a document that never went through the editor.
     */
    @Test
    fun aStoredGradientCannotAskTheRendererForAnUnboundedRamp() {
        val flood = Gradient(
            stops = List(Gradient.MAX_STOPS * 80) { index ->
                GradientStop(color = ColorValue(index % 256, 0x36, 0xFF), position = index / (Gradient.MAX_STOPS * 80f))
            },
        )
        val hostile = ShapeDocument(
            layers = listOf(
                ShapeLayer(
                    id = "ramp",
                    content = ShapePathLayer(square(10f, 10f, 60f)),
                    fill = ColorPaint.Ramp(flood),
                    stroke = ShapeStroke(paint = ColorPaint.Ramp(flood)),
                ),
            ),
        )

        val decoded = ShapeDocumentCodec.decode(ShapeDocumentCodec.encode(hostile))
        assertTrue("a wide ramp must decode to a verdict, not to an allocation", decoded is ShapeDecodeResult.Success)
        val layer = (decoded as ShapeDecodeResult.Success).document.layers.single()

        for ((label, paint) in listOf("fill" to layer.fill, "stroke" to layer.stroke.paint)) {
            val gradient = (paint as ColorPaint.Ramp).gradient
            assertEquals("$label ramp is capped on the way out of the payload", Gradient.MAX_STOPS, gradient.stopCount)
            assertTrue("$label ramp still renders", gradient.isRenderable)
            assertEquals("$label render list is bounded by the same cap", Gradient.MAX_STOPS, gradient.renderStops().size)
            assertFinitePaint(label, paint)
        }

        // The interactive route cannot build what the payload route has to trim, so the two agree.
        var built = Gradient.of(ColorValue(0, 0, 0), ColorValue(255, 255, 255))
        repeat(Gradient.MAX_STOPS * 2) { built = built.addStopAt(0.5f) }
        assertEquals(Gradient.MAX_STOPS, built.stopCount)
    }

    @Test
    fun aPayloadNestedFarPastTheCeilingLoadsWithoutRunningAwayAndSaysItCannotResolve() {
        // Forty levels is about five times the resolver's ceiling: far past anything the editor will let a
        // user build, so the only way a document gets here is a payload from somewhere else. Every walk over
        // this tree is a place unbounded recursion would show up, and the load path takes several — decode,
        // normalize, depth probe, resolve, hit test, re-encode — so each one is exercised below.
        val hostile = ShapeDocument(layers = listOf(ShapeLayer(id = "deep", content = ShapeBooleanLayer(nested(40)))))
        val payload = ShapeDocumentCodec.encode(hostile)

        val decoded = ShapeDocumentCodec.decode(payload)
        assertTrue("a deep payload has to decode to a verdict, not take the caller down", decoded is ShapeDecodeResult.Success)
        val loaded = (decoded as ShapeDecodeResult.Success).document
        val group = (loaded.layers.single().content as ShapeBooleanLayer).group

        // Nothing was trimmed on the way in. Deleting a user's children to make a payload resolvable would
        // be data loss dressed up as validation — the document keeps them, and the resolver is the thing
        // that admits it cannot finish. The probe saturates at the budget rather than measuring the true
        // depth, which is the point: asking how deep a hostile tree goes must not itself be the runaway.
        assertEquals(ShapeBooleans.MAX_DEPTH, ShapeBooleans.depthOf(group))

        val resolved = ShapeBooleans.compute(group)
        assertEquals(
            "a tree past the ceiling must report TOO_COMPLEX rather than passing off a partial union",
            ShapeBooleanStatus.TOO_COMPLEX, resolved.status,
        )
        assertFinite("refused nesting", resolved.path)

        // The layer stays in the stack whatever the resolver decided, so selection, layout and persistence
        // all still have to cope with the outline it produced.
        assertEquals(ShapeHit.None, ShapePathEditor.hitTest(resolved.path, ShapePoint(-500f, -500f)))
        val bounds = resolved.path.bounds()
        assertTrue(
            "bounds of a refused group must stay finite, got $bounds",
            listOf(bounds.left, bounds.top, bounds.right, bounds.bottom).all { it.isFinite() },
        )
        assertTrue(ShapeDocumentCodec.decode(ShapeDocumentCodec.encode(loaded)) is ShapeDecodeResult.Success)

        // Through the real ingress: a block carrying this payload opens as an editable canonical document
        // rather than being reported malformed, and the session it opens can still be closed.
        val runtime = ShapeFocusRuntime(
            ShapeBlock(id = "b-deep", kind = ShapeKind.Rectangle, shapeEditDocument = payload),
            RecordingHost(),
        )
        assertEquals(
            "the deep payload is canonical, not corrupt",
            LoadedShapeDocument.Origin.CANONICAL, runtime.origin(),
        )
        assertFalse("a readable payload opens editable", runtime.isReadOnly())
        assertFalse("opening a document changes nothing by itself", runtime.isDirty())
        runtime.cancel()

        // Deep *and* broken is the realistic hostile case, and it has to come back as a verdict too.
        assertTrue(ShapeDocumentCodec.decode(payload.substring(0, payload.length / 2)) is ShapeDecodeResult.Malformed)
    }

    // ── Hit targets and disambiguation ──────────────────────────────────────────────────────────────

    @Test
    fun handleTargetsGrowWithTheTouchRadiusRatherThanStayingPinnedToThreeUnits() {
        val path = ShapePath.of(
            ShapeSubpath(
                id = "s",
                closed = false,
                nodes = listOf(
                    ShapeNode(id = "a", point = ShapePoint(50f, 50f), controlOut = ShapePoint(20f, 0f)),
                    ShapeNode(id = "b", point = ShapePoint(50f, 90f), controlIn = ShapePoint(10f, 0f)),
                ),
            ),
        )
        // Just past the handle, along the handle's own direction: far from the node and clear of the curve.
        val probe = ShapePoint(73.5f, 50f)

        val fingerHit = ShapePathEditor.hitTest(path, probe, radius = 12f)
        assertTrue("a finger-sized target must still reach the handle it is aimed at, got $fingerHit",
            fingerHit is ShapeHit.Handle)
        assertEquals(ShapeHandleSide.OUT, (fingerHit as ShapeHit.Handle).ref.side)

        // Scaled, not unbounded: at a tight radius the handle is out of reach and nothing else claims it.
        assertFalse(ShapePathEditor.hitTest(path, probe, radius = 4f) is ShapeHit.Handle)

        // Priority is unchanged at the default radius: a handle still outranks the node it belongs to.
        val onHandle = ShapePathEditor.hitTest(path, ShapePoint(70f, 50f))
        assertTrue("the handle still wins its own position, got $onHandle", onHandle is ShapeHit.Handle)
    }

    @Test
    fun overlappingControlsResolveToTheSameChoiceEveryTime() {
        val path = ShapePath.of(
            ShapeSubpath(
                id = "s",
                nodes = listOf(
                    ShapeNode(id = "a", point = ShapePoint(40f, 40f), controlOut = ShapePoint(6f, 0f)),
                    // Coincident with the first node's outgoing handle, and with the next node.
                    ShapeNode(id = "b", point = ShapePoint(46f, 40f)),
                    ShapeNode(id = "c", point = ShapePoint(46f, 40f)),
                    ShapeNode(id = "d", point = ShapePoint(40f, 70f)),
                ),
            ),
        )
        val probe = ShapePoint(46f, 40f)

        val first = ShapePathEditor.hitTest(path, probe)
        repeat(200) {
            assertEquals("the same tap must mean the same control every time", first, ShapePathEditor.hitTest(path, probe))
        }
        assertTrue("a handle drawn on top of a node keeps priority", first is ShapeHit.Handle)

        // Two coincident nodes: whichever wins, it is always the same one, and it is a real reference.
        val nodeProbe = ShapePoint(46.9f, 40f)
        val nodeHit = ShapePathEditor.nearestNode(path, nodeProbe)
        repeat(200) { assertEquals(nodeHit, ShapePathEditor.nearestNode(path, nodeProbe)) }

        // A multi-node drag cannot depend on the order the selection happened to be built in.
        val refs = path.subpaths.single().nodes.map { ShapeNodeRef("s", it.id) }
        val forward = ShapePathEditor.moveNodesBy(path, LinkedHashSet(refs), ShapePoint(3f, -2f))
        val backward = ShapePathEditor.moveNodesBy(path, LinkedHashSet(refs.reversed()), ShapePoint(3f, -2f))
        assertEquals(forward, backward)
    }

    // ── History ownership ───────────────────────────────────────────────────────────────────────────

    @Test
    fun aContinuousParameterDragIsOneLocalStepAndNoHostTransaction() {
        val host = RecordingHost()
        val runtime = ShapeFocusRuntime(ShapeBlock(id = "b1", kind = ShapeKind.RoundedRectangle), host)
        val layerId = runtime.selection().layerId!!

        assertTrue(runtime.beginGesture())
        for (frame in 1..100) {
            runtime.previewGesture {
                ShapeLayerEditor.setParameters(it, layerId, ShapeParameters(cornerRadius = frame / 400f))
            }
        }
        assertTrue("the drag has to have moved something", runtime.stats().previewChanges > 1)
        assertEquals("preview frames are not steps", 0, runtime.stats().undoDepth)
        assertTrue(runtime.endGesture())
        assertEquals("a hundred frames is one step", 1, runtime.stats().undoDepth)
        assertTrue("a local step must not reach the artifact", host.labels.isEmpty())

        // A second drag that changes nothing leaves no step at all.
        assertTrue(runtime.beginGesture())
        runtime.previewGesture { it }
        assertFalse(runtime.endGesture())
        assertEquals(1, runtime.stats().undoDepth)

        assertTrue(runtime.done())
        assertEquals("Edit shape", host.labels.single())
    }

    @Test
    fun aCancelledGestureLeavesTheSessionSaveableAgain() {
        val host = RecordingHost()
        val runtime = ShapeFocusRuntime(ShapeBlock(id = "b1", kind = ShapeKind.Ellipse), host)
        val baseline = runtime.snapshot()

        assertTrue(runtime.beginGesture())
        runtime.previewPath { ShapePathEditor.moveNodesBy(it, it.subpaths.flatMap { s -> s.nodes.map { n -> ShapeNodeRef(s.id, n.id) } }, ShapePoint(4f, 4f)) }
        // Mid-gesture the session is deliberately closed to committing: an unfinished drag is not a result.
        assertFalse("Done must not absorb an unfinished gesture", runtime.done())
        assertFalse(runtime.canUndo())

        // A gesture whose pointer stream is lost is cancelled, and cancelling restores everything it held.
        assertTrue(runtime.cancelGesture())
        assertEquals(baseline, runtime.snapshot())
        assertFalse(runtime.hasGesture())
        assertFalse("a cancelled gesture leaves no step", runtime.canUndo())
        assertTrue("the session has to be saveable again", runtime.done())
        assertTrue("a session that changed nothing commits nothing", host.labels.isEmpty())
    }

    // ── Locked layers ───────────────────────────────────────────────────────────────────────────────

    @Test
    fun aLockedLayerRefusesEveryRouteThatWouldChangeItsGeometryOrExistence() {
        val locked = ShapeLayer(
            id = "locked",
            content = ShapePrimitiveLayer(ShapePrimitiveKind.STAR, ShapeParameters(pointCount = 7)),
            locked = true,
        )
        val other = ShapeLayer(id = "free", content = ShapePathLayer(square(10f, 10f, 30f)))
        val document = ShapeDocument(layers = listOf(locked, other)).normalized()

        val geometryRoutes: List<Pair<String, (ShapeDocument) -> ShapeDocument>> = listOf(
            "setParameters" to { d -> ShapeLayerEditor.setParameters(d, "locked", ShapeParameters(pointCount = 3)) },
            "setKind" to { d -> ShapeLayerEditor.setKind(d, "locked", ShapePrimitiveKind.TRIANGLE) },
            "setPath" to { d -> ShapeLayerEditor.setPath(d, "locked", square(0f, 0f, 90f)) },
            "replaceContent" to { d -> ShapeLayerEditor.replaceContent(d, "locked", ShapePathLayer(square(0f, 0f, 90f))) },
            "convertToPath" to { d -> ShapeLayerEditor.convertToPath(d, "locked") },
            "remove" to { d -> ShapeLayerEditor.remove(d, "locked") },
            "flatten" to { d -> ShapeLayerEditor.flatten(d, "locked") },
            "ungroup" to { d -> ShapeLayerEditor.ungroup(d, "locked") },
            "groupAsBoolean" to { d -> ShapeLayerEditor.groupAsBoolean(d, listOf("locked", "free"), ShapeBooleanOperation.UNION) },
            "moveUp" to { d -> ShapeLayerEditor.moveUp(d, "locked") },
            "moveDown" to { d -> ShapeLayerEditor.moveDown(d, "locked") },
        )
        for ((name, route) in geometryRoutes) {
            assertEquals("$name changed a locked layer", document, route(document))
        }

        // The lock guards the shape, not the layer list: hiding, renaming and unlocking still work, or the
        // user would have no way to reach a locked layer at all.
        assertNotEquals(document, ShapeLayerEditor.setVisibility(document, "locked", false))
        assertNotEquals(document, ShapeLayerEditor.rename(document, "locked", "Badge"))
        assertNotEquals(document, ShapeLayerEditor.setLocked(document, "locked", false))
        assertNotEquals(document, ShapeLayerEditor.setFill(document, "locked", ColorPaint.Flat(ColorValue(1, 2, 3))))

        // Unlocking is the way through, and it leaves the layer fully editable.
        val unlocked = ShapeLayerEditor.setLocked(document, "locked", false)
        assertNotEquals(unlocked, ShapeLayerEditor.setParameters(unlocked, "locked", ShapeParameters(pointCount = 3)))

        // Through the session runtime the refusal is reported, not swallowed.
        val runtime = ShapeFocusRuntime(
            ShapeBlock(id = "b1", kind = ShapeKind.Star, shapeEditDocument = ShapeDocumentCodec.encode(document)),
            RecordingHost(),
        )
        assertFalse(runtime.setParameters("locked", ShapeParameters(pointCount = 3)))
        assertFalse(runtime.convertToPath("locked"))
        assertFalse(runtime.removeLayer("locked"))
        assertFalse(runtime.isDirty())
    }

    // ── Derived outlines ────────────────────────────────────────────────────────────────────────────

    /**
     * A layer's outline is derived once per version of that layer, and a paint change is not a new version.
     *
     * Deriving an outline is the expensive half of drawing a shape — trigonometry per point for a primitive,
     * a whole group resolve for a Boolean — and the render walk, hit testing and the layer list all ask for
     * it. The memo lives on the content rather than on a caller's cache key, so invalidation is structural:
     * these are immutable data classes, so new geometry is a new instance and therefore a new outline, while
     * a recolour wraps the same content in a new layer and reuses it.
     *
     * Identity is the assertion, not elapsed time: a timing test would pass on a fast machine with the cache
     * removed, whereas the same instance coming back proves nothing was recomputed.
     */
    @Test
    fun anOutlineIsDerivedOncePerVersionOfTheGeometryThatProducesIt() {
        val primitive = ShapePrimitiveLayer(kind = ShapePrimitiveKind.STAR, parameters = ShapeParameters())
        assertSame("the same primitive must not re-run its generator", primitive.toPath(), primitive.toPath())

        val boolean = ShapeBooleanLayer(
            group(ShapeBooleanOperation.UNION, layer(square(10f, 10f, 40f)), layer(square(30f, 30f, 40f))),
        )
        assertSame("nor must the same Boolean group re-resolve", boolean.toPath(), boolean.toPath())
        assertEquals(ShapeBooleanStatus.RESOLVED, boolean.status())

        // A paint change must reuse the outline; a geometry change must not.
        val painted = ShapeLayer(content = primitive, fill = ColorPaint.Flat(ColorValue(1, 2, 3)))
        val recoloured = painted.copy(fill = ColorPaint.Flat(ColorValue(9, 8, 7)))
        assertSame("recolouring rebuilt the geometry", painted.content.toPath(), recoloured.content.toPath())
        assertNotEquals(
            "changing a parameter must produce different geometry",
            primitive.toPath(),
            primitive.copy(parameters = ShapeParameters(pointCount = 9)).toPath(),
        )

        // The memo is a cache, not state: it must stay out of equality, hashing and the wire, or two
        // identical layers stop comparing equal and a document stops round-tripping.
        val twin = ShapePrimitiveLayer(kind = ShapePrimitiveKind.STAR, parameters = ShapeParameters())
        twin.toPath()
        assertEquals("a resolved layer must still equal an unresolved twin", primitive, twin)
        assertEquals(primitive.hashCode(), twin.hashCode())
        val document = ShapeDocument(layers = listOf(painted, ShapeLayer(content = boolean)))
        val roundTrip = ShapeDocumentCodec.decode(ShapeDocumentCodec.encode(document))
        assertTrue("a memoised document must still serialize", roundTrip is ShapeDecodeResult.Success)
        assertEquals(document.normalized(), (roundTrip as ShapeDecodeResult.Success).document)
    }

    // ── Helpers ─────────────────────────────────────────────────────────────────────────────────────

    private class RecordingHost : ShapeFocusCommitHost {
        val labels = mutableListOf<String>()
        override fun commit(label: String, block: ShapeBlock): Boolean {
            labels += label
            return true
        }
    }

    private fun square(left: Float, top: Float, side: Float): ShapePath = ShapePath.of(
        ShapeSubpath.polygon(
            listOf(
                ShapePoint(left, top), ShapePoint(left + side, top),
                ShapePoint(left + side, top + side), ShapePoint(left, top + side),
            ),
        ),
    )

    private fun layer(path: ShapePath) = ShapeLayer(content = ShapePathLayer(path))

    private fun group(operation: ShapeBooleanOperation, vararg children: ShapeLayer) =
        ShapeBooleanGroup(operation = operation, children = children.toList())

    /** A chain of groups whose innermost one sits at [depth], for probing the resolver's ceiling. */
    private fun nested(depth: Int): ShapeBooleanGroup {
        var group = group(ShapeBooleanOperation.UNION, layer(square(10f, 10f, 40f)), layer(square(30f, 30f, 40f)))
        repeat(depth) {
            group = group(
                ShapeBooleanOperation.UNION,
                ShapeLayer(content = ShapeBooleanLayer(group)),
                layer(square(12f, 12f, 20f)),
            )
        }
        return group
    }

    private fun area(path: ShapePath): Float =
        abs(ShapeCurves.flatten(path).sumOf { ShapeCurves.signedArea(it).toDouble() }.toFloat())

    private fun assertNear(label: String, expected: ShapePoint, actual: ShapePoint) {
        assertEquals("$label x", expected.x, actual.x, 1e-3f)
        assertEquals("$label y", expected.y, actual.y, 1e-3f)
    }

    private fun assertFinite(label: String, path: ShapePath) {
        for (subpath in path.subpaths) {
            for (node in subpath.nodes) {
                for (point in listOf(node.point, node.controlIn, node.controlOut)) {
                    assertTrue("$label: $point is not finite", point.x.isFinite() && point.y.isFinite())
                    assertTrue(
                        "$label: $point escaped the coordinate limit",
                        abs(point.x) <= ShapeCanvas.LIMIT && abs(point.y) <= ShapeCanvas.LIMIT,
                    )
                }
            }
        }
    }

    private fun assertFinitePaint(label: String, paint: ColorPaint) {
        val gradient = (paint as? ColorPaint.Ramp)?.gradient ?: return
        assertTrue("$label angle", gradient.angle.isFinite())
        assertTrue("$label centre", gradient.centerX.isFinite() && gradient.centerY.isFinite())
        assertTrue("$label radius", gradient.radius.isFinite())
        for (stop in gradient.stops) {
            assertTrue("$label stop position", stop.position.isFinite())
            assertTrue("$label stop opacity", stop.opacity.isFinite())
        }
    }

    private companion object {
        const val SQUARE_AREA = 1_600f
        const val TOLERANCE = 0.5f
    }
}
