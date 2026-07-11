package com.norfold.app.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.CloudDone
import androidx.compose.material.icons.outlined.Difference
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.Tag
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ElevatedButton
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.norfold.app.domain.Destination
import com.norfold.app.domain.GoalStatus
import com.norfold.app.domain.TaskStatus
import com.norfold.app.domain.WorkspaceActivity
import com.norfold.app.domain.WorkspaceFileItem
import com.norfold.app.domain.WorkspaceObject
import com.norfold.app.domain.WorkspaceObjectType
import com.norfold.app.ui.NotesUiState
import com.norfold.app.ui.NotesViewModel
import com.norfold.app.ui.components.GlobalSearchBar
import com.norfold.app.ui.components.pressScale
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.cos
import kotlin.math.sin

private enum class DatabaseViewMode(val label: String) { Table("Table"), List("List"), Board("Board"), Gallery("Gallery"), Timeline("Timeline") }

@Composable
fun WorkspaceHubScreen(state: NotesUiState, viewModel: NotesViewModel, inboxMode: Boolean = false) {
    val needsSetup = !inboxMode && state.settings.syncUserName.isBlank() && state.settings.syncProvider.name == "None"
    LazyColumn(
        Modifier.fillMaxSize().padding(start = 18.dp, end = 18.dp, top = 58.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            HubSearchBar(viewModel)
        }
        item {
            WorkspaceHero(state, viewModel, inboxMode)
        }
        item {
            HubStats(state, viewModel)
        }
        if (!inboxMode) {
            item {
                PlanningOverview(state, viewModel)
            }
        }
        item {
            QuickCaptureRow(viewModel)
        }
        if (inboxMode) {
            item {
                HubSection("Inbox", "Capture now, process later.") {
                    EmptyActionCard(
                        title = "Inbox is ready",
                        detail = "Quick notes, files, imports, links, screenshots, and voice captures land here.",
                        action = "New note",
                        onAction = viewModel::createNote,
                    )
                }
            }
        } else {
            item {
                HubSection("Today's tasks", "${state.tasks.count { it.status != TaskStatus.Done }} open") {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        state.tasks.filter { it.status != TaskStatus.Done }.take(4).forEach { task ->
                            CompactHubRow(task.title, task.description.ifBlank { task.assignee.ifBlank { "@owner" } }, Icons.Outlined.Check) {
                                viewModel.go(Destination.Tasks)
                            }
                        }
                        if (state.tasks.none { it.status != TaskStatus.Done }) {
                            EmptyActionCard("No open tasks", "Plan the next thing from the task board.", "Add task", viewModel::createTaskAndOpen)
                        }
                    }
                }
            }
            item {
                HubSection("Continue writing", "Recent notes") {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(state.notes.take(8)) { note ->
                            Surface(
                                Modifier.width(154.dp).height(126.dp).clickable { viewModel.select(note) },
                                shape = RoundedCornerShape(18.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.48f),
                            ) {
                                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.SpaceBetween) {
                                    Text(note.title, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    Text(note.bodyMarkdown.replace(Regex("[#*`>]"), "").trim(), fontSize = 11.sp, maxLines = 2, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text(relative(note.updatedAt), fontSize = 10.sp, color = MaterialTheme.colorScheme.primary)
                                }
                            }
                        }
                    }
                }
            }
            item {
                HubSection("Workspace activity", "Latest changes") {
                    ActivityList(state.workspaceActivities.take(5))
                }
            }
            item {
                HubSection("Sync chain", state.settings.lastSyncStatus) {
                    SyncSummaryCard(state, viewModel)
                }
            }
            if (needsSetup) {
                item {
                    FirstRunSetupCard(viewModel)
                }
            }
        }
    }
}

