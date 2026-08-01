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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
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
import com.norfold.app.domain.effectiveStyleAssignment
import com.norfold.app.domain.ShapeBlock
import com.norfold.app.domain.TableBlock
import com.norfold.app.domain.TodoListBlock
import com.norfold.app.domain.UnknownBlock
import com.norfold.app.domain.plainText
import com.norfold.app.editor.flow.FlowInlineStyle
import com.norfold.app.editor.flow.FlowInlineText
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
import com.norfold.app.domain.style.resolvedShapeFill
import com.norfold.app.domain.style.resolvedShapeStroke
import com.norfold.app.ui.editor.style.resolvedShapeMetrics
import com.norfold.app.ui.editor.style.backgroundPaint
import androidx.compose.foundation.border

/**
 * A text edit inside a scene object's content, addressed the way the engines address it.
 *
 * [blockId] is the block the caret is genuinely in — **not** the object's root block. A scene object's content
 * is a tree: a quote, a callout and a container each hold children, and each child is independently editable.
 * Carrying only the object id was the defect: every child's keystrokes were applied to the root block, so
 * editing the second paragraph of a quote rewrote the first one and typing in a nested container did nothing
 * at all. [itemId] narrows further, to one row of a list or checklist.
 */
data class SceneTextEdit(
    val objectId: String,
    val blockId: String,
    val itemId: String?,
    val before: String,
    val after: String,
)

