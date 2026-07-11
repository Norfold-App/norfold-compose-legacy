package com.norfold.app.data

import com.norfold.app.domain.AppSettings
import com.norfold.app.domain.Attachment
import com.norfold.app.domain.BackupSnapshot
import com.norfold.app.domain.CanvasEdgeItem
import com.norfold.app.domain.CanvasNodeItem
import com.norfold.app.domain.CanvasNodeType
import com.norfold.app.domain.ChatMessageItem
import com.norfold.app.domain.Note
import com.norfold.app.domain.NoteEmbedType
import com.norfold.app.domain.Notebook
import com.norfold.app.domain.GoalItem
import com.norfold.app.domain.GoalStatus
import com.norfold.app.domain.CalendarEventItem
import com.norfold.app.domain.CalendarEventSource
import com.norfold.app.domain.Tag
import com.norfold.app.domain.TaskBoardItem
import com.norfold.app.domain.TaskColumnItem
import com.norfold.app.domain.TaskChecklistItem
import com.norfold.app.domain.TaskItem
import com.norfold.app.domain.TaskMoveNormalizer
import com.norfold.app.domain.TaskMoveRow
import com.norfold.app.domain.TaskPriority
import com.norfold.app.domain.TaskPropertyDefinition
import com.norfold.app.domain.TaskPropertyType
import com.norfold.app.domain.TaskDateRangeCodec
import com.norfold.app.domain.TaskPropertyValue
import com.norfold.app.domain.TaskStatus
import com.norfold.app.domain.TaskWorkspacePrefs
import com.norfold.app.domain.ThemeMode
import com.norfold.app.domain.ThemeProfile
import com.norfold.app.domain.SyncProvider
import com.norfold.app.domain.VaultCrypto
import com.norfold.app.domain.Workspace
import com.norfold.app.domain.WorkspaceActivity
import com.norfold.app.domain.WorkspaceActivityType
import com.norfold.app.domain.WorkspaceComment
import com.norfold.app.domain.WorkspaceFileItem
import com.norfold.app.domain.WorkspaceHistoryType
import com.norfold.app.domain.WorkspaceIconKind
import com.norfold.app.domain.WorkspaceLinkType
import com.norfold.app.domain.WorkspaceObject
import com.norfold.app.domain.WorkspaceObjectHistory
import com.norfold.app.domain.WorkspaceObjectLink
import com.norfold.app.domain.WorkspaceObjectType
import androidx.room.withTransaction
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlin.math.abs
import java.util.UUID
import org.json.JSONObject

@OptIn(ExperimentalCoroutinesApi::class)
class NotesRepository(private val database: NorfoldDatabase) {
    private val dao = database.dao()
    private val cloudSyncQueue = CloudSyncQueue(database)
    // Everything except tags/settings is scoped to the active workspace.
    private val activeWorkspaceId: Flow<Long> =
        dao.observeSettings().map { it?.activeWorkspaceId ?: 1L }.distinctUntilChanged()

    val workspaces: Flow<List<Workspace>> = dao.observeWorkspaces().map { rows -> rows.map { it.toDomain() } }
    val activeNotes: Flow<List<Note>> = activeWorkspaceId.flatMapLatest { ws -> dao.observeActiveNotes(ws).map { rows -> rows.map { it.toDomain() } } }
    val archivedNotes: Flow<List<Note>> = activeWorkspaceId.flatMapLatest { ws -> dao.observeArchivedNotes(ws).map { rows -> rows.map { it.toDomain() } } }
    val notebooks: Flow<List<Notebook>> = activeWorkspaceId.flatMapLatest { ws -> dao.observeNotebooks(ws).map { rows -> rows.map { it.toDomain() } } }
    val tags: Flow<List<Tag>> = dao.observeTags().map { rows -> rows.map { it.toDomain() } }
    val taskBoards: Flow<List<TaskBoardItem>> = activeWorkspaceId.flatMapLatest { ws -> dao.observeTaskBoards(ws).map { rows -> rows.map { it.toDomain() } } }
    val taskColumns: Flow<List<TaskColumnItem>> = activeWorkspaceId.flatMapLatest { ws -> dao.observeTaskColumns(ws).map { rows -> rows.map { it.toDomain() } } }
    val tasks: Flow<List<TaskItem>> = activeWorkspaceId.flatMapLatest { ws -> dao.observeTasks(ws).map { rows -> rows.map { it.toDomain() } } }
    val taskPropertyDefinitions: Flow<List<TaskPropertyDefinition>> = activeWorkspaceId.flatMapLatest { ws -> dao.observeTaskPropertyDefinitions(ws).map { rows -> rows.map { it.toDomain() } } }
    val taskPropertyValues: Flow<List<TaskPropertyValue>> = activeWorkspaceId.flatMapLatest { ws -> dao.observeTaskPropertyValues(ws).map { rows -> rows.map { it.toDomain() } } }
    val taskChecklistItems: Flow<List<TaskChecklistItem>> = activeWorkspaceId.flatMapLatest { ws -> dao.observeTaskChecklistItems(ws).map { rows -> rows.map { it.toDomain() } } }
    val chatMessages: Flow<List<ChatMessageItem>> = activeWorkspaceId.flatMapLatest { ws -> dao.observeChatMessages(ws).map { rows -> rows.map { it.toDomain() } } }
    val canvasNodes: Flow<List<CanvasNodeItem>> = activeWorkspaceId.flatMapLatest { ws -> dao.observeCanvasNodes(ws).map { rows -> rows.map { it.toDomain() } } }
    val canvasEdges: Flow<List<CanvasEdgeItem>> = activeWorkspaceId.flatMapLatest { ws -> dao.observeCanvasEdges(ws).map { rows -> rows.map { it.toDomain() } } }
    val workspaceObjects: Flow<List<WorkspaceObject>> = activeWorkspaceId.flatMapLatest { ws -> dao.observeWorkspaceObjects(ws).map { rows -> rows.map { it.toDomain() } } }
    val workspaceObjectLinks: Flow<List<WorkspaceObjectLink>> = activeWorkspaceId.flatMapLatest { ws -> dao.observeWorkspaceObjectLinks(ws).map { rows -> rows.map { it.toDomain() } } }
    val workspaceActivities: Flow<List<WorkspaceActivity>> = activeWorkspaceId.flatMapLatest { ws -> dao.observeWorkspaceActivities(ws).map { rows -> rows.map { it.toDomain() } } }
    val workspaceObjectHistory: Flow<List<WorkspaceObjectHistory>> = activeWorkspaceId.flatMapLatest { ws -> dao.observeWorkspaceObjectHistory(ws).map { rows -> rows.map { it.toDomain() } } }
    val workspaceComments: Flow<List<WorkspaceComment>> = activeWorkspaceId.flatMapLatest { ws -> dao.observeWorkspaceComments(ws).map { rows -> rows.map { it.toDomain() } } }
    val workspaceFiles: Flow<List<WorkspaceFileItem>> = activeWorkspaceId.flatMapLatest { ws -> dao.observeWorkspaceFiles(ws).map { rows -> rows.map { it.toDomain() } } }
    val goals: Flow<List<GoalItem>> = activeWorkspaceId.flatMapLatest { ws -> dao.observeGoals(ws).map { rows -> rows.map { it.toDomain() } } }
    val calendarEvents: Flow<List<CalendarEventItem>> = activeWorkspaceId.flatMapLatest { ws -> dao.observeCalendarEvents(ws).map { rows -> rows.map { it.toDomain() } } }
    val settings: Flow<AppSettings> = dao.observeSettings().map { it?.toDomain() ?: defaultSettings }

    private suspend fun activeWs(): Long = dao.settings()?.activeWorkspaceId ?: 1L

    suspend fun ensureSeedData() {
        if (dao.allWorkspaces().isNotEmpty()) {
            if (dao.settings() == null) dao.upsertSettings(AppSettingsEntity())
            ensureDefaultTaskBoard(activeWs())
            return
        }
        val wsId = dao.insertWorkspace(WorkspaceEntity(name = "Personal", icon = "N", iconKind = WorkspaceIconKind.Text.name, palette = ThemeProfile.Neon.name, createdAt = System.currentTimeMillis()))
        val existing = dao.settings()
        dao.upsertSettings((existing ?: AppSettingsEntity()).copy(activeWorkspaceId = wsId, workspaceName = "Personal"))
        ensureDefaultTaskBoard(wsId)
        val guide = dao.insertNotebook(NotebookEntity(name = "Guide", color = 0xFF7E57FF, sortOrder = 0))
        val work = dao.insertNotebook(NotebookEntity(name = "Work", color = 0xFF8B5CF6, sortOrder = 1))
        val personal = dao.insertNotebook(NotebookEntity(name = "Personal", color = 0xFF35A853, sortOrder = 2))
        val study = dao.insertNotebook(NotebookEntity(name = "Study", color = 0xFF42A5F5, sortOrder = 3))
        seedGuideNotes(guide)
        // A couple of light, realistic notes so Home feels lived-in.
        createNote("Daily Thoughts", "Gratitude turns what we have into enough.\n\nA quick scratch note — try pinning it, adding a #tag, or swiping it to see the gesture actions.", personal, listOf("Personal"), pinned = true)
        createNote(
            "Shopping List 🛒",
            "- [ ] Oats\n- [ ] Almond milk\n- [ ] Blueberries\n- [ ] Coffee\n- [ ] Olive oil\n\nChecklists like this render as tappable items in the editor.",
            personal,
            listOf("Personal"),
        )
        addTask("Draft project roadmap", "Outline milestones, owners, and launch notes", "@owner", TaskStatus.Todo)
        addTask("Polish Android workspace", "Review notes, tasks, canvas, and chat screens", "@owner", TaskStatus.Doing)
        addTask("Encrypted backup check", "Verify app data round-trip", "@team", TaskStatus.Done)
        createGoal("Finish the product redesign", target = 100.0, progress = 36.0, unit = "%")
        val eventStart = System.currentTimeMillis() + 86_400_000L
        createCalendarEvent("Design review", eventStart, eventStart + 3_600_000L, color = 0xFFE54CBD)
        sendChat("nadia", "Nadia", "Can we convert this message into a task?", 0xFF7E57FF)
        sendChat("you", "You", "Yes. Link it to the workspace note.", 0xFF4AADFF)
        sendChat("system", "System", "Task created: Polish mobile onboarding", 0xFF56CC98, system = true)
        val research = addCanvasNode("Research", "Embedded note", CanvasNodeType.Note, 0.12f, 0.14f, 0xFF7E57FF)
        val brief = addCanvasNode("PDF brief", "File block", CanvasNodeType.File, 0.58f, 0.12f, 0xFF4AADFF)
        val tasks = addCanvasNode("Task board", "Linked tasks", CanvasNodeType.Shape, 0.34f, 0.40f, 0xFF56CC98)
        val map = addCanvasNode("Mind map", "Shape group", CanvasNodeType.Shape, 0.66f, 0.58f, 0xFFF276E2)
        addCanvasEdge(research, tasks, "feeds")
        addCanvasEdge(tasks, map, "maps")
        addCanvasEdge(brief, map, "references")
        rebuildWorkspaceIndex()
    }

