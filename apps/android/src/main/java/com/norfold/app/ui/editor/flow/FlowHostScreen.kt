package com.norfold.app.ui.editor.flow

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlin.math.roundToInt
import com.norfold.app.domain.CodeBlock
import com.norfold.app.domain.DocumentBlock
import com.norfold.app.domain.DropTarget
import com.norfold.app.domain.MathBlock
import com.norfold.app.domain.MermaidBlock
import com.norfold.app.editor.command.CommandSurface
import com.norfold.app.editor.flow.FlowCommandItem
import com.norfold.app.editor.flow.FlowDragGeometry
import com.norfold.app.editor.flow.FlowDropPlacement
import com.norfold.app.editor.flow.FlowHostUiState
import com.norfold.app.editor.flow.FlowRowBounds
import com.norfold.app.editor.flow.FlowSlashContext
import com.norfold.app.editor.flow.FlowSurfaces
import com.norfold.app.editor.presentation.FormFactor
import com.norfold.app.editor.presentation.PresentationMode
import com.norfold.app.editor.surface.DismissTrigger
import com.norfold.app.editor.surface.SurfaceClass

/**
 * FlowHost — the writing-first Notes host (roadmap Phase 1 deliverables 1, 5, 6, 10).
 *
 * This composable owns layout and gestures and nothing else. Every action it offers is
 * `presenter.act { invoke(id, surface) }` against the shared registry, so the `+` palette, the slash menu,
 * a keyboard chord and an accessibility action are four presentations of one command rather than four
 * implementations of one idea. Nothing here decides whether a command is eligible, what it does, or how it
 * undoes.
 *
 * Rendering is virtualized at the top-level block — the same granularity the note is persisted at — so a long
 * note composes what is on screen and the instrumentation hook reports the range it actually built.
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun FlowHostScreen(
    presenter: FlowHostPresenter,
    title: String,
    onTitleChange: (String) -> Unit,
    onOpenSidebar: () -> Unit,
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state = presenter.state
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = presenter.runtime.restoredScrollIndex())
    var notice by remember { mutableStateOf<String?>(null) }

    // ── Adaptive presentation: the window drives the mode, never the other way round (§2.6) ──
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val imeBottom = WindowInsets.ime.getBottom(density)
    LaunchedEffect(configuration.screenWidthDp, configuration.screenHeightDp, imeBottom > 0) {
        presenter.act {
            onFormFactorChanged(
                FormFactor(
                    widthDp = configuration.screenWidthDp,
                    heightDp = configuration.screenHeightDp,
                    keyboardOpen = imeBottom > 0,
                ),
            )
        }
    }

    // ── Virtualization hook: report what the list genuinely composed ──
    LaunchedEffect(listState) {
        snapshotFlow { listState.firstVisibleItemIndex to listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index }
            .collect { (first, last) -> presenter.runtime.reportVisibleRange(first, last ?: first) }
    }

    BackHandler {
        when (presenter.act { back() }) {
            is com.norfold.app.editor.flow.FlowBackResult.LeftEditor -> onExit()
            else -> Unit
        }
    }

    // The lower surfaces stack — notice, slash results, selection bar, dock — and their combined height is
    // whatever they happen to be, not a constant anyone can guess right. Measuring it and padding the list by
    // exactly that much is what keeps the last paragraph and the active caret reachable (correction item 8).
    var lowerSurfaceHeight by remember { mutableStateOf(0) }
    val lowerSurfaceDp = with(density) { lowerSurfaceHeight.toDp() }

    val drag = rememberFlowDragState()

    Box(
        modifier
            .fillMaxSize()
            .onPreviewKeyEvent { event ->
                // Unbound keys fall through untouched, so ordinary typing and the IME are never intercepted.
                event.toFlowShortcut()?.let { presenter.act { handleShortcut(it) } } ?: false
            },
    ) {
        Column(Modifier.fillMaxSize()) {
            FlowTopBar(
                state = state,
                title = title,
                onTitleChange = onTitleChange,
                onOpenSidebar = onOpenSidebar,
                onUndo = { presenter.act { undo() } },
                onRedo = { presenter.act { redo() } },
                onEnterSelection = { presenter.act { enterSelectionMode(state.primaryId) } },
                compact = configuration.screenWidthDp < FLOW_COMPACT_WIDTH_DP,
            )
            HorizontalDivider()
            Box(Modifier.weight(1f)) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .onGloballyPositioned { drag.viewportTop = it.positionInWindow().y }
                        .onSizeChanged { drag.viewportHeight = it.height.toFloat() },
                    contentPadding = PaddingValues(
                        start = 20.dp,
                        end = 20.dp,
                        top = 16.dp,
                        // Reserve the real height of everything docked below, so the last block and the active
                        // caret can always be scrolled clear of it instead of sitting under it.
                        bottom = 16.dp + lowerSurfaceDp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(state.document.blocks, key = { it.id }) { block ->
                        Box(
                            Modifier
                                .fillMaxWidth()
                                // Writing-first measure: the *row* fills the list so the centering works, and
                                // the content inside it stops at a comfortable line length. Putting the
                                // width cap on a fillMaxWidth() row would have been defeated by the fill.
                                .onGloballyPositioned { coordinates ->
                                    val top = coordinates.positionInWindow().y - drag.viewportTop
                                    drag.bounds[block.id] = FlowRowBounds(
                                        blockId = block.id,
                                        top = top,
                                        bottom = top + coordinates.size.height,
                                    )
                                },
                            contentAlignment = Alignment.TopCenter,
                        ) {
                            FlowBlockRow(
                                block = block,
                                state = state,
                                presenter = presenter,
                                drag = drag,
                                onNotice = { notice = it },
                                modifier = Modifier.widthIn(max = FLOW_CONTENT_MAX_WIDTH),
                            )
                        }
                    }
                }
                drag.placement?.let { placement ->
                    FlowDropMarker(
                        placement = placement,
                        modifier = Modifier
                            .fillMaxWidth()
                            .zIndex(1f),
                    )
                }
            }
        }

        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                // The dock rides the keyboard: IME padding first, navigation bars only when it is closed.
                .imePadding()
                .navigationBarsPadding()
                .onSizeChanged { lowerSurfaceHeight = it.height },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            notice?.let { FlowNotice(it) { notice = null } }
            state.slash?.let { session ->
                FlowSlashMenu(
                    session = session,
                    onPick = { id ->
                        val result = presenter.act { acceptSlash(id) }
                        (result as? com.norfold.app.editor.command.CommandResult.Failure)?.let { notice = it.reason }
                    },
                    onDismiss = { presenter.act { closeSlash(DismissTrigger.CANCEL) } },
                )
            }
            if (state.selectionMode) {
                FlowSelectionBar(
                    count = state.selectedCount,
                    onCancel = { presenter.act { cancelSelectionMode() } },
                    onDone = { presenter.act { finishSelectionMode() } },
                )
            }
            FlowContextDock(
                state = state,
                onCommand = { item -> presenter.invokeItem(item, CommandSurface.DOCK) { notice = it } },
                onOpenInsert = { presenter.act { openInsertPalette(state.primaryId) } },
                onOpenConvert = {
                    presenter.act { openSurface(FlowSurfaces.CONVERT_MENU, SurfaceClass.COMMAND_MENU) }
                },
            )
        }

        FlowPaletteSurface(
            state = state,
            presenter = presenter,
            onNotice = { notice = it },
        )
    }

    // Keep the edited line above the keyboard and the dock. Driven by the runtime's caret request rather than
    // by focus, so a caret the *runtime* moved (split, merge, undo) scrolls into view exactly like a tap does.
    val caretRequest = state.caretRequest
    LaunchedEffect(caretRequest?.stamp, lowerSurfaceHeight, imeBottom) {
        val target = caretRequest ?: return@LaunchedEffect
        val index = state.document.blocks.indexOfFirst { it.id == target.blockId }
        if (index < 0) return@LaunchedEffect
        val visible = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }
        val limit = listState.layoutInfo.viewportEndOffset - lowerSurfaceHeight
        if (visible == null || visible.offset + visible.size > limit || visible.offset < 0) {
            listState.animateScrollToItem(index)
        }
    }
}

/** Mutable, non-snapshot drag bookkeeping plus the snapshot state the marker and the lift read. */
@androidx.compose.runtime.Stable
private class FlowDragState {
    /** Measured row bounds, keyed by block id. Written on every layout pass; not observed. */
    val bounds = mutableMapOf<String, FlowRowBounds>()
    var viewportTop: Float = 0f
    var viewportHeight: Float = 0f