/** What a scene object's content reports back to the runtime. */
class SceneContentCallbacks(
    val onEdit: (SceneTextEdit) -> Unit,
    val onToggleTodo: (blockId: String, itemId: String) -> Unit,
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
    stylesheet: Stylesheet = Stylesheet.defaults(),
    previewRole: ColorRole? = null,
    previewPaint: ColorPaint? = null,
    previewTargetIds: Set<String> = emptySet(),
    wrapperStyles: List<WrapperStyleSource> = emptyList(),
    contentScale: DocumentContentScale = DocumentContentScale.One,
) {
    when (block) {
        is ParagraphBlock -> SceneEditableLine(
            objectId = objectId,
            blockId = block.id,
            itemId = null,
            nodes = block.content,
            style = resolvedSceneTextStyle(block, stylesheet, MaterialTheme.typography.bodyMedium, wrapperStyles, previewRole, previewPaint, previewTargetIds, contentScale),
            placeholder = "Text",
            editing = editing,
            callbacks = callbacks,
            contentScale = contentScale,
            modifier = modifier,
        )

        is HeadingBlock -> SceneEditableLine(
            objectId = objectId,
            blockId = block.id,
            itemId = null,
            nodes = block.content,
            style = resolvedSceneTextStyle(block, stylesheet, sceneHeadingStyle(block.level), wrapperStyles, previewRole, previewPaint, previewTargetIds, contentScale),
            placeholder = "Heading ${block.level}",
            editing = editing,
            callbacks = callbacks,
            contentScale = contentScale,
            modifier = modifier,
        )

        is BulletListBlock -> Column(modifier, verticalArrangement = Arrangement.spacedBy(contentScale.dp(1f))) {
            block.items.forEach { item ->
                SceneListRow("•", contentScale) {
                    SceneEditableLine(
                        objectId = objectId,
                        blockId = block.id,
                        itemId = item.id,
                        nodes = item.content,
                        style = resolvedSceneTextStyle(block, stylesheet, MaterialTheme.typography.bodyMedium, wrapperStyles, previewRole, previewPaint, previewTargetIds, contentScale),
                        placeholder = "List item",
                        editing = editing,
                        callbacks = callbacks,
                        contentScale = contentScale,
                    )
                }
            }
        }

        is NumberedListBlock -> Column(modifier, verticalArrangement = Arrangement.spacedBy(contentScale.dp(1f))) {
            block.items.forEachIndexed { index, item ->
                SceneListRow("${block.start + index}.", contentScale) {
                    SceneEditableLine(
                        objectId = objectId,
                        blockId = block.id,
                        itemId = item.id,
                        nodes = item.content,
                        style = resolvedSceneTextStyle(block, stylesheet, MaterialTheme.typography.bodyMedium, wrapperStyles, previewRole, previewPaint, previewTargetIds, contentScale),
                        placeholder = "List item",
                        editing = editing,
                        callbacks = callbacks,
                        contentScale = contentScale,
                    )
                }
            }
        }

        is TodoListBlock -> Column(modifier, verticalArrangement = Arrangement.spacedBy(contentScale.dp(1f))) {
            block.items.forEach { item ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Live only while editing: outside content editing the object owns every gesture inside
                    // it, and a live checkbox would let a tap meant to select or drag the frame tick a box.
                    Checkbox(
                        checked = item.checked,
                        enabled = editing,
                        onCheckedChange = { callbacks.onToggleTodo(block.id, item.id) },
                    )
                    SceneEditableLine(
                        objectId = objectId,
                        blockId = block.id,
                        itemId = item.id,
                        nodes = item.content,
                        style = resolvedSceneTextStyle(block, stylesheet, MaterialTheme.typography.bodyMedium, wrapperStyles, previewRole, previewPaint, previewTargetIds, contentScale),
                        placeholder = "To-do",
                        editing = editing,
                        callbacks = callbacks,
                        contentScale = contentScale,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }

        is QuoteBlock -> Row(modifier.height(IntrinsicSize.Min)) {
            val childStyles = listOf(WrapperStyleSource(block.id, block.effectiveStyleAssignment())) + wrapperStyles
            Box(
                Modifier
                    .width(contentScale.dp(3f))
                    .fillMaxHeight()
                    .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(contentScale.dp(2f))),
            )
            Column(Modifier.padding(start = contentScale.dp(8f))) {
                SceneStyledChildren(
                    block.children, objectId, editing, callbacks, stylesheet,
                    previewRole, previewPaint, previewTargetIds, childStyles, contentScale,
                )
            }
        }

        is CalloutBlock -> Column(
            modifier
                .clip(RoundedCornerShape(contentScale.dp(8f)))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(contentScale.dp(8f)),
        ) {
            val childStyles = listOf(WrapperStyleSource(block.id, block.effectiveStyleAssignment())) + wrapperStyles
            Text(
                block.title.ifBlank { "Callout" },
                style = resolvedSceneTextStyle(block, stylesheet, MaterialTheme.typography.labelLarge, wrapperStyles, previewRole, previewPaint, previewTargetIds, contentScale),
            )
            // A collapsed callout on a page shows only its title, exactly as it does in Flow. Expanding is a
            // content edit, so it belongs to the content-editing pass rather than to placement.
            if (!block.collapsed) {
                SceneStyledChildren(
                    block.children, objectId, editing, callbacks, stylesheet,
                    previewRole, previewPaint, previewTargetIds, childStyles, contentScale,
                )
            }
        }

        // Rendered honestly, and **read-only**. A code block stores a plain `code` string, not inline nodes,
        // so `BlockEditorSession.editText` — which edits `editableInline()` — has no path to it and returns
        // the block untouched. Presenting a live caret over content no keystroke can reach is worse than
        // showing it as not-yet-editable: the user would type a line, see it vanish, and reasonably conclude
        // the document had lost it. Editing arrives with the code-block work, not by faking it here.
        is CodeBlock -> SceneReadOnlyCode(
            block, stylesheet, wrapperStyles, previewRole, previewPaint, previewTargetIds, contentScale, modifier,
        )

        is DividerBlock -> Box(modifier.fillMaxWidth().padding(vertical = contentScale.dp(6f))) {
            HorizontalDivider(thickness = contentScale.dp(1f))
        }

        is ContainerBlock -> if (block.axis == ContainerAxis.Row) {
            Row(modifier, horizontalArrangement = Arrangement.spacedBy(contentScale.dp(8f))) {
                block.children.forEach { child ->
                    Box(Modifier.weight(1f)) {
                        SceneObjectContent(
                            child, objectId, editing, callbacks,
                            stylesheet = stylesheet,
                            previewRole = previewRole,
                            previewPaint = previewPaint,
                            previewTargetIds = previewTargetIds,
                            wrapperStyles = wrapperStyles,
                            contentScale = contentScale,
                        )
                    }
                }
            }
        } else {
            Column(modifier) {
                SceneStyledChildren(
                    block.children, objectId, editing, callbacks, stylesheet,
                    previewRole, previewPaint, previewTargetIds, wrapperStyles, contentScale,
                )
            }
        }

        is TableBlock -> SceneTable(block, contentScale, modifier)

        is ShapeBlock -> SceneShape(
            block,
            stylesheet,
            modifier,
            previewRole,
            previewPaint,
            previewTargetIds,
            contentScale,
        )

        // Blocks a later phase owns render as an honest, labelled placeholder rather than a half-working
        // editor. Silently showing nothing would make placed content look lost and invite the user to
        // "fix" it by overwriting what is actually preserved intact.
        is ImageBlock -> ScenePlaceholder("Image", block.caption.ifBlank { block.source }, contentScale, modifier)
        is ChartBlock -> ScenePlaceholder("Chart", "Chart Studio arrives in a later phase.", contentScale, modifier)
        is MathBlock -> ScenePlaceholder("Equation", block.tex.ifBlank { "empty" }, contentScale, modifier)
        is MermaidBlock -> ScenePlaceholder("Diagram", block.code.ifBlank { "empty" }, contentScale, modifier)
        is EmbedBlock -> ScenePlaceholder("Embed", block.url.ifBlank { "empty" }, contentScale, modifier)
        is FileBlock -> ScenePlaceholder("File", block.name.ifBlank { "attachment" }, contentScale, modifier)

        is UnknownBlock -> ScenePlaceholder(
            title = "Preserved content",
            detail = "Saved by another version. Kept exactly as stored.",
            contentScale = contentScale,
            modifier = modifier,
        )

        else -> ScenePlaceholder(
            title = block::class.simpleName?.removeSuffix("Block") ?: "Block",
            detail = block.plainText().take(80).ifBlank { "Editing arrives with a later phase." },
            contentScale = contentScale,
            modifier = modifier,
        )
    }
}

@Composable
private fun SceneStyledChildren(
    children: List<DocumentBlock>,
    objectId: String,
    editing: Boolean,
    callbacks: SceneContentCallbacks,
    stylesheet: Stylesheet,
    previewRole: ColorRole?,
    previewPaint: ColorPaint?,
    previewTargetIds: Set<String>,
    wrapperStyles: List<WrapperStyleSource>,
    contentScale: DocumentContentScale,
) {
    val metrics = remember(children, stylesheet, wrapperStyles, contentScale) {
        children.map { contentScale.blockMetrics(resolvedBlockMetrics(it, stylesheet, wrapperStyles)) }
    }
    children.forEachIndexed { index, child ->
        val current = metrics[index]
        val top = if (index == 0) current.beforeDp
        else collapsedBlockSpacing(metrics[index - 1].afterDp, current.beforeDp)
        val bottom = if (index == children.lastIndex) current.afterDp else 0f
        SceneObjectContent(
            child,
            objectId,
            editing,
            callbacks,
            Modifier.fillMaxWidth().padding(top = top.dp, bottom = bottom.dp),
            stylesheet,
            previewRole,
            previewPaint,
            previewTargetIds,
            wrapperStyles,
            contentScale,
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
    blockId: String,
    itemId: String?,
    nodes: List<InlineNode>,
    style: TextStyle,
    placeholder: String,
    editing: Boolean,
    callbacks: SceneContentCallbacks,
    contentScale: DocumentContentScale,
    modifier: Modifier = Modifier,
) {
    val text = nodes.plainText()
    val styled = sceneInlineSpans(nodes, contentScale)

    if (!editing) {
        Text(
            text = if (text.isEmpty()) AnnotatedString(placeholder) else styled,
            style = style.copy(
                color = if (text.isEmpty()) {
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                } else {
                    resolvedContentColor(style.color, LocalContentColor.current)
                },
            ),
            modifier = modifier.fillMaxWidth(),
        )
        return
    }

    // Keyed on the *addressed field* rather than the object, so the caret and the local text belong to the
    // exact block being edited. Keying on objectId alone would share one field's state across every child of
    // a quote or container.
    var value by remember(blockId, itemId) { mutableStateOf(TextFieldValue(text, TextRange(text.length))) }
    // The document is the source of truth. When the text changes underneath the field — an undo, a remote
    // merge — the field is re-seeded and the caret clamped, so a history rewrite is visible immediately
    // instead of being masked by stale local state.
    if (value.text != text) {
        value = value.copy(text = text, selection = TextRange(value.selection.start.coerceIn(0, text.length)))
    }

    val focus = remember(blockId, itemId) { FocusRequester() }
    // Editing was entered deliberately, so the keyboard should be there without a second tap.
    LaunchedEffect(blockId, itemId) { runCatching { focus.requestFocus() } }

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
                    callbacks.onEdit(SceneTextEdit(objectId, blockId, itemId, before, next.text))
                }
            },
            visualTransformation = transformation,
            textStyle = style.copy(
                color = resolvedContentColor(style.color, LocalContentColor.current),
            ),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            modifier = Modifier.fillMaxWidth().focusRequester(focus),
        )
    }
}

