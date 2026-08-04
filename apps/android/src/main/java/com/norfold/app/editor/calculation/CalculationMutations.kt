package com.norfold.app.editor.calculation

import java.math.BigDecimal
import java.time.LocalDate

data class CalculationMutationResult(
    val workbook: CalculationWorkbook,
    val activeCell: StableCellCoordinate? = null,
)

data class PasteCellOutcome(
    val row: Int,
    val column: Int,
    val accepted: Boolean,
    val message: String? = null,
)

data class PasteReport(
    val workbook: CalculationWorkbook,
    val outcomes: List<PasteCellOutcome>,
) {
    val acceptedCount: Int get() = outcomes.count(PasteCellOutcome::accepted)
    val rejectedCount: Int get() = outcomes.size - acceptedCount
}

object CalculationMutations {
    fun insertRow(workbook: CalculationWorkbook, sheetId: SheetId, index: Int): CalculationMutationResult {
        val sheet = workbook.sheet(sheetId) ?: return CalculationMutationResult(workbook)
        val at = index.coerceIn(0, sheet.rowCount)
        val row = CalculationRow()
        val nextSheet = sheet.copy(
            rowCount = sheet.rowCount + 1,
            rows = sheet.rows.toMutableList().apply { add(at, row) },
            frozenRows = if (at < sheet.frozenRows) sheet.frozenRows + 1 else sheet.frozenRows,
        )
        val next = rewriteFormulas(workbook.withSheet(nextSheet)) { expression, owner ->
            FormulaReferenceTransform.shiftForInsert(expression, sheet.name, owner.name, rowIndex = at)
        }
        return CalculationMutationResult(next, next.sheet(sheetId)?.coordinateAt(at, 0))
    }

    fun insertColumn(workbook: CalculationWorkbook, sheetId: SheetId, index: Int): CalculationMutationResult {
        val sheet = workbook.sheet(sheetId) ?: return CalculationMutationResult(workbook)
        val at = index.coerceIn(0, sheet.columnCount)
        val column = CalculationColumn()
        val nextSheet = sheet.copy(
            columnCount = sheet.columnCount + 1,
            columns = sheet.columns.toMutableList().apply { add(at, column) },
            frozenColumns = if (at < sheet.frozenColumns) sheet.frozenColumns + 1 else sheet.frozenColumns,
        )
        val next = rewriteFormulas(workbook.withSheet(nextSheet)) { expression, owner ->
            FormulaReferenceTransform.shiftForInsert(expression, sheet.name, owner.name, columnIndex = at)
        }
        return CalculationMutationResult(next, next.sheet(sheetId)?.coordinateAt(0, at))
    }

    fun deleteRows(workbook: CalculationWorkbook, sheetId: SheetId, index: Int, count: Int = 1): CalculationMutationResult {
        val sheet = workbook.sheet(sheetId) ?: return CalculationMutationResult(workbook)
        if (count <= 0 || sheet.rowCount <= 1) return CalculationMutationResult(workbook)
        val at = index.coerceIn(0, sheet.rowCount - 1)
        val actual = count.coerceAtMost(sheet.rowCount - at).coerceAtMost(sheet.rowCount - 1)
        val removed = sheet.rows.subList(at, at + actual).map(CalculationRow::id).toSet()
        val nextRows = sheet.rows.filterNot { it.id in removed }
        val nextSheet = sheet.copy(
            rowCount = nextRows.size,
            rows = nextRows,
            cells = sheet.cells.filterNot { it.rowId in removed },
            mergedRegions = sheet.mergedRegions.mapNotNull { it.repairAfterRemovedRows(sheet, nextRows) },
            validations = sheet.validations.mapNotNull { it.copy(target = it.target.repairAfterRemovedRows(sheet, nextRows) ?: return@mapNotNull null) },
            conditionalFormats = sheet.conditionalFormats.mapNotNull { it.copy(target = it.target.repairAfterRemovedRows(sheet, nextRows) ?: return@mapNotNull null) },
            filters = sheet.filters.mapNotNull { it.copy(target = it.target.repairAfterRemovedRows(sheet, nextRows) ?: return@mapNotNull null) },
            sorts = sheet.sorts.mapNotNull { it.copy(target = it.target.repairAfterRemovedRows(sheet, nextRows) ?: return@mapNotNull null) },
            frozenRows = sheet.frozenRows.coerceAtMost(nextRows.size),
        )
        val replaced = workbook.withSheet(nextSheet).copy(
            namedRanges = workbook.namedRanges.mapNotNull { named ->
                if (named.target.start.sheetId != sheetId) named else named.copy(
                    target = named.target.repairAfterRemovedRows(sheet, nextRows) ?: return@mapNotNull null,
                )
            },
            embeddedTables = workbook.embeddedTables.mapNotNull { table ->
                if (table.range.start.sheetId != sheetId) table else table.copy(
                    range = table.range.repairAfterRemovedRows(sheet, nextRows) ?: return@mapNotNull null,
                    columns = table.columns.filter { it.id in nextSheet.columns.map(CalculationColumn::id) },
                )
            },
        )
        val next = rewriteFormulas(replaced) { expression, owner -> FormulaReferenceTransform.shiftForDelete(expression, sheet.name, owner.name, rowIndex = at, count = actual) }
        return CalculationMutationResult(next, next.sheet(sheetId)?.coordinateAt(at.coerceAtMost(nextSheet.rowCount - 1), 0))
    }

