package com.norfold.app.editor.flow

/**
 * One laid-out row, as the list measured it. Coordinates are in the scroll container's space, in pixels.
 */
data class FlowRowBounds(val blockId: String, val top: Float, val bottom: Float) {
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
     * The drop for [pointerY] over [rows], with [sourceId] the block being dragged.
     *
     * Rows are treated as an ordered partition of the axis: above the first row is BEFORE it, below the last
     * is AFTER it, and within a row the midpoint decides. That makes every pointer position resolve to exactly
     * one boundary — there is no gap between rows where the placement is undefined.
     *
     * Returns null when there is nothing to place against; the caller cancels rather than inventing a target.
     */
    fun placementFor(rows: List<FlowRowBounds>, sourceId: String, pointerY: Float): FlowDropPlacement? {
        if (rows.isEmpty()) return null
        val sourceIndex = rows.indexOfFirst { it.blockId == sourceId }

        val first = rows.first()
        if (pointerY <= first.top) return placement(0, first.top, FlowDropEdge.BEFORE, first.blockId, sourceIndex)
        val last = rows.last()
        if (pointerY >= last.bottom) {
            return placement(rows.size, last.bottom, FlowDropEdge.AFTER, last.blockId, sourceIndex)
        }

        rows.forEachIndexed { index, row ->
            if (pointerY < row.bottom) {
                return if (pointerY < row.center) {
                    placement(index, row.top, FlowDropEdge.BEFORE, row.blockId, sourceIndex)
                } else {
                    placement(index + 1, row.bottom, FlowDropEdge.AFTER, row.blockId, sourceIndex)
                }
            }
        }
        return placement(rows.size, last.bottom, FlowDropEdge.AFTER, last.blockId, sourceIndex)
    }

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
