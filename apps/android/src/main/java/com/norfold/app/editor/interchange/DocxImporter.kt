package com.norfold.app.editor.interchange

import com.norfold.app.domain.BlockAlign
import com.norfold.app.domain.BlockDocument
import com.norfold.app.domain.BoldInline
import com.norfold.app.domain.BulletListBlock
import com.norfold.app.domain.DocumentBlock
import com.norfold.app.domain.DocumentPageBlock
import com.norfold.app.domain.FontFamilyInline
import com.norfold.app.domain.FontSizeInline
import com.norfold.app.domain.HeadingBlock
import com.norfold.app.domain.HighlightInline
import com.norfold.app.domain.ImageBlock
import com.norfold.app.domain.InlineNode
import com.norfold.app.domain.InlineText
import com.norfold.app.domain.ItalicInline
import com.norfold.app.domain.LinkInline
import com.norfold.app.domain.ListItem
import com.norfold.app.domain.NumberedListBlock
import com.norfold.app.domain.PageGeometry
import com.norfold.app.domain.PageHeaderFooter
import com.norfold.app.domain.PageMargins
import com.norfold.app.domain.ParagraphBlock
import com.norfold.app.domain.QuoteBlock
import com.norfold.app.domain.SceneGeometry
import com.norfold.app.domain.SceneObject
import com.norfold.app.domain.SceneObjectKind
import com.norfold.app.domain.StrikethroughInline
import com.norfold.app.domain.TableBlock
import com.norfold.app.domain.TableCell
import com.norfold.app.domain.TodoItem
import com.norfold.app.domain.TodoListBlock
import com.norfold.app.domain.UnderlineInline
import com.norfold.app.domain.withInterchangeData
import com.norfold.app.domain.findById
import com.norfold.app.editor.relationship.AnchorState
import com.norfold.app.editor.relationship.Comment
import com.norfold.app.editor.relationship.CommentAnchor
import com.norfold.app.editor.relationship.CommentAuthorRef
import com.norfold.app.editor.relationship.CommentId
import com.norfold.app.editor.relationship.DiscussionThread
import com.norfold.app.editor.relationship.Phase7ArtifactCodec
import com.norfold.app.editor.relationship.Phase7ArtifactState
import com.norfold.app.editor.relationship.ThreadId
import com.norfold.app.domain.withRelationshipData
import java.util.Base64

object DocxImporter {
    private data class RelationshipTarget(val path: String, val external: Boolean, val type: String)
    private data class SourceComment(val id: String, val author: String, val body: String)
    private data class CommentMarker(val id: String, val start: Int?, val end: Int?)
    private data class ParsedParagraph(
        val block: DocumentBlock,
        val images: List<String>,
        val comments: List<CommentMarker>,
        val pageBreakAfter: Boolean,
        val issues: List<String>,
    )

