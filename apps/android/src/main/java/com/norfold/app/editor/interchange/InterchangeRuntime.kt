package com.norfold.app.editor.interchange

import com.norfold.app.domain.BlockDocument
import com.norfold.app.domain.DocxExporter
import com.norfold.app.domain.MarkdownBlockCodec
import com.norfold.app.domain.MarkdownExporter
import com.norfold.app.domain.NoteDocument
import com.norfold.app.editor.command.ApplicabilityPolicy
import com.norfold.app.editor.command.Capability
import com.norfold.app.editor.command.CommandContext
import com.norfold.app.editor.command.CommandRegistry
import com.norfold.app.editor.command.CommandResult
import com.norfold.app.editor.command.CommandState
import com.norfold.app.editor.command.CommandStatus
import com.norfold.app.editor.command.EditorCommand

enum class InterchangeAction {
    PICK_DOCUMENT, PICK_SPREADSHEET, PREFLIGHT, APPLY_IMPORT, CANCEL_IMPORT, FIDELITY_INSPECTOR,
    EXPORT_DOCX, EXPORT_MARKDOWN, EXPORT_HTML, PRINT, RAW_OPEN, RAW_VALIDATE, RAW_APPLY, RAW_CANCEL,
}

data class InterchangeUiState(
    val requestedAction: InterchangeAction? = null,
    val pendingRequest: ImportRequest? = null,
    val preflight: ImportPreflight? = null,
    val prepared: ImportResult? = null,
    val fidelity: FidelityReport? = null,
    val fidelityFilter: Set<FidelityState> = emptySet(),
    val rawDraft: RawSourceDraft? = null,
    val notice: String? = null,
) {
    val applyImportDisabledReason: String? get() = when {
        preflight == null -> "Choose a file and review preflight first"
        preflight.canApply.not() -> preflight.errors.firstOrNull()?.message ?: "Preflight did not pass"
        prepared !is ImportResult.Success -> "Prepare the import before applying"
        else -> null
    }
    val applyRawDisabledReason: String? get() = rawDraft?.let { if (it.canApply) null else it.applyDisabledReason ?: "Fix Raw Source diagnostics before applying" } ?: "Open Raw Source first"
}

/** Presentation-independent state shared by phone sheets and expanded inspector panes. */
class InterchangeRuntime(private val documentProvider: () -> BlockDocument) {
    var state: InterchangeUiState = InterchangeUiState()
        private set

    fun request(action: InterchangeAction) { state = state.copy(requestedAction = action, notice = null) }
    fun clearRequest() { state = state.copy(requestedAction = null) }
    fun inspect(request: ImportRequest): ImportPreflight = InterchangeImportService.preflight(request).also { state = state.copy(pendingRequest = request, preflight = it, prepared = null, fidelity = null, requestedAction = InterchangeAction.PREFLIGHT) }
    fun prepare(request: ImportRequest): ImportResult = InterchangeImportService.prepare(request, state.preflight ?: inspect(request)).also { result ->
        state = state.copy(pendingRequest = request, prepared = result, fidelity = (result as? ImportResult.Success)?.artifact?.fidelity, notice = (result as? ImportResult.Failure)?.diagnostics?.firstOrNull()?.message)
    }
    fun acceptPrepared(request: ImportRequest, preflight: ImportPreflight, result: ImportResult) {
        state = state.copy(
            requestedAction = InterchangeAction.PREFLIGHT,
            pendingRequest = request,
            preflight = preflight,
            prepared = result,
            fidelity = (result as? ImportResult.Success)?.artifact?.fidelity,
            notice = (result as? ImportResult.Failure)?.diagnostics?.firstOrNull()?.message,
        )
    }
    fun beginPreparation(request: ImportRequest) {
        state = state.copy(
            requestedAction = InterchangeAction.PREFLIGHT,
            pendingRequest = request,
            prepared = null,
            fidelity = null,
            notice = "Rechecking import settings…",
        )
    }
    fun cancelImport() { state = state.copy(pendingRequest = null, preflight = null, prepared = null, requestedAction = null, notice = "Import cancelled; the document was not changed.") }
    fun importApplied() { state = state.copy(pendingRequest = null, preflight = null, prepared = null, requestedAction = null, notice = "Import applied. Undo is available.") }
    fun openRawSource() { state = state.copy(rawDraft = RawSourceEditor.open(documentProvider()), requestedAction = InterchangeAction.RAW_OPEN) }
    fun editRawSource(source: String) { state.rawDraft?.let { state = state.copy(rawDraft = RawSourceEditor.edit(it, source), requestedAction = InterchangeAction.RAW_VALIDATE) } }
    fun cancelRawSource() { state = state.copy(rawDraft = null, requestedAction = null, notice = "Raw Source cancelled; the document was not changed.") }
    fun filterFidelity(states: Set<FidelityState>) { state = state.copy(fidelityFilter = states) }
    fun notice(message: String) { state = state.copy(notice = message, requestedAction = null) }

