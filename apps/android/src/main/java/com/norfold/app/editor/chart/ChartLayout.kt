package com.norfold.app.editor.chart

import com.norfold.app.domain.style.ColorValue
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

data class ChartPoint(val x: Float, val y: Float)
data class ChartRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = (right - left).coerceAtLeast(0f)
    val height: Float get() = (bottom - top).coerceAtLeast(0f)
    fun contains(x: Float, y: Float, padding: Float = 0f): Boolean =
        x in (left - padding)..(right + padding) && y in (top - padding)..(bottom + padding)
}

sealed interface ChartMark {
    val seriesId: String
    val sourceRowId: String?
    val sourceRowIndex: Int?
    val color: ColorValue
    val opacity: Float

    data class Bar(
        override val seriesId: String,
        override val sourceRowIndex: Int?,
        override val color: ColorValue,
        val bounds: ChartRect,
        val value: Double,
        val category: String,
        override val sourceRowId: String? = null,
        override val opacity: Float = 1f,
    ) : ChartMark

    data class Line(
        override val seriesId: String,
        override val color: ColorValue,
        val points: List<ChartPoint>,
        val pattern: ChartLinePattern,
        val fillToBottom: Boolean,
        override val opacity: Float = 1f,
        val strokeWidth: Float = 2f,
    ) : ChartMark {
        override val sourceRowId: String? = null
        override val sourceRowIndex: Int? = null
    }

    data class Point(
        override val seriesId: String,
        override val sourceRowIndex: Int?,
        override val color: ColorValue,
        val center: ChartPoint,
        val radius: Float,
        val value: Double,
        val category: String,
        override val sourceRowId: String? = null,
        override val opacity: Float = 1f,
    ) : ChartMark

    data class Arc(
        override val seriesId: String,
        override val sourceRowIndex: Int?,
        override val color: ColorValue,
        val center: ChartPoint,
        val innerRadius: Float,
        val outerRadius: Float,
        val startDegrees: Float,
        val sweepDegrees: Float,
        val value: Double,
        val category: String,
        override val sourceRowId: String? = null,
        override val opacity: Float = 1f,
    ) : ChartMark
}

data class ChartTick(val position: Float, val label: String)

data class ChartLayout(
    val width: Float,
    val height: Float,
    val plot: ChartRect,
    val marks: List<ChartMark>,
    val xTicks: List<ChartTick>,
    val yTicks: List<ChartTick>,
    val warnings: List<String>,
    val sampledPointCount: Int,
    val sourcePointCount: Int,
    val xDomain: Pair<Double, Double>? = null,
    val yDomain: Pair<Double, Double>? = null,
    val y2Ticks: List<ChartTick> = emptyList(),
    val y2Domain: Pair<Double, Double>? = null,
) {
    val isEmpty: Boolean get() = marks.isEmpty()

    /** Inverse of the stable scaled Y transform, used by direct manipulation. */
    fun valueAtY(y: Float, axisId: String = "y"): Double? {
        val domain = (if (axisId == "y2") y2Domain else yDomain) ?: return null
        val fraction = ((plot.bottom - y) / plot.height).coerceIn(0f, 1f).toDouble()
        val scale = max(max(abs(domain.first), abs(domain.second)), 1.0)
        return ((domain.first / scale) + (domain.second / scale - domain.first / scale) * fraction) * scale
    }
}

/**
 * Everything about a [ChartDocument] that can move a mark.
 *
 * §16.2 asks charts for *retained geometry* with layer/mark invalidation. Memoizing a layout on the whole
 * document defeats that: retitling a chart, editing its caption or moving its legend would re-sample and
 * re-project every mark, which on a 50,000-point series (§11.10) is the whole cost of drawing the chart paid
 * again for a change that cannot move anything. This key holds only the inputs [ChartLayoutEngine.layout]
 * actually reads, so text and legend edits reuse the geometry and data or type changes rebuild it.
 */
data class ChartGeometryKey(
    val type: ChartType,
    val palette: List<ColorValue>,
    val seriesGeometry: List<List<Any?>>,
    val axes: ChartAxes,
    val markOverrides: List<ChartMarkOverride>,
) {
    companion object {
        fun of(document: ChartDocument): ChartGeometryKey = ChartGeometryKey(
            document.type,
            document.style.palette,
            document.series.map { listOf(it.id, it.markType, it.axisId, it.strokeWidth, it.pointRadius, it.barWidthFraction, it.opacity) },
            document.axes,
            document.markOverrides,
        )
    }
}

