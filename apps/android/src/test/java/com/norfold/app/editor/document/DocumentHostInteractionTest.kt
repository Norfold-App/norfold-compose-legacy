package com.norfold.app.editor.document

import com.norfold.app.domain.BlockDocument
import com.norfold.app.domain.BlockDocumentJson
import com.norfold.app.domain.DocumentPageBlock
import com.norfold.app.domain.InlineText
import com.norfold.app.domain.PageGeometry
import com.norfold.app.domain.PageLimits
import com.norfold.app.domain.PageNumberFormat
import com.norfold.app.domain.PageNumbering
import com.norfold.app.domain.ParagraphBlock
import com.norfold.app.domain.ResizeCorner
import com.norfold.app.domain.SceneGeometry
import com.norfold.app.domain.SceneLock
import com.norfold.app.domain.SceneObject
import com.norfold.app.domain.SceneObjectKind
import com.norfold.app.domain.documentSettings
import com.norfold.app.domain.pageNumberLabels
import com.norfold.app.domain.pages
import com.norfold.app.editor.command.CommandResult
import com.norfold.app.editor.command.CommandSurface
import com.norfold.app.editor.flow.FlowShortcut
import com.norfold.app.editor.history.EditTarget
import com.norfold.app.editor.presentation.FormFactor
import com.norfold.app.editor.surface.DismissTrigger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The human-facing half of DocumentHost: the things a person actually does to a bounded document.
 *
 * These are deliberately separate from [DocumentHostRuntimeTest], which pins the engine contracts. What is
 * pinned here is the *interaction* — that dragging a corner anchors the opposite one, that a rotated object
 * responds to a tap where it is drawn, that Escape unwinds one level at a time, that a durable default is
 * still there after the session is rebuilt. Every one of these was capability without a route before, which
 * is exactly the class of gap no engine test can catch.
 */
class DocumentHostInteractionTest {