    fun deleteColumns(workbook: CalculationWorkbook, sheetId: SheetId, index: Int, count: Int = 1): CalculationMutationResult {
        val sheet = workbook.sheet(sheetId) ?: return CalculationMutationResult(workbook)
        if (count <= 0 || sheet.columnCount <= 1) return CalculationMutationResult(workbook)
        val at = index.coerceIn(0, sheet.columnCount - 1)
        val actual = count.coerceAtMost(sheet.columnCount - at).coerceAtMost(sheet.columnCount - 1)
        val removed = sheet.columns.subList(at, at + actual).map(CalculationColumn::id).toSet()
        val nextColumns = sheet.columns.filterNot { it.id in removed }
        val nextSheet = sheet.copy(
            columnCount = nextColumns.size,
            columns = nextColumns,
            cells = sheet.cells.filterNot { it.columnId in removed },
            mergedRegions = sheet.mergedRegions.mapNotNull { it.repairAfterRemovedColumns(sheet, nextColumns) },
            validations = sheet.validations.mapNotNull { it.copy(target = it.target.repairAfterRemovedColumns(sheet, nextColumns) ?: return@mapNotNull null) },
            conditionalFormats = sheet.conditionalFormats.mapNotNull { it.copy(target = it.target.repairAfterRemovedColumns(sheet, nextColumns) ?: return@mapNotNull null) },
            filters = sheet.filters.filterNot { it.columnId in removed }.mapNotNull {
                it.copy(target = it.target.repairAfterRemovedColumns(sheet, nextColumns) ?: return@mapNotNull null)
            },
            sorts = sheet.sorts.mapNotNull { sort ->
                val keys = sort.keys.filterNot { it.columnId in removed }
                val target = sort.target.repairAfterRemovedColumns(sheet, nextColumns) ?: return@mapNotNull null
                sort.copy(target = target, keys = keys)
            },
            frozenColumns = sheet.frozenColumns.coerceAtMost(nextColumns.size),
        )
        val replaced = workbook.withSheet(nextSheet).copy(
            namedRanges = workbook.namedRanges.mapNotNull { named ->
                if (named.target.start.sheetId != sheetId) named else named.copy(
                    target = named.target.repairAfterRemovedColumns(sheet, nextColumns) ?: return@mapNotNull null,
                )
            },
            embeddedTables = workbook.embeddedTables.mapNotNull { table ->
                if (table.range.start.sheetId != sheetId) table else table.copy(
                    range = table.range.repairAfterRemovedColumns(sheet, nextColumns) ?: return@mapNotNull null,
                    columns = table.columns.filterNot { it.id in removed },
                )
            },
        )
        val next = rewriteFormulas(replaced) { expression, owner -> FormulaReferenceTransform.shiftForDelete(expression, sheet.name, owner.name, columnIndex = at, count = actual) }
        return CalculationMutationResult(next, next.sheet(sheetId)?.coordinateAt(0, at.coerceAtMost(nextSheet.columnCount - 1)))
    }

    fun reorderRow(workbook: CalculationWorkbook, sheetId: SheetId, fromIndex: Int, toIndex: Int): CalculationMutationResult {
        val sheet = workbook.sheet(sheetId) ?: return CalculationMutationResult(workbook)
        if (fromIndex !in sheet.rows.indices || toIndex !in sheet.rows.indices || fromIndex == toIndex) {
            return CalculationMutationResult(workbook, sheet.coordinateAt(fromIndex.coerceIn(0, sheet.rowCount - 1), 0))
        }
        val oldRows = sheet.rows
        val rows = oldRows.toMutableList()
        val moved = rows.removeAt(fromIndex)
        rows.add(toIndex, moved)
        val remap = oldRows.mapIndexed { oldIndex, row -> oldIndex to rows.indexOfFirst { it.id == row.id } }.toMap()
        val replaced = workbook.withSheet(sheet.copy(rows = rows))
        val next = rewriteFormulas(replaced) { expression, owner ->
            FormulaReferenceTransform.remapPositions(expression, sheet.name, owner.name, rowIndexes = remap)
        }
        return CalculationMutationResult(next, next.sheet(sheetId)?.coordinateAt(toIndex, 0))
    }

    fun reorderColumn(workbook: CalculationWorkbook, sheetId: SheetId, fromIndex: Int, toIndex: Int): CalculationMutationResult {
        val sheet = workbook.sheet(sheetId) ?: return CalculationMutationResult(workbook)
        if (fromIndex !in sheet.columns.indices || toIndex !in sheet.columns.indices || fromIndex == toIndex) {
            return CalculationMutationResult(workbook, sheet.coordinateAt(0, fromIndex.coerceIn(0, sheet.columnCount - 1)))
        }
        val oldColumns = sheet.columns
        val columns = oldColumns.toMutableList()
        val moved = columns.removeAt(fromIndex)
        columns.add(toIndex, moved)
        val remap = oldColumns.mapIndexed { oldIndex, column -> oldIndex to columns.indexOfFirst { it.id == column.id } }.toMap()
        val embeddedTables = workbook.embeddedTables.map { table ->
            if (table.range.start.sheetId != sheetId) table else table.copy(
                columns = table.columns.sortedBy { embedded -> columns.indexOfFirst { it.id == embedded.id }.let { if (it < 0) Int.MAX_VALUE else it } },
            )
        }
        val replaced = workbook.withSheet(sheet.copy(columns = columns)).copy(embeddedTables = embeddedTables)
        val next = rewriteFormulas(replaced) { expression, owner ->
            FormulaReferenceTransform.remapPositions(expression, sheet.name, owner.name, columnIndexes = remap)
        }
        return CalculationMutationResult(next, next.sheet(sheetId)?.coordinateAt(0, toIndex))
    }