    fun import(request: ImportRequest, preflight: ImportPreflight = ImportPreflightService.inspect(request)): ImportResult {
        val started = System.nanoTime()
        if (preflight.detectedFormat != ImportFormat.DOCX || !preflight.canApply) {
            return ImportResult.Failure(preflight.errors.ifEmpty {
                listOf(FidelityIssue("wrong_format", "DOCX importer received a different format.", FidelitySeverity.ERROR))
            }, preflight.metrics)
        }
        return try {
            val pkg = BoundedPackageReader.read(request.bytes, request.limits)
            val documentXml = pkg["word/document.xml"]
                ?: return ImportResult.Failure(listOf(FidelityIssue("missing_document", "DOCX has no word/document.xml.", FidelitySeverity.ERROR)))
            val parsedDocument = SecureXml.parse(documentXml, request.limits)
            val relationships = parseRelationships(pkg["word/_rels/document.xml.rels"], request.limits)
            val numbering = parseNumbering(pkg["word/numbering.xml"], request.limits)
            val sourceComments = parseComments(pkg["word/comments.xml"], request.limits, request.limits.maxComments)
            val sourceHash = preflight.source.sha256
            val body = parsedDocument.root.descendants("body").firstOrNull()
                ?: return ImportResult.Failure(listOf(FidelityIssue("missing_body", "DOCX document body is missing.", FidelitySeverity.ERROR)))
            val blocks = mutableListOf<DocumentBlock>()
            val regions = mutableListOf<FidelityRegion>()
            val issues = preflight.warnings.toMutableList()
            val assets = mutableListOf<ImportedAsset>()
            val commentAnchors = mutableMapOf<String, Pair<String, CommentMarker>>()
            var pendingListKind: String? = null
            val pendingItems = mutableListOf<ListItem>()
            var currentPageBlocks = mutableListOf<DocumentBlock>()
            val pages = mutableListOf<List<DocumentBlock>>()
            var geometry = parsePageGeometry(body.descendants("sectPr").lastOrNull(), pkg, relationships, request.limits)
            var paragraphIndex = 0

            fun flushList() {
                if (pendingItems.isEmpty()) return
                val path = "word/document.xml/list/${blocks.size}"
                val id = stableImportId("block", sourceHash, path)
                val block = if (pendingListKind == "number") {
                    NumberedListBlock(id = id, items = pendingItems.toList())
                } else {
                    BulletListBlock(id = id, items = pendingItems.toList())
                }
                blocks += block
                currentPageBlocks += block
                regions += nativeRegion(sourceHash, path, id, "List")
                pendingItems.clear()
                pendingListKind = null
            }

            fun appendBlock(block: DocumentBlock, path: String, state: FidelityState = FidelityState.NATIVE, detail: String = "") {
                flushList()
                blocks += block
                currentPageBlocks += block
                regions += FidelityRegion(stableRegionId(sourceHash, path), block.id, path, state, block::class.simpleName.orEmpty(), detail)
            }

            body.children.forEachIndexed { childIndex, child ->
                when (child.localName) {
                    "p" -> {
                        val path = "word/document.xml/p/$paragraphIndex"
                        val parsed = parseParagraph(child, path, sourceHash, relationships)
                        paragraphIndex++
                        val listKind = child.child("pPr")?.child("numPr")?.let {
                            val numId = it.child("numId")?.attribute("val") ?: return@let null
                            if (numId == "0") null else if (numbering[numId].equals("bullet", true)) "bullet" else "number"
                        }
                        if (listKind != null) {
                            if (pendingListKind != null && pendingListKind != listKind) flushList()
                            pendingListKind = listKind
                            pendingItems += ListItem(
                                id = stableImportId("item", sourceHash, "$path/item"),
                                content = inlineOf(parsed.block),
                            )
                        } else {
                            appendBlock(parsed.block, path)
                        }
                        parsed.comments.forEach { commentAnchors[it.id] = parsed.block.id to it }
                        parsed.issues.forEach { detail ->
                            issues += FidelityIssue("docx_approximation", detail, FidelitySeverity.WARNING, stableRegionId(sourceHash, path))
                        }
                        parsed.images.forEachIndexed { imageIndex, relationshipId ->
                            val rel = relationships[relationshipId]
                            val target = rel?.takeUnless { it.external }?.path?.let(::resolveWordPath)
                            val bytes = target?.let(pkg::get)
                            if (target == null || bytes == null) {
                                issues += FidelityIssue("image_missing", "An image relationship could not be resolved locally.", FidelitySeverity.WARNING)
                            } else if (assets.count { it.mediaType.startsWith("image/") } >= request.limits.maxImages) {
                                throw ImportSecurityException("Image count exceeds ${request.limits.maxImages}")
                            } else {
                                validateImageDimensions(bytes, target, request.limits)
                                val assetId = stableImportId("asset", sourceHash, target)
                                val mediaType = imageMediaType(target)
                                assets += ImportedAsset(assetId, mediaType, bytes, target)
                                val imagePath = "$path/image/$imageIndex"
                                val image = ImageBlock(
                                    id = stableImportId("image", sourceHash, imagePath),
                                    source = "norfold-import://$assetId",
                                    caption = "",
                                )
                                appendBlock(image, imagePath, FidelityState.HYBRID, "Image bytes are preserved as a local imported asset.")
                            }
                        }
                        if (parsed.pageBreakAfter) {
                            flushList()
                            pages += currentPageBlocks
                            currentPageBlocks = mutableListOf()
                        }
                    }
                    "tbl" -> {
                        val path = "word/document.xml/table/$childIndex"
                        appendBlock(parseTable(child, sourceHash, path), path)
                    }
                    "sectPr" -> geometry = parsePageGeometry(child, pkg, relationships, request.limits)
                    else -> if (child.localName.isNotBlank()) {
                        issues += FidelityIssue("unsupported_ooxml", "Unsupported DOCX body element '${child.localName}' was retained in the fidelity report.", FidelitySeverity.WARNING)
                    }
                }
            }
            flushList()
            if (currentPageBlocks.isNotEmpty() || pages.isEmpty()) pages += currentPageBlocks

            val shouldUsePages = request.intent in setOf(ImportIntent.PRESERVE_APPEARANCE, ImportIntent.READ_AND_ANNOTATE) ||
                (request.mode == ImportMode.COMPATIBILITY && body.descendants("sectPr").any())
            val contentDocument = if (shouldUsePages) {
                BlockDocument(pages.mapIndexed { pageIndex, pageBlocks ->
                    pageFromBlocks(pageBlocks, geometry, sourceHash, pageIndex)
                })
            } else {
                BlockDocument(blocks)
            }
            if (shouldUsePages) {
                regions.replaceAll { region ->
                    if (region.state == FidelityState.NATIVE) region.copy(
                        state = FidelityState.APPROXIMATED,
                        details = "Converted to bounded Norfold page objects using source page geometry.",
                    ) else region
                }
            }

            val threads = sourceComments.mapNotNull { source ->
                val anchored = commentAnchors[source.id]
                val blockId = anchored?.first
                val marker = anchored?.second
                val anchoredBlock = blockId?.let(contentDocument::findById)
                val threadId = ThreadId(stableImportId("thread", sourceHash, "word/comments.xml/${source.id}"))
                val anchor = if (blockId == null || anchoredBlock == null) {
                    CommentAnchor.Artifact(request.targetArtifactId, AnchorState.DETACHED)
                } else if (marker?.start != null && marker.end != null && marker.end >= marker.start) {
                    val text = anchoredBlock.plainText()
                    val start = marker.start.coerceIn(0, text.length)
                    val end = marker.end.coerceIn(start, text.length)
                    CommentAnchor.Text(
                        artifactId = request.targetArtifactId,
                        blockId = blockId,
                        start = start,
                        end = end,
                        quote = text.substring(start, end),
                    )
                } else {
                    CommentAnchor.Block(request.targetArtifactId, blockId)
                }
                val bodyText = source.body.ifBlank { "Imported empty comment" }
                DiscussionThread(
                    id = threadId,
                    anchor = anchor,
                    comments = listOf(
                        Comment(
                            id = CommentId(stableImportId("comment", sourceHash, "word/comments.xml/${source.id}/0")),
                            threadId = threadId,
                            author = CommentAuthorRef("docx:${source.author.ifBlank { "unknown" }}", source.author.ifBlank { "Unknown author" }),
                            createdAt = 0L,
                            body = bodyText,
                        ),
                    ),
                )
            }
            var document = contentDocument
            if (threads.isNotEmpty()) {
                document = document.withRelationshipData(Phase7ArtifactCodec.encode(Phase7ArtifactState(threads = threads)))
                regions += FidelityRegion(
                    stableRegionId(sourceHash, "word/comments.xml"),
                    "document-relationships",
                    "word/comments.xml",
                    if (threads.any { it.anchor.state == AnchorState.DETACHED }) FidelityState.APPROXIMATED else FidelityState.NATIVE,
                    "Comments",
                    "Imported through the canonical Phase-7 discussion model.",
                )
            }

            val compatibility = compatibilityMetadata(pkg, request, sourceHash)
            val report = FidelityReport(ImportFormat.DOCX, request.mode, request.intent, regions, issues)
            val metadata = InterchangeArtifactMetadata(1, preflight.source, compatibility, report)
            document = document.withInterchangeData(InterchangeMetadataCodec.encode(metadata))
            assets.add(0, ImportedAsset(stableImportId("source", sourceHash, request.fileName), preflight.source.mediaType, request.bytes, request.fileName))
            ImportResult.Success(
                ImportedArtifact(document.normalized(), assets = assets, source = preflight.source, compatibility = compatibility, fidelity = report),
                preflight.metrics.copy(
                    xmlNodesParsed = preflight.metrics.xmlNodesParsed + parsedDocument.nodeCount,
                    imagesDecoded = assets.count { it.mediaType.startsWith("image/") },
                    durationNanos = System.nanoTime() - started,
                ),
            )
        } catch (e: Exception) {
            ImportResult.Failure(
                listOf(FidelityIssue("docx_import_failed", e.message ?: "DOCX import failed.", FidelitySeverity.ERROR)),
                preflight.metrics.copy(durationNanos = System.nanoTime() - started),
            )
        }
    }

