package com.norfold.app.editor.calculation

enum class SpreadsheetKey {
    ARROW_UP,
    ARROW_DOWN,
    ARROW_LEFT,
    ARROW_RIGHT,
    TAB,
    ENTER,
    ESCAPE,
    DELETE,
    Z,
    Y,
}

data class SpreadsheetShortcut(
    val key: SpreadsheetKey,
    val shift: Boolean = false,
    val ctrlOrMeta: Boolean = false,
)

sealed interface SpreadsheetKeyAction {
    data class Move(val rowDelta: Int, val columnDelta: Int, val extend: Boolean) : SpreadsheetKeyAction
    data object BeginOrCommitEdit : SpreadsheetKeyAction
    data object Clear : SpreadsheetKeyAction
    data object Back : SpreadsheetKeyAction
    data object Undo : SpreadsheetKeyAction
    data object Redo : SpreadsheetKeyAction
    data object Unhandled : SpreadsheetKeyAction
}

object SpreadsheetKeyboardMap {
    fun resolve(shortcut: SpreadsheetShortcut, textEntryActive: Boolean): SpreadsheetKeyAction {
        if (textEntryActive) {
            return when (shortcut.key) {
                SpreadsheetKey.ESCAPE -> SpreadsheetKeyAction.Back
                SpreadsheetKey.ENTER -> SpreadsheetKeyAction.BeginOrCommitEdit
                else -> SpreadsheetKeyAction.Unhandled
            }
        }
        if (shortcut.ctrlOrMeta && shortcut.key == SpreadsheetKey.Z) return if (shortcut.shift) SpreadsheetKeyAction.Redo else SpreadsheetKeyAction.Undo
        if (shortcut.ctrlOrMeta && shortcut.key == SpreadsheetKey.Y) return SpreadsheetKeyAction.Redo
        return when (shortcut.key) {
            SpreadsheetKey.ARROW_UP -> SpreadsheetKeyAction.Move(-1, 0, shortcut.shift)
            SpreadsheetKey.ARROW_DOWN -> SpreadsheetKeyAction.Move(1, 0, shortcut.shift)
            SpreadsheetKey.ARROW_LEFT -> SpreadsheetKeyAction.Move(0, -1, shortcut.shift)
            SpreadsheetKey.ARROW_RIGHT -> SpreadsheetKeyAction.Move(0, 1, shortcut.shift)
            SpreadsheetKey.TAB -> SpreadsheetKeyAction.Move(0, if (shortcut.shift) -1 else 1, false)
            SpreadsheetKey.ENTER -> SpreadsheetKeyAction.BeginOrCommitEdit
            SpreadsheetKey.ESCAPE -> SpreadsheetKeyAction.Back
            SpreadsheetKey.DELETE -> SpreadsheetKeyAction.Clear
            else -> SpreadsheetKeyAction.Unhandled
        }
    }
}
