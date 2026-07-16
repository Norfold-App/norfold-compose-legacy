package com.norfold.app.ui.screens

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Backup
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.CloudSync
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.Difference
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material.icons.outlined.ImportExport
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.CreditCard
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Workspaces
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import com.norfold.app.ui.components.NorfoldDialog
import androidx.compose.material3.ElevatedButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.norfold.app.R
import com.norfold.app.BuildConfig
import com.norfold.app.branding.BuiltInCovers
import com.norfold.app.branding.NorfoldLogo
import com.norfold.app.branding.palette
import com.norfold.app.domain.Destination
import com.norfold.app.domain.EditorLineWidth
import com.norfold.app.domain.ContextualMenuColor
import com.norfold.app.domain.ContextualMenuStyle
import com.norfold.app.domain.NoteGestureAction
import com.norfold.app.domain.SyncFolderAction
import com.norfold.app.domain.SyncFolderRequest
import com.norfold.app.domain.SyncProvider
import com.norfold.app.domain.ThemeMode
import com.norfold.app.domain.ThemeProfile
import com.norfold.app.cloud.ExternalServiceConfig
import com.norfold.app.data.BiometricVaultKeyStore
import com.norfold.app.ui.DocsUiState
import com.norfold.app.ui.DocsViewModel
import com.norfold.app.ui.components.AccentDot
import com.norfold.app.ui.components.EditFieldDialog
import com.norfold.app.ui.components.GlobalSearchBar
import com.norfold.app.ui.components.OptionPickerDialog
import com.norfold.app.ui.components.RowChevron
import com.norfold.app.ui.components.RowDivider
import com.norfold.app.ui.components.RowSwitch
import com.norfold.app.ui.components.RowValue
import com.norfold.app.ui.components.SettingsGroup
import com.norfold.app.ui.components.SettingsRow
import com.norfold.app.ui.components.SettingsSectionLabel
import coil.compose.AsyncImage
import kotlinx.coroutines.delay

private enum class SettingsSection(val title: String, val subtitle: String, val icon: ImageVector) {
    Profile("Profile", "Username, public name and device", Icons.Outlined.Person),
    Workspace("Workspace", "Name, visuals and member defaults", Icons.Outlined.Workspaces),
    Appearance("Appearance", "Theme, palette, density and type", Icons.Outlined.Palette),
    Editor("Editor & Markdown", "Line width, syntax and writing behavior", Icons.Outlined.Code),
    Security("Security & Vault", "Vault, biometrics and screenshots", Icons.Outlined.Security),
    Sync("Account & Restore", "Account, sync, backup and recovery", Icons.Outlined.CloudSync),
    Backup("Backup & Import", "Encrypted exports, restore and Markdown import", Icons.Outlined.Backup),
    App("App Info", "Version, identity and build details", Icons.Outlined.Settings),
    SyncEngine("Sync Settings", "Exit sync, provider status and manual controls", Icons.Outlined.CloudSync),
    Conflicts("Conflict Resolution", "Merge strategy and conflict records", Icons.Outlined.Difference),
    WorkspaceMembers("Members", "Local workspace membership", Icons.Outlined.Groups),
    WorkspaceAdvanced("Advanced", "Export or delete this workspace", Icons.Outlined.Tune),
    Permissions("Permissions", "Workspace roles and access control", Icons.Outlined.Security),
    Diagnostics("Diagnostics", "Crash logs and local reports", Icons.Outlined.Settings),
}

private val RootSettingsSections = setOf(
    SettingsSection.Profile,
    SettingsSection.Workspace,
    SettingsSection.Appearance,
    SettingsSection.Editor,
    SettingsSection.Security,
    SettingsSection.Sync,
    SettingsSection.Backup,
    SettingsSection.SyncEngine,
    SettingsSection.Conflicts,
    SettingsSection.App,
    SettingsSection.Diagnostics,
)

@Composable
fun SettingsScreen(
    state: DocsUiState,
    viewModel: DocsViewModel,
    onPickSyncFolder: (SyncFolderRequest) -> Unit,
    onPickMarkdown: () -> Unit,
    onPickBackupFolder: () -> Unit,
    onPickBackupFile: (String) -> Unit,
    onConnectGoogleDrive: () -> Unit,
    onDisconnectGoogleDrive: () -> Unit,
) {
    var section by remember { mutableStateOf<SettingsSection?>(null) }
    var showWorkspaceDialog by remember { mutableStateOf(false) }
    var workspaceDialogTab by remember { mutableStateOf(WorkspaceVisualTab.Identity) }
    if (showWorkspaceDialog) WorkspaceVisualDialog(state, viewModel, workspaceDialogTab) { showWorkspaceDialog = false }

    BackHandler(enabled = section != null || showWorkspaceDialog) {
        if (showWorkspaceDialog) showWorkspaceDialog = false else section = null
    }

    val pendingSection by viewModel.pendingSettingsSection.collectAsState()
    LaunchedEffect(pendingSection) {
        pendingSection?.let { name ->
            SettingsSection.entries.firstOrNull { it.name == name }?.let { section = it }
            viewModel.consumeSettingsSection()
        }
    }
    LaunchedEffect(state.destination) {
        section = when (state.destination) {
            Destination.Vault -> SettingsSection.Security
            Destination.ImportExport -> SettingsSection.Backup
            else -> section
        }
    }

    LazyColumn(
        Modifier.fillMaxSize().padding(start = 18.dp, end = 18.dp, top = 18.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        item {
            Box(Modifier.fillMaxWidth().widthIn(max = 1040.dp)) {
            if (section == null) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Settings", fontWeight = FontWeight.Black, fontSize = 30.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                    Text("Manage your workspace, privacy, sync, and app preferences.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    IconButton(onClick = { section = null }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, null) }
                    Text(section!!.title, fontWeight = FontWeight.SemiBold, fontSize = 20.sp)
                }
            }
            }
        }
        if (section == null) {
            item { Box(Modifier.fillMaxWidth().widthIn(max = 1040.dp)) { GlobalSearchBar(onOpen = { viewModel.go(Destination.Search) }, onNavigationClick = viewModel::toggleSidebar) } }
            item { SettingsIndex { section = it } }
        } else {
            item {
                Box(Modifier.fillMaxWidth().widthIn(max = 1040.dp)) {
                when (section) {
                    SettingsSection.Profile -> ProfileSettings(state, viewModel)
                    SettingsSection.Workspace -> WorkspaceSettings(
                        state = state,
                        onVisuals = { workspaceDialogTab = WorkspaceVisualTab.Identity; showWorkspaceDialog = true },
                        onDetails = { workspaceDialogTab = WorkspaceVisualTab.Identity; showWorkspaceDialog = true },
                        onMembers = { section = SettingsSection.WorkspaceMembers },
                        onPermissions = { section = SettingsSection.Permissions },
                        onAdvanced = { section = SettingsSection.WorkspaceAdvanced },
                    )
                    SettingsSection.Appearance -> AppearanceSettings(state, viewModel)
                    SettingsSection.Editor -> EditorSettings(state, viewModel)
                    SettingsSection.Security -> SecuritySettings(state, viewModel)
                    SettingsSection.Sync -> SyncSettings(state, viewModel, onPickSyncFolder, onConnectGoogleDrive, onDisconnectGoogleDrive) { section = it }
                    SettingsSection.Backup -> BackupImportSettings(state, viewModel, onPickMarkdown, onPickBackupFolder, onPickBackupFile)
                    SettingsSection.SyncEngine -> SyncEngineSettings(state, viewModel) { section = SettingsSection.Sync }
                    SettingsSection.Conflicts -> ConflictSettings(state, viewModel)
                    SettingsSection.Permissions -> PermissionsSettings(state, viewModel)
                    SettingsSection.WorkspaceMembers -> WorkspaceMembersSettings(state)
                    SettingsSection.WorkspaceAdvanced -> WorkspaceAdvancedSettings(state, viewModel) { section = SettingsSection.Sync }
                    SettingsSection.Diagnostics -> DiagnosticsSettings(state, viewModel)
                    SettingsSection.App -> AppInfoSettings()
                    null -> Unit
                }
                }
            }
        }
    }
}