    fun export(title: String, format: ExportFormat, assetResolver: (String) -> ByteArray? = { null }): ExportResult {
        val started = System.nanoTime()
        return try {
            val document = documentProvider()
            val bytes: ByteArray
            val media: String
            val name: String
            val issues = mutableListOf<FidelityIssue>()
            when (format) {
                ExportFormat.DOCX -> {
                    val pkg = DocxExporter.exportDetailed(title, document, assetResolver)
                    bytes = pkg.bytes; media = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"; name = "${safeName(title)}.docx"
                    pkg.warnings.forEach { issues += FidelityIssue("docx_export_approximation", it, FidelitySeverity.WARNING) }
                }
                ExportFormat.MARKDOWN -> { bytes = MarkdownBlockCodec.export(document).toByteArray(); media = "text/markdown"; name = "${safeName(title)}.md" }
                ExportFormat.HTML -> { bytes = MarkdownExporter.html(NoteDocument(title, "", "", MarkdownBlockCodec.export(document))).toByteArray(); media = "text/html"; name = "${safeName(title)}.html" }
                ExportFormat.PRINT_HTML -> { bytes = MarkdownExporter.printHtml(title, MarkdownBlockCodec.export(document)).toByteArray(); media = "text/html"; name = "${safeName(title)}-print.html" }
                ExportFormat.RAW_SOURCE -> { bytes = RawSourceSerializer.serialize(document).toByteArray(); media = "text/plain"; name = "${safeName(title)}.norfold" }
            }
            val report = FidelityReport(ImportFormat.UNKNOWN, ImportMode.NATIVE, ImportIntent.EDIT_AS_NORFOLD, issues = issues)
            ExportResult.Success(bytes, media, name, report, InterchangeMetrics(outputBytes = bytes.size.toLong(), durationNanos = System.nanoTime() - started))
        } catch (e: Exception) {
            ExportResult.Failure(listOf(FidelityIssue("export_failed", e.message ?: "Export failed.", FidelitySeverity.ERROR)))
        }
    }

    private fun safeName(title: String): String = title.ifBlank { "Untitled" }.replace(Regex("[^A-Za-z0-9._ -]"), "_").take(120)
}

object InterchangeCommandIds {
    const val IMPORT_DOCUMENT = "interchange.import.document"
    const val IMPORT_SPREADSHEET = "interchange.import.spreadsheet"
    const val PREFLIGHT = "interchange.preflight.open"
    const val APPLY_IMPORT = "interchange.import.apply"
    const val CANCEL_IMPORT = "interchange.import.cancel"
    const val FIDELITY = "interchange.fidelity.open"
    const val EXPORT_DOCX = "interchange.export.docx"
    const val EXPORT_MARKDOWN = "interchange.export.markdown"
    const val EXPORT_HTML = "interchange.export.html"
    const val PRINT = "interchange.print"
    const val RAW_OPEN = "interchange.raw.open"
    const val RAW_VALIDATE = "interchange.raw.validate"
    const val RAW_APPLY = "interchange.raw.apply"
    const val RAW_CANCEL = "interchange.raw.cancel"
}

