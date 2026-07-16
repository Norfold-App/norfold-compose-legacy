package com.norfold.app.ui.dnd

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector2D
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.round
import kotlinx.coroutines.launch

/**
 * Shared drag-and-drop *look* for Tasks and Docs, so both surfaces animate identically:
 * on long-press the item **lifts** (shrinks slightly, casts a stronger shadow) as a floating clone,
 * while a dashed [DropSlot] placeholder opens at the target position with animated push chevrons,
 * and neighbors slide out of the way via the list's `animateItem()`.
 */
object DragDropDefaults {
    /** Scale of the floating clone — "floats up as if shrinking". */
    const val LiftScale = 0.96f

    /** Alpha of the floating clone while dragging. */
    const val LiftAlpha = 0.95f

    /** Elevation shadow under the floating clone. */
    val LiftShadow = 16.dp

    /** Standard duration for lift/settle transitions. */
    const val MotionMillis = 180
}

/**
 * Lift treatment for a dragged item (usually its floating clone). Animates scale down to
 * [DragDropDefaults.LiftScale], raises a [DragDropDefaults.LiftShadow] shadow, and dips alpha
 * slightly so underlying content reads through the float.
 */
fun Modifier.dragLift(lifted: Boolean, cornerRadius: Dp = 12.dp): Modifier = composed {
    val scale by animateFloatAsState(
        targetValue = if (lifted) DragDropDefaults.LiftScale else 1f,
        animationSpec = tween(DragDropDefaults.MotionMillis),
        label = "dragLiftScale",
    )
    val elevation by animateFloatAsState(
        targetValue = if (lifted) DragDropDefaults.LiftShadow.value else 0f,
        animationSpec = tween(DragDropDefaults.MotionMillis),
        label = "dragLiftShadow",
    )
    val alpha by animateFloatAsState(
        targetValue = if (lifted) DragDropDefaults.LiftAlpha else 1f,
        animationSpec = tween(DragDropDefaults.MotionMillis),
        label = "dragLiftAlpha",
    )
    val shape = RoundedCornerShape(cornerRadius)
    this
        .shadow(elevation.dp, shape, clip = false)
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
            this.alpha = alpha
        }
}

/**
 * Animates position changes of a child inside a plain `Column`/`Row` (which lack LazyList's
 * `animateItem()`): when the item is re-placed at a new position, it visually slides there instead
 * of jumping. Used to make neighbors "slide/swap" around the moving [DropSlot] during a drag.
 */
fun Modifier.animatePlacement(): Modifier = composed {
    val scope = rememberCoroutineScope()
    var targetOffset by remember { mutableStateOf(IntOffset.Zero) }
    var animatable by remember { mutableStateOf<Animatable<IntOffset, AnimationVector2D>?>(null) }
    this
        .onPlaced { targetOffset = it.positionInParent().round() }
        .offset {
            val anim = animatable ?: Animatable(targetOffset, IntOffset.VectorConverter)
                .also { animatable = it }
            if (anim.targetValue != targetOffset) {
                scope.launch {
                    anim.animateTo(targetOffset, spring(stiffness = Spring.StiffnessMediumLow))
                }
            }
            anim.value - targetOffset
        }
}

/** Dashed rounded-rect outline used by [DropSlot]; also usable directly on an existing container. */
fun Modifier.dropSlotOutline(color: Color, cornerRadius: Dp = 12.dp): Modifier = drawBehind {
    val stroke = Stroke(
        width = 2.dp.toPx(),
        cap = StrokeCap.Round,
        pathEffect = PathEffect.dashPathEffect(floatArrayOf(12.dp.toPx(), 8.dp.toPx())),
    )
    val radius = CornerRadius(cornerRadius.toPx())
    drawRoundRect(color = color.copy(alpha = 0.08f), cornerRadius = radius)
    drawRoundRect(color = color, cornerRadius = radius, style = stroke)
}

/**
 * The placeholder container that "receives" the dragged item: a dashed outline with a softly pulsing
 * animated double-chevron showing where surrounding content is being pushed. Render it *in place of*
 * the item's slot (live-reordered to the current target index) so neighbors slide around it via
 * `animateItem()`.
 *
 * @param height match the dragged item's measured height so neighbors displace by the right amount.
 * @param pointDown chevrons point down when content below is being pushed down, up otherwise.
 */
@Composable
fun DropSlot(
    modifier: Modifier = Modifier,
    height: Dp = 56.dp,
    pointDown: Boolean = true,
    color: Color = MaterialTheme.colorScheme.primary,
) {
    val pulse = rememberInfiniteTransition(label = "dropSlotPulse")
    val chevronAlpha by pulse.animateFloat(
        initialValue = 0.35f,
        targetValue = 0.9f,
        animationSpec = infiniteRepeatable(tween(520), RepeatMode.Reverse),
        label = "dropSlotChevronAlpha",
    )
    val chevronShift by pulse.animateFloat(
        initialValue = -2f,
        targetValue = 2f,
        animationSpec = infiniteRepeatable(tween(520), RepeatMode.Reverse),
        label = "dropSlotChevronShift",
    )
    Box(
        modifier = modifier
            .height(height)
            .dropSlotOutline(color)
            .drawBehind {
                val w = 14.dp.toPx()
                val h = 6.dp.toPx()
                val gap = 9.dp.toPx()
                val stroke = 2.5.dp.toPx()
                val direction = if (pointDown) 1f else -1f
                val shift = chevronShift.dp.toPx() * direction
                val centerX = size.width / 2f
                val centerY = size.height / 2f
                val chevron = color.copy(alpha = chevronAlpha)
                repeat(2) { row ->
                    val baseY = centerY + (row - 0.5f) * gap + shift - direction * h / 2f
                    val tipY = baseY + direction * h
                    drawLine(chevron, Offset(centerX - w / 2f, baseY), Offset(centerX, tipY), stroke, StrokeCap.Round)
                    drawLine(chevron, Offset(centerX + w / 2f, baseY), Offset(centerX, tipY), stroke, StrokeCap.Round)
                }
            },
        contentAlignment = Alignment.Center,
    ) {}
}
