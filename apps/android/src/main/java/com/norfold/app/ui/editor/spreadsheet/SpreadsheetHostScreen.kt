package com.norfold.app.ui.editor.spreadsheet

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.scrollable
import com.norfold.app.editor.style.EditorStyleUiState
import com.norfold.app.ui.editor.style.EditorStyleSurface
import com.norfold.app.ui.editor.style.EditorStyleSurfaceActions
import androidx.compose.foundation.gestures.rememberScrollableState
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.focusable
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.CollectionItemInfo
import androidx.compose.ui.semantics.CollectionInfo
import androidx.compose.ui.semantics.collectionInfo
import androidx.compose.ui.semantics.collectionItemInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.norfold.app.editor.calculation.CellFormat
import com.norfold.app.editor.calculation.CellBorder
import com.norfold.app.editor.calculation.CellBorderStyle
import com.norfold.app.editor.calculation.CellNumberFormat
import com.norfold.app.editor.calculation.NumberFormatKind
import com.norfold.app.editor.calculation.GridAddress
import com.norfold.app.editor.calculation.ConditionalRuleId
import com.norfold.app.editor.calculation.ConditionalRuleSummary
import com.norfold.app.editor.calculation.NamedRangeId
import com.norfold.app.editor.calculation.NamedRangeSummary
import com.norfold.app.editor.calculation.ValidationRuleId
import com.norfold.app.editor.calculation.ValidationRuleSummary
import com.norfold.app.editor.calculation.HorizontalCellAlignment
import com.norfold.app.editor.calculation.SpreadsheetCommandIds
import com.norfold.app.editor.calculation.SpreadsheetHostRuntime
import com.norfold.app.editor.calculation.SpreadsheetViewportPlanner
import com.norfold.app.editor.calculation.TableSelectionState
import com.norfold.app.editor.calculation.columnName
import com.norfold.app.editor.calculation.editorText
import com.norfold.app.editor.calculation.ComparisonOperator
import com.norfold.app.editor.calculation.SortDirection
import com.norfold.app.editor.calculation.SpreadsheetKey
import com.norfold.app.editor.calculation.SpreadsheetShortcut
import com.norfold.app.domain.style.ColorValue
import com.norfold.app.domain.ChartBlock
import com.norfold.app.domain.findById
import com.norfold.app.editor.chart.ChartBlockAdapter
import com.norfold.app.editor.flags.EditorFeatureFlag
import com.norfold.app.ui.editor.editorFlags
import com.norfold.app.ui.editor.chart.ChartStudioSheet
import com.norfold.app.editor.guide.GuideBlocker
import com.norfold.app.editor.guide.GuideContext
import com.norfold.app.editor.guide.GuideDomain
import com.norfold.app.editor.guide.GuideHost
import com.norfold.app.editor.guide.GuideSignal
import com.norfold.app.ui.editor.guide.GuideEntryButton
import com.norfold.app.ui.editor.guide.GuideHostLayer
import com.norfold.app.ui.editor.guide.rememberGuideUiController
import com.norfold.app.ui.editor.history.HistoryEntryButton
import com.norfold.app.ui.editor.history.HistoryHostLayer
import com.norfold.app.ui.editor.history.rememberHistoryUiController
import com.norfold.app.ui.editor.recovery.RecoveryEntryButton
import com.norfold.app.ui.editor.recovery.RecoveryHostLayer
import com.norfold.app.ui.editor.recovery.rememberRecoveryUiController

