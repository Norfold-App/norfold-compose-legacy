package com.norfold.app.data

import android.os.Build
import com.norfold.app.cloud.ChatBackend
import com.norfold.app.cloud.RemoteWorkspaceObject
import com.norfold.app.domain.AppSettings
import com.norfold.app.domain.Attachment
import com.norfold.app.domain.BackupSnapshot
import com.norfold.app.domain.BlockDocument
import com.norfold.app.domain.BlockDocumentJson
import com.norfold.app.domain.ChatChannel
import com.norfold.app.domain.ChatChannelKind
import com.norfold.app.domain.ChatChannelVisibility
import com.norfold.app.domain.ChatMessageItem
import com.norfold.app.domain.ChatReceiptStatus
import com.norfold.app.domain.ChatSecurityMode
import com.norfold.app.domain.WorkspaceMember
import com.norfold.app.domain.ChartBlock
import com.norfold.app.domain.DocLayerOrder
import com.norfold.app.domain.DocCanvasSpec
import com.norfold.app.domain.DocLayoutJson
import com.norfold.app.domain.DocOverlapMode
import com.norfold.app.domain.docOverlapModeOf
import com.norfold.app.domain.DocumentOwner
import com.norfold.app.domain.DocumentOwnerType
import com.norfold.app.domain.DeviceSettingsImportPreview
import com.norfold.app.domain.DeviceSettingsProfile
import com.norfold.app.domain.SettingsProfileCategory
import com.norfold.app.domain.FreeformPlacement
import com.norfold.app.domain.EmbedBlock
import com.norfold.app.domain.EmbedMetadata
import com.norfold.app.domain.FileBlock
import com.norfold.app.domain.MathBlock
import com.norfold.app.domain.MermaidBlock
import com.norfold.app.domain.ParagraphBlock
import com.norfold.app.domain.Note
import com.norfold.app.domain.OwnedDocument
import com.norfold.app.domain.MarkdownBlockCodec
import com.norfold.app.domain.NoteEmbedType
import com.norfold.app.domain.Notebook
import com.norfold.app.domain.GoalItem
import com.norfold.app.domain.GoalStatus
import com.norfold.app.domain.HeadingBlock
import com.norfold.app.domain.InlineText
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
import com.norfold.app.domain.TaskDateRange
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
import com.norfold.app.domain.findById
import com.norfold.app.domain.toDeviceSettingsProfile
import androidx.room.withTransaction
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

data class ImportedSharedDocument(
    val noteId: Long,
    val title: String,
    val document: BlockDocument,
    val layoutMode: DocOverlapMode,
    val freeformLayout: Map<String, FreeformPlacement>,
    val canvasSpec: DocCanvasSpec,
    val remoteVersion: Long,
    val changed: Boolean,
)

data class DeletedChatMessageSnapshot(
    val message: ChatMessageEntity,
    val binding: RemoteObjectBindingEntity?,
)

private data class SharedDocumentPayload(
    val title: String,
    val document: BlockDocument,
    val layoutMode: DocOverlapMode,
    val freeformLayout: Map<String, FreeformPlacement>,
    val canvasSpec: DocCanvasSpec,
)

private fun chatSecurityModeName(raw: String): String = when (raw.lowercase()) {
    "e2ee", ChatSecurityMode.EndToEndEncrypted.name.lowercase() ->
        ChatSecurityMode.EndToEndEncrypted.name
    "managed", ChatSecurityMode.Managed.name.lowercase() -> ChatSecurityMode.Managed.name
    else -> error("Unknown Chat security mode: $raw")
}

private fun RemoteWorkspaceObject.decodeSharedDocument(): SharedDocumentPayload? = runCatching {
    val body = content ?: return null
    val documentJson = body["documentJson"]?.jsonPrimitive?.contentOrNull.orEmpty()
    require(documentJson.isNotBlank() && documentJson.length <= 4 * 1024 * 1024) { "Invalid shared document payload" }
    val layoutJson = body["layoutJson"]?.jsonPrimitive?.contentOrNull
    SharedDocumentPayload(
        title = body["title"]?.jsonPrimitive?.contentOrNull.orEmpty().trim().take(500).ifBlank { "Untitled document" },
        document = BlockDocumentJson.decode(documentJson),
        layoutMode = docOverlapModeOf(body["layoutMode"]?.jsonPrimitive?.contentOrNull),
        freeformLayout = DocLayoutJson.decode(layoutJson),
        canvasSpec = DocLayoutJson.decodeCanvas(layoutJson),
    )
}.getOrNull()

@OptIn(ExperimentalCoroutinesApi::class)
class DocsRepository(private val database: NorfoldDatabase) {
    private val dao = database.dao()
    private val workspaceObjectUpsertMutex = Mutex()
    private val activeChatAccountId = MutableStateFlow<String?>(null)
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
    val chatMessages: Flow<List<ChatMessageItem>> = combine(activeWorkspaceId, activeChatAccountId) { ws, accountId ->
        ws to accountId
    }.flatMapLatest { (ws, accountId) ->
        dao.observeChatMessages(ws, accountId).map { rows -> rows.map { it.toDomain() } }
    }
    val chatChannels: Flow<List<ChatChannel>> = combine(activeWorkspaceId, activeChatAccountId) { ws, accountId ->
        ws to accountId
    }.flatMapLatest { (ws, accountId) ->
        dao.observeChatChannels(ws, accountId).map { rows -> rows.map { it.toDomain() } }
    }
    val workspaceMembers: Flow<List<WorkspaceMember>> = activeWorkspaceId.flatMapLatest { ws -> dao.observeWorkspaceMembers(ws).map { rows -> rows.map { it.toDomain() } } }
    val workspaceObjects: Flow<List<WorkspaceObject>> = activeWorkspaceId.flatMapLatest { ws -> dao.observeWorkspaceObjects(ws).map { rows -> rows.map { it.toDomain() } } }
    val workspaceObjectLinks: Flow<List<WorkspaceObjectLink>> = activeWorkspaceId.flatMapLatest { ws -> dao.observeWorkspaceObjectLinks(ws).map { rows -> rows.map { it.toDomain() } } }
    val workspaceActivities: Flow<List<WorkspaceActivity>> = activeWorkspaceId.flatMapLatest { ws -> dao.observeWorkspaceActivities(ws).map { rows -> rows.map { it.toDomain() } } }
    val workspaceObjectHistory: Flow<List<WorkspaceObjectHistory>> = activeWorkspaceId.flatMapLatest { ws -> dao.observeWorkspaceObjectHistory(ws).map { rows -> rows.map { it.toDomain() } } }
    val workspaceComments: Flow<List<WorkspaceComment>> = activeWorkspaceId.flatMapLatest { ws -> dao.observeWorkspaceComments(ws).map { rows -> rows.map { it.toDomain() } } }
    val workspaceFiles: Flow<List<WorkspaceFileItem>> = activeWorkspaceId.flatMapLatest { ws -> dao.observeWorkspaceFiles(ws).map { rows -> rows.map { it.toDomain() } } }
    val goals: Flow<List<GoalItem>> = activeWorkspaceId.flatMapLatest { ws -> dao.observeGoals(ws).map { rows -> rows.map { it.toDomain() } } }
    val calendarEvents: Flow<List<CalendarEventItem>> = activeWorkspaceId.flatMapLatest { ws -> dao.observeCalendarEvents(ws).map { rows -> rows.map { it.toDomain() } } }
    val settings: Flow<AppSettings> = dao.observeSettings().map { it?.toDomain() ?: defaultSettings }
    val deviceSettingsProfiles: Flow<List<DeviceSettingsProfile>> =
        dao.observeDeviceSettingsProfiles().map { rows -> rows.map { it.toDomain() } }

    private suspend fun activeWs(): Long = dao.settings()?.activeWorkspaceId ?: 1L

    suspend fun activeWorkspaceIdValue(): Long = activeWs()

    fun setActiveChatAccount(accountId: String?) {
        activeChatAccountId.value = accountId?.trim()?.takeIf(String::isNotEmpty)
    }

    suspend fun settingsValue(): AppSettings = dao.settings()?.toDomain() ?: defaultSettings

    private fun detectedDeviceName(): String {
        val manufacturer = Build.MANUFACTURER.orEmpty().trim()
        val model = Build.MODEL.orEmpty().trim()
        return listOf(manufacturer, model)
            .filter { it.isNotBlank() }
            .distinctBy { it.lowercase() }
            .joinToString(" ")
            .ifBlank { "Android device" }
    }

    private suspend fun ensureSettingsDeviceIdentity(existing: AppSettingsEntity? = null): AppSettingsEntity {
        val stored = existing ?: dao.settings()
        val current = stored ?: AppSettingsEntity()
        val detectedName = detectedDeviceName()
        val identified = current.copy(
            settingsDeviceId = current.settingsDeviceId.ifBlank { UUID.randomUUID().toString() },
            settingsDeviceName = current.settingsDeviceName
                .takeUnless { it.isBlank() || it == "Android device" }
                ?: detectedName,
            settingsDeviceType = current.settingsDeviceType.ifBlank { "Android" },
            settingsScreenClass = current.settingsScreenClass.ifBlank { "Compact" },
            settingsSchemaVersion = maxOf(current.settingsSchemaVersion, 1),
        )
        if (identified != stored) dao.upsertSettings(identified)
        dao.upsertDeviceSettingsProfile(identified.toDomain().toDeviceSettingsProfile().toEntity())
        return identified
    }

    suspend fun ensureSeedData() {
        // The goal/calendar cloud outbox is parked: nothing enqueues into it anymore and no
        // server endpoint drains it, so purge any rows accumulated by older builds. The
        // sync_outbox/sync_tombstones tables stay in the schema for when real goal/event
        // cloud sync exists.
        dao.clearSyncOutbox()
        dao.clearSyncTombstones()
        if (dao.allWorkspaces().isNotEmpty()) {
            ensureSettingsDeviceIdentity()
            setActiveWorkspace(activeWs())
            ensureDefaultTaskBoard(activeWs())
            dao.allNotebooks()
                .filter { it.name == "Norfold Guide" }
                .map { it.workspaceId }
                .distinct()
                .forEach { repairGuideDocuments(it) }
            return
        }
        val wsId = dao.insertWorkspace(WorkspaceEntity(name = "Personal", icon = "N", iconKind = WorkspaceIconKind.Text.name, palette = ThemeProfile.Norfold.name, createdAt = System.currentTimeMillis()))
        val existing = ensureSettingsDeviceIdentity()
        dao.upsertSettings(existing.copy(activeWorkspaceId = wsId, workspaceName = "Personal"))
        ensureDefaultTaskBoard(wsId)
        // Seed nothing here: onboarding asks for a starting point and applies the chosen
        // template via applyWorkspaceTemplate — "Start empty" must actually start empty.
        rebuildWorkspaceIndex()
    }

    suspend fun registerSettingsDevice(screenClass: String) {
        val normalizedScreenClass = screenClass.takeIf { it in setOf("Compact", "Medium", "Expanded") } ?: "Compact"
        val current = ensureSettingsDeviceIdentity()
        val deviceType = when (normalizedScreenClass) {
            "Expanded" -> "Desktop-class Android"
            "Medium" -> "Android tablet"
            else -> "Android phone"
        }
        if (current.settingsScreenClass != normalizedScreenClass || current.settingsDeviceType != deviceType) {
            val updated = current.copy(
                    settingsScreenClass = normalizedScreenClass,
                    settingsDeviceType = deviceType,
                )
            dao.upsertSettings(updated)
            dao.upsertDeviceSettingsProfile(updated.toDomain().toDeviceSettingsProfile().toEntity())
        }
    }

    suspend fun applyWorkspaceTemplate(template: String) {
        if (template == "Start empty") return
        val workspaceId = activeWs()
        val workspaceHasNotes = dao.allNotesSnapshot().any { it.note.workspaceId == workspaceId }
        val workspaceHasTasks = dao.allTasks().any { it.workspaceId == workspaceId }
        when (template) {
            "Norfold Guide" -> {
                configureTaskWorkflow(
                    listOf(
                        "Inbox" to TaskStatus.Todo,
                        "In progress" to TaskStatus.Doing,
                        "Complete" to TaskStatus.Done,
                    ),
                )
                if (!workspaceHasNotes) {
                    val guide = dao.insertNotebook(NotebookEntity(name = "Norfold Guide", color = 0xFF6F36FF, sortOrder = 0, workspaceId = workspaceId))
                    seedGuideNotes(guide)
                } else repairGuideDocuments(workspaceId)
                if (!workspaceHasTasks) seedGuideTasks()
            }
            "Study planner" -> {
                if (workspaceHasNotes || workspaceHasTasks) return
                configureTaskWorkflow(
                    listOf(
                        "Upcoming" to TaskStatus.Todo,
                        "Studying" to TaskStatus.Doing,
                        "Submitted" to TaskStatus.Done,
                    ),
                )
                val notebook = dao.insertNotebook(NotebookEntity(name = "Study", color = 0xFF42A5F5, sortOrder = 0, workspaceId = workspaceId))
                createNote("Study dashboard", "# Study dashboard\n\nKeep lecture notes, readings, and revision plans together.", notebook, listOf("Study"), pinned = true)
                addTask("Review lecture notes", "Summarize the key definitions", "@me", TaskStatus.Todo)
                addTask("Practice problems", "Complete the current problem set", "@me", TaskStatus.Doing)
            }
            "Personal organizer" -> {
                if (workspaceHasNotes || workspaceHasTasks) return
                configureTaskWorkflow(
                    listOf(
                        "Next" to TaskStatus.Todo,
                        "In progress" to TaskStatus.Doing,
                        "Complete" to TaskStatus.Done,
                    ),
                )
                val notebook = dao.insertNotebook(NotebookEntity(name = "Personal", color = 0xFF35A853, sortOrder = 0, workspaceId = workspaceId))
                createNote("Daily doc", "# Daily doc\n\nWrite down what matters today.", notebook, listOf("Personal"), pinned = true)
                addTask("Plan the week", "Choose the three most important outcomes", "@me", TaskStatus.Todo)
            }
            "Team workspace" -> {
                if (workspaceHasNotes || workspaceHasTasks) return
                configureTaskWorkflow(
                    listOf(
                        "Backlog" to TaskStatus.Todo,
                        "Active" to TaskStatus.Doing,
                        "Review" to null,
                        "Complete" to TaskStatus.Done,
                    ),
                )
                val notebook = dao.insertNotebook(NotebookEntity(name = "Team", color = 0xFF8B5CF6, sortOrder = 0, workspaceId = workspaceId))
                createNote("Team brief", "# Team brief\n\nShared context, decisions, and next actions.", notebook, listOf("Team"), pinned = true)
                addTask("Prepare team stand-up", "Collect updates and blockers", "@owner", TaskStatus.Todo)
                sendChat("system", "Workspace", "Collaboration chat is ready for workspace members.", 0xFF7E57FF, system = true)
            }
            "Personal" -> {
                if (workspaceHasNotes || workspaceHasTasks) return
                configureTaskWorkflow(
                    listOf(
                        "Next" to TaskStatus.Todo,
                        "In progress" to TaskStatus.Doing,
                        "Complete" to TaskStatus.Done,
                    ),
                )
                val notebook = dao.insertNotebook(NotebookEntity(name = "Personal", color = 0xFF35A853, sortOrder = 0, workspaceId = workspaceId))
                createNote("Home base", "# Home base\n\nCapture priorities, routines, and things you do not want to forget.", notebook, listOf("Personal"), pinned = true)
                addTask("Plan the week", "Choose the three most important outcomes", "@me", TaskStatus.Todo)
            }
            "Student" -> {
                if (workspaceHasNotes || workspaceHasTasks) return
                configureTaskWorkflow(
                    listOf(
                        "Upcoming" to TaskStatus.Todo,
                        "Studying" to TaskStatus.Doing,
                        "Submitted" to TaskStatus.Done,
                    ),
                )
                val notebook = dao.insertNotebook(NotebookEntity(name = "Courses", color = 0xFF42A5F5, sortOrder = 0, workspaceId = workspaceId))
                createNote("Semester dashboard", "# Semester dashboard\n\nTrack courses, readings, assignments, and revision notes.", notebook, listOf("Study"), pinned = true)
                addTask("Review lecture notes", "Summarize the key definitions", "@me", TaskStatus.Todo)
                addTask("Practice problems", "Complete the current problem set", "@me", TaskStatus.Doing)
            }
            "Research" -> {
                if (workspaceHasNotes || workspaceHasTasks) return
                configureTaskWorkflow(
                    listOf(
                        "Sources to review" to TaskStatus.Todo,
                        "Investigating" to TaskStatus.Doing,
                        "Synthesized" to TaskStatus.Done,
                    ),
                )
                val notebook = dao.insertNotebook(NotebookEntity(name = "Research", color = 0xFF7E57FF, sortOrder = 0, workspaceId = workspaceId))
                createNote("Research brief", "# Research brief\n\n## Question\n\n## Sources\n\n## Evidence\n\n## Open questions", notebook, listOf("Research"), pinned = true)
                addTask("Build literature queue", "Collect and rank the first source set", "@owner", TaskStatus.Todo)
            }
            "Software Team" -> {
                if (workspaceHasNotes || workspaceHasTasks) return
                configureTaskWorkflow(
                    listOf(
                        "Backlog" to TaskStatus.Todo,
                        "In development" to TaskStatus.Doing,
                        "Review" to null,
                        "Released" to TaskStatus.Done,
                    ),
                )
                val notebook = dao.insertNotebook(NotebookEntity(name = "Engineering", color = 0xFF8B5CF6, sortOrder = 0, workspaceId = workspaceId))
                createNote("Engineering brief", "# Engineering brief\n\n## Goal\n\n## Architecture\n\n## Decisions\n\n## Release checklist", notebook, listOf("Engineering"), pinned = true)
                addTask("Prepare sprint", "Confirm scope, owners, dependencies, and acceptance checks", "@owner", TaskStatus.Todo)
                sendChat("system", "Workspace", "Team chat is ready locally. Connect collaboration sync before inviting other accounts.", 0xFF7E57FF, system = true)
            }
            "Business" -> {
                if (workspaceHasNotes || workspaceHasTasks) return
                configureTaskWorkflow(
                    listOf(
                        "Opportunities" to TaskStatus.Todo,
                        "In progress" to TaskStatus.Doing,
                        "Review" to null,
                        "Complete" to TaskStatus.Done,
                    ),
                )
                val notebook = dao.insertNotebook(NotebookEntity(name = "Operations", color = 0xFFFFA726, sortOrder = 0, workspaceId = workspaceId))
                createNote("Operating plan", "# Operating plan\n\n## Outcomes\n\n## Customers\n\n## Metrics\n\n## Risks", notebook, listOf("Business"), pinned = true)
                addTask("Review weekly metrics", "Record changes, causes, and next actions", "@owner", TaskStatus.Todo)
            }
            "Second Brain" -> {
                if (workspaceHasNotes || workspaceHasTasks) return
                val notebook = dao.insertNotebook(NotebookEntity(name = "Knowledge", color = 0xFF26A69A, sortOrder = 0, workspaceId = workspaceId))
                createNote("Knowledge map", "# Knowledge map\n\nLink projects, areas, resources, and archived ideas from here.", notebook, listOf("Knowledge"), pinned = true)
                createNote("Capture queue", "# Capture queue\n\nProcess these notes into durable knowledge.", notebook, listOf("Inbox"))
            }
            "Journal" -> {
                if (workspaceHasNotes || workspaceHasTasks) return
                val notebook = dao.insertNotebook(NotebookEntity(name = "Journal", color = 0xFFEC407A, sortOrder = 0, workspaceId = workspaceId))
                createNote("Daily reflection", "# Daily reflection\n\n## What happened?\n\n## What mattered?\n\n## What comes next?", notebook, listOf("Journal"), pinned = true)
            }
            "Knowledge Base" -> {
                if (workspaceHasNotes || workspaceHasTasks) return
                val notebook = dao.insertNotebook(NotebookEntity(name = "Knowledge Base", color = 0xFF5C6BC0, sortOrder = 0, workspaceId = workspaceId))
                createNote("Knowledge base index", "# Knowledge base index\n\n## Start here\n\n## Policies\n\n## How-to guides\n\n## Decisions", notebook, listOf("Knowledge Base"), pinned = true)
                addTask("Document the first workflow", "Turn one repeated process into a reusable guide", "@owner", TaskStatus.Todo)
            }
        }
        rebuildWorkspaceIndex()
    }

