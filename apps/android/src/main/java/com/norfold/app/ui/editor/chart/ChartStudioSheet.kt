package com.norfold.app.ui.editor.chart

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.AreaChart
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.DataObject
import androidx.compose.material.icons.outlined.DonutLarge
import androidx.compose.material.icons.outlined.InsertChart
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.ShowChart
import androidx.compose.material.icons.outlined.TableChart
import androidx.compose.material.icons.outlined.Label
import androidx.compose.material.icons.outlined.List
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.ui.text.input.ImeAction
import com.norfold.app.domain.style.ColorValue
import com.norfold.app.domain.style.ColorDomain
import com.norfold.app.domain.style.ColorPaint
import com.norfold.app.domain.style.ColorRole
import com.norfold.app.domain.style.ColorTarget
import com.norfold.app.editor.chart.ChartAxisPosition
import com.norfold.app.editor.chart.ChartAxis
import com.norfold.app.editor.chart.ChartCalculationDataProvider
import com.norfold.app.editor.chart.ChartCommitHost
import com.norfold.app.editor.chart.ChartDataRow
import com.norfold.app.editor.chart.ChartDataSource
import com.norfold.app.editor.chart.ChartDocument
import com.norfold.app.editor.chart.ChartDirectEditor
import com.norfold.app.editor.chart.ChartLegendPosition
import com.norfold.app.editor.chart.ChartLinePattern
import com.norfold.app.editor.chart.ChartMarkOverride
import com.norfold.app.editor.chart.ChartSeries
import com.norfold.app.editor.chart.ChartStudioRuntime
import com.norfold.app.editor.chart.ChartStyle
import com.norfold.app.editor.chart.ChartType
import com.norfold.app.editor.style.ColorStudioEngine
import com.norfold.app.editor.style.ColorStudioHost
import com.norfold.app.editor.style.ColorStudioInteractionController
import com.norfold.app.editor.style.EditorStyleUiState
import com.norfold.app.ui.components.NorfoldColorDot
import com.norfold.app.ui.components.NorfoldColorSwatch
import com.norfold.app.ui.editor.style.EditorStyleSurface
import com.norfold.app.ui.editor.style.EditorStyleSurfaceActions
import com.norfold.app.ui.rememberChromeFocus

private enum class ChartStudioSection(val label: String) {
    DATA("Data"), TYPE("Type"), SERIES("Series"), AXES("Axes"), LABELS("Labels"), LEGEND("Legend"), STYLE("Style")
}

