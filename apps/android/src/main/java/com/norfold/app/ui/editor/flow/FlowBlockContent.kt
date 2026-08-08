package com.norfold.app.ui.editor.flow

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.norfold.app.domain.BulletListBlock
import com.norfold.app.domain.CalloutBlock
import com.norfold.app.domain.ChartBlock
import com.norfold.app.domain.ImageBlock
import com.norfold.app.domain.CodeBlock
import com.norfold.app.domain.ContainerAxis
import com.norfold.app.domain.ContainerBlock
import com.norfold.app.domain.DividerBlock
import com.norfold.app.domain.DocumentBlock
import com.norfold.app.domain.HeadingBlock
import com.norfold.app.domain.InlineNode
import com.norfold.app.domain.InlineText
import com.norfold.app.domain.MathBlock
import com.norfold.app.domain.MermaidBlock
import com.norfold.app.domain.NumberedListBlock
import com.norfold.app.domain.ParagraphBlock
import com.norfold.app.domain.QuoteBlock
import com.norfold.app.domain.effectiveStyleAssignment
import com.norfold.app.domain.TableBlock
import com.norfold.app.domain.TodoListBlock
import com.norfold.app.domain.UnknownBlock
import com.norfold.app.domain.plainText
import com.norfold.app.editor.flow.FlowCaretRequest
import com.norfold.app.editor.flow.FlowInlineStyle
import com.norfold.app.editor.flow.FlowInlineText
import com.norfold.app.editor.flow.FlowTableCursor
import com.norfold.app.editor.flags.EditorFeatureFlag
import com.norfold.app.ui.editor.editorFlags
import com.norfold.app.ui.fonts.NorfoldFontFamilies
import com.norfold.app.ui.screens.highlightColor
import com.norfold.app.domain.style.Stylesheet
import com.norfold.app.domain.style.ColorPaint
import com.norfold.app.domain.style.ColorRole
import com.norfold.app.ui.editor.style.resolvedTextStyle
import com.norfold.app.ui.editor.style.WrapperStyleSource
import com.norfold.app.ui.editor.style.collapsedBlockSpacing
import com.norfold.app.ui.editor.style.resolvedBlockMetrics
import com.norfold.app.ui.editor.style.resolvedContentColor
import com.norfold.app.editor.chart.ChartBlockAdapter
import com.norfold.app.editor.chart.ChartDataResolver
import com.norfold.app.editor.chart.ChartCalculationDataProvider
import com.norfold.app.ui.editor.chart.ChartRenderer
import com.norfold.app.editor.image.ImageBlockAdapter
import com.norfold.app.ui.editor.image.ImageFocusRenderer

/**
 * A text edit the surface observed, addressed the way the engines address it.
 *
 * The row reports *what changed*; it never decides what that means. [FlowHostRuntime] turns it into a
 * canonical transaction with the right coalescing key, and the slash resolver reads the same text/caret pair
 * to decide whether a menu is open — which is why the typed `/query` can be the only search input there is.
 */
data class FlowTextEdit(
    val blockId: String,
    val itemId: String?,
    val before: String,
    val after: String,
    val caret: Int,
)

/** Everything a block row needs in order to report interactions back to the runtime. */
class FlowBlockCallbacks(
    val onEdit: (FlowTextEdit) -> Unit,
    val onCaret: (blockId: String, itemId: String?, caret: Int) -> Unit,
    val onToggleTodo: (blockId: String, itemId: String) -> Unit,
    val onTableCell: (FlowTableCursor) -> Unit,
    /**
     * A keystroke inside the addressed cell. Records the draft; it does not write.
     *
     * Separate from [onTableCellCommit] because they are different events. The old surface called one lambda
     * for both, so `=SUM(A1:A2)` reached the engine as eight partial inputs — each an undo step and a chance
     * to warn about text the user had not finished typing.
     */
    val onTableCellDraft: (FlowTableCursor, String) -> Unit = { _, _ -> },
    /** The user finished the cell: Done on the keyboard, or focus leaving the field. Writes once. */
    val onTableCellCommit: (FlowTableCursor, String) -> Unit = { _, _ -> },
    val onOpenTableInSpreadsheet: (String) -> Unit = {},
    /**
     * Enter pressed with the caret inside this field. Returns true when the runtime turned it into a document
     * change (split, new list item, leaving a list) and the field should *not* also insert a newline.
     */
    val onNewLine: () -> Boolean = { false },
    /**
     * Backspace pressed with the caret collapsed at offset 0. Returns true when the runtime merged backwards;
     * false lets the keystroke fall through, which is what "nothing above to join" should feel like.
     */
    val onBackspaceAtStart: () -> Boolean = { false },
    /** Expand/collapse a collapsible block (correction item 16). */
    val onToggleCollapsed: (blockId: String) -> Unit = {},
    val onOpenChart: (blockId: String) -> Unit = {},
    val onOpenImage: (blockId: String) -> Unit = {},
)

