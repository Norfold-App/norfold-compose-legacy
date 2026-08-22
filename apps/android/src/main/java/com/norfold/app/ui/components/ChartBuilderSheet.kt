package com.norfold.app.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Paint
import android.graphics.Path
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.border
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import com.norfold.app.ui.components.NorfoldBottomSheet
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.ripple
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.norfold.app.domain.style.ChromeGeometry
import com.norfold.app.ui.NorfoldChrome
import com.norfold.app.ui.asColorValue
import com.norfold.app.ui.rememberChromeFocus
import com.norfold.app.domain.ChartBlock
import com.norfold.app.domain.ChartBuilderModel
import com.norfold.app.domain.ChartDataRow
import com.norfold.app.domain.ChartPlacement
import com.norfold.app.domain.ChartSpecCodec
import com.norfold.app.domain.ChartType
import com.norfold.app.domain.DocumentBlock
import com.norfold.app.domain.ImageBlock
import com.norfold.app.ui.selectableChrome
import com.norfold.app.ui.toggleableChrome
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChartBuilderSheet(
    initialSpec: String? = null,
    onDismiss: () -> Unit,
    onCreate: (DocumentBlock) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val colorScheme = MaterialTheme.colorScheme
    val themeDefaultColor = colorScheme.primary.toRgbHex()
    /**
     * The theme colours a chart may be drawn in, each paired with what it *is*.
     *
     * The name travels with the colour rather than in a parallel list, because `distinctBy` drops entries —
     * on a theme where `secondary` equals `primary`, a list of names indexed alongside would silently shift
     * and every swatch after the collision would announce the wrong one.
     */
    val themeChartColors = remember(colorScheme) {
        listOf(
            NamedColor(colorScheme.primary, "Primary"),
            NamedColor(colorScheme.secondary, "Secondary"),
            NamedColor(colorScheme.tertiary, "Tertiary"),
            NamedColor(colorScheme.error, "Error"),
            NamedColor(colorScheme.primaryContainer, "Primary container"),
            NamedColor(colorScheme.secondaryContainer, "Secondary container"),
            NamedColor(colorScheme.tertiaryContainer, "Tertiary container"),
        ).distinctBy { it.color.toArgb() }
    }
    val initial = remember(initialSpec) { ChartSpecCodec.decode(initialSpec) }
    var type by remember(initialSpec) { mutableStateOf(initial.type) }
    var title by remember(initialSpec) { mutableStateOf(initial.title) }
    var caption by remember(initialSpec) { mutableStateOf(initial.caption) }
    var xAxis by remember(initialSpec) { mutableStateOf(initial.xAxis) }
    var yAxis by remember(initialSpec) { mutableStateOf(initial.yAxis) }
    var legend by remember(initialSpec) { mutableStateOf(initial.showLegend) }
    var color by remember(initialSpec, themeDefaultColor) {
        mutableStateOf(initial.color.ifBlank { themeDefaultColor })
    }
    var placement by remember(initialSpec) { mutableStateOf(ChartPlacement.Code) }
    var creating by remember { mutableStateOf(false) }
    val rows = remember(initialSpec) { mutableStateListOf<ChartDataRow>().apply { addAll(initial.rows) } }

    fun model() = ChartBuilderModel(
        type = type,
        title = title,
        caption = caption,
        xAxis = xAxis,
        yAxis = yAxis,
        showLegend = legend,
        color = color,
        rows = rows.toList(),
    )

    NorfoldBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).imePadding().padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(if (initialSpec == null) "Create chart" else "Edit chart", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ChartType.entries.forEach { candidate ->
                    TextButton(onClick = { type = candidate }, modifier = Modifier.background(if (type == candidate) MaterialTheme.colorScheme.primaryContainer else Color.Transparent, RoundedCornerShape(8.dp))) { Text(candidate.label) }
                }
            }
            VisualChartComposer(
                type = type,
                rows = rows,
                color = color,
                onAdd = { rows += ChartDataRow(label = "Section ${rows.size + 1}", value = "1") },
            )
            OutlinedTextField(title, { title = it }, label = { Text("Title") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            OutlinedTextField(caption, { caption = it }, label = { Text("Caption") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(xAxis, { xAxis = it }, label = { Text("X axis") }, modifier = Modifier.weight(1f), singleLine = true)
                OutlinedTextField(yAxis, { yAxis = it }, label = { Text("Y axis") }, modifier = Modifier.weight(1f), singleLine = true)
            }
            Text("Data", fontWeight = FontWeight.SemiBold)
            // Both scroll axes: the row list scrolls vertically inside a bounded height so long data
            // sets never clip, and the whole grid pans horizontally so Series/Delete stay reachable.
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                Column(Modifier.heightIn(max = 280.dp).verticalScroll(rememberScrollState())) {
                    rows.forEachIndexed { index, row ->
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            RowColorSwatch(
                                rowColor = row.color,
                                palette = themeChartColors,
                                fallback = runCatching { Color(android.graphics.Color.parseColor(color)) }.getOrDefault(colorScheme.primary),
                                onChange = { rows[index] = row.copy(color = it) },
                            )
                            OutlinedTextField(row.label, { rows[index] = row.copy(label = it) }, label = { Text("Label") }, modifier = Modifier.width(150.dp), singleLine = true)
                            OutlinedTextField(row.value, { rows[index] = row.copy(value = it) }, label = { Text("Value") }, modifier = Modifier.width(120.dp), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true)
                            OutlinedTextField(row.series, { rows[index] = row.copy(series = it) }, label = { Text("Series") }, modifier = Modifier.width(150.dp), singleLine = true)
                            TextButton(onClick = { if (rows.size > 1) rows.removeAt(index) }, enabled = rows.size > 1) { Text("Delete") }
                        }
                    }
                }
            }
            TextButton(onClick = { rows += ChartDataRow(label = "Item ${rows.size + 1}", value = "0") }) { Text("+ Add row") }
            LegendToggleRow(legend) { legend = it }
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).selectableGroup(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                themeChartColors.forEach { candidate ->
                    val hex = candidate.color.toRgbHex()
                    NorfoldColorSwatch(
                        color = candidate.color,
                        selected = color == hex,
                        label = candidate.name,
                        onClick = { color = hex },
                        size = 34f,
                    )
                }
            }
            Row(Modifier.fillMaxWidth().selectableGroup(), verticalAlignment = Alignment.CenterVertically) {
                ChartPlacement.entries.forEach { option ->
                    PlacementOption(
                        label = if (option == ChartPlacement.Image) "As image" else "As editable chart",
                        selected = placement == option,
                        modifier = Modifier.weight(1f),
                    ) { placement = option }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text("Cancel") }
                Button(
                    onClick = {
                        val current = model()
                        if (placement == ChartPlacement.Code) {
                            onCreate(ChartBlock(vegaLiteSpec = ChartSpecCodec.encode(current)))
                        } else {
                            creating = true
                            val backgroundArgb = colorScheme.surface.toArgb()
                            val inkArgb = colorScheme.onSurface.toArgb()
                            val fallbackAccentArgb = colorScheme.primary.toArgb()
                            scope.launch {
                                val uri = renderChartImage(context, current, backgroundArgb, inkArgb, fallbackAccentArgb)
                                creating = false
                                onCreate(ImageBlock(source = uri.toString(), caption = current.title, displayHeightDp = 260f))
                            }
                        }
                    },
                    modifier = Modifier.weight(1f),
                    enabled = !creating && rows.isNotEmpty(),
                ) { Text(if (creating) "Rendering…" else "Create") }
            }
            Spacer(Modifier.height(20.dp))
        }
    }
}

