package com.norfold.app.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
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
import com.norfold.app.ui.components.NorfoldDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedButton
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.norfold.app.branding.BuiltInCovers
import com.norfold.app.domain.WorkspaceIconKind
import com.norfold.app.ui.DocsUiState
import com.norfold.app.ui.DocsViewModel
import com.norfold.app.domain.style.ChromeGeometry
import com.norfold.app.domain.style.ChromeGeometry.Corner
import com.norfold.app.ui.components.RowSwitch
import com.norfold.app.ui.NorfoldChrome
import com.norfold.app.ui.selectableChrome
import com.norfold.app.ui.toggleableChrome

enum class WorkspaceVisualTab { Identity, CoverPermissions }

@Composable
fun WorkspaceVisualDialog(
    state: DocsUiState,
    viewModel: DocsViewModel,
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

    NorfoldDialog(
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
                    PermissionRow("Members create docs", membersCreateNotes) { membersCreateNotes = it }
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
    state: DocsUiState,
    viewModel: DocsViewModel,
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

    NorfoldDialog(
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

/**
 * The built-in cover choices, as a single-choice set.
 *
 * **The border is the only thing that says "selected".** There is no tick and no indicator — a chosen cover
 * is a cover with a heavier accent edge and a lighter scrim. That makes this one of the picker population's
 * clearest cases for two decisions:
 *
 * - `selectableChrome` rather than `clickable`, so the choice is *announced*. `clickable` publishes a node
 *   with a name and an action and nothing about state, so a screen-reader user heard eight covers and no
 *   indication of which one their workspace is wearing. Selection drawn and never said is selection only
 *   sighted users have.
 * - The edge weight is read from **one** local, and that same local is what tells the ring how far to stand
 *   off. Inside a 2 dp accent edge the substrate's default 3 dp inset would land the ring half a dp away, and
 *   two accent strokes half a dp apart are one thicker accent stroke — which on this chrome already means
 *   "selected". Focusing the cover you had already chosen would then change nothing visible. Deriving both
 *   from the same value is what makes that impossible to reintroduce by editing one of them.
 *
 * `selectableGroup()` is correct on this `Row` rather than harmful: the strip scrolls but composes every
 * cover, so the announced position counts all of them. See [com.norfold.app.ui.selectableChrome] for the lazy
 * exception that does *not* get the group.
 */
@Composable
private fun BuiltInBackgroundStrip(selectedUri: String, onSelect: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Built-in covers", fontWeight = FontWeight.Bold, fontSize = 13.sp)
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            BuiltInCovers.forEach { cover ->
                val selected = selectedUri == cover.uri
                val edgeWidth = if (selected) ChromeGeometry.PICKER_SELECTED_EDGE_WIDTH else ChromeGeometry.RESTING_EDGE_WIDTH
                Box(
                    Modifier
                        .size(width = 112.dp, height = 64.dp)
                        .border(
                            width = edgeWidth.dp,
                            // The resting edge is solved against the dialog card, not against what fills the
                            // cover. That is deliberate and it is the only honest choice available: a cover is a
                            // *photograph*, so the pixel under this stroke is whatever the image happens to be —
                            // unknowable at composition, and different at each of the four sides. What the
                            // measurement can pin is the outside: the stroke is drawn at the cover's boundary
                            // against the dialog card behind the strip, and that is the contrast that says where
                            // one cover ends and the next begins across an 8 dp gap. `primary` when selected
                            // stays a literal, for the reason [EmojiPickerGrid] gives.
                            color = if (selected) MaterialTheme.colorScheme.primary else with(NorfoldChrome) { restingEdge() },
                            shape = RoundedCornerShape(Corner.Media.radiusDp.dp),
                        )
                        .selectableChrome(
                            selected = selected,
                            corner = Corner.Media,
                            boundaryWidthDp = edgeWidth,
                        ) { onSelect(cover.uri) },
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
    // The preview's own accent wash, hoisted so the edge below can measure against it rather than against the
    // dialog card. It is the difference between an edge that draws and one that does not: `outline` cut to 0.22
    // alpha on this wash measured **1.0023:1** at its worst (Forest, light) — arithmetically present and
    // visually nothing, on the one element in this dialog whose whole job is to show the user what they are
    // about to save. The wash is left translucent because the substrate composites a declared alpha fill over
    // the resolved container, which here is the dialog card `NorfoldDialog` publishes.
    val wash = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
    Box(
        Modifier
            .fillMaxWidth()
            .height(112.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(wash)
            .border(ChromeGeometry.RESTING_EDGE_WIDTH.dp, with(NorfoldChrome) { restingEdge(wash) }, RoundedCornerShape(20.dp)),
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

/**
 * The workspace emoji choices, as a single-choice set.
 *
 * Same population and same two reasons as [BuiltInBackgroundStrip] — announce the selection, and let one
 * value drive both the drawn edge and the ring's stand-off — with two differences worth stating.
 *
 * **The leading `clip` stays.** These cells paint a fill of their own, and a `background` earlier in the chain
 * than a clip is not clipped by it: `selectableChrome`'s own clip arrives after this modifier, so without the
 * explicit one the tinted fill would keep four square corners outside a rounded border. The substrate's clip
 * covers the ripple and the content; it cannot retroactively clip something drawn before it.
 *
 * **No `selectableGroup()`, because this grid cannot be described by one.** The cells are laid out as four
 * `Row`s of four, so the group's direct children are rows rather than cells — a group here announces "4
 * items" against a set of sixteen, and one placed on the outer `Column` announces five, counting the heading.
 * `CollectionInfo` reads composed children, and no single node in this tree has all sixteen. So the position
 * is left unstated rather than stated wrongly; each cell still carries its own selected state, which is the
 * half that was missing.
 */
@Composable
private fun EmojiPickerGrid(selected: String, onPick: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Emoji", fontWeight = FontWeight.Bold, fontSize = 13.sp)
        WorkspaceEmojis.chunked(4).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { emoji ->
                    val isSel = selected == emoji
                    val edgeWidth = if (isSel) ChromeGeometry.PICKER_SELECTED_EDGE_WIDTH else ChromeGeometry.RESTING_EDGE_WIDTH
                    // The cell's own fill, hoisted for the same reason the edge weight is: one value, read once,
                    // used by the fill and by the measurement of the edge drawn on it.
                    val cellFill = if (isSel) MaterialTheme.colorScheme.primary.copy(alpha = 0.20f)
                        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    Box(
                        Modifier
                            .weight(1f)
                            .height(52.dp)
                            .clip(RoundedCornerShape(Corner.Media.radiusDp.dp))
                            .background(cellFill)
                            .border(
                                width = edgeWidth.dp,
                                // Only the resting edge is measured; `primary` stays a literal because a
                                // selected edge is not a boundary cue — it is the answer to "which one did I
                                // pick", and solving it to a neutral ratio would erase that. The resting one
                                // needed it: `outline` at 0.3 alpha on this cell drew 1.0364:1 light and
                                // 1.0287:1 dark, over a fill that separates from the dialog card by 1.0133:1
                                // light. Sixteen cells with neither cue are a wall of emoji with no grid.
                                color = if (isSel) MaterialTheme.colorScheme.primary else with(NorfoldChrome) { restingEdge(cellFill) },
                                shape = RoundedCornerShape(Corner.Media.radiusDp.dp),
                            )
                            .selectableChrome(
                                selected = isSel,
                                corner = Corner.Media,
                                boundaryWidthDp = edgeWidth,
                            ) { onPick(emoji) },
                        contentAlignment = Alignment.Center,
                    ) { Text(emoji, fontSize = 22.sp, fontWeight = FontWeight.Bold) }
                }
                repeat(4 - row.size) { Box(Modifier.weight(1f)) }
            }
        }
    }
}

/**
 * One workspace permission: a label and a switch, activated anywhere along the row.
 *
 * The row carries the toggle rather than a plain click, and the [RowSwitch] is decorative. Before, both were live,
 * which made each permission two accessibility nodes — the row named it without stating it, the switch stated it
 * without naming it — because Compose's semantics merge stops at any descendant that merges, and every Material
 * selection control does. Three of these stack here, so a screen reader met three unnamed switches. The same
 * split, and the same fix, as [SettingsToggleRow], whose documentation carries the detail.
 *
 * It is not routed through that composable: these sit in a scrolling dialog column rather than a clipped
 * [com.norfold.app.ui.components.SettingsGroup], are 13 sp rather than 15 sp, and put the label's icon inline
 * rather than in a 34 dp tile. Only the semantics are shared.
 *
 * The ring is inward at [Corner.Media] and measured against the dialog's own container, which
 * [com.norfold.app.ui.components.NorfoldDialog] announces — so nothing here names a colour, and the row stays
 * correct if the dialog's container token changes.
 *
 * [com.norfold.app.ui.toggleableChrome]'s 48 dp floor replaces the one Material was attaching to the switch,
 * which its null handler removes along with the interaction. It is load-bearing here: this row has no padding of
 * its own.
 */
@Composable
private fun PermissionRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .toggleableChrome(
                checked = checked,
                corner = Corner.Media,
                role = Role.Switch,
                onValueChange = onChange,
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Outlined.Workspaces, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
            Text(label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }
        RowSwitch(checked, null)
    }
}
