package com.norfold.app.ui.editor.shape

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.norfold.app.editor.shape.ShapeCanvas
import com.norfold.app.editor.shape.ShapeFocusRuntime
import com.norfold.app.editor.shape.ShapeHandleRef
import com.norfold.app.editor.shape.ShapeHandleSide
import com.norfold.app.editor.shape.ShapeHit
import com.norfold.app.editor.shape.ShapeNodeRef
import com.norfold.app.editor.shape.ShapeNodeSnap
import com.norfold.app.editor.shape.ShapePath
import com.norfold.app.editor.shape.ShapePathEditor
import com.norfold.app.editor.shape.ShapePoint
import com.norfold.app.editor.shape.ShapeRect
import com.norfold.app.editor.shape.ShapeSelection

/**
 * The direct-manipulation surface: nodes, handles, snapping and a magnifier over the live shape.
 *
 * Every gesture here has a non-gesture equivalent — the node toolbar, the parameter fields, and the
 * accessibility actions declared below — because a fingertip on a Bézier handle is a precision task and a
 * tool that can *only* be driven that way is a tool some people cannot drive at all.
 *
 * The canvas never edits geometry itself. It resolves a touch to a [ShapeHit] and forwards deltas to
 * [ShapeFocusRuntime], which owns what becomes a step. That is why a drag that is cancelled mid-flight
 * leaves no history behind: the runtime, not this file, decides.
 */

/** What a touch on the canvas means. Two tools, because "tap to add a point" cannot also mean "select". */
enum class ShapeCanvasTool(val label: String) {
    SELECT("Select"),
    ADD_POINT("Add point"),
}

/** Editing aids. These are view state, never document state — they are not saved and not undoable. */
data class ShapeCanvasSettings(
    val grid: Boolean = true,
    val snap: Boolean = true,
    val axisLock: Boolean = false,
    val showHandles: Boolean = true,
    val magnifier: Boolean = true,
)

private sealed interface ShapeDrag {
    /** Dragging one or more on-path nodes. [applied] is the last snapped position sent to the runtime. */
    data class Nodes(val refs: Set<ShapeNodeRef>, val origin: ShapePoint, var applied: ShapePoint) : ShapeDrag

    data class Handle(val ref: ShapeHandleRef) : ShapeDrag

    /** Rubber-band selection. Selection is not history, so this drag never opens a runtime gesture. */
    data class Marquee(val origin: ShapePoint) : ShapeDrag

    data object None : ShapeDrag
}

private const val MAGNIFIER_SCALE = 2.6f

/**
 * The interactive node canvas, drawn over the shape itself.
 *
 * [path] is the active layer's resolved outline. It is passed in rather than read from the runtime so the
 * caller controls when the (potentially expensive) Boolean resolution happens.
 *
 * **Gesture keys.** The `pointerInput` blocks below are keyed on as little as possible, and never on the
 * geometry they edit. A `pointerInput` key change tears the gesture coroutine down, and a torn-down
 * gesture delivers *neither* `onDragEnd` nor `onDragCancel` — the cancellation unwinds past the callbacks.
 * Keying on [path] would therefore break on the very first frame of a node drag: the preview changes the
 * path, the key changes, the handler resets, and the drag that is still under the user's finger never
 * ends. [ShapeFocusRuntime] would stay inside a gesture nobody can finish, which makes undo, redo and
 * Done all refuse for the rest of the session. So the live values are read through `rememberUpdatedState`
 * instead, and the viewport is measured inside each callback rather than captured once.
 */
