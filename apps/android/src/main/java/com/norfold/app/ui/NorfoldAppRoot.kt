package com.norfold.app.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.norfold.app.branding.palette
import com.norfold.app.data.GoogleDriveOAuth
import com.norfold.app.cloud.GoogleIdentityClient
import com.norfold.app.cloud.NorfoldSupabase
import com.google.android.gms.auth.api.identity.Identity
import com.norfold.app.domain.CanvasNodeItem
import com.norfold.app.domain.Destination
import com.norfold.app.domain.SyncFolderRequest
import com.norfold.app.domain.TaskItem
import com.norfold.app.ui.components.AnimatedFab
import com.norfold.app.ui.components.ComposeLoadingScreen
import com.norfold.app.ui.components.MobileBottomBar
import com.norfold.app.ui.screens.LockScreen
import com.norfold.app.ui.screens.BlockNoteEditorScreen
import com.norfold.app.ui.screens.NotebookScreen
import com.norfold.app.ui.screens.NotesHome
import com.norfold.app.ui.screens.ObjectDetailScreen
import com.norfold.app.ui.screens.CanvasBoardScreen
import com.norfold.app.ui.screens.ChatScreen
import com.norfold.app.ui.screens.ConflictReviewScreen
import com.norfold.app.ui.screens.SearchScreen
import com.norfold.app.ui.screens.SettingsScreen
import com.norfold.app.ui.screens.SectionSidebarOverlay
import com.norfold.app.ui.screens.Sidebar
import com.norfold.app.ui.screens.SyncMonitorScreen
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun NorfoldRoot(viewModel: NotesViewModel = viewModel()) {
    val state by viewModel.state.collectAsState()
    val lifecycleOwner = LocalLifecycleOwner.current
    val view = LocalView.current
    val context = view.context
    val googleAuthorizationClient = remember(context) { Identity.getAuthorizationClient(context) }
    val googleIdentityClient = remember { GoogleIdentityClient() }
    val coroutineScope = rememberCoroutineScope()
    var loading by remember { mutableStateOf(true) }
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
    val chatAttachmentLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        viewModel.sendChatAttachment(uri)
    }
    val workspaceFileLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        viewModel.addWorkspaceFile(uri)
    }
    var pendingTaskAttachment by remember { mutableStateOf<TaskItem?>(null) }
    val taskAttachmentLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        pendingTaskAttachment?.let { viewModel.attachFileToTask(it, uri) }
        pendingTaskAttachment = null
    }
    var pendingCanvasTarget by remember { mutableStateOf<CanvasNodeItem?>(null) }
    val canvasTargetLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        pendingCanvasTarget?.let { viewModel.attachTargetToCanvasNode(it, uri) }
        pendingCanvasTarget = null
    }
    val googleDriveAuthLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        runCatching { googleAuthorizationClient.getAuthorizationResultFromIntent(result.data) }
            .onSuccess { viewModel.connectGoogleDrive(it.accessToken) }
            .onFailure(viewModel::reportGoogleDriveAuthorizationFailure)
    }

    LaunchedEffect(Unit) {
        delay(2100)
        loading = false
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) viewModel.autoSyncIfPossible()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
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

    val biometricAuthenticators = BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.BIOMETRIC_WEAK
    val biometricAvailable = remember(context) {
        BiometricManager.from(context).canAuthenticate(biometricAuthenticators) == BiometricManager.BIOMETRIC_SUCCESS
    }
    fun authenticateBiometric() {
        val activity = context.findFragmentActivity()
        if (activity == null) {
            viewModel.showMessage("Biometric unlock is unavailable")
            return
        }
        val prompt = BiometricPrompt(
            activity,
            ContextCompat.getMainExecutor(activity),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    viewModel.unlockWithBiometric()
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
            )
        }.onFailure {
            viewModel.showMessage(it.localizedMessage ?: "Biometric unlock failed")
        }
    }

    NorfoldTheme(state.settings) {
        Surface(Modifier.fillMaxSize()) {
            if (state.diagnostics.pendingCrashPrompt && state.diagnostics.askBeforeSharing && !loading) {
                AlertDialog(
                    onDismissRequest = viewModel::acknowledgeCrashPrompt,
                    title = { Text("Norfold noticed a crash") },
                    text = { Text("A local diagnostics log is ready. You can share it through your email or any app from the share sheet.") },
                    confirmButton = { Button(onClick = viewModel::shareDiagnostics) { Text("Share log") } },
                    dismissButton = { TextButton(onClick = viewModel::acknowledgeCrashPrompt) { Text("Not now") } },
                )
            }
            when {
                loading -> ComposeLoadingScreen(palette = state.settings.themeProfile.palette())
                state.locked -> LockScreen(
                    biometricEnabled = state.settings.requireBiometricOnOpen,
                    biometricAvailable = biometricAvailable,
                    onUnlock = viewModel::unlock,
                    onBiometricUnlock = ::authenticateBiometric,
                )
                !state.settings.onboardingComplete -> NorfoldOnboardingScreen(
                    state = state,
                    viewModel = viewModel,
                    onRestoreBackup = { secret ->
                        pendingBackupImportSecret = secret
                        backupImportLauncher.launch(arrayOf("application/octet-stream", "text/*", "*/*"))
                    },
                    onGoogleSignIn = {
                        val activity = context.findFragmentActivity()
                        if (activity == null) viewModel.showMessage("Google sign-in is unavailable")
                        else coroutineScope.launch {
                            runCatching {
                                val identity = googleIdentityClient.signIn(activity)
                                NorfoldSupabase.signInWithGoogle(identity.idToken, identity.rawNonce)
                            }.onSuccess { viewModel.showMessage("Signed in with Google") }
                                .onFailure { viewModel.showMessage(it.message ?: "Google sign-in failed") }
                        }
                    },
                    onEmailSignIn = viewModel::authenticateWithEmail,
                )
                else -> NotesApp(
                    state = state,
                    viewModel = viewModel,
                    onPickSyncFolder = { request ->
                        pendingSyncRequest = request
                        syncFolderLauncher.launch(null)
                    },
                    onPickMarkdown = { markdownImportLauncher.launch(arrayOf("text/*", "text/markdown", "application/octet-stream")) },
                    onPickAttachment = { attachmentLauncher.launch(arrayOf("*/*")) },
                    onPickEmbed = { embedLauncher.launch(arrayOf("*/*")) },
                    onPickCover = { coverLauncher.launch(arrayOf("image/*")) },
                    onPickChatAttachment = { chatAttachmentLauncher.launch(arrayOf("*/*")) },
                    onPickWorkspaceFile = { workspaceFileLauncher.launch(arrayOf("*/*")) },
                    onPickTaskAttachment = { task ->
                        pendingTaskAttachment = task
                        taskAttachmentLauncher.launch(arrayOf("*/*"))
                    },
                    onPickCanvasTarget = { node ->
                        pendingCanvasTarget = node
                        canvasTargetLauncher.launch(
                            when (node.type.name) {
                                "Media" -> arrayOf("image/*", "video/*", "audio/*")
                                else -> arrayOf("*/*")
                            },
                        )
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
                )
            }
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
    state: NotesUiState,
    viewModel: NotesViewModel,
    onPickSyncFolder: (SyncFolderRequest) -> Unit,
    onPickMarkdown: () -> Unit,
    onPickAttachment: () -> Unit,
    onPickEmbed: () -> Unit,
    onPickCover: () -> Unit,
    onPickChatAttachment: () -> Unit,
    onPickWorkspaceFile: () -> Unit,
    onPickTaskAttachment: (TaskItem) -> Unit,
    onPickCanvasTarget: (CanvasNodeItem) -> Unit,
    onPickBackupFolder: () -> Unit,
    onPickBackupFile: (String) -> Unit,
    onConnectGoogleDrive: () -> Unit,
) {
    val snackbar = remember { SnackbarHostState() }
    var taskDetailOpen by remember { mutableStateOf(false) }
    LaunchedEffect(state.destination) { if (state.destination != Destination.Tasks) taskDetailOpen = false }
    val canHandleBack = state.locked || state.sidebarOpen || state.searchQuery.isNotBlank() ||
        state.destination != Destination.WorkspaceHub
    BackHandler(enabled = canHandleBack) { viewModel.handleBack() }
    LaunchedEffect(state.message) {
        state.message?.let {
            snackbar.showSnackbar(it)
            viewModel.clearMessage()
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
            Destination.ObjectDetail,
            Destination.CommandPalette,
        )
        Scaffold(
            snackbarHost = { SnackbarHost(snackbar) },
            bottomBar = { if (compact && !imeVisible && !immersiveDestination) MobileBottomBar(state, viewModel) },
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
                    compact -> CompactContent(state, viewModel, onPickSyncFolder, onPickMarkdown, onPickAttachment, onPickEmbed, onPickCover, onPickChatAttachment, onPickWorkspaceFile, onPickTaskAttachment, onPickCanvasTarget, onPickBackupFolder, onPickBackupFile, onConnectGoogleDrive) { taskDetailOpen = it }
                    medium -> MediumContent(state, viewModel, onPickSyncFolder, onPickMarkdown, onPickAttachment, onPickEmbed, onPickCover, onPickChatAttachment, onPickWorkspaceFile, onPickTaskAttachment, onPickCanvasTarget, onPickBackupFolder, onPickBackupFile, onConnectGoogleDrive) { taskDetailOpen = it }
                    else -> ExpandedContent(state, viewModel, onPickSyncFolder, onPickMarkdown, onPickAttachment, onPickEmbed, onPickCover, onPickChatAttachment, onPickWorkspaceFile, onPickTaskAttachment, onPickCanvasTarget, onPickBackupFolder, onPickBackupFile, onConnectGoogleDrive) { taskDetailOpen = it }
                }
                if (compact && !state.sidebarOpen && !imeVisible && !taskDetailOpen && state.destination.showCompactSidebarButton) {
                    CompactSidebarButton(viewModel)
                }
                if (compact && state.sidebarOpen) {
                    SectionSidebarOverlay(state, viewModel)
                }
            }
        }
    }
}

