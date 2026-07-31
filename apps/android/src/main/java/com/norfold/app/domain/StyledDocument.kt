package com.norfold.app.domain

import com.norfold.app.domain.style.ColorPaint
import com.norfold.app.domain.style.ColorValue
import com.norfold.app.domain.style.DocumentToken
import com.norfold.app.domain.style.StyleAssignment
import com.norfold.app.domain.style.StyleDefinition
import com.norfold.app.domain.style.Stylesheet
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The document's stylesheet, stored as a **top-level block** (roadmap Phase 3 §26).
 *
 * The same reasoning that made a page a block applies here, and for the same three reasons: the repository
 * persists one row per top-level block, the History Engine's operations are top-level-block granular, and
 * recovery snapshots already cover blocks. Making the stylesheet a block therefore inherits durable
 * persistence, atomic undo of a style-definition edit, recovery coverage, and the verbatim unknown-payload
 * round trip — from machinery that is already proven — instead of needing a new table, a new migration, its
 * own undo path, and its own recovery story.
 *
 * It also settles §26's "survive save / reopen / process recreation / undo" requirement structurally rather
 * than by adding four separate code paths.
 *
 * **Why this does not bump [BlockDocumentJson.SCHEMA_VERSION].** It is a new block *kind*, not a
 * reinterpretation of an existing one. A build that does not know `stylesheet` degrades it to
 * [UnknownBlock] and writes the payload back verbatim, so the styles survive a round trip through a build
 * that cannot read them. Bumping the version would instead make every *existing* block unreadable to that
 * build — a strictly worse trade.
 *
 * [plainText] is empty because a stylesheet is not content: it must never appear in search results, exports,
 * word counts, or the plain-text view of a note.
 */
@Serializable
@SerialName("stylesheet")
data class StylesheetBlock(
    override val id: String = STYLESHEET_BLOCK_ID,
    val styles: List<StyleDefinition> = emptyList(),
    val tokens: List<DocumentToken> = emptyList(),
    val chartPalettes: Map<String, List<String>> = emptyMap(),
) : DocumentBlock {
    override fun plainText(): String = ""

    fun toStylesheet() = Stylesheet(styles = styles, tokens = tokens, chartPalettes = chartPalettes)

    companion object {
        /**
         * Fixed id, deliberately: there is exactly one stylesheet per document, and a fixed id makes
         * "find it or create it" a lookup rather than a scan, and makes a duplicate impossible to create by
         * accident (the History Engine rejects a duplicate block id).
         */
        const val STYLESHEET_BLOCK_ID = "document-stylesheet"

        fun of(sheet: Stylesheet) = StylesheetBlock(
            styles = sheet.styles,
            tokens = sheet.tokens,
            chartPalettes = sheet.chartPalettes,
        )
    }
}

/** The document's stylesheet, or the built-in defaults when it has none yet. */
fun BlockDocument.stylesheet(): Stylesheet =
    blocks.filterIsInstance<StylesheetBlock>().firstOrNull()?.toStylesheet() ?: Stylesheet.defaults()

/** True when this document has an explicitly stored stylesheet (rather than falling back to defaults). */
fun BlockDocument.hasStoredStylesheet(): Boolean = blocks.any { it is StylesheetBlock }

/** The index of the stylesheet block, or -1. */
fun BlockDocument.stylesheetIndex(): Int = blocks.indexOfFirst { it is StylesheetBlock }

/**
 * The [StyleAssignment] a block carries, or [StyleAssignment.NONE].
 *
 * Reading through one accessor rather than casting at each call site means a block kind gaining style
 * support later is a one-line change here, and — more importantly — that a block kind *without* style
 * support returns a well-defined empty assignment instead of forcing every caller to handle null.
 */
fun DocumentBlock.styleAssignment(): StyleAssignment = when (this) {
    is ParagraphBlock -> style
    is HeadingBlock -> style
    is QuoteBlock -> style
    is CalloutBlock -> style
    is CodeBlock -> style
    is ShapeBlock -> style
    is BulletListBlock -> style
    is NumberedListBlock -> style
    is TodoListBlock -> style
    else -> StyleAssignment.NONE
}

