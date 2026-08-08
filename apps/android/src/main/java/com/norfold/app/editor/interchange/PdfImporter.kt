package com.norfold.app.editor.interchange

import com.norfold.app.domain.BlockDocument
import com.norfold.app.domain.DocumentPageBlock
import com.norfold.app.domain.FileBlock
import com.norfold.app.domain.PageGeometry
import com.norfold.app.domain.PageMargins
import com.norfold.app.domain.SceneGeometry
import com.norfold.app.domain.SceneObject
import com.norfold.app.domain.SceneObjectKind
import com.norfold.app.domain.withInterchangeData

/** Offline, inert fixed-layout PDF import. It deliberately makes no semantic-text claim. */
object PdfImporter {
    private val mediaBox = Regex("/MediaBox\\s*\\[\\s*([-+0-9.]+)\\s+([-+0-9.]+)\\s+([-+0-9.]+)\\s+([-+0-9.]+)\\s*]", RegexOption.IGNORE_CASE)

    fun import(request: ImportRequest, preflight: ImportPreflight = ImportPreflightService.inspect(request)): ImportResult {
        val started = System.nanoTime()
        if (preflight.detectedFormat != ImportFormat.PDF || !preflight.canApply) {
            return ImportResult.Failure(preflight.errors.ifEmpty {
                listOf(FidelityIssue("wrong_format", "PDF importer received a different or unsafe format.", FidelitySeverity.ERROR))
            }, preflight.metrics)
        }
        return try {
            val sourceHash = preflight.source.sha256
            val latin = request.bytes.toString(Charsets.ISO_8859_1)
            val defaultBox = mediaBox.find(latin)?.groupValues?.drop(1)?.mapNotNull(String::toFloatOrNull)
            val width = defaultBox?.let { (it[2] - it[0]).coerceAtLeast(1f) } ?: 612f
            val height = defaultBox?.let { (it[3] - it[1]).coerceAtLeast(1f) } ?: 792f
            val pageCount = preflight.source.pageCount ?: 1
            require(pageCount in 1..request.limits.maxPages) { "PDF page count exceeds ${request.limits.maxPages}" }
            val sourceAssetId = stableImportId("source", sourceHash, request.fileName)
            val pages = List(pageCount) { index ->
                val geometry = PageGeometry(width = width, height = height, margins = PageMargins(0f, 0f, 0f, 0f)).normalized()
                val fixed = FileBlock(
                    id = stableImportId("pdf-page", sourceHash, "page/$index"),
                    name = "PDF page ${index + 1}",
                    mimeType = "application/pdf",
                    sizeBytes = request.bytes.size.toLong(),
                    uri = "norfold-import://$sourceAssetId#page=${index + 1}",
                )
                DocumentPageBlock(
                    id = stableImportId("page", sourceHash, "page/$index"),
                    geometry = geometry,
                    objects = listOf(
                        SceneObject(
                            id = stableImportId("object", sourceHash, "page/$index/fixed"),
                            kind = SceneObjectKind.Preserved,
                            geometry = SceneGeometry(0f, 0f, width, height),
                            z = 0,
                            content = fixed,
                        ),
                    ),
                    extensions = mapOf("norfold.pdf.source" to sourceAssetId, "norfold.pdf.page" to (index + 1).toString()),
                ).normalized()
            }
            val regions = pages.mapIndexed { index, page ->
                FidelityRegion(
                    id = stableRegionId(sourceHash, "page/$index"),
                    stableTargetId = page.id,
                    sourcePath = "page/${index + 1}",
                    state = FidelityState.FIXED,
                    summary = "Fixed PDF page ${index + 1}",
                    details = "The original page remains inert and fixed-layout; Norfold does not claim editable paragraph semantics.",
                    editable = false,
                )
            }
            val issues = preflight.warnings + if (preflight.source.activeContent) listOf(
                FidelityIssue("pdf_active_content_inert", "PDF actions, scripts, embedded files and remote resources are disabled.", FidelitySeverity.WARNING),
            ) else emptyList()
            val report = FidelityReport(ImportFormat.PDF, request.mode, request.intent, regions, issues)
            val metadata = InterchangeArtifactMetadata(1, preflight.source, CompatibilityMetadata(), report)
            val document = BlockDocument(pages).withInterchangeData(InterchangeMetadataCodec.encode(metadata)).normalized()
            ImportResult.Success(
                ImportedArtifact(
                    document = document,
                    assets = listOf(ImportedAsset(sourceAssetId, "application/pdf", request.bytes, request.fileName)),
                    source = preflight.source,
                    fidelity = report,
                ),
                preflight.metrics.copy(durationNanos = System.nanoTime() - started),
            )
        } catch (e: Exception) {
            ImportResult.Failure(
                listOf(FidelityIssue("pdf_import_failed", e.message ?: "PDF import failed.", FidelitySeverity.ERROR)),
                preflight.metrics.copy(durationNanos = System.nanoTime() - started),
            )
        }
    }
}
