package com.norfold.app.editor.flow

import com.norfold.app.domain.BlockDocument
import com.norfold.app.domain.BlockDocumentJson
import com.norfold.app.domain.BulletListBlock
import com.norfold.app.domain.ContainerAxis
import com.norfold.app.domain.ContainerBlock
import com.norfold.app.domain.DocumentBlock
import com.norfold.app.domain.DropTarget
import com.norfold.app.domain.Edge
import com.norfold.app.domain.HeadingBlock
import com.norfold.app.domain.ImageBlock
import com.norfold.app.domain.InlineText
import com.norfold.app.domain.ListItem
import com.norfold.app.domain.NumberedListBlock
import com.norfold.app.domain.ParagraphBlock
import com.norfold.app.domain.TableBlock
import com.norfold.app.domain.TableCell
import com.norfold.app.domain.TodoListBlock
import com.norfold.app.domain.UnknownBlock
import com.norfold.app.domain.findById
import com.norfold.app.editor.command.CommandResult
import com.norfold.app.editor.command.CommandState
import com.norfold.app.editor.command.CommandSurface
import com.norfold.app.editor.history.EditTarget
import com.norfold.app.editor.selection.TextCaret
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 1 (slice 2) coverage for the Flow command catalogue.
 *
 * What these tests actually protect, beyond "the command changed the document":
 *  - **one command == one undo unit** (§4.14, §13.1) — every structural command is checked against
 *    [FlowEditController.undoDepth], because the whole point of Phase 1 deliverable 9 is that Flow
 *    mutations become canonical transactions rather than snapshot-deque pushes;
 *  - **honest eligibility and counts** (§4.2, §4.10) — hidden vs disabled-with-a-reason vs active vs mixed
 *    are asserted, and `affectedCount` is asserted against the real number of changed blocks;
 *  - **deterministic post-command selection** (§4.14) — the Selection Engine's resulting primary is asserted;
 *  - **directional table structure** (§4.15) — no command guesses "the end of the table";
 *  - **unknown-payload safety** (§17.1, R5) — an [UnknownBlock] is never offered text/conversion commands
 *    and survives an edit cycle byte-for-byte.
 */
class FlowCommandsTest {

    private fun para(id: String, text: String = "") =
        ParagraphBlock(id = id, content = if (text.isEmpty()) emptyList() else listOf(InlineText(text)))

    private fun doc(vararg blocks: DocumentBlock) = BlockDocument(blocks.toList())

    private fun scopeOf(vararg blocks: DocumentBlock): FlowCommandScope =
        FlowCommandScope(FlowEditController(EditTarget("note:cmd"), doc(*blocks)))

    private fun FlowCommandScope.registry() = FlowCommands.registry(this)

    private fun FlowCommandScope.run(commandId: String): CommandResult =
        registry().execute(commandId, commandContext())

    private fun FlowCommandScope.stateOf(commandId: String): CommandState {
        val command = requireNotNull(registry().get(commandId)) { "No command '$commandId'" }
        return command.status(commandContext()).state
    }

    private fun FlowCommandScope.ids() = document().blocks.map { it.id }

    // ── Catalogue integrity ─────────────────────────────────────────────────────────────────────────

    @Test fun catalogue_hasUniqueIds_andRegistersCleanly() {
        val scope = scopeOf(para("a"))
        val catalogue = FlowCommands.catalogue(scope)
        val ids = catalogue.map { it.id }
        assertEquals("Command ids must be unique", ids.size, ids.toSet().size)
        // register() throws on a duplicate id, so a clean build is itself the assertion.
        assertEquals(catalogue.size, scope.registry().size)
    }

