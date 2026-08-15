package com.norfold.app.editor.flow

import com.norfold.app.domain.BlockDocument
import com.norfold.app.domain.BulletListBlock
import com.norfold.app.domain.CodeBlock
import com.norfold.app.domain.DocumentBlock
import com.norfold.app.domain.HeadingBlock
import com.norfold.app.domain.InlineText
import com.norfold.app.domain.ListItem
import com.norfold.app.domain.MathBlock
import com.norfold.app.domain.MermaidBlock
import com.norfold.app.domain.ParagraphBlock
import com.norfold.app.domain.TodoItem
import com.norfold.app.domain.TodoListBlock
import com.norfold.app.domain.findById
import com.norfold.app.editor.command.CommandResult
import com.norfold.app.editor.command.CommandState
import com.norfold.app.editor.history.EditTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 1 (slice 3) coverage for the Slash Local Command Resolver.
 *
 * The two locked properties under test:
 *  - **the typed `/query` is the search** — no second field, so resolution is a pure function of the block
 *    text and caret, and the results carry the same [com.norfold.app.editor.command.CommandStatus] the dock
 *    would show;
 *  - **slash is suppressed where `/` is content** — URLs, paths, dates, fractions, code, equations and
 *    diagrams. The suppression cases are the bulk of these tests because a false positive here corrupts
 *    ordinary typing.
 *
 * [FlowSlashResolver.accept] is checked to be exactly one undo unit that restores the typed text — the
 * regression that would otherwise silently eat a user's `/`.
 */
class FlowSlashResolverTest {

    private fun para(id: String, text: String) =
        ParagraphBlock(id = id, content = listOf(InlineText(text)))

    private fun setup(vararg blocks: DocumentBlock): Pair<FlowCommandScope, FlowSlashResolver> {
        val scope = FlowCommandScope(FlowEditController(EditTarget("note:slash"), BlockDocument(blocks.toList())))
        return scope to FlowSlashResolver(scope, FlowCommands.registry(scope))
    }

    private fun input(text: String, caret: Int = text.length, context: FlowSlashContext = FlowSlashContext.PROSE) =
        FlowSlashInput(text = text, caret = caret, context = context)

    // ── Detection ───────────────────────────────────────────────────────────────────────────────────

    @Test fun detects_aBareSlashAtTheStartOfABlock() {
        val (_, resolver) = setup(para("a", "/"))
        assertEquals(FlowSlashQuery(triggerOffset = 0, query = ""), resolver.detect(input("/")))
    }

    @Test fun detects_anIndentedSlash_andCapturesTheQuery() {
        val (_, resolver) = setup(para("a", "  /head"))
        val query = resolver.detect(input("  /head"))
        assertEquals(2, query?.triggerOffset)
        assertEquals("head", query?.query)
        assertEquals("endOffset spans the whole trigger", 7, query?.endOffset)
    }

    @Test fun endOffset_erasesExactlyTheTrigger() {
        val text = "  /head"
        val (_, resolver) = setup(para("a", text))
        val query = resolver.detect(input(text))!!
        assertEquals("  ", text.removeRange(query.triggerOffset, query.endOffset))
    }

    @Test fun tracksTheCaret_notTheEndOfTheText() {
        val (_, resolver) = setup(para("a", "/head and more"))
        val query = resolver.detect(input("/head and more", caret = 5))
        assertEquals("Only text up to the caret is the query", "head", query?.query)
    }

    @Test fun suppresses_aSlashAfterOrdinaryProse() {
        val (_, resolver) = setup(para("a", "write /heading"))
        assertNull(resolver.detect(input("write /heading")))
    }

    // ── Suppression: `/` as content ─────────────────────────────────────────────────────────────────

    @Test fun suppressed_insideAUrl() {
        val (_, resolver) = setup(para("a", "x"))
        assertNull(resolver.detect(input("see https://example.com/docs")))
        assertNull(resolver.detect(input("see https:/", caret = 11)))
    }

