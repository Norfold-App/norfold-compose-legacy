package com.norfold.app.ui.editor.document

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Redo
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerKeyboardModifiers
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isMetaPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.norfold.app.domain.DocumentPageBlock
import com.norfold.app.domain.ChartBlock
import com.norfold.app.domain.ImageBlock
import com.norfold.app.domain.ResizeCorner
import com.norfold.app.domain.SceneGeometry
import com.norfold.app.domain.ShapeBlock
import com.norfold.app.domain.SceneLock
import com.norfold.app.domain.SceneObject
import com.norfold.app.editor.command.CommandSurface
import com.norfold.app.editor.document.ActiveManipulation
import com.norfold.app.editor.document.DocumentIds
import com.norfold.app.editor.lab.EditorLabFlag
import com.norfold.app.ui.editor.lab.EditorLab
import com.norfold.app.editor.document.DocumentBackResult
import com.norfold.app.editor.document.DocumentCommandItem
import com.norfold.app.editor.document.DocumentHostUiState
import com.norfold.app.editor.document.DocumentInspector
import com.norfold.app.editor.document.DocumentPageValueItem
import com.norfold.app.editor.document.DocumentSurfaces
import com.norfold.app.editor.document.GeometryInspector
import com.norfold.app.editor.document.LayoutRepresentation
import com.norfold.app.editor.document.PageScope
import com.norfold.app.editor.document.PageVirtualizer
import com.norfold.app.editor.document.SnapGuideEngine
import com.norfold.app.editor.document.WorkingIntent
import com.norfold.app.editor.presentation.FormFactor
import com.norfold.app.editor.surface.DismissTrigger
import com.norfold.app.editor.surface.SurfaceClass
import com.norfold.app.editor.style.EditorStyleCommandIds
import com.norfold.app.ui.editor.ObjectInspectorContent
import com.norfold.app.ui.editor.style.EditorStyleSurface
import com.norfold.app.ui.editor.style.resolvedBlockMetrics
import com.norfold.app.ui.editor.style.EditorStyleSurfaceActions
import com.norfold.app.ui.editor.style.rememberFontImportLauncher
import com.norfold.app.ui.editor.style.rememberImportedFontRemover
import com.norfold.app.ui.fonts.OfflineFontLibrary
import com.norfold.app.domain.stylesheet
import com.norfold.app.domain.findById
import androidx.compose.foundation.shape.RoundedCornerShape
import com.norfold.app.domain.style.resolvedPageBackground
import com.norfold.app.domain.style.ChromeGeometry
import com.norfold.app.domain.style.ColorPaint
import com.norfold.app.ui.EditorChromeColors
import com.norfold.app.ui.LocalChromeContainer
import com.norfold.app.ui.editor.style.backgroundPaint
import com.norfold.app.editor.chart.ChartBlockAdapter
import com.norfold.app.ui.editor.chart.ChartStudioSheet
import com.norfold.app.ui.editor.image.ImageFocusSheet
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
import kotlinx.coroutines.launch
import kotlin.math.atan2
import kotlin.math.roundToInt

/** Visual size of a manipulation handle, and the invisible target around it that a finger can actually hit. */
private val HANDLE_VISUAL = 14.dp
private val HANDLE_TARGET = 44.dp

/** How far above the object's top edge the rotation handle sits, in page points. */
private const val ROTATION_HANDLE_OFFSET_PT = 28f

// ── Chrome weights ──────────────────────────────────────────────────────────────────────────────────
//
// Every one of these is dp and is converted with `.toPx()` at the draw call. They were literal floats
// passed straight to `Stroke(width = ...)`, which a `DrawScope` reads as **pixels** — so the 3 "dp" primary
// outline was 3 px, a third of its intended weight on a 3× phone and half of it on a 2× tablet. The file's
// own units comment below warns about exactly this confusion; the chrome was the code ignoring it.

/** The primary object's edge, and a selected page's — the heaviest chrome, and the only 3 dp in the file. */
private val PRIMARY_EDGE = 3.dp

/** A secondary selection's edge, and the active page's. Distinguished from resting by weight and colour. */
private val SELECTED_EDGE = 2.dp

/** An unselected object's edge: enough to bound the object, not enough to compete with its content. */
private val RESTING_EDGE = 1.dp

/** Print production marks — one device-independent line, whatever the density. */
private val HAIRLINE = 1.dp

/** A live snap guide is momentary and must be seen instantly, so it is heavier than a static hint. */
private val SNAP_GUIDE_WIDTH = 1.5.dp

/** The lock badge's radius. Its inset from the corner is derived from this so the two cannot drift. */
private val LOCK_BADGE_RADIUS = 5.dp

/**
 * DocumentHost — the bounded-page host (roadmap Phase 2).
 *
 * This composable owns layout and gestures and nothing else. Selection, eligibility, snapping, history and
 * page structure all live in the shared engines behind [DocumentHostPresenter]; every action offered here is
 * `presenter.act { invoke(id, surface) }` or a call into the runtime's manipulation state machine. That is
 * what makes a touch handle, the dock, a keyboard chord and a TalkBack action four presentations of one
 * command instead of four implementations of one idea.
 *
 * Rendering is virtualized per page *and* per object from real geometry, so a long document composes what is
 * on screen and the instrumentation hook reports the range it genuinely built.
 */
