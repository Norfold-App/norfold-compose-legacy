package com.norfold.app.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.NotificationsNone
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material.icons.outlined.TaskAlt
import androidx.compose.material.icons.outlined.Workspaces
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.norfold.app.cloud.NorfoldSupabase
import com.norfold.app.domain.AppIconFamily
import com.norfold.app.domain.BackupCategory
import com.norfold.app.domain.ControlLabelMode
import com.norfold.app.domain.EditorIconFamily
import com.norfold.app.domain.ThemeMode
import com.norfold.app.data.GoogleDriveBackupManifest
import com.norfold.app.ui.DocsUiState
import com.norfold.app.ui.DocsViewModel
import com.norfold.app.ui.EmailAuthPhase
import com.norfold.app.ui.EmailAuthOperation
import com.norfold.app.ui.OnboardingSetupPreferences
import com.norfold.app.ui.AccountCoordinatorState
import com.norfold.app.ui.CloudAuthProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.norfold.app.BuildConfig
import com.norfold.app.branding.NorfoldGlyphDrawIn
import com.norfold.app.branding.NorfoldInk
import com.norfold.app.branding.NorfoldInkOnDark
import com.norfold.app.branding.NorfoldWordmarkText
import com.norfold.app.branding.norfoldRiseIn
import com.norfold.app.ui.components.popIn
import com.norfold.app.ui.components.GoogleGMark
import com.norfold.app.ui.components.NorfoldDialog
import androidx.compose.ui.graphics.luminance
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

private const val OnboardingPreferences = "norfold_onboarding_draft"

