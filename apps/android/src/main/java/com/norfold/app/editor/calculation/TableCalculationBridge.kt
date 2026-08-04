package com.norfold.app.editor.calculation

import com.norfold.app.domain.InlineText
import com.norfold.app.domain.LegacyTableRecovery
import com.norfold.app.domain.TableAlignment
import com.norfold.app.domain.TableBlock
import com.norfold.app.domain.TableCell
import com.norfold.app.domain.plainText

data class TableWorkbookView(
    val workbook: CalculationWorkbook,
    val tableId: EmbeddedTableId,
    val upgraded: Boolean,
)

sealed interface TableUpgradeResult {
    data class Success(val block: TableBlock, val view: TableWorkbookView) : TableUpgradeResult
    data class Failure(val message: String, val cause: Throwable? = null) : TableUpgradeResult
}

/**
 * The only bridge between persisted [TableBlock]s and the shared calculation domain. Legacy fields are a
 * compatibility projection; once [TableBlock.calculationPayload] exists, the workbook is authoritative.
 */
object TableCalculationBridge {
    const val SCHEMA_VERSION = 1

    fun view(block: TableBlock): TableWorkbookView {
        val payload = block.calculationPayload ?: return legacyWorkbook(block)
        return runCatching {
            val workbook = CalculationWorkbookJson.decode(payload)
            val tableId = block.calculationTableId?.let(::EmbeddedTableId)
                ?: workbook.embeddedTables.firstOrNull()?.id
                ?: error("Calculation table payload has no embedded table identity")
            require(workbook.embeddedTables.any { it.id == tableId }) { "Calculation table identity is missing from its workbook" }
            TableWorkbookView(workbook, tableId, upgraded = true)
        }.getOrElse {
            // Preserve an openable, read-only projection when a typed payload is corrupt. upgrade() still
            // refuses edits, so this fallback can never overwrite the sole malformed authoritative copy.
            legacyWorkbook(
                block.copy(
                    calculationPayload = null,
                    calculationTableId = null,
                    calculationSchemaVersion = null,
                ),
            )
        }
    }

    fun editorText(block: TableBlock, legacyRow: Int, column: Int): String {
        val view = view(block)
        val table = view.workbook.embeddedTables.firstOrNull { it.id == view.tableId } ?: return ""
        val sheet = view.workbook.sheet(table.range.start.sheetId) ?: return ""
        val row = sheet.rowIndex(table.range.start.rowId) + when {
            legacyRow < 0 -> 0
            table.headerRow -> legacyRow + 1
            else -> legacyRow
        }
        val absoluteColumn = sheet.columnIndex(table.range.start.columnId) + column
        return sheet.cellAt(row, absoluteColumn)?.input?.editorText().orEmpty()
    }

    /**
     * Copy-on-write conversion. The legacy pre-image is captured first and retained permanently, so callers
     * can restore the original even after repeated edits. Calling this on an upgraded table is idempotent.
     */
    fun upgrade(block: TableBlock): TableUpgradeResult {
        if (block.calculationPayload != null) {
            return runCatching {
                val resolved = view(block)
                require(resolved.upgraded) { "The typed table payload is not safe to edit" }
                TableUpgradeResult.Success(block, resolved)
            }
                .getOrElse { TableUpgradeResult.Failure("The typed table payload could not be decoded", it) }
        }
        return runCatching {
            val projected = legacyWorkbook(block)
            val recovery = block.legacyRecovery ?: LegacyTableRecovery(
                headers = block.headers,
                rows = block.rows,
                columnWidthsDp = block.columnWidthsDp,
                columnAlignments = block.columnAlignments,
            )
            val upgraded = block.copy(
                calculationPayload = CalculationWorkbookJson.encode(projected.workbook),
                calculationTableId = projected.tableId.value,
                calculationSchemaVersion = SCHEMA_VERSION,
                legacyRecovery = recovery,
            )
            TableUpgradeResult.Success(upgraded, projected.copy(upgraded = true))
        }.getOrElse { TableUpgradeResult.Failure("Legacy table conversion was refused to avoid data loss", it) }
    }

    fun restoreLegacy(block: TableBlock): TableBlock? = block.legacyRecovery?.let { recovery ->
        block.copy(
            headers = recovery.headers,
            rows = recovery.rows,
            columnWidthsDp = recovery.columnWidthsDp,
            columnAlignments = recovery.columnAlignments,
            calculationPayload = null,
            calculationTableId = null,
            calculationSchemaVersion = null,
            legacyRecovery = null,
        )
    }

