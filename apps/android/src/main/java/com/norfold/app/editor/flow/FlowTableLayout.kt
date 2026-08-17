package com.norfold.app.editor.flow

/**
 * Resolved column widths for one table, in dp.
 *
 * [scrolls] is derived rather than asked for: a table scrolls exactly when the resolved grid is wider than the
 * space it was given, and returning that alongside the widths is what stops a caller from deciding it
 * separately and disagreeing with the arithmetic.
 */
data class FlowTableWidths(
    val columnsDp: List<Float>,
    val availableDp: Float,
) {
    val totalDp: Float get() = columnsDp.sum()

    val scrolls: Boolean get() = availableDp > 0f && totalDp > availableDp + SCROLL_EPSILON_DP

    private companion object {
        /** Sub-pixel slack. A grid that overruns its box by a rounding error is not a scrollable table. */
        const val SCROLL_EPSILON_DP = 0.5f
    }
}

/**
 * How a table's authored column widths become drawn column widths.
 *
 * Every cell used to be `weight(1f)` in a `fillMaxWidth()` row, which divides the phone's ~360 dp of measure by
 * the column count — a six-column table gave each column 50 dp, under four characters — and
 * [com.norfold.app.domain.TableBlock.columnWidthsDp] was never read at all, so a width the calculation bridge had
 * already derived from the workbook was discarded on the way to the screen.
 *
 * The rule here replaces both faults with one formula, and it is a *policy*: which of two bad outcomes to accept
 * when a table cannot fit. The outcomes are a grid of truncated stubs and a grid that must be scrolled, and the
 * choice between them depends on numbers — a floor width, a ratio, an available width — that a screenshot cannot
 * settle. So it lives in pure Kotlin and is asserted on the desktop JVM, the same reason
 * [FlowDragGeometry] does.
 */
object FlowTableLayout {

    /**
     * Widths for [columnCount] columns of a table drawn into [availableDp].
     *
     * Pass `availableDp <= 0` for "unknown" — an unbounded measurement, a table nested inside another
     * horizontally scrolling container — and the authored widths are returned untouched. Guessing a page width
     * there is how a table ends up scaled against half a million dp of imaginary paper.
     *
     * [storedWidthsDp] may be shorter than [columnCount], or empty: an empty list means "never resized", which
     * [FlowTableOps.insertColumn] deliberately keeps empty, so a missing entry falls back to
     * [FlowTableOps.DEFAULT_COLUMN_WIDTH_DP] and a table that has never been resized draws identically whether
     * its widths list is populated or not.
     */
    fun resolve(
        storedWidthsDp: List<Float>,
        columnCount: Int,
        minimumDp: Float,
        availableDp: Float,
    ): FlowTableWidths {
        if (columnCount <= 0) return FlowTableWidths(emptyList(), availableDp)
        // The floor is applied before fitting, not after, so it is a property of the authored width rather than
        // an afterthought the scale can undo.
        val minimum = if (minimumDp.isFinite() && minimumDp > 0f) minimumDp else FALLBACK_MINIMUM_DP
        val authored = List(columnCount) { column ->
            val stored = storedWidthsDp.getOrNull(column)
                ?.takeIf { it.isFinite() && it > 0f }
                ?: FlowTableOps.DEFAULT_COLUMN_WIDTH_DP
            maxOf(stored, minimum)
        }
        // Hoisted rather than called inside the map: it is one factor for the whole table by definition, and
        // computing it per column invites a later edit that makes it differ per column.
        val scale = scaleFor(authored, minimum, availableDp)
        return FlowTableWidths(authored.map { it * scale }, availableDp)
    }

    /**
     * The single factor every column is multiplied by.
     *
     * One factor rather than per-column fitting, because the authored *ratio* is the information the user put in:
     * a `weight(1f)` row returns a 300/120 pair as 210/210, and the column they widened stops being the wide one.
     *
     * Two bounds meet here. `available / natural` is the factor that makes the grid fit exactly — above 1 for a
     * narrow table, below 1 for a wide one. `minimum / narrowest` is as far down as the table may be squeezed
     * before its thinnest column drops under the readable floor. Taking the larger means a table that *can* fit
     * does, a table that cannot is still squeezed as far as it legitimately can be — so the reader scrolls past
     * the overflow rather than past the whole grid — and no column ever goes under the floor either way.
     *
     * The honest cost of one shared factor: a table whose narrowest column is *already* at the floor cannot be
     * squeezed at all, so a 300/72 pair scrolls rather than fitting as 228/72. Shrinking the wide column alone
     * would fit, but it would also mean the ratio the user authored is only honoured while it happens to be
     * convenient — and a column resize that survives on a tablet and silently doesn't on a phone is worse than
     * a scrollbar.
     */
    private fun scaleFor(authored: List<Float>, minimum: Float, availableDp: Float): Float {
        if (availableDp <= 0f || !availableDp.isFinite()) return 1f
        val natural = authored.sum()
        if (natural <= 0f) return 1f
        val narrowest = authored.minOrNull() ?: return 1f
        // Always <= 1, since every authored width has already been floored at the minimum. That is what makes
        // the maxOf below a choice between "fit" and "squeeze as far as allowed" rather than an unbounded shrink.
        val squeezeLimit = if (narrowest > 0f) minimum / narrowest else 1f
        return maxOf(availableDp / natural, squeezeLimit)
    }

    /**
     * Used only when a caller hands over a nonsensical floor.
     *
     * The real floor is computed from the reading scale by `FlowContent.columnMinimum`, which is a density
     * conversion and so cannot be done here; this value is the same one that computation clamps to at 1.0x, so a
     * garbage minimum degrades to the ordinary one instead of collapsing the grid.
     */
    const val FALLBACK_MINIMUM_DP = 72f
}
