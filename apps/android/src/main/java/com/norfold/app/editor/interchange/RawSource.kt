package com.norfold.app.editor.interchange

import com.norfold.app.domain.*
import com.norfold.app.domain.style.StyleAssignment
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

private const val RAW_HEADER = "norfold-source 1\n"

@Serializable
data class RawSourceDocument(val version: Int = 1, val blocks: List<RawSourceBlock>, val relationships: String? = null)

@Serializable
data class RawSourceBlock(
    val type: String,
    val id: String,
    val text: String = "",
    val level: Int = 0,
    val checked: Boolean = false,
    val align: String = "Start",
    val attributes: Map<String, String> = emptyMap(),
    val inlines: List<RawSourceInline> = emptyList(),
    val children: List<RawSourceBlock> = emptyList(),
    val rows: List<List<List<RawSourceInline>>> = emptyList(),
    val objects: List<RawSourceObject> = emptyList(),
)

@Serializable
data class RawSourceInline(val type: String, val value: String = "", val attributes: Map<String, String> = emptyMap(), val children: List<RawSourceInline> = emptyList())

@Serializable
data class RawSourceObject(
    val id: String,
    val kind: String,
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
    val rotation: Float = 0f,
    val z: Int,
    val visible: Boolean,
    val name: String,
    val content: RawSourceBlock,
    val extensions: Map<String, String> = emptyMap(),
)

data class RawSourceDiagnostic(val line: Int, val column: Int, val message: String)

sealed interface RawSourceParseResult {
    data class Success(val ast: RawSourceDocument, val tokenCount: Int, val durationNanos: Long) : RawSourceParseResult
    data class Failure(val diagnostics: List<RawSourceDiagnostic>, val durationNanos: Long) : RawSourceParseResult
}

/** Explicit product parser: header, JSON AST, structural budgets, then typed product AST. */
object RawSourceParser {
    private val json = Json { ignoreUnknownKeys = false; explicitNulls = false }

    fun parse(source: String, limits: ImportSecurityLimits = ImportSecurityLimits()): RawSourceParseResult {
        val started = System.nanoTime()
        if (source.toByteArray().size > limits.maxRawSourceBytes) return failure("Source exceeds ${limits.maxRawSourceBytes} bytes", source, started)
        if (!source.startsWith(RAW_HEADER)) return failure("Expected product header 'norfold-source 1'", source, started)
        return try {
            val body = source.removePrefix(RAW_HEADER)
            val element = json.parseToJsonElement(body)
            val budget = RawBudget(limits)
            budget.visit(element, 0)
            val ast = json.decodeFromJsonElement(RawSourceDocument.serializer(), element)
            if (ast.version != 1) return failure("Unsupported Raw Source version ${ast.version}; Apply is disabled", source, started)
            RawSourceParseResult.Success(ast, budget.tokens, System.nanoTime() - started)
        } catch (e: Exception) {
            val position = Regex("offset (\\d+)").find(e.message.orEmpty())?.groupValues?.get(1)?.toIntOrNull() ?: 0
            val prefix = source.take(position.coerceIn(0, source.length))
            val line = prefix.count { it == '\n' } + 1
            val column = prefix.substringAfterLast('\n').length + 1
            RawSourceParseResult.Failure(listOf(RawSourceDiagnostic(line, column, e.message ?: "Malformed Raw Source")), System.nanoTime() - started)
        }
    }

    private fun failure(message: String, source: String, started: Long): RawSourceParseResult.Failure =
        RawSourceParseResult.Failure(listOf(RawSourceDiagnostic(source.count { it == '\n' }.coerceAtLeast(0) + 1, 1, message)), System.nanoTime() - started)

    private class RawBudget(private val limits: ImportSecurityLimits) {
        var tokens = 0
        fun visit(element: JsonElement, depth: Int) {
            if (depth > limits.maxRawNesting) throw ImportSecurityException("Raw Source nesting exceeds ${limits.maxRawNesting}")
            tokens++
            if (tokens > limits.maxRawTokens) throw ImportSecurityException("Raw Source token count exceeds ${limits.maxRawTokens}")
            when (element) {
                is JsonObject -> element.forEach { (key, value) ->
                    if (key.length > limits.maxRawStringChars) throw ImportSecurityException("Raw Source key is too long")
                    tokens++
                    visit(value, depth + 1)
                }
                is JsonArray -> element.forEach { visit(it, depth + 1) }
                is JsonPrimitive -> if (element.isString && element.content.length > limits.maxRawStringChars) throw ImportSecurityException("Raw Source string is too long")
            }
        }
    }
}