@Composable
fun DocumentHostScreen(
    presenter: DocumentHostPresenter,
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
    var notice by remember { mutableStateOf<String?>(null) }
    var chartEditingId by remember { mutableStateOf<String?>(null) }
    var imageEditingId by remember { mutableStateOf<String?>(null) }
    var shapeEditingId by remember { mutableStateOf<String?>(null) }
    val configuration = LocalConfiguration.current
    val guide = rememberGuideUiController(presenter.runtime.guide)
    val history = rememberHistoryUiController(presenter.runtime.history)
    val recovery = rememberRecoveryUiController(presenter.runtime.recoveryBrowser) { presenter.act { } }
    // Editor Lab experiment (default off): a transient "deleted — Undo" toast after a destructive command,
    // mirroring the Tasks board's swipe pattern. The Undo action reuses the exact reversal the top bar exposes,
    // so it can never diverge from it. When the flag is off, nothing below observes the snackbar state.
    val undoToastEnabled = EditorLab.isEnabled(EditorLabFlag.EDITOR_UNDO_TOAST)
    val undoSnackbar = remember { SnackbarHostState() }
    val undoScope = rememberCoroutineScope()
    fun raiseUndoToast(commandId: String, result: com.norfold.app.editor.command.CommandResult) {
        val label = when (commandId) {
            DocumentIds.DELETE -> "Object deleted"
            DocumentIds.PAGE_DELETE -> "Page deleted"
            else -> return
        }
        if (result !is com.norfold.app.editor.command.CommandResult.Success) return
        undoScope.launch {
            val outcome = undoSnackbar.showSnackbar(label, actionLabel = "Undo", duration = SnackbarDuration.Short)
            if (outcome == SnackbarResult.ActionPerformed) {
                if (history.runtime != null) presenter.act { history.undo() } else presenter.act { undo() }
            }
        }
    }
    // Editor Lab experiment (default off): a floating layers panel over the active page's objects, front-to-
    // back. It reads the live scene and drives the shared selection plus the existing bring-forward /
    // send-backward commands, so ordering here can never diverge from the canvas or the keyboard chords.
    val layersPanelEnabled = EditorLab.isEnabled(EditorLabFlag.DOCUMENT_LAYERS_PANEL)
    var layersPanelOpen by remember { mutableStateOf(false) }
    val guideContext = GuideContext(
        host = GuideHost.DOCUMENT,
        capabilities = presenter.runtime.scope.commandContext(CommandSurface.DOCK).offeredCapabilities,
        domains = buildSet {
            add(GuideDomain.DOCUMENT)
            when {
                chartEditingId != null -> add(GuideDomain.CHART)
                imageEditingId != null -> add(GuideDomain.IMAGE)
                shapeEditingId != null -> add(GuideDomain.SHAPE)
                state.activeSurfaceId in setOf(DocumentSurfaces.COLOR_STUDIO, DocumentSurfaces.FONT_BROWSER, DocumentSurfaces.NAMED_STYLES) -> add(GuideDomain.STYLES)
                state.activeSurfaceId in setOf(DocumentSurfaces.RELATIONSHIP_MANAGER, DocumentSurfaces.COMMENTS, DocumentSurfaces.REVIEW) -> add(GuideDomain.RELATIONSHIPS)
            }
        },
        blockers = buildSet {
            if (state.manipulation != null) add(GuideBlocker.DRAG)
            if (state.editingObjectId != null || state.editingTableCell != null) add(GuideBlocker.TEXT_COMPOSITION)
            if (imageEditingId != null) add(GuideBlocker.CROP)
        },
    )

    // The runtime raises a notice when it had to switch representation to keep an action faithful. Consuming
    // it here (rather than reading it every recomposition) is what makes it a one-shot explanation.
    LaunchedEffect(state.notice) {
        state.notice?.let { notice = presenter.act { consumeNotice() } }
    }
    LaunchedEffect(state.lastFailure) {
        state.lastFailure?.let { notice = it }
    }

    // A window change re-presents open tools; it never rebuilds the session, the selection, the history or an
    // in-flight gesture. Keying on the two dimensions means a rotation reports once rather than every frame.
    LaunchedEffect(configuration.screenWidthDp, configuration.screenHeightDp) {
        presenter.act {
            onFormFactorChanged(FormFactor(configuration.screenWidthDp, configuration.screenHeightDp))
        }
    }

    // Back unwinds one logical level at a time through the coordinator: surface → multi-select → live gesture
    // → content editing → selection → editor. Only the last of those is navigation.
    BackHandler {
        when (presenter.act { back() }) {
            DocumentBackResult.LeftEditor -> onExit()
            else -> Unit
        }
    }

    // Which text field, if any, currently owns keyboard input.
    //
    // The root installs a *preview* key handler, so without this the canvas would see Backspace, Delete and
    // the arrow keys before the focused field did — and the bindings would delete the object the user is
    // typing in and nudge the frame instead of moving the caret. The title, the inspector and Page Control's
    // fields report their own focus here; a caret inside a scene object is contributed by the runtime itself,
    // so object text is protected even before any of them has reported anything.
    var chromeTextFocus by remember { mutableStateOf(false) }
    val textEntryActive = chromeTextFocus || state.editingObjectId != null

    Box(modifier.fillMaxSize()) {
    Column(
        Modifier
            .fillMaxSize()
            // The root Scaffold passes WindowInsets(0,0,0,0) and only pads the status bar, so every screen
            // owns its own bottom edge. Without this the keyboard covers the docks and the inspector — the
            // caret ends up behind the IME with no way to see what is being typed — and on a
            // gesture-navigation device the last dock row sits under the navigation pill. IME padding first,
            // navigation bars only when the keyboard is closed, matching FlowHostScreen's dock.
            .imePadding()
            .navigationBarsPadding()
            // Keys a text field owns fall through untouched — see DocumentKeyboardMap.resolve. Unbound keys
            // fall through too, so ordinary typing is never intercepted.
            .onPreviewKeyEvent { event ->
                val shortcut = event.toDocumentShortcut() ?: return@onPreviewKeyEvent false
                val handled = presenter.act { handleShortcut(shortcut, textEntryActive) }
                // Ctrl+Z reaches History through the host rather than through this controller, so the
                // controller is told to look: a reversal that must be disclosed puts its sheet on screen
                // instead of quietly declining.
                history.syncFromRuntime()
                handled
            },
    ) {
        DocumentTopBar(
            state = state,
            title = title,
            onTitleChange = onTitleChange,
            onTitleFocusChange = { chromeTextFocus = it },
            onOpenSidebar = onOpenSidebar,
            // With the History browser on, Undo goes through it: only that route can disclose a multi-step
            // or cross-target reversal before it happens (§13.4).
            onUndo = { if (history.runtime != null) presenter.act { history.undo() } else presenter.act { undo() } },
            onRedo = { if (history.runtime != null) presenter.act { history.redo() } else presenter.act { redo() } },
            onRepresentation = { presenter.act { setRepresentation(it) } },
            onIntent = { presenter.act { setIntent(it) } },
            onOpenPageControl = { presenter.act { openSurface(DocumentSurfaces.PAGE_CONTROL) } },
            onOpenPageList = { presenter.act { openSurface(DocumentSurfaces.PAGE_LIST) } },
            onEnterSelection = { presenter.act { enterSelectionMode(state.primaryObjectId) } },
            onOpenGuide = guide.runtime?.let { { guide.open() } },
            onOpenHistory = history.runtime?.let { { history.open() } },
            onOpenRecovery = recovery.runtime?.let { { recovery.open() } },
        )
        HorizontalDivider()

        notice?.let { message -> NoticeRow(message) { notice = null } }
        state.relationshipReview.connectMode?.let {
            NoticeRow("Connect mode · choose an eligible object target") {
                presenter.act { relationshipReview.exitConnectMode() }
            }
        }
        if (state.relationshipReview.relationships.isNotEmpty() || state.relationshipReview.unresolvedCount > 0) {
            NoticeRow(
                "${state.relationshipReview.relationships.size} relationships · ${state.relationshipReview.unresolvedCount} unresolved comments",
            ) { presenter.act { openSurface(DocumentSurfaces.REVIEW) } }
        }

        Box(Modifier.weight(1f)) {
            DocumentPageStack(
                presenter,
                onOpenTableInSpreadsheet,
                { chartEditingId = it; guide.signal(GuideSignal.CHART_STUDIO_OPENED) },
                { imageEditingId = it; guide.signal(GuideSignal.IMAGE_FOCUS_OPENED) },
                { shapeEditingId = it; guide.signal(GuideSignal.SHAPE_BUILDER_OPENED) },
                Modifier.fillMaxSize(),
            )
        }

        // Original View owns zoom; Adaptive View fits to the viewport and has nothing to zoom.
        if (state.representation == LayoutRepresentation.ORIGINAL) {
            HorizontalDivider()
            ZoomBar(presenter)
        }

        if (state.selectionMode) {
            HorizontalDivider()
            SelectionBar(
                count = state.selectedObjectIds.size,
                onCancel = { presenter.act { cancelSelectionMode() } },
                onDone = { presenter.act { finishSelectionMode() } },
            )
        }

        // The inspector belongs beside the selection it edits, not behind a menu: exact numbers are what a
        // user reaches for the moment a drag lands *nearly* right.
        state.primaryObject?.let { primary ->
            if (state.intent == WorkingIntent.EDIT) {
                HorizontalDivider()
                GeometryInspectorBar(
                    obj = primary,
                    presenter = presenter,
                    onFocusChange = { chromeTextFocus = it },
                    onNotice = { notice = it },
                )
            }
        }

        val hasStyleDock = state.editorStyle.textTargetCount > 0 || state.editorStyle.shapeTargetCount > 0 ||
            state.editorStyle.pageTargetCount > 0
        val invokeDockItem: (DocumentCommandItem) -> Unit = { item ->
            if (!item.enabled) {
                notice = item.disabledReason ?: "'${item.label}' is not available here"
            } else {
                val result = presenter.act { invoke(item.id, CommandSurface.DOCK) }
                if (undoToastEnabled) raiseUndoToast(item.id, result)
            }
        }
        val invokeStyleItem: (String) -> Unit = { id ->
            when (val result = presenter.act { invoke(id, CommandSurface.DOCK) }) {
                is com.norfold.app.editor.command.CommandResult.Failure -> notice = result.reason
                else -> Unit
            }
        }
        // The object inspector's entry point. Offered only when an object is selected, because the inspector
        // titles itself with that object's type and groups that object's actions — with nothing selected it
        // would be a sheet about nothing.
        val openInspector: (() -> Unit)? = state.primaryObject?.let {
            { presenter.act { openSurface(DocumentSurfaces.OBJECT_INSPECTOR, SurfaceClass.COMMAND_MENU) }; Unit }
        }
        if (state.formFactor.isPhone && (state.dock.isNotEmpty() || hasStyleDock)) {
            HorizontalDivider()
            DocumentCompactDock(state.dock, state, invokeDockItem, invokeStyleItem, openInspector)
        } else {
            if (state.dock.isNotEmpty()) {
                HorizontalDivider()
                DocumentDock(state.dock, invokeDockItem, openInspector)
            }
            if (hasStyleDock) {
                HorizontalDivider()
                DocumentStyleDock(state, invokeStyleItem)
            }
        }
    }
        if (undoToastEnabled) {
            SnackbarHost(
                undoSnackbar,
                Modifier.align(Alignment.BottomCenter).navigationBarsPadding().imePadding().padding(16.dp),
            )
        }
        // Editor Lab experiment (default off): a live X/Y, size and angle readout while an object is being
        // dragged, resized or rotated. It reads the in-flight preview geometry the canvas is already drawing.
        if (EditorLab.isEnabled(EditorLabFlag.DOCUMENT_LIVE_TRANSFORM_READOUT)) {
            DocumentTransformReadout(
                manipulation = state.manipulation,
                primaryObjectId = state.primaryObjectId,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 64.dp, start = 12.dp, end = 12.dp),
            )
        }
        if (layersPanelEnabled) {
            val activePage = state.pages.firstOrNull { it.id == state.activePageId } ?: state.pages.firstOrNull()
            val layers = activePage?.objectsFrontToBack().orEmpty()
            if (layersPanelOpen) {
                DocumentLayersPanel(
                    layers = layers,
                    selectedIds = state.selectedObjectIds.toSet(),
                    onSelect = { id -> presenter.act { selectObjects(listOf(id)) } },
                    onReorder = { commandId -> presenter.act { invoke(commandId, CommandSurface.DOCK) } },
                    onClose = { layersPanelOpen = false },
                    modifier = Modifier.align(Alignment.TopEnd).padding(top = 64.dp, end = 12.dp),
                )
            } else {
                DocumentLayersLauncher(
                    count = layers.size,
                    onOpen = { layersPanelOpen = true },
                    modifier = Modifier.align(Alignment.TopEnd).padding(top = 64.dp, end = 12.dp),
                )
            }
        }
    }

    when (state.activeSurfaceId) {
        DocumentSurfaces.OBJECT_INSPECTOR -> DocumentObjectInspectorSheet(
            state = state,
            // The triggers this surface's own policy accepts, not the ones its neighbours use. Every other
            // sheet here is a `PICKER_BROWSER` and closes on `OUTSIDE_TAP`; the inspector is a `COMMAND_MENU`,
            // whose `AFTER_COMMAND` policy ignores an outside tap — so passing `OUTSIDE_TAP` would hide the
            // sheet while leaving the surface open on the coordinator's stack, and the next Back press would
            // be spent closing something invisible. A sheet dismissed without acting is a cancel; a row tapped
            // is a command executed, which is precisely what this class is for.
            onCancel = { presenter.act { dismiss(DismissTrigger.CANCEL) } },
            // No disabled branch: the inspector draws a disabled row with the editor's own reason beneath it
            // and does not make it clickable, so an unavailable action explains itself in place rather than
            // through a notice the user has to trigger.
            onInvoke = { item ->
                if (undoToastEnabled) {
                    val result = presenter.act { invoke(item.id, CommandSurface.DOCK) }
                    presenter.act { dismiss(DismissTrigger.COMMAND_EXECUTED) }
                    raiseUndoToast(item.id, result)
                } else {
                    presenter.act {
                        invoke(item.id, CommandSurface.DOCK)
                        dismiss(DismissTrigger.COMMAND_EXECUTED)
                    }
                }
            },
        )
        DocumentSurfaces.PAGE_CONTROL -> PageControlSheet(
            state = state,
            onDismiss = { presenter.act { dismiss(DismissTrigger.OUTSIDE_TAP) } },
            onScope = { presenter.act { setPageScope(it) } },
            onInvoke = { id -> presenter.act { invokePageControl(id, state.pageScope) } },
            onApplyValue = { id, raw -> presenter.act { invokePageValue(id, state.pageScope, raw) } },
            onClearNewPageTemplate = { presenter.act { clearNewPageTemplate() } },
            onOpenGuide = guide.runtime?.let { { guide.open() } },
        )
        DocumentSurfaces.PAGE_LIST -> PageListSheet(
            state = state,
            onDismiss = { presenter.act { dismiss(DismissTrigger.OUTSIDE_TAP) } },
            onToggle = { id -> presenter.act { togglePageSelection(id) } },
            onClear = { presenter.act { clearPageSelection() } },
            onSelect = { id -> presenter.act { selectPage(id) } },
        )
        DocumentSurfaces.COLOR_STUDIO, DocumentSurfaces.FONT_BROWSER, DocumentSurfaces.NAMED_STYLES ->
            DocumentEditorStyleSurface(state.activeSurfaceId, state, presenter) { notice = it }
        DocumentSurfaces.RELATIONSHIP_MANAGER, DocumentSurfaces.COMMENTS, DocumentSurfaces.REVIEW -> {
            RelationshipReviewSurface(
                title = when (state.activeSurfaceId) {
                    DocumentSurfaces.COMMENTS -> "Comments"
                    DocumentSurfaces.REVIEW -> "Review"
                    else -> "Relationships"
                },
                state = state.relationshipReview,
                onDismiss = { presenter.act {
                    if (state.activeSurfaceId == DocumentSurfaces.REVIEW) relationshipReview.closeReviewMode()
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
    }

    chartEditingId?.let { blockId ->
        val block = state.document.findById(blockId) as? ChartBlock
        if (block == null) {
            chartEditingId = null
        } else {
            val loaded = remember(block.chartDocument, block.vegaLiteSpec) { ChartBlockAdapter.load(block) }
            ChartStudioSheet(
                initial = loaded.document,
                readOnly = state.intent != WorkingIntent.EDIT || !loaded.isEditable,
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
                readOnly = state.intent != WorkingIntent.EDIT,
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
                readOnly = state.intent != WorkingIntent.EDIT,
                onCommit = { edited -> presenter.act { updateShape(blockId, edited) } },
                onDismiss = { shapeEditingId = null },
                onDone = { shapeEditingId = null },
                onOpenGuide = guide.runtime?.let { { guide.open() } },
                onCommitted = { guide.signal(GuideSignal.SHAPE_BUILDER_COMMITTED) },
                onFocusHistoryOwner = { presenter.runtime.setFocusHistoryOwner(it) },
            )
        }
    }
    if (state.interchange.requestedAction == null && state.interchange.rawDraft == null) {
        GuideHostLayer(guide, guideContext)
    }
    HistoryHostLayer(history)
    RecoveryHostLayer(recovery)
}

/**
 * Editor Lab experiment (default off): the live transform readout.
 *
 * It reads the in-flight [ActiveManipulation.preview] — the same uncommitted [SceneGeometry] the canvas is
 * drawing this frame — so the numbers are exactly what the object becomes when the gesture ends, never a
 * second computation that could disagree with the render. Values are in points, matching [SceneGeometry]'s
 * own unit; the field the active gesture is changing is emphasised. Purely additive: it observes manipulation
 * state and draws nothing when no gesture is in flight.
 */
@Composable
private fun DocumentTransformReadout(
    manipulation: ActiveManipulation?,
    primaryObjectId: String?,
    modifier: Modifier = Modifier,
) {
    val geometry = manipulation?.let { m ->
        primaryObjectId?.let { m.previewFor(it) } ?: m.preview.values.firstOrNull()
    } ?: return
    val kind = manipulation.kind
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.inverseSurface,
        contentColor = MaterialTheme.colorScheme.inverseOnSurface,
        tonalElevation = 3.dp,
        shadowElevation = 6.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DocumentReadoutField("X", geometry.x.roundToInt().toString(), kind == ActiveManipulation.Kind.MOVE)
            DocumentReadoutField("Y", geometry.y.roundToInt().toString(), kind == ActiveManipulation.Kind.MOVE)
            DocumentReadoutField("W", geometry.width.roundToInt().toString(), kind == ActiveManipulation.Kind.RESIZE)
            DocumentReadoutField("H", geometry.height.roundToInt().toString(), kind == ActiveManipulation.Kind.RESIZE)
            DocumentReadoutField("∠", "${geometry.rotation.roundToInt()}°", kind == ActiveManipulation.Kind.ROTATE)
        }
    }
}

@Composable
private fun DocumentReadoutField(label: String, value: String, emphasised: Boolean) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = LocalContentColor.current.copy(alpha = 0.6f),
        )
        Text(
            value,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = if (emphasised) FontWeight.Bold else FontWeight.Medium,
        )
    }
}