    var sourceId: String? by mutableStateOf(null)
    var lift: Float by mutableStateOf(0f)
    var placement: FlowDropPlacement? by mutableStateOf(null)

    /** Rows in document order, which is the order [FlowDragGeometry] resolves indices against. */
    fun orderedBounds(order: List<String>): List<FlowRowBounds> = order.mapNotNull { bounds[it] }

    fun reset() {
        sourceId = null
        lift = 0f
        placement = null
    }
}

@Composable
private fun rememberFlowDragState(): FlowDragState = remember { FlowDragState() }

/**
 * The insertion marker: a line exactly where the block will land.
 *
 * Drawn from the same [FlowDropPlacement] the drop commits, so the preview cannot promise one position and
 * the command perform another (correction items 3 and 4). A no-op placement is drawn muted rather than hidden
 * — "this changes nothing" is feedback, silence is not.
 */
@Composable
private fun FlowDropMarker(placement: FlowDropPlacement, modifier: Modifier = Modifier) {
    val color = if (placement.isNoOp) {
        MaterialTheme.colorScheme.outlineVariant
    } else {
        MaterialTheme.colorScheme.primary
    }
    Box(
        modifier
            .offset { IntOffset(0, placement.markerY.roundToInt() - 1) }
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .height(3.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(color),
    )
}

/** One block: its content, its `+` affordance, its selection state, and its accessibility actions. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FlowBlockRow(
    block: DocumentBlock,
    state: FlowHostUiState,
    presenter: FlowHostPresenter,
    drag: FlowDragState,
    onNotice: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val selected = block.id in state.selectedIds
    val haptics = LocalHapticFeedback.current
    val dragging = drag.sourceId == block.id
    val callbacks = remember(block.id, state.revision) {
        FlowBlockCallbacks(
            onEdit = { edit ->
                presenter.act {
                    editText(edit.blockId, edit.before, edit.after, edit.itemId)
                    setCaret(edit.blockId, edit.caret, edit.itemId)
                    // The runtime builds the slash input, because whether the caret sits in a verbatim inline
                    // span is a fact only the document holds (correction item 6).
                    onTextInput(
                        edit.blockId,
                        slashInputFor(
                            blockId = edit.blockId,
                            text = edit.after,
                            caret = edit.caret,
                            itemId = edit.itemId,
                            context = slashContextOf(block),
                        ),
                    )
                }
            },
            onCaret = { blockId, itemId, caret -> presenter.act { setCaret(blockId, caret, itemId) } },
            onToggleTodo = { blockId, itemId -> presenter.act { toggleTodo(blockId, itemId) } },
            onTableCell = { cursor -> presenter.act { setTableCursor(cursor) } },
            onNewLine = { presenter.act { newLine() } },
            onBackspaceAtStart = { presenter.act { backspaceAtStart() } },
            onToggleCollapsed = { blockId -> presenter.act { toggleCollapsed(blockId) } },
        )
    }

    Row(
        modifier
            .graphicsLayer {
                // The lifted clone rides above the list and stays translucent, so the destination marker under
                // it remains visible instead of being covered by the thing being moved (correction item 4).
                translationY = if (dragging) drag.lift else 0f
                alpha = if (dragging) 0.85f else 1f
                shadowElevation = if (dragging) 12f else 0f
            }
            .zIndex(if (dragging) 2f else 0f)
            .clip(RoundedCornerShape(8.dp))
            .background(
                when {
                    // The source keeps a visible placeholder tint so its origin stays legible during the drag.
                    dragging -> MaterialTheme.colorScheme.surfaceVariant
                    selected -> MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
                    else -> Color.Transparent
                },
            )
            .combinedClickable(
                onClick = {
                    if (state.selectionMode) presenter.act { toggleSelection(block.id) }
                    else presenter.act { setCaret(block.id, 0) }
                },
                // Long-press is the second, gesture-native way into multi-select; the top bar's Select
                // button is the first. Both call the same runtime entry point.
                onLongClick = { presenter.act { enterSelectionMode(block.id) } },
            )
            .semantics {
                // Actions are resolved against *this* block, so the availability and reason a screen reader
                // announces describe the row it is on rather than whatever happened to be selected
                // (correction item 14). Execution re-selects the row and runs the same registry command.
                customActions = presenter.runtime.accessibilityActionsFor(block.id).map { item ->
                    CustomAccessibilityAction(item.accessibilityLabel()) {
                        if (!item.enabled) {
                            onNotice(item.disabledReason ?: "'${item.label}' is not available here")
                        } else {
                            val result = presenter.act { invokeAccessibilityOn(block.id, item.id) }
                            if (result is com.norfold.app.editor.command.CommandResult.Failure) {
                                onNotice(result.reason)
                            }
                        }
                        true
                    }
                }
            }
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.Top,
    ) {
        // Tap inserts; long-press starts a reorder. Both live on the handle so an ordinary long-press in the
        // text still means multi-select.
        IconButton(
            onClick = { presenter.act { openInsertPalette(block.id) } },
            modifier = Modifier
                .width(36.dp)
                .pointerInput(block.id, state.document.blocks.size) {
                    detectDragGesturesAfterLongPress(
                        onDragStart = {
                            drag.sourceId = block.id
                            drag.lift = 0f
                            drag.placement = null
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            presenter.act { beginDrag(block.id) }
                        },
                        onDrag = { change, amount ->
                            change.consume()
                            drag.lift += amount.y
                            val order = state.document.blocks.map { it.id }
                            val rows = drag.orderedBounds(order)
                            val self = drag.bounds[block.id]
                            val pointer = (self?.center ?: 0f) + drag.lift
                            val next = FlowDragGeometry.placementFor(rows, block.id, pointer)
                            if (next?.index != drag.placement?.index) {
                                if (next != null && !next.isNoOp) {
                                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                }
                                drag.placement = next
                            }
                        },
                        onDragEnd = {
                            val placement = drag.placement
                            if (placement == null || placement.isNoOp) {
                                // Same position, or nowhere to place: cancel, so history records nothing and
                                // the list snaps back to exactly its pre-drag state.
                                presenter.act { cancelDrag() }
                            } else {
                                val result = presenter.act {
                                    completeDrag(DropTarget.Into(parentId = null, index = placement.index))
                                }
                                if (result is com.norfold.app.editor.command.CommandResult.Failure) {
                                    onNotice(result.reason)
                                } else {
                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                }
                            }
                            drag.reset()
                        },
                        onDragCancel = {
                            presenter.act { cancelDrag() }
                            drag.reset()
                        },
                    )
                },
        ) {
            Text("+", style = MaterialTheme.typography.titleMedium)
        }
        Box(Modifier.weight(1f)) {
            FlowBlockContent(
                block = block,
                callbacks = callbacks,
                modifier = Modifier.fillMaxWidth(),
                context = FlowBlockContext(
                    caretRequest = state.caretRequest,
                    selectionMode = state.selectionMode,
                    tableCursor = state.tableCursor,
                ),
            )
        }
    }
}

/**
 * The header (correction items 11 and 12).
 *
 * On a compact window the five controls no longer share one row and squeeze each other: navigation, the title
 * and Undo/Redo stay visible, and Select moves into an overflow menu — a real, labelled surface, not a hidden
 * gesture. The title is the only element allowed to take the remaining space, because it is the only one whose
 * content is unbounded.
 *
 * The title is a text field, not a label. `onTitleChange` was previously wired to nothing the user could
 * reach; here typing in the field is the one intentional edit interaction, and navigating never lands a caret
 * in it by accident because nothing else focuses it.
 */
@Composable
private fun FlowTopBar(
    state: FlowHostUiState,
    title: String,
    onTitleChange: (String) -> Unit,
    onOpenSidebar: () -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onEnterSelection: () -> Unit,
    compact: Boolean,
) {
    var overflow by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = onOpenSidebar) { Text("Notes") }
        FlowTitleField(
            title = title,
            onTitleChange = onTitleChange,
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 8.dp),
        )
        TextButton(onClick = onUndo, enabled = state.canUndo) { Text("Undo") }
        TextButton(onClick = onRedo, enabled = state.canRedo) { Text("Redo") }
        if (compact) {
            Box {
                IconButton(onClick = { overflow = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = "More actions")
                }
                DropdownMenu(expanded = overflow, onDismissRequest = { overflow = false }) {
                    DropdownMenuItem(
                        text = { Text(if (state.showMultiSelectHint) "Select blocks" else "Select") },
                        onClick = {
                            overflow = false
                            onEnterSelection()
                        },
                    )
                }
            }
        } else {
            // The multi-select entry is a permanent, labelled control — not a hidden long-press. The hint
            // appears until the affordance has been used once (GUIDE_DISCOVERY, not a user setting).
            TextButton(onClick = onEnterSelection) {
                Text(if (state.showMultiSelectHint) "Select blocks" else "Select")
            }
        }
    }
}