object RawSourceSerializer {
    private val json = Json { prettyPrint = true; prettyPrintIndent = "  "; explicitNulls = false; encodeDefaults = true }
    private val compactJson = Json { explicitNulls = false; encodeDefaults = true }

    fun serialize(document: BlockDocument): String {
        val relationship = document.relationshipDataBlock()?.payload
        val blocks = document.blocks.filterNot { it is RelationshipDataBlock || it is InterchangeDataBlock }.map(::toRaw)
        return RAW_HEADER + json.encodeToString(RawSourceDocument(blocks = blocks, relationships = relationship)) + "\n"
    }

    fun toArtifact(ast: RawSourceDocument): BlockDocument {
        var result = BlockDocument(ast.blocks.map(::fromRaw)).normalized()
        ast.relationships?.let { result = result.withRelationshipData(it) }
        return result
    }

    fun canRepresent(document: BlockDocument): List<String> = buildList {
        fun visit(block: DocumentBlock) {
            if (block is UnknownBlock) add("Unknown block ${block.id} must be preserved by its owning newer version; Raw Source Apply is disabled.")
            if (block is InterchangeDataBlock) add("Imported compatibility metadata is not editable in Raw Source; convert to Native mode first.")
            if (block is QuoteBlock) block.children.forEach(::visit)
            if (block is DocumentPageBlock) block.objects.forEach { visit(it.content) }
        }
        document.blocks.forEach(::visit)
    }

    private fun toRaw(block: DocumentBlock): RawSourceBlock = when (block) {
        is ParagraphBlock -> RawSourceBlock("paragraph", block.id, align = block.align.name, attributes = style(block.style), inlines = block.content.map(::toRaw))
        is HeadingBlock -> RawSourceBlock("heading", block.id, level = block.level, align = block.align.name, attributes = style(block.style), inlines = block.content.map(::toRaw))
        is BulletListBlock -> RawSourceBlock("bullet-list", block.id, attributes = style(block.style), children = block.items.map { RawSourceBlock("item", it.id, inlines = it.content.map(::toRaw)) })
        is NumberedListBlock -> RawSourceBlock("numbered-list", block.id, level = block.start, attributes = style(block.style), children = block.items.map { RawSourceBlock("item", it.id, inlines = it.content.map(::toRaw)) })
        is TodoListBlock -> RawSourceBlock("todo-list", block.id, attributes = style(block.style), children = block.items.map { RawSourceBlock("todo", it.id, checked = it.checked, inlines = it.content.map(::toRaw)) })
        is QuoteBlock -> RawSourceBlock("quote", block.id, attributes = style(block.style), children = block.children.map(::toRaw))
        is CodeBlock -> RawSourceBlock("code", block.id, text = block.code, attributes = style(block.style) + ("language" to block.language))
        is DividerBlock -> RawSourceBlock("divider", block.id)
        is ImageBlock -> RawSourceBlock("image", block.id, text = block.caption, attributes = mapOf("source" to block.source, "layout" to block.layout.name, "height" to block.displayHeightDp.toString(), "edit" to block.imageEditDocument.orEmpty()))
        is ChartBlock -> RawSourceBlock("chart", block.id, text = block.chartDocument ?: block.vegaLiteSpec, attributes = mapOf("native" to (block.chartDocument != null).toString(), "height" to block.editorHeightDp.toString()))
        is FileBlock -> RawSourceBlock("file", block.id, text = block.name, attributes = mapOf("mime" to block.mimeType, "size" to block.sizeBytes.toString(), "uri" to block.uri))
        is TableBlock -> RawSourceBlock("table", block.id, attributes = mapOf("workbook" to block.calculationPayload.orEmpty(), "schema" to (block.calculationSchemaVersion ?: 0).toString()), rows = (listOf(block.headers) + block.rows).map { row -> row.map { cell -> cell.content.map(::toRaw) } })
        is DocumentPageBlock -> RawSourceBlock(
            "page", block.id,
            attributes = mapOf("width" to block.geometry.width.toString(), "height" to block.geometry.height.toString(), "top" to block.geometry.margins.top.toString(), "right" to block.geometry.margins.right.toString(), "bottom" to block.geometry.margins.bottom.toString(), "left" to block.geometry.margins.left.toString()) + block.extensions.mapKeys { "extension.${it.key}" },
            objects = block.objects.map { obj -> RawSourceObject(obj.id, obj.kind.name, obj.geometry.x, obj.geometry.y, obj.geometry.width, obj.geometry.height, obj.geometry.rotation, obj.z, obj.visible, obj.name, toRaw(obj.content), obj.extensions) },
        )
        is StylesheetBlock -> RawSourceBlock("stylesheet", block.id, text = compactJson.encodeToString(StylesheetBlock.serializer(), block))
        is RelationshipDataBlock -> RawSourceBlock("relationships", block.id, text = block.payload)
        is InterchangeDataBlock -> RawSourceBlock("interchange", block.id, text = block.payload)
        is UnknownBlock -> RawSourceBlock("unknown", block.id, text = block.rawJson)
        else -> RawSourceBlock("unsupported", block.id, text = block.plainText(), attributes = mapOf("canonicalType" to block::class.simpleName.orEmpty()))
    }

