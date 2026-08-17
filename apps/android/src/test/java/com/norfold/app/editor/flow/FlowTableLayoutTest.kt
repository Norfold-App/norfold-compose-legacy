package com.norfold.app.editor.flow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The table column-width contract, asserted on the desktop JVM.
 *
 * The defect being fenced here is not a crash — it is a table that renders. Every cell was `weight(1f)` in a
 * `fillMaxWidth()` row, so a six-column table on a phone drew six 50 dp columns of ellipses and
 * `columnWidthsDp` was never read at all. Nothing about that looks like a failure in a screenshot review; it
 * looks like a narrow table. So the rule that replaces it is pinned as arithmetic instead.
 */
class FlowTableLayoutTest {

    /** A phone's content measure inside the block card, roughly. The number most of these claims are about. */
    private val phone = 340f

    /** A large tablet, for the claims about a table with room to spare. */
    private val tablet = 1100f

    private val minimum = FlowTableLayout.FALLBACK_MINIMUM_DP

    private fun assertClose(expected: Float, actual: Float, message: String = "") {
        assertEquals(message, expected.toDouble(), actual.toDouble(), 0.01)
    }

    private fun resolve(
        stored: List<Float>,
        columns: Int,
        available: Float,
        floor: Float = minimum,
    ) = FlowTableLayout.resolve(stored, columns, floor, available)

    // ── The no-regression property ───────────────────────────────────────────────────────────────────

    /**
     * The test that justifies shipping this at all. A two- or three-column table is what almost every existing
     * note contains, and under the old `weight(1f)` layout it filled the card and did not scroll. If this rule
     * changes that, the fix has broken the common case to repair the rare one.
     */
    @Test
    fun theCommonPhoneTable_stillFillsTheCard_andStillDoesNotScroll() {
        for (columns in 1..3) {
            val resolved = resolve(emptyList(), columns, phone)
            assertClose(phone, resolved.totalDp, "$columns columns must fill the card exactly")
            assertFalse("$columns columns must not scroll on a phone", resolved.scrolls)
            assertEquals(columns, resolved.columnsDp.size)
        }
    }

    /**
     * Equal authored widths stay equal. `weight(1f)` got this one case right, so the replacement has to as well
     * — a uniform table drawn with columns of visibly different widths would be a new defect.
     */
    @Test
    fun equalAuthoredWidths_resolveEqual() {
        val resolved = resolve(listOf(120f, 120f, 120f), 3, phone)
        assertEquals(1, resolved.columnsDp.distinct().size)
    }

