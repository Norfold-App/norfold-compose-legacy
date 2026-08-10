package com.norfold.app.collaboration

import com.norfold.app.collaboration.protocol.SemanticMutation
import com.norfold.app.collaboration.protocol.SemanticProjectionValidator
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SemanticProjectionValidatorTest {
    @Test fun `structural parent cycle is rejected before domain mutation`() {
        val operations = listOf(
            move("a", "b"),
            move("b", "c"),
            move("c", "a"),
        )
        assertEquals("structural_parent_cycle", SemanticProjectionValidator.rejectReason(operations))
    }

    @Test fun `spreadsheet calculated result is never collaborative truth`() {
        val mutation = SemanticMutation(
            domain = "spreadsheet", action = "cell.write", targetId = "book",
            payload = buildJsonObject { put("cellId", "cell"); put("result", "42") },
        )
        assertEquals("calculated_spreadsheet_state_forbidden", SemanticProjectionValidator.rejectReason(listOf(mutation)))
    }

    @Test fun `stable structural movement without a cycle is accepted`() {
        assertNull(SemanticProjectionValidator.rejectReason(listOf(move("a", "root"), move("b", "a"))))
    }

    private fun move(item: String, parent: String) = SemanticMutation(
        domain = "flow", action = "block.reparent", targetId = "document",
        payload = buildJsonObject { put("itemId", item); put("parentId", parent) },
    )
}