    @Test fun everyAlias_resolvesToExactlyOneCommand() {
        val scope = scopeOf(para("a"))
        val catalogue = FlowCommands.catalogue(scope)
        val owners = HashMap<String, MutableList<String>>()
        catalogue.forEach { cmd ->
            cmd.aliases.forEach { alias -> owners.getOrPut(alias.lowercase()) { mutableListOf() } += cmd.id }
        }
        val ambiguous = owners.filterValues { it.size > 1 }
        assertTrue("Aliases must not be ambiguous for the slash resolver: $ambiguous", ambiguous.isEmpty())
    }

    @Test fun slashStyleLookup_findsInsertAndConvertSeparately() {
        val registry = scopeOf(para("a")).registry()
        assertEquals("flow.insert.heading1", registry.resolve("h1")?.id)
        assertEquals("flow.convert.heading1", registry.resolve("turn into h1")?.id)
        assertEquals("flow.insert.bulletList", registry.resolve("bullet")?.id)
    }

    // ── Insertion ───────────────────────────────────────────────────────────────────────────────────

    @Test fun insert_addsBlockAfterPrimary_placesCaret_andIsOneUndoUnit() {
        val scope = scopeOf(para("a", "one"), para("b", "two"))
        scope.selectBlocks(listOf("a"))

        val result = scope.run("flow.insert.heading2")
        assertTrue(result is CommandResult.Success)

        val ids = scope.ids()
        assertEquals(3, ids.size)
        assertEquals("Inserted directly after the primary", listOf("a", ids[1], "b"), ids)
        val inserted = scope.document().findById(ids[1])
        assertTrue(inserted is HeadingBlock && inserted.level == 2)
        assertEquals("Caret lands inside the new block", ids[1], scope.caret()?.targetId)
        assertEquals(1, scope.controller.undoDepth())

        scope.controller.undo()
        assertEquals(listOf("a", "b"), scope.ids())
    }

    @Test fun insert_worksWithNoSelection_andIsNeverHidden() {
        val scope = scopeOf(para("a", "one"))
        assertEquals(CommandState.AVAILABLE, scope.stateOf("flow.insert.divider"))
        assertTrue(scope.run("flow.insert.divider") is CommandResult.Success)
        assertEquals(2, scope.document().blocks.size)
    }

    @Test fun insertAbove_placesBlockBeforePrimary() {
        val scope = scopeOf(para("a"), para("b"))
        scope.selectBlocks(listOf("b"))
        scope.run("flow.insert.above")
        val ids = scope.ids()
        assertEquals(3, ids.size)
        assertEquals(listOf("a", ids[1], "b"), ids)
    }

    @Test fun insertAbove_atDocumentStart_prepends() {
        val scope = scopeOf(para("a"))
        scope.selectBlocks(listOf("a"))
        scope.run("flow.insert.above")
        assertEquals("New block precedes the only block", "a", scope.ids().last())
        assertEquals(2, scope.ids().size)
    }

    @Test fun insertTable_producesAUsableGrid() {
        val scope = scopeOf(para("a"))
        scope.selectBlocks(listOf("a"))
        scope.run("flow.insert.table")
        val table = scope.document().blocks.last() as TableBlock
        assertEquals(2, table.headers.size)
        assertEquals(1, table.rows.size)
        assertEquals(2, table.rows.first().size)
    }

    // ── Conversion ──────────────────────────────────────────────────────────────────────────────────

    @Test fun convert_preservesIdAndText_asOneUndoUnit() {
        val scope = scopeOf(para("a", "hello"))
        scope.selectBlocks(listOf("a"))

        val result = scope.run("flow.convert.heading1")
        assertEquals(CommandResult.Success(affectedCount = 1), result)

        val block = scope.document().findById("a")
        assertTrue(block is HeadingBlock && block.level == 1)
        assertEquals("hello", block!!.plainText())
        assertEquals(1, scope.controller.undoDepth())

        scope.controller.undo()
        assertTrue(scope.document().findById("a") is ParagraphBlock)
    }

