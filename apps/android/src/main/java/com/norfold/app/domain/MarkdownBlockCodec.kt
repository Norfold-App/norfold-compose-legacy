package com.norfold.app.domain

import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.flavours.gfm.GFMElementTypes
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.parser.CancellationToken
import org.intellij.markdown.parser.MarkdownParser

object MarkdownBlockCodec {
    private val parser = MarkdownParser(GFMFlavourDescriptor(), false, CancellationToken.NonCancellable)

    fun import(markdown: String): BlockDocument {
        val normalized = unwrapOuterMarkdownFence(markdown)
        if (normalized.isBlank()) return BlockDocument()
        val root = parser.buildMarkdownTreeFromString(normalized as CharSequence)
        return BlockDocument(root.children.flatMap { mapBlock(it, normalized) }).normalized()
    }

    fun export(document: BlockDocument): String =
        document.normalized().blocks.joinToString("\n\n", transform = ::blockMarkdown).trimEnd()

    private fun mapBlock(node: ASTNode, source: String): List<DocumentBlock> {
        val raw = source.slice(node)
        return when (node.type) {
            MarkdownElementTypes.ATX_1, MarkdownElementTypes.SETEXT_1 -> listOf(HeadingBlock(level = 1, content = inlineContent(node, source).trimBoundaryWhitespace()))
            MarkdownElementTypes.ATX_2, MarkdownElementTypes.SETEXT_2 -> listOf(HeadingBlock(level = 2, content = inlineContent(node, source).trimBoundaryWhitespace()))
            MarkdownElementTypes.ATX_3 -> listOf(HeadingBlock(level = 3, content = inlineContent(node, source).trimBoundaryWhitespace()))
            MarkdownElementTypes.ATX_4 -> listOf(HeadingBlock(level = 4, content = inlineContent(node, source).trimBoundaryWhitespace()))
            MarkdownElementTypes.ATX_5 -> listOf(HeadingBlock(level = 5, content = inlineContent(node, source).trimBoundaryWhitespace()))
            MarkdownElementTypes.ATX_6 -> listOf(HeadingBlock(level = 6, content = inlineContent(node, source).trimBoundaryWhitespace()))
            MarkdownElementTypes.PARAGRAPH -> mapParagraph(node, source)
            MarkdownElementTypes.UNORDERED_LIST -> mapUnorderedList(node, source)
            MarkdownElementTypes.ORDERED_LIST -> listOf(NumberedListBlock(start = orderedStart(raw), items = listItems(node, source)))
            MarkdownElementTypes.BLOCK_QUOTE -> mapQuote(raw)
            MarkdownElementTypes.CODE_FENCE -> mapFence(raw)
            MarkdownElementTypes.CODE_BLOCK -> listOf(CodeBlock(code = raw.trimEnd()))
            GFMElementTypes.TABLE -> listOf(mapTable(raw))
            GFMElementTypes.BLOCK_MATH -> listOf(MathBlock(tex = raw.removeMathFence()))
            else -> when {
                raw.trim() in setOf("---", "***", "___") -> listOf(DividerBlock())
                raw.isBlank() -> emptyList()
                else -> listOf(ParagraphBlock(content = listOf(InlineText(raw.trimEnd()))))
            }
        }
    }

    private fun mapParagraph(node: ASTNode, source: String): List<DocumentBlock> {
        val raw = source.slice(node).trim()
        if (raw.isDisplayMath()) return listOf(MathBlock(tex = raw.removeMathFence(), display = true))
        val image = Regex("^!\\[([^]]*)]\\((\\S+?)(?:\\s+\"[^\"]*\")?\\)$").matchEntire(raw)
        if (image != null) return listOf(ImageBlock(source = image.groupValues[2], caption = image.groupValues[1]))
        if (raw.matches(Regex("https?://\\S+"))) return listOf(EmbedBlock(url = raw))
        return listOf(ParagraphBlock(content = inlineContent(node, source)))
    }

