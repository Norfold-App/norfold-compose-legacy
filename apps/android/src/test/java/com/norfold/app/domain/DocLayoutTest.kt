package com.norfold.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the per-document layout model: [FreeformPlacement] JSON round-trips, [docOverlapModeOf]
 * parsing, and [DocLayerOrder] z-order operations (contiguous indices, no gaps or duplicates).
 */
class DocLayoutTest {

    private fun layoutOf(vararg ids: String): Map<String, FreeformPlacement> =
        ids.mapIndexed { index, id -> id to FreeformPlacement(x = index * 10f, y = index * 20f, z = index) }.toMap()

    private fun zOrder(layout: Map<String, FreeformPlacement>): List<String> =
        layout.entries.sortedBy { it.value.z }.map { it.key }

    // --- Mode parsing ------------------------------------------------------------------------

    @Test
    fun `docOverlapModeOf parses case-insensitively and defaults to Reflow`() {
        assertEquals(DocOverlapMode.Reflow, docOverlapModeOf("reflow"))
        assertEquals(DocOverlapMode.Overlap, docOverlapModeOf("overlap"))
        assertEquals(DocOverlapMode.Overlap, docOverlapModeOf("Overlap"))
        assertEquals(DocOverlapMode.Bounded, docOverlapModeOf("document_canvas"))
        assertEquals(DocOverlapMode.Reflow, docOverlapModeOf("garbage"))
        assertEquals(DocOverlapMode.Reflow, docOverlapModeOf(null))
    }

    @Test
    fun `stored mode strings round trip for every enum entry`() {
        // The repository persists name.lowercase(); every entry must decode back to itself so an
        // enum rename or extension can never silently corrupt stored notes.
        DocOverlapMode.entries.forEach { mode ->
            assertEquals(mode, docOverlapModeOf(mode.name.lowercase()))
        }
    }

    @Test
    fun `legacy stored literals keep decoding to the original modes`() {
        // "reflow" and "overlap" exist in NoteEntity.overlapMode rows on real installs — these
        // exact strings are a compatibility contract, independent of the enum's current names.
        assertEquals(DocOverlapMode.Reflow, docOverlapModeOf("reflow"))
        assertEquals(DocOverlapMode.Overlap, docOverlapModeOf("overlap"))
        // The UI relabel to "Page"/"Infinite page" is text-only and must never leak into storage.
        assertEquals(DocOverlapMode.Reflow, docOverlapModeOf("page"))
        assertEquals(DocOverlapMode.Reflow, docOverlapModeOf("infinite page"))
    }

    // --- JSON codec --------------------------------------------------------------------------

    @Test
    fun `freeform layout survives json round trip`() {
        val layout = mapOf(
            "a" to FreeformPlacement(x = 12.5f, y = 40f, width = 300f, height = 120f, z = 0),
            "b" to FreeformPlacement(x = 80f, y = 200f, width = 160f, height = 90f, z = 1),
        )
        assertEquals(layout, DocLayoutJson.decode(DocLayoutJson.encode(layout)))
    }

    @Test
    fun `codec treats empty and invalid input as empty layout`() {
        assertEquals(emptyMap<String, FreeformPlacement>(), DocLayoutJson.decode(DocLayoutJson.encode(emptyMap())))
        assertEquals(emptyMap<String, FreeformPlacement>(), DocLayoutJson.decode(null))
        assertEquals(emptyMap<String, FreeformPlacement>(), DocLayoutJson.decode(""))
        assertEquals(emptyMap<String, FreeformPlacement>(), DocLayoutJson.decode("not json"))
    }

    @Test
    fun `versioned layout preserves bounded canvas and reads legacy maps`() {
        val layout = mapOf("a" to FreeformPlacement(x = 12f, y = 20f, z = 4))
        val canvas = DocCanvasSpec.letter().copy(pageCount = 3)
        val encoded = DocLayoutJson.encode(layout, canvas)
        assertEquals(canvas, DocLayoutJson.decodeCanvas(encoded))
        assertEquals(0, DocLayoutJson.decode(encoded).getValue("a").z)

        val legacy = """{"a":{"x":12.0,"y":20.0,"width":320.0,"height":160.0,"z":4}}"""
        assertEquals(12f, DocLayoutJson.decode(legacy).getValue("a").x)
        assertEquals(DocCanvasSpec(), DocLayoutJson.decodeCanvas(legacy))
    }

