package com.norfold.app.ui.screens

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.Card
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.norfold.app.ui.components.NorfoldContentDialog
import coil.compose.AsyncImage
import com.norfold.app.domain.AppSettings
import com.norfold.app.domain.WorkspaceIconKind
import com.norfold.app.domain.HomeTab
import com.norfold.app.domain.Note
import com.norfold.app.domain.NoteGestureAction
import com.norfold.app.domain.Tag
import com.norfold.app.domain.Destination
import com.norfold.app.ui.DocsUiState
import com.norfold.app.ui.DocsViewModel
import com.norfold.app.ui.components.EmptyNotes
import com.norfold.app.ui.components.GlobalSearchBar
import com.norfold.app.ui.components.pressScale

@Composable
fun NotesHome(state: DocsUiState, viewModel: DocsViewModel, modifier: Modifier, showHeader: Boolean = true) {
    var showWorkspaceDialog by remember { mutableStateOf(false) }
    if (showWorkspaceDialog) WorkspaceVisualDialog(state, viewModel) { showWorkspaceDialog = false }
    LazyColumn(
        modifier = modifier.padding(horizontal = 16.dp),
        contentPadding = PaddingValues(
            top = if (showHeader) 16.dp else 0.dp,
            bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 90.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (showHeader) {
            item { WorkspaceHomeHeader(state) { showWorkspaceDialog = true } }
            item { GlobalSearchBar(onOpen = { viewModel.go(Destination.Search) }, onNavigationClick = viewModel::toggleSidebar) }
        }
        item { HomeTabs(state, viewModel) }
        val tabNotes = when (state.tab) {
            HomeTab.AllNotes -> state.notes
            HomeTab.Pinned -> state.notes.filter { it.pinned }
            HomeTab.Tags -> state.notes.filter { it.tags.isNotEmpty() }
        }
        val notebookNotes = state.selectedNotebookId?.let { notebookId ->
            tabNotes.filter { it.notebookId == notebookId }
        } ?: tabNotes
        val visibleNotes = state.selectedTagId?.let { tagId ->
            notebookNotes.filter { note -> note.tags.any { it.id == tagId } }
        } ?: notebookNotes
        val activeNotebook = state.notebooks.firstOrNull { it.id == state.selectedNotebookId }
        val activeTag = state.tags.firstOrNull { it.id == state.selectedTagId }
        if (activeNotebook != null) {
            item { ActiveNotebookFilter(activeNotebook.name, visibleNotes.size) { viewModel.filterByNotebook(null) } }
        }
        if (activeTag != null) {
            item { ActiveTagFilter(activeTag.name, visibleNotes.size) { viewModel.filterByTag(null) } }
        }
        val pinned = visibleNotes.filter { it.pinned }
        if (pinned.isNotEmpty()) {
            item { SectionHeader("Pinned") }
            items(pinned, key = { it.id }) { NoteCard(it, state.selectedNote?.id == it.id, state.settings, state.tags.filter { tag -> tag.scope == "notes" }, viewModel, Modifier.animateItem()) }
        }
        if (state.tab != HomeTab.Pinned) {
            item { SectionHeader("Today") }
            items(visibleNotes.filterNot { it.pinned }, key = { it.id }) { NoteCard(it, state.selectedNote?.id == it.id, state.settings, state.tags.filter { tag -> tag.scope == "notes" }, viewModel, Modifier.animateItem()) }
        }
        if (state.notes.isEmpty()) item { EmptyNotes(viewModel::createNote) }
    }
}

@Composable
private fun WorkspaceHomeHeader(state: DocsUiState, onEdit: () -> Unit) {
    val settings = state.settings
    val headerInteraction = remember { MutableInteractionSource() }
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .pressScale(headerInteraction)
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.10f))
            .clickable(interactionSource = headerInteraction, indication = null, onClick = onEdit),
    ) {
        if (!settings.workspaceBackgroundUri.isNullOrBlank()) {
            AsyncImage(settings.workspaceBackgroundUri, null, Modifier.matchParentSize(), contentScale = ContentScale.Crop)
            Box(Modifier.matchParentSize().background(MaterialTheme.colorScheme.background.copy(alpha = 0.52f)))
        }
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(46.dp).clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surface), contentAlignment = Alignment.Center) {
                if ((settings.workspaceIconKind == WorkspaceIconKind.Image || settings.workspaceIconKind == WorkspaceIconKind.Gif) && !settings.workspaceIconUri.isNullOrBlank()) {
                    AsyncImage(settings.workspaceIconUri, null, Modifier.matchParentSize().clip(RoundedCornerShape(14.dp)), contentScale = ContentScale.Crop)
                } else {
                    Text(settings.workspaceIcon.ifBlank { "N" }.take(2), fontWeight = FontWeight.Black, fontSize = 18.sp)
                }
            }
            // Centered workspace name + counts (icon and edit affordance flank it).
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(settings.workspaceName.ifBlank { "Workspace" }, fontWeight = FontWeight.Black, fontSize = 22.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                Text("${state.notes.size} docs · ${state.tasks.size} tasks · ${state.chatMessages.size} messages", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, textAlign = TextAlign.Center)
            }
            Icon(Icons.Outlined.Edit, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
private fun HomeTabs(state: DocsUiState, viewModel: DocsViewModel) {
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FilterChip(state.tab == HomeTab.AllNotes, { viewModel.selectTab(HomeTab.AllNotes) }, label = { Text("All Docs") })
        FilterChip(state.tab == HomeTab.Pinned, { viewModel.selectTab(HomeTab.Pinned) }, label = { Text("Pinned") })
        FilterChip(state.tab == HomeTab.Tags, { viewModel.selectTab(HomeTab.Tags) }, label = { Text("Tags") })
        Icon(Icons.Outlined.KeyboardArrowDown, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ActiveNotebookFilter(name: String, count: Int, onClear: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.10f))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text("Notebook", color = MaterialTheme.colorScheme.primary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            Text(name, fontWeight = FontWeight.Bold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text("$count docs", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
        Icon(
            Icons.Outlined.Close,
            contentDescription = "Clear notebook filter",
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .size(28.dp)
                .clip(CircleShape)
                .clickable(onClick = onClear)
                .padding(5.dp),
        )
    }
}

@Composable
private fun ActiveTagFilter(name: String, count: Int, onClear: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text("Tag filter", color = MaterialTheme.colorScheme.primary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            Text("#$name", fontWeight = FontWeight.Bold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text("$count docs", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
        Icon(Icons.Outlined.Close, "Clear tag filter", Modifier.size(28.dp).clip(CircleShape).clickable(onClick = onClear).padding(5.dp), tint = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun SectionHeader(title: String) {
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, fontWeight = FontWeight.Bold, fontSize = 17.sp)
        Icon(Icons.Outlined.KeyboardArrowDown, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
fun NoteCard(note: Note, selected: Boolean, settings: AppSettings, availableTags: List<Tag>, viewModel: DocsViewModel, modifier: Modifier = Modifier) {
    val accent = noteAccent(note)
    val cardInteraction = remember { MutableInteractionSource() }
    var showActions by remember(note.id) { mutableStateOf(false) }
    fun runAction(action: NoteGestureAction) {
        when (action) {
            NoteGestureAction.Actions -> showActions = true
            NoteGestureAction.Pin -> viewModel.togglePin(note)
            NoteGestureAction.Star -> viewModel.toggleStar(note)
            NoteGestureAction.Lock -> viewModel.toggleLock(note)
            NoteGestureAction.Archive -> viewModel.archive(note)
            NoteGestureAction.Delete -> viewModel.delete(note)
            NoteGestureAction.None -> Unit
        }
    }
    if (showActions) {
        NoteQuickActionsDialog(note, availableTags, viewModel) { showActions = false }
    }
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            when (value) {
                SwipeToDismissBoxValue.StartToEnd -> runAction(settings.noteSwipeStartAction)
                SwipeToDismissBoxValue.EndToStart -> runAction(settings.noteSwipeEndAction)
                SwipeToDismissBoxValue.Settled -> Unit
            }
            false
        },
    )
    SwipeToDismissBox(
        modifier = modifier,
        state = dismissState,
        enableDismissFromStartToEnd = settings.noteSwipeStartAction != NoteGestureAction.None,
        enableDismissFromEndToStart = settings.noteSwipeEndAction != NoteGestureAction.None,
        backgroundContent = {
            val action = when (dismissState.dismissDirection) {
                SwipeToDismissBoxValue.StartToEnd -> settings.noteSwipeStartAction
                SwipeToDismissBoxValue.EndToStart -> settings.noteSwipeEndAction
                SwipeToDismissBoxValue.Settled -> NoteGestureAction.None
            }
            Box(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(accent.copy(alpha = 0.18f)).padding(16.dp),
                contentAlignment = if (dismissState.dismissDirection == SwipeToDismissBoxValue.EndToStart) Alignment.CenterEnd else Alignment.CenterStart,
            ) {
                Text(action.name, fontWeight = FontWeight.Bold, color = accent)
            }
        },
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .animateContentSize()
                .pressScale(cardInteraction)
                .border(
                    width = if (selected) 1.5.dp else 0.dp,
                    color = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent,
                    shape = RoundedCornerShape(20.dp),
                )
                .combinedClickable(
                    interactionSource = cardInteraction,
                    indication = androidx.compose.material3.ripple(),
                    onClick = { viewModel.select(note) },
                    onLongClick = { runAction(settings.noteLongPressAction) },
                ),
            shape = RoundedCornerShape(14.dp),
        ) {
            Row(Modifier.background(noteCardBrush(note, accent)).height(IntrinsicSize.Min)) {
                // Colour accent spine
                Box(
                    Modifier
                        .width(4.dp)
                        .fillMaxHeight()
                        .background(Brush.verticalGradient(listOf(accent, accent.copy(alpha = 0.35f)))),
                )
                Column(Modifier.weight(1f).padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(note.title, Modifier.weight(1f), fontWeight = FontWeight.Bold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (note.starred) Icon(Icons.Outlined.Star, null, tint = accent, modifier = Modifier.size(18.dp))
                        if (note.locked) Icon(Icons.Outlined.Lock, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                    }
                    Text(note.document.plainText().replace("\n", " ").trim().take(116), color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 12.sp)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        note.tags.take(2).forEach { MiniTag(it.name, accent) }
                        Spacer(Modifier.weight(1f))
                        Text(relativeTime(note.updatedAt), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                    }
                }
                if (!note.coverUri.isNullOrBlank()) {
                    AsyncImage(
                        model = note.coverUri,
                        contentDescription = null,
                        modifier = Modifier.padding(end = 10.dp).align(Alignment.CenterVertically).size(58.dp).clip(RoundedCornerShape(12.dp)),
                        contentScale = ContentScale.Crop,
                    )
                }
            }
        }
    }
}

@Composable
private fun NoteQuickActionsDialog(note: Note, availableTags: List<Tag>, viewModel: DocsViewModel, onDismiss: () -> Unit) {
    var editingTags by remember(note.id) { mutableStateOf(false) }
    var selectedTags by remember(note.id, note.tags) { mutableStateOf(note.tags.mapTo(mutableSetOf()) { it.name }) }
    NorfoldContentDialog(onDismissRequest = onDismiss) {
        Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp)) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(note.title, fontWeight = FontWeight.Black, fontSize = 20.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text("Doc actions", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                if (editingTags) {
                    Text("Choose tags", fontWeight = FontWeight.Bold)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        items(availableTags, key = { it.id }) { tag ->
                            FilterChip(
                                selected = tag.name in selectedTags,
                                onClick = {
                                    selectedTags = selectedTags.toMutableSet().apply {
                                        if (!add(tag.name)) remove(tag.name)
                                    }
                                },
                                label = { Text("#${tag.name}") },
                            )
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                        TextButton(onClick = { editingTags = false }) { Text("Cancel") }
                        Button(onClick = {
                            viewModel.setNoteTags(note, selectedTags.toList())
                            editingTags = false
                        }) { Text("Save tags") }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    Button(
                        onClick = {
                            viewModel.select(note)
                            onDismiss()
                        },
                        modifier = Modifier.weight(1f),
                    ) { Text("Open") }
                    ElevatedButton(
                        onClick = {
                            viewModel.togglePin(note)
                            onDismiss()
                        },
                        modifier = Modifier.weight(1f),
                    ) { Text(if (note.pinned) "Unpin" else "Pin") }
                }
                ElevatedButton(
                    onClick = { editingTags = !editingTags },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(if (note.tags.isEmpty()) "Add tags" else "Edit tags") }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    ElevatedButton(
                        onClick = {
                            viewModel.toggleStar(note)
                            onDismiss()
                        },
                        modifier = Modifier.weight(1f),
                    ) { Text(if (note.starred) "Unstar" else "Star") }
                    ElevatedButton(
                        onClick = {
                            viewModel.toggleLock(note)
                            onDismiss()
                        },
                        modifier = Modifier.weight(1f),
                    ) { Text(if (note.locked) "Unlock" else "Lock") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    ElevatedButton(
                        onClick = {
                            viewModel.archive(note)
                            onDismiss()
                        },
                        modifier = Modifier.weight(1f),
                    ) { Text("Archive") }
                    TextButton(
                        onClick = {
                            viewModel.delete(note)
                            onDismiss()
                        },
                        modifier = Modifier.weight(1f),
                    ) { Text("Delete", color = MaterialTheme.colorScheme.error) }
                }
            }
        }
    }
}

@Composable
private fun MiniTag(label: String, accent: Color) {
    Box(
        Modifier.padding(end = 6.dp).clip(RoundedCornerShape(999.dp))
            .background(accent.copy(alpha = 0.14f))
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Text(label, color = accent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

@Composable
private fun noteAccent(note: Note): Color {
    // Neutralized: notes no longer carry per-tag hues; the single accent drives the app.
    return MaterialTheme.colorScheme.primary
}

@Composable
private fun noteCardBrush(note: Note, accent: Color): Brush {
    val surface = MaterialTheme.colorScheme.surface
    return Brush.linearGradient(listOf(accent.copy(alpha = if (note.pinned) 0.14f else 0.06f), surface))
}

private fun relativeTime(updatedAt: Long): String {
    val minutes = ((System.currentTimeMillis() - updatedAt).coerceAtLeast(0) / 60_000).toInt()
    return when {
        minutes < 1 -> "Just now"
        minutes < 60 -> "${minutes}m ago"
        minutes < 1440 -> "${minutes / 60}h ago"
        else -> "${minutes / 1440}d ago"
    }
}