/**
 * The legend toggle: a label and a switch, activated anywhere along the row.
 *
 * Before, the `Text` and the `Switch` were two independent children of a plain `Row` with nothing joining them,
 * so a screen reader heard "Legend" and then, separately, an unnamed switch — the name on one node, the role and
 * state on another. [com.norfold.app.ui.toggleableChrome] on the row makes it one node carrying all three, and the
 * [RowSwitch] becomes decorative. [com.norfold.app.ui.components.SettingsToggleRow] documents why passing `null`
 * is what makes that work, and why a 48 dp floor is needed at all: Material gates the switch's whole interaction
 * modifier — `minimumInteractiveComponentSize` included — on that handler being non-null. That modifier applies
 * the floor, so this call site does not restate it.
 *
 * The floor is load-bearing rather than defensive. This row has no padding of its own, so the switch's own
 * touch-target box was the only thing holding it open; without the constraint the line would collapse toward the
 * 32 dp the switch draws.
 *
 * The ring is inward at [Corner.Media] and measured against whatever [com.norfold.app.ui.LocalChromeContainer]
 * names, which [NorfoldBottomSheet] provides from `BottomSheetDefaults.ContainerColor` — so nothing here states
 * a colour, and the row stays correct if the sheet's container token changes.
 */
@Composable
private fun LegendToggleRow(checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .toggleableChrome(
                checked = checked,
                corner = ChromeGeometry.Corner.Media,
                role = Role.Switch,
                onValueChange = onChange,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Legend", Modifier.weight(1f))
        RowSwitch(checked, null)
    }
}

