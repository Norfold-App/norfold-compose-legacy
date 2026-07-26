package com.norfold.app.ui

import android.app.Activity
import android.Manifest
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import android.content.pm.PackageManager
import androidx.activity.compose.PredictiveBackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Menu
import com.norfold.app.ui.components.NorfoldDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.core.content.ContextCompat
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.norfold.app.branding.NorfoldIntroTotalMillis
import com.norfold.app.branding.palette
import com.norfold.app.data.GoogleDriveOAuth
import com.norfold.app.data.BiometricVaultKeyStore
import com.norfold.app.cloud.GoogleIdentityClient
import com.norfold.app.cloud.NorfoldSupabase
import com.google.android.gms.auth.api.identity.Identity
import com.norfold.app.domain.Destination
import com.norfold.app.domain.DocOverlapMode
import com.norfold.app.domain.SyncFolderRequest
import com.norfold.app.domain.TaskItem
import com.norfold.app.ui.components.ComposeLoadingScreen
import com.norfold.app.ui.components.MobileBottomBar
import com.norfold.app.ui.feedback.NorfoldHaptic
import com.norfold.app.ui.feedback.UiFeedbackDuration
import com.norfold.app.ui.feedback.UiFeedbackTone
import com.norfold.app.ui.feedback.performNorfoldHaptic
import com.norfold.app.ui.screens.LockScreen
import com.norfold.app.ui.editor.BoundedDocumentEditorScreen
import com.norfold.app.ui.editor.FlowNoteEditorScreen
import com.norfold.app.ui.screens.NotebookScreen
import com.norfold.app.ui.screens.NotesHome
import com.norfold.app.ui.screens.LibraryKind
import com.norfold.app.ui.screens.ObjectDetailScreen
import com.norfold.app.ui.screens.ChatHomeScreen
import com.norfold.app.ui.screens.ChatScreen
import com.norfold.app.ui.screens.ConflictReviewScreen
import com.norfold.app.ui.screens.SearchScreen
import com.norfold.app.ui.screens.SettingsScreen
import com.norfold.app.ui.screens.SectionSidebarOverlay
import com.norfold.app.ui.screens.Sidebar
import com.norfold.app.ui.screens.TagsScreen
import com.norfold.app.ui.tasks.TasksBoardScreen
import com.norfold.app.ui.screens.WorkspaceHubScreen
import com.norfold.app.ui.screens.FilesLibraryScreen
import com.norfold.app.ui.screens.DatabaseScreen
import com.norfold.app.ui.screens.GraphScreen
import com.norfold.app.ui.screens.ActivityScreen
import com.norfold.app.ui.screens.TemplatesScreen
import com.norfold.app.ui.screens.CommandPaletteScreen
import com.norfold.app.ui.screens.CalendarWorkspaceScreen
import com.norfold.app.ui.screens.GoalsScreen
import com.norfold.app.ui.screens.NorfoldOnboardingScreen
import kotlinx.coroutines.launch
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.collect

