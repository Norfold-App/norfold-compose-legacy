package com.norfold.app.editor.flow

import com.norfold.app.domain.BlockDocument
import com.norfold.app.domain.DocumentBlock
import com.norfold.app.domain.InlineText
import com.norfold.app.domain.ParagraphBlock
import com.norfold.app.editor.history.EditTarget
import com.norfold.app.editor.presentation.FormFactor
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Session ownership and title lifecycle.
 *
 * Two defects are pinned here, both of which the persistence state machine alone could not prevent because
 * both live *above* it — in who owns a coordinator and who owns a title:
 *
 *  - the coordinator was built in a Compose `remember` keyed by document id, but its retry loop runs on the
 *    ViewModel's scope. A screen recreation during a retry therefore built a **second** coordinator, with its
 *    own controller and its own baseline, writing the same document concurrently with the first. "One writer
 *    per document" held only inside a single instance — precisely the case recreation breaks;
 *  - the visible title was `rememberSaveable` while the coordinator was seeded from the stored row. After a
 *    process recreation those two disagreed: the screen showed the restored rename while persistence believed
 *    the older stored title was both desired and persisted, so the rename was never written — or worse, the
 *    stale stored value was.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FlowSessionRegistryTest {

    private fun para(id: String, text: String = ""): ParagraphBlock =
        ParagraphBlock(id = id, content = if (text.isEmpty()) emptyList() else listOf(InlineText(text)))

    private fun doc(vararg blocks: DocumentBlock) = BlockDocument(blocks.toList())

    /** Counts writes per document so "one writer" can be asserted across instances, not just within one. */
    private class SharedPersister : FlowPersister {
        val requests = mutableListOf<FlowSaveRequest>()
        var gate: CompletableDeferred<Result<Unit>>? = null
        var failures: Int = 0
        private var concurrent = 0
        var maxConcurrent = 0
            private set

        override suspend fun persist(request: FlowSaveRequest): Result<Unit> {
            requests += request
            concurrent++
            maxConcurrent = maxOf(maxConcurrent, concurrent)
            try {
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

    /**
     * Builds a session the way the editor screen does, over a persister the test owns.
     *
     * Every session for one document shares the persister, which is what makes a second writer visible: two
     * coordinators each believing they are alone still both land here.
     */
    private fun session(
        documentId: String,
        document: BlockDocument,
        title: String,
        persister: FlowPersister,
    ): FlowDocumentSession {
        val runtime = FlowHostRuntime(
            controller = FlowEditController(EditTarget(documentId), document).apply { markSaved() },
            initialFormFactor = FormFactor(400, 800),
        )
        return FlowDocumentSession(
            runtime = runtime,
            saves = FlowSaveCoordinator(runtime.controller, persister).apply { bindTitle(title) },
        )
    }

    // ── Session ownership ────────────────────────────────────────────────────────────────────────────────

    @Test
    fun recreatingTheScreenReusesTheSameSaveOwner() = runTest {
        val registry = FlowSessionRegistry()
        val persister = SharedPersister()
        var built = 0

        val first = registry.open("note:1", backgroundScope) {
            built++
            session("note:1", doc(para("a", "one")), "Note", persister)
        }
        // The screen is disposed (rotation) and recomposed. Disposal flushes; it does not end the session.
        first.saves.flush()
        val second = registry.open("note:1", backgroundScope) {
            built++
            session("note:1", doc(para("a", "one")), "Note", persister)
        }

        assertSame("recreation must rebind to the live session, not build a second one", first, second)
        assertEquals("no second coordinator was constructed", 1, built)
        assertEquals(1, registry.size())
    }

    @Test
    fun disposalDoesNotEndTheSessionEvenAfterEverythingPendingHasLanded() = runTest {
        val registry = FlowSessionRegistry()
        val persister = SharedPersister()

        val first = registry.open("note:1", backgroundScope) {
            session("note:1", doc(para("a", "one")), "Note", persister)
        }
        first.runtime.controller.mutate("edit") { it.editText("a", "one", "ONE") }

        // Disposal, then enough time for the flush to be accepted before the screen is recomposed — the
        // ordering a slow recreation actually produces. Disposal is not a close, so the session must survive
        // having nothing left to do; retiring here is what lets the recomposition build a rival writer.
        first.saves.flush()
        settle()
        assertFalse("a disposed-but-not-closed session stays alive", first.saves.isRetired())
        assertEquals(1, registry.size())

        val second = registry.open("note:1", backgroundScope) {
            session("note:1", doc(para("a", "one")), "Note", persister)
        }
        assertSame(first, second)
    }

    @Test
    fun recreationDuringARetryDoesNotCreateASecondWriter() = runTest {
        val registry = FlowSessionRegistry()
        // Never succeeds while the "outage" lasts, so the first session stays in RETRY_WAIT — the window in
        // which a rotation used to spawn a rival writer.
        val persister = SharedPersister().apply { failures = Int.MAX_VALUE }

        val first = registry.open("note:1", backgroundScope) {
            session("note:1", doc(para("a", "one")), "Note", persister)
        }
        first.runtime.controller.mutate("edit") { it.editText("a", "one", "FIRST") }
        first.saves.requestSave()
        settle(FlowSaveCoordinator.DEFAULT_DEBOUNCE_MS + 1_500L)
        assertEquals(FlowSaveCoordinator.State.RETRY_WAIT, first.saves.state)

        first.saves.flush()
        val second = registry.open("note:1", backgroundScope) {
            session("note:1", doc(para("a", "one")), "Note", persister)
        }
        assertSame("a retrying session still owns the document", first, second)

        // The backend recovers. Exactly one writer lands the edit; a rival would write its own (empty) baseline.
        persister.failures = 0
        settle()
        assertEquals(1, persister.maxConcurrent)
        assertFalse(first.runtime.controller.hasUnsavedChanges())
        assertEquals("FIRST", persister.requests.last().document.blocks.single().let { it as ParagraphBlock }
            .content.filterIsInstance<InlineText>().joinToString("") { it.value })
    }

    @Test
    fun aRetiredSessionIsReplacedAndCannotWriteAgain() = runTest {
        val registry = FlowSessionRegistry()
        val persister = SharedPersister()

        val first = registry.open("note:1", backgroundScope) {
            session("note:1", doc(para("a", "one")), "Note", persister)
        }
        // The document is genuinely left: drain, then retire.
        registry.close("note:1")
        settle()
        assertTrue(first.saves.isRetired())
        assertEquals("a retired session drops out of the registry", 0, registry.size())

        val second = registry.open("note:1", backgroundScope) {
            session("note:1", doc(para("a", "one")), "Note", persister)
        }
        assertNotSame("reopening after close builds a fresh session", first, second)

        // The retired one is inert, so the two can never interleave writes for the same document.
        first.runtime.controller.mutate("stale edit") { it.editText("a", "one", "STALE") }
        first.saves.requestSave()
        second.runtime.controller.mutate("live edit") { it.editText("a", "one", "LIVE") }
        second.saves.requestSave()
        settle()

        assertEquals(1, persister.maxConcurrent)
        assertTrue(
            "an old session must not be able to overwrite a newer one's save",
            persister.requests.none { request ->
                request.document.blocks.any { it.plainTextOfParagraph() == "STALE" }
            },
        )
    }

    @Test
    fun openingASecondDocumentDoesNotDisturbTheFirst() = runTest {
        val registry = FlowSessionRegistry()
        val persister = SharedPersister()

        val one = registry.open("note:1", backgroundScope) {
            session("note:1", doc(para("a", "one")), "One", persister)
        }
        val two = registry.open("note:2", backgroundScope) {
            session("note:2", doc(para("b", "two")), "Two", persister)
        }
        assertNotSame(one, two)
        assertEquals(2, registry.size())

        registry.closeAllExcept("note:2")
        settle()
        assertTrue("leaving a document ends its session", one.saves.isRetired())
        assertFalse("the document still being edited keeps its writer", two.saves.isRetired())
    }

    // ── Title lifecycle ──────────────────────────────────────────────────────────────────────────────────

    @Test
    fun restoredLocalTitleSurvivesRecreationAndIsNotRevertedByTheOlderStoredTitle() = runTest {
        val registry = FlowSessionRegistry()
        // The write is held open, so the rename is pending exactly as it is mid-save during a rotation.
        val gate = CompletableDeferred<Result<Unit>>()
        val persister = SharedPersister().apply { this.gate = gate }

        val opened = registry.open("note:1", backgroundScope) {
            session("note:1", doc(para("a", "one")), "Old title", persister)
        }
        opened.saves.onTitleChanged("New title")
        settle()

        // Recreation: the screen is rebuilt and re-reports the stored row, which still holds the OLD title
        // because the write has not landed.
        opened.saves.flush()
        val reopened = registry.open("note:1", backgroundScope) {
            session("note:1", doc(para("a", "one")), "Old title", persister)
        }
        reopened.saves.onStoredTitle("Old title")

        assertSame(opened, reopened)
        assertEquals("a recreation must not revert a pending rename", "New title", reopened.saves.title())
        assertTrue(reopened.saves.isTitleDirty())

        gate.complete(Result.success(Unit))
        settle()
        assertEquals("New title", persister.requests.last().title)
    }

    @Test
    fun incomingStoredTitleIsAdoptedWhenNoRenameIsPending() = runTest {
        val persister = SharedPersister()
        val ctl = FlowEditController(EditTarget("note:1"), doc(para("a", "one"))).apply { markSaved() }
        val saves = FlowSaveCoordinator(ctl, persister).apply { bindTitle("Note") }
        saves.start(backgroundScope)

        // Renamed on another device, or by the notes list. Nothing local is pending, so it is simply adopted.
        saves.onStoredTitle("Renamed elsewhere")
        settle()

        assertEquals("Renamed elsewhere", saves.title())
        assertFalse(saves.isTitleDirty())
        assertEquals("adopting a stored title is not an edit and must not write", 0, persister.requests.size)
    }

    @Test
    fun incomingStoredTitleDoesNotClobberAPendingLocalRename() = runTest {
        val gate = CompletableDeferred<Result<Unit>>()
        val persister = SharedPersister().apply { this.gate = gate }
        val ctl = FlowEditController(EditTarget("note:1"), doc(para("a", "one"))).apply { markSaved() }
        val saves = FlowSaveCoordinator(ctl, persister).apply { bindTitle("Note") }
        saves.start(backgroundScope)

        saves.onTitleChanged("Local rename")
        settle()
        // A remote/stored title arrives while the local rename is still unaccepted. Local-dirty wins, exactly
        // as it does for block content — the alternative silently discards something the user typed.
        saves.onStoredTitle("Remote rename")

        assertEquals("Local rename", saves.title())
        assertTrue("the rename is still owed to the repository", saves.isTitleDirty())

        gate.complete(Result.success(Unit))
        settle()
        assertEquals("Local rename", persister.requests.last().title)
        assertFalse(saves.isTitleDirty())
    }

    @Test
    fun titleChangedDuringAnInFlightSaveIsNotMarkedSavedByThatWrite() = runTest {
        val gate = CompletableDeferred<Result<Unit>>()
        val persister = SharedPersister().apply { this.gate = gate }
        val ctl = FlowEditController(EditTarget("note:1"), doc(para("a", "one"))).apply { markSaved() }
        val saves = FlowSaveCoordinator(ctl, persister).apply { bindTitle("Note") }
        saves.start(backgroundScope)

        saves.onTitleChanged("First")
        settle()
        assertEquals(1, persister.requests.size)
        assertEquals("First", persister.requests[0].title)

        // Typed while the first write is still in flight: the completion describes "First", not this.
        saves.onTitleChanged("Second")
        gate.complete(Result.success(Unit))
        persister.gate = null
        settle()

        assertEquals("the newer title must get its own write", 2, persister.requests.size)
        assertEquals("Second", persister.requests[1].title)
        assertFalse(saves.isTitleDirty())
        assertEquals(1, persister.maxConcurrent)
    }

    @Test
    fun staleCompletionCannotMarkANewerTitleSaved() = runTest {
        val gate = CompletableDeferred<Result<Unit>>()
        val persister = SharedPersister().apply { this.gate = gate }
        val ctl = FlowEditController(EditTarget("note:1"), doc(para("a", "one"))).apply { markSaved() }
        val saves = FlowSaveCoordinator(ctl, persister).apply { bindTitle("Note") }
        saves.start(backgroundScope)

        saves.onTitleChanged("Pending")
        settle()

        // A remote document snapshot lands while that write is open, moving the baseline. The completion is now
        // measured against a baseline that no longer exists, so it may not declare anything persisted —
        // including the title it carried.
        ctl.mergeRemote(doc(para("a", "one"), para("b", "remote")))

        // The follow-up write is held open too, so the state the stale completion left is observable rather
        // than immediately reconciled away by the retry it triggers.
        val second = CompletableDeferred<Result<Unit>>()
        persister.gate = second
        gate.complete(Result.success(Unit))
        settle(0L)

        assertTrue("a stale completion cannot baseline the title it carried", saves.isTitleDirty())
        assertEquals("the title is re-sent against the current baseline", 2, persister.requests.size)
        assertEquals("Pending", persister.requests[1].title)

        second.complete(Result.success(Unit))
        persister.gate = null
        settle()
        assertFalse(saves.isTitleDirty())
    }

    private fun DocumentBlock.plainTextOfParagraph(): String =
        (this as? ParagraphBlock)?.content?.filterIsInstance<InlineText>()?.joinToString("") { it.value } ?: ""

    /** See `FlowSaveCoordinatorTest.settle` — `advanceUntilIdle()` ignores `backgroundScope`. */
    private fun TestScope.settle(millis: Long = FlowSaveCoordinator.DEFAULT_DEBOUNCE_MS + 120_000L) {
        runCurrent()
        advanceTimeBy(millis)
        runCurrent()
    }
}
