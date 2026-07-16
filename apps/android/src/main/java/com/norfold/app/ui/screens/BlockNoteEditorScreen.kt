@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.norfold.app.ui.screens

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.ActivityInfo
import android.net.Uri
import android.print.PrintAttributes
import android.print.PrintManager
import android.provider.OpenableColumns
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import com.norfold.app.ui.components.NorfoldBottomSheet
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.FormatListBulleted
import androidx.compose.material.icons.automirrored.outlined.Redo
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.CheckBox
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DragIndicator
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Fullscreen
import androidx.compose.material.icons.outlined.FormatListNumbered
import androidx.compose.material.icons.outlined.FormatQuote
import androidx.compose.material.icons.outlined.HorizontalRule
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material.icons.outlined.ScreenRotation
import androidx.compose.material.icons.outlined.TableChart
import androidx.compose.material.icons.outlined.Title
import com.norfold.app.ui.components.NorfoldDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.norfold.app.ui.components.NorfoldFullscreenDialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import coil.compose.AsyncImage
import com.norfold.app.domain.BlockCursor
import com.norfold.app.domain.BlockDocument
import com.norfold.app.domain.BlockDocumentJson
import com.norfold.app.domain.UnknownBlock
import com.norfold.app.domain.BlockEditorSession
import com.norfold.app.domain.BoldInline
import com.norfold.app.domain.BulletListBlock
import com.norfold.app.domain.CalloutBlock
import com.norfold.app.domain.ChartBlock
import com.norfold.app.domain.CodeBlock
import com.norfold.app.domain.CodeInline
import com.norfold.app.domain.ContainerAxis
import com.norfold.app.domain.ContainerBlock
import com.norfold.app.domain.DividerBlock
import com.norfold.app.domain.DocumentBlock
import com.norfold.app.domain.DocumentOwnerType
import com.norfold.app.domain.DropTarget
import com.norfold.app.domain.Edge
import com.norfold.app.domain.EmbedBlock
import com.norfold.app.domain.EmbedMetadata
import com.norfold.app.domain.EditorFontFamily
import com.norfold.app.domain.EmojiInline
import com.norfold.app.domain.FileBlock
import com.norfold.app.domain.DocOutline
import com.norfold.app.domain.DocCanvasSpec
import com.norfold.app.domain.DocLayerOrder
import com.norfold.app.domain.DocOverlapMode
import com.norfold.app.domain.DocPagination
import com.norfold.app.domain.DocSectionAction
import com.norfold.app.domain.DocSections
import com.norfold.app.domain.findById
import com.norfold.app.domain.FreeformPlacement
import com.norfold.app.domain.HeadingBlock
import com.norfold.app.ui.dnd.dragLift
import com.norfold.app.ui.dnd.dropSlotOutline
import com.norfold.app.domain.ImageBlock
import com.norfold.app.domain.ImageLayout
import com.norfold.app.domain.InlineNode
import com.norfold.app.domain.InlineText
import com.norfold.app.domain.ItalicInline
import com.norfold.app.domain.LinkInline
import com.norfold.app.domain.ListItem
import com.norfold.app.domain.locate
import com.norfold.app.domain.pathOf
import com.norfold.app.domain.MathBlock
import com.norfold.app.domain.MathInline
import com.norfold.app.domain.MarkdownBlockCodec
import com.norfold.app.domain.MarkdownExporter
import com.norfold.app.domain.DocxExporter
import com.norfold.app.domain.MermaidBlock
import com.norfold.app.domain.MentionInline
import com.norfold.app.domain.NumberedListBlock
import com.norfold.app.domain.ParagraphBlock
import com.norfold.app.domain.QuoteBlock
import com.norfold.app.domain.StrikethroughInline
import com.norfold.app.domain.SmartPasteCodec
import com.norfold.app.domain.TableBlock
import com.norfold.app.domain.TableAlignment
import com.norfold.app.domain.TableCell
import com.norfold.app.domain.TagInline
import com.norfold.app.domain.TodoListBlock
import com.norfold.app.domain.TodoItem
import com.norfold.app.ui.DocsUiState
import com.norfold.app.ui.DocsViewModel
import com.norfold.app.ui.LocalContextualMenuColor
import com.norfold.app.ui.LocalContextualMenuStyle
import com.norfold.app.domain.ContextualMenuColor
import com.norfold.app.domain.ContextualMenuStyle
import com.norfold.app.ui.components.EmbedMetadataResolver
import com.norfold.app.ui.components.ChartBuilderSheet
import com.norfold.app.ui.components.DiagramBuilderSheet
import com.norfold.app.ui.components.MathBuilderSheet
import com.norfold.app.ui.components.MathInsertionKind
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
fun BlockNoteEditorScreen(
    state: DocsUiState,
    viewModel: DocsViewModel,
    modifier: Modifier,
    onOpenSidebar: () -> Unit,
) {
    val ownedDocument by viewModel.activeDocument.collectAsState()
    val activeDocument = ownedDocument ?: return
    val owner = activeDocument.owner
    val editorKey = owner.documentId
    val initialTitle = when (owner.type) {
        DocumentOwnerType.Note -> state.notes.firstOrNull { it.id == owner.id }?.title ?: "Untitled doc"
        DocumentOwnerType.Task -> state.tasks.firstOrNull { it.id == owner.id }?.title ?: "Untitled task"
        DocumentOwnerType.CalendarEvent -> state.calendarEvents.firstOrNull { it.id == owner.id }?.title ?: "Untitled event"
    }
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val emojiShortcodes = remember(context) { loadEmojiShortcodes(context) }
    var title by remember(editorKey) { mutableStateOf(initialTitle) }
    val session = remember(editorKey) { BlockEditorSession(activeDocument.document) }
    var renderedDocument by remember(editorKey) { mutableStateOf(session.document) }
    var revision by remember(editorKey) { mutableIntStateOf(0) }
    var savedRevision by remember(editorKey) { mutableIntStateOf(0) }
    var editMode by remember(editorKey) { mutableStateOf(false) }
    var focusTarget by remember(editorKey) { mutableStateOf<BlockCursor?>(null) }
    var rangeAnchorId by remember(editorKey) { mutableStateOf<String?>(null) }
    var rangeExtentId by remember(editorKey) { mutableStateOf<String?>(null) }
    var chartBuilderRequest by remember(editorKey) { mutableStateOf<ChartBuilderRequest?>(null) }
    var mathBuilderRequest by remember(editorKey) { mutableStateOf<MathBuilderRequest?>(null) }
    var diagramBuilderRequest by remember(editorKey) { mutableStateOf<DiagramBuilderRequest?>(null) }
    var activeSelection by remember(editorKey) { mutableStateOf<EditorSelection?>(null) }
    var linkEditorRequest by remember(editorKey) { mutableStateOf<LinkEditorRequest?>(null) }
    val listState = rememberLazyListState()
    val overlapViewport = remember(editorKey) { FreeformViewport() }
    // Page-mode paginated render path (a render path within Reflow, not a third mode): opt-in
    // WYSIWYG preview toggled from the doc-settings menu. Session-local by design — the reflow
    // list below stays the default and the only editing surface.
    var paginatedView by remember(editorKey) { mutableStateOf(false) }
    // The print-source WebView must outlive the composable frame that created it: the print
    // framework pulls pages from it asynchronously after the menu closes.
    var printSource by remember { mutableStateOf<WebView?>(null) }
    var pendingDocx by remember { mutableStateOf<ByteArray?>(null) }
    val docxLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
    ) { uri ->
        val bytes = pendingDocx
        pendingDocx = null
        if (uri != null && bytes != null) {
            context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
        }
    }
    // Sidebar ToC taps: resolve the heading to its top-level ancestor, then scroll the Reflow list
    // (or pan the free-canvas viewport to the block's stored position) and consume the request.
    val scrollRequest by viewModel.scrollToBlockRequest.collectAsState()
    val screenDensity = LocalDensity.current
    LaunchedEffect(scrollRequest) {
        val request = scrollRequest ?: return@LaunchedEffect
        val entry = DocOutline.extract(renderedDocument).firstOrNull { it.blockId == request.blockId }
        val topId = entry?.topLevelId ?: request.blockId
        if (activeDocument.layoutMode != DocOverlapMode.Reflow) {
            val y = (activeDocument.freeformLayout[topId]?.y ?: 0f) - 12f
            val yPx = with(screenDensity) { y.coerceAtLeast(0f).dp.toPx() }
            overlapViewport.offset = Offset(overlapViewport.offset.x, -yPx * overlapViewport.scale)
        } else {
            val index = renderedDocument.blocks.indexOfFirst { it.id == topId }
            if (index >= 0) {
                if (paginatedView) {
                    // The paginated preview has no per-block scroll target: drop back to the
                    // reflow list, which honors the pending position on its next composition.
                    paginatedView = false
                    listState.scrollToItem(index)
                } else {
                    listState.animateScrollToItem(index)
                }
            }
        }
        viewModel.consumeScrollToBlock()
    }
    val rangeBounds = remember(renderedDocument, rangeAnchorId, rangeExtentId) {
        val anchorIndex = renderedDocument.blocks.indexOfFirst { it.id == rangeAnchorId }
        val extentIndex = renderedDocument.blocks.indexOfFirst { it.id == (rangeExtentId ?: rangeAnchorId) }
        if (anchorIndex >= 0 && extentIndex >= 0) minOf(anchorIndex, extentIndex)..maxOf(anchorIndex, extentIndex) else null
    }
    // Cross-container drag: every rendered block reports its window bounds here; a drag hit-tests against
    // them to find the hovered block + edge, then drops via a DropTarget (reorder / move / edge-split).
    val dragBounds = remember(editorKey) { mutableStateMapOf<String, Rect>() }
    var draggingId by remember(editorKey) { mutableStateOf<String?>(null) }
    var dragHoverId by remember(editorKey) { mutableStateOf<String?>(null) }
    var dragHoverEdge by remember(editorKey) { mutableStateOf<Edge?>(null) }
    // Floating-clone state: window pointer, grab offset inside the block, and the block's bounds at
    // lift-off, so the clone tracks the finger at the same relative spot it was grabbed.
    var dragPointer by remember(editorKey) { mutableStateOf<Offset?>(null) }
    var dragGrabOffset by remember(editorKey) { mutableStateOf(Offset.Zero) }
    var dragStartBounds by remember(editorKey) { mutableStateOf<Rect?>(null) }
    var editorRootOrigin by remember(editorKey) { mutableStateOf(Offset.Zero) }

    fun changed(cursor: BlockCursor? = null) {
        renderedDocument = session.document
        revision++
        focusTarget = cursor
    }

    // Sidebar ToC section actions: applied to the live session (not the repository) so the
    // mutation joins the normal undo/redo + debounced autosave path and can't be clobbered
    // by the session's next flush.
    val sectionRequest by viewModel.sectionActionRequest.collectAsState()
    LaunchedEffect(sectionRequest) {
        val request = sectionRequest ?: return@LaunchedEffect
        val headingId = request.headingId
        val applied = when (val action = request.action) {
            DocSectionAction.Delete -> session.deleteSection(headingId)
            DocSectionAction.Duplicate -> session.duplicateSection(headingId)
            DocSectionAction.MoveUp -> {
                val ids = DocSections.sectionHeadingIds(session.document)
                val index = ids.indexOf(headingId)
                if (index > 0) session.moveSectionBefore(headingId, ids[index - 1]) else false
            }
            DocSectionAction.MoveDown -> {
                val ids = DocSections.sectionHeadingIds(session.document)
                val index = ids.indexOf(headingId)
                // Insert before the section after next (null = end) to land one slot lower.
                if (index in 0 until ids.size - 1) session.moveSectionBefore(headingId, ids.getOrNull(index + 2)) else false
            }
            is DocSectionAction.MoveBefore -> session.moveSectionBefore(headingId, action.targetHeadingId)
        }
        if (applied) changed()
        viewModel.consumeSectionAction()
    }

    // --- Cross-container drag hit-testing (window coordinates) ---
    fun blockIsDescendantOf(id: String, ancestorId: String): Boolean =
        session.document.pathOf(id)?.ids?.dropLast(1)?.contains(ancestorId) == true

    fun updateDragHover(pointer: Offset) {
        val dragged = draggingId ?: return
        val hit = dragBounds.entries
            .filter { it.key != dragged && it.value.contains(pointer) && !blockIsDescendantOf(it.key, dragged) }
            .minByOrNull { it.value.width * it.value.height }
        if (hit == null) {
            // Keep the current target while the pointer rides the dragged block's own placeholder
            // (the live reorder preview parks that slot right under the finger); only clear the
            // target when the pointer genuinely leaves every block.
            val ownBounds = dragBounds[dragged]
            if (ownBounds == null || !ownBounds.contains(pointer)) {
                dragHoverId = null; dragHoverEdge = null
            }
            return
        }
        val r = hit.value
        val fx = if (r.width <= 0f) .5f else ((pointer.x - r.left) / r.width).coerceIn(0f, 1f)
        val fy = if (r.height <= 0f) .5f else ((pointer.y - r.top) / r.height).coerceIn(0f, 1f)
        dragHoverId = hit.key
        dragHoverEdge = when {
            fx < 0.22f -> Edge.Left
            fx > 0.78f -> Edge.Right
            fy < 0.28f -> Edge.Top
            fy > 0.72f -> Edge.Bottom
            else -> null // center → drop into a container (or vertical split as fallback)
        }
    }

    // True while a drag previews a plain top-level reorder (both blocks top-level, Top/Bottom edge):
    // the rendered list is permuted live so neighbors slide via animateItem(), instead of edge lines.
    fun previewReorderActive(): Boolean {
        val dragged = draggingId ?: return false
        val hover = dragHoverId ?: return false
        if (dragHoverEdge != Edge.Top && dragHoverEdge != Edge.Bottom) return false
        val blocks = renderedDocument.blocks
        return blocks.any { it.id == dragged } && blocks.any { it.id == hover }
    }

    fun finishDrag() {
        val dragged = draggingId
        val hover = dragHoverId
        val edge = dragHoverEdge
        draggingId = null; dragHoverId = null; dragHoverEdge = null
        dragPointer = null; dragStartBounds = null
        if (dragged == null || hover == null || dragged == hover) return
        val target: DropTarget? = when (edge) {
            Edge.Left, Edge.Right -> DropTarget.Split(hover, edge)
            Edge.Top, Edge.Bottom -> session.document.blocks.locate(hover)?.let { (parentId, idx, _) ->
                DropTarget.Into(parentId, idx + if (edge == Edge.Bottom) 1 else 0)
            }
            null -> (session.document.findById(hover) as? ContainerBlock)?.let { DropTarget.Into(hover, it.children.size) }
                ?: DropTarget.Split(hover, Edge.Bottom)
        }
        if (target != null) {
            session.move(dragged, target)
            changed()
        }
    }

    fun cancelDrag() {
        draggingId = null; dragHoverId = null; dragHoverEdge = null
        dragPointer = null; dragStartBounds = null
    }

    fun replaceActiveSelection(factory: (String) -> InlineNode) {
        var selection = activeSelection ?: return
        if (selection.text.isBlank()) {
            val block = session.document.blocks.firstOrNull { it.id == selection.start.blockId } ?: return
            val plain = block.inlineContent(selection.start.itemId)?.joinToString("") { it.plainText() } ?: return
            val caret = selection.start.offset.coerceIn(0, plain.length)
            var start = caret
            var end = caret
            while (start > 0 && !plain[start - 1].isWhitespace()) start--
            while (end < plain.length && !plain[end].isWhitespace()) end++
            if (start == end) return
            selection = EditorSelection(
                BlockCursor(block.id, start, selection.start.itemId),
                BlockCursor(block.id, end, selection.start.itemId),
                plain.substring(start, end),
            )
        }
        val selectedText = selection.text
        val replacement = factory(selectedText)
        val cursor = session.replaceSelectionWithInline(selection.start, selection.end, replacement)
        changed(cursor)
        activeSelection = EditorSelection(
            start = cursor.copy(offset = (cursor.offset - replacement.plainText().length).coerceAtLeast(0)),
            end = cursor,
            text = replacement.plainText(),
        )
    }

    fun transformActiveBlock(target: TextBlockTarget) {
        val selection = activeSelection ?: return
        val block = session.document.blocks.firstOrNull { it.id == selection.start.blockId } ?: return
        val transformed = block.convertTo(target)
        session.replaceBlock(transformed)
        val offset = selection.end.offset.coerceAtMost(transformed.plainText().length)
        changed(BlockCursor(transformed.id, offset))
        activeSelection = EditorSelection(BlockCursor(transformed.id, offset), BlockCursor(transformed.id, offset), "")
    }

    BackHandler(enabled = editMode) {
        if (rangeAnchorId != null) {
            rangeAnchorId = null
            rangeExtentId = null
        } else {
            editMode = false
            activeSelection = null
        }
    }
    LaunchedEffect(revision, title) {
        if (revision == 0 && title == initialTitle) return@LaunchedEffect
        val savingRevision = revision
        delay(500)
        viewModel.updateActiveDocument(owner, title, session.document, session.dirtyBlockIds.toSet()).join()
        session.markSaved()
        savedRevision = savingRevision
    }
    val latestTitle by rememberUpdatedState(title)
    val latestDocument by rememberUpdatedState(renderedDocument)
    val latestRevision by rememberUpdatedState(revision)
    val latestSavedRevision by rememberUpdatedState(savedRevision)
    DisposableEffect(lifecycleOwner, editorKey) {
        fun flushPendingDocument() {
            val dirtyIds = session.dirtyBlockIds.toSet()
            if (latestRevision == latestSavedRevision && latestTitle == initialTitle && dirtyIds.isEmpty()) return
            val savingRevision = latestRevision
            viewModel.updateActiveDocument(owner, latestTitle, latestDocument, dirtyIds).invokeOnCompletion { error ->
                if (error == null) {
                    session.markSaved()
                    savedRevision = savingRevision
                }
            }
        }
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) flushPendingDocument()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            flushPendingDocument()
        }
    }

    val baseEditorDensity = LocalDensity.current
    val editorFontScale = 0.85f + state.settings.editorFontSize.coerceIn(0f, 1f) * 0.45f
    val editorFamily = when (state.settings.editorFontFamily) {
        EditorFontFamily.Sans -> FontFamily.SansSerif
        EditorFontFamily.Serif -> FontFamily.Serif
    }
    CompositionLocalProvider(
        LocalEmojiShortcodes provides emojiShortcodes,
        LocalDensity provides Density(baseEditorDensity.density, baseEditorDensity.fontScale * editorFontScale),
        LocalTextStyle provides LocalTextStyle.current.copy(fontFamily = editorFamily),
    ) {
    Box(modifier.fillMaxSize().onGloballyPositioned { editorRootOrigin = it.positionInWindow() }) {
    Column(Modifier.fillMaxSize()) {
        BlockEditorHeader(
            title = title,
            editMode = editMode,
            canUndo = session.canUndo(),
            canRedo = session.canRedo(),
            onTitleChange = { title = it; revision++ },
            onBack = {
                if (editMode) {
                    editMode = false
                    activeSelection = null
                } else viewModel.handleBack()
            },
            onMenu = onOpenSidebar,
            onToggleEdit = {
                editMode = !editMode
                if (!editMode) activeSelection = null
            },
            onUndo = { if (session.undo()) changed() },
            onRedo = { if (session.redo()) changed() },
            overlapMode = activeDocument.layoutMode,
            onSetOverlapMode = { mode ->
                if (mode == DocOverlapMode.Bounded) {
                    val required = activeDocument.canvasSpec.pagesRequiredFor(activeDocument.freeformLayout)
                    if (required > activeDocument.canvasSpec.pageCount) {
                        viewModel.updateActiveDocumentLayout(
                            owner,
                            session.document,
                            mode,
                            activeDocument.freeformLayout,
                            activeDocument.canvasSpec.copy(pageCount = required),
                        )
                    } else {
                        viewModel.updateActiveDocumentLayout(owner, session.document, mode, activeDocument.freeformLayout, activeDocument.canvasSpec)
                    }
                } else {
                    viewModel.updateActiveDocumentLayout(owner, session.document, mode, activeDocument.freeformLayout, activeDocument.canvasSpec)
                }
            },
            canvasSpec = activeDocument.canvasSpec,
            minimumCanvasPages = activeDocument.canvasSpec.pagesRequiredFor(activeDocument.freeformLayout),
            onSetCanvasSpec = {
                viewModel.updateActiveDocumentLayout(owner, session.document, activeDocument.layoutMode, activeDocument.freeformLayout, it)
            },
            paginatedView = paginatedView,
            onTogglePaginatedView = { paginatedView = !paginatedView },
            onExportPdf = {
                // Deterministic print source: Kotlin-side HTML (no async JS render) fed to the
                // system print service, which owns pagination and the save-as-PDF UI. Uses the
                // live session document so unsaved edits are included.
                val jobName = title.ifBlank { "Norfold document" }
                val web = WebView(context)
                web.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String?) {
                        val printManager = context.getSystemService(Context.PRINT_SERVICE) as PrintManager
                        printManager.print(
                            jobName,
                            view.createPrintDocumentAdapter(jobName),
                            PrintAttributes.Builder().setMediaSize(
                                if (activeDocument.layoutMode == DocOverlapMode.Bounded) {
                                    PrintAttributes.MediaSize(
                                        "NORFOLD_DOCUMENT",
                                        "Norfold document",
                                        (activeDocument.canvasSpec.width / 72f * 1000f).roundToInt(),
                                        (activeDocument.canvasSpec.height / 72f * 1000f).roundToInt(),
                                    )
                                } else {
                                    PrintAttributes.MediaSize.ISO_A4
                                },
                            ).build(),
                        )
                    }
                }
                web.loadDataWithBaseURL(
                    null,
                    if (activeDocument.layoutMode == DocOverlapMode.Bounded) {
                        MarkdownExporter.canvasPrintHtml(jobName, session.document, activeDocument.freeformLayout, activeDocument.canvasSpec)
                    } else {
                        MarkdownExporter.printHtml(jobName, MarkdownBlockCodec.export(session.document))
                    },
                    "text/html",
                    "utf-8",
                    null,
                )
                printSource = web
            },
            onExportDocx = {
                pendingDocx = DocxExporter.export(title, session.document)
                val safeName = title.ifBlank { "Norfold document" }.replace(Regex("[\\\\/:*?\"<>|]"), "_")
                docxLauncher.launch("$safeName.docx")
            },
        )
        if (rangeAnchorId != null) {
            DocumentRangeToolbar(
                onReplace = { replacement ->
                    val anchorIndex = renderedDocument.blocks.indexOfFirst { it.id == rangeAnchorId }
                    val extentIndex = renderedDocument.blocks.indexOfFirst { it.id == (rangeExtentId ?: rangeAnchorId) }
                    if (anchorIndex >= 0 && extentIndex >= 0) {
                        val firstIndex = minOf(anchorIndex, extentIndex)
                        val lastIndex = maxOf(anchorIndex, extentIndex)
                        val first = renderedDocument.blocks[firstIndex]
                        val last = renderedDocument.blocks[lastIndex]
                        changed(session.replaceSelection(BlockCursor(first.id, 0), BlockCursor(last.id, last.plainText().length), replacement))
                    }
                    rangeAnchorId = null
                    rangeExtentId = null
                },
                onCancel = {
                    rangeAnchorId = null
                    rangeExtentId = null
                },
            )
        }
        // Renders one block and, for containers, recurses into its children via `renderChild`. Defined
        // locally so it can call itself while still capturing the session + editor state. `index` is the
        // top-level lazy index (or -1 when nested); `nested` gates drag/reorder chrome.
        @Composable
        fun BlockNode(
            block: DocumentBlock,
            index: Int,
            nested: Boolean,
            rowModifier: Modifier,
            contentEditing: Boolean = editMode,
        ) {
            SharedBlockRow(
                modifier = rowModifier,
                block = block,
                index = index,
                mode = if (contentEditing) BlockSurfaceMode.Edit else BlockSurfaceMode.View,
                focus = focusTarget?.takeIf { it.blockId == block.id },
                onFocusConsumed = { focusTarget = null },
                onReplace = { session.replaceBlock(it); changed() },
                onEditText = { old, new ->
                    val outcome = session.editTextOrSmartPaste(block.id, old, new)
                    changed(outcome.cursor.takeIf { outcome.structuredPaste })
                },
                onReplaceInline = { start, end, inline ->
                    session.replaceSelectionWithInline(BlockCursor(block.id, start), BlockCursor(block.id, end), inline)
                    changed()
                },
                onEditListItem = { itemId, old, new -> session.editListItem(block.id, itemId, old, new); changed() },
                onEditTodoItem = { itemId, old, new -> session.editTodoItem(block.id, itemId, old, new); changed() },
                onSplitListItem = { itemId, offset -> changed(session.splitListItem(block.id, itemId, offset)) },
                onExitListItem = { itemId -> changed(session.exitEmptyListItem(block.id, itemId)) },
                onMergeListItem = { itemId -> changed(session.mergeListItemWithPrevious(block.id, itemId)) },
                onSplit = { offset -> changed(session.split(block.id, offset)) },
                onMerge = { changed(session.mergeWithPrevious(block.id)) },
                onMove = { target -> session.move(block.id, target); changed() },
                dragTarget = { dragOffset ->
                    val current = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == block.id }
                    if (current == null) null else {
                        val center = current.offset + dragOffset + current.size / 2f
                        listState.layoutInfo.visibleItemsInfo
                            .asSequence()
                            .filter { it.index < renderedDocument.blocks.size }
                            .minByOrNull { candidate -> kotlin.math.abs(center - (candidate.offset + candidate.size / 2f)) }
                            ?.index
                    }
                },
                onInsert = { type ->
                    when (type) {
                        InsertBlockType.Chart -> chartBuilderRequest = ChartBuilderRequest(anchorId = block.id)
                        InsertBlockType.Math -> {
                            val inlineSelection = activeSelection?.takeIf {
                                it.start.blockId == block.id && it.end.blockId == block.id
                            }
                            mathBuilderRequest = MathBuilderRequest(
                                anchorId = block.id,
                                initialKind = if (inlineSelection == null) MathInsertionKind.Block else MathInsertionKind.Inline,
                                inlineSelection = inlineSelection,
                            )
                        }
                        InsertBlockType.Mermaid -> diagramBuilderRequest = DiagramBuilderRequest(anchorId = block.id)
                        else -> changed(session.insertAfter(block.id, newDocumentBlock(type)))
                    }
                },
                onDelete = { changed(session.delete(block.id)) },
                onDuplicate = { changed(session.insertAfter(block.id, duplicateBlock(block))) },
                rangeSelectionActive = rangeAnchorId != null,
                selectedForRange = !nested && rangeBounds?.contains(index) == true,
                onStartRangeSelection = {
                    rangeAnchorId = block.id
                    rangeExtentId = block.id
                },
                onExtendRangeSelection = { rangeExtentId = block.id },
                onEditChart = { chart -> chartBuilderRequest = ChartBuilderRequest(editingId = chart.id, initialSpec = chart.vegaLiteSpec) },
                onEditMath = { math ->
                    mathBuilderRequest = MathBuilderRequest(
                        editingId = math.id,
                        initialTex = math.tex,
                    )
                },
                onEditDiagram = { diagram ->
                    diagramBuilderRequest = DiagramBuilderRequest(
                        editingId = diagram.id,
                        initialSource = diagram.code,
                    )
                },
                onSelectionChange = { activeSelection = it },
                nested = nested,
                onWrap = { axis -> session.wrapInContainer(block.id, axis); changed() },
                onUnwrap = { session.unwrap(block.id); changed() },
                onExtract = { session.extractToTopLevel(block.id); changed() },
                onToggleAxis = {
                    (block as? ContainerBlock)?.let {
                        session.setAxis(it.id, if (it.axis == ContainerAxis.Row) ContainerAxis.Column else ContainerAxis.Row)
                        changed()
                    }
                },
                onAddToContainer = { session.addChild(block.id, ParagraphBlock()); changed() },
                onMoveWithin = { delta ->
                    session.document.blocks.locate(block.id)?.let { (parentId, idx, siblings) ->
                        val raw = if (delta < 0) idx - 1 else idx + 2
                        session.move(block.id, DropTarget.Into(parentId, raw.coerceIn(0, siblings.size)))
                        changed()
                    }
                },
                onReportBounds = { rect -> if (rect != null) dragBounds[block.id] = rect else dragBounds.remove(block.id) },
                onDragBegin = {
                    draggingId = block.id
                    dragStartBounds = dragBounds[block.id]
                },
                onDragTo = { p ->
                    if (dragPointer == null) dragGrabOffset = p - (dragStartBounds?.topLeft ?: p)
                    dragPointer = p
                    updateDragHover(p)
                },
                onDragDrop = { finishDrag() },
                onDragAbort = { cancelDrag() },
                isBeingDragged = draggingId == block.id,
                isDragHovered = dragHoverId == block.id && draggingId != null && !previewReorderActive(),
                dragHoverEdge = if (dragHoverId == block.id && !previewReorderActive()) dragHoverEdge else null,
                renderChild = { child ->
                    BlockNode(child, index = -1, nested = true, rowModifier = Modifier, contentEditing = contentEditing)
                },
            )
        }
        if (activeDocument.layoutMode != DocOverlapMode.Reflow) {
            // Free-overlap mode: a completely freeform canvas (absolute x/y, explicit size, z-layers)
            // replaces the stacked list; the Reflow LazyColumn path below is untouched.
            FreeformDocCanvas(
                blocks = renderedDocument.blocks,
                layout = activeDocument.freeformLayout,
                canvasSpec = activeDocument.canvasSpec.takeIf { activeDocument.layoutMode == DocOverlapMode.Bounded },
                editMode = editMode,
                onSeedLayout = { seeded ->
                    if (activeDocument.layoutMode == DocOverlapMode.Bounded) {
                        val required = activeDocument.canvasSpec.pagesRequiredFor(seeded)
                        viewModel.updateActiveDocumentLayout(
                            owner,
                            session.document,
                            activeDocument.layoutMode,
                            seeded,
                            activeDocument.canvasSpec.copy(pageCount = maxOf(required, activeDocument.canvasSpec.pageCount)),
                        )
                    } else {
                        viewModel.updateActiveDocumentLayout(owner, session.document, activeDocument.layoutMode, seeded, activeDocument.canvasSpec)
                    }
                },
                onCommitPlacement = { id, placement ->
                    viewModel.updateActiveDocumentLayout(owner, session.document, activeDocument.layoutMode, activeDocument.freeformLayout + (id to placement), activeDocument.canvasSpec)
                },
                onBringToFront = { id ->
                    viewModel.updateActiveDocumentLayout(owner, session.document, activeDocument.layoutMode, DocLayerOrder.bringToFront(activeDocument.freeformLayout, id), activeDocument.canvasSpec)
                },
                onBringForward = { id ->
                    viewModel.updateActiveDocumentLayout(owner, session.document, activeDocument.layoutMode, DocLayerOrder.bringForward(activeDocument.freeformLayout, id), activeDocument.canvasSpec)
                },
                onSendBackward = { id ->
                    viewModel.updateActiveDocumentLayout(owner, session.document, activeDocument.layoutMode, DocLayerOrder.sendBackward(activeDocument.freeformLayout, id), activeDocument.canvasSpec)
                },
                onSendToBack = { id ->
                    viewModel.updateActiveDocumentLayout(owner, session.document, activeDocument.layoutMode, DocLayerOrder.sendToBack(activeDocument.freeformLayout, id), activeDocument.canvasSpec)
                },
                onBackgroundTap = { if (editMode) { editMode = false; activeSelection = null } },
                onBackgroundDoubleTap = { if (!editMode) editMode = true },
                viewport = overlapViewport,
                modifier = Modifier.widthIn(max = 960.dp).fillMaxSize().align(Alignment.CenterHorizontally).imePadding(),
            ) { block, contentEditing ->
                BlockNode(block, index = -1, nested = true, rowModifier = Modifier, contentEditing = contentEditing)
            }
        } else if (paginatedView && !editMode) {
            // Page-mode paginated render path: read-only A4 pages; double-tap drops into edit
            // mode, which always uses the reflow list below — that path stays authoritative.
            PaginatedDocPages(
                blocks = renderedDocument.blocks,
                onBackgroundDoubleTap = { editMode = true },
                modifier = Modifier.widthIn(max = 960.dp).fillMaxSize().align(Alignment.CenterHorizontally),
            ) { block -> BlockNode(block, index = -1, nested = true, rowModifier = Modifier) }
        } else {
        // Live reorder preview: while dragging over a top-level Top/Bottom edge, render the list with
        // the dragged block moved to the would-be drop slot; animateItem() slides the neighbors. The
        // real document is only mutated on drop (session.move in finishDrag), so aborting snaps back.
        val displayBlocks = if (!previewReorderActive()) renderedDocument.blocks else {
            val blocks = renderedDocument.blocks
            val draggedBlock = blocks.first { it.id == draggingId }
            val without = blocks.filter { it.id != draggingId }
            val anchor = without.indexOfFirst { it.id == dragHoverId }
            if (anchor < 0) blocks else {
                val insertAt = (anchor + if (dragHoverEdge == Edge.Bottom) 1 else 0).coerceIn(0, without.size)
                without.toMutableList().apply { add(insertAt, draggedBlock) }
            }
        }
        LazyColumn(
            Modifier.widthIn(max = 960.dp).fillMaxSize().align(Alignment.CenterHorizontally).imePadding().pointerInput(editMode) {
                detectTapGestures(
                    onTap = { if (editMode) { editMode = false; activeSelection = null } },
                    onDoubleTap = { if (!editMode) editMode = true },
                )
            },
            contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 184.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            state = listState,
        ) {
            itemsIndexed(
                displayBlocks,
                key = { _, block -> block.id },
                contentType = { _, block -> block::class },
            ) { index, block ->
                BlockNode(
                    block = block,
                    index = index,
                    nested = false,
                    rowModifier = if (editMode) Modifier.animateItem() else Modifier,
                )
            }
            if (editMode) {
                item("insert-end") {
                    BlockInsertButton { type ->
                        val anchor = session.document.blocks.lastOrNull()?.id
                        when (type) {
                            InsertBlockType.Chart -> chartBuilderRequest = ChartBuilderRequest(anchorId = anchor)
                            InsertBlockType.Math -> mathBuilderRequest = MathBuilderRequest(anchorId = anchor)
                            InsertBlockType.Mermaid -> diagramBuilderRequest = DiagramBuilderRequest(anchorId = anchor)
                            else -> changed(session.insertAfter(anchor, newDocumentBlock(type)))
                        }
                    }
                }
            }
        }
        }
    }
    // Floating drag clone: the grabbed block "lifts off" and follows the finger while its source row
    // collapses into a dashed placeholder slot in the list below.
    val cloneBlock = draggingId?.let { id -> renderedDocument.findById(id) }
    val clonePointer = dragPointer
    if (cloneBlock != null && clonePointer != null) {
        val density = LocalDensity.current
        val cloneWidth = with(density) { (dragStartBounds?.width ?: 0f).toDp() }
        val position = clonePointer - dragGrabOffset - editorRootOrigin
        Surface(
            color = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier
                .then(if (cloneWidth > 0.dp) Modifier.width(cloneWidth) else Modifier.fillMaxWidth(0.8f))
                .offset { IntOffset(position.x.roundToInt(), position.y.roundToInt()) }
                .zIndex(20f)
                .dragLift(lifted = true, cornerRadius = 10.dp),
        ) {
            Box(Modifier.heightIn(max = 240.dp).padding(10.dp)) { BlockCloneContent(cloneBlock) }
        }
    }
    if (editMode && activeSelection != null) {
        FloatingFormattingToolbar(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .imePadding()
                .navigationBarsPadding()
                .padding(horizontal = 8.dp, vertical = 10.dp)
                .zIndex(12f),
            onInlineFormat = { action ->
                when (action) {
                    InlineFormatAction.Bold -> replaceActiveSelection { BoldInline(listOf(InlineText(it))) }
                    InlineFormatAction.Italic -> replaceActiveSelection { ItalicInline(listOf(InlineText(it))) }
                    InlineFormatAction.Strike -> replaceActiveSelection { StrikethroughInline(listOf(InlineText(it))) }
                    InlineFormatAction.Code -> replaceActiveSelection(::CodeInline)
                }
            },
            onLink = {
                activeSelection?.let { selection ->
                    linkEditorRequest = renderedDocument.linkEditorRequest(selection)
                        ?: LinkEditorRequest(selection.start, selection.end, selection.text.ifBlank { "Link text" }, "https://")
                }
            },
            onTurnInto = ::transformActiveBlock,
            onInsert = { type ->
                val anchor = activeSelection?.start?.blockId
                when (type) {
                    InsertBlockType.Chart -> chartBuilderRequest = ChartBuilderRequest(anchorId = anchor)
                    InsertBlockType.Math -> mathBuilderRequest = MathBuilderRequest(
                        anchorId = anchor,
                        initialKind = MathInsertionKind.Inline,
                        inlineSelection = activeSelection,
                    )
                    InsertBlockType.Mermaid -> diagramBuilderRequest = DiagramBuilderRequest(anchorId = anchor)
                    else -> changed(session.insertAfter(anchor, newDocumentBlock(type)))
                }
            },
            onMove = { delta ->
                val blockId = activeSelection?.start?.blockId
                if (blockId != null) {
                    val from = session.document.blocks.indexOfFirst { it.id == blockId }
                    if (from >= 0) {
                        session.move(blockId, (from + delta).coerceIn(0, session.document.blocks.lastIndex))
                        changed(BlockCursor(blockId, activeSelection?.end?.offset ?: 0))
                    }
                }
            },
            fontFamily = state.settings.editorFontFamily,
            fontSize = state.settings.editorFontSize,
            onFontFamily = viewModel::setEditorFontFamily,
            onFontSize = { size -> viewModel.patchSettings { it.copy(editorFontSize = size.coerceIn(0f, 1f)) } },
        )
    }
    }
    chartBuilderRequest?.let { request ->
        ChartBuilderSheet(
            initialSpec = request.initialSpec,
            onDismiss = { chartBuilderRequest = null },
            onCreate = { output ->
                if (request.editingId != null) {
                    session.replaceBlock(output.withBlockId(request.editingId))
                    changed()
                } else {
                    changed(session.insertAfter(request.anchorId, output))
                }
                chartBuilderRequest = null
            },
        )
    }
    mathBuilderRequest?.let { request ->
        MathBuilderSheet(
            initialTex = request.initialTex,
            initialKind = request.initialKind,
            allowKindSelection = request.editingId == null,
            onDismiss = { mathBuilderRequest = null },
            onInsert = { output ->
                when {
                    request.editingId != null -> {
                        val existing = session.document.blocks
                            .firstOrNull { it.id == request.editingId } as? MathBlock
                        session.replaceBlock(
                            existing?.copy(tex = output.tex)
                                ?: MathBlock(id = request.editingId, tex = output.tex),
                        )
                        changed()
                    }
                    output.kind == MathInsertionKind.Block -> {
                        changed(session.insertAfter(request.anchorId, MathBlock(tex = output.tex)))
                    }
                    request.inlineSelection != null -> {
                        val selection = request.inlineSelection
                        changed(
                            session.replaceSelectionWithInline(
                                selection.start,
                                selection.end,
                                MathInline(output.tex),
                            ),
                        )
                    }
                    else -> {
                        changed(
                            session.insertAfter(
                                request.anchorId,
                                ParagraphBlock(content = listOf(MathInline(output.tex))),
                            ),
                        )
                    }
                }
                mathBuilderRequest = null
            },
        )
    }
    diagramBuilderRequest?.let { request ->
        DiagramBuilderSheet(
            initialSource = request.initialSource,
            onDismiss = { diagramBuilderRequest = null },
            onCreate = { built ->
                if (request.editingId != null) {
                    val existing = session.document.blocks
                        .firstOrNull { it.id == request.editingId } as? MermaidBlock
                    session.replaceBlock(
                        existing?.copy(code = built.code) ?: built.withBlockId(request.editingId),
                    )
                    changed()
                } else {
                    changed(session.insertAfter(request.anchorId, built))
                }
                diagramBuilderRequest = null
            },
        )
    }
    linkEditorRequest?.let { request ->
        LinkEditorDialog(
            request = request,
            onDismiss = { linkEditorRequest = null },
            onSave = { text, url ->
                val cursor = session.replaceSelectionWithInline(
                    request.start,
                    request.end,
                    LinkInline(url.trim(), listOf(InlineText(text.ifBlank { url.trim() }))),
                )
                changed(cursor)
                linkEditorRequest = null
            },
        )
    }
    }
}

