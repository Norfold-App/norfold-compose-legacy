package com.norfold.app.ui.editor.flow

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.Functions
import androidx.compose.material.icons.filled.Grid3x3
import androidx.compose.material.icons.filled.HorizontalRule
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Notes
import androidx.compose.material.icons.filled.Schema
import androidx.compose.material.icons.filled.Title
import androidx.compose.material.icons.filled.Transform
import androidx.compose.material.icons.outlined.FormatListBulleted
import androidx.compose.material.icons.outlined.FormatListNumbered
import androidx.compose.material.icons.outlined.FormatQuote
import androidx.compose.material.icons.outlined.PostAdd
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.norfold.app.editor.flow.FlowBlockKind
import com.norfold.app.editor.flow.FlowCommandGroup
import com.norfold.app.editor.flow.FlowIds
import com.norfold.app.editor.flow.FlowRailSlot
import com.norfold.app.editor.flow.FlowRailState
import com.norfold.app.editor.motion.MotionCurve
import com.norfold.app.editor.motion.MotionLevel
import com.norfold.app.editor.motion.MotionProperty
import com.norfold.app.editor.motion.MotionSurfaceClass
import com.norfold.app.ui.components.NorfoldMotion

/** The rail's height. One thumb-sized row, so the line being written stays visible above the IME. */
private val RailHeight = 56.dp

/**
 * The Flow command rail: what a bare `/` opens on a touch device.
 *
 * It is horizontal and rides the IME rather than standing vertically at the caret, because a vertical bar at
 * the caret overlays the line being written, while the strip above the keyboard covers no prose and is where
 * the thumb already is. The vertical form of this rail belongs to the canvas hosts — Document, Shape Builder,
 * Image Focus, Chart Studio — which have a gutter and no IME.
 *
 * The `/` itself stays in the block text. [com.norfold.app.editor.flow.FlowSlashResolver] is a pure function
 * of text and caret, and erasing the trigger together with running the command as one undo unit needs the
 * trigger to still be there. So the rail *supplants* the glyph visually and the buffer is untouched — which
 * is also what keeps type-to-filter, backspace-to-dismiss and the IME behaving normally.
 *
 * @param onOpenList raises the full searchable list over the rail, which stays live underneath.
 */
@Composable
internal fun FlowCommandRailBar(
    rail: FlowRailState,
    onCommand: (String) -> Unit,
    onGroup: (FlowCommandGroup) -> Unit,
    onBack: () -> Unit,
    onOpenList: () -> Unit,
) {
    // The surface grows from a bar-sized seed with its origin at the top left, which reads as the rail
    // emerging from the `/` rather than arriving from off-screen. Geometry and opacity are planned
    // separately because reduced motion treats them differently: the growth collapses to an immediate
    // bounds change while the fade survives, since a fade conveys no travel to be spared.
    val grow by animateFloatAsState(
        targetValue = 1f,
        animationSpec = NorfoldMotion.spec(
            level = MotionLevel.SURFACE,
            property = MotionProperty.GEOMETRY,
            surface = MotionSurfaceClass.CHROME,
            curve = MotionCurve.CARET,
        ),
        label = "railGrow",
    )
    // The fade is a level below the growth on purpose. It is not the surface transition — it only keeps the
    // growth from starting on a hard edge — so it should be finished well before the bounds settle. Asking
    // SURFACE for a short duration would just be clamped up to 260 ms and arrive with the geometry.
    val fade by animateFloatAsState(
        targetValue = 1f,
        animationSpec = NorfoldMotion.spec(
            level = MotionLevel.STATE,
            property = MotionProperty.OPACITY,
            surface = MotionSurfaceClass.CHROME,
            curve = MotionCurve.CARET,
            durationMillis = MotionLevel.STATE.minMillis,
        ),
        label = "railFade",
    )
    val listState = rememberLazyListState()

    // Keyboard focus scrolls the strip rather than the strip scrolling under a fixed highlight, so an arrow
    // walk cannot leave the focused slot off-screen.
    LaunchedEffect(rail.focusedIndex, rail.group) {
        if (rail.slots.isNotEmpty()) listState.animateScrollToItem(rail.focusedIndex)
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .graphicsLayer {
                transformOrigin = TransformOrigin(0f, 0f)
                scaleX = 0.96f + 0.04f * grow
                scaleY = 0.96f + 0.04f * grow
                translationY = (1f - grow) * 7.dp.toPx()
            }
            .alpha(fade),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 3.dp,
    ) {
        Row(
            Modifier.fillMaxWidth().height(RailHeight).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            rail.group?.let { group ->
                Text(
                    group.label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 8.dp, end = 4.dp),
                )
            }
            LazyRow(
                state = listState,
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                itemsIndexed(rail.slots, key = { _, slot -> slot.id }) { index, slot ->
                    FlowRailButton(
                        slot = slot,
                        focused = index == rail.focusedIndex,
                        onClick = {
                            when (slot) {
                                FlowRailSlot.Back -> onBack()
                                is FlowRailSlot.Group -> onGroup(slot.group)
                                is FlowRailSlot.Command -> onCommand(slot.id)
                            }
                        },
                    )
                }
            }
            // The trailing chevron is the rail's escape hatch to names. It is always present, so a command
            // the icons cannot express is never unreachable from here.
            FlowRailIconButton(
                icon = Icons.Filled.ExpandLess,
                description = "Show all commands",
                onClick = onOpenList,
            )
        }
    }
}