    private fun parseRelationships(bytes: ByteArray?, limits: ImportSecurityLimits): Map<String, RelationshipTarget> {
        if (bytes == null) return emptyMap()
        val parsed = SecureXml.parse(bytes, limits)
        return parsed.root.descendants("Relationship").mapNotNull { node ->
            val id = node.attribute("Id") ?: return@mapNotNull null
            val target = node.attribute("Target") ?: return@mapNotNull null
            id to RelationshipTarget(target, node.attribute("TargetMode").equals("External", true), node.attribute("Type").orEmpty())
        }.toMap()
    }

    private fun parseComments(bytes: ByteArray?, limits: ImportSecurityLimits, max: Int): List<SourceComment> {
        if (bytes == null) return emptyList()
        val parsed = SecureXml.parse(bytes, limits)
        val nodes = parsed.root.descendants("comment").take(max + 1).toList()
        if (nodes.size > max) throw ImportSecurityException("Comment count exceeds $max")
        return nodes.mapNotNull { node ->
            val id = node.attribute("id") ?: return@mapNotNull null
            SourceComment(id, node.attribute("author").orEmpty(), node.descendants("t").joinToString("") { it.allText() })
        }
    }

    private fun parseNumbering(bytes: ByteArray?, limits: ImportSecurityLimits): Map<String, String> {
        if (bytes == null) return emptyMap()
        val root = SecureXml.parse(bytes, limits).root
        val abstractFormats = root.descendants("abstractNum").mapNotNull { abstract ->
            val id = abstract.attribute("abstractNumId") ?: return@mapNotNull null
            val format = abstract.descendants("numFmt").firstOrNull()?.attribute("val") ?: "decimal"
            id to format
        }.toMap()
        return root.descendants("num").mapNotNull { num ->
            val id = num.attribute("numId") ?: return@mapNotNull null
            val abstractId = num.child("abstractNumId")?.attribute("val") ?: return@mapNotNull null
            id to (abstractFormats[abstractId] ?: "decimal")
        }.toMap()
    }

