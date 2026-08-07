package com.norfold.app.editor.calculation

import java.time.LocalDate
import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Phase-4 formula baseline completion: the functions added on top of the original 32.
 *
 * Every function gets positive coverage, an argument-count case, and — where it can fail for a reason other
 * than arity — the specific error it is supposed to return. Error *propagation* is asserted separately from
 * error *production*, because the two have been confused before: a function that filters errors out of a
 * range reports a confident number where a `#LIMIT!` was owed.
 */
class CalculationFunctionBaselineTest {
    private fun input(workbook: CalculationWorkbook, sheetId: SheetId, row: Int, column: Int, raw: String): CalculationWorkbook {
        val engine = CalculationEngine(workbook)
        assertTrue(engine.commitInput(sheetId, row, column, CellInputParser.parse(raw)).accepted)
        return engine.workbook()
    }

    private fun evaluate(formula: String, setup: List<Triple<Int, Int, String>> = emptyList()): CalculatedValue {
        var workbook = CalculationWorkbook()
        val sheet = workbook.activeSheet()
        setup.forEach { (row, column, raw) -> workbook = input(workbook, sheet.id, row, column, raw) }
        workbook = input(workbook, sheet.id, 30, 12, formula)
        return CalculationEngine(workbook).valueAt(sheet.id, 30, 12)
    }

    private fun display(formula: String, setup: List<Triple<Int, Int, String>> = emptyList()): String =
        evaluate(formula, setup).displayText()

    private fun errorKind(formula: String, setup: List<Triple<Int, Int, String>> = emptyList()): CalculationErrorKind? =
        (evaluate(formula, setup) as? CalculatedValue.ErrorValue)?.error?.kind

    /** A1:A5 numbers, B1:B5 labels, C1:C5 second criteria — the fixture the criteria functions share. */
    private val grid = listOf(
        Triple(0, 0, "10"), Triple(1, 0, "20"), Triple(2, 0, "30"), Triple(3, 0, "40"), Triple(4, 0, "50"),
        Triple(0, 1, "red"), Triple(1, 1, "blue"), Triple(2, 1, "red"), Triple(3, 1, "blue"), Triple(4, 1, "red"),
        Triple(0, 2, "x"), Triple(1, 2, "y"), Triple(2, 2, "x"), Triple(3, 2, "x"), Triple(4, 2, "y"),
    )

    // ---- Math and numeric --------------------------------------------------------------------------

    @Test fun sign_returnsMinusOneZeroOrOne() {
        assertEquals("1", display("=SIGN(42)"))
        assertEquals("0", display("=SIGN(0)"))
        assertEquals("-1", display("=SIGN(-0.5)"))
        assertEquals(CalculationErrorKind.VALUE, errorKind("=SIGN()"))
        assertEquals(CalculationErrorKind.VALUE, errorKind("=SIGN(1,2)"))
    }

    @Test fun int_floorsTowardNegativeInfinityRatherThanTruncating() {
        assertEquals("5", display("=INT(5.9)"))
        // Truncation would give -1 here. Excel's INT floors, and so does this one.
        assertEquals("-2", display("=INT(-1.5)"))
        assertEquals("-2", display("=INT(-2)"))
        assertEquals(CalculationErrorKind.VALUE, errorKind("=INT()"))
    }

    @Test fun mod_takesTheSignOfTheDivisorAndRefusesZero() {
        assertEquals("1", display("=MOD(7,2)"))
        // BigDecimal.remainder would give -1; Excel's MOD follows the divisor's sign.
        assertEquals("1", display("=MOD(-3,2)"))
        assertEquals("-1", display("=MOD(3,-2)"))
        assertEquals(CalculationErrorKind.DIVIDE_BY_ZERO, errorKind("=MOD(5,0)"))
        assertEquals(CalculationErrorKind.VALUE, errorKind("=MOD(5)"))
    }

    @Test fun power_andSqrt_produceNumbersAndRefuseTheImpossible() {
        assertEquals("8", display("=POWER(2,3)"))
        assertEquals("0.5", display("=POWER(2,-1)"))
        assertEquals("4", display("=SQRT(16)"))
        assertEquals(CalculationErrorKind.VALUE, errorKind("=SQRT(-1)"))
        assertEquals(CalculationErrorKind.VALUE, errorKind("=POWER(2)"))
        assertEquals(CalculationErrorKind.VALUE, errorKind("=SQRT(1,2)"))
    }

