package com.norfold.app.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.norfold.app.data.BackupFolderStore
import com.norfold.app.data.BiometricVaultKeyStore
import com.norfold.app.data.CloudFolderSyncStore
import com.norfold.app.data.DiagnosticsState
import com.norfold.app.data.DiagnosticsStore
import com.norfold.app.data.GoogleDriveApiSyncStore
import com.norfold.app.data.GoogleDriveAuthStore
import com.norfold.app.data.NorfoldDatabase
import com.norfold.app.data.DocsRepository
import com.norfold.app.domain.AppSettings
import com.norfold.app.domain.BackupCodec
import com.norfold.app.domain.ChatMessageItem
import com.norfold.app.domain.Destination
import com.norfold.app.domain.DocLayerOrder
import com.norfold.app.domain.DocCanvasSpec
import com.norfold.app.domain.DocOverlapMode
import com.norfold.app.domain.DocSectionAction
import com.norfold.app.domain.FreeformPlacement
import com.norfold.app.domain.EditorFontFamily
import com.norfold.app.domain.EditorLineWidth
import com.norfold.app.domain.HomeTab
import com.norfold.app.domain.Note
import com.norfold.app.domain.NoteEmbedType
import com.norfold.app.domain.BlockDocument
import com.norfold.app.domain.NoteGestureAction
import com.norfold.app.domain.Notebook
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
import com.norfold.app.domain.WorkspaceObjectType
import com.norfold.app.domain.ThemeMode
import com.norfold.app.domain.ThemeProfile
import com.norfold.app.domain.VaultCrypto
import com.norfold.app.branding.palette
import com.norfold.app.cloud.NorfoldSupabase
import androidx.compose.ui.graphics.toArgb
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

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
    val message: String? = null,
)

/** A one-shot request from the sidebar ToC to scroll the open Doc to a block. */
data class ScrollToBlockRequest(val blockId: String, val serial: Long)

/**
 * A one-shot request from the sidebar ToC to mutate a section of the open Doc. Applied by the
 * editor against its live [BlockEditorSession] (never the repository directly) so undo/redo and
 * the debounced autosave path stay authoritative.
 */
data class SectionActionRequest(val headingId: String, val action: DocSectionAction, val serial: Long)

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
    val message: String?,
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

