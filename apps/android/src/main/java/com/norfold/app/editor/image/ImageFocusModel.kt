package com.norfold.app.editor.image

import com.norfold.app.domain.style.ColorValue
import kotlinx.serialization.Serializable
import kotlin.math.abs

@Serializable enum class ImageCropMode { FREE, ORIGINAL, RATIO_1_1, RATIO_4_3, RATIO_3_4, RATIO_16_9, RATIO_9_16 }
@Serializable enum class ImageCompositionGuide { NONE, THIRDS, GOLDEN_RATIO, DIAGONALS }
@Serializable enum class ImageFrameMode { FIT, FILL }
@Serializable enum class ImageMaskKind { NONE, RECTANGLE, ROUNDED_RECTANGLE, ELLIPSE }

/** A source-independent crop in normalized image coordinates. */
@Serializable
data class NormalizedRect(
    val left: Float = 0f,
    val top: Float = 0f,
    val width: Float = 1f,
    val height: Float = 1f,
) {
    fun normalized(): NormalizedRect {
        val cleanLeft = left.finiteOr(0f).coerceIn(0f, 1f - MIN_EXTENT)
        val cleanTop = top.finiteOr(0f).coerceIn(0f, 1f - MIN_EXTENT)
        val cleanWidth = width.finiteOr(1f).coerceIn(MIN_EXTENT, 1f - cleanLeft)
        val cleanHeight = height.finiteOr(1f).coerceIn(MIN_EXTENT, 1f - cleanTop)
        return copy(left = cleanLeft, top = cleanTop, width = cleanWidth, height = cleanHeight)
    }

    companion object {
        const val MIN_EXTENT = 0.01f
        val FULL = NormalizedRect()
    }
}

@Serializable
data class ImageCrop(
    val bounds: NormalizedRect = NormalizedRect.FULL,
    val mode: ImageCropMode = ImageCropMode.FREE,
    val guide: ImageCompositionGuide = ImageCompositionGuide.NONE,
    val straightenDegrees: Float = 0f,
    val quarterTurns: Int = 0,
    val flipHorizontal: Boolean = false,
    val flipVertical: Boolean = false,
    val perspectiveHorizontal: Float = 0f,
    val perspectiveVertical: Float = 0f,
) {
    fun normalized() = copy(
        bounds = bounds.normalized(),
        straightenDegrees = straightenDegrees.finiteOr(0f).coerceIn(-45f, 45f),
        quarterTurns = ((quarterTurns % 4) + 4) % 4,
        perspectiveHorizontal = perspectiveHorizontal.finiteOr(0f).coerceIn(-1f, 1f),
        perspectiveVertical = perspectiveVertical.finiteOr(0f).coerceIn(-1f, 1f),
    )
}

@Serializable
data class ImageFrame(
    val mode: ImageFrameMode = ImageFrameMode.FIT,
    val focalX: Float = 0.5f,
    val focalY: Float = 0.5f,
    val scale: Float = 1f,
    val offsetX: Float = 0f,
    val offsetY: Float = 0f,
) {
    fun normalized() = copy(
        focalX = focalX.finiteOr(0.5f).coerceIn(0f, 1f),
        focalY = focalY.finiteOr(0.5f).coerceIn(0f, 1f),
        scale = scale.finiteOr(1f).coerceIn(0.1f, 20f),
        offsetX = offsetX.finiteOr(0f).coerceIn(-1f, 1f),
        offsetY = offsetY.finiteOr(0f).coerceIn(-1f, 1f),
    )
}

@Serializable
data class ImageMask(
    val kind: ImageMaskKind = ImageMaskKind.NONE,
    val cornerRadius: Float = 0.12f,
) {
    fun normalized() = copy(cornerRadius = cornerRadius.finiteOr(0.12f).coerceIn(0f, 0.5f))
}

@Serializable
data class ImageAdjustments(
    val exposure: Float = 0f,
    val contrast: Float = 0f,
    val highlights: Float = 0f,
    val shadows: Float = 0f,
    val temperature: Float = 0f,
    val tint: Float = 0f,
    val saturation: Float = 0f,
    val sharpness: Float = 0f,
    val blur: Float = 0f,
) {
    fun normalized() = copy(
        exposure = exposure.unit(), contrast = contrast.unit(), highlights = highlights.unit(),
        shadows = shadows.unit(), temperature = temperature.unit(), tint = tint.unit(),
        saturation = saturation.unit(), sharpness = sharpness.unit(),
        blur = blur.finiteOr(0f).coerceIn(0f, 1f),
    )
}

@Serializable
data class ImageEffects(
    val opacity: Float = 1f,
    val monochrome: Float = 0f,
    val sepia: Float = 0f,
    val vignette: Float = 0f,
) {
    fun normalized() = copy(
        opacity = opacity.finiteOr(1f).coerceIn(0f, 1f),
        monochrome = monochrome.positiveUnit(),
        sepia = sepia.positiveUnit(),
        vignette = vignette.positiveUnit(),
    )
}

