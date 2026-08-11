package com.norfold.app.editor.shape

import com.norfold.app.domain.ShapeBlock
import com.norfold.app.domain.ShapeKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Session lifecycle: many local steps, exactly one host transaction.
 *
 * The property under test throughout is that Shape Builder does not own History. It keeps a local stack
 * while it is open, and the artifact's History Engine sees a single "Edit shape" transaction at the end —
 * or nothing at all, if the session changed nothing.
 */
class ShapeBuilderHistoryTest {
    /** Records every commit, so "exactly one host transaction" is asserted rather than assumed. */
    private class RecordingHost(private val accept: Boolean = true) : ShapeFocusCommitHost {
        val labels = mutableListOf<String>()
        val blocks = mutableListOf<ShapeBlock>()

        override fun commit(label: String, block: ShapeBlock): Boolean {
            labels += label
            blocks += block
            return accept
        }
    }

    @Test
    fun aLegacyBlockOpensAsAnEditableSingleLayerSession() {
        val runtime = runtime()
        assertEquals(LoadedShapeDocument.Origin.LEGACY_EMPTY, runtime.origin())
        assertFalse(runtime.isReadOnly())
        assertFalse("opening is not an edit", runtime.isDirty())
        assertNotNull("a layer is selected on open", runtime.selection().layerId)
        assertNull(runtime.preservedPayload())
        assertFalse(runtime.hasGesture())
        assertFalse(runtime.canUndo())
        assertFalse(runtime.canRedo())
    }

    @Test
    fun oneEditIsOneLocalStepAndReachesNoHost() {
        val host = RecordingHost()
        val runtime = runtime(host = host)
        val layer = runtime.selection().layerId!!

        assertTrue(runtime.setOpacity(layer, 0.5f))
        assertTrue(runtime.isDirty())
        assertEquals(1, runtime.stats().undoDepth)
        assertTrue("a local edit must not reach the host", host.labels.isEmpty())
        assertTrue(runtime.canUndo())
        assertFalse(runtime.canRedo())

        assertTrue(runtime.undo())
        assertFalse(runtime.isDirty())
        assertEquals(1, runtime.stats().redoDepth)
        assertEquals(0, runtime.stats().undoDepth)
        assertTrue(runtime.redo())
        assertTrue(runtime.isDirty())
        assertTrue(runtime.undo())
        assertFalse("an empty undo stack refuses", runtime.undo())
    }

    @Test
    fun anEditThatChangesNothingIsNotAStep() {
        // A no-op undo entry is one the user cannot explain: they press undo and nothing appears to happen.
        val runtime = runtime()
        val layer = runtime.selection().layerId!!
        assertFalse(runtime.setOpacity(layer, runtime.activeLayer()!!.opacity))
        assertEquals(0, runtime.stats().undoDepth)
    }

    @Test
    fun doneCommitsExactlyOnceAndClosesTheSession() {
        val host = RecordingHost()
        val runtime = runtime(host = host)
        val layer = runtime.selection().layerId!!
        runtime.setOpacity(layer, 0.4f)
        runtime.setStrokeWidth(layer, 6f)
        runtime.renameLayer(layer, "Badge")
        assertEquals(3, runtime.stats().undoDepth)

        assertTrue(runtime.done())
        assertEquals("three local steps, one host transaction", 1, host.labels.size)
        assertEquals(ShapeFocusRuntime.COMMIT_LABEL, host.labels.single())
        assertEquals(1L, runtime.stats().committedHostTransactions)
        assertFalse(runtime.isOpen())
        assertTrue(runtime.isReadOnly())
        assertFalse("a closed session refuses edits", runtime.setOpacity(layer, 0.9f))
        assertFalse("a closed session refuses a second commit", runtime.done())
        assertNotNull(host.blocks.single().shapeEditDocument)
    }

    @Test
    fun aSessionThatChangedNothingNeverCallsTheHost() {
        val clean = RecordingHost()
        val untouched = runtime(host = clean)
        assertTrue(untouched.done())
        assertTrue("an unchanged session writes no undo step", clean.labels.isEmpty())
        assertFalse(untouched.isOpen())

        // Edits that cancel out are also nothing: the resulting block is byte-identical to the original.
        val cancelled = RecordingHost()
        val undone = runtime(host = cancelled)
        undone.setOpacity(undone.selection().layerId!!, 0.25f)
        undone.undo()
        assertFalse(undone.isDirty())
        assertTrue(undone.done())
        assertTrue(cancelled.labels.isEmpty())
    }