/**
 * The per-row presentation facts a block needs from the runtime snapshot (correction items 1 and 5).
 *
 * Passed as one value rather than five parameters threaded through every nested `FlowBlockContent` call, so
 * adding a fact later does not mean touching every block branch.
 */
data class FlowBlockContext(
    /**
     * The caret the runtime wants shown, or null. Applied once per [FlowCaretRequest.stamp]; see that type for
     * why the stamp, and not the caret value, is what makes this loop-free.
     */
    val caretRequest: FlowCaretRequest? = null,
    /**
     * True while structural multi-select is active. Text fields go read-only and stop taking focus, so tapping
     * a block's *text* toggles the block instead of dropping a caret into it (correction item 5).
     */
    val selectionMode: Boolean = false,
    /**
     * The cell the runtime has addressed, or null. Comes from the runtime rather than from row-local state so
     * a highlighted cell always means the command system genuinely has a table cursor (correction item 7).
     */
    val tableCursor: FlowTableCursor? = null,
    /**
     * The uncommitted text of the addressed cell. Owned by the runtime, not by the cell composable: a draft
     * kept in a `remember` is lost to any recomposition the host cannot see, and the recovery re-seeded the
     * committed value, discarding what the user had typed.
     */
    val tableDraft: String? = null,
    val stylesheet: Stylesheet = Stylesheet.defaults(),
    val previewRole: ColorRole? = null,
    val previewPaint: ColorPaint? = null,
    val previewTargetIds: Set<String> = emptySet(),
    val wrapperStyles: List<WrapperStyleSource> = emptyList(),
    val chartCalculationProvider: ChartCalculationDataProvider? = null,
    val chartDataRevision: Long = 0,
)

/**
 * Renders one block's content, writing-first.
 *
 * Structure is the only thing Phase 1 owns, so text-bearing blocks are editable here and everything a later
 * phase owns (images, charts, shapes, embeds) renders as an honest placeholder rather than a half-working
 * editor. [UnknownBlock] says plainly that its payload is preserved verbatim — silently showing nothing would
 * make a newer-schema note look empty and invite the user to "fix" it by overwriting.
 */