private data class ChartBuilderRequest(
    val anchorId: String? = null,
    val editingId: String? = null,
    val initialSpec: String? = null,
)

private data class MathBuilderRequest(
    val anchorId: String? = null,
    val editingId: String? = null,
    val initialTex: String = "",
    val initialKind: MathInsertionKind = MathInsertionKind.Block,
    val inlineSelection: EditorSelection? = null,
)

private data class DiagramBuilderRequest(
    val anchorId: String? = null,
    val editingId: String? = null,
    val initialSource: String? = null,
)

private data class EditorSelection(
    val start: BlockCursor,
    val end: BlockCursor,
    val text: String,
)

private data class LinkEditorRequest(
    val start: BlockCursor,
    val end: BlockCursor,
    val text: String,
    val url: String,
)

private enum class InlineFormatAction { Bold, Italic, Strike, Code }

private enum class TextBlockTarget(val label: String) {
    Paragraph("Paragraph"),
    Heading1("Heading 1"),
    Heading2("Heading 2"),
    Heading3("Heading 3"),
    Heading4("Heading 4"),
    Heading5("Heading 5"),
    Heading6("Heading 6"),
    Bullet("Bullet list"),
    Numbered("Numbered list"),
    Checklist("Checklist"),
    Quote("Quote"),
    Code("Code block"),
    Divider("Divider"),
}