private val Destination.showCompactSidebarButton: Boolean
    get() = this !in setOf(
        Destination.NoteEditor,
        Destination.ObjectDetail,
        Destination.CommandPalette,
    )

@Composable
private fun CompactSidebarButton(viewModel: NotesViewModel) {
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
    state: NotesUiState,
    viewModel: NotesViewModel,
    onPickSyncFolder: (SyncFolderRequest) -> Unit,
    onPickMarkdown: () -> Unit,
    onPickAttachment: () -> Unit,
    onPickEmbed: () -> Unit,
    onPickCover: () -> Unit,
    onPickChatAttachment: () -> Unit,
    onPickWorkspaceFile: () -> Unit,
    onPickTaskAttachment: (TaskItem) -> Unit,
    onPickCanvasTarget: (CanvasNodeItem) -> Unit,
    onPickBackupFolder: () -> Unit,
    onPickBackupFile: (String) -> Unit,
    onConnectGoogleDrive: () -> Unit,
    onTaskDetailOpenChange: (Boolean) -> Unit,
) {
    DestinationContent(state, viewModel, Modifier.fillMaxSize(), onPickSyncFolder, onPickMarkdown, onPickAttachment, onPickEmbed, onPickCover, onPickChatAttachment, onPickWorkspaceFile, onPickTaskAttachment, onPickCanvasTarget, onPickBackupFolder, onPickBackupFile, onConnectGoogleDrive, onTaskDetailOpenChange)
}