    private fun obj(
        id: String,
        x: Float = 0f,
        y: Float = 0f,
        width: Float = 100f,
        height: Float = 50f,
        rotation: Float = 0f,
        lock: SceneLock = SceneLock.None,
        kind: SceneObjectKind = SceneObjectKind.TextFrame,
        text: String = "",
    ) = SceneObject(
        id = id,
        kind = kind,
        geometry = SceneGeometry(x, y, width, height, rotation),
        lock = lock,
        content = ParagraphBlock(id = "c-$id", content = listOf(InlineText(text))),
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

    /** Puts a runtime into the state a user is in when they are actually editing an object. */
    private fun editing(vararg pages: DocumentPageBlock): DocumentHostRuntime = runtime(*pages).apply {
        scope.activePageId = pages.first().id
        setRepresentation(LayoutRepresentation.ORIGINAL)
        setIntent(WorkingIntent.EDIT)
    }

    // ── 1. Four-corner resize ───────────────────────────────────────────────────────────────────────

    @Test
    fun everyCornerResizesAgainstTheCornerOppositeIt() {
        val geometry = SceneGeometry(x = 100f, y = 100f, width = 200f, height = 100f)

        // Bottom-right: the top-left is the anchor, so x and y do not move.
        val br = geometry.resizedTo(ResizeCorner.BOTTOM_RIGHT, px = 340f, py = 260f)
        assertEquals(100f, br.x, 0.001f)
        assertEquals(100f, br.y, 0.001f)
        assertEquals(240f, br.width, 0.001f)
        assertEquals(160f, br.height, 0.001f)

        // Top-left: the bottom-right is the anchor, so the *origin* moves and the far edges stay put.
        val tl = geometry.resizedTo(ResizeCorner.TOP_LEFT, px = 60f, py = 70f)
        assertEquals(60f, tl.x, 0.001f)
        assertEquals(70f, tl.y, 0.001f)
        assertEquals("the right edge must not move", 300f, tl.right, 0.001f)
        assertEquals("the bottom edge must not move", 200f, tl.bottom, 0.001f)

        // Top-right: x is anchored, y moves.
        val tr = geometry.resizedTo(ResizeCorner.TOP_RIGHT, px = 350f, py = 60f)
        assertEquals(100f, tr.x, 0.001f)
        assertEquals(60f, tr.y, 0.001f)
        assertEquals(250f, tr.width, 0.001f)
        assertEquals(200f, tr.bottom, 0.001f)

        // Bottom-left: y is anchored, x moves.
        val bl = geometry.resizedTo(ResizeCorner.BOTTOM_LEFT, px = 40f, py = 250f)
        assertEquals(40f, bl.x, 0.001f)
        assertEquals(100f, bl.y, 0.001f)
        assertEquals(300f, bl.right, 0.001f)
        assertEquals(150f, bl.height, 0.001f)
    }

    @Test
    fun draggingACornerPastItsAnchorPinsAtMinimumRatherThanInvertingTheBox() {
        val geometry = SceneGeometry(x = 100f, y = 100f, width = 200f, height = 100f)

        // Dragged far past the opposite corner in both axes.
        val crossed = geometry.resizedTo(ResizeCorner.TOP_LEFT, px = 900f, py = 900f)

        assertEquals(SceneGeometry.MIN_SIZE, crossed.width, 0.001f)
        assertEquals(SceneGeometry.MIN_SIZE, crossed.height, 0.001f)
        assertTrue("width must never go negative", crossed.width > 0f)
        assertTrue("height must never go negative", crossed.height > 0f)
        // The anchor is still respected: the box stops *at* it rather than passing through it.
        assertEquals(300f, crossed.right, 0.001f)
        assertEquals(200f, crossed.bottom, 0.001f)
    }

    @Test
    fun aTopLeftResizeIsOneTransactionAndCancelsToTheExactStartingGeometry() {
        val rt = editing(page("p1", obj("a", x = 100f, y = 100f, width = 200f, height = 100f)))
        rt.selectObjects(listOf("a"))
        val before = rt.controller.findObject("a")!!.second.geometry
        val depth = rt.controller.undoDepth()

        rt.beginManipulation(ActiveManipulation.Kind.RESIZE)
        rt.updateResize(ResizeCorner.TOP_LEFT, 90f, 90f)
        rt.updateResize(ResizeCorner.TOP_LEFT, 70f, 80f)
        rt.updateResize(ResizeCorner.TOP_LEFT, 60f, 70f)

        assertEquals("the model is untouched while previewing", before, rt.controller.findObject("a")!!.second.geometry)
        assertTrue(rt.endManipulation())
        assertEquals("a whole resize is one undo step", depth + 1, rt.controller.undoDepth())

        val after = rt.controller.findObject("a")!!.second.geometry
        assertNotEquals(before, after)
        assertTrue(rt.undo())
        assertEquals("undo restores the exact pre-resize geometry", before, rt.controller.findObject("a")!!.second.geometry)
    }

    @Test
    fun cancellingAResizeLeavesTheGeometryExactlyAsItWasWithNoHistoryEntry() {
        val rt = editing(page("p1", obj("a", x = 100f, y = 100f, width = 200f, height = 100f)))
        rt.selectObjects(listOf("a"))
        val before = rt.controller.findObject("a")!!.second.geometry
        val depth = rt.controller.undoDepth()

        rt.beginManipulation(ActiveManipulation.Kind.RESIZE)
        rt.updateResize(ResizeCorner.BOTTOM_RIGHT, 500f, 500f)
        rt.cancelManipulation()

        assertEquals("cancel is exact because the model was never touched", before, rt.controller.findObject("a")!!.second.geometry)
        assertEquals("and it records nothing", depth, rt.controller.undoDepth())
    }

    @Test
    fun onlyThePrimaryObjectIsIndividuallyResized() {
        val rt = editing(page("p1", obj("a", x = 0f, y = 0f), obj("b", x = 300f, y = 300f)))
        rt.selectObjects(listOf("a", "b"))
        val bBefore = rt.controller.findObject("b")!!.second.geometry

        rt.beginManipulation(ActiveManipulation.Kind.RESIZE)
        rt.updateResize(ResizeCorner.BOTTOM_RIGHT, 400f, 400f)
        rt.endManipulation()

        assertNotEquals("the primary resized", 100f, rt.controller.findObject("a")!!.second.geometry.width)
        assertEquals("a secondary selected object is not silently resized too", bBefore, rt.controller.findObject("b")!!.second.geometry)
    }

    // ── 2. Rotation ─────────────────────────────────────────────────────────────────────────────────

    @Test
    fun rotationCommitsOnceAndCancelsExactly() {
        val rt = editing(page("p1", obj("a", x = 100f, y = 100f)))
        rt.selectObjects(listOf("a"))
        val before = rt.controller.findObject("a")!!.second.geometry
        val depth = rt.controller.undoDepth()

        rt.beginManipulation(ActiveManipulation.Kind.ROTATE)
        rt.updateRotation(15f)
        rt.updateRotation(30f)
        rt.updateRotation(45f)
        assertEquals("previewing never writes", before, rt.controller.findObject("a")!!.second.geometry)
        assertTrue(rt.endManipulation())

        assertEquals(45f, rt.controller.findObject("a")!!.second.geometry.rotation, 0.001f)
        assertEquals("a whole rotation is one undo step", depth + 1, rt.controller.undoDepth())

        rt.beginManipulation(ActiveManipulation.Kind.ROTATE)
        rt.updateRotation(200f)
        rt.cancelManipulation()
        assertEquals("cancel restores the committed angle exactly", 45f, rt.controller.findObject("a")!!.second.geometry.rotation, 0.001f)
    }

    @Test
    fun rotationIsRefusedForTablesAndForSizeLockedObjects() {
        val rt = editing(
            page(
                "p1",
                obj("table", kind = SceneObjectKind.Table),
                obj("locked", lock = SceneLock.Size),
            ),
        )

        rt.selectObjects(listOf("table"))
        assertNull("a table has no meaningful rotation", rt.beginManipulation(ActiveManipulation.Kind.ROTATE))

        rt.selectObjects(listOf("locked"))
        assertNull("a size lock blocks rotation", rt.beginManipulation(ActiveManipulation.Kind.ROTATE))
    }

    @Test
    fun aRotatedObjectIsHitWhereItIsDrawnAndNotWhereItsUnrotatedBoxWouldBe() {
        // A tall thin box rotated a quarter turn: it is drawn wide and short about the same centre.
        val geometry = SceneGeometry(x = 100f, y = 50f, width = 40f, height = 200f, rotation = 90f)
        val centerX = geometry.centerX
        val centerY = geometry.centerY

        // A point far to the side of the centre — outside the unrotated box, inside the drawn one.
        assertTrue("the drawn shape must respond", geometry.containsRotated(centerX + 80f, centerY))
        assertFalse("the unrotated box would have missed it", geometry.contains(centerX + 80f, centerY))

        // A point far above the centre — inside the unrotated box, outside the drawn one.
        assertFalse("empty space must not respond", geometry.containsRotated(centerX, centerY - 80f))
        assertTrue(geometry.contains(centerX, centerY - 80f))
    }

    @Test
    fun rotatedHitTestingPreservesZOrderAndOverlapCycling() {
        val back = obj("back", x = 100f, y = 100f, width = 200f, height = 200f)
        val front = obj("front", x = 100f, y = 100f, width = 200f, height = 200f, rotation = 30f)
        val rt = editing(page("p1", back, front))

        // Both cover the shared centre, so the stack must be front-most first by z, not by rotation.
        val stack = rt.controller.page("p1")!!.hitTest(200f, 200f)
        assertEquals(listOf("front", "back"), stack.map { it.id })

        // Repeat taps walk down that stack and wrap, which is what makes the object underneath reachable.
        assertEquals("front", rt.selectAt("p1", 200f, 200f))
        assertEquals("back", rt.selectAt("p1", 200f, 200f))
        assertEquals("front", rt.selectAt("p1", 200f, 200f))
    }

    // ── 3. Geometry inspector ───────────────────────────────────────────────────────────────────────

    @Test
    fun theInspectorRejectsNonFiniteAndUnparseableInputWithoutTouchingTheObject() {
        val rt = editing(page("p1", obj("a", x = 10f, y = 20f)))
        rt.selectObjects(listOf("a"))
        val before = rt.controller.findObject("a")!!.second.geometry
        val depth = rt.controller.undoDepth()

        listOf("", "   ", "abc", "12x", "NaN", "Infinity", "-Infinity", "1e400").forEach { bad ->
            assertNull("'$bad' must not parse", GeometryInspector.parse(GeometryInspector.Field.X, bad))
            assertFalse("'$bad' must not be applied", rt.applyGeometryField(GeometryInspector.Field.X, bad))
        }

        assertEquals("nothing moved", before, rt.controller.findObject("a")!!.second.geometry)
        assertEquals("and nothing was recorded", depth, rt.controller.undoDepth())
    }

    @Test
    fun eachInspectorFieldIsOneTransactionAndChangesOnlyItsOwnValue() {
        val rt = editing(page("p1", obj("a", x = 10f, y = 20f, width = 100f, height = 50f)))
        rt.selectObjects(listOf("a"))
        val depth = rt.controller.undoDepth()

        assertTrue(rt.applyGeometryField(GeometryInspector.Field.WIDTH, "180"))

        val after = rt.controller.findObject("a")!!.second.geometry
        assertEquals(180f, after.width, 0.001f)
        assertEquals("x is untouched", 10f, after.x, 0.001f)
        assertEquals("y is untouched", 20f, after.y, 0.001f)
        assertEquals("height is untouched", 50f, after.height, 0.001f)
        assertEquals("one logical set is one undo step", depth + 1, rt.controller.undoDepth())
    }

    @Test
    fun theInspectorRefusesAFieldTheObjectsOwnLockForbids() {
        val rt = editing(page("p1", obj("a", lock = SceneLock.Position)))
        rt.selectObjects(listOf("a"))
        val before = rt.controller.findObject("a")!!.second.geometry

        assertFalse(rt.applyGeometryField(GeometryInspector.Field.X, "300"))
        assertEquals(before, rt.controller.findObject("a")!!.second.geometry)
        assertEquals("and says which lock", "This object's position is locked", rt.lastFailure)

        // The same object may still be resized: locks are per-axis, not all-or-nothing.
        assertTrue(rt.applyGeometryField(GeometryInspector.Field.WIDTH, "222"))
    }

    @Test
    fun rotationInputWrapsRatherThanBeingRejected() {
        // -90 and 270 are the same angle; refusing one would make a normal way to say "quarter turn" fail.
        assertEquals(270f, GeometryInspector.parse(GeometryInspector.Field.ROTATION, "-90")!!, 0.001f)
        assertEquals(10f, GeometryInspector.parse(GeometryInspector.Field.ROTATION, "370")!!, 0.001f)
        // Sizes clamp into the legal range instead of producing an object nothing can select.
        assertEquals(
            SceneGeometry.MIN_SIZE,
            GeometryInspector.parse(GeometryInspector.Field.WIDTH, "0")!!,
            0.001f,
        )
        assertEquals(
            PageLimits.MAX_SIDE_PT,
            GeometryInspector.parse(GeometryInspector.Field.HEIGHT, "999999")!!,
            0.001f,
        )
    }

    // ── 4. View: zoom, fit, pan ─────────────────────────────────────────────────────────────────────

    @Test
    fun zoomingAndFittingNeverChangeStoredPageGeometry() {
        val rt = runtime(page("p1", obj("a", x = 40f, y = 60f, width = 120f, height = 80f)))
        rt.scope.activePageId = "p1"
        val pageBefore = rt.controller.page("p1")!!.geometry
        val objectBefore = rt.controller.findObject("a")!!.second.geometry
        rt.reportViewport(widthPt = 400f, heightPt = 700f)

        listOf(0.5f, 1.0f, 2.0f).forEach { zoom ->
            rt.setZoom(zoom)
            assertEquals("zoom is a draw-time scale only", pageBefore, rt.controller.page("p1")!!.geometry)
            assertEquals(objectBefore, rt.controller.findObject("a")!!.second.geometry)
        }

        rt.fitWidth()
        assertEquals(pageBefore, rt.controller.page("p1")!!.geometry)
        rt.fitPage()
        assertEquals(pageBefore, rt.controller.page("p1")!!.geometry)
        assertEquals(objectBefore, rt.controller.findObject("a")!!.second.geometry)
    }

    @Test
    fun fitWidthUsesTheWidestPageAndFitPageSatisfiesBothDimensions() {
        val wide = PageGeometry.a4().copy(width = 1000f, height = 500f, structureLocked = false)
        val rt = runtime(page("p1"), page("p2", geometry = wide))
        rt.scope.activePageId = "p2"
        rt.reportViewport(widthPt = 500f, heightPt = 200f)

        // Fit width must fit the page that would otherwise overflow, not merely the first one.
        assertEquals(0.5f, rt.fitWidth(), 0.001f)

        // Fit page takes the smaller ratio, or a tall page would still be cropped.
        assertEquals(0.4f, rt.fitPage(), 0.001f)
    }

    @Test
    fun zoomIsClampedAndPanIsRejectedWhenNonFinite() {
        val rt = runtime(page("p1"))
        rt.setZoom(1000f)
        assertEquals(DocumentViewState.MAX_ZOOM, rt.view.zoom, 0.001f)
        rt.setZoom(0f)
        assertEquals(DocumentViewState.MIN_ZOOM, rt.view.zoom, 0.001f)

        rt.setPan(120f)
        assertEquals(120f, rt.view.panX, 0.001f)
        rt.setPan(Float.NaN)
        assertEquals("a non-finite pan must not corrupt the viewport", 0f, rt.view.panX, 0.001f)
    }

    // ── 5. Multi-select ─────────────────────────────────────────────────────────────────────────────

    @Test
    fun objectMultiSelectCountsItsTargetsAndCancelRestoresThePreviousSelection() {
        val rt = editing(page("p1", obj("a"), obj("b", x = 200f), obj("c", x = 400f)))
        rt.selectObjects(listOf("a"))

        rt.enterSelectionMode("a")
        assertTrue(rt.selectionMode)
        rt.toggleObjectSelection("b")
        rt.toggleObjectSelection("c")
        assertEquals(3, rt.selectedObjectCount)

        rt.toggleObjectSelection("c")
        assertEquals("toggling removes as well as adds", 2, rt.selectedObjectCount)

        rt.cancelSelectionMode()
        assertFalse(rt.selectionMode)
        assertEquals("Cancel means cancel, not clear", listOf("a"), rt.scope.selectedObjectIds())
    }

    @Test
    fun doneLeavesMultiSelectKeepingTheTargetsSoTheNextCommandStillHasThem() {
        val rt = editing(page("p1", obj("a"), obj("b", x = 200f)))
        rt.enterSelectionMode("a")
        rt.toggleObjectSelection("b")

        rt.finishSelectionMode()

        assertFalse(rt.selectionMode)
        assertEquals(setOf("a", "b"), rt.scope.selectedObjectIds().toSet())
        assertTrue(rt.invoke(DocumentIds.DUPLICATE, CommandSurface.DOCK) is CommandResult.Success)
    }

    @Test
    fun pageMultiSelectPopulatesTheSelectedPagesScope() {
        val rt = runtime(page("p1"), page("p2"), page("p3"))
        rt.scope.activePageId = "p1"

        // The scope falls back to the current page while nothing is explicitly chosen — which is exactly why
        // it needed a visible way to populate it.
        assertEquals(listOf("p1"), PageScope.SelectedPages.resolve(rt.scope))

        rt.togglePageSelection("p2")
        rt.togglePageSelection("p3")
        assertEquals(listOf("p2", "p3"), rt.scope.selectedPageIds)
        assertEquals(listOf("p2", "p3"), PageScope.SelectedPages.resolve(rt.scope))
        assertEquals(2, rt.uiState().pageScopeCount.let { rt.setPageScope(PageScope.SelectedPages); rt.uiState().pageScopeCount })

        rt.togglePageSelection("p2")
        assertEquals(listOf("p3"), rt.scope.selectedPageIds)
        rt.clearPageSelection()
        assertEquals(emptyList<String>(), rt.scope.selectedPageIds)
    }

    @Test
    fun aPageSelectionThatNamesADeletedPageIsPruned() {
        val rt = runtime(page("p1"), page("p2"))
        rt.scope.activePageId = "p1"
        rt.togglePageSelection("p2")

        rt.scope.activePageId = "p2"
        rt.invoke(DocumentIds.PAGE_DELETE)

        assertFalse("a scope naming a deleted page would resolve against nothing", "p2" in rt.scope.selectedPageIds)
    }

    // ── 6. Keyboard ─────────────────────────────────────────────────────────────────────────────────

    @Test
    fun everyBoundChordResolvesToARegisteredCommandSoNoChordIsKeyboardOnly() {
        val rt = editing(page("p1", obj("a")))
        DocumentKeyboardMap.bindings.forEach { (chord, commandId) ->
            assertNotNull(
                "chord ${chord.display()} is bound to '$commandId', which is not registered",
                rt.commands.get(commandId),
            )
        }
    }

    @Test
    fun arrowKeysNudgeAndShiftArrowsTakeTheCoarseStepThroughTheSameCommands() {
        val rt = editing(page("p1", obj("a", x = 100f, y = 100f)))
        rt.selectObjects(listOf("a"))

        assertTrue(rt.handleShortcut(FlowShortcut("arrowright")))
        assertEquals(100f + DocumentCommands.NUDGE, rt.controller.findObject("a")!!.second.geometry.x, 0.001f)

        assertTrue(rt.handleShortcut(FlowShortcut("arrowdown", shift = true)))
        assertEquals(100f + DocumentCommands.NUDGE_LARGE, rt.controller.findObject("a")!!.second.geometry.y, 0.001f)
    }

    @Test
    fun keyboardDuplicateDeleteAndUndoGoThroughTheSameCanonicalPathAsTheDock() {
        val rt = editing(page("p1", obj("a", x = 100f, y = 100f)))
        rt.selectObjects(listOf("a"))

        assertTrue(rt.handleShortcut(FlowShortcut("d", ctrl = true)))
        assertEquals("Ctrl+D is one duplicate", 2, rt.controller.page("p1")!!.objects.size)

        assertTrue(rt.handleShortcut(DocumentKeyboardMap.UNDO))
        assertEquals("Ctrl+Z reverses it in one step", 1, rt.controller.page("p1")!!.objects.size)

        assertTrue(rt.handleShortcut(DocumentKeyboardMap.REDO))
        assertEquals(2, rt.controller.page("p1")!!.objects.size)

        rt.selectObjects(listOf("a"))
        assertTrue(rt.handleShortcut(FlowShortcut("delete")))
        assertNull(rt.controller.findObject("a"))
    }

    @Test
    fun anUnboundChordIsNotConsumedSoOrdinaryTypingFallsThrough() {
        val rt = editing(page("p1", obj("a")))
        rt.selectObjects(listOf("a"))
        assertFalse("plain 'k' is unbound", rt.handleShortcut(FlowShortcut("k")))
        assertFalse(rt.handleShortcut(FlowShortcut("t")))
    }

    @Test
    fun escapeUnwindsExactlyOneLevelAtATime() {
        val rt = editing(page("p1", obj("a")))
        rt.selectObjects(listOf("a"))
        rt.openSurface(DocumentSurfaces.PAGE_CONTROL)
        rt.enterSelectionMode("a")

        // Surface first.
        assertTrue(rt.handleShortcut(DocumentKeyboardMap.ESCAPE))
        assertNull(rt.activeSurfaceId())
        assertTrue("the mode below it survived", rt.selectionMode)

        // Then the mode.
        assertTrue(rt.handleShortcut(DocumentKeyboardMap.ESCAPE))
        assertFalse(rt.selectionMode)
        assertTrue("the selection below it survived", rt.scope.selectedObjectIds().isNotEmpty())

        // Then the selection.
        assertEquals(DocumentBackResult.ClearedSelection, rt.back())
        assertTrue(rt.scope.selectedObjectIds().isEmpty())

        // Only then is leaving the editor the answer.
        assertEquals(DocumentBackResult.LeftEditor, rt.back())
    }

    @Test
    fun backCancelsALiveGestureRatherThanLeavingTheEditorMidDrag() {
        val rt = editing(page("p1", obj("a", x = 100f, y = 100f)))
        rt.selectObjects(listOf("a"))
        val before = rt.controller.findObject("a")!!.second.geometry

        rt.beginManipulation(ActiveManipulation.Kind.MOVE)
        rt.updateMove(200f, 200f)
        rt.scope.selectObjects(emptyList())

        assertEquals(DocumentBackResult.CancelledManipulation, rt.back())
        assertNull(rt.view.manipulation)
        assertEquals(before, rt.controller.findObject("a")!!.second.geometry)
    }

    // ── 7. Dock ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun theObjectDockOffersUnlockAndDeleteRatherThanTruncatingBeforeThem() {
        val rt = editing(page("p1", obj("a", lock = SceneLock.Full)))
        rt.selectObjects(listOf("a"))

        val dock = rt.uiState().dock
        assertTrue("Unlock must be reachable", dock.any { it.id == DocumentIds.UNLOCK })
        assertTrue("Delete must be reachable", dock.any { it.id == DocumentIds.DELETE })
        // Reachable, and honestly stated: a fully locked object cannot be deleted, and the dock says why
        // rather than hiding the row.
        assertFalse(dock.first { it.id == DocumentIds.DELETE }.enabled)
        assertNotNull(dock.first { it.id == DocumentIds.DELETE }.disabledReason)
    }