/**
 * The note title, editable in place.
 *
 * The field is re-seeded when the *owner's* value changes — a rename from elsewhere, or a process recreation
 * restoring the saved title — but not merely because it differs from the local text. Comparing against the
 * last observed owner value is what distinguishes "someone else changed it" from "the owner has not caught up
 * with this keystroke yet"; re-seeding on the latter would delete characters as they are typed.
 */
@Composable
private fun FlowTitleField(title: String, onTitleChange: (String) -> Unit, modifier: Modifier = Modifier) {
    var value by remember { mutableStateOf(title) }
    var lastOwner by remember { mutableStateOf(title) }
    if (title != lastOwner) {
        lastOwner = title
        value = title
    }
    Box(modifier) {
        if (value.isBlank()) {
            Text(
                "Untitled",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            )
        }
        BasicTextField(
            value = value,
            onValueChange = {
                // A title is one line by definition; newlines pasted in are flattened rather than silently
                // producing a header that renders differently everywhere it is shown.
                val single = it.replace("\n", " ")
                value = single
                lastOwner = single
                onTitleChange(single)
            },
            singleLine = true,
            textStyle = MaterialTheme.typography.titleMedium.copy(
                fontWeight = FontWeight.SemiBold,
                color = LocalContentColor.current,
            ),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun FlowSelectionBar(count: Int, onCancel: () -> Unit, onDone: () -> Unit) {
    Surface(
        tonalElevation = 3.dp,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onCancel) { Text("Cancel") }
            Text(
                text = if (count == 1) "1 block selected" else "$count blocks selected",
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 8.dp),
            )
            TextButton(onClick = onDone) { Text("Done") }
        }
    }
}