/**
 * One of the two chart-placement choices: a radio indicator and its label, as a single node.
 *
 * Both halves used to be live — the enclosing `Row` was `clickable` and the [RadioButton] had its own `onClick`
 * — which produced two accessibility nodes for one option: a row carrying "As image" with no selection state,
 * then an indicator carrying the state with no name. Compose does not fold the second into the first, because
 * its merge walk stops descending at any child that merges and every Material selection control merges.
 * [com.norfold.app.ui.selectableChrome] gives one node with the label as its name, `Role.RadioButton` as its
 * role, and the selection as its state; `selectableGroup` on the parent row then announces the pair as one
 * exclusive choice with a position rather than as two unrelated controls.
 *
 * The indicator keeps drawing from [selected]; only its interaction is gone, which also removes the 48 dp floor
 * Material was attaching to it. That modifier restores the floor as a minimum rather than a fixed height, which
 * is what this row needs: the label wraps to two lines on a narrow sheet ("As editable chart" beside a 20 dp
 * indicator in half the sheet's width).
 */
@Composable
private fun PlacementOption(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onSelect: () -> Unit,
) {
    Row(
        modifier
            .selectableChrome(
                selected = selected,
                corner = ChromeGeometry.Corner.Media,
                onClick = onSelect,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected, null)
        Text(label)
    }
}

/**
 * The per-row colour override: a disc showing the row's current colour, which opens a menu.
 *
 * **Not a [NorfoldColorSwatch]**, despite looking like one. A swatch is one option among several and
 * announces itself as a radio button; this is a single button that opens a menu, so it takes the ring and the
 * boundary from the same reasoning but keeps button semantics and a label describing the *action*.
 *
 * The fallback fill is the chart accent at 35% alpha, which is why the boundary is solved rather than picked:
 * [NorfoldChrome.restingEdge] composites the translucent fill onto the page before measuring it, so the edge
 * is graded against the pixel actually painted rather than against a colour that is never drawn.
 */
@Composable
private fun RowColorSwatch(
    rowColor: String,
    palette: List<NamedColor>,
    fallback: Color,
    onChange: (String) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val current = runCatching { Color(android.graphics.Color.parseColor(rowColor)) }.getOrNull()
    val fill = current ?: fallback.copy(alpha = .35f)
    val focus = rememberChromeFocus()
    Box {
        Box(
            // The ring sits outside `minimumInteractiveComponentSize` so it measures the expanded 48 dp box
            // rather than the 30 dp disc, and `size` comes last so it cannot fight that expansion.
            Modifier
                .then(focus.ringOnCircle(RowSwatchSize, fill))
                .minimumInteractiveComponentSize()
                .size(RowSwatchSize.dp)
                .clip(CircleShape)
                .background(fill)
                .border(ChromeGeometry.RESTING_EDGE_WIDTH.dp, with(NorfoldChrome) { restingEdge(fill) }, CircleShape)
                .clickable(
                    interactionSource = focus.source,
                    indication = ripple(),
                    // Names the colour as well as the action: the disc is the only thing stating which colour
                    // this row draws in, so a listener who cannot see it would otherwise have no way to know.
                    onClickLabel = "Change row color",
                    onClick = { open = true },
                )
                .semantics {
                    contentDescription = if (current == null) {
                        "Row color: chart default"
                    } else {
                        "Row color: ${current.asColorValue().accessibleLabel()}"
                    }
                },
        )
        NorfoldDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text("Default") },
                leadingIcon = { NorfoldColorDot(fallback.copy(alpha = .35f)) },
                onClick = { onChange(""); open = false },
            )
            palette.forEach { candidate ->
                DropdownMenuItem(
                    // The name, not the hex. `#FF6750A4` is accurate and tells a listener nothing, and the
                    // dot beside it is decorative — this text is the only thing naming the option.
                    text = { Text(candidate.name) },
                    leadingIcon = { NorfoldColorDot(candidate.color) },
                    onClick = { onChange(candidate.color.toRgbHex()); open = false },
                )
            }
        }
    }
}

