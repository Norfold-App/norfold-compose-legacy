package com.norfold.app.editor.shape

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Where a node lives. Subpath plus node, because a node id is only unique within its own subpath. */
data class ShapeNodeRef(val subpathId: String, val nodeId: String)

/** Which of a node's two handles a gesture has hold of. */
enum class ShapeHandleSide { IN, OUT }

/** A handle grabbed by a gesture. */
data class ShapeHandleRef(val node: ShapeNodeRef, val side: ShapeHandleSide)

/** A point on a segment, as the segment's own index plus the parameter along it. */
data class ShapeSegmentHit(
    val subpathId: String,
    /** Index into [ShapeSubpath.segments]; for a closed subpath the last one wraps back to node 0. */
    val segmentIndex: Int,
    val t: Float,
    val point: ShapePoint,
    val distance: Float,
)

/**
 * Node-level editing of a [ShapePath].
 *
 * Every operation is a pure function from path to path, for the same reason [ShapeLayerEditor] is: the
 * runtime compares two documents rather than replaying a stream of edits, so an undo step is a value and
 * not a reversal that has to be implemented separately and kept correct.
 *
 * Two invariants hold across all of it. First, **inserting or deleting a node never silently changes what
 * the rest of the curve does** — Add Point subdivides the existing cubic exactly, so the outline is
 * unchanged to the last bit. Second, **node type is honoured on write, not on read**: every operation that
 * moves a point or a handle re-applies the node's own constraint through [ShapeNode.normalized], so a
 * symmetric node cannot be left holding two handles that disagree.
 */
object ShapePathEditor {

    /** Touch radius in local canvas units. At a 100-unit canvas this is a comfortable finger target. */
    const val DEFAULT_HIT_RADIUS = 4f

    /** Handles are precise targets, so they answer only to a tighter radius than nodes get. */
    const val HANDLE_HIT_RADIUS = 3f

    // ── Moving points ────────────────────────────────────────────────────────────────────────────────

    /** Moves one node to [to], carrying its handles with it — the handles are offsets, so this is free. */
    fun moveNode(path: ShapePath, ref: ShapeNodeRef, to: ShapePoint): ShapePath =
        editNode(path, ref) { it.copy(point = to.normalized()) }

    fun moveNodeBy(path: ShapePath, ref: ShapeNodeRef, delta: ShapePoint): ShapePath =
        editNode(path, ref) { it.copy(point = (it.point + delta).normalized()) }

    /**
     * Moves several nodes by the same delta, for a marquee or multi-touch drag.
     *
     * Applied in one pass rather than by folding [moveNodeBy], so a selection that happens to contain two
     * nodes of the same subpath cannot have the second one resolved against a path the first already
     * changed — the delta means the same thing for every node in the selection.
     */
    fun moveNodesBy(path: ShapePath, refs: Collection<ShapeNodeRef>, delta: ShapePoint): ShapePath {
        if (refs.isEmpty()) return path
        val bySubpath = refs.groupBy({ it.subpathId }, { it.nodeId })
        return path.mapSubpaths { subpath ->
            val ids = bySubpath[subpath.id]?.toSet() ?: return@mapSubpaths subpath
            subpath.copy(
                nodes = subpath.nodes.map { node ->
                    if (node.id in ids) node.copy(point = (node.point + delta).normalized()) else node
                },
            )
        }.normalized()
    }

    // ── Moving handles ───────────────────────────────────────────────────────────────────────────────

    /**
     * Drags one handle to the absolute canvas position [to].
     *
     * The stored value is the offset from the node, and [ShapeNode.normalized] then applies the node's
     * type: a smooth node turns the opposite handle to match the new direction while keeping its length,
     * and a symmetric node mirrors it outright. Dragging one handle of a symmetric node moving the other
     * is the behaviour, not a side effect — it is what "symmetric" means, and doing it here means no
     * caller has to remember to.
     */
    fun moveHandle(path: ShapePath, ref: ShapeNodeRef, side: ShapeHandleSide, to: ShapePoint): ShapePath =
        editNode(path, ref) { node ->
            val offset = (to - node.point).normalized()
            when (side) {
                // An IN drag pre-applies the node's constraint to the *other* handle, so that the repair in
                // ShapeNode.normalized — which always derives controlIn from controlOut — recomputes exactly
                // the offset dragged here rather than snapping it back to the old one.
                ShapeHandleSide.IN -> when (node.type) {
                    ShapeNodeType.CORNER -> node.copy(controlIn = offset)
                    ShapeNodeType.SMOOTH -> node.copy(controlIn = offset, controlOut = (-offset).withLength(node.controlOut.length))
                    ShapeNodeType.SYMMETRIC -> node.copy(controlIn = offset, controlOut = -offset)
                }
                // An OUT drag needs no such care: controlOut already leads every repair.
                ShapeHandleSide.OUT -> node.copy(controlOut = offset)
            }
        }

