package com.norfold.app.editor.flow

import com.norfold.app.domain.BlockDocument
import com.norfold.app.domain.BoldInline
import com.norfold.app.domain.BulletListBlock
import com.norfold.app.domain.CalloutBlock
import com.norfold.app.domain.CodeBlock
import com.norfold.app.domain.CodeInline
import com.norfold.app.domain.HeadingBlock
import com.norfold.app.domain.InlineText
import com.norfold.app.domain.LinkInline
import com.norfold.app.domain.ListItem
import com.norfold.app.domain.MathInline
import com.norfold.app.domain.ParagraphBlock
import com.norfold.app.domain.TableBlock
import com.norfold.app.domain.TableCell
import com.norfold.app.domain.TodoItem
import com.norfold.app.domain.TodoListBlock
import com.norfold.app.domain.findById
import com.norfold.app.domain.plainText
import com.norfold.app.editor.command.CommandState
import com.norfold.app.editor.command.CommandSurface
import com.norfold.app.editor.history.EditTarget
import com.norfold.app.editor.presentation.FormFactor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Phase 1 interaction-correctness gate, at the layer that can actually be asserted.
 *
 * Every behavior here was previously "the logic exists somewhere" — a caret the runtime moved that no field
 * followed, an Enter key the field turned into a `\n`, a drag that divided by a guessed row pitch. These tests
 * pin the *decisions*: what the runtime asks the surface to do, and what a pointer position resolves to. The
 * Compose half — that a field genuinely takes focus and a marker genuinely draws — is asserted in
 * `FlowHostInteractionTest` under androidTest, because only a real composition can show it.
 */
class FlowHostCorrectionTest {

    private val phone = FormFactor(widthDp = 400, heightDp = 900)

    private fun runtimeOf(document: BlockDocument): FlowHostRuntime = FlowHostRuntime(
        controller = FlowEditController(EditTarget("note:flow"), document),
        initialFormFactor = phone,
    )

    private fun paragraphs(vararg text: String): BlockDocument = BlockDocument(
        text.mapIndexed { index, value -> ParagraphBlock(id = "p$index", content = listOf(InlineText(value))) },
    )

    private fun bullets(vararg text: String): BlockDocument = BlockDocument(
        listOf(
            BulletListBlock(
                id = "b0",
                items = text.mapIndexed { index, value ->
                    ListItem(id = "i$index", content = listOf(InlineText(value)))
                },
            ),
        ),
    )

    // ── Item 1: caret / focus two-way sync ───────────────────────────────────────────────────────────

    /**
     * A command that moves the caret must publish a request, or the field keeps its own stale cursor and the
     * next keystroke lands in the wrong block.
     */
    @Test
    fun caretRequest_isPublishedWhenACommandMovesTheCaret() {
        val runtime = runtimeOf(paragraphs("alpha"))
        runtime.setCaret("p0", 5)
        val before = runtime.uiState().caretRequest

        runtime.invoke(FlowIds.insert(FlowBlockKind.PARAGRAPH), CommandSurface.DOCK)

        val after = runtime.uiState().caretRequest
        assertNotNull("Insert moves the caret into the new block; the surface must be told", after)
        assertTrue((after!!.stamp) > (before?.stamp ?: 0L))
        assertEquals(runtime.scope.caret()?.targetId, after.blockId)
    }

    /**
     * A purely structural command leaves the caret alone, so no request is published. Re-seeding focus here
     * would steal it back from wherever the user had put it.
     */
    @Test
    fun caretRequest_isNotRepublishedByACommandThatDoesNotMoveTheCaret() {
        val runtime = runtimeOf(paragraphs("alpha", "beta"))
        runtime.setCaret("p1", 0)
        val before = runtime.uiState().caretRequest?.stamp ?: 0L

        runtime.invoke(FlowIds.MOVE_UP, CommandSurface.DOCK)

        assertEquals(before, runtime.uiState().caretRequest?.stamp ?: 0L)
    }

    /** The stamp advances monotonically, which is what lets the surface apply each request exactly once. */
    @Test
    fun caretRequest_stampIsMonotonic() {
        val runtime = runtimeOf(paragraphs("alpha"))
        runtime.setCaret("p0", 0)
        val stamps = mutableListOf<Long>()
        repeat(3) {
            runtime.invoke(FlowIds.insert(FlowBlockKind.PARAGRAPH), CommandSurface.DOCK)
            stamps += runtime.uiState().caretRequest!!.stamp
        }
        assertEquals(stamps.sorted().distinct(), stamps)
    }