    fun renameSheet(workbook: CalculationWorkbook, sheetId: SheetId, name: String): CalculationWorkbook {
        val trimmed = name.trim()
        require(trimmed.isNotEmpty()) { "Sheet name cannot be blank" }
        require(workbook.sheets.none { it.id != sheetId && it.name.equals(trimmed, true) }) { "Sheet name already exists" }
        val old = workbook.sheet(sheetId) ?: return workbook
        val replaced = workbook.withSheet(old.copy(name = trimmed))
        return rewriteFormulas(replaced) { expression, _ -> FormulaReferenceTransform.renameSheet(expression, old.name, trimmed) }
    }

    fun reorderSheet(workbook: CalculationWorkbook, sheetId: SheetId, toIndex: Int): CalculationWorkbook {
        val from = workbook.sheets.indexOfFirst { it.id == sheetId }
        if (from < 0) return workbook
        val sheets = workbook.sheets.toMutableList()
        val moved = sheets.removeAt(from)
        sheets.add(toIndex.coerceIn(0, sheets.size), moved)
        return workbook.copy(sheets = sheets, revision = workbook.revision + 1)
    }

    fun addSheet(workbook: CalculationWorkbook, requestedName: String = "Sheet"): CalculationWorkbook {
        val name = uniqueSheetName(workbook, requestedName)
        val sheet = CalculationSheet(name = name)
        return workbook.copy(sheets = workbook.sheets + sheet, activeSheetId = sheet.id, revision = workbook.revision + 1)
    }

    fun duplicateSheet(workbook: CalculationWorkbook, sheetId: SheetId): CalculationWorkbook {
        val source = workbook.sheet(sheetId) ?: return workbook
        val rows = source.rows.map { CalculationRow(heightPoints = it.heightPoints, hidden = it.hidden) }
        val columns = source.columns.map { CalculationColumn(name = it.name, widthPoints = it.widthPoints, hidden = it.hidden) }
        val rowMap = source.rows.map(CalculationRow::id).zip(rows.map(CalculationRow::id)).toMap()
        val columnMap = source.columns.map(CalculationColumn::id).zip(columns.map(CalculationColumn::id)).toMap()
        fun remapCoordinate(coordinate: StableCellCoordinate): StableCellCoordinate = StableCellCoordinate(
            sheetId = SheetId("pending"),
            rowId = rowMap.getValue(coordinate.rowId),
            columnId = columnMap.getValue(coordinate.columnId),
        )
        val copyId = SheetId(java.util.UUID.randomUUID().toString())
        fun remapRange(range: StableCellRange): StableCellRange {
            val start = remapCoordinate(range.start).copy(sheetId = copyId)
            val end = remapCoordinate(range.end).copy(sheetId = copyId)
            return StableCellRange(start, end)
        }
        val copy = source.copy(
            id = copyId,
            name = uniqueSheetName(workbook, "${source.name} Copy"),
            rows = rows,
            columns = columns,
            cells = source.cells.map { it.copy(id = CellId(java.util.UUID.randomUUID().toString()), rowId = rowMap.getValue(it.rowId), columnId = columnMap.getValue(it.columnId)) },
            mergedRegions = source.mergedRegions.map { it.copy(id = MergedRegionId(java.util.UUID.randomUUID().toString()), range = remapRange(it.range)) },
            validations = source.validations.map { it.copy(id = ValidationRuleId(java.util.UUID.randomUUID().toString()), target = remapRange(it.target)) },
            conditionalFormats = source.conditionalFormats.map { it.copy(id = ConditionalRuleId(java.util.UUID.randomUUID().toString()), target = remapRange(it.target)) },
            filters = source.filters.map { definition ->
                definition.copy(
                    id = FilterDefinitionId(java.util.UUID.randomUUID().toString()),
                    target = remapRange(definition.target),
                    columnId = columnMap.getValue(definition.columnId),
                )
            },
            sorts = source.sorts.map { definition ->
                definition.copy(
                    id = SortDefinitionId(java.util.UUID.randomUUID().toString()),
                    target = remapRange(definition.target),
                    keys = definition.keys.map { it.copy(columnId = columnMap.getValue(it.columnId)) },
                )
            },
        )
        val copiedTables = workbook.embeddedTables.filter { it.range.start.sheetId == sheetId }.map { table ->
            val base = "${table.name}_Copy"
            var candidate = base
            var suffix = 2
            while (workbook.embeddedTables.any { it.name.equals(candidate, true) }) candidate = "${base}_$suffix".also { suffix++ }
            table.copy(
                id = EmbeddedTableId(java.util.UUID.randomUUID().toString()),
                name = candidate,
                range = remapRange(table.range),
                columns = table.columns.map { it.copy(id = columnMap.getValue(it.id)) },
            )
        }
        return workbook.copy(
            sheets = workbook.sheets + copy,
            activeSheetId = copy.id,
            embeddedTables = workbook.embeddedTables + copiedTables,
            revision = workbook.revision + 1,
        )
    }