/**
 * Editor Lab experiment (default off): the collapsed launcher for the layers panel — a small pill showing the
 * object count on the active page. Tapping it opens [DocumentLayersPanel].
 */
@Composable
private fun DocumentLayersLauncher(count: Int, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        tonalElevation = 2.dp,
        shadowElevation = 4.dp,
        onClick = onOpen,
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Layers", style = MaterialTheme.typography.labelLarge)
            Text(
                count.toString(),
                style = MaterialTheme.typography.labelMedium,
                color = LocalContentColor.current.copy(alpha = 0.6f),
            )
        }
    }
}

/**
 * Editor Lab experiment (default off): the layers panel.
 *
 * The list is the active page's objects front-to-back — the same order hit-testing uses — and a row tap routes
 * through the shared [DocumentHostRuntime.selectObjects], so the panel's idea of "selected" is the canvas's.
 * The reorder buttons invoke the existing `document.object.*` z-order commands on that selection, meaning the
 * panel adds no ordering logic of its own; it is one more presentation of commands the keyboard already has.
 */
@Composable
private fun DocumentLayersPanel(
    layers: List<SceneObject>,
    selectedIds: Set<String>,
    onSelect: (String) -> Unit,
    onReorder: (String) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.width(268.dp),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        tonalElevation = 3.dp,
        shadowElevation = 8.dp,
    ) {
        Column(Modifier.padding(vertical = 6.dp)) {
            Row(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Layers", style = MaterialTheme.typography.titleSmall)
                TextButton(onClick = onClose) { Text("Done") }
            }
            HorizontalDivider()
            if (layers.isEmpty()) {
                Text(
                    "No objects on this page yet.",
                    style = MaterialTheme.typography.bodySmall,
                    color = LocalContentColor.current.copy(alpha = 0.6f),
                    modifier = Modifier.padding(16.dp),
                )
            } else {
                Column(
                    Modifier
                        .heightIn(max = 280.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    layers.forEach { obj ->
                        val selected = obj.id in selectedIds
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { onSelect(obj.id) }
                                .then(if (selected) Modifier.background(MaterialTheme.colorScheme.secondaryContainer) else Modifier)
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                obj.kind.name,
                                style = MaterialTheme.typography.labelSmall,
                                color = LocalContentColor.current.copy(alpha = 0.55f),
                                maxLines = 1,
                                modifier = Modifier.width(58.dp),
                            )
                            Text(
                                obj.displayName(),
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            if (obj.lock != SceneLock.None) {
                                Text(
                                    "locked",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = LocalContentColor.current.copy(alpha = 0.55f),
                                )
                            } else if (!obj.visible) {
                                Text(
                                    "hidden",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = LocalContentColor.current.copy(alpha = 0.55f),
                                )
                            }
                        }
                    }
                }
                HorizontalDivider()
                val hasSelection = selectedIds.isNotEmpty()
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = { onReorder(DocumentIds.BRING_TO_FRONT) }, enabled = hasSelection, modifier = Modifier.weight(1f)) { Text("Front") }
                    TextButton(onClick = { onReorder(DocumentIds.BRING_FORWARD) }, enabled = hasSelection, modifier = Modifier.weight(1f)) { Text("Up") }
                    TextButton(onClick = { onReorder(DocumentIds.SEND_BACKWARD) }, enabled = hasSelection, modifier = Modifier.weight(1f)) { Text("Down") }
                    TextButton(onClick = { onReorder(DocumentIds.SEND_TO_BACK) }, enabled = hasSelection, modifier = Modifier.weight(1f)) { Text("Back") }
                }
            }
        }
    }
}

// ── Page stack: virtualized composition from real geometry ──────────────────────────────────────────

/**
 * The scrolling stack of pages.
 *
 * Only the pages the window reports are composed. The window is computed from real page geometry rather than
 * an item count, which is what lets pages differ in size without every page after the first differing one
 * being drawn in the wrong place.
 */
@Composable
private fun DocumentPageStack(
    presenter: DocumentHostPresenter,
    onOpenTableInSpreadsheet: (String) -> Unit,
    onOpenChart: (String) -> Unit,
    onOpenImage: (String) -> Unit,
    onOpenShape: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state = presenter.state
    val scroll = rememberScrollState()
    val density = LocalDensity.current

    BoxWithConstraints(modifier) {
        // maxHeight/maxWidth are already dp, so only the pt↔dp factor applies — dividing by density as well
        // would report a viewport a few times too small and virtualize away pages that are on screen.
        val canvasGutter = if (maxWidth < 600.dp) 12.dp else 32.dp
        val usableWidth = (maxWidth - canvasGutter * 2).coerceAtLeast(1.dp)
        val viewportHeightPt = maxHeight.value / DP_PER_POINT
        val viewportWidthPt = usableWidth.value / DP_PER_POINT

        // Adaptive View fits the widest page to the viewport; Original View honours the user's zoom. Both are
        // draw-time scales — neither is ever written back into stored geometry.
        val scale = when (state.representation) {
            LayoutRepresentation.ADAPTIVE -> PageVirtualizer.adaptiveScale(state.pages, viewportWidthPt)
            LayoutRepresentation.ORIGINAL -> state.zoom
        }

        // Fit width / fit page need the measured viewport, which only exists here.
        LaunchedEffect(viewportWidthPt, viewportHeightPt) {
            presenter.runtime.reportViewport(viewportWidthPt, viewportHeightPt)
        }

        val scrollOffsetPt = with(density) { scroll.value.toDp().value } / DP_PER_POINT / scale
        val pageDensity = remember(density.density) { Density(density = density.density, fontScale = 1f) }
        val window = remember(state.revision, scrollOffsetPt, viewportHeightPt, scale) {
            presenter.runtime.pageWindow(scrollOffsetPt, viewportHeightPt / scale)
        }

        Box(
            Modifier
                .fillMaxWidth()
                .verticalScroll(scroll)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(horizontal = canvasGutter, vertical = 12.dp)
                // Pinch to zoom and drag to pan, in Original View only: Adaptive View's scale is derived from
                // the viewport, so a pinch there would fight the fit it exists to maintain.
                .then(
                    if (state.representation == LayoutRepresentation.ORIGINAL) {
                        Modifier.pointerInput(state.representation) {
                            detectTransformGestures { _, pan, zoomChange, _ ->
                                if (zoomChange != 1f) presenter.act { setZoom(state.zoom * zoomChange) }
                                if (pan.x != 0f) {
                                    presenter.act {
                                        setPan(state.panX + pan.x.toPagePoints(state.zoom, density))
                                    }
                                }
                            }
                        }
                    } else {
                        Modifier
                    },
                ),
        ) {
            // The spacer establishes the full scroll extent even though only a few pages exist in the tree;
            // without it, scrolling would stop at the bottom of the last *composed* page.
            Box(Modifier.fillMaxWidth().height(window.totalHeight.ptToDp(scale)))

            for (index in window.firstIndex..window.lastIndex) {
                val page = state.pages.getOrNull(index) ?: continue
                // Android's font scale belongs to editor chrome. Page typography is stored in document
                // points and has its own explicit style controls; applying both would enlarge text inside
                // fixed scene geometry and clip otherwise-valid content. The dp density stays identical, so
                // page/object geometry is unchanged while sp no longer receives the UI-only multiplier.
                CompositionLocalProvider(LocalDensity provides pageDensity) {
                    PageSheet(
                        page = page,
                        pageIndex = index,
                        topPt = window.topOf(index),
                        scale = scale,
                        // The band of this page that is actually on screen, in the page's own coordinates. Object
                        // virtualization is resolved against it.
                        visibleTopPt = scrollOffsetPt - window.topOf(index),
                        visibleBottomPt = scrollOffsetPt + viewportHeightPt / scale - window.topOf(index),
                        presenter = presenter,
                        onOpenTableInSpreadsheet = onOpenTableInSpreadsheet,
                        onOpenChart = onOpenChart,
                        onOpenImage = onOpenImage,
                        onOpenShape = onOpenShape,
                    )
                }
            }
        }
    }
}

/** One page: its sheet, its structure guides, its metadata bands, and the scene objects on it. */
@Composable
private fun PageSheet(
    page: DocumentPageBlock,
    pageIndex: Int,
    topPt: Float,
    scale: Float,
    visibleTopPt: Float,
    visibleBottomPt: Float,
    presenter: DocumentHostPresenter,
    onOpenTableInSpreadsheet: (String) -> Unit,
    onOpenChart: (String) -> Unit,
    onOpenImage: (String) -> Unit,
    onOpenShape: (String) -> Unit,
) {
    val state = presenter.state
    val editing = state.representation == LayoutRepresentation.ORIGINAL && state.intent == WorkingIntent.EDIT
    val isActive = page.id == state.activePageId
    val isSelected = page.id in state.selectedPageIds
    val numberLabel = state.pageNumberLabels.getOrNull(pageIndex)

    // Selected, manipulated and edited objects are never culled: dropping the object under the user's finger
    // is worse than composing a few extra boxes.
    val keepIds = remember(state.revision) {
        buildSet {
            addAll(state.selectedObjectIds)
            state.manipulation?.objectIds?.let { addAll(it) }
            state.editingObjectId?.let { add(it) }
        }
    }
    val visible = remember(state.revision, page.id, visibleTopPt, visibleBottomPt) {
        PageVirtualizer.visibleObjects(
            page = page,
            topPt = visibleTopPt,
            bottomPt = visibleBottomPt,
            keepIds = keepIds,
            previewOf = { id -> state.manipulation?.previewFor(id) },
        ).filter { it.visible }
    }

    // The page's paint is resolved once and used twice: to fill the sheet, and to measure the chrome drawn on
    // it. Resolving it separately in the two places is how a page could end up with an edge measured against a
    // colour it is no longer painted with — including mid-Color-Studio, where `previewPaint` means the sheet
    // is showing a colour the document has not committed yet and the chrome has to follow it live.
    val pagePaint = state.editorStyle.previewPaint.takeIf {
        state.editorStyle.colorStudio.target?.role == com.norfold.app.domain.style.ColorRole.PAGE_BACKGROUND &&
            page.id in state.editorStyle.colorStudio.target.targetIds
    } ?: resolvedPageBackground(page, state.document.stylesheet())
    val chrome = rememberDocumentChromeColors(pagePaint)

    Box(
        Modifier
            .offset {
                IntOffset(
                    state.panX.ptToPx(scale, this).roundToInt(),
                    topPt.ptToPx(scale, this).roundToInt(),
                )
            }
            .size(page.geometry.width.ptToDp(scale), page.geometry.height.ptToDp(scale))
            .backgroundPaint(pagePaint)
            .drawBehind {
                drawPageStructure(page, scale, editing, isActive, isSelected, chrome)
                // Guides belong to the page being manipulated, not to every page on screen: the positions are
                // page-relative, so drawing them on a neighbour would put the line in the wrong place.
                val manipulation = state.manipulation
                if (manipulation != null && manipulation.objectIds.any { page.objectById(it) != null }) {
                    drawSnapGuides(manipulation.guides, scale, chrome)
                }
            }
            .pointerInput(page.id, scale, state.revision) {
                detectTapGestures { offset ->
                    // Screen → page points, so a tap resolves against the same geometry the model stores and
                    // hit testing behaves identically at any zoom.
                    presenter.act {
                        // A tap on empty page area leaves content editing: the user aimed somewhere else, and
                        // keeping the caret would leave the keyboard up over a page they are no longer in.
                        endContentEdit()
                        selectAt(
                            page.id,
                            offset.x.toPagePoints(scale, this@pointerInput),
                            offset.y.toPagePoints(scale, this@pointerInput),
                        )
                    }
                }
            }
            .semantics { contentDescription = "Page ${numberLabel ?: (pageIndex + 1)}" },
    ) {
        // The page's own painted colour, published for the chrome drawn *inside* an object rather than around
        // it. `chrome` below covers the outlines this file draws itself; the content of an object is composed by
        // [com.norfold.app.ui.editor.document.SceneObjectContent], which is eleven levels of recursion away and
        // has no way to know what colour the sheet is — and here that is not a theme token at all but whatever
        // paint the user chose for this page, live-previewed mid-Color-Studio. Publishing it is what lets a
        // resting edge down there measure against the pixel it is actually drawn on instead of guessing the app
        // background; the alternative was a sixteenth parameter threaded through every block type.
        //
        // `pageBackdrop` resolves a ramp to its worst stop for the reason it documents: one chrome colour has to
        // survive every stop, and the extreme is what decides.
        val scope = this
        CompositionLocalProvider(LocalChromeContainer provides EditorChromeColors.pageBackdrop(pagePaint)) {
            with(scope) {
                // Header, footer and page number are page *metadata*, drawn beneath the objects so content placed
                // over a running head still wins. Reserved-but-invisible bands were the defect: the geometry
                // reserved space the user could not see, so their content sat mysteriously low on the sheet.
                PageMetadata(page, pageIndex, numberLabel, scale)

                // Back-to-front: the front-most object is drawn last, so it is the one that overlaps the others.
                visible.sortedBy { it.z }.forEach { obj ->
                    SceneObjectBox(
                        obj, page, scale, presenter, chrome,
                        onOpenTableInSpreadsheet, onOpenChart, onOpenImage, onOpenShape,
                    )
                }
            }
        }
    }
}