@Composable
fun SpreadsheetHostScreen(
    runtime: SpreadsheetHostRuntime,
    title: String,
    onTitleChange: (String) -> Unit,
    onOpenSidebar: () -> Unit,
    onExit: () -> Unit,
    onChanged: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var refresh by remember { mutableIntStateOf(0) }
    var chartEditingId by remember { mutableStateOf<String?>(null) }
    val state = remember(refresh) { runtime.state() }
    val context = LocalContext.current
    val clipboard = remember(context) { context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager }
    val rootFocus = remember { FocusRequester() }
    val guide = rememberGuideUiController(runtime.guide)
    val history = rememberHistoryUiController(runtime.history)
    // A restore rewrites the workbook outside the edit path, so nothing else would republish the grid.
    val recovery = rememberRecoveryUiController(runtime.recoveryBrowser) { refresh++; onChanged() }
    val guideContext = GuideContext(
        host = GuideHost.SPREADSHEET,
        domains = buildSet {
            add(GuideDomain.SPREADSHEET)
            if (state.colorStudio.target != null) add(GuideDomain.STYLES)
            if (chartEditingId != null) add(GuideDomain.CHART)
        },
        blockers = if (state.selection is TableSelectionState.Editing) setOf(GuideBlocker.TEXT_COMPOSITION) else emptySet(),
    )

    LaunchedEffect(Unit) { rootFocus.requestFocus() }

    BackHandler {
        if (!runtime.back()) onExit() else refresh++
    }

    Column(
        modifier.fillMaxSize().focusRequester(rootFocus).focusable().onKeyEvent { event ->
            event.toSpreadsheetShortcut()?.let { shortcut ->
                runtime.handleShortcut(shortcut).also { handled ->
                    if (handled) { refresh++; onChanged() }
                    // Ctrl+Z reaches History through the runtime, not through this controller: without
                    // this a reversal that must be disclosed would refuse and show nothing.
                    history.syncFromRuntime()
                }
            } ?: false
        },
    ) {
        SpreadsheetTopBar(
            title = title,
            onTitleChange = onTitleChange,
            onOpenSidebar = onOpenSidebar,
            canUndo = state.canUndo,
            canRedo = state.canRedo,
            // With the browser on, Undo goes through it — the only route that can disclose a multi-step
            // or cross-target reversal before it happens (§13.4).
            onUndo = { if (history.runtime != null) { history.undo(); refresh++; onChanged() } else if (runtime.undo()) { refresh++; onChanged() } },
            onRedo = { if (history.runtime != null) { history.redo(); refresh++; onChanged() } else if (runtime.redo()) { refresh++; onChanged() } },
            onAdvanced = { runtime.toggleAdvancedTools(); refresh++ },
            onOpenGuide = guide.runtime?.let { { guide.open() } },
            onOpenHistory = history.runtime?.let { { history.open() } },
            onOpenRecovery = recovery.runtime?.let { { recovery.open() } },
        )
        if (editorFlags.isEnabled(EditorFeatureFlag.CHART_STUDIO)) {
            val existingCharts = runtime.controller.document().blocks.filterIsInstance<ChartBlock>()
            if (existingCharts.isNotEmpty()) {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Charts", style = MaterialTheme.typography.labelLarge)
                    existingCharts.forEachIndexed { index, chart ->
                        val loaded = remember(chart.chartDocument, chart.vegaLiteSpec) { ChartBlockAdapter.load(chart) }
                        TextButton(onClick = { chartEditingId = chart.id }, modifier = Modifier.height(48.dp)) {
                            Text(loaded.document.title.ifBlank { "Chart ${index + 1}" })
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.End) {
                TextButton(
                    onClick = {
                        runtime.createChartFromSelection()?.let { id ->
                            chartEditingId = id
                            guide.signal(GuideSignal.CHART_STUDIO_OPENED)
                            refresh++
                            onChanged()
                        }
                    },
                    enabled = runtime.selectedChartBinding() != null,
                    modifier = Modifier.height(48.dp),
                ) { Text("Chart from selection") }
            }
        }
        FormulaBar(
            address = selectedAddress(state.selection)?.a1().orEmpty(),
            value = state.formulaBarText,
            onDraft = { runtime.updateFormulaBarDraft(it); refresh++ },
            onCommit = { value ->
                runtime.commitFormulaBar(value).let { result ->
                    refresh++
                    if (result.accepted) onChanged()
                }
            },
        )
        // One notice channel, owned by the runtime. It used to be a local `notice` fed only by the formula
        // bar, so every other refusal — an invalid range name, an out-of-bounds column width, a rename
        // collision — set a message the user never saw and the button simply looked broken.
        state.lastMessage?.let { message ->
            Surface(color = MaterialTheme.colorScheme.secondaryContainer) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(message, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { runtime.dismissMessage(); refresh++ }) { Text("Dismiss") }
                }
            }
        }
        Box(Modifier.weight(1f)) {
            VirtualSpreadsheetGrid(
                runtime = runtime,
                refresh = refresh,
                onChanged = { refresh++; onChanged() },
                // Scrolling and drafting change what is *shown*, not what is stored. Routing them through
                // `onChanged` would mark the document dirty and schedule a save for a scroll gesture.
                onInvalidate = { refresh++ },
                modifier = Modifier.fillMaxSize(),
            )
        }
        if (state.advancedToolsVisible) {
            // Dock buttons whose semantics are registered go *through* the registry, so a tap, a keyboard
            // chord and an accessibility action are three routes into one command rather than three
            // implementations that can drift apart (§32).
            val run: (String) -> Unit = { id ->
                if (runtime.invoke(id) is com.norfold.app.editor.command.CommandResult.Success) { refresh++; onChanged() }
            }
            AdvancedTableBar(
                onRowAbove = { run(SpreadsheetCommandIds.INSERT_ROW_ABOVE) },
                onRowBelow = { run(SpreadsheetCommandIds.INSERT_ROW_BELOW) },
                onColumnLeft = { run(SpreadsheetCommandIds.INSERT_COLUMN_LEFT) },
                onColumnRight = { run(SpreadsheetCommandIds.INSERT_COLUMN_RIGHT) },
                onMerge = { run(SpreadsheetCommandIds.MERGE) },
                onSplit = { run(SpreadsheetCommandIds.SPLIT) },
                onFreeze = { run(SpreadsheetCommandIds.FREEZE) },
                onBold = { run(SpreadsheetCommandIds.TOGGLE_BOLD) },
                onCenter = { if (runtime.applyFormat(CellFormat(horizontalAlignment = HorizontalCellAlignment.CENTER))) { refresh++; onChanged() } },
                onNamedRange = { if (runtime.defineNamedRange(it)) { refresh++; onChanged() } },
                onValidation = { if (runtime.applyListValidation(it)) { refresh++; onChanged() } },
                onConditional = {
                    if (runtime.applyComparisonConditional(ComparisonOperator.GREATER, it, CellFormat(fillColor = ColorValue(255, 226, 128)))) {
                        refresh++; onChanged()
                    }
                },
                onSort = { if (runtime.sortSelection(SortDirection.ASCENDING)) { refresh++; onChanged() } },
                onFilter = { if (runtime.filterSelectionTo(it)) { refresh++; onChanged() } },
                onClearFilter = { if (runtime.clearFilters()) { refresh++; onChanged() } },
                onCopy = {
                    runtime.copySelection().takeIf(String::isNotEmpty)?.let { clipboard.setPrimaryClip(ClipData.newPlainText("Norfold cells", it)) }
                },
                onCut = {
                    runtime.cutSelection().takeIf(String::isNotEmpty)?.let {
                        clipboard.setPrimaryClip(ClipData.newPlainText("Norfold cells", it))
                        refresh++; onChanged()
                    }
                },
                onPaste = {
                    val value = clipboard.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
                    if (value.isNotEmpty()) { runtime.paste(value); refresh++; onChanged() }
                },
                onMoveRow = { if (runtime.reorderSelectedRow(it)) { refresh++; onChanged() } },
                onMoveColumn = { if (runtime.reorderSelectedColumn(it)) { refresh++; onChanged() } },
                onColumnWidth = { if (runtime.setSelectedColumnWidth(it)) { refresh++; onChanged() } },
                onRowHeight = { if (runtime.setSelectedRowHeight(it)) { refresh++; onChanged() } },
                onFitRow = { if (runtime.fitSelectedRowHeight()) { refresh++; onChanged() } },
                onDeleteRow = { if (runtime.deleteSelectedRow()) { refresh++; onChanged() } },
                onDeleteColumn = { if (runtime.deleteSelectedColumn()) { refresh++; onChanged() } },
                onExtend = { row, column -> if (runtime.extendSelection(row, column)) refresh++ },
                mixedFormatting = state.mixedFormat.mixed,
                activeFilterCount = state.activeFilterCount,
                onOpenFillStudio = { if (runtime.openCellFillStudio()) { refresh++ } },
                onNumberFormat = { if (runtime.setNumberFormat(it)) { refresh++; onChanged() } },
                onTableStyle = { if (runtime.applyBuiltInTableStyle()) { refresh++; onChanged() } },
            )
            // Defining a name without being able to see, find, rename or remove one is a one-way door: the
            // name exists in every formula that uses it and nowhere the user can reach. The manager is the
            // other half of the workflow.
            NamedRangeManager(
                ranges = runtime.namedRangeSummaries(),
                onGoTo = { if (runtime.goToNamedRange(it)) { refresh++ } },
                onRename = { id, newName -> if (runtime.renameNamedRange(id, newName)) { refresh++; onChanged() } },
                onDelete = { if (runtime.deleteNamedRange(it)) { refresh++; onChanged() } },
            )
            RuleManager(
                validations = runtime.validationRules(),
                conditionals = runtime.conditionalRules(),
                onValidationWarnOnly = { id, warnOnly -> if (runtime.setValidationWarnOnly(id, warnOnly)) { refresh++; onChanged() } },
                onDeleteValidation = { if (runtime.deleteValidationRule(it)) { refresh++; onChanged() } },
                onConditionalEnabled = { id, enabled -> if (runtime.setConditionalRuleEnabled(id, enabled)) { refresh++; onChanged() } },
                onMoveConditional = { id, delta -> if (runtime.moveConditionalRule(id, delta)) { refresh++; onChanged() } },
                onDeleteConditional = { if (runtime.deleteConditionalRule(it)) { refresh++; onChanged() } },
            )
        }
        // The shared Phase-3 Color Studio, mounted by this host exactly as Flow and Document mount it.
        if (state.colorStudio.target != null) {
            EditorStyleSurface(
                surfaceId = "editor.surface.colorStudio",
                state = EditorStyleUiState(colorStudio = state.colorStudio),
                fontRows = emptyList(),
                actions = rememberSpreadsheetColorActions(runtime) { refresh++; onChanged() },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            )
        }
        SheetTabs(
            state.workbook.sheets.map { Triple(it.id, it.name, it.hidden) },
            state.workbook.activeSheetId,
            onActivate = { runtime.activateSheet(it); refresh++; onChanged() },
            onAdd = { if (runtime.addSheet()) { refresh++; onChanged() } },
            onRename = { if (runtime.renameActiveSheet(it)) { refresh++; onChanged() } },
            onDuplicate = { if (runtime.duplicateActiveSheet()) { refresh++; onChanged() } },
            onDelete = { if (runtime.deleteActiveSheet()) { refresh++; onChanged() } },
            onMove = { if (runtime.reorderActiveSheet(it)) { refresh++; onChanged() } },
            onSetHidden = { if (runtime.setActiveSheetHidden(it)) { refresh++; onChanged() } },
        )
    }

    chartEditingId?.let { blockId ->
        val block = runtime.controller.document().findById(blockId) as? ChartBlock
        if (block == null) {
            chartEditingId = null
        } else {
            val loaded = remember(block.chartDocument, block.vegaLiteSpec) { ChartBlockAdapter.load(block) }
            ChartStudioSheet(
                initial = loaded.document,
                readOnly = !loaded.isEditable,
                calculationProvider = runtime.chartCalculationProvider(),
                onRequestRangeBinding = {
                    if (runtime.rebindChartToSelection(blockId)) {
                        guide.signal(GuideSignal.CHART_DATA_BOUND)
                        refresh++
                        onChanged()
                    }
                },
                onDocumentChanged = { document ->
                    runtime.updateChart(blockId, document).also { changed ->
                        if (changed) { refresh++; onChanged() }
                    }
                },
                onDismiss = { chartEditingId = null },
                onDone = { chartEditingId = null },
                onOpenGuide = guide.runtime?.let { { guide.open() } },
                onDirectManipulation = { guide.signal(GuideSignal.CHART_DIRECT_MANIPULATION) },
            )
        }
    }
    GuideHostLayer(guide, guideContext)
    HistoryHostLayer(history)
    RecoveryHostLayer(recovery)
}

