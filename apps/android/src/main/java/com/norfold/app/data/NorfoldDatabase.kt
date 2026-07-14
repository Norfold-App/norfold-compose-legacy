package com.norfold.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        WorkspaceEntity::class,
        NoteEntity::class,
        NoteBlockEntity::class,
        NotebookEntity::class,
        TagEntity::class,
        AttachmentEntity::class,
        NoteEmbedEntity::class,
        NoteTagCrossRef::class,
        TaskBoardEntity::class,
        TaskColumnEntity::class,
        TaskEntity::class,
        TaskPropertyDefinitionEntity::class,
        TaskPropertyValueEntity::class,
        TaskChecklistItemEntity::class,
        ChatMessageEntity::class,
        CanvasNodeEntity::class,
        CanvasEdgeEntity::class,
        WorkspaceObjectEntity::class,
        WorkspaceObjectLinkEntity::class,
        WorkspaceActivityEntity::class,
        WorkspaceObjectHistoryEntity::class,
        WorkspaceCommentEntity::class,
        WorkspaceFileEntity::class,
        GoalEntity::class,
        CalendarEventEntity::class,
        RemoteObjectBindingEntity::class,
        SyncOutboxEntity::class,
        SyncTombstoneEntity::class,
        AppSettingsEntity::class,
    ],
    version = 29,
    exportSchema = true,
)
abstract class NorfoldDatabase : RoomDatabase() {
    abstract fun dao(): NotesDao

