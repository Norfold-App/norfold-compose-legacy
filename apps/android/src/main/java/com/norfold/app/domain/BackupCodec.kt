package com.norfold.app.domain

import java.util.Base64

data class BackupSnapshot(
    val workspaces: List<Workspace> = emptyList(),
    val taskWorkspacePrefs: TaskWorkspacePrefs = TaskWorkspacePrefs(),
    val deviceSettingsProfiles: List<DeviceSettingsProfile> = emptyList(),
    val notes: List<Note>,
    val notebooks: List<Notebook>,
    val tags: List<Tag>,
    val attachments: List<Attachment>,
    val embeds: List<NoteEmbedItem> = emptyList(),
    val taskBoards: List<TaskBoardItem> = emptyList(),
    val taskColumns: List<TaskColumnItem> = emptyList(),
    val tasks: List<TaskItem> = emptyList(),
    val taskPropertyDefinitions: List<TaskPropertyDefinition> = emptyList(),
    val taskPropertyValues: List<TaskPropertyValue> = emptyList(),
    val taskChecklistItems: List<TaskChecklistItem> = emptyList(),
    val ownedDocuments: List<OwnedDocument> = emptyList(),
    val goals: List<GoalItem> = emptyList(),
    val calendarEvents: List<CalendarEventItem> = emptyList(),
    val chatMessages: List<ChatMessageItem> = emptyList(),
    val chatChannels: List<ChatChannel> = emptyList(),
    val workspaceObjects: List<WorkspaceObject> = emptyList(),
    val workspaceObjectLinks: List<WorkspaceObjectLink> = emptyList(),
    val workspaceActivities: List<WorkspaceActivity> = emptyList(),
    val workspaceObjectHistory: List<WorkspaceObjectHistory> = emptyList(),
    val workspaceComments: List<WorkspaceComment> = emptyList(),
    val workspaceFiles: List<WorkspaceFileItem> = emptyList(),
)

object BackupCodec {
    // V3 adds structured documents owned by Tasks and Calendar events. NOTE rows keep carrying
    // their exact document payload so the pre-beta V2 shape remains straightforward to inspect.
    const val Header = "NORFOLD-BACKUP-V3"

