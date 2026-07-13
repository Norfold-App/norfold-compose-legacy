package com.norfold.app.data

import androidx.room.Entity
import androidx.room.Embedded
import androidx.room.ColumnInfo
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Junction
import androidx.room.PrimaryKey
import androidx.room.Relation
import com.norfold.app.domain.AppSettings
import com.norfold.app.domain.Attachment
import com.norfold.app.domain.BlockDocument
import com.norfold.app.domain.BlockDocumentJson
import com.norfold.app.domain.CanvasEdgeItem
import com.norfold.app.domain.CanvasNodeItem
import com.norfold.app.domain.CanvasNodeType
import com.norfold.app.domain.ChatMessageItem
import com.norfold.app.domain.ContextualMenuColor
import com.norfold.app.domain.ContextualMenuStyle
import com.norfold.app.domain.EditorFontFamily
import com.norfold.app.domain.EditorLineWidth
import com.norfold.app.domain.Note
import com.norfold.app.domain.NoteEmbedItem
import com.norfold.app.domain.NoteEmbedType
import com.norfold.app.domain.NoteGestureAction
import com.norfold.app.domain.Notebook
import com.norfold.app.domain.GoalItem
import com.norfold.app.domain.GoalStatus
import com.norfold.app.domain.CalendarEventItem
import com.norfold.app.domain.CalendarEventSource
import com.norfold.app.domain.SubscriptionTier
import com.norfold.app.domain.Tag
import com.norfold.app.domain.TaskChecklistItem
import com.norfold.app.domain.TaskItem
import com.norfold.app.domain.TaskPriority
import com.norfold.app.domain.TaskPropertyDefinition
import com.norfold.app.domain.TaskPropertyType
import com.norfold.app.domain.TaskPropertyValue
import com.norfold.app.domain.TaskStatus
import com.norfold.app.domain.ThemeMode
import com.norfold.app.domain.ThemeProfile
import com.norfold.app.domain.SyncProvider
import com.norfold.app.domain.WorkspaceActivity
import com.norfold.app.domain.WorkspaceActivityType
import com.norfold.app.domain.WorkspaceComment
import com.norfold.app.domain.WorkspaceFileItem
import com.norfold.app.domain.WorkspaceHistoryType
import com.norfold.app.domain.WorkspaceIconKind
import com.norfold.app.domain.WorkspaceObject
import com.norfold.app.domain.WorkspaceObjectHistory
import com.norfold.app.domain.WorkspaceObjectLink
import com.norfold.app.domain.WorkspaceObjectType
import com.norfold.app.domain.WorkspaceLinkType

@Entity(tableName = "workspaces")
data class WorkspaceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val icon: String = "N",
    val iconKind: String = WorkspaceIconKind.Text.name,
    val iconUri: String? = null,
    val backgroundUri: String? = null,
    val palette: String = ThemeProfile.Neon.name,
    val createdAt: Long,
    val permRename: Boolean = false,
    val permChangeIcon: Boolean = false,
    val permInviteMembers: Boolean = false,
    val permDeleteNotes: Boolean = false,
    val permEditNotes: Boolean = true,
    val permCreateCanvas: Boolean = true,
    val permManageTasks: Boolean = true,
)

@Entity(tableName = "notebooks", indices = [Index("workspaceId")])
data class NotebookEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val parentId: Long? = null,
    val color: Long,
    val sortOrder: Int,
    val workspaceId: Long = 1,
)

@Entity(
    tableName = "tags",
    indices = [
        Index(value = ["scope", "normalizedName"], unique = true),
        Index(value = ["scope"]),
    ],
)
data class TagEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val color: Long,
    @ColumnInfo(defaultValue = "'notes'") val scope: String = "notes",
    @ColumnInfo(defaultValue = "''") val normalizedName: String = name.trim().trimStart('#').replace(Regex("\\s+"), " ").lowercase(),
)