    @Test fun convert_reportsActiveWhenAlreadyThatKind_andNoOpsOnExecute() {
        val scope = scopeOf(HeadingBlock(id = "a", level = 1, content = listOf(InlineText("x"))))
        scope.selectBlocks(listOf("a"))
        assertEquals(CommandState.ACTIVE, scope.stateOf("flow.convert.heading1"))
        assertEquals(CommandResult.NoOp, scope.run("flow.convert.heading1"))
        assertEquals("A no-op conversion commits no transaction", 0, scope.controller.undoDepth())
    }

    @Test fun convert_reportsMixedWhenSelectionIsPartiallyThatKind() {
        val scope = scopeOf(HeadingBlock(id = "a", level = 1), para("b", "x"))
        scope.selectBlocks(listOf("a", "b"))
        assertEquals(CommandState.MIXED, scope.stateOf("flow.convert.heading1"))
    }

    @Test fun convert_affectsOnlyEligibleBlocks_andReportsHonestCount() {
        val scope = scopeOf(para("a", "x"), ImageBlock(id = "img", source = "file://x"), para("c", "y"))
        scope.selectBlocks(listOf("a", "img", "c"))

        val result = scope.run("flow.convert.quote")
        assertEquals("Only the two convertible blocks changed", CommandResult.Success(affectedCount = 2), result)
        assertTrue(scope.document().findById("img") is ImageBlock)
    }

    @Test fun convert_isDisabledWithAReason_whenNothingSelectedSupportsIt() {
        val scope = scopeOf(ImageBlock(id = "img", source = "file://x"))
        scope.selectBlocks(listOf("img"))
        val status = scope.registry().get("flow.convert.quote")!!.status(scope.commandContext())
        assertEquals(CommandState.DISABLED, status.state)
        assertNotNull("A disabled command explains itself", status.reason)
    }

    @Test fun convert_isHiddenWithNoSelection() {
        val scope = scopeOf(para("a"))
        assertEquals(CommandState.HIDDEN, scope.stateOf("flow.convert.quote"))
    }

    @Test fun convert_listToList_preservesItemIds() {
        val bullets = BulletListBlock(
            id = "a",
            items = listOf(
                ListItem(id = "i1", content = listOf(InlineText("one"))),
                ListItem(id = "i2", content = listOf(InlineText("two"))),
            ),
        )
        val scope = scopeOf(bullets)
        scope.selectBlocks(listOf("a"))

        scope.run("flow.convert.numberedList")
        val numbered = scope.document().findById("a") as NumberedListBlock
        assertEquals(
            "List item identity survives a list-to-list conversion",
            listOf("i1", "i2"),
            numbered.items.map { it.id },
        )

        scope.run("flow.convert.todoList")
        val todos = scope.document().findById("a") as TodoListBlock
        assertEquals(listOf("i1", "i2"), todos.items.map { it.id })
        assertEquals(listOf("one", "two"), todos.items.map { it.content.joinToString("") { n -> n.plainText() } })
    }

    // ── Duplicate / delete ──────────────────────────────────────────────────────────────────────────

    @Test fun duplicate_insertsFreshIdCopyBelow_andSelectsIt() {
        val scope = scopeOf(para("a", "one"), para("b", "two"))
        scope.selectBlocks(listOf("a"))
        scope.run("flow.duplicate")

        val ids = scope.ids()
        assertEquals(3, ids.size)
        assertEquals(listOf("a", ids[1], "b"), ids)
        assertEquals("Copy carries the same text", "one", scope.document().findById(ids[1])!!.plainText())
        assertEquals("The copy becomes the selection", ids[1], scope.selection.primary()?.id)
        assertEquals(1, scope.controller.undoDepth())
    }

    @Test fun duplicate_ofContainer_freshensNestedIdsToo() {
        val nested = ContainerBlock(id = "c1", axis = ContainerAxis.Row, children = listOf(para("x", "inner")), weights = listOf(1f))
        val scope = scopeOf(nested)
        scope.selectBlocks(listOf("c1"))
        scope.run("flow.duplicate")

        val copy = scope.document().blocks.last() as ContainerBlock
        assertFalse("Container copy has a fresh id", copy.id == "c1")
        assertFalse("Nested child copy has a fresh id", copy.children.first().id == "x")
        assertEquals("inner", copy.children.first().plainText())
    }