    /** In-depth, built-in reference notes that teach the app's features. Seeded once on first run. */
    private suspend fun seedGuideNotes(guide: Long) {
        createNote(
            "Welcome to Norfold",
            """
            # Welcome 👋

            Norfold is your private, local-first workspace: notes, tasks, files, canvas and chat, all in one place and kept on your device.

            This **Guide** notebook is a set of real reference notes. Open any of them to learn a feature in depth, then delete them whenever you like.

            **Start here**
            - 📝 Notes & the Editor
            - ✓ Tasks & the Kanban board
            - 🎨 Canvas & linking
            - 🔍 Search everything
            - 🔒 Sync, Vault & Backup
            - 🏷 Tags & Notebooks

            **Getting around**
            - Tap the ☰ button (top-left) or swipe from the left edge to open the sidebar.
            - The bottom bar switches between Home, Notes, Tasks, Canvas and Chat.
            - The **Search everything** bar on every page opens one global search.

            Everything here is yours. Nothing leaves your device unless you turn on sync.
            """.trimIndent(),
            guide,
            listOf("Guide", "Start here"),
            pinned = true,
            starred = true,
        )
        createNote(
            "📝 Notes & the Editor",
            """
            # Notes & the Editor

            Notes are written in **Markdown**, so formatting is just text.

            **Formatting basics**
            - `# Heading`, `## Subheading`
            - `**bold**`, `*italic*`, `` `code` ``
            - `- bullet` lists and `1.` numbered lists
            - `- [ ] todo` and `- [x] done` checklists
            - `> quote` blocks and `---` dividers

            **Organize a note**
            - **Pin** it to keep it at the top of Home.
            - **Star** favorites for quick access.
            - **Lock** a private note behind your vault.
            - Add a **cover image**, attachments, and embeds.
            - File it into a **notebook** and add **#tags**.

            **Gestures**
            - Swipe a note card left/right for quick actions (pin, star, archive, delete) — the actions are configurable in Settings › Editor.
            - Long-press a card for the full action sheet.

            Create a note anytime with the ➕ button.
            """.trimIndent(),
            guide,
            listOf("Guide", "Notes"),
        )
        createNote(
            "✓ Tasks & the Kanban board",
            """
            # Tasks & the Kanban board

            The Tasks page is a full project board.

            **Columns & cards**
            - Group work into columns (To do / Doing / Done — rename or add your own).
            - Each card carries a **priority**, **due date**, **assignee**, **labels** and an optional attachment.

            **Move things around**
            - **Long-press a card and drag** to reorder it within a column or move it to another column — drop it at any position.
            - Use the **Up / Down / Move** buttons for precise placement.

            **See it your way**
            Switch between **Board**, **List**, **Table**, **Calendar**, **Timeline** and **Chart** views from the view menu.

            **Filter & search**
            Filter by assignee, label or priority, and use the global search to find any task from anywhere.
            """.trimIndent(),
            guide,
            listOf("Guide", "Tasks"),
        )
        createNote(
            "🎨 Canvas & linking",
            """
            # Canvas & linking

            Canvas is a freeform space to think visually.

            **Nodes**
            Drop **text**, **note**, **file**, **shape**, **link** and **media** nodes anywhere and drag them to arrange your ideas.

            **Connections**
            Draw **edges** between nodes to show how things relate — feeds, references, maps to, and so on.

            Everything you place on the canvas is a real workspace object, so it shows up in Search and the Database view too.
            """.trimIndent(),
            guide,
            listOf("Guide", "Canvas"),
        )
        createNote(
            "🔍 Search everything",
            """
            # Search everything

            There is **one** search for the whole app. Open it from the **Search everything** bar on any page, or the Search item in the sidebar.

            **It finds**
            - 📝 Notes and their content
            - ✓ Tasks
            - 📁 Files
            - 🕘 Recent activity
            - ⚙️ Settings sections
            - 🏷 Tags
            - ⌘ Commands & destinations (Canvas, Graph, Chat, Notebooks…)

            Tap any result to jump straight to it. Browsing your notes is never left in a filtered state — search lives on its own page.
            """.trimIndent(),
            guide,
            listOf("Guide", "Search"),
        )
        createNote(
            "🔒 Sync, Vault & Backup",
            """
            # Sync, Vault & Backup

            Your data is **local-first** — it lives on your device by default.

            **Vault & lock**
            Lock private notes behind the vault. Manage security in Settings › Security.

            **Sync (optional)**
            Connect **Google Drive** in Settings › Sync to back up and sync across devices. If two edits collide, the **Conflict review** screen lets you compare and choose.

            **Backup & import/export**
            Export a full backup or individual notes as Markdown, and restore from a backup file in Settings › Backup & Import.

            You stay in control of where your data goes.
            """.trimIndent(),
            guide,
            listOf("Guide", "Sync"),
        )
        createNote(
            "🏷 Tags & Notebooks",
            """
            # Tags & Notebooks

            Two simple ways to keep things tidy.

            **Notebooks** are folders — a note lives in one notebook (like this **Guide** notebook).

            **Tags** are flexible labels — a note can have many. Add `#tags` to connect notes across notebooks.

            Filter Home by notebook, browse everything under **Tags** in the sidebar, and find any tag through global search.
            """.trimIndent(),
            guide,
            listOf("Guide", "Organize"),
        )
    }

    fun search(query: String): Flow<List<Note>> =
        if (query.isBlank()) activeNotes
        else activeWorkspaceId.flatMapLatest { ws -> dao.searchNotes(query.trim(), ws).map { rows -> rows.map { it.toDomain() } } }

    // ---- Workspaces ----
    suspend fun createWorkspace(
        name: String,
        icon: String = "N",
        palette: ThemeProfile = ThemeProfile.Neon,
        iconKind: WorkspaceIconKind = WorkspaceIconKind.Text,
        iconUri: String? = null,
        backgroundUri: String? = null,
    ): Long =
        dao.insertWorkspace(
            WorkspaceEntity(
                name = name.ifBlank { "Workspace" },
                icon = icon.ifBlank { "N" }.take(4),
                iconKind = iconKind.name,
                iconUri = iconUri?.takeIf { it.isNotBlank() },
                backgroundUri = backgroundUri?.takeIf { it.isNotBlank() },
                palette = palette.name,
                createdAt = System.currentTimeMillis(),
            ),
        )

    suspend fun updateWorkspace(
        id: Long,
        name: String,
        icon: String,
        palette: ThemeProfile,
        iconKind: WorkspaceIconKind = WorkspaceIconKind.Text,
        iconUri: String? = null,
        backgroundUri: String? = null,
    ) =
        dao.updateWorkspaceMeta(
            id,
            name.ifBlank { "Workspace" },
            icon.ifBlank { "N" }.take(4),
            iconKind.name,
            iconUri?.takeIf { it.isNotBlank() },
            backgroundUri?.takeIf { it.isNotBlank() },
            palette.name,
        )

    suspend fun updateWorkspacePermissions(
        id: Long,
        permRename: Boolean,
        permChangeIcon: Boolean,
        permInviteMembers: Boolean,
        permDeleteNotes: Boolean,
        permEditNotes: Boolean,
        permCreateCanvas: Boolean,
        permManageTasks: Boolean,
    ) = dao.updateWorkspacePermissions(id, permRename, permChangeIcon, permInviteMembers, permDeleteNotes, permEditNotes, permCreateCanvas, permManageTasks)

    suspend fun setActiveWorkspace(id: Long) {
        val current = dao.settings()?.toDomain() ?: defaultSettings
        updateSettings(current.copy(activeWorkspaceId = id))
    }

    /** Deletes a workspace and all of its content. Refuses to delete the last remaining workspace. */
    suspend fun deleteWorkspace(id: Long): Boolean {
        if (dao.allWorkspaces().size <= 1) return false
        dao.deleteNotesForWorkspace(id)
        dao.deleteNotebooksForWorkspace(id)
        dao.deleteTasksForWorkspace(id)
        dao.deleteChatForWorkspace(id)
        dao.deleteCanvasForWorkspace(id)
        dao.deleteCanvasEdgesForWorkspace(id)
        dao.deleteWorkspaceFilesForWorkspace(id)
        dao.deleteCalendarEventsForWorkspace(id)
        dao.deleteGoalsForWorkspace(id)
        dao.deleteSyncOutboxForWorkspace(id)
        dao.deleteSyncTombstonesForWorkspace(id)
        dao.deleteRemoteBindingsForWorkspace(id)
        dao.deleteWorkspaceActivitiesForWorkspace(id)
        dao.deleteWorkspaceObjectHistoryForWorkspace(id)
        dao.deleteWorkspaceCommentsForWorkspace(id)
        dao.deleteWorkspaceObjectLinksForWorkspace(id)
        dao.deleteWorkspaceObjectsForWorkspace(id)
        dao.deleteWorkspaceById(id)
        if (activeWs() == id) {
            dao.allWorkspaces().firstOrNull()?.let { setActiveWorkspace(it.id) }
        }
        return true
    }

    suspend fun noteById(id: Long): Note? = dao.noteById(id)?.toDomain()

    suspend fun createNote(
        title: String = "Untitled note",
        body: String = "",
        notebookId: Long? = null,
        tagNames: List<String> = emptyList(),
        pinned: Boolean = false,
        starred: Boolean = false,
    ): Long {
        val now = System.currentTimeMillis()
        val id = dao.insertNote(
            NoteEntity(
                title = title.ifBlank { "Untitled note" },
                bodyMarkdown = body,
                notebookId = notebookId,
                coverUri = null,
                coverMimeType = null,
                pinned = pinned,
                starred = starred,
                archived = false,
                locked = false,
                createdAt = now,
                updatedAt = now,
                workspaceId = activeWs(),
            ),
        )
        setTags(id, tagNames)
        val objectId = upsertWorkspaceObject(WorkspaceObjectType.Note, id, title.ifBlank { "Untitled note" }, body.take(160), tagNames.joinToString(","), "note", 0xFF9D6CFF, pinned)
        recordActivity(WorkspaceActivityType.Created, "You", "Created note", title.ifBlank { "Untitled note" }, objectType = WorkspaceObjectType.Note, sourceId = id)
        recordHistory(WorkspaceHistoryType.Created, objectId, "You", "Created note", afterValue = title.ifBlank { "Untitled note" })
        return id
    }

    suspend fun updateNote(note: Note, title: String, body: String) {
        // Targeted update preserves workspaceId, flags, etc.
        dao.updateNoteContent(note.id, title.ifBlank { "Untitled note" }, body, System.currentTimeMillis())
        val objectId = upsertWorkspaceObject(WorkspaceObjectType.Note, note.id, title.ifBlank { "Untitled note" }, body.take(160), note.tags.joinToString(",") { it.name }, "note", 0xFF9D6CFF, note.pinned)
        recordActivity(WorkspaceActivityType.Updated, "You", "Updated note", title.ifBlank { "Untitled note" }, objectType = WorkspaceObjectType.Note, sourceId = note.id)
        if (note.title != title || note.bodyMarkdown != body) {
            recordHistory(
                WorkspaceHistoryType.Updated,
                objectId,
                "You",
                "Updated note",
                beforeValue = note.bodyMarkdown.take(500),
                afterValue = body.take(500),
            )
        }
    }

    suspend fun updateNoteCover(note: Note, coverUri: String?, coverMimeType: String?) {
        dao.updateNoteCover(note.id, coverUri?.takeIf { it.isNotBlank() }, coverMimeType?.takeIf { it.isNotBlank() }, System.currentTimeMillis())
    }

    suspend fun addNotebook(name: String): Long {
        val order = dao.allNotebooks().size
        return dao.insertNotebook(NotebookEntity(name = name.ifBlank { "New notebook" }, color = 0xFF8B5CF6, sortOrder = order, workspaceId = activeWs()))
    }

    suspend fun addTag(name: String): Long = getOrCreateTag(name.trim().removePrefix("#")).id

    suspend fun addAttachment(noteId: Long, displayName: String, mimeType: String, uri: String, sizeBytes: Long) {
        dao.insertAttachment(AttachmentEntity(noteId = noteId, displayName = displayName, mimeType = mimeType, uri = uri, sizeBytes = sizeBytes))
        val objectId = upsertWorkspaceObject(WorkspaceObjectType.File, null, displayName, mimeType, "", "file", 0xFF4AADFF)
        dao.insertWorkspaceFile(WorkspaceFileEntity(objectId = objectId, displayName = displayName, mimeType = mimeType, uri = uri, sizeBytes = sizeBytes, createdAt = System.currentTimeMillis(), updatedAt = System.currentTimeMillis(), workspaceId = activeWs()))
        objectIdFor(WorkspaceObjectType.Note, noteId)?.let { noteObjectId ->
            insertObjectLinkIfMissing(noteObjectId, objectId, WorkspaceLinkType.Attachment, displayName)
        }
        recordActivity(WorkspaceActivityType.Uploaded, "You", "Attached file", displayName, objectId = objectId)
        recordHistory(WorkspaceHistoryType.Attachment, objectId, "You", "Attached file", afterValue = "$displayName\n$mimeType\n$uri")
    }