    @Test fun suppressed_insideAFilesystemPath() {
        val (_, resolver) = setup(para("a", "x"))
        assertNull(resolver.detect(input("open src/main/kotlin")))
        assertNull(resolver.detect(input("open ./relative/path")))
    }

    @Test fun suppressed_insideADateOrFraction() {
        val (_, resolver) = setup(para("a", "x"))
        assertNull(resolver.detect(input("due 12/07/2026")))
        assertNull(resolver.detect(input("about 3/4 done")))
    }

    @Test fun suppressed_onADoubleSlash() {
        val (_, resolver) = setup(para("a", "x"))
        assertNull("// is how a user types a literal comment marker", resolver.detect(input("// note")))
    }

    @Test fun suppressed_insideCodeEquationAndDiagramBlocks() {
        val (_, resolver) = setup(CodeBlock(id = "c"), MathBlock(id = "m"), MermaidBlock(id = "d"))
        assertNull(resolver.detect(input("/head", context = FlowSlashContext.CODE)))
        assertNull(resolver.detect(input("/frac", context = FlowSlashContext.EQUATION)))
        assertNull(resolver.detect(input("/graph", context = FlowSlashContext.DIAGRAM)))
    }

    @Test fun suppressed_insideAnInlineLiteralSpan() {
        val (_, resolver) = setup(para("a", "x"))
        val inline = FlowSlashInput(text = "a /b", caret = 4, literalInline = true)
        assertNull("Inline code / math keeps the slash literal", resolver.detect(inline))
    }

    @Test fun suppressed_forALongQuery_orAcrossALineBreak() {
        val (_, resolver) = setup(para("a", "x"))
        val long = "/" + "x".repeat(FlowSlashResolver.MAX_QUERY_LENGTH + 1)
        assertNull(resolver.detect(input(long)))
        assertNull(resolver.detect(input("/head\nmore")))
    }

    @Test fun slashContext_derivesFromTheBlockKind() {
        assertEquals(FlowSlashContext.CODE, FlowSlashContext.of(CodeBlock()))
        assertEquals(FlowSlashContext.EQUATION, FlowSlashContext.of(MathBlock()))
        assertEquals(FlowSlashContext.DIAGRAM, FlowSlashContext.of(MermaidBlock()))
        assertEquals(FlowSlashContext.PROSE, FlowSlashContext.of(ParagraphBlock()))
        assertEquals(FlowSlashContext.PROSE, FlowSlashContext.of(HeadingBlock()))
    }

    // ── Results ─────────────────────────────────────────────────────────────────────────────────────

    @Test fun emptyQuery_listsACompactInsertPalette() {
        val (scope, resolver) = setup(para("a", "/"))
        scope.selectBlocks(listOf("a"))
        val session = resolver.resolve(input("/"))!!
        assertEquals(FlowSlashResolver.DEFAULT_LIMIT, session.results.size)
        assertTrue(
            "An empty query opens on insertion, like the + surface",
            session.results.all { it.command.id.startsWith("flow.insert.") },
        )
    }

    @Test fun query_ranksTheExactAliasFirst() {
        val (scope, resolver) = setup(para("a", "/h1"))
        scope.selectBlocks(listOf("a"))
        val session = resolver.resolve(input("/h1"))!!
        assertEquals("flow.insert.heading1", session.results.first().command.id)
    }

    @Test fun query_isPrefixMatched() {
        val (scope, resolver) = setup(para("a", "/bull"))
        scope.selectBlocks(listOf("a"))
        val session = resolver.resolve(input("/bull"))!!
        assertEquals("flow.insert.bulletList", session.results.first().command.id)
    }

    @Test fun imageAlias_resolvesToThePickerBackedPhotoCommand() {
        val (scope, resolver) = setup(para("a", "/photo"))
        scope.selectBlocks(listOf("a"))
        val session = resolver.resolve(input("/photo"))!!
        assertEquals(FlowIds.INSERT_IMAGE, session.results.first().command.id)
    }

