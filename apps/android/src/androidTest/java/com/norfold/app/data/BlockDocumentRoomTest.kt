package com.norfold.app.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.norfold.app.domain.BlockDocument
import com.norfold.app.domain.BlockDocumentJson
import com.norfold.app.domain.BlockCursor
import com.norfold.app.domain.BlockEditorSession
import com.norfold.app.domain.BoldInline
import com.norfold.app.domain.ChartBlock
import com.norfold.app.domain.DocOverlapMode
import com.norfold.app.domain.EmbedBlock
import com.norfold.app.domain.FileBlock
import com.norfold.app.domain.HeadingBlock
import com.norfold.app.domain.InlineText
import com.norfold.app.domain.ParagraphBlock
import com.norfold.app.domain.MathBlock
import com.norfold.app.domain.MermaidBlock
import com.norfold.app.domain.TableBlock
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BlockDocumentRoomTest {
    private lateinit var database: NorfoldDatabase
    private lateinit var dao: NotesDao

    @Before
    fun openDatabase() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            NorfoldDatabase::class.java,
        ).allowMainThreadQueries().build()
        dao = database.dao()
    }

    @After
    fun closeDatabase() = database.close()

    @Test
    fun blockTreeReloadsFromRoomIdentically() = runBlocking {
        val document = BlockDocument(
            listOf(
                HeadingBlock(id = "heading", level = 2, content = listOf(InlineText("Room round trip"))),
                ParagraphBlock(id = "body", content = listOf(BoldInline(listOf(InlineText("Exact"))), InlineText(" block JSON"))),
            ),
        )
        dao.insertNote(note(id = 7, searchText = document.plainText()))
        dao.upsertNoteBlocks(document.blocks.mapIndexed { position, block ->
            NoteBlockEntity(block.id, 7, position, BlockDocumentJson.encodeBlock(block), 100)
        })

        assertEquals(document, dao.noteById(7)!!.toDomain().document)
    }

    @Test
    fun repositoryWritesOnlyTheChangedBlockRow() = runBlocking {
        dao.insertWorkspace(WorkspaceEntity(id = 1, name = "Test", createdAt = 1))
        dao.upsertSettings(AppSettingsEntity(activeWorkspaceId = 1, onboardingComplete = true))
        val first = ParagraphBlock(id = "first", content = listOf(InlineText("one")))
        val second = ParagraphBlock(id = "second", content = listOf(InlineText("two")))
        val original = BlockDocument(listOf(first, second))
        dao.insertNote(note(id = 8, searchText = original.plainText()))
        dao.upsertNoteBlocks(original.blocks.mapIndexed { position, block ->
            NoteBlockEntity(block.id, 8, position, BlockDocumentJson.encodeBlock(block), 100)
        })
        val loaded = dao.noteById(8)!!.toDomain()
        val changed = original.copy(blocks = listOf(first.copy(content = listOf(InlineText("changed"))), second))

        DocsRepository(database).updateNote(loaded, loaded.title, changed, setOf(first.id))

        val rows = dao.blocksForNote(8).associateBy(NoteBlockEntity::id)
        assertTrue(rows.getValue("first").updatedAt > 100)
        assertEquals(100, rows.getValue("second").updatedAt)
        assertEquals(changed, dao.noteById(8)!!.toDomain().document)
    }

    @Test
    fun overlapModeDefaultsToReflowAndRoundTripsThroughRoom() = runBlocking {
        dao.insertNote(note(id = 20, searchText = ""))
        val stored = dao.noteById(20)!!.toDomain()
        assertEquals(DocOverlapMode.Reflow, stored.overlapMode)

        DocsRepository(database).setOverlapMode(stored, DocOverlapMode.Overlap)

        assertEquals(DocOverlapMode.Overlap, dao.noteById(20)!!.toDomain().overlapMode)
        // The stored literal is the lowercase enum name — the compat contract docOverlapModeOf
        // decodes ("reflow"/"overlap"), never the UI labels ("Page"/"Infinite page").
        assertEquals("overlap", dao.noteById(20)!!.note.overlapMode)
    }

    @Test
    fun newBlankNoteHasOneEmptyParagraphAndNoDuplicateHeading() = runBlocking {
        dao.insertWorkspace(WorkspaceEntity(id = 1, name = "Test", createdAt = 1))
        dao.upsertSettings(AppSettingsEntity(activeWorkspaceId = 1, onboardingComplete = true))
        val id = DocsRepository(database).createNote(title = "Untitled doc", body = "")
        val note = dao.noteById(id)!!.toDomain()
        assertEquals("Untitled doc", note.title)
        assertEquals(1, note.document.blocks.size)
        assertTrue(note.document.blocks.single() is ParagraphBlock)
        assertEquals("", note.document.blocks.single().plainText())
    }

    @Test
    fun crossBlockReplacementDeletesObsoleteRowsAndReloadsExactly() = runBlocking {
        dao.insertWorkspace(WorkspaceEntity(id = 1, name = "Test", createdAt = 1))
        dao.upsertSettings(AppSettingsEntity(activeWorkspaceId = 1, onboardingComplete = true))
        val first = ParagraphBlock(id = "first", content = listOf(InlineText("alpha")))
        val second = ParagraphBlock(id = "second", content = listOf(InlineText("beta")))
        val third = ParagraphBlock(id = "third", content = listOf(InlineText("gamma")))
        val original = BlockDocument(listOf(first, second, third))
        dao.insertNote(note(id = 9, searchText = original.plainText()))
        dao.upsertNoteBlocks(original.blocks.mapIndexed { position, block ->
            NoteBlockEntity(block.id, 9, position, BlockDocumentJson.encodeBlock(block), 100)
        })
        val loaded = dao.noteById(9)!!.toDomain()
        val session = BlockEditorSession(loaded.document)

        session.replaceSelection(BlockCursor(first.id, 2), BlockCursor(third.id, 2), "X")
        DocsRepository(database).updateNote(loaded, loaded.title, session.document, session.dirtyBlockIds)

        assertEquals(listOf("first"), dao.blocksForNote(9).map(NoteBlockEntity::id))
        assertEquals("alXmma", dao.noteById(9)!!.toDomain().document.plainText())
        assertEquals(session.document, dao.noteById(9)!!.toDomain().document)
    }

    @Test
    fun taskTagsAreCaseInsensitiveWithinBoardAndIndependentAcrossBoards() = runBlocking {
        dao.insertWorkspace(WorkspaceEntity(id = 1, name = "Test", createdAt = 1))
        dao.upsertSettings(AppSettingsEntity(activeWorkspaceId = 1, onboardingComplete = true))
        val repository = DocsRepository(database)

        val first = repository.addTaskTag(7, "#Planning")
        val same = repository.addTaskTag(7, "planning")
        val otherBoard = repository.addTaskTag(8, "PLANNING")

        assertEquals(first, same)
        assertTrue(first != otherBoard)
        assertEquals(setOf("board:7", "board:8"), dao.allTags().map { it.scope }.toSet())
        assertTrue(dao.allTags().all { it.normalizedName == "planning" && !it.name.startsWith("#") })
    }

    @Test
    fun emptyDatabaseSeedsCompleteGuideExactlyOnce() = runBlocking {
        val repository = DocsRepository(database)

        repository.ensureSeedData()
        val notesAfterFirstSeed = dao.allNotesSnapshot().map { it.toDomain() }
        val tasksAfterFirstSeed = dao.allTasks()
        val playground = notesAfterFirstSeed.single { it.title == "Rich blocks playground" }
        val stress = notesAfterFirstSeed.single { it.title == "Long document performance demo" }
        val blockTypes = playground.document.blocks.map { it::class }

        assertTrue(dao.allNotebooks().any { it.name == "Norfold Guide" })
        assertTrue(blockTypes.containsAll(listOf(TableBlock::class, MathBlock::class, MermaidBlock::class, ChartBlock::class, EmbedBlock::class, FileBlock::class)))
        assertTrue(stress.document.blocks.size > 200)
        assertTrue(stress.document.blocks.count { it is MathBlock || it is MermaidBlock || it is ChartBlock } >= 8)
        assertTrue(tasksAfterFirstSeed.size >= 4)
        assertTrue(dao.allTaskPropertyValues().isNotEmpty())
        assertTrue(dao.allTags().any { it.scope.startsWith("board:") })

        repository.ensureSeedData()

        assertEquals(notesAfterFirstSeed.size, dao.allNotesSnapshot().size)
        assertEquals(tasksAfterFirstSeed.size, dao.allTasks().size)
    }

    @Test
    fun guideFillsMissingNotesWithoutReplacingExistingTasks() = runBlocking {
        dao.insertWorkspace(WorkspaceEntity(id = 1, name = "Test", createdAt = 1))
        dao.upsertSettings(AppSettingsEntity(activeWorkspaceId = 1, onboardingComplete = true))
        dao.insertTask(TaskEntity(title = "Keep this task", workspaceId = 1, createdAt = 1, updatedAt = 1))

        DocsRepository(database).ensureSeedData()

        assertTrue(dao.allNotesSnapshot().any { it.note.title == "Rich blocks playground" })
        assertEquals(listOf("Keep this task"), dao.allTasks().map { it.title })
    }

    private fun note(id: Long, searchText: String) = NoteEntity(
        id = id,
        title = "Block test",
        searchText = searchText,
        notebookId = null,
        pinned = false,
        starred = false,
        archived = false,
        locked = false,
        createdAt = 1,
        updatedAt = 1,
        workspaceId = 1,
    )
}