@Composable
fun FlowBlockContent(
    block: DocumentBlock,
    callbacks: FlowBlockCallbacks,
    modifier: Modifier = Modifier,
    context: FlowBlockContext = FlowBlockContext(),
) {
    when (block) {
        is ParagraphBlock -> FlowEditableLine(
            blockId = block.id,
            itemId = null,
            nodes = block.content,
            style = resolvedFlowTextStyle(block, context, MaterialTheme.typography.bodyLarge.copy(lineHeight = 28.sp)),
            placeholder = "Write something…",
            callbacks = callbacks,
            context = context,
            modifier = modifier,
        )

        is HeadingBlock -> FlowEditableLine(
            blockId = block.id,
            itemId = null,
            nodes = block.content,
            style = resolvedFlowTextStyle(block, context, headingStyle(block.level)),
            placeholder = "Heading ${block.level}",
            callbacks = callbacks,
            context = context,
            modifier = modifier,
        )

        is BulletListBlock -> Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            block.items.forEach { item ->
                FlowListRow(marker = "•") {
                    FlowEditableLine(
                        blockId = block.id,
                        itemId = item.id,
                        nodes = item.content,
                        style = resolvedFlowTextStyle(block, context, MaterialTheme.typography.bodyLarge),
                        placeholder = "List item",
                        callbacks = callbacks,
                        context = context,
                    )
                }
            }
        }

        is NumberedListBlock -> Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            block.items.forEachIndexed { index, item ->
                FlowListRow(marker = "${block.start + index}.") {
                    FlowEditableLine(
                        blockId = block.id,
                        itemId = item.id,
                        nodes = item.content,
                        style = resolvedFlowTextStyle(block, context, MaterialTheme.typography.bodyLarge),
                        placeholder = "List item",
                        callbacks = callbacks,
                        context = context,
                    )
                }
            }
        }

        is TodoListBlock -> Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            block.items.forEach { item ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = item.checked,
                        // In structural multi-select the row owns the gesture; a live checkbox here would let
                        // a stray tap edit content while the user is selecting blocks (correction item 5).
                        enabled = !context.selectionMode,
                        onCheckedChange = { callbacks.onToggleTodo(block.id, item.id) },
                    )
                    FlowEditableLine(
                        blockId = block.id,
                        itemId = item.id,
                        nodes = item.content,
                        style = resolvedFlowTextStyle(block, context, MaterialTheme.typography.bodyLarge),
                        placeholder = "To-do",
                        callbacks = callbacks,
                        context = context,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }

        is QuoteBlock -> Row(modifier.height(IntrinsicSize.Min)) {
            val childContext = context.copy(
                wrapperStyles = listOf(WrapperStyleSource(block.id, block.effectiveStyleAssignment())) + context.wrapperStyles,
            )
            Box(
                Modifier
                    .width(3.dp)
                    .fillMaxHeight()
                    .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(2.dp)),
            )
            Column(Modifier.padding(start = 12.dp)) {
                FlowStyledChildren(block.children, callbacks, childContext)
            }
        }

        is CalloutBlock -> Column(
            modifier
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(12.dp),
        ) {
            val childContext = context.copy(
                wrapperStyles = listOf(WrapperStyleSource(block.id, block.effectiveStyleAssignment())) + context.wrapperStyles,
            )
            // A collapsed callout with no control is content the user cannot reach at all, and structural
            // commands would still operate on children nobody can see (correction item 16).
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Disabled in structural selection so a tap meant to select the callout block cannot instead
                // expand/collapse it (correction item 6) — the same reason the Todo checkbox above is disabled.
                IconButton(
                    onClick = { callbacks.onToggleCollapsed(block.id) },
                    enabled = !context.selectionMode,
                ) {
                    Icon(
                        imageVector = if (block.collapsed) Icons.Filled.ChevronRight else Icons.Filled.ExpandMore,
                        contentDescription = if (block.collapsed) "Expand callout" else "Collapse callout",
                    )
                }
                Text(
                    block.title.ifBlank { "Callout" },
                    style = resolvedFlowTextStyle(block, context, MaterialTheme.typography.titleSmall),
                    modifier = Modifier.weight(1f),
                )
            }
            if (!block.collapsed) {
                FlowStyledChildren(block.children, callbacks, childContext)
            }
        }

        // A code block holds a plain string, not inline nodes. Wrapping it in a single [InlineText] is the
        // honest description of that: one run, no styling, same editing path as every other line.
        is CodeBlock -> FlowEditableLine(
            blockId = block.id,
            itemId = null,
            nodes = listOf(InlineText(block.code)),
            style = resolvedFlowTextStyle(
                block,
                context,
                MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
            ),
            placeholder = "Code",
            callbacks = callbacks,
            context = context,
            modifier = modifier
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(12.dp),
        )

        is DividerBlock -> HorizontalDivider(modifier.padding(vertical = 12.dp))

        is ContainerBlock -> if (block.axis == ContainerAxis.Row) {
            Row(modifier, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                block.children.forEach { child ->
                    Box(Modifier.weight(1f)) { FlowBlockContent(child, callbacks, context = context) }
                }
            }
        } else {
            Column(modifier) {
                FlowStyledChildren(block.children, callbacks, context)
            }
        }

        is TableBlock -> FlowTablePreview(block, callbacks, context, modifier)

        is ImageBlock -> if (editorFlags.isEnabled(EditorFeatureFlag.IMAGE_FOCUS)) {
            val loaded = remember(block.imageEditDocument) { ImageBlockAdapter.load(block) }
            Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                ImageFocusRenderer(
                    block = block,
                    modifier = Modifier.fillMaxWidth().height(block.displayHeightDp.coerceIn(120f, 560f).dp),
                )
                if (!context.selectionMode && loaded.isEditable) {
                    TextButton(onClick = { callbacks.onOpenImage(block.id) }) { Text("Edit image") }
                }
                if (block.caption.isNotBlank()) {
                    Text(block.caption, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } else {
            FlowPlaceholder("Image", block.caption.ifBlank { block.source }.take(120).ifBlank { "empty" }, modifier)
        }

        is ChartBlock -> if (editorFlags.isEnabled(EditorFeatureFlag.CHART_STUDIO)) {
            val loaded = remember(block.chartDocument, block.vegaLiteSpec) { ChartBlockAdapter.load(block) }
            Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                ChartRenderer(
                    document = loaded.document,
                    resolution = remember(loaded.document, context.chartDataRevision) {
                        ChartDataResolver.resolve(loaded.document, context.chartCalculationProvider)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    heightDp = block.editorHeightDp.toInt().coerceIn(120, 560),
                )
                if (!context.selectionMode && loaded.isEditable) {
                    TextButton(onClick = { callbacks.onOpenChart(block.id) }) { Text("Edit chart") }
                }
            }
        } else {
            FlowPlaceholder("Chart", block.plainText().take(120).ifBlank { "empty" }, modifier)
        }

        is MathBlock -> FlowPlaceholder("Equation", block.tex.ifBlank { "empty" }, modifier)
        is MermaidBlock -> FlowPlaceholder("Diagram", block.code.ifBlank { "empty" }, modifier)

        is UnknownBlock -> FlowPlaceholder(
            title = "Preserved content",
            detail = "Saved by a newer version. Kept exactly as stored.",
            modifier = modifier,
        )

        else -> FlowPlaceholder(
            title = block::class.simpleName?.removeSuffix("Block") ?: "Block",
            detail = block.plainText().take(120).ifBlank { "Editing arrives with a later phase." },
            modifier = modifier,
        )
    }
}

