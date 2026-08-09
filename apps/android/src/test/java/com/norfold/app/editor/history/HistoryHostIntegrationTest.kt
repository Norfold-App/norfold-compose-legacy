package com.norfold.app.editor.history

import com.norfold.app.domain.BlockDocument
import com.norfold.app.domain.ChartBlock
import com.norfold.app.domain.DocumentPageBlock
import com.norfold.app.domain.ImageBlock
import com.norfold.app.domain.InlineText
import com.norfold.app.domain.PageGeometry
import com.norfold.app.domain.ParagraphBlock
import com.norfold.app.domain.SceneGeometry
import com.norfold.app.domain.SceneObject
import com.norfold.app.domain.TableBlock
import com.norfold.app.domain.TableCell
import com.norfold.app.domain.findById
import com.norfold.app.editor.calculation.GridAddress
import com.norfold.app.editor.calculation.SpreadsheetHostRuntime
import com.norfold.app.editor.command.CommandResult
import com.norfold.app.editor.memory.InteractionMemory
import com.norfold.app.editor.presentation.AdaptivePresentationRuntime
import com.norfold.app.editor.surface.SurfaceCoordinator
import com.norfold.app.editor.chart.ChartBlockAdapter
import com.norfold.app.editor.chart.ChartDocument
import com.norfold.app.editor.chart.ChartDocumentCodec
import com.norfold.app.editor.chart.ChartStudioRuntime
import com.norfold.app.editor.document.DocumentHostRuntime
import com.norfold.app.editor.document.DocumentSceneController
import com.norfold.app.editor.flow.FlowEditController
import com.norfold.app.editor.flow.FlowHostRuntime
import com.norfold.app.editor.guide.GuideCatalog
import com.norfold.app.editor.guide.GuideCommandRouter
import com.norfold.app.editor.guide.GuideContext
import com.norfold.app.editor.guide.GuideDomain
import com.norfold.app.editor.guide.GuideHost
import com.norfold.app.editor.guide.GuideProgressStore
import com.norfold.app.editor.guide.GuideRouteAvailability
import com.norfold.app.editor.guide.GuideRuntime
import com.norfold.app.editor.guide.GuideStep
import com.norfold.app.editor.guide.GuideTopic
import com.norfold.app.editor.guide.GuideTopicId
import com.norfold.app.editor.image.ImageAdjustments
import com.norfold.app.editor.image.ImageFocusRuntime
import com.norfold.app.editor.interchange.InterchangeImportService
import com.norfold.app.editor.interchange.RawSourceEditor
import com.norfold.app.editor.presentation.FormFactor
import com.norfold.app.editor.recovery.RecoveryBrowserRuntime
import com.norfold.app.editor.recovery.RecoveryReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase-10 host and domain integration (§37 items 64–71).
 *
 * The Phase-10 invariant is that there is exactly **one** authoritative edit history per artifact. That is
 * cheap to state and easy to lose: each completed domain — Flow, Document, Spreadsheet, Chart Studio, Image
 * Focus, relationship review, interchange — has its own runtime, and any one of them could have grown a
 * private stack that undoes its own edits without the artifact knowing. These tests assert the opposite from
 * the outside: an edit made through a domain's own API shows up in the artifact projection, and one Undo
 * routed through the artifact reverses it.
 *
 * Chart Studio and Image Focus are the two that hold live in-tool state. Chart Studio has no step history at
 * all by design; Image Focus has one, and its whole reconciliation contract — local steps first, then a hard
 * stop rather than a fall-through into edits the user cannot see — is pinned here rather than in the focus
 * runtime's own tests, because it is only observable from the host.
 */
class HistoryHostIntegrationTest {

    // ── Fixtures ────────────────────────────────────────────────────────────────────────────────────

    private data class FlowFixture(
        val controller: FlowEditController,
        val host: FlowHostRuntime,
        val history: HistoryRuntime,
        val recovery: RecoveryBrowserRuntime,
    )

