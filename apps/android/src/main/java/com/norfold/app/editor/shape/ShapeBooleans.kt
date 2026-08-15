package com.norfold.app.editor.shape

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The outcome of resolving a Boolean group.
 *
 * The status is carried alongside the path rather than being inferred from an empty result, because
 * "Subtract removed everything" and "this group is too complex to resolve" are different things to tell
 * someone, and neither of them is the same as "nothing here yet".
 */
data class ShapeBooleanResult(
    val path: ShapePath,
    val status: ShapeBooleanStatus,
    /** Children that actually took part; hidden and empty ones are skipped before the fold. */
    val resolvedCount: Int = 0,
    /** Children skipped because they were hidden or had no outline. */
    val skippedCount: Int = 0,
) {
    val isEmpty: Boolean get() = path.isEmpty

    companion object {
        fun empty(status: ShapeBooleanStatus, skipped: Int = 0) =
            ShapeBooleanResult(ShapePath(), status, resolvedCount = 0, skippedCount = skipped)
    }
}

/** Why a Boolean group looks the way it does. Surfaced in the editor so a blank result is never a mystery. */
enum class ShapeBooleanStatus {
    /** The operation ran over every participating child and produced the region shown. */
    RESOLVED,

    /** Fewer than two children could take part, so the single remaining outline is passed through unchanged. */
    PASSTHROUGH,

    /** The operation ran and the regions cancelled out — a real, empty answer. */
    CANCELLED,

    /**
     * The resolver gave up: the outlines exceeded its edge budget, or a nested group sat past [MAX_DEPTH].
     *
     * The accompanying path is whatever survived — often one operand, sometimes nothing at all — and is
     * explicitly *not* the answer. A caller showing it must say so, because a partial union looks exactly
     * like a finished one.
     */
    TOO_COMPLEX,

    /** The operation ran but at least one boundary chain could not be closed, so part of it was dropped. */
    PARTIAL,
}

/**
 * Boolean combination of shape outlines.
 *
 * Curves are flattened to polygons first, the two polygon sets are cut against each other at every
 * crossing, and each resulting edge is kept, reversed or dropped according to whether it lies inside or
 * outside the other operand. The surviving edges are then stitched back into closed rings. Working on
 * flattened outlines is what keeps this self-contained: no curve-curve intersection solver, no external
 * geometry kernel, and a result whose accuracy is governed by one tolerance the whole editor shares.
 *
 * The cost is that a Boolean result is polygonal. That is why [ShapeLayerEditor.flatten] is an explicit,
 * separate command — the group keeps its children as real curves and stays re-editable, and only the
 * moment someone asks for a plain path do they get the polygon and give up the parametric originals.
 */
object ShapeBooleans {
    /** Points closer than this are the same point, for splitting, stitching and boundary tests. */
    const val EPSILON = 1e-3f

    /** A midpoint within this of the other outline is on it, not inside or outside it. */
    const val BOUNDARY_TOLERANCE = 5e-3f

    /** Rings thinner than this are numerical slivers rather than regions someone drew. */
    const val MIN_AREA = 1e-3f

    /** A chain that ends this far or less from where it started is closed; further, and it is dropped. */
    const val CLOSE_TOLERANCE = 0.5f

    /**
     * Per-operand edge ceiling. Two operands at the limit cost four million pair tests, which is the most
     * work worth spending before saying so out loud instead of stalling a gesture.
     */
    const val MAX_EDGES = 2_000

    /** Nesting ceiling for groups inside groups, so a pathological document cannot recurse without end. */
    const val MAX_DEPTH = 8