    @Test fun product_multipliesARangeAndPropagatesErrors() {
        assertEquals("12000000", display("=PRODUCT(A1:A5)", grid))
        assertEquals("24", display("=PRODUCT(2,3,4)"))
        // An empty product is zero here rather than one, matching SUM's empty-range convention.
        assertEquals("0", display("=PRODUCT(Z90:Z95)"))
        assertEquals(CalculationErrorKind.DIVIDE_BY_ZERO, errorKind("=PRODUCT(A1:A2,1/0)", grid))
    }

    // ---- Logical and error --------------------------------------------------------------------------

    @Test fun ifs_choosesTheFirstTrueBranchAndReportsWhenNoneMatch() {
        assertEquals("small", display("=IFS(A1<15,\"small\",A1<50,\"large\")", grid))
        assertEquals("large", display("=IFS(A3<15,\"small\",A3<50,\"large\")", grid))
        assertEquals(CalculationErrorKind.UNAVAILABLE, errorKind("=IFS(FALSE,1,FALSE,2)"))
        assertEquals(CalculationErrorKind.VALUE, errorKind("=IFS(TRUE)"))
        assertEquals(CalculationErrorKind.VALUE, errorKind("=IFS(TRUE,1,FALSE)"))
    }

    @Test fun ifs_doesNotEvaluateTheBranchesItDidNotChoose() {
        // The losing branch divides by zero. A non-lazy IFS would surface that error.
        assertEquals("chosen", display("=IFS(TRUE,\"chosen\",TRUE,1/0)"))
    }

    @Test fun ifna_catchesOnlyUnavailableAndLetsOtherErrorsThrough() {
        assertEquals("fallback", display("=IFNA(MATCH(\"missing\",B1:B5,0),\"fallback\")", grid))
        assertEquals("1", display("=IFNA(1,\"fallback\")"))
        // #DIV/0! is not #N/A: IFNA must not swallow it the way IFERROR would.
        assertEquals(CalculationErrorKind.DIVIDE_BY_ZERO, errorKind("=IFNA(1/0,\"fallback\")"))
        assertEquals(CalculationErrorKind.VALUE, errorKind("=IFNA(1)"))
    }

    // ---- Conditional aggregation --------------------------------------------------------------------

    @Test fun averageif_averagesMatchesAndRefusesAnEmptyAverage() {
        assertEquals("30", display("=AVERAGEIF(B1:B5,\"red\",A1:A5)", grid))
        assertEquals("40", display("=AVERAGEIF(A1:A5,\">25\")", grid))
        // Averaging nothing is not zero.
        assertEquals(CalculationErrorKind.DIVIDE_BY_ZERO, errorKind("=AVERAGEIF(B1:B5,\"green\",A1:A5)", grid))
        assertEquals(CalculationErrorKind.VALUE, errorKind("=AVERAGEIF(A1:A5)", grid))
    }

    @Test fun sumifs_countifs_andAverageifs_requireEveryCriterionToHold() {
        assertEquals("40", display("=SUMIFS(A1:A5,B1:B5,\"red\",C1:C5,\"x\")", grid))
        assertEquals("2", display("=COUNTIFS(B1:B5,\"red\",C1:C5,\"x\")", grid))
        assertEquals("20", display("=AVERAGEIFS(A1:A5,B1:B5,\"red\",C1:C5,\"x\")", grid))
        // A>25 selects rows 3-5 (30/40/50); their C values are x/x/y, so only 30 and 40 survive both tests.
        assertEquals("70", display("=SUMIFS(A1:A5,A1:A5,\">25\",C1:C5,\"x\")", grid))
    }

    @Test fun multiCriteriaAggregates_reportArgumentCountAndEmptyAverage() {
        assertEquals(CalculationErrorKind.VALUE, errorKind("=SUMIFS(A1:A5,B1:B5)", grid))
        assertEquals(CalculationErrorKind.VALUE, errorKind("=COUNTIFS(B1:B5)", grid))
        assertEquals(CalculationErrorKind.VALUE, errorKind("=AVERAGEIFS(A1:A5,B1:B5,\"red\",C1:C5)", grid))
        assertEquals(CalculationErrorKind.DIVIDE_BY_ZERO, errorKind("=AVERAGEIFS(A1:A5,B1:B5,\"green\")", grid))
    }

    // ---- Text ---------------------------------------------------------------------------------------

