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

    @Test
    fun printHtmlCarriesPageRulesAndDocumentStructure() {
        val html = MarkdownExporter.printHtml(
            title = "Field <notes>",
            markdown = "# Heading\n\nBody text\n> Quote",
        )

        // A4 page bounds + break hints are the print contract for the Page-mode export.
        assertTrue(html.contains("@page{size:A4;margin:18mm}"))
        assertTrue(html.contains("page-break-after:avoid"))
        assertTrue(html.contains("page-break-inside:avoid"))
        assertTrue(html.contains("<h1>Field &lt;notes&gt;</h1>"))
        assertTrue(html.contains("<h1>Heading</h1>"))
        assertTrue(html.contains("<p>Body text</p>"))
        assertTrue(html.contains("<blockquote>Quote</blockquote>"))
    }

    @Test
    fun boundedCanvasPrintUsesExactPageAndEscapesContent() {
        val document = MarkdownBlockCodec.import("# A & B")
        val block = document.blocks.single()
        val html = MarkdownExporter.canvasPrintHtml(
            "Plan <one>",
            document,
            mapOf(block.id to FreeformPlacement(x = 10f, y = 20f, width = 300f, height = 100f)),
            DocCanvasSpec.letter().copy(pageCount = 2),
        )
        assertTrue(html.contains("@page{size:612.0pt 792.0pt;margin:0}"))
        assertTrue(html.contains("Plan &lt;one&gt;"))
        assertTrue(html.contains("left:10.0pt;top:20.0pt"))
        assertEquals(2, "<section class=\"page\">".toRegex().findAll(html).count())
    }
}
