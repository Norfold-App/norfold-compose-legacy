package com.norfold.app.qa

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.room.withTransaction
import com.norfold.app.data.AppSettingsEntity
import com.norfold.app.data.DocumentBlockEntity
import com.norfold.app.data.DocumentEntity
import com.norfold.app.data.NorfoldDatabase
import com.norfold.app.data.NoteEntity
import com.norfold.app.data.WorkspaceActivityEntity
import com.norfold.app.data.WorkspaceCommentEntity
import com.norfold.app.data.WorkspaceEntity
import com.norfold.app.data.WorkspaceObjectEntity
import com.norfold.app.data.WorkspaceObjectHistoryEntity
import com.norfold.app.domain.AppSettings
import com.norfold.app.domain.BlockDocument
import com.norfold.app.domain.BlockDocumentJson
import com.norfold.app.domain.BoldInline
import com.norfold.app.domain.BulletListBlock
import com.norfold.app.domain.CalloutBlock
import com.norfold.app.domain.ChartBlock
import com.norfold.app.domain.CodeBlock
import com.norfold.app.domain.ContainerAxis
import com.norfold.app.domain.ContainerBlock
import com.norfold.app.domain.Destination
import com.norfold.app.domain.DocCanvasSpec
import com.norfold.app.domain.DocLayoutJson
import com.norfold.app.domain.DocOverlapMode
import com.norfold.app.domain.DocumentArtifactKind
import com.norfold.app.domain.DocumentOwner
import com.norfold.app.domain.DocumentOwnerType
import com.norfold.app.domain.DocumentPageBlock
import com.norfold.app.domain.HeadingBlock
import com.norfold.app.domain.ImageBlock
import com.norfold.app.domain.InlineText
import com.norfold.app.domain.ListItem
import com.norfold.app.domain.LinkInline
import com.norfold.app.domain.PageGeometry
import com.norfold.app.domain.ParagraphBlock
import com.norfold.app.domain.QuoteBlock
import com.norfold.app.domain.SceneGeometry
import com.norfold.app.domain.SceneObject
import com.norfold.app.domain.SceneObjectKind
import com.norfold.app.domain.ShapeBlock
import com.norfold.app.domain.ShapeKind
import com.norfold.app.domain.TableBlock
import com.norfold.app.domain.TableCell
import com.norfold.app.domain.ThemeMode
import com.norfold.app.domain.ThemeProfile
import com.norfold.app.domain.TodoItem
import com.norfold.app.domain.TodoListBlock
import com.norfold.app.domain.WorkspaceActivityType
import com.norfold.app.domain.WorkspaceHistoryType
import com.norfold.app.domain.WorkspaceObjectType
import com.norfold.app.editor.calculation.CalculationCell
import com.norfold.app.editor.calculation.CalculationColumn
import com.norfold.app.editor.calculation.CalculationRow
import com.norfold.app.editor.calculation.CalculationSheet
import com.norfold.app.editor.calculation.CalculationWorkbook
import com.norfold.app.editor.calculation.CellId
import com.norfold.app.editor.calculation.CellFormat
import com.norfold.app.editor.calculation.CellInput
import com.norfold.app.editor.calculation.ColumnId
import com.norfold.app.editor.calculation.ComparisonOperator
import com.norfold.app.editor.calculation.ConditionalCondition
import com.norfold.app.editor.calculation.ConditionalFormatRule
import com.norfold.app.editor.calculation.ConditionalRuleId
import com.norfold.app.editor.calculation.EmbeddedTableColumn
import com.norfold.app.editor.calculation.EmbeddedTableDefinition
import com.norfold.app.editor.calculation.EmbeddedTableId
import com.norfold.app.editor.calculation.NamedRange
import com.norfold.app.editor.calculation.NamedRangeId
import com.norfold.app.editor.calculation.RowId
import com.norfold.app.editor.calculation.SheetId
import com.norfold.app.editor.calculation.StableCellCoordinate
import com.norfold.app.editor.calculation.StableCellRange
import com.norfold.app.editor.calculation.TableCalculationBridge
import com.norfold.app.editor.calculation.ValidationCondition
import com.norfold.app.editor.calculation.ValidationPolicy
import com.norfold.app.editor.calculation.ValidationRule
import com.norfold.app.editor.calculation.ValidationRuleId
import com.norfold.app.editor.calculation.WorkbookId
import com.norfold.app.editor.interchange.CompatibilityMetadata
import com.norfold.app.editor.interchange.FidelityIssue
import com.norfold.app.editor.interchange.FidelityRegion
import com.norfold.app.editor.interchange.FidelityReport
import com.norfold.app.editor.interchange.FidelitySeverity
import com.norfold.app.editor.interchange.FidelityState
import com.norfold.app.editor.interchange.ImportFormat
import com.norfold.app.editor.interchange.ImportIntent
import com.norfold.app.editor.interchange.ImportMode
import com.norfold.app.editor.interchange.InterchangeArtifactMetadata
import com.norfold.app.editor.interchange.InterchangeMetadataCodec
import com.norfold.app.editor.interchange.SourceArtifactMetadata
import com.norfold.app.editor.relationship.AnchorState
import com.norfold.app.editor.relationship.Comment
import com.norfold.app.editor.relationship.CommentAuthorRef
import com.norfold.app.editor.relationship.CommentId
import com.norfold.app.editor.relationship.CommentMention
import com.norfold.app.editor.relationship.CommentStatus
import com.norfold.app.editor.relationship.CommentAnchor
import com.norfold.app.editor.relationship.DiscussionThread
import com.norfold.app.editor.relationship.Phase7ArtifactCodec
import com.norfold.app.editor.relationship.Phase7ArtifactState
import com.norfold.app.editor.relationship.Relationship
import com.norfold.app.editor.relationship.RelationshipAnchor
import com.norfold.app.editor.relationship.RelationshipEndpoint
import com.norfold.app.editor.relationship.RelationshipId
import com.norfold.app.editor.relationship.RelationshipKinds
import com.norfold.app.editor.relationship.RelationshipMetadata
import com.norfold.app.editor.relationship.ReviewSnapshot
import com.norfold.app.editor.relationship.ReviewSnapshotId
import com.norfold.app.editor.relationship.ThreadId
import com.norfold.app.domain.style.ColorValue
import com.norfold.app.domain.withInterchangeData
import com.norfold.app.domain.withRelationshipData
import com.norfold.app.ui.DocsViewModel
import com.norfold.app.ui.NorfoldRoot
import com.norfold.app.ui.NorfoldTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** Debug-variant-only launcher for deterministic, account-free integrated QA. */
class QaHarnessActivity : FragmentActivity() {
    private var status by mutableStateOf("Choose a deterministic local scenario")
    private var busy by mutableStateOf(false)
    private var integrationSeamConfigured by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        integrationSeamConfigured = QaIntegrationCredentials.from(intent.extras) != null
        showMenu()
    }

    private fun showMenu() {
        setContent {
            NorfoldTheme(
                settings = AppSettings(
                    themeMode = ThemeMode.System,
                    themeProfile = ThemeProfile.Norfold,
                    accentColor = 0xFF3D7C45,
                    backupFolderUri = null,
                    vaultLockEnabled = false,
                    vaultSecretHash = null,
                    onboardingComplete = true,
                ),
            ) {
                QaHarnessMenu(
                    status = status,
                    busy = busy,
                    integrationSeamConfigured = integrationSeamConfigured,
                    onEntry = ::runEntry,
                )
            }
        }
    }

    private fun runEntry(entry: QaEntry) {
        if (busy) return
        busy = true
        status = "Preparing ${entry.title}"
        lifecycleScope.launch {
            runCatching {
                withContext(Dispatchers.IO) { QaDemoCorpus.reset(applicationContext, entry.scenario) }
                if (entry == QaEntry.ResetDemo) {
                    status = "Demo data restored to the exact QA baseline"
                    busy = false
                    showMenu()
                    return@launch
                }
                val viewModel = ViewModelProvider(this@QaHarnessActivity)[DocsViewModel::class.java]
                withTimeout(15_000) { viewModel.state.first { it.settings.activeWorkspaceId == QaDemoCorpus.WorkspaceId } }
                when (entry.surface) {
                    QaSurface.Workspace -> viewModel.go(Destination.WorkspaceHub)
                    QaSurface.Onboarding -> Unit
                    QaSurface.Flow -> openArtifact(viewModel, DocumentArtifactKind.Flow, entry.noteTitle)
                    QaSurface.Document -> openArtifact(viewModel, DocumentArtifactKind.Document, entry.noteTitle)
                    QaSurface.Spreadsheet -> openArtifact(viewModel, DocumentArtifactKind.Spreadsheet, entry.noteTitle)
                    QaSurface.Review -> {
                        val objectRow = withTimeout(15_000) {
                            viewModel.state.first { state -> state.workspaceObjects.any { it.id == QaDemoCorpus.DocumentObjectId } }
                        }.workspaceObjects.first { it.id == QaDemoCorpus.DocumentObjectId }
                        viewModel.openWorkspaceObject(objectRow)
                    }
                    QaSurface.History -> viewModel.go(Destination.Activity)
                    QaSurface.Interchange -> openArtifact(viewModel, DocumentArtifactKind.Document, QaDemoCorpus.DocumentTitle)
                }
                setContent { NorfoldRoot(viewModel) }
            }.onFailure { error ->
                status = "QA setup failed: ${error.message ?: error::class.java.simpleName}"
                busy = false
                showMenu()
            }
        }
    }

    private suspend fun openArtifact(
        viewModel: DocsViewModel,
        kind: DocumentArtifactKind,
        title: String?,
    ) {
        val state = withTimeout(15_000) {
            viewModel.state.first { current ->
                current.notes.any { note -> note.artifactKind == kind && (title == null || note.title == title) }
            }
        }
        viewModel.select(state.notes.first { it.artifactKind == kind && (title == null || it.title == title) })
    }
}

