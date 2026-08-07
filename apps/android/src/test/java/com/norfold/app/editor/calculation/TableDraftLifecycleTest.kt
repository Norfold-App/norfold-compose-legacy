package com.norfold.app.editor.calculation

import com.norfold.app.domain.BlockDocument
import com.norfold.app.domain.DocumentPageBlock
import com.norfold.app.domain.InlineText
import com.norfold.app.domain.plainText
import com.norfold.app.domain.SceneGeometry
import com.norfold.app.domain.SceneObject
import com.norfold.app.domain.SceneObjectKind
import com.norfold.app.domain.TableBlock
import com.norfold.app.domain.TableCell
import com.norfold.app.editor.document.DocumentHostRuntime
import com.norfold.app.editor.document.DocumentSceneController
import com.norfold.app.editor.document.LayoutRepresentation
import com.norfold.app.editor.document.WorkingIntent
import com.norfold.app.editor.flow.FlowEditController
import com.norfold.app.editor.flow.FlowHostRuntime
import com.norfold.app.editor.flow.FlowTableCursor
import com.norfold.app.editor.history.EditTarget
import com.norfold.app.editor.presentation.FormFactor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The table cell **draft** lifecycle in both editing hosts.
 *
 * The defect these cover is a specific one: both surfaces used to call their commit lambda from
 * `onValueChange`, so every keystroke was a write. Typing `=SUM(A1:A2)` produced eleven commits, most of them
 * of unparseable text — eleven history transactions, eleven full recalculations, and a validation warning
 * raised against a formula the user had not finished. The lifecycle below separates the three events that
 * were being conflated: address a cell (seed), type (record), finish (write once).
 *
 * The draft lives in the runtime rather than in a `remember`, and that is load-bearing rather than tidy: a
 * Compose-local draft is discarded by any recomposition the host cannot see — a reflow, a theme change, a
 * configuration change — and the field then re-seeds from the *committed* value, silently throwing away
 * everything typed since the last write.
 */
class TableDraftLifecycleTest {

    /** Headerless on purpose, so legacy row 0 is sheet row 0 and `A1`/`A2` name the two data cells. */
    private fun flowTable(id: String = "t") = TableBlock(
        id = id,
        rows = listOf(
            listOf(TableCell(listOf(InlineText("2"))), TableCell(listOf(InlineText("")))),
            listOf(TableCell(listOf(InlineText("3"))), TableCell(listOf(InlineText("")))),
        ),
    )

    private fun flowHost(block: TableBlock = flowTable()): FlowHostRuntime = FlowHostRuntime(
        controller = FlowEditController(EditTarget("note:draft"), BlockDocument(listOf(block))),
        initialFormFactor = FormFactor(400, 900),
    )

    private fun documentHost(
        block: TableBlock = flowTable("doc-table"),
        objectId: String = "obj",
    ): Pair<DocumentHostRuntime, String> {
        val page = DocumentPageBlock(
            id = "page",
            objects = listOf(
                SceneObject(
                    id = objectId,
                    kind = SceneObjectKind.Table,
                    geometry = SceneGeometry(0f, 0f, 320f, 200f),
                    content = block,
                ),
            ),
        ).normalized()
        val host = DocumentHostRuntime(
            DocumentSceneController(EditTarget("doc:draft"), BlockDocument(listOf(page))),
            FormFactor(800, 1_000),
        )
        host.scope.activePageId = page.id
        host.setRepresentation(LayoutRepresentation.ORIGINAL)
        host.setIntent(WorkingIntent.EDIT)
        assertTrue(host.beginContentEdit(objectId))
        return host to objectId
    }

    // ---- Flow -------------------------------------------------------------------------------------

    @Test fun addressingACellSeedsTheDraftFromStorage() {
        val host = flowHost()
        assertNull(host.tableDraft())
        host.setTableCursor(FlowTableCursor("t", 0, 0))
        assertEquals("2", host.tableDraft())
    }

    @Test fun typingRecordsTheDraftWithoutWritingOrConsumingHistory() {
        val host = flowHost()
        host.setTableCursor(FlowTableCursor("t", 0, 1))
        val before = host.controller.document()
        val undoBefore = host.uiState().canUndo
        "=A1+A2".forEachIndexed { index, _ ->
            assertTrue(host.updateTableDraft("=A1+A2".take(index + 1)))
        }
        assertEquals("=A1+A2", host.tableDraft())
        // The document is untouched: not one of the eleven keystrokes reached storage.
        assertEquals(before, host.controller.document())
        assertEquals(undoBefore, host.uiState().canUndo)
    }

    @Test fun anUnparseablePartialFormulaNeverReachesTheEngine() {
        val host = flowHost()
        host.setTableCursor(FlowTableCursor("t", 0, 1))
        // `=A1+` is a parse error. Under the old surface it was committed, so the cell briefly *held* an
        // error and the user was warned about text they were still typing.
        host.updateTableDraft("=A1+")
        val cell = (host.controller.document().blocks.single() as TableBlock).rows[0][1]
        assertEquals("", cell.content.plainText())
    }

    @Test fun commitWritesOnceAndReSeedsFromTheStoredSource() {
        val host = flowHost()
        host.setTableCursor(FlowTableCursor("t", 0, 1))
        host.updateTableDraft("=A1+A2")
        assertTrue(host.commitTableDraft().accepted)
        val stored = host.controller.document().blocks.single() as TableBlock
        // What the grid draws is the value; what the editor holds is the source. Confusing the two is how a
        // formula gets replaced by its own result on the next commit.
        assertEquals("5", stored.rows[0][1].content.plainText())
        assertEquals("=A1+A2", host.tableDraft())
    }