object ChartLayoutEngine {
    fun layout(
        document: ChartDocument,
        data: ResolvedChartData,
        width: Float,
        height: Float,
        maxRenderedPoints: Int = 2_000,
    ): ChartLayout {
        val safeWidth = width.takeIf { it.isFinite() && it > 0f } ?: 1f
        val safeHeight = height.takeIf { it.isFinite() && it > 0f } ?: 1f
        val endInset = if (document.axes.y2?.visible == true) 52f else 20f
        val plot = ChartRect(52f, 20f, (safeWidth - endInset).coerceAtLeast(53f), (safeHeight - 42f).coerceAtLeast(21f))
        val rawCount = data.series.sumOf { it.points.size }
        val renderBudget = maxRenderedPoints.coerceIn(1, 20_000)
        val perSeriesBudget = (renderBudget / data.series.size.coerceAtLeast(1)).coerceAtLeast(1)
        val sampled = data.series.map { it.copy(points = sample(it.points, perSeriesBudget)) }
        val sampledCount = sampled.sumOf { it.points.size }
        val warnings = data.warnings.toMutableList()
        if (sampledCount < rawCount) warnings += "Showing $sampledCount of $rawCount points"

        val mixed = sampled.map { it.markType }.distinct().size > 1 ||
            sampled.any { it.markType != document.type || it.axisId == "y2" }
        val built = if (mixed) {
            mixedCartesian(document, sampled, plot)
        } else when (document.type) {
            ChartType.BAR -> bars(document, sampled, plot, stacked = false)
            ChartType.STACKED_BAR -> bars(document, sampled, plot, stacked = true)
            ChartType.LINE -> cartesian(document, sampled, plot, area = false, scatter = false)
            ChartType.AREA -> cartesian(document, sampled, plot, area = true, scatter = false)
            ChartType.SCATTER -> cartesian(document, sampled, plot, area = false, scatter = true)
            ChartType.PIE -> arcs(document, sampled, plot, donut = false, document.style.palette)
            ChartType.DONUT -> arcs(document, sampled, plot, donut = true, document.style.palette)
            ChartType.HISTOGRAM -> histogram(document, sampled, plot)
        }
        warnings += built.warnings
        return ChartLayout(
            safeWidth,
            safeHeight,
            plot,
            built.marks,
            built.xTicks,
            built.yTicks,
            warnings.distinct(),
            sampledCount,
            rawCount,
            built.xDomain,
            built.yDomain,
            built.y2Ticks,
            built.y2Domain,
        )
    }

    private data class Built(
        val marks: List<ChartMark>,
        val xTicks: List<ChartTick> = emptyList(),
        val yTicks: List<ChartTick> = emptyList(),
        val warnings: List<String> = emptyList(),
        val xDomain: Pair<Double, Double>? = null,
        val yDomain: Pair<Double, Double>? = null,
        val y2Ticks: List<ChartTick> = emptyList(),
        val y2Domain: Pair<Double, Double>? = null,
    )

    private fun effectiveColor(document: ChartDocument, series: ResolvedChartSeries, point: ResolvedChartPoint): ColorValue =
        document.markOverrides.firstOrNull { it.seriesId == series.id && it.sourceRowId == point.rowId }?.color
            ?: series.color

    private fun effectiveOpacity(document: ChartDocument, series: ResolvedChartSeries, point: ResolvedChartPoint): Float =
        document.markOverrides.firstOrNull { it.seriesId == series.id && it.sourceRowId == point.rowId }?.opacity
            ?: series.opacity
            ?: 1f