    // ── 8. Insertion ────────────────────────────────────────────────────────────────────────────────

    @Test
    fun insertingAnObjectNamesItsPageGetsFreshIdsAndBecomesTheSelection() {
        val rt = editing(page("p1"), page("p2"))
        rt.scope.activePageId = "p2"
        val depth = rt.controller.undoDepth()

        val result = rt.invoke(DocumentIds.INSERT_TEXT)

        assertTrue(result is CommandResult.Success)
        assertEquals("it landed on the explicitly active page", 1, rt.controller.page("p2")!!.objects.size)
        assertEquals("and not on any other page", 0, rt.controller.page("p1")!!.objects.size)
        assertEquals("one insert is one undo step", depth + 1, rt.controller.undoDepth())

        val created = rt.controller.page("p2")!!.objects.single()
        assertEquals("the new object is the selection", listOf(created.id), rt.scope.selectedObjectIds())

        rt.invoke(DocumentIds.INSERT_SHAPE)
        val ids = rt.controller.page("p2")!!.objects.map { it.id }
        assertEquals("two inserts produce two distinct identities", ids.size, ids.toSet().size)
        val contentIds = rt.controller.page("p2")!!.objects.map { it.content.id }
        assertEquals("and two distinct contents", contentIds.size, contentIds.toSet().size)
    }

