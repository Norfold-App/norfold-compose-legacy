package com.norfold.app.editor.flow

import kotlinx.coroutines.CoroutineScope

/**
 * One open Flow document: its runtime, and the coordinator that persists what the runtime produces.
 *
 * The two belong together because they share a baseline. The coordinator asks
 * [FlowEditController.baselineGeneration] what it is measuring against and hands that token back on success;
 * pairing a coordinator with a *different* controller than the one it started against silently invalidates that
 * guard. Binding them into one value makes the mismatch unrepresentable rather than merely avoided.
 */
class FlowDocumentSession(
    val runtime: FlowHostRuntime,
    val saves: FlowSaveCoordinator,
)

/**
 * The owner of every live [FlowDocumentSession], keyed by document id.
 *
 * **Why this is not a `remember` in the editor composable.** A coordinator's retry loop deliberately outlives
 * the screen — that is the whole point of running it on the ViewModel's scope, because a failing write must
 * keep retrying after the user closes the editor. But `remember(documentId)` is keyed to the *composition*, so
 * a configuration change or a recreation while that loop was still retrying produced a second coordinator for
 * the same document, with its own controller and its own baseline, writing concurrently with the first. Two
 * writers, two baselines, interleaved writes: the one-writer-per-document guarantee held only inside a single
 * coordinator instance, which is exactly the case that recreation breaks.
 *
 * So the session outlives the composition and the composition looks it up. Re-entering the same document
 * resolves to the same live session; a session is replaced only once the previous one has [retired]
 * [FlowSaveCoordinator.isRetired], meaning its loop has drained everything pending and can never write again.
 *
 * Owned by the ViewModel, so its lifetime is the document-editing session rather than any one screen.
 * Single-threaded by contract, like the rest of the Flow runtime: the main thread opens and closes sessions.
 */
class FlowSessionRegistry {

    private val sessions = LinkedHashMap<String, FlowDocumentSession>()

    /**
     * The live session for [documentId], creating one via [create] only when there is none.
     *
     * A session drops out of this map exactly when its coordinator retires, and a retired coordinator can never
     * write again — so a replacement is only ever built alongside something inert. That single rule is what
     * makes two concurrent writers for one document unrepresentable, rather than merely unlikely.
     *
     * [FlowSaveCoordinator.start] re-arms a session that had begun draining, which is what makes returning to
     * a document during its retry backoff reuse the existing writer instead of racing a new one against it.
     */
    fun open(documentId: String, scope: CoroutineScope, create: () -> FlowDocumentSession): FlowDocumentSession {
        sessions[documentId]?.let {
            it.saves.start(scope)
            return it
        }
        val session = create()
        sessions[documentId] = session
        session.saves.onRetired { sessions.remove(documentId, session) }
        session.saves.start(scope)
        return session
    }

    /**
     * Ends the session for [documentId]: everything pending is attempted immediately and retried until the
     * repository accepts it, and only then does the session retire and drop out of the registry.
     *
     * Deliberately not the same call the screen makes when it is disposed. Disposal happens on every rotation;
     * this happens when the document is genuinely left.
     */
    fun close(documentId: String) {
        sessions[documentId]?.saves?.close()
    }

    /** Ends every live session except [keep]. Called when the selected document changes. */
    fun closeAllExcept(keep: String?) {
        sessions.forEach { (id, session) -> if (id != keep) session.saves.close() }
    }

    /** Ends every live session. For ViewModel teardown. */
    fun closeAll() {
        sessions.values.toList().forEach { it.saves.close() }
    }

    /** Live session count, for tests. */
    fun size(): Int = sessions.size
}