private enum class QaScenario { Demo, Empty, Onboarding, Offline }
private enum class QaSurface { Workspace, Onboarding, Flow, Document, Spreadsheet, Review, History, Interchange }

private enum class QaEntry(
    val title: String,
    val detail: String,
    val scenario: QaScenario,
    val surface: QaSurface,
    val noteTitle: String? = null,
) {
    Demo("Enter Demo Workspace", "Complete local corpus; no account, cloud, or network", QaScenario.Demo, QaSurface.Workspace),
    Empty("Fresh / Empty Workspace", "A completed setup with no user content", QaScenario.Empty, QaSurface.Workspace),
    Onboarding("Test Onboarding", "First-run setup from a deterministic blank database", QaScenario.Onboarding, QaSurface.Onboarding),
    Offline("Offline Workspace", "Local content with sync disabled and explicit offline status", QaScenario.Offline, QaSurface.Workspace),
    Legacy("Legacy Compatibility", "A positioned pre-cutover row routed through bounded conversion", QaScenario.Demo, QaSurface.Document, QaDemoCorpus.LegacyTitle),
    ResetDemo("Reset Demo Data", "Delete mutations and restore the exact QA baseline", QaScenario.Demo, QaSurface.Workspace),
    Flow("Open Flow", "Rich long-form block corpus", QaScenario.Demo, QaSurface.Flow, QaDemoCorpus.FlowTitle),
    Document("Open Document", "Two pages with text, table, image, shape, overlap, and chart", QaScenario.Demo, QaSurface.Document, QaDemoCorpus.DocumentTitle),
    Spreadsheet("Open Spreadsheet", "Multi-sheet values, formulas, and chart binding", QaScenario.Demo, QaSurface.Spreadsheet, QaDemoCorpus.SpreadsheetTitle),
    Review("Open Review", "Comments and object review surface", QaScenario.Demo, QaSurface.Review),
    History("Open History", "Deterministic activity and version trail", QaScenario.Demo, QaSurface.History),
    Interchange("Open Interchange", "Seeded fidelity, import, export, and raw-source editor controls", QaScenario.Demo, QaSurface.Interchange),
}