    // ── 9. Page Control: value fields and limits ────────────────────────────────────────────────────

    @Test
    fun aCustomPageSizeStillHonoursBothTheSideLimitAndTheAreaLimit() {
        val rt = runtime(page("p1"))
        rt.scope.activePageId = "p1"

        // Far past 2000 mm on both sides: the result must satisfy *both* rules, not just the side one.
        rt.invokePageValue("document.page.customWidthMm", PageScope.CurrentPage, "9000")
        rt.invokePageValue("document.page.customHeightMm", PageScope.CurrentPage, "9000")

        val geometry = rt.controller.page("p1")!!.geometry
        assertTrue("side limit", geometry.width <= PageLimits.MAX_SIDE_PT + 0.01f)
        assertTrue("side limit", geometry.height <= PageLimits.MAX_SIDE_PT + 0.01f)
        assertTrue("area limit", geometry.width * geometry.height <= PageLimits.MAX_AREA_PT2 + 1f)
    }

    @Test
    fun anUnparseableValueIsRefusedAndChangesNothing() {
        val rt = runtime(page("p1"))
        rt.scope.activePageId = "p1"
        val before = rt.controller.page("p1")!!.geometry
        val depth = rt.controller.undoDepth()

        listOf("", "abc", "-40", "NaN").forEach { bad ->
            val result = rt.invokePageValue("document.page.marginTop", PageScope.CurrentPage, bad)
            assertTrue("'$bad' must be refused", result is CommandResult.Failure)
        }
        assertEquals(before, rt.controller.page("p1")!!.geometry)
        assertEquals(depth, rt.controller.undoDepth())
    }