    /** Mixed cartesian charts share one category/domain and one hit-testable mark list. */
    private fun mixedCartesian(document: ChartDocument, series: List<ResolvedChartSeries>, plot: ChartRect): Built {
        val cartesianSeries = series.filter { it.markType !in setOf(ChartType.PIE, ChartType.DONUT, ChartType.HISTOGRAM) }
        if (cartesianSeries.isEmpty()) return Built(emptyList(), warnings = listOf("Mixed charts require cartesian series"))
        val categories = cartesianSeries.flatMap { it.points }.map { it.category }.distinct()
        fun axisDomain(axisId: String): Pair<Double, Double> {
            val values = cartesianSeries.filter { it.axisId == axisId }.flatMap { it.points }.map { it.y }
            return numericDomain(min(0.0, values.minOrNull() ?: 0.0), max(0.0, values.maxOrNull() ?: 0.0))
        }
        val yDomain = axisDomain("y")
        val hasY2 = cartesianSeries.any { it.axisId == "y2" }
        val y2Domain = axisDomain("y2").takeIf { hasY2 }
        val xDomain = 0.0 to (categories.lastIndex.coerceAtLeast(1)).toDouble()
        val barSeries = cartesianSeries.filter { it.markType in setOf(ChartType.BAR, ChartType.STACKED_BAR) }
        val marks = mutableListOf<ChartMark>()
        cartesianSeries.forEach { entry ->
            val entryDomain = if (entry.axisId == "y2") y2Domain ?: yDomain else yDomain
            val projected = entry.points.map { point ->
                val index = categories.indexOf(point.category).coerceAtLeast(0)
                val x = plot.left + (plot.width / categories.size.coerceAtLeast(1)) * (index + .5f)
                point to ChartPoint(x, yFor(point.y, entryDomain, plot))
            }
            when (entry.markType) {
                ChartType.BAR, ChartType.STACKED_BAR -> projected.forEach { (point, rendered) ->
                    val slot = plot.width / categories.size.coerceAtLeast(1)
                    val group = slot * .72f
                    val width = group / barSeries.size.coerceAtLeast(1) * (entry.barWidthFraction ?: .86f)
                    val seriesIndex = barSeries.indexOf(entry).coerceAtLeast(0)
                    val left = rendered.x - group / 2f + seriesIndex * (group / barSeries.size.coerceAtLeast(1))
                    marks += ChartMark.Bar(
                        entry.id,
                        point.sourceRowIndex,
                        effectiveColor(document, entry, point),
                        ChartRect(left, min(rendered.y, yFor(0.0, entryDomain, plot)), left + width, max(rendered.y, yFor(0.0, entryDomain, plot))),
                        point.y,
                        point.category,
                        sourceRowId = point.rowId,
                        opacity = effectiveOpacity(document, entry, point),
                    )
                }
                ChartType.LINE, ChartType.AREA -> {
                    marks += ChartMark.Line(
                        entry.id,
                        entry.color,
                        projected.map { it.second },
                        entry.linePattern,
                        entry.markType == ChartType.AREA,
                        opacity = entry.opacity ?: 1f,
                        strokeWidth = entry.strokeWidth ?: document.style.strokeWidth,
                    )
                    projected.forEach { (point, rendered) ->
                        marks += ChartMark.Point(
                            entry.id,
                            point.sourceRowIndex,
                            effectiveColor(document, entry, point),
                            rendered,
                            entry.pointRadius ?: 5f,
                            point.y,
                            point.category,
                            sourceRowId = point.rowId,
                            opacity = effectiveOpacity(document, entry, point),
                        )
                    }
                }
                ChartType.SCATTER -> projected.forEach { (point, rendered) ->
                    marks += ChartMark.Point(
                        entry.id,
                        point.sourceRowIndex,
                        effectiveColor(document, entry, point),
                        rendered,
                        entry.pointRadius ?: 5f,
                        point.y,
                        point.category,
                        sourceRowId = point.rowId,
                        opacity = effectiveOpacity(document, entry, point),
                    )
                }
                else -> Unit
            }
        }
        val slot = plot.width / categories.size.coerceAtLeast(1)
        return Built(
            marks = marks,
            xTicks = categories.mapIndexed { index, label -> ChartTick(plot.left + slot * (index + .5f), label) },
            yTicks = numericTicks(yDomain, plot),
            xDomain = xDomain,
            yDomain = yDomain,
            y2Ticks = y2Domain?.let { numericTicks(it, plot) }.orEmpty(),
            y2Domain = y2Domain,
            warnings = if (cartesianSeries.size != series.size) listOf("Pie, donut, and histogram series cannot join a mixed cartesian chart") else emptyList(),
        )
    }