    @Test fun multiWordQuery_reachesConvertCommands() {
        val (scope, resolver) = setup(para("a", "/turn into quote"))
        scope.selectBlocks(listOf("a"))
        val session = resolver.resolve(input("/turn into quote"))!!
        assertEquals("flow.convert.quote", session.results.first().command.id)
    }

    @Test fun multiWordProse_closesTheMenuInsteadOfMatchingNothing() {
        val (scope, resolver) = setup(para("a", "x"))
        scope.selectBlocks(listOf("a"))
        assertNull(
            "A sentence that stops meaning a command stops being one",
            resolver.resolve(input("/ and then we left")),
        )
    }

    @Test fun results_carryTheSameStatusTheDockWouldShow() {
        val (scope, resolver) = setup(para("a", "one"), para("b", "two"))
        scope.selectBlocks(listOf("a"))
        val session = resolver.resolve(input("/move"))!!
        val moveUp = session.results.first { it.command.id == "flow.move.up" }
        assertEquals(CommandState.DISABLED, moveUp.status.state)
        assertEquals("Already first", moveUp.status.reason)
        val moveDown = session.results.first { it.command.id == "flow.move.down" }
        assertEquals(CommandState.AVAILABLE, moveDown.status.state)
    }

    @Test fun results_omitCommandsHiddenInThisContext() {
        val (scope, resolver) = setup(para("a", "one"))
        scope.selectBlocks(listOf("a"))
        val session = resolver.resolve(input("/table"))!!
        assertTrue(
            "Table structure commands are hidden outside a table",
            session.results.none { it.command.id.startsWith("flow.table.") },
        )
    }

    @Test fun results_respectTheLimit() {
        val (scope, resolver) = setup(para("a", "one"))
        scope.selectBlocks(listOf("a"))
        val session = resolver.resolve(input("/h"), limit = 3)!!
        assertEquals(3, session.results.size)
    }

    @Test fun session_exposesTheFirstResultAsTheInitialKeyboardSelection() {
        val (_, resolver) = setup(para("a", "/"))
        val session = resolver.resolve(input("/"))!!
        assertEquals(0, session.selectedIndex)
        assertEquals(session.results.first(), session.selected)
    }

    // ── Accept ──────────────────────────────────────────────────────────────────────────────────────

    @Test fun accept_erasesTheQueryAndRunsTheCommand_asOneUndoUnit() {
        val (scope, resolver) = setup(para("a", "/h1"))
        scope.selectBlocks(listOf("a"))
        val typed = input("/h1")
        val command = resolver.resolve(typed)!!.results.first().command
        assertEquals("flow.insert.heading1", command.id)

        val result = resolver.accept("a", typed, command)
        assertTrue(result is CommandResult.Success)

        assertEquals("The trigger text is gone", "", scope.document().findById("a")!!.plainText())
        assertEquals(2, scope.document().blocks.size)
        assertTrue(scope.document().blocks[1] is HeadingBlock)
        assertEquals("Erase + command is one undo step", 1, scope.controller.undoDepth())

        scope.controller.undo()
        assertEquals("Undo restores exactly what the user typed", "/h1", scope.document().findById("a")!!.plainText())
        assertEquals(1, scope.document().blocks.size)
    }

    @Test fun accept_convertsTheHostBlock_andStillRestoresTheTypedText() {
        val (scope, resolver) = setup(para("a", "/turn into h2"))
        scope.selectBlocks(listOf("a"))
        val typed = input("/turn into h2")
        val command = resolver.resolve(typed)!!.results.first().command
        assertEquals("flow.convert.heading2", command.id)

        resolver.accept("a", typed, command)
        val converted = scope.document().findById("a")
        assertTrue(converted is HeadingBlock && converted.level == 2)
        assertEquals("", converted!!.plainText())
        assertEquals(1, scope.controller.undoDepth())

        scope.controller.undo()
        val restored = scope.document().findById("a")
        assertTrue(restored is ParagraphBlock)
        assertEquals("/turn into h2", restored!!.plainText())
    }