    /**
     * Resolves [group] into a single region.
     *
     * Children are folded bottom-to-top in stack order: the first child is the base and each one above is
     * applied to the running result. For [ShapeBooleanOperation.SUBTRACT] that reads the way the layer list
     * looks — the bottom shape is the material, everything above it is cut out of it.
     *
     * Hidden children and children with no outline are left out of the fold. Hiding a child in the layer
     * list removing it from the operation is what the eye toggle already implies everywhere else.
     */
    fun compute(group: ShapeBooleanGroup, depth: Int = 0): ShapeBooleanResult {
        if (depth >= MAX_DEPTH) return ShapeBooleanResult.empty(ShapeBooleanStatus.TOO_COMPLEX)

        val children = group.children
            .filter { it.visible && it.content.hasGeometry }
            .map { child ->
                when (val content = child.content) {
                    is ShapeBooleanLayer -> compute(content.group, depth + 1)
                    else -> ShapeBooleanResult(content.toPath(), ShapeBooleanStatus.RESOLVED, 1)
                }
            }
        // A child that could not be resolved makes this group's region wrong too, and this group is the one
        // the user is looking at. Carrying the child's verdict up is what stops a parent from reporting a
        // finished outline over geometry that quietly vanished on the way.
        val nested = children.fold(ShapeBooleanStatus.RESOLVED) { carried, child -> worst(carried, child.status) }
        val outlines = children.map { it.path }.filterNot { it.isEmpty }
        // Counted against the real outlines rather than the pre-flight filter, so a child that passes
        // `hasGeometry` and still resolves to nothing is reported as skipped instead of silently vanishing.
        val skipped = group.children.size - outlines.size

        if (outlines.isEmpty()) {
            return ShapeBooleanResult.empty(worst(ShapeBooleanStatus.PASSTHROUGH, nested), skipped)
        }
        if (outlines.size == 1) {
            return ShapeBooleanResult(outlines.first(), worst(ShapeBooleanStatus.PASSTHROUGH, nested), 1, skipped)
        }

        var accumulated = outlines.first()
        var status = ShapeBooleanStatus.RESOLVED
        for (index in 1 until outlines.size) {
            val step = combine(accumulated, outlines[index], group.operation)
            accumulated = step.path
            // A single degraded step colours the whole result: it is the honest thing to report, and
            // TOO_COMPLEX outranks PARTIAL because it means the region shown is not the answer at all.
            if (step.status == ShapeBooleanStatus.TOO_COMPLEX) {
                status = ShapeBooleanStatus.TOO_COMPLEX
                break
            }
            if (step.status == ShapeBooleanStatus.PARTIAL) status = ShapeBooleanStatus.PARTIAL
        }

        val resolvedStatus = when {
            status != ShapeBooleanStatus.RESOLVED -> status
            accumulated.isEmpty -> ShapeBooleanStatus.CANCELLED
            else -> ShapeBooleanStatus.RESOLVED
        }
        return ShapeBooleanResult(accumulated, worst(resolvedStatus, nested), outlines.size, skipped)
    }

    /**
     * Nesting depth of [group]: zero when none of its children are groups themselves.
     *
     * Measured against a budget rather than against the data, so asking how deep a hostile payload goes
     * cannot itself become the unbounded recursion [MAX_DEPTH] exists to prevent. A subtree deeper than the
     * budget reports the budget, which is already past the ceiling and therefore enough to refuse on.
     */
    fun depthOf(group: ShapeBooleanGroup, budget: Int = MAX_DEPTH): Int {
        if (budget <= 0) return 0
        val deepest = group.children.maxOfOrNull { child ->
            (child.content as? ShapeBooleanLayer)?.let { 1 + depthOf(it.group, budget - 1) } ?: 0
        }
        return deepest ?: 0
    }

    /**
     * Whether [members] can become one group without putting a child past [MAX_DEPTH].
     *
     * A group is resolved at depth zero, so its deepest subtree may reach `MAX_DEPTH - 1` and no further.
     * Asked *before* the group is built, so the ceiling is a refusal the editor can explain rather than an
     * empty outline the user has to work out for themselves.
     */
    fun canNest(members: List<ShapeLayer>): Boolean {
        val deepest = members.maxOfOrNull { member ->
            (member.content as? ShapeBooleanLayer)?.let { 1 + depthOf(it.group) } ?: 0
        } ?: 0
        return deepest < MAX_DEPTH
    }

    /** The worse of two verdicts. TOO_COMPLEX outranks PARTIAL, and both outrank good news. */
    private fun worst(first: ShapeBooleanStatus, second: ShapeBooleanStatus): ShapeBooleanStatus = when {
        first == ShapeBooleanStatus.TOO_COMPLEX || second == ShapeBooleanStatus.TOO_COMPLEX ->
            ShapeBooleanStatus.TOO_COMPLEX
        first == ShapeBooleanStatus.PARTIAL || second == ShapeBooleanStatus.PARTIAL ->
            ShapeBooleanStatus.PARTIAL
        else -> first
    }

