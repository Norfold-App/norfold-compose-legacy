package com.norfold.app.ui.editor

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewModelScope
import com.norfold.app.domain.DocumentOwnerType
import com.norfold.app.editor.document.DocumentHostRuntime
import com.norfold.app.editor.document.DocumentSceneController
import com.norfold.app.editor.document.DocumentSession
import com.norfold.app.editor.flags.EditorFeatureFlag
import com.norfold.app.editor.flow.FlowSaveCoordinator
import com.norfold.app.editor.history.EditTarget
import com.norfold.app.editor.presentation.FormFactor
import com.norfold.app.editor.recovery.RecoverySnapshotManager
import com.norfold.app.ui.DocsUiState
import com.norfold.app.ui.DocsViewModel
import com.norfold.app.ui.editor.document.DocumentHostScreen
import com.norfold.app.ui.editor.document.rememberDocumentHostPresenter
import com.norfold.app.ui.screens.EditorSurface
import com.norfold.app.ui.screens.StructuredBlockEditorHost
import kotlinx.coroutines.CompletableDeferred

/**
 * Fixed-page Document entry point.
 *
 * Two genuinely separate hosts sit behind this one function: the legacy [StructuredBlockEditorHost] in its
 * `EditorSurface.Document` configuration, and the new DocumentHost built on the Phase-0 shared engines.
 * DocumentHost is not a wrapper — it renders its own bounded-page surface from [DocumentHostRuntime] and
 * shares no code path with the legacy composable, which is what makes the flag a real switch between two
 * implementations rather than another layout branch inside one host.
 *
 * [EditorFeatureFlag.DOCUMENT_HOST] defaults **off**, so this resolves to the legacy editor until an
 * internal build or a test flips it. The legacy Document editor is deliberately not removed in Phase 2.
 */
@Composable
fun BoundedDocumentEditorScreen(
    state: DocsUiState,
    viewModel: DocsViewModel,
    modifier: Modifier = Modifier,
    onOpenSidebar: () -> Unit,
) {
    if (editorFlags.isEnabled(EditorFeatureFlag.DOCUMENT_HOST)) {
        DocumentHostRoute(state, viewModel, modifier, onOpenSidebar)
    } else {
        StructuredBlockEditorHost(
            state = state,
            viewModel = viewModel,
            modifier = modifier,
            onOpenSidebar = onOpenSidebar,
            surface = EditorSurface.Document,
        )
    }
}

/**
 * Binds one bounded document to a [DocumentHostRuntime] and persists what it produces.
 *
 * The save contract is identical to Flow's, and deliberately the *same coordinator*: everything durable —
 * scene edits, page structure, title renames, the flush on close — goes through one [FlowSaveCoordinator],
 * which debounces, keeps exactly one write in flight for this document, retries a failure on bounded
 * backoff without waiting for another edit, and advances the persisted baseline only on evidence that the
 * repository accepted a specific snapshot against a baseline that is still current. It reaches the
 * controller through [com.norfold.app.editor.history.PersistableEditController], so it carries no Flow
 * semantics into this host.
 *
 * **Migration.** A legacy document arriving here is not a bounded scene yet. It is converted once, on open,
 * protected by a recovery snapshot taken *before* any work starts — and the conversion is refused rather
 * than forced when it would discard content, so the owner's document is never rewritten to make the new
 * host happy. The candidate replaces the workspace document through the canonical History Engine, so the
 * conversion is one undoable transaction like any other edit.
 *
 * **Ownership.** The runtime and its coordinator live in the ViewModel's registry, not in a `remember`
 * here: a retry loop must outlive the screen, and anything keyed to the composition is rebuilt on
 * recreation, which used to produce a second writer for the same document with its own baseline.
 */
