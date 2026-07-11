package com.norfold.app.ui.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.FormatListBulleted
import androidx.compose.material.icons.automirrored.outlined.Help
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.automirrored.outlined.Redo
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.Book
import androidx.compose.material.icons.outlined.CheckBox
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DragIndicator
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FormatBold
import androidx.compose.material.icons.outlined.FormatItalic
import androidx.compose.material.icons.outlined.FormatListNumbered
import androidx.compose.material.icons.outlined.FormatQuote
import androidx.compose.material.icons.outlined.FormatSize
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.Title
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.TableChart
import androidx.compose.material3.Button
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.norfold.app.domain.Destination
import com.norfold.app.domain.EditorFontFamily
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import com.norfold.app.domain.Attachment
import com.norfold.app.domain.EditorLineWidth
import com.norfold.app.domain.EditorMode
import com.norfold.app.domain.MarkdownExporter
import com.norfold.app.domain.Note
import com.norfold.app.domain.NoteEmbedItem
import com.norfold.app.domain.NoteEmbedType
import com.norfold.app.domain.PageBlock
import com.norfold.app.domain.PageBlockType
import com.norfold.app.domain.PageDocument
import com.norfold.app.ui.NotesUiState
import com.norfold.app.ui.NotesViewModel
import com.norfold.app.ui.components.EmptyEditor
import com.norfold.app.ui.components.MarkdownPreview as MarkdownBodyPreview
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

@Composable
fun NoteEditorScreen(
    state: NotesUiState,
    viewModel: NotesViewModel,
    modifier: Modifier,
    onPickAttachment: () -> Unit = {},
    onPickEmbed: () -> Unit = {},
    onPickCover: () -> Unit = {},
    onOpenSidebar: () -> Unit = {},
) {
    val note = state.selectedNote
    if (note == null) {
        EmptyEditor(viewModel::createNote, modifier)
        return
    }
    var title by remember(note.id) { mutableStateOf(note.title) }
    var body by remember(note.id) { mutableStateOf(TextFieldValue(note.bodyMarkdown)) }
    val undoStack = remember(note.id) { mutableStateListOf<TextFieldValue>() }
    val redoStack = remember(note.id) { mutableStateListOf<TextFieldValue>() }
    var readOnlySource by remember(note.id) { mutableStateOf(false) }
    var toolbarCollapsed by remember(note.id) { mutableStateOf(false) }
    var toolbarOffset by remember(note.id) { mutableStateOf(androidx.compose.ui.geometry.Offset(18f, 18f)) }
    val context = LocalContext.current
    fun persistPickedUri(uri: Uri) {
        runCatching {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
    fun commitBody(value: TextFieldValue, trackHistory: Boolean = true) {
        if (trackHistory && value.text != body.text) {
            undoStack += body
            if (undoStack.size > 80) undoStack.removeAt(0)
            redoStack.clear()
        }
        body = value
    }
    fun undo() {
        val previous = undoStack.removeLastOrNull() ?: return
        redoStack += body
        commitBody(previous, trackHistory = false)
    }
    fun redo() {
        val next = redoStack.removeLastOrNull() ?: return
        undoStack += body
        commitBody(next, trackHistory = false)
    }
    fun attachNoteLink(target: Note) {
        commitBody(insertBlock(body, "\n[[${target.title.ifBlank { "Untitled note" }}]]\n"))
    }
    LaunchedEffect(note.id, title, body.text) {
        delay(400)
        viewModel.updateNote(note, title, body.text)
    }
    val inlineImagePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        persistPickedUri(uri)
        val label = uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: "Image"
        commitBody(insertBlock(body, "\n![$label]($uri)\n"))
    }
    val inlineFilePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        persistPickedUri(uri)
        val label = uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: "File"
        commitBody(insertBlock(body, "\n[$label]($uri)\n"))
    }
    val maxLineWidth = when (state.settings.editorLineWidth) {
        EditorLineWidth.Narrow -> 560.dp
        EditorLineWidth.Comfortable -> 720.dp
        EditorLineWidth.Wide -> 980.dp
    }
    val editorFont = when (state.settings.editorFontFamily) {
        EditorFontFamily.Sans -> FontFamily.SansSerif
        EditorFontFamily.Serif -> FontFamily.Serif
    }

    BoxWithConstraints(
        modifier = modifier
            .padding(horizontal = 16.dp)
            .padding(top = 10.dp)
            .imePadding(),
    ) {
        val density = LocalDensity.current
        val imeVisible = WindowInsets.ime.getBottom(density) > 0
        val maxFloatingX = with(density) { (maxWidth - 164.dp).toPx().coerceAtLeast(0f) }
        val maxFloatingY = with(density) { (maxHeight - 118.dp).toPx().coerceAtLeast(0f) }
        Column(
            Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
        // Top action bar
        EditorTopBar(
            note = note,
            title = title,
            body = body.text,
            state = state,
            viewModel = viewModel,
            onPickCover = onPickCover,
            maxLineWidth = maxLineWidth,
            canUndo = undoStack.isNotEmpty(),
            canRedo = redoStack.isNotEmpty(),
            readOnlySource = readOnlySource,
            onUndo = ::undo,
            onRedo = ::redo,
            onToggleReadOnly = { readOnlySource = !readOnlySource },
            onOpenSidebar = onOpenSidebar,
            onSave = { viewModel.updateNote(note, title, body.text) },
        ) { newTitle ->
            title = newTitle
        }

        // Page editor / Source / Preview
        if (state.editorMode == EditorMode.Page) {
            FlatPageEditor(
                note = note.copy(title = title, bodyMarkdown = body.text),
                notes = state.notes,
                title = title,
                editorFont = editorFont,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .widthIn(max = maxLineWidth),
                onPickAttachment = { inlineFilePicker.launch(arrayOf("*/*")) },
                onPickImage = { inlineImagePicker.launch(arrayOf("image/*")) },
                onPickCover = onPickCover,
                onAttachNote = ::attachNoteLink,
                onTitleChange = { changed ->
                    title = changed
                    viewModel.updateNote(note, changed, body.text)
                },
                onMarkdownChange = { commitBody(TextFieldValue(it)) },
                onInsertBlock = { block -> commitBody(insertBlock(body, block)) },
            )
        } else if (state.editorMode == EditorMode.Edit) {
            Surface(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .widthIn(max = maxLineWidth),
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.6f),
            ) {
                Box(Modifier.fillMaxSize()) {
                    // Subtle line numbers / margin guide
                    Box(
                        Modifier
                            .align(Alignment.TopStart)
                            .padding(start = 8.dp, top = 16.dp, bottom = 16.dp)
                            .width(2.dp)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(999.dp))
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)),
                    )
                    BasicTextField(
                        value = body,
                        onValueChange = { if (!readOnlySource) commitBody(it) },
                        readOnly = readOnlySource,
                        textStyle = TextStyle(
                            fontSize = 16.sp,
                            lineHeight = 26.sp,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontFamily = editorFont,
                        ),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 20.dp, vertical = 16.dp)
                            .verticalScroll(rememberScrollState()),
                    )
                }
            }
        } else {
            MarkdownPreview(
                note.copy(title = title, bodyMarkdown = body.text),
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .widthIn(max = maxLineWidth),
                onPickCover = onPickCover,
            )
        }

        // Bottom formatting toolbar (only useful while editing)
        if (state.editorMode != EditorMode.Preview && !toolbarCollapsed) {
            EditorBottomToolbar(
                maxLineWidth = maxLineWidth,
                imeVisible = imeVisible,
                onCollapse = { toolbarCollapsed = true },
                onAttach = { inlineFilePicker.launch(arrayOf("*/*")) },
                onEmbed = { inlineFilePicker.launch(arrayOf("*/*")) },
                onImage = { inlineImagePicker.launch(arrayOf("image/*")) },
            ) { action -> commitBody(applyMarkdown(body, action)) }
        }
        }
        if (state.editorMode != EditorMode.Preview && toolbarCollapsed) {
            FloatingEditorToolbar(
                modifier = Modifier.offset { IntOffset(toolbarOffset.x.roundToInt(), toolbarOffset.y.roundToInt()) },
                onDrag = { delta ->
                    toolbarOffset = androidx.compose.ui.geometry.Offset(
                        x = (toolbarOffset.x + delta.x).coerceIn(0f, maxFloatingX),
                        y = (toolbarOffset.y + delta.y).coerceIn(0f, maxFloatingY),
                    )
                },
                onExpand = { toolbarCollapsed = false },
                onFormat = { action -> commitBody(applyMarkdown(body, action)) },
            )
        }
    }
}

