package com.norfold.app.editor.flow

import com.norfold.app.domain.BulletListBlock
import com.norfold.app.domain.CalloutBlock
import com.norfold.app.domain.CodeBlock
import com.norfold.app.domain.DividerBlock
import com.norfold.app.domain.DocumentBlock
import com.norfold.app.domain.HeadingBlock
import com.norfold.app.domain.InlineNode
import com.norfold.app.domain.InlineText
import com.norfold.app.domain.ListItem
import com.norfold.app.domain.MathBlock
import com.norfold.app.domain.MermaidBlock
import com.norfold.app.domain.NumberedListBlock
import com.norfold.app.domain.ParagraphBlock
import com.norfold.app.domain.QuoteBlock
import com.norfold.app.domain.TableBlock
import com.norfold.app.domain.TableCell
import com.norfold.app.domain.TodoItem
import com.norfold.app.domain.TodoListBlock
import com.norfold.app.domain.plainText

/**
 * The Flow block kinds a user can *insert* or *convert to* by name (roadmap Phase 1 deliverables 2–4).
 *
 * This is the host-independent vocabulary the `+` insert surface, the slash resolver, the block menu, the
 * keyboard map, and the accessibility tree all share. It replaces the legacy host's private
 * `TextBlockTarget` enum, which lived inside the 5,636-line composable and was reachable only from two
 * pieces of UI — the exact "one command, defined once, reachable everywhere" failure the spec rejects (§4.1).
 *
 * A kind knows three things and nothing else — it is pure data:
 *  - [create]: how to make an empty block of this kind (drives insertion);
 *  - [convert]: how to reshape an existing block into this kind while preserving its id and text
 *    (drives conversion; id preservation is what keeps History inverses and Room rows stable);
 *  - [matches]: whether a block already *is* this kind (drives ACTIVE/MIXED command state, §4.2/§4.11).
 *
 * Kinds whose creation needs user-supplied content (image, file, embed, chart, shape) are deliberately
 * absent: they are picker-backed commands owned by later phases, and listing them here as "insertable"
 * would promise a surface Phase 1 does not build.
 */