@Entity(
    tableName = "notes",
    foreignKeys = [
        ForeignKey(
            entity = NotebookEntity::class,
            parentColumns = ["id"],
            childColumns = ["notebookId"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [Index("notebookId"), Index("updatedAt"), Index("pinned"), Index("archived"), Index("workspaceId")],
)
data class NoteEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val searchText: String,
    val notebookId: Long?,
    val coverUri: String? = null,
    val coverMimeType: String? = null,
    val pinned: Boolean,
    val starred: Boolean,
    val archived: Boolean,
    val locked: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
    val workspaceId: Long = 1,
)

@Entity(
    tableName = "note_blocks",
    foreignKeys = [
        ForeignKey(entity = NoteEntity::class, parentColumns = ["id"], childColumns = ["noteId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("noteId"), Index(value = ["noteId", "position"])],
)
data class NoteBlockEntity(
    @PrimaryKey val id: String,
    val noteId: Long,
    val position: Int,
    val payloadJson: String,
    val updatedAt: Long,
)

@Entity(
    tableName = "attachments",
    foreignKeys = [
        ForeignKey(entity = NoteEntity::class, parentColumns = ["id"], childColumns = ["noteId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("noteId")],
)
data class AttachmentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val noteId: Long,
    val displayName: String,
    val mimeType: String,
    val uri: String,
    val sizeBytes: Long,
)

@Entity(
    tableName = "note_embeds",
    foreignKeys = [
        ForeignKey(entity = NoteEntity::class, parentColumns = ["id"], childColumns = ["noteId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("noteId"), Index("type")],
)
data class NoteEmbedEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val noteId: Long,
    val type: String,
    val title: String,
    val target: String,
    val preview: String = "",
    val createdAt: Long,
)

@Entity(
    tableName = "note_tags",
    primaryKeys = ["noteId", "tagId"],
    foreignKeys = [
        ForeignKey(entity = NoteEntity::class, parentColumns = ["id"], childColumns = ["noteId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = TagEntity::class, parentColumns = ["id"], childColumns = ["tagId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("tagId")],
)
data class NoteTagCrossRef(
    val noteId: Long,
    val tagId: Long,
)

@Entity(tableName = "task_boards", indices = [Index("workspaceId"), Index("updatedAt")])
data class TaskBoardEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val workspaceId: Long = 1,
    val createdAt: Long,
    val updatedAt: Long,
)

@Entity(tableName = "task_columns", indices = [Index("boardId"), Index("sortOrder")])
data class TaskColumnEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val boardId: Long,
    val name: String,
    val status: String? = null,
    val color: Long,
    val sortOrder: Int,
    val createdAt: Long,
    val updatedAt: Long,
)

@Entity(tableName = "tasks", indices = [Index("status"), Index("updatedAt"), Index("workspaceId"), Index("taskBoardId"), Index("taskColumnId")])
data class TaskEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val description: String = "",
    val assignee: String = "",
    val status: String = TaskStatus.Todo.name,
    val priority: String = TaskPriority.Normal.name,
    val startAt: Long? = null,
    val dueAt: Long? = null,
    val allDay: Boolean = true,
    val reminderMinutesBefore: Int? = null,
    val labels: String = "",
    val attachmentName: String? = null,
    val attachmentMimeType: String? = null,
    val attachmentUri: String? = null,
    val attachmentSizeBytes: Long? = null,
    val createdAt: Long,
    val updatedAt: Long,
    val workspaceId: Long = 1,
    val taskBoardId: Long = 1,
    val taskColumnId: Long? = null,
    val sortOrder: Int = 0,
    val colorArgb: Long? = null,
)

@Entity(
    tableName = "task_property_definitions",
    indices = [
        Index("boardId"),
        Index("sortOrder"),
        Index(value = ["boardId", "name"], unique = true),
    ],
)
data class TaskPropertyDefinitionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val boardId: Long,
    val name: String,
    val type: String,
    val sortOrder: Int,
    val hiddenWhenEmpty: Boolean = false,
    val optionsJson: String = "",
    val createdAt: Long,
    val updatedAt: Long,
)

@Entity(
    tableName = "task_property_values",
    indices = [
        Index("taskId"),
        Index("propertyId"),
        Index(value = ["taskId", "propertyId"], unique = true),
    ],
)
data class TaskPropertyValueEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val taskId: Long,
    val propertyId: Long,
    val valueJson: String,
    val updatedAt: Long,
)

@Entity(
    tableName = "task_checklist_items",
    indices = [Index("taskId"), Index("propertyId"), Index("sortOrder")],
)
data class TaskChecklistItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val taskId: Long,
    val propertyId: Long,
    val text: String,
    val checked: Boolean = false,
    val sortOrder: Int,
    val createdAt: Long,
    val updatedAt: Long,
)

@Entity(tableName = "chat_messages", indices = [Index("createdAt"), Index("workspaceId")])
data class ChatMessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val authorUsername: String,
    val authorDisplayName: String,
    val body: String,
    val color: Long,
    val createdAt: Long,
    val system: Boolean = false,
    val attachmentName: String? = null,
    val attachmentMimeType: String? = null,
    val attachmentUri: String? = null,
    val attachmentSizeBytes: Long? = null,
    val workspaceId: Long = 1,
)

@Entity(tableName = "canvas_nodes", indices = [Index("updatedAt"), Index("type"), Index("workspaceId")])
data class CanvasNodeEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val subtitle: String,
    val type: String,
    val x: Float,
    val y: Float,
    val color: Long,
    val linkedNoteId: Long? = null,
    val targetUri: String? = null,
    val targetMimeType: String? = null,
    val targetName: String? = null,
    val targetSizeBytes: Long? = null,
    val createdAt: Long,
    val updatedAt: Long,
    val workspaceId: Long = 1,
)

@Entity(tableName = "canvas_edges", indices = [Index("fromNodeId"), Index("toNodeId"), Index("workspaceId")])
data class CanvasEdgeEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val fromNodeId: Long,
    val toNodeId: Long,
    val label: String = "",
    val color: Long,
    val createdAt: Long,
    val updatedAt: Long,
    val workspaceId: Long = 1,
)

@Entity(tableName = "workspace_objects", indices = [Index("workspaceId"), Index("objectType"), Index("sourceId"), Index("updatedAt"), Index("pinned")])
data class WorkspaceObjectEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val objectType: String,
    val sourceId: Long? = null,
    val title: String,
    val summary: String = "",
    val tags: String = "",
    val icon: String = "",
    val color: Long = 0xFF8B5CF6,
    val pinned: Boolean = false,
    val archived: Boolean = false,
    val createdAt: Long,
    val updatedAt: Long,
    val workspaceId: Long = 1,
)

@Entity(tableName = "workspace_object_links", indices = [Index("workspaceId"), Index("fromObjectId"), Index("toObjectId"), Index("linkType")])
data class WorkspaceObjectLinkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val fromObjectId: Long,
    val toObjectId: Long,
    val linkType: String = WorkspaceLinkType.Reference.name,
    val label: String = "",
    val createdAt: Long,
    val workspaceId: Long = 1,
)