@Composable
private fun FlatPageEditor(
    note: Note,
    notes: List<Note>,
    title: String,
    editorFont: FontFamily,
    modifier: Modifier,
    onPickAttachment: () -> Unit,
    onPickImage: () -> Unit,
    onPickCover: () -> Unit,
    onAttachNote: (Note) -> Unit,
    onTitleChange: (String) -> Unit,
    onMarkdownChange: (String) -> Unit,
    onInsertBlock: (String) -> Unit,
) {
    val blocks = remember(note.id) {
        mutableStateListOf<PageBlock>().apply { addAll(PageDocument.parse(note.bodyMarkdown).blocks) }
    }
    var lastEmitted by remember(note.id) { mutableStateOf(note.bodyMarkdown.trimEnd()) }
    LaunchedEffect(note.bodyMarkdown) {
        if (note.bodyMarkdown.trimEnd() != lastEmitted) {
            blocks.clear()
            blocks.addAll(PageDocument.parse(note.bodyMarkdown).blocks)
            lastEmitted = note.bodyMarkdown.trimEnd()
        }
    }
    fun emit() {
        lastEmitted = PageDocument(blocks.toList()).toMarkdown()
        onMarkdownChange(lastEmitted)
    }
    fun insertAt(index: Int, block: PageBlock) {
        blocks.add(index.coerceIn(0, blocks.size), block)
        emit()
    }
    fun removeAt(index: Int) {
        if (index !in blocks.indices) return
        blocks.removeAt(index)
        emit()
    }
    fun mergeWithPrevious(index: Int) {
        if (index !in 1 until blocks.size) return
        val previous = blocks[index - 1]
        val current = blocks[index]
        if (previous.type in NonTextPageBlocks || current.type in NonTextPageBlocks) return
        blocks[index - 1] = previous.copy(text = listOf(previous.text, current.text).filter { it.isNotEmpty() }.joinToString("\n"))
        blocks.removeAt(index)
        emit()
    }
    val attachableNotes = remember(notes, note.id) { notes.filter { it.id != note.id && !it.archived }.take(6) }

    LazyColumn(
        modifier = modifier,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 12.dp, end = 12.dp, top = 14.dp, bottom = 88.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        item(key = "page-title-${note.id}") {
            BasicTextField(
                value = title,
                onValueChange = onTitleChange,
                textStyle = TextStyle(
                    color = MaterialTheme.colorScheme.onSurface,
                    fontFamily = editorFont,
                    fontWeight = FontWeight.Black,
                    fontSize = 32.sp,
                    lineHeight = 38.sp,
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 34.dp, vertical = 12.dp),
                singleLine = true,
            )
        }
        if (!note.coverUri.isNullOrBlank()) {
            item(key = "page-cover-${note.id}") {
                AsyncImage(
                    model = note.coverUri,
                    contentDescription = "Note cover",
                    modifier = Modifier.fillMaxWidth().height(210.dp).padding(horizontal = 34.dp, vertical = 8.dp)
                        .clip(RoundedCornerShape(10.dp)).clickable(onClick = onPickCover),
                    contentScale = ContentScale.Crop,
                )
            }
        }
        itemsIndexed(blocks, key = { _, block -> block.id }) { index, block ->
            FlatPageBlockRow(
                block = block,
                editorFont = editorFont,
                onChange = { changed ->
                    val current = blocks.indexOfFirst { it.id == block.id }
                    if (current >= 0) {
                        blocks[current] = changed
                        emit()
                    }
                },
                onInsertAfter = { type ->
                    val current = blocks.indexOfFirst { it.id == block.id }
                    insertAt(current + 1, newPageBlock(type))
                },
                onSplit = { before, after ->
                    val current = blocks.indexOfFirst { it.id == block.id }
                    if (current >= 0) {
                        blocks[current] = block.copy(text = before)
                        blocks.add(current + 1, block.copy(id = java.util.UUID.randomUUID().toString(), text = after))
                        emit()
                    }
                },
                onMergePrevious = { mergeWithPrevious(blocks.indexOfFirst { it.id == block.id }) },
                onMove = { delta ->
                    val current = blocks.indexOfFirst { it.id == block.id }
                    val target = (current + delta).coerceIn(0, blocks.lastIndex)
                    if (current >= 0 && target != current) {
                        val moved = blocks.removeAt(current)
                        blocks.add(target, moved)
                        emit()
                    }
                },
                onDelete = { removeAt(blocks.indexOfFirst { it.id == block.id }) },
            )
        }
        item(key = "page-insert-${note.id}") {
            PageInsertRow(
                onInsert = { insertAt(blocks.size, newPageBlock(it)) },
                onPickImage = onPickImage,
                onPickAttachment = onPickAttachment,
                onPickCover = onPickCover,
                attachableNotes = attachableNotes,
                onAttachNote = onAttachNote,
            )
        }
    }
}

