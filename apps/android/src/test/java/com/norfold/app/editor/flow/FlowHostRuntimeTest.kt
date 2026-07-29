package com.norfold.app.editor.flow

import com.norfold.app.domain.BlockDocument
import com.norfold.app.domain.BulletListBlock
import com.norfold.app.domain.CodeBlock
import com.norfold.app.domain.DividerBlock
import com.norfold.app.domain.HeadingBlock
import com.norfold.app.domain.ParagraphBlock
import com.norfold.app.domain.TableBlock
import com.norfold.app.domain.TableCell
import com.norfold.app.domain.findById
import com.norfold.app.editor.command.CommandResult
import com.norfold.app.editor.command.CommandState
import com.norfold.app.editor.command.CommandSurface
import com.norfold.app.editor.history.EditTarget
import com.norfold.app.editor.memory.MemoryScope
import com.norfold.app.editor.presentation.FormFactor
import com.norfold.app.editor.presentation.PresentationMode
import com.norfold.app.editor.surface.DismissTrigger
import com.norfold.app.editor.surface.SurfaceClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * FlowHost runtime tests (roadmap Phase 1 slice 4).
 *
 * The centre of gravity is [surfaceParity]: the slice's whole claim is that `+`, slash, keyboard and
 * accessibility are four *views* of one command, so the tests assert equivalence across surfaces rather than
 * exercising each route separately and hoping they agree.
 */
class FlowHostRuntimeTest {

    private val phone = FormFactor(widthDp = 400, heightDp = 900)
    private val tablet = FormFactor(widthDp = 900, heightDp = 700)

    private fun runtimeOf(
        document: BlockDocument,
        instrumentation: FlowHostInstrumentation = FlowHostInstrumentation.None,
    ): FlowHostRuntime = FlowHostRuntime(
        controller = FlowEditController(EditTarget("note:flow"), document),
        initialFormFactor = phone,
        instrumentation = instrumentation,
    )

    private fun paragraphs(vararg text: String): BlockDocument = BlockDocument(
        text.mapIndexed { index, value ->
            ParagraphBlock(id = "p$index", content = listOf(com.norfold.app.domain.InlineText(value)))
        },
    )

    // ── The parity gate ──────────────────────────────────────────────────────────────────────────────

    /**
     * The same command id resolves to the same [com.norfold.app.editor.command.CommandStatus] — state *and*
     * disabled reason — from every surface. Testing each UI independently could never establish this; only
     * comparing the four routes against one another can.
     */
    @Test
    fun surfaceParity_statusIsIdenticalFromEverySurface() {
        val runtime = runtimeOf(paragraphs("first", "second"))
        runtime.scope.selectBlocks(listOf("p1"))

        val surfaces = listOf(
            CommandSurface.DOCK,
            CommandSurface.SLASH,
            CommandSurface.KEYBOARD,
            CommandSurface.ACCESSIBILITY,
        )
        runtime.registry.all().forEach { command ->
            val statuses = surfaces.map { runtime.statusOf(command.id, it) }
            assertEquals(
                "Status of ${command.id} differs between surfaces: $statuses",
                1,
                statuses.distinct().size,
            )
        }
    }

    /** A disabled command carries the *same* reason no matter which surface asks for it. */
    @Test
    fun surfaceParity_disabledReasonIsIdenticalFromEverySurface() {
        val runtime = runtimeOf(paragraphs("only"))
        runtime.scope.selectBlocks(listOf("p0"))

        val dock = runtime.statusOf(FlowIds.MOVE_UP, CommandSurface.DOCK)
        assertEquals(CommandState.DISABLED, dock.state)
        assertEquals("Already first", dock.reason)
        listOf(CommandSurface.SLASH, CommandSurface.KEYBOARD, CommandSurface.ACCESSIBILITY).forEach { surface ->
            assertEquals(dock, runtime.statusOf(FlowIds.MOVE_UP, surface))
        }
    }

