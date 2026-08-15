package com.norfold.app.editor.flow

/**
 * Which presentation the *one* slash session is currently wearing.
 *
 * There is deliberately only one session. The rail and the list are two views of the same
 * [FlowSlashSession] — same query, same registry, same undo composition — so switching between them
 * cannot commit, cancel or reorder anything. That is what makes "the slash vanishes" a presentation
 * change rather than a second command system.
 */
enum class FlowSlashPresentation {
    /** Icons only, pinned above the IME. What a bare `/` opens on a touch device. */
    RAIL,

    /** Named, filterable rows. What a query, a hardware keyboard or an explicit chevron opens. */
    LIST,
}

/**
 * The families the rail can drill into. Membership is derived from the command id namespace rather than
 * from [com.norfold.app.editor.command.EditorCommand.category], because the id prefixes are referenced
 * through [FlowIds] constants and so a rename breaks the build, whereas a category string is free text
 * that only a handful of commands currently set.
 */
enum class FlowCommandGroup(val slotId: String, val label: String) {
    TURN_INTO("flow.rail.group.convert", "Turn into"),
    INSERT("flow.rail.group.insert", "Insert"),
    TABLE("flow.rail.group.table", "Table"),
    ACTIONS("flow.rail.group.actions", "Actions"),
    ;

    companion object {
        /** The group [commandId] belongs to. Every command belongs to exactly one, so nothing is unreachable. */
        fun of(commandId: String): FlowCommandGroup = when {
            commandId.startsWith(FlowIds.TABLE_PREFIX) -> TABLE
            commandId.startsWith(FlowIds.CONVERT_PREFIX) -> TURN_INTO
            commandId.startsWith(FlowIds.INSERT_PREFIX) -> INSERT
            else -> ACTIONS
        }
    }
}

/**
 * One slot on the rail. A slot is either a command to run or a move within the rail; the rail never holds
 * anything that does neither, so there is no inert control to explain.
 */
sealed interface FlowRailSlot {
    val id: String
    val label: String
    val enabled: Boolean

    /** A command, run exactly as the same command would run from the list, the dock or a chord. */
    data class Command(val item: FlowCommandItem) : FlowRailSlot {
        override val id: String get() = item.id
        override val label: String get() = item.label
        override val enabled: Boolean get() = item.enabled
    }

    /** A family that swaps the rail's contents in place. [size] is how many commands are behind it. */
    data class Group(val group: FlowCommandGroup, val size: Int) : FlowRailSlot {
        override val id: String get() = group.slotId
        override val label: String get() = group.label
        override val enabled: Boolean get() = size > 0
    }

    /** Leaves an open group. Present only while one is open, so the rail is never a dead end. */
    data object Back : FlowRailSlot {
        override val id: String get() = "flow.rail.back"
        override val label: String get() = "Back"
        override val enabled: Boolean get() = true
    }
}

/**
 * The rail as the surface should draw it: which slots, which one has keyboard focus, and — when the user
 * has drilled in — which group and the rows a promoted list would show for it.
 *
 * [listItems] exists so that opening the list from inside a group keeps the group's scope instead of
 * silently widening back to everything. It is the group's commands in rail order; at the top level it is
 * empty and the list falls back to the resolver's ranked results, which is the query-driven path.
 */
data class FlowRailState(
    val presentation: FlowSlashPresentation,
    val group: FlowCommandGroup?,
    val slots: List<FlowRailSlot>,
    val focusedIndex: Int,
    val listItems: List<FlowCommandItem>,
) {
    val focused: FlowRailSlot? get() = slots.getOrNull(focusedIndex)
    val atTopLevel: Boolean get() = group == null
    val isRail: Boolean get() = presentation == FlowSlashPresentation.RAIL

    /**
     * True when a promoted list should show this group's commands instead of the resolver's ranked results.
     * Equivalent to being inside a group, but stated in terms of what the surface has to render, because
     * that is the question the surface is asking.
     */
    val scoped: Boolean get() = listItems.isNotEmpty()
}

