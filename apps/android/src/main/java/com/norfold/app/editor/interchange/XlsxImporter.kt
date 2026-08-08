package com.norfold.app.editor.interchange

import com.norfold.app.domain.BlockDocument
import com.norfold.app.domain.InlineText
import com.norfold.app.domain.TableBlock
import com.norfold.app.domain.TableCell
import com.norfold.app.domain.withInterchangeData
import com.norfold.app.editor.calculation.CalculationCell
import com.norfold.app.editor.calculation.CalculationColumn
import com.norfold.app.editor.calculation.CalculationRow
import com.norfold.app.editor.calculation.CalculationSheet
import com.norfold.app.editor.calculation.CalculationWorkbook
import com.norfold.app.editor.calculation.CalculationWorkbookJson
import com.norfold.app.editor.calculation.CellId
import com.norfold.app.editor.calculation.CellFormat
import com.norfold.app.editor.calculation.CellInput
import com.norfold.app.editor.calculation.CellNumberFormat
import com.norfold.app.editor.calculation.MergedRegion
import com.norfold.app.editor.calculation.MergedRegionId
import com.norfold.app.editor.calculation.NamedRange
import com.norfold.app.editor.calculation.NamedRangeId
import com.norfold.app.editor.calculation.NumberFormatKind
import com.norfold.app.editor.calculation.StableCellRange
import com.norfold.app.editor.calculation.FormulaParseResult
import com.norfold.app.editor.calculation.FormulaParser
import com.norfold.app.editor.calculation.SheetId
import com.norfold.app.editor.calculation.WorkbookId

/** XLSX adapter over the shared Phase-4 workbook. It never evaluates macros or external links. */
object XlsxImporter {
    private data class SheetRef(val name: String, val relationshipId: String, val hidden: Boolean)

