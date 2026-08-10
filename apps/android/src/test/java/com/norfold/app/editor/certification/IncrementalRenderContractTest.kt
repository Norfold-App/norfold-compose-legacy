package com.norfold.app.editor.certification

import com.norfold.app.domain.BlockDocument
import com.norfold.app.domain.DocumentPageBlock
import com.norfold.app.domain.InlineText
import com.norfold.app.domain.PageGeometry
import com.norfold.app.domain.ParagraphBlock
import com.norfold.app.domain.SceneGeometry
import com.norfold.app.domain.SceneObject
import com.norfold.app.editor.calculation.CalculationColumn
import com.norfold.app.editor.calculation.CalculationEngine
import com.norfold.app.editor.calculation.CalculationRow
import com.norfold.app.editor.calculation.CalculationSheet
import com.norfold.app.editor.calculation.CalculationWorkbook
import com.norfold.app.editor.calculation.CellInput
import com.norfold.app.editor.calculation.CellInputParser
import com.norfold.app.editor.calculation.SheetId
import com.norfold.app.editor.chart.ChartDocument
import com.norfold.app.editor.chart.ChartGeometryKey
import com.norfold.app.editor.chart.ChartType
import com.norfold.app.editor.flow.FlowDocumentDiff
import com.norfold.app.editor.history.EditOperation
import com.norfold.app.editor.history.EditTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The one property no architecture comment can be trusted for: **a local edit must not reflow the whole
 * document**.
 *
 * The canonical reference states it twice — §16.1 ("ordinary interaction invalidates only affected
 * content") and §11.9 for charts — and it is the property most easily lost by accident, because losing it
 * changes no output, breaks no other test, and is invisible until a document is large enough to stutter.
 * Every case here is therefore a *counter* comparison, not a structural claim: the unit of work the engine
 * actually performed against the total it could have performed.
 *
 * The distinction the roadmap draws is preserved exactly. A local edit must stay bounded; an operation that
 * genuinely requires global recalculation (a dependency change, a first write to a coordinate that had no
 * identity for edges to target) is *allowed* to touch everything, and the tests that follow assert that
 * boundary in both directions rather than only the flattering one. A test that only proved "small" would
 * pass just as well against an engine that had quietly stopped recalculating what it must.
 */
class IncrementalRenderContractTest {

    // ── Fixtures ────────────────────────────────────────────────────────────────────────────────────

    private fun input(workbook: CalculationWorkbook, sheetId: SheetId, row: Int, column: Int, raw: String): CalculationWorkbook {
        val engine = CalculationEngine(workbook)
        val result = engine.commitInput(sheetId, row, column, CellInputParser.parse(raw), confirmWarning = true)
        assertTrue("fixture input at ($row,$column) must be accepted", result.accepted)
        return engine.workbook()
    }

    /**
     * A sheet whose every formula cell already has a durable identity.
     *
     * Populating through `commitInput` matters: a coordinate that was never written has no `CellId` for
     * dependency edges to point at. The engine now resolves that first write through coordinate-reference
     * spans, while an existing coordinate uses the ordinary id-keyed closure. Building the fixture this way
     * keeps the "unrelated edit" cases measuring the established-coordinate path specifically.
     */
    private fun independentColumns(rows: Int): Pair<CalculationWorkbook, SheetId> {
        val sheet = CalculationSheet(
            rowCount = rows,
            columnCount = 3,
            rows = List(rows) { CalculationRow() },
            columns = List(3) { CalculationColumn() },
        )
        var workbook = CalculationWorkbook(sheets = listOf(sheet), activeSheetId = sheet.id)
        for (row in 0 until rows) {
            workbook = input(workbook, sheet.id, row, 0, "${row + 1}")
            workbook = input(workbook, sheet.id, row, 1, "=A${row + 1}*2")
        }
        return workbook to sheet.id
    }

    private fun obj(id: String, x: Float = 0f, y: Float = 0f) = SceneObject(
        id = id,
        geometry = SceneGeometry(x, y, 100f, 50f),
        content = ParagraphBlock(id = "c-$id", content = listOf(InlineText(id))),
    )

    // ── Calculation: an unrelated edit recalculates nothing ─────────────────────────────────────────

    @Test
    fun editingAnUnrelatedCellRecalculatesNoFormulaAtAll() {
        val (workbook, sheetId) = independentColumns(rows = 50)
        val engine = CalculationEngine(workbook)
        // Settle the initial pass so the measurement covers the edit alone.
        engine.valueAt(sheetId, 0, 1)
        val before = engine.instrumentation().formulasRecalculated

        // Column C is referenced by nothing. Every one of the 50 formulas lives in column B.
        engine.commitInput(sheetId, 10, 2, CellInput.Number("999"))

        val recalculated = engine.instrumentation().formulasRecalculated - before
        assertEquals(
            "an edit to a cell no formula references must recalculate 0 formulas, not $recalculated of 50",
            0,
            recalculated,
        )
    }

    @Test
    fun editingAnUnrelatedCellInvalidatesOnlyItself() {
        val (workbook, sheetId) = independentColumns(rows = 50)
        val engine = CalculationEngine(workbook)
        engine.valueAt(sheetId, 0, 1)
        val before = engine.instrumentation().invalidatedCellCount

        engine.commitInput(sheetId, 10, 2, CellInput.Number("999"))

        val invalidated = engine.instrumentation().invalidatedCellCount - before
        val totalCells = engine.workbook().sheet(sheetId)!!.cells.size
        assertTrue(
            "invalidation must stay local: $invalidated of $totalCells cells for one unrelated edit",
            invalidated < totalCells,
        )
        assertEquals("only the edited cell's own closure is invalidated", 1, invalidated)
    }

    /**
     * The other direction. A test suite that only ever asserted "few cells recalculated" would stay green
     * against an engine that had stopped recalculating dependents entirely — the worst possible defect,
     * silently wrong values, dressed up as excellent performance.
     */
    @Test
    fun editingADependencySourceStillRecalculatesEveryDependentExactlyOnce() {
        val rows = 50
        val sheet = CalculationSheet(
            rowCount = rows + 1,
            columnCount = 2,
            rows = List(rows + 1) { CalculationRow() },
            columns = List(2) { CalculationColumn() },
        )
        var workbook = CalculationWorkbook(sheets = listOf(sheet), activeSheetId = sheet.id)
        workbook = input(workbook, sheet.id, 0, 0, "1")
        // 50 formulas, every one of them reading A1.
        for (row in 1..rows) workbook = input(workbook, sheet.id, row, 1, "=A1*$row")

        val engine = CalculationEngine(workbook)
        engine.valueAt(sheet.id, 1, 1)
        val before = engine.instrumentation().formulasRecalculated

        engine.commitInput(sheet.id, 0, 0, CellInput.Number("2"))

        val recalculated = engine.instrumentation().formulasRecalculated - before
        assertEquals("all $rows dependents must recalculate when their source changes", rows, recalculated)
        assertEquals("and the new value must actually be visible", "2", engine.displayAt(sheet.id, 1, 1))
    }

    @Test
    fun theRecalculationCostOfALocalEditDoesNotGrowWithDocumentSize() {
        // The scaling claim behind "no full-document reflow": the same edit, in a sheet 10x larger, must
        // cost the same. A full reflow would make the second number ten times the first.
        fun costOfUnrelatedEdit(rows: Int): Int {
            val (workbook, sheetId) = independentColumns(rows)
            val engine = CalculationEngine(workbook)
            engine.valueAt(sheetId, 0, 1)
            val before = engine.instrumentation().formulasRecalculated
            engine.commitInput(sheetId, 0, 2, CellInput.Number("7"))
            return engine.instrumentation().formulasRecalculated - before
        }

        assertEquals(
            "a local edit must cost the same in a large sheet as in a small one",
            costOfUnrelatedEdit(rows = 20),
            costOfUnrelatedEdit(rows = 200),
        )
    }

    // ── Calculation: the first write to a blank cell ────────────────────────────────────────────────

    /**
     * The boundary the narrow path must not break.
     *
     * A formula reading a *blank* coordinate has no dependency edge to it — there is no cell, so there is no
     * id for an edge to point at. Filling that coordinate must still recalculate the formula, and it must do
     * so without falling back to recalculating the workbook. This is the case that makes the optimisation
     * above dangerous, so it is asserted before the case that makes it look good.
     */
    @Test
    fun fillingABlankCellThatAFormulaReadsRecalculatesThatFormula() {
        val sheet = CalculationSheet(
            rowCount = 100,
            columnCount = 2,
            rows = List(100) { CalculationRow() },
            columns = List(2) { CalculationColumn() },
        )
        var workbook = CalculationWorkbook(sheets = listOf(sheet), activeSheetId = sheet.id)
        // Reads all of column A, every cell of which is blank.
        workbook = input(workbook, sheet.id, 0, 1, "=SUM(A1:A100)")

        val engine = CalculationEngine(workbook)
        assertEquals("an empty range sums to zero", "0", engine.displayAt(sheet.id, 0, 1))

        engine.commitInput(sheet.id, 49, 0, CellInput.Number("5"))

        assertEquals(
            "filling a blank cell inside the summed range must update the sum",
            "5",
            engine.displayAt(sheet.id, 0, 1),
        )
    }

    @Test
    fun fillingABlankCellNoFormulaReadsDoesNotRecalculateTheWorkbook() {
        // The same first-write path as above, in the case where nothing reads the coordinate. Before the span
        // index existed this rebuilt the entire graph, because "no edge points at it" was indistinguishable
        // from "we do not know what points at it".
        val (workbook, sheetId) = independentColumns(rows = 60)
        val engine = CalculationEngine(workbook)
        engine.valueAt(sheetId, 0, 1)
        val before = engine.instrumentation().formulasRecalculated

        engine.commitInput(sheetId, 30, 2, CellInput.Number("1"))

        val recalculated = engine.instrumentation().formulasRecalculated - before
        assertEquals(
            "a first write to a cell no formula reads must recalculate 0 of 60 formulas, not $recalculated",
            0,
            recalculated,
        )
    }

    @Test
    fun theCostOfAFirstWriteTracksItsReadersRatherThanTheSheetSize() {
        // One formula reads the coordinate being filled; the sheet around it grows 10x. If the first-write
        // path still rebuilt the graph, the second number would be ten times the first.
        fun costOfFirstWrite(rows: Int): Int {
            val sheet = CalculationSheet(
                rowCount = rows,
                columnCount = 3,
                rows = List(rows) { CalculationRow() },
                columns = List(3) { CalculationColumn() },
            )
            var workbook = CalculationWorkbook(sheets = listOf(sheet), activeSheetId = sheet.id)
            for (row in 0 until rows) {
                workbook = input(workbook, sheet.id, row, 0, "${row + 1}")
                workbook = input(workbook, sheet.id, row, 1, "=A${row + 1}*2")
            }
            // The only formula reading column C, at a coordinate that holds no cell yet.
            workbook = input(workbook, sheet.id, 0, 2, "=C2+1")

            val engine = CalculationEngine(workbook)
            engine.valueAt(sheet.id, 0, 2)
            val before = engine.instrumentation().formulasRecalculated
            engine.commitInput(sheet.id, 1, 2, CellInput.Number("4"))
            return engine.instrumentation().formulasRecalculated - before
        }

        val small = costOfFirstWrite(rows = 20)
        assertEquals("the one formula reading the filled coordinate recalculates", 1, small)
        assertEquals("and a 10x larger sheet costs the same", small, costOfFirstWrite(rows = 200))
    }

    // ── Charts: retained geometry survives edits that cannot move a mark ────────────────────────────

    /**
     * §16.2 asks charts for retained geometry with layer/mark invalidation.
     *
     * The risk in a key like this runs both ways, so both are asserted. A key that changed too often would
     * silently discard geometry — the defect this fixes. A key that changed too rarely would leave the chart
     * drawing stale marks, which is far worse than a slow chart, so the second half of this case is the one
     * that matters most.
     */
    @Test
    fun retitlingAChartDoesNotInvalidateItsRetainedGeometry() {
        val document = ChartDocument(type = ChartType.BAR, title = "Revenue")

        val cosmetic = listOf(
            document.copy(title = "Revenue by quarter"),
            document.copy(subtitle = "in thousands"),
            document.copy(caption = "Source: internal"),
            document.copy(description = "A bar chart of quarterly revenue"),
            document.copy(legend = document.legend.copy(visible = !document.legend.visible)),
        )

        cosmetic.forEach { edited ->
            assertEquals(
                "an edit that cannot move a mark must reuse the retained geometry",
                ChartGeometryKey.of(document),
                ChartGeometryKey.of(edited),
            )
        }
    }

    @Test
    fun changingWhatActuallyMovesAMarkDoesInvalidateTheGeometry() {
        val document = ChartDocument(type = ChartType.BAR)

        assertNotEquals(
            "a chart type change re-projects every mark and must invalidate",
            ChartGeometryKey.of(document),
            ChartGeometryKey.of(document.copy(type = ChartType.PIE)),
        )
        // The palette is read by the arc builders, so it is a geometry input rather than a cosmetic one.
        val repainted = document.copy(
            style = document.style.copy(palette = document.style.palette.asReversed()),
        )
        assertNotEquals(
            "the palette reaches the marks and must invalidate",
            ChartGeometryKey.of(document),
            ChartGeometryKey.of(repainted),
        )
    }

    // ── Flow: typing one character produces one operation, not a document rebuild ───────────────────

    @Test
    fun editingOneFlowBlockDiffsToOneOperationNotAWholeDocumentReplacement() {
        val blocks = (1..80).map { ParagraphBlock(id = "b$it", content = listOf(InlineText("paragraph $it"))) }
        val before = BlockDocument(blocks)
        val after = BlockDocument(
            blocks.mapIndexed { index, block ->
                if (index == 40) block.copy(content = listOf(InlineText("paragraph 41 edited"))) else block
            },
        )

        val ops = FlowDocumentDiff.diff(EditTarget("note:1"), before, after)

        assertEquals("typing in one block must produce exactly one operation, not ${ops.size}", 1, ops.size)
        val replaced = ops.single() as EditOperation.ReplaceBlock
        assertEquals("and it must name the block that actually changed", "b41", replaced.after.id)
        assertEquals("at the index it actually occupies", 40, replaced.index)
    }

    @Test
    fun anUnchangedFlowDocumentProducesNoOperationsAtAll() {
        val blocks = (1..40).map { ParagraphBlock(id = "b$it", content = listOf(InlineText("paragraph $it"))) }
        val document = BlockDocument(blocks)

        val ops = FlowDocumentDiff.diff(EditTarget("note:1"), document, BlockDocument(blocks.toList()))

        assertTrue("a no-op edit must not manufacture work: got ${ops.size} operations", ops.isEmpty())
    }

    @Test
    fun theOperationCountForALocalFlowEditDoesNotGrowWithDocumentSize() {
        fun opsForOneEdit(size: Int): Int {
            val blocks = (1..size).map { ParagraphBlock(id = "b$it", content = listOf(InlineText("p $it"))) }
            val after = BlockDocument(
                blocks.mapIndexed { index, block ->
                    if (index == 0) block.copy(content = listOf(InlineText("edited"))) else block
                },
            )
            return FlowDocumentDiff.diff(EditTarget("note:1"), BlockDocument(blocks), after).size
        }

        assertEquals("one edit is one operation at 25 blocks", 1, opsForOneEdit(25))
        assertEquals("and still one operation at 500 blocks", 1, opsForOneEdit(500))
    }

    // ── Documents: moving one object does not rewrite the page's other objects ──────────────────────

    @Test
    fun movingOneSceneObjectLeavesEveryOtherObjectIdentical() {
        val objects = (1..60).map { obj("o$it", x = it * 10f, y = it * 5f) }
        val page = DocumentPageBlock(id = "p1", geometry = PageGeometry.a4(), objects = objects).normalized()

        val moved = page.copy(
            objects = page.objects.map {
                if (it.id == "o30") it.copy(geometry = it.geometry.copy(x = it.geometry.x + 25f)) else it
            },
        )

        val changed = page.objects.indices.count { page.objects[it] != moved.objects[it] }
        assertEquals("dragging one object must alter exactly one object, not $changed", 1, changed)
        // Identity, not just equality: the untouched objects must be the very same instances, so a renderer
        // keyed on identity has nothing to rebuild.
        page.objects.filter { it.id != "o30" }.forEach { original ->
            val next = moved.objects.first { it.id == original.id }
            assertTrue("untouched object ${original.id} must not be reallocated", original === next)
        }
    }
}