@Composable
private fun SettingsIndex(onOpen: (SettingsSection) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth().widthIn(max = 1040.dp)) {
        val account = listOf(SettingsSection.Profile, SettingsSection.Workspace, SettingsSection.Appearance, SettingsSection.Editor)
        val privacy = listOf(SettingsSection.Security)
        val twoColumns = maxWidth >= 760.dp
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (twoColumns) {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.fillMaxWidth()) {
                    SettingsSectionGroup("Account & Workspace", account, onOpen, Modifier.weight(1f))
                    SettingsSectionGroup("Privacy & Security", privacy, onOpen, Modifier.weight(1f))
                }
            } else {
                SettingsSectionGroup("Account & Workspace", account, onOpen)
                SettingsSectionGroup("Privacy & Security", privacy, onOpen)
            }
            SettingsSectionGroup("Backup & Data", listOf(SettingsSection.Sync, SettingsSection.SyncEngine, SettingsSection.Backup, SettingsSection.Conflicts), onOpen)
            SettingsSectionGroup("About", listOf(SettingsSection.App, SettingsSection.Diagnostics), onOpen)
        }
    }
}

@Composable
private fun SettingsSectionGroup(
    title: String,
    sections: List<SettingsSection>,
    onOpen: (SettingsSection) -> Unit,
    modifier: Modifier = Modifier,
) {
    SettingsGroup(header = title, modifier = modifier) {
        sections.filter { it in RootSettingsSections }.forEachIndexed { index, section ->
            SettingsRow(section.title, subtitle = section.subtitle, icon = section.icon, onClick = { onOpen(section) }, trailing = { RowChevron() })
            if (index < sections.lastIndex) RowDivider()
        }
    }
}

@Composable
private fun ProfileSettings(state: DocsUiState, viewModel: DocsViewModel) {
    val username = state.settings.syncUserName.ifBlank { "owner" }
    val publicName = state.settings.syncPublicName
    val deviceName = state.settings.syncDeviceName
    // null = no dialog; otherwise which field is being edited
    var editing by remember { mutableStateOf<String?>(null) }
    val profileImagePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        viewModel.updateProfileVisuals(uri?.toString(), state.settings.profileBackgroundUri)
    }
    val profileCoverPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        viewModel.updateProfileVisuals(state.settings.profileImageUri, uri?.toString())
    }

    when (editing) {
        "username" -> EditFieldDialog("Username", username, "Used for mentions and links", { editing = null }) { viewModel.updateProfile(it, publicName, deviceName) }
        "public" -> EditFieldDialog("Public name", publicName, "Shown to other members", { editing = null }) { viewModel.updateProfile(username, it, deviceName) }
        "device" -> EditFieldDialog("Device name", deviceName, "Shown in sessions and sync", { editing = null }) { viewModel.updateProfile(username, publicName, it) }
    }

    Column(verticalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.fillMaxWidth()) {
        ProfileBannerCard(
            username = username,
            publicName = publicName,
            profileImageUri = state.settings.profileImageUri,
            coverUri = state.settings.profileBackgroundUri,
            onEditPicture = { profileImagePicker.launch(arrayOf("image/*")) },
            onEditCover = { profileCoverPicker.launch(arrayOf("image/*")) },
        )
        SettingsGroup {
            SettingsRow("Username", subtitle = username, icon = Icons.Outlined.Person, trailing = { EditPencil() }, onClick = { editing = "username" })
            RowDivider()
            SettingsRow("Public name", subtitle = publicName.ifBlank { "Shown to other members" }, icon = Icons.Outlined.Person, trailing = { EditPencil() }, onClick = { editing = "public" })
            RowDivider()
            SettingsRow("Device name", subtitle = deviceName.ifBlank { "Shown in sessions and sync" }, icon = Icons.Outlined.PhoneAndroid, trailing = { EditPencil() }, onClick = { editing = "device" })
        }
        SettingsGroup(header = "Profile customization") {
            SettingsRow("Profile picture", subtitle = "Pick from device", icon = Icons.Outlined.Person, trailing = { EditPencil() }, onClick = { profileImagePicker.launch(arrayOf("image/*")) })
            RowDivider()
            SettingsRow("Cover image / GIF", subtitle = "Pick from device", icon = Icons.Outlined.Palette, trailing = { EditPencil() }, onClick = { profileCoverPicker.launch(arrayOf("image/*")) })
            RowDivider()
            SettingsSectionLabel("Built-in covers")
            BuiltInSettingsCoverStrip(state.settings.profileBackgroundUri.orEmpty()) { cover ->
                viewModel.updateProfileVisuals(state.settings.profileImageUri, cover)
            }
            RowDivider()
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ElevatedButton(
                    onClick = { viewModel.updateProfileVisuals(null, state.settings.profileBackgroundUri) },
                    enabled = !state.settings.profileImageUri.isNullOrBlank(),
                    modifier = Modifier.weight(1f),
                ) { Text("Remove picture", maxLines = 1) }
                ElevatedButton(
                    onClick = { viewModel.updateProfileVisuals(state.settings.profileImageUri, null) },
                    enabled = !state.settings.profileBackgroundUri.isNullOrBlank(),
                    modifier = Modifier.weight(1f),
                ) { Text("Remove cover", maxLines = 1) }
            }
            RowDivider()
            SettingsRow("Accent color", subtitle = state.settings.themeProfile.name, icon = Icons.Outlined.Palette, trailing = { RowValue("Selected", accent = true) })
        }
    }
}

@Composable
private fun EditPencil() {
    Icon(Icons.Outlined.Edit, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
}

@Composable
private fun WorkspaceSettings(
    state: DocsUiState,
    onVisuals: () -> Unit,
    onDetails: () -> Unit,
    onMembers: () -> Unit,
    onPermissions: () -> Unit,
    onAdvanced: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.fillMaxWidth()) {
        WorkspaceBannerCard(
            name = state.settings.workspaceName,
            icon = state.settings.workspaceIcon.ifBlank { "N" },
            backgroundUri = state.settings.workspaceBackgroundUri,
            onEdit = onVisuals,
        )
        SettingsGroup {
            SettingsRow("Workspace visuals", subtitle = "Icon, cover, and appearance", icon = Icons.Outlined.Palette, trailing = { RowChevron() }, onClick = onVisuals)
            RowDivider()
            SettingsRow("Details & Info", subtitle = "Name, description, and basics", icon = Icons.Outlined.Settings, trailing = { RowChevron() }, onClick = onDetails)
            RowDivider()
            SettingsRow("Members", subtitle = "1 member", icon = Icons.Outlined.Person, trailing = { RowChevron() }, onClick = onMembers)
            RowDivider()
            SettingsRow("Permissions", subtitle = "Roles and access control", icon = Icons.Outlined.Security, trailing = { RowChevron() }, onClick = onPermissions)
        }
        SettingsGroup {
            SettingsRow("Advanced", subtitle = "Export or delete workspace", icon = Icons.Outlined.Difference, iconTint = MaterialTheme.colorScheme.error, trailing = { RowChevron() }, onClick = onAdvanced)
        }
    }
}