    /** A caret the *field* reported must not advance the stamp, or the surface re-applies its own echo forever. */
    @Test
    fun caretRequest_isNotAdvancedByAFieldReportedCaret() {
        val runtime = runtimeOf(paragraphs("alpha"))
        runtime.setCaret("p0", 1)
        val stamp = runtime.uiState().caretRequest?.stamp ?: 0L

        runtime.setCaret("p0", 2)
        runtime.setCaret("p0", 3)

        assertEquals(stamp, runtime.uiState().caretRequest?.stamp ?: 0L)
    }

    /** Undo rewrites text behind the field's back, so the caret is republished and clamped to the new length. */
    @Test
    fun undo_publishesAClampedCaretRequest() {
        val runtime = runtimeOf(paragraphs("alpha"))
        runtime.setCaret("p0", 5)
        runtime.editText("p0", "alpha", "alpha and then some more")
        runtime.setCaret("p0", 24)

        assertTrue(runtime.undo())

        val request = runtime.uiState().caretRequest
        assertNotNull(request)
        assertEquals("p0", request!!.blockId)
        assertTrue(
            "Offset ${request.offset} must be inside the restored text",
            request.offset <= "alpha".length,
        )
    }

    /** Focus must not remain in a block the runtime no longer treats as active. */
    @Test
    fun undo_retargetsTheCaretWhenItsBlockDisappears() {
        val runtime = runtimeOf(paragraphs("alpha", "beta"))
        runtime.setCaret("p0", 0)
        runtime.invoke(FlowIds.insert(FlowBlockKind.PARAGRAPH), CommandSurface.DOCK)
        val inserted = runtime.scope.caret()!!.targetId

        assertTrue(runtime.undo())

        assertNull(runtime.controller.document().findById(inserted))
        val caret = runtime.scope.caret()
        assertNotNull(caret)
        assertNotNull(
            "The caret must address a block that still exists",
            runtime.controller.document().findById(caret!!.targetId),
        )
    }

    /** A stale item id would make the next Enter/Backspace resolve against an item that is gone. */
    @Test
    fun caretItemId_isDroppedWhenTheItemDisappears() {
        val runtime = runtimeOf(bullets("one", "two"))
        runtime.setCaret("b0", 0, itemId = "i1")
        assertTrue(runtime.backspaceAtStart())

        runtime.undo()
        runtime.setCaret("b0", 0, itemId = "missing-item")
        runtime.undo()

        assertNull(runtime.scope.caretItemId)
    }

    // ── Item 2: writing semantics ────────────────────────────────────────────────────────────────────

    @Test
    fun newLine_splitsAParagraphAtTheCaret() {
        val runtime = runtimeOf(paragraphs("hello world"))
        runtime.setCaret("p0", 5)

        assertTrue(runtime.newLine())

        val blocks = runtime.controller.document().blocks
        assertEquals(2, blocks.size)
        assertEquals("hello", blocks[0].plainText())
        assertEquals(" world", blocks[1].plainText())
        assertEquals(blocks[1].id, runtime.uiState().caretRequest?.blockId)
    }

    @Test
    fun newLine_splitsAListItemIntoTwo() {
        val runtime = runtimeOf(bullets("alpha"))
        runtime.setCaret("b0", 2, itemId = "i0")

        assertTrue(runtime.newLine())

        val items = (runtime.controller.document().findById("b0") as BulletListBlock).items
        assertEquals(2, items.size)
        assertEquals("al", items[0].content.plainText())
        assertEquals("pha", items[1].content.plainText())
        assertEquals(items[1].id, runtime.uiState().caretRequest?.itemId)
    }

    /** Enter on an empty item leaves the list. Inserting another blank bullet is what users read as "stuck". */
    @Test
    fun newLine_onAnEmptyListItemLeavesTheList() {
        val runtime = runtimeOf(
            BlockDocument(
                listOf(
                    BulletListBlock(
                        id = "b0",
                        items = listOf(
                            ListItem(id = "i0", content = listOf(InlineText("alpha"))),
                            ListItem(id = "i1"),
                        ),
                    ),
                ),
            ),
        )
        runtime.setCaret("b0", 0, itemId = "i1")

        assertTrue(runtime.newLine())

        val blocks = runtime.controller.document().blocks
        assertEquals(2, blocks.size)
        assertTrue(blocks[1] is ParagraphBlock)
        assertEquals(1, (blocks[0] as BulletListBlock).items.size)
    }

