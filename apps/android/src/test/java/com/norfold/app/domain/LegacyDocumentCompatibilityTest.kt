package com.norfold.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Certifies that documents written by earlier generations of this app still decode correctly now that
 * the editors that wrote them are gone.
 *
 * **The distinction this suite exists to hold.** The cutover removed the legacy *product* — the host,
 * the surface switch, the Overlap mode controls. It removed no legacy *format*. An install that
 * predates the cutover holds rows written by those editors, and the only thing standing between them
 * and their content is the decoder. So the properties pinned here are decode-side: what a stored byte
 * sequence means, not what any editor would do with it.
 *
 * **What is certified elsewhere, deliberately not repeated here.** The block-payload generations (v0
 * bare block, the retired `renderMode` field, the `{"v":1,…}` envelope, unknown kinds and future
 * versions preserved verbatim, corrupt payloads degrading to [UnknownBlock]) are covered by
 * `BlockPayloadEnvelopeTest`; the layout codec and the pre-versioned raw placement map by
 * `DocLayoutTest`; nested containers through JSON by `BlockContainerTreeTest`. Those tests already
 * fail if a decoder arm is retired, so restating them here would add maintenance cost and no
 * protection. This file holds what none of them cover.
 *
 * Fixtures come from [LegacyDocumentCorpus] and are literal stored shapes rather than values produced
 * by today's writer, because a writer/reader pair will keep agreeing with each other while both drift
 * away from the disk.
 */
class LegacyDocumentCompatibilityTest {

    // ── Stored layout-mode literals ───────────────────────────────────────────────────────────────
    //
    // `DocLayoutTest` spot-checks four literals. The corpus holds nine, because the mode tag was
    // renamed twice while rows written under the old spellings stayed on disk. Each family is
    // asserted whole: a decoder arm that looks dead is one generation of stored value, and dropping
    // it silently reinterprets those rows as a mode they never were.

    @Test
    fun everyStoredReflowLiteralStillDecodesToReflow() {
        LegacyDocumentCorpus.REFLOW_LITERALS.forEach { literal ->
            assertEquals(literal, DocOverlapMode.Reflow, docOverlapModeOf(literal))
        }
    }

    @Test
    fun everyStoredBoundedLiteralStillDecodesToBounded() {
        LegacyDocumentCorpus.BOUNDED_LITERALS.forEach { literal ->
            assertEquals(literal, DocOverlapMode.Bounded, docOverlapModeOf(literal))
        }
    }

    @Test
    fun everyStoredOverlapLiteralStillDecodesToOverlap() {
        // The Overlap product is retired. These three literals are what is on disk, and the enum
        // entry they decode to is a storage tag, not a route to an editor that no longer exists.
        LegacyDocumentCorpus.OVERLAP_LITERALS.forEach { literal ->
            assertEquals(literal, DocOverlapMode.Overlap, docOverlapModeOf(literal))
        }
        // Upper case too: the tag was written by `name.lowercase()` only after the second rename.
        assertEquals(DocOverlapMode.Overlap, docOverlapModeOf("INFINITE_CANVAS"))
    }

    @Test
    fun everyStoredModeSurvivesABackupRoundTripWithItsGeometry() {
        // `DocLayoutTest` covers Overlap. A backup taken before the cutover can hold any of the
        // three, and a mode that decoded wrong on restore would move a document to another product.
        DocOverlapMode.entries.forEach { mode ->
            val canvas = DocCanvasSpec.letter().normalized()
            val note = note(
                mode = mode,
                layout = mapOf("r1" to FreeformPlacement(x = 5f, y = 6f, width = 200f, height = 100f, z = 0)),
                canvas = canvas,
            )

            val restored = BackupCodec
                .decode(BackupCodec.encode(snapshotOf(note)))
                .notes
                .single()

            assertEquals("stored mode must survive a backup round trip", mode, restored.overlapMode)
            assertEquals(note.freeformLayout, restored.freeformLayout)
            assertEquals(canvas, restored.canvasSpec)
        }
    }

    // ── Whole-document payloads ───────────────────────────────────────────────────────────────────

    @Test
    fun nonLatinAndCombiningTextSurvivesTheBlockPayloadCodec() {
        val block = LegacyDocumentCorpus.paragraph("u1", LegacyDocumentCorpus.UNICODE_SAMPLE)

        val reloaded = BlockDocumentJson.decodeBlock(BlockDocumentJson.encodeBlock(block))

        assertEquals(block, reloaded)
        // Asserted on the string as well as the block: equality on the data class would also pass
        // if both sides were mangled identically.
        assertEquals(LegacyDocumentCorpus.UNICODE_SAMPLE, reloaded.plainText())
    }

    @Test
    fun aLargeDocumentPayloadRoundTripsWithEveryBlockAndItsOrderIntact() {
        val document = BlockDocument((0 until 500).map { LegacyDocumentCorpus.paragraph("b$it", "line $it") })
            .normalized()

        val reloaded = BlockDocumentJson.decodeDocumentPayload(BlockDocumentJson.encodeDocumentPayload(document))

        assertEquals(500, reloaded.blocks.size)
        // Ids and their order, not just the count: the freeform layout map is keyed by block id, so
        // a reordering or a regenerated id detaches every stored placement from its block.
        assertEquals(document.blocks.map { it.id }, reloaded.blocks.map { it.id })
        assertEquals(document, reloaded)
    }

    @Test
    fun aNonArrayDocumentPayloadIsPreservedAsOneUnknownBlockButIsNotMistakenForADocument() {
        val notADocument = """{"unexpected":"shape"}"""

        val decoded = BlockDocumentJson.decodeDocumentPayload(notADocument)

        assertEquals(1, decoded.blocks.size)
        assertTrue(decoded.blocks.single() is UnknownBlock)
        // Decoding it must not be read as evidence that it *was* a document. Recovery restore checks
        // this predicate before overwriting live content, and `decodeDocumentPayload` never fails —
        // so "it decoded" answers a different question than "it is a document".
        assertFalse(BlockDocumentJson.isDocumentPayload(notADocument))
    }

    @Test
    fun aBlankDocumentPayloadOpensAsAnEmptyParagraphRatherThanAnUnknownBlock() {
        // A blank `document_json` is a real row shape: a note created and never typed into. It must
        // open as something a person can type in, not as an opaque block they cannot edit.
        val decoded = BlockDocumentJson.decodeDocumentPayload("")

        assertEquals(1, decoded.blocks.size)
        assertTrue(decoded.blocks.single() is ParagraphBlock)
        assertEquals("", decoded.plainText())
    }

    // ── Fixtures ──────────────────────────────────────────────────────────────────────────────────

    private fun note(
        mode: DocOverlapMode,
        layout: Map<String, FreeformPlacement>,
        canvas: DocCanvasSpec,
    ) = Note(
        id = 1L,
        title = "Legacy note",
        document = LegacyDocumentCorpus.reflowDocument(),
        notebookId = null,
        pinned = false,
        starred = false,
        archived = false,
        locked = false,
        createdAt = 1L,
        updatedAt = 2L,
        overlapMode = mode,
        freeformLayout = layout,
        canvasSpec = canvas,
    )

    private fun snapshotOf(note: Note) = BackupSnapshot(
        notes = listOf(note),
        notebooks = emptyList(),
        tags = emptyList(),
        attachments = emptyList(),
    )
}