    /** Pulls a handle back into its node, which straightens the segment on that side. */
    fun retractHandle(path: ShapePath, ref: ShapeNodeRef, side: ShapeHandleSide): ShapePath =
        editNode(path, ref) { node ->
            when (side) {
                ShapeHandleSide.IN -> node.copy(controlIn = ShapePoint(), type = ShapeNodeType.CORNER)
                ShapeHandleSide.OUT -> node.copy(controlOut = ShapePoint(), type = ShapeNodeType.CORNER)
            }
        }

    // ── Node type ────────────────────────────────────────────────────────────────────────────────────

    /**
     * Sets the constraint a node's handles are held to, and immediately applies it.
     *
     * Tightening the constraint moves a handle — that is the point of the button — so the change is visible
     * rather than a mode the user has to discover by dragging afterwards.
     */
    fun setNodeType(path: ShapePath, ref: ShapeNodeRef, type: ShapeNodeType): ShapePath =
        editNode(path, ref) { it.copy(type = type) }

    /**
     * The toolbar's Smooth: continuous tangent, each side keeping its own length.
     *
     * On a node whose handles are both retracted this is a no-op by design — there is no direction to be
     * smooth about. [roundNode] is the action for that case, and it is what the toolbar offers on a corner.
     */
    fun smoothNode(path: ShapePath, ref: ShapeNodeRef): ShapePath =
        editNode(path, ref) { if (it.isSharp) it else it.copy(type = ShapeNodeType.SMOOTH) }

    /** The toolbar's Mirror: equal and opposite handles. */
    fun mirrorNode(path: ShapePath, ref: ShapeNodeRef): ShapePath =
        editNode(path, ref) { it.copy(type = ShapeNodeType.SYMMETRIC) }

    /**
     * The toolbar's Corner: a hard corner, handles retracted.
     *
     * Distinct from `setNodeType(CORNER)`, which only *releases* the constraint and leaves the curve looking
     * exactly as it did. A button that appears to do nothing is worse than one that does something the user
     * can undo, so the toolbar action is the one with a visible result.
     */
    fun sharpenNode(path: ShapePath, ref: ShapeNodeRef): ShapePath =
        editNode(path, ref) {
            it.copy(type = ShapeNodeType.CORNER, controlIn = ShapePoint(), controlOut = ShapePoint())
        }

    /**
     * Gives a sharp node handles along the chord between its neighbours, which is the closest thing to
     * "what the user probably meant" when they smooth a polygon corner.
     */
    fun roundNode(path: ShapePath, ref: ShapeNodeRef, strength: Float = 0.3f): ShapePath {
        val subpath = path.subpathById(ref.subpathId) ?: return path
        val index = subpath.indexOf(ref.nodeId)
        if (index < 0 || subpath.nodes.size < 3) return path
        val previous = neighbour(subpath, index, -1) ?: return path
        val next = neighbour(subpath, index, 1) ?: return path
        val chord = next.point - previous.point
        if (chord.length <= 1e-4f) return path
        val scale = (strength.finiteOr(0.3f)).coerceIn(0f, 1f) / 3f
        val out = chord * scale
        return editNode(path, ref) {
            it.copy(type = ShapeNodeType.SYMMETRIC, controlIn = -out, controlOut = out)
        }
    }

    // ── Topology ─────────────────────────────────────────────────────────────────────────────────────

