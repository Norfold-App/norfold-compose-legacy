package com.norfold.app.ui.editor.image

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
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
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
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
import com.norfold.app.editor.image.ImageFrameMode
import com.norfold.app.editor.image.ImageMaskKind
import com.norfold.app.editor.image.ImageMask
import com.norfold.app.editor.image.ImagePreviewPolicy
import com.norfold.app.editor.image.ImagePreviewInstrumentation
import kotlin.math.min
import kotlin.math.roundToInt

/** Shared, feature-gated renderer used by Flow, Document, and the Image Focus preview. */
@Composable
fun ImageFocusRenderer(
    block: ImageBlock,
    modifier: Modifier = Modifier,
    documentOverride: ImageEditDocument? = null,
    sourceOverride: String? = null,
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
    val model = remember(source, target, document.adjustments.sharpness, previewKey, retryRevision) {
        instrumentation.recordRequest(previewKey, target)
        ImageRequest.Builder(context)
            .data(source)
            .size(target.widthPx, target.heightPx)
            .apply {
                if (document.adjustments.sharpness > 0f) {
                    transformations(ImageSharpnessTransformation(document.adjustments.sharpness))
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
    val crop = document.crop.bounds
    val geometricScale = document.frame.scale / min(crop.width, crop.height).coerceAtLeast(0.01f)
    val cropCenterX = crop.left + crop.width / 2f
    val cropCenterY = crop.top + crop.height / 2f
    val translationX = ((0.5f - cropCenterX) + document.frame.offsetX) * size.width
    val translationY = ((0.5f - cropCenterY) + document.frame.offsetY) * size.height

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
            Modifier.fillMaxSize().clip(shape).background(MaterialTheme.colorScheme.surfaceVariant),
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
            AsyncImage(
                model = model,
                contentDescription = null,
                contentScale = if (document.frame.mode == ImageFrameMode.FILL) ContentScale.Crop else ContentScale.Fit,
                alignment = BiasAlignment(
                    horizontalBias = document.frame.focalX * 2f - 1f,
                    verticalBias = document.frame.focalY * 2f - 1f,
                ),
                colorFilter = ColorFilter.colorMatrix(document.colorMatrix()),
                alpha = document.effects.opacity,
                onError = { failed = true },
                onSuccess = { instrumentation.recordPresented(target.widthPx, target.heightPx) },
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = geometricScale * if (document.crop.flipHorizontal) -1f else geometricScale
                        scaleY = geometricScale * if (document.crop.flipVertical) -1f else geometricScale
                        rotationZ = document.crop.quarterTurns * 90f + document.crop.straightenDegrees
                        rotationY = document.crop.perspectiveHorizontal * 18f
                        rotationX = -document.crop.perspectiveVertical * 18f
                        this.translationX = translationX
                        this.translationY = translationY
                        cameraDistance = 16f * density
                    }
                    .then(if (document.adjustments.blur > 0f) Modifier.blur((document.adjustments.blur * 24f).dp) else Modifier),
            )
            if (document.effects.vignette > 0f) {
                Box(
                    Modifier.fillMaxSize().background(
                        Brush.radialGradient(
                            0f to Color.Transparent,
                            0.62f to Color.Transparent,
                            1f to Color.Black.copy(alpha = document.effects.vignette * 0.72f),
                        ),
                    ),
                )
            }
            }
            if (border.enabled && border.widthDp > 0f) {
                Box(Modifier.fillMaxSize().border(border.widthDp.dp, border.color.composeColor(), shape))
            }
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
    val monochrome = effects.monochrome.coerceIn(0f, 1f)
    val saturation = ((1f + adjustments.saturation) * (1f - monochrome)).coerceIn(0f, 2f)
    val inverse = 1f - saturation
    val red = 0.213f * inverse
    val green = 0.715f * inverse
    val blue = 0.072f * inverse
    val contrast = (1f + adjustments.contrast).coerceIn(0f, 2f)
    val tonal = adjustments.exposure * 96f + adjustments.highlights * 28f + adjustments.shadows * 28f
    val base = 128f * (1f - contrast) + tonal
    val warm = adjustments.temperature * 36f
    val tint = adjustments.tint * 28f
    val sepia = effects.sepia.coerceIn(0f, 1f)
    val sr = 0.393f * sepia
    val sg = 0.769f * sepia
    val sb = 0.189f * sepia
    return ColorMatrix(floatArrayOf(
        ((red + saturation) * contrast) * (1f - sepia) + sr, (green * contrast) * (1f - sepia) + sg, (blue * contrast) * (1f - sepia) + sb, 0f, base + warm,
        (red * contrast) * (1f - sepia) + 0.349f * sepia, ((green + saturation) * contrast) * (1f - sepia) + 0.686f * sepia, (blue * contrast) * (1f - sepia) + 0.168f * sepia, 0f, base + tint,
        (red * contrast) * (1f - sepia) + 0.272f * sepia, (green * contrast) * (1f - sepia) + 0.534f * sepia, ((blue + saturation) * contrast) * (1f - sepia) + 0.131f * sepia, 0f, base - warm,
        0f, 0f, 0f, 1f, 0f,
    ))
}
