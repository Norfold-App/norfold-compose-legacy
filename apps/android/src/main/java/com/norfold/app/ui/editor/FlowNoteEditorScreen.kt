package com.norfold.app.ui.editor

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
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
    var title by remember(owner.documentId) { mutableStateOf(storedTitle) }

    val runtime = remember(owner.documentId) {
        FlowHostRuntime(
            controller = FlowEditController(EditTarget(owner.documentId), active.document),
            initialFormFactor = FormFactor(configuration.screenWidthDp, configuration.screenHeightDp),
        )
    }

    val presenter = rememberFlowHostPresenter(runtime) { document ->
        viewModel.updateActiveDocument(owner, title, document, runtime.controller.dirtyBlockIds())
        runtime.controller.markSaved()
    }

    // A remote save arrived: converge block-safely, preserving anything edited locally since.
    LaunchedEffect(active.updatedAt) {
        if (runtime.controller.mergeRemote(active.document)) presenter.refresh()
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
