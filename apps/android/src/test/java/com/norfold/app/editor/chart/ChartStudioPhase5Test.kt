package com.norfold.app.editor.chart

import com.norfold.app.domain.ChartBlock
import com.norfold.app.domain.BlockDocument
import com.norfold.app.domain.BlockDocumentJson
import com.norfold.app.domain.DocumentPageBlock
import com.norfold.app.domain.PageGeometry
import com.norfold.app.domain.ParagraphBlock
import com.norfold.app.domain.InlineText
import com.norfold.app.domain.SceneGeometry
import com.norfold.app.domain.SceneObject
import com.norfold.app.domain.SceneObjectKind
import com.norfold.app.domain.findById
import com.norfold.app.domain.TableBlock
import com.norfold.app.domain.TableCell
import com.norfold.app.editor.calculation.CalculatedValue
import com.norfold.app.editor.calculation.CalculationError
import com.norfold.app.editor.calculation.CalculationErrorKind
import com.norfold.app.editor.calculation.CalculationCell
import com.norfold.app.editor.calculation.CalculationRangeBinding
import com.norfold.app.editor.calculation.CalculationRangeRuntime
import com.norfold.app.editor.calculation.CalculationRangeSnapshot
import com.norfold.app.editor.calculation.CalculationSheet
import com.norfold.app.editor.calculation.CalculationWorkbook
import com.norfold.app.editor.calculation.CellInput
import com.norfold.app.editor.calculation.GridAddress
import com.norfold.app.editor.calculation.SpreadsheetHostRuntime
import com.norfold.app.editor.calculation.SpreadsheetWorkbookFactory
import com.norfold.app.editor.calculation.StableCellRange
import com.norfold.app.editor.calculation.TableCalculationBridge
import com.norfold.app.editor.calculation.TableUpgradeResult
import com.norfold.app.editor.command.CommandResult
import com.norfold.app.editor.command.CommandSurface
import com.norfold.app.editor.document.DocumentHostRuntime
import com.norfold.app.editor.document.DocumentIds
import com.norfold.app.editor.document.DocumentSceneController
import com.norfold.app.editor.flow.FlowEditController
import com.norfold.app.editor.flow.FlowHostRuntime
import com.norfold.app.editor.history.EditTarget
import com.norfold.app.editor.presentation.FormFactor
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChartStudioPhase5Test {
    @Test
    fun canonicalCodecIsDeterministicAndRoundTripsEverySection() {
        val document = ChartDocument(
            type = ChartType.DONUT,
            title = "Revenue",
            subtitle = "Quarterly",
            caption = "Offline source",
            labels = ChartLabels(showCategory = true, showValue = true),
            legend = ChartLegend(true, ChartLegendPosition.END),
            interaction = ChartInteraction(selectionEnabled = false, valueEditingEnabled = true),
        )
        val first = ChartDocumentCodec.encode(document)
        val decoded = assertIs<ChartDecodeResult.Success>(ChartDocumentCodec.decode(first)).document
        assertEquals(first, ChartDocumentCodec.encode(decoded))
        assertEquals(document.normalized(), decoded)
        assertEquals(document.id, decoded.id)
        val withUnknownField = first.replaceFirst("{", "{\"futureField\":true,")
        assertIs<ChartDecodeResult.Success>(ChartDocumentCodec.decode(withUnknownField))
    }

    @Test
    fun unknownAndMalformedCanonicalPayloadsRemainTheSolePreservedCopy() {
        val unknown = """{"schemaVersion":99,"document":{}}"""
        val block = ChartBlock(vegaLiteSpec = "legacy", chartDocument = unknown)
        val loaded = ChartBlockAdapter.load(block)
        assertEquals(LoadedChartDocument.Origin.UNSUPPORTED_CANONICAL, loaded.origin)
        assertEquals(unknown, loaded.preservedPayload)
        assertFalse(loaded.isEditable)
        assertIs<ChartDecodeResult.Malformed>(ChartDocumentCodec.decode("not-json"))
    }

    @Test
    fun legacyVegaMigratesOnlyOnExplicitSaveAndOriginalBytesRemain() {
        val legacy = """{"mark":"bar","data":{"values":[{"x":"A","y":4}]},"encoding":{"x":{"field":"x"},"y":{"field":"y"}}}"""
        val block = ChartBlock(vegaLiteSpec = legacy)
        val loaded = ChartBlockAdapter.load(block)
        assertEquals(LoadedChartDocument.Origin.LEGACY_VEGA, loaded.origin)
        assertEquals(legacy, block.vegaLiteSpec)
        assertNull(block.chartDocument)
        val saved = ChartBlockAdapter.save(block, loaded.document.copy(title = "Edited"))
        assertEquals(legacy, saved.vegaLiteSpec)
        assertNotNull(saved.chartDocument)
        assertEquals("Edited", assertIs<ChartDecodeResult.Success>(ChartDocumentCodec.decode(saved.chartDocument!!)).document.title)
    }

    @Test
    fun legacyMultiSeriesMigrationKeepsEachSeriesValuesIndependent() {
        val legacy = """{"mark":"bar","data":{"values":[{"x":"A","y":4,"series":"North"},{"x":"A","y":7,"series":"South"},{"x":"B","y":5,"series":"North"},{"x":"B","y":9,"series":"South"}]},"encoding":{"x":{"field":"x"},"y":{"field":"y"},"color":{"field":"series"}}}"""
        val loaded = ChartBlockAdapter.load(ChartBlock(vegaLiteSpec = legacy))
        val ready = assertIs<ChartDataResolution.Ready>(ChartDataResolver.resolve(loaded.document))
        assertEquals(listOf("North", "South"), ready.data.series.map { it.name })
        assertEquals(listOf(4.0, 5.0), ready.data.series[0].points.map { it.y })
        assertEquals(listOf(7.0, 9.0), ready.data.series[1].points.map { it.y })
    }

    @Test
    fun calculationRangeRuntimeBuildsOnceAndCachesIdenticalRange() {
        val workbook = workbook()
        val sheet = workbook.activeSheet()
        val binding = CalculationRangeBinding(
            workbook.id,
            StableCellRange(sheet.coordinateAt(0, 0)!!, sheet.coordinateAt(1, 1)!!),
        )
        val runtime = CalculationRangeRuntime()
        val first = runtime.read(workbook, binding)
        val second = runtime.read(workbook, binding)
        assertTrue(first === second)
        assertEquals(1, runtime.stats().engineBuilds)
        assertEquals(1, runtime.stats().rangeReads)
        assertEquals(4, runtime.stats().cellReads)

        val changed = workbook.copy(revision = 1)
        runtime.read(changed, binding)
        assertEquals(2, runtime.stats().engineBuilds)
        assertEquals(2, runtime.stats().rangeReads)
    }

    @Test
    fun calculationBindingIsStableAndBrokenBindingIsReportedWithoutMutation() {
        val workbook = workbook()
        val sheet = workbook.activeSheet()
        val binding = CalculationRangeBinding(workbook.id, StableCellRange(sheet.coordinateAt(0, 0)!!, sheet.coordinateAt(1, 1)!!))
        val document = ChartDocument(source = ChartDataSource.CalculationRange(binding))
        val broken = ChartDataResolver.resolve(document, ChartCalculationDataProvider { error("Sheet removed") })
        assertEquals(binding, assertIs<ChartDataResolution.BrokenBinding>(broken).binding)
        assertEquals(binding, (document.source as ChartDataSource.CalculationRange).binding)

        val renamedSheet = sheet.copy(name = "Renamed data")
        val renamedWorkbook = workbook.copy(sheets = listOf(renamedSheet), revision = 1)
        val renamed = CalculationRangeRuntime().read(renamedWorkbook, binding)
        assertEquals(binding, renamed.binding)
        assertEquals("10", (renamed.rows[1][1] as CalculatedValue.Number).value.toPlainString())
    }

    @Test
    fun calculationHeadersAndErrorsResolveToAnAccessibleTable() {
        val workbook = workbook()
        val sheet = workbook.activeSheet()
        val binding = CalculationRangeBinding(workbook.id, StableCellRange(sheet.coordinateAt(0, 0)!!, sheet.coordinateAt(2, 1)!!))
        val runtime = CalculationRangeRuntime()
        val resolution = ChartDataResolver.resolve(
            ChartDocument(source = ChartDataSource.CalculationRange(binding)),
            ChartCalculationDataProvider { runtime.read(workbook, it) },
        )
        val ready = assertIs<ChartDataResolution.Ready>(resolution)
        assertEquals(listOf("Label", "Amount"), ChartAccessibility.table(ready.data).headers)
        assertEquals(2, ready.data.table.rows.size)
        assertTrue(ChartAccessibility.description(ChartDocument(), ready.data).contains("data rows"))

        val withError = ChartDataResolver.resolve(
            ChartDocument(source = ChartDataSource.CalculationRange(binding, headerMode = ChartHeaderMode.FIRST_ROW)),
            ChartCalculationDataProvider {
                CalculationRangeSnapshot(
                    it,
                    listOf(
                        listOf(CalculatedValue.Text("Label"), CalculatedValue.Text("Amount")),
                        listOf(CalculatedValue.Text("A"), CalculatedValue.ErrorValue(CalculationError(CalculationErrorKind.DIVIDE_BY_ZERO))),
                    ),
                    0,
                )
            },
        )
        val errorReady = assertIs<ChartDataResolution.Ready>(withError)
        assertTrue(errorReady.data.warnings.any { it.contains("contains an error") })
        assertEquals("#DIV/0!", ChartAccessibility.table(errorReady.data).rows.single()[1])
    }

    @Test
    fun everyRequiredChartTypeLaysOutEmptySingleNegativeAndExtremeDataSafely() {
        val cases = listOf(
            emptyList(),
            listOf("Only" to "1"),
            listOf("Loss" to "-10", "Gain" to "4"),
            listOf("Min" to "-1e308", "Max" to "1e308"),
        )
        ChartType.entries.forEach { type ->
            cases.forEach { values ->
                val document = manual(values).copy(type = type)
                val ready = assertIs<ChartDataResolution.Ready>(ChartDataResolver.resolve(document))
                val layout = ChartLayoutEngine.layout(document, ready.data, 480f, 280f)
                assertTrue("$type produced non-finite geometry for $values", layout.marks.all { mark ->
                    when (mark) {
                        is ChartMark.Bar -> listOf(mark.bounds.left, mark.bounds.top, mark.bounds.right, mark.bounds.bottom).all(Float::isFinite)
                        is ChartMark.Point -> mark.center.x.isFinite() && mark.center.y.isFinite()
                        is ChartMark.Line -> mark.points.all { it.x.isFinite() && it.y.isFinite() }
                        is ChartMark.Arc -> mark.startDegrees.isFinite() && mark.sweepDegrees.isFinite()
                    }
                })
            }
        }
    }

    @Test
    fun samplingChangesPresentationOnlyAndKeepsEndpoints() {
        val values = (0 until 10_000).map { "R$it" to it.toString() }
        val document = manual(values).copy(type = ChartType.LINE)
        val ready = assertIs<ChartDataResolution.Ready>(ChartDataResolver.resolve(document))
        val layout = ChartLayoutEngine.layout(document, ready.data, 600f, 320f, maxRenderedPoints = 100)
        assertEquals(10_000, layout.sourcePointCount)
        assertEquals(100, layout.sampledPointCount)
        assertEquals(10_000, (document.source as ChartDataSource.Manual).rows.size)
        assertTrue(layout.warnings.any { it.contains("Showing 100 of 10000") })
    }

    @Test
    fun renderingBudgetIsGlobalAcrossSeriesAndEqualExtremeValuesStayFinite() {
        val columns = listOf(
            ChartColumn("category", "Category", ChartColumnKind.TEXT),
            ChartColumn("a", "A", ChartColumnKind.NUMBER),
            ChartColumn("b", "B", ChartColumnKind.NUMBER),
        )
        val rows = (0 until 500).map { ChartDataRow(values = listOf("R$it", it.toString(), (it * 2).toString())) }
        val document = ChartDocument(
            type = ChartType.LINE,
            source = ChartDataSource.Manual(columns, rows),
            series = listOf(
                ChartSeries("a-series", "A", valueColumnId = "a"),
                ChartSeries("b-series", "B", valueColumnId = "b"),
            ),
        )
        val ready = assertIs<ChartDataResolution.Ready>(ChartDataResolver.resolve(document))
        val bounded = ChartLayoutEngine.layout(document, ready.data, 480f, 280f, maxRenderedPoints = 100)
        assertEquals(1_000, bounded.sourcePointCount)
        assertEquals(100, bounded.sampledPointCount)

        val extreme = manual(listOf("Only" to Double.MAX_VALUE.toString()))
        val extremeReady = assertIs<ChartDataResolution.Ready>(ChartDataResolver.resolve(extreme))
        val extremeLayout = ChartLayoutEngine.layout(extreme, extremeReady.data, 480f, 280f)
        assertTrue(extremeLayout.marks.all { mark ->
            mark !is ChartMark.Bar || listOf(mark.bounds.left, mark.bounds.top, mark.bounds.right, mark.bounds.bottom).all(Float::isFinite)
        })
    }

    @Test
    fun directValueEditingIsPreciseAndRefusesRangeSourcesOrInvalidNumbers() {
        val document = manual(listOf("A" to "2"))
        val updated = ChartDirectEditor.updateValue(document, "series-1", 0, "2.75")
        assertEquals("2.75", ((updated!!.source as ChartDataSource.Manual).rows.first().values[1]))
        assertNull(ChartDirectEditor.updateValue(document, "series-1", 0, "NaN"))
        val workbook = workbook()
        val sheet = workbook.activeSheet()
        val range = StableCellRange(sheet.coordinateAt(0, 0)!!, sheet.coordinateAt(1, 1)!!)
        assertNull(ChartDirectEditor.updateValue(document.copy(source = ChartDataSource.CalculationRange(CalculationRangeBinding(workbook.id, range))), "series-1", 0, "3"))
    }

    @Test
    fun oneGestureCommitsOnceCancelCommitsNothingAndReadModeRefusesCommands() {
        val commits = AtomicInteger()
        val runtime = ChartStudioRuntime(manual(listOf("A" to "2")), ChartCommitHost { _, _ -> commits.incrementAndGet(); true })
        assertTrue(runtime.beginGesture())
        assertTrue(runtime.previewGesture { ChartDirectEditor.updateValue(it, "series-1", 0, "3")!! })
        assertTrue(runtime.previewGesture { ChartDirectEditor.updateValue(it, "series-1", 0, "4")!! })
        assertEquals(0, commits.get())
        assertTrue(runtime.endGesture("Resize mark"))
        assertEquals(1, commits.get())
        assertTrue(runtime.beginGesture())
        assertTrue(runtime.previewGesture { it.copy(title = "Preview") })
        assertTrue(runtime.cancelGesture())
        assertEquals(1, commits.get())

        val readOnly = ChartStudioRuntime(ChartDocument(), ChartCommitHost { _, _ -> true }, readOnly = true)
        assertIs<CommandResult.Failure>(readOnly.invoke("chart.type.line"))
        assertFalse(readOnly.beginGesture())
    }

    @Test
    fun chartCommandsCoverSeriesLifecycleAndStyleReset() {
        val commits = mutableListOf<ChartDocument>()
        val document = ChartDocument(
            source = ChartDataSource.Manual(
                columns = listOf(
                    ChartColumn("category", "Category", ChartColumnKind.TEXT),
                    ChartColumn("a", "A", ChartColumnKind.NUMBER),
                    ChartColumn("b", "B", ChartColumnKind.NUMBER),
                ),
                rows = listOf(ChartDataRow(values = listOf("Q1", "2", "3"))),
            ),
            series = listOf(ChartSeries(id = "a-series", valueColumnId = "a")),
        )
        val runtime = ChartStudioRuntime(document, ChartCommitHost { _, next -> commits += next; true })
        assertIs<CommandResult.Success>(runtime.invoke("chart.series.add"))
        assertEquals(2, runtime.document().series.size)
        assertIs<CommandResult.Success>(runtime.invoke("chart.series.remove"))
        assertEquals(1, runtime.document().series.size)
        runtime.commit("Custom style") { it.copy(style = it.style.copy(opacity = .5f)) }
        assertIs<CommandResult.Success>(runtime.invoke("chart.style.reset"))
        assertEquals(ChartStyle(), runtime.document().style)
    }

    @Test
    fun flowAndDocumentInsertRoutesExistOnlyWhenChartFlagIsEnabled() {
        fun flow(enabled: Boolean) = FlowHostRuntime(
            FlowEditController(EditTarget("chart-flow"), BlockDocument(listOf(ParagraphBlock()))),
            FormFactor(400, 800),
            chartStudioEnabled = enabled,
        )
        assertNull(flow(false).registry.get("flow.insert.chart"))
        val enabledFlow = flow(true)
        assertIs<CommandResult.Success>(enabledFlow.invoke("flow.insert.chart", CommandSurface.DOCK))
        assertTrue(enabledFlow.controller.document().blocks.any { it is ChartBlock && it.chartDocument != null })

        fun document(enabled: Boolean): DocumentHostRuntime {
            val page = DocumentPageBlock(id = "page", geometry = PageGeometry.a4()).normalized()
            return DocumentHostRuntime(
                DocumentSceneController(EditTarget("chart-document"), BlockDocument(listOf(page))),
                FormFactor(400, 800),
                chartStudioEnabled = enabled,
            ).also { it.scope.activePageId = page.id }
        }
        assertNull(document(false).commands.get(DocumentIds.INSERT_CHART))
        val enabledDocument = document(true)
        assertIs<CommandResult.Success>(enabledDocument.invoke(DocumentIds.INSERT_CHART, CommandSurface.DOCK))
        assertTrue(enabledDocument.scope.activePage()!!.objects.any { it.content is ChartBlock })
    }

    @Test
    fun flowHostResolvesSpreadsheetBoundChartFromTheSameDocumentWorkbook() {
        val legacy = TableBlock(
            headers = listOf(
                TableCell(content = listOf(InlineText("Label"))),
                TableCell(content = listOf(InlineText("Amount"))),
            ),
            rows = listOf(
                listOf(TableCell(content = listOf(InlineText("A"))), TableCell(content = listOf(InlineText("10")))),
                listOf(TableCell(content = listOf(InlineText("B"))), TableCell(content = listOf(InlineText("20")))),
            ),
        )
        val upgraded = assertIs<TableUpgradeResult.Success>(TableCalculationBridge.upgrade(legacy))
        val binding = CalculationRangeBinding(upgraded.view.workbook.id, upgraded.view.workbook.embeddedTables.single().range)
        val chart = ChartDocument(source = ChartDataSource.CalculationRange(binding))
        val runtime = FlowHostRuntime(
            FlowEditController(EditTarget("bound-chart"), BlockDocument(listOf(upgraded.block, ChartBlockAdapter.save(ChartBlock(), chart)))),
            FormFactor(400, 800),
            chartStudioEnabled = true,
        )
        val ready = assertIs<ChartDataResolution.Ready>(ChartDataResolver.resolve(chart, runtime.chartCalculationProvider()))
        assertEquals(listOf("Label", "Amount"), ready.data.table.columns.map { it.name })
        assertEquals(listOf(10.0, 20.0), ready.data.series.single().points.map { it.y })
    }

    @Test
    fun documentHostResolvesBoundChartFromTheSamePageWorkbookAndPersistsEdits() {
        val upgraded = assertIs<TableUpgradeResult.Success>(TableCalculationBridge.upgrade(TableBlock(
            headers = listOf(TableCell(content = listOf(InlineText("Label"))), TableCell(content = listOf(InlineText("Amount")))),
            rows = listOf(
                listOf(TableCell(content = listOf(InlineText("A"))), TableCell(content = listOf(InlineText("10")))),
                listOf(TableCell(content = listOf(InlineText("B"))), TableCell(content = listOf(InlineText("20")))),
            ),
        )))
        val binding = CalculationRangeBinding(upgraded.view.workbook.id, upgraded.view.workbook.embeddedTables.single().range)
        val chart = ChartDocument(source = ChartDataSource.CalculationRange(binding))
        val chartBlock = ChartBlockAdapter.save(ChartBlock(id = "page-chart"), chart)
        val page = DocumentPageBlock(
            id = "page",
            geometry = PageGeometry.a4(),
            objects = listOf(
                SceneObject("table-object", SceneObjectKind.Table, SceneGeometry(0f, 0f, 300f, 180f), content = upgraded.block),
                SceneObject("chart-object", SceneObjectKind.Chart, SceneGeometry(0f, 200f, 360f, 240f), content = chartBlock),
            ),
        ).normalized()
        val runtime = DocumentHostRuntime(
            DocumentSceneController(EditTarget("document-bound-chart"), BlockDocument(listOf(page))),
            FormFactor(800, 1_000),
            chartStudioEnabled = true,
        )
        val ready = assertIs<ChartDataResolution.Ready>(ChartDataResolver.resolve(chart, runtime.chartCalculationProvider()))
        assertEquals(listOf(10.0, 20.0), ready.data.series.single().points.map { it.y })
        assertTrue(runtime.updateChart(chartBlock.id, chart.copy(title = "Persisted chart")))
        val encoded = BlockDocumentJson.encode(runtime.controller.document())
        val reopened = BlockDocumentJson.decode(encoded).findById(chartBlock.id) as ChartBlock
        assertEquals("Persisted chart", ChartBlockAdapter.load(reopened).document.title)
    }

    @Test
    fun spreadsheetSelectionCreatesOneBoundChartAndUndoRedoPreservesIt() {
        val workbookBlock = SpreadsheetWorkbookFactory.createBlock("Chart source")
        val runtime = SpreadsheetHostRuntime(EditTarget("spreadsheet-chart"), BlockDocument(listOf(workbookBlock)))
        listOf(
            GridAddress(0, 0) to "Label",
            GridAddress(0, 1) to "Amount",
            GridAddress(1, 0) to "A",
            GridAddress(1, 1) to "10",
        ).forEach { (address, value) ->
            runtime.select(address)
            assertTrue(runtime.commitFormulaBar(value).accepted)
        }
        runtime.select(GridAddress(0, 0))
        runtime.select(GridAddress(1, 1), extend = true)
        val chartId = requireNotNull(runtime.createChartFromSelection())
        val created = runtime.controller.document().findById(chartId) as ChartBlock
        val loaded = ChartBlockAdapter.load(created).document
        val source = assertIs<ChartDataSource.CalculationRange>(loaded.source)
        assertEquals(runtime.state().workbook.id, source.binding.workbookId)
        val ready = assertIs<ChartDataResolution.Ready>(ChartDataResolver.resolve(loaded, runtime.chartCalculationProvider()))
        assertEquals(listOf(10.0), ready.data.series.single().points.map { it.y })

        assertTrue(runtime.undo())
        assertNull(runtime.controller.document().findById(chartId))
        assertTrue(runtime.redo())
        assertNotNull(runtime.controller.document().findById(chartId))

        val payload = BlockDocumentJson.encode(runtime.controller.document())
        val reopened = SpreadsheetHostRuntime(EditTarget("spreadsheet-chart-reopen"), BlockDocumentJson.decode(payload), workbookBlock.id)
        val reopenedChart = reopened.controller.document().findById(chartId) as ChartBlock
        assertEquals(source.binding, assertIs<ChartDataSource.CalculationRange>(ChartBlockAdapter.load(reopenedChart).document.source).binding)
    }

    @Test
    fun accessibilityDescriptionIncludesAuthorTextAndSelection() {
        val document = manual(listOf("A" to "2")).copy(
            description = "Quarterly sales comparison.",
            interaction = ChartInteraction(selectedSeriesId = "series-1", selectedSourceRowIndex = 0),
        )
        val ready = assertIs<ChartDataResolution.Ready>(ChartDataResolver.resolve(document))
        val description = ChartAccessibility.description(document, ready.data)
        assertTrue(description.contains("Quarterly sales comparison"))
        assertTrue(description.contains("Selected series"))
        assertTrue(description.contains("Selected data row 1"))
    }

    @Test
    fun hitTestingFindsBarsAndPoints() {
        val barDoc = manual(listOf("A" to "5"))
        val ready = assertIs<ChartDataResolution.Ready>(ChartDataResolver.resolve(barDoc))
        val layout = ChartLayoutEngine.layout(barDoc, ready.data, 400f, 240f)
        val bar = assertIs<ChartMark.Bar>(layout.marks.first())
        assertNotNull(ChartHitTester.hit(layout, (bar.bounds.left + bar.bounds.right) / 2, (bar.bounds.top + bar.bounds.bottom) / 2))
        assertNull(ChartHitTester.hit(layout, -100f, -100f, 0f))
    }

    @Test
    fun pieSlicesUseTheDocumentPaletteInOrder() {
        val palette = listOf(
            com.norfold.app.domain.style.ColorValue(1, 2, 3),
            com.norfold.app.domain.style.ColorValue(4, 5, 6),
        )
        val document = manual(listOf("A" to "5", "B" to "8")).copy(
            type = ChartType.PIE,
            style = ChartStyle(palette = palette),
        )
        val ready = assertIs<ChartDataResolution.Ready>(ChartDataResolver.resolve(document))
        val arcs = ChartLayoutEngine.layout(document, ready.data, 400f, 240f).marks.map { assertIs<ChartMark.Arc>(it) }
        assertEquals(palette, arcs.map { it.color })
    }

    @Test
    fun markOverridesUseStableRowIdsAndSurviveRowReorder() {
        val red = com.norfold.app.domain.style.ColorValue(220, 30, 40)
        val source = ChartDataSource.Manual(
            rows = listOf(
                ChartDataRow("stable-a", listOf("A", "5")),
                ChartDataRow("stable-b", listOf("B", "8")),
            ),
        )
        val document = ChartDocument(
            source = source,
            series = listOf(ChartSeries(id = "series-1")),
            markOverrides = listOf(ChartMarkOverride("series-1", "stable-b", color = red)),
        )
        val reordered = document.copy(source = source.copy(rows = source.rows.reversed()))
        val ready = assertIs<ChartDataResolution.Ready>(ChartDataResolver.resolve(reordered))
        val marks = ChartLayoutEngine.layout(reordered, ready.data, 400f, 240f).marks
            .map { assertIs<ChartMark.Bar>(it) }
        assertEquals(red, marks.single { it.sourceRowId == "stable-b" }.color)
    }

    @Test
    fun mixedChartUsesOneSharedLayoutWithPerSeriesMarkTypes() {
        val columns = listOf(
            ChartColumn("category", "Category", ChartColumnKind.TEXT),
            ChartColumn("bars", "Bars", ChartColumnKind.NUMBER),
            ChartColumn("line", "Line", ChartColumnKind.NUMBER),
        )
        val document = ChartDocument(
            type = ChartType.BAR,
            source = ChartDataSource.Manual(
                columns,
                listOf(ChartDataRow("q1", listOf("Q1", "4", "7")), ChartDataRow("q2", listOf("Q2", "6", "5"))),
            ),
            series = listOf(
                ChartSeries("bars", "Bars", valueColumnId = "bars", markType = ChartType.BAR),
                ChartSeries("line", "Line", valueColumnId = "line", markType = ChartType.LINE, strokeWidth = 5f),
            ),
        )
        val ready = assertIs<ChartDataResolution.Ready>(ChartDataResolver.resolve(document))
        val layout = ChartLayoutEngine.layout(document, ready.data, 480f, 280f)
        assertTrue(layout.marks.any { it is ChartMark.Bar && it.seriesId == "bars" })
        assertTrue(layout.marks.any { it is ChartMark.Line && it.seriesId == "line" && it.strokeWidth == 5f })
        assertEquals(2, layout.xTicks.size)
    }

    @Test
    fun legacyChartPayloadDecodesWithInheritedSeriesDefaults() {
        val encoded = ChartDocumentCodec.encode(manual(listOf("A" to "2")))
        val decoded = assertIs<ChartDecodeResult.Success>(ChartDocumentCodec.decode(encoded)).document
        assertNull(decoded.series.single().markType)
        assertEquals("y", decoded.series.single().axisId)
        assertTrue(decoded.markOverrides.isEmpty())
    }

    private fun manual(values: List<Pair<String, String>>): ChartDocument = ChartDocument(
        source = ChartDataSource.Manual(
            rows = values.mapIndexed { index, (label, value) -> ChartDataRow("r$index", listOf(label, value)) },
        ),
        series = listOf(ChartSeries(id = "series-1", name = "Series 1", categoryColumnId = "category", valueColumnId = "value")),
    )

    private fun workbook(): CalculationWorkbook {
        var sheet = CalculationSheet(rowCount = 3, columnCount = 2, rows = List(3) { com.norfold.app.editor.calculation.CalculationRow() }, columns = List(2) { com.norfold.app.editor.calculation.CalculationColumn() })
        fun put(row: Int, column: Int, input: CellInput) {
            val coordinate = sheet.coordinateAt(row, column)!!
            sheet = sheet.upsertCell(CalculationCell(rowId = coordinate.rowId, columnId = coordinate.columnId, input = input))
        }
        put(0, 0, CellInput.Text("Label"))
        put(0, 1, CellInput.Text("Amount"))
        put(1, 0, CellInput.Text("A"))
        put(1, 1, CellInput.Number("10"))
        put(2, 0, CellInput.Text("B"))
        put(2, 1, CellInput.Number("20"))
        return CalculationWorkbook(sheets = listOf(sheet), activeSheetId = sheet.id)
    }
}

private inline fun <reified T> assertIs(value: Any?): T {
    assertTrue("Expected ${T::class.java.simpleName}, got ${value?.javaClass?.simpleName}", value is T)
    return value as T
}
