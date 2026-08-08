package com.norfold.app.ui.editor.interchange

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import com.norfold.app.domain.BlockDocument
import com.norfold.app.domain.CalloutBlock
import com.norfold.app.domain.ContainerBlock
import com.norfold.app.domain.DocumentBlock
import com.norfold.app.domain.DocumentPageBlock
import com.norfold.app.domain.FileBlock
import com.norfold.app.domain.ImageBlock
import com.norfold.app.domain.QuoteBlock
import com.norfold.app.editor.interchange.ExportFormat
import com.norfold.app.editor.interchange.ExportResult
import com.norfold.app.editor.interchange.ImportIntent
import com.norfold.app.editor.interchange.ImportMode
import com.norfold.app.editor.interchange.ImportPreflightService
import com.norfold.app.editor.interchange.ImportRequest
import com.norfold.app.editor.interchange.ImportResult
import com.norfold.app.editor.interchange.ImportSecurityLimits
import com.norfold.app.editor.interchange.InterchangeAction
import com.norfold.app.editor.interchange.InterchangeImportService
import com.norfold.app.editor.interchange.InterchangeRuntime
import com.norfold.app.editor.interchange.InterchangeUiState
import com.norfold.app.editor.interchange.RawSourceDraft
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Android boundary for the shared pure-Kotlin interchange runtime. No import parser depends on this UI. */
@Composable
fun InterchangeHostBridge(
    title: String,
    artifactId: String,
    state: InterchangeUiState,
    mutateRuntime: (InterchangeRuntime.() -> Unit) -> Unit,
    export: (ExportFormat, (String) -> ByteArray?) -> ExportResult,
    applyImport: (ImportRequest, ImportResult) -> Boolean,
    beforeOpenRawSource: () -> Unit,
    applyRawSource: (RawSourceDraft) -> Boolean,
    navigateToRegion: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var pendingPicker by remember { mutableStateOf<InterchangeAction?>(null) }
    var pendingExport by remember { mutableStateOf<ExportResult.Success?>(null) }
    var printWebView by remember { mutableStateOf<WebView?>(null) }
    var prepareJob by remember { mutableStateOf<Job?>(null) }
    var importApplying by remember { mutableStateOf(false) }
    var rawApplying by remember { mutableStateOf(false) }

    fun writeExport(uri: Uri?) {
        val result = pendingExport
        pendingExport = null
        if (uri == null || result == null) return
        prepareJob?.cancel()
        prepareJob = scope.launch {
            val error = runCatching {
                withContext(Dispatchers.IO) {
                    requireNotNull(context.contentResolver.openOutputStream(uri)) { "The destination could not be opened." }
                        .use { it.write(result.bytes) }
                }
            }.exceptionOrNull()
            mutateRuntime {
                notice(error?.let { "Export failed: ${it.message ?: "destination error"}" }
                    ?: "Exported ${result.suggestedName}. ${result.fidelity.issues.firstOrNull()?.message.orEmpty()}".trim())
            }
        }
    }

    val docxDestination = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
        ::writeExport,
    )
    val textDestination = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain"),
        ::writeExport,
    )
    val htmlDestination = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/html"),
        ::writeExport,
    )

    fun loadImport(uri: Uri?) {
        val action = pendingPicker
        pendingPicker = null
        if (uri == null || action == null) return
        scope.launch {
            val outcome = runCatching {
                val request = withContext(Dispatchers.IO) {
                    val limits = ImportSecurityLimits()
                    val name = displayName(context, uri)
                    val bytes = readBounded(context, uri, limits.maxSourceBytes)
                    ImportRequest(
                        fileName = name,
                        bytes = bytes,
                        intent = if (action == InterchangeAction.PICK_SPREADSHEET) ImportIntent.EDIT_AS_NORFOLD else ImportIntent.EDIT_AS_NORFOLD,
                        mode = ImportMode.NATIVE,
                        targetArtifactId = artifactId,
                        limits = limits,
                    )
                }
                withContext(Dispatchers.Default) {
                    val preflight = ImportPreflightService.inspect(request)
                    val prepared = if (preflight.canApply) InterchangeImportService.prepare(request, preflight)
                    else ImportResult.Failure(preflight.errors, preflight.metrics)
                    Triple(request, preflight, prepared)
                }
            }
            outcome.fold(
                onSuccess = { (request, preflight, prepared) -> mutateRuntime { acceptPrepared(request, preflight, prepared) } },
                onFailure = { error -> mutateRuntime { notice("Import failed safely: ${error.message ?: "the file could not be read"}") } },
            )
        }
    }

    val documentPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument(), ::loadImport)

    fun reprepare(request: ImportRequest) {
        mutateRuntime { beginPreparation(request) }
        prepareJob?.cancel()
        prepareJob = scope.launch {
            val (preflight, prepared) = withContext(Dispatchers.Default) {
                val inspected = ImportPreflightService.inspect(request)
                inspected to if (inspected.canApply) InterchangeImportService.prepare(request, inspected)
                else ImportResult.Failure(inspected.errors, inspected.metrics)
            }
            mutateRuntime { acceptPrepared(request, preflight, prepared) }
        }
    }

    fun performImport() {
        if (importApplying) return
        val request = state.pendingRequest ?: return
        val prepared = state.prepared ?: return
        importApplying = true
        scope.launch {
            val materialized = runCatching { withContext(Dispatchers.IO) { persistAssets(context, artifactId, prepared) } }
            materialized.fold(
                onSuccess = { local ->
                    val applied = applyImport(request, local)
                    mutateRuntime {
                        if (applied) importApplied() else notice("Import was not applied; the original document is unchanged.")
                    }
                },
                onFailure = { error -> mutateRuntime { notice("Import asset storage failed: ${error.message ?: "storage error"}. The document is unchanged.") } },
            )
            importApplying = false
        }
    }

    fun performRawApply() {
        if (rawApplying) return
        val draft = state.rawDraft ?: return
        rawApplying = true
        val applied = runCatching { applyRawSource(draft) }.getOrDefault(false)
        mutateRuntime {
            if (applied) {
                cancelRawSource()
                notice("Raw Source applied as one undoable edit.")
            } else notice("Raw Source was not applied; the original document is unchanged.")
        }
        rawApplying = false
    }

    fun startExport(format: ExportFormat) {
        scope.launch {
            val result = withContext(Dispatchers.Default) { export(format) { source -> resolveLocalAsset(context, source) } }
            when (result) {
                is ExportResult.Failure -> mutateRuntime { notice("Export failed: ${result.diagnostics.firstOrNull()?.message ?: "unknown error"}") }
                is ExportResult.Success -> {
                    if (format == ExportFormat.PRINT_HTML) {
                        printWebView = printHtml(context, title, result.bytes.decodeToString())
                        mutateRuntime { notice("Print preview opened.") }
                    } else {
                        pendingExport = result
                        when (format) {
                            ExportFormat.DOCX -> docxDestination.launch(result.suggestedName)
                            ExportFormat.HTML -> htmlDestination.launch(result.suggestedName)
                            ExportFormat.MARKDOWN, ExportFormat.RAW_SOURCE -> textDestination.launch(result.suggestedName)
                            ExportFormat.PRINT_HTML -> Unit
                        }
                    }
                }
            }
        }
    }

    LaunchedEffect(state.requestedAction) {
        when (state.requestedAction) {
            InterchangeAction.PICK_DOCUMENT, InterchangeAction.PICK_SPREADSHEET -> {
                val action = state.requestedAction
                pendingPicker = action
                mutateRuntime { clearRequest() }
                documentPicker.launch(
                    if (action == InterchangeAction.PICK_SPREADSHEET) arrayOf(
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                        "application/octet-stream",
                    ) else arrayOf(
                        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                        "application/pdf",
                        "application/octet-stream",
                    ),
                )
            }
            InterchangeAction.APPLY_IMPORT -> performImport()
            InterchangeAction.CANCEL_IMPORT -> mutateRuntime { cancelImport() }
            InterchangeAction.EXPORT_DOCX -> { mutateRuntime { clearRequest() }; startExport(ExportFormat.DOCX) }
            InterchangeAction.EXPORT_MARKDOWN -> { mutateRuntime { clearRequest() }; startExport(ExportFormat.MARKDOWN) }
            InterchangeAction.EXPORT_HTML -> { mutateRuntime { clearRequest() }; startExport(ExportFormat.HTML) }
            InterchangeAction.PRINT -> { mutateRuntime { clearRequest() }; startExport(ExportFormat.PRINT_HTML) }
            InterchangeAction.RAW_OPEN -> if (state.rawDraft == null) {
                beforeOpenRawSource()
                mutateRuntime { openRawSource() }
            }
            InterchangeAction.RAW_APPLY -> performRawApply()
            InterchangeAction.RAW_CANCEL -> mutateRuntime { cancelRawSource() }
            else -> Unit
        }
    }

    LaunchedEffect(state.notice) { state.notice?.takeIf(String::isNotBlank)?.let { snackbar.showSnackbar(it) } }
    DisposableEffect(Unit) { onDispose { prepareJob?.cancel(); printWebView?.stopLoading(); printWebView?.destroy() } }

    Box(modifier.fillMaxSize()) {
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
    }

    val dialogModifier = if (LocalConfiguration.current.screenWidthDp < 600) Modifier.fillMaxSize()
    else Modifier.fillMaxHeight(.92f).widthIn(max = 760.dp)
    when {
        state.requestedAction == InterchangeAction.PREFLIGHT && state.preflight != null && state.pendingRequest != null ->
            InterchangeDialog(onDismiss = { mutateRuntime { cancelImport() } }, modifier = dialogModifier) {
                ImportPreflightSurface(
                    preflight = state.preflight,
                    intent = state.pendingRequest.intent,
                    mode = state.pendingRequest.mode,
                    onIntentChange = { reprepare(state.pendingRequest.copy(intent = it)) },
                    onModeChange = { reprepare(state.pendingRequest.copy(mode = it)) },
                    applyDisabledReason = state.applyImportDisabledReason ?: if (importApplying) "Import is being applied" else null,
                    onApply = ::performImport,
                    onCancel = { mutateRuntime { cancelImport() } },
                )
            }
        state.requestedAction == InterchangeAction.FIDELITY_INSPECTOR && state.fidelity != null ->
            InterchangeDialog(onDismiss = { mutateRuntime { clearRequest() } }, modifier = dialogModifier) {
                FidelityInspectorSurface(
                    report = state.fidelity,
                    selected = state.fidelityFilter,
                    onFilter = { mutateRuntime { filterFidelity(it) } },
                    onNavigate = { target -> navigateToRegion(target); mutateRuntime { clearRequest() } },
                )
            }
        state.rawDraft != null && state.requestedAction in setOf(InterchangeAction.RAW_OPEN, InterchangeAction.RAW_VALIDATE) ->
            InterchangeDialog(onDismiss = { mutateRuntime { cancelRawSource() } }, modifier = dialogModifier) {
                RawSourceSurface(
                    draft = state.rawDraft,
                    onSourceChange = { source -> mutateRuntime { editRawSource(source) } },
                    onApply = ::performRawApply,
                    onCancel = { mutateRuntime { cancelRawSource() } },
                )
            }
    }
}

