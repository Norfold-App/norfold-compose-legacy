package com.norfold.app.domain

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.util.UUID

@Serializable
data class BlockDocument(val blocks: List<DocumentBlock> = listOf(ParagraphBlock())) {
    fun normalized() = copy(blocks = normalizeBlocks(blocks).ifEmpty { listOf(ParagraphBlock()) })
    fun plainText() = blocks.joinToString("\n") { it.plainText() }
}

/**
 * Recursively normalizes a block list: fixes container weight lengths to match child counts and
 * drops containers that ended up empty. Runs on every encode/decode and after each session mutation,
 * so it must be structurally conservative — it never unwraps intentional single-child containers.
 */
internal fun normalizeBlocks(blocks: List<DocumentBlock>): List<DocumentBlock> =
    blocks.mapNotNull { block ->
        if (block is ContainerBlock) {
            val kids = normalizeBlocks(block.children)
            if (kids.isEmpty()) return@mapNotNull null
            val weights = if (block.weights.size == kids.size) block.weights
                else List(kids.size) { block.weights.getOrElse(it) { 1f } }
            block.copy(children = kids, weights = weights)
        } else block
    }

@Serializable
sealed interface DocumentBlock {
    val id: String
    fun plainText(): String
}

@Serializable @SerialName("paragraph")
data class ParagraphBlock(override val id: String = blockId(), val content: List<InlineNode> = emptyList()) : DocumentBlock {
    override fun plainText() = content.plainText()
}

@Serializable @SerialName("heading")
data class HeadingBlock(override val id: String = blockId(), val level: Int = 1, val content: List<InlineNode> = emptyList()) : DocumentBlock {
    override fun plainText() = content.plainText()
}

@Serializable
data class ListItem(val id: String = blockId(), val content: List<InlineNode> = emptyList(), val children: List<ListItem> = emptyList()) {
    fun plainText(): String = buildList {
        add(content.plainText())
        children.mapTo(this) { it.plainText() }
    }.filter(String::isNotBlank).joinToString("\n")
}

@Serializable @SerialName("bullet_list")
data class BulletListBlock(override val id: String = blockId(), val items: List<ListItem> = emptyList()) : DocumentBlock {
    override fun plainText() = items.joinToString("\n") { it.plainText() }
}

@Serializable @SerialName("numbered_list")
data class NumberedListBlock(override val id: String = blockId(), val start: Int = 1, val items: List<ListItem> = emptyList()) : DocumentBlock {
    override fun plainText() = items.joinToString("\n") { it.plainText() }
}

@Serializable
data class TodoItem(val id: String = blockId(), val checked: Boolean = false, val content: List<InlineNode> = emptyList())

@Serializable @SerialName("todo_list")
data class TodoListBlock(override val id: String = blockId(), val items: List<TodoItem> = emptyList()) : DocumentBlock {
    override fun plainText() = items.joinToString("\n") { it.content.plainText() }
}

@Serializable @SerialName("quote")
data class QuoteBlock(override val id: String = blockId(), val children: List<DocumentBlock> = emptyList()) : DocumentBlock {
    override fun plainText() = children.joinToString("\n") { it.plainText() }
}

@Serializable @SerialName("callout")
data class CalloutBlock(
    override val id: String = blockId(),
    val tone: String = "note",
    val title: String = "Note",
    val children: List<DocumentBlock> = emptyList(),
) : DocumentBlock {
    override fun plainText() = listOf(title, children.joinToString("\n") { it.plainText() }).filter(String::isNotBlank).joinToString("\n")
}

@Serializable
enum class ContainerAxis { Row, Column }

/**
 * Generic recursive layout container. Holds an ordered list of child blocks laid out along [axis]
 * (Row = side-by-side columns, Column = stacked). Children may themselves be [ContainerBlock]s, giving
 * arbitrary nesting. [weights] gives per-child flex weight for Row layouts (empty = equal). This is the
 * structural backbone for multi-column pages and future PDF/print export.
 */
@Serializable @SerialName("container")
data class ContainerBlock(
    override val id: String = blockId(),
    val axis: ContainerAxis = ContainerAxis.Column,
    val children: List<DocumentBlock> = emptyList(),
    val weights: List<Float> = emptyList(),
) : DocumentBlock {
    override fun plainText() = children.joinToString("\n") { it.plainText() }
}

@Serializable @SerialName("divider")
data class DividerBlock(override val id: String = blockId()) : DocumentBlock { override fun plainText() = "" }