@Composable
private fun WorkspaceMembersSettings(state: DocsUiState) {
    SettingsGroup(header = "Members") {
        SettingsRow(
            title = state.settings.syncPublicName.ifBlank { state.settings.syncUserName.ifBlank { "Local owner" } },
            subtitle = "Owner · This device",
            icon = Icons.Outlined.Person,
            trailing = { RowValue("Owner", accent = true) },
        )
    }
}

@Composable
private fun WorkspaceAdvancedSettings(state: DocsUiState, viewModel: DocsViewModel, onOpenExport: () -> Unit) {
    var confirmDelete by remember { mutableStateOf(false) }
    val active = state.workspaces.firstOrNull { it.id == state.settings.activeWorkspaceId }
    if (confirmDelete && active != null) {
        NorfoldDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete workspace?") },
            text = { Text("This permanently removes ${active.name} and its local content. The final workspace cannot be deleted.") },
            confirmButton = {
                TextButton(onClick = { viewModel.deleteWorkspace(active.id); confirmDelete = false }, enabled = state.workspaces.size > 1) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
    Column(verticalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.fillMaxWidth()) {
        SettingsGroup {
            SettingsRow("Export workspace", subtitle = "Open encrypted backup and export tools", icon = Icons.Outlined.FileDownload, trailing = { RowChevron() }, onClick = onOpenExport)
            RowDivider()
            SettingsRow(
                "Delete workspace",
                subtitle = if (state.workspaces.size > 1) "Permanently remove this workspace" else "Create another workspace before deleting this one",
                icon = Icons.Outlined.Difference,
                iconTint = MaterialTheme.colorScheme.error,
                titleColor = MaterialTheme.colorScheme.error,
                onClick = { confirmDelete = true },
                trailing = { RowChevron() },
            )
        }
    }
}

@Composable
private fun ProfileBannerCard(
    username: String,
    publicName: String,
    profileImageUri: String?,
    coverUri: String?,
    onEditPicture: () -> Unit,
    onEditCover: () -> Unit,
) {
    Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surface.copy(alpha = 0.78f), tonalElevation = 2.dp) {
        Box(Modifier.fillMaxWidth().aspectRatio(1.95f).clickable(onClick = onEditCover)) {
            if (!coverUri.isNullOrBlank()) {
                AsyncImage(coverUri, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            } else {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Brush.linearGradient(listOf(Color(0xFF1B1E28), Color(0xFF10131B), MaterialTheme.colorScheme.primary.copy(alpha = 0.60f)))),
                )
            }
            Box(Modifier.matchParentSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.58f)))))
            Row(
                Modifier.align(Alignment.BottomStart).padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(Modifier.size(68.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)).clickable(onClick = onEditPicture), contentAlignment = Alignment.Center) {
                    if (!profileImageUri.isNullOrBlank()) {
                        AsyncImage(profileImageUri, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    } else {
                        Text(publicName.ifBlank { username }.take(1).uppercase(), fontWeight = FontWeight.Black, fontSize = 28.sp, color = MaterialTheme.colorScheme.primary)
                    }
                    Box(Modifier.align(Alignment.BottomEnd).size(24.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary), contentAlignment = Alignment.Center) {
                        Icon(Icons.Outlined.Edit, null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(13.dp))
                    }
                }
                Column(Modifier.weight(1f)) {
                    Text(publicName.ifBlank { username }, color = Color.White, fontWeight = FontWeight.Black, fontSize = 20.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("@$username", color = Color.White.copy(alpha = 0.76f), fontSize = 12.sp, maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun WorkspaceBannerCard(name: String, icon: String, backgroundUri: String?, onEdit: () -> Unit) {
    Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surface.copy(alpha = 0.78f), tonalElevation = 2.dp) {
        Box(Modifier.fillMaxWidth().aspectRatio(2.12f).clickable(onClick = onEdit)) {
            if (!backgroundUri.isNullOrBlank()) {
                AsyncImage(backgroundUri, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            } else {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Brush.linearGradient(listOf(Color(0xFF14161F), Color(0xFF1B1E28), MaterialTheme.colorScheme.primary.copy(alpha = 0.70f)))),
                )
            }
            Box(Modifier.matchParentSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.62f)))))
            Row(
                Modifier.align(Alignment.BottomStart).padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(Modifier.size(54.dp).clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.primary), contentAlignment = Alignment.Center) {
                    Text(icon.take(2), fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.onPrimary, fontSize = 22.sp)
                }
                Column(Modifier.weight(1f)) {
                    Text(name, color = Color.White, fontWeight = FontWeight.Black, fontSize = 20.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("1 member · Owner", color = Color.White.copy(alpha = 0.76f), fontSize = 12.sp)
                }
                Icon(Icons.Outlined.Edit, null, tint = Color.White.copy(alpha = 0.84f))
            }
        }
    }
}

