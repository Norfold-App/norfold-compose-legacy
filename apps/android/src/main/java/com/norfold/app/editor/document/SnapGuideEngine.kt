package com.norfold.app.editor.document

import com.norfold.app.domain.DocumentPageBlock
import com.norfold.app.domain.SceneGeometry
import kotlin.math.abs

/**
 * Snapping and alignment guides for scene manipulation (roadmap Phase 2 §12).
 *
 * The engine answers one question — *given the box the gesture currently proposes, where should it actually
 * land?* — from real page and object geometry, never from screen coordinates. Working in page points means
 * snapping behaves identically at any zoom level, which is the whole reason the same nudge does not
 * mysteriously stop snapping when the user zooms out.
 *
 * Guides are returned alongside the adjusted geometry so the host can draw exactly the lines that were
 * responsible for the snap, and only while a manipulation is in progress. A guide the user cannot act on is
 * clutter; a guide that appears without having moved anything is a lie.
 *
 * Pure-JVM and stateless.
 */
object SnapGuideEngine {

    /** How near, in page points, a candidate must be before it captures the edge. A benchmark value. */
    const val DEFAULT_THRESHOLD = 6f

    /** One alignment line the manipulation snapped to, for the host to draw. */
    data class Guide(val orientation: Orientation, val position: Float, val kind: Kind)

    enum class Orientation { VERTICAL, HORIZONTAL }

    enum class Kind { PAGE_EDGE, PAGE_CENTER, MARGIN, OBJECT_EDGE, OBJECT_CENTER, GRID }

    data class Snapped(val geometry: SceneGeometry, val guides: List<Guide>)

    /**
     * Snaps a proposed [geometry] on [page], ignoring [excludeIds] (the objects being manipulated — an
     * object must never snap to itself, and a multi-object drag must not snap to its own members).
     *
     * Both axes are resolved independently, each taking the single nearest candidate within [threshold], so
     * a box can snap horizontally to a margin while snapping vertically to a neighbour's centre.
     */
    fun snapMove(
        geometry: SceneGeometry,
        page: DocumentPageBlock,
        excludeIds: Set<String> = emptySet(),
        threshold: Float = DEFAULT_THRESHOLD,
    ): Snapped {
        val g = geometry.normalized()
        val vertical = verticalCandidates(page, excludeIds)
        val horizontal = horizontalCandidates(page, excludeIds)

        // Each of an edge/centre trio may capture; the nearest wins, and the offset is applied to the whole
        // box so a snap translates the object rather than deforming it.
        val dx = nearestOffset(listOf(g.x, g.centerX, g.right), vertical, threshold)
        val dy = nearestOffset(listOf(g.y, g.centerY, g.bottom), horizontal, threshold)

        val snapped = g.copy(x = g.x + (dx?.delta ?: 0f), y = g.y + (dy?.delta ?: 0f))
        val guides = listOfNotNull(
            dx?.let { Guide(Orientation.VERTICAL, it.target, it.kind) },
            dy?.let { Guide(Orientation.HORIZONTAL, it.target, it.kind) },
        )
        return Snapped(snapped, guides)
    }

    /**
     * Snaps the moving edge(s) of a resize. Only the edges the handle actually drags are candidates, so a
     * bottom-right resize never silently repositions the top-left corner the user is anchoring against.
     */
    fun snapResize(
        geometry: SceneGeometry,
        page: DocumentPageBlock,
        movingRight: Boolean,
        movingBottom: Boolean,
        excludeIds: Set<String> = emptySet(),
        threshold: Float = DEFAULT_THRESHOLD,
    ): Snapped {
        val g = geometry.normalized()
        val vertical = verticalCandidates(page, excludeIds)
        val horizontal = horizontalCandidates(page, excludeIds)
        val guides = mutableListOf<Guide>()
        var result = g

        val xEdge = if (movingRight) g.right else g.x
        nearestOffset(listOf(xEdge), vertical, threshold)?.let { snap ->
            result = if (movingRight) {
                result.copy(width = (result.width + snap.delta).coerceAtLeast(SceneGeometry.MIN_SIZE))
            } else {
                result.copy(x = result.x + snap.delta, width = (result.width - snap.delta).coerceAtLeast(SceneGeometry.MIN_SIZE))
            }
            guides += Guide(Orientation.VERTICAL, snap.target, snap.kind)
        }

        val yEdge = if (movingBottom) g.bottom else g.y
        nearestOffset(listOf(yEdge), horizontal, threshold)?.let { snap ->
            result = if (movingBottom) {
                result.copy(height = (result.height + snap.delta).coerceAtLeast(SceneGeometry.MIN_SIZE))
            } else {
                result.copy(y = result.y + snap.delta, height = (result.height - snap.delta).coerceAtLeast(SceneGeometry.MIN_SIZE))
            }
            guides += Guide(Orientation.HORIZONTAL, snap.target, snap.kind)
        }

        return Snapped(result.normalized(), guides)
    }