@Serializable
data class ImageBorder(
    val enabled: Boolean = false,
    val color: ColorValue = ColorValue(0, 0, 0),
    val widthDp: Float = 1f,
) {
    fun normalized() = copy(widthDp = widthDp.finiteOr(1f).coerceIn(0f, 64f))
}

@Serializable
data class ImageShadow(
    val enabled: Boolean = false,
    val color: ColorValue = ColorValue(0, 0, 0, 96),
    val blurDp: Float = 8f,
    val offsetXDp: Float = 0f,
    val offsetYDp: Float = 4f,
) {
    fun normalized() = copy(
        blurDp = blurDp.finiteOr(8f).coerceIn(0f, 96f),
        offsetXDp = offsetXDp.finiteOr(0f).coerceIn(-96f, 96f),
        offsetYDp = offsetYDp.finiteOr(4f).coerceIn(-96f, 96f),
    )
}

@Serializable
data class ImageAccessibility(
    /** Alternative text is deliberately not derived from the visible caption. */
    val alternativeText: String = "",
    val decorative: Boolean = false,
) {
    fun normalized() = copy(alternativeText = alternativeText.take(MAX_ALT_TEXT))
    companion object { const val MAX_ALT_TEXT = 2_048 }
}

@Serializable
data class ImageSourceMetadata(
    val pixelWidth: Int? = null,
    val pixelHeight: Int? = null,
    val mimeType: String? = null,
) {
    fun normalized() = copy(
        pixelWidth = pixelWidth?.takeIf { it in 1..MAX_DIMENSION },
        pixelHeight = pixelHeight?.takeIf { it in 1..MAX_DIMENSION },
        mimeType = mimeType?.lowercase()?.take(MAX_MIME_LENGTH),
    )
    companion object { const val MAX_DIMENSION = 100_000; const val MAX_MIME_LENGTH = 128 }
}

/**
 * Canonical image edit state. The original source URI is intentionally absent: [ImageBlock.source] remains
 * the sole source identity, while this value only records reversible rendering intent.
 */
@Serializable
data class ImageEditDocument(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val crop: ImageCrop = ImageCrop(),
    val frame: ImageFrame = ImageFrame(),
    val mask: ImageMask = ImageMask(),
    val adjustments: ImageAdjustments = ImageAdjustments(),
    val effects: ImageEffects = ImageEffects(),
    val border: ImageBorder = ImageBorder(),
    val shadow: ImageShadow = ImageShadow(),
    val accessibility: ImageAccessibility = ImageAccessibility(),
    val sourceMetadata: ImageSourceMetadata = ImageSourceMetadata(),
) {
    fun normalized() = copy(
        schemaVersion = CURRENT_SCHEMA_VERSION,
        crop = crop.normalized(), frame = frame.normalized(), mask = mask.normalized(),
        adjustments = adjustments.normalized(), effects = effects.normalized(), border = border.normalized(),
        shadow = shadow.normalized(), accessibility = accessibility.normalized(),
        sourceMetadata = sourceMetadata.normalized(),
    )

    fun parameters() = ImageEditParameters(crop, frame, mask, adjustments, effects, border, shadow).normalized()

    fun withParameters(parameters: ImageEditParameters): ImageEditDocument {
        val clean = parameters.normalized()
        return copy(
            crop = clean.crop, frame = clean.frame, mask = clean.mask,
            adjustments = clean.adjustments, effects = clean.effects,
            border = clean.border, shadow = clean.shadow,
        ).normalized()
    }

    companion object { const val CURRENT_SCHEMA_VERSION = 1 }
}

/** Preset/clipboard payload. Source, accessibility, caption, block identity and host placement cannot enter it. */
@Serializable
data class ImageEditParameters(
    val crop: ImageCrop = ImageCrop(),
    val frame: ImageFrame = ImageFrame(),
    val mask: ImageMask = ImageMask(),
    val adjustments: ImageAdjustments = ImageAdjustments(),
    val effects: ImageEffects = ImageEffects(),
    val border: ImageBorder = ImageBorder(),
    val shadow: ImageShadow = ImageShadow(),
) {
    fun normalized() = copy(
        crop = crop.normalized(), frame = frame.normalized(), mask = mask.normalized(),
        adjustments = adjustments.normalized(), effects = effects.normalized(),
        border = border.normalized(), shadow = shadow.normalized(),
    )
}

internal fun Float.finiteOr(fallback: Float): Float = if (isFinite()) this else fallback
private fun Float.unit(): Float = finiteOr(0f).coerceIn(-1f, 1f)
private fun Float.positiveUnit(): Float = finiteOr(0f).coerceIn(0f, 1f)

internal fun Float.approximately(other: Float, epsilon: Float = 0.0001f): Boolean = abs(this - other) <= epsilon