@Entity(tableName = "workspace_activities", indices = [Index("workspaceId"), Index("objectId"), Index("createdAt"), Index("activityType")])
data class WorkspaceActivityEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val objectId: Long? = null,
    val activityType: String,
    val actor: String,
    val title: String,
    val detail: String = "",
    val createdAt: Long,
    val workspaceId: Long = 1,
)

@Entity(tableName = "workspace_object_history", indices = [Index("workspaceId"), Index("objectId"), Index("historyType"), Index("createdAt")])
data class WorkspaceObjectHistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val objectId: Long? = null,
    val historyType: String,
    val actor: String,
    val summary: String,
    val beforeValue: String = "",
    val afterValue: String = "",
    val createdAt: Long,
    val workspaceId: Long = 1,
)

@Entity(tableName = "workspace_comments", indices = [Index("workspaceId"), Index("objectId"), Index("authorUsername"), Index("resolved"), Index("updatedAt")])
data class WorkspaceCommentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val objectId: Long,
    val authorUsername: String,
    val authorDisplayName: String,
    val body: String,
    val resolved: Boolean = false,
    val createdAt: Long,
    val updatedAt: Long,
    val workspaceId: Long = 1,
)

@Entity(tableName = "workspace_files", indices = [Index("workspaceId"), Index("objectId"), Index("mimeType"), Index("updatedAt")])
data class WorkspaceFileEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val objectId: Long? = null,
    val displayName: String,
    val mimeType: String,
    val uri: String,
    val sizeBytes: Long,
    val createdAt: Long,
    val updatedAt: Long,
    val workspaceId: Long = 1,
)

