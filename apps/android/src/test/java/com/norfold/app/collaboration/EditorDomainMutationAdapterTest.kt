package com.norfold.app.collaboration

import com.norfold.app.collaboration.editor.EditorDomainMutationAdapter
import com.norfold.app.domain.BlockDocument
import com.norfold.app.domain.ImageBlock
import com.norfold.app.domain.InlineText
import com.norfold.app.domain.UnknownBlock
import com.norfold.app.domain.style.ColorValue
import com.norfold.app.editor.chart.ChartDocument
import com.norfold.app.editor.chart.ChartInteraction
import com.norfold.app.editor.calculation.CalculationCell
import com.norfold.app.editor.calculation.CalculationWorkbook
import com.norfold.app.editor.calculation.CellInput
import com.norfold.app.editor.history.EditOperation
import com.norfold.app.editor.history.EditTarget
import com.norfold.app.editor.history.EditTransaction
import com.norfold.app.editor.image.ImageEditDocument
import com.norfold.app.editor.relationship.Comment
import com.norfold.app.editor.relationship.CommentAuthorRef
import com.norfold.app.editor.relationship.CommentId
import com.norfold.app.editor.relationship.ThreadId
import org.junit.Assert.*
import org.junit.Test
import kotlinx.serialization.json.JsonPrimitive

class EditorDomainMutationAdapterTest {
    private val adapter = EditorDomainMutationAdapter()

    @Test fun `unknown block payload survives collaboration mapping`() {
        val unknown = UnknownBlock(id = "unknown-1", rawJson = "{\"futureKind\":{\"value\":7}}")
        val transaction = EditTransaction(
            "tx", "Insert future block",
            listOf(EditOperation.InsertBlock(EditTarget("note"), 0, unknown)),
        )
        val encoded = adapter.history(transaction).operations.single().payload.toString()
        assertTrue(encoded.contains("futureKind"))
        assertTrue(encoded.contains("unknown-1"))
    }

    @Test fun `chart collaboration contains canonical document not rendered geometry`() {
        val mutation = adapter.chart(ChartDocument(
            id = "chart-1",
            interaction = ChartInteraction(selectedSeriesId = "series-1", selectedSourceRowIndex = 0),
        ))
        assertEquals("chart", mutation.domain)
        assertTrue(mutation.payload.containsKey("document"))
        assertFalse(mutation.payload.containsKey("renderGeometry"))
        assertFalse(mutation.payload.toString().contains("selectedSeriesId"))
    }

    @Test fun `image collaboration contains committed edit and marks local asset unresolved`() {
        val mutation = adapter.committedImage(
            ImageBlock(id = "image-1", source = "content://device/private/1"),
            ImageEditDocument(),
        )
        assertEquals("missing_remote", mutation.payload["assetState"]?.toString()?.trim('"'))
        assertFalse(mutation.payload.toString().contains("content://"))
        assertTrue(mutation.payload.containsKey("edit"))
    }

    @Test fun `style adapter uses canonical color channels`() {
        val mutation = adapter.styleColor("token-1", ColorValue(1, 2, 3, 4))
        assertTrue(mutation.payload.toString().contains("\"r\":1"))
        assertEquals("style", mutation.domain)
    }

    @Test fun `spreadsheet collaboration carries source formula and no calculated result`() {
        val workbook = CalculationWorkbook()
        val sheet = workbook.activeSheet()
        val cell = CalculationCell(
            rowId = sheet.rows.first().id,
            columnId = sheet.columns.first().id,
            input = CellInput.Formula("=1+1"),
        )
        val mutation = adapter.spreadsheetCell(workbook, sheet, cell)
        assertTrue(mutation.payload.toString().contains("=1+1"))
        assertFalse(mutation.payload.toString().contains("calculated"))
    }

    @Test fun `discussion reply uses stable comment identity instead of replacing thread blob`() {
        val comment = Comment(
            id = CommentId("comment-1"), threadId = ThreadId("thread-1"),
            author = CommentAuthorRef("user-1", "Alex"), createdAt = 1, body = "Reply",
        )
        val mutation = adapter.discussionComment(comment)
        assertEquals("comment.insert", mutation.action)
        assertEquals("thread-1", mutation.targetId)
        assertEquals("comment-1", mutation.payload["commentId"]?.toString()?.trim('"'))
    }

    @Test fun `concurrent text protocol uses stable insertion and range anchors`() {
        val insert = adapter.insertText("block-1", "insert-1", "anchor-0", listOf(InlineText("Hello")))
        val delete = adapter.deleteText("block-1", "anchor-1", "anchor-4")
        val mark = adapter.markText("block-1", "anchor-1", "anchor-4", "bold", JsonPrimitive(true))

        assertEquals("text.insert", insert.action)
        assertEquals("insert-1", insert.payload["itemId"]?.toString()?.trim('"'))
        assertEquals("anchor-0", insert.payload["afterAnchor"]?.toString()?.trim('"'))
        assertEquals("anchor-1", delete.payload["fromAnchor"]?.toString()?.trim('"'))
        assertEquals("bold", mark.payload["property"]?.toString()?.trim('"'))
    }

    @Test fun `document and relationship deletion address stable members`() {
        val sceneDelete = adapter.deleteSceneObject("page-1", "object-1")
        val relationshipDelete = adapter.deleteRelationship("relationship-1")

        assertEquals("page-1", sceneDelete.targetId)
        assertEquals("object-1", sceneDelete.payload["itemId"]?.toString()?.trim('"'))
        assertEquals("relationship.delete", relationshipDelete.action)
        assertEquals("relationship-1", relationshipDelete.payload["relationshipId"]?.toString()?.trim('"'))
    }
}