@Composable
private fun FloatingFormattingToolbar(
    modifier: Modifier,
    onInlineFormat: (InlineFormatAction) -> Unit,
    onLink: () -> Unit,
    onTurnInto: (TextBlockTarget) -> Unit,
    onInsert: (InsertBlockType) -> Unit,
    onMove: (Int) -> Unit,
    fontFamily: EditorFontFamily,
    fontSize: Float,
    onFontFamily: (EditorFontFamily) -> Unit,
    onFontSize: (Float) -> Unit,
) {
    var turnIntoOpen by remember { mutableStateOf(false) }
    var overflowOpen by remember { mutableStateOf(false) }
    var expanded by rememberSaveable { mutableStateOf(false) }
    var dragY by remember { mutableStateOf(0f) }
    Surface(
        modifier = modifier.fillMaxWidth().widthIn(max = 720.dp).focusProperties { canFocus = false },
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        tonalElevation = 8.dp,
        shadowElevation = 10.dp,
    ) {
        Column(Modifier.fillMaxWidth().animateContentSize()) {
        LazyRow(
            modifier = Modifier.fillMaxWidth().height(54.dp).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            item("expand") { ToolbarTextButton(if (expanded) "⌄" else "⌃", if (expanded) "Collapse formatting bar" else "Expand formatting bar") { expanded = !expanded } }
            item("drag") {
                Icon(
                    Icons.Outlined.DragIndicator,
                    "Drag focused block up or down",
                    Modifier
                        .size(44.dp)
                        .padding(10.dp)
                        .pointerInput(Unit) {
                            detectDragGestures(
                                onDragStart = { dragY = 0f },
                                onDragCancel = { dragY = 0f },
                                onDragEnd = {
                                    if (kotlin.math.abs(dragY) > 18f) onMove(if (dragY < 0f) -1 else 1)
                                    dragY = 0f
                                },
                                onDrag = { change, amount -> change.consume(); dragY += amount.y },
                            )
                        },
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
            item("turn-into") {
                Box {
                    ToolbarTextButton("T", "Turn into") { turnIntoOpen = true }
                    DropdownMenu(expanded = turnIntoOpen, onDismissRequest = { turnIntoOpen = false }) {
                        TextBlockTarget.entries.forEach { target ->
                            DropdownMenuItem(
                                text = { Text(target.label) },
                                onClick = { turnIntoOpen = false; onTurnInto(target) },
                            )
                        }
                    }
                }
            }
            item("bold") { ToolbarTextButton("B", "Bold", FontWeight.Black) { onInlineFormat(InlineFormatAction.Bold) } }
            item("italic") { ToolbarTextButton("I", "Italic", fontStyle = FontStyle.Italic) { onInlineFormat(InlineFormatAction.Italic) } }
            item("strike") { ToolbarTextButton("S", "Strikethrough", textDecoration = TextDecoration.LineThrough) { onInlineFormat(InlineFormatAction.Strike) } }
            item("code") { ToolbarTextButton("</>", "Inline code", fontFamily = FontFamily.Monospace) { onInlineFormat(InlineFormatAction.Code) } }
            item("link") { ToolbarIconButton(Icons.Outlined.Link, "Link", onLink) }
            item("bullet") { ToolbarIconButton(Icons.AutoMirrored.Outlined.FormatListBulleted, "Bullet list") { onTurnInto(TextBlockTarget.Bullet) } }
            item("numbered") { ToolbarIconButton(Icons.Outlined.FormatListNumbered, "Numbered list") { onTurnInto(TextBlockTarget.Numbered) } }
            item("checklist") { ToolbarIconButton(Icons.Outlined.CheckBox, "Checklist") { onTurnInto(TextBlockTarget.Checklist) } }
            item("more") {
                Box {
                    ToolbarIconButton(Icons.Outlined.MoreVert, "More formatting") { overflowOpen = true }
                    DropdownMenu(expanded = overflowOpen, onDismissRequest = { overflowOpen = false }) {
                        DropdownMenuItem(text = { Text("Quote") }, leadingIcon = { Icon(Icons.Outlined.FormatQuote, null) }, onClick = { overflowOpen = false; onTurnInto(TextBlockTarget.Quote) })
                        DropdownMenuItem(text = { Text("Divider") }, leadingIcon = { Icon(Icons.Outlined.HorizontalRule, null) }, onClick = { overflowOpen = false; onTurnInto(TextBlockTarget.Divider) })
                        DropdownMenuItem(text = { Text("Code block") }, leadingIcon = { Icon(Icons.Outlined.Code, null) }, onClick = { overflowOpen = false; onTurnInto(TextBlockTarget.Code) })
                        DropdownMenuItem(text = { Text("Insert table") }, leadingIcon = { Icon(Icons.Outlined.TableChart, null) }, onClick = { overflowOpen = false; onInsert(InsertBlockType.Table) })
                        DropdownMenuItem(text = { Text("Insert image") }, leadingIcon = { Icon(Icons.Outlined.Image, null) }, onClick = { overflowOpen = false; onInsert(InsertBlockType.Image) })
                    }
                }
            }
        }
        if (expanded) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            LazyRow(
                modifier = Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                item("font-sans") {
                    ToolbarTextButton("Aa", "Use sans-serif document font", if (fontFamily == EditorFontFamily.Sans) FontWeight.Black else FontWeight.Normal) {
                        onFontFamily(EditorFontFamily.Sans)
                    }
                }
                item("font-serif") {
                    ToolbarTextButton("Ag", "Use serif document font", if (fontFamily == EditorFontFamily.Serif) FontWeight.Black else FontWeight.Normal, fontFamily = FontFamily.Serif) {
                        onFontFamily(EditorFontFamily.Serif)
                    }
                }
                item("font-smaller") { ToolbarTextButton("A−", "Decrease document font size") { onFontSize(fontSize - 0.1f) } }
                item("font-size") { Text("${(85 + fontSize.coerceIn(0f, 1f) * 45).roundToInt()}%", Modifier.padding(horizontal = 8.dp), fontSize = 12.sp, fontWeight = FontWeight.Bold) }
                item("font-larger") { ToolbarTextButton("A+", "Increase document font size") { onFontSize(fontSize + 0.1f) } }
                item("h1") { ToolbarTextButton("H1", "Heading 1") { onTurnInto(TextBlockTarget.Heading1) } }
                item("h2") { ToolbarTextButton("H2", "Heading 2") { onTurnInto(TextBlockTarget.Heading2) } }
                item("quote-expanded") { ToolbarIconButton(Icons.Outlined.FormatQuote, "Quote") { onTurnInto(TextBlockTarget.Quote) } }
                item("table-expanded") { ToolbarIconButton(Icons.Outlined.TableChart, "Insert table") { onInsert(InsertBlockType.Table) } }
                item("image-expanded") { ToolbarIconButton(Icons.Outlined.Image, "Insert image") { onInsert(InsertBlockType.Image) } }
            }
        }
        }
    }
}

@Composable
private fun ToolbarTextButton(
    label: String,
    description: String,
    fontWeight: FontWeight = FontWeight.SemiBold,
    fontStyle: FontStyle = FontStyle.Normal,
    textDecoration: TextDecoration = TextDecoration.None,
    fontFamily: FontFamily? = null,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier.size(width = 44.dp, height = 40.dp).semantics { contentDescription = description },
        shape = RoundedCornerShape(10.dp),
        color = Color.Transparent,
        onClick = onClick,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(label, fontWeight = fontWeight, fontStyle = fontStyle, textDecoration = textDecoration, fontFamily = fontFamily, fontSize = 14.sp)
        }
    }
}

@Composable
private fun ToolbarIconButton(icon: androidx.compose.ui.graphics.vector.ImageVector, description: String, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(44.dp)) {
        Icon(icon, description, Modifier.size(20.dp))
    }
}

@Composable
private fun LinkEditorDialog(
    request: LinkEditorRequest,
    onDismiss: () -> Unit,
    onSave: (String, String) -> Unit,
) {
    var text by remember(request) { mutableStateOf(request.text) }
    var url by remember(request) { mutableStateOf(request.url) }
    val valid = remember(url) { runCatching { Uri.parse(url.trim()) }.getOrNull()?.scheme in setOf("http", "https", "mailto") }
    NorfoldDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit link") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Text") })
                OutlinedTextField(url, { url = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("URL") }, supportingText = { if (!valid) Text("Use http, https, or mailto") })
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        confirmButton = { Button(onClick = { onSave(text, url) }, enabled = valid) { Text("Apply") } },
    )
}

private fun DocumentBlock.convertTo(target: TextBlockTarget): DocumentBlock {
    val inline = when (this) {
        is ParagraphBlock -> content
        is HeadingBlock -> content
        is BulletListBlock -> items.joinListItemsToInlineLines()
        is NumberedListBlock -> items.joinListItemsToInlineLines()
        is TodoListBlock -> items.joinTodoItemsToInlineLines()
        is QuoteBlock -> children.firstOrNull()?.editableInlineContent() ?: listOf(InlineText(plainText()))
        is CodeBlock -> listOf(InlineText(code))
        is DividerBlock -> emptyList()
        else -> listOf(InlineText(plainText()))
    }
    val listItems = when (this) {
        is BulletListBlock -> items
        is NumberedListBlock -> items
        is TodoListBlock -> items.map { ListItem(id = it.id, content = it.content) }
        else -> listOf(ListItem(content = inline))
    }.ifEmpty { listOf(ListItem()) }
    val todoItems = when (this) {
        is TodoListBlock -> items
        is BulletListBlock -> items.map { TodoItem(id = it.id, content = it.content) }
        is NumberedListBlock -> items.map { TodoItem(id = it.id, content = it.content) }
        else -> listOf(TodoItem(content = inline))
    }.ifEmpty { listOf(TodoItem()) }
    return when (target) {
        TextBlockTarget.Paragraph -> ParagraphBlock(id = id, content = inline)
        TextBlockTarget.Heading1 -> HeadingBlock(id = id, level = 1, content = inline)
        TextBlockTarget.Heading2 -> HeadingBlock(id = id, level = 2, content = inline)
        TextBlockTarget.Heading3 -> HeadingBlock(id = id, level = 3, content = inline)
        TextBlockTarget.Heading4 -> HeadingBlock(id = id, level = 4, content = inline)
        TextBlockTarget.Heading5 -> HeadingBlock(id = id, level = 5, content = inline)
        TextBlockTarget.Heading6 -> HeadingBlock(id = id, level = 6, content = inline)
        TextBlockTarget.Bullet -> BulletListBlock(id = id, items = listItems)
        TextBlockTarget.Numbered -> NumberedListBlock(id = id, items = listItems)
        TextBlockTarget.Checklist -> TodoListBlock(id = id, items = todoItems)
        TextBlockTarget.Quote -> QuoteBlock(id = id, children = listOf(ParagraphBlock(content = inline)))
        TextBlockTarget.Code -> CodeBlock(id = id, code = inline.joinToString("") { it.plainText() })
        TextBlockTarget.Divider -> DividerBlock(id = id)
    }
}

private fun DocumentBlock.editableInlineContent(): List<InlineNode>? = when (this) {
    is ParagraphBlock -> content
    is HeadingBlock -> content
    else -> null
}

private fun List<ListItem>.joinListItemsToInlineLines(): List<InlineNode> = buildList {
    this@joinListItemsToInlineLines.forEachIndexed { index, item ->
        if (index > 0) add(InlineText("\n"))
        addAll(item.content)
    }
}

private fun List<TodoItem>.joinTodoItemsToInlineLines(): List<InlineNode> = buildList {
    this@joinTodoItemsToInlineLines.forEachIndexed { index, item ->
        if (index > 0) add(InlineText("\n"))
        addAll(item.content)
    }
}

private fun BlockDocument.linkEditorRequest(selection: EditorSelection): LinkEditorRequest? {
    if (selection.start.blockId != selection.end.blockId || selection.start.itemId != selection.end.itemId) return null
    val block = blocks.firstOrNull { it.id == selection.start.blockId } ?: return null
    val nodes = block.inlineContent(selection.start.itemId) ?: return null
    val cursorStart = minOf(selection.start.offset, selection.end.offset)
    val cursorEnd = maxOf(selection.start.offset, selection.end.offset)
    return nodes.linkRanges().firstOrNull { range ->
        if (cursorStart == cursorEnd) cursorStart in range.start..range.end else cursorStart < range.end && cursorEnd > range.start
    }?.let { range ->
        LinkEditorRequest(
            start = BlockCursor(block.id, range.start, selection.start.itemId),
            end = BlockCursor(block.id, range.end, selection.start.itemId),
            text = range.link.children.joinToString("") { it.plainText() },
            url = range.link.url,
        )
    }
}

private fun DocumentBlock.inlineContent(itemId: String?): List<InlineNode>? = when (this) {
    is ParagraphBlock -> content.takeIf { itemId == null }
    is HeadingBlock -> content.takeIf { itemId == null }
    is BulletListBlock -> items.firstOrNull { it.id == itemId }?.content
    is NumberedListBlock -> items.firstOrNull { it.id == itemId }?.content
    is TodoListBlock -> items.firstOrNull { it.id == itemId }?.content
    is QuoteBlock -> children.firstOrNull()?.editableInlineContent()?.takeIf { itemId == null }
    else -> null
}

private data class LinkRange(val start: Int, val end: Int, val link: LinkInline)

private fun List<InlineNode>.linkRanges(): List<LinkRange> {
    val result = mutableListOf<LinkRange>()
    var offset = 0
    fun visit(nodes: List<InlineNode>) {
        nodes.forEach { node ->
            val start = offset
            when (node) {
                is BoldInline -> visit(node.children)
                is ItalicInline -> visit(node.children)
                is StrikethroughInline -> visit(node.children)
                is LinkInline -> {
                    visit(node.children)
                    result += LinkRange(start, offset, node)
                }
                else -> offset += node.plainText().length
            }
        }
    }
    visit(this)
    return result
}

@Composable
private fun DocumentRangeToolbar(
    onReplace: (String) -> Unit,
    onCancel: () -> Unit,
) {
    var replacement by remember { mutableStateOf(TextFieldValue("")) }
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
    ) {
        Row(Modifier.padding(start = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            BasicTextField(
                value = replacement,
                onValueChange = { replacement = it },
                modifier = Modifier.weight(1f).padding(vertical = 12.dp).onPreviewKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown && event.key == Key.Backspace && replacement.text.isEmpty()) {
                        onReplace("")
                        true
                    } else false
                },
                textStyle = TextStyle(fontSize = 14.sp, color = MaterialTheme.colorScheme.onPrimaryContainer),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onReplace(replacement.text) }),
                decorationBox = { inner ->
                    Box {
                        if (replacement.text.isEmpty()) Text("Type to replace selected blocks", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        inner()
                    }
                },
            )
            TextButton(onClick = { onReplace(replacement.text) }, enabled = replacement.text.isNotEmpty()) { Text("Replace") }
            IconButton(onClick = { onReplace("") }) { Icon(Icons.Outlined.Delete, "Delete selected blocks") }
            IconButton(onClick = onCancel) { Icon(Icons.Outlined.Close, "Cancel selection") }
        }
    }
}

private enum class BlockSurfaceMode { View, Edit }
private enum class InsertBlockType { Text, Heading, BulletList, NumberedList, TodoList, Quote, Callout, Container, Divider, Code, Table, Image, File, Embed, Chart, Math, Mermaid }

/** Snap distance (dp) for the free-overlap alignment guides. */
private const val FreeformSnapThresholdDp = 6f

/**
 * Pan/zoom viewport for the Infinite-page canvas: [offset] is the world origin's screen position
 * in px, [scale] the zoom factor. World coordinates stay in dp (matching [FreeformPlacement]).
 * Hoisted to the editor so the sidebar ToC scroll bridge can pan it from outside the canvas.
 */
@Stable
class FreeformViewport {
    var offset by mutableStateOf(Offset.Zero)
    var scale by mutableFloatStateOf(1f)
}

/**
 * Infinite-page canvas ([DocOverlapMode.Overlap]): top-level blocks float at absolute dp positions
 * with explicit size and z-order instead of the stacked Reflow list, so anything can be dropped on
 * top of anything (a callout positioned over an image *is* the overlay). The whole canvas pans and
 * pinch-zooms through [FreeformViewport] (same transform machinery as the workspace canvas board).
 *
 * Manipulation is selection-based: in edit mode a tap selects a block (an input overlay keeps the
 * tap from reaching the block's content), dragging anywhere on the selected block moves it, and the
 * resize handle + layer menu render only on the selection. Tapping the selected block again
 * "enters" it — the overlay lifts so its content becomes editable; the background tap clears the
 * selection before it exits edit mode. Move/resize gestures update a transient local map and
 * persist on gesture end; z always comes from the stored layout so the layer menu applies
 * instantly. While a block moves, other blocks' edges/centers within [FreeformSnapThresholdDp]
 * snap the drag and draw dashed guides.
 */