    @Test fun accept_insideAListItem_erasesTheQueryFromThatItem() {
        val list = BulletListBlock(
            id = "l",
            items = listOf(
                ListItem(id = "i1", content = listOf(InlineText("first"))),
                ListItem(id = "i2", content = listOf(InlineText("/divider"))),
            ),
        )
        val (scope, resolver) = setup(list)
        scope.selectBlocks(listOf("l"))
        val typed = FlowSlashInput(text = "/divider", caret = 8, itemId = "i2")
        val command = resolver.resolve(typed)!!.results.first().command

        resolver.accept("l", typed, command)
        val updated = scope.document().findById("l") as BulletListBlock
        assertEquals("", updated.items[1].content.joinToString("") { it.plainText() })
        assertEquals("first", updated.items[0].content.joinToString("") { it.plainText() })
        assertEquals(1, scope.controller.undoDepth())
    }

    /**
     * A to-do list holds `TodoItem`, a bullet list holds `ListItem`, and they share no supertype. Routing every
     * item on `itemId != null` sent to-do text through `editListItem`, which found nothing and returned without
     * editing — so the typed `/query` stayed in the text while the command ran anyway, and the single undo step
     * recorded a document the user never saw.
     */
    @Test fun accept_insideATodoItem_erasesTheQueryFromThatItem() {
        val todo = TodoListBlock(
            id = "t",
            items = listOf(
                TodoItem(id = "i1", checked = true, content = listOf(InlineText("done"))),
                TodoItem(id = "i2", checked = false, content = listOf(InlineText("/divider"))),
            ),
        )
        val (scope, resolver) = setup(todo)
        scope.selectBlocks(listOf("t"))
        val typed = FlowSlashInput(text = "/divider", caret = 8, itemId = "i2")
        val command = resolver.resolve(typed)!!.results.first().command

        val result = resolver.accept("t", typed, command)
        assertTrue(result is CommandResult.Success)

        val updated = scope.document().findById("t") as TodoListBlock
        assertEquals("the trigger is erased from the to-do's own text", "", updated.items[1].plain())
        assertEquals("the sibling item is untouched", "done", updated.items[0].plain())
        assertEquals("checked state is content, not a casualty of the erase", true, updated.items[0].checked)
        assertEquals(false, updated.items[1].checked)
        assertEquals("Erase + command is one undo step", 1, scope.controller.undoDepth())
    }

    @Test fun accept_insideATodoItem_undoRestoresTheTypedTextAndTheCheckedState() {
        val todo = TodoListBlock(
            id = "t",
            items = listOf(
                TodoItem(id = "i1", checked = true, content = listOf(InlineText("done"))),
                TodoItem(id = "i2", checked = true, content = listOf(InlineText("/divider"))),
            ),
        )
        val (scope, resolver) = setup(todo)
        scope.selectBlocks(listOf("t"))
        val typed = FlowSlashInput(text = "/divider", caret = 8, itemId = "i2")
        val command = resolver.resolve(typed)!!.results.first().command

        resolver.accept("t", typed, command)
        // Asserted before undoing: without this the test passes vacuously against an erase that never
        // happened, because "undo restores the typed text" is trivially true when nothing removed it.
        val afterAccept = scope.document().findById("t") as TodoListBlock
        assertEquals("", afterAccept.items[1].plain())

        scope.controller.undo()

        val restored = scope.document().findById("t") as TodoListBlock
        assertEquals("/divider", restored.items[1].plain())
        assertEquals(true, restored.items[1].checked)
        assertEquals("done", restored.items[0].plain())
        assertEquals(true, restored.items[0].checked)
        assertEquals("the divider the command inserted is gone too", 1, scope.document().blocks.size)
    }