    @Test
    fun aRefusedCommitLeavesTheSessionOpenWithItsEditsIntact() {
        val host = RecordingHost(accept = false)
        val runtime = runtime(host = host)
        runtime.setOpacity(runtime.selection().layerId!!, 0.3f)

        assertFalse(runtime.done())
        assertTrue("a refusal must not close the session", runtime.isOpen())
        assertFalse(runtime.isReadOnly())
        assertTrue("the work is not lost", runtime.isDirty())
        assertEquals(0L, runtime.stats().committedHostTransactions)
        assertTrue("history survives a refusal", runtime.canUndo())
    }

    @Test
    fun cancelReturnsTheExactOriginalAndNeverCallsTheHost() {
        val baseline = legacyBlock()
        val host = RecordingHost()
        val runtime = ShapeFocusRuntime(baseline, host)
        val layer = runtime.selection().layerId!!
        runtime.setOpacity(layer, 0.2f)
        runtime.setBlendMode(layer, ShapeBlendMode.MULTIPLY)

        assertEquals(baseline, runtime.cancel())
        assertTrue(host.labels.isEmpty())
        assertFalse(runtime.isDirty())
        assertEquals(0, runtime.stats().undoDepth)
        assertEquals(0, runtime.stats().redoDepth)
        assertFalse(runtime.isOpen())
    }

    @Test
    fun aGestureIsOneStepNoMatterHowManyFramesItSpans() {
        val runtime = runtime()
        val layer = runtime.selection().layerId!!

        assertTrue(runtime.beginGesture())
        assertFalse("a second begin is refused", runtime.beginGesture())
        assertFalse("a discrete edit must not race a drag", runtime.setOpacity(layer, 0.8f))
        assertFalse("undo is refused mid-gesture", runtime.canUndo())

        listOf(0.9f, 0.8f, 0.7f, 0.6f, 0.5f).forEach { opacity ->
            assertTrue(runtime.previewGesture { ShapeLayerEditor.setOpacity(it, layer, opacity) })
        }
        assertEquals(5L, runtime.stats().previewChanges)
        assertEquals("previews are not steps yet", 0, runtime.stats().undoDepth)

        assertTrue(runtime.endGesture())
        assertEquals("five frames collapse to one step", 1, runtime.stats().undoDepth)
        assertTrue(runtime.undo())
        assertFalse("one undo reverts the whole drag", runtime.isDirty())
    }

    @Test
    fun anEmptyOrCancelledGestureLeavesNoStepBehind() {
        val empty = runtime()
        empty.beginGesture()
        assertFalse("a gesture that changed nothing is a non-event", empty.endGesture())
        assertEquals(0, empty.stats().undoDepth)
        assertFalse("ending with no gesture open is refused", empty.endGesture())

        val runtime = runtime()
        val layer = runtime.selection().layerId!!
        val before = runtime.snapshot()
        runtime.beginGesture()
        runtime.previewGesture { ShapeLayerEditor.setOpacity(it, layer, 0.15f) }
        assertTrue(runtime.cancelGesture())
        assertEquals(before, runtime.snapshot())
        assertEquals(1L, runtime.stats().cancelledGestures)
        assertEquals(0, runtime.stats().undoDepth)
        assertFalse(runtime.cancelGesture())
        assertTrue(runtime.beginGesture())
        assertFalse("committing mid-drag is refused", runtime.done())
    }