    /** A to-do list holds a different item type; Enter must still work rather than silently doing nothing. */
    @Test
    fun newLine_splitsATodoItem() {
        val runtime = runtimeOf(
            BlockDocument(
                listOf(
                    TodoListBlock(
                        id = "t0",
                        items = listOf(TodoItem(id = "i0", checked = true, content = listOf(InlineText("done")))),
                    ),
                ),
            ),
        )
        runtime.setCaret("t0", 2, itemId = "i0")

        assertTrue(runtime.newLine())

        val items = (runtime.controller.document().findById("t0") as TodoListBlock).items
        assertEquals(2, items.size)
        assertEquals("do", items[0].content.plainText())
        assertEquals("ne", items[1].content.plainText())
        assertFalse("A split-off to-do is new work, not completed work", items[1].checked)
    }

    /** A newline inside code is content, so the runtime declines and the field keeps the keystroke. */
    @Test
    fun newLine_declinesInsideACodeBlock() {
        val runtime = runtimeOf(BlockDocument(listOf(CodeBlock(id = "c0", code = "fun main()"))))
        runtime.setCaret("c0", 4)

        assertFalse(runtime.newLine())
        assertEquals(1, runtime.controller.document().blocks.size)
    }

    @Test
    fun backspaceAtStart_mergesWithThePreviousBlock() {
        val runtime = runtimeOf(paragraphs("alpha", "beta"))
        runtime.setCaret("p1", 0)

        assertTrue(runtime.backspaceAtStart())

        assertEquals(1, runtime.controller.document().blocks.size)
        assertEquals("alphabeta", runtime.controller.document().blocks[0].plainText())
    }

    @Test
    fun backspaceAtStart_mergesListItems() {
        val runtime = runtimeOf(bullets("one", "two"))
        runtime.setCaret("b0", 0, itemId = "i1")

        assertTrue(runtime.backspaceAtStart())

        val items = (runtime.controller.document().findById("b0") as BulletListBlock).items
        assertEquals(1, items.size)
        assertEquals("onetwo", items[0].content.plainText())
        assertEquals(3, runtime.uiState().caretRequest?.offset)
    }

    /** Nothing above to join: the keystroke falls through unhandled rather than being swallowed. */
    @Test
    fun backspaceAtStart_returnsFalseAtTheTopOfTheDocument() {
        val runtime = runtimeOf(paragraphs("alpha"))
        runtime.setCaret("p0", 0)

        assertFalse(runtime.backspaceAtStart())
        assertEquals(1, runtime.controller.document().blocks.size)
    }

    /**
     * `exitEmptyListItem` declines on a non-empty first item and still returns a cursor. Reporting success
     * there would consume the key and yank focus for a change that never happened.
     */
    @Test
    fun backspaceAtStart_returnsFalseWhenTheVocabularyDeclines() {
        val runtime = runtimeOf(bullets("alpha"))
        runtime.setCaret("b0", 0, itemId = "i0")
        val before = runtime.controller.document()
        val stamp = runtime.uiState().caretRequest?.stamp ?: 0L

        assertFalse(runtime.backspaceAtStart())

        assertEquals(before, runtime.controller.document())
        assertEquals(stamp, runtime.uiState().caretRequest?.stamp ?: 0L)
    }

    /** Enter in prose is literally the Split command, so the two routes cannot drift apart. */
    @Test
    fun newLine_usesTheSameCommandAsTheDockSplit() {
        val viaKey = runtimeOf(paragraphs("hello world")).also {
            it.setCaret("p0", 5)
            it.newLine()
        }
        val viaDock = runtimeOf(paragraphs("hello world")).also {
            it.setCaret("p0", 5)
            it.invoke(FlowIds.SPLIT, CommandSurface.DOCK)
        }
        assertEquals(
            viaDock.controller.document().blocks.map { it.plainText() },
            viaKey.controller.document().blocks.map { it.plainText() },
        )
    }

    /** Two items of one list are separate writing runs; one undo step must not swallow edits in both. */
    @Test
    fun editText_coalescesPerItemNotPerBlock() {
        val runtime = runtimeOf(bullets("a", "b"))
        runtime.editText("b0", "a", "ab", itemId = "i0")
        runtime.editText("b0", "b", "bc", itemId = "i1")

        assertTrue(runtime.undo())

        val items = (runtime.controller.document().findById("b0") as BulletListBlock).items
        assertEquals("ab", items[0].content.plainText())
        assertEquals("b", items[1].content.plainText())
    }

    // ── Items 3 + 4: measured drag geometry ─────────────────────────────────────────────────────────

    private fun rows(vararg heights: Pair<String, Float>): List<FlowRowBounds> {
        var y = 0f
        return heights.mapIndexed { index, (id, height) ->
            FlowRowBounds(id, documentIndex = index, top = y, bottom = y + height).also { y += height }
        }
    }