    /**
     * The same command produces the same *document* whichever surface runs it — the property that would break
     * first if any surface grew its own implementation.
     */
    @Test
    fun surfaceParity_executionProducesIdenticalDocuments() {
        fun runVia(body: (FlowHostRuntime) -> Unit): BlockDocument {
            val runtime = runtimeOf(paragraphs("alpha", "beta"))
            runtime.scope.selectBlocks(listOf("p0"))
            body(runtime)
            return runtime.controller.document()
        }

        val viaDock = runVia { it.invoke(FlowIds.DUPLICATE, CommandSurface.DOCK) }
        val viaKeyboard = runVia { it.handleShortcut(FlowShortcut("d", ctrl = true)) }
        val viaAccessibility = runVia { it.invokeAccessibility(FlowIds.DUPLICATE) }

        // Ids of copies are freshly generated, so compare structure rather than identity.
        val shapes = listOf(viaDock, viaKeyboard, viaAccessibility).map { document ->
            document.blocks.map { it.plainText() }
        }
        assertEquals(1, shapes.distinct().size)
        assertEquals(listOf("alpha", "alpha", "beta"), shapes.first())
    }

    /** Every route commits exactly one undo unit — the "one command, one transaction" invariant (§4.14). */
    @Test
    fun surfaceParity_everyRouteCommitsOneUndoUnit() {
        listOf<(FlowHostRuntime) -> Unit>(
            { it.invoke(FlowIds.DUPLICATE, CommandSurface.DOCK) },
            { it.handleShortcut(FlowShortcut("d", ctrl = true)) },
            { it.invokeAccessibility(FlowIds.DUPLICATE) },
        ).forEach { route ->
            val runtime = runtimeOf(paragraphs("alpha", "beta"))
            runtime.scope.selectBlocks(listOf("p0"))
            route(runtime)
            assertEquals(1, runtime.controller.undoDepth())
            assertTrue(runtime.undo())
            assertEquals(listOf("alpha", "beta"), runtime.controller.document().blocks.map { it.plainText() })
        }
    }

    /** A slash acceptance and a `+` tap of the same id converge on the same document. */
    @Test
    fun surfaceParity_slashAndPaletteInsertProduceTheSameDocument() {
        val insertId = FlowIds.insert(FlowBlockKind.HEADING_1)

        val palette = runtimeOf(paragraphs("intro"))
        palette.setCaret("p0", 5)
        palette.invoke(insertId, CommandSurface.DOCK)

        val slash = runtimeOf(paragraphs("intro"))
        slash.setCaret("p0", 5)
        slash.onTextInput("p0", FlowSlashInput(text = "intro /h1", caret = 9))
        assertNotNull(slash.slashSession())
        slash.acceptSlash(insertId)

        assertEquals(
            palette.controller.document().blocks.map { it::class.simpleName },
            slash.controller.document().blocks.map { it::class.simpleName },
        )
    }

    /** Accessibility exposes the whole visible catalogue, disabled entries included, with their reasons. */
    @Test
    fun accessibilityActions_exposeDisabledEntriesWithReasons() {
        val runtime = runtimeOf(paragraphs("only"))
        runtime.scope.selectBlocks(listOf("p0"))

        val moveUp = runtime.accessibilityActions().first { it.id == FlowIds.MOVE_UP }
        assertFalse(moveUp.enabled)
        assertEquals("Already first", moveUp.disabledReason)
        assertTrue(moveUp.accessibilityLabel().contains("unavailable: Already first"))
    }

    /** An accessibility label carries the chord, so keyboard and screen-reader users learn the same route. */
    @Test
    fun accessibilityActions_announceTheKeyboardShortcut() {
        val runtime = runtimeOf(paragraphs("alpha", "beta"))
        runtime.scope.selectBlocks(listOf("p0"))
        val duplicate = runtime.accessibilityActions().first { it.id == FlowIds.DUPLICATE }
        assertEquals("Ctrl+D", duplicate.shortcut?.display())
        assertTrue(duplicate.accessibilityLabel().contains("Ctrl+D"))
    }

