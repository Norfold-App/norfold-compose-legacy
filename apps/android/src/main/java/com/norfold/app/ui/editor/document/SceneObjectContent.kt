package com.norfold.app.ui.editor.document

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.norfold.app.domain.BulletListBlock
import com.norfold.app.domain.CalloutBlock
import com.norfold.app.domain.ChartBlock
import com.norfold.app.domain.CodeBlock
import com.norfold.app.domain.ContainerAxis
import com.norfold.app.domain.ContainerBlock
import com.norfold.app.domain.DividerBlock
import com.norfold.app.domain.DocumentBlock
import com.norfold.app.domain.EmbedBlock
import com.norfold.app.domain.FileBlock
import com.norfold.app.domain.HeadingBlock
import com.norfold.app.domain.ImageBlock
import com.norfold.app.domain.InlineNode
import com.norfold.app.domain.InlineText
import com.norfold.app.domain.MathBlock
import com.norfold.app.domain.MermaidBlock
import com.norfold.app.domain.NumberedListBlock
import com.norfold.app.domain.ParagraphBlock
import com.norfold.app.domain.QuoteBlock
import com.norfold.app.domain.ShapeBlock
import com.norfold.app.domain.TableBlock
import com.norfold.app.domain.TodoListBlock
import com.norfold.app.domain.UnknownBlock
import com.norfold.app.domain.plainText
import com.norfold.app.editor.flow.FlowInlineStyle
import com.norfold.app.editor.flow.FlowInlineText
import com.norfold.app.ui.fonts.NorfoldFontFamilies
import com.norfold.app.ui.screens.highlightColor

/** A text edit inside a scene object's content, addressed the way the engines address it. */
data class SceneTextEdit(
    val objectId: String,
    val itemId: String?,
    val before: String,
    val after: String,
)

/** What a scene object's content reports back to the runtime. */
class SceneContentCallbacks(
    val onEdit: (SceneTextEdit) -> Unit,
    val onToggleTodo: (itemId: String) -> Unit,
)

/**
 * Renders a scene object's **actual content** (roadmap Phase 2 §7).
 *
 * The defect this replaces drew `obj.displayName()` — the object's *type name* — inside every box, so a page
 * of real writing rendered as a column of the word "TextFrame". A bounded-page editor whose pages do not show
 * their content is not an editor of that content; it is a diagram of one.
 *
 * The same block vocabulary the Flow host renders is honoured here, because a paragraph placed on a page *is*
 * the same paragraph: headings keep their level, lists keep their markers and numbering, a to-do keeps its
 * checkboxes, a quote keeps its rule, a callout keeps its tone, code keeps its monospace slab and a divider
 * is a divider. Inline styling is shown through the same run model [FlowInlineText] produces, so bold text on
 * a page looks bold rather than looking destroyed.
 *
 * [editing] is what makes a text frame editable, and it is deliberately **not** implied by selection. One tap
 * selects the object so it can be moved, resized, layered and deleted; only a deliberate second tap on an
 * already-selected frame enters its text. Entering on the first tap would mean every attempt to drag a text
 * frame instead dropped a caret into it.
 */