    suspend fun addNoteEmbed(noteId: Long, type: NoteEmbedType, title: String, target: String, preview: String = "") {
        dao.insertNoteEmbed(
            NoteEmbedEntity(
                noteId = noteId,
                type = type.name,
                title = title.ifBlank { target.take(48).ifBlank { "Embed" } },
                target = target,
                preview = preview,
                createdAt = System.currentTimeMillis(),
            ),
        )
        val objectType = when (type) {
            NoteEmbedType.Task -> WorkspaceObjectType.Task
            NoteEmbedType.Canvas -> WorkspaceObjectType.Canvas
            else -> WorkspaceObjectType.File
        }
        val linkedObjectId = upsertWorkspaceObject(objectType, null, title.ifBlank { target.take(48).ifBlank { "Embed" } }, preview.ifBlank { target }, "", type.name.lowercase(), 0xFF4AADFF)
        objectIdFor(WorkspaceObjectType.Note, noteId)?.let { noteObjectId ->
            insertObjectLinkIfMissing(noteObjectId, linkedObjectId, WorkspaceLinkType.Embed, title.ifBlank { type.name })
        }
    }

    suspend fun addTask(
        title: String,
        description: String = "",
        assignee: String = "",
        status: TaskStatus = TaskStatus.Todo,
        priority: TaskPriority = TaskPriority.Normal,
        dueAt: Long? = null,
    ): Long {
        val now = System.currentTimeMillis()
        val boardId = ensureDefaultTaskBoard(activeWs())
        val columnId = ensureTaskColumnForStatus(boardId, status)
        val order = dao.maxTaskSortOrder(boardId, columnId) + 1
        val id = dao.insertTask(
            TaskEntity(
                title = title.ifBlank { "New task" },
                description = description,
                assignee = assignee,
                status = status.name,
                priority = priority.name,
                dueAt = dueAt,
                labels = "",
                createdAt = now,
                updatedAt = now,
                workspaceId = activeWs(),
                taskBoardId = boardId,
                taskColumnId = columnId,
                sortOrder = order,
            ),
        )
        val objectId = upsertWorkspaceObject(WorkspaceObjectType.Task, id, title.ifBlank { "New task" }, description, "", "task", 0xFF56CC98)
        recordActivity(WorkspaceActivityType.Created, "You", "Created task", title.ifBlank { "New task" }, objectType = WorkspaceObjectType.Task, sourceId = id)
        recordHistory(WorkspaceHistoryType.Created, objectId, "You", "Created task", afterValue = title.ifBlank { "New task" })
        return id
    }

    suspend fun setTaskColor(task: TaskItem, colorArgb: Long?) {
        dao.updateTaskColor(task.id, colorArgb, System.currentTimeMillis())
    }
    suspend fun setTaskStatus(task: TaskItem, status: TaskStatus) {
        val boardId = task.taskBoardId.takeIf { it > 0 } ?: ensureDefaultTaskBoard(activeWs())
        val columnId = ensureTaskColumnForStatus(boardId, status)
        val order = dao.maxTaskSortOrder(boardId, columnId) + 1
        dao.setTaskColumn(task.id, boardId, columnId, status.name, order, System.currentTimeMillis())
        val objectId = upsertWorkspaceObject(WorkspaceObjectType.Task, task.id, task.title, task.description, task.labels, "task", 0xFF56CC98, pinned = false, archived = status == TaskStatus.Done)
        recordActivity(if (status == TaskStatus.Done) WorkspaceActivityType.Completed else WorkspaceActivityType.Updated, "You", "Moved task to ${status.name}", task.title, objectType = WorkspaceObjectType.Task, sourceId = task.id)
        recordHistory(WorkspaceHistoryType.Moved, objectId, "You", "Moved task", beforeValue = task.status.name, afterValue = status.name)
    }
    suspend fun moveTaskToColumn(task: TaskItem, column: TaskColumnItem) {
        val nextStatus = column.status ?: task.status
        val order = dao.maxTaskSortOrder(column.boardId, column.id) + 1
        dao.setTaskColumn(task.id, column.boardId, column.id, nextStatus.name, order, System.currentTimeMillis())
        val objectId = upsertWorkspaceObject(WorkspaceObjectType.Task, task.id, task.title, task.description, task.labels, "task", 0xFF56CC98, pinned = false, archived = nextStatus == TaskStatus.Done)
        recordActivity(if (nextStatus == TaskStatus.Done) WorkspaceActivityType.Completed else WorkspaceActivityType.Updated, "You", "Moved task to ${column.name}", task.title, objectType = WorkspaceObjectType.Task, sourceId = task.id)
        recordHistory(WorkspaceHistoryType.Moved, objectId, "You", "Moved task", beforeValue = task.taskColumnId?.toString().orEmpty(), afterValue = column.id.toString())
    }

    suspend fun moveTaskToColumnAt(task: TaskItem, column: TaskColumnItem, sortOrder: Int) {
        moveTaskToColumnAtIndex(task.id, column.id, sortOrder)
    }

    suspend fun moveTaskToColumnAtIndex(taskId: Long, targetColumnId: Long, targetIndex: Int) {
        data class MoveSummary(
            val task: TaskEntity,
            val targetColumn: TaskColumnEntity,
            val beforeValue: String,
            val afterValue: String,
            val nextStatus: TaskStatus,
        )

        val now = System.currentTimeMillis()
        var summary: MoveSummary? = null
        database.withTransaction {
            val task = dao.taskById(taskId) ?: return@withTransaction
            val targetColumn = dao.taskColumnById(targetColumnId) ?: return@withTransaction
            val sourceColumnId = task.taskColumnId
            val nextStatus = targetColumn.status?.let { value ->
                TaskStatus.entries.firstOrNull { it.name == value }
            } ?: TaskStatus.entries.firstOrNull { it.name == task.status } ?: TaskStatus.Todo
            val beforeValue = "${task.taskColumnId}:${task.sortOrder}:${task.status}"
            if (sourceColumnId != null && (sourceColumnId != targetColumn.id || task.taskBoardId != targetColumn.boardId)) {
                compactTaskColumnOrders(
                    boardId = task.taskBoardId,
                    columnId = sourceColumnId,
                    excludedTaskId = task.id,
                    now = now,
                )
            }
            val targetRows = dao.tasksForColumn(targetColumn.boardId, targetColumn.id)
            val insertionIndex = targetIndex.coerceIn(0, targetRows.count { it.id != task.id })
            val normalizedOrders = TaskMoveNormalizer
                .insertAt(targetRows.map { TaskMoveRow(it.id, it.sortOrder) }, task.id, insertionIndex)
                .associateBy { it.id }
            (targetRows.filterNot { it.id == task.id } + task).distinctBy { it.id }.forEach { row ->
                val index = normalizedOrders[row.id]?.sortOrder ?: return@forEach
                if (row.id == task.id) {
                    dao.setTaskColumn(row.id, targetColumn.boardId, targetColumn.id, nextStatus.name, index, now)
                } else if (row.sortOrder != index) {
                    dao.updateTaskOrder(row.id, index, now)
                }
            }
            summary = MoveSummary(
                task = task,
                targetColumn = targetColumn,
                beforeValue = beforeValue,
                afterValue = "${targetColumn.id}:$insertionIndex:${nextStatus.name}",
                nextStatus = nextStatus,
            )
        }
        val move = summary ?: return
        val objectId = upsertWorkspaceObject(
            WorkspaceObjectType.Task,
            move.task.id,
            move.task.title,
            move.task.description,
            move.task.labels,
            "task",
            0xFF56CC98,
            pinned = false,
            archived = move.nextStatus == TaskStatus.Done,
        )
        recordActivity(
            if (move.nextStatus == TaskStatus.Done) WorkspaceActivityType.Completed else WorkspaceActivityType.Updated,
            "You",
            "Moved task to ${move.targetColumn.name}",
            move.task.title,
            objectType = WorkspaceObjectType.Task,
            sourceId = move.task.id,
        )
        recordHistory(
            WorkspaceHistoryType.Moved,
            objectId,
            "You",
            "Moved task",
            beforeValue = move.beforeValue,
            afterValue = move.afterValue,
        )
    }

    suspend fun updateTaskOrder(task: TaskItem, sortOrder: Int) {
        dao.updateTaskOrder(task.id, sortOrder.coerceAtLeast(0), System.currentTimeMillis())
        task.taskColumnId?.let { normalizeTaskOrders(task.taskBoardId, it, task.id, sortOrder.coerceAtLeast(0)) }
    }

    private suspend fun normalizeTaskOrders(boardId: Long, columnId: Long, movedTaskId: Long, targetOrder: Int) {
        val rows = dao.tasksForColumn(boardId, columnId)
        val moved = rows.firstOrNull { it.id == movedTaskId } ?: return
        val normalized = rows
            .filterNot { it.id == movedTaskId }
            .toMutableList()
            .also { it.add(targetOrder.coerceIn(0, it.size), moved) }
        normalized.forEachIndexed { index, row ->
            if (row.sortOrder != index) dao.updateTaskOrder(row.id, index, System.currentTimeMillis())
        }
    }

    private suspend fun compactTaskColumnOrders(boardId: Long, columnId: Long, excludedTaskId: Long, now: Long) {
        val rows = dao.tasksForColumn(boardId, columnId)
        val normalizedOrders = TaskMoveNormalizer
            .compact(rows.map { TaskMoveRow(it.id, it.sortOrder) }, excludedTaskId)
            .associateBy { it.id }
        rows.forEach { row ->
                val index = normalizedOrders[row.id]?.sortOrder ?: return@forEach
                if (row.sortOrder != index) dao.updateTaskOrder(row.id, index, now)
        }
    }

    suspend fun addTaskToColumn(title: String, column: TaskColumnItem): Long =
        addTask(title, assignee = "@owner", status = column.status ?: TaskStatus.Todo).also { taskId ->
            val order = dao.maxTaskSortOrder(column.boardId, column.id) + 1
            dao.setTaskColumn(taskId, column.boardId, column.id, (column.status ?: TaskStatus.Todo).name, order, System.currentTimeMillis())
        }
    suspend fun createTaskBoard(name: String): Long {
        val ws = activeWs()
        val now = System.currentTimeMillis()
        val boardId = dao.insertTaskBoard(TaskBoardEntity(name = name.trim().ifBlank { "New board" }, workspaceId = ws, createdAt = now, updatedAt = now))
        createTaskColumn(boardId, "To do", TaskStatus.Todo, 0xFF9D7BFF, 0)
        createTaskColumn(boardId, "Doing", TaskStatus.Doing, 0xFF4FACFE, 1)
        createTaskColumn(boardId, "Done", TaskStatus.Done, 0xFF56CC98, 2)
        ensureDefaultTaskProperties(boardId)
        recordActivity(WorkspaceActivityType.Created, "You", "Created task board", name.trim().ifBlank { "New board" })
        return boardId
    }

    suspend fun renameTaskBoard(boardId: Long, name: String) {
        val nextName = name.trim().ifBlank { "Task board" }
        dao.updateTaskBoardName(boardId, nextName, System.currentTimeMillis())
        recordActivity(WorkspaceActivityType.Updated, "You", "Renamed task board", nextName)
    }
    suspend fun createTaskColumn(boardId: Long, name: String, status: TaskStatus? = null, color: Long = 0xFF9D7BFF, sortOrder: Int? = null): Long {
        val now = System.currentTimeMillis()
        val order = sortOrder ?: dao.taskColumnsForBoard(boardId).size
        return dao.insertTaskColumn(TaskColumnEntity(boardId = boardId, name = name.trim().ifBlank { "New column" }, status = status?.name, color = color, sortOrder = order, createdAt = now, updatedAt = now))
    }

    suspend fun renameTaskColumn(column: TaskColumnItem, name: String) {
        val nextName = name.trim().ifBlank { "New column" }
        dao.updateTaskColumnName(column.id, nextName, System.currentTimeMillis())
        recordActivity(WorkspaceActivityType.Updated, "You", "Renamed task column", nextName)
    }

    suspend fun moveTaskColumn(column: TaskColumnItem, delta: Int) {
        if (delta == 0) return
        val rows = dao.taskColumnsForBoard(column.boardId)
        val currentIndex = rows.indexOfFirst { it.id == column.id }
        if (currentIndex < 0) return
        val nextIndex = (currentIndex + delta).coerceIn(0, rows.lastIndex)
        if (nextIndex == currentIndex) return
        val normalized = rows.toMutableList().also { list ->
            val moved = list.removeAt(currentIndex)
            list.add(nextIndex, moved)
        }
        val now = System.currentTimeMillis()
        normalized.forEachIndexed { index, row ->
            if (row.sortOrder != index) dao.updateTaskColumnOrder(row.id, index, now)
        }
        recordActivity(WorkspaceActivityType.Updated, "You", "Reordered task column", column.name)
    }