    @Test
    fun everyPageValueControlIsDisabledWithAReasonWhileTheStructureIsLocked() {
        val locked = PageGeometry.a4().copy(structureLocked = true)
        val rt = runtime(page("p1", geometry = locked))
        rt.scope.activePageId = "p1"

        val rows = rt.pageValueControls(PageScope.CurrentPage)
        assertTrue(rows.isNotEmpty())
        rows.forEach { row ->
            assertFalse("'${row.label}' must refuse while the page is locked", row.enabled)
            assertNotNull("and must say why", row.disabledReason)
        }

        val result = rt.invokePageValue("document.page.marginTop", PageScope.CurrentPage, "40")
        assertTrue(result is CommandResult.Failure)
        assertEquals(locked, rt.controller.page("p1")!!.geometry)
    }

    @Test
    fun aScopeWhosePagesDisagreeReportsMixedRatherThanPickingOne() {
        val narrow = PageGeometry.a4().copy(structureLocked = false)
        val wide = PageGeometry.a4().copy(
            structureLocked = false,
            margins = com.norfold.app.domain.PageMargins(108f, 108f, 108f, 108f),
        )
        val rt = runtime(page("p1", geometry = narrow), page("p2", geometry = wide))
        rt.scope.activePageId = "p1"
        rt.setPageScope(PageScope.WholeDocument)

        val row = rt.pageValueControls(PageScope.WholeDocument).first { it.id == "document.page.marginTop" }
        assertTrue("the pages genuinely disagree", row.mixed)
        assertEquals("so no single value is claimed", "", row.value)
    }

