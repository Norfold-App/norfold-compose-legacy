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
import com.norfold.app.editor.flags.EditorFeatureFlag
import com.norfold.app.editor.flags.EditorFeatureFlags
import com.norfold.app.editor.flow.FlowDocumentSession
import com.norfold.app.editor.flow.FlowEditController
import com.norfold.app.editor.flow.FlowHostRuntime
import com.norfold.app.editor.flow.FlowSaveCoordinator
import com.norfold.app.editor.history.EditTarget
import com.norfold.app.editor.presentation.FormFactor
import com.norfold.app.ui.DocsUiState
import com.norfold.app.ui.DocsViewModel
import com.norfold.app.ui.editor.flow.FlowHostScreen
import com.norfold.app.ui.editor.flow.rememberFlowHostPresenter
import com.norfold.app.ui.screens.EditorSurface
import com.norfold.app.ui.screens.StructuredBlockEditorHost
import kotlinx.coroutines.CompletableDeferred

/**
 * Process-local editor flags. Every flag defaults **off**, so this resolves to the legacy host until an
 * internal build or a test flips [EditorFeatureFlag.FLOW_HOST] — the parallel-host arrangement Phase 0
 * established, with no user-visible change while Phase 1 is in progress.
 *
 * Tests can inject a custom instance via [setEditorFlagsForTesting]. This is not exposed to production code.
 */
private var editorFlags: EditorFeatureFlags = EditorFeatureFlags.productionDefaults()

/**
 * **Test/internal-only injection path.** Allows instrumentation tests to enable [EditorFeatureFlag.FLOW_HOST]
 * without changing the production default. Not accessible from production builds.
 *
 * Call before the first composition of [FlowNoteEditorScreen]. Resets to production defaults if [flags] is null.
 */
@Suppress("TestOnlyProblems") // This annotation documents intent; enforcement is via code-review only
internal fun setEditorFlagsForTesting(flags: EditorFeatureFlags?) {
    editorFlags = flags ?: EditorFeatureFlags.productionDefaults()
}

/**
 * The Notes entry point.
 *
 * Two genuinely separate hosts sit behind this one function: the legacy [StructuredBlockEditorHost], and the
 * new FlowHost built on the Phase-0 shared engines. FlowHost is *not* a wrapper — it renders its own surface
 * from [FlowHostRuntime] and shares no code path with the legacy composable, which is what makes the flag a
 * real switch between two implementations rather than a cosmetic branch inside one.
 */
@Composable
fun FlowNoteEditorScreen(
    state: DocsUiState,
    viewModel: DocsViewModel,
    modifier: Modifier = Modifier,
    onOpenSidebar: () -> Unit,
) {
    if (editorFlags.isEnabled(EditorFeatureFlag.FLOW_HOST)) {
        FlowHostRoute(state, viewModel, modifier, onOpenSidebar)
    } else {
        StructuredBlockEditorHost(
            state = state,
            viewModel = viewModel,
            modifier = modifier,
            onOpenSidebar = onOpenSidebar,
            surface = EditorSurface.FlowNote,
        )
    }
}

/**
 * Binds one note to a [FlowHostRuntime] and persists what it produces.
 *
 * The controller derives dirty/deleted block ids by diffing against the last-saved document, which is exactly
 * the shape the repository writes (one Room row per top-level block), so saving needs no bookkeeping here.
 *
 * **The save contract.** Everything durable goes through one [FlowSaveCoordinator]: body edits, structural
 * edits, deletions, title renames, and the flush when the editor closes. The coordinator debounces, serializes
 * (one write in flight for this document, ever), retries a failure on bounded backoff without waiting for
 * another keystroke, and advances the persisted baseline only on evidence the repository accepted a specific
 * snapshot against a baseline that is still current.
 *
 * **Ownership.** The runtime and its coordinator live in the ViewModel's [FlowSessionRegistry], not in a
 * `remember` here. A retry loop must outlive the screen — that is when it matters most — but anything keyed to
 * the composition is rebuilt on recreation, so a rotation during a retry used to produce a second writer for
 * the same document, with its own baseline. Looking the session up instead means recreation rebinds to the
 * live writer, and a replacement is only ever built once the previous one has retired and is inert.
 *
 * Process recreation: accepted content is durable in Room, the pending title is held by the session (which is
 * why it needs no `rememberSaveable` shadow that could disagree with it), and the idle auto-save bounds how
 * much uncommitted work a process death can take. Transient state (open surfaces, selection, caret, in-progress
 * drag) is deliberately *not* restored — reviving a drag source or a caret pointing at a block id that a remote
 * merge has since removed is how you corrupt canonical content.
 */
