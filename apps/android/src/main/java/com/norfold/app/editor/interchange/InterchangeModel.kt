package com.norfold.app.editor.interchange

import com.norfold.app.domain.BlockDocument
import com.norfold.app.editor.calculation.CalculationWorkbook
import kotlinx.serialization.Serializable
import java.security.MessageDigest

@Serializable
enum class ImportFormat { DOCX, PDF, XLSX, UNKNOWN }

@Serializable
enum class ImportIntent {
    EDIT_AS_NORFOLD,
    PRESERVE_APPEARANCE,
    READ_AND_ANNOTATE,
    PLACE_OR_LINK,
    EXTRACT_CONTENT,
}

@Serializable
enum class ImportMode { COMPATIBILITY, NATIVE }

@Serializable
enum class FidelityState { NATIVE, APPROXIMATED, HYBRID, FIXED, UNSUPPORTED }

@Serializable
enum class FidelitySeverity { INFO, WARNING, ERROR }

@Serializable
data class FidelityIssue(
    val code: String,
    val message: String,
    val severity: FidelitySeverity,
    val regionId: String? = null,
    val recovery: String? = null,
)

@Serializable
data class FidelityRegion(
    val id: String,
    val stableTargetId: String,
    val sourcePath: String,
    val state: FidelityState,
    val summary: String,
    val details: String = "",
    val editable: Boolean = state == FidelityState.NATIVE || state == FidelityState.APPROXIMATED || state == FidelityState.HYBRID,
)

@Serializable
data class FidelityReport(
    val format: ImportFormat,
    val mode: ImportMode,
    val intent: ImportIntent,
    val regions: List<FidelityRegion> = emptyList(),
    val issues: List<FidelityIssue> = emptyList(),
) {
    val hasErrors: Boolean get() = issues.any { it.severity == FidelitySeverity.ERROR }
    fun count(state: FidelityState): Int = regions.count { it.state == state }
    fun filtered(states: Set<FidelityState>): List<FidelityRegion> =
        if (states.isEmpty()) regions else regions.filter { it.state in states }

    fun afterNativeEdit(regionId: String): FidelityReport = copy(
        regions = regions.map { region ->
            if (region.id != regionId && region.stableTargetId != regionId) region else when (region.state) {
                FidelityState.NATIVE -> region
                FidelityState.APPROXIMATED, FidelityState.HYBRID, FidelityState.FIXED -> region.copy(
                    state = FidelityState.APPROXIMATED,
                    editable = true,
                    details = listOf(region.details, "Edited in Norfold; preserved source appearance may differ.")
                        .filter(String::isNotBlank).joinToString(" "),
                )
                FidelityState.UNSUPPORTED -> region
            }
        },
    )
}

@Serializable
data class CompatibilityEntry(
    val path: String,
    val mediaType: String,
    val sha256: String,
    val payloadBase64: String? = null,
)

@Serializable
data class CompatibilityMetadata(
    val schemaVersion: Int = 1,
    val entries: List<CompatibilityEntry> = emptyList(),
    val preservedBytes: Long = 0,
    val omittedPaths: List<String> = emptyList(),
) {
    init { require(schemaVersion == 1) { "Unsupported compatibility metadata version" } }
}

@Serializable
data class SourceArtifactMetadata(
    val format: ImportFormat,
    val originalName: String,
    val mediaType: String,
    val byteSize: Long,
    val sha256: String,
    val pageCount: Int? = null,
    val sheetCount: Int? = null,
    val protected: Boolean = false,
    val activeContent: Boolean = false,
    val externalRelationships: Int = 0,
)

