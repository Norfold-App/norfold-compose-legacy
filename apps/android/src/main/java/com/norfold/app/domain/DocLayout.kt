package com.norfold.app.domain

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Per-document layout controls for the Docs editor. These live on the [Note] (doc-level), not on the
 * block payloads, so block JSON (`note_blocks`) stays backward-compatible.
 *
 * - [DocOverlapMode] chooses how dragging behaves: [Reflow] pushes/reorders blocks in a stacked flow;
 *   [Overlap] is a completely freeform canvas (no grid) where blocks float at absolute positions.
 * - [FreeformPlacement] is one block's position/size/layer in [DocOverlapMode.Overlap], keyed by the
 *   top-level block id in `Note.freeformLayout`.
 */
@Serializable
enum class DocOverlapMode { Reflow, Overlap }

fun docOverlapModeOf(raw: String?): DocOverlapMode =
    DocOverlapMode.entries.firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: DocOverlapMode.Reflow

/** Absolute placement of a top-level block on the free-overlap canvas. x/y/width/height are in dp. */
@Serializable
data class FreeformPlacement(
    val x: Float = 0f,
    val y: Float = 0f,
    val width: Float = 320f,
    val height: Float = 160f,
    val z: Int = 0,
)

/**
 * Pure layer-order helpers over the freeform placement map. All return a **new** map with contiguous
 * z-indices (0..n-1, higher = front) — no gaps, no duplicates — so persistence stays stable.
 */
object DocLayerOrder {

    /** Reassigns z to 0..n-1 following current z order (stable by key on ties). */
    fun normalize(layout: Map<String, FreeformPlacement>): Map<String, FreeformPlacement> {
        val ordered = layout.entries.sortedWith(compareBy({ it.value.z }, { it.key }))
        return ordered.mapIndexed { index, entry -> entry.key to entry.value.copy(z = index) }.toMap()
    }

    fun bringToFront(layout: Map<String, FreeformPlacement>, id: String): Map<String, FreeformPlacement> =
        reorder(layout, id) { others, _ -> others + id }

    fun sendToBack(layout: Map<String, FreeformPlacement>, id: String): Map<String, FreeformPlacement> =
        reorder(layout, id) { others, _ -> listOf(id) + others }

    fun bringForward(layout: Map<String, FreeformPlacement>, id: String): Map<String, FreeformPlacement> =
        swapWithNeighbor(layout, id, forward = true)

    fun sendBackward(layout: Map<String, FreeformPlacement>, id: String): Map<String, FreeformPlacement> =
        swapWithNeighbor(layout, id, forward = false)

    private fun reorder(
        layout: Map<String, FreeformPlacement>,
        id: String,
        place: (others: List<String>, self: String) -> List<String>,
    ): Map<String, FreeformPlacement> {
        if (id !in layout) return layout
        val order = layout.entries.sortedWith(compareBy({ it.value.z }, { it.key })).map { it.key }
        val others = order.filter { it != id }
        val newOrder = place(others, id)
        return newOrder.mapIndexed { index, key -> key to layout.getValue(key).copy(z = index) }.toMap()
    }

    private fun swapWithNeighbor(
        layout: Map<String, FreeformPlacement>,
        id: String,
        forward: Boolean,
    ): Map<String, FreeformPlacement> {
        if (id !in layout) return layout
        val order = layout.entries.sortedWith(compareBy({ it.value.z }, { it.key })).map { it.key }.toMutableList()
        val index = order.indexOf(id)
        val target = if (forward) index + 1 else index - 1
        if (target !in order.indices) return layout
        order[index] = order[target].also { order[target] = order[index] }
        return order.mapIndexed { i, key -> key to layout.getValue(key).copy(z = i) }.toMap()
    }
}

/** JSON codec for the doc-level freeform placement map stored in `NoteEntity.freeformLayoutJson`. */
object DocLayoutJson {
    private val format = Json { encodeDefaults = true; ignoreUnknownKeys = true }
    private val serializer = MapSerializer(String.serializer(), FreeformPlacement.serializer())

    fun encode(layout: Map<String, FreeformPlacement>): String? =
        if (layout.isEmpty()) null else format.encodeToString(serializer, layout)

    fun decode(json: String?): Map<String, FreeformPlacement> =
        if (json.isNullOrBlank()) emptyMap() else runCatching { format.decodeFromString(serializer, json) }.getOrDefault(emptyMap())
}