@Entity(tableName = "goals", indices = [Index("workspaceId"), Index(value = ["syncId"], unique = true), Index("status"), Index("updatedAt")])
data class GoalEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val workspaceId: Long = 1,
    val syncId: String,
    val title: String,
    val description: String = "",
    val owner: String = "",
    val target: Double = 100.0,
    val progress: Double = 0.0,
    val unit: String = "%",
    val dueAt: Long? = null,
    val status: String = GoalStatus.NotStarted.name,
    val createdAt: Long,
    val updatedAt: Long,
)

@Entity(tableName = "calendar_events", indices = [Index("workspaceId"), Index(value = ["syncId"], unique = true), Index("startAt"), Index("source"), Index("externalId")])
data class CalendarEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val workspaceId: Long = 1,
    val syncId: String,
    val title: String,
    val description: String = "",
    val startAt: Long,
    val endAt: Long,
    val allDay: Boolean = false,
    val color: Long = 0xFF6F36FF,
    val source: String = CalendarEventSource.Local.name,
    val externalId: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
)

@Entity(
    tableName = "remote_object_bindings",
    indices = [
        Index(value = ["workspaceId", "objectType", "localId"], unique = true),
        Index(value = ["workspaceId", "syncId"], unique = true),
        Index("remoteId"),
    ],
)
data class RemoteObjectBindingEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val workspaceId: Long,
    val objectType: String,
    val localId: Long,
    val syncId: String,
    val remoteId: String? = null,
    val remoteVersion: Long = 0,
    val contentHash: String = "",
    val updatedAt: Long,
)

@Entity(
    tableName = "sync_outbox",
    indices = [
        Index(value = ["operationId"], unique = true),
        Index(value = ["workspaceId", "state", "nextAttemptAt"]),
        Index(value = ["workspaceId", "objectType", "objectSyncId"]),
    ],
)
data class SyncOutboxEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val workspaceId: Long,
    val operationId: String,
    val objectType: String,
    val objectSyncId: String,
    val operation: String,
    val payload: String,
    val contentHash: String,
    val baseVersion: Long? = null,
    val attemptCount: Int = 0,
    val nextAttemptAt: Long,
    val state: String = "Pending",
    val lastError: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
)

@Entity(
    tableName = "sync_tombstones",
    indices = [
        Index(value = ["workspaceId", "objectType", "objectSyncId"], unique = true),
        Index(value = ["workspaceId", "acknowledgedAt"]),
    ],
)
data class SyncTombstoneEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val workspaceId: Long,
    val objectType: String,
    val objectSyncId: String,
    val deletedAt: Long,
    val acknowledgedAt: Long? = null,
)