    suspend fun deleteTaskColumn(column: TaskColumnItem) {
        val columns = dao.taskColumnsForBoard(column.boardId)
        if (columns.size <= 1) return
        val target = columns
            .filterNot { it.id == column.id }
            .minByOrNull { kotlin.math.abs(it.sortOrder - column.sortOrder) }
            ?: return
        val targetStatus = target.status?.let { TaskStatus.valueOf(it) } ?: column.status ?: TaskStatus.Todo
        var nextOrder = dao.maxTaskSortOrder(target.boardId, target.id) + 1
        dao.tasksForColumn(column.boardId, column.id).forEach { task ->
            dao.setTaskColumn(task.id, target.boardId, target.id, targetStatus.name, nextOrder++, System.currentTimeMillis())
        }
        dao.deleteTaskColumn(column.id)
        dao.taskColumnsForBoard(column.boardId).forEachIndexed { index, row ->
            if (row.sortOrder != index) dao.updateTaskColumnOrder(row.id, index, System.currentTimeMillis())
        }
        recordActivity(WorkspaceActivityType.Updated, "You", "Deleted task column", column.name)
    }

    private suspend fun ensureDefaultTaskBoard(workspaceId: Long): Long {
        val existing = dao.taskBoardsForWorkspace(workspaceId).firstOrNull()
        if (existing != null) {
            ensureDefaultTaskColumns(existing.id)
            ensureDefaultTaskProperties(existing.id)
            return existing.id
        }
        val now = System.currentTimeMillis()
        val boardId = dao.insertTaskBoard(TaskBoardEntity(name = "Default board", workspaceId = workspaceId, createdAt = now, updatedAt = now))
        ensureDefaultTaskColumns(boardId)
        ensureDefaultTaskProperties(boardId)
        return boardId
    }

    private suspend fun ensureDefaultTaskColumns(boardId: Long) {
        if (dao.taskColumnsForBoard(boardId).isNotEmpty()) return
        createTaskColumn(boardId, "To do", TaskStatus.Todo, 0xFF9D7BFF, 0)
        createTaskColumn(boardId, "Doing", TaskStatus.Doing, 0xFF4FACFE, 1)
        createTaskColumn(boardId, "Done", TaskStatus.Done, 0xFF56CC98, 2)
    }

    private suspend fun ensureDefaultTaskProperties(boardId: Long) {
        if (dao.taskPropertyDefinitionsForBoard(boardId).isNotEmpty()) return
        val now = System.currentTimeMillis()
        DefaultTaskProperties.forEachIndexed { index, (name, type) ->
            dao.insertTaskPropertyDefinition(
                TaskPropertyDefinitionEntity(
                    boardId = boardId,
                    name = name,
                    type = type.name,
                    sortOrder = index,
                    hiddenWhenEmpty = false,
                    optionsJson = "",
                    createdAt = now,
                    updatedAt = now,
                ),
            )
        }
    }

    private suspend fun ensureTaskColumnForStatus(boardId: Long, status: TaskStatus): Long {
        dao.taskColumnForStatus(boardId, status.name)?.let { return it.id }
        return createTaskColumn(boardId, status.name, status, when (status) {
            TaskStatus.Todo -> 0xFF9D7BFF
            TaskStatus.Doing -> 0xFF4FACFE
            TaskStatus.Done -> 0xFF56CC98
        })
    }
    suspend fun updateTaskMeta(task: TaskItem, priority: TaskPriority, dueAt: Long?, assignee: String) =
        dao.updateTaskMeta(task.id, priority.name, dueAt, assignee, System.currentTimeMillis())
    suspend fun updateTaskDetails(task: TaskItem, title: String, description: String, status: TaskStatus, priority: TaskPriority, dueAt: Long?, assignee: String, labels: String) =
        dao.updateTaskDetails(
            id = task.id,
            title = title.ifBlank { "Untitled task" },
            description = description,
            status = status.name,
            priority = priority.name,
            dueAt = dueAt,
            assignee = assignee.ifBlank { "@owner" },
            labels = labels.split(",").map { it.trim().removePrefix("#") }.filter { it.isNotBlank() }.distinct().joinToString(","),
            attachmentName = task.attachmentName,
            attachmentMimeType = task.attachmentMimeType,
            attachmentUri = task.attachmentUri,
            attachmentSizeBytes = task.attachmentSizeBytes,
            updatedAt = System.currentTimeMillis(),
        )
    suspend fun updateTaskAttachment(task: TaskItem, name: String?, mimeType: String?, uri: String?, sizeBytes: Long?) =
        dao.updateTaskAttachment(task.id, name, mimeType, uri, sizeBytes, System.currentTimeMillis()).also {
            if (!name.isNullOrBlank() && !uri.isNullOrBlank()) {
                val now = System.currentTimeMillis()
                val taskObjectId = upsertWorkspaceObject(WorkspaceObjectType.Task, task.id, task.title, task.description, task.labels, "task", 0xFF56CC98)
                val fileObjectId = upsertWorkspaceObject(WorkspaceObjectType.File, null, name, mimeType.orEmpty(), "", "file", 0xFF4AADFF)
                insertObjectLinkIfMissing(taskObjectId, fileObjectId, WorkspaceLinkType.Attachment, name)
                dao.insertWorkspaceFile(WorkspaceFileEntity(objectId = taskObjectId, displayName = name, mimeType = mimeType.orEmpty(), uri = uri, sizeBytes = sizeBytes ?: 0, createdAt = now, updatedAt = now, workspaceId = activeWs()))
                recordActivity(WorkspaceActivityType.Uploaded, "You", "Linked task file", name, objectId = taskObjectId)
                recordHistory(WorkspaceHistoryType.Attachment, taskObjectId, "You", "Linked task file", afterValue = "$name\n${mimeType.orEmpty()}\n$uri")
            }
        }

    suspend fun removeTaskFile(task: TaskItem, file: WorkspaceFileItem) {
        dao.deleteWorkspaceFile(file.id)
        if (task.attachmentUri == file.uri) dao.updateTaskAttachment(task.id, null, null, null, null, System.currentTimeMillis())
        objectIdFor(WorkspaceObjectType.Task, task.id)?.let { objectId ->
            recordHistory(WorkspaceHistoryType.Attachment, objectId, "You", "Removed task file", beforeValue = file.displayName)
        }
    }

    suspend fun createTaskProperty(boardId: Long, name: String, type: TaskPropertyType): Long {
        val now = System.currentTimeMillis()
        val order = dao.taskPropertyDefinitionsForBoard(boardId).maxOfOrNull { it.sortOrder }?.plus(1) ?: 0
        val id = dao.insertTaskPropertyDefinition(
            TaskPropertyDefinitionEntity(
                boardId = boardId,
                name = name.trim().ifBlank { type.defaultLabel() },
                type = type.name,
                sortOrder = order,
                hiddenWhenEmpty = false,
                optionsJson = "",
                createdAt = now,
                updatedAt = now,
            ),
        )
        recordActivity(WorkspaceActivityType.Created, "You", "Created task property", name.trim().ifBlank { type.defaultLabel() })
        return id
    }

    suspend fun updateTaskPropertyDefinition(property: TaskPropertyDefinition, name: String, type: TaskPropertyType, hiddenWhenEmpty: Boolean, optionsJson: String = property.optionsJson) {
        dao.updateTaskPropertyDefinition(
            id = property.id,
            name = name.trim().ifBlank { type.defaultLabel() },
            type = type.name,
            hiddenWhenEmpty = hiddenWhenEmpty,
            optionsJson = optionsJson,
            updatedAt = System.currentTimeMillis(),
        )
        recordActivity(WorkspaceActivityType.Updated, "You", "Updated task property", name.trim().ifBlank { type.defaultLabel() })
    }

    suspend fun duplicateTaskProperty(property: TaskPropertyDefinition): Long =
        createTaskProperty(property.boardId, "${property.name} copy", property.type)

    suspend fun deleteTaskProperty(property: TaskPropertyDefinition) {
        dao.deleteTaskPropertyValuesForDefinition(property.id)
        dao.deleteTaskChecklistItemsForDefinition(property.id)
        dao.deleteTaskPropertyDefinition(property.id)
        normalizeTaskPropertyOrders(property.boardId)
        recordActivity(WorkspaceActivityType.Updated, "You", "Deleted task property", property.name)
    }

    suspend fun reorderTaskProperty(property: TaskPropertyDefinition, delta: Int) {
        val properties = dao.taskPropertyDefinitionsForBoard(property.boardId).sortedWith(compareBy<TaskPropertyDefinitionEntity> { it.sortOrder }.thenBy { it.id }).toMutableList()
        val from = properties.indexOfFirst { it.id == property.id }
        if (from < 0) return
        val to = (from + delta).coerceIn(0, properties.lastIndex)
        if (from == to) return
        val row = properties.removeAt(from)
        properties.add(to, row)
        val now = System.currentTimeMillis()
        properties.forEachIndexed { index, entity ->
            if (entity.sortOrder != index) dao.updateTaskPropertyDefinitionOrder(entity.id, index, now)
        }
    }

    suspend fun reorderTaskPropertyToIndex(property: TaskPropertyDefinition, targetIndex: Int) {
        val properties = dao.taskPropertyDefinitionsForBoard(property.boardId).sortedWith(compareBy<TaskPropertyDefinitionEntity> { it.sortOrder }.thenBy { it.id }).toMutableList()
        val from = properties.indexOfFirst { it.id == property.id }
        if (from < 0) return
        val to = targetIndex.coerceIn(0, properties.lastIndex)
        if (from == to) return
        val row = properties.removeAt(from)
        properties.add(to, row)
        val now = System.currentTimeMillis()
        properties.forEachIndexed { index, entity ->
            if (entity.sortOrder != index) dao.updateTaskPropertyDefinitionOrder(entity.id, index, now)
        }
    }

    suspend fun setTaskPropertyValue(task: TaskItem, property: TaskPropertyDefinition, value: String) {
        val now = System.currentTimeMillis()
        val existing = dao.taskPropertyValue(task.id, property.id)
        if (existing == null) {
            dao.insertTaskPropertyValue(TaskPropertyValueEntity(taskId = task.id, propertyId = property.id, valueJson = value, updatedAt = now))
        } else {
            dao.updateTaskPropertyValue(existing.id, value, now)
        }
        syncBuiltInTaskProjection(task, property.type, value, now)
        val objectId = upsertWorkspaceObject(WorkspaceObjectType.Task, task.id, nextTitleForProperty(task, property.type, value), nextSummaryForProperty(task, property.type, value), task.labels, "task", 0xFF56CC98)
        recordActivity(WorkspaceActivityType.Updated, "You", "Updated ${property.name}", nextTitleForProperty(task, property.type, value), objectType = WorkspaceObjectType.Task, sourceId = task.id, objectId = objectId)
        recordHistory(WorkspaceHistoryType.Updated, objectId, "You", "Updated ${property.name}", afterValue = value.take(500))
    }

    suspend fun addChecklistItem(task: TaskItem, property: TaskPropertyDefinition, text: String): Long {
        val now = System.currentTimeMillis()
        val order = dao.checklistItemsForProperty(task.id, property.id).maxOfOrNull { it.sortOrder }?.plus(1) ?: 0
        val id = dao.insertTaskChecklistItem(
            TaskChecklistItemEntity(
                taskId = task.id,
                propertyId = property.id,
                text = text.trim().ifBlank { "New item" },
                checked = false,
                sortOrder = order,
                createdAt = now,
                updatedAt = now,
            ),
        )
        recordActivity(WorkspaceActivityType.Updated, "You", "Added checklist item", task.title, objectType = WorkspaceObjectType.Task, sourceId = task.id)
        return id
    }

    suspend fun updateChecklistItem(item: TaskChecklistItem, text: String, checked: Boolean) {
        dao.updateTaskChecklistItem(item.id, text.trim().ifBlank { "Checklist item" }, checked, System.currentTimeMillis())
    }

    suspend fun moveChecklistItem(item: TaskChecklistItem, delta: Int) {
        val items = dao.checklistItemsForProperty(item.taskId, item.propertyId).sortedWith(compareBy<TaskChecklistItemEntity> { it.sortOrder }.thenBy { it.id }).toMutableList()
        val from = items.indexOfFirst { it.id == item.id }
        if (from < 0) return
        val to = (from + delta).coerceIn(0, items.lastIndex)
        if (from == to) return
        val row = items.removeAt(from)
        items.add(to, row)
        val now = System.currentTimeMillis()
        items.forEachIndexed { index, entity ->
            if (entity.sortOrder != index) dao.updateTaskChecklistItemOrder(entity.id, index, now)
        }
    }