@Composable
fun NorfoldRoot(viewModel: DocsViewModel = viewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val initializationState by viewModel.initializationState.collectAsStateWithLifecycle()
    val scalePreview by viewModel.scalePreview.collectAsStateWithLifecycle()
    val view = LocalView.current
    val configuration = LocalConfiguration.current
    val context = view.context
    val settingsScreenClass = when {
        configuration.screenWidthDp >= 840 -> "Expanded"
        configuration.screenWidthDp >= 600 -> "Medium"
        else -> "Compact"
    }
    LaunchedEffect(initializationState, settingsScreenClass) {
        if (initializationState == AppInitializationState.Ready) {
            viewModel.registerSettingsDevice(settingsScreenClass)
        }
    }
    val googleAuthorizationClient = remember(context) { Identity.getAuthorizationClient(context) }
    val googleIdentityClient = remember { GoogleIdentityClient() }
    val coroutineScope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var googleSignInBusy by remember { mutableStateOf(false) }
    val permissionPrefs = remember(context) { context.getSharedPreferences("norfold_permission_prompts", Context.MODE_PRIVATE) }
    var showNotificationPermissionPrompt by remember { mutableStateOf(false) }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        permissionPrefs.edit().putBoolean("notifications_asked", true).apply()
        showNotificationPermissionPrompt = false
        viewModel.showMessage(if (granted) "Notifications enabled" else "Notifications are off. You can enable them later in Android settings.")
    }
    LaunchedEffect(initializationState, state.settings.onboardingComplete, state.settings.notificationPush) {
        if (
            initializationState == AppInitializationState.Ready &&
            state.settings.onboardingComplete &&
            state.settings.notificationPush &&
            android.os.Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
            !permissionPrefs.getBoolean("notifications_asked", false)
        ) {
            showNotificationPermissionPrompt = true
        }
    }
    // Hold the branded loader through the logo intro on cold start so the animation always
    // completes instead of flashing away when initialization is fast. Reduce-motion skips the hold.
    var brandIntroHold by remember { mutableStateOf(true) }
    LaunchedEffect(state.settings.reduceMotion) {
        if (!state.settings.reduceMotion) delay(NorfoldIntroTotalMillis + 160L)
        brandIntroHold = false
    }
    var pendingSyncRequest by remember { mutableStateOf<SyncFolderRequest?>(null) }
    val syncFolderLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        viewModel.handleSyncFolderPicked(uri, pendingSyncRequest)
        pendingSyncRequest = null
    }
    val backupFolderLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        viewModel.handleBackupFolderPicked(uri)
    }
    val markdownImportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        viewModel.importMarkdown(uri)
    }
    var pendingBackupImportSecret by remember { mutableStateOf("") }
    val backupImportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        viewModel.importBackupFromUri(pendingBackupImportSecret, uri)
        pendingBackupImportSecret = ""
    }
    val attachmentLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        viewModel.attachFileToSelectedNote(uri)
    }
    val embedLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        viewModel.embedFileToSelectedNote(uri)
    }
    val coverLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        viewModel.setSelectedNoteCover(uri)
    }
    // Multi-select feeds the sequential upload queue — "no limit on number of files per send".
    val chatAttachmentLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        viewModel.sendChatAttachments(uris)
    }
    // Images and video go through the system Photo Picker: gallery-style UI, no storage
    // permission, and no access to anything the user didn't pick. Documents stay on SAF above.
    val chatMediaPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(),
    ) { uris ->
        viewModel.sendChatAttachments(uris)
    }
    // Saveable: TakePicture routinely outlives this activity (rotation/process death),
    // and the result callback needs the target Uri after state restoration.
    var pendingChatCameraUri by rememberSaveable { mutableStateOf<Uri?>(null) }
    val chatCameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        pendingChatCameraUri?.takeIf { success }?.let(viewModel::sendChatAttachment)
        pendingChatCameraUri = null
    }
    val workspaceFileLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        viewModel.addWorkspaceFile(uri)
    }
    var pendingTaskAttachmentId by rememberSaveable { mutableStateOf<Long?>(null) }
    val taskAttachmentLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        pendingTaskAttachmentId?.let { id -> state.tasks.find { it.id == id } }?.let { viewModel.attachFileToTask(it, uri) }
        pendingTaskAttachmentId = null
    }
    val googleDriveAuthLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        runCatching { googleAuthorizationClient.getAuthorizationResultFromIntent(result.data) }
            .onSuccess { viewModel.connectGoogleDrive(it.accessToken) }
            .onFailure(viewModel::reportGoogleDriveAuthorizationFailure)
    }
    val requestGoogleDriveAuthorization: () -> Unit = {
        googleAuthorizationClient.authorize(GoogleDriveOAuth.authorizationRequest())
            .addOnSuccessListener { result ->
                if (result.hasResolution()) {
                    result.pendingIntent?.intentSender?.let { sender ->
                        googleDriveAuthLauncher.launch(IntentSenderRequest.Builder(sender).build())
                    } ?: viewModel.reportGoogleDriveAuthorizationFailure(null)
                } else {
                    viewModel.connectGoogleDrive(result.accessToken)
                }
            }
            .addOnFailureListener(viewModel::reportGoogleDriveAuthorizationFailure)
    }

    LaunchedEffect(viewModel, snackbar, view) {
        viewModel.feedbackEvents.collect { feedback ->
            when (feedback.tone) {
                UiFeedbackTone.Success -> view.performNorfoldHaptic(NorfoldHaptic.Confirm)
                UiFeedbackTone.Error -> view.performNorfoldHaptic(NorfoldHaptic.Reject)
                UiFeedbackTone.Info, UiFeedbackTone.Warning -> Unit
            }
            val result = snackbar.showSnackbar(
                message = feedback.message,
                actionLabel = feedback.actionLabel,
                withDismissAction = feedback.actionLabel != null,
                duration = when (feedback.duration) {
                    UiFeedbackDuration.Short -> SnackbarDuration.Short
                    UiFeedbackDuration.Long -> SnackbarDuration.Long
                    UiFeedbackDuration.Indefinite -> SnackbarDuration.Indefinite
                },
            )
            viewModel.resolveFeedbackAction(feedback.action, result == SnackbarResult.ActionPerformed)
        }
    }

    DisposableEffect(state.settings.blockScreenshots) {
        val window = (view.context as? Activity)?.window
        if (state.settings.blockScreenshots) {
            window?.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
        onDispose { }
    }

    val biometricAuthenticators = BiometricManager.Authenticators.BIOMETRIC_STRONG
    val biometricVault = remember(context) { BiometricVaultKeyStore(context) }
    val biometricAvailable = remember(context) {
        BiometricManager.from(context).canAuthenticate(biometricAuthenticators) == BiometricManager.BIOMETRIC_SUCCESS
    }
    fun authenticateBiometric() {
        val activity = context.findFragmentActivity()
        if (activity == null) {
            viewModel.showMessage("Biometric unlock is unavailable")
            return
        }
        val cryptoObject = biometricVault.unlockCryptoObject()
        if (cryptoObject == null) {
            viewModel.setPrivacyOption(biometricOnOpen = false)
            viewModel.showMessage("Biometric Vault key is unavailable. Unlock with your password, then enable biometrics again.")
            return
        }
        val prompt = BiometricPrompt(
            activity,
            ContextCompat.getMainExecutor(activity),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    if (biometricVault.completeUnlock(result.cryptoObject)) {
                        viewModel.unlockWithBiometric()
                    } else {
                        viewModel.showMessage("Biometric Vault verification failed. Use your password and set it up again.")
                    }
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
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
                    .setTitle("Unlock Norfold")
                    .setSubtitle("Use your fingerprint or face to unlock the vault")
                    .setNegativeButtonText("Use password")
                    .setAllowedAuthenticators(biometricAuthenticators)
                    .build(),
                cryptoObject,
            )
        }.onFailure {
            viewModel.showMessage(it.localizedMessage ?: "Biometric unlock failed")
        }
    }

    val effectiveSettings = scalePreview?.applyTo(state.settings) ?: state.settings
    NorfoldTheme(
        settings = effectiveSettings,
        unscaledOverlay = {
            scalePreview?.let { preview ->
                ScalePreviewSafetyCard(
                    preview = preview,
                    onSave = viewModel::saveScalePreview,
                    onRevert = viewModel::revertScalePreview,
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            }
        },
    ) {
        Surface(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize()) {
            if (state.diagnostics.pendingCrashPrompt && state.diagnostics.askBeforeSharing && initializationState == AppInitializationState.Ready) {
                NorfoldDialog(
                    onDismissRequest = viewModel::acknowledgeCrashPrompt,
                    title = { Text("Norfold noticed a crash") },
                    text = { Text("A local diagnostics log is ready. You can share it through your email or any app from the share sheet.") },
                    confirmButton = { Button(onClick = viewModel::shareDiagnostics) { Text("Share log") } },
                    dismissButton = { TextButton(onClick = viewModel::acknowledgeCrashPrompt) { Text("Not now") } },
                )
            }
            if (showNotificationPermissionPrompt) {
                NorfoldDialog(
                    onDismissRequest = {
                        permissionPrefs.edit().putBoolean("notifications_asked", true).apply()
                        showNotificationPermissionPrompt = false
                    },
                    title = { Text("Stay updated when Norfold is closed") },
                    text = { Text("Allow notifications for new messages, workspace invitations, task reminders, sync conflicts, and backup failures. Conversation mute and quiet-hours settings still apply.") },
                    confirmButton = {
                        Button(onClick = { notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) }) { Text("Continue") }
                    },
                    dismissButton = {
                        TextButton(onClick = {
                            permissionPrefs.edit().putBoolean("notifications_asked", true).apply()
                            showNotificationPermissionPrompt = false
                        }) { Text("Not now") }
                    },
                )
            }
            when {
                initializationState == AppInitializationState.Failed -> InitializationFailure(viewModel::retryInitialization)
                initializationState == AppInitializationState.Loading || brandIntroHold ->
                    ComposeLoadingScreen(palette = state.settings.themeProfile.palette(), reduceMotion = state.settings.reduceMotion)
                state.locked -> LockScreen(
                    biometricEnabled = state.settings.requireBiometricOnOpen,
                    biometricAvailable = biometricAvailable,
                    onUnlock = viewModel::unlock,
                    onBiometricUnlock = ::authenticateBiometric,
                )
                !state.settings.onboardingComplete -> NorfoldOnboardingScreen(
                    state = state,
                    viewModel = viewModel,
                    externalAuthBusy = googleSignInBusy,
                    onRestoreBackup = { secret ->
                        pendingBackupImportSecret = secret
                        backupImportLauncher.launch(arrayOf("application/octet-stream", "text/*", "*/*"))
                    },
                    onGoogleSignIn = {
                        val activity = context.findFragmentActivity()
                        if (activity == null) viewModel.showMessage("Google sign-in is unavailable")
                        else if (!googleSignInBusy) coroutineScope.launch {
                            googleSignInBusy = true
                            try {
                                val identity = googleIdentityClient.signIn(activity)
                                NorfoldSupabase.signInWithGoogle(identity.idToken, identity.rawNonce)
                                viewModel.completeExternalSignIn("Google", identity.toProfileSuggestion())
                            } catch (noAccount: com.norfold.app.cloud.GoogleSignInException.NoAccountAvailable) {
                                viewModel.showMessage("No Google account found. Add one in Android Settings, then try again.")
                            } catch (cancellation: kotlinx.coroutines.CancellationException) {
                                throw cancellation
                            } catch (error: Throwable) {
                                viewModel.reportExternalSignInFailure(error, "Google")
                            } finally {
                                googleSignInBusy = false
                            }
                        }
                    },
                    onConnectGoogleDrive = requestGoogleDriveAuthorization,
                    onEmailSignIn = viewModel::authenticateWithEmail,
                )
                else -> NotesApp(
                    state = state,
                    viewModel = viewModel,
                    snackbar = snackbar,
                    onPickSyncFolder = { request ->
                        pendingSyncRequest = request
                        syncFolderLauncher.launch(null)
                    },
                    onPickMarkdown = { markdownImportLauncher.launch(arrayOf("text/*", "text/markdown", "application/octet-stream")) },
                    onPickAttachment = { attachmentLauncher.launch(arrayOf("*/*")) },
                    onPickEmbed = { embedLauncher.launch(arrayOf("*/*")) },
                    onPickCover = { coverLauncher.launch(arrayOf("image/*")) },
                    onPickChatAttachment = { action ->
                        when (action) {
                            ChatComposerAction.File -> chatAttachmentLauncher.launch(arrayOf("*/*"))
                            ChatComposerAction.Media -> chatMediaPickerLauncher.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo),
                            )
                            ChatComposerAction.Audio -> chatAttachmentLauncher.launch(arrayOf("audio/*"))
                            ChatComposerAction.Camera -> {
                                // filesDir, not cacheDir: the OS may trim cache at any time, which
                                // would dangle chat/task attachments referencing the captured photo.
                                val dir = File(context.filesDir, "captures").apply { mkdirs() }
                                val target = FileProvider.getUriForFile(
                                    context,
                                    "${context.packageName}.fileprovider",
                                    File(dir, "IMG_${System.currentTimeMillis()}.jpg"),
                                )
                                pendingChatCameraUri = target
                                chatCameraLauncher.launch(target)
                            }
                        }
                    },
                    onPickWorkspaceFile = { workspaceFileLauncher.launch(arrayOf("*/*")) },
                    onPickTaskAttachment = { task ->
                        pendingTaskAttachmentId = task.id
                        taskAttachmentLauncher.launch(arrayOf("*/*"))
                    },
                    onPickBackupFolder = { backupFolderLauncher.launch(null) },
                    onPickBackupFile = { secret ->
                        pendingBackupImportSecret = secret
                        backupImportLauncher.launch(arrayOf("application/octet-stream", "text/*", "*/*"))
                    },
                    onConnectGoogleDrive = {
                        googleAuthorizationClient.authorize(GoogleDriveOAuth.authorizationRequest())
                            .addOnSuccessListener { result ->
                                if (result.hasResolution()) {
                                    val intentSender = result.pendingIntent?.intentSender
                                    if (intentSender != null) {
                                        googleDriveAuthLauncher.launch(IntentSenderRequest.Builder(intentSender).build())
                                    } else {
                                        viewModel.reportGoogleDriveAuthorizationFailure(null)
                                    }
                                } else {
                                    viewModel.connectGoogleDrive(result.accessToken)
                                }
                            }
                            .addOnFailureListener(viewModel::reportGoogleDriveAuthorizationFailure)
                    },
                    onDisconnectGoogleDrive = {
                        googleAuthorizationClient.revokeAccess(GoogleDriveOAuth.revocationRequest())
                            .addOnSuccessListener { viewModel.disconnectGoogleDrive() }
                            .addOnFailureListener { error ->
                                viewModel.disconnectGoogleDrive()
                                viewModel.showMessage("Disconnected locally; Google access could not be revoked: ${error.localizedMessage ?: "unknown error"}")
                            }
                    },
                    onCloudGoogleSignIn = {
                        val activity = context.findFragmentActivity()
                        if (activity == null) viewModel.showMessage("Google sign-in is unavailable")
                        else if (!googleSignInBusy) coroutineScope.launch {
                            googleSignInBusy = true
                            try {
                                val identity = googleIdentityClient.signIn(activity)
                                NorfoldSupabase.signInWithGoogle(identity.idToken, identity.rawNonce)
                                viewModel.completeExternalSignIn("Google", identity.toProfileSuggestion())
                            } catch (noAccount: com.norfold.app.cloud.GoogleSignInException.NoAccountAvailable) {
                                viewModel.showMessage("No Google account found. Add one in Android Settings, then try again.")
                            } catch (cancellation: CancellationException) {
                                throw cancellation
                            } catch (error: Throwable) {
                                viewModel.reportExternalSignInFailure(error, "Google")
                            } finally {
                                googleSignInBusy = false
                            }
                        }
                    },
                )
            }
            if (!state.settings.onboardingComplete || initializationState != AppInitializationState.Ready || state.locked) {
                SnackbarHost(
                    hostState = snackbar,
                    modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(16.dp),
                )
            }
            }
        }
    }
}