/** The header band, the footer band and the printed page number, drawn where the geometry reserves them. */
@Composable
private fun androidx.compose.foundation.layout.BoxScope.PageMetadata(
    page: DocumentPageBlock,
    pageIndex: Int,
    numberLabel: String?,
    scale: Float,
) {
    val geometry = page.geometry
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val renderScale = remember(scale) { DocumentRenderScale.atZoom(scale) }

    val header = geometry.headerFooter
    // A section's first page commonly suppresses the running head, and honouring that here is what makes the
    // setting mean something rather than merely round-trip.
    val suppressed = header.differentFirstPage && pageIndex == 0

    if (header.hasHeader && !suppressed) {
        Box(
            Modifier
                .offset { IntOffset(0, geometry.margins.top.ptToPx(scale, this).roundToInt()) }
                .fillMaxWidth()
                .padding(horizontal = geometry.margins.left.ptToDp(scale)),
        ) {
            Text(header.headerText, style = renderScale.pointTextStyle(MaterialTheme.typography.labelSmall), color = muted)
        }
    }

    if (header.hasFooter && !suppressed) {
        Box(
            Modifier
                .align(Alignment.BottomStart)
                .offset { IntOffset(0, -geometry.margins.bottom.ptToPx(scale, this).roundToInt()) }
                .fillMaxWidth()
                .padding(horizontal = geometry.margins.left.ptToDp(scale)),
        ) {
            Text(header.footerText, style = renderScale.pointTextStyle(MaterialTheme.typography.labelSmall), color = muted)
        }
    }

    if (geometry.numbering.showNumber && numberLabel != null) {
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .offset { IntOffset(0, -(geometry.margins.bottom / 2f).ptToPx(scale, this).roundToInt()) },
        ) {
            Text(numberLabel, style = renderScale.pointTextStyle(MaterialTheme.typography.labelSmall), color = muted)
        }
    }
}

// ── Scene objects and direct manipulation ───────────────────────────────────────────────────────────

/**
 * One scene object, drawn at its **render** geometry.
 *
 * Render geometry is the manipulation preview while a gesture is in flight and the stored geometry otherwise,
 * so what the user sees during a drag is exactly what will be committed — the visual and the model can never
 * disagree, because there is only one function that answers "where is this".
 *
 * **Gesture ownership is exclusive and ordered.** Exactly one interaction can own a touch at any moment:
 *
 *  - in **multi-select**, a tap toggles the object and there is no drag at all;
 *  - while **editing this object's text**, the content owns its own gestures and the box does not drag —
 *    otherwise every attempt to place a caret would move the frame;
 *  - otherwise the box owns tap (select, or enter editing on a second deliberate tap) and drag (move).
 *
 * Handles are drawn only for the **primary** object, so a multi-object selection does not sprout four sets of
 * corner targets that each claim to resize something the group operation would not.
 */
@Composable
private fun SceneObjectBox(
    obj: SceneObject,
    page: DocumentPageBlock,
    scale: Float,
    presenter: DocumentHostPresenter,
    chrome: DocumentChromeColors,
    onOpenTableInSpreadsheet: (String) -> Unit,
    onOpenChart: (String) -> Unit,
    onOpenImage: (String) -> Unit,
    onOpenShape: (String) -> Unit,
) {
    val state = presenter.state
    val geometry = state.manipulation?.previewFor(obj.id) ?: obj.geometry
    val selected = obj.id in state.selectedObjectIds
    val isPrimary = obj.id == state.primaryObjectId
    val editable = state.representation == LayoutRepresentation.ORIGINAL && state.intent == WorkingIntent.EDIT
    val editingThis = state.editingObjectId == obj.id

    // Accessibility gets the same command ids the dock does, resolved against this object's own eligibility.
    val actions = remember(obj.id, obj.lock, state.revision) {
        presenter.runtime.accessibilityItemsFor(obj)
    }

    // Whether the press currently being resolved carried Ctrl/Cmd. Written on the Initial pointer pass and
    // read by the tap detector, which is how a modifier-click and a plain click become two meanings of one
    // gesture rather than two gesture detectors competing for the same pointer.
    var pressWasModified by remember(obj.id) { mutableStateOf(false) }

    Box(
        Modifier
            .offset {
                IntOffset(
                    geometry.x.ptToPx(scale, this).roundToInt(),
                    geometry.y.ptToPx(scale, this).roundToInt(),
                )
            }
            .size(geometry.width.ptToDp(scale), geometry.height.ptToDp(scale))
            // The **whole object** rotates — content, chrome and selection outline together — about its own
            // centre, which is the same centre `SceneGeometry.localToPage`/`containsRotated` use. Rotating
            // only the chrome inside `drawBehind` was the defect: the outline and the hit test agreed with
            // each other and both disagreed with the visible text, so a rotated frame drew its content
            // straight inside a slanted box.
            //
            // `graphicsLayer` rotates the layout node, so Compose's own hit testing follows the drawing and a
            // tap on the visibly-rotated content lands on it. Page-level tap routing is unaffected: taps on
            // empty page area are resolved by the page's own `pointerInput` against stored geometry through
            // `containsRotated`, which un-rotates about this same centre. Stored geometry is never touched.
            .graphicsLayer {
                rotationZ = geometry.rotation
                transformOrigin = TransformOrigin.Center
            }
            .drawBehind { drawObjectChrome(obj, selected, isPrimary, chrome) }
            .then(
                if (state.relationshipReview.connectMode != null) {
                    Modifier.pointerInput(obj.id, state.revision) {
                        detectTapGestures { presenter.act { selectObjects(listOf(obj.id)) } }
                    }
                } else if (state.selectionMode) {
                    Modifier.pointerInput(obj.id, state.revision) {
                        detectTapGestures { presenter.act { toggleObjectSelection(obj.id) } }
                    }
                } else if (editingThis) {
                    // The content owns every gesture inside it while its text is being edited.
                    Modifier
                } else {
                    Modifier
                        .pointerInput(obj.id) {
                            // Records whether the *current* press carried a toggle modifier, on the Initial
                            // pass — before any gesture detector on this node has had the event.
                            //
                            // Reading modifiers inside the tap detector is not an option (the callback has no
                            // event), and a competing `pointerInput` that consumed the press itself would be
                            // racing the tap detector for the same pointer: whichever Compose delivered to
                            // first would win, which is a coin toss dressed up as a rule. Observing on Initial
                            // and *not* consuming leaves the tap detector's behaviour intact and merely tells
                            // it, through `pressWasModified`, which of its two meanings this press has.
                            awaitPointerEventScope {
                                while (true) {
                                    val event = awaitPointerEvent(PointerEventPass.Initial)
                                    if (event.type != PointerEventType.Press) continue
                                    pressWasModified = event.keyboardModifiers.hasToggleModifier()
                                }
                            }
                        }
                        .pointerInput(obj.id, selected, editable, state.revision) {
                            detectTapGestures(
                                onTap = {
                                    val decision = documentClickDecision(
                                        modifiers = SelectionModifiers(toggle = pressWasModified),
                                        alreadySelected = selected,
                                        editable = editable,
                                    )
                                    presenter.act {
                                        when (decision) {
                                            // Ctrl/Cmd-click adds or removes this object. Resolved before the
                                            // second-tap rule, so modifier-clicking an already-selected frame
                                            // toggles it rather than dropping a caret into it.
                                            DocumentClickAction.TOGGLE_SELECTION ->
                                                toggleObjectSelection(obj.id)
                                            // First tap selects; a second, deliberate tap on an
                                            // already-selected text frame enters its content. Entering on the
                                            // first tap would make every attempt to move a frame drop a caret
                                            // into it instead.
                                            DocumentClickAction.NORMAL ->
                                                if (selected && editable) beginContentEdit(obj.id)
                                                else selectObjects(listOf(obj.id))
                                        }
                                    }
                                },
                                // Long-press is the *accelerator* into multi-select, never the only way in —
                                // the top bar's Select control is the discoverable one.
                                onLongPress = { presenter.act { enterSelectionMode(obj.id) } },
                            )
                        }
                        .pointerInput(obj.id, scale, editable, state.revision) {
                            // A locked object still accepts the tap that selects it (§4.13) — the drag simply
                            // never begins, because `beginManipulation` refuses it. Selection and
                            // manipulability are separate questions, and conflating them is what makes a
                            // locked object unreachable.
                            //
                            // The running total lives here, for the whole gesture: the runtime wants the delta
                            // from the manipulation's *start*, and feeding it per-frame deltas instead would
                            // make a snap sticky — the snapped position would become the base the next delta
                            // is added to.
                            var totalX = 0f
                            var totalY = 0f
                            detectDragGestures(
                                onDragStart = {
                                    totalX = 0f
                                    totalY = 0f
                                    presenter.act {
                                        if (obj.id !in scope.selectedObjectIds()) selectObjects(listOf(obj.id))
                                        beginManipulation(ActiveManipulation.Kind.MOVE)
                                    }
                                },
                                onDrag = { change, dragAmount ->
                                    change.consume()
                                    // Deltas arrive in the *rotated* layer's own frame, so they are carried
                                    // back into page space before they become a move. Using them raw would
                                    // send a rotated object sideways when the finger moved down.
                                    val (pageDx, pageDy) = geometry.rotateVectorToPage(
                                        dragAmount.x.toPagePoints(scale, this@pointerInput),
                                        dragAmount.y.toPagePoints(scale, this@pointerInput),
                                    )
                                    totalX += pageDx
                                    totalY += pageDy
                                    presenter.act { updateMove(totalX, totalY) }
                                },
                                onDragEnd = { presenter.act { endManipulation() } },
                                onDragCancel = { presenter.act { cancelManipulation() } },
                            )
                        }
                },
            )
            .semantics {
                contentDescription = obj.displayName()
                if (state.relationshipReview.connectMode != null) {
                    stateDescription = "Eligible relationship target"
                }
                customActions = actions.filter { it.enabled }.map { item ->
                    CustomAccessibilityAction(item.label) {
                        presenter.act {
                            selectObjects(listOf(obj.id))
                            invoke(item.id, CommandSurface.ACCESSIBILITY)
                        }
                        true
                    }
                }
            },
    ) {
        val objectSheet = state.document.stylesheet()
        val renderScale = remember(scale) { DocumentRenderScale.atZoom(scale) }
        val objectMetrics = remember(obj.content, objectSheet, renderScale) {
            renderScale.pointBlockMetrics(resolvedBlockMetrics(obj.content, objectSheet))
        }
        SceneObjectContent(
            block = obj.content,
            objectId = obj.id,
            // Read mode never renders a live field. The runtime already ends content editing on the way into
            // Read, so this is a second, independent guarantee rather than the only one: a caret surviving
            // into a mode whose whole promise is "nothing changes" is the failure worth two locks.
            editing = editingThis && editable,
            callbacks = remember(obj.id, state.revision) {
                SceneContentCallbacks(
                    onEdit = { edit ->
                        presenter.act {
                            editObjectText(edit.objectId, edit.blockId, edit.itemId, edit.before, edit.after)
                        }
                    },
                    onToggleTodo = { blockId, itemId ->
                        presenter.act { toggleObjectTodo(obj.id, blockId, itemId) }
                    },
                    onTableCellActivate = { row, column ->
                        presenter.act { setObjectTableCell(obj.id, row, column) }
                    },
                    onTableCellDraft = { _, _, value -> presenter.act { updateObjectTableDraft(value) } },
                    onTableCellCommit = { row, column, value ->
                        presenter.act { editObjectTableCell(obj.id, row, column, value) }
                    },
                    onOpenTableInSpreadsheet = onOpenTableInSpreadsheet,
                    onOpenChart = onOpenChart,
                    onOpenImage = onOpenImage,
                    onOpenShape = onOpenShape,
                )
            },
            modifier = Modifier.fillMaxWidth().padding(
                start = renderScale.pointToDp(4f),
                end = renderScale.pointToDp(4f),
                top = objectMetrics.beforeDp.dp,
                bottom = objectMetrics.afterDp.dp,
            ),
            stylesheet = objectSheet,
            previewRole = state.editorStyle.colorStudio.target?.role,
            previewPaint = state.editorStyle.previewPaint,
            previewTargetIds = state.editorStyle.colorStudio.target?.targetIds.orEmpty().toSet(),
            renderScale = renderScale,
            activeTableCell = state.editingTableCell?.takeIf { it.first == obj.id }?.second,
            tableCellDraft = state.editingTableDraft,
            chartCalculationProvider = presenter.runtime.chartCalculationProvider(),
            chartDataRevision = state.revision,
        )
    }

    // Handles are drawn *outside* the object box so a 44dp touch target is never clipped by a small object —
    // an 8pt-tall shape would otherwise have handles a finger cannot land on. Only the primary object gets
    // them: a secondary selected object drawing its own corner targets would promise an individual resize
    // that the group operation does not perform.
    //
    // They are positioned from the **rotated** corner/handle points, so they sit on the object as drawn. The
    // handles themselves are deliberately *not* rotated: a square target reads identically at any angle, and
    // what matters is that it is where the corner visibly is.
    if (isPrimary && editable && !state.selectionMode && !editingThis) {
        if (obj.canResize) {
            ResizeCorner.entries.forEach { corner ->
                ResizeHandle(obj, geometry, corner, scale, presenter)
            }
        }
        if (obj.canRotate) {
            RotationHandle(obj, geometry, scale, presenter)
        }
    }
}

