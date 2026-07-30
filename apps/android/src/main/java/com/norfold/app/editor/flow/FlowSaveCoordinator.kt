package com.norfold.app.editor.flow

import com.norfold.app.domain.BlockDocument
import com.norfold.app.editor.history.PersistableEditController
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select

/**
 * One save attempt's inputs, captured together before the write starts.
 *
 * Capturing them as one value is the point: the result of a write applies to *this* snapshot, not to whatever
 * the live document has become by the time it lands. [baselineGeneration] is the controller's baseline at
 * capture time, which is what lets a completion recognise that a remote merge has since moved the baseline out
 * from under it.
 */
data class FlowSaveRequest(
    val document: BlockDocument,
    val title: String,
    val dirtyBlockIds: Set<String>,
    val deletedBlockIds: Set<String>,
    val baselineGeneration: Long,
)

/** The persistence seam. Suspends until the repository has genuinely accepted or rejected the write. */
fun interface FlowPersister {
    suspend fun persist(request: FlowSaveRequest): Result<Unit>
}

/**
 * The authoritative persistence state machine for one Flow document.
 *
 * Every route that changes durable state — body edits, structural edits, deletions, title renames, and the
 * flush when the editor closes — converges on [requestSave]. There is deliberately no second path: the defect
 * this replaces let title keystrokes call the repository directly, fire-and-forget, so a title write could
 * neither be retried nor serialized against a body write and its failure was unobservable.
 *
 * The states, and why each exists:
 *
 * ```
 *  CLEAN              nothing pending
 *  WAITING_FOR_DEBOUNCE   an edit landed; wait for the typing to stop
 *  SAVING             a write is in flight — exactly one, ever
 *  DIRTY_WHILE_SAVING an edit landed during that write; it is not covered by it
 *  RETRY_WAIT         the write failed; a backed-off attempt is already scheduled
 * ```
 *
 * Two properties do the real work.
 *
 * **A failure schedules its own retry.** "Left dirty, so the next edit will pick it up" is not durability — if
 * the user stops typing, no next edit ever comes. The loop below re-arms on a bounded backoff with no user
 * activity required.
 *
 * **Only genuine evidence advances the baseline.** The controller is told a snapshot was saved solely when the
 * persister reports success *and* the baseline it was measured against is still current
 * ([FlowEditController.markSavedIfCurrent]). A completion that raced a remote merge is discarded rather than
 * applied, leaving the content dirty for the next attempt.
 *
 * Ownership: [start] runs the loop on the scope it is given, which must outlive any single composition — the
 * ViewModel's scope. A retry loop owned by a `LaunchedEffect` dies exactly when it is most needed, at the
 * moment the user closes the screen with a failed write outstanding.
 *
 * Pure-JVM apart from coroutines, so the whole state machine is unit-testable with a fake persister and a
 * virtual clock; no Compose, no Android, no repository knowledge.
 */