@Composable
private fun RowScope.ChartTypeButton(
    label: String,
    icon: ImageVector,
    type: ChartType,
    selectedType: ChartType,
    readOnly: Boolean,
    onClick: () -> Unit,
) {
    val selected = selectedType == type
    val fill = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface
    val focus = rememberChromeFocus()
    Surface(
        onClick = onClick,
        enabled = !readOnly,
        shape = RoundedCornerShape(10.dp),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
        ),
        color = fill,
        interactionSource = focus.source,
        modifier = Modifier.weight(1f).height(68.dp).then(focus.ringOnShape(10f, fill)),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Icon(icon, null, Modifier.size(24.dp))
            Text(label, style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun ChartSettingRow(icon: ImageVector, title: String, detail: String, onClick: () -> Unit) {
    val focus = rememberChromeFocus()
    Surface(
        onClick = onClick,
        color = Color.Transparent,
        interactionSource = focus.source,
        // Radius 0 because this `Surface` passes no `shape` and so draws `RectangleShape` — the ring is
        // square because the chrome is. Measured on the card's `surface`, which is what a transparent row
        // inside that card actually shows.
        modifier = Modifier.fillMaxWidth().then(focus.ringOnShape(0f, MaterialTheme.colorScheme.surface)),
    ) {
        Row(Modifier.padding(vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, Modifier.size(28.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(detail, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text("›", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun chartDataSummary(document: ChartDocument): String = when (val source = document.source) {
    is ChartDataSource.Manual -> "${source.rows.size} rows · ${source.columns.size} columns"
    is ChartDataSource.CalculationRange -> "Linked workbook range"
}

private data class ChartFieldGesture(
    val begin: () -> Boolean,
    val preview: ((ChartDocument) -> ChartDocument) -> Boolean,
    val finish: (String) -> Boolean,
    val cancel: () -> Boolean,
)

/** One shared Studio surface for every host. All sections edit the same canonical [ChartDocument]. */
@Composable
fun ChartStudioSheet(
    initial: ChartDocument,
    readOnly: Boolean,
    calculationProvider: ChartCalculationDataProvider? = null,
    onDocumentChanged: (ChartDocument) -> Boolean,
    onRequestRangeBinding: (() -> Unit)? = null,
    onOpenGuide: (() -> Unit)? = null,
    onDirectManipulation: (() -> Unit)? = null,
    onDismiss: () -> Unit,
    onDone: (ChartDocument) -> Unit = { onDismiss() },
) {
    val latestCommit by rememberUpdatedState(onDocumentChanged)
    val runtime = remember(initial.id, readOnly, calculationProvider) {
        ChartStudioRuntime(
            initial,
            ChartCommitHost { _, document -> latestCommit(document) },
            readOnly,
            calculationProvider,
        )
    }
    var revision by remember { mutableIntStateOf(0) }
    var sectionOrdinal by remember { mutableIntStateOf(0) }
    val colorStudio = remember(runtime) { ChartColorStudioController(runtime) { revision++ } }
    val section = ChartStudioSection.entries[sectionOrdinal]
    val document = remember(runtime, revision) { runtime.document() }
    fun edit(label: String, transform: (ChartDocument) -> ChartDocument) {
        if (runtime.commit(label, transform)) revision++
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(
                Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = {
                    if (colorStudio.engine.isOpen) colorStudio.close(commit = false)
                    runtime.cancelGesture()
                    onDismiss()
                }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") }
                Text("Chart Studio", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f).padding(start = 8.dp))
                TextButton(onClick = {
                    if (colorStudio.engine.isOpen) colorStudio.close(commit = true)
                    if (runtime.hasGesture()) runtime.endGesture("Finish chart edit")
                    onDone(runtime.document())
                }) { Text("Done") }
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.Lock, null, tint = MaterialTheme.colorScheme.primary)
                Column {
                    Text("Chart preview is locked", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                    Text("Edits update settings below.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                ChartRenderer(
                    document,
                    runtime.resolve(),
                    Modifier.fillMaxWidth(),
                    heightDp = 260,
                    compactChrome = true,
                    onRebind = onRequestRangeBinding,
                    directManipulation = if (!readOnly && document.source is ChartDataSource.Manual) {
                    ChartDirectManipulationCallbacks(
                        onSelect = { seriesId, rowIndex, rowId ->
                            if (runtime.selectMark(seriesId, rowIndex, rowId)) revision++
                        },
                        onStart = { seriesId, rowIndex, rowId ->
                            val selected = runtime.selectMark(seriesId, rowIndex, rowId)
                            if (selected) revision++
                            selected && runtime.beginGesture()
                        },
                        onPreview = { seriesId, rowIndex, value ->
                            if (runtime.previewGesture { current ->
                                    ChartDirectEditor.updateValue(current, seriesId, rowIndex, value.toString()) ?: current
                                }
                            ) revision++
                        },
                        onEnd = { if (runtime.endGesture("Adjust chart value")) { revision++; onDirectManipulation?.invoke() } },
                        onCancel = { if (runtime.cancelGesture()) revision++ },
                        onStyle = { seriesId, rowIndex, rowId ->
                            if (runtime.selectMark(seriesId, rowIndex, rowId)) {
                                revision++
                                rowId?.let { colorStudio.openMark(seriesId, it) }
                            }
                        },
                    )
                    } else null,
                )
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 1.dp,
                    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                ) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Icon(Icons.Outlined.InsertChart, null)
                        Text("Chart type", style = MaterialTheme.typography.titleMedium)
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        ChartTypeButton("Line", Icons.Outlined.ShowChart, ChartType.LINE, document.type, readOnly) { edit("Change chart type") { it.copy(type = ChartType.LINE) } }
                        ChartTypeButton("Area", Icons.Outlined.AreaChart, ChartType.AREA, document.type, readOnly) { edit("Change chart type") { it.copy(type = ChartType.AREA) } }
                        ChartTypeButton("Bar", Icons.Outlined.BarChart, ChartType.BAR, document.type, readOnly) { edit("Change chart type") { it.copy(type = ChartType.BAR) } }
                        ChartTypeButton("Column", Icons.Outlined.InsertChart, ChartType.STACKED_BAR, document.type, readOnly) { edit("Change chart type") { it.copy(type = ChartType.STACKED_BAR) } }
                        ChartTypeButton("Donut", Icons.Outlined.DonutLarge, ChartType.DONUT, document.type, readOnly) { edit("Change chart type") { it.copy(type = ChartType.DONUT) } }
                        ChartTypeButton("Table", Icons.Outlined.TableChart, ChartType.BAR, document.type, readOnly) { sectionOrdinal = ChartStudioSection.DATA.ordinal }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    ChartSettingRow(Icons.Outlined.DataObject, "Data", chartDataSummary(document)) { sectionOrdinal = ChartStudioSection.DATA.ordinal }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    ChartSettingRow(Icons.Outlined.Palette, "Appearance", "Colors, line style, gridlines") { sectionOrdinal = ChartStudioSection.STYLE.ordinal }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    ChartSettingRow(Icons.Outlined.List, "Legend", if (document.legend.visible) "Visible" else "Hidden") { sectionOrdinal = ChartStudioSection.LEGEND.ordinal }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    ChartSettingRow(Icons.Outlined.Label, "Labels", "Axis titles, data labels") { sectionOrdinal = ChartStudioSection.LABELS.ordinal }
                }
                }
            if (colorStudio.engine.isOpen) {
                EditorStyleSurface(
                    surfaceId = "editor.surface.colorStudio",
                    state = EditorStyleUiState(
                        colorStudio = colorStudio.engine.state(),
                        documentColors = (document.series.map { it.color } + document.style.palette).distinct(),
                        recentColors = colorStudio.engine.recentColors(),
                    ),
                    fontRows = emptyList(),
                    actions = chartColorActions(colorStudio),
                    onOpenGuide = onOpenGuide,
                )
            } else when (section) {
                ChartStudioSection.DATA -> DataSection(
                    document,
                    readOnly,
                    onRequestRangeBinding,
                    ChartFieldGesture(
                        begin = runtime::beginGesture,
                        preview = { transform -> runtime.previewGesture(transform).also { if (it) revision++ } },
                        finish = { label -> runtime.endGesture(label).also { if (it) revision++ } },
                        cancel = { runtime.cancelGesture().also { if (it) revision++ } },
                    ),
                    onSelectSection = { rowIndex ->
                        val rowId = (runtime.document().source as? ChartDataSource.Manual)?.rows?.getOrNull(rowIndex)?.id
                        val selected = runtime.document().series.firstOrNull()?.id
                            ?.let { runtime.selectMark(it, rowIndex, rowId) } == true
                        if (selected) revision++
                    },
                ) { label, transform -> edit(label, transform) }
                ChartStudioSection.TYPE -> TypeSection(document, readOnly) { type -> edit("Change chart type") { it.copy(type = type) } }
                ChartStudioSection.SERIES -> SeriesSection(
                    document,
                    readOnly,
                    onSelect = { if (runtime.selectSeries(it)) revision++ },
                    onEditColor = colorStudio::openSeries,
                ) { next -> edit("Edit chart series") { next } }
                ChartStudioSection.AXES -> AxesSection(document, readOnly) { next -> edit("Edit chart axes") { next } }
                ChartStudioSection.LABELS -> LabelsSection(document, readOnly) { next -> edit("Edit chart labels") { next } }
                ChartStudioSection.LEGEND -> LegendSection(document, readOnly) { next -> edit("Edit chart legend") { next } }
                ChartStudioSection.STYLE -> StyleSection(document, readOnly, colorStudio::openPaletteLead) { next -> edit("Edit chart style") { next } }
            }
            Spacer(Modifier.height(12.dp))
            Text("Changes apply to this chart only.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun DataSection(
    document: ChartDocument,
    readOnly: Boolean,
    onRequestRangeBinding: (() -> Unit)?,
    fieldGesture: ChartFieldGesture,
    onSelectSection: (Int) -> Unit,
    edit: (String, (ChartDocument) -> ChartDocument) -> Unit,
) {
    val source = document.source
    OutlinedTextField(
        value = document.title,
        onValueChange = { value -> edit("Edit chart title") { it.copy(title = value) } },
        enabled = !readOnly,
        label = { Text("Title") },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
    )
    OutlinedTextField(
        value = document.subtitle,
        onValueChange = { value -> edit("Edit chart subtitle") { it.copy(subtitle = value) } },
        enabled = !readOnly,
        label = { Text("Subtitle") },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
    )
    OutlinedTextField(
        value = document.caption,
        onValueChange = { value -> edit("Edit chart caption") { it.copy(caption = value) } },
        enabled = !readOnly,
        label = { Text("Caption") },
        modifier = Modifier.fillMaxWidth(),
        minLines = 2,
    )
    OutlinedTextField(
        value = document.description,
        onValueChange = { value -> edit("Edit chart description") { it.copy(description = value) } },
        enabled = !readOnly,
        label = { Text("Accessible description") },
        supportingText = { Text("Explain the chart's meaning without relying on color or shape.") },
        modifier = Modifier.fillMaxWidth(),
        minLines = 2,
    )
    Text(if (source is ChartDataSource.Manual) "Embedded data" else "Calculation range", style = MaterialTheme.typography.titleMedium)
    if (source is ChartDataSource.Manual) {
        var gridDraft by remember(document.id) { mutableStateOf("") }
        val categoryIndex = source.columns.indexOfFirst { it.id == document.encoding.categoryColumnId }.coerceAtLeast(0)
        val valueIndex = source.columns.indexOfFirst { it.id == document.series.firstOrNull()?.valueColumnId }.coerceAtLeast(1)
        source.rows.take(100).forEachIndexed { rowIndex, row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ChartDataCellField(
                    value = row.values.getOrNull(categoryIndex).orEmpty(),
                    label = { Text("Category ${rowIndex + 1}") },
                    enabled = !readOnly,
                    modifier = Modifier.weight(1f),
                    gesture = fieldGesture,
                    transform = { value -> { it.updateManualCell(rowIndex, categoryIndex, value) } },
                )
                ChartDataCellField(
                    value = row.values.getOrNull(valueIndex).orEmpty(),
                    label = { Text("Value ${rowIndex + 1}") },
                    enabled = !readOnly,
                    modifier = Modifier.weight(1f),
                    gesture = fieldGesture,
                    transform = { value -> { it.updateManualCell(rowIndex, valueIndex, value) } },
                    validator = { it.toDoubleOrNull() != null },
                )
            }
            TextButton(
                onClick = {
                    edit("Remove chart row") { current ->
                        val manual = current.source as ChartDataSource.Manual
                        current.copy(source = manual.copy(rows = manual.rows.filterIndexed { index, _ -> index != rowIndex }))
                    }
                },
                enabled = !readOnly,
            ) { Text("Remove row ${rowIndex + 1}") }
            if (document.type == ChartType.PIE || document.type == ChartType.DONUT) {
                FilterChip(
                    selected = document.interaction.selectedSourceRowIndex == rowIndex,
                    onClick = { onSelectSection(rowIndex) },
                    enabled = !readOnly,
                    label = { Text("Select section ${rowIndex + 1}") },
                )
            }
        }
        if (source.rows.size > 100) {
            Text("Showing the first 100 of ${source.rows.size} rows in the precise editor. The full source remains stored.", style = MaterialTheme.typography.bodySmall)
        }
        val circular = document.type == ChartType.PIE || document.type == ChartType.DONUT
        OutlinedButton(
            onClick = {
                edit("Add chart row") { current ->
                    val manual = current.source as ChartDataSource.Manual
                    val rowNumber = manual.rows.size + 1
                    current.copy(source = manual.copy(rows = manual.rows + ChartDataRow(values = List(manual.columns.size) { column ->
                        when {
                            circular && column == categoryIndex -> "Section $rowNumber"
                            circular && column == valueIndex -> "1"
                            else -> ""
                        }
                    })))
                }
            },
            enabled = !readOnly,
        ) { Text(if (circular) "Add section" else "Add row") }
        OutlinedTextField(
            value = gridDraft,
            onValueChange = { gridDraft = it.take(200_000) },
            enabled = !readOnly,
            label = { Text("Paste tab-separated data") },
            supportingText = { Text("One line per row; tabs separate columns. Applying is one undo step.") },
            modifier = Modifier.fillMaxWidth(),
            minLines = 3,
        )
        Button(
            onClick = {
                val parsed = parseTabularData(gridDraft)
                if (parsed.isNotEmpty()) {
                    edit("Paste chart data") { current ->
                        val manual = current.source as ChartDataSource.Manual
                        val width = parsed.maxOf(List<String>::size).coerceAtMost(256)
                        val columns = List(width) { index ->
                            manual.columns.getOrNull(index) ?: com.norfold.app.editor.chart.ChartColumn(
                                id = "pasted-column-${java.util.UUID.randomUUID()}",
                                name = "Column ${index + 1}",
                            )
                        }
                        current.copy(source = manual.copy(
                            columns = columns,
                            rows = parsed.take(100_000).map { values -> ChartDataRow(values = values.take(width)) },
                        ))
                    }
                    gridDraft = ""
                }
            },
            enabled = !readOnly && gridDraft.isNotBlank(),
        ) { Text("Apply pasted data") }
    } else {
        Text("The stable range identity is preserved even if its sheet or cells are removed.")
        val range = source as ChartDataSource.CalculationRange
        Text("Headers", style = MaterialTheme.typography.labelLarge)
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            com.norfold.app.editor.chart.ChartHeaderMode.entries.forEach { mode ->
                FilterChip(
                    selected = range.headerMode == mode,
                    onClick = { edit("Change chart headers") { it.copy(source = range.copy(headerMode = mode)) } },
                    enabled = !readOnly,
                    label = { Text(mode.name.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }) },
                )
            }
        }
        Text("Orientation", style = MaterialTheme.typography.labelLarge)
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            com.norfold.app.editor.chart.ChartDataOrientation.entries.forEach { orientation ->
                FilterChip(
                    selected = range.orientation == orientation,
                    onClick = { edit("Change chart orientation") { it.copy(source = range.copy(orientation = orientation)) } },
                    enabled = !readOnly,
                    label = { Text(orientation.name.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }) },
                )
            }
        }
        onRequestRangeBinding?.let { OutlinedButton(onClick = it, enabled = !readOnly) { Text("Rebind data") } }
    }
}

@Composable
private fun TypeSection(document: ChartDocument, readOnly: Boolean, onType: (ChartType) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ChartType.entries.chunked(2).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { type ->
                    FilterChip(
                        selected = document.type == type,
                        onClick = { onType(type) },
                        enabled = !readOnly,
                        label = { Text(type.name.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }) },
                        modifier = Modifier.weight(1f),
                    )
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun SeriesSection(
    document: ChartDocument,
    readOnly: Boolean,
    onSelect: (String) -> Unit,
    onEditColor: (ChartSeries) -> Unit,
    onChange: (ChartDocument) -> Unit,
) {
    val columns = (document.source as? ChartDataSource.Manual)?.columns.orEmpty()
    document.series.forEachIndexed { index, series ->
        FilterChip(
            selected = document.interaction.selectedSeriesId == series.id,
            onClick = { onSelect(series.id) },
            label = { Text(if (document.interaction.selectedSeriesId == series.id) "Selected" else "Select ${series.name}") },
        )
        Toggle("Show ${series.name}", series.visible, !readOnly) {
            onChange(document.copy(series = document.series.toMutableList().also { list -> list[index] = series.copy(visible = it) }))
        }
        OutlinedTextField(
            series.name,
            { name -> onChange(document.copy(series = document.series.toMutableList().also { it[index] = series.copy(name = name) })) },
            label = { Text("Series ${index + 1} name") },
            enabled = !readOnly,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        OutlinedButton(onClick = { onEditColor(series) }, enabled = !readOnly) {
            // Decorative: the button's own text is the target and the label. It still gets a boundary, because
            // a pale series colour against the button's surface has nothing else marking where the dot is.
            NorfoldColorDot(Color(series.color.toArgb()), size = SeriesDotSize)
            Text("Edit color", Modifier.padding(start = 8.dp))
        }
        if (columns.isNotEmpty()) {
            Text("Value column", style = MaterialTheme.typography.labelLarge)
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                columns.forEach { column ->
                    FilterChip(
                        selected = series.valueColumnId == column.id,
                        onClick = {
                            onChange(document.copy(series = document.series.toMutableList().also { list ->
                                list[index] = series.copy(valueColumnId = column.id)
                            }))
                        },
                        enabled = !readOnly,
                        label = { Text(column.name) },
                    )
                }
            }
        }
        Text("Line pattern", style = MaterialTheme.typography.labelLarge)
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ChartLinePattern.entries.forEach { pattern ->
                FilterChip(
                    selected = series.linePattern == pattern,
                    onClick = {
                        onChange(document.copy(series = document.series.toMutableList().also { list ->
                            list[index] = series.copy(linePattern = pattern)
                        }))
                    },
                    enabled = !readOnly,
                    label = { Text(pattern.name.lowercase().replaceFirstChar { it.uppercase() }) },
                )
            }
        }
        Text("Mark type", style = MaterialTheme.typography.labelLarge)
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(null, ChartType.BAR, ChartType.LINE, ChartType.AREA, ChartType.SCATTER).forEach { markType ->
                FilterChip(
                    selected = series.markType == markType,
                    onClick = {
                        onChange(document.copy(series = document.series.toMutableList().also { list ->
                            list[index] = series.copy(markType = markType)
                        }))
                    },
                    enabled = !readOnly,
                    label = { Text(markType?.name?.lowercase()?.replaceFirstChar(Char::uppercase) ?: "Inherit") },
                )
            }
        }
        Text("Value axis", style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("y" to "Primary", "y2" to "Secondary").forEach { (id, label) ->
                FilterChip(
                    selected = series.axisId == id,
                    onClick = {
                        val axes = if (id == "y2" && document.axes.y2 == null) {
                            document.axes.copy(y2 = ChartAxis("y2", "Value 2", ChartAxisPosition.RIGHT))
                        } else document.axes
                        onChange(document.copy(
                            axes = axes,
                            series = document.series.toMutableList().also { list -> list[index] = series.copy(axisId = id) },
                        ))
                    },
                    enabled = !readOnly,
                    label = { Text(label) },
                )
            }
        }
        Text("Series opacity ${(((series.opacity ?: 1f) * 100).toInt())}%")
        Slider(
            value = series.opacity ?: 1f,
            onValueChange = { value -> onChange(document.copy(series = document.series.toMutableList().also { it[index] = series.copy(opacity = value) })) },
            enabled = !readOnly,
        )
        if ((series.markType ?: document.type) in setOf(ChartType.LINE, ChartType.AREA)) {
            Text("Stroke width ${series.strokeWidth ?: document.style.strokeWidth}")
            Slider(
                value = series.strokeWidth ?: document.style.strokeWidth,
                onValueChange = { value -> onChange(document.copy(series = document.series.toMutableList().also { it[index] = series.copy(strokeWidth = value) })) },
                valueRange = .5f..12f,
                enabled = !readOnly,
            )
        }
        if ((series.markType ?: document.type) in setOf(ChartType.LINE, ChartType.AREA, ChartType.SCATTER)) {
            Text("Point radius ${series.pointRadius ?: 5f}")
            Slider(
                value = series.pointRadius ?: 5f,
                onValueChange = { value -> onChange(document.copy(series = document.series.toMutableList().also { it[index] = series.copy(pointRadius = value) })) },
                valueRange = 2f..32f,
                enabled = !readOnly,
            )
        }
        if ((series.markType ?: document.type) in setOf(ChartType.BAR, ChartType.STACKED_BAR)) {
            Text("Bar width ${(((series.barWidthFraction ?: .86f) * 100).toInt())}%")
            Slider(
                value = series.barWidthFraction ?: .86f,
                onValueChange = { value -> onChange(document.copy(series = document.series.toMutableList().also { it[index] = series.copy(barWidthFraction = value) })) },
                valueRange = .1f..1f,
                enabled = !readOnly,
            )
        }
        if (document.series.size > 1) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { onChange(document.copy(series = document.series.move(index, index - 1))) },
                    enabled = !readOnly && index > 0,
                    modifier = Modifier.weight(1f),
                ) { Text("Move up") }
                OutlinedButton(
                    onClick = { onChange(document.copy(series = document.series.move(index, index + 1))) },
                    enabled = !readOnly && index < document.series.lastIndex,
                    modifier = Modifier.weight(1f),
                ) { Text("Move down") }
            }
            TextButton(
                onClick = { onChange(document.copy(series = document.series.filterIndexed { itemIndex, _ -> itemIndex != index })) },
                enabled = !readOnly,
            ) { Text("Remove ${series.name}") }
        }
    }
    val nextColumn = columns.firstOrNull { column ->
        column.id != document.encoding.categoryColumnId &&
            column.id != document.encoding.xColumnId &&
            column.kind != com.norfold.app.editor.chart.ChartColumnKind.TEXT &&
            document.series.none { it.valueColumnId == column.id }
    }
    OutlinedButton(
        onClick = {
            val index = document.series.size
            val color = document.style.palette[index % document.style.palette.size]
            onChange(document.copy(series = document.series + ChartSeries(
                id = "series-${java.util.UUID.randomUUID()}",
                name = "Series ${index + 1}",
                categoryColumnId = document.encoding.categoryColumnId,
                valueColumnId = nextColumn?.id ?: "value",
                color = color,
                linePattern = ChartLinePattern.entries[index % ChartLinePattern.entries.size],
            )))
        },
        enabled = !readOnly && document.series.size < 64 && nextColumn != null,
    ) { Text("Add series") }
}