/** A colour paired with what it is, so a swatch showing it can be described rather than spelled in hex. */
private data class NamedColor(val color: Color, val name: String)

/** The row-override trigger's diameter, named because the focus ring's radius derives from it. */
private const val RowSwatchSize = 30f

@Composable
private fun VisualChartComposer(
    type: ChartType,
    rows: List<ChartDataRow>,
    color: String,
    onAdd: () -> Unit,
) {
    val accent = runCatching { Color(android.graphics.Color.parseColor(color)) }.getOrDefault(MaterialTheme.colorScheme.primary)
    val surfaceColor = MaterialTheme.colorScheme.surface
    fun rowColor(row: ChartDataRow, default: Color): Color =
        runCatching { Color(android.graphics.Color.parseColor(row.color)) }.getOrDefault(default)
    val values = rows.map { it.value.toFloatOrNull()?.coerceAtLeast(0f) ?: 0f }
    val maximum = (values.maxOrNull() ?: 1f).coerceAtLeast(1f)
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Box(Modifier.fillMaxWidth().height(190.dp).padding(14.dp)) {
            Canvas(Modifier.fillMaxWidth().height(160.dp)) {
                val left = 16.dp.toPx()
                val top = 10.dp.toPx()
                val right = size.width - 16.dp.toPx()
                val bottom = size.height - 14.dp.toPx()
                when (type) {
                    ChartType.Pie -> {
                        val radius = minOf(size.width, size.height) * 0.36f
                        val center = center
                        val total = values.sum().takeIf { it > 0f } ?: rows.size.coerceAtLeast(1).toFloat()
                        var start = -90f
                        rows.ifEmpty { listOf(ChartDataRow("Section 1", "1")) }.forEachIndexed { index, row ->
                            val value = row.value.toFloatOrNull()?.takeIf { it > 0f } ?: 1f
                            val sweep = value / total * 360f
                            drawArc(
                                color = rowColor(row, accent.copy(alpha = (1f - index * 0.12f).coerceAtLeast(0.34f))),
                                startAngle = start,
                                sweepAngle = sweep,
                                useCenter = true,
                                topLeft = androidx.compose.ui.geometry.Offset(center.x - radius, center.y - radius),
                                size = androidx.compose.ui.geometry.Size(radius * 2f, radius * 2f),
                            )
                            start += sweep
                        }
                        drawCircle(surfaceColor, radius = 23.dp.toPx(), center = center)
                        drawCircle(accent, radius = 23.dp.toPx(), center = center, style = Stroke(1.5.dp.toPx()))
                    }
                    ChartType.Bar, ChartType.Histogram -> {
                        val slot = (right - left) / rows.size.coerceAtLeast(1)
                        rows.forEachIndexed { index, row ->
                            val value = row.value.toFloatOrNull() ?: 0f
                            val y = bottom - (value / maximum) * (bottom - top)
                            drawRoundRect(
                                rowColor(row, accent.copy(alpha = 0.85f)),
                                topLeft = androidx.compose.ui.geometry.Offset(left + slot * index + slot * .18f, y),
                                size = androidx.compose.ui.geometry.Size(slot * .64f, bottom - y),
                                cornerRadius = androidx.compose.ui.geometry.CornerRadius(7.dp.toPx()),
                            )
                        }
                    }
                    ChartType.Line, ChartType.Area -> {
                        val slot = (right - left) / rows.size.coerceAtLeast(1)
                        val path = androidx.compose.ui.graphics.Path()
                        rows.forEachIndexed { index, row ->
                            val x = left + slot * (index + .5f)
                            val y = bottom - ((row.value.toFloatOrNull() ?: 0f) / maximum) * (bottom - top)
                            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
                            drawCircle(accent, 5.dp.toPx(), androidx.compose.ui.geometry.Offset(x, y))
                        }
                        drawPath(path, accent, style = Stroke(3.dp.toPx()))
                    }
                    ChartType.Scatter -> {
                        val slot = (right - left) / rows.size.coerceAtLeast(1)
                        rows.forEachIndexed { index, row ->
                            val x = left + slot * (index + .5f)
                            val y = bottom - ((row.value.toFloatOrNull() ?: 0f) / maximum) * (bottom - top)
                            drawCircle(rowColor(row, accent.copy(alpha = .82f)), 7.dp.toPx(), androidx.compose.ui.geometry.Offset(x, y))
                        }
                    }
                }
            }
            TextButton(onClick = onAdd, modifier = Modifier.align(if (type == ChartType.Pie) Alignment.Center else Alignment.TopEnd)) {
                Text(if (type == ChartType.Pie) "+" else "+ Add point", fontWeight = FontWeight.Black)
            }
        }
    }
}

