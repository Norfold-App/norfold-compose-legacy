package com.norfold.app.editor.shape

import kotlinx.serialization.Serializable
import java.util.UUID
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * The vector primitives Shape Builder edits: points, nodes, subpaths and the cubic maths over them.
 *
 * **Everything here is in the shape's own local canvas**, a [ShapeCanvas.SIDE]-unit square, never in page
 * points and never in screen pixels. That separation is the whole reason a shape can be moved, resized or
 * rotated on the page without a single node coordinate changing: placement lives in `SceneGeometry`, form
 * lives here, and the renderer is the only thing that knows both. The defect it exists to prevent is the
 * one where dragging an object across the page silently rewrites its geometry, so an undo of the *move*
 * cannot restore the *shape*.
 *
 * Handles are stored as **offsets from their node**, not absolute positions. Moving a node then moves its
 * curve with it for free, and the two constrained node types are expressible as exact relations
 * (`controlIn == -controlOut` for a symmetric node) rather than as a pair of coordinates that drift apart
 * after enough edits.
 *
 * Pure-JVM and free of Android and Compose types, so all of it is unit-testable without a device.
 */

/** Local-canvas conventions. Local units are resolution-independent; the renderer maps them to the box. */
object ShapeCanvas {
    /** The local canvas is a square of this side. A shape is authored in `0..100` on both axes. */
    const val SIDE = 100f

    /** Coordinates may leave the canvas — an overshooting handle is legitimate — but not without bound. */
    const val LIMIT = 10_000f

    val CENTER = ShapePoint(SIDE / 2f, SIDE / 2f)
}

internal fun shapeId(prefix: String): String = "$prefix-${UUID.randomUUID()}"

internal fun Float.finiteOr(fallback: Float): Float = if (isFinite()) this else fallback

internal fun Float.approximately(other: Float, epsilon: Float = 1e-4f): Boolean = abs(this - other) <= epsilon

/** A point in the shape's local canvas. Doubles as a vector for handle offsets. */
@Serializable
data class ShapePoint(val x: Float = 0f, val y: Float = 0f) {
    operator fun plus(other: ShapePoint) = ShapePoint(x + other.x, y + other.y)
    operator fun minus(other: ShapePoint) = ShapePoint(x - other.x, y - other.y)
    operator fun times(factor: Float) = ShapePoint(x * factor, y * factor)
    operator fun unaryMinus() = ShapePoint(-x, -y)

    val length: Float get() = hypot(x, y)
    val isZero: Boolean get() = x == 0f && y == 0f

    fun distanceTo(other: ShapePoint): Float = hypot(x - other.x, y - other.y)

    /** Unit vector, or [ShapePoint] zero for a zero-length vector — there is no meaningful direction there. */
    fun unit(): ShapePoint {
        val len = length
        return if (len <= 0f || !len.isFinite()) ShapePoint() else ShapePoint(x / len, y / len)
    }

    /** Same direction, given length. A zero vector stays zero rather than inventing a direction. */
    fun withLength(target: Float): ShapePoint = unit() * target

    fun normalized(): ShapePoint = ShapePoint(
        x.finiteOr(0f).coerceIn(-ShapeCanvas.LIMIT, ShapeCanvas.LIMIT),
        y.finiteOr(0f).coerceIn(-ShapeCanvas.LIMIT, ShapeCanvas.LIMIT),
    )

    fun approximately(other: ShapePoint, epsilon: Float = 1e-4f): Boolean =
        x.approximately(other.x, epsilon) && y.approximately(other.y, epsilon)

    companion object {
        fun lerp(a: ShapePoint, b: ShapePoint, t: Float) = ShapePoint(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t)
    }
}

