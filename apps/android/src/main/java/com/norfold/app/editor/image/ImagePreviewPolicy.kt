package com.norfold.app.editor.image

import kotlin.math.roundToInt
import kotlin.math.sqrt

data class ImageDecodeTarget(val widthPx: Int, val heightPx: Int, val wasDownsampled: Boolean)

object ImagePreviewPolicy {
    const val MAX_EDGE_PX = 2_048
    const val MAX_PIXELS = 4_000_000L

    /** Bounds hostile metadata and decodes only as large as the current rendered target requires. */
    fun target(sourceWidth: Int, sourceHeight: Int, viewWidth: Int, viewHeight: Int): ImageDecodeTarget {
        val safeSourceWidth = sourceWidth.coerceIn(1, ImageSourceMetadata.MAX_DIMENSION)
        val safeSourceHeight = sourceHeight.coerceIn(1, ImageSourceMetadata.MAX_DIMENSION)
        val requestedWidth = viewWidth.coerceIn(1, MAX_EDGE_PX).coerceAtMost(safeSourceWidth)
        val requestedHeight = viewHeight.coerceIn(1, MAX_EDGE_PX).coerceAtMost(safeSourceHeight)
        val pixels = requestedWidth.toLong() * requestedHeight
        val scale = if (pixels <= MAX_PIXELS) 1.0 else sqrt(MAX_PIXELS.toDouble() / pixels)
        val width = (requestedWidth * scale).roundToInt().coerceAtLeast(1)
        val height = (requestedHeight * scale).roundToInt().coerceAtLeast(1)
        return ImageDecodeTarget(width, height, width < safeSourceWidth || height < safeSourceHeight)
    }

    fun cacheKey(source: String, document: ImageEditDocument, target: ImageDecodeTarget): String =
        "${source.hashCode()}:${renderFingerprint(document).hashCode()}:${target.widthPx}x${target.heightPx}"

    /** Excludes metadata and editor-only state that cannot change rendered pixels. */
    fun renderFingerprint(document: ImageEditDocument): String = ImageEditDocumentCodec.encode(
        document.copy(
            crop = document.crop.copy(guide = ImageCompositionGuide.NONE),
            accessibility = ImageAccessibility(),
            sourceMetadata = ImageSourceMetadata(),
        ),
    )
}

data class ImagePreviewCacheStats(val hits: Long, val misses: Long, val evictions: Long, val size: Int)

data class ImagePreviewInstrumentationSnapshot(
    val sourceDecodeCount: Long,
    val previewRenders: Long,
    val cacheHits: Long,
    val cacheMisses: Long,
    val requestedTarget: ImageDecodeTarget?,
    val sampledWidthPx: Int?,
    val sampledHeightPx: Int?,
)

/** Process-level bounded metrics seam; it stores keys and dimensions, never source pixels or metadata. */
class ImagePreviewInstrumentation(private val keyCapacity: Int = 128) {
    private val knownKeys = object : LinkedHashMap<String, Unit>(16, 0.75f, true) {}
    private var decodes = 0L
    private var renders = 0L
    private var hits = 0L
    private var misses = 0L
    private var lastTarget: ImageDecodeTarget? = null
    private var sampledWidth: Int? = null
    private var sampledHeight: Int? = null

    @Synchronized fun recordRequest(key: String, target: ImageDecodeTarget) {
        lastTarget = target
        if (knownKeys.containsKey(key)) {
            knownKeys[key] = Unit
            hits++
        } else {
            misses++
            decodes++
            if (keyCapacity > 0) {
                knownKeys[key] = Unit
                while (knownKeys.size > keyCapacity) knownKeys.remove(knownKeys.entries.first().key)
            }
        }
    }

    @Synchronized fun recordPresented(widthPx: Int, heightPx: Int) {
        renders++
        sampledWidth = widthPx.coerceAtLeast(1)
        sampledHeight = heightPx.coerceAtLeast(1)
    }

    @Synchronized fun snapshot() = ImagePreviewInstrumentationSnapshot(
        decodes, renders, hits, misses, lastTarget, sampledWidth, sampledHeight,
    )

    companion object { val Global = ImagePreviewInstrumentation() }
}

/** Small generic LRU for derived preview artefacts; callers cannot put source bitmaps into edit history. */
class ImagePreviewCache<T>(private val capacity: Int = 24) {
    private val values = object : LinkedHashMap<String, T>(16, 0.75f, true) {}
    private var hits = 0L
    private var misses = 0L
    private var evictions = 0L

    fun get(key: String): T? = values[key].also { if (it == null) misses++ else hits++ }
    fun put(key: String, value: T) {
        if (capacity <= 0) return
        values[key] = value
        while (values.size > capacity) {
            values.remove(values.entries.first().key)
            evictions++
        }
    }
    fun clear() = values.clear()
    fun stats() = ImagePreviewCacheStats(hits, misses, evictions, values.size)
}

enum class ImagePipelineStage { SOURCE, CROP, GEOMETRY, FRAME, ADJUSTMENTS, EFFECTS, MASK, MARKUP, BORDER, SHADOW }

object ImageRenderPipeline {
    val semanticOrder = listOf(
        ImagePipelineStage.SOURCE, ImagePipelineStage.CROP, ImagePipelineStage.GEOMETRY,
        ImagePipelineStage.FRAME, ImagePipelineStage.MASK, ImagePipelineStage.ADJUSTMENTS,
        ImagePipelineStage.EFFECTS, ImagePipelineStage.MARKUP, ImagePipelineStage.BORDER, ImagePipelineStage.SHADOW,
    )
    fun stages(compareOriginal: Boolean): List<ImagePipelineStage> =
        if (compareOriginal) listOf(ImagePipelineStage.SOURCE) else semanticOrder
}
