package com.norfold.app.editor.document

import com.norfold.app.domain.BlockDocument
import com.norfold.app.domain.BulletListBlock
import com.norfold.app.domain.CalloutBlock
import com.norfold.app.domain.CodeBlock
import com.norfold.app.domain.ContainerBlock
import com.norfold.app.domain.DocumentBlock
import com.norfold.app.domain.DocumentPageBlock
import com.norfold.app.domain.InlineText
import com.norfold.app.domain.ListItem
import com.norfold.app.domain.PageColumns
import com.norfold.app.domain.PageGeometry
import com.norfold.app.domain.ParagraphBlock
import com.norfold.app.domain.QuoteBlock
import com.norfold.app.domain.ResizeCorner
import com.norfold.app.domain.SceneGeometry
import com.norfold.app.domain.SceneLock
import com.norfold.app.domain.SceneObject
import com.norfold.app.domain.SceneObjectKind
import com.norfold.app.domain.TodoItem
import com.norfold.app.domain.TodoListBlock
import com.norfold.app.domain.findById
import com.norfold.app.domain.plainText
import com.norfold.app.editor.command.CommandResult
import com.norfold.app.editor.flow.FlowShortcut
import com.norfold.app.editor.history.EditTarget
import com.norfold.app.editor.presentation.FormFactor
import com.norfold.app.ui.editor.document.DocumentClickAction
import com.norfold.app.ui.editor.document.SelectionModifiers
import com.norfold.app.ui.editor.document.documentClickDecision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Phase-2 correction pass: the interaction defects that survived the first host build.
 *
 * Every case here is one that *looked* finished. Rotation had a rotated outline over unrotated content;
 * keyboard bindings ate the keystrokes of the field they were meant to serve; Read mode kept a live caret;
 * nested content reported the wrong block to edit; the inspector spent one undo step per digit; a locked page
 * was counted as changed. None of those are device-certification questions — each one is a wrong route, a
 * wrong target or a wrong count, provable here with no Android runtime at all.
 */
class DocumentPhase2CorrectionTest {

    private fun obj(
        id: String,
        x: Float = 0f,
        y: Float = 0f,
        width: Float = 100f,
        height: Float = 50f,
        rotation: Float = 0f,
        lock: SceneLock = SceneLock.None,
        kind: SceneObjectKind = SceneObjectKind.TextFrame,
        content: DocumentBlock = ParagraphBlock(id = "c-$id", content = listOf(InlineText(""))),
    ) = SceneObject(
        id = id,
        kind = kind,
        geometry = SceneGeometry(x, y, width, height, rotation),
        lock = lock,
        content = content,
    )

    private fun page(
        id: String,
        vararg objects: SceneObject,
        geometry: PageGeometry = PageGeometry.a4().copy(structureLocked = false),
    ) = DocumentPageBlock(id = id, geometry = geometry, objects = objects.toList()).normalized()

    private fun runtime(vararg pages: DocumentPageBlock): DocumentHostRuntime = DocumentHostRuntime(
        controller = DocumentSceneController(EditTarget("note:1"), BlockDocument(pages.toList())),
        initialFormFactor = FormFactor(400, 800),
    )

    private fun editing(vararg pages: DocumentPageBlock): DocumentHostRuntime = runtime(*pages).apply {
        scope.activePageId = pages.first().id
        setRepresentation(LayoutRepresentation.ORIGINAL)
        setIntent(WorkingIntent.EDIT)
    }

    private fun contentOf(rt: DocumentHostRuntime, objectId: String): DocumentBlock =
        rt.controller.findObject(objectId)!!.second.content

    private fun textOf(rt: DocumentHostRuntime, objectId: String, blockId: String): String =
        listOf(contentOf(rt, objectId)).findById(blockId)!!.plainText()

    // ── 1. Full visual rotation: one transform, one centre ───────────────────────────────────────────

    @Test
    fun theRotatedCornersAreTheDrawnCornersAtEveryAngle() {
        val geometry = SceneGeometry(x = 100f, y = 100f, width = 200f, height = 100f)

        // 0°: the transform is the identity, so the rotated corners are the plain ones.
        ResizeCorner.entries.forEach { corner ->
            val (px, py) = corner.positionIn(geometry)
            val (rx, ry) = geometry.rotatedCornerOf(corner)
            assertEquals("$corner x at 0°", px, rx, 0.001f)
            assertEquals("$corner y at 0°", py, ry, 0.001f)
        }

        // 90° about the centre (200, 150): the box's own top-left lands at the page's right-of-centre.
        val quarter = geometry.copy(rotation = 90f)
        val (tlx, tly) = quarter.rotatedCornerOf(ResizeCorner.TOP_LEFT)
        assertEquals(250f, tlx, 0.001f)
        assertEquals(50f, tly, 0.001f)
        val (brx, bry) = quarter.rotatedCornerOf(ResizeCorner.BOTTOM_RIGHT)
        assertEquals(150f, brx, 0.001f)
        assertEquals(250f, bry, 0.001f)

        // The corners stay a rigid body: opposite corners remain symmetric about the centre at any angle.
        listOf(0f, 45f, 90f, 137f).forEach { angle ->
            val rotated = geometry.copy(rotation = angle)
            val (ax, ay) = rotated.rotatedCornerOf(ResizeCorner.TOP_LEFT)
            val (bx, by) = rotated.rotatedCornerOf(ResizeCorner.BOTTOM_RIGHT)
            assertEquals("centre x at $angle°", rotated.centerX, (ax + bx) / 2f, 0.001f)
            assertEquals("centre y at $angle°", rotated.centerY, (ay + by) / 2f, 0.001f)
        }

        // 45°: the diagonal is preserved, which is what proves this is a rotation and not a skew.
        val diagonal = geometry.copy(rotation = 45f)
        val (ax, ay) = diagonal.rotatedCornerOf(ResizeCorner.TOP_LEFT)
        val (bx, by) = diagonal.rotatedCornerOf(ResizeCorner.BOTTOM_RIGHT)
        val expected = Math.hypot(geometry.width.toDouble(), geometry.height.toDouble()).toFloat()
        assertEquals(expected, Math.hypot((bx - ax).toDouble(), (by - ay).toDouble()).toFloat(), 0.01f)
    }