@Composable
fun SceneObjectContent(
    block: DocumentBlock,
    objectId: String,
    editing: Boolean,
    callbacks: SceneContentCallbacks,
    modifier: Modifier = Modifier,
) {
    when (block) {
        is ParagraphBlock -> SceneEditableLine(
            objectId = objectId,
            itemId = null,
            nodes = block.content,
            style = MaterialTheme.typography.bodyMedium,
            placeholder = "Text",
            editing = editing,
            callbacks = callbacks,
            modifier = modifier,
        )

        is HeadingBlock -> SceneEditableLine(
            objectId = objectId,
            itemId = null,
            nodes = block.content,
            style = sceneHeadingStyle(block.level),
            placeholder = "Heading ${block.level}",
            editing = editing,
            callbacks = callbacks,
            modifier = modifier,
        )

        is BulletListBlock -> Column(modifier, verticalArrangement = Arrangement.spacedBy(1.dp)) {
            block.items.forEach { item ->
                SceneListRow("•") {
                    SceneEditableLine(
                        objectId = objectId,
                        itemId = item.id,
                        nodes = item.content,
                        style = MaterialTheme.typography.bodyMedium,
                        placeholder = "List item",
                        editing = editing,
                        callbacks = callbacks,
                    )
                }
            }
        }

        is NumberedListBlock -> Column(modifier, verticalArrangement = Arrangement.spacedBy(1.dp)) {
            block.items.forEachIndexed { index, item ->
                SceneListRow("${block.start + index}.") {
                    SceneEditableLine(
                        objectId = objectId,
                        itemId = item.id,
                        nodes = item.content,
                        style = MaterialTheme.typography.bodyMedium,
                        placeholder = "List item",
                        editing = editing,
                        callbacks = callbacks,
                    )
                }
            }
        }

        is TodoListBlock -> Column(modifier, verticalArrangement = Arrangement.spacedBy(1.dp)) {
            block.items.forEach { item ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Live only while editing: outside content editing the object owns every gesture inside
                    // it, and a live checkbox would let a tap meant to select or drag the frame tick a box.
                    Checkbox(
                        checked = item.checked,
                        enabled = editing,
                        onCheckedChange = { callbacks.onToggleTodo(item.id) },
                    )
                    SceneEditableLine(
                        objectId = objectId,
                        itemId = item.id,
                        nodes = item.content,
                        style = MaterialTheme.typography.bodyMedium,
                        placeholder = "To-do",
                        editing = editing,
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
            Column(Modifier.padding(start = 8.dp)) {
                block.children.forEach { child ->
                    SceneObjectContent(child, objectId, editing, callbacks, Modifier.fillMaxWidth())
                }
            }
        }

        is CalloutBlock -> Column(
            modifier
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(8.dp),
        ) {
            Text(
                block.title.ifBlank { "Callout" },
                style = MaterialTheme.typography.labelLarge,
            )
            // A collapsed callout on a page shows only its title, exactly as it does in Flow. Expanding is a
            // content edit, so it belongs to the content-editing pass rather than to placement.
            if (!block.collapsed) {
                block.children.forEach { child ->
                    SceneObjectContent(child, objectId, editing, callbacks, Modifier.fillMaxWidth())
                }
            }
        }

        // A code block holds a plain string, not inline nodes. One unstyled run is the honest description of
        // that, and it keeps the editing path identical to every other line.
        is CodeBlock -> SceneEditableLine(
            objectId = objectId,
            itemId = null,
            nodes = listOf(InlineText(block.code)),
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            placeholder = "Code",
            editing = editing,
            callbacks = callbacks,
            modifier = modifier
                .clip(RoundedCornerShape(6.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(8.dp),
        )

        is DividerBlock -> Box(modifier.fillMaxWidth().padding(vertical = 6.dp)) {
            HorizontalDivider()
        }

        is ContainerBlock -> if (block.axis == ContainerAxis.Row) {
            Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                block.children.forEach { child ->
                    Box(Modifier.weight(1f)) { SceneObjectContent(child, objectId, editing, callbacks) }
                }
            }
        } else {
            Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                block.children.forEach { child ->
                    SceneObjectContent(child, objectId, editing, callbacks, Modifier.fillMaxWidth())
                }
            }
        }

        is TableBlock -> SceneTable(block, modifier)

        is ShapeBlock -> SceneShape(block, modifier)

        // Blocks a later phase owns render as an honest, labelled placeholder rather than a half-working
        // editor. Silently showing nothing would make placed content look lost and invite the user to
        // "fix" it by overwriting what is actually preserved intact.
        is ImageBlock -> ScenePlaceholder("Image", block.caption.ifBlank { block.source }, modifier)
        is ChartBlock -> ScenePlaceholder("Chart", "Chart Studio arrives in a later phase.", modifier)
        is MathBlock -> ScenePlaceholder("Equation", block.tex.ifBlank { "empty" }, modifier)
        is MermaidBlock -> ScenePlaceholder("Diagram", block.code.ifBlank { "empty" }, modifier)
        is EmbedBlock -> ScenePlaceholder("Embed", block.url.ifBlank { "empty" }, modifier)
        is FileBlock -> ScenePlaceholder("File", block.name.ifBlank { "attachment" }, modifier)

        is UnknownBlock -> ScenePlaceholder(
            title = "Preserved content",
            detail = "Saved by another version. Kept exactly as stored.",
            modifier = modifier,
        )

        else -> ScenePlaceholder(
            title = block::class.simpleName?.removeSuffix("Block") ?: "Block",
            detail = block.plainText().take(80).ifBlank { "Editing arrives with a later phase." },
            modifier = modifier,
        )
    }
}

/**
 * One line of a scene object's text.
 *
 * Outside content editing there is deliberately **no text field at all** — only a `Text`. A read-only field
 * would still take focus and still place a caret, which is precisely how a tap meant to select an object ends
 * up opening a keyboard instead. Not existing cannot do that.
 */
@Composable
private fun SceneEditableLine(
    objectId: String,
    itemId: String?,
    nodes: List<InlineNode>,
    style: TextStyle,
    placeholder: String,
    editing: Boolean,
    callbacks: SceneContentCallbacks,
    modifier: Modifier = Modifier,
) {
    val text = nodes.plainText()
    val styled = sceneInlineSpans(nodes)

    if (!editing) {
        Text(
            text = if (text.isEmpty()) AnnotatedString(placeholder) else styled,
            style = style.copy(
                color = if (text.isEmpty()) {
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                } else {
                    LocalContentColor.current
                },
            ),
            modifier = modifier.fillMaxWidth(),
        )
        return
    }

    var value by remember(objectId, itemId) { mutableStateOf(TextFieldValue(text, TextRange(text.length))) }
    // The document is the source of truth. When the text changes underneath the field — an undo, a remote
    // merge — the field is re-seeded and the caret clamped, so a history rewrite is visible immediately
    // instead of being masked by stale local state.
    if (value.text != text) {
        value = value.copy(text = text, selection = TextRange(value.selection.start.coerceIn(0, text.length)))
    }

    val focus = remember(objectId, itemId) { FocusRequester() }
    // Editing was entered deliberately, so the keyboard should be there without a second tap.
    LaunchedEffect(objectId, itemId) { runCatching { focus.requestFocus() } }

    val transformation = remember(styled) { SceneInlineTransformation(styled) }

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
                    callbacks.onEdit(SceneTextEdit(objectId, itemId, before, next.text))
                }
            },
            visualTransformation = transformation,
            textStyle = style.copy(color = LocalContentColor.current),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            modifier = Modifier.fillMaxWidth().focusRequester(focus),
        )
    }
}

