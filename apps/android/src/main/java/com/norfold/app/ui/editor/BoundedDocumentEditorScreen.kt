package com.norfold.app.ui.editor

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.norfold.app.ui.DocsUiState
import com.norfold.app.ui.DocsViewModel
import com.norfold.app.ui.screens.EditorSurface
import com.norfold.app.ui.screens.StructuredBlockEditorHost

/** Fixed-page Document entry point over the shared structured-document command engine. */
@Composable
fun BoundedDocumentEditorScreen(
    state: DocsUiState,
    viewModel: DocsViewModel,
    modifier: Modifier = Modifier,
    onOpenSidebar: () -> Unit,
) {
    StructuredBlockEditorHost(
        state = state,
        viewModel = viewModel,
        modifier = modifier,
        onOpenSidebar = onOpenSidebar,
        surface = EditorSurface.Document,
    )
}
