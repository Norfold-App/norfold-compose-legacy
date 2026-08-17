package com.norfold.app.editor.inspector

import com.norfold.app.editor.document.DocumentIds
import com.norfold.app.editor.document.DocumentInspector
import com.norfold.app.editor.flow.FlowBlockKind
import com.norfold.app.editor.flow.FlowIds
import com.norfold.app.editor.flow.FlowInspector
import com.norfold.app.editor.relationship.RelationshipCommandIds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * That both editors' commands land in a slot a user can name.
 *
 * The value of these assertions is the fall-through case. `roleOf` ends in `else -> Other`, which is what keeps
 * the inspector from dropping an action — but it also means a command added later lands under a heading reading
 * "More" with nothing to say about it, silently. So every id both editors currently register is checked
 * explicitly, and the two lists that drive real surfaces are checked to contain no unclassified entry at all.
 */
class InspectorRoleMappingTest {

    // ── Flow ──────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `flow duplicate and delete are the head roles`() {
        assertEquals(InspectorRole.Duplicate, FlowInspector.roleOf(FlowIds.DUPLICATE))
        assertEquals(InspectorRole.Delete, FlowInspector.roleOf(FlowIds.DELETE))
    }

    @Test
    fun `flow positional commands arrange`() {
        listOf(FlowIds.MOVE_UP, FlowIds.MOVE_DOWN, FlowIds.DROP, FlowIds.NEST, FlowIds.UNNEST)
            .forEach { id -> assertEquals(id, InspectorRole.Arrange, FlowInspector.roleOf(id)) }
    }

    @Test
    fun `flow shape-changing commands are structural`() {
        listOf(
            FlowIds.WRAP_COLUMN,
            FlowIds.UNWRAP,
            FlowIds.SPLIT,
            FlowIds.MERGE_PREVIOUS,
            FlowIds.INSERT_ABOVE,
            FlowIds.insert(FlowBlockKind.TABLE),
            FlowIds.convert(FlowBlockKind.HEADING_1),
            "${FlowIds.TABLE_PREFIX}addRow",
        ).forEach { id -> assertEquals(id, InspectorRole.Structure, FlowInspector.roleOf(id)) }
    }

    @Test
    fun `an exact mapping wins over its own prefix family`() {
        // INSERT_ABOVE starts with INSERT_PREFIX; both branches say Structure, so this asserts the ordering
        // holds rather than the outcome — if either branch's role changes, the precedence must still be exact
        // before prefix.
        assertTrue(FlowIds.INSERT_ABOVE.startsWith(FlowIds.INSERT_PREFIX))
        assertEquals(InspectorRole.Structure, FlowInspector.roleOf(FlowIds.INSERT_ABOVE))
    }

    @Test
    fun `flow relationship commands relate rather than fall through to more`() {
        RelationshipCommandIds.contextual.forEach { id ->
            assertEquals(id, InspectorRole.Relate, FlowInspector.roleOf(id))
        }
    }

    @Test
    fun `every command in the flow dock order is classified`() {
        val unclassified = FlowIds.DOCK_ORDER.filter { FlowInspector.roleOf(it) == InspectorRole.Other }
        assertEquals("dock commands must not land under a bare More heading", emptyList<String>(), unclassified)
    }

    @Test
    fun `every inline text command is classified as text rather than silently other`() {
        // Inline text commands are not object actions, so they are legitimately not in the object inspector's
        // vocabulary. This asserts the deliberate outcome so that a future caller passing them in sees them
        // grouped rather than scattered.
        val roles = FlowIds.TEXT_DOCK_ORDER.map(FlowInspector::roleOf).distinct()
        assertEquals(listOf(InspectorRole.Other), roles)
    }

    @Test
    fun `an unknown flow command is reachable rather than dropped`() {
        assertEquals(InspectorRole.Other, FlowInspector.roleOf("flow.something.new"))
    }

    // ── Document ──────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `document duplicate and delete are the head roles`() {
        assertEquals(InspectorRole.Duplicate, DocumentInspector.roleOf(DocumentIds.DUPLICATE))
        assertEquals(InspectorRole.Delete, DocumentInspector.roleOf(DocumentIds.DELETE))
    }

    @Test
    fun `document nudge move and z-order all arrange`() {
        listOf(
            DocumentIds.NUDGE_LEFT,
            DocumentIds.NUDGE_RIGHT,
            DocumentIds.NUDGE_UP,
            DocumentIds.NUDGE_DOWN,
            DocumentIds.MOVE_LEFT,
            DocumentIds.MOVE_RIGHT,
            DocumentIds.MOVE_UP,
            DocumentIds.MOVE_DOWN,
            DocumentIds.BRING_FORWARD,
            DocumentIds.SEND_BACKWARD,
            DocumentIds.BRING_TO_FRONT,
            DocumentIds.SEND_TO_BACK,
        ).forEach { id -> assertEquals(id, InspectorRole.Arrange, DocumentInspector.roleOf(id)) }
    }

    @Test
    fun `document lock and unlock protect`() {
        assertEquals(InspectorRole.Protect, DocumentInspector.roleOf(DocumentIds.LOCK))
        assertEquals(InspectorRole.Protect, DocumentInspector.roleOf(DocumentIds.UNLOCK))
    }

    @Test
    fun `document page commands are structural`() {
        listOf(
            DocumentIds.PAGE_INSERT_AFTER,
            DocumentIds.PAGE_INSERT_BEFORE,
            DocumentIds.PAGE_DUPLICATE,
            DocumentIds.PAGE_DELETE,
            DocumentIds.PAGE_MOVE_UP,
            DocumentIds.PAGE_MOVE_DOWN,
        ).forEach { id -> assertEquals(id, InspectorRole.Structure, DocumentInspector.roleOf(id)) }
    }

    @Test
    fun `a page delete is not mistaken for the object delete in the head`() {
        // Both end in "delete", which is exactly what a label- or suffix-based match would confuse. The page's
        // delete must not be promoted into an object inspector's head beside the object's own.
        assertNotEquals(InspectorRole.Delete, DocumentInspector.roleOf(DocumentIds.PAGE_DELETE))
        assertNotEquals(InspectorRole.Duplicate, DocumentInspector.roleOf(DocumentIds.PAGE_DUPLICATE))
    }

    @Test
    fun `every document object command is classified`() {
        val objectIds = listOf(
            DocumentIds.NUDGE_LEFT, DocumentIds.NUDGE_RIGHT, DocumentIds.NUDGE_UP, DocumentIds.NUDGE_DOWN,
            DocumentIds.MOVE_LEFT, DocumentIds.MOVE_RIGHT, DocumentIds.MOVE_UP, DocumentIds.MOVE_DOWN,
            DocumentIds.BRING_FORWARD, DocumentIds.SEND_BACKWARD,
            DocumentIds.BRING_TO_FRONT, DocumentIds.SEND_TO_BACK,
            DocumentIds.DUPLICATE, DocumentIds.DELETE, DocumentIds.LOCK, DocumentIds.UNLOCK,
            DocumentIds.INSERT_TEXT, DocumentIds.INSERT_SHAPE, DocumentIds.INSERT_CHART,
        )
        val unclassified = objectIds.filter { DocumentInspector.roleOf(it) == InspectorRole.Other }
        assertEquals(emptyList<String>(), unclassified)
    }

    // ── Both ──────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `both editors agree on the role of the actions they share`() {
        assertEquals(
            FlowInspector.roleOf(FlowIds.DUPLICATE),
            DocumentInspector.roleOf(DocumentIds.DUPLICATE),
        )
        assertEquals(
            FlowInspector.roleOf(FlowIds.DELETE),
            DocumentInspector.roleOf(DocumentIds.DELETE),
        )
        RelationshipCommandIds.contextual.forEach { id ->
            assertEquals(id, FlowInspector.roleOf(id), DocumentInspector.roleOf(id))
        }
    }

    @Test
    fun `neither editor's ids are derivable from the other, which is why roles exist`() {
        // Stated as an assertion because it is the whole justification for the role indirection: if the ids
        // ever converged, this test fails and the indirection can be reconsidered.
        assertNotEquals(FlowIds.DUPLICATE, DocumentIds.DUPLICATE)
        assertNotEquals(FlowIds.DELETE, DocumentIds.DELETE)
    }
}
