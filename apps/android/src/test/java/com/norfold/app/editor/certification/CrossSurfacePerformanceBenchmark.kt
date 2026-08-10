package com.norfold.app.editor.certification

import com.norfold.app.domain.BlockDocument
import com.norfold.app.domain.InlineText
import com.norfold.app.domain.ParagraphBlock
import com.norfold.app.domain.style.ColorValue
import com.norfold.app.editor.chart.ChartColumn
import com.norfold.app.editor.chart.ChartDocument
import com.norfold.app.editor.chart.ChartLayout
import com.norfold.app.editor.chart.ChartLayoutEngine
import com.norfold.app.editor.chart.ChartLinePattern
import com.norfold.app.editor.chart.ChartType
import com.norfold.app.editor.chart.ResolvedChartData
import com.norfold.app.editor.chart.ResolvedChartPoint
import com.norfold.app.editor.chart.ResolvedChartSeries
import com.norfold.app.editor.chart.ResolvedChartTable
import com.norfold.app.editor.history.EditOperation
import com.norfold.app.editor.history.EditTarget
import com.norfold.app.editor.history.EditTransaction
import com.norfold.app.editor.history.HistoryProjection
import com.norfold.app.editor.image.ImageEditDocument
import com.norfold.app.editor.image.ImagePreviewCache
import com.norfold.app.editor.image.ImagePreviewInstrumentation
import com.norfold.app.editor.image.ImagePreviewPolicy
import com.norfold.app.editor.interchange.RawSourceParseResult
import com.norfold.app.editor.interchange.RawSourceParser
import com.norfold.app.editor.interchange.RawSourceSerializer
import com.norfold.app.editor.recovery.FileRecoverySnapshotStore
import com.norfold.app.editor.recovery.RecoveryReason
import com.norfold.app.editor.recovery.RecoveryRetentionPolicy
import com.norfold.app.editor.recovery.RecoverySnapshot
import java.nio.file.Files
import kotlin.system.measureNanoTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Repeatable, synthetic measurements for completed editor surfaces whose elapsed-time acceptance boundary
 * is not defined by the product specification.
 *
 * Correctness and resource bounds are hard assertions. Wall-clock results are deliberately observations:
 * every case warms the exact measured path, takes several samples, and reports the median and spread. That
 * makes the harness useful for release comparison without turning normal unit tests into machine-speed
 * lotteries. Fixtures contain fixed generated values and never user content.
 */
class CrossSurfacePerformanceBenchmark {

    private data class Samples(val medianMicros: Long, val minMicros: Long, val maxMicros: Long)

    private fun measure(
        warmups: Int = 2,
        iterations: Int = 7,
        operation: () -> Unit,
    ): Samples {
        repeat(warmups) { operation() }
        val micros = List(iterations) { measureNanoTime(operation) / 1_000L }.sorted()
        return Samples(micros[micros.size / 2], micros.first(), micros.last())
    }

    private fun report(name: String, workload: String, samples: Samples) {
        println(
            "[benchmark] $name workload=$workload median=${samples.medianMicros}us " +
                "min=${samples.minMicros}us max=${samples.maxMicros}us",
        )
    }

    @Test
    fun canonicalInteractiveChartScaleRetainsAllSourcePointsButBoundsGeometry() {
        val points = List(50_000) { index ->
            ResolvedChartPoint(
                rowId = "row-$index",
                category = "C$index",
                x = index.toDouble(),
                y = ((index * 37L) % 10_000L).toDouble(),
                sourceRowIndex = index,
            )
        }
        val data = ResolvedChartData(
            table = ResolvedChartTable(columns = listOf(ChartColumn(id = "value")), rows = emptyList()),
            series = listOf(
                ResolvedChartSeries(
                    id = "series",
                    name = "Synthetic series",
                    color = ColorValue(103, 80, 164),
                    linePattern = ChartLinePattern.SOLID,
                    points = points,
                ),
            ),
        )
        val document = ChartDocument(type = ChartType.LINE)
        var layout: ChartLayout? = null
        val samples = measure {
            layout = ChartLayoutEngine.layout(document, data, 720f, 420f, maxRenderedPoints = 2_000)
        }

        val result = requireNotNull(layout)
        assertEquals("the full bound source remains authoritative", 50_000, result.sourcePointCount)
        assertEquals("only the render policy budget becomes geometry", 2_000, result.sampledPointCount)
        assertTrue("sampling must be disclosed textually", result.warnings.any { it.contains("Showing 2000 of 50000") })
        report("chart-layout", "50000-source-points/2000-rendered", samples)
    }