    private fun flowHost(
        document: BlockDocument = BlockDocument(),
        relationshipsEnabled: Boolean = false,
    ): FlowFixture {
        val controller = FlowEditController(EditTarget("note:flow"), document)
        val host = FlowHostRuntime(
            controller = controller,
            initialFormFactor = FormFactor(400, 800),
            relationshipsEnabled = relationshipsEnabled,
            historyUiEnabled = true,
        )
        return FlowFixture(
            controller = controller,
            host = host,
            history = checkNotNull(host.history) { "HISTORY_UI was enabled; History runtime must exist" },
            recovery = checkNotNull(host.recoveryBrowser) { "HISTORY_UI was enabled; browser must exist" },
        )
    }

    private data class DocumentFixture(
        val controller: DocumentSceneController,
        val host: DocumentHostRuntime,
        val history: HistoryRuntime,
        val recovery: RecoveryBrowserRuntime,
    )

    private fun documentHost(document: BlockDocument): DocumentFixture {
        val controller = DocumentSceneController(EditTarget("note:doc"), document)
        val host = DocumentHostRuntime(
            controller = controller,
            initialFormFactor = FormFactor(400, 800),
            historyUiEnabled = true,
        )
        return DocumentFixture(
            controller = controller,
            host = host,
            history = checkNotNull(host.history) { "HISTORY_UI was enabled; History runtime must exist" },
            recovery = checkNotNull(host.recoveryBrowser) { "HISTORY_UI was enabled; browser must exist" },
        )
    }

    private data class SpreadsheetFixture(
        val controller: FlowEditController,
        val host: SpreadsheetHostRuntime,
        val history: HistoryRuntime,
    )

    private fun spreadsheetHost(document: BlockDocument): SpreadsheetFixture {
        val controller = FlowEditController(EditTarget("note:sheet"), document)
        val host = SpreadsheetHostRuntime(
            controller = controller,
            initialFormFactor = FormFactor(900, 700),
            historyUiEnabled = true,
        )
        return SpreadsheetFixture(
            controller = controller,
            host = host,
            history = checkNotNull(host.history) { "HISTORY_UI was enabled; History runtime must exist" },
        )
    }

    private fun para(text: String, id: String) = ParagraphBlock(id = id, content = listOf(InlineText(text)))

    private fun obj(id: String, x: Float = 0f, y: Float = 0f) = SceneObject(
        id = id,
        geometry = SceneGeometry(x, y, 100f, 50f),
        content = para(id, "c-$id"),
    )

    private fun page(id: String, vararg objects: SceneObject) =
        DocumentPageBlock(id = id, geometry = PageGeometry.a4(), objects = objects.toList()).normalized()

    private fun cell(text: String) = TableCell(listOf(InlineText(text)))

    // ── 64. Flow host ───────────────────────────────────────────────────────────────────────────────

    @Test
    fun flowEditsLandInTheOneArtifactHistoryAndUndoReversesThem() {
        val f = flowHost(BlockDocument(listOf(para("initial", "p0"))))
        assertTrue(f.history.projection().isEmpty)

        assertTrue(f.controller.mutateDocument("Edit A") { BlockDocument(listOf(para("A", "p0"))) })
        assertTrue(f.controller.mutateDocument("Edit B") { BlockDocument(listOf(para("B", "p0"))) })

        val projection = f.history.projection()
        assertEquals(2, projection.appliedCount)
        assertEquals(0, projection.undoneCount)
        assertEquals("the projection reads oldest-first", listOf("Edit A", "Edit B"), projection.entries.map { it.label })
        assertEquals("Edit B", projection.nextUndo()?.label)

        val outcome = f.history.undo()
        assertEquals(HistoryRoute.GLOBAL, outcome.route)
        assertTrue(outcome.succeeded)
        assertEquals("A", f.controller.document().blocks.first().plainText())
        assertEquals(1, f.history.projection().appliedCount)
        assertEquals(1, f.history.projection().undoneCount)

        assertTrue(f.history.redo().succeeded)
        assertEquals("B", f.controller.document().blocks.first().plainText())
    }

