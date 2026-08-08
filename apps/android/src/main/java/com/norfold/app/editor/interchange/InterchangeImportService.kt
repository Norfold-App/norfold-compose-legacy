package com.norfold.app.editor.interchange

import com.norfold.app.domain.BlockDocument
import com.norfold.app.domain.FileBlock
import com.norfold.app.editor.document.DocumentSceneController
import com.norfold.app.editor.flow.FlowEditController

object InterchangeImportService {
    fun preflight(request: ImportRequest): ImportPreflight = ImportPreflightService.inspect(request)

    /** Parses and validates into a detached value. The live controller is not touched. */
    fun prepare(request: ImportRequest, preflight: ImportPreflight = preflight(request)): ImportResult = when (preflight.detectedFormat) {
        ImportFormat.DOCX -> DocxImporter.import(request, preflight)
        ImportFormat.PDF -> PdfImporter.import(request, preflight)
        ImportFormat.XLSX -> XlsxImporter.import(request, preflight)
        ImportFormat.UNKNOWN -> ImportResult.Failure(preflight.errors.ifEmpty {
            listOf(FidelityIssue("unknown_format", "The file is not a supported DOCX, PDF or XLSX package.", FidelitySeverity.ERROR))
        }, preflight.metrics)
    }

    fun apply(controller: FlowEditController, request: ImportRequest, prepared: ImportResult, assetsReady: (List<ImportedAsset>) -> Boolean = { true }): Boolean =
        applyPrepared(request, prepared, controller.document(), controller::snapshotBeforeImport, assetsReady) { label, document ->
            controller.mutateDocument(label) { document }
        }

    fun apply(controller: DocumentSceneController, request: ImportRequest, prepared: ImportResult, assetsReady: (List<ImportedAsset>) -> Boolean = { true }): Boolean =
        applyPrepared(request, prepared, controller.document(), controller::snapshotBeforeImport, assetsReady) { label, document ->
            controller.transformDocument(label) { document }
        }

    fun applyRawSource(controller: FlowEditController, draft: RawSourceDraft, snapshotRequired: Boolean = true): Boolean {
        val materialized = RawSourceEditor.materialize(draft).normalized()
        if (snapshotRequired) controller.snapshotBeforeRawSource("Before Raw Source Apply")
        return controller.mutateDocument("Apply Raw Source") { materialized }
    }

    fun applyRawSource(controller: DocumentSceneController, draft: RawSourceDraft, snapshotRequired: Boolean = true): Boolean {
        val materialized = RawSourceEditor.materialize(draft).normalized()
        if (snapshotRequired) controller.snapshotBeforeRawSource("Before Raw Source Apply")
        return controller.transformDocument("Apply Raw Source") { materialized }
    }

    private fun applyPrepared(
        request: ImportRequest,
        prepared: ImportResult,
        current: BlockDocument,
        snapshot: (String) -> Any,
        assetsReady: (List<ImportedAsset>) -> Boolean,
        mutate: (String, BlockDocument) -> Boolean,
    ): Boolean {
        val artifact = (prepared as? ImportResult.Success)?.artifact ?: return false
        val next = when (request.intent) {
            ImportIntent.PLACE_OR_LINK -> current.copy(blocks = current.blocks + FileBlock(
                id = stableImportId("link", artifact.source.sha256, artifact.source.originalName),
                name = artifact.source.originalName,
                mimeType = artifact.source.mediaType,
                sizeBytes = artifact.source.byteSize,
                uri = "norfold-import://${artifact.assets.firstOrNull()?.id.orEmpty()}",
            )).normalized()
            ImportIntent.EXTRACT_CONTENT -> current.copy(blocks = current.blocks + artifact.document.blocks).normalized()
            ImportIntent.EDIT_AS_NORFOLD, ImportIntent.PRESERVE_APPEARANCE, ImportIntent.READ_AND_ANNOTATE -> artifact.document.normalized()
        }
        if (next == current) return false
        if (!assetsReady(artifact.assets)) return false
        snapshot("Before importing ${artifact.source.format.name}")
        return mutate("Import ${artifact.source.format.name}", next)
    }
}