@Composable
private fun AxesSection(document: ChartDocument, readOnly: Boolean, onChange: (ChartDocument) -> Unit) {
    OutlinedTextField(document.axes.x.title, { value -> onChange(document.copy(axes = document.axes.copy(x = document.axes.x.copy(title = value)))) }, label = { Text("Horizontal axis title") }, enabled = !readOnly, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(document.axes.y.title, { value -> onChange(document.copy(axes = document.axes.copy(y = document.axes.y.copy(title = value)))) }, label = { Text("Vertical axis title") }, enabled = !readOnly, modifier = Modifier.fillMaxWidth())
    Toggle("Show horizontal axis", document.axes.x.visible, !readOnly) { onChange(document.copy(axes = document.axes.copy(x = document.axes.x.copy(visible = it)))) }
    Toggle("Show vertical axis", document.axes.y.visible, !readOnly) { onChange(document.copy(axes = document.axes.copy(y = document.axes.y.copy(visible = it)))) }
    Toggle("Horizontal gridlines", document.axes.x.showGrid, !readOnly) { onChange(document.copy(axes = document.axes.copy(x = document.axes.x.copy(showGrid = it)))) }
    Toggle("Vertical gridlines", document.axes.y.showGrid, !readOnly) { onChange(document.copy(axes = document.axes.copy(y = document.axes.y.copy(showGrid = it)))) }
    Toggle("Secondary value axis", document.axes.y2 != null, !readOnly) { enabled ->
        val axes = document.axes.copy(y2 = if (enabled) ChartAxis("y2", "Value 2", ChartAxisPosition.RIGHT) else null)
        val series = if (enabled) document.series else document.series.map { it.copy(axisId = "y") }
        onChange(document.copy(axes = axes, series = series))
    }
    document.axes.y2?.let { secondary ->
        OutlinedTextField(
            secondary.title,
            { value -> onChange(document.copy(axes = document.axes.copy(y2 = secondary.copy(title = value)))) },
            label = { Text("Secondary axis title") },
            enabled = !readOnly,
            modifier = Modifier.fillMaxWidth(),
        )
        Toggle("Show secondary axis", secondary.visible, !readOnly) {
            onChange(document.copy(axes = document.axes.copy(y2 = secondary.copy(visible = it))))
        }
    }
}

@Composable
private fun LabelsSection(document: ChartDocument, readOnly: Boolean, onChange: (ChartDocument) -> Unit) {
    Toggle("Category labels", document.labels.showCategory, !readOnly) { onChange(document.copy(labels = document.labels.copy(showCategory = it))) }
    Toggle("Value labels", document.labels.showValue, !readOnly) { onChange(document.copy(labels = document.labels.copy(showValue = it))) }
    Toggle("Series names", document.labels.showSeriesName, !readOnly) { onChange(document.copy(labels = document.labels.copy(showSeriesName = it))) }
}

@Composable
private fun LegendSection(document: ChartDocument, readOnly: Boolean, onChange: (ChartDocument) -> Unit) {
    Toggle("Show legend", document.legend.visible, !readOnly) { onChange(document.copy(legend = document.legend.copy(visible = it))) }
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ChartLegendPosition.entries.forEach { position ->
            FilterChip(document.legend.position == position, { onChange(document.copy(legend = document.legend.copy(position = position))) }, { Text(position.name.lowercase().replaceFirstChar { it.uppercase() }) }, enabled = !readOnly)
        }
    }
}