    @Test
    fun theRotationHandleFollowsTheObjectsOwnUpDirection() {
        val geometry = SceneGeometry(x = 100f, y = 100f, width = 200f, height = 100f)

        // Unrotated: directly above the top edge's midpoint.
        val (ux, uy) = geometry.rotationHandlePoint(28f)
        assertEquals(geometry.centerX, ux, 0.001f)
        assertEquals(geometry.y - 28f, uy, 0.001f)

        // Rotated a quarter turn: the handle is now to the object's *page-right*, because "up" turned with it.
        // A handle that stayed above the page's north is precisely the detached-handle defect.
        val (rx, ry) = geometry.copy(rotation = 90f).rotationHandlePoint(28f)
        assertEquals(geometry.centerX + 78f, rx, 0.001f)
        assertEquals(geometry.centerY, ry, 0.001f)

        // Its distance from the centre is invariant: rotation moves the handle, never its reach.
        val reach = geometry.centerY - (geometry.y - 28f)
        listOf(0f, 45f, 90f, 210f).forEach { angle ->
            val (hx, hy) = geometry.copy(rotation = angle).rotationHandlePoint(28f)
            val actual = Math.hypot(
                (hx - geometry.centerX).toDouble(),
                (hy - geometry.centerY).toDouble(),
            ).toFloat()
            assertEquals("reach at $angle°", reach, actual, 0.001f)
        }
    }

    @Test
    fun theVisualTransformAndTheHitTestShareOneCentre() {
        val geometry = SceneGeometry(x = 100f, y = 100f, width = 200f, height = 100f, rotation = 37f)

        // pageToLocal is the exact inverse of localToPage. If drawing and hit testing had drifted onto two
        // centres, this round trip would not close.
        listOf(100f to 100f, 300f to 200f, 150f to 180f, 0f to 0f).forEach { (px, py) ->
            val (lx, ly) = geometry.pageToLocal(px, py)
            val (bx, by) = geometry.localToPage(lx, ly)
            assertEquals(px, bx, 0.001f)
            assertEquals(py, by, 0.001f)
        }

        // And every drawn corner hit-tests as inside, which is the property the user experiences: tapping the
        // visible corner of a rotated frame selects it.
        ResizeCorner.entries.forEach { corner ->
            val (cx, cy) = geometry.rotatedCornerOf(corner)
            // Nudged a hair toward the centre so the test is about containment, not float equality on an edge.
            val towardX = cx + (geometry.centerX - cx) * 0.01f
            val towardY = cy + (geometry.centerY - cy) * 0.01f
            assertTrue("$corner must hit test inside", geometry.containsRotated(towardX, towardY))
        }

        // The centre is inside at every angle; a point well outside the diagonal never is.
        assertTrue(geometry.containsRotated(geometry.centerX, geometry.centerY))
        assertFalse(geometry.containsRotated(geometry.centerX + 400f, geometry.centerY))
    }

    @Test
    fun resizingARotatedObjectHoldsTheOppositeCornerStillOnScreen() {
        val geometry = SceneGeometry(x = 100f, y = 100f, width = 200f, height = 100f, rotation = 30f)
        val corner = ResizeCorner.BOTTOM_RIGHT
        val anchorBefore = geometry.rotatedCornerOf(corner.opposite())

        // Drag the bottom-right handle somewhere arbitrary in page space.
        val resized = geometry.resizedToRotated(corner, px = 340f, py = 300f)

        val anchorAfter = resized.rotatedCornerOf(corner.opposite())
        assertEquals("anchor x must not move", anchorBefore.first, anchorAfter.first, 0.01f)
        assertEquals("anchor y must not move", anchorBefore.second, anchorAfter.second, 0.01f)
        assertEquals("rotation is not touched by a resize", 30f, resized.rotation, 0.001f)
        assertTrue("the box actually grew", resized.width > geometry.width)
    }

