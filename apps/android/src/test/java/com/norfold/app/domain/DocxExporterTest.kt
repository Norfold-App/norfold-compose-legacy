package com.norfold.app.domain

import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream
import org.junit.Assert.assertTrue
import org.junit.Test

class DocxExporterTest {
    @Test
    fun exportsValidPackageWithEscapedEditableText() {
        val bytes = DocxExporter.export("R&D <plan>", MarkdownBlockCodec.import("# Hello\n\nA & B"))
        val entries = linkedMapOf<String, String>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                entries[entry.name] = zip.readBytes().toString(Charsets.UTF_8)
                entry = zip.nextEntry
            }
        }
        assertTrue("[Content_Types].xml" in entries)
        assertTrue("word/document.xml" in entries)
        assertTrue(entries.getValue("word/document.xml").contains("R&amp;D &lt;plan&gt;"))
        assertTrue(entries.getValue("word/document.xml").contains("A &amp; B"))
    }
}