/** An axis-aligned box in local canvas units. */
@Serializable
data class ShapeRect(
    val left: Float = 0f,
    val top: Float = 0f,
    val right: Float = ShapeCanvas.SIDE,
    val bottom: Float = ShapeCanvas.SIDE,
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f
    val center: ShapePoint get() = ShapePoint(centerX, centerY)
    val isEmpty: Boolean get() = width <= 0f || height <= 0f

    fun contains(point: ShapePoint): Boolean =
        point.x >= left && point.x <= right && point.y >= top && point.y <= bottom

    fun union(other: ShapeRect): ShapeRect = ShapeRect(
        min(left, other.left), min(top, other.top), max(right, other.right), max(bottom, other.bottom),
    )

    fun expandedBy(amount: Float): ShapeRect =
        ShapeRect(left - amount, top - amount, right + amount, bottom + amount)

    companion object {
        val CANVAS = ShapeRect()

        /** The empty-but-valid seed for a fold; unioning it with a real box yields that box. */
        val EMPTY = ShapeRect(Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE)

        fun around(points: List<ShapePoint>): ShapeRect {
            if (points.isEmpty()) return ShapeRect(0f, 0f, 0f, 0f)
            var l = points[0].x; var t = points[0].y; var r = points[0].x; var b = points[0].y
            for (p in points) { l = min(l, p.x); t = min(t, p.y); r = max(r, p.x); b = max(b, p.y) }
            return ShapeRect(l, t, r, b)
        }
    }
}

/**
 * How a node's two handles are constrained to each other.
 *
 * The distinction is the difference between a hard vertex and a smooth join, and it has to be *stored*
 * rather than inferred: two handles that happen to be collinear today are not a promise that the curve
 * stays smooth after the next drag. Storing intent means dragging one side of a smooth node keeps the
 * other side smooth, which is the behaviour that makes node editing usable with a fingertip.
 */
@Serializable
enum class ShapeNodeType {
    /** Handles are independent. The node may be a sharp vertex. */
    CORNER,

    /** Handles stay collinear; their lengths are independent. */
    SMOOTH,

    /** Handles stay collinear *and* equal in length — a perfectly balanced curve through the node. */
    SYMMETRIC,
}

/**
 * One on-path anchor and its two off-path handles, expressed as offsets from [point].
 *
 * [id] is stable for the node's lifetime. Node editing addresses nodes by id rather than by index because
 * a drag that carries one node past another reorders nothing but *would* change every index — an
 * index-addressed drag would silently start editing the neighbour mid-gesture.
 */
@Serializable
data class ShapeNode(
    val id: String = shapeId("nd"),
    val point: ShapePoint = ShapePoint(),
    val controlIn: ShapePoint = ShapePoint(),
    val controlOut: ShapePoint = ShapePoint(),
    val type: ShapeNodeType = ShapeNodeType.CORNER,
) {
    /** Absolute position of the handle that governs the segment *arriving* at this node. */
    val incomingHandle: ShapePoint get() = point + controlIn

    /** Absolute position of the handle that governs the segment *leaving* this node. */
    val outgoingHandle: ShapePoint get() = point + controlOut

    val isSharp: Boolean get() = controlIn.isZero && controlOut.isZero

    /**
     * Repairs the node to satisfy its own [type].
     *
     * [controlOut] leads when a constraint has to break a tie, so repair is deterministic: loading the same
     * stored node twice cannot produce two different curves. The one exception is a zero [controlOut] — a
     * node with only an incoming handle is legitimate, and letting the zero side lead would flatten a curve
     * the user drew. Interactive edits that need the other handle to lead go through [ShapePathEditor],
     * which mirrors explicitly.
     */
    fun normalized(): ShapeNode {
        val cleanPoint = point.normalized()
        val out = controlOut.normalized()
        val incoming = controlIn.normalized()
        val (repairedIn, repairedOut) = when (type) {
            ShapeNodeType.CORNER -> incoming to out
            ShapeNodeType.SMOOTH ->
                if (out.isZero || incoming.isZero) incoming to out
                else (-out).withLength(incoming.length) to out
            ShapeNodeType.SYMMETRIC ->
                if (out.isZero) incoming to -incoming else -out to out
        }
        return copy(
            id = id.trim().take(MAX_ID).ifEmpty { shapeId("nd") },
            point = cleanPoint,
            controlIn = repairedIn,
            controlOut = repairedOut,
        )
    }

    companion object {
        const val MAX_ID = 128

        fun at(x: Float, y: Float, type: ShapeNodeType = ShapeNodeType.CORNER) =
            ShapeNode(point = ShapePoint(x, y), type = type)
    }
}