@Composable
private fun StyleSection(
    document: ChartDocument,
    readOnly: Boolean,
    onEditPaletteLead: (ColorValue) -> Unit,
    onChange: (ChartDocument) -> Unit,
) {
    Text("Palette", style = MaterialTheme.typography.titleSmall)
    ColorSwatches(document.style.palette.firstOrNull(), readOnly) { color ->
        val old = document.style.palette
        val palette = listOf(color) + old.filterNot { it == color }
        onChange(document.copy(style = document.style.copy(palette = palette)))
    }
    document.style.palette.firstOrNull()?.let { lead ->
        OutlinedButton(onClick = { onEditPaletteLead(lead) }, enabled = !readOnly) { Text("Open Color Studio") }
    }
    Text("Opacity ${(document.style.opacity * 100).toInt()}%")
    Slider(document.style.opacity, { value -> onChange(document.copy(style = document.style.copy(opacity = value))) }, enabled = !readOnly)
    OutlinedButton(onClick = { onChange(document.copy(style = ChartStyle())) }, enabled = !readOnly) { Text("Reset style") }
}

/**
 * The chart's palette row.
 *
 * Was the tenth hand-rolled colour swatch in the app and is now a caller of [NorfoldColorSwatch]. Three things
 * changed in the collapse besides the ring, which this version already had:
 *
 * - **The label names the colour instead of spelling it.** It read `"Choose #FF6750A4"`, which is accurate and
 *   tells a listener nothing. `accessibleLabel()` is the domain's own answer — hue family plus hex, "purple
 *   #6750A4" — and it is what every other palette in the app now announces.
 * - **Selection is a state, not a border.** It was drawn only, as a 3 dp edge; a reader had no way to report
 *   which of the palette entries was the lead colour. `Role.RadioButton` in a `selectableGroup` states it.
 * - **The resting edge is unconditional.** An unselected entry drew no boundary at all, so a pale palette
 *   colour on the sheet's surface had nothing marking where the swatch was.
 *
 * It keeps its 48 dp diameter, which is larger than [SwatchSize] because this row is the chart's primary
 * colour control rather than an accent beside other content.
 */