    suspend fun moveChecklistItemToIndex(item: TaskChecklistItem, targetIndex: Int) {
        val items = dao.checklistItemsForProperty(item.taskId, item.propertyId).sortedWith(compareBy<TaskChecklistItemEntity> { it.sortOrder }.thenBy { it.id }).toMutableList()
        val from = items.indexOfFirst { it.id == item.id }
        if (from < 0) return
        val to = targetIndex.coerceIn(0, items.lastIndex)
        if (from == to) return
        val row = items.removeAt(from)
        items.add(to, row)
        val now = System.currentTimeMillis()
        items.forEachIndexed { index, entity ->
            if (entity.sortOrder != index) dao.updateTaskChecklistItemOrder(entity.id, index, now)
        }
    }

    suspend fun deleteChecklistItem(item: TaskChecklistItem) {
        dao.deleteTaskChecklistItem(item.id)
        val now = System.currentTimeMillis()
        dao.checklistItemsForProperty(item.taskId, item.propertyId).forEachIndexed { index, entity ->
            if (entity.sortOrder != index) dao.updateTaskChecklistItemOrder(entity.id, index, now)
        }
    }

    suspend fun deleteTask(task: TaskItem) = dao.deleteTask(task.id)

    private suspend fun normalizeTaskPropertyOrders(boardId: Long) {
        val now = System.currentTimeMillis()
        dao.taskPropertyDefinitionsForBoard(boardId).sortedWith(compareBy<TaskPropertyDefinitionEntity> { it.sortOrder }.thenBy { it.id }).forEachIndexed { index, entity ->
            if (entity.sortOrder != index) dao.updateTaskPropertyDefinitionOrder(entity.id, index, now)
        }
    }

    private suspend fun syncBuiltInTaskProjection(task: TaskItem, type: TaskPropertyType, value: String, now: Long) {
        when (type) {
            TaskPropertyType.Name -> dao.updateTaskDetails(task.id, value.ifBlank { "Untitled task" }, task.description, task.status.name, task.priority.name, task.dueAt, task.assignee, task.labels, task.attachmentName, task.attachmentMimeType, task.attachmentUri, task.attachmentSizeBytes, now)
            TaskPropertyType.Text -> dao.updateTaskDetails(task.id, task.title, value, task.status.name, task.priority.name, task.dueAt, task.assignee, task.labels, task.attachmentName, task.attachmentMimeType, task.attachmentUri, task.attachmentSizeBytes, now)
            TaskPropertyType.Status -> TaskStatus.entries.firstOrNull { it.name.equals(value, true) }?.let { status -> setTaskStatus(task, status) }
            TaskPropertyType.DueDate, TaskPropertyType.Date -> TaskDateRangeCodec.decode(value, task.dueAt).let { range ->
                dao.updateTaskDateRange(task.id, range.startAt, range.endAt, range.allDay, range.reminderMinutesBefore, now)
            }
            TaskPropertyType.Assignee, TaskPropertyType.Person -> dao.updateTaskMeta(task.id, task.priority.name, task.dueAt, value.ifBlank { "@owner" }, now)
            TaskPropertyType.Labels, TaskPropertyType.Multiselect -> dao.updateTaskDetails(task.id, task.title, task.description, task.status.name, task.priority.name, task.dueAt, task.assignee, value, task.attachmentName, task.attachmentMimeType, task.attachmentUri, task.attachmentSizeBytes, now)
            TaskPropertyType.Priority -> TaskPriority.entries.firstOrNull { it.name.equals(value, true) }?.let { priority -> dao.updateTaskMeta(task.id, priority.name, task.dueAt, task.assignee, now) }
            TaskPropertyType.FilesMedia -> dao.updateTaskAttachment(task.id, value.takeIf { it.isNotBlank() }, task.attachmentMimeType, task.attachmentUri, task.attachmentSizeBytes, now)
            else -> Unit
        }
    }

    private fun nextTitleForProperty(task: TaskItem, type: TaskPropertyType, value: String): String =
        if (type == TaskPropertyType.Name) value.ifBlank { "Untitled task" } else task.title

    private fun nextSummaryForProperty(task: TaskItem, type: TaskPropertyType, value: String): String =
        if (type == TaskPropertyType.Text) value else task.description

    suspend fun sendChat(
        username: String,
        displayName: String,
        body: String,
        color: Long = 0xFF7E57FF,
        system: Boolean = false,
        attachmentName: String? = null,
        attachmentMimeType: String? = null,
        attachmentUri: String? = null,
        attachmentSizeBytes: Long? = null,
    ): Long {
        val id = dao.insertChatMessage(
            ChatMessageEntity(
                authorUsername = username.ifBlank { "you" },
                authorDisplayName = displayName.ifBlank { username.ifBlank { "You" } },
                body = body,
                color = color,
                createdAt = System.currentTimeMillis(),
                system = system,
                attachmentName = attachmentName,
                attachmentMimeType = attachmentMimeType,
                attachmentUri = attachmentUri,
                attachmentSizeBytes = attachmentSizeBytes,
                workspaceId = activeWs(),
            ),
        )
        val chatObjectId = upsertWorkspaceObject(WorkspaceObjectType.ChatMessage, id, displayName.ifBlank { username.ifBlank { "Message" } }, body.take(160), "", "chat", color)
        recordActivity(if (attachmentName != null) WorkspaceActivityType.Uploaded else WorkspaceActivityType.Commented, displayName.ifBlank { username.ifBlank { "You" } }, if (attachmentName != null) "Shared file in chat" else "Sent chat message", body.take(80).ifBlank { attachmentName.orEmpty() }, objectType = WorkspaceObjectType.ChatMessage, sourceId = id)
        recordHistory(WorkspaceHistoryType.Comment, chatObjectId, displayName.ifBlank { username.ifBlank { "You" } }, "Sent chat message", afterValue = body.take(500).ifBlank { attachmentName.orEmpty() })
        if (!attachmentUri.isNullOrBlank() && !attachmentName.isNullOrBlank()) {
            val objectId = upsertWorkspaceObject(WorkspaceObjectType.File, null, attachmentName, attachmentMimeType.orEmpty(), "", "file", 0xFF4AADFF)
            dao.insertWorkspaceFile(WorkspaceFileEntity(objectId = objectId, displayName = attachmentName, mimeType = attachmentMimeType.orEmpty(), uri = attachmentUri, sizeBytes = attachmentSizeBytes ?: 0, createdAt = System.currentTimeMillis(), updatedAt = System.currentTimeMillis(), workspaceId = activeWs()))
            recordHistory(WorkspaceHistoryType.Attachment, objectId, displayName.ifBlank { username.ifBlank { "You" } }, "Shared chat file", afterValue = "$attachmentName\n${attachmentMimeType.orEmpty()}\n$attachmentUri")
        }
        return id
    }

    suspend fun addCanvasNode(
        title: String,
        subtitle: String,
        type: CanvasNodeType,
        x: Float,
        y: Float,
        color: Long,
        linkedNoteId: Long? = null,
        targetUri: String? = null,
        targetMimeType: String? = null,
        targetName: String? = null,
        targetSizeBytes: Long? = null,
    ): Long {
        val now = System.currentTimeMillis()
        val id = dao.insertCanvasNode(
            CanvasNodeEntity(
                title = title.ifBlank { "Untitled block" },
                subtitle = subtitle,
                type = type.name,
                x = x.coerceIn(0f, 1f),
                y = y.coerceIn(0f, 1f),
                color = color,
                linkedNoteId = linkedNoteId,
                targetUri = targetUri,
                targetMimeType = targetMimeType,
                targetName = targetName,
                targetSizeBytes = targetSizeBytes,
                createdAt = now,
                updatedAt = now,
                workspaceId = activeWs(),
            ),
        )
        val objectId = upsertWorkspaceObject(WorkspaceObjectType.Canvas, id, title.ifBlank { "Untitled block" }, subtitle, "", type.name.lowercase(), color)
        recordActivity(WorkspaceActivityType.Created, "You", "Added canvas block", title.ifBlank { "Untitled block" }, objectType = WorkspaceObjectType.Canvas, sourceId = id)
        recordHistory(WorkspaceHistoryType.Created, objectId, "You", "Added canvas block", afterValue = title.ifBlank { "Untitled block" })
        return id
    }

    suspend fun addCanvasEdge(fromNodeId: Long, toNodeId: Long, label: String = ""): Long {
        if (fromNodeId == toNodeId) return 0
        val now = System.currentTimeMillis()
        return dao.insertCanvasEdge(
            CanvasEdgeEntity(
                fromNodeId = fromNodeId,
                toNodeId = toNodeId,
                label = label,
                color = 0xFF7E57FF,
                createdAt = now,
                updatedAt = now,
                workspaceId = activeWs(),
            ),
        )
            .also {
                val fromObjectId = objectIdFor(WorkspaceObjectType.Canvas, fromNodeId)
                val toObjectId = objectIdFor(WorkspaceObjectType.Canvas, toNodeId)
                if (fromObjectId != null && toObjectId != null) {
                    insertObjectLinkIfMissing(fromObjectId, toObjectId, WorkspaceLinkType.Related, label.ifBlank { "canvas" })
                }
                recordActivity(WorkspaceActivityType.Linked, "You", "Linked canvas blocks", label.ifBlank { "Canvas connection" })
            }
    }

    suspend fun deleteCanvasEdge(edge: CanvasEdgeItem) {
        dao.deleteCanvasEdge(edge.id)
    }

    suspend fun moveCanvasNode(node: CanvasNodeItem, x: Float, y: Float) {
        val ws = activeWs()
        val worldX = x.coerceIn(CanvasMinWorld, CanvasMaxWorld)
        val worldY = y.coerceIn(CanvasMinWorld, CanvasMaxWorld)
        val others = dao.allCanvasNodes()
            .filter { it.workspaceId == ws && it.id != node.id }
        fun overlaps(px: Float, py: Float): Boolean =
            others.any { other -> abs(other.x - px) < HardOverlapX && abs(other.y - py) < HardOverlapY }

        val candidate = if (!overlaps(worldX, worldY)) {
            worldX to worldY
        } else {
            buildList {
                listOf(0.18f, 0.28f, 0.42f, 0.58f).forEach { radius ->
                    add(worldX + radius to worldY)
                    add(worldX - radius to worldY)
                    add(worldX to worldY + radius)
                    add(worldX to worldY - radius)
                    add(worldX + radius to worldY + radius)
                    add(worldX - radius to worldY + radius)
                    add(worldX + radius to worldY - radius)
                    add(worldX - radius to worldY - radius)
                }
            }
                .map { (px, py) -> px.coerceIn(CanvasMinWorld, CanvasMaxWorld) to py.coerceIn(CanvasMinWorld, CanvasMaxWorld) }
                .firstOrNull { (px, py) -> !overlaps(px, py) }
                ?: (worldX to worldY)
        }
        val unresolvedOverlap = overlaps(candidate.first, candidate.second)
        dao.updateCanvasNodePosition(node.id, candidate.first, candidate.second, System.currentTimeMillis())
        if (unresolvedOverlap) dao.deleteCanvasEdgesForNode(node.id)
    }

    suspend fun updateCanvasNodeContent(node: CanvasNodeItem, title: String, subtitle: String) {
        dao.updateCanvasNodeContent(
            node.id,
            title.ifBlank { "Untitled block" },
            subtitle,
            System.currentTimeMillis(),
        )
        val objectId = upsertWorkspaceObject(
            type = WorkspaceObjectType.Canvas,
            sourceId = node.id,
            title = title.ifBlank { "Untitled block" },
            summary = subtitle,
            tags = "",
            icon = node.type.name.lowercase(),
            color = node.color,
        )
        recordActivity(WorkspaceActivityType.Updated, "You", "Updated canvas block", title.ifBlank { "Untitled block" }, objectId = objectId)
        recordHistory(WorkspaceHistoryType.Updated, objectId, "You", "Updated canvas block", beforeValue = "${node.title}\n${node.subtitle}", afterValue = "${title.ifBlank { "Untitled block" }}\n$subtitle")
    }