    @Test fun delete_removesSelection_andSelectsNearestSurvivor() {
        val scope = scopeOf(para("a"), para("b"), para("c"))
        scope.selectBlocks(listOf("b"))
        val result = scope.run("flow.delete")

        assertEquals(CommandResult.Success(affectedCount = 1), result)
        assertEquals(listOf("a", "c"), scope.ids())
        assertEquals("The following block becomes selected", "c", scope.selection.primary()?.id)
        assertEquals(1, scope.controller.undoDepth())
    }

    @Test fun delete_ofLastBlock_fallsBackToThePrecedingOne() {
        val scope = scopeOf(para("a"), para("b"))
        scope.selectBlocks(listOf("b"))
        scope.run("flow.delete")
        assertEquals("a", scope.selection.primary()?.id)
    }

    @Test fun delete_multiSelect_isOneTransaction() {
        val scope = scopeOf(para("a"), para("b"), para("c"))
        scope.selectBlocks(listOf("a", "b"))
        val result = scope.run("flow.delete")
        assertEquals(CommandResult.Success(affectedCount = 2), result)
        assertEquals(listOf("c"), scope.ids())
        assertEquals("Two deletions, one undo unit", 1, scope.controller.undoDepth())

        scope.controller.undo()
        assertEquals(listOf("a", "b", "c"), scope.ids())
    }

    // ── Reordering ──────────────────────────────────────────────────────────────────────────────────

    @Test fun moveUpAndDown_reorderWithinTheSiblingList() {
        val scope = scopeOf(para("a"), para("b"), para("c"))
        scope.selectBlocks(listOf("b"))
        scope.run("flow.move.up")
        assertEquals(listOf("b", "a", "c"), scope.ids())

        scope.selectBlocks(listOf("b"))
        scope.run("flow.move.down")
        assertEquals(listOf("a", "b", "c"), scope.ids())
    }

    @Test fun move_isDisabledWithAReasonAtTheBoundaries() {
        val scope = scopeOf(para("a"), para("b"))
        scope.selectBlocks(listOf("a"))
        val up = scope.registry().get("flow.move.up")!!.status(scope.commandContext())
        assertEquals(CommandState.DISABLED, up.state)
        assertEquals("Already first", up.reason)
        assertEquals(CommandState.AVAILABLE, scope.stateOf("flow.move.down"))

        scope.selectBlocks(listOf("b"))
        assertEquals(CommandState.DISABLED, scope.stateOf("flow.move.down"))
    }

    @Test fun moveDown_insideAContainer_staysInsideTheContainer() {
        val container = ContainerBlock(
            id = "c1",
            axis = ContainerAxis.Column,
            children = listOf(para("x"), para("y")),
            weights = listOf(1f, 1f),
        )
        val scope = scopeOf(container, para("tail"))
        scope.selectBlocks(listOf("x"))
        scope.run("flow.move.down")

        val updated = scope.document().findById("c1") as ContainerBlock
        assertEquals("Reorder happened inside the container", listOf("y", "x"), updated.children.map { it.id })
        assertEquals("Top level is untouched", listOf("c1", "tail"), scope.ids())
    }

    @Test fun drop_isHiddenUntilTheHostResolvesADropTarget() {
        val scope = scopeOf(para("a"), para("b"))
        scope.selectBlocks(listOf("a"))
        assertEquals(CommandState.HIDDEN, scope.stateOf("flow.move.drop"))

        scope.pendingDrop = DropTarget.Into(null, 2)
        assertEquals(CommandState.AVAILABLE, scope.stateOf("flow.move.drop"))
    }