/** Whether overlapping windings fill or cancel. Only meaningful for a path with more than one subpath. */
@Serializable
enum class ShapeFillRule { NON_ZERO, EVEN_ODD }

/**
 * One continuous run of nodes, optionally closed.
 *
 * An open subpath still strokes; only a closed one fills. Keeping the two in one type (rather than
 * separate "polyline" and "polygon" types) is what lets Close/Open be a single reversible edit instead of
 * a conversion that loses the handles.
 */
@Serializable
data class ShapeSubpath(
    val id: String = shapeId("sp"),
    val nodes: List<ShapeNode> = emptyList(),
    val closed: Boolean = true,
) {
    val isRenderable: Boolean get() = nodes.size >= 2

    /** Cubic segments in draw order. A closed subpath adds the wrap-around segment; an open one does not. */
    fun segments(): List<ShapeSegment> {
        if (nodes.size < 2) return emptyList()
        val result = ArrayList<ShapeSegment>(nodes.size)
        for (i in 0 until nodes.size - 1) result += ShapeSegment.between(nodes[i], nodes[i + 1])
        if (closed) result += ShapeSegment.between(nodes.last(), nodes.first())
        return result
    }

    fun nodeById(nodeId: String): ShapeNode? = nodes.firstOrNull { it.id == nodeId }

    fun indexOf(nodeId: String): Int = nodes.indexOfFirst { it.id == nodeId }

    fun bounds(): ShapeRect =
        segments().fold(ShapeRect.EMPTY) { acc, seg -> acc.union(seg.bounds()) }
            .takeIf { it.left <= it.right } ?: ShapeRect.around(nodes.map { it.point })

    /**
     * Repairs ids and nodes, and drops a subpath that cannot draw.
     *
     * Duplicate ids are re-minted rather than de-duplicated by removal: a stored document with two nodes
     * sharing an id is corrupt, but the *geometry* is still what the user drew, and throwing away a node
     * to fix bookkeeping would silently change their shape.
     */
    fun normalized(): ShapeSubpath {
        val seen = HashSet<String>(nodes.size)
        val cleaned = nodes.take(MAX_NODES).map { node ->
            val fixed = node.normalized()
            if (seen.add(fixed.id)) fixed else fixed.copy(id = shapeId("nd")).also { seen.add(it.id) }
        }
        return copy(id = id.trim().take(ShapeNode.MAX_ID).ifEmpty { shapeId("sp") }, nodes = cleaned)
    }

    companion object {
        /** A ceiling that keeps a hostile or generated path from making the editor unusable. */
        const val MAX_NODES = 4_000

        /** A closed polygon through the given points, all corners. */
        fun polygon(points: List<ShapePoint>, closed: Boolean = true) = ShapeSubpath(
            nodes = points.map { ShapeNode(point = it, type = ShapeNodeType.CORNER) },
            closed = closed,
        )
    }
}

