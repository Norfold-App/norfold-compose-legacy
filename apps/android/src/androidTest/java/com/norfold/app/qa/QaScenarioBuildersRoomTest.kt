package com.norfold.app.qa

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.norfold.app.data.DocsRepository
import com.norfold.app.data.NorfoldDatabase
import com.norfold.app.data.NotesDao
import com.norfold.app.domain.ChartBlock
import com.norfold.app.domain.CurrentEditorContentEpoch
import com.norfold.app.domain.DocOverlapMode
import com.norfold.app.domain.DocumentArtifactKind
import com.norfold.app.domain.DocumentBlock
import com.norfold.app.domain.DocumentOwner
import com.norfold.app.domain.DocumentPageBlock
import com.norfold.app.domain.OwnedDocument
import com.norfold.app.domain.SceneObjectKind
import com.norfold.app.domain.TableBlock
import com.norfold.app.domain.WorkspaceObjectType
import com.norfold.app.domain.relationshipDataBlock
import com.norfold.app.domain.walk
import com.norfold.app.editor.calculation.CellInput
import com.norfold.app.editor.calculation.TableCalculationBridge
import com.norfold.app.editor.flow.FlowBlockKind
import com.norfold.app.editor.relationship.CommentAnchor
import com.norfold.app.editor.relationship.CommentStatus
import com.norfold.app.editor.relationship.Phase7ArtifactCodec
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * What the QA scenarios actually author, against a real database.
 *
 * The builders drive the production runtimes and the command registry rather than inserting rows, so the
 * only honest way to check the harness menu's promises is to run a scenario and read the result back. Each
 * test here asserts a claim the menu makes to whoever is testing on a device.
 */
@RunWith(AndroidJUnit4::class)
class QaScenarioBuildersRoomTest {
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
    fun theEmptyScenarioIsACompleteSetupWithNoContent() = runBlocking {
        val workspaceId = QaScenarioBuilders.prepare(database, QaScenario.Empty)

        // The id belongs to production setup. Nothing in the harness may assume it is 1.
        assertTrue(workspaceId > 0)
        val settings = repository.settingsValue()
        assertEquals(workspaceId, settings.activeWorkspaceId)
        assertTrue(settings.onboardingComplete)
        assertFalse(settings.autoSync)
        assertEquals(1, dao.allWorkspaces().size)
        assertEquals(1, dao.allTaskBoards().size)

        assertTrue(dao.allNotesSnapshot().isEmpty())
        assertTrue(dao.allDocuments().isEmpty())
        assertTrue(dao.allDocumentBlocks().isEmpty())
        assertTrue(dao.allWorkspaceObjects().none { it.objectType == WorkspaceObjectType.Note.name })
        assertTrue(dao.allWorkspaceComments().isEmpty())

        // A workspace born in this build is already past the boundary, so it owes no reset to itself
        // or to a server.
        assertEquals(CurrentEditorContentEpoch, repository.editorContentEpoch())
        assertFalse(repository.remoteEditorResetPending())
    }

    @Test
    fun theOnboardingScenarioStopsShortOfACompletedSetup() = runBlocking {
        QaScenarioBuilders.prepare(database, QaScenario.Onboarding)

        assertFalse(repository.settingsValue().onboardingComplete)
        assertTrue(dao.allNotesSnapshot().isEmpty())
    }

    @Test
    fun theDemoScenarioAuthorsExactlyTheArtifactsTheMenuNames() = runBlocking {
        QaScenarioBuilders.prepare(database, QaScenario.Demo)

        assertEquals(
            listOf(
                QaTitles.Document,
                QaTitles.FlowArchitecture,
                QaTitles.FlowDailyLog,
                QaTitles.FlowFieldNotes,
                QaTitles.FlowReading,
                QaTitles.Legacy,
                QaTitles.QuickNote,
                QaTitles.Spreadsheet,
            ),
            dao.allNotesSnapshot().map { it.note.title }.sorted(),
        )
        assertEquals(
            mapOf(
                DocumentArtifactKind.Flow.storageValue to 5,
                DocumentArtifactKind.Document.storageValue to 2,
                DocumentArtifactKind.Spreadsheet.storageValue to 1,
            ),
            dao.allDocuments().groupingBy { it.artifactKind }.eachCount(),
        )

        // The workspace graph is a by-product of the production writers, never written by the harness.
        val notes = dao.allWorkspaceObjects().filter { it.objectType == WorkspaceObjectType.Note.name }
        assertEquals(8, notes.size)
        assertEquals(2, dao.allWorkspaceComments().size)
        assertTrue(dao.allWorkspaceActivities().isNotEmpty())
        assertTrue(dao.allWorkspaceObjectHistory().isNotEmpty())
    }