    private fun parseParagraph(
        node: XmlNode,
        path: String,
        sourceHash: String,
        relationships: Map<String, RelationshipTarget>,
    ): ParsedParagraph {
        val inlines = mutableListOf<InlineNode>()
        val images = mutableListOf<String>()
        val issues = mutableListOf<String>()
        val starts = mutableMapOf<String, Int>()
        val markers = mutableMapOf<String, CommentMarker>()
        var textLength = 0
        var pageBreak = false

        fun append(nodeValue: InlineNode) {
            inlines += nodeValue
            textLength += nodeValue.plainText().length
        }

        fun visit(current: XmlNode, hyperlink: String? = null) {
            when (current.localName) {
                "commentRangeStart" -> current.attribute("id")?.let { starts[it] = textLength }
                "commentRangeEnd" -> current.attribute("id")?.let { id -> markers[id] = CommentMarker(id, starts[id], textLength) }
                "hyperlink" -> {
                    val id = current.attribute("id")
                    val target = id?.let(relationships::get)
                    val url = target?.takeIf { it.external }?.path
                    current.children.forEach { visit(it, url) }
                }
                "r" -> {
                    val properties = current.child("rPr")
                    val text = buildString {
                        current.children.forEach { child ->
                            when (child.localName) {
                                "t", "instrText" -> append(child.allText())
                                "tab" -> append('\t')
                                "br", "cr" -> {
                                    if (child.attribute("type") == "page") pageBreak = true else append('\n')
                                }
                                "drawing", "pict" -> child.descendants("blip").forEach { blip ->
                                    blip.attribute("embed")?.let(images::add)
                                }
                            }
                        }
                    }
                    if (text.isNotEmpty()) {
                        var rich: InlineNode = InlineText(text)
                        if (properties?.child("b") != null) rich = BoldInline(listOf(rich))
                        if (properties?.child("i") != null) rich = ItalicInline(listOf(rich))
                        if (properties?.child("u") != null) rich = UnderlineInline(listOf(rich))
                        if (properties?.child("strike") != null || properties?.child("dstrike") != null) rich = StrikethroughInline(listOf(rich))
                        properties?.child("rFonts")?.attribute("ascii")?.takeIf(String::isNotBlank)?.let { rich = FontFamilyInline(it, listOf(rich)) }
                        properties?.child("sz")?.attribute("val")?.toFloatOrNull()?.let { rich = FontSizeInline((it / 2f).coerceIn(1f, 512f), listOf(rich)) }
                        properties?.child("highlight")?.attribute("val")?.let { rich = HighlightInline(it, listOf(rich)) }
                        if (properties?.child("color") != null) issues += "Inline text color was preserved as a fidelity warning because the current inline model has no direct text-color node."
                        if (!hyperlink.isNullOrBlank()) rich = LinkInline(hyperlink, listOf(rich))
                        append(rich)
                    }
                }
                else -> current.children.forEach { visit(it, hyperlink) }
            }
        }
        node.children.forEach(::visit)
        starts.forEach { (id, start) -> markers.putIfAbsent(id, CommentMarker(id, start, null)) }
        val pPr = node.child("pPr")
        val style = pPr?.child("pStyle")?.attribute("val").orEmpty()
        val align = when (pPr?.child("jc")?.attribute("val")) {
            "center" -> BlockAlign.Center
            "right", "end" -> BlockAlign.End
            "both", "distribute" -> BlockAlign.Justify
            else -> BlockAlign.Start
        }
        val id = stableImportId("block", sourceHash, path)
        val block: DocumentBlock = when {
            style.startsWith("Heading", true) -> HeadingBlock(id, style.filter(Char::isDigit).toIntOrNull()?.coerceIn(1, 6) ?: 1, inlines, align)
            style.contains("Quote", true) -> QuoteBlock(id, listOf(ParagraphBlock(stableImportId("paragraph", sourceHash, "$path/quote"), inlines, align)))
            inlines.plain().trimStart().startsWith("☐") || inlines.plain().trimStart().startsWith("☑") -> {
                val checked = inlines.plain().trimStart().startsWith("☑")
                TodoListBlock(id, listOf(TodoItem(stableImportId("todo", sourceHash, "$path/todo"), checked, inlines)))
            }
            else -> ParagraphBlock(id, inlines, align)
        }
        return ParsedParagraph(block, images, markers.values.toList(), pageBreak, issues)
    }