    /** Combines two outlines directly. [subject] is the left operand: `SUBTRACT` means subject minus clip. */
    fun combine(subject: ShapePath, clip: ShapePath, operation: ShapeBooleanOperation): ShapeBooleanResult {
        val subjectRings = orient(ShapeCurves.flatten(subject).filter { it.size >= 3 })
        val clipRings = orient(ShapeCurves.flatten(clip).filter { it.size >= 3 })

        if (subjectRings.isEmpty() && clipRings.isEmpty()) {
            return ShapeBooleanResult.empty(ShapeBooleanStatus.CANCELLED)
        }
        if (subjectRings.isEmpty()) {
            // Nothing to cut, intersect or exclude against; union and exclude both reduce to the clip alone.
            return when (operation) {
                ShapeBooleanOperation.UNION, ShapeBooleanOperation.EXCLUDE ->
                    ShapeBooleanResult(pathOf(clipRings), ShapeBooleanStatus.PASSTHROUGH, 1)
                else -> ShapeBooleanResult.empty(ShapeBooleanStatus.CANCELLED)
            }
        }
        if (clipRings.isEmpty()) {
            return when (operation) {
                ShapeBooleanOperation.INTERSECT -> ShapeBooleanResult.empty(ShapeBooleanStatus.CANCELLED)
                else -> ShapeBooleanResult(pathOf(subjectRings), ShapeBooleanStatus.PASSTHROUGH, 1)
            }
        }

        val subjectEdges = edgesOf(subjectRings)
        val clipEdges = edgesOf(clipRings)
        if (subjectEdges.size > MAX_EDGES || clipEdges.size > MAX_EDGES) {
            return ShapeBooleanResult(pathOf(subjectRings), ShapeBooleanStatus.TOO_COMPLEX, 1)
        }

        val subjectSplits = Array(subjectEdges.size) { mutableListOf<Float>() }
        val clipSplits = Array(clipEdges.size) { mutableListOf<Float>() }
        crossCut(subjectEdges, clipEdges, subjectSplits, clipSplits)

        val kept = ArrayList<Edge>(subjectEdges.size + clipEdges.size)
        collect(subjectEdges, subjectSplits, clipRings, clipEdges, operation, isSubject = true, into = kept)
        collect(clipEdges, clipSplits, subjectRings, subjectEdges, operation, isSubject = false, into = kept)

        val stitched = stitch(kept)
        val rings = stitched.rings.filter { abs(ShapeCurves.signedArea(it)) >= MIN_AREA }
        val status = when {
            stitched.dropped -> ShapeBooleanStatus.PARTIAL
            rings.isEmpty() -> ShapeBooleanStatus.CANCELLED
            else -> ShapeBooleanStatus.RESOLVED
        }
        return ShapeBooleanResult(pathOf(rings), status, 2)
    }

    // ── Orientation ──────────────────────────────────────────────────────────────────────────────────

    /**
     * Re-orients a ring set so that outer rings run one way and holes the other.
     *
     * Presets, node-edited paths and earlier Boolean results all arrive with whatever winding they happened
     * to be built with. Normalising it up front is what lets everything downstream — containment, the keep
     * rules, the stitched output — use the non-zero rule and mean the same thing by it.
     */
    internal fun orient(rings: List<List<ShapePoint>>): List<List<ShapePoint>> {
        if (rings.isEmpty()) return rings
        val samples = rings.map { interiorPoint(it) }
        return rings.mapIndexed { index, ring ->
            val sample = samples[index]
            var depth = 0
            for (other in rings.indices) {
                if (other == index) continue
                if (sample != null && ShapeCurves.windingNumber(rings[other], sample) != 0) depth++
            }
            val wantsPositive = depth % 2 == 0
            val area = ShapeCurves.signedArea(ring)
            if (area == 0f) ring else if ((area > 0f) == wantsPositive) ring else ring.reversed()
        }
    }

    /**
     * A point strictly inside [ring], found by nudging edge midpoints to either side until one lands inside.
     * A centroid would do for convex rings and quietly fall outside a crescent, which is exactly the shape
     * someone reaches for a Boolean operation to make.
     */
    internal fun interiorPoint(ring: List<ShapePoint>): ShapePoint? {
        if (ring.size < 3) return null
        for (i in ring.indices) {
            val a = ring[i]
            val b = ring[(i + 1) % ring.size]
            val edge = b - a
            if (edge.length <= EPSILON) continue
            val normal = ShapePoint(-edge.y, edge.x).unit() * (EPSILON * 10f)
            val mid = ShapePoint.lerp(a, b, 0.5f)
            for (candidate in listOf(mid + normal, mid - normal)) {
                if (ShapeCurves.windingNumber(ring, candidate) != 0) return candidate
            }
        }
        return null
    }