    /** Persists [workbook] and refreshes the flag-off/Markdown compatibility projection from calculated values. */
    fun persist(block: TableBlock, workbook: CalculationWorkbook, tableId: EmbeddedTableId): TableBlock {
        val engine = CalculationEngine(workbook)
        val table = workbook.embeddedTables.firstOrNull { it.id == tableId }
            ?: error("Embedded table ${tableId.value} is missing")
        val sheet = workbook.sheet(table.range.start.sheetId) ?: error("Embedded table sheet is missing")
        val startRow = sheet.rowIndex(table.range.start.rowId)
        val endRow = sheet.rowIndex(table.range.end.rowId)
        val startColumn = sheet.columnIndex(table.range.start.columnId)
        val endColumn = sheet.columnIndex(table.range.end.columnId)
        require(minOf(startRow, endRow, startColumn, endColumn) >= 0) { "Embedded table range is stale" }
        val firstColumn = minOf(startColumn, endColumn)
        val lastColumn = maxOf(startColumn, endColumn)
        val rows = (minOf(startRow, endRow)..maxOf(startRow, endRow)).map { row ->
            (firstColumn..lastColumn).map { column ->
                TableCell(engine.displayAt(sheet.id, row, column).takeIf(String::isNotEmpty)?.let { listOf(InlineText(it)) }.orEmpty())
            }
        }
        val headerRows = if (table.headerRow) rows.take(1) else emptyList()
        val dataStart = if (table.headerRow) 1 else 0
        val dataEndExclusive = rows.size - if (table.totalRow) 1 else 0
        return block.copy(
            headers = headerRows.firstOrNull().orEmpty(),
            rows = rows.subList(dataStart.coerceAtMost(rows.size), dataEndExclusive.coerceAtLeast(dataStart).coerceAtMost(rows.size)),
            columnWidthsDp = (firstColumn..lastColumn).map { column ->
                sheet.columns[column].widthPoints?.div(0.75f) ?: block.columnWidthsDp.getOrNull(column - firstColumn) ?: 120f
            },
            columnAlignments = (firstColumn..lastColumn).map { column ->
                val cellFormat = sheet.cellAt(startRow, column)?.format
                when (cellFormat?.horizontalAlignment) {
                    HorizontalCellAlignment.CENTER -> TableAlignment.Center
                    HorizontalCellAlignment.END -> TableAlignment.End
                    else -> TableAlignment.Start
                }
            },
            calculationPayload = CalculationWorkbookJson.encode(workbook),
            calculationTableId = tableId.value,
            calculationSchemaVersion = SCHEMA_VERSION,
            legacyRecovery = block.legacyRecovery ?: LegacyTableRecovery(
                headers = block.headers,
                rows = block.rows,
                columnWidthsDp = block.columnWidthsDp,
                columnAlignments = block.columnAlignments,
            ),
        )
    }

    private fun legacyWorkbook(block: TableBlock): TableWorkbookView {
        val columnCount = maxOf(block.headers.size, block.rows.maxOfOrNull { it.size } ?: 0, 1)
        // Only a table that *has* headers contributes a header row. Unconditionally prepending `block.headers`
        // gave a headerless table a phantom empty row 0 while `headerRow` stayed false, so every legacy row
        // index resolved one row too high: editing data row 1 overwrote data row 0, and the first save grew
        // the table by a blank leading row that the user never typed.
        val allRows = (if (block.headers.isNotEmpty()) listOf(block.headers) else emptyList()) + block.rows
        val rowCount = allRows.size.coerceAtLeast(1)
        val rows = List(rowCount) { CalculationRow() }
        val columns = List(columnCount) { column ->
            CalculationColumn(widthPoints = block.columnWidthsDp.getOrNull(column)?.times(0.75f))
        }
        val cells = buildList {
            allRows.forEachIndexed { rowIndex, row ->
                row.forEachIndexed { columnIndex, source ->
                    val text = source.content.plainText()
                    val legacyAlignment = block.columnAlignments.getOrNull(columnIndex)
                    val format = CellFormat(
                        bold = true.takeIf { rowIndex == 0 && block.headers.isNotEmpty() },
                        horizontalAlignment = when (legacyAlignment) {
                            TableAlignment.Center -> HorizontalCellAlignment.CENTER
                            TableAlignment.End -> HorizontalCellAlignment.END
                            TableAlignment.Start -> HorizontalCellAlignment.START
                            null -> null
                        },
                    )
                    if (text.isNotEmpty() || format != CellFormat()) {
                        add(
                            CalculationCell(
                                rowId = rows[rowIndex].id,
                                columnId = columns[columnIndex].id,
                                input = CellInput.Text(text),
                                format = format,
                            ),
                        )
                    }
                }
            }
        }
        val sheet = CalculationSheet(
            name = "Table",
            rowCount = rowCount,
            columnCount = columnCount,
            rows = rows,
            columns = columns,
            cells = cells,
        )
        val range = StableCellRange(
            sheet.coordinateAt(0, 0)!!,
            sheet.coordinateAt(rowCount - 1, columnCount - 1)!!,
        )
        val table = EmbeddedTableDefinition(
            name = "Table1",
            range = range,
            columns = uniqueLegacyColumnNames(block, columns),
            headerRow = block.headers.isNotEmpty(),
        )
        val workbook = CalculationWorkbook(
            displayName = "Embedded table",
            sheets = listOf(sheet),
            activeSheetId = sheet.id,
            embeddedTables = listOf(table),
        )
        return TableWorkbookView(workbook, table.id, upgraded = false)
    }

    private fun uniqueLegacyColumnNames(block: TableBlock, columns: List<CalculationColumn>): List<EmbeddedTableColumn> {
        val used = linkedSetOf<String>()
        return columns.mapIndexed { index, column ->
            val raw = block.headers.getOrNull(index)?.content?.plainText()?.trim().orEmpty()
            val base = raw.replace(Regex("[^A-Za-z0-9_.]"), "_")
                .takeIf { isValidCanonicalName(it) }
                ?: "Column${index + 1}"
            var candidate = base
            var suffix = 2
            while (!used.add(candidate.lowercase())) candidate = "$base$suffix".also { suffix++ }
            EmbeddedTableColumn(column.id, candidate)
        }
    }
}