    @Test
    fun everyCornersOppositeIsItsDiagonal() {
        assertEquals(ResizeCorner.BOTTOM_RIGHT, ResizeCorner.TOP_LEFT.opposite())
        assertEquals(ResizeCorner.TOP_LEFT, ResizeCorner.BOTTOM_RIGHT.opposite())
        assertEquals(ResizeCorner.BOTTOM_LEFT, ResizeCorner.TOP_RIGHT.opposite())
        assertEquals(ResizeCorner.TOP_RIGHT, ResizeCorner.BOTTOM_LEFT.opposite())
    }

    @Test
    fun aDragInsideARotatedLayerIsCarriedBackIntoPageSpace() {
        // A vector rotates but does not translate: this must not pick up the centre, or a two-point nudge
        // would jump the object to the middle of itself.
        val geometry = SceneGeometry(x = 100f, y = 100f, width = 200f, height = 100f, rotation = 90f)
        val (dx, dy) = geometry.rotateVectorToPage(10f, 0f)
        assertEquals(0f, dx, 0.001f)
        assertEquals(10f, dy, 0.001f)

        val unrotated = geometry.copy(rotation = 0f)
        assertEquals(10f to 0f, unrotated.rotateVectorToPage(10f, 0f))
    }

    // ── 2. Keyboard arbitration: the text field goes first ───────────────────────────────────────────

    @Test
    fun backspaceWhileEditingTextIsNotAnObjectDelete() {
        val backspace = FlowShortcut("backspace")
        assertEquals(
            "the canvas owns Backspace only when no field does",
            DocumentKeyAction.Command(DocumentIds.DELETE),
            DocumentKeyboardMap.resolve(backspace, textEntryActive = false),
        )
        assertEquals(
            DocumentKeyAction.Unhandled,
            DocumentKeyboardMap.resolve(backspace, textEntryActive = true),
        )
    }

    @Test
    fun deleteResolvesNormallyWhenTheCanvasOwnsFocus() {
        assertEquals(
            DocumentKeyAction.Command(DocumentIds.DELETE),
            DocumentKeyboardMap.resolve(FlowShortcut("delete"), textEntryActive = false),
        )
        assertEquals(
            DocumentKeyAction.Unhandled,
            DocumentKeyboardMap.resolve(FlowShortcut("delete"), textEntryActive = true),
        )
    }

    @Test
    fun arrowKeysWhileEditingTextBelongToTheCaretNotToNudging() {
        listOf("arrowleft", "arrowright", "arrowup", "arrowdown").forEach { key ->
            assertTrue(
                "$key must nudge when the canvas owns it",
                DocumentKeyboardMap.resolve(FlowShortcut(key), textEntryActive = false)
                    is DocumentKeyAction.Command,
            )
            assertEquals(
                "$key must reach the caret while typing",
                DocumentKeyAction.Unhandled,
                DocumentKeyboardMap.resolve(FlowShortcut(key), textEntryActive = true),
            )
            // Shift+arrow is text *selection* while typing, and a coarse move otherwise.
            assertEquals(
                "shift+$key must reach the selection while typing",
                DocumentKeyAction.Unhandled,
                DocumentKeyboardMap.resolve(FlowShortcut(key, shift = true), textEntryActive = true),
            )
        }
    }

    @Test
    fun modifiedChordsStillReachTheCanvasWhileTyping() {
        // Ctrl chords are not text input, so withholding them would make the editor's shortcuts vanish the
        // moment a caret existed — the over-correction this rule is written to avoid.
        assertEquals(
            DocumentKeyAction.Command(DocumentIds.DUPLICATE),
            DocumentKeyboardMap.resolve(FlowShortcut("d", ctrl = true), textEntryActive = true),
        )
        assertEquals(
            DocumentKeyAction.Undo,
            DocumentKeyboardMap.resolve(DocumentKeyboardMap.UNDO, textEntryActive = true),
        )
        assertEquals(
            DocumentKeyAction.Redo,
            DocumentKeyboardMap.resolve(DocumentKeyboardMap.REDO, textEntryActive = true),
        )
        assertEquals(
            DocumentKeyAction.Redo,
            DocumentKeyboardMap.resolve(DocumentKeyboardMap.REDO_ALT, textEntryActive = true),
        )
    }

    @Test
    fun ctrlAIsSelectAllTextWhileTypingAndSelectAllObjectsOtherwise() {
        assertEquals(
            DocumentKeyAction.SelectAllObjects,
            DocumentKeyboardMap.resolve(DocumentKeyboardMap.SELECT_ALL, textEntryActive = false),
        )
        assertEquals(
            "a text field's own select-all must win",
            DocumentKeyAction.Unhandled,
            DocumentKeyboardMap.resolve(DocumentKeyboardMap.SELECT_ALL, textEntryActive = true),
        )
    }

