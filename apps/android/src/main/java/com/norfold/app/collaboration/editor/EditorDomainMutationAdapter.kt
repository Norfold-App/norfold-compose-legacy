package com.norfold.app.collaboration.editor

import com.norfold.app.collaboration.protocol.SemanticBatch
import com.norfold.app.collaboration.protocol.SemanticMutation
import com.norfold.app.domain.DocumentBlock
import com.norfold.app.domain.DocumentPageBlock
import com.norfold.app.domain.ImageBlock
import com.norfold.app.domain.InlineNode
import com.norfold.app.domain.SceneObject
import com.norfold.app.domain.style.ColorValue
import com.norfold.app.editor.calculation.CalculationCell
import com.norfold.app.editor.calculation.CalculationSheet
import com.norfold.app.editor.calculation.CalculationWorkbook
import com.norfold.app.editor.chart.ChartDocument
import com.norfold.app.editor.chart.ChartSeries
import com.norfold.app.editor.history.EditOperation
import com.norfold.app.editor.history.EditTarget
import com.norfold.app.editor.history.EditTransaction
import com.norfold.app.editor.image.ImageEditDocument
import com.norfold.app.editor.relationship.DiscussionThread
import com.norfold.app.editor.relationship.Comment
import com.norfold.app.editor.relationship.CommentStatus
import com.norfold.app.editor.relationship.Relationship
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.security.MessageDigest