    @Test
    fun everyPageGeometryFieldIsReachableThroughSomePageControl() {
        // The catalogue is the contract: a field the model stores but no control can reach is a setting the
        // user cannot change, which is indistinguishable from it not existing.
        val ids = (DocumentCommands.PAGE_SETUP_CONTROLS.map { it.id } +
            DocumentCommands.PAGE_VALUE_CONTROLS.map { it.id }).toSet()
        listOf(
            "document.page.sizeA4", "document.page.customWidthMm", "document.page.customHeightMm",
            "document.page.portrait", "document.page.landscape",
            "document.page.marginTop", "document.page.marginRight",
            "document.page.marginBottom", "document.page.marginLeft",
            "document.page.columnCount", "document.page.columnGutter",
            "document.page.backgroundColor", "document.page.clearBackground",
            "document.page.borderColor", "document.page.borderWidth", "document.page.borderInset",
            "document.page.bleed", "document.page.safeArea",
            "document.page.toggleGrid", "document.page.gridSpacing",
            "document.page.verticalGuides", "document.page.horizontalGuides",
            "document.page.headerHeight", "document.page.footerHeight",
            "document.page.headerText", "document.page.footerText",
            "document.page.showNumbers", "document.page.restartAt",
            "document.page.startSection", "document.page.sectionName",
            "document.page.restoreDefaults",
        ).forEach { id ->
            assertTrue("$id must exist in the Page Control catalogue", id in ids)
        }
        // Every numbering format is individually selectable rather than reachable only by cycling.
        PageNumberFormat.entries.forEach { format ->
            assertTrue("document.page.numberFormat${format.name}" in ids)
        }
    }

    // ── 10. Durable new-page default ────────────────────────────────────────────────────────────────

    @Test
    fun theNewPageDefaultSurvivesSerializationAndSessionRecreation() {
        val rt = runtime(page("p1"))
        rt.scope.activePageId = "p1"
        rt.invokePageControl("document.page.sizeLetter", PageScope.NewPagesByDefault)

        val saved = rt.controller.document()
        assertNotNull("the default is stored in the document", saved.documentSettings()?.newPageTemplate)

        // Through the wire and back, exactly as a save/reopen would.
        val roundTripped = BlockDocumentJson.decodeDocumentPayload(
            BlockDocumentJson.encodeDocumentPayload(saved),
        )
        assertEquals(
            saved.documentSettings()?.newPageTemplate,
            roundTripped.documentSettings()?.newPageTemplate,
        )

        // A brand-new session over the reloaded document still knows the default.
        val reopened = DocumentHostRuntime(
            controller = DocumentSceneController(EditTarget("note:1"), roundTripped),
            initialFormFactor = FormFactor(400, 800),
        )
        reopened.scope.activePageId = reopened.pages().first().id
        reopened.invoke(DocumentIds.PAGE_INSERT_AFTER)
        assertEquals(
            "the page added after reopening still uses the saved default",
            PageGeometry.letter().width,
            reopened.pages().last().geometry.width,
            0.5f,
        )
    }

    @Test
    fun theNewPageDefaultIsDocumentLevelAndNotSharedWithAnotherDocument() {
        val a = runtime(page("p1"))
        a.scope.activePageId = "p1"
        a.invokePageControl("document.page.sizeLetter", PageScope.NewPagesByDefault)

        val b = runtime(page("q1"))
        b.scope.activePageId = "q1"

        assertNull("a default set in one document must not leak into another", b.controller.newPageTemplate())
        b.invoke(DocumentIds.PAGE_INSERT_AFTER)
        assertEquals(
            "the untouched document still inherits from the page it follows",
            PageGeometry.a4().width,
            b.pages().last().geometry.width,
            0.5f,
        )
    }

    @Test
    fun aPageOverrideStaysDistinctFromTheNewPageDefault() {
        val rt = runtime(page("p1"))
        rt.scope.activePageId = "p1"

        rt.invokePageControl("document.page.sizeLetter", PageScope.NewPagesByDefault)
        rt.invokePageControl("document.page.sizeLegal", PageScope.CurrentPage)

        assertEquals(
            "the page carries its own override",
            PageGeometry.legal().height,
            rt.controller.page("p1")!!.geometry.height,
            0.5f,
        )
        assertEquals(
            "and the default is untouched by it",
            PageGeometry.letter().height,
            rt.controller.newPageTemplate()!!.height,
            0.5f,
        )
    }

    @Test
    fun clearingTheDefaultReturnsNewPagesToInheritingFromThePageTheyFollow() {
        val legal = PageGeometry.legal().copy(structureLocked = false)
        val rt = runtime(page("p1", geometry = legal))
        rt.scope.activePageId = "p1"
        rt.invokePageControl("document.page.sizeLetter", PageScope.NewPagesByDefault)
        assertNotNull(rt.controller.newPageTemplate())

        rt.clearNewPageTemplate()

        assertNull(rt.controller.newPageTemplate())
        rt.invoke(DocumentIds.PAGE_INSERT_AFTER)
        assertEquals(
            "with no default, the new page copies its anchor again",
            legal.height,
            rt.pages().last().geometry.height,
            0.5f,
        )
    }

    // ── 11. Migration refusal ───────────────────────────────────────────────────────────────────────

    @Test
    fun aRefusedMigrationLeavesTheDocumentUsableAndAsksForTheLegacyHost() {
        val legacy = BlockDocument(listOf(ParagraphBlock(id = "b1", content = listOf(InlineText("keep me")))))
        val rt = DocumentHostRuntime(
            controller = DocumentSceneController(EditTarget("note:1"), legacy),
            initialFormFactor = FormFactor(400, 800),
        )

        // A placement whose block is already gone makes the migration lossy, so it must be refused.
        val report = rt.migrateLegacyDocument(
            layout = mapOf("ghost" to com.norfold.app.domain.FreeformPlacement(x = 10f, y = 10f)),
        )

        assertNotNull(report)
        assertFalse(report!!.isLossless)
        assertTrue("the host must fall back rather than render a blank document", rt.requiresLegacyFallback())
        assertEquals("the original document is untouched", legacy.normalized(), rt.controller.document())
        assertTrue("no pages were created", rt.pages().isEmpty())
        assertTrue(
            "the orphan information is kept, not summarized away",
            report.orphanedPlacements.contains("ghost"),
        )
        assertTrue("and it is stated in terms the owner can act on", report.refusalDetail().contains("ghost"))
    }

    @Test
    fun aLosslessMigrationProducesAUsableBoundedDocumentWithNoRefusal() {
        val legacy = BlockDocument(listOf(ParagraphBlock(id = "b1", content = listOf(InlineText("hello")))))
        val rt = DocumentHostRuntime(
            controller = DocumentSceneController(EditTarget("note:1"), legacy),
            initialFormFactor = FormFactor(400, 800),
        )

        rt.migrateLegacyDocument()

        assertFalse(rt.requiresLegacyFallback())
        assertNull(rt.migrationRefusal)
        assertTrue(rt.pages().isNotEmpty())
        assertTrue(rt.controller.document().plainText().contains("hello"))
    }