    /**
     * Placement is resolved against absolute document indices, so these tests address the source the way the
     * screen does — by id — and convert here rather than hard-coding ordinals that would silently stop matching
     * the rows they refer to.
     */
    private fun List<FlowRowBounds>.indexOfId(blockId: String): Int =
        indexOfFirst { it.blockId == blockId }

    /**
     * The defect this replaces divided the drag distance by a fixed 48dp pitch. With mixed row heights that
     * arithmetic lands somewhere unrelated to the pointer; measured bounds land where the finger is.
     */
    @Test
    fun dragGeometry_resolvesFromMeasuredBoundsNotAFixedPitch() {
        val measured = rows("a" to 40f, "b" to 300f, "c" to 40f)
        // Pointer 200px down from the top of a 40px row: a 48dp-pitch guess computes four rows of travel; the
        // real geometry says this is still inside row "b".
        val placement = FlowDragGeometry.placementFor(measured, measured.indexOfId("a"), pointerY = 200f)
        assertNotNull(placement)
        assertEquals("b", placement!!.referenceId)
    }

    @Test
    fun dragGeometry_aboveTheMidpointPlacesBefore() {
        val measured = rows("a" to 100f, "b" to 100f, "c" to 100f)
        val placement = FlowDragGeometry.placementFor(measured, measured.indexOfId("c"), pointerY = 120f)!!
        assertEquals(FlowDropEdge.BEFORE, placement.edge)
        assertEquals(1, placement.index)
        assertEquals(100f, placement.markerY, 0.01f)
    }

    @Test
    fun dragGeometry_belowTheMidpointPlacesAfter() {
        val measured = rows("a" to 100f, "b" to 100f, "c" to 100f)
        val placement = FlowDragGeometry.placementFor(measured, measured.indexOfId("a"), pointerY = 180f)!!
        assertEquals(FlowDropEdge.AFTER, placement.edge)
        assertEquals(2, placement.index)
        assertEquals(200f, placement.markerY, 0.01f)
    }

    /** Every pointer position resolves to exactly one boundary — no gap where placement is undefined. */
    @Test
    fun dragGeometry_coversTheWholeAxis() {
        val measured = rows("a" to 50f, "b" to 50f, "c" to 50f)
        (-40..200 step 5).forEach { y ->
            assertNotNull("No placement at y=$y", FlowDragGeometry.placementFor(measured, measured.indexOfId("b"), y.toFloat()))
        }
    }

    @Test
    fun dragGeometry_clampsPastEitherEnd() {
        val measured = rows("a" to 50f, "b" to 50f)
        assertEquals(0, FlowDragGeometry.placementFor(measured, measured.indexOfId("b"), -500f)!!.index)
        assertEquals(2, FlowDragGeometry.placementFor(measured, measured.indexOfId("a"), 5000f)!!.index)
    }

    /** Either boundary touching the dragged block is its own position: a no-op, and marked as one. */
    @Test
    fun dragGeometry_marksTheSourcePositionAsANoOp() {
        val measured = rows("a" to 100f, "b" to 100f, "c" to 100f)
        assertTrue(FlowDragGeometry.placementFor(measured, measured.indexOfId("b"), 120f)!!.isNoOp)
        assertTrue(FlowDragGeometry.placementFor(measured, measured.indexOfId("b"), 180f)!!.isNoOp)
        assertFalse(FlowDragGeometry.placementFor(measured, measured.indexOfId("b"), 20f)!!.isNoOp)
    }

    @Test
    fun dragGeometry_returnsNullWithNothingToPlaceAgainst() {
        assertNull(FlowDragGeometry.placementFor(emptyList(), 0, 10f))
    }

    /**
     * The index the geometry reports is the one `DropTarget.Into` resolves against, so committing a downward
     * placement genuinely moves the block. The old code needed a hand-written `+1` correction here; this
     * asserts the geometry already returns the right number.
     */
    @Test
    fun dragGeometry_indexCommitsAsARealMoveDownward() {
        val runtime = runtimeOf(paragraphs("a", "b", "c"))
        val measured = rows("p0" to 100f, "p1" to 100f, "p2" to 100f)
        val placement = FlowDragGeometry.placementFor(measured, measured.indexOfId("p0"), 280f)!!

        runtime.beginDrag("p0")
        runtime.completeDrag(com.norfold.app.domain.DropTarget.Into(parentId = null, index = placement.index))

        assertEquals(
            listOf("b", "c", "a"),
            runtime.controller.document().blocks.map { it.plainText() },
        )
    }

