package com.norfold.app.editor.certification

import com.norfold.app.editor.calculation.CalculationCell
import com.norfold.app.editor.calculation.CalculationColumn
import com.norfold.app.editor.calculation.CalculationEngine
import com.norfold.app.editor.calculation.CalculationMutations
import com.norfold.app.editor.calculation.CalculationRow
import com.norfold.app.editor.calculation.CalculationSheet
import com.norfold.app.editor.calculation.CalculationWorkbook
import com.norfold.app.editor.calculation.CellFormat
import com.norfold.app.editor.calculation.CellInput
import com.norfold.app.editor.calculation.CellInputParser
import com.norfold.app.editor.calculation.CellMutation
import com.norfold.app.editor.calculation.CellNumberFormat
import com.norfold.app.editor.calculation.GridAddress
import com.norfold.app.editor.calculation.HorizontalCellAlignment
import com.norfold.app.editor.calculation.NumberFormatKind
import com.norfold.app.editor.calculation.StableCellCoordinate
import com.norfold.app.editor.calculation.StableCellRange
import com.norfold.app.editor.calculation.ValidationCondition
import com.norfold.app.editor.calculation.ValidationPolicy
import com.norfold.app.editor.calculation.ValidationRule
import com.norfold.app.editor.calculation.editorText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The bulk write paths against the per-cell folds they replaced.
 *
 * A paste, a fill and a selection-wide format used to apply themselves one cell at a time, each write scanning
 * and copying the whole cell list. That is O(writes × cells), and at the scale §10.5 names it made those
 * operations take minutes; they now stage their writes and apply them in one pass. The measured improvement is
 * in `CalculationPerformanceBenchmark`, and it is worthless if the faster path answers differently, so this
 * suite pins the equivalence instead of the speed.
 *
 * Most cases here compare a batch against an actual fold of the single-cell commit, computed in the test rather
 * than transcribed into it, so the two cannot drift apart and no assertion depends on my reading of what the
 * engine ought to say. The comparison covers the accept/reject decisions and the resulting cell list in order,
 * which is what a caller and durable storage respectively observe. The inputs are the ones where a one-pass
 * rewrite plausibly diverges: an address repeated inside a single call, a write that empties a cell, a
 * coordinate visited both before and after it is emptied, and the validation rule that reads the sheet it is
 * validating against.
 */
class BulkEditEquivalenceTest {

    private fun sheet(rows: Int = 8, columns: Int = 4) = CalculationSheet(
        rowCount = rows,
        columnCount = columns,
        rows = List(rows) { CalculationRow() },
        columns = List(columns) { CalculationColumn() },
    )

    private fun engineOver(sheet: CalculationSheet) =
        CalculationEngine(CalculationWorkbook(sheets = listOf(sheet), activeSheetId = sheet.id))

    private fun CalculationSheet.textAt(row: Int, column: Int): String =
        cellAt(row, column)?.input?.editorText().orEmpty()

    /**
     * What a caller and durable storage can see of a run: the decision per mutation, and the cell list in order.
     *
     * Cell ids are freshly generated, so they cannot be compared between two runs; the editor text in list order
     * can, and it is the projection that is serialized. Comparing the accept flags alongside it keeps a path
     * that agreed on contents by rejecting everything from passing.
     */
    private data class Observed(val decisions: List<Boolean>, val cells: List<String>)

    private fun observe(engine: CalculationEngine, decisions: List<Boolean>): Observed {
        val sheet = engine.workbook().sheet(engine.workbook().activeSheetId)!!
        return Observed(decisions, sheet.cells.map { "${sheet.rowIndex(it.rowId)},${sheet.columnIndex(it.columnId)}=${it.input.editorText()}" })
    }

    /** The replaced implementation: one [CalculationEngine.commitInput] per mutation, in order. */
    private fun folded(start: CalculationSheet, mutations: List<CellMutation>): Observed {
        val engine = engineOver(start)
        val sheetId = engine.workbook().activeSheetId
        return observe(
            engine,
            mutations.map { engine.commitInput(sheetId, it.row, it.column, it.input, confirmWarning = true).accepted },
        )
    }