    private fun mapUnorderedList(node: ASTNode, source: String): List<DocumentBlock> {
        val nodes = node.children.filter { it.type == MarkdownElementTypes.LIST_ITEM }
        val todos = nodes.isNotEmpty() && nodes.all { source.slice(it).trimStart().matches(todoPattern) }
        return if (todos) {
            listOf(TodoListBlock(items = nodes.map { item ->
                val raw = source.slice(item).trimStart()
                TodoItem(checked = raw.matches(checkedTodoPattern), content = listItemInline(item, source))
            }))
        } else {
            listOf(BulletListBlock(items = listItems(node, source)))
        }
    }

    private fun listItems(node: ASTNode, source: String): List<ListItem> =
        node.children.filter { it.type == MarkdownElementTypes.LIST_ITEM }.map { item ->
            val children = item.children
                .filter { it.type == MarkdownElementTypes.UNORDERED_LIST || it.type == MarkdownElementTypes.ORDERED_LIST }
                .flatMap { listItems(it, source) }
            ListItem(content = listItemInline(item, source), children = children)
        }

    private fun listItemInline(item: ASTNode, source: String): List<InlineNode> {
        val paragraph = item.children.firstOrNull { it.type == MarkdownElementTypes.PARAGRAPH }
        if (paragraph != null) {
            val parsed = inlineContent(paragraph, source)
            val text = parsed.plainText().replace(Regex("^\\[[ xX]]\\s*"), "")
            return if (text == parsed.plainText()) parsed else listOf(InlineText(text))
        }
        val firstLine = source.slice(item).lineSequence().firstOrNull().orEmpty()
            .replace(Regex("^\\s*(?:[-*+]|\\d+[.)])\\s+"), "")
            .replace(Regex("^\\[[ xX]]\\s*"), "")
        return parseInline(firstLine)
    }

    private fun mapQuote(raw: String): List<DocumentBlock> {
        val inner = raw.lines().joinToString("\n") { it.replace(Regex("^\\s*> ?"), "") }
        val alert = Regex("^\\[!([A-Za-z]+)]\\s*(.*)$").find(inner)
        val childSource = if (alert == null) inner else inner.substringAfter('\n', "")
        val children = import(childSource).blocks
        return if (alert == null) listOf(QuoteBlock(children = children)) else listOf(
            CalloutBlock(
                tone = alert.groupValues[1].lowercase(),
                title = alert.groupValues[2].ifBlank { alert.groupValues[1].lowercase().replaceFirstChar(Char::uppercase) },
                children = children,
            ),
        )
    }

    private fun mapFence(raw: String): List<DocumentBlock> {
        val first = raw.lineSequence().firstOrNull().orEmpty().trim()
        val language = fenceOpening.matchEntire(first)?.groupValues?.get(2)?.trim()?.lowercase().orEmpty()
        val lines = raw.lines()
        val body = lines.drop(1).dropLast(1).joinToString("\n").trimEnd()
        return when (language) {
            "mermaid" -> listOf(MermaidBlock(code = body))
            "math", "latex", "tex" -> listOf(MathBlock(tex = body))
            "chart", "plot", "vega", "vega-lite", "vegalite" -> listOf(ChartBlock(vegaLiteSpec = body))
            "markdown", "md" -> if (first == "```markdown" || first == "```md") import(body).blocks else listOf(CodeBlock(language = language, code = body))
            else -> listOf(classifyFencedBlock(language, body))
        }
    }

    private fun classifyFencedBlock(language: String, body: String): DocumentBlock {
        if (language.isNotBlank()) return CodeBlock(language = language, code = body)
        val trimmed = body.trim()
        return when {
            mermaidSource.matches(trimmed) -> MermaidBlock(code = body)
            looksLikeVegaLite(trimmed) -> ChartBlock(vegaLiteSpec = body)
            texSource.containsMatchIn(trimmed) -> MathBlock(tex = body)
            else -> CodeBlock(language = detectCodeLanguage(trimmed), code = body)
        }
    }