@Composable
private fun FreeformDocCanvas(
    blocks: List<DocumentBlock>,
    layout: Map<String, FreeformPlacement>,
    canvasSpec: DocCanvasSpec?,
    editMode: Boolean,
    onSeedLayout: (Map<String, FreeformPlacement>) -> Unit,
    onCommitPlacement: (String, FreeformPlacement) -> Unit,
    onBringToFront: (String) -> Unit,
    onBringForward: (String) -> Unit,
    onSendBackward: (String) -> Unit,
    onSendToBack: (String) -> Unit,
    onBackgroundTap: () -> Unit,
    onBackgroundDoubleTap: () -> Unit,
    viewport: FreeformViewport,
    modifier: Modifier = Modifier,
    renderBlock: @Composable (DocumentBlock, contentEditing: Boolean) -> Unit,
) {
    // Blocks without a stored placement (first entry into Overlap mode, or newly inserted) cascade
    // down the page below the lowest existing block, each on its own fresh top layer.
    val effectiveLayout = remember(blocks, layout) {
        val merged = layout.toMutableMap()
        var nextY = (layout.values.maxOfOrNull { it.y + it.height } ?: 0f) + 16f
        var nextZ = (layout.values.maxOfOrNull { it.z } ?: -1) + 1
        blocks.forEach { block ->
            if (block.id !in merged) {
                merged[block.id] = FreeformPlacement(x = 16f, y = nextY, width = 328f, height = 160f, z = nextZ++)
                nextY += 176f
            }
        }
        merged
    }
    LaunchedEffect(layout, blocks) {
        if (blocks.any { it.id !in layout }) {
            onSeedLayout(effectiveLayout.filterKeys { id -> blocks.any { it.id == id } })
        }
    }
    // Gesture closures survive recomposition, so they must read layout/blocks through these.
    val latestLayout = rememberUpdatedState(effectiveLayout)
    val latestBlocks = rememberUpdatedState(blocks)
    // In-flight move/resize overrides; an entry clears once the persisted layout catches up so the
    // block never snaps back while the write round-trips through the repository.
    val localPlacements = remember { mutableStateMapOf<String, FreeformPlacement>() }
    LaunchedEffect(layout) {
        localPlacements.keys.toList().forEach { id ->
            val local = localPlacements[id] ?: return@forEach
            val stored = layout[id] ?: return@forEach
            if (stored.copy(z = 0) == local.copy(z = 0)) localPlacements.remove(id)
        }
    }
    fun displayed(id: String, source: Map<String, FreeformPlacement> = effectiveLayout): FreeformPlacement {
        val stored = source[id] ?: FreeformPlacement()
        return (localPlacements[id] ?: stored).copy(z = stored.z)
    }
    var movingId by remember { mutableStateOf<String?>(null) }
    // Selection: tap selects, tapping the selection again enters it for content editing.
    var selectedId by remember { mutableStateOf<String?>(null) }
    var enteredId by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(editMode) {
        if (!editMode) {
            selectedId = null
            enteredId = null
        }
    }
    val verticalGuides = remember { mutableStateListOf<Float>() }
    val horizontalGuides = remember { mutableStateListOf<Float>() }
    fun clearGuides() {
        verticalGuides.clear()
        horizontalGuides.clear()
    }
    // Nearest own-line → other-line match within threshold; returns (delta to apply, guide line).
    fun snapDelta(own: List<Float>, targets: List<Float>): Pair<Float, Float>? {
        var best: Pair<Float, Float>? = null
        own.forEach { o ->
            targets.forEach { t ->
                val d = t - o
                if (abs(d) <= FreeformSnapThresholdDp && (best == null || abs(d) < abs(best!!.first))) best = d to t
            }
        }
        return best
    }
    val zOrder = effectiveLayout.entries.sortedWith(compareBy({ it.value.z }, { it.key })).map { it.key }
    val bounded = canvasSpec?.normalized()
    val contentWidth = (bounded?.width ?: ((blocks.maxOfOrNull { block -> displayed(block.id).let { it.x + it.width } } ?: 480f) + 320f)).dp
    val contentHeight = (bounded?.totalHeight ?: ((blocks.maxOfOrNull { block -> displayed(block.id).let { it.y + it.height } } ?: 480f) + 320f)).dp
    val guideColor = MaterialTheme.colorScheme.primary
    val pageColor = MaterialTheme.colorScheme.surface
    val pageOutline = MaterialTheme.colorScheme.outlineVariant
    Box(
        modifier
            .clipToBounds()
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = {
                        // First tap calms the canvas (clears selection); only a tap on an already
                        // calm background exits edit mode.
                        if (selectedId != null || enteredId != null) {
                            selectedId = null
                            enteredId = null
                        } else {
                            onBackgroundTap()
                        }
                    },
                    onDoubleTap = { onBackgroundDoubleTap() },
                )
            }
            .pointerInput(Unit) {
                // Screen-space pinch-zoom + pan; zoom anchors at the gesture centroid so the point
                // under the fingers stays put. Block-level drags consume their events first, which
                // cancels this detector for that gesture.
                detectTransformGestures { centroid, pan, zoom, _ ->
                    val oldScale = viewport.scale
                    val newScale = (oldScale * zoom).coerceIn(0.5f, 2.5f)
                    viewport.offset = centroid - (centroid - viewport.offset) * (newScale / oldScale) + pan
                    viewport.scale = newScale
                }
            },
    ) {
        Box(
            Modifier
                .wrapContentSize(align = Alignment.TopStart, unbounded = true)
                .graphicsLayer {
                    transformOrigin = TransformOrigin(0f, 0f)
                    translationX = viewport.offset.x
                    translationY = viewport.offset.y
                    scaleX = viewport.scale
                    scaleY = viewport.scale
                }
                .size(contentWidth, contentHeight)
                .drawBehind {
                    bounded?.let { spec ->
                        repeat(spec.pageCount) { pageIndex ->
                            val top = (pageIndex * (spec.height + spec.pageGap)).dp.toPx()
                            val height = spec.height.dp.toPx()
                            drawRect(pageColor, topLeft = Offset(0f, top), size = androidx.compose.ui.geometry.Size(size.width, height))
                            drawRect(pageOutline, topLeft = Offset(0f, top), size = androidx.compose.ui.geometry.Size(size.width, height), style = androidx.compose.ui.graphics.drawscope.Stroke(1.dp.toPx()))
                        }
                    }
                    if (verticalGuides.isEmpty() && horizontalGuides.isEmpty()) return@drawBehind
                    val dash = PathEffect.dashPathEffect(floatArrayOf(10.dp.toPx(), 6.dp.toPx()))
                    verticalGuides.forEach { x ->
                        drawLine(guideColor, Offset(x.dp.toPx(), 0f), Offset(x.dp.toPx(), size.height), strokeWidth = 1.5.dp.toPx(), pathEffect = dash)
                    }
                    horizontalGuides.forEach { y ->
                        drawLine(guideColor, Offset(0f, y.dp.toPx()), Offset(size.width, y.dp.toPx()), strokeWidth = 1.5.dp.toPx(), pathEffect = dash)
                    }
                },
        ) {
            blocks.forEach { block ->
                key(block.id) {
                    val placement = displayed(block.id)
                    val moving = movingId == block.id
                    val selected = selectedId == block.id
                    val entered = enteredId == block.id
                    var layerMenu by remember { mutableStateOf(false) }
                    Surface(
                        color = if (editMode) MaterialTheme.colorScheme.surface.copy(alpha = 0.96f) else Color.Transparent,
                        shape = RoundedCornerShape(10.dp),
                        tonalElevation = if (editMode) 1.dp else 0.dp,
                        modifier = Modifier
                            .offset { IntOffset(placement.x.dp.roundToPx(), placement.y.dp.roundToPx()) }
                            .size(placement.width.dp, placement.height.dp)
                            .zIndex(placement.z.toFloat() + if (moving) 1000f else 0f)
                            .dragLift(lifted = moving, cornerRadius = 10.dp)
                            .then(
                                when {
                                    selected || entered -> Modifier.border(1.5.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(10.dp))
                                    editMode -> Modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(10.dp))
                                    else -> Modifier
                                },
                            ),
                    ) {
                        Box(Modifier.fillMaxSize()) {
                            Box(
                                Modifier
                                    .fillMaxSize()
                                    .clip(RoundedCornerShape(10.dp))
                                    .padding(horizontal = if (editMode) 8.dp else 0.dp, vertical = if (editMode) 6.dp else 0.dp),
                            ) { renderBlock(block, entered) }
                            if (editMode && !entered) {
                                // Input overlay: intercepts pointer input so a tap selects (then enters)
                                // the block instead of reaching its content, and dragging anywhere on the
                                // selected block moves it. Lifts once the block is entered for editing.
                                Box(
                                    Modifier
                                        .matchParentSize()
                                        .pointerInput(block.id) {
                                            detectTapGestures {
                                                if (selectedId == block.id) {
                                                    enteredId = block.id
                                                } else {
                                                    selectedId = block.id
                                                    enteredId = null
                                                }
                                            }
                                        }
                                        .pointerInput(block.id, selected) {
                                            if (!selected) return@pointerInput
                                            detectDragGestures(
                                                onDragStart = { movingId = block.id },
                                                onDragEnd = {
                                                    movingId = null
                                                    clearGuides()
                                                    localPlacements[block.id]?.let { onCommitPlacement(block.id, it) }
                                                },
                                                onDragCancel = {
                                                    movingId = null
                                                    clearGuides()
                                                    localPlacements.remove(block.id)
                                                },
                                            ) { change, dragAmount ->
                                                change.consume()
                                                val stored = latestLayout.value[block.id] ?: FreeformPlacement()
                                                val current = localPlacements[block.id] ?: stored
                                                var nx = current.x + dragAmount.x.toDp().value
                                                var ny = (current.y + dragAmount.y.toDp().value).coerceAtLeast(0f)
                                                clearGuides()
                                                val others = latestBlocks.value
                                                    .filter { it.id != block.id }
                                                    .map { displayed(it.id, latestLayout.value) }
                                                snapDelta(
                                                    listOf(nx, nx + current.width / 2f, nx + current.width),
                                                    others.flatMap { listOf(it.x, it.x + it.width / 2f, it.x + it.width) },
                                                )?.let { (d, guide) ->
                                                    nx += d
                                                    verticalGuides.add(guide)
                                                }
                                                snapDelta(
                                                    listOf(ny, ny + current.height / 2f, ny + current.height),
                                                    others.flatMap { listOf(it.y, it.y + it.height / 2f, it.y + it.height) },
                                                )?.let { (d, guide) ->
                                                    ny += d
                                                    horizontalGuides.add(guide)
                                                }
                                                bounded?.let { spec ->
                                                    nx = nx.coerceIn(0f, (spec.width - current.width).coerceAtLeast(0f))
                                                    ny = ny.coerceIn(0f, (spec.totalHeight - current.height).coerceAtLeast(0f))
                                                }
                                                localPlacements[block.id] = current.copy(x = nx, y = ny)
                                            }
                                        },
                                )
                            }
                            if (selected || entered) {
                                Box(Modifier.align(Alignment.TopEnd).padding(2.dp)) {
                                    Icon(
                                        Icons.Outlined.Layers,
                                        contentDescription = "Layer options",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier
                                            .size(26.dp)
                                            .clip(CircleShape)
                                            .clickable { layerMenu = true }
                                            .padding(4.dp),
                                    )
                                    DropdownMenu(expanded = layerMenu, onDismissRequest = { layerMenu = false }) {
                                        Text(
                                            "Layer ${zOrder.indexOf(block.id) + 1} of ${zOrder.size}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                                        )
                                        DropdownMenuItem(text = { Text("Bring to front") }, onClick = { layerMenu = false; onBringToFront(block.id) })
                                        DropdownMenuItem(text = { Text("Bring forward") }, onClick = { layerMenu = false; onBringForward(block.id) })
                                        DropdownMenuItem(text = { Text("Send backward") }, onClick = { layerMenu = false; onSendBackward(block.id) })
                                        DropdownMenuItem(text = { Text("Send to back") }, onClick = { layerMenu = false; onSendToBack(block.id) })
                                    }
                                }
                                // Corner resize handle, shown only on the selection.
                                Box(
                                    Modifier
                                        .align(Alignment.BottomEnd)
                                        .padding(3.dp)
                                        .size(16.dp)
                                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.85f), CircleShape)
                                        .pointerInput(block.id) {
                                            detectDragGestures(
                                                onDragEnd = {
                                                    localPlacements[block.id]?.let { onCommitPlacement(block.id, it) }
                                                },
                                                onDragCancel = { localPlacements.remove(block.id) },
                                            ) { change, dragAmount ->
                                                change.consume()
                                                val stored = latestLayout.value[block.id] ?: FreeformPlacement()
                                                val current = localPlacements[block.id] ?: stored
                                                val maxWidth = bounded?.let { (it.width - current.x).coerceAtLeast(120f) } ?: Float.MAX_VALUE
                                                val maxHeight = bounded?.let { (it.totalHeight - current.y).coerceAtLeast(64f) } ?: Float.MAX_VALUE
                                                localPlacements[block.id] = current.copy(
                                                    width = (current.width + dragAmount.x.toDp().value).coerceIn(120f, maxWidth),
                                                    height = (current.height + dragAmount.y.toDp().value).coerceIn(64f, maxHeight),
                                                )
                                            }
                                        },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** ISO A4 portrait aspect: page height = width × √2. */
private const val PageAspectRatio = 1.41421f

/**
 * Page-mode paginated render path (WYSIWYG preview): every top-level block is measured once at
 * the page content width, then packed onto fixed A4-proportioned pages by [DocPagination] —
 * breaks fall only between blocks, and a block taller than a page gets its own page clipped to
 * the page bounds. Read-only by design: double-tap enters edit mode, which always falls back to
 * the reflow list, keeping that proven path authoritative for every mutation.
 */
@Composable
private fun PaginatedDocPages(
    blocks: List<DocumentBlock>,
    onBackgroundDoubleTap: () -> Unit,
    modifier: Modifier = Modifier,
    renderBlock: @Composable (DocumentBlock) -> Unit,
) {
    val pageColor = MaterialTheme.colorScheme.surface
    val pageOutline = MaterialTheme.colorScheme.outlineVariant
    val pageLabel = MaterialTheme.colorScheme.onSurfaceVariant
    Box(
        modifier
            .verticalScroll(rememberScrollState())
            .pointerInput(Unit) { detectTapGestures(onDoubleTap = { onBackgroundDoubleTap() }) }
            .padding(horizontal = 20.dp, vertical = 12.dp),
    ) {
        SubcomposeLayout(Modifier.fillMaxWidth()) { constraints ->
            val pageWidth = constraints.maxWidth
            val pageHeight = (pageWidth * PageAspectRatio).roundToInt()
            val margin = 32.dp.roundToPx()
            val pageGap = 20.dp.roundToPx()
            val spacing = 6.dp.roundToPx()
            val contentWidth = (pageWidth - margin * 2).coerceAtLeast(1)
            val contentHeight = (pageHeight - margin * 2).coerceAtLeast(1)

            // One measurement per block; the clipToBounds wrapper caps oversized blocks at the
            // page content bounds instead of letting them bleed across the page gap.
            val blockPlaceables = blocks.map { block ->
                subcompose("block-${block.id}") {
                    Box(Modifier.clipToBounds()) { renderBlock(block) }
                }.first().measure(Constraints(maxWidth = contentWidth, maxHeight = contentHeight))
            }
            val pages = DocPagination.paginate(
                heights = blockPlaceables.map { it.height.toFloat() },
                pageHeight = contentHeight.toFloat(),
                spacing = spacing.toFloat(),
            )
            val pagePlaceables = pages.indices.map { index ->
                subcompose("page-$index") {
                    Surface(
                        color = pageColor,
                        shape = RoundedCornerShape(6.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, pageOutline),
                        shadowElevation = 2.dp,
                    ) {
                        Box(Modifier.fillMaxSize()) {
                            Text(
                                "${index + 1} / ${pages.size}",
                                Modifier.align(Alignment.BottomCenter).padding(bottom = 10.dp),
                                fontSize = 11.sp,
                                color = pageLabel,
                            )
                        }
                    }
                }.first().measure(Constraints.fixed(pageWidth, pageHeight))
            }
            val totalHeight = if (pages.isEmpty()) 0 else pages.size * pageHeight + (pages.size - 1) * pageGap
            layout(pageWidth, totalHeight) {
                pages.forEachIndexed { index, range ->
                    val pageTop = index * (pageHeight + pageGap)
                    pagePlaceables[index].place(0, pageTop)
                    var y = pageTop + margin
                    for (blockIndex in range) {
                        blockPlaceables[blockIndex].place(margin, y)
                        y += blockPlaceables[blockIndex].height + spacing
                    }
                }
            }
        }
    }
}

@Composable
private fun BlockEditorHeader(
    title: String,
    editMode: Boolean,
    canUndo: Boolean,
    canRedo: Boolean,
    onTitleChange: (String) -> Unit,
    onBack: () -> Unit,
    onMenu: () -> Unit,
    onToggleEdit: () -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    overlapMode: DocOverlapMode,
    onSetOverlapMode: (DocOverlapMode) -> Unit,
    canvasSpec: DocCanvasSpec,
    minimumCanvasPages: Int,
    onSetCanvasSpec: (DocCanvasSpec) -> Unit,
    paginatedView: Boolean,
    onTogglePaginatedView: () -> Unit,
    onExportPdf: () -> Unit,
    onExportDocx: () -> Unit,
) {
    var titleValue by remember { mutableStateOf(TextFieldValue(title)) }
    var titleFocused by remember { mutableStateOf(false) }
    LaunchedEffect(title, titleFocused) {
        if (!titleFocused && titleValue.composition == null && titleValue.text != title) {
            titleValue = titleValue.copy(
                text = title,
                selection = TextRange(titleValue.selection.start.coerceAtMost(title.length), titleValue.selection.end.coerceAtMost(title.length)),
                composition = null,
            )
        }
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") }
            IconButton(onClick = onMenu) { Icon(Icons.Outlined.Menu, "Workspace") }
            if (editMode) {
                BasicTextField(
                    value = titleValue,
                    onValueChange = { changed -> titleValue = changed; onTitleChange(changed.text) },
                    modifier = Modifier.weight(1f).padding(horizontal = 4.dp).onFocusChanged { titleFocused = it.isFocused },
                    singleLine = false,
                    maxLines = 3,
                    textStyle = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                )
            } else {
                Text(title, Modifier.weight(1f).padding(horizontal = 4.dp), fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 3)
            }
            AnimatedContent(editMode, label = "editor-mode") { editing ->
                if (editing) Row {
                    IconButton(onClick = onUndo, enabled = canUndo) { Icon(Icons.AutoMirrored.Outlined.Undo, "Undo") }
                    IconButton(onClick = onRedo, enabled = canRedo) { Icon(Icons.AutoMirrored.Outlined.Redo, "Redo") }
                } else IconButton(onClick = onToggleEdit) { Icon(Icons.Outlined.Edit, "Edit document") }
            }
            Box {
                var showDocSettings by remember { mutableStateOf(false) }
                IconButton(onClick = { showDocSettings = true }) { Icon(Icons.Outlined.MoreVert, "Document settings") }
                DropdownMenu(expanded = showDocSettings, onDismissRequest = { showDocSettings = false }) {
                    Text(
                        "Document settings",
                        Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text("Flow document")
                                Text(
                                    "Blocks flow in order and adapt to new space",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        },
                        leadingIcon = {
                            if (overlapMode == DocOverlapMode.Reflow) {
                                Icon(Icons.Outlined.Check, null, tint = MaterialTheme.colorScheme.primary)
                            } else Spacer(Modifier.size(24.dp))
                        },
                        onClick = {
                            showDocSettings = false
                            if (overlapMode != DocOverlapMode.Reflow) onSetOverlapMode(DocOverlapMode.Reflow)
                        },
                    )
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text("Document canvas")
                                Text(
                                    "Fixed-size pages with movable, resizable layers",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        },
                        leadingIcon = {
                            if (overlapMode == DocOverlapMode.Bounded) {
                                Icon(Icons.Outlined.Check, null, tint = MaterialTheme.colorScheme.primary)
                            } else Spacer(Modifier.size(24.dp))
                        },
                        onClick = {
                            showDocSettings = false
                            if (overlapMode != DocOverlapMode.Bounded) onSetOverlapMode(DocOverlapMode.Bounded)
                        },
                    )
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text("Infinite canvas")
                                Text(
                                    "Free canvas — place and overlap blocks anywhere",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        },
                        leadingIcon = {
                            if (overlapMode == DocOverlapMode.Overlap) {
                                Icon(Icons.Outlined.Check, null, tint = MaterialTheme.colorScheme.primary)
                            } else Spacer(Modifier.size(24.dp))
                        },
                        onClick = {
                            showDocSettings = false
                            if (overlapMode != DocOverlapMode.Overlap) onSetOverlapMode(DocOverlapMode.Overlap)
                        },
                    )
                    if (overlapMode == DocOverlapMode.Bounded) {
                        HorizontalDivider(Modifier.padding(vertical = 4.dp))
                        Text(
                            "Canvas · ${canvasSpec.width.roundToInt()} × ${canvasSpec.height.roundToInt()} pt · ${canvasSpec.pageCount} page${if (canvasSpec.pageCount == 1) "" else "s"}",
                            Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        DropdownMenuItem(text = { Text("A4 portrait") }, onClick = { showDocSettings = false; onSetCanvasSpec(DocCanvasSpec.a4().copy(pageCount = canvasSpec.pageCount)) })
                        DropdownMenuItem(text = { Text("US Letter portrait") }, onClick = { showDocSettings = false; onSetCanvasSpec(DocCanvasSpec.letter().copy(pageCount = canvasSpec.pageCount)) })
                        DropdownMenuItem(text = { Text("US Legal portrait") }, onClick = { showDocSettings = false; onSetCanvasSpec(DocCanvasSpec.legal().copy(pageCount = canvasSpec.pageCount)) })
                        DropdownMenuItem(
                            text = { Text("Swap portrait / landscape") },
                            onClick = { showDocSettings = false; onSetCanvasSpec(canvasSpec.copy(width = canvasSpec.height, height = canvasSpec.width)) },
                        )
                        DropdownMenuItem(
                            text = { Text("Add page") },
                            onClick = { showDocSettings = false; onSetCanvasSpec(canvasSpec.copy(pageCount = canvasSpec.pageCount + 1).normalized()) },
                        )
                        if (canvasSpec.pageCount > minimumCanvasPages) {
                            DropdownMenuItem(
                                text = { Text("Remove last page") },
                                onClick = { showDocSettings = false; onSetCanvasSpec(canvasSpec.copy(pageCount = canvasSpec.pageCount - 1).normalized()) },
                            )
                        }
                    }
                    if (overlapMode == DocOverlapMode.Reflow) {
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text("Paginated view")
                                    Text(
                                        "Preview the document as fixed A4 pages",
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            },
                            leadingIcon = {
                                if (paginatedView) {
                                    Icon(Icons.Outlined.Check, null, tint = MaterialTheme.colorScheme.primary)
                                } else Spacer(Modifier.size(24.dp))
                            },
                            onClick = {
                                showDocSettings = false
                                onTogglePaginatedView()
                            },
                        )
                    }
                    HorizontalDivider(Modifier.padding(vertical = 4.dp))
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text("Export PDF")
                                Text(
                                    "Print or save via the system print dialog",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        },
                        leadingIcon = { Icon(Icons.Outlined.PictureAsPdf, null) },
                        onClick = {
                            showDocSettings = false
                            onExportPdf()
                        },
                    )
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text("Export editable DOCX")
                                Text(
                                    "Preserves headings and text flow; free positioning is flattened",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        },
                        onClick = {
                            showDocSettings = false
                            onExportDocx()
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun SharedBlockRow(
    modifier: Modifier,
    block: DocumentBlock,
    index: Int,
    mode: BlockSurfaceMode,
    focus: BlockCursor?,
    onFocusConsumed: () -> Unit,
    onReplace: (DocumentBlock) -> Unit,
    onEditText: (String, String) -> Unit,
    onReplaceInline: (Int, Int, InlineNode) -> Unit,
    onEditListItem: (String, String, String) -> Unit,
    onEditTodoItem: (String, String, String) -> Unit,
    onSplitListItem: (String, Int) -> Unit,
    onExitListItem: (String) -> Unit,
    onMergeListItem: (String) -> Unit,
    onSplit: (Int) -> Unit,
    onMerge: () -> Unit,
    onMove: (Int) -> Unit,
    dragTarget: (Float) -> Int?,
    onInsert: (InsertBlockType) -> Unit,
    onDelete: () -> Unit,
    onDuplicate: () -> Unit,
    rangeSelectionActive: Boolean,
    selectedForRange: Boolean,
    onStartRangeSelection: () -> Unit,
    onExtendRangeSelection: () -> Unit,
    onEditChart: (ChartBlock) -> Unit,
    onEditMath: (MathBlock) -> Unit,
    onEditDiagram: (MermaidBlock) -> Unit,
    onSelectionChange: (EditorSelection) -> Unit,
    nested: Boolean = false,
    onWrap: (ContainerAxis) -> Unit = {},
    onUnwrap: () -> Unit = {},
    onExtract: () -> Unit = {},
    onToggleAxis: () -> Unit = {},
    onAddToContainer: () -> Unit = {},
    onMoveWithin: (Int) -> Unit = {},
    renderChild: (@Composable (DocumentBlock) -> Unit)? = null,
    onReportBounds: (Rect?) -> Unit = {},
    onDragBegin: () -> Unit = {},
    onDragTo: (Offset) -> Unit = {},
    onDragDrop: () -> Unit = {},
    onDragAbort: () -> Unit = {},
    isBeingDragged: Boolean = false,
    isDragHovered: Boolean = false,
    dragHoverEdge: Edge? = null,
) {
    var menu by remember(block.id) { mutableStateOf(false) }
    var insertMenu by remember(block.id) { mutableStateOf(false) }
    val rangeSelectable = block is ParagraphBlock || block is HeadingBlock
    val menuStyle = LocalContextualMenuStyle.current
    val menuColor = LocalContextualMenuColor.current
    val menuShape = when (menuStyle) {
        ContextualMenuStyle.Pill -> RoundedCornerShape(18.dp)
        ContextualMenuStyle.Block -> RoundedCornerShape(8.dp)
        ContextualMenuStyle.Minimal -> RoundedCornerShape(2.dp)
    }
    val menuContainer = if (menuColor == ContextualMenuColor.AppAccent) {
        MaterialTheme.colorScheme.primaryContainer
    } else MaterialTheme.colorScheme.surface
    // Report window bounds for drag hit-testing; clear on dispose so stale rects never match.
    DisposableEffect(block.id) { onDispose { onReportBounds(null) } }
    val dropHintColor = MaterialTheme.colorScheme.primary
    // While dragged, the source row becomes the placeholder slot: a dashed outline with the content
    // ghosted, so the list keeps its height and neighbors displace around it. The block itself
    // travels with the finger as the floating clone.
    val rowModifier = modifier.fillMaxWidth().let {
        if (mode == BlockSurfaceMode.Edit) it.animateContentSize() else it
    }.let { if (isBeingDragged) it.dropSlotOutline(dropHintColor).alpha(.15f) else it }
    Row(
        rowModifier.background(
            if (selectedForRange) MaterialTheme.colorScheme.primaryContainer.copy(alpha = .55f) else Color.Transparent,
            RoundedCornerShape(6.dp),
        ),
        verticalAlignment = Alignment.Top,
    ) {
        if (mode == BlockSurfaceMode.Edit) {
            // Drag handle: works at any depth. Reports the window pointer while dragging so the host can
            // hit-test across containers and drop (reorder, move into a group, or edge-split into a new row).
            var handleOrigin by remember(block.id) { mutableStateOf(Offset.Zero) }
            var dragPos by remember(block.id) { mutableStateOf(Offset.Zero) }
            Icon(
                Icons.Outlined.DragIndicator,
                "Move block",
                Modifier.padding(top = 9.dp).size(22.dp)
                    .onGloballyPositioned { handleOrigin = it.positionInWindow() }
                    .pointerInput(block.id) {
                        detectDragGestures(
                            onDragStart = { local -> dragPos = handleOrigin + local; onDragBegin(); onDragTo(dragPos) },
                            onDrag = { change, amount -> change.consume(); dragPos += amount; onDragTo(dragPos) },
                            onDragEnd = { onDragDrop() },
                            onDragCancel = { onDragAbort() },
                        )
                    },
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Box {
                IconButton(onClick = { insertMenu = true }, modifier = Modifier.size(38.dp)) { Icon(Icons.Outlined.Add, "Insert below", Modifier.size(18.dp)) }
                InsertBlockMenu(insertMenu, { insertMenu = false }) { insertMenu = false; onInsert(it) }
            }
        }
        Box(
            Modifier.weight(1f)
                .onGloballyPositioned { onReportBounds(it.boundsInWindow()) }
                .drawBehind {
                    if (isDragHovered) {
                        val stroke = 3.dp.toPx()
                        when (dragHoverEdge) {
                            Edge.Left -> drawLine(dropHintColor, Offset(0f, 0f), Offset(0f, size.height), stroke)
                            Edge.Right -> drawLine(dropHintColor, Offset(size.width, 0f), Offset(size.width, size.height), stroke)
                            Edge.Top -> drawLine(dropHintColor, Offset(0f, 0f), Offset(size.width, 0f), stroke)
                            Edge.Bottom -> drawLine(dropHintColor, Offset(0f, size.height), Offset(size.width, size.height), stroke)
                            null -> drawRect(dropHintColor.copy(alpha = .12f))
                        }
                    }
                }
                .then(
                    if (mode == BlockSurfaceMode.View) Modifier.combinedClickable(onClick = {}, onLongClick = { menu = true }) else Modifier,
                ),
        ) {
            RenderBlock(
                block = block,
                mode = mode,
                focus = focus,
                onFocusConsumed = onFocusConsumed,
                onReplace = onReplace,
                onEditText = onEditText,
                onReplaceInline = onReplaceInline,
                onEditListItem = onEditListItem,
                onEditTodoItem = onEditTodoItem,
                onSplitListItem = onSplitListItem,
                onExitListItem = onExitListItem,
                onMergeListItem = onMergeListItem,
                onSplit = onSplit,
                onMerge = onMerge,
                onInsert = onInsert,
                onEditChart = onEditChart,
                onEditMath = onEditMath,
                onEditDiagram = onEditDiagram,
                onSelectionChange = onSelectionChange,
                renderChild = renderChild,
            )
            if (rangeSelectionActive && rangeSelectable) {
                Box(Modifier.matchParentSize().clickable(onClick = onExtendRangeSelection))
            }
        }
        if (mode == BlockSurfaceMode.Edit || menu) {
            Box {
                if (mode == BlockSurfaceMode.Edit) {
                    IconButton(onClick = { menu = true }, modifier = Modifier.size(38.dp)) { Icon(Icons.Outlined.MoreVert, "Block actions", Modifier.size(18.dp)) }
                }
                DropdownMenu(
                    expanded = menu,
                    onDismissRequest = { menu = false },
                    shape = menuShape,
                    containerColor = menuContainer,
                    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                ) {
                    when (block) {
                        is ChartBlock -> DropdownMenuItem({ Text("Edit chart") }, onClick = { menu = false; onEditChart(block) })
                        is ImageBlock -> DropdownMenuItem({ Text("Cycle image layout") }, onClick = {
                            menu = false
                            val next = when (block.layout) {
                                ImageLayout.Fit -> ImageLayout.Wide
                                ImageLayout.Wide -> ImageLayout.Original
                                ImageLayout.Original -> ImageLayout.Fit
                            }
                            onReplace(block.copy(layout = next))
                        })
                        is TableBlock -> DropdownMenuItem({ Text("Add table row") }, onClick = {
                            menu = false
                            val columns = maxOf(block.headers.size, block.rows.maxOfOrNull { it.size } ?: 0, 1)
                            onReplace(block.copy(rows = block.rows + listOf(List(columns) { TableCell() })))
                        })
                        is CodeBlock -> DropdownMenuItem({ Text("Expand code editor") }, onClick = { menu = false; onReplace(block.copy(editorHeightDp = block.editorHeightDp + 80f)) })
                        is MathBlock -> {
                            DropdownMenuItem({ Text("Edit equation") }, onClick = { menu = false; onEditMath(block) })
                            DropdownMenuItem({ Text("Expand math editor") }, onClick = { menu = false; onReplace(block.copy(editorHeightDp = block.editorHeightDp + 80f)) })
                        }
                        is MermaidBlock -> {
                            DropdownMenuItem({ Text("Edit diagram") }, onClick = { menu = false; onEditDiagram(block) })
                            DropdownMenuItem({ Text("Expand diagram editor") }, onClick = { menu = false; onReplace(block.copy(editorHeightDp = block.editorHeightDp + 80f)) })
                        }
                        is FileBlock -> DropdownMenuItem({ Text("Replace file") }, onClick = { menu = false; onReplace(block.copy(uri = "", name = "")) })
                        is EmbedBlock -> DropdownMenuItem({ Text("Refresh preview") }, onClick = { menu = false; onReplace(block.copy(metadata = EmbedMetadata())) })
                        else -> Unit
                    }
                    DropdownMenuItem({ Text("Duplicate") }, onClick = { menu = false; onDuplicate() })
                    if (rangeSelectable) DropdownMenuItem({ Text("Select block range") }, onClick = { menu = false; onStartRangeSelection() })
                    if (block.isTextFamily()) {
                        TextBlockTarget.entries.forEach { target ->
                            DropdownMenuItem(
                                text = { Text("Turn into ${target.label.lowercase()}") },
                                onClick = { menu = false; onReplace(block.convertTo(target)) },
                            )
                        }
                    }
                    // Layout / nesting actions (generic recursive containers).
                    if (mode == BlockSurfaceMode.Edit) {
                        DropdownMenuItem({ Text("Wrap in row") }, onClick = { menu = false; onWrap(ContainerAxis.Row) })
                        DropdownMenuItem({ Text("Wrap in column") }, onClick = { menu = false; onWrap(ContainerAxis.Column) })
                        if (block is ContainerBlock) {
                            DropdownMenuItem({ Text(if (block.axis == ContainerAxis.Row) "Make column" else "Make row") }, onClick = { menu = false; onToggleAxis() })
                            DropdownMenuItem({ Text("Add block to group") }, onClick = { menu = false; onAddToContainer() })
                            DropdownMenuItem({ Text("Ungroup") }, onClick = { menu = false; onUnwrap() })
                        }
                        if (nested) {
                            DropdownMenuItem({ Text("Move up in group") }, onClick = { menu = false; onMoveWithin(-1) })
                            DropdownMenuItem({ Text("Move down in group") }, onClick = { menu = false; onMoveWithin(1) })
                            DropdownMenuItem({ Text("Extract from group") }, onClick = { menu = false; onExtract() })
                        }
                    }
                    DropdownMenuItem({ Text("Delete") }, onClick = { menu = false; onDelete() }, leadingIcon = { Icon(Icons.Outlined.Delete, null) })
                }
            }
        }
    }
}

private fun DocumentBlock.isTextFamily(): Boolean = when (this) {
    is ParagraphBlock, is HeadingBlock, is BulletListBlock, is NumberedListBlock,
    is TodoListBlock, is QuoteBlock, is CodeBlock, is DividerBlock -> true
    else -> false
}

@Composable
private fun BlockCloneContent(block: DocumentBlock) {
    RenderBlock(
        block = block,
        mode = BlockSurfaceMode.View,
        focus = null,
        onFocusConsumed = {},
        onReplace = {},
        onEditText = { _, _ -> },
        onReplaceInline = { _, _, _ -> },
        onEditListItem = { _, _, _ -> },
        onEditTodoItem = { _, _, _ -> },
        onSplitListItem = { _, _ -> },
        onExitListItem = {},
        onMergeListItem = {},
        onSplit = {},
        onMerge = {},
        onInsert = {},
        onEditChart = {},
        onEditMath = {},
        onEditDiagram = {},
        onSelectionChange = {},
        renderChild = { child -> BlockCloneContent(child) },
    )
}

@Composable
private fun RenderBlock(
    block: DocumentBlock,
    mode: BlockSurfaceMode,
    focus: BlockCursor?,
    onFocusConsumed: () -> Unit,
    onReplace: (DocumentBlock) -> Unit,
    onEditText: (String, String) -> Unit,
    onReplaceInline: (Int, Int, InlineNode) -> Unit,
    onEditListItem: (String, String, String) -> Unit,
    onEditTodoItem: (String, String, String) -> Unit,
    onSplitListItem: (String, Int) -> Unit,
    onExitListItem: (String) -> Unit,
    onMergeListItem: (String) -> Unit,
    onSplit: (Int) -> Unit,
    onMerge: () -> Unit,
    onInsert: (InsertBlockType) -> Unit,
    onEditChart: (ChartBlock) -> Unit,
    onEditMath: (MathBlock) -> Unit,
    onEditDiagram: (MermaidBlock) -> Unit,
    onSelectionChange: (EditorSelection) -> Unit,
    renderChild: (@Composable (DocumentBlock) -> Unit)? = null,
) {
    when (block) {
        is UnknownBlock -> Surface(
            Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(10.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .4f),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Unsupported block", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                Text(
                    "Created with a newer version of Norfold. It stays saved exactly as-is and will show once the app updates.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        is ParagraphBlock -> InlineTextBlock(block.content, block.id, mode, focus, onFocusConsumed, onEditText, onReplaceInline, onSplit, onMerge, onInsert, onSelectionChange, TextStyle(fontSize = 16.sp, lineHeight = 25.sp, color = MaterialTheme.colorScheme.onSurface))
        is HeadingBlock -> InlineTextBlock(block.content, block.id, mode, focus, onFocusConsumed, onEditText, onReplaceInline, onSplit, onMerge, onInsert, onSelectionChange, TextStyle(fontSize = when (block.level) { 1 -> 30.sp; 2 -> 24.sp; else -> 20.sp }, lineHeight = 36.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface))
        is BulletListBlock -> if (mode == BlockSurfaceMode.View) ListBlock(block.items.map { it.content }, "•") else EditableList(
            items = block.items,
            marker = { "•" },
            focus = focus,
            onFocusConsumed = onFocusConsumed,
            onChange = onEditListItem,
            onSplit = onSplitListItem,
            onExit = onExitListItem,
            onMerge = onMergeListItem,
            blockId = block.id,
            onSelectionChange = onSelectionChange,
        )
        is NumberedListBlock -> if (mode == BlockSurfaceMode.View) ListBlock(block.items.map { it.content }, "1.") else EditableList(
            items = block.items,
            marker = { "${it + block.start}." },
            focus = focus,
            onFocusConsumed = onFocusConsumed,
            onChange = onEditListItem,
            onSplit = onSplitListItem,
            onExit = onExitListItem,
            onMerge = onMergeListItem,
            blockId = block.id,
            onSelectionChange = onSelectionChange,
        )
        is TodoListBlock -> if (mode == BlockSurfaceMode.View) Column {
            block.items.forEachIndexed { index, item ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(item.checked, { checked -> onReplace(block.copy(items = block.items.toMutableList().apply { this[index] = item.copy(checked = checked) })) })
                    InlineRichText(item.content, Modifier.padding(vertical = 5.dp))
                }
            }
        } else EditableTodos(block, onReplace, onInsert, onEditTodoItem, onSelectionChange)
        is QuoteBlock -> Surface(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .45f), shape = RoundedCornerShape(8.dp)) {
            // View mode renders quoted children recursively so nested quotes/lists keep their
            // structure and depth; edit mode keeps the first child inline-editable as before.
            if (mode == BlockSurfaceMode.View && renderChild != null) {
                Column(Modifier.padding(start = 6.dp)) {
                    block.children.forEach { child -> renderChild(child) }
                }
            } else Column {
                val first = block.children.firstOrNull()
                val content = first?.editableInlineContent() ?: listOf(InlineText(first?.plainText() ?: block.plainText()))
                InlineTextBlock(
                    content = content,
                    id = block.id,
                    mode = mode,
                    focus = focus,
                    onFocusConsumed = onFocusConsumed,
                    onText = onEditText,
                    onReplaceInline = onReplaceInline,
                    onSplit = onSplit,
                    onMerge = onMerge,
                    onInsert = onInsert,
                    onSelectionChange = onSelectionChange,
                    style = TextStyle(fontSize = 16.sp, lineHeight = 25.sp, fontStyle = FontStyle.Italic, color = MaterialTheme.colorScheme.onSurface),
                )
                // Additional quoted blocks (multi-paragraph / nested content) render read-only so
                // they are never silently dropped; the first child stays inline-editable above.
                block.children.drop(1).forEach { child ->
                    InlineRichText(
                        nodes = child.editableInlineContent() ?: listOf(InlineText(child.plainText())),
                        modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                        style = TextStyle(fontSize = 16.sp, lineHeight = 25.sp, fontStyle = FontStyle.Italic, color = MaterialTheme.colorScheme.onSurface),
                    )
                }
            }
        }
        is CalloutBlock -> Surface(color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = .45f), shape = RoundedCornerShape(10.dp)) { Column(Modifier.padding(12.dp)) {
            if (mode == BlockSurfaceMode.Edit) {
                SimpleBlockTextField(block.title, { onReplace(block.copy(title = it)) }, Modifier.fillMaxWidth(), fontWeight = FontWeight.Bold)
                SimpleBlockTextField(block.children.joinToString("\n") { it.plainText() }, { onReplace(block.copy(children = listOf(ParagraphBlock(content = listOf(InlineText(it)))))) }, Modifier.fillMaxWidth().padding(top = 6.dp))
            } else {
                Text(block.title, fontWeight = FontWeight.Bold)
                block.children.forEach { child ->
                    InlineRichText(
                        nodes = child.editableInlineContent() ?: listOf(InlineText(child.plainText())),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        } }
        is ContainerBlock -> ContainerBlockView(block, mode, renderChild)
        is DividerBlock -> Spacer(Modifier.fillMaxWidth().padding(vertical = 14.dp).height(1.dp).background(MaterialTheme.colorScheme.outlineVariant))
        is CodeBlock -> Column {
            if (mode == BlockSurfaceMode.Edit) SimpleLabeledBlockField("Language", block.language) { onReplace(block.copy(language = it.trim())) }
            EditableEngineCard(
                blockId = block.id,
                label = "Code${block.language.takeIf(String::isNotBlank)?.let { " · $it" }.orEmpty()}",
                source = block.code,
                mode = mode,
                editorHeightDp = block.editorHeightDp,
                onSourceChange = { onReplace(block.copy(code = it)) },
                onHeightChange = { onReplace(block.copy(editorHeightDp = it)) },
                contentKind = EngineContentKind.Code,
            )
        }
        is TableBlock -> NativeTable(block, mode, onReplace)
        is ImageBlock -> EditableImageBlock(block, mode, onReplace)
        is FileBlock -> EditableFileBlock(block, mode, onReplace)
        is EmbedBlock -> EditableEmbedBlock(block, mode, onReplace)
        is ChartBlock -> EditableChartBlock(block, mode, onReplace, onEditChart)
        is MathBlock -> EditableMathBlock(block, mode, onReplace, onEditMath)
        is MermaidBlock -> EditableMermaidBlock(block, mode, onReplace, onEditDiagram)
    }
}

/**
 * Renders a [ContainerBlock] recursively. `Row` lays children side-by-side with per-child flex [weights];
 * `Column` stacks them. Each child is drawn through [renderChild] (a fully-wired [SharedBlockRow] from the
 * host), so nested blocks stay editable at any depth. In preview the container is borderless — just the
 * laid-out children; in Edit mode a light frame + axis label makes the group visible.
 */
@Composable
private fun ContainerBlockView(
    block: ContainerBlock,
    mode: BlockSurfaceMode,
    renderChild: (@Composable (DocumentBlock) -> Unit)?,
) {
    val drawChild: @Composable (DocumentBlock) -> Unit = renderChild ?: { child ->
        InlineRichText(
            nodes = child.editableInlineContent() ?: listOf(InlineText(child.plainText())),
            modifier = Modifier.fillMaxWidth(),
        )
    }
    val content: @Composable () -> Unit = {
        when (block.axis) {
            ContainerAxis.Row -> Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.Top,
            ) {
                block.children.forEachIndexed { index, child ->
                    val weight = block.weights.getOrElse(index) { 1f }.coerceAtLeast(0.05f)
                    Box(Modifier.weight(weight)) { drawChild(child) }
                }
            }
            ContainerAxis.Column -> Column(
                Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                block.children.forEach { child -> drawChild(child) }
            }
        }
    }
    if (mode == BlockSurfaceMode.Edit) {
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .2f),
            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            Column(Modifier.fillMaxWidth().padding(6.dp)) {
                Text(
                    "Layout · ${if (block.axis == ContainerAxis.Row) "row" else "column"}",
                    Modifier.padding(start = 4.dp, bottom = 4.dp),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                content()
            }
        }
    } else {
        content()
    }
}

@Composable
private fun InlineTextBlock(
    content: List<InlineNode>,
    id: String,
    mode: BlockSurfaceMode,
    focus: BlockCursor?,
    onFocusConsumed: () -> Unit,
    onText: (String, String) -> Unit,
    onReplaceInline: (Int, Int, InlineNode) -> Unit,
    onSplit: (Int) -> Unit,
    onMerge: () -> Unit,
    onInsert: (InsertBlockType) -> Unit,
    onSelectionChange: (EditorSelection) -> Unit,
    style: TextStyle,
) {
    val plain = content.joinToString("") { it.plainText() }
    val emojiMap = LocalEmojiShortcodes.current
    if (mode == BlockSurfaceMode.View) {
        InlineRichText(content, Modifier.fillMaxWidth().padding(vertical = 7.dp), style)
    } else {
        var value by remember(id) { mutableStateOf(TextFieldValue(plain)) }
        var slashMenu by remember(id) { mutableStateOf(false) }
        var slashOffset by remember(id) { mutableIntStateOf(-1) }
        var slashQuery by remember(id) { mutableStateOf("") }
        var focused by remember(id) { mutableStateOf(false) }
        var textLayout by remember(id) { mutableStateOf<TextLayoutResult?>(null) }
        val requester = remember(id) { FocusRequester() }
        val bringIntoView = remember(id) { BringIntoViewRequester() }
        val caretBottomClearancePx = with(LocalDensity.current) { 104.dp.toPx() }
        val editingVisual = rememberInlineEditingTransformation(content)

        fun publishSelection(field: TextFieldValue = value) {
            val start = minOf(field.selection.start, field.selection.end).coerceIn(0, field.text.length)
            val end = maxOf(field.selection.start, field.selection.end).coerceIn(start, field.text.length)
            onSelectionChange(
                EditorSelection(
                    start = BlockCursor(id, start),
                    end = BlockCursor(id, end),
                    text = field.text.substring(start, end),
                ),
            )
        }

        LaunchedEffect(plain, focused) {
            if (!focused && value.composition == null && plain != value.text) {
                value = value.copy(
                    text = plain,
                    selection = TextRange(
                        value.selection.start.coerceAtMost(plain.length),
                        value.selection.end.coerceAtMost(plain.length),
                    ),
                    composition = null,
                )
            }
        }
        LaunchedEffect(focus) {
            focus ?: return@LaunchedEffect
            if (plain != value.text && value.composition == null) {
                value = TextFieldValue(plain, selection = TextRange(focus.offset.coerceIn(0, plain.length)))
            }
            value = value.copy(selection = TextRange(focus.offset.coerceIn(0, value.text.length)))
            requester.requestFocus()
            delay(60)
            bringIntoView.bringIntoView()
            publishSelection()
            onFocusConsumed()
        }
        LaunchedEffect(value.text, value.selection, textLayout, focused) {
            if (!focused) return@LaunchedEffect
            val layout = textLayout ?: return@LaunchedEffect
            val cursor = value.selection.end.coerceIn(0, layout.layoutInput.text.length)
            val rect = layout.getCursorRect(cursor)
            bringIntoView.bringIntoView(
                Rect(
                    left = (rect.left - 12f).coerceAtLeast(0f),
                    top = (rect.top - 20f).coerceAtLeast(0f),
                    right = rect.right + 12f,
                    bottom = rect.bottom + caretBottomClearancePx,
                ),
            )
        }
        Box {
            BasicTextField(
                value,
                onValueChange = { changed ->
                    val previous = value
                    val emoji = detectLiveEmoji(previous, changed, emojiMap)
                    val pastedLink = detectUrlPaste(previous, changed)
                    val insertion = if (changed.text.length == previous.text.length + 1) {
                        previous.text.indices.firstOrNull { previous.text[it] != changed.text[it] } ?: previous.text.length
                    } else -1
                    val newline = insertion.takeIf { it >= 0 && changed.text.getOrNull(it) == '\n' }
                    when {
                        pastedLink != null -> {
                            val selectedText = previous.text.substring(pastedLink.start, pastedLink.end)
                            value = previous.copy(selection = TextRange(pastedLink.end))
                            onReplaceInline(
                                pastedLink.start,
                                pastedLink.end,
                                LinkInline(pastedLink.url, listOf(InlineText(selectedText))),
                            )
                            publishSelection(value)
                        }
                        emoji != null -> {
                            value = emoji.value
                            slashMenu = false
                            slashOffset = -1
                            slashQuery = ""
                            onReplaceInline(emoji.start, emoji.oldEnd, EmojiInline(emoji.source, emoji.unicode))
                            publishSelection(value)
                        }
                        newline != null -> onSplit(newline)
                        insertion >= 0 && changed.text.getOrNull(insertion) == '/' -> {
                            slashOffset = insertion
                            slashQuery = ""
                            value = changed
                            onText(previous.text, changed.text)
                            publishSelection(changed)
                            slashMenu = true
                        }
                        else -> {
                            value = changed
                            onText(previous.text, changed.text)
                            publishSelection(changed)
                            if (slashMenu && slashOffset >= 0) {
                                val commandStart = (slashOffset + 1).coerceAtMost(changed.text.length)
                                val end = changed.selection.start.coerceIn(commandStart, changed.text.length)
                                val command = changed.text.substring(commandStart, end)
                                if (changed.text.getOrNull(slashOffset) != '/' || command.any(Char::isWhitespace)) {
                                    slashMenu = false
                                    slashOffset = -1
                                    slashQuery = ""
                                } else slashQuery = command
                            }
                        }
                    }
                },
                textStyle = style,
                visualTransformation = editingVisual,
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                onTextLayout = { textLayout = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 7.dp)
                    .focusRequester(requester)
                    .bringIntoViewRequester(bringIntoView)
                    .onFocusChanged {
                        focused = it.isFocused
                        if (it.isFocused) publishSelection()
                    }
                    .onPreviewKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown && event.key == Key.Backspace && value.selection.collapsed) {
                        val emoji = findEmojiEndingAt(content, value.selection.start)
                        when {
                            emoji != null -> {
                                val previous = value
                                val restored = previous.text.replaceRange(emoji.start, emoji.end, emoji.node.shortcode)
                                value = TextFieldValue(restored, selection = TextRange(emoji.start + emoji.node.shortcode.length))
                                onText(previous.text, restored)
                                publishSelection(value)
                                true
                            }
                            value.selection.start == 0 -> { onMerge(); true }
                            else -> false
                        }
                    } else false
                },
            )
            InsertBlockMenu(
                slashMenu,
                {
                    slashMenu = false
                    slashOffset = -1
                    slashQuery = ""
                },
                slashQuery,
            ) { type ->
                if (slashOffset >= 0 && value.text.getOrNull(slashOffset) == '/') {
                    val previous = value
                    val commandEnd = (slashOffset + 1 + slashQuery.length).coerceAtMost(previous.text.length)
                    val withoutSlash = previous.text.removeRange(slashOffset, commandEnd)
                    value = TextFieldValue(withoutSlash, selection = TextRange(slashOffset))
                    onText(previous.text, withoutSlash)
                }
                slashMenu = false
                slashOffset = -1
                slashQuery = ""
                onInsert(type)
            }
        }
    }
}

private data class UrlPaste(val start: Int, val end: Int, val url: String)

private fun detectUrlPaste(previous: TextFieldValue, changed: TextFieldValue): UrlPaste? {
    if (previous.selection.collapsed) return null
    val start = minOf(previous.selection.start, previous.selection.end).coerceIn(0, previous.text.length)
    val end = maxOf(previous.selection.start, previous.selection.end).coerceIn(start, previous.text.length)
    val prefix = previous.text.substring(0, start)
    val suffix = previous.text.substring(end)
    if (!changed.text.startsWith(prefix) || !changed.text.endsWith(suffix)) return null
    val insertedEnd = changed.text.length - suffix.length
    if (insertedEnd < start) return null
    val candidate = changed.text.substring(start, insertedEnd).trim()
    val uri = runCatching { Uri.parse(candidate) }.getOrNull() ?: return null
    return if (uri.scheme in setOf("http", "https") && !uri.host.isNullOrBlank()) UrlPaste(start, end, candidate) else null
}

@Composable
private fun ListBlock(items: List<List<InlineNode>>, marker: String) {
    Column {
        items.forEachIndexed { index, item ->
            Row(Modifier.padding(vertical = 4.dp)) {
                Text(if (marker == "1.") "${index + 1}." else marker, Modifier.width(28.dp), color = MaterialTheme.colorScheme.primary)
                InlineRichText(item)
            }
        }
    }
}

@Composable
private fun EditableMathBlock(block: MathBlock, mode: BlockSurfaceMode, onReplace: (DocumentBlock) -> Unit, onEditMath: (MathBlock) -> Unit) {
    Column {
        if (mode == BlockSurfaceMode.Edit) {
            Button(onClick = { onEditMath(block) }) { Text("Edit equation") }
        }
        EditableEngineCard(
            blockId = block.id,
            label = "Math",
            source = block.tex,
            mode = mode,
            editorHeightDp = block.editorHeightDp,
            onSourceChange = { onReplace(block.copy(tex = it)) },
            onHeightChange = { onReplace(block.copy(editorHeightDp = it)) },
            contentKind = EngineContentKind.Math,
        )
    }
}

@Composable
private fun EditableMermaidBlock(block: MermaidBlock, mode: BlockSurfaceMode, onReplace: (DocumentBlock) -> Unit, onEditDiagram: (MermaidBlock) -> Unit) {
    Column {
        if (mode == BlockSurfaceMode.Edit) {
            Button(onClick = { onEditDiagram(block) }) { Text("Edit diagram") }
        }
        EditableEngineCard(
            blockId = block.id,
            label = "Diagram",
            source = block.code,
            mode = mode,
            editorHeightDp = block.editorHeightDp,
            onSourceChange = { onReplace(block.copy(code = it)) },
            onHeightChange = { onReplace(block.copy(editorHeightDp = it)) },
            contentKind = EngineContentKind.Diagram,
        )
    }
}

@Composable
private fun EditableChartBlock(block: ChartBlock, mode: BlockSurfaceMode, onReplace: (DocumentBlock) -> Unit, onEditChart: (ChartBlock) -> Unit) {
    Column {
        if (mode == BlockSurfaceMode.Edit) {
            Button(onClick = { onEditChart(block) }) { Text("Edit chart") }
        }
        EditableEngineCard(
            blockId = block.id,
            label = "Chart",
            source = block.vegaLiteSpec,
            mode = mode,
            editorHeightDp = block.editorHeightDp,
            onSourceChange = { onReplace(block.copy(vegaLiteSpec = it)) },
            onHeightChange = { onReplace(block.copy(editorHeightDp = it)) },
            contentKind = EngineContentKind.Chart,
        )
    }
}

@Composable
private fun EditableList(
    blockId: String,
    items: List<com.norfold.app.domain.ListItem>,
    marker: (Int) -> String,
    focus: BlockCursor?,
    onFocusConsumed: () -> Unit,
    onChange: (String, String, String) -> Unit,
    onSplit: (String, Int) -> Unit,
    onExit: (String) -> Unit,
    onMerge: (String) -> Unit,
    onSelectionChange: (EditorSelection) -> Unit,
) {
    Column {
        items.forEachIndexed { index, item ->
            EditableListItem(
                item = item,
                blockId = blockId,
                marker = marker(index),
                focus = focus?.takeIf { it.itemId == item.id },
                onFocusConsumed = onFocusConsumed,
                onChange = { old, new -> onChange(item.id, old, new) },
                onSplit = { offset -> onSplit(item.id, offset) },
                onExit = { onExit(item.id) },
                onMerge = { onMerge(item.id) },
                onSelectionChange = onSelectionChange,
            )
        }
    }
}

@Composable
private fun EditableListItem(
    item: com.norfold.app.domain.ListItem,
    blockId: String,
    marker: String,
    focus: BlockCursor?,
    onFocusConsumed: () -> Unit,
    onChange: (String, String) -> Unit,
    onSplit: (Int) -> Unit,
    onExit: () -> Unit,
    onMerge: () -> Unit,
    onSelectionChange: (EditorSelection) -> Unit,
) {
    val plain = item.content.joinToString("") { it.plainText() }
    var value by remember(item.id) { mutableStateOf(TextFieldValue(plain)) }
    var focused by remember(item.id) { mutableStateOf(false) }
    var textLayout by remember(item.id) { mutableStateOf<TextLayoutResult?>(null) }
    val requester = remember(item.id) { FocusRequester() }
    val bringIntoView = remember(item.id) { BringIntoViewRequester() }
    val caretBottomClearancePx = with(LocalDensity.current) { 104.dp.toPx() }
    val editingVisual = rememberInlineEditingTransformation(item.content)

    fun publishSelection(field: TextFieldValue = value) {
        val start = minOf(field.selection.start, field.selection.end).coerceIn(0, field.text.length)
        val end = maxOf(field.selection.start, field.selection.end).coerceIn(start, field.text.length)
        onSelectionChange(EditorSelection(BlockCursor(blockId, start, item.id), BlockCursor(blockId, end, item.id), field.text.substring(start, end)))
    }

    LaunchedEffect(plain, focused) {
        if (!focused && value.composition == null && plain != value.text) {
            value = value.copy(
                text = plain,
                selection = TextRange(value.selection.start.coerceAtMost(plain.length), value.selection.end.coerceAtMost(plain.length)),
                composition = null,
            )
        }
    }
    LaunchedEffect(focus) {
        focus ?: return@LaunchedEffect
        value = value.copy(selection = TextRange(focus.offset.coerceIn(0, value.text.length)))
        requester.requestFocus()
        delay(60)
        bringIntoView.bringIntoView()
        publishSelection()
        onFocusConsumed()
    }
    LaunchedEffect(value.text, value.selection, textLayout, focused) {
        if (!focused) return@LaunchedEffect
        val layout = textLayout ?: return@LaunchedEffect
        val rect = layout.getCursorRect(value.selection.end.coerceIn(0, layout.layoutInput.text.length))
        bringIntoView.bringIntoView(Rect((rect.left - 12f).coerceAtLeast(0f), (rect.top - 20f).coerceAtLeast(0f), rect.right + 12f, rect.bottom + caretBottomClearancePx))
    }
    Row(verticalAlignment = Alignment.Top) {
        Text(marker, Modifier.width(28.dp).padding(top = 8.dp), color = MaterialTheme.colorScheme.primary)
        BasicTextField(
            value = value,
            onValueChange = { changed ->
                val previous = value
                val newline = changed.text.indexOf('\n')
                when {
                    newline >= 0 && previous.text.isBlank() -> onExit()
                    newline >= 0 -> onSplit(newline)
                    else -> {
                        value = changed
                        onChange(previous.text, changed.text)
                        publishSelection(changed)
                    }
                }
            },
            textStyle = TextStyle(fontSize = 16.sp, lineHeight = 24.sp, color = MaterialTheme.colorScheme.onSurface),
            visualTransformation = editingVisual,
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            onTextLayout = { textLayout = it },
            modifier = Modifier
                .weight(1f)
                .padding(vertical = 7.dp)
                .focusRequester(requester)
                .bringIntoViewRequester(bringIntoView)
                .onFocusChanged { focused = it.isFocused; if (it.isFocused) publishSelection() }
                .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown && event.key == Key.Backspace && value.selection.collapsed && value.selection.start == 0) {
                    if (value.text.isBlank()) onExit() else onMerge()
                    true
                } else false
            },
        )
    }
}

@Composable
private fun EditableTodos(
    block: TodoListBlock,
    onReplace: (DocumentBlock) -> Unit,
    onInsert: (InsertBlockType) -> Unit,
    onEditTodoItem: (String, String, String) -> Unit,
    onSelectionChange: (EditorSelection) -> Unit,
) {
    var focusItemId by remember(block.id) { mutableStateOf<String?>(null) }
    Column {
        block.items.forEachIndexed { index, item ->
            val text = item.content.joinToString("") { it.plainText() }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(item.checked, { checked -> onReplace(block.copy(items = block.items.toMutableList().apply { this[index] = item.copy(checked = checked) })) })
                TodoItemTextField(
                    modifier = Modifier.weight(1f),
                    blockId = block.id,
                    itemId = item.id,
                    content = item.content,
                    text = text,
                    requestFocus = focusItemId == item.id,
                    onFocusConsumed = { focusItemId = null },
                    onChange = { old, changed -> onEditTodoItem(item.id, old, changed) },
                    onSelectionChange = onSelectionChange,
                    onEnter = { offset ->
                        if (text.isBlank()) {
                            if (block.items.size == 1) onReplace(ParagraphBlock(id = block.id))
                            else {
                                onReplace(block.copy(items = block.items.toMutableList().apply { removeAt(index) }))
                                onInsert(InsertBlockType.Text)
                            }
                        } else {
                            val next = com.norfold.app.domain.TodoItem(content = listOf(InlineText(text.substring(offset))))
                            focusItemId = next.id
                            onReplace(block.copy(items = block.items.toMutableList().apply {
                                this[index] = item.copy(content = listOf(InlineText(text.substring(0, offset))))
                                add(index + 1, next)
                            }))
                        }
                    },
                    onBackspaceAtStart = {
                        if (index > 0) {
                            val previous = block.items[index - 1]
                            focusItemId = previous.id
                            onReplace(block.copy(items = block.items.toMutableList().apply {
                                this[index - 1] = previous.copy(content = previous.content + item.content)
                                removeAt(index)
                            }))
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun TodoItemTextField(
    modifier: Modifier,
    blockId: String,
    itemId: String,
    content: List<InlineNode>,
    text: String,
    requestFocus: Boolean,
    onFocusConsumed: () -> Unit,
    onChange: (String, String) -> Unit,
    onSelectionChange: (EditorSelection) -> Unit,
    onEnter: (Int) -> Unit,
    onBackspaceAtStart: () -> Unit,
) {
    var value by remember { mutableStateOf(TextFieldValue(text)) }
    var focused by remember(itemId) { mutableStateOf(false) }
    var textLayout by remember(itemId) { mutableStateOf<TextLayoutResult?>(null) }
    val requester = remember { FocusRequester() }
    val bringIntoView = remember { BringIntoViewRequester() }
    val caretBottomClearancePx = with(LocalDensity.current) { 104.dp.toPx() }
    val editingVisual = rememberInlineEditingTransformation(content)

    fun publishSelection(field: TextFieldValue = value) {
        val start = minOf(field.selection.start, field.selection.end).coerceIn(0, field.text.length)
        val end = maxOf(field.selection.start, field.selection.end).coerceIn(start, field.text.length)
        onSelectionChange(EditorSelection(BlockCursor(blockId, start, itemId), BlockCursor(blockId, end, itemId), field.text.substring(start, end)))
    }

    LaunchedEffect(text, focused) {
        if (!focused && value.composition == null && text != value.text) {
            value = value.copy(text = text, selection = TextRange(value.selection.start.coerceAtMost(text.length), value.selection.end.coerceAtMost(text.length)), composition = null)
        }
    }
    LaunchedEffect(requestFocus) {
        if (requestFocus) {
            requester.requestFocus(); delay(60); bringIntoView.bringIntoView(); publishSelection(); onFocusConsumed()
        }
    }
    LaunchedEffect(value.text, value.selection, textLayout, focused) {
        if (!focused) return@LaunchedEffect
        val layout = textLayout ?: return@LaunchedEffect
        val rect = layout.getCursorRect(value.selection.end.coerceIn(0, layout.layoutInput.text.length))
        bringIntoView.bringIntoView(Rect((rect.left - 12f).coerceAtLeast(0f), (rect.top - 20f).coerceAtLeast(0f), rect.right + 12f, rect.bottom + caretBottomClearancePx))
    }
    BasicTextField(
        value = value,
        onValueChange = { changed ->
            val newline = changed.text.indexOf('\n')
            if (newline >= 0) onEnter(newline) else {
                val previous = value
                value = changed
                onChange(previous.text, changed.text)
                publishSelection(changed)
            }
        },
        textStyle = TextStyle(fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurface),
        visualTransformation = editingVisual,
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        onTextLayout = { textLayout = it },
        modifier = modifier
            .padding(vertical = 7.dp)
            .focusRequester(requester)
            .bringIntoViewRequester(bringIntoView)
            .onFocusChanged { focused = it.isFocused; if (it.isFocused) publishSelection() }
            .onPreviewKeyEvent { event ->
            if (event.type == KeyEventType.KeyDown && event.key == Key.Backspace && value.selection.collapsed && value.selection.start == 0) { onBackspaceAtStart(); true } else false
        },
    )
}

@Composable
private fun SimpleBlockTextField(
    text: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    fontWeight: FontWeight = FontWeight.Normal,
) {
    var value by remember { mutableStateOf(TextFieldValue(text)) }
    var focused by remember { mutableStateOf(false) }
    LaunchedEffect(text, focused) {
        if (!focused && value.composition == null && text != value.text) {
            value = value.copy(
                text = text,
                selection = TextRange(value.selection.start.coerceAtMost(text.length), value.selection.end.coerceAtMost(text.length)),
                composition = null,
            )
        }
    }
    BasicTextField(
        value = value,
        onValueChange = { value = it; onChange(it.text) },
        modifier = modifier.onFocusChanged { focused = it.isFocused },
        textStyle = TextStyle(fontSize = 15.sp, lineHeight = 22.sp, fontWeight = fontWeight, color = MaterialTheme.colorScheme.onSurface),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
    )
}

@Composable
private fun SimpleLabeledBlockField(label: String, text: String, onChange: (String) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Text(label, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Surface(Modifier.fillMaxWidth().padding(top = 4.dp), shape = RoundedCornerShape(7.dp), border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
            SimpleBlockTextField(text, onChange, Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp))
        }
    }
}

private enum class EngineContentKind { Code, Math, Diagram, Chart }

@Composable
private fun EditableEngineCard(
    blockId: String,
    label: String,
    source: String,
    mode: BlockSurfaceMode,
    editorHeightDp: Float,
    onSourceChange: (String) -> Unit,
    onHeightChange: (Float) -> Unit,
    contentKind: EngineContentKind,
) {
    var hidden by remember(blockId) { mutableStateOf(false) }
    var fullScreen by rememberSaveable(blockId) { mutableStateOf(false) }
    var landscape by rememberSaveable(blockId) { mutableStateOf(false) }
    var liveHeight by remember(blockId) { mutableStateOf(editorHeightDp.coerceIn(96f, 420f)) }
    var resizing by remember(blockId) { mutableStateOf(false) }
    var value by remember(blockId) { mutableStateOf(TextFieldValue(source)) }
    var focused by remember(blockId) { mutableStateOf(false) }
    val density = LocalDensity.current
    LaunchedEffect(editorHeightDp) { if (!resizing) liveHeight = editorHeightDp.coerceIn(96f, 420f) }
    LaunchedEffect(source, focused) {
        if (!focused && value.composition == null && source != value.text) {
            value = value.copy(
                text = source,
                selection = TextRange(value.selection.start.coerceAtMost(source.length), value.selection.end.coerceAtMost(source.length)),
                composition = null,
            )
        }
    }
    if (mode == BlockSurfaceMode.View) {
        NativeEngineSurface(label, source, contentKind)
        return
    }
    Surface(shape = RoundedCornerShape(10.dp), border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Column(Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth().padding(start = 10.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(label, Modifier.weight(1f), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = { hidden = !hidden }) { Text(if (hidden) "Show" else "Hide") }
                IconButton(onClick = { landscape = false; fullScreen = true }) { Icon(Icons.Outlined.Fullscreen, "Full screen") }
                IconButton(onClick = { landscape = true; fullScreen = true }) { Icon(Icons.Outlined.ScreenRotation, "Landscape") }
            }
            if (!hidden) {
                BasicTextField(
                    value = value,
                    onValueChange = { changed -> value = changed; onSourceChange(changed.text) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(liveHeight.dp)
                        .verticalScroll(rememberScrollState())
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .4f))
                        .padding(12.dp)
                        .onFocusChanged { focused = it.isFocused },
                    textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 19.sp, color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                )
                Row(
                    Modifier.fillMaxWidth().height(30.dp).background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .22f)),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Outlined.DragIndicator,
                        "Resize block",
                        Modifier.size(30.dp).padding(3.dp).pointerInput(blockId) {
                            detectDragGestures(
                                onDragStart = { resizing = true },
                                onDragCancel = { resizing = false; liveHeight = editorHeightDp.coerceIn(96f, 420f) },
                                onDragEnd = { resizing = false; onHeightChange(liveHeight) },
                                onDrag = { change, amount ->
                                    change.consume()
                                    liveHeight = (liveHeight + with(density) { amount.y.toDp().value }).coerceIn(96f, 420f)
                                },
                            )
                        },
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
    if (fullScreen) {
        EngineFullscreenDialog(label, source, contentKind, landscape) {
            fullScreen = false
            landscape = false
        }
    }
}

@Composable
private fun NativeCodeSurface(source: String, modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .4f))
            .horizontalScroll(rememberScrollState())
            .padding(12.dp),
    ) {
        Text(
            text = source.ifBlank { " " },
            color = MaterialTheme.colorScheme.onSurface,
            fontFamily = FontFamily.Monospace,
            fontSize = 13.sp,
            lineHeight = 19.sp,
            softWrap = false,
        )
    }
}

@Composable
private fun NativeEngineSurface(label: String, source: String, kind: EngineContentKind, modifier: Modifier = Modifier) {
    Surface(modifier.fillMaxWidth(), shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .35f)) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (kind != EngineContentKind.Code) {
                Text(label, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            }
            Text(
                text = source.ifBlank { when (kind) { EngineContentKind.Math -> "Empty equation"; EngineContentKind.Diagram -> "Empty diagram"; EngineContentKind.Chart -> "Empty chart"; EngineContentKind.Code -> " " } },
                color = MaterialTheme.colorScheme.onSurface,
                fontFamily = FontFamily.Monospace,
                fontStyle = if (kind == EngineContentKind.Math) FontStyle.Italic else FontStyle.Normal,
                fontSize = if (kind == EngineContentKind.Math) 16.sp else 13.sp,
                lineHeight = if (kind == EngineContentKind.Math) 24.sp else 19.sp,
            )
        }
    }
}

@Composable
private fun EngineFullscreenDialog(label: String, source: String, kind: EngineContentKind, landscape: Boolean, onDismiss: () -> Unit) {
    StableLandscapeOrientationEffect(landscape)
    NorfoldFullscreenDialog(onDismissRequest = onDismiss) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().padding(12.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(label, Modifier.weight(1f), fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    TextButton(onClick = onDismiss) { Text("Close") }
                }
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    NativeEngineSurface(label, source, kind)
                }
            }
        }
    }
}

@Composable
private fun NativeTable(table: TableBlock, mode: BlockSurfaceMode, onReplace: (DocumentBlock) -> Unit) {
    val columnCount = maxOf(table.headers.size, table.rows.maxOfOrNull { it.size } ?: 0).coerceAtLeast(1)
    val persistedWidths = List(columnCount) { table.columnWidthsDp.getOrNull(it)?.coerceIn(88f, 360f) ?: 160f }
    var widths by remember(table.id) { mutableStateOf(persistedWidths) }
    val alignments = List(columnCount) { table.columnAlignments.getOrNull(it) ?: TableAlignment.Start }
    var selectedColumn by remember(table.id) { mutableIntStateOf(-1) }
    var selectedRow by remember(table.id) { mutableIntStateOf(-1) }
    var columnMenu by remember(table.id) { mutableStateOf(false) }
    var rowMenu by remember(table.id) { mutableStateOf(false) }
    var resizingColumn by remember(table.id) { mutableIntStateOf(-1) }
    var hidden by remember(table.id) { mutableStateOf(false) }
    val density = LocalDensity.current
    LaunchedEffect(table.columnWidthsDp, columnCount) {
        if (resizingColumn < 0 && widths != persistedWidths) widths = persistedWidths
    }

    fun normalizedRows(): List<List<TableCell>> = table.rows.map { row ->
        List(columnCount) { column -> row.getOrNull(column) ?: TableCell() }
    }
    fun normalizedHeaders(): List<TableCell> = List(columnCount) { table.headers.getOrNull(it) ?: TableCell() }
    fun replace(
        headers: List<TableCell> = normalizedHeaders(),
        rows: List<List<TableCell>> = normalizedRows(),
        nextWidths: List<Float> = widths,
        nextAlignments: List<TableAlignment> = alignments,
    ) = onReplace(table.copy(headers = headers, rows = rows, columnWidthsDp = nextWidths, columnAlignments = nextAlignments))

    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("Table", Modifier.weight(1f), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton(onClick = { hidden = !hidden }) { Text(if (hidden) "Show" else "Hide") }
    }
    if (!hidden) {
    Column(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).border(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        val allRows = listOf(normalizedHeaders()) + normalizedRows()
        allRows.forEachIndexed { visualRow, row ->
            val header = visualRow == 0
            Row(Modifier.width(widths.sum().dp)) {
                repeat(columnCount) { column ->
                    Box(Modifier.width(widths[column].dp)) {
                        TableCellSurface(
                            tableId = table.id,
                            row = visualRow - 1,
                            column = column,
                            cell = row[column],
                            header = header,
                            alignment = alignments[column],
                            mode = mode,
                            selected = when {
                                rowMenu -> selectedRow == visualRow - 1
                                columnMenu -> selectedColumn == column
                                else -> selectedColumn == column && selectedRow == visualRow - 1
                            },
                            onSelect = {
                                selectedColumn = column
                                selectedRow = visualRow - 1
                            },
                            onChange = { content ->
                                if (header) {
                                    replace(headers = normalizedHeaders().toMutableList().apply { this[column] = TableCell(content) })
                                } else {
                                    replace(rows = normalizedRows().toMutableList().apply {
                                        this[visualRow - 1] = this[visualRow - 1].toMutableList().apply { this[column] = TableCell(content) }
                                    })
                                }
                            },
                        )
                        if (mode == BlockSurfaceMode.Edit && header && column < columnCount - 1) {
                            Box(
                                Modifier.align(Alignment.CenterEnd).width(12.dp).heightIn(min = 44.dp).pointerInput(table.id, column) {
                                    detectDragGestures(
                                        onDragStart = { resizingColumn = column },
                                        onDragCancel = { resizingColumn = -1; widths = persistedWidths },
                                        onDragEnd = { resizingColumn = -1; replace(nextWidths = widths) },
                                        onDrag = { change, amount ->
                                            change.consume()
                                            val deltaDp = with(density) { amount.x.toDp().value }
                                            widths = widths.toMutableList().apply { this[column] = (this[column] + deltaDp).coerceIn(88f, 360f) }
                                        },
                                    )
                                },
                            )
                        }
                    }
                }
            }
        }
        if (mode == BlockSurfaceMode.Edit) {
            Row(Modifier.width(widths.sum().dp).height(42.dp)) {
                TextButton(
                    onClick = { replace(rows = normalizedRows() + listOf(List(columnCount) { TableCell() })); selectedRow = table.rows.size },
                    modifier = Modifier.weight(1f),
                ) { Icon(Icons.Outlined.Add, null, Modifier.size(17.dp)); Text("Add row") }
                TextButton(onClick = {
                    replace(
                        headers = normalizedHeaders() + TableCell(),
                        rows = normalizedRows().map { it + TableCell() },
                        nextWidths = widths + 160f,
                        nextAlignments = alignments + TableAlignment.Start,
                    )
                    selectedColumn = columnCount
                }) { Icon(Icons.Outlined.Add, null, Modifier.size(17.dp)); Text("Column") }
                IconButton(onClick = { selectedColumn = selectedColumn.coerceAtLeast(0); columnMenu = true }) {
                    Icon(Icons.Outlined.MoreVert, "Column actions")
                }
                IconButton(onClick = { selectedRow = selectedRow.coerceAtLeast(0); rowMenu = true }) {
                    Icon(Icons.Outlined.Menu, "Row actions")
                }
            }
        }
    }
    }

    DropdownMenu(columnMenu, { columnMenu = false }) {
        val column = selectedColumn.coerceIn(0, columnCount - 1)
        fun insertColumn(index: Int) {
            val at = index.coerceIn(0, columnCount)
            replace(
                headers = normalizedHeaders().toMutableList().apply { add(at, TableCell()) },
                rows = normalizedRows().map { it.toMutableList().apply { add(at, TableCell()) } },
                nextWidths = widths.toMutableList().apply { add(at, 160f) },
                nextAlignments = alignments.toMutableList().apply { add(at, TableAlignment.Start) },
            )
            selectedColumn = at
            columnMenu = false
        }
        DropdownMenuItem({ Text("Insert left") }, onClick = { insertColumn(column) })
        DropdownMenuItem({ Text("Insert right") }, onClick = { insertColumn(column + 1) })
        DropdownMenuItem({ Text("Align") }, onClick = {
            val next = when (alignments[column]) { TableAlignment.Start -> TableAlignment.Center; TableAlignment.Center -> TableAlignment.End; TableAlignment.End -> TableAlignment.Start }
            replace(nextAlignments = alignments.toMutableList().apply { this[column] = next }); columnMenu = false
        })
        DropdownMenuItem({ Text("Set to page width") }, onClick = { replace(nextWidths = List(columnCount) { 160f }); columnMenu = false })
        DropdownMenuItem({ Text("Distribute columns evenly") }, onClick = { replace(nextWidths = List(columnCount) { widths.average().toFloat() }); columnMenu = false })
        DropdownMenuItem({ Text("Duplicate") }, onClick = {
            replace(
                headers = normalizedHeaders().toMutableList().apply { add(column + 1, this[column]) },
                rows = normalizedRows().map { it.toMutableList().apply { add(column + 1, this[column]) } },
                nextWidths = widths.toMutableList().apply { add(column + 1, this[column]) },
                nextAlignments = alignments.toMutableList().apply { add(column + 1, this[column]) },
            ); columnMenu = false
        })
        DropdownMenuItem({ Text("Clear contents") }, onClick = {
            replace(
                headers = normalizedHeaders().toMutableList().apply { this[column] = TableCell() },
                rows = normalizedRows().map { it.toMutableList().apply { this[column] = TableCell() } },
            ); columnMenu = false
        })
        DropdownMenuItem({ Text("Delete") }, enabled = columnCount > 1, onClick = {
            replace(
                headers = normalizedHeaders().toMutableList().apply { removeAt(column) },
                rows = normalizedRows().map { it.toMutableList().apply { removeAt(column) } },
                nextWidths = widths.toMutableList().apply { removeAt(column) },
                nextAlignments = alignments.toMutableList().apply { removeAt(column) },
            ); selectedColumn = -1; columnMenu = false
        })
    }
    DropdownMenu(rowMenu, { rowMenu = false }) {
        val row = selectedRow.coerceIn(0, normalizedRows().lastIndex.coerceAtLeast(0))
        fun insertRow(index: Int) {
            replace(rows = normalizedRows().toMutableList().apply { add(index.coerceIn(0, size), List(columnCount) { TableCell() }) })
            selectedRow = index
            rowMenu = false
        }
        DropdownMenuItem({ Text("Insert above") }, onClick = { insertRow(row) })
        DropdownMenuItem({ Text("Insert below") }, onClick = { insertRow(row + 1) })
        DropdownMenuItem({ Text("Duplicate") }, enabled = table.rows.isNotEmpty(), onClick = { replace(rows = normalizedRows().toMutableList().apply { add(row + 1, this[row]) }); rowMenu = false })
        DropdownMenuItem({ Text("Clear contents") }, enabled = table.rows.isNotEmpty(), onClick = { replace(rows = normalizedRows().toMutableList().apply { this[row] = List(columnCount) { TableCell() } }); rowMenu = false })
        DropdownMenuItem({ Text("Delete") }, enabled = table.rows.isNotEmpty(), onClick = { replace(rows = normalizedRows().toMutableList().apply { removeAt(row) }); selectedRow = -1; rowMenu = false })
    }
}

@Composable
private fun TableCellSurface(
    tableId: String,
    row: Int,
    column: Int,
    cell: TableCell,
    header: Boolean,
    alignment: TableAlignment,
    mode: BlockSurfaceMode,
    selected: Boolean,
    onSelect: () -> Unit,
    onChange: (List<InlineNode>) -> Unit,
) {
    val plain = cell.content.joinToString("") { it.plainText() }
    val horizontal = when (alignment) { TableAlignment.Start -> Alignment.Start; TableAlignment.Center -> Alignment.CenterHorizontally; TableAlignment.End -> Alignment.End }
    val borderColor = if (selected && mode == BlockSurfaceMode.Edit) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
    Surface(
        modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp).border(if (selected) 1.5.dp else .5.dp, borderColor).clickable(onClick = onSelect),
        color = if (header) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface,
    ) {
        if (mode == BlockSurfaceMode.Edit) {
            var value by remember(tableId, row, column) { mutableStateOf(TextFieldValue(plain)) }
            var focused by remember(tableId, row, column) { mutableStateOf(false) }
            LaunchedEffect(plain, focused) {
                if (!focused && value.composition == null && plain != value.text) {
                    value = value.copy(
                        text = plain,
                        selection = TextRange(value.selection.start.coerceAtMost(plain.length), value.selection.end.coerceAtMost(plain.length)),
                        composition = null,
                    )
                }
            }
            BasicTextField(
                value = value,
                onValueChange = { changed -> value = changed; onChange(listOf(InlineText(changed.text))) },
                modifier = Modifier.fillMaxWidth().padding(10.dp).onFocusChanged { focused = it.isFocused },
                textStyle = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = if (header) FontWeight.Bold else FontWeight.Normal, color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            )
        } else {
            Column(Modifier.fillMaxWidth().padding(10.dp), horizontalAlignment = horizontal) {
                Text(rememberInlineAnnotated(cell.content), fontSize = 14.sp, lineHeight = 20.sp, fontWeight = if (header) FontWeight.Bold else FontWeight.Normal)
            }
        }
    }
}

@Composable private fun FileCard(file: FileBlock) {
    val context = LocalContext.current
    Surface(Modifier.fillMaxWidth().clickable { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(file.uri)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)) } }, shape = RoundedCornerShape(9.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .45f)) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Outlined.AttachFile, null, tint = MaterialTheme.colorScheme.primary); Column(Modifier.padding(start = 10.dp)) { Text(file.name.ifBlank { "Attached file" }, fontWeight = FontWeight.Bold); Text(file.mimeType, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
    }
}