    @Test fun drop_appliesAnEdgeSplit_asOneTransaction() {
        val scope = scopeOf(para("a"), para("b"))
        scope.selectBlocks(listOf("a"))
        scope.pendingDrop = DropTarget.Split("b", Edge.Right)

        val result = scope.run("flow.move.drop")
        assertTrue(result is CommandResult.Success)

        val blocks = scope.document().blocks
        assertEquals(1, blocks.size)
        val container = blocks.single() as ContainerBlock
        assertEquals(ContainerAxis.Row, container.axis)
        assertEquals(listOf("b", "a"), container.children.map { it.id })
        assertEquals(1, scope.controller.undoDepth())
        assertNull("The drop is consumed exactly once", scope.pendingDrop)
    }

    // ── Nesting & containers ────────────────────────────────────────────────────────────────────────

    @Test fun nest_movesBlockIntoThePrecedingContainer() {
        val container = ContainerBlock(id = "c1", children = listOf(para("x")), weights = listOf(1f))
        val scope = scopeOf(container, para("b", "moved"))
        scope.selectBlocks(listOf("b"))
        assertEquals(CommandState.AVAILABLE, scope.stateOf("flow.nest"))

        scope.run("flow.nest")
        val updated = scope.document().findById("c1") as ContainerBlock
        assertEquals(listOf("x", "b"), updated.children.map { it.id })
        assertEquals(listOf("c1"), scope.ids())
    }

    @Test fun nest_isDisabledWhenNothingAboveIsAContainer() {
        val scope = scopeOf(para("a"), para("b"))
        scope.selectBlocks(listOf("b"))
        val status = scope.registry().get("flow.nest")!!.status(scope.commandContext())
        assertEquals(CommandState.DISABLED, status.state)
        assertEquals("No container directly above", status.reason)
    }

    @Test fun unnest_hoistsANestedBlockBackToTopLevel() {
        val container = ContainerBlock(id = "c1", children = listOf(para("x"), para("y")), weights = listOf(1f, 1f))
        val scope = scopeOf(container)
        scope.selectBlocks(listOf("y"))
        assertEquals(CommandState.AVAILABLE, scope.stateOf("flow.unnest"))

        scope.run("flow.unnest")
        assertEquals(listOf("c1", "y"), scope.ids())
    }

    @Test fun unnest_isHiddenForATopLevelBlock() {
        val scope = scopeOf(para("a"))
        scope.selectBlocks(listOf("a"))
        assertEquals(
            "A top-level block offers no FRAMING capability, so un-nesting is not shown",
            CommandState.HIDDEN,
            scope.stateOf("flow.unnest"),
        )
    }

    @Test fun wrapThenUnwrap_roundTripsTheDocument() {
        val scope = scopeOf(para("a", "one"), para("b", "two"))
        val before = BlockDocumentJson.encodeDocumentPayload(scope.document())

        scope.selectBlocks(listOf("a"))
        scope.run("flow.wrap.row")
        val container = scope.document().blocks.first()
        assertTrue(container is ContainerBlock && container.axis == ContainerAxis.Row)
        assertEquals("The new container becomes the selection", container.id, scope.selection.primary()?.id)

        scope.run("flow.unwrap")
        assertEquals(
            "Wrap then unwrap restores the exact document",
            before,
            BlockDocumentJson.encodeDocumentPayload(scope.document()),
        )
        assertEquals(2, scope.controller.undoDepth())
    }

    @Test fun unwrap_isDisabledOnANonContainer() {
        val scope = scopeOf(para("a"))
        scope.selectBlocks(listOf("a"))
        val status = scope.registry().get("flow.unwrap")!!.status(scope.commandContext())
        assertEquals(CommandState.DISABLED, status.state)
        assertEquals("Not a container", status.reason)
    }

    // ── Split / merge ───────────────────────────────────────────────────────────────────────────────