    private fun detectCodeLanguage(source: String): String = when {
        Regex("(?m)^\\s*(?:package\\s+[\\w.]+|(?:data\\s+)?class\\s+\\w+|fun\\s+\\w+|val\\s+\\w+)").containsMatchIn(source) -> "kotlin"
        Regex("(?m)^\\s*(?:public\\s+)?(?:class|interface|record)\\s+\\w+|System\\.out\\.").containsMatchIn(source) -> "java"
        Regex("(?m)^\\s*(?:def|class)\\s+\\w+.*:|^\\s*(?:from\\s+\\S+\\s+)?import\\s+").containsMatchIn(source) -> "python"
        Regex("(?m)^\\s*(?:const|let|var)\\s+\\w+|function\\s+\\w+|=>").containsMatchIn(source) -> "javascript"
        Regex("(?is)^\\s*(?:select|insert|update|delete|create|alter)\\b").containsMatchIn(source) -> "sql"
        Regex("(?s)^\\s*<([A-Za-z][\\w:-]*)(?:\\s|>)").containsMatchIn(source) -> "html"
        Regex("(?m)^\\s*#include\\s*[<\"]|std::").containsMatchIn(source) -> "cpp"
        looksLikeJson(source) -> "json"
        else -> ""
    }

    private fun looksLikeJson(source: String): Boolean = runCatching {
        org.json.JSONTokener(source).nextValue()
        true
    }.getOrDefault(false)

    private fun looksLikeVegaLite(source: String): Boolean {
        if (!looksLikeJson(source)) return false
        return Regex("\"(?:mark|encoding|layer|concat|hconcat|vconcat)\"\\s*:").containsMatchIn(source) ||
            source.contains("vega-lite", ignoreCase = true)
    }

    private fun unwrapOuterMarkdownFence(source: String): String {
        val lines = source.lines()
        val firstContent = lines.indexOfFirst { it.isNotBlank() }
        val lastContent = lines.indexOfLast { it.isNotBlank() }
        if (firstContent < 0 || lastContent <= firstContent) return source
        val opening = fenceOpening.matchEntire(lines[firstContent].trim()) ?: return source
        if (opening.groupValues[2].lowercase() !in setOf("markdown", "md")) return source
        val delimiter = opening.groupValues[1]
        if (lines[lastContent].trim() != delimiter) return source
        return lines.subList(firstContent + 1, lastContent).joinToString("\n")
    }

    private fun mapTable(raw: String): TableBlock {
        val parsed = raw.lines().filter(String::isNotBlank).map(::splitTableRow)
        val rows = parsed.filterIndexed { index, _ -> index != 1 }
        return TableBlock(
            headers = rows.firstOrNull().orEmpty().map { TableCell(parseInline(it)) },
            rows = rows.drop(1).map { row -> row.map { TableCell(parseInline(it)) } },
        )
    }

    private fun splitTableRow(line: String) = line.trim().removePrefix("|").removeSuffix("|").split('|').map(String::trim)
    private fun orderedStart(raw: String) = Regex("^\\s*(\\d+)[.)]").find(raw)?.groupValues?.get(1)?.toIntOrNull() ?: 1

    private fun inlineContent(node: ASTNode, source: String): List<InlineNode> {
        val parsed = node.children.filterNot { it.type in structuralTokens }.flatMap { mapInline(it, source) }
        return mergeText(parsed).ifEmpty { listOf(InlineText(source.slice(node).trim().trimStart('#').trim())) }
    }

    private fun parseInline(value: String): List<InlineNode> {
        if (value.isBlank()) return emptyList()
        val root = parser.parseInline(MarkdownElementTypes.PARAGRAPH, value, 0, value.length)
        return mergeText(root.children.flatMap { mapInline(it, value) })
    }

