package com.norfold.app.editor.interchange

import java.nio.charset.StandardCharsets

object ImportPreflightService {
    private val officePreflightPaths = setOf(
        "[Content_Types].xml",
        "_rels/.rels",
        "word/document.xml",
        "word/_rels/document.xml.rels",
        "word/comments.xml",
        "xl/workbook.xml",
        "xl/_rels/workbook.xml.rels",
    )

    fun detect(bytes: ByteArray, limits: ImportSecurityLimits = ImportSecurityLimits()): ImportFormat {
        if (bytes.size >= 5 && bytes.copyOfRange(0, 5).toString(StandardCharsets.US_ASCII) == "%PDF-") {
            return ImportFormat.PDF
        }
        if (bytes.size >= 4 && bytes[0] == 0x50.toByte() && bytes[1] == 0x4b.toByte()) {
            val pkg = BoundedPackageReader.read(bytes, limits) { false }
            return when {
                "word/document.xml" in pkg.names() -> ImportFormat.DOCX
                "xl/workbook.xml" in pkg.names() -> ImportFormat.XLSX
                else -> ImportFormat.UNKNOWN
            }
        }
        return ImportFormat.UNKNOWN
    }

    fun inspect(request: ImportRequest): ImportPreflight {
        val started = System.nanoTime()
        val hash = sha256(request.bytes)
        val detection = runCatching { detect(request.bytes, request.limits) }
        val format = detection.getOrElse { ImportFormat.UNKNOWN }
        val base = SourceArtifactMetadata(
            format = format,
            originalName = request.fileName.take(255),
            mediaType = format.mediaType,
            byteSize = request.bytes.size.toLong(),
            sha256 = hash,
        )
        detection.exceptionOrNull()?.let { failure ->
            return ImportPreflight(
                source = base,
                detectedFormat = format,
                proposedIntent = request.intent,
                proposedMode = request.mode,
                errors = listOf(error("unsafe_source", failure.message ?: "The file failed security preflight.")),
                metrics = InterchangeMetrics(sourceBytesRead = request.bytes.size.toLong(), durationNanos = System.nanoTime() - started),
            )
        }
        if (request.bytes.size.toLong() > request.limits.maxSourceBytes) {
            return ImportPreflight(
                source = base,
                detectedFormat = format,
                proposedIntent = request.intent,
                proposedMode = request.mode,
                errors = listOf(error("source_limit", "File exceeds the configured source-size limit.")),
                metrics = InterchangeMetrics(sourceBytesRead = request.bytes.size.toLong(), durationNanos = System.nanoTime() - started),
            )
        }
        return try {
            when (format) {
                ImportFormat.DOCX -> inspectDocx(request, base, started)
                ImportFormat.XLSX -> inspectXlsx(request, base, started)
                ImportFormat.PDF -> inspectPdf(request, base, started)
                ImportFormat.UNKNOWN -> ImportPreflight(
                    source = base,
                    detectedFormat = format,
                    proposedIntent = request.intent,
                    proposedMode = request.mode,
                    errors = listOf(error("unknown_format", "The file is not a supported DOCX, PDF, or XLSX artifact.")),
                    metrics = InterchangeMetrics(sourceBytesRead = request.bytes.size.toLong(), durationNanos = System.nanoTime() - started),
                )
            }
        } catch (e: ImportSecurityException) {
            ImportPreflight(
                source = base,
                detectedFormat = format,
                proposedIntent = request.intent,
                proposedMode = request.mode,
                errors = listOf(error("unsafe_source", e.message ?: "The file failed security preflight.")),
                metrics = InterchangeMetrics(sourceBytesRead = request.bytes.size.toLong(), durationNanos = System.nanoTime() - started),
            )
        }
    }