@Composable
private fun ScalePreviewSafetyCard(
    preview: ScalePreviewState,
    onSave: () -> Unit,
    onRevert: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .navigationBarsPadding()
            .padding(16.dp)
            .zIndex(100f),
        shape = MaterialTheme.shapes.large,
        tonalElevation = 8.dp,
        shadowElevation = 8.dp,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Previewing appearance", style = MaterialTheme.typography.titleSmall)
            Text(
                if (preview.saving) "Saving appearance settings…" else "Unsaved changes revert in ${preview.remainingSeconds}s",
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onSave, enabled = preview.invalidInputs.isEmpty() && !preview.saving) {
                    Text(if (preview.saving) "Saving…" else "Save")
                }
                TextButton(onClick = onRevert, enabled = !preview.saving) { Text("Revert now") }
            }
        }
    }
}

@Composable
private fun InitializationFailure(onRetry: () -> Unit) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Couldn't open your workspace", style = MaterialTheme.typography.headlineSmall)
            Text(
                "Norfold left your local data unchanged. Try opening it again.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onClick = onRetry) { Text("Retry") }
        }
    }
}

private tailrec fun Context.findFragmentActivity(): FragmentActivity? = when (this) {
    is FragmentActivity -> this
    is ContextWrapper -> baseContext.findFragmentActivity()
    else -> null
}

