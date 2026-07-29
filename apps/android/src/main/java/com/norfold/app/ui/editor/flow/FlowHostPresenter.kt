package com.norfold.app.ui.editor.flow

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import com.norfold.app.domain.BlockDocument
import com.norfold.app.editor.flow.FlowHostRuntime
import com.norfold.app.editor.flow.FlowHostUiState

/**
 * The only bridge between Compose and [FlowHostRuntime].
 *
 * It does exactly two things: hand the current [FlowHostUiState] to the composables, and funnel gestures back
 * into the runtime. It deliberately has no `if`s about eligibility, no document mutation, and no command
 * behavior — those live in the shared engines, and keeping them out of here is what stops the Compose layer
 * from re-acquiring the ad-hoc logic the legacy host accumulated.
 *
 * [act] is the funnel: run something on the runtime, then re-read the snapshot. Because the runtime bumps its
 * revision for anything that changes what should be drawn, one refresh after each interaction is sufficient
 * and there is no observer plumbing to keep in sync.
 */
@Stable
class FlowHostPresenter(
    val runtime: FlowHostRuntime,
    private val onDocumentChanged: (BlockDocument) -> Unit = {},
) {
    var state: FlowHostUiState by mutableStateOf(runtime.uiState())
        private set

    private var lastDocument: BlockDocument = runtime.controller.document()

    /** Runs [body] against the runtime and republishes the snapshot. */
    fun <T> act(body: FlowHostRuntime.() -> T): T {
        val result = runtime.body()
        refresh()
        return result
    }

    fun refresh() {
        state = runtime.uiState()
        val document = runtime.controller.document()
        if (document !== lastDocument && document != lastDocument) {
            lastDocument = document
            onDocumentChanged(document)
        }
    }
}

@Composable
fun rememberFlowHostPresenter(
    runtime: FlowHostRuntime,
    onDocumentChanged: (BlockDocument) -> Unit,
): FlowHostPresenter {
    // The callback is re-read at call time rather than captured once, so a recomposition with a new lambda
    // does not leave the presenter pushing edits into a stale receiver.
    val latest by rememberUpdatedState(onDocumentChanged)
    return remember(runtime) { FlowHostPresenter(runtime) { latest(it) } }
}