    private fun mapInline(node: ASTNode, source: String): List<InlineNode> {
        val raw = source.slice(node)
        return when (node.type) {
            MarkdownElementTypes.STRONG -> listOf(BoldInline(inlineContent(node, source)))
            MarkdownElementTypes.EMPH -> listOf(ItalicInline(inlineContent(node, source)))
            GFMElementTypes.STRIKETHROUGH -> listOf(StrikethroughInline(inlineContent(node, source)))
            MarkdownElementTypes.CODE_SPAN -> listOf(CodeInline(raw.trim('`')))
            GFMElementTypes.INLINE_MATH -> listOf(MathInline(raw.removeMathFence()))
            MarkdownElementTypes.INLINE_LINK, MarkdownElementTypes.FULL_REFERENCE_LINK, MarkdownElementTypes.SHORT_REFERENCE_LINK -> {
                val destination = node.descendants().firstOrNull { it.type == MarkdownElementTypes.LINK_DESTINATION }?.let { source.slice(it) }.orEmpty()
                val label = node.descendants().firstOrNull { it.type == MarkdownElementTypes.LINK_TEXT }
                listOf(LinkInline(destination, label?.let { inlineContent(it, source) } ?: listOf(InlineText(raw))))
            }
            MarkdownElementTypes.AUTOLINK -> listOf(LinkInline(raw.trim('<', '>'), listOf(InlineText(raw.trim('<', '>')))))
            MarkdownTokenTypes.TEXT, MarkdownTokenTypes.WHITE_SPACE, MarkdownTokenTypes.EOL, MarkdownTokenTypes.HARD_LINE_BREAK,
            MarkdownTokenTypes.CODE_LINE, MarkdownTokenTypes.ATX_CONTENT, MarkdownTokenTypes.SETEXT_CONTENT -> tokenizePlain(raw)
            else -> if (node.children.isEmpty()) {
                if (node.type in ignoredInlineTokens) emptyList() else tokenizePlain(raw)
            } else node.children.flatMap { mapInline(it, source) }
        }
    }

    private fun tokenizePlain(value: String): List<InlineNode> {
        if (value.isEmpty()) return emptyList()
        val result = mutableListOf<InlineNode>()
        var cursor = 0
        inlineToken.findAll(value).forEach { match ->
            if (match.range.first > cursor) result += InlineText(value.substring(cursor, match.range.first))
            result += if (match.value.startsWith('#')) TagInline(match.value.drop(1)) else MentionInline(match.value.drop(1))
            cursor = match.range.last + 1
        }
        if (cursor < value.length) result += InlineText(value.substring(cursor))
        return result
    }

    private fun blockMarkdown(block: DocumentBlock): String = when (block) {
        is ParagraphBlock -> block.content.markdown()
        is HeadingBlock -> "${"#".repeat(block.level.coerceIn(1, 6))} ${block.content.markdown()}"
        is BulletListBlock -> listMarkdown(block.items, "- ")
        is NumberedListBlock -> block.items.mapIndexed { index, item -> "${block.start + index}. ${item.content.markdown()}${nestedMarkdown(item.children)}" }.joinToString("\n")
        is TodoListBlock -> block.items.joinToString("\n") { "- [${if (it.checked) "x" else " "}] ${it.content.markdown()}" }
        is QuoteBlock -> export(BlockDocument(block.children)).lines().joinToString("\n") { "> $it" }
        is CalloutBlock -> buildString {
            append("> [!").append(block.tone.uppercase()).append("]")
            if (block.title.isNotBlank()) append(' ').append(block.title)
            if (block.children.isNotEmpty()) append('\n').append(export(BlockDocument(block.children)).lines().joinToString("\n") { "> $it" })
        }
        is DividerBlock -> "---"
        is CodeBlock -> "```${block.language}\n${block.code}\n```"
        is TableBlock -> tableMarkdown(block)
        is ImageBlock -> "![${block.caption}](${block.source})"
        is FileBlock -> "[${block.name}](${block.uri})"
        is EmbedBlock -> block.url
        is ChartBlock -> "```vega-lite\n${block.vegaLiteSpec}\n```"
        is MathBlock -> if (block.display) "$$\n${block.tex}\n$$" else "$${block.tex}$"
        is MermaidBlock -> "```mermaid\n${block.code}\n```"
    }

    private fun listMarkdown(items: List<ListItem>, marker: String): String =
        items.joinToString("\n") { marker + it.content.markdown() + nestedMarkdown(it.children) }

    private fun nestedMarkdown(items: List<ListItem>): String =
        if (items.isEmpty()) "" else "\n" + listMarkdown(items, "- ").prependIndent("  ")

    private fun tableMarkdown(table: TableBlock): String {
        val width = maxOf(table.headers.size, table.rows.maxOfOrNull(List<TableCell>::size) ?: 0).coerceAtLeast(1)
        fun row(cells: List<TableCell>) = "| " + (cells + List((width - cells.size).coerceAtLeast(0)) { TableCell() }).joinToString(" | ") { it.content.markdown() } + " |"
        return buildList {
            add(row(table.headers)); add("| " + List(width) { "---" }.joinToString(" | ") + " |")
            table.rows.forEach { add(row(it)) }
        }.joinToString("\n")
    }