    @Test
    fun escapeAlwaysResolvesTheHierarchyAndUnboundKeysAreNeverConsumed() {
        assertEquals(
            DocumentKeyAction.Back,
            DocumentKeyboardMap.resolve(DocumentKeyboardMap.ESCAPE, textEntryActive = true),
        )
        assertEquals(
            DocumentKeyAction.Back,
            DocumentKeyboardMap.resolve(DocumentKeyboardMap.ESCAPE, textEntryActive = false),
        )
        // An ordinary letter is bound to nothing and must fall straight through to the IME.
        assertEquals(
            DocumentKeyAction.Unhandled,
            DocumentKeyboardMap.resolve(FlowShortcut("q"), textEntryActive = false),
        )
        assertEquals(
            DocumentKeyAction.Unhandled,
            DocumentKeyboardMap.resolve(FlowShortcut("a"), textEntryActive = false),
        )
    }

    @Test
    fun theRuntimeProtectsObjectTextEvenIfTheHostReportsNoFocus() {
        val rt = editing(page("p1", obj("a", content = ParagraphBlock(id = "c-a", content = listOf(InlineText("hello"))))))
        rt.selectObjects(listOf("a"))
        assertTrue(rt.beginContentEdit("a"))

        // The host passes textEntryActive=false; the caret alone must still withhold the key.
        assertFalse(
            "a caret in a scene object protects that object's text by itself",
            rt.handleShortcut(FlowShortcut("backspace"), textEntryActive = false),
        )
        assertNotNull("the object must still exist", rt.controller.findObject("a"))
        assertEquals("hello", textOf(rt, "a", "c-a"))
    }

    @Test
    fun deleteFromTheKeyboardStillWorksWhenNothingIsBeingTypedIn() {
        val rt = editing(page("p1", obj("a")))
        rt.selectObjects(listOf("a"))
        assertTrue(rt.handleShortcut(FlowShortcut("delete"), textEntryActive = false))
        assertNull("the object is gone", rt.controller.findObject("a"))
    }

    // ── 3. Back/Escape hierarchy ────────────────────────────────────────────────────────────────────

    @Test
    fun backLeavesTextEditingFirstThenTheSelectionThenTheEditor() {
        val rt = editing(page("p1", obj("a")))
        rt.selectObjects(listOf("a"))
        assertTrue(rt.beginContentEdit("a"))

        // One: the caret leaves and the object stays selected.
        assertEquals(DocumentBackResult.ExitedContentEdit, rt.back())
        assertNull("the caret is gone", rt.editingObjectId)
        assertEquals("the selection survives", listOf("a"), rt.scope.selectedObjectIds())

        // Two: the selection clears.
        assertEquals(DocumentBackResult.ClearedSelection, rt.back())
        assertTrue(rt.scope.selectedObjectIds().isEmpty())

        // Three: nothing is left to unwind, so the host may navigate.
        assertEquals(DocumentBackResult.LeftEditor, rt.back())
    }

    @Test
    fun escapeWhileEditingExitsContentEditingRatherThanTheEditor() {
        val rt = editing(page("p1", obj("a")))
        rt.selectObjects(listOf("a"))
        rt.beginContentEdit("a")

        assertTrue(rt.handleShortcut(DocumentKeyboardMap.ESCAPE, textEntryActive = true))
        assertNull(rt.editingObjectId)
        assertEquals(listOf("a"), rt.scope.selectedObjectIds())
    }

    @Test
    fun aStaleCaretNeverSurvivesWithAnEmptySelection() {
        val rt = editing(page("p1", obj("a")))
        rt.selectObjects(listOf("a"))
        rt.beginContentEdit("a")
        rt.back()
        rt.back()
        // Both levels unwound in order, so the invalid combination — caret set, nothing selected — cannot
        // be reached by pressing Back.
        assertNull(rt.editingObjectId)
        assertTrue(rt.scope.selectedObjectIds().isEmpty())
    }

    // ── 4. Read mode is actually read-only ──────────────────────────────────────────────────────────

    @Test
    fun switchingToReadModeEndsContentEditingAndKeepsTheSelection() {
        val rt = editing(page("p1", obj("a", content = ParagraphBlock(id = "c-a", content = listOf(InlineText("hi"))))))
        rt.selectObjects(listOf("a"))
        assertTrue(rt.beginContentEdit("a"))
        val before = rt.controller.document()

        rt.setIntent(WorkingIntent.READ)

        assertNull("no live field may remain in Read", rt.editingObjectId)
        assertEquals("the selection is about attention, not editing", listOf("a"), rt.scope.selectedObjectIds())
        assertEquals("switching modes is not an edit", before, rt.controller.document())
        assertEquals(WorkingIntent.READ, rt.view.intent)
    }

    @Test
    fun readModeRefusesTextEditsThatArriveFromAStaleField() {
        val rt = editing(page("p1", obj("a", content = ParagraphBlock(id = "c-a", content = listOf(InlineText("hi"))))))
        rt.selectObjects(listOf("a"))
        rt.beginContentEdit("a")
        rt.setIntent(WorkingIntent.READ)

        // A field that outlived the mode switch must not be able to commit.
        assertFalse(rt.editObjectText("a", "c-a", null, "hi", "hijacked"))
        assertEquals("hi", textOf(rt, "a", "c-a"))
    }

