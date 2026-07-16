package com.norfold.app.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.FormatListBulleted
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Difference
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.DragIndicator
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.UnfoldMore
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.NotificationsNone
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.Tag
import androidx.compose.material.icons.outlined.TrackChanges
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.norfold.app.branding.palette
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInParent
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.norfold.app.domain.Destination
import com.norfold.app.domain.DocOutline
import com.norfold.app.domain.DocSectionAction
import com.norfold.app.domain.Note
import com.norfold.app.ui.DocsUiState
import com.norfold.app.ui.DocsViewModel

@Composable
fun SectionSidebarOverlay(state: DocsUiState, viewModel: DocsViewModel) {
    val panelEnter = if (state.settings.reduceMotion) {
        fadeIn(tween(150))
    } else {
        slideInHorizontally(
            spring(dampingRatio = 0.82f, stiffness = Spring.StiffnessMediumLow),
        ) { -it } + fadeIn(tween(200))
    }
    val panelExit = if (state.settings.reduceMotion) {
        fadeOut(tween(120))
    } else {
        slideOutHorizontally(tween(240, easing = FastOutSlowInEasing)) { -it } + fadeOut(tween(200))
    }
    AnimatedVisibility(
        visible = state.sidebarOpen,
        enter = fadeIn(tween(if (state.settings.reduceMotion) 120 else 250)),
        exit = fadeOut(tween(if (state.settings.reduceMotion) 120 else 250)),
    ) {
        Box(Modifier.fillMaxSize()) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.44f))
                    .clickable(onClick = viewModel::closeSidebar),
            )
            AnimatedVisibility(
                visible = state.sidebarOpen,
                enter = panelEnter,
                exit = panelExit,
                modifier = Modifier.align(Alignment.CenterStart),
            ) {
                Surface(
                    modifier = Modifier
                        .width(300.dp)
                        .fillMaxHeight(),
                    color = MaterialTheme.colorScheme.surface,
                    shadowElevation = 16.dp,
                ) {
                    SidebarContent(state, viewModel, Modifier.padding(horizontal = 16.dp, vertical = 20.dp))
                }
            }
        }
    }
}

@Composable
fun Sidebar(state: DocsUiState, viewModel: DocsViewModel, modifier: Modifier) {
    SidebarContent(state, viewModel, modifier)
}

