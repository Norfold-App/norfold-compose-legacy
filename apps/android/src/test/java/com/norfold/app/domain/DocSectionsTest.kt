package com.norfold.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the sidebar ToC section model: [DocSections] range resolution, [withFreshIds] deep
 * cloning, and the [BlockEditorSession] section operations (delete / duplicate / move) that the
 * editor applies on behalf of the sidebar.
 */
class DocSectionsTest {

    private fun heading(id: String, level: Int, text: String) =
        HeadingBlock(id = id, level = level, content = listOf(InlineText(text)))

    private fun para(id: String) = ParagraphBlock(id = id, content = listOf(InlineText("body $id")))

    /** lead paragraph, H1 A (2 paras), H2 A1 (1 para), H1 B (1 para) */
    private fun sampleDocument() = BlockDocument(
        blocks = listOf(
            para("lead"),
            heading("a", 1, "Alpha"),
            para("a1"),
            para("a2"),
            heading("a-sub", 2, "Alpha sub"),
            para("as1"),
            heading("b", 1, "Beta"),
            para("b1"),
        ),
    )

    // --- DocSections.rangeFor / sectionHeadingIds ---

    @Test
    fun `section heading ids are the top level headings in order`() {
        assertEquals(listOf("a", "a-sub", "b"), DocSections.sectionHeadingIds(sampleDocument()))
    }

    @Test
    fun `h1 section spans its subsections until the next h1`() {
        assertEquals(1..5, DocSections.rangeFor(sampleDocument(), "a"))
    }

    @Test
    fun `h2 section stops at the next heading of same or shallower level`() {
        assertEquals(4..5, DocSections.rangeFor(sampleDocument(), "a-sub"))
    }

    @Test
    fun `last section extends to the end of the document`() {
        assertEquals(6..7, DocSections.rangeFor(sampleDocument(), "b"))
    }

    @Test
    fun `non heading and unknown ids resolve to null`() {
        assertNull(DocSections.rangeFor(sampleDocument(), "lead"))
        assertNull(DocSections.rangeFor(sampleDocument(), "missing"))
    }

    @Test
    fun `nested headings are not sections`() {
        val document = BlockDocument(
            blocks = listOf(QuoteBlock(id = "quote", children = listOf(heading("h-nested", 2, "Inside")))),
        )
        assertNull(DocSections.rangeFor(document, "h-nested"))
        assertTrue(DocSections.sectionHeadingIds(document).isEmpty())
    }

    // --- withFreshIds ---

    @Test
    fun `withFreshIds renews every id including nested list items and container children`() {
        val original = ContainerBlock(
            id = "container",
            children = listOf(
                BulletListBlock(
                    id = "list",
                    items = listOf(ListItem(id = "item", children = listOf(ListItem(id = "child")))),
                ),
                TodoListBlock(id = "todos", items = listOf(TodoItem(id = "todo", checked = true))),
            ),
        )
        val copy = original.withFreshIds() as ContainerBlock
        assertNotEquals(original.id, copy.id)
        val copiedList = copy.children[0] as BulletListBlock
        assertNotEquals("list", copiedList.id)
        assertNotEquals("item", copiedList.items[0].id)
        assertNotEquals("child", copiedList.items[0].children[0].id)
        val copiedTodos = copy.children[1] as TodoListBlock
        assertNotEquals("todos", copiedTodos.id)
        assertNotEquals("todo", copiedTodos.items[0].id)
        assertTrue(copiedTodos.items[0].checked)
    }

    @Test
    fun `withFreshIds preserves content`() {
        val original = heading("h", 2, "Title")
        val copy = original.withFreshIds() as HeadingBlock
        assertEquals(original.content, copy.content)
        assertEquals(2, copy.level)
    }

    // --- BlockEditorSession.deleteSection ---

    @Test
    fun `deleteSection removes the heading and its whole range`() {
        val session = BlockEditorSession(sampleDocument())
        assertTrue(session.deleteSection("a"))
        assertEquals(listOf("lead", "b", "b1"), session.document.blocks.map { it.id })
        assertTrue(session.deletedBlockIds.containsAll(listOf("a", "a1", "a2", "a-sub", "as1")))
    }

