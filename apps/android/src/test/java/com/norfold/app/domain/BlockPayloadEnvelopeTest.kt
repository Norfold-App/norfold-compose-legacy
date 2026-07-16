package com.norfold.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BlockPayloadEnvelopeTest {
    @Test
    fun `stored payload is a versioned envelope and round trips`() {
        val block = HeadingBlock(level = 2, content = listOf(InlineText("Title")))
        val payload = BlockDocumentJson.encodeBlock(block)

        assertTrue(payload.startsWith("{\"v\":${BlockDocumentJson.SCHEMA_VERSION},"))
        assertEquals(block, BlockDocumentJson.decodeBlock(payload))
    }

    @Test
    fun `legacy bare block payload still decodes`() {
        val block = ParagraphBlock(content = listOf(InlineText("old row")))
        val legacyPayload = BlockDocumentJson.format.encodeToString(DocumentBlock.serializer(), block)

        assertEquals(block, BlockDocumentJson.decodeBlock(legacyPayload))
    }

    @Test
    fun `unknown kind is preserved verbatim through load and save`() {
        val foreign = """{"v":1,"block":{"kind":"hologram","id":"abc-123","beam":42}}"""

        val decoded = BlockDocumentJson.decodeBlock(foreign)

        assertTrue(decoded is UnknownBlock)
        assertEquals("abc-123", decoded.id)
        assertEquals(foreign, BlockDocumentJson.encodeBlock(decoded))
    }

    @Test
    fun `newer envelope version is preserved verbatim not decoded`() {
        val future = """{"v":${BlockDocumentJson.SCHEMA_VERSION + 1},"block":{"kind":"paragraph","id":"p1","content":[]}}"""

        val decoded = BlockDocumentJson.decodeBlock(future)

        assertTrue(decoded is UnknownBlock)
        assertEquals("p1", decoded.id)
        assertEquals(future, BlockDocumentJson.encodeBlock(decoded))
    }

    @Test
    fun `corrupt payload becomes an unknown block instead of vanishing`() {
        val decoded = BlockDocumentJson.decodeBlock("not json at all {")

        assertTrue(decoded is UnknownBlock)
        assertEquals("not json at all {", BlockDocumentJson.encodeBlock(decoded))
    }

    @Test
    fun `unknown block payload with extra fields on known kind is preserved`() {
        // A future client added a field to paragraph; strict decoding must not drop the block.
        val future = """{"v":1,"block":{"kind":"paragraph","id":"p2","content":[],"gravity":"high"}}"""

        val decoded = BlockDocumentJson.decodeBlock(future)

        assertTrue(decoded is UnknownBlock)
        assertEquals(future, BlockDocumentJson.encodeBlock(decoded))
    }

    @Test
    fun `persisted unknown wrapper recovers once the kind is understood`() {
        // Simulates a backup written while the block was unknown: the wrapper itself got persisted,
        // with a payload this build actually understands inside.
        val real = MathBlock(tex = "e = mc^2")
        val wrapper = BlockDocumentJson.format.encodeToString(
            DocumentBlock.serializer(),
            UnknownBlock(id = real.id, rawJson = BlockDocumentJson.encodeBlock(real)),
        )

        assertEquals(real, BlockDocumentJson.decodeBlock(wrapper))
    }

    @Test
    fun `reidentify rewrites the id inside the preserved payload`() {
        val foreign = """{"v":1,"block":{"kind":"hologram","id":"abc-123","beam":42}}"""
        val decoded = BlockDocumentJson.decodeBlock(foreign) as UnknownBlock

        val copy = BlockDocumentJson.reidentify(decoded, "new-id")

        assertEquals("new-id", copy.id)
        assertNotEquals(decoded.rawJson, copy.rawJson)
        val redecoded = BlockDocumentJson.decodeBlock(copy.rawJson)
        assertEquals("new-id", redecoded.id)
    }

    @Test
    fun `unknown blocks survive whole document normalization`() {
        val unknown = UnknownBlock(id = "u1", rawJson = """{"kind":"hologram","id":"u1"}""")
        val document = BlockDocument(listOf(ParagraphBlock(), unknown)).normalized()

        assertTrue(document.blocks.contains(unknown))
    }
}