@Composable
private fun ColorSwatches(selected: ColorValue?, readOnly: Boolean, onChoose: (ColorValue) -> Unit) {
    Row(
        // `selectableGroup`, so the palette announces as one exclusive choice rather than eight buttons.
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ChartStyle.defaultPalette().forEach { color ->
            NorfoldColorSwatch(
                color = Color(color.toArgb()),
                selected = selected == color,
                label = color.accessibleLabel(),
                onClick = { onChoose(color) },
                enabled = !readOnly,
                size = PaletteSwatchSize,
            )
        }
    }
}

/** The palette swatch's diameter: this row is the chart's primary colour control, so it is drawn large. */
private const val PaletteSwatchSize = 48f

/** The series colour dot's diameter: it sits inside a button beside a line of text, so it reads at 18 dp. */
private const val SeriesDotSize = 18f

@Composable
private fun Toggle(label: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked, onChange, enabled = enabled)
    }
}

private fun ChartDocument.updateManualCell(rowIndex: Int, columnIndex: Int, value: String): ChartDocument {
    val manual = source as? ChartDataSource.Manual ?: return this
    val row = manual.rows.getOrNull(rowIndex) ?: return this
    val values = row.values.toMutableList().apply { while (size <= columnIndex) add("") }
    values[columnIndex] = value
    val rows = manual.rows.toMutableList().also { it[rowIndex] = row.copy(values = values) }
    return copy(source = manual.copy(rows = rows))
}

