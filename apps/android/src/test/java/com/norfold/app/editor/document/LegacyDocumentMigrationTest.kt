package com.norfold.app.editor.document

import com.norfold.app.domain.BlockDocument
import com.norfold.app.domain.DocCanvasSpec
import com.norfold.app.domain.DocOverlapMode
import com.norfold.app.domain.DocumentPageBlock
import com.norfold.app.domain.FreeformPlacement
import com.norfold.app.domain.ImageBlock
import com.norfold.app.domain.InlineText
import com.norfold.app.domain.ParagraphBlock
import com.norfold.app.domain.SceneObjectKind
import com.norfold.app.domain.TableBlock
import com.norfold.app.domain.UnknownBlock
import com.norfold.app.domain.pages
import com.norfold.app.editor.history.EditTarget
import com.norfold.app.editor.presentation.FormFactor
import com.norfold.app.editor.recovery.RecoveryReason
import com.norfold.app.editor.recovery.RecoverySnapshotManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The legacy overlap → bounded scene migration (roadmap Phase 2 §9).
 *
 * The properties worth pinning are the ones a migration that "worked" would hide: that no block is
 * dropped, that content this build cannot read survives verbatim, that a stale placement does not
 * become a phantom object, that page-relative coordinates are actually page-relative, and that the
 * original is restorable after the fact.
 */
class LegacyDocumentMigrationTest {

    private fun para(id: String, text: String = id) =
        ParagraphBlock(id = id, content = listOf(InlineText(text)))

    private fun legacy(vararg ids: String) = BlockDocument(ids.map { para(it) })

    private fun placement(x: Float, y: Float, z: Int = 0) =
        FreeformPlacement(x = x, y = y, width = 320f, height = 160f, z = z)

    private fun runtime(document: BlockDocument): DocumentHostRuntime = DocumentHostRuntime(
        controller = DocumentSceneController(EditTarget("note:1"), document),
        initialFormFactor = FormFactor(400, 800),
    )

    // ── Nothing is dropped ──────────────────────────────────────────────────────────────────────────

    @Test
    fun everyLegacyBlockBecomesExactlyOneSceneObject() {
        val document = legacy("a", "b", "c")
        val layout = mapOf("a" to placement(0f, 0f), "b" to placement(100f, 50f), "c" to placement(50f, 200f))

        val report = LegacyDocumentMigration.migrate(document, layout)

        assertEquals(3, report.objectCount)
        val contentIds = report.document.pages().flatMap { it.objects }.map { it.content.id }.toSet()
        assertEquals("no block may be lost or duplicated", setOf("a", "b", "c"), contentIds)
    }

    @Test
    fun blockContentIsCarriedVerbatimNotReconstructed() {
        val original = para("a", "the exact text")
        val report = LegacyDocumentMigration.migrate(BlockDocument(listOf(original)), mapOf("a" to placement(0f, 0f)))

        val carried = report.document.pages().single().objects.single().content
        assertEquals("the block itself is the content, unchanged", original, carried)
    }

    @Test
    fun contentThisBuildCannotReadIsPreservedAndMarkedNeverFlattened() {
        val payload = """{"kind":"future.thing","id":"u1","data":42}"""
        val unknown = UnknownBlock(id = "u1", rawJson = payload)
        val report = LegacyDocumentMigration.migrate(
            BlockDocument(listOf(unknown)),
            mapOf("u1" to placement(10f, 10f)),
        )

        val obj = report.document.pages().single().objects.single()
        assertEquals("marked as not-natively-understood", SceneObjectKind.Preserved, obj.kind)
        assertEquals("and carried byte-for-byte", unknown, obj.content)
        assertEquals(listOf("u1"), report.preserved)
    }

    @Test
    fun aBlockWithNoPlacementIsLaidOutInReadingOrderNotStackedAtTheOrigin() {
        // The legacy editor drew unplaced blocks at 0,0; migrating them all to 0,0 would leave every
        // one hidden under the last.
        val report = LegacyDocumentMigration.migrate(legacy("a", "b", "c"), emptyMap())

        val objects = report.document.pages().single().objects
        assertEquals(3, objects.size)
        val positions = objects.map { it.geometry.x to it.geometry.y }
        assertEquals("no two unplaced blocks may occupy the same point", 3, positions.toSet().size)
        assertEquals(listOf("a", "b", "c"), report.unplacedBlocks)
    }