    companion object {
        @Volatile private var instance: NorfoldDatabase? = null

        fun get(context: Context): NorfoldDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, NorfoldDatabase::class.java, "norfold.db")
                .addMigrations(
                    MIGRATION_1_12,
                    MIGRATION_2_12,
                    MIGRATION_3_12,
                    MIGRATION_4_12,
                    MIGRATION_5_12,
                    MIGRATION_6_12,
                    MIGRATION_7_12,
                    MIGRATION_8_12,
                    MIGRATION_9_12,
                    MIGRATION_10_12,
                    MIGRATION_11_12,
                    MIGRATION_12_13,
                    MIGRATION_13_14,
                    MIGRATION_14_16,
                    MIGRATION_15_16,
                    MIGRATION_16_17,
                    MIGRATION_17_18,
                    MIGRATION_18_19,
                    MIGRATION_19_20,
                    MIGRATION_20_21,
                    MIGRATION_21_22,
                    MIGRATION_22_23,
                    MIGRATION_23_24,
                    MIGRATION_24_25,
                    MIGRATION_25_26,
                    MIGRATION_26_27,
                    MIGRATION_27_28,
                    MIGRATION_28_29,
                )
                .build()
                .also { instance = it }
        }

        private val MIGRATION_1_12 = schemaMigration(1, 12)
        private val MIGRATION_2_12 = schemaMigration(2, 12)
        private val MIGRATION_3_12 = schemaMigration(3, 12)
        private val MIGRATION_4_12 = schemaMigration(4, 12)
        private val MIGRATION_5_12 = schemaMigration(5, 12)
        private val MIGRATION_6_12 = schemaMigration(6, 12)
        private val MIGRATION_7_12 = schemaMigration(7, 12)
        private val MIGRATION_8_12 = schemaMigration(8, 12)
        private val MIGRATION_9_12 = schemaMigration(9, 12)
        private val MIGRATION_10_12 = schemaMigration(10, 12)
        private val MIGRATION_11_12 = schemaMigration(11, 12)
        private val MIGRATION_12_13 = schemaMigration(12, 13)
        private val MIGRATION_13_14 = schemaMigration(13, 14)
        private val MIGRATION_14_16 = schemaMigration(14, 16)
        private val MIGRATION_15_16 = schemaMigration(15, 16)
        private val MIGRATION_16_17 = schemaMigration(16, 17)
        private val MIGRATION_17_18 = schemaMigration(17, 18)
        private val MIGRATION_18_19 = schemaMigration(18, 19)
        private val MIGRATION_19_20 = schemaMigration(19, 20)
        private val MIGRATION_20_21 = schemaMigration(20, 21)
        private val MIGRATION_21_22 = schemaMigration(21, 22)
        private val MIGRATION_22_23 = schemaMigration(22, 23)
        // Removes the Projects feature: drops the projects table and rebuilds
        // goals/calendar_events without their projectId column so the on-disk
        // schema matches the entities (Room validates columns strictly).
        private val MIGRATION_23_24 = object : Migration(23, 24) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DROP TABLE IF EXISTS projects")
                db.execSQL("DROP INDEX IF EXISTS index_goals_projectId")
                db.execSQL("DROP INDEX IF EXISTS index_calendar_events_projectId")

                db.execSQL(
                    """
                    CREATE TABLE goals_new (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        workspaceId INTEGER NOT NULL DEFAULT 1,
                        syncId TEXT NOT NULL,
                        title TEXT NOT NULL,
                        description TEXT NOT NULL DEFAULT '',
                        owner TEXT NOT NULL DEFAULT '',
                        target REAL NOT NULL DEFAULT 100.0,
                        progress REAL NOT NULL DEFAULT 0.0,
                        unit TEXT NOT NULL DEFAULT '%',
                        dueAt INTEGER,
                        status TEXT NOT NULL DEFAULT 'NotStarted',
                        createdAt INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    INSERT INTO goals_new (id, workspaceId, syncId, title, description, owner, target, progress, unit, dueAt, status, createdAt, updatedAt)
                    SELECT id, workspaceId, syncId, title, description, owner, target, progress, unit, dueAt, status, createdAt, updatedAt FROM goals
                    """.trimIndent(),
                )
                db.execSQL("DROP TABLE goals")
                db.execSQL("ALTER TABLE goals_new RENAME TO goals")

                db.execSQL(
                    """
                    CREATE TABLE calendar_events_new (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        workspaceId INTEGER NOT NULL DEFAULT 1,
                        syncId TEXT NOT NULL,
                        title TEXT NOT NULL,
                        description TEXT NOT NULL DEFAULT '',
                        startAt INTEGER NOT NULL,
                        endAt INTEGER NOT NULL,
                        allDay INTEGER NOT NULL DEFAULT 0,
                        color INTEGER NOT NULL DEFAULT 4285482751,
                        source TEXT NOT NULL DEFAULT 'Local',
                        externalId TEXT,
                        createdAt INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    INSERT INTO calendar_events_new (id, workspaceId, syncId, title, description, startAt, endAt, allDay, color, source, externalId, createdAt, updatedAt)
                    SELECT id, workspaceId, syncId, title, description, startAt, endAt, allDay, color, source, externalId, createdAt, updatedAt FROM calendar_events
                    """.trimIndent(),
                )
                db.execSQL("DROP TABLE calendar_events")
                db.execSQL("ALTER TABLE calendar_events_new RENAME TO calendar_events")

                db.ensureCurrentSchema()
            }
        }

        private val MIGRATION_24_25 = object : Migration(24, 25) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE tasks ADD COLUMN startAt INTEGER")
                db.execSQL("ALTER TABLE tasks ADD COLUMN allDay INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE tasks ADD COLUMN reminderMinutesBefore INTEGER")
                db.execSQL("UPDATE tasks SET startAt = dueAt WHERE dueAt IS NOT NULL")
            }
        }

        internal val MIGRATION_25_26 = object : Migration(25, 26) {
            override fun migrate(db: SupportSQLiteDatabase) {
                val legacyNotes = buildList {
                    db.query("SELECT id, bodyMarkdown FROM notes").use { cursor ->
                        val idIndex = cursor.getColumnIndexOrThrow("id")
                        val bodyIndex = cursor.getColumnIndexOrThrow("bodyMarkdown")
                        while (cursor.moveToNext()) add(cursor.getLong(idIndex) to cursor.getString(bodyIndex).orEmpty())
                    }
                }
                db.execSQL("ALTER TABLE notes RENAME COLUMN bodyMarkdown TO searchText")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS note_blocks (
                        id TEXT NOT NULL PRIMARY KEY,
                        noteId INTEGER NOT NULL,
                        position INTEGER NOT NULL,
                        payloadJson TEXT NOT NULL,
                        updatedAt INTEGER NOT NULL,
                        FOREIGN KEY(noteId) REFERENCES notes(id) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_note_blocks_noteId ON note_blocks(noteId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_note_blocks_noteId_position ON note_blocks(noteId, position)")
                val now = System.currentTimeMillis()
                legacyNotes.forEach { (noteId, markdown) ->
                    val document = com.norfold.app.domain.MarkdownBlockCodec.import(markdown)
                    db.execSQL("UPDATE notes SET searchText = ? WHERE id = ?", arrayOf<Any?>(document.plainText(), noteId))
                    document.blocks.forEachIndexed { position, block ->
                        db.execSQL(
                            "INSERT INTO note_blocks(id, noteId, position, payloadJson, updatedAt) VALUES(?, ?, ?, ?, ?)",
                            arrayOf<Any?>(block.id, noteId, position, com.norfold.app.domain.BlockDocumentJson.encodeBlock(block), now),
                        )
                    }
                }
            }
        }

        internal val MIGRATION_26_27 = object : Migration(26, 27) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE settings ADD COLUMN contextualMenuStyle TEXT NOT NULL DEFAULT 'Pill'")
                db.execSQL("ALTER TABLE settings ADD COLUMN contextualMenuColor TEXT NOT NULL DEFAULT 'FollowTheme'")
            }
        }

        internal val MIGRATION_27_28 = object : Migration(27, 28) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE tags ADD COLUMN scope TEXT NOT NULL DEFAULT 'notes'")
                db.execSQL("ALTER TABLE tags ADD COLUMN normalizedName TEXT NOT NULL DEFAULT ''")

                // Development builds briefly stored board scope in the tag name. Preserve those tags.
                db.execSQL(
                    """
                    UPDATE tags
                    SET scope = 'board:' || substr(name, 6, instr(substr(name, 6), ':') - 1),
                        normalizedName = lower(ltrim(trim(substr(name, 6 + instr(substr(name, 6), ':'))), '#')),
                        name = ltrim(trim(substr(name, 6 + instr(substr(name, 6), ':'))), '#')
                    WHERE name LIKE 'task:%:%' AND instr(substr(name, 6), ':') > 0
                    """.trimIndent(),
                )
                db.execSQL(
                    "UPDATE tags SET normalizedName = lower(ltrim(trim(name), '#')) WHERE normalizedName = ''",
                )

                // Merge old case-only duplicates before enforcing the scoped unique key.
                db.execSQL(
                    """
                    UPDATE OR IGNORE note_tags
                    SET tagId = (
                        SELECT MIN(canonical.id) FROM tags canonical
                        WHERE canonical.scope = (SELECT source.scope FROM tags source WHERE source.id = note_tags.tagId)
                          AND canonical.normalizedName = (SELECT source.normalizedName FROM tags source WHERE source.id = note_tags.tagId)
                    )
                    """.trimIndent(),
                )
                db.execSQL("DELETE FROM tags WHERE id NOT IN (SELECT MIN(id) FROM tags GROUP BY scope, normalizedName)")
                db.execSQL("DROP INDEX IF EXISTS index_tags_name")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_tags_scope_normalizedName ON tags(scope, normalizedName)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_tags_scope ON tags(scope)")
            }
        }

        internal val MIGRATION_28_29 = object : Migration(28, 29) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE notes ADD COLUMN overlapMode TEXT NOT NULL DEFAULT 'reflow'")
                db.execSQL("ALTER TABLE notes ADD COLUMN freeformLayoutJson TEXT")
            }
        }

        private fun schemaMigration(from: Int, to: Int) = object : Migration(from, to) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.ensureCurrentSchema()
            }
        }

        private fun SupportSQLiteDatabase.ensureCurrentSchema() {
            execSQL(
                """
                CREATE TABLE IF NOT EXISTS workspaces (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    name TEXT NOT NULL,
                    icon TEXT NOT NULL DEFAULT 'N',
                    iconKind TEXT NOT NULL DEFAULT 'Text',
                    iconUri TEXT,
                    backgroundUri TEXT,
                    palette TEXT NOT NULL DEFAULT 'Neon',
                    createdAt INTEGER NOT NULL
                )
                """.trimIndent(),
            )
            execSQL(
                """
                CREATE TABLE IF NOT EXISTS notebooks (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    name TEXT NOT NULL,
                    parentId INTEGER,
                    color INTEGER NOT NULL,
                    sortOrder INTEGER NOT NULL,
                    workspaceId INTEGER NOT NULL DEFAULT 1
                )
                """.trimIndent(),
            )
            execSQL(
                """
                CREATE TABLE IF NOT EXISTS tags (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    name TEXT NOT NULL,
                    color INTEGER NOT NULL
                )
                """.trimIndent(),
            )
            execSQL(
                """
                CREATE TABLE IF NOT EXISTS notes (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    title TEXT NOT NULL,
                    bodyMarkdown TEXT NOT NULL,
                    notebookId INTEGER,
                    coverUri TEXT,
                    coverMimeType TEXT,
                    pinned INTEGER NOT NULL,
                    starred INTEGER NOT NULL,
                    archived INTEGER NOT NULL,
                    locked INTEGER NOT NULL,
                    createdAt INTEGER NOT NULL,
                    updatedAt INTEGER NOT NULL,
                    workspaceId INTEGER NOT NULL DEFAULT 1,
                    FOREIGN KEY(notebookId) REFERENCES notebooks(id) ON UPDATE NO ACTION ON DELETE SET NULL
                )
                """.trimIndent(),
            )
            execSQL(
                """
                CREATE TABLE IF NOT EXISTS attachments (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    noteId INTEGER NOT NULL,
                    displayName TEXT NOT NULL,
                    mimeType TEXT NOT NULL,
                    uri TEXT NOT NULL,
                    sizeBytes INTEGER NOT NULL,
                    FOREIGN KEY(noteId) REFERENCES notes(id) ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """.trimIndent(),
            )
            execSQL(
                """
                CREATE TABLE IF NOT EXISTS note_embeds (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    noteId INTEGER NOT NULL,
                    type TEXT NOT NULL,
                    title TEXT NOT NULL,
                    target TEXT NOT NULL,
                    preview TEXT NOT NULL DEFAULT '',
                    createdAt INTEGER NOT NULL,
                    FOREIGN KEY(noteId) REFERENCES notes(id) ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """.trimIndent(),
            )
            execSQL(
                """
                CREATE TABLE IF NOT EXISTS note_tags (
                    noteId INTEGER NOT NULL,
                    tagId INTEGER NOT NULL,
                    PRIMARY KEY(noteId, tagId),
                    FOREIGN KEY(noteId) REFERENCES notes(id) ON UPDATE NO ACTION ON DELETE CASCADE,
                    FOREIGN KEY(tagId) REFERENCES tags(id) ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """.trimIndent(),
            )
            execSQL(
                """
                CREATE TABLE IF NOT EXISTS task_boards (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    name TEXT NOT NULL,
                    workspaceId INTEGER NOT NULL DEFAULT 1,
                    createdAt INTEGER NOT NULL,
                    updatedAt INTEGER NOT NULL
                )
                """.trimIndent(),
            )
            execSQL(
                """
                CREATE TABLE IF NOT EXISTS task_columns (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    boardId INTEGER NOT NULL,
                    name TEXT NOT NULL,
                    status TEXT,
                    color INTEGER NOT NULL,
                    sortOrder INTEGER NOT NULL,
                    createdAt INTEGER NOT NULL,
                    updatedAt INTEGER NOT NULL
                )
                """.trimIndent(),
            )
            execSQL(
                """
                CREATE TABLE IF NOT EXISTS tasks (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    title TEXT NOT NULL,
                    description TEXT NOT NULL DEFAULT '',
                    assignee TEXT NOT NULL DEFAULT '',
                    status TEXT NOT NULL DEFAULT 'Todo',
                    priority TEXT NOT NULL DEFAULT 'Normal',
                    dueAt INTEGER,
                    labels TEXT NOT NULL DEFAULT '',
                    attachmentName TEXT,
                    attachmentMimeType TEXT,
                    attachmentUri TEXT,
                    attachmentSizeBytes INTEGER,
                    createdAt INTEGER NOT NULL,
                    updatedAt INTEGER NOT NULL,
                    workspaceId INTEGER NOT NULL DEFAULT 1,
                    taskBoardId INTEGER NOT NULL DEFAULT 1,
                    taskColumnId INTEGER,
                    sortOrder INTEGER NOT NULL DEFAULT 0,
                    colorArgb INTEGER
                )
                """.trimIndent(),
            )
            execSQL(
                """
                CREATE TABLE IF NOT EXISTS task_property_definitions (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    boardId INTEGER NOT NULL,
                    name TEXT NOT NULL,
                    type TEXT NOT NULL,
                    sortOrder INTEGER NOT NULL,
                    hiddenWhenEmpty INTEGER NOT NULL DEFAULT 0,
                    optionsJson TEXT NOT NULL DEFAULT '',
                    createdAt INTEGER NOT NULL,
                    updatedAt INTEGER NOT NULL
                )
                """.trimIndent(),
            )
            execSQL(
                """
                CREATE TABLE IF NOT EXISTS task_property_values (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    taskId INTEGER NOT NULL,
                    propertyId INTEGER NOT NULL,
                    valueJson TEXT NOT NULL DEFAULT '',
                    updatedAt INTEGER NOT NULL
                )
                """.trimIndent(),
            )
            execSQL(
                """
                CREATE TABLE IF NOT EXISTS task_checklist_items (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    taskId INTEGER NOT NULL,
                    propertyId INTEGER NOT NULL,
                    text TEXT NOT NULL,
                    checked INTEGER NOT NULL DEFAULT 0,
                    sortOrder INTEGER NOT NULL,
                    createdAt INTEGER NOT NULL,
                    updatedAt INTEGER NOT NULL
                )
                """.trimIndent(),
            )
            execSQL(
                """
                CREATE TABLE IF NOT EXISTS chat_messages (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    authorUsername TEXT NOT NULL,
                    authorDisplayName TEXT NOT NULL,
                    body TEXT NOT NULL,
                    color INTEGER NOT NULL,
                    createdAt INTEGER NOT NULL,
                    system INTEGER NOT NULL DEFAULT 0,
                    attachmentName TEXT,
                    attachmentMimeType TEXT,
                    attachmentUri TEXT,
                    attachmentSizeBytes INTEGER,
                    workspaceId INTEGER NOT NULL DEFAULT 1
                )
                """.trimIndent(),
            )
            execSQL(
                """
                CREATE TABLE IF NOT EXISTS canvas_nodes (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    title TEXT NOT NULL,
                    subtitle TEXT NOT NULL,
                    type TEXT NOT NULL,
                    x REAL NOT NULL,
                    y REAL NOT NULL,
                    color INTEGER NOT NULL,
                    linkedNoteId INTEGER,
                    targetUri TEXT,
                    targetMimeType TEXT,
                    targetName TEXT,
                    targetSizeBytes INTEGER,
                    createdAt INTEGER NOT NULL,
                    updatedAt INTEGER NOT NULL,
                    workspaceId INTEGER NOT NULL DEFAULT 1
                )
                """.trimIndent(),
            )
            execSQL(
                """
                CREATE TABLE IF NOT EXISTS canvas_edges (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    fromNodeId INTEGER NOT NULL,
                    toNodeId INTEGER NOT NULL,
                    label TEXT NOT NULL DEFAULT '',
                    color INTEGER NOT NULL,
                    createdAt INTEGER NOT NULL,
                    updatedAt INTEGER NOT NULL,
                    workspaceId INTEGER NOT NULL DEFAULT 1
                )
                """.trimIndent(),
            )
            execSQL(
                """
                CREATE TABLE IF NOT EXISTS workspace_objects (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    objectType TEXT NOT NULL,
                    sourceId INTEGER,
                    title TEXT NOT NULL,
                    summary TEXT NOT NULL DEFAULT '',
                    tags TEXT NOT NULL DEFAULT '',
                    icon TEXT NOT NULL DEFAULT '',
                    color INTEGER NOT NULL DEFAULT 4287327478,
                    pinned INTEGER NOT NULL DEFAULT 0,
                    archived INTEGER NOT NULL DEFAULT 0,
                    createdAt INTEGER NOT NULL,
                    updatedAt INTEGER NOT NULL,
                    workspaceId INTEGER NOT NULL DEFAULT 1
                )
                """.trimIndent(),
            )
            execSQL(
                """
                CREATE TABLE IF NOT EXISTS workspace_object_links (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    fromObjectId INTEGER NOT NULL,
                    toObjectId INTEGER NOT NULL,
                    linkType TEXT NOT NULL DEFAULT 'Reference',
                    label TEXT NOT NULL DEFAULT '',
                    createdAt INTEGER NOT NULL,
                    workspaceId INTEGER NOT NULL DEFAULT 1
                )
                """.trimIndent(),
            )
            execSQL(
                """
                CREATE TABLE IF NOT EXISTS workspace_activities (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    objectId INTEGER,
                    activityType TEXT NOT NULL,
                    actor TEXT NOT NULL,
                    title TEXT NOT NULL,
                    detail TEXT NOT NULL DEFAULT '',
                    createdAt INTEGER NOT NULL,
                    workspaceId INTEGER NOT NULL DEFAULT 1
                )
                """.trimIndent(),
            )
            execSQL(
                """
                CREATE TABLE IF NOT EXISTS workspace_object_history (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    objectId INTEGER,
                    historyType TEXT NOT NULL,
                    actor TEXT NOT NULL,
                    summary TEXT NOT NULL,
                    beforeValue TEXT NOT NULL DEFAULT '',
                    afterValue TEXT NOT NULL DEFAULT '',
                    createdAt INTEGER NOT NULL,
                    workspaceId INTEGER NOT NULL DEFAULT 1
                )
                """.trimIndent(),
            )
            execSQL(
                """
                CREATE TABLE IF NOT EXISTS workspace_comments (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    objectId INTEGER NOT NULL,
                    authorUsername TEXT NOT NULL,
                    authorDisplayName TEXT NOT NULL,
                    body TEXT NOT NULL,
                    resolved INTEGER NOT NULL DEFAULT 0,
                    createdAt INTEGER NOT NULL,
                    updatedAt INTEGER NOT NULL,
                    workspaceId INTEGER NOT NULL DEFAULT 1
                )
                """.trimIndent(),
            )
            execSQL(
                """
                CREATE TABLE IF NOT EXISTS workspace_files (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    objectId INTEGER,
                    displayName TEXT NOT NULL,
                    mimeType TEXT NOT NULL,
                    uri TEXT NOT NULL,
                    sizeBytes INTEGER NOT NULL,
                    createdAt INTEGER NOT NULL,
                    updatedAt INTEGER NOT NULL,
                    workspaceId INTEGER NOT NULL DEFAULT 1
                )
                """.trimIndent(),
            )
            execSQL(
                """
                CREATE TABLE IF NOT EXISTS goals (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    workspaceId INTEGER NOT NULL DEFAULT 1,
                    syncId TEXT NOT NULL,
                    title TEXT NOT NULL,
                    description TEXT NOT NULL DEFAULT '',
                    owner TEXT NOT NULL DEFAULT '',
                    target REAL NOT NULL DEFAULT 100.0,
                    progress REAL NOT NULL DEFAULT 0.0,
                    unit TEXT NOT NULL DEFAULT '%',
                    dueAt INTEGER,
                    status TEXT NOT NULL DEFAULT 'NotStarted',
                    createdAt INTEGER NOT NULL,
                    updatedAt INTEGER NOT NULL
                )
                """.trimIndent(),
            )
            execSQL(
                """
                CREATE TABLE IF NOT EXISTS calendar_events (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    workspaceId INTEGER NOT NULL DEFAULT 1,
                    syncId TEXT NOT NULL,
                    title TEXT NOT NULL,
                    description TEXT NOT NULL DEFAULT '',
                    startAt INTEGER NOT NULL,
                    endAt INTEGER NOT NULL,
                    allDay INTEGER NOT NULL DEFAULT 0,
                    color INTEGER NOT NULL DEFAULT 4285482751,
                    source TEXT NOT NULL DEFAULT 'Local',
                    externalId TEXT,
                    createdAt INTEGER NOT NULL,
                    updatedAt INTEGER NOT NULL
                )
                """.trimIndent(),
            )
            execSQL(
                """
                CREATE TABLE IF NOT EXISTS remote_object_bindings (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    workspaceId INTEGER NOT NULL,
                    objectType TEXT NOT NULL,
                    localId INTEGER NOT NULL,
                    syncId TEXT NOT NULL,
                    remoteId TEXT,
                    remoteVersion INTEGER NOT NULL DEFAULT 0,
                    contentHash TEXT NOT NULL DEFAULT '',
                    updatedAt INTEGER NOT NULL
                )
                """.trimIndent(),
            )
            execSQL(
                """
                CREATE TABLE IF NOT EXISTS sync_outbox (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    workspaceId INTEGER NOT NULL,
                    operationId TEXT NOT NULL,
                    objectType TEXT NOT NULL,
                    objectSyncId TEXT NOT NULL,
                    operation TEXT NOT NULL,
                    payload TEXT NOT NULL,
                    contentHash TEXT NOT NULL,
                    baseVersion INTEGER,
                    attemptCount INTEGER NOT NULL DEFAULT 0,
                    nextAttemptAt INTEGER NOT NULL,
                    state TEXT NOT NULL DEFAULT 'Pending',
                    lastError TEXT,
                    createdAt INTEGER NOT NULL,
                    updatedAt INTEGER NOT NULL
                )
                """.trimIndent(),
            )
            execSQL(
                """
                CREATE TABLE IF NOT EXISTS sync_tombstones (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    workspaceId INTEGER NOT NULL,
                    objectType TEXT NOT NULL,
                    objectSyncId TEXT NOT NULL,
                    deletedAt INTEGER NOT NULL,
                    acknowledgedAt INTEGER
                )
                """.trimIndent(),
            )
            execSQL(
                """
                CREATE TABLE IF NOT EXISTS settings (
                    id INTEGER NOT NULL,
                    themeMode TEXT NOT NULL DEFAULT 'System',
                    themeProfile TEXT NOT NULL DEFAULT 'Graphite',
                    accentColor INTEGER NOT NULL DEFAULT 4281546570,
                    activeWorkspaceId INTEGER NOT NULL DEFAULT 1,
                    backupFolderUri TEXT,
                    vaultLockEnabled INTEGER NOT NULL DEFAULT 0,
                    vaultSecretHash TEXT,
                    syncProvider TEXT NOT NULL DEFAULT 'None',
                    syncFolderUri TEXT,
                    syncChainId TEXT,
                    syncDeviceName TEXT NOT NULL DEFAULT 'Android device',
                    syncUserName TEXT NOT NULL DEFAULT '',
                    syncPublicName TEXT NOT NULL DEFAULT '',
                    lastSyncAt INTEGER,
                    lastSyncHash TEXT,
                    lastSyncStatus TEXT NOT NULL DEFAULT 'Sync chain not configured',
                    syncConflictCount INTEGER NOT NULL DEFAULT 0,
                    profileBackgroundUri TEXT,
                    profileImageUri TEXT,
                    workspaceName TEXT NOT NULL DEFAULT 'My Workspace',
                    workspaceIcon TEXT NOT NULL DEFAULT 'Z',
                    workspaceIconKind TEXT NOT NULL DEFAULT 'Text',
                    workspaceIconUri TEXT,
                    workspaceBackgroundUri TEXT,
                    adminsControlWorkspaceVisuals INTEGER NOT NULL DEFAULT 1,
                    allowMembersCreateNotes INTEGER NOT NULL DEFAULT 1,
                    allowMembersInvite INTEGER NOT NULL DEFAULT 0,
                    uiScale REAL NOT NULL DEFAULT 0.88,
                    editorLineWidth TEXT NOT NULL DEFAULT 'Comfortable',
                    editorFontFamily TEXT NOT NULL DEFAULT 'Sans',
                    showMarkdownSyntax INTEGER NOT NULL DEFAULT 1,
                    noteLongPressAction TEXT NOT NULL DEFAULT 'Actions',
                    noteSwipeStartAction TEXT NOT NULL DEFAULT 'Pin',
                    noteSwipeEndAction TEXT NOT NULL DEFAULT 'Archive',
                    blockScreenshots INTEGER NOT NULL DEFAULT 0,
                    requireBiometricOnOpen INTEGER NOT NULL DEFAULT 0,
                    reduceMotion INTEGER NOT NULL DEFAULT 0,
                    PRIMARY KEY(id)
                )
                """.trimIndent(),
            )

            addColumnIfMissing("workspaces", "icon", "TEXT NOT NULL DEFAULT 'N'")
            addColumnIfMissing("workspaces", "iconKind", "TEXT NOT NULL DEFAULT 'Text'")
            addColumnIfMissing("workspaces", "iconUri", "TEXT")
            addColumnIfMissing("workspaces", "backgroundUri", "TEXT")
            addColumnIfMissing("workspaces", "palette", "TEXT NOT NULL DEFAULT 'Neon'")
            addColumnIfMissing("notebooks", "workspaceId", "INTEGER NOT NULL DEFAULT 1")
            addColumnIfMissing("notes", "coverUri", "TEXT")
            addColumnIfMissing("notes", "coverMimeType", "TEXT")
            addColumnIfMissing("notes", "workspaceId", "INTEGER NOT NULL DEFAULT 1")
            addColumnIfMissing("notes", "overlapMode", "TEXT NOT NULL DEFAULT 'reflow'")
            addColumnIfMissing("notes", "freeformLayoutJson", "TEXT")
            addColumnIfMissing("tasks", "description", "TEXT NOT NULL DEFAULT ''")
            addColumnIfMissing("tasks", "assignee", "TEXT NOT NULL DEFAULT ''")
            addColumnIfMissing("tasks", "status", "TEXT NOT NULL DEFAULT 'Todo'")
            addColumnIfMissing("tasks", "priority", "TEXT NOT NULL DEFAULT 'Normal'")
            addColumnIfMissing("tasks", "dueAt", "INTEGER")
            addColumnIfMissing("tasks", "labels", "TEXT NOT NULL DEFAULT ''")
            addColumnIfMissing("tasks", "attachmentName", "TEXT")
            addColumnIfMissing("tasks", "attachmentMimeType", "TEXT")
            addColumnIfMissing("tasks", "attachmentUri", "TEXT")
            addColumnIfMissing("tasks", "attachmentSizeBytes", "INTEGER")
            addColumnIfMissing("tasks", "workspaceId", "INTEGER NOT NULL DEFAULT 1")
            addColumnIfMissing("tasks", "taskBoardId", "INTEGER NOT NULL DEFAULT 1")
            addColumnIfMissing("tasks", "taskColumnId", "INTEGER")
            addColumnIfMissing("tasks", "sortOrder", "INTEGER NOT NULL DEFAULT 0")
            addColumnIfMissing("tasks", "colorArgb", "INTEGER")
            addColumnIfMissing("chat_messages", "system", "INTEGER NOT NULL DEFAULT 0")
            addColumnIfMissing("chat_messages", "attachmentName", "TEXT")
            addColumnIfMissing("chat_messages", "attachmentMimeType", "TEXT")
            addColumnIfMissing("chat_messages", "attachmentUri", "TEXT")
            addColumnIfMissing("chat_messages", "attachmentSizeBytes", "INTEGER")
            addColumnIfMissing("chat_messages", "workspaceId", "INTEGER NOT NULL DEFAULT 1")
            addColumnIfMissing("canvas_nodes", "linkedNoteId", "INTEGER")
            addColumnIfMissing("canvas_nodes", "targetUri", "TEXT")
            addColumnIfMissing("canvas_nodes", "targetMimeType", "TEXT")
            addColumnIfMissing("canvas_nodes", "targetName", "TEXT")
            addColumnIfMissing("canvas_nodes", "targetSizeBytes", "INTEGER")
            addColumnIfMissing("canvas_nodes", "workspaceId", "INTEGER NOT NULL DEFAULT 1")
            addColumnIfMissing("settings", "themeProfile", "TEXT NOT NULL DEFAULT 'Neon'")
            addColumnIfMissing("settings", "activeWorkspaceId", "INTEGER NOT NULL DEFAULT 1")
            addColumnIfMissing("settings", "syncProvider", "TEXT NOT NULL DEFAULT 'None'")
            addColumnIfMissing("settings", "syncFolderUri", "TEXT")
            addColumnIfMissing("settings", "syncChainId", "TEXT")
            addColumnIfMissing("settings", "syncDeviceName", "TEXT NOT NULL DEFAULT 'Android device'")
            addColumnIfMissing("settings", "syncUserName", "TEXT NOT NULL DEFAULT ''")
            addColumnIfMissing("settings", "syncPublicName", "TEXT NOT NULL DEFAULT ''")
            addColumnIfMissing("settings", "lastSyncAt", "INTEGER")
            addColumnIfMissing("settings", "lastSyncHash", "TEXT")
            addColumnIfMissing("settings", "lastSyncStatus", "TEXT NOT NULL DEFAULT 'Sync chain not configured'")
            addColumnIfMissing("settings", "syncConflictCount", "INTEGER NOT NULL DEFAULT 0")
            addColumnIfMissing("settings", "profileBackgroundUri", "TEXT")
            addColumnIfMissing("settings", "profileImageUri", "TEXT")
            addColumnIfMissing("settings", "workspaceName", "TEXT NOT NULL DEFAULT 'My Workspace'")
            addColumnIfMissing("settings", "workspaceIcon", "TEXT NOT NULL DEFAULT 'Z'")
            addColumnIfMissing("settings", "workspaceIconKind", "TEXT NOT NULL DEFAULT 'Text'")
            addColumnIfMissing("settings", "workspaceIconUri", "TEXT")
            addColumnIfMissing("settings", "workspaceBackgroundUri", "TEXT")
            addColumnIfMissing("settings", "adminsControlWorkspaceVisuals", "INTEGER NOT NULL DEFAULT 1")
            addColumnIfMissing("settings", "allowMembersCreateNotes", "INTEGER NOT NULL DEFAULT 1")
            addColumnIfMissing("settings", "allowMembersInvite", "INTEGER NOT NULL DEFAULT 0")
            addColumnIfMissing("settings", "uiScale", "REAL NOT NULL DEFAULT 0.88")
            addColumnIfMissing("settings", "editorLineWidth", "TEXT NOT NULL DEFAULT 'Comfortable'")
            addColumnIfMissing("settings", "editorFontFamily", "TEXT NOT NULL DEFAULT 'Sans'")
            addColumnIfMissing("settings", "showMarkdownSyntax", "INTEGER NOT NULL DEFAULT 1")
            addColumnIfMissing("settings", "noteLongPressAction", "TEXT NOT NULL DEFAULT 'Actions'")
            addColumnIfMissing("settings", "noteSwipeStartAction", "TEXT NOT NULL DEFAULT 'Pin'")
            addColumnIfMissing("settings", "noteSwipeEndAction", "TEXT NOT NULL DEFAULT 'Archive'")
            addColumnIfMissing("settings", "blockScreenshots", "INTEGER NOT NULL DEFAULT 0")
            addColumnIfMissing("settings", "requireBiometricOnOpen", "INTEGER NOT NULL DEFAULT 0")
            addColumnIfMissing("settings", "reduceMotion", "INTEGER NOT NULL DEFAULT 0")
            addColumnIfMissing("settings", "taskViewMode", "TEXT NOT NULL DEFAULT 'Board'")
            addColumnIfMissing("settings", "taskSelectedBoardId", "INTEGER NOT NULL DEFAULT 1")
            addColumnIfMissing("settings", "taskSortMode", "TEXT NOT NULL DEFAULT 'Manual'")
            addColumnIfMissing("settings", "taskCompactLayout", "INTEGER NOT NULL DEFAULT 1")
            addColumnIfMissing("settings", "taskKanbanEngine", "TEXT NOT NULL DEFAULT 'BoardPointer'")
            addColumnIfMissing("settings", "onboardingComplete", "INTEGER NOT NULL DEFAULT 0")
            addColumnIfMissing("settings", "workspacePurpose", "TEXT NOT NULL DEFAULT 'Personal'")
            addColumnIfMissing("settings", "calendarDefaultView", "TEXT NOT NULL DEFAULT 'Month'")
            addColumnIfMissing("settings", "quietHoursEnabled", "INTEGER NOT NULL DEFAULT 0")
            addColumnIfMissing("settings", "quietHoursStart", "TEXT NOT NULL DEFAULT '22:00'")
            addColumnIfMissing("settings", "quietHoursEnd", "TEXT NOT NULL DEFAULT '07:00'")
            addColumnIfMissing("settings", "notificationInApp", "INTEGER NOT NULL DEFAULT 1")
            addColumnIfMissing("settings", "notificationEmail", "INTEGER NOT NULL DEFAULT 0")
            addColumnIfMissing("settings", "notificationPush", "INTEGER NOT NULL DEFAULT 1")
            addColumnIfMissing("settings", "subscriptionTier", "TEXT NOT NULL DEFAULT 'Free'")

            val now = System.currentTimeMillis()
            execSQL("INSERT OR IGNORE INTO task_boards (id, name, workspaceId, createdAt, updatedAt) VALUES (1, 'Default board', 1, $now, $now)")
            execSQL("INSERT OR IGNORE INTO task_columns (id, boardId, name, status, color, sortOrder, createdAt, updatedAt) VALUES (1, 1, 'To do', 'Todo', 10320895, 0, $now, $now)")
            execSQL("INSERT OR IGNORE INTO task_columns (id, boardId, name, status, color, sortOrder, createdAt, updatedAt) VALUES (2, 1, 'Doing', 'Doing', 5229822, 1, $now, $now)")
            execSQL("INSERT OR IGNORE INTO task_columns (id, boardId, name, status, color, sortOrder, createdAt, updatedAt) VALUES (3, 1, 'Done', 'Done', 5688472, 2, $now, $now)")
            execSQL("UPDATE tasks SET taskBoardId = 1 WHERE taskBoardId IS NULL OR taskBoardId = 0")
            execSQL("UPDATE tasks SET taskColumnId = CASE status WHEN 'Doing' THEN 2 WHEN 'Done' THEN 3 ELSE 1 END WHERE taskColumnId IS NULL")
            listOf(
                "Name" to "Name",
                "Status" to "Status",
                "Checklist" to "Checklist",
                "Due date" to "DueDate",
                "Text" to "Text",
                "Files & media" to "FilesMedia",
                "Assignee" to "Assignee",
                "Labels" to "Labels",
                "Priority" to "Priority",
                "Created at" to "CreatedAt",
                "Last modified" to "LastModified",
            ).forEachIndexed { index, (name, type) ->
                execSQL(
                    """
                    INSERT OR IGNORE INTO task_property_definitions
                        (boardId, name, type, sortOrder, hiddenWhenEmpty, optionsJson, createdAt, updatedAt)
                    SELECT id, '$name', '$type', $index, 0, '', $now, $now FROM task_boards
                    """.trimIndent(),
                )
            }

            execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_tags_name ON tags(name)")
            execSQL("CREATE INDEX IF NOT EXISTS index_note_tags_tagId ON note_tags(tagId)")
            execSQL("CREATE INDEX IF NOT EXISTS index_notes_notebookId ON notes(notebookId)")
            execSQL("CREATE INDEX IF NOT EXISTS index_notes_updatedAt ON notes(updatedAt)")
            execSQL("CREATE INDEX IF NOT EXISTS index_notes_pinned ON notes(pinned)")
            execSQL("CREATE INDEX IF NOT EXISTS index_notes_archived ON notes(archived)")
            execSQL("CREATE INDEX IF NOT EXISTS index_notes_workspaceId ON notes(workspaceId)")
            execSQL("CREATE INDEX IF NOT EXISTS index_notebooks_workspaceId ON notebooks(workspaceId)")
            execSQL("CREATE INDEX IF NOT EXISTS index_attachments_noteId ON attachments(noteId)")
            execSQL("CREATE INDEX IF NOT EXISTS index_note_embeds_noteId ON note_embeds(noteId)")
            execSQL("CREATE INDEX IF NOT EXISTS index_note_embeds_type ON note_embeds(type)")
            execSQL("CREATE INDEX IF NOT EXISTS index_task_boards_workspaceId ON task_boards(workspaceId)")
            execSQL("CREATE INDEX IF NOT EXISTS index_task_boards_updatedAt ON task_boards(updatedAt)")
            execSQL("CREATE INDEX IF NOT EXISTS index_task_columns_boardId ON task_columns(boardId)")
            execSQL("CREATE INDEX IF NOT EXISTS index_task_columns_sortOrder ON task_columns(sortOrder)")
            execSQL("CREATE INDEX IF NOT EXISTS index_tasks_status ON tasks(status)")
            execSQL("CREATE INDEX IF NOT EXISTS index_tasks_updatedAt ON tasks(updatedAt)")
            execSQL("CREATE INDEX IF NOT EXISTS index_tasks_workspaceId ON tasks(workspaceId)")
            execSQL("CREATE INDEX IF NOT EXISTS index_tasks_taskBoardId ON tasks(taskBoardId)")
            execSQL("CREATE INDEX IF NOT EXISTS index_tasks_taskColumnId ON tasks(taskColumnId)")
            execSQL("CREATE INDEX IF NOT EXISTS index_task_property_definitions_boardId ON task_property_definitions(boardId)")
            execSQL("CREATE INDEX IF NOT EXISTS index_task_property_definitions_sortOrder ON task_property_definitions(sortOrder)")
            execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_task_property_definitions_boardId_name ON task_property_definitions(boardId, name)")
            execSQL("CREATE INDEX IF NOT EXISTS index_task_property_values_taskId ON task_property_values(taskId)")
            execSQL("CREATE INDEX IF NOT EXISTS index_task_property_values_propertyId ON task_property_values(propertyId)")
            execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_task_property_values_taskId_propertyId ON task_property_values(taskId, propertyId)")
            execSQL("CREATE INDEX IF NOT EXISTS index_task_checklist_items_taskId ON task_checklist_items(taskId)")
            execSQL("CREATE INDEX IF NOT EXISTS index_task_checklist_items_propertyId ON task_checklist_items(propertyId)")
            execSQL("CREATE INDEX IF NOT EXISTS index_task_checklist_items_sortOrder ON task_checklist_items(sortOrder)")
            execSQL("CREATE INDEX IF NOT EXISTS index_chat_messages_createdAt ON chat_messages(createdAt)")
            execSQL("CREATE INDEX IF NOT EXISTS index_chat_messages_workspaceId ON chat_messages(workspaceId)")
            execSQL("CREATE INDEX IF NOT EXISTS index_canvas_nodes_updatedAt ON canvas_nodes(updatedAt)")
            execSQL("CREATE INDEX IF NOT EXISTS index_canvas_nodes_type ON canvas_nodes(type)")
            execSQL("CREATE INDEX IF NOT EXISTS index_canvas_nodes_workspaceId ON canvas_nodes(workspaceId)")
            execSQL("CREATE INDEX IF NOT EXISTS index_canvas_edges_fromNodeId ON canvas_edges(fromNodeId)")
            execSQL("CREATE INDEX IF NOT EXISTS index_canvas_edges_toNodeId ON canvas_edges(toNodeId)")
            execSQL("CREATE INDEX IF NOT EXISTS index_canvas_edges_workspaceId ON canvas_edges(workspaceId)")
            execSQL("CREATE INDEX IF NOT EXISTS index_workspace_objects_workspaceId ON workspace_objects(workspaceId)")
            execSQL("CREATE INDEX IF NOT EXISTS index_workspace_objects_objectType ON workspace_objects(objectType)")
            execSQL("CREATE INDEX IF NOT EXISTS index_workspace_objects_sourceId ON workspace_objects(sourceId)")
            execSQL("CREATE INDEX IF NOT EXISTS index_workspace_objects_updatedAt ON workspace_objects(updatedAt)")
            execSQL("CREATE INDEX IF NOT EXISTS index_workspace_objects_pinned ON workspace_objects(pinned)")
            execSQL("CREATE INDEX IF NOT EXISTS index_workspace_object_links_workspaceId ON workspace_object_links(workspaceId)")
            execSQL("CREATE INDEX IF NOT EXISTS index_workspace_object_links_fromObjectId ON workspace_object_links(fromObjectId)")
            execSQL("CREATE INDEX IF NOT EXISTS index_workspace_object_links_toObjectId ON workspace_object_links(toObjectId)")
            execSQL("CREATE INDEX IF NOT EXISTS index_workspace_object_links_linkType ON workspace_object_links(linkType)")
            execSQL("CREATE INDEX IF NOT EXISTS index_workspace_activities_workspaceId ON workspace_activities(workspaceId)")
            execSQL("CREATE INDEX IF NOT EXISTS index_workspace_activities_objectId ON workspace_activities(objectId)")
            execSQL("CREATE INDEX IF NOT EXISTS index_workspace_activities_createdAt ON workspace_activities(createdAt)")
            execSQL("CREATE INDEX IF NOT EXISTS index_workspace_activities_activityType ON workspace_activities(activityType)")
            execSQL("CREATE INDEX IF NOT EXISTS index_workspace_object_history_workspaceId ON workspace_object_history(workspaceId)")
            execSQL("CREATE INDEX IF NOT EXISTS index_workspace_object_history_objectId ON workspace_object_history(objectId)")
            execSQL("CREATE INDEX IF NOT EXISTS index_workspace_object_history_historyType ON workspace_object_history(historyType)")
            execSQL("CREATE INDEX IF NOT EXISTS index_workspace_object_history_createdAt ON workspace_object_history(createdAt)")
            execSQL("CREATE INDEX IF NOT EXISTS index_workspace_comments_workspaceId ON workspace_comments(workspaceId)")
            execSQL("CREATE INDEX IF NOT EXISTS index_workspace_comments_objectId ON workspace_comments(objectId)")
            execSQL("CREATE INDEX IF NOT EXISTS index_workspace_comments_authorUsername ON workspace_comments(authorUsername)")
            execSQL("CREATE INDEX IF NOT EXISTS index_workspace_comments_resolved ON workspace_comments(resolved)")
            execSQL("CREATE INDEX IF NOT EXISTS index_workspace_comments_updatedAt ON workspace_comments(updatedAt)")
            execSQL("CREATE INDEX IF NOT EXISTS index_workspace_files_workspaceId ON workspace_files(workspaceId)")
            execSQL("CREATE INDEX IF NOT EXISTS index_workspace_files_objectId ON workspace_files(objectId)")
            execSQL("CREATE INDEX IF NOT EXISTS index_workspace_files_mimeType ON workspace_files(mimeType)")
            execSQL("CREATE INDEX IF NOT EXISTS index_workspace_files_updatedAt ON workspace_files(updatedAt)")
            execSQL("CREATE INDEX IF NOT EXISTS index_goals_workspaceId ON goals(workspaceId)")
            execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_goals_syncId ON goals(syncId)")
            execSQL("CREATE INDEX IF NOT EXISTS index_goals_status ON goals(status)")
            execSQL("CREATE INDEX IF NOT EXISTS index_goals_updatedAt ON goals(updatedAt)")
            execSQL("CREATE INDEX IF NOT EXISTS index_calendar_events_workspaceId ON calendar_events(workspaceId)")
            execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_calendar_events_syncId ON calendar_events(syncId)")
            execSQL("CREATE INDEX IF NOT EXISTS index_calendar_events_startAt ON calendar_events(startAt)")
            execSQL("CREATE INDEX IF NOT EXISTS index_calendar_events_source ON calendar_events(source)")
            execSQL("CREATE INDEX IF NOT EXISTS index_calendar_events_externalId ON calendar_events(externalId)")
            execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_remote_object_bindings_workspaceId_objectType_localId ON remote_object_bindings(workspaceId, objectType, localId)")
            execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_remote_object_bindings_workspaceId_syncId ON remote_object_bindings(workspaceId, syncId)")
            execSQL("CREATE INDEX IF NOT EXISTS index_remote_object_bindings_remoteId ON remote_object_bindings(remoteId)")
            execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_sync_outbox_operationId ON sync_outbox(operationId)")
            execSQL("CREATE INDEX IF NOT EXISTS index_sync_outbox_workspaceId_state_nextAttemptAt ON sync_outbox(workspaceId, state, nextAttemptAt)")
            execSQL("CREATE INDEX IF NOT EXISTS index_sync_outbox_workspaceId_objectType_objectSyncId ON sync_outbox(workspaceId, objectType, objectSyncId)")
            execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_sync_tombstones_workspaceId_objectType_objectSyncId ON sync_tombstones(workspaceId, objectType, objectSyncId)")
            execSQL("CREATE INDEX IF NOT EXISTS index_sync_tombstones_workspaceId_acknowledgedAt ON sync_tombstones(workspaceId, acknowledgedAt)")
        }

        private fun SupportSQLiteDatabase.addColumnIfMissing(table: String, column: String, definition: String) {
            query("PRAGMA table_info($table)").use { cursor ->
                val nameIndex = cursor.getColumnIndex("name")
                while (cursor.moveToNext()) {
                    if (cursor.getString(nameIndex) == column) return
                }
            }
            execSQL("ALTER TABLE $table ADD COLUMN $column $definition")
        }
    }
}
