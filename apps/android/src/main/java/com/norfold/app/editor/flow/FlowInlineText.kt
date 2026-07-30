package com.norfold.app.editor.flow

import com.norfold.app.domain.BoldInline
import com.norfold.app.domain.BulletListBlock
import com.norfold.app.domain.CodeInline
import com.norfold.app.domain.DocumentBlock
import com.norfold.app.domain.FontFamilyInline
import com.norfold.app.domain.FontSizeInline
import com.norfold.app.domain.HeadingBlock
import com.norfold.app.domain.HighlightInline
import com.norfold.app.domain.InlineNode
import com.norfold.app.domain.InlineText
import com.norfold.app.domain.ItalicInline
import com.norfold.app.domain.LinkInline
import com.norfold.app.domain.MathInline
import com.norfold.app.domain.NumberedListBlock
import com.norfold.app.domain.ParagraphBlock
import com.norfold.app.domain.QuoteBlock
import com.norfold.app.domain.StrikethroughInline
import com.norfold.app.domain.TodoListBlock
import com.norfold.app.domain.UnderlineInline
import com.norfold.app.domain.plainText

/**
 * The inline attributes one run of text carries. Toolkit-independent on purpose: the renderer maps this to a
 * `SpanStyle`, and a unit test can assert it without Compose.
 */
data class FlowInlineStyle(
    val bold: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false,
    val strikethrough: Boolean = false,
    val code: Boolean = false,
    val math: Boolean = false,
    val link: String? = null,
    val highlight: String? = null,
    val fontFamily: String? = null,
    val fontSizeSp: Float? = null,
) {
    /** True when this run needs no span at all, so the renderer can skip it. */
    val isPlain: Boolean
        get() = !bold && !italic && !underline && !strikethrough && !code && !math &&
            link == null && highlight == null && fontFamily == null && fontSizeSp == null
}

/** One styled run, in plain-text offsets of the block's flattened text: `[start, end)`. */
data class FlowInlineRun(val start: Int, val end: Int, val style: FlowInlineStyle)

/** One editable item of a list-bearing block, whichever concrete item type the block actually holds. */
data class FlowItemText(val id: String, val content: List<InlineNode>)

/**
 * Read access to the inline content the Flow surface actually edits, plus the caret questions the surface
 * cannot answer for itself (roadmap Phase 1 deliverable 3; correction items 6 and 15).
 *
 * Two callers need this and neither should own it:
 *
 *  - the **slash resolver** needs `literalInline` — whether a typed `/` sits inside a span where a slash is
 *    content. A plain string cannot say; only the inline nodes can, and guessing from text would be wrong.
 *  - the **renderer** needs the nodes themselves, so existing bold/italic/link/code content is *shown* as
 *    styled text rather than flattened to `plainText()` and made to look destroyed.
 *
 * Pure JVM and read-only: it never mutates a document, so it is unit-testable without Compose and cannot
 * become a second, divergent editing path beside [com.norfold.app.domain.BlockEditorSession].
 */
object FlowInlineText {

    /**
     * The inline nodes the surface edits for [block] (and [itemId] when the caret is in a list item), or null
     * when this block's text is not inline-structured — [com.norfold.app.domain.CodeBlock] holds a plain
     * string, and containers hold blocks rather than text.
     *
     * Mirrors `BlockEditorSession`'s own private `editableInline`, deliberately: the surface must render and
     * probe exactly the content the mutation vocabulary would edit, or the caret arithmetic would disagree.
     */
    fun nodesOf(block: DocumentBlock, itemId: String? = null): List<InlineNode>? {
        if (itemId != null) return itemsOf(block)?.firstOrNull { it.id == itemId }?.content
        return when (block) {
            is ParagraphBlock -> block.content
            is HeadingBlock -> block.content
            is QuoteBlock -> when (val child = block.children.firstOrNull()) {
                is ParagraphBlock -> child.content
                is HeadingBlock -> child.content
                else -> null
            }
            else -> null
        }
    }