@Composable
private fun InterchangeDialog(onDismiss: () -> Unit, modifier: Modifier, content: @Composable () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(modifier, shape = MaterialTheme.shapes.extraLarge, tonalElevation = 8.dp) { content() }
    }
}

private fun displayName(context: Context, uri: Uri): String {
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) return cursor.getString(0)?.takeIf(String::isNotBlank) ?: "Imported file"
    }
    return uri.lastPathSegment?.substringAfterLast('/')?.takeIf(String::isNotBlank) ?: "Imported file"
}

private fun readBounded(context: Context, uri: Uri, maxBytes: Long): ByteArray {
    require(maxBytes <= Int.MAX_VALUE) { "The configured source limit is too large." }
    val input = requireNotNull(context.contentResolver.openInputStream(uri)) { "The selected file could not be opened." }
    return input.use { readStreamBounded(it, maxBytes) }
}

private fun readStreamBounded(input: java.io.InputStream, maxBytes: Long): ByteArray {
    return input.let {
        val out = ByteArrayOutputStream(minOf(maxBytes.toInt(), 64 * 1024))
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val read = it.read(buffer)
            if (read < 0) break
            total += read
            require(total <= maxBytes) { "The selected file exceeds the ${maxBytes / (1024 * 1024)} MB import limit." }
            out.write(buffer, 0, read)
        }
        out.toByteArray()
    }
}

