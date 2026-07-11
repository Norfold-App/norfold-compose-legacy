package com.norfold.app.domain

sealed interface MarkdownBlock {
    data class Heading(val level: Int, val text: String) : MarkdownBlock
    data class Paragraph(val text: String) : MarkdownBlock
    data class ListItem(val text: String, val checked: Boolean? = null) : MarkdownBlock
    data class Quote(val text: String) : MarkdownBlock
    data class Code(val code: String) : MarkdownBlock
    data object Rule : MarkdownBlock
}

object MarkdownExporter {
    fun markdown(document: NoteDocument): String = document.markdown

    fun html(document: NoteDocument): String = buildString {
        append("<!doctype html><html><head><meta charset=\"utf-8\">")
        append("<title>").append(escape(document.title)).append("</title>")
        append("<style>")
        append("body{font:16px/1.6 system-ui,sans-serif;max-width:780px;margin:40px auto;padding:0 20px;color:#181612}")
        append("pre{background:#181612;color:#fff8e8;padding:16px;border-radius:12px;overflow:auto}")
        append("blockquote{border-left:4px solid #8b5cf6;margin-left:0;padding-left:14px;color:#514838}")
        append("code{background:#f4ecd7;padding:2px 5px;border-radius:5px}")
        append("</style></head><body>")
        append("<h1>").append(escape(document.title)).append("</h1>")
        append("<p><small>").append(escape(document.folder)).append(" · ").append(escape(document.tag)).append("</small></p>")
        append(blocksToHtml(blocks(document.markdown)))
        append("</body></html>")
    }

    fun blocks(markdown: String): List<MarkdownBlock> {
        val blocks = mutableListOf<MarkdownBlock>()
        val code = StringBuilder()
        val paragraph = StringBuilder()
        var inCode = false
        fun flushParagraph() {
            val text = paragraph.toString().trim()
            if (text.isNotBlank()) blocks += MarkdownBlock.Paragraph(text)
            paragraph.clear()
        }
        for (line in markdown.lines()) {
            val trimmed = line.trimEnd()
            if (trimmed.startsWith("```")) {
                if (inCode) {
                    blocks += MarkdownBlock.Code(code.toString().trimEnd())
                    code.clear()
                } else {
                    flushParagraph()
                }
                inCode = !inCode
                continue
            }
            if (inCode) {
                code.appendLine(line)
                continue
            }
            when {
                trimmed.isBlank() -> flushParagraph()
                trimmed.startsWith("|") -> {
                    if (paragraph.isNotEmpty()) paragraph.appendLine()
                    paragraph.append(trimmed)
                }
                trimmed == "---" || trimmed == "***" -> {
                    flushParagraph()
                    blocks += MarkdownBlock.Rule
                }
                trimmed.startsWith("### ") -> {
                    flushParagraph()
                    blocks += MarkdownBlock.Heading(3, trimmed.drop(4))
                }
                trimmed.startsWith("## ") -> {
                    flushParagraph()
                    blocks += MarkdownBlock.Heading(2, trimmed.drop(3))
                }
                trimmed.startsWith("# ") -> {
                    flushParagraph()
                    blocks += MarkdownBlock.Heading(1, trimmed.drop(2))
                }
                trimmed.startsWith("> ") -> {
                    flushParagraph()
                    blocks += MarkdownBlock.Quote(trimmed.drop(2))
                }
                trimmed.startsWith("- [x] ", ignoreCase = true) -> {
                    flushParagraph()
                    blocks += MarkdownBlock.ListItem(trimmed.drop(6), true)
                }
                trimmed.startsWith("- [ ] ") -> {
                    flushParagraph()
                    blocks += MarkdownBlock.ListItem(trimmed.drop(6), false)
                }
                trimmed.startsWith("- ") -> {
                    flushParagraph()
                    blocks += MarkdownBlock.ListItem(trimmed.drop(2))
                }
                Regex("^\\d+\\.\\s+").containsMatchIn(trimmed) -> {
                    flushParagraph()
                    blocks += MarkdownBlock.ListItem(trimmed.replaceFirst(Regex("^\\d+\\.\\s+"), ""))
                }
                else -> {
                    if (paragraph.isNotEmpty()) paragraph.appendLine()
                    paragraph.append(trimmed)
                }
            }
        }
        flushParagraph()
        if (code.isNotEmpty()) blocks += MarkdownBlock.Code(code.toString().trimEnd())
        return blocks
    }

    private fun blocksToHtml(blocks: List<MarkdownBlock>): String = buildString {
        var inList = false
        fun closeList() {
            if (inList) {
                append("</ul>")
                inList = false
            }
        }
        for (block in blocks) {
            when (block) {
                is MarkdownBlock.Heading -> {
                    closeList()
                    append("<h").append(block.level).append(">").append(inline(block.text)).append("</h").append(block.level).append(">")
                }
                is MarkdownBlock.Paragraph -> {
                    closeList()
                    append("<p>").append(inline(block.text)).append("</p>")
                }
                is MarkdownBlock.Quote -> {
                    closeList()
                    append("<blockquote>").append(inline(block.text)).append("</blockquote>")
                }
                is MarkdownBlock.Code -> {
                    closeList()
                    append("<pre><code>").append(escape(block.code)).append("</code></pre>")
                }
                is MarkdownBlock.ListItem -> {
                    if (!inList) {
                        append("<ul>")
                        inList = true
                    }
                    val marker = when (block.checked) {
                        true -> "☑ "
                        false -> "☐ "
                        null -> ""
                    }
                    append("<li>").append(marker).append(inline(block.text)).append("</li>")
                }
                MarkdownBlock.Rule -> {
                    closeList()
                    append("<hr>")
                }
            }
        }
        closeList()
    }

    private fun inline(value: String): String = escape(value)
        .replace(Regex("\\*\\*([^*]+)\\*\\*"), "<strong>$1</strong>")
        .replace(Regex("\\*([^*]+)\\*"), "<em>$1</em>")
        .replace(Regex("`([^`]+)`"), "<code>$1</code>")

    private fun escape(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
}