    @Test
    fun flowHostUndoChordRoutesThroughHistoryRatherThanTheControllerDirectly() {
        val f = flowHost(BlockDocument(listOf(para("initial", "p0"))))
        f.controller.mutateDocument("Edit") { BlockDocument(listOf(para("changed", "p0"))) }

        assertEquals(HistoryRoute.GLOBAL, f.host.undoRouted())
        assertEquals("initial", f.controller.document().blocks.first().plainText())
        // Nothing left to reverse: the host reports it rather than silently doing nothing.
        assertEquals(HistoryRoute.UNAVAILABLE, f.host.undoRouted())
        assertEquals(HistoryRoute.GLOBAL, f.host.redoRouted())
        assertEquals("changed", f.controller.document().blocks.first().plainText())
    }

    @Test
    fun flowHostAvailabilityDescribesTheStepRatherThanJustEnablingIt() {
        val f = flowHost(BlockDocument(listOf(para("initial", "p0"))))
        val empty = f.history.availability()
        assertEquals(HistoryMode.GLOBAL, empty.mode)
        assertFalse(empty.canUndo)
        assertNotNull("an unavailable Undo must say why", empty.undoReason)

        f.controller.mutateDocument("Type") { BlockDocument(listOf(para("typed", "p0"))) }
        val ready = f.history.availability()
        assertTrue(ready.canUndo)
        assertEquals("Undo Type", ready.undoDescription)
        assertNull(ready.undoReason)
    }

    // ── 65. Document host ───────────────────────────────────────────────────────────────────────────

    @Test
    fun documentSceneEditsLandInTheOneArtifactHistoryAndUndoReversesThem() {
        val f = documentHost(BlockDocument(listOf(page("p1", obj("a", 10f, 10f)))))
        assertTrue(f.history.projection().isEmpty)

        assertTrue(f.controller.moveObjects(listOf("a"), 40f, 0f))
        assertTrue(f.controller.moveObjects(listOf("a"), 50f, 0f))
        assertEquals(2, f.history.projection().appliedCount)

        assertTrue(f.history.undo().succeeded)
        assertEquals(50f, xOf(f.controller, "a"), 0.01f)

        assertTrue(f.history.undo().succeeded)
        assertEquals(10f, xOf(f.controller, "a"), 0.01f)
        assertEquals(0, f.history.projection().appliedCount)
        assertEquals(2, f.history.projection().undoneCount)
    }

    @Test
    fun documentHostUndoChordRoutesThroughHistory() {
        val f = documentHost(BlockDocument(listOf(page("p1", obj("a", 10f, 10f)))))
        f.controller.moveObjects(listOf("a"), 25f, 0f)

        assertEquals(HistoryRoute.GLOBAL, f.host.undoRouted())
        assertEquals(10f, xOf(f.controller, "a"), 0.01f)
        assertEquals(HistoryRoute.UNAVAILABLE, f.host.undoRouted())
    }

    private fun xOf(controller: DocumentSceneController, objectId: String): Float =
        checkNotNull(controller.findObject(objectId)) { "object $objectId is gone" }.second.geometry.x

    // ── 66. Spreadsheet host ────────────────────────────────────────────────────────────────────────

