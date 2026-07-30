package com.norfold.app.editor.document

import com.norfold.app.editor.flow.FlowSaveCoordinator
import kotlinx.coroutines.CoroutineScope

/**
 * One open bounded document: its runtime, and the coordinator that persists what the runtime produces.
 *
 * The two belong together because they share a baseline — the coordinator hands
 * [DocumentSceneController.baselineGeneration] back on success, and pairing it with a *different*
 * controller than the one it started against silently invalidates that guard. Binding them into one
 * value makes the mismatch unrepresentable rather than merely avoided.
 *
 * The coordinator is the Flow one, deliberately. It talks to
 * [com.norfold.app.editor.history.PersistableEditController], not to a host — so it carries no Flow
 * semantics, and reusing it means the debounce, the single-writer serialization, the bounded retry and
 * the stale-completion guard are the *same proven state machine* for both products rather than two
 * copies that drift.
 */
class DocumentSession(
    val runtime: DocumentHostRuntime,
    val saves: FlowSaveCoordinator,
)

/**
 * The owner of every live [DocumentSession], keyed by document id.
 *
 * The same reasoning as [com.norfold.app.editor.flow.FlowSessionRegistry]: a coordinator's retry loop
 * must outlive the screen, so the session cannot live in a `remember`. A configuration change while a
 * write was still retrying would otherwise produce a second writer for the same document, with its own
 * baseline, writing concurrently with the first.
 *
 * Owned by the ViewModel. Single-threaded by contract, like the rest of the runtime.
 */
class DocumentSessionRegistry {

    private val sessions = LinkedHashMap<String, DocumentSession>()

    /** The live session for [documentId], creating one via [create] only when there is none. */
    fun open(documentId: String, scope: CoroutineScope, create: () -> DocumentSession): DocumentSession {
        sessions[documentId]?.let {
            it.saves.start(scope)
            return it
        }
        val session = create()
        sessions[documentId] = session
        // A session drops out exactly when its coordinator retires, and a retired coordinator can never
        // write again — so a replacement is only ever built alongside something inert.
        session.saves.onRetired { sessions.remove(documentId, session) }
        session.saves.start(scope)
        return session
    }

    /** Ends the session for [documentId] once everything pending has been accepted. */
    fun close(documentId: String) {
        sessions[documentId]?.saves?.close()
    }

    fun closeAllExcept(keep: String?) {
        sessions.forEach { (id, session) -> if (id != keep) session.saves.close() }
    }

    fun closeAll() {
        sessions.values.toList().forEach { it.saves.close() }
    }

    fun size(): Int = sessions.size
}