@Composable
private fun EditableImageBlock(block: ImageBlock, mode: BlockSurfaceMode, onReplace: (DocumentBlock) -> Unit) {
    val context = LocalContext.current
    var hidden by remember(block.id) { mutableStateOf(false) }
    var fullScreen by rememberSaveable(block.id) { mutableStateOf(false) }
    var landscape by rememberSaveable(block.id) { mutableStateOf(false) }
    var liveHeight by remember(block.id) { mutableStateOf(block.displayHeightDp.coerceIn(120f, 560f)) }
    var resizing by remember(block.id) { mutableStateOf(false) }
    LaunchedEffect(block.displayHeightDp) { if (!resizing) liveHeight = block.displayHeightDp.coerceIn(120f, 560f) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        onReplace(block.copy(source = uri.toString()))
    }
    Column {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Image", Modifier.weight(1f), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = { hidden = !hidden }, enabled = block.source.isNotBlank()) { Text(if (hidden) "Show" else "Hide") }
            IconButton(onClick = { landscape = false; fullScreen = true }, enabled = block.source.isNotBlank()) { Icon(Icons.Outlined.Fullscreen, "Full screen image") }
            IconButton(onClick = { landscape = true; fullScreen = true }, enabled = block.source.isNotBlank()) { Icon(Icons.Outlined.ScreenRotation, "Landscape image") }
        }
        if (!hidden && block.source.isNotBlank()) Box(if (block.layout == com.norfold.app.domain.ImageLayout.Fit) Modifier.fillMaxWidth() else Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
            val imageModifier = when (block.layout) {
                com.norfold.app.domain.ImageLayout.Fit -> Modifier.fillMaxWidth()
                com.norfold.app.domain.ImageLayout.Wide -> Modifier.width(840.dp)
                com.norfold.app.domain.ImageLayout.Original -> Modifier.width(1080.dp)
            }
            AsyncImage(block.source, block.caption, imageModifier.height(liveHeight.dp).clip(RoundedCornerShape(8.dp)), contentScale = ContentScale.Fit)
            if (mode == BlockSurfaceMode.Edit) {
                val density = LocalDensity.current
                Icon(Icons.Outlined.DragIndicator, "Resize image", Modifier.align(Alignment.BottomEnd).size(30.dp).background(MaterialTheme.colorScheme.surface.copy(alpha = .8f), RoundedCornerShape(6.dp)).pointerInput(block.id) {
                    detectDragGestures(
                        onDragStart = { resizing = true },
                        onDragCancel = { resizing = false; liveHeight = block.displayHeightDp.coerceIn(120f, 560f) },
                        onDragEnd = { resizing = false; onReplace(block.copy(displayHeightDp = liveHeight)) },
                        onDrag = { change, amount -> change.consume(); liveHeight = (liveHeight + with(density) { amount.y.toDp().value }).coerceIn(120f, 560f) },
                    )
                }, tint = MaterialTheme.colorScheme.primary)
            }
        }
        if (mode == BlockSurfaceMode.Edit) {
            TextButton(onClick = { picker.launch(arrayOf("image/*")) }) { Icon(Icons.Outlined.Image, null); Text(if (block.source.isBlank()) "Choose image" else "Replace image") }
            SimpleLabeledBlockField("Caption", block.caption) { onReplace(block.copy(caption = it)) }
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                com.norfold.app.domain.ImageLayout.entries.forEach { layout ->
                    TextButton(onClick = { onReplace(block.copy(layout = layout)) }) { Text(layout.name, color = if (block.layout == layout) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
        } else if (block.caption.isNotBlank()) Text(block.caption, Modifier.padding(top = 6.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (fullScreen && block.source.isNotBlank()) {
        MediaFullscreenDialog(
            label = block.caption.ifBlank { "Image" },
            source = block.source,
            landscape = landscape,
            onDismiss = { fullScreen = false; landscape = false },
        )
    }
}

@Composable
private fun MediaFullscreenDialog(
    label: String,
    source: String,
    landscape: Boolean,
    onDismiss: () -> Unit,
) {
    StableLandscapeOrientationEffect(landscape)
    NorfoldFullscreenDialog(onDismissRequest = onDismiss) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().padding(12.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(label, Modifier.weight(1f), fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                    TextButton(onClick = onDismiss) { Text("Close") }
                }
                Box(Modifier.fillMaxSize().horizontalScroll(rememberScrollState()), contentAlignment = Alignment.Center) {
                    AsyncImage(source, label, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                }
            }
        }
    }
}

@Composable
private fun EditableFileBlock(block: FileBlock, mode: BlockSurfaceMode, onReplace: (DocumentBlock) -> Unit) {
    val context = LocalContext.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        var name = block.name
        var size = block.sizeBytes
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                name = cursor.getString(cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME)) ?: name
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) size = cursor.getLong(sizeIndex)
            }
        }
        onReplace(block.copy(name = name.ifBlank { "Attached file" }, mimeType = context.contentResolver.getType(uri) ?: block.mimeType, sizeBytes = size, uri = uri.toString()))
    }
    Column {
        if (block.uri.isNotBlank()) FileCard(block)
        if (mode == BlockSurfaceMode.Edit) {
            TextButton(onClick = { picker.launch(arrayOf("*/*")) }) { Icon(Icons.Outlined.AttachFile, null); Text(if (block.uri.isBlank()) "Attach file" else "Replace file") }
            SimpleLabeledBlockField("Display name", block.name) { onReplace(block.copy(name = it)) }
        } else if (block.uri.isBlank()) {
            Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(9.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .45f)) {
                Text("No file attached", Modifier.padding(12.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun EditableEmbedBlock(block: EmbedBlock, mode: BlockSurfaceMode, onReplace: (DocumentBlock) -> Unit) {
    val context = LocalContext.current
    var hidden by remember(block.id) { mutableStateOf(false) }
    var fullScreen by rememberSaveable(block.id) { mutableStateOf(false) }
    var landscape by rememberSaveable(block.id) { mutableStateOf(false) }
    var attemptedUrl by remember(block.id) { mutableStateOf("") }
    var liveHeight by remember(block.id) { mutableStateOf(block.displayHeightDp.coerceIn(88f, 420f)) }
    var resizing by remember(block.id) { mutableStateOf(false) }
    val density = LocalDensity.current
    LaunchedEffect(block.displayHeightDp) {
        if (!resizing) liveHeight = block.displayHeightDp.coerceIn(88f, 420f)
    }
    LaunchedEffect(block.url) {
        if (block.url.startsWith("http") && attemptedUrl != block.url) {
            attemptedUrl = block.url
            val metadata = EmbedMetadataResolver.resolve(context, block.url, block.metadata)
            if (metadata != block.metadata) onReplace(block.copy(metadata = metadata))
        }
    }
    Column {
        // Clean preview: the "Embed · Hide · Fullscreen" chrome is Edit-only; reading shows just the card.
        if (mode == BlockSurfaceMode.Edit) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Embed", Modifier.weight(1f), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = { hidden = !hidden }, enabled = block.url.isNotBlank()) { Text(if (hidden) "Show" else "Hide") }
                IconButton(onClick = { landscape = false; fullScreen = true }, enabled = block.url.isNotBlank()) { Icon(Icons.Outlined.Fullscreen, "Full screen embed") }
                IconButton(onClick = { landscape = true; fullScreen = true }, enabled = block.url.isNotBlank()) { Icon(Icons.Outlined.ScreenRotation, "Landscape embed") }
            }
            SimpleLabeledBlockField("Embed URL", block.url) { onReplace(block.copy(url = it, metadata = if (it == block.url) block.metadata else com.norfold.app.domain.EmbedMetadata())) }
        }
        if (!hidden && block.url.isNotBlank()) {
            Column {
                Box(Modifier.fillMaxWidth().height(liveHeight.dp)) {
                    EmbedCard(block, Modifier.fillMaxSize())
                }
                if (mode == BlockSurfaceMode.Edit) {
                    Row(
                        Modifier.fillMaxWidth().height(30.dp).background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .22f)),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Outlined.DragIndicator,
                            "Resize embed",
                            Modifier.size(30.dp).padding(3.dp).pointerInput(block.id) {
                                detectDragGestures(
                                    onDragStart = { resizing = true },
                                    onDragCancel = { resizing = false; liveHeight = block.displayHeightDp.coerceIn(88f, 420f) },
                                    onDragEnd = { resizing = false; onReplace(block.copy(displayHeightDp = liveHeight)) },
                                    onDrag = { change, amount ->
                                        change.consume()
                                        liveHeight = (liveHeight + with(density) { amount.y.toDp().value }).coerceIn(88f, 420f)
                                    },
                                )
                            },
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        }
    }
    if (fullScreen && block.url.isNotBlank()) {
        EmbedFullscreenDialog(block, landscape) { fullScreen = false; landscape = false }
    }
}

@Composable
private fun EmbedFullscreenDialog(embed: EmbedBlock, landscape: Boolean, onDismiss: () -> Unit) {
    StableLandscapeOrientationEffect(landscape)
    NorfoldFullscreenDialog(onDismissRequest = onDismiss) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().padding(16.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(embed.metadata.title.ifBlank { "Embed" }, Modifier.weight(1f), fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    TextButton(onClick = onDismiss) { Text("Close") }
                }
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Box(Modifier.fillMaxWidth().widthIn(max = 760.dp).height(embed.displayHeightDp.coerceIn(88f, 420f).dp)) {
                        EmbedCard(embed, Modifier.fillMaxSize())
                    }
                }
            }
        }
    }
}