/** A table placed on a page: readable at its real content. Cell editing belongs to the Phase-4 table work. */
@Composable
private fun SceneTable(block: TableBlock, modifier: Modifier = Modifier) {
    Column(
        modifier
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Row(Modifier.fillMaxWidth()) {
            block.headers.forEach { cell ->
                SceneTableCell(cell.content.plainText(), header = true, modifier = Modifier.weight(1f))
            }
        }
        block.rows.forEach { row ->
            HorizontalDivider()
            Row(Modifier.fillMaxWidth()) {
                row.forEach { cell ->
                    SceneTableCell(cell.content.plainText(), header = false, modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun SceneTableCell(text: String, header: Boolean, modifier: Modifier = Modifier) {
    Text(
        text = text.ifBlank { " " },
        style = if (header) MaterialTheme.typography.labelMedium else MaterialTheme.typography.bodySmall,
        modifier = modifier.padding(horizontal = 6.dp, vertical = 4.dp),
    )
}

/** A shape drawn at its own fill and stroke, with its label centred — not a grey box with a type name. */
@Composable
private fun SceneShape(block: ShapeBlock, modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .fillMaxHeight()
            .clip(RoundedCornerShape(8.dp))
            .background(Color(block.fillColor)),
        contentAlignment = Alignment.Center,
    ) {
        if (block.text.isNotBlank()) {
            Text(
                block.text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.padding(6.dp),
            )
        }
    }
}

@Composable
private fun SceneListRow(marker: String, content: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.Top) {
        Text(
            marker,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.width(20.dp),
        )
        Box(Modifier.weight(1f)) { content() }
    }
}

@Composable
private fun ScenePlaceholder(title: String, detail: String, modifier: Modifier = Modifier) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(8.dp),
    ) {
        Text(title, style = MaterialTheme.typography.labelMedium)
        Text(
            detail,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Maps [FlowInlineText]'s toolkit-independent runs onto Compose spans.
 *
 * The *same* run model the Flow host renders, so a paragraph looks identical whether it is being written in
 * Flow or placed on a page. A second, page-specific styling path would be the thing that eventually makes
 * one of them show a link as plain text.
 */
@Composable
private fun sceneInlineSpans(nodes: List<InlineNode>): AnnotatedString {
    val colors = MaterialTheme.colorScheme
    return remember(nodes, colors) {
        val text = nodes.plainText()
        val spans = FlowInlineText.runsOf(nodes).mapNotNull { run ->
            if (run.style.isPlain) return@mapNotNull null
            val start = run.start.coerceIn(0, text.length)
            val end = run.end.coerceIn(start, text.length)
            if (start == end) return@mapNotNull null
            AnnotatedString.Range(run.style.toSceneSpanStyle(colors), start, end)
        }
        AnnotatedString(text, spans)
    }
}

private fun FlowInlineStyle.toSceneSpanStyle(colors: ColorScheme): SpanStyle = SpanStyle(
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
        underline && strikethrough ->
            TextDecoration.combine(listOf(TextDecoration.Underline, TextDecoration.LineThrough))
        underline || link != null -> TextDecoration.Underline
        strikethrough -> TextDecoration.LineThrough
        else -> null
    },
)

/**
 * Shows a block's inline styling inside a plain-text field.
 *
 * [OffsetMapping.Identity] is correct precisely because nothing is added, removed or reordered — only
 * painted. Spans are re-clamped against the live field text because the field can be a keystroke ahead of
 * the document, and an out-of-range span would crash rather than merely look wrong.
 */
private class SceneInlineTransformation(private val styled: AnnotatedString) : VisualTransformation {
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
private fun sceneHeadingStyle(level: Int): TextStyle = when (level) {
    1 -> MaterialTheme.typography.titleLarge
    2 -> MaterialTheme.typography.titleMedium
    else -> MaterialTheme.typography.titleSmall
}.copy(fontWeight = FontWeight.SemiBold)