object InterchangeCommands {
    fun register(registry: CommandRegistry, runtime: InterchangeRuntime): CommandRegistry {
        fun add(id: String, label: String, description: String, action: InterchangeAction, enabled: () -> Pair<Boolean, String?> = { true to null }) {
            registry.register(InterchangeCommand(id, label, description, runtime, action, enabled))
        }
        add(InterchangeCommandIds.IMPORT_DOCUMENT, "Import document", "Choose a DOCX or PDF and review preflight", InterchangeAction.PICK_DOCUMENT)
        add(InterchangeCommandIds.IMPORT_SPREADSHEET, "Import spreadsheet", "Choose an XLSX workbook and review preflight", InterchangeAction.PICK_SPREADSHEET)
        add(InterchangeCommandIds.PREFLIGHT, "Open import preflight", "Review format, mode, warnings and resource limits", InterchangeAction.PREFLIGHT) { (runtime.state.preflight != null) to "No import preflight is available" }
        add(InterchangeCommandIds.APPLY_IMPORT, "Apply import", "Apply the prepared import as one recoverable edit", InterchangeAction.APPLY_IMPORT) { (runtime.state.applyImportDisabledReason == null) to runtime.state.applyImportDisabledReason }
        add(InterchangeCommandIds.CANCEL_IMPORT, "Cancel import", "Discard import state without changing the artifact", InterchangeAction.CANCEL_IMPORT) { (runtime.state.preflight != null) to "No import is open" }
        add(InterchangeCommandIds.FIDELITY, "Fidelity Inspector", "Inspect native, approximated, hybrid, fixed and unsupported regions", InterchangeAction.FIDELITY_INSPECTOR) { (runtime.state.fidelity != null) to "No fidelity report is available" }
        add(InterchangeCommandIds.EXPORT_DOCX, "Export DOCX", "Export an editable Word package with fidelity warnings", InterchangeAction.EXPORT_DOCX)
        add(InterchangeCommandIds.EXPORT_MARKDOWN, "Export Markdown", "Export semantic lightweight Markdown", InterchangeAction.EXPORT_MARKDOWN)
        add(InterchangeCommandIds.EXPORT_HTML, "Export HTML", "Export interoperable HTML", InterchangeAction.EXPORT_HTML)
        add(InterchangeCommandIds.PRINT, "Print", "Open the existing print output boundary", InterchangeAction.PRINT)
        add(InterchangeCommandIds.RAW_OPEN, "Open Raw Source", "Open advanced versioned Norfold product source", InterchangeAction.RAW_OPEN)
        add(InterchangeCommandIds.RAW_VALIDATE, "Validate Raw Source", "Parse and show line-position diagnostics", InterchangeAction.RAW_VALIDATE) { (runtime.state.rawDraft != null) to "Open Raw Source first" }
        add(InterchangeCommandIds.RAW_APPLY, "Apply Raw Source", "Apply valid source as one recoverable edit", InterchangeAction.RAW_APPLY) { (runtime.state.applyRawDisabledReason == null) to runtime.state.applyRawDisabledReason }
        add(InterchangeCommandIds.RAW_CANCEL, "Cancel Raw Source", "Close source without changing the artifact", InterchangeAction.RAW_CANCEL) { (runtime.state.rawDraft != null) to "Raw Source is not open" }
        return registry
    }
}

private class InterchangeCommand(
    override val id: String,
    override val label: String,
    override val description: String,
    private val runtime: InterchangeRuntime,
    private val action: InterchangeAction,
    private val enabled: () -> Pair<Boolean, String?>,
) : EditorCommand {
    override val icon: String? = null
    override val category: String = "Import, export and source"
    override val aliases: List<String> = emptyList()
    override val requiredCapabilities: Set<Capability> = emptySet()
    override val applicability: ApplicabilityPolicy = ApplicabilityPolicy.ALL_TARGETS
    override fun status(context: CommandContext): CommandStatus = enabled().let { (available, reason) -> if (available) CommandStatus.Available else CommandStatus(CommandState.DISABLED, reason) }
    override fun perform(context: CommandContext): CommandResult { runtime.request(action); return CommandResult.Success(message = label) }
}
