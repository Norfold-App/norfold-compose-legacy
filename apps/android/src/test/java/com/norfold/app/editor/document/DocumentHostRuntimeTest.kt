package com.norfold.app.editor.document

import com.norfold.app.domain.BlockDocument
import com.norfold.app.domain.DocumentPageBlock
import com.norfold.app.domain.InlineText
import com.norfold.app.domain.PageGeometry
import com.norfold.app.domain.ParagraphBlock
import com.norfold.app.domain.SceneGeometry
import com.norfold.app.domain.SceneLock
import com.norfold.app.domain.SceneObject
import com.norfold.app.editor.command.CommandResult
import com.norfold.app.editor.command.CommandSurface
import com.norfold.app.editor.history.EditTarget
import com.norfold.app.editor.presentation.FormFactor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The DocumentHost runtime: the view axes, the manipulation state machine, page virtualization, and the
 * single command path every route shares.
 *
 * The properties worth pinning here are the ones no screenshot would reveal: that a drag commits **once**,
 * that cancelling restores the pre-gesture state exactly because the model was never touched, that switching
 * representation does not mutate stored geometry, and that a large document composes a handful of pages
 * rather than all of them.
 */
class DocumentHostRuntimeTest {

    private fun obj(
        id: String,
        x: Float = 0f,
        y: Float = 0f,
        width: Float = 100f,
        height: Float = 50f,
        lock: SceneLock = SceneLock.None,
    ) = SceneObject(
        id = id,
        geometry = SceneGeometry(x, y, width, height),
        lock = lock,
        content = ParagraphBlock(id = "c-$id", content = listOf(InlineText(id))),
    )

    private fun page(id: String, vararg objects: SceneObject, geometry: PageGeometry = PageGeometry.a4()) =
        DocumentPageBlock(id = id, geometry = geometry, objects = objects.toList()).normalized()

    private fun runtime(
        vararg pages: DocumentPageBlock,
        instrumentation: DocumentHostInstrumentation = DocumentHostInstrumentation.None,
    ): DocumentHostRuntime = DocumentHostRuntime(
        controller = DocumentSceneController(EditTarget("note:1"), BlockDocument(pages.toList())),
        initialFormFactor = FormFactor(400, 800),
        instrumentation = instrumentation,
    )

    // ── Manipulation: preview, one commit, exact cancel ─────────────────────────────────────────────

    @Test
    fun aDragPreviewsWithoutTouchingTheModelAndCommitsOnce() {
        val rt = runtime(page("p1", obj("a", x = 10f, y = 10f)))
        rt.scope.activePageId = "p1"
        rt.scope.selectObjects(listOf("a"))

        assertNotNull(rt.beginManipulation(ActiveManipulation.Kind.MOVE))
        rt.updateMove(40f, 40f)
        rt.updateMove(80f, 80f)
        rt.updateMove(120f, 120f)

        assertEquals(
            "the model must be untouched while a gesture previews",
            10f,
            rt.controller.findObject("a")!!.second.geometry.x,
            0.0001f,
        )
        assertEquals("but the preview reflects the gesture", 130f, rt.view.renderGeometry(rt.controller.findObject("a")!!.second).x, 0.0001f)

        assertTrue(rt.endManipulation())
        assertEquals(130f, rt.controller.findObject("a")!!.second.geometry.x, 0.0001f)
        assertEquals("a whole drag is one undo step, not one per frame", 1, rt.controller.undoDepth())
    }

    @Test
    fun cancellingADragRestoresTheExactPreGestureState() {
        val rt = runtime(page("p1", obj("a", x = 33.5f, y = 77.25f)))
        rt.scope.activePageId = "p1"
        rt.scope.selectObjects(listOf("a"))
        val before = rt.controller.findObject("a")!!.second.geometry

        rt.beginManipulation(ActiveManipulation.Kind.MOVE)
        rt.updateMove(200f, 200f)
        rt.cancelManipulation()

        assertEquals("cancel is exact because the model never moved", before, rt.controller.findObject("a")!!.second.geometry)
        assertFalse("a cancelled gesture records no history", rt.controller.canUndo())
    }

    @Test
    fun aDragThatEndsWhereItStartedCommitsNothing() {
        val rt = runtime(page("p1", obj("a", x = 10f)))
        rt.scope.activePageId = "p1"
        rt.scope.selectObjects(listOf("a"))
        rt.beginManipulation(ActiveManipulation.Kind.MOVE)
        rt.updateMove(50f, 0f)
        rt.updateMove(0f, 0f)
        assertFalse(rt.endManipulation())
        assertFalse(rt.controller.canUndo())
    }