    // ── Fitting ──────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun aNarrowTable_isScaledUpToFill_ratherThanLeavingADeadStrip() {
        // Two 120 dp columns is 240 dp of grid in a 340 dp card: a full-width card with 100 dp of blank paper
        // along its right edge reads as a rendering fault, not as a narrow table.
        val resolved = resolve(listOf(120f, 120f), 2, phone)
        assertClose(phone, resolved.totalDp)
        assertFalse(resolved.scrolls)
    }

    @Test
    fun aWideTableThatCanBeSqueezed_fitsInsteadOfScrolling() {
        // 3 x 200 dp = 600 dp into 340 dp. Scaling to 0.567 leaves 113 dp columns, still above the floor, so
        // this table has no business making the reader scroll.
        val resolved = resolve(listOf(200f, 200f, 200f), 3, phone)
        assertClose(phone, resolved.totalDp)
        assertFalse("a table that fits must not scroll", resolved.scrolls)
        assertTrue(resolved.columnsDp.all { it >= minimum })
    }

    @Test
    fun aTableThatCannotFit_scrolls_ratherThanCollapsingToStubs() {
        // Ten columns cannot fit 340 dp above a 72 dp floor. The old layout drew ten 34 dp stubs; this must
        // overflow instead, and every column must still be readable.
        val resolved = resolve(emptyList(), 10, phone)
        assertTrue("ten columns cannot fit a phone", resolved.scrolls)
        assertTrue("and no column may go under the floor", resolved.columnsDp.all { it >= minimum })
        assertClose(minimum * 10, resolved.totalDp, "squeezed exactly to the floor, no further")
    }

    /**
     * The floor is the whole point of the rule, so it is asserted across the range rather than at one width: no
     * combination of column count and available space may produce a column too narrow to read.
     */
    @Test
    fun noColumnEverGoesUnderTheFloor() {
        for (columns in 1..24) {
            for (available in listOf(0f, 1f, 120f, phone, 600f, tablet, 4000f)) {
                val resolved = resolve(emptyList(), columns, available)
                assertTrue(
                    "$columns columns in $available dp produced ${resolved.columnsDp.minOrNull()}",
                    resolved.columnsDp.all { it >= minimum - 0.01f },
                )
            }
        }
    }

    /**
     * A table that cannot fit is still squeezed as far as it legitimately can be, so the reader scrolls past the
     * overflow rather than past the entire grid. The tempting simpler rule — "if it doesn't fit, use the authored
     * widths" — would make a 6 x 200 dp table 1200 dp wide when 432 dp would have done.
     */
    @Test
    fun anOverflowingTable_isSqueezedToTheFloor_notLeftAtItsAuthoredWidth() {
        val authoredTotal = 6 * 200f
        val resolved = resolve(List(6) { 200f }, 6, phone)
        assertTrue(resolved.scrolls)
        assertTrue(
            "squeezing must actually reduce the scroll distance",
            resolved.totalDp < authoredTotal,
        )
        assertClose(minimum * 6, resolved.totalDp)
    }

    // ── The authored ratio ───────────────────────────────────────────────────────────────────────────

    /**
     * Why this is not `weight(1f)`. A weight discards the ratio: a 300/120 pair comes back as 210/210 and the
     * column the user widened stops being the wide one — a resize that survives on one screen and silently does
     * not on another.
     */
    @Test
    fun theAuthoredRatio_survivesFittingInBothDirections() {
        for (available in listOf(phone, 600f, tablet)) {
            val resolved = resolve(listOf(300f, 120f), 2, available)
            val (wide, narrow) = resolved.columnsDp
            assertClose(
                300f / 120f,
                wide / narrow,
                "the 2.5:1 ratio must survive at $available dp",
            )
        }
    }

    @Test
    fun aWiderAuthoredColumn_isAlwaysDrawnWider() {
        val resolved = resolve(listOf(80f, 400f, 150f), 3, phone)
        assertTrue(resolved.columnsDp[1] > resolved.columnsDp[2])
        assertTrue(resolved.columnsDp[2] > resolved.columnsDp[0])
    }

    /**
     * The documented cost of one shared factor, pinned so it is a decision rather than a surprise: a table whose
     * narrowest column already sits at the floor cannot be squeezed, so it scrolls even though shrinking the wide
     * column alone would have fitted it.
     */
    @Test
    fun aTableAlreadyAtTheFloor_scrollsRatherThanBreakingTheRatio() {
        val resolved = resolve(listOf(300f, minimum), 2, phone)
        assertTrue(resolved.scrolls)
        assertClose(300f, resolved.columnsDp[0], "the authored widths are left exactly as authored")
        assertClose(minimum, resolved.columnsDp[1])
    }

    // ── Stored widths are real data ──────────────────────────────────────────────────────────────────

    /**
     * `columnWidthsDp` is written by `TableCalculationBridge.persist()` from the workbook's own column points and
     * maintained by [FlowTableOps.insertColumn] — and was read by nothing. This is the assertion that it reaches
     * the screen at all.
     */
    @Test
    fun storedWidths_actuallyDecideTheColumns() {
        val uniform = resolve(emptyList(), 3, phone).columnsDp
        val authored = resolve(listOf(60f, 300f, 100f), 3, phone).columnsDp
        assertFalse("stored widths must change the result", uniform == authored)
    }

    /**
     * An empty widths list means "never resized", which [FlowTableOps.insertColumn] deliberately keeps empty. A
     * table in that state must draw exactly like one whose widths are all the default, or the same table would
     * change appearance the first time an unrelated column was inserted.
     */
    @Test
    fun anEmptyWidthsList_matchesAnExplicitDefaultList() {
        val unset = resolve(emptyList(), 4, phone).columnsDp
        val explicit = resolve(List(4) { FlowTableOps.DEFAULT_COLUMN_WIDTH_DP }, 4, phone).columnsDp
        assertEquals(unset.size, explicit.size)
        unset.zip(explicit).forEach { (a, b) -> assertClose(a, b) }
    }

    /** A widths list shorter than the table — a ragged import, a column added before widths were tracked. */
    @Test
    fun aShortWidthsList_fallsBackPerColumn_ratherThanBeingDiscarded() {
        val resolved = resolve(listOf(400f), 3, tablet)
        assertEquals(3, resolved.columnsDp.size)
        assertTrue("the one authored width is still the widest", resolved.columnsDp[0] > resolved.columnsDp[1])
        assertClose(resolved.columnsDp[1], resolved.columnsDp[2], "the two unset columns match each other")
    }

    // ── Garbage in ───────────────────────────────────────────────────────────────────────────────────

    /**
     * Stored widths arrive from Room, a backup file and an importer, so "who validated this" has three answers
     * and the safe one is "the function itself". A NaN column width must not become a NaN grid width — that is a
     * blank block on screen, and the block is the user's data.
     */
    @Test
    fun garbageStoredWidths_fallBackInsteadOfPoisoningTheGrid() {
        val resolved = resolve(listOf(Float.NaN, -50f, 0f, Float.POSITIVE_INFINITY), 4, phone)
        assertTrue("no column may be non-finite", resolved.columnsDp.all { it.isFinite() })
        assertTrue(resolved.columnsDp.all { it >= minimum })
        assertTrue(resolved.totalDp.isFinite())
        val clean = resolve(List(4) { FlowTableOps.DEFAULT_COLUMN_WIDTH_DP }, 4, phone)
        resolved.columnsDp.zip(clean.columnsDp).forEach { (a, b) ->
            assertClose(b, a, "every garbage width degrades to the ordinary default")
        }
    }

    @Test
    fun aGarbageFloor_degradesToTheOrdinaryOne() {
        for (floor in listOf(Float.NaN, 0f, -12f, Float.POSITIVE_INFINITY)) {
            val resolved = resolve(emptyList(), 10, phone, floor = floor)
            assertTrue("floor $floor produced a non-finite column", resolved.columnsDp.all { it.isFinite() })
            assertTrue(resolved.columnsDp.all { it >= minimum - 0.01f })
        }
    }

    @Test
    fun zeroColumns_isAnEmptyGrid_notACrash() {
        val resolved = resolve(listOf(120f), 0, phone)
        assertTrue(resolved.columnsDp.isEmpty())
        assertClose(0f, resolved.totalDp)
        assertFalse("an empty grid cannot scroll", resolved.scrolls)
    }

    @Test
    fun negativeColumnCount_isTreatedAsEmpty() {
        assertTrue(resolve(emptyList(), -3, phone).columnsDp.isEmpty())
    }

    // ── Unknown available width ──────────────────────────────────────────────────────────────────────

    /**
     * An unbounded measurement — a table nested in another horizontally scrolling container — must leave the
     * authored widths alone. The composable converts `BoxWithConstraints`'s infinite constraint into zero for
     * exactly this path, because that constraint arrives as a *finite* and enormous Dp and fitting against it
     * would scale the table up against half a million dp of imaginary page.
     */
    @Test
    fun anUnknownAvailableWidth_returnsTheAuthoredWidthsUntouched() {
        for (available in listOf(0f, -1f, Float.NaN, Float.POSITIVE_INFINITY)) {
            val resolved = resolve(listOf(300f, 120f), 2, available)
            assertClose(300f, resolved.columnsDp[0], "available=$available must not scale anything")
            assertClose(120f, resolved.columnsDp[1])
            assertFalse("and an unknown width cannot be known to overflow", resolved.scrolls)
        }
    }

    // ── Well-behavedness ─────────────────────────────────────────────────────────────────────────────

    /**
     * A wider screen must never produce a narrower grid. Stated as a property because the failure it guards is a
     * table that visibly shrinks when the device is rotated into landscape.
     */
    @Test
    fun theGrid_neverShrinksAsTheScreenGrows() {
        var previous = 0f
        for (step in 0..60) {
            val available = 200f + step * 40f
            val total = resolve(listOf(180f, 90f, 240f, 90f), 4, available).totalDp
            assertTrue("the grid shrank at $available dp", total >= previous - 0.01f)
            previous = total
        }
    }

    /** The total is the sum of the parts, at every size. A grid drawn wider than its columns has a seam. */
    @Test
    fun theTotal_isAlwaysTheSumOfTheColumns() {
        for (columns in 1..12) {
            val resolved = resolve(List(columns) { 100f + it * 30f }, columns, phone)
            assertClose(resolved.columnsDp.sum(), resolved.totalDp, "$columns columns")
        }
    }

    /** A grid that overruns its box by a rounding error is not a scrollable table. */
    @Test
    fun scrolls_ignoresSubPixelOverrun() {
        val resolved = FlowTableWidths(listOf(phone / 2f, phone / 2f + 0.2f), phone)
        assertFalse("0.2 dp of overrun is not a scroll", resolved.scrolls)
    }

    /**
     * At the top of the reading scale the floor is larger, so a table that fitted at 1.0x is expected to scroll.
     * The claim is that the *scale* is what moves it, not that any one table does or does not fit.
     */
    @Test
    fun aLargerReadingScale_movesTheFitBoundary_notTheFloorGuarantee() {
        val atOneX = resolve(emptyList(), 4, phone, floor = 72f)
        val atTwoX = resolve(emptyList(), 4, phone, floor = 146f)
        assertFalse("four columns fit a phone at 1.0x", atOneX.scrolls)
        assertTrue("and cannot at 2.0x", atTwoX.scrolls)
        assertTrue(atTwoX.columnsDp.all { it >= 146f - 0.01f })
    }
}