@Entity(tableName = "settings")
data class AppSettingsEntity(
    @PrimaryKey val id: Int = 1,
    val themeMode: String = ThemeMode.System.name,
    val themeProfile: String = ThemeProfile.Neon.name,
    val accentColor: Long = 0xFF6F36FF,
    val activeWorkspaceId: Long = 1,
    val backupFolderUri: String? = null,
    val vaultLockEnabled: Boolean = false,
    val vaultSecretHash: String? = null,
    val syncProvider: String = SyncProvider.None.name,
    val syncFolderUri: String? = null,
    val syncChainId: String? = null,
    val syncDeviceName: String = "Android device",
    val syncUserName: String = "",
    val syncPublicName: String = "",
    val lastSyncAt: Long? = null,
    val lastSyncHash: String? = null,
    val lastSyncStatus: String = "Sync chain not configured",
    val syncConflictCount: Int = 0,
    val profileBackgroundUri: String? = null,
    val profileImageUri: String? = null,
    val workspaceName: String = "My Workspace",
    val workspaceIcon: String = "N",
    val workspaceIconKind: String = WorkspaceIconKind.Text.name,
    val workspaceIconUri: String? = null,
    val workspaceBackgroundUri: String? = null,
    val adminsControlWorkspaceVisuals: Boolean = true,
    val allowMembersCreateNotes: Boolean = true,
    val allowMembersInvite: Boolean = false,
    val uiScale: Float = 1f,
    val editorLineWidth: String = EditorLineWidth.Comfortable.name,
    val editorFontFamily: String = EditorFontFamily.Sans.name,
    val showMarkdownSyntax: Boolean = true,
    val noteLongPressAction: String = NoteGestureAction.Actions.name,
    val noteSwipeStartAction: String = NoteGestureAction.Pin.name,
    val noteSwipeEndAction: String = NoteGestureAction.Archive.name,
    val blockScreenshots: Boolean = false,
    val requireBiometricOnOpen: Boolean = false,
    val reduceMotion: Boolean = false,
    val uiDensityCompact: Boolean = false,
    val appFont: String = "Inter",
    val defaultEditMode: Boolean = true,
    val editorFontSize: Float = 0.55f,
    val tabSize: Int = 4,
    val showLineNumbers: Boolean = true,
    val autoPairBrackets: Boolean = true,
    val syntaxColorful: Boolean = true,
    val autoConvertOnPaste: Boolean = true,
    val contextualMenuStyle: String = ContextualMenuStyle.Pill.name,
    val contextualMenuColor: String = ContextualMenuColor.FollowTheme.name,
    val appLockOnExit: Boolean = false,
    val autoLockMinutes: Int = 5,
    val autoBackup: Boolean = false,
    val backupFrequency: String = "Daily",
    val autoSync: Boolean = true,
    val backgroundSync: Boolean = true,
    val syncIntervalMinutes: Int = 30,
    val syncOnMobileData: Boolean = false,
    val syncOnBatterySaver: Boolean = false,
    val notifyOnErrors: Boolean = true,
    val selectiveSync: Boolean = false,
    val conflictDefaultAction: String = "Ask me",
    val autoMergeNonConflicting: Boolean = true,
    val keepBothCopies: Boolean = false,
    val taskViewMode: String = "Board",
    val taskSelectedBoardId: Long = 1,
    val taskSortMode: String = "Manual",
    val taskCompactLayout: Boolean = true,
    val taskKanbanEngine: String = "BoardPointer",
    val onboardingComplete: Boolean = false,
    val workspacePurpose: String = "Personal",
    val calendarDefaultView: String = "Month",
    val quietHoursEnabled: Boolean = false,
    val quietHoursStart: String = "22:00",
    val quietHoursEnd: String = "07:00",
    val notificationInApp: Boolean = true,
    val notificationEmail: Boolean = false,
    val notificationPush: Boolean = true,
    val subscriptionTier: String = SubscriptionTier.Free.name,
)