    @Test
    fun holdToCompareShowsTheOriginalWithoutDiscardingTheEdits() {
        val runtime = runtime()
        val layer = runtime.selection().layerId!!
        val original = runtime.snapshot()
        runtime.setOpacity(layer, 0.35f)
        val edited = runtime.snapshot()

        assertTrue(runtime.setCompareOriginal(true))
        assertTrue(runtime.isComparingOriginal())
        assertEquals("compare draws the original", original, runtime.document())
        assertEquals("compare keeps the working document", edited, runtime.snapshot())
        assertFalse(runtime.canUndo())
        assertFalse(runtime.setOpacity(layer, 0.45f))
        assertFalse("a redundant compare is a non-event", runtime.setCompareOriginal(true))

        assertTrue(runtime.setCompareOriginal(false))
        assertEquals(edited, runtime.document())
        runtime.beginGesture()
        assertFalse("compare is refused mid-gesture", runtime.setCompareOriginal(true))
    }

    @Test
    fun aReadOnlySessionOwnsNothingAndChangesNothing() {
        val host = RecordingHost()
        val runtime = ShapeFocusRuntime(legacyBlock(), host, readOnly = true)

        assertTrue(runtime.isReadOnly())
        assertFalse(runtime.setOpacity(runtime.selection().layerId ?: "missing", 0.5f))
        assertFalse(runtime.beginGesture())
        assertFalse(runtime.done())
        assertNull("a read-only session claims no history chord", runtime.historyOwner("shape"))
        assertTrue(host.labels.isEmpty())
        assertEquals("cancelling out returns the original", legacyBlock(), runtime.cancel())
    }

    @Test
    fun theHistoryChordBelongsToTheSessionOnlyWhileItIsOpen() {
        val runtime = runtime()
        val owner = runtime.historyOwner("shape-focus")
        assertNotNull(owner)
        assertEquals("shape-focus", owner!!.focusId)
        assertTrue(owner.isActive())
        assertFalse("nothing local to undo yet", owner.canUndoLocally())

        runtime.setOpacity(runtime.selection().layerId!!, 0.55f)
        assertTrue(owner.canUndoLocally())
        assertTrue(owner.undoLocally())
        assertFalse(runtime.isDirty())
        assertTrue(owner.canRedoLocally())
        assertTrue(owner.redoLocally())

        runtime.done()
        assertFalse("ownership expires with the session, not with the host's memory of it", owner.isActive())
        assertFalse(owner.canUndoLocally())
    }

    @Test
    fun localHistoryIsBoundedAndDropsTheOldestStepFirst() {
        val runtime = runtime(limit = 3)
        val layer = runtime.selection().layerId!!
        listOf(0.9f, 0.8f, 0.7f, 0.6f, 0.5f).forEach { runtime.setOpacity(layer, it) }
        assertEquals(3, runtime.stats().undoDepth)

        var undos = 0
        while (runtime.undo()) undos++
        assertEquals(3, undos)
        assertEquals("the oldest step is dropped, not the newest", 0.8f, runtime.activeLayer()!!.opacity, 1e-4f)
    }

    @Test
    fun nodeEditingConvertsToAPathAsPartOfTheSameStep() {
        val runtime = runtime()
        assertNotNull("a primitive still has a path to edit", runtime.activePath())
        // A parametric layer regenerates its path on every read; unstable ids would make the ref this tap
        // produces stale before the selection is even validated.
        assertEquals(runtime.activePath(), runtime.activePath())

        val hit = runtime.hitTest(runtime.activePath()!!.subpaths.first().nodes.first().point)
        assertTrue("hit=$hit", hit is ShapeHit.Node)
        assertTrue(runtime.selectNode((hit as ShapeHit.Node).ref))
        assertEquals(1, runtime.selection().nodes.size)

        assertTrue(runtime.moveSelectedNodes(ShapePoint(2f, 2f)))
        assertEquals(1, runtime.stats().undoDepth)
        assertTrue(runtime.activeLayer()!!.content is ShapePathLayer)
        assertTrue(runtime.undo())
        assertTrue("one undo restores the primitive", runtime.activeLayer()!!.content is ShapePrimitiveLayer)
        assertEquals("the conversion was not a second step", 0, runtime.stats().undoDepth)
    }

    @Test
    fun convertToPathKeepsHoldOfTheNodeTheUserGrabbed() {
        val runtime = runtime()
        val layer = runtime.selection().layerId!!
        val grabbed = runtime.activePath()!!.subpaths.first().let { ShapeNodeRef(it.id, it.nodes.first().id) }
        runtime.selectNode(grabbed)
        assertEquals(grabbed, runtime.selection().single)

        assertTrue(runtime.convertToPath(layer))
        assertEquals("the selection survives the one-way door", grabbed, runtime.selection().single)
        assertNotNull(runtime.activePath()!!.subpathById(grabbed.subpathId)?.nodeById(grabbed.nodeId))
    }