@Composable
private fun ChartDataCellField(
    value: String,
    label: @Composable () -> Unit,
    enabled: Boolean,
    modifier: Modifier,
    gesture: ChartFieldGesture,
    transform: (String) -> (ChartDocument) -> ChartDocument,
    validator: (String) -> Boolean = { true },
) {
    val focusManager = LocalFocusManager.current
    var draft by remember { mutableStateOf(value) }
    var ownsGesture by remember { mutableStateOf(false) }
    DisposableEffect(ownsGesture) {
        onDispose { if (ownsGesture) gesture.cancel() }
    }
    LaunchedEffect(value, ownsGesture) { if (!ownsGesture) draft = value }
    fun finish() {
        if (ownsGesture) gesture.finish("Edit chart data")
        ownsGesture = false
    }
    OutlinedTextField(
        value = draft,
        onValueChange = { next ->
            draft = next
            if (ownsGesture) gesture.preview(transform(next))
        },
        enabled = enabled,
        label = label,
        modifier = modifier.onFocusChanged { focus ->
            if (focus.isFocused && !ownsGesture) ownsGesture = gesture.begin()
            else if (!focus.isFocused) finish()
        },
        singleLine = true,
        isError = !validator(draft),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { finish(); focusManager.clearFocus() }),
    )
}

