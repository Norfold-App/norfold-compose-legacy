package com.norfold.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectableGroup
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
import com.norfold.app.domain.style.ChromeGeometry.Corner
import com.norfold.app.ui.focusableChrome
import com.norfold.app.ui.MIN_TOUCH_TARGET_DP
import com.norfold.app.ui.NorfoldChrome
import com.norfold.app.ui.selectableChrome

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
            // `Corner.Card`, not the 18 dp this drew: the tag row two screens down is the same component in
            // the same role and drew 16 dp, and one file holding two radii for one kind of card is the drift
            // the tiers exist to remove. The tier is also what keeps the ring concentric — the shape and the
            // ring now read one value instead of two literals that agreed by luck.
            Card(
                Modifier
                    .fillMaxWidth()
                    .animateItem()
                    .focusableChrome(corner = Corner.Card, onClickLabel = "Show only this folder") { viewModel.filterByNotebook(folder.id) },
                shape = NorfoldChrome.shape(Corner.Card),
            ) {
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

/**
 * The radius a folder-icon cell is clipped and ringed at — one value, read by the [Card]'s shape and by the
 * substrate that has to keep the ring concentric inside it.
 */
private const val FolderIconCellRadius = 12f

/**
 * The folder icon choices, as a single-choice set.
 *
 * **Selection here is a tint and nothing else** — the chosen icon is drawn in the accent, the rest in
 * `onSurfaceVariant`. So `clickable` left this worse off than the border pickers: a screen-reader user heard
 * seven icon names with no indication which one the folder would get, and a keyboard user had no indication
 * of where they were at all. `selectableChrome` supplies both, and because the cell draws no boundary of its
 * own the ring's default stand-off is the right one.
 *
 * `selectableGroup()` is correct on this `Row`: it scrolls but composes all seven, so the announced position
 * counts all seven. The `widthIn` is the other axis of the touch target — the substrate raises the height to
 * 48 dp, and a 22 dp icon in 12 dp of padding is 46 dp wide, two short.
 */
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
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            choices.forEach { choice ->
                Card(
                    Modifier
                        .widthIn(min = MIN_TOUCH_TARGET_DP.dp)
                        .selectableChrome(
                            selected = choice == selected,
                            radiusDp = FolderIconCellRadius,
                        ) { onSelect(choice) },
                    shape = RoundedCornerShape(FolderIconCellRadius.dp),
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
            Card(
                Modifier
                    .fillMaxWidth()
                    .animateItem()
                    .focusableChrome(corner = Corner.Card, onClickLabel = "Show only this tag") { viewModel.filterByTag(tag.id) },
                shape = NorfoldChrome.shape(Corner.Card),
            ) {
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
