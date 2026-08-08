package com.norfold.app.ui.editor.image

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.norfold.app.editor.image.ImageCropGeometry
import com.norfold.app.editor.image.ImageCropHandle
import com.norfold.app.editor.image.ImageEditDocument
import com.norfold.app.editor.image.ImageFocusRuntime
import androidx.compose.ui.draw.drawWithContent
import kotlin.math.hypot

/** Direct crop surface with edge/corner handles and complete non-gesture command equivalents. */
@Composable
fun ImageCropOverlay(
    document: ImageEditDocument,
    runtime: ImageFocusRuntime,
    disabled: Boolean,
    onChanged: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var activeHandle by remember { mutableStateOf(ImageCropHandle.MOVE) }
    val bounds = document.crop.bounds
    val ratio = ImageCropGeometry.aspectRatio(document.crop.mode, document.sourceMetadata, document.crop.quarterTurns)
    val nudge = 0.01f

    fun command(handle: ImageCropHandle, dx: Float, dy: Float): Boolean {
        if (disabled) return false
        val changed = runtime.edit { current ->
            val currentRatio = ImageCropGeometry.aspectRatio(current.crop.mode, current.sourceMetadata, current.crop.quarterTurns)
            current.copy(crop = current.crop.copy(bounds = ImageCropGeometry.drag(current.crop.bounds, handle, dx, dy, currentRatio)))
        }
        if (changed) onChanged()
        return changed
    }

    val actions = listOf(
        CustomAccessibilityAction("Move crop left") { command(ImageCropHandle.MOVE, -nudge, 0f) },
        CustomAccessibilityAction("Move crop right") { command(ImageCropHandle.MOVE, nudge, 0f) },
        CustomAccessibilityAction("Move crop up") { command(ImageCropHandle.MOVE, 0f, -nudge) },
        CustomAccessibilityAction("Move crop down") { command(ImageCropHandle.MOVE, 0f, nudge) },
        CustomAccessibilityAction("Move left edge inward") { command(ImageCropHandle.LEFT, nudge, 0f) },
        CustomAccessibilityAction("Move left edge outward") { command(ImageCropHandle.LEFT, -nudge, 0f) },
        CustomAccessibilityAction("Move right edge inward") { command(ImageCropHandle.RIGHT, -nudge, 0f) },
        CustomAccessibilityAction("Move right edge outward") { command(ImageCropHandle.RIGHT, nudge, 0f) },
        CustomAccessibilityAction("Move top edge inward") { command(ImageCropHandle.TOP, 0f, nudge) },
        CustomAccessibilityAction("Move top edge outward") { command(ImageCropHandle.TOP, 0f, -nudge) },
        CustomAccessibilityAction("Move bottom edge inward") { command(ImageCropHandle.BOTTOM, 0f, -nudge) },
        CustomAccessibilityAction("Move bottom edge outward") { command(ImageCropHandle.BOTTOM, 0f, nudge) },
    )

    androidx.compose.foundation.layout.Box(
        modifier
            .fillMaxSize()
            .semantics {
                contentDescription = "Crop image"
                customActions = actions
            }
            .pointerInput(disabled, ratio) {
                if (disabled) return@pointerInput
                detectDragGestures(
                    onDragStart = { point ->
                        activeHandle = hitTestCropHandle(
                            point, size.width.toFloat(), size.height.toFloat(), runtime.document().crop.bounds, 24.dp.toPx(),
                        )
                        runtime.beginGesture()
                    },
                    onDragCancel = {
                        if (runtime.cancelGesture()) onChanged()
                    },
                    onDragEnd = {
                        if (runtime.endGesture()) onChanged()
                    },
                    onDrag = { change, delta ->
                        change.consume()
                        if (runtime.previewGesture { current ->
                                current.copy(
                                    crop = current.crop.copy(
                                        bounds = ImageCropGeometry.drag(
                                            current.crop.bounds,
                                            activeHandle,
                                            delta.x / size.width.coerceAtLeast(1),
                                            delta.y / size.height.coerceAtLeast(1),
                                            ImageCropGeometry.aspectRatio(current.crop.mode, current.sourceMetadata, current.crop.quarterTurns),
                                        ),
                                    ),
                                )
                            }
                        ) onChanged()
                    },
                )
            }
            .drawWithContent {
                drawContent()
                val rect = Rect(
                    left = bounds.left * size.width,
                    top = bounds.top * size.height,
                    right = (bounds.left + bounds.width) * size.width,
                    bottom = (bounds.top + bounds.height) * size.height,
                )
                val shade = Color.Black.copy(alpha = 0.42f)
                drawRect(shade, size = androidx.compose.ui.geometry.Size(size.width, rect.top))
                drawRect(shade, topLeft = Offset(0f, rect.bottom), size = androidx.compose.ui.geometry.Size(size.width, size.height - rect.bottom))
                drawRect(shade, topLeft = Offset(0f, rect.top), size = androidx.compose.ui.geometry.Size(rect.left, rect.height))
                drawRect(shade, topLeft = Offset(rect.right, rect.top), size = androidx.compose.ui.geometry.Size(size.width - rect.right, rect.height))
                val stroke = 2.dp.toPx()
                drawRect(Color.White, topLeft = rect.topLeft, size = rect.size, style = Stroke(stroke))
                val radius = 6.dp.toPx()
                cropHandlePoints(rect).forEach { point ->
                    drawCircle(Color.Black.copy(alpha = 0.5f), radius + stroke, point)
                    drawCircle(Color.White, radius, point)
                }
            },
    )
}