    @Test
    fun `bounded canvas computes pages required without clipping placed blocks`() {
        val spec = DocCanvasSpec.a4()
        assertEquals(1, spec.pagesRequiredFor(emptyMap()))
        assertEquals(1, spec.pagesRequiredFor(mapOf("a" to FreeformPlacement(y = 20f, height = 200f))))
        assertEquals(3, spec.pagesRequiredFor(mapOf("a" to FreeformPlacement(y = 1700f, height = 100f))))
    }

    // --- Layer order -------------------------------------------------------------------------

    @Test
    fun `normalize reassigns contiguous z indices`() {
        val messy = mapOf(
            "a" to FreeformPlacement(z = 7),
            "b" to FreeformPlacement(z = -2),
            "c" to FreeformPlacement(z = 7),
        )
        val normalized = DocLayerOrder.normalize(messy)
        assertEquals(setOf(0, 1, 2), normalized.values.map { it.z }.toSet())
        assertEquals("b", zOrder(normalized).first())
    }

    @Test
    fun `bringToFront and sendToBack move the block to the extremes`() {
        val layout = layoutOf("a", "b", "c")

        assertEquals(listOf("b", "c", "a"), zOrder(DocLayerOrder.bringToFront(layout, "a")))
        assertEquals(listOf("c", "a", "b"), zOrder(DocLayerOrder.sendToBack(layout, "c")))
    }

    @Test
    fun `bringForward and sendBackward swap with the adjacent layer only`() {
        val layout = layoutOf("a", "b", "c")

        assertEquals(listOf("b", "a", "c"), zOrder(DocLayerOrder.bringForward(layout, "a")))
        assertEquals(listOf("a", "c", "b"), zOrder(DocLayerOrder.sendBackward(layout, "c")))
        // Already at the extreme → unchanged.
        assertEquals(listOf("a", "b", "c"), zOrder(DocLayerOrder.bringForward(layout, "c")))
        assertEquals(listOf("a", "b", "c"), zOrder(DocLayerOrder.sendBackward(layout, "a")))
    }

    @Test
    fun `layer ops on unknown ids are no-ops and never break contiguity`() {
        val layout = layoutOf("a", "b")
        assertEquals(layout, DocLayerOrder.bringToFront(layout, "missing"))

        val reordered = DocLayerOrder.bringToFront(layout, "a")
        val zs = reordered.values.map { it.z }.sorted()
        assertEquals(listOf(0, 1), zs)
        assertTrue(reordered.values.map { it.z }.distinct().size == reordered.size)
    }

    // --- Backup round trip -------------------------------------------------------------------

    @Test
    fun `note overlap mode and layout survive backup encode-decode`() {
        val note = Note(
            id = 1L,
            title = "Doc",
            bodyMarkdown = "# Doc",
            notebookId = null,
            pinned = false,
            starred = false,
            archived = false,
            locked = false,
            createdAt = 1L,
            updatedAt = 2L,
            overlapMode = DocOverlapMode.Overlap,
            freeformLayout = mapOf("blk" to FreeformPlacement(x = 5f, y = 6f, width = 200f, height = 100f, z = 0)),
            canvasSpec = DocCanvasSpec.legal().copy(pageCount = 2),
        )
        val snapshot = BackupSnapshot(
            notes = listOf(note),
            notebooks = emptyList(),
            tags = emptyList(),
            attachments = emptyList(),
        )

        val decoded = BackupCodec.decode(BackupCodec.encode(snapshot))
        val restored = decoded.notes.single()
        assertEquals(DocOverlapMode.Overlap, restored.overlapMode)
        assertEquals(note.freeformLayout, restored.freeformLayout)
        assertEquals(note.canvasSpec, restored.canvasSpec)
    }
}