    @Test
    fun dragGeometry_indexCommitsAsARealMoveUpward() {
        val runtime = runtimeOf(paragraphs("a", "b", "c"))
        val measured = rows("p0" to 100f, "p1" to 100f, "p2" to 100f)
        val placement = FlowDragGeometry.placementFor(measured, measured.indexOfId("p2"), 20f)!!

        runtime.beginDrag("p2")
        runtime.completeDrag(com.norfold.app.domain.DropTarget.Into(parentId = null, index = placement.index))

        assertEquals(
            listOf("c", "a", "b"),
            runtime.controller.document().blocks.map { it.plainText() },
        )
    }

    /** Cancel restores the exact pre-drag state — nothing in history, nothing moved. */
    @Test
    fun cancelDrag_leavesNoHistoryEntry() {
        val runtime = runtimeOf(paragraphs("a", "b"))
        val before = runtime.controller.document()
        val canUndo = runtime.controller.canUndo()

        runtime.beginDrag("p0")
        runtime.cancelDrag()

        assertEquals(before, runtime.controller.document())
        assertEquals(canUndo, runtime.controller.canUndo())
    }

    // ── Item 6: inline-literal slash suppression ────────────────────────────────────────────────────

    @Test
    fun literalInline_isTrueInsideInlineCode() {
        val block = ParagraphBlock(
            id = "p0",
            content = listOf(InlineText("run "), CodeInline("a/b")),
        )
        assertTrue(FlowInlineText.literalInlineAt(block, itemId = null, offset = 7))
    }

    @Test
    fun literalInline_isTrueInsideInlineMath() {
        val block = ParagraphBlock(id = "p0", content = listOf(MathInline("a/b")))
        assertTrue(FlowInlineText.literalInlineAt(block, itemId = null, offset = 2))
    }

    /** Bold and links contain ordinary prose, so a slash there should still open the menu. */
    @Test
    fun literalInline_isFalseInsideOrdinaryProseSpans() {
        val bold = ParagraphBlock(id = "p0", content = listOf(BoldInline(listOf(InlineText("hi")))))
        val link = ParagraphBlock(
            id = "p1",
            content = listOf(LinkInline("https://x", listOf(InlineText("hi")))),
        )
        assertFalse(FlowInlineText.literalInlineAt(bold, null, 2))
        assertFalse(FlowInlineText.literalInlineAt(link, null, 2))
    }

    @Test
    fun literalInline_isFalseAtTheStartOfTheLine() {
        val block = ParagraphBlock(id = "p0", content = listOf(CodeInline("a/b")))
        assertFalse(FlowInlineText.literalInlineAt(block, null, 0))
    }

    @Test
    fun literalInline_readsListItems() {
        val block = BulletListBlock(
            id = "b0",
            items = listOf(ListItem(id = "i0", content = listOf(CodeInline("a/b")))),
        )
        assertTrue(FlowInlineText.literalInlineAt(block, "i0", 2))
    }

    /**
     * The host must *supply* the fact, not merely have a helper that could compute it. This asserts the seam:
     * the runtime's own slash input carries it.
     */
    @Test
    fun slashInputFor_carriesTheLiteralInlineFactFromTheDocument() {
        val runtime = runtimeOf(
            BlockDocument(listOf(ParagraphBlock(id = "p0", content = listOf(CodeInline("cmd /h"))))),
        )
        val input = runtime.slashInputFor("p0", "cmd /h", caret = 6, itemId = null)
        assertTrue(input.literalInline)
    }

    /** And the resolver then keeps the menu shut, which is the user-visible half of the same fact. */
    @Test
    fun slashMenu_staysClosedForASlashInsideInlineCode() {
        val runtime = runtimeOf(
            BlockDocument(listOf(ParagraphBlock(id = "p0", content = listOf(CodeInline("cmd /h"))))),
        )
        runtime.setCaret("p0", 6)
        val session = runtime.onTextInput(
            "p0",
            runtime.slashInputFor("p0", "cmd /h", 6, null),
        )
        assertNull(session)
    }

    @Test
    fun slashMenu_opensForASlashInOrdinaryProse() {
        val runtime = runtimeOf(paragraphs("h"))
        runtime.setCaret("p0", 0)
        val session = runtime.onTextInput(
            "p0",
            runtime.slashInputFor("p0", "/h", 2, null),
        )
        assertNotNull(session)
    }

    // ── Item 7: table cell establishes complete command context ─────────────────────────────────────