    private fun inspectDocx(request: ImportRequest, base: SourceArtifactMetadata, started: Long): ImportPreflight {
        val pkg = BoundedPackageReader.read(request.bytes, request.limits) { it in officePreflightPaths }
        val names = pkg.names()
        val protected = "EncryptionInfo" in names || "EncryptedPackage" in names
        val macro = names.any { it.endsWith("vbaProject.bin", true) }
        val embedded = names.count { it.startsWith("word/embeddings/") }
        val document = pkg["word/document.xml"]?.let { SecureXml.parse(it, request.limits) }
        val relationships = pkg["word/_rels/document.xml.rels"]?.let { SecureXml.parse(it, request.limits) }
        val external = relationships?.root?.descendants("Relationship")?.count {
            it.attribute("TargetMode").equals("External", true)
        } ?: 0
        val paragraphCount = document?.root?.descendants("p")?.count() ?: 0
        val tableCount = document?.root?.descendants("tbl")?.count() ?: 0
        val imageCount = document?.root?.descendants("blip")?.count() ?: 0
        val pageBreaks = document?.root?.descendants("br")?.count { it.attribute("type") == "page" } ?: 0
        val sectionCount = document?.root?.descendants("sectPr")?.count() ?: 0
        val comments = pkg["word/comments.xml"]?.let { SecureXml.parse(it, request.limits) }
        val commentCount = comments?.root?.descendants("comment")?.count() ?: 0
        val warnings = buildList {
            if (macro) add(warning("active_content", "Macros are present and will remain inert."))
            if (external > 0) add(warning("external_relationships", "$external external relationship(s) will not be fetched."))
            if (embedded > 0) add(warning("embedded_objects", "$embedded embedded object(s) require compatibility preservation."))
            if (commentCount > request.limits.maxComments) add(warning("comment_limit", "Comment count exceeds the import limit."))
        }
        val errors = buildList {
            if (protected) add(error("encrypted_office", "Encrypted Office packages cannot be imported without decryption."))
            if (imageCount > request.limits.maxImages) add(error("image_limit", "Image count exceeds ${request.limits.maxImages}."))
            if (commentCount > request.limits.maxComments) add(error("comment_limit", "Comment count exceeds ${request.limits.maxComments}."))
        }
        val pages = maxOf(1, pageBreaks + 1, sectionCount)
        val source = base.copy(
            pageCount = pages,
            protected = protected,
            activeContent = macro,
            externalRelationships = external,
        )
        return ImportPreflight(
            source = source,
            detectedFormat = ImportFormat.DOCX,
            proposedIntent = request.intent,
            proposedMode = request.mode,
            warnings = warnings,
            errors = errors,
            estimatedRegions = mapOf(
                FidelityState.NATIVE to (paragraphCount + tableCount),
                FidelityState.HYBRID to imageCount,
                FidelityState.UNSUPPORTED to (external + embedded + if (macro) 1 else 0),
            ),
            metrics = InterchangeMetrics(
                sourceBytesRead = request.bytes.size.toLong(),
                zipEntriesProcessed = pkg.entryCount,
                expandedBytesRead = pkg.expandedBytes,
                xmlNodesParsed = listOfNotNull(document?.nodeCount, relationships?.nodeCount, comments?.nodeCount).sum(),
                durationNanos = System.nanoTime() - started,
            ),
        )
    }