    private fun fromRaw(raw: RawSourceBlock): DocumentBlock = when (raw.type) {
        "paragraph" -> ParagraphBlock(raw.id, raw.inlines.map(::fromRaw), enumOr(raw.align, BlockAlign.Start), parseStyle(raw))
        "heading" -> HeadingBlock(raw.id, raw.level.coerceIn(1, 6), raw.inlines.map(::fromRaw), enumOr(raw.align, BlockAlign.Start), parseStyle(raw))
        "bullet-list" -> BulletListBlock(raw.id, raw.children.map { ListItem(it.id, it.inlines.map(::fromRaw)) }, parseStyle(raw))
        "numbered-list" -> NumberedListBlock(raw.id, raw.level.coerceAtLeast(1), raw.children.map { ListItem(it.id, it.inlines.map(::fromRaw)) }, parseStyle(raw))
        "todo-list" -> TodoListBlock(raw.id, raw.children.map { TodoItem(it.id, it.checked, it.inlines.map(::fromRaw)) }, parseStyle(raw))
        "quote" -> QuoteBlock(raw.id, raw.children.map(::fromRaw), parseStyle(raw))
        "code" -> CodeBlock(raw.id, raw.attributes["language"].orEmpty(), raw.text, style = parseStyle(raw))
        "divider" -> DividerBlock(raw.id)
        "image" -> ImageBlock(raw.id, raw.attributes["source"].orEmpty(), raw.text, enumOr(raw.attributes["layout"], ImageLayout.Fit), raw.attributes["height"]?.toFloatOrNull() ?: 220f, raw.attributes["edit"]?.takeIf(String::isNotBlank))
        "chart" -> ChartBlock(raw.id, if (raw.attributes["native"] == "true") "{}" else raw.text, raw.attributes["height"]?.toFloatOrNull() ?: 180f, raw.text.takeIf { raw.attributes["native"] == "true" })
        "file" -> FileBlock(raw.id, raw.text, raw.attributes["mime"].orEmpty().ifBlank { "application/octet-stream" }, raw.attributes["size"]?.toLongOrNull() ?: 0, raw.attributes["uri"].orEmpty())
        "table" -> {
            val rows = raw.rows.map { row -> row.map { cell -> TableCell(cell.map(::fromRaw)) } }
            TableBlock(raw.id, rows.firstOrNull().orEmpty(), rows.drop(1), calculationPayload = raw.attributes["workbook"]?.takeIf(String::isNotBlank), calculationSchemaVersion = raw.attributes["schema"]?.toIntOrNull()?.takeIf { it > 0 })
        }
        "page" -> {
            val margins = PageMargins(raw.attributes["top"]?.toFloatOrNull() ?: 72f, raw.attributes["right"]?.toFloatOrNull() ?: 72f, raw.attributes["bottom"]?.toFloatOrNull() ?: 72f, raw.attributes["left"]?.toFloatOrNull() ?: 72f)
            val geometry = PageGeometry(width = raw.attributes["width"]?.toFloatOrNull() ?: 595f, height = raw.attributes["height"]?.toFloatOrNull() ?: 842f, margins = margins)
            DocumentPageBlock(raw.id, geometry, raw.objects.map { obj -> SceneObject(obj.id, enumOr(obj.kind, SceneObjectKind.Preserved), SceneGeometry(obj.x, obj.y, obj.width, obj.height, obj.rotation), obj.z, visible = obj.visible, name = obj.name, content = fromRaw(obj.content), extensions = obj.extensions) }, raw.attributes.filterKeys { it.startsWith("extension.") }.mapKeys { it.key.removePrefix("extension.") }).normalized()
        }
        "stylesheet" -> compactJson.decodeFromString(StylesheetBlock.serializer(), raw.text)
        "relationships" -> RelationshipDataBlock(raw.id, raw.text)
        "interchange", "unknown", "unsupported" -> throw IllegalArgumentException("Raw Source block type '${raw.type}' cannot be safely applied by this version")
        else -> throw IllegalArgumentException("Unknown Raw Source block type '${raw.type}'")
    }

