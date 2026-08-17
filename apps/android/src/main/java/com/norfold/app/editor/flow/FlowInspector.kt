package com.norfold.app.editor.flow

import com.norfold.app.editor.inspector.InspectorAction
import com.norfold.app.editor.inspector.InspectorModel
import com.norfold.app.editor.inspector.InspectorRole
import com.norfold.app.editor.inspector.ObjectInspector
import com.norfold.app.editor.relationship.RelationshipCommandIds

/**
 * Flow's half of the object inspector: which role each Flow command plays.
 *
 * Kept in the `flow` package rather than in `inspector`, because the inspector must not know that Flow exists
 * — otherwise the shared component grows a branch per editor, which is the shape this work replaces. The
 * dependency points one way: Flow knows about roles, roles know nothing about Flow.
 *
 * The mapping is keyed on [FlowIds] constants, so renaming a command fails to compile rather than quietly
 * dropping it out of its slot. That is the same reasoning [FlowCommandRail] gives for deriving groups from the
 * id namespace instead of from the free-text `category` field, and it is the direct fix for what the dock was
 * doing: `allCommands.firstOrNull { it.label.contains("Move", ignoreCase = true) }` matched the first entry of
 * [FlowIds.DOCK_ORDER], which is `MOVE_UP` — so the dock's drag-icon "Move" button ran *move up*.
 */
object FlowInspector {

    /** What the inspector titles itself for a text block with no object frame. */
    const val BLOCK_TITLE = "Block"

    /**
     * The role of the command with id [commandId].
     *
     * Prefix families are matched after the exact ids, so a specific mapping always wins over its namespace.
     * Conversion and insertion are [InspectorRole.Structure] and table hooks are structural too — all three
     * change what the block *is* rather than where it sits.
     */
    fun roleOf(commandId: String): InspectorRole = when {
        commandId == FlowIds.DUPLICATE -> InspectorRole.Duplicate
        commandId == FlowIds.DELETE -> InspectorRole.Delete
        commandId == FlowIds.MOVE_UP ||
            commandId == FlowIds.MOVE_DOWN ||
            commandId == FlowIds.DROP ||
            commandId == FlowIds.NEST ||
            commandId == FlowIds.UNNEST -> InspectorRole.Arrange
        commandId == FlowIds.WRAP_COLUMN ||
            commandId == FlowIds.UNWRAP ||
            commandId == FlowIds.SPLIT ||
            commandId == FlowIds.MERGE_PREVIOUS ||
            commandId == FlowIds.INSERT_ABOVE -> InspectorRole.Structure
        commandId.startsWith(FlowIds.CONVERT_PREFIX) ||
            commandId.startsWith(FlowIds.INSERT_PREFIX) ||
            commandId.startsWith(FlowIds.TABLE_PREFIX) -> InspectorRole.Structure
        commandId in RelationshipCommandIds.contextual -> InspectorRole.Relate
        else -> InspectorRole.Other
    }

    /** [item] as an inspector row, carrying the editor's own enablement and its reason for it. */
    fun actionOf(item: FlowCommandItem): InspectorAction = InspectorAction(
        id = item.id,
        label = item.label,
        role = roleOf(item.id),
        enabled = item.enabled,
        disabledReason = item.disabledReason,
    )

    /**
     * The inspector for the current selection.
     *
     * @param title the selected object's own type, from `flowObjectKindLabel`, or [BLOCK_TITLE] when the
     *   selection is not a framed object. This is what replaces the literal `"More actions"` heading: a user
     *   who pressed More already knows they pressed More, and the type is the one thing the sheet can tell
     *   them that they may not have been sure of.
     * @param editRoute the host's own "Edit image" / "Edit chart" entry, when the selected type has an editor.
     *   Not a command — it needs an Android route rather than a registry entry — which is why
     *   [InspectorAction] is a value the adapter fills rather than a command item passed straight through.
     * @param items every command the selection currently offers. Duplicates by id are collapsed, because the
     *   dock row and the More list overlap and the caller should not have to know where.
     */
    fun build(
        title: String,
        items: List<FlowCommandItem>,
        editRoute: InspectorAction? = null,
    ): InspectorModel = ObjectInspector.build(
        title = title,
        actions = listOfNotNull(editRoute) + items.distinctBy(FlowCommandItem::id).map(::actionOf),
    )
}