/** A whole path: one or more subpaths sharing a fill rule. The geometry a layer draws. */
@Serializable
data class ShapePath(
    val subpaths: List<ShapeSubpath> = emptyList(),
    val fillRule: ShapeFillRule = ShapeFillRule.NON_ZERO,
) {
    val isEmpty: Boolean get() = subpaths.none { it.isRenderable }
    val nodeCount: Int get() = subpaths.sumOf { it.nodes.size }

    fun subpathById(subpathId: String): ShapeSubpath? = subpaths.firstOrNull { it.id == subpathId }

    fun bounds(): ShapeRect {
        val renderable = subpaths.filter { it.isRenderable }
        if (renderable.isEmpty()) return ShapeRect(0f, 0f, 0f, 0f)
        return renderable.map { it.bounds() }.reduce { a, b -> a.union(b) }
    }

    fun mapSubpaths(transform: (ShapeSubpath) -> ShapeSubpath): ShapePath =
        copy(subpaths = subpaths.map(transform))

    /** Applies a point transform to every anchor and handle. Handles are offsets, so they map as vectors. */
    fun mapPoints(transform: (ShapePoint) -> ShapePoint): ShapePath = mapSubpaths { subpath ->
        subpath.copy(nodes = subpath.nodes.map { node ->
            val movedPoint = transform(node.point)
            node.copy(
                point = movedPoint,
                controlIn = transform(node.point + node.controlIn) - movedPoint,
                controlOut = transform(node.point + node.controlOut) - movedPoint,
            )
        })
    }

    fun normalized(): ShapePath {
        val seen = HashSet<String>(subpaths.size)
        val cleaned = subpaths.take(MAX_SUBPATHS).map { it.normalized() }
            .filter { it.nodes.isNotEmpty() }
            .map { subpath -> if (seen.add(subpath.id)) subpath else subpath.copy(id = shapeId("sp")).also { seen.add(it.id) } }
        return copy(subpaths = cleaned)
    }

    companion object {
        const val MAX_SUBPATHS = 256

        fun of(vararg subpaths: ShapeSubpath) = ShapePath(subpaths.toList())
    }
}

/**
 * One cubic Bézier segment, resolved from a node pair into absolute control points.
 *
 * Segments are derived, never stored — storing both nodes and segments would be two representations of
 * one curve, and the day they disagree is the day the rendered shape stops matching the editable one.
 */
