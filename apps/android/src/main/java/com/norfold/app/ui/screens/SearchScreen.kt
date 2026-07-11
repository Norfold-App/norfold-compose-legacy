package com.norfold.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Tag
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.norfold.app.domain.Destination
import com.norfold.app.domain.WorkspaceObject
import com.norfold.app.domain.WorkspaceObjectType
import com.norfold.app.ui.NotesUiState
import com.norfold.app.ui.NotesViewModel
import com.norfold.app.ui.components.EmptyNotes
import com.norfold.app.ui.components.SearchField
import com.norfold.app.ui.components.pressScale

private data class SearchResult(
    val title: String,
    val detail: String,
    val type: String,
    val icon: ImageVector,
    val weight: Int,
    val action: () -> Unit,
)

@Composable
fun SearchScreen(state: NotesUiState, viewModel: NotesViewModel) {
    val query = state.searchQuery.trim()
    val results = buildSearchResults(state, viewModel, query)
    Column(Modifier.fillMaxSize().padding(top = 58.dp, start = 18.dp, end = 18.dp, bottom = 18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Search", fontWeight = FontWeight.Black, fontSize = 30.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        SearchField(state.searchQuery, viewModel::search, Modifier.fillMaxWidth().padding(bottom = 2.dp))
        if (query.isBlank() && state.notes.isEmpty() && state.workspaceObjects.isEmpty()) {
            EmptyNotes(viewModel::createNote)
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (query.isBlank()) {
                    item { SectionLabel("Recent workspace objects") }
                }
                items(results, key = { "${it.type}:${it.title}:${it.detail}" }) { result ->
                    SearchResultRow(result, Modifier.animateItem())
                }
            }
        }
    }
}

private fun buildSearchResults(state: NotesUiState, viewModel: NotesViewModel, query: String): List<SearchResult> {
    fun matches(vararg values: String): Boolean = query.isBlank() || values.any { it.contains(query, ignoreCase = true) }
    val objectResults = state.workspaceObjects
        .filter { matches(it.title, it.summary, it.tags, it.objectType.name) }
        .map { obj ->
            SearchResult(
                title = obj.title,
                detail = "${obj.objectType.name} · ${obj.summary.ifBlank { obj.tags.ifBlank { "Workspace object" } }}",
                type = obj.objectType.name,
                icon = iconFor(obj),
                weight = when {
                    obj.pinned -> 0
                    obj.objectType == WorkspaceObjectType.Note -> 1
                    else -> 3
                },
                action = { viewModel.openWorkspaceObject(obj) },
            )
        }
    val tagResults = state.tags
        .filter { matches(it.name, "tag") }
        .map {
            SearchResult(
                title = "#${it.name}",
                detail = "${state.notes.count { note -> note.tags.any { tag -> tag.name == it.name } }} notes",
                type = "Tag",
                icon = Icons.Outlined.Tag,
                weight = 4,
                action = { viewModel.go(Destination.Tags) },
            )
        }
    val fileResults = state.workspaceFiles
        .filter { matches(it.displayName, it.mimeType, "file") }
        .map { file ->
            SearchResult(
                title = file.displayName,
                detail = "File · ${file.mimeType.ifBlank { "attachment" }}",
                type = "File",
                icon = Icons.AutoMirrored.Outlined.InsertDriveFile,
                weight = 4,
                action = { viewModel.go(Destination.Files) },
            )
        }
    val activityResults = state.workspaceActivities
        .filter { matches(it.title, it.detail, it.actor, "activity") }
        .take(12)
        .map { activity ->
            SearchResult(
                title = activity.title,
                detail = "Activity · ${activity.detail.ifBlank { activity.actor }}",
                type = "Activity",
                icon = Icons.Outlined.History,
                weight = 6,
                action = { viewModel.go(Destination.Activity) },
            )
        }
    // Settings sections + navigable command surfaces, all reachable from the one search.
    val settingsResults = listOf(
        Triple("Appearance", Destination.Settings, "Theme, colors and layout"),
        Triple("Profile", Destination.Settings, "Your identity and workspace name"),
        Triple("Editor", Destination.Settings, "Writing and note behavior"),
        Triple("Security & Vault", Destination.Settings, "Lock, encryption and privacy"),
        Triple("Sync settings", Destination.Settings, "Google Drive and folders"),
        Triple("Backup & Import", Destination.ImportExport, "Export, restore and backups"),
        Triple("Diagnostics", Destination.Settings, "Logs and app health"),
    ).filter { matches(it.first, it.third, "settings") }
        .map {
            SearchResult(
                title = it.first,
                detail = it.third,
                type = "Settings",
                icon = Icons.Outlined.Settings,
                weight = 7,
                action = { viewModel.go(it.second) },
            )
        }
    val commandResults = listOf(
        Triple("Command Palette", Destination.CommandPalette, Icons.Outlined.Code),
        Triple("Knowledge Graph", Destination.Graph, Icons.Outlined.GridView),
        Triple("Canvas", Destination.Canvas, Icons.Outlined.GridView),
        Triple("Chat", Destination.Chat, Icons.Outlined.ChatBubbleOutline),
        Triple("Tasks", Destination.Tasks, Icons.Outlined.Check),
        Triple("Notebooks", Destination.Notebooks, Icons.Outlined.Folder),
        Triple("Tags", Destination.Tags, Icons.Outlined.Tag),
    ).filter { matches(it.first, "command", "open", "go to") }
        .map {
            SearchResult(
                title = it.first,
                detail = "Open ${it.first}",
                type = "Command",
                icon = it.third,
                weight = 8,
                action = { viewModel.go(it.second) },
            )
        }
    return (objectResults + tagResults + fileResults + activityResults + settingsResults + commandResults)
        .sortedWith(compareBy<SearchResult> { it.weight }.thenByDescending { it.title.contains(query, ignoreCase = true) }.thenBy { it.title.lowercase() })
        .take(120)
}

@Composable
private fun SearchResultRow(result: SearchResult, modifier: Modifier = Modifier) {
    val interaction = remember { MutableInteractionSource() }
    Surface(
        modifier
            .fillMaxWidth()
            .pressScale(interaction)
            .clickable(interactionSource = interaction, indication = androidx.compose.material3.ripple(), onClick = result.action),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.78f),
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(result.icon, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f)) {
                Text(result.title, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(result.detail, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Text(result.type, color = MaterialTheme.colorScheme.primary, fontSize = 11.sp)
        }
    }
}

@Composable
private fun SectionLabel(label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Outlined.Search, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.size(8.dp))
        Text(label, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun iconFor(obj: WorkspaceObject): ImageVector = when (obj.objectType) {
    WorkspaceObjectType.Note -> Icons.Outlined.Folder
    WorkspaceObjectType.Task -> Icons.Outlined.Check
    WorkspaceObjectType.Goal -> Icons.Outlined.Check
    WorkspaceObjectType.CalendarEvent -> Icons.Outlined.Settings
    WorkspaceObjectType.File -> Icons.Outlined.Folder
    WorkspaceObjectType.Canvas -> Icons.Outlined.GridView
    WorkspaceObjectType.ChatMessage -> Icons.Outlined.ChatBubbleOutline
    WorkspaceObjectType.DatabaseRow -> Icons.Outlined.GridView
    WorkspaceObjectType.Workspace -> Icons.Outlined.Folder
    WorkspaceObjectType.System -> Icons.Outlined.Settings
}
