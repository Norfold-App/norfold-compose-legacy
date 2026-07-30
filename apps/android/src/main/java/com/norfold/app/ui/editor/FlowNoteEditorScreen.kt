package com.norfold.app.ui.editor

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import com.norfold.app.domain.DocumentOwner
import com.norfold.app.domain.DocumentOwnerType
import com.norfold.app.editor.flags.EditorFeatureFlag
import com.norfold.app.editor.flags.EditorFeatureFlags
import com.norfold.app.editor.flow.FlowEditController
import com.norfold.app.editor.flow.FlowHostRuntime
import com.norfold.app.editor.history.EditTarget
import com.norfold.app.editor.presentation.FormFactor
import com.norfold.app.ui.DocsUiState
import com.norfold.app.ui.DocsViewModel
import com.norfold.app.ui.editor.flow.FlowHostScreen
import com.norfold.app.ui.editor.flow.rememberFlowHostPresenter
import com.norfold.app.ui.screens.EditorSurface
import com.norfold.app.ui.screens.StructuredBlockEditorHost
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.withContext

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
 * **The save contract.** `updateActiveDocument` launches a coroutine and returns immediately, so calling it is
 * a *request* to persist, not proof of persistence. Only one place saves — the debounced effect below — and it
 * clears the dirty set strictly after that request has completed without throwing. Saving from the presenter
 * callback as well would fire on every keystroke, which both defeats the debounce and clears dirty state
 * before Room has accepted anything: a process death in that window would treat unsaved work as durable.
 * A failed save leaves the dirty ids intact, so the next tick retries them rather than losing them.
 *
 * Process recreation: accepted content is durable in Room, the title survives via `rememberSaveable`, and the
 * idle auto-save bounds how much uncommitted work a process death can take. Transient state (open surfaces,
 * selection, caret, in-progress drag) is deliberately *not* restored — reviving a drag source or a caret
 * pointing at a block id that a remote merge has since removed is how you corrupt canonical content.
 */
@OptIn(FlowPreview::class)
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

    // rememberSaveable ensures title survives process recreation
    var title by rememberSaveable(owner.documentId) { mutableStateOf(storedTitle) }

    val runtime = remember(owner.documentId) {
        FlowHostRuntime(
            controller = FlowEditController(EditTarget(owner.documentId), active.document),
            initialFormFactor = FormFactor(configuration.screenWidthDp, configuration.screenHeightDp),
        )
    }

    // The presenter republishes the snapshot; it deliberately does not persist. See the save contract above.
    val presenter = rememberFlowHostPresenter(runtime) { }

    // A remote save arrived: converge block-safely, preserving anything edited locally since.
    LaunchedEffect(active.updatedAt) {
        if (runtime.controller.mergeRemote(active.document)) presenter.refresh()
    }

    // Debounced on an idle gap rather than counting revisions: `revision` also advances for selection and
    // surface changes, so a counter would both skip dirty moments and fire on clean ones. The autosave effect
    // must read the latest title without restarting the debounce every time it changes — that's what
    // `rememberUpdatedState` exists for (correction item 7).
    val latestTitle by rememberUpdatedState(title)
    LaunchedEffect(owner.documentId) {
        snapshotFlow { presenter.state.revision }
            .debounce(AUTO_SAVE_IDLE_MS)
            .collect { saveIfDirty(viewModel, owner, latestTitle, runtime) }
    }

    // Leaving the editor must not drop work still inside the debounce window. Runs on the ViewModel's scope
    // via the same request path, so a save in flight when this composable goes away still completes. The
    // dispose handler also reads the latest title via `rememberUpdatedState`, for the same reason.
    val latestTitleOnDispose by rememberUpdatedState(title)
    DisposableEffect(owner.documentId) {
        onDispose {
            val dirtyIds = runtime.controller.dirtyBlockIds()
            val deletedIds = runtime.controller.deletedBlockIds()
            if (dirtyIds.isNotEmpty() || deletedIds.isNotEmpty()) {
                viewModel.updateActiveDocument(owner, latestTitleOnDispose, runtime.controller.document(), dirtyIds)
            }
        }
    }

    FlowHostScreen(
        presenter = presenter,
        title = title,
        onTitleChange = { next ->
            title = next
            viewModel.updateActiveDocument(owner, next, runtime.controller.document(), emptySet())
        },
        onOpenSidebar = onOpenSidebar,
        onExit = viewModel::handleBack,
        modifier = modifier,
    )
}

/**
 * Persists the document if anything is dirty or deleted, and clears the baseline only once persistence succeeds.
 *
 * The ids are captured *before* awaiting, so edits made while the write is in flight stay dirty and are picked
 * up by the next tick instead of being silently marked saved along with it. A deletion-only change (empty dirty
 * set, non-empty deleted) must still schedule persistence — `persistDocument` derives removals from the document
 * itself, so the write path is the same whether the change is an edit, a deletion, or both.
 *
 * The Job the ViewModel returns completes whether or not the repository threw, and `join()` returns Unit either
 * way, so it is not success evidence. Awaiting the Job and then checking `isCompleted && !isCancelled` would
 * still not distinguish "wrote successfully" from "threw an exception inside the launch". Instead, attach an
 * `invokeOnCompletion` handler that can see the exception (or its absence), and suspend on a `CompletableDeferred`
 * the handler resolves once it knows the outcome. A failed write leaves the dirty/deleted baseline intact so the
 * next tick retries rather than losing the change.
 */
private suspend fun saveIfDirty(
    viewModel: DocsViewModel,
    owner: DocumentOwner,
    title: String,
    runtime: FlowHostRuntime,
) {
    val dirtyIds = runtime.controller.dirtyBlockIds()
    val deletedIds = runtime.controller.deletedBlockIds()
    if (dirtyIds.isEmpty() && deletedIds.isEmpty()) return
    val document = runtime.controller.document()
    val saved = withContext(NonCancellable) {
        val result = CompletableDeferred<Result<Unit>>()
        val job = viewModel.updateActiveDocument(owner, title, document, dirtyIds)
        job.invokeOnCompletion { error ->
            result.complete(if (error == null) Result.success(Unit) else Result.failure(error))
        }
        result.await()
    }
    if (saved.isSuccess) runtime.controller.markSaved(document)
}

/** Idle gap after the last edit before an auto-save fires, bounding what a process death can take. */
private const val AUTO_SAVE_IDLE_MS = 1_500L