/**
 * One corner resize handle, drawn where that corner **actually is** once rotation is applied.
 *
 * All four corners exist, and each anchors the corner **opposite** it: dragging the top-left moves x and y as
 * well as width and height, which is what "resize from this corner" means to a human. The one-corner version
 * this replaces could only ever grow an object down and to the right, so re-sizing against a fixed bottom
 * edge required moving the object afterwards and doing the arithmetic by hand.
 *
 * Position comes from [SceneGeometry.rotatedCornerOf] — the same forward transform the object's own
 * `graphicsLayer` applies — so on a rotated object the handle sits on the visible corner instead of hovering
 * beside it at the unrotated one, which is where the touch target and the drawing used to disagree.
 *
 * The gesture reports an absolute page position rather than accumulated deltas, so a snap can never become the
 * base for the next frame and stick to a guide the user has already dragged away from.
 */
@Composable
private fun ResizeHandle(
    obj: SceneObject,
    geometry: SceneGeometry,
    corner: ResizeCorner,
    scale: Float,
    presenter: DocumentHostPresenter,
) {
    val density = LocalDensity.current
    val targetPx = with(density) { HANDLE_TARGET.toPx() }
    val (cornerX, cornerY) = geometry.rotatedCornerOf(corner)

    Box(
        Modifier
            .offset {
                // Centred on the corner: the visual sits on the corner, and the target extends past it, which
                // is what makes the handle reachable when the object is smaller than the target.
                IntOffset(
                    (cornerX.ptToPx(scale, this) - targetPx / 2f).roundToInt(),
                    (cornerY.ptToPx(scale, this) - targetPx / 2f).roundToInt(),
                )
            }
            .size(HANDLE_TARGET)
            .pointerInput(obj.id, corner, scale, geometry.rotation) {
                // The corner's page position, accumulated across the gesture from where it started. The
                // handle is not inside the rotated layer, so its deltas are already page-space directions.
                var pointerX = cornerX
                var pointerY = cornerY
                detectDragGestures(
                    onDragStart = {
                        pointerX = cornerX
                        pointerY = cornerY
                        presenter.act { beginManipulation(ActiveManipulation.Kind.RESIZE) }
                    },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        pointerX += dragAmount.x.toPagePoints(scale, this@pointerInput)
                        pointerY += dragAmount.y.toPagePoints(scale, this@pointerInput)
                        presenter.act { updateResize(corner, pointerX, pointerY) }
                    },
                    onDragEnd = { presenter.act { endManipulation() } },
                    onDragCancel = { presenter.act { cancelManipulation() } },
                )
            }
            .semantics { contentDescription = "Resize ${corner.name.lowercase().replace('_', ' ')}" },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(HANDLE_VISUAL)
                .background(MaterialTheme.colorScheme.primary),
        )
    }
}

/**
 * The rotation handle, above the object's top edge **as the object is oriented**.
 *
 * Its position comes from [SceneGeometry.rotationHandlePoint], so it swings around with the object rather than
 * staying pinned above the page's north while the frame turns underneath it — which is what made the handle
 * appear to detach from the thing it rotates.
 *
 * The angle is computed from the pointer's position relative to the object's **centre**, which is the same
 * centre the drawing rotates about and the same centre hit testing un-rotates about — so what the user drags,
 * what they see, and what responds to a later tap are one thing rather than three approximations of it.
 *
 * Like every other gesture here it previews and commits once, so a rotation is one undo step and cancelling
 * restores the pre-gesture angle exactly rather than by inverting the arc.
 */
@Composable
private fun RotationHandle(
    obj: SceneObject,
    geometry: SceneGeometry,
    scale: Float,
    presenter: DocumentHostPresenter,
) {
    val density = LocalDensity.current
    val targetPx = with(density) { HANDLE_TARGET.toPx() }
    val (handleX, handleY) = geometry.rotationHandlePoint(ROTATION_HANDLE_OFFSET_PT)

    Box(
        Modifier
            .offset {
                IntOffset(
                    (handleX.ptToPx(scale, this) - targetPx / 2f).roundToInt(),
                    (handleY.ptToPx(scale, this) - targetPx / 2f).roundToInt(),
                )
            }
            .size(HANDLE_TARGET)
            .pointerInput(obj.id, scale, geometry.rotation) {
                // The handle's page-space offset from the centre when the gesture begins. The pointer walks
                // from there and the angle is read off the vector, so the object does not snap the instant
                // the drag starts — and because the start vector already includes the current rotation, the
                // angle read from it *is* the absolute angle rather than a delta to add to itself.
                val startX = handleX - geometry.centerX
                val startY = handleY - geometry.centerY
                var vectorX = startX
                var vectorY = startY
                detectDragGestures(
                    onDragStart = {
                        vectorX = startX
                        vectorY = startY
                        presenter.act { beginManipulation(ActiveManipulation.Kind.ROTATE) }
                    },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        vectorX += dragAmount.x.toPagePoints(scale, this@pointerInput)
                        vectorY += dragAmount.y.toPagePoints(scale, this@pointerInput)
                        // atan2 measures from the +x axis; the handle sits on the object's -y axis, so 90° is
                        // added to make "no movement" read as the object's current angle.
                        val degrees = Math.toDegrees(atan2(vectorY, vectorX).toDouble()).toFloat() + 90f
                        presenter.act { updateRotation(degrees) }
                    },
                    onDragEnd = { presenter.act { endManipulation() } },
                    onDragCancel = { presenter.act { cancelManipulation() } },
                )
            }
            .semantics { contentDescription = "Rotate" },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(HANDLE_VISUAL)
                .background(MaterialTheme.colorScheme.tertiary),
        )
    }
}

/**
 * The modifier state a pointer press carried, reduced to the one question selection cares about.
 *
 * A tiny toolkit-independent value so the *decision* below is a pure function that can be unit-tested, rather
 * than something only observable by driving a real pointer on a real device.
 */
data class SelectionModifiers(val toggle: Boolean)

/** What a press on an already-drawn object should do. */
enum class DocumentClickAction {
    /** Ordinary click: fall through to the tap/second-tap rule (select, or enter content editing). */
    NORMAL,

    /** Modifier-click: add/remove this object from the multi-selection. */
    TOGGLE_SELECTION,
}

/**
 * Whether this press is a modifier-click that should toggle selection.
 *
 * Pure and separate from the pointer plumbing so the rule is testable. Two things it deliberately does **not**
 * do: it does not toggle when the canvas is not editable (a modifier-click while reading is still just a
 * click), and it never returns TOGGLE_SELECTION for an unmodified press on a selected object — that case
 * belongs to the second-tap-enters-text rule, and stealing it would make Ctrl-click the only way to type.
 */
fun documentClickDecision(
    modifiers: SelectionModifiers,
    alreadySelected: Boolean,
    editable: Boolean,
): DocumentClickAction = when {
    !modifiers.toggle -> DocumentClickAction.NORMAL
    !editable -> DocumentClickAction.NORMAL
    else -> DocumentClickAction.TOGGLE_SELECTION
}

/**
 * True when Compose's pointer modifier state carries the platform's "toggle this" modifier.
 *
 * Ctrl **or** Meta, because the platform convention differs and a user with an external keyboard brings the
 * habit of whichever platform they learned it on; accepting both costs nothing and refusing one makes the
 * feature simply absent for half of them.
 */
private fun PointerKeyboardModifiers.hasToggleModifier(): Boolean = isCtrlPressed || isMetaPressed

// ── Drawing ─────────────────────────────────────────────────────────────────────────────────────────

/** Page chrome: border, bleed/safe-area marks, margin guides, grid, custom guides and page state. */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawPageStructure(
    page: DocumentPageBlock,
    scale: Float,
    editing: Boolean,
    isActive: Boolean,
    isSelected: Boolean,
    chrome: DocumentChromeColors,
) {
    val geo = page.geometry
    val k = scale * DP_PER_POINT * density

    if (geo.border.isVisible) {
        val inset = geo.border.inset * k
        drawRect(
            color = Color(geo.border.color!!.toInt()),
            topLeft = Offset(inset, inset),
            size = androidx.compose.ui.geometry.Size(size.width - inset * 2f, size.height - inset * 2f),
            style = Stroke(width = geo.border.width * k),
        )
    }

    // Structure aids appear only where they can be acted on: in Original + Edit. Drawing them while reading
    // is clutter, and drawing them in Adaptive would imply a margin the adapted layout is not honouring.
    if (editing) {
        val guide = chrome.marginGuide
        val left = geo.margins.left * k
        val right = size.width - geo.margins.right * k
        val top = geo.margins.top * k
        val bottom = size.height - geo.margins.bottom * k
        drawLine(guide, Offset(left, 0f), Offset(left, size.height))
        drawLine(guide, Offset(right, 0f), Offset(right, size.height))
        drawLine(guide, Offset(0f, top), Offset(size.width, top))
        drawLine(guide, Offset(0f, bottom), Offset(size.width, bottom))

        // Bleed and safe area are print-production marks, so they are drawn distinctly from margins: mistaking
        // one for the other is exactly the error that gets content trimmed off a printed page. Their hues are
        // print convention and stay literal — green means safe area to every printer in the world, so
        // theme-following would destroy the meaning rather than modernise it. Only the widths change, from
        // raw pixels to dp.
        if (geo.bleed.safeArea > 0f) {
            val inset = geo.bleed.safeArea * k
            drawRect(
                color = Color(0xFF4CAF50).copy(alpha = 0.4f),
                topLeft = Offset(inset, inset),
                size = androidx.compose.ui.geometry.Size(size.width - inset * 2f, size.height - inset * 2f),
                style = Stroke(width = HAIRLINE.toPx()),
            )
        }
        if (geo.bleed.bleed > 0f) {
            val out = geo.bleed.bleed * k
            drawRect(
                color = Color(0xFFE91E63).copy(alpha = 0.4f),
                topLeft = Offset(-out, -out),
                size = androidx.compose.ui.geometry.Size(size.width + out * 2f, size.height + out * 2f),
                style = Stroke(width = HAIRLINE.toPx()),
            )
        }

        if (geo.grid.visible && geo.grid.spacing > 0f) {
            val step = geo.grid.spacing * k
            val grid = chrome.grid
            var x = step
            while (x < size.width) {
                drawLine(grid, Offset(x, 0f), Offset(x, size.height))
                x += step
            }
            var y = step
            while (y < size.height) {
                drawLine(grid, Offset(0f, y), Offset(size.width, y))
                y += step
            }
        }

        // Custom guides are the user's own lines, so they are drawn in their own colour rather than blending
        // into the grid they were placed against.
        val custom = Color(0xFF00BCD4).copy(alpha = 0.5f)
        geo.grid.verticalGuides.forEach { at ->
            drawLine(custom, Offset(at * k, 0f), Offset(at * k, size.height))
        }
        geo.grid.horizontalGuides.forEach { at ->
            drawLine(custom, Offset(0f, at * k), Offset(size.width, at * k))
        }

        for (index in 1 until geo.columns.count) {
            val x = geo.columnLeft(index) * k
            drawLine(chrome.columnDivider, Offset(x, top), Offset(x, bottom))
        }
    }

    // Explicitly selected pages are marked distinctly from the merely-active one, because "Selected pages" is
    // a Page Control scope and the user has to be able to see what it names.
    //
    // Both used to be literal hex, and the selected page used the *same* cyan as a user's custom guide — so a
    // selected page and a guide line were the same colour on the same canvas. They are now the accent and its
    // secondary, each carried to legibility against this page's own colour.
    if (isSelected) {
        drawRect(color = chrome.selection, style = Stroke(width = PRIMARY_EDGE.toPx()))
    } else if (isActive) {
        drawRect(color = chrome.activePage, style = Stroke(width = SELECTED_EDGE.toPx()))
    }
}