@Composable
private fun SpreadsheetTopBar(
    title: String,
    onTitleChange: (String) -> Unit,
    onOpenSidebar: () -> Unit,
    canUndo: Boolean,
    canRedo: Boolean,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onAdvanced: () -> Unit,
    onOpenGuide: (() -> Unit)?,
    onOpenHistory: (() -> Unit)?,
    onOpenRecovery: (() -> Unit)?,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        TextButton(onClick = onOpenSidebar, modifier = Modifier.size(48.dp)) { Text("☰") }
        BasicTextField(
            value = title,
            onValueChange = onTitleChange,
            textStyle = MaterialTheme.typography.titleMedium.copy(color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
            singleLine = true,
        )
        TextButton(onClick = onUndo, enabled = canUndo, modifier = Modifier.size(48.dp)) { Text("↶") }
        TextButton(onClick = onRedo, enabled = canRedo, modifier = Modifier.size(48.dp)) { Text("↷") }
        TextButton(onClick = onAdvanced, modifier = Modifier.height(48.dp)) { Text("Tools") }
        onOpenGuide?.let { GuideEntryButton("Spreadsheet editor", it) }
        onOpenHistory?.let { HistoryEntryButton(it) }
        onOpenRecovery?.let { RecoveryEntryButton(it) }
    }
    HorizontalDivider()
}

/**
 * The formula bar.
 *
 * Holds no draft of its own. The text it shows and the text it commits are both the runtime's, so a reflow, a
 * theme change or a configuration change cannot discard a half-typed formula — which is exactly what a
 * `remember` keyed on the selected address used to do.
 */
@Composable
private fun FormulaBar(address: String, value: String, onDraft: (String) -> Unit, onCommit: (String) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
            Text(address.ifBlank { "—" }, Modifier.width(64.dp).padding(10.dp), style = MaterialTheme.typography.labelLarge)
        }
        OutlinedTextField(
            value = value,
            onValueChange = onDraft,
            modifier = Modifier.weight(1f),
            singleLine = true,
            label = { Text("Value or formula") },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { onCommit(value) }),
        )
        Button(onClick = { onCommit(value) }, modifier = Modifier.height(48.dp)) { Text("Apply") }
    }
}