    @Test fun concatenate_andTextjoin_buildStrings() {
        assertEquals("Norfold", display("=CONCATENATE(\"Nor\",\"fold\")"))
        assertEquals("red-blue-red-blue-red", display("=TEXTJOIN(\"-\",TRUE,B1:B5)", grid))
        assertEquals("10,20,30,40,50", display("=TEXTJOIN(\",\",TRUE,A1:A5)", grid))
        assertEquals(CalculationErrorKind.VALUE, errorKind("=TEXTJOIN(\"-\",TRUE)"))
    }

    @Test fun textjoin_ignoreEmptyFlagDecidesWhetherBlanksTakeASlot() {
        val sparse = listOf(Triple(0, 5, "a"), Triple(2, 5, "c"))
        assertEquals("a,c", display("=TEXTJOIN(\",\",TRUE,F1:F3)", sparse))
        assertEquals("a,,c", display("=TEXTJOIN(\",\",FALSE,F1:F3)", sparse))
    }

    @Test fun find_isCaseSensitiveAndSearchIsNot() {
        assertEquals("3", display("=FIND(\"r\",\"Norfold\")"))
        assertEquals("1", display("=SEARCH(\"n\",\"Norfold\")"))
        // FIND respects case, so a lowercase needle does not match the capital N.
        assertEquals(CalculationErrorKind.VALUE, errorKind("=FIND(\"n\",\"Norfold\")"))
        // Starting at 4 skips the "o" at 2 and finds the one at 5; the result is an absolute position.
        assertEquals("5", display("=FIND(\"o\",\"Norfold\",4)"))
        assertEquals(CalculationErrorKind.VALUE, errorKind("=FIND(\"o\",\"Norfold\",99)"))
        assertEquals(CalculationErrorKind.VALUE, errorKind("=FIND(\"o\")"))
    }

    @Test fun substitute_replacesAllOccurrencesOrOnlyTheRequestedOne() {
        assertEquals("b-b-b", display("=SUBSTITUTE(\"a-a-a\",\"a\",\"b\")"))
        assertEquals("a-b-a", display("=SUBSTITUTE(\"a-a-a\",\"a\",\"b\",2)"))
        // Asking for an occurrence that does not exist leaves the text untouched rather than erroring.
        assertEquals("a-a-a", display("=SUBSTITUTE(\"a-a-a\",\"a\",\"b\",9)"))
        assertEquals(CalculationErrorKind.VALUE, errorKind("=SUBSTITUTE(\"a\",\"a\",\"b\",0)"))
        assertEquals(CalculationErrorKind.VALUE, errorKind("=SUBSTITUTE(\"a\",\"a\")"))
    }

    @Test fun value_convertsNumericTextAndRefusesTheRest() {
        assertEquals("42", display("=VALUE(\"42\")"))
        assertEquals("-1.5", display("=VALUE(\"-1.5\")"))
        assertEquals(CalculationErrorKind.VALUE, errorKind("=VALUE(\"abc\")"))
        assertEquals(CalculationErrorKind.VALUE, errorKind("=VALUE()"))
    }

    // ---- Date and time ------------------------------------------------------------------------------

    @Test fun datevalue_parsesIsoDatesOnly() {
        assertEquals("2026-08-06", display("=DATEVALUE(\"2026-08-06\")"))
        assertEquals(CalculationErrorKind.VALUE, errorKind("=DATEVALUE(\"6 August 2026\")"))
        assertEquals(CalculationErrorKind.VALUE, errorKind("=DATEVALUE()"))
    }

    @Test fun time_andItsComponentsRoundTrip() {
        assertEquals("13", display("=HOUR(TIME(13,45,30))"))
        assertEquals("45", display("=MINUTE(TIME(13,45,30))"))
        assertEquals("30", display("=SECOND(TIME(13,45,30))"))
        assertEquals(CalculationErrorKind.VALUE, errorKind("=TIME(25,0,0)"))
        assertEquals(CalculationErrorKind.VALUE, errorKind("=TIME(1,2)"))
        // A plain date carries no clock, so asking it for an hour is an error rather than a silent zero.
        assertEquals(CalculationErrorKind.VALUE, errorKind("=HOUR(DATEVALUE(\"2026-08-06\"))"))
    }