    @Test
    fun readModeRefusesTodoTogglesToo() {
        val todo = TodoListBlock(
            id = "c-t",
            items = listOf(TodoItem(id = "i1", content = listOf(InlineText("task")), checked = false)),
        )
        val rt = editing(page("p1", obj("t", content = todo)))
        rt.setIntent(WorkingIntent.READ)
        assertFalse(rt.toggleObjectTodo("t", "c-t", "i1"))
        assertFalse((contentOf(rt, "t") as TodoListBlock).items.first().checked)
    }

    @Test
    fun returningToEditModeDoesNotReopenTheOldCaret() {
        val rt = editing(page("p1", obj("a")))
        rt.selectObjects(listOf("a"))
        rt.beginContentEdit("a")
        rt.setIntent(WorkingIntent.READ)
        rt.setIntent(WorkingIntent.EDIT)
        assertNull("Read → Edit must not resurrect the caret", rt.editingObjectId)
    }

    @Test
    fun switchingToReadCancelsAnInFlightGestureWithoutCommittingIt() {
        val rt = editing(page("p1", obj("a", x = 10f, y = 10f)))
        rt.selectObjects(listOf("a"))
        rt.beginManipulation(ActiveManipulation.Kind.MOVE)
        rt.updateMove(40f, 40f)
        val depth = rt.controller.undoDepth()

        rt.setIntent(WorkingIntent.READ)

        assertNull("no gesture may survive into Read", rt.view.manipulation)
        assertEquals("an abandoned drag is not history", depth, rt.controller.undoDepth())
        assertEquals("and it did not move", 10f, rt.controller.findObject("a")!!.second.geometry.x, 0.001f)
    }

    @Test
    fun switchingToAdaptiveViewAlsoEndsContentEditing() {
        // The other view axis has the same failure mode: the host only renders a live field in Original View,
        // so a caret surviving into Adaptive is state pointing at a text field that is no longer on screen.
        val rt = editing(page("p1", obj("a")))
        rt.selectObjects(listOf("a"))
        assertTrue(rt.beginContentEdit("a"))

        rt.setRepresentation(LayoutRepresentation.ADAPTIVE)

        assertNull("no caret may survive into Adaptive View", rt.editingObjectId)
        assertEquals("the selection still survives", listOf("a"), rt.scope.selectedObjectIds())
    }

    // ── 5. Nested content addressing ────────────────────────────────────────────────────────────────

    @Test
    fun editingTheSecondChildOfAQuoteEditsTheSecondChild() {
        val quote = QuoteBlock(
            id = "c-q",
            children = listOf(
                ParagraphBlock(id = "q1", content = listOf(InlineText("first"))),
                ParagraphBlock(id = "q2", content = listOf(InlineText("second"))),
            ),
        )
        val rt = editing(page("p1", obj("q", content = quote)))

        assertTrue(rt.editObjectText("q", "q2", null, "second", "second edited"))

        assertEquals("the first child must be untouched", "first", textOf(rt, "q", "q1"))
        assertEquals("second edited", textOf(rt, "q", "q2"))
    }

    @Test
    fun editingTheFirstChildOfAQuoteDoesNotDisturbTheSecond() {
        val quote = QuoteBlock(
            id = "c-q",
            children = listOf(
                ParagraphBlock(id = "q1", content = listOf(InlineText("first"))),
                ParagraphBlock(id = "q2", content = listOf(InlineText("second"))),
            ),
        )
        val rt = editing(page("p1", obj("q", content = quote)))

        assertTrue(rt.editObjectText("q", "q1", null, "first", "first edited"))

        assertEquals("first edited", textOf(rt, "q", "q1"))
        assertEquals("second", textOf(rt, "q", "q2"))
    }

    @Test
    fun editingAParagraphNestedInAContainerEditsThatExactChild() {
        val container = ContainerBlock(
            id = "c-box",
            children = listOf(
                ParagraphBlock(id = "b1", content = listOf(InlineText("left"))),
                ParagraphBlock(id = "b2", content = listOf(InlineText("right"))),
            ),
        )
        val rt = editing(page("p1", obj("box", content = container)))

        assertTrue(rt.editObjectText("box", "b2", null, "right", "right edited"))

        assertEquals("left", textOf(rt, "box", "b1"))
        assertEquals("right edited", textOf(rt, "box", "b2"))
    }

    @Test
    fun editingAParagraphNestedInACalloutEditsThatExactChild() {
        val callout = CalloutBlock(
            id = "c-note",
            title = "Note",
            children = listOf(
                ParagraphBlock(id = "n1", content = listOf(InlineText("body one"))),
                ParagraphBlock(id = "n2", content = listOf(InlineText("body two"))),
            ),
        )
        val rt = editing(page("p1", obj("note", content = callout)))

        assertTrue(rt.editObjectText("note", "n2", null, "body two", "body two edited"))

        assertEquals("body one", textOf(rt, "note", "n1"))
        assertEquals("body two edited", textOf(rt, "note", "n2"))
    }

