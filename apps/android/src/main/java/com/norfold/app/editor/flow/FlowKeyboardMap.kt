package com.norfold.app.editor.flow

/**
 * A physical chord, normalized away from any windowing toolkit (roadmap Phase 1 deliverable 6).
 *
 * [key] is a lowercase token — a single character (`"d"`, `"1"`) or a named key (`"tab"`, `"arrowup"`,
 * `"backspace"`). Keeping it a plain string is what lets the whole keyboard layer stay pure-JVM and be tested
 * without an Android `KeyEvent`; the Compose host does one translation at the edge.
 */
data class FlowShortcut(
    val key: String,
    val ctrl: Boolean = false,
    val shift: Boolean = false,
    val alt: Boolean = false,
) {
    /** Display form for menus, tooltips and the accessibility tree (`"Ctrl+Shift+↑"`). */
    fun display(): String = buildString {
        if (ctrl) append("Ctrl+")
        if (alt) append("Alt+")
        if (shift) append("Shift+")
        append(DISPLAY_NAMES[key] ?: key.uppercase())
    }

    companion object {
        private val DISPLAY_NAMES = mapOf(
            "arrowup" to "↑",
            "arrowdown" to "↓",
            "tab" to "Tab",
            "backspace" to "Backspace",
            "enter" to "Enter",
        )
    }
}

/**
 * The Flow keyboard map: chord → **command id** (roadmap Phase 1 deliverable 6).
 *
 * The values are ids and nothing else. That is the whole point: a shortcut cannot carry its own
 * implementation, so a keyboard-triggered edit necessarily runs the same eligibility check, the same
 * transaction and the same post-command selection as the identical entry in the `+` palette, the slash menu
 * or the accessibility action list. If a command is disabled with a reason, the chord is inert for that same
 * reason rather than quietly doing something slightly different.
 *
 * Undo/redo are deliberately absent: they are History Engine operations, not registry commands, and
 * [FlowHostRuntime] routes them separately.
 *
 * Chord choices avoid the text-editing chords a focused field owns (Ctrl+C/V/X/A, plain Enter), so nothing
 * here fights the IME.
 */
object FlowKeyboardMap {

    /** Undo — handled by the runtime against the canonical history, not by a registry command. */
    val UNDO = FlowShortcut("z", ctrl = true)

    /** Redo. Both the Ctrl+Shift+Z and Ctrl+Y conventions are accepted. */
    val REDO = FlowShortcut("z", ctrl = true, shift = true)
    val REDO_ALT = FlowShortcut("y", ctrl = true)

    /** Enters multi-select from the keyboard — the discoverability counterpart of the dock's Select button. */
    val SELECT_MODE = FlowShortcut("l", ctrl = true, shift = true)

