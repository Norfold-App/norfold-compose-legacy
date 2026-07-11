package com.norfold.app.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.norfold.app.data.BackupFolderStore
import com.norfold.app.data.CloudFolderSyncStore
import com.norfold.app.data.DiagnosticsState
import com.norfold.app.data.DiagnosticsStore
import com.norfold.app.data.GoogleDriveApiSyncStore
import com.norfold.app.data.GoogleDriveAuthStore
import com.norfold.app.data.NorfoldDatabase
import com.norfold.app.data.NotesRepository
import com.norfold.app.domain.AppSettings
import com.norfold.app.domain.BackupCodec
import com.norfold.app.domain.CanvasEdgeItem
import com.norfold.app.domain.CanvasNodeItem
import com.norfold.app.domain.CanvasNodeType
import com.norfold.app.domain.ChatMessageItem
import com.norfold.app.domain.Destination
import com.norfold.app.domain.EditorMode
import com.norfold.app.domain.EditorFontFamily
import com.norfold.app.domain.EditorLineWidth
import com.norfold.app.domain.HomeTab
import com.norfold.app.domain.Note
import com.norfold.app.domain.NoteEmbedType
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

data class NotesUiState(
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
    val canvasNodes: List<CanvasNodeItem> = emptyList(),
    val canvasEdges: List<CanvasEdgeItem> = emptyList(),
    val workspaceObjects: List<WorkspaceObject> = emptyList(),
    val workspaceObjectLinks: List<WorkspaceObjectLink> = emptyList(),
    val workspaceActivities: List<WorkspaceActivity> = emptyList(),
    val workspaceObjectHistory: List<WorkspaceObjectHistory> = emptyList(),
    val workspaceComments: List<WorkspaceComment> = emptyList(),
    val workspaceFiles: List<WorkspaceFileItem> = emptyList(),
    val goals: List<GoalItem> = emptyList(),
    val calendarEvents: List<CalendarEventItem> = emptyList(),
    val workspaces: List<Workspace> = emptyList(),
    val settings: AppSettings = NotesRepository.defaultSettings,
    val selectedNote: Note? = null,
    val selectedObject: WorkspaceObject? = null,
    val destination: Destination = Destination.WorkspaceHub,
    val tab: HomeTab = HomeTab.AllNotes,
    val editorMode: EditorMode = EditorMode.Page,
    val searchQuery: String = "",
    val selectedNotebookId: Long? = null,
    val locked: Boolean = false,
    val syncing: Boolean = false,
    val googleDriveConnected: Boolean = false,
    val sidebarOpen: Boolean = false,
    val conflictReport: ParsedSyncConflictReport? = null,
    val diagnostics: DiagnosticsState = DiagnosticsState(),
    val message: String? = null,
)

