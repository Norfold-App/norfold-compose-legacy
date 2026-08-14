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
import com.norfold.app.editor.image.ImageFreeTransform
import com.norfold.app.editor.image.ImageFreeTransformCorner
import com.norfold.app.editor.image.ImageFocusRuntime
import com.norfold.app.editor.image.ImageMarkupPoint
import com.norfold.app.editor.image.ImageMarkupStroke
import com.norfold.app.domain.style.ColorValue
import androidx.compose.ui.draw.drawWithContent
import kotlin.math.hypot
import kotlin.math.max

/**
 * Markup is deliberately modal: a canvas cannot simultaneously interpret a drag as a new stroke
 * and as moving an existing object. This mirrors the explicit Draw/Select split used by mature
 * image editors while keeping the selection state local to the open Image Focus session.
 */
enum class ImageMarkupTool { SELECT, DRAW, ERASER, SHAPE }

/** Direct crop surface with edge/corner handles and complete non-gesture command equivalents. */
@Composable
fun ImageCropOverlay(
    document: ImageEditDocument,
    runtime: ImageFocusRuntime,
    disabled: Boolean,
    onChanged: () -> Unit,
    freeTransformEnabled: Boolean = false,
    modifier: Modifier = Modifier,
) {
    if (freeTransformEnabled) {
        ImageFreeTransformOverlay(document, runtime, disabled, onChanged, modifier)
        return
    }
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
                            point,
                            size.width.toFloat(),
                            size.height.toFloat(),
                            runtime.document().crop.bounds,
                            24.dp.toPx(),
                            0f,
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
                                            delta.x / size.width.toFloat().coerceAtLeast(1f),
                                            delta.y / size.height.toFloat().coerceAtLeast(1f),
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
                // The crop frame is the actual visible source container. Hit targets expand outside
                // it, but a visual inset would falsely imply that the crop misses the image edge.
                val rect = cropViewportRect(bounds, size.width, size.height, 0f)
                val shade = Color.Black.copy(alpha = 0.42f)
                // A full-size crop has no excluded pixels. Do not darken the handle gutter: that
                // created a false grey canvas around an otherwise transparent image stage.
                val hasExcludedArea = bounds.left > 0.001f || bounds.top > 0.001f ||
                    bounds.left + bounds.width < 0.999f || bounds.top + bounds.height < 0.999f
                if (hasExcludedArea) {
                    drawRect(shade, size = androidx.compose.ui.geometry.Size(size.width, rect.top))
                    drawRect(shade, topLeft = Offset(0f, rect.bottom), size = androidx.compose.ui.geometry.Size(size.width, size.height - rect.bottom))
                    drawRect(shade, topLeft = Offset(0f, rect.top), size = androidx.compose.ui.geometry.Size(rect.left, rect.height))
                    drawRect(shade, topLeft = Offset(rect.right, rect.top), size = androidx.compose.ui.geometry.Size(size.width - rect.right, rect.height))
                }
                val stroke = 2.dp.toPx()
                drawRect(Color.White, topLeft = rect.topLeft, size = rect.size, style = Stroke(stroke))
                // A persistent thirds grid makes the crop result legible while dragging, independent
                // of the optional saved composition guide used in reading mode.
                val grid = Color.White.copy(alpha = 0.48f)
                listOf(1f / 3f, 2f / 3f).forEach { fraction ->
                    drawLine(
                        grid,
                        Offset(rect.left + rect.width * fraction, rect.top),
                        Offset(rect.left + rect.width * fraction, rect.bottom),
                        1.dp.toPx(),
                    )
                    drawLine(
                        grid,
                        Offset(rect.left, rect.top + rect.height * fraction),
                        Offset(rect.right, rect.top + rect.height * fraction),
                        1.dp.toPx(),
                    )
                }
                val radius = 6.dp.toPx()
                cropHandlePoints(rect).forEach { point ->
                    drawCircle(Color.Black.copy(alpha = 0.5f), radius + stroke, point)
                    drawCircle(Color.White, radius, point)
                }
            },
    )
}

