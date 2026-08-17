package com.norfold.app.editor.inspector

/**
 * What an action *does to a selected object*, independent of which editor supplied it or what it is called.
 *
 * This exists because the two editors name the same action differently. Duplicating a block is
 * `flow.duplicate`; duplicating a scene object is `document.object.duplicate`. Neither id is derivable from
 * the other, and both labels are free text an author may reword. So the inspector cannot key on ids and must
 * not key on labels — the adapter states the role, and everything downstream reads the role.
 *
 * The alternative the tree already tried is instructive. `FlowContextDock` looked its rows up with
 * `allCommands.firstOrNull { it.label.contains("Move", ignoreCase = true) }`, and because the dock is built in
 * `FlowIds.DOCK_ORDER` — which begins `MOVE_UP, MOVE_DOWN, …` — the button captioned "Move", carrying a
 * drag-to-reposition icon, ran *move up*. A substring match against user-facing prose cannot be relied on to
 * pick a command, and it fails silently: nothing crashes, the wrong thing simply happens.
 *
 * @property groupLabel what a slot of this role calls itself, for the roles that form slots. [Duplicate] and
 *   [Delete] never head a slot — they are the shared head — so their value is unused and deliberately blunt.
 * @property destructive whether the surface should draw this in the error colour. Stated here rather than at
 *   the call site because `FlowDockButton` decided it with `label.equals("Delete", ignoreCase = true)`, which
 *   is the same category of mistake as looking a command up by its caption: a reworded label silently stops
 *   being red, and a differently-worded destructive action never starts.
 */
enum class InspectorRole(val groupLabel: String, val destructive: Boolean = false) {

    /**
     * Opens the object's own editor. The one role that is not registry-backed everywhere — Flow's "Edit
     * image" and "Edit chart" are host navigation callbacks rather than commands — which is why
     * [InspectorAction] is a plain value the adapter fills rather than a command item passed straight through.
     */
    Edit("Edit"),

    /** Copy the object as it stands. In the head. */
    Duplicate("Duplicate"),

    /** Remove the object. In the head, last, and red. */
    Delete("Delete", destructive = true),

    /** Where the object sits: move, nest, unnest, drop, nudge, z-order. */
    Arrange("Arrange"),

    /** What the object *is*: split, merge, wrap, unwrap, convert, table structure. */
    Structure("Structure"),

    /** Whether the object can be changed at all: lock, unlock. */
    Protect("Protect"),

    /**
     * What the object relates to: comments, review, connections to other objects.
     *
     * Its own role rather than part of [Other] because both editors register the same four relationship
     * commands, so it is shared vocabulary rather than one editor's idiom — and because "Add comment" under a
     * heading reading "More" tells the user nothing about what the group contains.
     */
    Relate("Comments & connections"),

    /** Anything the roles above do not describe, so no supplied action is ever dropped. */
    Other("More"),
}

/**
 * One row the inspector can draw.
 *
 * A value rather than a `FlowCommandItem` or a `DocumentCommandItem`, for two reasons. The two item types
 * are near-identical but not identical — Flow's carries a keyboard shortcut and an accessibility label,
 * Document's does not — so neither can be the shared currency without one editor importing the other's
 * runtime. And Flow's "Edit image" is not a command at all, so a signature that demanded a command item
 * could not express the row the reference study found at the top of every inspector in every product.
 *
 * @param id whatever the adapter uses to route the action back — a command id, or a host route key. The
 *   inspector treats it as opaque and only requires that it be unique within one model, since it is the
 *   list key the surface draws with.
 * @param disabledReason why [enabled] is false, when the editor knows. Shown under the row, so a visible
 *   disabled row explains itself instead of reading as a dead tap.
 */
data class InspectorAction(
    val id: String,
    val label: String,
    val role: InspectorRole,
    val enabled: Boolean = true,
    val disabledReason: String? = null,
)

/** A named group of same-role rows, below the head. */
data class InspectorSlot(val label: String, val actions: List<InspectorAction>)

/**
 * The inspector as a surface should draw it: what the object is called, the rows that are the same for every
 * object, and the rows that are not.
 *
 * @param title the object's own type — "Image", "Chart", "Text frame". The reference study's clearest finding
 *   about this surface: Canva heads its object sheet with `Text`/`Graphic`/`Line`, Notion and AppFlowy with
 *   the block type. Flow's equivalent sheet was headed with the literal `"More actions"`, which tells the
 *   user nothing they did not already know from having pressed More.
 */