@Composable
private fun VirtualSpreadsheetGrid(
    runtime: SpreadsheetHostRuntime,
    refresh: Int,
    onChanged: () -> Unit,
    onInvalidate: () -> Unit = onChanged,
    modifier: Modifier = Modifier,
) {
    val state = remember(refresh) { runtime.state() }
    val sheet = state.activeSheet
    // The host's own engine, not a new one built from the workbook: constructing an engine here would
    // rebuild the dependency graph and recalculate every formula on every recomposition.
    val engine = runtime.engine()
    val density = LocalDensity.current
    // Scroll position is the runtime's, not the composition's: a reflow or configuration change used to
    // scroll a large grid back to A1, losing the user's place in a way nothing on screen explained.
    val (verticalOffset, horizontalOffset) = state.viewportOffset
    val vertical = rememberScrollableState { delta ->
        val maximum = sheet.rows.sumOf { (it.heightPoints ?: 24f).toDouble() }.toFloat()
        val deltaPoints = with(density) { delta.toDp().value }
        val next = (runtime.viewportOffset().first - deltaPoints).coerceIn(0f, maximum)
        val consumed = with(density) { (runtime.viewportOffset().first - next).dp.toPx() }
        runtime.setViewportOffset(next, runtime.viewportOffset().second)
        onInvalidate()
        consumed
    }
    val horizontal = rememberScrollableState { delta ->
        val maximum = sheet.columns.sumOf { (it.widthPoints ?: 96f).toDouble() }.toFloat()
        val deltaPoints = with(density) { delta.toDp().value }
        val next = (runtime.viewportOffset().second - deltaPoints).coerceIn(0f, maximum)
        val consumed = with(density) { (runtime.viewportOffset().second - next).dp.toPx() }
        runtime.setViewportOffset(runtime.viewportOffset().first, next)
        onInvalidate()
        consumed
    }
    BoxWithConstraints(modifier.background(MaterialTheme.colorScheme.surface)) {
        val viewportWidth = maxWidth.value
        val viewportHeight = maxHeight.value
        val plan = remember(sheet, verticalOffset, horizontalOffset, viewportWidth, viewportHeight) {
            SpreadsheetViewportPlanner.plan(sheet, verticalOffset, horizontalOffset, viewportWidth, viewportHeight, defaultRowHeightPoints = 48f)
        }
        LaunchedEffect(plan, state.workbook.revision) {
            engine.recordViewport(sheet.rowCount.toLong() * sheet.columnCount.toLong(), plan.composedCellCount)
        }
        val columnHeader = 48f
        val rowHeader = 48f
        Box(
            Modifier.fillMaxSize()
                .scrollable(vertical, orientation = androidx.compose.foundation.gestures.Orientation.Vertical)
                .scrollable(horizontal, orientation = androidx.compose.foundation.gestures.Orientation.Horizontal)
                .semantics { collectionInfo = CollectionInfo(sheet.rowCount, sheet.columnCount) },
        ) {
            val selectedCells = selectedAddresses(state.selection)
            val bodyRows = (plan.firstRow..plan.lastRow).filter { it in sheet.rows.indices && !sheet.rows[it].hidden }
            val bodyColumns = (plan.firstColumn..plan.lastColumn).filter { it in sheet.columns.indices && !sheet.columns[it].hidden }
            // Scrollable body is emitted first; pinned rows/columns are emitted last so they own
            // hit-testing wherever the scrolled body passes beneath a frozen pane.
            val rowsToCompose = (bodyRows + (0 until plan.frozenRows).filter { !sheet.rows[it].hidden }).distinct()
            val columnsToCompose = (bodyColumns + (0 until plan.frozenColumns).filter { !sheet.columns[it].hidden }).distinct()
            for (row in rowsToCompose) {
                val rowPrefix = sheet.rows.take(row).sumOf { if (it.hidden) 0.0 else maxOf(it.heightPoints ?: 48f, 48f).toDouble() }.toFloat()
                val y = rowHeader + rowPrefix - if (row < plan.frozenRows) 0f else verticalOffset
                val height = maxOf(sheet.rows[row].heightPoints ?: 48f, 48f)
                for (column in columnsToCompose) {
                    val columnPrefix = sheet.columns.take(column).sumOf { if (it.hidden) 0.0 else (it.widthPoints ?: 96f).toDouble() }.toFloat()
                    val x = columnHeader + columnPrefix - if (column < plan.frozenColumns) 0f else horizontalOffset
                    val width = sheet.columns[column].widthPoints ?: 96f
                    val address = GridAddress(row, column)
                    val selected = address in selectedCells
                    val presentation = engine.conditionalFormat(sheet.id, row, column)
                    // A live Color Studio preview paints the addressed cells without touching the workbook,
                    // so cancelling the gesture leaves no trace and no history entry.
                    val previewFill = runtime.previewFill?.representative()?.takeIf { selected }
                    GridCell(
                        address = address,
                        value = engine.displayAt(sheet.id, row, column),
                        input = sheet.cellAt(row, column)?.input?.editorText().orEmpty(),
                        format = if (previewFill == null) presentation.format else presentation.format.copy(fillColor = previewFill),
                        conditionalDescriptions = presentation.descriptions,
                        selected = selected,
                        editing = state.selection is TableSelectionState.Editing && state.selection.cell == address,
                        // The editor text is the runtime's draft while this cell is being typed into, and the
                        // stored value otherwise. A `remember` here used to hold it, so any recomposition the
                        // surface could not predict discarded what the user had typed.
                        draft = (state.selection as? TableSelectionState.Editing)?.takeIf { it.cell == address }?.draft,
                        onClick = {
                            if (selected && state.selection is TableSelectionState.CellSelected) runtime.beginEdit() else runtime.select(address)
                            onInvalidate()
                        },
                        onDraft = { runtime.updateDraft(it); onInvalidate() },
                        onCommit = { runtime.commitFormulaBar(it); onChanged() },
                        onExtendRight = { runtime.select(GridAddress(row, (column + 1).coerceAtMost(sheet.columnCount - 1)), extend = true); onChanged() },
                        onExtendDown = { runtime.select(GridAddress((row + 1).coerceAtMost(sheet.rowCount - 1), column), extend = true); onChanged() },
                        modifier = Modifier.offset(x.dp, y.dp).size(width.dp, height.dp),
                    )
                }
                GridHeader(
                    text = (row + 1).toString(),
                    selected = state.selection is TableSelectionState.RowSelected && state.selection.row == row,
                    onClick = { runtime.selectRow(row); onChanged() },
                    label = "Row ${row + 1}",
                    onResize = { delta ->
                        runtime.selectRow(row)
                        if (runtime.setSelectedRowHeight((height + with(density) { delta.toDp().value }).coerceIn(12f, 2_000f))) onChanged()
                    },
                    resizeAxis = ResizeAxis.VERTICAL,
                    modifier = Modifier.offset(0.dp, y.dp).size(columnHeader.dp, height.dp),
                )
            }
            for (column in columnsToCompose) {
                val columnPrefix = sheet.columns.take(column).sumOf { if (it.hidden) 0.0 else (it.widthPoints ?: 96f).toDouble() }.toFloat()
                val x = columnHeader + columnPrefix - if (column < plan.frozenColumns) 0f else horizontalOffset
                val width = sheet.columns[column].widthPoints ?: 96f
                GridHeader(
                    text = columnName(column),
                    selected = state.selection is TableSelectionState.ColumnSelected && state.selection.column == column,
                    onClick = { runtime.selectColumn(column); onChanged() },
                    label = "Column ${columnName(column)}",
                    onResize = { delta ->
                        runtime.selectColumn(column)
                        if (runtime.setSelectedColumnWidth((width + with(density) { delta.toDp().value }).coerceIn(16f, 2_000f))) onChanged()
                    },
                    resizeAxis = ResizeAxis.HORIZONTAL,
                    modifier = Modifier.offset(x.dp, 0.dp).size(width.dp, rowHeader.dp),
                )
            }
            GridHeader(
                text = "▦",
                selected = state.selection == TableSelectionState.WholeTable,
                onClick = { runtime.selectAll(); onChanged() },
                label = "Select the whole sheet",
                modifier = Modifier.size(columnHeader.dp, rowHeader.dp),
            )
            selectedAddress(state.selection)?.takeIf { it.row in sheet.rows.indices && it.column in sheet.columns.indices }?.let { focus ->
                val rowPrefix = sheet.rows.take(focus.row + 1).sumOf { if (it.hidden) 0.0 else maxOf(it.heightPoints ?: 48f, 48f).toDouble() }.toFloat()
                val columnPrefix = sheet.columns.take(focus.column + 1).sumOf { if (it.hidden) 0.0 else (it.widthPoints ?: 96f).toDouble() }.toFloat()
                // The parentheses matter: `a - if (c) 0f else b - 24f` would fold the centring offset into the
                // *unfrozen* branch only, so a handle on a frozen row would sit 24dp away from where the same
                // handle sits one row lower. Centring applies to both branches; only the scroll offset differs.
                val handleX = columnHeader + columnPrefix - (if (focus.column < plan.frozenColumns) 0f else horizontalOffset) - 24f
                val handleY = rowHeader + rowPrefix - (if (focus.row < plan.frozenRows) 0f else verticalOffset) - 24f
                var dragX by remember(focus, refresh) { mutableFloatStateOf(0f) }
                var dragY by remember(focus, refresh) { mutableFloatStateOf(0f) }
                Box(
                    Modifier.offset(handleX.dp, handleY.dp).size(48.dp)
                        .pointerInput(focus) {
                            detectDragGestures(
                                onDragStart = { dragX = 0f; dragY = 0f },
                                onDragCancel = { dragX = 0f; dragY = 0f },
                                onDragEnd = {
                                    val rowDelta = (dragY / 48f).toInt()
                                    val columnDelta = (dragX / 96f).toInt()
                                    if (rowDelta != 0 || columnDelta != 0) {
                                        val target = GridAddress(
                                            (focus.row + rowDelta).coerceIn(0, sheet.rowCount - 1),
                                            (focus.column + columnDelta).coerceIn(0, sheet.columnCount - 1),
                                        )
                                        runtime.select(target, extend = true)
                                        onChanged()
                                    }
                                    dragX = 0f; dragY = 0f
                                },
                            ) { change, amount ->
                                change.consume()
                                dragX += with(density) { amount.x.toDp().value }
                                dragY += with(density) { amount.y.toDp().value }
                            }
                        }
                        // The visible dot is 14dp inside a 48dp target, and the drag is only an accelerator:
                        // the same extension is available as an action, so a user who cannot drag is not
                        // locked out of range selection.
                        .semantics {
                            role = Role.Button
                            contentDescription = "Range selection handle"
                            stateDescription = describeSelection(state.selection)
                            customActions = listOf(
                                CustomAccessibilityAction("Extend the range right") {
                                    runtime.select(GridAddress(focus.row, (focus.column + 1).coerceAtMost(sheet.columnCount - 1)), extend = true)
                                    onChanged(); true
                                },
                                CustomAccessibilityAction("Extend the range down") {
                                    runtime.select(GridAddress((focus.row + 1).coerceAtMost(sheet.rowCount - 1), focus.column), extend = true)
                                    onChanged(); true
                                },
                                CustomAccessibilityAction("Collapse the range to one cell") {
                                    runtime.select(focus); onChanged(); true
                                },
                            )
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Box(Modifier.size(14.dp).clip(RoundedCornerShape(7.dp)).background(MaterialTheme.colorScheme.primary))
                }
            }
        }
    }

}

private enum class ResizeAxis { HORIZONTAL, VERTICAL }

/** One accessibility-action resize step, in dp. Large enough to be felt, small enough to repeat. */
private const val RESIZE_STEP_DP = 16f

/**
 * Binds the shared Color Studio surface to this host's runtime.
 *
 * Only the colour half is wired: the font and named-style actions belong to text hosts and are no-ops here
 * rather than plausible-looking stubs that would silently do nothing if the surface ever offered them.
 */
@Composable
private fun rememberSpreadsheetColorActions(
    runtime: SpreadsheetHostRuntime,
    onChanged: () -> Unit,
): EditorStyleSurfaceActions = remember(runtime, onChanged) {
    fun act(block: () -> Unit) { block(); onChanged() }
    EditorStyleSurfaceActions(
        dismiss = { commit ->
            act {
                if (commit) runtime.colorInteractions.done() else runtime.colorInteractions.cancel()
                runtime.closeCellFillStudio()
            }
        },
        setColorView = { act { runtime.colorInteractions.cancel(); runtime.colorStudio.setView(it) } },
        draftColor = { field, value -> act { runtime.colorStudio.draft(field, value) } },
        commitColorDraft = { act { runtime.colorStudio.commitDraft(it) } },
        discardColorDraft = { act { runtime.colorStudio.discardDraft(it) } },
        beginColorAdjustment = { act { runtime.colorInteractions.beginContinuousAdjustment() } },
        previewColor = { act { runtime.colorStudio.previewColor(it) } },
        previewAlpha = { act { runtime.colorStudio.previewAlpha(it) } },
        finishColorAdjustment = { act { runtime.colorInteractions.finishContinuousAdjustment(it) } },
        cancelColorAdjustment = { act { runtime.colorInteractions.cancelContinuousAdjustment() } },
        chooseColor = { color -> act { runtime.colorInteractions.chooseExplicitColor(color, "Fill cells") } },
        // A cell fill is flat in Phase 4; the role itself declines gradients, so these stay inert rather
        // than half-implemented.
        toggleGradient = {},
        addGradientStop = {},
        selectGradientStop = {},
        removeGradientStop = {},
        setGradientKind = {},
        moveGradientStop = {},
        setGradientStopOpacity = { act { runtime.colorStudio.previewAlpha(it) } },
        setGradientAngle = {},
        reverseGradient = {},
        reorderGradientStop = {},
        setFontQuery = {},
        setFontTab = {},
        setFontCategory = {},
        setFontRole = {},
        setFontScript = {},
        setVariableOnly = {},
        selectFont = {},
        toggleFavorite = {},
        importFont = {},
        removeImportedFont = {},
        applyStyle = {},
        setStyleNameDraft = {},
        createStyle = {},
        updateStyle = {},
        detachStyle = {},
        clearFormatting = {},
        setTokenNameDraft = {},
        createColorToken = {},
        updateColorToken = {},
        applyColorToken = {},
    )
}

/**
 * A row, column or whole-table header.
 *
 * Two things here are accessibility contracts rather than decoration. Selected state is *spoken*, not only
 * tinted — a header that reads "A header" whether or not its column is selected leaves the fact conveyed by
 * fill colour alone. And resizing has a custom action beside the drag, because a drag is an accelerator: a
 * switch or screen-reader user who cannot perform one must still be able to size a column.
 */
@Composable
private fun GridHeader(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier,
    label: String = text,
    onResize: ((Float) -> Unit)? = null,
    resizeAxis: ResizeAxis = ResizeAxis.HORIZONTAL,
) {
    val axisWord = if (resizeAxis == ResizeAxis.HORIZONTAL) "wider" else "taller"
    val otherWord = if (resizeAxis == ResizeAxis.HORIZONTAL) "narrower" else "shorter"
    Box(
        modifier
            .background(if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant)
            .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant)
            .clickable(onClick = onClick)
            .then(
                if (onResize == null) Modifier else Modifier.pointerInput(onResize, resizeAxis) {
                    var total = 0f
                    detectDragGestures(
                        onDragStart = { total = 0f },
                        onDragCancel = { total = 0f },
                        onDragEnd = { if (total != 0f) onResize(total); total = 0f },
                    ) { change, amount ->
                        change.consume()
                        total += if (resizeAxis == ResizeAxis.HORIZONTAL) amount.x else amount.y
                    }
                },
            )
            .semantics {
                role = Role.Button
                contentDescription = label
                stateDescription = if (selected) "Selected" else "Not selected"
                if (onResize != null) {
                    customActions = listOf(
                        CustomAccessibilityAction("Make $axisWord") { onResize(RESIZE_STEP_DP); true },
                        CustomAccessibilityAction("Make $otherWord") { onResize(-RESIZE_STEP_DP); true },
                    )
                }
            },
        contentAlignment = Alignment.Center,
    ) { Text(text, style = MaterialTheme.typography.labelSmall) }
}

