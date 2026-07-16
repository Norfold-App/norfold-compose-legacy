package com.norfold.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Tag
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.norfold.app.domain.TaskPriority
import com.norfold.app.domain.WorkspaceObject
import com.norfold.app.domain.WorkspaceObjectType
import com.norfold.app.ui.DocsUiState
import com.norfold.app.ui.DocsViewModel
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun ObjectDetailScreen(state: DocsUiState, viewModel: DocsViewModel, modifier: Modifier = Modifier) {
    val obj = state.selectedObject
    if (obj == null) {
        EmptyObjectDetail(viewModel, modifier)
        return
    }
    val links = state.workspaceObjectLinks.filter { it.fromObjectId == obj.id || it.toObjectId == obj.id }
    val comments = state.workspaceComments.filter { it.objectId == obj.id }
    val history = state.workspaceObjectHistory.filter { it.objectId == obj.id }
    var comment by remember(obj.id) { mutableStateOf("") }

    LazyColumn(
        modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            ObjectHero(obj, state, viewModel)
        }
        item {
            SourcePreview(obj, state, viewModel)
        }
        item {
            ObjectSection("Relations", "${links.size} connected objects", Icons.Outlined.Link) {
                if (links.isEmpty()) {
                    MutedText("No backlinks, embeds, attachments, or related objects yet.")
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        links.forEach { link ->
                            val otherId = if (link.fromObjectId == obj.id) link.toObjectId else link.fromObjectId
                            val other = state.workspaceObjects.firstOrNull { it.id == otherId }
                            ObjectMiniRow(
                                title = other?.title ?: "Object $otherId",
                                detail = "${link.linkType.name}${link.label.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""}",
                                icon = iconFor(other?.objectType ?: WorkspaceObjectType.System),
                                color = other?.color ?: obj.color,
                                onClick = { other?.let(viewModel::openWorkspaceObject) },
                            )
                        }
                    }
                }
            }
        }
        item {
            ObjectSection("Comments", "${comments.size} notes from collaborators", Icons.Outlined.ChatBubbleOutline) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    comments.take(12).forEach {
                        Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f)) {
                            Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(it.authorDisplayName, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                Text(it.body, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                                Text(relative(it.updatedAt), color = MaterialTheme.colorScheme.primary, fontSize = 10.sp)
                            }
                        }
                    }
                    OutlinedTextField(
                        value = comment,
                        onValueChange = { comment = it },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 2,
                        label = { Text("Add comment") },
                    )
                    Button(
                        onClick = {
                            viewModel.addWorkspaceComment(obj.id, comment)
                            comment = ""
                        },
                        enabled = comment.isNotBlank(),
                    ) { Text("Add comment") }
                }
            }
        }
        item {
            ObjectSection("History", "${history.size} events", Icons.Outlined.History) {
                if (history.isEmpty()) {
                    MutedText("No history recorded yet.")
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        history.take(20).forEach {
                            ObjectMiniRow(
                                title = it.summary,
                                detail = "${it.historyType.name} · ${it.actor} · ${relative(it.createdAt)}",
                                icon = Icons.Outlined.History,
                                color = obj.color,
                                onClick = null,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyObjectDetail(viewModel: DocsViewModel, modifier: Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("No object selected", fontWeight = FontWeight.Black, fontSize = 22.sp)
            Text("Open an object from Search, Database, Graph, or Files.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(onClick = { viewModel.go(com.norfold.app.domain.Destination.Database) }) { Text("Open database") }
        }
    }
}

@Composable
private fun ObjectHero(obj: WorkspaceObject, state: DocsUiState, viewModel: DocsViewModel) {
    Surface(shape = RoundedCornerShape(26.dp), color = MaterialTheme.colorScheme.surface.copy(alpha = 0.82f), tonalElevation = 2.dp) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(
                    Modifier.size(56.dp).background(Color(obj.color).copy(alpha = 0.18f), RoundedCornerShape(18.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(iconFor(obj.objectType), null, tint = Color(obj.color), modifier = Modifier.size(28.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text(obj.title, fontWeight = FontWeight.Black, fontSize = 24.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text("${if (obj.objectType == WorkspaceObjectType.Note) "Doc" else obj.objectType.name} · updated ${relative(obj.updatedAt)}", color = MaterialTheme.colorScheme.primary, fontSize = 12.sp)
                }
            }
            if (obj.summary.isNotBlank()) {
                Text(obj.summary, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 5, overflow = TextOverflow.Ellipsis)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (obj.pinned) Pill("Pinned")
                if (obj.archived) Pill("Archived")
                obj.tags.split(",").map { it.trim() }.filter { it.isNotBlank() }.take(4).forEach { Pill("#$it") }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { viewModel.openWorkspaceObjectSource(obj) }) {
                    Icon(Icons.AutoMirrored.Outlined.OpenInNew, null, Modifier.size(16.dp))
                    Spacer(Modifier.size(6.dp))
                    Text("Open source")
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatPill("Links", state.workspaceObjectLinks.count { it.fromObjectId == obj.id || it.toObjectId == obj.id })
                StatPill("Comments", state.workspaceComments.count { it.objectId == obj.id })
                StatPill("History", state.workspaceObjectHistory.count { it.objectId == obj.id })
            }
        }
    }
}

@Composable
private fun SourcePreview(obj: WorkspaceObject, state: DocsUiState, viewModel: DocsViewModel) {
    ObjectSection("Source", "Live data behind this object", iconFor(obj.objectType)) {
        when (obj.objectType) {
            WorkspaceObjectType.Note -> {
                val note = state.notes.firstOrNull { it.id == obj.sourceId }
                if (note == null) MutedText("Source doc is not in the active list.") else {
                    Text(note.document.plainText().take(700), color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 10, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = { viewModel.select(note) }) { Text("Edit doc") }
                }
            }
            WorkspaceObjectType.Task -> {
                val task = state.tasks.firstOrNull { it.id == obj.sourceId }
                if (task == null) MutedText("Source task is unavailable.") else {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(task.description.ifBlank { "No description." }, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Pill(task.status.name)
                            Pill(task.priority.name)
                            Pill(task.assignee.ifBlank { "@owner" })
                            if (task.priority == TaskPriority.Urgent) Pill("High attention")
                        }
                    }
                }
            }
            WorkspaceObjectType.Goal -> {
                val goal = state.goals.firstOrNull { it.id == obj.sourceId }
                MutedText(goal?.let { "${it.progress} / ${it.target} ${it.unit}\n${it.description.ifBlank { it.status.name }}" } ?: "Source goal is unavailable.")
            }
            WorkspaceObjectType.CalendarEvent -> {
                val event = state.calendarEvents.firstOrNull { it.id == obj.sourceId }
                MutedText(event?.let { "${relative(it.startAt)} · ${it.source.name}\n${it.description.ifBlank { "No description." }}" } ?: "Source event is unavailable.")
            }
            WorkspaceObjectType.File -> {
                val file = state.workspaceFiles.firstOrNull { it.objectId == obj.id }
                MutedText(file?.let { "${it.displayName}\n${it.mimeType} · ${bytes(it.sizeBytes)}\n${it.uri}" } ?: obj.summary.ifBlank { "File metadata object." })
            }
            WorkspaceObjectType.ChatMessage -> {
                val message = state.chatMessages.firstOrNull { it.id == obj.sourceId }
                if (message == null) MutedText("Source chat message is unavailable.") else {
                    Text("${message.authorDisplayName} · ${relative(message.createdAt)}", color = MaterialTheme.colorScheme.primary, fontSize = 12.sp)
                    Text(message.body, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            WorkspaceObjectType.DatabaseRow, WorkspaceObjectType.Workspace, WorkspaceObjectType.System -> {
                MutedText(obj.summary.ifBlank { "This object is represented by workspace metadata." })
            }
        }
    }
}

@Composable
private fun ObjectSection(title: String, subtitle: String, icon: ImageVector, content: @Composable () -> Unit) {
    Surface(shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.surface.copy(alpha = 0.76f)) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                Column(Modifier.weight(1f)) {
                    Text(title, fontWeight = FontWeight.Black)
                    Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
                }
            }
            content()
        }
    }
}

@Composable
private fun ObjectMiniRow(title: String, detail: String, icon: ImageVector, color: Long, onClick: (() -> Unit)?) {
    Surface(
        Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.38f),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.size(28.dp).background(Color(color).copy(alpha = 0.18f), CircleShape), contentAlignment = Alignment.Center) {
                Icon(icon, null, Modifier.size(16.dp), tint = Color(color))
            }
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(detail, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun Pill(text: String) {
    Surface(shape = RoundedCornerShape(999.dp), color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)) {
        Text(text, Modifier.padding(horizontal = 10.dp, vertical = 5.dp), color = MaterialTheme.colorScheme.primary, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun StatPill(label: String, value: Int) {
    Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f)) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Text(value.toString(), fontWeight = FontWeight.Black)
            Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp)
        }
    }
}

@Composable
private fun MutedText(text: String) {
    Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
}

private fun iconFor(type: WorkspaceObjectType): ImageVector = when (type) {
    WorkspaceObjectType.Note -> Icons.Outlined.Folder
    WorkspaceObjectType.Task -> Icons.Outlined.Check
    WorkspaceObjectType.Goal -> Icons.Outlined.Check
    WorkspaceObjectType.CalendarEvent -> Icons.Outlined.History
    WorkspaceObjectType.File -> Icons.Outlined.Folder
    WorkspaceObjectType.ChatMessage -> Icons.Outlined.ChatBubbleOutline
    WorkspaceObjectType.DatabaseRow -> Icons.Outlined.Tag
    WorkspaceObjectType.Workspace -> Icons.Outlined.Folder
    WorkspaceObjectType.System -> Icons.Outlined.History
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
