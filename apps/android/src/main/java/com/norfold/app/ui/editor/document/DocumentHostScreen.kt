package com.norfold.app.ui.editor.document

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.norfold.app.domain.DocumentPageBlock
import com.norfold.app.domain.ResizeCorner
import com.norfold.app.domain.SceneGeometry
import com.norfold.app.domain.SceneLock
import com.norfold.app.domain.SceneObject
import com.norfold.app.editor.command.CommandSurface
import com.norfold.app.editor.document.ActiveManipulation
import com.norfold.app.editor.document.DocumentBackResult
import com.norfold.app.editor.document.DocumentCommandItem
import com.norfold.app.editor.document.DocumentHostUiState
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
import kotlin.math.atan2
import kotlin.math.roundToInt

/**
 * How many dp one page point occupies at 100% zoom. A point is 1/72", a dp is 1/160", so a page drawn at this
 * factor is physically the size it will print.
 */
private const val DP_PER_POINT = 160f / 72f

/** Visual size of a manipulation handle, and the invisible target around it that a finger can actually hit. */
private val HANDLE_VISUAL = 14.dp
private val HANDLE_TARGET = 44.dp

/** How far above the object's top edge the rotation handle sits, in page points. */
private const val ROTATION_HANDLE_OFFSET_PT = 28f

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
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state = presenter.state
    var notice by remember { mutableStateOf<String?>(null) }
    val configuration = LocalConfiguration.current

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
    // → selection → editor. Only the last of those is navigation.
    BackHandler {
        when (presenter.act { back() }) {
            DocumentBackResult.LeftEditor -> onExit()
            else -> Unit
        }
    }

    Column(
        modifier
            .fillMaxSize()
            // Unbound keys fall through untouched, so typing into an object's text is never intercepted.
            .onPreviewKeyEvent { event ->
                event.toDocumentShortcut()?.let { presenter.act { handleShortcut(it) } } ?: false
            },
    ) {
        DocumentTopBar(
            state = state,
            title = title,
            onTitleChange = onTitleChange,
            onOpenSidebar = onOpenSidebar,
            onUndo = { presenter.act { undo() } },
            onRedo = { presenter.act { redo() } },
            onRepresentation = { presenter.act { setRepresentation(it) } },
            onIntent = { presenter.act { setIntent(it) } },
            onOpenPageControl = { presenter.act { openSurface(DocumentSurfaces.PAGE_CONTROL) } },
            onOpenPageList = { presenter.act { openSurface(DocumentSurfaces.PAGE_LIST) } },
            onEnterSelection = { presenter.act { enterSelectionMode(state.primaryObjectId) } },
        )
        HorizontalDivider()

        notice?.let { message -> NoticeRow(message) { notice = null } }

        Box(Modifier.weight(1f)) {
            DocumentPageStack(presenter, Modifier.fillMaxSize())
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
                GeometryInspectorBar(primary, presenter) { notice = it }
            }
        }

        if (state.dock.isNotEmpty()) {
            HorizontalDivider()
            DocumentDock(state.dock) { item ->
                if (!item.enabled) {
                    notice = item.disabledReason ?: "'${item.label}' is not available here"
                } else {
                    presenter.act { invoke(item.id, CommandSurface.DOCK) }
                }
            }
        }
    }

    when (state.activeSurfaceId) {
        DocumentSurfaces.PAGE_CONTROL -> PageControlSheet(
            state = state,
            onDismiss = { presenter.act { dismiss(DismissTrigger.OUTSIDE_TAP) } },
            onScope = { presenter.act { setPageScope(it) } },
            onInvoke = { id -> presenter.act { invokePageControl(id, state.pageScope) } },
            onApplyValue = { id, raw -> presenter.act { invokePageValue(id, state.pageScope, raw) } },
            onClearNewPageTemplate = { presenter.act { clearNewPageTemplate() } },
        )
        DocumentSurfaces.PAGE_LIST -> PageListSheet(
            state = state,
            onDismiss = { presenter.act { dismiss(DismissTrigger.OUTSIDE_TAP) } },
            onToggle = { id -> presenter.act { togglePageSelection(id) } },
            onClear = { presenter.act { clearPageSelection() } },
            onSelect = { id -> presenter.act { selectPage(id) } },
        )
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
private fun DocumentPageStack(presenter: DocumentHostPresenter, modifier: Modifier = Modifier) {
    val state = presenter.state
    val scroll = rememberScrollState()
    val density = LocalDensity.current

    BoxWithConstraints(modifier) {
        // maxHeight/maxWidth are already dp, so only the pt↔dp factor applies — dividing by density as well
        // would report a viewport a few times too small and virtualize away pages that are on screen.
        val viewportHeightPt = maxHeight.value / DP_PER_POINT
        val viewportWidthPt = maxWidth.value / DP_PER_POINT

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
        val window = remember(state.revision, scrollOffsetPt, viewportHeightPt, scale) {
            presenter.runtime.pageWindow(scrollOffsetPt, viewportHeightPt / scale)
        }

        Box(
            Modifier
                .fillMaxWidth()
                .verticalScroll(scroll)
                .background(MaterialTheme.colorScheme.surfaceVariant)
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
                )
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

    Box(
        Modifier
            .offset {
                IntOffset(
                    state.panX.ptToPx(scale, this).roundToInt(),
                    topPt.ptToPx(scale, this).roundToInt(),
                )
            }
            .size(page.geometry.width.ptToDp(scale), page.geometry.height.ptToDp(scale))
            .background(page.geometry.background?.let { Color(it.toInt()) } ?: Color.White)
            .drawBehind {
                drawPageStructure(page, scale, editing, isActive, isSelected)
                // Guides belong to the page being manipulated, not to every page on screen: the positions are
                // page-relative, so drawing them on a neighbour would put the line in the wrong place.
                val manipulation = state.manipulation
                if (manipulation != null && manipulation.objectIds.any { page.objectById(it) != null }) {
                    drawSnapGuides(manipulation.guides, scale)
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
        // Header, footer and page number are page *metadata*, drawn beneath the objects so content placed
        // over a running head still wins. Reserved-but-invisible bands were the defect: the geometry
        // reserved space the user could not see, so their content sat mysteriously low on the sheet.
        PageMetadata(page, pageIndex, numberLabel, scale)

        // Back-to-front: the front-most object is drawn last, so it is the one that overlaps the others.
        visible.sortedBy { it.z }.forEach { obj ->
            SceneObjectBox(obj, page, scale, presenter)
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
            Text(header.headerText, style = MaterialTheme.typography.labelSmall, color = muted)
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
            Text(header.footerText, style = MaterialTheme.typography.labelSmall, color = muted)
        }
    }

    if (geometry.numbering.showNumber && numberLabel != null) {
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .offset { IntOffset(0, -(geometry.margins.bottom / 2f).ptToPx(scale, this).roundToInt()) },
        ) {
            Text(numberLabel, style = MaterialTheme.typography.labelSmall, color = muted)
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

    Box(
        Modifier
            .offset {
                IntOffset(
                    geometry.x.ptToPx(scale, this).roundToInt(),
                    geometry.y.ptToPx(scale, this).roundToInt(),
                )
            }
            .size(geometry.width.ptToDp(scale), geometry.height.ptToDp(scale))
            .drawBehind {
                // Rotation is applied to the *drawing*, about the box's centre, which is the same centre
                // `containsRotated` rotates a tap about. A bare `Modifier.rotate()` would rotate the layout
                // node — including its touch area — leaving hit testing and the model to disagree about
                // where the object is.
                rotate(geometry.rotation) { drawObjectChrome(obj, selected, isPrimary) }
            }
            .then(
                if (state.selectionMode) {
                    Modifier.pointerInput(obj.id, state.revision) {
                        detectTapGestures { presenter.act { toggleObjectSelection(obj.id) } }
                    }
                } else if (editingThis) {
                    // The content owns every gesture inside it while its text is being edited.
                    Modifier
                } else {
                    Modifier
                        .pointerInput(obj.id, selected, state.revision) {
                            detectTapGestures(
                                onTap = {
                                    presenter.act {
                                        // First tap selects; a second, deliberate tap on an already-selected
                                        // text frame enters its content. Entering on the first tap would make
                                        // every attempt to move a frame drop a caret into it instead.
                                        if (selected && editable) beginContentEdit(obj.id)
                                        else selectObjects(listOf(obj.id))
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
                                    totalX += dragAmount.x.toPagePoints(scale, this@pointerInput)
                                    totalY += dragAmount.y.toPagePoints(scale, this@pointerInput)
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
        SceneObjectContent(
            block = obj.content,
            objectId = obj.id,
            editing = editingThis,
            callbacks = remember(obj.id, state.revision) {
                SceneContentCallbacks(
                    onEdit = { edit ->
                        presenter.act { editObjectText(edit.objectId, edit.itemId, edit.before, edit.after) }
                    },
                    onToggleTodo = { itemId -> presenter.act { toggleObjectTodo(obj.id, itemId) } },
                )
            },
            modifier = Modifier.fillMaxWidth().padding(4.dp),
        )
    }

    // Handles are drawn *outside* the object box so a 44dp touch target is never clipped by a small object —
    // an 8pt-tall shape would otherwise have handles a finger cannot land on. Only the primary object gets
    // them: a secondary selected object drawing its own corner targets would promise an individual resize
    // that the group operation does not perform.
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
 * One corner resize handle.
 *
 * All four corners exist, and each anchors the corner **opposite** it: dragging the top-left moves x and y as
 * well as width and height, which is what "resize from this corner" means to a human. The one-corner version
 * this replaces could only ever grow an object down and to the right, so re-sizing against a fixed bottom
 * edge required moving the object afterwards and doing the arithmetic by hand.
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
    val (cornerX, cornerY) = corner.positionIn(geometry)

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
            .pointerInput(obj.id, corner, scale) {
                // The corner's page position, accumulated across the gesture from where it started.
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
 * The rotation handle, above the object's top edge.
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

    Box(
        Modifier
            .offset {
                IntOffset(
                    (geometry.centerX.ptToPx(scale, this) - targetPx / 2f).roundToInt(),
                    ((geometry.y - ROTATION_HANDLE_OFFSET_PT).ptToPx(scale, this) - targetPx / 2f).roundToInt(),
                )
            }
            .size(HANDLE_TARGET)
            .pointerInput(obj.id, scale) {
                // Where the handle sits relative to the centre when the gesture begins. The pointer walks from
                // there, and the angle is read off the vector — so the object does not snap a quarter-turn the
                // instant the drag starts.
                var vectorX = 0f
                var vectorY = -(ROTATION_HANDLE_OFFSET_PT + geometry.height / 2f)
                detectDragGestures(
                    onDragStart = {
                        vectorX = 0f
                        vectorY = -(ROTATION_HANDLE_OFFSET_PT + geometry.height / 2f)
                        presenter.act { beginManipulation(ActiveManipulation.Kind.ROTATE) }
                    },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        vectorX += dragAmount.x.toPagePoints(scale, this@pointerInput)
                        vectorY += dragAmount.y.toPagePoints(scale, this@pointerInput)
                        // atan2 measures from the +x axis; the handle starts on the -y axis, so 90° is added
                        // to make "no movement" read as the object's current angle rather than a quarter-turn.
                        val degrees = Math.toDegrees(atan2(vectorY, vectorX).toDouble()).toFloat() + 90f
                        presenter.act { updateRotation(geometry.rotation + degrees) }
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

// ── Drawing ─────────────────────────────────────────────────────────────────────────────────────────

/** Page chrome: border, bleed/safe-area marks, margin guides, grid, custom guides and page state. */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawPageStructure(
    page: DocumentPageBlock,
    scale: Float,
    editing: Boolean,
    isActive: Boolean,
    isSelected: Boolean,
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
        val guide = Color.Gray.copy(alpha = 0.35f)
        val left = geo.margins.left * k
        val right = size.width - geo.margins.right * k
        val top = geo.margins.top * k
        val bottom = size.height - geo.margins.bottom * k
        drawLine(guide, Offset(left, 0f), Offset(left, size.height))
        drawLine(guide, Offset(right, 0f), Offset(right, size.height))
        drawLine(guide, Offset(0f, top), Offset(size.width, top))
        drawLine(guide, Offset(0f, bottom), Offset(size.width, bottom))

        // Bleed and safe area are print-production marks, so they are drawn distinctly from margins: mistaking
        // one for the other is exactly the error that gets content trimmed off a printed page.
        if (geo.bleed.safeArea > 0f) {
            val inset = geo.bleed.safeArea * k
            drawRect(
                color = Color(0xFF4CAF50).copy(alpha = 0.4f),
                topLeft = Offset(inset, inset),
                size = androidx.compose.ui.geometry.Size(size.width - inset * 2f, size.height - inset * 2f),
                style = Stroke(width = 1f),
            )
        }
        if (geo.bleed.bleed > 0f) {
            val out = geo.bleed.bleed * k
            drawRect(
                color = Color(0xFFE91E63).copy(alpha = 0.4f),
                topLeft = Offset(-out, -out),
                size = androidx.compose.ui.geometry.Size(size.width + out * 2f, size.height + out * 2f),
                style = Stroke(width = 1f),
            )
        }

        if (geo.grid.visible && geo.grid.spacing > 0f) {
            val step = geo.grid.spacing * k
            val grid = Color.Gray.copy(alpha = 0.15f)
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
            drawLine(Color.Gray.copy(alpha = 0.25f), Offset(x, top), Offset(x, bottom))
        }
    }

    // Explicitly selected pages are marked distinctly from the merely-active one, because "Selected pages" is
    // a Page Control scope and the user has to be able to see what it names.
    if (isSelected) {
        drawRect(color = Color(0xFF00BCD4), style = Stroke(width = 3f))
    } else if (isActive) {
        drawRect(color = Color(0xFF6750A4), style = Stroke(width = 2f))
    }
}

/** Object chrome: the selection outline, the primary marker and the lock badge. */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawObjectChrome(
    obj: SceneObject,
    selected: Boolean,
    isPrimary: Boolean,
) {
    when {
        // The primary object is the one handles and single-target commands act on, so it reads differently
        // from the rest of a multi-selection rather than the user having to infer which one is which.
        isPrimary -> drawRect(color = Color(0xFF6750A4), style = Stroke(width = 3f))
        selected -> drawRect(color = Color(0xFF6750A4).copy(alpha = 0.6f), style = Stroke(width = 2f))
        else -> drawRect(color = Color.Gray.copy(alpha = 0.4f), style = Stroke(width = 1f))
    }
    // A lock the user cannot see is a lock they will fight: the badge is why "this will not move" is legible
    // before the drag rather than after it.
    if (obj.lock != SceneLock.None) {
        drawCircle(color = Color.Gray, radius = 5f, center = Offset(size.width - 9f, 9f))
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
) {
    val k = scale * DP_PER_POINT * density
    guides.forEach { guide ->
        val color = when (guide.kind) {
            SnapGuideEngine.Kind.PAGE_EDGE, SnapGuideEngine.Kind.PAGE_CENTER -> Color(0xFFE91E63)
            SnapGuideEngine.Kind.MARGIN -> Color(0xFF4CAF50)
            SnapGuideEngine.Kind.OBJECT_EDGE, SnapGuideEngine.Kind.OBJECT_CENTER -> Color(0xFF00BCD4)
            SnapGuideEngine.Kind.GRID -> Color.Gray
        }
        val at = guide.position * k
        when (guide.orientation) {
            SnapGuideEngine.Orientation.VERTICAL ->
                drawLine(color, Offset(at, 0f), Offset(at, size.height), strokeWidth = 1.5f)
            SnapGuideEngine.Orientation.HORIZONTAL ->
                drawLine(color, Offset(0f, at), Offset(size.width, at), strokeWidth = 1.5f)
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
    onOpenSidebar: () -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onRepresentation: (LayoutRepresentation) -> Unit,
    onIntent: (WorkingIntent) -> Unit,
    onOpenPageControl: () -> Unit,
    onOpenPageList: () -> Unit,
    onEnterSelection: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onOpenSidebar) { Text("Menu") }
            DocumentTitleField(
                title = title,
                onTitleChange = onTitleChange,
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
            )
            TextButton(onClick = onUndo, enabled = state.canUndo) { Text("Undo") }
            TextButton(onClick = onRedo, enabled = state.canRedo) { Text("Redo") }
        }
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
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
private fun DocumentTitleField(title: String, onTitleChange: (String) -> Unit, modifier: Modifier = Modifier) {
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
            modifier = Modifier.fillMaxWidth(),
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
 * Each field commits on its own, as one transaction, through the same controller call a drag ends with — so
 * "typed 120" and "dragged to 120" are indistinguishable in history and in the saved document. A field whose
 * lock forbids it is read-only and says which lock, because a number that silently refuses to change is the
 * most confusing possible refusal.
 */
@Composable
private fun GeometryInspectorBar(
    obj: SceneObject,
    presenter: DocumentHostPresenter,
    onNotice: (String) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GeometryInspector.Field.entries.forEach { field ->
            GeometryField(field, obj, presenter, onNotice)
        }
    }
}

@Composable
private fun GeometryField(
    field: GeometryInspector.Field,
    obj: SceneObject,
    presenter: DocumentHostPresenter,
    onNotice: (String) -> Unit,
) {
    val stored = GeometryInspector.display(field.valueOf(obj.geometry))
    var text by remember(obj.id, field) { mutableStateOf(stored) }
    var lastStored by remember(obj.id, field) { mutableStateOf(stored) }
    // Re-seeded when the *object* changed underneath — a drag, an undo — but not merely because the field
    // differs from it, which would delete digits as they are typed.
    if (stored != lastStored) {
        lastStored = stored
        text = stored
    }
    val locked = field.isLockedFor(obj)

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
                    // Committed as it is typed only when the value is genuinely usable. A partial entry
                    // ("-", "1.") simply does not commit, so the object never jumps somewhere the user was
                    // only passing through on the way to the number they meant.
                    if (GeometryInspector.parse(field, next) != null) {
                        presenter.act { applyGeometryField(field, next) }
                    }
                },
                readOnly = locked,
                singleLine = true,
                textStyle = MaterialTheme.typography.labelMedium.copy(
                    color = if (locked) {
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    } else {
                        LocalContentColor.current
                    },
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
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
private fun DocumentDock(items: List<DocumentCommandItem>, onInvoke: (DocumentCommandItem) -> Unit) {
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
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PageControlSheet(
    state: DocumentHostUiState,
    onDismiss: () -> Unit,
    onScope: (PageScope) -> Unit,
    onInvoke: (String) -> Unit,
    onApplyValue: (String, String) -> Unit,
    onClearNewPageTemplate: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Text("Page setup", style = MaterialTheme.typography.titleMedium)
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
 * inspector commits as you type for the opposite reason — one object, instantly reversible, and the feedback
 * is the point.
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