/** Four independent corner handles. The affine/perspective bitmap transform is stored in [ImageCrop]. */
@Composable
private fun ImageFreeTransformOverlay(
    document: ImageEditDocument,
    runtime: ImageFocusRuntime,
    disabled: Boolean,
    onChanged: () -> Unit,
    modifier: Modifier,
) {
    fun corners(transform: ImageFreeTransform) = listOf(
        transform.topLeft, transform.topRight, transform.bottomRight, transform.bottomLeft,
    )
    fun replace(transform: ImageFreeTransform, index: Int, corner: ImageFreeTransformCorner): ImageFreeTransform = when (index) {
        0 -> transform.copy(topLeft = corner)
        1 -> transform.copy(topRight = corner)
        2 -> transform.copy(bottomRight = corner)
        else -> transform.copy(bottomLeft = corner)
    }
    var activeCorner by remember { mutableStateOf<Int?>(null) }
    androidx.compose.foundation.layout.Box(
        modifier
            .fillMaxSize()
            .semantics { contentDescription = "Free distortion. Drag one of four image corners." }
            .pointerInput(disabled, document.crop.freeTransform) {
                if (disabled) return@pointerInput
                detectDragGestures(
                    onDragStart = { point ->
                        val points = corners(document.crop.freeTransform).map { Offset(it.x * size.width, it.y * size.height) }
                        activeCorner = points.indices.minByOrNull { index -> hypot(point.x - points[index].x, point.y - points[index].y) }
                            ?.takeIf { index -> hypot(point.x - points[index].x, point.y - points[index].y) <= 28.dp.toPx() }
                        if (activeCorner != null) runtime.beginGesture()
                    },
                    onDragCancel = { activeCorner = null; if (runtime.cancelGesture()) onChanged() },
                    onDragEnd = { activeCorner = null; if (runtime.endGesture()) onChanged() },
                    onDrag = { change, _ ->
                        val index = activeCorner ?: return@detectDragGestures
                        change.consume()
                        val next = ImageFreeTransformCorner(
                            change.position.x / size.width.toFloat().coerceAtLeast(1f),
                            change.position.y / size.height.toFloat().coerceAtLeast(1f),
                        ).normalized()
                        if (runtime.previewGesture { state ->
                                state.copy(crop = state.crop.copy(freeTransform = replace(state.crop.freeTransform, index, next)))
                            }
                        ) onChanged()
                    },
                )
            }
            .drawWithContent {
                drawContent()
                val points = corners(document.crop.freeTransform).map { Offset(it.x * size.width, it.y * size.height) }
                val path = androidx.compose.ui.graphics.Path().apply {
                    moveTo(points[0].x, points[0].y)
                    lineTo(points[1].x, points[1].y)
                    lineTo(points[2].x, points[2].y)
                    lineTo(points[3].x, points[3].y)
                    close()
                }
                drawPath(path, Color.White, style = Stroke(1.5.dp.toPx()))
                points.forEach { point ->
                    drawCircle(Color.Black.copy(alpha = 0.5f), radius = 9.dp.toPx(), center = point)
                    drawCircle(Color.White, radius = 6.dp.toPx(), center = point)
                }
            },
    )
}

private fun hitTestCropHandle(
    point: Offset,
    width: Float,
    height: Float,
    bounds: com.norfold.app.editor.image.NormalizedRect,
    hitRadiusPx: Float,
    insetPx: Float,
): ImageCropHandle {
    val rect = cropViewportRect(bounds, width, height, insetPx)
    val points = cropHandlePoints(rect)
    val handles = listOf(
        ImageCropHandle.TOP_LEFT, ImageCropHandle.TOP, ImageCropHandle.TOP_RIGHT,
        ImageCropHandle.LEFT, ImageCropHandle.RIGHT,
        ImageCropHandle.BOTTOM_LEFT, ImageCropHandle.BOTTOM, ImageCropHandle.BOTTOM_RIGHT,
    )
    val nearest = points.indices.minByOrNull { hypot(point.x - points[it].x, point.y - points[it].y) }
    return if (nearest != null && hypot(point.x - points[nearest].x, point.y - points[nearest].y) <= hitRadiusPx) handles[nearest] else ImageCropHandle.MOVE
}

private fun cropViewportRect(
    bounds: com.norfold.app.editor.image.NormalizedRect,
    width: Float,
    height: Float,
    insetPx: Float,
): Rect {
    val availableWidth = (width - insetPx * 2f).coerceAtLeast(1f)
    val availableHeight = (height - insetPx * 2f).coerceAtLeast(1f)
    return Rect(
        left = insetPx + bounds.left * availableWidth,
        top = insetPx + bounds.top * availableHeight,
        right = insetPx + (bounds.left + bounds.width) * availableWidth,
        bottom = insetPx + (bounds.top + bounds.height) * availableHeight,
    )
}

