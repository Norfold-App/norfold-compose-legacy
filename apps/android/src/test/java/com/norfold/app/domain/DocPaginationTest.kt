package com.norfold.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the Page-mode packing rules in [DocPagination]: breaks fall between blocks, spacing only
 * counts inside a page, and oversized blocks still land (alone) instead of vanishing.
 */
class DocPaginationTest {

    @Test
    fun `empty document yields no pages`() {
        assertEquals(emptyList<IntRange>(), DocPagination.paginate(emptyList(), pageHeight = 100f))
    }

    @Test
    fun `blocks that fit share a single page`() {
        assertEquals(listOf(0..2), DocPagination.paginate(listOf(30f, 30f, 30f), pageHeight = 100f))
    }

    @Test
    fun `page breaks between blocks when the next one would overflow`() {
        assertEquals(
            listOf(0..1, 2..3),
            DocPagination.paginate(listOf(50f, 50f, 50f, 20f), pageHeight = 100f),
        )
    }

    @Test
    fun `an exact fit stays on the page`() {
        assertEquals(listOf(0..1, 2..2), DocPagination.paginate(listOf(60f, 40f, 10f), pageHeight = 100f))
    }

    @Test
    fun `spacing counts between blocks on the same page`() {
        // 45 + 10 + 45 = 100 fits; the third block (45) would need 155 → next page.
        assertEquals(
            listOf(0..1, 2..2),
            DocPagination.paginate(listOf(45f, 45f, 45f), pageHeight = 100f, spacing = 10f),
        )
    }

    @Test
    fun `oversized block gets its own page and never disappears`() {
        assertEquals(
            listOf(0..0, 1..1, 2..2),
            DocPagination.paginate(listOf(20f, 500f, 20f), pageHeight = 100f),
        )
    }

    @Test
    fun `every block index lands on exactly one page`() {
        val heights = listOf(80f, 15f, 200f, 5f, 5f, 90f, 40f, 60f)
        val pages = DocPagination.paginate(heights, pageHeight = 100f, spacing = 4f)
        val covered = pages.flatMap { it.toList() }
        assertEquals(heights.indices.toList(), covered)
        assertTrue(pages.all { !it.isEmpty() })
    }
}
