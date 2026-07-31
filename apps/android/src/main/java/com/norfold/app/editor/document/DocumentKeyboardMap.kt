package com.norfold.app.editor.document

import com.norfold.app.editor.flow.FlowShortcut

/**
 * The DocumentHost keyboard/mouse map: chord → **command id** (roadmap Phase 2 §15).
 *
 * The values are ids, exactly as in [com.norfold.app.editor.flow.FlowKeyboardMap], and for the same reason:
 * a chord cannot carry its own implementation, so a keyboard nudge runs the same eligibility check, the same
 * canonical transaction and the same post-command selection as the identical action from the dock or from
 * TalkBack. There is deliberately **no** keyboard-only behaviour anywhere in this file — if a chord could do
 * something no other surface could, the keyboard would have become a second editor.
 *
 * [FlowShortcut] is reused rather than cloned. It is a toolkit-independent chord with no Flow semantics in
 * it, and two identical chord types would mean two translation layers that could disagree about what
 * Ctrl+Shift+Z is.
 *
 * Undo/redo and Escape are absent from [bindings] on purpose: they are History Engine and surface-hierarchy
 * operations rather than registry commands, and the runtime routes them separately.
 */
object DocumentKeyboardMap {

    /** Undo — the canonical history, not a registry command. */
    val UNDO = FlowShortcut("z", ctrl = true)

    /** Redo. Both the Ctrl+Shift+Z and Ctrl+Y conventions are accepted, because both are muscle memory. */
    val REDO = FlowShortcut("z", ctrl = true, shift = true)
    val REDO_ALT = FlowShortcut("y", ctrl = true)

    /** Escape: one level of the back hierarchy — surface → multi-select → clear selection. */
    val ESCAPE = FlowShortcut("escape")

    /** Enters object multi-select from the keyboard, mirroring the dock's Select control. */
    val SELECT_MODE = FlowShortcut("l", ctrl = true, shift = true)

    /** Selects every object on the active page. */
    val SELECT_ALL = FlowShortcut("a", ctrl = true)

    val bindings: Map<FlowShortcut, String> = buildMap {
        // Arrows nudge by one point; Shift makes the step coarse. Both land on the *same* registered
        // commands the accessibility tree uses, so "moved with the keyboard" and "moved with TalkBack" are
        // one entry in history rather than two implementations of nudging.
        put(FlowShortcut("arrowleft"), DocumentIds.NUDGE_LEFT)
        put(FlowShortcut("arrowright"), DocumentIds.NUDGE_RIGHT)
        put(FlowShortcut("arrowup"), DocumentIds.NUDGE_UP)
        put(FlowShortcut("arrowdown"), DocumentIds.NUDGE_DOWN)
        put(FlowShortcut("arrowleft", shift = true), DocumentIds.MOVE_LEFT)
        put(FlowShortcut("arrowright", shift = true), DocumentIds.MOVE_RIGHT)
        put(FlowShortcut("arrowup", shift = true), DocumentIds.MOVE_UP)
        put(FlowShortcut("arrowdown", shift = true), DocumentIds.MOVE_DOWN)

        // Lifecycle.
        put(FlowShortcut("d", ctrl = true), DocumentIds.DUPLICATE)
        put(FlowShortcut("delete"), DocumentIds.DELETE)
        put(FlowShortcut("backspace"), DocumentIds.DELETE)

        // Layering: the near-universal bracket chords.
        put(FlowShortcut("]", ctrl = true), DocumentIds.BRING_FORWARD)
        put(FlowShortcut("[", ctrl = true), DocumentIds.SEND_BACKWARD)
        put(FlowShortcut("]", ctrl = true, shift = true), DocumentIds.BRING_TO_FRONT)
        put(FlowShortcut("[", ctrl = true, shift = true), DocumentIds.SEND_TO_BACK)

        // Protection.
        put(FlowShortcut("l", ctrl = true), DocumentIds.LOCK)
        put(FlowShortcut("l", ctrl = true, alt = true), DocumentIds.UNLOCK)

        // Insertion.
        put(FlowShortcut("t", ctrl = true, shift = true), DocumentIds.INSERT_TEXT)
        put(FlowShortcut("r", ctrl = true, shift = true), DocumentIds.INSERT_SHAPE)
    }