    @Test
    fun deeplyNestedContentIsStillAddressedExactly() {
        // A paragraph inside a callout inside a container: the recursion has to hold at depth, not just once.
        val nested = ContainerBlock(
            id = "c-outer",
            children = listOf(
                CalloutBlock(
                    id = "inner",
                    children = listOf(ParagraphBlock(id = "deep", content = listOf(InlineText("buried")))),
                ),
            ),
        )
        val rt = editing(page("p1", obj("outer", content = nested)))

        assertTrue(rt.editObjectText("outer", "deep", null, "buried", "found"))
        assertEquals("found", textOf(rt, "outer", "deep"))
    }

    @Test
    fun aStaleOrUnknownBlockIdFailsClosed() {
        val quote = QuoteBlock(
            id = "c-q",
            children = listOf(ParagraphBlock(id = "q1", content = listOf(InlineText("only")))),
        )
        val rt = editing(page("p1", obj("q", content = quote)))
        val before = rt.controller.document()

        assertFalse(
            "an unresolvable address must not fall back to the root block",
            rt.editObjectText("q", "no-such-block", null, "only", "hijacked"),
        )
        assertEquals("nothing was written anywhere", before, rt.controller.document())
        assertEquals("only", textOf(rt, "q", "q1"))
    }

    @Test
    fun listAndTodoItemAddressingStillWorks() {
        val bullets = BulletListBlock(
            id = "c-l",
            items = listOf(
                ListItem(id = "i1", content = listOf(InlineText("one"))),
                ListItem(id = "i2", content = listOf(InlineText("two"))),
            ),
        )
        val rt = editing(page("p1", obj("l", content = bullets)))

        assertTrue(rt.editObjectText("l", "c-l", "i2", "two", "two edited"))
        val list = contentOf(rt, "l") as BulletListBlock
        assertEquals("one", list.items[0].content.plainText())
        assertEquals("two edited", list.items[1].content.plainText())
    }

    @Test
    fun aNestedTodoListIsTogglableByItsOwnBlockId() {
        // The old cast against the object's *root* content silently did nothing for every nested checklist.
        val nested = ContainerBlock(
            id = "c-box",
            children = listOf(
                TodoListBlock(
                    id = "todo",
                    items = listOf(TodoItem(id = "i1", content = listOf(InlineText("task")), checked = false)),
                ),
            ),
        )
        val rt = editing(page("p1", obj("box", content = nested)))

        assertTrue(rt.toggleObjectTodo("box", "todo", "i1"))
        val todo = listOf(contentOf(rt, "box")).findById("todo") as TodoListBlock
        assertTrue(todo.items.first().checked)
    }

    @Test
    fun typingInTwoDifferentNestedBlocksProducesTwoUndoSteps() {
        // Coalescing keys name the *field*, so two children of one quote are two writing runs. Keying on the
        // object would merge them and make one Undo swallow edits in both.
        val quote = QuoteBlock(
            id = "c-q",
            children = listOf(
                ParagraphBlock(id = "q1", content = listOf(InlineText(""))),
                ParagraphBlock(id = "q2", content = listOf(InlineText(""))),
            ),
        )
        val rt = editing(page("p1", obj("q", content = quote)))
        val depth = rt.controller.undoDepth()

        rt.editObjectText("q", "q1", null, "", "a")
        rt.editObjectText("q", "q1", null, "a", "ab")
        rt.editObjectText("q", "q2", null, "", "z")

        assertEquals("one run per field", depth + 2, rt.controller.undoDepth())
        assertEquals("ab", textOf(rt, "q", "q1"))
        assertEquals("z", textOf(rt, "q", "q2"))
    }

    // ── 7. CodeBlock edit policy ────────────────────────────────────────────────────────────────────

    @Test
    fun aCodeBlockUsesTheCanonicalSceneHistoryPath() {
        val code = CodeBlock(id = "c-code", language = "kotlin", code = "fun main() {}")
        val rt = editing(page("p1", obj("code", content = code)))

        assertTrue(
            "a code edit must commit through the owning scene transaction",
            rt.editObjectText("code", "c-code", null, "fun main() {}", "fun main() { changed }"),
        )
        assertEquals("fun main() { changed }", (contentOf(rt, "code") as CodeBlock).code)
        assertTrue(rt.undo())
        assertEquals("fun main() {}", (contentOf(rt, "code") as CodeBlock).code)
    }

    // ── 8. Inspector transaction lifecycle ──────────────────────────────────────────────────────────

    @Test
    fun aNumericEditingSessionIsOneHistoryTransaction() {
        val rt = editing(page("p1", obj("a", x = 0f, y = 0f, width = 100f, height = 50f)))
        rt.selectObjects(listOf("a"))
        val depth = rt.controller.undoDepth()

        // The draft sequence a user types. Only the committed value reaches the controller — this is the
        // shape the composable's draft/commit lifecycle produces, and the reason it exists: committing each
        // intermediate value cost one undo step per digit and visibly jumped the object through 1 and 12.
        assertTrue(rt.applyGeometryField(GeometryInspector.Field.WIDTH, "120"))

        assertEquals("one editing session is one transaction", depth + 1, rt.controller.undoDepth())
        assertEquals(120f, rt.controller.findObject("a")!!.second.geometry.width, 0.001f)

        assertTrue(rt.undo())
        assertEquals("one Undo restores it", 100f, rt.controller.findObject("a")!!.second.geometry.width, 0.001f)
    }