@Composable
private fun FirstRunSetupCard(viewModel: NotesViewModel) {
    Surface(
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.13f),
        tonalElevation = 2.dp,
    ) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Workspace setup", fontWeight = FontWeight.Black, fontSize = 16.sp)
            Text(
                "Create locally, restore from backup, or connect sync when you are ready.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
            )
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                QuickChip("Create now", Icons.Outlined.Add, viewModel::createNote)
                QuickChip("Restore", Icons.Outlined.CloudDone) { viewModel.go(Destination.Settings) }
                QuickChip("Import", Icons.Outlined.Folder) { viewModel.go(Destination.Settings) }
            }
        }
    }
}

@Composable
private fun WorkspaceHero(state: NotesUiState, viewModel: NotesViewModel, inboxMode: Boolean) {
    val active = state.workspaces.firstOrNull { it.id == state.settings.activeWorkspaceId }
    Surface(shape = RoundedCornerShape(26.dp), color = MaterialTheme.colorScheme.surface.copy(alpha = 0.78f), tonalElevation = 2.dp) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.size(52.dp).clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.primary), contentAlignment = Alignment.Center) {
                    Text((active?.icon ?: state.settings.workspaceIcon).take(2), color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.Black, fontSize = 20.sp)
                }
                Column(Modifier.weight(1f)) {
                    Text(if (inboxMode) "Inbox" else "Good day, ${state.settings.syncPublicName.ifBlank { state.settings.syncUserName.ifBlank { "Aftab" } }}", fontWeight = FontWeight.Black, fontSize = 22.sp)
                    Text(active?.name ?: state.settings.workspaceName, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                }
                Icon(Icons.Outlined.Home, null, tint = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@Composable
private fun HubSearchBar(viewModel: NotesViewModel) {
    GlobalSearchBar(onOpen = { viewModel.go(Destination.Search) })
}

@Composable
private fun HubStats(state: NotesUiState, viewModel: NotesViewModel) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        StatCard("Notes", state.notes.size.toString(), Icons.Outlined.Folder) { viewModel.go(Destination.NotesHome) }
        StatCard("Tasks", state.tasks.size.toString(), Icons.Outlined.Check) { viewModel.go(Destination.Tasks) }
        StatCard("Goals", state.goals.size.toString(), Icons.Outlined.Check) { viewModel.go(Destination.Goals) }
        StatCard("Canvas", state.canvasNodes.size.toString(), Icons.Outlined.GridView) { viewModel.go(Destination.Canvas) }
        StatCard("Files", state.workspaceFiles.size.toString(), Icons.Outlined.Folder) { viewModel.go(Destination.Files) }
        StatCard("Objects", state.workspaceObjects.size.toString(), Icons.Outlined.Difference) { viewModel.go(Destination.Graph) }
    }
}

@Composable
private fun PlanningOverview(state: NotesUiState, viewModel: NotesViewModel) {
    val openTasks = state.tasks.count { it.status != TaskStatus.Done }
    val completedTasks = state.tasks.size - openTasks
    val taskProgress = if (state.tasks.isEmpty()) 0f else completedTasks.toFloat() / state.tasks.size
    val achievedGoals = state.goals.count { it.status == GoalStatus.Achieved }
    val goalProgress = when {
        state.goals.isEmpty() -> 0f
        else -> state.goals.map { goal ->
            if (goal.target <= 0.0) 0f else (goal.progress / goal.target).coerceIn(0.0, 1.0).toFloat()
        }.average().toFloat()
    }

    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        PlanningProgressCard(
            title = "Task progress",
            detail = "$completedTasks complete · $openTasks open",
            progress = taskProgress,
            action = "Open tasks",
            onClick = { viewModel.go(Destination.Tasks) },
        )
        PlanningProgressCard(
            title = "Goals",
            detail = if (state.goals.isEmpty()) "Create your first goal" else "$achievedGoals of ${state.goals.size} achieved",
            progress = goalProgress,
            action = "Open goals",
            onClick = { viewModel.go(Destination.Goals) },
        )
    }
}

