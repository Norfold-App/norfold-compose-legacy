package com.norfold.app.ui.editor.document

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.norfold.app.domain.DocumentPageBlock
import com.norfold.app.domain.SceneGeometry
import com.norfold.app.domain.SceneLock
import com.norfold.app.domain.SceneObject
import com.norfold.app.editor.command.CommandSurface
import com.norfold.app.editor.document.ActiveManipulation
import com.norfold.app.editor.document.DocumentCommandItem
import com.norfold.app.editor.document.DocumentHostUiState
import com.norfold.app.editor.document.LayoutRepresentation
import com.norfold.app.editor.document.PageScope
import com.norfold.app.editor.document.PageVirtualizer
import com.norfold.app.editor.document.SnapGuideEngine
import com.norfold.app.editor.document.WorkingIntent
import kotlin.math.roundToInt

/**
 * How many dp one page point occupies at 100% zoom. A point is 1/72", a dp is 1/160", so a page drawn at this
 * factor is physically the size it will print.
 */
private const val DP_PER_POINT = 160f / 72f

/** Visual size of a manipulation handle, and the invisible target around it that a finger can actually hit. */
private val HANDLE_VISUAL = 14.dp
private val HANDLE_TARGET = 44.dp

/**
 * DocumentHost — the bounded-page host (roadmap Phase 2).
 *
 * This composable owns layout and gestures and nothing else. Selection, eligibility, snapping, history and
 * page structure all live in the shared engines behind [DocumentHostPresenter]; every action offered here is
 * `presenter.act { invoke(id, surface) }` or a call into the runtime's manipulation state machine. That is
 * what makes a touch handle, the dock, a keyboard chord and a TalkBack action four presentations of one
 * command instead of four implementations of one idea.
 *
 * Rendering is virtualized per page from real page geometry, so a long document composes the pages on screen
 * and the instrumentation hook reports the range it genuinely built.
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
    var pageControlOpen by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }

    // The runtime raises a notice when it had to switch representation to keep an action faithful. Consuming
    // it here (rather than reading it every recomposition) is what makes it a one-shot explanation.
    LaunchedEffect(state.notice) {
        state.notice?.let { notice = presenter.act { consumeNotice() } }
    }

    Column(modifier.fillMaxSize()) {
        DocumentTopBar(
            state = state,
            title = title,
            onOpenSidebar = onOpenSidebar,
            onUndo = { presenter.act { undo() } },
            onRedo = { presenter.act { redo() } },
            onRepresentation = { presenter.act { setRepresentation(it) } },
            onIntent = { presenter.act { setIntent(it) } },
            onOpenPageControl = { pageControlOpen = true },
        )
        HorizontalDivider()

        notice?.let { message ->
            NoticeRow(message) { notice = null }
        }

        Box(Modifier.weight(1f)) {
            DocumentPageStack(presenter, Modifier.fillMaxSize())
        }

        if (state.dock.isNotEmpty()) {
            HorizontalDivider()
            DocumentDock(state.dock) { id -> presenter.act { invoke(id, CommandSurface.DOCK) } }
        }
    }

    if (pageControlOpen) {
        PageControlSheet(
            state = state,
            onDismiss = { pageControlOpen = false },
            onScope = { presenter.act { setPageScope(it) } },
            onInvoke = { id -> presenter.act { invokePageControl(id, state.pageScope) } },
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

        val scrollOffsetPt = with(density) { scroll.value.toDp().value } / DP_PER_POINT / scale
        val window = remember(state.revision, scrollOffsetPt, viewportHeightPt, scale) {
            presenter.runtime.pageWindow(scrollOffsetPt, viewportHeightPt / scale)
        }

        Box(
            Modifier
                .fillMaxWidth()
                .verticalScroll(scroll)
                .background(MaterialTheme.colorScheme.surfaceVariant),
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
                    presenter = presenter,
                )
            }
        }
    }
}

/** One page: its sheet, its structure guides, and the scene objects on it. */
@Composable
private fun PageSheet(
    page: DocumentPageBlock,
    pageIndex: Int,
    topPt: Float,
    scale: Float,
    presenter: DocumentHostPresenter,
) {
    val state = presenter.state
    val editing = state.representation == LayoutRepresentation.ORIGINAL && state.intent == WorkingIntent.EDIT
    val isActive = page.id == state.activePageId

    Box(
        Modifier
            .offset { IntOffset(0, topPt.ptToPx(scale, this).roundToInt()) }
            .size(page.geometry.width.ptToDp(scale), page.geometry.height.ptToDp(scale))
            .background(page.geometry.background?.let { Color(it.toInt()) } ?: Color.White)
            .drawBehind {
                drawPageStructure(page, scale, editing, isActive)
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
                        selectAt(
                            page.id,
                            offset.x.toPagePoints(scale, this@pointerInput),
                            offset.y.toPagePoints(scale, this@pointerInput),
                        )
                    }
                }
            }
            .semantics { contentDescription = "Page ${pageIndex + 1}" },
    ) {
        // Back-to-front: the front-most object is drawn last, so it is the one that overlaps the others.
        page.objects.sortedBy { it.z }.filter { it.visible }.forEach { obj ->
            SceneObjectBox(obj, page, scale, presenter)
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
    val editable = state.representation == LayoutRepresentation.ORIGINAL && state.intent == WorkingIntent.EDIT

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
            .drawBehind { drawObjectChrome(obj, selected) }
            .pointerInput(obj.id, scale, editable) {
                // A locked object still accepts the tap that selects it (§4.13) — the drag simply never
                // begins, because `beginManipulation` refuses it. Selection and manipulability are separate
                // questions, and conflating them is what makes a locked object unreachable.
                //
                // The running total lives here, for the whole gesture: the runtime wants the delta from the
                // manipulation's *start*, and feeding it per-frame deltas instead would make a snap sticky —
                // the snapped position would become the base the next delta is added to.
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
        Text(
            text = obj.displayName(),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(4.dp),
        )
    }

    // Handles are drawn *outside* the object box so a 44dp touch target is never clipped by a small object —
    // an 8pt-tall shape would otherwise have handles a finger cannot land on.
    if (selected && editable && obj.canResize) {
        ResizeHandle(obj, geometry, scale, presenter)
    }
}

/**
 * The bottom-right resize handle.
 *
 * One corner, deliberately, for Phase 2: it is the corner that reads as "resize" on a touch surface without a
 * ring of targets crowding a small object, and the exact-dimensions route through Page Control / the inspector
 * is what covers the cases a single corner cannot express.
 */
@Composable
private fun ResizeHandle(
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
                // Centred on the corner: the visual sits on the corner, and the target extends past it, which
                // is what makes the handle reachable when the object is smaller than the target.
                IntOffset(
                    (geometry.right.ptToPx(scale, this) - targetPx / 2f).roundToInt(),
                    (geometry.bottom.ptToPx(scale, this) - targetPx / 2f).roundToInt(),
                )
            }
            .size(HANDLE_TARGET)
            .pointerInput(obj.id, scale) {
                var width = geometry.width
                var height = geometry.height
                detectDragGestures(
                    onDragStart = {
                        width = geometry.width
                        height = geometry.height
                        presenter.act { beginManipulation(ActiveManipulation.Kind.RESIZE) }
                    },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        width += dragAmount.x.toPagePoints(scale, this@pointerInput)
                        height += dragAmount.y.toPagePoints(scale, this@pointerInput)
                        presenter.act {
                            updateResize(
                                geometry.copy(width = width, height = height),
                                movingRight = true,
                                movingBottom = true,
                            )
                        }
                    },
                    onDragEnd = { presenter.act { endManipulation() } },
                    onDragCancel = { presenter.act { cancelManipulation() } },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(HANDLE_VISUAL)
                .background(MaterialTheme.colorScheme.primary),
        )
    }
}