@Composable
private fun FlowStyledChildren(
    children: List<DocumentBlock>,
    callbacks: FlowBlockCallbacks,
    context: FlowBlockContext,
) {
    val metrics = remember(children, context.stylesheet, context.wrapperStyles) {
        children.map { resolvedBlockMetrics(it, context.stylesheet, context.wrapperStyles) }
    }
    children.forEachIndexed { index, child ->
        val current = metrics[index]
        val top = if (index == 0) current.beforeDp
        else collapsedBlockSpacing(metrics[index - 1].afterDp, current.beforeDp)
        val bottom = if (index == children.lastIndex) current.afterDp else 0f
        FlowBlockContent(
            child,
            callbacks,
            Modifier.fillMaxWidth().padding(top = top.dp, bottom = bottom.dp),
            context,
        )
    }
}

/**
 * A table as Phase 1 owns it: readable, and tappable so a cell can be *addressed*. Addressing is the point —
 * `flow.table.insertRowAbove` and friends are disabled with a reason until a cell is selected, because a
 * directional command without a reference cell is the ambiguous `addRow()` the spec rejects. Cell text
 * editing belongs to the Phase-4 table work.
 *
 * The addressed cell is drawn from [FlowBlockContext.tableCursor], i.e. from the runtime's own cursor. That is
 * the correction for item 7: a cell can only *look* selected when the command system agrees it is.
 */