@Serializable @SerialName("code")
data class CodeBlock(
    override val id: String = blockId(),
    val language: String = "",
    val code: String = "",
    val editorHeightDp: Float = 180f,
) : DocumentBlock {
    override fun plainText() = code
}

@Serializable data class TableCell(val content: List<InlineNode> = emptyList())

@Serializable enum class TableAlignment { Start, Center, End }

@Serializable @SerialName("table")
data class TableBlock(
    override val id: String = blockId(),
    val headers: List<TableCell> = emptyList(),
    val rows: List<List<TableCell>> = emptyList(),
    val columnWidthsDp: List<Float> = emptyList(),
    val columnAlignments: List<TableAlignment> = emptyList(),
) : DocumentBlock {
    override fun plainText() = (listOf(headers) + rows).joinToString("\n") { row -> row.joinToString("\t") { it.content.plainText() } }
}

@Serializable enum class ImageLayout { Fit, Wide, Original }

@Serializable @SerialName("image")
data class ImageBlock(
    override val id: String = blockId(),
    val source: String = "",
    val caption: String = "",
    val layout: ImageLayout = ImageLayout.Fit,
    val displayHeightDp: Float = 220f,
) : DocumentBlock { override fun plainText() = caption }

@Serializable @SerialName("file")
data class FileBlock(
    override val id: String = blockId(),
    val name: String = "",
    val mimeType: String = "application/octet-stream",
    val sizeBytes: Long = 0,
    val uri: String = "",
) : DocumentBlock { override fun plainText() = name }

@Serializable
data class EmbedMetadata(val title: String = "", val description: String = "", val faviconPath: String? = null)

@Serializable @SerialName("embed")
data class EmbedBlock(
    override val id: String = blockId(),
    val url: String = "",
    val metadata: EmbedMetadata = EmbedMetadata(),
    val displayHeightDp: Float = 112f,
) : DocumentBlock {
    override fun plainText() = listOf(metadata.title, metadata.description, url).filter(String::isNotBlank).joinToString(" ")
}

@Serializable @SerialName("chart")
data class ChartBlock(
    override val id: String = blockId(),
    val vegaLiteSpec: String = "{}",
    val editorHeightDp: Float = 180f,
) : DocumentBlock {
    override fun plainText() = vegaLiteSpec
}

@Serializable @SerialName("math")
data class MathBlock(
    override val id: String = blockId(),
    val tex: String = "",
    val display: Boolean = true,
    val editorHeightDp: Float = 140f,
) : DocumentBlock {
    override fun plainText() = tex
}

@Serializable @SerialName("mermaid")
data class MermaidBlock(
    override val id: String = blockId(),
    val code: String = "",
    val editorHeightDp: Float = 180f,
) : DocumentBlock {
    override fun plainText() = code
}

/**
 * A block whose stored payload this app version couldn't decode — a newer schema version, an
 * unknown kind, or corrupt JSON. [rawJson] holds the exact stored payload; saving writes it back
 * verbatim, so unrecognized content is never dropped, and a future version that understands the
 * payload decodes it back into a real block.
 */
@Serializable @SerialName("unknown")
data class UnknownBlock(override val id: String = blockId(), val rawJson: String = "") : DocumentBlock {
    override fun plainText() = ""
}

@Serializable
sealed interface InlineNode { fun plainText(): String }

@Serializable @SerialName("text") data class InlineText(val value: String) : InlineNode { override fun plainText() = value }
@Serializable @SerialName("bold") data class BoldInline(val children: List<InlineNode>) : InlineNode { override fun plainText() = children.plainText() }
@Serializable @SerialName("italic") data class ItalicInline(val children: List<InlineNode>) : InlineNode { override fun plainText() = children.plainText() }
@Serializable @SerialName("strike") data class StrikethroughInline(val children: List<InlineNode>) : InlineNode { override fun plainText() = children.plainText() }
@Serializable @SerialName("code") data class CodeInline(val value: String) : InlineNode { override fun plainText() = value }
@Serializable @SerialName("link") data class LinkInline(val url: String, val children: List<InlineNode>) : InlineNode { override fun plainText() = children.plainText() }
@Serializable @SerialName("emoji") data class EmojiInline(val shortcode: String, val unicode: String) : InlineNode { override fun plainText() = unicode }
@Serializable @SerialName("math") data class MathInline(val tex: String) : InlineNode { override fun plainText() = tex }
@Serializable @SerialName("tag") data class TagInline(val value: String) : InlineNode { override fun plainText() = "#$value" }
@Serializable @SerialName("mention") data class MentionInline(val value: String) : InlineNode { override fun plainText() = "@$value" }