    @Test fun today_andNow_readTheInjectedClockAndRejectArguments() {
        val fixed = LocalDateTime.of(2026, 8, 6, 11, 22, 33)
        var workbook = CalculationWorkbook()
        val sheet = workbook.activeSheet()
        workbook = input(workbook, sheet.id, 0, 0, "=TODAY()")
        workbook = input(workbook, sheet.id, 1, 0, "=NOW()")
        val engine = CalculationEngine(workbook, clock = { fixed })
        assertEquals("2026-08-06", engine.valueAt(sheet.id, 0, 0).displayText())
        assertEquals(fixed, (engine.valueAt(sheet.id, 1, 0) as CalculatedValue.DateTimeValue).value)
        assertEquals(CalculationErrorKind.VALUE, errorKind("=TODAY(1)"))
        assertEquals(CalculationErrorKind.VALUE, errorKind("=NOW(1)"))
    }

    @Test fun onePassObservesOneInstantAcrossEveryVolatileCell() {
        // The clock advances a full day on every read. If any volatile cell sampled it independently, the two
        // NOW() cells would differ and NOW()-NOW() would be non-zero.
        var tick = 0L
        val advancing = {
            tick += 1
            LocalDateTime.of(2026, 8, 6, 0, 0, 0).plusDays(tick)
        }
        var workbook = CalculationWorkbook()
        val sheet = workbook.activeSheet()
        workbook = input(workbook, sheet.id, 0, 0, "=NOW()")
        workbook = input(workbook, sheet.id, 1, 0, "=NOW()")
        workbook = input(workbook, sheet.id, 2, 0, "=TODAY()")
        val engine = CalculationEngine(workbook, clock = advancing)
        val first = (engine.valueAt(sheet.id, 0, 0) as CalculatedValue.DateTimeValue).value
        val second = (engine.valueAt(sheet.id, 1, 0) as CalculatedValue.DateTimeValue).value
        val date = (engine.valueAt(sheet.id, 2, 0) as CalculatedValue.DateValue).value
        assertEquals(first, second)
        assertEquals(first.toLocalDate(), date)
        assertEquals(engine.evaluationInstant(), first)
    }

    @Test fun aLaterPassObservesALaterInstantAndDependentsFollow() {
        var tick = 0L
        val advancing = {
            tick += 1
            LocalDateTime.of(2026, 8, 6, 0, 0, 0).plusDays(tick)
        }
        var workbook = CalculationWorkbook()
        val sheet = workbook.activeSheet()
        workbook = input(workbook, sheet.id, 0, 0, "=TODAY()")
        // A dependent of a volatile cell is just as stale as the volatile cell itself, and the dependency
        // graph cannot say so — the pass opener has to invalidate the whole closure.
        workbook = input(workbook, sheet.id, 1, 0, "=YEAR(A1)+DAY(A1)")
        val engine = CalculationEngine(workbook, clock = advancing)
        val firstDate = (engine.valueAt(sheet.id, 0, 0) as CalculatedValue.DateValue).value
        val firstDerived = engine.valueAt(sheet.id, 1, 0).displayText()
        engine.replaceWorkbook(engine.workbook(), changedCells = emptySet())
        val secondDate = (engine.valueAt(sheet.id, 0, 0) as CalculatedValue.DateValue).value
        val secondDerived = engine.valueAt(sheet.id, 1, 0).displayText()
        assertNotEquals(firstDate, secondDate)
        assertEquals(firstDate.plusDays(1), secondDate)
        assertNotEquals(firstDerived, secondDerived)
    }

    @Test fun anOrdinaryCellIsNotInvalidatedByThePassOpener() {
        var tick = 0L
        val advancing = {
            tick += 1
            LocalDateTime.of(2026, 8, 6, 0, 0, 0).plusDays(tick)
        }
        var workbook = CalculationWorkbook()
        val sheet = workbook.activeSheet()
        workbook = input(workbook, sheet.id, 0, 0, "5")
        workbook = input(workbook, sheet.id, 1, 0, "=A1*2")
        workbook = input(workbook, sheet.id, 2, 0, "=TODAY()")
        val engine = CalculationEngine(workbook, clock = advancing)
        // Warm both caches first, so the delta below measures re-computation rather than first computation.
        engine.valueAt(sheet.id, 1, 0)
        engine.valueAt(sheet.id, 2, 0)
        val before = engine.instrumentation().formulasRecalculated
        engine.replaceWorkbook(engine.workbook(), changedCells = emptySet())
        engine.valueAt(sheet.id, 1, 0)
        engine.valueAt(sheet.id, 2, 0)
        // Exactly one formula — the volatile one — recomputes. `=A1*2` keeps its cached value.
        assertEquals(1, engine.instrumentation().formulasRecalculated - before)
    }