fun WorkspaceEntity.toDomain() = com.norfold.app.domain.Workspace(
    id = id,
    name = name,
    icon = icon,
    iconKind = WorkspaceIconKind.entries.firstOrNull { it.name == iconKind } ?: WorkspaceIconKind.Text,
    iconUri = iconUri,
    backgroundUri = backgroundUri,
    palette = ThemeProfile.entries.firstOrNull { it.name == palette } ?: ThemeProfile.Neon,
    createdAt = createdAt,
    permRename = permRename,
    permChangeIcon = permChangeIcon,
    permInviteMembers = permInviteMembers,
    permDeleteNotes = permDeleteNotes,
    permEditNotes = permEditNotes,
    permCreateCanvas = permCreateCanvas,
    permManageTasks = permManageTasks,
)
fun NotebookEntity.toDomain() = Notebook(id = id, name = name, parentId = parentId, color = color, sortOrder = sortOrder)
fun TagEntity.toDomain() = Tag(id = id, name = name, color = color, scope = scope)
fun AttachmentEntity.toDomain() = Attachment(id = id, noteId = noteId, displayName = displayName, mimeType = mimeType, uri = uri, sizeBytes = sizeBytes)
fun NoteEmbedEntity.toDomain() = NoteEmbedItem(
    id = id,
    noteId = noteId,
    type = NoteEmbedType.entries.firstOrNull { it.name == type } ?: NoteEmbedType.Link,
    title = title,
    target = target,
    preview = preview,
    createdAt = createdAt,
)
fun TaskBoardEntity.toDomain() = com.norfold.app.domain.TaskBoardItem(
    id = id,
    name = name,
    workspaceId = workspaceId,
    createdAt = createdAt,
    updatedAt = updatedAt,
)
fun TaskColumnEntity.toDomain() = com.norfold.app.domain.TaskColumnItem(
    id = id,
    boardId = boardId,
    name = name,
    status = status?.let { value -> TaskStatus.entries.firstOrNull { it.name == value } },
    color = color,
    sortOrder = sortOrder,
    createdAt = createdAt,
    updatedAt = updatedAt,
)
fun TaskEntity.toDomain() = TaskItem(
    id = id,
    title = title,
    description = description,
    assignee = assignee,
    status = TaskStatus.entries.firstOrNull { it.name == status } ?: TaskStatus.Todo,
    priority = TaskPriority.entries.firstOrNull { it.name == priority } ?: TaskPriority.Normal,
    startAt = startAt,
    dueAt = dueAt,
    allDay = allDay,
    reminderMinutesBefore = reminderMinutesBefore,
    labels = labels,
    attachmentName = attachmentName,
    attachmentMimeType = attachmentMimeType,
    attachmentUri = attachmentUri,
    attachmentSizeBytes = attachmentSizeBytes,
    createdAt = createdAt,
    updatedAt = updatedAt,
    taskBoardId = taskBoardId,
    taskColumnId = taskColumnId,
    sortOrder = sortOrder,
    colorArgb = colorArgb,
)
fun TaskPropertyDefinitionEntity.toDomain() = TaskPropertyDefinition(
    id = id,
    boardId = boardId,
    name = name,
    type = TaskPropertyType.entries.firstOrNull { it.name == type } ?: TaskPropertyType.Text,
    sortOrder = sortOrder,
    hiddenWhenEmpty = hiddenWhenEmpty,
    optionsJson = optionsJson,
    createdAt = createdAt,
    updatedAt = updatedAt,
)
fun TaskPropertyValueEntity.toDomain() = TaskPropertyValue(
    id = id,
    taskId = taskId,
    propertyId = propertyId,
    valueJson = valueJson,
    updatedAt = updatedAt,
)
fun TaskChecklistItemEntity.toDomain() = TaskChecklistItem(
    id = id,
    taskId = taskId,
    propertyId = propertyId,
    text = text,
    checked = checked,
    sortOrder = sortOrder,
    createdAt = createdAt,
    updatedAt = updatedAt,
)
fun ChatMessageEntity.toDomain() = ChatMessageItem(
    id = id,
    authorUsername = authorUsername,
    authorDisplayName = authorDisplayName,
    body = body,
    color = color,
    createdAt = createdAt,
    system = system,
    attachmentName = attachmentName,
    attachmentMimeType = attachmentMimeType,
    attachmentUri = attachmentUri,
    attachmentSizeBytes = attachmentSizeBytes,
)
fun CanvasNodeEntity.toDomain() = CanvasNodeItem(
    id = id,
    title = title,
    subtitle = subtitle,
    type = CanvasNodeType.entries.firstOrNull { it.name == type } ?: CanvasNodeType.Text,
    x = x,
    y = y,
    color = color,
    linkedNoteId = linkedNoteId,
    targetUri = targetUri,
    targetMimeType = targetMimeType,
    targetName = targetName,
    targetSizeBytes = targetSizeBytes,
    createdAt = createdAt,
    updatedAt = updatedAt,
)
fun CanvasEdgeEntity.toDomain() = CanvasEdgeItem(
    id = id,
    fromNodeId = fromNodeId,
    toNodeId = toNodeId,
    label = label,
    color = color,
    createdAt = createdAt,
    updatedAt = updatedAt,
)
fun WorkspaceObjectEntity.toDomain() = WorkspaceObject(
    id = id,
    objectType = WorkspaceObjectType.entries.firstOrNull { it.name == objectType } ?: WorkspaceObjectType.System,
    sourceId = sourceId,
    title = title,
    summary = summary,
    tags = tags,
    icon = icon,
    color = color,
    pinned = pinned,
    archived = archived,
    createdAt = createdAt,
    updatedAt = updatedAt,
)
fun WorkspaceObjectLinkEntity.toDomain() = WorkspaceObjectLink(
    id = id,
    fromObjectId = fromObjectId,
    toObjectId = toObjectId,
    linkType = WorkspaceLinkType.entries.firstOrNull { it.name == linkType } ?: WorkspaceLinkType.Reference,
    label = label,
    createdAt = createdAt,
)
fun WorkspaceActivityEntity.toDomain() = WorkspaceActivity(
    id = id,
    objectId = objectId,
    activityType = WorkspaceActivityType.entries.firstOrNull { it.name == activityType } ?: WorkspaceActivityType.Updated,
    actor = actor,
    title = title,
    detail = detail,
    createdAt = createdAt,
)
fun WorkspaceObjectHistoryEntity.toDomain() = WorkspaceObjectHistory(
    id = id,
    objectId = objectId,
    historyType = WorkspaceHistoryType.entries.firstOrNull { it.name == historyType } ?: WorkspaceHistoryType.Updated,
    actor = actor,
    summary = summary,
    beforeValue = beforeValue,
    afterValue = afterValue,
    createdAt = createdAt,
)
fun WorkspaceCommentEntity.toDomain() = WorkspaceComment(
    id = id,
    objectId = objectId,
    authorUsername = authorUsername,
    authorDisplayName = authorDisplayName,
    body = body,
    resolved = resolved,
    createdAt = createdAt,
    updatedAt = updatedAt,
)
fun WorkspaceFileEntity.toDomain() = WorkspaceFileItem(
    id = id,
    objectId = objectId,
    displayName = displayName,
    mimeType = mimeType,
    uri = uri,
    sizeBytes = sizeBytes,
    createdAt = createdAt,
    updatedAt = updatedAt,
)
fun GoalEntity.toDomain() = GoalItem(
    id = id,
    workspaceId = workspaceId,
    syncId = syncId,
    title = title,
    description = description,
    owner = owner,
    target = target,
    progress = progress,
    unit = unit,
    dueAt = dueAt,
    status = GoalStatus.entries.firstOrNull { it.name == status } ?: GoalStatus.NotStarted,
    createdAt = createdAt,
    updatedAt = updatedAt,
)
fun CalendarEventEntity.toDomain() = CalendarEventItem(
    id = id,
    workspaceId = workspaceId,
    syncId = syncId,
    title = title,
    description = description,
    startAt = startAt,
    endAt = endAt,
    allDay = allDay,
    color = color,
    source = CalendarEventSource.entries.firstOrNull { it.name == source } ?: CalendarEventSource.Local,
    externalId = externalId,
    createdAt = createdAt,
    updatedAt = updatedAt,
)
fun AppSettingsEntity.toDomain() = AppSettings(
    themeMode = ThemeMode.entries.firstOrNull { it.name == themeMode } ?: ThemeMode.System,
    themeProfile = ThemeProfile.entries.firstOrNull { it.name == themeProfile } ?: ThemeProfile.Neon,
    accentColor = accentColor,
    activeWorkspaceId = activeWorkspaceId,
    backupFolderUri = backupFolderUri,
    vaultLockEnabled = vaultLockEnabled,
    vaultSecretHash = vaultSecretHash,
    syncProvider = SyncProvider.entries.firstOrNull { it.name == syncProvider } ?: SyncProvider.None,
    syncFolderUri = syncFolderUri,
    syncChainId = syncChainId,
    syncDeviceName = syncDeviceName,
    syncUserName = syncUserName,
    syncPublicName = syncPublicName,
    lastSyncAt = lastSyncAt,
    lastSyncHash = lastSyncHash,
    lastSyncStatus = lastSyncStatus,
    syncConflictCount = syncConflictCount,
    profileBackgroundUri = profileBackgroundUri,
    profileImageUri = profileImageUri,
    workspaceName = workspaceName,
    workspaceIcon = workspaceIcon,
    workspaceIconKind = WorkspaceIconKind.entries.firstOrNull { it.name == workspaceIconKind } ?: WorkspaceIconKind.Text,
    workspaceIconUri = workspaceIconUri,
    workspaceBackgroundUri = workspaceBackgroundUri,
    adminsControlWorkspaceVisuals = adminsControlWorkspaceVisuals,
    allowMembersCreateNotes = allowMembersCreateNotes,
    allowMembersInvite = allowMembersInvite,
    uiScale = uiScale.coerceIn(0.78f, 1.12f),
    editorLineWidth = EditorLineWidth.entries.firstOrNull { it.name == editorLineWidth } ?: EditorLineWidth.Comfortable,
    editorFontFamily = EditorFontFamily.entries.firstOrNull { it.name == editorFontFamily } ?: EditorFontFamily.Sans,
    showMarkdownSyntax = showMarkdownSyntax,
    noteLongPressAction = NoteGestureAction.entries.firstOrNull { it.name == noteLongPressAction } ?: NoteGestureAction.Actions,
    noteSwipeStartAction = NoteGestureAction.entries.firstOrNull { it.name == noteSwipeStartAction } ?: NoteGestureAction.Pin,
    noteSwipeEndAction = NoteGestureAction.entries.firstOrNull { it.name == noteSwipeEndAction } ?: NoteGestureAction.Archive,
    blockScreenshots = blockScreenshots,
    requireBiometricOnOpen = requireBiometricOnOpen,
    reduceMotion = reduceMotion,
    uiDensityCompact = uiDensityCompact,
    appFont = appFont,
    defaultEditMode = defaultEditMode,
    editorFontSize = editorFontSize,
    tabSize = tabSize,
    showLineNumbers = showLineNumbers,
    autoPairBrackets = autoPairBrackets,
    syntaxColorful = syntaxColorful,
    autoConvertOnPaste = autoConvertOnPaste,
    contextualMenuStyle = ContextualMenuStyle.entries.firstOrNull { it.name == contextualMenuStyle } ?: ContextualMenuStyle.Pill,
    contextualMenuColor = ContextualMenuColor.entries.firstOrNull { it.name == contextualMenuColor } ?: ContextualMenuColor.FollowTheme,
    appLockOnExit = appLockOnExit,
    autoLockMinutes = autoLockMinutes,
    autoBackup = autoBackup,
    backupFrequency = backupFrequency,
    autoSync = autoSync,
    backgroundSync = backgroundSync,
    syncIntervalMinutes = syncIntervalMinutes,
    syncOnMobileData = syncOnMobileData,
    syncOnBatterySaver = syncOnBatterySaver,
    notifyOnErrors = notifyOnErrors,
    selectiveSync = selectiveSync,
    conflictDefaultAction = conflictDefaultAction,
    autoMergeNonConflicting = autoMergeNonConflicting,
    keepBothCopies = keepBothCopies,
    taskViewMode = taskViewMode,
    taskSelectedBoardId = taskSelectedBoardId,
    taskSortMode = taskSortMode,
    taskCompactLayout = taskCompactLayout,
    taskKanbanEngine = taskKanbanEngine,
    onboardingComplete = onboardingComplete,
    workspacePurpose = workspacePurpose,
    calendarDefaultView = calendarDefaultView,
    quietHoursEnabled = quietHoursEnabled,
    quietHoursStart = quietHoursStart,
    quietHoursEnd = quietHoursEnd,
    notificationInApp = notificationInApp,
    notificationEmail = notificationEmail,
    notificationPush = notificationPush,
    subscriptionTier = SubscriptionTier.entries.firstOrNull { it.name == subscriptionTier } ?: SubscriptionTier.Free,
)