    // ── 12. Object content ──────────────────────────────────────────────────────────────────────────

    @Test
    fun editingAnObjectsTextCoalescesIntoOneUndoStepPerField() {
        val rt = editing(page("p1", obj("a", text = "")))
        val depth = rt.controller.undoDepth()

        rt.editObjectText("a", null, "", "h")
        rt.editObjectText("a", null, "h", "he")
        rt.editObjectText("a", null, "he", "hel")
        rt.editObjectText("a", null, "hel", "hell")
        rt.editObjectText("a", null, "hell", "hello")

        assertEquals("hello", rt.controller.findObject("a")!!.second.content.plainText())
        assertEquals("a writing run is one undo step", depth + 1, rt.controller.undoDepth())

        assertTrue(rt.undo())
        assertEquals("", rt.controller.findObject("a")!!.second.content.plainText())
    }

    @Test
    fun contentEditingIsNeverEnteredByAFirstTapAndIsRefusedForLockedContent() {
        val rt = editing(page("p1", obj("a"), obj("locked", x = 300f, lock = SceneLock.Content)))

        // Selecting is not editing: a tap that selected an object must leave it draggable.
        rt.selectAt("p1", 10f, 10f)
        assertNull("selection alone must not open a caret", rt.editingObjectId)

        assertTrue("a deliberate second step opens it", rt.beginContentEdit("a"))
        assertEquals("a", rt.editingObjectId)

        rt.endContentEdit()
        assertNull(rt.editingObjectId)

        assertFalse("content-locked objects refuse", rt.beginContentEdit("locked"))
        assertNull(rt.editingObjectId)
        assertEquals("This object's content is locked", rt.lastFailure)
    }

    @Test
    fun aContentLockedObjectRefusesTextEditsEvenIfAskedDirectly() {
        val rt = editing(page("p1", obj("a", lock = SceneLock.Content, text = "original")))
        assertFalse(rt.editObjectText("a", null, "original", "changed"))
        assertEquals("original", rt.controller.findObject("a")!!.second.content.plainText())
    }

    @Test
    fun aCaretAndAGestureNeverOwnTheSameObjectAtOnce() {
        val rt = editing(page("p1", obj("a", x = 10f, y = 10f)))
        rt.selectObjects(listOf("a"))

        // A drag arriving while text is being edited takes over, so the keyboard is not left open over the
        // object being moved.
        assertTrue(rt.beginContentEdit("a"))
        assertNotNull(rt.beginManipulation(ActiveManipulation.Kind.MOVE))
        assertNull("the caret gives way to the drag", rt.editingObjectId)

        // And the reverse: entering the caret cancels the gesture rather than committing a stray movement the
        // user made on the way to tapping.
        rt.updateMove(40f, 40f)
        assertTrue(rt.beginContentEdit("a"))
        assertNull("the drag gives way to the caret", rt.view.manipulation)
        assertEquals("and the stray movement was not kept", 10f, rt.controller.findObject("a")!!.second.geometry.x, 0.001f)
    }

    @Test
    fun aTypedValueEndsALiveGestureRatherThanBeingOverwrittenByIt() {
        val rt = editing(page("p1", obj("a", x = 10f, y = 10f)))
        rt.selectObjects(listOf("a"))

        rt.beginManipulation(ActiveManipulation.Kind.MOVE)
        rt.updateMove(100f, 0f)
        // The drag commits from the geometry it started at, so a value written underneath it would simply be
        // replaced when the finger lifted.
        assertTrue(rt.applyGeometryField(GeometryInspector.Field.Y, "500"))

        assertNull("the gesture is settled first", rt.view.manipulation)
        assertEquals("the typed value survives", 500f, rt.controller.findObject("a")!!.second.geometry.y, 0.001f)
        assertEquals("and so does the drag it followed", 110f, rt.controller.findObject("a")!!.second.geometry.x, 0.001f)
    }

    @Test
    fun aCaretDoesNotSurviveTheObjectItWasInBeingDeletedOrUndoneAway() {
        val rt = editing(page("p1", obj("a", text = "hello")))
        rt.selectObjects(listOf("a"))
        assertTrue(rt.beginContentEdit("a"))

        rt.invoke(DocumentIds.DELETE)
        assertNull("a caret in a deleted object accepts keystrokes nothing can keep", rt.editingObjectId)

        // Undo brings it back, and re-editing has to be a deliberate act again rather than resuming silently.
        assertTrue(rt.undo())
        assertNull(rt.editingObjectId)

        // Undo can also *remove* the object being edited, which is why the check cannot live only at delete.
        // The selection is re-established explicitly: undoing a delete restores the object, not the selection
        // that named it, so a command issued now would otherwise have no target.
        rt.selectObjects(listOf("a"))
        rt.invoke(DocumentIds.DUPLICATE)
        val copy = rt.scope.selectedObjectIds().single()
        assertNotEquals("the duplicate is a new object", "a", copy)
        assertTrue(rt.beginContentEdit(copy))
        assertTrue(rt.undo())
        assertNull("the duplicate the caret was in is gone", rt.editingObjectId)
    }

    // ── 13. Object virtualization ───────────────────────────────────────────────────────────────────

    @Test
    fun offscreenObjectsAreNotComposedButSelectedAndPreviewedOnesAlwaysAre() {
        val objects = (0 until 200).map { obj("o$it", x = 20f, y = it * 100f, height = 40f) }
        val page = page("p1", *objects.toTypedArray())

        val visible = PageVirtualizer.visibleObjects(page, topPt = 0f, bottomPt = 400f)
        assertTrue("a long page must not compose every object", visible.size < objects.size)
        assertTrue(visible.any { it.id == "o0" })
        assertFalse("something 15,000 points away is not on screen", visible.any { it.id == "o150" })

        // The object under the user's finger is never culled, wherever it is.
        val kept = PageVirtualizer.visibleObjects(page, 0f, 400f, keepIds = setOf("o150"))
        assertTrue("a selected or manipulated object is always composed", kept.any { it.id == "o150" })

        // Nor is one whose *preview* has been dragged into view from off-page.
        val previewed = PageVirtualizer.visibleObjects(
            page,
            0f,
            400f,
            previewOf = { id -> if (id == "o150") SceneGeometry(20f, 100f, 100f, 40f) else null },
        )
        assertTrue("an object dragged into view must appear", previewed.any { it.id == "o150" })
    }