    @Test
    fun aMultiObjectDragPreservesRelativeOffsets() {
        val rt = runtime(page("p1", obj("a", x = 0f, y = 0f), obj("b", x = 60f, y = 30f)))
        rt.scope.activePageId = "p1"
        rt.scope.selectObjects(listOf("a", "b"))

        rt.beginManipulation(ActiveManipulation.Kind.MOVE)
        val active = rt.updateMove(17f, 23f)!!

        val a = active.previewFor("a")!!
        val b = active.previewFor("b")!!
        assertEquals("the arrangement must not deform", 60f, b.x - a.x, 0.0001f)
        assertEquals(30f, b.y - a.y, 0.0001f)
    }

    @Test
    fun aLockedObjectCannotStartAManipulationAtAll() {
        val rt = runtime(page("p1", obj("a", lock = SceneLock.Full)))
        rt.scope.activePageId = "p1"
        rt.scope.selectObjects(listOf("a"))
        assertNull("no preview may begin for something that cannot move", rt.beginManipulation(ActiveManipulation.Kind.MOVE))
    }

    @Test
    fun aLockedObjectRemainsSelectable() {
        // §4.13: locking restricts what commands do, never whether the object can be reached.
        val rt = runtime(page("p1", obj("a", lock = SceneLock.Full)))
        assertEquals("a", rt.selectAt("p1", 10f, 10f))
        assertEquals(1, rt.selection.count)
    }

    @Test
    fun aDragSnapsToTheLeftMarginAndReportsTheGuide() {
        // A4 default left margin is 72pt; starting at 68 and nudging 1pt puts the edge within threshold.
        val rt = runtime(page("p1", obj("a", x = 68f, y = 200f)))
        rt.scope.activePageId = "p1"
        rt.scope.selectObjects(listOf("a"))
        rt.beginManipulation(ActiveManipulation.Kind.MOVE)

        val active = rt.updateMove(1f, 0f)!!

        assertEquals("snapped to the margin", 72f, active.previewFor("a")!!.x, 0.0001f)
        assertTrue(
            "the guide that caused the snap is reported so it can be drawn",
            active.guides.any { it.kind == SnapGuideEngine.Kind.MARGIN },
        )
    }

    // ── View axes ───────────────────────────────────────────────────────────────────────────────────

    @Test
    fun theFourViewCombinationsAreIndependentlyReachable() {
        val rt = runtime(page("p1"))
        val seen = mutableSetOf<Pair<LayoutRepresentation, WorkingIntent>>()
        for (representation in LayoutRepresentation.entries) {
            for (intent in WorkingIntent.entries) {
                rt.setRepresentation(representation)
                rt.setIntent(intent)
                seen += rt.view.representation to rt.view.intent
            }
        }
        assertEquals("all four states exist independently", 4, seen.size)
    }

    @Test
    fun aLayoutSensitiveActionSwitchesToOriginalAndExplainsWhy() {
        val rt = runtime(page("p1", obj("a")))
        rt.setRepresentation(LayoutRepresentation.ADAPTIVE)

        val transition = rt.prepareFor(LayoutSensitiveAction.MOVE_OBJECT)

        assertTrue(transition.switched)
        assertEquals(LayoutRepresentation.ORIGINAL, rt.view.representation)
        assertNotNull("the switch is explained rather than silent", rt.consumeNotice())
    }

    @Test
    fun alreadyInOriginalViewNoActionSwitchesAndNoNoticeIsRaised() {
        val rt = runtime(page("p1"))
        rt.setRepresentation(LayoutRepresentation.ORIGINAL)
        val transition = rt.prepareFor(LayoutSensitiveAction.MOVE_OBJECT)
        assertFalse(transition.switched)
        assertNull(rt.consumeNotice())
    }

