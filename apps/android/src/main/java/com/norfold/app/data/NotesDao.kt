package com.norfold.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface NotesDao {
    // ---- Workspaces ----
    @Query("SELECT * FROM workspaces ORDER BY createdAt ASC")
    fun observeWorkspaces(): Flow<List<WorkspaceEntity>>

    @Query("SELECT * FROM workspaces ORDER BY createdAt ASC")
    suspend fun allWorkspaces(): List<WorkspaceEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertWorkspace(entity: WorkspaceEntity): Long

    @Query("UPDATE workspaces SET name = :name, icon = :icon, iconKind = :iconKind, iconUri = :iconUri, backgroundUri = :backgroundUri, palette = :palette WHERE id = :id")
    suspend fun updateWorkspaceMeta(id: Long, name: String, icon: String, iconKind: String, iconUri: String?, backgroundUri: String?, palette: String)

    @Query(
        "UPDATE workspaces SET permRename = :permRename, permChangeIcon = :permChangeIcon, permInviteMembers = :permInviteMembers, " +
            "permDeleteNotes = :permDeleteNotes, permEditNotes = :permEditNotes, permManageTasks = :permManageTasks WHERE id = :id",
    )
    suspend fun updateWorkspacePermissions(
        id: Long,
        permRename: Boolean,
        permChangeIcon: Boolean,
        permInviteMembers: Boolean,
        permDeleteNotes: Boolean,
        permEditNotes: Boolean,
        permManageTasks: Boolean,
    )

    @Query("DELETE FROM workspaces WHERE id = :id")
    suspend fun deleteWorkspaceById(id: Long)

    @Query("DELETE FROM notes WHERE workspaceId = :ws")
    suspend fun deleteNotesForWorkspace(ws: Long)

    @Query("DELETE FROM documents WHERE owner_type = 'note' AND owner_id IN (SELECT id FROM notes WHERE workspaceId = :ws)")
    suspend fun deleteNoteDocumentsForWorkspace(ws: Long)

    @Query("DELETE FROM documents WHERE owner_type = 'task' AND owner_id IN (SELECT id FROM tasks WHERE workspaceId = :ws)")
    suspend fun deleteTaskDocumentsForWorkspace(ws: Long)

    @Query("DELETE FROM documents WHERE owner_type = 'calendar_event' AND owner_id IN (SELECT id FROM calendar_events WHERE workspaceId = :ws)")
    suspend fun deleteCalendarEventDocumentsForWorkspace(ws: Long)

    @Query("DELETE FROM notebooks WHERE workspaceId = :ws")
    suspend fun deleteNotebooksForWorkspace(ws: Long)

    @Query("DELETE FROM tasks WHERE workspaceId = :ws")
    suspend fun deleteTasksForWorkspace(ws: Long)

    @Query("DELETE FROM chat_messages WHERE workspaceId = :ws")
    suspend fun deleteChatForWorkspace(ws: Long)

    @Query("DELETE FROM workspace_objects WHERE workspaceId = :ws")
    suspend fun deleteWorkspaceObjectsForWorkspace(ws: Long)

    @Query("DELETE FROM workspace_object_links WHERE workspaceId = :ws")
    suspend fun deleteWorkspaceObjectLinksForWorkspace(ws: Long)

    @Query("DELETE FROM workspace_activities WHERE workspaceId = :ws")
    suspend fun deleteWorkspaceActivitiesForWorkspace(ws: Long)

    @Query("DELETE FROM workspace_object_history WHERE workspaceId = :ws")
    suspend fun deleteWorkspaceObjectHistoryForWorkspace(ws: Long)

    @Query("DELETE FROM workspace_comments WHERE workspaceId = :ws")
    suspend fun deleteWorkspaceCommentsForWorkspace(ws: Long)

    @Query("DELETE FROM workspace_files WHERE workspaceId = :ws")
    suspend fun deleteWorkspaceFilesForWorkspace(ws: Long)

    @Query("DELETE FROM calendar_events WHERE workspaceId = :ws")
    suspend fun deleteCalendarEventsForWorkspace(ws: Long)

    @Query("DELETE FROM goals WHERE workspaceId = :ws")
    suspend fun deleteGoalsForWorkspace(ws: Long)

    @Query("DELETE FROM sync_outbox WHERE workspaceId = :ws")
    suspend fun deleteSyncOutboxForWorkspace(ws: Long)

    @Query("DELETE FROM sync_tombstones WHERE workspaceId = :ws")
    suspend fun deleteSyncTombstonesForWorkspace(ws: Long)

    @Query("DELETE FROM remote_object_bindings WHERE workspaceId = :ws")
    suspend fun deleteRemoteBindingsForWorkspace(ws: Long)

    @Transaction
    @Query("SELECT * FROM notes WHERE archived = 0 AND workspaceId = :ws ORDER BY pinned DESC, updatedAt DESC")
    fun observeActiveNotes(ws: Long): Flow<List<NoteWithRelations>>

    @Transaction
    @Query("SELECT * FROM notes WHERE archived = 1 AND workspaceId = :ws ORDER BY updatedAt DESC")
    fun observeArchivedNotes(ws: Long): Flow<List<NoteWithRelations>>

    @Transaction
    @Query(
        """
        SELECT DISTINCT notes.* FROM notes
        LEFT JOIN note_tags ON note_tags.noteId = notes.id
        LEFT JOIN tags ON tags.id = note_tags.tagId
        WHERE notes.archived = 0 AND notes.workspaceId = :ws AND (
            notes.title LIKE '%' || :query || '%' OR
            notes.searchText LIKE '%' || :query || '%' OR
            tags.name LIKE '%' || :query || '%'
        )
        ORDER BY notes.pinned DESC, notes.updatedAt DESC
        """,
    )
    fun searchNotes(query: String, ws: Long): Flow<List<NoteWithRelations>>

    @Transaction
    @Query("SELECT * FROM notes WHERE id = :id LIMIT 1")
    suspend fun noteById(id: Long): NoteWithRelations?

    @Transaction
    @Query("SELECT * FROM notes ORDER BY updatedAt DESC")
    suspend fun allNotesSnapshot(): List<NoteWithRelations>

    @Query("SELECT * FROM notebooks WHERE workspaceId = :ws ORDER BY sortOrder ASC, name ASC")
    fun observeNotebooks(ws: Long): Flow<List<NotebookEntity>>

    @Query("SELECT * FROM notebooks ORDER BY sortOrder ASC, name ASC")
    suspend fun allNotebooks(): List<NotebookEntity>

    @Query("SELECT * FROM tags ORDER BY name ASC")
    fun observeTags(): Flow<List<TagEntity>>

    @Query("SELECT * FROM tags ORDER BY name ASC")
    suspend fun allTags(): List<TagEntity>

    @Query("SELECT * FROM attachments ORDER BY id ASC")
    suspend fun allAttachments(): List<AttachmentEntity>

    @Query("SELECT * FROM note_embeds ORDER BY createdAt ASC")
    suspend fun allNoteEmbeds(): List<NoteEmbedEntity>

    @Query("SELECT * FROM task_boards WHERE workspaceId = :ws ORDER BY updatedAt DESC")
    fun observeTaskBoards(ws: Long): Flow<List<TaskBoardEntity>>

    @Query("SELECT * FROM task_boards WHERE workspaceId = :ws ORDER BY updatedAt DESC")
    suspend fun taskBoardsForWorkspace(ws: Long): List<TaskBoardEntity>

    @Query("SELECT * FROM task_boards WHERE id = :id LIMIT 1")
    suspend fun taskBoardById(id: Long): TaskBoardEntity?

    @Query("SELECT * FROM task_boards ORDER BY workspaceId ASC, updatedAt DESC")
    suspend fun allTaskBoards(): List<TaskBoardEntity>

    @Query("SELECT task_columns.* FROM task_columns INNER JOIN task_boards ON task_boards.id = task_columns.boardId WHERE task_boards.workspaceId = :ws ORDER BY task_columns.boardId ASC, task_columns.sortOrder ASC")
    fun observeTaskColumns(ws: Long): Flow<List<TaskColumnEntity>>

    @Query("SELECT * FROM task_columns WHERE boardId = :boardId ORDER BY sortOrder ASC")
    suspend fun taskColumnsForBoard(boardId: Long): List<TaskColumnEntity>

    @Query("SELECT * FROM task_columns WHERE id = :id LIMIT 1")
    suspend fun taskColumnById(id: Long): TaskColumnEntity?

    @Query("SELECT * FROM task_columns WHERE boardId = :boardId AND status = :status ORDER BY sortOrder ASC LIMIT 1")
    suspend fun taskColumnForStatus(boardId: Long, status: String): TaskColumnEntity?

    @Query("SELECT * FROM task_columns ORDER BY boardId ASC, sortOrder ASC")
    suspend fun allTaskColumns(): List<TaskColumnEntity>

    @Query("SELECT * FROM tasks WHERE workspaceId = :ws ORDER BY taskBoardId ASC, taskColumnId ASC, sortOrder ASC, updatedAt DESC")
    fun observeTasks(ws: Long): Flow<List<TaskEntity>>

    @Query("SELECT * FROM tasks WHERE id = :id LIMIT 1")
    suspend fun taskById(id: Long): TaskEntity?

    @Query("SELECT * FROM tasks ORDER BY updatedAt DESC")
    suspend fun allTasks(): List<TaskEntity>

    @Query("SELECT COALESCE(MAX(sortOrder), -1) FROM tasks WHERE taskBoardId = :boardId AND taskColumnId = :columnId")
    suspend fun maxTaskSortOrder(boardId: Long, columnId: Long): Int

    @Query("SELECT * FROM tasks WHERE taskBoardId = :boardId AND taskColumnId = :columnId ORDER BY sortOrder ASC, updatedAt DESC")
    suspend fun tasksForColumn(boardId: Long, columnId: Long): List<TaskEntity>

    @Query("SELECT task_property_definitions.* FROM task_property_definitions INNER JOIN task_boards ON task_boards.id = task_property_definitions.boardId WHERE task_boards.workspaceId = :ws ORDER BY task_property_definitions.boardId ASC, task_property_definitions.sortOrder ASC")
    fun observeTaskPropertyDefinitions(ws: Long): Flow<List<TaskPropertyDefinitionEntity>>

    @Query("SELECT * FROM task_property_definitions ORDER BY boardId ASC, sortOrder ASC")
    suspend fun allTaskPropertyDefinitions(): List<TaskPropertyDefinitionEntity>

    @Query("SELECT * FROM task_property_definitions WHERE boardId = :boardId ORDER BY sortOrder ASC")
    suspend fun taskPropertyDefinitionsForBoard(boardId: Long): List<TaskPropertyDefinitionEntity>

    @Query("SELECT * FROM task_property_definitions WHERE id = :id LIMIT 1")
    suspend fun taskPropertyDefinitionById(id: Long): TaskPropertyDefinitionEntity?

    @Query("SELECT task_property_values.* FROM task_property_values INNER JOIN tasks ON tasks.id = task_property_values.taskId WHERE tasks.workspaceId = :ws ORDER BY task_property_values.updatedAt DESC")
    fun observeTaskPropertyValues(ws: Long): Flow<List<TaskPropertyValueEntity>>

    @Query("SELECT * FROM task_property_values ORDER BY updatedAt DESC")
    suspend fun allTaskPropertyValues(): List<TaskPropertyValueEntity>

    @Query("SELECT * FROM task_property_values WHERE taskId = :taskId")
    suspend fun taskPropertyValuesForTask(taskId: Long): List<TaskPropertyValueEntity>

    @Query("SELECT * FROM task_property_values WHERE taskId = :taskId AND propertyId = :propertyId LIMIT 1")
    suspend fun taskPropertyValue(taskId: Long, propertyId: Long): TaskPropertyValueEntity?

    @Query("SELECT task_checklist_items.* FROM task_checklist_items INNER JOIN tasks ON tasks.id = task_checklist_items.taskId WHERE tasks.workspaceId = :ws ORDER BY task_checklist_items.taskId ASC, task_checklist_items.propertyId ASC, task_checklist_items.sortOrder ASC")
    fun observeTaskChecklistItems(ws: Long): Flow<List<TaskChecklistItemEntity>>

    @Query("SELECT * FROM task_checklist_items ORDER BY taskId ASC, propertyId ASC, sortOrder ASC")
    suspend fun allTaskChecklistItems(): List<TaskChecklistItemEntity>

    @Query("SELECT * FROM task_checklist_items WHERE taskId = :taskId AND propertyId = :propertyId ORDER BY sortOrder ASC")
    suspend fun checklistItemsForProperty(taskId: Long, propertyId: Long): List<TaskChecklistItemEntity>

    @Query("SELECT * FROM task_checklist_items WHERE id = :id LIMIT 1")
    suspend fun checklistItemById(id: Long): TaskChecklistItemEntity?

    @Query("SELECT * FROM chat_messages WHERE workspaceId = :ws ORDER BY createdAt ASC")
    fun observeChatMessages(ws: Long): Flow<List<ChatMessageEntity>>

    @Query("SELECT * FROM chat_messages ORDER BY createdAt ASC")
    suspend fun allChatMessages(): List<ChatMessageEntity>

    @Query("SELECT * FROM workspace_objects WHERE workspaceId = :ws AND archived = 0 ORDER BY pinned DESC, updatedAt DESC")
    fun observeWorkspaceObjects(ws: Long): Flow<List<WorkspaceObjectEntity>>

    @Query("SELECT * FROM workspace_objects ORDER BY updatedAt DESC")
    suspend fun allWorkspaceObjects(): List<WorkspaceObjectEntity>

    @Query("SELECT * FROM workspace_object_links WHERE workspaceId = :ws ORDER BY createdAt DESC")
    fun observeWorkspaceObjectLinks(ws: Long): Flow<List<WorkspaceObjectLinkEntity>>

    @Query("SELECT * FROM workspace_object_links ORDER BY createdAt DESC")
    suspend fun allWorkspaceObjectLinks(): List<WorkspaceObjectLinkEntity>

    @Query("SELECT * FROM workspace_activities WHERE workspaceId = :ws ORDER BY createdAt DESC LIMIT :limit")
    fun observeWorkspaceActivities(ws: Long, limit: Int = 80): Flow<List<WorkspaceActivityEntity>>

    @Query("SELECT * FROM workspace_activities ORDER BY createdAt DESC")
    suspend fun allWorkspaceActivities(): List<WorkspaceActivityEntity>

    @Query("SELECT * FROM workspace_object_history WHERE workspaceId = :ws ORDER BY createdAt DESC LIMIT :limit")
    fun observeWorkspaceObjectHistory(ws: Long, limit: Int = 160): Flow<List<WorkspaceObjectHistoryEntity>>

    @Query("SELECT * FROM workspace_object_history ORDER BY createdAt DESC")
    suspend fun allWorkspaceObjectHistory(): List<WorkspaceObjectHistoryEntity>

    @Query("SELECT * FROM workspace_comments WHERE workspaceId = :ws ORDER BY updatedAt DESC")
    fun observeWorkspaceComments(ws: Long): Flow<List<WorkspaceCommentEntity>>

    @Query("SELECT * FROM workspace_comments ORDER BY updatedAt DESC")
    suspend fun allWorkspaceComments(): List<WorkspaceCommentEntity>

    @Query("SELECT * FROM workspace_files WHERE workspaceId = :ws ORDER BY updatedAt DESC")
    fun observeWorkspaceFiles(ws: Long): Flow<List<WorkspaceFileEntity>>

    @Query("SELECT * FROM workspace_files ORDER BY updatedAt DESC")
    suspend fun allWorkspaceFiles(): List<WorkspaceFileEntity>

    @Query("SELECT * FROM goals WHERE workspaceId = :ws ORDER BY updatedAt DESC")
    fun observeGoals(ws: Long): Flow<List<GoalEntity>>

    @Query("SELECT * FROM goals ORDER BY updatedAt DESC")
    suspend fun allGoals(): List<GoalEntity>

    @Query("SELECT * FROM calendar_events WHERE workspaceId = :ws ORDER BY startAt ASC")
    fun observeCalendarEvents(ws: Long): Flow<List<CalendarEventEntity>>

    @Query("SELECT * FROM calendar_events ORDER BY startAt ASC")
    suspend fun allCalendarEvents(): List<CalendarEventEntity>

    @Query("SELECT COUNT(*) FROM sync_outbox WHERE workspaceId = :ws AND state IN ('Pending', 'Failed')")
    fun observePendingSyncCount(ws: Long): Flow<Int>

    @Query("SELECT * FROM sync_outbox WHERE workspaceId = :ws AND state IN ('Pending', 'Failed') AND nextAttemptAt <= :now ORDER BY createdAt ASC LIMIT :limit")
    suspend fun pendingSyncOperations(ws: Long, now: Long, limit: Int): List<SyncOutboxEntity>

    @Query("SELECT * FROM remote_object_bindings WHERE workspaceId = :ws AND objectType = :objectType AND localId = :localId LIMIT 1")
    suspend fun remoteBinding(ws: Long, objectType: String, localId: Long): RemoteObjectBindingEntity?

    @Query("SELECT * FROM sync_tombstones WHERE workspaceId = :ws AND acknowledgedAt IS NULL ORDER BY deletedAt ASC")
    suspend fun pendingTombstones(ws: Long): List<SyncTombstoneEntity>

    @Query("SELECT * FROM settings WHERE id = 1")
    fun observeSettings(): Flow<AppSettingsEntity?>

    @Query("SELECT * FROM settings WHERE id = 1")
    suspend fun settings(): AppSettingsEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSettings(settings: AppSettingsEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertNotebook(entity: NotebookEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTag(entity: TagEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertNote(entity: NoteEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertDocument(entity: DocumentEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertDocumentBlocks(entities: List<DocumentBlockEntity>)

    @Query("DELETE FROM document_blocks WHERE document_id = :documentId AND block_id IN (:ids)")
    suspend fun deleteDocumentBlocks(documentId: String, ids: List<String>)

    @Query("SELECT * FROM document_blocks WHERE document_id = :documentId ORDER BY position ASC")
    suspend fun blocksForDocument(documentId: String): List<DocumentBlockEntity>

    @Transaction
    @Query("SELECT * FROM documents WHERE owner_type = :ownerType AND owner_id = :ownerId LIMIT 1")
    suspend fun documentByOwner(ownerType: String, ownerId: Long): DocumentWithBlocks?

    @Transaction
    @Query("SELECT * FROM documents WHERE owner_type = :ownerType AND owner_id = :ownerId LIMIT 1")
    fun observeDocumentByOwner(ownerType: String, ownerId: Long): Flow<DocumentWithBlocks?>

    @Query("SELECT * FROM documents ORDER BY updated_at DESC")
    suspend fun allDocuments(): List<DocumentEntity>

    @Query("SELECT * FROM document_blocks ORDER BY document_id ASC, position ASC")
    suspend fun allDocumentBlocks(): List<DocumentBlockEntity>

    @Query("DELETE FROM documents WHERE owner_type = :ownerType AND owner_id = :ownerId")
    suspend fun deleteDocumentForOwner(ownerType: String, ownerId: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAttachment(entity: AttachmentEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertNoteEmbed(entity: NoteEmbedEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTaskBoard(entity: TaskBoardEntity): Long

    @Query("UPDATE task_boards SET name = :name, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateTaskBoardName(id: Long, name: String, updatedAt: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTaskColumn(entity: TaskColumnEntity): Long

    @Query("UPDATE task_columns SET name = :name, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateTaskColumnName(id: Long, name: String, updatedAt: Long)

    @Query("UPDATE task_columns SET sortOrder = :sortOrder, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateTaskColumnOrder(id: Long, sortOrder: Int, updatedAt: Long)

    @Query("DELETE FROM task_columns WHERE id = :id")
    suspend fun deleteTaskColumn(id: Long)

    @Query("UPDATE tasks SET colorArgb = :colorArgb, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateTaskColor(id: Long, colorArgb: Long?, updatedAt: Long)

    @Query("UPDATE tasks SET updatedAt = :updatedAt WHERE id = :id")
    suspend fun touchTask(id: Long, updatedAt: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTask(entity: TaskEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTaskPropertyDefinition(entity: TaskPropertyDefinitionEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTaskPropertyValue(entity: TaskPropertyValueEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTaskChecklistItem(entity: TaskChecklistItemEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertChatMessage(entity: ChatMessageEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertWorkspaceObject(entity: WorkspaceObjectEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertWorkspaceObjectLink(entity: WorkspaceObjectLinkEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertWorkspaceActivity(entity: WorkspaceActivityEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertWorkspaceObjectHistory(entity: WorkspaceObjectHistoryEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertWorkspaceComment(entity: WorkspaceCommentEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertWorkspaceFile(entity: WorkspaceFileEntity): Long

    @Query("DELETE FROM workspace_files WHERE id = :id")
    suspend fun deleteWorkspaceFile(id: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertGoal(entity: GoalEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCalendarEvent(entity: CalendarEventEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRemoteBinding(entity: RemoteObjectBindingEntity): Long

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertSyncOutbox(entity: SyncOutboxEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSyncTombstone(entity: SyncTombstoneEntity): Long

    @Query("UPDATE sync_outbox SET state = 'Sending', updatedAt = :now WHERE id = :id AND state IN ('Pending', 'Failed')")
    suspend fun claimSyncOperation(id: Long, now: Long): Int

    @Query("UPDATE sync_outbox SET state = 'Failed', attemptCount = attemptCount + 1, nextAttemptAt = :nextAttemptAt, lastError = :error, updatedAt = :now WHERE id = :id")
    suspend fun failSyncOperation(id: Long, error: String, nextAttemptAt: Long, now: Long)

    @Query("DELETE FROM sync_outbox WHERE id = :id")
    suspend fun deleteSyncOperation(id: Long)

    @Query("UPDATE sync_tombstones SET acknowledgedAt = :acknowledgedAt WHERE workspaceId = :ws AND objectType = :objectType AND objectSyncId = :syncId")
    suspend fun acknowledgeTombstone(ws: Long, objectType: String, syncId: String, acknowledgedAt: Long)

    @Query("UPDATE goals SET title = :title, description = :description, owner = :owner, target = :target, progress = :progress, unit = :unit, dueAt = :dueAt, status = :status, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateGoal(id: Long, title: String, description: String, owner: String, target: Double, progress: Double, unit: String, dueAt: Long?, status: String, updatedAt: Long)

    @Query("UPDATE calendar_events SET title = :title, description = :description, startAt = :startAt, endAt = :endAt, allDay = :allDay, color = :color, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateCalendarEvent(id: Long, title: String, description: String, startAt: Long, endAt: Long, allDay: Boolean, color: Long, updatedAt: Long)

    @Query("DELETE FROM goals WHERE id = :id")
    suspend fun deleteGoal(id: Long)

    @Query("DELETE FROM calendar_events WHERE id = :id")
    suspend fun deleteCalendarEvent(id: Long)

    @Query("UPDATE calendar_events SET updatedAt = :updatedAt WHERE id = :id")
    suspend fun touchCalendarEvent(id: Long, updatedAt: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertNoteTag(ref: NoteTagCrossRef)

    @Update
    suspend fun updateNote(entity: NoteEntity)

    @Query("UPDATE notes SET title = :title, searchText = :searchText, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateNoteContent(id: Long, title: String, searchText: String, updatedAt: Long)

    @Query("UPDATE notes SET updatedAt = :updatedAt WHERE id = :id")
    suspend fun touchNote(id: Long, updatedAt: Long)

    @Query("UPDATE notes SET coverUri = :coverUri, coverMimeType = :coverMimeType, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateNoteCover(id: Long, coverUri: String?, coverMimeType: String?, updatedAt: Long)

    @Query("UPDATE notes SET pinned = :value, updatedAt = :updatedAt WHERE id = :id")
    suspend fun setPinned(id: Long, value: Boolean, updatedAt: Long)

    @Query("UPDATE notes SET starred = :value, updatedAt = :updatedAt WHERE id = :id")
    suspend fun setStarred(id: Long, value: Boolean, updatedAt: Long)

    @Query("UPDATE notes SET archived = :value, updatedAt = :updatedAt WHERE id = :id")
    suspend fun setArchived(id: Long, value: Boolean, updatedAt: Long)

    @Query("UPDATE notes SET locked = :value, updatedAt = :updatedAt WHERE id = :id")
    suspend fun setLocked(id: Long, value: Boolean, updatedAt: Long)

    @Query("DELETE FROM notes WHERE id = :id")
    suspend fun deleteNote(id: Long)

    @Query("DELETE FROM note_tags WHERE noteId = :noteId")
    suspend fun clearTagsForNote(noteId: Long)

    @Query("SELECT * FROM tags WHERE scope = :scope AND normalizedName = :normalizedName LIMIT 1")
    suspend fun tagByScopeAndNormalizedName(scope: String, normalizedName: String): TagEntity?

    @Query("UPDATE tags SET name = :name, normalizedName = :normalizedName WHERE id = :id")
    suspend fun renameTag(id: Long, name: String, normalizedName: String)

    @Query("DELETE FROM tags WHERE id = :id")
    suspend fun deleteTag(id: Long)

    @Query("DELETE FROM notes")
    suspend fun clearNotes()

    @Query("DELETE FROM documents")
    suspend fun clearDocuments()

    @Query("DELETE FROM notebooks")
    suspend fun clearNotebooks()

    @Query("DELETE FROM tags")
    suspend fun clearTags()

    @Query("DELETE FROM attachments")
    suspend fun clearAttachments()

    @Query("DELETE FROM note_embeds")
    suspend fun clearNoteEmbeds()

    @Query("DELETE FROM task_columns")
    suspend fun clearTaskColumns()

    @Query("DELETE FROM task_boards")
    suspend fun clearTaskBoards()

    @Query("UPDATE tasks SET status = :status, updatedAt = :updatedAt WHERE id = :id")
    suspend fun setTaskStatus(id: Long, status: String, updatedAt: Long)

    @Query("UPDATE tasks SET taskBoardId = :boardId, taskColumnId = :columnId, status = :status, sortOrder = :sortOrder, updatedAt = :updatedAt WHERE id = :id")
    suspend fun setTaskColumn(id: Long, boardId: Long, columnId: Long, status: String, sortOrder: Int, updatedAt: Long)

    @Query("UPDATE tasks SET sortOrder = :sortOrder, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateTaskOrder(id: Long, sortOrder: Int, updatedAt: Long)

    @Query("UPDATE tasks SET priority = :priority, dueAt = :dueAt, assignee = :assignee, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateTaskMeta(id: Long, priority: String, dueAt: Long?, assignee: String, updatedAt: Long)

    @Query("UPDATE tasks SET startAt = :startAt, dueAt = :endAt, allDay = :allDay, reminderMinutesBefore = :reminderMinutesBefore, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateTaskDateRange(id: Long, startAt: Long?, endAt: Long?, allDay: Boolean, reminderMinutesBefore: Int?, updatedAt: Long)

    @Query("UPDATE tasks SET title = :title, description = :description, status = :status, priority = :priority, dueAt = :dueAt, assignee = :assignee, labels = :labels, attachmentName = :attachmentName, attachmentMimeType = :attachmentMimeType, attachmentUri = :attachmentUri, attachmentSizeBytes = :attachmentSizeBytes, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateTaskDetails(id: Long, title: String, description: String, status: String, priority: String, dueAt: Long?, assignee: String, labels: String, attachmentName: String?, attachmentMimeType: String?, attachmentUri: String?, attachmentSizeBytes: Long?, updatedAt: Long)

    @Query("UPDATE tasks SET attachmentName = :name, attachmentMimeType = :mimeType, attachmentUri = :uri, attachmentSizeBytes = :sizeBytes, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateTaskAttachment(id: Long, name: String?, mimeType: String?, uri: String?, sizeBytes: Long?, updatedAt: Long)

    @Query("UPDATE task_property_definitions SET name = :name, type = :type, hiddenWhenEmpty = :hiddenWhenEmpty, optionsJson = :optionsJson, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateTaskPropertyDefinition(id: Long, name: String, type: String, hiddenWhenEmpty: Boolean, optionsJson: String, updatedAt: Long)

    @Query("UPDATE task_property_definitions SET sortOrder = :sortOrder, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateTaskPropertyDefinitionOrder(id: Long, sortOrder: Int, updatedAt: Long)

    @Query("DELETE FROM task_property_definitions WHERE id = :id")
    suspend fun deleteTaskPropertyDefinition(id: Long)

    @Query("DELETE FROM task_property_values WHERE propertyId = :propertyId")
    suspend fun deleteTaskPropertyValuesForDefinition(propertyId: Long)

    @Query("DELETE FROM task_checklist_items WHERE propertyId = :propertyId")
    suspend fun deleteTaskChecklistItemsForDefinition(propertyId: Long)

    @Query("UPDATE task_property_values SET valueJson = :valueJson, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateTaskPropertyValue(id: Long, valueJson: String, updatedAt: Long)

    @Query("UPDATE task_checklist_items SET text = :text, checked = :checked, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateTaskChecklistItem(id: Long, text: String, checked: Boolean, updatedAt: Long)

    @Query("UPDATE task_checklist_items SET sortOrder = :sortOrder, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateTaskChecklistItemOrder(id: Long, sortOrder: Int, updatedAt: Long)

    @Query("DELETE FROM task_checklist_items WHERE id = :id")
    suspend fun deleteTaskChecklistItem(id: Long)

    @Query("DELETE FROM tasks WHERE id = :id")
    suspend fun deleteTask(id: Long)

    @Query("DELETE FROM tasks")
    suspend fun clearTasks()

    @Query("DELETE FROM task_property_definitions")
    suspend fun clearTaskPropertyDefinitions()

    @Query("DELETE FROM task_property_values")
    suspend fun clearTaskPropertyValues()

    @Query("DELETE FROM task_checklist_items")
    suspend fun clearTaskChecklistItems()

    @Query("DELETE FROM chat_messages")
    suspend fun clearChatMessages()

    @Query("DELETE FROM workspace_objects")
    suspend fun clearWorkspaceObjects()

    @Query("DELETE FROM workspace_object_links")
    suspend fun clearWorkspaceObjectLinks()

    @Query("DELETE FROM workspace_activities")
    suspend fun clearWorkspaceActivities()

    @Query("DELETE FROM workspace_object_history")
    suspend fun clearWorkspaceObjectHistory()

    @Query("DELETE FROM workspace_comments")
    suspend fun clearWorkspaceComments()

    @Query("DELETE FROM workspace_files")
    suspend fun clearWorkspaceFiles()

    @Query("DELETE FROM calendar_events")
    suspend fun clearCalendarEvents()

    @Query("DELETE FROM goals")
    suspend fun clearGoals()

    @Query("DELETE FROM sync_outbox")
    suspend fun clearSyncOutbox()

    @Query("DELETE FROM sync_tombstones")
    suspend fun clearSyncTombstones()

    @Query("DELETE FROM remote_object_bindings")
    suspend fun clearRemoteBindings()
}