/**
 * Builds the Flow command rail: the horizontal, IME-pinned command surface a bare `/` opens.
 *
 * Pure Kotlin, with no Compose or Android dependency, because the interesting part of this surface is a
 * behaviour table — what a typed character, a space, a backspace and an arrow each do — and that table is
 * worth asserting on the desktop JVM rather than reading off a device.
 *
 * Two structural choices:
 *
 *  - **The featured slots are a shortcut into the groups, not a separate catalogue.** A featured command
 *    also appears inside its group. The two are never visible at once (one is the top level, the other is
 *    a drilldown), and the alternative — subtracting featured commands from their groups — makes a group's
 *    contents depend on what happens to be promoted, so "Turn into" would be missing Heading 1.
 *  - **A featured command the current context hides is dropped, not disabled.** This matches the context
 *    dock: an icon-only rail has no room to say why, and a row that can explain itself is exactly what the
 *    list is for.
 */
object FlowCommandRail {

    /**
     * Commands promoted to a direct slot on the top rail, in order. Built through [FlowIds] helpers rather
     * than string literals so a renamed command fails to compile instead of quietly vanishing from the rail.
     */
    val FEATURED: List<String> = listOf(
        FlowIds.convert(FlowBlockKind.HEADING_1),
        FlowIds.convert(FlowBlockKind.HEADING_2),
        FlowIds.convert(FlowBlockKind.BULLET_LIST),
        FlowIds.convert(FlowBlockKind.TODO_LIST),
        FlowIds.convert(FlowBlockKind.QUOTE),
        FlowIds.convert(FlowBlockKind.CODE),
        FlowIds.insert(FlowBlockKind.TABLE),
        FlowIds.INSERT_IMAGE,
    )

    /** Group order after the featured slots. Insertion before conversion inverts the featured emphasis. */
    val GROUP_ORDER: List<FlowCommandGroup> = listOf(
        FlowCommandGroup.INSERT,
        FlowCommandGroup.TURN_INTO,
        FlowCommandGroup.TABLE,
        FlowCommandGroup.ACTIONS,
    )

    /**
     * The rail for [visible] — the commands the current context shows — at [group], or at the top level
     * when [group] is null.
     *
     * @param focusedIndex the hardware-arrow focus, clamped to the slots that actually exist so a context
     *   change cannot leave focus pointing past the end of the rail.
     */
    fun build(
        visible: List<FlowCommandItem>,
        presentation: FlowSlashPresentation = FlowSlashPresentation.RAIL,
        group: FlowCommandGroup? = null,
        focusedIndex: Int = 0,
    ): FlowRailState {
        val byId = visible.associateBy { it.id }
        val slots: List<FlowRailSlot>
        val listItems: List<FlowCommandItem>
        if (group == null) {
            val featured = FEATURED.mapNotNull(byId::get).map(FlowRailSlot::Command)
            val groups = GROUP_ORDER
                .map { candidate -> candidate to visible.count { FlowCommandGroup.of(it.id) == candidate } }
                .filter { (_, size) -> size > 0 }
                .map { (candidate, size) -> FlowRailSlot.Group(candidate, size) }
            slots = featured + groups
            listItems = emptyList()
        } else {
            listItems = visible.filter { FlowCommandGroup.of(it.id) == group }
            slots = listOf(FlowRailSlot.Back) + listItems.map(FlowRailSlot::Command)
        }
        return FlowRailState(
            presentation = presentation,
            group = group,
            slots = slots,
            focusedIndex = if (slots.isEmpty()) 0 else focusedIndex.coerceIn(0, slots.lastIndex),
            listItems = listItems,
        )
    }

    /**
     * Focus after walking [delta] slots. The rail wraps, because it is a closed strip the thumb scrolls
     * rather than a list with a beginning and an end, and a wrapping arrow is how a user discovers the
     * trailing groups without knowing they are there.
     */
    fun moveFocus(state: FlowRailState, delta: Int): Int {
        val size = state.slots.size
        if (size == 0) return 0
        return ((state.focusedIndex + delta) % size + size) % size
    }
}
