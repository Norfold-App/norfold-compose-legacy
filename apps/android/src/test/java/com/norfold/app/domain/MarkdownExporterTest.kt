package com.norfold.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownExporterTest {
    @Test
    fun markdownExportKeepsSourceExactly() {
        val source = "# Title\n\n- [x] Done\n\n`code`"
        val document = NoteDocument("Title", "Inbox", "#tag", source)

        assertEquals(source, MarkdownExporter.markdown(document))
    }

    @Test
    fun htmlExportRendersMarkdownStructure() {
        val document = NoteDocument(
            title = "Roadmap",
            folder = "Projects",
            tag = "#planning",
            markdown = "# Heading\n\n- [x] Done\n> Quote\n```kotlin\nval x = 1\n```",
        )
        val html = MarkdownExporter.html(document)

        assertTrue(html.contains("<h1>Heading</h1>"))
        assertTrue(html.contains("<li>☑ Done</li>"))
        assertTrue(html.contains("<blockquote>Quote</blockquote>"))
        assertTrue(html.contains("<pre><code>val x = 1</code></pre>"))
    }
}