    @Test
    fun switchingRepresentationPreservesSelectionCaretToolAndHistory() {
        val rt = runtime(page("p1", obj("a"), obj("b")))
        rt.scope.activePageId = "p1"
        rt.scope.selectObjects(listOf("a", "b"))
        rt.selection.setCaret(com.norfold.app.editor.selection.TextCaret("c-a", 3))
        rt.moveObjects()
        val depthBefore = rt.controller.undoDepth()
        val selectionBefore = rt.selection.selection().map { it.id }
        val primaryBefore = rt.selection.primary()

        rt.setRepresentation(LayoutRepresentation.ORIGINAL)
        rt.setRepresentation(LayoutRepresentation.ADAPTIVE)

        assertEquals(selectionBefore, rt.selection.selection().map { it.id })
        assertSame("primary survives a representation change", primaryBefore, rt.selection.primary())
        assertEquals(com.norfold.app.editor.selection.TextCaret("c-a", 3), rt.selection.caret())
        assertEquals("history is untouched by a rendering change", depthBefore, rt.controller.undoDepth())
    }

    @Test
    fun adaptiveViewNeverMutatesOriginalGeometry() {
        val rt = runtime(page("p1", obj("a", x = 40f, y = 500f, width = 300f, height = 200f)))
        val original = rt.controller.findObject("a")!!.second.geometry

        rt.setRepresentation(LayoutRepresentation.ADAPTIVE)
        // Adaptive rendering is a scale applied at draw time; it must not be written back.
        PageVirtualizer.adaptiveScale(rt.pages(), viewportWidth = 320f)
        rt.setIntent(WorkingIntent.EDIT)
        rt.setRepresentation(LayoutRepresentation.ORIGINAL)

        assertEquals("exact geometry survives a round trip through Adaptive", original, rt.controller.findObject("a")!!.second.geometry)
    }

    @Test
    fun beginningAManipulationInAdaptiveReadMovesBothAxes() {
        val rt = runtime(page("p1", obj("a")))
        rt.setRepresentation(LayoutRepresentation.ADAPTIVE)
        rt.setIntent(WorkingIntent.READ)
        rt.scope.activePageId = "p1"
        rt.scope.selectObjects(listOf("a"))

        rt.beginManipulation(ActiveManipulation.Kind.MOVE)

        assertEquals(LayoutRepresentation.ORIGINAL, rt.view.representation)
        assertEquals("editing implies Edit intent", WorkingIntent.EDIT, rt.view.intent)
    }

    private fun DocumentHostRuntime.moveObjects() {
        controller.moveObjects(scope.selectedObjectIds(), 5f, 5f)
    }

    // ── Virtualization ──────────────────────────────────────────────────────────────────────────────

    @Test
    fun aLargeDocumentComposesOnlyTheVisiblePages() {
        val pages = (1..200).map { page("p$it") }
        val rt = runtime(*pages.toTypedArray())

        val window = rt.pageWindow(scrollOffset = 0f, viewportHeight = 900f)

        assertTrue("a 200-page document must not compose 200 pages: got ${window.count}", window.count <= 5)
        assertEquals(200, rt.pages().size)
    }

    @Test
    fun scrollingDeepIntoTheDocumentComposesThatRegionNotTheStart() {
        val pages = (1..100).map { page("p$it") }
        val rt = runtime(*pages.toTypedArray())
        // Page 50 starts at 49 * (842 + 24).
        val offset = 49 * (842f + 24f)

        val window = rt.pageWindow(scrollOffset = offset, viewportHeight = 900f)

        assertTrue("page 50 is composed", 49 in window)
        assertFalse("page 1 is not", 0 in window)
    }

    @Test
    fun pageTopsFollowRealGeometryWhenPageHeightsDiffer() {
        // A fixed-height assumption misplaces every page after the first differing one.
        val tall = PageGeometry.a4()
        val short = PageGeometry.a4().copy(structureLocked = false, height = 400f)
        val rt = runtime(page("p1", geometry = tall), page("p2", geometry = short), page("p3", geometry = tall))

        val window = rt.pageWindow(0f, 5000f)

        assertEquals(0f, window.topOf(0), 0.001f)
        assertEquals(842f + 24f, window.topOf(1), 0.001f)
        assertEquals("the third page follows the *short* second one", 842f + 24f + 400f + 24f, window.topOf(2), 0.001f)
    }

    @Test
    fun theWindowIsNeverEmptyWhenAStaleScrollOffsetPointsPastTheEnd() {
        // Deleting pages can leave the scroll position beyond the new extent; a blank viewport is worse
        // than showing the last page.
        val rt = runtime(page("p1"), page("p2"))
        val window = rt.pageWindow(scrollOffset = 99_999f, viewportHeight = 800f)
        assertTrue(window.count >= 1)
    }