@Composable
private fun AppearanceSettings(state: DocsUiState, viewModel: DocsViewModel) {
    val s = state.settings
    var showFontPicker by remember { mutableStateOf(false) }
    if (showFontPicker) OptionPickerDialog("App font", listOf("Inter", "System", "Serif", "Mono"), s.appFont, { showFontPicker = false }) { picked -> viewModel.patchSettings { it.copy(appFont = picked) } }

    Column(verticalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.fillMaxWidth()) {
        // Theme tiles
        Column {
            SettingsSectionLabel("Theme")
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ThemeTile("System", Icons.Outlined.Settings, state.settings.themeMode == ThemeMode.System, Modifier.weight(1f)) { viewModel.setTheme(ThemeMode.System) }
                ThemeTile("Light", Icons.Outlined.LightMode, state.settings.themeMode == ThemeMode.Light, Modifier.weight(1f)) { viewModel.setTheme(ThemeMode.Light) }
                ThemeTile("Dark", Icons.Outlined.DarkMode, state.settings.themeMode == ThemeMode.Dark, Modifier.weight(1f)) { viewModel.setTheme(ThemeMode.Dark) }
            }
        }
        // Accent color dots (wired to palette). Graphite (the neutral default) is shown first.
        Column {
            SettingsSectionLabel("Accent color")
            var showAllPalettes by remember { mutableStateOf(false) }
            val accentProfiles = listOf(ThemeProfile.Graphite) + ThemeProfile.entries.filter { it != ThemeProfile.Graphite }
            SettingsGroup {
                Column {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        accentProfiles.take(6).forEach { profile ->
                            AccentDot(profile.palette().accent, profile == state.settings.themeProfile) { viewModel.setThemeProfile(profile) }
                        }
                        Spacer(Modifier.weight(1f))
                        Text(
                            if (showAllPalettes) "Less" else "More",
                            color = MaterialTheme.colorScheme.primary,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.clickable { showAllPalettes = !showAllPalettes },
                        )
                    }
                    if (showAllPalettes) {
                        Box(Modifier.padding(horizontal = 14.dp).padding(bottom = 14.dp)) {
                            PaletteGrid(state.settings.themeProfile) { viewModel.setThemeProfile(it) }
                        }
                    }
                }
            }
        }
        // Density / font / font-size / animations / reduce-motion
        SettingsGroup {
            SettingsRow("UI density", trailing = { RowValue(if (s.uiDensityCompact) "Compact" else "Comfortable", accent = true) }, onClick = { viewModel.patchSettings { it.copy(uiDensityCompact = !it.uiDensityCompact) } })
            RowDivider(inset = false)
            SettingsRow("Font", trailing = { RowValue(s.appFont, accent = true) }, onClick = { showFontPicker = true })
            RowDivider(inset = false)
            Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                Text("App size", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("A", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Slider(
                        value = s.uiScale,
                        onValueChange = viewModel::setUiScale,
                        modifier = Modifier.weight(1f),
                        valueRange = 0.9f..1.12f,
                    )
                    Text("A", fontSize = 20.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            RowDivider(inset = false)
            SettingsRow("Reduce motion", subtitle = "Minimize navigation and content animations", trailing = { RowSwitch(s.reduceMotion) { viewModel.setPrivacyOption(reduceMotion = it) } })
        }
    }
}

@Composable
private fun ThemeTile(label: String, icon: ImageVector, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Column(
        modifier
            .clip(RoundedCornerShape(16.dp))
            .background(if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.16f) else MaterialTheme.colorScheme.surface)
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.7f),
                shape = RoundedCornerShape(16.dp),
            )
            .clickable(onClick = onClick)
            .padding(vertical = 18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(icon, null, tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
        Text(label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun EditorSettings(state: DocsUiState, viewModel: DocsViewModel) {
    val s = state.settings
    val lineWidths = EditorLineWidth.entries
    var showLinePicker by remember { mutableStateOf(false) }
    var showMenuStylePicker by remember { mutableStateOf(false) }
    var showMenuColorPicker by remember { mutableStateOf(false) }
    if (showLinePicker) OptionPickerDialog("Line width", lineWidths.map { it.name }, s.editorLineWidth.name, { showLinePicker = false }) { p -> viewModel.setEditorLineWidth(EditorLineWidth.valueOf(p)) }
    if (showMenuStylePicker) OptionPickerDialog("Contextual menu", ContextualMenuStyle.entries.map { it.name }, s.contextualMenuStyle.name, { showMenuStylePicker = false }) { p -> viewModel.patchSettings { it.copy(contextualMenuStyle = ContextualMenuStyle.valueOf(p)) } }
    if (showMenuColorPicker) OptionPickerDialog("Menu color", listOf("Follow theme", "App accent"), if (s.contextualMenuColor == ContextualMenuColor.AppAccent) "App accent" else "Follow theme", { showMenuColorPicker = false }) { p -> viewModel.patchSettings { it.copy(contextualMenuColor = if (p == "App accent") ContextualMenuColor.AppAccent else ContextualMenuColor.FollowTheme) } }

    Column(verticalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.fillMaxWidth()) {
        SettingsGroup(header = "Editor") {
            SettingsRow(
                "Document surface",
                subtitle = "Tap to select, tap again to edit, then drag to move",
                trailing = { RowValue("View + Edit", accent = true) },
            )
            RowDivider(inset = false)
            SettingsRow("Line width", trailing = { RowChevron(s.editorLineWidth.name) }, onClick = { showLinePicker = true })
            RowDivider(inset = false)
            SliderRow("Tab size", (s.tabSize - 2) / 6f) { v -> viewModel.patchSettings { it.copy(tabSize = (2 + (v * 6)).toInt().coerceIn(2, 8)) } }
            RowDivider(inset = false)
            SettingsRow("Show line numbers", trailing = { RowSwitch(s.showLineNumbers) { v -> viewModel.patchSettings { it.copy(showLineNumbers = v) } } })
            RowDivider(inset = false)
            SettingsRow("Auto pair brackets", trailing = { RowSwitch(s.autoPairBrackets) { v -> viewModel.patchSettings { it.copy(autoPairBrackets = v) } } })
        }
        SettingsGroup(header = "Markdown") {
            SettingsRow("Inline rendering", subtitle = "Markdown, math, diagrams and embeds render in place", trailing = { RowValue("Always on", accent = true) })
            RowDivider(inset = false)
            SettingsRow("Syntax highlight", trailing = { RowValue(if (s.syntaxColorful) "Colorful" else "Minimal", accent = true) }, onClick = { viewModel.patchSettings { it.copy(syntaxColorful = !it.syntaxColorful) } })
            RowDivider(inset = false)
            SettingsRow("Auto convert on paste", trailing = { RowSwitch(s.autoConvertOnPaste) { v -> viewModel.patchSettings { it.copy(autoConvertOnPaste = v) } } })
        }
        SettingsGroup(header = "Doc interactions") {
            GestureRow("Long press", state.settings.noteLongPressAction) { viewModel.setNoteGestureActions(longPress = it) }
            RowDivider(inset = false)
            GestureRow("Swipe right", state.settings.noteSwipeStartAction) { viewModel.setNoteGestureActions(swipeStart = it) }
            RowDivider(inset = false)
            GestureRow("Swipe left", state.settings.noteSwipeEndAction) { viewModel.setNoteGestureActions(swipeEnd = it) }
        }
        SettingsGroup(header = "Contextual menus") {
            SettingsRow("Menu treatment", subtitle = "Pill is the compact default", trailing = { RowChevron(s.contextualMenuStyle.name) }, onClick = { showMenuStylePicker = true })
            RowDivider(inset = false)
            SettingsRow("Color behavior", subtitle = "System light/dark or the app accent", trailing = { RowChevron(if (s.contextualMenuColor == ContextualMenuColor.AppAccent) "App accent" else "Follow theme") }, onClick = { showMenuColorPicker = true })
        }
    }
}

@Composable
private fun SliderRow(label: String, value: Float, onChange: (Float) -> Unit) {
    Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
        Text(label, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
        Slider(value, onChange, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun GestureRow(label: String, selected: NoteGestureAction, onSelect: (NoteGestureAction) -> Unit) {
    val actions = NoteGestureAction.entries
    SettingsRow(label, trailing = { RowChevron(selected.name) }, onClick = {
        onSelect(actions[(actions.indexOf(selected) + 1) % actions.size])
    })
}

@Composable
private fun GestureActionRow(selected: NoteGestureAction, onSelect: (NoteGestureAction) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
        NoteGestureAction.entries.forEach { action ->
            FilterChip(
                selected = selected == action,
                onClick = { onSelect(action) },
                label = { Text(action.name) },
            )
        }
    }
}

@Composable
private fun SecuritySettings(state: DocsUiState, viewModel: DocsViewModel) {
    val s = state.settings
    val context = LocalContext.current
    val biometricAuthenticators = BiometricManager.Authenticators.BIOMETRIC_STRONG
    val biometricVault = remember(context) { BiometricVaultKeyStore(context) }
    var vaultSecret by remember { mutableStateOf("") }
    var showPasswordField by remember { mutableStateOf(false) }
    var showAutoLockPicker by remember { mutableStateOf(false) }
    val biometricEnrollmentLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        val available = BiometricManager.from(context).canAuthenticate(biometricAuthenticators) == BiometricManager.BIOMETRIC_SUCCESS
        if (available) viewModel.showMessage("Biometric enrollment is ready. Tap Biometric unlock again to bind it to the Vault.")
        else viewModel.showMessage("Biometric enrollment is not complete yet")
    }
    val autoLockOptions = mapOf("Immediately" to 0, "After 1 minute" to 1, "After 5 minutes" to 5, "After 15 minutes" to 15)
    fun autoLockLabel(m: Int) = autoLockOptions.entries.firstOrNull { it.value == m }?.key ?: "After 5 minutes"
    if (showAutoLockPicker) OptionPickerDialog("Auto lock", autoLockOptions.keys.toList(), autoLockLabel(s.autoLockMinutes), { showAutoLockPicker = false }) { p -> viewModel.patchSettings { it.copy(autoLockMinutes = autoLockOptions[p] ?: 5) } }

    fun updateBiometric(enabled: Boolean) {
        if (!enabled) {
            biometricVault.clear()
            viewModel.setPrivacyOption(biometricOnOpen = false)
            return
        }
        if (!s.vaultLockEnabled) {
            showPasswordField = true
            viewModel.showMessage("Set up the Vault before enabling biometric unlock")
            return
        }
        when (BiometricManager.from(context).canAuthenticate(biometricAuthenticators)) {
            BiometricManager.BIOMETRIC_SUCCESS -> {
                val activity = context.findSettingsFragmentActivity()
                if (activity == null) {
                    viewModel.showMessage("Biometric setup is unavailable in this window")
                    return
                }
                val cryptoObject = runCatching { biometricVault.enrollmentCryptoObject() }.getOrElse {
                    viewModel.showMessage(it.localizedMessage ?: "Could not prepare biometric Vault protection")
                    return
                }
                val prompt = BiometricPrompt(
                    activity,
                    ContextCompat.getMainExecutor(activity),
                    object : BiometricPrompt.AuthenticationCallback() {
                        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                            if (biometricVault.completeEnrollment(result.cryptoObject)) {
                                viewModel.setPrivacyOption(biometricOnOpen = true)
                                viewModel.showMessage("Biometric Vault unlock enabled")
                            } else {
                                biometricVault.clear()
                                viewModel.setPrivacyOption(biometricOnOpen = false)
                                viewModel.showMessage("Biometric Vault setup could not be verified")
                            }
                        }

                        override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                            biometricVault.clear()
                            viewModel.setPrivacyOption(biometricOnOpen = false)
                            viewModel.showMessage(errString.toString())
                        }

                        override fun onAuthenticationFailed() {
                            viewModel.showMessage("Biometric check failed")
                        }
                    },
                )
                runCatching {
                    prompt.authenticate(
                        BiometricPrompt.PromptInfo.Builder()
                            .setTitle("Protect Norfold Vault")
                            .setSubtitle("Confirm your biometric to create a device-bound unlock key")
                            .setNegativeButtonText("Cancel")
                            .setAllowedAuthenticators(biometricAuthenticators)
                            .build(),
                        cryptoObject,
                    )
                }.onFailure {
                    biometricVault.clear()
                    viewModel.showMessage(it.localizedMessage ?: "Biometric setup failed")
                }
            }
            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> {
                val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    Intent(Settings.ACTION_BIOMETRIC_ENROLL).putExtra(
                        Settings.EXTRA_BIOMETRIC_AUTHENTICATORS_ALLOWED,
                        biometricAuthenticators,
                    )
                } else {
                    Intent(Settings.ACTION_SECURITY_SETTINGS)
                }
                runCatching { biometricEnrollmentLauncher.launch(intent) }
                    .onFailure { viewModel.showMessage("Open Android Security settings and enroll a fingerprint") }
            }
            BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE -> viewModel.showMessage("This device has no biometric hardware")
            BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE -> viewModel.showMessage("Biometric hardware is temporarily unavailable")
            else -> viewModel.showMessage("Biometric unlock is unavailable on this device")
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.fillMaxWidth()) {
        SettingsGroup(header = "Vault") {
            SettingsRow(
                "Vault status",
                subtitle = if (s.vaultLockEnabled) "Ready · tap to lock now" else "Not set up · tap to create",
                icon = Icons.Outlined.Lock,
                trailing = { RowChevron() },
                onClick = { if (s.vaultLockEnabled) viewModel.lock() else showPasswordField = true },
            )
            RowDivider()
            SettingsRow(if (s.vaultLockEnabled) "Change vault password" else "Create vault password", icon = Icons.Outlined.Security, trailing = { RowChevron() }, onClick = { showPasswordField = !showPasswordField })
            RowDivider()
            SettingsRow(
                "Biometric unlock",
                subtitle = if (s.vaultLockEnabled) "Use an enrolled fingerprint or face after Vault setup" else "Set up Vault first",
                icon = Icons.Outlined.PhoneAndroid,
                trailing = { RowSwitch(s.requireBiometricOnOpen, ::updateBiometric) },
                onClick = { updateBiometric(!s.requireBiometricOnOpen) },
            )
            RowDivider()
            SettingsRow("Auto lock", subtitle = autoLockLabel(s.autoLockMinutes), icon = Icons.Outlined.Lock, trailing = { RowChevron() }, onClick = { showAutoLockPicker = true })
        }
        if (showPasswordField) {
            SettingsGroup {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(vaultSecret, { vaultSecret = it }, label = { Text("PIN or password") }, supportingText = { Text("Use at least 6 characters. This protects locked Docs and encrypted backups.") }, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(), singleLine = true)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { viewModel.setVaultSecret(vaultSecret); if (vaultSecret.length >= 6) { vaultSecret = ""; showPasswordField = false } }, enabled = vaultSecret.length >= 6) { Text(if (s.vaultLockEnabled) "Update" else "Enable") }
                        if (s.vaultLockEnabled) ElevatedButton(onClick = viewModel::disableVault) { Text("Disable") }
                    }
                }
            }
        }
        SettingsGroup(header = "Security") {
            SettingsRow("Protect screenshots & recents", subtitle = "Block screen captures and hide the app preview", trailing = { RowSwitch(s.blockScreenshots) { viewModel.setPrivacyOption(blockScreenshots = it) } })
            RowDivider(inset = false)
            SettingsRow("App lock on exit", trailing = { RowSwitch(s.appLockOnExit) { v -> viewModel.patchSettings { it.copy(appLockOnExit = v) } } })
            RowDivider(inset = false)
            SettingsRow("Encryption", subtitle = "Local vault and sync payloads use device-backed protection", icon = Icons.Outlined.Settings, trailing = { RowValue("Local") })
        }
    }
}

