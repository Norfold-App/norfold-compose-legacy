package com.norfold.app.ui.editor.image

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.norfold.app.domain.ImageBlock
import com.norfold.app.editor.image.ImageBlockAdapter
import com.norfold.app.editor.image.ImageEditDocument
import com.norfold.app.editor.image.ImageCropGeometry
import com.norfold.app.editor.image.ImageFrameMode
import com.norfold.app.editor.image.ImageFilterKind
import com.norfold.app.editor.image.ImageMaskKind
import com.norfold.app.editor.image.ImageMask
import com.norfold.app.editor.image.ImageMarkupLabelKind
import com.norfold.app.editor.image.ImageMarkupPoint
import com.norfold.app.editor.image.ImagePreviewPolicy
import com.norfold.app.editor.image.ImagePreviewInstrumentation
import com.norfold.app.editor.sticker.StickerCatalog
import kotlin.math.min
import kotlin.math.max
import kotlin.math.roundToInt

/** Shared, feature-gated renderer used by Flow, Document, and the Image Focus preview. */
@Composable
fun ImageFocusRenderer(
    block: ImageBlock,
    modifier: Modifier = Modifier,
    documentOverride: ImageEditDocument? = null,
    sourceOverride: String? = null,
    /** While a crop gesture is active, shrink the rotated source enough to keep its edges visible. */
    cropPreview: Boolean = false,
    instrumentation: ImagePreviewInstrumentation = ImagePreviewInstrumentation.Global,
) {
    val loaded = remember(block.imageEditDocument) { ImageBlockAdapter.load(block) }
    val document = documentOverride ?: loaded.document
    val source = sourceOverride ?: block.source
    val shape = document.mask.composeShape()
    val context = LocalContext.current
    var size by remember { mutableStateOf(IntSize.Zero) }
    var failed by remember(source) { mutableStateOf(false) }
    var retryRevision by remember(source) { mutableStateOf(0) }
    val target = remember(size, document.sourceMetadata) {
        ImagePreviewPolicy.target(
            sourceWidth = document.sourceMetadata.pixelWidth ?: size.width.coerceAtLeast(1),
            sourceHeight = document.sourceMetadata.pixelHeight ?: size.height.coerceAtLeast(1),
            viewWidth = size.width.coerceAtLeast(1),
            viewHeight = size.height.coerceAtLeast(1),
        )
    }
    val previewKey = remember(source, document, target) { ImagePreviewPolicy.cacheKey(source, document, target) }
    val model = remember(source, target, document.adjustments.sharpness, document.crop.freeTransform, document.effects.filter, document.effects.filterIntensity, previewKey, retryRevision) {
        instrumentation.recordRequest(previewKey, target)
        ImageRequest.Builder(context)
            .data(source)
            .size(target.widthPx, target.heightPx)
            .apply {
                val transforms = mutableListOf<coil.transform.Transformation>()
                if (document.adjustments.sharpness > 0f) {
                    transforms += ImageSharpnessTransformation(document.adjustments.sharpness)
                }
                if (!document.crop.freeTransform.isIdentity()) {
                    transforms += ImagePerspectiveTransformation(document.crop.freeTransform)
                }
                if (document.effects.filter in setOf(
                        ImageFilterKind.GRAIN,
                        ImageFilterKind.PIXELATE,
                        ImageFilterKind.POSTERIZE,
                        ImageFilterKind.EMBOSS,
                    )
                ) {
                    transforms += ImageCreativeFilterTransformation(document.effects.filter, document.effects.filterIntensity)
                }
                if (transforms.isNotEmpty()) {
                    transformations(*transforms.toTypedArray())
                }
            }
            .crossfade(false)
            .build()
    }
    val accessibility = document.accessibility
    val semanticModifier = if (accessibility.decorative) {
        Modifier.clearAndSetSemantics { }
    } else {
        Modifier.semantics {
            contentDescription = accessibility.alternativeText.ifBlank { "Image" }
            stateDescription = when {
                failed -> "Image unavailable"
                !loaded.isEditable -> "Image from a newer or unreadable editor format"
                block.caption.isNotBlank() -> "Image. Caption: ${block.caption.take(256)}"
                else -> "Image"
            }
        }
    }
    val shadow = document.shadow
    val border = document.border
    // Crop editing must show the *source* under the movable crop frame. Rendering the already-cropped
    // result beneath that frame made handles appear to move one thing while the image moved another.
    // Reading mode still renders the committed crop edge-to-edge.
    val crop = if (cropPreview) com.norfold.app.editor.image.NormalizedRect.FULL else document.crop.bounds
    // Crop geometry defines the visible frame. Frame scale is only an optional magnification; a
    // legacy value below 1 must not create borders or a thumbnail inside the editor canvas.
    val rotationDegrees = document.crop.quarterTurns * 90f + document.crop.straightenDegrees
    // A crop editor needs the entire source visible beneath the movable frame. In the committed reader we
    // expand to the crop bounds; only that reader path uses crop magnification. The old shared expression
    // magnified an uncropped source inside Crop mode and made the preview look like a wide strip.
    val cropScale = if (cropPreview) 1f else 1f / min(crop.width, crop.height).coerceAtLeast(0.01f)
    val geometricScale = document.frame.scale.coerceAtLeast(1f) * cropScale
    val sourceAspectRatio = document.sourceMetadata.pixelWidth
        ?.toFloat()
        ?.div(document.sourceMetadata.pixelHeight?.toFloat()?.coerceAtLeast(1f) ?: 1f)
        ?.takeIf { it.isFinite() && it > 0f }
        ?: (size.width.toFloat() / size.height.coerceAtLeast(1).toFloat())
    val sideways = document.crop.quarterTurns % 2 != 0
    val rotationPreviewScale = if (cropPreview) {
        // Quarter turns receive a matching viewport below. Only the free straighten angle needs
        // additional fit compensation inside that viewport.
        ImageCropGeometry.rotationScaleToFitViewport(
            document.crop.straightenDegrees,
            if (sideways) 1f / sourceAspectRatio else sourceAspectRatio,
        )
    } else 1f
    val cropCenterX = crop.left + crop.width / 2f
    val cropCenterY = crop.top + crop.height / 2f
    val translationX = (if (cropPreview) 0f else (0.5f - cropCenterX) + document.frame.offsetX) * size.width
    val translationY = (if (cropPreview) 0f else (0.5f - cropCenterY) + document.frame.offsetY) * size.height

    @Composable
    fun SourceLayer(modifier: Modifier, contentScale: ContentScale, layerAlpha: Float = 1f) {
        AsyncImage(
            model = model,
            contentDescription = null,
            contentScale = contentScale,
            alignment = BiasAlignment(
                horizontalBias = document.frame.focalX * 2f - 1f,
                verticalBias = document.frame.focalY * 2f - 1f,
            ),
            colorFilter = ColorFilter.colorMatrix(document.colorMatrix()),
            alpha = document.effects.opacity * layerAlpha,
            onError = { failed = true },
            onSuccess = { instrumentation.recordPresented(target.widthPx, target.heightPx) },
            modifier = modifier.then(
                if (max(document.adjustments.blur, if (document.effects.filter == ImageFilterKind.BLUR) document.effects.filterIntensity else 0f) > 0f) {
                    Modifier.blur((max(document.adjustments.blur, if (document.effects.filter == ImageFilterKind.BLUR) document.effects.filterIntensity else 0f) * 24f).dp)
                } else Modifier,
            ),
        )
    }

    Box(modifier.then(semanticModifier).onSizeChanged { size = it }, contentAlignment = Alignment.Center) {
        if (shadow.enabled) {
            Box(
                Modifier.fillMaxSize()
                    .offset(shadow.offsetXDp.dp, shadow.offsetYDp.dp)
                    .then(if (shadow.blurDp > 0f) Modifier.blur(shadow.blurDp.dp) else Modifier)
                    .background(shadow.color.composeColor(), shape),
            )
        }
        Box(
            Modifier
                .fillMaxSize()
                // Crop is a transparent working stage: the surrounding app theme is the canvas,
                // never an extra colored card behind the source photo.
                .then(
                    if (cropPreview) Modifier
                    else Modifier.clip(shape).background(MaterialTheme.colorScheme.surfaceVariant),
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (source.isBlank() || failed) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        if (source.isBlank()) "Choose an image" else "This image could not be loaded",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (failed) {
                        TextButton(onClick = { failed = false; retryRevision++ }) { Text("Retry") }
                    }
                }
            } else {
            // A committed crop foreground sits over a deliberately dim source backdrop. It preserves
            // orientation context while making the selected result the reading-mode subject.
            if (crop != com.norfold.app.editor.image.NormalizedRect.FULL) {
                SourceLayer(
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                    layerAlpha = 0.16f,
                )
            }
            if (cropPreview) {
                // uCrop-style transform model: the viewport changes orientation, while the source
                // remains an unrotated layer transformed around its own centre. The crop frame then
                // stays large and meaningful after Rotate rather than becoming a thumbnail canvas.
                BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    val sourceWidth = if (sideways) maxHeight else maxWidth
                    val sourceHeight = if (sideways) maxWidth else maxHeight
                    SourceLayer(
                        modifier = Modifier
                            // Do not constrain the pre-rotation source to the viewport. A 90 degree
                            // turn needs an unrotated layer whose dimensions are the viewport's
                            // inverse; `width` / `height` are coerced by BoxWithConstraints and
                            // previously reduced that layer to a thumbnail before rotation.
                            .requiredWidth(sourceWidth)
                            .requiredHeight(sourceHeight)
                            .graphicsLayer {
                                scaleX = rotationPreviewScale * if (document.crop.flipHorizontal) -1f else rotationPreviewScale
                                scaleY = rotationPreviewScale * if (document.crop.flipVertical) -1f else rotationPreviewScale
                                rotationZ = rotationDegrees
                                rotationY = document.crop.perspectiveHorizontal * 18f
                                rotationX = -document.crop.perspectiveVertical * 18f
                                cameraDistance = 16f * density
                            },
                        contentScale = ContentScale.Fit,
                    )
                }
            } else {
                SourceLayer(
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            scaleX = geometricScale * if (document.crop.flipHorizontal) -1f else geometricScale
                            scaleY = geometricScale * if (document.crop.flipVertical) -1f else geometricScale
                            rotationZ = rotationDegrees
                            rotationY = document.crop.perspectiveHorizontal * 18f
                            rotationX = -document.crop.perspectiveVertical * 18f
                            this.translationX = translationX
                            this.translationY = translationY
                            cameraDistance = 16f * density
                        },
                    contentScale = if (document.frame.mode == ImageFrameMode.FILL) ContentScale.Crop else ContentScale.Fit,
                )
            }
            val vignette = max(document.effects.vignette, document.adjustments.vignette)
            if (vignette > 0f) {
                Box(
                    Modifier.fillMaxSize().background(
                        Brush.radialGradient(
                            0f to Color.Transparent,
                            0.62f to Color.Transparent,
                            1f to Color.Black.copy(alpha = vignette * 0.72f),
                        ),
                    ),
                )
            }
            if (!cropPreview && (document.markup.strokes.isNotEmpty() || document.markup.labels.isNotEmpty())) {
                ImageMarkupLayer(document = document, modifier = Modifier.fillMaxSize())
            }
            }
            if (border.enabled && border.widthDp > 0f) {
                Box(Modifier.fillMaxSize().border(border.widthDp.dp, border.color.composeColor(), shape))
            }
        }
    }
}