@Composable
private fun NotesApp(
    state: DocsUiState,
    viewModel: DocsViewModel,
    snackbar: SnackbarHostState,
    onPickSyncFolder: (SyncFolderRequest) -> Unit,
    onPickMarkdown: () -> Unit,
    onPickAttachment: () -> Unit,
    onPickEmbed: () -> Unit,
    onPickCover: () -> Unit,
    onPickChatAttachment: (ChatComposerAction) -> Unit,
    onPickWorkspaceFile: () -> Unit,
    onPickTaskAttachment: (TaskItem) -> Unit,
    onPickBackupFolder: () -> Unit,
    onPickBackupFile: (String) -> Unit,
    onConnectGoogleDrive: () -> Unit,
    onDisconnectGoogleDrive: () -> Unit,
    onCloudGoogleSignIn: () -> Unit,
) {
    var taskDetailOpen by remember { mutableStateOf(false) }
    LaunchedEffect(state.destination) { if (state.destination != Destination.Tasks) taskDetailOpen = false }
    LaunchedEffect(state.destination, state.settings.activeWorkspaceId) {
        if (state.destination == Destination.Chat) {
            // Account-level Direct conversations follow the user across workspaces;
            // materialize them into the local store whenever the Chat tab opens.
            viewModel.refreshSocialGraph()
            // Presence joins alongside the live message stream; both end (and we
            // leave the presence roster) when the Chat destination goes away.
            launch { viewModel.observeWorkspacePresenceWhileVisible() }
            launch { viewModel.observeWorkspaceChatSecurityWhileVisible() }
            viewModel.observeTeamChatWhileVisible()
        }
    }
    val canHandleBack = state.locked || state.sidebarOpen || state.searchQuery.isNotBlank() ||
        state.destination != Destination.WorkspaceHub
    PredictiveBackHandler(enabled = canHandleBack) { progress ->
        try {
            progress.collect { }
            viewModel.handleBack()
        } catch (_: CancellationException) {
            // A cancelled gesture must not mutate navigation state.
        }
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val compact = maxWidth < 720.dp
        val medium = maxWidth in 720.dp..1040.dp
        val density = LocalDensity.current
        val imeVisible = WindowInsets.ime.getBottom(density) > 0
        val edgeSwipePx = with(density) { 46.dp.toPx() }
        val openSwipePx = with(density) { 64.dp.toPx() }
        val immersiveDestination = state.destination in setOf(
            Destination.NoteEditor,
            Destination.DocumentEditor,
            Destination.ObjectDetail,
            Destination.CommandPalette,
        ) ||
            // An open conversation replaces the navbar with the message composer; the chat
            // inbox (conversation list) keeps the navbar like any other tab.
            (state.destination == Destination.Chat && state.chatConversationOpen)
        val showBottomNavigation = compact && !imeVisible && !immersiveDestination && !taskDetailOpen
        Scaffold(
            snackbarHost = { SnackbarHost(snackbar) },
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
        ) { padding ->
            Box(
                Modifier
                    .pointerInput(compact, state.sidebarOpen) {
                        if (compact && !state.sidebarOpen) {
                            var edgeDrag = false
                            var accumulatedX = 0f
                            detectDragGestures(
                                onDragStart = { start ->
                                    edgeDrag = start.x <= edgeSwipePx
                                    accumulatedX = 0f
                                },
                                onDragEnd = {
                                    edgeDrag = false
                                    accumulatedX = 0f
                                },
                                onDragCancel = {
                                    edgeDrag = false
                                    accumulatedX = 0f
                                },
                                onDrag = { change, dragAmount ->
                                    if (edgeDrag) {
                                        accumulatedX += dragAmount.x
                                        if (accumulatedX > openSwipePx) {
                                            change.consume()
                                            viewModel.toggleSidebar()
                                            edgeDrag = false
                                            accumulatedX = 0f
                                        }
                                    }
                                },
                            )
                        }
                    }
                    .padding(padding)
                    .statusBarsPadding()
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background),
            ) {
                when {
                    compact -> CompactContent(state, viewModel, onPickSyncFolder, onPickMarkdown, onPickAttachment, onPickEmbed, onPickCover, onPickChatAttachment, onPickWorkspaceFile, onPickTaskAttachment, onPickBackupFolder, onPickBackupFile, onConnectGoogleDrive, onDisconnectGoogleDrive, onCloudGoogleSignIn) { taskDetailOpen = it }
                    medium -> MediumContent(state, viewModel, onPickSyncFolder, onPickMarkdown, onPickAttachment, onPickEmbed, onPickCover, onPickChatAttachment, onPickWorkspaceFile, onPickTaskAttachment, onPickBackupFolder, onPickBackupFile, onConnectGoogleDrive, onDisconnectGoogleDrive, onCloudGoogleSignIn) { taskDetailOpen = it }
                    else -> ExpandedContent(state, viewModel, onPickSyncFolder, onPickMarkdown, onPickAttachment, onPickEmbed, onPickCover, onPickChatAttachment, onPickWorkspaceFile, onPickTaskAttachment, onPickBackupFolder, onPickBackupFile, onConnectGoogleDrive, onDisconnectGoogleDrive, onCloudGoogleSignIn) { taskDetailOpen = it }
                }
                if (compact && !state.sidebarOpen && !imeVisible && !taskDetailOpen && state.destination.showCompactSidebarButton) {
                    CompactSidebarButton(viewModel)
                }
                if (showBottomNavigation) {
                    MobileBottomBar(
                        state,
                        viewModel,
                        Modifier.align(Alignment.BottomCenter).zIndex(6f),
                    )
                }
            }
        }
        // Above the Scaffold so the scrim and panel cover the bottom navbar instead of
        // being clipped to the content slot.
        if (compact && state.sidebarOpen) {
            SectionSidebarOverlay(state, viewModel)
        }
    }
}

