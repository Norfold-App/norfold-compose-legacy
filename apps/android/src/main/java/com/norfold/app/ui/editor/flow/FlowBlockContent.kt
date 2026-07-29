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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.norfold.app.domain.BulletListBlock
import com.norfold.app.domain.CalloutBlock
import com.norfold.app.domain.CodeBlock
import com.norfold.app.domain.ContainerAxis
import com.norfold.app.domain.ContainerBlock
import com.norfold.app.domain.DividerBlock
import com.norfold.app.domain.DocumentBlock
import com.norfold.app.domain.HeadingBlock
import com.norfold.app.domain.MathBlock
import com.norfold.app.domain.MermaidBlock
import com.norfold.app.domain.NumberedListBlock
import com.norfold.app.domain.ParagraphBlock
import com.norfold.app.domain.QuoteBlock
import com.norfold.app.domain.TableBlock
import com.norfold.app.domain.TodoListBlock
import com.norfold.app.domain.UnknownBlock
import com.norfold.app.domain.plainText
import com.norfold.app.editor.flow.FlowTableCursor

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
) {
    when (block) {
        is ParagraphBlock -> FlowEditableLine(
            blockId = block.id,
            itemId = null,
            text = block.plainText(),
            style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 28.sp),
            placeholder = "Write something…",
            callbacks = callbacks,
            modifier = modifier,
        )

        is HeadingBlock -> FlowEditableLine(
            blockId = block.id,
            itemId = null,
            text = block.plainText(),
            style = headingStyle(block.level),
            placeholder = "Heading ${block.level}",
            callbacks = callbacks,
            modifier = modifier,
        )

        is BulletListBlock -> Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            block.items.forEach { item ->
                FlowListRow(marker = "•") {
                    FlowEditableLine(
                        blockId = block.id,
                        itemId = item.id,
                        text = item.content.plainText(),
                        style = MaterialTheme.typography.bodyLarge,
                        placeholder = "List item",
                        callbacks = callbacks,
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
                        text = item.content.plainText(),
                        style = MaterialTheme.typography.bodyLarge,
                        placeholder = "List item",
                        callbacks = callbacks,
                    )
                }
            }
        }

        is TodoListBlock -> Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            block.items.forEach { item ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = item.checked,
                        onCheckedChange = { callbacks.onToggleTodo(block.id, item.id) },
                    )
                    FlowEditableLine(
                        blockId = block.id,
                        itemId = item.id,
                        text = item.content.plainText(),
                        style = MaterialTheme.typography.bodyLarge,
                        placeholder = "To-do",
                        callbacks = callbacks,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }

        is QuoteBlock -> Row(modifier.height(IntrinsicSize.Min)) {
            Box(
                Modifier
                    .width(3.dp)
                    .fillMaxHeight()
                    .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(2.dp)),
            )
            Column(Modifier.padding(start = 12.dp)) {
                block.children.forEach { child ->
                    FlowBlockContent(child, callbacks, Modifier.fillMaxWidth())
                }
            }
        }

        is CalloutBlock -> Column(
            modifier
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(12.dp),
        ) {
            Text(block.title, style = MaterialTheme.typography.titleSmall)
            if (!block.collapsed) {
                block.children.forEach { child ->
                    FlowBlockContent(child, callbacks, Modifier.fillMaxWidth())
                }
            }
        }

        is CodeBlock -> FlowEditableLine(
            blockId = block.id,
            itemId = null,
            text = block.code,
            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
            placeholder = "Code",
            callbacks = callbacks,
            modifier = modifier
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(12.dp),
        )

        is DividerBlock -> HorizontalDivider(modifier.padding(vertical = 12.dp))

        is ContainerBlock -> if (block.axis == ContainerAxis.Row) {
            Row(modifier, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                block.children.forEach { child ->
                    Box(Modifier.weight(1f)) { FlowBlockContent(child, callbacks) }
                }
            }
        } else {
            Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                block.children.forEach { child -> FlowBlockContent(child, callbacks, Modifier.fillMaxWidth()) }
            }
        }

        is TableBlock -> FlowTablePreview(block, callbacks, modifier)

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

/**
 * A table as Phase 1 owns it: readable, and tappable so a cell can be *addressed*. Addressing is the point —
 * `flow.table.insertRowAbove` and friends are disabled with a reason until a cell is selected, because a
 * directional command without a reference cell is the ambiguous `addRow()` the spec rejects. Cell text
 * editing belongs to the Phase-4 table work.
 */
@Composable
private fun FlowTablePreview(
    block: TableBlock,
    callbacks: FlowBlockCallbacks,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Row(Modifier.fillMaxWidth()) {
            block.headers.forEachIndexed { column, cell ->
                FlowTableCell(
                    text = cell.content.plainText(),
                    header = true,
                    modifier = Modifier.weight(1f),
                    onClick = { callbacks.onTableCell(FlowTableCursor(block.id, row = -1, column = column)) },
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
                        modifier = Modifier.weight(1f),
                        onClick = { callbacks.onTableCell(FlowTableCursor(block.id, rowIndex, column)) },
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
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text.ifBlank { " " },
        style = if (header) MaterialTheme.typography.labelLarge else MaterialTheme.typography.bodyMedium,
        modifier = modifier
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
    )
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
 * document is. When [text] changes underneath the field — an undo, a convert, a remote merge — the field is
 * re-seeded and the caret is clamped, so history rewrites are visible immediately instead of being masked by
 * stale local state.
 */
@Composable
private fun FlowEditableLine(
    blockId: String,
    itemId: String?,
    text: String,
    style: TextStyle,
    placeholder: String,
    callbacks: FlowBlockCallbacks,
    modifier: Modifier = Modifier,
) {
    var value by remember(blockId, itemId) { mutableStateOf(TextFieldValue(text, TextRange(text.length))) }
    if (value.text != text) {
        value = value.copy(text = text, selection = TextRange(value.selection.start.coerceIn(0, text.length)))
    }
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
                value = next
                if (next.text != before) {
                    callbacks.onEdit(FlowTextEdit(blockId, itemId, before, next.text, next.selection.start))
                } else {
                    callbacks.onCaret(blockId, itemId, next.selection.start)
                }
            },
            textStyle = style.copy(color = LocalContentColor.current),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun headingStyle(level: Int): TextStyle = when (level) {
    1 -> MaterialTheme.typography.headlineLarge
    2 -> MaterialTheme.typography.headlineMedium
    3 -> MaterialTheme.typography.headlineSmall
    4 -> MaterialTheme.typography.titleLarge
    5 -> MaterialTheme.typography.titleMedium
    else -> MaterialTheme.typography.titleSmall
}.copy(fontWeight = FontWeight.SemiBold)