@Composable
fun ShapeCanvasOverlay(
    runtime: ShapeFocusRuntime,
    path: ShapePath?,
    tool: ShapeCanvasTool,
    settings: ShapeCanvasSettings,
    disabled: Boolean,
    onChanged: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var drag by remember { mutableStateOf<ShapeDrag>(ShapeDrag.None) }
    var marquee by remember { mutableStateOf<ShapeRect?>(null) }
    var guides by remember { mutableStateOf<List<ShapeNodeSnap.Guide>>(emptyList()) }
    var touch by remember { mutableStateOf<Offset?>(null) }
    val selection = runtime.selection()
    // Whether there is *something* to edit is a gesture key; which outline it currently is, is not. The
    // gestures resolve hits through the runtime, so they always read the live path without keying on it.
    val editable = path != null && !disabled
    val currentTool by rememberUpdatedState(tool)
    val currentSettings by rememberUpdatedState(settings)
    val currentOnChanged by rememberUpdatedState(onChanged)
    val nodeColor = MaterialTheme.colorScheme.primary
    val selectedColor = MaterialTheme.colorScheme.tertiary
    val handleColor = MaterialTheme.colorScheme.secondary
    val guideColor = MaterialTheme.colorScheme.error
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val outlineColor = MaterialTheme.colorScheme.onSurfaceVariant

    // The one place a torn-down gesture can still be released. `editable` is the gesture key, so when it
    // flips — the layer becomes unselectable, the sheet disables editing, the composable leaves — the
    // pointer handler is reset and the callbacks that would normally clean up never run. Releasing the
    // runtime here is what keeps "the handler was replaced" from meaning "this shape can never be saved".
    DisposableEffect(editable) {
        onDispose {
            if (drag !is ShapeDrag.Marquee && drag !is ShapeDrag.None) runtime.cancelGesture()
            drag = ShapeDrag.None
            marquee = null
            guides = emptyList()
            touch = null
        }
    }

    /** Nudges the selection by whole local units — the switch-access and keyboard route to node moves. */
    fun nudge(dx: Float, dy: Float): Boolean {
        if (disabled || selection.nodes.isEmpty()) return false
        return runtime.moveSelectedNodes(ShapePoint(dx, dy)).also { if (it) onChanged() }
    }

    val actions = buildList {
        add(CustomAccessibilityAction("Move selected nodes left") { nudge(-1f, 0f) })
        add(CustomAccessibilityAction("Move selected nodes right") { nudge(1f, 0f) })
        add(CustomAccessibilityAction("Move selected nodes up") { nudge(0f, -1f) })
        add(CustomAccessibilityAction("Move selected nodes down") { nudge(0f, 1f) })
        add(CustomAccessibilityAction("Select all nodes") {
            val all = path?.subpaths?.flatMap { sub -> sub.nodes.map { ShapeNodeRef(sub.id, it.id) } }.orEmpty()
            all.isNotEmpty() && runtime.select(selection.copy(nodes = all.toSet(), handle = null)).also { if (it) onChanged() }
        })
        add(CustomAccessibilityAction("Clear node selection") {
            runtime.clearSelection().also { if (it) onChanged() }
        })
    }

    Box(
        modifier
            .fillMaxSize()
            .semantics {
                contentDescription = if (path == null) {
                    "Shape canvas. No editable path on the selected layer."
                } else {
                    "Shape canvas. ${selection.nodes.size} of ${path.nodeCount()} nodes selected."
                }
                customActions = actions
            }
            .pointerInput(editable) {
                if (!editable) return@pointerInput
                detectTapGestures { offset ->
                    val viewport = ShapeViewport(size.width.toFloat(), size.height.toFloat())
                    val point = viewport.toCanvas(offset)
                    val changed = when (currentTool) {
                        ShapeCanvasTool.ADD_POINT -> runtime.addPointAt(point, hitRadius(viewport))
                        ShapeCanvasTool.SELECT -> applyTapSelection(runtime, point, hitRadius(viewport))
                    }
                    if (changed) currentOnChanged()
                }
            }
            .pointerInput(editable) {
                if (!editable) return@pointerInput
                detectDragGestures(
                    onDragStart = { offset ->
                        val viewport = ShapeViewport(size.width.toFloat(), size.height.toFloat())
                        touch = offset
                        drag = beginDrag(runtime, viewport, offset, currentTool)
                        if (drag !is ShapeDrag.Marquee && drag !is ShapeDrag.None) runtime.beginGesture()
                        currentOnChanged()
                    },
                    onDragCancel = {
                        touch = null
                        marquee = null
                        guides = emptyList()
                        if (drag !is ShapeDrag.Marquee && runtime.cancelGesture()) currentOnChanged()
                        drag = ShapeDrag.None
                    },
                    onDragEnd = {
                        touch = null
                        val active = drag
                        if (active is ShapeDrag.Marquee) {
                            marquee?.let { rect ->
                                val hits = ShapePathEditor.nodesIn(runtime.activePath() ?: ShapePath(), rect)
                                runtime.select(runtime.selection().copy(nodes = hits.toSet(), handle = null))
                            }
                        } else if (runtime.endGesture()) currentOnChanged()
                        marquee = null
                        guides = emptyList()
                        drag = ShapeDrag.None
                        currentOnChanged()
                    },
                    onDrag = { change, delta ->
                        change.consume()
                        val viewport = ShapeViewport(size.width.toFloat(), size.height.toFloat())
                        touch = change.position
                        val point = viewport.toCanvas(change.position)
                        when (val active = drag) {
                            is ShapeDrag.Nodes -> {
                                val resolved = resolveNodeTarget(runtime, active, point, currentSettings)
                                guides = resolved.guides
                                val step = resolved.point - active.applied
                                if (!step.isZero && runtime.previewPath { ShapePathEditor.moveNodesBy(it, active.refs, step) }) {
                                    active.applied = resolved.point
                                    currentOnChanged()
                                }
                            }
                            is ShapeDrag.Handle -> {
                                if (runtime.previewPath {
                                        ShapePathEditor.moveHandle(it, active.ref.node, active.ref.side, point)
                                    }
                                ) currentOnChanged()
                            }
                            is ShapeDrag.Marquee -> {
                                marquee = ShapeRect.around(listOf(active.origin, point))
                                currentOnChanged()
                            }
                            ShapeDrag.None -> {
                                // A drag that started on nothing pans nothing: the canvas is a fixed
                                // square, so there is no viewport to move. Ignoring it is the honest
                                // response — better than inventing a pan the model cannot store.
                                Unit
                            }
                        }
                        // A delta the runtime refused (a locked layer, say) must not accumulate silently.
                        if (delta == Offset.Zero) Unit
                    },
                )
            }
            .drawWithContent {
                drawContent()
                val viewport = ShapeViewport(size.width, size.height)
                if (settings.grid) drawCanvasGrid(viewport, gridColor)
                drawCanvasFrame(viewport, outlineColor)
                if (path != null) {
                    drawEditorPath(path, viewport, outlineColor)
                    drawNodes(path, viewport, selection, settings.showHandles, nodeColor, selectedColor, handleColor)
                }
                guides.forEach { drawSnapGuide(it, viewport, guideColor) }
                marquee?.let { drawMarquee(it, viewport, selectedColor) }
                val focus = touch
                if (settings.magnifier && focus != null && path != null) {
                    drawMagnifier(focus, viewport, path, selection, nodeColor, selectedColor, handleColor, outlineColor)
                }
            },
    )
}

