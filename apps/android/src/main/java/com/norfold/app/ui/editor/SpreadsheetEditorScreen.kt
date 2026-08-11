package com.norfold.app.ui.editor

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.lifecycle.viewModelScope
import com.norfold.app.domain.DocumentOwnerType
import com.norfold.app.data.rememberRecoverySnapshotStore
import com.norfold.app.editor.calculation.SpreadsheetDocumentSession
import com.norfold.app.editor.calculation.SpreadsheetHostRuntime
import com.norfold.app.editor.flags.EditorFeatureFlag
import com.norfold.app.editor.flow.FlowEditController
import com.norfold.app.editor.flow.FlowSaveCoordinator
import com.norfold.app.editor.history.EditTarget
import com.norfold.app.ui.DocsUiState
import com.norfold.app.ui.DocsViewModel
import com.norfold.app.ui.editor.spreadsheet.SpreadsheetHostScreen
import com.norfold.app.ui.editor.guide.rememberEditorInteractionMemory
import kotlinx.coroutines.CompletableDeferred

@Composable
fun SpreadsheetEditorScreen(
    state: DocsUiState,
    viewModel: DocsViewModel,
    modifier: Modifier = Modifier,
    onOpenSidebar: () -> Unit,
) {
    val ownedDocument by viewModel.activeDocument.collectAsState()
    val active = ownedDocument ?: return
    val owner = active.owner
    val tableBlockId = viewModel.spreadsheetTableBlockId
    // The route's own precondition. The sidebar gates the entry on the host flag, but the flag is the
    // product decision, not the document's fitness: a restored or re-entered destination can still arrive
    // at a document whose workbook this host cannot open (no TableBlock, or a stale table id), and
    // [SpreadsheetHostRuntime] treats that as programmer error. Every entry passes through here, so the
    // destination checks the actual precondition rather than trusting that no caller found a way in.
    if (!SpreadsheetHostRuntime.canOpen(active.document, tableBlockId)) {
        LaunchedEffect(Unit) { viewModel.handleBack() }
        return
    }
    val configuration = LocalConfiguration.current
    val guideEnabled = editorFlags.isEnabled(EditorFeatureFlag.GUIDE)
    val historyUiEnabled = editorFlags.isEnabled(EditorFeatureFlag.HISTORY_UI)
    // Restore points only reach the disk when the browser that lets a person manage them exists.
    // Writing durable snapshots a user has no way to see, name or delete would be storage they
    // never agreed to and cannot reclaim.
    val recoveryStore = rememberRecoverySnapshotStore(owner.documentId, historyUiEnabled)
    val interactionMemory = rememberEditorInteractionMemory(guideEnabled)
    val enabledGuideFeatures = remember {
        EditorFeatureFlag.entries.filter(editorFlags::isEnabled).toSet()
    }
    val storedTitle = when (owner.type) {
        DocumentOwnerType.Note -> state.notes.firstOrNull { it.id == owner.id }?.title ?: "Untitled spreadsheet"
        DocumentOwnerType.Task -> state.tasks.firstOrNull { it.id == owner.id }?.title ?: "Untitled spreadsheet"
        DocumentOwnerType.CalendarEvent -> state.calendarEvents.firstOrNull { it.id == owner.id }?.title ?: "Untitled spreadsheet"
    }
    val sessionKey = "${owner.documentId}:${tableBlockId.orEmpty()}"
    val session = remember(sessionKey) {
        viewModel.spreadsheetSessions.open(sessionKey, viewModel.viewModelScope) {
            val runtime = SpreadsheetHostRuntime(
                FlowEditController(
                    EditTarget(owner.documentId),
                    active.document,
                    recoveryStore = recoveryStore,
                ),
                tableBlockId,
                initialFormFactor = com.norfold.app.editor.presentation.FormFactor(
                    configuration.screenWidthDp,
                    configuration.screenHeightDp,
                ),
                memory = interactionMemory,
                guideEnabled = guideEnabled,
                enabledGuideFeatures = enabledGuideFeatures,
                historyUiEnabled = historyUiEnabled,
            )
            SpreadsheetDocumentSession(
                runtime = runtime,
                saves = FlowSaveCoordinator(
                    controller = runtime.controller,
                    persister = { request ->
                        val outcome = CompletableDeferred<Result<Unit>>()
                        val job = viewModel.updateActiveDocument(owner, request.title, request.document, request.dirtyBlockIds)
                        job.invokeOnCompletion { error ->
                            outcome.complete(if (error == null) Result.success(Unit) else Result.failure(error))
                        }
                        outcome.await()
                    },
                ).apply { bindTitle(storedTitle) },
            )
        }
    }
    var title by remember(session) { mutableStateOf(session.saves.title()) }
    LaunchedEffect(session, storedTitle) {
        session.saves.onStoredTitle(storedTitle)
        title = session.saves.title()
    }
    LaunchedEffect(session, active.updatedAt) {
        if (session.runtime.mergeRemote(active.document)) session.saves.requestSave()
    }
    DisposableEffect(session) { onDispose { session.saves.flush() } }

    SpreadsheetHostScreen(
        runtime = session.runtime,
        title = title,
        onTitleChange = { next -> title = next; session.saves.onTitleChanged(next) },
        onOpenSidebar = onOpenSidebar,
        onExit = viewModel::handleBack,
        onChanged = session.saves::requestSave,
        modifier = modifier,
    )
}