    private fun parseTable(node: XmlNode, sourceHash: String, path: String): TableBlock {
        val rows = node.children("tr").mapIndexed { rowIndex, row ->
            row.children("tc").mapIndexed { columnIndex, cell ->
                val content = cell.descendants("p").flatMap { paragraph ->
                    val text = paragraph.descendants("t").joinToString("") { it.allText() }
                    if (text.isEmpty()) emptyList() else listOf<InlineNode>(InlineText(text))
                }.toList()
                TableCell(content.ifEmpty { listOf(InlineText("")) })
            }
        }
        val headers = rows.firstOrNull().orEmpty()
        return TableBlock(
            id = stableImportId("table", sourceHash, path),
            headers = headers,
            rows = if (rows.size > 1) rows.drop(1) else emptyList(),
        )
    }

    private fun parsePageGeometry(
        sectPr: XmlNode?,
        pkg: BoundedPackage,
        relationships: Map<String, RelationshipTarget>,
        limits: ImportSecurityLimits,
    ): PageGeometry {
        val size = sectPr?.child("pgSz")
        val margins = sectPr?.child("pgMar")
        val width = (size?.attribute("w")?.toFloatOrNull()?.div(20f) ?: 595f).coerceAtLeast(1f)
        val height = (size?.attribute("h")?.toFloatOrNull()?.div(20f) ?: 842f).coerceAtLeast(1f)
        fun margin(name: String, fallback: Float) = margins?.attribute(name)?.toFloatOrNull()?.div(20f) ?: fallback
        val headerText = resolveHeaderFooter(sectPr, "headerReference", pkg, relationships, limits)
        val footerText = resolveHeaderFooter(sectPr, "footerReference", pkg, relationships, limits)
        return PageGeometry(
            width = width,
            height = height,
            margins = PageMargins(margin("top", 72f), margin("right", 72f), margin("bottom", 72f), margin("left", 72f)),
            headerFooter = PageHeaderFooter(
                headerHeight = if (headerText.isBlank()) 0f else 24f,
                footerHeight = if (footerText.isBlank()) 0f else 24f,
                headerText = headerText,
                footerText = footerText,
            ),
        ).normalized()
    }