    private fun inspectXlsx(request: ImportRequest, base: SourceArtifactMetadata, started: Long): ImportPreflight {
        val pkg = BoundedPackageReader.read(request.bytes, request.limits) { it in officePreflightPaths }
        val names = pkg.names()
        val protected = "EncryptionInfo" in names || "EncryptedPackage" in names
        val macro = names.any { it.endsWith("vbaProject.bin", true) }
        val workbook = pkg["xl/workbook.xml"]?.let { SecureXml.parse(it, request.limits) }
        val rels = pkg["xl/_rels/workbook.xml.rels"]?.let { SecureXml.parse(it, request.limits) }
        val sheets = workbook?.root?.descendants("sheet")?.count() ?: 0
        val external = names.count { it.startsWith("xl/externalLinks/") } +
            (rels?.root?.descendants("Relationship")?.count { it.attribute("TargetMode").equals("External", true) } ?: 0)
        val warnings = buildList {
            if (macro) add(warning("active_content", "Macros are present and will remain inert."))
            if (external > 0) add(warning("external_workbooks", "$external external workbook relationship(s) will remain inert."))
            if (names.any { it.startsWith("xl/pivot") }) add(warning("pivots", "Pivot content is reported but not made editable in Phase 8."))
        }
        val errors = buildList {
            if (protected) add(error("encrypted_office", "Encrypted Office packages cannot be imported without decryption."))
            if (sheets > request.limits.maxSheets) add(error("sheet_limit", "Workbook exceeds ${request.limits.maxSheets} sheets."))
        }
        return ImportPreflight(
            source = base.copy(sheetCount = sheets, protected = protected, activeContent = macro, externalRelationships = external),
            detectedFormat = ImportFormat.XLSX,
            proposedIntent = request.intent,
            proposedMode = request.mode,
            warnings = warnings,
            errors = errors,
            estimatedRegions = mapOf(
                FidelityState.NATIVE to sheets,
                FidelityState.UNSUPPORTED to (external + if (macro) 1 else 0),
            ),
            metrics = InterchangeMetrics(
                sourceBytesRead = request.bytes.size.toLong(),
                zipEntriesProcessed = pkg.entryCount,
                expandedBytesRead = pkg.expandedBytes,
                xmlNodesParsed = listOfNotNull(workbook?.nodeCount, rels?.nodeCount).sum(),
                durationNanos = System.nanoTime() - started,
            ),
        )
    }

    private fun inspectPdf(request: ImportRequest, base: SourceArtifactMetadata, started: Long): ImportPreflight {
        val text = request.bytes.toString(StandardCharsets.ISO_8859_1)
        val protected = Regex("/Encrypt\\b").containsMatchIn(text)
        val activeTokens = listOf("/JavaScript", "/JS", "/Launch", "/EmbeddedFile", "/RichMedia")
        val active = activeTokens.count { it in text }
        val pages = Regex("/Type\\s*/Page(?!s)\\b").findAll(text).count().coerceAtLeast(1)
        val errors = buildList {
            if (!text.startsWith("%PDF-")) add(error("corrupt_pdf", "The PDF header is missing or corrupt."))
            if (protected) add(error("protected_pdf", "Password-protected PDFs cannot be imported without decryption."))
            if (pages > request.limits.maxPages) add(error("page_limit", "PDF exceeds ${request.limits.maxPages} pages."))
            if ("%%EOF" !in text.takeLast(minOf(text.length, 4_096))) add(error("corrupt_pdf", "The PDF end marker is missing."))
        }
        val warnings = buildList {
            if (active > 0) add(warning("pdf_active_content", "$active active PDF feature(s) will remain inert."))
        }
        return ImportPreflight(
            source = base.copy(pageCount = pages, protected = protected, activeContent = active > 0),
            detectedFormat = ImportFormat.PDF,
            proposedIntent = request.intent,
            proposedMode = request.mode,
            warnings = warnings,
            errors = errors,
            estimatedRegions = mapOf(FidelityState.FIXED to pages, FidelityState.UNSUPPORTED to active),
            metrics = InterchangeMetrics(sourceBytesRead = request.bytes.size.toLong(), durationNanos = System.nanoTime() - started),
        )
    }

    private fun warning(code: String, message: String) = FidelityIssue(code, message, FidelitySeverity.WARNING)
    private fun error(code: String, message: String) = FidelityIssue(code, message, FidelitySeverity.ERROR)
}

internal val ImportFormat.mediaType: String get() = when (this) {
    ImportFormat.DOCX -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
    ImportFormat.PDF -> "application/pdf"
    ImportFormat.XLSX -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
    ImportFormat.UNKNOWN -> "application/octet-stream"
}