@Composable
private fun DestinationContent(
    state: NotesUiState,
    viewModel: NotesViewModel,
    modifier: Modifier,
    onPickSyncFolder: (SyncFolderRequest) -> Unit,
    onPickMarkdown: () -> Unit,
    onPickAttachment: () -> Unit,
    onPickEmbed: () -> Unit,
    onPickCover: () -> Unit,
    onPickChatAttachment: () -> Unit,
    onPickWorkspaceFile: () -> Unit,
    onPickTaskAttachment: (TaskItem) -> Unit,
    onPickCanvasTarget: (CanvasNodeItem) -> Unit,
    onPickBackupFolder: () -> Unit,
    onPickBackupFile: (String) -> Unit,
    onConnectGoogleDrive: () -> Unit,
    onTaskDetailOpenChange: (Boolean) -> Unit,
) {
    Box(modifier) {
        Crossfade(
            targetState = state.destination,
            animationSpec = tween(durationMillis = 260, easing = FastOutSlowInEasing),
            label = "destination-crossfade",
        ) { destination ->
            when (destination) {
                Destination.NoteEditor -> BlockNoteEditorScreen(state, viewModel, Modifier.fillMaxSize(), viewModel::toggleSidebar)
                Destination.WorkspaceHub -> WorkspaceHubScreen(state, viewModel)
                Destination.Inbox -> WorkspaceHubScreen(state, viewModel, inboxMode = true)
                Destination.Calendar -> CalendarWorkspaceScreen(state, viewModel)
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
                Destination.Canvas -> CanvasBoardScreen(state, viewModel, onPickCanvasTarget)
                Destination.Chat -> ChatScreen(state, viewModel, onPickChatAttachment)
                Destination.ConflictReview -> ConflictReviewScreen(state, viewModel)
                Destination.SyncMonitor -> SyncMonitorScreen(state, viewModel)
                Destination.Settings, Destination.Vault, Destination.ImportExport -> SettingsScreen(state, viewModel, onPickSyncFolder, onPickMarkdown, onPickBackupFolder, onPickBackupFile, onConnectGoogleDrive)
                Destination.NotesHome -> NotesHome(state, viewModel, Modifier.fillMaxSize())
            }
        }
    }
}