private fun <T> List<T>.move(from: Int, to: Int): List<T> {
    if (from !in indices || to !in indices || from == to) return this
    return toMutableList().apply { add(to, removeAt(from)) }
}

private fun parseTabularData(raw: String): List<List<String>> = raw
    .lineSequence()
    .take(100_000)
    .map { line -> line.split('\t').take(256) }
    .filter { row -> row.any(String::isNotBlank) }
    .toList()

private class ChartColorStudioController(
    private val runtime: ChartStudioRuntime,
    private val onChanged: () -> Unit,
) {
    val engine = ColorStudioEngine(object : ColorStudioHost {
        override fun preview(target: ColorTarget, paint: ColorPaint) {
            val targetId = target.targetIds.singleOrNull()
            val next = apply(runtime.document(), targetId, paint.representative())
            if (next == runtime.document()) {
                onChanged()
                return
            }
            if (!runtime.hasGesture()) runtime.beginGesture()
            runtime.previewGesture { document -> apply(document, targetId, paint.representative()) }
            onChanged()
        }

        override fun commit(target: ColorTarget, paint: ColorPaint, label: String): Boolean {
            if (!runtime.hasGesture()) {
                runtime.beginGesture()
                runtime.previewGesture { document -> apply(document, target.targetIds.singleOrNull(), paint.representative()) }
            }
            return runtime.endGesture(label).also { onChanged() }
        }
    })
    val interactions = ColorStudioInteractionController(engine)

    fun openSeries(series: ChartSeries) = open("series:${series.id}", series.color)
    fun openMark(seriesId: String, rowId: String) {
        val document = runtime.document()
        val override = document.markOverrides.firstOrNull { it.seriesId == seriesId && it.sourceRowId == rowId }
        val color = override?.color ?: document.series.firstOrNull { it.id == seriesId }?.color ?: return
        open("mark:$seriesId:$rowId", color)
    }
    fun openPaletteLead(color: ColorValue) = open("palette:0", color)

    fun close(commit: Boolean) {
        if (commit) interactions.done() else {
            interactions.cancel()
            runtime.cancelGesture()
        }
        engine.close()
        onChanged()
    }

    fun cancelAdjustment() {
        interactions.cancelContinuousAdjustment()
        runtime.cancelGesture()
        onChanged()
    }

    private fun open(targetId: String, color: ColorValue) {
        engine.open(
            ColorTarget(ColorDomain.DATA_VISUALIZATION, ColorRole.CHART_SERIES, listOf(targetId), host = "chart"),
            ColorPaint.Flat(color),
        )
        onChanged()
    }

    private fun apply(document: ChartDocument, targetId: String?, color: ColorValue): ChartDocument = when {
        targetId?.startsWith("series:") == true -> {
            val id = targetId.removePrefix("series:")
            document.copy(series = document.series.map { if (it.id == id) it.copy(color = color) else it })
        }
        targetId?.startsWith("mark:") == true -> {
            val parts = targetId.removePrefix("mark:").split(':', limit = 2)
            if (parts.size != 2) document else {
                val (seriesId, rowId) = parts
                val others = document.markOverrides.filterNot { it.seriesId == seriesId && it.sourceRowId == rowId }
                document.copy(markOverrides = others + ChartMarkOverride(seriesId, rowId, color = color))
            }
        }
        targetId == "palette:0" -> document.copy(style = document.style.copy(
            palette = listOf(color) + document.style.palette.drop(1),
        ))
        else -> document
    }
}

