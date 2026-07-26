package com.norfold.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SharedDocumentMergeTest {
    @Test
    fun `different dirty and remote blocks merge without loss`() {
        val remote = BlockDocument(listOf(ParagraphBlock(id = "a", content = listOf(InlineText("remote A"))), ParagraphBlock(id = "b", content = listOf(InlineText("remote B")))))
        val local = BlockDocument(listOf(ParagraphBlock(id = "a", content = listOf(InlineText("local A"))), ParagraphBlock(id = "b", content = listOf(InlineText("old B")))))

        val merged = mergeSharedDocumentBlocks(remote, local, setOf("a"))

        assertEquals("local A", merged.findById("a")?.plainText())
        assertEquals("remote B", merged.findById("b")?.plainText())
    }

    @Test
    fun `dirty local deletion removes remote block`() {
        val remote = BlockDocument(listOf(ParagraphBlock(id = "a"), ParagraphBlock(id = "b")))
        val local = BlockDocument(listOf(ParagraphBlock(id = "a")))

        val merged = mergeSharedDocumentBlocks(remote, local, setOf("b"))

        assertNull(merged.findById("b"))
    }

    @Test
    fun `clean local snapshot accepts remote additions and removals`() {
        val remote = BlockDocument(listOf(ParagraphBlock(id = "remote")))
        val local = BlockDocument(listOf(ParagraphBlock(id = "local")))

        assertEquals(listOf("remote"), mergeSharedDocumentBlocks(remote, local, emptySet()).blocks.map { it.id })
    }
}