    private fun tableDocument(): BlockDocument = BlockDocument(
        listOf(
            ParagraphBlock(id = "p0", content = listOf(InlineText("before"))),
            TableBlock(
                id = "t0",
                headers = listOf(TableCell(content = listOf(InlineText("H")))),
                rows = listOf(listOf(TableCell(content = listOf(InlineText("A"))))),
            ),
        ),
    )

    /**
     * A visibly selected cell while the command system thinks no table is selected is the defect. Addressing a
     * cell must make the table the primary structural target in the same step.
     */
    @Test
    fun setTableCursor_makesTheTableThePrimaryTarget() {
        val runtime = runtimeOf(tableDocument())
        runtime.setCaret("p0", 0)

        runtime.setTableCursor(FlowTableCursor("t0", row = 0, column = 0))

        assertEquals("t0", runtime.scope.primaryBlockId())
        assertEquals("t0", runtime.scope.caret()?.targetId)
    }

    @Test
    fun setTableCursor_makesDirectionalCommandsImmediatelyInvokable() {
        val runtime = runtimeOf(tableDocument())
        runtime.setCaret("p0", 0)
        val before = runtime.tableActions().filter { it.enabled }
        assertTrue("Directional commands need a reference cell", before.isEmpty())

        runtime.setTableCursor(FlowTableCursor("t0", row = 0, column = 0))

        val after = runtime.tableActions()
        assertTrue(after.isNotEmpty())
        assertTrue("Every table action should now resolve", after.all { it.enabled })
    }

    @Test
    fun setTableCursor_isSurfacedInTheSnapshotForHighlighting() {
        val runtime = runtimeOf(tableDocument())
        val cursor = FlowTableCursor("t0", row = -1, column = 0)
        runtime.setTableCursor(cursor)
        assertEquals(cursor, runtime.uiState().tableCursor)
    }

    @Test
    fun setTableCursor_ignoresACellInABlockThatDoesNotExist() {
        val runtime = runtimeOf(tableDocument())
        runtime.setTableCursor(FlowTableCursor("gone", row = 0, column = 0))
        assertNull(runtime.uiState().tableCursor)
    }

    // ── Item 14: accessibility resolves against the row operated on ─────────────────────────────────

    /**
     * The defect: the catalogue was resolved once against the global selection, and the callback then
     * re-pointed at whichever row was activated — so the announced availability described a different block.
     */
    @Test
    fun accessibilityActionsFor_describeTheRequestedRow() {
        val runtime = runtimeOf(paragraphs("first", "second"))
        runtime.scope.selectBlocks(listOf("p1"))

        val first = runtime.accessibilityActionsFor("p0")
        val second = runtime.accessibilityActionsFor("p1")

        val moveUpFirst = first.first { it.id == FlowIds.MOVE_UP }
        val moveUpSecond = second.first { it.id == FlowIds.MOVE_UP }
        assertFalse("The first block cannot move up", moveUpFirst.enabled)
        assertTrue("The second block can", moveUpSecond.enabled)
    }

    /** Reading the action list must not change what the user had selected. */
    @Test
    fun accessibilityActionsFor_restoresTheSelectionItBorrowed() {
        val runtime = runtimeOf(paragraphs("first", "second"))
        runtime.setCaret("p1", 3)
        val ids = runtime.scope.selectedBlockIds()
        val caret = runtime.scope.caret()

        runtime.accessibilityActionsFor("p0")

        assertEquals(ids, runtime.scope.selectedBlockIds())
        assertEquals(caret, runtime.scope.caret())
    }

    /** What was announced as available is what execution actually produces for that row. */
    @Test
    fun invokeAccessibilityOn_operatesOnTheRowTheActionsDescribed() {
        val runtime = runtimeOf(paragraphs("first", "second", "third"))
        runtime.scope.selectBlocks(listOf("p0"))

        val actions = runtime.accessibilityActionsFor("p2")
        assertTrue(actions.first { it.id == FlowIds.MOVE_UP }.enabled)
        runtime.invokeAccessibilityOn("p2", FlowIds.MOVE_UP)

        assertEquals(
            listOf("first", "third", "second"),
            runtime.controller.document().blocks.map { it.plainText() },
        )
    }

    /** Same registry command, same undo unit — accessibility is a surface, not a second implementation. */
    @Test
    fun invokeAccessibilityOn_isTheSameCommandAsTheDockRoute() {
        val viaA11y = runtimeOf(paragraphs("a", "b")).also {
            it.invokeAccessibilityOn("p1", FlowIds.MOVE_UP)
        }
        val viaDock = runtimeOf(paragraphs("a", "b")).also {
            it.scope.selectBlocks(listOf("p1"))
            it.invoke(FlowIds.MOVE_UP, CommandSurface.DOCK)
        }
        assertEquals(
            viaDock.controller.document().blocks.map { it.plainText() },
            viaA11y.controller.document().blocks.map { it.plainText() },
        )
    }

