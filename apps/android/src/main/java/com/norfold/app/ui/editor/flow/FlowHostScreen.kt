package com.norfold.app.ui.editor.flow

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.content.Intent
import android.content.res.Configuration
import android.graphics.BitmapFactory
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Redo
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.AddBox
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FormatBold
import androidx.compose.material.icons.filled.FormatItalic
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.OpenWith
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.outlined.Menu
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
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlin.math.roundToInt
import kotlinx.coroutines.isActive
import com.norfold.app.domain.DocumentBlock
import com.norfold.app.domain.ChartBlock
import com.norfold.app.domain.ImageBlock
import com.norfold.app.domain.ShapeBlock
import com.norfold.app.domain.DropTarget
import com.norfold.app.editor.command.CommandSurface
import com.norfold.app.editor.flow.FlowCommandItem
import com.norfold.app.editor.flow.FlowDragGeometry
import com.norfold.app.editor.flow.FlowDropPlacement
import com.norfold.app.editor.flow.FlowHostUiState
import com.norfold.app.editor.flow.FlowIds
import com.norfold.app.editor.flow.FlowRowBounds
import com.norfold.app.editor.flow.FlowSurfaces
import com.norfold.app.editor.presentation.FormFactor
import com.norfold.app.editor.presentation.PresentationMode
import com.norfold.app.editor.surface.DismissTrigger
import com.norfold.app.editor.surface.SurfaceClass
import com.norfold.app.editor.style.EditorStyleCommandIds
import com.norfold.app.ui.editor.style.EditorStyleSurface
import com.norfold.app.ui.editor.style.collapsedBlockSpacing
import com.norfold.app.ui.editor.style.resolvedBlockMetrics
import com.norfold.app.ui.editor.style.EditorStyleSurfaceActions
import com.norfold.app.ui.editor.style.rememberFontImportLauncher
import com.norfold.app.ui.editor.style.rememberImportedFontRemover
import com.norfold.app.ui.fonts.OfflineFontLibrary
import com.norfold.app.domain.stylesheet
import com.norfold.app.domain.RelationshipDataBlock
import com.norfold.app.domain.InterchangeDataBlock
import com.norfold.app.domain.findById
import com.norfold.app.domain.rootAncestorId
import com.norfold.app.domain.StylesheetBlock
import com.norfold.app.editor.chart.ChartBlockAdapter
import com.norfold.app.ui.editor.chart.ChartStudioSheet
import com.norfold.app.ui.editor.image.ImageFocusSheet
import com.norfold.app.editor.image.ImageSourceMetadata
import com.norfold.app.ui.editor.shape.ShapeBuilderSheet
import com.norfold.app.ui.editor.editorFlags
import com.norfold.app.editor.flags.EditorFeatureFlag
import com.norfold.app.editor.relationship.CommentAuthorRef
import com.norfold.app.editor.relationship.CommentMention
import com.norfold.app.ui.editor.relationship.RelationshipReviewSurface
import com.norfold.app.editor.guide.GuideBlocker
import com.norfold.app.editor.guide.GuideContext
import com.norfold.app.editor.guide.GuideDomain
import com.norfold.app.editor.guide.GuideHost
import com.norfold.app.editor.guide.GuideSignal
import com.norfold.app.ui.editor.guide.GuideEntryButton
import com.norfold.app.ui.editor.guide.GuideHostLayer
import com.norfold.app.ui.editor.guide.rememberGuideUiController
import com.norfold.app.ui.editor.history.HistoryEntryButton
import com.norfold.app.ui.editor.history.HistoryHostLayer
import com.norfold.app.ui.editor.history.rememberHistoryUiController
import com.norfold.app.ui.editor.recovery.RecoveryEntryButton
import com.norfold.app.ui.editor.recovery.RecoveryHostLayer
import com.norfold.app.ui.editor.recovery.rememberRecoveryUiController

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
    onOpenTableInSpreadsheet: (String) -> Unit,
    onExit: () -> Unit,
    currentAuthor: CommentAuthorRef,
    mentionCandidates: List<CommentMention>,
    modifier: Modifier = Modifier,
) {
    val state = presenter.state
    val visibleBlocks = remember(state.document) {
        state.document.blocks.filterNot { it is StylesheetBlock || it is RelationshipDataBlock || it is InterchangeDataBlock }
    }
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = presenter.runtime.restoredScrollIndex())
    var notice by remember { mutableStateOf<String?>(null) }
    var chartEditingId by remember { mutableStateOf<String?>(null) }
    var imageEditingId by remember { mutableStateOf<String?>(null) }
    var shapeEditingId by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        val readable = runCatching {
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            bounds.outWidth > 0 && bounds.outHeight > 0
        }.getOrDefault(false)
        if (!readable) {
            notice = "That image could not be read. Choose another photo."
            return@rememberLauncherForActivityResult
        }
        val permissionGranted = runCatching {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }.isSuccess
        if (!permissionGranted) {
            notice = "Norfold could not keep access to that photo. Choose it again."
            return@rememberLauncherForActivityResult
        }
        val imageId = presenter.act {
            insertImage(
                uri.toString(),
                ImageSourceMetadata(
                    pixelWidth = bounds.outWidth,
                    pixelHeight = bounds.outHeight,
                    mimeType = context.contentResolver.getType(uri),
                ),
            )
        }
        if (imageId == null) {
            notice = "Couldn't add that photo. Your note was left unchanged."
        } else {
            imageEditingId = imageId
        }
    }

    // ── Adaptive presentation: the window drives the mode, never the other way round (§2.6) ──
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val imeBottom = WindowInsets.ime.getBottom(density)
    val compact = configuration.screenWidthDp < FLOW_COMPACT_WIDTH_DP
    var imeWasOpen by remember { mutableStateOf(false) }
    // A physical keyboard is a presentation input, not just an input method: `KEYBOARD_QWERTY` is reported
    // for an attached hardware keyboard and `KEYBOARD_NOKEYS` for a touch-only device, so the slash session
    // can open as a searchable list where arrow keys exist and as the thumb rail where they do not.
    val hardwareKeyboard = configuration.keyboard == Configuration.KEYBOARD_QWERTY
    LaunchedEffect(
        configuration.screenWidthDp,
        configuration.screenHeightDp,
        imeBottom > 0,
        hardwareKeyboard,
    ) {
        presenter.act {
            onFormFactorChanged(
                FormFactor(
                    widthDp = configuration.screenWidthDp,
                    heightDp = configuration.screenHeightDp,
                    keyboardOpen = imeBottom > 0,
                    hardwareKeyboard = hardwareKeyboard,
                ),
            )
        }
    }
    LaunchedEffect(imeBottom) {
        if (imeBottom > 0) {
            imeWasOpen = true
        } else if (imeWasOpen) {
            presenter.act { leaveTextEditing() }
            imeWasOpen = false
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
    val guide = rememberGuideUiController(presenter.runtime.guide)
    val history = rememberHistoryUiController(presenter.runtime.history)
    val recovery = rememberRecoveryUiController(presenter.runtime.recoveryBrowser) { presenter.act { } }
    val guideContext = GuideContext(
        host = GuideHost.FLOW,
        capabilities = presenter.runtime.scope.commandContext(CommandSurface.DOCK).offeredCapabilities,
        domains = buildSet {
            add(GuideDomain.FLOW)
            when {
                chartEditingId != null -> add(GuideDomain.CHART)
                imageEditingId != null -> add(GuideDomain.IMAGE)
                shapeEditingId != null -> add(GuideDomain.SHAPE)
                state.activeSurfaceId in setOf(FlowSurfaces.COLOR_STUDIO, FlowSurfaces.FONT_BROWSER, FlowSurfaces.NAMED_STYLES) -> add(GuideDomain.STYLES)
                state.activeSurfaceId in setOf(FlowSurfaces.RELATIONSHIP_MANAGER, FlowSurfaces.COMMENTS, FlowSurfaces.REVIEW) -> add(GuideDomain.RELATIONSHIPS)
            }
        },
        blockers = buildSet {
            if (drag.sourceId != null) add(GuideBlocker.DRAG)
            if (imageEditingId != null) add(GuideBlocker.CROP)
        },
    )

    // Running a slash command is one behaviour with three entry points — a rail tap, a list tap, and Enter —
    // and one of them cannot live in the runtime: picker-backed insertion needs an Android URI, so the host
    // has to consume the session and launch the picker itself. Hoisting it here is what keeps the rail and
    // the list from acquiring separately-drifting copies of that exception.
    val pickSlashCommand: (String) -> Unit = { id ->
        if (id == FlowIds.INSERT_IMAGE) {
            if (presenter.act { consumeSlashForImagePicker() }) imagePicker.launch(arrayOf("image/*"))
            else notice = "Couldn't prepare image insertion. Try again."
        } else {
            val result = presenter.act { acceptSlash(id) }
            (result as? com.norfold.app.editor.command.CommandResult.Failure)?.let { notice = it.reason }
        }
    }

    FlowVisualTheme {
    val selectedObject = state.primaryId?.let(state.document::findById)
    Box(
        modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .onPreviewKeyEvent { event ->
                if (state.slash != null && event.type == KeyEventType.KeyDown) {
                    val rail = state.slashRail
                    // Two things can hold the highlight: the rail's slots, or the resolver's ranked results.
                    // A list promoted from inside a group still shows the group's slots, so it walks with the
                    // rail rather than with the query — otherwise the arrow keys would move a highlight the
                    // user cannot see.
                    val onSlots = rail != null && (rail.isRail || rail.scoped)
                    when (event.key) {
                        Key.DirectionLeft, Key.DirectionRight -> {
                            // Horizontal arrows belong to the strip. In the list the rows are vertical, so
                            // leaving these unhandled lets the IME and text field have them back.
                            if (rail?.isRail == true) {
                                presenter.act { moveRailFocus(if (event.key == Key.DirectionRight) 1 else -1) }
                                return@onPreviewKeyEvent true
                            }
                        }
                        Key.DirectionDown -> {
                            // On the rail, down is the gesture that raises the names — the keyboard twin of
                            // the trailing chevron.
                            if (rail?.isRail == true) presenter.act { openSlashList() }
                            else if (onSlots) presenter.act { moveRailFocus(1) }
                            else presenter.act { moveSlashSelection(1) }
                            return@onPreviewKeyEvent true
                        }
                        Key.DirectionUp -> {
                            if (rail?.isRail == true) {
                                // Up is down's inverse: it puts the names away again, and does nothing at all
                                // when there is nothing to put away.
                                presenter.act { popRailLevel() }
                            } else if (onSlots) {
                                presenter.act { moveRailFocus(-1) }
                            } else {
                                presenter.act { moveSlashSelection(-1) }
                            }
                            return@onPreviewKeyEvent true
                        }
                        Key.Enter -> {
                            val focused = rail?.focused
                            if (onSlots && focused != null) {
                                when (focused) {
                                    is com.norfold.app.editor.flow.FlowRailSlot.Command ->
                                        pickSlashCommand(focused.id)
                                    else -> presenter.act { activateRailFocus() }
                                }
                            } else {
                                val selected = state.slash.selected
                                if (selected != null) pickSlashCommand(selected.command.id)
                            }
                            return@onPreviewKeyEvent true
                        }
                        Key.Backspace -> {
                            // Intercepted only when there is a level to pop. When there is not, the `/` is
                            // ordinary text and deleting it is what dismisses the session — so the key must
                            // reach the field rather than be swallowed into a dead press.
                            if (presenter.act { popRailLevel() }) return@onPreviewKeyEvent true
                        }
                        Key.Escape -> {
                            presenter.act { closeSlash(DismissTrigger.CANCEL) }
                            return@onPreviewKeyEvent true
                        }
                        else -> Unit
                    }
                }
                // Unbound keys fall through untouched, so ordinary typing and the IME are never intercepted.
                val shortcut = event.toFlowShortcut() ?: return@onPreviewKeyEvent false
                val handled = presenter.act { handleShortcut(shortcut) }
                // Ctrl+Z reaches History through the host rather than through this controller, so the
                // controller is told to look: a reversal that must be disclosed puts its sheet on screen
                // instead of quietly declining.
                history.syncFromRuntime()
                handled
            },
    ) {
        Column(Modifier.fillMaxSize()) {
            FlowTopBar(
                state = state,
                title = title,
                onTitleChange = onTitleChange,
                onOpenSidebar = onOpenSidebar,
                onExit = onExit,
                // With the History browser on, Undo goes through it: only that route can disclose a
                // multi-step or cross-target reversal before it happens (§13.4).
                onUndo = { if (history.runtime != null) presenter.act { history.undo() } else presenter.act { undo() } },
                onRedo = { if (history.runtime != null) presenter.act { history.redo() } else presenter.act { redo() } },
                onDoneEditing = { presenter.act { leaveTextEditing() } },
                onEnterSelection = { presenter.act { enterSelectionMode(state.primaryId) } },
                onOpenGuide = guide.runtime?.let { { guide.open() } },
                onOpenHistory = history.runtime?.let { { history.open() } },
                onOpenRecovery = recovery.runtime?.let { { recovery.open() } },
                compact = compact,
                editing = (state.interactionMode != com.norfold.app.editor.flow.FlowInteractionMode.READING || state.formFactor.keyboardOpen) &&
                    selectedObject !is ImageBlock && selectedObject !is ChartBlock,
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f))
            Box(Modifier.weight(1f)) {
                val flowSheet = state.document.stylesheet()
                val flowMetrics = remember(visibleBlocks, flowSheet) {
                    visibleBlocks.map { resolvedBlockMetrics(it, flowSheet) }
                }
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .onGloballyPositioned { drag.viewportTop = it.positionInWindow().y }
                        .onSizeChanged { drag.viewportHeight = it.height.toFloat() },
                    contentPadding = PaddingValues(
                        start = 22.dp,
                        end = 22.dp,
                        top = 22.dp,
                        // Reserve the real height of everything docked below, so the last block and the active
                        // caret can always be scrolled clear of it instead of sitting under it.
                        bottom = 24.dp + lowerSurfaceDp,
                    ),
                ) {
                    itemsIndexed(visibleBlocks, key = { _, block -> block.id }) { index, block ->
                        val metrics = flowMetrics[index]
                        val topSpacing = if (index == 0) {
                            metrics.beforeDp
                        } else {
                            collapsedBlockSpacing(flowMetrics[index - 1].afterDp, metrics.beforeDp)
                        }
                        val bottomSpacing = if (index == visibleBlocks.lastIndex) metrics.afterDp else 0f
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .padding(top = topSpacing.dp, bottom = bottomSpacing.dp)
                                // Writing-first measure: the *row* fills the list so the centering works, and
                                // the content inside it stops at a comfortable line length. Putting the
                                // width cap on a fillMaxWidth() row would have been defeated by the fill.
                                .onGloballyPositioned { coordinates ->
                                    val top = coordinates.positionInWindow().y - drag.viewportTop
                                    drag.bounds[block.id] = FlowRowBounds(
                                        blockId = block.id,
                                        documentIndex = index,
                                        top = top,
                                        bottom = top + coordinates.size.height,
                                    )
                                },
                            contentAlignment = Alignment.TopCenter,
                        ) {
                            // A row measured at a stale index (scrolled away, or the list reordered under it)
                            // must stop claiming a screen position once it leaves composition, or the geometry
                            // would keep resolving drops against where it *used* to be.
                            DisposableEffect(block.id) {
                                onDispose { drag.bounds.remove(block.id) }
                            }
                            FlowBlockRow(
                                block = block,
                                state = state,
                                presenter = presenter,
                                drag = drag,
                                onNotice = { notice = it },
                                onOpenTableInSpreadsheet = onOpenTableInSpreadsheet,
                                onOpenChart = { chartEditingId = it; guide.signal(GuideSignal.CHART_STUDIO_OPENED) },
                                onOpenImage = { imageEditingId = it; guide.signal(GuideSignal.IMAGE_FOCUS_OPENED) },
                                onOpenShape = { shapeEditingId = it; guide.signal(GuideSignal.SHAPE_BUILDER_OPENED) },
                                compact = compact,
                                // A focused child remains inside the same top-level row.  Comparing only its
                                // own id made nested callouts, quote text and container cells flip straight
                                // back to read-only immediately after focus moved into them.
                                editing = state.editingBlockId?.let(state.document::rootAncestorId) == block.id,
                                onActivateEditor = { id -> presenter.act { enterTextEditing(id) } },
                                onEditorFocusChanged = { blockId, focused ->
                                    if (focused) presenter.act { enterTextEditing(blockId) }
                                },
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

        // Edge auto-scroll (correction item 5): restarts exactly when a drag begins or ends, because
        // `drag.sourceId` is the key. While a drag is active it scrolls the list towards whichever edge the
        // finger is resting in, re-measuring the placement every frame so the marker tracks the moving content
        // instead of lagging behind it. Ending the drag — drop or cancel — clears `sourceId` first, which is
        // what stops the loop; nothing here needs to know which happened.
        val dragHaptics = LocalHapticFeedback.current
        LaunchedEffect(drag.sourceId) {
            if (drag.sourceId == null) return@LaunchedEffect
            while (isActive && drag.sourceId != null) {
                withFrameNanos { }
                val amount = FlowDragGeometry.edgeScroll(drag.pointerY, drag.viewportHeight)
                if (amount != 0f) listState.scrollBy(amount)
                val order = visibleBlocks.map { it.id }
                val crossed = drag.refresh(order)
                if (crossed && drag.placement?.isNoOp == false) {
                    dragHaptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
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
            state.relationshipReview.connectMode?.let {
                FlowNotice("Connect mode · choose an eligible block target") {
                    presenter.act { relationshipReview.exitConnectMode() }
                }
            }
            notice?.let { FlowNotice(it) { notice = null } }
            state.slash?.let { session ->
                val rail = state.slashRail
                // The rail and the list are one session wearing two presentations, so this is a branch on
                // how to draw it — never on which command system is in charge.
                if (rail != null && rail.isRail) {
                    FlowCommandRailBar(
                        rail = rail,
                        onCommand = pickSlashCommand,
                        onGroup = { group -> presenter.act { openRailGroup(group) } },
                        onBack = { presenter.act { popRailLevel() } },
                        onOpenList = { presenter.act { openSlashList() } },
                    )
                } else {
                    FlowSlashMenu(
                        session = session,
                        rail = rail,
                        onPick = pickSlashCommand,
                        onBack = { presenter.act { popRailLevel() } },
                        onDismiss = { presenter.act { closeSlash(DismissTrigger.CANCEL) } },
                    )
                }
            }
            if (state.interactionMode != com.norfold.app.editor.flow.FlowInteractionMode.READING || state.formFactor.keyboardOpen) {
                FlowContextDock(
                    state = state,
                    onCommand = { item -> presenter.invokeItem(item, CommandSurface.DOCK) { notice = it } },
                    onOpenInsert = { presenter.act { openInsertPalette(state.primaryId) } },
                    onOpenConvert = {
                        presenter.act { openSurface(FlowSurfaces.CONVERT_MENU, SurfaceClass.COMMAND_MENU) }
                    },
                    onStyleCommand = { id ->
                        when (val result = presenter.act { invoke(id, CommandSurface.DOCK) }) {
                            is com.norfold.app.editor.command.CommandResult.Failure -> notice = result.reason
                            else -> Unit
                        }
                    },
                    onCancelSelection = { presenter.act { cancelSelectionMode() } },
                    onFinishSelection = { presenter.act { finishSelectionMode() } },
                    onOpenMore = {
                        presenter.act { openSurface(FlowSurfaces.MORE_ACTIONS, SurfaceClass.COMMAND_MENU) }
                    },
                    onOpenImage = { imageEditingId = it; guide.signal(GuideSignal.IMAGE_FOCUS_OPENED) },
                    onOpenChart = { chartEditingId = it; guide.signal(GuideSignal.CHART_STUDIO_OPENED) },
                )
            }
        }

        FlowPaletteSurface(
            state = state,
            presenter = presenter,
            onNotice = { notice = it },
            onPickImage = { imagePicker.launch(arrayOf("image/*")) },
        )
        if (state.interchange.requestedAction == null && state.interchange.rawDraft == null) {
            GuideHostLayer(guide, guideContext)
        }
        HistoryHostLayer(history)
        RecoveryHostLayer(recovery)
    }

    if (state.activeSurfaceId in setOf(FlowSurfaces.RELATIONSHIP_MANAGER, FlowSurfaces.COMMENTS, FlowSurfaces.REVIEW)) {
        RelationshipReviewSurface(
            title = when (state.activeSurfaceId) {
                FlowSurfaces.COMMENTS -> "Comments"
                FlowSurfaces.REVIEW -> "Review"
                else -> "Relationships"
            },
            state = state.relationshipReview,
            onDismiss = { presenter.act {
                if (state.activeSurfaceId == FlowSurfaces.REVIEW) relationshipReview.closeReviewMode()
                dismiss(DismissTrigger.OUTSIDE_TAP)
            } },
            mentionCandidates = mentionCandidates,
            onAddComment = { body, mentions -> presenter.act { addComment(currentAuthor, body, mentions) } },
            onRemoveRelationship = { id -> presenter.act { relationshipReview.removeRelationship(id) } },
            onChangeRelationshipType = { id, kind -> presenter.act { relationshipReview.changeType(id, kind) } },
            onRenameRelationship = { id, label -> presenter.act { relationshipReview.rename(id, label) } },
            onReverseRelationship = { id -> presenter.act { relationshipReview.reverse(id) } },
            onBeginRebind = { id, stableId -> presenter.act { relationshipReview.beginRebind(id, stableId) } },
            onNavigateEndpoint = { endpoint -> presenter.act { navigateTo(endpoint) } },
            onNavigateThread = { id -> presenter.act { relationshipReview.focusThread(id)?.let { navigateTo(it.anchor) } } },
            onReply = { id, body, mentions -> presenter.act { relationshipReview.reply(id, currentAuthor, body, mentions) } },
            onResolve = { id -> presenter.act { relationshipReview.resolve(id, currentAuthor) } },
            onReopen = { id -> presenter.act { relationshipReview.reopen(id) } },
            onCreateSnapshot = { presenter.act { relationshipReview.createReviewSnapshot(author = currentAuthor) } },
            onRestoreSnapshot = { id -> presenter.act { relationshipReview.restoreReviewSnapshot(id) } },
            onExitConnect = { presenter.act { relationshipReview.exitConnectMode() } },
            onOpenGuide = guide.runtime?.let { { guide.open() } },
        )
    }

    chartEditingId?.let { blockId ->
        val block = state.document.findById(blockId) as? ChartBlock
        if (block == null) {
            chartEditingId = null
        } else {
            val loaded = remember(block.chartDocument, block.vegaLiteSpec) { ChartBlockAdapter.load(block) }
            ChartStudioSheet(
                initial = loaded.document,
                readOnly = !loaded.isEditable,
                calculationProvider = presenter.runtime.chartCalculationProvider(),
                onDocumentChanged = { document -> presenter.act { updateChart(blockId, document) } },
                onDismiss = { chartEditingId = null },
                onDone = { chartEditingId = null },
                onOpenGuide = guide.runtime?.let { { guide.open() } },
                onDirectManipulation = { guide.signal(GuideSignal.CHART_DIRECT_MANIPULATION) },
            )
        }
    }

    imageEditingId?.takeIf { editorFlags.isEnabled(EditorFeatureFlag.IMAGE_FOCUS) }?.let { blockId ->
        val block = state.document.findById(blockId) as? ImageBlock
        if (block == null) {
            imageEditingId = null
        } else {
            ImageFocusSheet(
                block = block,
                readOnly = false,
                onCommit = { edited -> presenter.act { updateImage(blockId, edited) } },
                onDismiss = { imageEditingId = null },
                onDone = { imageEditingId = null },
                onOpenGuide = guide.runtime?.let { { guide.open() } },
                onCommitted = { guide.signal(GuideSignal.IMAGE_FOCUS_COMMITTED) },
                onFocusHistoryOwner = { presenter.runtime.setFocusHistoryOwner(it) },
            )
        }
    }

    shapeEditingId?.takeIf { editorFlags.isEnabled(EditorFeatureFlag.SHAPE_BUILDER) }?.let { blockId ->
        val block = state.document.findById(blockId) as? ShapeBlock
        if (block == null) {
            shapeEditingId = null
        } else {
            ShapeBuilderSheet(
                block = block,
                readOnly = false,
                onCommit = { edited -> presenter.act { updateShape(blockId, edited) } },
                onDismiss = { shapeEditingId = null },
                onDone = { shapeEditingId = null },
                onOpenGuide = guide.runtime?.let { { guide.open() } },
                onCommitted = { guide.signal(GuideSignal.SHAPE_BUILDER_COMMITTED) },
                onFocusHistoryOwner = { presenter.runtime.setFocusHistoryOwner(it) },
            )
        }
    }

    // Keep the edited line above the keyboard and the dock. Driven by the runtime's caret request rather than
    // by focus, so a caret the *runtime* moved (split, merge, undo) scrolls into view exactly like a tap does.
    val caretRequest = state.caretRequest
    LaunchedEffect(caretRequest?.stamp, lowerSurfaceHeight, imeBottom) {
        val target = caretRequest ?: return@LaunchedEffect
        val index = visibleBlocks.indexOfFirst { it.id == target.blockId }
        if (index < 0) return@LaunchedEffect
        val visible = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }
        val limit = listState.layoutInfo.viewportEndOffset - lowerSurfaceHeight
        if (visible == null || visible.offset + visible.size > limit || visible.offset < 0) {
            listState.animateScrollToItem(index)
        }
    }
    }
}

/** Mutable, non-snapshot drag bookkeeping plus the snapshot state the marker and the lift read. */
@androidx.compose.runtime.Stable
private class FlowDragState {
    /**
     * Measured row bounds, keyed by block id. Written on every layout pass and **removed when a row leaves
     * composition**: a virtualized list stops measuring rows it has scrolled away from, so bounds left behind
     * would keep claiming a screen position the row no longer occupies and place drops against it.
     */
    val bounds = mutableMapOf<String, FlowRowBounds>()
    var viewportTop: Float = 0f
    var viewportHeight: Float = 0f

    var sourceId: String? by mutableStateOf(null)

    /**
     * The finger's position in viewport space, advanced only by raw touch deltas. Kept independent of [lift]
     * because auto-scroll moves the *content* underneath a finger that has not itself moved: if this were
     * re-derived from the dragged row's own measured position, every auto-scroll tick would double-count the
     * scroll as additional finger travel and the clone would run away from the touch point.
     */
    var pointerY: Float = 0f

    /**
     * The dragged row's visual offset from its own layout slot. Recomputed — not accumulated — from
     * [pointerY] minus the row's current measured center, so it self-corrects every time the list scrolls
     * the row's slot out from underneath the still-stationary finger.
     */
    var lift: Float by mutableStateOf(0f)
    var placement: FlowDropPlacement? by mutableStateOf(null)

    fun begin(blockId: String) {
        sourceId = blockId
        pointerY = bounds[blockId]?.center ?: 0f
        lift = 0f
        placement = null
    }

    /**
     * Recomputes [lift] and the placement from the rows currently measured. Returns true when the placement
     * crossed to a different boundary, which is the only moment worth a haptic.
     */
    fun refresh(order: List<String>): Boolean {
        val sourceId = sourceId ?: return false
        bounds[sourceId]?.let { lift = pointerY - it.center }
        val next = FlowDragGeometry.placementFor(
            rows = bounds.values.toList(),
            sourceDocumentIndex = order.indexOf(sourceId),
            pointerY = pointerY,
        )
        val crossed = next?.index != placement?.index
        placement = next
        return crossed
    }

    fun reset() {
        sourceId = null
        pointerY = 0f
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
    Row(
        modifier
            .offset { IntOffset(0, placement.markerY.roundToInt() - 1) }
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .semantics {
                stateDescription = if (placement.isNoOp) "No position change" else "Move block here"
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            modifier = Modifier.weight(1f).height(42.dp),
            shape = RoundedCornerShape(14.dp),
            color = color.copy(alpha = if (placement.isNoOp) .06f else .10f),
            border = androidx.compose.foundation.BorderStroke(2.dp, color),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    if (placement.isNoOp) "Current position" else "Drop block here",
                    style = MaterialTheme.typography.labelLarge,
                    color = color,
                )
            }
        }
    }
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
    onOpenTableInSpreadsheet: (String) -> Unit,
    onOpenChart: (String) -> Unit,
    onOpenImage: (String) -> Unit,
    onOpenShape: (String) -> Unit,
    compact: Boolean,
    editing: Boolean,
    onActivateEditor: (String) -> Unit,
    onEditorFocusChanged: (String, Boolean) -> Unit,
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
                    // The runtime builds the whole slash input, because both suppression facts — which block
                    // kind is actually addressed, and whether the caret sits in a verbatim inline span — are
                    // held by the document. This row may be rendering a nested child, so the block it was
                    // composed for is not necessarily the one being edited (correction item 6).
                    onTextInput(
                        edit.blockId,
                        slashInputFor(
                            blockId = edit.blockId,
                            text = edit.after,
                            caret = edit.caret,
                            itemId = edit.itemId,
                        ),
                    )
                }
            },
            onCaret = { blockId, itemId, caret -> presenter.act { setCaret(blockId, caret, itemId) } },
            onTextSelection = { blockId, itemId, start, end ->
                presenter.act { setTextSelection(blockId, start, end, itemId) }
            },
            onToggleTodo = { blockId, itemId -> presenter.act { toggleTodo(blockId, itemId) } },
            onTableCell = { cursor -> presenter.act { setTableCursor(cursor) } },
            onTableCellDraft = { _, value -> presenter.act { updateTableDraft(value) } },
            onTableCellCommit = { cursor, value ->
                val result = presenter.act { editTableCell(cursor, value) }
                result.error?.let { onNotice(it.detail.ifBlank { it.displayLabel }) }
                result.warning?.let(onNotice)
            },
            onOpenTableInSpreadsheet = onOpenTableInSpreadsheet,
            onNewLine = { presenter.act { newLine() } },
            onBackspaceAtStart = { presenter.act { backspaceAtStart() } },
            onToggleCollapsed = { blockId -> presenter.act { toggleCollapsed(blockId) } },
            onOpenChart = onOpenChart,
            onOpenImage = onOpenImage,
            onOpenShape = onOpenShape,
            onUpdateMermaid = { before, after -> presenter.act { updateMermaid(before, after) } },
            onEditorFocusChanged = onEditorFocusChanged,
        )
    }
    val isObject = block is ImageBlock || block is ChartBlock

    // Structural selection means the *row* owns every gesture inside it (correction item 6). The defect this
    // replaces let a child control — the insert handle, a table cell, a callout toggle — take a tap that was
    // meant to select or toggle the block, editing or navigating instead of selecting. Disabling the handle
    // here and threading `context.selectionMode` into every interactive child below is what makes "tap a block
    // in selection mode" mean the same thing everywhere in the row, not just in the text field.
    val rowSelectable = state.selectionMode
    val reorderModifier = if (rowSelectable && selected && state.relationshipReview.connectMode == null) {
        Modifier.pointerInput(
            block.id,
            state.document.blocks.count {
                it !is StylesheetBlock && it !is RelationshipDataBlock && it !is InterchangeDataBlock
            },
        ) {
            detectDragGesturesAfterLongPress(
                onDragStart = {
                    drag.begin(block.id)
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    presenter.act { beginDrag(block.id) }
                },
                onDrag = { change, amount ->
                    change.consume()
                    drag.pointerY += amount.y
                    val order = state.document.blocks
                        .filterNot { it is StylesheetBlock || it is RelationshipDataBlock || it is InterchangeDataBlock }
                        .map { it.id }
                    val crossed = drag.refresh(order)
                    if (crossed && drag.placement?.isNoOp == false) {
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    }
                },
                onDragEnd = {
                    val placement = drag.placement
                    if (placement == null || placement.isNoOp) {
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
        }
    } else Modifier
    Row(
        modifier
            .then(reorderModifier)
            .graphicsLayer {
                // The lifted clone rides above the list and stays translucent, so the destination marker under
                // it remains visible instead of being covered by the thing being moved (correction item 4).
                translationY = if (dragging) drag.lift else 0f
                alpha = if (dragging) 0.85f else 1f
                shadowElevation = if (dragging) 12f else 0f
                rotationZ = if (dragging) -1.25f else 0f
                scaleX = if (dragging) 1.015f else 1f
                scaleY = if (dragging) 1.015f else 1f
            }
            .zIndex(if (dragging) 2f else 0f)
            .clip(RoundedCornerShape(16.dp))
            .background(
                when {
                    // The source keeps a visible placeholder tint so its origin stays legible during the drag.
                    dragging -> MaterialTheme.colorScheme.surfaceVariant
                    // `selectedIds` also contains the ordinary caret owner. Tinting that whole row made
                    // transparent controls and captions look like one overlapping surface after a tap.
                    // Structural selection is the only state that needs a full-row selection treatment.
                    rowSelectable && selected -> MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
                    else -> Color.Transparent
                },
            )
            .combinedClickable(
                onClick = {
                    if (state.selectionMode) presenter.act { toggleSelection(block.id) }
                    else {
                        if (isObject && editing) {
                            when (block) {
                                is ImageBlock -> onOpenImage(block.id)
                                is ChartBlock -> onOpenChart(block.id)
                                else -> Unit
                            }
                        } else {
                            onActivateEditor(block.id)
                            presenter.act { setCaret(block.id, 0) }
                        }
                    }
                },
                // Long-press is the second, gesture-native way into multi-select; the top bar's Select
                // button is the first. Both call the same runtime entry point.
                onLongClick = {
                    if (state.relationshipReview.connectMode != null) {
                        onActivateEditor(block.id)
                        presenter.act { setCaret(block.id, 0) }
                    } else presenter.act { enterSelectionMode(block.id) }
                },
            )
            .semantics {
                if (state.relationshipReview.connectMode != null) {
                    stateDescription = "Eligible relationship target"
                }
                // Actions are resolved against *this* block, so the availability and reason a screen reader
                // announces describe the row it is on rather than whatever happened to be selected
                // (correction item 14). Execution re-selects the row and runs the same registry command.
                customActions = presenter.runtime.accessibilityActionsForNode(block.id).map { item ->
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
        Box(Modifier.weight(1f)) {
            FlowBlockContent(
                block = block,
                callbacks = callbacks,
                modifier = Modifier.fillMaxWidth(),
                context = FlowBlockContext(
                    caretRequest = state.caretRequest,
                    selectionMode = state.selectionMode,
                    tableCursor = state.tableCursor,
                    tableDraft = state.tableDraft,
                    stylesheet = state.document.stylesheet(),
                    previewRole = state.editorStyle.colorStudio.target?.role,
                    previewPaint = state.editorStyle.previewPaint,
                    previewTargetIds = state.editorStyle.colorStudio.target?.targetIds.orEmpty().toSet(),
                    chartCalculationProvider = presenter.runtime.chartCalculationProvider(),
                    chartDataRevision = state.revision,
                    compact = compact,
                    editing = editing,
                    objectSelected = editing && (block is ImageBlock || block is ChartBlock),
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
    onExit: () -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onDoneEditing: () -> Unit,
    onEnterSelection: () -> Unit,
    onOpenGuide: (() -> Unit)?,
    onOpenHistory: (() -> Unit)?,
    onOpenRecovery: (() -> Unit)?,
    compact: Boolean,
    editing: Boolean,
) {
    var overflow by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .heightIn(min = 64.dp)
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.97f))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (compact) {
            if (editing) {
                TextButton(onClick = onDoneEditing) {
                    Text("Done", style = MaterialTheme.typography.labelLarge)
                }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onUndo, enabled = state.canUndo) {
                    Icon(Icons.AutoMirrored.Outlined.Undo, contentDescription = "Undo")
                }
                IconButton(onClick = onRedo, enabled = state.canRedo) {
                    Icon(Icons.AutoMirrored.Outlined.Redo, contentDescription = "Redo")
                }
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                ) {
                    Row(
                        Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Text", style = MaterialTheme.typography.labelLarge)
                        Icon(Icons.Filled.ArrowDropDown, null, Modifier.width(18.dp))
                    }
                }
            } else {
                IconButton(onClick = onExit) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back to notes")
                }
                Surface(
                    modifier = Modifier.padding(start = 4.dp),
                    color = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    shape = androidx.compose.foundation.shape.CircleShape,
                ) {
                    Text(
                        "N",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(horizontal = 13.dp, vertical = 9.dp),
                    )
                }
                FlowTitleField(
                    title = title,
                    onTitleChange = onTitleChange,
                    editable = false,
                    modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
                )
            }
            Box {
                IconButton(onClick = { overflow = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = "More actions")
                }
                DropdownMenu(expanded = overflow, onDismissRequest = { overflow = false }) {
                    onOpenGuide?.let { open ->
                        DropdownMenuItem(
                            text = { Text("Guide") },
                            onClick = { overflow = false; open() },
                        )
                    }
                    onOpenHistory?.let { open ->
                        DropdownMenuItem(
                            text = { Text("History") },
                            onClick = { overflow = false; open() },
                        )
                    }
                    onOpenRecovery?.let { open ->
                        DropdownMenuItem(
                            text = { Text("Recovery") },
                            onClick = { overflow = false; open() },
                        )
                    }
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
            IconButton(onClick = onExit) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back to notes")
            }
            IconButton(onClick = onOpenSidebar) {
                Icon(Icons.Outlined.Menu, contentDescription = "Open note navigation")
            }
            FlowTitleField(
                title = title,
                onTitleChange = onTitleChange,
                editable = editing,
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
            )
            if (editing) {
                TextButton(onClick = onUndo, enabled = state.canUndo) { Text("Undo") }
                TextButton(onClick = onRedo, enabled = state.canRedo) { Text("Redo") }
            }
            onOpenGuide?.let { GuideEntryButton("Flow editor", it) }
            onOpenHistory?.let { HistoryEntryButton(it) }
            onOpenRecovery?.let { RecoveryEntryButton(it) }
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
private fun FlowTitleField(
    title: String,
    onTitleChange: (String) -> Unit,
    editable: Boolean,
    modifier: Modifier = Modifier,
) {
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
        if (!editable) {
            Text(
                value.ifBlank { "Untitled" },
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                modifier = Modifier.fillMaxWidth(),
            )
        } else BasicTextField(
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

/**
 * A bounded, state-specific context dock. The visible row never relies on horizontal scrolling; secondary
 * actions remain reachable through More and execute through the same command registry.
 */
@Composable
private fun FlowContextDock(
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
    val showLabels = state.formFactor.widthDp >= FLOW_COMPACT_WIDTH_DP
    val inline = state.dock.filter { it.id.startsWith(com.norfold.app.editor.flow.FlowIds.TEXT_PREFIX) }
    val structural = state.dock.filterNot { it.id.startsWith(com.norfold.app.editor.flow.FlowIds.TEXT_PREFIX) }
    val primaryActions = when (state.interactionMode) {
        com.norfold.app.editor.flow.FlowInteractionMode.TEXT_EDITING,
        com.norfold.app.editor.flow.FlowInteractionMode.TEXT_SELECTION -> inline.take(4)
        com.norfold.app.editor.flow.FlowInteractionMode.BLOCK_SELECTION,
        com.norfold.app.editor.flow.FlowInteractionMode.MULTI_SELECTION,
        com.norfold.app.editor.flow.FlowInteractionMode.DRAGGING -> (structural + state.tableActions).take(2)
        com.norfold.app.editor.flow.FlowInteractionMode.READING -> emptyList()
    }
    val selectedObject = state.primaryId?.let(state.document::findById)
    val allCommands = state.dock + state.moreActions
    fun command(label: String): FlowCommandItem? = allCommands.firstOrNull { it.label.contains(label, ignoreCase = true) }
    Surface(
        tonalElevation = 2.dp,
        shadowElevation = 8.dp,
        shape = RoundedCornerShape(22.dp),
        modifier = Modifier
            .padding(horizontal = 14.dp, vertical = 8.dp)
            .widthIn(max = FLOW_CONTENT_MAX_WIDTH)
            .fillMaxWidth(),
    ) {
        Row(
            Modifier
                .padding(horizontal = 8.dp, vertical = 7.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (selectedObject is ImageBlock && !state.selectionMode) {
                FlowDockButton("Edit image", Icons.Filled.Edit, { onOpenImage(selectedObject.id) }, Modifier.weight(1f), showLabel = showLabels)
                command("Move")?.let { item ->
                    FlowDockButton("Move", Icons.Filled.OpenWith, { onCommand(item) }, Modifier.weight(1f), item.enabled, showLabels)
                }
                command("Duplicate")?.let { item ->
                    FlowDockButton("Duplicate", Icons.Filled.ContentCopy, { onCommand(item) }, Modifier.weight(1f), item.enabled, showLabels)
                }
                command("Delete")?.let { item ->
                    FlowDockButton("Delete", Icons.Filled.Delete, { onCommand(item) }, Modifier.weight(1f), item.enabled, showLabels)
                }
            } else if (selectedObject is ChartBlock && !state.selectionMode) {
                FlowDockButton("Edit chart", Icons.Filled.BarChart, { onOpenChart(selectedObject.id) }, Modifier.weight(1f), showLabel = showLabels)
                command("Move")?.let { item ->
                    FlowDockButton("Move", Icons.Filled.OpenWith, { onCommand(item) }, Modifier.weight(1f), item.enabled, showLabels)
                }
                command("Duplicate")?.let { item ->
                    FlowDockButton("Duplicate", Icons.Filled.ContentCopy, { onCommand(item) }, Modifier.weight(1f), item.enabled, showLabels)
                }
                command("Delete")?.let { item ->
                    FlowDockButton("Delete", Icons.Filled.Delete, { onCommand(item) }, Modifier.weight(1f), item.enabled, showLabels)
                }
            } else if (state.selectionMode) {
                FlowDockButton("Cancel", Icons.Filled.Close, onCancelSelection, Modifier.weight(1f), showLabel = showLabels)
                Text(
                    if (state.selectedCount == 1) "1 selected" else "${state.selectedCount} selected",
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.weight(1f),
                )
            } else {
                FlowDockButton("Insert", Icons.Filled.AddBox, onOpenInsert, Modifier.weight(1f), showLabel = showLabels)
                primaryActions.forEach { item -> FlowCommandChip(item, onCommand, Modifier.weight(1f), showLabel = showLabels) }
                if (state.interactionMode == com.norfold.app.editor.flow.FlowInteractionMode.TEXT_EDITING &&
                    state.editorStyle.textTargetCount > 0 && primaryActions.size < 3
                ) {
                    FlowDockButton("Font", Icons.Filled.TextFields, { onStyleCommand(EditorStyleCommandIds.FONT_BROWSER) }, Modifier.weight(1f), showLabel = showLabels)
                }
            }
            FlowDockButton("More", Icons.Filled.MoreHoriz, onOpenMore, Modifier.weight(1f), showLabel = showLabels)
            if (state.selectionMode) {
                FlowDockButton("Done", Icons.Filled.Check, onFinishSelection, Modifier.weight(1f), showLabel = showLabels)
            }
        }
    }
}

@Composable
private fun FlowCommandChip(
    item: FlowCommandItem,
    onCommand: (FlowCommandItem) -> Unit,
    modifier: Modifier = Modifier,
    showLabel: Boolean = true,
) {
    val icon = when {
        item.label.contains("bold", true) -> Icons.Filled.FormatBold
        item.label.contains("italic", true) -> Icons.Filled.FormatItalic
        item.label.contains("link", true) -> Icons.Filled.Link
        item.label.contains("check", true) -> Icons.Filled.Checklist
        else -> Icons.Filled.TextFields
    }
    FlowDockButton(item.label, icon, { onCommand(item) }, modifier, item.enabled, showLabel, selected = item.active || item.mixed)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FlowDockButton(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    showLabel: Boolean = true,
    selected: Boolean = false,
) {
    val color = when {
        !enabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
        label.equals("Delete", ignoreCase = true) -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurface
    }
    TooltipBox(
        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = { PlainTooltip { Text(label) } },
        state = rememberTooltipState(),
    ) {
        Column(
            modifier
                .heightIn(min = 54.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
                .clickable(enabled = enabled, onClick = onClick)
                .padding(horizontal = 2.dp, vertical = if (showLabel) 4.dp else 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(icon, contentDescription = label, tint = color, modifier = Modifier.height(if (showLabel) 22.dp else 25.dp))
            val compactLabel = when {
                label.contains("strike", true) -> "Strike"
                else -> label
            }
            if (showLabel) Text(compactLabel, style = MaterialTheme.typography.labelSmall, color = color, maxLines = 1)
        }
    }
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
    rail: com.norfold.app.editor.flow.FlowRailState?,
    onPick: (String) -> Unit,
    onBack: () -> Unit,
    onDismiss: () -> Unit,
) {
    // Promoted from inside a group, the list keeps the group's scope. Widening back to everything would
    // silently undo the narrowing the user just performed, and the query path is the one that should widen.
    val scoped = rail?.scoped == true
    val rows: List<SlashRow> = if (scoped) {
        rail.listItems.map { SlashRow(it.id, it.label, it.enabled, it.disabledReason) }
    } else {
        session.results.map {
            SlashRow(it.command.id, it.command.label, it.status.isInvokable, it.status.reason)
        }
    }
    // Scoped rows are the rail's slots minus the leading Back, so the rail's focus is the row index plus one.
    val highlighted = if (scoped) rail.focusedIndex - 1 else session.selectedIndex

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
                if (scoped) rail.group?.label.orEmpty() else "/${session.query.query}",
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.weight(1f),
            )
            // Inside a group the only useful exit is one level up; at the top level there is no level to go
            // up to, so the button says what it does instead of pretending to be a hierarchy.
            if (scoped) TextButton(onClick = onBack) { Text("Back") }
            else TextButton(onClick = onDismiss) { Text("Close") }
        }
        HorizontalDivider()
        LazyColumn(Modifier.heightIn(max = FLOW_COMPACT_LIST_MAX_HEIGHT)) {
            itemsIndexed(rows, key = { _, row -> row.id }) { index, row ->
                Column(
                    Modifier
                        .fillMaxWidth()
                        .semantics { selected = index == highlighted }
                        .background(if (index == highlighted) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent, RoundedCornerShape(10.dp))
                        .clickable(enabled = row.enabled, onClick = { onPick(row.id) })
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    Text(
                        row.label,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (row.enabled) MaterialTheme.colorScheme.onSurface
                        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                    )
                    if (!row.enabled) {
                        Text(
                            row.reason ?: "Not available here",
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
 * One row of the slash list, whichever source it came from. The ranked results and a group's commands carry
 * the same four facts in different shapes; flattening them here keeps one row renderer instead of two that
 * drift.
 */
private data class SlashRow(
    val id: String,
    val label: String,
    val enabled: Boolean,
    val reason: String?,
)

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
    onPickImage: () -> Unit,
) {
    val surfaceId = state.activeSurfaceId ?: return
    if (surfaceId == FlowSurfaces.COLOR_STUDIO || surfaceId == FlowSurfaces.FONT_BROWSER ||
        surfaceId == FlowSurfaces.NAMED_STYLES
    ) {
        FlowEditorStyleSurface(surfaceId, state, presenter, onNotice)
        return
    }
    val items = when (surfaceId) {
        FlowSurfaces.INSERT_PALETTE -> state.insertPalette
        FlowSurfaces.CONVERT_MENU -> state.convertPalette
        FlowSurfaces.MORE_ACTIONS -> state.moreActions
        else -> return
    }
    val heading = when (surfaceId) {
        FlowSurfaces.INSERT_PALETTE -> "Insert"
        FlowSurfaces.CONVERT_MENU -> "Turn into"
        else -> "More actions"
    }
    val dismiss: () -> Unit = { presenter.act { closeSurface(surfaceId, DismissTrigger.OUTSIDE_TAP) } }
    val compact = state.presentationMode == PresentationMode.PHONE_SHEET ||
        state.presentationMode == PresentationMode.PHONE_POPOVER
    val body = @Composable {
        FlowPaletteList(
            heading = heading,
            items = items,
            recentId = state.recentInsertId.takeIf { surfaceId == FlowSurfaces.INSERT_PALETTE },
            compact = compact,
            onPick = { item ->
                if (item.id == FlowIds.INSERT_IMAGE) {
                    presenter.act { closeSurface(FlowSurfaces.INSERT_PALETTE, DismissTrigger.COMMAND_EXECUTED) }
                    onPickImage()
                } else {
                    presenter.invokeItem(item, CommandSurface.DOCK, onNotice)
                }
            },
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FlowEditorStyleSurface(
    surfaceId: String,
    state: FlowHostUiState,
    presenter: FlowHostPresenter,
    onNotice: (String) -> Unit,
) {
    val styleRuntime = presenter.runtime.editorStyle
    val rows = styleRuntime.fontBrowser.results(state.editorStyle.documentFontIds)
        .map(styleRuntime.fontBrowser::rowState)
    val sections = styleRuntime.fontBrowser.sections(state.editorStyle.documentFontIds)
    val launchImport = rememberFontImportLauncher { result ->
        presenter.act { editorStyle.updateFontRegistry(OfflineFontLibrary.registry) }
        onNotice(
            when (result) {
                is com.norfold.app.data.FontImportResult.Imported -> "Imported ${result.entry.family}."
                is com.norfold.app.data.FontImportResult.AlreadyImported -> "${result.entry.family} is already imported."
                is com.norfold.app.data.FontImportResult.Rejected -> result.reason
            },
        )
    }
    val removeImport = rememberImportedFontRemover { identity, removed ->
        presenter.act { editorStyle.updateFontRegistry(OfflineFontLibrary.registry) }
        onNotice(if (removed) "Removed ${identity.displayName}. Existing text will use a labeled fallback." else "That font could not be removed.")
    }
    val actions = EditorStyleSurfaceActions(
        dismiss = { commit ->
            presenter.act {
                if (commit) editorStyle.colorInteractions.done()
                closeSurface(surfaceId, DismissTrigger.CANCEL)
            }
        },
        setColorView = { presenter.act { editorStyle.colorInteractions.cancel(); editorStyle.colorStudio.setView(it) } },
        draftColor = { field, value -> presenter.act { editorStyle.colorStudio.draft(field, value) } },
        commitColorDraft = { presenter.act { editorStyle.colorStudio.commitDraft(it) } },
        discardColorDraft = { presenter.act { editorStyle.colorStudio.discardDraft(it) } },
        beginColorAdjustment = { presenter.act { editorStyle.colorInteractions.beginContinuousAdjustment() } },
        previewColor = { presenter.act { editorStyle.colorStudio.previewColor(it) } },
        previewAlpha = { presenter.act { editorStyle.colorStudio.previewAlpha(it) } },
        finishColorAdjustment = { presenter.act { editorStyle.colorInteractions.finishContinuousAdjustment(it) } },
        cancelColorAdjustment = { presenter.act { editorStyle.colorInteractions.cancelContinuousAdjustment() } },
        chooseColor = { color -> presenter.act { editorStyle.colorInteractions.chooseExplicitColor(color) } },
        toggleGradient = { enabled -> presenter.act { editorStyle.colorInteractions.oneShot("Toggle gradient") { setGradientEnabled(enabled) } } },
        addGradientStop = { presenter.act { editorStyle.colorInteractions.oneShot("Add gradient stop") { addStopAt(0.5f) } } },
        selectGradientStop = { presenter.act { editorStyle.colorStudio.selectStop(it) } },
        removeGradientStop = { presenter.act { editorStyle.colorInteractions.oneShot("Remove gradient stop") { removeActiveStop() } } },
        setGradientKind = { kind -> presenter.act { editorStyle.colorInteractions.oneShot("Change gradient kind") { setGradientKind(kind) } } },
        moveGradientStop = { presenter.act { editorStyle.colorStudio.moveActiveStop(it) } },
        setGradientStopOpacity = { presenter.act { editorStyle.colorStudio.previewAlpha(it) } },
        setGradientAngle = { presenter.act { editorStyle.colorStudio.setGradientAngle(it) } },
        reverseGradient = { presenter.act { editorStyle.colorInteractions.oneShot("Reverse gradient") { reverseGradient() } } },
        reorderGradientStop = { delta -> presenter.act { editorStyle.colorInteractions.oneShot("Reorder gradient stop") { reorderActiveStop(delta) } } },
        setFontQuery = { presenter.act { editorStyle.fontBrowser.setQuery(it) } },
        setFontTab = { presenter.act { editorStyle.fontBrowser.setTab(it) } },
        setFontCategory = { presenter.act { editorStyle.fontBrowser.setCategory(it) } },
        setFontRole = { presenter.act { editorStyle.fontBrowser.setRole(it) } },
        setFontScript = { presenter.act { editorStyle.fontBrowser.setScript(it) } },
        setVariableOnly = { presenter.act { editorStyle.fontBrowser.setVariableOnly(it) } },
        selectFont = { identity -> presenter.act { editorStyle.applyFont(identity) } },
        toggleFavorite = { presenter.act { editorStyle.fontBrowser.toggleFavorite(it) } },
        importFont = launchImport,
        removeImportedFont = removeImport,
        applyStyle = { presenter.act { editorStyle.applyNamedStyle(it) } },
        setStyleNameDraft = { presenter.act { editorStyle.setStyleNameDraft(it) } },
        createStyle = { presenter.act { editorStyle.createStyleFromDraft() } },
        updateStyle = { presenter.act { editorStyle.updateStyleFromSelection(it) } },
        detachStyle = { presenter.act { editorStyle.detachStyle() } },
        clearFormatting = { presenter.act { editorStyle.clearFormatting() } },
        setTokenNameDraft = { presenter.act { editorStyle.setTokenNameDraft(it) } },
        createColorToken = { presenter.act { editorStyle.createColorTokenFromDraft() } },
        updateColorToken = { presenter.act { editorStyle.updateColorToken(it) } },
        applyColorToken = { presenter.act { editorStyle.applyColorToken(it) } },
    )
    val dismiss = { presenter.act { closeSurface(surfaceId, DismissTrigger.OUTSIDE_TAP) } }
    when (state.presentationMode) {
        PresentationMode.PHONE_SHEET, PresentationMode.PHONE_POPOVER -> {
            val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)
            ModalBottomSheet(onDismissRequest = dismiss, sheetState = sheetState) {
                EditorStyleSurface(surfaceId, state.editorStyle, rows, sections, actions)
            }
        }
        PresentationMode.TABLET_PANE, PresentationMode.EXPANDED_THREE_PANEL -> Box(Modifier.fillMaxSize()) {
            ElevatedCard(
                Modifier.align(Alignment.CenterEnd).width(FLOW_PANE_WIDTH).padding(16.dp),
            ) { EditorStyleSurface(surfaceId, state.editorStyle, rows, sections, actions) }
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

/** Comfortable measure for continuous prose; wider windows gain margin, not line length. */
private val FLOW_CONTENT_MAX_WIDTH = 760.dp
private val FLOW_PANE_WIDTH = 360.dp

/** Below this the header sheds its secondary control into an overflow menu rather than squeezing. */
/** Match the shared phone/tablet breakpoint so ordinary 411dp phones never receive tablet chrome. */
private const val FLOW_COMPACT_WIDTH_DP = FormFactor.TABLET_MIN_WIDTH_DP

/** Roughly five rows: enough to choose from, little enough that the document stays visible behind. */
private val FLOW_COMPACT_LIST_MAX_HEIGHT = 260.dp
