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
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AccessibilityNew
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
import androidx.compose.material.icons.outlined.FormatBold
import androidx.compose.material.icons.outlined.ImportExport
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.CreditCard
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Phone
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Workspaces
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import com.norfold.app.ui.components.WorkspaceIconBadge
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
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.KeyboardType
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
import com.norfold.app.domain.WorkspaceIconKind
import com.norfold.app.domain.Destination
import com.norfold.app.domain.AppIconFamily
import com.norfold.app.domain.ControlLabelMode
import com.norfold.app.domain.EditorIconFamily
import com.norfold.app.domain.EditorFontFamily
import com.norfold.app.domain.EditorLineWidth
import com.norfold.app.editor.style.ContentRole
import com.norfold.app.editor.style.ReadingScale
import com.norfold.app.editor.style.contentCharacters
import com.norfold.app.domain.DeviceSettingsProfile
import com.norfold.app.domain.SettingsProfileCategory
import com.norfold.app.domain.NoteGestureAction
import com.norfold.app.domain.TaskGestureAction
import com.norfold.app.domain.NoteRenderEngine
import com.norfold.app.domain.SemanticActionId
import com.norfold.app.domain.SyncFolderAction
import com.norfold.app.domain.SyncFolderRequest
import com.norfold.app.domain.SyncProvider
import com.norfold.app.domain.ThemeMode
import com.norfold.app.domain.ThemeProfile
import com.norfold.app.cloud.ExternalServiceConfig
import com.norfold.app.data.BiometricVaultKeyStore
import com.norfold.app.ui.DocsUiState
import com.norfold.app.ui.DocsViewModel
import com.norfold.app.ui.EmailAuthPhase
import com.norfold.app.ui.EmailAuthOperation
import com.norfold.app.ui.ScaleSetting
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
import com.norfold.app.ui.components.SemanticEditorIcon
import com.norfold.app.ui.components.SemanticFamilyIcon
import com.norfold.app.ui.components.NorfoldIconFamilies
import com.norfold.app.ui.components.CustomIconPackStore
import com.norfold.app.ui.components.displayName
import com.norfold.app.ui.fonts.FontCatalogPickerSheet
import com.norfold.app.ui.fonts.FontCatalogSnapshot
import com.norfold.app.ui.fonts.FontCatalogStore
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