    suspend fun updateCanvasNodeTarget(node: CanvasNodeItem, uri: String?, mimeType: String?, name: String?, sizeBytes: Long?) =
        dao.updateCanvasNodeTarget(node.id, uri, mimeType, name, sizeBytes, System.currentTimeMillis()).also {
            if (!uri.isNullOrBlank() && !name.isNullOrBlank()) {
                val objectId = upsertWorkspaceObject(WorkspaceObjectType.File, null, name, mimeType.orEmpty(), "", "file", 0xFF4AADFF)
                dao.insertWorkspaceFile(WorkspaceFileEntity(objectId = objectId, displayName = name, mimeType = mimeType.orEmpty(), uri = uri, sizeBytes = sizeBytes ?: 0, createdAt = System.currentTimeMillis(), updatedAt = System.currentTimeMillis(), workspaceId = activeWs()))
                objectIdFor(WorkspaceObjectType.Canvas, node.id)?.let { canvasObjectId ->
                    insertObjectLinkIfMissing(canvasObjectId, objectId, WorkspaceLinkType.Attachment, name)
                }
                recordActivity(WorkspaceActivityType.Uploaded, "You", "Attached canvas target", name, objectId = objectId)
                recordHistory(WorkspaceHistoryType.Attachment, objectId, "You", "Attached canvas target", afterValue = "$name\n${mimeType.orEmpty()}\n$uri")
            }
        }

    suspend fun deleteCanvasNode(node: CanvasNodeItem) {
        dao.deleteCanvasEdgesForNode(node.id)
        dao.deleteCanvasNode(node.id)
    }

    suspend fun createGoal(
        title: String,
        description: String = "",
        owner: String = "",
        target: Double = 100.0,
        progress: Double = 0.0,
        unit: String = "%",
        dueAt: Long? = null,
    ): Long {
        val now = System.currentTimeMillis()
        val status = when {
            progress >= target && target > 0 -> GoalStatus.Achieved
            progress > 0 -> GoalStatus.InProgress
            else -> GoalStatus.NotStarted
        }
        val entity = GoalEntity(
                workspaceId = activeWs(),
                syncId = UUID.randomUUID().toString(),
                title = title.trim().ifBlank { "Untitled goal" },
                description = description.trim(),
                owner = owner.trim(),
                target = target.coerceAtLeast(0.0),
                progress = progress.coerceAtLeast(0.0),
                unit = unit.trim().ifBlank { "%" },
                dueAt = dueAt,
                status = status.name,
                createdAt = now,
                updatedAt = now,
            )
        val id = dao.insertGoal(entity)
        enqueueCloudUpsert(entity.copy(id = id).toDomain())
        val objectId = upsertWorkspaceObject(WorkspaceObjectType.Goal, id, title, description, status.name, "goal", 0xFF20B26B)
        recordActivity(WorkspaceActivityType.Created, owner.ifBlank { "You" }, "Created goal", title, objectId)
        return id
    }

    suspend fun updateGoal(goal: GoalItem) {
        val status = if (goal.target > 0 && goal.progress >= goal.target) GoalStatus.Achieved else goal.status
        dao.updateGoal(goal.id, goal.title, goal.description, goal.owner, goal.target, goal.progress, goal.unit, goal.dueAt, status.name, System.currentTimeMillis())
        upsertWorkspaceObject(WorkspaceObjectType.Goal, goal.id, goal.title, goal.description, status.name, "goal", 0xFF20B26B, archived = status == GoalStatus.Achieved)
        recordActivity(WorkspaceActivityType.Updated, goal.owner.ifBlank { "You" }, "Updated goal", goal.title, objectType = WorkspaceObjectType.Goal, sourceId = goal.id)
        enqueueCloudUpsert(goal.copy(status = status))
    }

    suspend fun deleteGoal(goal: GoalItem) {
        enqueueCloudDelete(goal.workspaceId, "goal", goal.syncId)
        dao.deleteGoal(goal.id)
        recordActivity(WorkspaceActivityType.Updated, "You", "Deleted goal", goal.title)
    }

    suspend fun createCalendarEvent(
        title: String,
        startAt: Long,
        endAt: Long,
        description: String = "",
        allDay: Boolean = false,
        color: Long = 0xFF6F36FF,
        source: CalendarEventSource = CalendarEventSource.Local,
        externalId: String? = null,
    ): Long {
        require(endAt >= startAt) { "Event end must be after its start." }
        val now = System.currentTimeMillis()
        val entity = CalendarEventEntity(
                workspaceId = activeWs(),
                syncId = UUID.randomUUID().toString(),
                title = title.trim().ifBlank { "Untitled event" },
                description = description.trim(),
                startAt = startAt,
                endAt = endAt,
                allDay = allDay,
                color = color,
                source = source.name,
                externalId = externalId,
                createdAt = now,
                updatedAt = now,
            )
        val id = dao.insertCalendarEvent(entity)
        enqueueCloudUpsert(entity.copy(id = id).toDomain())
        val objectId = upsertWorkspaceObject(WorkspaceObjectType.CalendarEvent, id, title, description, source.name, "calendar", color)
        recordActivity(WorkspaceActivityType.Created, "You", "Created calendar event", title, objectId)
        return id
    }

    suspend fun updateCalendarEvent(event: CalendarEventItem) {
        require(event.endAt >= event.startAt) { "Event end must be after its start." }
        dao.updateCalendarEvent(event.id, event.title, event.description, event.startAt, event.endAt, event.allDay, event.color, System.currentTimeMillis())
        upsertWorkspaceObject(WorkspaceObjectType.CalendarEvent, event.id, event.title, event.description, event.source.name, "calendar", event.color)
        enqueueCloudUpsert(event)
    }

    suspend fun deleteCalendarEvent(event: CalendarEventItem) {
        enqueueCloudDelete(event.workspaceId, "calendar_event", event.syncId)
        dao.deleteCalendarEvent(event.id)
        recordActivity(WorkspaceActivityType.Updated, "You", "Deleted calendar event", event.title)
    }

    private suspend fun enqueueCloudUpsert(goal: GoalItem) {
        val payload = JSONObject()
            .put("title", goal.title)
            .put("description", goal.description)
            .put("owner", goal.owner)
            .put("target", goal.target)
            .put("progress", goal.progress)
            .put("unit", goal.unit)
            .put("dueAt", goal.dueAt)
            .put("status", goal.status.name)
            .toString()
        cloudSyncQueue.enqueue(goal.workspaceId, "goal", goal.syncId, CloudObjectOperation.Upsert, payload, null)
    }

    private suspend fun enqueueCloudUpsert(event: CalendarEventItem) {
        val payload = JSONObject()
            .put("title", event.title)
            .put("description", event.description)
            .put("startAt", event.startAt)
            .put("endAt", event.endAt)
            .put("allDay", event.allDay)
            .put("color", event.color)
            .put("source", event.source.name)
            .put("externalId", event.externalId)
            .toString()
        cloudSyncQueue.enqueue(event.workspaceId, "calendar_event", event.syncId, CloudObjectOperation.Upsert, payload, null)
    }

    private suspend fun enqueueCloudDelete(workspaceId: Long, objectType: String, syncId: String) {
        cloudSyncQueue.enqueue(workspaceId, objectType, syncId, CloudObjectOperation.Delete, "", null)
    }

    suspend fun setPinned(note: Note) = dao.setPinned(note.id, !note.pinned, System.currentTimeMillis())
    suspend fun setStarred(note: Note) = dao.setStarred(note.id, !note.starred, System.currentTimeMillis())
    suspend fun setArchived(note: Note, value: Boolean = !note.archived) = dao.setArchived(note.id, value, System.currentTimeMillis())
    suspend fun setLocked(note: Note) = dao.setLocked(note.id, !note.locked, System.currentTimeMillis())
    suspend fun deleteNote(note: Note) = dao.deleteNote(note.id)

    suspend fun updateSettings(settings: AppSettings) = dao.upsertSettings(
        AppSettingsEntity(
            themeMode = settings.themeMode.name,
            themeProfile = settings.themeProfile.name,
            accentColor = settings.accentColor,
            activeWorkspaceId = settings.activeWorkspaceId,
            backupFolderUri = settings.backupFolderUri,
            vaultLockEnabled = settings.vaultLockEnabled,
            vaultSecretHash = settings.vaultSecretHash,
            syncProvider = settings.syncProvider.name,
            syncFolderUri = settings.syncFolderUri,
            syncChainId = settings.syncChainId,
            syncDeviceName = settings.syncDeviceName,
            syncUserName = settings.syncUserName,
            syncPublicName = settings.syncPublicName,
            lastSyncAt = settings.lastSyncAt,
            lastSyncHash = settings.lastSyncHash,
            lastSyncStatus = settings.lastSyncStatus,
            syncConflictCount = settings.syncConflictCount,
            profileBackgroundUri = settings.profileBackgroundUri,
            profileImageUri = settings.profileImageUri,
            workspaceName = settings.workspaceName,
            workspaceIcon = settings.workspaceIcon,
            workspaceIconKind = settings.workspaceIconKind.name,
            workspaceIconUri = settings.workspaceIconUri,
            workspaceBackgroundUri = settings.workspaceBackgroundUri,
            adminsControlWorkspaceVisuals = settings.adminsControlWorkspaceVisuals,
            allowMembersCreateNotes = settings.allowMembersCreateNotes,
            allowMembersInvite = settings.allowMembersInvite,
            uiScale = settings.uiScale,
            editorLineWidth = settings.editorLineWidth.name,
            editorFontFamily = settings.editorFontFamily.name,
            showMarkdownSyntax = settings.showMarkdownSyntax,
            noteLongPressAction = settings.noteLongPressAction.name,
            noteSwipeStartAction = settings.noteSwipeStartAction.name,
            noteSwipeEndAction = settings.noteSwipeEndAction.name,
            blockScreenshots = settings.blockScreenshots,
            requireBiometricOnOpen = settings.requireBiometricOnOpen,
            reduceMotion = settings.reduceMotion,
            uiDensityCompact = settings.uiDensityCompact,
            appFont = settings.appFont,
            defaultEditMode = settings.defaultEditMode,
            editorFontSize = settings.editorFontSize,
            tabSize = settings.tabSize,
            showLineNumbers = settings.showLineNumbers,
            autoPairBrackets = settings.autoPairBrackets,
            syntaxColorful = settings.syntaxColorful,
            autoConvertOnPaste = settings.autoConvertOnPaste,
            appLockOnExit = settings.appLockOnExit,
            autoLockMinutes = settings.autoLockMinutes,
            autoBackup = settings.autoBackup,
            backupFrequency = settings.backupFrequency,
            autoSync = settings.autoSync,
            backgroundSync = settings.backgroundSync,
            syncIntervalMinutes = settings.syncIntervalMinutes,
            syncOnMobileData = settings.syncOnMobileData,
            syncOnBatterySaver = settings.syncOnBatterySaver,
            notifyOnErrors = settings.notifyOnErrors,
            selectiveSync = settings.selectiveSync,
            conflictDefaultAction = settings.conflictDefaultAction,
            autoMergeNonConflicting = settings.autoMergeNonConflicting,
            keepBothCopies = settings.keepBothCopies,
            taskViewMode = settings.taskViewMode,
            taskSelectedBoardId = settings.taskSelectedBoardId,
            taskSortMode = settings.taskSortMode,
            taskCompactLayout = settings.taskCompactLayout,
            taskKanbanEngine = settings.taskKanbanEngine,
            onboardingComplete = settings.onboardingComplete,
            workspacePurpose = settings.workspacePurpose,
            calendarDefaultView = settings.calendarDefaultView,
            quietHoursEnabled = settings.quietHoursEnabled,
            quietHoursStart = settings.quietHoursStart,
            quietHoursEnd = settings.quietHoursEnd,
            notificationInApp = settings.notificationInApp,
            notificationEmail = settings.notificationEmail,
            notificationPush = settings.notificationPush,
            subscriptionTier = settings.subscriptionTier.name,
        ),
    )

    suspend fun setVaultSecret(secret: CharArray) {
        val current = dao.settings()?.toDomain() ?: defaultSettings
        updateSettings(current.copy(vaultLockEnabled = true, vaultSecretHash = VaultCrypto.hashSecret(secret)))
    }