    // ── Cutting ──────────────────────────────────────────────────────────────────────────────────────

    private fun edgesOf(rings: List<List<ShapePoint>>): List<Edge> {
        val edges = ArrayList<Edge>(rings.sumOf { it.size })
        for (ring in rings) {
            for (i in ring.indices) {
                val a = ring[i]
                val b = ring[(i + 1) % ring.size]
                if (a.distanceTo(b) > EPSILON) edges += Edge(a, b)
            }
        }
        return edges
    }

    /** Records, for every edge of each operand, the parameters at which the other operand crosses it. */
    private fun crossCut(
        subject: List<Edge>,
        clip: List<Edge>,
        subjectSplits: Array<MutableList<Float>>,
        clipSplits: Array<MutableList<Float>>,
    ) {
        for (i in subject.indices) {
            val a = subject[i]
            for (j in clip.indices) {
                val b = clip[j]
                if (!a.overlaps(b)) continue
                intersect(a, b) { tA, tB ->
                    subjectSplits[i] += tA
                    clipSplits[j] += tB
                }
            }
        }
    }

    /**
     * Reports where two segments meet.
     *
     * Collinear overlaps report the endpoints of the shared span rather than a crossing point, so that a
     * coincident edge is cut at exactly the places where its classification changes. Without that, an edge
     * half-shared with the other operand would be classified once, for both halves, and one of them wrongly.
     */
    private inline fun intersect(a: Edge, b: Edge, report: (Float, Float) -> Unit) {
        val d1 = a.end - a.start
        val d2 = b.end - b.start
        val denominator = d1.x * d2.y - d1.y * d2.x
        val delta = b.start - a.start
        if (abs(denominator) > 1e-7f) {
            val tA = (delta.x * d2.y - delta.y * d2.x) / denominator
            val tB = (delta.x * d1.y - delta.y * d1.x) / denominator
            if (tA in -1e-4f..1.0001f && tB in -1e-4f..1.0001f) {
                report(tA.coerceIn(0f, 1f), tB.coerceIn(0f, 1f))
            }
            return
        }
        // Parallel. Only a collinear pair can share points, and then the shared span is what matters.
        if (abs(delta.x * d1.y - delta.y * d1.x) > EPSILON * max(1f, d1.length)) return
        val lengthSquared = d1.x * d1.x + d1.y * d1.y
        if (lengthSquared <= 1e-9f) return
        val tStart = ((b.start - a.start).let { it.x * d1.x + it.y * d1.y }) / lengthSquared
        val tEnd = ((b.end - a.start).let { it.x * d1.x + it.y * d1.y }) / lengthSquared
        val low = min(tStart, tEnd)
        val high = max(tStart, tEnd)
        if (high < 0f || low > 1f) return
        val clampedLow = low.coerceIn(0f, 1f)
        val clampedHigh = high.coerceIn(0f, 1f)
        report(clampedLow, if (tStart <= tEnd) 0f else 1f)
        report(clampedHigh, if (tStart <= tEnd) 1f else 0f)
    }

    // ── Classification ───────────────────────────────────────────────────────────────────────────────

    private fun collect(
        edges: List<Edge>,
        splits: Array<MutableList<Float>>,
        otherRings: List<List<ShapePoint>>,
        otherEdges: List<Edge>,
        operation: ShapeBooleanOperation,
        isSubject: Boolean,
        into: MutableList<Edge>,
    ) {
        for (i in edges.indices) {
            val edge = edges[i]
            val parameters = parametersFor(splits[i])
            for (p in 0 until parameters.size - 1) {
                val start = edge.pointAt(parameters[p])
                val end = edge.pointAt(parameters[p + 1])
                if (start.distanceTo(end) <= EPSILON) continue
                val piece = Edge(start, end)
                when (decide(piece, otherRings, otherEdges, operation, isSubject)) {
                    Verdict.KEEP -> into += piece
                    Verdict.REVERSE -> into += piece.reversed()
                    Verdict.DROP -> Unit
                }
            }
        }
    }