// ── Drawing ─────────────────────────────────────────────────────────────────────────────────────────

/** Page chrome: border, margin guides, grid, and the active-page indicator. */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawPageStructure(
    page: DocumentPageBlock,
    scale: Float,
    editing: Boolean,
    isActive: Boolean,
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

        for (index in 1 until geo.columns.count) {
            val x = geo.columnLeft(index) * k
            drawLine(Color.Gray.copy(alpha = 0.25f), Offset(x, top), Offset(x, bottom))
        }
    }

    if (isActive) {
        drawRect(color = Color(0xFF6750A4), style = Stroke(width = 2f))
    }
}

/** Object chrome: the selection outline and the lock badge. */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawObjectChrome(
    obj: SceneObject,
    selected: Boolean,
) {
    if (selected) {
        drawRect(color = Color(0xFF6750A4), style = Stroke(width = 2f))
    } else {
        drawRect(color = Color.Gray.copy(alpha = 0.4f), style = Stroke(width = 1f))
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
    onOpenSidebar: () -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onRepresentation: (LayoutRepresentation) -> Unit,
    onIntent: (WorkingIntent) -> Unit,
    onOpenPageControl: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onOpenSidebar) { Text("Menu") }
            Text(
                text = title.ifBlank { "Untitled document" },
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
            )
            TextButton(onClick = onUndo, enabled = state.canUndo) { Text("Undo") }
            TextButton(onClick = onRedo, enabled = state.canRedo) { Text("Redo") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
            AssistChip(onClick = onOpenPageControl, label = { Text("Page") })
        }
    }
}

/** The one-shot explanation of an automatic view transition. Dismissible, never blocking. */
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
 * Deliberately a short scrollable row of the actions that matter for the current selection, not a desktop
 * ribbon: the full catalogue is reachable through Page Control and the command surfaces, and squeezing it
 * all onto a phone is what makes a document editor unusable there.
 */
@Composable
private fun DocumentDock(items: List<DocumentCommandItem>, onInvoke: (String) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        items.take(6).forEach { item ->
            AssistChip(
                onClick = { onInvoke(item.id) },
                enabled = item.enabled,
                label = { Text(item.label, style = MaterialTheme.typography.labelSmall) },
            )
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

            HorizontalDivider(Modifier.padding(vertical = 8.dp))

            Column(Modifier.verticalScroll(rememberScrollState())) {
                // Grouped by the control's own category, so the sheet's structure comes from the catalogue
                // rather than a second hand-maintained ordering that could disagree with it.
                state.pageControls.groupBy { it.category }.forEach { (group, items) ->
                    Text(
                        text = group,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    items.forEach { item -> PageControlRow(item) { onInvoke(item.id) } }
                }
                Box(Modifier.height(24.dp))
            }
        }
    }
}

/** What the chosen scope actually names right now — the promise the rows below are answering against. */
private fun pageScopeSummary(state: DocumentHostUiState): String = when (state.pageScope) {
    PageScope.NewPagesByDefault -> "Changes apply to pages you add from now on"
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



