package com.norfold.app.ui.editor.flow

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.norfold.app.domain.CodeBlock
import com.norfold.app.domain.DocumentBlock
import com.norfold.app.domain.MathBlock
import com.norfold.app.domain.MermaidBlock
import com.norfold.app.editor.command.CommandSurface
import com.norfold.app.editor.flow.FlowCommandItem
import com.norfold.app.editor.flow.FlowHostUiState
import com.norfold.app.editor.flow.FlowSlashContext
import com.norfold.app.editor.flow.FlowSlashInput
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
            )
            HorizontalDivider()
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(state.document.blocks, key = { it.id }) { block ->
                    FlowBlockRow(
                        block = block,
                        state = state,
                        presenter = presenter,
                        onNotice = { notice = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            // Writing-first measure: text stops at a comfortable line length and centres
                            // on wide windows instead of stretching to the edge of a tablet.
                            .widthIn(max = FLOW_CONTENT_MAX_WIDTH),
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
                .navigationBarsPadding(),
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
}

/** One block: its content, its `+` affordance, its selection state, and its accessibility actions. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FlowBlockRow(
    block: DocumentBlock,
    state: FlowHostUiState,
    presenter: FlowHostPresenter,
    onNotice: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val selected = block.id in state.selectedIds
    val callbacks = remember(block.id, state.revision) {
        FlowBlockCallbacks(
            onEdit = { edit ->
                presenter.act {
                    editText(edit.blockId, edit.before, edit.after, edit.itemId)
                    setCaret(edit.blockId, edit.caret)
                    onTextInput(
                        edit.blockId,
                        FlowSlashInput(
                            text = edit.after,
                            caret = edit.caret,
                            context = slashContextOf(block),
                            itemId = edit.itemId,
                        ),
                    )
                }
            },
            onCaret = { blockId, _, caret -> presenter.act { setCaret(blockId, caret) } },
            onToggleTodo = { blockId, itemId -> presenter.act { toggleTodo(blockId, itemId) } },
            onTableCell = { cursor -> presenter.act { setTableCursor(cursor) } },
        )
    }

    Row(
        modifier
            .clip(RoundedCornerShape(8.dp))
            .background(
                if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.10f) else Color.Transparent,
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
                // Accessibility actions are the *same* registry commands the dock shows, resolved against the
                // live selection. Activating a row selects it first, exactly as a tap does, so the action list
                // that follows describes this block.
                customActions = state.accessibilityActions.map { item ->
                    CustomAccessibilityAction(item.accessibilityLabel()) {
                        if (block.id !in state.selectedIds) presenter.act { setCaret(block.id, 0) }
                        presenter.invokeItem(item, CommandSurface.ACCESSIBILITY, onNotice)
                        true
                    }
                }
            }
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.Top,
    ) {
        IconButton(
            onClick = { presenter.act { openInsertPalette(block.id) } },
            modifier = Modifier.width(36.dp),
        ) {
            Text("+", style = MaterialTheme.typography.titleMedium)
        }
        Box(Modifier.weight(1f)) {
            FlowBlockContent(block, callbacks, Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun FlowTopBar(
    state: FlowHostUiState,
    title: String,
    onTitleChange: (String) -> Unit,
    onOpenSidebar: () -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onEnterSelection: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = onOpenSidebar) { Text("Notes") }
        Text(
            text = title.ifBlank { "Untitled" },
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 8.dp),
        )
        TextButton(onClick = onUndo, enabled = state.canUndo) { Text("Undo") }
        TextButton(onClick = onRedo, enabled = state.canRedo) { Text("Redo") }
        // The multi-select entry is a permanent, labelled control — not a hidden long-press. The hint chip
        // appears until the affordance has been used once (GUIDE_DISCOVERY, not a user setting).
        TextButton(onClick = onEnterSelection) {
            Text(if (state.showMultiSelectHint) "Select blocks" else "Select")
        }
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
        session.results.forEach { result ->
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
    val body = @Composable {
        FlowPaletteList(
            heading = heading,
            items = items,
            recentId = state.recentInsertId.takeIf { surfaceId == FlowSurfaces.INSERT_PALETTE },
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
    onPick: (FlowCommandItem) -> Unit,
) {
    Column(Modifier.padding(bottom = 24.dp)) {
        Text(
            heading,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
        )
        val ordered = items.sortedByDescending { it.id == recentId }
        ordered.forEach { item ->
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