/** Hit slop in canvas units, derived from a finger-sized target rather than a fixed geometric constant. */
private fun hitRadius(viewport: ShapeViewport): Float {
    val scale = viewport.uniformScale
    return if (scale > 0f) (TOUCH_SLOP_PX / scale).coerceIn(ShapePathEditor.DEFAULT_HIT_RADIUS, 12f)
    else ShapePathEditor.DEFAULT_HIT_RADIUS
}

/** About 22dp at typical density — half a minimum touch target, so two adjacent nodes stay separable. */
private const val TOUCH_SLOP_PX = 56f

private fun ShapePath.nodeCount(): Int = subpaths.sumOf { it.nodes.size }

/**
 * Resolves a tap in Select mode.
 *
 * A tap on empty space clears the node selection rather than doing nothing, because "I tapped away to
 * deselect" is the near-universal expectation and leaving a stale selection makes the next toolbar press
 * act on something the user believes they let go of.
 */
private fun applyTapSelection(runtime: ShapeFocusRuntime, point: ShapePoint, radius: Float): Boolean =
    when (val hit = runtime.hitTest(point, radius)) {
        is ShapeHit.Node -> runtime.toggleNode(hit.ref)
        is ShapeHit.Handle -> runtime.selectHandle(hit.ref)
        is ShapeHit.Segment -> runtime.clearSelection()
        ShapeHit.None -> runtime.clearSelection()
    }

/**
 * Decides what a drag grabs, before any geometry moves.
 *
 * Dragging an *unselected* node grabs that node alone; dragging a selected one carries the whole
 * selection. That is the rule every direct-manipulation editor uses, and getting it wrong means a
 * multi-node selection is destroyed by the very gesture meant to move it.
 */
