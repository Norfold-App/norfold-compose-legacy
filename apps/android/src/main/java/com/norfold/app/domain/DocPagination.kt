package com.norfold.app.domain

/**
 * Greedy block→page packing for the Page-mode paginated renderer. The UI measures each top-level
 * block at the page's content width and hands the pixel heights here; this stays pure math so the
 * packing rules are unit-testable without Compose.
 *
 * Rules:
 * - Blocks are never split: a page break always falls between two top-level blocks.
 * - A block taller than the page still gets placed (alone at the top of its own page) — the
 *   renderer clips it to the page bounds rather than dropping content silently.
 * - [spacing] is the inter-block gap and only counts between blocks already on the page.
 */
object DocPagination {

    /** Partitions block indices into per-page ranges. Every index appears in exactly one range. */
    fun paginate(heights: List<Float>, pageHeight: Float, spacing: Float = 0f): List<IntRange> {
        if (heights.isEmpty()) return emptyList()
        val pages = mutableListOf<IntRange>()
        var start = 0
        var used = 0f
        heights.forEachIndexed { index, height ->
            if (index == start) {
                used = height
                return@forEachIndexed
            }
            if (used + spacing + height > pageHeight) {
                pages += start until index
                start = index
                used = height
            } else {
                used += spacing + height
            }
        }
        pages += start until heights.size
        return pages
    }
}