    @Test fun split_dividesTheBlockAtTheCaret() {
        val scope = scopeOf(para("a", "hello"))
        scope.selectBlocks(listOf("a"))
        scope.selection.setCaret(TextCaret("a", 2))

        assertEquals(CommandState.AVAILABLE, scope.stateOf("flow.split"))
        scope.run("flow.split")

        val blocks = scope.document().blocks
        assertEquals(2, blocks.size)
        assertEquals("he", blocks[0].plainText())
        assertEquals("llo", blocks[1].plainText())
        assertEquals("Caret lands at the start of the new block", blocks[1].id, scope.caret()?.targetId)
        assertEquals(1, scope.controller.undoDepth())
    }

    @Test fun split_isDisabledWithoutACaretInTheBlock() {
        val scope = scopeOf(para("a", "hello"))
        scope.selectBlocks(listOf("a"))
        val status = scope.registry().get("flow.split")!!.status(scope.commandContext())
        assertEquals(CommandState.DISABLED, status.state)
        assertEquals("Place the caret where the block should split", status.reason)
    }

    @Test fun mergePrevious_joinsIntoThePrecedingBlock() {
        val scope = scopeOf(para("a", "he"), para("b", "llo"))
        scope.selectBlocks(listOf("b"))
        scope.run("flow.merge.previous")

        assertEquals(listOf("a"), scope.ids())
        assertEquals("hello", scope.document().findById("a")!!.plainText())
        assertEquals("Caret lands at the join point", 2, scope.caret()?.offset)
        assertEquals(1, scope.controller.undoDepth())
    }

    @Test fun mergePrevious_isDisabledOnTheFirstBlock() {
        val scope = scopeOf(para("a", "one"), para("b", "two"))
        scope.selectBlocks(listOf("a"))
        val status = scope.registry().get("flow.merge.previous")!!.status(scope.commandContext())
        assertEquals(CommandState.DISABLED, status.state)
        assertEquals("Nothing above to merge into", status.reason)
    }

    // ── Directional table hooks (§4.15) ─────────────────────────────────────────────────────────────

    private fun tableScope(): FlowCommandScope {
        val table = TableBlock(
            id = "t",
            headers = listOf(TableCell(listOf(InlineText("H1"))), TableCell(listOf(InlineText("H2")))),
            rows = listOf(
                listOf(TableCell(listOf(InlineText("r0c0"))), TableCell(listOf(InlineText("r0c1")))),
                listOf(TableCell(listOf(InlineText("r1c0"))), TableCell(listOf(InlineText("r1c1")))),
            ),
        )
        return scopeOf(table).also { it.selectBlocks(listOf("t")) }
    }

    private fun FlowCommandScope.table() = document().findById("t") as TableBlock

    @Test fun tableCommands_areDisabledUntilACellIsAddressed() {
        val scope = tableScope()
        val ids = listOf(
            "flow.table.insertRowAbove",
            "flow.table.insertRowBelow",
            "flow.table.insertColumnLeft",
            "flow.table.insertColumnRight",
        )
        assertEquals("Exactly four directional hooks exist", ids.size, FlowTableDirection.entries.size)
        ids.forEach { id ->
            val status = scope.registry().get(id)!!.status(scope.commandContext())
            assertEquals("$id must not guess a position", CommandState.DISABLED, status.state)
            assertEquals("Place the cursor in a table cell first", status.reason)
        }
    }

    @Test fun insertRowAbove_and_below_areDirectional() {
        val scope = tableScope()
        scope.tableCursor = FlowTableCursor("t", row = 1, column = 0)

        scope.run("flow.table.insertRowAbove")
        var rows = scope.table().rows
        assertEquals(3, rows.size)
        assertEquals("New row sits above row 1", "", rows[1][0].content.joinToString("") { it.plainText() })
        assertEquals("r1c0", rows[2][0].content.joinToString("") { it.plainText() })

        scope.tableCursor = FlowTableCursor("t", row = 0, column = 0)
        scope.run("flow.table.insertRowBelow")
        rows = scope.table().rows
        assertEquals(4, rows.size)
        assertEquals("New row sits below row 0", "", rows[1][0].content.joinToString("") { it.plainText() })
        assertEquals("Each insertion is one undo unit", 2, scope.controller.undoDepth())
    }

