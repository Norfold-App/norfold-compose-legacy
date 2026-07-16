package com.norfold.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BlockDocumentFoundationTest {
    @Test
    fun standaloneDisplayMathImportsAsEngineBlock() {
        val dollar = MarkdownBlockCodec.import("$$\n\\int_0^1 x^2\\,dx = \\frac{1}{3}\n$$").blocks.single()
        val bracket = MarkdownBlockCodec.import("\\[\n\\sum_{i=1}^{n} i\n\\]").blocks.single()

        assertTrue(dollar is MathBlock)
        assertEquals("\\int_0^1 x^2\\,dx = \\frac{1}{3}", (dollar as MathBlock).tex)
        assertTrue(bracket is MathBlock)
    }
    @Test
    fun `block tree json round trip is identical`() {
        val document = richDocument()
        assertEquals(document, BlockDocumentJson.decode(BlockDocumentJson.encode(document)))
    }

    @Test
    fun `render modes and embed size survive json round trip`() {
        val document = BlockDocument(
            listOf(
                CodeBlock(code = "println(42)", renderMode = BlockRenderMode.Source),
                TableBlock(headers = listOf(TableCell(listOf(InlineText("A")))), renderMode = BlockRenderMode.Source),
                ChartBlock(vegaLiteSpec = "{\"mark\":\"bar\"}", renderMode = BlockRenderMode.Source),
                MathBlock(tex = "x^2", renderMode = BlockRenderMode.Source),
                MermaidBlock(code = "graph TD; A-->B", renderMode = BlockRenderMode.Source),
                EmbedBlock(url = "https://example.com", displayHeightDp = 286f),
            ),
        )

        val restored = BlockDocumentJson.decode(BlockDocumentJson.encode(document))

        assertEquals(document, restored)
        assertEquals(286f, (restored.blocks.last() as EmbedBlock).displayHeightDp)
        assertTrue(restored.blocks.dropLast(1).all {
            when (it) {
                is CodeBlock -> it.renderMode == BlockRenderMode.Source
                is TableBlock -> it.renderMode == BlockRenderMode.Source
                is ChartBlock -> it.renderMode == BlockRenderMode.Source
                is MathBlock -> it.renderMode == BlockRenderMode.Source
                is MermaidBlock -> it.renderMode == BlockRenderMode.Source
                else -> false
            }
        })
    }

    @Test
    fun `inline formatting accepts reversed selections in paragraphs lists and todos`() {
        val paragraph = ParagraphBlock(id = "p", content = listOf(InlineText("alpha")))
        val listItem = ListItem(id = "li", content = listOf(InlineText("bravo")))
        val todoItem = TodoItem(id = "ti", content = listOf(InlineText("charlie")))
        val session = BlockEditorSession(
            BlockDocument(
                listOf(
                    paragraph,
                    BulletListBlock(id = "list", items = listOf(listItem)),
                    TodoListBlock(id = "todo", items = listOf(todoItem)),
                ),
            ),
        )

        session.replaceSelectionWithInline(BlockCursor("p", 5), BlockCursor("p", 0), BoldInline(listOf(InlineText("alpha"))))
        session.replaceSelectionWithInline(BlockCursor("list", 5, "li"), BlockCursor("list", 0, "li"), ItalicInline(listOf(InlineText("bravo"))))
        session.replaceSelectionWithInline(BlockCursor("todo", 7, "ti"), BlockCursor("todo", 0, "ti"), StrikethroughInline(listOf(InlineText("charlie"))))

        assertTrue((session.document.blocks[0] as ParagraphBlock).content.single() is BoldInline)
        assertTrue((session.document.blocks[1] as BulletListBlock).items.single().content.single() is ItalicInline)
        assertTrue((session.document.blocks[2] as TodoListBlock).items.single().content.single() is StrikethroughInline)
        assertEquals(listOf("alpha", "bravo", "charlie"), session.document.blocks.map(DocumentBlock::plainText))
    }

    @Test
    fun `quote edits retain inline structure`() {
        val quote = QuoteBlock(id = "quote", children = listOf(ParagraphBlock(content = listOf(BoldInline(listOf(InlineText("hello")))))))
        val session = BlockEditorSession(BlockDocument(listOf(quote)))

        session.editText(quote.id, "hello", "hello world")

        val content = ((session.document.blocks.single() as QuoteBlock).children.single() as ParagraphBlock).content
        assertTrue(content.first() is BoldInline)
        assertEquals("hello world", content.joinToString("") { it.plainText() })
    }

    @Test
    fun `seed style markdown import export is structurally stable`() {
        val markdown = """
            # Norfold Guide

            Write with **bold**, *italic*, [links](https://norfold.app), inline math ${'$'}x^2${'$'} and #guide.

            - Nested planning
              - Capture
              - Organize

            - [x] Create a note
            - [ ] Try the board

            | Feature | Result |
            | --- | --- |
            | Tables | Visual |
            | Math | ${'$'}${'$'}\\sum_1^n x${'$'}${'$'} |

            ```mermaid
            graph TD
              A[Capture] --> B[Organize]
            ```

            > [!TIP] Double-tap to edit.
        """.trimIndent()
        val once = MarkdownBlockCodec.import(markdown)
        val twice = MarkdownBlockCodec.import(MarkdownBlockCodec.export(once))
        assertEquals(MarkdownBlockCodec.export(once), MarkdownBlockCodec.export(twice))
        assertTrue(once.blocks.any { it is TableBlock })
        assertTrue(once.blocks.any { it is MermaidBlock })
        assertTrue(once.plainText().contains("Norfold Guide"))
    }

    @Test
    fun `nested list text remains searchable`() {
        val document = MarkdownBlockCodec.import("- Parent\n  - Nested child")
        assertTrue(MarkdownBlockCodec.export(document), document.plainText().contains("Nested child"))
    }

    @Test
    fun `split in middle preserves inline formatting and cursor`() {
        val paragraph = ParagraphBlock(content = listOf(BoldInline(listOf(InlineText("bold text")))))
        val session = BlockEditorSession(BlockDocument(listOf(paragraph)))
        val cursor = session.split(paragraph.id, 4)
        val left = session.document.blocks[0] as ParagraphBlock
        val right = session.document.blocks[1] as ParagraphBlock
        assertEquals("bold", left.plainText())
        assertEquals(" text", right.plainText())
        assertTrue(left.content.single() is BoldInline)
        assertTrue(right.content.single() is BoldInline)
        assertEquals(BlockCursor(right.id, 0), cursor)
    }

    @Test
    fun `split at end creates empty paragraph`() {
        val paragraph = ParagraphBlock(content = listOf(InlineText("hello")))
        val session = BlockEditorSession(BlockDocument(listOf(paragraph)))
        val cursor = session.split(paragraph.id, 5)
        assertEquals(2, session.document.blocks.size)
        assertEquals("", session.document.blocks[1].plainText())
        assertEquals(BlockCursor(session.document.blocks[1].id, 0), cursor)
    }

    @Test
    fun `merge restores join cursor and first block backspace is no op`() {
        val first = ParagraphBlock(content = listOf(InlineText("one")))
        val second = ParagraphBlock(content = listOf(ItalicInline(listOf(InlineText("two")))))
        val session = BlockEditorSession(BlockDocument(listOf(first, second)))
        assertEquals(BlockCursor(first.id, 0), session.mergeWithPrevious(first.id))
        assertEquals(2, session.document.blocks.size)
        val cursor = session.mergeWithPrevious(second.id)
        assertEquals(BlockCursor(first.id, 3), cursor)
        assertEquals("onetwo", session.document.blocks.single().plainText())
    }

    @Test
    fun `cross block replacement leaves one consistent block`() {
        val first = ParagraphBlock(content = listOf(InlineText("alpha")))
        val second = ParagraphBlock(content = listOf(InlineText("beta")))
        val third = ParagraphBlock(content = listOf(InlineText("gamma")))
        val session = BlockEditorSession(BlockDocument(listOf(first, second, third)))
        val cursor = session.replaceSelection(BlockCursor(first.id, 2), BlockCursor(third.id, 2), "X")
        assertEquals(1, session.document.blocks.size)
        assertEquals("alXmma", session.document.blocks.single().plainText())
        assertEquals(BlockCursor(first.id, 3), cursor)
    }

    @Test
    fun `undo redo and dirty tracking are operation based`() {
        val first = ParagraphBlock(content = listOf(InlineText("one")))
        val second = ParagraphBlock(content = listOf(InlineText("two")))
        val session = BlockEditorSession(BlockDocument(listOf(first, second)))
        session.replaceBlock(first.copy(content = listOf(InlineText("changed"))))
        assertEquals(setOf(first.id), session.dirtyBlockIds)
        assertTrue(session.undo())
        assertEquals("one", session.document.blocks.first().plainText())
        assertTrue(session.redo())
        assertEquals("changed", session.document.blocks.first().plainText())
        session.markSaved()
        assertFalse(session.dirtyBlockIds.isNotEmpty())
    }

    @Test
    fun `deleting last block creates safe empty paragraph`() {
        val only = ParagraphBlock(content = listOf(InlineText("remove")))
        val session = BlockEditorSession(BlockDocument(listOf(only)))
        session.delete(only.id)
        assertEquals(1, session.document.blocks.size)
        assertEquals("", session.document.blocks.single().plainText())
    }

    @Test
    fun `rapid sequential typing and backspace do not duplicate stale text`() {
        val paragraph = ParagraphBlock()
        val session = BlockEditorSession(BlockDocument(listOf(paragraph)))
        var local = ""
        "editor".forEach { character ->
            val next = local + character
            session.editText(paragraph.id, local, next)
            local = next
        }
        repeat(3) {
            val next = local.dropLast(1)
            session.editText(paragraph.id, local, next)
            local = next
        }
        assertEquals("edi", session.document.blocks.single().plainText())
    }

    @Test
    fun `blank markdown creates exactly one empty paragraph`() {
        val document = MarkdownBlockCodec.import("")
        assertEquals(1, document.blocks.size)
        assertTrue(document.blocks.single() is ParagraphBlock)
        assertEquals("", document.blocks.single().plainText())
    }

    @Test
    fun `enter in a list splits an item and empty item exits to paragraph`() {
        val item = ListItem(content = listOf(BoldInline(listOf(InlineText("alpha")))))
        val list = BulletListBlock(items = listOf(item))
        val session = BlockEditorSession(BlockDocument(listOf(list)))

        val splitCursor = session.splitListItem(list.id, item.id, 2)
        val split = session.document.blocks.single() as BulletListBlock
        assertEquals(listOf("al", "pha"), split.items.map { it.content.plainText() })
        assertTrue(split.items.all { it.content.single() is BoldInline })
        assertEquals(split.items[1].id, splitCursor.itemId)

        session.editListItem(list.id, split.items[1].id, "pha", "")
        val exitCursor = session.exitEmptyListItem(list.id, split.items[1].id)
        assertEquals(2, session.document.blocks.size)
        assertTrue(session.document.blocks[0] is BulletListBlock)
        assertTrue(session.document.blocks[1] is ParagraphBlock)
        assertEquals(session.document.blocks[1].id, exitCursor.blockId)
    }

    @Test
    fun `backspace at list item start merges at the exact join`() {
        val first = ListItem(content = listOf(ItalicInline(listOf(InlineText("one")))))
        val second = ListItem(content = listOf(BoldInline(listOf(InlineText("two")))))
        val list = NumberedListBlock(items = listOf(first, second))
        val session = BlockEditorSession(BlockDocument(listOf(list)))

        val cursor = session.mergeListItemWithPrevious(list.id, second.id)
        val merged = session.document.blocks.single() as NumberedListBlock
        assertEquals(1, merged.items.size)
        assertEquals("onetwo", merged.items.single().content.plainText())
        assertEquals(BlockCursor(list.id, 3, first.id), cursor)
    }

    @Test
    fun `undo and redo cover insert delete move split merge and text edit`() {
        fun document() = BlockDocument(
            listOf(
                ParagraphBlock(id = "a", content = listOf(InlineText("one"))),
                ParagraphBlock(id = "b", content = listOf(InlineText("two"))),
            ),
        )

        BlockEditorSession(document()).also { session ->
            session.insertAfter("a", ParagraphBlock(id = "inserted"))
            assertEquals(3, session.document.blocks.size)
            assertTrue(session.undo()); assertEquals(document(), session.document)
            assertTrue(session.redo()); assertEquals(3, session.document.blocks.size)
        }
        BlockEditorSession(document()).also { session ->
            session.delete("b")
            assertEquals(1, session.document.blocks.size)
            assertTrue(session.undo()); assertEquals(document(), session.document)
            assertTrue(session.redo()); assertEquals(1, session.document.blocks.size)
        }
        BlockEditorSession(document()).also { session ->
            session.move("b", 0)
            assertEquals("b", session.document.blocks.first().id)
            assertTrue(session.undo()); assertEquals(document(), session.document)
            assertTrue(session.redo()); assertEquals("b", session.document.blocks.first().id)
        }
        BlockEditorSession(document()).also { session ->
            session.editText("a", "one", "ONE")
            assertEquals("ONE", session.document.blocks.first().plainText())
            assertTrue(session.undo()); assertEquals(document(), session.document)
            assertTrue(session.redo()); assertEquals("ONE", session.document.blocks.first().plainText())
        }
        BlockEditorSession(document()).also { session ->
            val split = session.split("a", 1)
            assertEquals(3, session.document.blocks.size)
            assertTrue(session.undo()); assertEquals(document(), session.document)
            assertTrue(session.redo()); assertEquals(3, session.document.blocks.size)
            session.mergeWithPrevious(split.blockId)
            assertEquals(2, session.document.blocks.size)
            assertTrue(session.undo()); assertEquals(3, session.document.blocks.size)
            assertTrue(session.redo()); assertEquals(2, session.document.blocks.size)
        }
    }

    @Test
    fun `smart paste imports mixed markdown as ordered blocks in one transaction`() {
        val paragraph = ParagraphBlock(id = "target", content = listOf(InlineText("before after")))
        val session = BlockEditorSession(BlockDocument(listOf(paragraph)))
        val paste = """
            # Pasted heading

            - first
            - second

            | A | B |
            | --- | --- |
            | 1 | 2 |
        """.trimIndent()
        val newText = "before $paste after"

        val outcome = session.editTextOrSmartPaste(paragraph.id, paragraph.plainText(), newText)

        assertTrue(outcome.structuredPaste)
        assertTrue(session.document.blocks.any { it is HeadingBlock })
        assertTrue(session.document.blocks.any { it is BulletListBlock })
        assertTrue(session.document.blocks.any { it is TableBlock })
        assertEquals("before", session.document.blocks.first().plainText().trim())
        assertEquals("after", session.document.blocks.last().plainText().trim())
        assertTrue(session.undo())
        assertEquals(BlockDocument(listOf(paragraph)), session.document)
    }

    @Test
    fun `outer markdown wrapper unwraps while preserving nested fences`() {
        val pasted = """
            ````markdown
            ## Reply

            ```kotlin
            fun main() = println("Norfold")
            ```
            ````
        """.trimIndent()

        val document = MarkdownBlockCodec.import(pasted)

        assertTrue(document.blocks.first() is HeadingBlock)
        assertEquals("kotlin", (document.blocks.last() as CodeBlock).language)
        assertTrue((document.blocks.last() as CodeBlock).code.contains("fun main"))
    }

    @Test
    fun `unlabelled fences route to engines and detect code languages`() {
        fun block(source: String) = MarkdownBlockCodec.import("```\n$source\n```").blocks.single()

        assertTrue(block("graph TD\n  A-->B") is MermaidBlock)
        assertTrue(block("\\frac{a}{b} + \\sum_{i=1}^{n} i") is MathBlock)
        assertTrue(block("{\"mark\":\"bar\",\"encoding\":{}}") is ChartBlock)
        assertEquals("kotlin", (block("fun answer(): Int = 42") as CodeBlock).language)
        assertEquals("python", (block("def answer():\n    return 42") as CodeBlock).language)
    }

    @Test
    fun `ordinary multi character input remains a text edit`() {
        val paragraph = ParagraphBlock(id = "autocomplete", content = listOf(InlineText("hel")))
        val session = BlockEditorSession(BlockDocument(listOf(paragraph)))

        val outcome = session.editTextOrSmartPaste(paragraph.id, "hel", "hello")

        assertFalse(outcome.structuredPaste)
        assertEquals("hello", session.document.blocks.single().plainText())
    }

    @Test
    fun `live emoji keeps reversible shortcode in the block tree`() {
        val paragraph = ParagraphBlock(id = "emoji", content = listOf(InlineText("Launch ")))
        val session = BlockEditorSession(BlockDocument(listOf(paragraph)))

        val cursor = session.replaceSelectionWithInline(
            BlockCursor(paragraph.id, 7),
            BlockCursor(paragraph.id, 7),
            EmojiInline(":rocket:", "🚀"),
        )

        assertEquals("Launch 🚀", session.document.plainText())
        assertEquals("Launch :rocket:", MarkdownBlockCodec.export(session.document))
        assertEquals("Launch ".length + "🚀".length, cursor.offset)
        session.replaceSelection(BlockCursor(paragraph.id, 7), BlockCursor(paragraph.id, 7 + "🚀".length), ":rocket:")
        assertEquals("Launch :rocket:", session.document.plainText())
        assertTrue(session.undo())
        assertEquals("Launch 🚀", session.document.plainText())
    }

    @Test
    fun `large mixed document remains structurally stable`() {
        val blocks = buildList {
            repeat(220) { index ->
                add(ParagraphBlock(id = "p-$index", content = listOf(InlineText("Block $index"))))
                if (index % 40 == 0) add(MathBlock(id = "m-$index", tex = "x_$index^2"))
                if (index % 55 == 0) add(MermaidBlock(id = "g-$index", code = "graph TD; A$index-->B$index"))
                if (index % 70 == 0) add(ChartBlock(id = "c-$index", vegaLiteSpec = "{\"mark\":\"bar\"}"))
            }
        }
        val document = BlockDocument(blocks)
        assertEquals(document, BlockDocumentJson.decode(BlockDocumentJson.encode(document)))
        val session = BlockEditorSession(document)
        session.editText("p-100", "Block 100", "Changed")
        assertEquals(setOf("p-100"), session.dirtyBlockIds)
    }

    @Test
    fun `rapid enter and backspace operations preserve a valid document`() {
        val original = ParagraphBlock(id = "rapid", content = listOf(InlineText("abcdefghij")))
        val session = BlockEditorSession(BlockDocument(listOf(original)))
        repeat(75) {
            val target = session.document.blocks.last()
            val cursor = session.split(target.id, target.plainText().length)
            session.editText(cursor.blockId, "", "x")
        }
        repeat(75) {
            val target = session.document.blocks.last()
            session.mergeWithPrevious(target.id)
        }
        assertEquals(1, session.document.blocks.size)
        assertEquals("abcdefghij" + "x".repeat(75), session.document.blocks.single().plainText())
    }

    private fun richDocument() = BlockDocument(
        listOf(
            HeadingBlock(level = 1, content = listOf(InlineText("Norfold"))),
            ParagraphBlock(content = listOf(BoldInline(listOf(InlineText("Private"))), InlineText(" workspace"))),
            TodoListBlock(items = listOf(TodoItem(checked = true, content = listOf(InlineText("Round trip"))))),
            TableBlock(headers = listOf(TableCell(listOf(InlineText("A")))), rows = listOf(listOf(TableCell(listOf(InlineText("B")))))),
            MathBlock(tex = "\\frac{1}{2}"),
            MermaidBlock(code = "graph TD; A-->B"),
        ),
    )
}
