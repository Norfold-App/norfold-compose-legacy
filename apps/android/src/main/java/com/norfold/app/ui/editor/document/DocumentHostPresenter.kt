package com.norfold.app.ui.editor.document

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import com.norfold.app.domain.BlockDocument
import com.norfold.app.editor.document.DocumentHostRuntime
import com.norfold.app.editor.document.DocumentHostUiState

/**
 * The only bridge between Compose and [DocumentHostRuntime].
 *
 * The mirror of [com.norfold.app.ui.editor.flow.FlowHostPresenter], and deliberately as small: it publishes
 * the runtime's snapshot and funnels gestures back. It holds no eligibility rules, no geometry maths and no
 * document mutation, because every one of those already exists in a shared engine — duplicating any of them
 * here is exactly how the legacy host accumulated its ad-hoc behaviour.
 *
 * [act] is the funnel: run something on the runtime, then re-read the snapshot. The runtime bumps its
 * revision for anything that changes what should be drawn, so one refresh per interaction is sufficient.
 */
@Stable
class DocumentHostPresenter(
    val runtime: DocumentHostRuntime,
    private val onDocumentChanged: (BlockDocument) -> Unit = {},
) {
    var state: DocumentHostUiState by mutableStateOf(runtime.uiState())
        private set

    private var lastDocument: BlockDocument = runtime.controller.document()

    /** Runs [body] against the runtime and republishes the snapshot. */
    fun <T> act(body: DocumentHostRuntime.() -> T): T {
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
fun rememberDocumentHostPresenter(
    runtime: DocumentHostRuntime,
    onDocumentChanged: (BlockDocument) -> Unit,
): DocumentHostPresenter {
    // Re-read at call time rather than captured once, so a recomposition with a new lambda does not leave
    // the presenter pushing edits into a stale receiver.
    val latest by rememberUpdatedState(onDocumentChanged)
    return remember(runtime) { DocumentHostPresenter(runtime) { latest(it) } }
}