/** Copy of this block with [assignment]; blocks that do not carry style are returned unchanged. */
fun DocumentBlock.withStyleAssignment(assignment: StyleAssignment): DocumentBlock = when (this) {
    is ParagraphBlock -> copy(style = assignment)
    is HeadingBlock -> copy(style = assignment)
    is QuoteBlock -> copy(style = assignment)
    is CalloutBlock -> copy(style = assignment)
    is CodeBlock -> copy(style = assignment)
    is ShapeBlock -> copy(style = assignment)
    is BulletListBlock -> copy(style = assignment)
    is NumberedListBlock -> copy(style = assignment)
    is TodoListBlock -> copy(style = assignment)
    else -> this
}

/** Whether this block kind can carry a style assignment at all — what the command layer checks. */
fun DocumentBlock.supportsStyle(): Boolean = when (this) {
    is ParagraphBlock, is HeadingBlock, is QuoteBlock, is CalloutBlock, is CodeBlock,
    is ShapeBlock, is BulletListBlock, is NumberedListBlock, is TodoListBlock,
    -> true
    else -> false
}

/**
 * The semantic role of this block — what style it is *by default*.
 *
 * Derived from the block's own type rather than stored, so applying a style can never contradict the
 * structure. This is what §12 protects: a `HeadingBlock` stays a heading, and styling decorates that
 * meaning instead of flattening it into "big bold text".
 */
fun DocumentBlock.semanticStyleRole(): String? = when (this) {
    is ParagraphBlock -> "paragraph"
    is HeadingBlock -> "heading:${level.coerceIn(1, 6)}"
    is QuoteBlock -> "quote"
    is CodeBlock -> "code"
    is CalloutBlock -> "paragraph"
    is BulletListBlock, is NumberedListBlock, is TodoListBlock -> "paragraph"
    else -> null
}

/**
 * The style this block resolves against: its explicit assignment if it has one, else the default for its
 * semantic role.
 *
 * The fallback is what makes styling work on documents written before Phase 3 — every existing paragraph
 * already renders as Body without a migration pass rewriting a single block.
 */
fun DocumentBlock.effectiveStyleAssignment(): StyleAssignment {
    val assignment = styleAssignment()
    if (assignment.styleId != null) return assignment
    val role = semanticStyleRole() ?: return assignment
    val defaultId = Stylesheet.defaultStyleIdFor(role) ?: return assignment
    return assignment.copy(styleId = defaultId)
}

/**
 * Every colour this block references *as content* (spec §6: the Document palette).
 *
 * Deliberately restricted to authored content colours. Chrome, collaborator and signal colours are not
 * reachable from a block, which is what structurally prevents the §6 failure "document colours must not
 * accidentally contain app chrome/collaborator signal colours" — they are not merely filtered out, they
 * were never in scope.
 */
fun DocumentBlock.contentColors(): List<ColorValue> = buildList {
    when (val block = this@contentColors) {
        is ShapeBlock -> {
            addAll(block.fillPaint?.colors() ?: listOf(ColorValue.fromArgb(block.fillColor)))
            addAll(block.strokePaint?.colors() ?: listOf(ColorValue.fromArgb(block.strokeColor)))
        }
        else -> Unit
    }
    // Inline highlight colours are authored content too, and are the colours a writer most often reuses.
    inlineColors().let(::addAll)
}

private fun DocumentBlock.inlineColors(): List<ColorValue> {
    val nodes = when (this) {
        is ParagraphBlock -> content
        is HeadingBlock -> content
        is BulletListBlock -> items.flatMap { it.content }
        is NumberedListBlock -> items.flatMap { it.content }
        is TodoListBlock -> items.flatMap { it.content }
        else -> emptyList()
    }
    return nodes.collectHighlightColors()
}