private val Destination.showCompactSidebarButton: Boolean
    get() = this !in setOf(
        Destination.NoteEditor,
        Destination.DocumentEditor,
        Destination.ObjectDetail,
        Destination.CommandPalette,
        Destination.WorkspaceHub,
        Destination.Inbox,
        Destination.NotesHome,
        Destination.DocumentsHome,
        Destination.Files,
        Destination.Database,
        Destination.Activity,
        Destination.Tasks,
        Destination.Settings,
        Destination.Vault,
        Destination.ImportExport,
        // The chat inbox has its own header; conversations have the persona bar's back button.
        Destination.Chat,
    )

@Composable
private fun CompactSidebarButton(viewModel: DocsViewModel) {
    Surface(
        modifier = Modifier
            .padding(start = 14.dp, top = 12.dp)
            .size(42.dp)
            .zIndex(4f),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
        shadowElevation = 8.dp,
        tonalElevation = 2.dp,
    ) {
        IconButton(onClick = viewModel::toggleSidebar) {
            Icon(Icons.Outlined.Menu, contentDescription = "Open sidebar")
        }
    }
}

@Composable
private fun CompactContent(
    state: DocsUiState,
    viewModel: DocsViewModel,
    onPickSyncFolder: (SyncFolderRequest) -> Unit,
    onPickMarkdown: () -> Unit,
    onPickAttachment: () -> Unit,
    onPickEmbed: () -> Unit,
    onPickCover: () -> Unit,
    onPickChatAttachment: (ChatComposerAction) -> Unit,
    onPickWorkspaceFile: () -> Unit,
    onPickTaskAttachment: (TaskItem) -> Unit,
    onPickBackupFolder: () -> Unit,
    onPickBackupFile: (String) -> Unit,
    onConnectGoogleDrive: () -> Unit,
    onDisconnectGoogleDrive: () -> Unit,
    onCloudGoogleSignIn: () -> Unit,
    onTaskDetailOpenChange: (Boolean) -> Unit,
) {
    DestinationContent(state, viewModel, Modifier.fillMaxSize(), true, onPickSyncFolder, onPickMarkdown, onPickAttachment, onPickEmbed, onPickCover, onPickChatAttachment, onPickWorkspaceFile, onPickTaskAttachment, onPickBackupFolder, onPickBackupFile, onConnectGoogleDrive, onDisconnectGoogleDrive, onCloudGoogleSignIn, onTaskDetailOpenChange)
}