    fun setSheetHidden(workbook: CalculationWorkbook, sheetId: SheetId, hidden: Boolean): CalculationWorkbook {
        val sheet = workbook.sheet(sheetId) ?: return workbook
        if (sheet.hidden == hidden) return workbook
        if (hidden && workbook.sheets.count { !it.hidden } <= 1) return workbook
        val sheets = workbook.sheets.map { if (it.id == sheetId) it.copy(hidden = hidden) else it }
        val nextActive = if (hidden && workbook.activeSheetId == sheetId) sheets.first { !it.hidden }.id else workbook.activeSheetId
        return workbook.copy(sheets = sheets, activeSheetId = nextActive, revision = workbook.revision + 1)
    }

    fun deleteSheet(workbook: CalculationWorkbook, sheetId: SheetId): CalculationWorkbook {
        if (workbook.sheets.size <= 1) return workbook
        val removed = workbook.sheet(sheetId) ?: return workbook
        // Deleting the last *visible* sheet must not leave every survivor hidden with one of them active: the
        // active sheet is what the grid renders, so it is revealed rather than left in a contradictory state.
        val survivors = workbook.sheets.filterNot { it.id == sheetId }
        val sheets = if (survivors.any { !it.hidden }) survivors else survivors.mapIndexed { index, sheet ->
            if (index == 0) sheet.copy(hidden = false) else sheet
        }
        val replaced = workbook.copy(
            sheets = sheets,
            activeSheetId = if (workbook.activeSheetId == sheetId) sheets.first { !it.hidden }.id else workbook.activeSheetId,
            namedRanges = workbook.namedRanges.filterNot { it.target.start.sheetId == sheetId },
            embeddedTables = workbook.embeddedTables.filterNot { it.range.start.sheetId == sheetId },
            revision = workbook.revision + 1,
        )
        return rewriteFormulas(replaced) { expression, _ ->
            FormulaReferenceTransform.map(expression) { reference ->
                if (reference.sheetName.equals(removed.name, true)) null else reference
            }
        }
    }

    fun renameNamedRange(workbook: CalculationWorkbook, id: NamedRangeId, newName: String): CalculationWorkbook {
        require(isValidCanonicalName(newName)) { "Invalid named range" }
        require(workbook.namedRanges.none { it.id != id && it.name.equals(newName, true) }) { "Named range already exists" }
        val old = workbook.namedRanges.firstOrNull { it.id == id } ?: return workbook
        return rewriteFormulas(
            workbook.copy(namedRanges = workbook.namedRanges.map { if (it.id == id) it.copy(name = newName) else it }, revision = workbook.revision + 1),
        ) { expression, _ -> FormulaReferenceTransform.renameSymbol(expression, old.name, newName) }
    }

    fun deleteNamedRange(workbook: CalculationWorkbook, id: NamedRangeId): CalculationWorkbook {
        val removed = workbook.namedRanges.firstOrNull { it.id == id } ?: return workbook
        return rewriteFormulas(
            workbook.copy(namedRanges = workbook.namedRanges.filterNot { it.id == id }, revision = workbook.revision + 1),
        ) { expression, _ -> FormulaReferenceTransform.deleteSymbol(expression, removed.name) }
    }

    fun renameEmbeddedTable(workbook: CalculationWorkbook, id: EmbeddedTableId, newName: String): CalculationWorkbook {
        require(isValidCanonicalName(newName)) { "Invalid table name" }
        require(workbook.embeddedTables.none { it.id != id && it.name.equals(newName, true) }) { "Table name already exists" }
        val old = workbook.embeddedTables.firstOrNull { it.id == id } ?: return workbook
        return rewriteFormulas(
            workbook.copy(embeddedTables = workbook.embeddedTables.map { if (it.id == id) it.copy(name = newName) else it }, revision = workbook.revision + 1),
        ) { expression, _ -> FormulaReferenceTransform.renameSymbol(expression, old.name, newName) }
    }

    fun renameEmbeddedColumn(workbook: CalculationWorkbook, tableId: EmbeddedTableId, columnId: ColumnId, newName: String): CalculationWorkbook {
        val table = workbook.embeddedTables.firstOrNull { it.id == tableId } ?: return workbook
        require(table.columns.none { it.id != columnId && it.name.equals(newName, true) }) { "Column name already exists" }
        val old = table.columns.firstOrNull { it.id == columnId } ?: return workbook
        val nextTable = table.copy(columns = table.columns.map { if (it.id == columnId) it.copy(name = newName) else it })
        return rewriteFormulas(
            workbook.copy(embeddedTables = workbook.embeddedTables.map { if (it.id == tableId) nextTable else it }, revision = workbook.revision + 1),
        ) { expression, _ -> FormulaReferenceTransform.renameSymbol(expression, old.name, newName, table.name) }
    }

    fun merge(workbook: CalculationWorkbook, range: StableCellRange): CalculationWorkbook {
        val sheet = workbook.sheet(range.start.sheetId) ?: return workbook
        if (sheet.mergedRegions.any { it.range.overlaps(range, sheet) }) return workbook
        return workbook.withSheet(sheet.copy(mergedRegions = sheet.mergedRegions + MergedRegion(range = range)))
    }

