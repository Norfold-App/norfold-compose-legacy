package com.norfold.app.editor.document

import com.norfold.app.domain.BlockDocument
import com.norfold.app.domain.DocumentPageBlock
import com.norfold.app.domain.InlineText
import com.norfold.app.domain.PageColumns
import com.norfold.app.domain.PageGeometry
import com.norfold.app.domain.PageLimits
import com.norfold.app.domain.PageMargins
import com.norfold.app.domain.PageNumbering
import com.norfold.app.domain.PageOrientation
import com.norfold.app.domain.ParagraphBlock
import com.norfold.app.domain.SceneGeometry
import com.norfold.app.domain.SceneLock
import com.norfold.app.domain.SceneObject
import com.norfold.app.domain.SceneObjectKind
import com.norfold.app.domain.pages
import com.norfold.app.domain.sectionIndices
import com.norfold.app.editor.history.EditTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The scene/page controller against the canonical History Engine.
 *
 * The property under test throughout is that **undo restores exact geometry**, not merely a document that
 * looks similar. A scene editor that rounds a position on the way back is one the user cannot trust with a
 * layout they spent time on, and the failure is invisible until they compare against a print.
 */
class DocumentSceneControllerTest {

    private val target = EditTarget("note:1")

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
        content = ParagraphBlock(id = "content-$id", content = listOf(InlineText(text))),
    )

    private fun page(id: String, vararg objects: SceneObject, geometry: PageGeometry = PageGeometry.a4()) =
        DocumentPageBlock(id = id, geometry = geometry, objects = objects.toList()).normalized()

    private fun controller(vararg pages: DocumentPageBlock) =
        DocumentSceneController(target, BlockDocument(pages.toList()))

    private fun DocumentSceneController.geometryOf(objectId: String): SceneGeometry? =
        findObject(objectId)?.second?.geometry

    // ── Move ────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun moveCommitsExactGeometryAndUndoesExactly() {
        val ctl = controller(page("p1", obj("a", x = 10f, y = 20f)))
        val before = ctl.geometryOf("a")!!

        ctl.commitGeometry("a", SceneGeometry(133.5f, 87.25f, 100f, 50f))

        assertEquals(133.5f, ctl.geometryOf("a")!!.x, 0.0001f)
        assertEquals(87.25f, ctl.geometryOf("a")!!.y, 0.0001f)

        assertTrue(ctl.undo())
        assertEquals("undo must restore the exact pre-move geometry", before, ctl.geometryOf("a"))

        assertTrue(ctl.redo())
        assertEquals(133.5f, ctl.geometryOf("a")!!.x, 0.0001f)
        assertEquals(87.25f, ctl.geometryOf("a")!!.y, 0.0001f)
    }

    @Test
    fun resizeCommitsExactGeometryAndUndoesExactly() {
        val ctl = controller(page("p1", obj("a", width = 100f, height = 50f)))
        val before = ctl.geometryOf("a")!!

        ctl.commitGeometry("a", SceneGeometry(0f, 0f, 321.75f, 199.5f), label = "Resize")

        assertEquals(321.75f, ctl.geometryOf("a")!!.width, 0.0001f)
        assertEquals(199.5f, ctl.geometryOf("a")!!.height, 0.0001f)
        assertTrue(ctl.undo())
        assertEquals(before, ctl.geometryOf("a"))
    }

    @Test
    fun rotationRoundTripsThroughHistory() {
        val ctl = controller(page("p1", obj("a")))
        ctl.commitGeometry("a", SceneGeometry(0f, 0f, 100f, 50f, rotation = 37.5f), label = "Rotate")
        assertEquals(37.5f, ctl.geometryOf("a")!!.rotation, 0.0001f)
        assertTrue(ctl.undo())
        assertEquals(0f, ctl.geometryOf("a")!!.rotation, 0.0001f)
    }

    @Test
    fun rotationIsRefusedForAnObjectKindThatDoesNotSupportIt() {
        // A rotated table has no meaningful cell layout, so the capability is absent rather than merely
        // discouraged — and the geometry commit must honour that, not just the toolbar.
        val ctl = controller(page("p1", obj("t", kind = SceneObjectKind.Table)))
        ctl.commitGeometry("t", SceneGeometry(0f, 0f, 100f, 50f, rotation = 45f), label = "Rotate")
        assertEquals(0f, ctl.geometryOf("t")!!.rotation, 0.0001f)
    }

    // ── Locking ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun aPositionLockedObjectCannotBeMovedButCanStillBeResized() {
        val ctl = controller(page("p1", obj("a", x = 10f, y = 10f, lock = SceneLock.Position)))

        ctl.commitGeometry("a", SceneGeometry(500f, 500f, 250f, 120f))

        val geometry = ctl.geometryOf("a")!!
        assertEquals("position is locked", 10f, geometry.x, 0.0001f)
        assertEquals(10f, geometry.y, 0.0001f)
        assertEquals("size is not locked by a position lock", 250f, geometry.width, 0.0001f)
        assertEquals(120f, geometry.height, 0.0001f)
    }

    @Test
    fun aFullyLockedObjectIsUnchangedByMoveAndResize() {
        val ctl = controller(page("p1", obj("a", x = 10f, y = 10f, lock = SceneLock.Full)))
        val before = ctl.geometryOf("a")!!
        ctl.commitGeometry("a", SceneGeometry(400f, 400f, 300f, 300f, 90f))
        assertEquals(before, ctl.geometryOf("a"))
        assertFalse("a refused manipulation must not record an undo step", ctl.canUndo())
    }

    @Test
    fun nudgeSkipsLockedObjectsAndMovesTheRest() {
        val ctl = controller(page("p1", obj("free", x = 0f), obj("stuck", x = 100f, lock = SceneLock.Position)))
        ctl.moveObjects(listOf("free", "stuck"), dx = 5f, dy = 0f)
        assertEquals(5f, ctl.geometryOf("free")!!.x, 0.0001f)
        assertEquals(100f, ctl.geometryOf("stuck")!!.x, 0.0001f)
    }

    // ── Atomicity ───────────────────────────────────────────────────────────────────────────────────

    @Test
    fun aMultiObjectMoveIsOneUndoStep() {
        val ctl = controller(page("p1", obj("a", x = 0f), obj("b", x = 50f), obj("c", x = 100f)))

        ctl.moveObjects(listOf("a", "b", "c"), dx = 10f, dy = 10f)
        assertEquals(1, ctl.undoDepth())

        assertTrue(ctl.undo())
        assertEquals(0f, ctl.geometryOf("a")!!.x, 0.0001f)
        assertEquals(50f, ctl.geometryOf("b")!!.x, 0.0001f)
        assertEquals("all three revert together or none do", 100f, ctl.geometryOf("c")!!.x, 0.0001f)
    }

    @Test
    fun aMoveSpanningTwoPagesIsStillOneAtomicTransaction() {
        val ctl = controller(page("p1", obj("a", x = 0f)), page("p2", obj("b", x = 0f)))

        ctl.moveObjects(listOf("a", "b"), dx = 25f, dy = 0f)

        assertEquals("two pages changed, but one user action", 1, ctl.undoDepth())
        assertEquals(25f, ctl.geometryOf("a")!!.x, 0.0001f)
        assertEquals(25f, ctl.geometryOf("b")!!.x, 0.0001f)

        ctl.undo()
        assertEquals(0f, ctl.geometryOf("a")!!.x, 0.0001f)
        assertEquals(0f, ctl.geometryOf("b")!!.x, 0.0001f)
    }

    @Test
    fun movingSeveralObjectsOnOnePageReplacesThatPageExactlyOnce() {
        // Two ReplaceBlock ops against one page would carry a stale `before` in the second, which the
        // History Engine rejects — the resulting failure would be an unapplied, silently-lost edit.
        val ctl = controller(page("p1", obj("a", x = 0f), obj("b", x = 10f), obj("c", x = 20f)))
        ctl.moveObjects(listOf("a", "b", "c"), dx = 3f, dy = 0f)
        assertEquals(1, ctl.history().single().operations.size)
    }

    // ── Layering ────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun layerOrderIsStableContiguousAndReversible() {
        val ctl = controller(page("p1", obj("a"), obj("b"), obj("c")))
        assertEquals(listOf(0, 1, 2), ctl.page("p1")!!.objects.map { it.z })

        ctl.reorderObject("a", LayerMove.TO_FRONT)
        assertEquals(listOf("b", "c", "a"), ctl.page("p1")!!.objects.map { it.id })
        assertEquals("z stays contiguous from 0", listOf(0, 1, 2), ctl.page("p1")!!.objects.map { it.z })

        ctl.undo()
        assertEquals(listOf("a", "b", "c"), ctl.page("p1")!!.objects.map { it.id })
        assertEquals(listOf(0, 1, 2), ctl.page("p1")!!.objects.map { it.z })
    }

    @Test
    fun bringForwardAtTheFrontIsANoOpRatherThanAnEmptyUndoStep() {
        val ctl = controller(page("p1", obj("a"), obj("b")))
        assertFalse(ctl.reorderObject("b", LayerMove.FORWARD))
        assertFalse("a no-op must not pollute history", ctl.canUndo())
    }

    @Test
    fun overlappingObjectsHitTestFrontMostFirst() {
        val ctl = controller(
            page(
                "p1",
                obj("back", x = 0f, y = 0f, width = 200f, height = 200f),
                obj("front", x = 50f, y = 50f, width = 50f, height = 50f),
            ),
        )
        val stack = ctl.page("p1")!!.hitTest(60f, 60f)
        assertEquals(listOf("front", "back"), stack.map { it.id })
    }

    // ── Object lifecycle ────────────────────────────────────────────────────────────────────────────

    @Test
    fun duplicateProducesAStableNewIdOffsetFromItsSource() {
        val ctl = controller(page("p1", obj("a", x = 10f, y = 10f, text = "hello")))
        val copyId = ctl.duplicateObject("a")

        assertNotNull(copyId)
        assertNotEquals("a", copyId)
        val copy = ctl.findObject(copyId!!)!!.second
        assertEquals(10f + DocumentSceneController.DUPLICATE_OFFSET, copy.geometry.x, 0.0001f)
        assertEquals("the copy carries the content", "hello", copy.content.plainText())
        assertNotEquals(
            "content must get a fresh id, or two blocks would share one identity",
            ctl.findObject("a")!!.second.content.id,
            copy.content.id,
        )
    }

    @Test
    fun deletingAnObjectDoesNotDeleteItsPage() {
        val ctl = controller(page("p1", obj("a"), obj("b")))
        ctl.deleteObjects(listOf("a"))
        assertEquals("the page survives", 1, ctl.pages().size)
        assertEquals(listOf("b"), ctl.page("p1")!!.objects.map { it.id })
        ctl.undo()
        assertEquals(listOf("a", "b"), ctl.page("p1")!!.objects.map { it.id })
    }

    @Test
    fun movingAnObjectBetweenPagesIsOneTransactionAndNeverDuplicatesIt() {
        val ctl = controller(page("p1", obj("a")), page("p2"))

        assertTrue(ctl.moveObjectToPage("a", "p2"))
        assertEquals(0, ctl.page("p1")!!.objects.size)
        assertEquals(listOf("a"), ctl.page("p2")!!.objects.map { it.id })
        assertEquals("both pages change as one step", 1, ctl.undoDepth())

        ctl.undo()
        assertEquals(listOf("a"), ctl.page("p1")!!.objects.map { it.id })
        assertEquals(0, ctl.page("p2")!!.objects.size)
    }

    @Test
    fun actingOnAnObjectThatNoLongerExistsFailsSafely() {
        val ctl = controller(page("p1", obj("a")))
        assertFalse(ctl.commitGeometry("ghost", SceneGeometry(1f, 1f, 10f, 10f)))
        assertFalse(ctl.deleteObjects(listOf("ghost")))
        assertNull(ctl.duplicateObject("ghost"))
        assertFalse(ctl.reorderObject("ghost", LayerMove.TO_FRONT))
        assertFalse("no failed lookup may record history", ctl.canUndo())
    }

    // ── Pages ───────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun pageInsertDeleteDuplicateAndReorderAreCanonicalTransactions() {
        val ctl = controller(page("p1", obj("a")), page("p2", obj("b")))

        ctl.insertPage(DocumentPageBlock(id = "p3"), relativeToPageId = "p1", after = true)
        assertEquals(listOf("p1", "p3", "p2"), ctl.pages().map { it.id })

        ctl.movePage("p3", 2)
        assertEquals(listOf("p1", "p2", "p3"), ctl.pages().map { it.id })

        assertTrue(ctl.deletePage("p3"))
        assertEquals(listOf("p1", "p2"), ctl.pages().map { it.id })

        // Every one of those steps inverts individually.
        ctl.undo()
        assertEquals(listOf("p1", "p2", "p3"), ctl.pages().map { it.id })
        ctl.undo()
        assertEquals(listOf("p1", "p3", "p2"), ctl.pages().map { it.id })
        ctl.undo()
        assertEquals(listOf("p1", "p2"), ctl.pages().map { it.id })
    }

    @Test
    fun deletingAPageTakesItsObjectsAndUndoBringsThemBack() {
        val ctl = controller(page("p1", obj("a")), page("p2", obj("b", x = 42f, text = "kept")))

        ctl.deletePage("p2")
        assertNull(ctl.findObject("b"))

        ctl.undo()
        val restored = ctl.findObject("b")!!.second
        assertEquals(42f, restored.geometry.x, 0.0001f)
        assertEquals("kept", restored.content.plainText())
    }

    @Test
    fun theLastPageCannotBeDeleted() {
        val ctl = controller(page("p1", obj("a")))
        assertFalse(ctl.deletePage("p1"))
        assertEquals(1, ctl.pages().size)
        assertFalse(ctl.canUndo())
    }

    @Test
    fun duplicatingAPageGivesEveryObjectAFreshIdentity() {
        val ctl = controller(page("p1", obj("a", text = "one"), obj("b", text = "two")))
        val copyId = ctl.duplicatePage("p1")!!

        val original = ctl.page("p1")!!
        val copy = ctl.page(copyId)!!
        assertEquals(2, copy.objects.size)
        assertEquals(listOf("one", "two"), copy.objects.map { it.content.plainText() })
        assertTrue(
            "no object id may be shared between a page and its duplicate",
            copy.objects.map { it.id }.none { it in original.objects.map { o -> o.id } },
        )
    }

    @Test
    fun pageReorderKeepsObjectsWithTheirOwnPage() {
        val ctl = controller(page("p1", obj("a")), page("p2", obj("b")), page("p3", obj("c")))
        ctl.movePage("p1", 2)
        assertEquals(listOf("p2", "p3", "p1"), ctl.pages().map { it.id })
        assertEquals("ownership is structural, so it cannot be broken by reordering", "p1", ctl.findObject("a")!!.first.id)
        assertEquals("p3", ctl.findObject("c")!!.first.id)
    }

    // ── Page geometry and structure lock ────────────────────────────────────────────────────────────

    @Test
    fun pageGeometryIsProtectedByDefaultAndEditableOnceUnlocked() {
        val ctl = controller(page("p1", obj("a")))
        assertTrue("pages ship locked", ctl.page("p1")!!.geometry.structureLocked)

        assertFalse(
            "a locked page refuses geometry changes",
            ctl.editPageGeometry(listOf("p1"), "Landscape") { it.withOrientation(PageOrientation.Landscape) },
        )
        assertEquals(PageOrientation.Portrait, ctl.page("p1")!!.geometry.orientation)

        ctl.setPageStructureLocked(listOf("p1"), locked = false)
        assertTrue(ctl.editPageGeometry(listOf("p1"), "Landscape") { it.withOrientation(PageOrientation.Landscape) })
        assertEquals(PageOrientation.Landscape, ctl.page("p1")!!.geometry.orientation)
    }

    @Test
    fun editingObjectsIsUnaffectedByTheStructureLock() {
        // The rule is "page structure locked; content editable" — a locked page must not freeze its content.
        val ctl = controller(page("p1", obj("a", x = 0f)))
        assertTrue(ctl.page("p1")!!.geometry.structureLocked)
        assertTrue(ctl.moveObjects(listOf("a"), dx = 20f, dy = 0f))
        assertEquals(20f, ctl.geometryOf("a")!!.x, 0.0001f)
    }

    @Test
    fun mixedPageSizesAndOrientationsCoexistAndRoundTrip() {
        val a4 = PageGeometry.a4().copy(structureLocked = false)
        val landscapeLetter = PageGeometry.letter().withOrientation(PageOrientation.Landscape)
        val ctl = controller(
            page("p1", geometry = a4),
            page("p2", geometry = landscapeLetter),
        )

        assertEquals(PageOrientation.Portrait, ctl.page("p1")!!.geometry.orientation)
        assertEquals(PageOrientation.Landscape, ctl.page("p2")!!.geometry.orientation)
        assertEquals(792f, ctl.page("p2")!!.geometry.width, 0.001f)
        assertEquals(612f, ctl.page("p2")!!.geometry.height, 0.001f)

        val json = com.norfold.app.domain.BlockDocumentJson.encodeDocumentPayload(ctl.document())
        val decoded = com.norfold.app.domain.BlockDocumentJson.decodeDocumentPayload(json)
        assertEquals(ctl.document().pages().map { it.geometry }, decoded.pages().map { it.geometry })
    }

    @Test
    fun editingGeometryAcrossAScopeIsOneTransaction() {
        val unlocked = PageGeometry.a4().copy(structureLocked = false)
        val ctl = controller(page("p1", geometry = unlocked), page("p2", geometry = unlocked))

        ctl.editPageGeometry(listOf("p1", "p2"), "Two columns") { it.copy(columns = PageColumns(2)) }

        assertEquals(1, ctl.undoDepth())
        assertEquals(2, ctl.page("p1")!!.geometry.columns.count)
        assertEquals(2, ctl.page("p2")!!.geometry.columns.count)
        ctl.undo()
        assertEquals(1, ctl.page("p1")!!.geometry.columns.count)
        assertEquals(1, ctl.page("p2")!!.geometry.columns.count)
    }

    // ── Page limits ─────────────────────────────────────────────────────────────────────────────────

    @Test
    fun customPageSizeHonoursBothTheSideAndTheAreaLimit() {
        // 1500 x 1500 mm satisfies the 2000 mm side limit but is 2.25 m² — over the 2 m² area limit.
        val geometry = PageGeometry.a4().withSizeMm(1500f, 1500f)
        val areaMm2 = (geometry.width / com.norfold.app.domain.POINTS_PER_MM) *
            (geometry.height / com.norfold.app.domain.POINTS_PER_MM)

        assertTrue("side limit holds", geometry.width <= PageLimits.MAX_SIDE_PT + 0.01f)
        assertTrue("area limit holds too: was ${areaMm2}mm²", areaMm2 <= PageLimits.MAX_AREA_MM2 + 1f)
        assertEquals("proportional scaling keeps the requested shape", 1f, geometry.width / geometry.height, 0.001f)
    }

    @Test
    fun anOverlongSideIsClampedToTwoThousandMillimetres() {
        val geometry = PageGeometry.a4().withSizeMm(5000f, 10f)
        assertEquals(PageLimits.MAX_SIDE_MM, geometry.width / com.norfold.app.domain.POINTS_PER_MM, 0.5f)
    }

    @Test
    fun marginsCanNeverConsumeTheWholeSheet() {
        val geometry = PageGeometry.a4().copy(margins = PageMargins(9999f, 9999f, 9999f, 9999f)).normalized()
        assertTrue("a content box must remain", geometry.contentWidth > 0f && geometry.contentHeight > 0f)
    }

    // ── Sections ────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun sectionsRunFromOneSectionStartToTheNext() {
        val pages = listOf(
            page("p1"),
            page("p2"),
            page("p3", geometry = PageGeometry.a4().copy(numbering = PageNumbering(startsSection = true))),
            page("p4"),
        )
        assertEquals(listOf(0, 0, 1, 1), pages.sectionIndices())
    }

    // ── Persistence hand-off ────────────────────────────────────────────────────────────────────────

    @Test
    fun onlyTheChangedPageIsReportedDirty() {
        val ctl = controller(page("p1", obj("a")), page("p2", obj("b")))
        ctl.markSaved()
        assertTrue(ctl.dirtyBlockIds().isEmpty())

        ctl.moveObjects(listOf("a"), dx = 5f, dy = 0f)

        assertEquals("one page moved, so one Room row is dirty", setOf("p1"), ctl.dirtyBlockIds())
        assertTrue(ctl.deletedBlockIds().isEmpty())
    }

    @Test
    fun aDeletedPageIsReportedForRowDeletion() {
        val ctl = controller(page("p1"), page("p2"))
        ctl.markSaved()
        ctl.deletePage("p2")
        assertEquals(setOf("p2"), ctl.deletedBlockIds())
    }

    @Test
    fun aStaleSaveCompletionCannotAdvanceANewerBaseline() {
        val ctl = controller(page("p1", obj("a")))
        val generation = ctl.baselineGeneration()
        val snapshot = ctl.document()

        // A remote/document change moves the baseline while a write is still in flight.
        ctl.markSaved()

        assertFalse(
            "a completion measured against a replaced baseline must be declined",
            ctl.markSavedIfCurrent(snapshot, generation),
        )
    }
}
