package com.norfold.app.domain

import java.util.UUID

enum class PageBlockType {
    Paragraph,
    Heading,
    Bullet,
    Numbered,
    Checklist,
    Quote,
    Code,
    Divider,
    Table,
    Image,
    File,
    NoteLink,
    Raw,
}

data class PageBlock(
    val id: String = UUID.randomUUID().toString(),
    val type: PageBlockType,
    val text: String = "",
    val level: Int = 0,
    val checked: Boolean = false,
    val language: String = "",
    val target: String = "",
) {
    fun toMarkdown(): String = when (type) {
        PageBlockType.Paragraph -> text
        PageBlockType.Heading -> "${"#".repeat(level.coerceIn(1, 6))} $text"
        PageBlockType.Bullet -> "- $text"
        PageBlockType.Numbered -> "1. $text"
        PageBlockType.Checklist -> "- [${if (checked) "x" else " "}] $text"
        PageBlockType.Quote -> text.lines().joinToString("\n") { "> $it" }
        PageBlockType.Code -> "```${language}\n$text\n```"
        PageBlockType.Divider -> "---"
        PageBlockType.Table,
        PageBlockType.Raw -> text
        PageBlockType.Image -> "![$text]($target)"
        PageBlockType.File -> "[$text]($target)"
        PageBlockType.NoteLink -> "[[$text]]"
    }
}

data class PageDocument(val blocks: List<PageBlock>) {
    fun toMarkdown(): String = blocks.joinToString("\n\n") { it.toMarkdown() }.trimEnd()

    companion object {
        fun parse(markdown: String): PageDocument {
            if (markdown.isBlank()) return PageDocument(emptyList())
            val lines = markdown.lines()
            val blocks = mutableListOf<PageBlock>()
            var index = 0
            while (index < lines.size) {
                val line = lines[index]
                if (line.isBlank()) {
                    index++
                    continue
                }
                val trimmed = line.trimEnd()
                when {
                    trimmed.startsWith("```") -> {
                        val language = trimmed.removePrefix("```").trim()
                        val code = mutableListOf<String>()
                        index++
                        while (index < lines.size && !lines[index].trimStart().startsWith("```")) {
                            code += lines[index]
                            index++
                        }
                        if (index < lines.size) index++
                        blocks += PageBlock(type = PageBlockType.Code, text = code.joinToString("\n"), language = language)
                    }
                    trimmed == "---" || trimmed == "***" || trimmed == "___" -> {
                        blocks += PageBlock(type = PageBlockType.Divider)
                        index++
                    }
                    trimmed.startsWith("|") && tableExtent(lines, index) > index -> {
                        val end = tableExtent(lines, index)
                        blocks += PageBlock(type = PageBlockType.Table, text = lines.subList(index, end + 1).joinToString("\n"))
                        index = end + 1
                    }
                    HEADING.matches(trimmed) -> {
                        val match = HEADING.matchEntire(trimmed)!!
                        blocks += PageBlock(type = PageBlockType.Heading, level = match.groupValues[1].length, text = match.groupValues[2])
                        index++
                    }
                    CHECKLIST.matches(trimmed) -> {
                        val match = CHECKLIST.matchEntire(trimmed)!!
                        blocks += PageBlock(type = PageBlockType.Checklist, checked = match.groupValues[1].equals("x", true), text = match.groupValues[2])
                        index++
                    }
                    BULLET.matches(trimmed) -> {
                        blocks += PageBlock(type = PageBlockType.Bullet, text = BULLET.matchEntire(trimmed)!!.groupValues[1])
                        index++
                    }
                    NUMBERED.matches(trimmed) -> {
                        blocks += PageBlock(type = PageBlockType.Numbered, text = NUMBERED.matchEntire(trimmed)!!.groupValues[1])
                        index++
                    }
                    trimmed.startsWith(">") -> {
                        val quote = mutableListOf<String>()
                        while (index < lines.size && lines[index].trimStart().startsWith(">")) {
                            quote += lines[index].trimStart().removePrefix(">").removePrefix(" ")
                            index++
                        }
                        blocks += PageBlock(type = PageBlockType.Quote, text = quote.joinToString("\n"))
                    }
                    IMAGE.matches(trimmed) -> {
                        val match = IMAGE.matchEntire(trimmed)!!
                        blocks += PageBlock(type = PageBlockType.Image, text = match.groupValues[1], target = match.groupValues[2])
                        index++
                    }
                    NOTE_LINK.matches(trimmed) -> {
                        blocks += PageBlock(type = PageBlockType.NoteLink, text = NOTE_LINK.matchEntire(trimmed)!!.groupValues[1])
                        index++
                    }
                    FILE.matches(trimmed) -> {
                        val match = FILE.matchEntire(trimmed)!!
                        blocks += PageBlock(type = PageBlockType.File, text = match.groupValues[1], target = match.groupValues[2])
                        index++
                    }
                    looksLikeUnsupportedBlock(trimmed) -> {
                        blocks += PageBlock(type = PageBlockType.Raw, text = trimmed)
                        index++
                    }
                    else -> {
                        val paragraph = mutableListOf(trimmed)
                        index++
                        while (index < lines.size && lines[index].isNotBlank() && !startsStructuredBlock(lines, index)) {
                            paragraph += lines[index].trimEnd()
                            index++
                        }
                        blocks += PageBlock(type = PageBlockType.Paragraph, text = paragraph.joinToString("\n"))
                    }
                }
            }
            return PageDocument(blocks)
        }

        private fun startsStructuredBlock(lines: List<String>, index: Int): Boolean {
            val value = lines[index].trimEnd()
            return value.startsWith("```") || value in setOf("---", "***", "___") ||
                HEADING.matches(value) || CHECKLIST.matches(value) || BULLET.matches(value) ||
                NUMBERED.matches(value) || value.startsWith(">") || IMAGE.matches(value) ||
                NOTE_LINK.matches(value) || FILE.matches(value) ||
                (value.startsWith("|") && tableExtent(lines, index) > index) || looksLikeUnsupportedBlock(value)
        }

        private fun tableExtent(lines: List<String>, start: Int): Int {
            if (start + 1 >= lines.size || !TABLE_SEPARATOR.matches(lines[start + 1].trim())) return start
            var end = start + 1
            while (end + 1 < lines.size && lines[end + 1].trim().startsWith("|")) end++
            return end
        }

        private fun looksLikeUnsupportedBlock(value: String): Boolean =
            value.startsWith("<") || value.startsWith("    ") || value.startsWith(":::")

        private val HEADING = Regex("^(#{1,6})\\s+(.+)$")
        private val CHECKLIST = Regex("^[-*+]\\s+\\[([ xX])]\\s+(.*)$")
        private val BULLET = Regex("^[-*+]\\s+(.*)$")
        private val NUMBERED = Regex("^\\d+[.)]\\s+(.*)$")
        private val IMAGE = Regex("^!\\[([^]]*)]\\(([^)]+)\\)$")
        private val FILE = Regex("^\\[([^]]+)]\\(([^)]+)\\)$")
        private val NOTE_LINK = Regex("^\\[\\[([^]]+)]]$")
        private val TABLE_SEPARATOR = Regex("^\\|?(?:\\s*:?-{3,}:?\\s*\\|)+\\s*$")
    }
}