@Composable
private fun MediumContent(
    state: NotesUiState,
    viewModel: NotesViewModel,
    onPickSyncFolder: (SyncFolderRequest) -> Unit,
    onPickMarkdown: () -> Unit,
    onPickAttachment: () -> Unit,
    onPickEmbed: () -> Unit,
    onPickCover: () -> Unit,
    onPickChatAttachment: () -> Unit,
    onPickWorkspaceFile: () -> Unit,
    onPickTaskAttachment: (TaskItem) -> Unit,
    onPickCanvasTarget: (CanvasNodeItem) -> Unit,
    onPickBackupFolder: () -> Unit,
    onPickBackupFile: (String) -> Unit,
    onConnectGoogleDrive: () -> Unit,
    onTaskDetailOpenChange: (Boolean) -> Unit,
) {
    if (state.destination == Destination.NotesHome) {
        Row(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
            NotesHome(state, viewModel, Modifier.weight(0.42f).fillMaxHeight(), showHeader = false)
            BlockNoteEditorScreen(state, viewModel, Modifier.weight(0.58f).fillMaxHeight(), viewModel::toggleSidebar)
        }
    } else if (state.destination == Destination.NoteEditor) {
        BlockNoteEditorScreen(state, viewModel, Modifier.fillMaxSize(), viewModel::toggleSidebar)
    } else {
        DestinationContent(state, viewModel, Modifier.fillMaxSize(), onPickSyncFolder, onPickMarkdown, onPickAttachment, onPickEmbed, onPickCover, onPickChatAttachment, onPickWorkspaceFile, onPickTaskAttachment, onPickCanvasTarget, onPickBackupFolder, onPickBackupFile, onConnectGoogleDrive, onTaskDetailOpenChange)
    }
}

@Composable
private fun ExpandedContent(
    state: NotesUiState,
    viewModel: NotesViewModel,
    onPickSyncFolder: (SyncFolderRequest) -> Unit,
    onPickMarkdown: () -> Unit,
    onPickAttachment: () -> Unit,
    onPickEmbed: () -> Unit,
    onPickCover: () -> Unit,
    onPickChatAttachment: () -> Unit,
    onPickWorkspaceFile: () -> Unit,
    onPickTaskAttachment: (TaskItem) -> Unit,
    onPickCanvasTarget: (CanvasNodeItem) -> Unit,
    onPickBackupFolder: () -> Unit,
    onPickBackupFile: (String) -> Unit,
    onConnectGoogleDrive: () -> Unit,
    onTaskDetailOpenChange: (Boolean) -> Unit,
) {
    Row(Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 6.dp)) {
        Sidebar(state, viewModel, Modifier.width(228.dp).fillMaxHeight())
        if (state.destination == Destination.NotesHome) {
            NotesHome(state, viewModel, Modifier.width(370.dp).fillMaxHeight(), showHeader = false)
            BlockNoteEditorScreen(state, viewModel, Modifier.weight(1f).fillMaxHeight(), viewModel::toggleSidebar)
        } else if (state.destination == Destination.NoteEditor) {
            BlockNoteEditorScreen(state, viewModel, Modifier.weight(1f).fillMaxHeight(), viewModel::toggleSidebar)
        } else {
            DestinationContent(state, viewModel, Modifier.weight(1f).fillMaxHeight(), onPickSyncFolder, onPickMarkdown, onPickAttachment, onPickEmbed, onPickCover, onPickChatAttachment, onPickWorkspaceFile, onPickTaskAttachment, onPickCanvasTarget, onPickBackupFolder, onPickBackupFile, onConnectGoogleDrive, onTaskDetailOpenChange)
        }
    }
}