    fun encode(snapshot: BackupSnapshot): String = buildString {
        appendLine(Header)
        appendLine(
            listOf(
                "TASK_PREFS",
                b64(snapshot.taskWorkspacePrefs.viewMode),
                snapshot.taskWorkspacePrefs.selectedBoardId,
                b64(snapshot.taskWorkspacePrefs.sortMode),
                snapshot.taskWorkspacePrefs.compactLayout,
                b64(snapshot.taskWorkspacePrefs.kanbanEngine),
            ).joinToString("|"),
        )
        snapshot.deviceSettingsProfiles.sortedByDescending { it.updatedAt }.forEach {
            appendLine(
                listOf(
                    "DEVICE_SETTINGS",
                    b64(it.deviceId),
                    b64(it.deviceName),
                    b64(it.deviceType),
                    b64(it.screenClass),
                    it.schemaVersion,
                    it.updatedAt,
                    it.fontScale,
                    it.uiScale,
                    it.appLabelMode.name,
                    it.appIconFamily.name,
                    b64(it.appFont),
                    it.uiDensityCompact,
                    it.editorToolbarScale,
                    it.editorIconFamily.name,
                    it.editorLineWidth.name,
                    it.editorFontFamily.name,
                    it.reduceMotion,
                ).joinToString("|"),
            )
        }
        snapshot.workspaces.sortedBy { it.createdAt }.forEach {
            appendLine(
                listOf(
                    "WORKSPACE",
                    it.id,
                    b64(it.name),
                    b64(it.icon),
                    it.iconKind.name,
                    b64(it.iconUri.orEmpty()),
                    b64(it.backgroundUri.orEmpty()),
                    it.palette.name,
                    it.createdAt,
                    it.permRename,
                    it.permChangeIcon,
                    it.permInviteMembers,
                    it.permDeleteNotes,
                    it.permEditNotes,
                    it.permManageTasks,
                ).joinToString("|"),
            )
        }
        snapshot.notebooks.sortedBy { it.sortOrder }.forEach {
            appendLine(listOf("NOTEBOOK", it.id, b64(it.name), it.parentId ?: "", it.color, it.sortOrder, it.workspaceId, b64(it.scope), b64(it.iconId)).joinToString("|"))
        }
        snapshot.tags.sortedBy { it.name.lowercase() }.forEach {
            appendLine(listOf("TAG", it.id, b64(it.name), it.color, b64(it.scope)).joinToString("|"))
        }
        snapshot.notes.sortedByDescending { it.updatedAt }.forEach {
            appendLine(
                listOf(
                    "NOTE",
                    it.id,
                    b64(it.title),
                    b64(BlockDocumentJson.encodeDocumentPayload(it.document)),
                    it.notebookId ?: "",
                    b64(it.coverUri.orEmpty()),
                    b64(it.coverMimeType.orEmpty()),
                    it.pinned,
                    it.starred,
                    it.archived,
                    it.locked,
                    it.createdAt,
                    it.updatedAt,
                    b64(it.tags.joinToString(",") { tag -> tag.name }),
                    it.overlapMode.name,
                    b64(DocLayoutJson.encode(it.freeformLayout, it.canvasSpec)),
                    it.workspaceId,
                ).joinToString("|"),
            )
        }
        snapshot.attachments.sortedBy { it.id }.forEach {
            appendLine(listOf("ATTACHMENT", it.id, it.noteId, b64(it.displayName), b64(it.mimeType), b64(it.uri), it.sizeBytes).joinToString("|"))
        }
        snapshot.embeds.sortedBy { it.id }.forEach {
            appendLine(listOf("EMBED", it.id, it.noteId, it.type.name, b64(it.title), b64(it.target), b64(it.preview), it.createdAt).joinToString("|"))
        }
        snapshot.taskBoards.sortedBy { it.id }.forEach {
            appendLine(listOf("TASK_BOARD", it.id, b64(it.name), it.workspaceId, it.createdAt, it.updatedAt).joinToString("|"))
        }
        snapshot.taskColumns.sortedWith(compareBy<TaskColumnItem> { it.boardId }.thenBy { it.sortOrder }).forEach {
            appendLine(listOf("TASK_COLUMN", it.id, it.boardId, b64(it.name), it.status?.name.orEmpty(), it.color, it.sortOrder, it.createdAt, it.updatedAt).joinToString("|"))
        }
        snapshot.tasks.sortedWith(compareBy<TaskItem> { it.taskBoardId }.thenBy { it.taskColumnId ?: Long.MAX_VALUE }.thenBy { it.sortOrder }).forEach {
            appendLine(
                listOf(
                    "TASK",
                    it.id,
                    b64(it.title),
                    b64(it.description),
                    b64(it.assignee),
                    it.status.name,
                    it.priority.name,
                    it.dueAt ?: "",
                    it.createdAt,
                    it.updatedAt,
                    b64(it.labels),
                    b64(it.attachmentName.orEmpty()),
                    b64(it.attachmentMimeType.orEmpty()),
                    b64(it.attachmentUri.orEmpty()),
                    it.attachmentSizeBytes ?: "",
                    it.taskBoardId,
                    it.taskColumnId ?: "",
                    it.sortOrder,
                    it.colorArgb ?: "",
                    it.startAt ?: "",
                    it.allDay,
                    it.reminderMinutesBefore ?: "",
                    it.workspaceId,
                ).joinToString("|"),
            )
        }
        snapshot.taskPropertyDefinitions.sortedWith(compareBy<TaskPropertyDefinition> { it.boardId }.thenBy { it.sortOrder }).forEach {
            appendLine(listOf("TASK_PROPERTY", it.id, it.boardId, b64(it.name), it.type.name, it.sortOrder, it.hiddenWhenEmpty, b64(it.optionsJson), it.createdAt, it.updatedAt).joinToString("|"))
        }
        snapshot.taskPropertyValues.sortedWith(compareBy<TaskPropertyValue> { it.taskId }.thenBy { it.propertyId }).forEach {
            appendLine(listOf("TASK_PROPERTY_VALUE", it.id, it.taskId, it.propertyId, b64(it.valueJson), it.updatedAt).joinToString("|"))
        }
        snapshot.taskChecklistItems.sortedWith(compareBy<TaskChecklistItem> { it.taskId }.thenBy { it.propertyId }.thenBy { it.sortOrder }).forEach {
            appendLine(listOf("TASK_CHECKLIST_ITEM", it.id, it.taskId, it.propertyId, b64(it.text), it.checked, it.sortOrder, it.createdAt, it.updatedAt).joinToString("|"))
        }
        snapshot.ownedDocuments
            .filter { it.owner.type != DocumentOwnerType.Note }
            .sortedWith(compareBy<OwnedDocument> { it.owner.type.storageValue }.thenBy { it.owner.id })
            .forEach {
                appendLine(
                    listOf(
                        "OWNED_DOCUMENT",
                        it.owner.type.storageValue,
                        it.owner.id,
                        b64(BlockDocumentJson.encodeDocumentPayload(it.document)),
                        it.layoutMode.name,
                        b64(DocLayoutJson.encode(it.freeformLayout, it.canvasSpec)),
                        it.createdAt,
                        it.updatedAt,
                    ).joinToString("|"),
                )
            }
        snapshot.goals.sortedBy { it.id }.forEach {
            appendLine(listOf("GOAL", it.id, it.workspaceId, b64(it.syncId), b64(it.title), b64(it.description), b64(it.owner), it.target, it.progress, b64(it.unit), it.dueAt ?: "", it.status.name, it.createdAt, it.updatedAt).joinToString("|"))
        }
        snapshot.calendarEvents.sortedBy { it.id }.forEach {
            appendLine(listOf("CALENDAR_EVENT", it.id, it.workspaceId, b64(it.syncId), b64(it.title), b64(it.description), it.startAt, it.endAt, it.allDay, it.color, it.source.name, b64(it.externalId.orEmpty()), it.createdAt, it.updatedAt).joinToString("|"))
        }
        snapshot.chatMessages.sortedBy { it.createdAt }.forEach {
            appendLine(
                listOf(
                    "CHAT",
                    it.id,
                    b64(it.authorUsername),
                    b64(it.authorDisplayName),
                    b64(it.body),
                    it.color,
                    it.createdAt,
                    it.system,
                    b64(it.attachmentName.orEmpty()),
                    b64(it.attachmentMimeType.orEmpty()),
                    b64(it.attachmentUri.orEmpty()),
                    it.attachmentSizeBytes ?: "",
                    it.workspaceId,
                    it.channelId ?: "",
                    b64(it.replyPreview.orEmpty()),
                    it.editedAt ?: "",
                    b64(it.reactionsJson),
                    it.pinned,
                ).joinToString("|"),
            )
        }
        snapshot.chatChannels.sortedBy { it.id }.forEach {
            appendLine(
                listOf(
                    "CHANNEL",
                    it.id,
                    it.syncId,
                    it.kind.name,
                    b64(it.name),
                    b64(it.counterpartUsername),
                    b64(it.icon),
                    it.createdAt,
                    it.workspaceId,
                    it.pinned,
                ).joinToString("|"),
            )
        }
        snapshot.workspaceObjects.sortedBy { it.id }.forEach {
            appendLine(listOf("OBJECT", it.id, it.objectType.name, it.sourceId ?: "", b64(it.title), b64(it.summary), b64(it.tags), b64(it.icon), it.color, it.pinned, it.archived, it.createdAt, it.updatedAt, it.workspaceId).joinToString("|"))
        }
        snapshot.workspaceObjectLinks.sortedBy { it.id }.forEach {
            appendLine(listOf("OBJECT_LINK", it.id, it.fromObjectId, it.toObjectId, it.linkType.name, b64(it.label), it.createdAt, it.workspaceId).joinToString("|"))
        }
        snapshot.workspaceActivities.sortedBy { it.id }.forEach {
            appendLine(listOf("ACTIVITY", it.id, it.objectId ?: "", it.activityType.name, b64(it.actor), b64(it.title), b64(it.detail), it.createdAt, it.workspaceId).joinToString("|"))
        }
        snapshot.workspaceObjectHistory.sortedBy { it.id }.forEach {
            appendLine(listOf("HISTORY", it.id, it.objectId ?: "", it.historyType.name, b64(it.actor), b64(it.summary), b64(it.beforeValue), b64(it.afterValue), it.createdAt, it.workspaceId, b64(it.blockId.orEmpty())).joinToString("|"))
        }
        snapshot.workspaceComments.sortedBy { it.id }.forEach {
            appendLine(listOf("COMMENT", it.id, it.objectId, b64(it.authorUsername), b64(it.authorDisplayName), b64(it.body), it.resolved, it.createdAt, it.updatedAt, it.workspaceId).joinToString("|"))
        }
        snapshot.workspaceFiles.sortedBy { it.id }.forEach {
            appendLine(listOf("FILE", it.id, it.objectId ?: "", b64(it.displayName), b64(it.mimeType), b64(it.uri), it.sizeBytes, it.createdAt, it.updatedAt, it.workspaceId).joinToString("|"))
        }
    }