    /**
     * The editable items of [block], or null when it is not a list-bearing block.
     *
     * Bullet and numbered lists hold `ListItem`; a to-do list holds `TodoItem`. They share no supertype, so
     * they are projected to a common shape here rather than each caller having to know both.
     */
    fun itemsOf(block: DocumentBlock): List<FlowItemText>? = when (block) {
        is BulletListBlock -> block.items.map { FlowItemText(it.id, it.content) }
        is NumberedListBlock -> block.items.map { FlowItemText(it.id, it.content) }
        is TodoListBlock -> block.items.map { FlowItemText(it.id, it.content) }
        else -> null
    }

    /**
     * The styled runs of [nodes], flattened to plain-text offsets.
     *
     * Offsets index the same string the surface renders and the caret arithmetic uses, so a run can be turned
     * into a span without any offset mapping — which is what lets existing rich content be *shown* while the
     * field itself still edits plain text (correction item 15).
     */
    fun runsOf(nodes: List<InlineNode>): List<FlowInlineRun> {
        val runs = mutableListOf<FlowInlineRun>()
        var cursor = 0
        fun walk(list: List<InlineNode>, style: FlowInlineStyle) {
            for (node in list) {
                when (node) {
                    is InlineText -> {
                        if (node.value.isNotEmpty()) {
                            runs += FlowInlineRun(cursor, cursor + node.value.length, style)
                            cursor += node.value.length
                        }
                    }
                    is BoldInline -> walk(node.children, style.copy(bold = true))
                    is ItalicInline -> walk(node.children, style.copy(italic = true))
                    is UnderlineInline -> walk(node.children, style.copy(underline = true))
                    is StrikethroughInline -> walk(node.children, style.copy(strikethrough = true))
                    is HighlightInline -> walk(node.children, style.copy(highlight = node.color ?: ""))
                    is FontFamilyInline -> walk(node.children, style.copy(fontFamily = node.family))
                    is FontSizeInline -> walk(node.children, style.copy(fontSizeSp = node.sizeSp))
                    is LinkInline -> walk(node.children, style.copy(link = node.url))
                    else -> {
                        val text = node.plainText()
                        if (text.isNotEmpty()) {
                            val leaf = when (node) {
                                is CodeInline -> style.copy(code = true)
                                is MathInline -> style.copy(math = true)
                                else -> style
                            }
                            runs += FlowInlineRun(cursor, cursor + text.length, leaf)
                            cursor += text.length
                        }
                    }
                }
            }
        }
        walk(nodes, FlowInlineStyle())
        return runs
    }

    /**
     * True when [offset] in the text of [block]/[itemId] sits inside an inline span where `/` is content —
     * inline code or inline math.
     *
     * The probe is the character *before* the caret, because that is the span the next keystroke joins: a
     * caret parked immediately after `` `a/b` `` is still typing inside that code span. At offset 0 there is
     * no preceding character, so nothing is literal.
     */
    fun literalInlineAt(block: DocumentBlock, itemId: String?, offset: Int): Boolean {
        val nodes = nodesOf(block, itemId) ?: return false
        if (offset <= 0) return false
        val probe = offset - 1
        var cursor = 0
        for (node in nodes) {
            val length = node.plainText().length
            if (probe < cursor + length) return node.isLiteralSlashSpan()
            cursor += length
        }
        return false
    }

    /**
     * Whether a `/` typed inside this node is content rather than a menu trigger.
     *
     * Only the spans whose payload is *verbatim* qualify. Bold or a link contains ordinary prose, so a slash
     * there should still open the menu; inline code and inline math do not, so it must not.
     */
    private fun InlineNode.isLiteralSlashSpan(): Boolean = when (this) {
        is CodeInline -> true
        is MathInline -> true
        else -> false
    }
}