    @Test
    fun adaptiveScaleShrinksAWidePageButNeverMagnifiesASmallOne() {
        val wide = runtime(page("p1", geometry = PageGeometry.a4()))
        assertTrue("an A4 page shrinks onto a phone", PageVirtualizer.adaptiveScale(wide.pages(), 360f) < 1f)

        val small = runtime(page("p1", geometry = PageGeometry.a4().copy(structureLocked = false, width = 200f)))
        assertEquals("a small page is not blown up to fill the screen", 1f, PageVirtualizer.adaptiveScale(small.pages(), 1000f), 0.0001f)
    }

    @Test
    fun instrumentationReportsTheComposedRangeSoLazinessIsMeasurable() {
        val recorder = RecordingDocumentHostInstrumentation()
        val rt = runtime(*(1..50).map { page("p$it") }.toTypedArray(), instrumentation = recorder)
        rt.pageWindow(0f, 900f)
        assertTrue(recorder.events().any { it.startsWith("pages:") && it.endsWith("/50") })
    }

    // ── One command path for every route ────────────────────────────────────────────────────────────

    @Test
    fun theSameCommandIdReachedFromEverySurfaceProducesTheSameEffect() {
        val surfaces = listOf(
            CommandSurface.DOCK,
            CommandSurface.KEYBOARD,
            CommandSurface.ACCESSIBILITY,
            CommandSurface.POINTER_CONTEXT_MENU,
        )
        for (surface in surfaces) {
            val rt = runtime(page("p1", obj("a", x = 100f)))
            rt.scope.activePageId = "p1"
            rt.scope.selectObjects(listOf("a"))

            val result = rt.invoke("document.object.nudgeRight", surface)

            assertTrue("$surface must succeed like every other route", result is CommandResult.Success)
            assertEquals(
                "$surface must move the object identically",
                100f + DocumentCommands.NUDGE,
                rt.controller.findObject("a")!!.second.geometry.x,
                0.0001f,
            )
            assertEquals("$surface produces one undo step", 1, rt.controller.undoDepth())
        }
    }

    @Test
    fun accessibilityActionsAreRegisteredCommandsNotAParallelImplementation() {
        val rt = runtime(page("p1", obj("a")))
        val actions = rt.accessibilityActions(rt.controller.findObject("a")!!.second)

        assertTrue(actions.isNotEmpty())
        actions.forEach { (id, _) ->
            assertNotNull("accessibility action '$id' must be a registered command", rt.commands.get(id))
        }
    }

    @Test
    fun aLockedObjectOffersNoMoveActionToAccessibilityEither() {
        val rt = runtime(page("p1", obj("a", lock = SceneLock.Full)))
        val actions = rt.accessibilityActions(rt.controller.findObject("a")!!.second).map { it.first }
        assertFalse(actions.any { it.contains("move", ignoreCase = true) || it.contains("nudge", ignoreCase = true) })
        assertTrue("but unlocking is offered", actions.contains("document.object.unlock"))
    }

    // ── Selection integrity ─────────────────────────────────────────────────────────────────────────

    @Test
    fun repeatedTapsCycleDownThroughOverlappingObjects() {
        val rt = runtime(
            page(
                "p1",
                obj("back", x = 0f, y = 0f, width = 200f, height = 200f),
                obj("front", x = 0f, y = 0f, width = 200f, height = 200f),
            ),
        )
        assertEquals("front", rt.selectAt("p1", 50f, 50f))
        assertEquals("a second tap reaches the object underneath", "back", rt.selectAt("p1", 50f, 50f))
        assertEquals("and cycles round", "front", rt.selectAt("p1", 50f, 50f))
    }

    @Test
    fun tappingEmptyPageAreaSelectsThePage() {
        val rt = runtime(page("p1", obj("a", x = 0f, y = 0f, width = 50f, height = 50f)))
        assertNull(rt.selectAt("p1", 400f, 700f))
        assertEquals("p1", rt.selection.primary()?.id)
        assertEquals(
            com.norfold.app.editor.selection.SelectionGranularity.DOC_PAGE,
            rt.selection.primary()?.granularity,
        )
    }