    private fun bars(document: ChartDocument, series: List<ResolvedChartSeries>, plot: ChartRect, stacked: Boolean): Built {
        val categories = series.flatMap { it.points }.map { it.category }.distinct()
        if (categories.isEmpty()) return Built(emptyList())
        val bySeries = series.associateWith { entry -> entry.points.associateBy { it.category } }
        val positiveMax: Double
        val negativeMin: Double
        if (stacked) {
            val totals = categories.map { category ->
                val values = series.mapNotNull { bySeries[it]?.get(category)?.y }
                values.filter { it >= 0 }.sum() to values.filter { it < 0 }.sum()
            }
            positiveMax = max(0.0, totals.maxOfOrNull { it.first } ?: 0.0)
            negativeMin = min(0.0, totals.minOfOrNull { it.second } ?: 0.0)
        } else {
            val values = series.flatMap { it.points }.map { it.y }
            positiveMax = max(0.0, values.maxOrNull() ?: 0.0)
            negativeMin = min(0.0, values.minOrNull() ?: 0.0)
        }
        val domain = numericDomain(negativeMin, positiveMax)
        val zeroY = yFor(0.0, domain, plot)
        val slot = plot.width / categories.size
        val marks = mutableListOf<ChartMark>()
        categories.forEachIndexed { categoryIndex, category ->
            var positiveBase = 0.0
            var negativeBase = 0.0
            series.forEachIndexed { seriesIndex, entry ->
                val point = bySeries[entry]?.get(category) ?: return@forEachIndexed
                val left: Float
                val right: Float
                val start: Double
                val end: Double
                if (stacked) {
                    left = plot.left + categoryIndex * slot + slot * 0.16f
                    right = left + slot * 0.68f
                    start = if (point.y >= 0) positiveBase else negativeBase
                    end = start + point.y
                    if (point.y >= 0) positiveBase = end else negativeBase = end
                } else {
                    val groupWidth = slot * 0.76f
                    val barWidth = groupWidth / series.size.coerceAtLeast(1)
                    left = plot.left + categoryIndex * slot + slot * 0.12f + seriesIndex * barWidth
                    right = left + barWidth * (entry.barWidthFraction ?: 0.86f)
                    start = 0.0
                    end = point.y
                }
                val startY = if (start == 0.0) zeroY else yFor(start, domain, plot)
                val endY = yFor(end, domain, plot)
                marks += ChartMark.Bar(
                    entry.id,
                    point.sourceRowIndex,
                    effectiveColor(document, entry, point),
                    ChartRect(left, min(startY, endY), right, max(startY, endY)),
                    point.y,
                    category,
                    sourceRowId = point.rowId,
                    opacity = effectiveOpacity(document, entry, point),
                )
            }
        }
        return Built(
            marks,
            xTicks = categories.mapIndexed { index, label -> ChartTick(plot.left + slot * (index + .5f), label) },
            yTicks = numericTicks(domain, plot),
            yDomain = domain,
        )
    }

    private fun cartesian(
        document: ChartDocument,
        series: List<ResolvedChartSeries>,
        plot: ChartRect,
        area: Boolean,
        scatter: Boolean,
    ): Built {
        val all = series.flatMap { it.points }
        if (all.isEmpty()) return Built(emptyList())
        val categories = all.map { it.category }.distinct()
        val xDomain = if (scatter) {
            numericDomain(all.minOf { it.x }, all.maxOf { it.x })
        } else {
            0.0 to categories.lastIndex.coerceAtLeast(1).toDouble()
        }
        val yDomain = numericDomain(min(0.0, all.minOf { it.y }), max(0.0, all.maxOf { it.y }))
        val marks = buildList {
            series.forEach { entry ->
                val points = entry.points.map { point ->
                    val x = if (scatter) {
                        xFor(point.x, xDomain, plot)
                    } else {
                        val categoryIndex = categories.indexOf(point.category).coerceAtLeast(0)
                        val slot = plot.width / categories.size.coerceAtLeast(1)
                        plot.left + slot * (categoryIndex + .5f)
                    }
                    ChartPoint(x, yFor(point.y, yDomain, plot))
                }
                if (!scatter && points.isNotEmpty()) add(
                    ChartMark.Line(
                        entry.id,
                        entry.color,
                        points,
                        entry.linePattern,
                        area,
                        opacity = entry.opacity ?: 1f,
                        strokeWidth = entry.strokeWidth ?: document.style.strokeWidth,
                    ),
                )
                entry.points.zip(points).forEach { (source, rendered) ->
                    add(
                        ChartMark.Point(
                            entry.id,
                            source.sourceRowIndex,
                            effectiveColor(document, entry, source),
                            rendered,
                            entry.pointRadius ?: 5f,
                            source.y,
                            source.category,
                            sourceRowId = source.rowId,
                            opacity = effectiveOpacity(document, entry, source),
                        ),
                    )
                }
            }
        }
        val xTicks = if (scatter) {
            numericXTicks(xDomain, plot)
        } else {
            val slot = plot.width / categories.size.coerceAtLeast(1)
            categories.mapIndexed { index, label -> ChartTick(plot.left + slot * (index + .5f), label) }
        }
        return Built(marks, xTicks, numericTicks(yDomain, plot), xDomain = xDomain, yDomain = yDomain)
    }