    private fun parametersFor(splits: MutableList<Float>): List<Float> {
        if (splits.isEmpty()) return listOf(0f, 1f)
        splits.sort()
        val out = ArrayList<Float>(splits.size + 2)
        out += 0f
        for (t in splits) {
            if (t > out.last() + 1e-5f && t < 1f - 1e-5f) out += t
        }
        out += 1f
        return out
    }

    /**
     * The keep rules.
     *
     * Every operation is the same question asked of one edge at a time: does the region on the far side of
     * this piece survive? Union keeps what is outside the other shape, intersect keeps what is inside, and
     * subtract keeps the subject's outside plus the clip's inside walked backwards — a reversed clip edge
     * is precisely a hole boundary under the non-zero rule.
     *
     * Edges lying along the other outline are the ambiguous case, and they are settled by convention: the
     * subject's copy is kept when the two run the same way, and the clip's copy is always dropped, so a
     * shared border contributes exactly one edge instead of two or none.
     */
    private fun decide(
        piece: Edge,
        otherRings: List<List<ShapePoint>>,
        otherEdges: List<Edge>,
        operation: ShapeBooleanOperation,
        isSubject: Boolean,
    ): Verdict {
        val midpoint = ShapePoint.lerp(piece.start, piece.end, 0.5f)
        val onBoundary = ShapeCurves.distanceToOutline(otherRings, midpoint) <= BOUNDARY_TOLERANCE
        if (onBoundary) {
            if (!isSubject) return Verdict.DROP
            val sameDirection = agreesWith(piece, otherEdges, midpoint)
            return when (operation) {
                ShapeBooleanOperation.UNION,
                ShapeBooleanOperation.INTERSECT,
                -> if (sameDirection) Verdict.KEEP else Verdict.DROP
                ShapeBooleanOperation.SUBTRACT -> if (sameDirection) Verdict.DROP else Verdict.KEEP
                ShapeBooleanOperation.EXCLUDE -> Verdict.DROP
            }
        }

        val inside = ShapeCurves.contains(otherRings, midpoint, ShapeFillRule.NON_ZERO)
        return when (operation) {
            ShapeBooleanOperation.UNION -> if (inside) Verdict.DROP else Verdict.KEEP
            ShapeBooleanOperation.INTERSECT -> if (inside) Verdict.KEEP else Verdict.DROP
            ShapeBooleanOperation.SUBTRACT ->
                if (isSubject) {
                    if (inside) Verdict.DROP else Verdict.KEEP
                } else {
                    if (inside) Verdict.REVERSE else Verdict.DROP
                }
            ShapeBooleanOperation.EXCLUDE -> if (inside) Verdict.REVERSE else Verdict.KEEP
        }
    }

    /** Whether the other operand's nearest edge runs the same way as [piece], for coincident boundaries. */
    private fun agreesWith(piece: Edge, otherEdges: List<Edge>, midpoint: ShapePoint): Boolean {
        var best: Edge? = null
        var bestDistance = Float.MAX_VALUE
        for (edge in otherEdges) {
            val distance = ShapeCurves.distanceToSegment(edge.start, edge.end, midpoint)
            if (distance < bestDistance) {
                bestDistance = distance
                best = edge
            }
        }
        val other = best ?: return false
        val a = (piece.end - piece.start).unit()
        val b = (other.end - other.start).unit()
        return a.x * b.x + a.y * b.y > 0f
    }

    // ── Stitching ────────────────────────────────────────────────────────────────────────────────────

    private class Stitched(val rings: List<List<ShapePoint>>, val dropped: Boolean)

    /**
     * Walks the surviving edges back into closed rings.
     *
     * Edges are indexed by a quantised start point so that a chain can find its continuation without
     * scanning everything, and neighbouring buckets are searched too, because two endpoints that agree to
     * within the tolerance can still round to different cells. Where several edges leave the same point —
     * two boundaries crossing — the sharpest turn wins, which keeps the two lobes separate instead of
     * splicing them into one ring.
     */
    private fun stitch(edges: List<Edge>): Stitched {
        if (edges.isEmpty()) return Stitched(emptyList(), dropped = false)
        val used = BooleanArray(edges.size)
        val index = HashMap<Long, MutableList<Int>>(edges.size * 2)
        for (i in edges.indices) index.getOrPut(cell(edges[i].start)) { mutableListOf() } += i

        val rings = ArrayList<List<ShapePoint>>()
        var dropped = false
        for (seed in edges.indices) {
            if (used[seed]) continue
            used[seed] = true
            val ring = ArrayList<ShapePoint>()
            ring += edges[seed].start
            var current = edges[seed]
            val origin = edges[seed].start
            var closed = false
            // The edge count is the only bound that matters: every step consumes one, so a walk that never
            // closes still terminates instead of circling a duplicated point forever.
            for (ignored in edges.indices) {
                ring += current.end
                if (current.end.distanceTo(origin) <= EPSILON * 10f) {
                    closed = true
                    break
                }
                val next = continuation(edges, used, index, current) ?: break
                used[next] = true
                current = edges[next]
            }
            if (!closed && ring.size >= 3 && ring.last().distanceTo(origin) <= CLOSE_TOLERANCE) closed = true
            if (closed && ring.size >= 3) {
                if (ring.last().distanceTo(origin) <= EPSILON * 10f) ring.removeAt(ring.size - 1)
                if (ring.size >= 3) rings += ring else dropped = true
            } else {
                dropped = true
            }
        }
        return Stitched(rings, dropped)
    }