    @Test
    fun `deleteSection is undoable`() {
        val document = sampleDocument()
        val session = BlockEditorSession(document)
        assertTrue(session.deleteSection("a"))
        assertTrue(session.undo())
        assertEquals(document.blocks.map { it.id }, session.document.blocks.map { it.id })
    }

    @Test
    fun `deleting the only section leaves a normalized non empty document`() {
        val session = BlockEditorSession(BlockDocument(blocks = listOf(heading("only", 1, "All"), para("p"))))
        assertTrue(session.deleteSection("only"))
        assertEquals(1, session.document.blocks.size)
        assertTrue(session.document.blocks.single() is ParagraphBlock)
    }

    @Test
    fun `deleteSection rejects non section ids`() {
        val session = BlockEditorSession(sampleDocument())
        assertFalse(session.deleteSection("lead"))
        assertEquals(sampleDocument().blocks.map { it.id }, session.document.blocks.map { it.id })
    }

    // --- BlockEditorSession.duplicateSection ---

    @Test
    fun `duplicateSection inserts a fresh id copy right after the original`() {
        val session = BlockEditorSession(sampleDocument())
        assertTrue(session.duplicateSection("a-sub"))
        val ids = session.document.blocks.map { it.id }
        assertEquals(10, ids.size)
        // Copy sits between the original subsection and the Beta heading.
        assertEquals(listOf("lead", "a", "a1", "a2", "a-sub", "as1"), ids.take(6))
        assertEquals(listOf("b", "b1"), ids.takeLast(2))
        assertEquals(ids.size, ids.toSet().size)
        val copyHeading = session.document.blocks[6] as HeadingBlock
        assertEquals("Alpha sub", copyHeading.content.plainText())
        assertNotEquals("a-sub", copyHeading.id)
    }

    @Test
    fun `duplicateSection marks the copies dirty for persistence`() {
        val session = BlockEditorSession(sampleDocument())
        assertTrue(session.duplicateSection("b"))
        val copyIds = session.document.blocks.map { it.id } - sampleDocument().blocks.map { it.id }.toSet()
        assertEquals(2, copyIds.size)
        assertTrue(session.dirtyBlockIds.containsAll(copyIds))
    }

    // --- BlockEditorSession.moveSectionBefore ---

    @Test
    fun `moveSectionBefore moves a whole section up`() {
        val session = BlockEditorSession(sampleDocument())
        assertTrue(session.moveSectionBefore("b", "a"))
        assertEquals(listOf("lead", "b", "b1", "a", "a1", "a2", "a-sub", "as1"), session.document.blocks.map { it.id })
    }

    @Test
    fun `moveSectionBefore with null target moves the section to the end`() {
        val session = BlockEditorSession(sampleDocument())
        assertTrue(session.moveSectionBefore("a", null))
        assertEquals(listOf("lead", "b", "b1", "a", "a1", "a2", "a-sub", "as1"), session.document.blocks.map { it.id })
    }

    @Test
    fun `moveSectionBefore rejects targets inside the moving section`() {
        val session = BlockEditorSession(sampleDocument())
        assertFalse(session.moveSectionBefore("a", "a-sub"))
        assertEquals(sampleDocument().blocks.map { it.id }, session.document.blocks.map { it.id })
    }

    @Test
    fun `moveSectionBefore rejects the identity position`() {
        val session = BlockEditorSession(sampleDocument())
        assertFalse(session.moveSectionBefore("a", "b"))
        assertFalse(session.moveSectionBefore("b", null))
        assertEquals(sampleDocument().blocks.map { it.id }, session.document.blocks.map { it.id })
    }

    @Test
    fun `moving a subsection out of its parent keeps both sections intact`() {
        val session = BlockEditorSession(sampleDocument())
        assertTrue(session.moveSectionBefore("a-sub", null))
        assertEquals(listOf("lead", "a", "a1", "a2", "b", "b1", "a-sub", "as1"), session.document.blocks.map { it.id })
    }

    @Test
    fun `moveSectionBefore is undoable`() {
        val document = sampleDocument()
        val session = BlockEditorSession(document)
        assertTrue(session.moveSectionBefore("b", "a"))
        assertTrue(session.undo())
        assertEquals(document.blocks.map { it.id }, session.document.blocks.map { it.id })
    }
}
