package com.norfold.app.ui.editor.chart

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.norfold.app.domain.style.ColorValue
import com.norfold.app.editor.chart.AccessibleChartTable
import com.norfold.app.editor.chart.ChartAccessibility
import com.norfold.app.editor.chart.ChartDataResolution
import com.norfold.app.editor.chart.ChartDocument
import com.norfold.app.editor.chart.ChartGeometryKey
import com.norfold.app.editor.chart.ChartLayoutEngine
import com.norfold.app.editor.chart.ChartHitTester
import com.norfold.app.editor.chart.ChartLinePattern
import com.norfold.app.editor.chart.ChartLegendPosition
import com.norfold.app.editor.chart.ChartMark
import com.norfold.app.editor.chart.ChartPoint
import com.norfold.app.editor.chart.ResolvedChartData
import com.norfold.app.ui.components.NorfoldBottomSheet

data class ChartDirectManipulationCallbacks(
    val onSelect: (seriesId: String, sourceRowIndex: Int, sourceRowId: String?) -> Unit,
    val onStart: (seriesId: String, sourceRowIndex: Int, sourceRowId: String?) -> Boolean,
    val onPreview: (seriesId: String, sourceRowIndex: Int, value: Double) -> Unit,
    val onEnd: () -> Unit,
    val onCancel: () -> Unit,
    val onStyle: (seriesId: String, sourceRowIndex: Int, sourceRowId: String?) -> Unit,
)

/** Native, offline renderer. It consumes resolved immutable data and never opens a calculation engine. */
@Composable
fun ChartRenderer(
    document: ChartDocument,
    resolution: ChartDataResolution,
    modifier: Modifier = Modifier,
    heightDp: Int = 240,
    onRebind: (() -> Unit)? = null,
    directManipulation: ChartDirectManipulationCallbacks? = null,
) {
    when (resolution) {
        is ChartDataResolution.BrokenBinding -> ChartProblem(
            title = "Chart data is unavailable",
            detail = resolution.message,
            action = onRebind?.let { "Rebind data" to it },
            modifier = modifier,
        )
        is ChartDataResolution.Invalid -> ChartProblem("Chart needs data", resolution.message, null, modifier)
        is ChartDataResolution.Ready -> ReadyChart(document, resolution.data, modifier, heightDp, directManipulation)
    }
}

