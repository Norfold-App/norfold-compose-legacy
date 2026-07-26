package com.norfold.app.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex

/**
 * In-editor anchored card engine. Cards render as an overlay INSIDE the editor layout
 * (not platform [androidx.compose.ui.window.Popup] windows) so that:
 *  - a chained child card can position itself off its parent card's placed bounds, and
 *  - opening a card never steals focus, so the soft keyboard stays up.
 *
 * Behavior contract (from the editor demos): cards center under/over their trigger,
 * chained cards keep the parent visible, re-opening an open key closes from that card
 * down, outside tap closes the whole stack, entrance is a quick fade/rise/scale pop.
 */
enum class EditorCardPlacement { Auto, Below, Above }

class EditorCardEntry internal constructor(
    val key: String,
    internal val anchorInRoot: Rect,
    internal val placement: EditorCardPlacement,
    internal val parentKey: String?,
    internal val content: @Composable ColumnScope.() -> Unit,
) {
    internal var placedRect by mutableStateOf<Rect?>(null)
}

class EditorCardController {
    private val anchors = mutableStateMapOf<String, Rect>()
    internal val stack = mutableStateListOf<EditorCardEntry>()
    internal var hostOriginInRoot by mutableStateOf(Offset.Zero)

    val hasOpenCards: Boolean get() = stack.isNotEmpty()
    val openKeys: List<String> get() = stack.map { it.key }

    fun isOpen(key: String): Boolean = stack.any { it.key == key }

    fun updateAnchor(key: String, boundsInRoot: Rect) {
        anchors[key] = boundsInRoot
    }

    /** Open [key]; a plain open replaces the whole stack, [stacked] chains onto the current top. */
    fun open(
        key: String,
        placement: EditorCardPlacement = EditorCardPlacement.Auto,
        stacked: Boolean = false,
        content: @Composable ColumnScope.() -> Unit,
    ) {
        val existing = stack.indexOfFirst { it.key == key }
        if (existing >= 0) {
            while (stack.size > existing) stack.removeAt(stack.size - 1)
        } else if (!stacked) {
            stack.clear()
        }
        val parent = if (stacked) stack.lastOrNull() else null
        val anchor = anchors[key] ?: parent?.anchorInRoot ?: return
        stack.add(EditorCardEntry(key, anchor, placement, parent?.key, content))
    }

    /** Toggle: closes [key] (and any chained children) if open, otherwise opens it. */
    fun toggle(
        key: String,
        placement: EditorCardPlacement = EditorCardPlacement.Auto,
        stacked: Boolean = false,
        content: @Composable ColumnScope.() -> Unit,
    ) {
        if (isOpen(key)) closeFrom(key) else open(key, placement, stacked, content)
    }

    /** Close [key] and every card chained after it. */
    fun closeFrom(key: String) {
        val idx = stack.indexOfFirst { it.key == key }
        if (idx >= 0) while (stack.size > idx) stack.removeAt(stack.size - 1)
    }

    fun closeAll() = stack.clear()

    internal fun parentOf(entry: EditorCardEntry): EditorCardEntry? =
        entry.parentKey?.let { key -> stack.firstOrNull { it.key == key } }
}

@Composable
fun rememberEditorCardController(): EditorCardController = remember { EditorCardController() }

/** Report a trigger's bounds so [EditorCardController.open] can anchor its card to it. */
fun Modifier.editorCardAnchor(controller: EditorCardController, key: String): Modifier =
    onGloballyPositioned { controller.updateAnchor(key, it.boundsInRoot()) }

/**
 * Overlay host. Place as the LAST child of the editor's root Box so cards draw above the
 * bars. Consumes outside taps only while cards are open.
 */
@Composable
fun EditorCardHost(
    controller: EditorCardController,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .fillMaxSize()
            .zIndex(20f)
            .onGloballyPositioned { controller.hostOriginInRoot = it.boundsInRoot().topLeft },
    ) {
        if (controller.hasOpenCards) {
            Box(
                Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) { detectTapGestures { controller.closeAll() } },
            )
            // Iterate over a snapshot copy: closing mid-frame mutates the stack.
            controller.stack.toList().forEach { entry ->
                PositionedEditorCard(controller, entry)
            }
        }
    }
}

private const val EDGE_PAD_DP = 10
private const val ANCHOR_GAP_BELOW_DP = 6
private const val ANCHOR_GAP_ABOVE_DP = 8
private const val STACK_GAP_DP = 6

