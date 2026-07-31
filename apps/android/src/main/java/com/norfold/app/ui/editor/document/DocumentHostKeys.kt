package com.norfold.app.ui.editor.document

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import com.norfold.app.editor.flow.FlowShortcut

/**
 * The single translation point between Android key events and the pure-JVM chord vocabulary DocumentHost
 * binds.
 *
 * The mirror of [com.norfold.app.ui.editor.flow.toFlowShortcut], and deliberately as thin: everything above
 * this line is toolkit-independent, which is why [com.norfold.app.editor.document.DocumentKeyboardMap] and
 * its parity with the dock and the accessibility tree can be unit-tested with no Android runtime at all.
 *
 * Only the keys DocumentHost actually binds are translated. An unmapped key returns null and falls straight
 * through to the focused text field and the IME, so editing an object's text never has its keystrokes eaten
 * by the canvas around it.
 */
private val KEY_TOKENS: Map<Key, String> = buildMap {
    put(Key.A, "a")
    put(Key.D, "d")
    put(Key.L, "l")
    put(Key.R, "r")
    put(Key.T, "t")
    put(Key.Y, "y")
    put(Key.Z, "z")
    put(Key.LeftBracket, "[")
    put(Key.RightBracket, "]")
    put(Key.Delete, "delete")
    put(Key.Backspace, "backspace")
    put(Key.Escape, "escape")
    put(Key.DirectionLeft, "arrowleft")
    put(Key.DirectionRight, "arrowright")
    put(Key.DirectionUp, "arrowup")
    put(Key.DirectionDown, "arrowdown")
}

/** The chord this event represents, or null when DocumentHost binds nothing on this key. */
fun KeyEvent.toDocumentShortcut(): FlowShortcut? {
    if (type != KeyEventType.KeyDown) return null
    val token = KEY_TOKENS[key] ?: return null
    return FlowShortcut(
        key = token,
        ctrl = isCtrlPressed,
        shift = isShiftPressed,
        alt = isAltPressed,
    )
}