/** Maps final editor-domain identities to the versioned collaboration seam without reshaping them. */
class EditorDomainMutationAdapter(
    private val blockAt: (EditTarget, Int) -> DocumentBlock? = { _, _ -> null },
) {
    fun currentBlock(target: EditTarget, index: Int): DocumentBlock? = blockAt(target, index)

    fun history(transaction: EditTransaction): SemanticBatch = SemanticBatch(
        batchId = transaction.id,
        commandId = transaction.id,
        operations = transaction.operations.map(::historyOperation),
    )

    fun sceneObject(page: DocumentPageBlock, objectValue: SceneObject, action: String = "object.set"): SemanticMutation =
        mutation("document", action, page.id, buildJsonObject {
            put("pageId", page.id)
            put("itemId", objectValue.id)
            put("objectId", objectValue.id)
            put("object", encode(SceneObject.serializer(), objectValue.normalized()))
        })

    fun deleteSceneObject(pageId: String, objectId: String): SemanticMutation = mutation(
        "document", "object.delete", pageId,
        buildJsonObject { put("itemId", objectId); put("objectId", objectId) },
    )

    fun transformSceneObject(pageId: String, objectValue: SceneObject): SemanticMutation = mutation(
        "document", "object.transform", pageId,
        buildJsonObject {
            put("itemId", objectValue.id)
            put("objectId", objectValue.id)
            put("geometry", encode(com.norfold.app.domain.SceneGeometry.serializer(), objectValue.geometry.normalized()))
            put("z", objectValue.z)
            put("lock", objectValue.lock.name)
            put("visible", objectValue.visible)
        },
    )

    fun pageSettings(page: DocumentPageBlock): SemanticMutation = mutation(
        "document", "page.settings", page.id,
        buildJsonObject { put("geometry", encode(com.norfold.app.domain.PageGeometry.serializer(), page.geometry.normalized())) },
    )

    fun styleColor(tokenId: String, color: ColorValue): SemanticMutation = mutation(
        "style", "token.color", tokenId,
        buildJsonObject { put("property", "color"); put("value", encode(ColorValue.serializer(), color)) },
    )

    fun spreadsheetCell(workbook: CalculationWorkbook, sheet: CalculationSheet, cell: CalculationCell): SemanticMutation =
        mutation("spreadsheet", "cell.write", workbook.id.value, buildJsonObject {
            put("sheetId", sheet.id.value)
            put("cellId", cell.id.value)
            put("rowId", cell.rowId.value)
            put("columnId", cell.columnId.value)
            put("input", encode(com.norfold.app.editor.calculation.CellInput.serializer(), cell.input))
        })

    fun spreadsheetStructure(workbook: CalculationWorkbook, action: String, payload: JsonObject): SemanticMutation =
        mutation("spreadsheet", action, workbook.id.value, payload)

    fun chart(document: ChartDocument, action: String = "document.set"): SemanticMutation {
        val normalized = document.normalized()
        val durable = normalized.copy(interaction = normalized.interaction.copy(
            selectedSeriesId = null,
            selectedSourceRowIndex = null,
        ))
        return mutation(
            "chart", action, document.id,
            buildJsonObject { put("document", encode(ChartDocument.serializer(), durable)) },
        )
    }

    fun chartSeries(documentId: String, series: ChartSeries, action: String = "series.set"): SemanticMutation = mutation(
        "chart", action, documentId,
        buildJsonObject { put("seriesId", series.id); put("series", encode(ChartSeries.serializer(), series)) },
    )

    /** Called only after Image Focus Done; source identity remains on the owning ImageBlock. */
    fun committedImage(block: ImageBlock, edit: ImageEditDocument): SemanticMutation = mutation(
        "image", "edit.commit", block.id,
        buildJsonObject {
            val portable = block.source.takeIf { it.startsWith("norfold-asset:") || it.startsWith("https://") }
            portable?.let { put("assetReference", it) } ?: put("assetState", "missing_remote")
            put("sourceFingerprint", MessageDigest.getInstance("SHA-256").digest(block.source.toByteArray()).joinToString("") { "%02x".format(it) })
            put("edit", encode(ImageEditDocument.serializer(), edit.normalized()))
        },
    )

    fun relationship(value: Relationship, action: String = "relationship.set"): SemanticMutation = mutation(
        "relationship", action, value.id.value,
        buildJsonObject { put("relationshipId", value.id.value); put("value", encode(Relationship.serializer(), value)) },
    )

    fun deleteRelationship(relationshipId: String): SemanticMutation = mutation(
        "relationship", "relationship.delete", relationshipId,
        buildJsonObject { put("relationshipId", relationshipId) },
    )

    fun discussion(value: DiscussionThread, action: String = "thread.create"): SemanticMutation = mutation(
        "comment", action, value.id.value,
        buildJsonObject { put("threadId", value.id.value); put("value", encode(DiscussionThread.serializer(), value)) },
    )

    fun discussionComment(value: Comment, action: String = "comment.insert"): SemanticMutation = mutation(
        "comment", action, value.threadId.value,
        buildJsonObject {
            put("commentId", value.id.value)
            put("value", encode(Comment.serializer(), value))
        },
    )

    fun discussionResolution(threadId: String, status: CommentStatus): SemanticMutation = mutation(
        "comment", if (status == CommentStatus.RESOLVED) "thread.resolve" else "thread.reopen", threadId,
        buildJsonObject { put("property", "resolution"); put("status", status.name) },
    )

    fun interchangeMetadata(artifactId: String, compatibility: JsonObject): SemanticMutation =
        mutation("interchange", "compatibility.set", artifactId, compatibility)

    /** Stable insertion anchors allow concurrent text inserts to survive independently. */
    fun insertText(
        blockId: String,
        insertionId: String,
        afterAnchor: String?,
        content: List<InlineNode>,
    ): SemanticMutation = mutation("flow", "text.insert", blockId, buildJsonObject {
        put("itemId", insertionId)
        if (afterAnchor == null) put("afterAnchor", JsonNull) else put("afterAnchor", afterAnchor)
        put("content", encode(ListSerializer(InlineNode.serializer()), content))
    })

    fun deleteText(blockId: String, fromAnchor: String, toAnchor: String): SemanticMutation = mutation(
        "flow", "text.delete", blockId,
        buildJsonObject { put("fromAnchor", fromAnchor); put("toAnchor", toAnchor) },
    )

    fun markText(
        blockId: String,
        fromAnchor: String,
        toAnchor: String,
        property: String,
        value: JsonElement,
    ): SemanticMutation = mutation("flow", "text.mark", blockId, buildJsonObject {
        put("fromAnchor", fromAnchor)
        put("toAnchor", toAnchor)
        put("property", property)
        put("value", value)
    })

    private fun historyOperation(operation: EditOperation): SemanticMutation = when (operation) {
        is EditOperation.InsertBlock -> mutation("flow", "block.insert", operation.target.id, buildJsonObject {
            put("itemId", operation.block.id)
            val afterId = blockAt(operation.target, operation.index - 1)?.id
            if (afterId == null) put("afterId", JsonNull) else put("afterId", afterId)
            put("block", encode(DocumentBlock.serializer(), operation.block))
        })
        is EditOperation.RemoveBlock -> mutation("flow", "block.delete", operation.target.id, buildJsonObject {
            put("itemId", operation.block.id)
        })
        is EditOperation.ReplaceBlock -> mutation("flow", "block.replace", operation.target.id, buildJsonObject {
            put("itemId", operation.after.id)
            put("block", encode(DocumentBlock.serializer(), operation.after))
        })
        is EditOperation.MoveBlock -> {
            val moved = blockAt(operation.target, operation.toIndex)
                ?: error("A stable block resolver is required for collaborative movement")
            mutation("flow", "block.move", operation.target.id, buildJsonObject {
                put("itemId", moved.id)
                val afterId = blockAt(operation.target, operation.toIndex - 1)?.id
                if (afterId == null) put("afterId", JsonNull) else put("afterId", afterId)
            })
        }
    }

    private fun mutation(domain: String, action: String, target: String, payload: JsonObject) =
        SemanticMutation(domain = domain, action = action, targetId = target, payload = payload)

    private fun <T> encode(serializer: KSerializer<T>, value: T) = JSON.encodeToJsonElement(serializer, value)

    companion object {
        private val JSON = Json { encodeDefaults = true; explicitNulls = false; ignoreUnknownKeys = false }
    }
}