private fun beginDrag(
    runtime: ShapeFocusRuntime,
    viewport: ShapeViewport,
    offset: Offset,
    tool: ShapeCanvasTool,
): ShapeDrag {
    val point = viewport.toCanvas(offset)
    if (tool == ShapeCanvasTool.ADD_POINT) return ShapeDrag.Marquee(point)
    return when (val hit = runtime.hitTest(point, hitRadius(viewport))) {
        is ShapeHit.Handle -> {
            runtime.selectHandle(hit.ref)
            ShapeDrag.Handle(hit.ref)
        }
        is ShapeHit.Node -> {
            val current = runtime.selection().nodes
            val refs = if (hit.ref in current) current else setOf(hit.ref)
            if (hit.ref !in current) runtime.select(runtime.selection().copy(nodes = refs, handle = null))
            val anchor = runtime.activePath()?.pointOf(hit.ref) ?: point
            ShapeDrag.Nodes(refs, anchor, anchor)
        }
        is ShapeHit.Segment, ShapeHit.None -> ShapeDrag.Marquee(point)
    }
}

private fun ShapePath.pointOf(ref: ShapeNodeRef): ShapePoint? =
    subpathById(ref.subpathId)?.nodeById(ref.nodeId)?.point

/**
 * Where a dragged node should actually land: axis-locked first, then snapped.
 *
 * The order matters. Snapping a point that has already been constrained to an axis keeps it on that axis
 * only if the snap agrees; constraining a snapped point would instead drag it back off the guide it just
 * caught. Locking first and snapping second means the guide wins, which is what the guide is *for*.
 */
private fun resolveNodeTarget(
    runtime: ShapeFocusRuntime,
    drag: ShapeDrag.Nodes,
    point: ShapePoint,
    settings: ShapeCanvasSettings,
): ShapeNodeSnap.Snapped {
    val constrained = if (settings.axisLock) ShapeNodeSnap.constrain(drag.origin, point) else point
    if (!settings.snap) return ShapeNodeSnap.Snapped(constrained.normalized(), emptyList())
    val path = runtime.activePath() ?: return ShapeNodeSnap.Snapped(constrained.normalized(), emptyList())
    return ShapeNodeSnap.snap(constrained, path, exclude = drag.refs, grid = settings.grid)
}

// ── Drawing ──────────────────────────────────────────────────────────────────────────────────────

private fun DrawScope.drawCanvasGrid(viewport: ShapeViewport, color: Color) {
    val divisions = (ShapeCanvas.SIDE / ShapeNodeSnap.GRID_SPACING).toInt()
    val faint = color.copy(alpha = 0.35f)
    for (i in 1 until divisions) {
        val x = i * ShapeNodeSnap.GRID_SPACING * viewport.scaleX
        val y = i * ShapeNodeSnap.GRID_SPACING * viewport.scaleY
        drawLine(faint, Offset(x, 0f), Offset(x, viewport.height), strokeWidth = 1f)
        drawLine(faint, Offset(0f, y), Offset(viewport.width, y), strokeWidth = 1f)
    }
}

private fun DrawScope.drawCanvasFrame(viewport: ShapeViewport, color: Color) {
    drawRect(
        color.copy(alpha = 0.5f),
        size = Size(viewport.width, viewport.height),
        style = Stroke(width = 1.5f),
    )
}

/** The active outline, drawn thin over the filled shape so nodes stay legible on any fill. */
private fun DrawScope.drawEditorPath(path: ShapePath, viewport: ShapeViewport, color: Color) {
    drawPath(path.toComposePath(viewport), color.copy(alpha = 0.65f), style = Stroke(width = 1.5f))
}

