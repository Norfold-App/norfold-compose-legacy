package com.norfold.app.editor.diagram

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagramEditDocumentTest {
    @Test
    fun `geometry round trip is bounded and ignores removed nodes`() {
        val payload = DiagramEditDocumentJson.encode(
            DiagramEditDocument(nodePositions = mapOf(
                "kept" to DiagramNodePosition(-2f, 4f),
                "removed" to DiagramNodePosition(.2f, .3f),
            )),
        )

        val restored = DiagramEditDocumentJson.decodeOrDefault(payload).normalized(setOf("kept"))

        assertEquals(DiagramNodePosition(0f, 1f), restored.nodePositions.getValue("kept"))
        assertFalse("removed" in restored.nodePositions)
    }

    @Test
    fun `invalid sidecar falls back without touching Mermaid source`() {
        val restored = DiagramEditDocumentJson.decodeOrDefault("not json")
        assertTrue(restored.nodePositions.isEmpty())
    }
}