@Composable
private fun PlanningProgressCard(
    title: String,
    detail: String,
    progress: Float,
    action: String,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier.width(248.dp).clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(title, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(detail, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text("${(progress * 100).toInt()}%", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
            }
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth().height(5.dp).clip(CircleShape),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
            )
            Text(action, color = MaterialTheme.colorScheme.primary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun StatCard(label: String, value: String, icon: ImageVector, onClick: () -> Unit) {
    Surface(Modifier.width(118.dp).clickable(onClick = onClick), shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surface.copy(alpha = 0.82f)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
            Text(value, fontWeight = FontWeight.Black, fontSize = 20.sp)
            Text(label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun QuickCaptureRow(viewModel: NotesViewModel) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        QuickChip("Note", Icons.Outlined.Add, viewModel::createNote)
        QuickChip("Task", Icons.Outlined.Check, viewModel::createTaskAndOpen)
        QuickChip("Canvas", Icons.Outlined.GridView, viewModel::createCanvasAndOpen)
        QuickChip("Command", Icons.Outlined.Search) { viewModel.go(Destination.CommandPalette) }
    }
}

@Composable
private fun QuickChip(label: String, icon: ImageVector, onClick: () -> Unit) {
    Surface(Modifier.clickable(onClick = onClick), shape = RoundedCornerShape(999.dp), color = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
            Text(label, fontSize = 13.sp, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun HubSection(title: String, subtitle: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, fontWeight = FontWeight.Black, fontSize = 18.sp)
            Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
        }
        content()
    }
}

@Composable
private fun CompactHubRow(title: String, detail: String, icon: ImageVector, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Surface(
        modifier.fillMaxWidth().pressScale(interaction).clickable(interactionSource = interaction, indication = androidx.compose.material3.ripple(), onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.74f),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(detail, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun EmptyActionCard(title: String, detail: String, action: String, onAction: () -> Unit) {
    Card(shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(title, fontWeight = FontWeight.Black)
            Text(detail, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(onClick = onAction) { Text(action) }
        }
    }
}

@Composable
private fun SyncSummaryCard(state: NotesUiState, viewModel: NotesViewModel) {
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface.copy(alpha = 0.78f)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(Modifier.size(72.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.CloudDone, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(34.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(if (state.syncing) "Syncing" else "All caught up", fontWeight = FontWeight.Black)
                Text("Conflicts ${state.settings.syncConflictCount} · Files ${state.workspaceFiles.size}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(state.settings.lastSyncAt?.let(::relative) ?: "No sync yet", fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
            }
            TextButton(onClick = { viewModel.go(Destination.SyncMonitor) }) { Text("Open") }
        }
    }
}

@Composable
private fun ActivityList(items: List<WorkspaceActivity>) {
    if (items.isEmpty()) {
        EmptyActionCard("No activity yet", "Changes across the workspace will appear here.", "Rebuild index") {}
    } else {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items.forEach { activity ->
                CompactHubRow(activity.title, "${activity.actor} · ${relative(activity.createdAt)} · ${activity.detail}", Icons.Outlined.Star) {}
            }
        }
    }
}

@Composable
fun FilesLibraryScreen(state: NotesUiState, viewModel: NotesViewModel, onPickFile: () -> Unit) {
    LazyColumn(Modifier.fillMaxSize().padding(top = 58.dp, start = 18.dp, end = 18.dp, bottom = 18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Text("Files", fontWeight = FontWeight.Black, fontSize = 28.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()) }
        item { GlobalSearchBar(onOpen = { viewModel.go(Destination.Search) }) }
        item { Button(onClick = onPickFile, modifier = Modifier.fillMaxWidth()) { Text("Upload") } }
        if (state.workspaceFiles.isEmpty()) {
            item { EmptyActionCard("No files yet", "Upload PDFs, images, audio, markdown, ZIPs, and project assets.", "Upload file", onPickFile) }
        } else {
            items(state.workspaceFiles, key = { it.id }) { file -> FileRow(file, Modifier.animateItem()) }
        }
    }
}

@Composable
private fun FileRow(file: WorkspaceFileItem, modifier: Modifier = Modifier) {
    CompactHubRow(file.displayName, "${file.mimeType} · ${bytes(file.sizeBytes)} · ${relative(file.updatedAt)}", Icons.Outlined.Folder, modifier) {}
}

@Composable
fun DatabaseScreen(state: NotesUiState, viewModel: NotesViewModel) {
    var viewMode by remember { mutableStateOf(DatabaseViewMode.Table) }
    var typeFilter by remember { mutableStateOf<WorkspaceObjectType?>(null) }
    val objects = remember(state.workspaceObjects, typeFilter) {
        state.workspaceObjects
            .filter { obj -> typeFilter == null || obj.objectType == typeFilter }
            .sortedByDescending { it.updatedAt }
            .take(160)
    }
    LazyColumn(Modifier.fillMaxSize().padding(top = 58.dp, start = 18.dp, end = 18.dp, bottom = 18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Text("Database", fontWeight = FontWeight.Black, fontSize = 28.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            Text("Objects, relations, files, tasks, notes, and workspace records in one place.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        }
        item { GlobalSearchBar(onOpen = { viewModel.go(Destination.Search) }) }
        item {
            Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.surface.copy(alpha = 0.76f)) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        DatabaseChip("All", typeFilter == null) { typeFilter = null }
                        WorkspaceObjectType.entries.forEach { type ->
                            DatabaseChip(type.label(), typeFilter == type) { typeFilter = type }
                        }
                    }
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        DatabaseViewMode.entries.forEach { mode ->
                            DatabaseChip(mode.label, viewMode == mode) { viewMode = mode }
                        }
                    }
                }
            }
        }
        item {
            DatabaseStats(objects, state)
        }
        if (objects.isEmpty()) {
            item {
                EmptyActionCard(
                    title = "No matching objects",
                    detail = "Clear filters or rebuild the workspace index from Activity.",
                    action = "Rebuild index",
                    onAction = viewModel::rebuildWorkspaceIndex,
                )
            }
        } else {
            when (viewMode) {
                DatabaseViewMode.Table -> {
                    item { DatabaseTableHeader() }
                    items(objects) { obj -> DatabaseTableRow(obj, state, viewModel) }
                }
                DatabaseViewMode.List -> {
                    WorkspaceObjectType.entries.forEach { type ->
                        val group = objects.filter { it.objectType == type }
                        if (group.isNotEmpty()) {
                            item { DatabaseGroupHeader(type.label(), group.size) }
                            items(group) { obj -> DatabaseObjectRow(obj, state, viewModel) }
                        }
                    }
                }
                DatabaseViewMode.Board -> {
                    item {
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            WorkspaceObjectType.entries.forEach { type ->
                                val group = objects.filter { it.objectType == type }
                                if (group.isNotEmpty()) {
                                    DatabaseBoardColumn(type.label(), group, state, viewModel)
                                }
                            }
                        }
                    }
                }
                DatabaseViewMode.Gallery -> {
                    items(objects.chunked(2)) { row ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            row.forEach { obj ->
                                DatabaseObjectCard(obj, state, viewModel, Modifier.weight(1f))
                            }
                            if (row.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
                }
                DatabaseViewMode.Timeline -> {
                    objects.groupBy { timelineBucket(it.updatedAt) }.forEach { (bucket, bucketObjects) ->
                        item { DatabaseGroupHeader(bucket, bucketObjects.size) }
                        items(bucketObjects) { obj -> DatabaseTimelineRow(obj, state, viewModel) }
                    }
                }
            }
        }
    }
}

@Composable
private fun DatabaseChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(999.dp),
        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.54f),
    ) {
        Text(
            label,
            Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
            color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
        )
    }
}

@Composable
private fun DatabaseStats(objects: List<WorkspaceObject>, state: NotesUiState) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        DatabaseStat("Shown", objects.size.toString(), Icons.Outlined.GridView)
        DatabaseStat("Links", state.workspaceObjectLinks.size.toString(), Icons.Outlined.Difference)
        DatabaseStat("Comments", state.workspaceComments.size.toString(), Icons.Outlined.ChatBubbleOutline)
        DatabaseStat("Files", state.workspaceFiles.size.toString(), Icons.Outlined.Folder)
        DatabaseStat("History", state.workspaceObjectHistory.size.toString(), Icons.Outlined.Star)
    }
}

@Composable
private fun DatabaseStat(label: String, value: String, icon: ImageVector) {
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface.copy(alpha = 0.78f)) {
        Row(Modifier.width(132.dp).padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
            Column {
                Text(value, fontWeight = FontWeight.Black, fontSize = 18.sp)
                Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
            }
        }
    }
}