    private fun arcs(
        document: ChartDocument,
        series: List<ResolvedChartSeries>,
        plot: ChartRect,
        donut: Boolean,
        documentPalette: List<ColorValue>,
    ): Built {
        val points = series.firstOrNull()?.points.orEmpty().filter { it.y > 0.0 }
        if (points.isEmpty()) return Built(emptyList(), warnings = listOf("Pie and donut charts require positive values"))
        val scale = points.maxOf { it.y }
        val scaledTotal = points.sumOf { it.y / scale }
        if (!scaledTotal.isFinite() || scaledTotal <= 0.0) return Built(emptyList(), warnings = listOf("Values are outside the supported range"))
        val center = ChartPoint((plot.left + plot.right) / 2f, (plot.top + plot.bottom) / 2f)
        val outer = min(plot.width, plot.height) * .46f
        val palette = documentPalette.ifEmpty { ChartStyle.defaultPalette() }
        var angle = -90f
        val marks = points.mapIndexed { index, point ->
            val sweep = ((point.y / scale) / scaledTotal * 360.0).toFloat()
            ChartMark.Arc(
                series.first().id,
                point.sourceRowIndex,
                document.markOverrides.firstOrNull { it.seriesId == series.first().id && it.sourceRowId == point.rowId }?.color
                    ?: palette[index % palette.size],
                center,
                if (donut) outer * .55f else 0f,
                outer,
                angle,
                sweep,
                point.y,
                point.category,
                sourceRowId = point.rowId,
                opacity = effectiveOpacity(document, series.first(), point),
            ).also { angle += sweep }
        }
        return Built(marks)
    }

    private fun histogram(@Suppress("UNUSED_PARAMETER") document: ChartDocument, series: List<ResolvedChartSeries>, plot: ChartRect): Built {
        val values = series.flatMap { it.points }.map { it.y }.sorted()
        if (values.isEmpty()) return Built(emptyList())
        val minValue = values.first()
        val maxValue = values.last()
        val bins = ceil(sqrt(values.size.toDouble())).toInt().coerceIn(1, 30)
        if (minValue == maxValue) {
            val bounds = ChartRect(plot.left + plot.width * .2f, plot.top, plot.right - plot.width * .2f, plot.bottom)
            return Built(listOf(ChartMark.Bar("histogram", null, series.first().color, bounds, values.size.toDouble(), format(minValue))))
        }
        val scale = max(abs(minValue), abs(maxValue)).coerceAtLeast(1.0)
        val normalizedMin = minValue / scale
        val normalizedSpan = maxValue / scale - normalizedMin
        val counts = IntArray(bins)
        values.forEach { value ->
            val index = floor(((value / scale - normalizedMin) / normalizedSpan) * bins).toInt().coerceIn(0, bins - 1)
            counts[index]++
        }
        val maxCount = counts.maxOrNull()?.coerceAtLeast(1) ?: 1
        val slot = plot.width / bins
        val marks = counts.mapIndexed { index, count ->
            val height = plot.height * count / maxCount.toFloat()
            ChartMark.Bar(
                "histogram",
                null,
                series.first().color,
                ChartRect(plot.left + index * slot + 1f, plot.bottom - height, plot.left + (index + 1) * slot - 1f, plot.bottom),
                count.toDouble(),
                "Bin ${index + 1}",
            )
        }
        val domain = 0.0 to maxCount.toDouble()
        return Built(marks, yTicks = numericTicks(domain, plot), yDomain = domain)
    }

    private fun numericDomain(minimum: Double, maximum: Double): Pair<Double, Double> {
        if (!minimum.isFinite() || !maximum.isFinite()) return 0.0 to 1.0
        if (minimum == maximum) {
            val pad = max(abs(minimum) * .1, 1.0)
            val lower = minimum - pad
            val upper = maximum + pad
            if (lower.isFinite() && upper.isFinite()) return lower to upper
            return when {
                minimum > 0.0 -> minimum * .9 to minimum
                minimum < 0.0 -> minimum to minimum * .9
                else -> -1.0 to 1.0
            }
        }
        return min(minimum, maximum) to max(minimum, maximum)
    }