/**
 * Every chrome colour for one page, already measured against that page's own background.
 *
 * A data holder rather than eight parameters, and resolved in the composable rather than inside `DrawScope`,
 * for two separate reasons. The first is mechanical: `MaterialTheme.colorScheme` is only readable in a
 * `@Composable`, and a `DrawScope` is not one — the same reason [com.norfold.app.ui.editor.shape.ShapeCanvasOverlay]
 * resolves its palette up front and threads it down. The second is that these values depend on the *page*,
 * so a document whose pages are different colours needs a different answer per page, and a single
 * theme-resolved set computed once for the whole host would be wrong for all but one of them.
 *
 * Every field is derived from the theme — the selection tiers via [EditorChromeColors.legible] and the
 * structure aids via [EditorChromeColors.settled] — so the user's accent still drives the chrome on an
 * ordinary white page and is adjusted only as far as the page colour forces. `rememberDocumentChromeColors`
 * says why the two groups need different functions.
 */
private data class DocumentChromeColors(
    /** Every "this is the selection" state — primary object, secondary selection, selected page. */
    val selection: Color,
    val restingObject: Color,
    val badge: Color,
    val activePage: Color,
    val marginGuide: Color,
    val grid: Color,
    val columnDivider: Color,
)

/**
 * [DocumentChromeColors] for a page painted with [paint].
 *
 * The three selection tiers get [ContrastAnalysis.UI_COMPONENT] — WCAG 1.4.11's 3:1 for a non-text control —
 * because a selection outline *is* a control boundary: it is the feedback that says which object the next
 * command will act on. The structure aids ask for less. A margin guide at 3:1 competes with the text it is
 * measuring, so it targets 2.2:1, and the grid asks only 1.6:1 because a grid legible enough to read as
 * content is a grid that has stopped being a background. Those two are judgements about how loud a hint
 * should be; the 3:1 is a requirement.
 *
 * **That difference in kind is why the two groups call different functions.** [EditorChromeColors.legible] is
 * a floor — it returns its input untouched when the input already clears the target — which is right for a
 * requirement and wrong for a judgement. Passing `GRID_RATIO` to it did not produce a 1.6:1 grid: on the
 * white page `PageGeometry.effectiveBackgroundPaint` defaults to, `onSurface` already measures 17.7110:1, so
 * the grid, the margin guide and the column divider all came back as full-strength ink `#171725` and the three
 * constants above named one weight between them. Dark mode on the near-black page landed all three at
 * 15.0379:1. The structure aids now use [EditorChromeColors.settled], which clears the same target without
 * overshooting it: the grid solves to `#C9C9DD` (1.6301) on white and `#334073` (1.6265) on the near-black
 * page in dark mode, and across the eight page-by-mode cases every aid lands between 0.0028 and 0.0755 above
 * its target — with the guide always at least 0.56 louder than the grid, which under the floor it was not.
 * All of those figures are pinned in `ContrastAnalysisTest`. The selection tiers keep
 * [EditorChromeColors.legible], because 3:1 is a minimum and there is no such thing as a selection outline
 * seen too well.
 *
 * The resting edge derives from the page's own ink rather than from the accent: an unselected object is not
 * in an accent-worthy state, and tinting every object edge with the user's brand colour would make a page of
 * ordinary objects look like a page of selected ones.
 */
@Composable
private fun rememberDocumentChromeColors(paint: ColorPaint): DocumentChromeColors {
    val scheme = MaterialTheme.colorScheme
    val accent = scheme.primary
    val secondary = scheme.secondary
    val ink = scheme.onSurface
    return remember(paint, accent, secondary, ink) {
        val page = EditorChromeColors.pageBackdrop(paint)
        // One accent for every "this is the selection" state. Two hues for one concept would read as two
        // kinds of selection; weight is what separates the tiers, which is why RESTING/SELECTED/PRIMARY_EDGE
        // differ by whole dp rather than by alpha.
        val selectionAccent = EditorChromeColors.legible(accent, page)
        DocumentChromeColors(
            selection = selectionAccent,
            restingObject = EditorChromeColors.settled(ink, page, ChromeGeometry.RESTING_EDGE_RATIO),
            badge = EditorChromeColors.badgeInk(page),
            activePage = EditorChromeColors.legible(secondary, page),
            marginGuide = EditorChromeColors.settled(ink, page, ChromeGeometry.GUIDE_RATIO),
            grid = EditorChromeColors.settled(ink, page, ChromeGeometry.GRID_RATIO),
            columnDivider = EditorChromeColors.settled(ink, page, ChromeGeometry.GUIDE_RATIO),
        )
    }
}

/**
 * Object chrome: the selection outline, the primary marker and the lock badge.
 *
 * Every colour arrives in [chrome] rather than being written here, because none of them can be decided
 * without knowing the page colour — see [DocumentChromeColors]. The widths are dp converted at draw time:
 * a bare `Stroke(width = 3f)` inside a `DrawScope` is 3 **px**, which is a hairline on a 3× phone.
 */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawObjectChrome(
    obj: SceneObject,
    selected: Boolean,
    isPrimary: Boolean,
    chrome: DocumentChromeColors,
) {
    when {
        // The primary object is the one handles and single-target commands act on, so it reads differently
        // from the rest of a multi-selection rather than the user having to infer which one is which.
        isPrimary -> drawRect(color = chrome.selection, style = Stroke(width = PRIMARY_EDGE.toPx()))
        // Opacity is deliberately not how a secondary selection differs from the primary one: a translucent
        // outline's real ratio depends on the content beneath it, so the difference is carried by weight
        // against a colour that has been measured to be legible.
        selected -> drawRect(color = chrome.selection, style = Stroke(width = SELECTED_EDGE.toPx()))
        else -> drawRect(color = chrome.restingObject, style = Stroke(width = RESTING_EDGE.toPx()))
    }
    // A lock the user cannot see is a lock they will fight: the badge is why "this will not move" is legible
    // before the drag rather than after it.
    if (obj.lock != SceneLock.None) {
        val radius = LOCK_BADGE_RADIUS.toPx()
        drawCircle(
            color = chrome.badge,
            radius = radius,
            center = Offset(size.width - radius * 1.8f, radius * 1.8f),
        )
    }
}

// ── Units ───────────────────────────────────────────────────────────────────────────────────────────
//
// Three spaces meet in this file and confusing any two of them silently misplaces everything:
//
//  - **page points** — what the model stores (72 per inch);
//  - **dp** — what `size`/`height` take (160 per inch);
//  - **pixels** — what `Modifier.offset { }`, `pointerInput` deltas and `DrawScope` all speak.
//
// Every conversion goes through the three functions below, so there is exactly one place the factors
// live. Passing a dp value to `offset { }` compiles perfectly and draws everything at 1/density of its
// correct position, which is precisely the class of bug a single boundary exists to prevent.

/** Page points → dp at [scale]. For `size`/`height`, which take dp. */
private fun Float.ptToDp(scale: Float): Dp = (this * scale * DP_PER_POINT).dp

/** Page points → pixels at [scale]. For `offset { }` and `DrawScope`, which take pixels. */
private fun Float.ptToPx(scale: Float, density: Density): Float =
    this * scale * DP_PER_POINT * density.density

/**
 * Screen pixels → page points at [scale].
 *
 * Gestures arrive in pixels and the model stores points, so this conversion is the boundary between them.
 * Doing it here — once, in the composable that owns the gesture — is what keeps snapping, hit testing and
 * commit all resolving against the same numbers regardless of zoom or device density.
 */
private fun Float.toPagePoints(scale: Float, density: Density): Float =
    this / (scale * DP_PER_POINT * density.density)

/**
 * The snap guides responsible for the current snap.
 *
 * Drawn only while a manipulation is in flight, and only the lines that actually captured an edge: a guide
 * that lingers after the gesture is clutter, and a guide that appears without having moved anything is a lie.
 */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawSnapGuides(
    guides: List<SnapGuideEngine.Guide>,
    scale: Float,
    chrome: DocumentChromeColors,
) {
    val k = scale * DP_PER_POINT * density
    val width = SNAP_GUIDE_WIDTH.toPx()
    guides.forEach { guide ->
        // The four hues say *what* captured the edge — page, margin, another object, the grid — and a user who
        // has learned them reads the reason without reading a label, so they are kept literal. The grid case is
        // the exception: `Color.Gray` was invisible on a grey sheet, and "the grid" has no colour convention to
        // preserve, so it borrows the same measured guide colour the static grid lines use.
        val color = when (guide.kind) {
            SnapGuideEngine.Kind.PAGE_EDGE, SnapGuideEngine.Kind.PAGE_CENTER -> Color(0xFFE91E63)
            SnapGuideEngine.Kind.MARGIN -> Color(0xFF4CAF50)
            SnapGuideEngine.Kind.OBJECT_EDGE, SnapGuideEngine.Kind.OBJECT_CENTER -> Color(0xFF00BCD4)
            SnapGuideEngine.Kind.GRID -> chrome.marginGuide
        }
        val at = guide.position * k
        when (guide.orientation) {
            SnapGuideEngine.Orientation.VERTICAL ->
                drawLine(color, Offset(at, 0f), Offset(at, size.height), strokeWidth = width)
            SnapGuideEngine.Orientation.HORIZONTAL ->
                drawLine(color, Offset(0f, at), Offset(size.width, at), strokeWidth = width)
        }
    }
}

// ── Chrome ──────────────────────────────────────────────────────────────────────────────────────────

/**
 * The top bar: title, history, and the two view axes as independent controls.
 *
 * Two separate control groups rather than one four-way selector, because the axes are genuinely independent —
 * a single selector would make "read the exact printed layout" and "edit an adapted layout" awkward to reach,
 * and those are the two combinations a phone user actually spends time in.
 */