    @Test
    fun spreadsheetCellEditsLandInTheOneArtifactHistoryAndUndoReversesThem() {
        val table = TableBlock(
            id = "t1",
            headers = listOf(cell("A"), cell("B")),
            rows = listOf(listOf(cell("1"), cell("")), listOf(cell("2"), cell(""))),
        )
        val f = spreadsheetHost(BlockDocument(listOf(table)))
        val baseline = f.history.projection().appliedCount

        f.host.select(GridAddress(0, 1))
        assertTrue(f.host.commitFormulaBar("X").accepted)
        f.host.select(GridAddress(1, 1))
        assertTrue(f.host.commitFormulaBar("Y").accepted)

        val afterEdits = f.history.projection().appliedCount
        assertTrue("both cell commits must reach the artifact history", afterEdits >= baseline + 2)

        assertTrue(f.host.undo())
        assertTrue(
            "the artifact history moved, not a private spreadsheet stack",
            f.history.projection().undoneCount >= 1,
        )
    }

    @Test
    fun spreadsheetHostShareOneHistoryWithItsOwningFlowController() {
        val table = TableBlock(id = "t1", headers = listOf(cell("A")), rows = listOf(listOf(cell("1"))))
        val f = spreadsheetHost(BlockDocument(listOf(table)))

        // An edit made outside the grid is visible to the grid's own History runtime: one stack, two views.
        assertTrue(f.controller.mutateDocument("Rename") { it.copy(blocks = it.blocks + para("note", "p9")) })
        assertTrue(f.history.projection().entries.any { it.label == "Rename" })
    }

    // ── 67. Chart Studio ────────────────────────────────────────────────────────────────────────────

    @Test
    fun chartStudioCommitsLandInTheHostHistoryAsOneTransaction() {
        val encoded = ChartDocumentCodec.encode(ChartDocument(id = "c1", title = "Sales"))
        val f = flowHost(BlockDocument(listOf(ChartBlock(id = "chart1", chartDocument = encoded))))

        assertTrue(f.host.updateChart("chart1", ChartDocument(id = "c1", title = "Revenue")))

        val projection = f.history.projection()
        assertEquals("one chart edit is one transaction", 1, projection.appliedCount)
        val entry = checkNotNull(projection.nextUndo())
        assertEquals("Edit chart", entry.label)
        assertTrue(HistoryDomain.CHART in entry.domains)

        assertTrue(f.history.undo().succeeded)
        val restored = f.controller.document().findById("chart1") as ChartBlock
        assertEquals("Sales", ChartBlockAdapter.load(restored).document.title)
    }

    /**
     * Chart Studio must not own a step history.
     *
     * Its preview state is transient and its only durable route is [com.norfold.app.editor.chart.ChartCommitHost].
     * A `historyOwner` here would be a second authoritative stack for chart edits, and the Undo chord would
     * start reversing chart steps the artifact history never recorded.
     */
    @Test
    fun chartStudioRegistersNoFocusHistoryOwner() {
        val runtime = ChartStudioRuntime(initial = ChartDocument(id = "c1"), host = { _, _ -> true })
        val methods = runtime::class.java.methods.map { it.name }
        assertFalse("Chart Studio must not expose a local history owner", "historyOwner" in methods)
        assertFalse("Chart Studio must not expose local undo", "undo" in methods)
        assertFalse("Chart Studio must not expose local redo", "redo" in methods)
    }

    @Test
    fun chartStudioEditsAreVisibleToAContextualChartFilter() {
        val encoded = ChartDocumentCodec.encode(ChartDocument(id = "c1", title = "Sales"))
        val f = flowHost(BlockDocument(listOf(ChartBlock(id = "chart1", chartDocument = encoded), para("text", "p1"))))

        f.controller.mutateDocument("Type") { doc ->
            doc.copy(blocks = doc.blocks.map { if (it.id == "p1") para("typed", "p1") else it })
        }
        f.host.updateChart("chart1", ChartDocument(id = "c1", title = "Revenue"))

        f.history.setScope(HistoryScope.OneBlock("chart1"))
        val visible = f.history.visibleProjection()
        assertEquals(1, visible.entries.size)
        assertEquals("Edit chart", visible.entries.single().label)
        // Filtering is a view: the authoritative history still holds both transactions.
        assertEquals(2, f.history.projection().appliedCount)
    }