    @Test
    fun aPlacementWhoseBlockIsGoneIsReportedNotTurnedIntoAPhantomObject() {
        val report = LegacyDocumentMigration.migrate(
            legacy("a"),
            mapOf("a" to placement(0f, 0f), "deleted-long-ago" to placement(400f, 400f)),
        )

        assertEquals(1, report.objectCount)
        assertEquals(listOf("deleted-long-ago"), report.orphanedPlacements)
        assertFalse("a lossy migration must say so", report.isLossless)
    }

    // ── Geometry fidelity ───────────────────────────────────────────────────────────────────────────

    @Test
    fun placementsOnLaterPagesBecomePageRelativeCoordinates() {
        // Legacy y runs continuously across pages; failing to subtract the page origin would put page
        // two's content thousands of points below the sheet it belongs to.
        val spec = DocCanvasSpec(pageCount = 2).normalized()
        // FreeformPlacement is persisted in Compose dp while DocCanvasSpec uses document points.
        // Convert the page origin back to dp before adding a 100dp in-page offset.
        val secondPageY = spec.pageOrigin(1) * 160f / 72f + 100f
        val report = LegacyDocumentMigration.migrate(
            legacy("a"),
            mapOf("a" to placement(0f, secondPageY)),
            spec,
        )

        assertEquals(2, report.pageCount)
        assertTrue("nothing lands on page one", report.document.pages()[0].objects.isEmpty())
        val obj = report.document.pages()[1].objects.single()
        assertEquals("100 dp below the page top", 100f * 72f / 160f, obj.geometry.y, 0.01f)
    }

    @Test
    fun layerOrderSurvivesTheMigration() {
        val report = LegacyDocumentMigration.migrate(
            legacy("back", "front"),
            mapOf("back" to placement(0f, 0f, z = 0), "front" to placement(0f, 0f, z = 5)),
        )

        val objects = report.document.pages().single().objects
        val backIndex = objects.indexOfFirst { it.content.id == "back" }
        val frontIndex = objects.indexOfFirst { it.content.id == "front" }
        assertTrue("front must still be in front", frontIndex > backIndex)
    }

    @Test
    fun pageSizeIsTakenFromTheLegacyCanvasNotDefaultedToA4() {
        val letter = DocCanvasSpec.letter()
        val report = LegacyDocumentMigration.migrate(legacy("a"), mapOf("a" to placement(0f, 0f)), letter)

        assertEquals(letter.width, report.document.pages().single().geometry.width, 0.01f)
        assertEquals(letter.height, report.document.pages().single().geometry.height, 0.01f)
    }

    @Test
    fun contentBelowTheLastDeclaredPageStillGetsAPageToLandOn() {
        // A legacy document could be saved with content past its declared page count; trusting the
        // declaration alone would place that content on a page that does not exist.
        val spec = DocCanvasSpec(pageCount = 1).normalized()
        val report = LegacyDocumentMigration.migrate(
            legacy("a"),
            mapOf("a" to placement(0f, spec.pageOrigin(0) + spec.height * 3f)),
            spec,
        )

        assertTrue("the page count grew to cover the content", report.pageCount > 1)
        assertEquals(1, report.objectCount)
    }

    @Test
    fun legacyPageIdsAreReusedSoPagesRemainRecognizable() {
        val spec = DocCanvasSpec(pageCount = 2, pageIds = listOf("page-alpha", "page-beta")).normalized()
        val report = LegacyDocumentMigration.migrate(legacy("a"), emptyMap(), spec)

        assertEquals(listOf("page-alpha", "page-beta"), report.document.pages().map { it.id })
    }

    @Test
    fun blockKindsMapToTheirSceneEquivalents() {
        val document = BlockDocument(
            listOf(
                para("p"),
                ImageBlock(id = "i", source = "file://x"),
                TableBlock(id = "t"),
            ),
        )
        val report = LegacyDocumentMigration.migrate(document, emptyMap())
        val byContent = report.document.pages().flatMap { it.objects }.associateBy { it.content.id }

        assertEquals(SceneObjectKind.TextFrame, byContent.getValue("p").kind)
        assertEquals(SceneObjectKind.Image, byContent.getValue("i").kind)
        assertEquals(SceneObjectKind.Table, byContent.getValue("t").kind)
        assertFalse("a table cannot be rotated", byContent.getValue("t").canRotate)
    }

    @Test
    fun aReflowDocumentIgnoresPlacementItNeverMeaningfullyHad() {
        // Reflow notes were never positioned by the user; reading a stale side map would scatter them.
        val report = LegacyDocumentMigration.migrate(
            legacy("a", "b"),
            mapOf("a" to placement(900f, 900f)),
            DocCanvasSpec(),
            DocOverlapMode.Reflow,
        )

        assertEquals(listOf("a", "b"), report.unplacedBlocks)
        assertTrue("and no orphan is claimed either", report.orphanedPlacements.isEmpty())
    }