@Composable
fun NorfoldOnboardingScreen(
    state: DocsUiState,
    viewModel: DocsViewModel,
    externalAuthBusy: Boolean = false,
    onRestoreBackup: (String) -> Unit = {},
    onGoogleSignIn: () -> Unit = {},
    onConnectGoogleDrive: () -> Unit = {},
    onEmailSignIn: (String, String, Boolean) -> Unit = { _, _, _ -> },
) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val preferences = remember { context.getSharedPreferences(OnboardingPreferences, 0) }
    var step by rememberSaveable { mutableStateOf(preferences.getInt("step", 0).coerceIn(0, 9)) }
    var fullName by rememberSaveable { mutableStateOf(preferences.getString("full_name", "") ?: "") }
    var displayName by rememberSaveable { mutableStateOf(preferences.getString("display_name", "") ?: "") }
    var workspaceName by rememberSaveable { mutableStateOf(preferences.getString("workspace", "My Workspace") ?: "My Workspace") }
    var workspaceTemplate by rememberSaveable { mutableStateOf(preferences.getString("template", "Start empty") ?: "Start empty") }
    var collaborationMode by rememberSaveable { mutableStateOf(preferences.getString("collaboration", "Just me for now") ?: "Just me for now") }
    var inviteCode by rememberSaveable { mutableStateOf("") }
    var avatarUri by rememberSaveable { mutableStateOf(preferences.getString("avatar_uri", "") ?: "") }
    var email by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var backupPassword by rememberSaveable { mutableStateOf("") }
    var backupFrequency by rememberSaveable { mutableStateOf(preferences.getString("backup_frequency", "Daily") ?: "Daily") }
    var restoreWorkspaceContent by rememberSaveable { mutableStateOf(true) }
    var restoreConversations by rememberSaveable { mutableStateOf(true) }
    var restoreDeviceSettings by rememberSaveable { mutableStateOf(true) }
    var restoredFromCloud by rememberSaveable { mutableStateOf(false) }
    var confirmStartFresh by rememberSaveable { mutableStateOf(false) }
    var signUp by rememberSaveable { mutableStateOf(false) }
    var emailUpdates by rememberSaveable { mutableStateOf(false) }
    var reminders by rememberSaveable { mutableStateOf(true) }
    var productTips by rememberSaveable { mutableStateOf(true) }
    var useRecommendedDefaults by rememberSaveable { mutableStateOf(preferences.getBoolean("recommended_defaults", true)) }
    var isFinishing by remember { mutableStateOf(false) }
    var setupTheme by rememberSaveable { mutableStateOf(enumValueOrDefault(preferences.getString("setup_theme", null), ThemeMode.System)) }
    var setupLabels by rememberSaveable { mutableStateOf(enumValueOrDefault(preferences.getString("setup_labels", null), ControlLabelMode.Adaptive)) }
    var setupAppIcons by rememberSaveable { mutableStateOf(enumValueOrDefault(preferences.getString("setup_app_icons", null), AppIconFamily.MaterialSymbolsRounded)) }
    var setupEditorIcons by rememberSaveable { mutableStateOf(enumValueOrDefault(preferences.getString("setup_editor_icons", null), EditorIconFamily.UseApp)) }
    var setupFontScale by rememberSaveable { mutableStateOf(preferences.getFloat("setup_font_scale", 1f).coerceIn(0.5f, 2f)) }
    var setupUiScale by rememberSaveable { mutableStateOf(preferences.getFloat("setup_ui_scale", 1f).coerceIn(0.5f, 2f)) }
    var setupToolbarScale by rememberSaveable { mutableStateOf(preferences.getFloat("setup_toolbar_scale", 1f).coerceIn(0.5f, 2f)) }
    var setupReduceMotion by rememberSaveable { mutableStateOf(preferences.getBoolean("setup_reduce_motion", false)) }
    val authPhase by viewModel.emailAuthPhase.collectAsStateWithLifecycle()
    val authOperation by viewModel.emailAuthOperation.collectAsStateWithLifecycle()
    val googleProfileSuggestion by viewModel.googleProfileSuggestion.collectAsStateWithLifecycle()
    val accountCoordinatorState by viewModel.accountCoordinatorState.collectAsStateWithLifecycle()
    val cloudAuthProvider by viewModel.cloudAuthProvider.collectAsStateWithLifecycle()
    val driveBackups by viewModel.driveBackups.collectAsStateWithLifecycle()
    val authBusy = authOperation != EmailAuthOperation.Idle || externalAuthBusy
    var resendCooldownSeconds by remember { mutableStateOf(viewModel.emailResendCooldownRemainingSeconds()) }
    LaunchedEffect(authPhase, authBusy) {
        do {
            resendCooldownSeconds = viewModel.emailResendCooldownRemainingSeconds()
            if (resendCooldownSeconds > 0L) delay(250L)
        } while (resendCooldownSeconds > 0L)
    }
    LaunchedEffect(authPhase) {
        if (authPhase == EmailAuthPhase.Completed) {
            viewModel.consumeEmailAuthCompleted()
            if (step <= 1) step = 3
        }
    }
    LaunchedEffect(googleProfileSuggestion) {
        val profile = googleProfileSuggestion ?: return@LaunchedEffect
        val suggestedFullName = profile.fullName.trim()
        val suggestedNickname = profile.nickname.trim()
        val suggestedAvatar = profile.avatarUri.orEmpty().trim()
        if (fullName.isBlank() && suggestedFullName.isNotBlank()) fullName = suggestedFullName
        if (displayName.isBlank() && suggestedNickname.isNotBlank()) displayName = suggestedNickname
        if (avatarUri.isBlank() && suggestedAvatar.isNotBlank()) avatarUri = suggestedAvatar
        if (step <= 1) step = 3
        viewModel.consumeGoogleProfileSuggestion()
    }
    LaunchedEffect(step, cloudAuthProvider, state.googleDriveConnected) {
        if (step == 8 && cloudAuthProvider == CloudAuthProvider.Google && !state.googleDriveConnected) {
            onConnectGoogleDrive()
        }
    }
    LaunchedEffect(accountCoordinatorState) {
        if (accountCoordinatorState == AccountCoordinatorState.Ready && step == 8) {
            restoredFromCloud = true
            step = 9
        }
    }
    val avatarPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching { context.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            avatarUri = uri.toString()
        }
    }

    LaunchedEffect(
        step, fullName, displayName, workspaceName, workspaceTemplate, collaborationMode, avatarUri,
        useRecommendedDefaults, setupTheme, setupLabels, setupAppIcons, setupEditorIcons,
        setupFontScale, setupUiScale, setupToolbarScale, setupReduceMotion,
        backupFrequency,
    ) {
        preferences.edit()
            .putInt("step", step)
            .putString("full_name", fullName)
            .putString("display_name", displayName)
            .putString("workspace", workspaceName)
            .putString("template", workspaceTemplate)
            .putString("collaboration", collaborationMode)
            .putString("avatar_uri", avatarUri)
            .putBoolean("recommended_defaults", useRecommendedDefaults)
            .putString("setup_theme", setupTheme.name)
            .putString("setup_labels", setupLabels.name)
            .putString("setup_app_icons", setupAppIcons.name)
            .putString("setup_editor_icons", setupEditorIcons.name)
            .putFloat("setup_font_scale", setupFontScale)
            .putFloat("setup_ui_scale", setupUiScale)
            .putFloat("setup_toolbar_scale", setupToolbarScale)
            .putBoolean("setup_reduce_motion", setupReduceMotion)
            .putString("backup_frequency", backupFrequency)
            .apply()
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier.fillMaxSize().navigationBarsPadding().imePadding(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                Modifier.weight(1f).fillMaxWidth().widthIn(max = 520.dp).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 18.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    // Restore (step 2) is entered from Welcome, not Sign in — back returns there.
                    if (step > 0) IconButton(onClick = { step = if (step == 2) 0 else step - 1 }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") }
                    else Spacer(Modifier.size(48.dp))
                    Spacer(Modifier.weight(1f))
                    if (step == 1) TextButton(onClick = { step = 3 }) { Text("Skip") }
                }
                AnimatedContent(
                    targetState = step,
                    transitionSpec = {
                        if (state.settings.reduceMotion) {
                            fadeIn(tween(90)) togetherWith fadeOut(tween(90))
                        } else if (targetState > initialState) {
                            (slideInHorizontally(tween(280)) { it / 4 } + fadeIn(tween(220))) togetherWith
                                (slideOutHorizontally(tween(220)) { -it / 5 } + fadeOut(tween(180)))
                        } else {
                            (slideInHorizontally(tween(280)) { -it / 4 } + fadeIn(tween(220))) togetherWith
                                (slideOutHorizontally(tween(220)) { it / 5 } + fadeOut(tween(180)))
                        }
                    },
                    label = "onboarding-step",
                ) { visibleStep ->
                // AnimatedContent lays its children out in a Box (top-start stacking); each step
                // function emits several sibling composables, so without this Column they overlap.
                Column(
                    Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                ) {
                when (visibleStep) {
                    0 -> WelcomeStep(
                        onNext = { step = 1 },
                        onRestore = { step = 2 },
                        onGoogle = onGoogleSignIn,
                        onOffline = { step = 3 },
                        onTerms = { uriHandler.openUri(BuildConfig.TERMS_URL) },
                        onPrivacy = { uriHandler.openUri(BuildConfig.PRIVACY_URL) },
                    )
                    1 -> when (val phase = authPhase) {
                        is EmailAuthPhase.AwaitSignupCode -> EmailCodeStep(phase.email, recovery = false, authBusy, resendCooldownSeconds, viewModel::verifyEmailCode, viewModel::resendEmailCode, viewModel::cancelEmailAuth)
                        is EmailAuthPhase.AwaitRecoveryCode -> EmailCodeStep(phase.email, recovery = true, authBusy, resendCooldownSeconds, viewModel::verifyEmailCode, viewModel::resendEmailCode, viewModel::cancelEmailAuth)
                        is EmailAuthPhase.AwaitNewPassword -> NewPasswordStep(authBusy, viewModel::completePasswordReset)
                        else -> SignInStep(email, { email = it }, password, { password = it }, signUp, { signUp = it }, authBusy, onGoogleSignIn, onEmailSignIn, { viewModel.beginPasswordReset(email.trim()) }) { step = 3 }
                    }
                    2 -> RestoreStep(backupPassword, { backupPassword = it }) { onRestoreBackup(backupPassword) }
                    3 -> ProfileStep(fullName, { fullName = it }, displayName, { displayName = it }, avatarUri) {
                        avatarPicker.launch(arrayOf("image/*"))
                    }
                    4 -> WorkspaceStep(workspaceName, { workspaceName = it }, workspaceTemplate) { workspaceTemplate = it }
                    5 -> CollaborationStep(
                        collaborationMode,
                        inviteCode,
                        { inviteCode = it },
                        signedIn = remember(visibleStep) { NorfoldSupabase.isSignedIn() },
                        onSignIn = { step = 1 },
                    ) { collaborationMode = it }
                    6 -> PreferencesStep(
                        useRecommendedDefaults = useRecommendedDefaults,
                        onRecommended = { useRecommendedDefaults = it },
                        theme = setupTheme,
                        onTheme = { setupTheme = it },
                        labels = setupLabels,
                        onLabels = { setupLabels = it },
                        appIcons = setupAppIcons,
                        onAppIcons = { setupAppIcons = it },
                        editorIcons = setupEditorIcons,
                        onEditorIcons = { setupEditorIcons = it },
                        fontScale = setupFontScale,
                        onFontScale = { setupFontScale = it },
                        uiScale = setupUiScale,
                        onUiScale = { setupUiScale = it },
                        toolbarScale = setupToolbarScale,
                        onToolbarScale = { setupToolbarScale = it },
                        reduceMotion = setupReduceMotion,
                        onReduceMotion = { setupReduceMotion = it },
                    )
                    7 -> NotificationStep(emailUpdates, { emailUpdates = it }, reminders, { reminders = it }, productTips, { productTips = it })
                    8 -> BackupSetupStep(
                        signedIn = NorfoldSupabase.isSignedIn(),
                        googleProvider = cloudAuthProvider == CloudAuthProvider.Google,
                        driveConnected = state.googleDriveConnected,
                        coordinatorState = accountCoordinatorState,
                        backups = driveBackups,
                        frequency = backupFrequency,
                        onFrequency = { backupFrequency = it },
                        secret = backupPassword,
                        onSecret = { backupPassword = it },
                        workspaceContent = restoreWorkspaceContent,
                        onWorkspaceContent = { restoreWorkspaceContent = it },
                        conversations = restoreConversations,
                        onConversations = { restoreConversations = it },
                        deviceSettings = restoreDeviceSettings,
                        onDeviceSettings = { restoreDeviceSettings = it },
                        onConnectDrive = onConnectGoogleDrive,
                        onRefresh = viewModel::discoverGoogleDriveBackups,
                        onRestore = { manifest ->
                            viewModel.restoreGoogleDriveBackup(
                                manifest,
                                backupPassword,
                                buildSet {
                                    if (restoreWorkspaceContent) add(BackupCategory.WorkspaceContent)
                                    if (restoreConversations) add(BackupCategory.ConversationsAndFiles)
                                    if (restoreDeviceSettings) add(BackupCategory.DeviceSettings)
                                },
                            )
                        },
                    )
                    else -> CompletionStep(if (restoredFromCloud) "Restored workspace" else workspaceName)
                }
                }
                }
            }
            if (step in 3..8) {
                OnboardingPrimaryButton(if (step >= 7) "Continue" else "Next") {
                    if (step == 8 && driveBackups.isNotEmpty()) {
                        confirmStartFresh = true
                    } else {
                        if (step == 8) viewModel.startFreshAfterBackupDiscovery()
                        step++
                    }
                }
            } else if (step == 9) {
                OnboardingPrimaryButton("Go to workspace", loading = isFinishing) {
                    isFinishing = true
                    viewModel.finishOnboarding(
                        workspaceName = workspaceName,
                        purpose = collaborationMode,
                        setup = OnboardingSetupPreferences(
                            useRecommendedDefaults = useRecommendedDefaults,
                            themeMode = setupTheme,
                            appLabelMode = setupLabels,
                            appIconFamily = setupAppIcons,
                            editorIconFamily = setupEditorIcons,
                            fontScale = setupFontScale,
                            uiScale = setupUiScale,
                            editorToolbarScale = setupToolbarScale,
                            reduceMotion = setupReduceMotion,
                        ),
                        fullName = fullName,
                        displayName = displayName,
                        template = workspaceTemplate,
                        emailUpdates = emailUpdates,
                        reminders = reminders,
                        productTips = productTips,
                        backupFrequency = backupFrequency,
                        preserveRestoredWorkspace = restoredFromCloud,
                        avatarUri = avatarUri,
                        inviteToken = inviteCode.trim().takeIf { collaborationMode == "Join a team" && it.isNotBlank() },
                        openMembers = collaborationMode == "Invite people",
                        // Wizard state is dropped only after onboarding is durably persisted, so a
                        // failure mid-finish restarts with everything the user already entered.
                        onFinished = { preferences.edit().clear().apply() },
                    )
                }
            }
            if (step != 2) StepDots(step)
        }
    }
    if (confirmStartFresh) {
        NorfoldDialog(
            onDismissRequest = { confirmStartFresh = false },
            title = { Text("Start without restoring?") },
            text = {
                Text("A backup was found. Starting fresh keeps that backup in Google Drive, but none of its selected data will be added to this device.")
            },
            dismissButton = { TextButton(onClick = { confirmStartFresh = false }) { Text("Review backup") } },
            confirmButton = {
                Button(onClick = {
                    confirmStartFresh = false
                    viewModel.startFreshAfterBackupDiscovery()
                    step = 9
                }) { Text("Start fresh") }
            },
        )
    }
}