    private suspend fun repairGuideDocuments(workspaceId: Long) {
        val guideNotes = dao.allNotesSnapshot().filter { it.note.workspaceId == workspaceId }
        guideNotes.firstOrNull { it.note.title == "Rich blocks playground" }?.toDomain()?.let { playground ->
            val seenMath = linkedSetOf<String>()
            val repaired = buildList {
                playground.document.blocks.forEach { block ->
                    val candidate = if (block is ParagraphBlock) {
                        val raw = block.plainText().trim()
                        if (raw.startsWith("$$") && raw.endsWith("$$") && raw.length >= 4) {
                            MathBlock(id = block.id, tex = raw.removePrefix("$$").removeSuffix("$$").trim())
                        } else block
                    } else block
                    if (candidate !is MathBlock || seenMath.add(candidate.tex.trim())) add(candidate)
                }
            }
            if (repaired != playground.document.blocks) {
                val dirtyIds = (playground.document.blocks.map { it.id } + repaired.map { it.id }).toSet()
                updateNote(playground, playground.title, playground.document.copy(blocks = repaired), dirtyIds)
            }
        }
        // Deliberately no re-seed of the "Long document performance demo" note here:
        // deleting it must be permanent. Fresh installs still get it via seedGuideNotes.
    }

    /** In-depth, built-in reference notes that teach the app's features. Seeded once on first run. */
    private suspend fun seedGuideNotes(guide: Long) {
        val playgroundId = createNote(
            "Rich blocks playground",
            """
            # Rich blocks playground :sparkles:

            This doc deliberately exercises the complete Norfold document renderer. Double-tap a block to edit it, use `/` to insert another block, and long-press for its type-specific actions.

            ## Inline formatting

            **Bold**, *italic*, `inline code`, [Norfold support](https://sheikhti1205.github.io/Norfold/support.html), #Guide, :white_check_mark:, and inline math ${'$'}E = mc^2${'$'} all share one paragraph.

            ## Checklist

            - [x] Open the rendered document surface
            - [x] Render rich inline content
            - [ ] Reorder this item with its six-dot handle

            ## Table

            | Block | What to verify | Status |
            |---|---|---|
            | Math | KaTeX/MathJax layout | Ready |
            | Mermaid | Diagram fallback | Ready |
            | Chart | Editable Vega-Lite | Ready |

            ## Display math

            ${'$'}${'$'}
            \int_0^1 x^2\,dx = \frac{1}{3}
            ${'$'}${'$'}

            ```mermaid
            flowchart LR
              Paste --> Parse
              Parse --> Blocks
              Blocks --> Render
            ```

            ```vega-lite
            {"${'$'}schema":"https://vega.github.io/schema/vega-lite/v5.json","title":"Feature coverage","mark":{"type":"bar"},"data":{"values":[{"x":"Docs","y":12},{"x":"Tasks","y":8},{"x":"Charts","y":5}]},"encoding":{"x":{"field":"x","type":"nominal"},"y":{"field":"y","type":"quantitative"}}}
            ```

            ```kotlin
            val workspace = Norfold.localFirst()
            println(workspace.isPrivate)
            ```

            > Smart-paste this entire doc into a blank page to verify one atomic, structured paste and one-step undo.
            """.trimIndent(),
            guide,
            listOf("Guide", "Blocks", "Demo"),
            pinned = true,
        )
        dao.noteById(playgroundId)?.toDomain()?.let { playground ->
            val current = playground.document.blocks
            val richBlocks = buildList {
                if (current.none { it is MathBlock }) add(MathBlock(tex = "\\int_0^1 x^2\\,dx = \\frac{1}{3}"))
                if (current.none { it is MermaidBlock }) add(MermaidBlock(code = "flowchart LR\n  Paste --> Parse\n  Parse --> Blocks\n  Blocks --> Render"))
                if (current.none { it is ChartBlock }) add(
                    ChartBlock(
                        vegaLiteSpec = """{"${'$'}schema":"https://vega.github.io/schema/vega-lite/v5.json","title":"Feature coverage","mark":{"type":"bar"},"data":{"values":[{"x":"Docs","y":12},{"x":"Tasks","y":8},{"x":"Charts","y":5}]},"encoding":{"x":{"field":"x","type":"nominal"},"y":{"field":"y","type":"quantitative"}}}""",
                    ),
                )
                if (current.none { it is EmbedBlock }) add(
                    EmbedBlock(
                        url = "https://sheikhti1205.github.io/Norfold/",
                        metadata = EmbedMetadata(
                            title = "Norfold product guide",
                            description = "Privacy, support, changelog, and testing information.",
                        ),
                    ),
                )
                if (current.none { it is FileBlock }) add(
                    FileBlock(
                        name = "smart-paste-example.md",
                        mimeType = "text/markdown",
                        sizeBytes = 2_048,
                        uri = "https://sheikhti1205.github.io/Norfold/changelog.html",
                    ),
                )
            }
            updateNote(
                playground,
                playground.title,
                playground.document.copy(blocks = playground.document.blocks + richBlocks),
                richBlocks.mapTo(linkedSetOf()) { it.id },
            )
        }
        createNote(
            "Welcome to Norfold",
            """
            # Welcome 👋

            Norfold is your private, local-first workspace: docs, tasks, files and chat, all in one place and kept on your device.

            This **Guide** notebook is a set of real reference docs. Open any of them to learn a feature in depth, then delete them whenever you like.

            **Start here**
            - 📝 Docs & the Editor
            - ✓ Tasks & the Kanban board
            - 🔗 Links, files & references
            - 🔍 Search everything
            - 🔒 Sync, Vault & Backup
            - 🏷 Tags & Notebooks

            **Getting around**
            - Tap the ☰ button (top-left) or swipe from the left edge to open the sidebar.
            - The bottom bar switches between Home, Docs, Tasks, and Chat. Create opens context-aware actions; the rest of the workspace lives in the section menu.
            - The **Search everything** bar on every page opens one global search.

            Everything here is yours. Nothing leaves your device unless you turn on sync.
            """.trimIndent(),
            guide,
            listOf("Guide", "Start here"),
            pinned = true,
            starred = true,
        )
        createNote(
            "📝 Docs & the Editor",
            """
            # Docs & the Editor

            Docs use structured blocks with Markdown-compatible import and export.

            **Formatting basics**
            - `# Heading`, `## Subheading`
            - `**bold**`, `*italic*`, `` `code` ``
            - `- bullet` lists and `1.` numbered lists
            - `- [ ] todo` and `- [x] done` checklists
            - `> quote` blocks and `---` dividers

            **Organize a doc**
            - **Pin** it to keep it at the top of Home.
            - **Star** favorites for quick access.
            - **Lock** a private doc behind your vault.
            - Add a **cover image**, attachments, and embeds.
            - File it into a **notebook** and add **#tags**.

            **Gestures**
            - Swipe a doc card left/right for quick actions (pin, star, archive, delete) — the actions are configurable in Settings › Editor.
            - Long-press a card for the full action sheet.

            Create a doc anytime with the ➕ button.
            """.trimIndent(),
            guide,
            listOf("Guide", "Docs"),
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
            "🔍 Search everything",
            """
            # Search everything

            There is **one** search for the whole app. Open it from the **Search everything** bar on any page, or the Search item in the sidebar.

            **It finds**
            - 📝 Docs and their content
            - ✓ Tasks
            - 📁 Files
            - 🕘 Recent activity
            - ⚙️ Settings sections
            - 🏷 Tags
            - ⌘ Commands & destinations (Graph, Chat, Notebooks…)

            Tap any result to jump straight to it. Browsing your docs is never left in a filtered state — search lives on its own page.
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
            Lock private docs behind the vault. Manage security in Settings › Security.

            **Sync (optional)**
            Connect **Google Drive** in Settings › Sync to back up and sync across devices. If two edits collide, the **Conflict review** screen lets you compare and choose.

            **Backup & import/export**
            Export a full backup or individual docs as Markdown, and restore from a backup file in Settings › Backup & Import.

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

            **Notebooks** are folders — a doc lives in one notebook (like this **Guide** notebook).

            **Tags** are flexible labels — a doc can have many. Add `#tags` to connect docs across notebooks.

            Filter Home by notebook, browse everything under **Tags** in the sidebar, and find any tag through global search.
            """.trimIndent(),
            guide,
            listOf("Guide", "Organize"),
        )
        seedGuideStressNote(guide)
    }

    private suspend fun seedGuideStressNote(guide: Long) {
        val noteId = createNote(
            title = "Long document performance demo",
            body = "# Long document performance demo",
            notebookId = guide,
            tagNames = listOf("Guide", "Performance"),
        )
        val note = dao.noteById(noteId)?.toDomain() ?: return
        val blocks = buildList {
            add(HeadingBlock(level = 1, content = listOf(InlineText("Long document performance demo"))))
            repeat(220) { index ->
                when {
                    index % 70 == 0 -> add(ChartBlock(vegaLiteSpec = """{"mark":{"type":"bar"},"data":{"values":[{"x":"A","y":${index + 1}},{"x":"B","y":${index + 2}}]},"encoding":{"x":{"field":"x","type":"nominal"},"y":{"field":"y","type":"quantitative"}}}"""))
                    index % 50 == 0 -> add(MermaidBlock(code = "flowchart LR\n  Block$index --> Block${index + 1}"))
                    index % 40 == 0 -> add(MathBlock(tex = "x_{$index}^2 + \\sum_{i=1}^{n} i"))
                    else -> add(ParagraphBlock(content = listOf(InlineText("Block ${index + 1}: lazy document rendering remains responsive while scrolling."))))
                }
            }
        }
        val document = BlockDocument(blocks)
        updateNote(note, note.title, document, blocks.mapTo(linkedSetOf()) { it.id })
    }

    private suspend fun seedGuideTasks() {
        val now = System.currentTimeMillis()
        val day = 86_400_000L
        val taskIds = listOf(
            addTask(
                title = "Polish Android workspace",
                description = "Review docs, tasks, calendar, and chat screens for Android consistency.",
                assignee = "@owner",
                status = TaskStatus.Todo,
                priority = TaskPriority.High,
                dueAt = now + day * 4,
            ),
            addTask(
                title = "Implement offline sync",
                description = "Add durable outbox processing and conflict recovery for mobile.",
                assignee = "@alex",
                status = TaskStatus.Doing,
                priority = TaskPriority.Urgent,
                dueAt = now + day * 2,
            ),
            addTask(
                title = "Create onboarding",
                description = "Build the complete private-workspace onboarding flow with real persisted choices.",
                assignee = "@nina",
                status = TaskStatus.Done,
                priority = TaskPriority.Low,
                dueAt = now - day,
            ),
            addTask(
                title = "Draft project roadmap",
                description = "Outline milestones, owners, launch plan, dependencies, and delivery dates.",
                assignee = "@product",
                status = TaskStatus.Todo,
                priority = TaskPriority.Normal,
                dueAt = now + day * 10,
            ),
        )
        val boardId = dao.taskById(taskIds.first())?.taskBoardId ?: return
        val properties = dao.taskPropertyDefinitionsForBoard(boardId).map { it.toDomain() }
        val text = properties.firstOrNull { it.type == TaskPropertyType.Text }
        val labels = properties.firstOrNull { it.type == TaskPropertyType.Labels }
        val date = properties.firstOrNull { it.type == TaskPropertyType.DueDate }
        val checklist = properties.firstOrNull { it.type == TaskPropertyType.Checklist }
        val taskLabels = listOf("Android,UI/UX", "Sync,Backend,Blocked", "Onboarding,UI/UX", "Roadmap,Planning")
        val checklistText = listOf(
            listOf("Review docs", "Review tasks", "Review files", "Review charts", "Document results"),
            listOf("Design conflict model", "Build delta sync", "Integration tests"),
            listOf("Welcome screen", "Workspace setup", "Completion state"),
            listOf("Define goals and scope", "Gather stakeholder input", "Outline milestones", "Identify dependencies", "Draft timeline"),
        )
        taskIds.forEachIndexed { index, taskId ->
            val task = dao.taskById(taskId)?.toDomain() ?: return@forEachIndexed
            text?.let { setTaskPropertyValue(task, it, task.description) }
            labels?.let { property ->
                taskLabels[index].split(',').forEach { addTaskTag(boardId, it) }
                setTaskPropertyValue(task, property, taskLabels[index])
            }
            date?.let { property ->
                setTaskPropertyValue(
                    task,
                    property,
                    TaskDateRangeCodec.encode(
                        TaskDateRange(
                            startAt = task.dueAt?.minus(day),
                            endAt = task.dueAt,
                            allDay = true,
                            reminderMinutesBefore = if (task.status == TaskStatus.Done) null else 30,
                        ),
                    ),
                )
            }
            checklist?.let { property ->
                checklistText[index].forEach { item -> addChecklistItem(task, property, item) }
                dao.checklistItemsForProperty(task.id, property.id).take(2).forEach { item ->
                    dao.updateTaskChecklistItem(item.id, item.text, true, System.currentTimeMillis())
                }
            }
        }
    }

