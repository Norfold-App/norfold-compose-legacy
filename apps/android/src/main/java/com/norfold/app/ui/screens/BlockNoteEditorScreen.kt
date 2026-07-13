@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.norfold.app.ui.screens

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.ActivityInfo
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.ClickableText
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Redo
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.CheckBox
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DragIndicator
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Fullscreen
import androidx.compose.material.icons.outlined.FormatListBulleted
import androidx.compose.material.icons.outlined.FormatListNumbered
import androidx.compose.material.icons.outlined.FormatQuote
import androidx.compose.material.icons.outlined.HorizontalRule
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.ScreenRotation
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.TableChart
import androidx.compose.material.icons.outlined.Title
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import coil.compose.AsyncImage
import com.norfold.app.domain.BlockCursor
import com.norfold.app.domain.BlockDocument
import com.norfold.app.domain.BlockEditorSession
import com.norfold.app.domain.BlockRenderMode
import com.norfold.app.domain.BoldInline
import com.norfold.app.domain.BulletListBlock
import com.norfold.app.domain.CalloutBlock
import com.norfold.app.domain.ChartBlock
import com.norfold.app.domain.CodeBlock
import com.norfold.app.domain.CodeInline
import com.norfold.app.domain.DividerBlock
import com.norfold.app.domain.DocumentBlock
import com.norfold.app.domain.EmbedBlock
import com.norfold.app.domain.EmbedMetadata
import com.norfold.app.domain.EmojiInline
import com.norfold.app.domain.FileBlock
import com.norfold.app.domain.HeadingBlock
import com.norfold.app.domain.ImageBlock
import com.norfold.app.domain.ImageLayout
import com.norfold.app.domain.InlineNode
import com.norfold.app.domain.InlineText
import com.norfold.app.domain.ItalicInline
import com.norfold.app.domain.LinkInline
import com.norfold.app.domain.ListItem
import com.norfold.app.domain.MathBlock
import com.norfold.app.domain.MathInline
import com.norfold.app.domain.MarkdownBlockCodec
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
import com.norfold.app.ui.NotesUiState
import com.norfold.app.ui.NotesViewModel
import com.norfold.app.ui.LocalContextualMenuColor
import com.norfold.app.ui.LocalContextualMenuStyle
import com.norfold.app.domain.ContextualMenuColor
import com.norfold.app.domain.ContextualMenuStyle
import com.norfold.app.ui.components.MarkdownPreview
import com.norfold.app.ui.components.EmbedMetadataResolver
import com.norfold.app.ui.components.ChartBuilderSheet
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import kotlin.math.roundToInt