private tailrec fun Context.findSettingsFragmentActivity(): FragmentActivity? = when (this) {
    is FragmentActivity -> this
    is ContextWrapper -> baseContext.findSettingsFragmentActivity()
    else -> null
}

@Composable
private fun SyncSettings(
    state: DocsUiState,
    viewModel: DocsViewModel,
    onPickSyncFolder: (SyncFolderRequest) -> Unit,
    onConnectGoogleDrive: () -> Unit,
    onDisconnectGoogleDrive: () -> Unit,
    onNavigate: (SettingsSection) -> Unit,
) {
    val capabilities = ExternalServiceConfig.capabilities
    Column(verticalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.fillMaxWidth()) {
        SettingsGroup(header = "Account") {
            SettingsRow(
                state.settings.syncUserName.ifBlank { "Local owner" },
                subtitle = if (capabilities.googleIdentity) "Google identity is ready" else "Local-first workspace · cloud account backend not configured",
                icon = Icons.Outlined.Person,
            )
            RowDivider()
            SettingsRow(
                "Google configuration",
                subtitle = if (capabilities.googleDrive) "Android client and Drive app-data project are configured" else "Add the Android OAuth client and Google project ID",
                icon = if (capabilities.googleDrive) Icons.Outlined.Check else Icons.Outlined.CloudOff,
                trailing = { RowValue(if (capabilities.googleDrive) "Ready" else "Needs setup", accent = capabilities.googleDrive) },
            )
        }
        ManualSyncCard(state, viewModel, onPickSyncFolder, onConnectGoogleDrive, onDisconnectGoogleDrive)
        SettingsGroup(header = "Sync") {
            SettingsRow(
                "Last synced",
                subtitle = state.settings.lastSyncAt?.let { relativeSettingsTime(it) + " ago" } ?: "Never",
                icon = Icons.Outlined.CloudSync,
            )
            RowDivider()
            SettingsRow(
                "Open Sync Settings",
                subtitle = "Provider status, Sync now, cooldown and background behavior",
                icon = Icons.Outlined.CloudSync,
                trailing = { RowChevron() },
                onClick = { onNavigate(SettingsSection.SyncEngine) },
            )
        }
        SettingsGroup(header = "Manage") {
            SettingsRow("Sync behavior", subtitle = "Interval, network and battery rules", icon = Icons.Outlined.CloudSync, trailing = { RowChevron() }, onClick = { onNavigate(SettingsSection.SyncEngine) })
            RowDivider()
            SettingsRow("Backup & Import", subtitle = "Encrypted exports, restore and Markdown", icon = Icons.Outlined.Backup, trailing = { RowChevron() }, onClick = { onNavigate(SettingsSection.Backup) })
            RowDivider()
            SettingsRow("Conflict Resolution", subtitle = "Defaults, reports and keep-both behavior", icon = Icons.Outlined.Difference, trailing = { RowChevron() }, onClick = { onNavigate(SettingsSection.Conflicts) })
        }
    }
}