private fun chartColorActions(controller: ChartColorStudioController): EditorStyleSurfaceActions =
    EditorStyleSurfaceActions(
        dismiss = controller::close,
        setColorView = { controller.cancelAdjustment(); controller.engine.setView(it) },
        draftColor = controller.engine::draft,
        commitColorDraft = { controller.engine.commitDraft(it) },
        discardColorDraft = { controller.engine.discardDraft(it) },
        beginColorAdjustment = { controller.interactions.beginContinuousAdjustment() },
        previewColor = { controller.engine.previewColor(it) },
        previewAlpha = { controller.engine.previewAlpha(it) },
        finishColorAdjustment = { controller.interactions.finishContinuousAdjustment(it) },
        cancelColorAdjustment = controller::cancelAdjustment,
        chooseColor = { controller.interactions.chooseExplicitColor(it) },
        toggleGradient = {}, addGradientStop = {}, selectGradientStop = {}, removeGradientStop = {},
        setGradientKind = {}, moveGradientStop = {}, setGradientStopOpacity = {}, setGradientAngle = {},
        reverseGradient = {}, reorderGradientStop = {}, setFontQuery = {}, setFontTab = {},
        setFontCategory = {}, setFontRole = {}, setFontScript = {}, setVariableOnly = {}, selectFont = {},
        toggleFavorite = {}, importFont = {}, removeImportedFont = {}, applyStyle = {},
        setStyleNameDraft = {}, createStyle = {}, updateStyle = {}, detachStyle = {}, clearFormatting = {},
        setTokenNameDraft = {}, createColorToken = {}, updateColorToken = {}, applyColorToken = {},
    )
