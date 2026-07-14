package com.norfold.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the extended-syntax preprocessing in [MarkdownBlockCodec.import]: Obsidian comments,
 * wikilinks, definition lists, and tilde fences.
 */
class MarkdownExtendedSyntaxTest {

    @Test
    fun `inline comments are stripped on import`() {
        val document = MarkdownBlockCodec.import("Before %%hidden note%% after")
        val paragraph = document.blocks.single() as ParagraphBlock
        assertEquals("Before  after", paragraph.plainText())
    }

    @Test
    fun `multi line comments are stripped on import`() {
        val document = MarkdownBlockCodec.import("Visible\n\n%%\nfully hidden\nstill hidden\n%%\n\nAlso visible")
        assertEquals(listOf("Visible", "Also visible"), document.blocks.map { it.plainText().trim() })
    }

    @Test
    fun `comments inside code fences are preserved`() {
        val document = MarkdownBlockCodec.import("```css\na %% b %%\n```")
        val code = document.blocks.single() as CodeBlock
        assertTrue(code.code.contains("%% b %%"))
    }

    @Test
    fun `wikilinks become internal links`() {
        val document = MarkdownBlockCodec.import("See [[Study Plan]] for details")
        val paragraph = document.blocks.single() as ParagraphBlock
        val link = paragraph.content.filterIsInstance<LinkInline>().single()
        assertEquals("norfold://page/Study%20Plan", link.url)
        assertEquals("Study Plan", link.children.plainText())
    }

    @Test
    fun `aliased wikilinks keep the alias as label`() {
        val document = MarkdownBlockCodec.import("Read [[Physics Notes|the notes]] first")
        val paragraph = document.blocks.single() as ParagraphBlock
        val link = paragraph.content.filterIsInstance<LinkInline>().single()
        assertEquals("norfold://page/Physics%20Notes", link.url)
        assertEquals("the notes", link.children.plainText())
    }

    @Test
    fun `definition lists map to bold term plus bullet list`() {
        val document = MarkdownBlockCodec.import("Apple\n: A fruit\n: A company")
        assertEquals(2, document.blocks.size)
        val term = document.blocks[0] as ParagraphBlock
        assertTrue(term.content.first() is BoldInline)
        assertEquals("Apple", term.plainText())
        val definitions = document.blocks[1] as BulletListBlock
        assertEquals(listOf("A fruit", "A company"), definitions.items.map { it.content.plainText() })
    }

    @Test
    fun `tilde fences import as code blocks`() {
        val document = MarkdownBlockCodec.import("~~~python\nprint(1)\n~~~")
        val code = document.blocks.single() as CodeBlock
        assertEquals("python", code.language)
        assertEquals("print(1)", code.code)
    }

    @Test
    fun `definition syntax inside fences is untouched`() {
        val document = MarkdownBlockCodec.import("```yaml\nkey\n: value\n```")
        val code = document.blocks.single() as CodeBlock
        assertTrue(code.code.contains(": value"))
        assertFalse(code.code.contains("**"))
    }
}