@Composable
private fun FlowTablePreview(
    block: TableBlock,
    callbacks: FlowBlockCallbacks,
    context: FlowBlockContext,
    modifier: Modifier = Modifier,
) {
    val cursor = context.tableCursor?.takeIf { it.blockId == block.id }
    Column(
        modifier
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        // Gated on the same flag as the route itself: an escape hatch into a host the build has not
        // enabled would make the flag decorative. §25 — this opens the *same* table identity, so it is a
        // deeper surface, not a conversion.
        if (editorFlags.isEnabled(EditorFeatureFlag.SPREADSHEET_HOST)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(
                    onClick = { callbacks.onOpenTableInSpreadsheet(block.id) },
                    enabled = !context.selectionMode,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text("Open in Spreadsheet") }
            }
        }
        Row(Modifier.fillMaxWidth()) {
            block.headers.forEachIndexed { column, cell ->
                FlowTableCell(
                    text = cell.content.plainText(),
                    header = true,
                    addressed = cursor?.row == -1 && cursor.column == column,
                    // In structural selection a cell tap must select the table block, not address a cell
                    // (correction item 6); the click is disabled so the row's own gesture sees it instead.
                    enabled = !context.selectionMode,
                    modifier = Modifier.weight(1f),
                    onClick = { callbacks.onTableCell(FlowTableCursor(block.id, row = -1, column = column)) },
                    editableText = if (cursor?.row == -1 && cursor.column == column) context.tableDraft else null,
                    onDraft = { callbacks.onTableCellDraft(FlowTableCursor(block.id, -1, column), it) },
                    onCommit = { callbacks.onTableCellCommit(FlowTableCursor(block.id, -1, column), it) },
                )
            }
        }
        block.rows.forEachIndexed { rowIndex, row ->
            HorizontalDivider()
            Row(Modifier.fillMaxWidth()) {
                row.forEachIndexed { column, cell ->
                    FlowTableCell(
                        text = cell.content.plainText(),
                        header = false,
                        addressed = cursor?.row == rowIndex && cursor.column == column,
                        enabled = !context.selectionMode,
                        modifier = Modifier.weight(1f),
                        onClick = { callbacks.onTableCell(FlowTableCursor(block.id, rowIndex, column)) },
                        editableText = if (cursor?.row == rowIndex && cursor.column == column) context.tableDraft else null,
                        onDraft = { callbacks.onTableCellDraft(FlowTableCursor(block.id, rowIndex, column), it) },
                        onCommit = { callbacks.onTableCellCommit(FlowTableCursor(block.id, rowIndex, column), it) },
                    )
                }
            }
        }
    }
}

@Composable
private fun FlowTableCell(
    text: String,
    header: Boolean,
    addressed: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    editableText: String? = null,
    onDraft: (String) -> Unit = {},
    onCommit: (String) -> Unit = {},
) {
    val cellStyle = if (header) MaterialTheme.typography.labelLarge else MaterialTheme.typography.bodyMedium
    // Focus loss is a commit route, not a discard: tapping the next cell has always meant "keep what I typed"
    // in every grid the user has met. Tracked here because Compose reports the transition, not the state.
    var hadFocus by remember { mutableStateOf(false) }
    Box(
        modifier = modifier
            .heightIn(min = 48.dp)
            .clickable(enabled = enabled && !addressed, onClick = onClick)
            .then(
                if (addressed) {
                    Modifier.background(MaterialTheme.colorScheme.primary.copy(alpha = 0.16f))
                } else {
                    Modifier
                },
            )
            .semantics {
                role = Role.Button
                contentDescription = "${if (header) "Header" else "Table"} cell, ${text.ifBlank { "blank" }}${if (addressed) ", selected and editable" else ""}"
            }
            .padding(horizontal = 10.dp, vertical = 8.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        if (addressed && enabled && editableText != null) {
            BasicTextField(
                value = editableText,
                onValueChange = onDraft,
                modifier = Modifier
                    .fillMaxWidth()
                    .onFocusChanged { focus ->
                        if (hadFocus && !focus.isFocused) onCommit(editableText)
                        hadFocus = focus.isFocused
                    },
                textStyle = cellStyle.copy(color = LocalContentColor.current),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onCommit(editableText) }),
            )
        } else {
            Text(text = text.ifBlank { " " }, style = cellStyle)
        }
    }
}

@Composable
private fun FlowListRow(marker: String, content: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.Top) {
        Text(
            marker,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier
                .width(28.dp)
                .padding(top = 2.dp),
        )
        Box(Modifier.weight(1f)) { content() }
    }
}