@Composable
private fun DestinationContent(
    state: DocsUiState,
    viewModel: DocsViewModel,
    modifier: Modifier,
    compact: Boolean,
    onPickSyncFolder: (SyncFolderRequest) -> Unit,
    onPickMarkdown: () -> Unit,
    onPickAttachment: () -> Unit,
    onPickEmbed: () -> Unit,
    onPickCover: () -> Unit,
    onPickChatAttachment: (ChatComposerAction) -> Unit,
    onPickWorkspaceFile: () -> Unit,
    onPickTaskAttachment: (TaskItem) -> Unit,
    onPickBackupFolder: () -> Unit,
    onPickBackupFile: (String) -> Unit,
    onConnectGoogleDrive: () -> Unit,
    onDisconnectGoogleDrive: () -> Unit,
    onCloudGoogleSignIn: () -> Unit,
    onTaskDetailOpenChange: (Boolean) -> Unit,
) {
    Box(modifier) {
        Crossfade(
            targetState = state.destination,
            animationSpec = tween(durationMillis = if (state.settings.reduceMotion) 90 else 260, easing = FastOutSlowInEasing),
            label = "destination-crossfade",
        ) { destination ->
            when (destination) {
                Destination.NoteEditor -> FlowNoteEditorScreen(state, viewModel, Modifier.fillMaxSize(), viewModel::toggleSidebar)
                Destination.DocumentEditor -> BoundedDocumentEditorScreen(state, viewModel, Modifier.fillMaxSize(), viewModel::toggleSidebar)
                Destination.WorkspaceHub -> WorkspaceHubScreen(state, viewModel)
                Destination.Inbox -> WorkspaceHubScreen(state, viewModel, inboxMode = true)
                // Legacy deep links enter the same integrated Tasks calendar instead of a
                // disconnected calendar destination with no local way back to the task views.
                Destination.Calendar -> TasksBoardScreen(state, viewModel, onPickTaskAttachment, onTaskDetailOpenChange)
                Destination.Goals -> GoalsScreen(state, viewModel)
                Destination.Files -> FilesLibraryScreen(state, viewModel, onPickWorkspaceFile)
                Destination.Database -> DatabaseScreen(state, viewModel)
                Destination.Graph -> GraphScreen(state, viewModel)
                Destination.ObjectDetail -> ObjectDetailScreen(state, viewModel, Modifier.fillMaxSize())
                Destination.Activity -> ActivityScreen(state, viewModel)
                Destination.Templates -> TemplatesScreen(state, viewModel)
                Destination.CommandPalette -> CommandPaletteScreen(state, viewModel)
                Destination.Notebooks -> NotebookScreen(state, viewModel)
                Destination.Tags -> TagsScreen(state, viewModel)
                Destination.Search -> SearchScreen(state, viewModel)
                Destination.Tasks -> TasksBoardScreen(state, viewModel, onPickTaskAttachment, onTaskDetailOpenChange)
                Destination.Chat ->
                    // Compact: inbox list first, conversation on tap. Wide layouts already have
                    // the channel list in the sidebar, so they go straight to the conversation.
                    if (state.chatConversationOpen || !compact) {
                        ChatScreen(state, viewModel, onPickChatAttachment)
                    } else {
                        ChatHomeScreen(state, viewModel)
                    }
                Destination.ConflictReview -> ConflictReviewScreen(state, viewModel)
                Destination.Settings, Destination.Vault, Destination.ImportExport -> SettingsScreen(state, viewModel, onPickSyncFolder, onPickMarkdown, onPickBackupFolder, onPickBackupFile, onConnectGoogleDrive, onDisconnectGoogleDrive, onCloudGoogleSignIn)
                Destination.NotesHome -> NotesHome(state, viewModel, Modifier.fillMaxSize())
                Destination.DocumentsHome -> NotesHome(state, viewModel, Modifier.fillMaxSize(), libraryKind = LibraryKind.Documents)
            }
        }
    }
}