    suspend fun disableVaultLock() {
        val current = dao.settings()?.toDomain() ?: defaultSettings
        updateSettings(current.copy(vaultLockEnabled = false, vaultSecretHash = null))
    }

    suspend fun snapshot(): BackupSnapshot = BackupSnapshot(
        workspaces = dao.allWorkspaces().map { it.toDomain() },
        taskWorkspacePrefs = (dao.settings()?.toDomain() ?: defaultSettings).let {
            TaskWorkspacePrefs(
                viewMode = it.taskViewMode,
                selectedBoardId = it.taskSelectedBoardId,
                sortMode = it.taskSortMode,
                compactLayout = it.taskCompactLayout,
                kanbanEngine = it.taskKanbanEngine,
            )
        },
        notes = dao.allNotesSnapshot().map { it.toDomain() },
        notebooks = dao.allNotebooks().map { it.toDomain() },
        tags = dao.allTags().map { it.toDomain() },
        attachments = dao.allAttachments().map { it.toDomain() },
        embeds = dao.allNoteEmbeds().map { it.toDomain() },
        taskBoards = dao.allTaskBoards().map { it.toDomain() },
        taskColumns = dao.allTaskColumns().map { it.toDomain() },
        tasks = dao.allTasks().map { it.toDomain() },
        taskPropertyDefinitions = dao.allTaskPropertyDefinitions().map { it.toDomain() },
        taskPropertyValues = dao.allTaskPropertyValues().map { it.toDomain() },
        taskChecklistItems = dao.allTaskChecklistItems().map { it.toDomain() },
        goals = dao.allGoals().map { it.toDomain() },
        calendarEvents = dao.allCalendarEvents().map { it.toDomain() },
        chatMessages = dao.allChatMessages().map { it.toDomain() },
        canvasNodes = dao.allCanvasNodes().map { it.toDomain() },
        canvasEdges = dao.allCanvasEdges().map { it.toDomain() },
        workspaceObjects = dao.allWorkspaceObjects().map { it.toDomain() },
        workspaceObjectLinks = dao.allWorkspaceObjectLinks().map { it.toDomain() },
        workspaceActivities = dao.allWorkspaceActivities().map { it.toDomain() },
        workspaceObjectHistory = dao.allWorkspaceObjectHistory().map { it.toDomain() },
        workspaceComments = dao.allWorkspaceComments().map { it.toDomain() },
        workspaceFiles = dao.allWorkspaceFiles().map { it.toDomain() },
    )

    suspend fun restore(snapshot: BackupSnapshot) {
        dao.clearSyncOutbox()
        dao.clearSyncTombstones()
        dao.clearRemoteBindings()
        dao.clearCalendarEvents()
        dao.clearGoals()
        dao.clearWorkspaceFiles()
        dao.clearWorkspaceComments()
        dao.clearWorkspaceObjectHistory()
        dao.clearWorkspaceActivities()
        dao.clearWorkspaceObjectLinks()
        dao.clearWorkspaceObjects()
        dao.clearAttachments()
        dao.clearNoteEmbeds()
        dao.clearCanvasEdges()
        dao.clearCanvasNodes()
        dao.clearChatMessages()
        dao.clearTaskChecklistItems()
        dao.clearTaskPropertyValues()
        dao.clearTaskPropertyDefinitions()
        dao.clearTasks()
        dao.clearTaskColumns()
        dao.clearTaskBoards()
        dao.clearNotes()
        dao.clearTags()
        dao.clearNotebooks()
        snapshot.workspaces.forEach {
            dao.insertWorkspace(
                WorkspaceEntity(
                    id = it.id,
                    name = it.name,
                    icon = it.icon,
                    iconKind = it.iconKind.name,
                    iconUri = it.iconUri,
                    backgroundUri = it.backgroundUri,
                    palette = it.palette.name,
                    createdAt = it.createdAt,
                ),
            )
        }
        snapshot.notebooks.forEach { dao.insertNotebook(NotebookEntity(it.id, it.name, it.parentId, it.color, it.sortOrder)) }
        snapshot.tags.forEach { dao.insertTag(TagEntity(it.id, it.name, it.color)) }
        snapshot.notes.forEach { note ->
            dao.insertNote(
                NoteEntity(
                    id = note.id,
                    title = note.title,
                    bodyMarkdown = note.bodyMarkdown,
                    notebookId = note.notebookId,
                    coverUri = note.coverUri,
                    coverMimeType = note.coverMimeType,
                    pinned = note.pinned,
                    starred = note.starred,
                    archived = note.archived,
                    locked = note.locked,
                    createdAt = note.createdAt,
                    updatedAt = note.updatedAt,
                ),
            )
            setTags(note.id, note.tags.map { it.name })
        }
        snapshot.attachments.forEach { dao.insertAttachment(AttachmentEntity(it.id, it.noteId, it.displayName, it.mimeType, it.uri, it.sizeBytes)) }
        snapshot.embeds.forEach { dao.insertNoteEmbed(NoteEmbedEntity(it.id, it.noteId, it.type.name, it.title, it.target, it.preview, it.createdAt)) }
        snapshot.taskBoards.forEach { dao.insertTaskBoard(TaskBoardEntity(it.id, it.name, it.workspaceId, it.createdAt, it.updatedAt)) }
        snapshot.taskColumns.forEach { dao.insertTaskColumn(TaskColumnEntity(it.id, it.boardId, it.name, it.status?.name, it.color, it.sortOrder, it.createdAt, it.updatedAt)) }
        if (snapshot.taskBoards.isEmpty()) {
            val ws = snapshot.workspaces.firstOrNull()?.id ?: 1L
            ensureDefaultTaskBoard(ws)
        }
        snapshot.taskPropertyDefinitions.forEach {
            dao.insertTaskPropertyDefinition(
                TaskPropertyDefinitionEntity(
                    id = it.id,
                    boardId = it.boardId,
                    name = it.name,
                    type = it.type.name,
                    sortOrder = it.sortOrder,
                    hiddenWhenEmpty = it.hiddenWhenEmpty,
                    optionsJson = it.optionsJson,
                    createdAt = it.createdAt,
                    updatedAt = it.updatedAt,
                ),
            )
        }
        snapshot.taskBoards.forEach { ensureDefaultTaskProperties(it.id) }
        snapshot.tasks.forEach {
            dao.insertTask(
                TaskEntity(
                    id = it.id,
                    title = it.title,
                    description = it.description,
                    assignee = it.assignee,
                    status = it.status.name,
                    priority = it.priority.name,
                    startAt = it.startAt,
                    dueAt = it.dueAt,
                    allDay = it.allDay,
                    reminderMinutesBefore = it.reminderMinutesBefore,
                    labels = it.labels,
                    attachmentName = it.attachmentName,
                    attachmentMimeType = it.attachmentMimeType,
                    attachmentUri = it.attachmentUri,
                    attachmentSizeBytes = it.attachmentSizeBytes,
                    createdAt = it.createdAt,
                    updatedAt = it.updatedAt,
                    taskBoardId = it.taskBoardId,
                    taskColumnId = it.taskColumnId,
                    sortOrder = it.sortOrder,
                    colorArgb = it.colorArgb,
                ),
            )
        }
        snapshot.taskPropertyValues.forEach {
            dao.insertTaskPropertyValue(TaskPropertyValueEntity(it.id, it.taskId, it.propertyId, it.valueJson, it.updatedAt))
        }
        snapshot.taskChecklistItems.forEach {
            dao.insertTaskChecklistItem(TaskChecklistItemEntity(it.id, it.taskId, it.propertyId, it.text, it.checked, it.sortOrder, it.createdAt, it.updatedAt))
        }
        snapshot.goals.forEach {
            dao.insertGoal(GoalEntity(it.id, it.workspaceId, it.syncId, it.title, it.description, it.owner, it.target, it.progress, it.unit, it.dueAt, it.status.name, it.createdAt, it.updatedAt))
        }
        snapshot.calendarEvents.forEach {
            dao.insertCalendarEvent(CalendarEventEntity(it.id, it.workspaceId, it.syncId, it.title, it.description, it.startAt, it.endAt, it.allDay, it.color, it.source.name, it.externalId, it.createdAt, it.updatedAt))
        }
        snapshot.chatMessages.forEach {
            dao.insertChatMessage(ChatMessageEntity(it.id, it.authorUsername, it.authorDisplayName, it.body, it.color, it.createdAt, it.system, it.attachmentName, it.attachmentMimeType, it.attachmentUri, it.attachmentSizeBytes))
        }
        snapshot.canvasNodes.forEach {
            dao.insertCanvasNode(CanvasNodeEntity(it.id, it.title, it.subtitle, it.type.name, it.x, it.y, it.color, it.linkedNoteId, it.targetUri, it.targetMimeType, it.targetName, it.targetSizeBytes, it.createdAt, it.updatedAt))
        }
        snapshot.canvasEdges.forEach {
            dao.insertCanvasEdge(CanvasEdgeEntity(it.id, it.fromNodeId, it.toNodeId, it.label, it.color, it.createdAt, it.updatedAt))
        }
        snapshot.workspaceObjects.forEach {
            dao.insertWorkspaceObject(WorkspaceObjectEntity(it.id, it.objectType.name, it.sourceId, it.title, it.summary, it.tags, it.icon, it.color, it.pinned, it.archived, it.createdAt, it.updatedAt))
        }
        snapshot.workspaceObjectLinks.forEach {
            dao.insertWorkspaceObjectLink(WorkspaceObjectLinkEntity(it.id, it.fromObjectId, it.toObjectId, it.linkType.name, it.label, it.createdAt))
        }
        snapshot.workspaceActivities.forEach {
            dao.insertWorkspaceActivity(WorkspaceActivityEntity(it.id, it.objectId, it.activityType.name, it.actor, it.title, it.detail, it.createdAt))
        }
        snapshot.workspaceObjectHistory.forEach {
            dao.insertWorkspaceObjectHistory(WorkspaceObjectHistoryEntity(it.id, it.objectId, it.historyType.name, it.actor, it.summary, it.beforeValue, it.afterValue, it.createdAt))
        }
        snapshot.workspaceComments.forEach {
            dao.insertWorkspaceComment(WorkspaceCommentEntity(it.id, it.objectId, it.authorUsername, it.authorDisplayName, it.body, it.resolved, it.createdAt, it.updatedAt))
        }
        snapshot.workspaceFiles.forEach {
            dao.insertWorkspaceFile(WorkspaceFileEntity(it.id, it.objectId, it.displayName, it.mimeType, it.uri, it.sizeBytes, it.createdAt, it.updatedAt))
        }
        val currentSettings = dao.settings()?.toDomain() ?: defaultSettings
        updateSettings(
            currentSettings.copy(
                taskViewMode = snapshot.taskWorkspacePrefs.viewMode,
                taskSelectedBoardId = snapshot.taskWorkspacePrefs.selectedBoardId,
                taskSortMode = snapshot.taskWorkspacePrefs.sortMode,
                taskCompactLayout = snapshot.taskWorkspacePrefs.compactLayout,
                taskKanbanEngine = snapshot.taskWorkspacePrefs.kanbanEngine,
            ),
        )
        rebuildWorkspaceIndex()
    }

    suspend fun addWorkspaceFile(displayName: String, mimeType: String, uri: String, sizeBytes: Long): Long {
        val now = System.currentTimeMillis()
        val objectId = upsertWorkspaceObject(WorkspaceObjectType.File, null, displayName, mimeType, "", "file", 0xFF4AADFF)
        val id = dao.insertWorkspaceFile(WorkspaceFileEntity(objectId = objectId, displayName = displayName, mimeType = mimeType, uri = uri, sizeBytes = sizeBytes, createdAt = now, updatedAt = now, workspaceId = activeWs()))
        recordActivity(WorkspaceActivityType.Uploaded, "You", "Uploaded file", displayName, objectId = objectId)
        recordHistory(WorkspaceHistoryType.Attachment, objectId, "You", "Uploaded file", afterValue = "$displayName\n$mimeType\n$uri")
        return id
    }

    suspend fun addObjectLink(fromObjectId: Long, toObjectId: Long, type: WorkspaceLinkType = WorkspaceLinkType.Reference, label: String = ""): Long {
        val id = insertObjectLinkIfMissing(fromObjectId, toObjectId, type, label)
        recordActivity(WorkspaceActivityType.Linked, "You", "Linked objects", label.ifBlank { "Workspace objects connected" })
        recordHistory(WorkspaceHistoryType.Linked, fromObjectId, "You", "Linked object", afterValue = "$toObjectId:${label.ifBlank { type.name }}")
        return id
    }