    /** The current implementation: one [CalculationEngine.commitBatch] over the same mutations. */
    private fun batched(start: CalculationSheet, mutations: List<CellMutation>): Observed {
        val engine = engineOver(start)
        val sheetId = engine.workbook().activeSheetId
        return observe(engine, engine.commitBatch(sheetId, mutations, confirmWarnings = true).map { it.accepted })
    }

    private fun assertBatchMatchesFold(start: CalculationSheet, mutations: List<CellMutation>) {
        val fold = folded(start, mutations)
        val batch = batched(start, mutations)
        // Agreement between two paths that both did nothing is not evidence of anything, and a case can drift
        // into that state silently — an address moved outside the sheet, a validation rule that rejects
        // everything. Checked on the fold, which is the reference side.
        assertTrue("the fold accepted no write, so this case compares two empty results", fold.decisions.any { it })
        assertTrue("the fold left no cells, so this case compares two empty sheets", fold.cells.isNotEmpty())
        assertEquals("the batch decided differently from the per-cell fold", fold.decisions, batch.decisions)
        assertEquals("the batch left a different sheet from the per-cell fold", fold.cells, batch.cells)
    }

    // ── A repeated coordinate inside one call ───────────────────────────────────────────────────────

    /**
     * Two writes to one coordinate in a single batch: the second has to win.
     *
     * The fold made this true incidentally, because the second write resolved against a sheet that already held
     * the first. A staged batch resolves its writes against the sheet as it was on entry, so unless the staging
     * carries its own view of what it has already written, the *first* value would survive — the user would type
     * over a cell and see the older of their two entries.
     */
    @Test
    fun theLastOfTwoWritesToOneCoordinateInABatchIsTheOneThatSurvives() {
        val mutations = listOf(
            CellMutation(0, 0, CellInput.Number("1")),
            CellMutation(0, 0, CellInput.Number("2")),
        )
        assertBatchMatchesFold(sheet(), mutations)

        val engine = engineOver(sheet())
        engine.commitBatch(engine.workbook().activeSheetId, mutations, confirmWarnings = true)
        assertEquals("2", engine.workbook().sheet(engine.workbook().activeSheetId)!!.textAt(0, 0))
    }

    /**
     * The same question for a formula, which also has to be *recalculated* against the surviving input.
     *
     * A batch that stored the right text but invalidated the wrong cell would leave the display showing the
     * superseded result, which is the more damaging failure of the two: the sheet would look internally
     * inconsistent with no edit the user could make to reconcile it. The stored text is compared against the
     * fold; the displayed value is asserted outright, since that is the part a cell list cannot show.
     */
    @Test
    fun aRepeatedFormulaWriteRecalculatesAgainstTheValueThatSurvived() {
        val mutations = listOf(
            CellMutation(0, 0, CellInput.Number("10")),
            CellMutation(0, 1, CellInputParser.parse("=A1*2")),
            CellMutation(0, 0, CellInput.Number("50")),
        )
        assertBatchMatchesFold(sheet(), mutations)

        val engine = engineOver(sheet())
        val sheetId = engine.workbook().activeSheetId
        engine.commitBatch(sheetId, mutations, confirmWarnings = true)
        assertEquals("100", engine.displayAt(sheetId, 0, 1))
    }

    // ── Emptying a cell inside a batch ──────────────────────────────────────────────────────────────

    /**
     * A write that blanks a cell removes it, rather than leaving a durably-empty one behind.
     *
     * The distinction is invisible on screen and visible everywhere else: a blank cell that stays in the list is
     * serialized, counted, and iterated forever after. `upsertCell` dropped it; the staged path has to drop it
     * too, in the staging view as well as in the result, or a later write in the same batch would find a cell
     * that is not there.
     */
    @Test
    fun aBatchWriteThatBlanksACellRemovesItRatherThanKeepingAnEmptyOne() {
        val engine = engineOver(sheet())
        val sheetId = engine.workbook().activeSheetId
        engine.commitBatch(sheetId, listOf(CellMutation(0, 0, CellInput.Number("7"))), confirmWarnings = true)
        val populated = engine.workbook().sheet(sheetId)!!.cells.size

        engine.commitBatch(sheetId, listOf(CellMutation(0, 0, CellInput.Blank)), confirmWarnings = true)

        val after = engine.workbook().sheet(sheetId)!!
        assertEquals("blanking the cell should shrink the list, not leave an empty one in it", populated - 1, after.cells.size)
        assertNull(after.cellAt(0, 0))
    }