@Composable
private fun DocumentHostRoute(
    state: DocsUiState,
    viewModel: DocsViewModel,
    modifier: Modifier,
    onOpenSidebar: () -> Unit,
) {
    val ownedDocument by viewModel.activeDocument.collectAsState()
    val active = ownedDocument ?: return
    val owner = active.owner
    val configuration = LocalConfiguration.current

    val storedTitle = when (owner.type) {
        DocumentOwnerType.Note -> state.notes.firstOrNull { it.id == owner.id }?.title ?: "Untitled document"
        DocumentOwnerType.Task -> state.tasks.firstOrNull { it.id == owner.id }?.title ?: "Untitled task"
        DocumentOwnerType.CalendarEvent ->
            state.calendarEvents.firstOrNull { it.id == owner.id }?.title ?: "Untitled event"
    }

    val session = remember(owner.documentId) {
        viewModel.documentSessions.open(owner.documentId, viewModel.viewModelScope) {
            val controller = DocumentSceneController(EditTarget(owner.documentId), active.document)
            val runtime = DocumentHostRuntime(
                controller = controller,
                initialFormFactor = FormFactor(configuration.screenWidthDp, configuration.screenHeightDp),
                fontRegistry = com.norfold.app.ui.fonts.OfflineFontLibrary.registry,
            )
            // The snapshot manager captures the controller's *own* workspace: a snapshot over any other
            // workspace would restore something the editor is not looking at, and report success doing it.
            runtime.migrateLegacyDocument(
                layout = active.freeformLayout,
                canvas = active.canvasSpec,
                mode = active.layoutMode,
                recovery = RecoverySnapshotManager(controller.workspace),
            )
            DocumentSession(
                runtime = runtime,
                saves = FlowSaveCoordinator(
                    controller = controller,
                    // `updateActiveDocument` returns a Job that completes whether or not the write threw,
                    // and `join()` returns Unit either way, so it is not success evidence; only the
                    // completion handler's exception (or its absence) distinguishes accepted from rejected.
                    persister = { request ->
                        val outcome = CompletableDeferred<Result<Unit>>()
                        val job = viewModel.updateActiveDocument(
                            owner,
                            request.title,
                            request.document,
                            request.dirtyBlockIds,
                        )
                        job.invokeOnCompletion { error ->
                            outcome.complete(
                                if (error == null) Result.success(Unit) else Result.failure(error),
                            )
                        }
                        outcome.await()
                    },
                ).apply { bindTitle(storedTitle) },
            )
        }
    }
    val runtime = session.runtime
    val saves = session.saves

    // A refused migration means this runtime has no bounded document. Rendering it anyway would show the
    // owner a blank page where their content used to be, so the *usable* editor is what they get: the legacy
    // host opens the original document unchanged. Nothing here sets `allowLossy` — forcing a conversion that
    // was refused is a decision only the owner of the content can make.
    if (runtime.requiresLegacyFallback()) {
        val report = runtime.migrationRefusal
        var explained by remember(session) { mutableStateOf(false) }
        Column(modifier) {
            // Shown above the working editor, dismissible, and never a blocking dialog: the owner came here
            // to write, and the document they came to write in is already open below.
            if (!explained && report != null) {
                Surface(color = MaterialTheme.colorScheme.secondaryContainer) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "Page layout was not converted. ${report.refusalDetail()}",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { explained = true }) { Text("OK") }
                    }
                }
            }
            StructuredBlockEditorHost(
                state = state,
                viewModel = viewModel,
                modifier = Modifier,
                onOpenSidebar = onOpenSidebar,
                surface = EditorSurface.Document,
            )
        }
        return
    }

    // The coordinator holds the authoritative title; this is only the Compose mirror that makes it draw.
    var title by remember(session) { mutableStateOf(saves.title()) }

    LaunchedEffect(session, storedTitle) {
        saves.onStoredTitle(storedTitle)
        title = saves.title()
    }

    val presenter = rememberDocumentHostPresenter(runtime) { saves.requestSave() }

    // A migration performed at session creation left the document dirty against its baseline; the first
    // pass persists it. Asking on every recomposition would be wrong, but asking once here is what makes
    // the converted document durable rather than existing only in memory until the next edit.
    LaunchedEffect(session) {
        if (runtime.controller.hasUnsavedChanges()) saves.requestSave()
    }

    // Leaving the screen must not drop work still inside the debounce window. This is a flush, not a
    // close: the session belongs to the ViewModel, and a rotation disposes this composable without the
    // document having been left.
    DisposableEffect(session) {
        onDispose { saves.flush() }
    }

    DocumentHostScreen(
        presenter = presenter,
        title = title,
        onTitleChange = { next ->
            title = next
            saves.onTitleChanged(next)
        },
        onOpenSidebar = onOpenSidebar,
        onOpenTableInSpreadsheet = viewModel::openTableInSpreadsheet,
        onExit = viewModel::handleBack,
        modifier = modifier,
    )
}