    private fun normalized(value: Double, domain: Pair<Double, Double>): Double {
        val scale = max(max(abs(domain.first), abs(domain.second)), 1.0)
        val low = domain.first / scale
        val span = domain.second / scale - low
        return if (span == 0.0 || !span.isFinite()) .5 else ((value / scale - low) / span).coerceIn(0.0, 1.0)
    }

    private fun xFor(value: Double, domain: Pair<Double, Double>, plot: ChartRect): Float =
        plot.left + plot.width * normalized(value, domain).toFloat()

    private fun yFor(value: Double, domain: Pair<Double, Double>, plot: ChartRect): Float =
        plot.bottom - plot.height * normalized(value, domain).toFloat()

    private fun numericTicks(domain: Pair<Double, Double>, plot: ChartRect): List<ChartTick> = (0..4).map { index ->
        val fraction = index / 4.0
        ChartTick(plot.bottom - plot.height * fraction.toFloat(), format(interpolate(domain, fraction)))
    }

    private fun numericXTicks(domain: Pair<Double, Double>, plot: ChartRect): List<ChartTick> = (0..4).map { index ->
        val fraction = index / 4.0
        ChartTick(plot.left + plot.width * fraction.toFloat(), format(interpolate(domain, fraction)))
    }

    private fun interpolate(domain: Pair<Double, Double>, fraction: Double): Double {
        val scale = max(max(abs(domain.first), abs(domain.second)), 1.0)
        return ((domain.first / scale) + (domain.second / scale - domain.first / scale) * fraction) * scale
    }

    private fun format(value: Double): String = when {
        !value.isFinite() -> "—"
        abs(value) >= 1_000_000 || (value != 0.0 && abs(value) < .001) -> "%.2e".format(java.util.Locale.ROOT, value)
        abs(value - value.toLong()) < 1e-9 -> value.toLong().toString()
        else -> "%.2f".format(java.util.Locale.ROOT, value).trimEnd('0').trimEnd('.')
    }

    private fun <T> sample(points: List<T>, limit: Int): List<T> {
        if (limit <= 0 || points.size <= limit) return points
        if (limit == 1) return listOf(points.first())
        return List(limit) { index -> points[(index.toLong() * (points.lastIndex) / (limit - 1)).toInt()] }
    }
}

data class ChartHit(val mark: ChartMark, val distance: Float)

object ChartHitTester {
    fun hit(layout: ChartLayout, x: Float, y: Float, tolerance: Float = 12f): ChartHit? =
        layout.marks.mapNotNull { mark ->
            when (mark) {
                is ChartMark.Bar -> if (mark.bounds.contains(x, y, tolerance)) ChartHit(mark, 0f) else null
                is ChartMark.Point -> {
                    val distance = kotlin.math.hypot(x - mark.center.x, y - mark.center.y)
                    if (distance <= mark.radius + tolerance) ChartHit(mark, distance) else null
                }
                is ChartMark.Arc -> {
                    val dx = x - mark.center.x
                    val dy = y - mark.center.y
                    val radius = kotlin.math.hypot(dx, dy)
                    var degrees = Math.toDegrees(kotlin.math.atan2(dy.toDouble(), dx.toDouble())).toFloat()
                    if (degrees < -90f) degrees += 360f
                    val start = mark.startDegrees
                    val end = start + mark.sweepDegrees
                    if (radius in mark.innerRadius..mark.outerRadius && degrees in start..end) ChartHit(mark, 0f) else null
                }
                is ChartMark.Line -> mark.points.minOfOrNull { kotlin.math.hypot(x - it.x, y - it.y) }
                    ?.takeIf { it <= tolerance }?.let { ChartHit(mark, it) }
            }
        }.minByOrNull { it.distance }
}

object ChartDirectEditor {
    /** Precise/direct manipulation write path for manual data; range-backed charts remain read-only. */
    fun updateValue(document: ChartDocument, seriesId: String, sourceRowIndex: Int, value: String): ChartDocument? {
        val source = document.source as? ChartDataSource.Manual ?: return null
        val series = document.series.firstOrNull { it.id == seriesId } ?: return null
        val column = source.columns.indexOfFirst { it.id == series.valueColumnId }
        val row = source.rows.getOrNull(sourceRowIndex) ?: return null
        if (column < 0 || value.toDoubleOrNull()?.isFinite() != true) return null
        val values = row.values.toMutableList().apply { while (size <= column) add("") }
        values[column] = value
        val rows = source.rows.toMutableList().also { it[sourceRowIndex] = row.copy(values = values) }
        return document.copy(source = source.copy(rows = rows)).normalized()
    }
}
