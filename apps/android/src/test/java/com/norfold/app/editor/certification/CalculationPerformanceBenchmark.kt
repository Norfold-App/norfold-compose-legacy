package com.norfold.app.editor.certification

import com.norfold.app.editor.calculation.CalculationColumn
import com.norfold.app.editor.calculation.CalculationEngine
import com.norfold.app.editor.calculation.CalculationRow
import com.norfold.app.editor.calculation.CalculationSheet
import com.norfold.app.editor.calculation.CalculationWorkbook
import com.norfold.app.editor.calculation.CellInput
import com.norfold.app.editor.calculation.CellInputParser
import com.norfold.app.editor.calculation.CellMutation
import com.norfold.app.editor.calculation.SheetId
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.random.Random

/**
 * Measured cost of the spreadsheet hot paths, at the scale the canonical reference names.
 *
 * §10.5 sets "an initial safety target around 100,000 cells" and states that final thresholds are
 * benchmark-dependent — so there is no canonical number to assert against, and the private roadmap lists
 * calc recompute latency as an open `BENCHMARK` value with no accepted prior baseline. This suite therefore
 * *measures* rather than checks a target, and every assertion is one of two machine-independent kinds:
 *
 *  - **scaling shape** — how cost grows as the sheet grows, which is a property of the algorithm rather than
 *    of the hardware the suite runs on;
 *  - **a ceiling loose enough that only a real regression reaches it** — because "does not scale with size"
 *    is not the same as "is fast enough", and a path that took a fixed two seconds would satisfy the first
 *    while failing the product.
 *
 * A wall-clock threshold tight enough to be interesting would be flaky on unknown hardware, and a threshold
 * loose enough to be stable would assert nothing; the ratio between two sizes measured in the same JVM on
 * the same machine avoids both. Absolute medians are printed under `[benchmark]` for transcription into the
 * private record, where the machine they were measured on can be named alongside them.
 */
class CalculationPerformanceBenchmark {

    /**
     * Drives every path measured below, on a sheet small enough to be quick, before any timing starts.
     *
     * [medianMicros] discards warmup runs of the block it is given, which is enough for one measurement but
     * not for a class of them: whichever test runs first would otherwise pay to compile the engine, and its
     * number would be several times the steady-state cost. That distorts the absolute figures the private
     * record transcribes, and it distorts the ratios worse — an inflated first measurement makes the second
     * one look like an improvement, so a genuine regression could hide inside it.
     */
    @Before
    fun warmTheMeasuredPaths() {
        val (engine, sheetId, _) = populated(rows = 300)
        repeat(600) { engine.commitInput(sheetId, it % 300, 0, CellInput.Number(it.toString()), confirmWarning = true) }
        repeat(100) { engine.commitInput(sheetId, it, 3, CellInput.Number("1"), confirmWarning = true) }
        populated(rows = 300)
    }

    // ── Measurement ─────────────────────────────────────────────────────────────────────────────────

    /**
     * Median of [trials] after [warmup] discarded runs.
     *
     * The median rather than the mean because a JIT recompilation or a GC pause during one trial would
     * dominate a mean and make the ratio assertions flaky; warmup runs first because measuring the
     * interpreter rather than the compiled code would exaggerate whichever path happened to run first.
     */
    private fun medianMicros(warmup: Int = 3, trials: Int = 9, block: () -> Unit): Long {
        repeat(warmup) { block() }
        val samples = LongArray(trials) {
            val start = System.nanoTime()
            block()
            System.nanoTime() - start
        }
        samples.sort()
        return samples[trials / 2] / 1_000
    }

    private fun report(label: String, micros: Long) {
        println("[benchmark] $label: ${micros}µs")
    }

    // ── Fixtures ────────────────────────────────────────────────────────────────────────────────────

    private fun sheetOf(rows: Int, columns: Int) = CalculationSheet(
        rowCount = rows,
        columnCount = columns,
        rows = List(rows) { CalculationRow() },
        columns = List(columns) { CalculationColumn() },
    )

