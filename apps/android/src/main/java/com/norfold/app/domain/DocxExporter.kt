package com.norfold.app.domain

import com.norfold.app.editor.relationship.CommentAnchor
import com.norfold.app.editor.relationship.DiscussionThread
import com.norfold.app.editor.relationship.Phase7ArtifactCodec
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

data class DocxExportPackage(val bytes: ByteArray, val warnings: List<String>, val packagedImages: Int, val packagedComments: Int)

/** Repository-owned OOXML writer. Existing [export] remains the stable legacy boundary. */
object DocxExporter {
    fun export(title: String, document: BlockDocument): ByteArray = exportDetailed(title, document).bytes

    fun exportDetailed(title: String, document: BlockDocument, assetResolver: (String) -> ByteArray? = { null }): DocxExportPackage {
        val warnings = mutableListOf<String>()
        val relationships = mutableListOf<Relationship>()
        val media = linkedMapOf<String, ByteArray>()
        val threads = document.relationshipDataBlock()?.payload?.let(Phase7ArtifactCodec::decode)?.threads.orEmpty()
        val threadsByBlock = threads.mapNotNull { thread -> thread.anchor.blockIdOrNull()?.let { it to thread } }.groupBy({ it.first }, { it.second })
        var nextRel = 10
        var commentIndex = 0
        val commentIds = linkedMapOf<String, Int>()

        fun relationship(type: String, target: String): String = "rId${nextRel++}".also { relationships += Relationship(it, type, target) }
        fun paragraph(content: List<InlineNode>, style: String? = null, numbering: Pair<Int, Int>? = null, blockId: String? = null): String = buildString {
            append("<w:p>")
            if (style != null || numbering != null) {
                append("<w:pPr>")
                style?.let { append("<w:pStyle w:val=\"").append(xml(it)).append("\"/>") }
                numbering?.let { (numId, level) -> append("<w:numPr><w:ilvl w:val=\"").append(level).append("\"/><w:numId w:val=\"").append(numId).append("\"/></w:numPr>") }
                append("</w:pPr>")
            }
            val comments = blockId?.let(threadsByBlock::get).orEmpty()
            comments.forEach { thread ->
                val id = commentIds.getOrPut(thread.id.value) { commentIndex++ }
                append("<w:commentRangeStart w:id=\"").append(id).append("\"/>")
            }
            content.forEach { append(inline(it, ::relationship)) }
            comments.asReversed().forEach { thread ->
                val id = commentIds.getValue(thread.id.value)
                append("<w:commentRangeEnd w:id=\"").append(id).append("\"/><w:r><w:commentReference w:id=\"").append(id).append("\"/></w:r>")
            }
            append("</w:p>")
        }
        fun image(block: ImageBlock): String {
            val bytes = assetResolver(block.source)
            if (bytes == null) {
                warnings += "Image ${block.id} could not be resolved and was exported as caption text."
                return paragraph(listOf(InlineText(block.caption.ifBlank { "Image" })), blockId = block.id)
            }
            val extension = sniffImageExtension(bytes)
            val name = "image${media.size + 1}.$extension"
            media[name] = bytes
            val rel = relationship("http://schemas.openxmlformats.org/officeDocument/2006/relationships/image", "media/$name")
            return """<w:p><w:r><w:drawing><wp:inline xmlns:wp="http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing"><wp:extent cx="5486400" cy="3200400"/><wp:docPr id="${media.size}" name="${xml(block.caption.ifBlank { "Image" })}"/><a:graphic xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main"><a:graphicData uri="http://schemas.openxmlformats.org/drawingml/2006/picture"><pic:pic xmlns:pic="http://schemas.openxmlformats.org/drawingml/2006/picture"><pic:blipFill><a:blip r:embed="$rel" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"/><a:stretch><a:fillRect/></a:stretch></pic:blipFill><pic:spPr><a:xfrm><a:off x="0" y="0"/><a:ext cx="5486400" cy="3200400"/></a:xfrm><a:prstGeom prst="rect"><a:avLst/></a:prstGeom></pic:spPr></pic:pic></a:graphicData></a:graphic></wp:inline></w:drawing></w:r></w:p>"""
        }
        fun table(block: TableBlock): String = buildString {
            append("<w:tbl><w:tblPr><w:tblStyle w:val=\"TableGrid\"/></w:tblPr>")
            (listOf(block.headers) + block.rows).filter { it.isNotEmpty() }.forEach { row ->
                append("<w:tr>")
                row.forEach { cell -> append("<w:tc>").append(paragraph(cell.content)).append("</w:tc>") }
                append("</w:tr>")
            }
            append("</w:tbl>")
        }
        fun blocks(source: List<DocumentBlock>): String = buildString {
            source.forEach { block -> when (block) {
                is ParagraphBlock -> append(paragraph(block.content, blockId = block.id))
                is HeadingBlock -> append(paragraph(block.content, "Heading${block.level.coerceIn(1, 6)}", blockId = block.id))
                is BulletListBlock -> block.items.forEach { append(paragraph(it.content, numbering = 1 to 0, blockId = block.id)) }
                is NumberedListBlock -> block.items.forEach { append(paragraph(it.content, numbering = 2 to 0, blockId = block.id)) }
                is TodoListBlock -> block.items.forEach { append(paragraph(listOf(InlineText(if (it.checked) "☑ " else "☐ ")) + it.content, blockId = block.id)) }
                is QuoteBlock -> block.children.forEach { child -> append(paragraph(listOf(InlineText(child.plainText())), "Quote", blockId = block.id)) }
                is CodeBlock -> append(paragraph(listOf(InlineText(block.code)), "Code", blockId = block.id))
                is DividerBlock -> append(paragraph(listOf(InlineText("────────────────────────")), blockId = block.id))
                is TableBlock -> append(table(block))
                is ImageBlock -> append(image(block))
                is DocumentPageBlock -> block.objects.sortedBy { it.z }.forEach { append(blocks(listOf(it.content))) }
                is RelationshipDataBlock, is InterchangeDataBlock, is StylesheetBlock -> Unit
                else -> {
                    warnings += "${block::class.simpleName} ${block.id} was approximated as text."
                    append(paragraph(listOf(InlineText(block.plainText())), blockId = block.id))
                }
            } }
        }

        val body = paragraph(listOf(InlineText(title.ifBlank { "Untitled document" })), "Title") + blocks(document.blocks)
        val documentXml = XML + """<w:document xmlns:w="$W" xmlns:r="$R"><w:body>$body<w:sectPr><w:pgSz w:w="11906" w:h="16838"/><w:pgMar w:top="1021" w:right="1021" w:bottom="1021" w:left="1021"/></w:sectPr></w:body></w:document>"""
        if (commentIds.isNotEmpty()) relationships += Relationship("rIdComments", "http://schemas.openxmlformats.org/officeDocument/2006/relationships/comments", "comments.xml")
        val commentsXml = if (commentIds.isEmpty()) null else buildString {
            append(XML).append("<w:comments xmlns:w=\"").append(W).append("\">")
            commentIds.forEach { (threadId, id) ->
                val thread = threads.first { it.id.value == threadId }
                val author = thread.comments.firstOrNull()?.author?.displayName.orEmpty()
                val bodyText = thread.comments.joinToString("\n") { it.body }
                append("<w:comment w:id=\"").append(id).append("\" w:author=\"").append(xml(author)).append("\"><w:p><w:r><w:t xml:space=\"preserve\">").append(xml(bodyText)).append("</w:t></w:r></w:p></w:comment>")
            }
            append("</w:comments>")
        }
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            zip.put("[Content_Types].xml", contentTypes(media.values, commentsXml != null))
            zip.put("_rels/.rels", ROOT_RELS)
            zip.put("word/document.xml", documentXml)
            zip.put("word/styles.xml", STYLES)
            zip.put("word/numbering.xml", NUMBERING)
            zip.put("word/_rels/document.xml.rels", relationshipXml(relationships + Relationship("rIdStyles", "http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles", "styles.xml") + Relationship("rIdNumbering", "http://schemas.openxmlformats.org/officeDocument/2006/relationships/numbering", "numbering.xml")))
            commentsXml?.let { zip.put("word/comments.xml", it) }
            media.forEach { (name, bytes) -> zip.put("word/media/$name", bytes) }
        }
        return DocxExportPackage(output.toByteArray(), warnings, media.size, commentIds.size)
    }

    private data class Relationship(val id: String, val type: String, val target: String)

    private fun inline(node: InlineNode, relationship: (String, String) -> String): String {
        fun run(properties: String, text: String) = "<w:r>${if (properties.isBlank()) "" else "<w:rPr>$properties</w:rPr>"}<w:t xml:space=\"preserve\">${xml(text)}</w:t></w:r>"
        return when (node) {
            is InlineText -> run("", node.value)
            is CodeInline -> run("<w:rFonts w:ascii=\"Consolas\" w:hAnsi=\"Consolas\"/>", node.value)
            is BoldInline -> node.children.joinToString("") { inlineWith(it, "<w:b/>", relationship) }
            is ItalicInline -> node.children.joinToString("") { inlineWith(it, "<w:i/>", relationship) }
            is UnderlineInline -> node.children.joinToString("") { inlineWith(it, "<w:u w:val=\"single\"/>", relationship) }
            is StrikethroughInline -> node.children.joinToString("") { inlineWith(it, "<w:strike/>", relationship) }
            is HighlightInline -> node.children.joinToString("") { inlineWith(it, "<w:highlight w:val=\"${xml(node.color ?: "yellow")}\"/>", relationship) }
            is FontFamilyInline -> node.children.joinToString("") { inlineWith(it, "<w:rFonts w:ascii=\"${xml(node.family)}\" w:hAnsi=\"${xml(node.family)}\"/>", relationship) }
            is FontSizeInline -> node.children.joinToString("") { inlineWith(it, "<w:sz w:val=\"${(node.sizeSp * 2).toInt().coerceIn(2, 1024)}\"/>", relationship) }
            is LinkInline -> {
                val id = relationship("http://schemas.openxmlformats.org/officeDocument/2006/relationships/hyperlink", node.url)
                "<w:hyperlink r:id=\"$id\">${node.children.joinToString("") { inline(it, relationship) }}</w:hyperlink>"
            }
            else -> run("", node.plainText())
        }
    }

    private fun inlineWith(node: InlineNode, properties: String, relationship: (String, String) -> String): String =
        if (node is InlineText) "<w:r><w:rPr>$properties</w:rPr><w:t xml:space=\"preserve\">${xml(node.value)}</w:t></w:r>"
        else "<w:r><w:rPr>$properties</w:rPr><w:t xml:space=\"preserve\">${xml(node.plainText())}</w:t></w:r>"

    private fun CommentAnchor.blockIdOrNull(): String? = when (this) {
        is CommentAnchor.Block -> blockId
        is CommentAnchor.Text -> blockId
        else -> null
    }

    private fun relationshipXml(items: List<Relationship>): String = XML + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" + items.joinToString("") { rel ->
        val external = if (rel.type.endsWith("/hyperlink")) " TargetMode=\"External\"" else ""
        "<Relationship Id=\"${xml(rel.id)}\" Type=\"${xml(rel.type)}\" Target=\"${xml(rel.target)}\"$external/>"
    } + "</Relationships>"

    private fun contentTypes(images: Collection<ByteArray>, comments: Boolean): String = XML + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"><Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/><Default Extension=\"xml\" ContentType=\"application/xml\"/><Default Extension=\"png\" ContentType=\"image/png\"/><Default Extension=\"jpg\" ContentType=\"image/jpeg\"/><Default Extension=\"gif\" ContentType=\"image/gif\"/><Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/><Override PartName=\"/word/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml\"/><Override PartName=\"/word/numbering.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.numbering+xml\"/>${if (comments) "<Override PartName=\"/word/comments.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.comments+xml\"/>" else ""}</Types>"

    private fun sniffImageExtension(bytes: ByteArray): String = when {
        bytes.take(8).toByteArray().contentEquals(byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)) -> "png"
        bytes.take(3).toByteArray().contentEquals(byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte())) -> "jpg"
        bytes.take(3).toByteArray().toString(Charsets.US_ASCII) == "GIF" -> "gif"
        else -> "png"
    }

    private fun ZipOutputStream.put(path: String, content: String) = put(path, content.toByteArray())
    private fun ZipOutputStream.put(path: String, bytes: ByteArray) { putNextEntry(ZipEntry(path)); write(bytes); closeEntry() }
    private fun xml(value: String): String = value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;")

    private const val XML = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
    private const val W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
    private const val R = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
    private const val ROOT_RELS = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"word/document.xml\"/></Relationships>"
    private const val NUMBERING = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><w:numbering xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:abstractNum w:abstractNumId=\"1\"><w:lvl w:ilvl=\"0\"><w:numFmt w:val=\"bullet\"/><w:lvlText w:val=\"•\"/></w:lvl></w:abstractNum><w:abstractNum w:abstractNumId=\"2\"><w:lvl w:ilvl=\"0\"><w:numFmt w:val=\"decimal\"/><w:lvlText w:val=\"%1.\"/></w:lvl></w:abstractNum><w:num w:numId=\"1\"><w:abstractNumId w:val=\"1\"/></w:num><w:num w:numId=\"2\"><w:abstractNumId w:val=\"2\"/></w:num></w:numbering>"
    private const val STYLES = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><w:styles xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:style w:type=\"paragraph\" w:default=\"1\" w:styleId=\"Normal\"><w:name w:val=\"Normal\"/></w:style><w:style w:type=\"paragraph\" w:styleId=\"Title\"><w:name w:val=\"Title\"/><w:rPr><w:b/><w:sz w:val=\"36\"/></w:rPr></w:style><w:style w:type=\"paragraph\" w:styleId=\"Heading1\"><w:name w:val=\"heading 1\"/></w:style><w:style w:type=\"paragraph\" w:styleId=\"Heading2\"><w:name w:val=\"heading 2\"/></w:style><w:style w:type=\"paragraph\" w:styleId=\"Quote\"><w:name w:val=\"Quote\"/></w:style><w:style w:type=\"paragraph\" w:styleId=\"Code\"><w:name w:val=\"Code\"/></w:style></w:styles>"
}