    @Test fun insertRowAbove_isRefusedOnTheHeaderRow() {
        val scope = tableScope()
        scope.tableCursor = FlowTableCursor("t", row = -1, column = 0)
        val status = scope.registry().get("flow.table.insertRowAbove")!!.status(scope.commandContext())
        assertEquals(CommandState.DISABLED, status.state)
        assertEquals("The header row is always first", status.reason)
    }

    @Test fun insertRowBelow_fromTheHeader_addsTheFirstDataRow() {
        val scope = tableScope()
        scope.tableCursor = FlowTableCursor("t", row = -1, column = 0)
        scope.run("flow.table.insertRowBelow")
        val rows = scope.table().rows
        assertEquals(3, rows.size)
        assertEquals("", rows[0][0].content.joinToString("") { it.plainText() })
        assertEquals("r0c0", rows[1][0].content.joinToString("") { it.plainText() })
    }

    @Test fun insertColumnLeft_and_right_areDirectional_andKeepTheGridRectangular() {
        val scope = tableScope()
        scope.tableCursor = FlowTableCursor("t", row = 0, column = 1)

        scope.run("flow.table.insertColumnLeft")
        var table = scope.table()
        assertEquals(3, table.headers.size)
        assertEquals("", table.headers[1].content.joinToString("") { it.plainText() })
        assertEquals("H2", table.headers[2].content.joinToString("") { it.plainText() })
        assertTrue("Every row keeps the header's width", table.rows.all { it.size == 3 })

        // The cursor followed the inserted column, so "right" now inserts after it.
        scope.run("flow.table.insertColumnRight")
        table = scope.table()
        assertEquals(4, table.headers.size)
        assertTrue(table.rows.all { it.size == 4 })
    }

    @Test fun tableCommands_areHiddenWhenTheSelectionIsNotATable() {
        val scope = scopeOf(para("a"))
        scope.selectBlocks(listOf("a"))
        assertEquals(CommandState.HIDDEN, scope.stateOf("flow.table.insertRowBelow"))
    }

    // ── Unknown payload safety (§17.1, R5) ──────────────────────────────────────────────────────────

    @Test fun unknownBlock_isNeverOfferedTextOrConversionCommands() {
        val raw = """{"v":999,"block":{"kind":"quantum","id":"u1","payload":42}}"""
        val unknown = BlockDocumentJson.decodeBlock(raw)
        assertTrue(unknown is UnknownBlock)

        val scope = scopeOf(para("a"), unknown)
        scope.selectBlocks(listOf("u1"))

        assertEquals(CommandState.DISABLED, scope.stateOf("flow.convert.paragraph"))
        assertEquals(CommandState.HIDDEN, scope.stateOf("flow.split"))
        assertEquals("It can still be reordered and removed", CommandState.AVAILABLE, scope.stateOf("flow.move.up"))
        assertEquals(CommandState.AVAILABLE, scope.stateOf("flow.duplicate"))
    }

    @Test fun unknownBlock_survivesACommandCycle_verbatim() {
        val raw = """{"v":999,"block":{"kind":"quantum","id":"u1","payload":42}}"""
        val unknown = BlockDocumentJson.decodeBlock(raw)
        val scope = scopeOf(para("a", "one"), unknown)

        scope.selectBlocks(listOf("a"))
        scope.run("flow.convert.heading1")
        scope.selectBlocks(listOf("u1"))
        scope.run("flow.move.up")
        scope.controller.undo()

        val preserved = scope.document().findById("u1")!!
        assertEquals(raw, BlockDocumentJson.encodeBlock(preserved))
    }
}