    fun split(workbook: CalculationWorkbook, mergedRegionId: MergedRegionId): CalculationWorkbook {
        val sheet = workbook.sheets.firstOrNull { candidate -> candidate.mergedRegions.any { it.id == mergedRegionId } } ?: return workbook
        return workbook.withSheet(sheet.copy(mergedRegions = sheet.mergedRegions.filterNot { it.id == mergedRegionId }))
    }

    fun pasteGrid(workbook: CalculationWorkbook, sheetId: SheetId, startRow: Int, startColumn: Int, clipboard: String): PasteReport {
        val rows = clipboard.replace("\r\n", "\n").replace('\r', '\n').split('\n')
            .dropLastWhile(String::isEmpty)
            .map { it.split('\t') }
        val mutations = buildList {
            rows.forEachIndexed { rowOffset, values ->
                values.forEachIndexed { columnOffset, raw ->
                    add(CellMutation(startRow + rowOffset, startColumn + columnOffset, CellInputParser.parse(raw)))
                }
            }
        }
        val engine = CalculationEngine(workbook)
        val results = engine.commitBatch(sheetId, mutations, confirmWarnings = true)
        return PasteReport(
            engine.workbook(),
            mutations.zip(results).map { (mutation, outcome) ->
                PasteCellOutcome(mutation.row, mutation.column, outcome.accepted, outcome.error?.detail ?: outcome.warning)
            },
        )
    }

    fun fill(workbook: CalculationWorkbook, sheetId: SheetId, source: GridAddress, targets: List<GridAddress>): CalculationWorkbook {
        val sheet = workbook.sheet(sheetId) ?: return workbook
        val sourceCell = sheet.cellAt(source.row, source.column) ?: return workbook
        val mutations = targets.mapNotNull { target ->
            if (sheet.coordinateAt(target.row, target.column) == null) return@mapNotNull null
            val input = when (val original = sourceCell.input) {
                is CellInput.Formula -> {
                    val parsed = FormulaParser().parse(original.source) as? FormulaParseResult.Success
                    parsed?.let {
                        CellInput.Formula(FormulaRenderer.render(FormulaReferenceTransform.offsetForFill(it.expression, target.row - source.row, target.column - source.column)))
                    } ?: original
                }
                else -> original
            }
            CellMutation(target.row, target.column, input)
        }
        val engine = CalculationEngine(workbook)
        val results = engine.commitBatch(sheetId, mutations, confirmWarnings = true)
        val accepted = mutations.zip(results).mapNotNull { (mutation, result) ->
            if (result.accepted) GridAddress(mutation.row, mutation.column) else null
        }
        return if (accepted.isEmpty()) workbook else applyFormat(engine.workbook(), sheetId, accepted, sourceCell.format)
    }

    fun fillSeries(workbook: CalculationWorkbook, sheetId: SheetId, targets: List<GridAddress>, start: CellInput, step: BigDecimal): CalculationWorkbook {
        val sheet = workbook.sheet(sheetId) ?: return workbook
        val mutations = targets.mapIndexedNotNull { index, target ->
            if (sheet.coordinateAt(target.row, target.column) == null) return@mapIndexedNotNull null
            val input = when (start) {
                is CellInput.Number -> CellInput.Number((start.canonical.toBigDecimal() + step * BigDecimal(index)).stripTrailingZeros().toPlainString())
                is CellInput.DateValue -> CellInput.DateValue(LocalDate.parse(start.isoDate).plusDays(step.toLong() * index).toString())
                else -> start
            }
            CellMutation(target.row, target.column, input)
        }
        val engine = CalculationEngine(workbook)
        engine.commitBatch(sheetId, mutations, confirmWarnings = true)
        return engine.workbook()
    }

    fun resizeColumn(workbook: CalculationWorkbook, sheetId: SheetId, column: Int, widthPoints: Float): CalculationWorkbook {
        require(widthPoints.isFinite() && widthPoints in 16f..2_000f)
        val sheet = workbook.sheet(sheetId) ?: return workbook
        val columns = sheet.columns.toMutableList()
        columns[column] = columns[column].copy(widthPoints = widthPoints)
        return workbook.withSheet(sheet.copy(columns = columns))
    }

    fun resizeRow(workbook: CalculationWorkbook, sheetId: SheetId, row: Int, heightPoints: Float?): CalculationWorkbook {
        require(heightPoints == null || (heightPoints.isFinite() && heightPoints in 12f..2_000f))
        val sheet = workbook.sheet(sheetId) ?: return workbook
        val rows = sheet.rows.toMutableList()
        rows[row] = rows[row].copy(heightPoints = heightPoints)
        return workbook.withSheet(sheet.copy(rows = rows))
    }

    fun fitRowHeight(workbook: CalculationWorkbook, sheetId: SheetId, row: Int, columnWidthFallback: Float = 96f): CalculationWorkbook {
        val sheet = workbook.sheet(sheetId) ?: return workbook
        if (row !in sheet.rows.indices) return workbook
        val longestWrappedLines = sheet.cells.asSequence()
            .filter { it.rowId == sheet.rows[row].id }
            .map { cell ->
                val column = sheet.columns.firstOrNull { it.id == cell.columnId }
                val width = column?.widthPoints ?: columnWidthFallback
                val charactersPerLine = (width / 7f).toInt().coerceAtLeast(1)
                cell.input.editorText().lineSequence().sumOf { line -> (line.length.coerceAtLeast(1) + charactersPerLine - 1) / charactersPerLine }
            }
            .maxOrNull()
            ?.coerceAtLeast(1)
            ?: 1
        return resizeRow(workbook, sheetId, row, (longestWrappedLines * 20f + 12f).coerceIn(48f, 2_000f))
    }

