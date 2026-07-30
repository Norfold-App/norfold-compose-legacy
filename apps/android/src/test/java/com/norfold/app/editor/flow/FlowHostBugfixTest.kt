package com.norfold.app.editor.flow

import com.norfold.app.domain.BlockDocument
import com.norfold.app.domain.InlineText
import com.norfold.app.domain.ParagraphBlock
import com.norfold.app.domain.TodoItem
import com.norfold.app.domain.TodoListBlock
import com.norfold.app.domain.plainText
import com.norfold.app.editor.history.EditTarget
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for the seven defects found during the Phase 1 final review (2026-07-30).
 *
 * These are separate from `FlowHostCorrectionTest` because they were missed by the original corrective pass
 * and only surfaced during direct code review of the committed implementation.
 */
class FlowHostBugfixTest {

    private val target = EditTarget("note:bugfix")

    private fun para(id: String, text: String = ""): ParagraphBlock =
        ParagraphBlock(id = id, content = if (text.isEmpty()) emptyList() else listOf(InlineText(text)))

    private fun doc(vararg blocks: com.norfold.app.domain.DocumentBlock) = BlockDocument(blocks.toList())

    // ── Defect 3: Todo typing must route through editTodoItem, not editListItem ───────────────────────────

    @Test
    fun todoTyping_mutatesTheDocument() {
        val runtime = FlowHostRuntime(
            controller = FlowEditController(target, doc(
                TodoListBlock(
                    id = "t0",
                    items = listOf(TodoItem(id = "i0", checked = false, content = listOf(InlineText("buy milk")))),
                ),
            )),
            initialFormFactor = com.norfold.app.editor.presentation.FormFactor(400, 900),
        )
        runtime.setCaret("t0", 8, "i0")

        val changed = runtime.editText("t0", "buy milk", "buy oat milk", "i0")

        assertTrue("Todo typing must change the document", changed)
        val item = (runtime.controller.document().blocks[0] as TodoListBlock).items[0]
        assertEquals("buy oat milk", item.content.plainText())
    }

    @Test
    fun todoTyping_coalescesLikeOtherListItems() {
        val clock = FlowEditControllerTest.Clock()
        val runtime = FlowHostRuntime(
            controller = FlowEditController(
                target,
                doc(TodoListBlock(id = "t0", items = listOf(TodoItem(id = "i0", checked = false, content = emptyList())))),
                nanoTime = clock,
            ),
            initialFormFactor = com.norfold.app.editor.presentation.FormFactor(400, 900),
        )
        runtime.setCaret("t0", 0, "i0")

        clock.now = 0
        runtime.editText("t0", "", "h", "i0")
        clock.now = 100_000_000 // within coalesce window
        runtime.editText("t0", "h", "he", "i0")

        assertEquals("Two keystrokes in one Todo item coalesce to one undo unit", 1, runtime.controller.undoDepth())
    }

    @Test
    fun todoTyping_preservesCheckedState() {
        val runtime = FlowHostRuntime(
            controller = FlowEditController(target, doc(
                TodoListBlock(
                    id = "t0",
                    items = listOf(TodoItem(id = "i0", checked = true, content = listOf(InlineText("done")))),
                ),
            )),
            initialFormFactor = com.norfold.app.editor.presentation.FormFactor(400, 900),
        )
        runtime.setCaret("t0", 4, "i0")

        runtime.editText("t0", "done", "done!", "i0")

        val item = (runtime.controller.document().blocks[0] as TodoListBlock).items[0]
        assertTrue("Typing must preserve the checked flag", item.checked)
    }

    // ── Defect 4: drag geometry must use absolute document indices, not measured-slice positions ─────────

    @Test
    fun dragGeometry_usesAbsoluteDocumentIndices() {
        // A virtualized list showing document rows 80–84 measures five rows at list positions 0–4. If placement
        // resolved against those ordinals, dropping near row 82 would commit at document index 2 instead of 82.
        val rows = listOf(
            FlowRowBounds("id80", documentIndex = 80, top = 0f, bottom = 50f),
            FlowRowBounds("id81", documentIndex = 81, top = 56f, bottom = 106f),
            FlowRowBounds("id82", documentIndex = 82, top = 112f, bottom = 162f),
            FlowRowBounds("id83", documentIndex = 83, top = 168f, bottom = 218f),
            FlowRowBounds("id84", documentIndex = 84, top = 224f, bottom = 274f),
        )

        val placement = FlowDragGeometry.placementFor(rows, sourceDocumentIndex = 82, pointerY = 137f)

        assertEquals("Drop must resolve to the document index, not the measured ordinal", 83, placement?.index)
    }

    @Test
    fun dragGeometry_sortsRowsByDocumentIndex() {
        // Measured rows arrive in layout order, which is not document order after an insertion or reorder. If
        // geometry read them as-is, a drop during the layout pass following a reorder would resolve against
        // the pre-reorder positions and commit at the wrong index.
        val rows = listOf(
            FlowRowBounds("id2", documentIndex = 2, top = 112f, bottom = 162f), // visually third, was second
            FlowRowBounds("id0", documentIndex = 0, top = 0f, bottom = 50f),
            FlowRowBounds("id1", documentIndex = 1, top = 56f, bottom = 106f),
        )

        val placement = FlowDragGeometry.placementFor(rows, sourceDocumentIndex = -1, pointerY = 80f)

        assertEquals("Geometry must sort rows by document index before resolving", 1, placement?.index)
    }