    // ── 68. Image Focus local history reconciliation ────────────────────────────────────────────────

    @Test
    fun imageFocusLocalStepsRunFirstAndThenBlockRatherThanFallingThrough() {
        val img = ImageBlock(id = "img1", source = "photo.jpg")
        val f = flowHost(BlockDocument(listOf(img, para("text", "p1"))))
        // An artifact edit made *before* the focus session opened; the user cannot see it from inside.
        f.controller.mutateDocument("Type") { doc ->
            doc.copy(blocks = doc.blocks.map { if (it.id == "p1") para("typed", "p1") else it })
        }
        assertEquals(1, f.history.projection().appliedCount)

        val focus = ImageFocusRuntime(baseline = img, host = { _, block -> f.host.updateImage("img1", block) })
        f.host.setFocusHistoryOwner(focus.historyOwner("image.focus.img1"))

        assertTrue(focus.edit { it.copy(adjustments = ImageAdjustments(exposure = 0.5f)) })
        assertTrue(focus.edit { it.copy(adjustments = ImageAdjustments(exposure = 0.5f, contrast = 0.3f)) })

        assertEquals(HistoryRoute.LOCAL_FOCUS, f.host.undoRouted())
        assertEquals(HistoryRoute.LOCAL_FOCUS, f.host.undoRouted())
        // Out of local steps. Reaching into the artifact history now would reverse "Type", which is not on
        // screen and was never part of this session (§13.5).
        assertEquals(HistoryRoute.BLOCKED_BY_FOCUS, f.host.undoRouted())
        assertEquals(1, f.history.projection().appliedCount)
    }

    @Test
    fun imageFocusAvailabilityNamesTheToolWhileTheSessionIsOpen() {
        val img = ImageBlock(id = "img1", source = "photo.jpg")
        val f = flowHost(BlockDocument(listOf(img)))
        val focus = ImageFocusRuntime(baseline = img, host = { _, block -> f.host.updateImage("img1", block) })
        f.host.setFocusHistoryOwner(focus.historyOwner("image.focus.img1"))

        val empty = f.history.availability()
        assertFalse(empty.canUndo)
        assertEquals(HistoryRuntime.FOCUS_EXHAUSTED_REASON, empty.undoReason)

        focus.edit { it.copy(adjustments = ImageAdjustments(exposure = 0.4f)) }
        val ready = f.history.availability()
        assertTrue(ready.canUndo)
        assertEquals("Undo step in this tool", ready.undoDescription)
    }

    @Test
    fun imageFocusDoneCommitsOneArtifactTransactionAndReleasesTheChord() {
        val img = ImageBlock(id = "img1", source = "photo.jpg")
        val f = flowHost(BlockDocument(listOf(img)))
        val focus = ImageFocusRuntime(baseline = img, host = { _, block -> f.host.updateImage("img1", block) })
        f.host.setFocusHistoryOwner(focus.historyOwner("image.focus.img1"))

        focus.edit { it.copy(adjustments = ImageAdjustments(exposure = 0.5f)) }
        focus.edit { it.copy(adjustments = ImageAdjustments(exposure = 0.9f)) }
        assertTrue(focus.done())

        val projection = f.history.projection()
        assertEquals("a whole focus session is one artifact transaction", 1, projection.appliedCount)
        val entry = checkNotNull(projection.nextUndo())
        assertEquals("Edit image", entry.label)
        assertTrue(HistoryDomain.IMAGE in entry.domains)

        // The session is closed, so the stale owner is dropped and Undo belongs to the artifact again.
        assertNull(f.host.focusHistoryOwner())
        assertEquals(HistoryRoute.GLOBAL, f.host.undoRouted())
        val restored = f.controller.document().findById("img1") as ImageBlock
        assertNull("undoing the session restores the pre-focus image", restored.imageEditDocument)
    }

