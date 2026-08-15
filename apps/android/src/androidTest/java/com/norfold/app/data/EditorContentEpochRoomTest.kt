package com.norfold.app.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.norfold.app.domain.BlockDocument
import com.norfold.app.domain.BlockDocumentJson
import com.norfold.app.domain.CurrentEditorContentEpoch
import com.norfold.app.domain.DocumentOwner
import com.norfold.app.domain.InlineText
import com.norfold.app.domain.NoteEmbedType
import com.norfold.app.domain.NoteTagScope
import com.norfold.app.domain.ParagraphBlock
import com.norfold.app.domain.RemoteEditorResetOutcome
import com.norfold.app.domain.UnstampedEditorContentEpoch
import com.norfold.app.domain.WorkspaceActivityType
import com.norfold.app.domain.WorkspaceHistoryType
import com.norfold.app.domain.WorkspaceLinkType
import com.norfold.app.domain.WorkspaceObjectType
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The one-time editor content epoch reset, against a real database.
 *
 * The reset is defined by what it does not touch as much as by what it erases, so every test here
 * asserts both halves. What cannot be checked without a database — foreign-key cascades, the
 * transaction boundary, the settings round trip — is exactly what these tests exist for.
 */
@RunWith(AndroidJUnit4::class)
class EditorContentEpochRoomTest {
    private lateinit var database: NorfoldDatabase
    private lateinit var dao: NotesDao
    private lateinit var repository: DocsRepository

