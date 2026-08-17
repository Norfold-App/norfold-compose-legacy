package com.norfold.app.editor.document

import com.norfold.app.editor.inspector.InspectorAction
import com.norfold.app.editor.inspector.InspectorModel
import com.norfold.app.editor.inspector.InspectorRole
import com.norfold.app.editor.inspector.ObjectInspector
import com.norfold.app.editor.relationship.RelationshipCommandIds
import com.norfold.app.domain.SceneObjectKind

/**
 * Document's half of the object inspector: which role each Document command plays.
 *
 * The mirror of [com.norfold.app.editor.flow.FlowInspector], and the reason the inspector model takes a role
 * rather than a command id. The two editors name the same action with ids neither can derive from the other —
 * `flow.duplicate` against `document.object.duplicate` — so an inspector that keyed on ids would need a table
 * of both, which is the per-editor branch the shared component exists to avoid.
 *
 * Document is the editor that gains the most here, because it had no per-object action surface at all: its
 * only inspector was `GeometryInspectorBar`'s numeric X/Y/W/H/Rotation entry, and its docks are chip rows with
 * no overflow. Every action below was already registered and reachable only by chord or by chip.
 */
object DocumentInspector {

    /** What the inspector titles itself when the selected object's kind cannot be determined. */
    const val OBJECT_TITLE = "Object"

    /**
     * What to call [kind] in a sentence a user reads.
     *
     * [SceneObjectKind]'s entry names are code identifiers — `TextFrame`, `Preserved` — and neither reads as
     * English. `Preserved` in particular would tell a user nothing: it means content from an older document
     * model that this version can place but not natively interpret, which is "Imported content" from where the
     * user is standing.
     *
     * The Flow-side counterpart is `flowObjectKindLabel`, which stays where it is: the two editors model
     * objects differently — Flow titles a `DocumentBlock`, Document a [SceneObjectKind] — so a single shared
     * function would take a union of two unrelated types purely to look shared.
     */
    fun titleFor(kind: SceneObjectKind): String = when (kind) {
        SceneObjectKind.TextFrame -> "Text"
        SceneObjectKind.Image -> "Image"
        SceneObjectKind.Shape -> "Shape"
        SceneObjectKind.Table -> "Table"
        SceneObjectKind.Chart -> "Chart"
        SceneObjectKind.Media -> "Media"
        SceneObjectKind.Preserved -> "Imported content"
    }

    /**
     * The role of the command with id [commandId].
     *
     * Nudge, move and z-order are all [InspectorRole.Arrange]: a document object floats on a page, so
     * "where it sits" includes which objects it sits in front of. Page commands are [InspectorRole.Structure]
     * — they change the container the object lives in rather than the object — and are included at all only
     * because a caller may pass the page dock through; an object inspector normally will not.
     */
    fun roleOf(commandId: String): InspectorRole = when {
        commandId == DocumentIds.DUPLICATE -> InspectorRole.Duplicate
        commandId == DocumentIds.DELETE -> InspectorRole.Delete
        commandId == DocumentIds.LOCK || commandId == DocumentIds.UNLOCK -> InspectorRole.Protect
        commandId == DocumentIds.NUDGE_LEFT ||
            commandId == DocumentIds.NUDGE_RIGHT ||
            commandId == DocumentIds.NUDGE_UP ||
            commandId == DocumentIds.NUDGE_DOWN ||
            commandId == DocumentIds.MOVE_LEFT ||
            commandId == DocumentIds.MOVE_RIGHT ||
            commandId == DocumentIds.MOVE_UP ||
            commandId == DocumentIds.MOVE_DOWN ||
            commandId == DocumentIds.BRING_FORWARD ||
            commandId == DocumentIds.SEND_BACKWARD ||
            commandId == DocumentIds.BRING_TO_FRONT ||
            commandId == DocumentIds.SEND_TO_BACK -> InspectorRole.Arrange
        commandId == DocumentIds.INSERT_TEXT ||
            commandId == DocumentIds.INSERT_SHAPE ||
            commandId == DocumentIds.INSERT_CHART ||
            commandId.startsWith(DocumentIds.PAGE_PREFIX) -> InspectorRole.Structure
        commandId in RelationshipCommandIds.contextual -> InspectorRole.Relate
        else -> InspectorRole.Other
    }

    /** [item] as an inspector row, carrying the editor's own enablement and its reason for it. */
    fun actionOf(item: DocumentCommandItem): InspectorAction = InspectorAction(
        id = item.id,
        label = item.label,
        role = roleOf(item.id),
        enabled = item.enabled,
        disabledReason = item.disabledReason,
    )

    /**
     * The inspector for the current selection.
     *
     * @param title the selected object's own type, from [com.norfold.app.domain.SceneObjectKind], or
     *   [OBJECT_TITLE] when nothing is selected.
     * @param editRoute the host's own entry into the object's editor, when the selected kind has one.
     */
    fun build(
        title: String,
        items: List<DocumentCommandItem>,
        editRoute: InspectorAction? = null,
    ): InspectorModel = ObjectInspector.build(
        title = title,
        actions = listOfNotNull(editRoute) + items.distinctBy(DocumentCommandItem::id).map(::actionOf),
    )
}