private suspend fun renderChartImage(
    context: Context,
    model: ChartBuilderModel,
    backgroundArgb: Int,
    inkArgb: Int,
    fallbackAccentArgb: Int,
): Uri = withContext(Dispatchers.Default) {
    val width = 1200
    val height = 720
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = AndroidCanvas(bitmap)
    val background = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = backgroundArgb }
    val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = inkArgb; textSize = 28f }
    val accent = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = runCatching { android.graphics.Color.parseColor(model.color) }.getOrDefault(fallbackAccentArgb); strokeWidth = 7f; style = Paint.Style.FILL }
    canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), background)
    ink.textSize = 42f; ink.isFakeBoldText = true; canvas.drawText(model.title, 72f, 72f, ink)
    ink.isFakeBoldText = false
    if (model.caption.isNotBlank()) {
        ink.textSize = 26f; ink.alpha = 170
        canvas.drawText(model.caption, 72f, 112f, ink)
        ink.alpha = 255
    }
    ink.textSize = 25f
    val baseAccentArgb = accent.color
    fun rowArgb(row: ChartDataRow): Int? =
        runCatching { android.graphics.Color.parseColor(row.color) }.getOrNull()
    val left = 100f; val top = 150f; val right = 1120f; val bottom = 620f
    canvas.drawLine(left, bottom, right, bottom, ink); canvas.drawLine(left, top, left, bottom, ink)
    val values = model.rows.map { it.value.toFloatOrNull() ?: 0f }
    val max = (values.maxOrNull() ?: 1f).coerceAtLeast(1f)
    val slot = (right - left) / model.rows.size.coerceAtLeast(1)
    when (model.type) {
        ChartType.Line, ChartType.Area -> {
            val path = Path()
            model.rows.forEachIndexed { index, row ->
                val x = left + slot * (index + .5f); val y = bottom - ((row.value.toFloatOrNull() ?: 0f) / max) * (bottom - top)
                if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
                canvas.drawCircle(x, y, 10f, accent)
            }
            accent.style = Paint.Style.STROKE; canvas.drawPath(path, accent); accent.style = Paint.Style.FILL
        }
        ChartType.Scatter -> model.rows.forEachIndexed { index, row ->
            accent.color = rowArgb(row) ?: baseAccentArgb
            val x = left + slot * (index + .5f); val y = bottom - ((row.value.toFloatOrNull() ?: 0f) / max) * (bottom - top); canvas.drawCircle(x, y, 16f, accent)
        }
        ChartType.Pie -> {
            val total = values.sum().coerceAtLeast(1f); var start = -90f
            model.rows.forEachIndexed { index, row ->
                accent.color = rowArgb(row) ?: rotateColor(baseAccentArgb, index); val sweep = ((row.value.toFloatOrNull() ?: 0f) / total) * 360f
                canvas.drawArc(330f, 170f, 870f, 690f, start, sweep, true, accent); start += sweep
            }
        }
        ChartType.Bar, ChartType.Histogram -> model.rows.forEachIndexed { index, row ->
            accent.color = rowArgb(row) ?: baseAccentArgb
            val value = row.value.toFloatOrNull() ?: 0f; val x = left + slot * index + slot * .18f; val barRight = x + slot * .64f; val y = bottom - (value / max) * (bottom - top)
            canvas.drawRoundRect(x, y, barRight, bottom, 12f, 12f, accent); canvas.drawText(row.label.take(12), x, bottom + 34f, ink)
        }
    }
    val directory = File(context.filesDir, "chart-images").apply { mkdirs() }
    val output = File(directory, "${UUID.randomUUID()}.png")
    withContext(Dispatchers.IO) { FileOutputStream(output).use { bitmap.compress(Bitmap.CompressFormat.PNG, 96, it) } }
    bitmap.recycle()
    Uri.fromFile(output)
}

private fun rotateColor(base: Int, index: Int): Int {
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(base, hsv)
    hsv[0] = (hsv[0] + index * 47f) % 360f
    return android.graphics.Color.HSVToColor(hsv)
}

private fun Color.toRgbHex(): String = "#%06X".format(toArgb() and 0x00FFFFFF)
