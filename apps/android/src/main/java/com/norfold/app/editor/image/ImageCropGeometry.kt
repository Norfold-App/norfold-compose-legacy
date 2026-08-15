package com.norfold.app.editor.image

import kotlin.math.max
import kotlin.math.min

enum class ImageCropHandle {
    MOVE,
    LEFT,
    TOP,
    RIGHT,
    BOTTOM,
    TOP_LEFT,
    TOP_RIGHT,
    BOTTOM_LEFT,
    BOTTOM_RIGHT,
}

/** Pure normalized crop geometry shared by pointer and accessibility command routes. */
object ImageCropGeometry {
    /**
     * Scale required to keep a rotated source entirely inside an unchanged crop viewport.
     *
     * The viewport is expressed in the source's unrotated aspect ratio. Unlike a square-only
     * approximation, this preserves the whole image for portrait and landscape sources alike.
     */
    fun rotationScaleToFitViewport(degrees: Float, sourceAspectRatio: Float): Float {
        val aspect = sourceAspectRatio.finiteOr(1f).coerceIn(0.05f, 20f)
        val radians = Math.toRadians(degrees.finiteOr(0f).toDouble())
        val cosine = kotlin.math.abs(kotlin.math.cos(radians)).toFloat()
        val sine = kotlin.math.abs(kotlin.math.sin(radians)).toFloat()
        val rotatedWidth = aspect * cosine + sine
        val rotatedHeight = aspect * sine + cosine
        return min(aspect / rotatedWidth.coerceAtLeast(0.0001f), 1f / rotatedHeight.coerceAtLeast(0.0001f))
            .coerceIn(0.05f, 1f)
    }

    /** Resizes the crop frame around its centre. The source stays put; only the visible frame changes. */
    fun zoom(bounds: NormalizedRect, zoom: Float): NormalizedRect {
        val current = bounds.normalized()
        val targetZoom = zoom.finiteOr(1f).coerceIn(1f, 12f)
        val currentExtent = max(current.width, current.height).coerceAtLeast(NormalizedRect.MIN_EXTENT)
        val targetExtent = (1f / targetZoom).coerceIn(NormalizedRect.MIN_EXTENT, 1f)
        val factor = targetExtent / currentExtent
        val width = (current.width * factor).coerceIn(NormalizedRect.MIN_EXTENT, 1f)
        val height = (current.height * factor).coerceIn(NormalizedRect.MIN_EXTENT, 1f)
        val centerX = current.left + current.width / 2f
        val centerY = current.top + current.height / 2f
        return NormalizedRect(
            left = (centerX - width / 2f).coerceIn(0f, 1f - width),
            top = (centerY - height / 2f).coerceIn(0f, 1f - height),
            width = width,
            height = height,
        ).normalized()
    }

    fun aspectRatio(mode: ImageCropMode, metadata: ImageSourceMetadata, quarterTurns: Int): Float? {
        val base = when (mode) {
            ImageCropMode.FREE -> return null
            ImageCropMode.ORIGINAL -> {
                val width = metadata.pixelWidth ?: return null
                val height = metadata.pixelHeight ?: return null
                width.toFloat() / height.toFloat()
            }
            ImageCropMode.RATIO_1_1 -> 1f
            ImageCropMode.RATIO_4_3 -> 4f / 3f
            ImageCropMode.RATIO_3_4 -> 3f / 4f
            ImageCropMode.RATIO_16_9 -> 16f / 9f
            ImageCropMode.RATIO_9_16 -> 9f / 16f
        }
        return if (((quarterTurns % 2) + 2) % 2 == 1) 1f / base else base
    }

