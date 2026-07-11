package com.norfold.app.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Workspaces
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.norfold.app.branding.BuiltInCovers
import com.norfold.app.domain.WorkspaceIconKind
import com.norfold.app.ui.NotesUiState
import com.norfold.app.ui.NotesViewModel

enum class WorkspaceVisualTab { Identity, CoverPermissions }

@Composable
fun WorkspaceVisualDialog(
    state: NotesUiState,
    viewModel: NotesViewModel,
    initialTab: WorkspaceVisualTab = WorkspaceVisualTab.Identity,
    onDismiss: () -> Unit,
) {
    var tab by remember(initialTab) { mutableStateOf(initialTab) }
    var name by remember(state.settings.workspaceName) { mutableStateOf(state.settings.workspaceName) }
    var icon by remember(state.settings.workspaceIcon) { mutableStateOf(state.settings.workspaceIcon) }
    var iconKind by remember(state.settings.workspaceIconKind) { mutableStateOf(state.settings.workspaceIconKind) }
    var iconUri by remember(state.settings.workspaceIconUri) { mutableStateOf(state.settings.workspaceIconUri.orEmpty()) }
    var backgroundUri by remember(state.settings.workspaceBackgroundUri) { mutableStateOf(state.settings.workspaceBackgroundUri.orEmpty()) }
    var adminsControlVisuals by remember(state.settings.adminsControlWorkspaceVisuals) { mutableStateOf(state.settings.adminsControlWorkspaceVisuals) }
    var membersCreateNotes by remember(state.settings.allowMembersCreateNotes) { mutableStateOf(state.settings.allowMembersCreateNotes) }
    var membersInvite by remember(state.settings.allowMembersInvite) { mutableStateOf(state.settings.allowMembersInvite) }
    val iconPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            iconUri = uri.toString()
            iconKind = if (uri.toString().contains(".gif", ignoreCase = true)) WorkspaceIconKind.Gif else WorkspaceIconKind.Image
        }
    }
    val backgroundPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) backgroundUri = uri.toString()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Workspace visuals", fontWeight = FontWeight.Black) },
        text = {
            Column(
                Modifier
                    .heightIn(max = 500.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                WorkspacePreview(name, icon, iconKind, iconUri, backgroundUri)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(tab == WorkspaceVisualTab.Identity, { tab = WorkspaceVisualTab.Identity }, label = { Text("Icon & Identity") }, modifier = Modifier.weight(1f))
                    FilterChip(tab == WorkspaceVisualTab.CoverPermissions, { tab = WorkspaceVisualTab.CoverPermissions }, label = { Text("Cover & Permissions") }, modifier = Modifier.weight(1f))
                }
                if (tab == WorkspaceVisualTab.Identity) {
                    OutlinedTextField(name, { name = it }, label = { Text("Workspace name") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    ) {
                        listOf(WorkspaceIconKind.Text, WorkspaceIconKind.Emoji, WorkspaceIconKind.Image).forEach { kind ->
                            FilterChip(
                                selected = iconKind == kind || (kind == WorkspaceIconKind.Image && iconKind == WorkspaceIconKind.Gif),
                                onClick = { iconKind = kind },
                                label = { Text(kind.name, maxLines = 1) },
                                modifier = Modifier.widthIn(min = 76.dp),
                            )
                        }
                    }
                    if (iconKind == WorkspaceIconKind.Text || iconKind == WorkspaceIconKind.Emoji) {
                        EmojiPickerGrid(selected = icon) { icon = it; iconKind = WorkspaceIconKind.Emoji }
                    }
                    OutlinedTextField(icon, { icon = it.take(4) }, label = { Text("Single character or emoji") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    ElevatedButton(onClick = { iconPicker.launch(arrayOf("image/*")) }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Outlined.Image, null)
                        Text("Pick workspace icon")
                    }
                } else {
                    VisualMediaSummary("Workspace icon image", iconUri, "No custom icon selected") {
                        iconUri = ""
                        if (iconKind == WorkspaceIconKind.Image || iconKind == WorkspaceIconKind.Gif) iconKind = WorkspaceIconKind.Text
                    }
                    VisualMediaSummary("Workspace cover", backgroundUri, "No custom cover selected") { backgroundUri = "" }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        ElevatedButton(onClick = { iconPicker.launch(arrayOf("image/*")) }, modifier = Modifier.weight(1f)) { Icon(Icons.Outlined.Image, null); Text("Pick icon") }
                        ElevatedButton(onClick = { backgroundPicker.launch(arrayOf("image/*")) }, modifier = Modifier.weight(1f)) { Icon(Icons.Outlined.Image, null); Text("Pick cover") }
                    }
                    BuiltInBackgroundStrip(backgroundUri) { backgroundUri = it }
                    Text("Permissions", fontWeight = FontWeight.Bold)
                    PermissionRow("Admins control visuals", adminsControlVisuals) { adminsControlVisuals = it }
                    PermissionRow("Members create notes", membersCreateNotes) { membersCreateNotes = it }
                    PermissionRow("Members invite people", membersInvite) { membersInvite = it }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    viewModel.updateWorkspace(
                        name = name,
                        icon = icon,
                        iconKind = iconKind,
                        iconUri = iconUri.takeIf { it.isNotBlank() },
                        backgroundUri = backgroundUri.takeIf { it.isNotBlank() },
                        adminsControlVisuals = adminsControlVisuals,
                        membersCreateNotes = membersCreateNotes,
                        membersInvite = membersInvite,
                    )
                    onDismiss()
                },
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun CreateWorkspaceVisualDialog(
    state: NotesUiState,
    viewModel: NotesViewModel,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf("New Workspace") }
    var icon by remember { mutableStateOf("N") }
    var iconKind by remember { mutableStateOf(WorkspaceIconKind.Text) }
    var iconUri by remember { mutableStateOf("") }
    var backgroundUri by remember { mutableStateOf("") }
    val iconPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            iconUri = uri.toString()
            iconKind = if (uri.toString().contains(".gif", ignoreCase = true)) WorkspaceIconKind.Gif else WorkspaceIconKind.Image
        }
    }
    val backgroundPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) backgroundUri = uri.toString()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Create workspace", fontWeight = FontWeight.Black) },
        text = {
            Column(
                Modifier
                    .heightIn(max = 500.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                WorkspacePreview(name, icon, iconKind, iconUri, backgroundUri)
                OutlinedTextField(name, { name = it }, label = { Text("Workspace name") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                ) {
                    WorkspaceIconKind.entries.forEach { kind ->
                        FilterChip(
                            selected = iconKind == kind,
                            onClick = { iconKind = kind },
                            label = { Text(kind.name, maxLines = 1) },
                            modifier = Modifier.widthIn(min = 76.dp),
                        )
                    }
                }
                OutlinedTextField(icon, { icon = it.take(4) }, label = { Text("Single character or emoji") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    ElevatedButton(onClick = { iconPicker.launch(arrayOf("image/*")) }, modifier = Modifier.weight(1f)) { Icon(Icons.Outlined.Image, null); Text("Pick icon", maxLines = 1) }
                    ElevatedButton(onClick = { backgroundPicker.launch(arrayOf("image/*")) }, modifier = Modifier.weight(1f)) { Icon(Icons.Outlined.Image, null); Text("Pick cover", maxLines = 1) }
                }
                BuiltInBackgroundStrip(backgroundUri) { backgroundUri = it }
                VisualMediaSummary(
                    label = "Workspace icon image",
                    value = iconUri,
                    empty = "No custom icon selected",
                    onClear = {
                        iconUri = ""
                        if (iconKind == WorkspaceIconKind.Image || iconKind == WorkspaceIconKind.Gif) iconKind = WorkspaceIconKind.Text
                    },
                )
                VisualMediaSummary(
                    label = "Workspace cover",
                    value = backgroundUri,
                    empty = "No custom cover selected",
                    onClear = { backgroundUri = "" },
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    viewModel.createWorkspace(
                        name = name,
                        icon = icon,
                        iconKind = iconKind,
                        iconUri = iconUri.takeIf { it.isNotBlank() },
                        backgroundUri = backgroundUri.takeIf { it.isNotBlank() },
                    )
                    onDismiss()
                },
            ) { Text("Create") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun VisualMediaSummary(label: String, value: String, empty: String, onClear: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(Icons.Outlined.Image, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
            Column(Modifier.weight(1f)) {
                Text(label, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                Text(
                    if (value.isBlank()) empty else if (value.startsWith("builtin://")) "Built-in cover selected" else "Device image selected",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (value.isNotBlank()) {
                TextButton(onClick = onClear) {
                    Text("Remove", maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun BuiltInBackgroundStrip(selectedUri: String, onSelect: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Built-in covers", fontWeight = FontWeight.Bold, fontSize = 13.sp)
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            BuiltInCovers.forEach { cover ->
                val selected = selectedUri == cover.uri
                Box(
                    Modifier
                        .size(width = 112.dp, height = 64.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .border(
                            width = if (selected) 2.dp else 1.dp,
                            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.24f),
                            shape = RoundedCornerShape(14.dp),
                        )
                        .clickable { onSelect(cover.uri) },
                ) {
                    Image(painterResource(cover.drawableRes), null, Modifier.matchParentSize(), contentScale = ContentScale.Crop)
                    Box(Modifier.matchParentSize().background(MaterialTheme.colorScheme.background.copy(alpha = if (selected) 0.08f else 0.24f)))
                    Text(
                        cover.title,
                        modifier = Modifier.align(Alignment.BottomStart).padding(8.dp),
                        color = MaterialTheme.colorScheme.onPrimary,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun WorkspacePreview(name: String, icon: String, iconKind: WorkspaceIconKind, iconUri: String, backgroundUri: String) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(112.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f))
            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.22f), RoundedCornerShape(20.dp)),
    ) {
        if (backgroundUri.isNotBlank()) {
            AsyncImage(backgroundUri, null, Modifier.matchParentSize(), contentScale = ContentScale.Crop)
            Box(Modifier.matchParentSize().background(MaterialTheme.colorScheme.background.copy(alpha = 0.42f)))
        }
        Row(Modifier.align(Alignment.BottomStart).padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.size(46.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surface), contentAlignment = Alignment.Center) {
                if ((iconKind == WorkspaceIconKind.Image || iconKind == WorkspaceIconKind.Gif) && iconUri.isNotBlank()) {
                    AsyncImage(iconUri, null, Modifier.matchParentSize().clip(CircleShape), contentScale = ContentScale.Crop)
                } else {
                    Text(icon.ifBlank { "N" }.take(2), fontWeight = FontWeight.Black, fontSize = 18.sp, maxLines = 1)
                }
            }
            Column {
                Text(name.ifBlank { "Workspace" }, fontWeight = FontWeight.Black, fontSize = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("Icon, background, and permissions sync with the workspace.", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

private val WorkspaceEmojis = listOf(
    "🚀", "📝", "🌱", "👑", "⏳", "P", "📌", "🎯",
    "🧠", "🎨", "🔥", "🌍", "📊", "💎", "🌸", "🙂",
)

@Composable
private fun EmojiPickerGrid(selected: String, onPick: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Emoji", fontWeight = FontWeight.Bold, fontSize = 13.sp)
        WorkspaceEmojis.chunked(4).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { emoji ->
                    val isSel = selected == emoji
                    Box(
                        Modifier
                            .weight(1f)
                            .height(52.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(if (isSel) MaterialTheme.colorScheme.primary.copy(alpha = 0.20f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                            .border(
                                width = if (isSel) 2.dp else 1.dp,
                                color = if (isSel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                                shape = RoundedCornerShape(14.dp),
                            )
                            .clickable { onPick(emoji) },
                        contentAlignment = Alignment.Center,
                    ) { Text(emoji, fontSize = 22.sp, fontWeight = FontWeight.Bold) }
                }
                repeat(4 - row.size) { Box(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun PermissionRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Outlined.Workspaces, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
            Text(label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }
        Switch(checked, onChange)
    }
}
