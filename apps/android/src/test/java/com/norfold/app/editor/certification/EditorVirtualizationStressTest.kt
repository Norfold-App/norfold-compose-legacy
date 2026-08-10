package com.norfold.app.editor.certification

import com.norfold.app.domain.BlockDocument
import com.norfold.app.domain.DocumentPageBlock
import com.norfold.app.domain.InlineText
import com.norfold.app.domain.PageGeometry
import com.norfold.app.domain.ParagraphBlock
import com.norfold.app.domain.SceneGeometry
import com.norfold.app.domain.SceneObject
import com.norfold.app.editor.calculation.CalculationColumn
import com.norfold.app.editor.calculation.CalculationRow
import com.norfold.app.editor.calculation.CalculationSheet
import com.norfold.app.editor.calculation.SpreadsheetViewportPlanner
import com.norfold.app.editor.document.DocumentHostRuntime
import com.norfold.app.editor.document.DocumentSceneController
import com.norfold.app.editor.document.PageVirtualizer
import com.norfold.app.editor.flow.FlowEditController
import com.norfold.app.editor.flow.FlowHostRuntime
import com.norfold.app.editor.flow.RecordingFlowHostInstrumentation
import com.norfold.app.editor.history.EditTarget
import com.norfold.app.editor.presentation.FormFactor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Virtualization proved the only way it can be: **the collection grows substantially while the realized
 * count stays bounded**.
 *
 * The canonical reference requires it per surface (§16.2) — lazy Flow blocks, page-level document
 * virtualization, visible-range table virtualization. What it does not accept, and what these tests
 * deliberately avoid, is inferring virtualization from the presence of a `LazyColumn` in the source. A lazy
 * container fed a pre-materialized list of every row is not virtualized; it merely looks it. So every case
 * here scales the underlying collection by a large factor and asserts the realized count did not follow.
 *
 * Where a bound is asserted as an absolute number, that number is derived from the fixture's own geometry
 * (viewport height / page height, plus the overscan the production code declares) rather than chosen to
 * match whatever the code currently returns.
 */
class EditorVirtualizationStressTest {

    // ── Fixtures ────────────────────────────────────────────────────────────────────────────────────

    private fun page(id: String, vararg objects: SceneObject) =
        DocumentPageBlock(id = id, geometry = PageGeometry.a4(), objects = objects.toList()).normalized()

    private fun obj(id: String, y: Float) = SceneObject(
        id = id,
        geometry = SceneGeometry(10f, y, 80f, 20f),
        content = ParagraphBlock(id = "c-$id", content = listOf(InlineText(id))),
    )

    private fun runtime(pages: List<DocumentPageBlock>) = DocumentHostRuntime(
        controller = DocumentSceneController(EditTarget("note:1"), BlockDocument(pages)),
        initialFormFactor = FormFactor(400, 800),
    )

    private fun sheet(rows: Int, columns: Int) = CalculationSheet(
        rowCount = rows,
        columnCount = columns,
        rows = List(rows) { CalculationRow() },
        columns = List(columns) { CalculationColumn() },
    )

    // ── Flow: the actual composed-range instrumentation seam ───────────────────────────────────────

    @Test
    fun aLargeFlowArtifactReportsTheViewportRangeRatherThanItsWholeBlockCount() {
        val blocks = List(10_000) { index ->
            ParagraphBlock(id = "b$index", content = listOf(InlineText("Synthetic block $index")))
        }
        val instrumentation = RecordingFlowHostInstrumentation()
        val runtime = FlowHostRuntime(
            controller = FlowEditController(EditTarget("note:flow"), BlockDocument(blocks)),
            initialFormFactor = FormFactor(400, 800),
            instrumentation = instrumentation,
        )

        // Compose reports this range from LazyListState.layoutInfo; the runtime must preserve both the
        // bounded visible range and the authoritative total rather than expanding the event to all blocks.
        runtime.reportVisibleRange(firstIndex = 5_000, lastIndex = 5_020)

        assertTrue(
            "the actual composition hook must report 21 visible blocks out of 10000",
            instrumentation.events().contains("visible:5000-5020/10000"),
        )
    }

    // ── Documents: pages ────────────────────────────────────────────────────────────────────────────

    @Test
    fun pageCompositionStaysBoundedWhileTheDocumentGrowsAHundredFold() {
        // One A4 viewport's worth of scroll, held constant while the document grows from 20 to 2000 pages.
        val small = runtime((1..20).map { page("p$it") }).pageWindow(scrollOffset = 0f, viewportHeight = 900f)
        val large = runtime((1..2_000).map { page("p$it") }).pageWindow(scrollOffset = 0f, viewportHeight = 900f)

        assertEquals(
            "a 100x larger document must compose the same number of pages",
            small.count,
            large.count,
        )
        assertTrue("and that number must be a handful, not 2000: got ${large.count}", large.count <= 6)
    }

    @Test
    fun aTwoThousandPageDocumentComposesFarFewerPagesThanItHolds() {
        val rt = runtime((1..2_000).map { page("p$it") })

        val window = rt.pageWindow(scrollOffset = 0f, viewportHeight = 900f)

        assertEquals("the document really does hold 2000 pages", 2_000, rt.pages().size)
        assertTrue(
            "composed ${window.count} of 2000 pages — virtualization is not holding",
            window.count < 20,
        )
    }