@Composable
private fun ImageMarkupLayer(document: ImageEditDocument, modifier: Modifier = Modifier) {
    val labelPaint = remember { android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG) }
    val crop = document.crop.bounds
    BoxWithConstraints(modifier) {
        Canvas(Modifier.fillMaxSize()) {
        fun project(point: ImageMarkupPoint): Offset = Offset(
            ((point.x - crop.left) / crop.width * size.width).coerceIn(-size.width, size.width * 2f),
            ((point.y - crop.top) / crop.height * size.height).coerceIn(-size.height, size.height * 2f),
        )
        document.markup.strokes.forEach { stroke ->
            val points = stroke.points
            val width = stroke.width * min(size.width, size.height)
            if (points.size == 1) {
                drawCircle(stroke.color.composeColor(), radius = width / 2f, center = project(points.single()))
            } else {
                points.zipWithNext().forEach { (from, to) ->
                    drawLine(
                        color = stroke.color.composeColor(),
                        start = project(from), end = project(to), strokeWidth = width,
                        cap = StrokeCap.Round,
                    )
                }
            }
        }
        document.markup.labels.filter { it.kind != ImageMarkupLabelKind.STICKER }.forEach { label ->
            labelPaint.color = android.graphics.Color.argb(label.color.alpha, label.color.red, label.color.green, label.color.blue)
            labelPaint.textSize = label.size * min(size.width, size.height)
            labelPaint.typeface = android.graphics.Typeface.DEFAULT_BOLD
            val anchor = project(ImageMarkupPoint(label.x, label.y))
            when (label.kind) {
                ImageMarkupLabelKind.SIGNATURE -> {
                    label.points.zipWithNext().forEach { (from, to) ->
                        val fromPoint = project(ImageMarkupPoint(
                            label.x + (from.x - 0.5f) * label.size * 4f,
                            label.y + (from.y - 0.5f) * label.size * 2.2f,
                        ))
                        val toPoint = project(ImageMarkupPoint(
                            label.x + (to.x - 0.5f) * label.size * 4f,
                            label.y + (to.y - 0.5f) * label.size * 2.2f,
                        ))
                        drawLine(label.color.composeColor(), fromPoint, toPoint, strokeWidth = (label.size * min(size.width, size.height) * 0.12f).coerceAtLeast(3.dp.toPx()), cap = StrokeCap.Round)
                    }
                }
                else -> {
                    val native = drawContext.canvas.nativeCanvas
                    native.save()
                    native.rotate(label.rotationDegrees, anchor.x, anchor.y)
                    native.drawText(label.text, anchor.x, anchor.y, labelPaint)
                    native.restore()
                }
            }
        }
    }
        document.markup.labels.filter { it.kind == ImageMarkupLabelKind.STICKER }.forEach { label ->
            val sticker = StickerCatalog.find(label.id) ?: return@forEach
            val croppedX = ((label.x - crop.left) / crop.width).coerceIn(-0.2f, 1.2f)
            val croppedY = ((label.y - crop.top) / crop.height).coerceIn(-0.2f, 1.2f)
            val diameter = minOf(maxWidth, maxHeight) * (label.size * 2.8f)
            AsyncImage(
                model = sticker.assetUri,
                contentDescription = sticker.label,
                modifier = Modifier
                    .size(diameter)
                    .offset(maxWidth * croppedX - diameter / 2, maxHeight * croppedY - diameter / 2)
                    .graphicsLayer { rotationZ = label.rotationDegrees },
            )
        }
    }
}