    /**
     * A populated sheet of [rows] × 4 columns: two literal columns, one formula reading its own row, and one
     * left blank so a first-write case has somewhere to land.
     *
     * Synthetic and seeded (§26) so the numbers are comparable between runs: the values differ per cell, so
     * no accidental deduplication flatters the measurement, but the same run produces the same sheet.
     */
    private fun populated(rows: Int): Triple<CalculationEngine, SheetId, Int> {
        val sheet = sheetOf(rows, 4)
        val random = Random(20260810)
        val engine = CalculationEngine(CalculationWorkbook(sheets = listOf(sheet), activeSheetId = sheet.id))
        val mutations = buildList {
            for (row in 0 until rows) {
                add(CellMutation(row, 0, CellInput.Number(random.nextInt(1, 9_999).toString())))
                add(CellMutation(row, 1, CellInput.Number(random.nextInt(1, 9_999).toString())))
                add(CellMutation(row, 2, CellInputParser.parse("=A${row + 1}+B${row + 1}")))
            }
        }
        engine.commitBatch(sheet.id, mutations)
        return Triple(engine, sheet.id, mutations.size)
    }

    // ── One keystroke, measured against the sheet it lands in ───────────────────────────────────────

    /**
     * The interaction-critical path: overwriting a cell that already holds a value.
     *
     * Reported rather than merely asserted, because this is the number the private record needs and the one
     * §10.5 leaves open. The ceiling is deliberately far above any plausible measurement — it exists to catch
     * a path that has become quadratic or started recalculating the workbook, not to police hardware.
     */
    @Test
    fun oneKeystrokeInAPopulatedSheetStaysWellInsideAnInteractiveBudget() {
        val (engine, sheetId, cellCount) = populated(rows = 4_000)
        var nudge = 0

        val micros = medianMicros {
            engine.commitInput(sheetId, 1_999, 0, CellInput.Number((++nudge).toString()), confirmWarning = true)
        }

        report("one keystroke, $cellCount populated cells", micros)
        assertTrue(
            "one keystroke over $cellCount cells took ${micros}µs, which is past any interactive budget",
            micros < 50_000,
        )
    }

    /**
     * The shape the ceiling above cannot see: whether the keystroke's cost per cell is constant, or grows
     * with the sheet as well.
     *
     * One local edit *is* proportional to the number of populated cells, and by design: a sheet's cell list
     * is persistent, so a write copies it rather than mutating it in place. That copy is what makes History
     * cheap and undo exact — the previous list stays valid and shared — and it is a pointer-array copy, so
     * the constant is small. Measured, it is well under a tenth of a microsecond per populated cell, which
     * puts the §10.5 safety target of around 100,000 cells inside a single frame.
     *
     * What would not be acceptable is *super*-linear growth: a scan per impacted cell, or a recalculation
     * that widened with the document, would put the same target in seconds. So the bar is set between the
     * two — quadrupling the sheet may cost up to four times as much, which linear work does, and is allowed
     * headroom above that for measurement noise, but nothing close to the sixteen-fold growth a quadratic
     * path would show. Compared as a ratio inside one JVM on one machine, so the result does not depend on
     * the hardware the suite happens to run on.
     */
    @Test
    fun oneKeystrokeCostsNoMoreThanLinearlyMoreAsTheSheetGrows() {
        fun keystrokeMicros(rows: Int): Long {
            val (engine, sheetId, _) = populated(rows)
            var nudge = 0
            return medianMicros {
                engine.commitInput(sheetId, rows / 2, 0, CellInput.Number((++nudge).toString()), confirmWarning = true)
            }
        }

        val small = keystrokeMicros(1_000)
        val large = keystrokeMicros(4_000)
        report("one keystroke, 3000 cells", small)
        report("one keystroke, 12000 cells", large)

        val growth = large.toDouble() / small.coerceAtLeast(1).toDouble()
        assertTrue(
            "quadrupling the sheet multiplied one keystroke by ${"%.1f".format(growth)}× " +
                "(${small}µs → ${large}µs); linear work would be about 4×, so the edit is doing more than " +
                "copy the cell list — something is scanning or recalculating per edit",
            growth < 8.0,
        )
    }