enum class FlowBlockKind(
    val label: String,
    /** Alternate names for slash / command-explorer matching (spec §4.4). */
    val aliases: List<String>,
    /**
     * Whether an existing block may be *converted* into this kind. Conversion is text-preserving, so it is
     * offered only for kinds that can carry an arbitrary run of inline content (or deliberately discard it,
     * as [DIVIDER] does). Structural containers such as [TABLE] are insert-only.
     */
    val convertible: Boolean,
) {
    PARAGRAPH("Body text", listOf("paragraph", "text", "body", "p"), convertible = true),
    HEADING_1("Heading 1", listOf("h1", "heading1", "title"), convertible = true),
    HEADING_2("Heading 2", listOf("h2", "heading2", "subtitle"), convertible = true),
    HEADING_3("Heading 3", listOf("h3", "heading3"), convertible = true),
    HEADING_4("Heading 4", listOf("h4", "heading4"), convertible = true),
    HEADING_5("Heading 5", listOf("h5", "heading5"), convertible = true),
    HEADING_6("Heading 6", listOf("h6", "heading6"), convertible = true),
    BULLET_LIST("Bulleted list", listOf("bullet", "ul", "list", "unordered"), convertible = true),
    NUMBERED_LIST("Numbered list", listOf("numbered", "ol", "ordered"), convertible = true),
    TODO_LIST("Checklist", listOf("todo", "task", "checkbox", "checklist"), convertible = true),
    QUOTE("Quote", listOf("quote", "blockquote", "cite"), convertible = true),
    CODE("Code block", listOf("code", "snippet", "pre"), convertible = true),
    DIVIDER("Divider", listOf("divider", "hr", "rule", "separator"), convertible = true),
    CALLOUT("Callout", listOf("callout", "note", "aside", "toggle"), convertible = false),
    TABLE("Table", listOf("table", "grid"), convertible = false),
    MATH("Equation", listOf("math", "equation", "latex", "tex"), convertible = false),
    MERMAID("Diagram", listOf("mermaid", "diagram", "flowchart"), convertible = false),
    ;

    /** A new, empty block of this kind. Fresh id — the caller inserts it. */
    fun create(): DocumentBlock = when (this) {
        PARAGRAPH -> ParagraphBlock()
        HEADING_1 -> HeadingBlock(level = 1)
        HEADING_2 -> HeadingBlock(level = 2)
        HEADING_3 -> HeadingBlock(level = 3)
        HEADING_4 -> HeadingBlock(level = 4)
        HEADING_5 -> HeadingBlock(level = 5)
        HEADING_6 -> HeadingBlock(level = 6)
        BULLET_LIST -> BulletListBlock(items = listOf(ListItem()))
        NUMBERED_LIST -> NumberedListBlock(items = listOf(ListItem()))
        TODO_LIST -> TodoListBlock(items = listOf(TodoItem()))
        QUOTE -> QuoteBlock(children = listOf(ParagraphBlock()))
        CODE -> CodeBlock()
        DIVIDER -> DividerBlock()
        CALLOUT -> CalloutBlock(children = listOf(ParagraphBlock()))
        TABLE -> TableBlock(
            headers = listOf(TableCell(), TableCell()),
            rows = listOf(listOf(TableCell(), TableCell())),
        )
        MATH -> MathBlock()
        MERMAID -> MermaidBlock()
    }

    /** True when [block] is already of this kind — the source of ACTIVE state on a convert command. */
    fun matches(block: DocumentBlock): Boolean = when (this) {
        PARAGRAPH -> block is ParagraphBlock
        HEADING_1 -> block is HeadingBlock && block.level == 1
        HEADING_2 -> block is HeadingBlock && block.level == 2
        HEADING_3 -> block is HeadingBlock && block.level == 3
        HEADING_4 -> block is HeadingBlock && block.level == 4
        HEADING_5 -> block is HeadingBlock && block.level == 5
        HEADING_6 -> block is HeadingBlock && block.level == 6
        BULLET_LIST -> block is BulletListBlock
        NUMBERED_LIST -> block is NumberedListBlock
        TODO_LIST -> block is TodoListBlock
        QUOTE -> block is QuoteBlock
        CODE -> block is CodeBlock
        DIVIDER -> block is DividerBlock
        CALLOUT -> block is CalloutBlock
        TABLE -> block is TableBlock
        MATH -> block is MathBlock
        MERMAID -> block is MermaidBlock
    }

    /**
     * [block] reshaped into this kind, **keeping its id**. Text is carried across wherever both kinds can
     * hold it; list item ids survive list↔list conversions so a round-trip does not churn identity.
     * Returns [block] unchanged when this kind is not [convertible] or already matches.
     */
    fun convert(block: DocumentBlock): DocumentBlock {
        if (!convertible || matches(block)) return block
        val inline = block.flowInlineContent()
        val listItems = block.flowListItems(inline)
        return when (this) {
            PARAGRAPH -> ParagraphBlock(id = block.id, content = inline)
            HEADING_1 -> HeadingBlock(id = block.id, level = 1, content = inline)
            HEADING_2 -> HeadingBlock(id = block.id, level = 2, content = inline)
            HEADING_3 -> HeadingBlock(id = block.id, level = 3, content = inline)
            HEADING_4 -> HeadingBlock(id = block.id, level = 4, content = inline)
            HEADING_5 -> HeadingBlock(id = block.id, level = 5, content = inline)
            HEADING_6 -> HeadingBlock(id = block.id, level = 6, content = inline)
            BULLET_LIST -> BulletListBlock(id = block.id, items = listItems)
            NUMBERED_LIST -> NumberedListBlock(id = block.id, items = listItems)
            TODO_LIST -> TodoListBlock(
                id = block.id,
                items = when (block) {
                    is TodoListBlock -> block.items
                    else -> listItems.map { TodoItem(id = it.id, content = it.content) }
                },
            )
            QUOTE -> QuoteBlock(id = block.id, children = listOf(ParagraphBlock(content = inline)))
            CODE -> CodeBlock(id = block.id, code = inline.plainText())
            // A divider carries no content by definition; the conversion is intentionally lossy and is
            // undoable as one transaction like any other command.
            DIVIDER -> DividerBlock(id = block.id)
            CALLOUT, TABLE, MATH, MERMAID -> block
        }
    }

    companion object {
        /** The kind [block] currently is, or null for kinds outside this vocabulary (image, embed, …). */
        fun of(block: DocumentBlock): FlowBlockKind? = entries.firstOrNull { it.matches(block) }

        /** Kinds offered by the `+` insert surface and the slash resolver, in menu order. */
        val insertable: List<FlowBlockKind> = entries.toList()

        /** Kinds offered by the "turn into" surface, in menu order. */
        val convertTargets: List<FlowBlockKind> = entries.filter { it.convertible }
    }
}

/**
 * The inline run a block contributes to a conversion. Multi-line sources (lists, code) flatten to
 * newline-joined inline text, which is what the legacy host did and what users expect from "turn into".
 */
private fun DocumentBlock.flowInlineContent(): List<InlineNode> = when (this) {
    is ParagraphBlock -> content
    is HeadingBlock -> content
    is BulletListBlock -> items.map { it.content }.joinInlineLines()
    is NumberedListBlock -> items.map { it.content }.joinInlineLines()
    is TodoListBlock -> items.map { it.content }.joinInlineLines()
    is QuoteBlock -> when (val first = children.firstOrNull()) {
        is ParagraphBlock -> first.content
        is HeadingBlock -> first.content
        else -> listOf(InlineText(plainText())).dropEmpty()
    }
    is CodeBlock -> listOf(InlineText(code)).dropEmpty()
    is DividerBlock -> emptyList()
    else -> listOf(InlineText(plainText())).dropEmpty()
}

/** List items a block contributes to a list conversion, preserving item ids across list↔list moves. */
private fun DocumentBlock.flowListItems(inline: List<InlineNode>): List<ListItem> = when (this) {
    is BulletListBlock -> items
    is NumberedListBlock -> items
    is TodoListBlock -> items.map { ListItem(id = it.id, content = it.content) }
    else -> listOf(ListItem(content = inline))
}.ifEmpty { listOf(ListItem()) }

private fun List<List<InlineNode>>.joinInlineLines(): List<InlineNode> = buildList {
    this@joinInlineLines.forEachIndexed { index, line ->
        if (index > 0) add(InlineText("\n"))
        addAll(line)
    }
}

private fun List<InlineNode>.dropEmpty(): List<InlineNode> = filterNot { it is InlineText && it.value.isEmpty() }