@Composable
fun ImageCropGuides(document: ImageEditDocument, modifier: Modifier = Modifier) {
    if (document.crop.guide == com.norfold.app.editor.image.ImageCompositionGuide.NONE) return
    val lineColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f)
    Box(modifier.drawWithContent {
        drawContent()
        val stroke = 1.dp.toPx()
        when (document.crop.guide) {
            com.norfold.app.editor.image.ImageCompositionGuide.THIRDS -> {
                listOf(1f / 3f, 2f / 3f).forEach { fraction ->
                    drawLine(lineColor, Offset(size.width * fraction, 0f), Offset(size.width * fraction, size.height), stroke)
                    drawLine(lineColor, Offset(0f, size.height * fraction), Offset(size.width, size.height * fraction), stroke)
                }
            }
            com.norfold.app.editor.image.ImageCompositionGuide.GOLDEN_RATIO -> {
                listOf(0.382f, 0.618f).forEach { fraction ->
                    drawLine(lineColor, Offset(size.width * fraction, 0f), Offset(size.width * fraction, size.height), stroke)
                    drawLine(lineColor, Offset(0f, size.height * fraction), Offset(size.width, size.height * fraction), stroke)
                }
            }
            com.norfold.app.editor.image.ImageCompositionGuide.DIAGONALS -> {
                drawLine(lineColor, Offset.Zero, Offset(size.width, size.height), stroke)
                drawLine(lineColor, Offset(size.width, 0f), Offset(0f, size.height), stroke)
            }
        }
        drawRect(lineColor, size = Size(size.width, size.height), style = Stroke(stroke))
    })
}