    private fun resolveHeaderFooter(
        sectPr: XmlNode?,
        referenceName: String,
        pkg: BoundedPackage,
        relationships: Map<String, RelationshipTarget>,
        limits: ImportSecurityLimits,
    ): String {
        val id = sectPr?.children(referenceName)?.firstOrNull()?.attribute("id") ?: return ""
        val rel = relationships[id]?.takeUnless { it.external } ?: return ""
        val path = resolveWordPath(rel.path)
        return pkg[path]?.let { SecureXml.parse(it, limits).root.descendants("t").joinToString("") { node -> node.allText() } }.orEmpty()
    }

    private fun pageFromBlocks(blocks: List<DocumentBlock>, geometry: PageGeometry, sourceHash: String, pageIndex: Int): DocumentPageBlock {
        var y = geometry.contentTop
        val objects = blocks.mapIndexed { index, block ->
            val lineCount = block.plainText().count { it == '\n' } + 1
            val height = when (block) {
                is TableBlock -> ((block.rows.size + 1) * 28f).coerceAtLeast(48f)
                is ImageBlock -> 180f
                else -> (lineCount * 18f + 18f).coerceAtLeast(36f)
            }.coerceAtMost(geometry.contentHeight)
            val kind = when (block) {
                is TableBlock -> SceneObjectKind.Table
                is ImageBlock -> SceneObjectKind.Image
                else -> SceneObjectKind.TextFrame
            }
            SceneObject(
                id = stableImportId("object", sourceHash, "page/$pageIndex/object/$index"),
                kind = kind,
                geometry = SceneGeometry(geometry.contentLeft, y, geometry.contentWidth, height),
                z = index,
                content = block,
            ).also { y += height + 6f }
        }
        return DocumentPageBlock(stableImportId("page", sourceHash, "page/$pageIndex"), geometry, objects).normalized()
    }