@Composable
private fun WelcomeStep(
    onNext: () -> Unit,
    onRestore: () -> Unit,
    onGoogle: () -> Unit,
    onOffline: () -> Unit,
    onTerms: () -> Unit,
    onPrivacy: () -> Unit,
) {
    NorfoldWordmark()
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = .35f), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Welcome to\nNorfold", fontSize = 34.sp, lineHeight = 38.sp, fontWeight = FontWeight.Black)
            Text("A private place for docs, tasks, calendar, chat, and focused work.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 17.sp, lineHeight = 24.sp)
            Text("DOCS  |  CALENDAR  |  CHAT  |  TASKS", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold, fontSize = 11.sp)
        }
    }
    OnboardingPrimaryButton("Get started", onClick = onNext)
    // Stacked full-width so the label never wraps against its icon on narrow screens.
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedButton(onClick = onRestore, Modifier.fillMaxWidth().height(52.dp)) {
            Icon(Icons.Outlined.Restore, null, Modifier.size(20.dp))
            Spacer(Modifier.size(10.dp))
            Text("Restore backup", maxLines = 1)
        }
        OutlinedButton(onClick = onGoogle, Modifier.fillMaxWidth().height(52.dp)) {
            GoogleGMark(size = 18.dp)
            Spacer(Modifier.size(10.dp))
            Text("Sign in with Google", maxLines = 1)
        }
    }
    Text("What you can do", Modifier.fillMaxWidth(), fontWeight = FontWeight.Bold, fontSize = 18.sp)
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            FeatureCard(Icons.Outlined.Description, "Docs", "Capture ideas and keep them organized.", Modifier.weight(1f))
            FeatureCard(Icons.Outlined.TaskAlt, "Tasks", "Plan your day and get things done.", Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            FeatureCard(Icons.Outlined.CalendarMonth, "Calendar", "Manage your schedule with ease.", Modifier.weight(1f))
            FeatureCard(Icons.Outlined.ChatBubbleOutline, "Chat", "Private conversations that stay with you.", Modifier.weight(1f))
        }
    }
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = .28f)) {
        Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Icon(Icons.Outlined.Lock, null, Modifier.size(46.dp), tint = MaterialTheme.colorScheme.primary)
            Column { Text("Private by default", fontWeight = FontWeight.Bold, fontSize = 18.sp); Text("Your data stays on your device unless you choose to sync.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = onTerms) { Text("Terms") }; Text("•"); TextButton(onClick = onPrivacy) { Text("Privacy") }; Text("•"); TextButton(onClick = onOffline) { Text("Continue offline") }
    }
}

@Composable
private fun SignInStep(email: String, onEmail: (String) -> Unit, password: String, onPassword: (String) -> Unit, signUp: Boolean, onMode: (Boolean) -> Unit, busy: Boolean, onGoogle: () -> Unit, onEmailAction: (String, String, Boolean) -> Unit, onForgotPassword: () -> Unit, onOffline: () -> Unit) {
    Text("Let's get you in", fontSize = 30.sp, fontWeight = FontWeight.Black)
    Text("Sign in to access your workspace or create a new account.", color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(10.dp)).padding(3.dp)) {
        listOf(false to "Sign in", true to "Sign up").forEach { (mode, label) -> Surface(Modifier.weight(1f).clickable(enabled = !busy) { onMode(mode) }, color = if (signUp == mode) MaterialTheme.colorScheme.surface else Color.Transparent, shape = RoundedCornerShape(8.dp)) { Text(label, Modifier.padding(10.dp), textAlign = TextAlign.Center, color = if (signUp == mode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant) } }
    }
    OutlinedButton(onClick = onGoogle, modifier = Modifier.fillMaxWidth().height(52.dp), enabled = !busy) {
        GoogleGMark(size = 18.dp)
        Spacer(Modifier.size(10.dp))
        Text("Continue with Google")
    }
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) { Text("Apple sign-in will be available in a later release", Modifier.padding(15.dp), textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    HorizontalDivider()
    OutlinedTextField(email, onEmail, Modifier.fillMaxWidth(), label = { Text("Email address") }, leadingIcon = { Icon(Icons.Outlined.Email, null) }, singleLine = true, enabled = !busy)
    OutlinedTextField(password, onPassword, Modifier.fillMaxWidth(), label = { Text("Password") }, visualTransformation = PasswordVisualTransformation(), singleLine = true, enabled = !busy)
    OnboardingPrimaryButton(if (signUp) "Create account" else "Sign in", loading = busy) { onEmailAction(email.trim(), password, signUp) }
    if (!signUp) TextButton(onClick = onForgotPassword, enabled = !busy) { Text("Forgot password?") }
    TextButton(onClick = onOffline, enabled = !busy) { Text("Continue offline") }
}

@Composable
private fun EmailCodeStep(email: String, recovery: Boolean, busy: Boolean, resendCooldownSeconds: Long, onVerify: (String) -> Unit, onResend: () -> Unit, onBack: () -> Unit) {
    var code by rememberSaveable { mutableStateOf("") }
    StepIcon(Icons.Outlined.Email)
    Text(if (recovery) "Reset your password" else "Check your email", fontSize = 30.sp, fontWeight = FontWeight.Black)
    Text(
        "Enter the 6-digit code we sent to $email.",
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
    OutlinedTextField(
        code,
        { value -> code = value.filter(Char::isDigit).take(6) },
        Modifier.fillMaxWidth(),
        label = { Text("6-digit code") },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        singleLine = true,
        enabled = !busy,
    )
    OnboardingPrimaryButton(if (recovery) "Verify code" else "Verify and continue", loading = busy) { onVerify(code) }
    TextButton(onClick = onResend, enabled = !busy && resendCooldownSeconds == 0L) {
        Text(if (resendCooldownSeconds > 0L) "Resend in ${resendCooldownSeconds}s" else "Resend code")
    }
    TextButton(onClick = onBack, enabled = !busy) { Text(if (recovery) "Back to sign in" else "Use a different email") }
}

@Composable
private fun NewPasswordStep(busy: Boolean, onSubmit: (String) -> Unit) {
    var newPassword by rememberSaveable { mutableStateOf("") }
    var confirm by rememberSaveable { mutableStateOf("") }
    val mismatch = confirm.isNotEmpty() && confirm != newPassword
    StepIcon(Icons.Outlined.Lock)
    Text("Choose a new password", fontSize = 30.sp, fontWeight = FontWeight.Black)
    Text("Code accepted. Set a new password for your account.", color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    OutlinedTextField(newPassword, { newPassword = it }, Modifier.fillMaxWidth(), label = { Text("New password") }, visualTransformation = PasswordVisualTransformation(), singleLine = true, enabled = !busy)
    OutlinedTextField(
        confirm,
        { confirm = it },
        Modifier.fillMaxWidth(),
        label = { Text("Confirm password") },
        visualTransformation = PasswordVisualTransformation(),
        singleLine = true,
        isError = mismatch,
        supportingText = { if (mismatch) Text("Passwords don't match") },
        enabled = !busy,
    )
    OnboardingPrimaryButton("Update password", loading = busy, enabled = !mismatch && newPassword.isNotEmpty()) { onSubmit(newPassword) }
}

@Composable
private fun RestoreStep(secret: String, onSecret: (String) -> Unit, onRestore: () -> Unit) {
    StepIcon(Icons.Outlined.CloudDownload)
    Text("Restore your data", fontSize = 30.sp, fontWeight = FontWeight.Black)
    Text("Enter the backup password, then choose an encrypted Norfold backup. When the restore finishes you'll land in your workspace.", color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    OutlinedTextField(secret, onSecret, Modifier.fillMaxWidth(), label = { Text("Backup password") }, visualTransformation = PasswordVisualTransformation())
    listOf("Docs", "Tasks", "Calendar", "Files & media", "Settings").forEach { label -> Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(10.dp), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) { Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) { Text(label, Modifier.weight(1f)); Icon(Icons.Outlined.Restore, null, tint = MaterialTheme.colorScheme.primary) } } }
    OnboardingPrimaryButton("Choose backup", enabled = secret.isNotBlank(), onClick = onRestore)
}

@Composable
private fun ProfileStep(fullName: String, onFullName: (String) -> Unit, displayName: String, onDisplayName: (String) -> Unit, avatarUri: String, onPickAvatar: () -> Unit) {
    Surface(
        Modifier.size(72.dp).clickable(onClick = onPickAvatar),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = .55f),
    ) {
        if (avatarUri.isNotBlank()) {
            AsyncImage(avatarUri, "Profile image", Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } else {
            Icon(Icons.Outlined.Person, "Choose profile image", Modifier.padding(20.dp), tint = MaterialTheme.colorScheme.primary)
        }
    }
    Text("Tell us about you", fontSize = 28.sp, fontWeight = FontWeight.Black)
    Text("This helps personalize your experience.", color = MaterialTheme.colorScheme.onSurfaceVariant)
    OutlinedTextField(fullName, onFullName, Modifier.fillMaxWidth(), label = { Text("Full name") }, singleLine = true)
    OutlinedTextField(displayName, onDisplayName, Modifier.fillMaxWidth(), label = { Text("What should we call you?") }, singleLine = true)
    OutlinedTextField("(GMT+06:00) Dhaka, Bangladesh", {}, Modifier.fillMaxWidth(), label = { Text("Timezone") }, readOnly = true)
}

@Composable
private fun WorkspaceStep(name: String, onName: (String) -> Unit, template: String, onTemplate: (String) -> Unit) {
    StepIcon(Icons.Outlined.Workspaces)
    Text("Create your workspace", fontSize = 28.sp, fontWeight = FontWeight.Black)
    Text("Give your workspace a name.", color = MaterialTheme.colorScheme.onSurfaceVariant)
    OutlinedTextField(name, { onName(it.take(50)) }, Modifier.fillMaxWidth(), label = { Text("Workspace name") }, supportingText = { Text("${name.length}/50") }, singleLine = true)
    OutlinedTextField("Personal", {}, Modifier.fillMaxWidth(), label = { Text("Workspace type") }, readOnly = true)
    Text("Starting point", Modifier.fillMaxWidth(), fontWeight = FontWeight.Bold)
    listOf(
        "Start empty" to "A clean workspace with no demo content",
        "Norfold Guide" to "A complete interactive tour of docs, tasks, tables, charts, and rich blocks",
        "Personal organizer" to "A daily doc and a weekly planning task",
        "Study planner" to "Study docs and revision tasks",
        "Team workspace" to "A shared brief, task, and collaboration chat",
    ).forEach { (label, detail) ->
        Surface(
            Modifier.fillMaxWidth().clickable { onTemplate(label) },
            shape = RoundedCornerShape(12.dp),
            border = BorderStroke(1.dp, if (template == label) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
            color = if (template == label) MaterialTheme.colorScheme.primaryContainer.copy(alpha = .35f) else MaterialTheme.colorScheme.surface,
        ) {
            Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { Text(label, fontWeight = FontWeight.Bold); Text(detail, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp) }
                if (template == label) Icon(Icons.Outlined.CheckCircle, null, tint = MaterialTheme.colorScheme.primary)
            }
        }
    }
    Text("You can always change this later.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, modifier = Modifier.fillMaxWidth())
}

@Composable
private fun CollaborationStep(selected: String, inviteCode: String, onInviteCode: (String) -> Unit, signedIn: Boolean, onSignIn: () -> Unit, onSelect: (String) -> Unit) {
    StepIcon(Icons.Outlined.Groups)
    Text("Collaborate your way", fontSize = 28.sp, fontWeight = FontWeight.Black)
    Text("Invite people or skip this step.", color = MaterialTheme.colorScheme.onSurfaceVariant)
    ChoiceCard("Just me for now", "I'll invite people later", selected == "Just me for now") { onSelect("Just me for now") }
    ChoiceCard("Invite people", "Opens workspace members after setup so you can send invitations", selected == "Invite people") { onSelect("Invite people") }
    ChoiceCard("Join a team", "Use an invite code to join", selected == "Join a team") { onSelect("Join a team") }
    if (selected == "Join a team") {
        if (!signedIn) {
            Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.errorContainer) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Sign in first", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onErrorContainer)
                    Text(
                        "Joining a team needs the account the invite was sent to. Sign in, then come back to this step.",
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                    TextButton(onClick = onSignIn) { Text("Sign in") }
                }
            }
        }
        OutlinedTextField(
            inviteCode,
            onInviteCode,
            Modifier.fillMaxWidth(),
            label = { Text("Invite code") },
            supportingText = { Text("Paste the code your teammate shared. Requires the invited account to be signed in.") },
            singleLine = true,
            enabled = signedIn,
        )
    }
}

@Composable
private fun NotificationStep(email: Boolean, onEmail: (Boolean) -> Unit, reminders: Boolean, onReminders: (Boolean) -> Unit, tips: Boolean, onTips: (Boolean) -> Unit) {
    StepIcon(Icons.Outlined.NotificationsNone)
    Text("Stay in the loop", fontSize = 28.sp, fontWeight = FontWeight.Black)
    Text("Choose what you want to receive.", color = MaterialTheme.colorScheme.onSurfaceVariant)
    ToggleCard("Email updates", "Important updates and activity", email, onEmail)
    ToggleCard("Task reminders", "Reminders for tasks and deadlines", reminders, onReminders)
    ToggleCard("Product tips", "Tips to help you get more done", tips, onTips)
}

@Composable
private fun BackupSetupStep(
    signedIn: Boolean,
    googleProvider: Boolean,
    driveConnected: Boolean,
    coordinatorState: AccountCoordinatorState,
    backups: List<GoogleDriveBackupManifest>,
    frequency: String,
    onFrequency: (String) -> Unit,
    secret: String,
    onSecret: (String) -> Unit,
    workspaceContent: Boolean,
    onWorkspaceContent: (Boolean) -> Unit,
    conversations: Boolean,
    onConversations: (Boolean) -> Unit,
    deviceSettings: Boolean,
    onDeviceSettings: (Boolean) -> Unit,
    onConnectDrive: () -> Unit,
    onRefresh: () -> Unit,
    onRestore: (GoogleDriveBackupManifest) -> Unit,
) {
    StepIcon(Icons.Outlined.CloudDownload)
    Text("Backup and restore", fontSize = 28.sp, fontWeight = FontWeight.Black)
    Text(
        when {
            !signedIn -> "Local-only work stays on this device. Connect an account later to enable automatic encrypted backup."
            driveConnected -> "Automatic encrypted backup is on. New backups replace this device's earlier snapshot without deleting backups from your other devices."
            googleProvider -> "Google sign-in is complete. Approve the separate Drive app-data permission to finish automatic backup."
            else -> "Your Norfold account is ready. Connect a Google account for encrypted backup; the Google account is only the storage provider."
        },
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
    if (signedIn && !driveConnected) {
        OutlinedButton(onClick = onConnectDrive, modifier = Modifier.fillMaxWidth().height(52.dp)) {
            GoogleGMark(size = 18.dp)
            Spacer(Modifier.size(10.dp))
            Text(if (googleProvider) "Allow encrypted Drive backup" else "Connect Google for backup")
        }
    }
    if (driveConnected) {
        Text("Backup schedule", Modifier.fillMaxWidth(), fontWeight = FontWeight.Bold)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("Daily", "Weekly", "Monthly").forEach { option ->
                Surface(
                    modifier = Modifier.weight(1f).clickable { onFrequency(option) },
                    shape = RoundedCornerShape(10.dp),
                    color = if (frequency == option) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, if (frequency == option) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
                ) { Text(option, Modifier.padding(vertical = 11.dp), textAlign = TextAlign.Center, fontWeight = if (frequency == option) FontWeight.Bold else FontWeight.Normal) }
            }
        }
        OutlinedTextField(
            value = secret,
            onValueChange = onSecret,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Backup password (optional)") },
            supportingText = { Text(if (secret.isBlank()) "Leave blank to let Norfold manage encryption automatically on this device." else "This password is never uploaded. You will need it to restore on a new device.") },
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true,
        )
        if (coordinatorState == AccountCoordinatorState.DiscoveringBackupsAndMemberships) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                Text("Checking Drive and workspace memberships…")
            }
        }
        (coordinatorState as? AccountCoordinatorState.RecoverableError)?.let {
            Surface(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(12.dp)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(it.message, color = MaterialTheme.colorScheme.onErrorContainer)
                    TextButton(onClick = onRefresh) { Text("Try again") }
                }
            }
        }
        if (backups.isNotEmpty()) {
            Text("Backups found", Modifier.fillMaxWidth(), fontWeight = FontWeight.Bold, fontSize = 18.sp)
            ToggleCard("Workspace content", "Docs, Notes, Tasks, calendar, links, comments, and history", workspaceContent, onWorkspaceContent)
            ToggleCard("Conversations and files", "Chat messages, channels, attachments, and Files", conversations, onConversations)
            ToggleCard("Device settings", "Appearance, editor, and accessibility preferences", deviceSettings, onDeviceSettings)
            backups.forEach { manifest ->
                Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        Text(manifest.deviceName, fontWeight = FontWeight.Bold)
                        Text(
                            "${manifest.appVersion.ifBlank { "Earlier build" }} · ${manifest.encryptedSizeBytes / 1024L} KB · ${manifest.counts.workspaceContent} workspace items · ${manifest.counts.conversationsAndFiles} chat/file items",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 12.sp,
                        )
                        Button(
                            onClick = { onRestore(manifest) },
                            enabled = workspaceContent || conversations || deviceSettings,
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Restore selected data") }
                    }
                }
            }
            Text("Continue starts fresh without deleting or overwriting these backups.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
        } else if (coordinatorState !is AccountCoordinatorState.DiscoveringBackupsAndMemberships) {
            Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = .3f)) {
                Text("No earlier Norfold backup was found for this Google account.", Modifier.padding(14.dp))
            }
        }
    }
}