@Composable
private fun PositionedEditorCard(controller: EditorCardController, entry: EditorCardEntry) {
    val density = LocalDensity.current
    val edgePad = with(density) { EDGE_PAD_DP.dp.roundToPx() }
    val gapBelow = with(density) { ANCHOR_GAP_BELOW_DP.dp.roundToPx() }
    val gapAbove = with(density) { ANCHOR_GAP_ABOVE_DP.dp.roundToPx() }
    val stackGap = with(density) { STACK_GAP_DP.dp.roundToPx() }
    val translate = with(density) { 4.dp.toPx() }

    val alpha = remember(entry.key) { Animatable(0f) }
    LaunchedEffect(entry.key) { alpha.animateTo(1f, tween(130)) }

    Layout(
        content = {
            NorfoldPopupCard(
                modifier = Modifier.graphicsLayer {
                    this.alpha = alpha.value
                    translationY = translate * (1f - alpha.value)
                    val scale = 0.97f + 0.03f * alpha.value
                    scaleX = scale
                    scaleY = scale
                },
                content = entry.content,
            )
        },
    ) { measurables, constraints ->
        val hostW = constraints.maxWidth
        val hostH = constraints.maxHeight
        val cardConstraints = constraints.copy(
            minWidth = 0,
            minHeight = 0,
            maxWidth = (hostW - 2 * edgePad).coerceAtLeast(0),
            maxHeight = (hostH - 2 * edgePad).coerceAtLeast(0),
        )
        val placeable = measurables.first().measure(cardConstraints)
        val origin = controller.hostOriginInRoot
        val parentRect = controller.parentOf(entry)?.placedRect
        var x: Float
        var y: Float
        if (parentRect != null) {
            // Chained card: off the parent card's far edge, top-aligned to it.
            x = parentRect.right + stackGap
            if (x + placeable.width > hostW - edgePad) {
                x = parentRect.left - stackGap - placeable.width
            }
            y = parentRect.top
        } else {
            val anchor = entry.anchorInRoot.translate(-origin)
            x = anchor.center.x - placeable.width / 2f
            val below = anchor.bottom + gapBelow
            val above = anchor.top - placeable.height - gapAbove
            val fitsBelow = below + placeable.height <= hostH - edgePad
            y = when (entry.placement) {
                EditorCardPlacement.Below -> below
                EditorCardPlacement.Above -> above
                EditorCardPlacement.Auto -> if (fitsBelow) below else above
            }
        }
        x = x.coerceIn(edgePad.toFloat(), (hostW - placeable.width - edgePad).coerceAtLeast(edgePad).toFloat())
        y = y.coerceIn(edgePad.toFloat(), (hostH - placeable.height - edgePad).coerceAtLeast(edgePad).toFloat())
        entry.placedRect = Rect(Offset(x, y), Size(placeable.width.toFloat(), placeable.height.toFloat()))
        layout(hostW, hostH) {
            placeable.place(x.toInt(), y.toInt())
        }
    }
}

// ---------------------------------------------------------------------------
// Reusable card content pieces (beyond NorfoldPopupRow/SectionLabel/Divider)
// ---------------------------------------------------------------------------

data class EditorCardChoice(
    val id: String,
    val label: String,
    val icon: ImageVector? = null,
    val glyph: String? = null,
)

/** 3-column tile grid (demo NEW BLOCK / Insert grids). */
@Composable
fun EditorCardChoiceGrid(
    choices: List<EditorCardChoice>,
    onPick: (String) -> Unit,
    modifier: Modifier = Modifier,
    columns: Int = 3,
) {
    Column(modifier.padding(2.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        choices.chunked(columns).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { choice ->
                    Surface(
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        onClick = { onPick(choice.id) },
                    ) {
                        Column(
                            Modifier.padding(vertical = 10.dp, horizontal = 4.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            if (choice.icon != null) {
                                Icon(choice.icon, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            } else {
                                Text(
                                    choice.glyph ?: "•",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Text(
                                choice.label,
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                }
                repeat(columns - row.size) { Box(Modifier.weight(1f)) }
            }
        }
    }
}


data class EditorCardSegment(
    val id: String,
    val label: String? = null,
    val icon: ImageVector? = null,
)

/** Single-row segmented control (demo alignment picker). */
@Composable
fun EditorCardSegmentRow(
    segments: List<EditorCardSegment>,
    selectedId: String?,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.padding(4.dp),
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Row(Modifier.padding(3.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            segments.forEach { segment ->
                val isSelected = segment.id == selectedId
                Surface(
                    modifier = Modifier.weight(1f).height(34.dp),
                    shape = RoundedCornerShape(8.dp),
                    color = if (isSelected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
                    onClick = { onSelect(segment.id) },
                ) {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                        if (segment.icon != null) {
                            Icon(
                                segment.icon,
                                segment.label,
                                Modifier.size(18.dp),
                                tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            Text(
                                segment.label.orEmpty(),
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Label + −/＋ stepper row (demo indent / zoom rows). Doesn't close the card. */
@Composable
fun EditorCardStepperRow(
    label: String,
    valueText: String,
    onDecrease: () -> Unit,
    onIncrease: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        StepperButton("−", onDecrease)
        Text(
            valueText,
            Modifier.width(52.dp),
            style = MaterialTheme.typography.labelMedium,
            textAlign = TextAlign.Center,
        )
        StepperButton("＋", onIncrease)
    }
}

@Composable
private fun StepperButton(glyph: String, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.size(30.dp),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        onClick = onClick,
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
            Text(glyph, style = MaterialTheme.typography.titleMedium)
        }
    }
}