private fun ImageMask.composeShape(): Shape = when (kind) {
    ImageMaskKind.NONE, ImageMaskKind.RECTANGLE -> RectangleShape
    ImageMaskKind.ROUNDED_RECTANGLE -> RoundedCornerShape((cornerRadius * 100f).roundToInt().coerceIn(0, 50))
    ImageMaskKind.ELLIPSE -> RoundedCornerShape(50)
}

private fun com.norfold.app.domain.style.ColorValue.composeColor() =
    Color(red, green, blue, alpha)

/** One deterministic matrix for colour adjustments; geometry, blur and vignette stay separate stages. */
private fun ImageEditDocument.colorMatrix(): ColorMatrix {
    val filterStrength = effects.filterIntensity.coerceIn(0f, 1f)
    val monochrome = max(effects.monochrome, if (effects.filter == ImageFilterKind.MONOCHROME) filterStrength else 0f).coerceIn(0f, 1f)
    val saturation = ((1f + adjustments.saturation) * (1f - monochrome)).coerceIn(0f, 2f)
    val inverse = 1f - saturation
    val red = 0.213f * inverse
    val green = 0.715f * inverse
    val blue = 0.072f * inverse
    val contrast = (1f + adjustments.contrast).coerceIn(0f, 2f)
    val tonal = adjustments.exposure * 96f + adjustments.brightness * 64f + adjustments.highlights * 28f + adjustments.shadows * 28f
    val base = 128f * (1f - contrast) + tonal
    val warm = adjustments.temperature * 36f
    val tint = adjustments.tint * 28f
    val sepia = max(effects.sepia, if (effects.filter == ImageFilterKind.SEPIA) filterStrength else 0f).coerceIn(0f, 1f)
    val sr = 0.393f * sepia
    val sg = 0.769f * sepia
    val sb = 0.189f * sepia
    val standard = ColorMatrix(floatArrayOf(
        ((red + saturation) * contrast) * (1f - sepia) + sr, (green * contrast) * (1f - sepia) + sg, (blue * contrast) * (1f - sepia) + sb, 0f, base + warm,
        (red * contrast) * (1f - sepia) + 0.349f * sepia, ((green + saturation) * contrast) * (1f - sepia) + 0.686f * sepia, (blue * contrast) * (1f - sepia) + 0.168f * sepia, 0f, base + tint,
        (red * contrast) * (1f - sepia) + 0.272f * sepia, (green * contrast) * (1f - sepia) + 0.534f * sepia, ((blue + saturation) * contrast) * (1f - sepia) + 0.131f * sepia, 0f, base - warm,
        0f, 0f, 0f, 1f, 0f,
    ))
    val creative = when (effects.filter) {
        ImageFilterKind.INVERT -> ColorMatrix(floatArrayOf(
            1f - 2f * filterStrength, 0f, 0f, 0f, 255f * filterStrength,
            0f, 1f - 2f * filterStrength, 0f, 0f, 255f * filterStrength,
            0f, 0f, 1f - 2f * filterStrength, 0f, 255f * filterStrength,
            0f, 0f, 0f, 1f, 0f,
        ))
        ImageFilterKind.DUOTONE -> ColorMatrix(floatArrayOf(
            0.28f, 0.48f, 0.12f, 0f, 22f * filterStrength,
            0.16f, 0.34f, 0.18f, 0f, 38f * filterStrength,
            0.42f, 0.18f, 0.46f, 0f, 70f * filterStrength,
            0f, 0f, 0f, 1f, 0f,
        ))
        else -> null
    }
    // A creative colour filter is an effect layer, not a replacement adjustment layer.
    // Compose's ColorMatrix has no public concatenate API, so concatenate the two affine
    // 4x5 matrices explicitly: standard adjustments first, creative colour effect second.
    return creative?.after(standard) ?: standard
}

/** Returns this matrix after [base], preserving the renderer order: Adjust then Filter. */
private fun ColorMatrix.after(base: ColorMatrix): ColorMatrix {
    val upper = values
    val lower = base.values
    val result = FloatArray(20)
    for (row in 0 until 4) {
        for (column in 0 until 4) {
            var sum = 0f
            for (index in 0 until 4) sum += upper[row * 5 + index] * lower[index * 5 + column]
            result[row * 5 + column] = sum
        }
        var offset = upper[row * 5 + 4]
        for (index in 0 until 4) offset += upper[row * 5 + index] * lower[index * 5 + 4]
        result[row * 5 + 4] = offset
    }
    return ColorMatrix(result)
}