    @Test
    fun deletingSelectedNodesClearsTheSelectionThatNamedThem() {
        val runtime = runtime()
        runtime.convertToPath(runtime.selection().layerId!!)
        val subpath = runtime.activePath()!!.subpaths.first()
        runtime.selectNode(ShapeNodeRef(subpath.id, subpath.nodes.first().id))

        assertTrue(runtime.deleteSelectedNodes())
        assertTrue("a selection must never name deleted geometry", runtime.selection().nodes.isEmpty())
        assertFalse(runtime.deleteSelectedNodes())
    }

    @Test
    fun aLockedLayerRefusesNodeEditingRatherThanSilentlyConverting() {
        val runtime = runtime()
        val layer = runtime.selection().layerId!!
        val subpath = runtime.activePath()!!.subpaths.first()
        runtime.selectNode(ShapeNodeRef(subpath.id, subpath.nodes.first().id))
        runtime.setLayerLocked(layer, true)
        val depth = runtime.stats().undoDepth

        assertFalse(runtime.moveSelectedNodes(ShapePoint(3f, 3f)))
        assertFalse(runtime.previewPath { it })
        assertEquals("a refusal is not a step", depth, runtime.stats().undoDepth)
    }

    @Test
    fun selectionIsValidatedAgainstTheDocumentAndIsNotHistory() {
        val runtime = runtime()
        val original = runtime.selection().layerId!!

        runtime.selectNode(ShapeNodeRef("no-such-subpath", "no-such-node"))
        assertTrue("a selection naming nothing is dropped", runtime.selection().nodes.isEmpty())

        runtime.addPrimitive(ShapePrimitiveKind.ELLIPSE)
        val added = runtime.selection().layerId
        assertNotNull(added)
        assertTrue("adding a layer selects it", added != original)
        assertTrue(runtime.removeLayer(added!!))
        assertTrue("removing the selected layer re-selects", runtime.selection().layerId != added)

        assertTrue(runtime.selectLayer(original))
        assertEquals("selecting is not an undoable edit", 2, runtime.stats().undoDepth)
    }

    @Test
    fun resetReturnsToTheLegacyShapeButKeepsAccessibilityBecauseItIsContent() {
        val runtime = runtime()
        assertTrue(runtime.setAccessibility(ShapeAccessibility(alternativeText = "Company badge")))
        runtime.addPrimitive(ShapePrimitiveKind.STAR)

        assertTrue(runtime.resetToLegacy())
        assertEquals(1, runtime.snapshot().layers.size)
        assertEquals("Company badge", runtime.snapshot().accessibility.alternativeText)
    }

    @Test
    fun aCommittedBlockReopensAsCanonicalWithTheSameGeometry() {
        val host = RecordingHost()
        val runtime = runtime(host = host)
        val layer = runtime.selection().layerId!!
        runtime.setOpacity(layer, 0.65f)
        runtime.renameLayer(layer, "Ring")
        runtime.addPrimitive(ShapePrimitiveKind.TRIANGLE)
        val saved = runtime.snapshot()
        runtime.done()

        val reopened = ShapeFocusRuntime(host.blocks.single(), RecordingHost())
        assertEquals(LoadedShapeDocument.Origin.CANONICAL, reopened.origin())
        assertEquals(saved, reopened.snapshot())
        assertFalse(reopened.isReadOnly())
        assertFalse("reopening is not an edit", reopened.isDirty())
    }

    private fun legacyBlock() = ShapeBlock(id = "b1", kind = ShapeKind.RoundedRectangle)

    private fun runtime(
        block: ShapeBlock = legacyBlock(),
        host: ShapeFocusCommitHost = RecordingHost(),
        limit: Int = ShapeFocusRuntime.DEFAULT_HISTORY_LIMIT,
    ) = ShapeFocusRuntime(block, host, readOnly = false, historyLimit = limit)
}
