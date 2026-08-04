package com.norfold.app.editor.calculation

/** Resolves durable table-style inheritance without rewriting consumer cells. */
object TableStyleResolver {
    data class Resolved(
        val header: CellFormat,
        val body: CellFormat,
        val total: CellFormat,
        val firstColumn: CellFormat,
        val lastColumn: CellFormat,
        val bandedRowFormat: CellFormat,
        val bandedColumnFormat: CellFormat,
        val bandedRows: Boolean,
        val bandedColumns: Boolean,
    )

    fun resolve(workbook: CalculationWorkbook, styleId: TableStyleId?): Resolved {
        val chain = mutableListOf<TableStyleDefinition>()
        val visited = mutableSetOf<TableStyleId>()
        var current = styleId?.let { id -> workbook.tableStyles.firstOrNull { it.id == id } }
        while (current != null && visited.add(current.id)) {
            chain += current
            current = current.parentId?.let { parent -> workbook.tableStyles.firstOrNull { it.id == parent } }
        }
        var resolved = Resolved(CellFormat(), CellFormat(), CellFormat(), CellFormat(), CellFormat(), CellFormat(), CellFormat(), false, false)
        chain.asReversed().forEach { style ->
            resolved = resolved.copy(
                header = resolved.header.merge(style.header),
                body = resolved.body.merge(style.body),
                total = resolved.total.merge(style.total),
                firstColumn = resolved.firstColumn.merge(style.firstColumn),
                lastColumn = resolved.lastColumn.merge(style.lastColumn),
                bandedRowFormat = resolved.bandedRowFormat.merge(style.bandedRowFormat),
                bandedColumnFormat = resolved.bandedColumnFormat.merge(style.bandedColumnFormat),
                bandedRows = style.bandedRows,
                bandedColumns = style.bandedColumns,
            )
        }
        return resolved
    }

    fun effectiveCellFormat(
        workbook: CalculationWorkbook,
        table: EmbeddedTableDefinition,
        rowOffset: Int,
        columnOffset: Int,
        local: CellFormat,
    ): CellFormat {
        val resolved = resolve(workbook, table.styleId)
        var base = when {
            table.headerRow && rowOffset == 0 -> resolved.header
            table.totalRow && rowOffset == table.range.rowSpan(workbook) - 1 -> resolved.total
            columnOffset == 0 -> resolved.body.merge(resolved.firstColumn)
            columnOffset == table.columns.lastIndex -> resolved.body.merge(resolved.lastColumn)
            else -> resolved.body
        }
        if (resolved.bandedRows && rowOffset % 2 == 1) base = base.merge(resolved.bandedRowFormat)
        if (resolved.bandedColumns && columnOffset % 2 == 1) base = base.merge(resolved.bandedColumnFormat)
        return base.merge(local)
    }
}

fun CalculationWorkbook.mixedFormat(sheetId: SheetId, addresses: List<GridAddress>): MixedValue<CellFormat> {
    val sheet = sheet(sheetId) ?: return MixedValue(null, false)
    return MixedValue.of(addresses.map { sheet.cellAt(it.row, it.column)?.format ?: CellFormat() })
}

private fun StableCellRange.rowSpan(workbook: CalculationWorkbook): Int {
    val sheet = workbook.sheet(start.sheetId) ?: return 0
    return kotlin.math.abs(sheet.rowIndex(end.rowId) - sheet.rowIndex(start.rowId)) + 1
}