    /**
     * Add Point: splits the segment at [t] so the outline is bit-for-bit unchanged.
     *
     * The neighbours' handles move too, and must: de Casteljau's construction produces a new control
     * polygon for both halves, and writing only the new node's handles would leave the two halves tracing
     * a different curve than the one that was cut. Inserting a point that visibly deforms the shape is the
     * defect this method exists to avoid.
     *
     * The shortened neighbour handles are why a symmetric neighbour is demoted to smooth. Symmetric means
     * the two handles are equal and opposite, and [ShapeNode.normalized] enforces exactly that on every
     * read — so a node left symmetric here would have its *untouched* side pulled in to match the side the
     * split shortened, bending the neighbouring segment that the user did not cut. Smooth keeps the
     * tangent, which is the part that has to hold, and lets the two lengths differ, which after a split
     * they genuinely do.
     */
    fun insertNode(path: ShapePath, subpathId: String, segmentIndex: Int, t: Float): ShapePath {
        val subpath = path.subpathById(subpathId) ?: return path
        val segments = subpath.segments()
        if (segmentIndex !in segments.indices) return path
        if (subpath.nodes.size >= ShapeSubpath.MAX_NODES) return path

        val (left, right) = segments[segmentIndex].splitAt(t)
        val startIndex = segmentIndex
        val endIndex = (segmentIndex + 1) % subpath.nodes.size
        val startNode = subpath.nodes[startIndex]
        val endNode = subpath.nodes[endIndex]

        val inserted = ShapeNode(
            point = left.end,
            // Both halves' inner controls are collinear with the split point, so either side determines the
            // tangent — but only the incoming side has the length the smooth repair will keep, and it
            // derives controlIn from controlOut. Storing left.control2 makes that repair an exact no-op.
            controlIn = left.control2 - left.end,
            controlOut = right.control1 - right.start,
            // Smooth by construction: the split point's two controls are collinear with it by definition,
            // so recording anything else would be a claim the geometry contradicts.
            type = ShapeNodeType.SMOOTH,
        )

        val nodes = subpath.nodes.toMutableList()
        nodes[startIndex] = startNode.copy(
            controlOut = left.control1 - left.start,
            type = relaxedForSplit(startNode.type),
        )
        nodes[endIndex] = endNode.copy(
            controlIn = right.control2 - right.end,
            type = relaxedForSplit(endNode.type),
        )
        nodes.add(startIndex + 1, inserted)
        return path.mapSubpaths { if (it.id == subpathId) it.copy(nodes = nodes) else it }.normalized()
    }

    /** A split shortens one handle, which a symmetric node cannot represent without moving the other. */
    private fun relaxedForSplit(type: ShapeNodeType): ShapeNodeType =
        if (type == ShapeNodeType.SYMMETRIC) ShapeNodeType.SMOOTH else type

    /** Add Point driven by a finger: splits whichever segment is nearest, or returns the path untouched. */
    fun insertNodeNear(path: ShapePath, point: ShapePoint, radius: Float = DEFAULT_HIT_RADIUS): ShapePath {
        val hit = nearestSegment(path, point, radius) ?: return path
        return insertNode(path, hit.subpathId, hit.segmentIndex, hit.t)
    }

    /**
     * Deletes a node.
     *
     * The neighbours reconnect directly, which does change the curve between them — it has to, since the
     * point that shaped it is gone. No handle refitting is attempted: a fit would guess at an outline the
     * user did not draw, and the honest result of deleting a point is the curve without that point.
     *
     * A subpath that would drop below two nodes is removed entirely rather than left as a stray point that
     * renders as nothing and still absorbs taps.
     */
    fun deleteNode(path: ShapePath, ref: ShapeNodeRef): ShapePath {
        val subpath = path.subpathById(ref.subpathId) ?: return path
        if (subpath.indexOf(ref.nodeId) < 0) return path
        val remaining = subpath.nodes.filterNot { it.id == ref.nodeId }
        if (remaining.size < 2) return deleteSubpath(path, ref.subpathId)
        return path.mapSubpaths { if (it.id == ref.subpathId) it.copy(nodes = remaining) else it }.normalized()
    }

    fun deleteNodes(path: ShapePath, refs: Collection<ShapeNodeRef>): ShapePath =
        refs.fold(path) { acc, ref -> deleteNode(acc, ref) }

    /** Draw mode: adds a node at the end of an open subpath, continuing the line the user is drawing. */
    fun appendNode(
        path: ShapePath,
        subpathId: String,
        point: ShapePoint,
        type: ShapeNodeType = ShapeNodeType.CORNER,
    ): ShapePath {
        val subpath = path.subpathById(subpathId) ?: return path
        if (subpath.nodes.size >= ShapeSubpath.MAX_NODES) return path
        val node = ShapeNode(point = point.normalized(), type = type)
        return path.mapSubpaths { if (it.id == subpathId) it.copy(nodes = it.nodes + node) else it }.normalized()
    }