@Composable
private fun MediumContent(
    state: DocsUiState,
    viewModel: DocsViewModel,
    onPickSyncFolder: (SyncFolderRequest) -> Unit,
    onPickMarkdown: () -> Unit,
    onPickAttachment: () -> Unit,
    onPickEmbed: () -> Unit,
    onPickCover: () -> Unit,
    onPickChatAttachment: (ChatComposerAction) -> Unit,
    onPickWorkspaceFile: () -> Unit,
    onPickTaskAttachment: (TaskItem) -> Unit,
    onPickBackupFolder: () -> Unit,
    onPickBackupFile: (String) -> Unit,
    onConnectGoogleDrive: () -> Unit,
    onDisconnectGoogleDrive: () -> Unit,
    onCloudGoogleSignIn: () -> Unit,
    onTaskDetailOpenChange: (Boolean) -> Unit,
) {
    if (state.destination == Destination.DocumentEditor) {
        Row(Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 6.dp)) {
            Sidebar(state, viewModel, Modifier.width(260.dp).fillMaxHeight())
            BoundedDocumentEditorScreen(state, viewModel, Modifier.weight(1f).fillMaxHeight(), viewModel::toggleSidebar)
        }
    } else if (state.destination in setOf(Destination.NotesHome, Destination.DocumentsHome)) {
        val documents = state.destination == Destination.DocumentsHome
        Row(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
            NotesHome(
                state,
                viewModel,
                Modifier.weight(0.42f).fillMaxHeight(),
                showHeader = false,
                libraryKind = if (documents) LibraryKind.Documents else LibraryKind.Notes,
            )
            if (documents) {
                BoundedDocumentEditorScreen(state, viewModel, Modifier.weight(0.58f).fillMaxHeight(), viewModel::toggleSidebar)
            } else {
                FlowNoteEditorScreen(state, viewModel, Modifier.weight(0.58f).fillMaxHeight(), viewModel::toggleSidebar)
            }
        }
    } else if (state.destination in setOf(Destination.NoteEditor, Destination.DocumentEditor)) {
        if (state.destination == Destination.DocumentEditor) {
            BoundedDocumentEditorScreen(state, viewModel, Modifier.fillMaxSize(), viewModel::toggleSidebar)
        } else {
            FlowNoteEditorScreen(state, viewModel, Modifier.fillMaxSize(), viewModel::toggleSidebar)
        }
    } else {
        DestinationContent(state, viewModel, Modifier.fillMaxSize(), false, onPickSyncFolder, onPickMarkdown, onPickAttachment, onPickEmbed, onPickCover, onPickChatAttachment, onPickWorkspaceFile, onPickTaskAttachment, onPickBackupFolder, onPickBackupFile, onConnectGoogleDrive, onDisconnectGoogleDrive, onCloudGoogleSignIn, onTaskDetailOpenChange)
    }
}