@Composable
private fun PreferencesStep(
    useRecommendedDefaults: Boolean,
    onRecommended: (Boolean) -> Unit,
    theme: ThemeMode,
    onTheme: (ThemeMode) -> Unit,
    labels: ControlLabelMode,
    onLabels: (ControlLabelMode) -> Unit,
    appIcons: AppIconFamily,
    onAppIcons: (AppIconFamily) -> Unit,
    editorIcons: EditorIconFamily,
    onEditorIcons: (EditorIconFamily) -> Unit,
    fontScale: Float,
    onFontScale: (Float) -> Unit,
    uiScale: Float,
    onUiScale: (Float) -> Unit,
    toolbarScale: Float,
    onToolbarScale: (Float) -> Unit,
    reduceMotion: Boolean,
    onReduceMotion: (Boolean) -> Unit,
) {
    StepIcon(Icons.Outlined.CheckCircle)
    Text("Set up Norfold your way", fontSize = 28.sp, fontWeight = FontWeight.Black)
    Text("Use the recommended setup, or customize the important interface and editor choices now. Nothing is saved until setup finishes.", color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    ChoiceCard("Recommended defaults", "Norfold theme, adaptive labels, readable sizing and editor icons that follow the app", useRecommendedDefaults) { onRecommended(true) }
    ChoiceCard("Customize now", "Choose app appearance and editor controls separately", !useRecommendedDefaults) { onRecommended(false) }
    if (!useRecommendedDefaults) {
        OnboardingPreferenceGroup("Appearance") {
            PreferenceChoices("Theme", ThemeMode.entries, theme, onTheme) { it.name }
            PreferenceChoices("Labels", ControlLabelMode.entries, labels, onLabels) {
                when (it) {
                    ControlLabelMode.Adaptive -> "Adaptive"
                    ControlLabelMode.IconsOnly -> "Icons"
                    ControlLabelMode.IconsAndText -> "Icons + text"
                }
            }
            PreferenceChoices(
                "App UI icons",
                listOf(AppIconFamily.MaterialSymbolsRounded, AppIconFamily.MaterialSymbolsOutlined, AppIconFamily.Lucide),
                appIcons,
                onAppIcons,
            ) { it.name.removePrefix("MaterialSymbols").replace("Rounded", "Material rounded").replace("Outlined", "Material outline") }
            OnboardingScalePreference("Text size", fontScale, onFontScale)
            OnboardingScalePreference("App UI size", uiScale, onUiScale)
        }
        OnboardingPreferenceGroup("Editor") {
            PreferenceChoices(
                "Editor icons",
                listOf(EditorIconFamily.UseApp, EditorIconFamily.MaterialSymbolsRounded, EditorIconFamily.Lucide),
                editorIcons,
                onEditorIcons,
            ) { if (it == EditorIconFamily.UseApp) "Follow app" else it.name.removePrefix("MaterialSymbols").replace("Rounded", "Material rounded") }
            OnboardingScalePreference("Toolbar size", toolbarScale, onToolbarScale)
        }
        ToggleCard("Reduce motion", "Use simpler transitions and less animation", reduceMotion, onReduceMotion)
    }
    Text("Every choice remains available later in Appearance, Editor & Markdown, and Accessibility settings.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, modifier = Modifier.fillMaxWidth())
}

@Composable
private fun OnboardingPreferenceGroup(title: String, content: @Composable () -> Unit) {
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, fontWeight = FontWeight.Bold, fontSize = 17.sp)
            content()
        }
    }
}