@Composable
private fun StableLandscapeOrientationEffect(enabled: Boolean) {
    val activity = LocalContext.current.findActivity()
    DisposableEffect(activity, enabled) {
        if (enabled && activity?.requestedOrientation != ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE) {
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE
        }
        onDispose {
            if (
                enabled &&
                activity?.isChangingConfigurations == false &&
                activity.requestedOrientation == ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE
            ) {
                activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            }
        }
    }
}

@Composable private fun EmbedCard(embed: EmbedBlock, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    Surface(modifier.fillMaxWidth().clickable { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(embed.url))) } }, shape = RoundedCornerShape(9.dp), border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Row(Modifier.fillMaxSize().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            val favicon = embed.metadata.faviconPath?.let(::File)?.takeIf(File::isFile)
            Surface(Modifier.size(38.dp), shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                Box(contentAlignment = Alignment.Center) {
                    if (favicon != null) AsyncImage(favicon, null, Modifier.fillMaxSize().padding(7.dp), contentScale = ContentScale.Fit)
                    else Text(embed.url.removePrefix("https://").take(1).uppercase(), fontWeight = FontWeight.Bold)
                }
            }
            Column(Modifier.padding(start = 10.dp)) {
                Text(embed.metadata.title.ifBlank { embed.url.substringAfter("://").substringBefore('/') }, fontWeight = FontWeight.Bold)
                Text(embed.metadata.description.ifBlank { embed.url }, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
            }
        }
    }
}

