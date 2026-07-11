package com.norfold.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PageDocumentTest {
    @Test
    fun structuredMarkdownRoundTripsWithoutLosingBlockMeaning() {
        val source = """
            # Roadmap

            A paragraph with
            two lines.

            - Bullet

            7. Numbered

            - [x] Finished

            > Quoted

            ```kotlin
            val ready = true
            ```

            | Item | State |
            | --- | --- |
            | Editor | Ready |

            ![Cover](content://cover)

            [[Linked note]]
        """.trimIndent()

        val document = PageDocument.parse(source)
        val reparsed = PageDocument.parse(document.toMarkdown())

        assertEquals(document.blocks.map { it.type }, reparsed.blocks.map { it.type })
        assertEquals("kotlin", document.blocks.first { it.type == PageBlockType.Code }.language)
        assertTrue(document.blocks.first { it.type == PageBlockType.Checklist }.checked)
        assertEquals("content://cover", document.blocks.first { it.type == PageBlockType.Image }.target)
    }

    @Test
    fun unsupportedHtmlRemainsRawMarkdown() {
        val source = "<details>custom</details>"
        assertEquals(source, PageDocument.parse(source).toMarkdown())
        assertEquals(PageBlockType.Raw, PageDocument.parse(source).blocks.single().type)
    }
}