    @Before
    fun openDatabase() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            NorfoldDatabase::class.java,
        ).allowMainThreadQueries().build()
        dao = database.dao()
        repository = DocsRepository(database)
    }

    @After
    fun closeDatabase() = database.close()

    @Test
    fun resetErasesTheEditorGenerationAndKeepsEverythingElse() = runBlocking {
        seedInstallFromBeforeTheBoundary()

        assertTrue(repository.applyEditorContentEpoch())

        // Erased: notes, their documents and blocks, attachments, embeds, note tags, and the
        // workspace-graph rows that described them.
        assertTrue(dao.allNotesSnapshot().isEmpty())
        assertTrue(dao.allDocuments().isEmpty())
        assertTrue(dao.allDocumentBlocks().isEmpty())
        assertTrue(dao.allAttachments().isEmpty())
        assertTrue(dao.allNoteEmbeds().isEmpty())
        assertTrue(dao.allNotebooks().isEmpty())
        assertTrue(dao.allTags().none { it.scope == NoteTagScope })
        assertTrue(dao.allWorkspaceObjects().none { it.objectType == WorkspaceObjectType.Note.name })
        assertTrue(dao.allWorkspaceComments().isEmpty())
        assertTrue(dao.allWorkspaceObjectHistory().isEmpty())
        assertTrue(dao.allWorkspaceObjectLinks().isEmpty())

        // Preserved: the workspace itself, settings, tasks and their board, calendar, goals, chat,
        // standalone files, and the board-scoped tag that is a card label rather than a note tag.
        assertEquals(1, dao.allWorkspaces().size)
        assertTrue(dao.settings() != null)
        assertEquals(listOf("Keep this task"), dao.allTasks().map { it.title })
        assertEquals(1, dao.allTaskBoards().size)
        assertEquals(listOf("Design review"), dao.allCalendarEvents().map { it.title })
        assertEquals(1, dao.allGoals().size)
        assertEquals(1, dao.allChatChannels().size)
        assertEquals(1, dao.allChatMessages().size)
        assertEquals(listOf("brief.pdf"), dao.allWorkspaceFiles().map { it.displayName })
        assertEquals(listOf("board:3"), dao.allTags().map { it.scope })
        // A file object is not an editor artifact, so it and its activity row stay.
        assertEquals(listOf(WorkspaceObjectType.File.name), dao.allWorkspaceObjects().map { it.objectType })
        assertEquals(listOf("Added file"), dao.allWorkspaceActivities().map { it.title })

        assertEquals(CurrentEditorContentEpoch, repository.editorContentEpoch())
    }

    @Test
    fun editorSyncWorkGoesAndOtherPendingWorkSurvives() = runBlocking {
        seedInstallFromBeforeTheBoundary()
        listOf(WorkspaceObjectType.Note.name, DocsRepository.RemoteDocumentObjectType, WorkspaceObjectType.Task.name)
            .forEachIndexed { index, objectType ->
                dao.insertSyncOutbox(
                    SyncOutboxEntity(
                        workspaceId = 1,
                        operationId = "op-$index",
                        objectType = objectType,
                        objectSyncId = "sync-$index",
                        operation = "Upsert",
                        payload = "{}",
                        contentHash = "hash-$index",
                        nextAttemptAt = 0,
                        createdAt = 1,
                        updatedAt = 1,
                    ),
                )
                dao.insertSyncTombstone(
                    SyncTombstoneEntity(workspaceId = 1, objectType = objectType, objectSyncId = "tombstone-$index", deletedAt = 1),
                )
                dao.insertRemoteBinding(
                    RemoteObjectBindingEntity(
                        workspaceId = 1,
                        objectType = objectType,
                        localId = index + 1L,
                        syncId = "binding-$index",
                        updatedAt = 1,
                    ),
                )
            }

        assertTrue(repository.applyEditorContentEpoch())

        // Queued editor work would push the erased generation back to the server on the next sync.
        val pending = dao.pendingSyncOperations(ws = 1, now = Long.MAX_VALUE, limit = 50)
        assertEquals(listOf(WorkspaceObjectType.Task.name), pending.map { it.objectType })
        assertEquals(null, dao.remoteBinding(1, WorkspaceObjectType.Note.name, 1))
        assertEquals(null, dao.remoteBinding(1, DocsRepository.RemoteDocumentObjectType, 2))
        assertTrue(dao.remoteBinding(1, WorkspaceObjectType.Task.name, 3) != null)
    }

    @Test
    fun resetRunsOnceAndLeavesLaterWorkAlone() = runBlocking {
        seedInstallFromBeforeTheBoundary()

        assertTrue(repository.applyEditorContentEpoch())
        val afterBoundary = repository.createNote("Written after", "# Written after", null)

        // The second launch must be a no-op, not a second erasure.
        assertFalse(repository.applyEditorContentEpoch())

        assertEquals(listOf("Written after"), dao.allNotesSnapshot().map { it.note.title })
        assertTrue(dao.noteById(afterBoundary) != null)
    }

    @Test
    fun aResetThatCannotClearRestorePointsDoesNothingAtAll() = runBlocking {
        seedInstallFromBeforeTheBoundary()

        // Restore points are copies of documents. Leaving them behind while erasing the documents
        // would let the erased epoch be typed back into existence one undo at a time, so a failure
        // here has to abandon the whole boundary and retry on the next launch.
        assertFalse(repository.applyEditorContentEpoch(deleteLocalSnapshots = { false }))

        assertEquals(listOf("Before the boundary"), dao.allNotesSnapshot().map { it.note.title })
        assertEquals(UnstampedEditorContentEpoch, repository.editorContentEpoch())
    }

    @Test
    fun freshInstallIsBornAtTheCurrentEpochAndNeverResets() = runBlocking {
        repository.ensureSeedData()

        assertEquals(CurrentEditorContentEpoch, repository.editorContentEpoch())
        // Nothing to erase, so the boundary reports that it did not run.
        assertFalse(repository.applyEditorContentEpoch())
        assertTrue(dao.allNotesSnapshot().isEmpty())
    }

    @Test
    fun writingSettingsDoesNotMoveTheInstallBackToAnErasedEpoch() = runBlocking {
        seedInstallFromBeforeTheBoundary()
        assertTrue(repository.applyEditorContentEpoch())

        // The epoch is not part of the settings model, so a settings write rebuilds the row without
        // it. If that reverted the stamp, the reset would run again after something as ordinary as
        // a theme change.
        repository.updateSettings(repository.settingsValue().copy(lastSyncStatus = "changed"))
        repository.createNote("Survives a settings write", "# Survives", null)

        assertEquals(CurrentEditorContentEpoch, repository.editorContentEpoch())
        assertFalse(repository.applyEditorContentEpoch())
        assertEquals(listOf("Survives a settings write"), dao.allNotesSnapshot().map { it.note.title })
    }

    @Test
    fun anUpgradedInstallOwesTheRemoteResetUntilTheServerAnswers() = runBlocking {
        seedInstallFromBeforeTheBoundary()
        // Before the boundary there is nothing owed, because the local erase is what creates
        // the debt in the first place.
        assertFalse(repository.remoteEditorResetPending())

        assertTrue(repository.applyEditorContentEpoch())
        assertTrue(repository.remoteEditorResetPending())

        // Offline, signed out, expired: the answer is "not yet", and the debt must survive it.
        assertFalse(repository.recordRemoteEditorReset(RemoteEditorResetOutcome.Retryable("offline")))
        assertTrue(repository.remoteEditorResetPending())

        assertTrue(repository.recordRemoteEditorReset(RemoteEditorResetOutcome.Applied(3)))
        assertFalse(repository.remoteEditorResetPending())
    }

    @Test
    fun aServerThatHadAlreadyCrossedTheBoundarySettlesTheDebtToo() = runBlocking {
        seedInstallFromBeforeTheBoundary()
        assertTrue(repository.applyEditorContentEpoch())

        // A second device reaching a server another device already reset gets AlreadyApplied, which
        // has to settle the debt: the work is done, and it does not matter who did it.
        assertTrue(repository.recordRemoteEditorReset(RemoteEditorResetOutcome.AlreadyApplied))
        assertFalse(repository.remoteEditorResetPending())
    }

    @Test
    fun aDeviceThatMayNotResetTheWorkspaceStopsAsking() = runBlocking {
        seedInstallFromBeforeTheBoundary()
        assertTrue(repository.applyEditorContentEpoch())

        // A member or viewer will never be allowed to erase the workspace's documents, so retrying
        // forever would be a permanent background failure with no possible outcome.
        assertTrue(repository.recordRemoteEditorReset(RemoteEditorResetOutcome.Refused("authorization_denied")))
        assertFalse(repository.remoteEditorResetPending())
    }

    @Test
    fun aSettingsWriteDoesNotBringTheRemoteDebtBack() = runBlocking {
        seedInstallFromBeforeTheBoundary()
        assertTrue(repository.applyEditorContentEpoch())
        assertTrue(repository.recordRemoteEditorReset(RemoteEditorResetOutcome.Applied(1)))

        // The remote columns are not part of the settings model either, so the same trap applies:
        // a theme change must not make the device start asking the server to erase again.
        repository.updateSettings(repository.settingsValue().copy(lastSyncStatus = "changed"))

        assertFalse(repository.remoteEditorResetPending())
    }

    @Test
    fun aFreshInstallOwesTheServerNothing() = runBlocking {
        repository.ensureSeedData()

        // A workspace created by this build never held a remote editor generation, so the retry
        // must never start — not even once, and not on any later launch.
        assertFalse(repository.remoteEditorResetPending())
        repository.updateSettings(repository.settingsValue().copy(lastSyncStatus = "changed"))
        assertFalse(repository.remoteEditorResetPending())
    }

    /** An install carrying a generation of editor content authored before the boundary existed. */
    private suspend fun seedInstallFromBeforeTheBoundary() {
        dao.insertWorkspace(WorkspaceEntity(id = 1, name = "Test", createdAt = 1))
        dao.upsertSettings(AppSettingsEntity(activeWorkspaceId = 1, onboardingComplete = true))
        assertEquals(UnstampedEditorContentEpoch, repository.editorContentEpoch())

        val notebook = dao.insertNotebook(NotebookEntity(name = "Work", color = 0, sortOrder = 0, workspaceId = 1))
        dao.insertNote(
            NoteEntity(
                id = 1,
                title = "Before the boundary",
                searchText = "Before the boundary",
                notebookId = notebook,
                pinned = false,
                starred = false,
                archived = false,
                locked = false,
                createdAt = 1,
                updatedAt = 1,
                workspaceId = 1,
            ),
        )
        val noteDocument = DocumentOwner.note(1)
        dao.upsertDocument(
            DocumentEntity(
                id = noteDocument.documentId,
                ownerType = noteDocument.type.storageValue,
                ownerId = noteDocument.id,
                createdAt = 1,
                updatedAt = 1,
            ),
        )
        val block = ParagraphBlock(id = "body", content = listOf(InlineText("Erased with its note")))
        dao.upsertDocumentBlocks(
            BlockDocument(listOf(block)).blocks.mapIndexed { position, it ->
                DocumentBlockEntity(it.id, noteDocument.documentId, position, BlockDocumentJson.encodeBlock(it), 1)
            },
        )
        dao.insertAttachment(AttachmentEntity(noteId = 1, displayName = "image.png", mimeType = "image/png", uri = "content://image", sizeBytes = 128))
        dao.insertNoteEmbed(NoteEmbedEntity(noteId = 1, type = NoteEmbedType.Link.name, title = "Link", target = "https://x.y", createdAt = 1))
        val noteTag = dao.insertTag(TagEntity(name = "sync", color = 0, scope = NoteTagScope))
        dao.insertNoteTag(NoteTagCrossRef(noteId = 1, tagId = noteTag))
        dao.insertTag(TagEntity(name = "Planning", color = 0, scope = "board:3"))

        // A rich body owned by a task: erased with the rest of the editor generation, while the
        // task row that owns it is card metadata the boundary preserves.
        dao.insertTaskBoard(TaskBoardEntity(id = 3, name = "Project board", workspaceId = 1, createdAt = 1, updatedAt = 1))
        val task = dao.insertTask(TaskEntity(title = "Keep this task", workspaceId = 1, createdAt = 1, updatedAt = 1, taskBoardId = 3))
        val taskDocument = DocumentOwner.task(task)
        dao.upsertDocument(
            DocumentEntity(
                id = taskDocument.documentId,
                ownerType = taskDocument.type.storageValue,
                ownerId = taskDocument.id,
                createdAt = 1,
                updatedAt = 1,
            ),
        )

        dao.insertCalendarEvent(CalendarEventEntity(workspaceId = 1, syncId = "event-1", title = "Design review", startAt = 200, endAt = 220, createdAt = 1, updatedAt = 1))
        dao.insertGoal(GoalEntity(workspaceId = 1, syncId = "goal-1", title = "Ship", createdAt = 1, updatedAt = 1))
        val channel = dao.insertChatChannel(ChatChannelEntity(syncId = "channel-1", kind = "Topic", name = "General", createdAt = 1, workspaceId = 1))
        dao.insertChatMessage(ChatMessageEntity(authorUsername = "member", authorDisplayName = "Member", body = "hi", color = 0, createdAt = 1, workspaceId = 1, channelId = channel))

        val noteObject = dao.insertWorkspaceObject(
            WorkspaceObjectEntity(objectType = WorkspaceObjectType.Note.name, sourceId = 1, title = "Before the boundary", createdAt = 1, updatedAt = 1, workspaceId = 1),
        )
        val fileObject = dao.insertWorkspaceObject(
            WorkspaceObjectEntity(objectType = WorkspaceObjectType.File.name, sourceId = null, title = "brief.pdf", createdAt = 1, updatedAt = 1, workspaceId = 1),
        )
        dao.insertWorkspaceObjectLink(
            WorkspaceObjectLinkEntity(fromObjectId = noteObject, toObjectId = fileObject, linkType = WorkspaceLinkType.Attachment.name, label = "brief.pdf", createdAt = 1),
        )
        dao.insertWorkspaceActivity(
            WorkspaceActivityEntity(objectId = noteObject, activityType = WorkspaceActivityType.Updated.name, actor = "You", title = "Updated note", createdAt = 1),
        )
        dao.insertWorkspaceActivity(
            WorkspaceActivityEntity(objectId = fileObject, activityType = WorkspaceActivityType.Updated.name, actor = "You", title = "Added file", createdAt = 1),
        )
        dao.insertWorkspaceObjectHistory(
            WorkspaceObjectHistoryEntity(objectId = noteObject, historyType = WorkspaceHistoryType.Updated.name, actor = "You", summary = "Edited a block", createdAt = 1),
        )
        dao.insertWorkspaceComment(
            WorkspaceCommentEntity(objectId = noteObject, authorUsername = "you", authorDisplayName = "You", body = "Looks good", createdAt = 1, updatedAt = 1),
        )
        dao.insertWorkspaceFile(
            WorkspaceFileEntity(objectId = fileObject, displayName = "brief.pdf", mimeType = "application/pdf", uri = "content://brief", sizeBytes = 4096, createdAt = 1, updatedAt = 1),
        )
    }
}