    fun search(query: String): Flow<List<Note>> =
        if (query.isBlank()) activeNotes
        else activeWorkspaceId.flatMapLatest { ws -> dao.searchNotes(query.trim(), ws).map { rows -> rows.map { it.toDomain() } } }

    // ---- Workspaces ----
    suspend fun createWorkspace(
        name: String,
        icon: String = "N",
        palette: ThemeProfile = ThemeProfile.Norfold,
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
        permManageTasks: Boolean,
    ) = dao.updateWorkspacePermissions(id, permRename, permChangeIcon, permInviteMembers, permDeleteNotes, permEditNotes, permManageTasks)

    suspend fun setActiveWorkspace(id: Long) {
        val workspace = dao.allWorkspaces().firstOrNull { it.id == id }
            ?: throw IllegalArgumentException("Workspace $id does not exist")
        val current = dao.settings()?.toDomain() ?: defaultSettings
        updateSettings(
            current.copy(
                activeWorkspaceId = workspace.id,
                workspaceName = workspace.name,
                workspaceIcon = workspace.icon,
                workspaceIconKind = WorkspaceIconKind.entries.firstOrNull { it.name == workspace.iconKind }
                    ?: WorkspaceIconKind.Text,
                workspaceIconUri = workspace.iconUri,
                workspaceBackgroundUri = workspace.backgroundUri,
            ),
        )
    }

    /**
     * Replaces the local roster for a workspace with the authoritative set fetched from the
     * backend. Upserts each member (keyed by remote userId) and drops anyone no longer returned,
     * so removed members disappear. An empty list clears the roster only when [authoritative] —
     * a failed/offline fetch passes authoritative=false so a transient empty result can't wipe it.
     */
    suspend fun replaceWorkspaceMembers(
        localWorkspaceId: Long,
        members: List<WorkspaceMember>,
        authoritative: Boolean = true,
    ) = database.withTransaction {
        if (members.isEmpty() && !authoritative) return@withTransaction
        members.forEach { member ->
            val existing = dao.workspaceMemberByUserId(localWorkspaceId, member.userId)
            dao.insertWorkspaceMember(
                WorkspaceMemberEntity(
                    id = existing?.id ?: 0,
                    workspaceId = localWorkspaceId,
                    userId = member.userId,
                    username = member.username,
                    displayName = member.displayName,
                    avatarPath = member.avatarPath,
                    role = member.role,
                    joinedAt = member.joinedAt,
                ),
            )
        }
        if (members.isEmpty()) dao.deleteWorkspaceMembersForWorkspace(localWorkspaceId)
        else dao.deleteWorkspaceMembersNotIn(localWorkspaceId, members.map { it.userId })
    }

    suspend fun remoteWorkspaceId(): String? = remoteWorkspaceId(activeWs())

    suspend fun remoteWorkspaceId(localWorkspaceId: Long): String? =
        dao.remoteBinding(localWorkspaceId, WorkspaceObjectType.Workspace.name, localWorkspaceId)?.remoteId

    suspend fun ensureRemoteDocumentSyncId(localWorkspaceId: Long, noteId: Long): String {
        val existing = dao.remoteBinding(localWorkspaceId, RemoteDocumentObjectType, noteId)
        if (existing != null) return existing.syncId
        val syncId = UUID.randomUUID().toString()
        dao.insertRemoteBinding(
            RemoteObjectBindingEntity(
                workspaceId = localWorkspaceId,
                objectType = RemoteDocumentObjectType,
                localId = noteId,
                syncId = syncId,
                updatedAt = System.currentTimeMillis(),
            ),
        )
        return syncId
    }

    suspend fun remoteDocumentBinding(localWorkspaceId: Long, noteId: Long): RemoteObjectBindingEntity? =
        dao.remoteBinding(localWorkspaceId, RemoteDocumentObjectType, noteId)

    suspend fun bindRemoteDocument(
        localWorkspaceId: Long,
        noteId: Long,
        remote: RemoteWorkspaceObject,
    ) {
        val existing = dao.remoteBinding(localWorkspaceId, RemoteDocumentObjectType, noteId)
        dao.insertRemoteBinding(
            existing?.copy(
                syncId = remote.clientSyncId,
                remoteId = remote.id,
                remoteVersion = remote.serverVersion,
                contentHash = remote.contentHash,
                updatedAt = System.currentTimeMillis(),
            ) ?: RemoteObjectBindingEntity(
                workspaceId = localWorkspaceId,
                objectType = RemoteDocumentObjectType,
                localId = noteId,
                syncId = remote.clientSyncId,
                remoteId = remote.id,
                remoteVersion = remote.serverVersion,
                contentHash = remote.contentHash,
                updatedAt = System.currentTimeMillis(),
            ),
        )
    }

    suspend fun importRemoteSharedDocument(
        localWorkspaceId: Long,
        remote: RemoteWorkspaceObject,
    ): ImportedSharedDocument? {
        val payload = remote.decodeSharedDocument() ?: return null
        val existingBinding = dao.remoteBindingByRemoteId(localWorkspaceId, RemoteDocumentObjectType, remote.id)
            ?: dao.remoteBindingBySyncId(localWorkspaceId, RemoteDocumentObjectType, remote.clientSyncId)
        if (existingBinding != null && remote.serverVersion <= existingBinding.remoteVersion) {
            val existing = dao.noteById(existingBinding.localId)?.toDomain() ?: return null
            return ImportedSharedDocument(
                noteId = existing.id,
                title = existing.title,
                document = existing.document,
                layoutMode = existing.overlapMode,
                freeformLayout = existing.freeformLayout,
                canvasSpec = existing.canvasSpec,
                remoteVersion = existingBinding.remoteVersion,
                changed = false,
            )
        }

        val localId = if (existingBinding == null) {
            require(activeWs() == localWorkspaceId) { "Shared documents can only be imported into the active workspace." }
            createNote(
                title = payload.title,
                body = MarkdownBlockCodec.export(payload.document),
                layoutMode = payload.layoutMode,
            ).also { noteId ->
                persistDocument(
                    owner = DocumentOwner.note(noteId),
                    document = payload.document,
                    dirtyBlockIds = payload.document.blocks.mapTo(linkedSetOf()) { it.id },
                    layoutMode = payload.layoutMode,
                    freeformLayout = payload.freeformLayout,
                    canvasSpec = payload.canvasSpec,
                )
            }
        } else {
            database.withTransaction {
                persistDocument(
                    owner = DocumentOwner.note(existingBinding.localId),
                    document = payload.document,
                    dirtyBlockIds = payload.document.blocks.mapTo(linkedSetOf()) { it.id },
                    layoutMode = payload.layoutMode,
                    freeformLayout = payload.freeformLayout,
                    canvasSpec = payload.canvasSpec,
                )
                dao.updateNoteContent(
                    existingBinding.localId,
                    payload.title,
                    payload.document.plainText(),
                    System.currentTimeMillis(),
                )
            }
            existingBinding.localId
        }
        bindRemoteDocument(localWorkspaceId, localId, remote)
        upsertWorkspaceObject(
            WorkspaceObjectType.Note,
            localId,
            payload.title,
            payload.document.plainText().take(160),
            "shared",
            "document",
            0xFF9D6CFF,
            false,
        )
        return ImportedSharedDocument(
            noteId = localId,
            title = payload.title,
            document = payload.document,
            layoutMode = payload.layoutMode,
            freeformLayout = payload.freeformLayout,
            canvasSpec = payload.canvasSpec,
            remoteVersion = remote.serverVersion,
            changed = true,
        )
    }

    suspend fun bindRemoteWorkspace(localWorkspaceId: Long, remoteWorkspaceId: String) {
        require(runCatching { UUID.fromString(remoteWorkspaceId) }.isSuccess) { "Remote workspace id must be a UUID." }
        dao.insertRemoteBinding(
            RemoteObjectBindingEntity(
                workspaceId = localWorkspaceId,
                objectType = WorkspaceObjectType.Workspace.name,
                localId = localWorkspaceId,
                syncId = remoteWorkspaceId,
                remoteId = remoteWorkspaceId,
                remoteVersion = 1,
                updatedAt = System.currentTimeMillis(),
            ),
        )
    }

    /** Local workspace already bound to [remoteWorkspaceId], regardless of which workspace is active. */
    suspend fun localWorkspaceIdForRemote(remoteWorkspaceId: String): Long? =
        dao.bindingByRemoteIdAnyWorkspace(WorkspaceObjectType.Workspace.name, remoteWorkspaceId)?.workspaceId

    /**
     * Creates the local workspace and its remote binding in one transaction, so a failed bind
     * can't strand an unbound local workspace that a retry would then duplicate.
     */
    suspend fun createRemoteBoundWorkspace(name: String, icon: String, remoteWorkspaceId: String): Long =
        database.withTransaction {
            val id = createWorkspace(name, icon)
            bindRemoteWorkspace(id, remoteWorkspaceId)
            id
        }

    suspend fun bindRemoteChatMessage(
        localWorkspaceId: Long,
        localMessageId: Long,
        remote: RemoteWorkspaceObject,
    ) {
        val existing = dao.remoteBinding(
            localWorkspaceId,
            WorkspaceObjectType.ChatMessage.name,
            localMessageId,
        )
        dao.insertRemoteBinding(
            existing?.copy(
                syncId = remote.clientSyncId,
                remoteId = remote.id,
                remoteVersion = remote.serverVersion,
                contentHash = remote.contentHash,
                updatedAt = System.currentTimeMillis(),
            ) ?: RemoteObjectBindingEntity(
                workspaceId = localWorkspaceId,
                objectType = WorkspaceObjectType.ChatMessage.name,
                localId = localMessageId,
                syncId = remote.clientSyncId,
                remoteId = remote.id,
                remoteVersion = remote.serverVersion,
                contentHash = remote.contentHash,
                updatedAt = System.currentTimeMillis(),
            ),
        )
        val remoteUri = remote.content?.get("attachmentExternalUrl")?.jsonPrimitive?.contentOrNull
            ?: remote.content?.get("attachmentStoragePath")?.jsonPrimitive?.contentOrNull?.let { "norfold-cloud://$it" }
        if (remoteUri != null) dao.setChatMessageAttachmentUri(localMessageId, remoteUri)
    }

    suspend fun bindRemoteChatChannel(
        localWorkspaceId: Long,
        localChannelId: Long,
        remote: RemoteWorkspaceObject,
    ) {
        val existing = dao.remoteBinding(localWorkspaceId, WorkspaceObjectType.ChatChannel.name, localChannelId)
        dao.insertRemoteBinding(
            existing?.copy(
                syncId = remote.clientSyncId,
                remoteId = remote.id,
                remoteVersion = remote.serverVersion,
                contentHash = remote.contentHash,
                updatedAt = System.currentTimeMillis(),
            ) ?: RemoteObjectBindingEntity(
                workspaceId = localWorkspaceId,
                objectType = WorkspaceObjectType.ChatChannel.name,
                localId = localChannelId,
                syncId = remote.clientSyncId,
                remoteId = remote.id,
                remoteVersion = remote.serverVersion,
                contentHash = remote.contentHash,
                updatedAt = System.currentTimeMillis(),
            ),
        )
    }