data class InspectorModel(
    val title: String,
    val head: List<InspectorAction>,
    val slots: List<InspectorSlot>,
) {
    /** True when the object supplied nothing at all, so a caller can decline to open an empty sheet. */
    val isEmpty: Boolean get() = head.isEmpty() && slots.isEmpty()

    /** Every row, head first, in the order they are drawn. Convenient for tests and for accessibility order. */
    val allActions: List<InspectorAction> get() = head + slots.flatMap(InspectorSlot::actions)
}

/**
 * Builds the one object inspector both editors present.
 *
 * The finding this implements, from the mobile-editor reference study: *the primitive to build is not
 * per-editor inspectors; it is one inspector component with a shared action head and a typed slot list.*
 * Building Flow's and Document's separately would be building the same thing twice and letting them drift —
 * which is observably what happened to the dock, where the `ImageBlock` and `ChartBlock` branches were
 * near-duplicate transcriptions of one row.
 *
 * Pure Kotlin with no Compose or Android dependency, following [com.norfold.app.editor.flow.FlowCommandRail]
 * and [com.norfold.app.domain.style.ChromeGeometry]: the interesting part of this surface is an ordering
 * table, and an ordering table is worth failing a desktop unit test over rather than reading off a device.
 */
object ObjectInspector {

    /**
     * The head, in the order it is always drawn.
     *
     * Two rows, which is thinner than the reference products — Canva leads with
     * `Copy · Copy style · Paste · Duplicate · Delete` — and thin *honestly*: neither editor has a copy, cut,
     * paste or copy-style command to offer, so a head listing them would be five rows of which three did
     * nothing. The head arranges what exists. When those commands arrive they are added to this list and
     * every inspector in both editors gains them in the same position, with no call site touched.
     *
     * [InspectorRole.Edit] is deliberately *not* here even though Flow's dock leads with "Edit image". The
     * property that makes a head a head is that it is identical for every type; an image has an editor and a
     * table does not, so including Edit would make the head change shape between objects, which is precisely
     * the drift the head exists to prevent. Edit leads the slots instead.
     */
    val HEAD_ORDER: List<InspectorRole> = listOf(InspectorRole.Duplicate, InspectorRole.Delete)

    /**
     * Slot order below the head.
     *
     * Edit first because it is usually why the sheet was opened for a media object; [InspectorRole.Arrange]
     * next because it is the most common structural intent; [InspectorRole.Protect] late because locking is
     * rare and irreversible-feeling; [InspectorRole.Other] last so an unclassified action is reachable
     * without displacing a classified one.
     */
    val SLOT_ORDER: List<InspectorRole> = listOf(
        InspectorRole.Edit,
        InspectorRole.Arrange,
        InspectorRole.Structure,
        InspectorRole.Relate,
        InspectorRole.Protect,
        InspectorRole.Other,
    )

    /**
     * The inspector for an object titled [title] offering [actions].
     *
     * Three guarantees, each asserted in `ObjectInspectorTest`:
     *
     *  - **Nothing supplied is dropped.** Every action reaches either the head or exactly one slot, because
     *    [InspectorRole] is total over itself and [SLOT_ORDER] covers every role not in [HEAD_ORDER]. A sheet
     *    that silently swallowed an action would be worse than the flat list it replaces.
     *  - **Nothing appears twice.** A head action is removed from its slot rather than repeated, so Delete is
     *    not offered in two places with two different pieces of surrounding context.
     *  - **Order does not depend on the input order.** The head follows [HEAD_ORDER] and the slots follow
     *    [SLOT_ORDER] regardless of how the adapter happened to accumulate the list, so two object types
     *    cannot present the same actions in two different arrangements.
     *
     * Within a slot the adapter's order is preserved: that ordering is real information the editor holds —
     * `FlowIds.DOCK_ORDER` is curated, and "Move up" before "Move down" is not alphabetical — and re-sorting
     * it here would be this file inventing a second opinion about a question the editor already answered.
     *
     * Empty slots are omitted rather than rendered as headings with nothing under them.
     */
    fun build(title: String, actions: List<InspectorAction>): InspectorModel {
        val byRole = actions.groupBy(InspectorAction::role)
        val head = HEAD_ORDER.flatMap { role -> byRole[role].orEmpty() }
        val slots = SLOT_ORDER.mapNotNull { role ->
            byRole[role].orEmpty()
                .takeIf(List<InspectorAction>::isNotEmpty)
                ?.let { InspectorSlot(role.groupLabel, it) }
        }
        return InspectorModel(title = title, head = head, slots = slots)
    }
}