@Composable
private fun GridCell(
    address: GridAddress,
    value: String,
    input: String,
    format: CellFormat,
    conditionalDescriptions: List<String>,
    selected: Boolean,
    editing: Boolean,
    /** The uncommitted text while this cell is being typed into, or null. Runtime-owned. */
    draft: String?,
    onClick: () -> Unit,
    onDraft: (String) -> Unit,
    onCommit: (String) -> Unit,
    onExtendRight: () -> Unit,
    onExtendDown: () -> Unit,
    modifier: Modifier,
) {
    val editorText = draft ?: input
    val cellFill = format.fillColor?.let { Color(it.toArgb()) } ?: Color.Transparent
    val textColor = format.textColor?.let { Color(it.toArgb()) } ?: MaterialTheme.colorScheme.onSurface
    val alignment = when (format.horizontalAlignment) {
        HorizontalCellAlignment.CENTER -> Alignment.Center
        HorizontalCellAlignment.END -> Alignment.CenterEnd
        else -> Alignment.CenterStart
    }
    val textAlign = when (format.horizontalAlignment) {
        HorizontalCellAlignment.CENTER -> TextAlign.Center
        HorizontalCellAlignment.END -> TextAlign.End
        else -> TextAlign.Start
    }
    val fontWeight = if (format.bold == true) FontWeight.Bold else FontWeight.Normal
    val fontStyle = if (format.italic == true) FontStyle.Italic else FontStyle.Normal
    Box(
        modifier
            .background(cellFill)
            .background(if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f) else Color.Transparent)
            .border(if (selected) 2.dp else 0.5.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant)
            .cellBorders(format)
            .clickable(enabled = !editing, onClick = onClick)
            .semantics {
                role = Role.Button
                contentDescription = "Cell ${address.a1()}, ${value.ifBlank { "blank" }}${if (selected) ", selected" else ""}${if (editing) ", editing" else ""}"
                stateDescription = buildString {
                    append(if (editing) "Editing" else if (selected) "Selected" else "Not selected")
                    if (conditionalDescriptions.isNotEmpty()) append(". ${conditionalDescriptions.joinToString()}")
                }
                collectionItemInfo = CollectionItemInfo(address.row, 1, address.column, 1)
                customActions = listOf(
                    CustomAccessibilityAction("Extend selection right") { onExtendRight(); true },
                    CustomAccessibilityAction("Extend selection down") { onExtendDown(); true },
                )
            }
            .padding((format.paddingPoints ?: 6f).dp),
        contentAlignment = alignment,
    ) {
        if (editing) {
            BasicTextField(
                value = editorText,
                onValueChange = onDraft,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                textStyle = MaterialTheme.typography.bodySmall.copy(
                    color = textColor,
                    fontSize = format.fontSizePoints?.sp ?: MaterialTheme.typography.bodySmall.fontSize,
                    fontWeight = fontWeight,
                    fontStyle = fontStyle,
                    textAlign = textAlign,
                ),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onCommit(editorText) }),
            )
        } else {
            Text(
                value,
                style = MaterialTheme.typography.bodySmall.copy(
                    color = textColor,
                    fontSize = format.fontSizePoints?.sp ?: MaterialTheme.typography.bodySmall.fontSize,
                    fontWeight = fontWeight,
                    fontStyle = fontStyle,
                    textAlign = textAlign,
                ),
                maxLines = if (format.wrap == true) Int.MAX_VALUE else 1,
            )
        }
    }
}