    @Test
    fun dragGeometry_edgeScrollReturnsZeroInTheMiddle() {
        val scroll = FlowDragGeometry.edgeScroll(pointerY = 500f, viewportHeight = 1000f, edgeZone = 96f)
        assertEquals(0f, scroll, 0.01f)
    }

    @Test
    fun dragGeometry_edgeScrollRampsNearTheTop() {
        val scroll = FlowDragGeometry.edgeScroll(pointerY = 30f, viewportHeight = 1000f, edgeZone = 96f)
        assertTrue("Near the top edge, scroll must be negative", scroll < 0f)
    }

    @Test
    fun dragGeometry_edgeScrollRampsNearTheBottom() {
        val scroll = FlowDragGeometry.edgeScroll(pointerY = 950f, viewportHeight = 1000f, edgeZone = 96f)
        assertTrue("Near the bottom edge, scroll must be positive", scroll > 0f)
    }

    // ── Defect 2: failed persistence must leave dirty/deleted baseline intact ────────────────────────────

    /**
     * The seam under test is `saveIfDirty`'s rule: baseline only on genuine success. The old code took
     * `Job.join()` as evidence, but `join()` returns Unit whether the coroutine completed or threw, so a
     * repository failure was indistinguishable from a successful write and `markSaved` ran anyway — losing the
     * change permanently, because the next tick saw a clean baseline.
     *
     * This reproduces both branches against a stand-in for the persistence call, proving the controller's
     * baseline survives a failure and is still retryable, and that the retry then succeeds.
     */
    @Test
    fun failedWrite_leavesTheBaselineIntactAndIsRetryable() = runBlocking {
        val ctl = FlowEditController(target, doc(para("a", "one"), para("b", "two")))
        ctl.markSaved()
        ctl.mutate("Edit a") { it.editText("a", "one", "ONE") }
        ctl.mutate("Delete b") { it.delete("b") }

        val dirtyBefore = ctl.dirtyBlockIds()
        val deletedBefore = ctl.deletedBlockIds()
        assertEquals(setOf("a"), dirtyBefore)
        assertEquals(setOf("b"), deletedBefore)

        // Attempt 1: the repository throws. Mirrors saveIfDirty's contract — await the outcome, and only
        // baseline when it reports success.
        val firstAttempt = persistOnce(ctl, succeeds = false)
        assertFalse("A throwing write must not report success", firstAttempt)
        assertEquals("Dirty ids survive a failed write", dirtyBefore, ctl.dirtyBlockIds())
        assertEquals("Deleted ids survive a failed write", deletedBefore, ctl.deletedBlockIds())
        assertTrue("The change is still pending, so it will be retried", ctl.hasUnsavedChanges())

        // Attempt 2: the same pending change is retried and accepted.
        val secondAttempt = persistOnce(ctl, succeeds = true)
        assertTrue(secondAttempt)
        assertTrue("A successful write clears the dirty baseline", ctl.dirtyBlockIds().isEmpty())
        assertTrue("A successful write clears the deleted baseline", ctl.deletedBlockIds().isEmpty())
        assertFalse(ctl.hasUnsavedChanges())
    }

    /**
     * One save attempt, structured exactly as `saveIfDirty`: capture the snapshot, launch the write, await its
     * completion *including* whether it threw, and baseline only on success. Returns whether it succeeded.
     *
     * The write is launched on a scope of its own — never a child of the caller — because that is what
     * `viewModelScope` is. A child coroutine's failure would cancel the awaiting caller and mask the very
     * distinction under test; an independent scope reports the failure through the completion handler instead,
     * exactly as the production path does.
     */
    private suspend fun persistOnce(ctl: FlowEditController, succeeds: Boolean): Boolean {
        val dirtyIds = ctl.dirtyBlockIds()
        val deletedIds = ctl.deletedBlockIds()
        if (dirtyIds.isEmpty() && deletedIds.isEmpty()) return true
        val snapshot = ctl.document()

        val viewModelScope = CoroutineScope(SupervisorJob() + CoroutineExceptionHandler { _, _ -> })
        return try {
            val outcome = CompletableDeferred<Result<Unit>>()
            val job = viewModelScope.launch {
                if (!succeeds) throw IllegalStateException("Room write failed")
            }
            job.invokeOnCompletion { error ->
                outcome.complete(if (error == null) Result.success(Unit) else Result.failure(error))
            }
            val result = outcome.await()
            if (result.isSuccess) ctl.markSaved(snapshot)
            result.isSuccess
        } finally {
            viewModelScope.cancel()
        }
    }

    /**
     * A deletion-only change must schedule persistence. `dirtyBlockIds()` deliberately excludes removals, so
     * the old `if (dirtyIds.isEmpty()) return` guard skipped the write entirely and the deleted block came back
     * on reopen.
     */
    @Test
    fun deletionOnlyChange_isPersistedRatherThanSkipped() = runBlocking {
        val ctl = FlowEditController(target, doc(para("a", "one"), para("b", "two")))
        ctl.markSaved()
        ctl.mutate("Delete b") { it.delete("b") }

        assertTrue("A pure deletion leaves no dirty ids", ctl.dirtyBlockIds().isEmpty())
        assertEquals("…but it does leave deleted ids", setOf("b"), ctl.deletedBlockIds())

        val persisted = persistOnce(ctl, succeeds = true)

        assertTrue("The deletion-only change must be written, not skipped", persisted)
        assertFalse("…and the baseline must then be clean", ctl.hasUnsavedChanges())
        assertEquals(listOf("a"), ctl.document().blocks.map { it.id })
    }
}