@Composable
private fun ExpandedContent(
    state: DocsUiState,
    viewModel: DocsViewModel,
    onPickSyncFolder: (SyncFolderRequest) -> Unit,
    onPickMarkdown: () -> Unit,
    onPickAttachment: () -> Unit,
    onPickEmbed: () -> Unit,
    onPickCover: () -> Unit,
    onPickChatAttachment: (ChatComposerAction) -> Unit,
    onPickWorkspaceFile: () -> Unit,
    onPickTaskAttachment: (TaskItem) -> Unit,
    onPickBackupFolder: () -> Unit,
    onPickBackupFile: (String) -> Unit,
    onConnectGoogleDrive: () -> Unit,
    onDisconnectGoogleDrive: () -> Unit,
    onCloudGoogleSignIn: () -> Unit,
    onTaskDetailOpenChange: (Boolean) -> Unit,
) {
    Row(Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 6.dp)) {
        Sidebar(state, viewModel, Modifier.width(228.dp).fillMaxHeight())
        if (state.destination in setOf(Destination.NotesHome, Destination.DocumentsHome)) {
            val documents = state.destination == Destination.DocumentsHome
            NotesHome(
                state,
                viewModel,
                Modifier.width(370.dp).fillMaxHeight(),
                showHeader = false,
                libraryKind = if (documents) LibraryKind.Documents else LibraryKind.Notes,
            )
            if (documents) {
                BoundedDocumentEditorScreen(state, viewModel, Modifier.weight(1f).fillMaxHeight(), viewModel::toggleSidebar)
            } else {
                FlowNoteEditorScreen(state, viewModel, Modifier.weight(1f).fillMaxHeight(), viewModel::toggleSidebar)
            }
        } else if (state.destination in setOf(Destination.NoteEditor, Destination.DocumentEditor)) {
            if (state.destination == Destination.DocumentEditor) {
                BoundedDocumentEditorScreen(state, viewModel, Modifier.weight(1f).fillMaxHeight(), viewModel::toggleSidebar)
            } else {
                FlowNoteEditorScreen(state, viewModel, Modifier.weight(1f).fillMaxHeight(), viewModel::toggleSidebar)
            }
        } else {
            DestinationContent(state, viewModel, Modifier.weight(1f).fillMaxHeight(), false, onPickSyncFolder, onPickMarkdown, onPickAttachment, onPickEmbed, onPickCover, onPickChatAttachment, onPickWorkspaceFile, onPickTaskAttachment, onPickBackupFolder, onPickBackupFile, onConnectGoogleDrive, onDisconnectGoogleDrive, onCloudGoogleSignIn, onTaskDetailOpenChange)
        }
    }
}

private fun com.norfold.app.cloud.GoogleIdentityResult.toProfileSuggestion(): GoogleProfileSuggestion {
    val cleanFullName = displayName
        ?.trim()
        ?.takeIf { it.isNotBlank() }
        ?: listOfNotNull(givenName?.trim(), familyName?.trim())
            .filter { it.isNotBlank() }
            .joinToString(" ")
            .takeIf { it.isNotBlank() }
        ?: email.orEmpty()
            .substringBefore('@')
            .replace('.', ' ')
            .replace('_', ' ')
            .trim()
    val cleanNickname = givenName
        ?.trim()
        ?.takeIf { it.isNotBlank() }
        ?: cleanFullName
            .substringBefore(' ')
            .takeIf { it.isNotBlank() }
        ?: email.orEmpty().substringBefore('@')
    return GoogleProfileSuggestion(
        fullName = cleanFullName,
        nickname = cleanNickname,
        email = email.orEmpty(),
        avatarUri = profilePictureUri?.takeIf { it.isNotBlank() },
    )
}

/** Attachment actions offered by the chat composer's floating popup. */
enum class ChatComposerAction { File, Media, Camera, Audio }
