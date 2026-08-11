package com.norfold.app.editor.flow

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextInputSelection
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.text.TextRange
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.norfold.app.domain.BlockDocument
import com.norfold.app.domain.BulletListBlock
import com.norfold.app.domain.CalloutBlock
import com.norfold.app.domain.ContainerAxis
import com.norfold.app.domain.ContainerBlock
import com.norfold.app.domain.InlineText
import com.norfold.app.domain.ListItem
import com.norfold.app.domain.MermaidBlock
import com.norfold.app.domain.ParagraphBlock
import com.norfold.app.domain.findById
import com.norfold.app.domain.plainText
import com.norfold.app.editor.flags.EditorFeatureFlag
import com.norfold.app.editor.flags.EditorFeatureFlags
import com.norfold.app.editor.history.EditTarget
import com.norfold.app.editor.presentation.FormFactor
import com.norfold.app.ui.editor.flow.FlowBlockCallbacks
import com.norfold.app.ui.editor.flow.FlowBlockContent
import com.norfold.app.ui.editor.flow.FlowBlockContext
import com.norfold.app.ui.editor.setEditorFlagsForTesting
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The Compose half of the Phase 1 interaction gate.
 *
 * `FlowHostCorrectionTest` proves what the runtime *decides*; nothing in a JVM test can prove that a field
 * genuinely takes focus, that a tap in multi-select reaches the row instead of the text field, or that a
 * caret request actually lands. Those are compositions, so they are asserted here against a real one.
 *
 * These drive `FlowBlockContent` directly with a live [FlowHostRuntime] rather than the whole screen: the
 * seam under test is row ↔ runtime, and going through app navigation would make a failure ambiguous between
 * the seam and the surrounding scaffolding.
 */
@OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class FlowHostInstrumentationTest {

    @get:Rule
    val compose = createComposeRule()

    private var invalidateComposition: (() -> Unit)? = null

    @Before
    fun setUp() {
        setEditorFlagsForTesting(
            EditorFeatureFlags.withOverrides(EditorFeatureFlag.FLOW_HOST to true),
        )
    }

    @After
    fun tearDown() {
        invalidateComposition = null
        setEditorFlagsForTesting(null)
    }

    private fun runtimeOf(document: BlockDocument) = FlowHostRuntime(
        controller = FlowEditController(EditTarget("note:flow"), document),
        initialFormFactor = FormFactor(widthDp = 400, heightDp = 900),
    )

    private fun paragraphs(vararg text: String) = BlockDocument(
        text.mapIndexed { i, v -> ParagraphBlock(id = "p$i", content = listOf(InlineText(v))) },
    )

    /**
     * Renders every block of [runtime]'s document, re-reading the snapshot on each recomposition so a runtime
     * change is visible to the assertions exactly as it would be on screen.
     */
    private fun setContent(
        runtime: FlowHostRuntime,
        selectionMode: Boolean = false,
        compact: Boolean = false,
        editing: Boolean = true,
    ) {
        compose.setContent {
            var revision by remember { mutableStateOf(0) }
            invalidateComposition = { revision++ }
            // Read `revision` so a runtime mutation recomposes this subtree exactly as the real screen does.
            val state = remember(revision) { runtime.uiState() }
            Column {
                state.document.blocks.forEach { block ->
                    FlowBlockContent(
                        block = block,
                        callbacks = FlowBlockCallbacks(
                            onEdit = { runtime.editText(it.blockId, it.before, it.after, it.itemId); revision++ },
                            onCaret = { id, item, caret -> runtime.setCaret(id, caret, item); revision++ },
                            onToggleTodo = { id, item -> runtime.toggleTodo(id, item); revision++ },
                            onTableCell = { runtime.setTableCursor(it); revision++ },
                            onNewLine = { runtime.newLine().also { revision++ } },
                            onBackspaceAtStart = { runtime.backspaceAtStart().also { revision++ } },
                            onToggleCollapsed = { runtime.toggleCollapsed(it); revision++ },
                        ),
                        modifier = Modifier.fillMaxWidth(),
                        context = FlowBlockContext(
                            caretRequest = state.caretRequest,
                            selectionMode = selectionMode,
                            compact = compact,
                            editing = editing,
                        ),
                    )
                }
            }
        }
    }

    // ── Item 1: a caret request moves *visible* focus ────────────────────────────────────────────────

    /**
     * The defect this pins: the runtime moved its caret and no field followed, so the next keystroke landed
     * in whichever block still held focus. Only a real composition can show that focus actually moved.
     */
    @Test
    fun caretRequest_movesFocusToTheRequestedField() {
        val runtime = runtimeOf(paragraphs("alpha", "beta"))
        setContent(runtime)

        compose.onNodeWithText("beta").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("beta").assertIsFocused()

        // A real structural route: Backspace at the start of "beta" merges it into "alpha", so the caret
        // belongs in the surviving block. Focus must follow it there.
        compose.runOnUiThread {
            runtime.backspaceAtStart()
            invalidateComposition?.invoke()
        }
        compose.waitForIdle()

        compose.onNodeWithText("alphabeta").assertIsFocused()
    }

    /** A field the user has since focused must not be yanked back by an already-applied request. */
    @Test
    fun caretRequest_isAppliedOncePerStamp() {
        val runtime = runtimeOf(paragraphs("alpha", "beta"))
        setContent(runtime)

        compose.runOnUiThread {
            runtime.setCaret("p0", 5)
            runtime.editText("p0", "alpha", "alpha more")
            runtime.undo()
            invalidateComposition?.invoke()
        }
        compose.waitForIdle()
        compose.onNodeWithText("alpha").assertIsFocused()

        compose.onNodeWithText("beta").performClick()
        compose.waitForIdle()

        // Same stamp, so no re-application: focus stays where the user just put it.
        compose.onNodeWithText("beta").assertIsFocused()
    }

    /** Typing reaches the document through the runtime, which is the only writer. */
    @Test
    fun typing_reachesTheDocumentThroughTheRuntime() {
        val runtime = runtimeOf(paragraphs("alpha"))
        setContent(runtime)

        compose.onNodeWithText("alpha").performTextInput("!")
        compose.waitForIdle()

        assertTrue(runtime.controller.document().blocks[0].plainText().contains("!"))
    }

    // ── Item 2: Enter and Backspace are structural in a real field ───────────────────────────────────

    /**
     * A `BasicTextField` inserts `\n` by default. The gate is that the key is intercepted *before* the field
     * sees it, which no JVM test can show — the text would simply gain a newline and the block count stay 1.
     */
    @Test
    fun enter_splitsTheBlockInsteadOfInsertingANewline() {
        val runtime = runtimeOf(paragraphs("hello world"))
        setContent(runtime)

        compose.onNodeWithText("hello world").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("hello world").performKeyInput {
            pressKey(androidx.compose.ui.input.key.Key.Enter)
        }
        compose.waitForIdle()

        val blocks = runtime.controller.document().blocks
        assertEquals("Enter must split rather than type a newline", 2, blocks.size)
        assertTrue(
            "No block may contain the literal newline the field would have inserted",
            blocks.none { it.plainText().contains("\n") },
        )
    }

    /** Backspace at offset 0 merges backwards rather than deleting a character somewhere. */
    @Test
    fun backspaceAtStart_mergesInARealField() {
        val runtime = runtimeOf(paragraphs("alpha", "beta"))
        setContent(runtime)

        compose.onNodeWithText("beta").performClick()
        compose.waitForIdle()
        // The handler fires only for a collapsed caret at offset 0, which is the whole point: an ordinary
        // Backspace elsewhere must stay an ordinary deletion. A tap lands wherever it lands, so address it.
        compose.onNodeWithText("beta").performTextInputSelection(TextRange(0))
        compose.onNodeWithText("beta").performKeyInput {
            pressKey(androidx.compose.ui.input.key.Key.Backspace)
        }
        compose.waitForIdle()

        assertEquals(1, runtime.controller.document().blocks.size)
        assertEquals("alphabeta", runtime.controller.document().blocks[0].plainText())
    }

    // ── Item 5: multi-select takes the gesture away from the text field ──────────────────────────────

    /**
     * The defect: an editable child stole the tap, so selecting a block dropped a caret into it instead. In
     * selection mode the field must be read-only and unfocusable.
     */
    @Test
    fun selectionMode_textFieldDoesNotTakeFocusOrTheCaret() {
        val runtime = runtimeOf(paragraphs("alpha", "beta"))
        runtime.enterSelectionMode("p0")
        val caretBeforeTap = runtime.scope.caret()
        setContent(runtime, selectionMode = true)

        compose.onNodeWithText("beta").performClick()
        compose.waitForIdle()

        compose.onNodeWithText("beta").assert(
            SemanticsMatcher.keyNotDefined(SemanticsProperties.Focused),
        )
        assertEquals(
            "The caret must not be re-pointed by a tap in selection mode",
            caretBeforeTap,
            runtime.scope.caret(),
        )
        assertEquals("p0", runtime.scope.primaryBlockId())
    }

    /** Read-only means read-only: a keystroke in selection mode must not change the document. */
    @Test
    fun selectionMode_fieldRejectsInput() {
        val runtime = runtimeOf(paragraphs("alpha"))
        runtime.enterSelectionMode("p0")
        setContent(runtime, selectionMode = true)
        val before = runtime.controller.document()

        compose.onNodeWithText("alpha").assert(
            SemanticsMatcher.keyNotDefined(SemanticsActions.SetText),
        )

        assertEquals(before, runtime.controller.document())
    }

    // ── Item 15: existing inline formatting is visibly preserved ─────────────────────────────────────

    /**
     * A bold run must reach the composition as a span. Asserting the *style* needs the text layout, so this
     * asserts the weaker but still meaningful fact the defect violated: the styled text is rendered at all,
     * rather than the row falling back to a placeholder or empty line.
     */
    @Test
    fun inlineFormatting_isRenderedRatherThanFlattenedAway() {
        val runtime = runtimeOf(
            BlockDocument(
                listOf(
                    ParagraphBlock(
                        id = "p0",
                        content = listOf(
                            com.norfold.app.domain.BoldInline(listOf(InlineText("bold"))),
                            InlineText(" plain"),
                        ),
                    ),
                ),
            ),
        )
        setContent(runtime)

        compose.onNodeWithText("bold plain").assertIsDisplayed()
    }

    // ── Item 16: collapsed content has a real route open ─────────────────────────────────────────────

    /** A collapsed callout with no control is unreachable content; the toggle must be tappable. */
    @Test
    fun collapsedCallout_canBeExpandedByTappingTheControl() {
        val runtime = runtimeOf(
            BlockDocument(
                listOf(
                    CalloutBlock(
                        id = "c0",
                        title = "Note",
                        collapsed = true,
                        children = listOf(
                            ParagraphBlock(id = "inner", content = listOf(InlineText("hidden text"))),
                        ),
                    ),
                ),
            ),
        )
        setContent(runtime)

        // Targeted by its content description rather than a test tag: if the control is unlabelled it is also
        // unreachable by a screen reader, so this asserts both at once.
        compose.onNodeWithContentDescription("Expand callout").performClick()
        compose.waitForIdle()

        assertFalse((runtime.controller.document().findById("c0") as CalloutBlock).collapsed)
        compose.onNodeWithText("hidden text").assertIsDisplayed()
    }

    // ── Item 1 + 2 on list items: the item id reaches the runtime ───────────────────────────────────

    /** Tapping a specific item must address *that* item, or Enter/Backspace resolve against the wrong one. */
    @Test
    fun tappingAListItem_reportsThatItemToTheRuntime() {
        val runtime = runtimeOf(
            BlockDocument(
                listOf(
                    BulletListBlock(
                        id = "b0",
                        items = listOf(
                            ListItem(id = "i0", content = listOf(InlineText("one"))),
                            ListItem(id = "i1", content = listOf(InlineText("two"))),
                        ),
                    ),
                ),
            ),
        )
        setContent(runtime)

        compose.onNodeWithText("two").performClick()
        compose.waitForIdle()

        assertEquals("i1", runtime.scope.caretItemId)
        assertNotNull(runtime.scope.caret())
    }

    @Test
    fun readingMode_hasNoEditableTextOrPermanentCalloutAction() {
        val runtime = runtimeOf(
            BlockDocument(
                listOf(
                    ParagraphBlock("p0", listOf(InlineText("Quiet reading"))),
                    CalloutBlock("c0", title = "Reference", collapsed = false),
                ),
            ),
        )
        setContent(runtime, editing = false)

        compose.onNodeWithText("Quiet reading").assert(
            SemanticsMatcher.keyNotDefined(SemanticsActions.SetText),
        )
        assertTrue(
            "Reading mode must not expose a permanent callout control",
            compose.onAllNodesWithContentDescription("Collapse callout").fetchSemanticsNodes().isEmpty(),
        )
    }

    @Test
    fun compactRowContainer_stacksChildrenInReadingOrder() {
        val runtime = runtimeOf(
            BlockDocument(
                listOf(
                    ContainerBlock(
                        id = "columns",
                        axis = ContainerAxis.Row,
                        children = listOf(
                            ParagraphBlock("left", listOf(InlineText("First column"))),
                            ParagraphBlock("right", listOf(InlineText("Second column"))),
                        ),
                    ),
                ),
            ),
        )
        setContent(runtime, compact = true, editing = false)
        compose.waitForIdle()

        val first = compose.onNodeWithText("First column").fetchSemanticsNode().boundsInRoot
        val second = compose.onNodeWithText("Second column").fetchSemanticsNode().boundsInRoot
        assertTrue("The second nested child must be below the first on phone", second.top >= first.bottom)
    }

    @Test
    fun mermaid_hasOneSourceOrPreviewRepresentationRegion() {
        val runtime = runtimeOf(
            BlockDocument(
                listOf(
                    MermaidBlock("diagram", "flowchart TD\n  Start --> Finish", editorHeightDp = 180f),
                ),
            ),
        )
        setContent(runtime, compact = true, editing = false)

        compose.onNodeWithText("Source").assertIsDisplayed()
        compose.onNodeWithText("Preview").assertIsDisplayed()
        compose.onNodeWithText("Start").assertIsDisplayed()
        compose.onNodeWithText("Finish").assertIsDisplayed()
    }
}