    @Test
    fun undoThatRemovesTheSelectedObjectLeavesNoStaleSelection() {
        val rt = runtime(page("p1", obj("a")))
        rt.scope.activePageId = "p1"
        rt.scope.selectObjects(listOf("a"))
        rt.controller.duplicateObject("a")
        val copyId = rt.controller.page("p1")!!.objects.last().id
        rt.scope.selectObjects(listOf(copyId))

        rt.undo()

        assertNull("the object is gone", rt.controller.findObject(copyId))
        assertTrue(
            "so no selection may still name it",
            rt.selection.selection().none { it.id == copyId },
        )
    }

    @Test
    fun deletingAPageMovesTheActivePageToOneThatExists() {
        val rt = runtime(page("p1"), page("p2"))
        rt.scope.activePageId = "p2"
        rt.scope.selectPage("p2")

        rt.invoke("document.page.delete")

        val active = rt.scope.activePageId
        assertNotNull(active)
        assertTrue("the active page must exist", rt.pages().any { it.id == active })
    }

    // ── Page Control scopes ─────────────────────────────────────────────────────────────────────────

    private fun unlocked(vararg ids: String) = ids.map {
        page(it, geometry = PageGeometry.a4().copy(structureLocked = false))
    }.toTypedArray()

    @Test
    fun everyScopeIsReachableAndNamesADistinctSetOfPages() {
        val rt = runtime(*unlocked("p1", "p2", "p3", "p4"))
        // p3 starts a section, so p1..p2 is one section and p3..p4 another.
        rt.controller.editPageGeometry(listOf("p3"), "section", force = true) {
            it.copy(numbering = it.numbering.copy(startsSection = true))
        }
        rt.scope.activePageId = "p3"
        rt.setSelectedPages(listOf("p1", "p4"))

        assertEquals(listOf("p3"), PageScope.CurrentPage.resolve(rt.scope))
        assertEquals(listOf("p1", "p4"), PageScope.SelectedPages.resolve(rt.scope))
        assertEquals(listOf("p3", "p4"), PageScope.ThisSection.resolve(rt.scope))
        assertEquals(listOf("p3", "p4"), PageScope.ThisPageOnward.resolve(rt.scope))
        assertEquals(listOf("p1", "p2", "p3", "p4"), PageScope.WholeDocument.resolve(rt.scope))
        assertEquals(
            "setting a default names no existing page",
            emptyList<String>(),
            PageScope.NewPagesByDefault.resolve(rt.scope),
        )
        assertEquals("all six scopes are offered", 6, PageScope.ALL.size)
    }

    @Test
    fun applyingAtWholeDocumentScopeChangesEveryPageInOneTransaction() {
        val rt = runtime(*unlocked("p1", "p2", "p3"))
        rt.scope.activePageId = "p1"
        rt.setPageScope(PageScope.WholeDocument)

        val result = rt.invokePageControl("document.page.landscape", PageScope.WholeDocument)

        assertTrue(result is CommandResult.Success)
        assertTrue("every page turned", rt.pages().all { it.geometry.width > it.geometry.height })
        assertEquals("a scoped apply is one undo step, not one per page", 1, rt.controller.undoDepth())

        rt.undo()
        assertTrue("and it inverts as one", rt.pages().all { it.geometry.width < it.geometry.height })
    }

    @Test
    fun applyingAtCurrentPageScopeLeavesTheOtherPagesAlone() {
        val rt = runtime(*unlocked("p1", "p2"))
        rt.scope.activePageId = "p2"

        rt.invokePageControl("document.page.landscape", PageScope.CurrentPage)

        assertTrue(rt.controller.page("p2")!!.geometry.width > rt.controller.page("p2")!!.geometry.height)
        assertTrue(
            "the scope is a promise about which pages change",
            rt.controller.page("p1")!!.geometry.width < rt.controller.page("p1")!!.geometry.height,
        )
    }

    @Test
    fun aScopedControlReportsMixedWhenTheScopedPagesDisagree() {
        val rt = runtime(*unlocked("p1", "p2"))
        rt.scope.activePageId = "p1"
        rt.invokePageControl("document.page.toggleGrid", PageScope.CurrentPage)

        rt.setPageScope(PageScope.WholeDocument)
        val grid = rt.uiState().pageControls.first { it.id == "document.page.toggleGrid" }

        assertTrue("one page has the grid on and one off — that is Mixed, not on or off", grid.mixed)
    }