    private data class Candidate(val position: Float, val kind: Kind)

    private data class Snap(val delta: Float, val target: Float, val kind: Kind)

    private fun verticalCandidates(page: DocumentPageBlock, excludeIds: Set<String>): List<Candidate> {
        val geo = page.geometry
        val result = mutableListOf(
            Candidate(0f, Kind.PAGE_EDGE),
            Candidate(geo.width, Kind.PAGE_EDGE),
            Candidate(geo.width / 2f, Kind.PAGE_CENTER),
            Candidate(geo.margins.left, Kind.MARGIN),
            Candidate(geo.width - geo.margins.right, Kind.MARGIN),
        )
        for (index in 0 until geo.columns.count) {
            result += Candidate(geo.columnLeft(index), Kind.MARGIN)
            result += Candidate(geo.columnLeft(index) + geo.columnWidth, Kind.MARGIN)
        }
        if (geo.grid.snapToGrid && geo.grid.spacing > 0f) {
            var x = 0f
            while (x <= geo.width) {
                result += Candidate(x, Kind.GRID)
                x += geo.grid.spacing
            }
        }
        geo.grid.verticalGuides.forEach { result += Candidate(it, Kind.GRID) }
        page.objects.asSequence().filter { it.id !in excludeIds && it.visible }.forEach { obj ->
            result += Candidate(obj.geometry.x, Kind.OBJECT_EDGE)
            result += Candidate(obj.geometry.right, Kind.OBJECT_EDGE)
            result += Candidate(obj.geometry.centerX, Kind.OBJECT_CENTER)
        }
        return result
    }

    private fun horizontalCandidates(page: DocumentPageBlock, excludeIds: Set<String>): List<Candidate> {
        val geo = page.geometry
        val result = mutableListOf(
            Candidate(0f, Kind.PAGE_EDGE),
            Candidate(geo.height, Kind.PAGE_EDGE),
            Candidate(geo.height / 2f, Kind.PAGE_CENTER),
            Candidate(geo.contentTop, Kind.MARGIN),
            Candidate(geo.contentTop + geo.contentHeight, Kind.MARGIN),
        )
        if (geo.grid.snapToGrid && geo.grid.spacing > 0f) {
            var y = 0f
            while (y <= geo.height) {
                result += Candidate(y, Kind.GRID)
                y += geo.grid.spacing
            }
        }
        geo.grid.horizontalGuides.forEach { result += Candidate(it, Kind.GRID) }
        page.objects.asSequence().filter { it.id !in excludeIds && it.visible }.forEach { obj ->
            result += Candidate(obj.geometry.y, Kind.OBJECT_EDGE)
            result += Candidate(obj.geometry.bottom, Kind.OBJECT_EDGE)
            result += Candidate(obj.geometry.centerY, Kind.OBJECT_CENTER)
        }
        return result
    }

    /**
     * The nearest snap across every (edge, candidate) pair within [threshold], or null when nothing is close
     * enough. Ties resolve to the first candidate, which orders page geometry ahead of other objects — a
     * margin is a more meaningful thing to align to than a neighbour that merely happens to be there.
     */
    private fun nearestOffset(
        edges: List<Float>,
        candidates: List<Candidate>,
        threshold: Float,
    ): Snap? {
        var best: Snap? = null
        var bestDistance = threshold
        for (edge in edges) {
            for (candidate in candidates) {
                val distance = abs(candidate.position - edge)
                if (distance < bestDistance) {
                    bestDistance = distance
                    best = Snap(candidate.position - edge, candidate.position, candidate.kind)
                }
            }
        }
        return best
    }
}