    fun applyFormat(workbook: CalculationWorkbook, sheetId: SheetId, addresses: List<GridAddress>, delta: CellFormat): CalculationWorkbook {
        val sheet = workbook.sheet(sheetId) ?: return workbook
        var next = sheet
        addresses.forEach { address ->
            val coordinate = next.coordinateAt(address.row, address.column) ?: return@forEach
            val existing = next.cell(coordinate.rowId, coordinate.columnId)
            val cell = existing?.copy(format = existing.format.merge(delta))
                ?: CalculationCell(rowId = coordinate.rowId, columnId = coordinate.columnId, format = delta)
            next = next.upsertCell(cell)
        }
        return workbook.withSheet(next)
    }

    fun setNumberFormat(workbook: CalculationWorkbook, sheetId: SheetId, addresses: List<GridAddress>, numberFormat: CellNumberFormat): CalculationWorkbook {
        val sheet = workbook.sheet(sheetId) ?: return workbook
        var next = sheet
        addresses.forEach { address ->
            val coordinate = next.coordinateAt(address.row, address.column) ?: return@forEach
            val existing = next.cell(coordinate.rowId, coordinate.columnId)
            val format = (existing?.format ?: CellFormat()).copy(numberFormat = numberFormat)
            next = next.upsertCell(existing?.copy(format = format) ?: CalculationCell(rowId = coordinate.rowId, columnId = coordinate.columnId, format = format))
        }
        return workbook.withSheet(next)
    }

    fun applyTableStyle(workbook: CalculationWorkbook, tableId: EmbeddedTableId, style: TableStyleDefinition): CalculationWorkbook {
        if (workbook.embeddedTables.none { it.id == tableId }) return workbook
        val styles = workbook.tableStyles.filterNot { it.id == style.id } + style
        return workbook.copy(
            tableStyles = styles,
            embeddedTables = workbook.embeddedTables.map { if (it.id == tableId) it.copy(styleId = style.id) else it },
            revision = workbook.revision + 1,
        )
    }

    fun freeze(workbook: CalculationWorkbook, sheetId: SheetId, rows: Int, columns: Int): CalculationWorkbook {
        val sheet = workbook.sheet(sheetId) ?: return workbook
        return workbook.withSheet(sheet.copy(frozenRows = rows.coerceIn(0, sheet.rowCount), frozenColumns = columns.coerceIn(0, sheet.columnCount)))
    }

    fun sort(
        workbook: CalculationWorkbook,
        sheetId: SheetId,
        range: StableCellRange,
        keyColumnId: ColumnId,
        direction: SortDirection,
        headerAware: Boolean = true,
    ): CalculationWorkbook {
        val sheet = workbook.sheet(sheetId) ?: return workbook
        val startRow = minOf(sheet.rowIndex(range.start.rowId), sheet.rowIndex(range.end.rowId))
        val endRow = maxOf(sheet.rowIndex(range.start.rowId), sheet.rowIndex(range.end.rowId))
        val startColumn = minOf(sheet.columnIndex(range.start.columnId), sheet.columnIndex(range.end.columnId))
        val endColumn = maxOf(sheet.columnIndex(range.start.columnId), sheet.columnIndex(range.end.columnId))
        val keyColumn = sheet.columnIndex(keyColumnId)
        if (minOf(startRow, startColumn, keyColumn) < 0 || keyColumn !in startColumn..endColumn) return workbook
        val dataStart = startRow + if (headerAware) 1 else 0
        if (dataStart > endRow) return workbook
        val engine = CalculationEngine(workbook)
        val sourceRows = (dataStart..endRow).toList()
        val sortedRows = sourceRows.sortedWith { a, b ->
            val comparison = compareForSort(engine.valueAt(sheet.id, a, keyColumn), engine.valueAt(sheet.id, b, keyColumn))
            if (direction == SortDirection.ASCENDING) comparison else -comparison
        }
        val outside = sheet.cells.filterNot { cell ->
            val row = sheet.rowIndex(cell.rowId)
            val column = sheet.columnIndex(cell.columnId)
            row in dataStart..endRow && column in startColumn..endColumn
        }
        val moved = buildList {
            sortedRows.forEachIndexed { destinationOffset, sourceRow ->
                val destinationRow = dataStart + destinationOffset
                val destinationRowId = sheet.rows[destinationRow].id
                sheet.cells.filter { cell -> sheet.rowIndex(cell.rowId) == sourceRow && sheet.columnIndex(cell.columnId) in startColumn..endColumn }
                    .forEach { cell ->
                        val input = (cell.input as? CellInput.Formula)?.let { formula ->
                            val parsed = FormulaParser().parse(formula.source) as? FormulaParseResult.Success
                            parsed?.let { CellInput.Formula(FormulaRenderer.render(FormulaReferenceTransform.offsetForFill(it.expression, destinationRow - sourceRow, 0))) }
                                ?: formula
                        } ?: cell.input
                        add(cell.copy(rowId = destinationRowId, input = input))
                    }
            }
        }
        val definition = SortDefinition(target = range, keys = listOf(SortKey(keyColumnId, direction)), headerAware = headerAware)
        return workbook.withSheet(sheet.copy(cells = outside + moved, sorts = sheet.sorts + definition))
    }