    @Test
    fun fieldNotesCarriesEveryInsertableBlockKind() = runBlocking {
        QaScenarioBuilders.prepare(database, QaScenario.Demo)
        val document = documentTitled(QaTitles.FlowFieldNotes).document

        val present = mutableListOf<DocumentBlock>()
        document.walk { present += it }
        val missing = FlowBlockKind.entries.filter { kind -> present.none(kind::matches) }
        assertEquals(emptyList<FlowBlockKind>(), missing)
    }

    @Test
    fun fieldNotesCarriesTheReviewStateTheReviewSurfaceRenders() = runBlocking {
        QaScenarioBuilders.prepare(database, QaScenario.Demo)
        val document = documentTitled(QaTitles.FlowFieldNotes).document

        val review = Phase7ArtifactCodec.decode(document.relationshipDataBlock()?.payload)
        assertEquals(2, review.threads.size)
        // One open and one resolved: the surface has to render both, and a restore has to land on them.
        assertEquals(1, review.threads.count { it.status == CommentStatus.OPEN })
        assertEquals(1, review.threads.count { it.status == CommentStatus.RESOLVED })
        assertTrue(review.threads.all { it.comments.size == 2 })
        // Both threads quote a phrase, so both must be anchored to the text rather than to the block.
        assertTrue(review.threads.all { it.anchor is CommentAnchor.Text })
        assertEquals(1, review.snapshots.size)
    }

    @Test
    fun theEditorialBriefIsTwoBoundedPagesWithALayeredOverlap() = runBlocking {
        QaScenarioBuilders.prepare(database, QaScenario.Demo)
        val stored = documentTitled(QaTitles.Document)

        assertEquals(DocOverlapMode.Bounded, stored.layoutMode)
        val pages = stored.document.blocks.filterIsInstance<DocumentPageBlock>()
        assertEquals(2, pages.size)
        val objects = pages.flatMap { it.objects }
        assertEquals(6, objects.size)
        assertEquals(1, objects.count { it.kind == SceneObjectKind.Shape })
        assertEquals(1, objects.count { it.kind == SceneObjectKind.Chart })
        // Send-to-back is what produces the overlap, so the shape must sit behind something on its page.
        val shapePage = pages.first { page -> page.objects.any { it.kind == SceneObjectKind.Shape } }
        val shape = shapePage.objects.first { it.kind == SceneObjectKind.Shape }
        assertTrue(shapePage.objects.any { it.z > shape.z })
    }

    @Test
    fun theLegacyDocumentIsStoredTheWayRestoreSuppliesOne() = runBlocking {
        QaScenarioBuilders.prepare(database, QaScenario.Demo)
        val stored = documentTitled(QaTitles.Legacy)

        assertEquals(DocOverlapMode.Overlap, stored.layoutMode)
        assertEquals(2, stored.freeformLayout.size)
        // Overlapping on purpose: the pre-cutover row this stands in for was positioned by hand.
        val (first, second) = stored.freeformLayout.values.sortedBy { it.z }
        assertTrue(second.y < first.y + first.height)
        assertTrue(stored.document.blocks.none { it is DocumentPageBlock })
    }

    @Test
    fun theWorkbookCarriesEverySpreadsheetFeatureTheMenuClaims() = runBlocking {
        QaScenarioBuilders.prepare(database, QaScenario.Demo)
        val document = documentTitled(QaTitles.Spreadsheet).document

        val table = document.blocks.filterIsInstance<TableBlock>().single()
        val view = TableCalculationBridge.view(table)
        assertTrue(view.upgraded)
        val workbook = view.workbook
        assertEquals(listOf("Sheet1", "Lookups"), workbook.sheets.map { it.name })
        assertEquals(listOf("ReadinessScores"), workbook.namedRanges.map { it.name })

        val main = workbook.sheets.first()
        assertEquals(1, main.frozenRows)
        assertEquals(1, main.validations.size)
        assertEquals(1, main.conditionalFormats.size)
        val formulas = main.cells.mapNotNull { (it.input as? CellInput.Formula)?.source }
        assertTrue(formulas.any { it.startsWith("=SUM(") })
        assertTrue(formulas.any { it.startsWith("=AVERAGE(") })

        // The chart is bound to the selection through the same command the toolbar uses, and it lands in
        // the document next to the table rather than inside the workbook.
        assertNotNull(document.blocks.filterIsInstance<ChartBlock>().firstOrNull())
    }

    private suspend fun documentTitled(title: String): OwnedDocument {
        val note = dao.allNotesSnapshot().firstOrNull { it.note.title == title }
            ?: error("No note titled $title")
        return repository.documentByOwner(DocumentOwner.note(note.note.id))
            ?: error("No document for $title")
    }
}
