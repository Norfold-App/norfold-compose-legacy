package com.norfold.app.editor.document

import com.norfold.app.domain.BlockDocument
import com.norfold.app.domain.BlockDocumentJson
import com.norfold.app.domain.DocCanvasSpec
import com.norfold.app.domain.DocLayoutJson
import com.norfold.app.domain.DocOverlapMode
import com.norfold.app.domain.LegacyDocumentCorpus
import com.norfold.app.domain.SceneObjectKind
import com.norfold.app.domain.UnknownBlock
import com.norfold.app.domain.pages
import com.norfold.app.editor.history.EditTarget
import com.norfold.app.editor.presentation.FormFactor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The legacy → bounded conversion driven from **stored bytes** rather than from hand-built objects.
 *
 * `LegacyDocumentMigrationTest` certifies the migration's semantics against documents constructed in
 * memory, which is the right way to pin those rules. This suite closes the gap that leaves: it starts
 * from [LegacyDocumentCorpus] payload strings — the `document_json` and pre-versioned `layout_json` of
 * one Overlap-era row — decodes them the way the repository does, and converts the result. A model
 * change that keeps the migration correct but breaks the decode path in front of it passes there and
 * fails here.
 *
 * The conversion of a person's existing document is the single least reversible thing this cutover
 * does, so what is pinned is what a conversion that "worked" would hide: that geometry survives the
 * unit change, that stacking order survives z being renumbered, that content this build cannot read is
 * preserved rather than flattened, and that a refused conversion leaves the original exactly as it was.
 */
class LegacyCorpusMigrationTest {

    /** dp → points, as [LegacyDocumentMigration] converts them. Restated so the expectation is independent. */
    private val pointsPerDp = 72f / 160f

    private fun storedDocument() = BlockDocumentJson.decodeDocumentPayload(LegacyDocumentCorpus.OVERLAP_DOCUMENT_JSON)

    private fun storedLayout() = DocLayoutJson.decode(LegacyDocumentCorpus.OVERLAP_LAYOUT_JSON)

    private fun runtime(document: BlockDocument) = DocumentHostRuntime(
        controller = DocumentSceneController(EditTarget("note:1"), document),
        initialFormFactor = FormFactor(400, 800),
    )

    // ── The stored row decodes before anything converts it ────────────────────────────────────────

    @Test
    fun theStoredRowDecodesToEveryBlockItHoldsAcrossAllThreePayloadGenerations() {
        val document = storedDocument()

        assertEquals(LegacyDocumentCorpus.OVERLAP_BLOCK_IDS, document.blocks.map { it.id })
        // The mixture is the point: one array holding a bare v0 block, v1 envelopes, and an unreadable
        // kind. A decoder that handled only the current generation would silently lose the others.
        val opaque = document.blocks.single { it.id == LegacyDocumentCorpus.OVERLAP_OPAQUE_BLOCK_ID }
        assertTrue("an unreadable kind must survive as an opaque block", opaque is UnknownBlock)
        assertEquals(4, document.blocks.count { it !is UnknownBlock })
    }

    @Test
    fun theStoredLayoutDecodesWithoutTheVersionedWrapperOrACanvas() {
        val layout = storedLayout()

        assertEquals(setOf("o1", "o2", "o3", "o4"), layout.keys)
        assertEquals(10f, layout.getValue("o1").x)
        assertEquals(150f, layout.getValue("o1").height)
        // No canvas was stored in this format, so decoding must supply the default page rather than a
        // zero-sized one — a conversion onto a canvas with no area would have nowhere to put content.
        val canvas = DocLayoutJson.decodeCanvas(LegacyDocumentCorpus.OVERLAP_LAYOUT_JSON)
        assertEquals(DocCanvasSpec().normalized().width, canvas.width)
        assertTrue(canvas.pageCount >= 1)
    }

    // ── Geometry survives the conversion ──────────────────────────────────────────────────────────

