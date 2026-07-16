package com.norfold.app.domain

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Minimal, dependency-free Office Open XML writer for editable semantic document export. */
object DocxExporter {
    fun export(title: String, document: BlockDocument): ByteArray {
        val markdown = MarkdownBlockCodec.export(document)
        val body = buildString {
            paragraph(title.ifBlank { "Untitled document" }, style = "Title")
            MarkdownExporter.blocks(markdown).forEach { block ->
                when (block) {
                    is MarkdownBlock.Heading -> paragraph(block.text, "Heading${block.level.coerceIn(1, 3)}")
                    is MarkdownBlock.Paragraph -> paragraph(block.text)
                    is MarkdownBlock.Quote -> paragraph(block.text, "Quote")
                    is MarkdownBlock.Code -> paragraph(block.code, "Code")
                    is MarkdownBlock.ListItem -> paragraph(
                        when (block.checked) {
                            true -> "☑ ${block.text}"
                            false -> "☐ ${block.text}"
                            null -> "• ${block.text}"
                        },
                    )
                    MarkdownBlock.Rule -> paragraph("────────────────────────")
                }
            }
        }
        val documentXml = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body>$body<w:sectPr><w:pgSz w:w="11906" w:h="16838"/><w:pgMar w:top="1021" w:right="1021" w:bottom="1021" w:left="1021"/></w:sectPr></w:body></w:document>""".trimIndent()
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            zip.put("[Content_Types].xml", CONTENT_TYPES)
            zip.put("_rels/.rels", ROOT_RELS)
            zip.put("word/document.xml", documentXml)
            zip.put("word/styles.xml", STYLES)
        }
        return output.toByteArray()
    }

    private fun StringBuilder.paragraph(text: String, style: String? = null) {
        append("<w:p>")
        if (style != null) append("<w:pPr><w:pStyle w:val=\"").append(style).append("\"/></w:pPr>")
        append("<w:r><w:t xml:space=\"preserve\">").append(xml(text)).append("</w:t></w:r></w:p>")
    }

    private fun ZipOutputStream.put(path: String, content: String) {
        putNextEntry(ZipEntry(path))
        write(content.toByteArray(Charsets.UTF_8))
        closeEntry()
    }

    private fun xml(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")

    private const val CONTENT_TYPES = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/><Override PartName="/word/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml"/></Types>"""
    private const val ROOT_RELS = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/></Relationships>"""
    private const val STYLES = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><w:styles xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:style w:type="paragraph" w:default="1" w:styleId="Normal"><w:name w:val="Normal"/></w:style><w:style w:type="paragraph" w:styleId="Title"><w:name w:val="Title"/><w:rPr><w:b/><w:sz w:val="36"/></w:rPr></w:style><w:style w:type="paragraph" w:styleId="Heading1"><w:name w:val="heading 1"/><w:rPr><w:b/><w:sz w:val="32"/></w:rPr></w:style><w:style w:type="paragraph" w:styleId="Heading2"><w:name w:val="heading 2"/><w:rPr><w:b/><w:sz w:val="28"/></w:rPr></w:style><w:style w:type="paragraph" w:styleId="Heading3"><w:name w:val="heading 3"/><w:rPr><w:b/><w:sz w:val="24"/></w:rPr></w:style><w:style w:type="paragraph" w:styleId="Quote"><w:name w:val="Quote"/><w:pPr><w:ind w:left="360"/></w:pPr><w:rPr><w:i/></w:rPr></w:style><w:style w:type="paragraph" w:styleId="Code"><w:name w:val="Code"/><w:rPr><w:rFonts w:ascii="Consolas" w:hAnsi="Consolas"/></w:rPr></w:style></w:styles>"""
}
