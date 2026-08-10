package com.norfold.app.collaboration

import com.norfold.app.collaboration.protocol.SemanticBatch
import com.norfold.app.collaboration.protocol.SemanticDecodeResult
import com.norfold.app.collaboration.protocol.SemanticMutation
import com.norfold.app.collaboration.protocol.SemanticOperationCodec
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Test

class SemanticOperationCodecTest {
    @Test fun `canonical encoding ignores object insertion order`() {
        val a = batch(buildJsonObject { put("z", 1); put("a", 2) })
        val b = batch(buildJsonObject { put("a", 2); put("z", 1) })
        assertArrayEquals(SemanticOperationCodec.encode(a), SemanticOperationCodec.encode(b))
        assertEquals(SemanticOperationCodec.hash(a), SemanticOperationCodec.hash(b))
    }

    @Test fun `supported operation round trips with stable ids`() {
        val source = batch(buildJsonObject { put("itemId", "block-7") })
        val decoded = SemanticOperationCodec.decode(SemanticOperationCodec.encode(source))
        assertTrue(decoded is SemanticDecodeResult.Supported)
        assertEquals(source, (decoded as SemanticDecodeResult.Supported).batch)
    }

    @Test fun `future semantic operation remains preserved`() {
        val future = """{"atomic":true,"batchId":"b","commandId":"c","operations":[{"version":99,"domain":"flow","action":"future.action","targetId":"t","payload":{"opaque":{"x":1}}}]}"""
        val result = SemanticOperationCodec.decode(future.toByteArray())
        assertTrue(result is SemanticDecodeResult.Future)
        assertTrue((result as SemanticDecodeResult.Future).raw.toString().contains("opaque"))
    }

    @Test fun `local preview and guide domains cannot enter durable batch`() {
        listOf("preview", "guide", "recovery", "history-ui").forEach { domain ->
            assertThrows(IllegalArgumentException::class.java) {
                SemanticBatch("b", "c", listOf(SemanticMutation(domain = domain, action = "state.set", targetId = "t")))
            }
        }
    }

    @Test fun `oversized semantic payload is rejected before transport`() {
        val huge = batch(JsonObject(mapOf("value" to JsonPrimitive("x".repeat(70_000)))))
        assertThrows(IllegalArgumentException::class.java) { SemanticOperationCodec.encode(huge) }
    }

    @Test fun `hostile nested payload is rejected before recursive decoding`() {
        val nested = "[".repeat(80) + "0" + "]".repeat(80)
        assertEquals(
            SemanticDecodeResult.Rejected("payload_structure_too_deep"),
            SemanticOperationCodec.decode(nested.toByteArray()),
        )
    }

    private fun batch(payload: JsonObject) = SemanticBatch(
        batchId = "batch-1", commandId = "command-1",
        operations = listOf(SemanticMutation(domain = "flow", action = "block.replace", targetId = "artifact-1", payload = payload)),
    )
}