data class ShapeSegment(
    val start: ShapePoint,
    val control1: ShapePoint,
    val control2: ShapePoint,
    val end: ShapePoint,
) {
    /** True when both handles are unused, i.e. the segment is a straight line. */
    val isLine: Boolean get() = control1.approximately(start) && control2.approximately(end)

    fun pointAt(t: Float): ShapePoint {
        val u = 1f - t
        val a = u * u * u
        val b = 3f * u * u * t
        val c = 3f * u * t * t
        val d = t * t * t
        return ShapePoint(
            a * start.x + b * control1.x + c * control2.x + d * end.x,
            a * start.y + b * control1.y + c * control2.y + d * end.y,
        )
    }

    /**
     * Exact bounds, from the roots of the derivative rather than from sampling.
     *
     * A sampled bound is always slightly too small, and a selection box that is slightly too small is one
     * the user can see the shape poking out of.
     */
    fun bounds(): ShapeRect {
        val xs = axisExtremes(start.x, control1.x, control2.x, end.x)
        val ys = axisExtremes(start.y, control1.y, control2.y, end.y)
        return ShapeRect(xs.first, ys.first, xs.second, ys.second)
    }

    /** de Casteljau split at [t], returning the two halves whose union is exactly this curve. */
    fun splitAt(t: Float): Pair<ShapeSegment, ShapeSegment> {
        val clamped = t.finiteOr(0.5f).coerceIn(0f, 1f)
        val p01 = ShapePoint.lerp(start, control1, clamped)
        val p12 = ShapePoint.lerp(control1, control2, clamped)
        val p23 = ShapePoint.lerp(control2, end, clamped)
        val p012 = ShapePoint.lerp(p01, p12, clamped)
        val p123 = ShapePoint.lerp(p12, p23, clamped)
        val mid = ShapePoint.lerp(p012, p123, clamped)
        return ShapeSegment(start, p01, p012, mid) to ShapeSegment(mid, p123, p23, end)
    }

    /** Adaptive flattening to a polyline, including both endpoints. */
    fun flatten(tolerance: Float = ShapeCurves.DEFAULT_TOLERANCE): List<ShapePoint> {
        val out = ArrayList<ShapePoint>(8)
        subdivide(this, tolerance, 0, out)
        out += end
        return out
    }

    /** Nearest point on the curve to [target], as `(t, distance)`. Sampled then refined — cheap and stable. */
    fun nearest(target: ShapePoint, samples: Int = 24): Pair<Float, Float> {
        var bestT = 0f
        var bestD = Float.MAX_VALUE
        val steps = samples.coerceAtLeast(4)
        for (i in 0..steps) {
            val t = i.toFloat() / steps
            val d = pointAt(t).distanceTo(target)
            if (d < bestD) { bestD = d; bestT = t }
        }
        // Refine within one sample interval. Three bisections take the error well below a touch target,
        // which is the only precision that matters for "which part of the curve did the finger mean".
        var span = 1f / steps
        repeat(3) {
            span /= 2f
            val lower = (bestT - span).coerceIn(0f, 1f)
            val upper = (bestT + span).coerceIn(0f, 1f)
            val dl = pointAt(lower).distanceTo(target)
            val du = pointAt(upper).distanceTo(target)
            if (dl < bestD) { bestD = dl; bestT = lower }
            if (du < bestD) { bestD = du; bestT = upper }
        }
        return bestT to bestD
    }

    companion object {
        fun between(from: ShapeNode, to: ShapeNode) =
            ShapeSegment(from.point, from.outgoingHandle, to.incomingHandle, to.point)

        private fun subdivide(segment: ShapeSegment, tolerance: Float, depth: Int, out: MutableList<ShapePoint>) {
            if (depth >= ShapeCurves.MAX_FLATTEN_DEPTH || segment.isFlatWithin(tolerance)) {
                out += segment.start
                return
            }
            val (left, right) = segment.splitAt(0.5f)
            subdivide(left, tolerance, depth + 1, out)
            subdivide(right, tolerance, depth + 1, out)
        }

        private fun axisExtremes(p0: Float, p1: Float, p2: Float, p3: Float): Pair<Float, Float> {
            var lo = min(p0, p3)
            var hi = max(p0, p3)
            // B'(t) = 3[(p1-p0)(1-t)^2 + 2(p2-p1)(1-t)t + (p3-p2)t^2] → at^2 + bt + c after expansion.
            val a = -p0 + 3f * p1 - 3f * p2 + p3
            val b = 2f * (p0 - 2f * p1 + p2)
            val c = p1 - p0
            fun consider(t: Float) {
                if (t <= 0f || t >= 1f || !t.isFinite()) return
                val u = 1f - t
                val value = u * u * u * p0 + 3f * u * u * t * p1 + 3f * u * t * t * p2 + t * t * t * p3
                lo = min(lo, value); hi = max(hi, value)
            }
            if (abs(a) < 1e-6f) {
                if (abs(b) > 1e-6f) consider(-c / b)
            } else {
                val discriminant = b * b - 4f * a * c
                if (discriminant >= 0f) {
                    val root = sqrt(discriminant)
                    consider((-b + root) / (2f * a))
                    consider((-b - root) / (2f * a))
                }
            }
            return lo to hi
        }
    }

    /** Flat when both handles sit within [tolerance] of the chord — the standard control-polygon test. */
    private fun isFlatWithin(tolerance: Float): Boolean {
        val dx = end.x - start.x
        val dy = end.y - start.y
        val chord = hypot(dx, dy)
        if (chord < 1e-6f) return control1.distanceTo(start) <= tolerance && control2.distanceTo(start) <= tolerance
        val d1 = abs((control1.x - start.x) * dy - (control1.y - start.y) * dx) / chord
        val d2 = abs((control2.x - start.x) * dy - (control2.y - start.y) * dx) / chord
        return max(d1, d2) <= tolerance
    }
}

/** Flattening and containment. The bridge between editable curves and the polygon world Boolean ops need. */
object ShapeCurves {
    /** Local units. At a canvas side of 100 this keeps a flattened circle visually smooth at any zoom. */
    const val DEFAULT_TOLERANCE = 0.05f

    /** Recursion ceiling, so a degenerate control polygon cannot subdivide forever. */
    const val MAX_FLATTEN_DEPTH = 12

