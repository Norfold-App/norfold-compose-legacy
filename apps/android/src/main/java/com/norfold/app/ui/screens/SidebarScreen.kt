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
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.CloudSync
import androidx.compose.material.icons.outlined.Difference
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.UnfoldMore
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.Tag
import androidx.compose.material.icons.outlined.TrackChanges
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.norfold.app.branding.palette
import com.norfold.app.branding.NorfoldLogo
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.norfold.app.domain.Destination
import com.norfold.app.ui.NotesUiState
import com.norfold.app.ui.NotesViewModel

@Composable
fun SectionSidebarOverlay(state: NotesUiState, viewModel: NotesViewModel) {
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
fun Sidebar(state: NotesUiState, viewModel: NotesViewModel, modifier: Modifier) {
    SidebarContent(state, viewModel, modifier)
}

@Composable
private fun WorkspaceSwitcher(state: NotesUiState, viewModel: NotesViewModel) {
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
                Text("${state.notes.size} notes · ${state.workspaces.size} workspaces", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
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

@Composable
private fun SidebarContent(state: NotesUiState, viewModel: NotesViewModel, modifier: Modifier) {
    Column(
        modifier
            .fillMaxHeight()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            NorfoldLogo(34.dp)
            Text("Norfold", fontWeight = FontWeight.Bold, fontSize = 20.sp)
        }
        WorkspaceSwitcher(state, viewModel)

        Spacer(Modifier.height(4.dp))

        Button(onClick = { viewModel.go(Destination.CommandPalette) }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) {
            Icon(Icons.Outlined.Add, null)
            Spacer(Modifier.width(8.dp))
            Text("Command / New")
        }

        Spacer(Modifier.height(4.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
        Spacer(Modifier.height(4.dp))

        SideNavItem("Home", Icons.Outlined.Home, state.destination == Destination.WorkspaceHub) { viewModel.go(Destination.WorkspaceHub) }
        SideNavItem("Inbox", Icons.Outlined.Folder, state.destination == Destination.Inbox) { viewModel.go(Destination.Inbox) }
        SideNavItem("Calendar", Icons.Outlined.CalendarMonth, state.destination == Destination.Calendar) { viewModel.go(Destination.Calendar) }
        SideNavItem("Notes", Icons.Outlined.Folder, state.destination == Destination.NotesHome) { viewModel.go(Destination.NotesHome) }
        SideNavItem("Tasks", Icons.Outlined.Check, state.destination == Destination.Tasks) { viewModel.go(Destination.Tasks) }
        SideNavItem("Goals", Icons.Outlined.TrackChanges, state.destination == Destination.Goals) { viewModel.go(Destination.Goals) }
        SideNavItem("Canvas", Icons.Outlined.GridView, state.destination == Destination.Canvas) { viewModel.go(Destination.Canvas) }
        SideNavItem("Files", Icons.Outlined.Folder, state.destination == Destination.Files) { viewModel.go(Destination.Files) }
        SideNavItem("Chat", Icons.Outlined.ChatBubbleOutline, state.destination == Destination.Chat) { viewModel.go(Destination.Chat) }
        SideNavItem("Database", Icons.Outlined.GridView, state.destination == Destination.Database) { viewModel.go(Destination.Database) }
        SideNavItem("Graph", Icons.Outlined.Difference, state.destination == Destination.Graph) { viewModel.go(Destination.Graph) }
        SideNavItem("Activity", Icons.Outlined.Star, state.destination == Destination.Activity) { viewModel.go(Destination.Activity) }
        SideNavItem("Templates", Icons.Outlined.Tag, state.destination == Destination.Templates) { viewModel.go(Destination.Templates) }

        Spacer(Modifier.height(4.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
        Spacer(Modifier.height(4.dp))

        // Animated section content
        AnimatedContent(
            targetState = state.destination,
            transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(150)) },
            label = "section",
        ) { dest ->
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    sectionTitle(dest),
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 12.dp, top = 6.dp, bottom = 2.dp),
                )
                sectionItems(dest).forEach { item ->
                    SideNavItem(item.label, item.icon, false) { viewModel.go(item.destination) }
                }
            }
        }

        Spacer(Modifier.height(4.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
        Spacer(Modifier.height(4.dp))

        Text(
            "Library",
            fontWeight = FontWeight.Bold,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 12.dp, top = 6.dp, bottom = 2.dp),
        )
        SideNavItem("Notebooks", Icons.Outlined.Folder, state.destination == Destination.Notebooks) { viewModel.go(Destination.Notebooks) }
        SideNavItem("Tags", Icons.Outlined.Tag, state.destination == Destination.Tags) { viewModel.go(Destination.Tags) }
        SideNavItem("Search", Icons.Outlined.Search, state.destination == Destination.Search) { viewModel.go(Destination.Search) }

        Spacer(Modifier.height(4.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
        Spacer(Modifier.height(4.dp))

        SideNavItem("Sync", Icons.Outlined.CloudSync, state.destination == Destination.SyncMonitor) { viewModel.go(Destination.SyncMonitor) }
        SideNavItem("Conflicts", Icons.Outlined.Difference, state.destination == Destination.ConflictReview) { viewModel.go(Destination.ConflictReview) }
        SideNavItem("Vault", Icons.Outlined.Lock, state.destination == Destination.Vault) { viewModel.go(Destination.Vault) }
        SideNavItem("Settings", Icons.Outlined.Settings, state.destination == Destination.Settings) { viewModel.go(Destination.Settings) }

        if (state.notebooks.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
            Spacer(Modifier.height(4.dp))
            Text(
                "Notebooks",
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 12.dp, top = 6.dp, bottom = 2.dp),
            )
            state.notebooks.forEach { notebook ->
                val selected = state.selectedNotebookId == notebook.id
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f) else Color.Transparent)
                        .clickable { viewModel.filterByNotebook(notebook.id) }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Outlined.Folder, null, tint = Color(notebook.color), modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(
                        notebook.name,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        fontSize = 14.sp,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                    )
                    if (selected) {
                        Icon(Icons.Outlined.Check, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                    }
                }
            }
        }
    }
}

private data class SidebarEntry(val label: String, val icon: ImageVector, val destination: Destination)

private fun sectionTitle(destination: Destination): String = when (destination) {
    Destination.WorkspaceHub, Destination.Inbox -> "Workspace"
    Destination.Calendar -> "Calendar"
    Destination.Goals -> "Goals"
    Destination.Files -> "Files"
    Destination.Database -> "Database"
    Destination.Graph, Destination.ObjectDetail -> "Relationships"
    Destination.Activity -> "Activity"
    Destination.Templates -> "Templates"
    Destination.CommandPalette -> "Command"
    Destination.Tasks -> "Task views"
    Destination.Canvas -> "Canvas tools"
    Destination.Chat -> "Chat"
    Destination.Settings, Destination.SyncMonitor, Destination.ConflictReview -> "System"
    else -> "Note views"
}

private fun sectionItems(destination: Destination): List<SidebarEntry> = when (destination) {
    Destination.WorkspaceHub, Destination.Inbox -> listOf(
        SidebarEntry("Dashboard", Icons.Outlined.Home, Destination.WorkspaceHub),
        SidebarEntry("Inbox", Icons.Outlined.Folder, Destination.Inbox),
        SidebarEntry("Activity", Icons.Outlined.Star, Destination.Activity),
    )
    Destination.Tasks -> listOf(
        SidebarEntry("Workspace tasks", Icons.Outlined.Check, Destination.Tasks),
        SidebarEntry("Database view", Icons.Outlined.GridView, Destination.Database),
        SidebarEntry("Sync monitor", Icons.Outlined.CloudSync, Destination.SyncMonitor),
    )
    Destination.Canvas -> listOf(
        SidebarEntry("Canvas board", Icons.Outlined.GridView, Destination.Canvas),
        SidebarEntry("Linked notes", Icons.Outlined.Folder, Destination.NotesHome),
    )
    Destination.Chat -> listOf(
        SidebarEntry("Workspace chat", Icons.Outlined.ChatBubbleOutline, Destination.Chat),
        SidebarEntry("Files", Icons.Outlined.Folder, Destination.Files),
        SidebarEntry("People", Icons.Outlined.Person, Destination.Settings),
    )
    Destination.Files -> listOf(
        SidebarEntry("All files", Icons.Outlined.Folder, Destination.Files),
        SidebarEntry("Recent activity", Icons.Outlined.Star, Destination.Activity),
    )
    Destination.Database -> listOf(
        SidebarEntry("Table", Icons.Outlined.GridView, Destination.Database),
        SidebarEntry("Board", Icons.Outlined.Check, Destination.Tasks),
        SidebarEntry("Graph", Icons.Outlined.Difference, Destination.Graph),
    )
    Destination.Graph, Destination.ObjectDetail -> listOf(
        SidebarEntry("Knowledge graph", Icons.Outlined.Difference, Destination.Graph),
        SidebarEntry("Database", Icons.Outlined.GridView, Destination.Database),
        SidebarEntry("Backlinks", Icons.Outlined.Folder, Destination.NotesHome),
    )
    Destination.Activity -> listOf(
        SidebarEntry("Workspace activity", Icons.Outlined.Star, Destination.Activity),
        SidebarEntry("Sync history", Icons.Outlined.CloudSync, Destination.SyncMonitor),
    )
    Destination.Templates -> listOf(
        SidebarEntry("Workspace templates", Icons.Outlined.Tag, Destination.Templates),
        SidebarEntry("New workspace", Icons.Outlined.Add, Destination.Settings),
    )
    Destination.CommandPalette -> listOf(
        SidebarEntry("Search everything", Icons.Outlined.Search, Destination.Search),
        SidebarEntry("New note", Icons.Outlined.Add, Destination.NotesHome),
    )
    Destination.Settings, Destination.SyncMonitor, Destination.ConflictReview -> listOf(
        SidebarEntry("Settings", Icons.Outlined.Settings, Destination.Settings),
        SidebarEntry("Sync", Icons.Outlined.CloudSync, Destination.SyncMonitor),
        SidebarEntry("Conflicts", Icons.Outlined.Difference, Destination.ConflictReview),
    )
    else -> listOf(
        SidebarEntry("Favorites", Icons.Outlined.Star, Destination.NotesHome),
        SidebarEntry("Archive", Icons.Outlined.Archive, Destination.NotesHome),
        SidebarEntry("Trash", Icons.Outlined.Delete, Destination.NotesHome),
    )
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