@Composable
private fun FlowPlaceholder(title: String, detail: String, modifier: Modifier = Modifier) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(12.dp),
    ) {
        Text(title, style = MaterialTheme.typography.labelLarge)
        Text(
            detail,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * One editable line of block text.
 *
 * The field holds its own [TextFieldValue] so the IME behaves, but it is never the source of truth: the
 * document is. When the text changes underneath the field — an undo, a convert, a remote merge — the field is
 * re-seeded and the caret is clamped, so history rewrites are visible immediately instead of being masked by
 * stale local state.
 *
 * Three correction items meet here, because they are all the *same* seam:
 *
 *  - **item 1** — [FlowBlockContext.caretRequest] moves real focus and a real caret when the runtime moves
 *    its own. Applied once per stamp, so the field echoing its caret back cannot start a sync loop.
 *  - **item 2** — Enter and Backspace-at-start are asked of the runtime first. Consumed only when the runtime
 *    actually restructured the document; otherwise the keystroke stays an ordinary keystroke.
 *  - **item 5** — in structural multi-select there is no field at all, so a tap on a block's text is the
 *    row's gesture. Read-only would still place a caret; not existing cannot.
 *  - **item 15** — existing bold/italic/link/code/highlight/font content is *shown* through a visual
 *    transformation. The field still edits plain text, and the inline metadata outside the edited range is
 *    never touched, so nothing is flattened on the way through.
 */
@Composable
private fun FlowEditableLine(
    blockId: String,
    itemId: String?,
    nodes: List<InlineNode>,
    style: TextStyle,
    placeholder: String,
    callbacks: FlowBlockCallbacks,
    context: FlowBlockContext,
    modifier: Modifier = Modifier,
) {
    val text = nodes.plainText()
    val styled = flowInlineSpans(nodes)

    if (context.selectionMode) {
        Box(modifier) {
            Text(
                text = if (text.isEmpty()) AnnotatedString(placeholder) else styled,
                style = style.copy(
                    color = if (text.isEmpty()) {
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    } else {
                        resolvedContentColor(style.color, LocalContentColor.current)
                    },
                ),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        return
    }

    var value by remember(blockId, itemId) { mutableStateOf(TextFieldValue(text, TextRange(text.length))) }
    if (value.text != text) {
        value = value.copy(text = text, selection = TextRange(value.selection.start.coerceIn(0, text.length)))
    }

    val focus = remember(blockId, itemId) { FocusRequester() }
    var appliedStamp by remember(blockId, itemId) { mutableStateOf(0L) }
    val request = context.caretRequest
    // Only the request that names *this* field, and only once per stamp: the field reports its caret back on
    // every change, and re-applying on value would make that report re-arm the request forever.
    if (request != null && request.blockId == blockId && request.itemId == itemId &&
        request.stamp != appliedStamp
    ) {
        appliedStamp = request.stamp
        val offset = request.offset.coerceIn(0, value.text.length)
        value = value.copy(selection = TextRange(offset))
        LaunchedEffect(request.stamp) { runCatching { focus.requestFocus() } }
    }

    // Latched by hardware Shift+Enter so the newline that follows is inserted as a soft break instead of
    // being read as a structural split. Soft keyboards send no modifier, which is why the latch and not the
    // key event itself decides.
    var softBreak by remember(blockId, itemId) { mutableStateOf(false) }

    val transformation = remember(styled) { FlowInlineTransformation(styled) }

    Box(modifier) {
        if (value.text.isEmpty()) {
            Text(
                placeholder,
                style = style,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            )
        }
        BasicTextField(
            value = value,
            onValueChange = { next ->
                val before = value.text
                val newline = next.text.length > before.length && next.selection.collapsed &&
                    next.selection.start > 0 && next.text.getOrNull(next.selection.start - 1) == '\n'
                if (newline && !softBreak && callbacks.onNewLine()) {
                    // The runtime restructured; the re-seed from the new document is what moves this field.
                    return@BasicTextField
                }
                softBreak = false
                value = next
                if (next.text != before) {
                    callbacks.onEdit(FlowTextEdit(blockId, itemId, before, next.text, next.selection.start))
                } else {
                    callbacks.onCaret(blockId, itemId, next.selection.start)
                }
            },
            visualTransformation = transformation,
            textStyle = style.copy(
                color = resolvedContentColor(style.color, LocalContentColor.current),
            ),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(focus)
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (event.key) {
                        Key.Enter, Key.NumPadEnter -> when {
                            event.isShiftPressed -> {
                                softBreak = true
                                false
                            }
                            else -> callbacks.onNewLine()
                        }
                        Key.Backspace ->
                            value.selection.collapsed && value.selection.start == 0 &&
                                callbacks.onBackspaceAtStart()
                        else -> false
                    }
                },
        )
    }
}

/**
 * Maps [FlowInlineText]'s toolkit-independent runs onto Compose spans.
 *
 * Kept beside the renderer rather than in [FlowInlineText] so the run model stays unit-testable without
 * Compose, and reuses the legacy editor's [highlightColor] and [NorfoldFontFamilies] so the same note looks
 * the same in both hosts.
 */
@Composable
private fun flowInlineSpans(nodes: List<InlineNode>): AnnotatedString {
    val colors = MaterialTheme.colorScheme
    return remember(nodes, colors) {
        val text = nodes.plainText()
        val spans = FlowInlineText.runsOf(nodes).mapNotNull { run ->
            if (run.style.isPlain) return@mapNotNull null
            val start = run.start.coerceIn(0, text.length)
            val end = run.end.coerceIn(start, text.length)
            if (start == end) return@mapNotNull null
            AnnotatedString.Range(run.style.toSpanStyle(colors), start, end)
        }
        AnnotatedString(text, spans)
    }
}

private fun FlowInlineStyle.toSpanStyle(colors: ColorScheme): SpanStyle = SpanStyle(
    fontWeight = if (bold) FontWeight.Bold else null,
    fontStyle = if (italic) FontStyle.Italic else null,
    fontSize = fontSizeSp?.coerceIn(6f, 144f)?.sp ?: TextUnit.Unspecified,
    fontFamily = when {
        code || math -> FontFamily.Monospace
        fontFamily != null -> NorfoldFontFamilies.resolve(fontFamily)
        else -> null
    },
    color = if (link != null) colors.primary else Color.Unspecified,
    background = when {
        highlight != null -> highlightColor(highlight.ifBlank { null })
        code -> colors.surfaceVariant.copy(alpha = 0.55f)
        math -> colors.surfaceVariant.copy(alpha = 0.35f)
        else -> Color.Unspecified
    },
    textDecoration = when {
        underline && strikethrough -> TextDecoration.combine(
            listOf(TextDecoration.Underline, TextDecoration.LineThrough),
        )
        underline || link != null -> TextDecoration.Underline
        strikethrough -> TextDecoration.LineThrough
        else -> null
    },
)

/**
 * Shows the block's inline styling inside a plain-text field.
 *
 * [OffsetMapping.Identity] is correct here precisely because no character is added, removed or reordered —
 * only painted. Spans are re-clamped against the live field text because the field can be a keystroke ahead
 * of the document, and an out-of-range span would crash rather than merely look wrong.
 */
private class FlowInlineTransformation(private val styled: AnnotatedString) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val spans = styled.spanStyles.mapNotNull { span ->
            val start = span.start.coerceIn(0, text.length)
            val end = span.end.coerceIn(start, text.length)
            if (start == end) null else AnnotatedString.Range(span.item, start, end)
        }
        return TransformedText(AnnotatedString(text.text, spans), OffsetMapping.Identity)
    }
}

@Composable
private fun resolvedFlowTextStyle(
    block: DocumentBlock,
    context: FlowBlockContext,
    base: TextStyle,
): TextStyle = resolvedTextStyle(
    block = block,
    sheet = context.stylesheet,
    base = base,
    wrappersNearestFirst = context.wrapperStyles,
    previewRole = context.previewRole,
    previewPaint = context.previewPaint,
    previewTargetIds = context.previewTargetIds,
)

@Composable
private fun headingStyle(level: Int): TextStyle = when (level) {
    1 -> MaterialTheme.typography.headlineLarge
    2 -> MaterialTheme.typography.headlineMedium
    3 -> MaterialTheme.typography.headlineSmall
    4 -> MaterialTheme.typography.titleLarge
    5 -> MaterialTheme.typography.titleMedium
    else -> MaterialTheme.typography.titleSmall
}.copy(fontWeight = FontWeight.SemiBold)