    /** A disabled action reports its reason, so a screen-reader user learns why rather than finding it missing. */
    @Test
    fun accessibilityActionsFor_keepsDisabledEntriesWithReasons() {
        val runtime = runtimeOf(paragraphs("only"))
        val moveUp = runtime.accessibilityActionsFor("p0").first { it.id == FlowIds.MOVE_UP }
        assertEquals(CommandState.DISABLED, moveUp.status.state)
        assertNotNull(moveUp.disabledReason)
        assertTrue(moveUp.accessibilityLabel().contains("unavailable"))
    }

    /** Android crashes accessibility-tree traversal when a single node exposes more than 32 actions. */
    @Test
    fun accessibilityActionsForNode_isBoundedAndKeepsContextualCommandsFirst() {
        val runtime = runtimeOf(paragraphs("first", "second"))

        val actions = runtime.accessibilityActionsForNode("p1")

        assertTrue(actions.size <= 16)
        assertEquals(FlowIds.MOVE_UP, actions.first().id)
        assertTrue(actions.none { it.id.startsWith(FlowIds.INSERT_PREFIX) && it.id != FlowIds.INSERT_ABOVE })
        assertTrue(actions.any { it.id == FlowIds.DELETE })
    }

    // ── Item 15: inline formatting is preserved and representable ───────────────────────────────────

    @Test
    fun runsOf_reportsNestedStylesInPlainTextOffsets() {
        val nodes = listOf(
            InlineText("a"),
            BoldInline(listOf(InlineText("b"), com.norfold.app.domain.ItalicInline(listOf(InlineText("c"))))),
            InlineText("d"),
        )
        val runs = FlowInlineText.runsOf(nodes)
        assertEquals("abcd", nodes.plainText())

        val bold = runs.first { it.style.bold && !it.style.italic }
        assertEquals(1, bold.start)
        assertEquals(2, bold.end)
        val both = runs.first { it.style.bold && it.style.italic }
        assertEquals(2, both.start)
        assertEquals(3, both.end)
    }

    /** Runs must tile the text exactly, or a span would be drawn over the wrong characters. */
    @Test
    fun runsOf_tilesTheWholeTextWithoutGapsOrOverlap() {
        val nodes = listOf(
            InlineText("plain "),
            BoldInline(listOf(InlineText("bold"))),
            CodeInline("code"),
            com.norfold.app.domain.HighlightInline("yellow", listOf(InlineText("hi"))),
        )
        val runs = FlowInlineText.runsOf(nodes)
        assertEquals(0, runs.first().start)
        assertEquals(nodes.plainText().length, runs.last().end)
        runs.zipWithNext { a, b -> assertEquals(a.end, b.start) }
    }

    @Test
    fun runsOf_carriesEveryInlineAttributeTheDocumentSupports() {
        val nodes = listOf(
            BoldInline(listOf(InlineText("b"))),
            com.norfold.app.domain.ItalicInline(listOf(InlineText("i"))),
            com.norfold.app.domain.UnderlineInline(listOf(InlineText("u"))),
            com.norfold.app.domain.StrikethroughInline(listOf(InlineText("s"))),
            com.norfold.app.domain.HighlightInline("green", listOf(InlineText("h"))),
            com.norfold.app.domain.FontFamilyInline("serif", listOf(InlineText("f"))),
            com.norfold.app.domain.FontSizeInline(22f, listOf(InlineText("z"))),
            LinkInline("https://x", listOf(InlineText("l"))),
            CodeInline("c"),
            MathInline("m"),
        )
        val styles = FlowInlineText.runsOf(nodes).map { it.style }
        assertTrue(styles.any { it.bold })
        assertTrue(styles.any { it.italic })
        assertTrue(styles.any { it.underline })
        assertTrue(styles.any { it.strikethrough })
        assertEquals("green", styles.firstNotNullOf { it.highlight.takeIf { c -> c == "green" } })
        assertEquals("serif", styles.firstNotNullOf { it.fontFamily })
        assertEquals(22f, styles.firstNotNullOf { it.fontSizeSp }, 0.01f)
        assertEquals("https://x", styles.firstNotNullOf { it.link })
        assertTrue(styles.any { it.code })
        assertTrue(styles.any { it.math })
        assertTrue("Every run here is styled", styles.none { it.isPlain })
    }