@Composable
private fun QaHarnessMenu(
    status: String,
    busy: Boolean,
    integrationSeamConfigured: Boolean,
    onEntry: (QaEntry) -> Unit,
) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 20.dp),
            contentPadding = PaddingValues(top = 40.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Norfold QA", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.SemiBold)
                    Text("Debug-only deterministic test harness", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        if (integrationSeamConfigured) "Optional integration seam: configured for this launch"
                        else "Optional integration seam: waiting for externally injected QA credentials",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        status,
                        modifier = Modifier.semantics { stateDescription = status },
                        color = MaterialTheme.colorScheme.primary,
                    )
                    if (busy) CircularProgressIndicator(Modifier.testTag("qa_loading"))
                }
            }
            items(QaEntry.entries, key = QaEntry::name) { entry ->
                Button(
                    onClick = { onEntry(entry) },
                    enabled = !busy,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 56.dp)
                        .testTag("qa_entry_${entry.name.lowercase()}"),
                ) {
                    Column(Modifier.fillMaxWidth()) {
                        Text(entry.title, fontWeight = FontWeight.SemiBold)
                        Text(entry.detail, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

/** Ephemeral debug-only seam. Values are never persisted, logged, or rendered. */
private data class QaIntegrationCredentials(val email: String, val password: String) {
    companion object {
        fun from(extras: Bundle?): QaIntegrationCredentials? {
            val email = extras?.getString("com.norfold.qa.EMAIL")?.trim().orEmpty()
            val password = extras?.getString("com.norfold.qa.PASSWORD").orEmpty()
            return if (email.contains('@') && password.isNotBlank()) QaIntegrationCredentials(email, password) else null
        }
    }
}

private object QaDemoCorpus {
    const val WorkspaceId = 1L
    const val DocumentObjectId = 1_001L
    const val FlowTitle = "QA Field Notes"
    const val DocumentTitle = "QA Editorial Brief"
    const val SpreadsheetTitle = "QA Launch Workbook"
    const val LegacyTitle = "QA Legacy Positioned Document"
    private const val BaseTime = 1_780_000_000_000L

    suspend fun reset(context: android.content.Context, scenario: QaScenario) {
        val database = NorfoldDatabase.get(context)
        database.clearAllTables()
        database.openHelper.writableDatabase.execSQL("DELETE FROM sqlite_sequence")
        database.withTransaction {
            val dao = database.dao()
            dao.insertWorkspace(
                WorkspaceEntity(
                    id = WorkspaceId,
                    name = if (scenario == QaScenario.Empty || scenario == QaScenario.Onboarding) "Empty QA Workspace" else "Norfold QA Workspace",
                    icon = "N",
                    createdAt = BaseTime,
                ),
            )
            dao.upsertSettings(
                AppSettingsEntity(
                    activeWorkspaceId = WorkspaceId,
                    onboardingComplete = scenario != QaScenario.Onboarding,
                    syncUserName = "qa.owner",
                    syncPublicName = "QA Owner",
                    workspaceName = "Norfold QA Workspace",
                    themeMode = ThemeMode.System.name,
                    autoSync = false,
                    backgroundSync = false,
                    notificationPush = false,
                    lastSyncStatus = if (scenario == QaScenario.Offline) "Offline · changes saved locally" else "Local QA workspace",
                ),
            )
            if (scenario != QaScenario.Empty && scenario != QaScenario.Onboarding) seedDemo(dao)
        }
    }

    private suspend fun seedDemo(dao: com.norfold.app.data.NotesDao) {
        storeNote(dao, 100L, FlowTitle, flowDocument(), DocOverlapMode.Reflow, DocumentArtifactKind.Flow)
        storeNote(
            dao,
            101L,
            "QA Quick Note",
            BlockDocument(listOf(ParagraphBlock("quick-note-body", listOf(InlineText("A small note for empty-versus-rich library comparison."))))),
            DocOverlapMode.Reflow,
            DocumentArtifactKind.Flow,
        )
        storeNote(dao, 200L, DocumentTitle, boundedDocument(), DocOverlapMode.Bounded, DocumentArtifactKind.Document)
        storeNote(dao, 300L, SpreadsheetTitle, spreadsheetDocument(), DocOverlapMode.Reflow, DocumentArtifactKind.Spreadsheet)
        storeNote(
            dao,
            400L,
            LegacyTitle,
            BlockDocument(
                listOf(
                    HeadingBlock("legacy-heading", 1, listOf(InlineText("Legacy positioned content"))),
                    ParagraphBlock("legacy-body", listOf(InlineText("This row must convert once, remain visible, syncable, and reopen in Document."))),
                ),
            ),
            DocOverlapMode.Overlap,
            DocumentArtifactKind.Document,
            DocLayoutJson.encode(
                mapOf(
                    "legacy-heading" to com.norfold.app.domain.FreeformPlacement(x = 44f, y = 64f, width = 470f, height = 80f),
                    "legacy-body" to com.norfold.app.domain.FreeformPlacement(x = 44f, y = 168f, width = 470f, height = 180f),
                ),
                DocCanvasSpec.a4(),
            ),
        )
        dao.insertWorkspaceObject(workspaceObject(1_000L, 100L, FlowTitle, "Flow corpus", BaseTime + 100))
        dao.insertWorkspaceObject(workspaceObject(1_004L, 101L, "QA Quick Note", "Simple note corpus", BaseTime + 101))
        dao.insertWorkspaceObject(workspaceObject(DocumentObjectId, 200L, DocumentTitle, "Two-page review corpus", BaseTime + 200))
        dao.insertWorkspaceObject(workspaceObject(1_002L, 300L, SpreadsheetTitle, "Workbook corpus", BaseTime + 300))
        dao.insertWorkspaceObject(workspaceObject(1_003L, 400L, LegacyTitle, "Compatibility corpus", BaseTime + 400))
        dao.insertWorkspaceComment(
            WorkspaceCommentEntity(
                id = 2_000L,
                objectId = DocumentObjectId,
                authorUsername = "reviewer",
                authorDisplayName = "Mira Reviewer",
                body = "Please verify the chart caption and overlap order before publishing.",
                createdAt = BaseTime + 500,
                updatedAt = BaseTime + 500,
                workspaceId = WorkspaceId,
            ),
        )
        dao.insertWorkspaceComment(
            WorkspaceCommentEntity(
                id = 2_001L,
                objectId = DocumentObjectId,
                authorUsername = "qa.owner",
                authorDisplayName = "QA Owner",
                body = "Confirmed the source document is retained for fidelity comparison.",
                createdAt = BaseTime + 550,
                updatedAt = BaseTime + 550,
                workspaceId = WorkspaceId,
            ),
        )
        dao.insertWorkspaceObjectHistory(
            WorkspaceObjectHistoryEntity(
                id = 3_000L,
                objectId = DocumentObjectId,
                historyType = WorkspaceHistoryType.Created.name,
                actor = "QA Owner",
                summary = "Created editorial brief",
                afterValue = "Two-page baseline",
                createdAt = BaseTime + 200,
                workspaceId = WorkspaceId,
            ),
        )
        dao.insertWorkspaceObjectHistory(
            WorkspaceObjectHistoryEntity(
                id = 3_001L,
                objectId = DocumentObjectId,
                historyType = WorkspaceHistoryType.Updated.name,
                actor = "Mira Reviewer",
                summary = "Adjusted launch findings",
                beforeValue = "Draft findings",
                afterValue = "Reviewed findings",
                createdAt = BaseTime + 600,
                blockId = "doc-body",
                workspaceId = WorkspaceId,
            ),
        )
        dao.insertWorkspaceActivity(
            WorkspaceActivityEntity(
                id = 4_000L,
                objectId = DocumentObjectId,
                activityType = WorkspaceActivityType.Commented.name,
                actor = "Mira Reviewer",
                title = "Commented on editorial brief",
                detail = "Verify chart caption and overlap order",
                createdAt = BaseTime + 600,
                workspaceId = WorkspaceId,
            ),
        )
        dao.insertWorkspaceActivity(
            WorkspaceActivityEntity(
                id = 4_001L,
                objectId = DocumentObjectId,
                activityType = WorkspaceActivityType.Updated.name,
                actor = "QA Owner",
                title = "Saved named QA baseline",
                detail = "Preserved a deterministic recovery point",
                createdAt = BaseTime + 700,
                workspaceId = WorkspaceId,
            ),
        )
    }

    private suspend fun storeNote(
        dao: com.norfold.app.data.NotesDao,
        id: Long,
        title: String,
        document: BlockDocument,
        mode: DocOverlapMode,
        kind: DocumentArtifactKind,
        layoutJson: String = DocLayoutJson.encode(emptyMap(), DocCanvasSpec.a4()),
    ) {
        dao.insertNote(
            NoteEntity(
                id = id,
                title = title,
                searchText = document.plainText(),
                notebookId = null,
                pinned = id == 100L,
                starred = id == 200L,
                archived = false,
                locked = false,
                createdAt = BaseTime + id,
                updatedAt = BaseTime + id,
                workspaceId = WorkspaceId,
            ),
        )
        val owner = DocumentOwner(DocumentOwnerType.Note, id)
        dao.upsertDocument(
            DocumentEntity(
                id = owner.documentId,
                ownerType = owner.type.storageValue,
                ownerId = id,
                layoutMode = mode.name.lowercase(),
                layoutJson = layoutJson,
                createdAt = BaseTime + id,
                updatedAt = BaseTime + id,
                artifactKind = kind.storageValue,
            ),
        )
        dao.upsertDocumentBlocks(
            document.blocks.mapIndexed { index, block ->
                DocumentBlockEntity(block.id, owner.documentId, index, BlockDocumentJson.encodeBlock(block), BaseTime + id)
            },
        )
    }

    private fun workspaceObject(id: Long, sourceId: Long, title: String, summary: String, time: Long) =
        WorkspaceObjectEntity(
            id = id,
            objectType = WorkspaceObjectType.Note.name,
            sourceId = sourceId,
            title = title,
            summary = summary,
            icon = "note",
            color = 0xFF3D7C45,
            createdAt = time,
            updatedAt = time,
            workspaceId = WorkspaceId,
        )

    private fun flowDocument() = BlockDocument(
        listOf(
            HeadingBlock("flow-title", 1, listOf(InlineText("Field notes — launch readiness"))),
            ParagraphBlock("flow-intro", listOf(InlineText("A deterministic long-form sample with "), BoldInline(listOf(InlineText("mixed inline emphasis"))), InlineText(", a "), LinkInline("https://norfold.app/qa", listOf(InlineText("safe reference link"))), InlineText(", and enough content to inspect wrapping."))),
            HeadingBlock("flow-findings", 2, listOf(InlineText("Findings"))),
            BulletListBlock("flow-list", listOf(ListItem("flow-li-1", listOf(InlineText("Navigation remains predictable"))), ListItem("flow-li-2", listOf(InlineText("Offline edits retain local truth"))))),
            TodoListBlock("flow-todos", listOf(TodoItem("flow-todo-1", true, listOf(InlineText("Review information hierarchy"))), TodoItem("flow-todo-2", false, listOf(InlineText("Certify large text behavior"))))),
            QuoteBlock("flow-quote", listOf(ParagraphBlock("flow-quote-p", listOf(InlineText("Quiet interfaces make the document—not the chrome—the main character."))))),
            CalloutBlock("flow-callout", "note", "QA note", false, listOf(ParagraphBlock("flow-callout-p", listOf(InlineText("This callout must remain readable in light and dark themes."))))),
            ContainerBlock(
                id = "flow-container",
                axis = ContainerAxis.Row,
                weights = listOf(1f, 1f),
                children = listOf(
                    CalloutBlock("flow-container-left", "tip", "Left column", false, listOf(ParagraphBlock("flow-container-left-p", listOf(InlineText("Nested containers preserve reading order."))))),
                    ContainerBlock("flow-container-right", ContainerAxis.Column, listOf(
                        ParagraphBlock("flow-container-right-a", listOf(InlineText("Right column, first item"))),
                        ParagraphBlock("flow-container-right-b", listOf(InlineText("Right column, second item"))),
                    )),
                ),
            ),
            CodeBlock("flow-code", "kotlin", "val result = workspace.verifyLocally()\ncheck(result.isDeterministic)"),
            TableBlock(
                id = "flow-table",
                headers = listOf(cell("Surface"), cell("Status"), cell("Owner")),
                rows = listOf(listOf(cell("Flow"), cell("Ready"), cell("QA")), listOf(cell("Document"), cell("Review"), cell("Owner"))),
            ),
            ImageBlock("flow-image", "android.resource://com.norfold.app/drawable/cover_mountain_path", "Mountain path reference", displayHeightDp = 260f),
            ChartBlock("flow-chart", "{\"mark\":\"line\",\"data\":{\"values\":[{\"day\":\"Mon\",\"score\":74},{\"day\":\"Tue\",\"score\":88},{\"day\":\"Wed\",\"score\":93}]},\"encoding\":{\"x\":{\"field\":\"day\"},\"y\":{\"field\":\"score\",\"type\":\"quantitative\"}}}"),
        ),
    ).withReviewCorpus(noteId = 100L, sourceId = "flow-intro", targetId = "flow-findings")

    private fun boundedDocument(): BlockDocument {
        val pageOne = DocumentPageBlock(
            id = "qa-page-1",
            geometry = PageGeometry(),
            objects = listOf(
                scene("doc-title", SceneObjectKind.TextFrame, SceneGeometry(64f, 58f, 470f, 72f), HeadingBlock("doc-title-content", 1, listOf(InlineText("Norfold editorial brief"))), "Title"),
                scene("doc-body", SceneObjectKind.TextFrame, SceneGeometry(64f, 148f, 470f, 150f), ParagraphBlock("doc-body-content", listOf(InlineText("The bounded document keeps physical pages, stable object identity, and non-destructive editing while presenting calm, editorial controls."))), "Summary"),
                scene("doc-table", SceneObjectKind.Table, SceneGeometry(64f, 330f, 470f, 180f), TableBlock("doc-table-content", headers = listOf(cell("Metric"), cell("Result")), rows = listOf(listOf(cell("Readability"), cell("Pass")), listOf(cell("Reflow"), cell("Inspect")))), "Readiness table"),
                scene("doc-image", SceneObjectKind.Image, SceneGeometry(64f, 548f, 290f, 210f), ImageBlock("doc-image-content", "android.resource://com.norfold.app/drawable/cover_coastal_light", "Coastal launch image"), "Launch image"),
                scene("doc-shape", SceneObjectKind.Shape, SceneGeometry(374f, 568f, 150f, 110f, 8f), ShapeBlock("doc-shape-content", ShapeKind.RoundedRectangle, "READY", 0xFFB8D8B8, 0xFF2F6338), "Status shape"),
            ),
        )
        val pageTwo = DocumentPageBlock(
            id = "qa-page-2",
            geometry = PageGeometry(),
            objects = listOf(
                scene("doc-page2-heading", SceneObjectKind.TextFrame, SceneGeometry(64f, 60f, 470f, 70f), HeadingBlock("doc-page2-heading-content", 2, listOf(InlineText("Evidence and overlap"))), "Evidence heading"),
                scene("doc-overlap-back", SceneObjectKind.Shape, SceneGeometry(90f, 170f, 260f, 180f, 350f), ShapeBlock("doc-overlap-back-content", ShapeKind.Ellipse, "Background", 0xFFDCE9D8, 0xFF6D8E67), "Background overlap", z = 0),
                scene("doc-overlap-front", SceneObjectKind.Shape, SceneGeometry(235f, 230f, 250f, 160f, 9f), ShapeBlock("doc-overlap-front-content", ShapeKind.RoundedRectangle, "Foreground", 0xFF8BB58C, 0xFF2F6338), "Foreground overlap", z = 1),
                scene("doc-chart", SceneObjectKind.Chart, SceneGeometry(64f, 450f, 470f, 260f), ChartBlock("doc-chart-content", "{\"mark\":\"bar\",\"data\":{\"values\":[{\"area\":\"Flow\",\"score\":92},{\"area\":\"Document\",\"score\":86}]},\"encoding\":{\"x\":{\"field\":\"area\"},\"y\":{\"field\":\"score\",\"type\":\"quantitative\"}}}"), "Readiness chart"),
            ),
        )
        val base = BlockDocument(listOf(pageOne, pageTwo))
        val reviewed = base.withReviewCorpus(noteId = 200L, sourceId = "doc-body", targetId = "doc-chart", sceneAnchors = true)
        return reviewed.withInterchangeData(
            InterchangeMetadataCodec.encode(
                InterchangeArtifactMetadata(
                    source = SourceArtifactMetadata(
                        format = ImportFormat.DOCX,
                        originalName = "qa-editorial-brief.docx",
                        mediaType = "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                        byteSize = 48_192,
                        sha256 = "9a7ce20508c205c2b441f0bed8b2dc00d6027e0710c840403c54f45f15fb27d8",
                        pageCount = 2,
                    ),
                    compatibility = CompatibilityMetadata(
                        preservedBytes = 1_024,
                        omittedPaths = listOf("word/vbaProject.bin"),
                    ),
                    fidelity = FidelityReport(
                        format = ImportFormat.DOCX,
                        mode = ImportMode.COMPATIBILITY,
                        intent = ImportIntent.PRESERVE_APPEARANCE,
                        regions = listOf(
                            FidelityRegion("doc-title", "doc-title", "word/document.xml/title", FidelityState.NATIVE, "Heading imported natively"),
                            FidelityRegion("doc-chart", "doc-chart", "word/charts/chart1.xml", FidelityState.APPROXIMATED, "Chart converted to an editable Norfold chart"),
                            FidelityRegion("doc-macro", "doc-macro", "word/vbaProject.bin", FidelityState.UNSUPPORTED, "Active macro content was not imported", editable = false),
                        ),
                        issues = listOf(
                            FidelityIssue("active-content-omitted", "Active macro content was omitted for safety.", FidelitySeverity.WARNING, "doc-macro", "Keep the original file if the macro is required."),
                        ),
                    ),
                ),
            ),
        )
    }

    private fun BlockDocument.withReviewCorpus(
        noteId: Long,
        sourceId: String,
        targetId: String,
        sceneAnchors: Boolean = false,
    ): BlockDocument {
        val artifactId = DocumentOwner(DocumentOwnerType.Note, noteId).documentId
        val reviewer = CommentAuthorRef("qa-reviewer", "Mira Reviewer")
        val owner = CommentAuthorRef("qa-owner", "QA Owner")
        val openThreadId = ThreadId("qa-thread-$noteId-open")
        val resolvedThreadId = ThreadId("qa-thread-$noteId-resolved")
        val sourceAnchor = if (sceneAnchors) {
            RelationshipAnchor.SceneObject(artifactId, "qa-page-1", sourceId)
        } else {
            RelationshipAnchor.Block(artifactId, sourceId)
        }
        val targetAnchor = if (sceneAnchors) {
            RelationshipAnchor.SceneObject(artifactId, "qa-page-2", targetId)
        } else {
            RelationshipAnchor.Block(artifactId, targetId)
        }
        val openAnchor = if (sceneAnchors) {
            CommentAnchor.SceneObject(artifactId, "qa-page-1", sourceId)
        } else {
            CommentAnchor.Text(artifactId, sourceId, 0, 13, "deterministic", suffix = " long-form sample")
        }
        val state = Phase7ArtifactState(
            relationships = listOf(
                Relationship(
                    id = RelationshipId("qa-relationship-$noteId"),
                    source = RelationshipEndpoint(sourceAnchor),
                    target = RelationshipEndpoint(targetAnchor),
                    kind = RelationshipKinds.REFERENCES,
                    label = "Evidence link",
                    description = "Deterministic relationship used by integrated QA.",
                    metadata = RelationshipMetadata(BaseTime + noteId, "qa-owner"),
                ),
            ),
            threads = listOf(
                DiscussionThread(
                    id = openThreadId,
                    anchor = openAnchor,
                    comments = listOf(
                        Comment(CommentId("qa-comment-$noteId-1"), openThreadId, reviewer, BaseTime + noteId + 10, body = "Please verify this evidence before publishing.", mentions = listOf(CommentMention(owner.stableId, "@QA Owner"))),
                        Comment(CommentId("qa-comment-$noteId-2"), openThreadId, owner, BaseTime + noteId + 20, body = "Acknowledged; keeping the thread open for visual review.", replyTo = CommentId("qa-comment-$noteId-1")),
                    ),
                ),
                DiscussionThread(
                    id = resolvedThreadId,
                    anchor = CommentAnchor.Artifact(artifactId, AnchorState.DETACHED),
                    status = CommentStatus.RESOLVED,
                    comments = listOf(Comment(CommentId("qa-comment-$noteId-3"), resolvedThreadId, reviewer, BaseTime + noteId + 30, body = "Resolved baseline copy review.")),
                    resolvedAt = BaseTime + noteId + 40,
                    resolvedBy = owner,
                ),
            ),
            snapshots = listOf(
                ReviewSnapshot(
                    id = ReviewSnapshotId("qa-snapshot-$noteId-baseline"),
                    artifactId = artifactId,
                    createdAt = BaseTime + noteId + 5,
                    label = "QA baseline",
                    contentHash = "a2fc7d6f5df079e0fa6700f899da64e9123dc56bce189e3963799436774c524e",
                    documentPayload = BlockDocumentJson.encode(this),
                    author = owner,
                ),
            ),
        )
        return withRelationshipData(Phase7ArtifactCodec.encode(state))
    }

    private fun scene(
        id: String,
        kind: SceneObjectKind,
        geometry: SceneGeometry,
        content: com.norfold.app.domain.DocumentBlock,
        name: String,
        z: Int = 0,
    ) = SceneObject(id = id, kind = kind, geometry = geometry, z = z, name = name, content = content)

    private fun spreadsheetDocument(): BlockDocument {
        val sheetOneId = SheetId("qa-sheet-1")
        val rows = List(240) { CalculationRow(RowId("qa-row-$it")) }
        val columns = List(8) { CalculationColumn(ColumnId("qa-column-$it"), name = ('A' + it).toString()) }
        fun cellAt(row: Int, column: Int, input: CellInput) = CalculationCell(
            id = CellId("qa-cell-$row-$column"),
            rowId = rows[row].id,
            columnId = columns[column].id,
            input = input,
        )
        val scoreRange = StableCellRange(
            StableCellCoordinate(sheetOneId, rows[1].id, columns[1].id),
            StableCellCoordinate(sheetOneId, rows.last().id, columns[1].id),
        )
        val validationId = ValidationRuleId("qa-score-validation")
        val sheetOne = CalculationSheet(
            id = sheetOneId,
            name = "Launch",
            rowCount = rows.size,
            columnCount = columns.size,
            rows = rows,
            columns = columns,
            frozenRows = 1,
            frozenColumns = 1,
            cells = listOf(
                cellAt(0, 0, CellInput.Text("Area")), cellAt(0, 1, CellInput.Text("Score")), cellAt(0, 2, CellInput.Text("Owner")),
                cellAt(1, 0, CellInput.Text("Flow")), cellAt(1, 1, CellInput.Number("92")).copy(validationRuleIds = listOf(validationId)), cellAt(1, 2, CellInput.Text("Mira")),
                cellAt(2, 0, CellInput.Text("Document")), cellAt(2, 1, CellInput.Number("86")), cellAt(2, 2, CellInput.Text("Sheikh")),
                cellAt(3, 0, CellInput.Text("Spreadsheet")), cellAt(3, 1, CellInput.Number("89")), cellAt(3, 2, CellInput.Text("QA")),
                cellAt(4, 0, CellInput.Text("Average")), cellAt(4, 1, CellInput.Formula("=AVERAGE(B2:B4)")),
                cellAt(5, 0, CellInput.Text("Threshold")), cellAt(5, 1, CellInput.Formula("=Lookups!A2")),
            ) + (6 until rows.size).map { row -> cellAt(row, 0, CellInput.Text("Scenario ${row - 4}")) },
            validations = listOf(
                ValidationRule(validationId, scoreRange, ValidationCondition.NumberRange("0", "100"), inputMessage = "Enter a score from 0 to 100", policy = ValidationPolicy.REJECT),
            ),
            conditionalFormats = listOf(
                ConditionalFormatRule(
                    id = ConditionalRuleId("qa-score-ready"),
                    target = scoreRange,
                    condition = ConditionalCondition.Comparison(ComparisonOperator.GREATER_OR_EQUAL, "90"),
                    priority = 1,
                    formatDelta = CellFormat(fillColor = ColorValue(184, 216, 184)),
                    accessibleDescription = "Ready score: 90 or higher",
                ),
            ),
        )
        val lookupRows = List(10) { CalculationRow(RowId("qa-lookup-row-$it")) }
        val lookupColumns = List(4) { CalculationColumn(ColumnId("qa-lookup-column-$it")) }
        val sheetTwo = CalculationSheet(
            id = SheetId("qa-sheet-2"),
            name = "Lookups",
            rowCount = lookupRows.size,
            columnCount = lookupColumns.size,
            rows = lookupRows,
            columns = lookupColumns,
            cells = listOf(
                CalculationCell(CellId("qa-lookup-1"), lookupRows[0].id, lookupColumns[0].id, CellInput.Text("Threshold")),
                CalculationCell(CellId("qa-lookup-2"), lookupRows[1].id, lookupColumns[0].id, CellInput.Number("85")),
            ),
        )
        val range = StableCellRange(
            StableCellCoordinate(sheetOneId, rows[0].id, columns[0].id),
            StableCellCoordinate(sheetOneId, rows[5].id, columns[3].id),
        )
        val tableId = EmbeddedTableId("qa-table")
        val workbook = CalculationWorkbook(
            id = WorkbookId("qa-workbook"),
            displayName = SpreadsheetTitle,
            sheets = listOf(sheetOne, sheetTwo),
            activeSheetId = sheetOneId,
            namedRanges = listOf(NamedRange(NamedRangeId("qa-readiness-scores"), "ReadinessScores", scoreRange)),
            embeddedTables = listOf(
                EmbeddedTableDefinition(
                    id = tableId,
                    name = "LaunchReadiness",
                    range = range,
                    columns = columns.take(4).mapIndexed { index, column -> EmbeddedTableColumn(column.id, "Column${index + 1}") },
                    headerRow = true,
                ),
            ),
        )
        val table = TableCalculationBridge.persist(TableBlock(id = "qa-spreadsheet-table"), workbook, tableId)
        val chart = ChartBlock(
            id = "qa-spreadsheet-chart",
            vegaLiteSpec = "{\"mark\":\"bar\",\"data\":{\"values\":[{\"area\":\"Flow\",\"score\":92},{\"area\":\"Document\",\"score\":86},{\"area\":\"Spreadsheet\",\"score\":89}]},\"encoding\":{\"x\":{\"field\":\"area\"},\"y\":{\"field\":\"score\",\"type\":\"quantitative\"}}}",
        )
        return BlockDocument(listOf(table, chart))
    }

    private fun cell(text: String) = TableCell(listOf(InlineText(text)))
}
