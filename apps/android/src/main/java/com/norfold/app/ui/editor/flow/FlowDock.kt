package com.norfold.app.ui.editor.flow

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.norfold.app.domain.ChartBlock
import com.norfold.app.domain.ImageBlock
import com.norfold.app.domain.SemanticActionId
import com.norfold.app.domain.findById
import com.norfold.app.domain.style.ChromeGeometry
import com.norfold.app.editor.flow.FlowCommandItem
import com.norfold.app.editor.flow.FlowHostUiState
import com.norfold.app.editor.flow.FlowIds
import com.norfold.app.editor.flow.FlowInteractionMode
import com.norfold.app.editor.motion.MotionLevel
import com.norfold.app.editor.motion.MotionProperty
import com.norfold.app.editor.motion.MotionSurfaceClass
import com.norfold.app.editor.presentation.FormFactor
import com.norfold.app.editor.relationship.RelationshipCommandIds
import com.norfold.app.editor.style.EditorStyleCommandIds
import com.norfold.app.ui.NorfoldChromeSurface
import com.norfold.app.ui.components.NorfoldMotion
import com.norfold.app.ui.components.SemanticEditorIcon
import com.norfold.app.ui.rememberChromeFocus

/**
 * The floating Flow context dock — rebuilt around *identity* rather than caption.
 *
 * The dock this replaces chose each glyph by reading the command's human label: `label.contains("bold")`,
 * `label.contains("link")`, and an `else` that drew a generic text-fields icon for everything it did not
 * recognise. A caption is not an identifier — it is localised, it is edited for tone, and two unrelated
 * commands can share a word — so that scheme silently mis-drew or blanked commands whenever a label moved.
 *
 * Here every button resolves its command id to a [SemanticActionId] through [flowSemanticAction] and draws it
 * with [SemanticEditorIcon], so the glyph follows the active icon family (Lucide by default) and a renamed or
 * relocalised command keeps its icon. The resolver is total: anything it does not recognise falls back to
 * [SemanticActionId.More] instead of guessing.
 *
 * Two style quick-tools — Color and Typography — expand in place over the primary row. That expansion is a
 * [MotionLevel.SURFACE] transition (the level whose own definition is "menu to expanded controls"), so it
 * obeys the shared reduce-motion policy through [NorfoldMotion] rather than a hand-rolled duration.
 */
@Composable
internal fun FlowContextDock(
    state: FlowHostUiState,
    onCommand: (FlowCommandItem) -> Unit,
    onOpenInsert: () -> Unit,
    onOpenConvert: () -> Unit,
    onStyleCommand: (String) -> Unit,
    onCancelSelection: () -> Unit,
    onFinishSelection: () -> Unit,
    onOpenMore: () -> Unit,
    onOpenImage: (String) -> Unit,
    onOpenChart: (String) -> Unit,
) {
    // The open tool is a property of the current interaction: changing what is selected, or entering/leaving
    // multi-select, collapses any half-open quick-tool rather than stranding it over a selection it no longer
    // describes.
    var openTool by remember(state.interactionMode, state.selectionMode, state.primaryId) {
        mutableStateOf(FlowQuickTool.None)
    }
    val showLabels = state.formFactor.widthDp >= FLOW_DOCK_COMPACT_WIDTH_DP

    // The dock is the substrate's canonical floating capsule: it floats over content the reader is still
    // reading, so it takes the Floating lift's real shadow and no edge — there is no backdrop a resting-edge
    // ratio could honestly be measured against.
    NorfoldChromeSurface(
        lift = ChromeGeometry.Lift.Floating,
        corner = ChromeGeometry.Corner.Capsule,
        edge = false,
        modifier = Modifier
            .padding(horizontal = 14.dp, vertical = 8.dp)
            .widthIn(max = FLOW_DOCK_MAX_WIDTH)
            .fillMaxWidth(),
    ) {
        Crossfade(
            targetState = openTool,
            animationSpec = NorfoldMotion.spec(
                level = MotionLevel.SURFACE,
                property = MotionProperty.OPACITY,
                surface = MotionSurfaceClass.CHROME,
            ),
            label = "flowDockMorph",
        ) { tool ->
            when (tool) {
                FlowQuickTool.None -> FlowDockPrimaryRow(
                    state = state,
                    showLabels = showLabels,
                    onCommand = onCommand,
                    onOpenInsert = onOpenInsert,
                    onCancelSelection = onCancelSelection,
                    onFinishSelection = onFinishSelection,
                    onOpenMore = onOpenMore,
                    onOpenImage = onOpenImage,
                    onOpenChart = onOpenChart,
                    onOpenColorTool = { openTool = FlowQuickTool.Color },
                    onOpenTypographyTool = { openTool = FlowQuickTool.Typography },
                )
                FlowQuickTool.Color, FlowQuickTool.Typography -> FlowDockToolRow(
                    tool = tool,
                    textTargets = state.editorStyle.textTargetCount,
                    shapeTargets = state.editorStyle.shapeTargetCount,
                    pageTargets = state.editorStyle.pageTargetCount,
                    onStyleCommand = onStyleCommand,
                    onClose = { openTool = FlowQuickTool.None },
                )
            }
        }
    }
}

