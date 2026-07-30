package com.norfold.app.editor.flow

import com.norfold.app.domain.BlockDocument
import com.norfold.app.domain.DocumentBlock
import com.norfold.app.domain.InlineText
import com.norfold.app.domain.ParagraphBlock
import com.norfold.app.editor.history.EditTarget
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The persistence regression matrix (cases A–W of the Phase-1 data-safety closure).
 *
 * These exercise [FlowSaveCoordinator] against a fake persister whose completion the test controls, on the
 * virtual clock `runTest` provides — so debounce, backoff and races are asserted deterministically rather than
 * approximated with sleeps.
 *
 * The defects being pinned are all "looks safe, loses data":
 *
 *  - a failed write left the content dirty but scheduled nothing, so an idle user's edit was never retried;
 *  - title typing bypassed the save path entirely, fire-and-forget, unretryable and unobservable;
 *  - a completion could baseline a snapshot measured against a baseline a remote merge had already replaced.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FlowSaveCoordinatorTest {

    private val target = EditTarget("note:save")

    private fun para(id: String, text: String = ""): ParagraphBlock =
        ParagraphBlock(id = id, content = if (text.isEmpty()) emptyList() else listOf(InlineText(text)))

    private fun doc(vararg blocks: DocumentBlock) = BlockDocument(blocks.toList())

    private fun controller(vararg blocks: DocumentBlock): FlowEditController =
        FlowEditController(target, doc(*blocks)).apply { markSaved() }

    /**
     * A persister the test drives. Records every request it was asked to write, reports whatever outcome the
     * test dictates, and can hold a write open so "what happens *during* a save" is testable.
     */
    private class FakePersister : FlowPersister {
        val requests = mutableListOf<FlowSaveRequest>()

        /** Set to fail the next [failures] attempts; each failure decrements it. */
        var failures: Int = 0

        /** When set, a write suspends on this until the test completes it. */
        var gate: CompletableDeferred<Result<Unit>>? = null

        /**
         * Gates applied to successive attempts, one each, before [gate] is consulted. Lets a test hold the
         * *second* write open so the state left by the first one's completion can be inspected before the
         * coordinator reconciles it away.
         */
        val queuedGates = mutableListOf<CompletableDeferred<Result<Unit>>>()

        var concurrent: Int = 0
        var maxConcurrent: Int = 0

        override suspend fun persist(request: FlowSaveRequest): Result<Unit> {
            requests += request
            concurrent++
            maxConcurrent = maxOf(maxConcurrent, concurrent)
            try {
                if (queuedGates.isNotEmpty()) return queuedGates.removeAt(0).await()
                gate?.let { return it.await() }
                if (failures > 0) {
                    failures--
                    return Result.failure(IllegalStateException("Room write failed"))
                }
                return Result.success(Unit)
            } finally {
                concurrent--
            }
        }
    }

    // ── A–E: everything that changes durable state reaches the repository ────────────────────────────────

    @Test
    fun caseA_ordinaryBodyEditIsPersisted() = runTest {
        val ctl = controller(para("a", "one"))
        val persister = FakePersister()
        val saves = FlowSaveCoordinator(ctl, persister).apply { bindTitle("Note") }
        saves.start(backgroundScope)

        ctl.mutate("edit") { it.editText("a", "one", "ONE") }
        saves.requestSave()
        settle()

        assertEquals(1, persister.requests.size)
        assertEquals(setOf("a"), persister.requests[0].dirtyBlockIds)
        assertFalse(ctl.hasUnsavedChanges())
    }

    @Test
    fun caseB_deletionOnlyChangeIsPersisted() = runTest {
        // `dirtyBlockIds()` deliberately excludes removals, so a guard on it alone skips the write entirely
        // and the deleted block reappears on reopen.
        val ctl = controller(para("a", "one"), para("b", "two"))
        val persister = FakePersister()
        val saves = FlowSaveCoordinator(ctl, persister).apply { bindTitle("Note") }
        saves.start(backgroundScope)

        ctl.mutate("delete") { it.delete("b") }
        assertTrue("a pure deletion leaves no dirty ids", ctl.dirtyBlockIds().isEmpty())
        saves.requestSave()
        settle()

        assertEquals(1, persister.requests.size)
        assertEquals(setOf("b"), persister.requests[0].deletedBlockIds)
        assertTrue(ctl.deletedBlockIds().isEmpty())
    }

    @Test
    fun caseC_titleOnlyRenameIsPersisted() = runTest {
        val ctl = controller(para("a", "one"))
        val persister = FakePersister()
        val saves = FlowSaveCoordinator(ctl, persister).apply { bindTitle("Note") }
        saves.start(backgroundScope)

        saves.onTitleChanged("Renamed")
        settle()

        assertEquals("a title-only rename must enter the save pipeline", 1, persister.requests.size)
        assertEquals("Renamed", persister.requests[0].title)
        assertTrue("no document blocks changed", persister.requests[0].dirtyBlockIds.isEmpty())
        assertFalse(saves.hasPendingWork())
    }

    @Test
    fun caseD_titleAndBodyPersistTogether() = runTest {
        val ctl = controller(para("a", "one"))
        val persister = FakePersister()
        val saves = FlowSaveCoordinator(ctl, persister).apply { bindTitle("Note") }
        saves.start(backgroundScope)

        ctl.mutate("edit") { it.editText("a", "one", "ONE") }
        saves.onTitleChanged("Renamed")
        settle()

        assertEquals("one debounced write covers both", 1, persister.requests.size)
        assertEquals("Renamed", persister.requests[0].title)
        assertEquals(setOf("a"), persister.requests[0].dirtyBlockIds)
        assertFalse(saves.hasPendingWork())
    }

    @Test
    fun caseE_titleAndDeletionPersistTogether() = runTest {
        val ctl = controller(para("a", "one"), para("b", "two"))
        val persister = FakePersister()
        val saves = FlowSaveCoordinator(ctl, persister).apply { bindTitle("Note") }
        saves.start(backgroundScope)

        ctl.mutate("delete") { it.delete("b") }
        saves.onTitleChanged("Renamed")
        settle()

        assertEquals(1, persister.requests.size)
        assertEquals("Renamed", persister.requests[0].title)
        assertEquals(setOf("b"), persister.requests[0].deletedBlockIds)
        assertFalse(saves.hasPendingWork())
    }

    // ── F–K: failure is retried automatically and loses nothing ──────────────────────────────────────────

    @Test
    fun caseF_firstFailureRetriesWithoutAnotherEdit() = runTest {
        val ctl = controller(para("a", "one"))
        val persister = FakePersister().apply { failures = 1 }
        val saves = FlowSaveCoordinator(ctl, persister).apply { bindTitle("Note") }
        saves.start(backgroundScope)

        ctl.mutate("edit") { it.editText("a", "one", "ONE") }
        saves.requestSave()
        settle()

        assertEquals("the failure must schedule its own retry — no keystroke required", 2, persister.attemptCount())
        assertFalse("and the retry lands", ctl.hasUnsavedChanges())
    }

    @Test
    fun caseG_repeatedFailuresBackOffRatherThanSpin() = runTest {
        val ctl = controller(para("a", "one"))
        val persister = FakePersister().apply { failures = Int.MAX_VALUE }
        val saves = FlowSaveCoordinator(ctl, persister).apply { bindTitle("Note") }
        saves.start(backgroundScope)

        ctl.mutate("edit") { it.editText("a", "one", "ONE") }
        saves.requestSave()
        // Debounce, then let 8 s of retry time pass: 1 s + 2 s + 4 s fit, so a bounded backoff makes a handful
        // of attempts. A tight loop would make thousands.
        settle(FlowSaveCoordinator.DEFAULT_DEBOUNCE_MS + 8_000L)

        val attempts = persister.attemptCount()
        assertTrue("backoff must not spin: $attempts attempts in 8 s", attempts in 2..5)
        assertEquals(FlowSaveCoordinator.State.RETRY_WAIT, saves.state)
    }

    @Test
    fun caseG_backoffIsCapped() {
        val saves = FlowSaveCoordinator(controller(para("a")), FakePersister())
        assertEquals(1_000L, saves.backoffMillis(1))
        assertEquals(2_000L, saves.backoffMillis(2))
        assertEquals(4_000L, saves.backoffMillis(3))
        assertEquals("an hour-long outage must not overflow into a tight loop", 30_000L, saves.backoffMillis(40))
        assertTrue(saves.backoffMillis(200) == FlowSaveCoordinator.MAX_RETRY_MS)
    }

    @Test
    fun caseH_successResetsTheBackoff() = runTest {
        val ctl = controller(para("a", "one"))
        val persister = FakePersister().apply { failures = 2 }
        val saves = FlowSaveCoordinator(ctl, persister).apply { bindTitle("Note") }
        saves.start(backgroundScope)

        ctl.mutate("edit") { it.editText("a", "one", "ONE") }
        saves.requestSave()
        settle()

        assertEquals(0, saves.consecutiveFailures)
        assertEquals(FlowSaveCoordinator.State.CLEAN, saves.state)
    }

    @Test
    fun caseI_J_K_failureKeepsBodyDeletionAndTitleDirty() = runTest {
        val ctl = controller(para("a", "one"), para("b", "two"))
        val persister = FakePersister().apply { failures = Int.MAX_VALUE }
        val saves = FlowSaveCoordinator(ctl, persister).apply { bindTitle("Note") }
        saves.start(backgroundScope)

        ctl.mutate("edit a") { it.editText("a", "one", "ONE") }
        ctl.mutate("delete b") { it.delete("b") }
        saves.onTitleChanged("Renamed")
        settle(FlowSaveCoordinator.DEFAULT_DEBOUNCE_MS + 2_500L)

        assertEquals("body stays dirty", setOf("a"), ctl.dirtyBlockIds())
        assertEquals("deletion stays pending", setOf("b"), ctl.deletedBlockIds())
        assertTrue("title stays dirty", saves.hasPendingWork())

        // And once the backend recovers, the same pending state is accepted.
        persister.failures = 0
        settle()
        assertFalse(saves.hasPendingWork())
    }

    // ── L–N: work that lands during a write is not swallowed by that write's completion ─────────────────

    @Test
    fun caseL_editDuringSaveStaysDirtyAfterTheOldSnapshotSucceeds() = runTest {
        val ctl = controller(para("a", "one"), para("b", "two"))
        val persister = FakePersister()
        val gate = CompletableDeferred<Result<Unit>>()
        persister.gate = gate
        val saves = FlowSaveCoordinator(ctl, persister).apply { bindTitle("Note") }
        saves.start(backgroundScope)

        ctl.mutate("edit a") { it.editText("a", "one", "ONE") }
        saves.requestSave()
        settle()
        assertEquals(FlowSaveCoordinator.State.SAVING, saves.state)

        // A newer edit lands while the write of the older snapshot is still in flight.
        ctl.mutate("edit b") { it.editText("b", "two", "TWO") }
        saves.requestSave()
        assertEquals(FlowSaveCoordinator.State.DIRTY_WHILE_SAVING, saves.state)

        persister.gate = null
        gate.complete(Result.success(Unit))
        settle()

        assertEquals("the accepted snapshot baselines only itself", 2, persister.requests.size)
        assertEquals(setOf("b"), persister.requests[1].dirtyBlockIds)
        assertFalse("and the newer edit is then written too", ctl.hasUnsavedChanges())
    }

    @Test
    fun caseM_renameDuringSaveStaysPendingAfterTheOldTitleSucceeds() = runTest {
        val ctl = controller(para("a", "one"))
        val persister = FakePersister()
        val gate = CompletableDeferred<Result<Unit>>()
        persister.gate = gate
        val saves = FlowSaveCoordinator(ctl, persister).apply { bindTitle("T0") }
        saves.start(backgroundScope)

        ctl.mutate("edit a") { it.editText("a", "one", "ONE") }
        saves.onTitleChanged("T1")
        settle()
        assertEquals("T1", persister.requests[0].title)

        saves.onTitleChanged("T2")
        persister.gate = null
        gate.complete(Result.success(Unit))
        settle()

        assertEquals("T2 must not be marked saved by T1's completion", 2, persister.requests.size)
        assertEquals("T2", persister.requests[1].title)
        assertFalse(saves.hasPendingWork())
    }

    @Test
    fun caseN_deletionDuringSaveRemainsPending() = runTest {
        val ctl = controller(para("a", "one"), para("b", "two"))
        val persister = FakePersister()
        val gate = CompletableDeferred<Result<Unit>>()
        persister.gate = gate
        val saves = FlowSaveCoordinator(ctl, persister).apply { bindTitle("Note") }
        saves.start(backgroundScope)

        ctl.mutate("edit a") { it.editText("a", "one", "ONE") }
        saves.requestSave()
        settle()

        ctl.mutate("delete b") { it.delete("b") }
        saves.requestSave()
        persister.gate = null
        gate.complete(Result.success(Unit))
        settle()

        assertEquals(setOf("b"), persister.requests[1].deletedBlockIds)
        assertTrue(ctl.deletedBlockIds().isEmpty())
    }

    // ── O–S: remote convergence and stale completions ───────────────────────────────────────────────────

    /**
     * The stale-completion guard, asserted where the harm actually shows.
     *
     * Sequence: a write of the pre-merge snapshot goes out; a remote snapshot arrives carrying `b=REMOTE` and
     * advances the baseline; then the old write succeeds. Its snapshot never contained `b=REMOTE`.
     *
     * Without the epoch check, that completion baselines the pre-merge snapshot, so diffing the working
     * document against it reports `b` as a *local* change and the editor writes `b=REMOTE` back to the server as
     * though the user had typed it. The second write is held open here so the state left by the first
     * completion is observable before reconciliation hides it.
     */
    @Test
    fun caseO_staleCompletionAfterRemoteMergeCannotRewriteRemoteContent() = runTest {
        val ctl = controller(para("a", "one"), para("b", "two"))
        val persister = FakePersister()
        val firstWrite = CompletableDeferred<Result<Unit>>()
        val secondWrite = CompletableDeferred<Result<Unit>>()
        persister.queuedGates += firstWrite
        persister.queuedGates += secondWrite
        val saves = FlowSaveCoordinator(ctl, persister).apply { bindTitle("Note") }
        saves.start(backgroundScope)

        ctl.mutate("edit a") { it.editText("a", "one", "LOCAL") }
        saves.requestSave()
        settle()
        assertEquals(1, persister.requests.size)
        val staleGeneration = persister.requests[0].baselineGeneration

        // A remote snapshot lands mid-write and advances the baseline.
        ctl.mergeRemote(doc(para("a", "one"), para("b", "REMOTE")))
        assertNotEquals("the merge must advance the baseline", staleGeneration, ctl.baselineGeneration())

        // Now the pre-merge write succeeds. It knew nothing about b=REMOTE.
        firstWrite.complete(Result.success(Unit))
        settle()

        assertEquals("the local edit survives", "LOCAL", ctl.document().blocks.first { it.id == "a" }.plainText())
        assertEquals("the remote change survives", "REMOTE", ctl.document().blocks.first { it.id == "b" }.plainText())
        assertEquals(
            "the stale completion must not baseline a snapshot the remote merge superseded — doing so makes " +
                "the remote's own block look like a local edit and sends it back",
            setOf("a"),
            ctl.dirtyBlockIds(),
        )
        assertEquals(
            "so the follow-up write carries only the genuinely-unsaved local block",
            setOf("a"),
            persister.requests[1].dirtyBlockIds,
        )

        secondWrite.complete(Result.success(Unit))
        settle()
        assertFalse("and reconciliation settles it", ctl.hasUnsavedChanges())
    }

    @Test
    fun caseP_cleanRemoteUpdateDoesNotBecomeALocalEdit() {
        val ctl = controller(para("a", "old"), para("b", "old"))
        ctl.mergeRemote(doc(para("a", "old"), para("b", "REMOTE")))

        assertEquals("REMOTE", ctl.document().blocks.first { it.id == "b" }.plainText())
        assertTrue("an untouched remote change is not a local edit", ctl.dirtyBlockIds().isEmpty())
    }

    @Test
    fun caseQ_localDirtyBlockSurvivesRemoteMergeAndRemainsDirty() {
        // The defect: `lastSavedDocument = merged` baselined the merge result, which *contains* the protected
        // local edit — so `a` went clean without ever having been written, and a process death lost it.
        val ctl = controller(para("a", "old"), para("b", "old"))
        ctl.mutate("edit a") { it.editText("a", "old", "LOCAL") }

        ctl.mergeRemote(doc(para("a", "old"), para("b", "REMOTE")))

        assertEquals("LOCAL", ctl.document().blocks.first { it.id == "a" }.plainText())
        assertEquals("REMOTE", ctl.document().blocks.first { it.id == "b" }.plainText())
        assertEquals("the protected local edit is still unsaved", setOf("a"), ctl.dirtyBlockIds())
    }

    @Test
    fun caseR_localDeletionSurvivesRemoteMergeAndRemainsPending() {
        val ctl = controller(para("a", "one"), para("b", "two"))
        ctl.mutate("delete b") { it.delete("b") }

        // Remote still has b — it does not know about the deletion yet.
        ctl.mergeRemote(doc(para("a", "one"), para("b", "two")))

        assertEquals("b must not be resurrected", listOf("a"), ctl.document().blocks.map { it.id })
        assertEquals("and its deletion must still be pending", setOf("b"), ctl.deletedBlockIds())
    }

    @Test
    fun caseS_remoteSnapshotMatchingLocalDirtyContentClearsThatDirtyState() {
        val ctl = controller(para("a", "old"))
        ctl.mutate("edit a") { it.editText("a", "old", "NEW") }
        assertEquals(setOf("a"), ctl.dirtyBlockIds())

        // Remote now genuinely carries the same content — the editor must stop resaving it forever.
        ctl.mergeRemote(doc(para("a", "NEW")))

        assertTrue("matching remote content is real evidence of persistence", ctl.dirtyBlockIds().isEmpty())
        assertFalse(ctl.hasUnsavedChanges())
    }

    // ── T–W: serialization, disposal, and what counts as evidence ───────────────────────────────────────

    @Test
    fun caseT_onlyOneWriteIsEverInFlight() = runTest {
        val ctl = controller(para("a", "one"), para("b", "two"))
        val persister = FakePersister()
        val gate = CompletableDeferred<Result<Unit>>()
        persister.gate = gate
        val saves = FlowSaveCoordinator(ctl, persister).apply { bindTitle("Note") }
        saves.start(backgroundScope)

        ctl.mutate("edit a") { it.editText("a", "one", "ONE") }
        saves.requestSave()
        settle()

        // Hammer the coordinator with edits and renames while a write is open.
        repeat(20) { i ->
            val before = ctl.document().blocks.first { it.id == "b" }.plainText()
            ctl.mutate("edit b $i") { it.editText("b", before, "two-$i") }
            saves.onTitleChanged("T$i")
            saves.requestSave()
        }
        settle()
        assertEquals("no write may start while one is open", 1, persister.maxConcurrent)

        persister.gate = null
        gate.complete(Result.success(Unit))
        settle()
        assertEquals("still serialized once it drains", 1, persister.maxConcurrent)
        assertFalse(saves.hasPendingWork())
    }

    @Test
    fun caseU_exitDuringDebounceRequestsAnImmediateFlush() = runTest {
        val ctl = controller(para("a", "one"))
        val persister = FakePersister()
        val saves = FlowSaveCoordinator(ctl, persister).apply { bindTitle("Note") }
        saves.start(backgroundScope)

        ctl.mutate("edit") { it.editText("a", "one", "ONE") }
        saves.requestSave()
        // Well inside the debounce window: nothing has been written yet.
        settle(200L)
        assertEquals(0, persister.requests.size)

        saves.flush()
        settle()

        assertEquals("closing the editor must not wait out the debounce", 1, persister.requests.size)
        assertFalse(ctl.hasUnsavedChanges())
    }

    @Test
    fun caseV_exitDuringRetryDoesNotMarkUnsavedDataSaved() = runTest {
        val ctl = controller(para("a", "one"))
        val persister = FakePersister().apply { failures = Int.MAX_VALUE }
        val saves = FlowSaveCoordinator(ctl, persister).apply { bindTitle("Note") }
        saves.start(backgroundScope)

        ctl.mutate("edit") { it.editText("a", "one", "ONE") }
        saves.onTitleChanged("Renamed")
        settle(FlowSaveCoordinator.DEFAULT_DEBOUNCE_MS + 1_500L)
        assertEquals(FlowSaveCoordinator.State.RETRY_WAIT, saves.state)

        saves.flush()
        settle(2_000L)

        assertEquals("nothing was accepted, so nothing may be baselined", setOf("a"), ctl.dirtyBlockIds())
        assertTrue(saves.hasPendingWork())

        // The retry loop survived the flush and still lands the work when the backend recovers — which is the
        // whole reason it is owned by the ViewModel's scope rather than the composition's.
        persister.failures = 0
        settle()
        assertFalse(saves.hasPendingWork())
    }

    @Test
    fun caseW_repositoryFailureIsObservedNotInferred() = runTest {
        val ctl = controller(para("a", "one"))
        // A persister that throws rather than returning a failed Result — the shape a repository exception
        // takes. `join()` on a Job would report this as ordinary completion.
        val thrower = object : FlowPersister {
            var calls = 0
            override suspend fun persist(request: FlowSaveRequest): Result<Unit> {
                calls++
                if (calls == 1) throw IllegalStateException("Room write failed")
                return Result.success(Unit)
            }
        }
        val saves = FlowSaveCoordinator(ctl, thrower).apply { bindTitle("Note") }
        saves.start(backgroundScope)

        ctl.mutate("edit") { it.editText("a", "one", "ONE") }
        saves.requestSave()
        settle()

        assertEquals("the throw must be treated as failure and retried", 2, thrower.calls)
        assertFalse(ctl.hasUnsavedChanges())
    }

    /**
     * A repository write echoing back as a remote snapshot must converge, not start a save/merge/dirty cycle.
     * Case 5.9.
     */
    @Test
    fun remoteEchoOfOurOwnWriteDoesNotLoop() = runTest {
        val ctl = controller(para("a", "one"))
        val persister = FakePersister()
        val saves = FlowSaveCoordinator(ctl, persister).apply { bindTitle("Note") }
        saves.start(backgroundScope)

        ctl.mutate("edit") { it.editText("a", "one", "ONE") }
        saves.requestSave()
        settle()
        val afterFirstSave = persister.requests.size

        // The write lands and comes back as an activeDocument emission, identical to what we sent.
        repeat(3) {
            ctl.mergeRemote(persister.requests.last().document)
            saves.requestSave()
            settle()
        }

        assertEquals("an echo of our own write must not schedule more writes", afterFirstSave, persister.requests.size)
        assertFalse(ctl.hasUnsavedChanges())
    }

    /**
     * The loop must not park forever on the ViewModel's scope once the document has been closed — that would
     * leak one idle coroutine per note ever opened. It drains first (so a failing write's retry still outlives
     * the screen) and only then retires.
     */
    @Test
    fun loopDrainsPendingWorkThenExitsAfterClose() = runTest {
        val ctl = controller(para("a", "one"))
        val persister = FakePersister().apply { failures = 2 }
        val saves = FlowSaveCoordinator(ctl, persister).apply { bindTitle("Note") }
        saves.start(backgroundScope)

        ctl.mutate("edit") { it.editText("a", "one", "ONE") }
        saves.close()
        settle()

        assertFalse("the retries still landed after the screen closed", ctl.hasUnsavedChanges())
        assertTrue("a drained, closed session is retired", saves.isRetired())

        // A later request cannot be served, because the loop has finished — which is exactly what closing means.
        ctl.mutate("edit again") { it.editText("a", "ONE", "TWO") }
        val before = persister.requests.size
        saves.requestSave()
        settle()
        assertEquals("a closed coordinator does not resurrect itself", before, persister.requests.size)
    }

    private fun FakePersister.attemptCount(): Int = requests.size
    /**
     * Runs pending work and lets [millis] of virtual time pass.
     *
     * `advanceUntilIdle()` is deliberately **not** used: since coroutines-test 1.8 it ignores `backgroundScope`
     * entirely, so an infinite background loop cannot hang a test — which means it would also never run the
     * coordinator's loop, and every assertion here would pass against a coordinator that does nothing.
     * `advanceTimeBy` + `runCurrent` do drive background work, so the default covers the debounce plus a
     * generous margin for the retries a test expects to complete.
     */
    private fun TestScope.settle(millis: Long = FlowSaveCoordinator.DEFAULT_DEBOUNCE_MS + 120_000L) {
        runCurrent()
        advanceTimeBy(millis)
        runCurrent()
    }
}