    /** Starts a new subpath at [point]; the id of the new subpath is the last one in the returned path. */
    fun beginSubpath(path: ShapePath, point: ShapePoint): ShapePath {
        if (path.subpaths.size >= ShapePath.MAX_SUBPATHS) return path
        val subpath = ShapeSubpath(nodes = listOf(ShapeNode(point = point.normalized())), closed = false)
        return path.copy(subpaths = path.subpaths + subpath).normalized()
    }

    /** Closes a subpath, joining its last node back to its first. */
    fun closeSubpath(path: ShapePath, subpathId: String): ShapePath =
        path.mapSubpaths { if (it.id == subpathId && it.nodes.size >= 3) it.copy(closed = true) else it }.normalized()

    /**
     * Opens a closed subpath by cutting the segment that runs from [afterNodeId] back to the start.
     *
     * The node list is rotated so the cut lands at the ends rather than the middle, because an open
     * subpath's ends are its first and last nodes — leaving the cut in the middle would draw a closed
     * shape with a gap in the wrong place.
     */
    fun openSubpath(path: ShapePath, subpathId: String, afterNodeId: String): ShapePath =
        path.mapSubpaths { subpath ->
            if (subpath.id != subpathId || !subpath.closed) return@mapSubpaths subpath
            val index = subpath.indexOf(afterNodeId)
            if (index < 0) return@mapSubpaths subpath
            val rotated = subpath.nodes.drop(index + 1) + subpath.nodes.take(index + 1)
            subpath.copy(nodes = rotated, closed = false)
        }.normalized()

    fun deleteSubpath(path: ShapePath, subpathId: String): ShapePath =
        path.copy(subpaths = path.subpaths.filterNot { it.id == subpathId }).normalized()

    /** Reverses a subpath's direction, which swaps every node's handles along with the node order. */
    fun reverseSubpath(path: ShapePath, subpathId: String): ShapePath =
        path.mapSubpaths { subpath ->
            if (subpath.id != subpathId) return@mapSubpaths subpath
            subpath.copy(
                nodes = subpath.nodes.reversed().map { it.copy(controlIn = it.controlOut, controlOut = it.controlIn) },
            )
        }.normalized()

    /** Flips which side of the path is filled where subpaths overlap. */
    fun setFillRule(path: ShapePath, rule: ShapeFillRule): ShapePath = path.copy(fillRule = rule)

    // ── Hit testing ──────────────────────────────────────────────────────────────────────────────────

    /** The node nearest [point] within [radius], or null. Local canvas units throughout. */
    fun nearestNode(path: ShapePath, point: ShapePoint, radius: Float = DEFAULT_HIT_RADIUS): ShapeNodeRef? {
        var best: ShapeNodeRef? = null
        var bestDistance = radius
        for (subpath in path.subpaths) {
            for (node in subpath.nodes) {
                val distance = node.point.distanceTo(point)
                if (distance <= bestDistance) {
                    bestDistance = distance
                    best = ShapeNodeRef(subpath.id, node.id)
                }
            }
        }
        return best
    }

    /**
     * The handle nearest [point] within [radius], or null.
     *
     * A retracted handle sits exactly on its node and is deliberately not hittable: it has no visible
     * grip, and letting it win would make the node itself impossible to grab on a sharp corner.
     */
    fun nearestHandle(path: ShapePath, point: ShapePoint, radius: Float = HANDLE_HIT_RADIUS): ShapeHandleRef? {
        var best: ShapeHandleRef? = null
        var bestDistance = radius
        for (subpath in path.subpaths) {
            for (node in subpath.nodes) {
                if (!node.controlIn.isZero) {
                    val distance = node.incomingHandle.distanceTo(point)
                    if (distance <= bestDistance) {
                        bestDistance = distance
                        best = ShapeHandleRef(ShapeNodeRef(subpath.id, node.id), ShapeHandleSide.IN)
                    }
                }
                if (!node.controlOut.isZero) {
                    val distance = node.outgoingHandle.distanceTo(point)
                    if (distance <= bestDistance) {
                        bestDistance = distance
                        best = ShapeHandleRef(ShapeNodeRef(subpath.id, node.id), ShapeHandleSide.OUT)
                    }
                }
            }
        }
        return best
    }

