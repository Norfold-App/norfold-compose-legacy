package com.norfold.app.collaboration.protocol

import com.norfold.app.collaboration.ops.ChartOperation
import com.norfold.app.collaboration.ops.OpaqueOperation
import com.norfold.app.collaboration.ops.OrderedStructureOperation
import com.norfold.app.collaboration.ops.SceneOperation
import com.norfold.app.collaboration.ops.SemanticOperation
import com.norfold.app.collaboration.ops.TableOperation
import com.norfold.app.collaboration.ops.TextOperation
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Lossless bridge for preliminary operations that may already exist in local developer outboxes. */
object LegacySemanticOperationAdapter {
    fun adapt(operation: SemanticOperation): SemanticMutation = when (operation) {
        is TextOperation.Insert -> mutation("flow", "text.insert", operation.targetId) {
            put("anchor", operation.anchor); put("text", operation.text)
            put("marks", kotlinx.serialization.json.JsonObject(operation.marks.mapValues { kotlinx.serialization.json.JsonPrimitive(it.value) }))
        }
        is TextOperation.Delete -> mutation("flow", "text.delete", operation.targetId) {
            put("fromAnchor", operation.fromAnchor); put("toAnchor", operation.toAnchor)
        }
        is TextOperation.Mark -> mutation("flow", "text.mark", operation.targetId) {
            put("fromAnchor", operation.fromAnchor); put("toAnchor", operation.toAnchor)
            put("property", operation.mark); operation.value?.let { put("value", it) } ?: put("value", JsonNull)
        }
        is SceneOperation.Create -> mutation("document", "object.insert", operation.targetId) {
            put("objectType", operation.objectType)
        }
        is SceneOperation.Delete -> mutation("document", "object.delete", operation.targetId) {}
        is SceneOperation.Transform -> mutation("document", "object.transform", operation.targetId) {
            operation.x?.let { put("x", it) }; operation.y?.let { put("y", it) }
            operation.rotation?.let { put("rotation", it) }
            operation.scaleX?.let { put("scaleX", it) }; operation.scaleY?.let { put("scaleY", it) }
        }
        is SceneOperation.SetProperty -> mutation("document", "object.property", operation.targetId) {
            put("property", operation.key); operation.value?.let { put("value", it) } ?: put("value", JsonNull)
        }
        is TableOperation.InsertRow -> mutation("spreadsheet", "row.insert", operation.targetId) { put("itemId", operation.rowId); operation.afterRowId?.let { put("afterId", it) } ?: put("afterId", JsonNull) }
        is TableOperation.InsertColumn -> mutation("spreadsheet", "column.insert", operation.targetId) { put("itemId", operation.colId); operation.afterColId?.let { put("afterId", it) } ?: put("afterId", JsonNull) }
        is TableOperation.DeleteRow -> mutation("spreadsheet", "row.delete", operation.targetId) { put("itemId", operation.rowId) }
        is TableOperation.DeleteColumn -> mutation("spreadsheet", "column.delete", operation.targetId) { put("itemId", operation.colId) }
        is TableOperation.SetCell -> mutation("spreadsheet", "cell.write", operation.targetId) {
            put("cellId", "${operation.rowId}:${operation.colId}")
            put("rowId", operation.rowId)
            put("columnId", operation.colId)
            operation.value?.let { put("input", it) } ?: put("input", JsonNull)
        }
        is ChartOperation.SetSeries -> mutation("chart", "series.set", operation.targetId) { put("seriesId", operation.seriesId); put("value", operation.data) }
        is ChartOperation.RemoveSeries -> mutation("chart", "series.delete", operation.targetId) { put("seriesId", operation.seriesId) }
        is ChartOperation.SetMark -> mutation("chart", "mark.set", operation.targetId) { put("itemId", operation.markId); put("value", operation.spec) }
        is ChartOperation.SetAnnotation -> mutation("chart", "annotation.set", operation.targetId) { put("itemId", operation.annotationId); operation.spec?.let { put("value", it) } ?: put("value", JsonNull) }
        is OrderedStructureOperation.Insert -> mutation("flow", "item.insert", operation.targetId) { put("itemId", operation.itemId); operation.afterItemId?.let { put("afterId", it) } ?: put("afterId", JsonNull) }
        is OrderedStructureOperation.Move -> mutation("flow", "item.move", operation.targetId) { put("itemId", operation.itemId); operation.afterItemId?.let { put("afterId", it) } ?: put("afterId", JsonNull) }
        is OrderedStructureOperation.Remove -> mutation("flow", "item.delete", operation.targetId) { put("itemId", operation.itemId) }
        is OpaqueOperation -> SemanticMutation(domain = operation.domain, action = operation.opType, targetId = operation.targetId, payload = buildJsonObject { put("legacyBody", operation.body) })
    }

    private inline fun mutation(domain: String, action: String, targetId: String, body: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit) =
        SemanticMutation(domain = domain, action = action, targetId = targetId, payload = buildJsonObject(body))
}