    fun filter(
        workbook: CalculationWorkbook,
        sheetId: SheetId,
        range: StableCellRange,
        columnId: ColumnId,
        condition: FilterCondition,
        headerAware: Boolean = true,
    ): CalculationWorkbook {
        val sheet = workbook.sheet(sheetId) ?: return workbook
        val startRow = minOf(sheet.rowIndex(range.start.rowId), sheet.rowIndex(range.end.rowId))
        val endRow = maxOf(sheet.rowIndex(range.start.rowId), sheet.rowIndex(range.end.rowId))
        val column = sheet.columnIndex(columnId)
        if (minOf(startRow, column) < 0) return workbook
        val engine = CalculationEngine(workbook)
        val rows = sheet.rows.mapIndexed { index, row ->
            when {
                index !in startRow..endRow -> row
                headerAware && index == startRow -> row.copy(hidden = false)
                else -> row.copy(hidden = !filterMatches(engine.valueAt(sheet.id, index, column), condition))
            }
        }
        val definition = FilterDefinition(target = range, columnId = columnId, condition = condition)
        return workbook.withSheet(sheet.copy(rows = rows, filters = sheet.filters + definition))
    }

    fun clearFilters(workbook: CalculationWorkbook, sheetId: SheetId): CalculationWorkbook {
        val sheet = workbook.sheet(sheetId) ?: return workbook
        return workbook.withSheet(sheet.copy(
            rows = sheet.rows.map { it.copy(hidden = false) },
            filters = emptyList(),
        ))
    }

    private fun rewriteFormulas(workbook: CalculationWorkbook, transform: (FormulaExpression, CalculationSheet) -> FormulaExpression): CalculationWorkbook {
        val parser = FormulaParser()
        var changed = false
        val sheets = workbook.sheets.map { sheet ->
            val cells = sheet.cells.map { cell ->
                val input = cell.input as? CellInput.Formula ?: return@map cell
                val parsed = parser.parse(input.source) as? FormulaParseResult.Success ?: return@map cell
                val source = FormulaRenderer.render(transform(parsed.expression, sheet))
                if (source == input.source) cell else cell.copy(input = CellInput.Formula(source)).also { changed = true }
            }
            if (cells == sheet.cells) sheet else sheet.copy(cells = cells)
        }
        return if (changed) workbook.copy(sheets = sheets, revision = workbook.revision + 1) else workbook.copy(sheets = sheets)
    }

}

private fun compareForSort(a: CalculatedValue, b: CalculatedValue): Int {
    if (a is CalculatedValue.ErrorValue || b is CalculatedValue.ErrorValue) return when {
        a is CalculatedValue.ErrorValue && b is CalculatedValue.ErrorValue -> 0
        a is CalculatedValue.ErrorValue -> 1
        else -> -1
    }
    if (a == CalculatedValue.Blank || b == CalculatedValue.Blank) return when {
        a == CalculatedValue.Blank && b == CalculatedValue.Blank -> 0
        a == CalculatedValue.Blank -> 1
        else -> -1
    }
    val an = a.displayText().toBigDecimalOrNull()
    val bn = b.displayText().toBigDecimalOrNull()
    return if (an != null && bn != null) an.compareTo(bn) else a.displayText().compareTo(b.displayText(), true)
}

private fun filterMatches(value: CalculatedValue, condition: FilterCondition): Boolean = when (condition) {
    is FilterCondition.Values -> condition.allowed.any { it.equals(value.displayText(), true) }
    is FilterCondition.Text -> when (condition.operator) {
        TextFilterOperator.CONTAINS -> value.displayText().contains(condition.value, true)
        TextFilterOperator.NOT_CONTAINS -> !value.displayText().contains(condition.value, true)
        TextFilterOperator.STARTS_WITH -> value.displayText().startsWith(condition.value, true)
        TextFilterOperator.ENDS_WITH -> value.displayText().endsWith(condition.value, true)
        TextFilterOperator.EQUALS -> value.displayText().equals(condition.value, true)
    }
    is FilterCondition.Number -> {
        val left = value.displayText().toBigDecimalOrNull()
        val right = condition.value.toBigDecimalOrNull()
        if (left == null || right == null) false else when (condition.operator) {
                ComparisonOperator.EQUAL -> left == right
                ComparisonOperator.NOT_EQUAL -> left != right
                ComparisonOperator.LESS -> left < right
                ComparisonOperator.LESS_OR_EQUAL -> left <= right
                ComparisonOperator.GREATER -> left > right
                ComparisonOperator.GREATER_OR_EQUAL -> left >= right
            }
    }
    is FilterCondition.Date -> {
        val left = runCatching { LocalDate.parse(value.displayText()) }.getOrNull()
        val right = runCatching { LocalDate.parse(condition.isoDate) }.getOrNull()
        if (left == null || right == null) false else when (condition.operator) {
                ComparisonOperator.EQUAL -> left == right
                ComparisonOperator.NOT_EQUAL -> left != right
                ComparisonOperator.LESS -> left < right
                ComparisonOperator.LESS_OR_EQUAL -> left <= right
                ComparisonOperator.GREATER -> left > right
                ComparisonOperator.GREATER_OR_EQUAL -> left >= right
            }
    }
}