    // ---- Lookup and reference -----------------------------------------------------------------------

    @Test fun vlookup_findsByFirstColumnAndReturnsTheRequestedOffset() {
        val table = listOf(
            Triple(0, 0, "alpha"), Triple(0, 1, "1"), Triple(0, 2, "one"),
            Triple(1, 0, "beta"), Triple(1, 1, "2"), Triple(1, 2, "two"),
            Triple(2, 0, "gamma"), Triple(2, 1, "3"), Triple(2, 2, "three"),
        )
        assertEquals("2", display("=VLOOKUP(\"beta\",A1:C3,2)", table))
        assertEquals("three", display("=VLOOKUP(\"gamma\",A1:C3,3,FALSE)", table))
        assertEquals(CalculationErrorKind.UNAVAILABLE, errorKind("=VLOOKUP(\"delta\",A1:C3,2)", table))
        assertEquals(CalculationErrorKind.REFERENCE, errorKind("=VLOOKUP(\"beta\",A1:C3,9)", table))
        assertEquals(CalculationErrorKind.VALUE, errorKind("=VLOOKUP(\"beta\",A1:C3,0)", table))
        assertEquals(CalculationErrorKind.VALUE, errorKind("=VLOOKUP(\"beta\",A1:C3)", table))
    }

    @Test fun vlookup_refusesApproximateMatchRatherThanGuessing() {
        val table = listOf(Triple(0, 0, "alpha"), Triple(0, 1, "1"), Triple(1, 0, "beta"), Triple(1, 1, "2"))
        // Approximate match needs a sorted range; returning a neighbour when that is untrue is worse than
        // saying the mode is unsupported.
        assertEquals(CalculationErrorKind.VALUE, errorKind("=VLOOKUP(\"beta\",A1:B2,2,TRUE)", table))
    }

    @Test fun hlookup_searchesTheFirstRowInstead() {
        val table = listOf(
            Triple(0, 0, "alpha"), Triple(1, 0, "1"), Triple(2, 0, "one"),
            Triple(0, 1, "beta"), Triple(1, 1, "2"), Triple(2, 1, "two"),
        )
        assertEquals("2", display("=HLOOKUP(\"beta\",A1:B3,2)", table))
        assertEquals("one", display("=HLOOKUP(\"alpha\",A1:B3,3)", table))
        assertEquals(CalculationErrorKind.UNAVAILABLE, errorKind("=HLOOKUP(\"gamma\",A1:B3,2)", table))
    }

    @Test fun row_andColumn_reportOneBasedPositions() {
        // No argument means the calling cell, which `evaluate` places at row 30, column 12 (M31).
        assertEquals("31", display("=ROW()"))
        assertEquals("13", display("=COLUMN()"))
        assertEquals("5", display("=ROW(A5)"))
        assertEquals("3", display("=COLUMN(C1)"))
        assertEquals("2", display("=ROW(B2:D4)"))
        assertEquals(CalculationErrorKind.VALUE, errorKind("=ROW(A1,B1)"))
    }

    @Test fun rows_andColumns_measureShapeWithoutReadingValues() {
        assertEquals("3", display("=ROWS(A1:C3)"))
        assertEquals("3", display("=COLUMNS(A1:C3)"))
        assertEquals("1", display("=ROWS(A1)"))
        assertEquals("5", display("=ROWS(A1:B5)"))
        assertEquals("2", display("=COLUMNS(A1:B5)"))
        assertEquals(CalculationErrorKind.VALUE, errorKind("=ROWS()"))
    }

    @Test fun rows_measuresARangeFarLargerThanTheEvaluationBudget() {
        // 100 rows x 26 columns is well inside the sheet but the point is that no cell is read: a shape
        // question must not cost a value pass.
        assertEquals("100", display("=ROWS(A1:Z100)"))
        assertEquals("26", display("=COLUMNS(A1:Z100)"))
    }

    // ---- Cross-cutting -------------------------------------------------------------------------------