private fun Modifier.cellBorders(format: CellFormat): Modifier = drawBehind {
    fun draw(border: CellBorder?, start: Offset, end: Offset) {
        if (border == null || border.style == CellBorderStyle.NONE || border.widthPoints <= 0f) return
        val width = border.widthPoints.dp.toPx()
        val effect = when (border.style) {
            CellBorderStyle.DASHED -> androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(width * 4, width * 3))
            CellBorderStyle.DOTTED -> androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(width, width * 2))
            else -> null
        }
        val color = Color(border.color.toArgb())
        drawLine(color, start, end, strokeWidth = width, pathEffect = effect)
        if (border.style == CellBorderStyle.DOUBLE) {
            val shift = width * 2
            val shiftedStart = if (start.y == end.y) start.copy(y = (start.y + if (start.y == 0f) shift else -shift)) else start.copy(x = start.x + if (start.x == 0f) shift else -shift)
            val shiftedEnd = if (start.y == end.y) end.copy(y = shiftedStart.y) else end.copy(x = shiftedStart.x)
            drawLine(color, shiftedStart, shiftedEnd, strokeWidth = width)
        }
    }
    draw(format.borderTop, Offset.Zero, Offset(size.width, 0f))
    draw(format.borderRight, Offset(size.width, 0f), Offset(size.width, size.height))
    draw(format.borderBottom, Offset(0f, size.height), Offset(size.width, size.height))
    draw(format.borderLeft, Offset.Zero, Offset(0f, size.height))
}

/**
 * The defined-name manager: list, find, rename, remove.
 *
 * Every row is reachable and every row is honest. A name whose cells were deleted still appears — marked
 * unresolved and still deletable — because the alternative is a formula reporting `#NAME?` against a name the
 * UI claims does not exist. Rename edits in place rather than through a dialog so the old name stays visible
 * while the new one is typed, which is what makes "am I renaming the right one" answerable.
 */