@OptIn(ExperimentalCoroutinesApi::class)
class DocsViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application
    private val repository = DocsRepository(NorfoldDatabase.get(application))
    private val syncStore = CloudFolderSyncStore(application)
    private val googleDriveAuthStore = GoogleDriveAuthStore(application)
    private val googleDriveApiSyncStore = GoogleDriveApiSyncStore(application, googleDriveAuthStore)
    private val backupStore = BackupFolderStore(application)
    private val diagnosticsStore = DiagnosticsStore(application)
    private val destination = MutableStateFlow(Destination.WorkspaceHub)
    private val tab = MutableStateFlow(HomeTab.AllNotes)
    private val searchQuery = MutableStateFlow("")
    private val selectedNotebookFilterId = MutableStateFlow<Long?>(null)
    private val selectedTagFilterId = MutableStateFlow<Long?>(null)
    private val selectedNoteId = MutableStateFlow<Long?>(null)
    private val selectedObjectId = MutableStateFlow<Long?>(null)
    private val locked = MutableStateFlow(false)
    private val syncing = MutableStateFlow(false)
    private val googleDriveConnected = MutableStateFlow(googleDriveAuthStore.hasAuthState())
    private val sidebarOpen = MutableStateFlow(false)
    private val _pendingSettingsSection = MutableStateFlow<String?>(null)
    val pendingSettingsSection: StateFlow<String?> = _pendingSettingsSection.asStateFlow()
    private var scrollToBlockSerial = 0L
    private val _scrollToBlockRequest = MutableStateFlow<ScrollToBlockRequest?>(null)
    val scrollToBlockRequest: StateFlow<ScrollToBlockRequest?> = _scrollToBlockRequest.asStateFlow()
    private var sectionActionSerial = 0L
    private val _sectionActionRequest = MutableStateFlow<SectionActionRequest?>(null)
    val sectionActionRequest: StateFlow<SectionActionRequest?> = _sectionActionRequest.asStateFlow()
    private val conflictReport = MutableStateFlow<ParsedSyncConflictReport?>(null)
    private val diagnostics = MutableStateFlow(diagnosticsStore.state())
    private val message = MutableStateFlow<String?>(null)
    private var sessionSyncSecret: String? = null
    private var syncCooldownDeadlineElapsedMs = 0L
    private var backgroundedAtElapsedMs: Long? = null
    private var failedUnlockAttempts = 0
    private var unlockBlockedUntilElapsedMs = 0L

    // The notes list always shows every active note; search lives on the dedicated Search page
    // (driven by searchQuery) so browsing the list is never left in a filtered state.
    private val notes = repository.activeNotes
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

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
            message = null,
        )
    }

    private val navigation = combine(primaryNavigation, locked, syncing, sidebarOpen, message) { current, isLocked, isSyncing, isSidebarOpen, currentMessage ->
        current.copy(locked = isLocked, syncing = isSyncing, sidebarOpen = isSidebarOpen, message = currentMessage)
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
    ) { values ->
        @Suppress("UNCHECKED_CAST")
        WorkspaceDataState(
            taskBoards = values[0] as List<TaskBoardItem>,
            taskColumns = values[1] as List<TaskColumnItem>,
            tasks = values[2] as List<TaskItem>,
            taskPropertyDefinitions = values[3] as List<TaskPropertyDefinition>,
            taskPropertyValues = values[4] as List<TaskPropertyValue>,
            taskChecklistItems = values[5] as List<TaskChecklistItem>,
            chatMessages = values[6] as List<ChatMessageItem>,
            workspaceObjects = values[7] as List<WorkspaceObject>,
            workspaceObjectLinks = values[8] as List<WorkspaceObjectLink>,
            workspaceActivities = values[9] as List<WorkspaceActivity>,
            workspaceObjectHistory = values[10] as List<WorkspaceObjectHistory>,
            workspaceComments = values[11] as List<WorkspaceComment>,
            workspaceFiles = values[12] as List<WorkspaceFileItem>,
            workspaces = values[13] as List<Workspace>,
            goals = values[14] as List<GoalItem>,
            calendarEvents = values[15] as List<CalendarEventItem>,
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
            selectedNote = base.notes.firstOrNull { it.id == base.navigation.selectedNoteId } ?: base.notes.firstOrNull(),
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
            message = base.navigation.message,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DocsUiState())

    init {
        viewModelScope.launch {
            repository.ensureSeedData()
            repository.rebuildWorkspaceIndex()
        }
    }

    private val navigationHistory = ArrayDeque<Destination>()

    private fun navigateTo(next: Destination, recordHistory: Boolean = true) {
        val current = destination.value
        if (current == next) {
            sidebarOpen.value = false
            return
        }
        if (recordHistory) {
            if (navigationHistory.lastOrNull() != current) navigationHistory.addLast(current)
            while (navigationHistory.size > 32) navigationHistory.removeFirst()
        }
        destination.value = next
        sidebarOpen.value = false
    }

    private fun resetNavigation(next: Destination) {
        navigationHistory.clear()
        destination.value = next
        sidebarOpen.value = false
    }

    fun go(next: Destination) = navigateTo(next)

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
    fun consumeSettingsSection() { _pendingSettingsSection.value = null }
    fun toggleSidebar() { sidebarOpen.value = !sidebarOpen.value }
    fun closeSidebar() { sidebarOpen.value = false }
    fun selectTab(next: HomeTab) { tab.value = next }
    fun search(query: String) { searchQuery.value = query }
    fun filterByNotebook(notebookId: Long?) {
        selectedNotebookFilterId.value = notebookId
        selectedTagFilterId.value = null
        tab.value = HomeTab.AllNotes
        navigateTo(Destination.NotesHome)
    }
    fun filterByTag(tagId: Long?) {
        selectedTagFilterId.value = tagId
        selectedNotebookFilterId.value = null
        if (tagId != null) tab.value = HomeTab.Tags
        navigateTo(Destination.NotesHome)
    }
    fun select(note: Note) {
        selectedNoteId.value = note.id
        selectedObjectId.value = state.value.workspaceObjects.firstOrNull {
            it.objectType == WorkspaceObjectType.Note && it.sourceId == note.id
        }?.id
        navigateTo(Destination.NoteEditor)
    }
    fun clearMessage() { message.value = null }
    fun showMessage(value: String) { message.value = value }

    fun finishOnboarding(
        workspaceName: String,
        purpose: String,
        themeMode: ThemeMode,
        fullName: String = "",
        displayName: String = "",
        template: String = "Start empty",
        emailUpdates: Boolean = false,
        reminders: Boolean = true,
        avatarUri: String = "",
    ) = viewModelScope.launch {
        val cleanName = workspaceName.trim().ifBlank { "My Workspace" }
        val active = state.value.workspaces.firstOrNull { it.id == state.value.settings.activeWorkspaceId }
        if (active != null) repository.updateWorkspace(active.id, cleanName, cleanName.take(1).uppercase(), state.value.settings.themeProfile)
        repository.updateSettings(
            state.value.settings.copy(
                workspaceName = cleanName,
                workspaceIcon = cleanName.take(1).uppercase(),
                workspacePurpose = purpose,
                syncUserName = displayName.trim().ifBlank { fullName.trim().substringBefore(' ').lowercase().ifBlank { "owner" } },
                syncPublicName = fullName.trim().ifBlank { displayName.trim() },
                themeMode = themeMode,
                notificationEmail = emailUpdates,
                notificationPush = reminders,
                profileImageUri = avatarUri.takeIf { it.isNotBlank() },
                onboardingComplete = true,
            ),
        )
        repository.applyWorkspaceTemplate(template)
        resetNavigation(Destination.WorkspaceHub)
    }

    fun authenticateWithEmail(email: String, password: String, signUp: Boolean) = viewModelScope.launch {
        if (email.isBlank() || password.length < 8) {
            message.value = "Enter a valid email and a password with at least 8 characters"
            return@launch
        }
        runCatching {
            if (signUp) NorfoldSupabase.signUpWithEmail(email, password)
            else NorfoldSupabase.signInWithEmail(email, password)
        }.onSuccess {
            message.value = if (signUp) "Check your email to confirm the account" else "Signed in"
        }.onFailure {
            message.value = it.message ?: "Email authentication failed"
        }
    }

    fun createGoal(title: String) = viewModelScope.launch {
        repository.createGoal(title)
        message.value = "Goal created"
    }

    fun updateGoal(goal: GoalItem) = viewModelScope.launch { repository.updateGoal(goal) }
    fun deleteGoal(goal: GoalItem) = viewModelScope.launch { repository.deleteGoal(goal) }

    fun createCalendarEvent(title: String, startAt: Long, endAt: Long) = viewModelScope.launch {
        repository.createCalendarEvent(title, startAt, endAt)
        message.value = "Event created"
    }

    fun updateCalendarEvent(event: CalendarEventItem) = viewModelScope.launch { repository.updateCalendarEvent(event) }
    fun deleteCalendarEvent(event: CalendarEventItem) = viewModelScope.launch { repository.deleteCalendarEvent(event) }

    fun setDiagnosticsOptions(enabled: Boolean? = null, includeDeviceInfo: Boolean? = null, askBeforeSharing: Boolean? = null) {
        diagnostics.value = diagnosticsStore.update(enabled, includeDeviceInfo, askBeforeSharing)
    }

    fun acknowledgeCrashPrompt() {
        diagnostics.value = diagnosticsStore.acknowledgeCrashPrompt()
    }

    fun clearDiagnostics() {
        diagnostics.value = diagnosticsStore.clear()
        message.value = "Diagnostics cleared"
    }

    fun shareDiagnostics() {
        runCatching {
            diagnosticsStore.log("Diagnostics shared by user")
            val intent = Intent.createChooser(diagnosticsStore.shareIntent(), "Share Norfold diagnostics")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            app.startActivity(intent)
            diagnostics.value = diagnosticsStore.acknowledgeCrashPrompt()
        }.onFailure {
            message.value = it.message ?: "Could not share diagnostics"
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
            WorkspaceObjectType.ChatMessage -> go(Destination.Chat)
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
        message.value = "Comment added"
    }

    fun createNote() = viewModelScope.launch {
        selectedNoteId.value = repository.createNote(title = "Untitled doc", body = "")
        navigateTo(Destination.NoteEditor)
    }

    fun createTaskAndOpen() = viewModelScope.launch {
        repository.addTask("New task", assignee = state.value.settings.syncUserName.ifBlank { "@owner" })
        navigateTo(Destination.Tasks)
    }

    fun updateNote(note: Note, title: String, body: String) = viewModelScope.launch {
        repository.updateNote(note, title, body)
    }

    fun updateNoteDocument(note: Note, title: String, document: BlockDocument, dirtyBlockIds: Set<String>) = viewModelScope.launch {
        repository.updateNote(note, title, document, dirtyBlockIds)
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

    fun archive(note: Note) = viewModelScope.launch { repository.setArchived(note, true) }
    fun delete(note: Note) = viewModelScope.launch { repository.deleteNote(note) }

    fun addNotebook(name: String) = viewModelScope.launch { repository.addNotebook(name) }
    fun addTag(name: String) = viewModelScope.launch { repository.addTag(name) }

    fun renameTag(tag: Tag, name: String) = viewModelScope.launch {
        runCatching { repository.renameTag(tag, name) }
            .onSuccess { message.value = "Tag renamed" }
            .onFailure { message.value = it.message ?: "Could not rename tag" }
    }

    fun deleteTag(tag: Tag) = viewModelScope.launch {
        repository.deleteTag(tag)
        if (selectedTagFilterId.value == tag.id) selectedTagFilterId.value = null
        message.value = "Tag deleted and removed from Docs"
    }

    fun setNoteTags(note: Note, names: List<String>) = viewModelScope.launch {
        repository.setNoteTags(note, names)
        message.value = if (names.isEmpty()) "Tags removed" else "Tags updated"
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
        message.value = "Board created"
    }
    fun renameTaskBoard(boardId: Long, name: String) = viewModelScope.launch {
        repository.renameTaskBoard(boardId, name)
        message.value = "Board renamed"
    }
    fun createTaskColumn(boardId: Long, name: String) = viewModelScope.launch {
        repository.createTaskColumn(boardId, name)
        message.value = "Column added"
    }
    fun renameTaskColumn(column: TaskColumnItem, name: String) = viewModelScope.launch {
        repository.renameTaskColumn(column, name)
        message.value = "Column renamed"
    }
    fun moveTaskColumn(column: TaskColumnItem, delta: Int) = viewModelScope.launch {
        repository.moveTaskColumn(column, delta)
    }
    fun deleteTaskColumn(column: TaskColumnItem) = viewModelScope.launch {
        repository.deleteTaskColumn(column)
        message.value = "Column deleted"
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
        message.value = "Task updated"
    }
    fun createTaskProperty(boardId: Long, name: String, type: TaskPropertyType) = viewModelScope.launch {
        repository.createTaskProperty(boardId, name, type)
        message.value = "Property added"
    }
    fun updateTaskPropertyDefinition(property: TaskPropertyDefinition, name: String, type: TaskPropertyType, hiddenWhenEmpty: Boolean, optionsJson: String = property.optionsJson) = viewModelScope.launch {
        repository.updateTaskPropertyDefinition(property, name, type, hiddenWhenEmpty, optionsJson)
        message.value = "Property updated"
    }
    fun duplicateTaskProperty(property: TaskPropertyDefinition) = viewModelScope.launch {
        repository.duplicateTaskProperty(property)
        message.value = "Property duplicated"
    }
    fun deleteTaskProperty(property: TaskPropertyDefinition) = viewModelScope.launch {
        repository.deleteTaskProperty(property)
        message.value = "Property deleted"
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
            }.onSuccess { message.value = it }
                .onFailure { message.value = it.message ?: "Task attachment failed" }
        }
    }
    fun clearTaskAttachment(task: TaskItem) = viewModelScope.launch {
        repository.updateTaskAttachment(task, null, null, null, null)
        message.value = "Task attachment removed"
    }
    fun removeTaskFile(task: TaskItem, file: WorkspaceFileItem) = viewModelScope.launch {
        repository.removeTaskFile(task, file)
    }
    fun deleteTask(task: TaskItem) = viewModelScope.launch { repository.deleteTask(task) }
    fun sendChat(body: String) = viewModelScope.launch {
        if (body.isBlank()) return@launch
        val settings = state.value.settings
        repository.sendChat(
            username = settings.syncUserName.ifBlank { "you" },
            displayName = settings.syncPublicName.ifBlank { settings.syncUserName.ifBlank { "You" } },
            body = body.trim(),
            color = settings.accentColor,
        )
    }

    fun sendChatAttachment(uri: Uri?) {
        if (uri == null) return
        persistReadPermission(uri)
        viewModelScope.launch {
            val settings = state.value.settings
            runCatching {
                repository.sendChat(
                    username = settings.syncUserName.ifBlank { "you" },
                    displayName = settings.syncPublicName.ifBlank { settings.syncUserName.ifBlank { "You" } },
                    body = "Shared ${displayName(uri)}",
                    color = settings.accentColor,
                    attachmentName = displayName(uri),
                    attachmentMimeType = app.contentResolver.getType(uri) ?: "application/octet-stream",
                    attachmentUri = uri.toString(),
                    attachmentSizeBytes = displaySize(uri),
                )
                "File shared"
            }.onSuccess { message.value = it }
                .onFailure { message.value = it.message ?: "File share failed" }
        }
    }

    fun addWorkspaceFile(uri: Uri?) {
        if (uri == null) return
        persistReadPermission(uri)
        viewModelScope.launch {
            runCatching {
                repository.addWorkspaceFile(
                    displayName = displayName(uri),
                    mimeType = app.contentResolver.getType(uri) ?: "application/octet-stream",
                    uri = uri.toString(),
                    sizeBytes = displaySize(uri),
                )
                "File added to workspace"
            }.onSuccess { message.value = it }
                .onFailure { message.value = it.message ?: "File add failed" }
        }
    }

    fun rebuildWorkspaceIndex() = viewModelScope.launch {
        repository.rebuildWorkspaceIndex()
        message.value = "Workspace index rebuilt"
    }
    fun importMarkdown(uri: Uri?) {
        if (uri == null) return
        persistReadPermission(uri)
        viewModelScope.launch {
            runCatching {
                val body = app.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }.orEmpty()
                require(body.isNotBlank()) { "Markdown file is empty" }
                val title = displayName(uri).substringBeforeLast('.').ifBlank { "Imported Markdown" }
                selectedNoteId.value = repository.createNote(title = title, body = body, tagNames = listOf("Imported"))
                navigateTo(Destination.NoteEditor)
                "Markdown imported"
            }.onSuccess {
                message.value = it
            }.onFailure {
                message.value = it.message ?: "Markdown import failed"
            }
        }
    }

    fun attachFileToSelectedNote(uri: Uri?) {
        if (uri == null) return
        persistReadPermission(uri)
        viewModelScope.launch {
            val note = state.value.selectedNote
            if (note == null) {
                message.value = "Select a doc first"
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
                message.value = it
            }.onFailure {
                message.value = it.message ?: "Attachment failed"
            }
        }
    }

    fun addLinkEmbedToSelectedNote(target: String, title: String = target) = viewModelScope.launch {
        val note = state.value.selectedNote
        if (note == null) {
            message.value = "Select a doc first"
            return@launch
        }
        if (target.isBlank()) {
            message.value = "Link is empty"
            return@launch
        }
        repository.addNoteEmbed(note.id, NoteEmbedType.Link, title, target, "Embedded link")
        message.value = "Link embedded"
    }

    fun embedFileToSelectedNote(uri: Uri?) {
        if (uri == null) return
        persistReadPermission(uri)
        viewModelScope.launch {
            val note = state.value.selectedNote
            if (note == null) {
                message.value = "Select a doc first"
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
                message.value = it
            }.onFailure {
                message.value = it.message ?: "Embed failed"
            }
        }
    }

    fun setSelectedNoteCover(uri: Uri?) {
        if (uri == null) return
        persistReadPermission(uri)
        viewModelScope.launch {
            val note = state.value.selectedNote
            if (note == null) {
                message.value = "Select a doc first"
                return@launch
            }
            runCatching {
                repository.updateNoteCover(note, uri.toString(), app.contentResolver.getType(uri) ?: "application/octet-stream")
                "Cover updated"
            }.onSuccess { message.value = it }
                .onFailure { message.value = it.message ?: "Cover update failed" }
        }
    }

    fun clearSelectedNoteCover() = viewModelScope.launch {
        state.value.selectedNote?.let {
            repository.updateNoteCover(it, null, null)
            message.value = "Cover removed"
        }
    }

    fun createWorkspace(
        name: String,
        palette: ThemeProfile = state.value.settings.themeProfile,
        icon: String = name.trim().take(1).uppercase().ifBlank { "N" },
        iconKind: WorkspaceIconKind = WorkspaceIconKind.Text,
        iconUri: String? = null,
        backgroundUri: String? = null,
    ) = viewModelScope.launch {
        if (name.isBlank()) return@launch
        val id = repository.createWorkspace(name.trim(), icon, palette, iconKind, iconUri, backgroundUri)
        repository.setActiveWorkspace(id)
        repository.updateSettings(
            state.value.settings.copy(
                activeWorkspaceId = id,
                workspaceName = name.trim(),
                workspaceIcon = icon.ifBlank { "N" },
                workspaceIconKind = iconKind,
                workspaceIconUri = iconUri,
                workspaceBackgroundUri = backgroundUri,
            ),
        )
        resetNavigation(Destination.WorkspaceHub)
        message.value = "Workspace \"${name.trim()}\" created"
    }

    fun switchWorkspace(id: Long) = viewModelScope.launch {
        repository.setActiveWorkspace(id)
        selectedNoteId.value = null
        resetNavigation(Destination.WorkspaceHub)
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
        message.value = if (ok) "Workspace deleted" else "Can't delete your only workspace"
    }

    /** Generic persisted-settings patch so any row can save without a bespoke setter. */
    fun patchSettings(block: (com.norfold.app.domain.AppSettings) -> com.norfold.app.domain.AppSettings) = viewModelScope.launch {
        repository.updateSettings(block(state.value.settings))
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

    fun setUiScale(value: Float) = viewModelScope.launch {
        repository.updateSettings(state.value.settings.copy(uiScale = value.coerceIn(0.78f, 1.12f)))
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
            message.value = "Backup folder selected"
        }
    }

    fun updateProfile(username: String, publicName: String, deviceName: String) = viewModelScope.launch {
        val handle = username.trim().removePrefix("@").lowercase()
        if (!handle.matches(Regex("^[a-z][a-z0-9_]{2,29}$"))) {
            message.value = "Handle must be 3–30 characters, start with a letter, and use only letters, numbers, or underscores"
            return@launch
        }
        runCatching { NorfoldSupabase.claimProfileHandleIfSignedIn(handle, publicName.trim()) }
            .onSuccess { verifiedOnline ->
                repository.updateSettings(
                    state.value.settings.copy(
                        syncUserName = handle,
                        syncPublicName = publicName.trim().take(80),
                        syncDeviceName = deviceName.trim().ifBlank { "Android device" }.take(80),
                    ),
                )
                message.value = if (verifiedOnline) "Profile updated · @$handle is reserved" else "Profile updated locally · sign in to reserve @$handle"
            }
            .onFailure { error ->
                val detail = error.message.orEmpty()
                message.value = if (detail.contains("handle_taken", ignoreCase = true) || detail.contains("23505")) {
                    "@$handle is already taken"
                } else {
                    detail.ifBlank { "Could not verify that handle" }
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
        message.value = "Profile visuals updated"
    }

    fun connectGoogleDrive(accessToken: String?) {
        if (accessToken.isNullOrBlank()) {
            message.value = "Google Drive authorization did not return an access token"
            return
        }
        googleDriveAuthStore.saveAccessToken(accessToken)
        googleDriveConnected.value = true
        message.value = "Google Drive connected"
    }

    fun reportGoogleDriveAuthorizationFailure(error: Throwable?) {
        message.value = error?.localizedMessage ?: "Google Drive authorization cancelled"
    }

    fun disconnectGoogleDrive() {
        googleDriveAuthStore.clear()
        googleDriveConnected.value = false
        message.value = "Google Drive disconnected"
    }

    private fun displayName(uri: Uri): String {
        return app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
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

    private fun displaySize(uri: Uri): Long {
        return app.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
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
                message.value = it
            }.onFailure {
                message.value = it.message ?: "Sync chain failed"
            }
            syncing.value = false
        }
    }

    fun createGoogleDriveApiChain(secret: String, username: String, publicName: String, deviceName: String) = viewModelScope.launch {
        requireGoogleDriveSecret(secret) ?: return@launch
        syncing.value = true
        sessionSyncSecret = secret
        runCatching {
            val result = googleDriveApiSyncStore.createChain(
                secret = secret.toCharArray(),
                username = username,
                publicName = publicName,
                deviceName = deviceName,
                snapshot = repository.snapshot(),
            )
            repository.updateSettings(
                state.value.settings.copy(
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
                ),
            )
            conflictReport.value = null
            result.status
        }.onSuccess {
            message.value = it
        }.onFailure {
            message.value = googleDriveFailureMessage(it, "Google Drive sync failed")
        }
        syncing.value = false
    }

    fun restoreGoogleDriveApiChain(secret: String, username: String, publicName: String, deviceName: String) = viewModelScope.launch {
        requireGoogleDriveSecret(secret) ?: return@launch
        syncing.value = true
        sessionSyncSecret = secret
        runCatching {
            val result = googleDriveApiSyncStore.restoreChain(secret.toCharArray())
            repository.restore(result.snapshot)
            repository.updateSettings(
                state.value.settings.copy(
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
                ),
            )
            conflictReport.value = null
            result.writeResult.status
        }.onSuccess {
            message.value = it
        }.onFailure {
            message.value = googleDriveFailureMessage(it, "Google Drive restore failed")
        }
        syncing.value = false
    }

    fun syncNow(secret: String) = viewModelScope.launch {
        if (syncing.value) {
            message.value = "Sync is already running"
            return@launch
        }
        val now = SystemClock.elapsedRealtime()
        val remainingMs = syncCooldownDeadlineElapsedMs - now
        if (remainingMs > 0L) {
            message.value = "Please wait ${(remainingMs + 999L) / 1_000L}s before syncing again"
            return@launch
        }
        val current = state.value.settings
        val folder = current.syncFolderUri
        val chainId = current.syncChainId
        if (current.syncProvider == SyncProvider.None || folder.isNullOrBlank() || chainId.isNullOrBlank()) {
            message.value = "Create or restore a sync chain first"
            return@launch
        }
        sessionSyncSecret = secret
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
            repository.updateSettings(
                current.copy(
                    lastSyncAt = System.currentTimeMillis(),
                    lastSyncHash = result.snapshotHash,
                    lastSyncStatus = result.status,
                    syncConflictCount = result.conflictCount,
                ),
            )
            conflictReport.value = if (result.conflictCount > 0) readConflictReportFrom(current) else null
            result.status
        }.onSuccess {
            message.value = it
        }.onFailure {
            consumeCooldown = !it.isLikelyOfflineSyncFailure()
            message.value = if (current.syncProvider == SyncProvider.GoogleDrive) {
                googleDriveFailureMessage(it, "Google Drive sync failed")
            } else {
                it.message ?: "Sync failed"
            }
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
        return detail.ifBlank { fallback }
    }

    fun loadConflictReport() = viewModelScope.launch {
        runCatching {
            readConflictReportFrom(state.value.settings)
        }.onSuccess { report ->
            conflictReport.value = report
            message.value = if (report == null) "No conflict report found" else "Conflict report loaded"
        }.onFailure {
            message.value = it.message ?: "Could not load conflict report"
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
        message.value = "Local copy will be uploaded on next sync"
        navigateTo(Destination.SyncMonitor)
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
            message.value = if (state.value.settings.syncProvider == SyncProvider.None) {
                "Sync is not configured yet"
            } else {
                "Restore or reconnect this sync session before syncing"
            }
            return
        }
        if (!syncing.value) syncNow(secret)
    }

    private fun requireGoogleDriveSecret(secret: String): String? {
        if (!googleDriveConnected.value) {
            message.value = "Connect Google Drive first"
            return null
        }
        if (secret.isBlank()) {
            message.value = "Sync key is required"
            return null
        }
        return secret
    }

    fun setVaultSecret(secret: String) = viewModelScope.launch {
        if (secret.length < 6) {
            message.value = "Vault password must be at least 6 characters"
            return@launch
        }
        repository.setVaultSecret(secret.toCharArray())
        message.value = "Vault lock enabled"
    }

    fun disableVault() = viewModelScope.launch {
        BiometricVaultKeyStore(app).clear()
        repository.disableVaultLock()
        repository.updateSettings(state.value.settings.copy(vaultLockEnabled = false, vaultSecretHash = null, requireBiometricOnOpen = false))
        locked.value = false
        message.value = "Vault lock disabled"
    }

    fun lock() {
        if (state.value.settings.vaultLockEnabled) locked.value = true
    }

    fun onAppBackgrounded() {
        val settings = state.value.settings
        if (!settings.vaultLockEnabled) return
        backgroundedAtElapsedMs = SystemClock.elapsedRealtime()
        if (settings.appLockOnExit || settings.autoLockMinutes == 0) locked.value = true
    }

    fun onAppForegrounded() {
        val settings = state.value.settings
        val backgroundedAt = backgroundedAtElapsedMs ?: return
        backgroundedAtElapsedMs = null
        if (!settings.vaultLockEnabled || locked.value) return
        val timeoutMs = settings.autoLockMinutes.coerceAtLeast(0) * 60_000L
        if (settings.appLockOnExit || SystemClock.elapsedRealtime() - backgroundedAt >= timeoutMs) locked.value = true
    }

    fun handleBack() {
        when {
            locked.value -> locked.value = false
            sidebarOpen.value -> sidebarOpen.value = false
            searchQuery.value.isNotBlank() -> searchQuery.value = ""
            navigationHistory.isNotEmpty() -> navigateTo(navigationHistory.removeLast(), recordHistory = false)
            destination.value != Destination.WorkspaceHub -> resetNavigation(Destination.WorkspaceHub)
            else -> message.value = "Already at home"
        }
    }

    fun unlock(secret: String) {
        val now = SystemClock.elapsedRealtime()
        if (now < unlockBlockedUntilElapsedMs) {
            message.value = "Too many attempts. Try again in ${(unlockBlockedUntilElapsedMs - now + 999L) / 1_000L}s"
            return
        }
        val verified = VaultCrypto.verifySecret(secret.toCharArray(), state.value.settings.vaultSecretHash)
        locked.value = !verified
        if (verified) {
            failedUnlockAttempts = 0
            unlockBlockedUntilElapsedMs = 0L
            message.value = null
        } else {
            failedUnlockAttempts++
            if (failedUnlockAttempts >= 5) {
                failedUnlockAttempts = 0
                unlockBlockedUntilElapsedMs = now + UnlockFailureCooldownMs
                message.value = "Too many attempts. Vault unlock is paused for 30 seconds"
            } else {
                message.value = "Wrong password · ${5 - failedUnlockAttempts} attempts before a short pause"
            }
        }
    }

    fun unlockWithBiometric() {
        val settings = state.value.settings
        if (settings.vaultLockEnabled && settings.requireBiometricOnOpen) {
            locked.value = false
            message.value = null
        }
    }

    private companion object {
        const val SyncRequestCooldownMs = 15_000L
        const val UnlockFailureCooldownMs = 30_000L
    }

    fun exportBackup(secret: String, write: (String) -> Unit) = viewModelScope.launch {
        runCatching {
            require(secret.isNotBlank()) { "Backup password is required" }
            val payload = BackupCodec.encrypt(repository.snapshot(), secret.toCharArray())
            write(payload)
            val folder = state.value.settings.backupFolderUri
            if (!folder.isNullOrBlank()) {
                val fileName = backupStore.writeEncryptedBackup(Uri.parse(folder), payload)
                "Encrypted backup exported to $fileName"
            } else {
                "Encrypted backup exported"
            }
        }.onSuccess {
            message.value = it
        }.onFailure {
            message.value = it.message ?: "Backup export failed"
        }
    }

    fun importBackup(secret: String, payload: String) = viewModelScope.launch {
        runCatching {
            require(secret.isNotBlank()) { "Backup password is required" }
            require(payload.isNotBlank()) { "Backup payload is empty" }
            repository.restore(BackupCodec.decrypt(payload, secret.toCharArray()))
        }.onSuccess {
            message.value = "Backup restored"
        }.onFailure {
            message.value = it.message ?: "Backup restore failed"
        }
    }

    fun importBackupFromUri(secret: String, uri: Uri?) {
        if (uri == null) return
        viewModelScope.launch {
            runCatching {
                require(secret.isNotBlank()) { "Backup password is required" }
                val payload = app.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }.orEmpty()
                require(payload.isNotBlank()) { "Backup file is empty" }
                repository.restore(BackupCodec.decrypt(payload, secret.toCharArray()))
            }.onSuccess {
                message.value = "Backup restored"
            }.onFailure {
                message.value = it.message ?: "Backup restore failed"
            }
        }
    }
}