@Composable
private fun DocumentTopBar(
    state: DocumentHostUiState,
    title: String,
    onTitleChange: (String) -> Unit,
    onTitleFocusChange: (Boolean) -> Unit,
    onOpenSidebar: () -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onRepresentation: (LayoutRepresentation) -> Unit,
    onIntent: (WorkingIntent) -> Unit,
    onOpenPageControl: () -> Unit,
    onOpenPageList: () -> Unit,
    onEnterSelection: () -> Unit,
    onOpenGuide: (() -> Unit)?,
    onOpenHistory: (() -> Unit)?,
    onOpenRecovery: (() -> Unit)?,
) {
    val compact = state.formFactor.isPhone
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onOpenSidebar) { Text("Menu") }
            DocumentTitleField(
                title = title,
                onTitleChange = onTitleChange,
                onFocusChange = onTitleFocusChange,
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
            )
            if (!compact) {
                TextButton(onClick = onUndo, enabled = state.canUndo) { Text("Undo") }
                TextButton(onClick = onRedo, enabled = state.canRedo) { Text("Redo") }
                onOpenGuide?.let { GuideEntryButton("Document editor", it) }
                onOpenHistory?.let { HistoryEntryButton(it) }
                onOpenRecovery?.let { RecoveryEntryButton(it) }
            }
        }
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (compact) {
                IconButton(onClick = onUndo, enabled = state.canUndo) {
                    Icon(Icons.AutoMirrored.Outlined.Undo, contentDescription = "Undo")
                }
                IconButton(onClick = onRedo, enabled = state.canRedo) {
                    Icon(Icons.AutoMirrored.Outlined.Redo, contentDescription = "Redo")
                }
                onOpenGuide?.let { GuideEntryButton("Document editor", it) }
                onOpenHistory?.let { HistoryEntryButton(it) }
                onOpenRecovery?.let { RecoveryEntryButton(it) }
            }
            AssistChip(
                onClick = {
                    onRepresentation(
                        if (state.representation == LayoutRepresentation.ORIGINAL) {
                            LayoutRepresentation.ADAPTIVE
                        } else {
                            LayoutRepresentation.ORIGINAL
                        },
                    )
                },
                label = {
                    Text(if (state.representation == LayoutRepresentation.ORIGINAL) "Original" else "Adaptive")
                },
            )
            AssistChip(
                onClick = {
                    onIntent(if (state.intent == WorkingIntent.EDIT) WorkingIntent.READ else WorkingIntent.EDIT)
                },
                label = { Text(if (state.intent == WorkingIntent.EDIT) "Edit" else "Read") },
            )
            AssistChip(onClick = onOpenPageControl, label = { Text("Page setup") })
            AssistChip(onClick = onOpenPageList, label = { Text("Pages") })
            // Multi-select is a permanent, labelled control — long-press is the accelerator, never the only
            // way in, because a gesture nobody is told about is a feature nobody has.
            AssistChip(onClick = onEnterSelection, label = { Text("Select") })
        }
    }
}

/**
 * The document title, editable in place.
 *
 * The field is re-seeded when the *owner's* value changes — a rename from elsewhere, or a process recreation
 * restoring the saved title — but not merely because it differs from the local text. Comparing against the
 * last observed owner value distinguishes "someone else changed it" from "the owner has not caught up with
 * this keystroke yet"; re-seeding on the latter would delete characters as they are typed.
 *
 * `onTitleChange` reaches the existing `FlowSaveCoordinator` through the route the host was already given, so
 * there is exactly one authority for the title rather than a second one here that could disagree with it.
 */
@Composable
private fun DocumentTitleField(
    title: String,
    onTitleChange: (String) -> Unit,
    onFocusChange: (Boolean) -> Unit,
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
                "Untitled document",
                style = MaterialTheme.typography.titleMedium,
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
            // Backspace in the title must delete a character, not the selected object. Reporting focus is
            // what tells the root key handler to keep its hands off.
            modifier = Modifier.fillMaxWidth().onFocusChanged { onFocusChange(it.isFocused) },
        )
    }
}

/**
 * Zoom controls for Original View: discrete steps, Fit width and Fit page.
 *
 * Every one of these changes only the *draw-time* scale. Stored [com.norfold.app.domain.PageGeometry] is
 * never touched by zooming — a page is 595 × 842 points whether it is being viewed at 25% or 400%, and
 * writing a view scale back into geometry would silently resize the sheet the user is composing against.
 */
@Composable
private fun ZoomBar(presenter: DocumentHostPresenter) {
    val state = presenter.state
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "${(state.zoom * 100f).roundToInt()}%",
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        AssistChip(onClick = { presenter.act { setZoom(state.zoom / 1.25f) } }, label = { Text("−") })
        AssistChip(onClick = { presenter.act { setZoom(state.zoom * 1.25f) } }, label = { Text("+") })
        AssistChip(onClick = { presenter.act { setZoom(1f) } }, label = { Text("100%") })
        AssistChip(onClick = { presenter.act { fitWidth() } }, label = { Text("Fit width") })
        AssistChip(onClick = { presenter.act { fitPage() } }, label = { Text("Fit page") })
        if (state.panX != 0f) {
            AssistChip(onClick = { presenter.act { setPan(0f) } }, label = { Text("Centre") })
        }
    }
}

/** The multi-select bar: how many objects are selected, and the two ways out of the mode. */
@Composable
private fun SelectionBar(count: Int, onCancel: () -> Unit, onDone: () -> Unit) {
    Surface(tonalElevation = 3.dp, modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onCancel) { Text("Cancel") }
            Text(
                text = if (count == 1) "1 object selected" else "$count objects selected",
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
            )
            TextButton(onClick = onDone) { Text("Done") }
        }
    }
}

/**
 * Exact geometry entry for the primary selected object.
 *
 * Each field commits as **one** transaction, through the same controller call a drag ends with — so "typed
 * 120" and "dragged to 120" are indistinguishable in history and in the saved document. A field whose lock
 * forbids it is read-only and says which lock, because a number that silently refuses to change is the most
 * confusing possible refusal.
 */
@Composable
private fun GeometryInspectorBar(
    obj: SceneObject,
    presenter: DocumentHostPresenter,
    onFocusChange: (Boolean) -> Unit,
    onNotice: (String) -> Unit,
) {
    // One flag for the whole bar rather than one per field: what the key router needs to know is "does some
    // number field own the keyboard", and five independent booleans would report false as focus moved between
    // two of them.
    val focused = remember { mutableStateOf(setOf<GeometryInspector.Field>()) }

    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GeometryInspector.Field.entries.forEach { field ->
            GeometryField(
                field = field,
                obj = obj,
                presenter = presenter,
                onFocusChange = { hasFocus ->
                    focused.value = if (hasFocus) focused.value + field else focused.value - field
                    onFocusChange(focused.value.isNotEmpty())
                },
                onNotice = onNotice,
            )
        }
    }
}

/**
 * One numeric geometry field: a local draft, committed once.
 *
 * **Why a draft and not commit-as-you-type.** The previous version committed every intermediate value that
 * happened to parse, so typing `120` moved the object to 1, then to 12, then to 120 — three History
 * transactions for one edit, and three Undos to get back. Worse, the object visibly jumped through positions
 * the user was only passing through on the way to the number they meant.
 *
 * The lifecycle is the one a human already expects from a number field:
 *
 *  - typing edits a **local draft** and touches nothing else;
 *  - **Done/Enter** commits it, as a single transaction;
 *  - **focus loss** commits it too, because tapping away from a field you have filled in means you meant it;
 *  - an **invalid draft never commits** — it is dropped and the field re-seeded from the object, so a typo
 *    cannot leave the geometry somewhere the user did not choose;
 *  - the field re-seeds from the object when it is **not dirty**, so a drag or an undo is reflected live, and
 *    an in-progress entry is never overwritten mid-keystroke.
 *
 * Keying the draft on the object id is what stops a pending value from being applied to the *next* object: a
 * selection change rebuilds this state from the newly selected object rather than carrying the draft across.
 */
@Composable
private fun GeometryField(
    field: GeometryInspector.Field,
    obj: SceneObject,
    presenter: DocumentHostPresenter,
    onFocusChange: (Boolean) -> Unit,
    onNotice: (String) -> Unit,
) {
    val stored = GeometryInspector.display(field.valueOf(obj.geometry))
    var text by remember(obj.id, field) { mutableStateOf(stored) }
    // Dirty means "the user has typed something not yet committed". It is what makes re-seeding safe: a value
    // arriving from a drag or an undo refreshes a clean field and is ignored by a dirty one.
    var dirty by remember(obj.id, field) { mutableStateOf(false) }
    if (!dirty && text != stored) text = stored
    val locked = field.isLockedFor(obj)

    // Committing is one function because it has three callers — Done, focus loss, and the caller's own
    // teardown — and three copies of "parse, commit or revert" is how one of them ends up committing an
    // invalid draft.
    fun commit() {
        if (!dirty) return
        dirty = false
        if (GeometryInspector.parse(field, text) == null) {
            // Rejected: the object keeps its value and the field stops showing a number that was never real.
            onNotice("'$text' is not a valid ${field.label}")
            text = stored
            return
        }
        presenter.act { applyGeometryField(field, text) }
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            "${field.label} ",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Surface(
            tonalElevation = 1.dp,
            modifier = Modifier.width(64.dp),
        ) {
            BasicTextField(
                value = text,
                onValueChange = { next ->
                    if (locked) {
                        onNotice(field.lockReasonFor(obj) ?: "This value cannot be changed")
                        return@BasicTextField
                    }
                    text = next
                    dirty = true
                },
                readOnly = locked,
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Number,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(onDone = { commit() }),
                textStyle = MaterialTheme.typography.labelMedium.copy(
                    color = if (locked) {
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    } else {
                        LocalContentColor.current
                    },
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                modifier = Modifier
                    .padding(horizontal = 6.dp, vertical = 4.dp)
                    .onFocusChanged { focusState ->
                        onFocusChange(focusState.isFocused)
                        // Leaving a field you have filled in means you meant the value; leaving one you have
                        // not touched must not commit the number that was already there.
                        if (!focusState.isFocused) commit()
                    },
            )
        }
    }
}

/** The one-shot explanation of an automatic view transition or a refused action. Dismissible, never blocking. */
@Composable
private fun NoticeRow(message: String, onDismiss: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(message, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
        TextButton(onClick = onDismiss) { Text("OK") }
    }
}

/**
 * The compact context dock — the touch-first action row for whatever is selected.
 *
 * A **scrolling** row of every action the selection offers, not a truncated one: the previous version drew
 * only the first six, which silently hid Unlock and Delete — the two most consequential entries and the two
 * a user is most likely to go looking for. A disabled entry stays visible and says why when tapped, because
 * a control that explains itself is more useful than one that vanishes (§4.2).
 *
 * Still deliberately not a desktop ribbon: the full page catalogue lives in Page Control, and squeezing that
 * onto a phone is what makes a document editor unusable there.
 */
@Composable
private fun DocumentDock(
    items: List<DocumentCommandItem>,
    onInvoke: (DocumentCommandItem) -> Unit,
    onOpenInspector: (() -> Unit)? = null,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        items.forEach { item ->
            AssistChip(
                onClick = { onInvoke(item) },
                enabled = item.enabled,
                label = { Text(item.label, style = MaterialTheme.typography.labelSmall) },
            )
        }
        onOpenInspector?.let { open ->
            AssistChip(onClick = open, label = { Text("More", style = MaterialTheme.typography.labelSmall) })
        }
    }
}

@Composable
private fun DocumentStyleDock(state: DocumentHostUiState, invoke: (String) -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (state.editorStyle.textTargetCount > 0) {
            AssistChip({ invoke(EditorStyleCommandIds.NAMED_STYLES) }, label = { Text("Style") })
            AssistChip({ invoke(EditorStyleCommandIds.FONT_BROWSER) }, label = { Text("Font") })
            AssistChip({ invoke(EditorStyleCommandIds.TEXT_COLOR) }, label = { Text("Color") })
            AssistChip({ invoke(EditorStyleCommandIds.HIGHLIGHT) }, label = { Text("Highlight") })
        }
        if (state.editorStyle.shapeTargetCount > 0) {
            AssistChip({ invoke(EditorStyleCommandIds.FILL) }, label = { Text("Fill") })
            AssistChip({ invoke(EditorStyleCommandIds.STROKE) }, label = { Text("Stroke") })
            AssistChip({ invoke(EditorStyleCommandIds.NAMED_STYLES) }, label = { Text("Object style") })
        }
        if (state.editorStyle.textTargetCount == 0 && state.editorStyle.shapeTargetCount == 0 &&
            state.editorStyle.pageTargetCount > 0
        ) {
            AssistChip({ invoke(EditorStyleCommandIds.PAGE_BACKGROUND) }, label = { Text("Page color") })
        }
    }
}