    suspend fun addWorkspaceComment(objectId: Long, body: String, username: String, displayName: String): Long {
        val now = System.currentTimeMillis()
        val id = dao.insertWorkspaceComment(
            WorkspaceCommentEntity(
                objectId = objectId,
                authorUsername = username.ifBlank { "you" },
                authorDisplayName = displayName.ifBlank { username.ifBlank { "You" } },
                body = body.trim(),
                createdAt = now,
                updatedAt = now,
                workspaceId = activeWs(),
            ),
        )
        recordActivity(WorkspaceActivityType.Commented, displayName.ifBlank { username.ifBlank { "You" } }, "Commented on object", body.take(120), objectId = objectId)
        recordHistory(WorkspaceHistoryType.Comment, objectId, displayName.ifBlank { username.ifBlank { "You" } }, "Added comment", afterValue = body.take(500))
        return id
    }

    suspend fun rebuildWorkspaceIndex() {
        val ws = activeWs()
        dao.allNotesSnapshot().filter { it.note.workspaceId == ws }.forEach { row ->
            val note = row.toDomain()
            upsertWorkspaceObject(WorkspaceObjectType.Note, note.id, note.title, note.bodyMarkdown.take(160), note.tags.joinToString(",") { it.name }, "note", 0xFF9D6CFF, note.pinned, note.archived)
        }
        dao.allTasks().filter { it.workspaceId == ws }.forEach {
            upsertWorkspaceObject(WorkspaceObjectType.Task, it.id, it.title, it.description, it.labels, "task", 0xFF56CC98, archived = it.status == TaskStatus.Done.name)
        }
        dao.allGoals().filter { it.workspaceId == ws }.forEach {
            upsertWorkspaceObject(WorkspaceObjectType.Goal, it.id, it.title, it.description, it.status, "goal", 0xFF20B26B, archived = it.status == GoalStatus.Achieved.name)
        }
        dao.allCalendarEvents().filter { it.workspaceId == ws }.forEach {
            upsertWorkspaceObject(WorkspaceObjectType.CalendarEvent, it.id, it.title, it.description, it.source, "calendar", it.color)
        }
        dao.allChatMessages().filter { it.workspaceId == ws }.forEach {
            upsertWorkspaceObject(WorkspaceObjectType.ChatMessage, it.id, it.authorDisplayName, it.body.take(160), "", "chat", it.color)
        }
        dao.allCanvasNodes().filter { it.workspaceId == ws }.forEach {
            val canvasObjectId = upsertWorkspaceObject(WorkspaceObjectType.Canvas, it.id, it.title, it.subtitle, "", it.type.lowercase(), it.color)
            it.linkedNoteId?.let { noteId ->
                objectIdFor(WorkspaceObjectType.Note, noteId)?.let { noteObjectId ->
                    insertObjectLinkIfMissing(canvasObjectId, noteObjectId, WorkspaceLinkType.Embed, "Linked note")
                }
            }
            if (!it.targetUri.isNullOrBlank() && !it.targetName.isNullOrBlank()) {
                val fileObjectId = upsertWorkspaceObject(WorkspaceObjectType.File, null, it.targetName, it.targetMimeType.orEmpty(), "", "file", 0xFF4AADFF)
                insertObjectLinkIfMissing(canvasObjectId, fileObjectId, WorkspaceLinkType.Attachment, it.targetName)
            }
        }
        dao.allCanvasEdges().filter { it.workspaceId == ws }.forEach {
            val fromObjectId = objectIdFor(WorkspaceObjectType.Canvas, it.fromNodeId)
            val toObjectId = objectIdFor(WorkspaceObjectType.Canvas, it.toNodeId)
            if (fromObjectId != null && toObjectId != null) {
                insertObjectLinkIfMissing(fromObjectId, toObjectId, WorkspaceLinkType.Related, it.label.ifBlank { "canvas" })
            }
        }
        dao.allAttachments().forEach { attachment ->
            val noteObjectId = objectIdFor(WorkspaceObjectType.Note, attachment.noteId)
            val fileObjectId = upsertWorkspaceObject(WorkspaceObjectType.File, null, attachment.displayName, attachment.mimeType, "", "file", 0xFF4AADFF)
            if (noteObjectId != null) {
                insertObjectLinkIfMissing(noteObjectId, fileObjectId, WorkspaceLinkType.Attachment, attachment.displayName)
            }
        }
        if (dao.allWorkspaceActivities().none { it.workspaceId == ws }) {
            recordActivity(WorkspaceActivityType.Synced, "System", "Workspace index ready", "Objects, files, and activity are connected.")
            recordHistory(WorkspaceHistoryType.Sync, null, "System", "Workspace index ready")
        }
    }

    private suspend fun upsertWorkspaceObject(
        type: WorkspaceObjectType,
        sourceId: Long?,
        title: String,
        summary: String,
        tags: String,
        icon: String,
        color: Long,
        pinned: Boolean = false,
        archived: Boolean = false,
    ): Long {
        val now = System.currentTimeMillis()
        val existing = dao.allWorkspaceObjects().firstOrNull { it.workspaceId == activeWs() && it.objectType == type.name && it.sourceId == sourceId && sourceId != null }
        return dao.insertWorkspaceObject(
            WorkspaceObjectEntity(
                id = existing?.id ?: 0,
                objectType = type.name,
                sourceId = sourceId,
                title = title.ifBlank { type.name },
                summary = summary,
                tags = tags,
                icon = icon,
                color = color,
                pinned = pinned,
                archived = archived,
                createdAt = existing?.createdAt ?: now,
                updatedAt = now,
                workspaceId = activeWs(),
            ),
        )
    }

    private suspend fun recordActivity(
        type: WorkspaceActivityType,
        actor: String,
        title: String,
        detail: String,
        objectId: Long? = null,
        objectType: WorkspaceObjectType? = null,
        sourceId: Long? = null,
    ) {
        val resolvedObjectId = objectId ?: objectType?.let { t -> dao.allWorkspaceObjects().firstOrNull { it.workspaceId == activeWs() && it.objectType == t.name && it.sourceId == sourceId }?.id }
        dao.insertWorkspaceActivity(
            WorkspaceActivityEntity(
                objectId = resolvedObjectId,
                activityType = type.name,
                actor = actor.ifBlank { "System" },
                title = title,
                detail = detail,
                createdAt = System.currentTimeMillis(),
                workspaceId = activeWs(),
            ),
        )
    }

    private suspend fun objectIdFor(type: WorkspaceObjectType, sourceId: Long): Long? =
        dao.allWorkspaceObjects().firstOrNull { it.workspaceId == activeWs() && it.objectType == type.name && it.sourceId == sourceId }?.id

    private suspend fun insertObjectLinkIfMissing(fromObjectId: Long, toObjectId: Long, type: WorkspaceLinkType, label: String = ""): Long {
        if (fromObjectId == toObjectId) return 0
        val ws = activeWs()
        val existing = dao.allWorkspaceObjectLinks().firstOrNull {
            it.workspaceId == ws &&
                it.fromObjectId == fromObjectId &&
                it.toObjectId == toObjectId &&
                it.linkType == type.name &&
                it.label == label
        }
        if (existing != null) return existing.id
        return dao.insertWorkspaceObjectLink(
            WorkspaceObjectLinkEntity(
                fromObjectId = fromObjectId,
                toObjectId = toObjectId,
                linkType = type.name,
                label = label,
                createdAt = System.currentTimeMillis(),
                workspaceId = ws,
            ),
        )
    }

    private suspend fun recordHistory(
        type: WorkspaceHistoryType,
        objectId: Long?,
        actor: String,
        summary: String,
        beforeValue: String = "",
        afterValue: String = "",
    ) {
        dao.insertWorkspaceObjectHistory(
            WorkspaceObjectHistoryEntity(
                objectId = objectId,
                historyType = type.name,
                actor = actor.ifBlank { "System" },
                summary = summary,
                beforeValue = beforeValue,
                afterValue = afterValue,
                createdAt = System.currentTimeMillis(),
                workspaceId = activeWs(),
            ),
        )
    }

    private suspend fun setTags(noteId: Long, names: List<String>) {
        dao.clearTagsForNote(noteId)
        names.map { it.trim().removePrefix("#") }.filter { it.isNotBlank() }.distinct().forEach { name ->
            dao.insertNoteTag(NoteTagCrossRef(noteId, getOrCreateTag(name).id))
        }
    }

    private suspend fun getOrCreateTag(name: String): TagEntity =
        dao.tagByName(name) ?: TagEntity(id = dao.insertTag(TagEntity(name = name, color = 0xFF8B5CF6)), name = name, color = 0xFF8B5CF6)

    companion object {
        private const val CanvasMinWorld = -10f
        private const val CanvasMaxWorld = 10f
        private const val HardOverlapX = 0.16f
        private const val HardOverlapY = 0.13f
        private val DefaultTaskProperties = listOf(
            "Name" to TaskPropertyType.Name,
            "Status" to TaskPropertyType.Status,
            "Checklist" to TaskPropertyType.Checklist,
            "Due date" to TaskPropertyType.DueDate,
            "Text" to TaskPropertyType.Text,
            "Files & media" to TaskPropertyType.FilesMedia,
            "Assignee" to TaskPropertyType.Assignee,
            "Labels" to TaskPropertyType.Labels,
            "Priority" to TaskPropertyType.Priority,
            "Created at" to TaskPropertyType.CreatedAt,
            "Last modified" to TaskPropertyType.LastModified,
        )

        private fun TaskPropertyType.defaultLabel(): String = when (this) {
            TaskPropertyType.Name -> "Name"
            TaskPropertyType.Status -> "Status"
            TaskPropertyType.Checklist -> "Checklist"
            TaskPropertyType.DueDate -> "Due date"
            TaskPropertyType.Text -> "Text"
            TaskPropertyType.FilesMedia -> "Files & media"
            TaskPropertyType.Assignee -> "Assignee"
            TaskPropertyType.Labels -> "Labels"
            TaskPropertyType.Priority -> "Priority"
            TaskPropertyType.CreatedAt -> "Created at"
            TaskPropertyType.LastModified -> "Last modified"
            TaskPropertyType.Numbers -> "Numbers"
            TaskPropertyType.Select -> "Select"
            TaskPropertyType.Multiselect -> "Multiselect"
            TaskPropertyType.Date -> "Date"
            TaskPropertyType.Person -> "Person"
            TaskPropertyType.Url -> "URL"
            TaskPropertyType.Checkbox -> "Checkbox"
            TaskPropertyType.Relation -> "Relation"
            TaskPropertyType.Rollup -> "Rollup"
            TaskPropertyType.AiSummary -> "AI Summary"
            TaskPropertyType.AiTranslate -> "AI Translate"
        }

        val defaultSettings = AppSettings(
            themeMode = ThemeMode.System,
            themeProfile = ThemeProfile.Neon,
            accentColor = 0xFF9D50BB,
            activeWorkspaceId = 1,
            backupFolderUri = null,
            vaultLockEnabled = false,
            vaultSecretHash = null,
            syncProvider = SyncProvider.None,
            syncFolderUri = null,
            syncChainId = null,
            syncDeviceName = "Android device",
            syncUserName = "",
            syncPublicName = "",
            lastSyncAt = null,
            lastSyncHash = null,
            lastSyncStatus = "Sync chain not configured",
            syncConflictCount = 0,
            profileBackgroundUri = null,
            profileImageUri = null,
            workspaceName = "My Workspace",
            workspaceIcon = "N",
            workspaceIconKind = WorkspaceIconKind.Text,
            workspaceIconUri = null,
            workspaceBackgroundUri = null,
            adminsControlWorkspaceVisuals = true,
            allowMembersCreateNotes = true,
            allowMembersInvite = false,
            uiScale = 0.88f,
            showMarkdownSyntax = true,
            noteLongPressAction = com.norfold.app.domain.NoteGestureAction.Actions,
            noteSwipeStartAction = com.norfold.app.domain.NoteGestureAction.Pin,
            noteSwipeEndAction = com.norfold.app.domain.NoteGestureAction.Archive,
            blockScreenshots = false,
            requireBiometricOnOpen = false,
            reduceMotion = false,
        )
    }
}