    /** The point on the outline nearest [point] within [radius], or null. */
    fun nearestSegment(path: ShapePath, point: ShapePoint, radius: Float = DEFAULT_HIT_RADIUS): ShapeSegmentHit? {
        var best: ShapeSegmentHit? = null
        var bestDistance = radius
        for (subpath in path.subpaths) {
            val segments = subpath.segments()
            for (index in segments.indices) {
                val (t, distance) = segments[index].nearest(point)
                if (distance <= bestDistance) {
                    bestDistance = distance
                    best = ShapeSegmentHit(subpath.id, index, t, segments[index].pointAt(t), distance)
                }
            }
        }
        return best
    }

    /**
     * What a tap at [point] should grab, resolved once so every caller agrees.
     *
     * Strict priority rather than nearest-wins: handle, then node, then outline. A handle is drawn on top
     * of everything and answers to a tighter radius, so reaching for one is unambiguous; a node sits on the
     * outline it belongs to, so letting the outline win a near tie would make a node on a curve hard to
     * grab. The order is what makes the same tap mean the same thing twice.
     */
    fun hitTest(path: ShapePath, point: ShapePoint, radius: Float = DEFAULT_HIT_RADIUS): ShapeHit {
        nearestHandle(path, point, handleRadiusFor(radius))?.let { return ShapeHit.Handle(it) }
        nearestNode(path, point, radius)?.let { return ShapeHit.Node(it) }
        nearestSegment(path, point, radius)?.let { return ShapeHit.Segment(it) }
        return ShapeHit.None
    }

    /**
     * A handle's share of a given touch radius.
     *
     * Tighter than a node's, in the same proportion at every radius — a fixed ceiling would mean that the
     * bigger the caller's finger target, the *smaller* a handle's share of it, so the one control that
     * needs precision would end up the hardest thing on the canvas to grab. At [DEFAULT_HIT_RADIUS] this is
     * exactly [HANDLE_HIT_RADIUS], so the priority the tests pin down is unchanged.
     */
    fun handleRadiusFor(radius: Float): Float = radius * (HANDLE_HIT_RADIUS / DEFAULT_HIT_RADIUS)

    /** Every node inside [rect], for a marquee selection. */
    fun nodesIn(path: ShapePath, rect: ShapeRect): List<ShapeNodeRef> =
        path.subpaths.flatMap { subpath ->
            subpath.nodes.filter { rect.contains(it.point) }.map { ShapeNodeRef(subpath.id, it.id) }
        }

    // ── Internals ────────────────────────────────────────────────────────────────────────────────────

    private fun editNode(path: ShapePath, ref: ShapeNodeRef, edit: (ShapeNode) -> ShapeNode): ShapePath =
        path.mapSubpaths { subpath ->
            if (subpath.id != ref.subpathId) return@mapSubpaths subpath
            subpath.copy(nodes = subpath.nodes.map { if (it.id == ref.nodeId) edit(it) else it })
        }.normalized()

    /** The node [step] places away, wrapping on a closed subpath and clamping to nothing on an open one. */
    private fun neighbour(subpath: ShapeSubpath, index: Int, step: Int): ShapeNode? {
        val target = index + step
        return when {
            target in subpath.nodes.indices -> subpath.nodes[target]
            !subpath.closed -> null
            else -> subpath.nodes[(target + subpath.nodes.size) % subpath.nodes.size]
        }
    }
}

/** What a tap landed on. */
sealed interface ShapeHit {
    data class Node(val ref: ShapeNodeRef) : ShapeHit
    data class Handle(val ref: ShapeHandleRef) : ShapeHit
    data class Segment(val hit: ShapeSegmentHit) : ShapeHit
    data object None : ShapeHit
}

/**
 * Snapping *inside* the shape canvas.
 *
 * This is not a second scene snap engine and does not overlap
 * [com.norfold.app.editor.document.SnapGuideEngine], which snaps object boxes to page geometry and stays
 * the only thing that does. The question here is a different one — where should a *node* land within a
 * 100-unit local canvas — and it has no page, no margins, no sibling objects and no boxes. Routing node
 * drags through the scene engine would mean inventing a fake page for every shape; the two would then have
 * to agree about what a page means, which is exactly the coupling that keeps one engine from being one.
 */
object ShapeNodeSnap {
    /** Capture distance in local canvas units. About a finger's worth of slop at typical editing zoom. */
    const val DEFAULT_THRESHOLD = 1.5f

    /** Spacing of the background grid, in local units. Ten divisions across the canvas. */
    const val GRID_SPACING = 10f