    fun decode(payload: String): BackupSnapshot {
        val lines = payload.lineSequence().filter { it.isNotBlank() }.toList()
        require(lines.firstOrNull() == Header) { "Unsupported backup format." }
        val workspaces = mutableListOf<Workspace>()
        val notebooks = mutableListOf<Notebook>()
        val tags = mutableListOf<Tag>()
        val notes = mutableListOf<Note>()
        val attachments = mutableListOf<Attachment>()
        val embeds = mutableListOf<NoteEmbedItem>()
        val taskBoards = mutableListOf<TaskBoardItem>()
        val taskColumns = mutableListOf<TaskColumnItem>()
        val tasks = mutableListOf<TaskItem>()
        val taskPropertyDefinitions = mutableListOf<TaskPropertyDefinition>()
        val taskPropertyValues = mutableListOf<TaskPropertyValue>()
        val taskChecklistItems = mutableListOf<TaskChecklistItem>()
        val ownedDocuments = mutableListOf<OwnedDocument>()
        val goals = mutableListOf<GoalItem>()
        val calendarEvents = mutableListOf<CalendarEventItem>()
        val chatMessages = mutableListOf<ChatMessageItem>()
        val chatChannels = mutableListOf<ChatChannel>()
        val workspaceObjects = mutableListOf<WorkspaceObject>()
        val workspaceObjectLinks = mutableListOf<WorkspaceObjectLink>()
        val workspaceActivities = mutableListOf<WorkspaceActivity>()
        val workspaceObjectHistory = mutableListOf<WorkspaceObjectHistory>()
        val workspaceComments = mutableListOf<WorkspaceComment>()
        val workspaceFiles = mutableListOf<WorkspaceFileItem>()
        val deviceSettingsProfiles = mutableListOf<DeviceSettingsProfile>()
        var taskWorkspacePrefs = TaskWorkspacePrefs()
        lines.drop(1).forEach { line ->
            val cells = line.split("|")
            when (cells.firstOrNull()) {
                "TASK_PREFS" -> taskWorkspacePrefs = TaskWorkspacePrefs(
                    viewMode = unb64(cells.getOrElse(1) { b64("Board") }),
                    selectedBoardId = cells.getOrElse(2) { "1" }.toLong(),
                    sortMode = unb64(cells.getOrElse(3) { b64("Manual") }),
                    compactLayout = cells.getOrElse(4) { "true" }.toBoolean(),
                    kanbanEngine = unb64(cells.getOrElse(5) { b64("BoardPointer") }),
                )
                "DEVICE_SETTINGS" -> deviceSettingsProfiles += DeviceSettingsProfile(
                    deviceId = unb64(cells.getOrElse(1) { "" }),
                    deviceName = unb64(cells.getOrElse(2) { "" }),
                    deviceType = unb64(cells.getOrElse(3) { "" }),
                    screenClass = unb64(cells.getOrElse(4) { "" }),
                    schemaVersion = cells.getOrElse(5) { "1" }.toInt(),
                    updatedAt = cells.getOrElse(6) { "0" }.toLong(),
                    fontScale = cells.getOrElse(7) { "1" }.toFloat().coerceIn(0.5f, 2f),
                    uiScale = cells.getOrElse(8) { "1" }.toFloat().coerceIn(0.5f, 2f),
                    appLabelMode = ControlLabelMode.entries.firstOrNull { it.name == cells.getOrElse(9) { "" } } ?: ControlLabelMode.Adaptive,
                    appIconFamily = AppIconFamily.entries.firstOrNull { it.name == cells.getOrElse(10) { "" } } ?: AppIconFamily.MaterialSymbolsRounded,
                    appFont = unb64(cells.getOrElse(11) { "" }).ifBlank { "Inter" },
                    uiDensityCompact = cells.getOrElse(12) { "false" }.toBoolean(),
                    editorToolbarScale = cells.getOrElse(13) { "1" }.toFloat().coerceIn(0.5f, 2f),
                    editorIconFamily = EditorIconFamily.entries.firstOrNull { it.name == cells.getOrElse(14) { "" } } ?: EditorIconFamily.UseApp,
                    editorLineWidth = EditorLineWidth.entries.firstOrNull { it.name == cells.getOrElse(15) { "" } } ?: EditorLineWidth.Comfortable,
                    editorFontFamily = EditorFontFamily.entries.firstOrNull { it.name == cells.getOrElse(16) { "" } } ?: EditorFontFamily.Sans,
                    reduceMotion = cells.getOrElse(17) { "false" }.toBoolean(),
                )
                "WORKSPACE" -> workspaces += Workspace(
                    id = cells[1].toLong(),
                    name = unb64(cells[2]),
                    icon = unb64(cells[3]),
                    iconKind = WorkspaceIconKind.entries.firstOrNull { it.name == cells[4] } ?: WorkspaceIconKind.Text,
                    iconUri = unb64(cells.getOrElse(5) { "" }).takeIf { it.isNotBlank() },
                    backgroundUri = unb64(cells.getOrElse(6) { "" }).takeIf { it.isNotBlank() },
                    palette = ThemeProfile.entries.firstOrNull { it.name == cells.getOrElse(7) { ThemeProfile.Norfold.name } } ?: ThemeProfile.Norfold,
                    createdAt = cells.getOrElse(8) { "0" }.toLong(),
                    permRename = cells.getOrElse(9) { "false" }.toBoolean(),
                    permChangeIcon = cells.getOrElse(10) { "false" }.toBoolean(),
                    permInviteMembers = cells.getOrElse(11) { "false" }.toBoolean(),
                    permDeleteNotes = cells.getOrElse(12) { "false" }.toBoolean(),
                    permEditNotes = cells.getOrElse(13) { "true" }.toBoolean(),
                    permManageTasks = cells.getOrElse(14) { "true" }.toBoolean(),
                )
                "NOTEBOOK" -> notebooks += Notebook(
                    id = cells[1].toLong(),
                    name = unb64(cells[2]),
                    parentId = cells[3].takeIf { it.isNotBlank() }?.toLong(),
                    color = cells[4].toLong(),
                    sortOrder = cells[5].toInt(),
                    workspaceId = cells.getOrElse(6) { "1" }.toLong(),
                    scope = unb64(cells.getOrElse(7) { "" }).ifBlank { "notes" },
                    iconId = unb64(cells.getOrElse(8) { "" }).ifBlank { "app.folder" },
                )
                "TAG" -> tags += Tag(
                    id = cells[1].toLong(),
                    name = unb64(cells[2]),
                    color = cells[3].toLong(),
                    scope = unb64(cells.getOrElse(4) { "" }).ifBlank { "notes" },
                )
                "NOTE" -> notes += Note(
                    id = cells[1].toLong(),
                    title = unb64(cells[2]),
                    document = BlockDocumentJson.decodeDocumentPayload(unb64(cells[3])),
                    notebookId = cells[4].takeIf { it.isNotBlank() }?.toLong(),
                    coverUri = unb64(cells.getOrElse(5) { "" }).takeIf { it.isNotBlank() },
                    coverMimeType = unb64(cells.getOrElse(6) { "" }).takeIf { it.isNotBlank() },
                    pinned = cells[7].toBoolean(),
                    starred = cells[8].toBoolean(),
                    archived = cells[9].toBoolean(),
                    locked = cells[10].toBoolean(),
                    createdAt = cells[11].toLong(),
                    updatedAt = cells[12].toLong(),
                    tags = unb64(cells[13]).split(",").filter { it.isNotBlank() }.mapIndexed { index, name ->
                        Tag(id = index.toLong() + 1, name = name, color = 0xFF8B5CF6)
                    },
                    overlapMode = docOverlapModeOf(cells.getOrNull(14)),
                    freeformLayout = DocLayoutJson.decode(unb64(cells.getOrElse(15) { "" })),
                    canvasSpec = DocLayoutJson.decodeCanvas(unb64(cells.getOrElse(15) { "" })),
                    workspaceId = cells.getOrElse(16) { "1" }.toLong(),
                )
                "ATTACHMENT" -> attachments += Attachment(
                    id = cells[1].toLong(),
                    noteId = cells[2].toLong(),
                    displayName = unb64(cells[3]),
                    mimeType = unb64(cells[4]),
                    uri = unb64(cells[5]),
                    sizeBytes = cells[6].toLong(),
                )
                "EMBED" -> embeds += NoteEmbedItem(
                    id = cells[1].toLong(),
                    noteId = cells[2].toLong(),
                    type = NoteEmbedType.entries.firstOrNull { it.name == cells[3] } ?: NoteEmbedType.Link,
                    title = unb64(cells[4]),
                    target = unb64(cells[5]),
                    preview = unb64(cells.getOrElse(6) { "" }),
                    createdAt = cells.getOrElse(7) { "0" }.toLong(),
                )
                "TASK_BOARD" -> taskBoards += TaskBoardItem(
                    id = cells[1].toLong(),
                    name = unb64(cells[2]),
                    workspaceId = cells.getOrElse(3) { "1" }.toLong(),
                    createdAt = cells.getOrElse(4) { "0" }.toLong(),
                    updatedAt = cells.getOrElse(5) { "0" }.toLong(),
                )
                "TASK_COLUMN" -> taskColumns += TaskColumnItem(
                    id = cells[1].toLong(),
                    boardId = cells[2].toLong(),
                    name = unb64(cells[3]),
                    status = cells.getOrElse(4) { "" }.takeIf { it.isNotBlank() }?.let { value -> TaskStatus.entries.firstOrNull { it.name == value } },
                    color = cells.getOrElse(5) { "0" }.toLong(),
                    sortOrder = cells.getOrElse(6) { "0" }.toInt(),
                    createdAt = cells.getOrElse(7) { "0" }.toLong(),
                    updatedAt = cells.getOrElse(8) { "0" }.toLong(),
                )
                "TASK" -> tasks += TaskItem(
                    id = cells[1].toLong(),
                    title = unb64(cells[2]),
                    description = unb64(cells[3]),
                    assignee = unb64(cells[4]),
                    status = TaskStatus.entries.firstOrNull { it.name == cells[5] } ?: TaskStatus.Todo,
                    priority = TaskPriority.entries.firstOrNull { it.name == cells.getOrElse(6) { TaskPriority.Normal.name } } ?: TaskPriority.Normal,
                    dueAt = cells.getOrElse(7) { "" }.takeIf { it.isNotBlank() }?.toLong(),
                    createdAt = cells.getOrElse(8) { "0" }.toLong(),
                    updatedAt = cells.getOrElse(9) { "0" }.toLong(),
                    labels = unb64(cells.getOrElse(10) { "" }),
                    attachmentName = unb64(cells.getOrElse(11) { "" }).takeIf { it.isNotBlank() },
                    attachmentMimeType = unb64(cells.getOrElse(12) { "" }).takeIf { it.isNotBlank() },
                    attachmentUri = unb64(cells.getOrElse(13) { "" }).takeIf { it.isNotBlank() },
                    attachmentSizeBytes = cells.getOrElse(14) { "" }.takeIf { it.isNotBlank() }?.toLong(),
                    taskBoardId = cells.getOrElse(15) { "1" }.takeIf { it.isNotBlank() }?.toLong() ?: 1L,
                    taskColumnId = cells.getOrElse(16) { "" }.takeIf { it.isNotBlank() }?.toLong(),
                    sortOrder = cells.getOrElse(17) { "0" }.takeIf { it.isNotBlank() }?.toInt() ?: 0,
                    colorArgb = cells.getOrElse(18) { "" }.takeIf { it.isNotBlank() }?.toLong(),
                    startAt = cells.getOrElse(19) { "" }.takeIf { it.isNotBlank() }?.toLong(),
                    allDay = cells.getOrElse(20) { "true" }.toBoolean(),
                    reminderMinutesBefore = cells.getOrElse(21) { "" }.takeIf { it.isNotBlank() }?.toInt(),
                    workspaceId = cells.getOrElse(22) { "1" }.toLong(),
                )
                "TASK_PROPERTY" -> taskPropertyDefinitions += TaskPropertyDefinition(
                    id = cells[1].toLong(),
                    boardId = cells[2].toLong(),
                    name = unb64(cells[3]),
                    type = TaskPropertyType.entries.firstOrNull { it.name == cells.getOrElse(4) { TaskPropertyType.Text.name } } ?: TaskPropertyType.Text,
                    sortOrder = cells.getOrElse(5) { "0" }.toInt(),
                    hiddenWhenEmpty = cells.getOrElse(6) { "false" }.toBoolean(),
                    optionsJson = unb64(cells.getOrElse(7) { "" }),
                    createdAt = cells.getOrElse(8) { "0" }.toLong(),
                    updatedAt = cells.getOrElse(9) { "0" }.toLong(),
                )
                "TASK_PROPERTY_VALUE" -> taskPropertyValues += TaskPropertyValue(
                    id = cells[1].toLong(),
                    taskId = cells[2].toLong(),
                    propertyId = cells[3].toLong(),
                    valueJson = unb64(cells.getOrElse(4) { "" }),
                    updatedAt = cells.getOrElse(5) { "0" }.toLong(),
                )
                "TASK_CHECKLIST_ITEM" -> taskChecklistItems += TaskChecklistItem(
                    id = cells[1].toLong(),
                    taskId = cells[2].toLong(),
                    propertyId = cells[3].toLong(),
                    text = unb64(cells.getOrElse(4) { "" }),
                    checked = cells.getOrElse(5) { "false" }.toBoolean(),
                    sortOrder = cells.getOrElse(6) { "0" }.toInt(),
                    createdAt = cells.getOrElse(7) { "0" }.toLong(),
                    updatedAt = cells.getOrElse(8) { "0" }.toLong(),
                )
                "OWNED_DOCUMENT" -> {
                    val ownerType = DocumentOwnerType.fromStorage(cells[1])
                        ?: error("Unsupported document owner type: ${cells[1]}")
                    ownedDocuments += OwnedDocument(
                        owner = DocumentOwner(ownerType, cells[2].toLong()),
                        document = BlockDocumentJson.decodeDocumentPayload(unb64(cells[3])),
                        layoutMode = docOverlapModeOf(cells.getOrNull(4)),
                        freeformLayout = DocLayoutJson.decode(unb64(cells.getOrElse(5) { "" })),
                        canvasSpec = DocLayoutJson.decodeCanvas(unb64(cells.getOrElse(5) { "" })),
                        createdAt = cells.getOrElse(6) { "0" }.toLong(),
                        updatedAt = cells.getOrElse(7) { "0" }.toLong(),
                    )
                }
                // Legacy PROJECT rows from pre-v24 backups are ignored (feature removed).
                "PROJECT" -> Unit
                "GOAL" -> goals += GoalItem(
                    id = cells[1].toLong(), workspaceId = cells[2].toLong(), syncId = unb64(cells[3]),
                    title = unb64(cells[4]),
                    description = unb64(cells[5]), owner = unb64(cells[6]), target = cells[7].toDouble(),
                    progress = cells[8].toDouble(), unit = unb64(cells[9]), dueAt = cells[10].takeIf { it.isNotBlank() }?.toLong(),
                    status = GoalStatus.entries.firstOrNull { it.name == cells[11] } ?: GoalStatus.NotStarted,
                    createdAt = cells[12].toLong(), updatedAt = cells[13].toLong(),
                )
                "CALENDAR_EVENT" -> calendarEvents += CalendarEventItem(
                    id = cells[1].toLong(), workspaceId = cells[2].toLong(), syncId = unb64(cells[3]),
                    title = unb64(cells[4]), description = unb64(cells[5]),
                    startAt = cells[6].toLong(), endAt = cells[7].toLong(), allDay = cells[8].toBoolean(), color = cells[9].toLong(),
                    source = CalendarEventSource.entries.firstOrNull { it.name == cells[10] } ?: CalendarEventSource.Local,
                    externalId = unb64(cells[11]).takeIf { it.isNotBlank() }, createdAt = cells[12].toLong(), updatedAt = cells[13].toLong(),
                )
                "CHAT" -> chatMessages += ChatMessageItem(
                    id = cells[1].toLong(),
                    authorUsername = unb64(cells[2]),
                    authorDisplayName = unb64(cells[3]),
                    body = unb64(cells[4]),
                    color = cells[5].toLong(),
                    createdAt = cells[6].toLong(),
                    system = cells[7].toBoolean(),
                    attachmentName = unb64(cells.getOrElse(8) { "" }).takeIf { it.isNotBlank() },
                    attachmentMimeType = unb64(cells.getOrElse(9) { "" }).takeIf { it.isNotBlank() },
                    attachmentUri = unb64(cells.getOrElse(10) { "" }).takeIf { it.isNotBlank() },
                    attachmentSizeBytes = cells.getOrElse(11) { "" }.takeIf { it.isNotBlank() }?.toLong(),
                    workspaceId = cells.getOrElse(12) { "1" }.toLong(),
                    channelId = cells.getOrElse(13) { "" }.takeIf { it.isNotBlank() }?.toLong(),
                    replyPreview = unb64(cells.getOrElse(14) { "" }).takeIf { it.isNotBlank() },
                    editedAt = cells.getOrElse(15) { "" }.takeIf { it.isNotBlank() }?.toLong(),
                    reactionsJson = unb64(cells.getOrElse(16) { "" }).ifBlank { "{}" },
                    pinned = cells.getOrElse(17) { "false" }.toBoolean(),
                )
                "CHANNEL" -> {
                    val legacy = ChatChannelKind.entries.any { it.name == cells.getOrElse(2) { "" } }
                    val offset = if (legacy) 0 else 1
                    val id = cells[1].toLong()
                    val workspaceId = cells.getOrElse(7 + offset) { "1" }.toLong()
                    chatChannels += ChatChannel(
                        id = id,
                        syncId = if (legacy) {
                            java.util.UUID.nameUUIDFromBytes("legacy-channel:$workspaceId:$id".toByteArray()).toString()
                        } else cells[2],
                        kind = ChatChannelKind.entries.firstOrNull { it.name == cells.getOrElse(2 + offset) { "" } } ?: ChatChannelKind.Topic,
                        name = unb64(cells.getOrElse(3 + offset) { "" }),
                        counterpartUsername = unb64(cells.getOrElse(4 + offset) { "" }),
                        icon = unb64(cells.getOrElse(5 + offset) { "" }).ifBlank { "app.topics" },
                        createdAt = cells.getOrElse(6 + offset) { "0" }.toLong(),
                        workspaceId = workspaceId,
                        pinned = cells.getOrElse(8 + offset) { "false" }.toBoolean(),
                    )
                }
                "OBJECT" -> workspaceObjects += WorkspaceObject(
                    id = cells[1].toLong(),
                    objectType = WorkspaceObjectType.entries.firstOrNull { it.name == cells[2] } ?: WorkspaceObjectType.System,
                    sourceId = cells[3].takeIf { it.isNotBlank() }?.toLong(),
                    title = unb64(cells[4]),
                    summary = unb64(cells[5]),
                    tags = unb64(cells[6]),
                    icon = unb64(cells[7]),
                    color = cells[8].toLong(),
                    pinned = cells[9].toBoolean(),
                    archived = cells[10].toBoolean(),
                    createdAt = cells[11].toLong(),
                    updatedAt = cells[12].toLong(),
                    workspaceId = cells.getOrElse(13) { "1" }.toLong(),
                )
                "OBJECT_LINK" -> workspaceObjectLinks += WorkspaceObjectLink(
                    id = cells[1].toLong(),
                    fromObjectId = cells[2].toLong(),
                    toObjectId = cells[3].toLong(),
                    linkType = WorkspaceLinkType.entries.firstOrNull { it.name == cells[4] } ?: WorkspaceLinkType.Reference,
                    label = unb64(cells[5]),
                    createdAt = cells[6].toLong(),
                    workspaceId = cells.getOrElse(7) { "1" }.toLong(),
                )
                "ACTIVITY" -> workspaceActivities += WorkspaceActivity(
                    id = cells[1].toLong(),
                    objectId = cells[2].takeIf { it.isNotBlank() }?.toLong(),
                    activityType = WorkspaceActivityType.entries.firstOrNull { it.name == cells[3] } ?: WorkspaceActivityType.Updated,
                    actor = unb64(cells[4]),
                    title = unb64(cells[5]),
                    detail = unb64(cells[6]),
                    createdAt = cells[7].toLong(),
                    workspaceId = cells.getOrElse(8) { "1" }.toLong(),
                )
                "HISTORY" -> workspaceObjectHistory += WorkspaceObjectHistory(
                    id = cells[1].toLong(),
                    objectId = cells[2].takeIf { it.isNotBlank() }?.toLong(),
                    historyType = WorkspaceHistoryType.entries.firstOrNull { it.name == cells[3] } ?: WorkspaceHistoryType.Updated,
                    actor = unb64(cells[4]),
                    summary = unb64(cells[5]),
                    beforeValue = unb64(cells.getOrElse(6) { "" }),
                    afterValue = unb64(cells.getOrElse(7) { "" }),
                    createdAt = cells.getOrElse(8) { "0" }.toLong(),
                    blockId = unb64(cells.getOrElse(10) { "" }).takeIf(String::isNotBlank),
                    workspaceId = cells.getOrElse(9) { "1" }.toLong(),
                )
                "COMMENT" -> workspaceComments += WorkspaceComment(
                    id = cells[1].toLong(),
                    objectId = cells[2].toLong(),
                    authorUsername = unb64(cells[3]),
                    authorDisplayName = unb64(cells[4]),
                    body = unb64(cells[5]),
                    resolved = cells.getOrElse(6) { "false" }.toBoolean(),
                    createdAt = cells.getOrElse(7) { "0" }.toLong(),
                    updatedAt = cells.getOrElse(8) { "0" }.toLong(),
                    workspaceId = cells.getOrElse(9) { "1" }.toLong(),
                )
                "FILE" -> workspaceFiles += WorkspaceFileItem(
                    id = cells[1].toLong(),
                    objectId = cells[2].takeIf { it.isNotBlank() }?.toLong(),
                    displayName = unb64(cells[3]),
                    mimeType = unb64(cells[4]),
                    uri = unb64(cells[5]),
                    sizeBytes = cells[6].toLong(),
                    createdAt = cells[7].toLong(),
                    updatedAt = cells[8].toLong(),
                    workspaceId = cells.getOrElse(9) { "1" }.toLong(),
                )
            }
        }
        return BackupSnapshot(
            workspaces = workspaces,
            taskWorkspacePrefs = taskWorkspacePrefs,
            deviceSettingsProfiles = deviceSettingsProfiles.filter { it.deviceId.isNotBlank() },
            notes = notes,
            notebooks = notebooks,
            tags = tags,
            attachments = attachments,
            embeds = embeds,
            taskBoards = taskBoards,
            taskColumns = taskColumns,
            tasks = tasks,
            taskPropertyDefinitions = taskPropertyDefinitions,
            taskPropertyValues = taskPropertyValues,
            taskChecklistItems = taskChecklistItems,
            ownedDocuments = ownedDocuments,
            goals = goals,
            calendarEvents = calendarEvents,
            chatMessages = chatMessages,
            chatChannels = chatChannels,
            workspaceObjects = workspaceObjects,
            workspaceObjectLinks = workspaceObjectLinks,
            workspaceActivities = workspaceActivities,
            workspaceObjectHistory = workspaceObjectHistory,
            workspaceComments = workspaceComments,
            workspaceFiles = workspaceFiles,
        )
    }

    fun encrypt(snapshot: BackupSnapshot, secret: CharArray): String = VaultCrypto.encrypt(encode(snapshot), secret)
    fun decrypt(payload: String, secret: CharArray): BackupSnapshot = decode(VaultCrypto.decrypt(payload, secret))

    private fun b64(value: String): String = Base64.getEncoder().encodeToString(value.toByteArray(Charsets.UTF_8))
    private fun unb64(value: String): String = Base64.getDecoder().decode(value).toString(Charsets.UTF_8)
}