class FlowSaveCoordinator(
    private val controller: PersistableEditController,
    private val persister: FlowPersister,
    private val debounceMillis: Long = DEFAULT_DEBOUNCE_MS,
    private val delayFor: suspend (Long) -> Unit = { delay(it) },
) {

    /** What the document's title should be. Updated by typing; persisted through the same pipeline as content. */
    private var desiredTitle: String = ""

    /** The title last accepted by the repository. Title dirtiness lives here — it is outside the document. */
    private var persistedTitle: String = ""

    /** Signals the loop. Conflated: several edits arriving before the loop wakes mean one pass, not several. */
    private val wakeups = Channel<Unit>(Channel.CONFLATED)

    /** Set when a flush was requested (editor closing), so the loop skips the debounce for that pass. */
    private var flushRequested: Boolean = false

    /**
     * Set when the editor has closed: the loop finishes what is pending, then exits.
     *
     * Without this the loop would sit suspended on [wakeups] for the life of the ViewModel — one idle coroutine
     * per note ever opened. It cannot simply be cancelled at teardown either, because a failed write's retry is
     * exactly the work that must outlive the screen. Draining first and exiting after satisfies both.
     */
    private var closeAfterDrain: Boolean = false

    private var loop: Job? = null

    /** True once the loop has drained and exited. A retired coordinator can never write again. */
    private var retired: Boolean = false

    /** Invoked once, from the loop, when it exits — lets the owning registry drop this session. */
    private var onRetired: (() -> Unit)? = null

    /** True while a write is awaiting the repository. Guards against a second concurrent write. */
    private var writing: Boolean = false

    // Observable for tests and for a future "saving…" indicator.
    var state: State = State.CLEAN
        private set

    var consecutiveFailures: Int = 0
        private set

    enum class State { CLEAN, WAITING_FOR_DEBOUNCE, SAVING, DIRTY_WHILE_SAVING, RETRY_WAIT }

    /**
     * Seeds the titles without marking anything dirty. Called once when the document session is created, so
     * opening a note does not immediately look like a rename.
     */
    fun bindTitle(title: String) {
        desiredTitle = title
        persistedTitle = title
    }

    /** The title the editor should display. Authoritative: it survives every composition of the screen. */
    fun title(): String = desiredTitle

    /** True when the user has renamed the document and the repository has not accepted it yet. */
    fun isTitleDirty(): Boolean = desiredTitle != persistedTitle

    /**
     * A title observed from storage — the repository row, or a remote write echoed into it.
     *
     * It always updates the *baseline*, because it is evidence of what is persisted, and that is true whoever
     * wrote it. What it must not do is silently discard a rename the user has typed but that has not been
     * accepted yet: local-dirty wins, exactly as it does for block content, and the pending rename is simply
     * re-measured against the new baseline and saved again. With no local rename pending there is nothing to
     * protect, so the stored title is adopted and becomes what the editor shows.
     */
    fun onStoredTitle(stored: String) {
        val renamePending = isTitleDirty()
        persistedTitle = stored
        if (renamePending) requestSave() else desiredTitle = stored
    }

    /**
     * Starts the loop on [scope]. Idempotent, and safe to call again for a session that was disposed and
     * recomposed before it finished draining — that is what keeps one writer per document across a screen
     * recreation.
     */
    fun start(scope: CoroutineScope) {
        // Reopened mid-drain: cancel the retirement rather than let a second writer appear beside this one.
        closeAfterDrain = false
        if (loop == null) loop = scope.launch { run() }
    }

    /** True once the loop has drained and exited. A retired coordinator is inert. */
    fun isRetired(): Boolean = retired

    /** Registers the callback the loop fires when it retires, so an owning registry can drop the entry. */
    fun onRetired(callback: () -> Unit) {
        onRetired = callback
    }

    /** The title the user has typed. Schedules persistence; it does not write. */
    fun onTitleChanged(next: String) {
        desiredTitle = next
        requestSave()
    }

    /** A body/structural/deletion edit landed. Schedules persistence. */
    fun requestSave() {
        if (state == State.SAVING) state = State.DIRTY_WHILE_SAVING
        wakeups.trySend(Unit)
    }

    /**
     * Asks for an immediate attempt, skipping the debounce — the editor's screen is going away.
     *
     * The write itself is deliberately not awaited: the loop lives on a scope that outlives this composition, so
     * a flush requested during teardown completes (and retries until accepted) after the screen is gone.
     *
     * This does **not** end the session. A configuration change disposes and recomposes the same document, and
     * retiring here would let the recomposition build a second coordinator beside a first one that can still
     * write. Ending the session is [close]'s job, and only the registry that knows the document was genuinely
     * left calls it.
     */
    fun flush() {
        flushRequested = true
        requestSave()
    }

    /**
     * Ends the session: attempt everything pending immediately, then retire once the repository has accepted
     * it. Called when the document is genuinely closed, not when its screen is merely recreated.
     */
    fun close() {
        closeAfterDrain = true
        flush()
    }

    /** True when the document or the title differs from what the repository has accepted. */
    fun hasPendingWork(): Boolean =
        desiredTitle != persistedTitle ||
            controller.dirtyBlockIds().isNotEmpty() ||
            controller.deletedBlockIds().isNotEmpty()

    private suspend fun run() {
        while (true) {
            wakeups.receive()
            // Coalesce a burst of edits into one attempt. A flush means the editor is closing, so the idle gap
            // is exactly the wrong thing to wait for.
            if (!flushRequested) waitForIdle()
            flushRequested = false
            saveUntilAcceptedOrClean()
            // Everything pending has been accepted. If the editor has closed and has not been reopened in the
            // meantime, retire rather than park a coroutine on the ViewModel's scope for every note ever
            // opened. Retiring is one-way and observable, so the registry can replace this session knowing it
            // is inert — the alternative, letting a second coordinator appear beside a live one, is two
            // writers for one document.
            if (closeAfterDrain) {
                retired = true
                loop = null
                onRetired?.invoke()
                onRetired = null
                return
            }
        }
    }

    /**
     * Waits for [debounceMillis] of quiet. Each new edit restarts the gap, so a continuous typing run produces
     * one write at the end of it rather than one per keystroke.
     */
    private suspend fun waitForIdle() {
        state = State.WAITING_FOR_DEBOUNCE
        while (true) {
            @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
            val more = select {
                wakeups.onReceive { true }
                onTimeout(debounceMillis) { false }
            }
            if (!more) return
            if (flushRequested) return
        }
    }

    /**
     * Attempts the write, retrying on failure with bounded backoff, until the repository accepts everything
     * pending or there is nothing left to save.
     *
     * The loop re-reads the pending state after every attempt rather than trusting the snapshot it started
     * with. That is what makes an edit arriving mid-write safe: the accepted snapshot baselines only itself, so
     * the newer edit is still dirty on the next pass and gets its own write.
     */
    private suspend fun saveUntilAcceptedOrClean() {
        while (true) {
            if (!hasPendingWork()) {
                state = State.CLEAN
                consecutiveFailures = 0
                return
            }

            val request = FlowSaveRequest(
                document = controller.document(),
                title = desiredTitle,
                dirtyBlockIds = controller.dirtyBlockIds(),
                deletedBlockIds = controller.deletedBlockIds(),
                baselineGeneration = controller.baselineGeneration(),
            )

            state = State.SAVING
            val result = try {
                // Serialization is structural — one loop, one sequential await — but asserted rather than
                // assumed, because a second concurrent write is the failure mode that silently reorders
                // baselines and it must not be able to appear from a future refactor unnoticed.
                check(!writing) { "a second write started while one was in flight" }
                writing = true
                persister.persist(request)
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                Result.failure(t)
            } finally {
                writing = false
            }

            if (result.isSuccess) {
                consecutiveFailures = 0
                // Both baselines move together or neither does. A title accepted against a baseline that has
                // since been replaced by a remote snapshot is no more trustworthy than the document was.
                if (controller.markSavedIfCurrent(request.document, request.baselineGeneration)) {
                    persistedTitle = request.title
                }
                // Loop rather than return: an edit that landed during the write is still pending, and waiting
                // for another wakeup would leave it unsaved until the user happened to type again.
                continue
            }

            consecutiveFailures++
            state = State.RETRY_WAIT
            delayFor(backoffMillis(consecutiveFailures))
        }
    }

    /**
     * Exponential backoff, capped. Bounded on both ends deliberately: an unbounded retry after a connectivity
     * failure is a storm, and a tight loop is worse than not retrying at all.
     */
    internal fun backoffMillis(failures: Int): Long {
        val shift = (failures - 1).coerceIn(0, 30)
        val scaled = FIRST_RETRY_MS shl shift
        return if (scaled <= 0L || scaled > MAX_RETRY_MS) MAX_RETRY_MS else scaled
    }

    companion object {
        /** Idle gap after the last edit before an auto-save fires, bounding what a process death can take. */
        const val DEFAULT_DEBOUNCE_MS = 1_500L

        const val FIRST_RETRY_MS = 1_000L

        /** A failing backend must not be retried more often than this, however long the outage lasts. */
        const val MAX_RETRY_MS = 30_000L
    }
}