object BlockDocumentJson {
    /** Version of the per-block payload envelope written by this app. Bump on breaking block-schema changes. */
    const val SCHEMA_VERSION = 1

    val format = Json { classDiscriminator = "kind"; encodeDefaults = true; ignoreUnknownKeys = false }
    fun encode(document: BlockDocument) = format.encodeToString(document.normalized())
    fun decode(json: String) = format.decodeFromString<BlockDocument>(json).normalized()

    /** Stored payload shape: `{"v": N, "block": {...}}`. [UnknownBlock] writes its original payload back verbatim. */
    fun encodeBlock(block: DocumentBlock): String {
        if (block is UnknownBlock && block.rawJson.isNotBlank()) return block.rawJson
        return buildJsonObject {
            put("v", SCHEMA_VERSION)
            put("block", format.encodeToJsonElement(DocumentBlock.serializer(), block))
        }.toString()
    }

    /** Never throws: anything undecodable comes back as an [UnknownBlock] instead of being dropped. */
    fun decodeBlock(json: String): DocumentBlock = decodeBlock(json, recover = true)

    private fun decodeBlock(payload: String, recover: Boolean): DocumentBlock {
        val root = runCatching { format.parseToJsonElement(payload) }.getOrNull() as? JsonObject
            ?: return UnknownBlock(rawJson = payload)
        val enveloped = root["v"] != null && root["block"] is JsonObject
        // Pre-envelope payloads (v0) are the bare block object.
        val version = if (enveloped) (root["v"] as? JsonPrimitive)?.intOrNull ?: Int.MAX_VALUE else 0
        val inner = if (enveloped) root.getValue("block").jsonObject else root
        if (version > SCHEMA_VERSION) return UnknownBlock(id = inner.blockIdOrNew(), rawJson = payload)
        val block = runCatching { format.decodeFromJsonElement(DocumentBlock.serializer(), inner) }.getOrNull()
            ?: return UnknownBlock(id = inner.blockIdOrNew(), rawJson = payload)
        // A persisted unknown wrapper may become decodable once the app learns its kind — try once.
        if (block is UnknownBlock && recover && block.rawJson.isNotBlank() && block.rawJson != payload) {
            val recovered = decodeBlock(block.rawJson, recover = false)
            if (recovered !is UnknownBlock) return recovered
        }
        return block
    }

    private fun JsonObject.blockIdOrNew() = (this["id"] as? JsonPrimitive)?.contentOrNull ?: blockId()

    /**
     * Whole-document payload for backup/sync: a JSON array of per-block envelope payloads. Block
     * ids travel exactly as stored, so freeform-layout keys stay valid across a round trip.
     */
    fun encodeDocumentPayload(document: BlockDocument): String =
        JsonArray(document.normalized().blocks.map { format.parseToJsonElement(encodeBlock(it)) }).toString()

    /** Never throws; undecodable content degrades to [UnknownBlock]s rather than being dropped. */
    fun decodeDocumentPayload(json: String): BlockDocument {
        if (json.isBlank()) return BlockDocument().normalized()
        val array = runCatching { format.parseToJsonElement(json) }.getOrNull() as? JsonArray
            ?: return BlockDocument(listOf(UnknownBlock(rawJson = json))).normalized()
        return BlockDocument(array.map { decodeBlock(it.toString()) }).normalized()
    }

    /**
     * Copy of [block] under [newId], rewriting the id inside the preserved payload too so the new
     * identity survives the next decode (needed when duplicating or re-identifying unknown blocks).
     */
    fun reidentify(block: UnknownBlock, newId: String = blockId()): UnknownBlock {
        val root = runCatching { format.parseToJsonElement(block.rawJson) }.getOrNull() as? JsonObject
            ?: return block.copy(id = newId)
        val updated = if (root["v"] != null && root["block"] is JsonObject) {
            JsonObject(root + ("block" to JsonObject(root.getValue("block").jsonObject + ("id" to JsonPrimitive(newId)))))
        } else {
            JsonObject(root + ("id" to JsonPrimitive(newId)))
        }
        return block.copy(id = newId, rawJson = updated.toString())
    }
}

internal fun List<InlineNode>.plainText() = joinToString("") { it.plainText() }
private fun blockId() = UUID.randomUUID().toString()