    fun applyMode(crop: ImageCrop, metadata: ImageSourceMetadata, mode: ImageCropMode): ImageCrop {
        val next = crop.copy(mode = mode)
        val ratio = aspectRatio(mode, metadata, crop.quarterTurns) ?: return next.normalized()
        val current = crop.bounds.normalized()
        val centerX = current.left + current.width / 2f
        val centerY = current.top + current.height / 2f
        var width = current.width
        var height = width / ratio
        if (height > current.height) {
            height = current.height
            width = height * ratio
        }
        width = width.coerceIn(NormalizedRect.MIN_EXTENT, 1f)
        height = height.coerceIn(NormalizedRect.MIN_EXTENT, 1f)
        return next.copy(
            bounds = NormalizedRect(
                left = (centerX - width / 2f).coerceIn(0f, 1f - width),
                top = (centerY - height / 2f).coerceIn(0f, 1f - height),
                width = width,
                height = height,
            ),
        ).normalized()
    }

    fun drag(
        bounds: NormalizedRect,
        handle: ImageCropHandle,
        deltaX: Float,
        deltaY: Float,
        aspectRatio: Float? = null,
    ): NormalizedRect {
        val original = bounds.normalized()
        if (handle == ImageCropHandle.MOVE) {
            return original.copy(
                left = (original.left + deltaX.finiteOr(0f)).coerceIn(0f, 1f - original.width),
                top = (original.top + deltaY.finiteOr(0f)).coerceIn(0f, 1f - original.height),
            )
        }

        var left = original.left
        var top = original.top
        var right = original.left + original.width
        var bottom = original.top + original.height
        if (handle.movesLeft) left = (left + deltaX.finiteOr(0f)).coerceIn(0f, right - NormalizedRect.MIN_EXTENT)
        if (handle.movesRight) right = (right + deltaX.finiteOr(0f)).coerceIn(left + NormalizedRect.MIN_EXTENT, 1f)
        if (handle.movesTop) top = (top + deltaY.finiteOr(0f)).coerceIn(0f, bottom - NormalizedRect.MIN_EXTENT)
        if (handle.movesBottom) bottom = (bottom + deltaY.finiteOr(0f)).coerceIn(top + NormalizedRect.MIN_EXTENT, 1f)

        val ratio = aspectRatio?.finiteOr(0f)?.takeIf { it > 0f }
        if (ratio != null) {
            val anchorX = if (handle.movesLeft) right else left
            val anchorY = if (handle.movesTop) bottom else top
            var width = right - left
            var height = bottom - top
            if (handle.movesLeft || handle.movesRight) height = width / ratio else width = height * ratio
            width = min(width, if (handle.movesLeft) anchorX else 1f - anchorX)
            height = min(height, if (handle.movesTop) anchorY else 1f - anchorY)
            if (width / height > ratio) width = height * ratio else height = width / ratio
            width = max(width, NormalizedRect.MIN_EXTENT)
            height = max(height, NormalizedRect.MIN_EXTENT)
            left = if (handle.movesLeft) anchorX - width else anchorX
            right = if (handle.movesLeft) anchorX else anchorX + width
            top = if (handle.movesTop) anchorY - height else anchorY
            bottom = if (handle.movesTop) anchorY else anchorY + height
        }
        return NormalizedRect(left, top, right - left, bottom - top).normalized()
    }
}

private val ImageCropHandle.movesLeft get() = this in setOf(ImageCropHandle.LEFT, ImageCropHandle.TOP_LEFT, ImageCropHandle.BOTTOM_LEFT)
private val ImageCropHandle.movesRight get() = this in setOf(ImageCropHandle.RIGHT, ImageCropHandle.TOP_RIGHT, ImageCropHandle.BOTTOM_RIGHT)
private val ImageCropHandle.movesTop get() = this in setOf(ImageCropHandle.TOP, ImageCropHandle.TOP_LEFT, ImageCropHandle.TOP_RIGHT)
private val ImageCropHandle.movesBottom get() = this in setOf(ImageCropHandle.BOTTOM, ImageCropHandle.BOTTOM_LEFT, ImageCropHandle.BOTTOM_RIGHT)