/**
 * The context dock. Disabled entries stay visible and say *why* when tapped — a greyed control that explains
 * itself is more useful than one that silently vanishes (§4.2).
 */
@Composable
private fun FlowContextDock(
    state: FlowHostUiState,
    onCommand: (FlowCommandItem) -> Unit,
    onOpenInsert: () -> Unit,
    onOpenConvert: () -> Unit,
) {
    Surface(
        tonalElevation = 2.dp,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        Row(
            Modifier
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AssistChip(onClick = onOpenInsert, label = { Text("Insert") })
            if (state.convertPalette.isNotEmpty()) {
                AssistChip(onClick = onOpenConvert, label = { Text("Turn into") })
            }
            (state.dock + state.tableActions).forEach { item ->
                FlowCommandChip(item, onCommand)
            }
        }
    }
}

@Composable
private fun FlowCommandChip(item: FlowCommandItem, onCommand: (FlowCommandItem) -> Unit) {
    AssistChip(
        onClick = { onCommand(item) },
        label = { Text(item.label) },
        enabled = true,
        colors = AssistChipDefaults.assistChipColors(
            labelColor = if (item.enabled) MaterialTheme.colorScheme.onSurface
            else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
        ),
    )
}

/**
 * The slash menu. Its only input is the `/query` already in the block text — there is deliberately no search
 * field here, so the list can never disagree with what the user typed.
 *
 * Its height is capped at roughly five rows and the remainder scrolls (correction item 9). An unbounded Column
 * of results would grow over the line being edited, which is the one thing the user needs to keep seeing while
 * choosing a command.
 */