    /**
     * Blanking a coordinate and writing it again in the same batch.
     *
     * The staged view has to forget the cell at the blanked slot and then accept a new one there. Getting this
     * wrong in either direction is silent: keep the removal and the rewrite vanishes, keep the old cell and the
     * rewrite lands on an identity the batch has already deleted.
     */
    @Test
    fun aCoordinateBlankedAndRewrittenInOneBatchEndsWithTheRewrittenValue() {
        val start = sheet()
        val mutations = listOf(
            CellMutation(1, 1, CellInput.Text("before")),
            CellMutation(1, 1, CellInput.Blank),
            CellMutation(1, 1, CellInput.Text("after")),
        )
        assertBatchMatchesFold(start, mutations)

        val engine = engineOver(start)
        val sheetId = engine.workbook().activeSheetId
        engine.commitBatch(sheetId, mutations, confirmWarnings = true)

        val after = engine.workbook().sheet(sheetId)!!
        assertEquals("after", after.textAt(1, 1))
        assertEquals(
            "the rewritten coordinate should hold exactly one cell",
            1,
            after.cells.count { it.rowId == after.rows[1].id && it.columnId == after.columns[1].id },
        )
    }

    /**
     * A formula reading a cell the same batch blanks sees the blank.
     *
     * This is the invalidation question rather than the storage one. Removing a cell drops the id that dependency
     * edges point at, so a batch that staged the removal but reported the wrong changed identity would leave the
     * reader displaying the value of a cell that no longer exists.
     */
    @Test
    fun aFormulaReadingACellTheSameBatchBlanksRecalculatesWithoutIt() {
        val engine = engineOver(sheet())
        val sheetId = engine.workbook().activeSheetId
        engine.commitBatch(
            sheetId,
            listOf(
                CellMutation(0, 0, CellInput.Number("4")),
                CellMutation(0, 1, CellInputParser.parse("=A1+1")),
            ),
            confirmWarnings = true,
        )
        assertEquals("5", engine.displayAt(sheetId, 0, 1))

        engine.commitBatch(sheetId, listOf(CellMutation(0, 0, CellInput.Blank)), confirmWarnings = true)

        val fold = engineOver(sheet()).let { other ->
            val otherId = other.workbook().activeSheetId
            other.commitInput(otherId, 0, 0, CellInput.Number("4"), confirmWarning = true)
            other.commitInput(otherId, 0, 1, CellInputParser.parse("=A1+1"), confirmWarning = true)
            other.commitInput(otherId, 0, 0, CellInput.Blank, confirmWarning = true)
            other.displayAt(otherId, 0, 1)
        }
        assertEquals(fold, engine.displayAt(sheetId, 0, 1))
    }

    // ── The validation rule that reads the sheet ────────────────────────────────────────────────────

    /**
     * A sheet whose column B rejects a value unless A on the first row holds a positive number.
     *
     * `CustomFormula` evaluates its source as written, so the reference is to A1 specifically rather than to the
     * row being validated. That is enough for the question here, which is whether the condition sees the sheet as
     * an earlier mutation in the same batch left it.
     */
    private fun guardedByCustomFormula(): CalculationSheet {
        val base = sheet()
        val column = base.columns[1].id
        return base.copy(
            validations = listOf(
                ValidationRule(
                    target = StableCellRange(
                        start = StableCellCoordinate(base.id, base.rows.first().id, column),
                        end = StableCellCoordinate(base.id, base.rows.last().id, column),
                    ),
                    condition = ValidationCondition.CustomFormula("=A1>0"),
                    policy = ValidationPolicy.REJECT,
                    errorMessage = "A1 must hold a positive number first",
                ),
            ),
        )
    }