    // ── Keyboard ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun keyboard_unboundChordFallsThrough() {
        val runtime = runtimeOf(paragraphs("alpha"))
        assertFalse(runtime.handleShortcut(FlowShortcut("k", ctrl = true)))
    }

    /** A bound-but-ineligible chord is consumed rather than leaking a stray character into the text. */
    @Test
    fun keyboard_boundButIneligibleChordIsStillConsumed() {
        val runtime = runtimeOf(paragraphs("alpha"))
        runtime.scope.selectBlocks(listOf("p0"))
        assertTrue(runtime.handleShortcut(FlowShortcut("arrowup", ctrl = true, shift = true)))
        assertEquals(0, runtime.controller.undoDepth())
    }

    @Test
    fun keyboard_undoRedoRouteToCanonicalHistory() {
        val runtime = runtimeOf(paragraphs("alpha", "beta"))
        runtime.scope.selectBlocks(listOf("p0"))
        runtime.invoke(FlowIds.DUPLICATE, CommandSurface.DOCK)
        assertEquals(3, runtime.controller.document().blocks.size)

        assertTrue(runtime.handleShortcut(FlowKeyboardMap.UNDO))
        assertEquals(2, runtime.controller.document().blocks.size)
        assertTrue(runtime.handleShortcut(FlowKeyboardMap.REDO))
        assertEquals(3, runtime.controller.document().blocks.size)
        assertTrue(runtime.handleShortcut(FlowKeyboardMap.REDO_ALT))
    }

    @Test
    fun keyboard_everyBindingResolvesToARegisteredCommand() {
        val runtime = runtimeOf(paragraphs("alpha"))
        FlowKeyboardMap.bindings.forEach { (shortcut, commandId) ->
            assertNotNull("$shortcut binds unknown command '$commandId'", runtime.registry.get(commandId))
        }
    }

    @Test
    fun keyboard_headingChordConvertsThroughTheSameCommand() {
        val runtime = runtimeOf(paragraphs("title"))
        runtime.scope.selectBlocks(listOf("p0"))
        assertTrue(runtime.handleShortcut(FlowShortcut("1", ctrl = true, alt = true)))
        val block = runtime.controller.document().findById("p0")
        assertTrue(block is HeadingBlock && block.level == 1)
    }

    // ── Multi-select (deliverable 5) ─────────────────────────────────────────────────────────────────

    @Test
    fun selectionMode_cancelRestoresThePreviousSelectionAndCaret() {
        val runtime = runtimeOf(paragraphs("alpha", "beta", "gamma"))
        runtime.setCaret("p0", 3)

        runtime.enterSelectionMode("p1")
        runtime.toggleSelection("p2")
        assertEquals(2, runtime.selectedCount)

        runtime.cancelSelectionMode()
        assertFalse(runtime.selectionMode)
        assertEquals(listOf("p0"), runtime.scope.selectedBlockIds())
        assertEquals(3, runtime.scope.caret()?.offset)
    }

    /** Done keeps the selection so the next command still has its targets; Cancel does not. */
    @Test
    fun selectionMode_doneKeepsTheSelection() {
        val runtime = runtimeOf(paragraphs("alpha", "beta"))
        runtime.enterSelectionMode("p0")
        runtime.toggleSelection("p1")
        runtime.finishSelectionMode()

        assertFalse(runtime.selectionMode)
        assertEquals(listOf("p0", "p1"), runtime.scope.selectedBlockIds())
    }

    @Test
    fun selectionMode_toggleRemovesAndReAdds() {
        val runtime = runtimeOf(paragraphs("alpha", "beta"))
        runtime.enterSelectionMode("p0")
        runtime.toggleSelection("p1")
        runtime.toggleSelection("p1")
        assertEquals(listOf("p0"), runtime.scope.selectedBlockIds())
    }

    /** A multi-selection deletes as one transaction and reports an honest affected count (§4.10). */
    @Test
    fun selectionMode_multiSelectDeleteIsOneTransaction() {
        val runtime = runtimeOf(paragraphs("alpha", "beta", "gamma"))
        runtime.enterSelectionMode("p0")
        runtime.toggleSelection("p1")

        val result = runtime.invoke(FlowIds.DELETE, CommandSurface.DOCK)
        assertEquals(CommandResult.Success(affectedCount = 2), result)
        assertEquals(listOf("gamma"), runtime.controller.document().blocks.map { it.plainText() })
        assertEquals(1, runtime.controller.undoDepth())
    }

    /** The hint is guide-discovery state, so it survives nothing but being shown — and never leaks scope. */
    @Test
    fun selectionMode_hintIsGuideDiscoveryStateOnly() {
        val runtime = runtimeOf(paragraphs("alpha"))
        assertTrue(runtime.showMultiSelectHint())
        runtime.enterSelectionMode("p0")
        assertFalse(runtime.showMultiSelectHint())
        assertEquals(0, runtime.memory.size(MemoryScope.USER_PREFERENCE))
        assertEquals(0, runtime.memory.size(MemoryScope.TRANSIENT))
    }

    // ── Slash presentation (deliverable 3 + 5) ───────────────────────────────────────────────────────

    @Test
    fun slash_openingAndClosingTracksTheSurfaceStack() {
        val runtime = runtimeOf(paragraphs("alpha"))
        runtime.setCaret("p0", 0)

        runtime.onTextInput("p0", FlowSlashInput(text = "/h", caret = 2))
        assertEquals(FlowSurfaces.SLASH_MENU, runtime.uiState().activeSurfaceId)

        runtime.onTextInput("p0", FlowSlashInput(text = "no slash", caret = 8))
        assertNull(runtime.uiState().activeSurfaceId)
        assertNull(runtime.slashSession())
    }

    /** Slash never opens where `/` is content — the suppression rule the resolver owns, honored by the host. */
    @Test
    fun slash_isSuppressedInCodeBlocks() {
        val runtime = runtimeOf(BlockDocument(listOf(CodeBlock(id = "c0", code = "a/b"))))
        runtime.setCaret("c0", 3)
        val session = runtime.onTextInput("c0", FlowSlashInput("a/b", caret = 3, context = FlowSlashContext.CODE))
        assertNull(session)
    }

    /** Accepting a slash row erases the query and runs the command as one undo unit. */
    @Test
    fun slash_acceptIsOneUndoUnit() {
        val runtime = runtimeOf(paragraphs("hello "))
        runtime.setCaret("p0", 6)
        runtime.onTextInput("p0", FlowSlashInput(text = "hello /divider", caret = 14))

        val result = runtime.acceptSlash(FlowIds.insert(FlowBlockKind.DIVIDER))
        assertTrue(result is CommandResult.Success)
        assertTrue(runtime.controller.document().blocks.any { it is DividerBlock })
        assertEquals(1, runtime.controller.undoDepth())

        runtime.undo()
        assertEquals(listOf("hello "), runtime.controller.document().blocks.map { it.plainText() })
    }

    @Test
    fun slash_refusesAnIneligibleCommand() {
        val runtime = runtimeOf(paragraphs("alpha"))
        runtime.setCaret("p0", 0)
        runtime.onTextInput("p0", FlowSlashInput(text = "/merge", caret = 6))
        val result = runtime.acceptSlash(FlowIds.MERGE_PREVIOUS)
        assertTrue(result is CommandResult.Failure)
    }

    // ── Surfaces & back hierarchy (deliverable 7) ────────────────────────────────────────────────────

    @Test
    fun back_unwindsChildThenParentThenSelectionThenEditor() {
        val runtime = runtimeOf(paragraphs("alpha"))
        runtime.enterSelectionMode("p0")
        runtime.openSurface(FlowSurfaces.INSERT_PALETTE, SurfaceClass.PICKER_BROWSER)
        runtime.openSurface(FlowSurfaces.CONVERT_MENU, SurfaceClass.COMMAND_MENU)

        assertEquals(
            FlowBackResult.ClosedSurface(FlowSurfaces.CONVERT_MENU, FlowSurfaces.INSERT_PALETTE),
            runtime.back(),
        )
        assertEquals(FlowBackResult.ClosedSurface(FlowSurfaces.INSERT_PALETTE, null), runtime.back())
        assertEquals(FlowBackResult.ExitedSelectionMode, runtime.back())
        assertEquals(FlowBackResult.LeftEditor, runtime.back())
    }

    /** A command-menu surface closes when its command runs; that is its declared policy, not a global rule. */
    @Test
    fun surface_commandMenuClosesAfterItsCommandExecutes() {
        val runtime = runtimeOf(paragraphs("alpha", "beta"))
        runtime.scope.selectBlocks(listOf("p0"))
        runtime.openSurface(FlowSurfaces.CONVERT_MENU, SurfaceClass.COMMAND_MENU)

        runtime.invoke(FlowIds.DUPLICATE, CommandSurface.DOCK)
        assertNull(runtime.uiState().activeSurfaceId)
    }

    /** A picker stays open across an execution — repeated insertion is the whole point of a browser surface. */
    @Test
    fun surface_pickerSurvivesItsCommandExecuting() {
        val runtime = runtimeOf(paragraphs("alpha"))
        runtime.openInsertPalette("p0")
        runtime.invoke(FlowIds.insert(FlowBlockKind.PARAGRAPH), CommandSurface.DOCK)
        assertEquals(FlowSurfaces.INSERT_PALETTE, runtime.uiState().activeSurfaceId)
    }

    /** Closing a tool clears that tool's session memory only — no other scope is touched (§5.7). */
    @Test
    fun surface_closingClearsOnlyThatToolsSessionMemory() {
        val runtime = runtimeOf(paragraphs("alpha"))
        runtime.openInsertPalette("p0")
        assertEquals(1, runtime.memory.size(MemoryScope.TOOL_SESSION))

        runtime.memory.put(MemoryScope.USER_PREFERENCE, "unrelated", "kept")
        runtime.closeSurface(FlowSurfaces.INSERT_PALETTE, DismissTrigger.CANCEL)

        assertEquals(0, runtime.memory.size(MemoryScope.TOOL_SESSION))
        assertEquals(1, runtime.memory.size(MemoryScope.USER_PREFERENCE))
    }

    // ── Adaptive presentation (deliverable 7) ────────────────────────────────────────────────────────

    /** The D7 invariant, at host level: a reflow re-presents open tools without resetting their state. */
    @Test
    fun presentation_formFactorChangeDoesNotResetToolState() {
        val runtime = runtimeOf(paragraphs("alpha"))
        runtime.openInsertPalette("p0")
        runtime.presentation.update(
            runtime.presentation.stateOf(FlowSurfaces.INSERT_PALETTE)!!.withQuery("head").withScroll(12),
        )

        val presented = runtime.onFormFactorChanged(tablet)
        val state = presented.getValue(FlowSurfaces.INSERT_PALETTE)
        assertEquals("head", state.state.query)
        assertEquals(12, state.state.scroll)
        assertEquals(PresentationMode.EXPANDED_THREE_PANEL, state.mode)
    }

    @Test
    fun presentation_phonePaletteUsesASheet() {
        val runtime = runtimeOf(paragraphs("alpha"))
        runtime.openInsertPalette("p0")
        assertEquals(PresentationMode.PHONE_SHEET, runtime.currentPresentationMode())
    }

    // ── Interaction memory scoping (deliverable 7) ───────────────────────────────────────────────────

    /** A drag is transient: the moment the gesture ends the value is gone, and no other scope saw it. */
    @Test
    fun memory_dragStateIsTransientOnly() {
        val runtime = runtimeOf(paragraphs("alpha", "beta"))
        runtime.beginDrag("p0")
        assertEquals(1, runtime.memory.size(MemoryScope.TRANSIENT))

        runtime.completeDrag(com.norfold.app.domain.DropTarget.Into(null, 2))
        assertEquals(0, runtime.memory.size(MemoryScope.TRANSIENT))
        assertEquals(0, runtime.memory.size(MemoryScope.USER_PREFERENCE))
        assertEquals(0, runtime.memory.size(MemoryScope.CONTEXT_PREFERENCE))
    }

    /** The last-used insert kind is a context preference, not a device-wide user setting. */
    @Test
    fun memory_lastInsertIsAContextPreference() {
        val runtime = runtimeOf(paragraphs("alpha"))
        val quoteId = FlowIds.insert(FlowBlockKind.QUOTE)
        runtime.invoke(quoteId, CommandSurface.DOCK)

        assertEquals(quoteId, runtime.recentInsertId())
        assertEquals(1, runtime.memory.size(MemoryScope.CONTEXT_PREFERENCE))
        assertEquals(0, runtime.memory.size(MemoryScope.USER_PREFERENCE))
        assertEquals(0, runtime.memory.size(MemoryScope.TRANSIENT))
    }

    /** Scroll is per-note artifact UI state, keyed by the note so two notes cannot collide. */
    @Test
    fun memory_scrollIsArtifactScoped() {
        val runtime = runtimeOf(paragraphs("alpha", "beta"))
        runtime.reportVisibleRange(4, 9)
        assertEquals(4, runtime.restoredScrollIndex())
        assertEquals(1, runtime.memory.size(MemoryScope.ARTIFACT_UI))

        runtime.memory.closeArtifact(runtime.artifactId)
        assertEquals(0, runtime.restoredScrollIndex())
    }

    // ── Drag/drop routed through the registry ────────────────────────────────────────────────────────

    @Test
    fun drag_completesThroughTheSameMoveCommand() {
        val runtime = runtimeOf(paragraphs("alpha", "beta", "gamma"))
        runtime.beginDrag("p0")
        val result = runtime.completeDrag(com.norfold.app.domain.DropTarget.Into(null, 3))

        assertTrue(result is CommandResult.Success)
        assertEquals(listOf("beta", "gamma", "alpha"), runtime.controller.document().blocks.map { it.plainText() })
        assertEquals(1, runtime.controller.undoDepth())
    }

    @Test
    fun drag_withoutABeginFails() {
        val runtime = runtimeOf(paragraphs("alpha"))
        assertTrue(runtime.completeDrag(com.norfold.app.domain.DropTarget.Into(null, 0)) is CommandResult.Failure)
    }

    /** Cancel is what the gesture layer calls on a same-position drop or a cancelled gesture. */
    @Test
    fun drag_cancelLeavesTheDocumentAndHistoryUntouched() {
        val runtime = runtimeOf(paragraphs("alpha", "beta", "gamma"))
        val before = runtime.controller.document()

        runtime.beginDrag("p0")
        runtime.cancelDrag()

        assertEquals(before, runtime.controller.document())
        assertEquals(0, runtime.controller.undoDepth())
    }

    /** After a cancel the transient drag source is gone, so a later completeDrag cannot resurrect it. */
    @Test
    fun drag_cancelClearsTheDragSource() {
        val runtime = runtimeOf(paragraphs("alpha", "beta"))
        runtime.beginDrag("p0")
        runtime.cancelDrag()

        assertTrue(runtime.completeDrag(com.norfold.app.domain.DropTarget.Into(null, 2)) is CommandResult.Failure)
    }

    /** A committed drag is one undo unit, so one undo restores the pre-drag order exactly. */
    @Test
    fun drag_undoRestoresThePreDragOrder() {
        val runtime = runtimeOf(paragraphs("alpha", "beta", "gamma"))
        val before = runtime.controller.document().blocks.map { it.plainText() }

        runtime.beginDrag("p0")
        runtime.completeDrag(com.norfold.app.domain.DropTarget.Into(null, 3))
        runtime.undo()

        assertEquals(before, runtime.controller.document().blocks.map { it.plainText() })
        assertEquals(0, runtime.controller.undoDepth())
    }

    /**
     * The gesture layer computes an index from a pixel offset, so an over-range value is reachable. The tree
     * clamps it, which means the drop lands last instead of dropping the block or corrupting the document.
     */
    @Test
    fun drag_overRangeDropClampsToTheEndWithoutLosingBlocks() {
        val runtime = runtimeOf(paragraphs("alpha", "beta"))

        runtime.beginDrag("p0")
        val result = runtime.completeDrag(com.norfold.app.domain.DropTarget.Into(null, 99))

        assertTrue(result is CommandResult.Success)
        assertEquals(listOf("beta", "alpha"), runtime.controller.document().blocks.map { it.plainText() })
        assertEquals(1, runtime.controller.undoDepth())
    }

    /**
     * `moveInto` interprets the index against the list *before* the block is removed, so a one-row downward
     * drag needs index = target + 1. Dropping the naive target index is a silent no-op — the block appears to
     * snap back. These two tests pin the indices the gesture layer must actually send.
     */
    @Test
    fun drag_downOneRowUsesThePreRemovalIndex() {
        val runtime = runtimeOf(paragraphs("alpha", "beta", "gamma"))
        runtime.beginDrag("p0")
        runtime.completeDrag(com.norfold.app.domain.DropTarget.Into(null, 2))

        assertEquals(listOf("beta", "alpha", "gamma"), runtime.controller.document().blocks.map { it.plainText() })
    }

    @Test
    fun drag_upOneRowUsesTheTargetIndexDirectly() {
        val runtime = runtimeOf(paragraphs("alpha", "beta", "gamma"))
        runtime.beginDrag("p2")
        runtime.completeDrag(com.norfold.app.domain.DropTarget.Into(null, 1))

        assertEquals(listOf("alpha", "gamma", "beta"), runtime.controller.document().blocks.map { it.plainText() })
    }

    /** The naive downward index is the bug the gesture layer must not send: it moves nothing. */
    @Test
    fun drag_naiveDownwardIndexIsANoOp() {
        val runtime = runtimeOf(paragraphs("alpha", "beta", "gamma"))
        runtime.beginDrag("p0")
        runtime.completeDrag(com.norfold.app.domain.DropTarget.Into(null, 1))

        assertEquals(listOf("alpha", "beta", "gamma"), runtime.controller.document().blocks.map { it.plainText() })
    }

    // ── Auto-save contract (what the host's revision-triggered save relies on) ────────────────────────

    /**
     * `revision` is the signal the host debounces on, so every edit must advance it. Note it also advances for
     * non-content changes — which is precisely why the host debounces on idle and re-checks `dirtyBlockIds()`
     * instead of saving on a revision count.
     */
    @Test
    fun autoSave_revisionAdvancesOnEveryEdit() {
        val runtime = runtimeOf(paragraphs("alpha", "beta"))
        val start = runtime.revision

        runtime.setCaret("p0", 0)
        runtime.editText("p0", "alpha", "alphax")
        val afterTyping = runtime.revision
        assertTrue(afterTyping > start)

        runtime.invoke(FlowIds.MOVE_DOWN, CommandSurface.DOCK)
        assertTrue(runtime.revision > afterTyping)
    }

    /** A save marks the document clean without rewriting content or collapsing undo history. */
    @Test
    fun autoSave_markSavedClearsDirtyIdsAndKeepsContentAndHistory() {
        val runtime = runtimeOf(paragraphs("alpha", "beta"))
        runtime.setCaret("p0", 5)
        runtime.editText("p0", "alpha", "alphax")

        assertTrue(runtime.controller.dirtyBlockIds().isNotEmpty())
        val saved = runtime.controller.document()
        val depth = runtime.controller.undoDepth()

        runtime.controller.markSaved()

        assertTrue(runtime.controller.dirtyBlockIds().isEmpty())
        assertEquals(saved, runtime.controller.document())
        assertEquals(depth, runtime.controller.undoDepth())
    }

    /** Undo is still available after a save, so an auto-save never strands the user with committed content. */
    @Test
    fun autoSave_undoStillWorksAfterASave() {
        val runtime = runtimeOf(paragraphs("alpha", "beta"))
        val before = runtime.controller.document().blocks.map { it.plainText() }

        runtime.setCaret("p0", 5)
        runtime.editText("p0", "alpha", "alphax")
        runtime.controller.markSaved()
        runtime.undo()

        assertEquals(before, runtime.controller.document().blocks.map { it.plainText() })
        assertTrue(runtime.controller.dirtyBlockIds().isNotEmpty())
    }

    /** An in-progress drag is transient: a save mid-gesture must not persist the drag source. */
    @Test
    fun autoSave_doesNotPersistInProgressDragState() {
        val runtime = runtimeOf(paragraphs("alpha", "beta"))
        runtime.beginDrag("p0")
        runtime.controller.markSaved()
        runtime.cancelDrag()

        assertNull(runtime.memory.get<String>(MemoryScope.TRANSIENT, FlowMemoryKeys.DRAG_SOURCE))
    }

    /**
     * The reason the host debounces instead of counting revisions: opening a surface advances `revision` while
     * leaving the document clean, so a `% N` trigger would fire saves with nothing to write.
     */
    @Test
    fun autoSave_revisionAdvancesOnNonContentChangesWithoutDirtyingTheDocument() {
        val runtime = runtimeOf(paragraphs("alpha", "beta"))
        runtime.controller.markSaved()
        val before = runtime.revision

        runtime.openInsertPalette("p0")

        assertTrue(runtime.revision > before)
        assertTrue(runtime.controller.dirtyBlockIds().isEmpty())
    }

    // ── Text editing through the canonical history (deliverable 9) ───────────────────────────────────

    /** Continuous typing in one block collapses to one undo unit; leaving the block opens a new one. */
    @Test
    fun typing_coalescesWithinABlockAndBreaksOnCaretMove() {
        val runtime = runtimeOf(paragraphs("a", "z"))
        runtime.setCaret("p0", 1)
        runtime.editText("p0", "a", "ab")
        runtime.editText("p0", "ab", "abc")
        assertEquals(1, runtime.controller.undoDepth())

        runtime.setCaret("p1", 1)
        runtime.editText("p1", "z", "zy")
        assertEquals(2, runtime.controller.undoDepth())
    }

    @Test
    fun typing_noOpEditIsNotRecorded() {
        val runtime = runtimeOf(paragraphs("alpha"))
        assertFalse(runtime.editText("p0", "alpha", "alpha"))
        assertEquals(0, runtime.controller.undoDepth())
    }

    /** Undo that removes a block must not leave the selection pointing at a phantom. */
    @Test
    fun undo_dropsSelectionEntriesForBlocksThatNoLongerExist() {
        val runtime = runtimeOf(paragraphs("alpha", "beta"))
        runtime.scope.selectBlocks(listOf("p0"))
        runtime.invoke(FlowIds.DUPLICATE, CommandSurface.DOCK)
        val copyId = runtime.scope.selectedBlockIds().single()

        runtime.undo()
        assertFalse(copyId in runtime.scope.selectedBlockIds())
    }

    // ── Table addressing (§4.15) ─────────────────────────────────────────────────────────────────────

    @Test
    fun table_directionalInsertNeedsACellCursor() {
        val table = TableBlock(
            id = "t0",
            headers = listOf(TableCell(), TableCell()),
            rows = listOf(listOf(TableCell(), TableCell())),
        )
        val runtime = runtimeOf(BlockDocument(listOf(table)))
        runtime.scope.selectBlocks(listOf("t0"))

        val before = runtime.tableActions().first { it.id == "flow.table.insertRowBelow" }
        assertFalse(before.enabled)
        assertEquals("Place the cursor in a table cell first", before.disabledReason)

        runtime.setTableCursor(FlowTableCursor("t0", row = 0, column = 0))
        assertTrue(runtime.tableActions().first { it.id == "flow.table.insertRowBelow" }.enabled)
    }

    // ── Dock & palette views ─────────────────────────────────────────────────────────────────────────

    /** The dock shows the curated structural row, and every entry it lists is a real registered command. */
    @Test
    fun dock_listsOnlyVisibleRegisteredCommands() {
        val runtime = runtimeOf(paragraphs("alpha", "beta"))
        runtime.scope.selectBlocks(listOf("p0"))
        val dock = runtime.dockCommands()

        assertTrue(dock.isNotEmpty())
        dock.forEach { item ->
            assertNotNull(runtime.registry.get(item.id))
            assertTrue(item.status.isVisible)
        }
        assertTrue(dock.map { it.id }.contains(FlowIds.DUPLICATE))
    }

    /** Insertion is context-free, so the `+` palette is never empty — the `+` button is never a dead control. */
    @Test
    fun insertPalette_isNeverEmptyEvenWithNoSelection() {
        val runtime = runtimeOf(paragraphs("alpha"))
        val palette = runtime.insertPalette()
        assertEquals(FlowBlockKind.insertable.size, palette.size)
        assertTrue(palette.all { it.enabled })
    }

    @Test
    fun convertPalette_isHiddenWithoutASelection() {
        val runtime = runtimeOf(paragraphs("alpha"))
        assertTrue(runtime.convertPalette().isEmpty())

        runtime.scope.selectBlocks(listOf("p0"))
        assertTrue(runtime.convertPalette().isNotEmpty())
    }

    /** A conversion whose target the block already is reports ACTIVE, so the dock can show it as "on" (§4.2). */
    @Test
    fun convertPalette_reportsActiveForTheCurrentKind() {
        val runtime = runtimeOf(BlockDocument(listOf(BulletListBlock(id = "b0"))))
        runtime.scope.selectBlocks(listOf("b0"))
        val bullet = runtime.convertPalette().first { it.id == FlowIds.convert(FlowBlockKind.BULLET_LIST) }
        assertTrue(bullet.active)
    }

    // ── Instrumentation (deliverable 10) ─────────────────────────────────────────────────────────────

    /** Instrumentation records the route actually taken, which is what makes parity measurable in the field. */
    @Test
    fun instrumentation_recordsTheInvokingSurface() {
        val recorder = RecordingFlowHostInstrumentation()
        val runtime = runtimeOf(paragraphs("alpha", "beta"), recorder)
        runtime.scope.selectBlocks(listOf("p0"))

        runtime.invoke(FlowIds.DUPLICATE, CommandSurface.DOCK)
        runtime.handleShortcut(FlowShortcut("d", ctrl = true))
        runtime.invokeAccessibility(FlowIds.DUPLICATE)

        val commands = recorder.events().filter { it.startsWith("command:") }
        assertEquals(3, commands.size)
        assertTrue(commands[0].contains(":DOCK:"))
        assertTrue(commands[1].contains(":KEYBOARD:"))
        assertTrue(commands[2].contains(":ACCESSIBILITY:"))
    }

    @Test
    fun instrumentation_reportsTheComposedRange() {
        val recorder = RecordingFlowHostInstrumentation()
        val runtime = runtimeOf(paragraphs("a", "b", "c", "d"), recorder)
        runtime.reportVisibleRange(1, 2)
        assertTrue(recorder.events().contains("visible:1-2/4"))
    }

    @Test
    fun instrumentation_reportsSlashSessionsOpeningAndClosing() {
        val recorder = RecordingFlowHostInstrumentation()
        val runtime = runtimeOf(paragraphs("alpha"), recorder)
        runtime.setCaret("p0", 0)
        runtime.onTextInput("p0", FlowSlashInput("/h", caret = 2))
        runtime.closeSlash()

        val slash = recorder.events().filter { it.startsWith("slash:") }
        assertTrue(slash.any { it.startsWith("slash:h:") })
        assertTrue(slash.last().startsWith("slash:-:-1"))
    }
}