    fun import(request: ImportRequest, preflight: ImportPreflight = ImportPreflightService.inspect(request)): ImportResult {
        val started = System.nanoTime()
        if (preflight.detectedFormat != ImportFormat.XLSX || !preflight.canApply) {
            return ImportResult.Failure(preflight.errors.ifEmpty {
                listOf(FidelityIssue("wrong_format", "XLSX importer received a different or unsafe format.", FidelitySeverity.ERROR))
            }, preflight.metrics)
        }
        return try {
            val pkg = BoundedPackageReader.read(request.bytes, request.limits)
            val workbookNode = SecureXml.parse(pkg["xl/workbook.xml"] ?: error("XLSX workbook.xml is missing"), request.limits).root
            val rels = relationships(pkg["xl/_rels/workbook.xml.rels"], request.limits)
            val sharedStrings = sharedStrings(pkg["xl/sharedStrings.xml"], request.limits)
            val styles = parseStyles(pkg["xl/styles.xml"], request.limits)
            val sourceHash = preflight.source.sha256
            val refs = workbookNode.descendants("sheet").mapNotNull { node ->
                val name = node.attribute("name")?.takeIf(String::isNotBlank) ?: return@mapNotNull null
                val rid = node.attribute("id") ?: return@mapNotNull null
                SheetRef(name.take(31), rid, node.attribute("state") in setOf("hidden", "veryHidden"))
            }.take(request.limits.maxSheets).toList()
            if (refs.isEmpty()) error("XLSX has no readable sheets")
            var processed = 0
            val regions = mutableListOf<FidelityRegion>()
            val issues = preflight.warnings.toMutableList()
            val parser = FormulaParser()
            val sheets = refs.mapIndexed { sheetIndex, ref ->
                val target = rels[ref.relationshipId] ?: "worksheets/sheet${sheetIndex + 1}.xml"
                val path = resolveXlPath(target)
                val root = SecureXml.parse(pkg[path] ?: error("Missing worksheet ${ref.name}"), request.limits).root
                val cellsByAddress = root.descendants("c").toList()
                processed += cellsByAddress.size
                if (processed > request.limits.maxCells) throw ImportSecurityException("Cell count exceeds ${request.limits.maxCells}")
                var maxRow = 0
                var maxColumn = 0
                val parsedCells = cellsByAddress.mapNotNull { cell ->
                    val address = cell.attribute("r") ?: return@mapNotNull null
                    val (row, column) = parseA1(address) ?: return@mapNotNull null
                    if (row >= request.limits.maxRows || column >= request.limits.maxColumns) {
                        throw ImportSecurityException("Worksheet dimensions exceed configured limits")
                    }
                    maxRow = maxOf(maxRow, row)
                    maxColumn = maxOf(maxColumn, column)
                    Triple(row, column, parseCell(cell, sharedStrings, styles, parser, sourceHash, ref.name, address, regions, issues))
                }
                val rowCount = maxOf(1, maxRow + 1)
                val columnCount = maxOf(1, maxColumn + 1)
                val sheetId = SheetId(stableImportId("sheet", sourceHash, ref.name))
                val rowNodes = root.descendants("row").associateBy { (it.attribute("r")?.toIntOrNull() ?: 0) - 1 }
                val columnNodes = root.descendants("col").toList()
                val rows = List(rowCount) { index -> CalculationRow(com.norfold.app.editor.calculation.RowId(stableImportId("row", sourceHash, "${ref.name}/$index")), rowNodes[index]?.attribute("ht")?.toFloatOrNull(), rowNodes[index]?.attribute("hidden") == "1") }
                val columns = List(columnCount) { index ->
                    val source = columnNodes.firstOrNull { node -> index + 1 in (node.attribute("min")?.toIntOrNull() ?: -1)..(node.attribute("max")?.toIntOrNull() ?: -1) }
                    CalculationColumn(com.norfold.app.editor.calculation.ColumnId(stableImportId("column", sourceHash, "${ref.name}/$index")), widthPoints = source?.attribute("width")?.toFloatOrNull()?.times(7f), hidden = source?.attribute("hidden") == "1")
                }
                val cells = parsedCells.map { (row, column, parsed) ->
                    CalculationCell(
                        id = CellId(stableImportId("cell", sourceHash, "${ref.name}/${row}/${column}")),
                        rowId = rows[row].id,
                        columnId = columns[column].id,
                        input = parsed.first,
                        format = parsed.second,
                    )
                }
                val merged = root.descendants("mergeCell").mapNotNull { node ->
                    val parts = node.attribute("ref")?.split(':') ?: return@mapNotNull null
                    val start = parseA1(parts.first()) ?: return@mapNotNull null
                    val end = parseA1(parts.getOrElse(1) { parts.first() }) ?: return@mapNotNull null
                    val startCoordinate = CalculationSheet(SheetId("temporary"), "temporary", rowCount, columnCount, rows, columns).coordinateAt(start.first, start.second) ?: return@mapNotNull null
                    val endCoordinate = startCoordinate.copy(rowId = rows[end.first].id, columnId = columns[end.second].id)
                    MergedRegion(MergedRegionId(stableImportId("merge", sourceHash, "${ref.name}/${node.attribute("ref")}")), StableCellRange(startCoordinate.copy(sheetId = sheetId), endCoordinate.copy(sheetId = sheetId)))
                }.toList()
                if (root.descendants("dataValidation").any()) issues += FidelityIssue("xlsx_validation", "Data validation rules not representable by this adapter are reported and not executed.", FidelitySeverity.WARNING)
                if (root.descendants("conditionalFormatting").any()) issues += FidelityIssue("xlsx_conditional_format", "Unsupported conditional formats are reported and remain inert.", FidelitySeverity.WARNING)
                regions += FidelityRegion(
                    stableRegionId(sourceHash, "sheet/${ref.name}"), sheetId.value, "sheet/${ref.name}",
                    if (issues.any { it.code.startsWith("xlsx_") }) FidelityState.HYBRID else FidelityState.NATIVE,
                    "Worksheet ${ref.name}", "Imported into the shared Phase-4 CalculationWorkbook.",
                )
                CalculationSheet(sheetId, ref.name, rowCount, columnCount, rows, columns, cells, mergedRegions = merged, hidden = ref.hidden)
            }
            val namedRanges = workbookNode.descendants("definedName").mapNotNull { node ->
                val name = node.attribute("name") ?: return@mapNotNull null
                val source = node.allText().removePrefix("=")
                val split = source.lastIndexOf('!')
                if (split < 0) return@mapNotNull null
                val sheetName = source.substring(0, split).trim('\'', ' ')
                val sheet = sheets.firstOrNull { it.name == sheetName } ?: return@mapNotNull null
                val range = source.substring(split + 1).split(':')
                val start = parseA1(range.first()) ?: return@mapNotNull null
                val end = parseA1(range.getOrElse(1) { range.first() }) ?: return@mapNotNull null
                val startCoordinate = sheet.coordinateAt(start.first, start.second) ?: return@mapNotNull null
                val endCoordinate = sheet.coordinateAt(end.first, end.second) ?: return@mapNotNull null
                runCatching { NamedRange(NamedRangeId(stableImportId("named-range", sourceHash, name)), name, StableCellRange(startCoordinate, endCoordinate)) }.getOrNull()
            }.toList()
            val visibleSheets = if (sheets.any { !it.hidden }) sheets else sheets.mapIndexed { index, sheet -> if (index == 0) sheet.copy(hidden = false) else sheet }
            val workbook = CalculationWorkbook(
                id = WorkbookId(stableImportId("workbook", sourceHash, request.fileName)),
                displayName = request.fileName.substringBeforeLast('.').ifBlank { "Imported workbook" },
                sheets = visibleSheets,
                activeSheetId = visibleSheets.first { !it.hidden }.id,
                namedRanges = namedRanges,
            )
            val preview = workbook.activeSheet()
            val rowLimit = minOf(preview.rowCount, 100)
            val columnLimit = minOf(preview.columnCount, 50)
            fun display(row: Int, column: Int): String = when (val input = preview.cellAt(row, column)?.input) {
                is CellInput.Text -> input.value
                is CellInput.Number -> input.canonical
                is CellInput.BooleanValue -> input.value.toString()
                is CellInput.DateValue -> input.isoDate
                is CellInput.DateTimeValue -> input.isoDateTime
                is CellInput.Formula -> input.source
                else -> ""
            }
            val legacyRows = List(rowLimit) { row -> List(columnLimit) { column -> TableCell(listOf(InlineText(display(row, column)))) } }
            val table = TableBlock(
                id = stableImportId("table", sourceHash, "workbook-preview"),
                headers = legacyRows.firstOrNull().orEmpty(),
                rows = legacyRows.drop(1),
                calculationPayload = CalculationWorkbookJson.encode(workbook),
                calculationSchemaVersion = 1,
            )
            val compatibility = compatibilityMetadata(pkg, request)
            val report = FidelityReport(ImportFormat.XLSX, request.mode, request.intent, regions, issues)
            val metadata = InterchangeArtifactMetadata(1, preflight.source, compatibility, report)
            val document = BlockDocument(listOf(table)).withInterchangeData(InterchangeMetadataCodec.encode(metadata)).normalized()
            val sourceAsset = ImportedAsset(stableImportId("source", sourceHash, request.fileName), preflight.source.mediaType, request.bytes, request.fileName)
            ImportResult.Success(
                ImportedArtifact(document, workbook, listOf(sourceAsset), preflight.source, compatibility, report),
                preflight.metrics.copy(spreadsheetCellsProcessed = processed, durationNanos = System.nanoTime() - started),
            )
        } catch (e: Exception) {
            ImportResult.Failure(listOf(FidelityIssue("xlsx_import_failed", e.message ?: "XLSX import failed.", FidelitySeverity.ERROR)), preflight.metrics.copy(durationNanos = System.nanoTime() - started))
        }
    }