@Composable
private fun ReadyChart(
    document: ChartDocument,
    data: ResolvedChartData,
    modifier: Modifier,
    heightDp: Int,
    directManipulation: ChartDirectManipulationCallbacks?,
) {
    var showData by remember { mutableStateOf(false) }
    var inspectedMark by remember(document.id) { mutableStateOf<ChartMark?>(null) }
    val scheme = MaterialTheme.colorScheme
    val background = document.style.background?.let { Color(it.toArgb()) } ?: scheme.surface
    val foregroundColor = document.style.foreground?.let { Color(it.toArgb()) } ?: scheme.onSurface
    val gridColor = document.style.grid?.let { Color(it.toArgb()) } ?: scheme.outlineVariant
    val description = remember(document, data) { ChartAccessibility.description(document, data) }
    val table = remember(data) { ChartAccessibility.table(data) }
    Column(
        modifier
            .background(background, RoundedCornerShape(12.dp))
            .padding(12.dp)
            .semantics(mergeDescendants = true) {
                contentDescription = description
                customActions = listOf(CustomAccessibilityAction("View chart data") { showData = true; true })
            },
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (document.title.isNotBlank()) Text(document.title, style = MaterialTheme.typography.titleMedium)
        if (document.subtitle.isNotBlank()) Text(document.subtitle, style = MaterialTheme.typography.bodySmall)
        if (document.legend.visible && document.legend.position == ChartLegendPosition.TOP) {
            ChartLegendRow(data, Alignment.Start)
        }
        BoxWithConstraints(Modifier.fillMaxWidth().height(heightDp.dp)) {
            val density = LocalDensity.current
            val widthPx = with(density) { maxWidth.toPx() }
            val heightPx = with(density) { maxHeight.toPx() }
            // Keyed on the geometry inputs rather than the whole document, so a title, caption or legend edit
            // reuses the retained marks instead of re-projecting the series (§16.2).
            val geometryKey = ChartGeometryKey.of(document)
            val layout = remember(geometryKey, data, widthPx, heightPx) {
                ChartLayoutEngine.layout(document, data, widthPx, heightPx)
            }
            val selectedMark = layout.marks.firstOrNull { mark ->
                mark.seriesId == document.interaction.selectedSeriesId &&
                    (document.interaction.selectedSourceRowId?.let { it == mark.sourceRowId }
                        ?: (mark.sourceRowIndex == document.interaction.selectedSourceRowIndex))
            }
            val tapModifier = if (!document.interaction.selectionEnabled) Modifier else Modifier.pointerInput(layout, directManipulation) {
                detectTapGestures { offset ->
                    val hit = ChartHitTester.hit(layout, offset.x, offset.y)?.mark
                    inspectedMark = hit
                    val row = hit?.sourceRowIndex
                    if (hit != null && row != null) {
                        val styleHandle = markStyleHandle(hit)
                        if (styleHandle != null && kotlin.math.hypot(offset.x - styleHandle.x, offset.y - styleHandle.y) <= 24f) {
                            directManipulation?.onStyle(hit.seriesId, row, hit.sourceRowId)
                        } else {
                            directManipulation?.onSelect(hit.seriesId, row, hit.sourceRowId)
                        }
                    }
                }
            }
            val gestureModifier = if (directManipulation == null || !document.interaction.valueEditingEnabled) {
                Modifier
            } else {
                Modifier.pointerInput(layout, directManipulation) {
                    var active: ChartMark? = null
                    detectDragGestures(
                        onDragStart = { offset ->
                            val mark = ChartHitTester.hit(layout, offset.x, offset.y)?.mark
                            val row = mark?.sourceRowIndex
                            val handle = mark?.let(::markDataHandle)
                            active = mark?.takeIf {
                                row != null && handle != null &&
                                    kotlin.math.hypot(offset.x - handle.x, offset.y - handle.y) <= 28f &&
                                    directManipulation.onStart(mark.seriesId, row, mark.sourceRowId)
                            }
                        },
                        onDrag = { change, _ ->
                            val mark = active ?: return@detectDragGestures
                            val row = mark.sourceRowIndex ?: return@detectDragGestures
                            val axisId = data.series.firstOrNull { it.id == mark.seriesId }?.axisId ?: "y"
                            layout.valueAtY(change.position.y, axisId)?.takeIf(Double::isFinite)?.let { value ->
                                directManipulation.onPreview(mark.seriesId, row, value)
                            }
                            change.consume()
                        },
                        onDragEnd = {
                            if (active != null) directManipulation.onEnd()
                            active = null
                        },
                        onDragCancel = {
                            if (active != null) directManipulation.onCancel()
                            active = null
                        },
                    )
                }
            }
            Canvas(Modifier.fillMaxWidth().height(heightDp.dp).then(tapModifier).then(gestureModifier)) {
                val grid = gridColor
                val foreground = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                    color = foregroundColor.toArgb()
                    textSize = 11.dp.toPx()
                }
                if (document.axes.y.showGrid) layout.yTicks.forEach { tick ->
                    drawLine(grid, Offset(layout.plot.left, tick.position), Offset(layout.plot.right, tick.position), 1f)
                }
                if (document.axes.x.showGrid) layout.xTicks.forEach { tick ->
                    drawLine(grid, Offset(tick.position, layout.plot.top), Offset(tick.position, layout.plot.bottom), 1f)
                }
                if (document.axes.y.visible) {
                    drawLine(grid, Offset(layout.plot.left, layout.plot.top), Offset(layout.plot.left, layout.plot.bottom), 1.5f)
                    drawIntoCanvas { canvas ->
                        layout.yTicks.forEach { tick ->
                            foreground.textAlign = android.graphics.Paint.Align.RIGHT
                            canvas.nativeCanvas.drawText(tick.label.take(12), layout.plot.left - 6.dp.toPx(), tick.position + 4.dp.toPx(), foreground)
                        }
                    }
                }
                document.axes.y2?.takeIf { it.visible }?.let {
                    drawLine(grid, Offset(layout.plot.right, layout.plot.top), Offset(layout.plot.right, layout.plot.bottom), 1.5f)
                    drawIntoCanvas { canvas ->
                        layout.y2Ticks.forEach { tick ->
                            foreground.textAlign = android.graphics.Paint.Align.LEFT
                            canvas.nativeCanvas.drawText(tick.label.take(12), layout.plot.right + 6.dp.toPx(), tick.position + 4.dp.toPx(), foreground)
                        }
                    }
                }
                if (document.axes.x.visible) {
                    drawLine(grid, Offset(layout.plot.left, layout.plot.bottom), Offset(layout.plot.right, layout.plot.bottom), 1.5f)
                    val every = (layout.xTicks.size / 6).coerceAtLeast(1)
                    drawIntoCanvas { canvas ->
                        foreground.textAlign = android.graphics.Paint.Align.CENTER
                        layout.xTicks.forEachIndexed { index, tick ->
                            if (index % every == 0) canvas.nativeCanvas.drawText(
                                tick.label.take(10),
                                tick.position,
                                layout.plot.bottom + 17.dp.toPx(),
                                foreground,
                            )
                        }
                    }
                }
                layout.marks.forEach { mark ->
                    val color = Color(mark.color.toArgb()).copy(alpha = document.style.opacity * mark.opacity)
                    when (mark) {
                    is ChartMark.Bar -> drawRoundRect(
                        color,
                        topLeft = Offset(mark.bounds.left, mark.bounds.top),
                        size = androidx.compose.ui.geometry.Size(mark.bounds.width, mark.bounds.height.coerceAtLeast(1f)),
                        cornerRadius = CornerRadius(document.style.cornerRadius, document.style.cornerRadius),
                    )
                    is ChartMark.Point -> drawCircle(color, mark.radius, Offset(mark.center.x, mark.center.y))
                    is ChartMark.Line -> {
                        if (mark.points.isEmpty()) return@forEach
                        val path = Path().apply {
                            moveTo(mark.points.first().x, mark.points.first().y)
                            mark.points.drop(1).forEach { lineTo(it.x, it.y) }
                            if (mark.fillToBottom) {
                                lineTo(mark.points.last().x, layout.plot.bottom)
                                lineTo(mark.points.first().x, layout.plot.bottom)
                                close()
                            }
                        }
                        if (mark.fillToBottom) drawPath(path, color.copy(alpha = color.alpha * .22f), style = Fill)
                        val effect = when (mark.pattern) {
                            ChartLinePattern.SOLID -> null
                            ChartLinePattern.DASHED -> PathEffect.dashPathEffect(floatArrayOf(14f, 8f))
                            ChartLinePattern.DOTTED -> PathEffect.dashPathEffect(floatArrayOf(2f, 7f))
                        }
                        drawPath(path, color, style = Stroke(mark.strokeWidth, cap = StrokeCap.Round, pathEffect = effect))
                    }
                    is ChartMark.Arc -> drawArc(
                        color,
                        mark.startDegrees,
                        mark.sweepDegrees,
                        useCenter = mark.innerRadius == 0f,
                        topLeft = Offset(mark.center.x - mark.outerRadius, mark.center.y - mark.outerRadius),
                        size = androidx.compose.ui.geometry.Size(mark.outerRadius * 2, mark.outerRadius * 2),
                        style = if (mark.innerRadius == 0f) Fill else Stroke(mark.outerRadius - mark.innerRadius),
                    )
                    }
                }
                selectedMark?.let { mark ->
                    markDataHandle(mark)?.let { center ->
                        val offset = Offset(center.x, center.y)
                        drawCircle(scheme.surface, 10.dp.toPx(), offset)
                        drawCircle(scheme.primary, 7.dp.toPx(), offset)
                    }
                    markStyleHandle(mark)?.let { center ->
                        val size = 14.dp.toPx()
                        drawRoundRect(
                            scheme.surface,
                            topLeft = Offset(center.x - size / 2f - 3f, center.y - size / 2f - 3f),
                            size = androidx.compose.ui.geometry.Size(size + 6f, size + 6f),
                            cornerRadius = CornerRadius(5f, 5f),
                        )
                        drawRoundRect(
                            scheme.secondary,
                            topLeft = Offset(center.x - size / 2f, center.y - size / 2f),
                            size = androidx.compose.ui.geometry.Size(size, size),
                            cornerRadius = CornerRadius(4f, 4f),
                        )
                    }
                }
                if (document.labels.showValue || document.labels.showCategory || document.labels.showSeriesName) {
                    drawIntoCanvas { canvas ->
                        foreground.textAlign = android.graphics.Paint.Align.CENTER
                        layout.marks.forEach { mark ->
                            val anchor = when (mark) {
                                is ChartMark.Bar -> Offset((mark.bounds.left + mark.bounds.right) / 2f, mark.bounds.top - 4.dp.toPx())
                                is ChartMark.Point -> Offset(mark.center.x, mark.center.y - mark.radius - 4.dp.toPx())
                                is ChartMark.Arc -> {
                                    val angle = Math.toRadians((mark.startDegrees + mark.sweepDegrees / 2f).toDouble())
                                    val radius = (mark.innerRadius + mark.outerRadius) / 2f
                                    Offset(
                                        mark.center.x + kotlin.math.cos(angle).toFloat() * radius,
                                        mark.center.y + kotlin.math.sin(angle).toFloat() * radius,
                                    )
                                }
                                is ChartMark.Line -> null
                            } ?: return@forEach
                            val parts = buildList {
                                if (document.labels.showCategory) add(
                                    when (mark) {
                                        is ChartMark.Bar -> mark.category
                                        is ChartMark.Point -> mark.category
                                        is ChartMark.Arc -> mark.category
                                        else -> ""
                                    },
                                )
                                if (document.labels.showValue) add(
                                    when (mark) {
                                        is ChartMark.Bar -> formatChartValue(mark.value)
                                        is ChartMark.Point -> formatChartValue(mark.value)
                                        is ChartMark.Arc -> formatChartValue(mark.value)
                                        else -> ""
                                    },
                                )
                                if (document.labels.showSeriesName) add(data.series.firstOrNull { it.id == mark.seriesId }?.name.orEmpty())
                            }.filter(String::isNotBlank)
                            canvas.nativeCanvas.drawText(parts.joinToString(" · ").take(30), anchor.x, anchor.y, foreground)
                        }
                    }
                }
            }
        }
        if (document.axes.x.visible && document.axes.x.title.isNotBlank()) {
            Text(document.axes.x.title, Modifier.align(Alignment.CenterHorizontally), style = MaterialTheme.typography.labelMedium)
        }
        if (document.axes.y.visible && document.axes.y.title.isNotBlank()) {
            Text(document.axes.y.title, style = MaterialTheme.typography.labelMedium)
        }
        if (document.legend.visible && document.legend.position != ChartLegendPosition.TOP) {
            ChartLegendRow(
                data,
                if (document.legend.position == ChartLegendPosition.END) Alignment.End else Alignment.Start,
            )
        }
        data.warnings.forEach { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        inspectedMark?.let { mark ->
            Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(10.dp)) {
                Text(markSummary(mark, data), Modifier.padding(horizontal = 10.dp, vertical = 8.dp), style = MaterialTheme.typography.bodySmall)
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = { showData = true }) { Text("View data") }
        }
        if (document.caption.isNotBlank()) Text(document.caption, style = MaterialTheme.typography.bodySmall)
    }
    if (showData) ChartDataSheet(table) { showData = false }
}