@Composable
private fun FlowSlashMenu(
    session: com.norfold.app.editor.flow.FlowSlashSession,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    ElevatedCard(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "/${session.query.query}",
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onDismiss) { Text("Close") }
        }
        HorizontalDivider()
        LazyColumn(Modifier.heightIn(max = FLOW_COMPACT_LIST_MAX_HEIGHT)) {
            items(session.results, key = { it.command.id }) { result ->
                val enabled = result.status.isInvokable
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clickable(enabled = enabled, onClick = { onPick(result.command.id) })
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    Text(
                        result.command.label,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (enabled) MaterialTheme.colorScheme.onSurface
                        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                    )
                    if (!enabled) {
                        Text(
                            result.status.reason ?: "Not available here",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

/**
 * The `+` palette and the "Turn into" list, presented the way the Adaptive Presentation runtime chose.
 * The tool's state lives in that runtime, so rotating from popover to pane does not reset it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FlowPaletteSurface(
    state: FlowHostUiState,
    presenter: FlowHostPresenter,
    onNotice: (String) -> Unit,
) {
    val surfaceId = state.activeSurfaceId ?: return
    val items = when (surfaceId) {
        FlowSurfaces.INSERT_PALETTE -> state.insertPalette
        FlowSurfaces.CONVERT_MENU -> state.convertPalette
        else -> return
    }
    val heading = if (surfaceId == FlowSurfaces.INSERT_PALETTE) "Insert" else "Turn into"
    val dismiss: () -> Unit = { presenter.act { closeSurface(surfaceId, DismissTrigger.OUTSIDE_TAP) } }
    val compact = state.presentationMode == PresentationMode.PHONE_SHEET ||
        state.presentationMode == PresentationMode.PHONE_POPOVER
    val body = @Composable {
        FlowPaletteList(
            heading = heading,
            items = items,
            recentId = state.recentInsertId.takeIf { surfaceId == FlowSurfaces.INSERT_PALETTE },
            compact = compact,
            onPick = { item -> presenter.invokeItem(item, CommandSurface.DOCK, onNotice) },
        )
    }

    when (state.presentationMode) {
        PresentationMode.PHONE_SHEET, PresentationMode.PHONE_POPOVER -> {
            val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)
            ModalBottomSheet(onDismissRequest = dismiss, sheetState = sheetState) { body() }
        }
        PresentationMode.TABLET_PANE, PresentationMode.EXPANDED_THREE_PANEL -> {
            Box(Modifier.fillMaxSize()) {
                ElevatedCard(
                    Modifier
                        .align(Alignment.CenterEnd)
                        .width(FLOW_PANE_WIDTH)
                        .padding(16.dp),
                ) { body() }
            }
        }
    }
}

@Composable
private fun FlowPaletteList(
    heading: String,
    items: List<FlowCommandItem>,
    recentId: String?,
    compact: Boolean,
    onPick: (FlowCommandItem) -> Unit,
) {
    Column(Modifier.padding(bottom = 24.dp)) {
        Text(
            heading,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
        )
        val ordered = items.sortedByDescending { it.id == recentId }
        // Bounded on a compact window so the list keeps the document visible behind it and scrolls the rest;
        // the tablet pane is given the room it has (correction item 9).
        LazyColumn(
            if (compact) Modifier.heightIn(max = FLOW_COMPACT_LIST_MAX_HEIGHT) else Modifier,
        ) {
            items(ordered, key = { it.id }) { item ->
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clickable(onClick = { onPick(item) })
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            item.label,
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (item.enabled) MaterialTheme.colorScheme.onSurface
                            else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                            modifier = Modifier.weight(1f),
                        )
                        item.shortcut?.let {
                            Text(it.display(), style = MaterialTheme.typography.labelSmall)
                        }
                        if (item.id == recentId) {
                            Text("Recent", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    item.disabledReason?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FlowNotice(message: String, onDismiss: () -> Unit) {
    Surface(
        tonalElevation = 4.dp,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(message, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            TextButton(onClick = onDismiss) { Text("OK") }
        }
    }
}

/**
 * Invokes an item, or explains why it cannot run. Disabled controls stay tappable on purpose: the reason the
 * command resolved to DISABLED is more useful to the user than an inert control, and it is the same reason
 * every other surface would state.
 */
private fun FlowHostPresenter.invokeItem(
    item: FlowCommandItem,
    surface: CommandSurface,
    onNotice: (String) -> Unit,
) {
    if (!item.enabled) {
        onNotice(item.disabledReason ?: "'${item.label}' is not available here")
        return
    }
    val result = act { invoke(item.id, surface) }
    if (result is com.norfold.app.editor.command.CommandResult.Failure) onNotice(result.reason)
}

/** Blocks whose `/` is content, so the slash resolver stays closed inside them. */
private fun slashContextOf(block: DocumentBlock): FlowSlashContext = when (block) {
    is CodeBlock -> FlowSlashContext.CODE
    is MathBlock -> FlowSlashContext.EQUATION
    is MermaidBlock -> FlowSlashContext.DIAGRAM
    else -> FlowSlashContext.PROSE
}

/** Comfortable measure for continuous prose; wider windows gain margin, not line length. */
private val FLOW_CONTENT_MAX_WIDTH = 760.dp
private val FLOW_PANE_WIDTH = 360.dp

/** Below this the header sheds its secondary control into an overflow menu rather than squeezing. */
private const val FLOW_COMPACT_WIDTH_DP = 400

/** Roughly five rows: enough to choose from, little enough that the document stays visible behind. */
private val FLOW_COMPACT_LIST_MAX_HEIGHT = 260.dp