@Composable
private fun <T> PreferenceChoices(label: String, values: List<T>, selected: T, onSelect: (T) -> Unit, name: (T) -> String) {
    Text(label, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
    values.forEach { value ->
        Surface(
            Modifier.fillMaxWidth().clickable { onSelect(value) },
            shape = RoundedCornerShape(10.dp),
            color = if (value == selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = .45f) else MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, if (value == selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
        ) {
            Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(name(value), Modifier.weight(1f), fontWeight = if (value == selected) FontWeight.Bold else FontWeight.Normal)
                if (value == selected) Icon(Icons.Outlined.CheckCircle, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
            }
        }
    }
}

@Composable
private fun OnboardingScalePreference(label: String, value: Float, onValue: (Float) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            Text("${(value * 100).roundToInt()}%", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold, fontSize = 12.sp)
        }
        TextButton(onClick = { onValue(1f) }, enabled = value != 1f) { Text("Reset") }
    }
    Slider(value = value, onValueChange = { onValue((it * 20).roundToInt() / 20f) }, valueRange = 0.5f..2f)
}

@Composable
private fun CompletionStep(workspace: String) {
    // The brand pop on the moment that earns it.
    Surface(Modifier.size(92.dp).popIn(), shape = CircleShape, color = MaterialTheme.colorScheme.primary) { Icon(Icons.Outlined.Check, null, Modifier.padding(24.dp), tint = Color.White) }
    Text("You're all set!", fontSize = 30.sp, fontWeight = FontWeight.Black)
    Text("Welcome to Norfold. Let's get things done.", color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) { listOf("Workspace created: $workspace", "Profile set", "You're ready to go").forEach { Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Outlined.CheckCircle, null, tint = com.norfold.app.ui.SemanticColors.signalGreen); Text(it, Modifier.padding(start = 10.dp)) } } }
    }
}

