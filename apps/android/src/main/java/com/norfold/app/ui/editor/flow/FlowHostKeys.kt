package com.norfold.app.ui.editor.flow

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
 * The single translation point between Android key events and the pure-JVM [FlowShortcut] vocabulary.
 *
 * Everything above this line is toolkit-independent, which is why the keyboard map and its parity with the
 * dock, slash menu and accessibility tree can be unit-tested without an Android runtime at all.
 *
 * Only the keys FlowHost actually binds are translated. An unmapped key returns null and falls straight
 * through to the focused text field and the IME, so the editor never swallows ordinary typing.
 */
private val KEY_TOKENS: Map<Key, String> = buildMap {
    put(Key.Zero, "0")
    put(Key.One, "1")
    put(Key.Two, "2")
    put(Key.Three, "3")
    put(Key.Four, "4")
    put(Key.Five, "5")
    put(Key.Six, "6")
    put(Key.Seven, "7")
    put(Key.Eight, "8")
    put(Key.Nine, "9")
    put(Key.C, "c")
    put(Key.D, "d")
    put(Key.G, "g")
    put(Key.L, "l")
    put(Key.Q, "q")
    put(Key.Y, "y")
    put(Key.Z, "z")
    put(Key.Tab, "tab")
    put(Key.Backspace, "backspace")
    put(Key.Enter, "enter")
    put(Key.NumPadEnter, "enter")
    put(Key.DirectionUp, "arrowup")
    put(Key.DirectionDown, "arrowdown")
}

/** The chord this event represents, or null when FlowHost binds nothing on this key. */
fun KeyEvent.toFlowShortcut(): FlowShortcut? {
    if (type != KeyEventType.KeyDown) return null
    val token = KEY_TOKENS[key] ?: return null
    return FlowShortcut(
        key = token,
        ctrl = isCtrlPressed,
        shift = isShiftPressed,
        alt = isAltPressed,
    )
}