    /** Imports a shared channel and returns its local Room id. Tombstones remove the local copy. */
    suspend fun importRemoteChatChannel(
        localWorkspaceId: Long,
        remote: RemoteWorkspaceObject,
        currentAccountId: String?,
    ): Long? = database.withTransaction {
        val existing = dao.chatChannelBySyncId(localWorkspaceId, remote.clientSyncId)
        if (remote.deletedAt != null) {
            existing?.let {
                dao.deleteChatMessagesForChannel(it.id)
                dao.deleteChatChannel(it.id)
            }
            return@withTransaction existing?.id
        }
        val content = remote.content ?: return@withTransaction null
        val kind = content["kind"]?.jsonPrimitive?.contentOrNull
            ?.let { stored -> ChatChannelKind.entries.firstOrNull { it.name.equals(stored, ignoreCase = true) } }
            ?: ChatChannelKind.Topic
        val participantIds = content["participantUserIds"]?.jsonArray.orEmpty().mapNotNull { it.jsonPrimitive.contentOrNull }
        val participantUsernames = content["participantUsernames"]?.jsonArray.orEmpty().mapNotNull { it.jsonPrimitive.contentOrNull }
        val participantNames = content["participantDisplayNames"]?.jsonArray.orEmpty().mapNotNull { it.jsonPrimitive.contentOrNull }
        val counterpartIndex = participantIds.indexOfFirst { !it.equals(currentAccountId, ignoreCase = true) }
        val counterpartUsername = if (kind == ChatChannelKind.Dm) participantUsernames.getOrNull(counterpartIndex).orEmpty() else ""
        val storedName = content["name"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val displayName = if (kind == ChatChannelKind.Dm) {
            participantNames.getOrNull(counterpartIndex).orEmpty()
                .ifBlank { counterpartUsername }
                .ifBlank { storedName }
                .ifBlank { "Direct message" }
        } else storedName.ifBlank { if (kind == ChatChannelKind.Group) "Group" else "Channel" }
        val visibility = content["visibility"]?.jsonPrimitive?.contentOrNull
            ?.let { stored -> ChatChannelVisibility.entries.firstOrNull { it.name.equals(stored, true) } }
            ?: ChatChannelVisibility.Private
        val securityMode = when (content["securityMode"]?.jsonPrimitive?.contentOrNull?.lowercase()) {
            "e2ee", ChatSecurityMode.EndToEndEncrypted.name.lowercase() -> ChatSecurityMode.EndToEndEncrypted
            "managed", ChatSecurityMode.Managed.name.lowercase() -> ChatSecurityMode.Managed
            // Legacy workspace channel objects were transported as plaintext and are
            // classified Managed by the additive control-plane migration.
            else -> ChatSecurityMode.Managed
        }
        val securityEpochId = content["securityEpochId"]?.jsonPrimitive?.contentOrNull
        val entity = ChatChannelEntity(
            id = existing?.id ?: 0L,
            syncId = remote.clientSyncId,
            kind = kind.name,
            name = displayName,
            counterpartUsername = counterpartUsername,
            icon = content["icon"]?.jsonPrimitive?.contentOrNull.orEmpty().ifBlank { "app.topics" },
            createdAt = content["createdAt"]?.jsonPrimitive?.longOrNull ?: System.currentTimeMillis(),
            workspaceId = localWorkspaceId,
            pinned = existing?.pinned ?: false,
            visibility = visibility.name,
            securityMode = securityMode.name,
            securityEpochId = securityEpochId,
            acknowledgedSecurityEpochId = existing?.acknowledgedSecurityEpochId,
        )
        val localId = dao.insertChatChannel(entity)
        bindRemoteChatChannel(localWorkspaceId, localId, remote)
        localId
    }

    /** Persists a stable client id before attempting delivery so a process restart can retry it. */
    suspend fun markChatMessagePendingRemote(
        localWorkspaceId: Long,
        localMessageId: Long,
        syncId: String,
    ) {
        val existing = dao.remoteBinding(
            localWorkspaceId,
            WorkspaceObjectType.ChatMessage.name,
            localMessageId,
        )
        if (existing?.remoteId != null) return
        dao.insertRemoteBinding(
            existing?.copy(syncId = syncId, updatedAt = System.currentTimeMillis())
                ?: RemoteObjectBindingEntity(
                    workspaceId = localWorkspaceId,
                    objectType = WorkspaceObjectType.ChatMessage.name,
                    localId = localMessageId,
                    syncId = syncId,
                    updatedAt = System.currentTimeMillis(),
                ),
        )
    }

    suspend fun pendingRemoteChatMessages(
        localWorkspaceId: Long,
        limit: Int,
    ): List<Pair<ChatMessageItem, String>> =
        dao.pendingRemoteBindings(localWorkspaceId, WorkspaceObjectType.ChatMessage.name, limit)
            .mapNotNull { binding ->
                dao.chatMessageById(binding.localId)?.toDomain()?.let { it to binding.syncId }
            }

    suspend fun importRemoteChatMessage(localWorkspaceId: Long, remote: RemoteWorkspaceObject): Boolean =
        database.withTransaction {
            val content = remote.content ?: return@withTransaction false
            val channelSyncId = content["channelSyncId"]?.jsonPrimitive?.contentOrNull
            val localChannelId = channelSyncId?.let { dao.chatChannelBySyncId(localWorkspaceId, it)?.id }
            if (channelSyncId != null && localChannelId == null) return@withTransaction false
            val existingBinding =
                dao.remoteBindingByRemoteId(localWorkspaceId, WorkspaceObjectType.ChatMessage.name, remote.id)
                    ?: dao.remoteBindingBySyncId(
                        localWorkspaceId,
                        WorkspaceObjectType.ChatMessage.name,
                        remote.clientSyncId,
                    )
            if (existingBinding != null) {
                val local = dao.chatMessageById(existingBinding.localId)
                val changed = local != null && remote.serverVersion > existingBinding.remoteVersion
                if (changed) {
                    dao.insertChatMessage(
                        local!!.copy(
                            body = content["body"]?.jsonPrimitive?.contentOrNull ?: local.body,
                            replyPreview = content["replyPreview"]?.jsonPrimitive?.contentOrNull,
                            editedAt = content["editedAt"]?.jsonPrimitive?.longOrNull,
                            reactionsJson = content["reactionsJson"]?.jsonPrimitive?.contentOrNull ?: local.reactionsJson,
                            pinned = content["pinned"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: local.pinned,
                            channelId = localChannelId,
                            securityMode = content["securityMode"]?.jsonPrimitive?.contentOrNull
                                ?.let(::chatSecurityModeName) ?: local.securityMode,
                            securityEpochId = content["securityEpochId"]?.jsonPrimitive?.contentOrNull
                                ?: local.securityEpochId,
                            expiresAt = content["expiresAt"]?.jsonPrimitive?.longOrNull ?: local.expiresAt,
                        ),
                    )
                }
                if (existingBinding.remoteId == null || changed) {
                    dao.insertRemoteBinding(
                        existingBinding.copy(
                            remoteId = remote.id,
                            remoteVersion = remote.serverVersion,
                            contentHash = remote.contentHash,
                            updatedAt = System.currentTimeMillis(),
                        ),
                    )
                }
                return@withTransaction changed
            }
            val username = content["authorUsername"]?.jsonPrimitive?.contentOrNull.orEmpty().ifBlank { "member" }
            val displayName = content["authorDisplayName"]?.jsonPrimitive?.contentOrNull.orEmpty().ifBlank { username }
            val body = content["body"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val color = content["color"]?.jsonPrimitive?.longOrNull ?: 0xFF7E57FF
            val createdAt = content["createdAt"]?.jsonPrimitive?.longOrNull ?: System.currentTimeMillis()
            // A sender's local content:// uri is useless here; the storage path is kept under a
            // norfold-cloud:// scheme so the UI knows to resolve a signed URL on demand.
            val attachmentStoragePath = content["attachmentStoragePath"]?.jsonPrimitive?.contentOrNull
            val attachmentExternalUrl = content["attachmentExternalUrl"]?.jsonPrimitive?.contentOrNull
            val localId = dao.insertChatMessage(
                ChatMessageEntity(
                    authorUsername = username,
                    authorDisplayName = displayName,
                    body = body,
                    color = color,
                    createdAt = createdAt,
                    attachmentName = content["attachmentName"]?.jsonPrimitive?.contentOrNull,
                    attachmentMimeType = content["attachmentMimeType"]?.jsonPrimitive?.contentOrNull,
                    attachmentSizeBytes = content["attachmentSizeBytes"]?.jsonPrimitive?.longOrNull,
                    attachmentUri = attachmentExternalUrl ?: attachmentStoragePath?.let { "norfold-cloud://$it" },
                    replyPreview = content["replyPreview"]?.jsonPrimitive?.contentOrNull,
                    editedAt = content["editedAt"]?.jsonPrimitive?.longOrNull,
                    reactionsJson = content["reactionsJson"]?.jsonPrimitive?.contentOrNull ?: "{}",
                    pinned = content["pinned"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false,
                    channelId = localChannelId,
                    securityMode = content["securityMode"]?.jsonPrimitive?.contentOrNull
                        ?.let(::chatSecurityModeName),
                    securityEpochId = content["securityEpochId"]?.jsonPrimitive?.contentOrNull,
                    expiresAt = content["expiresAt"]?.jsonPrimitive?.longOrNull,
                    workspaceId = localWorkspaceId,
                ),
            )
            dao.insertWorkspaceObject(
                WorkspaceObjectEntity(
                    objectType = WorkspaceObjectType.ChatMessage.name,
                    sourceId = localId,
                    title = displayName,
                    summary = body.take(160),
                    icon = "chat",
                    color = color,
                    createdAt = createdAt,
                    updatedAt = createdAt,
                    workspaceId = localWorkspaceId,
                ),
            )
            bindRemoteChatMessage(localWorkspaceId, localId, remote)
            true
        }

    /** Deletes a workspace and all of its content. Refuses to delete the last remaining workspace. */
    suspend fun deleteWorkspace(id: Long): Boolean = database.withTransaction {
        if (dao.allWorkspaces().size <= 1) return@withTransaction false
        dao.deleteNoteDocumentsForWorkspace(id)
        dao.deleteTaskDocumentsForWorkspace(id)
        dao.deleteCalendarEventDocumentsForWorkspace(id)
        dao.deleteNotesForWorkspace(id)
        dao.deleteNotebooksForWorkspace(id)
        // Board hierarchy has no FK cascades; values/checklists resolve through the tasks
        // subquery, so they must go before the tasks themselves.
        dao.deleteTaskPropertyValuesForWorkspace(id)
        dao.deleteTaskChecklistItemsForWorkspace(id)
        dao.deleteTaskPropertyDefinitionsForWorkspace(id)
        dao.deleteTaskColumnsForWorkspace(id)
        dao.deleteTaskBoardsForWorkspace(id)
        dao.deleteTasksForWorkspace(id)
        dao.deleteChatForWorkspace(id)
        dao.deleteChatChannelsForWorkspace(id)
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
        true
    }

    suspend fun noteById(id: Long): Note? = dao.noteById(id)?.toDomain()

    suspend fun documentByOwner(owner: DocumentOwner): OwnedDocument? =
        dao.documentByOwner(owner.type.storageValue, owner.id)?.toDomain()

    fun observeDocument(owner: DocumentOwner): Flow<OwnedDocument?> =
        dao.observeDocumentByOwner(owner.type.storageValue, owner.id).map { it?.toDomain() }

    suspend fun saveDocument(
        owner: DocumentOwner,
        document: BlockDocument,
        dirtyBlockIds: Set<String> = document.blocks.mapTo(linkedSetOf()) { it.id },
        layoutMode: DocOverlapMode? = null,
        freeformLayout: Map<String, FreeformPlacement>? = null,
        canvasSpec: DocCanvasSpec? = null,
    ): OwnedDocument = database.withTransaction {
        persistDocument(owner, document, dirtyBlockIds, layoutMode, freeformLayout, canvasSpec)
    }

    private suspend fun persistDocument(
        owner: DocumentOwner,
        document: BlockDocument,
        dirtyBlockIds: Set<String>,
        layoutMode: DocOverlapMode?,
        freeformLayout: Map<String, FreeformPlacement>?,
        canvasSpec: DocCanvasSpec?,
        updatedAt: Long = System.currentTimeMillis(),
    ): OwnedDocument {
        val existing = dao.documentByOwner(owner.type.storageValue, owner.id)?.toDomain()
        val normalized = document.normalized()
        val resolvedMode = layoutMode ?: existing?.layoutMode ?: DocOverlapMode.Reflow
        val resolvedLayout = freeformLayout ?: existing?.freeformLayout.orEmpty()
        val resolvedCanvas = canvasSpec ?: existing?.canvasSpec ?: DocCanvasSpec()
        dao.upsertDocument(
            DocumentEntity(
                id = owner.documentId,
                ownerType = owner.type.storageValue,
                ownerId = owner.id,
                layoutMode = resolvedMode.name.lowercase(),
                layoutJson = DocLayoutJson.encode(resolvedLayout, resolvedCanvas),
                createdAt = existing?.createdAt ?: updatedAt,
                updatedAt = updatedAt,
            ),
        )
        val existingRows = dao.blocksForDocument(owner.documentId).associateBy(DocumentBlockEntity::blockId)
        val next = normalized.blocks.mapIndexed { position, block ->
            DocumentBlockEntity(block.id, owner.documentId, position, BlockDocumentJson.encodeBlock(block), updatedAt)
        }
        val changed = next.filter { candidate ->
            val old = existingRows[candidate.blockId]
            candidate.blockId in dirtyBlockIds || old == null || old.position != candidate.position || old.payloadJson != candidate.payloadJson
        }
        if (changed.isNotEmpty()) dao.upsertDocumentBlocks(changed)
        val removed = existingRows.keys - next.mapTo(hashSetOf(), DocumentBlockEntity::blockId)
        if (removed.isNotEmpty()) dao.deleteDocumentBlocks(owner.documentId, removed.toList())
        return OwnedDocument(owner, normalized, resolvedMode, resolvedLayout, resolvedCanvas, existing?.createdAt ?: updatedAt, updatedAt)
    }

    suspend fun createNote(
        title: String = "Untitled doc",
        body: String = "",
        notebookId: Long? = null,
        tagNames: List<String> = emptyList(),
        pinned: Boolean = false,
        starred: Boolean = false,
        layoutMode: DocOverlapMode = DocOverlapMode.Reflow,
    ): Long {
        val now = System.currentTimeMillis()
        val document = MarkdownBlockCodec.import(body)
        val id = database.withTransaction {
            val noteId = dao.insertNote(
                NoteEntity(
                    title = title.ifBlank { "Untitled doc" },
                    searchText = document.plainText(),
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
            persistDocument(DocumentOwner.note(noteId), document, document.blocks.mapTo(linkedSetOf()) { it.id }, layoutMode, null, null, now)
            noteId
        }
        setTags(id, tagNames)
        val objectId = upsertWorkspaceObject(WorkspaceObjectType.Note, id, title.ifBlank { "Untitled doc" }, body.take(160), tagNames.joinToString(","), "note", 0xFF9D6CFF, pinned)
        val typeLabel = if (layoutMode == DocOverlapMode.Reflow) "note" else "document"
        recordActivity(WorkspaceActivityType.Created, "You", "Created $typeLabel", title.ifBlank { "Untitled $typeLabel" }, objectType = WorkspaceObjectType.Note, sourceId = id)
        recordHistory(WorkspaceHistoryType.Created, objectId, "You", "Created $typeLabel", afterValue = title.ifBlank { "Untitled $typeLabel" })
        return id
    }

    suspend fun updateNote(note: Note, title: String, body: String) {
        updateNote(note, title, MarkdownBlockCodec.import(body))
    }

    suspend fun updateNote(
        note: Note,
        title: String,
        document: BlockDocument,
        dirtyBlockIds: Set<String> = document.blocks.mapTo(linkedSetOf()) { it.id },
    ) {
        val now = System.currentTimeMillis()
        val normalized = database.withTransaction {
            val persisted = persistDocument(DocumentOwner.note(note.id), document, dirtyBlockIds, null, null, null, now)
            dao.updateNoteContent(note.id, title.ifBlank { "Untitled doc" }, persisted.document.plainText(), now)
            persisted.document
        }
        val markdown = MarkdownBlockCodec.export(normalized)
        val actor = dao.settings()?.let { it.syncPublicName.ifBlank { it.syncUserName.ifBlank { "You" } } } ?: "You"
        val objectId = upsertWorkspaceObject(WorkspaceObjectType.Note, note.id, title.ifBlank { "Untitled doc" }, normalized.plainText().take(160), note.tags.joinToString(",") { it.name }, "note", 0xFF9D6CFF, note.pinned)
        recordActivity(WorkspaceActivityType.Updated, actor, "Updated doc", title.ifBlank { "Untitled doc" }, objectType = WorkspaceObjectType.Note, sourceId = note.id)
        if (note.title != title || note.document != normalized) {
            recordHistory(
                WorkspaceHistoryType.Updated,
                objectId,
                actor,
                "Updated doc",
                beforeValue = note.bodyMarkdown.take(500),
                afterValue = markdown.take(500),
            )
            dirtyBlockIds.forEach { blockId ->
                val beforeBlock = note.document.findById(blockId)
                val afterBlock = normalized.findById(blockId)
                if (beforeBlock != afterBlock) {
                    recordHistory(
                        WorkspaceHistoryType.Updated,
                        objectId,
                        actor,
                        when {
                            beforeBlock == null -> "Added block"
                            afterBlock == null -> "Removed block"
                            else -> "Edited block"
                        },
                        beforeValue = beforeBlock?.let(BlockDocumentJson::encodeBlock).orEmpty(),
                        afterValue = afterBlock?.let(BlockDocumentJson::encodeBlock).orEmpty(),
                        blockId = blockId,
                    )
                }
            }
        }
    }

    suspend fun updateNoteCover(note: Note, coverUri: String?, coverMimeType: String?) {
        dao.updateNoteCover(note.id, coverUri?.takeIf { it.isNotBlank() }, coverMimeType?.takeIf { it.isNotBlank() }, System.currentTimeMillis())
    }

    suspend fun addNotebook(name: String, scope: String = "notes", iconId: String = "app.folder"): Long {
        val normalizedScope = scope.takeIf { it == "documents" } ?: "notes"
        val workspaceId = activeWs()
        val order = dao.allNotebooks().count { it.workspaceId == workspaceId && it.scope == normalizedScope }
        return dao.insertNotebook(
            NotebookEntity(
                name = name.ifBlank { if (normalizedScope == "documents") "New document folder" else "New notes folder" },
                color = 0xFF8B5CF6,
                sortOrder = order,
                workspaceId = workspaceId,
                scope = normalizedScope,
                iconId = iconId.ifBlank { "app.folder" },
            ),
        )
    }

    suspend fun updateNotebook(notebook: Notebook, name: String, iconId: String) {
        dao.updateNotebook(notebook.id, name.trim().ifBlank { notebook.name }, notebook.scope, iconId.ifBlank { "app.folder" })
    }

    suspend fun addTag(name: String): Long = getOrCreateTag(name.trim().removePrefix("#"), scope = "notes").id

    suspend fun renameTag(tag: Tag, name: String) {
        val displayName = name.trim().trimStart('#').replace(Regex("\\s+"), " ")
        require(displayName.isNotBlank()) { "Tag name cannot be blank" }
        val normalized = normalizeTagName(displayName)
        val conflict = dao.tagByScopeAndNormalizedName(tag.scope, normalized)
        require(conflict == null || conflict.id == tag.id) { "A tag with that name already exists" }
        dao.renameTag(tag.id, displayName, normalized)
    }

    suspend fun deleteTag(tag: Tag) = dao.deleteTag(tag.id)

    suspend fun setNoteTags(note: Note, names: List<String>) {
        setTags(note.id, names)
        upsertWorkspaceObject(
            WorkspaceObjectType.Note,
            note.id,
            note.title,
            note.document.plainText().take(160),
            names.joinToString(","),
            "note",
            0xFF9D6CFF,
            note.pinned,
        )
        recordActivity(
            WorkspaceActivityType.Updated,
            "You",
            "Updated doc tags",
            names.joinToString(", ").ifBlank { "Removed all tags" },
            objectType = WorkspaceObjectType.Note,
            sourceId = note.id,
        )
    }

    suspend fun addTaskTag(boardId: Long, name: String): Long {
        val displayName = name.trim().trimStart('#').replace(Regex("\\s+"), " ")
        require(displayName.isNotBlank()) { "Tag name cannot be blank" }
        return getOrCreateTag(displayName, scope = "board:$boardId").id
    }

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
        val columnId = destinationColumnForNewTask(boardId, status)
        val order = dao.maxTaskSortOrder(boardId, columnId) + 1
        val initialDocument = MarkdownBlockCodec.import(description)
        val summary = initialDocument.plainText()
        val id = database.withTransaction {
            val taskId = dao.insertTask(
                TaskEntity(
                    title = title.ifBlank { "New task" },
                    description = summary,
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
            persistDocument(DocumentOwner.task(taskId), initialDocument, initialDocument.blocks.mapTo(linkedSetOf()) { it.id }, null, null, null, now)
            taskId
        }
        val objectId = upsertWorkspaceObject(WorkspaceObjectType.Task, id, title.ifBlank { "New task" }, summary, "", "task", 0xFF56CC98)
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
        ensureDefaultTaskProperties(boardId)
        recordActivity(WorkspaceActivityType.Created, "You", "Created task board", name.trim().ifBlank { "New board" })
        return boardId
    }

    suspend fun renameTaskBoard(boardId: Long, name: String) {
        val nextName = name.trim().ifBlank { "Task board" }
        dao.updateTaskBoardName(boardId, nextName, System.currentTimeMillis())
        recordActivity(WorkspaceActivityType.Updated, "You", "Renamed task board", nextName)
    }
    suspend fun createTaskColumn(boardId: Long, name: String, status: TaskStatus? = null, color: Long = 0L, sortOrder: Int? = null): Long {
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
            ensureDefaultTaskProperties(existing.id)
            return existing.id
        }
        val now = System.currentTimeMillis()
        val boardId = dao.insertTaskBoard(TaskBoardEntity(name = "Default board", workspaceId = workspaceId, createdAt = now, updatedAt = now))
        ensureDefaultTaskProperties(boardId)
        return boardId
    }

    private suspend fun configureTaskWorkflow(columns: List<Pair<String, TaskStatus?>>) {
        val boardId = ensureDefaultTaskBoard(activeWs())
        if (dao.taskColumnsForBoard(boardId).isNotEmpty()) return
        columns.forEachIndexed { index, (name, status) ->
            createTaskColumn(boardId, name, status, 0L, index)
        }
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
        val name = when (status) {
            TaskStatus.Todo -> "Next"
            TaskStatus.Doing -> "In progress"
            TaskStatus.Done -> "Complete"
        }
        return createTaskColumn(boardId, name, status, 0L)
    }

    private suspend fun destinationColumnForNewTask(boardId: Long, status: TaskStatus): Long {
        dao.taskColumnForStatus(boardId, status.name)?.let { return it.id }
        dao.taskColumnsForBoard(boardId).firstOrNull()?.let { return it.id }
        return createTaskColumn(boardId, "Tasks", status, 0L)
    }
    suspend fun updateTaskMeta(task: TaskItem, priority: TaskPriority, dueAt: Long?, assignee: String) =
        dao.updateTaskMeta(task.id, priority.name, dueAt, assignee, System.currentTimeMillis())
    suspend fun updateTaskDetails(task: TaskItem, title: String, description: String, status: TaskStatus, priority: TaskPriority, dueAt: Long?, assignee: String, labels: String) =
        updateTaskDetailsAndDocument(task, title, description, status, priority, dueAt, assignee, labels)

    private suspend fun updateTaskDetailsAndDocument(task: TaskItem, title: String, description: String, status: TaskStatus, priority: TaskPriority, dueAt: Long?, assignee: String, labels: String) {
        val current = documentByOwner(DocumentOwner.task(task.id))
        val document = if (description != task.description) MarkdownBlockCodec.import(description) else current?.document ?: MarkdownBlockCodec.import(task.description)
        val summary = document.plainText()
        database.withTransaction {
            persistDocument(DocumentOwner.task(task.id), document, document.blocks.mapTo(linkedSetOf()) { it.id }, null, null, null)
            dao.updateTaskDetails(
            id = task.id,
            title = title.ifBlank { "Untitled task" },
            description = summary,
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
        }
    }

    suspend fun ensureTaskDocument(task: TaskItem): OwnedDocument {
        documentByOwner(DocumentOwner.task(task.id))?.let { return it }
        val initial = MarkdownBlockCodec.import(task.description)
        val saved = saveDocument(DocumentOwner.task(task.id), initial)
        val summary = saved.document.plainText()
        if (summary != task.description) {
            dao.updateTaskDetails(
                task.id,
                task.title,
                summary,
                task.status.name,
                task.priority.name,
                task.dueAt,
                task.assignee,
                task.labels,
                task.attachmentName,
                task.attachmentMimeType,
                task.attachmentUri,
                task.attachmentSizeBytes,
                saved.updatedAt,
            )
        }
        return saved
    }

    suspend fun updateTaskDocument(
        task: TaskItem,
        title: String,
        document: BlockDocument,
        dirtyBlockIds: Set<String>,
    ) {
        val now = System.currentTimeMillis()
        val normalized = database.withTransaction {
            val persisted = persistDocument(DocumentOwner.task(task.id), document, dirtyBlockIds, null, null, null, now)
            dao.updateTaskDetails(
                task.id,
                title.ifBlank { "Untitled task" },
                persisted.document.plainText(),
                task.status.name,
                task.priority.name,
                task.dueAt,
                task.assignee,
                task.labels,
                task.attachmentName,
                task.attachmentMimeType,
                task.attachmentUri,
                task.attachmentSizeBytes,
                now,
            )
            persisted.document
        }
        val objectId = upsertWorkspaceObject(WorkspaceObjectType.Task, task.id, title.ifBlank { "Untitled task" }, normalized.plainText().take(160), task.labels, "task", 0xFF56CC98)
        recordActivity(WorkspaceActivityType.Updated, "You", "Updated task doc", title.ifBlank { "Untitled task" }, objectType = WorkspaceObjectType.Task, sourceId = task.id, objectId = objectId)
        recordHistory(WorkspaceHistoryType.Updated, objectId, "You", "Updated task doc", beforeValue = task.description.take(500), afterValue = normalized.plainText().take(500))
    }
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
        if (property.type == TaskPropertyType.Text) {
            val document = MarkdownBlockCodec.import(value)
            updateTaskDocument(task, task.title, document, document.blocks.mapTo(linkedSetOf()) { it.id })
            return
        }
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

    suspend fun taskById(id: Long): TaskItem? = dao.taskById(id)?.toDomain()

    suspend fun deleteTask(task: TaskItem) = database.withTransaction {
        dao.taskById(task.id)?.let { row ->
            dao.workspaceObjectIdForSource(row.workspaceId, WorkspaceObjectType.Task.name, task.id)?.let { objectId ->
                deleteWorkspaceObjectGraph(objectId)
            }
        }
        dao.deleteDocumentForOwner(DocumentOwnerType.Task.storageValue, task.id)
        dao.deleteTask(task.id)
    }

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
        createdAt: Long = System.currentTimeMillis(),
        channelId: Long? = null,
        replyPreview: String? = null,
        expiresAt: Long? = null,
    ): Long {
        val id = dao.insertChatMessage(
            ChatMessageEntity(
                authorUsername = username.ifBlank { "you" },
                authorDisplayName = displayName.ifBlank { username.ifBlank { "You" } },
                body = body,
                color = color,
                createdAt = createdAt,
                system = system,
                attachmentName = attachmentName,
                attachmentMimeType = attachmentMimeType,
                attachmentUri = attachmentUri,
                attachmentSizeBytes = attachmentSizeBytes,
                workspaceId = activeWs(),
                channelId = channelId,
                replyPreview = replyPreview,
                expiresAt = expiresAt,
            ),
        )
        // Only the shared General feed feeds the workspace graph/activity. Topic/DM/group messages
        // stay scoped to their channel so private side conversations don't surface as workspace objects.
        if (channelId == null) {
            val chatObjectId = upsertWorkspaceObject(WorkspaceObjectType.ChatMessage, id, displayName.ifBlank { username.ifBlank { "Message" } }, body.take(160), "", "chat", color)
            recordActivity(if (attachmentName != null) WorkspaceActivityType.Uploaded else WorkspaceActivityType.Commented, displayName.ifBlank { username.ifBlank { "You" } }, if (attachmentName != null) "Shared file in chat" else "Sent chat message", body.take(80).ifBlank { attachmentName.orEmpty() }, objectType = WorkspaceObjectType.ChatMessage, sourceId = id)
            recordHistory(WorkspaceHistoryType.Comment, chatObjectId, displayName.ifBlank { username.ifBlank { "You" } }, "Sent chat message", afterValue = body.take(500).ifBlank { attachmentName.orEmpty() })
        }
        if (!attachmentUri.isNullOrBlank() && !attachmentName.isNullOrBlank()) {
            val objectId = upsertWorkspaceObject(WorkspaceObjectType.File, null, attachmentName, attachmentMimeType.orEmpty(), "", "file", 0xFF4AADFF)
            dao.insertWorkspaceFile(WorkspaceFileEntity(objectId = objectId, displayName = attachmentName, mimeType = attachmentMimeType.orEmpty(), uri = attachmentUri, sizeBytes = attachmentSizeBytes ?: 0, createdAt = System.currentTimeMillis(), updatedAt = System.currentTimeMillis(), workspaceId = activeWs()))
            recordHistory(WorkspaceHistoryType.Attachment, objectId, displayName.ifBlank { username.ifBlank { "You" } }, "Shared chat file", afterValue = "$attachmentName\n${attachmentMimeType.orEmpty()}\n$attachmentUri")
        }
        return id
    }

    /**
     * Persists an inline notification reply in the workspace that originated the notification.
     * Notification actions can run while another workspace is active, so they must not use
     * [activeWs]. The pending remote binding lets reconciliation retry the reply after an offline
     * or interrupted send without ever creating a second local bubble.
     */
    suspend fun queueNotificationChatReply(
        workspaceId: Long,
        channelId: Long?,
        username: String,
        displayName: String,
        body: String,
        color: Long,
        createdAt: Long,
        syncId: String,
    ): Long {
        val localId = dao.insertChatMessage(
            ChatMessageEntity(
                authorUsername = username.ifBlank { "you" },
                authorDisplayName = displayName.ifBlank { username.ifBlank { "You" } },
                body = body,
                color = color,
                createdAt = createdAt,
                workspaceId = workspaceId,
                channelId = channelId,
            ),
        )
        markChatMessagePendingRemote(workspaceId, localId, syncId)
        return localId
    }

    suspend fun editChatMessage(message: ChatMessageItem, body: String) {
        val clean = body.trim()
        require(clean.isNotBlank()) { "Message cannot be empty" }
        dao.editChatMessage(message.id, clean, System.currentTimeMillis())
    }

    suspend fun setChatMessagePinned(messageId: Long, pinned: Boolean) = dao.setChatMessagePinned(messageId, pinned)

    /** Private bookmark: local-only by design and excluded from every remote payload. */
    suspend fun setChatMessageStarred(messageId: Long, starred: Boolean) =
        dao.setChatMessageStarred(messageId, starred)

    suspend fun toggleChatReaction(message: ChatMessageItem, emoji: String, username: String) {
        val root = runCatching { JSONObject(message.reactionsJson) }.getOrElse { JSONObject() }
        // One reaction per user: remove the user from every other emoji first.
        root.keys().asSequence().toList().forEach { key ->
            if (key != emoji) {
                val arr = root.optJSONArray(key) ?: return@forEach
                val kept = (0 until arr.length()).mapNotNull { arr.optString(it).takeIf { u -> u.isNotBlank() && !u.equals(username, ignoreCase = true) } }
                if (kept.isEmpty()) root.remove(key) else root.put(key, JSONArray(kept))
            }
        }
        val current = root.optJSONArray(emoji) ?: JSONArray()
        val users = (0 until current.length()).mapNotNull { current.optString(it).takeIf(String::isNotBlank) }.toMutableList()
        if (!users.remove(username)) users += username
        if (users.isEmpty()) root.remove(emoji) else root.put(emoji, JSONArray(users))
        dao.setChatMessageReactions(message.id, root.toString())
    }

    /** Creates a topic or group channel. Returns the new channel's id. */
    suspend fun createChatChannel(
        kind: ChatChannelKind,
        name: String,
        icon: String,
        syncId: String = UUID.randomUUID().toString(),
        counterpartUsername: String = "",
        createdAt: Long = System.currentTimeMillis(),
    ): Long = dao.insertChatChannel(
        ChatChannelEntity(
            syncId = syncId,
            kind = kind.name,
            name = name.trim(),
            counterpartUsername = counterpartUsername,
            icon = icon,
            createdAt = createdAt,
            workspaceId = activeWs(),
        ),
    )

    /**
     * Finds the existing DM channel for a person, or creates one. Keeps a single conversation per
     * counterpart instead of spawning a new channel every time the row is tapped.
     */
    suspend fun openOrCreateDmChannel(counterpartUsername: String, displayName: String, syncId: String): Long {
        val ws = activeWs()
        dao.chatChannelByCounterpart(ws, ChatChannelKind.Dm.name, counterpartUsername)?.let { return it.id }
        dao.chatChannelBySyncId(ws, syncId)?.let { return it.id }
        return dao.insertChatChannel(
            ChatChannelEntity(
                syncId = syncId,
                kind = ChatChannelKind.Dm.name,
                name = displayName.ifBlank { counterpartUsername },
                counterpartUsername = counterpartUsername,
                icon = "app.people",
                createdAt = System.currentTimeMillis(),
                workspaceId = ws,
            ),
        )
    }

    /** Pins or unpins a conversation in the chat inbox. */
    suspend fun setChatChannelPinned(channelId: Long, pinned: Boolean) {
        dao.setChatChannelPinned(channelId, pinned)
    }

    suspend fun setChatChannelDefaultLifespan(channelId: Long, seconds: Long?) {
        require(seconds == null || seconds in 60L..31_536_000L) {
            "Message lifespan must be between one minute and one year."
        }
        dao.setChatChannelDefaultLifespan(channelId, seconds)
    }

    /**
     * Finds (or creates) the local channel row that mirrors an account-level
     * conversation. Keyed by [accountConversationId] so the same conversation
     * reconciles across refreshes; the non-null id is what routes sends onto the
     * account transport (ChatScope.Account) instead of the workspace transport.
     */
    suspend fun openOrCreateAccountChannel(
        accountConversationId: String,
        kind: ChatChannelKind,
        name: String,
        counterpartUsername: String = "",
        icon: String = "app.people",
        coverUri: String? = null,
        description: String = "",
        rules: String = "",
        rulesReminderDays: Int = 7,
        createdAt: Long = System.currentTimeMillis(),
    ): Long {
        val ws = activeWs()
        val accountId = requireNotNull(activeChatAccountId.value) {
            "A signed-in account is required for a private conversation."
        }
        dao.chatChannelByAccountConversation(accountId, accountConversationId)?.let { existing ->
            // Keep the display name fresh (a peer may have set a profile name since first open).
            dao.insertChatChannel(
                existing.copy(
                    name = name.trim().ifBlank { existing.name },
                    icon = icon.ifBlank { existing.icon },
                    coverUri = coverUri,
                    description = description,
                    rules = rules,
                    rulesReminderDays = rulesReminderDays.coerceIn(1, 365),
                ),
            )
            return existing.id
        }
        return dao.insertChatChannel(
            ChatChannelEntity(
                syncId = UUID.randomUUID().toString(),
                kind = kind.name,
                name = name.trim().ifBlank { counterpartUsername },
                counterpartUsername = counterpartUsername,
                icon = icon,
                coverUri = coverUri,
                description = description,
                rules = rules,
                rulesReminderDays = rulesReminderDays.coerceIn(1, 365),
                createdAt = createdAt,
                workspaceId = ws,
                accountConversationId = accountConversationId,
                accountOwnerId = accountId,
            ),
        )
    }

    /** The local channel mirroring an account conversation, if one exists. */
    suspend fun accountChannelId(accountConversationId: String): Long? =
        activeChatAccountId.value?.let { accountId ->
            dao.chatChannelByAccountConversation(accountId, accountConversationId)?.id
        }

    suspend fun updateChatChannelSecurity(
        channelId: Long,
        mode: ChatSecurityMode,
        securityEpochId: String?,
        acknowledged: Boolean,
    ) {
        dao.setChatChannelSecurity(
            id = channelId,
            securityMode = mode.name,
            securityEpochId = securityEpochId,
            acknowledgedSecurityEpochId = securityEpochId.takeIf { acknowledged },
        )
    }

    suspend fun chatMessageById(messageId: Long): ChatMessageItem? =
        dao.chatMessageById(messageId)?.toDomain()

    suspend fun setChatMessageSecurity(
        messageId: Long,
        mode: ChatSecurityMode,
        securityEpochId: String,
    ) {
        dao.setChatMessageSecurity(messageId, mode.name, securityEpochId)
    }

    suspend fun setChatMessageOriginPayload(messageId: Long, originPayloadJson: String) {
        dao.setChatMessageOriginPayload(messageId, originPayloadJson)
    }

    suspend fun updateChatChannelRichMetadata(
        channelId: Long,
        name: String,
        icon: String,
        coverUri: String?,
        description: String,
        rules: String,
        rulesReminderDays: Int,
    ) {
        dao.updateChatChannelRichMetadata(
            id = channelId,
            name = name.trim(),
            icon = icon.trim(),
            coverUri = coverUri?.trim()?.ifBlank { null },
            description = description.trim(),
            rules = rules.trim(),
            rulesReminderDays = rulesReminderDays.coerceIn(1, 365),
        )
    }

    /**
     * Imports one account-level (NorfoldSocial) direct message into the local
     * chat_messages store. Dedupes on the remote client_sync_id via the same
     * remote_object_bindings table the workspace path uses — client_sync_id is
     * the one key present on BOTH our own optimistic send and the realtime echo,
     * so it collapses the self-echo and any reconnect replay. Returns true when a
     * new local row was inserted.
     *
     * The parent account channel must already exist locally (channel-first
     * ordering, mirroring importRemoteChatMessage's guard) — callers resolve it
     * through openOrCreateAccountChannel before draining messages.
     */
    suspend fun importAccountDirectMessage(
        localChannelId: Long,
        clientSyncId: String,
        remoteMessageId: String,
        senderUsername: String,
        senderDisplayName: String,
        body: String,
        color: Long,
        createdAt: Long,
        isOwn: Boolean,
        securityEpochId: String? = null,
        originPayloadJson: String? = null,
        expiresAt: Long? = null,
    ): Boolean = database.withTransaction {
        val ws = activeWs()
        // Own messages already have a local row + binding (keyed by the same
        // client_sync_id) from the optimistic send; reconnect replays already imported.
        val existing = dao.bindingBySyncIdAnyWorkspace(WorkspaceObjectType.ChatMessage.name, clientSyncId)
        if (existing != null) return@withTransaction false
        val localId = dao.insertChatMessage(
            ChatMessageEntity(
                authorUsername = senderUsername,
                authorDisplayName = senderDisplayName.ifBlank { senderUsername },
                body = body,
                color = color,
                createdAt = createdAt,
                workspaceId = ws,
                channelId = localChannelId,
                // Incoming peer messages carry no receipt; our own echoes were already stamped Sent.
                receiptStatus = if (isOwn) ChatReceiptStatus.Sent.name else null,
                securityMode = securityEpochId?.let { ChatSecurityMode.EndToEndEncrypted.name },
                securityEpochId = securityEpochId,
                originPayloadJson = originPayloadJson,
                expiresAt = expiresAt,
            ),
        )
        dao.insertRemoteBinding(
            RemoteObjectBindingEntity(
                workspaceId = ws,
                objectType = WorkspaceObjectType.ChatMessage.name,
                localId = localId,
                syncId = clientSyncId,
                remoteId = remoteMessageId,
                remoteVersion = 1,
                updatedAt = System.currentTimeMillis(),
            ),
        )
        true
    }

    /** Imports an opaque-relay workspace message without creating a plaintext cloud object. */
    suspend fun importSecureWorkspaceMessage(
        localWorkspaceId: Long,
        localChannelId: Long?,
        clientSyncId: String,
        relayId: String,
        senderUsername: String,
        senderDisplayName: String,
        body: String,
        color: Long,
        createdAt: Long,
        isOwn: Boolean,
        securityEpochId: String,
        originPayloadJson: String? = null,
        expiresAt: Long? = null,
    ): Boolean = database.withTransaction {
        val existing = dao.remoteBindingBySyncId(
            localWorkspaceId,
            WorkspaceObjectType.ChatMessage.name,
            clientSyncId,
        )
        if (existing != null) return@withTransaction false
        val localId = dao.insertChatMessage(
            ChatMessageEntity(
                authorUsername = senderUsername,
                authorDisplayName = senderDisplayName.ifBlank { senderUsername },
                body = body,
                color = color,
                createdAt = createdAt,
                workspaceId = localWorkspaceId,
                channelId = localChannelId,
                receiptStatus = if (isOwn) ChatReceiptStatus.Sent.name else null,
                securityMode = ChatSecurityMode.EndToEndEncrypted.name,
                securityEpochId = securityEpochId,
                originPayloadJson = originPayloadJson,
                expiresAt = expiresAt,
            ),
        )
        dao.insertRemoteBinding(
            RemoteObjectBindingEntity(
                workspaceId = localWorkspaceId,
                objectType = WorkspaceObjectType.ChatMessage.name,
                localId = localId,
                syncId = clientSyncId,
                remoteId = relayId,
                remoteVersion = 1,
                updatedAt = System.currentTimeMillis(),
            ),
        )
        true
    }

    /**
     * Materializes authoritative workspace-security audit events as centered system dividers in
     * General. The stable event id makes repeated pulls and restored backups idempotent.
     */
    suspend fun importWorkspaceChatSecurityEvents(
        localWorkspaceId: Long,
        events: List<ChatBackend.WorkspaceSecurityEvent>,
    ): Int = database.withTransaction {
        var inserted = 0
        events.forEach { event ->
            if (dao.chatMessageBySecurityEventId(localWorkspaceId, event.id) != null) return@forEach
            val createdAt = runCatching { java.time.Instant.parse(event.createdAt).toEpochMilli() }
                .getOrElse { System.currentTimeMillis() }
            dao.insertChatMessage(
                ChatMessageEntity(
                    authorUsername = "system",
                    authorDisplayName = "Security",
                    body = event.dividerText,
                    color = 0xFF7E57FF,
                    createdAt = createdAt,
                    system = true,
                    workspaceId = localWorkspaceId,
                    channelId = null,
                    securityMode = event.securityMode.name,
                    securityEventId = event.id,
                ),
            )
            inserted += 1
        }
        inserted
    }

    /** Marks an optimistic account send as bound to its server row (dedupe on echo). */
    suspend fun bindAccountDirectMessage(localMessageId: Long, clientSyncId: String) {
        val ws = activeWs()
        val existing = dao.remoteBinding(ws, WorkspaceObjectType.ChatMessage.name, localMessageId)
        dao.insertRemoteBinding(
            existing?.copy(
                syncId = clientSyncId,
                remoteId = clientSyncId,
                remoteVersion = 1,
                updatedAt = System.currentTimeMillis(),
            ) ?: RemoteObjectBindingEntity(
                workspaceId = ws,
                objectType = WorkspaceObjectType.ChatMessage.name,
                localId = localMessageId,
                syncId = clientSyncId,
                remoteId = clientSyncId,
                remoteVersion = 1,
                updatedAt = System.currentTimeMillis(),
            ),
        )
    }

    suspend fun bindSecureChatMessage(
        localWorkspaceId: Long,
        localMessageId: Long,
        clientSyncId: String,
        relayId: String = clientSyncId,
    ) {
        val existing = dao.remoteBinding(
            localWorkspaceId,
            WorkspaceObjectType.ChatMessage.name,
            localMessageId,
        )
        dao.insertRemoteBinding(
            existing?.copy(
                syncId = clientSyncId,
                remoteId = relayId,
                remoteVersion = 1,
                updatedAt = System.currentTimeMillis(),
            ) ?: RemoteObjectBindingEntity(
                workspaceId = localWorkspaceId,
                objectType = WorkspaceObjectType.ChatMessage.name,
                localId = localMessageId,
                syncId = clientSyncId,
                remoteId = relayId,
                remoteVersion = 1,
                updatedAt = System.currentTimeMillis(),
            ),
        )
    }

    /** Sets a single message's receipt stage (initial 'Sent' stamp on own sends). */
    suspend fun setChatMessageReceiptStatus(messageId: Long, status: ChatReceiptStatus) {
        dao.setChatMessageReceiptStatus(messageId, status.name)
    }

    /** Forward-only batch upgrade of receipt stages; replayed events are harmless. */
    suspend fun upgradeChatMessageReceipts(messageIds: List<Long>, status: ChatReceiptStatus) {
        if (messageIds.isEmpty()) return
        dao.upgradeChatMessageReceipts(messageIds, status.name)
    }

    /** The remote sync id bound to a local chat message, if the message ever synced. */
    suspend fun chatMessageSyncId(localWorkspaceId: Long, messageId: Long): String? =
        dao.remoteBinding(localWorkspaceId, WorkspaceObjectType.ChatMessage.name, messageId)
            ?.takeIf { it.remoteId != null }
            ?.syncId

    suspend fun chatMessageRemoteBinding(localWorkspaceId: Long, messageId: Long): RemoteObjectBindingEntity? =
        dao.remoteBinding(localWorkspaceId, WorkspaceObjectType.ChatMessage.name, messageId)
            ?.takeIf { it.remoteId != null }

    suspend fun chatMessageRemoteBindingBySyncId(
        localWorkspaceId: Long,
        syncId: String,
    ): RemoteObjectBindingEntity? =
        dao.remoteBindingBySyncId(localWorkspaceId, WorkspaceObjectType.ChatMessage.name, syncId)

    suspend fun chatChannelRemoteBinding(localWorkspaceId: Long, channelId: Long): RemoteObjectBindingEntity? =
        dao.remoteBinding(localWorkspaceId, WorkspaceObjectType.ChatChannel.name, channelId)
            ?.takeIf { it.remoteId != null }

    suspend fun localChatChannelIdBySyncId(localWorkspaceId: Long, syncId: String): Long? =
        dao.chatChannelBySyncId(localWorkspaceId, syncId)?.id

    suspend fun workspaceChannelBySyncId(syncId: String): ChatChannel? =
        dao.chatChannelBySyncIdAnyWorkspace(syncId)?.toDomain()?.takeIf { it.scope == com.norfold.app.domain.ChatScope.Workspace }

    suspend fun chatChannelById(channelId: Long): ChatChannel? = dao.chatChannelById(channelId)?.toDomain()

    suspend fun localChatMessageIdBySyncId(localWorkspaceId: Long, syncId: String): Long? =
        dao.remoteBindingBySyncId(localWorkspaceId, WorkspaceObjectType.ChatMessage.name, syncId)?.localId

    suspend fun chatMessageBySyncIdAnyWorkspace(syncId: String): ChatMessageItem? =
        dao.bindingBySyncIdAnyWorkspace(WorkspaceObjectType.ChatMessage.name, syncId)
            ?.let { dao.chatMessageById(it.localId) }
            ?.toDomain()

    /**
     * Resolves local message ids for a set of remote sync ids — the bridge from a
     * receipt-summary poll (keyed by sync id) back to local receipt columns.
     */
    suspend fun chatMessageIdsBySyncIds(localWorkspaceId: Long, syncIds: List<String>): Map<String, Long> =
        syncIds.mapNotNull { syncId ->
            dao.remoteBindingBySyncId(localWorkspaceId, WorkspaceObjectType.ChatMessage.name, syncId)
                ?.let { syncId to it.localId }
        }.toMap()

    /**
     * Sync ids of own recently-sent messages that have a remote binding — the set the
     * receipt poll asks the server about.
     */
    suspend fun recentOwnChatSyncIds(
        localWorkspaceId: Long,
        username: String,
        limit: Int = 60,
    ): List<Pair<String, Long>> =
        dao.chatMessages(localWorkspaceId)
            .filter { !it.system && it.authorUsername.equals(username, ignoreCase = true) && it.receiptStatus != ChatReceiptStatus.Viewed.name }
            .sortedByDescending { it.createdAt }
            .take(limit)
            .mapNotNull { message ->
                dao.remoteBinding(localWorkspaceId, WorkspaceObjectType.ChatMessage.name, message.id)
                    ?.takeIf { it.remoteId != null }
                    ?.let { it.syncId to message.id }
            }

    /**
     * "Delete for me": removes the local row only. The remote binding is kept on purpose —
     * import dedup is binding-based, so keeping it stops the catch-up fetch from resurrecting
     * the message on this device. Other devices are unaffected.
     */
    suspend fun deleteChatMessageForMe(messageId: Long): DeletedChatMessageSnapshot? =
        database.withTransaction {
            val message = dao.chatMessageById(messageId) ?: return@withTransaction null
            val binding = dao.remoteBinding(
                message.workspaceId,
                WorkspaceObjectType.ChatMessage.name,
                messageId,
            )
            dao.deleteChatMessage(messageId)
            DeletedChatMessageSnapshot(message, binding)
        }

    suspend fun restoreChatMessageForUndo(snapshot: DeletedChatMessageSnapshot) {
        dao.insertChatMessage(snapshot.message)
        snapshot.binding?.let(dao::insertRemoteBinding)
    }

    suspend fun recordSecureChatUserHidden(entity: SecureChatUserHiddenEntity) {
        dao.upsertSecureChatUserHidden(entity)
    }

    suspend fun removeSecureChatUserHidden(accountOwnerId: String, messageSyncId: String) {
        dao.deleteSecureChatUserHidden(accountOwnerId, messageSyncId)
    }

    suspend fun hasSecureChatUserHidden(accountOwnerId: String, messageSyncId: String): Boolean =
        dao.secureChatUserHidden(accountOwnerId, messageSyncId) != null

    suspend fun mergeSecureChatUserHidden(
        accountOwnerId: String,
        rows: List<SecureChatUserHiddenEntity>,
    ) = database.withTransaction {
        rows.forEach { row ->
            if (row.accountOwnerId != accountOwnerId) return@forEach
            dao.upsertSecureChatUserHidden(row)
            dao.bindingBySyncIdAnyWorkspace(WorkspaceObjectType.ChatMessage.name, row.messageSyncId)
                ?.let { dao.deleteChatMessage(it.localId) }
        }
    }

    /**
     * Removes the local copy of a message that was unsent elsewhere (remote tombstone arrived).
     * Binding stays for the same dedup reason; the server row is tombstoned and never re-fetched.
     * Returns the removed local id, or null if the sync id was never imported here.
     */
    suspend fun removeChatMessageBySyncId(localWorkspaceId: Long, syncId: String): Long? {
        val binding = dao.remoteBindingBySyncId(localWorkspaceId, WorkspaceObjectType.ChatMessage.name, syncId)
            ?: return null
        dao.deleteChatMessage(binding.localId)
        return binding.localId
    }

    suspend fun applySecureChatMutation(
        localWorkspaceId: Long,
        targetSyncId: String,
        type: String,
        body: String,
        editedAt: Long?,
        reactionsJson: String?,
        pinned: Boolean?,
    ): Boolean {
        val localId = dao.remoteBindingBySyncId(
            localWorkspaceId,
            WorkspaceObjectType.ChatMessage.name,
            targetSyncId,
        )?.localId ?: return false
        when (type) {
            "edit" -> dao.editChatMessage(localId, body, editedAt ?: System.currentTimeMillis())
            "reactions" -> dao.setChatMessageReactions(localId, reactionsJson ?: "{}")
            "pin" -> dao.setChatMessagePinned(localId, pinned == true)
            "delete_everyone" -> dao.deleteChatMessage(localId)
            else -> return false
        }
        return true
    }

    suspend fun enqueueSecureChatOperation(operation: SecureChatOutboxEntity) {
        dao.upsertSecureChatOutbox(operation)
    }

    suspend fun pendingSecureChatOperations(limit: Int = 100): List<SecureChatOutboxEntity> =
        dao.pendingSecureChatOutbox(System.currentTimeMillis(), limit.coerceIn(1, 250))

    suspend fun completeSecureChatOperation(id: String) {
        dao.deleteSecureChatOutbox(id)
    }

    suspend fun failSecureChatOperation(operation: SecureChatOutboxEntity, failure: Throwable) {
        val exponent = operation.attemptCount.coerceIn(0, 8)
        val delayMs = (2_000L shl exponent).coerceAtMost(5 * 60_000L)
        dao.failSecureChatOutbox(
            id = operation.id,
            nextAttemptAt = System.currentTimeMillis() + delayMs,
            lastError = failure.message.orEmpty().take(240),
        )
    }

    suspend fun wakeSecureWorkspaceChatOperations(remoteWorkspaceId: String) {
        dao.wakeSecureChatOutbox(remoteWorkspaceId)
    }

    suspend fun recordSecureRelayCheckpoint(
        deviceId: String,
        generation: Int,
        createdAt: String,
        relayId: String,
    ) {
        dao.upsertSecureChatRelayCheckpoint(
            SecureChatRelayCheckpointEntity(
                deviceId = deviceId,
                cryptoGeneration = generation,
                lastRelayCreatedAt = createdAt,
                lastRelayId = relayId,
                updatedAt = System.currentTimeMillis(),
            ),
        )
    }

    suspend fun recordSecureChatTombstone(
        messageSyncId: String,
        conversationId: String,
        reason: String,
        destructiveAt: Long = System.currentTimeMillis(),
    ) {
        dao.upsertSecureChatTombstone(
            SecureChatTombstoneEntity(messageSyncId, conversationId, destructiveAt, reason),
        )
    }

    suspend fun hasSecureChatTombstone(messageSyncId: String): Boolean =
        dao.secureChatTombstone(messageSyncId) != null

    suspend fun mergeSecureChatTombstones(rows: List<SecureChatTombstoneEntity>) =
        database.withTransaction {
            rows.forEach { row ->
                dao.upsertSecureChatTombstone(row)
                dao.bindingBySyncIdAnyWorkspace(WorkspaceObjectType.ChatMessage.name, row.messageSyncId)
                    ?.let { dao.deleteChatMessage(it.localId) }
            }
        }

    /**
     * Removes locally expired content and records the same anti-resurrection marker used by
     * remote expiry. The server remains authoritative for other devices; this makes the active
     * device deterministic even while offline.
     */
    suspend fun purgeExpiredChatMessages(now: Long = System.currentTimeMillis()): Int =
        database.withTransaction {
            var removed = 0
            dao.expiredChatMessages(now).forEach { message ->
                val binding = dao.remoteBinding(
                    message.workspaceId,
                    WorkspaceObjectType.ChatMessage.name,
                    message.id,
                )
                val syncId = binding?.syncId
                if (syncId != null) {
                    val conversationId = message.channelId
                        ?.let { dao.chatChannelById(it) }
                        ?.let { it.accountConversationId ?: it.syncId }
                        ?: remoteWorkspaceId(message.workspaceId)
                        ?: "workspace:${message.workspaceId}"
                    dao.upsertSecureChatTombstone(
                        SecureChatTombstoneEntity(syncId, conversationId, message.expiresAt ?: now, "expired"),
                    )
                }
                dao.deleteChatMessage(message.id)
                removed += 1
            }
            removed
        }

    /** Deletes a channel and every message scoped to it. General (null channel) is never deletable. */
    suspend fun deleteChatChannel(channelId: Long) {
        dao.deleteChatMessagesForChannel(channelId)
        dao.deleteChatChannel(channelId)
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
        val objectId = upsertWorkspaceObject(WorkspaceObjectType.Goal, id, title, description, status.name, "goal", 0xFF20B26B)
        recordActivity(WorkspaceActivityType.Created, owner.ifBlank { "You" }, "Created goal", title, objectId)
        return id
    }

    suspend fun updateGoal(goal: GoalItem) {
        val status = if (goal.target > 0 && goal.progress >= goal.target) GoalStatus.Achieved else goal.status
        dao.updateGoal(goal.id, goal.title, goal.description, goal.owner, goal.target, goal.progress, goal.unit, goal.dueAt, status.name, System.currentTimeMillis())
        upsertWorkspaceObject(WorkspaceObjectType.Goal, goal.id, goal.title, goal.description, status.name, "goal", 0xFF20B26B, archived = status == GoalStatus.Achieved)
        recordActivity(WorkspaceActivityType.Updated, goal.owner.ifBlank { "You" }, "Updated goal", goal.title, objectType = WorkspaceObjectType.Goal, sourceId = goal.id)
    }

    suspend fun deleteGoal(goal: GoalItem) {
        database.withTransaction {
            dao.workspaceObjectIdForSource(goal.workspaceId, WorkspaceObjectType.Goal.name, goal.id)?.let { objectId ->
                deleteWorkspaceObjectGraph(objectId)
            }
            dao.deleteGoal(goal.id)
        }
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
        val initialDocument = MarkdownBlockCodec.import(description)
        val summary = initialDocument.plainText()
        val entity = CalendarEventEntity(
                workspaceId = activeWs(),
                syncId = UUID.randomUUID().toString(),
                title = title.trim().ifBlank { "Untitled event" },
                description = summary,
                startAt = startAt,
                endAt = endAt,
                allDay = allDay,
                color = color,
                source = source.name,
                externalId = externalId,
                createdAt = now,
                updatedAt = now,
            )
        val id = database.withTransaction {
            val eventId = dao.insertCalendarEvent(entity)
            persistDocument(
                DocumentOwner.calendarEvent(eventId),
                initialDocument,
                initialDocument.blocks.mapTo(linkedSetOf()) { it.id },
                null,
                null,
                null,
                now,
            )
            eventId
        }
        val objectId = upsertWorkspaceObject(WorkspaceObjectType.CalendarEvent, id, title, summary, source.name, "calendar", color)
        recordActivity(WorkspaceActivityType.Created, "You", "Created calendar event", title, objectId)
        return id
    }

    suspend fun updateCalendarEvent(event: CalendarEventItem) {
        require(event.endAt >= event.startAt) { "Event end must be after its start." }
        val current = documentByOwner(DocumentOwner.calendarEvent(event.id))
        val document = if (current == null || event.description != current.document.plainText()) {
            MarkdownBlockCodec.import(event.description)
        } else {
            current.document
        }
        val now = System.currentTimeMillis()
        val summary = document.plainText()
        database.withTransaction {
            persistDocument(DocumentOwner.calendarEvent(event.id), document, document.blocks.mapTo(linkedSetOf()) { it.id }, null, null, null, now)
            dao.updateCalendarEvent(event.id, event.title, summary, event.startAt, event.endAt, event.allDay, event.color, now)
        }
        upsertWorkspaceObject(WorkspaceObjectType.CalendarEvent, event.id, event.title, summary, event.source.name, "calendar", event.color)
    }

    suspend fun ensureCalendarEventDocument(event: CalendarEventItem): OwnedDocument {
        documentByOwner(DocumentOwner.calendarEvent(event.id))?.let { return it }
        val initial = MarkdownBlockCodec.import(event.description)
        return saveDocument(DocumentOwner.calendarEvent(event.id), initial)
    }

    suspend fun updateCalendarEventDocument(
        event: CalendarEventItem,
        title: String,
        document: BlockDocument,
        dirtyBlockIds: Set<String>,
    ) {
        val now = System.currentTimeMillis()
        val normalized = database.withTransaction {
            val persisted = persistDocument(DocumentOwner.calendarEvent(event.id), document, dirtyBlockIds, null, null, null, now)
            dao.updateCalendarEvent(
                event.id,
                title.ifBlank { "Untitled event" },
                persisted.document.plainText(),
                event.startAt,
                event.endAt,
                event.allDay,
                event.color,
                now,
            )
            persisted.document
        }
        val summary = normalized.plainText()
        val normalizedTitle = title.ifBlank { "Untitled event" }
        val objectId = upsertWorkspaceObject(WorkspaceObjectType.CalendarEvent, event.id, normalizedTitle, summary, event.source.name, "calendar", event.color)
        recordActivity(WorkspaceActivityType.Updated, "You", "Updated event doc", normalizedTitle, objectType = WorkspaceObjectType.CalendarEvent, sourceId = event.id, objectId = objectId)
        recordHistory(WorkspaceHistoryType.Updated, objectId, "You", "Updated event doc", beforeValue = event.description.take(500), afterValue = summary.take(500))
    }

    suspend fun deleteCalendarEvent(event: CalendarEventItem) {
        database.withTransaction {
            dao.workspaceObjectIdForSource(event.workspaceId, WorkspaceObjectType.CalendarEvent.name, event.id)?.let { objectId ->
                deleteWorkspaceObjectGraph(objectId)
            }
            dao.deleteDocumentForOwner(DocumentOwnerType.CalendarEvent.storageValue, event.id)
            dao.deleteCalendarEvent(event.id)
        }
        recordActivity(WorkspaceActivityType.Updated, "You", "Deleted calendar event", event.title)
    }

    suspend fun setPinned(note: Note) = dao.setPinned(note.id, !note.pinned, System.currentTimeMillis())
    suspend fun setStarred(note: Note) = dao.setStarred(note.id, !note.starred, System.currentTimeMillis())
    suspend fun setArchived(note: Note, value: Boolean = !note.archived) = dao.setArchived(note.id, value, System.currentTimeMillis())
    suspend fun setLocked(note: Note) = dao.setLocked(note.id, !note.locked, System.currentTimeMillis())
    suspend fun setOverlapMode(note: Note, mode: DocOverlapMode) = updateNoteDocumentLayout(note, mode, note.freeformLayout, note.canvasSpec)
    suspend fun setFreeformLayout(note: Note, layout: Map<String, FreeformPlacement>) =
        updateNoteDocumentLayout(note, note.overlapMode, layout, note.canvasSpec)
    suspend fun setCanvasSpec(note: Note, canvasSpec: DocCanvasSpec) =
        updateNoteDocumentLayout(note, note.overlapMode, note.freeformLayout, canvasSpec)
    suspend fun setCanvasLayout(
        note: Note,
        layout: Map<String, FreeformPlacement>,
        canvasSpec: DocCanvasSpec,
    ) = updateNoteDocumentLayout(note, note.overlapMode, layout, canvasSpec)

    private suspend fun updateNoteDocumentLayout(
        note: Note,
        mode: DocOverlapMode,
        layout: Map<String, FreeformPlacement>,
        canvasSpec: DocCanvasSpec,
    ) =
        updateOwnedDocumentLayout(DocumentOwner.note(note.id), note.document, mode, layout, canvasSpec)

    suspend fun updateOwnedDocumentLayout(
        owner: DocumentOwner,
        document: BlockDocument,
        mode: DocOverlapMode,
        layout: Map<String, FreeformPlacement>,
        canvasSpec: DocCanvasSpec,
    ) = database.withTransaction {
        val now = System.currentTimeMillis()
        persistDocument(owner, document, emptySet(), mode, layout, canvasSpec, now)
        when (owner.type) {
            DocumentOwnerType.Note -> dao.touchNote(owner.id, now)
            DocumentOwnerType.Task -> dao.touchTask(owner.id, now)
            DocumentOwnerType.CalendarEvent -> dao.touchCalendarEvent(owner.id, now)
        }
    }

    suspend fun deleteNote(note: Note) = database.withTransaction {
        dao.noteById(note.id)?.let { row ->
            dao.workspaceObjectIdForSource(row.note.workspaceId, WorkspaceObjectType.Note.name, note.id)?.let { objectId ->
                deleteWorkspaceObjectGraph(objectId)
            }
        }
        dao.deleteDocumentForOwner(DocumentOwnerType.Note.storageValue, note.id)
        dao.deleteNote(note.id)
    }

    suspend fun updateSettings(settings: AppSettings) {
        val entity = AppSettingsEntity(
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
            settingsDeviceId = settings.settingsDeviceId,
            settingsDeviceName = settings.settingsDeviceName,
            settingsDeviceType = settings.settingsDeviceType,
            settingsScreenClass = settings.settingsScreenClass,
            settingsSchemaVersion = settings.settingsSchemaVersion,
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
            fontScale = settings.fontScale,
            uiScale = settings.uiScale,
            appLabelMode = settings.appLabelMode.name,
            appIconFamily = settings.appIconFamily.name,
            editorToolbarScale = settings.editorToolbarScale,
            editorIconFamily = settings.editorIconFamily.name,
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
            contextualMenuStyle = settings.contextualMenuStyle.name,
            contextualMenuColor = settings.contextualMenuColor.name,
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
            taskSwipeStartAction = settings.taskSwipeStartAction.name,
            taskSwipeEndAction = settings.taskSwipeEndAction.name,
            noteRenderEngine = settings.noteRenderEngine.name,
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
        )
        dao.upsertSettings(entity)
        if (settings.settingsDeviceId.isNotBlank()) {
            dao.upsertDeviceSettingsProfile(settings.toDeviceSettingsProfile().toEntity())
        }
    }

    suspend fun previewDeviceSettingsProfile(deviceId: String): DeviceSettingsImportPreview? {
        val source = dao.deviceSettingsProfile(deviceId)?.toDomain() ?: return null
        val current = dao.settings()?.toDomain() ?: defaultSettings
        return DeviceSettingsImportPreview(source, current.settingsDeviceId, source.changesFrom(current))
    }

    suspend fun applyDeviceSettingsProfile(
        deviceId: String,
        categories: Set<SettingsProfileCategory>,
    ): Boolean {
        if (categories.isEmpty()) return false
        val source = dao.deviceSettingsProfile(deviceId)?.toDomain() ?: return false
        val current = dao.settings()?.toDomain() ?: defaultSettings
        updateSettings(source.applyTo(current, categories))
        return true
    }

    suspend fun resetDeviceSettings(categories: Set<SettingsProfileCategory>): Boolean {
        if (categories.isEmpty()) return false
        val current = dao.settings()?.toDomain() ?: return false
        val defaults = defaultSettings.toDeviceSettingsProfile(updatedAt = 0L)
        updateSettings(defaults.applyTo(current, categories))
        return true
    }

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
        deviceSettingsProfiles = dao.allDeviceSettingsProfiles().map { it.toDomain() },
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
        ownedDocuments = dao.allDocuments()
            .filterNot { it.ownerType == DocumentOwnerType.Note.storageValue }
            .mapNotNull { document -> dao.documentByOwner(document.ownerType, document.ownerId)?.toDomain() },
        goals = dao.allGoals().map { it.toDomain() },
        calendarEvents = dao.allCalendarEvents().map { it.toDomain() },
        // Account DMs/groups live exclusively in the separately encrypted private Chat archive.
        // Putting them in a workspace snapshot crosses account and workspace privacy domains.
        chatMessages = dao.allWorkspaceChatMessages().map { it.toDomain() },
        chatChannels = dao.allWorkspaceChatChannels().map { it.toDomain() },
        workspaceObjects = dao.allWorkspaceObjects().map { it.toDomain() },
        workspaceObjectLinks = dao.allWorkspaceObjectLinks().map { it.toDomain() },
        workspaceActivities = dao.allWorkspaceActivities().map { it.toDomain() },
        workspaceObjectHistory = dao.allWorkspaceObjectHistory().map { it.toDomain() },
        workspaceComments = dao.allWorkspaceComments().map { it.toDomain() },
        workspaceFiles = dao.allWorkspaceFiles().map { it.toDomain() },
    )

    suspend fun restore(snapshot: BackupSnapshot) {
        database.withTransaction {
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
            dao.clearChatMessages()
            dao.clearChatChannels()
            dao.clearTaskChecklistItems()
            dao.clearTaskPropertyValues()
            dao.clearTaskPropertyDefinitions()
            dao.clearTasks()
            dao.clearTaskColumns()
            dao.clearTaskBoards()
            dao.clearDocuments()
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
                        permRename = it.permRename,
                        permChangeIcon = it.permChangeIcon,
                        permInviteMembers = it.permInviteMembers,
                        permDeleteNotes = it.permDeleteNotes,
                        permEditNotes = it.permEditNotes,
                        permManageTasks = it.permManageTasks,
                    ),
                )
            }
            snapshot.notebooks.forEach {
                dao.insertNotebook(
                    NotebookEntity(
                        id = it.id,
                        name = it.name,
                        parentId = it.parentId,
                        color = it.color,
                        sortOrder = it.sortOrder,
                        workspaceId = it.workspaceId,
                        scope = it.scope,
                        iconId = it.iconId,
                    ),
                )
            }
            snapshot.tags.forEach {
                dao.insertTag(
                    TagEntity(
                        id = it.id,
                        name = it.name,
                        color = it.color,
                        scope = it.scope,
                        normalizedName = normalizeTagName(it.name),
                    ),
                )
            }
            snapshot.notes.forEach { note ->
                dao.insertNote(
                    NoteEntity(
                        id = note.id,
                        title = note.title,
                        searchText = note.document.plainText(),
                        notebookId = note.notebookId,
                        coverUri = note.coverUri,
                        coverMimeType = note.coverMimeType,
                        pinned = note.pinned,
                        starred = note.starred,
                        archived = note.archived,
                        locked = note.locked,
                        createdAt = note.createdAt,
                        updatedAt = note.updatedAt,
                        workspaceId = note.workspaceId,
                    ),
                )
                persistDocument(
                    owner = DocumentOwner.note(note.id),
                    document = note.document,
                    dirtyBlockIds = note.document.blocks.mapTo(linkedSetOf()) { it.id },
                    layoutMode = note.overlapMode,
                    freeformLayout = note.freeformLayout,
                    canvasSpec = note.canvasSpec,
                    updatedAt = note.updatedAt,
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
                        workspaceId = it.workspaceId,
                    ),
                )
            }
            snapshot.ownedDocuments.forEach { owned ->
                persistDocument(
                    owner = owned.owner,
                    document = owned.document,
                    dirtyBlockIds = owned.document.blocks.mapTo(linkedSetOf()) { it.id },
                    layoutMode = owned.layoutMode,
                    freeformLayout = owned.freeformLayout,
                    canvasSpec = owned.canvasSpec,
                    updatedAt = owned.updatedAt,
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
            val workspaceChannels = snapshot.chatChannels.filter { it.accountConversationId == null }
            val workspaceChannelIds = workspaceChannels.mapTo(hashSetOf()) { it.id }
            workspaceChannels.forEach {
                dao.insertChatChannel(
                    ChatChannelEntity(
                        id = it.id,
                        syncId = it.syncId,
                        kind = it.kind.name,
                        name = it.name,
                        counterpartUsername = it.counterpartUsername,
                        icon = it.icon,
                        createdAt = it.createdAt,
                        workspaceId = it.workspaceId,
                        pinned = it.pinned,
                        accountConversationId = it.accountConversationId,
                        coverUri = it.coverUri,
                        description = it.description,
                        rules = it.rules,
                        rulesReminderDays = it.rulesReminderDays,
                        visibility = it.visibility.name,
                        securityMode = it.securityMode.name,
                        securityEpochId = it.securityEpochId,
                        acknowledgedSecurityEpochId = it.acknowledgedSecurityEpochId,
                        defaultLifespanSeconds = it.defaultLifespanSeconds,
                    ),
                )
            }
            snapshot.chatMessages
                .filter { it.channelId == null || it.channelId in workspaceChannelIds }
                .forEach {
                dao.insertChatMessage(
                    ChatMessageEntity(
                        id = it.id,
                        authorUsername = it.authorUsername,
                        authorDisplayName = it.authorDisplayName,
                        body = it.body,
                        color = it.color,
                        createdAt = it.createdAt,
                        system = it.system,
                        attachmentName = it.attachmentName,
                        attachmentMimeType = it.attachmentMimeType,
                        attachmentUri = it.attachmentUri,
                        attachmentSizeBytes = it.attachmentSizeBytes,
                        workspaceId = it.workspaceId,
                        channelId = it.channelId,
                        receiptStatus = it.receiptStatus?.name,
                        replyPreview = it.replyPreview,
                        editedAt = it.editedAt,
                        reactionsJson = it.reactionsJson,
                        pinned = it.pinned,
                        starred = it.starred,
                        securityMode = it.securityMode?.name,
                        securityEpochId = it.securityEpochId,
                        originPayloadJson = it.originPayloadJson,
                        securityEventId = it.securityEventId,
                        expiresAt = it.expiresAt,
                    ),
                )
            }
            snapshot.workspaceObjects.forEach {
                dao.insertWorkspaceObject(WorkspaceObjectEntity(it.id, it.objectType.name, it.sourceId, it.title, it.summary, it.tags, it.icon, it.color, it.pinned, it.archived, it.createdAt, it.updatedAt, it.workspaceId))
            }
            snapshot.workspaceObjectLinks.forEach {
                dao.insertWorkspaceObjectLink(WorkspaceObjectLinkEntity(it.id, it.fromObjectId, it.toObjectId, it.linkType.name, it.label, it.createdAt, it.workspaceId))
            }
            snapshot.workspaceActivities.forEach {
                dao.insertWorkspaceActivity(WorkspaceActivityEntity(it.id, it.objectId, it.activityType.name, it.actor, it.title, it.detail, it.createdAt, it.workspaceId))
            }
            snapshot.workspaceObjectHistory.forEach {
                dao.insertWorkspaceObjectHistory(
                    WorkspaceObjectHistoryEntity(
                        id = it.id,
                        objectId = it.objectId,
                        historyType = it.historyType.name,
                        actor = it.actor,
                        summary = it.summary,
                        beforeValue = it.beforeValue,
                        afterValue = it.afterValue,
                        createdAt = it.createdAt,
                        blockId = it.blockId,
                        workspaceId = it.workspaceId,
                    ),
                )
            }
            snapshot.workspaceComments.forEach {
                dao.insertWorkspaceComment(WorkspaceCommentEntity(it.id, it.objectId, it.authorUsername, it.authorDisplayName, it.body, it.resolved, it.createdAt, it.updatedAt, it.workspaceId))
            }
            snapshot.workspaceFiles.forEach {
                dao.insertWorkspaceFile(WorkspaceFileEntity(it.id, it.objectId, it.displayName, it.mimeType, it.uri, it.sizeBytes, it.createdAt, it.updatedAt, it.workspaceId))
            }
            snapshot.deviceSettingsProfiles.forEach { imported ->
                val existing = dao.deviceSettingsProfile(imported.deviceId)?.toDomain()
                if (existing == null || imported.updatedAt > existing.updatedAt) {
                    dao.upsertDeviceSettingsProfile(imported.toEntity())
                }
            }
            val currentSettings = dao.settings()?.toDomain() ?: defaultSettings
            // The pre-restore active workspace may not exist in the snapshot; a dangling id
            // would filter every scoped flow to empty until the user switches manually.
            val workspacesAfterRestore = dao.allWorkspaces()
            val resolvedActiveWorkspaceId = currentSettings.activeWorkspaceId
                .takeIf { id -> workspacesAfterRestore.any { it.id == id } }
                ?: workspacesAfterRestore.firstOrNull()?.id ?: 1L
            updateSettings(
                currentSettings.copy(
                    activeWorkspaceId = resolvedActiveWorkspaceId,
                    taskViewMode = snapshot.taskWorkspacePrefs.viewMode,
                    taskSelectedBoardId = snapshot.taskWorkspacePrefs.selectedBoardId,
                    taskSortMode = snapshot.taskWorkspacePrefs.sortMode,
                    taskCompactLayout = snapshot.taskWorkspacePrefs.compactLayout,
                    taskKanbanEngine = snapshot.taskWorkspacePrefs.kanbanEngine,
                ),
            )
            rebuildWorkspaceIndex()
        }
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
        val noteIds = hashSetOf<Long>()
        val taskIds = hashSetOf<Long>()
        val goalIds = hashSetOf<Long>()
        val eventIds = hashSetOf<Long>()
        val chatIds = hashSetOf<Long>()
        dao.allNotesSnapshot().filter { it.note.workspaceId == ws }.forEach { row ->
            val note = row.toDomain()
            noteIds += note.id
            upsertWorkspaceObject(WorkspaceObjectType.Note, note.id, note.title, note.document.plainText().take(160), note.tags.joinToString(",") { it.name }, "note", 0xFF9D6CFF, note.pinned, note.archived)
        }
        dao.allTasks().filter { it.workspaceId == ws }.forEach {
            taskIds += it.id
            upsertWorkspaceObject(WorkspaceObjectType.Task, it.id, it.title, it.description, it.labels, "task", 0xFF56CC98, archived = it.status == TaskStatus.Done.name)
        }
        dao.allGoals().filter { it.workspaceId == ws }.forEach {
            goalIds += it.id
            upsertWorkspaceObject(WorkspaceObjectType.Goal, it.id, it.title, it.description, it.status, "goal", 0xFF20B26B, archived = it.status == GoalStatus.Achieved.name)
        }
        dao.allCalendarEvents().filter { it.workspaceId == ws }.forEach {
            eventIds += it.id
            upsertWorkspaceObject(WorkspaceObjectType.CalendarEvent, it.id, it.title, it.description, it.source, "calendar", it.color)
        }
        // Only General (null channel) is mirrored into the workspace graph; channel chatter stays local.
        dao.allChatMessages().filter { it.workspaceId == ws && it.channelId == null }.forEach {
            chatIds += it.id
            upsertWorkspaceObject(WorkspaceObjectType.ChatMessage, it.id, it.authorDisplayName, it.body.take(160), "", "chat", it.color)
        }
        // Clean up duplicated attachment file objects before backfilling: this loop used to mint a
        // fresh File object + link per attachment on every rebuild (i.e. every app start), because
        // null-sourceId upserts can never find their previous row. Candidates are File objects no
        // workspace_files row references whose only inbound edge is a note Attachment link (or
        // nothing at all); one survivor is kept per (note, label) so the backfill below stays
        // idempotent. Chat/upload file objects are workspace_files-referenced and task file
        // objects hang off Task links, so both are excluded by construction.
        val objectsById = dao.workspaceObjectsSnapshot(ws).associateBy { it.id }
        val referencedFileObjectIds = dao.workspaceFileObjectIds(ws).toHashSet()
        val linksSnapshot = dao.workspaceObjectLinksSnapshot(ws)
        val inboundLinks = linksSnapshot.groupBy { it.toObjectId }
        val duplicateCandidates = objectsById.values.filter {
            it.objectType == WorkspaceObjectType.File.name && it.sourceId == null && it.id !in referencedFileObjectIds
        }
        val (orphanFileObjects, linkedFileObjects) = duplicateCandidates.partition { inboundLinks[it.id].isNullOrEmpty() }
        orphanFileObjects.forEach { deleteWorkspaceObjectGraph(it.id) }
        linkedFileObjects
            .mapNotNull { obj ->
                val link = inboundLinks.getValue(obj.id).singleOrNull() ?: return@mapNotNull null
                val fromNote = objectsById[link.fromObjectId]?.objectType == WorkspaceObjectType.Note.name
                if (fromNote && link.linkType == WorkspaceLinkType.Attachment.name) Triple(link.fromObjectId, link.label, obj) else null
            }
            .groupBy { (fromObjectId, label, _) -> fromObjectId to label }
            .forEach { (key, group) ->
                val (fromObjectId, label) = key
                val hasReferencedTwin = linksSnapshot.any {
                    it.fromObjectId == fromObjectId && it.label == label &&
                        it.linkType == WorkspaceLinkType.Attachment.name && it.toObjectId in referencedFileObjectIds
                }
                val survivorId = if (hasReferencedTwin) null else group.minOf { (_, _, obj) -> obj.id }
                group.forEach { (_, _, obj) -> if (obj.id != survivorId) deleteWorkspaceObjectGraph(obj.id) }
            }
        // Backfill: adopt the already-linked file object when one exists; only attachments with no
        // File object at all (legacy data, wiped index) get a new one. Scoped to this workspace's
        // notes — the old unfiltered pass also indexed other workspaces' attachments in here.
        dao.allAttachments().filter { it.noteId in noteIds }.forEach { attachment ->
            val noteObjectId = objectIdFor(WorkspaceObjectType.Note, attachment.noteId) ?: return@forEach
            val existingFileObjectId = dao.workspaceObjectLinkTarget(ws, noteObjectId, WorkspaceLinkType.Attachment.name, attachment.displayName)
            if (existingFileObjectId == null) {
                val fileObjectId = upsertWorkspaceObject(WorkspaceObjectType.File, null, attachment.displayName, attachment.mimeType, "", "file", 0xFF4AADFF)
                insertObjectLinkIfMissing(noteObjectId, fileObjectId, WorkspaceLinkType.Attachment, attachment.displayName)
            }
        }
        // Reconciliation: prune ghost objects whose source row no longer exists (deletes made
        // before delete paths cleaned up workspace_objects left these behind permanently).
        // Objects with a null sourceId are kept: embed/file placeholders are created that way.
        val liveSourceIds = mapOf(
            WorkspaceObjectType.Note.name to noteIds,
            WorkspaceObjectType.Task.name to taskIds,
            WorkspaceObjectType.Goal.name to goalIds,
            WorkspaceObjectType.CalendarEvent.name to eventIds,
            WorkspaceObjectType.ChatMessage.name to chatIds,
        )
        dao.workspaceObjectsSnapshot(ws)
            .filter { obj ->
                val live = liveSourceIds[obj.objectType] ?: return@filter false
                obj.sourceId != null && obj.sourceId !in live
            }
            .forEach { ghost -> deleteWorkspaceObjectGraph(ghost.id) }
        if (dao.countWorkspaceActivities(ws) == 0) {
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
    ): Long = workspaceObjectUpsertMutex.withLock {
        val now = System.currentTimeMillis()
        val workspaceId = activeWs()
        val existing = sourceId?.let { dao.workspaceObjectBySource(workspaceId, type.name, it) }
        dao.insertWorkspaceObject(
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
                workspaceId = workspaceId,
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
        val resolvedObjectId = objectId ?: if (objectType != null && sourceId != null) {
            dao.workspaceObjectIdForSource(activeWs(), objectType.name, sourceId)
        } else {
            null
        }
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
        dao.workspaceObjectIdForSource(activeWs(), type.name, sourceId)

    /**
     * Removes a workspace object plus everything that references it: links (either direction),
     * activities, history, and comments. File rows keep their content but drop the dangling
     * objectId so real uploads are never orphan-deleted alongside the object they were linked to.
     */
    private suspend fun deleteWorkspaceObjectGraph(objectId: Long) {
        dao.deleteWorkspaceObjectLinksForObject(objectId)
        dao.deleteWorkspaceActivitiesForObject(objectId)
        dao.deleteWorkspaceObjectHistoryForObject(objectId)
        dao.deleteWorkspaceCommentsForObject(objectId)
        dao.detachWorkspaceFilesFromObject(objectId)
        dao.deleteWorkspaceObjectById(objectId)
    }

    private suspend fun insertObjectLinkIfMissing(fromObjectId: Long, toObjectId: Long, type: WorkspaceLinkType, label: String = ""): Long {
        if (fromObjectId == toObjectId) return 0
        val ws = activeWs()
        dao.workspaceObjectLinkId(ws, fromObjectId, toObjectId, type.name, label)?.let { return it }
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
        blockId: String? = null,
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
                blockId = blockId,
                workspaceId = activeWs(),
            ),
        )
    }

    private suspend fun setTags(noteId: Long, names: List<String>) {
        dao.clearTagsForNote(noteId)
        names.map { it.trim().removePrefix("#") }.filter { it.isNotBlank() }.distinct().forEach { name ->
            dao.insertNoteTag(NoteTagCrossRef(noteId, getOrCreateTag(name, scope = "notes").id))
        }
    }

    private suspend fun getOrCreateTag(name: String, scope: String): TagEntity {
        val displayName = name.trim().trimStart('#').replace(Regex("\\s+"), " ")
        val normalized = normalizeTagName(displayName)
        require(normalized.isNotBlank()) { "Tag name cannot be blank" }
        return dao.tagByScopeAndNormalizedName(scope, normalized)
            ?: TagEntity(
                id = dao.insertTag(TagEntity(name = displayName, color = 0xFF8B5CF6, scope = scope, normalizedName = normalized)),
                name = displayName,
                color = 0xFF8B5CF6,
                scope = scope,
                normalizedName = normalized,
            )
    }

    private fun normalizeTagName(name: String): String =
        name.trim().trimStart('#').replace(Regex("\\s+"), " ").lowercase()

    companion object {
        const val RemoteDocumentObjectType = "SharedDocument"
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
            themeProfile = ThemeProfile.Norfold,
            accentColor = 0xFF3D7C45,
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
            lastSyncStatus = "Sync not set up",
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
            uiScale = 1f,
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