private fun persistAssets(context: Context, artifactId: String, prepared: ImportResult): ImportResult {
    val success = prepared as? ImportResult.Success ?: return prepared
    val safeArtifact = artifactId.replace(Regex("[^A-Za-z0-9._-]"), "_").take(96).ifBlank { "artifact" }
    val directory = File(context.filesDir, "imported-artifacts/$safeArtifact").apply { mkdirs() }
    require(directory.isDirectory) { "Private import storage is unavailable." }
    val uris = success.artifact.assets.associate { asset ->
        val extension = extensionFor(asset.mediaType)
        val target = File(directory, "${asset.id.replace(Regex("[^A-Za-z0-9._-]"), "_")}.$extension")
        val temporary = File(directory, ".${target.name}.${System.nanoTime()}.tmp")
        FileOutputStream(temporary).use { stream -> stream.write(asset.bytes); stream.fd.sync() }
        check(temporary.renameTo(target) || run { temporary.copyTo(target, overwrite = true); temporary.delete() }) {
            "An imported asset could not be committed to private storage."
        }
        asset.id to FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", target).toString()
    }
    val document = success.artifact.document.copy(blocks = success.artifact.document.blocks.map { it.localizeAssets(uris) })
    return success.copy(artifact = success.artifact.copy(document = document))
}