@Composable
fun BlockNoteEditorScreen(
    state: NotesUiState,
    viewModel: NotesViewModel,
    modifier: Modifier,
    onOpenSidebar: () -> Unit,
) {
    val note = state.selectedNote ?: return
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val emojiShortcodes = remember(context) { loadEmojiShortcodes(context) }
    var title by remember(note.id) { mutableStateOf(note.title) }
    val session = remember(note.id) { BlockEditorSession(note.document) }
    var renderedDocument by remember(note.id) { mutableStateOf(session.document) }
    var revision by remember(note.id) { mutableIntStateOf(0) }
    var savedRevision by remember(note.id) { mutableIntStateOf(0) }
    var editMode by remember(note.id) { mutableStateOf(false) }
    var focusTarget by remember(note.id) { mutableStateOf<BlockCursor?>(null) }
    var rangeAnchorId by remember(note.id) { mutableStateOf<String?>(null) }
    var rangeExtentId by remember(note.id) { mutableStateOf<String?>(null) }
    var chartBuilderRequest by remember(note.id) { mutableStateOf<ChartBuilderRequest?>(null) }
    var activeSelection by remember(note.id) { mutableStateOf<EditorSelection?>(null) }
    var linkEditorRequest by remember(note.id) { mutableStateOf<LinkEditorRequest?>(null) }
    val listState = rememberLazyListState()
    val listScrolling by remember { derivedStateOf { listState.isScrollInProgress } }
    val rangeBounds = remember(renderedDocument, rangeAnchorId, rangeExtentId) {
        val anchorIndex = renderedDocument.blocks.indexOfFirst { it.id == rangeAnchorId }
        val extentIndex = renderedDocument.blocks.indexOfFirst { it.id == (rangeExtentId ?: rangeAnchorId) }
        if (anchorIndex >= 0 && extentIndex >= 0) minOf(anchorIndex, extentIndex)..maxOf(anchorIndex, extentIndex) else null
    }

    fun changed(cursor: BlockCursor? = null) {
        renderedDocument = session.document
        revision++
        focusTarget = cursor
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
        if (revision == 0 && title == note.title) return@LaunchedEffect
        val savingRevision = revision
        delay(500)
        viewModel.updateNoteDocument(note, title, session.document, session.dirtyBlockIds.toSet()).join()
        session.markSaved()
        savedRevision = savingRevision
    }
    val latestTitle by rememberUpdatedState(title)
    val latestDocument by rememberUpdatedState(renderedDocument)
    val latestRevision by rememberUpdatedState(revision)
    val latestSavedRevision by rememberUpdatedState(savedRevision)
    DisposableEffect(lifecycleOwner, note.id) {
        fun flushPendingDocument() {
            val dirtyIds = session.dirtyBlockIds.toSet()
            if (latestRevision == latestSavedRevision && latestTitle == note.title && dirtyIds.isEmpty()) return
            val savingRevision = latestRevision
            viewModel.updateNoteDocument(note, latestTitle, latestDocument, dirtyIds).invokeOnCompletion { error ->
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

    CompositionLocalProvider(LocalEmojiShortcodes provides emojiShortcodes) {
    Box(modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize()) {
        BlockEditorHeader(
            title = title,
            saveLabel = if (savedRevision == revision) "Saved · ${renderedDocument.plainText().split(Regex("\\s+")).count(String::isNotBlank)} words" else "Saving…",
            editMode = editMode,
            pinned = note.pinned,
            starred = note.starred,
            locked = note.locked,
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
            onStar = { viewModel.toggleStar(note) },
            onPin = { viewModel.togglePin(note) },
            onLock = { viewModel.toggleLock(note) },
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
                renderedDocument.blocks,
                key = { _, block -> block.id },
                contentType = { _, block -> block::class },
            ) { index, block ->
                SharedBlockRow(
                    modifier = if (editMode) Modifier.animateItem() else Modifier,
                    block = block,
                    index = index,
                    mode = if (editMode) BlockSurfaceMode.Edit else BlockSurfaceMode.View,
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
                        if (type == InsertBlockType.Chart) chartBuilderRequest = ChartBuilderRequest(anchorId = block.id)
                        else changed(session.insertAfter(block.id, newDocumentBlock(type)))
                    },
                    onDelete = { changed(session.delete(block.id)) },
                    onDuplicate = { changed(session.insertAfter(block.id, duplicateBlock(block))) },
                    rangeSelectionActive = rangeAnchorId != null,
                    selectedForRange = rangeBounds?.contains(index) == true,
                    onStartRangeSelection = {
                        rangeAnchorId = block.id
                        rangeExtentId = block.id
                    },
                    onExtendRangeSelection = { rangeExtentId = block.id },
                    scrolling = listScrolling,
                    onEditChart = { chart -> chartBuilderRequest = ChartBuilderRequest(editingId = chart.id, initialSpec = chart.vegaLiteSpec) },
                    onSelectionChange = { activeSelection = it },
                )
            }
            if (editMode) {
                item("insert-end") {
                    BlockInsertButton { type ->
                        val anchor = session.document.blocks.lastOrNull()?.id
                        if (type == InsertBlockType.Chart) chartBuilderRequest = ChartBuilderRequest(anchorId = anchor)
                        else changed(session.insertAfter(anchor, newDocumentBlock(type)))
                    }
                }
            }
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
                if (type == InsertBlockType.Chart) chartBuilderRequest = ChartBuilderRequest(anchorId = anchor)
                else changed(session.insertAfter(anchor, newDocumentBlock(type)))
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
) {
    var turnIntoOpen by remember { mutableStateOf(false) }
    var overflowOpen by remember { mutableStateOf(false) }
    var dragY by remember { mutableStateOf(0f) }
    Surface(
        modifier = modifier.fillMaxWidth().widthIn(max = 720.dp).focusProperties { canFocus = false },
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        tonalElevation = 8.dp,
        shadowElevation = 10.dp,
    ) {
        LazyRow(
            modifier = Modifier.fillMaxWidth().height(54.dp).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
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
            item("bullet") { ToolbarIconButton(Icons.Outlined.FormatListBulleted, "Bullet list") { onTurnInto(TextBlockTarget.Bullet) } }
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
    AlertDialog(
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
private enum class InsertBlockType { Text, Heading, BulletList, NumberedList, TodoList, Quote, Callout, Divider, Code, Table, Image, File, Embed, Chart, Math, Mermaid }

private fun DocumentBlock.renderModeOrNull(): BlockRenderMode? = when (this) {
    is CodeBlock -> renderMode
    is TableBlock -> renderMode
    is ChartBlock -> renderMode
    is MathBlock -> renderMode
    is MermaidBlock -> renderMode
    else -> null
}

private fun DocumentBlock.withRenderMode(mode: BlockRenderMode): DocumentBlock = when (this) {
    is CodeBlock -> copy(renderMode = mode)
    is TableBlock -> copy(renderMode = mode)
    is ChartBlock -> copy(renderMode = mode)
    is MathBlock -> copy(renderMode = mode)
    is MermaidBlock -> copy(renderMode = mode)
    else -> this
}

@Composable
private fun BlockEditorHeader(
    title: String,
    saveLabel: String,
    editMode: Boolean,
    pinned: Boolean,
    starred: Boolean,
    locked: Boolean,
    canUndo: Boolean,
    canRedo: Boolean,
    onTitleChange: (String) -> Unit,
    onBack: () -> Unit,
    onMenu: () -> Unit,
    onToggleEdit: () -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onStar: () -> Unit,
    onPin: () -> Unit,
    onLock: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") }
            IconButton(onClick = onMenu) { Icon(Icons.Outlined.Menu, "Workspace") }
            if (editMode) {
                BasicTextField(
                    title,
                    onTitleChange,
                    Modifier.weight(1f).padding(horizontal = 4.dp),
                    singleLine = true,
                    textStyle = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                )
            } else {
                Text(title, Modifier.weight(1f).padding(horizontal = 4.dp), fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 2)
            }
        }
        Box(Modifier.fillMaxWidth().height(48.dp)) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(end = 112.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AnimatedContent(editMode, label = "editor-mode") { editing ->
                    if (editing) Row {
                        IconButton(onClick = onUndo, enabled = canUndo) { Icon(Icons.AutoMirrored.Outlined.Undo, "Undo") }
                        IconButton(onClick = onRedo, enabled = canRedo) { Icon(Icons.AutoMirrored.Outlined.Redo, "Redo") }
                    } else IconButton(onClick = onToggleEdit) { Icon(Icons.Outlined.Edit, "Edit document") }
                }
                IconButton(onClick = onStar) { Icon(Icons.Outlined.Star, "Star", tint = if (starred) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant) }
                IconButton(onClick = onPin) { Icon(Icons.Outlined.PushPin, "Pin", tint = if (pinned) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant) }
                IconButton(onClick = onLock) { Icon(if (locked) Icons.Outlined.Lock else Icons.Outlined.LockOpen, "Lock") }
            }
            Surface(
                modifier = Modifier.align(Alignment.CenterEnd).width(108.dp),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = .96f),
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            ) {
                Text(
                    saveLabel,
                    Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                    fontSize = 10.sp,
                    maxLines = 1,
                    color = if (saveLabel.startsWith("Saved")) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary,
                )
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
    scrolling: Boolean,
    onEditChart: (ChartBlock) -> Unit,
    onSelectionChange: (EditorSelection) -> Unit,
) {
    var menu by remember(block.id) { mutableStateOf(false) }
    var insertMenu by remember(block.id) { mutableStateOf(false) }
    var dragY by remember(block.id) { mutableStateOf(0f) }
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
    val rowModifier = modifier.fillMaxWidth().offset { IntOffset(0, dragY.roundToInt()) }.zIndex(if (dragY == 0f) 0f else 2f).let {
        if (mode == BlockSurfaceMode.Edit) it.animateContentSize() else it
    }
    Row(
        rowModifier.background(
            if (selectedForRange) MaterialTheme.colorScheme.primaryContainer.copy(alpha = .55f) else Color.Transparent,
            RoundedCornerShape(6.dp),
        ),
        verticalAlignment = Alignment.Top,
    ) {
        if (mode == BlockSurfaceMode.Edit) {
            Icon(
                Icons.Outlined.DragIndicator,
                "Move block",
                Modifier.padding(top = 9.dp).size(22.dp).pointerInput(block.id) {
                    detectDragGestures(
                        onDrag = { change, amount ->
                            change.consume()
                            dragY += amount.y
                            dragTarget(dragY)?.takeIf { it != index }?.let { target ->
                                onMove(target)
                                dragY = 0f
                            }
                        },
                        onDragCancel = { dragY = 0f },
                        onDragEnd = { dragY = 0f },
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
            Modifier.weight(1f).then(
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
                scrolling = scrolling,
                onEditChart = onEditChart,
                onSelectionChange = onSelectionChange,
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
                    block.renderModeOrNull()?.let { renderMode ->
                        DropdownMenuItem(
                            text = { Text(if (renderMode == BlockRenderMode.Render) "Show source" else "Render") },
                            onClick = {
                                menu = false
                                onReplace(block.withRenderMode(if (renderMode == BlockRenderMode.Render) BlockRenderMode.Source else BlockRenderMode.Render))
                            },
                        )
                    }
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
                        is MathBlock -> DropdownMenuItem({ Text("Expand math editor") }, onClick = { menu = false; onReplace(block.copy(editorHeightDp = block.editorHeightDp + 80f)) })
                        is MermaidBlock -> DropdownMenuItem({ Text("Expand diagram editor") }, onClick = { menu = false; onReplace(block.copy(editorHeightDp = block.editorHeightDp + 80f)) })
                        is FileBlock -> DropdownMenuItem({ Text("Replace file") }, onClick = { menu = false; onReplace(block.copy(uri = "", name = "")) })
                        is EmbedBlock -> DropdownMenuItem({ Text("Refresh preview") }, onClick = { menu = false; onReplace(block.copy(metadata = EmbedMetadata())) })
                        else -> Unit
                    }
                    DropdownMenuItem({ Text("Duplicate") }, onClick = { menu = false; onDuplicate() })
                    if (rangeSelectable) DropdownMenuItem({ Text("Select block range") }, onClick = { menu = false; onStartRangeSelection() })
                    DropdownMenuItem({ Text("Turn into text") }, onClick = { menu = false; onReplace(ParagraphBlock(id = block.id, content = listOf(InlineText(block.plainText())))) })
                    DropdownMenuItem({ Text("Turn into heading") }, onClick = { menu = false; onReplace(HeadingBlock(id = block.id, level = 2, content = listOf(InlineText(block.plainText())))) })
                    DropdownMenuItem({ Text("Delete") }, onClick = { menu = false; onDelete() }, leadingIcon = { Icon(Icons.Outlined.Delete, null) })
                }
            }
        }
    }
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
    scrolling: Boolean,
    onEditChart: (ChartBlock) -> Unit,
    onSelectionChange: (EditorSelection) -> Unit,
) {
    when (block) {
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
            val content = block.children.firstOrNull()?.editableInlineContent() ?: listOf(InlineText(block.plainText()))
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
        }
        is CalloutBlock -> Surface(color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = .45f), shape = RoundedCornerShape(10.dp)) { Column(Modifier.padding(12.dp)) {
            if (mode == BlockSurfaceMode.Edit) {
                SimpleBlockTextField(block.title, { onReplace(block.copy(title = it)) }, Modifier.fillMaxWidth(), fontWeight = FontWeight.Bold)
                SimpleBlockTextField(block.children.joinToString("\n") { it.plainText() }, { onReplace(block.copy(children = listOf(ParagraphBlock(content = listOf(InlineText(it)))))) }, Modifier.fillMaxWidth().padding(top = 6.dp))
            } else { Text(block.title, fontWeight = FontWeight.Bold); Text(block.children.joinToString("\n") { it.plainText() }) }
        } }
        is DividerBlock -> Spacer(Modifier.fillMaxWidth().padding(vertical = 14.dp).height(1.dp).background(MaterialTheme.colorScheme.outlineVariant))
        is CodeBlock -> Column {
            if (mode == BlockSurfaceMode.Edit) SimpleLabeledBlockField("Language", block.language) { onReplace(block.copy(language = it.trim())) }
            EditableEngineCard(
                blockId = block.id,
                label = "Code${block.language.takeIf(String::isNotBlank)?.let { " · $it" }.orEmpty()}",
                source = block.code,
                markdown = "```${block.language}\n${block.code}\n```",
                mode = mode,
                editorHeightDp = block.editorHeightDp,
                onSourceChange = { onReplace(block.copy(code = it)) },
                onHeightChange = { onReplace(block.copy(editorHeightDp = it)) },
                scrolling = scrolling,
                nativeCode = true,
                showSource = block.renderMode == BlockRenderMode.Source,
            )
        }
        is TableBlock -> if (block.renderMode == BlockRenderMode.Source) TableSourceBlock(block, mode, onReplace) else NativeTable(block, mode, onReplace)
        is ImageBlock -> EditableImageBlock(block, mode, onReplace)
        is FileBlock -> EditableFileBlock(block, mode, onReplace)
        is EmbedBlock -> EditableEmbedBlock(block, mode, onReplace)
        is ChartBlock -> EditableChartBlock(block, mode, scrolling, onReplace, onEditChart)
        is MathBlock -> EditableEngineCard(
            blockId = block.id,
            label = "Math",
            source = block.tex,
            markdown = "$$\n${block.tex}\n$$",
            mode = mode,
            editorHeightDp = block.editorHeightDp,
            onSourceChange = { onReplace(block.copy(tex = it)) },
            onHeightChange = { onReplace(block.copy(editorHeightDp = it)) },
            scrolling = scrolling,
            showSource = block.renderMode == BlockRenderMode.Source,
        )
        is MermaidBlock -> EditableEngineCard(
            blockId = block.id,
            label = "Diagram",
            source = block.code,
            markdown = "```mermaid\n${block.code}\n```",
            mode = mode,
            editorHeightDp = block.editorHeightDp,
            onSourceChange = { onReplace(block.copy(code = it)) },
            onHeightChange = { onReplace(block.copy(editorHeightDp = it)) },
            scrolling = scrolling,
            showSource = block.renderMode == BlockRenderMode.Source,
        )
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
            val cursor = value.selection.end.coerceIn(0, value.text.length)
            val rect = layout.getCursorRect(cursor)
            bringIntoView.bringIntoView(
                Rect(
                    left = (rect.left - 12f).coerceAtLeast(0f),
                    top = (rect.top - 20f).coerceAtLeast(0f),
                    right = rect.right + 12f,
                    bottom = rect.bottom + 48f,
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
private fun EditableChartBlock(
    block: ChartBlock,
    mode: BlockSurfaceMode,
    scrolling: Boolean,
    onReplace: (DocumentBlock) -> Unit,
    onEditChart: (ChartBlock) -> Unit,
) {
    Column {
        if (mode == BlockSurfaceMode.Edit) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = { onEditChart(block) }) { Text("Edit chart") }
                TextButton(onClick = {
                    onReplace(block.copy(renderMode = if (block.renderMode == BlockRenderMode.Source) BlockRenderMode.Render else BlockRenderMode.Source))
                }) { Text(if (block.renderMode == BlockRenderMode.Source) "Render" else "Advanced") }
            }
        }
        EditableEngineCard(
            blockId = block.id,
            label = "Chart",
            source = block.vegaLiteSpec,
            markdown = "```vega-lite\n${block.vegaLiteSpec}\n```",
            mode = mode,
            editorHeightDp = block.editorHeightDp,
            onSourceChange = { onReplace(block.copy(vegaLiteSpec = it)) },
            onHeightChange = { onReplace(block.copy(editorHeightDp = it)) },
            scrolling = scrolling,
            showSource = block.renderMode == BlockRenderMode.Source,
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
        val rect = layout.getCursorRect(value.selection.end.coerceIn(0, value.text.length))
        bringIntoView.bringIntoView(Rect((rect.left - 12f).coerceAtLeast(0f), (rect.top - 20f).coerceAtLeast(0f), rect.right + 12f, rect.bottom + 48f))
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
        val rect = layout.getCursorRect(value.selection.end.coerceIn(0, value.text.length))
        bringIntoView.bringIntoView(Rect((rect.left - 12f).coerceAtLeast(0f), (rect.top - 20f).coerceAtLeast(0f), rect.right + 12f, rect.bottom + 48f))
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
    LaunchedEffect(text) { if (text != value.text) value = value.copy(text = text, selection = TextRange(value.selection.start.coerceAtMost(text.length))) }
    BasicTextField(
        value = value,
        onValueChange = { value = it; onChange(it.text) },
        modifier = modifier,
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

@Composable
private fun EditableEngineCard(
    blockId: String,
    label: String,
    source: String,
    markdown: String,
    mode: BlockSurfaceMode,
    editorHeightDp: Float,
    onSourceChange: (String) -> Unit,
    onHeightChange: (Float) -> Unit,
    scrolling: Boolean,
    nativeCode: Boolean = false,
    showSource: Boolean = false,
) {
    var hidden by remember(blockId) { mutableStateOf(false) }
    var fullScreen by rememberSaveable(blockId) { mutableStateOf(false) }
    var landscape by rememberSaveable(blockId) { mutableStateOf(false) }
    var liveHeight by remember(blockId) { mutableStateOf(editorHeightDp.coerceIn(96f, 420f)) }
    var resizing by remember(blockId) { mutableStateOf(false) }
    val density = LocalDensity.current
    val primaryArgb = MaterialTheme.colorScheme.primary.toArgb()
    val accentHex = remember(primaryArgb) { "#%06X".format(primaryArgb and 0xFFFFFF) }
    LaunchedEffect(editorHeightDp) { if (!resizing) liveHeight = editorHeightDp.coerceIn(96f, 420f) }
    Surface(shape = RoundedCornerShape(10.dp), border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Column(Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth().padding(start = 10.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(label, Modifier.weight(1f), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = { hidden = !hidden }) { Text(if (hidden) "Show" else "Hide") }
                IconButton(onClick = { landscape = false; fullScreen = true }) { Icon(Icons.Outlined.Fullscreen, "Full screen") }
                IconButton(onClick = { landscape = true; fullScreen = true }) { Icon(Icons.Outlined.ScreenRotation, "Landscape") }
            }
            if (!hidden) {
                if (showSource) {
                    Column {
                        var value by remember(blockId) { mutableStateOf(TextFieldValue(source)) }
                        var focused by remember(blockId) { mutableStateOf(false) }
                        LaunchedEffect(source, focused) {
                            if (!focused && value.composition == null && source != value.text) {
                                value = value.copy(text = source, selection = TextRange(value.selection.start.coerceAtMost(source.length), value.selection.end.coerceAtMost(source.length)), composition = null)
                            }
                        }
                        BasicTextField(
                            value = value,
                            onValueChange = { changed -> if (mode == BlockSurfaceMode.Edit) { value = changed; onSourceChange(changed.text) } },
                            readOnly = mode == BlockSurfaceMode.View,
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
                        if (mode == BlockSurfaceMode.Edit) {
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .height(30.dp)
                                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .22f)),
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
                } else if (nativeCode) {
                    NativeCodeSurface(source)
                } else {
                    DeferredEnginePreview(markdown, accentHex, scrolling)
                }
            }
        }
    }
    if (fullScreen) {
        EngineFullscreenDialog(
            label = label,
            markdown = markdown,
            accentHex = accentHex,
            landscape = landscape,
            nativeCode = source.takeIf { nativeCode },
            onDismiss = { fullScreen = false; landscape = false },
        )
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
private fun EngineFullscreenDialog(
    label: String,
    markdown: String,
    accentHex: String,
    landscape: Boolean,
    nativeCode: String? = null,
    onDismiss: () -> Unit,
) {
    StableLandscapeOrientationEffect(landscape)
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().padding(12.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(label, Modifier.weight(1f), fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    TextButton(onClick = onDismiss) { Text("Close") }
                }
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    if (nativeCode != null) {
                        NativeCodeSurface(nativeCode)
                    } else {
                        MarkdownPreview(
                            markdown = markdown,
                            dark = androidx.compose.foundation.isSystemInDarkTheme(),
                            accentHex = accentHex,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DeferredEnginePreview(markdown: String, accentHex: String, scrolling: Boolean) {
    var mounted by remember(markdown) { mutableStateOf(false) }
    LaunchedEffect(markdown, scrolling) {
        if (!mounted && !scrolling) {
            delay(300)
            mounted = true
        }
    }
    if (mounted) {
        MarkdownPreview(
            markdown = markdown,
            dark = androidx.compose.foundation.isSystemInDarkTheme(),
            accentHex = accentHex,
            modifier = Modifier.fillMaxWidth(),
        )
    } else {
        Box(Modifier.fillMaxWidth().height(48.dp), contentAlignment = Alignment.CenterStart) {
            Text("Rendering…", Modifier.padding(horizontal = 12.dp), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun TableSourceBlock(
    table: TableBlock,
    mode: BlockSurfaceMode,
    onReplace: (DocumentBlock) -> Unit,
) {
    val source = remember(table.headers, table.rows, table.columnAlignments) {
        MarkdownBlockCodec.export(BlockDocument(listOf(table)))
    }
    var value by remember(table.id) { mutableStateOf(TextFieldValue(source)) }
    var focused by remember(table.id) { mutableStateOf(false) }
    LaunchedEffect(source, focused) {
        if (!focused && value.composition == null && source != value.text) {
            value = value.copy(
                text = source,
                selection = TextRange(value.selection.start.coerceAtMost(source.length), value.selection.end.coerceAtMost(source.length)),
                composition = null,
            )
        }
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .35f),
    ) {
        BasicTextField(
            value = value,
            onValueChange = { changed ->
                if (mode != BlockSurfaceMode.Edit) return@BasicTextField
                value = changed
                val parsed = runCatching { MarkdownBlockCodec.import(changed.text).blocks.singleOrNull() as? TableBlock }.getOrNull()
                if (parsed != null) {
                    onReplace(
                        parsed.copy(
                            id = table.id,
                            columnWidthsDp = table.columnWidthsDp,
                            columnAlignments = table.columnAlignments,
                            renderMode = BlockRenderMode.Source,
                        ),
                    )
                }
            },
            readOnly = mode == BlockSurfaceMode.View,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 120.dp, max = 360.dp)
                .verticalScroll(rememberScrollState())
                .padding(12.dp)
                .onFocusChanged { focused = it.isFocused },
            textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 19.sp, color = MaterialTheme.colorScheme.onSurface),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        )
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
    var fullScreen by rememberSaveable(table.id) { mutableStateOf(false) }
    var landscape by rememberSaveable(table.id) { mutableStateOf(false) }
    val density = LocalDensity.current
    val primaryArgb = MaterialTheme.colorScheme.primary.toArgb()
    val accentHex = remember(primaryArgb) { "#%06X".format(primaryArgb and 0xFFFFFF) }
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
        IconButton(onClick = { landscape = false; fullScreen = true }) { Icon(Icons.Outlined.Fullscreen, "Full screen table") }
        IconButton(onClick = { landscape = true; fullScreen = true }) { Icon(Icons.Outlined.ScreenRotation, "Landscape table") }
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
    if (fullScreen) {
        EngineFullscreenDialog(
            label = "Table",
            markdown = MarkdownBlockCodec.export(BlockDocument(listOf(table))),
            accentHex = accentHex,
            landscape = landscape,
            onDismiss = { fullScreen = false; landscape = false },
        )
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
            LaunchedEffect(plain) { if (plain != value.text) value = value.copy(text = plain, selection = TextRange(value.selection.start.coerceAtMost(plain.length))) }
            BasicTextField(
                value = value,
                onValueChange = { changed -> value = changed; onChange(listOf(InlineText(changed.text))) },
                modifier = Modifier.fillMaxWidth().padding(10.dp),
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
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
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
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Embed", Modifier.weight(1f), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = { hidden = !hidden }, enabled = block.url.isNotBlank()) { Text(if (hidden) "Show" else "Hide") }
            IconButton(onClick = { landscape = false; fullScreen = true }, enabled = block.url.isNotBlank()) { Icon(Icons.Outlined.Fullscreen, "Full screen embed") }
            IconButton(onClick = { landscape = true; fullScreen = true }, enabled = block.url.isNotBlank()) { Icon(Icons.Outlined.ScreenRotation, "Landscape embed") }
        }
        if (mode == BlockSurfaceMode.Edit) SimpleLabeledBlockField("Embed URL", block.url) { onReplace(block.copy(url = it, metadata = if (it == block.url) block.metadata else com.norfold.app.domain.EmbedMetadata())) }
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
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().padding(16.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(embed.metadata.title.ifBlank { "Embed" }, Modifier.weight(1f), fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    TextButton(onClick = onDismiss) { Text("Close") }
                }
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Box(Modifier.widthIn(max = 760.dp)) { EmbedCard(embed) }
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
    is DividerBlock -> DividerBlock()
    is CodeBlock -> CodeBlock(language = block.language, code = block.code, editorHeightDp = block.editorHeightDp, renderMode = block.renderMode)
    is TableBlock -> TableBlock(headers = block.headers, rows = block.rows, columnWidthsDp = block.columnWidthsDp, columnAlignments = block.columnAlignments, renderMode = block.renderMode)
    is ImageBlock -> ImageBlock(source = block.source, caption = block.caption, layout = block.layout, displayHeightDp = block.displayHeightDp)
    is FileBlock -> FileBlock(name = block.name, mimeType = block.mimeType, sizeBytes = block.sizeBytes, uri = block.uri)
    is EmbedBlock -> EmbedBlock(url = block.url, metadata = block.metadata, displayHeightDp = block.displayHeightDp)
    is ChartBlock -> ChartBlock(vegaLiteSpec = block.vegaLiteSpec, editorHeightDp = block.editorHeightDp, renderMode = block.renderMode)
    is MathBlock -> MathBlock(tex = block.tex, display = block.display, editorHeightDp = block.editorHeightDp, renderMode = block.renderMode)
    is MermaidBlock -> MermaidBlock(code = block.code, editorHeightDp = block.editorHeightDp, renderMode = block.renderMode)
}

private fun DocumentBlock.withBlockId(id: String): DocumentBlock = when (this) {
    is ParagraphBlock -> copy(id = id)
    is HeadingBlock -> copy(id = id)
    is BulletListBlock -> copy(id = id)
    is NumberedListBlock -> copy(id = id)
    is TodoListBlock -> copy(id = id)
    is QuoteBlock -> copy(id = id)
    is CalloutBlock -> copy(id = id)
    is DividerBlock -> copy(id = id)
    is CodeBlock -> copy(id = id)
    is TableBlock -> copy(id = id)
    is ImageBlock -> copy(id = id)
    is FileBlock -> copy(id = id)
    is EmbedBlock -> copy(id = id)
    is ChartBlock -> copy(id = id)
    is MathBlock -> copy(id = id)
    is MermaidBlock -> copy(id = id)
}

@Composable
private fun InlineRichText(
    nodes: List<InlineNode>,
    modifier: Modifier = Modifier,
    style: TextStyle = TextStyle.Default,
) {
    val colors = MaterialTheme.colorScheme
    if (nodes.containsInlineMath()) {
        val primaryArgb = colors.primary.toArgb()
        val accentHex = remember(primaryArgb) { "#%06X".format(primaryArgb and 0xFFFFFF) }
        val markdown = remember(nodes) {
            MarkdownBlockCodec.export(BlockDocument(listOf(ParagraphBlock(content = nodes))))
        }
        MarkdownPreview(
            markdown = markdown,
            dark = isSystemInDarkTheme(),
            accentHex = accentHex,
            modifier = modifier.fillMaxWidth(),
        )
    } else {
        val annotated = rememberInlineAnnotated(nodes)
        val uriHandler = LocalUriHandler.current
        ClickableText(
            text = annotated,
            modifier = modifier,
            style = TextStyle(color = colors.onSurface).merge(style),
            onClick = { offset ->
                annotated.getStringAnnotations(tag = "URL", start = offset, end = offset)
                    .firstOrNull()
                    ?.item
                    ?.let { url -> runCatching { uriHandler.openUri(url) } }
            },
        )
    }
}

private fun List<InlineNode>.containsInlineMath(): Boolean = any { node ->
    when (node) {
        is MathInline -> true
        is BoldInline -> node.children.containsInlineMath()
        is ItalicInline -> node.children.containsInlineMath()
        is StrikethroughInline -> node.children.containsInlineMath()
        is LinkInline -> node.children.containsInlineMath()
        else -> false
    }
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
                pushStringAnnotation(tag = "URL", annotation = node.url)
                withStyle(inherited.merge(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline))) {
                    appendNodes(node.children, inherited)
                }
                pop()
            }
            is MathInline -> withStyle(inherited.merge(SpanStyle(fontFamily = FontFamily.Serif))) { append(node.tex) }
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