/**
 * A code block on a page: its real code, in its slab, monospaced — and not editable.
 *
 * The "not editable" is the point rather than an omission. `code` is a plain string outside the inline-node
 * vocabulary every other editable line uses, so there is no correct mutation path to wire yet, and a text
 * field here would accept keystrokes the controller would silently discard.
 */
@Composable
private fun SceneReadOnlyCode(
    block: CodeBlock,
    stylesheet: Stylesheet,
    wrapperStyles: List<WrapperStyleSource>,
    previewRole: ColorRole?,
    previewPaint: ColorPaint?,
    previewTargetIds: Set<String>,
    contentScale: DocumentContentScale,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(contentScale.dp(6f)))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(contentScale.dp(8f)),
    ) {
        if (block.language.isNotBlank()) {
            Text(
                block.language,
                style = contentScale.textStyle(MaterialTheme.typography.labelSmall),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = block.code.ifBlank { "Empty code block" },
            style = resolvedSceneTextStyle(
                block,
                stylesheet,
                MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                wrapperStyles,
                previewRole,
                previewPaint,
                previewTargetIds,
                contentScale,
            ),
        )
    }
}

/** A table placed on a page: readable at its real content. Cell editing belongs to the Phase-4 table work. */
@Composable
private fun SceneTable(
    block: TableBlock,
    contentScale: DocumentContentScale,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .clip(RoundedCornerShape(contentScale.dp(6f)))
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Row(Modifier.fillMaxWidth()) {
            block.headers.forEach { cell ->
                SceneTableCell(cell.content.plainText(), true, contentScale, Modifier.weight(1f))
            }
        }
        block.rows.forEach { row ->
            HorizontalDivider(thickness = contentScale.dp(1f))
            Row(Modifier.fillMaxWidth()) {
                row.forEach { cell ->
                    SceneTableCell(cell.content.plainText(), false, contentScale, Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun SceneTableCell(
    text: String,
    header: Boolean,
    contentScale: DocumentContentScale = DocumentContentScale.One,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text.ifBlank { " " },
        style = contentScale.textStyle(
            if (header) MaterialTheme.typography.labelMedium else MaterialTheme.typography.bodySmall,
        ),
        modifier = modifier.padding(horizontal = contentScale.dp(6f), vertical = contentScale.dp(4f)),
    )
}

/** A shape drawn at its own fill and stroke, with its label centred — not a grey box with a type name. */
@Composable
private fun SceneShape(
    block: ShapeBlock,
    stylesheet: Stylesheet,
    modifier: Modifier = Modifier,
    previewRole: ColorRole? = null,
    previewPaint: ColorPaint? = null,
    previewTargetIds: Set<String> = emptySet(),
    contentScale: DocumentContentScale,
) {
    val metrics = resolvedShapeMetrics(block, stylesheet)
    val shape = RoundedCornerShape(contentScale.dp(metrics.cornerRadiusDp))
    val previewApplies = block.id in previewTargetIds
    val fill = previewPaint.takeIf { previewApplies && previewRole == ColorRole.FILL }
        ?: resolvedShapeFill(block, stylesheet)
    val stroke = previewPaint.takeIf { previewApplies && previewRole == ColorRole.STROKE }
        ?: resolvedShapeStroke(block, stylesheet)
    Box(
        modifier
            .fillMaxWidth()
            .fillMaxHeight()
            .graphicsLayer { alpha = metrics.opacity }
            .then(if (metrics.shadowDp > 0f) Modifier.shadow(contentScale.dp(metrics.shadowDp), shape) else Modifier)
            .clip(shape)
            .backgroundPaint(fill)
            .border(contentScale.dp(metrics.strokeWidthDp), Color(stroke.representative().toArgb()), shape),
        contentAlignment = Alignment.Center,
    ) {
        if (block.text.isNotBlank()) {
            Text(
                block.text,
                style = resolvedSceneTextStyle(
                    block, stylesheet, MaterialTheme.typography.bodyMedium,
                    emptyList(), previewRole, previewPaint, previewTargetIds, contentScale,
                ),
                modifier = Modifier.padding(contentScale.dp(metrics.paddingDp)),
            )
        }
    }
}

@Composable
private fun SceneListRow(
    marker: String,
    contentScale: DocumentContentScale,
    content: @Composable () -> Unit,
) {
    Row(verticalAlignment = Alignment.Top) {
        Text(
            marker,
            style = contentScale.textStyle(MaterialTheme.typography.bodyMedium),
            modifier = Modifier.width(contentScale.dp(20f)),
        )
        Box(Modifier.weight(1f)) { content() }
    }
}

@Composable
private fun ScenePlaceholder(
    title: String,
    detail: String,
    contentScale: DocumentContentScale,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(contentScale.dp(6f)))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(contentScale.dp(8f)),
    ) {
        Text(title, style = contentScale.textStyle(MaterialTheme.typography.labelMedium))
        Text(
            detail,
            style = contentScale.textStyle(MaterialTheme.typography.labelSmall),
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
private fun sceneInlineSpans(
    nodes: List<InlineNode>,
    contentScale: DocumentContentScale,
): AnnotatedString {
    val colors = MaterialTheme.colorScheme
    return remember(nodes, colors, contentScale) {
        val text = nodes.plainText()
        val spans = FlowInlineText.runsOf(nodes).mapNotNull { run ->
            if (run.style.isPlain) return@mapNotNull null
            val start = run.start.coerceIn(0, text.length)
            val end = run.end.coerceIn(start, text.length)
            if (start == end) return@mapNotNull null
            AnnotatedString.Range(run.style.toSceneSpanStyle(colors, contentScale), start, end)
        }
        AnnotatedString(text, spans)
    }
}

private fun FlowInlineStyle.toSceneSpanStyle(
    colors: ColorScheme,
    contentScale: DocumentContentScale,
): SpanStyle = SpanStyle(
    fontWeight = if (bold) FontWeight.Bold else null,
    fontStyle = if (italic) FontStyle.Italic else null,
    fontSize = fontSizeSp?.coerceIn(6f, 144f)?.let { contentScale.scalar(it).sp } ?: TextUnit.Unspecified,
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
private fun resolvedSceneTextStyle(
    block: DocumentBlock,
    sheet: Stylesheet,
    base: TextStyle,
    wrapperStyles: List<WrapperStyleSource>,
    previewRole: ColorRole?,
    previewPaint: ColorPaint?,
    previewTargetIds: Set<String>,
    contentScale: DocumentContentScale,
): TextStyle = contentScale.textStyle(resolvedTextStyle(
    block = block,
    sheet = sheet,
    base = base,
    wrappersNearestFirst = wrapperStyles,
    previewRole = previewRole,
    previewPaint = previewPaint,
    previewTargetIds = previewTargetIds,
))

@Composable
private fun sceneHeadingStyle(level: Int): TextStyle = when (level) {
    1 -> MaterialTheme.typography.titleLarge
    2 -> MaterialTheme.typography.titleMedium
    else -> MaterialTheme.typography.titleSmall
}.copy(fontWeight = FontWeight.SemiBold)