data class ImportSecurityLimits(
    val maxSourceBytes: Long = 32L * 1024 * 1024,
    val maxZipEntries: Int = 2_048,
    val maxExpandedBytes: Long = 128L * 1024 * 1024,
    val maxEntryBytes: Long = 16L * 1024 * 1024,
    val maxXmlDepth: Int = 64,
    val maxXmlNodes: Int = 1_000_000,
    val maxTextNodeChars: Int = 1_048_576,
    val maxImagePixels: Long = 40_000_000,
    val maxImages: Int = 512,
    val maxPages: Int = 2_000,
    val maxSheets: Int = 256,
    val maxRows: Int = 100_000,
    val maxColumns: Int = 16_384,
    val maxCells: Int = 1_000_000,
    val maxComments: Int = 20_000,
    val maxRelationships: Int = 100_000,
    val maxCompatibilityBytes: Long = 8L * 1024 * 1024,
    val maxRawSourceBytes: Int = 8 * 1024 * 1024,
    val maxRawTokens: Int = 1_000_000,
    val maxRawNesting: Int = 64,
    val maxRawStringChars: Int = 1_048_576,
) {
    init {
        require(maxSourceBytes > 0 && maxZipEntries > 0 && maxExpandedBytes > 0 && maxEntryBytes > 0)
        require(maxXmlDepth > 0 && maxXmlNodes > 0 && maxTextNodeChars > 0)
        require(maxPages > 0 && maxSheets > 0 && maxRows > 0 && maxColumns > 0 && maxCells > 0)
        require(maxRawSourceBytes > 0 && maxRawTokens > 0 && maxRawNesting > 0 && maxRawStringChars > 0)
    }
}

data class ImportRequest(
    val fileName: String,
    val bytes: ByteArray,
    val intent: ImportIntent,
    val mode: ImportMode,
    val targetArtifactId: String,
    val limits: ImportSecurityLimits = ImportSecurityLimits(),
)

data class ImportPreflight(
    val source: SourceArtifactMetadata,
    val detectedFormat: ImportFormat,
    val proposedIntent: ImportIntent,
    val proposedMode: ImportMode,
    val warnings: List<FidelityIssue> = emptyList(),
    val errors: List<FidelityIssue> = emptyList(),
    val estimatedRegions: Map<FidelityState, Int> = emptyMap(),
    val metrics: InterchangeMetrics = InterchangeMetrics(),
) {
    val canApply: Boolean get() = detectedFormat != ImportFormat.UNKNOWN && errors.isEmpty()
}

data class ImportedAsset(
    val id: String,
    val mediaType: String,
    val bytes: ByteArray,
    val sourcePath: String,
)

data class ImportedArtifact(
    val document: BlockDocument,
    val workbook: CalculationWorkbook? = null,
    val assets: List<ImportedAsset> = emptyList(),
    val source: SourceArtifactMetadata,
    val compatibility: CompatibilityMetadata = CompatibilityMetadata(),
    val fidelity: FidelityReport,
)

sealed interface ImportResult {
    data class Success(val artifact: ImportedArtifact, val metrics: InterchangeMetrics) : ImportResult
    data class Failure(val diagnostics: List<FidelityIssue>, val metrics: InterchangeMetrics = InterchangeMetrics()) : ImportResult
}

enum class ExportFormat { DOCX, MARKDOWN, HTML, PRINT_HTML, RAW_SOURCE }

data class ExportRequest(
    val title: String,
    val document: BlockDocument,
    val format: ExportFormat,
    val sourceMetadata: SourceArtifactMetadata? = null,
    val assetResolver: (String) -> ByteArray? = { null },
)

sealed interface ExportResult {
    data class Success(
        val bytes: ByteArray,
        val mediaType: String,
        val suggestedName: String,
        val fidelity: FidelityReport,
        val metrics: InterchangeMetrics,
    ) : ExportResult
    data class Failure(val diagnostics: List<FidelityIssue>) : ExportResult
}

@Serializable
data class InterchangeMetrics(
    val sourceBytesRead: Long = 0,
    val zipEntriesProcessed: Int = 0,
    val expandedBytesRead: Long = 0,
    val xmlNodesParsed: Int = 0,
    val imagesDecoded: Int = 0,
    val spreadsheetCellsProcessed: Int = 0,
    val rawTokensParsed: Int = 0,
    val outputBytes: Long = 0,
    val durationNanos: Long = 0,
)

internal fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
    .digest(bytes).joinToString("") { "%02x".format(it) }

internal fun stableRegionId(sourceHash: String, sourcePath: String): String =
    "region-${sha256("$sourceHash\u0000$sourcePath".toByteArray()).take(24)}"

internal fun stableImportId(prefix: String, sourceHash: String, sourcePath: String): String =
    "$prefix-${sha256("$sourceHash\u0000$sourcePath".toByteArray()).take(24)}"

class ImportSecurityException(message: String) : IllegalArgumentException(message)