@Composable
private fun FlowHostRoute(
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
        DocumentOwnerType.Note -> state.notes.firstOrNull { it.id == owner.id }?.title ?: "Untitled note"
        DocumentOwnerType.Task -> state.tasks.firstOrNull { it.id == owner.id }?.title ?: "Untitled task"
        DocumentOwnerType.CalendarEvent ->
            state.calendarEvents.firstOrNull { it.id == owner.id }?.title ?: "Untitled event"
    }

    val session = remember(owner.documentId) {
        viewModel.flowSessions.open(owner.documentId, viewModel.viewModelScope) {
            val runtime = FlowHostRuntime(
                controller = FlowEditController(EditTarget(owner.documentId), active.document),
                initialFormFactor = FormFactor(configuration.screenWidthDp, configuration.screenHeightDp),
            )
            FlowDocumentSession(
                runtime = runtime,
                saves = FlowSaveCoordinator(
                    controller = runtime.controller,
                    // The persistence seam is the existing repository path, awaited properly.
                    // `updateActiveDocument` returns a Job that completes whether or not the write threw, and
                    // `join()` returns Unit either way, so it is not success evidence; only the completion
                    // handler's exception (or its absence) distinguishes an accepted write from a rejected one.
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

    // The coordinator holds the authoritative title; this is only the Compose mirror that makes it draw. It is
    // re-seeded from the session, never from a separate saved-state copy, so a recreation cannot revive an
    // older title and then persist it over a newer rename.
    var title by remember(session) { mutableStateOf(saves.title()) }

    // The stored title is evidence of what is persisted, so it always moves the baseline — but it must not
    // discard a rename the user has typed and the repository has not accepted yet. The coordinator applies
    // local-dirty-wins, identically to block content; the mirror follows whatever it decides.
    LaunchedEffect(session, storedTitle) {
        saves.onStoredTitle(storedTitle)
        title = saves.title()
    }

    // The presenter republishes the snapshot; it deliberately does not persist. See the save contract above.
    val presenter = rememberFlowHostPresenter(runtime) { saves.requestSave() }

    // A remote save arrived: converge block-safely, preserving anything edited locally since. The controller
    // baselines on the remote snapshot rather than the merge result, so protected local edits stay dirty and
    // the coordinator's next pass sends them.
    LaunchedEffect(session, active.updatedAt) {
        if (runtime.controller.mergeRemote(active.document)) presenter.refresh()
        // Convergence can leave content dirty (protected local edits) or clean (remote already had them).
        // Either way the coordinator decides, from evidence, whether a write is needed.
        saves.requestSave()
    }

    // Leaving the screen must not drop work still inside the debounce window. This is a flush, not a close:
    // the session belongs to the ViewModel, and a rotation disposes this composable without the document
    // having been left. Ending the session is the registry's call, once the document is genuinely closed.
    DisposableEffect(session) {
        onDispose { saves.flush() }
    }

    FlowHostScreen(
        presenter = presenter,
        title = title,
        onTitleChange = { next ->
            title = next
            // Title typing enters the same pipeline as content: debounced, serialized, retried on failure.
            // The coordinator holds the authoritative value, so there is one answer to "what is this called".
            saves.onTitleChanged(next)
        },
        onOpenSidebar = onOpenSidebar,
        onExit = viewModel::handleBack,
        modifier = modifier,
    )
}
