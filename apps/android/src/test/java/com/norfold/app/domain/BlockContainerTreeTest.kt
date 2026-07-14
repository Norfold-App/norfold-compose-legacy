package com.norfold.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the nested-container backbone: [BlockTree] recursion utilities, path-based
 * [BlockEditorSession] structural ops (move Into/Split, wrap/unwrap/extract), dirty-ancestor
 * tracking, nested JSON round-trips, and the markdown projection for row/column containers.
 */
class BlockContainerTreeTest {

    private fun para(id: String, text: String) = ParagraphBlock(id = id, content = listOf(InlineText(text)))

    // --- BlockTree recursion ---------------------------------------------------------------

    @Test
    fun `pathOf and rootAncestorId resolve nested ids`() {
        val document = BlockDocument(
            listOf(
                para("a", "alpha"),
                ContainerBlock(
                    id = "row",
                    axis = ContainerAxis.Row,
                    children = listOf(
                        para("b", "beta"),
                        ContainerBlock(id = "col", children = listOf(para("c", "gamma"))),
                    ),
                    weights = listOf(1f, 1f),
                ),
            ),
        )

        assertEquals(listOf("row", "col", "c"), document.pathOf("c")?.ids)
        assertEquals("row", document.pathOf("c")?.rootId)
        assertEquals("col", document.pathOf("c")?.parentId)
        assertEquals("row", document.rootAncestorId("c"))
        assertEquals("a", document.rootAncestorId("a"))
        assertNull(document.pathOf("missing"))
    }

    @Test
    fun `allIds walks the whole tree depth first`() {
        val document = BlockDocument(
            listOf(
                para("a", "a"),
                ContainerBlock(id = "row", children = listOf(para("b", "b"), para("c", "c"))),
            ),
        )
        assertEquals(listOf("a", "row", "b", "c"), document.allIds())
    }

    @Test
    fun `findById updateBlock removeBlock and locate operate at depth`() {
        val blocks = listOf(
            para("a", "a"),
            ContainerBlock(id = "row", children = listOf(para("b", "b"), para("c", "c"))),
        )

        assertEquals("c", blocks.findById("c")?.id)

        val updated = blocks.updateBlock("c") { para("c", "CHANGED") }
        assertEquals("CHANGED", updated.findById("c")?.plainText())

        val (afterRemove, removed) = blocks.removeBlock("b")
        assertEquals("b", removed?.id)
        assertNull(afterRemove.findById("b"))
        assertEquals(listOf("c"), (afterRemove.findById("row") as ContainerBlock).children.map { it.id })

        assertEquals(Triple("row", 1, listOf("b", "c")),
            blocks.locate("c")?.let { (p, i, s) -> Triple(p, i, s.map { it.id }) })
        assertEquals(null as String?, blocks.locate("a")?.first)
    }

    // --- Session moves ---------------------------------------------------------------------

    @Test
    fun `move Into relocates a block into a container`() {
        val session = BlockEditorSession(
            BlockDocument(
                listOf(
                    para("a", "a"),
                    ContainerBlock(id = "row", axis = ContainerAxis.Row, children = listOf(para("b", "b")), weights = listOf(1f)),
                ),
            ),
        )

        session.move("a", DropTarget.Into(parentId = "row", index = 1))

        val row = session.document.findById("row") as ContainerBlock
        assertEquals(listOf("b", "a"), row.children.map { it.id })
        // "a" is no longer top-level → it must be recorded as removed for persistence.
        assertTrue("a" in session.deletedBlockIds)
        assertTrue("row" in session.dirtyBlockIds)
    }

    @Test
    fun `move Split forms a new row container at the target position`() {
        val session = BlockEditorSession(BlockDocument(listOf(para("a", "a"), para("b", "b"))))

        session.move("a", DropTarget.Split(targetId = "b", edge = Edge.Right))

        // Top level collapses to a single new container holding [b, a] (Right → target first).
        val top = session.document.blocks.single()
        assertTrue(top is ContainerBlock)
        top as ContainerBlock
        assertEquals(ContainerAxis.Row, top.axis)
        assertEquals(listOf("b", "a"), top.children.map { it.id })
        assertEquals(listOf(1f, 1f), top.weights)
    }

    @Test
    fun `move guards against dropping a container into its own descendant`() {
        val document = BlockDocument(
            listOf(
                ContainerBlock(id = "outer", children = listOf(ContainerBlock(id = "inner", children = listOf(para("x", "x"))))),
            ),
        )
        val session = BlockEditorSession(document)

        session.move("outer", DropTarget.Into(parentId = "inner", index = 0))

        // No-op: the tree is unchanged.
        assertEquals(document.normalized(), session.document)
    }

    // --- wrap / unwrap / extract -----------------------------------------------------------

