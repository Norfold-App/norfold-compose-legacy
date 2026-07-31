package com.norfold.app.ui.editor.style

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import com.norfold.app.data.FontImportResult
import com.norfold.app.data.FontImportStore
import com.norfold.app.domain.style.FontIdentity
import com.norfold.app.ui.fonts.OfflineFontLibrary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Android document-picker bridge for private, validated TTF/OTF import. */
@Composable
fun rememberFontImportLauncher(onResult: (FontImportResult) -> Unit): () -> Unit {
    val context = LocalContext.current
    val store = remember(context) { FontImportStore(context) }
    val scope = rememberCoroutineScope()
    val latest = rememberUpdatedState(onResult)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val result = withContext(Dispatchers.IO) { store.import(uri) }
            if (result.entryOrNull != null) {
                store.records().forEach(OfflineFontLibrary::install)
            }
            latest.value(result)
        }
    }
    return { launcher.launch(arrayOf("font/ttf", "font/otf", "application/x-font-ttf", "application/x-font-opentype")) }
}

/** Removes only a private imported family; built-in/system families cannot enter this path. */
@Composable
fun rememberImportedFontRemover(onRemoved: (FontIdentity, Boolean) -> Unit): (FontIdentity) -> Unit {
    val context = LocalContext.current
    val store = remember(context) { FontImportStore(context) }
    val scope = rememberCoroutineScope()
    val latest = rememberUpdatedState(onRemoved)
    return { identity ->
        if (identity.source == com.norfold.app.domain.style.FontSource.IMPORTED) {
            scope.launch {
                val removed = withContext(Dispatchers.IO) { store.remove(identity) > 0 }
                if (removed) OfflineFontLibrary.uninstall(identity)
                latest.value(identity, removed)
            }
        }
    }
}