    @Test
    fun largeRawSourceRoundTripIsMeasuredInsideItsParserBudgets() {
        val document = BlockDocument(
            List(5_000) { index ->
                ParagraphBlock(id = "p$index", content = listOf(InlineText("Synthetic paragraph $index")))
            },
        )
        val source = RawSourceSerializer.serialize(document)
        var parsed: RawSourceParseResult? = null
        val samples = measure(warmups = 1, iterations = 5) { parsed = RawSourceParser.parse(source) }

        val success = parsed as RawSourceParseResult.Success
        assertEquals(5_000, success.ast.blocks.size)
        assertEquals(document.normalized(), RawSourceSerializer.toArtifact(success.ast).normalized())
        assertTrue("fixture must exercise a substantial token stream", success.tokenCount > 50_000)
        report("raw-source-parse", "5000-paragraphs/${source.toByteArray().size}-bytes", samples)
    }

    @Test
    fun imagePreviewMeasurementKeepsDecodeDimensionsAndCacheWorkBounded() {
        val document = ImageEditDocument()
        val target = ImagePreviewPolicy.target(
            sourceWidth = Int.MAX_VALUE,
            sourceHeight = Int.MAX_VALUE,
            viewWidth = 8_192,
            viewHeight = 8_192,
        )
        val key = ImagePreviewPolicy.cacheKey("synthetic-source", document, target)
        val cache = ImagePreviewCache<String>(capacity = 24)
        val instrumentation = ImagePreviewInstrumentation(keyCapacity = 24)
        val samples = measure {
            repeat(10_000) {
                instrumentation.recordRequest(key, target)
                if (cache.get(key) == null) cache.put(key, "preview")
            }
        }

        val metrics = instrumentation.snapshot()
        assertTrue(target.widthPx <= ImagePreviewPolicy.MAX_EDGE_PX)
        assertTrue(target.heightPx <= ImagePreviewPolicy.MAX_EDGE_PX)
        assertTrue(target.widthPx.toLong() * target.heightPx <= ImagePreviewPolicy.MAX_PIXELS)
        assertEquals("one key causes one decode regardless of preview churn", 1L, metrics.sourceDecodeCount)
        assertEquals(1, cache.stats().size)
        report("image-preview-cache", "10000-preview-requests", samples)
    }

    @Test
    fun largeHistoryProjectsMetadataWithoutArtifactPayloadReads() {
        val target = EditTarget("synthetic:history")
        val transactions = List(10_000) { index ->
            val block = ParagraphBlock(id = "p$index", content = listOf(InlineText("Synthetic $index")))
            EditTransaction(
                id = "t$index",
                label = "Insert paragraph",
                operations = listOf(EditOperation.InsertBlock(target, index, block)),
                createdAtMillis = index.toLong(),
            )
        }
        var projection: HistoryProjection? = null
        val samples = measure { projection = HistoryProjection.of(transactions) }

        val result = requireNotNull(projection)
        assertEquals(10_000, result.entries.size)
        assertEquals("p9999", result.nextUndo()?.blockIds?.single())
        assertTrue("projected rows keep ids and metadata, not document bodies", result.entries.all { it.operationCount == 1 })
        report("history-projection", "10000-transactions", samples)
    }

    @Test
    fun recoveryIndexAtItsRetentionCeilingListsSummariesWithoutDecodingPayloads() {
        val directory = Files.createTempDirectory("norfold-recovery-benchmark").toFile()
        try {
            val retention = RecoveryRetentionPolicy(maxAutomatic = 32, maxManual = 32, maxTotalBytes = 64L * 1024 * 1024)
            val store = FileRecoverySnapshotStore(directory, retention)
            repeat(64) { index ->
                store.put(
                    RecoverySnapshot(
                        id = "snapshot-$index",
                        reason = if (index % 2 == 0) RecoveryReason.MANUAL_CHECKPOINT else RecoveryReason.DESTRUCTIVE_EDIT,
                        createdAt = index.toLong(),
                        documents = mapOf("synthetic:document" to "opaque-payload-$index"),
                        name = if (index % 2 == 0) "Point $index" else null,
                    ),
                )
            }
            var listed = 0
            val samples = measure { listed = store.summaries().size }

            assertEquals("the configured manual and automatic ceilings are both retained", 64, listed)
            assertEquals("summary listing must not parse opaque non-document payloads", 64, store.summaries().size)
            report("recovery-index", "64-retained-summaries", samples)
        } finally {
            directory.deleteRecursively()
        }
    }
}