    @Test
    fun imageFocusCancelLeavesTheArtifactHistoryUntouched() {
        val img = ImageBlock(id = "img1", source = "photo.jpg")
        val f = flowHost(BlockDocument(listOf(img)))
        val focus = ImageFocusRuntime(baseline = img, host = { _, block -> f.host.updateImage("img1", block) })
        f.host.setFocusHistoryOwner(focus.historyOwner("image.focus.img1"))

        focus.edit { it.copy(adjustments = ImageAdjustments(saturation = 0.8f)) }
        assertEquals(img, focus.cancel())
        assertTrue("a cancelled session must record nothing", f.history.projection().isEmpty)
    }

    @Test
    fun aReadOnlyImageSessionNeverClaimsTheUndoChord() {
        val img = ImageBlock(id = "img1", source = "locked.jpg")
        val focus = ImageFocusRuntime(baseline = img, host = { _, _ -> true }, readOnly = true)
        // It can never accumulate a local step, so claiming Undo would create a dead zone where nothing moves.
        assertNull(focus.historyOwner("image.focus.img1"))
    }

    @Test
    fun droppingTheFocusOwnerReturnsTheChordToTheArtifact() {
        val img = ImageBlock(id = "img1", source = "photo.jpg")
        val f = flowHost(BlockDocument(listOf(img, para("text", "p1"))))
        f.controller.mutateDocument("Type") { doc ->
            doc.copy(blocks = doc.blocks.map { if (it.id == "p1") para("typed", "p1") else it })
        }
        val focus = ImageFocusRuntime(baseline = img, host = { _, block -> f.host.updateImage("img1", block) })
        val owner = focus.historyOwner("image.focus.img1")
        f.host.setFocusHistoryOwner(owner)
        assertSame(owner, f.host.focusHistoryOwner())

        // What ImageFocusSheet's DisposableEffect does when the sheet leaves composition.
        f.host.setFocusHistoryOwner(null)
        assertNull(f.host.focusHistoryOwner())
        assertEquals(HistoryRoute.GLOBAL, f.host.undoRouted())
    }

    // ── 69. Relationship review ─────────────────────────────────────────────────────────────────────

    @Test
    fun restoringAReviewSnapshotTakesARecoveryPointFirst() {
        val f = flowHost(BlockDocument(listOf(para("original", "p1"))), relationshipsEnabled = true)
        val review = f.host.relationshipReview
        assertTrue(f.recovery.rows().isEmpty())

        val snapshot = checkNotNull(review.createReviewSnapshot("Before the experiment"))
        // A review snapshot is relationship state, not a recovery point: it must not silently add rows.
        assertTrue("creating a review snapshot is not a recovery point", f.recovery.rows().isEmpty())

        assertTrue(f.controller.mutateDocument("Edit") { doc ->
            doc.copy(blocks = doc.blocks.map { if (it.id == "p1") para("changed", "p1") else it })
        })
        assertTrue(review.restoreReviewSnapshot(snapshot.id))

        // Restoring one *is* destructive, so the recovery net catches it before the document is rewritten.
        val rows = f.recovery.rows()
        assertEquals(1, rows.size)
        assertEquals(RecoveryReason.DESTRUCTIVE_EDIT, rows.single().reason)
        assertTrue(rows.single().title.startsWith("Before restoring review snapshot"))
        assertTrue(rows.single().isRestorable)
    }

    @Test
    fun aReviewRestoreIsOneUndoableArtifactTransaction() {
        val f = flowHost(BlockDocument(listOf(para("original", "p1"))), relationshipsEnabled = true)
        val review = f.host.relationshipReview
        val snapshot = checkNotNull(review.createReviewSnapshot("Checkpoint"))
        val afterCreate = f.history.projection().appliedCount

        assertTrue(f.controller.mutateDocument("Edit") { doc ->
            doc.copy(blocks = doc.blocks.map { if (it.id == "p1") para("changed", "p1") else it })
        })
        assertTrue(review.restoreReviewSnapshot(snapshot.id))

        val projection = f.history.projection()
        assertTrue(projection.appliedCount > afterCreate)
        assertEquals("Restore review snapshot", projection.nextUndo()?.label)
        assertTrue("a review restore is reversible like any other edit", f.history.undo().succeeded)
    }

