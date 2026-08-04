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
import androidx.lifecycle.viewModelScope
import com.norfold.app.domain.DocumentOwnerType
import com.norfold.app.editor.calculation.SpreadsheetDocumentSession
import com.norfold.app.editor.calculation.SpreadsheetHostRuntime
import com.norfold.app.editor.flags.EditorFeatureFlag
import com.norfold.app.editor.flow.FlowEditController
import com.norfold.app.editor.flow.FlowSaveCoordinator
import com.norfold.app.editor.history.EditTarget
import com.norfold.app.ui.DocsUiState
import com.norfold.app.ui.DocsViewModel
import com.norfold.app.ui.editor.spreadsheet.SpreadsheetHostScreen
import kotlinx.coroutines.CompletableDeferred

@Composable
fun SpreadsheetEditorScreen(
    state: DocsUiState,
    viewModel: DocsViewModel,
    modifier: Modifier = Modifier,
    onOpenSidebar: () -> Unit,
) {
    // The route's own gate. Every *entry* is flag-gated too, but a restored destination or a deep link
    // arrives without passing through one of those, so the destination refuses on its own authority
    // rather than trusting that no caller found a way in.
    if (!editorFlags.isEnabled(EditorFeatureFlag.SPREADSHEET_HOST)) {
        LaunchedEffect(Unit) { viewModel.handleBack() }
        return
    }
    val ownedDocument by viewModel.activeDocument.collectAsState()
    val active = ownedDocument ?: return
    val owner = active.owner
    val tableBlockId = viewModel.spreadsheetTableBlockId
    val storedTitle = when (owner.type) {
        DocumentOwnerType.Note -> state.notes.firstOrNull { it.id == owner.id }?.title ?: "Untitled spreadsheet"
        DocumentOwnerType.Task -> state.tasks.firstOrNull { it.id == owner.id }?.title ?: "Untitled spreadsheet"
        DocumentOwnerType.CalendarEvent -> state.calendarEvents.firstOrNull { it.id == owner.id }?.title ?: "Untitled spreadsheet"
    }
    val sessionKey = "${owner.documentId}:${tableBlockId.orEmpty()}"
    val session = remember(sessionKey) {
        viewModel.spreadsheetSessions.open(sessionKey, viewModel.viewModelScope) {
            val runtime = SpreadsheetHostRuntime(
                FlowEditController(EditTarget(owner.documentId), active.document),
                tableBlockId,
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