    @Test fun accept_failsSafely_whenTheAddressedItemIsGone() {
        val todo = TodoListBlock(
            id = "t",
            items = listOf(TodoItem(id = "i1", content = listOf(InlineText("/divider")))),
        )
        val (scope, resolver) = setup(todo)
        scope.selectBlocks(listOf("t"))
        val typed = FlowSlashInput(text = "/divider", caret = 8, itemId = "gone")
        val command = FlowCommands.registry(scope).get("flow.insert.divider")!!

        val result = resolver.accept("t", typed, command)
        assertTrue(result is CommandResult.Failure)
        assertEquals("nothing is committed against an item that no longer exists", 0, scope.controller.undoDepth())
        assertEquals(1, scope.document().blocks.size)
    }

    @Test fun accept_failsSafely_whenTheAddressedBlockIsGone() {
        val (scope, resolver) = setup(para("a", "/divider"))
        scope.selectBlocks(listOf("a"))
        val typed = input("/divider")
        val command = FlowCommands.registry(scope).get("flow.insert.divider")!!

        val result = resolver.accept("missing", typed, command)
        assertTrue(result is CommandResult.Failure)
        assertEquals(0, scope.controller.undoDepth())
    }

    private fun TodoItem.plain(): String = content.joinToString("") { it.plainText() }

    @Test fun accept_refusesAnIneligibleCommand_andLeavesTheTextAlone() {
        val (scope, resolver) = setup(para("a", "one"), para("b", "/move up"))
        // The query lives in block b, but block a is selected, so "move up" is genuinely disabled.
        scope.selectBlocks(listOf("a"))
        val typed = input("/move up")
        val command = FlowCommands.registry(scope).get("flow.move.up")!!

        val result = resolver.accept("b", typed, command)
        assertTrue(result is CommandResult.Failure)
        assertEquals("/move up", scope.document().findById("b")!!.plainText())
        assertEquals("A refused command commits nothing", 0, scope.controller.undoDepth())
    }

    @Test fun accept_withoutAQuery_fails() {
        val (scope, resolver) = setup(para("a", "plain text"))
        scope.selectBlocks(listOf("a"))
        val command = FlowCommands.registry(scope).get("flow.insert.divider")!!
        val result = resolver.accept("a", input("plain text"), command)
        assertTrue(result is CommandResult.Failure)
        assertEquals(0, scope.controller.undoDepth())
    }

    // ── Composite transactions (controller support for accept) ──────────────────────────────────────

    @Test fun transaction_recordsSeveralMutationsAsOneUndoStep() {
        val (scope, _) = setup(para("a", "one"), para("b", "two"))
        scope.controller.transaction("Composite") {
            scope.controller.mutate("insert") { it.insertAfter("a", ParagraphBlock(id = "x")) }
            scope.controller.mutate("delete") { it.delete("b") }
        }
        assertEquals(listOf("a", "x"), scope.document().blocks.map { it.id })
        assertEquals(1, scope.controller.undoDepth())

        scope.controller.undo()
        assertEquals(listOf("a", "b"), scope.document().blocks.map { it.id })
    }

    @Test fun transaction_rollsBackWhenTheBodyThrows() {
        val (scope, _) = setup(para("a", "one"), para("b", "two"))
        val before = scope.document()
        runCatching {
            scope.controller.transaction("Composite") {
                scope.controller.mutate("delete") { it.delete("b") }
                error("boom")
            }
        }
        assertEquals("A half-applied composite is never observable", before, scope.document())
        assertEquals(0, scope.controller.undoDepth())
    }

    @Test fun transaction_withNoEffectiveChange_recordsNothing() {
        val (scope, _) = setup(para("a", "one"))
        scope.controller.transaction("Nothing") { }
        assertEquals(0, scope.controller.undoDepth())
    }
}