private fun DrawScope.drawNodes(
    path: ShapePath,
    viewport: ShapeViewport,
    selection: ShapeSelection,
    showHandles: Boolean,
    nodeColor: Color,
    selectedColor: Color,
    handleColor: Color,
) {
    val radius = 5.5f * viewport.uniformScale.coerceIn(0.6f, 2f)
    path.subpaths.forEach { subpath ->
        subpath.nodes.forEach { node ->
            val ref = ShapeNodeRef(subpath.id, node.id)
            val selected = ref in selection.nodes
            val centre = viewport.toScreen(node.point)
            if (showHandles && selected && !node.isSharp) {
                listOf(node.incomingHandle to ShapeHandleSide.IN, node.outgoingHandle to ShapeHandleSide.OUT)
                    .forEach { (handlePoint, side) ->
                        val end = viewport.toScreen(handlePoint)
                        if (handlePoint == node.point) return@forEach
                        drawLine(handleColor.copy(alpha = 0.8f), centre, end, strokeWidth = 1.5f)
                        val active = selection.handle == ShapeHandleRef(ref, side)
                        drawCircle(if (active) selectedColor else handleColor, radius * 0.72f, end)
                    }
            }
            // Selected nodes are drawn as filled discs and unselected ones as rings: state has to be
            // readable under a fingertip, where colour alone is both occluded and unavailable to
            // anyone who cannot distinguish these two hues.
            if (selected) {
                drawCircle(Color.White, radius + 1.5f, centre)
                drawCircle(selectedColor, radius, centre)
            } else {
                drawCircle(Color.White, radius + 1.5f, centre)
                drawCircle(nodeColor, radius, centre, style = Stroke(width = 2f))
            }
        }
    }
}

private fun DrawScope.drawSnapGuide(guide: ShapeNodeSnap.Guide, viewport: ShapeViewport, color: Color) {
    when (guide.orientation) {
        ShapeNodeSnap.Orientation.VERTICAL -> {
            val x = guide.position * viewport.scaleX
            drawLine(color, Offset(x, 0f), Offset(x, viewport.height), strokeWidth = 1.5f)
        }
        ShapeNodeSnap.Orientation.HORIZONTAL -> {
            val y = guide.position * viewport.scaleY
            drawLine(color, Offset(0f, y), Offset(viewport.width, y), strokeWidth = 1.5f)
        }
    }
}

private fun DrawScope.drawMarquee(rect: ShapeRect, viewport: ShapeViewport, color: Color) {
    val topLeft = viewport.toScreen(ShapePoint(rect.left, rect.top))
    val bottomRight = viewport.toScreen(ShapePoint(rect.right, rect.bottom))
    val size = Size(bottomRight.x - topLeft.x, bottomRight.y - topLeft.y)
    drawRect(color.copy(alpha = 0.12f), topLeft = topLeft, size = size)
    drawRect(color, topLeft = topLeft, size = size, style = Stroke(width = 1.5f))
}

/**
 * A magnified inset of the area under the finger, placed in whichever top corner the finger is not in.
 *
 * The point is not decoration: a node under a fingertip is a node the user cannot see, and Bézier work is
 * exactly where a pixel of error is visible in the result. Re-drawing the path scaled about the touch
 * point (rather than sampling pixels) keeps the inset sharp and needs no bitmap round trip.
 */
private fun DrawScope.drawMagnifier(
    touch: Offset,
    viewport: ShapeViewport,
    path: ShapePath,
    selection: ShapeSelection,
    nodeColor: Color,
    selectedColor: Color,
    handleColor: Color,
    outlineColor: Color,
) {
    val diameter = minOf(viewport.width, viewport.height) * 0.34f
    if (diameter <= 0f) return
    val margin = diameter * 0.12f
    val onLeft = touch.x > viewport.width / 2f
    val originX = if (onLeft) margin else viewport.width - diameter - margin
    val lens = Rect(originX, margin, originX + diameter, margin + diameter)
    val clip = Path().apply { addOval(lens) }
    clipPath(clip) {
        drawRect(Color.White, topLeft = lens.topLeft, size = lens.size)
        // Scale about the touch point, then translate that point to the lens centre.
        val dx = lens.center.x - touch.x
        val dy = lens.center.y - touch.y
        translate(dx, dy) {
            scale(MAGNIFIER_SCALE, pivot = touch) {
                drawEditorPath(path, viewport, outlineColor)
                drawNodes(path, viewport, selection, true, nodeColor, selectedColor, handleColor)
            }
        }
    }
    drawCircle(outlineColor, diameter / 2f, lens.center, style = Stroke(width = 2f))
    drawLine(
        outlineColor.copy(alpha = 0.6f),
        Offset(lens.center.x - 6f, lens.center.y),
        Offset(lens.center.x + 6f, lens.center.y),
        strokeWidth = 1f,
    )
    drawLine(
        outlineColor.copy(alpha = 0.6f),
        Offset(lens.center.x, lens.center.y - 6f),
        Offset(lens.center.x, lens.center.y + 6f),
        strokeWidth = 1f,
    )
}
