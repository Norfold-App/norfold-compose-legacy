package com.norfold.app.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.norfold.app.branding.NorfoldPopEasing
import com.norfold.app.ui.chromeFocusRing

/**
 * Shared motion language for the app so navigation and interaction feel intentional, not cheap.
 * Keep every animation subtle and consistent by reusing these tokens and helpers.
 */
object Motion {
    const val Quick = 160      // taps, small feedback
    const val Standard = 240   // list items, screen changes
    const val Gentle = 320     // reveals, larger content
    const val PopDuration = 420 // the brand pop, below

    val Easing: Easing = FastOutSlowInEasing

    /**
     * The Norfold "pop": the green dot's overshoot from the logo intro, reused as the
     * app's success gesture so confirmations feel like the brand, not a stock scale-in.
     */
    val Pop: Easing = NorfoldPopEasing

    fun <T> spec(durationMillis: Int = Standard) = tween<T>(durationMillis, easing = Easing)
}

/** One-shot brand pop on first composition — for success surfaces that appear once. */
@Composable
fun Modifier.popIn(): Modifier {
    val scale = remember { Animatable(0f) }
    LaunchedEffect(Unit) { scale.animateTo(1f, tween(Motion.PopDuration, easing = Motion.Pop)) }
    return this.scale(scale.value)
}

/**
 * Replays the brand pop whenever [key] changes (e.g. a task flipping to Done). Renders
 * at rest on first composition so lists don't pop while scrolling into view.
 */
@Composable
fun Modifier.popOnChange(key: Any?): Modifier {
    val scale = remember { Animatable(1f) }
    var last by remember { mutableStateOf(key) }
    LaunchedEffect(key) {
        if (key != last) {
            last = key
            scale.snapTo(0.4f) // shrink, don't vanish: mid-list blinking would read as a glitch
            scale.animateTo(1f, tween(Motion.PopDuration, easing = Motion.Pop))
        }
    }
    return this.scale(scale.value)
}

/**
 * Gentle press feedback: scales the element down slightly while pressed. Attach the same
 * [interactionSource] to the element's `clickable` so the scale tracks real presses.
 */
@Composable
fun Modifier.pressScale(
    interactionSource: MutableInteractionSource,
    pressedScale: Float = 0.97f,
): Modifier {
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) pressedScale else 1f,
        animationSpec = Motion.spec(Motion.Quick),
        label = "pressScale",
    )
    return this.scale(scale)
}

/**
 * Uniform "Search everything" entry used across every page. It is a tappable pill (not a live
 * field) that opens the single global Search screen, so search behaves identically everywhere.
 */
@Composable
fun GlobalSearchBar(
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "Search everything",
    onNavigationClick: (() -> Unit)? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    val fill = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f)
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .pressScale(interaction)
            // The source was already hoisted for the press scale and already handed to `Surface`, so the
            // ring only has to join the stream the other two are on.
            .chromeFocusRing(interaction, radiusDp = 16f, surface = fill),
        shape = RoundedCornerShape(16.dp),
        color = fill,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.30f)),
        onClick = onOpen,
        interactionSource = interaction,
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onNavigationClick != null) {
                IconButton(
                    onClick = onNavigationClick,
                    modifier = Modifier.size(32.dp),
                ) {
                    Icon(Icons.Outlined.Menu, contentDescription = "Open workspace navigation", modifier = Modifier.size(20.dp))
                }
                Spacer(Modifier.size(8.dp))
            }
            Icon(Icons.Outlined.Search, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
            Text(
                placeholder,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 15.sp,
                modifier = Modifier.padding(start = 12.dp),
            )
        }
    }
}