object CellInputParser {
    fun parse(raw: String): CellInput = when {
        raw.isEmpty() -> CellInput.Blank
        raw.startsWith('=') -> CellInput.Formula(raw)
        raw.equals("TRUE", true) -> CellInput.BooleanValue(true)
        raw.equals("FALSE", true) -> CellInput.BooleanValue(false)
        // `parseRenderableDecimal`, not `toBigDecimalOrNull`: the latter happily accepts `1e2147483000`, whose
        // `toPlainString()` on the very next call allocates a two-billion-character string and throws
        // OutOfMemoryError. Anything past the bound falls through and is kept as text, which is lossless.
        parseRenderableDecimal(raw) != null -> CellInput.Number(parseRenderableDecimal(raw)!!.stripTrailingZeros().toPlainString())
        runCatching { java.time.LocalDateTime.parse(raw) }.isSuccess -> CellInput.DateTimeValue(raw)
        runCatching { LocalDate.parse(raw) }.isSuccess -> CellInput.DateValue(raw)
        else -> CellInput.Text(raw)
    }
}

private fun uniqueSheetName(workbook: CalculationWorkbook, requested: String): String {
    val base = requested.trim().ifEmpty { "Sheet" }
    if (workbook.sheets.none { it.name.equals(base, true) }) return base
    var suffix = 2
    while (workbook.sheets.any { it.name.equals("$base $suffix", true) }) suffix++
    return "$base $suffix"
}

private fun StableCellRange.repairAfterRemovedRows(old: CalculationSheet, newRows: List<CalculationRow>): StableCellRange? {
    val start = start.repairRow(old, newRows) ?: return null
    val end = end.repairRow(old, newRows) ?: return null
    return copy(start = start, end = end)
}

private fun StableCellRange.repairAfterRemovedColumns(old: CalculationSheet, newColumns: List<CalculationColumn>): StableCellRange? {
    val start = start.repairColumn(old, newColumns) ?: return null
    val end = end.repairColumn(old, newColumns) ?: return null
    return copy(start = start, end = end)
}

private fun StableCellCoordinate.repairRow(old: CalculationSheet, newRows: List<CalculationRow>): StableCellCoordinate? {
    if (newRows.isEmpty()) return null
    if (newRows.any { it.id == rowId }) return this
    val oldIndex = old.rowIndex(rowId).coerceAtLeast(0)
    return copy(rowId = newRows[oldIndex.coerceAtMost(newRows.lastIndex)].id)
}

private fun StableCellCoordinate.repairColumn(old: CalculationSheet, newColumns: List<CalculationColumn>): StableCellCoordinate? {
    if (newColumns.isEmpty()) return null
    if (newColumns.any { it.id == columnId }) return this
    val oldIndex = old.columnIndex(columnId).coerceAtLeast(0)
    return copy(columnId = newColumns[oldIndex.coerceAtMost(newColumns.lastIndex)].id)
}

private fun MergedRegion.repairAfterRemovedRows(old: CalculationSheet, newRows: List<CalculationRow>): MergedRegion? =
    range.repairAfterRemovedRows(old, newRows)?.let { copy(range = it) }

private fun MergedRegion.repairAfterRemovedColumns(old: CalculationSheet, newColumns: List<CalculationColumn>): MergedRegion? =
    range.repairAfterRemovedColumns(old, newColumns)?.let { copy(range = it) }

private fun StableCellRange.overlaps(other: StableCellRange, sheet: CalculationSheet): Boolean {
    if (start.sheetId != other.start.sheetId) return false
    fun bounds(range: StableCellRange): List<Int> = listOf(
        sheet.rowIndex(range.start.rowId), sheet.rowIndex(range.end.rowId),
        sheet.columnIndex(range.start.columnId), sheet.columnIndex(range.end.columnId),
    )
    val a = bounds(this)
    val b = bounds(other)
    return maxOf(minOf(a[0], a[1]), minOf(b[0], b[1])) <= minOf(maxOf(a[0], a[1]), maxOf(b[0], b[1])) &&
        maxOf(minOf(a[2], a[3]), minOf(b[2], b[3])) <= minOf(maxOf(a[2], a[3]), maxOf(b[2], b[3]))
}

internal fun CellFormat.merge(delta: CellFormat): CellFormat = copy(
    font = delta.font ?: font,
    fontSizePoints = delta.fontSizePoints ?: fontSizePoints,
    bold = delta.bold ?: bold,
    italic = delta.italic ?: italic,
    textColor = delta.textColor ?: textColor,
    fillColor = delta.fillColor ?: fillColor,
    borderTop = delta.borderTop ?: borderTop,
    borderRight = delta.borderRight ?: borderRight,
    borderBottom = delta.borderBottom ?: borderBottom,
    borderLeft = delta.borderLeft ?: borderLeft,
    horizontalAlignment = delta.horizontalAlignment ?: horizontalAlignment,
    verticalAlignment = delta.verticalAlignment ?: verticalAlignment,
    wrap = delta.wrap ?: wrap,
    paddingPoints = delta.paddingPoints ?: paddingPoints,
    numberFormat = if (delta.numberFormat == CellNumberFormat()) numberFormat else delta.numberFormat,
)