    private fun compatibilityMetadata(pkg: BoundedPackage, request: ImportRequest, sourceHash: String): CompatibilityMetadata {
        if (request.mode != ImportMode.COMPATIBILITY) return CompatibilityMetadata()
        var kept = 0L
        val entries = mutableListOf<CompatibilityEntry>()
        val omitted = mutableListOf<String>()
        pkg.entries.values.sortedBy { it.name }.forEach { entry ->
            val safeOpaque = entry.name.endsWith(".xml", true) || entry.name.endsWith(".rels", true)
            if (!safeOpaque || entry.name in setOf("word/document.xml", "word/comments.xml")) return@forEach
            if (kept + entry.bytes.size > request.limits.maxCompatibilityBytes) {
                omitted += entry.name
            } else {
                kept += entry.bytes.size
                entries += CompatibilityEntry(
                    path = entry.name,
                    mediaType = if (entry.name.endsWith(".rels")) "application/vnd.openxmlformats-package.relationships+xml" else "application/xml",
                    sha256 = sha256(entry.bytes),
                    payloadBase64 = Base64.getEncoder().encodeToString(entry.bytes),
                )
            }
        }
        return CompatibilityMetadata(entries = entries, preservedBytes = kept, omittedPaths = omitted)
    }

    private fun nativeRegion(hash: String, path: String, targetId: String, summary: String) = FidelityRegion(
        stableRegionId(hash, path), targetId, path, FidelityState.NATIVE, summary,
    )

    private fun resolveWordPath(target: String): String {
        val clean = target.replace('\\', '/').removePrefix("/")
        val parts = mutableListOf<String>()
        (if (clean.startsWith("word/")) clean else "word/$clean").split('/').forEach { part ->
            when (part) {
                "", "." -> Unit
                ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.lastIndex) else throw ImportSecurityException("Unsafe DOCX relationship target")
                else -> parts += part
            }
        }
        return parts.joinToString("/")
    }

    private fun imageMediaType(path: String): String = when (path.substringAfterLast('.').lowercase()) {
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "svg" -> "image/svg+xml"
        else -> "application/octet-stream"
    }

    private fun validateImageDimensions(bytes: ByteArray, path: String, limits: ImportSecurityLimits) {
        fun u16(offset: Int, little: Boolean = false): Int {
            if (offset + 1 >= bytes.size) return 0
            val a = bytes[offset].toInt() and 0xff
            val b = bytes[offset + 1].toInt() and 0xff
            return if (little) a or (b shl 8) else (a shl 8) or b
        }
        fun u32(offset: Int): Long {
            if (offset + 3 >= bytes.size) return 0
            return (0..3).fold(0L) { value, index -> (value shl 8) or (bytes[offset + index].toLong() and 0xff) }
        }
        val extension = path.substringAfterLast('.').lowercase()
        val dimensions: Pair<Long, Long>? = when (extension) {
            "png" -> if (bytes.size >= 24) u32(16) to u32(20) else null
            "gif" -> if (bytes.size >= 10) u16(6, true).toLong() to u16(8, true).toLong() else null
            "jpg", "jpeg" -> {
                var offset = 2
                var found: Pair<Long, Long>? = null
                while (offset + 8 < bytes.size && found == null) {
                    if ((bytes[offset].toInt() and 0xff) != 0xff) { offset++; continue }
                    val marker = bytes[offset + 1].toInt() and 0xff
                    val length = u16(offset + 2)
                    if (marker in setOf(0xc0, 0xc1, 0xc2, 0xc3, 0xc5, 0xc6, 0xc7, 0xc9, 0xca, 0xcb, 0xcd, 0xce, 0xcf) && offset + 8 < bytes.size) {
                        found = u16(offset + 7).toLong() to u16(offset + 5).toLong()
                    }
                    if (length < 2) break
                    offset += length + 2
                }
                found
            }
            else -> null
        }
        dimensions?.let { (width, height) ->
            if (width <= 0 || height <= 0 || width > limits.maxImagePixels || height > limits.maxImagePixels || width > limits.maxImagePixels / height) {
                throw ImportSecurityException("Image '$path' exceeds the ${limits.maxImagePixels}-pixel limit")
            }
        }
    }

    private fun inlineOf(block: DocumentBlock): List<InlineNode> = when (block) {
        is ParagraphBlock -> block.content
        is HeadingBlock -> block.content
        else -> listOf(InlineText(block.plainText()))
    }

    private fun List<InlineNode>.plain(): String = joinToString("") { it.plainText() }
}