    @Test
    fun `wrapInContainer then unwrap is round trip at top level`() {
        val session = BlockEditorSession(BlockDocument(listOf(para("a", "a"), para("b", "b"))))

        session.wrapInContainer("a", ContainerAxis.Column)
        val container = session.document.blocks.first()
        assertTrue(container is ContainerBlock)
        assertEquals(listOf("a"), (container as ContainerBlock).children.map { it.id })
        assertTrue("a" in session.deletedBlockIds)

        session.unwrap(container.id)
        assertEquals(listOf("a", "b"), session.document.blocks.map { it.id })
    }

    @Test
    fun `extractToTopLevel hoists a nested block after its owning row`() {
        val session = BlockEditorSession(
            BlockDocument(
                listOf(
                    para("head", "head"),
                    ContainerBlock(id = "row", axis = ContainerAxis.Row, children = listOf(para("b", "b"), para("c", "c")), weights = listOf(1f, 1f)),
                ),
            ),
        )

        session.extractToTopLevel("c")

        assertEquals(listOf("head", "row", "c"), session.document.blocks.map { it.id })
        val row = session.document.findById("row") as ContainerBlock
        assertEquals(listOf("b"), row.children.map { it.id })
    }

    @Test
    fun `addChild and setAxis mutate a container and mark ancestor dirty`() {
        val session = BlockEditorSession(
            BlockDocument(listOf(ContainerBlock(id = "row", axis = ContainerAxis.Row, children = listOf(para("b", "b")), weights = listOf(1f)))),
        )

        session.addChild("row", para("c", "c"))
        val row = session.document.findById("row") as ContainerBlock
        assertEquals(listOf("b", "c"), row.children.map { it.id })
        assertEquals(listOf(1f, 1f), row.weights)
        assertTrue("row" in session.dirtyBlockIds)

        session.setAxis("row", ContainerAxis.Column)
        assertEquals(ContainerAxis.Column, (session.document.findById("row") as ContainerBlock).axis)
    }

    @Test
    fun `nested text edit marks the top-level ancestor dirty not the child`() {
        val session = BlockEditorSession(
            BlockDocument(
                listOf(
                    para("top", "top"),
                    ContainerBlock(id = "row", children = listOf(para("child", "child"))),
                ),
            ),
        )

        session.editText("child", "child", "edited")

        assertEquals("edited", session.document.findById("child")?.plainText())
        assertTrue("row" in session.dirtyBlockIds)
        assertTrue("child" !in session.deletedBlockIds)
    }

    // --- Normalization / serialization -----------------------------------------------------

    @Test
    fun `normalize pads weights and drops empty containers keeping single-child ones`() {
        val document = BlockDocument(
            listOf(
                ContainerBlock(id = "empty", children = emptyList()),
                ContainerBlock(id = "lonely", children = listOf(para("x", "x")), weights = emptyList()),
                ContainerBlock(id = "row", axis = ContainerAxis.Row, children = listOf(para("y", "y"), para("z", "z")), weights = listOf(2f)),
            ),
        ).normalized()

        assertNull(document.findById("empty"))
        val lonely = document.findById("lonely") as ContainerBlock
        assertEquals(listOf(1f), lonely.weights)
        val row = document.findById("row") as ContainerBlock
        assertEquals(listOf(2f, 1f), row.weights) // padded to child count
    }

    @Test
    fun `nested container survives json round trip`() {
        val document = BlockDocument(
            listOf(
                HeadingBlock(id = "h", level = 1, content = listOf(InlineText("Title"))),
                ContainerBlock(
                    id = "row",
                    axis = ContainerAxis.Row,
                    children = listOf(
                        para("left", "left text"),
                        ContainerBlock(
                            id = "rightcol",
                            axis = ContainerAxis.Column,
                            children = listOf(para("img", "image"), MathBlock(id = "m", tex = "x^2")),
                            weights = listOf(1f, 1f),
                        ),
                    ),
                    weights = listOf(1f, 1f),
                ),
            ),
        )

        assertEquals(document, BlockDocumentJson.decode(BlockDocumentJson.encode(document)))
    }

    // --- Markdown projection ---------------------------------------------------------------

    @Test
    fun `column container exports children stacked`() {
        val document = BlockDocument(
            listOf(ContainerBlock(id = "col", axis = ContainerAxis.Column, children = listOf(para("a", "alpha"), para("b", "beta")))),
        )
        val markdown = MarkdownBlockCodec.export(document)
        assertTrue(markdown, markdown.contains("alpha"))
        assertTrue(markdown, markdown.contains("beta"))
        assertTrue(markdown, !markdown.contains(":::column"))
    }

    @Test
    fun `row container exports as columns directive`() {
        val document = BlockDocument(
            listOf(
                ContainerBlock(
                    id = "row",
                    axis = ContainerAxis.Row,
                    children = listOf(para("a", "alpha"), para("b", "beta")),
                    weights = listOf(1f, 1f),
                ),
            ),
        )
        val markdown = MarkdownBlockCodec.export(document)
        assertTrue(markdown, markdown.contains("::::columns"))
        assertEquals(2, Regex(":::column\\b").findAll(markdown).count())
        assertTrue(markdown, markdown.contains("alpha"))
        assertTrue(markdown, markdown.contains("beta"))
    }
}