private data class NavigationState(
    val selectedNoteId: Long?,
    val selectedObjectId: Long?,
    val destination: Destination,
    val tab: HomeTab,
    val editorMode: EditorMode,
    val searchQuery: String,
    val selectedNotebookId: Long?,
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
    val canvasNodes: List<CanvasNodeItem>,
    val canvasEdges: List<CanvasEdgeItem>,
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
class NotesViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application
    private val repository = NotesRepository(NorfoldDatabase.get(application))
    private val syncStore = CloudFolderSyncStore(application)
    private val googleDriveAuthStore = GoogleDriveAuthStore(application)
    private val googleDriveApiSyncStore = GoogleDriveApiSyncStore(application, googleDriveAuthStore)
    private val backupStore = BackupFolderStore(application)
    private val diagnosticsStore = DiagnosticsStore(application)
    private val destination = MutableStateFlow(Destination.WorkspaceHub)
    private val tab = MutableStateFlow(HomeTab.AllNotes)
    private val editorMode = MutableStateFlow(EditorMode.Page)
    private val searchQuery = MutableStateFlow("")
    private val selectedNotebookFilterId = MutableStateFlow<Long?>(null)
    private val selectedNoteId = MutableStateFlow<Long?>(null)
    private val selectedObjectId = MutableStateFlow<Long?>(null)
    private val locked = MutableStateFlow(false)
    private val syncing = MutableStateFlow(false)
    private val googleDriveConnected = MutableStateFlow(googleDriveAuthStore.hasAuthState())
    private val sidebarOpen = MutableStateFlow(false)
    private val _pendingSettingsSection = MutableStateFlow<String?>(null)
    val pendingSettingsSection: StateFlow<String?> = _pendingSettingsSection.asStateFlow()
    private val conflictReport = MutableStateFlow<ParsedSyncConflictReport?>(null)
    private val diagnostics = MutableStateFlow(diagnosticsStore.state())
    private val message = MutableStateFlow<String?>(null)
    private var sessionSyncSecret: String? = null

    // The notes list always shows every active note; search lives on the dedicated Search page
    // (driven by searchQuery) so browsing the list is never left in a filtered state.
    private val notes = repository.activeNotes
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val primaryNavigation = combine<Any?, NavigationState>(selectedNoteId, selectedObjectId, destination, tab, editorMode, searchQuery, selectedNotebookFilterId) { values ->
        @Suppress("UNCHECKED_CAST")
        val selectedId = values[0] as Long?
        val selectedObjId = values[1] as Long?
        val currentDestination = values[2] as Destination
        val currentTab = values[3] as HomeTab
        val currentEditorMode = values[4] as EditorMode
        val query = values[5] as String
        val currentNotebookFilter = values[6] as Long?
        NavigationState(
            selectedNoteId = selectedId,
            selectedObjectId = selectedObjId,
            destination = currentDestination,
            tab = currentTab,
            editorMode = currentEditorMode,
            searchQuery = query,
            selectedNotebookId = currentNotebookFilter,
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
        repository.canvasNodes,
        repository.canvasEdges,
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
            canvasNodes = values[7] as List<CanvasNodeItem>,
            canvasEdges = values[8] as List<CanvasEdgeItem>,
            workspaceObjects = values[9] as List<WorkspaceObject>,
            workspaceObjectLinks = values[10] as List<WorkspaceObjectLink>,
            workspaceActivities = values[11] as List<WorkspaceActivity>,
            workspaceObjectHistory = values[12] as List<WorkspaceObjectHistory>,
            workspaceComments = values[13] as List<WorkspaceComment>,
            workspaceFiles = values[14] as List<WorkspaceFileItem>,
            workspaces = values[15] as List<Workspace>,
            goals = values[16] as List<GoalItem>,
            calendarEvents = values[17] as List<CalendarEventItem>,
        )
    }

    val state = combine(baseState, workspaceData, googleDriveConnected, conflictReport, diagnostics) { base, workspace, isGoogleDriveConnected, currentConflictReport, diagnosticsState ->
        NotesUiState(
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
            canvasNodes = workspace.canvasNodes,
            canvasEdges = workspace.canvasEdges,
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
            editorMode = base.navigation.editorMode,
            searchQuery = base.navigation.searchQuery,
            selectedNotebookId = base.navigation.selectedNotebookId,
            locked = base.navigation.locked,
            syncing = base.navigation.syncing,
            googleDriveConnected = isGoogleDriveConnected,
            sidebarOpen = base.navigation.sidebarOpen,
            conflictReport = currentConflictReport,
            diagnostics = diagnosticsState,
            message = base.navigation.message,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), NotesUiState())

    init {
        viewModelScope.launch {
            repository.ensureSeedData()
            repository.rebuildWorkspaceIndex()
        }
    }

    fun go(next: Destination) { destination.value = next; sidebarOpen.value = false }

    fun goToProfile() { _pendingSettingsSection.value = "Profile"; go(Destination.Settings) }
    fun consumeSettingsSection() { _pendingSettingsSection.value = null }
    fun toggleSidebar() { sidebarOpen.value = !sidebarOpen.value }
    fun closeSidebar() { sidebarOpen.value = false }
    fun selectTab(next: HomeTab) { tab.value = next }
    fun setEditorMode(next: EditorMode) { editorMode.value = next }
    fun search(query: String) { searchQuery.value = query }
    fun filterByNotebook(notebookId: Long?) {
        selectedNotebookFilterId.value = notebookId
        tab.value = HomeTab.AllNotes
        destination.value = Destination.NotesHome
        sidebarOpen.value = false
    }
    fun select(note: Note) { selectedNoteId.value = note.id; selectedObjectId.value = state.value.workspaceObjects.firstOrNull { it.objectType == WorkspaceObjectType.Note && it.sourceId == note.id }?.id; destination.value = Destination.NoteEditor }
    fun clearMessage() { message.value = null }
    fun showMessage(value: String) { message.value = value }

    fun finishOnboarding(workspaceName: String, purpose: String, themeMode: ThemeMode) = viewModelScope.launch {
        val cleanName = workspaceName.trim().ifBlank { "My Workspace" }
        val active = state.value.workspaces.firstOrNull { it.id == state.value.settings.activeWorkspaceId }
        if (active != null) repository.updateWorkspace(active.id, cleanName, cleanName.take(1).uppercase(), state.value.settings.themeProfile)
        repository.updateSettings(
            state.value.settings.copy(
                workspaceName = cleanName,
                workspaceIcon = cleanName.take(1).uppercase(),
                workspacePurpose = purpose,
                themeMode = themeMode,
                onboardingComplete = true,
            ),
        )
        destination.value = Destination.WorkspaceHub
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
        destination.value = Destination.ObjectDetail
        sidebarOpen.value = false
    }

    fun openWorkspaceObjectSource(obj: WorkspaceObject) {
        selectedObjectId.value = obj.id
        when (obj.objectType) {
            WorkspaceObjectType.Note -> state.value.notes.firstOrNull { it.id == obj.sourceId }?.let { select(it) } ?: go(Destination.NotesHome)
            WorkspaceObjectType.Task -> go(Destination.Tasks)
            WorkspaceObjectType.Goal -> go(Destination.Goals)
            WorkspaceObjectType.CalendarEvent -> go(Destination.Calendar)
            WorkspaceObjectType.File -> go(Destination.Files)
            WorkspaceObjectType.Canvas -> go(Destination.Canvas)
            WorkspaceObjectType.ChatMessage -> go(Destination.Chat)
            WorkspaceObjectType.DatabaseRow -> go(Destination.Database)
            WorkspaceObjectType.Workspace, WorkspaceObjectType.System -> go(Destination.WorkspaceHub)
        }
    }

    fun openCanvasNodeObject(node: CanvasNodeItem) {
        val obj = state.value.workspaceObjects.firstOrNull {
            it.objectType == WorkspaceObjectType.Canvas && it.sourceId == node.id
        }
        if (obj != null) {
            openWorkspaceObject(obj)
        } else {
            message.value = "Canvas object is not indexed yet. Rebuild the workspace index."
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
        selectedNoteId.value = repository.createNote(title = "Untitled note", body = "# Untitled note\n\nStart writing...")
        destination.value = Destination.NoteEditor
    }

    fun createTaskAndOpen() = viewModelScope.launch {
        repository.addTask("New task", assignee = state.value.settings.syncUserName.ifBlank { "@owner" })
        destination.value = Destination.Tasks
    }

    fun createCanvasAndOpen() = viewModelScope.launch {
        addCanvasNode(CanvasNodeType.Text)
        destination.value = Destination.Canvas
    }

    fun updateNote(note: Note, title: String, body: String) = viewModelScope.launch {
        repository.updateNote(note, title, body)
    }

    fun togglePin(note: Note) = viewModelScope.launch { repository.setPinned(note) }
    fun toggleStar(note: Note) = viewModelScope.launch { repository.setStarred(note) }
    fun toggleLock(note: Note) = viewModelScope.launch { repository.setLocked(note) }
    fun archive(note: Note) = viewModelScope.launch { repository.setArchived(note, true) }
    fun delete(note: Note) = viewModelScope.launch { repository.deleteNote(note) }

    fun addNotebook(name: String) = viewModelScope.launch { repository.addNotebook(name) }
    fun addTag(name: String) = viewModelScope.launch { repository.addTag(name) }
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
    fun addCanvasNode(type: CanvasNodeType) = viewModelScope.launch {
        val count = state.value.canvasNodes.size
        val newId = repository.addCanvasNode(
            title = when (type) {
                CanvasNodeType.Text -> "Text block"
                CanvasNodeType.Note -> "Linked note"
                CanvasNodeType.File -> "File block"
                CanvasNodeType.Shape -> "Shape"
                CanvasNodeType.Link -> "Link"
                CanvasNodeType.Media -> "Media"
            },
            subtitle = "Embedded block",
            type = type,
            x = 0.16f + (count % 3) * 0.23f,
            y = 0.16f + (count % 5) * 0.13f,
            color = listOf(0xFF7E57FF, 0xFF4AADFF, 0xFF56CC98, 0xFFF276E2, 0xFFFFCF52)[count % 5],
            linkedNoteId = state.value.selectedNote?.id.takeIf { type == CanvasNodeType.Note },
        )
        state.value.canvasNodes.maxByOrNull { it.updatedAt }?.let { previous ->
            repository.addCanvasEdge(previous.id, newId, "link")
        }
    }

    fun addCanvasEdge(fromNodeId: Long, toNodeId: Long, label: String = "link") = viewModelScope.launch {
        runCatching {
            repository.addCanvasEdge(fromNodeId, toNodeId, label)
        }.onSuccess {
            message.value = "Canvas connection added"
        }.onFailure {
            message.value = it.message ?: "Connection failed"
        }
    }

    fun moveCanvasNode(node: CanvasNodeItem, x: Float, y: Float) = viewModelScope.launch {
        repository.moveCanvasNode(node, x, y)
    }

    fun updateCanvasNodeContent(node: CanvasNodeItem, title: String, subtitle: String) = viewModelScope.launch {
        repository.updateCanvasNodeContent(node, title, subtitle)
    }

    fun attachTargetToCanvasNode(node: CanvasNodeItem, uri: Uri?) {
        if (uri == null) return
        persistReadPermission(uri)
        viewModelScope.launch {
            repository.updateCanvasNodeTarget(
                node = node,
                uri = uri.toString(),
                mimeType = app.contentResolver.getType(uri) ?: "application/octet-stream",
                name = displayName(uri),
                sizeBytes = displaySize(uri),
            )
            message.value = "Canvas block attached"
        }
    }

    fun updateCanvasNodeTarget(node: CanvasNodeItem, uri: String?, mimeType: String?, name: String?, sizeBytes: Long?) = viewModelScope.launch {
        repository.updateCanvasNodeTarget(node, uri?.takeIf { it.isNotBlank() }, mimeType, name?.takeIf { it.isNotBlank() }, sizeBytes)
    }

    fun deleteCanvasNode(node: CanvasNodeItem) = viewModelScope.launch {
        repository.deleteCanvasNode(node)
    }

    fun deleteCanvasEdge(edge: CanvasEdgeItem) = viewModelScope.launch {
        repository.deleteCanvasEdge(edge)
        message.value = "Canvas connection removed"
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
                destination.value = Destination.NoteEditor
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
                message.value = "Select a note first"
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
            message.value = "Select a note first"
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
                message.value = "Select a note first"
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
                message.value = "Select a note first"
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
        destination.value = Destination.WorkspaceHub
        message.value = "Workspace \"${name.trim()}\" created"
    }

    fun switchWorkspace(id: Long) = viewModelScope.launch {
        repository.setActiveWorkspace(id)
        selectedNoteId.value = null
        destination.value = Destination.WorkspaceHub
        sidebarOpen.value = false
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
            w.permDeleteNotes, w.permEditNotes, w.permCreateCanvas, w.permManageTasks,
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
        repository.updateSettings(state.value.settings.copy(syncUserName = username, syncPublicName = publicName, syncDeviceName = deviceName))
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
            message.value = it.message ?: "Google Drive sync failed"
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
            message.value = it.message ?: "Google Drive restore failed"
        }
        syncing.value = false
    }

    fun syncNow(secret: String) = viewModelScope.launch {
        sessionSyncSecret = secret
        val current = state.value.settings
        val folder = current.syncFolderUri
        val chainId = current.syncChainId
        if (current.syncProvider == SyncProvider.None || folder.isNullOrBlank() || chainId.isNullOrBlank()) {
            message.value = "Create or restore a sync chain first"
            return@launch
        }
        syncing.value = true
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
            message.value = it.message ?: "Sync failed"
        }
        syncing.value = false
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
        destination.value = Destination.SyncMonitor
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
        val secret = sessionSyncSecret?.takeIf { it.isNotBlank() } ?: return
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
        repository.setVaultSecret(secret.toCharArray())
        message.value = "Vault lock enabled"
    }

    fun disableVault() = viewModelScope.launch {
        repository.disableVaultLock()
        locked.value = false
    }

    fun lock() {
        if (state.value.settings.vaultLockEnabled) locked.value = true
    }

    fun handleBack() {
        when {
            locked.value -> locked.value = false
            sidebarOpen.value -> sidebarOpen.value = false
            editorMode.value == EditorMode.Preview -> editorMode.value = EditorMode.Page
            editorMode.value == EditorMode.Edit -> editorMode.value = EditorMode.Page
            searchQuery.value.isNotBlank() -> searchQuery.value = ""
            destination.value != Destination.WorkspaceHub -> destination.value = Destination.WorkspaceHub
            else -> message.value = "Already at home"
        }
    }

    fun unlock(secret: String) {
        locked.value = !VaultCrypto.verifySecret(secret.toCharArray(), state.value.settings.vaultSecretHash)
        message.value = if (locked.value) "Wrong password" else null
    }

    fun unlockWithBiometric() {
        val settings = state.value.settings
        if (settings.vaultLockEnabled && settings.requireBiometricOnOpen) {
            locked.value = false
            message.value = null
        }
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