    private fun relationships(bytes: ByteArray?, limits: ImportSecurityLimits): Map<String, String> {
        if (bytes == null) return emptyMap()
        return SecureXml.parse(bytes, limits).root.descendants("Relationship").mapNotNull { node ->
            if (node.attribute("TargetMode").equals("External", true)) return@mapNotNull null
            val id = node.attribute("Id") ?: return@mapNotNull null
            val target = node.attribute("Target") ?: return@mapNotNull null
            id to target
        }.toMap()
    }

    private fun sharedStrings(bytes: ByteArray?, limits: ImportSecurityLimits): List<String> = bytes?.let {
        SecureXml.parse(it, limits).root.descendants("si").map { si -> si.descendants("t").joinToString("") { node -> node.allText() } }.toList()
    }.orEmpty()

    private data class XlsxStyle(val format: CellFormat, val dateTime: Boolean)

    private fun parseStyles(bytes: ByteArray?, limits: ImportSecurityLimits): List<XlsxStyle> {
        if (bytes == null) return listOf(XlsxStyle(CellFormat(), false))
        val root = SecureXml.parse(bytes, limits).root
        val custom = root.descendants("numFmt").mapNotNull { node ->
            val id = node.attribute("numFmtId")?.toIntOrNull() ?: return@mapNotNull null
            id to node.attribute("formatCode").orEmpty()
        }.toMap()
        val fontNodes = root.child("fonts")?.children("font").orEmpty()
        return root.child("cellXfs")?.children("xf")?.map { xf ->
            val numId = xf.attribute("numFmtId")?.toIntOrNull() ?: 0
            val formatCode = custom[numId].orEmpty().lowercase()
            val date = numId in 14..22 || listOf("yy", "dd", "mm", "hh", "ss").any { it in formatCode }
            val font = fontNodes.getOrNull(xf.attribute("fontId")?.toIntOrNull() ?: 0)
            XlsxStyle(
                CellFormat(
                    bold = font?.child("b")?.let { true },
                    italic = font?.child("i")?.let { true },
                    fontSizePoints = font?.child("sz")?.attribute("val")?.toFloatOrNull(),
                    numberFormat = CellNumberFormat(if (date) NumberFormatKind.DATE_TIME else NumberFormatKind.GENERAL),
                ),
                date,
            )
        }.orEmpty().ifEmpty { listOf(XlsxStyle(CellFormat(), false)) }
    }