    /** Editing plain text must not strip inline metadata outside the edited range. */
    @Test
    fun editText_preservesInlineStylingOutsideTheEditedRange() {
        val runtime = runtimeOf(
            BlockDocument(
                listOf(
                    ParagraphBlock(
                        id = "p0",
                        content = listOf(
                            BoldInline(listOf(InlineText("bold"))),
                            InlineText(" tail"),
                        ),
                    ),
                ),
            ),
        )
        runtime.editText("p0", "bold tail", "bold tail!")

        val nodes = FlowInlineText.nodesOf(runtime.controller.document().findById("p0")!!)!!
        assertEquals("bold tail!", nodes.plainText())
        assertTrue(
            "The bold span must survive an edit elsewhere in the line",
            FlowInlineText.runsOf(nodes).any { it.style.bold && it.end == 4 },
        )
    }

    // ── Item 16: collapsed content is reachable ─────────────────────────────────────────────────────

    @Test
    fun toggleCollapsed_expandsAndCollapsesThroughHistory() {
        val runtime = runtimeOf(
            BlockDocument(
                listOf(
                    CalloutBlock(
                        id = "c0",
                        title = "Note",
                        collapsed = true,
                        children = listOf(ParagraphBlock(id = "inner", content = listOf(InlineText("hidden")))),
                    ),
                ),
            ),
        )

        assertTrue(runtime.toggleCollapsed("c0"))
        assertFalse((runtime.controller.document().findById("c0") as CalloutBlock).collapsed)

        assertTrue(runtime.undo())
        assertTrue((runtime.controller.document().findById("c0") as CalloutBlock).collapsed)
    }

    @Test
    fun toggleCollapsed_ignoresBlocksThatDoNotCollapse() {
        val runtime = runtimeOf(paragraphs("alpha"))
        assertFalse(runtime.toggleCollapsed("p0"))
    }

    /** Nested content must be addressable by the same structural commands the user can see it under. */
    @Test
    fun nestedContent_isReachableByTheStructuralCommands() {
        val runtime = runtimeOf(
            BlockDocument(
                listOf(
                    CalloutBlock(
                        id = "c0",
                        title = "Note",
                        children = listOf(ParagraphBlock(id = "inner", content = listOf(InlineText("nested")))),
                    ),
                ),
            ),
        )
        runtime.setCaret("inner", 0)
        assertEquals("inner", runtime.scope.primaryBlockId())
        assertTrue(runtime.statusOf(FlowIds.UNNEST, CommandSurface.DOCK).isInvokable)
    }

    // ── Item 5: multi-select suppresses text interaction ────────────────────────────────────────────

    /** In selection mode the caret must not be re-pointed by a row tap; the tap toggles the block. */
    @Test
    fun selectionMode_togglingDoesNotDisturbTheCaret() {
        val runtime = runtimeOf(paragraphs("a", "b", "c"))
        runtime.setCaret("p0", 1)
        val caret = runtime.scope.caret()

        runtime.enterSelectionMode("p0")
        runtime.toggleSelection("p1")
        runtime.toggleSelection("p2")

        assertEquals(caret, runtime.scope.caret())
        assertEquals(listOf("p0", "p1", "p2"), runtime.scope.selectedBlockIds())
    }

    @Test
    fun selectionMode_cancelRestoresThePreModeSelectionAndCaret() {
        val runtime = runtimeOf(paragraphs("a", "b"))
        runtime.setCaret("p1", 1)
        val ids = runtime.scope.selectedBlockIds()
        val caret = runtime.scope.caret()

        runtime.enterSelectionMode("p0")
        runtime.toggleSelection("p1")
        runtime.cancelSelectionMode()

        assertEquals(ids, runtime.scope.selectedBlockIds())
        assertEquals(caret, runtime.scope.caret())
    }

    @Test
    fun selectionMode_doneRetainsTheSelection() {
        val runtime = runtimeOf(paragraphs("a", "b"))
        runtime.enterSelectionMode("p0")
        runtime.toggleSelection("p1")
        runtime.finishSelectionMode()

        assertFalse(runtime.selectionMode)
        assertEquals(listOf("p0", "p1"), runtime.scope.selectedBlockIds())
    }

    /** Setting a caret while in selection mode must not collapse the multi-selection to one block. */
    @Test
    fun selectionMode_caretDoesNotCollapseTheSelection() {
        val runtime = runtimeOf(paragraphs("a", "b"))
        runtime.enterSelectionMode("p0")
        runtime.toggleSelection("p1")

        runtime.setCaret("p0", 0)

        assertEquals(listOf("p0", "p1"), runtime.scope.selectedBlockIds())
    }
}