    private fun List<InlineNode>.markdown(): String = joinToString("") { inline -> when (inline) {
        is InlineText -> inline.value
        is BoldInline -> "**${inline.children.markdown()}**"
        is ItalicInline -> "*${inline.children.markdown()}*"
        is StrikethroughInline -> "~~${inline.children.markdown()}~~"
        is CodeInline -> "`${inline.value}`"
        is LinkInline -> "[${inline.children.markdown()}](${inline.url})"
        is EmojiInline -> inline.shortcode
        is MathInline -> "$${inline.tex}$"
        is TagInline -> "#${inline.value}"
        is MentionInline -> "@${inline.value}"
    } }

    private fun mergeText(nodes: List<InlineNode>) = nodes.fold(mutableListOf<InlineNode>()) { acc, node ->
        if (node is InlineText && acc.lastOrNull() is InlineText) {
            val previous = acc.removeAt(acc.lastIndex) as InlineText
            acc += InlineText(previous.value + node.value)
        } else acc += node
        acc
    }

    private fun List<InlineNode>.trimBoundaryWhitespace(): List<InlineNode> {
        if (isEmpty()) return this
        val result = toMutableList()
        (result.firstOrNull() as? InlineText)?.let { first -> result[0] = first.copy(value = first.value.trimStart()) }
        (result.lastOrNull() as? InlineText)?.let { last -> result[result.lastIndex] = last.copy(value = last.value.trimEnd().trimEnd('#').trimEnd()) }
        return result.filterNot { it is InlineText && it.value.isEmpty() }
    }

    private fun String.slice(node: ASTNode): String = substring(node.startOffset.coerceIn(0, length), node.endOffset.coerceIn(0, length))
    private fun String.isDisplayMath(): Boolean {
        val value = trim()
        return (value.startsWith("$$") && value.endsWith("$$") && value.length >= 4) ||
            (value.startsWith("\\[") && value.endsWith("\\]") && value.length >= 4)
    }
    private fun String.removeMathFence() = trim()
        .removePrefix("$$").removeSuffix("$$")
        .removePrefix("\\[").removeSuffix("\\]")
        .removePrefix("$").removeSuffix("$")
        .trim()
    private fun ASTNode.descendants(): Sequence<ASTNode> = sequence { children.forEach { child -> yield(child); yieldAll(child.descendants()) } }

    private val todoPattern = Regex("^[-*+]\\s+\\[[ xX]](?:\\s+.*)?", RegexOption.DOT_MATCHES_ALL)
    private val checkedTodoPattern = Regex("^[-*+]\\s+\\[[xX]].*", RegexOption.DOT_MATCHES_ALL)
    private val inlineToken = Regex("(?<![\\w])([#@][\\p{L}\\p{N}_-]+)")
    private val fenceOpening = Regex("^(`{3,}|~{3,})\\s*([A-Za-z0-9_+.-]*)\\s*$")
    private val mermaidSource = Regex("(?is)^\\s*(?:graph|flowchart|sequenceDiagram|classDiagram|stateDiagram(?:-v2)?|erDiagram|gantt|pie|mindmap|timeline|journey|gitGraph)\\b.*")
    private val texSource = Regex("\\\\(?:frac|sum|int|begin|mathbb|vec|tag|newcommand)\\b|(?:\\^|_)\\{[^}]+\\}")
    private val structuralTokens = setOf(MarkdownTokenTypes.ATX_HEADER, MarkdownTokenTypes.SETEXT_1, MarkdownTokenTypes.SETEXT_2)
    private val ignoredInlineTokens = setOf(
        MarkdownTokenTypes.ATX_HEADER, MarkdownTokenTypes.SETEXT_1, MarkdownTokenTypes.SETEXT_2,
        MarkdownTokenTypes.EMPH, MarkdownTokenTypes.BACKTICK, MarkdownTokenTypes.LBRACKET,
        MarkdownTokenTypes.RBRACKET, MarkdownTokenTypes.LPAREN, MarkdownTokenTypes.RPAREN,
    )
}