private fun markDataHandle(mark: ChartMark): ChartPoint? = when (mark) {
    is ChartMark.Bar -> ChartPoint((mark.bounds.left + mark.bounds.right) / 2f, mark.bounds.top)
    is ChartMark.Point -> mark.center
    is ChartMark.Arc -> null
    is ChartMark.Line -> null
}

private fun markStyleHandle(mark: ChartMark): ChartPoint? = when (mark) {
    is ChartMark.Bar -> ChartPoint(mark.bounds.right, mark.bounds.top)
    is ChartMark.Point -> ChartPoint(mark.center.x + mark.radius + 12f, mark.center.y)
    is ChartMark.Arc -> null
    is ChartMark.Line -> null
}

private fun markSummary(mark: ChartMark, data: ResolvedChartData): String {
    val series = data.series.firstOrNull { it.id == mark.seriesId }?.name ?: "Series"
    return when (mark) {
        is ChartMark.Bar -> "$series · ${mark.category}: ${formatChartValue(mark.value)}"
        is ChartMark.Point -> "$series · ${mark.category}: ${formatChartValue(mark.value)}"
        is ChartMark.Arc -> "$series · ${mark.category}: ${formatChartValue(mark.value)}"
        is ChartMark.Line -> series
    }
}

@Composable
private fun ChartLegendRow(data: ResolvedChartData, alignment: Alignment.Horizontal) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = when (alignment) {
            Alignment.End -> Arrangement.End
            else -> Arrangement.Start
        },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        data.series.take(12).forEach { series ->
            Row(Modifier.padding(end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(12.dp).background(Color(series.color.toArgb()), RoundedCornerShape(3.dp)))
                Text(series.name, Modifier.padding(start = 4.dp), style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

private fun formatChartValue(value: Double): String = when {
    !value.isFinite() -> ""
    value == value.toLong().toDouble() -> value.toLong().toString()
    else -> java.lang.String.format(java.util.Locale.ROOT, "%.4g", value)
}

@Composable
private fun ChartProblem(
    title: String,
    detail: String,
    action: Pair<String, () -> Unit>?,
    modifier: Modifier,
) {
    Column(
        modifier.background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        Text(detail, style = MaterialTheme.typography.bodySmall)
        action?.let { (label, callback) -> TextButton(onClick = callback) { Text(label) } }
    }
}

@Composable
private fun ChartDataSheet(table: AccessibleChartTable, onDismiss: () -> Unit) {
    NorfoldBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Chart data", style = MaterialTheme.typography.headlineSmall)
            if (table.headers.isEmpty()) {
                Text("No data")
            } else {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    table.headers.forEach { Text(it, Modifier.weight(1f), style = MaterialTheme.typography.labelMedium) }
                }
                table.rows.take(200).forEach { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        table.headers.indices.forEach { index -> Text(row.getOrNull(index).orEmpty(), Modifier.weight(1f)) }
                    }
                }
                if (table.rows.size > 200) Text("Showing the first 200 rows", style = MaterialTheme.typography.labelSmall)
            }
            TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) { Text("Close") }
        }
    }
}