private fun hitTestCropHandle(point: Offset, width: Float, height: Float, bounds: com.norfold.app.editor.image.NormalizedRect, hitRadiusPx: Float): ImageCropHandle {
    val rect = Rect(bounds.left * width, bounds.top * height, (bounds.left + bounds.width) * width, (bounds.top + bounds.height) * height)
    val points = cropHandlePoints(rect)
    val handles = listOf(
        ImageCropHandle.TOP_LEFT, ImageCropHandle.TOP, ImageCropHandle.TOP_RIGHT,
        ImageCropHandle.LEFT, ImageCropHandle.RIGHT,
        ImageCropHandle.BOTTOM_LEFT, ImageCropHandle.BOTTOM, ImageCropHandle.BOTTOM_RIGHT,
    )
    val nearest = points.indices.minByOrNull { hypot(point.x - points[it].x, point.y - points[it].y) }
    return if (nearest != null && hypot(point.x - points[nearest].x, point.y - points[nearest].y) <= hitRadiusPx) handles[nearest] else ImageCropHandle.MOVE
}

private fun cropHandlePoints(rect: Rect): List<Offset> = listOf(
    rect.topLeft, Offset(rect.center.x, rect.top), rect.topRight,
    Offset(rect.left, rect.center.y), Offset(rect.right, rect.center.y),
    rect.bottomLeft, Offset(rect.center.x, rect.bottom), rect.bottomRight,
)

/** Direct frame pan/pinch route. Sliders and custom actions remain equivalent keyboard/switch routes. */
@Composable
fun ImageFrameGestureOverlay(
    document: ImageEditDocument,
    runtime: ImageFocusRuntime,
    disabled: Boolean,
    onChanged: () -> Unit,
    modifier: Modifier = Modifier,
) {
    fun editFrame(scaleFactor: Float = 1f, deltaX: Float = 0f, deltaY: Float = 0f): Boolean {
        if (disabled) return false
        val changed = runtime.edit { current ->
            current.copy(frame = current.frame.copy(
                scale = current.frame.scale * scaleFactor,
                offsetX = current.frame.offsetX + deltaX,
                offsetY = current.frame.offsetY + deltaY,
            ))
        }
        if (changed) onChanged()
        return changed
    }

    val step = 0.02f
    val actions = listOf(
        CustomAccessibilityAction("Pan image left") { editFrame(deltaX = -step) },
        CustomAccessibilityAction("Pan image right") { editFrame(deltaX = step) },
        CustomAccessibilityAction("Pan image up") { editFrame(deltaY = -step) },
        CustomAccessibilityAction("Pan image down") { editFrame(deltaY = step) },
        CustomAccessibilityAction("Zoom image in") { editFrame(scaleFactor = 1.05f) },
        CustomAccessibilityAction("Zoom image out") { editFrame(scaleFactor = 0.95f) },
    )
    androidx.compose.foundation.layout.Box(
        modifier.fillMaxSize()
            .semantics {
                contentDescription = "Position image in frame"
                customActions = actions
            }
            .pointerInput(disabled) {
                if (disabled) return@pointerInput
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    runtime.beginGesture()
                    var event = awaitPointerEvent()
                    while (event.changes.any { it.pressed }) {
                        val pan = event.calculatePan()
                        val zoom = event.calculateZoom()
                        if (runtime.previewGesture { current ->
                                current.copy(frame = current.frame.copy(
                                    scale = current.frame.scale * zoom,
                                    offsetX = current.frame.offsetX + pan.x / size.width.coerceAtLeast(1),
                                    offsetY = current.frame.offsetY + pan.y / size.height.coerceAtLeast(1),
                                ))
                            }
                        ) onChanged()
                        event.changes.filter { it.positionChanged() }.forEach { it.consume() }
                        event = awaitPointerEvent()
                    }
                    if (runtime.endGesture()) onChanged()
                }
            },
    )
}