    @Test
    fun aScopedControlReportsActiveWhenEveryScopedPageAlreadyHasIt() {
        val rt = runtime(*unlocked("p1", "p2"))
        rt.scope.activePageId = "p1"
        rt.setPageScope(PageScope.WholeDocument)
        rt.invokePageControl("document.page.landscape", PageScope.WholeDocument)

        val landscape = rt.uiState().pageControls.first { it.id == "document.page.landscape" }
        val portrait = rt.uiState().pageControls.first { it.id == "document.page.portrait" }

        assertTrue(landscape.active)
        assertFalse(portrait.active)
    }

    @Test
    fun aLockedPageRefusesPageSetupAndSaysWhy() {
        // structureLocked defaults to true: "page structure locked; content editable" is the default state.
        val rt = runtime(page("p1"))
        rt.scope.activePageId = "p1"

        val landscape = rt.uiState().pageControls.first { it.id == "document.page.landscape" }
        assertFalse(landscape.enabled)
        assertNotNull("a greyed control must explain itself", landscape.disabledReason)

        val result = rt.invokePageControl("document.page.landscape", PageScope.CurrentPage)
        assertTrue(result is CommandResult.Failure)
        assertEquals("the page is untouched", 595f, rt.controller.page("p1")!!.geometry.width, 0.001f)
    }

    @Test
    fun unlockingIsAlwaysAvailableEvenWhileTheStructureIsLocked() {
        // The lock control governs the others, so a locked page must never be able to trap the user.
        val rt = runtime(page("p1"))
        rt.scope.activePageId = "p1"

        val unlock = rt.uiState().pageControls.first { it.id == "document.page.unlockStructure" }
        assertTrue(unlock.enabled)

        rt.invokePageControl("document.page.unlockStructure", PageScope.CurrentPage)
        assertFalse(rt.controller.page("p1")!!.geometry.structureLocked)
        assertTrue(rt.uiState().pageControls.first { it.id == "document.page.landscape" }.enabled)
    }

    @Test
    fun theNewPagesDefaultChangesFuturePagesWithoutTouchingExistingOnes() {
        val rt = runtime(*unlocked("p1"))
        rt.scope.activePageId = "p1"
        val before = rt.controller.page("p1")!!.geometry
        val depthBefore = rt.controller.undoDepth()

        val result = rt.invokePageControl("document.page.landscape", PageScope.NewPagesByDefault)

        assertTrue(result is CommandResult.Success)
        assertEquals("no existing page changed", before, rt.controller.page("p1")!!.geometry)
        assertEquals(
            "setting a default is not an edit, so it takes no undo step",
            depthBefore,
            rt.controller.undoDepth(),
        )
        assertNotNull("but the user is told, because nothing visibly happened", rt.consumeNotice())

        rt.invoke("document.page.insertAfter")
        val added = rt.pages().last()
        assertTrue("the next page inherits the default", added.geometry.width > added.geometry.height)
    }

    @Test
    fun withNoExplicitDefaultANewPageInheritsThePageItFollows() {
        val letter = PageGeometry.letter().copy(structureLocked = false)
        val rt = runtime(page("p1", geometry = letter))
        rt.scope.activePageId = "p1"

        rt.invoke("document.page.insertAfter")

        assertEquals(
            "a Letter document must not silently produce an A4 page",
            letter.width,
            rt.pages().last().geometry.width,
            0.001f,
        )
    }

    @Test
    fun theSameControlIdMeansTheSameThingScopedOrFromTheRegistry() {
        // The scoped route constructs its command rather than looking it up; it must still be the *same*
        // definition, or Page Control and the slash menu would drift into two page-setup implementations.
        val scoped = runtime(*unlocked("p1"))
        scoped.scope.activePageId = "p1"
        scoped.invokePageControl("document.page.landscape", PageScope.CurrentPage)

        val registered = runtime(*unlocked("p1"))
        registered.scope.activePageId = "p1"
        registered.invoke("document.page.landscape", CommandSurface.SLASH)

        assertEquals(
            scoped.controller.page("p1")!!.geometry,
            registered.controller.page("p1")!!.geometry,
        )
    }

    @Test
    fun everyRegisteredPageSetupControlIsOfferedByTheScopedSurface() {
        val rt = runtime(*unlocked("p1"))
        rt.scope.activePageId = "p1"
        val offered = rt.uiState().pageControls.map { it.id }.toSet()

        DocumentCommands.PAGE_SETUP_CONTROLS.forEach { control ->
            assertTrue("${control.id} must appear in Page Control", control.id in offered)
            assertNotNull("${control.id} must be a registered command too", rt.commands.get(control.id))
        }
    }
}