/** Phone dock: one scrollable action rail instead of two stacked toolbars obscuring the page. */
@Composable
private fun DocumentCompactDock(
    items: List<DocumentCommandItem>,
    state: DocumentHostUiState,
    onInvoke: (DocumentCommandItem) -> Unit,
    invokeStyle: (String) -> Unit,
    onOpenInspector: (() -> Unit)? = null,
) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Leads the rail on a phone rather than trailing it. The rail scrolls, so a trailing entry on a
        // sixteen-chip row is off-screen — which is the reachability problem the inspector exists to fix, and
        // putting its own entry point behind the same scroll would reintroduce it.
        onOpenInspector?.let { open ->
            AssistChip(onClick = open, label = { Text("More", style = MaterialTheme.typography.labelSmall) })
        }
        items.forEach { item ->
            AssistChip(
                onClick = { onInvoke(item) },
                enabled = item.enabled,
                label = { Text(item.label, style = MaterialTheme.typography.labelSmall) },
            )
        }
        if (state.editorStyle.textTargetCount > 0) {
            AssistChip({ invokeStyle(EditorStyleCommandIds.NAMED_STYLES) }, label = { Text("Style") })
            AssistChip({ invokeStyle(EditorStyleCommandIds.FONT_BROWSER) }, label = { Text("Font") })
            AssistChip({ invokeStyle(EditorStyleCommandIds.TEXT_COLOR) }, label = { Text("Color") })
            AssistChip({ invokeStyle(EditorStyleCommandIds.HIGHLIGHT) }, label = { Text("Highlight") })
        }
        if (state.editorStyle.shapeTargetCount > 0) {
            AssistChip({ invokeStyle(EditorStyleCommandIds.FILL) }, label = { Text("Fill") })
            AssistChip({ invokeStyle(EditorStyleCommandIds.STROKE) }, label = { Text("Stroke") })
            AssistChip({ invokeStyle(EditorStyleCommandIds.NAMED_STYLES) }, label = { Text("Object style") })
        }
        if (state.editorStyle.textTargetCount == 0 && state.editorStyle.shapeTargetCount == 0 &&
            state.editorStyle.pageTargetCount > 0
        ) {
            AssistChip({ invokeStyle(EditorStyleCommandIds.PAGE_BACKGROUND) }, label = { Text("Page color") })
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DocumentEditorStyleSurface(
    surfaceId: String,
    state: DocumentHostUiState,
    presenter: DocumentHostPresenter,
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
        com.norfold.app.editor.presentation.PresentationMode.PHONE_SHEET,
        com.norfold.app.editor.presentation.PresentationMode.PHONE_POPOVER -> {
            val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)
            ModalBottomSheet(onDismissRequest = dismiss, sheetState = sheetState) {
                EditorStyleSurface(surfaceId, state.editorStyle, rows, sections, actions)
            }
        }
        com.norfold.app.editor.presentation.PresentationMode.TABLET_PANE,
        com.norfold.app.editor.presentation.PresentationMode.EXPANDED_THREE_PANEL -> Box(Modifier.fillMaxSize()) {
            Surface(
                Modifier.align(Alignment.CenterEnd).width(420.dp).padding(16.dp),
                shape = RoundedCornerShape(24.dp),
                tonalElevation = 8.dp,
            ) { EditorStyleSurface(surfaceId, state.editorStyle, rows, sections, actions) }
        }
    }
}

/**
 * The page list: where "Selected pages" is actually populated, and where pages are reordered.
 *
 * That Page Control scope previously resolved to nothing the user could ever fill, so choosing it silently
 * fell back to the current page and looked broken. A visible list with a checkbox per page is what turns it
 * into a real choice.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PageListSheet(
    state: DocumentHostUiState,
    onDismiss: () -> Unit,
    onToggle: (String) -> Unit,
    onClear: () -> Unit,
    onSelect: (String) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Pages", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                if (state.selectedPageIds.isNotEmpty()) {
                    TextButton(onClick = onClear) { Text("Clear (${state.selectedPageIds.size})") }
                }
            }
            Column(Modifier.verticalScroll(rememberScrollState())) {
                state.pages.forEachIndexed { index, page ->
                    val selected = page.id in state.selectedPageIds
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        FilterChip(
                            selected = selected,
                            onClick = { onToggle(page.id) },
                            label = { Text(if (selected) "Selected" else "Select") },
                        )
                        TextButton(onClick = { onSelect(page.id) }, modifier = Modifier.weight(1f)) {
                            Column(Modifier.fillMaxWidth()) {
                                Text(
                                    text = "Page ${state.pageNumberLabels.getOrNull(index) ?: (index + 1)}" +
                                        if (page.id == state.activePageId) " · current" else "",
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                Text(
                                    text = "${page.objects.size} object" +
                                        "${if (page.objects.size == 1) "" else "s"} · " +
                                        "${page.geometry.width.roundToInt()}×" +
                                        "${page.geometry.height.roundToInt()} pt" +
                                        if (page.geometry.structureLocked) " · locked" else "",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
                Box(Modifier.height(24.dp))
            }
        }
    }
}

/**
 * Page Control: every page-setup command, as commands, at a scope the user chooses.
 *
 * The sheet is a projection of the command definitions rather than a panel of its own callbacks, which is why
 * a control unavailable at the chosen scope is greyed out here with the same reason the dock would give, and
 * why a control already applied there reads as on. "Apply settings to…" changes what the rows *answer about*,
 * not just what a tap will do — picking Whole document immediately re-resolves every row against every page.
 */
/**
 * Document's selection, presented through the one object inspector.
 *
 * The counterpart to Flow's bridge, and the same thinness: [DocumentInspector] decides the arrangement where a
 * unit test can fail on it, and this supplies Document's context — what the selected object is called, and how
 * to run a command.
 *
 * This surface is new. Document's object actions previously existed only as chips on a dock that scrolls
 * sideways — up to sixteen of them, undifferentiated — or as keyboard chords, which a phone does not have. So
 * for Document the inspector is not a reorganisation of an existing sheet but the first grouped view of what a
 * selected object can do.
 *
 * No edit route: unlike Flow, Document opens an object's own editor by tapping the object on the page rather
 * than through a dock button, so there is no host route for the inspector to offer and inventing one here
 * would be a second way to do something that already works.
 *
 * @param onCancel the sheet closed without acting — swipe-away or Back.
 * @param onInvoke a row was tapped. The caller both runs the command and closes the surface, because
 *   Document's `invoke` does not dispatch `COMMAND_EXECUTED` itself the way Flow's does.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DocumentObjectInspectorSheet(
    state: DocumentHostUiState,
    onCancel: () -> Unit,
    onInvoke: (DocumentCommandItem) -> Unit,
) {
    val selected = state.primaryObject
    val model = DocumentInspector.build(
        title = selected?.kind?.let(DocumentInspector::titleFor) ?: DocumentInspector.OBJECT_TITLE,
        items = state.dock,
    )
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onCancel, sheetState = sheetState) {
        ObjectInspectorContent(
            model = model,
            // Mapped back to the editor's own item rather than invoked from the action: the action is a view of
            // the command, and the runtime's enablement and failure reason live on the item.
            onAction = { action -> state.dock.firstOrNull { it.id == action.id }?.let(onInvoke) },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PageControlSheet(
    state: DocumentHostUiState,
    onDismiss: () -> Unit,
    onScope: (PageScope) -> Unit,
    onInvoke: (String) -> Unit,
    onApplyValue: (String, String) -> Unit,
    onClearNewPageTemplate: () -> Unit,
    onOpenGuide: (() -> Unit)? = null,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Page setup", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                onOpenGuide?.let { GuideEntryButton("Page setup", it) }
            }
            Text(
                text = pageScopeSummary(state),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Text(
                text = "Apply settings to",
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
            )
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                PageScope.ALL.forEach { scope ->
                    FilterChip(
                        selected = scope == state.pageScope,
                        onClick = { onScope(scope) },
                        label = { Text(scope.label, style = MaterialTheme.typography.labelSmall) },
                    )
                }
            }

            // "New pages by default" is durable, so it needs a visible way *off* as well as on — otherwise a
            // default set once could never be returned to inherit-from-the-previous-page.
            if (state.pageScope == PageScope.NewPagesByDefault && state.newPageTemplate != null) {
                TextButton(onClick = onClearNewPageTemplate) {
                    Text("Use the previous page's setup instead")
                }
            }

            HorizontalDivider(Modifier.padding(vertical = 8.dp))

            Column(Modifier.verticalScroll(rememberScrollState())) {
                // Grouped by the control's own category, so the sheet's structure comes from the catalogue
                // rather than a second hand-maintained ordering that could disagree with it. Fixed controls
                // and value fields for one group appear together, because a user thinking about margins wants
                // both the presets and the exact numbers in one place.
                val fixed = state.pageControls.groupBy { it.category }
                val values = state.pageValueControls.groupBy { it.category }
                (fixed.keys + values.keys).forEach { group ->
                    Text(
                        text = group,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    fixed[group].orEmpty().forEach { item -> PageControlRow(item) { onInvoke(item.id) } }
                    values[group].orEmpty().forEach { item ->
                        PageValueRow(item) { raw -> onApplyValue(item.id, raw) }
                    }
                }
                Box(Modifier.height(24.dp))
            }
        }
    }
}

/** What the chosen scope actually names right now — the promise the rows below are answering against. */
private fun pageScopeSummary(state: DocumentHostUiState): String = when (state.pageScope) {
    PageScope.NewPagesByDefault ->
        if (state.newPageTemplate == null) {
            "New pages currently copy the page they follow"
        } else {
            "New pages use a saved default"
        }
    else -> {
        val n = state.pageScopeCount
        "${state.pages.size} page${if (state.pages.size == 1) "" else "s"} · " +
            "changing $n page${if (n == 1) "" else "s"}"
    }
}

/**
 * One Page Control row.
 *
 * The disabled reason is shown rather than the row being hidden, because "why can I not change the margins"
 * is the question Page Control exists to answer — and the answer is almost always the structure lock, which
 * is directly above it in the same sheet.
 */
@Composable
private fun PageControlRow(item: DocumentCommandItem, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        enabled = item.enabled,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = item.label,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (item.active) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                // Mixed is stated, never collapsed to on or off: with a multi-page scope the honest answer
                // to "is this on" is often "on some of them", and picking either would be a lie.
                if (item.mixed) {
                    Text("Mixed", style = MaterialTheme.typography.labelSmall)
                } else if (item.active) {
                    Text("On", style = MaterialTheme.typography.labelSmall)
                }
            }
            item.disabledReason?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * One value-driven Page Control row: a field, and an Apply that commits it.
 *
 * Apply is explicit rather than committing per keystroke, because these are page-*structure* changes: a
 * half-typed width would resize every page in the scope on the way to the number the user meant. The geometry
 * inspector uses the same draft-then-commit shape for the same reason, differing only in that Done and focus
 * loss stand in for a visible Apply where there is no room for a button.
 *
 * These fields are inside the sheet, which is a sibling of the editor column rather than a descendant, so the
 * root's preview key handler never sees their keystrokes — Backspace here cannot reach the canvas bindings.
 */
@Composable
private fun PageValueRow(item: DocumentPageValueItem, onApply: (String) -> Unit) {
    var text by remember(item.id, item.value) { mutableStateOf(item.value) }
    Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = item.label,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            Surface(tonalElevation = 1.dp, modifier = Modifier.width(96.dp)) {
                BasicTextField(
                    value = text,
                    onValueChange = { text = it },
                    readOnly = !item.enabled,
                    singleLine = true,
                    textStyle = MaterialTheme.typography.labelMedium.copy(color = LocalContentColor.current),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 6.dp),
                )
            }
            TextButton(onClick = { onApply(text) }, enabled = item.enabled) { Text("Apply") }
        }
        // Mixed is stated here too: an empty field across a scope whose pages disagree is not "no value", and
        // saying so is what stops the user pressing Apply and flattening them all to one.
        if (item.mixed) {
            Text(
                "Mixed across the selected pages",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        item.disabledReason?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