    @Test
    fun everyStoredPlacementBecomesTheSameRectangleInPoints() {
        // The legacy editor stored dp; the scene model stores points. "Preserved" therefore means
        // converted by exactly one factor — not that the numbers are unchanged, and not that they were
        // re-derived from anything else.
        val report = LegacyDocumentMigration.migrate(storedDocument(), storedLayout(), mode = DocOverlapMode.Overlap)
        val placed = report.document.pages().flatMap { it.objects }.associateBy { it.content.id }

        storedLayout().forEach { (id, placement) ->
            val geometry = placed.getValue(id).geometry
            assertEquals("$id x", placement.x * pointsPerDp, geometry.x, 0.01f)
            assertEquals("$id y", placement.y * pointsPerDp, geometry.y, 0.01f)
            assertEquals("$id width", placement.width * pointsPerDp, geometry.width, 0.01f)
            assertEquals("$id height", placement.height * pointsPerDp, geometry.height, 0.01f)
        }
    }

    @Test
    fun overlappingBoxesStayOverlappingRatherThanBeingSeparated() {
        // o1 and o2 overlap on disk. A conversion that tidied them apart would be redesigning the
        // person's document, which is a different act from migrating it.
        val report = LegacyDocumentMigration.migrate(storedDocument(), storedLayout(), mode = DocOverlapMode.Overlap)
        val placed = report.document.pages().flatMap { it.objects }.associateBy { it.content.id }

        val first = placed.getValue("o1").geometry
        val second = placed.getValue("o2").geometry
        assertTrue(
            "the two boxes must still intersect",
            first.x < second.x + second.width && second.x < first.x + first.width &&
                first.y < second.y + second.height && second.y < first.y + first.height,
        )
    }

    @Test
    fun sparseStoredZBecomesContiguousOrderWithTheSameStacking() {
        // Stored z is 0, 3, 7, 41. The scene model renumbers to 0..n-1, so what must be preserved is
        // the *order*, not the integers — front stays in front. Asserting the literals would pin an
        // implementation detail the model deliberately does not keep.
        val report = LegacyDocumentMigration.migrate(storedDocument(), storedLayout(), mode = DocOverlapMode.Overlap)
        val page = report.document.pages().single()
        val order = page.objects.map { it.content.id }

        val expected = (storedLayout().entries.sortedBy { it.value.z }.map { it.key })
        assertEquals("stacking order must match the stored z order", expected, order.filter { it in storedLayout() })
        assertEquals("and z is renumbered contiguously from zero", page.objects.indices.toList(), page.objects.map { it.z })
    }

    @Test
    fun theBlockWithNoStoredPlacementIsGivenOneRatherThanBeingDroppedOrHidden() {
        val report = LegacyDocumentMigration.migrate(storedDocument(), storedLayout(), mode = DocOverlapMode.Overlap)

        assertEquals(listOf(LegacyDocumentCorpus.OVERLAP_OPAQUE_BLOCK_ID), report.unplacedBlocks)
        val objects = report.document.pages().flatMap { it.objects }
        assertEquals("every stored block is converted", LegacyDocumentCorpus.OVERLAP_BLOCK_IDS.size, objects.size)
        val unplaced = objects.single { it.content.id == LegacyDocumentCorpus.OVERLAP_OPAQUE_BLOCK_ID }
        assertTrue("it must land somewhere visible, not at the origin under everything", unplaced.geometry.x > 0f)
    }

    // ── Content survives the conversion ───────────────────────────────────────────────────────────

    @Test
    fun contentThisBuildCannotReadIsPreservedVerbatimAndStillReEncodesToItsStoredBytes() {
        val report = LegacyDocumentMigration.migrate(storedDocument(), storedLayout(), mode = DocOverlapMode.Overlap)

        val obj = report.document.pages()
            .flatMap { it.objects }
            .single { it.content.id == LegacyDocumentCorpus.OVERLAP_OPAQUE_BLOCK_ID }
        assertEquals("marked, not coerced into a text frame", SceneObjectKind.Preserved, obj.kind)
        // Compared against the stored literal, not against a re-decode of it: two passes through the
        // same decoder would agree with each other even if both had drifted from the bytes on disk.
        // Byte-identical after decode *and* conversion is what lets a later build that understands
        // this kind read it correctly.
        assertEquals(
            LegacyDocumentCorpus.OVERLAP_OPAQUE_BLOCK_JSON,
            (obj.content as UnknownBlock).rawJson,
        )
        assertEquals(
            LegacyDocumentCorpus.OVERLAP_OPAQUE_BLOCK_JSON,
            BlockDocumentJson.encodeBlock(obj.content),
        )
    }