fun NoteEntity.toDomain(
    blocks: List<NoteBlockEntity> = emptyList(),
    tags: List<Tag> = emptyList(),
    attachments: List<Attachment> = emptyList(),
) = Note(
    id = id,
    title = title,
    document = BlockDocument(
        blocks.sortedBy(NoteBlockEntity::position).mapNotNull { runCatching { BlockDocumentJson.decodeBlock(it.payloadJson) }.getOrNull() },
    ).normalized(),
    notebookId = notebookId,
    coverUri = coverUri,
    coverMimeType = coverMimeType,
    pinned = pinned,
    starred = starred,
    archived = archived,
    locked = locked,
    createdAt = createdAt,
    updatedAt = updatedAt,
    tags = tags,
    attachments = attachments,
)

data class NoteWithRelations(
    @Embedded val note: NoteEntity,
    @Relation(parentColumn = "id", entityColumn = "noteId")
    val blocks: List<NoteBlockEntity>,
    @Relation(
        parentColumn = "id",
        entityColumn = "id",
        associateBy = Junction(value = NoteTagCrossRef::class, parentColumn = "noteId", entityColumn = "tagId"),
    )
    val tags: List<TagEntity>,
    @Relation(parentColumn = "id", entityColumn = "noteId")
    val attachments: List<AttachmentEntity>,
    @Relation(parentColumn = "id", entityColumn = "noteId")
    val embeds: List<NoteEmbedEntity>,
) {
    fun toDomain(): Note = note.toDomain(
        blocks = blocks,
        tags = tags.map { it.toDomain() },
        attachments = attachments.map { it.toDomain() },
    ).copy(embeds = embeds.map { it.toDomain() })
}