    @Test fun cancelRestoresTheCommittedTextAndKeepsTheDocumentIntact() {
        val host = flowHost()
        host.setTableCursor(FlowTableCursor("t", 0, 0))
        host.updateTableDraft("999")
        val before = host.controller.document()
        assertTrue(host.cancelTableDraft())
        assertEquals("2", host.tableDraft())
        assertEquals(before, host.controller.document())
    }

    @Test fun movingToAnotherCellDiscardsTheDraftRatherThanWritingIt() {
        val host = flowHost()
        host.setTableCursor(FlowTableCursor("t", 0, 0))
        host.updateTableDraft("999")
        host.setTableCursor(FlowTableCursor("t", 1, 0))
        // Re-addressing the first cell shows what is stored, not the abandoned text.
        host.setTableCursor(FlowTableCursor("t", 0, 0))
        assertEquals("2", host.tableDraft())
    }

    @Test fun aDraftWithNoAddressedCellIsRefusedRatherThanBuffered() {
        val host = flowHost()
        assertFalse(host.updateTableDraft("stray"))
        assertNull(host.tableDraft())
        assertFalse(host.commitTableDraft().accepted)
    }

    @Test fun aRefusedCommitKeepsTheDraftSoTheUserCanCorrectIt() {
        val host = flowHost()
        val cursor = FlowTableCursor("t", 0, 1)
        host.setTableCursor(cursor)
        // A self-reference is a circular formula: refused, and the typed text must survive the refusal.
        host.updateTableDraft("=B1")
        val result = host.commitTableDraft()
        if (!result.accepted) assertEquals("=B1", host.tableDraft())
    }

    @Test fun oneCommitIsOneUndoStepRegardlessOfHowManyKeystrokesPrecededIt() {
        val host = flowHost()
        host.setTableCursor(FlowTableCursor("t", 0, 1))
        val baseline = host.controller.document()
        "=1+1".forEachIndexed { index, _ -> host.updateTableDraft("=1+1".take(index + 1)) }
        assertTrue(host.commitTableDraft().accepted)
        assertNotEquals(baseline, host.controller.document())
        assertTrue(host.undo())
        // A single undo returns the whole cell edit, rather than peeling off one keystroke of it.
        assertEquals(
            baseline.blocks.single().let { (it as TableBlock).rows[0][1].content.plainText() },
            (host.controller.document().blocks.single() as TableBlock).rows[0][1].content.plainText(),
        )
    }

    // ---- Document ---------------------------------------------------------------------------------

    @Test fun documentAddressingSeedsAndTypingDoesNotWrite() {
        val (host, objectId) = documentHost()
        assertNull(host.editingTableDraft)
        assertTrue(host.setObjectTableCell(objectId, 0, 0))
        assertEquals("2", host.editingTableDraft)
        val before = host.controller.document()
        host.updateObjectTableDraft("=A1+")
        host.updateObjectTableDraft("=A1+A2")
        assertEquals("=A1+A2", host.editingTableDraft)
        assertEquals(before, host.controller.document())
    }

    @Test fun documentCommitWritesOnceAndReSeeds() {
        val (host, objectId) = documentHost()
        assertTrue(host.setObjectTableCell(objectId, 0, 1))
        host.updateObjectTableDraft("=A1+A2")
        assertTrue(host.commitObjectTableDraft().accepted)
        val stored = host.controller.findObject(objectId)?.second?.content as TableBlock
        assertEquals("5", stored.rows[0][1].content.plainText())
        assertEquals("=A1+A2", host.editingTableDraft)
    }

    @Test fun documentCellRefusesWhenTheObjectIsNotInContentEditing() {
        val (host, objectId) = documentHost()
        host.endContentEdit()
        assertFalse(host.setObjectTableCell(objectId, 0, 0))
        assertNull(host.editingTableCell)
        assertNull(host.editingTableDraft)
    }

    @Test fun leavingContentEditingDiscardsTheDraftRatherThanWritingIt() {
        val (host, objectId) = documentHost()
        assertTrue(host.setObjectTableCell(objectId, 0, 0))
        host.updateObjectTableDraft("999")
        val before = host.controller.document()
        host.endContentEdit()
        assertNull(host.editingTableCell)
        assertNull(host.editingTableDraft)
        assertEquals(before, host.controller.document())
    }

    @Test fun documentCancelRestoresTheCommittedText() {
        val (host, objectId) = documentHost()
        assertTrue(host.setObjectTableCell(objectId, 1, 0))
        host.updateObjectTableDraft("nonsense")
        assertTrue(host.cancelObjectTableDraft())
        assertEquals("3", host.editingTableDraft)
    }

    @Test fun theDraftSurvivesAReflowThatWouldHaveDestroyedComposeLocalState() {
        val (host, objectId) = documentHost()
        assertTrue(host.setObjectTableCell(objectId, 0, 1))
        host.updateObjectTableDraft("=1+1")
        // A form-factor change re-presents every open surface. A `remember`-held draft would not survive it.
        host.onFormFactorChanged(FormFactor(1_200, 800))
        assertEquals("=1+1", host.editingTableDraft)
        assertEquals(objectId to (0 to 1), host.editingTableCell)
    }

    @Test fun theFlowDraftSurvivesAReflowToo() {
        val host = flowHost()
        host.setTableCursor(FlowTableCursor("t", 0, 1))
        host.updateTableDraft("=1+1")
        host.onFormFactorChanged(FormFactor(1_200, 800))
        assertEquals("=1+1", host.tableDraft())
    }
}
