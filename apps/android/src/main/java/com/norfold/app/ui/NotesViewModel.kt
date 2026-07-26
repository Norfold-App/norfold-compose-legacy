package com.norfold.app.ui

import android.app.Application
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfRenderer
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.norfold.app.data.BackupFolderStore
import com.norfold.app.data.BiometricVaultKeyStore
import com.norfold.app.data.CloudFolderSyncStore
import com.norfold.app.data.DiagnosticsState
import com.norfold.app.data.DiagnosticsStore
import com.norfold.app.data.GoogleDriveApiSyncStore
import com.norfold.app.data.GoogleDriveAuthStore
import com.norfold.app.data.GoogleDriveBackupManifest
import com.norfold.app.data.chat.GoogleDriveChatArchiveProvider
import com.norfold.app.data.chat.LocalChatArchiveProvider
import com.norfold.app.data.chat.PrivateChatArchiveCoordinator
import com.norfold.app.data.chat.PrivateChatArchiveRestoreResult
import com.norfold.app.data.chat.TinkChatArchiveCrypto
import com.norfold.app.data.InstallationIdentityStore
import com.norfold.app.data.NorfoldWorkScheduler
import com.norfold.app.data.NorfoldDatabase
import com.norfold.app.data.NetworkMonitor
import com.norfold.app.data.DocsRepository
import com.norfold.app.data.SyncSecretStore
import com.norfold.app.data.SecureChatOutboxEntity
import com.norfold.app.data.SecureChatTombstoneEntity
import com.norfold.app.data.SecureChatUserHiddenEntity
import com.norfold.app.domain.AppSettings
import com.norfold.app.domain.AppIconFamily
import com.norfold.app.domain.BackupCodec
import com.norfold.app.domain.BackupCategory
import com.norfold.app.domain.mergeSelectedInto
import com.norfold.app.domain.ChatChannel
import com.norfold.app.domain.ChatChannelKind
import com.norfold.app.domain.ChatMessageItem
import com.norfold.app.domain.ChatReceiptStatus
import com.norfold.app.domain.ChatSecurityMode
import com.norfold.app.domain.ChatScope
import com.norfold.app.domain.SemanticActionId
import com.norfold.app.domain.Destination
import com.norfold.app.domain.DocLayerOrder
import com.norfold.app.domain.DocCanvasSpec
import com.norfold.app.domain.DocOverlapMode
import com.norfold.app.domain.DocSectionAction
import com.norfold.app.domain.ControlLabelMode
import com.norfold.app.domain.DocumentOwner
import com.norfold.app.domain.DocumentOwnerType
import com.norfold.app.domain.SettingsProfileCategory
import com.norfold.app.domain.FreeformPlacement
import com.norfold.app.domain.EditorFontFamily
import com.norfold.app.domain.EditorIconFamily
import com.norfold.app.domain.EditorLineWidth
import com.norfold.app.domain.HomeTab
import com.norfold.app.domain.Note
import com.norfold.app.domain.ParagraphBlock
import com.norfold.app.domain.NoteEmbedType
import com.norfold.app.domain.BlockDocument
import com.norfold.app.domain.BlockDocumentJson
import com.norfold.app.domain.DocLayoutJson
import com.norfold.app.domain.mergeSharedDocumentBlocks
import com.norfold.app.domain.NoteGestureAction
import com.norfold.app.domain.Notebook
import com.norfold.app.domain.OwnedDocument
import com.norfold.app.domain.ParsedSyncConflictReport
import com.norfold.app.domain.GoalItem
import com.norfold.app.domain.CalendarEventItem
import com.norfold.app.domain.Tag
import com.norfold.app.domain.TaskBoardItem
import com.norfold.app.domain.TaskChecklistItem
import com.norfold.app.domain.TaskColumnItem
import com.norfold.app.domain.SyncFolderAction
import com.norfold.app.domain.SyncFolderRequest
import com.norfold.app.domain.SyncProvider
import com.norfold.app.domain.TaskItem
import com.norfold.app.domain.TaskPriority
import com.norfold.app.domain.TaskPropertyDefinition
import com.norfold.app.domain.TaskPropertyType
import com.norfold.app.domain.TaskPropertyValue
import com.norfold.app.domain.TaskStatus
import com.norfold.app.domain.Workspace
import com.norfold.app.domain.WorkspaceActivity
import com.norfold.app.domain.WorkspaceComment
import com.norfold.app.domain.WorkspaceFileItem
import com.norfold.app.domain.WorkspaceIconKind
import com.norfold.app.domain.WorkspaceObject
import com.norfold.app.domain.WorkspaceObjectHistory
import com.norfold.app.domain.WorkspaceObjectLink
import com.norfold.app.domain.WorkspaceMember
import com.norfold.app.domain.WorkspaceObjectType
import com.norfold.app.domain.ThemeMode
import com.norfold.app.domain.ThemeProfile
import com.norfold.app.domain.VaultCrypto
import com.norfold.app.branding.palette
import com.norfold.app.cloud.MutualChatContacts
import com.norfold.app.cloud.NorfoldPresence
import com.norfold.app.cloud.NorfoldSocial
import com.norfold.app.cloud.NorfoldSupabase
import com.norfold.app.cloud.PushRegistrationClient
import com.norfold.app.cloud.KlipyClient
import com.norfold.app.cloud.KlipyGif
import com.norfold.app.data.ChatDrafts
import com.norfold.app.data.ChatReadMarkers
import com.norfold.app.data.ChatConversationPrefs
import com.norfold.app.cloud.DocumentPresenceState
import com.norfold.app.cloud.ChatBackend
import com.norfold.app.cloud.SecureChatBackend
import com.norfold.app.cloud.toWorkspaceMember
import com.norfold.app.ui.feedback.UiFeedback
import com.norfold.app.ui.feedback.UiFeedbackAction
import com.norfold.app.ui.feedback.UiFeedbackDuration
import com.norfold.app.ui.feedback.UiFeedbackTone
import androidx.compose.ui.graphics.toArgb
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

data class TeamInviteShare(
    val email: String,
    val token: String,
    val expiresAt: String,
)

data class PrivateChatArchiveUiState(
    val status: String = "Private Chat archive will start after an encrypted direct conversation exists.",
    val lastWrittenAt: Long? = null,
    val providerIds: Set<String> = emptySet(),
    val recoveryMaterial: String? = null,
)

data class OnboardingSetupPreferences(
    val useRecommendedDefaults: Boolean = true,
    val themeMode: ThemeMode = ThemeMode.System,
    val appLabelMode: ControlLabelMode = ControlLabelMode.Adaptive,
    val appIconFamily: AppIconFamily = AppIconFamily.MaterialSymbolsRounded,
    val editorIconFamily: EditorIconFamily = EditorIconFamily.UseApp,
    val fontScale: Float = 1f,
    val uiScale: Float = 1f,
    val editorToolbarScale: Float = 1f,
    val reduceMotion: Boolean = false,
)

data class DocsUiState(
    val notes: List<Note> = emptyList(),
    val notebooks: List<Notebook> = emptyList(),
    val tags: List<Tag> = emptyList(),
    val taskBoards: List<TaskBoardItem> = emptyList(),
    val taskColumns: List<TaskColumnItem> = emptyList(),
    val tasks: List<TaskItem> = emptyList(),
    val taskPropertyDefinitions: List<TaskPropertyDefinition> = emptyList(),
    val taskPropertyValues: List<TaskPropertyValue> = emptyList(),
    val taskChecklistItems: List<TaskChecklistItem> = emptyList(),
    val chatMessages: List<ChatMessageItem> = emptyList(),
    val chatChannels: List<ChatChannel> = emptyList(),
    val workspaceMembers: List<WorkspaceMember> = emptyList(),
    val activeChatChannelId: Long? = null,
    // Whether a conversation is open inside the Chat tab. False means the chat inbox (the
    // conversation list) is showing — the bottom navbar stays visible there; only an open
    // conversation swaps the navbar for the composer.
    val chatConversationOpen: Boolean = false,
    val workspaceObjects: List<WorkspaceObject> = emptyList(),
    val workspaceObjectLinks: List<WorkspaceObjectLink> = emptyList(),
    val workspaceActivities: List<WorkspaceActivity> = emptyList(),
    val workspaceObjectHistory: List<WorkspaceObjectHistory> = emptyList(),
    val workspaceComments: List<WorkspaceComment> = emptyList(),
    val workspaceFiles: List<WorkspaceFileItem> = emptyList(),
    val goals: List<GoalItem> = emptyList(),
    val calendarEvents: List<CalendarEventItem> = emptyList(),
    val workspaces: List<Workspace> = emptyList(),
    val settings: AppSettings = DocsRepository.defaultSettings,
    val selectedNote: Note? = null,
    val selectedObject: WorkspaceObject? = null,
    val destination: Destination = Destination.WorkspaceHub,
    val tab: HomeTab = HomeTab.AllNotes,
    val searchQuery: String = "",
    val selectedNotebookId: Long? = null,
    val selectedTagId: Long? = null,
    val locked: Boolean = false,
    val syncing: Boolean = false,
    val googleDriveConnected: Boolean = false,
    val sidebarOpen: Boolean = false,
    val conflictReport: ParsedSyncConflictReport? = null,
    val diagnostics: DiagnosticsState = DiagnosticsState(),
    val online: Boolean = true,
)

data class GoogleProfileSuggestion(
    val fullName: String = "",
    val nickname: String = "",
    val email: String = "",
    val avatarUri: String? = null,
)

enum class AppInitializationState {
    Loading,
    Ready,
    Failed,
}

enum class EmailAuthOperation {
    Idle,
    SignIn,
    SignUp,
    VerifyCode,
    ResendCode,
    RequestReset,
    UpdatePassword,
}

/** A one-shot request from the sidebar ToC to scroll the open Doc to a block. */
data class ScrollToBlockRequest(val blockId: String, val serial: Long)

/**
 * A one-shot request from the sidebar ToC to mutate a section of the open Doc. Applied by the
 * editor against its live [BlockEditorSession] (never the repository directly) so undo/redo and
 * the debounced autosave path stay authoritative.
 */
data class SectionActionRequest(val headingId: String, val action: DocSectionAction, val serial: Long)
data class BlockRestoreRequest(val blockId: String, val payloadJson: String?, val serial: Long)

enum class ScaleSetting { Font, Ui, Toolbar }

data class ScalePreviewState(
    val fontScale: Float,
    val uiScale: Float,
    val toolbarScale: Float,
    val remainingSeconds: Int = 20,
    val invalidInputs: Set<ScaleSetting> = emptySet(),
    val saving: Boolean = false,
) {
    fun applyTo(settings: AppSettings): AppSettings = settings.copy(
        fontScale = fontScale,
        uiScale = uiScale,
        editorToolbarScale = toolbarScale,
    )
}

private data class NavigationState(
    val selectedNoteId: Long?,
    val selectedObjectId: Long?,
    val destination: Destination,
    val tab: HomeTab,
    val searchQuery: String,
    val selectedNotebookId: Long?,
    val selectedTagId: Long?,
    val locked: Boolean,
    val syncing: Boolean,
    val sidebarOpen: Boolean,
    val online: Boolean,
)

private data class BaseUiState(
    val notes: List<Note>,
    val notebooks: List<Notebook>,
    val tags: List<Tag>,
    val settings: AppSettings,
    val navigation: NavigationState,
)

private data class WorkspaceDataState(
    val taskBoards: List<TaskBoardItem>,
    val taskColumns: List<TaskColumnItem>,
    val tasks: List<TaskItem>,
    val taskPropertyDefinitions: List<TaskPropertyDefinition>,
    val taskPropertyValues: List<TaskPropertyValue>,
    val taskChecklistItems: List<TaskChecklistItem>,
    val chatMessages: List<ChatMessageItem>,
    val chatChannels: List<ChatChannel>,
    val workspaceMembers: List<WorkspaceMember>,
    val activeChatChannelId: Long?,
    val chatConversationOpen: Boolean,
    val workspaceObjects: List<WorkspaceObject>,
    val workspaceObjectLinks: List<WorkspaceObjectLink>,
    val workspaceActivities: List<WorkspaceActivity>,
    val workspaceObjectHistory: List<WorkspaceObjectHistory>,
    val workspaceComments: List<WorkspaceComment>,
    val workspaceFiles: List<WorkspaceFileItem>,
    val workspaces: List<Workspace>,
    val goals: List<GoalItem>,
    val calendarEvents: List<CalendarEventItem>,
)

private data class PendingDeletionState(
    val noteIds: Set<Long> = emptySet(),
    val taskIds: Set<Long> = emptySet(),
    val calendarEventIds: Set<Long> = emptySet(),
    val goalIds: Set<Long> = emptySet(),
)

private data class PendingFeedbackResolution(
    val fallback: Job,
    val onAction: suspend () -> Unit,
    val onDismiss: suspend () -> Unit,
)

/** Immutable payload for the first delivery attempt; failed attempts are rebuilt from Room. */
private data class PendingTeamChatSend(
    val remoteWorkspaceId: String,
    val deviceId: String,
    val username: String,
    val displayName: String,
    val body: String,
    val color: Long,
    val createdAt: Long,
    val syncId: String,
    val idempotencyKey: String,
    val replyPreview: String? = null,
    val channelSyncId: String? = null,
)

/** One file working its way to the team; drives the upload strip above the chat composer. */
data class ChatUploadItem(
    val id: Long,
    val displayName: String,
    val sizeBytes: Long,
    val status: ChatUploadStatus,
)

enum class ChatUploadStatus { Queued, Uploading, Failed }

/** Playback state for the inline voice-message player in chat bubbles. */
data class ChatAudioState(
    val messageId: Long,
    val playing: Boolean,
    val durationMs: Int,
    val positionMs: Int,
    val speed: Float = 1f,
)

/** Live microphone state used by the composer. Amplitudes are normalized 0..1 samples. */
data class VoiceRecordingState(
    val startedAt: Long,
    val elapsedMs: Long = 0L,
    val amplitudes: List<Float> = emptyList(),
    val paused: Boolean = false,
)

data class ChatAttachmentPreviewState(
    val message: ChatMessageItem,
    val source: String? = null,
    val loading: Boolean = true,
    val downloadProgress: Float? = null,
    val pdfPages: Map<Int, Bitmap> = emptyMap(),
    val pdfPageCount: Int = 0,
    val error: String? = null,
)

data class GifPickerState(
    val query: String = "",
    val loading: Boolean = false,
    val results: List<KlipyGif> = emptyList(),
    val error: String? = null,
)

/** A queued team delivery: upload the bytes, then send the referencing chat message. */
private data class ChatUploadTask(
    val localWorkspaceId: Long,
    val localId: Long,
    val uri: String,
    val name: String,
    val mime: String,
    val size: Long,
    val body: String,
    val color: Long,
    val createdAt: Long,
    val username: String,
    val authorDisplayName: String,
    val syncId: String,
    val idempotencyKey: String,
    val channelSyncId: String? = null,
    var attempts: Int = 0,
    var failed: Boolean = false,
)

sealed interface AccountCoordinatorState {
    data object LocalInitializing : AccountCoordinatorState
    data object SignedOut : AccountCoordinatorState
    data object CloudSessionReady : AccountCoordinatorState
    data object DiscoveringBackupsAndMemberships : AccountCoordinatorState
    data object RestoreChoiceRequired : AccountCoordinatorState
    data object BackupSetupRequired : AccountCoordinatorState
    data object Ready : AccountCoordinatorState
    data class RecoverableError(val message: String) : AccountCoordinatorState
}

enum class CloudAuthProvider { None, Google, Email }