private fun List<InlineNode>.collectHighlightColors(): List<ColorValue> = buildList {
    for (node in this@collectHighlightColors) {
        when (node) {
            is HighlightInline -> {
                ColorValue.parse(node.color)?.let(::add)
                addAll(node.children.collectHighlightColors())
            }
            is BoldInline -> addAll(node.children.collectHighlightColors())
            is ItalicInline -> addAll(node.children.collectHighlightColors())
            is UnderlineInline -> addAll(node.children.collectHighlightColors())
            is StrikethroughInline -> addAll(node.children.collectHighlightColors())
            is LinkInline -> addAll(node.children.collectHighlightColors())
            is FontFamilyInline -> addAll(node.children.collectHighlightColors())
            is FontSizeInline -> addAll(node.children.collectHighlightColors())
            else -> Unit
        }
    }
}

/**
 * Every content colour in the document, most-used first, deduplicated (roadmap §6, §30).
 *
 * Computed by an explicit walk that a caller **caches** — §30 forbids scanning a large document on every
 * recomposition, and §6 requires the palette to be derived state rather than a stored second copy that
 * could contradict the content. Ordering is by frequency then hex, so it is deterministic: a palette whose
 * order changed between two identical documents would move the swatch a user was reaching for.
 */
fun BlockDocument.documentPalette(limit: Int = 24): List<ColorValue> {
    val counts = LinkedHashMap<ColorValue, Int>()
    walk { block -> block.contentColors().forEach { counts[it] = (counts[it] ?: 0) + 1 } }
    stylesheet().tokenColors().forEach { counts[it] = (counts[it] ?: 0) + 1 }
    return counts.entries
        .sortedWith(compareByDescending<Map.Entry<ColorValue, Int>> { it.value }.thenBy { it.key.toHex() })
        .take(limit.coerceAtLeast(0))
        .map { it.key }
}

/** Every font identity referenced by the document — the Font Browser's "Document fonts" section (§19). */
fun BlockDocument.documentFontIds(): List<String> {
    val sheet = stylesheet()
    val result = LinkedHashSet<String>()
    walk { block ->
        com.norfold.app.domain.style.StyleResolver
            .resolveFont(sheet, block.effectiveStyleAssignment())
            ?.let { result += it.encode() }
        block.inlineFontFamilies().forEach { result += it }
    }
    return result.toList()
}

private fun DocumentBlock.inlineFontFamilies(): List<String> {
    val nodes = when (this) {
        is ParagraphBlock -> content
        is HeadingBlock -> content
        is BulletListBlock -> items.flatMap { it.content }
        is NumberedListBlock -> items.flatMap { it.content }
        is TodoListBlock -> items.flatMap { it.content }
        else -> emptyList()
    }
    return nodes.collectFontFamilies()
}

private fun List<InlineNode>.collectFontFamilies(): List<String> = buildList {
    for (node in this@collectFontFamilies) {
        when (node) {
            is FontFamilyInline -> {
                add(node.family)
                addAll(node.children.collectFontFamilies())
            }
            is BoldInline -> addAll(node.children.collectFontFamilies())
            is ItalicInline -> addAll(node.children.collectFontFamilies())
            is UnderlineInline -> addAll(node.children.collectFontFamilies())
            is StrikethroughInline -> addAll(node.children.collectFontFamilies())
            is HighlightInline -> addAll(node.children.collectFontFamilies())
            is LinkInline -> addAll(node.children.collectFontFamilies())
            is FontSizeInline -> addAll(node.children.collectFontFamilies())
            else -> Unit
        }
    }
}

/** Convenience: the paint a shape actually fills with, honouring the gradient field when present. */
fun ShapeBlock.effectiveFill(): ColorPaint =
    fillPaint ?: ColorPaint.Flat(ColorValue.fromArgb(fillColor))

/** Convenience: the paint a shape strokes with. Strokes stay flat in Phase 3. */
fun ShapeBlock.effectiveStroke(): ColorPaint =
    strokePaint ?: ColorPaint.Flat(ColorValue.fromArgb(strokeColor))