    private fun toRaw(inline: InlineNode): RawSourceInline = when (inline) {
        is InlineText -> RawSourceInline("text", inline.value)
        is BoldInline -> RawSourceInline("bold", children = inline.children.map(::toRaw))
        is ItalicInline -> RawSourceInline("italic", children = inline.children.map(::toRaw))
        is UnderlineInline -> RawSourceInline("underline", children = inline.children.map(::toRaw))
        is StrikethroughInline -> RawSourceInline("strike", children = inline.children.map(::toRaw))
        is HighlightInline -> RawSourceInline("highlight", attributes = mapOf("color" to inline.color.orEmpty()), children = inline.children.map(::toRaw))
        is FontFamilyInline -> RawSourceInline("font-family", attributes = mapOf("family" to inline.family), children = inline.children.map(::toRaw))
        is FontSizeInline -> RawSourceInline("font-size", attributes = mapOf("size" to inline.sizeSp.toString()), children = inline.children.map(::toRaw))
        is LinkInline -> RawSourceInline("link", attributes = mapOf("url" to inline.url), children = inline.children.map(::toRaw))
        is CodeInline -> RawSourceInline("code", inline.value)
        else -> RawSourceInline("text", inline.plainText())
    }

    private fun fromRaw(inline: RawSourceInline): InlineNode = when (inline.type) {
        "text" -> InlineText(inline.value)
        "bold" -> BoldInline(inline.children.map(::fromRaw)); "italic" -> ItalicInline(inline.children.map(::fromRaw)); "underline" -> UnderlineInline(inline.children.map(::fromRaw)); "strike" -> StrikethroughInline(inline.children.map(::fromRaw))
        "highlight" -> HighlightInline(inline.attributes["color"]?.takeIf(String::isNotBlank), inline.children.map(::fromRaw))
        "font-family" -> FontFamilyInline(inline.attributes["family"].orEmpty(), inline.children.map(::fromRaw))
        "font-size" -> FontSizeInline(inline.attributes["size"]?.toFloatOrNull()?.coerceIn(1f, 512f) ?: 16f, inline.children.map(::fromRaw))
        "link" -> LinkInline(inline.attributes["url"].orEmpty(), inline.children.map(::fromRaw))
        "code" -> CodeInline(inline.value)
        else -> throw IllegalArgumentException("Unknown Raw Source inline type '${inline.type}'")
    }

    private inline fun <reified T : Enum<T>> enumOr(value: String?, fallback: T): T = enumValues<T>().firstOrNull { it.name == value } ?: fallback
    private fun style(value: StyleAssignment): Map<String, String> = if (value == StyleAssignment.NONE) emptyMap() else mapOf("style" to compactJson.encodeToString(StyleAssignment.serializer(), value))
    private fun parseStyle(raw: RawSourceBlock): StyleAssignment = raw.attributes["style"]?.let { compactJson.decodeFromString(StyleAssignment.serializer(), it) } ?: StyleAssignment.NONE
}

data class RawSourceDraft(
    val original: BlockDocument,
    val source: String,
    val parse: RawSourceParseResult,
    val applyDisabledReason: String? = null,
) {
    val canApply: Boolean get() = parse is RawSourceParseResult.Success && applyDisabledReason == null
}

object RawSourceEditor {
    fun open(document: BlockDocument, limits: ImportSecurityLimits = ImportSecurityLimits()): RawSourceDraft {
        val blocked = RawSourceSerializer.canRepresent(document)
        val source = RawSourceSerializer.serialize(document)
        return RawSourceDraft(document, source, RawSourceParser.parse(source, limits), blocked.firstOrNull())
    }

    /** Validates a draft without touching the live artifact. */
    fun edit(draft: RawSourceDraft, source: String, limits: ImportSecurityLimits = ImportSecurityLimits()): RawSourceDraft =
        draft.copy(source = source, parse = RawSourceParser.parse(source, limits))

    fun materialize(draft: RawSourceDraft): BlockDocument {
        require(draft.canApply) { draft.applyDisabledReason ?: "Raw Source has parse errors" }
        return RawSourceSerializer.toArtifact((draft.parse as RawSourceParseResult.Success).ast)
    }
}