    @Test
    fun anInvalidDraftNeverMutatesGeometry() {
        val rt = editing(page("p1", obj("a", x = 25f)))
        rt.selectObjects(listOf("a"))
        val depth = rt.controller.undoDepth()

        // "1." is deliberately absent: Java parses it as 1.0, so it is a *valid* partial entry rather than a
        // rejected one, and the draft lifecycle — not the parser — is what stops it committing mid-typing.
        listOf("", "-", "abc", "NaN", "1e40x", "12x").forEach { bad ->
            assertFalse("'$bad' must be refused", rt.applyGeometryField(GeometryInspector.Field.X, bad))
        }

        assertEquals("nothing entered history", depth, rt.controller.undoDepth())
        assertEquals("and the object did not move", 25f, rt.controller.findObject("a")!!.second.geometry.x, 0.001f)
    }

    @Test
    fun aLockedFieldRefusesAndSaysWhichLock() {
        val rt = editing(page("p1", obj("a", lock = SceneLock.Position)))
        rt.selectObjects(listOf("a"))

        assertFalse(rt.applyGeometryField(GeometryInspector.Field.X, "50"))
        assertEquals("This object's position is locked", rt.lastFailure)
        assertEquals(0f, rt.controller.findObject("a")!!.second.geometry.x, 0.001f)
    }

    @Test
    fun anInspectorCommitLandsOnTheSelectedObjectAndNotAnother() {
        val rt = editing(page("p1", obj("a", x = 0f), obj("b", x = 200f)))
        rt.selectObjects(listOf("b"))

        assertTrue(rt.applyGeometryField(GeometryInspector.Field.X, "300"))

        assertEquals("the selected object moved", 300f, rt.controller.findObject("b")!!.second.geometry.x, 0.001f)
        assertEquals("the other one did not", 0f, rt.controller.findObject("a")!!.second.geometry.x, 0.001f)
    }

    @Test
    fun anInspectorCommitEndsAnInFlightGestureRatherThanRacingIt() {
        val rt = editing(page("p1", obj("a", x = 10f)))
        rt.selectObjects(listOf("a"))
        rt.beginManipulation(ActiveManipulation.Kind.MOVE)
        rt.updateMove(30f, 0f)

        assertTrue(rt.applyGeometryField(GeometryInspector.Field.X, "500"))

        assertNull("the gesture is settled first", rt.view.manipulation)
        assertEquals("the typed value wins", 500f, rt.controller.findObject("a")!!.second.geometry.x, 0.001f)
    }

    // ── 9. Mixed locked-page result accuracy ────────────────────────────────────────────────────────

    @Test
    fun aMixedLockedScopeReportsOnlyThePagesItActuallyChanged() {
        val unlocked = PageGeometry.a4().copy(structureLocked = false)
        val locked = PageGeometry.a4().copy(structureLocked = true)
        val ctl = DocumentSceneController(
            EditTarget("note:1"),
            BlockDocument(
                listOf(
                    page("p1", geometry = unlocked),
                    page("p2", geometry = unlocked),
                    page("p3", geometry = unlocked),
                    page("p4", geometry = locked),
                    page("p5", geometry = locked),
                ),
            ),
        )

        val outcome = ctl.editPageGeometry(listOf("p1", "p2", "p3", "p4", "p5"), "Two columns") {
            it.copy(columns = PageColumns(2))
        }

        assertEquals("three editable pages changed", 3, outcome.changedCount)
        assertEquals("two locked pages were skipped", 2, outcome.skippedLockedCount)
        assertEquals(listOf("p1", "p2", "p3"), outcome.changedIds)
        assertEquals(listOf("p4", "p5"), outcome.skippedLockedIds)
        assertTrue(outcome.didChange)
        assertFalse("something changed, so this is not a lock refusal", outcome.blockedByLock)
        assertNotNull("the skip is stated, not hidden", outcome.skippedNote())

        // And the document agrees with the report.
        assertEquals(2, ctl.page("p1")!!.geometry.columns.count)
        assertEquals(1, ctl.page("p4")!!.geometry.columns.count)
        assertEquals("one user action is one transaction", 1, ctl.undoDepth())
    }

    @Test
    fun anAllUnlockedScopeReportsEveryPage() {
        val unlocked = PageGeometry.a4().copy(structureLocked = false)
        val ctl = DocumentSceneController(
            EditTarget("note:1"),
            BlockDocument(listOf(page("p1", geometry = unlocked), page("p2", geometry = unlocked))),
        )

        val outcome = ctl.editPageGeometry(listOf("p1", "p2"), "Two columns") {
            it.copy(columns = PageColumns(2))
        }

        assertEquals(2, outcome.changedCount)
        assertEquals(0, outcome.skippedLockedCount)
        assertNull("nothing to report as skipped", outcome.skippedNote())
    }