    /**
     * A custom-formula rule validates each write in a batch against the writes before it.
     *
     * Every other validation condition inspects the incoming value alone, which is why the batch is allowed to
     * stage its writes and never build the intermediate sheets. A `CustomFormula` condition breaks that
     * assumption by construction: it evaluates against the sheet, so it can read a cell an earlier mutation in
     * the same batch just changed. The batch keeps the per-cell fold when such a rule is present, and this is the
     * test that fails if that exception is ever dropped as an optimization.
     */
    @Test
    fun aCustomFormulaRuleSeesTheWritesEarlierInTheSameBatchMade() {
        val guarded = guardedByCustomFormula()
        val mutations = listOf(
            CellMutation(0, 0, CellInput.Number("5")),
            CellMutation(0, 1, CellInput.Text("allowed")),
        )
        assertBatchMatchesFold(guarded, mutations)

        val engine = engineOver(guarded)
        val sheetId = engine.workbook().activeSheetId
        val results = engine.commitBatch(sheetId, mutations, confirmWarnings = true)

        assertTrue(
            "the second write was rejected, so validation did not see the first: ${results[1].error?.detail}",
            results[1].accepted,
        )
        assertEquals("allowed", engine.workbook().sheet(sheetId)!!.textAt(0, 1))
    }

    /**
     * The same rule still rejects a write nothing in the batch has yet satisfied, and rejecting it does not
     * disturb the writes around it.
     *
     * Without this, the test above could pass on a batch that had stopped validating altogether — which is the
     * failure mode a staged rewrite invites, since skipping the intermediate sheets and skipping the rule look
     * identical from the outside. The rejected write is put *first*, ahead of the one that makes the condition
     * true and the retry that then satisfies it, so the case also pins that a rejection consumes its own slot
     * and nothing else: a staged path that dropped the rest of the batch on the first rejection, or that let the
     * later writes shift into the rejected one's place, disagrees with the fold here.
     */
    @Test
    fun aCustomFormulaRuleRejectsOnlyTheWriteThatViolatedIt() {
        val guarded = guardedByCustomFormula()
        val mutations = listOf(
            CellMutation(0, 1, CellInput.Text("premature")),
            CellMutation(0, 0, CellInput.Number("5")),
            CellMutation(0, 1, CellInput.Text("allowed")),
        )
        assertBatchMatchesFold(guarded, mutations)

        val engine = engineOver(guarded)
        val sheetId = engine.workbook().activeSheetId
        val results = engine.commitBatch(sheetId, mutations, confirmWarnings = true)

        assertEquals(listOf(false, true, true), results.map { it.accepted })
        assertNotNull("the violating write should carry the rule's error", results.first().error)
        assertEquals("allowed", engine.workbook().sheet(sheetId)!!.textAt(0, 1))
    }

    /**
     * A rejected write leaves the cell exactly as it was, rather than blanking it.
     *
     * Separated from the case above because it is the one situation where the batch has nothing to write and
     * still must not touch the sheet — and where a staged path that recorded the rejection but staged the value
     * anyway would look correct in the returned decisions and wrong in storage.
     */
    @Test
    fun aRejectedWriteLeavesTheExistingValueUntouched() {
        val engine = engineOver(guardedByCustomFormula())
        val sheetId = engine.workbook().activeSheetId

        val results = engine.commitBatch(
            sheetId,
            listOf(CellMutation(0, 1, CellInput.Text("premature"))),
            confirmWarnings = true,
        )

        assertEquals(false, results.single().accepted)
        assertNotNull(results.single().error)
        assertEquals("", engine.workbook().sheet(sheetId)!!.textAt(0, 1))
        assertNull("a rejected write should not create a cell", engine.workbook().sheet(sheetId)!!.cellAt(0, 1))
    }

    // ── Selection-wide formatting ──────────────────────────────────────────────────────────────────

    /**
     * A format applied to a selection reaches every named coordinate, including those that held nothing, and
     * creates one cell per coordinate rather than one per address.
     *
     * The staged path creates a cell for an empty coordinate exactly as the fold did. If it resolved every
     * address against the entry sheet without tracking what it created, two addresses landing on one coordinate
     * would produce two cells for it.
     */
    @Test
    fun aFormatAppliedAcrossASelectionReachesEveryCoordinateExactlyOnce() {
        val base = sheet()
        val addresses = listOf(GridAddress(0, 0), GridAddress(0, 1), GridAddress(1, 0), GridAddress(0, 0))

        val workbook = CalculationMutations.applyFormat(
            CalculationWorkbook(sheets = listOf(base), activeSheetId = base.id),
            base.id,
            addresses,
            CellFormat(horizontalAlignment = HorizontalCellAlignment.CENTER),
        )

        val after = workbook.sheet(base.id)!!
        assertEquals("three distinct coordinates were named, so three cells should exist", 3, after.cells.size)
        listOf(GridAddress(0, 0), GridAddress(0, 1), GridAddress(1, 0)).forEach { address ->
            assertEquals(
                "the cell at ${address.a1()} did not take the format",
                HorizontalCellAlignment.CENTER,
                after.cellAt(address.row, address.column)?.format?.horizontalAlignment,
            )
        }
    }