    private fun parseCell(
        cell: XmlNode,
        strings: List<String>,
        styles: List<XlsxStyle>,
        parser: FormulaParser,
        hash: String,
        sheet: String,
        address: String,
        regions: MutableList<FidelityRegion>,
        issues: MutableList<FidelityIssue>,
    ): Pair<CellInput, CellFormat> {
        val style = styles.getOrNull(cell.attribute("s")?.toIntOrNull() ?: 0) ?: XlsxStyle(CellFormat(), false)
        val formula = cell.child("f")?.allText()?.takeIf(String::isNotBlank)?.let { if (it.startsWith('=')) it else "=$it" }
        if (formula != null) {
            val state = when {
                '[' in formula || Regex("https?://", RegexOption.IGNORE_CASE).containsMatchIn(formula) -> FidelityState.UNSUPPORTED
                parser.parse(formula) is FormulaParseResult.Success -> FidelityState.NATIVE
                else -> FidelityState.HYBRID
            }
            val regionId = stableRegionId(hash, "sheet/$sheet/cell/$address")
            regions += FidelityRegion(regionId, "$sheet!$address", "sheet/$sheet/cell/$address", state, "Formula $address", "Source formula is preserved and evaluated only by the shared Phase-4 engine.", state != FidelityState.UNSUPPORTED)
            if (state != FidelityState.NATIVE) issues += FidelityIssue("xlsx_formula_${state.name.lowercase()}", "Formula at $sheet!$address is preserved but is not fully supported.", FidelitySeverity.WARNING, regionId)
            return CellInput.Formula(formula) to style.format
        }
        val value = cell.child("v")?.allText().orEmpty()
        val input = when (cell.attribute("t")) {
            "s" -> CellInput.Text(strings.getOrNull(value.toIntOrNull() ?: -1).orEmpty())
            "inlineStr", "str" -> CellInput.Text(cell.descendants("t").joinToString("") { it.allText() }.ifBlank { value })
            "b" -> CellInput.BooleanValue(value == "1" || value.equals("true", true))
            "e" -> CellInput.Text(value)
            else -> if (value.isBlank()) CellInput.Blank else if (style.dateTime) excelDate(value) ?: CellInput.Text(value) else runCatching { CellInput.Number(value) }.getOrElse { CellInput.Text(value) }
        }
        return input to style.format
    }

    private fun excelDate(value: String): CellInput? = value.toDoubleOrNull()?.let { serial ->
        val whole = kotlin.math.floor(serial).toLong()
        val seconds = kotlin.math.round((serial - whole) * 86_400.0).toLong().coerceIn(0, 86_399)
        val date = java.time.LocalDate.of(1899, 12, 30).plusDays(whole)
        if (seconds == 0L) CellInput.DateValue(date.toString()) else CellInput.DateTimeValue(date.atStartOfDay().plusSeconds(seconds).toString())
    }

    private fun parseA1(value: String): Pair<Int, Int>? {
        val match = Regex("\\$?([A-Za-z]+)\\$?([0-9]+)").matchEntire(value) ?: return null
        var column = 0
        match.groupValues[1].uppercase().forEach { column = column * 26 + (it - 'A' + 1) }
        return (match.groupValues[2].toInt() - 1) to (column - 1)
    }

    private fun resolveXlPath(target: String): String {
        val clean = target.replace('\\', '/').removePrefix("/")
        val base = if (clean.startsWith("xl/")) clean else "xl/$clean"
        val parts = mutableListOf<String>()
        base.split('/').forEach { part ->
            when (part) { "", "." -> Unit; ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.lastIndex) else throw ImportSecurityException("Unsafe XLSX relationship"); else -> parts += part }
        }
        return parts.joinToString("/")
    }

    private fun compatibilityMetadata(pkg: BoundedPackage, request: ImportRequest): CompatibilityMetadata {
        if (request.mode != ImportMode.COMPATIBILITY) return CompatibilityMetadata()
        val kept = mutableListOf<CompatibilityEntry>()
        val omitted = mutableListOf<String>()
        var bytes = 0L
        pkg.entries.values.sortedBy { it.name }.forEach { entry ->
            if (!entry.name.endsWith(".xml", true) && !entry.name.endsWith(".rels", true)) return@forEach
            if (bytes + entry.bytes.size > request.limits.maxCompatibilityBytes) omitted += entry.name else {
                bytes += entry.bytes.size
                kept += CompatibilityEntry(entry.name, "application/xml", sha256(entry.bytes), java.util.Base64.getEncoder().encodeToString(entry.bytes))
            }
        }
        return CompatibilityMetadata(entries = kept, preservedBytes = bytes, omittedPaths = omitted)
    }
}