private enum class SettingsSection(val title: String, val subtitle: String, val icon: ImageVector) {
    Profile("Profile", "Username, public name and device", Icons.Outlined.Person),
    Workspace("Workspace", "Name, visuals and member defaults", Icons.Outlined.Workspaces),
    Appearance("Appearance", "Theme, palette, density and type", Icons.Outlined.Palette),
    Accessibility("Accessibility", "Labels, readable controls and assistive guidance", Icons.Outlined.AccessibilityNew),
    Editor("Editor & Markdown", "Line width, syntax and writing behavior", Icons.Outlined.Code),
    Security("Security & Vault", "Vault, biometrics and screenshots", Icons.Outlined.Security),
    Sync("Account & Sync", "Norfold Cloud account and sync status", Icons.Outlined.CloudSync),
    Backup("Backup & Import", "Encrypted exports, restore and Markdown import", Icons.Outlined.Backup),
    App("App Info", "Version, identity and build details", Icons.Outlined.Settings),
    SyncEngine("Sync Settings", "Provider status, Sync now and background behavior", Icons.Outlined.CloudSync),
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
    SettingsSection.Accessibility,
    SettingsSection.Editor,
    SettingsSection.Security,
    SettingsSection.Sync,
    SettingsSection.Backup,
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
    onCloudGoogleSignIn: () -> Unit,
) {
    var section by remember { mutableStateOf<SettingsSection?>(null) }
    var showWorkspaceDialog by remember { mutableStateOf(false) }
    var showLeavePreviewDialog by remember { mutableStateOf(false) }
    var workspaceDialogTab by remember { mutableStateOf(WorkspaceVisualTab.Identity) }
    val scalePreview by viewModel.scalePreview.collectAsState()
    val renderedState = scalePreview?.let { state.copy(settings = it.applyTo(state.settings)) } ?: state
    if (showWorkspaceDialog) WorkspaceVisualDialog(state, viewModel, workspaceDialogTab) { showWorkspaceDialog = false }
    if (showLeavePreviewDialog) {
        NorfoldDialog(
            onDismissRequest = { showLeavePreviewDialog = false },
            title = { Text("Unsaved appearance preview") },
            text = { Text("Revert the temporary scale changes before leaving, or continue previewing them.") },
            confirmButton = {
                Button(onClick = {
                    viewModel.revertScalePreview()
                    showLeavePreviewDialog = false
                    section = null
                }) { Text("Revert and leave") }
            },
            dismissButton = { TextButton(onClick = { showLeavePreviewDialog = false }) { Text("Continue preview") } },
        )
    }

    BackHandler(enabled = section != null || showWorkspaceDialog || showLeavePreviewDialog) {
        when {
            showLeavePreviewDialog -> showLeavePreviewDialog = false
            showWorkspaceDialog -> showWorkspaceDialog = false
            scalePreview != null && section in setOf(SettingsSection.Appearance, SettingsSection.Editor) -> showLeavePreviewDialog = true
            else -> section = null
        }
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
                    IconButton(onClick = {
                        if (scalePreview != null && section in setOf(SettingsSection.Appearance, SettingsSection.Editor)) showLeavePreviewDialog = true
                        else section = null
                    }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, null) }
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
                    SettingsSection.Appearance -> AppearanceSettings(renderedState, viewModel)
                    SettingsSection.Accessibility -> AccessibilitySettings(renderedState, viewModel)
                    SettingsSection.Editor -> EditorSettings(renderedState, viewModel)
                    SettingsSection.Security -> SecuritySettings(state, viewModel)
                    SettingsSection.Sync -> SyncSettings(state, viewModel, onPickSyncFolder, onConnectGoogleDrive, onDisconnectGoogleDrive, onCloudGoogleSignIn) { section = it }
                    SettingsSection.Backup -> BackupImportSettings(state, viewModel, onPickMarkdown, onPickBackupFolder, onPickBackupFile)
                    SettingsSection.SyncEngine -> SyncEngineSettings(state, viewModel) { section = SettingsSection.Sync }
                    SettingsSection.Conflicts -> ConflictSettings(state, viewModel)
                    SettingsSection.Permissions -> PermissionsSettings(state, viewModel)
                    SettingsSection.WorkspaceMembers -> WorkspaceMembersSettings(state, viewModel)
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
        val privacy = listOf(SettingsSection.Accessibility, SettingsSection.Security)
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
            SettingsSectionGroup("Backup & Data", listOf(SettingsSection.Sync, SettingsSection.Backup), onOpen)
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
    val accountEmail by viewModel.cloudAccountEmail.collectAsState()
    val verifiedPhone by viewModel.verifiedPhone.collectAsState()
    val phoneVerificationInProgress by viewModel.phoneVerificationInProgress.collectAsState()
    // Ask Supabase for the current status on entry — the deep-link return never implies verified.
    LaunchedEffect(Unit) { viewModel.refreshPhoneVerification() }
    // null = no dialog; otherwise which field is being edited
    var editing by remember { mutableStateOf<String?>(null) }
    val profileImagePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        viewModel.updateProfileVisuals(uri?.toString(), state.settings.profileBackgroundUri)
    }
    val profileCoverPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        viewModel.updateProfileVisuals(state.settings.profileImageUri, uri?.toString())
    }

    when (editing) {
        "username" -> UsernameEditDialog(username, viewModel, onDismiss = { editing = null }) { viewModel.updateProfile(it, publicName, deviceName) }
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
        SettingsGroup(header = "Phone verification") {
            val phone = verifiedPhone
            if (phone != null) {
                SettingsRow(
                    "Phone number",
                    subtitle = maskPhoneNumber(phone),
                    icon = Icons.Outlined.Phone,
                    trailing = { RowValue("Verified", accent = true) },
                )
            } else {
                val canVerify = accountEmail != null && !phoneVerificationInProgress
                SettingsRow(
                    "Verify with Telegram",
                    subtitle = when {
                        accountEmail == null -> "Sign in first"
                        phoneVerificationInProgress -> "Opening Telegram…"
                        else -> "Confirm your number via @Norfold_auth_bot"
                    },
                    icon = Icons.Outlined.Phone,
                    trailing = { RowChevron() },
                    onClick = if (canVerify) ({ viewModel.startTelegramVerification() }) else null,
                )
            }
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

/** Show only the last 4 digits of a verified E.164 number: +1••••••1234. */
private fun maskPhoneNumber(e164: String): String {
    val digits = e164.filter { it.isDigit() }
    if (digits.length <= 4) return e164
    val last4 = digits.takeLast(4)
    val hiddenCount = digits.length - 4
    val prefix = if (e164.startsWith("+")) "+" else ""
    return prefix + "•".repeat(hiddenCount) + last4
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
            iconKind = state.settings.workspaceIconKind,
            iconUri = state.settings.workspaceIconUri,
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
private fun WorkspaceMembersSettings(state: DocsUiState, viewModel: DocsViewModel) {
    val remoteWorkspaceId by viewModel.teamWorkspaceRemoteId.collectAsState()
    val collaborationStatus by viewModel.teamCollaborationStatus.collectAsState()
    val accountEmail by viewModel.cloudAccountEmail.collectAsState()
    val inviteShare by viewModel.teamInviteShare.collectAsState()
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    var showInvite by remember { mutableStateOf(false) }
    var showJoin by remember { mutableStateOf(false) }
    var inviteEmail by remember { mutableStateOf("") }
    var inviteAsViewer by remember { mutableStateOf(false) }
    var inviteToken by remember { mutableStateOf("") }

    if (showInvite) {
        NorfoldDialog(
            onDismissRequest = { showInvite = false },
            title = { Text("Invite a member") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = inviteEmail,
                        onValueChange = { inviteEmail = it },
                        label = { Text("Email") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = inviteAsViewer, onCheckedChange = { inviteAsViewer = it })
                        Text("Viewer only")
                    }
                    Text("The invitation is bound to this email and expires after seven days.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                }
            },
            confirmButton = {
                Button(onClick = {
                    viewModel.createTeamInvitation(inviteEmail, inviteAsViewer)
                    showInvite = false
                }) { Text("Create invite") }
            },
            dismissButton = { TextButton(onClick = { showInvite = false }) { Text("Cancel") } },
        )
    }
    if (showJoin) {
        NorfoldDialog(
            onDismissRequest = { showJoin = false },
            title = { Text("Join a team workspace") },
            text = {
                OutlinedTextField(
                    value = inviteToken,
                    onValueChange = { inviteToken = it },
                    label = { Text("Invitation code") },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                Button(onClick = { viewModel.acceptTeamInvitation(inviteToken); showJoin = false }) { Text("Join") }
            },
            dismissButton = { TextButton(onClick = { showJoin = false }) { Text("Cancel") } },
        )
    }
    if (inviteShare != null) {
        val invite = inviteShare!!
        val shareText = "Norfold team invitation for ${invite.email}\n\nInvitation code:\n${invite.token}\n\nSign in with ${invite.email}, then open Settings → Workspace → Members → Join a team workspace."
        NorfoldDialog(
            onDismissRequest = viewModel::clearTeamInviteShare,
            title = { Text("Invitation ready") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("For ${invite.email}", fontWeight = FontWeight.Bold)
                    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                        Text(invite.token, Modifier.fillMaxWidth().padding(12.dp), fontSize = 12.sp)
                    }
                    Text("Only that account can accept this code. It expires in seven days.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                }
            },
            confirmButton = {
                Button(onClick = {
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_EMAIL, arrayOf(invite.email))
                        putExtra(Intent.EXTRA_SUBJECT, "Join my Norfold workspace")
                        putExtra(Intent.EXTRA_TEXT, shareText)
                    }
                    context.startActivity(Intent.createChooser(send, "Send Norfold invitation"))
                }) { Text("Send") }
            },
            dismissButton = {
                TextButton(onClick = {
                    clipboard.setText(AnnotatedString(invite.token))
                    viewModel.showMessage("Invitation code copied")
                }) { Text("Copy code") }
            },
        )
    }

    Column(verticalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.fillMaxWidth()) {
        SettingsGroup(header = "Members") {
            SettingsRow(
                title = state.settings.syncPublicName.ifBlank { state.settings.syncUserName.ifBlank { "Local owner" } },
                subtitle = accountEmail?.let { "Owner · $it" } ?: "Owner · This device",
                icon = Icons.Outlined.Person,
                trailing = { RowValue("Owner", accent = true) },
            )
        }
        SettingsGroup(header = "Team collaboration") {
            SettingsRow(
                title = if (remoteWorkspaceId == null) "Local workspace" else "Connected team workspace",
                subtitle = collaborationStatus,
                icon = if (remoteWorkspaceId == null) Icons.Outlined.CloudOff else Icons.Outlined.Groups,
                trailing = { RowValue(if (remoteWorkspaceId == null) "Local" else "Team", accent = remoteWorkspaceId != null) },
                onClick = if (remoteWorkspaceId != null) ({ viewModel.refreshTeamChat() }) else null,
            )
            RowDivider()
            if (remoteWorkspaceId == null) {
                Button(onClick = viewModel::enableTeamCollaboration, enabled = accountEmail != null, modifier = Modifier.fillMaxWidth().padding(12.dp)) {
                    Text(if (accountEmail == null) "Sign in first" else "Enable team collaboration")
                }
            } else {
                SettingsRow("Invite member", subtitle = "Create a seven-day email-bound invitation", icon = Icons.Outlined.Add, trailing = { RowChevron() }, onClick = { showInvite = true })
            }
            RowDivider()
            SettingsRow("Join a team workspace", subtitle = "Use an invitation code sent to this account", icon = Icons.Outlined.Groups, trailing = { RowChevron() }, onClick = { showJoin = true })
        }
    }
}

@Composable
private fun WorkspaceAdvancedSettings(state: DocsUiState, viewModel: DocsViewModel, onOpenExport: () -> Unit) {
    var confirmDelete by remember { mutableStateOf(false) }
    val active = state.workspaces.firstOrNull { it.id == state.settings.activeWorkspaceId }
    if (confirmDelete && active != null) {
        // The widest destructive operation in the app: it empties every table this workspace owns and
        // there is no undo. So the consent names what is lost and asks for the workspace name back,
        // which a muscle-memory tap on a confirm button cannot supply.
        var typed by remember(active.id) { mutableStateOf("") }
        val nameMatches = typed.trim() == active.name
        NorfoldDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete ${active.name}?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("This permanently deletes every doc, note, notebook, task, board, chat, calendar event, goal and attached file in this workspace. It cannot be undone.")
                    Text("Type the workspace name to confirm.", fontWeight = FontWeight.Medium)
                    OutlinedTextField(
                        typed,
                        { typed = it },
                        label = { Text("Workspace name") },
                        placeholder = { Text(active.name) },
                        singleLine = true,
                        isError = typed.isNotBlank() && !nameMatches,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { viewModel.deleteWorkspace(active.id); confirmDelete = false },
                    enabled = nameMatches && state.workspaces.size > 1,
                ) {
                    Text("Delete workspace", color = MaterialTheme.colorScheme.error)
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
                subtitle = if (state.workspaces.size > 1) "Permanently remove this workspace and everything in it" else "Create another workspace before deleting this one",
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
                        .background(Brush.linearGradient(listOf(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.surface, MaterialTheme.colorScheme.primary.copy(alpha = 0.60f)))),
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
                    Text("@$username", color = Color.White.copy(alpha = 0.76f), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
private fun WorkspaceBannerCard(name: String, icon: String, iconKind: WorkspaceIconKind, iconUri: String?, backgroundUri: String?, onEdit: () -> Unit) {
    Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surface.copy(alpha = 0.78f), tonalElevation = 2.dp) {
        Box(Modifier.fillMaxWidth().aspectRatio(2.12f).clickable(onClick = onEdit)) {
            if (!backgroundUri.isNullOrBlank()) {
                AsyncImage(backgroundUri, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            } else {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Brush.linearGradient(listOf(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.surface, MaterialTheme.colorScheme.primary.copy(alpha = 0.70f)))),
                )
            }
            Box(Modifier.matchParentSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.62f)))))
            Row(
                Modifier.align(Alignment.BottomStart).padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                WorkspaceIconBadge(
                    icon = icon,
                    iconKind = iconKind,
                    iconUri = iconUri,
                    size = 54.dp,
                    corner = 16.dp,
                    fontSize = 22.sp,
                )
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
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val fontCatalog = remember(context) { FontCatalogStore(context) }
    var showFontPicker by remember { mutableStateOf(false) }
    var showLabelsPicker by remember { mutableStateOf(false) }
    var showAppIconPicker by remember { mutableStateOf(false) }
    var iconImportStatus by remember { mutableStateOf<String?>(null) }
    var pendingAppIcon by remember { mutableStateOf<SemanticActionId?>(null) }
    val iconImporter = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            iconImportStatus = "Importing icon pack…"
            iconImportStatus = runCatching {
                val result = withContext(Dispatchers.IO) { CustomIconPackStore(context).importZip(uri) }
                viewModel.patchSettings { it.copy(appIconFamily = AppIconFamily.Custom) }
                "Imported ${result.mapped} controls; ${result.missing.size} use safe fallbacks"
            }.getOrElse { "Couldn't import icon pack: ${it.message ?: "invalid archive"}" }
        }
    }
    val singleAppIconImporter = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val action = pendingAppIcon
        pendingAppIcon = null
        if (uri != null && action != null) scope.launch {
            iconImportStatus = runCatching {
                withContext(Dispatchers.IO) { CustomIconPackStore(context, "app").importSingle(action, uri) }
                viewModel.patchSettings { it.copy(appIconFamily = AppIconFamily.Custom) }
                "Assigned ${action.defaultLabel}; unmatched controls still use their preset fallback"
            }.getOrElse { "Couldn't assign ${action.defaultLabel}: ${it.message ?: "invalid SVG"}" }
        }
    }
    if (showFontPicker) FontCatalogPickerSheet(
        store = fontCatalog,
        selectedFamily = s.appFont,
        title = "App font",
        onDismiss = { showFontPicker = false },
        onSelect = { picked ->
            viewModel.patchSettings { it.copy(appFont = picked) }
            showFontPicker = false
        },
    )
    if (showLabelsPicker) OptionPickerDialog(
        "Control labels",
        listOf("Adaptive labels", "Icons only", "Icons + text"),
        s.appLabelMode.displayName(),
        { showLabelsPicker = false },
    ) { picked ->
        viewModel.patchSettings {
            it.copy(
                appLabelMode = when (picked) {
                    "Icons only" -> ControlLabelMode.IconsOnly
                    "Icons + text" -> ControlLabelMode.IconsAndText
                    else -> ControlLabelMode.Adaptive
                },
            )
        }
    }
    if (showAppIconPicker) IconFamilyPickerDialog(
        title = "App icon family",
        selected = s.appIconFamily,
        importStatus = iconImportStatus,
        onImport = { iconImporter.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream")) },
        customNamespace = "app",
        onAssignMissing = { action ->
            pendingAppIcon = action
            singleAppIconImporter.launch(arrayOf("image/svg+xml", "text/xml", "application/xml"))
        },
        onDismiss = { showAppIconPicker = false },
        onSelect = { picked ->
            viewModel.patchSettings { it.copy(appIconFamily = picked) }
            showAppIconPicker = false
        },
    )

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
        // Accent color dots (wired to palette). The canonical logo palette is the default.
        Column {
            SettingsSectionLabel("Accent color")
            var showAllPalettes by remember { mutableStateOf(false) }
            val accentProfiles = listOf(ThemeProfile.Norfold) + ThemeProfile.entries.filter { it != ThemeProfile.Norfold }
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
        // Density / font / independent app scales / labels / animations.
        SettingsGroup {
            SettingsRow("UI density", trailing = { RowValue(if (s.uiDensityCompact) "Compact" else "Comfortable", accent = true) }, onClick = { viewModel.patchSettings { it.copy(uiDensityCompact = !it.uiDensityCompact) } })
            RowDivider(inset = false)
            SettingsRow(
                "App font",
                subtitle = "Choose from device fonts or the complete open-source Google Fonts catalog",
                trailing = { RowValue(s.appFont, accent = true) },
                onClick = { showFontPicker = true },
            )
            RowDivider(inset = false)
            ScalePreferenceEditor(
                label = "App text scale",
                description = "Changes app text only; document selection and typing size live in the editor toolbar",
                value = s.fontScale,
                setting = ScaleSetting.Font,
                onValue = { viewModel.previewScales(fontScale = it) },
                onValidity = viewModel::setScaleInputValidity,
            )
            RowDivider(inset = false)
            ScalePreferenceEditor(
                label = "App UI/UX scale",
                description = "Changes control and layout dimensions independently from app text",
                value = s.uiScale,
                setting = ScaleSetting.Ui,
                onValue = { viewModel.previewScales(uiScale = it) },
                onValidity = viewModel::setScaleInputValidity,
            )
            RowDivider(inset = false)
            SettingsRow(
                "Labels",
                subtitle = "Adaptive labels keep familiar actions compact and explain ambiguous actions",
                trailing = { RowChevron(s.appLabelMode.displayName()) },
                onClick = { showLabelsPicker = true },
            )
            RowDivider(inset = false)
            SettingsRow(
                "App icon family",
                subtitle = "Choose among verified free presets or import your own SVG ZIP",
                trailing = { RowChevron(s.appIconFamily.displayName()) },
                onClick = { showAppIconPicker = true },
            )
            RowDivider(inset = false)
            SettingsRow(
                "Reset appearance scale",
                subtitle = "Preview font and interface scale at 100%",
                onClick = { viewModel.previewScales(fontScale = 1f, uiScale = 1f) },
            )
            RowDivider(inset = false)
            SettingsRow("Reduce motion", subtitle = "Minimize navigation and content animations", trailing = { RowSwitch(s.reduceMotion) { viewModel.setPrivacyOption(reduceMotion = it) } })
        }
    }
}

@Composable
private fun AccessibilitySettings(state: DocsUiState, viewModel: DocsViewModel) {
    val settings = state.settings
    var showLabelsPicker by remember { mutableStateOf(false) }
    if (showLabelsPicker) {
        OptionPickerDialog(
            title = "Visible control labels",
            options = listOf("Adaptive labels", "Icons only", "Icons + text"),
            selected = settings.appLabelMode.displayName(),
            onDismiss = { showLabelsPicker = false },
        ) { picked ->
            viewModel.patchSettings {
                it.copy(
                    appLabelMode = when (picked) {
                        "Icons only" -> ControlLabelMode.IconsOnly
                        "Icons + text" -> ControlLabelMode.IconsAndText
                        else -> ControlLabelMode.Adaptive
                    },
                )
            }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.fillMaxWidth()) {
        SettingsGroup(header = "Visible labels") {
            SettingsRow(
                "App control labels",
                subtitle = "The same setting shown in Appearance",
                icon = Icons.Outlined.AccessibilityNew,
                trailing = { RowChevron(settings.appLabelMode.displayName()) },
                onClick = { showLabelsPicker = true },
            )
            RowDivider(inset = false)
            SettingsRow(
                "Editor toolbar labels",
                subtitle = "Global default; an individual document can override it from Document settings",
                icon = Icons.Outlined.Code,
                trailing = { RowChevron(settings.appLabelMode.displayName()) },
                onClick = { showLabelsPicker = true },
            )
        }
        SettingsGroup(header = "How adaptive labels work") {
            Column(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("Adaptive labels respond to available space and your scaling choices.", fontWeight = FontWeight.SemiBold)
                Text(
                    "Familiar actions may stay icon-only. Ambiguous or active actions receive visible text, and every icon keeps an accessibility name for screen readers.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp,
                )
                Text(
                    "Choose Icons + text when you want labels shown wherever space allows. Tool rows scroll horizontally instead of clipping controls.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp,
                )
            }
        }
    }
}

private fun ControlLabelMode.displayName(): String = when (this) {
    ControlLabelMode.Adaptive -> "Adaptive labels"
    ControlLabelMode.IconsOnly -> "Icons only"
    ControlLabelMode.IconsAndText -> "Icons + text"
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
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val fontCatalog = remember(context) { FontCatalogStore(context) }
    val lineWidths = EditorLineWidth.entries
    var showLinePicker by remember { mutableStateOf(false) }
    var showDocumentFacePicker by remember { mutableStateOf(false) }
    var showEditorIconPicker by remember { mutableStateOf(false) }
    var editorIconImportStatus by remember { mutableStateOf<String?>(null) }
    var pendingEditorIcon by remember { mutableStateOf<SemanticActionId?>(null) }
    val editorIconImporter = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            editorIconImportStatus = "Importing icon pack…"
            editorIconImportStatus = runCatching {
                val result = withContext(Dispatchers.IO) { CustomIconPackStore(context, "editor").importZip(uri) }
                viewModel.patchSettings { it.copy(editorIconFamily = EditorIconFamily.Custom) }
                "Imported ${result.mapped} controls; ${result.missing.size} use safe fallbacks"
            }.getOrElse { "Couldn't import icon pack: ${it.message ?: "invalid archive"}" }
        }
    }
    val singleEditorIconImporter = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val action = pendingEditorIcon
        pendingEditorIcon = null
        if (uri != null && action != null) scope.launch {
            editorIconImportStatus = runCatching {
                withContext(Dispatchers.IO) { CustomIconPackStore(context, "editor").importSingle(action, uri) }
                viewModel.patchSettings { it.copy(editorIconFamily = EditorIconFamily.Custom) }
                "Assigned ${action.defaultLabel}; unmatched editor controls use their preset fallback"
            }.getOrElse { "Couldn't assign ${action.defaultLabel}: ${it.message ?: "invalid SVG"}" }
        }
    }
    var showHiddenFonts by remember { mutableStateOf(false) }
    var hiddenFontsSnapshot by remember { mutableStateOf<FontCatalogSnapshot?>(null) }
    var hiddenReload by remember { mutableStateOf(0) }
    var selectedHiddenFonts by remember { mutableStateOf(emptySet<String>()) }
    LaunchedEffect(showHiddenFonts, hiddenReload) {
        if (showHiddenFonts) hiddenFontsSnapshot = fontCatalog.snapshot()
    }
    if (showLinePicker) OptionPickerDialog("Line width", lineWidths.map { it.name }, s.editorLineWidth.name, { showLinePicker = false }) { p -> viewModel.setEditorLineWidth(EditorLineWidth.valueOf(p)) }
    if (showDocumentFacePicker) OptionPickerDialog(
        "Document font",
        EditorFontFamily.entries.map { it.documentFaceLabel },
        s.editorFontFamily.documentFaceLabel,
        { showDocumentFacePicker = false },
    ) { picked ->
        EditorFontFamily.entries.firstOrNull { it.documentFaceLabel == picked }
            ?.let(viewModel::setEditorFontFamily)
    }
    if (showEditorIconPicker) EditorIconFamilyPickerDialog(
        selected = s.editorIconFamily,
        appFamily = s.appIconFamily,
        importStatus = editorIconImportStatus,
        onImport = { editorIconImporter.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream")) },
        onAssignMissing = { action ->
            pendingEditorIcon = action
            singleEditorIconImporter.launch(arrayOf("image/svg+xml", "text/xml", "application/xml"))
        },
        onDismiss = { showEditorIconPicker = false },
        onSelect = { picked ->
            viewModel.patchSettings { it.copy(editorIconFamily = picked) }
            showEditorIconPicker = false
        },
    )
    if (showHiddenFonts) NorfoldDialog(
        onDismissRequest = { showHiddenFonts = false },
        title = { Text("Hidden editor fonts") },
        text = {
            val hidden = hiddenFontsSnapshot?.hidden.orEmpty().sortedBy(String::lowercase)
            if (hidden.isEmpty()) Text("No fonts are hidden.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            else LazyColumn(Modifier.fillMaxWidth().heightIn(max = 460.dp)) {
                items(hidden.size) { index ->
                    val family = hidden[index]
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = family in selectedHiddenFonts,
                            onCheckedChange = { checked ->
                                selectedHiddenFonts = if (checked) selectedHiddenFonts + family else selectedHiddenFonts - family
                            },
                        )
                        Text(family, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        TextButton(onClick = {
                            fontCatalog.restore(listOf(family))
                            selectedHiddenFonts -= family
                            hiddenReload++
                        }) { Text("Restore") }
                    }
                }
            }
        },
        dismissButton = { TextButton(onClick = { showHiddenFonts = false }) { Text("Close") } },
        confirmButton = {
            Button(
                onClick = {
                    fontCatalog.restore(selectedHiddenFonts)
                    selectedHiddenFonts = emptySet()
                    hiddenReload++
                },
                enabled = selectedHiddenFonts.isNotEmpty(),
            ) { Text("Restore selected") }
        },
    )

    Column(verticalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.fillMaxWidth()) {
        SettingsGroup(header = "Toolbar") {
            SettingsRow(
                "Icon family",
                subtitle = "Independent from app chrome when explicitly selected",
                trailing = { RowChevron(s.editorIconFamily.displayName()) },
                onClick = { showEditorIconPicker = true },
            )
            RowDivider(inset = false)
            ScalePreferenceEditor(
                label = "Toolbar thickness",
                description = "Scales editor controls, icons and toolbar labels together",
                value = s.editorToolbarScale,
                setting = ScaleSetting.Toolbar,
                onValue = { viewModel.previewScales(toolbarScale = it) },
                onValidity = viewModel::setScaleInputValidity,
            )
            RowDivider(inset = false)
            Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                Text("Live toolbar preview", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                Text("Targets remain at least 48dp even when visuals are smaller.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                ToolbarPreviewStrip(s.appLabelMode, s.editorToolbarScale)
            }
            RowDivider(inset = false)
            SettingsRow(
                "Reset toolbar appearance",
                subtitle = "Preview toolbar thickness at 100%",
                onClick = { viewModel.previewScales(toolbarScale = 1f) },
            )
        }
        SettingsGroup(header = "Editor") {
            SettingsRow(
                "Document surface",
                subtitle = "Tap to select, tap again to edit, then drag to move",
                trailing = { RowValue("View + Edit", accent = true) },
            )
            RowDivider(inset = false)
            ReadingSizeEditor(
                fraction = s.editorFontSize,
                lineWidth = s.editorLineWidth,
                onValue = { v -> viewModel.patchSettings { it.copy(editorFontSize = v.coerceIn(0f, 1f)) } },
            )
            RowDivider(inset = false)
            SettingsRow(
                "Line width",
                subtitle = "Caps a line at about ${s.editorLineWidth.contentCharacters} characters; a wider window gains margin, not longer lines",
                trailing = { RowChevron(s.editorLineWidth.name) },
                onClick = { showLinePicker = true },
            )
            RowDivider(inset = false)
            SettingsRow(
                "Document font",
                subtitle = "The face a document's text starts from. App font follows your app typeface; a style you write, or a face you apply to a selection, still wins",
                trailing = { RowChevron(s.editorFontFamily.documentFaceLabel) },
                onClick = { showDocumentFacePicker = true },
            )
            RowDivider(inset = false)
            SettingsRow(
                "Selected text and next typing size",
                subtitle = "Use A↕ in the editor: a selection changes immediately; a caret changes what you type next until reset",
                trailing = { RowValue("6–144 sp", accent = true) },
            )
            RowDivider(inset = false)
            SettingsRow(
                "Hidden fonts",
                subtitle = "Restore individual or multiple fonts hidden from search, A–Z, recent and most-used lists",
                trailing = { RowChevron(hiddenFontsSnapshot?.hidden?.size?.toString().orEmpty()) },
                onClick = { showHiddenFonts = true },
            )
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
        SettingsGroup(header = "Task interactions") {
            TaskGestureRow("Swipe right", state.settings.taskSwipeStartAction) { viewModel.setTaskGestureActions(swipeStart = it) }
            RowDivider(inset = false)
            TaskGestureRow("Swipe left", state.settings.taskSwipeEndAction) { viewModel.setTaskGestureActions(swipeEnd = it) }
        }
        SettingsGroup(header = "Note rendering") {
            RenderEngineRow(state.settings.noteRenderEngine) { viewModel.setNoteRenderEngine(it) }
        }
    }
}

@Composable
private fun IconFamilyPickerDialog(
    title: String,
    selected: AppIconFamily,
    importStatus: String?,
    onImport: () -> Unit,
    customNamespace: String = "app",
    onAssignMissing: (SemanticActionId) -> Unit = {},
    onDismiss: () -> Unit,
    onSelect: (AppIconFamily) -> Unit,
) {
    val context = LocalContext.current
    val customStore = remember(context, customNamespace, importStatus) { CustomIconPackStore(context, customNamespace) }
    val relevantActions = remember(customNamespace) {
        if (customNamespace == "editor") {
            listOf(
                SemanticActionId.Back, SemanticActionId.Menu, SemanticActionId.Search,
                SemanticActionId.Done, SemanticActionId.Save, SemanticActionId.Undo,
                SemanticActionId.Redo, SemanticActionId.Bold, SemanticActionId.Italic,
                SemanticActionId.Underline, SemanticActionId.Insert, SemanticActionId.Toolsets,
                SemanticActionId.Paragraph, SemanticActionId.Checklist, SemanticActionId.Table,
                SemanticActionId.Image, SemanticActionId.Attachment, SemanticActionId.Shapes,
                SemanticActionId.Arrange, SemanticActionId.Review, SemanticActionId.More,
            )
        } else {
            listOf(
                SemanticActionId.AppHome, SemanticActionId.AppToday, SemanticActionId.AppUpcoming,
                SemanticActionId.AppFavorites, SemanticActionId.AppNotes, SemanticActionId.AppDocuments,
                SemanticActionId.AppTasks, SemanticActionId.AppFiles, SemanticActionId.AppChat,
                SemanticActionId.AppInbox, SemanticActionId.AppDatabase, SemanticActionId.AppGraph,
                SemanticActionId.AppActivity, SemanticActionId.AppTemplates, SemanticActionId.AppSettings,
                SemanticActionId.AppFolder, SemanticActionId.AppTags,
            )
        }
    }
    val mappedCount = remember(importStatus, relevantActions) { relevantActions.count { customStore.iconFile(it) != null } }
    val missingActions = remember(importStatus, relevantActions) { relevantActions.filter { customStore.iconFile(it) == null } }
    val previewActions = if (customNamespace == "editor") {
        listOf(SemanticActionId.Bold, SemanticActionId.Italic, SemanticActionId.Insert)
    } else {
        listOf(SemanticActionId.AppHome, SemanticActionId.AppNotes, SemanticActionId.AppDocuments)
    }
    NorfoldDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                Text(
                    "Every bundled preset is free to use under the license shown. The preview is rendered by the same icon system used throughout this part of Norfold.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                )
                importStatus?.let {
                    Text(it, color = MaterialTheme.colorScheme.primary, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
                }
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 480.dp).padding(top = 8.dp)) {
                    items(NorfoldIconFamilies.size) { index ->
                        val info = NorfoldIconFamilies[index]
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .clickable(enabled = info.family != AppIconFamily.Custom || mappedCount > 0) { onSelect(info.family) }
                                .padding(horizontal = 10.dp, vertical = 10.dp),
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                                    previewActions.forEach { action ->
                                        SemanticFamilyIcon(info.family, action, null, Modifier.size(20.dp), customNamespace = customNamespace)
                                    }
                                }
                                Column(Modifier.weight(1f)) {
                                    Text(info.displayName, fontWeight = FontWeight.SemiBold)
                                    Text(
                                        if (info.family == AppIconFamily.Custom) "$mappedCount of ${relevantActions.size} mapped controls · ${info.license}" else "${info.license} · ${info.source}",
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        fontSize = 11.sp,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                if (selected == info.family) Icon(Icons.Outlined.Check, "Selected", tint = MaterialTheme.colorScheme.primary)
                            }
                            if (info.family == AppIconFamily.Custom) {
                                TextButton(onClick = onImport, modifier = Modifier.align(Alignment.End)) {
                                    Text(if (mappedCount == 0) "Import SVG ZIP" else "Replace imported pack")
                                }
                                if (missingActions.isNotEmpty()) {
                                    Text(
                                        "${missingActions.size} icons are missing. Norfold uses the active default icon for each missing item until you assign an SVG.",
                                        color = MaterialTheme.colorScheme.error,
                                        fontSize = 11.sp,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                    )
                                    missingActions.forEach { action ->
                                        TextButton(
                                            onClick = { onAssignMissing(action) },
                                            modifier = Modifier.fillMaxWidth(),
                                        ) {
                                            Text("Assign ${action.defaultLabel}", modifier = Modifier.weight(1f))
                                            Icon(Icons.Outlined.FileUpload, null, modifier = Modifier.size(18.dp))
                                        }
                                    }
                                }
                            }
                        }
                        if (index != NorfoldIconFamilies.lastIndex) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
private fun EditorIconFamilyPickerDialog(
    selected: EditorIconFamily,
    appFamily: AppIconFamily,
    importStatus: String?,
    onImport: () -> Unit,
    onAssignMissing: (SemanticActionId) -> Unit,
    onDismiss: () -> Unit,
    onSelect: (EditorIconFamily) -> Unit,
) {
    var showSpecific by remember { mutableStateOf(false) }
    if (showSpecific) {
        IconFamilyPickerDialog(
            title = "Editor icon preset",
            selected = AppIconFamily.entries.firstOrNull { it.name == selected.name } ?: appFamily,
            importStatus = importStatus,
            onImport = onImport,
            customNamespace = "editor",
            onAssignMissing = onAssignMissing,
            onDismiss = { showSpecific = false },
            onSelect = { family ->
                onSelect(EditorIconFamily.entries.first { it.name == family.name })
                showSpecific = false
            },
        )
        return
    }
    NorfoldDialog(
        onDismissRequest = onDismiss,
        title = { Text("Editor icon family") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Surface(
                    onClick = { onSelect(EditorIconFamily.UseApp) },
                    shape = RoundedCornerShape(12.dp),
                    color = if (selected == EditorIconFamily.UseApp) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                ) {
                    Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        SemanticFamilyIcon(appFamily, SemanticActionId.Bold, null, Modifier.size(24.dp))
                        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                            Text("Use app icon family", fontWeight = FontWeight.SemiBold)
                            Text(appFamily.displayName(), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                        }
                        if (selected == EditorIconFamily.UseApp) Icon(Icons.Outlined.Check, "Selected")
                    }
                }
                Surface(onClick = { showSpecific = true }, shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                    Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Palette, null)
                        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                            Text("Choose editor-only preset", fontWeight = FontWeight.SemiBold)
                            Text(if (selected == EditorIconFamily.UseApp) "Independent from app icons" else selected.displayName(), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                        }
                        Icon(Icons.Outlined.ChevronRight, null)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
private fun ScalePreferenceEditor(
    label: String,
    description: String = "50%–200% · temporary until Save",
    value: Float,
    setting: ScaleSetting,
    onValue: (Float) -> Unit,
    onValidity: (ScaleSetting, Boolean) -> Unit,
) {
    var raw by remember(label) { mutableStateOf((value * 100f).roundToInt().toString()) }
    LaunchedEffect(value) {
        raw = (value * 100f).roundToInt().toString()
    }
    val parsed = raw.toFloatOrNull()
    val valid = parsed != null && parsed in 50f..200f

    Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(Modifier.weight(1f)) {
                Text(label, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                Text("$description · 50%–200% · temporary until Save", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
            }
            TextButton(onClick = {
                raw = "100"
                onValidity(setting, true)
                onValue(1f)
            }) { Text("Reset") }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Slider(
                value = value.coerceIn(0.5f, 2f),
                onValueChange = { next ->
                    raw = (next * 100f).roundToInt().toString()
                    onValidity(setting, true)
                    onValue(next)
                },
                modifier = Modifier.weight(1f),
                valueRange = 0.5f..2f,
                steps = 149,
            )
            OutlinedTextField(
                value = raw,
                onValueChange = { next ->
                    raw = next.take(7)
                    val numeric = raw.toFloatOrNull()
                    val inRange = numeric != null && numeric in 50f..200f
                    onValidity(setting, inRange)
                    if (inRange) onValue(numeric!! / 100f)
                },
                modifier = Modifier
                    .widthIn(min = 88.dp, max = 104.dp),
                singleLine = true,
                isError = raw.isNotEmpty() && !valid,
                suffix = { Text("%") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            )
        }
        if (!valid) {
            Text("Enter a value from 50 to 200. The last valid preview remains active.", color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
        }
    }
}

@Composable
private fun ToolbarPreviewStrip(labelMode: ControlLabelMode, toolbarScale: Float) {
    data class PreviewCommand(val action: SemanticActionId, val label: String, val ambiguous: Boolean)
    val commands = remember {
        listOf(
            PreviewCommand(SemanticActionId.Back, "Back", false),
            PreviewCommand(SemanticActionId.Undo, "Undo", false),
            PreviewCommand(SemanticActionId.Bold, "Bold", false),
            PreviewCommand(SemanticActionId.Insert, "Insert", true),
            PreviewCommand(SemanticActionId.Toolsets, "Toolsets", true),
        )
    }
    val visualScale = toolbarScale.coerceIn(0.5f, 2f)
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        commands.forEach { command ->
            val showLabel = when (labelMode) {
                ControlLabelMode.IconsOnly -> false
                ControlLabelMode.IconsAndText -> true
                ControlLabelMode.Adaptive -> command.ambiguous
            }
            Surface(
                shape = RoundedCornerShape((12f * visualScale.coerceAtMost(1.5f)).dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
            ) {
                Row(
                    modifier = Modifier
                        .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                        .padding(horizontal = if (showLabel) 12.dp else 8.dp, vertical = (6f * visualScale).dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SemanticEditorIcon(command.action, command.label, Modifier.size((24f * visualScale).coerceIn(14f, 40f).dp))
                    if (showLabel) Text(command.label, fontSize = (13f * visualScale).coerceIn(10f, 20f).sp, maxLines = 1)
                }
            }
        }
    }
}

/**
 * What the document-face row and its picker show for each stored face.
 *
 * `Sans` reads as "App font" rather than "Sans" because that is what it does: it defers to whichever
 * typeface the app is set to instead of asserting a sans face of its own. Labelling it "Sans" would promise
 * a face the setting does not choose, and would look wrong to anyone whose app font is a serif.
 *
 * The enum names stay untouched — they are the persisted strings — so the mapping lives here, at the one
 * place a person reads them.
 */
private val EditorFontFamily.documentFaceLabel: String
    get() = when (this) {
        EditorFontFamily.Sans -> "App font"
        EditorFontFamily.Serif -> "Serif"
        EditorFontFamily.Mono -> "Monospace"
    }

@Composable
private fun SliderRow(label: String, value: Float, onChange: (Float) -> Unit) {
    Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
        Text(label, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
        Slider(value, onChange, modifier = Modifier.fillMaxWidth())
    }
}

/**
 * How large document text is, with a sample of the answer.
 *
 * The sample is not decoration. This is the one preference on this screen whose effect is invisible from
 * this screen, and "140%" tells nobody whether 140% is what they want. So the sample renders a heading, a
 * paragraph and a caption at the chosen scale inside the chosen measure — which puts all three of the
 * scale's promises on screen at once: the hierarchy holds, prose stays comfortable, the column is capped.
 *
 * It reads [ReadingScale] directly rather than the editor's content typography, because this screen is not
 * inside the editor and must not borrow the editor's canvas theme to show a preview of it.
 */
@Composable
private fun ReadingSizeEditor(fraction: Float, lineWidth: EditorLineWidth, onValue: (Float) -> Unit) {
    Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Reading size",
                fontWeight = FontWeight.SemiBold,
                fontSize = 15.sp,
                modifier = Modifier.weight(1f),
            )
            Text(
                ReadingScale.percentLabel(fraction),
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold,
                fontSize = 14.sp,
            )
        }
        Text(
            "Sizes document text only — toolbars, menus and this screen keep the app size. " +
                "It multiplies your system font size rather than replacing it.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 12.sp,
        )
        Slider(value = fraction, onValueChange = onValue, modifier = Modifier.fillMaxWidth())
        ReadingSample(fraction = fraction, lineWidth = lineWidth)
    }
}

@Composable
private fun ReadingSample(fraction: Float, lineWidth: EditorLineWidth) {
    val characters = lineWidth.contentCharacters
    val measure = with(LocalDensity.current) { ReadingScale.measureSp(fraction, characters).sp.toDp() }
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
    ) {
        Column(
            Modifier.padding(14.dp).widthIn(max = measure),
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Text(
                "A heading keeps its rank",
                fontFamily = FontFamily.Serif,
                fontWeight = FontWeight.SemiBold,
                fontSize = ReadingScale.sizeSp(ContentRole.HEADING_2, fraction).sp,
                lineHeight = ReadingScale.lineHeightSp(ContentRole.HEADING_2, fraction).sp,
            )
            Text(
                "This is a paragraph at the size your documents will use. Long-form writing wants a line " +
                    "you can return from without losing your place.",
                fontSize = ReadingScale.sizeSp(ContentRole.BODY, fraction).sp,
                lineHeight = ReadingScale.lineHeightSp(ContentRole.BODY, fraction).sp,
            )
            Text(
                "About $characters characters per line · ${ReadingScale.percentLabel(fraction)}",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = ReadingScale.sizeSp(ContentRole.SMALL, fraction).sp,
                lineHeight = ReadingScale.lineHeightSp(ContentRole.SMALL, fraction).sp,
            )
        }
    }
}

@Composable
private fun TaskGestureRow(label: String, selected: TaskGestureAction, onSelect: (TaskGestureAction) -> Unit) {
    val actions = TaskGestureAction.entries
    SettingsRow(label, trailing = { RowChevron(selected.name) }, onClick = {
        onSelect(actions[(actions.indexOf(selected) + 1) % actions.size])
    })
}

@Composable
private fun RenderEngineRow(selected: NoteRenderEngine, onSelect: (NoteRenderEngine) -> Unit) {
    val engines = NoteRenderEngine.entries
    val subtitle = when (selected) {
        NoteRenderEngine.Auto -> "Picks WebView or native based on device capability"
        NoteRenderEngine.Native -> "Lightweight native renderer — best performance"
        NoteRenderEngine.WebView -> "Full WebView renderer — richest formatting"
    }
    SettingsRow(
        "Render engine",
        subtitle = subtitle,
        trailing = { RowChevron(selected.name) },
        onClick = { onSelect(engines[(engines.indexOf(selected) + 1) % engines.size]) },
    )
}

@Composable
private fun GestureRow(label: String, selected: NoteGestureAction, onSelect: (NoteGestureAction) -> Unit) {
    val actions = NoteGestureAction.entries
    SettingsRow(label, trailing = { RowChevron(selected.name) }, onClick = {
        onSelect(actions[(actions.indexOf(selected) + 1) % actions.size])
    })
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
    onCloudGoogleSignIn: () -> Unit,
    onNavigate: (SettingsSection) -> Unit,
) {
    val capabilities = ExternalServiceConfig.capabilities
    val accountEmail by viewModel.cloudAccountEmail.collectAsState()
    val emailAuthPhase by viewModel.emailAuthPhase.collectAsState()
    val emailAuthOperation by viewModel.emailAuthOperation.collectAsState()
    var showEmailAuth by remember { mutableStateOf(false) }
    var signUp by remember { mutableStateOf(false) }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    val authBusy = emailAuthOperation != EmailAuthOperation.Idle

    LaunchedEffect(emailAuthPhase) {
        if (emailAuthPhase == EmailAuthPhase.Completed) {
            showEmailAuth = false
            password = ""
            code = ""
            viewModel.consumeEmailAuthCompleted()
        }
    }
    if (showEmailAuth || emailAuthPhase !is EmailAuthPhase.None) {
        NorfoldDialog(
            onDismissRequest = { showEmailAuth = false; viewModel.cancelEmailAuth() },
            title = {
                Text(
                    when (emailAuthPhase) {
                        is EmailAuthPhase.AwaitSignupCode -> "Verify your email"
                        is EmailAuthPhase.AwaitRecoveryCode -> "Enter recovery code"
                        is EmailAuthPhase.AwaitNewPassword -> "Choose a new password"
                        else -> if (signUp) "Create Norfold account" else "Sign in with email"
                    },
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    when (val phase = emailAuthPhase) {
                        is EmailAuthPhase.AwaitSignupCode,
                        is EmailAuthPhase.AwaitRecoveryCode -> {
                            val target = when (phase) {
                                is EmailAuthPhase.AwaitSignupCode -> phase.email
                                is EmailAuthPhase.AwaitRecoveryCode -> phase.email
                                else -> ""
                            }
                            Text("Enter the 6-digit code sent to $target", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            OutlinedTextField(code, { code = it.filter(Char::isDigit).take(6) }, label = { Text("Verification code") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, modifier = Modifier.fillMaxWidth())
                        }
                        is EmailAuthPhase.AwaitNewPassword ->
                            OutlinedTextField(password, { password = it }, label = { Text("New password") }, visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth())
                        else -> {
                            OutlinedTextField(email, { email = it }, label = { Text("Email") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email), singleLine = true, modifier = Modifier.fillMaxWidth())
                            OutlinedTextField(password, { password = it }, label = { Text("Password") }, visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth())
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(signUp, { signUp = it })
                                Text("Create a new account")
                            }
                            Text("Passwords are sent only to Norfold Cloud over TLS and are never stored in workspace files.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    enabled = !authBusy,
                    onClick = {
                        when (emailAuthPhase) {
                            is EmailAuthPhase.AwaitSignupCode,
                            is EmailAuthPhase.AwaitRecoveryCode -> viewModel.verifyEmailCode(code)
                            is EmailAuthPhase.AwaitNewPassword -> viewModel.completePasswordReset(password)
                            else -> viewModel.authenticateWithEmail(email, password, signUp)
                        }
                    },
                ) { Text(if (authBusy) "Working…" else if (emailAuthPhase is EmailAuthPhase.None) if (signUp) "Create account" else "Sign in" else "Continue") }
            },
            dismissButton = { TextButton(onClick = { showEmailAuth = false; viewModel.cancelEmailAuth() }) { Text("Cancel") } },
        )
    }
    Column(verticalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.fillMaxWidth()) {
        SettingsGroup(header = "Account") {
            SettingsRow(
                accountEmail ?: state.settings.syncUserName.ifBlank { "Local owner" },
                subtitle = if (accountEmail != null) "Signed in to Norfold Cloud" else if (capabilities.googleIdentity) "Cloud identity is ready" else "Local-first workspace · cloud account backend not configured",
                icon = Icons.Outlined.Person,
                trailing = { RowValue(if (accountEmail == null) "Local" else "Connected", accent = accountEmail != null) },
            )
            RowDivider()
            if (accountEmail == null) {
                Button(onClick = onCloudGoogleSignIn, enabled = capabilities.googleIdentity && !authBusy, modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
                    Text("Sign in with Google")
                }
                ElevatedButton(onClick = { showEmailAuth = true }, enabled = capabilities.supabase && !authBusy, modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
                    Text("Sign in or create account with email")
                }
            } else {
                ElevatedButton(onClick = viewModel::signOutCloudAccount, modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
                    Text("Sign out of Norfold Cloud")
                }
            }
            RowDivider()
            SettingsRow(
                "Google configuration",
                subtitle = if (capabilities.googleDrive) "Android client and Drive app-data project are configured" else "Add the Android OAuth client and Google project ID",
                icon = if (capabilities.googleDrive) Icons.Outlined.Check else Icons.Outlined.CloudOff,
                trailing = { RowValue(if (capabilities.googleDrive) "Ready" else "Needs setup", accent = capabilities.googleDrive) },
            )
        }
        SettingsGroup(header = "Sync") {
            SettingsRow(
                "Last synced",
                subtitle = state.settings.lastSyncAt?.let { relativeSettingsTime(it) + " ago" } ?: "Never",
                icon = Icons.Outlined.CloudSync,
            )
            RowDivider()
            SettingsRow(
                "Sync Settings",
                subtitle = "Provider status, Sync now, cooldown and background behavior",
                icon = Icons.Outlined.CloudSync,
                trailing = { RowChevron() },
                onClick = { onNavigate(SettingsSection.SyncEngine) },
            )
            RowDivider()
            SettingsRow("Backup & Import", subtitle = "Encrypted exports, restore and Markdown", icon = Icons.Outlined.Backup, trailing = { RowChevron() }, onClick = { onNavigate(SettingsSection.Backup) })
            RowDivider()
            SettingsRow("Conflict Resolution", subtitle = "Defaults, reports and keep-both behavior", icon = Icons.Outlined.Difference, trailing = { RowChevron() }, onClick = { onNavigate(SettingsSection.Conflicts) })
        }
        var showLegacyChain by remember { mutableStateOf(false) }
        SettingsGroup(header = "Advanced") {
            SettingsRow(
                "Legacy sync chain",
                subtitle = "Manual encrypted sync via Drive app-data or a folder you choose. Separate from your Norfold Cloud account.",
                icon = Icons.Outlined.Tune,
                trailing = { RowValue(if (showLegacyChain) "Hide" else "Show", accent = showLegacyChain) },
                onClick = { showLegacyChain = !showLegacyChain },
            )
        }
        if (showLegacyChain) {
            ManualSyncCard(state, viewModel, onPickSyncFolder, onConnectGoogleDrive, onDisconnectGoogleDrive)
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
            var showWipeConfirm by remember { mutableStateOf(false) }
            ElevatedButton(
                onClick = { showWipeConfirm = true },
                enabled = state.googleDriveConnected && !state.syncing,
                modifier = Modifier.fillMaxWidth(),
                colors = androidx.compose.material3.ButtonDefaults.elevatedButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) { Text("Wipe all Drive backup data") }
            if (showWipeConfirm) {
                NorfoldDialog(
                    onDismissRequest = { showWipeConfirm = false },
                    title = { Text("Delete all backups?", fontWeight = FontWeight.Bold) },
                    text = { Text("This permanently deletes all Norfold backup files from your Google Drive app data. Your local notes are not affected. This cannot be undone.") },
                    confirmButton = {
                        TextButton(onClick = { showWipeConfirm = false; viewModel.wipeGoogleDriveBackup() }) {
                            Text("Delete forever", color = MaterialTheme.colorScheme.error)
                        }
                    },
                    dismissButton = { TextButton(onClick = { showWipeConfirm = false }) { Text("Cancel") } },
                )
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
            ) { SyncButtonContent("Create Google sync chain", state.syncing) }
            ElevatedButton(
                modifier = Modifier.fillMaxWidth(),
                enabled = state.googleDriveConnected && syncSecret.isNotBlank() && !state.syncing,
                onClick = { viewModel.restoreGoogleDriveApiChain(syncSecret, syncUsername, syncPublicName, syncDeviceName) },
            ) { SyncButtonContent("Restore Google sync chain", state.syncing) }
        }
    }
    OutlinedTextField(syncSecret, { syncSecret = it }, label = { Text("Backup password (optional)") }, supportingText = { Text(if (syncSecret.isBlank()) "Leave blank — Norfold manages encryption automatically on this device." else "Never uploaded. Required to restore on a new device.") }, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(), singleLine = true)
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
        Button(modifier = Modifier.fillMaxWidth(), enabled = syncSecret.isNotBlank() && !state.syncing, onClick = { onPickSyncFolder(SyncFolderRequest(syncProvider, SyncFolderAction.CreateChain, syncSecret, syncUsername, syncPublicName, syncDeviceName)) }) { SyncButtonContent("Create manual chain", state.syncing) }
        ElevatedButton(modifier = Modifier.fillMaxWidth(), enabled = syncSecret.isNotBlank() && !state.syncing, onClick = { onPickSyncFolder(SyncFolderRequest(syncProvider, SyncFolderAction.RestoreChain, syncSecret, syncUsername, syncPublicName, syncDeviceName)) }) { SyncButtonContent("Restore manual chain", state.syncing) }
    }
    ElevatedButton(modifier = Modifier.fillMaxWidth(), onClick = { viewModel.go(Destination.ConflictReview) }) { Icon(Icons.Outlined.Difference, null); Text("Open conflicts") }
}

// ---------- Mockup 13: Sync Settings ----------
@Composable
private fun SyncEngineSettings(state: DocsUiState, viewModel: DocsViewModel, onSetUpSync: () -> Unit) {
    val s = state.settings
    val deviceProfiles by viewModel.deviceSettingsProfiles.collectAsState()
    var cooldownSeconds by remember { mutableStateOf(viewModel.syncCooldownRemainingSeconds()) }
    LaunchedEffect(state.syncing, s.lastSyncAt) {
        do {
            cooldownSeconds = viewModel.syncCooldownRemainingSeconds()
            if (cooldownSeconds > 0L) delay(250L)
        } while (cooldownSeconds > 0L)
    }

    Column(verticalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.fillMaxWidth()) {
        SyncChainCard(state, deviceProfiles)
        SettingsGroup(header = "Sync status") {
            SettingsRow(
                "Provider",
                subtitle = when (s.syncProvider) {
                    SyncProvider.GoogleDrive -> "Encrypted backup in your Google Drive"
                    SyncProvider.OneDrive -> "Encrypted backup in your OneDrive folder"
                    SyncProvider.LocalFolder -> "Encrypted backup in a folder on this device"
                    SyncProvider.None -> "Optional — Norfold works fully offline without it"
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
                SyncButtonContent(
                    label = when {
                        cooldownSeconds > 0L -> "Sync again in ${cooldownSeconds}s"
                        else -> "Sync now"
                    },
                    syncing = state.syncing,
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
            SettingsRow("Sync when leaving the app", subtitle = "Runs only after sync is unlocked in this session", trailing = { RowSwitch(s.autoSync) { v -> viewModel.patchSettings { it.copy(autoSync = v) } } })
            RowDivider(inset = false)
            SettingsRow("Background scheduling", subtitle = "Not enabled in this pre-beta build", trailing = { RowValue("Off") })
        }
        SettingsGroup(header = "Advanced") {
            SettingsRow("Rebuild local index", subtitle = "Re-index all content", icon = Icons.Outlined.Settings, onClick = { viewModel.rebuildWorkspaceIndex() })
        }
    }
}

@Composable
private fun SyncChainCard(state: DocsUiState, deviceProfiles: List<DeviceSettingsProfile>) = SettingsCard {
    val settings = state.settings
    val provider = when (settings.syncProvider) {
        SyncProvider.GoogleDrive -> "Google Drive app data"
        SyncProvider.OneDrive -> "OneDrive folder"
        SyncProvider.LocalFolder -> "Authorized local folder"
        SyncProvider.None -> "No remote destination"
    }
    val statusText = when {
        state.syncing -> "Transferring encrypted snapshot"
        !state.online && settings.syncProvider != SyncProvider.None -> "Offline · local changes stay on this device"
        settings.syncProvider == SyncProvider.None -> "Local only"
        else -> settings.lastSyncStatus
    }
    val statusColor = when {
        state.syncing -> MaterialTheme.colorScheme.primary
        !state.online || statusText.contains("fail", true) || statusText.contains("error", true) -> MaterialTheme.colorScheme.error
        settings.syncConflictCount > 0 -> MaterialTheme.colorScheme.tertiary
        settings.lastSyncAt != null -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val knownDevices = deviceProfiles
        .distinctBy { it.deviceId }
        .sortedByDescending { it.updatedAt }
    val restoreSummary = when {
        knownDevices.isEmpty() -> "This device will register with its first snapshot"
        knownDevices.size == 1 -> "1 registered device profile · ${knownDevices.first().deviceName}"
        else -> "${knownDevices.size} registered device profiles · choose what to restore per device"
    }

    Text("Your sync chain", fontWeight = FontWeight.Black, fontSize = 18.sp)
    Text(
        "Norfold writes locally first, encrypts one workspace snapshot, then moves that snapshot through the configured provider.",
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontSize = 12.sp,
    )
    Spacer(Modifier.size(12.dp))
    SyncChainNode(
        icon = Icons.Outlined.PhoneAndroid,
        title = settings.settingsDeviceName.ifBlank { settings.syncDeviceName.ifBlank { "This device" } },
        subtitle = "Local Room database · edits remain available offline",
        badge = "Source",
    )
    SyncChainConnector()
    SyncChainNode(
        icon = Icons.Outlined.Security,
        title = "Encrypted Norfold snapshot",
        subtitle = "Workspaces, docs, tasks, chat, object history, file metadata and device settings",
        badge = settings.syncChainId?.take(8)?.let { "Chain $it" } ?: "Not created",
    )
    SyncChainConnector()
    SyncChainNode(
        icon = if (settings.syncProvider == SyncProvider.None) Icons.Outlined.CloudOff else Icons.Outlined.CloudSync,
        title = provider,
        subtitle = statusText,
        badge = settings.lastSyncAt?.let { relativeSettingsTime(it) + " ago" } ?: "Never synced",
        accent = statusColor,
    )
    SyncChainConnector()
    SyncChainNode(
        icon = Icons.Outlined.Backup,
        title = "Restore by device profile",
        subtitle = restoreSummary,
        badge = if (knownDevices.size > 1) "Selective" else "Ready",
    )
    if (settings.syncConflictCount > 0) {
        Spacer(Modifier.size(10.dp))
        Text(
            "${settings.syncConflictCount} conflict${if (settings.syncConflictCount == 1) "" else "s"} need review before the chain is fully settled.",
            color = MaterialTheme.colorScheme.tertiary,
            fontWeight = FontWeight.SemiBold,
            fontSize = 12.sp,
        )
    }
}

@Composable
private fun SyncChainNode(
    icon: ImageVector,
    title: String,
    subtitle: String,
    badge: String,
    accent: Color = MaterialTheme.colorScheme.primary,
) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(shape = CircleShape, color = accent.copy(alpha = 0.13f), modifier = Modifier.size(42.dp)) {
            Box(contentAlignment = Alignment.Center) { Icon(icon, null, tint = accent, modifier = Modifier.size(21.dp)) }
        }
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
        }
        Surface(shape = RoundedCornerShape(50), color = accent.copy(alpha = 0.11f)) {
            Text(
                badge,
                color = accent,
                fontWeight = FontWeight.SemiBold,
                fontSize = 10.sp,
                maxLines = 1,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
            )
        }
    }
}

@Composable
private fun SyncChainConnector() {
    Box(Modifier.padding(start = 20.dp).size(width = 2.dp, height = 18.dp).background(MaterialTheme.colorScheme.outlineVariant))
}

@Composable
private fun SyncButtonContent(label: String, syncing: Boolean) {
    if (syncing) {
        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
        Spacer(Modifier.size(8.dp))
        Text("Syncing…")
    } else {
        Icon(Icons.Outlined.CloudSync, null)
        Spacer(Modifier.size(8.dp))
        Text(label)
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
    val deviceProfiles by viewModel.deviceSettingsProfiles.collectAsState()
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
        DeviceSettingsProfilesCard(
            profiles = deviceProfiles,
            settings = s,
            onApply = viewModel::applyDeviceSettingsProfile,
            onReset = viewModel::resetDeviceSettings,
        )
        if (showAdvanced) BackupAdvancedCard(state, viewModel, onPickMarkdown, onPickBackupFolder, onPickBackupFile)
    }
}

@Composable
private fun DeviceSettingsProfilesCard(
    profiles: List<DeviceSettingsProfile>,
    settings: com.norfold.app.domain.AppSettings,
    onApply: (String, Set<SettingsProfileCategory>) -> Unit,
    onReset: (Set<SettingsProfileCategory>) -> Unit,
) = SettingsCard {
    var selectedDeviceId by remember { mutableStateOf<String?>(null) }
    var categories by remember {
        mutableStateOf(
            setOf(
                SettingsProfileCategory.Appearance,
                SettingsProfileCategory.Editor,
                SettingsProfileCategory.Accessibility,
            ),
        )
    }
    LaunchedEffect(profiles, settings.settingsDeviceId) {
        if (profiles.none { it.deviceId == selectedDeviceId }) {
            selectedDeviceId = profiles.firstOrNull { it.deviceId != settings.settingsDeviceId }?.deviceId
                ?: profiles.firstOrNull()?.deviceId
        }
    }
    val selected = profiles.firstOrNull { it.deviceId == selectedDeviceId }
    val changes = selected?.changesFrom(settings).orEmpty()

    Text("Device settings profiles", fontWeight = FontWeight.Black, fontSize = 18.sp)
    Text(
        "Backups keep each device's interface preferences separate. Choose exactly what this device should inherit before applying anything.",
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontSize = 12.sp,
    )
    if (profiles.isEmpty()) {
        Text("A profile will appear after this device finishes registering.", color = MaterialTheme.colorScheme.onSurfaceVariant)
    } else {
        profiles.take(6).forEach { profile ->
            val selectedProfile = profile.deviceId == selectedDeviceId
            Surface(
                modifier = Modifier.fillMaxWidth().clickable { selectedDeviceId = profile.deviceId },
                shape = MaterialTheme.shapes.medium,
                color = if (selectedProfile) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(Icons.Outlined.PhoneAndroid, null, tint = MaterialTheme.colorScheme.primary)
                    Column(Modifier.weight(1f)) {
                        Text(profile.deviceName, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            "${profile.deviceType} · ${profile.screenClass}${if (profile.deviceId == settings.settingsDeviceId) " · This device" else ""}",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (selectedProfile) Icon(Icons.Outlined.Check, "Selected", tint = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }

    if (selected != null) {
        Text("Include", fontWeight = FontWeight.Bold)
        SettingsProfileCategory.entries.forEach { category ->
            Row(
                Modifier.fillMaxWidth().clickable {
                    categories = if (category in categories) categories - category else categories + category
                },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(
                    checked = category in categories,
                    onCheckedChange = { checked -> categories = if (checked) categories + category else categories - category },
                )
                Text(category.name)
            }
        }
        Text("Changes on this device", fontWeight = FontWeight.Bold)
        val visibleChanges = changes.filter { it.category in categories }
        if (visibleChanges.isEmpty()) {
            Text("No selected values would change.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            visibleChanges.take(12).forEach { change ->
                Text(
                    "${change.name}: ${change.currentValue} → ${change.incomingValue}",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                enabled = categories.isNotEmpty() && visibleChanges.isNotEmpty(),
                onClick = { onApply(selected.deviceId, categories) },
            ) { Text("Apply selected") }
            ElevatedButton(
                enabled = categories.isNotEmpty(),
                onClick = { onReset(categories) },
            ) { Text("Reset selected") }
        }
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
        Button(
            enabled = !state.syncing,
            onClick = { viewModel.exportBackup(backupSecret) { backupPayload = it } },
        ) {
            if (state.syncing) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
            } else {
                Icon(Icons.Outlined.FileDownload, null); Text("Export")
            }
        }
        ElevatedButton(
            enabled = !state.syncing,
            onClick = { viewModel.importBackup(backupSecret, backupPayload) },
        ) {
            if (state.syncing) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            } else {
                Icon(Icons.Outlined.FileUpload, null); Text("Import")
            }
        }
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
            SettingsRow("Help & Support", icon = Icons.Outlined.Security, trailing = { RowChevron() }, onClick = { uriHandler.openUri("https://github.com/Norfold-App/Norfold/issues") })
            RowDivider()
            SettingsRow("Privacy policy", icon = Icons.Outlined.Lock, trailing = { RowChevron() }, onClick = { uriHandler.openUri("https://sheikhti1205.github.io/Norfold/privacy.html") })
            RowDivider()
            SettingsRow("Terms of service", icon = Icons.Outlined.Difference, trailing = { RowChevron() }, onClick = { uriHandler.openUri("https://sheikhti1205.github.io/Norfold/terms.html") })
            RowDivider()
            SettingsRow("Open source licenses", icon = Icons.Outlined.Code, trailing = { RowChevron() }, onClick = { uriHandler.openUri("https://github.com/Norfold-App/Norfold") })
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