@Composable
private fun FlatPageBlockRow(
    block: PageBlock,
    editorFont: FontFamily,
    onChange: (PageBlock) -> Unit,
    onInsertAfter: (PageBlockType) -> Unit,
    onSplit: (String, String) -> Unit,
    onMergePrevious: () -> Unit,
    onMove: (Int) -> Unit,
    onDelete: () -> Unit,
) {
    var focused by remember(block.id) { mutableStateOf(false) }
    var menuOpen by remember(block.id) { mutableStateOf(false) }
    var value by remember(block.id) { mutableStateOf(TextFieldValue(block.text)) }
    var dragY by remember(block.id) { mutableStateOf(0f) }
    val step = with(LocalDensity.current) { 54.dp.toPx() }
    Row(
        Modifier.fillMaxWidth().offset { IntOffset(0, dragY.roundToInt()) }.zIndex(if (dragY != 0f) 2f else 0f),
        verticalAlignment = Alignment.Top,
    ) {
        Box(Modifier.width(32.dp).heightIn(min = 44.dp), contentAlignment = Alignment.TopCenter) {
            if (focused || dragY != 0f) {
                Icon(
                    Icons.Outlined.DragIndicator,
                    "Reorder block",
                    Modifier.padding(top = 10.dp).size(19.dp).pointerInput(block.id) {
                        detectDragGestures(
                            onDragEnd = {
                                val delta = (dragY / step).roundToInt()
                                dragY = 0f
                                if (delta != 0) onMove(delta)
                            },
                            onDragCancel = { dragY = 0f },
                            onDrag = { change, amount -> change.consume(); dragY += amount.y },
                        )
                    },
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Box(Modifier.width(28.dp).heightIn(min = 44.dp), contentAlignment = Alignment.TopCenter) {
            if (focused) {
                IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Outlined.Add, "Insert block", Modifier.size(18.dp))
                }
                PageBlockMenu(menuOpen, { menuOpen = false }) { onInsertAfter(it); menuOpen = false }
            }
        }
        when (block.type) {
            PageBlockType.Divider -> Box(Modifier.weight(1f).padding(vertical = 22.dp).height(1.dp).background(MaterialTheme.colorScheme.outline))
            PageBlockType.Image -> Column(Modifier.weight(1f).padding(vertical = 5.dp)) {
                AsyncImage(block.target, block.text, Modifier.fillMaxWidth().heightIn(min = 120.dp).clip(RoundedCornerShape(8.dp)), contentScale = ContentScale.FillWidth)
                if (block.text.isNotBlank()) Text(block.text, Modifier.padding(top = 5.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
            }
            else -> {
                Row(Modifier.weight(1f), verticalAlignment = Alignment.Top) {
                    PageBlockPrefix(block) { onChange(block.copy(checked = !block.checked)) }
                    BasicTextField(
                        value = value,
                        onValueChange = { changed ->
                            val newline = changed.text.indexOf('\n')
                            if (newline >= 0 && block.type !in setOf(PageBlockType.Code, PageBlockType.Table, PageBlockType.Raw)) {
                                onSplit(changed.text.substring(0, newline), changed.text.substring(newline + 1))
                            } else {
                                value = changed
                                onChange(block.copy(text = changed.text))
                            }
                        },
                        textStyle = pageBlockTextStyle(block, editorFont),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        modifier = Modifier.fillMaxWidth().padding(vertical = 9.dp)
                            .onFocusChanged { focused = it.isFocused }
                            .onPreviewKeyEvent { event ->
                                if (event.type == KeyEventType.KeyDown && event.key == Key.Backspace && value.text.isEmpty()) {
                                    onMergePrevious()
                                    true
                                } else false
                            },
                    )
                    if (focused) {
                        IconButton(onClick = onDelete, modifier = Modifier.size(34.dp)) {
                            Icon(Icons.Outlined.Delete, "Delete block", Modifier.size(17.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PageBlockPrefix(block: PageBlock, onToggle: () -> Unit) {
    when (block.type) {
        PageBlockType.Bullet -> Text("•", Modifier.padding(top = 9.dp, end = 9.dp), color = MaterialTheme.colorScheme.primary)
        PageBlockType.Numbered -> Text("1.", Modifier.padding(top = 9.dp, end = 9.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        PageBlockType.Checklist -> IconButton(onClick = onToggle, modifier = Modifier.padding(top = 4.dp).size(30.dp)) {
            Icon(if (block.checked) Icons.Outlined.CheckBox else Icons.Outlined.RadioButtonUnchecked, "Toggle checklist", Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
        }
        PageBlockType.Quote -> Box(Modifier.padding(top = 7.dp, end = 10.dp).width(3.dp).height(34.dp).background(MaterialTheme.colorScheme.primary, RoundedCornerShape(3.dp)))
        else -> Unit
    }
}

@Composable
private fun PageBlockMenu(expanded: Boolean, onDismiss: () -> Unit, onSelect: (PageBlockType) -> Unit) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        PageBlockType.entries.filterNot { it in setOf(PageBlockType.Raw, PageBlockType.Image, PageBlockType.File, PageBlockType.NoteLink) }.forEach { type ->
            DropdownMenuItem(text = { Text(pageBlockLabel(type)) }, onClick = { onSelect(type) })
        }
    }
}

@Composable
private fun PageInsertRow(
    onInsert: (PageBlockType) -> Unit,
    onPickImage: () -> Unit,
    onPickAttachment: () -> Unit,
    onPickCover: () -> Unit,
    attachableNotes: List<Note>,
    onAttachNote: (Note) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(start = 60.dp, top = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth().clickable { expanded = true }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Add, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("  Type / to insert a block", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
        }
        PageBlockMenu(expanded, { expanded = false }) { onInsert(it); expanded = false }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BlockInsertChip("Image", onPickImage)
            BlockInsertChip("File", onPickAttachment)
            BlockInsertChip("Cover", onPickCover)
            attachableNotes.forEach { note -> BlockInsertChip("[[${note.title.take(18)}]]") { onAttachNote(note) } }
        }
    }
}

@Composable
private fun BlockInsertChip(label: String, onClick: () -> Unit) {
    AssistChip(
        onClick = onClick,
        label = { Text(label, maxLines = 1) },
        leadingIcon = { Icon(Icons.Outlined.Add, null, Modifier.size(16.dp)) },
    )
}

private val NonTextPageBlocks = setOf(PageBlockType.Divider, PageBlockType.Image, PageBlockType.File)

private fun newPageBlock(type: PageBlockType): PageBlock = PageBlock(
    type = type,
    text = when (type) {
        PageBlockType.Heading -> "Heading"
        PageBlockType.Checklist -> "New task"
        PageBlockType.Table -> "| Column | Detail |\n| --- | --- |\n|  |  |"
        PageBlockType.Code -> "code"
        PageBlockType.Quote -> "Quote"
        else -> ""
    },
    level = if (type == PageBlockType.Heading) 2 else 0,
    language = if (type == PageBlockType.Code) "text" else "",
)

private fun pageBlockLabel(type: PageBlockType): String = when (type) {
    PageBlockType.Paragraph -> "Text"
    PageBlockType.Heading -> "Heading"
    PageBlockType.Bullet -> "Bulleted list"
    PageBlockType.Numbered -> "Numbered list"
    PageBlockType.Checklist -> "Checklist"
    PageBlockType.Quote -> "Quote"
    PageBlockType.Code -> "Code"
    PageBlockType.Divider -> "Divider"
    PageBlockType.Table -> "Table"
    PageBlockType.Image -> "Image"
    PageBlockType.File -> "File"
    PageBlockType.NoteLink -> "Note link"
    PageBlockType.Raw -> "Raw Markdown"
}

@Composable
private fun pageBlockTextStyle(block: PageBlock, editorFont: FontFamily): TextStyle = when (block.type) {
    PageBlockType.Heading -> TextStyle(color = MaterialTheme.colorScheme.onSurface, fontFamily = editorFont, fontWeight = FontWeight.Black, fontSize = when (block.level) { 1 -> 27.sp; 2 -> 22.sp; else -> 18.sp }, lineHeight = 32.sp)
    PageBlockType.Code, PageBlockType.Table, PageBlockType.Raw -> TextStyle(color = MaterialTheme.colorScheme.onSurface, fontFamily = FontFamily.Monospace, fontSize = 14.sp, lineHeight = 21.sp)
    PageBlockType.Quote -> TextStyle(color = MaterialTheme.colorScheme.onSurfaceVariant, fontFamily = editorFont, fontSize = 16.sp, lineHeight = 25.sp)
    else -> TextStyle(color = MaterialTheme.colorScheme.onSurface, fontFamily = editorFont, fontSize = 16.sp, lineHeight = 25.sp)
}

@Composable
private fun NoteEmbedsBlock(
    note: Note,
    notes: List<Note>,
    viewModel: NotesViewModel,
    onPickEmbed: () -> Unit,
    onAttachNote: (Note) -> Unit,
    maxLineWidth: androidx.compose.ui.unit.Dp,
) {
    var link by remember(note.id) { mutableStateOf("") }
    val attachableNotes = remember(notes, note.id) {
        notes.filter { it.id != note.id && !it.archived }.take(8)
    }
    Surface(
        modifier = Modifier.fillMaxWidth().widthIn(max = maxLineWidth),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.62f),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Outlined.Link, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                Text("Embeds", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                Spacer(Modifier.weight(1f))
                Text("${note.embeds.size}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (note.embeds.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                    note.embeds.forEach { embed ->
                        EmbedCard(embed)
                    }
                }
            }
            if (attachableNotes.isNotEmpty()) {
                Text("Attach note", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.SemiBold)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(attachableNotes, key = { it.id }) { linkedNote ->
                        Surface(
                            modifier = Modifier.clickable { onAttachNote(linkedNote) },
                            shape = RoundedCornerShape(999.dp),
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                        ) {
                            Text(
                                linkedNote.title.ifBlank { "Untitled note" },
                                Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BasicTextField(
                    value = link,
                    onValueChange = { link = it },
                    textStyle = TextStyle(fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    modifier = Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.38f)).padding(horizontal = 10.dp, vertical = 8.dp),
                    singleLine = true,
                )
                Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.primary) {
                    Text(
                        "Add",
                        Modifier.clickable {
                            viewModel.addLinkEmbedToSelectedNote(link)
                            link = ""
                        }.padding(horizontal = 12.dp, vertical = 8.dp),
                        color = MaterialTheme.colorScheme.onPrimary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)) {
                    Row(
                        Modifier.clickable(onClick = onPickEmbed).padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Icon(Icons.Outlined.AttachFile, null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                        Text("Embed file/media", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
                Text("Images, video, audio, PDFs, and files stay linked as syncable metadata.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp, modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun EmbedCard(embed: NoteEmbedItem) {
    val context = LocalContext.current
    val icon = when (embed.type) {
        NoteEmbedType.Link -> Icons.Outlined.Link
        NoteEmbedType.Image -> Icons.Outlined.Image
        NoteEmbedType.Video -> Icons.Outlined.PlayArrow
        NoteEmbedType.Audio -> Icons.Outlined.Mic
        NoteEmbedType.File -> Icons.Outlined.AttachFile
        NoteEmbedType.Canvas -> Icons.Outlined.Code
        NoteEmbedType.Task -> Icons.Outlined.Book
    }
    val accent = when (embed.type) {
        NoteEmbedType.Image -> Color(0xFF4AADFF)
        NoteEmbedType.Video -> Color(0xFFF276E2)
        NoteEmbedType.Audio -> Color(0xFF56CC98)
        NoteEmbedType.File -> Color(0xFFFFCF52)
        else -> MaterialTheme.colorScheme.primary
    }
    Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)) {
        Column(Modifier.width(172.dp).padding(10.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                Box(Modifier.size(28.dp).clip(RoundedCornerShape(8.dp)).background(accent.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
                    Icon(icon, null, tint = accent, modifier = Modifier.size(16.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text(embed.type.name, color = accent, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    Text(embed.title, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                }
            }
            if (embed.type == NoteEmbedType.Image) {
                AsyncImage(
                    model = embed.target,
                    contentDescription = null,
                    modifier = Modifier.fillMaxWidth().height(78.dp).clip(RoundedCornerShape(10.dp)),
                    contentScale = ContentScale.Crop,
                )
            } else {
                Text(
                    embed.preview.ifBlank { embed.target },
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
            }
            Text(embed.target, fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f), maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                EmbedActionButton("Open", Icons.AutoMirrored.Outlined.OpenInNew, Modifier.weight(1f)) {
                    context.openEmbedTarget(embed)
                }
                EmbedActionButton("Share", Icons.Outlined.Share, Modifier.weight(1f)) {
                    context.shareEmbedTarget(embed)
                }
            }
        }
    }
}

@Composable
private fun EmbedActionButton(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f),
    ) {
        Row(
            Modifier.clickable(onClick = onClick).padding(horizontal = 7.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Icon(icon, null, modifier = Modifier.size(13.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(4.dp))
            Text(label, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
        }
    }
}

@Composable
private fun AttachmentInlineCard(attachment: Attachment) {
    val context = LocalContext.current
    Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)) {
        Column(Modifier.width(172.dp).padding(10.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                Box(Modifier.size(28.dp).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
                    Icon(
                        if (attachment.mimeType.startsWith("image/")) Icons.Outlined.Image else Icons.Outlined.AttachFile,
                        null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp),
                    )
                }
                Column(Modifier.weight(1f)) {
                    Text("Attachment", color = MaterialTheme.colorScheme.primary, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    Text(attachment.displayName, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                }
            }
            if (attachment.mimeType.startsWith("image/") || imageLikeUri(attachment.uri)) {
                AsyncImage(
                    model = attachment.uri,
                    contentDescription = attachment.displayName,
                    modifier = Modifier.fillMaxWidth().height(78.dp).clip(RoundedCornerShape(10.dp)),
                    contentScale = ContentScale.Crop,
                )
            } else {
                Text(
                    attachment.mimeType,
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
            }
            Text(attachment.uri, fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f), maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                EmbedActionButton("Open", Icons.AutoMirrored.Outlined.OpenInNew, Modifier.weight(1f)) {
                    context.openAttachmentTarget(attachment)
                }
                EmbedActionButton("Share", Icons.Outlined.Share, Modifier.weight(1f)) {
                    context.shareAttachmentTarget(attachment)
                }
            }
        }
    }
}

private fun Context.openEmbedTarget(embed: NoteEmbedItem) {
    val target = embed.target.trim()
    if (target.isBlank()) return
    val uri = embed.toOpenUri()
    val intent = Intent(Intent.ACTION_VIEW).apply {
        if (embed.type == NoteEmbedType.Link) {
            data = uri
        } else {
            setDataAndType(uri, embed.mimeType())
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
    runCatching {
        startActivity(Intent.createChooser(intent, "Open ${embed.title.ifBlank { embed.type.name }}"))
    }
}

private fun Context.openAttachmentTarget(attachment: Attachment) {
    val uri = Uri.parse(attachment.uri)
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, attachment.mimeType.ifBlank { "application/octet-stream" })
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    runCatching {
        startActivity(Intent.createChooser(intent, "Open ${attachment.displayName.ifBlank { "attachment" }}"))
    }
}

private fun Context.shareAttachmentTarget(attachment: Attachment) {
    val uri = Uri.parse(attachment.uri)
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = attachment.mimeType.ifBlank { "application/octet-stream" }
        putExtra(Intent.EXTRA_TITLE, attachment.displayName.ifBlank { "attachment" })
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    runCatching {
        startActivity(Intent.createChooser(intent, "Share ${attachment.displayName.ifBlank { "attachment" }}"))
    }
}

private fun Context.shareEmbedTarget(embed: NoteEmbedItem) {
    val target = embed.target.trim()
    if (target.isBlank()) return
    val uri = embed.toOpenUri()
    val intent = Intent(Intent.ACTION_SEND).apply {
        putExtra(Intent.EXTRA_TITLE, embed.title.ifBlank { embed.type.name })
        if (embed.type == NoteEmbedType.Link) {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, uri.toString())
        } else {
            type = embed.mimeType()
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
    runCatching {
        startActivity(Intent.createChooser(intent, "Share ${embed.title.ifBlank { embed.type.name }}"))
    }
}

private fun NoteEmbedItem.toOpenUri(): Uri {
    val target = target.trim()
    return if (type == NoteEmbedType.Link && !target.contains("://")) {
        Uri.parse("https://$target")
    } else {
        Uri.parse(target)
    }
}

private fun NoteEmbedItem.mimeType(): String = when (type) {
    NoteEmbedType.Image -> "image/*"
    NoteEmbedType.Video -> "video/*"
    NoteEmbedType.Audio -> "audio/*"
    NoteEmbedType.File -> "application/octet-stream"
    NoteEmbedType.Link -> "text/plain"
    NoteEmbedType.Canvas,
    NoteEmbedType.Task -> "text/plain"
}

@Composable
private fun EditorTopBar(
    note: Note,
    title: String,
    body: String,
    state: NotesUiState,
    viewModel: NotesViewModel,
    onPickCover: () -> Unit,
    maxLineWidth: androidx.compose.ui.unit.Dp,
    canUndo: Boolean,
    canRedo: Boolean,
    readOnlySource: Boolean,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onToggleReadOnly: () -> Unit,
    onOpenSidebar: () -> Unit,
    onSave: () -> Unit,
    onTitleChange: (String) -> Unit,
) {
    val editorFont = when (state.settings.editorFontFamily) {
        EditorFontFamily.Sans -> FontFamily.SansSerif
        EditorFontFamily.Serif -> FontFamily.Serif
    }

    Column(Modifier.fillMaxWidth().widthIn(max = maxLineWidth)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { onSave(); viewModel.go(Destination.NotesHome) }) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back to notes")
            }
            IconButton(onClick = onOpenSidebar) {
                Icon(Icons.Outlined.Menu, "Open sidebar")
            }
            Column(Modifier.weight(1f)) {
                BasicTextField(
                    value = title,
                    onValueChange = {
                        onTitleChange(it)
                    },
                    textStyle = TextStyle(
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontFamily = editorFont,
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                Text(
                    "Saved · ${note.bodyMarkdown.split(Regex("\\s+")).filter { it.isNotBlank() }.size} words",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                )
            }
            IconButton(onClick = { viewModel.toggleStar(note) }) {
                Icon(Icons.Outlined.Star, null, tint = if (note.starred) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = { viewModel.togglePin(note) }) {
                Icon(Icons.Outlined.PushPin, null, tint = if (note.pinned) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = { viewModel.toggleLock(note) }) {
                Icon(if (note.locked) Icons.Outlined.Lock else Icons.Outlined.LockOpen, null)
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onUndo, enabled = canUndo) {
                Icon(Icons.AutoMirrored.Outlined.Undo, null, modifier = Modifier.size(19.dp))
            }
            IconButton(onClick = onRedo, enabled = canRedo) {
                Icon(Icons.AutoMirrored.Outlined.Redo, null, modifier = Modifier.size(19.dp))
            }
            FilterChip(
                state.editorMode == EditorMode.Page,
                { viewModel.setEditorMode(EditorMode.Page) },
                label = { Text("Page") },
                leadingIcon = { Icon(Icons.Outlined.Book, null, modifier = Modifier.size(18.dp)) },
            )
            FilterChip(
                state.editorMode == EditorMode.Edit,
                { viewModel.setEditorMode(EditorMode.Edit) },
                label = { Text("Source") },
                leadingIcon = { Icon(Icons.Outlined.Code, null, modifier = Modifier.size(18.dp)) },
            )
            FilterChip(
                state.editorMode == EditorMode.Preview,
                { viewModel.setEditorMode(EditorMode.Preview) },
                label = { Text("Preview") },
                leadingIcon = { Icon(Icons.Outlined.PlayArrow, null, modifier = Modifier.size(18.dp)) },
            )
            if (state.editorMode == EditorMode.Edit) {
                FilterChip(
                    selected = readOnlySource,
                    onClick = onToggleReadOnly,
                    label = { Text(if (readOnlySource) "Read" else "Write") },
                    leadingIcon = { Icon(Icons.Outlined.Code, null, modifier = Modifier.size(18.dp)) },
                )
            }
            if (state.editorMode == EditorMode.Preview) {
                FilterChip(
                    selected = !note.coverUri.isNullOrBlank(),
                    onClick = onPickCover,
                    label = { Text(if (note.coverUri.isNullOrBlank()) "Add cover" else "Cover") },
                    leadingIcon = { Icon(Icons.Outlined.Image, null, modifier = Modifier.size(18.dp)) },
                )
            }
            IconButton(onClick = onSave) { Icon(Icons.Outlined.CheckBox, null, modifier = Modifier.size(20.dp)) }
            IconButton(onClick = { viewModel.archive(note) }) { Icon(Icons.Outlined.Archive, null, modifier = Modifier.size(20.dp)) }
            IconButton(onClick = { viewModel.delete(note) }) { Icon(Icons.Outlined.Delete, null, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.error.copy(alpha = 0.7f)) }
            Spacer(Modifier.width(18.dp))
        }
    }
}

@Composable
private fun NoteCoverBlock(
    note: Note,
    viewModel: NotesViewModel,
    onPickCover: () -> Unit,
    maxLineWidth: androidx.compose.ui.unit.Dp,
) {
    if (note.coverUri.isNullOrBlank()) {
        Surface(
            modifier = Modifier.fillMaxWidth().widthIn(max = maxLineWidth).clickable(onClick = onPickCover),
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.38f),
        ) {
            Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Outlined.Image, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                Text("Add cover image or GIF", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        return
    }
    Surface(
        modifier = Modifier.fillMaxWidth().widthIn(max = maxLineWidth),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp,
    ) {
        Box {
            AsyncImage(
                model = note.coverUri,
                contentDescription = null,
                modifier = Modifier.fillMaxWidth().height(150.dp),
                contentScale = ContentScale.Crop,
            )
            Row(
                Modifier.align(Alignment.BottomEnd).padding(8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Surface(shape = RoundedCornerShape(999.dp), color = MaterialTheme.colorScheme.surface.copy(alpha = 0.88f)) {
                    Text("Change", Modifier.clickable(onClick = onPickCover).padding(horizontal = 10.dp, vertical = 5.dp), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }
                Surface(shape = RoundedCornerShape(999.dp), color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.92f)) {
                    Text("Remove", Modifier.clickable(onClick = viewModel::clearSelectedNoteCover).padding(horizontal = 10.dp, vertical = 5.dp), fontSize = 12.sp, color = MaterialTheme.colorScheme.onErrorContainer, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

private enum class EditorAction { H1, H2, Bold, Italic, Bullet, Numbered, Checklist, Quote, Code, Link, Table, Divider }

@Composable
private fun EditorBottomToolbar(
    maxLineWidth: androidx.compose.ui.unit.Dp,
    imeVisible: Boolean,
    onCollapse: () -> Unit,
    onAttach: () -> Unit,
    onEmbed: () -> Unit,
    onImage: () -> Unit,
    onFormat: (EditorAction) -> Unit,
) {
    val scroll = rememberScrollState()
    val toolbarModifier = if (imeVisible) {
        Modifier
            .fillMaxWidth()
            .widthIn(max = maxLineWidth)
            .padding(bottom = 8.dp)
    } else {
        Modifier
            .fillMaxWidth()
            .widthIn(max = maxLineWidth)
            .navigationBarsPadding()
    }
    Surface(
        modifier = toolbarModifier.horizontalScroll(scroll),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.84f),
    ) {
        Row(
            Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ToolbarButton(Icons.Outlined.DragIndicator, "Float") { onCollapse() }
            ToolbarDivider()
            ToolbarButton(Icons.Outlined.Title, "H1") { onFormat(EditorAction.H1) }
            ToolbarButton(Icons.Outlined.FormatSize, "H2") { onFormat(EditorAction.H2) }
            ToolbarDivider()
            ToolbarButton(Icons.Outlined.FormatBold, "Bold") { onFormat(EditorAction.Bold) }
            ToolbarButton(Icons.Outlined.FormatItalic, "Italic") { onFormat(EditorAction.Italic) }
            ToolbarDivider()
            ToolbarButton(Icons.AutoMirrored.Outlined.FormatListBulleted, "List") { onFormat(EditorAction.Bullet) }
            ToolbarButton(Icons.Outlined.FormatListNumbered, "Num") { onFormat(EditorAction.Numbered) }
            ToolbarButton(Icons.Outlined.CheckBox, "Task") { onFormat(EditorAction.Checklist) }
            ToolbarDivider()
            ToolbarButton(Icons.Outlined.FormatQuote, "Quote") { onFormat(EditorAction.Quote) }
            ToolbarButton(Icons.Outlined.Code, "Code") { onFormat(EditorAction.Code) }
            ToolbarButton(Icons.Outlined.Link, "Link") { onFormat(EditorAction.Link) }
            ToolbarButton(Icons.Outlined.TableChart, "Table") { onFormat(EditorAction.Table) }
            ToolbarButton(Icons.Outlined.Title, "Rule") { onFormat(EditorAction.Divider) }
            ToolbarDivider()
            ToolbarButton(Icons.Outlined.AttachFile, "Attach") { onAttach() }
            ToolbarButton(Icons.Outlined.PlayArrow, "Embed") { onEmbed() }
            ToolbarButton(Icons.Outlined.Image, "Image") { onImage() }
        }
    }
}

@Composable
private fun FloatingEditorToolbar(
    modifier: Modifier = Modifier,
    onDrag: (androidx.compose.ui.geometry.Offset) -> Unit,
    onExpand: () -> Unit,
    onFormat: (EditorAction) -> Unit,
) {
    Surface(
        modifier = modifier
            .pointerInput(Unit) {
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    onDrag(androidx.compose.ui.geometry.Offset(dragAmount.x, dragAmount.y))
                }
            },
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
        shadowElevation = 8.dp,
    ) {
        Row(
            Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            ToolbarButton(Icons.Outlined.DragIndicator, "Move") {}
            ToolbarButton(Icons.Outlined.FormatBold, "Bold") { onFormat(EditorAction.Bold) }
            ToolbarButton(Icons.Outlined.CheckBox, "Task") { onFormat(EditorAction.Checklist) }
            ToolbarButton(Icons.Outlined.Link, "Link") { onFormat(EditorAction.Link) }
            ToolbarButton(Icons.Outlined.Code, "Code") { onFormat(EditorAction.Code) }
            ToolbarButton(Icons.Outlined.Edit, "Full") { onExpand() }
        }
    }
}

@Composable
private fun ToolbarDivider() {
    Box(Modifier.width(1.dp).height(20.dp).background(MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)))
}

/** Applies a markdown formatting action to the current selection/cursor. */
private fun applyMarkdown(value: TextFieldValue, action: EditorAction): TextFieldValue = when (action) {
    EditorAction.Bold -> wrap(value, "**")
    EditorAction.Italic -> wrap(value, "*")
    EditorAction.Code -> wrap(value, "`")
    EditorAction.Link -> link(value)
    EditorAction.H1 -> linePrefix(value, "# ")
    EditorAction.H2 -> linePrefix(value, "## ")
    EditorAction.Bullet -> linePrefix(value, "- ")
    EditorAction.Numbered -> linePrefix(value, "1. ")
    EditorAction.Checklist -> linePrefix(value, "- [ ] ")
    EditorAction.Quote -> linePrefix(value, "> ")
    EditorAction.Table -> insertBlock(value, "\n| Column | Detail |\n| --- | --- |\n|  |  |\n")
    EditorAction.Divider -> insertBlock(value, "\n---\n")
}

private fun wrap(v: TextFieldValue, token: String): TextFieldValue {
    val start = minOf(v.selection.start, v.selection.end)
    val end = maxOf(v.selection.start, v.selection.end)
    val selected = v.text.substring(start, end)
    val newText = v.text.substring(0, start) + token + selected + token + v.text.substring(end)
    val cursor = if (selected.isEmpty()) start + token.length else end + 2 * token.length
    return TextFieldValue(newText, TextRange(cursor))
}

private fun link(v: TextFieldValue): TextFieldValue {
    val start = minOf(v.selection.start, v.selection.end)
    val end = maxOf(v.selection.start, v.selection.end)
    val selected = v.text.substring(start, end).ifEmpty { "text" }
    val insert = "[$selected](url)"
    val newText = v.text.substring(0, start) + insert + v.text.substring(end)
    // Place cursor inside the (url) placeholder.
    val urlStart = start + insert.length - 4
    return TextFieldValue(newText, TextRange(urlStart, urlStart + 3))
}

private fun linePrefix(v: TextFieldValue, prefix: String): TextFieldValue {
    val cursor = v.selection.start
    val lineStart = if (cursor <= 0) 0 else v.text.lastIndexOf('\n', cursor - 1).let { if (it < 0) 0 else it + 1 }
    val newText = v.text.substring(0, lineStart) + prefix + v.text.substring(lineStart)
    return TextFieldValue(newText, TextRange(cursor + prefix.length))
}

private fun insertBlock(v: TextFieldValue, block: String): TextFieldValue {
    val start = minOf(v.selection.start, v.selection.end)
    val end = maxOf(v.selection.start, v.selection.end)
    val newText = v.text.substring(0, start) + block + v.text.substring(end)
    val cursor = start + block.length
    return TextFieldValue(newText, TextRange(cursor))
}

@Composable
private fun ToolbarButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    active: Boolean = false,
    onClick: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (active) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 5.dp),
    ) {
        Icon(icon, null, tint = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun MarkdownPreview(note: Note, modifier: Modifier, onPickCover: () -> Unit) {
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val accentHex = String.format("#%06X", 0xFFFFFF and MaterialTheme.colorScheme.primary.toArgb())
    LazyColumn(
        modifier = modifier,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 18.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item(key = "preview-title-${note.id}") {
            Text(note.title, fontWeight = FontWeight.Black, fontSize = 30.sp, color = MaterialTheme.colorScheme.onSurface)
        }
        if (!note.coverUri.isNullOrBlank()) {
            item(key = "preview-cover-${note.id}") {
                AsyncImage(
                    model = note.coverUri,
                    contentDescription = "Note cover",
                    modifier = Modifier.fillMaxWidth().height(220.dp).clip(RoundedCornerShape(10.dp)).clickable(onClick = onPickCover),
                    contentScale = ContentScale.Crop,
                )
            }
        } else {
            item(key = "preview-cover-action-${note.id}") {
                Surface(
                    modifier = Modifier.fillMaxWidth().clickable(onClick = onPickCover),
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.08f),
                ) {
                    Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Outlined.Image, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                        Text("Add cover", fontSize = 13.sp, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
        item(key = "preview-media-${note.id}") { PreviewMediaStrip(note) }
        item(key = "preview-body-${note.id}-${note.bodyMarkdown.hashCode()}") {
            MarkdownBodyPreview(
                markdown = note.bodyMarkdown,
                dark = dark,
                accentHex = accentHex,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun PreviewMediaStrip(note: Note) {
    val imageAttachments = note.attachments.filter { it.mimeType.startsWith("image/") || imageLikeUri(it.uri) }
    val imageEmbeds = note.embeds.filter { it.type == NoteEmbedType.Image || imageLikeUri(it.target) }
    val files = note.attachments.filterNot { it.mimeType.startsWith("image/") || imageLikeUri(it.uri) }
    val nonImageEmbeds = note.embeds.filterNot { it.type == NoteEmbedType.Image || imageLikeUri(it.target) }
    if (imageAttachments.isEmpty() && imageEmbeds.isEmpty() && files.isEmpty() && nonImageEmbeds.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(bottom = 14.dp)) {
        imageAttachments.take(4).forEach { attachment ->
            PreviewImageCard(attachment.uri, attachment.displayName)
        }
        imageEmbeds.take(4).forEach { embed ->
            PreviewImageCard(embed.target, embed.title)
        }
        if (files.isNotEmpty() || nonImageEmbeds.isNotEmpty()) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(files, key = { "preview-attachment-${it.id}" }) { attachment ->
                    AttachmentInlineCard(attachment)
                }
                items(nonImageEmbeds, key = { "preview-embed-${it.id}" }) { embed ->
                    EmbedCard(embed)
                }
            }
        }
    }
}

@Composable
private fun PreviewImageCard(uri: String, title: String) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.34f),
    ) {
        Column {
            AsyncImage(
                model = uri,
                contentDescription = title,
                modifier = Modifier.fillMaxWidth().height(220.dp),
                contentScale = ContentScale.Crop,
            )
            Text(
                title.ifBlank { "Image" },
                Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun imageLikeUri(value: String): Boolean {
    val clean = value.substringBefore('?').lowercase()
    return clean.endsWith(".png") || clean.endsWith(".jpg") || clean.endsWith(".jpeg") || clean.endsWith(".webp") || clean.endsWith(".gif")
}