@Composable
private fun WorkspaceSwitcher(state: DocsUiState, viewModel: DocsViewModel) {
    var expanded by remember { mutableStateOf(false) }
    var creating by remember { mutableStateOf(false) }
    if (creating) {
        CreateWorkspaceVisualDialog(state, viewModel) {
            creating = false
            expanded = false
        }
    }
    val active = state.workspaces.firstOrNull { it.id == state.settings.activeWorkspaceId }
    val activeColor = (active?.palette ?: state.settings.themeProfile).palette().accent

    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)),
    ) {
        // Current workspace / toggle
        Row(
            Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(activeColor),
                contentAlignment = Alignment.Center,
            ) {
                Text((active?.icon ?: "N").take(1), fontWeight = FontWeight.Bold, fontSize = 18.sp, color = Color.White)
            }
            Column(Modifier.weight(1f)) {
                Text(active?.name ?: state.settings.workspaceName.ifBlank { "Workspace" }, fontWeight = FontWeight.Bold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${state.notes.size} docs · ${state.workspaces.size} workspaces", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = { viewModel.go(Destination.Inbox) }, modifier = Modifier.size(30.dp)) {
                Icon(Icons.Outlined.NotificationsNone, "Inbox", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
            }
            Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.UnfoldMore, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
        }

        AnimatedVisibility(expanded) {
            Column(Modifier.padding(horizontal = 8.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
                state.workspaces.forEach { ws ->
                    val selected = ws.id == state.settings.activeWorkspaceId
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { viewModel.switchWorkspace(ws.id) }.padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Box(Modifier.size(26.dp).clip(RoundedCornerShape(8.dp)).background(ws.palette.palette().accent), contentAlignment = Alignment.Center) {
                            Text(ws.icon.take(1), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        }
                        Text(ws.name, Modifier.weight(1f), fontSize = 14.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (selected) Icon(Icons.Outlined.Check, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                        else if (state.workspaces.size > 1) IconButton(onClick = { viewModel.deleteWorkspace(ws.id) }, modifier = Modifier.size(24.dp)) {
                            Icon(Icons.Outlined.Delete, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(15.dp))
                        }
                    }
                }
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { creating = true }.padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Box(Modifier.size(26.dp).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)), contentAlignment = Alignment.Center) {
                        Icon(Icons.Outlined.Add, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                    }
                    Text("New workspace", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}

/** Sticky workspace identity and cross-object search share one visual region in every sidebar. */
@Composable
private fun UnifiedSidebarHeader(
    state: DocsUiState,
    viewModel: DocsViewModel,
    query: String,
    onQueryChange: (String) -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.32f),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            WorkspaceSwitcher(state, viewModel)
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.78f))
                    .padding(horizontal = 10.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.Search, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(8.dp))
                Box(Modifier.weight(1f)) {
                    if (query.isBlank()) {
                        Text("Search workspace", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                    }
                    BasicTextField(
                        value = query,
                        onValueChange = onQueryChange,
                        singleLine = true,
                        textStyle = TextStyle(fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (query.isNotEmpty()) {
                    Icon(
                        Icons.Outlined.Close,
                        "Clear search",
                        Modifier.size(18.dp).clip(CircleShape).clickable { onQueryChange("") },
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Text("Ctrl K", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp)
                }
            }
        }
    }
}

@Composable
private fun SidebarSearchResults(
    state: DocsUiState,
    viewModel: DocsViewModel,
    query: String,
    modifier: Modifier = Modifier,
) {
    val results = buildSearchResults(state, viewModel, query).take(30)
    Column(
        modifier.verticalScroll(rememberScrollState()).padding(top = 6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            "${results.size} result${if (results.size == 1) "" else "s"}",
            Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (results.isEmpty()) {
            Text(
                "Nothing in this workspace matches “${query.trim()}”.",
                Modifier.padding(12.dp),
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            results.forEach { result ->
                SearchResultRow(result, onActivated = viewModel::closeSidebar)
            }
        }
    }
}

@Composable
private fun SidebarContent(state: DocsUiState, viewModel: DocsViewModel, modifier: Modifier) {
    var sidebarQuery by remember { mutableStateOf("") }
    // While a doc is open, the sidebar *becomes* the document's table of contents —
    // the workspace nav is reachable again via the editor's back button.
    val docNote = state.selectedNote
    if (state.destination == Destination.NoteEditor && docNote != null) {
        DocSidebarContent(docNote, state, viewModel, sidebarQuery, { sidebarQuery = it }, modifier)
        return
    }
    var notesExpanded by remember { mutableStateOf(state.destination in setOf(Destination.NotesHome, Destination.NoteEditor)) }
    var notesShowAll by remember { mutableStateOf(false) }
    var tasksExpanded by remember { mutableStateOf(state.destination in setOf(Destination.Tasks, Destination.Calendar)) }
    var notebooksExpanded by remember { mutableStateOf(state.destination == Destination.Notebooks) }
    val publicName = state.settings.syncPublicName.ifBlank { state.settings.syncUserName.ifBlank { "Local owner" } }
    Column(
        modifier.fillMaxHeight(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        UnifiedSidebarHeader(state, viewModel, sidebarQuery, { sidebarQuery = it })

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Surface(
                onClick = viewModel::createNote,
                modifier = Modifier.weight(1f).height(40.dp),
                shape = RoundedCornerShape(9.dp),
                color = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.primary,
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            ) {
                Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                    Icon(Icons.Outlined.Add, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Doc", maxLines = 1, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
            }
            Surface(
                onClick = viewModel::createTaskAndOpen,
                modifier = Modifier.weight(1f).height(40.dp),
                shape = RoundedCornerShape(9.dp),
                color = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.primary,
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            ) {
                Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                    Icon(Icons.Outlined.Add, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Task", maxLines = 1, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }

        if (sidebarQuery.isNotBlank()) {
            SidebarSearchResults(state, viewModel, sidebarQuery, Modifier.weight(1f))
        } else Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Spacer(Modifier.height(4.dp))
            SideNavItem("Dashboard", Icons.Outlined.Home, state.destination == Destination.WorkspaceHub) { viewModel.go(Destination.WorkspaceHub) }
            SideNavItem("Today", Icons.Outlined.CalendarMonth, state.destination == Destination.Tasks && state.settings.taskViewMode == "Calendar" && state.settings.calendarDefaultView == "Day") {
                viewModel.openTaskCalendar("Day")
            }
            SideNavItem("Upcoming", Icons.Outlined.CalendarMonth, state.destination == Destination.Tasks && state.settings.taskViewMode == "Calendar" && state.settings.calendarDefaultView == "Agenda") {
                viewModel.openTaskCalendar("Agenda")
            }
            SideNavItem("Favorites", Icons.Outlined.Star, state.destination == Destination.NotesHome && state.tab == com.norfold.app.domain.HomeTab.Pinned) {
                viewModel.selectTab(com.norfold.app.domain.HomeTab.Pinned)
                viewModel.go(Destination.NotesHome)
            }

            SidebarDropdownHeader(
                label = "Docs",
                icon = Icons.Outlined.Description,
                expanded = notesExpanded,
                selected = state.destination in setOf(Destination.NotesHome, Destination.NoteEditor),
                onToggle = { notesExpanded = !notesExpanded },
                onClick = { viewModel.go(Destination.NotesHome) },
            )
            AnimatedVisibility(notesExpanded) {
                Column(Modifier.padding(start = 22.dp)) {
                    SideNavItem("All docs", Icons.Outlined.Description, state.destination == Destination.NotesHome) { viewModel.go(Destination.NotesHome) }
                    SideNavItem("Tags", Icons.Outlined.Tag, state.destination == Destination.Tags) { viewModel.go(Destination.Tags) }
                    val recents = state.notes.sortedByDescending { it.updatedAt }
                    val shown = if (notesShowAll) recents else recents.take(3)
                    shown.forEach { note ->
                        SideNavItem(note.title.ifBlank { "Untitled" }, Icons.Outlined.Description, state.destination == Destination.NoteEditor && state.selectedNote?.id == note.id) {
                            viewModel.select(note)
                        }
                    }
                    if (recents.size > 3) {
                        SideNavItem(if (notesShowAll) "Show less" else "…", Icons.Outlined.UnfoldMore, false) { notesShowAll = !notesShowAll }
                    }
                }
            }

            SidebarDropdownHeader(
                label = "Tasks",
                icon = Icons.Outlined.Check,
                expanded = tasksExpanded,
                selected = state.destination == Destination.Tasks,
                onToggle = { tasksExpanded = !tasksExpanded },
                onClick = { viewModel.go(Destination.Tasks) },
            )
            AnimatedVisibility(tasksExpanded) {
                Column(Modifier.padding(start = 22.dp)) {
                    SideNavItem("Board", Icons.Outlined.GridView, state.destination == Destination.Tasks && state.settings.taskViewMode == "Board") {
                        viewModel.patchSettings { it.copy(taskViewMode = "Board") }; viewModel.go(Destination.Tasks)
                    }
                    SideNavItem("Table", Icons.Outlined.Check, state.destination == Destination.Tasks && state.settings.taskViewMode == "Table") {
                        viewModel.patchSettings { it.copy(taskViewMode = "Table") }; viewModel.go(Destination.Tasks)
                    }
                    SideNavItem("Calendar", Icons.Outlined.CalendarMonth, state.destination == Destination.Tasks && state.settings.taskViewMode == "Calendar" && state.settings.calendarDefaultView !in setOf("Day", "Agenda")) { viewModel.openTaskCalendar() }
                    SideNavItem("Agenda", Icons.Outlined.CalendarMonth, state.destination == Destination.Tasks && state.settings.taskViewMode == "Calendar" && state.settings.calendarDefaultView == "Agenda") {
                        viewModel.openTaskCalendar("Agenda")
                    }
                }
            }

            SideNavItem("Files", Icons.Outlined.Folder, state.destination == Destination.Files) { viewModel.go(Destination.Files) }
            SideNavItem("Chat", Icons.Outlined.ChatBubbleOutline, state.destination == Destination.Chat) { viewModel.go(Destination.Chat) }
            SideNavItem("Inbox", Icons.Outlined.Folder, state.destination == Destination.Inbox) { viewModel.go(Destination.Inbox) }

            SidebarDropdownHeader(
                label = "Notebooks",
                icon = Icons.Outlined.Folder,
                expanded = notebooksExpanded,
                selected = state.destination == Destination.Notebooks || state.selectedNotebookId != null,
                onToggle = { notebooksExpanded = !notebooksExpanded },
                onClick = { viewModel.go(Destination.Notebooks) },
            )
            AnimatedVisibility(notebooksExpanded) {
                Column(Modifier.padding(start = 22.dp)) {
                    SideNavItem("Manage notebooks", Icons.Outlined.Folder, state.destination == Destination.Notebooks) { viewModel.go(Destination.Notebooks) }
                    state.notebooks.forEach { notebook ->
                        SideNavItem(notebook.name, Icons.Outlined.Description, state.selectedNotebookId == notebook.id) {
                            viewModel.filterByNotebook(notebook.id)
                        }
                    }
                }
            }

            Spacer(Modifier.height(4.dp))
            Text(
                "WORKSPACE",
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.8.sp,
            )
            SideNavItem("Database", Icons.Outlined.GridView, state.destination == Destination.Database) { viewModel.go(Destination.Database) }
            SideNavItem("Graph", Icons.Outlined.Difference, state.destination == Destination.Graph) { viewModel.go(Destination.Graph) }
            SideNavItem("Activity", Icons.Outlined.Star, state.destination == Destination.Activity) { viewModel.go(Destination.Activity) }
            SideNavItem("Templates", Icons.Outlined.Tag, state.destination == Destination.Templates) { viewModel.go(Destination.Templates) }

            Spacer(Modifier.height(6.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
            SideNavItem("Settings", Icons.Outlined.Settings, state.destination == Destination.Settings) { viewModel.go(Destination.Settings) }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { viewModel.go(Destination.Settings) }.padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(Modifier.size(34.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary), contentAlignment = Alignment.Center) {
                Text(publicName.take(1).uppercase(), color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.Bold)
            }
            Column(Modifier.weight(1f)) {
                Text(publicName, fontWeight = FontWeight.Bold, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("@${state.settings.syncUserName.ifBlank { "owner" }}", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp, maxLines = 1)
            }
            Icon(Icons.Outlined.UnfoldMore, null, Modifier.size(17.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * Sidebar body while a Doc is open: workspace switcher + the doc's table of contents,
 * replacing the workspace nav entirely. The editor's back button restores the nav.
 */
@Composable
private fun DocSidebarContent(
    note: Note,
    state: DocsUiState,
    viewModel: DocsViewModel,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    modifier: Modifier,
) {
    Column(
        modifier.fillMaxHeight(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        UnifiedSidebarHeader(state, viewModel, searchQuery, onSearchQueryChange)
        Text(
            note.title.ifBlank { "Untitled" },
            Modifier.padding(start = 14.dp, end = 14.dp, top = 10.dp, bottom = 2.dp),
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (searchQuery.isNotBlank()) {
            SidebarSearchResults(state, viewModel, searchQuery, Modifier.weight(1f))
        } else Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Spacer(Modifier.height(2.dp))
            DocTableOfContents(note, viewModel)
        }
    }
}

/**
 * The open Doc's table of contents — the sidebar's whole body while inside a Doc (viewing or
 * editing). Tapping a heading fires [DocsViewModel.scrollToBlock]; the editor observes the
 * request and scrolls (or, in free-canvas mode, pans) to the heading's top-level block.
 *
 * Interactions: a caret collapses/expands a heading's run of deeper headings (UI-only state);
 * long-pressing a section heading (top-level, unlocked note) opens a move/duplicate/delete menu;
 * the trailing drag handle reorders whole sections. Mutations go through
 * [DocsViewModel.requestSectionAction] so the editor applies them to its live session — the
 * sidebar only ever sees the persisted note, which trails the session by the autosave debounce.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DocTableOfContents(note: Note, viewModel: DocsViewModel) {
    val headings = remember(note.id, note.document) { DocOutline.extract(note.document) }
    // Heading ids that own deeper headings right after them — only these get a caret.
    val hasChildren = remember(headings) {
        headings.mapIndexedNotNull { i, h ->
            h.blockId.takeIf { i + 1 < headings.size && headings[i + 1].level > h.level }
        }.toSet()
    }
    var collapsed by remember(note.id) { mutableStateOf(setOf<String>()) }
    var query by remember(note.id) { mutableStateOf("") }
    val filtering = query.isNotBlank()
    var menuFor by remember(note.id) { mutableStateOf<String?>(null) }
    // Section drag state: row bounds in the ToC column's space + the dragged row's finger offset.
    val rowBounds = remember(note.id) { mutableStateMapOf<String, Rect>() }
    var draggedId by remember(note.id) { mutableStateOf<String?>(null) }
    var dragOffsetY by remember(note.id) { mutableFloatStateOf(0f) }

    // While filtering: matches plus their ancestors (so context survives), collapse ignored.
    // Otherwise: walk the flat list skipping runs hidden under a collapsed heading.
    val visibleHeadings = remember(headings, query, collapsed) {
        if (query.isNotBlank()) {
            val q = query.trim()
            val keep = BooleanArray(headings.size)
            val ancestors = ArrayDeque<Int>()
            headings.forEachIndexed { i, h ->
                while (ancestors.isNotEmpty() && headings[ancestors.last()].level >= h.level) ancestors.removeLast()
                if (h.label.contains(q, ignoreCase = true)) {
                    keep[i] = true
                    ancestors.forEach { keep[it] = true }
                }
                ancestors.addLast(i)
            }
            headings.filterIndexed { i, _ -> keep[i] }
        } else {
            buildList {
                var skipDeeper: Int? = null
                for (h in headings) {
                    if (skipDeeper != null && h.level > skipDeeper) continue
                    skipDeeper = null
                    add(h)
                    if (h.blockId in collapsed) skipDeeper = h.level
                }
            }
        }
    }

    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
            .padding(vertical = 6.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.AutoMirrored.Outlined.FormatListBulleted, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(8.dp))
            Text(
                "On this page",
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (headings.size > 4) {
            Row(
                Modifier
                    .padding(horizontal = 10.dp, vertical = 4.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.6f))
                    .padding(horizontal = 10.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.Search, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(6.dp))
                Box(Modifier.weight(1f)) {
                    if (query.isEmpty()) {
                        Text("Filter headings", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    BasicTextField(
                        value = query,
                        onValueChange = { query = it },
                        singleLine = true,
                        textStyle = TextStyle(fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (query.isNotEmpty()) {
                    Icon(
                        Icons.Outlined.Close,
                        "Clear filter",
                        Modifier.size(16.dp).clip(CircleShape).clickable { query = "" },
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        if (headings.isEmpty()) {
            Text(
                "No headings yet",
                Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else if (visibleHeadings.isEmpty()) {
            Text(
                "No matching headings",
                Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            visibleHeadings.forEach { heading ->
                val id = heading.blockId
                // Only top-level headings own a contiguous section (nested ones live inside a
                // quote/callout/container and share their ancestor's range) — only they get
                // the long-press actions and the reorder handle.
                val isSection = id == heading.topLevelId
                val isDragged = draggedId == id
                Box(
                    Modifier
                        .fillMaxWidth()
                        .onGloballyPositioned { rowBounds[id] = it.boundsInParent() }
                        .zIndex(if (isDragged) 1f else 0f)
                        .graphicsLayer { translationY = if (isDragged) dragOffsetY else 0f },
                ) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (isDragged) MaterialTheme.colorScheme.surface else Color.Transparent)
                            .combinedClickable(
                                onClick = {
                                    viewModel.scrollToBlock(id)
                                    viewModel.closeSidebar()
                                },
                                onLongClick = if (isSection && !note.locked) ({ menuFor = id }) else null,
                            )
                            .padding(start = (6 + (heading.level.coerceAtMost(4) - 1) * 12).dp, end = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (id in hasChildren) {
                            Icon(
                                if (id in collapsed) Icons.Outlined.ChevronRight else Icons.Outlined.ExpandMore,
                                if (id in collapsed) "Expand" else "Collapse",
                                Modifier
                                    .size(22.dp)
                                    .clip(CircleShape)
                                    .clickable(enabled = !filtering) {
                                        collapsed = if (id in collapsed) collapsed - id else collapsed + id
                                    }
                                    .padding(3.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            Spacer(Modifier.width(22.dp))
                        }
                        Text(
                            heading.label,
                            Modifier.weight(1f).padding(horizontal = 4.dp, vertical = 7.dp),
                            fontSize = 13.sp,
                            fontWeight = if (heading.level <= 1) FontWeight.SemiBold else FontWeight.Normal,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (isSection && !note.locked && !filtering) {
                            Icon(
                                Icons.Outlined.DragIndicator,
                                "Reorder section",
                                Modifier
                                    .size(20.dp)
                                    .pointerInput(id) {
                                        detectDragGestures(
                                            onDragStart = {
                                                draggedId = id
                                                dragOffsetY = 0f
                                            },
                                            onDrag = { change, amount ->
                                                change.consume()
                                                dragOffsetY += amount.y
                                            },
                                            onDragCancel = {
                                                draggedId = null
                                                dragOffsetY = 0f
                                            },
                                            onDragEnd = {
                                                val bounds = rowBounds[id]
                                                if (bounds != null) {
                                                    val center = bounds.center.y + dragOffsetY
                                                    // Drop before the first visible section row whose center
                                                    // sits below the dragged row's center; none = move to end.
                                                    // The editor resolves ids against its live session, so a
                                                    // stale sidebar document can't corrupt indices.
                                                    val target = visibleHeadings.firstOrNull { h ->
                                                        h.blockId != id && h.blockId == h.topLevelId &&
                                                            (rowBounds[h.blockId]?.center?.y ?: Float.NEGATIVE_INFINITY) > center
                                                    }
                                                    viewModel.requestSectionAction(id, DocSectionAction.MoveBefore(target?.blockId))
                                                }
                                                draggedId = null
                                                dragOffsetY = 0f
                                            },
                                        )
                                    },
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (isDragged) 1f else 0.55f),
                            )
                        }
                    }
                    DropdownMenu(expanded = menuFor == id, onDismissRequest = { menuFor = null }) {
                        DropdownMenuItem(text = { Text("Move up") }, onClick = {
                            menuFor = null
                            viewModel.requestSectionAction(id, DocSectionAction.MoveUp)
                        })
                        DropdownMenuItem(text = { Text("Move down") }, onClick = {
                            menuFor = null
                            viewModel.requestSectionAction(id, DocSectionAction.MoveDown)
                        })
                        DropdownMenuItem(text = { Text("Duplicate") }, onClick = {
                            menuFor = null
                            viewModel.requestSectionAction(id, DocSectionAction.Duplicate)
                        })
                        DropdownMenuItem(text = { Text("Delete") }, onClick = {
                            menuFor = null
                            viewModel.requestSectionAction(id, DocSectionAction.Delete)
                        })
                    }
                }
            }
        }
    }
}

/**
 * Top-level nav row with an expandable child list: tapping the row navigates,
 * tapping the trailing chevron only toggles the dropdown.
 */
@Composable
private fun SidebarDropdownHeader(
    label: String,
    icon: ImageVector,
    expanded: Boolean,
    selected: Boolean,
    onToggle: () -> Unit,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(start = 14.dp, end = 4.dp, top = 3.dp, bottom = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, Modifier.size(22.dp), tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(12.dp))
        Text(
            label,
            Modifier.weight(1f),
            fontSize = 14.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
        )
        IconButton(onClick = onToggle, modifier = Modifier.size(34.dp)) {
            Icon(
                if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.UnfoldMore,
                if (expanded) "Collapse $label" else "Expand $label",
                Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SideNavItem(label: String, icon: ImageVector, selected: Boolean, onClick: () -> Unit) {
    val bgAlpha by animateFloatAsState(
        targetValue = if (selected) 1f else 0f,
        animationSpec = tween(200, easing = FastOutSlowInEasing),
        label = "bg",
    )
    val indicatorWidth by animateDpAsState(
        targetValue = if (selected) 4.dp else 0.dp,
        animationSpec = tween(250, easing = FastOutSlowInEasing),
        label = "indicator",
    )

    Box(Modifier.fillMaxWidth()) {
        // Animated selection pill background
        if (bgAlpha > 0.01f) {
            Box(
                Modifier
                    .matchParentSize()
                    .padding(vertical = 2.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f * bgAlpha)),
            )
        }
        // Left indicator bar
        if (indicatorWidth > 0.dp) {
            Box(
                Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 4.dp)
                    .width(indicatorWidth)
                    .height(20.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(MaterialTheme.colorScheme.primary),
            )
        }
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .clickable(onClick = onClick)
                .padding(horizontal = 14.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, null, tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(12.dp))
            Text(
                label,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                fontSize = 14.sp,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}