@Composable
private fun DatabaseTableHeader() {
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Type", Modifier.weight(0.23f), fontWeight = FontWeight.Bold, fontSize = 12.sp)
        Text("Object", Modifier.weight(0.42f), fontWeight = FontWeight.Bold, fontSize = 12.sp)
        Text("Relations", Modifier.weight(0.18f), fontWeight = FontWeight.Bold, fontSize = 12.sp)
        Text("Updated", Modifier.weight(0.17f), fontWeight = FontWeight.Bold, fontSize = 12.sp)
    }
}

@Composable
private fun DatabaseTableRow(obj: WorkspaceObject, state: NotesUiState, viewModel: NotesViewModel) {
    val relations = state.workspaceObjectLinks.count { it.fromObjectId == obj.id || it.toObjectId == obj.id }
    Surface(Modifier.fillMaxWidth().clickable { viewModel.openWorkspaceObject(obj) }, shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surface.copy(alpha = 0.76f)) {
        Row(Modifier.padding(10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(0.23f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(iconForObject(obj.objectType), null, tint = Color(obj.color), modifier = Modifier.size(16.dp))
                Text(obj.objectType.label(), fontSize = 12.sp, color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Column(Modifier.weight(0.42f)) {
                Text(obj.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                Text(obj.summary.ifBlank { obj.tags.ifBlank { "No summary" } }, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(relations.toString(), Modifier.weight(0.18f), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(relative(obj.updatedAt), Modifier.weight(0.17f), fontSize = 11.sp, color = MaterialTheme.colorScheme.primary, maxLines = 1)
        }
    }
}

@Composable
private fun DatabaseObjectRow(obj: WorkspaceObject, state: NotesUiState, viewModel: NotesViewModel) {
    Surface(Modifier.fillMaxWidth().clickable { viewModel.openWorkspaceObject(obj) }, shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface.copy(alpha = 0.74f)) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.size(38.dp).clip(RoundedCornerShape(12.dp)).background(Color(obj.color).copy(alpha = 0.18f)), contentAlignment = Alignment.Center) {
                Icon(iconForObject(obj.objectType), null, tint = Color(obj.color), modifier = Modifier.size(20.dp))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(obj.title, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(obj.summary.ifBlank { "Open for backlinks, comments, history, and source." }, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(databaseMeta(obj, state), fontSize = 11.sp, color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun DatabaseBoardColumn(title: String, objects: List<WorkspaceObject>, state: NotesUiState, viewModel: NotesViewModel) {
    Surface(Modifier.width(246.dp), shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface.copy(alpha = 0.66f)) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, Modifier.weight(1f), fontWeight = FontWeight.Black)
                Text(objects.size.toString(), color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
            }
            objects.take(12).forEach { obj ->
                DatabaseObjectCard(obj, state, viewModel, Modifier.fillMaxWidth())
            }
            if (objects.size > 12) {
                Text("+${objects.size - 12} more", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun DatabaseObjectCard(obj: WorkspaceObject, state: NotesUiState, viewModel: NotesViewModel, modifier: Modifier = Modifier) {
    Surface(modifier.clickable { viewModel.openWorkspaceObject(obj) }, shape = RoundedCornerShape(18.dp), color = Color(obj.color).copy(alpha = 0.16f)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(iconForObject(obj.objectType), null, tint = Color(obj.color), modifier = Modifier.size(18.dp))
                Text(obj.objectType.label(), color = MaterialTheme.colorScheme.primary, fontSize = 11.sp, maxLines = 1)
            }
            Text(obj.title, fontWeight = FontWeight.Black, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(obj.summary.ifBlank { obj.tags.ifBlank { "No preview available." } }, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
            Text(databaseMeta(obj, state), color = MaterialTheme.colorScheme.primary, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun DatabaseTimelineRow(obj: WorkspaceObject, state: NotesUiState, viewModel: NotesViewModel) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(12.dp).clip(CircleShape).background(Color(obj.color)))
            Box(Modifier.width(2.dp).height(58.dp).background(MaterialTheme.colorScheme.outline.copy(alpha = 0.32f)))
        }
        DatabaseObjectRow(obj, state, viewModel)
    }
}

@Composable
private fun DatabaseGroupHeader(title: String, count: Int) {
    Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, fontWeight = FontWeight.Black, fontSize = 17.sp)
        Text("$count", color = MaterialTheme.colorScheme.primary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
}

private fun WorkspaceObjectType.label(): String = when (this) {
    WorkspaceObjectType.ChatMessage -> "Chat"
    WorkspaceObjectType.DatabaseRow -> "Database"
    else -> name
}

private fun databaseMeta(obj: WorkspaceObject, state: NotesUiState): String {
    val links = state.workspaceObjectLinks.count { it.fromObjectId == obj.id || it.toObjectId == obj.id }
    val comments = state.workspaceComments.count { it.objectId == obj.id }
    val history = state.workspaceObjectHistory.count { it.objectId == obj.id }
    return "${relative(obj.updatedAt)} · $links links · $comments comments · $history events"
}

private fun timelineBucket(time: Long): String {
    val diff = System.currentTimeMillis() - time
    return when {
        diff < 86_400_000 -> "Today"
        diff < 172_800_000 -> "Yesterday"
        diff < 604_800_000 -> "This week"
        diff < 2_592_000_000 -> "This month"
        else -> DateTimeFormatter.ofPattern("MMM yyyy").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(time))
    }
}

@Composable
fun GraphScreen(state: NotesUiState, viewModel: NotesViewModel) {
    Column(Modifier.fillMaxSize().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Graph", fontWeight = FontWeight.Black, fontSize = 28.sp)
        Surface(Modifier.fillMaxWidth().weight(1f), shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surface.copy(alpha = 0.70f)) {
            Canvas(Modifier.fillMaxSize().padding(16.dp)) {
                val objects = state.workspaceObjects.take(12)
                val center = Offset(size.width / 2f, size.height / 2f)
                val radius = minOf(size.width, size.height) * 0.34f
                val positions = objects.mapIndexed { index, obj ->
                    val angle = (index.toFloat() / objects.size.coerceAtLeast(1)) * 6.28318f
                    obj.id to Offset(center.x + cos(angle) * radius, center.y + sin(angle) * radius)
                }.toMap()
                state.workspaceObjectLinks.forEach { link ->
                    val a = positions[link.fromObjectId]
                    val b = positions[link.toObjectId]
                    if (a != null && b != null) drawLine(Color(0xFF8B5CF6).copy(alpha = 0.46f), a, b, strokeWidth = 3f)
                }
                objects.forEach { obj ->
                    val p = positions[obj.id] ?: center
                    drawCircle(Color(obj.color).copy(alpha = 0.88f), radius = 24f, center = p)
                    drawCircle(Color.White.copy(alpha = 0.18f), radius = 34f, center = p)
                }
            }
        }
        Text("Objects ${state.workspaceObjects.size} · Links ${state.workspaceObjectLinks.size}", color = MaterialTheme.colorScheme.onSurfaceVariant)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(state.workspaceObjects.take(16)) { obj ->
                Surface(Modifier.clickable { viewModel.openWorkspaceObject(obj) }, shape = RoundedCornerShape(999.dp), color = Color(obj.color).copy(alpha = 0.18f)) {
                    Text(obj.title, Modifier.padding(horizontal = 12.dp, vertical = 8.dp), maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
fun ActivityScreen(state: NotesUiState, viewModel: NotesViewModel) {
    val feed = state.workspaceActivities.map {
        ActivityTimelineEntry(
            time = it.createdAt,
            title = it.title,
            detail = "${it.actor} · ${it.detail}",
            color = Color(0xFF8B5CF6),
            icon = Icons.Outlined.Star,
        )
    } + state.workspaceObjectHistory.take(80).map {
        ActivityTimelineEntry(
            time = it.createdAt,
            title = it.summary,
            detail = "${it.actor} · ${it.historyType.name}",
            color = Color(0xFF4FACFE),
            icon = Icons.Outlined.Difference,
        )
    }
    LazyColumn(Modifier.fillMaxSize().padding(top = 58.dp, start = 18.dp, end = 18.dp, bottom = 18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Text("My Activity", fontWeight = FontWeight.Black, fontSize = 28.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()) }
        item { GlobalSearchBar(onOpen = { viewModel.go(Destination.Search) }) }
        item {
            ElevatedButton(onClick = viewModel::rebuildWorkspaceIndex, modifier = Modifier.fillMaxWidth()) { Text("Rebuild index") }
        }
        val grouped = feed.sortedByDescending { it.time }.groupBy { timelineBucket(it.time) }
        grouped.forEach { (bucket, entries) ->
            item {
                Text(bucket, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, fontSize = 13.sp)
            }
            items(entries) { entry ->
                ActivityTimelineRow(entry, Modifier.animateItem())
            }
        }
    }
}

private data class ActivityTimelineEntry(
    val time: Long,
    val title: String,
    val detail: String,
    val color: Color,
    val icon: ImageVector,
)

@Composable
private fun ActivityTimelineRow(entry: ActivityTimelineEntry, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Surface(shape = CircleShape, color = entry.color.copy(alpha = 0.22f)) {
                Icon(entry.icon, null, tint = entry.color, modifier = Modifier.padding(7.dp).size(16.dp))
            }
            Box(Modifier.width(2.dp).height(44.dp).background(MaterialTheme.colorScheme.outline.copy(alpha = 0.18f)))
        }
        Surface(
            modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f),
        ) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(entry.title, modifier = Modifier.weight(1f), fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(relative(entry.time), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(entry.detail, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
fun TemplatesScreen(state: NotesUiState, viewModel: NotesViewModel) {
    val templates = listOf("Personal", "Student", "Research", "Software Team", "Business", "Second Brain", "Journal", "Knowledge Base")
    LazyColumn(Modifier.fillMaxSize().padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Text("Templates", fontWeight = FontWeight.Black, fontSize = 28.sp) }
        items(templates) { name ->
            CompactHubRow(name, "Workspace structure with notes, tasks, files, and boards", Icons.Outlined.Tag) {
                viewModel.createWorkspace(name)
            }
        }
    }
}

@Composable
fun CommandPaletteScreen(state: NotesUiState, viewModel: NotesViewModel) {
    var query by remember { mutableStateOf("") }
    val commands: List<Pair<String, () -> Unit>> = listOf(
        "Create note" to { viewModel.createNote(); Unit },
        "Create task" to { viewModel.createTaskAndOpen(); Unit },
        "Open files" to { viewModel.go(Destination.Files); Unit },
        "Open graph" to { viewModel.go(Destination.Graph); Unit },
        "Open sync" to { viewModel.go(Destination.SyncMonitor); Unit },
        "Open settings" to { viewModel.go(Destination.Settings); Unit },
        "Rebuild workspace index" to { viewModel.rebuildWorkspaceIndex(); Unit },
    ).filter { it.first.contains(query, ignoreCase = true) || query.isBlank() }
    LazyColumn(Modifier.fillMaxSize().padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Text("Command Palette", fontWeight = FontWeight.Black, fontSize = 28.sp)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), singleLine = true, leadingIcon = { Icon(Icons.Outlined.Search, null) }, label = { Text("Search commands and workspace objects") })
        }
        items(commands) { (label, action) ->
            CompactHubRow(label, "Command", Icons.Outlined.Search, onClick = action)
        }
        items(state.workspaceObjects.filter { it.title.contains(query, true) && query.isNotBlank() }.take(20)) { obj ->
            CompactHubRow(obj.title, "${obj.objectType.name} · ${obj.summary}", Icons.Outlined.Folder) {
                viewModel.openWorkspaceObject(obj)
            }
        }
    }
}

@Composable
private fun ObjectInspectorDialog(obj: WorkspaceObject, state: NotesUiState, viewModel: NotesViewModel, onDismiss: () -> Unit) {
    var comment by remember(obj.id) { mutableStateOf("") }
    val links = state.workspaceObjectLinks.filter { it.fromObjectId == obj.id || it.toObjectId == obj.id }
    val comments = state.workspaceComments.filter { it.objectId == obj.id }
    val history = state.workspaceObjectHistory.filter { it.objectId == obj.id }.take(8)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(obj.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                item {
                    Text("${obj.objectType.name} · ${relative(obj.updatedAt)}", color = MaterialTheme.colorScheme.primary, fontSize = 12.sp)
                    Text(obj.summary.ifBlank { "No summary yet." }, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                }
                item { Text("Links", fontWeight = FontWeight.Bold) }
                if (links.isEmpty()) {
                    item { Text("No backlinks or related objects yet.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                } else {
                    items(links) { link ->
                        val otherId = if (link.fromObjectId == obj.id) link.toObjectId else link.fromObjectId
                        val other = state.workspaceObjects.firstOrNull { it.id == otherId }
                        Text("${link.linkType.name}: ${other?.title ?: otherId}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                item { Text("Comments", fontWeight = FontWeight.Bold) }
                items(comments.take(5)) { item ->
                    Text("${item.authorDisplayName}: ${item.body}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                item {
                    OutlinedTextField(
                        value = comment,
                        onValueChange = { comment = it },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 2,
                        label = { Text("Add comment") },
                    )
                }
                item { Text("History", fontWeight = FontWeight.Bold) }
                if (history.isEmpty()) {
                    item { Text("No history recorded yet.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                } else {
                    items(history) { item ->
                        Text("${item.historyType.name} · ${item.actor} · ${relative(item.createdAt)}\n${item.summary}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (comment.isNotBlank()) {
                        viewModel.addWorkspaceComment(obj.id, comment)
                        comment = ""
                    } else {
                        viewModel.openWorkspaceObject(obj)
                        onDismiss()
                    }
                },
            ) { Text(if (comment.isBlank()) "Open" else "Comment") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

private fun iconForObject(type: WorkspaceObjectType): ImageVector = when (type) {
    WorkspaceObjectType.Note -> Icons.Outlined.Folder
    WorkspaceObjectType.Task -> Icons.Outlined.Check
    WorkspaceObjectType.Goal -> Icons.Outlined.Check
    WorkspaceObjectType.CalendarEvent -> Icons.Outlined.Star
    WorkspaceObjectType.File -> Icons.Outlined.Folder
    WorkspaceObjectType.Canvas -> Icons.Outlined.GridView
    WorkspaceObjectType.ChatMessage -> Icons.Outlined.ChatBubbleOutline
    WorkspaceObjectType.DatabaseRow -> Icons.Outlined.GridView
    WorkspaceObjectType.Workspace -> Icons.Outlined.Home
    WorkspaceObjectType.System -> Icons.Outlined.Difference
    }

private fun relative(time: Long): String {
    val diff = System.currentTimeMillis() - time
    return when {
        diff < 60_000 -> "just now"
        diff < 3_600_000 -> "${diff / 60_000}m ago"
        diff < 86_400_000 -> "${diff / 3_600_000}h ago"
        else -> DateTimeFormatter.ofPattern("MMM d").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(time))
    }
}

private fun bytes(size: Long): String = when {
    size < 1024 -> "$size B"
    size < 1024 * 1024 -> "${size / 1024} KB"
    else -> "${size / (1024 * 1024)} MB"
}