    @Test
    fun aRotatedObjectReachingIntoTheBandIsKeptRatherThanCulledOnItsUnrotatedBox() {
        // Wide and flat, rotated a quarter turn: its drawn shape is *tall*, so it reaches far above and below
        // the band its unrotated box sits well outside of.
        val rotated = obj("r", x = 100f, y = 400f, width = 400f, height = 20f, rotation = 90f)
        val page = page("p1", rotated)

        assertFalse(
            "the unrotated box is nowhere near the band",
            rotated.geometry.y <= 250f + PageVirtualizer.OBJECT_OVERSCAN,
        )
        val visible = PageVirtualizer.visibleObjects(page, topPt = 0f, bottomPt = 250f)
        assertTrue("but the drawn shape intersects it, so it must be composed", visible.any { it.id == "r" })
    }

    @Test
    fun anUnmeasuredPageRendersEverythingRatherThanNothing() {
        // A degenerate band is indistinguishable from an empty page, and rendering nothing looks like loss.
        val page = page("p1", obj("a"), obj("b", y = 400f))
        assertEquals(2, PageVirtualizer.visibleObjects(page, topPt = 0f, bottomPt = 0f).size)
    }

    // ── 14. Page metadata ───────────────────────────────────────────────────────────────────────────

    @Test
    fun pageNumbersHonourSectionRestartsAndPerPageFormat() {
        val front = PageGeometry.a4().copy(
            numbering = PageNumbering(showNumber = true, format = PageNumberFormat.RomanLower),
        )
        val body = PageGeometry.a4().copy(
            numbering = PageNumbering(
                startsSection = true,
                restartAt = 1,
                showNumber = true,
                format = PageNumberFormat.Decimal,
            ),
        )
        val pages = listOf(
            page("p1", geometry = front),
            page("p2", geometry = front),
            page("p3", geometry = body),
            page("p4", geometry = PageGeometry.a4().copy(numbering = PageNumbering(showNumber = true))),
        )

        assertEquals(listOf("i", "ii", "1", "2"), pages.pageNumberLabels())
    }

    @Test
    fun everyNumberFormatRendersDistinctlyRatherThanFallingBackToDecimal() {
        assertEquals("4", PageNumberFormat.Decimal.format(4))
        assertEquals("iv", PageNumberFormat.RomanLower.format(4))
        assertEquals("IV", PageNumberFormat.RomanUpper.format(4))
        assertEquals("d", PageNumberFormat.AlphaLower.format(4))
        assertEquals("D", PageNumberFormat.AlphaUpper.format(4))
        // Past Z the sequence continues rather than wrapping, or two pages would share a label.
        assertEquals("AA", PageNumberFormat.AlphaUpper.format(27))
    }

    // ── 15. Form factor ─────────────────────────────────────────────────────────────────────────────

    @Test
    fun aFormFactorChangeKeepsSelectionHistoryViewAxesAndAnOpenSurface() {
        val rt = editing(page("p1", obj("a", x = 10f, y = 10f), obj("b", x = 300f)))
        rt.selectObjects(listOf("a", "b"))
        rt.invoke(DocumentIds.NUDGE_RIGHT)
        rt.openSurface(DocumentSurfaces.PAGE_CONTROL)
        rt.setPageScope(PageScope.WholeDocument)
        rt.setZoom(2f)

        val selection = rt.scope.selectedObjectIds()
        val depth = rt.controller.undoDepth()
        val document = rt.controller.document()

        rt.onFormFactorChanged(FormFactor(900, 600))

        assertEquals("the selection survives a rotation", selection, rt.scope.selectedObjectIds())
        assertEquals("history survives", depth, rt.controller.undoDepth())
        assertEquals("the document is untouched", document, rt.controller.document())
        assertEquals(LayoutRepresentation.ORIGINAL, rt.view.representation)
        assertEquals(WorkingIntent.EDIT, rt.view.intent)
        assertEquals(2f, rt.view.zoom, 0.001f)
        assertEquals("the open surface is re-presented, not closed", DocumentSurfaces.PAGE_CONTROL, rt.activeSurfaceId())
        assertEquals("and its scope is preserved", PageScope.WholeDocument, rt.pageScope)
    }

    @Test
    fun aFormFactorChangeDoesNotAbandonAGestureInFlight() {
        val rt = editing(page("p1", obj("a", x = 10f, y = 10f)))
        rt.selectObjects(listOf("a"))
        rt.beginManipulation(ActiveManipulation.Kind.MOVE)
        rt.updateMove(50f, 50f)

        rt.onFormFactorChanged(FormFactor(900, 600))

        assertNotNull("a rotation is not a reason to drop the drag", rt.view.manipulation)
        assertTrue(rt.endManipulation())
        assertEquals(60f, rt.controller.findObject("a")!!.second.geometry.x, 0.001f)
    }

    // ── 16. Surfaces ────────────────────────────────────────────────────────────────────────────────

    @Test
    fun pageControlOpensThroughTheCoordinatorAndBackDismissesIt() {
        val rt = editing(page("p1"))

        rt.openSurface(DocumentSurfaces.PAGE_CONTROL)
        assertEquals(DocumentSurfaces.PAGE_CONTROL, rt.activeSurfaceId())
        assertEquals("re-opening must not stack a second copy", 1, rt.surfaces.depth)

        rt.openSurface(DocumentSurfaces.PAGE_CONTROL)
        assertEquals(1, rt.surfaces.depth)

        val result = rt.back()
        assertTrue(result is DocumentBackResult.ClosedSurface)
        assertNull(rt.activeSurfaceId())
    }

    @Test
    fun anOutsideTapDismissesPageControlByItsOwnPolicyRatherThanBlanketClosing() {
        val rt = editing(page("p1"))
        rt.openSurface(DocumentSurfaces.PAGE_CONTROL)
        rt.dismiss(DismissTrigger.OUTSIDE_TAP)
        assertNull(rt.activeSurfaceId())
    }
}