private fun relativeSyncStatus(state: DocsUiState): String =
    state.settings.lastSyncAt?.let { relativeSettingsTime(it) + " ago" } ?: "Just now"

@Composable
private fun ManualSyncCard(
    state: DocsUiState,
    viewModel: DocsViewModel,
    onPickSyncFolder: (SyncFolderRequest) -> Unit,
    onConnectGoogleDrive: () -> Unit,
    onDisconnectGoogleDrive: () -> Unit,
) = SettingsCard {
    val googleDriveEnabled = ExternalServiceConfig.capabilities.googleDrive
    var syncSecret by remember { mutableStateOf("") }
    var syncUsername by remember(state.settings.syncUserName) { mutableStateOf(state.settings.syncUserName.ifBlank { "owner" }) }
    var syncPublicName by remember(state.settings.syncPublicName) { mutableStateOf(state.settings.syncPublicName) }
    var syncDeviceName by remember(state.settings.syncDeviceName) { mutableStateOf(state.settings.syncDeviceName) }
    var syncProvider by remember(state.settings.syncProvider, googleDriveEnabled) {
        mutableStateOf(
            when {
                state.settings.syncProvider != SyncProvider.None -> state.settings.syncProvider
                googleDriveEnabled -> SyncProvider.GoogleDrive
                else -> SyncProvider.LocalFolder
            },
        )
    }
    Text("Status: ${state.settings.lastSyncStatus}", color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (googleDriveEnabled) {
        Text("Google Drive app-data sync", fontWeight = FontWeight.Bold)
        Text(
            if (state.googleDriveConnected) "Connected. Norfold stores encrypted sync snapshots in hidden Google Drive app data, not in your visible Drive files."
            else "Continue with Google to create or restore an encrypted sync chain after reinstalling or changing devices.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 12.sp,
        )
        Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Button(onClick = onConnectGoogleDrive, enabled = !state.syncing, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Outlined.CloudSync, null)
                Spacer(Modifier.size(8.dp))
                Text(if (state.googleDriveConnected) "Reconnect Google" else "Continue with Google")
            }
            ElevatedButton(onClick = onDisconnectGoogleDrive, enabled = state.googleDriveConnected && !state.syncing, modifier = Modifier.fillMaxWidth()) {
                Text("Disconnect and revoke Drive access")
            }
        }
        Text(
            "Identity and Drive authorization are separate. Drive data stays in appDataFolder; manual providers below use a user-selected folder.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 12.sp,
        )
        Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Button(
                modifier = Modifier.fillMaxWidth(),
                enabled = state.googleDriveConnected && syncSecret.isNotBlank() && !state.syncing,
                onClick = { viewModel.createGoogleDriveApiChain(syncSecret, syncUsername, syncPublicName, syncDeviceName) },
            ) { Text("Create Google sync chain") }
            ElevatedButton(
                modifier = Modifier.fillMaxWidth(),
                enabled = state.googleDriveConnected && syncSecret.isNotBlank() && !state.syncing,
                onClick = { viewModel.restoreGoogleDriveApiChain(syncSecret, syncUsername, syncPublicName, syncDeviceName) },
            ) { Text("Restore Google sync chain") }
        }
    }
    OutlinedTextField(syncSecret, { syncSecret = it }, label = { Text("Sync key") }, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(), singleLine = true)
    OutlinedTextField(syncUsername, { syncUsername = it }, label = { Text("Username") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
    OutlinedTextField(syncPublicName, { syncPublicName = it }, label = { Text("Public name") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
    OutlinedTextField(syncDeviceName, { syncDeviceName = it }, label = { Text("Device name") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
    Text("Advanced manual sync", fontWeight = FontWeight.Bold)
    Text("Use this only when you want Norfold to write encrypted sync files into a folder you choose yourself.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
        if (googleDriveEnabled) FilterChip(syncProvider == SyncProvider.GoogleDrive, { syncProvider = SyncProvider.GoogleDrive }, label = { Text("Google folder") })
        FilterChip(syncProvider == SyncProvider.OneDrive, { syncProvider = SyncProvider.OneDrive }, label = { Text("OneDrive folder") })
        FilterChip(syncProvider == SyncProvider.LocalFolder, { syncProvider = SyncProvider.LocalFolder }, label = { Text("Local folder") })
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        Button(modifier = Modifier.fillMaxWidth(), enabled = syncSecret.isNotBlank() && !state.syncing, onClick = { onPickSyncFolder(SyncFolderRequest(syncProvider, SyncFolderAction.CreateChain, syncSecret, syncUsername, syncPublicName, syncDeviceName)) }) { Text("Create manual chain") }
        ElevatedButton(modifier = Modifier.fillMaxWidth(), enabled = syncSecret.isNotBlank() && !state.syncing, onClick = { onPickSyncFolder(SyncFolderRequest(syncProvider, SyncFolderAction.RestoreChain, syncSecret, syncUsername, syncPublicName, syncDeviceName)) }) { Text("Restore manual chain") }
    }
    ElevatedButton(modifier = Modifier.fillMaxWidth(), onClick = { viewModel.go(Destination.ConflictReview) }) { Icon(Icons.Outlined.Difference, null); Text("Open conflicts") }
}

// ---------- Mockup 13: Sync Settings ----------
@Composable
private fun SyncEngineSettings(state: DocsUiState, viewModel: DocsViewModel, onSetUpSync: () -> Unit) {
    val s = state.settings
    var cooldownSeconds by remember { mutableStateOf(viewModel.syncCooldownRemainingSeconds()) }
    LaunchedEffect(state.syncing, state.message, s.lastSyncAt) {
        do {
            cooldownSeconds = viewModel.syncCooldownRemainingSeconds()
            if (cooldownSeconds > 0L) delay(250L)
        } while (cooldownSeconds > 0L)
    }

    Column(verticalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.fillMaxWidth()) {
        SettingsGroup(header = "Sync status") {
            SettingsRow(
                "Provider",
                subtitle = when (s.syncProvider) {
                    SyncProvider.GoogleDrive -> "Encrypted Google Drive app-data chain"
                    SyncProvider.OneDrive -> "Encrypted OneDrive folder chain"
                    SyncProvider.LocalFolder -> "Encrypted local folder chain"
                    SyncProvider.None -> "Create or restore a chain in Account & Restore"
                },
                icon = Icons.Outlined.CloudSync,
                trailing = { RowValue(if (s.syncProvider == SyncProvider.None) "Not configured" else s.syncProvider.name) },
            )
            RowDivider()
            SettingsRow(
                "Last synced",
                subtitle = s.lastSyncAt?.let { relativeSettingsTime(it) + " ago" } ?: "Never",
                trailing = { RowValue(if (state.syncing) "Syncing…" else s.lastSyncStatus.take(28)) },
            )
            RowDivider()
            Button(
                onClick = viewModel::syncConfiguredNow,
                enabled = !state.syncing && cooldownSeconds == 0L && s.syncProvider != SyncProvider.None,
                modifier = Modifier.fillMaxWidth().padding(12.dp),
            ) {
                Icon(Icons.Outlined.CloudSync, null)
                Spacer(Modifier.size(8.dp))
                Text(
                    when {
                        state.syncing -> "Syncing…"
                        cooldownSeconds > 0L -> "Sync again in ${cooldownSeconds}s"
                        else -> "Sync now"
                    },
                )
            }
            if (s.syncProvider == SyncProvider.None) {
                ElevatedButton(
                    onClick = onSetUpSync,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                ) { Text("Set up sync") }
            }
        }
        SettingsGroup(header = "Sync behavior") {
            SettingsRow("Sync when leaving the app", subtitle = "Runs only after a chain is unlocked in this session", trailing = { RowSwitch(s.autoSync) { v -> viewModel.patchSettings { it.copy(autoSync = v) } } })
            RowDivider(inset = false)
            SettingsRow("Background scheduling", subtitle = "Not enabled in this pre-beta build", trailing = { RowValue("Off") })
        }
        SettingsGroup(header = "Advanced") {
            SettingsRow("Rebuild local index", subtitle = "Re-index all content", icon = Icons.Outlined.Settings, trailing = { RowChevron() }, onClick = { viewModel.go(Destination.SyncMonitor) })
        }
    }
}

// ---------- Mockup 12: Permissions (per-workspace) ----------
@Composable
private fun PermissionsSettings(state: DocsUiState, viewModel: DocsViewModel) {
    val ws = state.workspaces.firstOrNull { it.id == state.settings.activeWorkspaceId }
    Column(verticalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.fillMaxWidth()) {
        Text("Manage who can do what in this workspace.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, modifier = Modifier.padding(start = 6.dp))
        if (ws == null) {
            SettingsGroup { SettingsRow("No active workspace", icon = Icons.Outlined.Workspaces) }
        } else {
            SettingsGroup {
                SettingsRow("Workspace owner", subtitle = "Only owner can change", icon = Icons.Outlined.Person, trailing = { RowValue("You") })
                RowDivider()
                SettingsRow("Rename workspace", subtitle = "Members", icon = Icons.Outlined.Workspaces, trailing = { RowSwitch(ws.permRename) { v -> viewModel.patchActiveWorkspacePermissions { it.copy(permRename = v) } } })
                RowDivider()
                SettingsRow("Change icon & background", subtitle = "Members", icon = Icons.Outlined.Palette, trailing = { RowSwitch(ws.permChangeIcon) { v -> viewModel.patchActiveWorkspacePermissions { it.copy(permChangeIcon = v) } } })
                RowDivider()
                SettingsRow("Invite members", subtitle = "Members", icon = Icons.Outlined.Person, trailing = { RowSwitch(ws.permInviteMembers) { v -> viewModel.patchActiveWorkspacePermissions { it.copy(permInviteMembers = v) } } })
                RowDivider()
                SettingsRow("Delete docs", subtitle = "Members", icon = Icons.Outlined.Difference, trailing = { RowSwitch(ws.permDeleteNotes) { v -> viewModel.patchActiveWorkspacePermissions { it.copy(permDeleteNotes = v) } } })
                RowDivider()
                SettingsRow("Edit docs", subtitle = "Members", icon = Icons.Outlined.Code, trailing = { RowSwitch(ws.permEditNotes) { v -> viewModel.patchActiveWorkspacePermissions { it.copy(permEditNotes = v) } } })
                RowDivider()
                RowDivider()
                SettingsRow("Manage tasks", subtitle = "Members", icon = Icons.Outlined.Check, trailing = { RowSwitch(ws.permManageTasks) { v -> viewModel.patchActiveWorkspacePermissions { it.copy(permManageTasks = v) } } })
            }
            ElevatedButton(
                onClick = {
                    viewModel.patchActiveWorkspacePermissions {
                        it.copy(permRename = false, permChangeIcon = false, permInviteMembers = false, permDeleteNotes = false, permEditNotes = true, permManageTasks = true)
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Reset to default") }
        }
    }
}

// ---------- Mockup 14: Conflict Resolution ----------
@Composable
private fun ConflictSettings(state: DocsUiState, viewModel: DocsViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.fillMaxWidth()) {
        SettingsGroup {
            SettingsRow("Current behavior", subtitle = "Conflicts stop sync and require review", icon = Icons.Outlined.Security, trailing = { RowValue("Ask") })
            RowDivider()
            SettingsRow("Available resolution", subtitle = "Use the local snapshot on the next sync", icon = Icons.Outlined.Check, trailing = { RowValue("Local") })
        }
        SettingsGroup {
            SettingsRow("Conflict records", subtitle = "View history of resolved conflicts", icon = Icons.Outlined.Difference, trailing = { RowChevron() }, onClick = { viewModel.go(Destination.ConflictReview) })
        }
        ElevatedButton(onClick = { viewModel.go(Destination.ConflictReview) }, modifier = Modifier.fillMaxWidth()) { Text("Open detailed conflict report") }
    }
}

@Composable
private fun BackupImportSettings(
    state: DocsUiState,
    viewModel: DocsViewModel,
    onPickMarkdown: () -> Unit,
    onPickBackupFolder: () -> Unit,
    onPickBackupFile: (String) -> Unit,
) {
    val s = state.settings
    var showAdvanced by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.fillMaxWidth()) {
        SettingsGroup(header = "Backup & Import") {
            SettingsRow("Create encrypted backup", subtitle = "Manual backup to device", icon = Icons.Outlined.Backup, trailing = { RowChevron() }, onClick = { showAdvanced = true })
            RowDivider()
            SettingsRow("Backup location", subtitle = if (s.backupFolderUri.isNullOrBlank()) "Choose a folder for encrypted .enc files" else "Custom folder authorized", icon = Icons.Outlined.PhoneAndroid, trailing = { RowChevron() }, onClick = onPickBackupFolder)
        }
        SettingsGroup {
            SettingsRow("Import from Markdown", subtitle = ".md files and folders", icon = Icons.Outlined.ImportExport, trailing = { RowChevron() }, onClick = onPickMarkdown)
            RowDivider()
            SettingsRow("Import from ZIP", subtitle = "Norfold or other apps export", icon = Icons.Outlined.FileUpload, trailing = { RowChevron() }, onClick = { showAdvanced = true })
        }
        if (showAdvanced) BackupAdvancedCard(state, viewModel, onPickMarkdown, onPickBackupFolder, onPickBackupFile)
    }
}

@Composable
private fun BackupAdvancedCard(
    state: DocsUiState,
    viewModel: DocsViewModel,
    onPickMarkdown: () -> Unit,
    onPickBackupFolder: () -> Unit,
    onPickBackupFile: (String) -> Unit,
) = SettingsCard {
    var backupSecret by remember { mutableStateOf("") }
    var backupPayload by remember { mutableStateOf("") }
    Text("Backup folder", fontWeight = FontWeight.Bold)
    Text(
        if (state.settings.backupFolderUri.isNullOrBlank()) "No folder selected. Exports stay in the encrypted payload field."
        else "Selected folder is authorized. Exports also write encrypted .enc files.",
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontSize = 12.sp,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        Button(onClick = onPickBackupFolder) {
            Icon(Icons.Outlined.PhoneAndroid, null)
            Text(if (state.settings.backupFolderUri.isNullOrBlank()) "Choose folder" else "Change folder")
        }
        ElevatedButton(
            enabled = !state.settings.backupFolderUri.isNullOrBlank(),
            onClick = { viewModel.setBackupFolder("") },
        ) { Text("Clear") }
    }
    OutlinedTextField(backupSecret, { backupSecret = it }, label = { Text("Backup password") }, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(), singleLine = true)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = { viewModel.exportBackup(backupSecret) { backupPayload = it } }) { Icon(Icons.Outlined.FileDownload, null); Text("Export") }
        ElevatedButton(onClick = { viewModel.importBackup(backupSecret, backupPayload) }) { Icon(Icons.Outlined.FileUpload, null); Text("Import") }
    }
    ElevatedButton(
        enabled = backupSecret.isNotBlank(),
        onClick = { onPickBackupFile(backupSecret) },
    ) {
        Icon(Icons.Outlined.FileUpload, null)
        Text("Import .enc file")
    }
    Button(onClick = onPickMarkdown) { Icon(Icons.Outlined.ImportExport, null); Text("Import Markdown") }
    OutlinedTextField(backupPayload, { backupPayload = it }, label = { Text("Encrypted backup payload") }, modifier = Modifier.fillMaxWidth(), minLines = 4)
}

@Composable
private fun DiagnosticsSettings(state: DocsUiState, viewModel: DocsViewModel) = SettingsCard {
    val diagnostics = state.diagnostics
    Text("Local diagnostics", fontWeight = FontWeight.Black, fontSize = 18.sp)
    Text(
        "Logs stay on this device unless you share them. No remote analytics service is used, and repeated prompts are suppressed after a crash is handled.",
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontSize = 12.sp,
    )
    SettingSwitch("Enable local logging", diagnostics.enabled) { viewModel.setDiagnosticsOptions(enabled = it) }
    SettingSwitch("Include device model and Android version", diagnostics.includeDeviceInfo) { viewModel.setDiagnosticsOptions(includeDeviceInfo = it) }
    SettingSwitch("Ask before sharing after a crash", diagnostics.askBeforeSharing) { viewModel.setDiagnosticsOptions(askBeforeSharing = it) }
    HorizontalDivider()
    Text("Last crash", fontWeight = FontWeight.Bold)
    Text(
        diagnostics.lastCrashSummary ?: "No crash recorded.",
        color = if (diagnostics.lastCrashSummary == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
        fontSize = 12.sp,
    )
    diagnostics.lastCrashAt?.let {
        Text("Recorded ${relativeSettingsTime(it)} ago", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
    }
    Text("Log size: ${formatDiagnosticsBytes(diagnostics.logBytes)} · Shared reports include app version and recent local log lines.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
    if (diagnostics.pendingCrashPrompt) {
        Card(shape = RoundedCornerShape(16.dp)) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Norfold detected a previous crash.", fontWeight = FontWeight.Bold)
                Text("Share diagnostics if you want to inspect or send the local crash context.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    Button(onClick = viewModel::shareDiagnostics, modifier = Modifier.weight(1f)) { Text("Share") }
                    ElevatedButton(onClick = viewModel::acknowledgeCrashPrompt, modifier = Modifier.weight(1f)) { Text("Dismiss") }
                }
            }
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        Button(onClick = viewModel::shareDiagnostics, modifier = Modifier.weight(1f)) { Text("Share logs") }
        ElevatedButton(onClick = viewModel::clearDiagnostics, modifier = Modifier.weight(1f)) { Text("Clear logs") }
    }
}

@Composable
private fun AppInfoSettings() {
    var showChangelog by remember { mutableStateOf(false) }
    val uriHandler = LocalUriHandler.current
    val context = androidx.compose.ui.platform.LocalContext.current
    val changelog = remember(context) {
        context.resources.openRawResource(com.norfold.app.R.raw.changelog).bufferedReader().use { it.readText() }
    }
    if (showChangelog) {
        NorfoldDialog(
            onDismissRequest = { showChangelog = false },
            title = { Text("Changelog") },
            text = { Text(changelog, Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) },
            confirmButton = { TextButton(onClick = { showChangelog = false }) { Text("Close") } },
        )
    }
    Column(verticalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.fillMaxWidth()) {
        // Header card with logo + name + version
        SettingsGroup {
            Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Box(Modifier.size(56.dp).clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
                    NorfoldLogo(48.dp)
                }
                Column(Modifier.weight(1f)) {
                    Text("Norfold", fontWeight = FontWeight.Black, fontSize = 20.sp)
                    Text("Version ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                    Text("Your private workspace.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                }
            }
        }
        SettingsGroup {
            SettingsRow("Changelog", icon = Icons.Outlined.Difference, trailing = { RowChevron() }, onClick = { showChangelog = true })
            RowDivider()
            SettingsRow("Help & Support", icon = Icons.Outlined.Security, trailing = { RowChevron() }, onClick = { uriHandler.openUri("https://github.com/sheikhti1205/Norfold/issues") })
            RowDivider()
            SettingsRow("Privacy policy", icon = Icons.Outlined.Lock, trailing = { RowChevron() }, onClick = { uriHandler.openUri("https://sheikhti1205.github.io/Norfold/privacy.html") })
            RowDivider()
            SettingsRow("Terms of service", icon = Icons.Outlined.Difference, trailing = { RowChevron() }, onClick = { uriHandler.openUri("https://sheikhti1205.github.io/Norfold/terms.html") })
            RowDivider()
            SettingsRow("Open source licenses", icon = Icons.Outlined.Code, trailing = { RowChevron() }, onClick = { uriHandler.openUri("https://github.com/sheikhti1205/Norfold") })
        }
        Text("Application ID com.norfold.app · Open-source, Android-first.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp, modifier = Modifier.padding(start = 6.dp))
    }
}

@Composable
private fun BuiltInSettingsCoverStrip(selectedUri: String, onSelect: (String) -> Unit) {
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
                    .size(width = 104.dp, height = 58.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .border(
                        width = if (selected) 2.dp else 1.dp,
                        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.22f),
                        shape = RoundedCornerShape(14.dp),
                    )
                    .clickable { onSelect(cover.uri) },
            ) {
                Image(painterResource(cover.drawableRes), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background.copy(alpha = 0.18f)))
                Text(
                    cover.title,
                    Modifier.align(Alignment.BottomStart).padding(7.dp),
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 10.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private fun relativeSettingsTime(timestamp: Long): String {
    val minutes = ((System.currentTimeMillis() - timestamp).coerceAtLeast(0) / 60_000).toInt()
    return when {
        minutes < 1 -> "just now"
        minutes < 60 -> "${minutes}m"
        minutes < 1440 -> "${minutes / 60}h"
        else -> "${minutes / 1440}d"
    }
}

private fun formatDiagnosticsBytes(bytes: Long): String = when {
    bytes <= 0 -> "0 B"
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    else -> "${bytes / (1024 * 1024)} MB"
}

@Composable
private fun PaletteGrid(selected: ThemeProfile, onSelect: (ThemeProfile) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        ThemeProfile.entries.toList().chunked(4).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { profile ->
                    val p = profile.palette()
                    val isSel = profile == selected
                    Box(
                        Modifier.weight(1f).aspectRatio(1f).clip(RoundedCornerShape(14.dp))
                            .background(Brush.linearGradient(listOf(p.c1, p.c2, p.c3)))
                            .border(if (isSel) 3.dp else 0.dp, if (isSel) MaterialTheme.colorScheme.onSurface else Color.Transparent, RoundedCornerShape(14.dp))
                            .clickable { onSelect(profile) },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (isSel) Box(Modifier.size(24.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.28f)), contentAlignment = Alignment.Center) {
                            Icon(Icons.Outlined.Check, null, tint = Color.White, modifier = Modifier.size(16.dp))
                        }
                    }
                }
                repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun SettingSwitch(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, modifier = Modifier.weight(1f))
        Switch(checked, onChange)
    }
}

@Composable
private fun SettingsCard(content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}