    /**
     * Formatting preserves the value and the formula already in the cell.
     *
     * The staged path rebuilds the cell list rather than editing it in place, so a mistake here would not throw —
     * it would return a sheet whose formatting is right and whose contents are gone. That is the worst available
     * outcome for a formatting command, and it is worth one explicit test. The number format is also checked
     * through the display, since a format the renderer never reads would satisfy the storage assertions alone.
     */
    @Test
    fun formattingACellLeavesItsValueAndItsFormulaAlone() {
        val engine = engineOver(sheet())
        val sheetId = engine.workbook().activeSheetId
        engine.commitBatch(
            sheetId,
            listOf(
                CellMutation(0, 0, CellInput.Number("3")),
                CellMutation(0, 1, CellInputParser.parse("=A1*3")),
            ),
            confirmWarnings = true,
        )

        val formatted = CalculationMutations.setNumberFormat(
            engine.workbook(),
            sheetId,
            listOf(GridAddress(0, 0), GridAddress(0, 1)),
            CellNumberFormat(kind = NumberFormatKind.NUMBER),
        )
        engine.replaceWorkbook(formatted)

        assertEquals("3", formatted.sheet(sheetId)!!.textAt(0, 0))
        assertEquals("=A1*3", formatted.sheet(sheetId)!!.textAt(0, 1))
        assertEquals("9.00", engine.displayAt(sheetId, 0, 1))
    }

    /**
     * Two format deltas over one address in one call both survive.
     *
     * `applyFormat` merges its delta into what the cell already has, so an address appearing twice in one call
     * accumulates both. A staged path that resolved each address against the entry sheet would apply the second
     * delta to the *original* cell and discard the first — invisible unless the two deltas touch different
     * fields, which is exactly when a user would notice.
     */
    @Test
    fun twoFormatDeltasOverOneAddressInOneCallBothSurvive() {
        val base = sheet()
        var workbook = CalculationWorkbook(sheets = listOf(base), activeSheetId = base.id)

        workbook = CalculationMutations.applyFormat(workbook, base.id, listOf(GridAddress(2, 2)), CellFormat(bold = true))
        workbook = CalculationMutations.applyFormat(workbook, base.id, listOf(GridAddress(2, 2)), CellFormat(italic = true))

        val cell = workbook.sheet(base.id)!!.cellAt(2, 2)
        assertEquals("the first delta was discarded", true, cell?.format?.bold)
        assertEquals("the second delta was discarded", true, cell?.format?.italic)
    }

    // ── The one input the batch does not reproduce ──────────────────────────────────────────────────

    /**
     * Writing a cell whose id already sits at another coordinate moves it, leaving one cell per coordinate.
     *
     * No caller does this — `commitInput` and `commitBatch` both derive their candidate from the coordinate they
     * are writing, so an id never arrives at a slot it did not come from — but `upsertCells` is public API and
     * this is the single input where it deliberately does not agree with a fold of `upsertCell`. The fold scans
     * for an id match *or* a coordinate match and replaces whichever it meets first, so a moved id overwrites the
     * cell at the old slot and leaves the new slot's occupant beside it: two cells at one coordinate, which
     * every coordinate-keyed lookup downstream would then resolve by list order. Pinned here so the divergence
     * stays a decision rather than becoming an accident, and so a future caller can rely on it.
     */
    @Test
    fun writingACellWhoseIdSitsElsewhereMovesItRatherThanDuplicatingTheCoordinate() {
        val base = sheet()
        val moving = CalculationCell(rowId = base.rows[0].id, columnId = base.columns[0].id, input = CellInput.Number("1"))
        val resident = CalculationCell(rowId = base.rows[1].id, columnId = base.columns[1].id, input = CellInput.Number("2"))
        val start = base.copy(cells = listOf(moving, resident))

        val moved = start.upsertCells(
            listOf(moving.copy(rowId = base.rows[1].id, columnId = base.columns[1].id)),
        )

        assertEquals("the moved id should leave one cell, not two", 1, moved.cells.size)
        assertNull("the coordinate the id moved off should be vacant", moved.cellAt(0, 0))
        assertEquals("1", moved.textAt(1, 1))
        assertEquals(
            "the surviving cell should be the one that moved, keeping its identity",
            moving.id,
            moved.cellAt(1, 1)?.id,
        )

        // The fold this replaced corrupts the same input, which is why equivalence is not claimed for it.
        val folded = start.upsertCell(moving.copy(rowId = base.rows[1].id, columnId = base.columns[1].id))
        assertEquals(
            "the fold is expected to leave two cells at one coordinate here; if it no longer does, the " +
                "divergence documented on upsertCells is stale",
            2,
            folded.cells.count { it.rowId == base.rows[1].id && it.columnId == base.columns[1].id },
        )
    }

