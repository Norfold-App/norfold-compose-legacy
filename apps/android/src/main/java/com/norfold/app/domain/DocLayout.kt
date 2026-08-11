package com.norfold.app.domain

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import java.util.UUID
import kotlin.math.ceil

/**
 * Per-document layout controls for the Docs editor. These live on the owner-agnostic document row,
 * not on block payloads, so block JSON remains portable across Notes, Tasks, and Calendar events.
 *
 * - [DocOverlapMode] selects Flow, a bounded document canvas, or an infinite spatial canvas.
 * - [FreeformPlacement] is one block's position/size/layer in [DocOverlapMode.Overlap], keyed by the
 *   top-level block id in `Note.freeformLayout`.
 *
 * Persistence contract: `DocumentEntity.layout_mode` stores `name.lowercase()` and
 * [docOverlapModeOf] keeps decoding legacy literals from the pre-beta note-owned layout model.
 */
@Serializable
enum class DocOverlapMode { Reflow, Bounded, Overlap }

fun docOverlapModeOf(raw: String?): DocOverlapMode =
    when (raw?.lowercase()) {
        "flow", "page", "reflow" -> DocOverlapMode.Reflow
        "bounded", "document", "document_canvas" -> DocOverlapMode.Bounded
        "infinite", "infinite_canvas", "overlap" -> DocOverlapMode.Overlap
        else -> DocOverlapMode.Reflow
    }

@Serializable
enum class DocPagePreset { A4, Letter, Legal, Custom }

@Serializable
enum class DocumentLabelMode { UseGlobal, Adaptive, IconsOnly, IconsAndText }

/**
 * A fixed-size, exportable artboard. Dimensions use document points (72 points per inch), which
 * map directly to PDF/CSS print units and remain independent of device density.
 */
@Serializable
data class DocCanvasSpec(
    val preset: DocPagePreset = DocPagePreset.A4,
    val width: Float = 595f,
    val height: Float = 842f,
    val pageCount: Int = 1,
    /** Stable identities used by history, page reordering, and future collaboration operations. */
    val pageIds: List<String> = emptyList(),
    val pageGap: Float = 24f,
    val toolbarLabelMode: DocumentLabelMode = DocumentLabelMode.UseGlobal,
) {
    fun normalized(): DocCanvasSpec {
        val count = pageCount.coerceIn(1, 100)
        val stableIds = pageIds
            .map(String::trim)
            .filter(String::isNotEmpty)
            .distinct()
            .take(count)
            .toMutableList()
        while (stableIds.size < count) {
            val ordinal = stableIds.size + 1
            var candidate = "legacy-page-$ordinal"
            var suffix = 2
            while (candidate in stableIds) candidate = "legacy-page-$ordinal-${suffix++}"
            stableIds += candidate
        }
        return copy(
            width = width.coerceIn(240f, 2400f),
            height = height.coerceIn(240f, 3400f),
            pageCount = count,
            pageIds = stableIds,
            pageGap = pageGap.coerceIn(0f, 96f),
        )
    }

    fun addPage(): DocCanvasSpec {
        val current = normalized()
        if (current.pageCount >= 100) return current
        return current.copy(
            pageCount = current.pageCount + 1,
            pageIds = current.pageIds + "page-${UUID.randomUUID()}",
        ).normalized()
    }

    fun removePage(index: Int): DocCanvasSpec {
        val current = normalized()
        if (current.pageCount <= 1) return current
        val resolved = index.coerceIn(0, current.pageCount - 1)
        return current.copy(
            pageCount = current.pageCount - 1,
            pageIds = current.pageIds.filterIndexed { pageIndex, _ -> pageIndex != resolved },
        ).normalized()
    }

    fun movePage(from: Int, to: Int): DocCanvasSpec {
        val current = normalized()
        val source = from.coerceIn(0, current.pageCount - 1)
        val target = to.coerceIn(0, current.pageCount - 1)
        if (source == target) return current
        val ids = current.pageIds.toMutableList()
        val moved = ids.removeAt(source)
        ids.add(target, moved)
        return current.copy(pageIds = ids)
    }

    val totalHeight: Float get() = height * pageCount + pageGap * (pageCount - 1)

    fun pageOrigin(pageIndex: Int): Float = pageIndex.coerceIn(0, pageCount - 1) * (height + pageGap)

    fun rawPageIndexOf(placement: FreeformPlacement): Int =
        ((placement.y * POINTS_PER_DP) / (height + pageGap)).toInt().coerceAtLeast(0)

    fun pageIndexOf(placement: FreeformPlacement): Int = rawPageIndexOf(placement).coerceIn(0, pageCount - 1)

    fun pagesRequiredFor(layout: Map<String, FreeformPlacement>): Int {
        val bottom = (layout.values.maxOfOrNull { it.y + it.height } ?: 0f) * POINTS_PER_DP
        return ceil((bottom + pageGap) / (height + pageGap)).toInt().coerceIn(1, 100)
    }

    companion object {
        private const val POINTS_PER_DP = 72f / 160f
        fun a4() = DocCanvasSpec()
        fun letter() = DocCanvasSpec(DocPagePreset.Letter, width = 612f, height = 792f)
        fun legal() = DocCanvasSpec(DocPagePreset.Legal, width = 612f, height = 1008f)
    }
}

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

@Serializable
private data class DocLayoutPayload(
    val version: Int = 2,
    val placements: Map<String, FreeformPlacement> = emptyMap(),
    val canvas: DocCanvasSpec = DocCanvasSpec(),
)

/**
 * Versioned JSON codec stored in `DocumentEntity.layout_json`. It deliberately reads the legacy raw
 * placement-map format migrated from the pre-beta note-owned layout model.
 */
object DocLayoutJson {
    private val format = Json { encodeDefaults = true; ignoreUnknownKeys = true }
    private val serializer = MapSerializer(String.serializer(), FreeformPlacement.serializer())

    fun encode(
        layout: Map<String, FreeformPlacement>,
        canvas: DocCanvasSpec = DocCanvasSpec(),
    ): String = format.encodeToString(
        DocLayoutPayload(
            placements = DocLayerOrder.normalize(layout),
            canvas = canvas.normalized(),
        ),
    )

    fun decode(json: String?): Map<String, FreeformPlacement> = decodePayload(json).placements

    fun decodeCanvas(json: String?): DocCanvasSpec = decodePayload(json).canvas

    private fun decodePayload(json: String?): DocLayoutPayload {
        if (json.isNullOrBlank()) return DocLayoutPayload()
        val isVersioned = runCatching {
            "placements" in format.parseToJsonElement(json).jsonObject
        }.getOrDefault(false)
        if (isVersioned) {
            return runCatching { format.decodeFromString<DocLayoutPayload>(json) }
                .getOrDefault(DocLayoutPayload())
                .let { it.copy(placements = DocLayerOrder.normalize(it.placements), canvas = it.canvas.normalized()) }
        }
        val legacy = runCatching { format.decodeFromString(serializer, json) }.getOrDefault(emptyMap())
        return DocLayoutPayload(placements = DocLayerOrder.normalize(legacy))
    }
}
