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
    val state = remember(refresh) { runtime.state() }
    var notice by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val clipboard = remember(context) { context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager }
    val rootFocus = remember { FocusRequester() }

    LaunchedEffect(Unit) { rootFocus.requestFocus() }

    BackHandler {
        if (!runtime.back()) onExit() else refresh++
    }

    Column(
        modifier.fillMaxSize().focusRequester(rootFocus).focusable().onKeyEvent { event ->
            event.toSpreadsheetShortcut()?.let { shortcut ->
                runtime.handleShortcut(shortcut).also { handled ->
                    if (handled) { refresh++; onChanged() }
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
            onUndo = { if (runtime.undo()) { refresh++; onChanged() } },
            onRedo = { if (runtime.redo()) { refresh++; onChanged() } },
            onAdvanced = { runtime.toggleAdvancedTools(); refresh++ },
        )
        FormulaBar(
            address = selectedAddress(state.selection)?.a1().orEmpty(),
            initialValue = state.formulaBarText,
            onCommit = { value ->
                val result = runtime.commitFormulaBar(value)
                notice = result.error?.detail ?: result.warning
                refresh++
                if (result.accepted) onChanged()
            },
        )
        notice?.let { message ->
            Surface(color = MaterialTheme.colorScheme.secondaryContainer) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(message, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { notice = null }) { Text("Dismiss") }
                }
            }
        }
        Box(Modifier.weight(1f)) {
            VirtualSpreadsheetGrid(
                runtime = runtime,
                refresh = refresh,
                onChanged = { refresh++; onChanged() },
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
    }
    HorizontalDivider()
}

@Composable
private fun FormulaBar(address: String, initialValue: String, onCommit: (String) -> Unit) {
    var value by remember(address, initialValue) { mutableStateOf(initialValue) }
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
            onValueChange = { value = it },
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
    modifier: Modifier = Modifier,
) {
    val state = remember(refresh) { runtime.state() }
    val sheet = state.activeSheet
    // The host's own engine, not a new one built from the workbook: constructing an engine here would
    // rebuild the dependency graph and recalculate every formula on every recomposition.
    val engine = runtime.engine()
    val density = LocalDensity.current
    var verticalOffset by remember(sheet.id) { mutableFloatStateOf(0f) }
    var horizontalOffset by remember(sheet.id) { mutableFloatStateOf(0f) }
    val vertical = rememberScrollableState { delta ->
        val maximum = sheet.rows.sumOf { (it.heightPoints ?: 24f).toDouble() }.toFloat()
        val deltaPoints = with(density) { delta.toDp().value }
        val next = (verticalOffset - deltaPoints).coerceIn(0f, maximum)
        val consumed = with(density) { (verticalOffset - next).dp.toPx() }
        verticalOffset = next
        consumed
    }
    val horizontal = rememberScrollableState { delta ->
        val maximum = sheet.columns.sumOf { (it.widthPoints ?: 96f).toDouble() }.toFloat()
        val deltaPoints = with(density) { delta.toDp().value }
        val next = (horizontalOffset - deltaPoints).coerceIn(0f, maximum)
        val consumed = with(density) { (horizontalOffset - next).dp.toPx() }
        horizontalOffset = next
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
                        onClick = {
                            if (selected && state.selection is TableSelectionState.CellSelected) runtime.beginEdit() else runtime.select(address)
                            onChanged()
                        },
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
    onClick: () -> Unit,
    onCommit: (String) -> Unit,
    onExtendRight: () -> Unit,
    onExtendDown: () -> Unit,
    modifier: Modifier,
) {
    var draft by remember(address, input) { mutableStateOf(input) }
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
                value = draft,
                onValueChange = { draft = it },
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
                keyboardActions = KeyboardActions(onDone = { onCommit(draft) }),
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