    @Test
    fun chainedMovesKeepTheCellThatReplacedADisplacedId() {
        val base = sheet()
        val first = CalculationCell(rowId = base.rows[0].id, columnId = base.columns[0].id, input = CellInput.Text("first"))
        val second = CalculationCell(rowId = base.rows[1].id, columnId = base.columns[1].id, input = CellInput.Text("second"))
        val start = base.copy(cells = listOf(first, second))

        val moved = start.upsertCells(
            listOf(
                first.copy(rowId = base.rows[1].id, columnId = base.columns[1].id),
                second.copy(rowId = base.rows[2].id, columnId = base.columns[2].id),
            ),
        )

        assertEquals("first", moved.textAt(1, 1))
        assertEquals("second", moved.textAt(2, 2))
        assertEquals("both moved identities must survive", setOf(first.id, second.id), moved.cells.map { it.id }.toSet())
    }

    @Test
    fun anEmptyMovedCellRemovesItsIdentityWithoutErasingTheTargetResident() {
        val base = sheet()
        val moving = CalculationCell(rowId = base.rows[0].id, columnId = base.columns[0].id, input = CellInput.Text("moving"))
        val resident = CalculationCell(rowId = base.rows[1].id, columnId = base.columns[1].id, input = CellInput.Text("resident"))
        val start = base.copy(cells = listOf(moving, resident))

        val after = start.upsertCells(
            listOf(
                moving.copy(
                    rowId = base.rows[1].id,
                    columnId = base.columns[1].id,
                    input = CellInput.Blank,
                ),
            ),
        )

        assertNull(after.cell(moving.id))
        assertEquals("resident", after.textAt(1, 1))
        assertEquals(resident.id, after.cellAt(1, 1)?.id)
    }

    // ── List order, because it is serialized ───────────────────────────────────────────────────────

    /**
     * A batch of scattered writes leaves the same cell list, in the same order, as the fold would.
     *
     * Order is not cosmetic here: the list is serialized, so a batch that reordered it would rewrite storage for
     * every affected sheet and make two documents with identical contents compare unequal. The addresses are
     * deliberately out of reading order, with one repeat, since a path that happened to sort its writes would
     * agree with the fold on any monotonic input.
     */
    @Test
    fun aBatchOfScatteredWritesLeavesTheCellListInTheOrderAFoldWould() {
        assertBatchMatchesFold(
            sheet(),
            listOf(
                CellMutation(3, 2, CellInput.Number("30")),
                CellMutation(0, 0, CellInput.Number("10")),
                CellMutation(2, 1, CellInput.Number("20")),
                CellMutation(0, 0, CellInput.Number("11")),
                CellMutation(1, 3, CellInputParser.parse("=A1+C3")),
            ),
        )
    }

    /**
     * A batch that writes past the edge of the sheet agrees with the fold about which writes it kept.
     *
     * The staged path skips an address it cannot resolve, and the fold rejected one. Both are defensible in
     * isolation, and they are only equivalent if the out-of-range write neither lands nor takes a valid
     * neighbouring write down with it.
     */
    @Test
    fun aBatchWithAnOutOfRangeWriteKeepsTheSameWritesTheFoldKept() {
        val start = sheet(rows = 4, columns = 3)
        assertBatchMatchesFold(
            start,
            listOf(
                CellMutation(0, 0, CellInput.Number("1")),
                CellMutation(9, 0, CellInput.Number("2")),
                CellMutation(0, 7, CellInput.Number("3")),
                CellMutation(3, 2, CellInput.Number("4")),
            ),
        )
    }
}