/** One rail slot. Icons only: a label under a 40 dp target would be unreadable at this height. */
@Composable
private fun FlowRailButton(slot: FlowRailSlot, focused: Boolean, onClick: () -> Unit) {
    val tint = when {
        !slot.enabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
        focused -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Box(
        Modifier
            .semantics {
                selected = focused
                contentDescription = slotDescription(slot)
            }
            .background(
                if (focused) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f) else Color.Transparent,
                RoundedCornerShape(12.dp),
            ),
        contentAlignment = Alignment.Center,
    ) {
        FlowRailIconButton(
            icon = railIcon(slot),
            description = null,
            enabled = slot.enabled,
            tint = tint,
            onClick = onClick,
        )
    }
}

@Composable
private fun FlowRailIconButton(
    icon: ImageVector,
    description: String?,
    enabled: Boolean = true,
    tint: Color = LocalContentColor.current,
    onClick: () -> Unit,
) {
    // A 48 dp target rather than Material's icon-button default, because this strip is the primary command
    // surface on a phone and the reference material's icon-only precision controls were too small to hit.
    Box(
        Modifier.size(48.dp).clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = description, tint = tint, modifier = Modifier.size(22.dp))
    }
}

/** What a screen reader hears. A group announces its size so "Insert" is not a mystery destination. */
private fun slotDescription(slot: FlowRailSlot): String = when (slot) {
    FlowRailSlot.Back -> "Back to all commands"
    is FlowRailSlot.Group -> "${slot.label}, ${slot.size} commands"
    is FlowRailSlot.Command -> slot.item.accessibilityLabel()
}

/**
 * The icon for a slot, keyed off the command id rather than matched against its label. A label-substring
 * guess silently degrades to a default when a command is renamed; an id-keyed table is wrong loudly, and the
 * ids are already constants.
 */
private fun railIcon(slot: FlowRailSlot): ImageVector = when (slot) {
    FlowRailSlot.Back -> Icons.AutoMirrored.Filled.ArrowBack
    is FlowRailSlot.Group -> when (slot.group) {
        FlowCommandGroup.INSERT -> Icons.Outlined.PostAdd
        FlowCommandGroup.TURN_INTO -> Icons.Filled.Transform
        FlowCommandGroup.TABLE -> Icons.Filled.Grid3x3
        FlowCommandGroup.ACTIONS -> Icons.Filled.Bolt
    }
    is FlowRailSlot.Command -> commandIcon(slot.id)
}

private fun commandIcon(id: String): ImageVector = when (id) {
    FlowIds.INSERT_IMAGE -> Icons.Filled.AddPhotoAlternate
    else -> when (kindOf(id)) {
        FlowBlockKind.PARAGRAPH -> Icons.Filled.Notes
        FlowBlockKind.HEADING_1, FlowBlockKind.HEADING_2, FlowBlockKind.HEADING_3,
        FlowBlockKind.HEADING_4, FlowBlockKind.HEADING_5, FlowBlockKind.HEADING_6,
        -> Icons.Filled.Title
        FlowBlockKind.BULLET_LIST -> Icons.Outlined.FormatListBulleted
        FlowBlockKind.NUMBERED_LIST -> Icons.Outlined.FormatListNumbered
        FlowBlockKind.TODO_LIST -> Icons.Filled.Checklist
        FlowBlockKind.QUOTE -> Icons.Outlined.FormatQuote
        FlowBlockKind.CODE -> Icons.Filled.Code
        FlowBlockKind.DIVIDER -> Icons.Filled.HorizontalRule
        FlowBlockKind.CALLOUT -> Icons.Filled.Info
        FlowBlockKind.TABLE -> Icons.Filled.Grid3x3
        FlowBlockKind.MATH -> Icons.Filled.Functions
        FlowBlockKind.MERMAID -> Icons.Filled.Schema
        FlowBlockKind.CHART -> Icons.Filled.BarChart
        null -> Icons.Filled.Bolt
    }
}

/** The block kind an insert/convert command addresses, or null for a command that is not about a kind. */
private fun kindOf(id: String): FlowBlockKind? = FlowBlockKind.entries.firstOrNull { kind ->
    id == FlowIds.insert(kind) || id == FlowIds.convert(kind)
}