@Composable private fun NorfoldWordmark() {
    // First thing a new user sees: the brand introduces itself — glyph draws in,
    // dot pops, wordmark rises. Brand ink, not theme tint, so it matches the logo.
    val ink = if (MaterialTheme.colorScheme.background.luminance() < 0.5f) NorfoldInkOnDark else NorfoldInk
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            NorfoldGlyphDrawIn(size = 42.dp, ink = ink)
            NorfoldWordmarkText(fontSize = 28.sp, color = ink, modifier = Modifier.norfoldRiseIn())
        }
        Text("Your private workspace.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.norfoldRiseIn())
    }
}
@Composable private fun StepIcon(icon: ImageVector) { Surface(Modifier.size(72.dp), shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = .55f)) { Icon(icon, null, Modifier.padding(20.dp), tint = MaterialTheme.colorScheme.primary) } }
@Composable private fun FeatureCard(icon: ImageVector, title: String, detail: String, modifier: Modifier = Modifier) { Surface(modifier, shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) { Row(Modifier.padding(13.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) { Icon(icon, null, tint = MaterialTheme.colorScheme.primary); Column { Text(title, fontWeight = FontWeight.Bold); Text(detail, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp, lineHeight = 15.sp) } } } }
@Composable private fun ChoiceCard(title: String, subtitle: String, selected: Boolean, onClick: () -> Unit) { Surface(Modifier.fillMaxWidth().clickable(onClick = onClick), shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant)) { Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) { Box(Modifier.size(18.dp).background(if (selected) MaterialTheme.colorScheme.primary else Color.Transparent, CircleShape)); Column(Modifier.padding(start = 14.dp)) { Text(title, fontWeight = FontWeight.Bold); Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp) } } } }
@Composable private fun ToggleCard(title: String, subtitle: String, checked: Boolean, onChecked: (Boolean) -> Unit) { Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) { Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text(title, fontWeight = FontWeight.Bold); Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp) }; Switch(checked, onChecked) } } }

@Composable
private fun OnboardingPrimaryButton(label: String, loading: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    Button(onClick, Modifier.fillMaxWidth().widthIn(max = 472.dp).padding(horizontal = 24.dp, vertical = 8.dp).height(52.dp), enabled = enabled && !loading, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)) {
        if (loading) {
            CircularProgressIndicator(Modifier.size(20.dp), color = MaterialTheme.colorScheme.onPrimary, strokeWidth = 2.dp)
            Spacer(Modifier.size(10.dp))
            Text("Please wait…", fontWeight = FontWeight.Bold)
        } else {
            Text(label, fontWeight = FontWeight.Bold); Spacer(Modifier.weight(1f)); Icon(Icons.AutoMirrored.Outlined.ArrowForward, null)
        }
    }
}

@Composable
private fun StepDots(step: Int) {
    Row(Modifier.padding(bottom = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        repeat(10) { index -> Box(Modifier.size(if (index == step) 9.dp else 7.dp).background(if (index == step) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, CircleShape)) }
    }
}

private inline fun <reified T : Enum<T>> enumValueOrDefault(raw: String?, fallback: T): T =
    enumValues<T>().firstOrNull { it.name == raw } ?: fallback