@OptIn(ExperimentalCoroutinesApi::class)
class DocsViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application
    private val database = NorfoldDatabase.get(application)
    private val repository = DocsRepository(database)
    private val syncStore = CloudFolderSyncStore(application)
    private val googleDriveAuthStore = GoogleDriveAuthStore(application)
    private val googleDriveApiSyncStore = GoogleDriveApiSyncStore(application, googleDriveAuthStore)
    private val privateChatArchive = PrivateChatArchiveCoordinator(
        database = database,
        crypto = TinkChatArchiveCrypto(application),
        localProvider = LocalChatArchiveProvider(application),
        cloudProvider = GoogleDriveChatArchiveProvider(googleDriveApiSyncStore),
    )
    private val installationIdentity = InstallationIdentityStore(application)
    private val syncSecretStore = SyncSecretStore(application)
    private val pushRegistrationClient = PushRegistrationClient(application)
    private val secureChatBackend = SecureChatBackend(application)
    private val _privateChatArchiveState = MutableStateFlow(PrivateChatArchiveUiState())
    val privateChatArchiveState: StateFlow<PrivateChatArchiveUiState> =
        _privateChatArchiveState.asStateFlow()
    private var privateChatArchiveJob: Job? = null
    private var privateChatArchiveRestorePending = false
    private var privateChatArchiveDiscoveryComplete = false
    private val backupStore = BackupFolderStore(application)
    private val diagnosticsStore = DiagnosticsStore(application)
    private val networkMonitor = NetworkMonitor(application)
    private val destination = MutableStateFlow(Destination.WorkspaceHub)
    private val tab = MutableStateFlow(HomeTab.AllNotes)
    private val searchQuery = MutableStateFlow("")
    private val selectedNotebookFilterId = MutableStateFlow<Long?>(null)
    private val selectedTagFilterId = MutableStateFlow<Long?>(null)
    private val selectedNoteId = MutableStateFlow<Long?>(null)
    private val selectedDocumentOwner = MutableStateFlow<DocumentOwner?>(null)
    private val selectedObjectId = MutableStateFlow<Long?>(null)
    private val locked = MutableStateFlow(false)
    private val syncing = MutableStateFlow(false)
    private val googleDriveConnected = MutableStateFlow(googleDriveAuthStore.hasAuthState())
    private val networkOnline = networkMonitor.isOnline
        .stateIn(viewModelScope, SharingStarted.Eagerly, networkMonitor.currentlyOnline())
    private val sidebarOpen = MutableStateFlow(false)
    private val _pendingSettingsSection = MutableStateFlow<String?>(null)
    val pendingSettingsSection: StateFlow<String?> = _pendingSettingsSection.asStateFlow()
    private val _scalePreview = MutableStateFlow<ScalePreviewState?>(null)
    val scalePreview: StateFlow<ScalePreviewState?> = _scalePreview.asStateFlow()
    val deviceSettingsProfiles = repository.deviceSettingsProfiles
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    private var scalePreviewJob: Job? = null
    private var scalePreviewCountdownPaused = false
    private val _cloudAccountEmail = MutableStateFlow(NorfoldSupabase.currentAccountEmail())
    val cloudAccountEmail: StateFlow<String?> = _cloudAccountEmail.asStateFlow()
    private val _cloudAuthProvider = MutableStateFlow(CloudAuthProvider.None)
    val cloudAuthProvider: StateFlow<CloudAuthProvider> = _cloudAuthProvider.asStateFlow()
    // Telegram phone verification. verifiedPhone is the authoritative E.164 from the server;
    // pendingVerificationId tracks an in-flight session so a return/resume can re-check it.
    // The return deep link never sets verified — only a server read does.
    private val _verifiedPhone = MutableStateFlow<String?>(null)
    val verifiedPhone: StateFlow<String?> = _verifiedPhone.asStateFlow()
    private val _phoneVerificationInProgress = MutableStateFlow(false)
    val phoneVerificationInProgress: StateFlow<Boolean> = _phoneVerificationInProgress.asStateFlow()
    private var pendingVerificationId: String? = null
    private val _accountCoordinatorState = MutableStateFlow<AccountCoordinatorState>(AccountCoordinatorState.LocalInitializing)
    val accountCoordinatorState: StateFlow<AccountCoordinatorState> = _accountCoordinatorState.asStateFlow()
    private val _driveBackups = MutableStateFlow<List<GoogleDriveBackupManifest>>(emptyList())
    val driveBackups: StateFlow<List<GoogleDriveBackupManifest>> = _driveBackups.asStateFlow()
    private var restoredBackupCategories: Set<BackupCategory> = emptySet()
    private val _teamWorkspaceRemoteId = MutableStateFlow<String?>(null)
    val teamWorkspaceRemoteId: StateFlow<String?> = _teamWorkspaceRemoteId.asStateFlow()
    private val _teamCollaborationStatus = MutableStateFlow("Local collaboration")
    val teamCollaborationStatus: StateFlow<String> = _teamCollaborationStatus.asStateFlow()
    private val _workspaceChatSecurity = MutableStateFlow<ChatBackend.WorkspaceSecuritySnapshot?>(null)
    val workspaceChatSecurity: StateFlow<ChatBackend.WorkspaceSecuritySnapshot?> =
        _workspaceChatSecurity.asStateFlow()
    private val _teamInviteShare = MutableStateFlow<TeamInviteShare?>(null)
    val teamInviteShare: StateFlow<TeamInviteShare?> = _teamInviteShare.asStateFlow()
    private val _documentPresence = MutableStateFlow<List<DocumentPresenceState>>(emptyList())
    val documentPresence: StateFlow<List<DocumentPresenceState>> = _documentPresence.asStateFlow()
    private val _documentPresenceStatus = MutableStateFlow("Local document")
    val documentPresenceStatus: StateFlow<String> = _documentPresenceStatus.asStateFlow()
    private val localDocumentPresence = MutableStateFlow<DocumentPresenceState?>(null)
    private var documentPresenceJob: Job? = null
    private var documentContentJob: Job? = null
    private val sharedDocumentSyncMutex = Mutex()
    private val _activeDocumentBlockId = MutableStateFlow<String?>(null)
    val activeDocumentBlockId: StateFlow<String?> = _activeDocumentBlockId.asStateFlow()
    private val teamChatRefreshMutex = Mutex()
    // Newest remote created_at seen for the bound team workspace, so the 4 s chat poll asks the
    // server only for rows at/after it instead of refetching the whole table every tick.
    private var teamChatCursorWorkspaceId: String? = null
    private var teamChatNewestCreatedAt: String? = null
    private var scrollToBlockSerial = 0L
    private val _scrollToBlockRequest = MutableStateFlow<ScrollToBlockRequest?>(null)
    val scrollToBlockRequest: StateFlow<ScrollToBlockRequest?> = _scrollToBlockRequest.asStateFlow()
    private var sectionActionSerial = 0L
    private val _sectionActionRequest = MutableStateFlow<SectionActionRequest?>(null)
    val sectionActionRequest: StateFlow<SectionActionRequest?> = _sectionActionRequest.asStateFlow()
    private var blockRestoreSerial = 0L
    private val _blockRestoreRequest = MutableStateFlow<BlockRestoreRequest?>(null)
    val blockRestoreRequest: StateFlow<BlockRestoreRequest?> = _blockRestoreRequest.asStateFlow()
    private val conflictReport = MutableStateFlow<ParsedSyncConflictReport?>(null)
    private val diagnostics = MutableStateFlow(diagnosticsStore.state())
    private val feedbackChannel = Channel<UiFeedback>(Channel.BUFFERED)
    val feedbackEvents = feedbackChannel.receiveAsFlow()
    private var feedbackSerial = 0L
    private val pendingFeedbackResolutions = mutableMapOf<Long, PendingFeedbackResolution>()
    private val pendingNoteDeleteIds = MutableStateFlow<Set<Long>>(emptySet())
    private val pendingTaskDeleteIds = MutableStateFlow<Set<Long>>(emptySet())
    private val pendingCalendarEventDeleteIds = MutableStateFlow<Set<Long>>(emptySet())
    private val pendingGoalDeleteIds = MutableStateFlow<Set<Long>>(emptySet())
    // null == General. Reset to General whenever the active workspace changes.
    private val activeChatChannelId = MutableStateFlow<Long?>(null)
    // True while a conversation is open inside the Chat tab; false shows the chat inbox.
    private val chatConversationOpen = MutableStateFlow(false)
    // Live realtime subscription for the open account (DM/group) conversation, if any.
    // Cancelled and replaced whenever a different account conversation is opened.
    private var accountConversationObserver: Job? = null
    private var accountConversationObserverId: String? = null
    // Account-wide encrypted relay drain. It is independent of the currently open
    // conversation so messages persist locally even while the user is elsewhere.
    private var secureRelayObserver: Job? = null
    // Which chat-context navbar tab is active on the inbox (Chats/People/Requests/You).
    private val _chatSection = MutableStateFlow(com.norfold.app.domain.ChatSection.Chats)
    val chatSection: StateFlow<com.norfold.app.domain.ChatSection> = _chatSection.asStateFlow()
    // Read-watermark serial: bumped after every markRead so inbox unread badges recompute.
    // The watermarks themselves live in ChatReadMarkers (SharedPreferences).
    private val _chatReadSerial = MutableStateFlow(0L)
    val chatReadSerial: StateFlow<Long> = _chatReadSerial.asStateFlow()
    // Live workspace presence roster (online/away), populated while the Chat tab is visible.
    private val _workspacePresence = MutableStateFlow<List<NorfoldPresence.WorkspacePresenceState>>(emptyList())
    val workspacePresence: StateFlow<List<NorfoldPresence.WorkspacePresenceState>> = _workspacePresence.asStateFlow()
    private val _accountConversationPresence = MutableStateFlow<List<NorfoldPresence.WorkspacePresenceState>>(emptyList())
    val accountConversationPresence: StateFlow<List<NorfoldPresence.WorkspacePresenceState>> =
        _accountConversationPresence.asStateFlow()
    // Own availability claim, tracked into the presence channel (online in foreground, away otherwise).
    private val chatAvailability = MutableStateFlow(NorfoldPresence.Availability.Online)
    // Ephemeral Realtime presence state. Never persisted and automatically expires remotely.
    private val chatTyping = MutableStateFlow<String?>(null)
    private val pendingDeletionState = combine(
        pendingNoteDeleteIds,
        pendingTaskDeleteIds,
        pendingCalendarEventDeleteIds,
        pendingGoalDeleteIds,
    ) { noteIds, taskIds, calendarEventIds, goalIds ->
        PendingDeletionState(noteIds, taskIds, calendarEventIds, goalIds)
    }
    private val _initializationState = MutableStateFlow(AppInitializationState.Loading)
    val initializationState: StateFlow<AppInitializationState> = _initializationState.asStateFlow()
    /** Sub-state of the email auth flow: code entry after signup, code + new password during recovery. */
    val emailAuthPhase = MutableStateFlow<EmailAuthPhase>(EmailAuthPhase.None)
    private val _emailAuthOperation = MutableStateFlow(EmailAuthOperation.Idle)
    val emailAuthOperation: StateFlow<EmailAuthOperation> = _emailAuthOperation.asStateFlow()
    private val _googleProfileSuggestion = MutableStateFlow<GoogleProfileSuggestion?>(null)
    val googleProfileSuggestion: StateFlow<GoogleProfileSuggestion?> = _googleProfileSuggestion.asStateFlow()
    private var emailResendAvailableAtElapsedMs = 0L
    private var sessionSyncSecret: String? = null
    private var syncCooldownDeadlineElapsedMs = 0L
    private var backgroundedAtElapsedMs: Long? = null
    private var failedUnlockAttempts = 0
    private var unlockBlockedUntilElapsedMs = 0L

    // The notes list always shows every active note; search lives on the dedicated Search page
    // (driven by searchQuery) so browsing the list is never left in a filtered state.
    private val notes = combine(repository.activeNotes, pendingNoteDeleteIds) { noteList, hiddenIds ->
        noteList.filterNot { it.id in hiddenIds }
    }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val activeDocument: StateFlow<OwnedDocument?> = selectedDocumentOwner
        .flatMapLatest { owner -> owner?.let(repository::observeDocument) ?: flowOf(null) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val primaryNavigation = combine<Any?, NavigationState>(selectedNoteId, selectedObjectId, destination, tab, searchQuery, selectedNotebookFilterId, selectedTagFilterId) { values ->
        @Suppress("UNCHECKED_CAST")
        val selectedId = values[0] as Long?
        val selectedObjId = values[1] as Long?
        val currentDestination = values[2] as Destination
        val currentTab = values[3] as HomeTab
        val query = values[4] as String
        val currentNotebookFilter = values[5] as Long?
        val currentTagFilter = values[6] as Long?
        NavigationState(
            selectedNoteId = selectedId,
            selectedObjectId = selectedObjId,
            destination = currentDestination,
            tab = currentTab,
            searchQuery = query,
            selectedNotebookId = currentNotebookFilter,
            selectedTagId = currentTagFilter,
            locked = false,
            syncing = false,
            sidebarOpen = false,
            online = true,
        )
    }

    private val navigation = combine(primaryNavigation, locked, syncing, sidebarOpen, networkOnline) { current, isLocked, isSyncing, isSidebarOpen, isOnline ->
        current.copy(locked = isLocked, syncing = isSyncing, sidebarOpen = isSidebarOpen, online = isOnline)
    }

    private val baseState = combine(
        notes,
        repository.notebooks,
        repository.tags,
        repository.settings,
        navigation,
    ) { noteList, notebooks, tags, settings, currentNavigation ->
        BaseUiState(noteList, notebooks, tags, settings, currentNavigation)
    }

    @Suppress("UNCHECKED_CAST")
    private val workspaceData = combine(
        repository.taskBoards,
        repository.taskColumns,
        repository.tasks,
        repository.taskPropertyDefinitions,
        repository.taskPropertyValues,
        repository.taskChecklistItems,
        repository.chatMessages,
        repository.workspaceObjects,
        repository.workspaceObjectLinks,
        repository.workspaceActivities,
        repository.workspaceObjectHistory,
        repository.workspaceComments,
        repository.workspaceFiles,
        repository.workspaces,
        repository.goals,
        repository.calendarEvents,
        pendingDeletionState,
        repository.chatChannels,
        activeChatChannelId,
        repository.workspaceMembers,
        chatConversationOpen,
    ) { values ->
        val pending = values[16] as PendingDeletionState
        val visibleObjects = (values[7] as List<WorkspaceObject>).filterNot { obj ->
            when (obj.objectType) {
                WorkspaceObjectType.Note -> obj.sourceId in pending.noteIds
                WorkspaceObjectType.Task -> obj.sourceId in pending.taskIds
                WorkspaceObjectType.CalendarEvent -> obj.sourceId in pending.calendarEventIds
                WorkspaceObjectType.Goal -> obj.sourceId in pending.goalIds
                else -> false
            }
        }
        WorkspaceDataState(
            taskBoards = values[0] as List<TaskBoardItem>,
            taskColumns = values[1] as List<TaskColumnItem>,
            tasks = (values[2] as List<TaskItem>).filterNot { it.id in pending.taskIds },
            taskPropertyDefinitions = values[3] as List<TaskPropertyDefinition>,
            taskPropertyValues = values[4] as List<TaskPropertyValue>,
            taskChecklistItems = values[5] as List<TaskChecklistItem>,
            chatMessages = values[6] as List<ChatMessageItem>,
            chatChannels = values[17] as List<ChatChannel>,
            workspaceMembers = values[19] as List<WorkspaceMember>,
            activeChatChannelId = values[18] as Long?,
            chatConversationOpen = values[20] as Boolean,
            workspaceObjects = visibleObjects,
            workspaceObjectLinks = values[8] as List<WorkspaceObjectLink>,
            workspaceActivities = values[9] as List<WorkspaceActivity>,
            workspaceObjectHistory = values[10] as List<WorkspaceObjectHistory>,
            workspaceComments = values[11] as List<WorkspaceComment>,
            workspaceFiles = values[12] as List<WorkspaceFileItem>,
            workspaces = values[13] as List<Workspace>,
            goals = (values[14] as List<GoalItem>).filterNot { it.id in pending.goalIds },
            calendarEvents = (values[15] as List<CalendarEventItem>).filterNot { it.id in pending.calendarEventIds },
        )
    }

    val state = combine(baseState, workspaceData, googleDriveConnected, conflictReport, diagnostics) { base, workspace, isGoogleDriveConnected, currentConflictReport, diagnosticsState ->
        DocsUiState(
            notes = base.notes,
            notebooks = base.notebooks,
            tags = base.tags,
            taskBoards = workspace.taskBoards,
            taskColumns = workspace.taskColumns,
            tasks = workspace.tasks,
            taskPropertyDefinitions = workspace.taskPropertyDefinitions,
            taskPropertyValues = workspace.taskPropertyValues,
            taskChecklistItems = workspace.taskChecklistItems,
            chatMessages = workspace.chatMessages,
            chatChannels = workspace.chatChannels,
            workspaceMembers = workspace.workspaceMembers,
            activeChatChannelId = workspace.activeChatChannelId,
            chatConversationOpen = workspace.chatConversationOpen,
            workspaceObjects = workspace.workspaceObjects,
            workspaceObjectLinks = workspace.workspaceObjectLinks,
            workspaceActivities = workspace.workspaceActivities,
            workspaceObjectHistory = workspace.workspaceObjectHistory,
            workspaceComments = workspace.workspaceComments,
            workspaceFiles = workspace.workspaceFiles,
            workspaces = workspace.workspaces,
            goals = workspace.goals,
            calendarEvents = workspace.calendarEvents,
            settings = base.settings,
            // No first-note fallback: attachment/cover/embed actions must never target an
            // arbitrary Doc when the selected id no longer resolves (e.g. it was just deleted).
            selectedNote = base.notes.firstOrNull { it.id == base.navigation.selectedNoteId },
            selectedObject = workspace.workspaceObjects.firstOrNull { it.id == base.navigation.selectedObjectId },
            destination = base.navigation.destination,
            tab = base.navigation.tab,
            searchQuery = base.navigation.searchQuery,
            selectedNotebookId = base.navigation.selectedNotebookId,
            selectedTagId = base.navigation.selectedTagId,
            locked = base.navigation.locked,
            syncing = base.navigation.syncing,
            googleDriveConnected = isGoogleDriveConnected,
            sidebarOpen = base.navigation.sidebarOpen,
            conflictReport = currentConflictReport,
            diagnostics = diagnosticsState,
            online = base.navigation.online,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DocsUiState())

    init {
        repository.setActiveChatAccount(NorfoldSupabase.currentAccountId())
        initialize()
        viewModelScope.launch {
            repository.chatMessages.collect { schedulePrivateChatArchive() }
        }
        // Session restore from disk finishes after process start; without this, a signed-in user
        // cold-starts into "sign in to sync" until they touch an auth screen.
        NorfoldSupabase.sessionStatusFlow()?.let { statusFlow ->
            viewModelScope.launch {
                statusFlow.collect {
                    refreshCloudAccountState()
                    refreshTeamBinding()
                    if (NorfoldSupabase.isSignedIn()) {
                        coordinateCloudAccount()
                        schedulePrivateChatArchive(delayMs = 1_000L)
                    }
                }
            }
        }
    }

    private fun schedulePrivateChatArchive(delayMs: Long = 12_000L) {
        if (
            !NorfoldSupabase.isSignedIn() ||
            !privateChatArchiveDiscoveryComplete ||
            privateChatArchiveRestorePending
        ) return
        privateChatArchiveJob?.cancel()
        privateChatArchiveJob = viewModelScope.launch {
            delay(delayMs)
            if (state.value.chatChannels.none { it.scope == ChatScope.Account }) return@launch
            writePrivateChatArchive(showFeedback = false)
        }
    }

    fun writePrivateChatArchiveNow() {
        if (!privateChatArchiveDiscoveryComplete || privateChatArchiveRestorePending) {
            emitInfo("Choose whether to restore the earlier private Chat archive first")
            return
        }
        privateChatArchiveJob?.cancel()
        privateChatArchiveJob = viewModelScope.launch { writePrivateChatArchive(showFeedback = true) }
    }

    private suspend fun writePrivateChatArchive(showFeedback: Boolean) {
        val accountId = NorfoldSupabase.currentAccountId() ?: return
        _privateChatArchiveState.value = _privateChatArchiveState.value.copy(
            status = "Encrypting private Chat archive…",
        )
        runCatching {
            privateChatArchive.write(
                accountId = accountId,
                includeCloud = googleDriveAuthStore.hasAuthState(),
            )
        }.onSuccess { result ->
            _privateChatArchiveState.value = _privateChatArchiveState.value.copy(
                status = if (result.providerFailures.isEmpty()) {
                    "Private Chat archive protected"
                } else {
                    "Saved locally · cloud archive needs attention"
                },
                lastWrittenAt = result.updatedAt,
                providerIds = result.providersWritten,
            )
            runCatching { secureChatBackend.registerCurrentDevice(archiveCapable = true) }
            if (showFeedback) emitSuccess("Private Chat archive updated")
        }.onFailure {
            _privateChatArchiveState.value = _privateChatArchiveState.value.copy(
                status = "Private Chat archive failed · tap to retry",
            )
            if (showFeedback) emitError("Couldn't update the private Chat archive. Your device history is unchanged.")
        }
    }

    fun revealPrivateChatRecoveryMaterial() = viewModelScope.launch(Dispatchers.IO) {
        val accountId = NorfoldSupabase.currentAccountId() ?: return@launch
        runCatching { privateChatArchive.getOrCreateRecoveryMaterial(accountId) }
            .onSuccess { material ->
                _privateChatArchiveState.value = _privateChatArchiveState.value.copy(
                    recoveryMaterial = material,
                )
            }
            .onFailure { emitError("Couldn't unlock private Chat recovery material on this device.") }
    }

    fun hidePrivateChatRecoveryMaterial() {
        _privateChatArchiveState.value = _privateChatArchiveState.value.copy(recoveryMaterial = null)
    }

    fun restorePrivateChatArchive(providerId: String, recoveryMaterial: String? = null) =
        viewModelScope.launch {
            val accountId = NorfoldSupabase.currentAccountId() ?: return@launch
            _privateChatArchiveState.value = _privateChatArchiveState.value.copy(
                status = "Restoring private Chat archive…",
            )
            runCatching {
                privateChatArchive.restore(accountId, providerId, recoveryMaterial)
            }.onSuccess(::handlePrivateChatArchiveRestore)
                .onFailure {
                    _privateChatArchiveState.value = _privateChatArchiveState.value.copy(
                        status = "Private Chat restore failed · history was not replaced",
                    )
                    emitError("Couldn't restore that private Chat archive. Check the provider and recovery material.")
                }
        }

    private fun handlePrivateChatArchiveRestore(result: PrivateChatArchiveRestoreResult) {
        privateChatArchiveRestorePending = false
        _privateChatArchiveState.value = _privateChatArchiveState.value.copy(
            status = "Private Chat history restored",
            providerIds = _privateChatArchiveState.value.providerIds + result.providerId,
        )
        emitSuccess(
            "Restored ${result.messagesAdded} messages in ${result.conversationsAdded} conversations",
        )
    }

    fun keepCurrentPrivateChatHistory() {
        privateChatArchiveRestorePending = false
        _privateChatArchiveState.value = _privateChatArchiveState.value.copy(
            status = "Current device history kept · archive will update after changes",
        )
        schedulePrivateChatArchive(delayMs = 1_000L)
    }

    private suspend fun discoverPrivateChatArchive(accountId: String) {
        privateChatArchiveDiscoveryComplete = false
        val providers = privateChatArchive.availableProviders(accountId)
        val hasLocalHistory = privateChatArchive.hasLocalAccountHistory(accountId)
        privateChatArchiveRestorePending = providers.isNotEmpty() && !hasLocalHistory
        privateChatArchiveDiscoveryComplete = true
        _privateChatArchiveState.value = _privateChatArchiveState.value.copy(
            status = when {
                privateChatArchiveRestorePending ->
                    "Earlier encrypted Chat archive found · choose restore or keep this device"
                providers.isNotEmpty() -> "Private Chat archive protected"
                else -> "Private Chat archive will start after an encrypted direct conversation exists."
            },
            providerIds = providers,
        )
    }

    fun retryInitialization() {
        if (_initializationState.value != AppInitializationState.Loading) initialize()
    }

    private val InitLogTag = "NorfoldInit"

    private fun initialize() {
        _initializationState.value = AppInitializationState.Loading
        viewModelScope.launch {
            try {
                Log.i(InitLogTag, "seed")
                repository.ensureSeedData()
                repairLegacyCallingName()
                // A process restart is a new authentication session. Resolve the persisted vault
                // state before exposing workspace content so a protected vault never flashes or
                // cold-starts unlocked while the settings flow catches up.
                Log.i(InitLogTag, "vault")
                locked.value = repository.settingsValue().vaultLockEnabled
                // Before the index rebuild so the swept drafts' workspace objects get pruned too.
                Log.i(InitLogTag, "sweep")
                sweepOrphanedDrafts()
                Log.i(InitLogTag, "index")
                repository.rebuildWorkspaceIndex()
                Log.i(InitLogTag, "cloud")
                refreshCloudAccountState()
                refreshTeamBinding()
                NorfoldWorkScheduler.reconcile(app, repository.settingsValue())
                if (NorfoldSupabase.isSignedIn()) coordinateCloudAccount()
                // Attachment deliveries interrupted by a process death resume where they left off.
                Log.i(InitLogTag, "uploads")
                restoreChatUploadQueue()
                drainChatUploads()
                Log.i(InitLogTag, "ready")
                _initializationState.value = AppInitializationState.Ready
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                _initializationState.value = AppInitializationState.Failed
                emitError(
                    "Couldn't open the workspace. Your local data was not changed.",
                    actionLabel = "Retry",
                    action = UiFeedbackAction.RetryInitialization,
                )
            }
        }
    }

    /** Repairs the old onboarding mapping once without overwriting a profile edited by hand. */
    private suspend fun repairLegacyCallingName() {
        val preferences = app.getSharedPreferences("norfold_identity_migrations", Context.MODE_PRIVATE)
        if (preferences.getBoolean("calling_name_v1_complete", false)) return
        val current = repository.settingsValue()
        val explicitlyEdited = preferences.getBoolean("profile_name_explicitly_edited", false)
        if (
            current.onboardingComplete && !explicitlyEdited &&
            current.syncUserName.isNotBlank() && current.syncPublicName.isNotBlank() &&
            !current.syncUserName.equals(current.syncPublicName, ignoreCase = true)
        ) {
            repository.updateSettings(current.copy(syncPublicName = current.syncUserName.trim()))
        }
        preferences.edit().putBoolean("calling_name_v1_complete", true).apply()
    }

    private fun emitFeedback(
        text: String,
        tone: UiFeedbackTone = UiFeedbackTone.Info,
        actionLabel: String? = null,
        action: UiFeedbackAction? = null,
        duration: UiFeedbackDuration = if (actionLabel == null) UiFeedbackDuration.Short else UiFeedbackDuration.Long,
    ) {
        feedbackChannel.trySend(
            UiFeedback(
                id = ++feedbackSerial,
                message = text,
                tone = tone,
                actionLabel = actionLabel,
                action = action,
                duration = duration,
            ),
        )
    }

    private fun emitInfo(text: String) = emitFeedback(text)

    private fun emitSuccess(text: String, actionLabel: String? = null, action: UiFeedbackAction? = null) =
        emitFeedback(text, UiFeedbackTone.Success, actionLabel, action)

    private fun emitError(text: String, actionLabel: String? = null, action: UiFeedbackAction? = null) =
        emitFeedback(
            text,
            UiFeedbackTone.Error,
            actionLabel,
            action,
            if (actionLabel == null) UiFeedbackDuration.Long else UiFeedbackDuration.Indefinite,
        )

    fun resolveFeedbackAction(action: UiFeedbackAction?, actionPerformed: Boolean) {
        when (action) {
            is UiFeedbackAction.Resolve -> {
                val resolution = pendingFeedbackResolutions.remove(action.token) ?: return
                resolution.fallback.cancel()
                viewModelScope.launch {
                    if (actionPerformed) resolution.onAction() else resolution.onDismiss()
                }
            }
            UiFeedbackAction.RetrySync -> if (actionPerformed) {
                syncConfiguredNow()
            }
            UiFeedbackAction.RetryInitialization -> if (actionPerformed) retryInitialization()
            null -> Unit
        }
    }

    private fun registerFeedbackResolution(
        onAction: suspend () -> Unit,
        onDismiss: suspend () -> Unit = {},
        fallbackDelayMs: Long = 5 * 60_000L,
    ): UiFeedbackAction.Resolve {
        val token = ++feedbackSerial
        val fallback = viewModelScope.launch {
            delay(fallbackDelayMs)
            pendingFeedbackResolutions.remove(token)?.onDismiss?.invoke()
        }
        pendingFeedbackResolutions[token] = PendingFeedbackResolution(fallback, onAction, onDismiss)
        return UiFeedbackAction.Resolve(token)
    }

    private val navigationHistory = ArrayDeque<Destination>()
    private val _highlightedChatMessageId = MutableStateFlow<Long?>(null)
    val highlightedChatMessageId: StateFlow<Long?> = _highlightedChatMessageId.asStateFlow()

    /** Briefly flash a chat message (reply-to-jump / pinned-banner tap), then clear the marker. */
    fun flashChatMessage(localId: Long) {
        _highlightedChatMessageId.value = localId
        viewModelScope.launch {
            kotlinx.coroutines.delay(1400)
            if (_highlightedChatMessageId.value == localId) _highlightedChatMessageId.value = null
        }
    }

    // Draft-until-edited: docs/tasks created from the + button are discarded silently
    // when the user leaves without giving them a title or any content.
    private val draftNoteIds = mutableSetOf<Long>()
    private val draftTaskIds = mutableSetOf<Long>()

    // Leaving the editor races its 500 ms debounced autosave, so the keep-or-delete call is
    // deferred: leave parks the draft id here and the editor's dispose-time flush (which goes
    // through updateActiveDocument) gets to land before finalizeDraftDiscard decides.
    private val pendingDraftDiscardIds = mutableSetOf<Long>()
    private val editorSaveJobs = mutableSetOf<Job>()

    // The sets above die with the process, which used to leak an empty "Untitled doc" after
    // process death mid-draft. Mirror the ids to prefs and sweep survivors on next launch.
    private val draftRecoveryPrefs = app.getSharedPreferences("norfold_draft_recovery", 0)

    private fun persistDraftIds() {
        draftRecoveryPrefs.edit()
            .putStringSet("notes", (draftNoteIds + pendingDraftDiscardIds).mapTo(mutableSetOf()) { it.toString() })
            .putStringSet("tasks", draftTaskIds.mapTo(mutableSetOf()) { it.toString() })
            .apply()
    }

    /** Deletes drafts recorded by a previous process that died before deciding their fate. */
    private suspend fun sweepOrphanedDrafts() {
        val notes = draftRecoveryPrefs.getStringSet("notes", emptySet()).orEmpty().mapNotNull { it.toLongOrNull() }
        val tasks = draftRecoveryPrefs.getStringSet("tasks", emptySet()).orEmpty().mapNotNull { it.toLongOrNull() }
        if (notes.isEmpty() && tasks.isEmpty()) return
        notes.forEach { id ->
            val note = repository.noteById(id) ?: return@forEach
            if (note.isUntouchedDraft()) repository.deleteNote(note)
        }
        tasks.forEach { id ->
            val task = repository.taskById(id) ?: return@forEach
            if (task.title == "New task" && task.description.isBlank() && task.attachmentUri == null) repository.deleteTask(task)
        }
        persistDraftIds()
    }

    private fun Note.isUntouchedDraft(): Boolean {
        if (title.isNotBlank() && title !in setOf("Untitled doc", "Untitled note", "Untitled document")) return false
        val blocks = document.blocks
        // Only a blank paragraph counts as untouched: any other block type (image, table,
        // chart, heading, …) is real content even when its plainText() is empty.
        return blocks.isEmpty() ||
            (blocks.size == 1 && blocks.first().let { it is ParagraphBlock && it.plainText().isBlank() })
    }

    /**
     * Called by the editor after its dispose-time flush has been handed to [updateActiveDocument].
     * Waits for every in-flight editor save, then deletes the parked draft only if the persisted
     * row is still genuinely untouched.
     */
    fun finalizeDraftDiscard(owner: DocumentOwner) {
        if (owner.type != DocumentOwnerType.Note || owner.id !in pendingDraftDiscardIds) return
        val saves = editorSaveJobs.toList()
        viewModelScope.launch {
            saves.forEach { it.join() }
            if (!pendingDraftDiscardIds.remove(owner.id)) return@launch
            persistDraftIds()
            val note = repository.noteById(owner.id) ?: return@launch
            if (note.isUntouchedDraft()) repository.deleteNote(note)
        }
    }

    private fun discardUntouchedDrafts(leaving: Destination) {
        when (leaving) {
            Destination.NoteEditor, Destination.DocumentEditor -> {
                val noteId = selectedNoteId.value ?: return
                if (!draftNoteIds.remove(noteId)) return
                // Don't decide from persisted state here — the editor may still hold unsaved
                // edits. finalizeDraftDiscard runs after the editor disposes and flushes.
                pendingDraftDiscardIds += noteId
            }
            Destination.Tasks -> {
                if (draftTaskIds.isEmpty()) return
                val ids = draftTaskIds.toSet()
                draftTaskIds.clear()
                persistDraftIds()
                viewModelScope.launch {
                    ids.forEach { id ->
                        // Read the row directly — the state flow may not have emitted a
                        // just-created task yet, and skipping it here would leak the draft.
                        val task = repository.taskById(id) ?: return@forEach
                        if (task.title == "New task" && task.description.isBlank() && task.attachmentUri == null) {
                            repository.deleteTask(task)
                        }
                    }
                }
            }
            else -> Unit
        }
    }

    private fun navigateTo(next: Destination, recordHistory: Boolean = true) {
        val current = destination.value
        if (current == next) {
            sidebarOpen.value = false
            return
        }
        discardUntouchedDrafts(current)
        if (recordHistory) {
            if (navigationHistory.lastOrNull() != current) navigationHistory.addLast(current)
            while (navigationHistory.size > 32) navigationHistory.removeFirst()
        }
        destination.value = next
        sidebarOpen.value = false
    }

    private fun resetNavigation(next: Destination) {
        if (destination.value != next) discardUntouchedDrafts(destination.value)
        navigationHistory.clear()
        destination.value = next
        sidebarOpen.value = false
    }

    fun go(next: Destination) = navigateTo(next)

    fun handleNotificationIntent(intent: Intent) {
        when (intent.getStringExtra("norfold.destination")) {
            "Chat" -> {
                val channelSyncId = intent.getStringExtra("norfold.channelId").orEmpty().takeIf { it.isNotBlank() }
                selectChatChannel(null)
                val messageSyncId = intent.getStringExtra("norfold.messageId")
                viewModelScope.launch {
                    refreshTeamChat().join()
                    val workspaceId = repository.activeWorkspaceIdValue()
                    val localChannelId = channelSyncId?.let { repository.localChatChannelIdBySyncId(workspaceId, it) }
                    selectChatChannel(localChannelId)
                    if (!messageSyncId.isNullOrBlank()) {
                        _highlightedChatMessageId.value = repository.localChatMessageIdBySyncId(workspaceId, messageSyncId)
                    }
                }
            }
            "Tasks" -> go(Destination.Tasks)
            "ObjectDetail" -> {
                val localObjectId = intent.getStringExtra("norfold.objectId")?.toLongOrNull()
                val local = state.value.workspaceObjects.firstOrNull { it.id == localObjectId }
                if (local != null) openWorkspaceObject(local) else go(Destination.Activity)
            }
            "Files" -> go(Destination.Files)
            "Activity" -> go(Destination.Activity)
            else -> if (intent.hasExtra("norfold.destination")) go(Destination.WorkspaceHub)
        }
    }

    fun openTaskCalendar(calendarView: String? = null) {
        viewModelScope.launch {
            val current = state.value.settings
            repository.updateSettings(
                current.copy(
                    taskViewMode = "Calendar",
                    calendarDefaultView = calendarView ?: current.calendarDefaultView,
                ),
            )
        }
        navigateTo(Destination.Tasks)
    }

    fun goToProfile() { _pendingSettingsSection.value = "Profile"; go(Destination.Settings) }
    fun goToWorkspaceMembers() { _pendingSettingsSection.value = "WorkspaceMembers"; go(Destination.Settings) }
    fun consumeSettingsSection() { _pendingSettingsSection.value = null }

    /**
     * Open a conversation (null == General) inside the Chat tab, close the sidebar. The open
     * conversation replaces the bottom navbar with the composer; backing out returns to the inbox.
     */
    fun selectChatChannel(channelId: Long?) {
        chatTyping.value = null
        activeChatChannelId.value = channelId
        chatConversationOpen.value = true
        markChatConversationRead(channelId)
        observeActiveAccountConversation(channelId)
        refreshWorkspaceChatSecurity(channelId)
        closeSidebar()
        if (state.value.destination != Destination.Chat) go(Destination.Chat)
    }

    fun refreshWorkspaceChatSecurity(channelId: Long? = activeChatChannelId.value) {
        viewModelScope.launch {
            val localWorkspaceId = state.value.settings.activeWorkspaceId
            val remoteWorkspaceId = repository.remoteWorkspaceId(localWorkspaceId)
            val channel = channelId?.let { repository.chatChannelById(it) }
            if (remoteWorkspaceId == null || channel?.scope == ChatScope.Account || !NorfoldSupabase.isSignedIn()) {
                _workspaceChatSecurity.value = null
                return@launch
            }
            runCatching {
                ChatBackend.workspaceSecuritySnapshot(remoteWorkspaceId, channel?.syncId)
            }.onSuccess { security ->
                applyWorkspaceChatSecurity(localWorkspaceId, remoteWorkspaceId, channel, security)
            }.onFailure {
                _workspaceChatSecurity.value = null
                _teamCollaborationStatus.value = "Workspace security unavailable · sending paused"
            }
        }
    }

    private suspend fun applyWorkspaceChatSecurity(
        localWorkspaceId: Long,
        remoteWorkspaceId: String,
        channel: ChatChannel?,
        security: ChatBackend.WorkspaceSecuritySnapshot,
    ) {
        _workspaceChatSecurity.value = security
        runCatching { ChatBackend.workspaceSecurityEvents(remoteWorkspaceId) }
            .onSuccess { events ->
                repository.importWorkspaceChatSecurityEvents(localWorkspaceId, events)
            }
        channel?.let {
            repository.updateChatChannelSecurity(
                channelId = it.id,
                mode = security.securityMode,
                securityEpochId = security.securityEpochId,
                acknowledged = security.acknowledged,
            )
        }
        _teamCollaborationStatus.value = when (security.securityMode) {
            ChatSecurityMode.EndToEndEncrypted -> "Team workspace · end-to-end encrypted"
            ChatSecurityMode.Managed -> "Team workspace · managed"
        }
    }

    /** Keeps mode changes and their durable divider visible to every foreground member. */
    suspend fun observeWorkspaceChatSecurityWhileVisible() {
        val localWorkspaceId = repository.activeWorkspaceIdValue()
        val remoteWorkspaceId = repository.remoteWorkspaceId(localWorkspaceId) ?: return
        if (!NorfoldSupabase.isSignedIn()) return
        activeChatChannelId.collectLatest { channelId ->
            val channel = channelId?.let { repository.chatChannelById(it) }
            if (channel?.scope == ChatScope.Account) {
                _workspaceChatSecurity.value = null
                return@collectLatest
            }
            ChatBackend.workspaceSecurityChanges(remoteWorkspaceId, channel?.syncId).collect { security ->
                applyWorkspaceChatSecurity(localWorkspaceId, remoteWorkspaceId, channel, security)
            }
        }
    }

    fun acknowledgeWorkspaceChatSecurity() = viewModelScope.launch {
        val security = _workspaceChatSecurity.value ?: return@launch
        runCatching {
            ChatBackend.acknowledgeWorkspaceSecurity(
                workspaceId = security.workspaceId,
                securityVersion = security.securityVersion,
            )
            ChatBackend.workspaceSecuritySnapshot(
                workspaceId = security.workspaceId,
                channelId = activeChatChannelId.value?.let { repository.chatChannelById(it)?.syncId },
            )
        }.onSuccess {
            _workspaceChatSecurity.value = it
            activeChatChannelId.value?.let { channelId ->
                repository.updateChatChannelSecurity(
                    channelId = channelId,
                    mode = it.securityMode,
                    securityEpochId = it.securityEpochId,
                    acknowledged = true,
                )
            }
            repository.wakeSecureWorkspaceChatOperations(it.workspaceId)
            drainSecureChatOutbox()
            emitSuccess("Workspace security details acknowledged")
        }.onFailure { emitError("Couldn't save the security acknowledgement. Try again.") }
    }

    fun changeWorkspaceChatSecurityMode(mode: ChatSecurityMode) = viewModelScope.launch {
        val security = _workspaceChatSecurity.value ?: return@launch
        runCatching {
            ChatBackend.setWorkspaceSecurityMode(
                workspaceId = security.workspaceId,
                mode = mode,
                expectedSecurityVersion = security.securityVersion,
            )
        }.onSuccess {
            _workspaceChatSecurity.value = it
            emitInfo("Workspace security changed. Every member must review the new security epoch.")
            refreshWorkspaceChatSecurity()
        }.onFailure {
            emitError(authFailureMessage(it, "Couldn't change workspace security. Verify every member has an authorized device."))
        }
    }

    /**
     * Keeps a single realtime subscription alive for the open account conversation.
     * Workspace channels use the shared observeTeamChatWhileVisible stream instead, so
     * this no-ops for them. Reopening the same account conversation keeps the existing
     * subscription; switching conversations tears the old one down.
     */
    private fun observeActiveAccountConversation(channelId: Long?) {
        viewModelScope.launch {
            val channel = channelId?.let { repository.chatChannelById(it) }
            val conversationId = channel?.takeIf { it.scope == ChatScope.Account }?.accountConversationId
            if (channel == null || conversationId == null) {
                accountConversationObserver?.cancel()
                accountConversationObserver = null
                accountConversationObserverId = null
                _accountConversationPresence.value = emptyList()
                return@launch
            }
            if (accountConversationObserverId == conversationId && accountConversationObserver?.isActive == true) return@launch
            accountConversationObserver?.cancelAndJoin()
            accountConversationObserverId = conversationId
            accountConversationObserver = viewModelScope.launch {
                val settings = state.value.settings
                val userId = NorfoldSupabase.currentAccountId()
                val presenceJob = if (userId == null) null else launch {
                    runCatching {
                        NorfoldPresence.accountConversationPresence(
                            conversationId = conversationId,
                            initial = NorfoldPresence.WorkspacePresenceState(
                                userId = userId,
                                username = settings.syncUserName.ifBlank { "you" },
                                displayName = settings.syncPublicName.ifBlank {
                                    settings.syncUserName.ifBlank { "You" }
                                },
                                deviceId = settings.settingsDeviceId,
                            ),
                            availabilityUpdates = chatAvailability,
                            typingUpdates = chatTyping,
                        ).collect { roster -> _accountConversationPresence.value = roster }
                    }
                }
                val receiptJob = launch {
                    while (kotlinx.coroutines.currentCoroutineContext().isActive) {
                        refreshChatReceipts()
                        delay(4_000L)
                    }
                }
                try {
                    var retryDelayMs = 2_000L
                    while (kotlinx.coroutines.currentCoroutineContext().isActive) {
                        try {
                            NorfoldSocial.directMessageChanges(conversationId).collect { remote ->
                                val localChannelId = repository.accountChannelId(conversationId) ?: channel.id
                                importAccountMessage(localChannelId, remote)
                                if (chatConversationOpen.value && activeChatChannelId.value == channel.id) {
                                    markChatConversationRead(channel.id)
                                }
                                _teamCollaborationStatus.value = "Direct · live"
                                retryDelayMs = 2_000L
                            }
                            delay(retryDelayMs)
                            retryDelayMs = (retryDelayMs * 2).coerceAtMost(30_000L)
                        } catch (cancellation: CancellationException) {
                            throw cancellation
                        } catch (_: Throwable) {
                            _teamCollaborationStatus.value = if (networkOnline.value) "Direct · reconnecting…" else "Offline · messages saved locally"
                            delay(retryDelayMs)
                            retryDelayMs = (retryDelayMs * 2).coerceAtMost(30_000L)
                        }
                    }
                } finally {
                    receiptJob.cancel()
                    presenceJob?.cancel()
                    if (accountConversationObserverId == conversationId) {
                        _accountConversationPresence.value = emptyList()
                    }
                }
            }
        }
    }

    fun consumeHighlightedChatMessage() {
        _highlightedChatMessageId.value = null
    }

    /** Land on the chat inbox (conversation list) with no conversation open. */
    fun openChatInbox() {
        // Whatever conversation was open has been seen up to now.
        if (chatConversationOpen.value) markChatConversationRead(activeChatChannelId.value)
        chatConversationOpen.value = false
        chatTyping.value = null
        if (state.value.destination != Destination.Chat) go(Destination.Chat)
    }

    /** Back out of an open conversation to the chat inbox without leaving the Chat tab. */
    fun closeChatConversation() {
        markChatConversationRead(activeChatChannelId.value)
        chatConversationOpen.value = false
        chatTyping.value = null
    }

    /**
     * Chat-context navbar (Tier 2): Chats/People/Requests filter the inbox in place; You opens the
     * profile viewer (Settings for now, until the dedicated viewer lands in Phase 5).
     */
    fun selectChatSection(section: com.norfold.app.domain.ChatSection) {
        if (section == com.norfold.app.domain.ChatSection.You) {
            go(Destination.Settings)
            return
        }
        _chatSection.value = section
        if (chatConversationOpen.value) {
            markChatConversationRead(activeChatChannelId.value)
            chatConversationOpen.value = false
            chatTyping.value = null
        }
        if (state.value.destination != Destination.Chat) go(Destination.Chat)
    }

    /** Moves the unread watermark for a conversation to now and refreshes inbox badges. */
    fun markChatConversationRead(channelId: Long?) {
        ChatReadMarkers.markRead(
            app,
            state.value.settings.activeWorkspaceId,
            ChatReadMarkers.channelKey(channelId),
        )
        _chatReadSerial.value++
        recordViewedReceipts(channelId)
    }

    // -- Presence + 3-stage receipts (chat overhaul Phase 3) ------------------

    /**
     * Joins the workspace presence channel while the Chat tab is visible; called
     * from the same lifecycle effect as [observeTeamChatWhileVisible]. Ends
     * (and drops us from the roster) when the caller's scope is cancelled.
     */
    suspend fun observeWorkspacePresenceWhileVisible() {
        val settings = state.value.settings
        val remoteWorkspaceId = repository.remoteWorkspaceId(settings.activeWorkspaceId) ?: return
        if (!NorfoldSupabase.isSignedIn()) return
        val userId = NorfoldSupabase.currentAccountId() ?: return
        runCatching {
            NorfoldPresence.workspacePresence(
                remoteWorkspaceId = remoteWorkspaceId,
                initial = NorfoldPresence.WorkspacePresenceState(
                    userId = userId,
                    username = settings.syncUserName.ifBlank { "you" },
                    displayName = settings.syncPublicName.ifBlank { settings.syncUserName.ifBlank { "You" } },
                    deviceId = settings.settingsDeviceId,
                ),
                availabilityUpdates = chatAvailability,
                typingChannelUpdates = chatTyping,
            ).collect { roster -> _workspacePresence.value = roster }
        }
        _workspacePresence.value = emptyList()
    }

    /** Foreground/background hook: flips our presence claim between online and away. */
    fun setChatForeground(foreground: Boolean) {
        chatAvailability.value =
            if (foreground) NorfoldPresence.Availability.Online else NorfoldPresence.Availability.Away
    }

    /** Publishes a short-lived typing hint scoped to the currently open shared conversation. */
    fun setChatTyping(typing: Boolean) {
        chatTyping.value = if (typing && chatConversationOpen.value) {
            activeChatChannelId.value
                ?.let { id -> state.value.chatChannels.firstOrNull { it.id == id }?.syncId }
                ?: "general"
        } else null
    }

    /** Presence for one member, by username: "online" | "away" | null (offline/unknown). */
    fun presenceFor(username: String): String? =
        (accountConversationPresence.value + workspacePresence.value)
            .firstOrNull { it.username.equals(username, ignoreCase = true) }
            ?.availability

    /**
     * Marks every synced message in the open conversation as viewed by this account: batched RPC
     * upstream (the senders' ticks), quiet failure — receipts are best-effort
     * and the backend may not be deployed yet.
     */
    private fun recordViewedReceipts(channelId: Long?) = viewModelScope.launch {
        val settings = state.value.settings
        if (!NorfoldSupabase.isSignedIn()) return@launch
        val username = settings.syncUserName.ifBlank { "you" }
        val channel = channelId?.let { id -> state.value.chatChannels.firstOrNull { it.id == id } }
        val messages = state.value.chatMessages
            .filter { it.channelId == channelId && !it.system && !it.authorUsername.equals(username, ignoreCase = true) }
            .sortedByDescending { it.createdAt }
            .take(200)
        val syncIds = messages
            .mapNotNull { message ->
                repository.chatMessageSyncId(message.workspaceId, message.id)
            }
        if (channel?.scope == ChatScope.Account) {
            runCatching { NorfoldSocial.recordDirectMessageReceipts(syncIds, viewed = true) }
            refreshChatReceipts()
            return@launch
        }
        val remoteWorkspaceId = repository.remoteWorkspaceId(settings.activeWorkspaceId) ?: return@launch
        runCatching { NorfoldPresence.recordReceipts(remoteWorkspaceId, syncIds, viewed = true) }
    }

    /**
     * Refreshes tick states for our own recent messages: asks the server how many
     * recipients delivered/viewed each and upgrades the local receipt column.
     * "All recipients" is everyone on the roster except ourselves.
     */
    fun refreshChatReceipts() = viewModelScope.launch {
        val settings = state.value.settings
        if (!NorfoldSupabase.isSignedIn()) return@launch
        val username = settings.syncUserName.ifBlank { "you" }
        val channelsById = state.value.chatChannels.associateBy(ChatChannel::id)
        val ownMessages = state.value.chatMessages
            .filter {
                !it.system && it.authorUsername.equals(username, ignoreCase = true) &&
                    it.receiptStatus != ChatReceiptStatus.Viewed
            }
            .sortedByDescending(ChatMessageItem::createdAt)
            .take(120)

        val workspaceOwn = ownMessages
            .filter { message -> message.channelId?.let(channelsById::get)?.scope != ChatScope.Account }
            .mapNotNull { message ->
                repository.chatMessageSyncId(message.workspaceId, message.id)?.let { it to message }
            }
        val remoteWorkspaceId = repository.remoteWorkspaceId(settings.activeWorkspaceId)
        if (remoteWorkspaceId != null && workspaceOwn.isNotEmpty()) runCatching {
            NorfoldPresence.receiptSummaries(remoteWorkspaceId, workspaceOwn.map { it.first })
        }.onSuccess { summaries ->
            val byId = workspaceOwn.associate { it.first to it.second.id }
            val delivered = mutableListOf<Long>()
            val viewed = mutableListOf<Long>()
            summaries.forEach { summary ->
                val localId = byId[summary.messageSyncId] ?: return@forEach
                val message = state.value.chatMessages.firstOrNull { it.id == localId }
                val channel = message?.channelId?.let { id -> state.value.chatChannels.firstOrNull { it.id == id } }
                val recipients = if (channel?.kind == ChatChannelKind.Dm) 1 else {
                    state.value.workspaceMembers.count { !it.username.equals(username, ignoreCase = true) }.coerceAtLeast(1)
                }
                when {
                    summary.viewedCount >= recipients -> viewed += localId
                    summary.deliveredCount >= recipients -> delivered += localId
                    // Partial progress still deserves a double tick: as soon as the message
                    // reaches at least one other device/reader, advance past the single "sent"
                    // tick rather than leaving it stuck until every recipient reports in.
                    summary.viewedCount >= 1 || summary.deliveredCount >= 1 -> delivered += localId
                }
            }
            repository.upgradeChatMessageReceipts(delivered, ChatReceiptStatus.Delivered)
            repository.upgradeChatMessageReceipts(viewed, ChatReceiptStatus.Viewed)
        }

        val accountOwn = ownMessages
            .mapNotNull { message ->
                val channel = message.channelId?.let(channelsById::get)
                    ?.takeIf { it.scope == ChatScope.Account } ?: return@mapNotNull null
                repository.chatMessageSyncId(message.workspaceId, message.id)
                    ?.let { syncId -> syncId to (message to channel) }
            }
        if (accountOwn.isNotEmpty()) runCatching {
            NorfoldSocial.directMessageReceiptSummaries(accountOwn.map { it.first })
        }.onSuccess { summaries ->
            val bySyncId = accountOwn.toMap()
            val delivered = mutableListOf<Long>()
            val viewed = mutableListOf<Long>()
            summaries.forEach { summary ->
                val (message, channel) = bySyncId[summary.messageSyncId] ?: return@forEach
                val recipients = _personalConversations.value
                    .firstOrNull { it.conversationId == channel.accountConversationId }
                    ?.memberCount
                    ?.minus(1)
                    ?.coerceAtLeast(1)
                    ?: 1
                when {
                    summary.viewedCount >= recipients -> viewed += message.id
                    summary.deliveredCount >= recipients -> delivered += message.id
                    summary.viewedCount > 0 || summary.deliveredCount > 0 -> delivered += message.id
                }
            }
            repository.upgradeChatMessageReceipts(delivered, ChatReceiptStatus.Delivered)
            repository.upgradeChatMessageReceipts(viewed, ChatReceiptStatus.Viewed)
        }
    }

    fun chatLastReadAt(channelId: Long?): Long = ChatReadMarkers.lastReadAt(
        app,
        state.value.settings.activeWorkspaceId,
        ChatReadMarkers.channelKey(channelId),
    )

    fun chatDraft(channelId: Long?): String = ChatDrafts.draft(
        app,
        state.value.settings.activeWorkspaceId,
        ChatDrafts.channelKey(channelId),
    )

    fun setChatDraft(channelId: Long?, text: String) = ChatDrafts.setDraft(
        app,
        state.value.settings.activeWorkspaceId,
        ChatDrafts.channelKey(channelId),
        text,
    )

    fun setChatChannelPinned(channelId: Long, pinned: Boolean) = viewModelScope.launch {
        repository.setChatChannelPinned(channelId, pinned)
    }

    fun isChatConversationMuted(channel: ChatChannel): Boolean =
        ChatConversationPrefs.isMuted(app, chatConversationPreferenceKey(channel))

    fun setChatConversationMuted(channel: ChatChannel, muted: Boolean) {
        ChatConversationPrefs.setMuted(app, chatConversationPreferenceKey(channel), muted)
    }

    fun chatConversationNotificationLevel(channel: ChatChannel): ChatConversationPrefs.NotificationLevel =
        ChatConversationPrefs.notificationLevel(app, chatConversationPreferenceKey(channel))

    fun setChatConversationNotificationLevel(
        channel: ChatChannel,
        level: ChatConversationPrefs.NotificationLevel,
    ) {
        ChatConversationPrefs.setNotificationLevel(app, chatConversationPreferenceKey(channel), level)
    }

    fun setChatChannelDefaultLifespan(channel: ChatChannel, seconds: Long?) = viewModelScope.launch {
        runCatching { repository.setChatChannelDefaultLifespan(channel.id, seconds) }
            .onSuccess {
                emitSuccess(
                    if (seconds == null) "Disappearing messages turned off"
                    else "New messages will disappear ${formatChatLifespan(seconds)}",
                )
            }
            .onFailure { emitError("Couldn't change the message lifespan.") }
    }

    private fun formatChatLifespan(seconds: Long): String = when (seconds) {
        86_400L -> "after 24 hours"
        604_800L -> "after 7 days"
        2_592_000L -> "after 30 days"
        else -> "after $seconds seconds"
    }

    fun isChatGroupOwner(channel: ChatChannel): Boolean =
        channel.accountConversationId?.let { conversationId ->
            _personalConversations.value.firstOrNull { it.conversationId == conversationId }
                ?.myRole
                ?.equals("owner", ignoreCase = true)
        } == true

    fun isChatGroupAdmin(channel: ChatChannel): Boolean =
        channel.accountConversationId?.let { conversationId ->
            _personalConversations.value.firstOrNull { it.conversationId == conversationId }
                ?.myRole
                ?.lowercase() in setOf("owner", "admin")
        } == true

    fun refreshChatGroupMembers(channel: ChatChannel) = viewModelScope.launch {
        val conversationId = channel.accountConversationId
            ?.takeIf { channel.kind == ChatChannelKind.Group } ?: return@launch
        runCatching { NorfoldSocial.groupMembers(conversationId) }
            .onSuccess { members ->
                _groupMembers.value = _groupMembers.value + (conversationId to members)
            }
            .onFailure { Log.w("NorfoldChatGovernance", "Group roster refresh failed", it) }
    }

    fun setChatGroupMemberRole(channel: ChatChannel, memberId: String, role: String) =
        mutateChatGroupMembership(channel, "Couldn't change that member's role.") { conversationId ->
            NorfoldSocial.setGroupMemberRole(conversationId, memberId, role)
        }

    fun addChatGroupMember(channel: ChatChannel, memberId: String) =
        mutateChatGroupMembership(channel, "Couldn't add that member.") { conversationId ->
            NorfoldSocial.addGroupMember(conversationId, memberId)
        }

    fun transferChatGroupOwnership(channel: ChatChannel, memberId: String) =
        mutateChatGroupMembership(channel, "Couldn't transfer group ownership.") { conversationId ->
            NorfoldSocial.transferGroupOwnership(conversationId, memberId)
        }

    fun removeChatGroupMember(channel: ChatChannel, memberId: String) =
        mutateChatGroupMembership(channel, "Couldn't remove that member.") { conversationId ->
            NorfoldSocial.removeGroupMember(conversationId, memberId)
        }

    private fun mutateChatGroupMembership(
        channel: ChatChannel,
        failureMessage: String,
        mutation: suspend (String) -> Unit,
    ) = viewModelScope.launch {
        val conversationId = channel.accountConversationId
            ?.takeIf { channel.kind == ChatChannelKind.Group }
            ?: return@launch
        runCatching { mutation(conversationId) }
            .onSuccess {
                refreshSocialGraph()
                refreshChatGroupMembers(channel)
                refreshChatGovernanceHistory(channel)
                emitSuccess("Group membership updated")
            }
            .onFailure { emitError(authFailureMessage(it, failureMessage)) }
    }

    fun refreshWorkspaceChannelMembers(channel: ChatChannel) = viewModelScope.launch {
        if (channel.scope != ChatScope.Workspace) return@launch
        val workspaceId = _teamWorkspaceRemoteId.value ?: return@launch
        runCatching { ChatBackend.workspaceChannelMembers(workspaceId, channel.syncId) }
            .onSuccess { members ->
                _workspaceChannelMembers.value = _workspaceChannelMembers.value + (channel.syncId to members)
            }
            .onFailure { Log.w("NorfoldChatGovernance", "Workspace channel roster refresh failed", it) }
    }

    fun setWorkspaceChannelMemberRole(channel: ChatChannel, memberId: String, role: String) =
        mutateWorkspaceChannelMembership(channel, "Couldn't change that channel role.") { workspaceId ->
            ChatBackend.setWorkspaceChannelMemberRole(workspaceId, channel.syncId, memberId, role)
        }

    fun addWorkspaceChannelMember(channel: ChatChannel, memberId: String) =
        mutateWorkspaceChannelMembership(channel, "Couldn't add that channel member.") { workspaceId ->
            ChatBackend.addWorkspaceChannelMember(workspaceId, channel.syncId, memberId)
        }

    fun transferWorkspaceChannelOwnership(channel: ChatChannel, memberId: String) =
        mutateWorkspaceChannelMembership(channel, "Couldn't transfer channel ownership.") { workspaceId ->
            ChatBackend.transferWorkspaceChannelOwnership(workspaceId, channel.syncId, memberId)
        }

    fun removeWorkspaceChannelMember(channel: ChatChannel, memberId: String) =
        mutateWorkspaceChannelMembership(channel, "Couldn't remove that channel member.") { workspaceId ->
            ChatBackend.removeWorkspaceChannelMember(workspaceId, channel.syncId, memberId)
        }

    private fun mutateWorkspaceChannelMembership(
        channel: ChatChannel,
        failureMessage: String,
        mutation: suspend (String) -> Unit,
    ) = viewModelScope.launch {
        val workspaceId = _teamWorkspaceRemoteId.value ?: return@launch
        runCatching { mutation(workspaceId) }
            .onSuccess {
                refreshWorkspaceChannelMembers(channel)
                refreshWorkspaceChatSecurity(channel.id)
                refreshChatGovernanceHistory(channel)
                emitSuccess("Channel membership updated")
            }
            .onFailure { emitError(authFailureMessage(it, failureMessage)) }
    }

    fun refreshChatGovernanceHistory(channel: ChatChannel) = viewModelScope.launch {
        val key = channel.accountConversationId ?: channel.syncId
        val workspaceId = if (channel.scope == ChatScope.Workspace) _teamWorkspaceRemoteId.value else null
        runCatching {
            ChatBackend.governanceHistory(
                accountConversationId = channel.accountConversationId,
                workspaceId = workspaceId,
                channelId = channel.syncId.takeIf { channel.scope == ChatScope.Workspace },
            )
        }.onSuccess { history ->
            _chatGovernanceHistory.value = _chatGovernanceHistory.value + (key to history)
        }.onFailure { Log.w("NorfoldChatGovernance", "Governance history refresh failed", it) }
    }

    fun shouldReviewChatGroupRules(channel: ChatChannel): Boolean {
        if (channel.kind != ChatChannelKind.Group || channel.rules.isBlank()) return false
        val lastSeen = ChatConversationPrefs.rulesLastSeenAt(app, chatConversationPreferenceKey(channel))
        if (lastSeen == 0L) return true
        val reminderMs = channel.rulesReminderDays.coerceIn(1, 365) * 24L * 60L * 60L * 1_000L
        return System.currentTimeMillis() - lastSeen >= reminderMs
    }

    fun markChatGroupRulesSeen(channel: ChatChannel) {
        ChatConversationPrefs.markRulesSeen(app, chatConversationPreferenceKey(channel))
    }

    fun updateChatGroupMetadata(
        channel: ChatChannel,
        name: String,
        icon: String,
        coverPath: String?,
        description: String,
        rules: String,
        rulesReminderDays: Int,
    ) = viewModelScope.launch {
        val conversationId = channel.accountConversationId
        if (channel.kind != ChatChannelKind.Group || conversationId == null) {
            emitError("Only personal groups have shared group details.")
            return@launch
        }
        val trimmedName = name.trim()
        if (trimmedName.isBlank()) {
            emitError("Give the group a name.")
            return@launch
        }
        runCatching {
            NorfoldSocial.setGroupMetadata(
                conversationId = conversationId,
                name = trimmedName,
                icon = icon,
                coverPath = coverPath,
                description = description,
                rules = rules,
                rulesReminderDays = rulesReminderDays,
            )
            repository.updateChatChannelRichMetadata(
                channelId = channel.id,
                name = trimmedName,
                icon = icon,
                coverUri = coverPath,
                description = description,
                rules = rules,
                rulesReminderDays = rulesReminderDays,
            )
        }.onSuccess {
            emitInfo("Group details updated.")
            refreshSocialGraph()
            refreshChatGovernanceHistory(channel)
        }.onFailure { emitError(authFailureMessage(it, "Couldn't update the group. Try again.")) }
    }

    fun leaveChatGroup(channel: ChatChannel) = viewModelScope.launch {
        val conversationId = channel.accountConversationId
        if (channel.kind != ChatChannelKind.Group || conversationId == null) return@launch
        runCatching { NorfoldSocial.leaveGroup(conversationId) }
            .onSuccess {
                if (activeChatChannelId.value == channel.id) closeChatConversation()
                repository.deleteChatChannel(channel.id)
                _personalConversations.value = _personalConversations.value.filterNot {
                    it.conversationId == conversationId
                }
                emitInfo("You left ${channel.name}.")
                refreshSocialGraph()
            }
            .onFailure { emitError(authFailureMessage(it, "Couldn't leave the group. Try again.")) }
    }

    private fun chatConversationPreferenceKey(channel: ChatChannel): String =
        ChatConversationPrefs.conversationKey(channel.accountConversationId, channel.id)

    fun createChatTopic(name: String) = viewModelScope.launch {
        val trimmed = name.trim()
        if (trimmed.isBlank()) return@launch
        val id = repository.createChatChannel(ChatChannelKind.Topic, trimmed, SemanticActionId.AppTopics.stableId)
        repository.chatChannelById(id)?.let { publishChatChannel(it) }
        selectChatChannel(id)
    }

    /**
     * Creates an account-level personal group (NorfoldSocial), not a workspace group.
     * Members must already be contacts (enforced server-side by create_personal_group);
     * the picker only offers contacts. The group follows the user across workspaces and
     * is hard-private — readable only by its members (RLS is_direct_member).
     */
    fun createChatGroup(name: String, memberIds: List<String>) = viewModelScope.launch {
        val trimmed = name.trim()
        if (trimmed.isBlank()) return@launch
        val members = memberIds.filter { it.isNotBlank() }.distinct()
        if (members.isEmpty()) {
            emitError("Pick at least one member for the group.")
            return@launch
        }
        if (!NorfoldSupabase.isSignedIn()) {
            emitError("Sign in to create a group.")
            return@launch
        }
        runCatching {
            val conversationId = NorfoldSocial.createPersonalGroup(trimmed, members)
            val id = repository.openOrCreateAccountChannel(
                accountConversationId = conversationId,
                kind = ChatChannelKind.Group,
                name = trimmed,
                icon = SemanticActionId.AppGroups.stableId,
            )
            refreshSocialGraph()
            id
        }.onSuccess(::selectChatChannel)
            .onFailure { emitError(authFailureMessage(it, "Couldn't create that group. Try again.")) }
    }

    fun openDirectMessage(username: String, displayName: String) = viewModelScope.launch {
        if (username.isBlank()) return@launch
        val target = state.value.workspaceMembers.firstOrNull { it.username.equals(username, ignoreCase = true) }
        if (target == null || target.userId.isBlank() || !NorfoldSupabase.isSignedIn()) {
            emitError("Refresh People before starting a direct message.")
            return@launch
        }
        runCatching {
            // Account transport: DMs follow the person across every workspace, not the
            // workspace. open_direct_conversation is idempotent (dm_key), so reopening
            // returns the same conversation instead of spawning a duplicate.
            val conversationId = NorfoldSocial.openDirectConversation(target.userId)
            val id = repository.openOrCreateAccountChannel(
                accountConversationId = conversationId,
                kind = ChatChannelKind.Dm,
                name = displayName.ifBlank { target.displayName.ifBlank { username } },
                counterpartUsername = target.username,
            )
            MutualChatContacts.markMutual(app, target.username)
            refreshSocialGraph()
            id
        }.onSuccess(::selectChatChannel)
            .onFailure { emitError(authFailureMessage(it, "Couldn't start that direct message. Try again.")) }
    }

    private suspend fun publishChatChannel(
        channel: ChatChannel,
        participantUserIds: List<String> = emptyList(),
        participantUsernames: List<String> = emptyList(),
        participantDisplayNames: List<String> = emptyList(),
    ) {
        val settings = state.value.settings
        val remoteWorkspaceId = repository.remoteWorkspaceId(channel.workspaceId) ?: return
        if (!NorfoldSupabase.isSignedIn()) return
        ChatBackend.registerWorkspaceChannel(
            workspaceId = remoteWorkspaceId,
            channelId = channel.syncId,
            visibility = channel.visibility,
        )
        val security = ChatBackend.workspaceSecuritySnapshot(remoteWorkspaceId, channel.syncId)
        repository.updateChatChannelSecurity(
            channelId = channel.id,
            mode = security.securityMode,
            securityEpochId = security.securityEpochId,
            acknowledged = security.acknowledged,
        )
        val remote = ChatBackend.upsertWorkspaceChannel(
            remoteWorkspaceId = remoteWorkspaceId,
            deviceId = settings.settingsDeviceId,
            syncId = channel.syncId,
            kind = channel.kind.name,
            name = channel.name,
            icon = channel.icon,
            createdAt = channel.createdAt,
            visibility = channel.visibility,
            security = security,
            participantUserIds = participantUserIds,
            participantUsernames = participantUsernames,
            participantDisplayNames = participantDisplayNames,
            baseVersion = repository.chatChannelRemoteBinding(channel.workspaceId, channel.id)?.remoteVersion,
        )
        repository.bindRemoteChatChannel(channel.workspaceId, channel.id, remote)
    }

    fun deleteChatChannel(channelId: Long) = viewModelScope.launch {
        if (activeChatChannelId.value == channelId) activeChatChannelId.value = null
        val channel = state.value.chatChannels.firstOrNull { it.id == channelId }
        val remoteWorkspaceId = repository.remoteWorkspaceId(state.value.settings.activeWorkspaceId)
        if (channel != null && remoteWorkspaceId != null && NorfoldSupabase.isSignedIn()) {
            runCatching { NorfoldSupabase.deleteTeamChatChannel(remoteWorkspaceId, channel.syncId) }
                .onFailure {
                    emitError("Couldn't delete this conversation for everyone. Nothing was removed.")
                    return@launch
                }
        }
        repository.deleteChatChannel(channelId)
    }

    // -- Account-level social graph (chat overhaul Phase 2) -------------------
    // Contacts, friend invites, and personal DM/group conversations that live
    // outside the workspace. Held in plain StateFlows (not the DocsUiState
    // combine) because they're cloud-account-scoped, not workspace-scoped.

    private val _socialContacts = MutableStateFlow<List<NorfoldSocial.RemoteContact>>(emptyList())
    val socialContacts: StateFlow<List<NorfoldSocial.RemoteContact>> = _socialContacts.asStateFlow()

    private val _contactRequests = MutableStateFlow<List<NorfoldSocial.RemoteContactRequest>>(emptyList())
    val contactRequests: StateFlow<List<NorfoldSocial.RemoteContactRequest>> = _contactRequests.asStateFlow()

    private val _blockedAccountIds = MutableStateFlow<Set<String>>(emptySet())
    val blockedAccountIds: StateFlow<Set<String>> = _blockedAccountIds.asStateFlow()

    private val _personalConversations = MutableStateFlow<List<NorfoldSocial.RemoteDirectConversation>>(emptyList())
    val personalConversations: StateFlow<List<NorfoldSocial.RemoteDirectConversation>> = _personalConversations.asStateFlow()

    private val _groupMembers = MutableStateFlow<Map<String, List<NorfoldSocial.RemoteGroupMember>>>(emptyMap())
    val groupMembers: StateFlow<Map<String, List<NorfoldSocial.RemoteGroupMember>>> = _groupMembers.asStateFlow()

    private val _workspaceChannelMembers = MutableStateFlow<Map<String, List<ChatBackend.RemoteWorkspaceChannelMember>>>(emptyMap())
    val workspaceChannelMembers: StateFlow<Map<String, List<ChatBackend.RemoteWorkspaceChannelMember>>> =
        _workspaceChannelMembers.asStateFlow()

    private val _chatGovernanceHistory = MutableStateFlow<Map<String, List<ChatBackend.GovernanceEvent>>>(emptyMap())
    val chatGovernanceHistory: StateFlow<Map<String, List<ChatBackend.GovernanceEvent>>> =
        _chatGovernanceHistory.asStateFlow()

    /** The last invite token created this session, until dismissed or consumed into a share. */
    private val _pendingFriendInvite = MutableStateFlow<String?>(null)
    val pendingFriendInvite: StateFlow<String?> = _pendingFriendInvite.asStateFlow()

    /**
     * Pulls contacts + personal conversations. Quiet on failure: this runs
     * opportunistically (inbox open, contact sheet open) and the social-graph
     * backend may simply not be deployed yet.
     */
    fun refreshSocialGraph() = viewModelScope.launch {
        runCatching { NorfoldSocial.myContacts() }.onSuccess { _socialContacts.value = it }
        runCatching { NorfoldSocial.myContactRequests() }.onSuccess { _contactRequests.value = it }
        runCatching { NorfoldSocial.myBlockedAccounts() }
            .onSuccess { rows -> _blockedAccountIds.value = rows.mapTo(linkedSetOf()) { it.blockedId } }
        runCatching { NorfoldSocial.myDirectConversations() }
            .onSuccess { conversations ->
                _personalConversations.value = conversations
                // Materialize each account conversation as a local channel row so it
                // renders in the existing inbox (chatChannels flow) and routes sends onto
                // the account transport via its accountConversationId. Then pull recent
                // messages — channel-first, so importAccountDirectMessage always finds its
                // parent. Quiet on failure: the social backend may not be deployed yet.
                conversations.forEach { convo -> runCatching { materializeAccountConversation(convo) } }
            }
    }

    /**
     * Ensures a local channel mirrors [convo] and drains its recent messages into
     * the local store. Returns the local channel id.
     */
    private suspend fun materializeAccountConversation(
        convo: NorfoldSocial.RemoteDirectConversation,
    ): Long {
        val isGroup = convo.kind.equals("group", ignoreCase = true)
        val displayName = if (isGroup) {
            convo.name.ifBlank { "Group" }
        } else {
            convo.peerDisplayName?.ifBlank { null } ?: convo.peerHandle ?: "Direct message"
        }
        val counterpart = convo.peerHandle.orEmpty()
        val localChannelId = repository.openOrCreateAccountChannel(
            accountConversationId = convo.conversationId,
            kind = if (isGroup) ChatChannelKind.Group else ChatChannelKind.Dm,
            name = displayName,
            counterpartUsername = counterpart,
            icon = if (isGroup) convo.icon.ifBlank { SemanticActionId.AppGroups.stableId } else "app.people",
            coverUri = convo.coverPath,
            description = convo.description,
            rules = convo.rules,
            rulesReminderDays = convo.rulesReminderDays,
        )
        runCatching { NorfoldSocial.directMessages(convo.conversationId) }
            .onSuccess { messages -> messages.forEach { importAccountMessage(localChannelId, it) } }
        if (isGroup) {
            runCatching { NorfoldSocial.groupMembers(convo.conversationId) }
                .onSuccess { members ->
                    _groupMembers.value = _groupMembers.value + (convo.conversationId to members)
                }
        }
        return localChannelId
    }

    /** Imports one account direct message, resolving the sender to a display identity. */
    private suspend fun importAccountMessage(
        localChannelId: Long,
        remote: NorfoldSocial.RemoteDirectMessage,
    ) {
        val myId = NorfoldSupabase.currentAccountId()
        val isOwn = myId != null && remote.senderId == myId
        val identity = resolveAccountSender(remote.senderId, isOwn)
        val body = remote.content["body"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val createdAt = remote.content["createdAt"]?.jsonPrimitive?.longOrNull
            ?: parseIsoToEpochMillis(remote.createdAt)
            ?: System.currentTimeMillis()
        val inserted = repository.importAccountDirectMessage(
            localChannelId = localChannelId,
            clientSyncId = remote.clientSyncId,
            remoteMessageId = remote.id,
            senderUsername = identity.first,
            senderDisplayName = identity.second,
            body = body,
            color = state.value.settings.accentColor,
            createdAt = createdAt,
            isOwn = isOwn,
        )
        if (inserted && !isOwn) {
            runCatching {
                NorfoldSocial.recordDirectMessageReceipts(
                    messageSyncIds = listOf(remote.clientSyncId),
                    viewed = chatConversationOpen.value && activeChatChannelId.value == localChannelId,
                )
            }
        }
    }

    private fun startSecureRelayObserver() {
        if (secureRelayObserver?.isActive == true) return
        secureRelayObserver = viewModelScope.launch {
            var retryDelayMs = 2_000L
            var nextLeaseAt = 0L
            while (kotlinx.coroutines.currentCoroutineContext().isActive && NorfoldSupabase.isSignedIn()) {
                try {
                    val now = System.currentTimeMillis()
                    repository.purgeExpiredChatMessages(now)
                    if (now >= nextLeaseAt) {
                        secureChatBackend.renewLease()
                        syncSecureTombstones()
                        syncSecureHiddenState()
                        requestMissingSecureHistory()
                        serveSecureRecoveryRequests()
                        nextLeaseAt = now + 60_000L
                    }
                    drainSecureChatOutbox()
                    val pending = secureChatBackend.pending()
                    pending.forEach { remote -> importSecureMessage(remote) }
                    retryDelayMs = 2_000L
                    delay(if (pending.isEmpty()) 4_000L else 250L)
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (failure: Throwable) {
                    Log.w("NorfoldSecureChat", "Encrypted relay drain will retry", failure)
                    delay(retryDelayMs)
                    retryDelayMs = (retryDelayMs * 2).coerceAtMost(30_000L)
                }
            }
        }
    }

    private suspend fun drainSecureChatOutbox() {
        val pending = repository.pendingSecureChatOperations()
        for (operation in pending) {
            try {
                if (operation.operation == "text" && operation.expiresAt?.let { it <= System.currentTimeMillis() } == true) {
                    repository.recordSecureChatTombstone(
                        messageSyncId = operation.id,
                        conversationId = operation.conversationId,
                        reason = "expired",
                        destructiveAt = requireNotNull(operation.expiresAt),
                    )
                    repository.removeChatMessageBySyncId(operation.workspaceId, operation.id)
                    repository.completeSecureChatOperation(operation.id)
                    continue
                }
                if (operation.remoteWorkspaceId != null) {
                    drainWorkspaceChatOperation(operation)
                } else if (operation.operation == "text") {
                    val sent = secureChatBackend.sendText(
                        conversationId = operation.conversationId,
                        clientSyncId = operation.id,
                        body = operation.body,
                        createdAt = operation.createdAt,
                        replyPreview = operation.replyPreview,
                        expiresAt = operation.expiresAt,
                    )
                    repository.localChatMessageIdBySyncId(operation.workspaceId, operation.id)?.let { localId ->
                        repository.bindAccountDirectMessage(localId, operation.id)
                        repository.setChatMessageReceiptStatus(localId, ChatReceiptStatus.Sent)
                        repository.setChatMessageSecurity(
                            localId,
                            ChatSecurityMode.EndToEndEncrypted,
                            sent.securityEpochId,
                        )
                        repository.setChatMessageOriginPayload(localId, sent.originPayloadJson)
                    }
                } else {
                    val target = operation.targetMessageSyncId
                        ?: error("Secure mutation is missing its target message.")
                    secureChatBackend.sendMutation(
                        conversationId = operation.conversationId,
                        clientSyncId = operation.id,
                        targetMessageId = target,
                        type = operation.operation,
                        body = operation.body,
                        editedAt = operation.editedAt,
                        reactionsJson = operation.reactionsJson,
                        pinned = operation.pinned,
                    )
                    if (operation.operation == "delete_everyone") {
                        repository.recordSecureChatTombstone(
                            target,
                            operation.conversationId,
                            "delete_everyone",
                        )
                    }
                }
                repository.completeSecureChatOperation(operation.id)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Throwable) {
                repository.failSecureChatOperation(operation, failure)
                return
            }
        }
    }

    private suspend fun syncSecureHiddenState() {
        val accountId = NorfoldSupabase.currentAccountId() ?: return
        val hidden = secureChatBackend.hiddenForCurrentUser().map { remote ->
            SecureChatUserHiddenEntity(
                accountOwnerId = accountId,
                messageSyncId = remote.objectId,
                conversationId = remote.conversationId,
                securityEpochId = remote.securityEpochId,
                hiddenAt = parseIsoToEpochMillis(remote.hiddenAt) ?: System.currentTimeMillis(),
                reason = remote.reason,
            )
        }
        repository.mergeSecureChatUserHidden(accountId, hidden)
    }

    private suspend fun syncSecureTombstones() {
        val rows = secureChatBackend.tombstones().map { remote ->
            SecureChatTombstoneEntity(
                messageSyncId = remote.objectId,
                conversationId = remote.conversationId,
                destructiveAt = parseIsoToEpochMillis(remote.destructiveAt) ?: System.currentTimeMillis(),
                reason = remote.reason,
            )
        }
        repository.mergeSecureChatTombstones(rows)
    }

    private suspend fun serveSecureRecoveryRequests() {
        secureChatBackend.pendingRecoveryRequests().forEach { request ->
            val message = repository.chatMessageBySyncIdAnyWorkspace(request.objectId) ?: return@forEach
            val originPayload = message.originPayloadJson ?: return@forEach
            val channel = message.channelId?.let { repository.chatChannelById(it) }
            val conversationId = when (channel?.scope) {
                ChatScope.Account -> channel.accountConversationId
                ChatScope.Workspace -> channel.syncId
                null -> repository.remoteWorkspaceId(message.workspaceId)
            } ?: return@forEach
            runCatching {
                secureChatBackend.fulfillRecovery(request, conversationId, originPayload)
            }.onFailure { failure ->
                Log.w("NorfoldSecureChat", "Encrypted recovery fulfilment will retry", failure)
            }
        }
    }

    private suspend fun requestMissingSecureHistory() {
        val accountId = NorfoldSupabase.currentAccountId() ?: return
        secureChatBackend.recoverableObjects().forEach { remote ->
            if (repository.chatMessageBySyncIdAnyWorkspace(remote.objectId) != null) return@forEach
            if (repository.hasSecureChatTombstone(remote.objectId)) return@forEach
            if (repository.hasSecureChatUserHidden(accountId, remote.objectId)) return@forEach
            runCatching { secureChatBackend.requestRecovery(remote.objectId, remote.securityEpochId) }
                .onFailure { failure ->
                    Log.w("NorfoldSecureChat", "Encrypted recovery request will retry", failure)
                }
        }
    }

    private suspend fun drainWorkspaceChatOperation(operation: SecureChatOutboxEntity) {
        val remoteWorkspaceId = requireNotNull(operation.remoteWorkspaceId)
        val security = ChatBackend.ensureWorkspaceSecurityEpoch(
            workspaceId = remoteWorkspaceId,
            channelId = operation.channelSyncId,
        )
        _workspaceChatSecurity.value = security
        require(security.acknowledged) {
            "Workspace security disclosure acknowledgement is required."
        }
        val localMessageId = repository.localChatMessageIdBySyncId(operation.workspaceId, operation.id)
        val localMessage = localMessageId?.let { repository.chatMessageById(it) }

        if (operation.operation == "text") {
            val message = requireNotNull(localMessage) { "Local workspace message is missing." }
            when (security.securityMode) {
                ChatSecurityMode.EndToEndEncrypted -> {
                    val sent = secureChatBackend.sendWorkspaceText(
                        workspaceId = remoteWorkspaceId,
                        channelId = operation.channelSyncId,
                        clientSyncId = operation.id,
                        body = operation.body,
                        createdAt = operation.createdAt,
                        replyPreview = operation.replyPreview,
                        expiresAt = operation.expiresAt,
                    )
                    val epochId = requireNotNull(security.securityEpochId)
                    repository.setChatMessageSecurity(message.id, security.securityMode, epochId)
                    repository.bindSecureChatMessage(
                        localWorkspaceId = operation.workspaceId,
                        localMessageId = message.id,
                        clientSyncId = operation.id,
                        relayId = sent.relayId,
                    )
                    repository.setChatMessageOriginPayload(message.id, sent.originPayloadJson)
                }
                ChatSecurityMode.Managed -> {
                    val remote = ChatBackend.sendManagedWorkspaceMessage(
                        remoteWorkspaceId = remoteWorkspaceId,
                        deviceId = state.value.settings.settingsDeviceId,
                        username = message.authorUsername,
                        displayName = message.authorDisplayName,
                        body = operation.body,
                        color = message.color,
                        createdAt = operation.createdAt,
                        syncId = operation.id,
                        idempotencyKey = operation.id,
                        security = security,
                        channelSyncId = operation.channelSyncId,
                        replyPreview = operation.replyPreview,
                        expiresAt = operation.expiresAt,
                    )
                    repository.bindRemoteChatMessage(operation.workspaceId, message.id, remote)
                    repository.setChatMessageSecurity(
                        message.id,
                        security.securityMode,
                        requireNotNull(security.securityEpochId),
                    )
                }
            }
            repository.setChatMessageReceiptStatus(message.id, ChatReceiptStatus.Sent)
            return
        }

        val targetSyncId = requireNotNull(operation.targetMessageSyncId) {
            "Workspace mutation is missing its target message."
        }
        when (security.securityMode) {
            ChatSecurityMode.EndToEndEncrypted -> secureChatBackend.sendWorkspaceMutation(
                workspaceId = remoteWorkspaceId,
                channelId = operation.channelSyncId,
                clientSyncId = operation.id,
                targetMessageId = targetSyncId,
                type = operation.operation,
                body = operation.body,
                editedAt = operation.editedAt,
                reactionsJson = operation.reactionsJson,
                pinned = operation.pinned,
            )
            ChatSecurityMode.Managed -> {
                if (operation.operation == "delete_everyone") {
                    val binding = repository.chatMessageRemoteBindingBySyncId(
                        operation.workspaceId,
                        targetSyncId,
                    )
                    ChatBackend.deleteManagedWorkspaceMessage(
                        remoteWorkspaceId = remoteWorkspaceId,
                        deviceId = state.value.settings.settingsDeviceId,
                        targetSyncId = targetSyncId,
                        idempotencyKey = operation.id,
                        baseVersion = binding?.remoteVersion,
                    )
                    repository.recordSecureChatTombstone(
                        targetSyncId,
                        operation.conversationId,
                        "delete_everyone",
                    )
                    return
                }
                val targetId = repository.localChatMessageIdBySyncId(operation.workspaceId, targetSyncId)
                val target = targetId?.let { repository.chatMessageById(it) }
                    ?: error("Managed workspace mutation target is missing.")
                val remote = ChatBackend.sendManagedWorkspaceMessage(
                    remoteWorkspaceId = remoteWorkspaceId,
                    deviceId = state.value.settings.settingsDeviceId,
                    username = target.authorUsername,
                    displayName = target.authorDisplayName,
                    body = operation.body.ifBlank { target.body },
                    color = target.color,
                    createdAt = target.createdAt,
                    syncId = targetSyncId,
                    idempotencyKey = operation.id,
                    security = security,
                    channelSyncId = operation.channelSyncId,
                    replyPreview = target.replyPreview,
                    editedAt = operation.editedAt,
                    reactionsJson = operation.reactionsJson,
                    pinned = operation.pinned,
                    expiresAt = target.expiresAt,
                    baseVersion = repository.chatMessageRemoteBinding(operation.workspaceId, target.id)?.remoteVersion,
                )
                repository.bindRemoteChatMessage(operation.workspaceId, target.id, remote)
            }
        }
        if (operation.operation == "delete_everyone") {
            repository.recordSecureChatTombstone(
                targetSyncId,
                operation.conversationId,
                "delete_everyone",
            )
        }
    }

    private suspend fun importSecureMessage(
        remote: SecureChatBackend.PendingRelay,
    ) {
        val decrypted = secureChatBackend.decrypt(remote)
        var accountChannelId = repository.accountChannelId(decrypted.conversationId)
        var localWorkspaceId = repository.localWorkspaceIdForRemote(decrypted.conversationId)
        var workspaceChannel = repository.workspaceChannelBySyncId(decrypted.conversationId)
        if (accountChannelId == null && localWorkspaceId == null && workspaceChannel == null) {
            val conversations = NorfoldSocial.myDirectConversations()
            _personalConversations.value = conversations
            conversations.firstOrNull { it.conversationId == decrypted.conversationId }
                ?.let { accountChannelId = materializeAccountConversation(it) }
        }
        val isAccount = accountChannelId != null
        val resolvedChannelId: Long? = accountChannelId ?: workspaceChannel?.id
        if (!isAccount) {
            localWorkspaceId = localWorkspaceId ?: workspaceChannel?.workspaceId
        }
        val resolvedWorkspaceId = if (isAccount) {
            repository.activeWorkspaceIdValue()
        } else {
            localWorkspaceId ?: return
        }
        val accountId = NorfoldSupabase.currentAccountId()
        if (decrypted.payload.type != "text") {
            val target = decrypted.payload.targetMessageId ?: return
            if (accountId != null && repository.hasSecureChatUserHidden(accountId, target)) {
                secureChatBackend.acknowledge(decrypted.relayId, read = false)
                recordSecureRelayCheckpoint(remote)
                return
            }
            val applied = repository.applySecureChatMutation(
                localWorkspaceId = resolvedWorkspaceId,
                targetSyncId = target,
                type = decrypted.payload.type,
                body = decrypted.payload.body,
                editedAt = decrypted.payload.editedAt,
                reactionsJson = decrypted.payload.reactionsJson,
                pinned = decrypted.payload.pinned,
            )
            if (!applied && decrypted.payload.type != "delete_everyone") return
            if (decrypted.payload.type == "delete_everyone") {
                repository.recordSecureChatTombstone(
                    target,
                    decrypted.conversationId,
                    "delete_everyone",
                    destructiveAt = decrypted.payload.createdAt,
                )
            }
            secureChatBackend.acknowledge(
                decrypted.relayId,
                read = chatConversationOpen.value && activeChatChannelId.value == resolvedChannelId,
            )
            recordSecureRelayCheckpoint(remote)
            return
        }
        if (repository.hasSecureChatTombstone(decrypted.clientSyncId)) {
            secureChatBackend.acknowledge(decrypted.relayId, read = false)
            recordSecureRelayCheckpoint(remote)
            return
        }
        if (accountId != null && repository.hasSecureChatUserHidden(accountId, decrypted.clientSyncId)) {
            secureChatBackend.acknowledge(decrypted.relayId, read = false)
            recordSecureRelayCheckpoint(remote)
            return
        }
        val myId = accountId
        val isOwn = myId != null && decrypted.senderId == myId
        val identity = resolveAccountSender(decrypted.senderId, isOwn)
        if (isAccount) {
            repository.importAccountDirectMessage(
                localChannelId = requireNotNull(resolvedChannelId),
                clientSyncId = decrypted.clientSyncId,
                remoteMessageId = decrypted.relayId,
                senderUsername = identity.first,
                senderDisplayName = identity.second,
                body = decrypted.payload.body,
                color = state.value.settings.accentColor,
                createdAt = decrypted.payload.createdAt,
                isOwn = isOwn,
                securityEpochId = decrypted.securityEpochId,
                originPayloadJson = decrypted.originPayloadJson,
                expiresAt = decrypted.payload.expiresAt,
            )
        } else {
            repository.importSecureWorkspaceMessage(
                localWorkspaceId = resolvedWorkspaceId,
                localChannelId = resolvedChannelId,
                clientSyncId = decrypted.clientSyncId,
                relayId = decrypted.relayId,
                senderUsername = identity.first,
                senderDisplayName = identity.second,
                body = decrypted.payload.body,
                color = state.value.settings.accentColor,
                createdAt = decrypted.payload.createdAt,
                isOwn = isOwn,
                securityEpochId = decrypted.securityEpochId,
                originPayloadJson = decrypted.originPayloadJson,
                expiresAt = decrypted.payload.expiresAt,
            )
        }
        val viewed = chatConversationOpen.value && activeChatChannelId.value == resolvedChannelId
        secureChatBackend.acknowledge(decrypted.relayId, read = viewed)
        recordSecureRelayCheckpoint(remote)
        _teamCollaborationStatus.value = if (isAccount) "Direct · encrypted" else "Team workspace · end-to-end encrypted"
    }

    private suspend fun recordSecureRelayCheckpoint(remote: SecureChatBackend.PendingRelay) {
        val device = secureChatBackend.currentDevicePublic()
        repository.recordSecureRelayCheckpoint(
            deviceId = device.deviceId,
            generation = device.generation,
            createdAt = remote.createdAt,
            relayId = remote.relayId,
        )
    }

    /** Best-effort (username, displayName) for an account sender UUID. */
    private fun resolveAccountSender(senderId: String, isOwn: Boolean): Pair<String, String> {
        if (isOwn) {
            val settings = state.value.settings
            return settings.syncUserName.ifBlank { "you" } to settings.syncPublicName.ifBlank { "You" }
        }
        _socialContacts.value.firstOrNull { it.contactId == senderId }?.let { contact ->
            val handle = contact.handle ?: "member"
            return handle to contact.displayName.ifBlank { handle }
        }
        state.value.workspaceMembers.firstOrNull { it.userId == senderId }?.let {
            return it.username to it.displayName.ifBlank { it.username }
        }
        _personalConversations.value.firstOrNull { it.peerId == senderId }?.let {
            return (it.peerHandle ?: "member") to (it.peerDisplayName ?: it.peerHandle ?: "member")
        }
        return "member" to "member"
    }

    /** Parses a Postgres timestamptz string to epoch millis, tolerant of missing zone. */
    private fun parseIsoToEpochMillis(iso: String?): Long? {
        if (iso.isNullOrBlank()) return null
        runCatching { return java.time.Instant.parse(iso).toEpochMilli() }
        runCatching { return java.time.OffsetDateTime.parse(iso).toInstant().toEpochMilli() }
        return null
    }

    fun addContactByHandle(handle: String) = viewModelScope.launch {
        val trimmed = handle.trim().removePrefix("@")
        if (trimmed.isBlank()) return@launch
        runCatching { NorfoldSocial.addContactByHandle(trimmed) }
            .onSuccess {
                emitInfo("@$trimmed added to your contacts.")
                refreshSocialGraph()
            }
            .onFailure {
                // handle_not_found is deliberately indistinguishable from blocked.
                emitError("No one goes by @$trimmed.")
            }
    }

    fun removeContact(contactId: String) = viewModelScope.launch {
        runCatching { NorfoldSocial.removeContact(contactId) }
            .onSuccess { refreshSocialGraph() }
            .onFailure { emitError("Couldn't remove that contact right now.") }
    }

    fun blockContact(userId: String) = viewModelScope.launch {
        runCatching { NorfoldSocial.blockUser(userId) }
            .onSuccess {
                _blockedAccountIds.value = _blockedAccountIds.value + userId
                emitInfo("Blocked. They can no longer reach you.")
                refreshSocialGraph()
            }
            .onFailure { emitError("Couldn't block right now. Try again.") }
    }

    fun unblockContact(userId: String) = viewModelScope.launch {
        runCatching { NorfoldSocial.unblockUser(userId) }
            .onSuccess {
                _blockedAccountIds.value = _blockedAccountIds.value - userId
                emitInfo("Unblocked.")
                refreshSocialGraph()
            }
            .onFailure { emitError("Couldn't unblock right now. Try again.") }
    }

    fun blockContactByUsername(username: String) {
        val userId = accountIdForUsername(username)
        if (userId == null) {
            emitError("Refresh People before changing this block.")
            return
        }
        blockContact(userId)
    }

    fun unblockContactByUsername(username: String) {
        val userId = accountIdForUsername(username)
        if (userId == null) {
            emitError("Refresh People before changing this block.")
            return
        }
        unblockContact(userId)
    }

    fun isContactBlocked(username: String): Boolean =
        accountIdForUsername(username)?.let(_blockedAccountIds.value::contains) == true

    private fun accountIdForUsername(username: String): String? =
        _socialContacts.value.firstOrNull { it.handle.equals(username, ignoreCase = true) }?.contactId
            ?: state.value.workspaceMembers.firstOrNull {
                it.username.equals(username, ignoreCase = true)
            }?.userId

    fun respondToContactRequest(request: NorfoldSocial.RemoteContactRequest, accept: Boolean) =
        viewModelScope.launch {
            runCatching { NorfoldSocial.respondContactRequest(request.requestId, accept) }
                .onSuccess {
                    _contactRequests.value = _contactRequests.value.filterNot { it.requestId == request.requestId }
                    emitInfo(if (accept) "Message request accepted." else "Message request declined.")
                    refreshSocialGraph()
                }
                .onFailure { emitError("Couldn't update that request. Try again.") }
        }

    fun openContactRequest(request: NorfoldSocial.RemoteContactRequest) = viewModelScope.launch {
        val channelId = repository.accountChannelId(request.conversationId)
            ?: materializeAccountConversation(
                NorfoldSocial.RemoteDirectConversation(
                    conversationId = request.conversationId,
                    kind = "dm",
                    peerId = request.senderId,
                    peerHandle = request.senderHandle,
                    peerDisplayName = request.senderDisplayName,
                    inboundRequestStatus = "pending",
                ),
            )
        selectChatChannel(channelId)
    }

    fun blockContactRequest(request: NorfoldSocial.RemoteContactRequest) = viewModelScope.launch {
        runCatching { NorfoldSocial.blockUser(request.senderId) }
            .onSuccess {
                _blockedAccountIds.value = _blockedAccountIds.value + request.senderId
                _contactRequests.value = _contactRequests.value.filterNot { it.requestId == request.requestId }
                repository.accountChannelId(request.conversationId)?.let { channelId ->
                    repository.chatChannelById(channelId)?.let { channel ->
                        ChatConversationPrefs.setMuted(app, chatConversationPreferenceKey(channel), true)
                    }
                }
                emitInfo("Blocked. This request was hidden.")
                refreshSocialGraph()
            }
            .onFailure { emitError("Couldn't block right now. Try again.") }
    }

    fun createFriendInvite() = viewModelScope.launch {
        runCatching { NorfoldSocial.createFriendInvite() }
            .onSuccess { _pendingFriendInvite.value = it }
            .onFailure { emitError("Couldn't create an invite. Sign in to Norfold Cloud first.") }
    }

    fun dismissFriendInvite() {
        _pendingFriendInvite.value = null
    }

    fun redeemFriendInvite(token: String) = viewModelScope.launch {
        if (token.isBlank()) return@launch
        runCatching { NorfoldSocial.redeemFriendInvite(token) }
            .onSuccess {
                emitInfo("You're now connected.")
                refreshSocialGraph()
            }
            .onFailure { emitError("That invite is invalid or expired.") }
    }
    fun toggleSidebar() { sidebarOpen.value = !sidebarOpen.value }
    fun closeSidebar() { sidebarOpen.value = false }
    fun selectTab(next: HomeTab) { tab.value = next }
    fun search(query: String) { searchQuery.value = query }
    fun openNotesLibrary() {
        selectedNotebookFilterId.value = null
        selectedTagFilterId.value = null
        val note = state.value.selectedNote?.takeIf { it.overlapMode == DocOverlapMode.Reflow }
            ?: state.value.notes.firstOrNull { it.overlapMode == DocOverlapMode.Reflow }
        selectedNoteId.value = note?.id
        selectedDocumentOwner.value = note?.let { DocumentOwner.note(it.id) }
        navigateTo(Destination.NotesHome)
    }

    fun openDocumentsLibrary() {
        selectedNotebookFilterId.value = null
        selectedTagFilterId.value = null
        tab.value = HomeTab.AllNotes
        val document = state.value.selectedNote?.takeIf { it.overlapMode == DocOverlapMode.Bounded }
            ?: state.value.notes.firstOrNull { it.overlapMode == DocOverlapMode.Bounded }
        selectedNoteId.value = document?.id
        selectedDocumentOwner.value = document?.let { DocumentOwner.note(it.id) }
        navigateTo(Destination.DocumentsHome)
    }

    fun filterByNotebook(notebookId: Long?) {
        selectedNotebookFilterId.value = notebookId
        selectedTagFilterId.value = null
        tab.value = HomeTab.AllNotes
        val scope = notebookId?.let { id -> state.value.notebooks.firstOrNull { it.id == id }?.scope }
        val documents = scope == "documents" || (notebookId == null && state.value.destination == Destination.DocumentsHome)
        navigateTo(if (documents) Destination.DocumentsHome else Destination.NotesHome)
    }
    fun filterByTag(tagId: Long?) {
        selectedTagFilterId.value = tagId
        selectedNotebookFilterId.value = null
        if (tagId != null) tab.value = HomeTab.Tags
        navigateTo(Destination.NotesHome)
    }
    fun select(note: Note) {
        // Split view swaps the selection without a NoteEditor -> NoteEditor navigation, so
        // discardUntouchedDrafts never sees the outgoing note: park it here and let the old
        // editor's dispose-time finalizeDraftDiscard make the keep-or-delete call.
        val previousId = selectedNoteId.value
        if (previousId != null && previousId != note.id && draftNoteIds.remove(previousId)) {
            pendingDraftDiscardIds += previousId
        }
        // Reopening a draft that was parked for discard cancels the pending delete and
        // restores the draft-until-edited lifecycle.
        if (pendingDraftDiscardIds.remove(note.id)) draftNoteIds += note.id
        selectedNoteId.value = note.id
        selectedDocumentOwner.value = DocumentOwner.note(note.id)
        selectedObjectId.value = state.value.workspaceObjects.firstOrNull {
            it.objectType == WorkspaceObjectType.Note && it.sourceId == note.id
        }?.id
        navigateTo(if (note.overlapMode == DocOverlapMode.Reflow) Destination.NoteEditor else Destination.DocumentEditor)
    }
    fun clearMessage() = Unit
    fun showMessage(value: String) = emitInfo(value)

    fun registerSettingsDevice(screenClass: String) = viewModelScope.launch {
        repository.registerSettingsDevice(screenClass)
    }

    fun applyDeviceSettingsProfile(deviceId: String, categories: Set<SettingsProfileCategory>) = viewModelScope.launch {
        if (repository.applyDeviceSettingsProfile(deviceId, categories)) {
            emitSuccess("Settings applied to this device")
        } else {
            emitError("That device settings profile is no longer available")
        }
    }

    fun resetDeviceSettings(categories: Set<SettingsProfileCategory>) = viewModelScope.launch {
        if (repository.resetDeviceSettings(categories)) emitSuccess("Selected settings reset to Norfold defaults")
    }

    fun finishOnboarding(
        workspaceName: String,
        purpose: String,
        setup: OnboardingSetupPreferences = OnboardingSetupPreferences(),
        fullName: String = "",
        displayName: String = "",
        template: String = "Start empty",
        emailUpdates: Boolean = false,
        reminders: Boolean = true,
        productTips: Boolean = true,
        backupFrequency: String = "Daily",
        preserveRestoredWorkspace: Boolean = false,
        avatarUri: String = "",
        inviteToken: String? = null,
        openMembers: Boolean = false,
        onFinished: () -> Unit = {},
    ) = viewModelScope.launch {
        val cleanName = workspaceName.trim().ifBlank { "My Workspace" }
        val active = state.value.workspaces.firstOrNull { it.id == state.value.settings.activeWorkspaceId }
        if (active != null && !preserveRestoredWorkspace) {
            repository.updateWorkspace(active.id, cleanName, cleanName.take(1).uppercase(), state.value.settings.themeProfile)
        }
        val defaults = DocsRepository.defaultSettings
        val appearance = if (setup.useRecommendedDefaults) {
            setup.copy(
                themeMode = defaults.themeMode,
                appLabelMode = defaults.appLabelMode,
                appIconFamily = defaults.appIconFamily,
                editorIconFamily = defaults.editorIconFamily,
                fontScale = defaults.fontScale,
                uiScale = defaults.uiScale,
                editorToolbarScale = defaults.editorToolbarScale,
                reduceMotion = defaults.reduceMotion,
            )
        } else setup
        val preserveDeviceSettings = BackupCategory.DeviceSettings in restoredBackupCategories
        val completedSettings = state.value.settings.copy(
                workspaceName = if (preserveRestoredWorkspace) state.value.settings.workspaceName else cleanName,
                workspaceIcon = if (preserveRestoredWorkspace) state.value.settings.workspaceIcon else cleanName.take(1).uppercase(),
                workspacePurpose = purpose,
                syncUserName = displayName.trim().ifBlank { fullName.trim().substringBefore(' ').lowercase().ifBlank { "owner" } },
                // The onboarding question promises that this is the name Norfold uses in the
                // product and shares with collaborators. The legal/full name remains input
                // data only; it must not overwrite the person's chosen calling name.
                syncPublicName = displayName.trim().ifBlank { fullName.trim() },
                themeMode = if (preserveDeviceSettings) state.value.settings.themeMode else appearance.themeMode,
                appLabelMode = if (preserveDeviceSettings) state.value.settings.appLabelMode else appearance.appLabelMode,
                appIconFamily = if (preserveDeviceSettings) state.value.settings.appIconFamily else appearance.appIconFamily,
                editorIconFamily = if (preserveDeviceSettings) state.value.settings.editorIconFamily else appearance.editorIconFamily,
                fontScale = if (preserveDeviceSettings) state.value.settings.fontScale else appearance.fontScale.coerceIn(0.5f, 2f),
                uiScale = if (preserveDeviceSettings) state.value.settings.uiScale else appearance.uiScale.coerceIn(0.5f, 2f),
                editorToolbarScale = if (preserveDeviceSettings) state.value.settings.editorToolbarScale else appearance.editorToolbarScale.coerceIn(0.5f, 2f),
                reduceMotion = if (preserveDeviceSettings) state.value.settings.reduceMotion else appearance.reduceMotion,
                notificationEmail = if (preserveDeviceSettings) state.value.settings.notificationEmail else emailUpdates,
                notificationPush = if (preserveDeviceSettings) state.value.settings.notificationPush else reminders,
                notificationInApp = if (preserveDeviceSettings) state.value.settings.notificationInApp else productTips,
                profileImageUri = avatarUri.takeIf { it.isNotBlank() },
                autoBackup = NorfoldSupabase.isSignedIn() || state.value.settings.autoBackup,
                backupFrequency = backupFrequency.takeIf { it in setOf("Daily", "Weekly", "Monthly") } ?: "Daily",
                settingsDeviceId = installationIdentity.installationId,
                settingsDeviceName = state.value.settings.settingsDeviceName
                    .takeUnless { it == "Android device" }
                    ?: installationIdentity.suggestedDeviceName,
                syncDeviceName = state.value.settings.syncDeviceName
                    .takeUnless { it == "Android device" }
                    ?: installationIdentity.suggestedDeviceName,
                onboardingComplete = true,
            )
        repository.updateSettings(completedSettings)
        NorfoldWorkScheduler.reconcile(app, completedSettings)
        if (!preserveRestoredWorkspace) repository.applyWorkspaceTemplate(template)
        // Only now is onboarding durably complete — safe for the caller to drop its wizard state.
        onFinished()
        resetNavigation(Destination.WorkspaceHub)
        when {
            // "Join a team": accept the pasted invite right away — it signs the account
            // into the shared workspace and switches to it.
            !inviteToken.isNullOrBlank() -> acceptTeamInvitation(inviteToken)
            // "Invite people": land on the Members settings page where invitations live.
            openMembers -> {
                _pendingSettingsSection.value = "WorkspaceMembers"
                navigateTo(Destination.Settings)
            }
        }
    }

    private fun launchEmailAuth(operation: EmailAuthOperation, block: suspend () -> Unit) = viewModelScope.launch {
        if (!_emailAuthOperation.compareAndSet(EmailAuthOperation.Idle, operation)) return@launch
        try {
            block()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } finally {
            _emailAuthOperation.value = EmailAuthOperation.Idle
        }
    }

    private fun authFailureMessage(error: Throwable, fallback: String): String {
        // Every auth failure lands here — keep the full chain in logcat so field reports
        // aren't reduced to the friendly fallback text.
        Log.e("NorfoldAuth", "auth failure (fallback: $fallback)", error)
        val detail = generateSequence(error) { it.cause }.joinToString(" ") { it.message.orEmpty() }.lowercase()
        return when {
            listOf("unable to resolve host", "network is unreachable", "failed to connect", "timeout").any(detail::contains) ->
                "Couldn't reach Norfold Cloud. Check your connection and try again."
            "rate" in detail && ("limit" in detail || "many" in detail) ->
                "Too many attempts. Wait a moment, then try again."
            "invalid login" in detail || "invalid credential" in detail ->
                "That email or password didn't match. Check both and try again."
            "already registered" in detail || "already exists" in detail ->
                "An account already uses that email. Sign in instead."
            // Credential Manager (Google) cases, mapped to something actionable.
            "cancel" in detail && "user" in detail ->
                "Sign-in was cancelled."
            "no credential" in detail || "no accounts" in detail ->
                "No Google account is available on this device. Add one in Android settings, then try again."
            "developer" in detail || "10:" in detail ->
                "Google rejected this build's signature — the signing key isn't registered for this app."
            else -> {
                // Unknown cause: show the deepest real message so the failure is diagnosable
                // from the snackbar alone instead of a dead-end "try again".
                val reason = generateSequence(error) { it.cause }
                    .mapNotNull { it.message?.trim()?.takeIf(String::isNotEmpty) }
                    .lastOrNull()
                if (reason == null) fallback else "$fallback ($reason)"
            }
        }
    }

    fun completeExternalSignIn(provider: String, profileSuggestion: GoogleProfileSuggestion? = null) {
        if (profileSuggestion != null) _googleProfileSuggestion.value = profileSuggestion
        refreshCloudAccountState()
        emailAuthPhase.value = EmailAuthPhase.Completed
        viewModelScope.launch { coordinateCloudAccount(if (provider.equals("Google", true)) CloudAuthProvider.Google else CloudAuthProvider.Email) }
        emitSuccess("Signed in with $provider")
    }

    fun consumeGoogleProfileSuggestion() {
        _googleProfileSuggestion.value = null
    }

    fun reportExternalSignInFailure(error: Throwable, provider: String) {
        emitError(authFailureMessage(error, "$provider sign-in failed. Try again."))
    }

    fun authenticateWithEmail(email: String, password: String, signUp: Boolean) = launchEmailAuth(
        if (signUp) EmailAuthOperation.SignUp else EmailAuthOperation.SignIn,
    ) {
        if (email.isBlank() || password.length < 8) {
            emitError("Enter a valid email and a password with at least 8 characters")
            return@launchEmailAuth
        }
        if (signUp) {
            runCatching { NorfoldSupabase.signUpWithEmail(email, password) }
                .onSuccess {
                    emailAuthPhase.value = EmailAuthPhase.AwaitSignupCode(email)
                    emailResendAvailableAtElapsedMs = SystemClock.elapsedRealtime() + EmailCodeResendCooldownMs
                    emitSuccess("We emailed a 6-digit code to $email")
                }
                .onFailure { emitError(authFailureMessage(it, "Couldn't create the account. Check the details and try again.")) }
        } else {
            runCatching { NorfoldSupabase.signInWithEmail(email, password) }
                .onSuccess {
                    refreshCloudAccountState()
                    coordinateCloudAccount(CloudAuthProvider.Email)
                    emailAuthPhase.value = EmailAuthPhase.Completed
                    emitSuccess("Signed in")
                }
                .onFailure { error ->
                    if (error.message?.contains("confirm", ignoreCase = true) == true) {
                        // Account exists but was never verified — resend the code and finish verification.
                        runCatching { NorfoldSupabase.resendSignupCode(email) }
                        emailAuthPhase.value = EmailAuthPhase.AwaitSignupCode(email)
                        emailResendAvailableAtElapsedMs = SystemClock.elapsedRealtime() + EmailCodeResendCooldownMs
                        emitInfo("This email still needs verification — we sent a new 6-digit code")
                    } else {
                        emitError(authFailureMessage(error, "Email sign-in failed. Check your details and try again."))
                    }
                }
        }
    }

    fun verifyEmailCode(code: String) = launchEmailAuth(EmailAuthOperation.VerifyCode) {
        val trimmed = code.trim()
        if (trimmed.length != 6 || trimmed.any { !it.isDigit() }) {
            emitError("Enter the 6-digit code from the email")
            return@launchEmailAuth
        }
        when (val phase = emailAuthPhase.value) {
            is EmailAuthPhase.AwaitSignupCode ->
                runCatching { NorfoldSupabase.verifySignupCode(phase.email, trimmed) }
                    .onSuccess {
                        refreshCloudAccountState()
                        coordinateCloudAccount(CloudAuthProvider.Email)
                        emailAuthPhase.value = EmailAuthPhase.Completed
                        emitSuccess("Email verified — you're signed in")
                    }
                    .onFailure { emitError(authFailureMessage(it, "That code didn't work. Try again or request a new code.")) }
            is EmailAuthPhase.AwaitRecoveryCode ->
                runCatching { NorfoldSupabase.verifyRecoveryCode(phase.email, trimmed) }
                    .onSuccess { emailAuthPhase.value = EmailAuthPhase.AwaitNewPassword(phase.email) }
                    .onFailure { emitError(authFailureMessage(it, "That code didn't work. Try again or request a new code.")) }
            else -> Unit
        }
    }

    fun resendEmailCode() = launchEmailAuth(EmailAuthOperation.ResendCode) {
        val remaining = emailResendCooldownRemainingSeconds()
        if (remaining > 0L) {
            emitInfo("You can request another code in ${remaining}s")
            return@launchEmailAuth
        }
        when (val phase = emailAuthPhase.value) {
            is EmailAuthPhase.AwaitSignupCode ->
                runCatching { NorfoldSupabase.resendSignupCode(phase.email) }
                    .onSuccess {
                        emailResendAvailableAtElapsedMs = SystemClock.elapsedRealtime() + EmailCodeResendCooldownMs
                        emitSuccess("New code sent to ${phase.email}")
                    }
                    .onFailure { emitError(authFailureMessage(it, "Couldn't resend the code. Wait a moment and try again.")) }
            is EmailAuthPhase.AwaitRecoveryCode ->
                runCatching { NorfoldSupabase.requestPasswordReset(phase.email) }
                    .onSuccess {
                        emailResendAvailableAtElapsedMs = SystemClock.elapsedRealtime() + EmailCodeResendCooldownMs
                        emitSuccess("New code sent to ${phase.email}")
                    }
                    .onFailure { emitError(authFailureMessage(it, "Couldn't resend the code. Wait a moment and try again.")) }
            else -> Unit
        }
    }

    fun beginPasswordReset(email: String) = launchEmailAuth(EmailAuthOperation.RequestReset) {
        if (email.isBlank()) {
            emitError("Enter your email address first")
            return@launchEmailAuth
        }
        runCatching { NorfoldSupabase.requestPasswordReset(email) }
            .onSuccess {
                emailAuthPhase.value = EmailAuthPhase.AwaitRecoveryCode(email)
                emailResendAvailableAtElapsedMs = SystemClock.elapsedRealtime() + EmailCodeResendCooldownMs
                emitSuccess("We emailed a 6-digit reset code to $email")
            }
            .onFailure { emitError(authFailureMessage(it, "Couldn't start the password reset. Try again.")) }
    }

    fun completePasswordReset(newPassword: String) = launchEmailAuth(EmailAuthOperation.UpdatePassword) {
        if (newPassword.length < 8) {
            emitError("Use a password with at least 8 characters")
            return@launchEmailAuth
        }
        runCatching { NorfoldSupabase.updatePassword(newPassword) }
            .onSuccess {
                refreshCloudAccountState()
                coordinateCloudAccount(CloudAuthProvider.Email)
                emailAuthPhase.value = EmailAuthPhase.Completed
                emitSuccess("Password updated — you're signed in")
            }
            .onFailure { emitError(authFailureMessage(it, "Couldn't update the password. Try again.")) }
    }

    fun cancelEmailAuth() {
        emailAuthPhase.value = EmailAuthPhase.None
        emailResendAvailableAtElapsedMs = 0L
    }

    fun signOutCloudAccount() = viewModelScope.launch {
        runCatching {
            NorfoldSupabase.currentAccountId()?.let { accountId ->
                runCatching {
                    privateChatArchive.write(
                        accountId = accountId,
                        includeCloud = googleDriveAuthStore.hasAuthState(),
                    )
                }.onFailure { Log.w("NorfoldArchive", "Final private Chat archive deferred", it) }
            }
            pushRegistrationClient.disableCurrentToken()
            NorfoldSupabase.signOut()
        }
            .onSuccess {
                // Mutual-contact state is per-account; the next sign-in starts fresh.
                MutualChatContacts.clear(app)
                privateChatArchiveJob?.cancel()
                privateChatArchiveJob = null
                privateChatArchiveRestorePending = false
                privateChatArchiveDiscoveryComplete = false
                secureRelayObserver?.cancel()
                secureRelayObserver = null
                _socialContacts.value = emptyList()
                _contactRequests.value = emptyList()
                _blockedAccountIds.value = emptySet()
                _personalConversations.value = emptyList()
                _privateChatArchiveState.value = PrivateChatArchiveUiState()
                installationIdentity.bindAccount(null)
                NorfoldWorkScheduler.cancelAccountWork(app)
                _cloudAuthProvider.value = CloudAuthProvider.None
                _accountCoordinatorState.value = AccountCoordinatorState.SignedOut
                _driveBackups.value = emptyList()
                refreshCloudAccountState()
                _teamCollaborationStatus.value = if (_teamWorkspaceRemoteId.value == null) "Local collaboration" else "Sign in to sync team chat"
                emitSuccess("Signed out of Norfold Cloud")
            }
            .onFailure { emitError(authFailureMessage(it, "Couldn't sign out. Try again.")) }
    }

    private fun refreshCloudAccountState() {
        _cloudAccountEmail.value = NorfoldSupabase.currentAccountEmail()
        repository.setActiveChatAccount(NorfoldSupabase.currentAccountId())
        if (!NorfoldSupabase.isSignedIn()) _accountCoordinatorState.value = AccountCoordinatorState.SignedOut
    }

    private suspend fun coordinateCloudAccount(provider: CloudAuthProvider = _cloudAuthProvider.value) {
        val accountId = NorfoldSupabase.currentAccountId() ?: run {
            _accountCoordinatorState.value = AccountCoordinatorState.SignedOut
            return
        }
        _cloudAuthProvider.value = provider.takeUnless { it == CloudAuthProvider.None } ?: _cloudAuthProvider.value
        _accountCoordinatorState.value = AccountCoordinatorState.CloudSessionReady
        installationIdentity.bindAccount(accountId)
        runCatching { pushRegistrationClient.registerCurrentToken() }
            .onFailure { Log.w("NorfoldPush", "Post-login token registration deferred", it) }
        runCatching {
            secureChatBackend.registerCurrentDevice(archiveCapable = true)
            secureChatBackend.renewLease()
        }.onSuccess {
            startSecureRelayObserver()
            drainSecureChatOutbox()
            retryPendingTeamChatSends()
        }
            .onFailure { Log.w("NorfoldSecureChat", "Secure device registration deferred", it) }
        val current = repository.settingsValue()
        if (!current.autoBackup) repository.updateSettings(current.copy(autoBackup = true))
        _accountCoordinatorState.value = AccountCoordinatorState.DiscoveringBackupsAndMemberships
        refreshTeamBinding()
        discoverPrivateChatArchive(accountId)
        if (!googleDriveAuthStore.hasAuthState()) {
            _driveBackups.value = emptyList()
            _accountCoordinatorState.value = AccountCoordinatorState.BackupSetupRequired
            NorfoldWorkScheduler.reconcile(app, repository.settingsValue())
            return
        }
        runCatching { googleDriveApiSyncStore.discoverBackups() }
            .onSuccess { backups ->
                _driveBackups.value = backups
                _accountCoordinatorState.value = if (backups.isEmpty()) {
                    AccountCoordinatorState.BackupSetupRequired
                } else {
                    AccountCoordinatorState.RestoreChoiceRequired
                }
            }
            .onFailure { error ->
                _accountCoordinatorState.value = AccountCoordinatorState.RecoverableError(
                    googleDriveFailureMessage(error, "Couldn't check Google Drive backups"),
                )
            }
        NorfoldWorkScheduler.reconcile(app, repository.settingsValue())
    }

    private suspend fun refreshTeamBinding() {
        val localWorkspaceId = repository.activeWorkspaceIdValue()
        _teamWorkspaceRemoteId.value = repository.remoteWorkspaceId(localWorkspaceId)
        _teamCollaborationStatus.value = when {
            _teamWorkspaceRemoteId.value == null -> "Local collaboration"
            !NorfoldSupabase.isSignedIn() -> "Team workspace · sign in to sync"
            else -> "Team workspace · connected"
        }
        if (_teamWorkspaceRemoteId.value != null && NorfoldSupabase.isSignedIn()) refreshSharedDocuments()
    }

    fun enableTeamCollaboration() = viewModelScope.launch {
        val active = state.value.workspaces.firstOrNull { it.id == state.value.settings.activeWorkspaceId }
            ?: return@launch
        if (!NorfoldSupabase.isSignedIn()) {
            emitError("Sign in to Norfold Cloud before enabling team collaboration")
            return@launch
        }
        runCatching {
            repository.remoteWorkspaceId(active.id)?.let { return@runCatching it }
            val remote = NorfoldSupabase.createTeamWorkspace(active.name)
            repository.bindRemoteWorkspace(active.id, remote.id)
            remote.id
        }.onSuccess { remoteId ->
            _teamWorkspaceRemoteId.value = remoteId
            _teamCollaborationStatus.value = "Team workspace · connected"
            refreshSharedDocuments()
            emitSuccess("Team collaboration enabled")
        }.onFailure { error ->
            emitError(authFailureMessage(error, "Couldn't enable team collaboration. Try again."))
        }
    }

    fun createTeamInvitation(email: String, viewer: Boolean = false) = viewModelScope.launch {
        val normalized = email.trim().lowercase()
        if (!normalized.contains('@') || normalized.length > 254) {
            emitError("Enter the invited member's email address")
            return@launch
        }
        val remoteWorkspaceId = _teamWorkspaceRemoteId.value ?: repository.remoteWorkspaceId()
        if (remoteWorkspaceId == null) {
            emitError("Enable team collaboration before inviting members")
            return@launch
        }
        runCatching { NorfoldSupabase.createTeamInvitation(remoteWorkspaceId, normalized, viewer) }
            .onSuccess { invite ->
                _teamInviteShare.value = TeamInviteShare(normalized, invite.token, invite.expiresAt)
                emitSuccess("Invitation created for $normalized")
            }
            .onFailure { error -> emitError(authFailureMessage(error, "Couldn't create the invitation. Try again.")) }
    }

    fun clearTeamInviteShare() {
        _teamInviteShare.value = null
    }

    fun acceptTeamInvitation(token: String) = viewModelScope.launch {
        if (token.isBlank()) {
            emitError("Paste the invitation code first")
            return@launch
        }
        if (!NorfoldSupabase.isSignedIn()) {
            emitError("Sign in with the invited account before accepting the invitation")
            return@launch
        }
        runCatching {
            val remoteId = NorfoldSupabase.acceptTeamInvitation(token.trim())
            // Look up the binding in the DB, not in-memory state: a workspace created by an
            // earlier attempt (or another screen) may not have reached the state flow yet.
            val localId = repository.localWorkspaceIdForRemote(remoteId) ?: run {
                val remote = NorfoldSupabase.workspace(remoteId)
                // Atomic create+bind — a failed bind can't strand an unbound workspace for a
                // retry to duplicate.
                repository.createRemoteBoundWorkspace(remote.name, remote.name.take(1).uppercase().ifBlank { "T" }, remoteId)
            }
            repository.setActiveWorkspace(localId)
            remoteId
        }.onSuccess { remoteId ->
            _teamWorkspaceRemoteId.value = remoteId
            _teamCollaborationStatus.value = "Team workspace · connected"
            resetNavigation(Destination.WorkspaceHub)
            refreshTeamChat()
            refreshSharedDocuments()
            emitSuccess("Team workspace joined")
        }.onFailure { error -> emitError(authFailureMessage(error, "Couldn't accept that invitation. Check the code and account.")) }
    }

    fun refreshTeamChat() = viewModelScope.launch {
        // Drop, don't queue: manual refresh, initial catch-up and reconnect fallback can overlap.
        if (!teamChatRefreshMutex.tryLock()) return@launch
        try {
            val localWorkspaceId = repository.activeWorkspaceIdValue()
            val remoteWorkspaceId = repository.remoteWorkspaceId(localWorkspaceId) ?: run {
                _teamWorkspaceRemoteId.value = null
                _teamCollaborationStatus.value = "Local collaboration"
                return@launch
            }
            _teamWorkspaceRemoteId.value = remoteWorkspaceId
            if (!NorfoldSupabase.isSignedIn()) {
                _teamCollaborationStatus.value = "Team workspace · sign in to sync"
                return@launch
            }
            // Push before pull: messages that were saved locally while offline or after a failed
            // send reach the team on the next refresh, as the send-failure toast promises.
            state.value.chatChannels
                .filter { it.kind != ChatChannelKind.Dm && repository.chatChannelRemoteBinding(localWorkspaceId, it.id) == null }
                .forEach { channel -> runCatching { publishChatChannel(channel) } }
            retryPendingTeamChatSends()
            drainChatUploads()
            runCatching {
                val since = teamChatNewestCreatedAt.takeIf { teamChatCursorWorkspaceId == remoteWorkspaceId }
                val objects = NorfoldSupabase.teamChatObjects(remoteWorkspaceId, since)
                val channels = objects.filter { it.objectType == "chat_channel" }
                channels.sortedBy { it.serverVersion }.forEach {
                    repository.importRemoteChatChannel(localWorkspaceId, it, NorfoldSupabase.currentAccountId())
                }
                val messages = objects.filter { it.objectType == "chat_message" }
                val imported = messages
                    .sortedBy { it.content?.get("createdAt")?.toString()?.trim('"')?.toLongOrNull() ?: 0L }
                    .count { repository.importRemoteChatMessage(localWorkspaceId, it) }
                recordDeliveredReceipts(remoteWorkspaceId, messages)
                // Advance only after every fetched row is imported, so a mid-batch failure can't
                // skip rows on the next poll. ISO timestamptz strings from the same server compare
                // lexicographically; the cursor is inclusive (gte) and import dedupes boundary
                // rows by remote id.
                objects.mapNotNull { it.createdAt }.maxOrNull()?.let { newest ->
                    if (since == null || newest > since) {
                        teamChatCursorWorkspaceId = remoteWorkspaceId
                        teamChatNewestCreatedAt = newest
                    }
                }
                imported
            }.onSuccess {
                _teamCollaborationStatus.value = "Team workspace · connected"
            }.onFailure {
                _teamCollaborationStatus.value = if (networkOnline.value) "Team sync error · tap to retry" else "Offline · messages saved locally"
            }
            // Roster: the People list reads this, not message authors, so members who haven't
            // spoken yet still appear. A failure here must not flip the chat status to error, and
            // authoritative=false guards against wiping the local roster on a transient empty read.
            runCatching {
                val members = NorfoldSupabase.workspaceMembers(remoteWorkspaceId).map { it.toWorkspaceMember() }
                repository.replaceWorkspaceMembers(localWorkspaceId, members, authoritative = members.isNotEmpty())
            }
            // Tick pass for our own messages, piggybacked on the poll cadence.
            refreshChatReceipts()
        } finally {
            teamChatRefreshMutex.unlock()
        }
    }

    /**
     * Confirms delivery of teammates' messages that just landed on this device
     * (their single tick becomes a double tick). Own messages are skipped;
     * quiet failure — the receipts backend may not be deployed yet.
     */
    private fun recordDeliveredReceipts(
        remoteWorkspaceId: String,
        messages: List<com.norfold.app.cloud.RemoteWorkspaceObject>,
    ) = viewModelScope.launch {
        val username = state.value.settings.syncUserName.ifBlank { "you" }
        val incoming = messages
            .filter { remote ->
                remote.content?.get("authorUsername")?.toString()?.trim('"')
                    ?.equals(username, ignoreCase = true) != true
            }
            .map { it.clientSyncId }
        runCatching { NorfoldPresence.recordReceipts(remoteWorkspaceId, incoming, viewed = false) }
    }

    fun refreshSharedDocuments() = viewModelScope.launch {
        if (!NorfoldSupabase.isSignedIn()) return@launch
        val localWorkspaceId = repository.activeWorkspaceIdValue()
        val remoteWorkspaceId = repository.remoteWorkspaceId(localWorkspaceId) ?: return@launch
        runCatching {
            sharedDocumentSyncMutex.withLock {
                NorfoldSupabase.sharedDocuments(remoteWorkspaceId)
                    .sortedBy { it.serverVersion }
                    .count { repository.importRemoteSharedDocument(localWorkspaceId, it)?.changed == true }
            }
        }.onSuccess { imported ->
            if (imported > 0) emitSuccess(if (imported == 1) "1 shared document updated" else "$imported shared documents updated")
        }.onFailure {
            _teamCollaborationStatus.value = if (networkOnline.value) "Shared document sync error" else "Offline · documents saved locally"
        }
    }

    private suspend fun publishSharedDocument(noteId: Long, locallyDirtyBlockIds: Set<String>) {
        if (!NorfoldSupabase.isSignedIn()) return
        sharedDocumentSyncMutex.withLock {
            val settings = repository.settingsValue()
            val remoteWorkspaceId = repository.remoteWorkspaceId(settings.activeWorkspaceId) ?: return@withLock
            var note = repository.noteById(noteId) ?: return@withLock
            if (note.overlapMode != DocOverlapMode.Bounded) return@withLock
            val syncId = repository.ensureRemoteDocumentSyncId(settings.activeWorkspaceId, noteId)
            val binding = repository.remoteDocumentBinding(settings.activeWorkspaceId, noteId)
            var dirty = locallyDirtyBlockIds
            if (binding?.remoteId == null) dirty = note.document.blocks.mapTo(linkedSetOf()) { it.id }

            repeat(2) { attempt ->
                val currentRemote = NorfoldSupabase.sharedDocuments(remoteWorkspaceId, syncId).maxByOrNull { it.serverVersion }
                val remoteDocument = currentRemote?.content?.get("documentJson")?.jsonPrimitive?.contentOrNull
                    ?.takeIf { it.length <= 4 * 1024 * 1024 }
                    ?.let { runCatching { BlockDocumentJson.decode(it) }.getOrNull() }
                val merged = if (remoteDocument == null) note.document
                else mergeSharedDocumentBlocks(remoteDocument, note.document, dirty)
                if (merged != note.document) {
                    repository.updateNote(
                        note = note,
                        title = note.title,
                        document = merged,
                        dirtyBlockIds = (note.document.blocks.map { it.id } + merged.blocks.map { it.id }).toSet(),
                    )
                    note = requireNotNull(repository.noteById(noteId))
                }
                val content = buildJsonObject {
                    put("schemaVersion", 1)
                    put("title", note.title)
                    put("documentJson", BlockDocumentJson.encode(merged))
                    put("layoutMode", note.overlapMode.name.lowercase())
                    put("layoutJson", DocLayoutJson.encode(note.freeformLayout, note.canvasSpec))
                    put("updatedAt", note.updatedAt)
                }
                val result = runCatching {
                    NorfoldSupabase.upsertSharedDocument(
                        remoteWorkspaceId = remoteWorkspaceId,
                        documentSyncId = syncId,
                        deviceId = settings.settingsDeviceId,
                        content = content,
                        baseVersion = currentRemote?.serverVersion ?: binding?.remoteVersion?.takeIf { it > 0 },
                    )
                }
                if (result.isSuccess) {
                    repository.bindRemoteDocument(settings.activeWorkspaceId, noteId, result.getOrThrow())
                    _documentPresenceStatus.value = if (_documentPresence.value.size <= 1) "Only you are here · synced" else "${_documentPresence.value.size} people here · synced"
                    return@withLock
                }
                if (attempt == 1) throw result.exceptionOrNull() ?: IllegalStateException("Document sync failed")
            }
        }
    }

    /**
     * Called from the Chat destination's lifecycle. Initial pull closes the gap before the live
     * subscription; a second pull after subscribe/reconnect closes the subscription race window.
     */
    suspend fun observeTeamChatWhileVisible() {
        var retryDelayMs = 2_000L
        while (kotlinx.coroutines.currentCoroutineContext().isActive) {
            val localWorkspaceId = repository.activeWorkspaceIdValue()
            val remoteWorkspaceId = repository.remoteWorkspaceId(localWorkspaceId) ?: return
            if (!NorfoldSupabase.isSignedIn()) return
            try {
                val since = teamChatNewestCreatedAt.takeIf { teamChatCursorWorkspaceId == remoteWorkspaceId }
                NorfoldSupabase.teamChatChanges(remoteWorkspaceId, since).collect { remote ->
                    if (remote.objectType == "chat_channel") {
                        repository.importRemoteChatChannel(localWorkspaceId, remote, NorfoldSupabase.currentAccountId())
                        _teamCollaborationStatus.value = "Team workspace · live"
                        retryDelayMs = 2_000L
                        return@collect
                    }
                    if (remote.deletedAt != null) {
                        // Someone unsent this message; drop the local copy if it was imported.
                        repository.removeChatMessageBySyncId(localWorkspaceId, remote.clientSyncId)
                        _teamCollaborationStatus.value = "Team workspace · live"
                        retryDelayMs = 2_000L
                        return@collect
                    }
                    if (repository.importRemoteChatMessage(localWorkspaceId, remote)) {
                        remote.createdAt?.let { newest ->
                            if (teamChatCursorWorkspaceId != remoteWorkspaceId || teamChatNewestCreatedAt == null || newest > teamChatNewestCreatedAt!!) {
                                teamChatCursorWorkspaceId = remoteWorkspaceId
                                teamChatNewestCreatedAt = newest
                            }
                        }
                        recordDeliveredReceipts(remoteWorkspaceId, listOf(remote))
                        // Live-arrived messages are read instantly when the conversation is open.
                        if (chatConversationOpen.value) {
                            val remoteChannelSyncId = remote.content?.get("channelSyncId")?.jsonPrimitive?.contentOrNull
                            val remoteLocalChannelId = remoteChannelSyncId?.let {
                                repository.localChatChannelIdBySyncId(localWorkspaceId, it)
                            }
                            if (activeChatChannelId.value == remoteLocalChannelId) markChatConversationRead(remoteLocalChannelId)
                        }
                    }
                    _teamCollaborationStatus.value = "Team workspace · live"
                    retryDelayMs = 2_000L
                }
                _teamCollaborationStatus.value = "Live sync reconnecting…"
                delay(retryDelayMs)
                refreshTeamChat().join()
                retryDelayMs = (retryDelayMs * 2).coerceAtMost(30_000L)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                _teamCollaborationStatus.value = if (networkOnline.value) "Live sync reconnecting…" else "Offline · messages saved locally"
                delay(retryDelayMs)
                refreshTeamChat().join()
                retryDelayMs = (retryDelayMs * 2).coerceAtMost(30_000L)
            }
        }
    }

    /**
     * Re-attempts the oldest locally-saved chat messages whose remote send hasn't landed yet.
     * Each entry reuses its original syncId/idempotencyKey, so a retry of a send that actually
     * reached the server upserts instead of duplicating. Stops at the first failure — the
     * network is likely still down — and puts the unsent remainder back, order preserved.
     */
    private suspend fun retryPendingTeamChatSends() {
        if (!NorfoldSupabase.isSignedIn()) return
        val localWorkspaceId = repository.activeWorkspaceIdValue()
        val remoteWorkspaceId = repository.remoteWorkspaceId(localWorkspaceId)
        val batch = repository.pendingRemoteChatMessages(localWorkspaceId, MaxTeamChatRetryBatch)
        for (pending in batch) {
            val (message, syncId) = pending
            val channel = message.channelId?.let { repository.chatChannelById(it) }
            if (channel?.scope == ChatScope.Account) {
                // Account operations have their own durable, idempotent outbox.
                continue
            }
            val workspaceTarget = remoteWorkspaceId ?: continue
            repository.enqueueSecureChatOperation(
                SecureChatOutboxEntity(
                    id = syncId,
                    workspaceId = localWorkspaceId,
                    conversationId = channel?.syncId ?: workspaceTarget,
                    remoteWorkspaceId = workspaceTarget,
                    channelSyncId = channel?.syncId,
                    operation = "text",
                    body = message.body,
                    createdAt = message.createdAt,
                    replyPreview = message.replyPreview,
                    expiresAt = message.expiresAt,
                ),
            )
        }
        drainSecureChatOutbox()
    }

    // Minor 16: signed-out sends used to fail with only a status-line change, indistinguishable
    // from a network failure. Toast once per session so the user learns the message is parked
    // until they sign in, without nagging on every send.
    private var signedOutChatNoticeShown = false

    private fun notifySignedOutChatSend() {
        _teamCollaborationStatus.value = "Saved locally · sign in to sync"
        if (!signedOutChatNoticeShown) {
            signedOutChatNoticeShown = true
            emitInfo("Message saved on this device. Sign in to send it to your team.")
        }
    }

    fun emailResendCooldownRemainingSeconds(): Long =
        ((emailResendAvailableAtElapsedMs - SystemClock.elapsedRealtime()).coerceAtLeast(0L) + 999L) / 1_000L

    fun consumeEmailAuthCompleted() {
        if (emailAuthPhase.value == EmailAuthPhase.Completed) emailAuthPhase.value = EmailAuthPhase.None
    }

    fun createGoal(title: String) = viewModelScope.launch {
        repository.createGoal(title)
        emitSuccess("Goal created")
    }

    fun updateGoal(goal: GoalItem) = viewModelScope.launch { repository.updateGoal(goal) }
    fun deleteGoal(goal: GoalItem) {
        if (goal.id in pendingGoalDeleteIds.value) return
        pendingGoalDeleteIds.value = pendingGoalDeleteIds.value + goal.id
        val action = registerFeedbackResolution(
            onAction = {
                pendingGoalDeleteIds.value = pendingGoalDeleteIds.value - goal.id
                emitSuccess("Goal restored")
            },
            onDismiss = {
                try {
                    repository.deleteGoal(goal)
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: Throwable) {
                    emitError("Couldn't delete the goal. It was restored so you can try again.")
                } finally {
                    pendingGoalDeleteIds.value = pendingGoalDeleteIds.value - goal.id
                }
            },
        )
        emitSuccess("Goal deleted", "Undo", action)
    }

    fun createCalendarEvent(title: String, startAt: Long, endAt: Long) = viewModelScope.launch {
        repository.createCalendarEvent(title, startAt, endAt)
        emitSuccess("Event created")
    }

    fun updateCalendarEvent(event: CalendarEventItem) = viewModelScope.launch { repository.updateCalendarEvent(event) }
    fun deleteCalendarEvent(event: CalendarEventItem) {
        if (event.id in pendingCalendarEventDeleteIds.value) return
        pendingCalendarEventDeleteIds.value = pendingCalendarEventDeleteIds.value + event.id
        val action = registerFeedbackResolution(
            onAction = {
                pendingCalendarEventDeleteIds.value = pendingCalendarEventDeleteIds.value - event.id
                emitSuccess("Event restored")
            },
            onDismiss = {
                try {
                    repository.deleteCalendarEvent(event)
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: Throwable) {
                    emitError("Couldn't delete the event. It was restored so you can try again.")
                } finally {
                    pendingCalendarEventDeleteIds.value = pendingCalendarEventDeleteIds.value - event.id
                }
            },
        )
        emitSuccess("Event deleted", "Undo", action)
    }

    fun setDiagnosticsOptions(enabled: Boolean? = null, includeDeviceInfo: Boolean? = null, askBeforeSharing: Boolean? = null) {
        diagnostics.value = diagnosticsStore.update(enabled, includeDeviceInfo, askBeforeSharing)
    }

    fun acknowledgeCrashPrompt() {
        diagnostics.value = diagnosticsStore.acknowledgeCrashPrompt()
    }

    fun clearDiagnostics() {
        diagnostics.value = diagnosticsStore.clear()
        emitSuccess("Diagnostics cleared")
    }

    fun shareDiagnostics() {
        runCatching {
            diagnosticsStore.log("Diagnostics shared by user")
            val intent = Intent.createChooser(diagnosticsStore.shareIntent(), "Share Norfold diagnostics")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            app.startActivity(intent)
            diagnostics.value = diagnosticsStore.acknowledgeCrashPrompt()
        }.onFailure {
            emitError("Couldn't open the share sheet. The diagnostics log remains on this device.")
        }
    }

    fun openWorkspaceObject(obj: WorkspaceObject) {
        selectedObjectId.value = obj.id
        navigateTo(Destination.ObjectDetail)
    }

    fun openWorkspaceObjectSource(obj: WorkspaceObject) {
        selectedObjectId.value = obj.id
        when (obj.objectType) {
            WorkspaceObjectType.Note -> state.value.notes.firstOrNull { it.id == obj.sourceId }?.let { select(it) } ?: go(Destination.NotesHome)
            WorkspaceObjectType.Task -> go(Destination.Tasks)
            WorkspaceObjectType.Goal -> go(Destination.Goals)
            WorkspaceObjectType.CalendarEvent -> openTaskCalendar()
            WorkspaceObjectType.File -> go(Destination.Files)
            // Chat message objects live in the General feed; land inside that conversation.
            WorkspaceObjectType.ChatMessage -> selectChatChannel(null)
            WorkspaceObjectType.ChatChannel -> go(Destination.Chat)
            WorkspaceObjectType.DatabaseRow -> go(Destination.Database)
            WorkspaceObjectType.Workspace, WorkspaceObjectType.System -> go(Destination.WorkspaceHub)
        }
    }

    fun addWorkspaceComment(objectId: Long, body: String) = viewModelScope.launch {
        if (body.isBlank()) return@launch
        val settings = state.value.settings
        repository.addWorkspaceComment(
            objectId = objectId,
            body = body,
            username = settings.syncUserName.ifBlank { "you" },
            displayName = settings.syncPublicName.ifBlank { settings.syncUserName.ifBlank { "You" } },
        )
        emitSuccess("Comment added")
    }

    fun createNote() = viewModelScope.launch {
        val folderId = selectedNotebookFilterId.value?.takeIf { selectedId ->
            state.value.notebooks.any { it.id == selectedId && it.scope == "notes" }
        }
        val id = repository.createNote(title = "Untitled note", body = "", notebookId = folderId, layoutMode = DocOverlapMode.Reflow)
        draftNoteIds += id
        persistDraftIds()
        selectedNoteId.value = id
        selectedDocumentOwner.value = DocumentOwner.note(id)
        navigateTo(Destination.NoteEditor)
    }

    fun createDocument() = viewModelScope.launch {
        val folderId = selectedNotebookFilterId.value?.takeIf { selectedId ->
            state.value.notebooks.any { it.id == selectedId && it.scope == "documents" }
        }
        val id = repository.createNote(title = "Untitled document", body = "", notebookId = folderId, layoutMode = DocOverlapMode.Bounded)
        draftNoteIds += id
        persistDraftIds()
        selectedNoteId.value = id
        selectedDocumentOwner.value = DocumentOwner.note(id)
        navigateTo(Destination.DocumentEditor)
    }

    fun enterDocumentPresence(owner: DocumentOwner) {
        documentPresenceJob?.cancel()
        documentContentJob?.cancel()
        _documentPresence.value = emptyList()
        localDocumentPresence.value = null
        if (owner.type != DocumentOwnerType.Note) {
            _documentPresenceStatus.value = "Presence is available for shared documents"
            return
        }
        documentPresenceJob = viewModelScope.launch {
            val settings = state.value.settings
            val remoteWorkspaceId = repository.remoteWorkspaceId(settings.activeWorkspaceId)
            val accountId = NorfoldSupabase.currentAccountId()
            if (remoteWorkspaceId == null || accountId == null) {
                _documentPresenceStatus.value = if (accountId == null) {
                    "Sign in to see document collaborators"
                } else {
                    "Connect this workspace to Norfold Cloud"
                }
                return@launch
            }
            val syncId = repository.ensureRemoteDocumentSyncId(settings.activeWorkspaceId, owner.id)
            documentContentJob = viewModelScope.launch {
                runCatching { publishSharedDocument(owner.id, emptySet()) }
                    .onFailure {
                        if (it is CancellationException) throw it
                        _documentPresenceStatus.value = if (networkOnline.value) "Document sync error · retrying when reopened" else "Offline · document saved locally"
                    }
                runCatching {
                    NorfoldSupabase.sharedDocumentChanges(remoteWorkspaceId, syncId).collect { remote ->
                        repository.importRemoteSharedDocument(settings.activeWorkspaceId, remote)
                    }
                }.onFailure {
                    if (it is CancellationException) throw it
                    _documentPresenceStatus.value = if (networkOnline.value) "Live document sync reconnecting…" else "Offline · document saved locally"
                }
            }
            val initial = DocumentPresenceState(
                userId = accountId,
                displayName = settings.syncPublicName.ifBlank {
                    settings.syncUserName.ifBlank {
                        NorfoldSupabase.currentAccountEmail().orEmpty().substringBefore('@').ifBlank { "You" }
                    }
                },
                deviceId = settings.settingsDeviceId,
                documentSyncId = syncId,
                color = 0xFF8B5CF6,
            )
            localDocumentPresence.value = initial
            _documentPresenceStatus.value = "Connecting collaborators…"
            runCatching {
                NorfoldSupabase.documentPresence(
                    remoteWorkspaceId = remoteWorkspaceId,
                    documentSyncId = syncId,
                    initial = initial,
                    updates = localDocumentPresence.filterNotNull(),
                ).collect { people ->
                    _documentPresence.value = people
                    _documentPresenceStatus.value = if (people.size == 1) "Only you are here" else "${people.size} people here"
                }
            }.onFailure { error ->
                if (error is CancellationException) throw error
                _documentPresence.value = emptyList()
                _documentPresenceStatus.value = "Couldn't connect presence · tap to retry"
            }
        }
    }

    fun updateDocumentPresence(blockId: String?, editing: Boolean) {
        _activeDocumentBlockId.value = blockId
        localDocumentPresence.value = localDocumentPresence.value?.copy(
            blockId = blockId,
            editing = editing,
            updatedAt = System.currentTimeMillis(),
        )
    }

    fun leaveDocumentPresence() {
        documentPresenceJob?.cancel()
        documentPresenceJob = null
        documentContentJob?.cancel()
        documentContentJob = null
        localDocumentPresence.value = null
        _activeDocumentBlockId.value = null
        _documentPresence.value = emptyList()
        _documentPresenceStatus.value = "Local document"
    }

    fun openTaskDocument(task: TaskItem) = viewModelScope.launch {
        repository.ensureTaskDocument(task)
        selectedDocumentOwner.value = DocumentOwner.task(task.id)
        selectedNoteId.value = null
        navigateTo(Destination.NoteEditor)
    }

    fun openCalendarEventDocument(event: CalendarEventItem) = viewModelScope.launch {
        repository.ensureCalendarEventDocument(event)
        selectedDocumentOwner.value = DocumentOwner.calendarEvent(event.id)
        selectedNoteId.value = null
        navigateTo(Destination.NoteEditor)
    }

    fun createTaskAndOpen() = viewModelScope.launch {
        val id = repository.addTask("New task", assignee = state.value.settings.syncUserName.ifBlank { "@owner" })
        draftTaskIds += id
        persistDraftIds()
        navigateTo(Destination.Tasks)
    }

    /**
     * Creates a draft task and hands it back for immediate editing — "new task" opens the
     * full task editor directly instead of a separate creation form. Untouched drafts are
     * swept by the existing draft-recovery cleanup, or discarded on close via
     * [discardTaskDraftIfUntouched].
     */
    fun createTaskDraft(column: TaskColumnItem? = null, onCreated: (TaskItem) -> Unit) = viewModelScope.launch {
        val id = if (column != null) {
            repository.addTaskToColumn("New task", column)
        } else {
            repository.addTask("New task", assignee = state.value.settings.syncUserName.ifBlank { "@owner" })
        }
        draftTaskIds += id
        persistDraftIds()
        repository.taskById(id)?.let(onCreated)
    }

    /** Deletes a just-created draft the user closed without touching. Never touches real tasks. */
    fun discardTaskDraftIfUntouched(task: TaskItem) = viewModelScope.launch {
        if (task.id !in draftTaskIds) return@launch
        val current = repository.taskById(task.id) ?: return@launch
        if (current.title == "New task" && current.description.isBlank() && current.attachmentUri == null) {
            repository.deleteTask(current)
            draftTaskIds.remove(task.id)
            persistDraftIds()
        }
    }

    fun updateNote(note: Note, title: String, body: String) = viewModelScope.launch {
        repository.updateNote(note, title, body)
    }

    fun updateNoteDocument(note: Note, title: String, document: BlockDocument, dirtyBlockIds: Set<String>) = viewModelScope.launch {
        repository.updateNote(note, title, document, dirtyBlockIds)
    }

    fun updateActiveDocument(owner: DocumentOwner, title: String, document: BlockDocument, dirtyBlockIds: Set<String>): Job {
        val job = viewModelScope.launch {
            when (owner.type) {
                DocumentOwnerType.Note -> state.value.notes.firstOrNull { it.id == owner.id }
                    ?.let { note ->
                        repository.updateNote(note, title, document, dirtyBlockIds)
                        if (note.overlapMode == DocOverlapMode.Bounded) {
                            viewModelScope.launch {
                                runCatching { publishSharedDocument(owner.id, dirtyBlockIds) }
                                    .onFailure {
                                        if (it is CancellationException) throw it
                                        _documentPresenceStatus.value = if (networkOnline.value) "Saved locally · document sync failed" else "Offline · document saved locally"
                                    }
                            }
                        }
                    }
                DocumentOwnerType.Task -> state.value.tasks.firstOrNull { it.id == owner.id }
                    ?.let { repository.updateTaskDocument(it, title, document, dirtyBlockIds) }
                DocumentOwnerType.CalendarEvent -> state.value.calendarEvents.firstOrNull { it.id == owner.id }
                    ?.let { repository.updateCalendarEventDocument(it, title, document, dirtyBlockIds) }
            }
        }
        // Tracked so finalizeDraftDiscard can wait for every in-flight editor save.
        editorSaveJobs += job
        job.invokeOnCompletion { editorSaveJobs -= job }
        return job
    }

    fun updateActiveDocumentLayout(
        owner: DocumentOwner,
        document: BlockDocument,
        mode: DocOverlapMode,
        layout: Map<String, FreeformPlacement>,
        canvasSpec: DocCanvasSpec,
    ) = viewModelScope.launch {
        repository.updateOwnedDocumentLayout(owner, document, mode, layout, canvasSpec)
        if (owner.type == DocumentOwnerType.Note && mode == DocOverlapMode.Bounded) {
            runCatching { publishSharedDocument(owner.id, emptySet()) }
                .onFailure {
                    if (it is CancellationException) throw it
                    _documentPresenceStatus.value = if (networkOnline.value) "Saved locally · layout sync failed" else "Offline · layout saved locally"
                }
        }
    }

    fun togglePin(note: Note) = viewModelScope.launch { repository.setPinned(note) }
    fun toggleStar(note: Note) = viewModelScope.launch { repository.setStarred(note) }
    fun toggleLock(note: Note) = viewModelScope.launch { repository.setLocked(note) }

    fun setOverlapMode(note: Note, mode: DocOverlapMode) = viewModelScope.launch { repository.setOverlapMode(note, mode) }

    fun setCanvasSpec(note: Note, canvasSpec: DocCanvasSpec) = viewModelScope.launch {
        repository.setCanvasSpec(note, canvasSpec)
    }

    fun updateCanvasLayout(
        note: Note,
        layout: Map<String, FreeformPlacement>,
        canvasSpec: DocCanvasSpec,
    ) = viewModelScope.launch { repository.setCanvasLayout(note, layout, canvasSpec) }

    fun updateBlockPlacement(note: Note, blockId: String, placement: FreeformPlacement) = viewModelScope.launch {
        repository.setFreeformLayout(note, note.freeformLayout + (blockId to placement))
    }

    fun updateFreeformLayout(note: Note, layout: Map<String, FreeformPlacement>) = viewModelScope.launch {
        repository.setFreeformLayout(note, layout)
    }

    fun bringBlockToFront(note: Note, blockId: String) = viewModelScope.launch {
        repository.setFreeformLayout(note, DocLayerOrder.bringToFront(note.freeformLayout, blockId))
    }

    fun bringBlockForward(note: Note, blockId: String) = viewModelScope.launch {
        repository.setFreeformLayout(note, DocLayerOrder.bringForward(note.freeformLayout, blockId))
    }

    fun sendBlockBackward(note: Note, blockId: String) = viewModelScope.launch {
        repository.setFreeformLayout(note, DocLayerOrder.sendBackward(note.freeformLayout, blockId))
    }

    fun sendBlockToBack(note: Note, blockId: String) = viewModelScope.launch {
        repository.setFreeformLayout(note, DocLayerOrder.sendToBack(note.freeformLayout, blockId))
    }

    /** Sidebar ToC → editor scroll. The serial retriggers the editor even for repeated same-heading taps. */
    fun scrollToBlock(blockId: String) {
        _scrollToBlockRequest.value = ScrollToBlockRequest(blockId, ++scrollToBlockSerial)
    }

    fun consumeScrollToBlock() {
        _scrollToBlockRequest.value = null
    }

    /** Sidebar ToC → editor section mutation (delete / duplicate / reorder), applied to the live session. */
    fun requestSectionAction(headingId: String, action: DocSectionAction) {
        _sectionActionRequest.value = SectionActionRequest(headingId, action, ++sectionActionSerial)
    }

    fun consumeSectionAction() {
        _sectionActionRequest.value = null
    }

    fun requestBlockRestore(entry: WorkspaceObjectHistory) {
        val blockId = entry.blockId ?: return
        _blockRestoreRequest.value = BlockRestoreRequest(blockId, entry.beforeValue.takeIf(String::isNotBlank), ++blockRestoreSerial)
    }

    fun consumeBlockRestore() {
        _blockRestoreRequest.value = null
    }

    fun archive(note: Note) = viewModelScope.launch {
        repository.setArchived(note, true)
        val action = registerFeedbackResolution(
            onAction = {
                repository.setArchived(note, false)
                emitSuccess("Doc restored")
            },
        )
        emitSuccess("Doc archived", "Undo", action)
    }

    fun delete(note: Note) {
        if (note.id in pendingNoteDeleteIds.value) return
        pendingNoteDeleteIds.value = pendingNoteDeleteIds.value + note.id
        val action = registerFeedbackResolution(
            onAction = {
                pendingNoteDeleteIds.value = pendingNoteDeleteIds.value - note.id
                emitSuccess("Doc restored")
            },
            onDismiss = {
                try {
                    repository.deleteNote(note)
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: Throwable) {
                    emitError("Couldn't delete the Doc. It was restored so you can try again.")
                } finally {
                    pendingNoteDeleteIds.value = pendingNoteDeleteIds.value - note.id
                }
            },
        )
        emitSuccess("Doc deleted", "Undo", action)
    }

    fun addNotebook(name: String, scope: String = "notes", iconId: String = "app.folder") = viewModelScope.launch {
        repository.addNotebook(name, scope, iconId)
        emitSuccess(if (scope == "documents") "Document folder created" else "Notes folder created")
    }

    fun updateNotebook(notebook: Notebook, name: String, iconId: String) = viewModelScope.launch {
        repository.updateNotebook(notebook, name, iconId)
        emitSuccess("Folder updated")
    }
    fun addTag(name: String) = viewModelScope.launch { repository.addTag(name) }

    fun renameTag(tag: Tag, name: String) = viewModelScope.launch {
        runCatching { repository.renameTag(tag, name) }
            .onSuccess { emitSuccess("Tag renamed") }
            .onFailure { emitError("Couldn't rename the tag. Check the name and try again.") }
    }

    fun deleteTag(tag: Tag) = viewModelScope.launch {
        repository.deleteTag(tag)
        if (selectedTagFilterId.value == tag.id) selectedTagFilterId.value = null
        emitSuccess("Tag deleted and removed from notes")
    }

    fun setNoteTags(note: Note, names: List<String>) = viewModelScope.launch {
        repository.setNoteTags(note, names)
        emitSuccess(if (names.isEmpty()) "Tags removed" else "Tags updated")
    }
    fun addTaskTag(boardId: Long, name: String) = viewModelScope.launch { repository.addTaskTag(boardId, name) }
    fun addTask(title: String, assignee: String = state.value.settings.syncUserName) = viewModelScope.launch { repository.addTask(title, assignee = assignee.ifBlank { "@owner" }) }
    fun addTaskToStatus(title: String, status: TaskStatus) = viewModelScope.launch {
        repository.addTask(
            title = title.trim().ifBlank { "New task" },
            assignee = state.value.settings.syncUserName.ifBlank { "@owner" },
            status = status,
        )
    }
    fun addTaskToColumn(title: String, column: TaskColumnItem) = viewModelScope.launch {
        repository.addTaskToColumn(title.trim().ifBlank { "New task" }, column)
    }
    fun createTaskBoard(name: String) = viewModelScope.launch {
        repository.createTaskBoard(name)
        emitSuccess("Board created")
    }
    fun renameTaskBoard(boardId: Long, name: String) = viewModelScope.launch {
        repository.renameTaskBoard(boardId, name)
        emitSuccess("Board renamed")
    }
    fun createTaskColumn(boardId: Long, name: String) = viewModelScope.launch {
        repository.createTaskColumn(boardId, name)
        emitSuccess("Column added")
    }
    fun renameTaskColumn(column: TaskColumnItem, name: String) = viewModelScope.launch {
        repository.renameTaskColumn(column, name)
        emitSuccess("Column renamed")
    }
    fun moveTaskColumn(column: TaskColumnItem, delta: Int) = viewModelScope.launch {
        repository.moveTaskColumn(column, delta)
    }
    fun deleteTaskColumn(column: TaskColumnItem) = viewModelScope.launch {
        repository.deleteTaskColumn(column)
        emitSuccess("Column deleted")
    }
    fun moveTask(task: TaskItem, status: TaskStatus) = viewModelScope.launch { repository.setTaskStatus(task, status) }
    fun moveTaskToColumn(task: TaskItem, column: TaskColumnItem) = viewModelScope.launch { repository.moveTaskToColumn(task, column) }
    fun moveTaskToColumnAt(task: TaskItem, column: TaskColumnItem, sortOrder: Int) = viewModelScope.launch {
        repository.moveTaskToColumnAt(task, column, sortOrder)
    }
    fun moveTaskToColumnAtIndex(taskId: Long, targetColumnId: Long, targetIndex: Int) = viewModelScope.launch {
        repository.moveTaskToColumnAtIndex(taskId, targetColumnId, targetIndex)
    }
    fun updateTaskOrder(task: TaskItem, sortOrder: Int) = viewModelScope.launch {
        repository.updateTaskOrder(task, sortOrder)
    }
    fun updateTaskMeta(task: TaskItem, priority: TaskPriority, dueAt: Long?, assignee: String) = viewModelScope.launch {
        repository.updateTaskMeta(task, priority, dueAt, assignee.ifBlank { "@owner" })
    }
    fun updateTaskDetails(task: TaskItem, title: String, description: String, status: TaskStatus, priority: TaskPriority, dueAt: Long?, assignee: String, labels: String) = viewModelScope.launch {
        repository.updateTaskDetails(task, title, description, status, priority, dueAt, assignee, labels)
        emitSuccess("Task updated")
    }
    fun createTaskProperty(boardId: Long, name: String, type: TaskPropertyType) = viewModelScope.launch {
        repository.createTaskProperty(boardId, name, type)
        emitSuccess("Property added")
    }
    fun updateTaskPropertyDefinition(property: TaskPropertyDefinition, name: String, type: TaskPropertyType, hiddenWhenEmpty: Boolean, optionsJson: String = property.optionsJson) = viewModelScope.launch {
        repository.updateTaskPropertyDefinition(property, name, type, hiddenWhenEmpty, optionsJson)
        emitSuccess("Property updated")
    }
    fun duplicateTaskProperty(property: TaskPropertyDefinition) = viewModelScope.launch {
        repository.duplicateTaskProperty(property)
        emitSuccess("Property duplicated")
    }
    fun deleteTaskProperty(property: TaskPropertyDefinition) = viewModelScope.launch {
        repository.deleteTaskProperty(property)
        emitSuccess("Property deleted")
    }
    fun reorderTaskProperty(property: TaskPropertyDefinition, delta: Int) = viewModelScope.launch {
        repository.reorderTaskProperty(property, delta)
    }
    fun reorderTaskPropertyToIndex(property: TaskPropertyDefinition, targetIndex: Int) = viewModelScope.launch {
        repository.reorderTaskPropertyToIndex(property, targetIndex)
    }
    fun setTaskPropertyValue(task: TaskItem, property: TaskPropertyDefinition, value: String) = viewModelScope.launch {
        repository.setTaskPropertyValue(task, property, value)
    }
    fun setTaskColor(task: TaskItem, colorArgb: Long?) = viewModelScope.launch {
        repository.setTaskColor(task, colorArgb)
    }
    fun addChecklistItem(task: TaskItem, property: TaskPropertyDefinition, text: String) = viewModelScope.launch {
        repository.addChecklistItem(task, property, text)
    }
    fun updateChecklistItem(item: TaskChecklistItem, text: String, checked: Boolean) = viewModelScope.launch {
        repository.updateChecklistItem(item, text, checked)
    }
    fun moveChecklistItem(item: TaskChecklistItem, delta: Int) = viewModelScope.launch {
        repository.moveChecklistItem(item, delta)
    }
    fun moveChecklistItemToIndex(item: TaskChecklistItem, targetIndex: Int) = viewModelScope.launch {
        repository.moveChecklistItemToIndex(item, targetIndex)
    }
    fun deleteChecklistItem(item: TaskChecklistItem) = viewModelScope.launch {
        repository.deleteChecklistItem(item)
    }
    fun attachFileToTask(task: TaskItem, uri: Uri?) {
        if (uri == null) return
        persistReadPermission(uri)
        viewModelScope.launch {
            runCatching {
                repository.updateTaskAttachment(
                    task = task,
                    name = displayName(uri),
                    mimeType = app.contentResolver.getType(uri) ?: "application/octet-stream",
                    uri = uri.toString(),
                    sizeBytes = displaySize(uri),
                )
                "Task attachment linked"
            }.onSuccess(::emitSuccess)
                .onFailure { emitError("Couldn't attach that file to the task. Choose it again and retry.") }
        }
    }
    fun clearTaskAttachment(task: TaskItem) = viewModelScope.launch {
        repository.updateTaskAttachment(task, null, null, null, null)
        emitSuccess("Task attachment removed")
    }
    fun removeTaskFile(task: TaskItem, file: WorkspaceFileItem) = viewModelScope.launch {
        repository.removeTaskFile(task, file)
    }
    fun deleteTask(task: TaskItem) {
        if (task.id in pendingTaskDeleteIds.value) return
        pendingTaskDeleteIds.value = pendingTaskDeleteIds.value + task.id
        val action = registerFeedbackResolution(
            onAction = {
                pendingTaskDeleteIds.value = pendingTaskDeleteIds.value - task.id
                emitSuccess("Task restored")
            },
            onDismiss = {
                try {
                    repository.deleteTask(task)
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: Throwable) {
                    emitError("Couldn't delete the task. It was restored so you can try again.")
                } finally {
                    pendingTaskDeleteIds.value = pendingTaskDeleteIds.value - task.id
                }
            },
        )
        emitSuccess("Task deleted", "Undo", action)
    }
    fun sendChat(body: String, replyTo: ChatMessageItem? = null, targetChannelId: Long? = null) = viewModelScope.launch {
        if (body.isBlank()) return@launch
        val settings = state.value.settings
        val createdAt = System.currentTimeMillis()
        val channelId = targetChannelId ?: activeChatChannelId.value
        val activeChannel = channelId?.let { id -> state.value.chatChannels.firstOrNull { it.id == id } }
        val expiresAt = activeChannel?.defaultLifespanSeconds?.let { seconds ->
            createdAt + seconds * 1_000L
        }
        val localId = repository.sendChat(
            username = settings.syncUserName.ifBlank { "you" },
            displayName = settings.syncPublicName.ifBlank { settings.syncUserName.ifBlank { "You" } },
            body = body.trim(),
            color = settings.accentColor,
            createdAt = createdAt,
            channelId = channelId,
            replyPreview = replyTo?.let { "${it.authorDisplayName}: ${it.body.ifBlank { it.attachmentName.orEmpty() }.take(120)}" },
            expiresAt = expiresAt,
        )
        activeChannel?.takeIf { it.kind == ChatChannelKind.Dm }
            ?.let { MutualChatContacts.markMutual(app, it.counterpartUsername) }

        // Account transport (DMs + personal groups): the conversation carries an
        // accountConversationId, so it routes through NorfoldSocial rather than the
        // workspace team-chat path. The same syncId is reused on the optimistic local
        // row and the remote insert (server dedupes on conversation_id + client_sync_id).
        if (activeChannel?.scope == ChatScope.Account) {
            val conversationId = activeChannel.accountConversationId ?: return@launch
            val syncId = UUID.randomUUID().toString()
            repository.markChatMessagePendingRemote(settings.activeWorkspaceId, localId, syncId)
            val outboxOperation = SecureChatOutboxEntity(
                id = syncId,
                workspaceId = settings.activeWorkspaceId,
                conversationId = conversationId,
                operation = "text",
                body = body.trim(),
                createdAt = createdAt,
                replyPreview = replyTo?.let {
                    "${it.authorDisplayName}: ${it.body.ifBlank { it.attachmentName.orEmpty() }.take(120)}"
                },
                expiresAt = expiresAt,
            )
            repository.enqueueSecureChatOperation(outboxOperation)
            if (!NorfoldSupabase.isSignedIn()) {
                notifySignedOutChatSend()
                return@launch
            }
            runCatching {
                secureChatBackend.sendText(
                    conversationId = conversationId,
                    clientSyncId = syncId,
                    body = body.trim(),
                    createdAt = createdAt,
                    replyPreview = replyTo?.let {
                        "${it.authorDisplayName}: ${it.body.ifBlank { it.attachmentName.orEmpty() }.take(120)}"
                    },
                    expiresAt = expiresAt,
                )
            }.onSuccess { sent ->
                repository.completeSecureChatOperation(syncId)
                repository.bindAccountDirectMessage(localId, syncId)
                repository.setChatMessageReceiptStatus(localId, ChatReceiptStatus.Sent)
                repository.setChatMessageSecurity(
                    localId,
                    ChatSecurityMode.EndToEndEncrypted,
                    sent.securityEpochId,
                )
                repository.setChatMessageOriginPayload(localId, sent.originPayloadJson)
                _teamCollaborationStatus.value = "Direct · encrypted"
            }.onFailure {
                repository.failSecureChatOperation(outboxOperation, it)
                _teamCollaborationStatus.value = if (networkOnline.value) "Saved locally · direct sync failed" else "Offline · message saved locally"
                emitError("Message saved locally, but direct sync couldn't finish. It'll retry.")
            }
            return@launch
        }

        val remoteWorkspaceId = repository.remoteWorkspaceId(settings.activeWorkspaceId) ?: return@launch
        val syncId = UUID.randomUUID().toString()
        repository.markChatMessagePendingRemote(settings.activeWorkspaceId, localId, syncId)
        val outboxOperation = SecureChatOutboxEntity(
            id = syncId,
            workspaceId = settings.activeWorkspaceId,
            conversationId = activeChannel?.syncId ?: remoteWorkspaceId,
            remoteWorkspaceId = remoteWorkspaceId,
            channelSyncId = activeChannel?.syncId,
            operation = "text",
            body = body.trim(),
            createdAt = createdAt,
            replyPreview = replyTo?.let { "${it.authorDisplayName}: ${it.body.ifBlank { it.attachmentName.orEmpty() }.take(120)}" },
            expiresAt = expiresAt,
        )
        repository.enqueueSecureChatOperation(outboxOperation)
        if (!NorfoldSupabase.isSignedIn()) {
            notifySignedOutChatSend()
            return@launch
        }
        runCatching {
            drainWorkspaceChatOperation(outboxOperation)
            repository.completeSecureChatOperation(syncId)
        }.onSuccess {
            _teamCollaborationStatus.value = when (_workspaceChatSecurity.value?.securityMode) {
                ChatSecurityMode.EndToEndEncrypted -> "Team workspace · end-to-end encrypted"
                ChatSecurityMode.Managed -> "Team workspace · managed"
                null -> "Team workspace · connected"
            }
            retryPendingTeamChatSends()
        }.onFailure {
            repository.failSecureChatOperation(outboxOperation, it)
            _teamCollaborationStatus.value = when {
                it.message?.contains("acknowledge", ignoreCase = true) == true ->
                    "Review workspace security before sending"
                networkOnline.value -> "Saved locally · secure team sync paused"
                else -> "Offline · message saved locally"
            }
            emitError(
                if (it.message?.contains("acknowledge", ignoreCase = true) == true) {
                    "Review and acknowledge this workspace's security details. Your message is saved locally."
                } else {
                    "Message saved locally, but secure team sync couldn't finish. It'll retry."
                },
            )
        }
    }

    /**
     * Forward a message's text into another channel. Attachments aren't re-uploaded in this pass —
     * a media-bearing message forwards its caption, or a "[shared <file>]" note when caption-less.
     */
    fun forwardChatMessage(message: ChatMessageItem, targetChannelId: Long) {
        val text = message.body.ifBlank { message.attachmentName?.let { "[shared $it]" }.orEmpty() }
        if (text.isBlank()) return
        sendChat(text, targetChannelId = targetChannelId)
    }

    fun editChatMessage(message: ChatMessageItem, newBody: String) = viewModelScope.launch {
        if (!message.authorUsername.equals(state.value.settings.syncUserName.ifBlank { "you" }, true)) return@launch
        val clean = newBody.trim()
        if (clean.isBlank()) return@launch
        val edited = message.copy(body = clean, editedAt = System.currentTimeMillis())
        repository.editChatMessage(message, clean)
        syncChatMutation(edited, "edit")
    }

    fun toggleChatReaction(message: ChatMessageItem, emoji: String) = viewModelScope.launch {
        val username = state.value.settings.syncUserName.ifBlank { "you" }
        repository.toggleChatReaction(message, emoji, username)
        val root = runCatching { JSONObject(message.reactionsJson) }.getOrElse { JSONObject() }
        val array = root.optJSONArray(emoji) ?: JSONArray()
        val users = (0 until array.length()).map { array.optString(it) }.filter(String::isNotBlank).toMutableList()
        if (!users.remove(username)) users += username
        if (users.isEmpty()) root.remove(emoji) else root.put(emoji, JSONArray(users))
        syncChatMutation(message.copy(reactionsJson = root.toString()), "reactions")
    }

    fun setChatMessagePinned(message: ChatMessageItem, pinned: Boolean) = viewModelScope.launch {
        repository.setChatMessagePinned(message.id, pinned)
        syncChatMutation(message.copy(pinned = pinned), "pin")
    }

    fun setChatMessageStarred(message: ChatMessageItem, starred: Boolean) = viewModelScope.launch {
        repository.setChatMessageStarred(message.id, starred)
        emitInfo(if (starred) "Saved to Starred." else "Removed from Starred.")
    }

    private suspend fun syncChatMutation(message: ChatMessageItem, mutationType: String) {
        if (!NorfoldSupabase.isSignedIn()) return
        val settings = state.value.settings
        val binding = repository.chatMessageRemoteBinding(settings.activeWorkspaceId, message.id) ?: return
        val channel = message.channelId?.let { repository.chatChannelById(it) }
        if (channel?.scope == ChatScope.Account) {
            val conversationId = channel.accountConversationId ?: return
            val operationId = UUID.randomUUID().toString()
            val outboxOperation = SecureChatOutboxEntity(
                id = operationId,
                workspaceId = settings.activeWorkspaceId,
                conversationId = conversationId,
                operation = mutationType,
                targetMessageSyncId = binding.syncId,
                body = message.body,
                createdAt = System.currentTimeMillis(),
                editedAt = message.editedAt,
                reactionsJson = message.reactionsJson,
                pinned = message.pinned,
            )
            repository.enqueueSecureChatOperation(outboxOperation)
            runCatching {
                secureChatBackend.sendMutation(
                    conversationId = conversationId,
                    clientSyncId = operationId,
                    targetMessageId = binding.syncId,
                    type = mutationType,
                    body = message.body,
                    editedAt = message.editedAt,
                    reactionsJson = message.reactionsJson,
                    pinned = message.pinned,
                )
            }.onSuccess { repository.completeSecureChatOperation(operationId) }
                .onFailure {
                    repository.failSecureChatOperation(outboxOperation, it)
                    emitError("Saved here, but the encrypted update will retry after reconnection.")
                }
            return
        }
        val remoteWorkspaceId = repository.remoteWorkspaceId(settings.activeWorkspaceId) ?: return
        val operationId = UUID.randomUUID().toString()
        val outboxOperation = SecureChatOutboxEntity(
            id = operationId,
            workspaceId = settings.activeWorkspaceId,
            conversationId = channel?.syncId ?: remoteWorkspaceId,
            remoteWorkspaceId = remoteWorkspaceId,
            channelSyncId = channel?.syncId,
            operation = mutationType,
            targetMessageSyncId = binding.syncId,
            body = message.body,
            createdAt = System.currentTimeMillis(),
            editedAt = message.editedAt,
            reactionsJson = message.reactionsJson,
            pinned = message.pinned,
        )
        repository.enqueueSecureChatOperation(outboxOperation)
        runCatching {
            drainWorkspaceChatOperation(outboxOperation)
            repository.completeSecureChatOperation(operationId)
        }.onFailure {
            repository.failSecureChatOperation(outboxOperation, it)
            emitError("Saved here, but the secure team update will retry after reconnection.")
        }
    }

    /**
     * "Delete for me": removes only this device's copy. The remote binding survives so the
     * catch-up fetch can't resurrect the message here; nothing changes for other members.
     */
    fun deleteChatMessageForMe(messageId: Long) = viewModelScope.launch {
        val message = repository.chatMessageById(messageId) ?: return@launch
        val binding = repository.chatMessageRemoteBinding(message.workspaceId, messageId)
        val syncId = binding?.syncId
        val epochId = message.securityEpochId
        val accountId = NorfoldSupabase.currentAccountId()
        val channel = message.channelId?.let { repository.chatChannelById(it) }
        val conversationId = when (channel?.scope) {
            ChatScope.Account -> channel.accountConversationId
            ChatScope.Workspace -> channel.syncId
            null -> repository.remoteWorkspaceId(message.workspaceId)
        }
        val snapshot = repository.deleteChatMessageForMe(messageId) ?: return@launch
        val hiddenAt = System.currentTimeMillis()
        if (accountId != null && syncId != null && epochId != null && conversationId != null) {
            repository.recordSecureChatUserHidden(
                SecureChatUserHiddenEntity(
                    accountOwnerId = accountId,
                    messageSyncId = syncId,
                    conversationId = conversationId,
                    securityEpochId = epochId,
                    hiddenAt = hiddenAt,
                ),
            )
            runCatching { secureChatBackend.hideForCurrentUser(syncId, epochId, hiddenAt) }
                .onFailure { Log.w("NorfoldSecureChat", "Private deletion will retry through archive sync", it) }
        }
        val undo = registerFeedbackResolution(
            fallbackDelayMs = 5_000L,
            onAction = {
                repository.restoreChatMessageForUndo(snapshot)
                if (accountId != null && syncId != null) {
                    repository.removeSecureChatUserHidden(accountId, syncId)
                    if (NorfoldSupabase.isSignedIn()) {
                        runCatching { secureChatBackend.unhideForCurrentUser(syncId) }
                    }
                }
                emitSuccess("Message restored")
            },
        )
        emitSuccess("Message deleted", "Undo", undo)
    }

    /** Whether the unsend action should be offered for this message right now. */
    fun canUnsendChatMessage(message: ChatMessageItem): Boolean =
        !message.system &&
            message.authorUsername.equals(state.value.settings.syncUserName.ifBlank { "you" }, ignoreCase = true) &&
            System.currentTimeMillis() - message.createdAt <= UnsendWindowMs

    /**
     * Unsend: removes the message for everyone. Only the author's own messages within
     * [UnsendWindowMs] qualify — the sheet hides the action outside that window, and this
     * re-checks so a stale sheet can't slip through. Local removal is immediate; the remote
     * tombstone is best-effort (a failure leaves other devices showing the message).
     */
    fun unsendChatMessage(message: ChatMessageItem) = viewModelScope.launch {
        val settings = state.value.settings
        val username = settings.syncUserName.ifBlank { "you" }
        if (!message.authorUsername.equals(username, ignoreCase = true)) return@launch
        if (System.currentTimeMillis() - message.createdAt > UnsendWindowMs) {
            emitError("Messages can only be unsent within an hour of sending.")
            return@launch
        }
        val syncId = repository.chatMessageSyncId(settings.activeWorkspaceId, message.id)
        repository.deleteChatMessageForMe(message.id)
        if (syncId == null || !NorfoldSupabase.isSignedIn()) return@launch
        val channel = message.channelId?.let { repository.chatChannelById(it) }
        if (channel?.scope == ChatScope.Account) {
            val conversationId = channel.accountConversationId ?: return@launch
            val operationId = UUID.randomUUID().toString()
            val outboxOperation = SecureChatOutboxEntity(
                id = operationId,
                workspaceId = settings.activeWorkspaceId,
                conversationId = conversationId,
                operation = "delete_everyone",
                targetMessageSyncId = syncId,
                createdAt = System.currentTimeMillis(),
            )
            repository.enqueueSecureChatOperation(outboxOperation)
            runCatching {
                secureChatBackend.sendMutation(
                    conversationId = conversationId,
                    clientSyncId = operationId,
                    targetMessageId = syncId,
                    type = "delete_everyone",
                )
            }.onSuccess {
                repository.completeSecureChatOperation(operationId)
                repository.recordSecureChatTombstone(syncId, conversationId, "delete_everyone")
            }.onFailure {
                if (it is CancellationException) throw it
                repository.failSecureChatOperation(outboxOperation, it)
                emitError("Removed here, but the encrypted unsend couldn't reach other devices yet.")
            }
            return@launch
        }
        val remoteWorkspaceId = repository.remoteWorkspaceId(settings.activeWorkspaceId) ?: return@launch
        val operationId = UUID.randomUUID().toString()
        val outboxOperation = SecureChatOutboxEntity(
            id = operationId,
            workspaceId = settings.activeWorkspaceId,
            conversationId = channel?.syncId ?: remoteWorkspaceId,
            remoteWorkspaceId = remoteWorkspaceId,
            channelSyncId = channel?.syncId,
            operation = "delete_everyone",
            targetMessageSyncId = syncId,
            createdAt = System.currentTimeMillis(),
        )
        repository.enqueueSecureChatOperation(outboxOperation)
        runCatching {
            drainWorkspaceChatOperation(outboxOperation)
            repository.completeSecureChatOperation(operationId)
        }.onFailure {
            if (it is CancellationException) throw it
            repository.failSecureChatOperation(outboxOperation, it)
            emitError("Removed here, but the secure team unsend will retry after reconnection.")
        }
    }

    fun sendChatAttachment(uri: Uri?) {
        sendChatAttachments(listOfNotNull(uri))
    }

    /**
     * One message per picked file, saved to chat immediately (offline-first). Delivery to the
     * team happens through a single sequential upload worker, so picking hundreds of photos
     * can't fan out hundreds of concurrent reads/uploads (launch/01 chat spec §4).
     */
    fun sendChatAttachments(uris: List<Uri>) {
        if (uris.isEmpty()) return
        uris.forEach { persistReadPermission(it) }
        viewModelScope.launch {
            val settings = state.value.settings
            val channelId = activeChatChannelId.value
            val transportCheck = runCatching { managedAttachmentSecurity(channelId) }
            if (transportCheck.isFailure) {
                emitError(
                    transportCheck.exceptionOrNull()?.message
                        ?: "Attachments are unavailable for this conversation.",
                )
                return@launch
            }
            var oversized = 0
            for (uri in uris) {
                val name = displayName(uri)
                val size = displaySize(uri)
                // Cap check happens on provider metadata, before a single byte is read; the
                // streaming cap in the worker backstops providers that report 0 for SIZE.
                if (size > MaxTeamFileBytes) {
                    oversized += 1
                    continue
                }
                enqueueChatUpload(
                    settings = settings,
                    uriString = uri.toString(),
                    name = name,
                    mime = app.contentResolver.getType(uri) ?: "application/octet-stream",
                    size = size,
                    body = "",
                )
            }
            if (oversized > 0) {
                val counted = if (oversized == 1) "1 file wasn't sent" else "$oversized files weren't sent"
                emitError("$counted — files up to ${MaxTeamFileBytes / (1024 * 1024)} MB on the free plan.")
            }
            drainChatUploads()
        }
    }

    /**
     * Media E2EE is a separate phase. Until its ciphertext relay lands, refuse to
     * leak account/E2EE workspace files through the Managed object/storage path.
     */
    private suspend fun managedAttachmentSecurity(channelId: Long?): ChatBackend.WorkspaceSecuritySnapshot? {
        val channel = channelId?.let { repository.chatChannelById(it) }
        require(channel?.scope != ChatScope.Account) {
            "Encrypted personal attachments aren't available in this build yet. Nothing was uploaded."
        }
        val workspaceId = state.value.settings.activeWorkspaceId
        val remoteWorkspaceId = repository.remoteWorkspaceId(workspaceId) ?: return null
        require(NorfoldSupabase.isSignedIn()) {
            "Sign in before attaching a file to a shared workspace."
        }
        val security = ChatBackend.ensureWorkspaceSecurityEpoch(remoteWorkspaceId, channel?.syncId)
        require(security.securityMode == ChatSecurityMode.Managed) {
            "Encrypted workspace attachments aren't available in this build yet. Nothing was uploaded."
        }
        require(security.acknowledged) {
            "Review and acknowledge this workspace's security details before attaching files."
        }
        return security
    }

    private val _gifPicker = MutableStateFlow(GifPickerState())
    val gifPicker: StateFlow<GifPickerState> = _gifPicker.asStateFlow()
    private var gifSearchJob: Job? = null
    private var lastGifSendUrl: String? = null
    private var lastGifSendAt: Long = 0L

    fun searchChatGifs(query: String) {
        gifSearchJob?.cancel()
        _gifPicker.value = _gifPicker.value.copy(query = query, loading = true, error = null)
        gifSearchJob = viewModelScope.launch {
            delay(if (query.isBlank()) 0 else 300)
            runCatching { KlipyClient.search(query) }
                .onSuccess { _gifPicker.value = GifPickerState(query = query, results = it) }
                .onFailure {
                    if (it is CancellationException) throw it
                    _gifPicker.value = GifPickerState(
                        query = query,
                        error = if (KlipyClient.configured) "Couldn't load GIFs. Check the connection and try again." else "GIF search needs a KLIPY key in this build.",
                    )
                }
        }
    }

    fun sendChatGif(gif: KlipyGif) {
        val now = SystemClock.elapsedRealtime()
        if (gif.originalUrl == lastGifSendUrl && now - lastGifSendAt < 2_000L) return
        lastGifSendUrl = gif.originalUrl
        lastGifSendAt = now
        viewModelScope.launch {
            val settings = state.value.settings
            val channelId = activeChatChannelId.value
            val securityResult = runCatching { managedAttachmentSecurity(channelId) }
            if (securityResult.isFailure) {
                emitError(
                    securityResult.exceptionOrNull()?.message
                        ?: "GIFs are unavailable for this conversation.",
                )
                return@launch
            }
            val managedSecurity = securityResult.getOrThrow()
            val createdAt = System.currentTimeMillis()
            val username = settings.syncUserName.ifBlank { "you" }
            val displayName = settings.syncPublicName.ifBlank { username }
            val name = "${gif.title.ifBlank { "KLIPY GIF" }.sanitizeFileName()}.gif"
            val localId = repository.sendChat(
                username = username,
                displayName = displayName,
                body = "",
                color = settings.accentColor,
                attachmentName = name,
                attachmentMimeType = "image/gif",
                attachmentUri = gif.originalUrl,
                createdAt = createdAt,
                channelId = channelId,
            )
            // Provider content remains provider-hosted: do not proxy, cache, or re-upload it.
            if (NorfoldSupabase.isSignedIn()) {
                val remoteWorkspaceId = repository.remoteWorkspaceId(settings.activeWorkspaceId)
                if (remoteWorkspaceId != null && managedSecurity != null) {
                    val syncId = UUID.randomUUID().toString()
                    repository.markChatMessagePendingRemote(settings.activeWorkspaceId, localId, syncId)
                    runCatching {
                        ChatBackend.sendManagedWorkspaceMessage(
                            remoteWorkspaceId = remoteWorkspaceId,
                            deviceId = settings.settingsDeviceId,
                            username = username,
                            displayName = displayName,
                            body = "",
                            color = settings.accentColor,
                            createdAt = createdAt,
                            security = managedSecurity,
                            attachmentName = name,
                            attachmentMimeType = "image/gif",
                            attachmentExternalUrl = gif.originalUrl,
                            syncId = syncId,
                            idempotencyKey = syncId,
                            channelSyncId = channelId?.let { id -> state.value.chatChannels.firstOrNull { it.id == id }?.syncId },
                        )
                    }.onSuccess { repository.bindRemoteChatMessage(settings.activeWorkspaceId, localId, it) }
                        .onFailure { emitError("GIF saved here, but it didn't reach the team. Try again when you're online.") }
                }
            }
        }
    }

    /** Saves the local chat message for an attachment and queues its team delivery. */
    private suspend fun enqueueChatUpload(
        settings: AppSettings,
        uriString: String,
        name: String,
        mime: String,
        size: Long,
        body: String,
    ): Long {
        val createdAt = System.currentTimeMillis()
        val username = settings.syncUserName.ifBlank { "you" }
        val authorDisplayName = settings.syncPublicName.ifBlank { settings.syncUserName.ifBlank { "You" } }
        val channelId = activeChatChannelId.value
        val localId = repository.sendChat(
            username = username,
            displayName = authorDisplayName,
            body = body,
            color = settings.accentColor,
            attachmentName = name,
            attachmentMimeType = mime,
            attachmentUri = uriString,
            attachmentSizeBytes = size,
            createdAt = createdAt,
            channelId = channelId,
        )
        val channelSyncId = channelId?.let { id -> state.value.chatChannels.firstOrNull { it.id == id }?.syncId }
        val syncId = UUID.randomUUID().toString()
        if (repository.remoteWorkspaceId(settings.activeWorkspaceId) != null) {
            repository.markChatMessagePendingRemote(settings.activeWorkspaceId, localId, syncId)
        }
        chatUploadQueue.addLast(
            ChatUploadTask(
                localWorkspaceId = settings.activeWorkspaceId,
                localId = localId,
                uri = uriString,
                name = name,
                mime = mime,
                size = size,
                body = body,
                color = settings.accentColor,
                createdAt = createdAt,
                username = username,
                authorDisplayName = authorDisplayName,
                syncId = syncId,
                idempotencyKey = UUID.randomUUID().toString(),
                channelSyncId = channelSyncId,
            ),
        )
        persistChatUploadQueue()
        publishChatUploads()
        return localId
    }

    private val chatUploadQueue = ArrayDeque<ChatUploadTask>()
    private val chatUploadPrefs = app.getSharedPreferences("norfold_chat_uploads", 0)
    private val _chatUploads = MutableStateFlow<List<ChatUploadItem>>(emptyList())
    val chatUploads: StateFlow<List<ChatUploadItem>> = _chatUploads.asStateFlow()
    private var chatUploadWorker: Job? = null
    private var uploadingLocalId: Long? = null

    private fun publishChatUploads() {
        _chatUploads.value = chatUploadQueue.map { task ->
            ChatUploadItem(
                id = task.localId,
                displayName = task.name,
                sizeBytes = task.size,
                status = when {
                    task.failed -> ChatUploadStatus.Failed
                    task.localId == uploadingLocalId -> ChatUploadStatus.Uploading
                    else -> ChatUploadStatus.Queued
                },
            )
        }
    }

    // The queue mirrors to prefs so an app kill mid-batch resumes on next launch; picked uris
    // survive because persistReadPermission took a persistable grant at enqueue time.
    private fun persistChatUploadQueue() {
        val array = JSONArray()
        chatUploadQueue.forEach { task ->
            array.put(
                JSONObject()
                    .put("localWorkspaceId", task.localWorkspaceId)
                    .put("localId", task.localId)
                    .put("uri", task.uri)
                    .put("name", task.name)
                    .put("mime", task.mime)
                    .put("size", task.size)
                    .put("body", task.body)
                    .put("color", task.color)
                    .put("createdAt", task.createdAt)
                    .put("username", task.username)
                    .put("authorDisplayName", task.authorDisplayName)
                    .put("syncId", task.syncId)
                    .put("idempotencyKey", task.idempotencyKey)
                    .put("channelSyncId", task.channelSyncId),
            )
        }
        chatUploadPrefs.edit().putString("queue", array.toString()).apply()
    }

    private fun restoreChatUploadQueue() {
        val raw = chatUploadPrefs.getString("queue", null) ?: return
        // retryInitialization() can run this twice; rebuild rather than append.
        chatUploadQueue.clear()
        runCatching {
            val array = JSONArray(raw)
            for (i in 0 until array.length()) {
                val entry = array.getJSONObject(i)
                chatUploadQueue.addLast(
                    ChatUploadTask(
                        localWorkspaceId = entry.getLong("localWorkspaceId"),
                        localId = entry.getLong("localId"),
                        uri = entry.getString("uri"),
                        name = entry.getString("name"),
                        mime = entry.getString("mime"),
                        size = entry.getLong("size"),
                        body = entry.getString("body"),
                        color = entry.getLong("color"),
                        createdAt = entry.getLong("createdAt"),
                        username = entry.getString("username"),
                        authorDisplayName = entry.getString("authorDisplayName"),
                        syncId = entry.getString("syncId"),
                        idempotencyKey = entry.getString("idempotencyKey"),
                        channelSyncId = entry.optString("channelSyncId").takeIf { it.isNotBlank() && it != "null" },
                    ),
                )
            }
        }
        publishChatUploads()
    }

    fun retryFailedChatUploads() {
        chatUploadQueue.forEach { it.failed = false; it.attempts = 0 }
        publishChatUploads()
        drainChatUploads()
    }

    /**
     * Sequential delivery worker. Stops on the first transient failure (the network is likely
     * down for the rest of the queue too) and resumes on the next refresh/drain; the same
     * syncId/idempotencyKey make a re-send after a lost response upsert, not duplicate.
     */
    private fun drainChatUploads() {
        if (chatUploadWorker?.isActive == true) return
        chatUploadWorker = viewModelScope.launch {
            while (true) {
                val task = chatUploadQueue.firstOrNull { !it.failed } ?: break
                if (!NorfoldSupabase.isSignedIn()) {
                    notifySignedOutChatSend()
                    break
                }
                val remoteWorkspaceId = repository.remoteWorkspaceId(task.localWorkspaceId)
                if (remoteWorkspaceId == null) {
                    // Local-only workspace: the message already lives in chat; nothing to deliver.
                    chatUploadQueue.remove(task)
                    persistChatUploadQueue()
                    publishChatUploads()
                    continue
                }
                uploadingLocalId = task.localId
                publishChatUploads()
                val settings = state.value.settings
                val delivered = runCatching {
                    val security = ChatBackend.ensureWorkspaceSecurityEpoch(
                        workspaceId = remoteWorkspaceId,
                        channelId = task.channelSyncId,
                    )
                    require(security.securityMode == ChatSecurityMode.Managed) {
                        "Encrypted workspace attachments aren't available in this build yet. Nothing was uploaded."
                    }
                    require(security.acknowledged) {
                        "Review and acknowledge this workspace's security details before attaching files."
                    }
                    val rawBytes = withContext(Dispatchers.IO) {
                        openChatUploadInput(task.uri).use { it.readBytesCapped(MaxTeamFileBytes) }
                    }
                    val (bytes, mime) = compressImageForUpload(rawBytes, task.mime)
                    val storagePath = NorfoldSupabase.uploadWorkspaceFile(
                        remoteWorkspaceId = remoteWorkspaceId,
                        displayName = task.name,
                        mimeType = mime,
                        bytes = bytes,
                    )
                    val remote = ChatBackend.sendManagedWorkspaceMessage(
                        remoteWorkspaceId = remoteWorkspaceId,
                        deviceId = settings.settingsDeviceId,
                        username = task.username,
                        displayName = task.authorDisplayName,
                        body = task.body,
                        color = task.color,
                        createdAt = task.createdAt,
                        security = security,
                        syncId = task.syncId,
                        idempotencyKey = task.idempotencyKey,
                        attachmentName = task.name,
                        attachmentMimeType = mime,
                        attachmentSizeBytes = bytes.size.toLong(),
                        attachmentStoragePath = storagePath,
                        channelSyncId = task.channelSyncId,
                    )
                    repository.bindRemoteChatMessage(task.localWorkspaceId, task.localId, remote)
                }
                uploadingLocalId = null
                val failure = delivered.exceptionOrNull()
                when {
                    failure == null -> {
                        chatUploadQueue.remove(task)
                        _teamCollaborationStatus.value = "Team workspace · connected"
                    }
                    failure is CancellationException -> throw failure
                    failure is TeamUploadTooLargeException -> {
                        chatUploadQueue.remove(task)
                        emitError("\"${task.name}\" stays on this device — files up to ${MaxTeamFileBytes / (1024 * 1024)} MB on the free plan.")
                    }
                    else -> {
                        Log.e(
                            "NorfoldChatUpload",
                            "Team attachment delivery failed for ${task.mime} (${task.attempts + 1}/$MaxChatUploadAttempts)",
                            failure,
                        )
                        task.attempts += 1
                        if (task.attempts >= MaxChatUploadAttempts) {
                            task.failed = true
                            emitError("\"${task.name}\" didn't reach the team. Tap retry above the message box.")
                        }
                    }
                }
                persistChatUploadQueue()
                publishChatUploads()
                if (failure != null && failure !is TeamUploadTooLargeException && !task.failed) {
                    // Transient failure: leave the task at the head and stop; the next
                    // refresh/drain picks it back up instead of hammering a dead network.
                    _teamCollaborationStatus.value =
                        if (networkOnline.value) "Saved locally · team sync failed" else "Offline · message saved locally"
                    break
                }
            }
        }
    }

    private fun openChatUploadInput(uriString: String): InputStream {
        val uri = Uri.parse(uriString)
        return when (uri.scheme?.lowercase()) {
            "file" -> File(requireNotNull(uri.path)).inputStream()
            "http", "https" -> openAttachmentStream(uriString)
            else -> app.contentResolver.openInputStream(uri) ?: error("File is not readable")
        }
    }

    /** Recompress large images the way messengers do (~2 MB target); other types pass through. */
    private suspend fun compressImageForUpload(bytes: ByteArray, mime: String): Pair<ByteArray, String> =
        withContext(Dispatchers.Default) {
            val compressible = mime == "image/jpeg" || mime == "image/png" || mime == "image/webp"
            if (!compressible || bytes.size <= ImageRecompressThresholdBytes) return@withContext bytes to mime
            runCatching {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                var sample = 1
                while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MaxCompressedImageDimensionPx) sample *= 2
                val bitmap = BitmapFactory.decodeByteArray(
                    bytes,
                    0,
                    bytes.size,
                    BitmapFactory.Options().apply { inSampleSize = sample },
                ) ?: return@runCatching bytes to mime
                var quality = 85
                var out: ByteArray
                do {
                    val stream = ByteArrayOutputStream()
                    bitmap.compress(Bitmap.CompressFormat.JPEG, quality, stream)
                    out = stream.toByteArray()
                    quality -= 10
                } while (out.size > ImageRecompressThresholdBytes && quality >= 55)
                bitmap.recycle()
                // A already-optimized image can compress larger than the source; keep the original.
                if (out.size < bytes.size) out to "image/jpeg" else bytes to mime
            }.getOrDefault(bytes to mime)
        }

    // ---- Voice messages -------------------------------------------------------------------

    private var voiceRecorder: MediaRecorder? = null
    private var voiceRecordingFile: File? = null
    private var voiceRecordingTicker: Job? = null
    private var voicePausedAt: Long? = null
    private var voicePausedDurationMs: Long = 0L
    private val _voiceRecordingStartedAt = MutableStateFlow<Long?>(null)
    /** SystemClock.elapsedRealtime() at record start; null when idle. Drives the composer timer. */
    val voiceRecordingStartedAt: StateFlow<Long?> = _voiceRecordingStartedAt.asStateFlow()
    private val _voiceRecordingState = MutableStateFlow<VoiceRecordingState?>(null)
    val voiceRecordingState: StateFlow<VoiceRecordingState?> = _voiceRecordingState.asStateFlow()

    fun startVoiceRecording() {
        if (voiceRecorder != null) return
        runCatching {
            // filesDir, not cacheDir: the OS must never trim a voice note out of an old chat.
            val dir = File(app.filesDir, "voice-notes").apply { mkdirs() }
            val file = File(dir, "Voice-${System.currentTimeMillis()}.m4a")
            val recorder = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(app) else @Suppress("DEPRECATION") MediaRecorder()
            recorder.setAudioSource(MediaRecorder.AudioSource.MIC)
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            recorder.setAudioEncodingBitRate(96_000)
            recorder.setAudioSamplingRate(44_100)
            recorder.setOutputFile(file.absolutePath)
            recorder.prepare()
            recorder.start()
            voiceRecorder = recorder
            voiceRecordingFile = file
            val startedAt = SystemClock.elapsedRealtime()
            voicePausedAt = null
            voicePausedDurationMs = 0L
            _voiceRecordingStartedAt.value = startedAt
            _voiceRecordingState.value = VoiceRecordingState(startedAt = startedAt)
            voiceRecordingTicker?.cancel()
            voiceRecordingTicker = viewModelScope.launch {
                while (isActive && voiceRecorder != null) {
                    val current = _voiceRecordingState.value ?: break
                    val now = SystemClock.elapsedRealtime()
                    val activePausedMs = voicePausedAt?.let { now - it } ?: 0L
                    val elapsed = (now - current.startedAt - voicePausedDurationMs - activePausedMs).coerceAtLeast(0L)
                    val amplitude = if (current.paused) 0.05f else runCatching {
                        // MediaRecorder reports a 16-bit peak since the last query. A square-root
                        // curve keeps quiet speech visible without making normal speech clip.
                        kotlin.math.sqrt((voiceRecorder?.maxAmplitude ?: 0) / 32767f)
                    }.getOrDefault(0f).coerceIn(0.04f, 1f)
                    _voiceRecordingState.value = current.copy(
                        elapsedMs = elapsed,
                        amplitudes = (current.amplitudes + amplitude).takeLast(72),
                    )
                    delay(80)
                }
            }
        }.onFailure {
            stopVoiceRecorderQuietly()
            voiceRecordingFile?.delete()
            voiceRecordingFile = null
            emitError("Couldn't start recording. Check the microphone permission and try again.")
        }
    }

    fun cancelVoiceRecording() {
        stopVoiceRecorderQuietly()
        voiceRecordingFile?.delete()
        voiceRecordingFile = null
    }

    fun finishVoiceRecording() {
        val recording = _voiceRecordingState.value ?: return
        val tooShort = recording.elapsedMs < 1_000
        stopVoiceRecorderQuietly()
        val file = voiceRecordingFile ?: return
        voiceRecordingFile = null
        if (tooShort || file.length() <= 0L) {
            file.delete()
            if (tooShort) emitInfo("Hold on a moment longer to record a voice message.")
            return
        }
        viewModelScope.launch {
            enqueueChatUpload(
                settings = state.value.settings,
                uriString = Uri.fromFile(file).toString(),
                name = "Voice message",
                mime = "audio/mp4",
                size = file.length(),
                body = "",
            )
            drainChatUploads()
        }
    }

    fun toggleVoiceRecordingPause() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return
        val recorder = voiceRecorder ?: return
        val current = _voiceRecordingState.value ?: return
        runCatching {
            if (current.paused) {
                recorder.resume()
                voicePausedAt?.let { voicePausedDurationMs += SystemClock.elapsedRealtime() - it }
                voicePausedAt = null
            } else {
                recorder.pause()
                voicePausedAt = SystemClock.elapsedRealtime()
            }
            _voiceRecordingState.value = current.copy(paused = !current.paused)
        }.onFailure { emitError("Couldn't pause the recording. You can still send or discard it.") }
    }

    private fun stopVoiceRecorderQuietly() {
        voiceRecordingTicker?.cancel()
        voiceRecordingTicker = null
        runCatching { voiceRecorder?.stop() }
        runCatching { voiceRecorder?.release() }
        voiceRecorder = null
        _voiceRecordingStartedAt.value = null
        _voiceRecordingState.value = null
        voicePausedAt = null
        voicePausedDurationMs = 0L
    }

    // ---- Voice/attachment playback --------------------------------------------------------

    private var chatAudioPlayer: MediaPlayer? = null
    private var chatAudioTicker: Job? = null
    private val _chatAudio = MutableStateFlow<ChatAudioState?>(null)
    val chatAudio: StateFlow<ChatAudioState?> = _chatAudio.asStateFlow()

    fun toggleChatAudio(message: ChatMessageItem) {
        val current = _chatAudio.value
        val player = chatAudioPlayer
        if (player != null && current?.messageId == message.id) {
            if (current.playing) {
                runCatching { player.pause() }
                _chatAudio.value = current.copy(playing = false)
            } else {
                runCatching { player.start() }
                _chatAudio.value = current.copy(playing = true)
                startChatAudioTicker(message.id)
            }
            return
        }
        stopChatAudio()
        val uriString = message.attachmentUri ?: return
        viewModelScope.launch {
            runCatching {
                val source = resolveChatAttachmentSource(uriString) ?: error("Attachment unavailable")
                val mediaPlayer = MediaPlayer()
                withContext(Dispatchers.IO) {
                    if (source.startsWith("http")) mediaPlayer.setDataSource(source)
                    else mediaPlayer.setDataSource(app, Uri.parse(source))
                    mediaPlayer.prepare()
                }
                mediaPlayer.setOnCompletionListener { stopChatAudio() }
                chatAudioPlayer = mediaPlayer
                mediaPlayer.start()
                _chatAudio.value = ChatAudioState(message.id, true, mediaPlayer.duration, 0)
                startChatAudioTicker(message.id)
            }.onFailure {
                if (it is CancellationException) throw it
                stopChatAudio()
                emitError(
                    if (NorfoldSupabase.isSignedIn() || !uriString.startsWith("norfold-cloud://")) "Couldn't play that voice message."
                    else "Sign in to play voice messages from your team.",
                )
            }
        }
    }

    fun cycleChatAudioSpeed(message: ChatMessageItem) {
        val current = _chatAudio.value?.takeIf { it.messageId == message.id } ?: return
        val player = chatAudioPlayer ?: return
        val next = when (current.speed) {
            1f -> 1.5f
            1.5f -> 2f
            else -> 1f
        }
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                player.playbackParams = player.playbackParams.setSpeed(next)
            }
            _chatAudio.value = current.copy(speed = next)
        }.onFailure { emitError("Playback speed isn't supported for this recording.") }
    }

    fun stopChatAudio() {
        chatAudioTicker?.cancel()
        chatAudioTicker = null
        runCatching { chatAudioPlayer?.stop() }
        runCatching { chatAudioPlayer?.release() }
        chatAudioPlayer = null
        _chatAudio.value = null
    }

    private fun startChatAudioTicker(messageId: Long) {
        chatAudioTicker?.cancel()
        chatAudioTicker = viewModelScope.launch {
            while (true) {
                delay(250)
                val player = chatAudioPlayer ?: break
                val current = _chatAudio.value?.takeIf { it.messageId == messageId } ?: break
                if (!current.playing) continue
                _chatAudio.value = current.copy(positionMs = runCatching { player.currentPosition }.getOrDefault(current.positionMs))
            }
        }
    }

    /** Local uris play/open directly; norfold-cloud:// storage paths need a signed URL. */
    private suspend fun resolveChatAttachmentSource(uriString: String): String? = when {
        uriString.startsWith("norfold-cloud://") ->
            runCatching { NorfoldSupabase.workspaceFileUrl(uriString.removePrefix("norfold-cloud://")) }.getOrNull()
        else -> uriString
    }

    suspend fun chatAttachmentPreviewSource(message: ChatMessageItem): String? =
        message.attachmentUri?.let { resolveChatAttachmentSource(it) }

    private val _chatAttachmentPreview = MutableStateFlow<ChatAttachmentPreviewState?>(null)
    val chatAttachmentPreview: StateFlow<ChatAttachmentPreviewState?> = _chatAttachmentPreview.asStateFlow()

    fun openChatAttachment(message: ChatMessageItem) {
        val uriString = message.attachmentUri ?: return
        _chatAttachmentPreview.value = ChatAttachmentPreviewState(message = message)
        viewModelScope.launch {
            val source = resolveChatAttachmentSource(uriString)
            if (source == null) {
                _chatAttachmentPreview.value = ChatAttachmentPreviewState(
                    message = message,
                    loading = false,
                    error = "Sign in to open files shared by your team.",
                )
                return@launch
            }
            val pdfPage = if (message.attachmentMimeType == "application/pdf") {
                runCatching { renderPdfPage(materializeChatAttachment(source, message), 0) }.getOrNull()
            } else null
            _chatAttachmentPreview.value = ChatAttachmentPreviewState(
                message = message,
                source = source,
                loading = false,
                pdfPages = pdfPage?.first?.let { mapOf(0 to it) }.orEmpty(),
                pdfPageCount = pdfPage?.second ?: 0,
                error = if (message.attachmentMimeType == "application/pdf" && pdfPage == null) "Couldn't preview this PDF." else null,
            )
        }
    }

    fun dismissChatAttachmentPreview() {
        _chatAttachmentPreview.value = null
    }

    fun loadChatPdfPage(pageIndex: Int) {
        val current = _chatAttachmentPreview.value ?: return
        if (current.message.attachmentMimeType != "application/pdf" || current.source == null) return
        val nextIndex = pageIndex.coerceIn(0, (current.pdfPageCount - 1).coerceAtLeast(0))
        if (nextIndex in current.pdfPages) return
        viewModelScope.launch {
            runCatching {
                val file = materializeChatAttachment(current.source, current.message)
                renderPdfPage(file, nextIndex)
            }.onSuccess { (bitmap, count) ->
                _chatAttachmentPreview.value = _chatAttachmentPreview.value?.let { latest ->
                    latest.copy(pdfPages = latest.pdfPages + (nextIndex to bitmap), pdfPageCount = count, error = null)
                }
            }.onFailure {
                _chatAttachmentPreview.value = _chatAttachmentPreview.value?.copy(error = "Couldn't render PDF page ${nextIndex + 1}.")
            }
        }
    }

    fun saveChatAttachmentToDevice() {
        val preview = _chatAttachmentPreview.value ?: return
        val source = preview.source ?: return
        if (preview.loading) return
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val mime = preview.message.attachmentMimeType ?: "application/octet-stream"
                    val displayName = preview.message.attachmentName?.sanitizeFileName()
                        ?.takeIf(String::isNotBlank) ?: "Norfold-${System.currentTimeMillis()}"
                    val (collection, relativePath) = when {
                        mime.startsWith("image/") -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI to "${Environment.DIRECTORY_PICTURES}/Norfold"
                        mime.startsWith("video/") -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI to "${Environment.DIRECTORY_MOVIES}/Norfold"
                        mime.startsWith("audio/") -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI to "${Environment.DIRECTORY_MUSIC}/Norfold"
                        else -> (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            MediaStore.Downloads.EXTERNAL_CONTENT_URI
                        } else {
                            MediaStore.Files.getContentUri("external")
                        }) to "${Environment.DIRECTORY_DOWNLOADS}/Norfold"
                    }
                    val values = android.content.ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                        put(MediaStore.MediaColumns.MIME_TYPE, mime)
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
                            put(MediaStore.MediaColumns.IS_PENDING, 1)
                        }
                    }
                    val destination = app.contentResolver.insert(collection, values) ?: error("Storage is unavailable")
                    try {
                        openAttachmentStream(source).use { input ->
                            app.contentResolver.openOutputStream(destination, "w")?.use { output ->
                                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                                var copied = 0L
                                val total = preview.message.attachmentSizeBytes?.coerceAtLeast(1L)
                                while (true) {
                                    val count = input.read(buffer)
                                    if (count < 0) break
                                    output.write(buffer, 0, count)
                                    copied += count
                                    if (total != null) _chatAttachmentPreview.value = _chatAttachmentPreview.value?.copy(
                                        downloadProgress = (copied.toFloat() / total).coerceIn(0f, 1f),
                                    )
                                }
                            } ?: error("Couldn't write the selected file")
                        }
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            values.clear()
                            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                            app.contentResolver.update(destination, values, null, null)
                        }
                    } catch (failure: Throwable) {
                        app.contentResolver.delete(destination, null, null)
                        throw failure
                    }
                }
            }.onSuccess {
                _chatAttachmentPreview.value = _chatAttachmentPreview.value?.copy(downloadProgress = null)
                emitInfo("Saved to the Norfold media folder on this device.")
            }.onFailure {
                _chatAttachmentPreview.value = _chatAttachmentPreview.value?.copy(downloadProgress = null)
                emitError("Couldn't save that file. Check device storage and try again.")
            }
        }
    }

    private suspend fun materializeChatAttachment(source: String, message: ChatMessageItem): File = withContext(Dispatchers.IO) {
        val suffix = message.attachmentName?.substringAfterLast('.', "bin")?.take(8) ?: "bin"
        val target = File(app.cacheDir, "chat-preview-${message.id}.$suffix")
        if (!target.isFile || target.length() == 0L) {
            openAttachmentStream(source).use { input -> target.outputStream().use(input::copyTo) }
        }
        target
    }

    private fun openAttachmentStream(source: String): InputStream = when {
        source.startsWith("http://") || source.startsWith("https://") -> {
            val connection = URL(source).openConnection() as HttpURLConnection
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            connection.instanceFollowRedirects = true
            connection.inputStream
        }
        else -> app.contentResolver.openInputStream(Uri.parse(source)) ?: error("File is unavailable")
    }

    private fun renderPdfPage(file: File, pageIndex: Int): Pair<Bitmap, Int> {
        val descriptor = android.os.ParcelFileDescriptor.open(file, android.os.ParcelFileDescriptor.MODE_READ_ONLY)
        PdfRenderer(descriptor).use { renderer ->
            require(renderer.pageCount > 0) { "PDF has no pages" }
            val count = renderer.pageCount
            renderer.openPage(pageIndex.coerceIn(0, count - 1)).use { page ->
                val width = 720
                val height = (width.toFloat() / page.width * page.height).toInt().coerceAtLeast(1)
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { bitmap ->
                    bitmap.eraseColor(android.graphics.Color.WHITE)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                }
                return bitmap to count
            }
        }
    }

    private fun String.sanitizeFileName(): String = replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").take(120)

    /** Thrown while streaming a file whose provider reports no size once it crosses the cap. */
    private class TeamUploadTooLargeException : Exception()

    /**
     * Uploads the file to the team workspace when one is bound and the user is signed in;
     * returns the toast message describing what actually happened. Local-only workspaces
     * and signed-out sessions keep the file on-device without requiring an account.
     */
    private suspend fun shareFileWithTeam(uri: Uri, settings: AppSettings): String {
        val remoteWorkspaceId = repository.remoteWorkspaceId(settings.activeWorkspaceId)
            ?: return "File shared"
        if (!NorfoldSupabase.isSignedIn()) return "File shared here · sign in to send it to the team"
        // The picker accepts */*: refusing over-limit files before the read keeps a large
        // video from being pulled into memory just to be rejected.
        if (displaySize(uri) > MaxTeamFileBytes) {
            return "File shared here · files up to 25 MB sync on the free plan"
        }
        return runCatching {
            val bytes = withContext(Dispatchers.IO) {
                app.contentResolver.openInputStream(uri)?.use { it.readBytesCapped(MaxTeamFileBytes) }
                    ?: error("File is not readable")
            }
            val (payload, mime) = compressImageForUpload(bytes, app.contentResolver.getType(uri) ?: "application/octet-stream")
            NorfoldSupabase.uploadWorkspaceFile(
                remoteWorkspaceId = remoteWorkspaceId,
                displayName = displayName(uri),
                mimeType = mime,
                bytes = payload,
            )
        }.fold(
            onSuccess = { "File shared with the team" },
            onFailure = { failure ->
                if (failure is TeamUploadTooLargeException) "File shared here · files up to 25 MB sync on the free plan"
                else "File shared here · team upload didn't finish, it will stay on this device"
            },
        )
    }

    /** Reads the stream fully but aborts past [limit] — some providers report 0 for OpenableColumns.SIZE. */
    private fun InputStream.readBytesCapped(limit: Long): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0L
        while (true) {
            val read = read(buffer)
            if (read < 0) break
            total += read
            if (total > limit) throw TeamUploadTooLargeException()
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }

    fun addWorkspaceFile(uri: Uri?) {
        if (uri == null) return
        persistReadPermission(uri)
        viewModelScope.launch {
            val settings = state.value.settings
            runCatching {
                repository.addWorkspaceFile(
                    displayName = displayName(uri),
                    mimeType = app.contentResolver.getType(uri) ?: "application/octet-stream",
                    uri = uri.toString(),
                    sizeBytes = displaySize(uri),
                )
                when (shareFileWithTeam(uri, settings)) {
                    "File shared" -> "File added to workspace"
                    "File shared with the team" -> "File added and shared with the team"
                    "File shared here · files up to 25 MB sync on the free plan" -> "File added here · files up to 25 MB sync on the free plan"
                    else -> "File added here · sign in to share it with the team"
                }
            }.onSuccess(::emitSuccess)
                .onFailure { emitError("Couldn't add that file. Choose it again and retry.") }
        }
    }

    fun rebuildWorkspaceIndex() = viewModelScope.launch {
        repository.rebuildWorkspaceIndex()
        emitSuccess("Workspace index rebuilt")
    }
    fun importMarkdown(uri: Uri?) {
        if (uri == null) return
        persistReadPermission(uri)
        viewModelScope.launch {
            runCatching {
                val body = withContext(Dispatchers.IO) {
                    app.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }.orEmpty()
                }
                require(body.isNotBlank()) { "Markdown file is empty" }
                val title = displayName(uri).substringBeforeLast('.').ifBlank { "Imported Markdown" }
                selectedNoteId.value = repository.createNote(title = title, body = body, tagNames = listOf("Imported"))
                selectedDocumentOwner.value = DocumentOwner.note(requireNotNull(selectedNoteId.value))
                navigateTo(Destination.NoteEditor)
                "Markdown imported"
            }.onSuccess {
                emitSuccess(it)
            }.onFailure {
                emitError("Couldn't import that Markdown file. The current workspace was not changed.")
            }
        }
    }

    fun attachFileToSelectedNote(uri: Uri?) {
        if (uri == null) return
        persistReadPermission(uri)
        viewModelScope.launch {
            val note = state.value.selectedNote
            if (note == null) {
                emitError("Open a Doc before adding an attachment")
                return@launch
            }
            runCatching {
                repository.addAttachment(
                    noteId = note.id,
                    displayName = displayName(uri),
                    mimeType = app.contentResolver.getType(uri) ?: "application/octet-stream",
                    uri = uri.toString(),
                    sizeBytes = displaySize(uri),
                )
                "Attachment linked"
            }.onSuccess {
                emitSuccess(it)
            }.onFailure {
                emitError("Couldn't attach that file. Choose it again and retry.")
            }
        }
    }

    fun addLinkEmbedToSelectedNote(target: String, title: String = target) = viewModelScope.launch {
        val note = state.value.selectedNote
        if (note == null) {
            emitError("Open a Doc before adding a link")
            return@launch
        }
        if (target.isBlank()) {
            emitError("Enter a link before embedding it")
            return@launch
        }
        repository.addNoteEmbed(note.id, NoteEmbedType.Link, title, target, "Embedded link")
        emitSuccess("Link embedded")
    }

    fun embedFileToSelectedNote(uri: Uri?) {
        if (uri == null) return
        persistReadPermission(uri)
        viewModelScope.launch {
            val note = state.value.selectedNote
            if (note == null) {
                emitError("Open a Doc before embedding a file")
                return@launch
            }
            runCatching {
                val mimeType = app.contentResolver.getType(uri) ?: "application/octet-stream"
                val type = when {
                    mimeType.startsWith("image/") -> NoteEmbedType.Image
                    mimeType.startsWith("video/") -> NoteEmbedType.Video
                    mimeType.startsWith("audio/") -> NoteEmbedType.Audio
                    else -> NoteEmbedType.File
                }
                val name = displayName(uri)
                repository.addNoteEmbed(
                    noteId = note.id,
                    type = type,
                    title = name,
                    target = uri.toString(),
                    preview = "${mimeType} · ${formatSize(displaySize(uri))}",
                )
                "Embedded $name"
            }.onSuccess {
                emitSuccess(it)
            }.onFailure {
                emitError("Couldn't embed that file. The Doc was not changed.")
            }
        }
    }

    fun setSelectedNoteCover(uri: Uri?) {
        if (uri == null) return
        persistReadPermission(uri)
        viewModelScope.launch {
            val note = state.value.selectedNote
            if (note == null) {
                emitError("Open a Doc before changing its cover")
                return@launch
            }
            runCatching {
                repository.updateNoteCover(note, uri.toString(), app.contentResolver.getType(uri) ?: "application/octet-stream")
                "Cover updated"
            }.onSuccess(::emitSuccess)
                .onFailure { emitError("Couldn't update the cover. Choose the image again and retry.") }
        }
    }

    fun clearSelectedNoteCover() = viewModelScope.launch {
        state.value.selectedNote?.let {
            repository.updateNoteCover(it, null, null)
            emitSuccess("Cover removed")
        }
    }

    fun createWorkspace(
        name: String,
        palette: ThemeProfile = state.value.settings.themeProfile,
        icon: String = name.trim().take(1).uppercase().ifBlank { "N" },
        iconKind: WorkspaceIconKind = WorkspaceIconKind.Text,
        iconUri: String? = null,
        backgroundUri: String? = null,
        template: String? = null,
    ) = viewModelScope.launch {
        if (name.isBlank()) return@launch
        val id = repository.createWorkspace(name.trim(), icon, palette, iconKind, iconUri, backgroundUri)
        repository.setActiveWorkspace(id)
        refreshTeamBinding()
        template?.let { repository.applyWorkspaceTemplate(it) }
        resetNavigation(Destination.WorkspaceHub)
        emitSuccess("Workspace \"${name.trim()}\" created")
    }

    fun switchWorkspace(id: Long) = viewModelScope.launch {
        repository.setActiveWorkspace(id)
        refreshTeamBinding()
        // Channel ids are per-workspace; drop back to General so we don't point at a stale channel.
        activeChatChannelId.value = null
        chatConversationOpen.value = false
        // Navigate away first: discardUntouchedDrafts reads selectedNoteId, so nulling it
        // before resetNavigation would leak an open draft into the old workspace.
        resetNavigation(Destination.WorkspaceHub)
        selectedNoteId.value = null
    }

    fun renameWorkspace(id: Long, name: String, palette: ThemeProfile) = viewModelScope.launch {
        repository.updateWorkspace(id, name, name.take(1).uppercase().ifBlank { "N" }, palette)
    }

    /** Patch the active workspace's member permissions (mockup 12). */
    fun patchActiveWorkspacePermissions(block: (Workspace) -> Workspace) = viewModelScope.launch {
        val settings = state.value.settings
        val active = state.value.workspaces.firstOrNull { it.id == settings.activeWorkspaceId } ?: return@launch
        val w = block(active)
        repository.updateWorkspacePermissions(
            w.id, w.permRename, w.permChangeIcon, w.permInviteMembers,
            w.permDeleteNotes, w.permEditNotes, w.permManageTasks,
        )
    }

    fun deleteWorkspace(id: Long) = viewModelScope.launch {
        val ok = repository.deleteWorkspace(id)
        if (ok) emitSuccess("Workspace deleted") else emitError("Create another workspace before deleting this one")
    }

    /** Generic persisted-settings patch so any row can save without a bespoke setter. */
    fun patchSettings(block: (com.norfold.app.domain.AppSettings) -> com.norfold.app.domain.AppSettings) = viewModelScope.launch {
        val next = block(state.value.settings)
        repository.updateSettings(next)
        NorfoldWorkScheduler.reconcile(app, next)
    }

    fun previewScales(
        fontScale: Float? = null,
        uiScale: Float? = null,
        toolbarScale: Float? = null,
    ) {
        val persisted = state.value.settings
        val current = _scalePreview.value ?: ScalePreviewState(
            fontScale = persisted.fontScale,
            uiScale = persisted.uiScale,
            toolbarScale = persisted.editorToolbarScale,
        )
        if (current.saving) return
        val next = current.copy(
            fontScale = fontScale ?: current.fontScale,
            uiScale = uiScale ?: current.uiScale,
            toolbarScale = toolbarScale ?: current.toolbarScale,
            remainingSeconds = 20,
            invalidInputs = current.invalidInputs - setOfNotNull(
                ScaleSetting.Font.takeIf { fontScale != null },
                ScaleSetting.Ui.takeIf { uiScale != null },
                ScaleSetting.Toolbar.takeIf { toolbarScale != null },
            ),
        )
        if (next.fontScale !in 0.5f..2f || next.uiScale !in 0.5f..2f || next.toolbarScale !in 0.5f..2f) return
        if (
            next.fontScale == persisted.fontScale &&
            next.uiScale == persisted.uiScale &&
            next.toolbarScale == persisted.editorToolbarScale
        ) {
            revertScalePreview()
            return
        }
        _scalePreview.value = next
        startScalePreviewCountdown()
    }

    fun setScaleInputValidity(setting: ScaleSetting, valid: Boolean) {
        val current = _scalePreview.value ?: return
        if (current.saving) return
        _scalePreview.value = current.copy(
            invalidInputs = if (valid) current.invalidInputs - setting else current.invalidInputs + setting,
        )
    }

    fun saveScalePreview() {
        val preview = _scalePreview.value ?: return
        if (preview.saving || preview.invalidInputs.isNotEmpty()) return
        scalePreviewJob?.cancel()
        scalePreviewJob = null
        _scalePreview.value = preview.copy(saving = true)
        viewModelScope.launch {
            runCatching { repository.updateSettings(preview.applyTo(state.value.settings)) }
                .onSuccess { _scalePreview.value = null }
                .onFailure {
                    _scalePreview.value = preview.copy(saving = false)
                    emitError("Couldn't save appearance settings. Your preview is still active; try again.")
                    startScalePreviewCountdown()
                }
        }
    }

    fun revertScalePreview() {
        scalePreviewJob?.cancel()
        scalePreviewJob = null
        _scalePreview.value = null
    }

    private fun startScalePreviewCountdown() {
        scalePreviewJob?.cancel()
        scalePreviewJob = null
        val preview = _scalePreview.value ?: return
        if (scalePreviewCountdownPaused || preview.saving) return
        scalePreviewJob = viewModelScope.launch {
            while (true) {
                delay(1_000)
                val active = _scalePreview.value ?: return@launch
                if (active.saving || scalePreviewCountdownPaused) return@launch
                val remaining = active.remainingSeconds - 1
                if (remaining <= 0) {
                    _scalePreview.value = null
                    scalePreviewJob = null
                    return@launch
                }
                _scalePreview.value = active.copy(remainingSeconds = remaining)
            }
        }
    }

    private fun pauseScalePreviewCountdown() {
        scalePreviewCountdownPaused = true
        scalePreviewJob?.cancel()
        scalePreviewJob = null
    }

    private fun resumeScalePreviewCountdown() {
        scalePreviewCountdownPaused = false
        startScalePreviewCountdown()
    }

    fun setTheme(mode: ThemeMode) = viewModelScope.launch {
        repository.updateSettings(state.value.settings.copy(themeMode = mode))
    }

    fun setThemeProfile(profile: ThemeProfile) = viewModelScope.launch {
        repository.updateSettings(
            state.value.settings.copy(
                themeProfile = profile,
                accentColor = profile.palette().accent.toArgb().toLong() and 0xFFFFFFFFL,
            ),
        )
    }

    fun setEditorLineWidth(value: EditorLineWidth) = viewModelScope.launch {
        repository.updateSettings(state.value.settings.copy(editorLineWidth = value))
    }

    fun setEditorFontFamily(value: EditorFontFamily) = viewModelScope.launch {
        repository.updateSettings(state.value.settings.copy(editorFontFamily = value))
    }

    fun setShowMarkdownSyntax(value: Boolean) = viewModelScope.launch {
        repository.updateSettings(state.value.settings.copy(showMarkdownSyntax = value))
    }

    fun setNoteGestureActions(longPress: NoteGestureAction? = null, swipeStart: NoteGestureAction? = null, swipeEnd: NoteGestureAction? = null) = viewModelScope.launch {
        val current = state.value.settings
        repository.updateSettings(
            current.copy(
                noteLongPressAction = longPress ?: current.noteLongPressAction,
                noteSwipeStartAction = swipeStart ?: current.noteSwipeStartAction,
                noteSwipeEndAction = swipeEnd ?: current.noteSwipeEndAction,
            ),
        )
    }

    fun setTaskGestureActions(swipeStart: com.norfold.app.domain.TaskGestureAction? = null, swipeEnd: com.norfold.app.domain.TaskGestureAction? = null) = viewModelScope.launch {
        val current = state.value.settings
        repository.updateSettings(
            current.copy(
                taskSwipeStartAction = swipeStart ?: current.taskSwipeStartAction,
                taskSwipeEndAction = swipeEnd ?: current.taskSwipeEndAction,
            ),
        )
    }

    fun setNoteRenderEngine(engine: com.norfold.app.domain.NoteRenderEngine) = viewModelScope.launch {
        repository.updateSettings(state.value.settings.copy(noteRenderEngine = engine))
    }

    fun setPrivacyOption(blockScreenshots: Boolean? = null, biometricOnOpen: Boolean? = null, reduceMotion: Boolean? = null) = viewModelScope.launch {
        val current = state.value.settings
        if (biometricOnOpen == false) BiometricVaultKeyStore(app).clear()
        repository.updateSettings(
            current.copy(
                blockScreenshots = blockScreenshots ?: current.blockScreenshots,
                requireBiometricOnOpen = biometricOnOpen ?: current.requireBiometricOnOpen,
                reduceMotion = reduceMotion ?: current.reduceMotion,
            ),
        )
    }

    fun setBackupFolder(uri: String) = viewModelScope.launch {
        repository.updateSettings(state.value.settings.copy(backupFolderUri = uri))
    }

    fun handleBackupFolderPicked(uri: Uri?) {
        if (uri == null) return
        persistReadPermission(uri, write = true)
        viewModelScope.launch {
            repository.updateSettings(state.value.settings.copy(backupFolderUri = uri.toString()))
            emitSuccess("Backup folder selected")
        }
    }

    /** Live availability check for the username editor. Own current handle always reads as free. */
    suspend fun isUsernameAvailable(handle: String): Boolean {
        val normalized = handle.trim().removePrefix("@").lowercase()
        if (normalized == state.value.settings.syncUserName.lowercase()) return true
        return NorfoldSupabase.isHandleAvailable(normalized)
    }

    /** A handful of unique handle suggestions derived from a base, used when one is taken. */
    fun usernameSuggestions(base: String): List<String> {
        val root = base.trim().removePrefix("@").lowercase()
            .filter { it.isLetterOrDigit() || it == '_' }
            .ifBlank { "user" }
            .take(20)
        val safeRoot = if (root.first().isDigit()) "u$root".take(24) else root
        return buildList {
            add("${safeRoot}_${(10..99).random()}")
            add("${safeRoot}${(100..999).random()}")
            add("the_$safeRoot".take(30))
            add("${safeRoot}_${listOf("hq","dev","io","app","real").random()}")
        }.distinct()
    }

    fun updateProfile(username: String, publicName: String, deviceName: String) = viewModelScope.launch {
        val handle = username.trim().removePrefix("@").lowercase()
        if (!handle.matches(Regex("^[a-z][a-z0-9_]{2,29}$"))) {
            emitError("Handle must be 3–30 characters, start with a letter, and use only letters, numbers, or underscores")
            return@launch
        }
        runCatching { NorfoldSupabase.claimProfileHandleIfSignedIn(handle, publicName.trim()) }
            .onSuccess { verifiedOnline ->
                app.getSharedPreferences("norfold_identity_migrations", Context.MODE_PRIVATE)
                    .edit().putBoolean("profile_name_explicitly_edited", true).apply()
                repository.updateSettings(
                    state.value.settings.copy(
                        syncUserName = handle,
                        syncPublicName = publicName.trim().take(80),
                        syncDeviceName = deviceName.trim().ifBlank { "Android device" }.take(80),
                    ),
                )
                emitSuccess(if (verifiedOnline) "Profile updated · @$handle is reserved" else "Profile updated locally · sign in to reserve @$handle")
            }
            .onFailure { error ->
                val detail = error.message.orEmpty()
                if (detail.contains("handle_taken", ignoreCase = true) || detail.contains("23505")) {
                    emitError(
                    "@$handle is already taken"
                    )
                } else {
                    emitError(authFailureMessage(error, "Couldn't verify that handle. Your current profile was not changed."))
                }
            }
    }

    fun updateProfileVisuals(profileImageUri: String?, profileBackgroundUri: String?) = viewModelScope.launch {
        profileImageUri?.let { persistReadPermissionString(it) }
        profileBackgroundUri?.let { persistReadPermissionString(it) }
        repository.updateSettings(
            state.value.settings.copy(
                profileImageUri = profileImageUri?.takeIf { it.isNotBlank() },
                profileBackgroundUri = profileBackgroundUri?.takeIf { it.isNotBlank() },
            ),
        )
        emitSuccess("Profile visuals updated")
    }

    fun connectGoogleDrive(accessToken: String?) {
        if (accessToken.isNullOrBlank()) {
            emitError("Couldn't get access to Google Drive. Reconnect Google and try again.")
            return
        }
        googleDriveAuthStore.saveAccessToken(accessToken)
        googleDriveConnected.value = true
        viewModelScope.launch { coordinateCloudAccount() }
        emitSuccess("Google Drive connected")
    }

    fun reportGoogleDriveAuthorizationFailure(error: Throwable?) {
        if (error == null) emitInfo("Google Drive authorization cancelled")
        else emitError(syncFailureMessage(error, SyncProvider.GoogleDrive))
    }

    fun discoverGoogleDriveBackups() = viewModelScope.launch {
        _accountCoordinatorState.value = AccountCoordinatorState.DiscoveringBackupsAndMemberships
        runCatching { googleDriveApiSyncStore.discoverBackups() }
            .onSuccess { backups ->
                _driveBackups.value = backups
                _accountCoordinatorState.value = if (backups.isEmpty()) {
                    AccountCoordinatorState.BackupSetupRequired
                } else {
                    AccountCoordinatorState.RestoreChoiceRequired
                }
            }
            .onFailure {
                _accountCoordinatorState.value = AccountCoordinatorState.RecoverableError(
                    googleDriveFailureMessage(it, "Couldn't check Google Drive backups"),
                )
            }
    }

    fun restoreGoogleDriveBackup(
        manifest: GoogleDriveBackupManifest,
        secret: String,
        categories: Set<BackupCategory>,
    ) = viewModelScope.launch {
        if (categories.isEmpty()) {
            emitError("Choose at least one backup category to restore")
            return@launch
        }
        val resolvedSecret = resolveRestoreSecret(secret) ?: return@launch
        _accountCoordinatorState.value = AccountCoordinatorState.DiscoveringBackupsAndMemberships
        runCatching {
            val result = googleDriveApiSyncStore.restoreDeviceBackup(resolvedSecret.toCharArray(), manifest)
            val local = repository.snapshot()
            repository.restore(result.snapshot.mergeSelectedInto(local, categories))
            val accountId = NorfoldSupabase.currentAccountId()
            if (accountId != null) syncSecretStore.save(accountId, secret.toCharArray())
            val next = repository.settingsValue().copy(
                syncProvider = SyncProvider.GoogleDrive,
                syncFolderUri = GoogleDriveApiSyncStore.AppDataFolderUri,
                syncChainId = result.writeResult.chainId,
                lastSyncAt = System.currentTimeMillis(),
                lastSyncHash = result.writeResult.snapshotHash,
                lastSyncStatus = "Selected backup data restored",
                autoBackup = true,
            )
            repository.updateSettings(next)
            NorfoldWorkScheduler.reconcile(app, next)
        }.onSuccess {
            restoredBackupCategories = categories
            _accountCoordinatorState.value = AccountCoordinatorState.Ready
            emitSuccess("Selected backup data restored")
        }.onFailure {
            val message = googleDriveFailureMessage(it, "Couldn't restore that backup")
            _accountCoordinatorState.value = AccountCoordinatorState.RecoverableError(message)
            emitError(message)
        }
    }

    fun startFreshAfterBackupDiscovery() {
        restoredBackupCategories = emptySet()
        _accountCoordinatorState.value = AccountCoordinatorState.BackupSetupRequired
    }

    fun disconnectGoogleDrive() {
        googleDriveAuthStore.clear()
        googleDriveConnected.value = false
        viewModelScope.launch {
            val next = repository.settingsValue().copy(
                autoBackup = false,
                lastSyncStatus = "Google Drive disconnected — automatic backup is paused",
            )
            repository.updateSettings(next)
            NorfoldWorkScheduler.reconcile(app, next)
            _driveBackups.value = emptyList()
            _accountCoordinatorState.value = if (NorfoldSupabase.isSignedIn()) {
                AccountCoordinatorState.BackupSetupRequired
            } else {
                AccountCoordinatorState.SignedOut
            }
        }
        emitSuccess("Google Drive disconnected")
    }

    fun wipeGoogleDriveBackup() = viewModelScope.launch {
        if (!googleDriveConnected.value) {
            emitError("Connect Google Drive first")
            return@launch
        }
        if (syncing.value) {
            emitInfo("Sync is already running")
            return@launch
        }
        syncing.value = true
        runCatching {
            googleDriveApiSyncStore.wipeAllBackups()
            val next = repository.settingsValue().copy(
                autoBackup = false,
                syncChainId = "",
                lastSyncHash = "",
                lastSyncStatus = "All Google Drive backup data was wiped",
            )
            repository.updateSettings(next)
            NorfoldWorkScheduler.reconcile(app, next)
            _driveBackups.value = emptyList()
        }.onSuccess {
            emitSuccess("All Google Drive backup data was permanently deleted")
        }.onFailure {
            val message = googleDriveFailureMessage(it, "Couldn't wipe the Drive backup data")
            emitError(message)
        }
        syncing.value = false
    }

    // ContentResolver.query is a cross-process call — keep it off the main thread.
    private suspend fun displayName(uri: Uri): String = withContext(Dispatchers.IO) {
        app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        } ?: uri.lastPathSegment?.substringAfterLast('/') ?: "Imported file"
    }

    private fun persistReadPermissionString(value: String) {
        runCatching { Uri.parse(value) }.getOrNull()?.let { persistReadPermission(it) }
    }

    private fun persistReadPermission(uri: Uri, write: Boolean = false) {
        if (uri.scheme != "content") return
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
            if (write) Intent.FLAG_GRANT_WRITE_URI_PERMISSION else 0
        runCatching {
            app.contentResolver.takePersistableUriPermission(uri, flags)
        }
    }

    private suspend fun displaySize(uri: Uri): Long = withContext(Dispatchers.IO) {
        app.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getLong(0) else 0L
        } ?: 0L
    }

    private fun formatSize(bytes: Long): String = when {
        bytes <= 0 -> "unknown size"
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        else -> "${bytes / (1024 * 1024)} MB"
    }

    fun updateWorkspace(
        name: String,
        icon: String,
        iconKind: WorkspaceIconKind,
        iconUri: String?,
        backgroundUri: String?,
        adminsControlVisuals: Boolean,
        membersCreateNotes: Boolean,
        membersInvite: Boolean,
    ) = viewModelScope.launch {
        iconUri?.let { persistReadPermissionString(it) }
        backgroundUri?.let { persistReadPermissionString(it) }
        val settings = state.value.settings
        // Rename the active workspace itself, not just the cosmetic label.
        val active = state.value.workspaces.firstOrNull { it.id == settings.activeWorkspaceId }
        if (active != null) {
            repository.updateWorkspace(active.id, name.ifBlank { active.name }, icon.ifBlank { "N" }, active.palette, iconKind, iconUri, backgroundUri)
        }
        repository.updateSettings(
            settings.copy(
                workspaceName = name.ifBlank { "Team Notebook" },
                workspaceIcon = icon.ifBlank { "N" },
                workspaceIconKind = iconKind,
                workspaceIconUri = iconUri,
                workspaceBackgroundUri = backgroundUri,
                adminsControlWorkspaceVisuals = adminsControlVisuals,
                allowMembersCreateNotes = membersCreateNotes,
                allowMembersInvite = membersInvite,
            ),
        )
    }

    fun handleSyncFolderPicked(uri: Uri?, request: SyncFolderRequest?) {
        if (uri == null || request == null) return
        if (syncing.value) {
            emitInfo("Sync is already running")
            return
        }
        persistReadPermission(uri, write = true)
        viewModelScope.launch {
            syncing.value = true
            sessionSyncSecret = request.secret
            runCatching {
                when (request.action) {
                    SyncFolderAction.CreateChain -> {
                        val result = syncStore.createChain(
                            treeUri = uri,
                            provider = request.provider,
                            secret = request.secret.toCharArray(),
                            username = request.username,
                            publicName = request.publicName,
                            deviceName = request.deviceName,
                            snapshot = repository.snapshot(),
                        )
                        repository.updateSettings(
                            state.value.settings.copy(
                                syncProvider = request.provider,
                                syncFolderUri = uri.toString(),
                                syncChainId = result.chainId,
                                syncDeviceName = request.deviceName,
                                syncUserName = request.username,
                                syncPublicName = request.publicName,
                                lastSyncAt = System.currentTimeMillis(),
                                lastSyncHash = result.snapshotHash,
                                lastSyncStatus = result.status,
                                syncConflictCount = result.conflictCount,
                            ),
                        )
                        conflictReport.value = null
                        result.status
                    }
                    SyncFolderAction.RestoreChain -> {
                        val result = syncStore.restoreChain(uri, request.provider, request.secret.toCharArray())
                        repository.restore(result.snapshot)
                        repository.updateSettings(
                            state.value.settings.copy(
                                syncProvider = request.provider,
                                syncFolderUri = uri.toString(),
                                syncChainId = result.writeResult.chainId,
                                syncDeviceName = request.deviceName,
                                syncUserName = request.username,
                                syncPublicName = request.publicName,
                                lastSyncAt = System.currentTimeMillis(),
                                lastSyncHash = result.writeResult.snapshotHash,
                                lastSyncStatus = result.writeResult.status,
                                syncConflictCount = result.writeResult.conflictCount,
                            ),
                        )
                        conflictReport.value = null
                        result.writeResult.status
                    }
                }
            }.onSuccess {
                emitSuccess(it)
            }.onFailure { error ->
                val failure = syncFailureMessage(error, request.provider)
                repository.updateSettings(state.value.settings.copy(lastSyncStatus = failure))
                emitError(failure, actionLabel = "Retry", action = UiFeedbackAction.RetrySync)
            }
            syncing.value = false
        }
    }

    fun createGoogleDriveApiChain(secret: String, username: String, publicName: String, deviceName: String) = viewModelScope.launch {
        if (syncing.value) {
            emitInfo("Sync is already running")
            return@launch
        }
        val resolvedSecret = resolveCreateSecret(secret) ?: return@launch
        syncing.value = true
        sessionSyncSecret = resolvedSecret
        NorfoldSupabase.currentAccountId()?.let { syncSecretStore.save(it, resolvedSecret.toCharArray()) }
        runCatching {
            val result = googleDriveApiSyncStore.createChain(
                secret = resolvedSecret.toCharArray(),
                username = username,
                publicName = publicName,
                deviceName = deviceName,
                snapshot = repository.snapshot(),
            )
            val next = state.value.settings.copy(
                    syncProvider = SyncProvider.GoogleDrive,
                    syncFolderUri = GoogleDriveApiSyncStore.AppDataFolderUri,
                    syncChainId = result.chainId,
                    syncDeviceName = deviceName,
                    syncUserName = username,
                    syncPublicName = publicName,
                    lastSyncAt = System.currentTimeMillis(),
                    lastSyncHash = result.snapshotHash,
                    lastSyncStatus = result.status,
                    syncConflictCount = result.conflictCount,
                    autoBackup = true,
                )
            repository.updateSettings(next)
            NorfoldWorkScheduler.reconcile(app, next)
            conflictReport.value = null
            result.status
        }.onSuccess {
            emitSuccess(it)
        }.onFailure { error ->
            val failure = googleDriveFailureMessage(error, "Google Drive sync failed")
            repository.updateSettings(state.value.settings.copy(lastSyncStatus = failure))
            emitError(failure, actionLabel = "Retry", action = UiFeedbackAction.RetrySync)
        }
        syncing.value = false
    }

    fun restoreGoogleDriveApiChain(secret: String, username: String, publicName: String, deviceName: String) = viewModelScope.launch {
        if (syncing.value) {
            emitInfo("Sync is already running")
            return@launch
        }
        val resolvedSecret = resolveRestoreSecret(secret) ?: return@launch
        syncing.value = true
        sessionSyncSecret = resolvedSecret
        NorfoldSupabase.currentAccountId()?.let { syncSecretStore.save(it, resolvedSecret.toCharArray()) }
        runCatching {
            val result = googleDriveApiSyncStore.restoreChain(resolvedSecret.toCharArray())
            repository.restore(result.snapshot)
            val next = state.value.settings.copy(
                    syncProvider = SyncProvider.GoogleDrive,
                    syncFolderUri = GoogleDriveApiSyncStore.AppDataFolderUri,
                    syncChainId = result.writeResult.chainId,
                    syncDeviceName = deviceName,
                    syncUserName = username,
                    syncPublicName = publicName,
                    lastSyncAt = System.currentTimeMillis(),
                    lastSyncHash = result.writeResult.snapshotHash,
                    lastSyncStatus = result.writeResult.status,
                    syncConflictCount = result.writeResult.conflictCount,
                    autoBackup = true,
                )
            repository.updateSettings(next)
            NorfoldWorkScheduler.reconcile(app, next)
            conflictReport.value = null
            result.writeResult.status
        }.onSuccess {
            emitSuccess(it)
        }.onFailure { error ->
            val failure = googleDriveFailureMessage(error, "Google Drive restore failed")
            repository.updateSettings(state.value.settings.copy(lastSyncStatus = failure))
            emitError(failure)
        }
        syncing.value = false
    }

    fun syncNow(secret: String) = viewModelScope.launch {
        if (syncing.value) {
            emitInfo("Sync is already running")
            return@launch
        }
        val now = SystemClock.elapsedRealtime()
        val remainingMs = syncCooldownDeadlineElapsedMs - now
        if (remainingMs > 0L) {
            emitInfo("Please wait ${(remainingMs + 999L) / 1_000L}s before syncing again")
            return@launch
        }
        val current = state.value.settings
        val folder = current.syncFolderUri
        val chainId = current.syncChainId
        if (current.syncProvider == SyncProvider.None || folder.isNullOrBlank() || chainId.isNullOrBlank()) {
            emitError("Set up sync in Settings before syncing this workspace")
            return@launch
        }
        val cloudProvider = current.syncProvider == SyncProvider.GoogleDrive ||
            current.syncProvider == SyncProvider.OneDrive
        if (cloudProvider && !networkOnline.value) {
            val offlineMessage = "Offline — changes are saved on this device. Reconnect and try again."
            repository.updateSettings(current.copy(lastSyncStatus = offlineMessage))
            emitError(offlineMessage, actionLabel = "Retry", action = UiFeedbackAction.RetrySync)
            return@launch
        }
        sessionSyncSecret = secret
        NorfoldSupabase.currentAccountId()?.let { syncSecretStore.save(it, secret.toCharArray()) }
        syncing.value = true
        var consumeCooldown = true
        runCatching {
            val (remoteSnapshot, result) =
                if (current.syncProvider == SyncProvider.GoogleDrive && folder == GoogleDriveApiSyncStore.AppDataFolderUri) {
                    googleDriveApiSyncStore.syncNow(
                        chainId = chainId,
                        secret = secret.toCharArray(),
                        username = current.syncUserName,
                        publicName = current.syncPublicName,
                        deviceName = current.syncDeviceName,
                        lastSyncHash = current.lastSyncHash,
                        localSnapshot = repository.snapshot(),
                    )
                } else {
                    syncStore.syncNow(
                        treeUri = Uri.parse(folder),
                        provider = current.syncProvider,
                        chainId = chainId,
                        secret = secret.toCharArray(),
                        username = current.syncUserName,
                        publicName = current.syncPublicName,
                        deviceName = current.syncDeviceName,
                        lastSyncHash = current.lastSyncHash,
                        localSnapshot = repository.snapshot(),
                    )
                }
            if (remoteSnapshot != null) repository.restore(remoteSnapshot)
            // Re-read at write time: `current` predates the network sync, and restore() may have
            // just persisted the remote snapshot's settings (or the user changed one mid-sync).
            // Only the sync-outcome fields belong to this write.
            repository.updateSettings(
                repository.settings.first().copy(
                    lastSyncAt = System.currentTimeMillis(),
                    lastSyncHash = result.snapshotHash,
                    lastSyncStatus = result.status,
                    syncConflictCount = result.conflictCount,
                ),
            )
            conflictReport.value = if (result.conflictCount > 0) readConflictReportFrom(current) else null
            result.status
        }.onSuccess {
            emitSuccess(it)
        }.onFailure { error ->
            consumeCooldown = !error.isLikelyOfflineSyncFailure()
            val userMessage = syncFailureMessage(error, current.syncProvider)
            // Fresh read for the same reason as the success path: don't revert settings that
            // changed while the sync was running.
            repository.updateSettings(repository.settings.first().copy(lastSyncStatus = userMessage))
            emitError(userMessage, actionLabel = "Retry", action = UiFeedbackAction.RetrySync)
        }
        syncCooldownDeadlineElapsedMs = if (consumeCooldown) {
            SystemClock.elapsedRealtime() + SyncRequestCooldownMs
        } else {
            0L
        }
        syncing.value = false
    }

    fun syncCooldownRemainingSeconds(): Long =
        ((syncCooldownDeadlineElapsedMs - SystemClock.elapsedRealtime()).coerceAtLeast(0L) + 999L) / 1_000L

    private fun Throwable.isLikelyOfflineSyncFailure(): Boolean {
        val detail = generateSequence(this) { it.cause }
            .joinToString(" ") { it.message.orEmpty() }
            .lowercase()
        return listOf("unable to resolve host", "network is unreachable", "no network", "offline", "failed to connect")
            .any(detail::contains)
    }

    private fun googleDriveFailureMessage(error: Throwable, fallback: String): String {
        val detail = error.message.orEmpty()
        if (detail.contains("Google Drive", ignoreCase = true) && detail.contains("(401)")) {
            googleDriveAuthStore.clear()
            googleDriveConnected.value = false
            return "Google Drive authorization expired. Reconnect Google, then retry."
        }
        return syncFailureMessage(error, SyncProvider.GoogleDrive).ifBlank { fallback }
    }

    private fun syncFailureMessage(error: Throwable, provider: SyncProvider): String {
        val detail = generateSequence(error) { it.cause }
            .joinToString(" ") { it.message.orEmpty() }
            .lowercase()
        return when {
            error.isLikelyOfflineSyncFailure() -> "Offline — changes are saved on this device. Reconnect and try again."
            "401" in detail || "unauthorized" in detail || "authorization" in detail && "expired" in detail ->
                if (provider == SyncProvider.GoogleDrive) "Google Drive access expired. Reconnect Google, then retry." else "Sync authorization expired. Reconnect, then retry."
            "secret" in detail || "decrypt" in detail || "password" in detail ->
                "The sync key couldn't open this encrypted backup. Check the key and try again."
            "quota" in detail || "storage limit" in detail ->
                "Cloud storage is full. Free some space, then retry."
            "conflict" in detail -> "Sync found conflicting changes. Open Conflict Review before trying again."
            "corrupt" in detail || "invalid snapshot" in detail ->
                "The remote snapshot couldn't be read safely. Your local changes remain on this device."
            else -> "Sync couldn't finish. Your changes remain saved on this device. Try again."
        }
    }

    fun loadConflictReport() = viewModelScope.launch {
        runCatching {
            readConflictReportFrom(state.value.settings)
        }.onSuccess { report ->
            conflictReport.value = report
            if (report == null) emitInfo("No conflict report found") else emitSuccess("Conflict report loaded")
        }.onFailure {
            emitError("Couldn't load the conflict report. Check the sync provider and try again.")
        }
    }

    fun useLocalForNextConflictSync() = viewModelScope.launch {
        val settings = state.value.settings
        repository.updateSettings(
            settings.copy(
                lastSyncHash = null,
                lastSyncStatus = "Local copy selected. Run Sync now to overwrite the remote snapshot.",
                syncConflictCount = 0,
            ),
        )
        conflictReport.value = null
        emitSuccess("Local copy will be uploaded on next sync")
    }

    private suspend fun readConflictReportFrom(settings: AppSettings): ParsedSyncConflictReport? {
        val folder = settings.syncFolderUri ?: return null
        if (settings.syncProvider == SyncProvider.None || folder.isBlank()) return null
        return if (settings.syncProvider == SyncProvider.GoogleDrive && folder == GoogleDriveApiSyncStore.AppDataFolderUri) {
            googleDriveApiSyncStore.readConflictReport()
        } else {
            syncStore.readConflictReport(Uri.parse(folder))
        }
    }

    fun autoSyncIfPossible() {
        if (!state.value.settings.autoSync) return
        val secret = sessionSyncSecret?.takeIf { it.isNotBlank() } ?: return
        if (!syncing.value) syncNow(secret)
    }

    fun syncConfiguredNow() {
        val secret = sessionSyncSecret?.takeIf { it.isNotBlank() }
        if (secret == null) {
            emitError(if (state.value.settings.syncProvider == SyncProvider.None) {
                "Sync is not configured yet"
            } else {
                "Restore or reconnect this sync session before syncing"
            })
            return
        }
        if (!syncing.value) syncNow(secret)
    }

    /**
     * Resolve the encryption secret when creating a backup/chain.
     * Blank password → reuse a previously generated device key, or mint a fresh random one the
     * app manages transparently. A user-typed password is used verbatim and never uploaded.
     */
    private fun resolveCreateSecret(secret: String): String? {
        if (!googleDriveConnected.value) {
            emitError("Connect Google Drive first")
            return null
        }
        if (secret.isNotBlank()) return secret
        val accountId = NorfoldSupabase.currentAccountId()
        accountId?.let { syncSecretStore.load(it)?.concatToString() }?.let { return it }
        return generateManagedSecret()
    }

    /**
     * Resolve the encryption secret when restoring. Blank password → use the stored device key.
     * If no stored key exists (fresh install / new device) and no password is given, the backup
     * cannot be opened without the password that protected it.
     */
    private fun resolveRestoreSecret(secret: String): String? {
        if (!googleDriveConnected.value) {
            emitError("Connect Google Drive first")
            return null
        }
        if (secret.isNotBlank()) return secret
        val accountId = NorfoldSupabase.currentAccountId()
        accountId?.let { syncSecretStore.load(it)?.concatToString() }?.let { return it }
        emitError("Enter the password that protected this backup to restore it.")
        return null
    }

    private fun generateManagedSecret(): String {
        val bytes = ByteArray(32)
        java.security.SecureRandom().nextBytes(bytes)
        return android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
    }

    fun setVaultSecret(secret: String) = viewModelScope.launch {
        if (secret.length < 6) {
            emitError("Vault password must be at least 6 characters")
            return@launch
        }
        repository.setVaultSecret(secret.toCharArray())
        emitSuccess("Vault lock enabled")
    }

    fun disableVault() = viewModelScope.launch {
        BiometricVaultKeyStore(app).clear()
        repository.disableVaultLock()
        repository.updateSettings(state.value.settings.copy(vaultLockEnabled = false, vaultSecretHash = null, requireBiometricOnOpen = false))
        locked.value = false
        emitSuccess("Vault lock disabled")
    }

    fun lock() {
        if (state.value.settings.vaultLockEnabled) locked.value = true
    }

    fun onAppPaused() {
        pauseScalePreviewCountdown()
    }

    fun onAppResumed() {
        resumeScalePreviewCountdown()
        refreshPhoneVerification()
    }

    /**
     * Start Telegram phone verification: create a server session and open the t.me link. The bot
     * token and webhook secret live only in Supabase — the app only ever opens a URL and later
     * reads the session status back. Never marks anything verified locally.
     */
    fun startTelegramVerification() {
        if (!NorfoldSupabase.isSignedIn()) {
            emitError("Sign in to Norfold Cloud before verifying your phone.")
            return
        }
        viewModelScope.launch {
            _phoneVerificationInProgress.value = true
            runCatching { NorfoldSupabase.startTelegramVerification() }
                .onSuccess { start ->
                    pendingVerificationId = start.verificationId
                    runCatching {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(start.telegramUrl))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        app.startActivity(intent)
                    }.onFailure {
                        _phoneVerificationInProgress.value = false
                        emitError("Couldn't open Telegram. Install Telegram or try again.")
                    }
                }
                .onFailure {
                    _phoneVerificationInProgress.value = false
                    emitError("Couldn't start Telegram verification. Please try again.")
                }
        }
    }

    /**
     * Re-read the authoritative phone/verification status from the server. Called on resume and
     * when returning from Telegram. This — not the deep link — is what flips the UI to verified.
     */
    fun refreshPhoneVerification() {
        if (!NorfoldSupabase.isSignedIn()) return
        viewModelScope.launch {
            runCatching { NorfoldSupabase.verifiedPhoneE164() }.onSuccess { phone ->
                _verifiedPhone.value = phone
                if (phone != null) {
                    if (_phoneVerificationInProgress.value || pendingVerificationId != null) {
                        showMessage("Phone verified")
                    }
                    pendingVerificationId = null
                    _phoneVerificationInProgress.value = false
                }
            }
            val id = pendingVerificationId ?: return@launch
            runCatching { NorfoldSupabase.telegramVerificationStatus(id) }.onSuccess { status ->
                when (status) {
                    "VERIFIED" -> {
                        pendingVerificationId = null
                        _phoneVerificationInProgress.value = false
                        runCatching { NorfoldSupabase.verifiedPhoneE164() }
                            .onSuccess { _verifiedPhone.value = it }
                        showMessage("Phone verified")
                    }
                    "EXPIRED", "CANCELLED" -> {
                        pendingVerificationId = null
                        _phoneVerificationInProgress.value = false
                    }
                    else -> Unit // PENDING / STARTED: keep waiting.
                }
            }
        }
    }

    /**
     * Handle the "Return to Norfold" deep link (norfold://verify/telegram). The link is untrusted:
     * it only prompts a fresh server status read.
     */
    fun handleTelegramReturn(intent: Intent) {
        val data = intent.data ?: return
        if (data.host == "verify" && data.path == "/telegram") refreshPhoneVerification()
    }


    fun onAppBackgrounded() {
        // Presence flips regardless of lock settings; the channel debounce keeps
        // quick app switches from flapping the roster.
        setChatForeground(false)
        val settings = state.value.settings
        if (!settings.vaultLockEnabled) return
        backgroundedAtElapsedMs = SystemClock.elapsedRealtime()
        if (settings.appLockOnExit || settings.autoLockMinutes == 0) locked.value = true
    }

    fun onAppForegrounded() {
        setChatForeground(true)
        val settings = state.value.settings
        val backgroundedAt = backgroundedAtElapsedMs ?: return
        backgroundedAtElapsedMs = null
        if (!settings.vaultLockEnabled || locked.value) return
        val timeoutMs = settings.autoLockMinutes.coerceAtLeast(0) * 60_000L
        if (settings.appLockOnExit || SystemClock.elapsedRealtime() - backgroundedAt >= timeoutMs) locked.value = true
    }

    fun handleBack() {
        when {
            // Never unlock the vault from a back gesture: consume and stay locked. This branch is
            // unreachable today (the back handler lives inside NotesApp, which isn't composed while
            // locked), but a future handler covering LockScreen must not become a bypass.
            locked.value -> Unit
            sidebarOpen.value -> sidebarOpen.value = false
            // Back inside an open conversation returns to the chat inbox, not out of the tab.
            destination.value == Destination.Chat && chatConversationOpen.value ->
                closeChatConversation()
            searchQuery.value.isNotBlank() -> searchQuery.value = ""
            navigationHistory.isNotEmpty() -> navigateTo(navigationHistory.removeLast(), recordHistory = false)
            destination.value != Destination.WorkspaceHub -> resetNavigation(Destination.WorkspaceHub)
            else -> emitInfo("Already at home")
        }
    }

    fun unlock(secret: String) {
        val now = SystemClock.elapsedRealtime()
        if (now < unlockBlockedUntilElapsedMs) {
            emitError("Too many attempts. Try again in ${(unlockBlockedUntilElapsedMs - now + 999L) / 1_000L}s")
            return
        }
        val verified = VaultCrypto.verifySecret(secret.toCharArray(), state.value.settings.vaultSecretHash)
        locked.value = !verified
        if (verified) {
            failedUnlockAttempts = 0
            unlockBlockedUntilElapsedMs = 0L
        } else {
            failedUnlockAttempts++
            if (failedUnlockAttempts >= 5) {
                failedUnlockAttempts = 0
                unlockBlockedUntilElapsedMs = now + UnlockFailureCooldownMs
                emitError("Too many attempts. Vault unlock is paused for 30 seconds")
            } else {
                emitError("Wrong password. ${5 - failedUnlockAttempts} attempts left before a short pause.")
            }
        }
    }

    fun unlockWithBiometric() {
        val settings = state.value.settings
        if (settings.vaultLockEnabled && settings.requireBiometricOnOpen) {
            locked.value = false
        }
    }

    override fun onCleared() {
        // MediaRecorder/MediaPlayer hold native resources the GC won't reclaim for us.
        cancelVoiceRecording()
        stopChatAudio()
        super.onCleared()
    }

    private companion object {
        const val SyncRequestCooldownMs = 15_000L
        const val EmailCodeResendCooldownMs = 30_000L
        const val UnlockFailureCooldownMs = 30_000L
        // Free-plan per-file cap, enforced before a single byte is read. The server bucket
        // stays at 100 MB so a paid tier is a config change, not a migration (chat spec §4).
        const val MaxTeamFileBytes = 25L * 1024L * 1024L
        // Images above this recompress toward it before upload, like the big messengers do.
        const val ImageRecompressThresholdBytes = 2L * 1024L * 1024L
        const val MaxCompressedImageDimensionPx = 2048
        // Per-file delivery attempts before the queue parks it behind a manual retry.
        const val MaxChatUploadAttempts = 2
        // Oldest-first cap per refreshTeamChat pass so one refresh never replays a long backlog.
        const val MaxTeamChatRetryBatch = 10

        /** Unsend is allowed for one hour after sending (WhatsApp-style regret window). */
        const val UnsendWindowMs = 60L * 60L * 1000L
    }

    fun exportBackup(secret: String, write: (String) -> Unit) = viewModelScope.launch {
        runCatching {
            require(secret.isNotBlank()) { "Backup password is required" }
            // Encrypting a whole snapshot is CPU/IO heavy; the write callback only hands the
            // payload to the UI and writeEncryptedBackup does its own IO hop.
            val snapshot = repository.snapshot()
            val payload = withContext(Dispatchers.IO) { BackupCodec.encrypt(snapshot, secret.toCharArray()) }
            write(payload)
            val folder = state.value.settings.backupFolderUri
            if (!folder.isNullOrBlank()) {
                val fileName = backupStore.writeEncryptedBackup(Uri.parse(folder), payload)
                "Encrypted backup exported to $fileName"
            } else {
                "Encrypted backup exported"
            }
        }.onSuccess {
            emitSuccess(it)
        }.onFailure {
            emitError("Couldn't export the encrypted backup. Check the destination and available space, then retry.")
        }
    }

    fun importBackup(secret: String, payload: String) = viewModelScope.launch {
        runCatching {
            require(secret.isNotBlank()) { "Backup password is required" }
            require(payload.isNotBlank()) { "Backup payload is empty" }
            repository.restore(withContext(Dispatchers.IO) { BackupCodec.decrypt(payload, secret.toCharArray()) })
        }.onSuccess {
            emitSuccess("Backup restored")
        }.onFailure {
            emitError("Couldn't restore that backup. Check the backup password and file, then retry.")
        }
    }

    fun importBackupFromUri(secret: String, uri: Uri?) {
        if (uri == null) return
        viewModelScope.launch {
            runCatching {
                require(secret.isNotBlank()) { "Backup password is required" }
                val payload = withContext(Dispatchers.IO) {
                    app.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }.orEmpty()
                }
                require(payload.isNotBlank()) { "Backup file is empty" }
                val snapshot = withContext(Dispatchers.IO) { BackupCodec.decrypt(payload, secret.toCharArray()) }
                repository.restore(snapshot)
                snapshot
            }.onSuccess { snapshot ->
                // Restoring during onboarding replaces the starter content with the user's real
                // workspaces; walking the remaining wizard steps would rename the restored active
                // workspace and rewrite its settings. Finish onboarding here instead.
                if (!state.value.settings.onboardingComplete) {
                    // Point at a workspace that exists in the snapshot — the pre-restore active id
                    // may reference the empty starter workspace rather than the restored data.
                    snapshot.workspaces.firstOrNull()?.let { workspace ->
                        runCatching { repository.setActiveWorkspace(workspace.id) }
                    }
                    repository.updateSettings(repository.settings.first().copy(onboardingComplete = true))
                    resetNavigation(Destination.WorkspaceHub)
                }
                emitSuccess("Backup restored")
            }.onFailure {
                emitError("Couldn't restore that backup. Check the backup password and file, then retry.")
            }
        }
    }
}

sealed interface EmailAuthPhase {
    data object None : EmailAuthPhase

    /** Signup succeeded; waiting for the 6-digit confirmation code sent to [email]. */
    data class AwaitSignupCode(val email: String) : EmailAuthPhase

    /** Password reset requested; waiting for the 6-digit recovery code sent to [email]. */
    data class AwaitRecoveryCode(val email: String) : EmailAuthPhase

    /** Recovery code accepted; waiting for the user to choose a new password. */
    data class AwaitNewPassword(val email: String) : EmailAuthPhase

    /** Auth finished with a live session; UI should advance and then call consumeEmailAuthCompleted(). */
    data object Completed : EmailAuthPhase
}