    val bindings: Map<FlowShortcut, String> = buildMap {
        // Conversion: Ctrl+Alt+<n> is the near-universal "make this a heading" chord.
        put(FlowShortcut("0", ctrl = true, alt = true), convertId(FlowBlockKind.PARAGRAPH))
        listOf(
            FlowBlockKind.HEADING_1,
            FlowBlockKind.HEADING_2,
            FlowBlockKind.HEADING_3,
            FlowBlockKind.HEADING_4,
            FlowBlockKind.HEADING_5,
            FlowBlockKind.HEADING_6,
        ).forEachIndexed { index, kind ->
            put(FlowShortcut("${index + 1}", ctrl = true, alt = true), convertId(kind))
        }
        put(FlowShortcut("8", ctrl = true, shift = true), convertId(FlowBlockKind.BULLET_LIST))
        put(FlowShortcut("7", ctrl = true, shift = true), convertId(FlowBlockKind.NUMBERED_LIST))
        put(FlowShortcut("9", ctrl = true, shift = true), convertId(FlowBlockKind.TODO_LIST))
        put(FlowShortcut("q", ctrl = true, shift = true), convertId(FlowBlockKind.QUOTE))
        put(FlowShortcut("c", ctrl = true, alt = true), convertId(FlowBlockKind.CODE))

        // Structure.
        put(FlowShortcut("d", ctrl = true), FlowIds.DUPLICATE)
        put(FlowShortcut("backspace", ctrl = true, shift = true), FlowIds.DELETE)
        put(FlowShortcut("arrowup", ctrl = true, shift = true), FlowIds.MOVE_UP)
        put(FlowShortcut("arrowdown", ctrl = true, shift = true), FlowIds.MOVE_DOWN)
        put(FlowShortcut("tab", alt = true), FlowIds.NEST)
        put(FlowShortcut("tab", alt = true, shift = true), FlowIds.UNNEST)
        put(FlowShortcut("g", ctrl = true), FlowIds.WRAP_COLUMN)
        put(FlowShortcut("g", ctrl = true, shift = true), FlowIds.UNWRAP)
        put(FlowShortcut("enter", ctrl = true), FlowIds.SPLIT)
        put(FlowShortcut("backspace", alt = true), FlowIds.MERGE_PREVIOUS)

        // Insertion: one chord for the most common block, plus the palette itself.
        put(FlowShortcut("enter", ctrl = true, shift = true), insertId(FlowBlockKind.PARAGRAPH))
    }

    /** Command id bound to [shortcut], or null when the chord is unbound. */
    fun commandFor(shortcut: FlowShortcut): String? = bindings[shortcut]

    /** The chord that reaches [commandId], for display in menus and the accessibility tree. */
    fun shortcutFor(commandId: String): FlowShortcut? =
        bindings.entries.firstOrNull { it.value == commandId }?.key

    private fun convertId(kind: FlowBlockKind) = "flow.convert.${kind.commandKey()}"
    private fun insertId(kind: FlowBlockKind) = "flow.insert.${kind.commandKey()}"
}

/**
 * Ids of the commands FlowHost's own surfaces reference by name (dock order, keyboard map, table hooks).
 *
 * Referencing ids through constants rather than string literals scattered across the host means a renamed
 * command breaks the build instead of silently producing an empty dock row.
 */
object FlowIds {
    const val DUPLICATE = "flow.duplicate"
    const val DELETE = "flow.delete"
    const val MOVE_UP = "flow.move.up"
    const val MOVE_DOWN = "flow.move.down"
    const val DROP = "flow.move.drop"
    const val NEST = "flow.nest"
    const val UNNEST = "flow.unnest"
    const val WRAP_COLUMN = "flow.wrap.column"
    const val UNWRAP = "flow.unwrap"
    const val SPLIT = "flow.split"
    const val MERGE_PREVIOUS = "flow.merge.previous"
    const val INSERT_ABOVE = "flow.insert.above"

    /** Prefix of every insertion command — the `+` palette is exactly this family. */
    const val INSERT_PREFIX = "flow.insert."

    /** Prefix of every conversion command — the "Turn into" child surface is exactly this family. */
    const val CONVERT_PREFIX = "flow.convert."

    /** Prefix of every table structure hook. */
    const val TABLE_PREFIX = "flow.table."

    fun insert(kind: FlowBlockKind) = "$INSERT_PREFIX${kind.commandKey()}"
    fun convert(kind: FlowBlockKind) = "$CONVERT_PREFIX${kind.commandKey()}"

    /**
     * The context dock's order: the structural actions worth a permanent slot, most-used first. Entries the
     * current selection hides are simply not rendered — the dock never shows an inert row it cannot explain.
     */
    val DOCK_ORDER: List<String> = listOf(
        MOVE_UP,
        MOVE_DOWN,
        DUPLICATE,
        NEST,
        UNNEST,
        WRAP_COLUMN,
        UNWRAP,
        SPLIT,
        MERGE_PREVIOUS,
        DELETE,
    )
}