    // ── 70. Interchange and Raw Source ──────────────────────────────────────────────────────────────

    @Test
    fun applyingRawSourceTakesARecoveryPointThatTheBrowserLists() {
        val f = flowHost(BlockDocument(listOf(para("before", "p1"))))
        assertTrue(f.recovery.rows().isEmpty())

        val draft = RawSourceEditor.open(f.controller.document())
        val edited = RawSourceEditor.edit(draft, draft.source.replace("before", "after"))
        assertTrue(edited.canApply)
        assertTrue(InterchangeImportService.applyRawSource(f.controller, edited))

        assertEquals("after", f.controller.document().blocks.first().plainText())
        val rows = f.recovery.rows()
        assertEquals(1, rows.size)
        assertEquals(RecoveryReason.RAW_SOURCE, rows.single().reason)
        assertFalse("an automatic point must not masquerade as one the user named", rows.single().isManual)
        assertTrue(rows.single().isRestorable)
    }

    @Test
    fun anAppliedRawSourceIsBothUndoableAndRestorable() {
        val f = flowHost(BlockDocument(listOf(para("before", "p1"))))
        val draft = RawSourceEditor.open(f.controller.document())
        val edited = RawSourceEditor.edit(draft, draft.source.replace("before", "after"))
        InterchangeImportService.applyRawSource(f.controller, edited)

        // Route one: the ordinary Undo, because Raw Source is an edit like any other.
        assertEquals("Apply Raw Source", f.history.projection().nextUndo()?.label)
        assertTrue(f.history.undo().succeeded)
        assertEquals("before", f.controller.document().blocks.first().plainText())

        // Route two: the recovery point, for the case where the edit was noticed much later.
        assertTrue(f.history.redo().succeeded)
        assertEquals("after", f.controller.document().blocks.first().plainText())
        val id = f.recovery.rows().single().id
        assertTrue(f.recovery.requestRestore(id) is com.norfold.app.editor.recovery.RecoveryRestorePrompt.Ready)
        assertTrue(f.recovery.confirmRestore().succeeded)
        assertEquals("before", f.controller.document().blocks.first().plainText())
    }

    @Test
    fun anImportRecoveryPointCarriesItsOwnReasonAndLabel() {
        val f = flowHost(BlockDocument(listOf(para("original", "p1"))))
        f.controller.snapshotBeforeImport("Before import")

        val rows = f.recovery.rows()
        assertEquals(1, rows.size)
        assertEquals(RecoveryReason.IMPORT, rows.single().reason)
        assertEquals("Before import", rows.single().title)
        assertFalse(rows.single().isManual)
    }

    // ── 71. Guide is not an artifact edit ───────────────────────────────────────────────────────────