    private fun continuation(
        edges: List<Edge>,
        used: BooleanArray,
        index: Map<Long, MutableList<Int>>,
        current: Edge,
    ): Int? {
        val candidates = ArrayList<Int>(4)
        val originX = (current.end.x / EPSILON).roundToInt()
        val originY = (current.end.y / EPSILON).roundToInt()
        for (dx in -1..1) {
            for (dy in -1..1) {
                val bucket = index[key(originX + dx, originY + dy)] ?: continue
                for (candidate in bucket) {
                    if (used[candidate]) continue
                    if (edges[candidate].start.distanceTo(current.end) > EPSILON * 10f) continue
                    candidates += candidate
                }
            }
        }
        if (candidates.isEmpty()) return null
        if (candidates.size == 1) return candidates.first()

        // Several edges leave this point, so it is a crossing where two boundaries meet. Turning as far as
        // possible one way — rather than going as straight as possible — is what keeps the two boundaries
        // apart. At a symmetric crossing, "straightest" is a tie between the two departures and picking
        // either one splices the lobes into a single ring with the right area and the wrong shape; the
        // sharpest turn is never a tie, so each arrival is paired with exactly one departure.
        val arrival = atan2(current.end.y - current.start.y, current.end.x - current.start.x)
        var best: Int? = null
        var bestTurn = Float.MAX_VALUE
        for (candidate in candidates) {
            val edge = edges[candidate]
            val heading = atan2(edge.end.y - edge.start.y, edge.end.x - edge.start.x)
            val turn = normalizeAngle(heading - arrival)
            if (turn < bestTurn) {
                bestTurn = turn
                best = candidate
            }
        }
        return best
    }

    /** A turn as `0..2π` measured from the arrival direction, so "sharpest turn" is a total order. */
    private fun normalizeAngle(radians: Float): Float {
        val full = (2.0 * PI).toFloat()
        var value = radians % full
        if (value < 0f) value += full
        return value
    }

    private fun cell(point: ShapePoint): Long =
        key((point.x / EPSILON).roundToInt(), (point.y / EPSILON).roundToInt())

    private fun key(x: Int, y: Int): Long = (x.toLong() shl 32) xor (y.toLong() and 0xFFFFFFFFL)

    // ── Output ───────────────────────────────────────────────────────────────────────────────────────

    /**
     * A Boolean result is a polygon, so every node is a corner. Saying so in the node type is what keeps a
     * later smooth-node operation from claiming handles that were never there.
     */
    private fun pathOf(rings: List<List<ShapePoint>>): ShapePath {
        val subpaths = rings.filter { it.size >= 3 }.map { ShapeSubpath.polygon(it, closed = true) }
        return ShapePath(subpaths, ShapeFillRule.NON_ZERO).normalized()
    }

    private enum class Verdict { KEEP, REVERSE, DROP }

    private class Edge(val start: ShapePoint, val end: ShapePoint) {
        val minX = min(start.x, end.x)
        val maxX = max(start.x, end.x)
        val minY = min(start.y, end.y)
        val maxY = max(start.y, end.y)

        fun pointAt(t: Float): ShapePoint = ShapePoint.lerp(start, end, t)

        fun reversed() = Edge(end, start)

        fun overlaps(other: Edge): Boolean =
            minX - EPSILON <= other.maxX && maxX + EPSILON >= other.minX &&
                minY - EPSILON <= other.maxY && maxY + EPSILON >= other.minY
    }
}