    @Test
    fun migratedPagesArriveWithTheirStructureProtected() {
        val report = LegacyDocumentMigration.migrate(legacy("a"), emptyMap())
        assertTrue(
            "imported content must not be reshaped by an accidental page edit",
            report.document.pages().all { it.geometry.structureLocked },
        )
    }

    @Test
    fun anAlreadyMigratedDocumentIsReturnedUntouched() {
        val bounded = BlockDocument(listOf(DocumentPageBlock(id = "p1").normalized()))
        assertTrue(LegacyDocumentMigration.isMigrated(bounded))
        assertEquals(bounded, LegacyDocumentMigration.migrate(bounded).document)
    }

    // ── The runtime pipeline: snapshot first, then commit ────────────────────────────────────────────

    @Test
    fun theRuntimeTakesARecoverySnapshotBeforeItMigrates() {
        val rt = runtime(legacy("a", "b"))
        val recovery = RecoverySnapshotManager(rt.controller.workspace)

        rt.migrateLegacyDocument(mapOf("a" to placement(0f, 0f), "b" to placement(0f, 200f)), recovery = recovery)

        val snapshots = recovery.snapshots()
        assertEquals(1, snapshots.size)
        assertEquals(RecoveryReason.MIGRATION, snapshots.single().reason)
    }

    @Test
    fun theOriginalIsRestorableFromTheSnapshotAfterMigrating() {
        val original = legacy("a", "b")
        val rt = runtime(original)
        val recovery = RecoverySnapshotManager(rt.controller.workspace)

        rt.migrateLegacyDocument(mapOf("a" to placement(0f, 0f)), recovery = recovery, allowLossy = true)
        assertTrue("migration happened", rt.document().pages().isNotEmpty())

        recovery.restoreLatest()

        assertEquals("the pre-image comes back exactly", original.normalized(), rt.document())
    }

    @Test
    fun migrationIsOneUndoableTransaction() {
        val original = legacy("a", "b", "c")
        val rt = runtime(original)

        rt.migrateLegacyDocument(emptyMap())

        assertEquals("one transaction, not one per block", 1, rt.controller.undoDepth())
        assertTrue(rt.undo())
        assertEquals("undo restores the legacy document", original.normalized(), rt.document())
    }

    @Test
    fun aLossyMigrationIsReportedButNotAppliedUnlessAllowed() {
        val original = legacy("a")
        val rt = runtime(original)

        val report = rt.migrateLegacyDocument(
            mapOf("a" to placement(0f, 0f), "ghost" to placement(0f, 0f)),
        )

        assertNotNull(report)
        assertFalse(report!!.isLossless)
        assertEquals("the document is untouched until the user decides", original.normalized(), rt.document())
        assertFalse(rt.controller.canUndo())

        val forced = rt.migrateLegacyDocument(
            mapOf("a" to placement(0f, 0f), "ghost" to placement(0f, 0f)),
            allowLossy = true,
        )
        assertTrue(forced!!.objectCount == 1)
        assertTrue(rt.document().pages().isNotEmpty())
    }

    @Test
    fun migratingClearsASelectionThatNamedTheOldBlocks() {
        val rt = runtime(legacy("a"))
        rt.selection.setCaret(com.norfold.app.editor.selection.TextCaret("a", 2))

        rt.migrateLegacyDocument(emptyMap())

        assertEquals(0, rt.selection.count)
        assertNotNull("and the active page is one that exists", rt.scope.activePageId)
        assertTrue(rt.pages().any { it.id == rt.scope.activePageId })
    }

    @Test
    fun migratingAnAlreadyBoundedDocumentDoesNothingAtAll() {
        val bounded = BlockDocument(listOf(DocumentPageBlock(id = "p1").normalized()))
        val rt = runtime(bounded)

        assertNull(rt.migrateLegacyDocument(emptyMap()))
        assertFalse("no transaction is recorded for a no-op", rt.controller.canUndo())
    }

    @Test
    fun theUserIsToldWhatTheMigrationDid() {
        val rt = runtime(legacy("a", "b"))
        rt.migrateLegacyDocument(emptyMap())
        val notice = rt.consumeNotice()
        assertNotNull(notice)
        assertTrue("the summary names what was converted", notice!!.contains("2 objects"))
    }
}
