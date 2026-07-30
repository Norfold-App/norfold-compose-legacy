package com.norfold.app.editor.flow

/**
 * One laid-out row, as the list measured it. Coordinates are in the scroll container's space, in pixels.
 *
 * [documentIndex] is the row's position in the **whole document**, not its position among the measured rows.
 * A virtualized list only measures what is on screen, so a window showing document blocks 80..90 hands over
 * eleven rows whose list positions are 0..10; resolving an insertion index against those positions would
 * commit a drop near block 85 at document index 5. Carrying the absolute index is what keeps the geometry
 * correct at any scroll offset.
 */
data class FlowRowBounds(
    val blockId: String,
    val documentIndex: Int,
    val top: Float,
    val bottom: Float,
) {
    val center: Float get() = (top + bottom) / 2f
}

/** Which side of a row a drop lands on. */
enum class FlowDropEdge { BEFORE, AFTER }

/**
 * Where a drag would land, resolved from measured geometry.
 *
 * [index] is the insertion index **in the document as it stands before the dragged block is removed**, which
 * is what [com.norfold.app.domain.DropTarget.Into] resolves against. [markerY] is where the insertion line is
 * drawn, so what the user sees and what the command does are computed from the same number rather than two
 * approximations that can disagree.
 */
data class FlowDropPlacement(
    val index: Int,
    val markerY: Float,
    val edge: FlowDropEdge,
    val referenceId: String,
    val isNoOp: Boolean,
)

/**
 * Turns a pointer position over measured rows into a deterministic drop (correction items 3 and 4).
 *
 * The defect this replaces was a fixed 48dp row pitch: the gesture divided the drag distance by a guessed
 * constant, so a drag across a heading, a table and a code block landed somewhere unrelated to what was under
 * the finger. Nothing here guesses — placement comes from the bounds the list actually laid out, and the same
 * placement drives the insertion marker, so the preview cannot lie about the result.
 *
 * Pure JVM, so the rule is unit-testable without a device.
 */
object FlowDragGeometry {

    /**
     * The drop for [pointerY] over [rows], with [sourceDocumentIndex] the dragged block's position in the
     * document (-1 if it is not in the document, in which case no placement is a no-op).
     *
     * Rows are treated as an ordered partition of the axis: above the first measured row is BEFORE it, below
     * the last is AFTER it, and within a row the midpoint decides. That makes every pointer position resolve to
     * exactly one boundary — there is no gap between rows where the placement is undefined.
     *
     * Every returned index is a **document** index, taken from the row it resolved against, so a drop over the
     * middle of a scrolled window commits where the marker is drawn rather than at the same ordinal within the
     * measured slice. [rows] is sorted here rather than trusted: measurement callbacks arrive in layout order,
     * which is not document order after an insertion.
     *
     * Returns null when there is nothing to place against; the caller cancels rather than inventing a target.
     */
    fun placementFor(
        rows: List<FlowRowBounds>,
        sourceDocumentIndex: Int,
        pointerY: Float,
    ): FlowDropPlacement? {
        if (rows.isEmpty()) return null
        val ordered = rows.sortedBy { it.documentIndex }

        val first = ordered.first()
        if (pointerY <= first.top) {
            return placement(first.documentIndex, first.top, FlowDropEdge.BEFORE, first.blockId, sourceDocumentIndex)
        }
        val last = ordered.last()
        if (pointerY >= last.bottom) {
            return placement(
                last.documentIndex + 1,
                last.bottom,
                FlowDropEdge.AFTER,
                last.blockId,
                sourceDocumentIndex,
            )
        }

        ordered.forEach { row ->
            if (pointerY < row.bottom) {
                return if (pointerY < row.center) {
                    placement(row.documentIndex, row.top, FlowDropEdge.BEFORE, row.blockId, sourceDocumentIndex)
                } else {
                    placement(
                        row.documentIndex + 1,
                        row.bottom,
                        FlowDropEdge.AFTER,
                        row.blockId,
                        sourceDocumentIndex,
                    )
                }
            }
        }
        return placement(
            last.documentIndex + 1,
            last.bottom,
            FlowDropEdge.AFTER,
            last.blockId,
            sourceDocumentIndex,
        )
    }

    /**
     * How far past a viewport edge the pointer is, as the scroll amount to apply this frame (positive scrolls
     * towards the end of the list). Zero in the middle of the viewport.
     *
     * Without this, a block can only be moved as far as the window it started in — a reorder across a long note
     * is impossible, which is the whole reason drag exists. The rate ramps with depth into the edge zone so a
     * pointer resting just inside the zone creeps and one held at the very edge moves quickly, and it is
     * expressed as pixels-per-frame so the caller needs no clock.
     */
    fun edgeScroll(
        pointerY: Float,
        viewportHeight: Float,
        edgeZone: Float = DEFAULT_EDGE_ZONE_PX,
        maxPerFrame: Float = DEFAULT_MAX_SCROLL_PER_FRAME_PX,
    ): Float {
        if (viewportHeight <= 0f || edgeZone <= 0f) return 0f
        // A zone taller than half the viewport would make the two edges overlap, so every position would be
        // "in an edge" and the list would never hold still.
        val zone = edgeZone.coerceAtMost(viewportHeight / 2f)
        if (pointerY < zone) {
            val depth = ((zone - pointerY) / zone).coerceIn(0f, 1f)
            return -depth * maxPerFrame
        }
        val bottomEdge = viewportHeight - zone
        if (pointerY > bottomEdge) {
            val depth = ((pointerY - bottomEdge) / zone).coerceIn(0f, 1f)
            return depth * maxPerFrame
        }
        return 0f
    }

    /** Roughly a finger's width: close enough to the edge to read as "I want to go further". */
    const val DEFAULT_EDGE_ZONE_PX = 96f

    /** Fast enough to cross a long note, slow enough that the marker stays readable while it moves. */
    const val DEFAULT_MAX_SCROLL_PER_FRAME_PX = 18f

    /**
     * A boundary immediately before or immediately after the dragged block is the block's own position, so
     * committing it would be a no-op transaction in history and a flicker on screen. Marked rather than
     * dropped, because the marker should still be drawn there — the user is being told "this changes nothing",
     * not left without feedback.
     */
    private fun placement(
        index: Int,
        markerY: Float,
        edge: FlowDropEdge,
        referenceId: String,
        sourceIndex: Int,
    ): FlowDropPlacement = FlowDropPlacement(
        index = index,
        markerY = markerY,
        edge = edge,
        referenceId = referenceId,
        isNoOp = sourceIndex >= 0 && (index == sourceIndex || index == sourceIndex + 1),
    )
}