@Composable private fun BlockInsertButton(onInsert: (InsertBlockType) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box { TextButton({ open = true }) { Icon(Icons.Outlined.Add, null); Text("Type / to insert a block") }; InsertBlockMenu(open, { open = false }) { open = false; onInsert(it) } }
}

@Composable private fun InsertBlockMenu(open: Boolean, dismiss: () -> Unit, query: String = "", select: (InsertBlockType) -> Unit) {
    val normalized = query.trim().lowercase()
    val matches = InsertBlockType.entries.filter { type ->
        val label = type.name.replace(Regex("([a-z])([A-Z])"), "$1 $2")
        normalized.isBlank() || label.lowercase().contains(normalized) || when (type) {
            InsertBlockType.Chart -> normalized in setOf("plot", "graph", "histogram", "draw graph")
            InsertBlockType.TodoList -> normalized in setOf("checklist", "task")
            InsertBlockType.Embed -> normalized in setOf("link", "url")
            InsertBlockType.Math -> normalized in setOf("equation", "formula", "latex")
            InsertBlockType.Container -> normalized in setOf("columns", "layout", "group", "row", "column", "split")
            else -> false
        }
    }
    DropdownMenu(open, dismiss) {
        if (query.isNotBlank()) Text("/$query", Modifier.padding(horizontal = 12.dp, vertical = 7.dp), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        matches.forEach { type -> DropdownMenuItem({ Text(type.name.replace(Regex("([a-z])([A-Z])"), "$1 $2")) }, onClick = { select(type) }) }
        if (matches.isEmpty()) Text("No matching block", Modifier.padding(12.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun newDocumentBlock(type: InsertBlockType): DocumentBlock = when (type) {
    InsertBlockType.Text -> ParagraphBlock()
    InsertBlockType.Heading -> HeadingBlock(level = 2)
    InsertBlockType.BulletList -> BulletListBlock(items = listOf(com.norfold.app.domain.ListItem(content = listOf(InlineText("List item")))))
    InsertBlockType.NumberedList -> NumberedListBlock(items = listOf(com.norfold.app.domain.ListItem(content = listOf(InlineText("List item")))))
    InsertBlockType.TodoList -> TodoListBlock(items = listOf(com.norfold.app.domain.TodoItem(content = listOf(InlineText("Task")))))
    InsertBlockType.Quote -> QuoteBlock(children = listOf(ParagraphBlock(content = listOf(InlineText("Quote")))))
    InsertBlockType.Callout -> CalloutBlock(children = listOf(ParagraphBlock(content = listOf(InlineText("Callout")))))
    InsertBlockType.Container -> ContainerBlock(
        axis = ContainerAxis.Row,
        children = listOf(
            ParagraphBlock(content = listOf(InlineText("Left column"))),
            ParagraphBlock(content = listOf(InlineText("Right column"))),
        ),
        weights = listOf(1f, 1f),
    )
    InsertBlockType.Divider -> DividerBlock()
    InsertBlockType.Code -> CodeBlock()
    InsertBlockType.Table -> TableBlock(headers = listOf(com.norfold.app.domain.TableCell(listOf(InlineText("Column"))), com.norfold.app.domain.TableCell(listOf(InlineText("Detail")))), rows = listOf(listOf(com.norfold.app.domain.TableCell(), com.norfold.app.domain.TableCell())))
    InsertBlockType.Image -> ImageBlock()
    InsertBlockType.File -> FileBlock()
    InsertBlockType.Embed -> EmbedBlock()
    InsertBlockType.Chart -> ChartBlock()
    InsertBlockType.Math -> MathBlock(tex = "x = \\frac{-b \\pm \\sqrt{b^2-4ac}}{2a}")
    InsertBlockType.Mermaid -> MermaidBlock(code = "graph TD\n  A[Start] --> B[Done]")
}

private fun duplicateBlock(block: DocumentBlock): DocumentBlock = when (block) {
    is ParagraphBlock -> ParagraphBlock(content = block.content)
    is HeadingBlock -> HeadingBlock(level = block.level, content = block.content)
    is BulletListBlock -> BulletListBlock(items = block.items)
    is NumberedListBlock -> NumberedListBlock(start = block.start, items = block.items)
    is TodoListBlock -> TodoListBlock(items = block.items)
    is QuoteBlock -> QuoteBlock(children = block.children)
    is CalloutBlock -> CalloutBlock(tone = block.tone, title = block.title, children = block.children)
    is ContainerBlock -> ContainerBlock(axis = block.axis, children = block.children.map(::duplicateBlock), weights = block.weights)
    is DividerBlock -> DividerBlock()
    is CodeBlock -> CodeBlock(language = block.language, code = block.code, editorHeightDp = block.editorHeightDp)
    is TableBlock -> TableBlock(headers = block.headers, rows = block.rows, columnWidthsDp = block.columnWidthsDp, columnAlignments = block.columnAlignments)
    is ImageBlock -> ImageBlock(source = block.source, caption = block.caption, layout = block.layout, displayHeightDp = block.displayHeightDp)
    is FileBlock -> FileBlock(name = block.name, mimeType = block.mimeType, sizeBytes = block.sizeBytes, uri = block.uri)
    is EmbedBlock -> EmbedBlock(url = block.url, metadata = block.metadata, displayHeightDp = block.displayHeightDp)
    is ChartBlock -> ChartBlock(vegaLiteSpec = block.vegaLiteSpec, editorHeightDp = block.editorHeightDp)
    is MathBlock -> MathBlock(tex = block.tex, display = block.display, editorHeightDp = block.editorHeightDp)
    is MermaidBlock -> MermaidBlock(code = block.code, editorHeightDp = block.editorHeightDp)
    is UnknownBlock -> BlockDocumentJson.reidentify(block)
}

private fun DocumentBlock.withBlockId(id: String): DocumentBlock = when (this) {
    is ParagraphBlock -> copy(id = id)
    is HeadingBlock -> copy(id = id)
    is BulletListBlock -> copy(id = id)
    is NumberedListBlock -> copy(id = id)
    is TodoListBlock -> copy(id = id)
    is QuoteBlock -> copy(id = id)
    is CalloutBlock -> copy(id = id)
    is ContainerBlock -> copy(id = id)
    is DividerBlock -> copy(id = id)
    is CodeBlock -> copy(id = id)
    is TableBlock -> copy(id = id)
    is ImageBlock -> copy(id = id)
    is FileBlock -> copy(id = id)
    is EmbedBlock -> copy(id = id)
    is ChartBlock -> copy(id = id)
    is MathBlock -> copy(id = id)
    is MermaidBlock -> copy(id = id)
    is UnknownBlock -> BlockDocumentJson.reidentify(this, id)
}

@Composable
private fun InlineRichText(
    nodes: List<InlineNode>,
    modifier: Modifier = Modifier,
    style: TextStyle = TextStyle.Default,
) {
    val colors = MaterialTheme.colorScheme
    val annotated = rememberInlineAnnotated(nodes)
    Text(
        text = annotated,
        modifier = modifier,
        style = TextStyle(color = colors.onSurface).merge(style),
    )
}

@Composable
private fun rememberInlineEditingTransformation(nodes: List<InlineNode>): VisualTransformation {
    val colors = MaterialTheme.colorScheme
    val annotated = remember(nodes, colors.surfaceVariant, colors.primary, colors.tertiary) {
        inlineAnnotated(nodes, emptyMap(), colors.surfaceVariant, colors.primary, colors.tertiary)
    }
    return remember(annotated) {
        VisualTransformation { source ->
            TransformedText(
                text = annotated.takeIf { it.text == source.text } ?: AnnotatedString(source.text),
                offsetMapping = OffsetMapping.Identity,
            )
        }
    }
}

@Composable
private fun rememberInlineAnnotated(nodes: List<InlineNode>): AnnotatedString {
    val emojiMap = LocalEmojiShortcodes.current
    val colors = MaterialTheme.colorScheme
    return remember(nodes, emojiMap, colors.surfaceVariant, colors.primary, colors.tertiary) {
        inlineAnnotated(nodes, emojiMap, colors.surfaceVariant, colors.primary, colors.tertiary)
    }
}

private fun inlineAnnotated(
    nodes: List<InlineNode>,
    emojiMap: Map<String, String>,
    codeBackground: Color,
    linkColor: Color,
    tagColor: Color,
): AnnotatedString = buildAnnotatedString {
    fun appendNodes(values: List<InlineNode>, inherited: SpanStyle = SpanStyle()) {
        values.forEach { node -> when (node) {
            is InlineText -> withStyle(inherited) { append(expandEmojiShortcodes(node.value, emojiMap)) }
            is BoldInline -> appendNodes(node.children, inherited.merge(SpanStyle(fontWeight = FontWeight.Bold)))
            is ItalicInline -> appendNodes(node.children, inherited.merge(SpanStyle(fontStyle = FontStyle.Italic)))
            is StrikethroughInline -> appendNodes(node.children, inherited.merge(SpanStyle(textDecoration = TextDecoration.LineThrough)))
            is CodeInline -> withStyle(inherited.merge(SpanStyle(fontFamily = FontFamily.Monospace, background = codeBackground.copy(alpha = .55f)))) { append(node.value) }
            is LinkInline -> {
                withLink(
                    LinkAnnotation.Url(
                        url = node.url,
                        styles = TextLinkStyles(
                            style = inherited.merge(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)),
                        ),
                    ),
                ) {
                    appendNodes(node.children, inherited)
                }
            }
            is MathInline -> withStyle(inherited.merge(SpanStyle(fontFamily = FontFamily.Monospace, fontStyle = FontStyle.Italic, background = codeBackground.copy(alpha = .35f)))) { append(node.tex) }
            is EmojiInline -> withStyle(inherited) { append(node.unicode) }
            is TagInline -> withStyle(inherited.merge(SpanStyle(color = tagColor, fontWeight = FontWeight.SemiBold))) { append("#${node.value}") }
            is MentionInline -> withStyle(inherited.merge(SpanStyle(fontWeight = FontWeight.SemiBold))) { append("@${node.value}") }
        } }
    }
    appendNodes(nodes)
}

private val LocalEmojiShortcodes = staticCompositionLocalOf<Map<String, String>> { emptyMap() }
private val EmojiShortcodePattern = Regex(":([a-zA-Z0-9_+\\-]+):")
private val LiveEmojiPattern = Regex("(?::[a-zA-Z0-9_+\\-]+:|:-?\\)|:-?\\(|;-?\\)|:-?[dD]|<3)$")
private val EmoticonEmoji = mapOf(
    ":)" to "🙂", ":-)" to "🙂", ":(" to "🙁", ":-(" to "🙁",
    ";)" to "😉", ";-)" to "😉", ":D" to "😄", ":-D" to "😄",
    ":d" to "😄", ":-d" to "😄", "<3" to "❤️",
)

private data class LiveEmojiReplacement(
    val start: Int,
    val oldEnd: Int,
    val source: String,
    val unicode: String,
    val value: TextFieldValue,
)

private data class EmojiSpan(val start: Int, val end: Int, val node: EmojiInline)

private fun detectLiveEmoji(previous: TextFieldValue, changed: TextFieldValue, emojiMap: Map<String, String>): LiveEmojiReplacement? {
    if (!changed.selection.collapsed) return null
    val cursor = changed.selection.start.coerceIn(0, changed.text.length)
    val match = LiveEmojiPattern.find(changed.text.substring(0, cursor)) ?: return null
    val start = match.range.first
    if (changed.text.substring(0, start).count { it.code == 96 } % 2 != 0) return null
    val source = match.value
    val unicode = if (source.startsWith(':') && source.endsWith(':') && source.length > 2) {
        emojiMap[source.substring(1, source.lastIndex)]
    } else EmoticonEmoji[source]
    unicode ?: return null
    val insertion = SmartPasteCodec.insertion(previous.text, changed.text) ?: return null
    val newInsertionEnd = insertion.oldStart + insertion.insertedText.length
    val removedLength = insertion.oldEnd - insertion.oldStart
    val oldEnd = when {
        match.range.last + 1 <= insertion.oldStart -> match.range.last + 1
        match.range.last + 1 >= newInsertionEnd -> match.range.last + 1 - insertion.insertedText.length + removedLength
        else -> insertion.oldStart
    }.coerceIn(start, previous.text.length)
    val transformed = changed.text.replaceRange(start, match.range.last + 1, unicode)
    return LiveEmojiReplacement(
        start = start,
        oldEnd = oldEnd,
        source = source,
        unicode = unicode,
        value = TextFieldValue(transformed, selection = TextRange(start + unicode.length)),
    )
}

private fun findEmojiEndingAt(nodes: List<InlineNode>, offset: Int): EmojiSpan? {
    var cursor = 0
    var result: EmojiSpan? = null
    fun visit(values: List<InlineNode>) {
        values.forEach { node ->
            if (result != null) return
            when (node) {
                is BoldInline -> visit(node.children)
                is ItalicInline -> visit(node.children)
                is StrikethroughInline -> visit(node.children)
                is LinkInline -> visit(node.children)
                is EmojiInline -> {
                    val end = cursor + node.unicode.length
                    if (end == offset) result = EmojiSpan(cursor, end, node)
                    cursor = end
                }
                else -> cursor += node.plainText().length
            }
        }
    }
    visit(nodes)
    return result
}

private fun expandEmojiShortcodes(value: String, emojiMap: Map<String, String>): String =
    EmojiShortcodePattern.replace(value) { match -> emojiMap[match.groupValues[1]] ?: match.value }

private fun loadEmojiShortcodes(context: Context): Map<String, String> = runCatching {
    val json = context.assets.open("preview/emoji-shortcodes.json").bufferedReader().use { it.readText() }
    val source = JSONObject(json)
    buildMap {
        source.keys().forEach { alias -> put(alias, source.getString(alias)) }
    }
}.getOrDefault(emptyMap())

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
