package com.norfold.app.ui.editor

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
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
import com.norfold.app.editor.document.DocumentHostRuntime
import com.norfold.app.editor.document.DocumentSceneController
import com.norfold.app.editor.document.DocumentSession
import com.norfold.app.data.rememberRecoverySnapshotStore
import com.norfold.app.editor.flags.EditorFeatureFlag
import com.norfold.app.ui.editor.guide.rememberEditorInteractionMemory
import com.norfold.app.editor.flow.FlowSaveCoordinator
import com.norfold.app.editor.history.EditTarget
import com.norfold.app.editor.interchange.InterchangeImportService
import com.norfold.app.editor.relationship.CommentAuthorRef
import com.norfold.app.editor.relationship.CommentMention
import com.norfold.app.editor.presentation.FormFactor
import com.norfold.app.ui.DocsUiState
import com.norfold.app.ui.DocsViewModel
import com.norfold.app.ui.editor.document.DocumentHostScreen
import com.norfold.app.ui.editor.document.UnsupportedDocumentRecovery
import com.norfold.app.ui.editor.document.rememberDocumentHostPresenter
import com.norfold.app.ui.editor.interchange.InterchangeHostBridge
import kotlinx.coroutines.CompletableDeferred

/**
 * The fixed-page Document entry point.
 *
 * DocumentHost is the only Document host. It renders its own bounded-page surface from
 * [DocumentHostRuntime] over the shared engines; there is no second implementation to choose between and
 * no way for a person to select one, because a product is not a preference.
 *
 * This function stays as the route rather than collapsing into [DocumentHostRoute] so that every entry —
 * the destination graph, the two multi-pane layouts, a restored destination — passes through one place
 * that binds a document to a host.
 */
@Composable
fun BoundedDocumentEditorScreen(
    state: DocsUiState,
    viewModel: DocsViewModel,
    modifier: Modifier = Modifier,
    onOpenSidebar: () -> Unit,
) {
    DocumentHostRoute(state, viewModel, modifier, onOpenSidebar)
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
 * conversion is one undoable transaction like any other edit. A refusal routes to
 * [UnsupportedDocumentRecovery], which reads the untouched original — never a blank page, never a second
 * editor.
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
        DocumentOwnerType.Note -> state.notes.firstOrNull { it.id == owner.id }?.title ?: "Untitled document"
        DocumentOwnerType.Task -> state.tasks.firstOrNull { it.id == owner.id }?.title ?: "Untitled task"
        DocumentOwnerType.CalendarEvent ->
            state.calendarEvents.firstOrNull { it.id == owner.id }?.title ?: "Untitled event"
    }

    val session = remember(owner.documentId) {
        viewModel.documentSessions.open(owner.documentId, viewModel.viewModelScope) {
            val controller = DocumentSceneController(
                EditTarget(owner.documentId),
                active.document,
                recoveryStore = recoveryStore,
            )
            val runtime = DocumentHostRuntime(
                controller = controller,
                initialFormFactor = FormFactor(configuration.screenWidthDp, configuration.screenHeightDp),
                fontRegistry = com.norfold.app.ui.fonts.OfflineFontLibrary.registry,
                chartStudioEnabled = editorFlags.isEnabled(EditorFeatureFlag.CHART_STUDIO),
                relationshipsEnabled = editorFlags.isEnabled(EditorFeatureFlag.RELATIONSHIPS),
                importExportRawEnabled = editorFlags.isEnabled(EditorFeatureFlag.IMPORT_EXPORT_RAW),
                memory = interactionMemory,
                guideEnabled = guideEnabled,
                enabledGuideFeatures = enabledGuideFeatures,
                historyUiEnabled = historyUiEnabled,
            )
            // The controller's *own* manager, not a fresh one over the same workspace. A separate manager
            // would default to an in-memory store, so the pre-image taken before an irreversible migration —
            // the single most valuable restore point this app takes — would be written where nothing can
            // ever list or restore it.
            runtime.migrateLegacyDocument(
                layout = active.freeformLayout,
                canvas = active.canvasSpec,
                mode = active.layoutMode,
                recovery = controller.recovery(),
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

    // A refused migration means this runtime has no bounded document, so there is nothing here to draw and
    // drawing it anyway would show a blank page where the owner's content used to be. The recovery surface
    // reads the untouched original instead: readable, selectable, exportable, and specific about what
    // stopped. Nothing on it sets `allowLossy` — forcing a conversion that was refused is a decision only
    // the owner of the content can make, and not one to offer at the moment of failure.
    val refusal = runtime.migrationRefusal
    if (refusal != null && runtime.requiresRecoverySurface()) {
        UnsupportedDocumentRecovery(
            title = storedTitle,
            // The controller's document, not the report's: on a refusal the candidate was never committed,
            // so this is the original exactly as it was found on disk.
            document = runtime.controller.document(),
            report = refusal,
            onOpenSidebar = onOpenSidebar,
            onExit = viewModel::handleBack,
            modifier = modifier,
        )
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

    val currentMember = state.workspaceMembers.firstOrNull { it.username == state.settings.syncUserName }
    Box(modifier) {
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
            currentAuthor = CommentAuthorRef(
                stableId = currentMember?.userId ?: state.settings.syncUserName.ifBlank { "local" },
                displayName = currentMember?.displayName?.takeIf(String::isNotBlank)
                    ?: state.settings.syncPublicName.ifBlank { state.settings.syncUserName.ifBlank { "You" } },
            ),
            mentionCandidates = state.workspaceMembers.map {
                CommentMention(it.userId, it.displayName.ifBlank { it.username })
            },
            modifier = Modifier.fillMaxSize(),
        )
        if (editorFlags.isEnabled(EditorFeatureFlag.IMPORT_EXPORT_RAW)) {
            InterchangeHostBridge(
                title = title,
                artifactId = owner.documentId,
                state = presenter.state.interchange,
                mutateRuntime = { action -> presenter.act { interchange.action() } },
                export = { format, resolver -> runtime.interchange.export(title, format, resolver) },
                applyImport = { request, prepared ->
                    presenter.act { InterchangeImportService.apply(controller, request, prepared) }
                },
                beforeOpenRawSource = { runtime.controller.snapshotBeforeRawSource("Before opening Raw Source") },
                applyRawSource = { draft ->
                    presenter.act { InterchangeImportService.applyRawSource(controller, draft, snapshotRequired = false) }
                },
                navigateToRegion = { target ->
                    presenter.act {
                        val page = pages().firstOrNull { it.id == target }
                        val objectId = pages().asSequence().flatMap { it.objects.asSequence() }
                            .firstOrNull { it.id == target || it.content.id == target }?.id
                        when {
                            page != null -> selectPage(page.id)
                            objectId != null -> selectObjects(listOf(objectId))
                        }
                    }
                },
                guideRuntime = runtime.guide,
                guideHost = com.norfold.app.editor.guide.GuideHost.DOCUMENT,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}
