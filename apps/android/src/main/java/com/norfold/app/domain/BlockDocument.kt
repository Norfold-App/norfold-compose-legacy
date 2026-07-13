package com.norfold.app.domain

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

@Serializable
data class BlockDocument(val blocks: List<DocumentBlock> = listOf(ParagraphBlock())) {
    fun normalized() = copy(blocks = blocks.ifEmpty { listOf(ParagraphBlock()) })
    fun plainText() = blocks.joinToString("\n") { it.plainText() }
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

@Serializable @SerialName("divider")
data class DividerBlock(override val id: String = blockId()) : DocumentBlock { override fun plainText() = "" }

@Serializable
enum class BlockRenderMode { Render, Source }

@Serializable @SerialName("code")
data class CodeBlock(
    override val id: String = blockId(),
    val language: String = "",
    val code: String = "",
    val editorHeightDp: Float = 180f,
    val renderMode: BlockRenderMode = BlockRenderMode.Render,
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
    val renderMode: BlockRenderMode = BlockRenderMode.Render,
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
    val renderMode: BlockRenderMode = BlockRenderMode.Render,
) : DocumentBlock {
    override fun plainText() = vegaLiteSpec
}

@Serializable @SerialName("math")
data class MathBlock(
    override val id: String = blockId(),
    val tex: String = "",
    val display: Boolean = true,
    val editorHeightDp: Float = 140f,
    val renderMode: BlockRenderMode = BlockRenderMode.Render,
) : DocumentBlock {
    override fun plainText() = tex
}

@Serializable @SerialName("mermaid")
data class MermaidBlock(
    override val id: String = blockId(),
    val code: String = "",
    val editorHeightDp: Float = 180f,
    val renderMode: BlockRenderMode = BlockRenderMode.Render,
) : DocumentBlock {
    override fun plainText() = code
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
    val format = Json { classDiscriminator = "kind"; encodeDefaults = true; ignoreUnknownKeys = false }
    fun encode(document: BlockDocument) = format.encodeToString(document.normalized())
    fun decode(json: String) = format.decodeFromString<BlockDocument>(json).normalized()
    fun encodeBlock(block: DocumentBlock) = format.encodeToString(DocumentBlock.serializer(), block)
    fun decodeBlock(json: String) = format.decodeFromString(DocumentBlock.serializer(), json)
}

internal fun List<InlineNode>.plainText() = joinToString("") { it.plainText() }
private fun blockId() = UUID.randomUUID().toString()