private fun cropHandlePoints(rect: Rect): List<Offset> = listOf(
    rect.topLeft, Offset(rect.center.x, rect.top), rect.topRight,
    Offset(rect.left, rect.center.y), Offset(rect.right, rect.center.y),
    rect.bottomLeft, Offset(rect.center.x, rect.bottom), rect.bottomRight,
)

/**
 * The markup layer writes one local-history gesture per stroke. Points are translated through the active
 * crop so a mark stays attached to the image when it is reopened on a different device or frame size.
 */
@Composable
fun ImageMarkupOverlay(
    document: ImageEditDocument,
    runtime: ImageFocusRuntime,
    disabled: Boolean,
    strokeColor: ColorValue = ColorValue(244, 67, 54),
    strokeWidth: Float = 0.014f,
    tool: ImageMarkupTool = ImageMarkupTool.DRAW,
    selectedLabelIndex: Int? = null,
    onSelectedLabelChange: (Int?) -> Unit = {},
    onChanged: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val crop = document.crop.bounds
    fun sourcePoint(offset: Offset, width: Float, height: Float): ImageMarkupPoint = ImageMarkupPoint(
        x = crop.left + (offset.x / width.coerceAtLeast(1f)) * crop.width,
        y = crop.top + (offset.y / height.coerceAtLeast(1f)) * crop.height,
    ).normalized()

    val interactionModifier = when (tool) {
        ImageMarkupTool.DRAW, ImageMarkupTool.SHAPE -> Modifier.pointerInput(disabled, crop, strokeColor, strokeWidth, tool) {
            if (disabled) return@pointerInput
            detectDragGestures(
                onDragStart = { point ->
                    if (!runtime.beginGesture()) return@detectDragGestures
                    val initial = sourcePoint(point, size.width.toFloat(), size.height.toFloat())
                    if (runtime.previewGesture { current ->
                            current.copy(
                                markup = current.markup.copy(
                                    strokes = current.markup.strokes + ImageMarkupStroke(
                                        points = listOf(initial),
                                        color = strokeColor,
                                        width = strokeWidth,
                                    ),
                                ),
                            )
                        }
                    ) onChanged()
                },
                onDragCancel = { if (runtime.cancelGesture()) onChanged() },
                onDragEnd = { if (runtime.endGesture()) onChanged() },
                onDrag = { change, _ ->
                    change.consume()
                    val point = sourcePoint(change.position, size.width.toFloat(), size.height.toFloat())
                    if (runtime.previewGesture { current ->
                            val last = current.markup.strokes.lastOrNull() ?: return@previewGesture current
                            current.copy(markup = current.markup.copy(
                                strokes = current.markup.strokes.dropLast(1) + last.copy(points = if (tool == ImageMarkupTool.SHAPE) {
                                    listOf(last.points.first(), point)
                                } else last.points + point),
                            ))
                        }
                    ) onChanged()
                },
            )
        }
        ImageMarkupTool.SELECT, ImageMarkupTool.ERASER -> Modifier.pointerInput(disabled, crop, document.markup.labels, document.markup.strokes, selectedLabelIndex, tool) {
            if (disabled) return@pointerInput
            var draggedLabelIndex: Int? = null
            fun labelAt(point: ImageMarkupPoint): Int? = document.markup.labels.indices
                .minByOrNull { index ->
                    val label = document.markup.labels[index]
                    hypot(label.x - point.x, label.y - point.y)
                }
                ?.takeIf { index ->
                    val label = document.markup.labels[index]
                    hypot(label.x - point.x, label.y - point.y) <= max(0.06f, label.size * 1.4f)
                }
            fun strokeAt(point: ImageMarkupPoint): Int? = document.markup.strokes.indices
                .minByOrNull { index ->
                    document.markup.strokes[index].points.minOfOrNull { candidate ->
                        hypot(candidate.x - point.x, candidate.y - point.y)
                    } ?: Float.MAX_VALUE
                }
                ?.takeIf { index ->
                    val stroke = document.markup.strokes[index]
                    val nearest = stroke.points.minOfOrNull { candidate -> hypot(candidate.x - point.x, candidate.y - point.y) }
                        ?: Float.MAX_VALUE
                    nearest <= max(0.025f, stroke.width * 1.8f)
                }
            detectDragGestures(
                onDragStart = { point ->
                    val source = sourcePoint(point, size.width.toFloat(), size.height.toFloat())
                    val hit = labelAt(source)
                    if (tool == ImageMarkupTool.ERASER && hit != null) {
                        runtime.edit { current -> current.copy(markup = current.markup.copy(labels = current.markup.labels.filterIndexed { index, _ -> index != hit })) }
                        onChanged()
                        onSelectedLabelChange(null)
                        return@detectDragGestures
                    }
                    if (tool == ImageMarkupTool.ERASER) {
                        val stroke = strokeAt(source)
                        if (stroke != null) {
                            runtime.edit { current -> current.copy(markup = current.markup.copy(
                                strokes = current.markup.strokes.filterIndexed { index, _ -> index != stroke },
                            )) }
                            onChanged()
                            onSelectedLabelChange(null)
                            return@detectDragGestures
                        }
                    }
                    onSelectedLabelChange(hit)
                    draggedLabelIndex = hit
                    if (hit != null) runtime.beginGesture()
                },
                onDragCancel = {
                    draggedLabelIndex = null
                    if (runtime.cancelGesture()) onChanged()
                },
                onDragEnd = {
                    draggedLabelIndex = null
                    if (runtime.endGesture()) onChanged()
                },
                onDrag = { change, delta ->
                    val index = draggedLabelIndex ?: return@detectDragGestures
                    change.consume()
                    if (runtime.previewGesture { current ->
                            val label = current.markup.labels.getOrNull(index) ?: return@previewGesture current
                            val deltaX = delta.x / size.width.toFloat().coerceAtLeast(1f) * current.crop.bounds.width
                            val deltaY = delta.y / size.height.toFloat().coerceAtLeast(1f) * current.crop.bounds.height
                            current.copy(markup = current.markup.copy(
                                labels = current.markup.labels.mapIndexed { labelIndex, candidate ->
                                    if (labelIndex == index) candidate.copy(
                                        x = candidate.x + deltaX,
                                        y = candidate.y + deltaY,
                                    ).normalized() else candidate
                                },
                            ))
                        }
                    ) onChanged()
                },
            )
        }
    }

    androidx.compose.foundation.layout.Box(
        modifier = modifier
            .fillMaxSize()
            .semantics {
                contentDescription = when (tool) {
                    ImageMarkupTool.DRAW -> "Draw annotation on image"
                    ImageMarkupTool.SHAPE -> "Draw shape annotation on image"
                    ImageMarkupTool.ERASER -> "Remove annotation from image"
                    ImageMarkupTool.SELECT -> "Select or move annotation"
                }
            }
            .then(interactionModifier)
            .drawWithContent {
                drawContent()
                val selected = selectedLabelIndex?.let(document.markup.labels::getOrNull) ?: return@drawWithContent
                val x = ((selected.x - crop.left) / crop.width * size.width).coerceIn(0f, size.width)
                val y = ((selected.y - crop.top) / crop.height * size.height).coerceIn(0f, size.height)
                val halfWidth = max(36.dp.toPx(), selected.text.length * selected.size * size.width * 0.28f)
                val halfHeight = max(22.dp.toPx(), selected.size * size.height * 0.75f)
                val outline = Color.White
                drawRect(
                    color = outline,
                    topLeft = Offset((x - halfWidth).coerceIn(0f, size.width), (y - halfHeight).coerceIn(0f, size.height)),
                    size = androidx.compose.ui.geometry.Size(
                        (halfWidth * 2f).coerceAtMost(size.width),
                        (halfHeight * 2f).coerceAtMost(size.height),
                    ),
                    style = Stroke(1.5.dp.toPx()),
                )
                // Three high-contrast handles make selection, resize and rotation discoverable;
                // precise size/rotation values remain available in the accessible control deck.
                drawCircle(outline, 6.dp.toPx(), Offset(x - halfWidth, y - halfHeight))
                drawCircle(outline, 6.dp.toPx(), Offset(x + halfWidth, y + halfHeight))
                drawCircle(outline, 6.dp.toPx(), Offset(x + halfWidth, y - halfHeight))
            },
    )
}

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
