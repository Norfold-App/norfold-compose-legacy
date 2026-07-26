package com.norfold.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.automirrored.outlined.Label
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.norfold.app.ui.DocsUiState
import com.norfold.app.ui.DocsViewModel
import com.norfold.app.domain.Destination
import com.norfold.app.domain.Tag
import com.norfold.app.domain.Notebook
import com.norfold.app.domain.SemanticActionId
import com.norfold.app.ui.components.NorfoldDialog
import com.norfold.app.ui.components.SemanticAppIcon

@Composable
fun NotebookScreen(state: DocsUiState, viewModel: DocsViewModel) {
    var name by remember { mutableStateOf("") }
    var scope by remember { mutableStateOf("notes") }
    var icon by remember { mutableStateOf(SemanticActionId.AppFolder) }
    var editing by remember { mutableStateOf<Notebook?>(null) }
    var editName by remember { mutableStateOf("") }
    var editIcon by remember { mutableStateOf(SemanticActionId.AppFolder) }
    editing?.let { folder ->
        NorfoldDialog(
            onDismissRequest = { editing = null },
            title = { Text("Edit folder") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(editName, { editName = it }, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("Folder name") })
                    FolderIconPicker(editIcon) { editIcon = it }
                }
            },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("Cancel") } },
            confirmButton = {
                Button(onClick = {
                    viewModel.updateNotebook(folder, editName, editIcon.stableId)
                    editing = null
                }, enabled = editName.isNotBlank()) { Text("Save") }
            },
        )
    }
    LazyColumn(Modifier.fillMaxSize().padding(top = 58.dp, start = 18.dp, end = 18.dp, bottom = 18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text("Folders", fontWeight = FontWeight.Black, fontSize = 28.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistChip(onClick = { scope = "notes" }, label = { Text(if (scope == "notes") "Notes ✓" else "Notes") })
                AssistChip(onClick = { scope = "documents" }, label = { Text(if (scope == "documents") "Documents ✓" else "Documents") })
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(name, { name = it }, label = { Text(if (scope == "documents") "New document folder" else "New notes folder") }, modifier = Modifier.weight(1f))
                Button(onClick = { viewModel.addNotebook(name, scope, icon.stableId); name = "" }, enabled = name.isNotBlank()) { Text("Add") }
            }
            FolderIconPicker(icon) { icon = it }
        }
        items(state.notebooks.filter { it.scope == scope }, key = { it.id }) { folder ->
            Card(Modifier.fillMaxWidth().animateItem().clickable { viewModel.filterByNotebook(folder.id) }, shape = RoundedCornerShape(18.dp)) {
                Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                    val resolved = SemanticActionId.entries.firstOrNull { it.stableId == folder.iconId } ?: SemanticActionId.AppFolder
                    SemanticAppIcon(resolved, null, Modifier.size(24.dp), Color(folder.color))
                    androidx.compose.foundation.layout.Spacer(Modifier.width(10.dp))
                    Text(folder.name, Modifier.weight(1f), fontWeight = FontWeight.Bold)
                    TextButton(onClick = {
                        editing = folder
                        editName = folder.name
                        editIcon = resolved
                    }) { Text("Edit") }
                }
            }
        }
    }
}

@Composable
private fun FolderIconPicker(selected: SemanticActionId, onSelect: (SemanticActionId) -> Unit) {
    val choices = listOf(
        SemanticActionId.AppFolder,
        SemanticActionId.AppNotes,
        SemanticActionId.AppDocuments,
        SemanticActionId.AppFavorites,
        SemanticActionId.AppTags,
        SemanticActionId.AppFiles,
        SemanticActionId.AppTemplates,
    )
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Icon", fontWeight = FontWeight.SemiBold)
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            choices.forEach { choice ->
                Card(
                    Modifier.clickable { onSelect(choice) },
                    shape = RoundedCornerShape(12.dp),
                ) {
                    SemanticAppIcon(
                        choice,
                        choice.defaultLabel,
                        Modifier.padding(12.dp).size(22.dp),
                        if (choice == selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
fun TagsScreen(state: DocsUiState, viewModel: DocsViewModel) {
    var name by remember { mutableStateOf("") }
    var editingTag by remember { mutableStateOf<Tag?>(null) }
    var deletingTag by remember { mutableStateOf<Tag?>(null) }
    var editName by remember { mutableStateOf("") }
    editingTag?.let { tag ->
        NorfoldDialog(
            onDismissRequest = { editingTag = null },
            title = { Text("Rename tag") },
            text = { OutlinedTextField(editName, { editName = it }, label = { Text("Tag name") }, singleLine = true) },
            dismissButton = { TextButton(onClick = { editingTag = null }) { Text("Cancel") } },
            confirmButton = { Button(onClick = { viewModel.renameTag(tag, editName); editingTag = null }, enabled = editName.isNotBlank()) { Text("Rename") } },
        )
    }
    deletingTag?.let { tag ->
        NorfoldDialog(
            onDismissRequest = { deletingTag = null },
            title = { Text("Delete #${tag.name}?") },
            text = { Text("This removes the tag from every note. The notes themselves are not deleted.") },
            dismissButton = { TextButton(onClick = { deletingTag = null }) { Text("Cancel") } },
            confirmButton = { Button(onClick = { viewModel.deleteTag(tag); deletingTag = null }) { Text("Delete") } },
        )
    }
    LazyColumn(Modifier.fillMaxSize().padding(top = 58.dp, start = 18.dp, end = 18.dp, bottom = 18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text("Tags", fontWeight = FontWeight.Black, fontSize = 28.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(name, { name = it }, label = { Text("New tag") }, modifier = Modifier.weight(1f))
                Button(onClick = { viewModel.addTag(name); name = "" }, enabled = name.isNotBlank()) { Text("Add") }
            }
        }
        items(state.tags.filter { it.scope == "notes" }, key = { it.id }) { tag ->
            val count = state.notes.count { note -> note.tags.any { it.id == tag.id } }
            Card(Modifier.fillMaxWidth().animateItem().clickable { viewModel.filterByTag(tag.id) }, shape = RoundedCornerShape(16.dp)) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(Icons.AutoMirrored.Outlined.Label, null, tint = Color(tag.color))
                    Column(Modifier.weight(1f)) {
                        Text("#${tag.name}", fontWeight = FontWeight.Bold)
                        Text("$count docs", fontSize = 12.sp)
                    }
                    TextButton(onClick = { editName = tag.name; editingTag = tag }) { Text("Rename") }
                    TextButton(onClick = { deletingTag = tag }) { Text("Delete") }
                }
            }
        }
    }
}