/** The three faces the dock can show. [None] is the command row; the other two are expanded style tools. */
private enum class FlowQuickTool { None, Color, Typography }

/**
 * The default command face: a pinned leading affordance, a horizontally scrollable middle of context actions,
 * and a pinned trailing cluster ending in More. Which leading affordance and which middle actions appear is
 * decided by what is selected, exactly as the runtime already reports it in [FlowHostUiState.dock].
 */
@Composable
private fun FlowDockPrimaryRow(
    state: FlowHostUiState,
    showLabels: Boolean,
    onCommand: (FlowCommandItem) -> Unit,
    onOpenInsert: () -> Unit,
    onCancelSelection: () -> Unit,
    onFinishSelection: () -> Unit,
    onOpenMore: () -> Unit,
    onOpenImage: (String) -> Unit,
    onOpenChart: (String) -> Unit,
    onOpenColorTool: () -> Unit,
    onOpenTypographyTool: () -> Unit,
) {
    val selectedObject = state.primaryId?.let(state.document::findById)
    val allCommands = state.dock + state.moreActions
    // Looked up by id, never by label — the defect this whole rebuild removes. The dock is built in
    // `FlowIds.DOCK_ORDER`, so a label search for "Move" would find `MOVE_UP` first and run the wrong command.
    fun command(id: String): FlowCommandItem? = allCommands.firstOrNull { it.id == id }

    val isObject = selectedObject != null && !state.selectionMode &&
        (selectedObject is ImageBlock || selectedObject is ChartBlock)
    val inline = state.dock.filter { it.id.startsWith(FlowIds.TEXT_PREFIX) }
    val structural = state.dock.filterNot { it.id.startsWith(FlowIds.TEXT_PREFIX) }
    val primaryActions: List<FlowCommandItem> = when (state.interactionMode) {
        FlowInteractionMode.TEXT_EDITING,
        FlowInteractionMode.TEXT_SELECTION -> inline
        FlowInteractionMode.BLOCK_SELECTION,
        FlowInteractionMode.MULTI_SELECTION,
        FlowInteractionMode.DRAGGING -> structural + state.tableActions
        FlowInteractionMode.READING -> emptyList()
    }

    Row(
        Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when {
            isObject -> {
                val editLabel = if (selectedObject is ChartBlock) "Edit chart" else "Edit image"
                FlowDockActionButton(
                    action = SemanticActionId.Edit,
                    label = editLabel,
                    onClick = {
                        when (selectedObject) {
                            is ImageBlock -> onOpenImage(selectedObject.id)
                            is ChartBlock -> onOpenChart(selectedObject.id)
                        }
                    },
                    showLabel = showLabels,
                )
                Row(
                    Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    command(FlowIds.DROP)?.let { item ->
                        FlowDockActionButton(SemanticActionId.Move, "Move", { onCommand(item) }, item.enabled, showLabel = showLabels)
                    }
                    command(FlowIds.DUPLICATE)?.let { item ->
                        FlowDockActionButton(SemanticActionId.Duplicate, "Duplicate", { onCommand(item) }, item.enabled, showLabel = showLabels)
                    }
                    command(FlowIds.DELETE)?.let { item ->
                        FlowDockActionButton(SemanticActionId.Delete, "Delete", { onCommand(item) }, item.enabled, showLabel = showLabels, destructive = true)
                    }
                }
            }
            state.selectionMode -> {
                FlowDockActionButton(SemanticActionId.Cancel, "Cancel", onCancelSelection, showLabel = showLabels)
                Text(
                    if (state.selectedCount == 1) "1 selected" else "${state.selectedCount} selected",
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            else -> {
                FlowDockActionButton(SemanticActionId.Insert, "Insert", onOpenInsert, showLabel = showLabels)
                Row(
                    Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    primaryActions.forEach { item ->
                        FlowDockActionButton(
                            action = flowSemanticAction(item.id),
                            label = item.label,
                            onClick = { onCommand(item) },
                            enabled = item.enabled,
                            selected = item.active || item.mixed,
                            showLabel = showLabels,
                            stateLabel = when {
                                item.active -> "On"
                                item.mixed -> "Mixed"
                                else -> null
                            },
                        )
                    }
                }
                if (state.editorStyle.textTargetCount > 0 ||
                    state.editorStyle.shapeTargetCount > 0 ||
                    state.editorStyle.pageTargetCount > 0
                ) {
                    FlowDockActionButton(SemanticActionId.Color, "Color", onOpenColorTool, showLabel = showLabels)
                }
                if (state.editorStyle.textTargetCount > 0) {
                    FlowDockActionButton(SemanticActionId.Style, "Style", onOpenTypographyTool, showLabel = showLabels)
                }
            }
        }
        FlowDockActionButton(SemanticActionId.More, "More", onOpenMore, showLabel = showLabels)
        if (state.selectionMode) {
            FlowDockActionButton(SemanticActionId.Done, "Done", onFinishSelection, showLabel = showLabels)
        }
    }
}

/**
 * An expanded style tool. The leading control returns to the command row; the rest are the concrete targets
 * this selection actually offers, which is why the row is built from the live target counts rather than a
 * fixed list. Labels are always shown here: a focused tool has room, and the color targets share one glyph.
 */
@Composable
private fun FlowDockToolRow(
    tool: FlowQuickTool,
    textTargets: Int,
    shapeTargets: Int,
    pageTargets: Int,
    onStyleCommand: (String) -> Unit,
    onClose: () -> Unit,
) {
    val targets = when (tool) {
        FlowQuickTool.Color -> buildList {
            if (textTargets > 0) {
                add(FlowStyleTarget("Text", EditorStyleCommandIds.TEXT_COLOR))
                add(FlowStyleTarget("Highlight", EditorStyleCommandIds.HIGHLIGHT))
            }
            if (shapeTargets > 0) {
                add(FlowStyleTarget("Fill", EditorStyleCommandIds.FILL))
                add(FlowStyleTarget("Stroke", EditorStyleCommandIds.STROKE))
            }
            if (pageTargets > 0) add(FlowStyleTarget("Page", EditorStyleCommandIds.PAGE_BACKGROUND))
        }
        FlowQuickTool.Typography -> listOf(
            FlowStyleTarget("Font", EditorStyleCommandIds.FONT_BROWSER),
            FlowStyleTarget("Styles", EditorStyleCommandIds.NAMED_STYLES),
            FlowStyleTarget("Clear", EditorStyleCommandIds.CLEAR_FORMATTING),
        )
        FlowQuickTool.None -> emptyList()
    }
    val toolIcon = if (tool == FlowQuickTool.Color) SemanticActionId.Color else SemanticActionId.Style
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FlowDockActionButton(SemanticActionId.Cancel, "Back", onClose, showLabel = false)
        Row(
            Modifier.weight(1f).horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            targets.forEach { target ->
                FlowDockActionButton(
                    action = toolIcon,
                    label = target.label,
                    onClick = {
                        onStyleCommand(target.commandId)
                        onClose()
                    },
                    showLabel = true,
                )
            }
        }
    }
}

/** One expanded-tool target: what to call it and which style command it invokes. */
private data class FlowStyleTarget(val label: String, val commandId: String)

/**
 * One dock button. Icon-led, with the label shown only when the dock is wide enough to afford it, and a 48 dp
 * minimum touch target regardless. The focus ring is measured against the button's own fill — the dock's
 * `surface`, or `secondaryContainer` when the command is on — because a button inside the capsule is drawn on
 * the capsule, not over the document the capsule floats above.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FlowDockActionButton(
    action: SemanticActionId,
    label: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    selected: Boolean = false,
    showLabel: Boolean = true,
    destructive: Boolean = false,
    stateLabel: String? = null,
) {
    val tint = when {
        !enabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
        destructive -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurface
    }
    val ringBackdrop =
        if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface
    val focus = rememberChromeFocus()
    val announcedState = stateLabel
    TooltipBox(
        // Above, explicitly: the dock floats at the bottom of the editor, so a tooltip placed below it would
        // be pushed off-screen or over the navigation bar.
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
        tooltip = { PlainTooltip { Text(label) } },
        state = rememberTooltipState(),
    ) {
        Column(
            Modifier
                .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                .clip(RoundedCornerShape(ChromeGeometry.Corner.Media.radiusDp.dp))
                .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
                .clickable(
                    interactionSource = focus.source,
                    // `LocalIndication.current`, not `ripple()`: this subtree provides its own indication and
                    // naming a ripple here would change every dock button's press feedback as a side effect.
                    indication = LocalIndication.current,
                    enabled = enabled,
                    onClick = onClick,
                )
                .then(focus.ring(ChromeGeometry.Corner.Media, backdrop = ringBackdrop))
                .semantics { if (announcedState != null) stateDescription = announcedState }
                .padding(horizontal = if (showLabel) 10.dp else 6.dp, vertical = if (showLabel) 4.dp else 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            SemanticEditorIcon(action, label, Modifier.size(24.dp), tint = tint)
            if (showLabel) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelSmall,
                    color = tint,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * Resolves a command's *identity* to the glyph that stands for it, for every id the dock, its overflow, the
 * table hooks and the relationship contextual set can carry. Keyed on the [FlowIds] / [EditorStyleCommandIds]
 * / [RelationshipCommandIds] constants rather than on strings, so a renamed command breaks the build here
 * instead of silently drawing the wrong icon. Total by construction: anything unrecognised resolves to
 * [SemanticActionId.More], the neutral fallback, never to a caption guess.
 */
internal fun flowSemanticAction(id: String): SemanticActionId = when (id) {
    FlowIds.BOLD -> SemanticActionId.Bold
    FlowIds.ITALIC -> SemanticActionId.Italic
    FlowIds.UNDERLINE -> SemanticActionId.Underline
    FlowIds.STRIKETHROUGH -> SemanticActionId.Strikethrough
    FlowIds.INLINE_CODE -> SemanticActionId.Code
    FlowIds.DUPLICATE -> SemanticActionId.Duplicate
    FlowIds.DELETE -> SemanticActionId.Delete
    FlowIds.MOVE_UP -> SemanticActionId.MoveUp
    FlowIds.MOVE_DOWN -> SemanticActionId.MoveDown
    FlowIds.DROP -> SemanticActionId.Move
    FlowIds.NEST -> SemanticActionId.NestIn
    FlowIds.UNNEST -> SemanticActionId.NestOut
    FlowIds.WRAP_COLUMN -> SemanticActionId.WrapColumns
    FlowIds.UNWRAP -> SemanticActionId.Unwrap
    FlowIds.SPLIT -> SemanticActionId.Split
    FlowIds.MERGE_PREVIOUS -> SemanticActionId.Merge
    FlowIds.INSERT_IMAGE -> SemanticActionId.Image
    RelationshipCommandIds.ENTER_CONNECT -> SemanticActionId.Link
    RelationshipCommandIds.OPEN_MANAGER -> SemanticActionId.Arrange
    RelationshipCommandIds.ADD_COMMENT -> SemanticActionId.Review
    RelationshipCommandIds.OPEN_REVIEW -> SemanticActionId.Review
    EditorStyleCommandIds.TEXT_COLOR,
    EditorStyleCommandIds.HIGHLIGHT,
    EditorStyleCommandIds.FILL,
    EditorStyleCommandIds.STROKE,
    EditorStyleCommandIds.PAGE_BACKGROUND -> SemanticActionId.Color
    EditorStyleCommandIds.FONT_BROWSER,
    EditorStyleCommandIds.NAMED_STYLES,
    EditorStyleCommandIds.CLEAR_FORMATTING -> SemanticActionId.Style
    else -> when {
        id.startsWith(FlowIds.INSERT_PREFIX) -> SemanticActionId.Insert
        id.startsWith(FlowIds.CONVERT_PREFIX) -> SemanticActionId.Paragraph
        id.startsWith(FlowIds.TABLE_PREFIX) -> SemanticActionId.Table
        else -> SemanticActionId.More
    }
}

/** How wide the dock's floating capsule may get — a toolbar's comfortable width, not the text column's. */
private val FLOW_DOCK_MAX_WIDTH = 760.dp

/** At or above this the buttons carry their labels; below it they are icon-only. Matches the phone/tablet cut. */
private const val FLOW_DOCK_COMPACT_WIDTH_DP = FormFactor.TABLET_MIN_WIDTH_DP