    @Test
    fun anAllLockedScopeChangesNothingAndReadsAsARefusal() {
        val locked = PageGeometry.a4().copy(structureLocked = true)
        val ctl = DocumentSceneController(
            EditTarget("note:1"),
            BlockDocument(listOf(page("p1", geometry = locked), page("p2", geometry = locked))),
        )

        val outcome = ctl.editPageGeometry(listOf("p1", "p2"), "Two columns") {
            it.copy(columns = PageColumns(2))
        }

        assertEquals(0, outcome.changedCount)
        assertEquals(2, outcome.skippedLockedCount)
        assertFalse(outcome.didChange)
        assertTrue("all-locked is a refusal the user must be told about", outcome.blockedByLock)
        assertEquals("nothing was committed", 0, ctl.undoDepth())
    }

    @Test
    fun aScopeAlreadyInTheRequestedStateIsANoOpNotARefusal() {
        val unlocked = PageGeometry.a4().copy(structureLocked = false, columns = PageColumns(2))
        val ctl = DocumentSceneController(
            EditTarget("note:1"),
            BlockDocument(listOf(page("p1", geometry = unlocked))),
        )

        val outcome = ctl.editPageGeometry(listOf("p1"), "Two columns") { it.copy(columns = PageColumns(2)) }

        assertFalse(outcome.didChange)
        assertFalse("nothing was refused", outcome.blockedByLock)
        assertEquals(listOf("p1"), outcome.unchangedIds)
        assertEquals("an unchanged value must not occupy an undo step", 0, ctl.undoDepth())
    }

    @Test
    fun aStalePageIdIsReportedAsMissingRatherThanCountedAsChanged() {
        val unlocked = PageGeometry.a4().copy(structureLocked = false)
        val ctl = DocumentSceneController(
            EditTarget("note:1"),
            BlockDocument(listOf(page("p1", geometry = unlocked))),
        )

        val outcome = ctl.editPageGeometry(listOf("p1", "ghost"), "Two columns") {
            it.copy(columns = PageColumns(2))
        }

        assertEquals(listOf("p1"), outcome.changedIds)
        assertEquals(listOf("ghost"), outcome.missingIds)
        assertEquals(1, outcome.changedCount)
    }

    @Test
    fun aPageControlCommandReportsTheTrueAffectedCount() {
        val unlocked = PageGeometry.a4().copy(structureLocked = false)
        val locked = PageGeometry.a4().copy(structureLocked = true)
        val rt = editing(
            page("p1", geometry = unlocked),
            page("p2", geometry = unlocked),
            page("p3", geometry = locked),
        )
        rt.setPageScope(PageScope.WholeDocument)

        val result = rt.invokePageControl("document.page.twoColumns", PageScope.WholeDocument)

        // The honesty rule from §4.10, at the surface a user actually reads.
        val success = result as? CommandResult.Success
        assertNotNull("a partial apply is still a success", success)
        assertEquals("only the two editable pages", 2, success!!.affectedCount)
        assertNotNull("and the locked one is accounted for", success.message)
    }

    // ── 10. Modifier-click multi-select ─────────────────────────────────────────────────────────────

    @Test
    fun modifierClickTogglesSelectionAndAPlainClickDoesNot() {
        assertEquals(
            DocumentClickAction.TOGGLE_SELECTION,
            documentClickDecision(SelectionModifiers(toggle = true), alreadySelected = false, editable = true),
        )
        assertEquals(
            "an already-selected object toggles off",
            DocumentClickAction.TOGGLE_SELECTION,
            documentClickDecision(SelectionModifiers(toggle = true), alreadySelected = true, editable = true),
        )
        assertEquals(
            "a plain click follows the ordinary select/enter-text rule",
            DocumentClickAction.NORMAL,
            documentClickDecision(SelectionModifiers(toggle = false), alreadySelected = false, editable = true),
        )
        assertEquals(
            "and must not be stolen from the second-tap-enters-text rule",
            DocumentClickAction.NORMAL,
            documentClickDecision(SelectionModifiers(toggle = false), alreadySelected = true, editable = true),
        )
    }

    @Test
    fun modifierClickWhileReadingIsStillJustAClick() {
        assertEquals(
            DocumentClickAction.NORMAL,
            documentClickDecision(SelectionModifiers(toggle = true), alreadySelected = false, editable = false),
        )
    }

    @Test
    fun togglingViaTheSemanticApiEntersMultiSelectAndAccumulates() {
        // The modifier-click route lands on the *existing* selection API rather than a second engine path.
        val rt = editing(page("p1", obj("a"), obj("b", x = 200f), obj("c", x = 400f)))

        rt.toggleObjectSelection("a")
        assertTrue("toggling enters the mode", rt.selectionMode)
        assertEquals(listOf("a"), rt.scope.selectedObjectIds())

        rt.toggleObjectSelection("b")
        assertEquals(listOf("a", "b"), rt.scope.selectedObjectIds())

        rt.toggleObjectSelection("a")
        assertEquals("toggling again removes it", listOf("b"), rt.scope.selectedObjectIds())
    }
}
