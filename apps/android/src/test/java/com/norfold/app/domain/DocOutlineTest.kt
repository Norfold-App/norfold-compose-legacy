package com.norfold.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers [DocOutline.extract]: top-level and nested headings (quotes, callouts, container columns)
 * appear in document order with labels, clamped levels, and top-level ancestor scroll targets.
 */
class DocOutlineTest {

    private fun heading(id: String, level: Int, text: String) =
        HeadingBlock(id = id, level = level, content = listOf(InlineText(text)))

    @Test
    fun `top level headings map to their own index`() {
        val document = BlockDocument(
            blocks = listOf(
                heading("h1", 1, "Intro"),
                ParagraphBlock(id = "p1", content = listOf(InlineText("body"))),
                heading("h2", 2, "Details"),
            ),
        )
        val outline = DocOutline.extract(document)
        assertEquals(listOf("Intro", "Details"), outline.map { it.label })
        assertEquals(listOf(0, 2), outline.map { it.topLevelIndex })
        assertEquals(listOf("h1", "h2"), outline.map { it.topLevelId })
        assertEquals(listOf(1, 2), outline.map { it.level })
    }

    @Test
    fun `nested headings resolve to their top level ancestor`() {
        val nested = heading("h-nested", 3, "Inside column")
        val container = ContainerBlock(
            id = "container",
            children = listOf(ParagraphBlock(id = "cp"), nested),
        )
        val quote = QuoteBlock(id = "quote", children = listOf(heading("h-quote", 2, "Quoted")))
        val callout = CalloutBlock(id = "callout", children = listOf(heading("h-callout", 4, "Callout note")))
        val document = BlockDocument(blocks = listOf(ParagraphBlock(id = "lead"), container, quote, callout))

        val outline = DocOutline.extract(document)
        assertEquals(listOf("h-nested", "h-quote", "h-callout"), outline.map { it.blockId })
        assertEquals(listOf("container", "quote", "callout"), outline.map { it.topLevelId })
        assertEquals(listOf(1, 2, 3), outline.map { it.topLevelIndex })
    }

    @Test
    fun `deeply nested heading still targets the outermost ancestor`() {
        val inner = ContainerBlock(id = "inner", children = listOf(heading("h-deep", 2, "Deep")))
        val outer = ContainerBlock(id = "outer", children = listOf(inner))
        val document = BlockDocument(blocks = listOf(outer))

        val outline = DocOutline.extract(document)
        assertEquals(1, outline.size)
        assertEquals("outer", outline.single().topLevelId)
        assertEquals(0, outline.single().topLevelIndex)
    }

    @Test
    fun `blank headings get a placeholder label and levels are clamped`() {
        val document = BlockDocument(
            blocks = listOf(
                heading("h-blank", 1, "   "),
                heading("h-wild", 9, "Overflow"),
                heading("h-low", 0, "Underflow"),
            ),
        )
        val outline = DocOutline.extract(document)
        assertEquals("Untitled heading", outline[0].label)
        assertEquals(6, outline[1].level)
        assertEquals(1, outline[2].level)
    }

    @Test
    fun `documents without headings produce an empty outline`() {
        val document = BlockDocument(
            blocks = listOf(ParagraphBlock(id = "p"), DividerBlock(id = "d"), CodeBlock(id = "c")),
        )
        assertTrue(DocOutline.extract(document).isEmpty())
    }
}