@Composable
private fun NamedRangeManager(
    ranges: List<NamedRangeSummary>,
    onGoTo: (NamedRangeId) -> Unit,
    onRename: (NamedRangeId, String) -> Unit,
    onDelete: (NamedRangeId) -> Unit,
) {
    if (ranges.isEmpty()) return
    // Keyed by id, so the field follows the name it was opened for even when the list re-sorts after a rename.
    var renaming by remember { mutableStateOf<NamedRangeId?>(null) }
    var draft by remember { mutableStateOf("") }
    Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
        Text("Named ranges", style = MaterialTheme.typography.labelLarge)
        ranges.forEach { range ->
            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (renaming == range.id) {
                    OutlinedTextField(
                        draft,
                        { draft = it },
                        label = { Text("Rename \"${range.name}\"") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        onClick = { onRename(range.id, draft); renaming = null },
                        enabled = draft.isNotBlank(),
                        modifier = Modifier.height(48.dp),
                    ) { Text("Save") }
                    TextButton(onClick = { renaming = null }, modifier = Modifier.height(48.dp)) { Text("Cancel") }
                } else {
                    Column(Modifier.weight(1f)) {
                        Text(range.name, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            if (range.resolvable) range.location else "${range.location} — the cells it pointed at are gone",
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                    TextButton(
                        onClick = { onGoTo(range.id) },
                        // Navigating to cells that no longer exist has nowhere to go; the row stays listed so
                        // it can still be renamed or removed.
                        enabled = range.resolvable,
                        modifier = Modifier.height(48.dp),
                    ) { Text("Go to") }
                    TextButton(
                        onClick = { renaming = range.id; draft = range.name },
                        modifier = Modifier.height(48.dp),
                    ) { Text("Rename") }
                    TextButton(onClick = { onDelete(range.id) }, modifier = Modifier.height(48.dp)) { Text("Delete") }
                }
            }
            HorizontalDivider()
        }
    }
}

/**
 * Validation and highlight rules already on the sheet.
 *
 * Applying a rule used to be a one-way door: the rule kept refusing input or repainting cells, and the only
 * thing the UI offered was applying another one on top. Each row states what the rule does and where, so
 * "why was my value refused" and "why is this cell yellow" are answerable without reading the model.
 *
 * Highlight rows are listed in evaluation order and carry that order in their accessible description, because
 * priority is what decides which rule wins on a cell two rules both match.
 */
@Composable
private fun RuleManager(
    validations: List<ValidationRuleSummary>,
    conditionals: List<ConditionalRuleSummary>,
    onValidationWarnOnly: (ValidationRuleId, Boolean) -> Unit,
    onDeleteValidation: (ValidationRuleId) -> Unit,
    onConditionalEnabled: (ConditionalRuleId, Boolean) -> Unit,
    onMoveConditional: (ConditionalRuleId, Int) -> Unit,
    onDeleteConditional: (ConditionalRuleId) -> Unit,
) {
    if (validations.isEmpty() && conditionals.isEmpty()) return
    Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
        if (validations.isNotEmpty()) {
            Text("Validation rules", style = MaterialTheme.typography.labelLarge)
            validations.forEach { rule ->
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics {
                        contentDescription = "${rule.location} allows ${rule.description}"
                        stateDescription = if (rule.warnOnly) "Warns about other values" else "Refuses other values"
                    },
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("${rule.location} — allows ${rule.description}", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            if (rule.warnOnly) "Warns, then accepts" else "Refuses anything else",
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                    // A rule that is right about the range but too strict is far more common than a rule that
                    // should not exist, so relaxing it is one tap and does not lose the range.
                    TextButton(
                        onClick = { onValidationWarnOnly(rule.id, !rule.warnOnly) },
                        modifier = Modifier.height(48.dp),
                    ) { Text(if (rule.warnOnly) "Refuse" else "Warn only") }
                    TextButton(onClick = { onDeleteValidation(rule.id) }, modifier = Modifier.height(48.dp)) { Text("Delete") }
                }
                HorizontalDivider()
            }
        }
        if (conditionals.isNotEmpty()) {
            Text("Highlight rules", style = MaterialTheme.typography.labelLarge)
            conditionals.forEachIndexed { index, rule ->
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics {
                        contentDescription = "Rule ${index + 1} of ${conditionals.size}: ${rule.location} ${rule.description}"
                        stateDescription = if (rule.enabled) "On" else "Off"
                    },
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("${rule.location} — ${rule.description}", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "Checked ${rule.priority}${if (rule.stopIfTrue) " · stops here when it matches" else ""}",
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                    TextButton(
                        onClick = { onMoveConditional(rule.id, -1) },
                        enabled = index > 0,
                        modifier = Modifier.size(48.dp),
                    ) { Text("↑") }
                    TextButton(
                        onClick = { onMoveConditional(rule.id, 1) },
                        enabled = index < conditionals.lastIndex,
                        modifier = Modifier.size(48.dp),
                    ) { Text("↓") }
                    // Off rather than deleted, so "what did this look like before" stays answerable.
                    TextButton(
                        onClick = { onConditionalEnabled(rule.id, !rule.enabled) },
                        modifier = Modifier.height(48.dp),
                    ) { Text(if (rule.enabled) "Turn off" else "Turn on") }
                    TextButton(onClick = { onDeleteConditional(rule.id) }, modifier = Modifier.height(48.dp)) { Text("Delete") }
                }
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun AdvancedTableBar(
    onRowAbove: () -> Unit,
    onRowBelow: () -> Unit,
    onColumnLeft: () -> Unit,
    onColumnRight: () -> Unit,
    onMerge: () -> Unit,
    onSplit: () -> Unit,
    onFreeze: () -> Unit,
    onBold: () -> Unit,
    onCenter: () -> Unit,
    onNamedRange: (String) -> Unit,
    onValidation: (List<String>) -> Unit,
    onConditional: (String) -> Unit,
    onSort: () -> Unit,
    onFilter: (List<String>) -> Unit,
    onClearFilter: () -> Unit,
    onCopy: () -> Unit,
    onCut: () -> Unit,
    onPaste: () -> Unit,
    onMoveRow: (Int) -> Unit,
    onMoveColumn: (Int) -> Unit,
    onColumnWidth: (Float) -> Unit,
    onRowHeight: (Float) -> Unit,
    onFitRow: () -> Unit,
    onDeleteRow: () -> Unit,
    onDeleteColumn: () -> Unit,
    onExtend: (Int, Int) -> Unit,
    mixedFormatting: Boolean,
    activeFilterCount: Int,
    onOpenFillStudio: () -> Unit,
    onNumberFormat: (CellNumberFormat) -> Unit,
    onTableStyle: () -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var values by remember { mutableStateOf("") }
    var threshold by remember { mutableStateOf("") }
    var columnWidth by remember { mutableStateOf("") }
    var rowHeight by remember { mutableStateOf("") }
    var currencyId by remember { mutableStateOf("USD") }
    val scroll = rememberScrollState()
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(scroll).padding(horizontal = 6.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            listOf(
                "Row ↑" to onRowAbove,
                "Row ↓" to onRowBelow,
                "Col ←" to onColumnLeft,
                "Col →" to onColumnRight,
                "Merge" to onMerge,
                "Split" to onSplit,
                "Freeze" to onFreeze,
                "Bold" to onBold,
                "Center" to onCenter,
                "Sort A–Z" to onSort,
                "Copy" to onCopy,
                "Cut" to onCut,
                "Paste" to onPaste,
                "Row earlier" to { onMoveRow(-1) },
                "Row later" to { onMoveRow(1) },
                "Column earlier" to { onMoveColumn(-1) },
                "Column later" to { onMoveColumn(1) },
                "Extend →" to { onExtend(0, 1) },
                "Extend ↓" to { onExtend(1, 0) },
                "Fit row" to onFitRow,
                "Delete row" to onDeleteRow,
                "Delete column" to onDeleteColumn,
                "Clear filters" to onClearFilter,
                "Table style" to onTableStyle,
                "General" to { onNumberFormat(CellNumberFormat(NumberFormatKind.GENERAL)) },
                "Number" to { onNumberFormat(CellNumberFormat(NumberFormatKind.NUMBER)) },
                "Integer" to { onNumberFormat(CellNumberFormat(NumberFormatKind.INTEGER)) },
                "Percent" to { onNumberFormat(CellNumberFormat(NumberFormatKind.PERCENTAGE)) },
                "Date" to { onNumberFormat(CellNumberFormat(NumberFormatKind.DATE)) },
                "Date-time" to { onNumberFormat(CellNumberFormat(NumberFormatKind.DATE_TIME)) },
                "Time" to { onNumberFormat(CellNumberFormat(NumberFormatKind.TIME)) },
                "Text" to { onNumberFormat(CellNumberFormat(NumberFormatKind.TEXT)) },
            ).forEach { (label, action) -> TextButton(onClick = action, modifier = Modifier.height(48.dp)) { Text(label) } }
        }
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(name, { name = it }, label = { Text("Range name") }, singleLine = true, modifier = Modifier.width(180.dp))
            Button(onClick = { onNamedRange(name) }, enabled = name.isNotBlank(), modifier = Modifier.height(48.dp)) { Text("Name range") }
            OutlinedTextField(values, { values = it }, label = { Text("Allowed/filter values") }, singleLine = true, modifier = Modifier.width(220.dp))
            Button(onClick = { onValidation(values.split(',')) }, enabled = values.isNotBlank(), modifier = Modifier.height(48.dp)) { Text("Validate") }
            Button(onClick = { onFilter(values.split(',')) }, enabled = values.isNotBlank(), modifier = Modifier.height(48.dp)) { Text("Filter") }
            OutlinedTextField(threshold, { threshold = it }, label = { Text("Highlight >") }, singleLine = true, modifier = Modifier.width(150.dp))
            Button(onClick = { onConditional(threshold) }, enabled = threshold.isNotBlank(), modifier = Modifier.height(48.dp)) { Text("Highlight") }
            OutlinedTextField(columnWidth, { columnWidth = it }, label = { Text("Column width") }, singleLine = true, modifier = Modifier.width(150.dp))
            Button(onClick = { columnWidth.toFloatOrNull()?.let(onColumnWidth) }, enabled = columnWidth.toFloatOrNull() != null, modifier = Modifier.height(48.dp)) { Text("Set width") }
            OutlinedTextField(rowHeight, { rowHeight = it }, label = { Text("Row height") }, singleLine = true, modifier = Modifier.width(150.dp))
            Button(onClick = { rowHeight.toFloatOrNull()?.let(onRowHeight) }, enabled = rowHeight.toFloatOrNull() != null, modifier = Modifier.height(48.dp)) { Text("Set height") }
            Button(onClick = onOpenFillStudio, modifier = Modifier.height(48.dp)) { Text("Fill…") }
            OutlinedTextField(currencyId, { currencyId = it.uppercase().take(8) }, label = { Text("Currency") }, singleLine = true, modifier = Modifier.width(120.dp))
            Button(onClick = { onNumberFormat(CellNumberFormat(NumberFormatKind.CURRENCY, currencyId)) }, enabled = currencyId.isNotBlank(), modifier = Modifier.height(48.dp)) { Text("Currency") }
            Text(
                buildString {
                    append(if (mixedFormatting) "Formatting: Mixed" else "Formatting: Uniform")
                    if (activeFilterCount > 0) append(" · $activeFilterCount active filter${if (activeFilterCount == 1) "" else "s"}")
                },
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}

@Composable
private fun SheetTabs(
    sheets: List<Triple<com.norfold.app.editor.calculation.SheetId, String, Boolean>>,
    active: com.norfold.app.editor.calculation.SheetId,
    onActivate: (com.norfold.app.editor.calculation.SheetId) -> Unit,
    onAdd: () -> Unit,
    onRename: (String) -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
    onMove: (Int) -> Unit,
    onSetHidden: (Boolean) -> Unit,
) {
    var rename by remember(active) { mutableStateOf(sheets.firstOrNull { it.first == active }?.second.orEmpty()) }
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 6.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        sheets.forEach { (id, name, hidden) ->
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = if (id == active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.clickable { onActivate(id) }.height(48.dp),
            ) { Box(Modifier.padding(horizontal = 16.dp), contentAlignment = Alignment.Center) { Text(if (hidden) "$name (hidden)" else name) } }
        }
        TextButton(onClick = onAdd, modifier = Modifier.size(48.dp)) { Text("+") }
        OutlinedTextField(rename, { rename = it }, singleLine = true, label = { Text("Sheet name") }, modifier = Modifier.width(160.dp))
        TextButton(onClick = { onRename(rename) }, enabled = rename.isNotBlank(), modifier = Modifier.height(48.dp)) { Text("Rename") }
        TextButton(onClick = onDuplicate, modifier = Modifier.height(48.dp)) { Text("Duplicate") }
        TextButton(onClick = onDelete, enabled = sheets.size > 1, modifier = Modifier.height(48.dp)) { Text("Delete") }
        val currentIndex = sheets.indexOfFirst { it.first == active }
        val activeHidden = sheets.firstOrNull { it.first == active }?.third == true
        TextButton(onClick = { onMove(currentIndex - 1) }, enabled = currentIndex > 0, modifier = Modifier.height(48.dp)) { Text("Move left") }
        TextButton(onClick = { onMove(currentIndex + 1) }, enabled = currentIndex in 0 until sheets.lastIndex, modifier = Modifier.height(48.dp)) { Text("Move right") }
        TextButton(onClick = { onSetHidden(!activeHidden) }, enabled = activeHidden || sheets.count { !it.third } > 1, modifier = Modifier.height(48.dp)) { Text(if (activeHidden) "Show" else "Hide") }
    }
}

private fun selectedAddress(selection: TableSelectionState): GridAddress? = when (selection) {
    is TableSelectionState.CellSelected -> selection.cell
    is TableSelectionState.Editing -> selection.cell
    is TableSelectionState.RangeSelected -> selection.focus
    is TableSelectionState.RowSelected -> GridAddress(selection.row, 0)
    is TableSelectionState.ColumnSelected -> GridAddress(0, selection.column)
    TableSelectionState.WholeTable -> GridAddress(0, 0)
    else -> null
}

/**
 * Selection in words — §34 requires a range to have a textual description, not just a tint.
 *
 * The tint is the only signal a sighted user gets; without this, a screen-reader user has no way to learn
 * that a 3×4 block is selected rather than one cell.
 */
private fun describeSelection(selection: TableSelectionState): String = when (selection) {
    is TableSelectionState.CellSelected -> "Cell ${selection.cell.a1()} selected"
    is TableSelectionState.Editing -> "Editing cell ${selection.cell.a1()}"
    is TableSelectionState.RangeSelected -> {
        val rows = kotlin.math.abs(selection.focus.row - selection.anchor.row) + 1
        val columns = kotlin.math.abs(selection.focus.column - selection.anchor.column) + 1
        "Range ${selection.anchor.a1()} to ${selection.focus.a1()} selected, $rows rows by $columns columns"
    }
    is TableSelectionState.RowSelected -> "Row ${selection.row + 1} selected"
    is TableSelectionState.ColumnSelected -> "Column ${columnName(selection.column)} selected"
    TableSelectionState.WholeTable -> "Whole sheet selected"
    else -> "Nothing selected"
}

private fun selectedAddresses(selection: TableSelectionState): Set<GridAddress> = when (selection) {
    is TableSelectionState.CellSelected -> setOf(selection.cell)
    is TableSelectionState.Editing -> setOf(selection.cell)
    is TableSelectionState.RangeSelected -> buildSet {
        for (row in minOf(selection.anchor.row, selection.focus.row)..maxOf(selection.anchor.row, selection.focus.row)) {
            for (column in minOf(selection.anchor.column, selection.focus.column)..maxOf(selection.anchor.column, selection.focus.column)) add(GridAddress(row, column))
        }
    }
    else -> emptySet()
}

private fun KeyEvent.toSpreadsheetShortcut(): SpreadsheetShortcut? {
    if (type != KeyEventType.KeyDown) return null
    val mapped = when (key) {
        Key.DirectionUp -> SpreadsheetKey.ARROW_UP
        Key.DirectionDown -> SpreadsheetKey.ARROW_DOWN
        Key.DirectionLeft -> SpreadsheetKey.ARROW_LEFT
        Key.DirectionRight -> SpreadsheetKey.ARROW_RIGHT
        Key.Tab -> SpreadsheetKey.TAB
        Key.Enter, Key.NumPadEnter -> SpreadsheetKey.ENTER
        Key.Escape -> SpreadsheetKey.ESCAPE
        Key.Delete, Key.Backspace -> SpreadsheetKey.DELETE
        Key.Z -> SpreadsheetKey.Z
        Key.Y -> SpreadsheetKey.Y
        else -> return null
    }
    return SpreadsheetShortcut(mapped, shift = isShiftPressed, ctrlOrMeta = isCtrlPressed || isMetaPressed)
}
