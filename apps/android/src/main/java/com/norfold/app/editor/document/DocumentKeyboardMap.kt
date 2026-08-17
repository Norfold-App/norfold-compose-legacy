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

    /**
     * What [shortcut] should actually do, given whether a text field currently owns input.
     *
     * **Why arbitration is a rule and not a list of exceptions.** The host installs a *preview* key handler on
     * the editor root, so the canvas sees every key before the focused `BasicTextField` does. With a flat
     * binding table that means Backspace deletes the object you are typing in, and an arrow key nudges the
     * frame instead of moving the caret. Filtering individual characters would leave the same bug waiting for
     * whichever key was forgotten, so the decision is made from one property of the chord instead:
     *
     *  - **a bare chord belongs to the text field.** No Ctrl and no Alt means it is text input, caret
     *    movement, or deletion — Backspace, Delete, the four arrows, and Shift+arrow for selection;
     *  - **a modified chord belongs to the canvas**, because Ctrl/Alt chords are not text input;
     *  - **except Ctrl+A**, which every text field on every platform reads as "select all text". Handing it to
     *    the canvas would make select-all-objects fire while the user meant their paragraph;
     *  - **Escape always resolves the hierarchy**, which is precisely how the caret is meant to leave.
     *
     * Pure and total, so every case above is a unit test rather than a device observation.
     */
    fun resolve(shortcut: FlowShortcut, textEntryActive: Boolean): DocumentKeyAction {
        // Escape is the documented way out of content editing, so it is never withheld from the hierarchy.
        if (shortcut == ESCAPE) return DocumentKeyAction.Back

        if (textEntryActive && !shortcut.ctrl && !shortcut.alt) return DocumentKeyAction.Unhandled
        // Undo/redo are routed even while typing: they are the history, and a text field's own local undo
        // would diverge from the document's.
        if (shortcut == UNDO) return DocumentKeyAction.Undo
        if (shortcut == REDO || shortcut == REDO_ALT) return DocumentKeyAction.Redo
        if (textEntryActive && shortcut == SELECT_ALL) return DocumentKeyAction.Unhandled
        if (shortcut == SELECT_MODE) return DocumentKeyAction.EnterSelectionMode
        if (shortcut == SELECT_ALL) return DocumentKeyAction.SelectAllObjects

        return bindings[shortcut]?.let { DocumentKeyAction.Command(it) } ?: DocumentKeyAction.Unhandled
    }
}

/**
 * What one chord resolves to once focus ownership has been accounted for.
 *
 * A closed set rather than a nullable command id, because four of these are *not* registry commands — they
 * are History and hierarchy operations — and collapsing them into "some id" is what would let the routing
 * layer be tested only through the runtime that consumes it.
 */
sealed interface DocumentKeyAction {
    /** Not ours: the event must fall through to the focused field and the IME untouched. */
    data object Unhandled : DocumentKeyAction

    data object Undo : DocumentKeyAction
    data object Redo : DocumentKeyAction

    /** One level of the back hierarchy — which, while editing text, is "leave the caret". */
    data object Back : DocumentKeyAction

    data object EnterSelectionMode : DocumentKeyAction
    data object SelectAllObjects : DocumentKeyAction

    /** A registered command id, invoked through the one path every other surface uses. */
    data class Command(val id: String) : DocumentKeyAction
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
    const val INSERT_CHART = "document.object.insertChart"

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
    const val COLOR_STUDIO = "editor.surface.colorStudio"
    const val FONT_BROWSER = "editor.surface.fontBrowser"
    const val NAMED_STYLES = "editor.surface.namedStyles"
    const val RELATIONSHIP_MANAGER = com.norfold.app.editor.relationship.RelationshipSurfaces.MANAGER
    const val COMMENTS = com.norfold.app.editor.relationship.RelationshipSurfaces.COMMENTS
    const val REVIEW = com.norfold.app.editor.relationship.RelationshipSurfaces.REVIEW

    /** Page setup at a chosen scope. */
    const val PAGE_CONTROL = "document.surface.pageControl"

    /** Exact X/Y/W/H/Rotation entry for the primary selected object. */
    const val GEOMETRY_INSPECTOR = "document.surface.geometry"

    /**
     * Every action the selected object offers, grouped — the object inspector.
     *
     * Distinct from [GEOMETRY_INSPECTOR], which is numeric *value* entry for one object's box. This is the
     * object's *actions*, and Document had no surface for them at all: they were reachable only as chips on a
     * dock that scrolls sideways past sixteen entries, or by keyboard chord.
     */
    const val OBJECT_INSPECTOR = "document.surface.objectInspector"

    /** The page list, where pages are selected for the "Selected pages" scope and reordered. */
    const val PAGE_LIST = "document.surface.pageList"
}

/** What one Back/Escape press resolved to. The host translates only [LeftEditor] into navigation. */
sealed interface DocumentBackResult {
    data class ClosedSurface(val closed: String, val nowActive: String?) : DocumentBackResult

    /** The surface swallowed Back (it requires an explicit Done) and stayed open. */
    data class Consumed(val surface: String) : DocumentBackResult

    data object ExitedSelectionMode : DocumentBackResult
    data object ExitedConnectMode : DocumentBackResult

    /** A gesture was in flight and was cancelled, restoring the pre-gesture geometry exactly. */
    data object CancelledManipulation : DocumentBackResult

    /**
     * Text/content editing ended. The object stays selected and the caret leaves — the inner level unwound
     * without disturbing the outer one, so a second Back can still clear the selection.
     */
    data object ExitedContentEdit : DocumentBackResult

    /** Nothing was open, but something was selected; the selection was cleared. */
    data object ClearedSelection : DocumentBackResult

    /** Nothing left to unwind — the host may leave for the previous screen. */
    data object LeftEditor : DocumentBackResult
}
