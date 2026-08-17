package com.norfold.app.editor.inspector

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the object inspector guarantees, asserted rather than documented.
 *
 * The model lives in the editor layer instead of beside its composable for exactly this reason: the three
 * properties that make one inspector safe to share between two editors — nothing dropped, nothing duplicated,
 * order independent of input order — are checkable on the desktop JVM, and a property that is only described
 * in a KDoc is a property that drifts.
 */
class ObjectInspectorTest {

    private fun action(
        id: String,
        role: InspectorRole,
        label: String = id,
        enabled: Boolean = true,
    ) = InspectorAction(id = id, label = label, role = role, enabled = enabled)

    @Test
    fun `head follows its own order regardless of input order`() {
        val model = ObjectInspector.build(
            title = "Image",
            actions = listOf(
                action("delete", InspectorRole.Delete),
                action("duplicate", InspectorRole.Duplicate),
            ),
        )
        assertEquals(listOf("duplicate", "delete"), model.head.map(InspectorAction::id))
    }

    @Test
    fun `delete is last in the head so the destructive row is never reached by accident`() {
        val model = ObjectInspector.build(
            "Chart",
            listOf(action("duplicate", InspectorRole.Duplicate), action("delete", InspectorRole.Delete)),
        )
        assertEquals(InspectorRole.Delete, model.head.last().role)
        assertTrue(model.head.last().role.destructive)
    }

    @Test
    fun `only delete is destructive`() {
        val destructive = InspectorRole.entries.filter(InspectorRole::destructive)
        assertEquals(listOf(InspectorRole.Delete), destructive)
    }

    @Test
    fun `every role reaches the head or exactly one slot`() {
        val actions = InspectorRole.entries.map { role -> action(role.name, role) }
        val model = ObjectInspector.build("Table", actions)
        val placed = model.allActions.map(InspectorAction::id)
        assertEquals(
            "every supplied action must be reachable",
            actions.map(InspectorAction::id).sorted(),
            placed.sorted(),
        )
        assertEquals("no action may appear twice", placed.size, placed.distinct().size)
    }

    @Test
    fun `head roles do not also appear in a slot`() {
        val model = ObjectInspector.build(
            "Shape",
            listOf(action("duplicate", InspectorRole.Duplicate), action("delete", InspectorRole.Delete)),
        )
        assertEquals(2, model.head.size)
        assertTrue("head-role actions must not be repeated in slots", model.slots.isEmpty())
    }

    @Test
    fun `head and slot orders together cover every role exactly once`() {
        val covered = ObjectInspector.HEAD_ORDER + ObjectInspector.SLOT_ORDER
        assertEquals(InspectorRole.entries.size, covered.size)
        assertEquals(InspectorRole.entries.toSet(), covered.toSet())
    }

    @Test
    fun `slots follow slot order regardless of input order`() {
        val model = ObjectInspector.build(
            "Image",
            listOf(
                action("lock", InspectorRole.Protect),
                action("split", InspectorRole.Structure),
                action("edit", InspectorRole.Edit),
                action("moveUp", InspectorRole.Arrange),
            ),
        )
        assertEquals(
            listOf(
                InspectorRole.Edit.groupLabel,
                InspectorRole.Arrange.groupLabel,
                InspectorRole.Structure.groupLabel,
                InspectorRole.Protect.groupLabel,
            ),
            model.slots.map(InspectorSlot::label),
        )
    }

    @Test
    fun `order within a slot preserves what the editor supplied`() {
        val model = ObjectInspector.build(
            "Paragraph",
            listOf(
                action("moveUp", InspectorRole.Arrange, "Move up"),
                action("moveDown", InspectorRole.Arrange, "Move down"),
                action("nest", InspectorRole.Arrange, "Nest into previous container"),
            ),
        )
        assertEquals(
            listOf("moveUp", "moveDown", "nest"),
            model.slots.single().actions.map(InspectorAction::id),
        )
    }

    @Test
    fun `empty slots are omitted rather than drawn as bare headings`() {
        val model = ObjectInspector.build("Image", listOf(action("edit", InspectorRole.Edit)))
        assertEquals(1, model.slots.size)
        assertTrue(model.slots.all { it.actions.isNotEmpty() })
    }

    @Test
    fun `an object with no actions reports itself empty instead of opening a blank sheet`() {
        val model = ObjectInspector.build("File", emptyList())
        assertTrue(model.isEmpty)
        assertTrue(model.allActions.isEmpty())
    }

    @Test
    fun `a model with any action is not empty`() {
        val model = ObjectInspector.build("File", listOf(action("delete", InspectorRole.Delete)))
        assertFalse(model.isEmpty)
    }

    @Test
    fun `edit heads the slots rather than the head, so the head does not change shape per type`() {
        val image = ObjectInspector.build(
            "Image",
            listOf(action("edit", InspectorRole.Edit), action("delete", InspectorRole.Delete)),
        )
        val table = ObjectInspector.build("Table", listOf(action("delete", InspectorRole.Delete)))
        assertEquals(
            "the head is the same shape for a type with an editor and one without",
            image.head.map(InspectorAction::role),
            table.head.map(InspectorAction::role),
        )
        assertEquals(InspectorRole.Edit.groupLabel, image.slots.first().label)
    }

    @Test
    fun `a disabled action keeps its reason so a visible row can explain itself`() {
        val model = ObjectInspector.build(
            "Paragraph",
            listOf(
                InspectorAction(
                    id = "unnest",
                    label = "Move out of container",
                    role = InspectorRole.Arrange,
                    enabled = false,
                    disabledReason = "This block is not nested",
                ),
            ),
        )
        val row = model.allActions.single()
        assertFalse(row.enabled)
        assertEquals("This block is not nested", row.disabledReason)
    }

    @Test
    fun `unclassified actions land last rather than being dropped`() {
        val model = ObjectInspector.build(
            "Embedded link",
            listOf(action("other", InspectorRole.Other), action("moveUp", InspectorRole.Arrange)),
        )
        assertEquals(InspectorRole.Other.groupLabel, model.slots.last().label)
        assertEquals(2, model.allActions.size)
    }

    @Test
    fun `the title is the object's own type, not the surface's name`() {
        assertEquals("Image", ObjectInspector.build("Image", emptyList()).title)
    }
}