    data class Guide(val orientation: Orientation, val position: Float, val kind: Kind)

    enum class Orientation { VERTICAL, HORIZONTAL }

    enum class Kind { CANVAS_EDGE, CANVAS_CENTER, NODE, GRID }

    data class Snapped(val point: ShapePoint, val guides: List<Guide>)

    /**
     * Snaps [point] to the canvas frame, the grid, and the other nodes of [path].
     *
     * Each axis resolves independently, so a node can align vertically with a neighbour while landing on a
     * grid line horizontally — the same rule the scene engine uses, because it is the one that matches what
     * a two-dimensional drag actually feels like.
     */
    fun snap(
        point: ShapePoint,
        path: ShapePath,
        exclude: Collection<ShapeNodeRef> = emptyList(),
        grid: Boolean = true,
        threshold: Float = DEFAULT_THRESHOLD,
    ): Snapped {
        val excluded = exclude.groupBy({ it.subpathId }, { it.nodeId }).mapValues { it.value.toSet() }
        val vertical = ArrayList<Candidate>(16)
        val horizontal = ArrayList<Candidate>(16)

        vertical += Candidate(0f, Kind.CANVAS_EDGE)
        vertical += Candidate(ShapeCanvas.SIDE, Kind.CANVAS_EDGE)
        vertical += Candidate(ShapeCanvas.SIDE / 2f, Kind.CANVAS_CENTER)
        horizontal += Candidate(0f, Kind.CANVAS_EDGE)
        horizontal += Candidate(ShapeCanvas.SIDE, Kind.CANVAS_EDGE)
        horizontal += Candidate(ShapeCanvas.SIDE / 2f, Kind.CANVAS_CENTER)

        if (grid) {
            var value = 0f
            while (value <= ShapeCanvas.SIDE) {
                vertical += Candidate(value, Kind.GRID)
                horizontal += Candidate(value, Kind.GRID)
                value += GRID_SPACING
            }
        }

        for (subpath in path.subpaths) {
            val skip = excluded[subpath.id].orEmpty()
            for (node in subpath.nodes) {
                if (node.id in skip) continue
                vertical += Candidate(node.point.x, Kind.NODE)
                horizontal += Candidate(node.point.y, Kind.NODE)
            }
        }

        val x = nearest(point.x, vertical, threshold)
        val y = nearest(point.y, horizontal, threshold)
        return Snapped(
            point = ShapePoint(x?.position ?: point.x, y?.position ?: point.y),
            guides = listOfNotNull(
                x?.let { Guide(Orientation.VERTICAL, it.position, it.kind) },
                y?.let { Guide(Orientation.HORIZONTAL, it.position, it.kind) },
            ),
        )
    }

    /**
     * Constrains a drag to the horizontal, the vertical or the diagonal through [origin] — the shift-drag
     * behaviour, offered as its own function so a two-finger or long-press modifier can reuse it.
     */
    fun constrain(origin: ShapePoint, point: ShapePoint): ShapePoint {
        val dx = point.x - origin.x
        val dy = point.y - origin.y
        val ax = abs(dx)
        val ay = abs(dy)
        // How close to 45° the drag is, as 0 (axis-aligned) to 1 (exactly diagonal).
        val diagonal = min(ax, ay) / max(max(ax, ay), 1e-4f)
        return when {
            diagonal > 0.5f -> {
                val magnitude = (ax + ay) / 2f
                ShapePoint(origin.x + magnitude * signOf(dx), origin.y + magnitude * signOf(dy))
            }
            ax >= ay -> ShapePoint(point.x, origin.y)
            else -> ShapePoint(origin.x, point.y)
        }
    }

    /** ±1 with zero counted as positive, so a drag that has not started yet still has a defined diagonal. */
    private fun signOf(value: Float): Float = if (value < 0f) -1f else 1f

    private data class Candidate(val position: Float, val kind: Kind)

    private fun nearest(value: Float, candidates: List<Candidate>, threshold: Float): Candidate? {
        var best: Candidate? = null
        var bestDistance = threshold
        for (candidate in candidates) {
            val distance = abs(candidate.position - value)
            // Strictly nearer, so ties keep the earlier candidate — canvas frame ahead of grid ahead of
            // nodes, which is the order of how deliberate each one is as a thing to line up with.
            if (distance < bestDistance) {
                bestDistance = distance
                best = candidate
            }
        }
        return best
    }
}