    fun commandFor(shortcut: FlowShortcut): String? = bindings[shortcut]

    /** The chord that reaches [commandId], for display in menus and the accessibility tree. */
    fun shortcutFor(commandId: String): FlowShortcut? =
        bindings.entries.firstOrNull { it.value == commandId }?.key
}

/**
 * Ids of the commands DocumentHost's own surfaces reference by name.
 *
 * Constants rather than string literals scattered across the host and the keyboard map, so a renamed command
 * breaks the build instead of silently producing an inert chord and an empty dock slot.
 */
object DocumentIds {
    const val NUDGE_LEFT = "document.object.nudgeLeft"
    const val NUDGE_RIGHT = "document.object.nudgeRight"
    const val NUDGE_UP = "document.object.nudgeUp"
    const val NUDGE_DOWN = "document.object.nudgeDown"
    const val MOVE_LEFT = "document.object.moveLeft"
    const val MOVE_RIGHT = "document.object.moveRight"
    const val MOVE_UP = "document.object.moveUp"
    const val MOVE_DOWN = "document.object.moveDown"

    const val BRING_FORWARD = "document.object.bringForward"
    const val SEND_BACKWARD = "document.object.sendBackward"
    const val BRING_TO_FRONT = "document.object.bringToFront"
    const val SEND_TO_BACK = "document.object.sendToBack"

    const val DUPLICATE = "document.object.duplicate"
    const val DELETE = "document.object.delete"
    const val LOCK = "document.object.lock"
    const val UNLOCK = "document.object.unlock"

    const val INSERT_TEXT = "document.object.insertText"
    const val INSERT_SHAPE = "document.object.insertShape"

    const val PAGE_INSERT_AFTER = "document.page.insertAfter"
    const val PAGE_INSERT_BEFORE = "document.page.insertBefore"
    const val PAGE_DUPLICATE = "document.page.duplicate"
    const val PAGE_DELETE = "document.page.delete"
    const val PAGE_MOVE_UP = "document.page.moveUp"
    const val PAGE_MOVE_DOWN = "document.page.moveDown"

    /** Prefix of every page-setup control — the Page Control surface is exactly this family. */
    const val PAGE_PREFIX = "document.page."
}

/** Ids of DocumentHost's own tool surfaces, and the retarget domain they share. */
object DocumentSurfaces {
    const val DOMAIN = "document"

    /** Page setup at a chosen scope. */
    const val PAGE_CONTROL = "document.surface.pageControl"

    /** Exact X/Y/W/H/Rotation entry for the primary selected object. */
    const val GEOMETRY_INSPECTOR = "document.surface.geometry"

    /** The page list, where pages are selected for the "Selected pages" scope and reordered. */
    const val PAGE_LIST = "document.surface.pageList"
}

/** What one Back/Escape press resolved to. The host translates only [LeftEditor] into navigation. */
sealed interface DocumentBackResult {
    data class ClosedSurface(val closed: String, val nowActive: String?) : DocumentBackResult

    /** The surface swallowed Back (it requires an explicit Done) and stayed open. */
    data class Consumed(val surface: String) : DocumentBackResult

    data object ExitedSelectionMode : DocumentBackResult

    /** A gesture was in flight and was cancelled, restoring the pre-gesture geometry exactly. */
    data object CancelledManipulation : DocumentBackResult

    /** Nothing was open, but something was selected; the selection was cleared. */
    data object ClearedSelection : DocumentBackResult

    /** Nothing left to unwind — the host may leave for the previous screen. */
    data object LeftEditor : DocumentBackResult
}