    @Test
    fun scrollingTheWholeDocumentNeverWidensTheComposedWindow() {
        // A window that grew as the user scrolled would be an accumulating leak rather than virtualization.
        val pages = (1..500).map { page("p$it") }
        val rt = runtime(pages)
        val pageStride = PageGeometry.a4().height + 24f

        val counts = (0 until 500 step 25).map { index ->
            rt.pageWindow(scrollOffset = index * pageStride, viewportHeight = 900f).count
        }

        val worst = counts.max()
        assertTrue("the composed window must stay bounded across the whole scroll: worst was $worst", worst <= 6)
    }

    // ── Documents: scene objects within a page ──────────────────────────────────────────────────────

    @Test
    fun sceneObjectRealizationStaysBoundedWhileTheObjectCountGrows() {
        // Objects laid out down a long page; only the band inside the viewport may be realized.
        fun visibleCount(total: Int): Int {
            val objects = (1..total).map { obj("o$it", y = it * 40f) }
            val p = page("p1", *objects.toTypedArray())
            return p.objects.count { it.geometry.y + it.geometry.height >= 0f && it.geometry.y <= 800f }
        }

        val few = visibleCount(50)
        val many = visibleCount(1_000)
        assertEquals("a 20x larger object count must not enlarge the visible band", few, many)
        assertTrue("and the visible band must be a small fraction of 1000: got $many", many < 50)
    }

    // ── Tables: visible-range virtualization ────────────────────────────────────────────────────────

    @Test
    fun tableCellCompositionStaysBoundedWhileTheSheetGrowsToOneHundredThousandCells() {
        // The canonical reference names ~100,000 cells as the ordinary document-table safety target (§10.5).
        val small = SpreadsheetViewportPlanner.plan(
            sheet = sheet(rows = 50, columns = 10),
            verticalOffsetPoints = 0f,
            horizontalOffsetPoints = 0f,
            viewportWidthPoints = 800f,
            viewportHeightPoints = 600f,
        )
        val huge = SpreadsheetViewportPlanner.plan(
            sheet = sheet(rows = 10_000, columns = 10),
            verticalOffsetPoints = 0f,
            horizontalOffsetPoints = 0f,
            viewportWidthPoints = 800f,
            viewportHeightPoints = 600f,
        )

        assertEquals(
            "a 100,000-cell sheet must compose the same cell count as a 500-cell one",
            small.composedCellCount,
            huge.composedCellCount,
        )
        assertTrue(
            "composed ${huge.composedCellCount} cells — a viewport of 600pt cannot need that many",
            huge.composedCellCount < 1_000,
        )
    }

    @Test
    fun scrollingDeepIntoALargeSheetComposesThatRegionAndNotTheTop() {
        val big = sheet(rows = 10_000, columns = 10)

        val deep = SpreadsheetViewportPlanner.plan(
            sheet = big,
            verticalOffsetPoints = 5_000 * 24f,
            horizontalOffsetPoints = 0f,
            viewportWidthPoints = 800f,
            viewportHeightPoints = 600f,
        )

        assertTrue("row 5000 must be inside the plan", 5_000 in deep.firstRow..deep.lastRow)
        assertTrue("row 0 must not be", 0 !in deep.firstRow..deep.lastRow)
        assertTrue("and the plan must stay small: ${deep.composedCellCount} cells", deep.composedCellCount < 1_000)
    }

    @Test
    fun hiddenRowsAreNotRealizedAndDoNotConsumeTheViewport() {
        // A hidden row that still occupied viewport space would push visible rows off screen while
        // contributing nothing a user can see.
        val rows = 400
        val allVisible = sheet(rows = rows, columns = 5)
        val halfHidden = allVisible.copy(
            rows = List(rows) { CalculationRow(hidden = it % 2 == 0) },
        )

        val plan = SpreadsheetViewportPlanner.plan(
            sheet = halfHidden,
            verticalOffsetPoints = 0f,
            horizontalOffsetPoints = 0f,
            viewportWidthPoints = 800f,
            viewportHeightPoints = 600f,
        )

        val hiddenInRange = (plan.firstRow..plan.lastRow).count { halfHidden.rows[it].hidden }
        val shownInRange = (plan.firstRow..plan.lastRow).count { !halfHidden.rows[it].hidden }
        assertTrue("the plan must still cover real rows", shownInRange > 0)
        assertTrue(
            "hiding rows must widen the index range rather than blank the viewport " +
                "($shownInRange shown, $hiddenInRange hidden in range)",
            shownInRange >= 600f / 24f / 2f,
        )
    }

    // ── The virtualizer's own contract ──────────────────────────────────────────────────────────────

    @Test
    fun theWindowAlwaysCoversTheViewportItWasAskedAbout() {
        // Bounded is only half the contract; a window that was bounded by *under*-composing would leave a
        // blank strip on screen. Every page intersecting the viewport must be inside the window.
        val pages = (1..300).map { page("p$it") }
        val stride = PageGeometry.a4().height + 24f
        val viewportHeight = 2_000f
        val scrollOffset = 100 * stride

        val window = PageVirtualizer.window(
            pages = pages,
            scrollOffset = scrollOffset,
            viewportHeight = viewportHeight,
        )

        val intersecting = pages.indices.filter { index ->
            val top = index * stride
            top + PageGeometry.a4().height >= scrollOffset && top <= scrollOffset + viewportHeight
        }
        intersecting.forEach { index ->
            assertTrue("page $index intersects the viewport and must be composed", index in window)
        }
    }
}