    /**
     * Guide teaches; it does not edit, and History is a record of edits (§37 item 71).
     *
     * The failure this guards against is a person opening History to find out what they changed and reading
     * "Opened Guide" or "Completed a topic" between two of their own edits — and worse, an Undo whose next
     * step is a tip having been shown. Guide state is discovery progress in [InteractionMemory], a different
     * store with a different lifetime.
     *
     * Driven through the real runtime rather than asserted structurally, because "Guide has no controller
     * today" is a fact about the current wiring, and the claim is about what History contains.
     */
    @Test
    fun guideActivityNeverReachesTheArtifactHistory() {
        val f = flowHost(BlockDocument(listOf(para("original", "p1"))))
        assertTrue(f.controller.mutateDocument("Type") { doc ->
            doc.copy(blocks = doc.blocks.map { if (it.id == "p1") para("typed", "p1") else it })
        })
        val afterTheOnlyEdit = f.history.projection().appliedCount

        val topic = GuideTopic(
            id = GuideTopicId("guide.history"),
            title = "Undo a change",
            explanation = "How to step back",
            domain = GuideDomain.FLOW,
            hosts = setOf(GuideHost.FLOW),
            steps = listOf(GuideStep("one", "Press Undo", "Use the visible control")),
        )
        val guide = GuideRuntime(
            catalog = GuideCatalog(listOf(topic)),
            progressStore = GuideProgressStore(InteractionMemory()),
            enabledFeatures = emptySet(),
            commandRouter = object : GuideCommandRouter {
                override fun availability(commandId: String) = GuideRouteAvailability(true, null)
                override fun execute(commandId: String) = CommandResult.Success()
            },
            surfaces = SurfaceCoordinator(),
            presentation = AdaptivePresentationRuntime(FormFactor(400, 800)),
        )

        // Everything Guide does that could plausibly look like an event worth recording.
        guide.updateContext(GuideContext(GuideHost.FLOW))
        guide.beginMeaningfulPeriod()
        assertTrue(guide.openBrowser(topic.id))

        assertEquals(
            "Guide is not an edit and must not appear in the artifact history",
            afterTheOnlyEdit,
            f.history.projection().appliedCount,
        )
        assertEquals("Type", f.history.projection().nextUndo()?.label)
        // And the one Undo available still reverses the user's edit rather than anything Guide did.
        assertTrue(f.history.undo().succeeded)
        assertEquals("original", f.controller.document().blocks.first().plainText())
    }

    // ── Flag gating across the hosts ────────────────────────────────────────────────────────────────

    /**
     * With HISTORY_UI off nothing is constructed at all — not a hidden runtime, not a registered surface.
     * A disabled-but-present browser would still read the recovery index on open and still occupy a surface
     * slot, which is a cost paid by every user who never turned the feature on.
     */
    @Test
    fun historyUiFlagGatesConstructionInEveryHost() {
        val flowOff = FlowHostRuntime(
            controller = FlowEditController(EditTarget("note:a"), BlockDocument()),
            initialFormFactor = FormFactor(400, 800),
        )
        assertNull(flowOff.history)
        assertNull(flowOff.recoveryBrowser)

        val documentOff = DocumentHostRuntime(
            controller = DocumentSceneController(EditTarget("note:b"), BlockDocument(listOf(page("p1")))),
            initialFormFactor = FormFactor(400, 800),
        )
        assertNull(documentOff.history)
        assertNull(documentOff.recoveryBrowser)

        val sheetDocument = BlockDocument(listOf(TableBlock(id = "t1", headers = listOf(cell("A")), rows = listOf(listOf(cell("1"))))))
        val sheetOff = SpreadsheetHostRuntime(
            controller = FlowEditController(EditTarget("note:c"), sheetDocument),
            initialFormFactor = FormFactor(900, 700),
        )
        assertNull(sheetOff.history)
        assertNull(sheetOff.recoveryBrowser)
    }

    @Test
    fun aGatedHostStillUndoesThroughTheSameCanonicalHistory() {
        // The flag governs the *UI*, never the engine: turning it off must not change what Undo does.
        val controller = FlowEditController(EditTarget("note:a"), BlockDocument(listOf(para("initial", "p0"))))
        val host = FlowHostRuntime(controller = controller, initialFormFactor = FormFactor(400, 800))
        controller.mutateDocument("Edit") { BlockDocument(listOf(para("changed", "p0"))) }

        assertEquals(HistoryRoute.GLOBAL, host.undoRouted())
        assertEquals("initial", controller.document().blocks.first().plainText())
        assertEquals(HistoryRoute.UNAVAILABLE, host.undoRouted())
    }
}