    /**
     * A first write to a coordinate that held nothing is the one local edit allowed to consult every formula:
     * a blank coordinate has no `CellId`, so no dependency edge can point at it, and its readers are found by
     * scanning the reference spans instead. Allowed, but the allowance is what needs bounding — an edit that
     * reads every formula is one step away from recalculating every formula.
     *
     * So this measures the same two things the ordinary keystroke does: the cost at the larger size, and how
     * that cost grows. The span scan is one integer comparison per formula against a rectangle, so it should
     * stay a small addition to the copy the write already performs — visible in the growth, not dominating it.
     */
    @Test
    fun theFirstWriteToABlankCoordinateStaysWithinASmallMultipleOfAnOrdinaryWrite() {
        val (engine, sheetId, cellCount) = populated(rows = 4_000)

        var nudge = 0
        val ordinary = medianMicros {
            engine.commitInput(sheetId, 1_999, 0, CellInput.Number((++nudge).toString()), confirmWarning = true)
        }
        // A fresh row per trial, so every one of them is genuinely a first write: once a coordinate holds a
        // cell it has an id, and the second write to it takes the ordinary path instead.
        var row = 0
        val firstWrite = medianMicros {
            engine.commitInput(sheetId, row++, 3, CellInput.Number("1"), confirmWarning = true)
        }

        report("ordinary write, $cellCount populated cells", ordinary)
        report("first write to a blank coordinate, $cellCount populated cells", firstWrite)
        assertTrue(
            "a first write over $cellCount cells took ${firstWrite}µs, past any interactive budget",
            firstWrite < 50_000,
        )

        // Compared against the ordinary write rather than against a second size, because the regression worth
        // catching here is "the first write recalculates the sheet", and recalculating is *also* linear in the
        // formula count — so a growth ratio cannot tell the two apart, while the cost relative to a write that
        // recalculates nothing can. Scanning spans is an integer comparison per formula against a rectangle;
        // recalculating is a parse and an evaluation per formula, which is orders of magnitude more, not a
        // small multiple. The bar leaves room for the scan to cost several times the write it accompanies.
        val multiple = firstWrite.toDouble() / ordinary.coerceAtLeast(1).toDouble()
        assertTrue(
            "a first write to a blank coordinate cost ${"%.1f".format(multiple)}× an ordinary write on the " +
                "same sheet (${ordinary}µs → ${firstWrite}µs), which is the cost of recalculating the " +
                "formulas rather than of finding them",
            multiple < 20.0,
        )
    }

    /**
     * Filling a sheet in one batch — what a paste or an import does — measured at three sizes so the growth
     * rate is visible rather than inferred.
     *
     * This path was quadratic: the batch applied its writes by folding a single-cell upsert, and each of
     * those scanned and copied the whole cell list, so doubling the sheet roughly quadrupled the work.
     * Measured, doubling cost about 3.5× to 3.9× — which extrapolates to minutes for the §10.5 safety target
     * of around 100,000 cells, for one paste. The batch now indexes the coordinates once and copies the list
     * once, and resolving the formulas' references indexes rather than rescans, which is linear in the sheet.
     *
     * Asserted on the larger doubling rather than both, because the smaller sizes are where fixed setup cost
     * still shows and the ratio is noisiest. The bar sits between the two shapes: linear doubles, quadratic
     * quadruples, and the ratio is taken inside one JVM so the hardware cancels out.
     */
    @Test
    fun fillingASheetInOneBatchScalesWithTheSheetRatherThanWithItsSquare() {
        // Warmed and taken as a median for the same reason [medianMicros] is, and separately from it because
        // the work being timed is the fixture itself rather than a call against a prepared one.
        fun buildMicros(rows: Int): Long {
            populated(rows)
            val samples = LongArray(5) {
                val start = System.nanoTime()
                populated(rows)
                System.nanoTime() - start
            }
            samples.sort()
            return samples[2] / 1_000
        }

        val a = buildMicros(500)
        val b = buildMicros(1_000)
        val c = buildMicros(2_000)
        report("bulk populate 1500 cells", a)
        report("bulk populate 3000 cells", b)
        report("bulk populate 6000 cells", c)
        report("doubling ratio b/a x100", (b.toDouble() / a.coerceAtLeast(1) * 100).toLong())
        report("doubling ratio c/b x100", (c.toDouble() / b.coerceAtLeast(1) * 100).toLong())

        val growth = c.toDouble() / b.coerceAtLeast(1).toDouble()
        assertTrue(
            "doubling the sheet multiplied one bulk fill by ${"%.1f".format(growth)}× (${b}µs → ${c}µs); " +
                "linear work would be about 2×, so the batch is applying its writes one at a time again",
            growth < 3.0,
        )
    }
}