    @Test fun everyNewFunctionIsReachableThroughTheRegistry() {
        // A name the registry does not know returns #NAME?. Each of these resolving to anything else proves
        // the dispatch branch exists.
        val calls = listOf(
            "=SIGN(1)", "=INT(1)", "=MOD(4,2)", "=POWER(2,2)", "=SQRT(4)", "=PRODUCT(2,2)",
            "=IFS(TRUE,1)", "=IFNA(1,2)",
            "=SUMIFS(A1:A2,A1:A2,\">0\")", "=COUNTIFS(A1:A2,\">0\")",
            "=AVERAGEIF(A1:A2,\">0\")", "=AVERAGEIFS(A1:A2,A1:A2,\">0\")",
            "=CONCATENATE(\"a\")", "=TEXTJOIN(\",\",TRUE,\"a\")", "=FIND(\"a\",\"a\")", "=SEARCH(\"a\",\"a\")",
            "=SUBSTITUTE(\"a\",\"a\",\"b\")", "=VALUE(\"1\")",
            "=DATEVALUE(\"2026-01-01\")", "=TIME(1,1,1)", "=HOUR(TIME(1,1,1))", "=MINUTE(TIME(1,1,1))",
            "=SECOND(TIME(1,1,1))", "=TODAY()", "=NOW()",
            "=VLOOKUP(1,A1:B2,2)", "=HLOOKUP(1,A1:B2,2)", "=ROW()", "=COLUMN()", "=ROWS(A1)", "=COLUMNS(A1)",
        )
        val setup = listOf(Triple(0, 0, "1"), Triple(1, 0, "2"), Triple(0, 1, "9"), Triple(1, 1, "8"))
        calls.forEach { formula ->
            val kind = (evaluate(formula, setup) as? CalculatedValue.ErrorValue)?.error?.kind
            assertNotEquals("$formula reached the unknown-function fallthrough", CalculationErrorKind.NAME, kind)
        }
    }

    @Test fun anUnknownFunctionStillReturnsTheCanonicalNameError() {
        assertEquals(CalculationErrorKind.NAME, errorKind("=NOTAFUNCTION(1)"))
        assertEquals(CalculationErrorKind.NAME, errorKind("=WEBSERVICE(\"https://example.com\")"))
    }

    @Test fun theNewAggregatesHonourTheRangeResourceLimit() {
        val limits = CalculationLimits(maximumRangeCells = 4)
        var workbook = CalculationWorkbook()
        val sheet = workbook.activeSheet()
        workbook = input(workbook, sheet.id, 0, 0, "=PRODUCT(A1:A9)")
        val value = CalculationEngine(workbook, limits).valueAt(sheet.id, 0, 0)
        // A refused range must surface as #LIMIT!, never as a plausible product of the cells that fit.
        assertEquals(CalculationErrorKind.RESOURCE_LIMIT, (value as CalculatedValue.ErrorValue).error.kind)
    }

    @Test fun theNewFunctionsPropagateAnErrorArgumentRatherThanAbsorbingIt() {
        assertEquals(CalculationErrorKind.DIVIDE_BY_ZERO, errorKind("=SIGN(1/0)"))
        assertEquals(CalculationErrorKind.DIVIDE_BY_ZERO, errorKind("=INT(1/0)"))
        assertEquals(CalculationErrorKind.DIVIDE_BY_ZERO, errorKind("=TEXTJOIN(\",\",TRUE,1/0)"))
        assertEquals(CalculationErrorKind.DIVIDE_BY_ZERO, errorKind("=VALUE(1/0)"))
        assertEquals(CalculationErrorKind.DIVIDE_BY_ZERO, errorKind("=DATEVALUE(1/0)"))
    }

    @Test fun dateAndTimeValuesSurviveADurableRoundTrip() {
        var workbook = CalculationWorkbook()
        val sheet = workbook.activeSheet()
        workbook = input(workbook, sheet.id, 0, 0, "=DATEVALUE(\"2026-08-06\")")
        workbook = input(workbook, sheet.id, 1, 0, "=TIME(9,30,0)")
        val encoded = CalculationWorkbookJson.encode(workbook)
        val decoded = CalculationWorkbookJson.decode(encoded)
        val engine = CalculationEngine(decoded)
        assertEquals(LocalDate.of(2026, 8, 6), (engine.valueAt(sheet.id, 0, 0) as CalculatedValue.DateValue).value)
        assertEquals(9, (engine.valueAt(sheet.id, 1, 0) as CalculatedValue.DateTimeValue).value.hour)
    }
}