private fun DocumentBlock.localizeAssets(uris: Map<String, String>): DocumentBlock = when (this) {
    is ImageBlock -> copy(source = source.localAssetUri(uris))
    is FileBlock -> copy(uri = uri.localAssetUri(uris))
    is QuoteBlock -> copy(children = children.map { it.localizeAssets(uris) })
    is CalloutBlock -> copy(children = children.map { it.localizeAssets(uris) })
    is ContainerBlock -> copy(children = children.map { it.localizeAssets(uris) })
    is DocumentPageBlock -> copy(objects = objects.map { it.copy(content = it.content.localizeAssets(uris)) })
    else -> this
}

private fun String.localAssetUri(uris: Map<String, String>): String {
    if (!startsWith("norfold-import://")) return this
    val idAndFragment = removePrefix("norfold-import://")
    val id = idAndFragment.substringBefore('#')
    val fragment = idAndFragment.substringAfter('#', "")
    return uris[id]?.let { if (fragment.isBlank()) it else "$it#$fragment" } ?: this
}

private fun extensionFor(mediaType: String): String = when (mediaType.lowercase()) {
    "image/png" -> "png"
    "image/jpeg" -> "jpg"
    "image/gif" -> "gif"
    "image/webp" -> "webp"
    "image/svg+xml" -> "svg"
    "application/pdf" -> "pdf"
    "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> "docx"
    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" -> "xlsx"
    else -> "bin"
}

private fun resolveLocalAsset(context: Context, source: String): ByteArray? = runCatching {
    val uri = Uri.parse(source.substringBefore('#'))
    when (uri.scheme?.lowercase()) {
        "content" -> context.contentResolver.openInputStream(uri)?.use { readStreamBounded(it, 32L * 1024 * 1024) }
        "file" -> uri.path?.let(::File)?.takeIf { it.isFile && it.canonicalPath.startsWith(context.filesDir.canonicalPath) }
            ?.inputStream()?.use { readStreamBounded(it, 32L * 1024 * 1024) }
        "data" -> null
        else -> null
    }
}.getOrNull()

private fun printHtml(context: Context, title: String, html: String): WebView {
    val view = WebView(context)
    view.settings.javaScriptEnabled = false
    view.settings.blockNetworkLoads = true
    view.webViewClient = object : WebViewClient() {
        override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
            val scheme = request?.url?.scheme?.lowercase()
            return if (scheme == "http" || scheme == "https") WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream(ByteArray(0))) else null
        }

        override fun onPageFinished(view: WebView, url: String?) {
            val manager = context.getSystemService(Context.PRINT_SERVICE) as android.print.PrintManager
            manager.print(title.ifBlank { "Norfold document" }, view.createPrintDocumentAdapter(title), null)
        }
    }
    view.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
    return view
}