    @Test
    fun theConvertedDocumentSurvivesBeingWrittenAndReadBackAsStoredBytes() {
        // Conversion is only half the act: the result has to survive the save that follows it. A scene
        // that converts correctly but cannot round-trip through `document_json` would lose the
        // document on the next open, after the legacy copy had already been replaced.
        val converted = LegacyDocumentMigration
            .migrate(storedDocument(), storedLayout(), mode = DocOverlapMode.Overlap)
            .document

        val reloaded = BlockDocumentJson.decodeDocumentPayload(BlockDocumentJson.encodeDocumentPayload(converted))

        assertEquals(converted, reloaded)
        assertEquals(
            LegacyDocumentCorpus.OVERLAP_BLOCK_IDS.toSet(),
            reloaded.pages().flatMap { it.objects }.map { it.content.id }.toSet(),
        )
    }

    @Test
    fun convertingTwiceIsNotAConversionOfAConversion() {
        val first = LegacyDocumentMigration
            .migrate(storedDocument(), storedLayout(), mode = DocOverlapMode.Overlap)
            .document

        val second = LegacyDocumentMigration.migrate(first, storedLayout(), mode = DocOverlapMode.Overlap)

        assertEquals("an already-converted document is returned as it stands", first, second.document)
        assertTrue("and nothing is reported as newly unplaced or orphaned", second.unplacedBlocks.isEmpty())
    }

    // ── A refused conversion leaves the original alone ────────────────────────────────────────────

    @Test
    fun aStoredLayoutNamingContentThatIsGoneIsRefusedAndTheOriginalIsUntouched() {
        // The executable form of "never overwrite the only legacy copy": a stale placement means the
        // stored layout and the stored content disagree, and the app must stop rather than decide.
        val original = storedDocument()
        val runtime = runtime(original)
        val staleLayout = storedLayout() + ("deleted-long-ago" to storedLayout().getValue("o1"))

        val report = runtime.migrateLegacyDocument(staleLayout, mode = DocOverlapMode.Overlap)

        assertNotNull(report)
        assertFalse("a conversion that would drop stored state is not lossless", report!!.isLossless)
        assertEquals(listOf("deleted-long-ago"), report.orphanedPlacements)
        assertEquals("the document is exactly as it was", original.normalized(), runtime.document())
        assertFalse("and nothing was committed to undo", runtime.controller.canUndo())
        assertTrue(
            "the refusal names what a person can act on",
            runtime.migrationRefusal?.refusalDetail()?.contains("deleted-long-ago") == true,
        )
    }

    @Test
    fun aRefusalIsRecoverableRatherThanTerminal() {
        // Refusing must not strand the document: the same conversion, explicitly allowed, still works.
        val runtime = runtime(storedDocument())
        val staleLayout = storedLayout() + ("deleted-long-ago" to storedLayout().getValue("o1"))
        runtime.migrateLegacyDocument(staleLayout, mode = DocOverlapMode.Overlap)

        val forced = runtime.migrateLegacyDocument(staleLayout, mode = DocOverlapMode.Overlap, allowLossy = true)

        assertNotNull(forced)
        assertEquals(LegacyDocumentCorpus.OVERLAP_BLOCK_IDS.size, forced!!.objectCount)
        assertTrue(runtime.document().pages().isNotEmpty())
        assertTrue("and the conversion is undoable in one step", runtime.undo())
        assertEquals(storedDocument().normalized(), runtime.document())
    }
}