    /**
     * A subpath as a polyline. The first point is included once; a closed subpath is *not* given a
     * duplicated final point, so callers can treat the ring as `points[i] → points[(i+1) % size]`.
     */
    fun flatten(subpath: ShapeSubpath, tolerance: Float = DEFAULT_TOLERANCE): List<ShapePoint> {
        if (!subpath.isRenderable) return subpath.nodes.map { it.point }
        val out = ArrayList<ShapePoint>(subpath.nodes.size * 4)
        out += subpath.nodes.first().point
        // Each segment's flattening starts at its own start point, which is the previous segment's end and
        // is already in `out` — dropping it is what keeps the ring free of zero-length edges, which would
        // otherwise contribute nothing to area but still be tested by every containment query.
        for (segment in subpath.segments()) {
            val flattened = segment.flatten(tolerance)
            for (i in 1 until flattened.size) out += flattened[i]
        }
        if (subpath.closed && out.size > 1 && out.first().approximately(out.last())) out.removeAt(out.size - 1)
        return out
    }

    fun flatten(path: ShapePath, tolerance: Float = DEFAULT_TOLERANCE): List<List<ShapePoint>> =
        path.subpaths.filter { it.isRenderable }.map { flatten(it, tolerance) }

    /** Signed area of a ring; positive is counter-clockwise in a y-down canvas being clockwise on screen. */
    fun signedArea(ring: List<ShapePoint>): Float {
        if (ring.size < 3) return 0f
        var sum = 0f
        for (i in ring.indices) {
            val a = ring[i]
            val b = ring[(i + 1) % ring.size]
            sum += a.x * b.y - b.x * a.y
        }
        return sum / 2f
    }

    /** Winding number of [point] with respect to [ring]; zero means outside under the non-zero rule. */
    fun windingNumber(ring: List<ShapePoint>, point: ShapePoint): Int {
        if (ring.size < 3) return 0
        var winding = 0
        for (i in ring.indices) {
            val a = ring[i]
            val b = ring[(i + 1) % ring.size]
            if (a.y <= point.y) {
                if (b.y > point.y && cross(a, b, point) > 0f) winding++
            } else {
                if (b.y <= point.y && cross(a, b, point) < 0f) winding--
            }
        }
        return winding
    }

    /** Whether [point] is inside the flattened [path] under its own fill rule. */
    fun contains(path: ShapePath, point: ShapePoint, tolerance: Float = DEFAULT_TOLERANCE): Boolean =
        contains(flatten(path, tolerance), point, path.fillRule)

    fun contains(rings: List<List<ShapePoint>>, point: ShapePoint, rule: ShapeFillRule): Boolean {
        var winding = 0
        var crossings = 0
        for (ring in rings) {
            val w = windingNumber(ring, point)
            winding += w
            crossings += abs(w)
        }
        return when (rule) {
            ShapeFillRule.NON_ZERO -> winding != 0
            ShapeFillRule.EVEN_ODD -> crossings % 2 == 1
        }
    }

    /** Shortest distance from [point] to any edge of [rings]; for stroke hit-testing near an outline. */
    fun distanceToOutline(rings: List<List<ShapePoint>>, point: ShapePoint): Float {
        var best = Float.MAX_VALUE
        for (ring in rings) {
            if (ring.size < 2) {
                ring.firstOrNull()?.let { best = min(best, it.distanceTo(point)) }
                continue
            }
            for (i in ring.indices) {
                val a = ring[i]
                val b = ring[(i + 1) % ring.size]
                best = min(best, distanceToSegment(a, b, point))
            }
        }
        return best
    }

    fun distanceToSegment(a: ShapePoint, b: ShapePoint, point: ShapePoint): Float {
        val dx = b.x - a.x
        val dy = b.y - a.y
        val lengthSquared = dx * dx + dy * dy
        if (lengthSquared <= 1e-9f) return a.distanceTo(point)
        val t = (((point.x - a.x) * dx + (point.y - a.y) * dy) / lengthSquared).coerceIn(0f, 1f)
        return ShapePoint(a.x + dx * t, a.y + dy * t).distanceTo(point)
    }

    private fun cross(a: ShapePoint, b: ShapePoint, p: ShapePoint): Float =
        (b.x - a.x) * (p.y - a.y) - (p.x - a.x) * (b.y - a.y)
}
