package com.norfold.app.ui.editor.document

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
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
import com.norfold.app.editor.flags.EditorFeatureFlag
import com.norfold.app.ui.editor.editorFlags
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
import com.norfold.app.editor.chart.ChartBlockAdapter
import com.norfold.app.editor.chart.ChartDataResolver
import com.norfold.app.editor.chart.ChartCalculationDataProvider
import com.norfold.app.ui.editor.chart.ChartRenderer
import com.norfold.app.editor.image.ImageBlockAdapter
import com.norfold.app.editor.shape.LoadedShapeDocument
import com.norfold.app.editor.shape.ShapeBlockAdapter
import com.norfold.app.editor.shape.ShapeDocument
import com.norfold.app.editor.shape.ShapeLayerEditor
import com.norfold.app.ui.editor.image.ImageFocusRenderer
import com.norfold.app.ui.editor.shape.ShapeDocumentPreview
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
    /** A cell was addressed. The runtime seeds the draft; the surface holds no cell state of its own. */
    val onTableCellActivate: (legacyRow: Int, column: Int) -> Unit = { _, _ -> },
    /** A keystroke inside the addressed cell. Records the draft; it does not write. */
    val onTableCellDraft: (legacyRow: Int, column: Int, value: String) -> Unit = { _, _, _ -> },
    /** The user finished the cell: Done on the keyboard, or focus leaving the field. Writes once. */
    val onTableCellCommit: (legacyRow: Int, column: Int, value: String) -> Unit = { _, _, _ -> },
    val onOpenTableInSpreadsheet: (String) -> Unit = {},
    val onOpenChart: (String) -> Unit = {},
    val onOpenImage: (String) -> Unit = {},
    val onOpenShape: (String) -> Unit = {},
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
    renderScale: DocumentRenderScale = DocumentRenderScale.At100Percent,
    /** The table cell the runtime has addressed, as `row to column`; row `-1` is the header. */
    activeTableCell: Pair<Int, Int>? = null,
    /** The uncommitted text of [activeTableCell]. Runtime-owned, so a recomposition cannot lose it. */
    tableCellDraft: String? = null,
    chartCalculationProvider: ChartCalculationDataProvider? = null,
    chartDataRevision: Long = 0,
) {
    when (block) {
        is ParagraphBlock -> SceneEditableLine(
            objectId = objectId,
            blockId = block.id,
            itemId = null,
            nodes = block.content,
            style = resolvedSceneTextStyle(block, stylesheet, MaterialTheme.typography.bodyMedium, wrapperStyles, previewRole, previewPaint, previewTargetIds, renderScale),
            placeholder = "Text",
            editing = editing,
            callbacks = callbacks,
            renderScale = renderScale,
            modifier = modifier,
        )

        is HeadingBlock -> SceneEditableLine(
            objectId = objectId,
            blockId = block.id,
            itemId = null,
            nodes = block.content,
            style = resolvedSceneTextStyle(block, stylesheet, sceneHeadingStyle(block.level), wrapperStyles, previewRole, previewPaint, previewTargetIds, renderScale),
            placeholder = "Heading ${block.level}",
            editing = editing,
            callbacks = callbacks,
            renderScale = renderScale,
            modifier = modifier,
        )

        is BulletListBlock -> Column(modifier, verticalArrangement = Arrangement.spacedBy(renderScale.pointToDp(1f))) {
            block.items.forEach { item ->
                SceneListRow("•", renderScale) {
                    SceneEditableLine(
                        objectId = objectId,
                        blockId = block.id,
                        itemId = item.id,
                        nodes = item.content,
                        style = resolvedSceneTextStyle(block, stylesheet, MaterialTheme.typography.bodyMedium, wrapperStyles, previewRole, previewPaint, previewTargetIds, renderScale),
                        placeholder = "List item",
                        editing = editing,
                        callbacks = callbacks,
                        renderScale = renderScale,
                    )
                }
            }
        }

        is NumberedListBlock -> Column(modifier, verticalArrangement = Arrangement.spacedBy(renderScale.pointToDp(1f))) {
            block.items.forEachIndexed { index, item ->
                SceneListRow("${block.start + index}.", renderScale) {
                    SceneEditableLine(
                        objectId = objectId,
                        blockId = block.id,
                        itemId = item.id,
                        nodes = item.content,
                        style = resolvedSceneTextStyle(block, stylesheet, MaterialTheme.typography.bodyMedium, wrapperStyles, previewRole, previewPaint, previewTargetIds, renderScale),
                        placeholder = "List item",
                        editing = editing,
                        callbacks = callbacks,
                        renderScale = renderScale,
                    )
                }
            }
        }

        is TodoListBlock -> Column(modifier, verticalArrangement = Arrangement.spacedBy(renderScale.pointToDp(1f))) {
            block.items.forEach { item ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    DocumentTodoMarker(
                        checked = item.checked,
                        editing = editing,
                        renderScale = renderScale,
                        onToggle = { callbacks.onToggleTodo(block.id, item.id) },
                    )
                    Spacer(Modifier.width(renderScale.pointToDp(4f)))
                    SceneEditableLine(
                        objectId = objectId,
                        blockId = block.id,
                        itemId = item.id,
                        nodes = item.content,
                        style = resolvedSceneTextStyle(block, stylesheet, MaterialTheme.typography.bodyMedium, wrapperStyles, previewRole, previewPaint, previewTargetIds, renderScale),
                        placeholder = "To-do",
                        editing = editing,
                        callbacks = callbacks,
                        renderScale = renderScale,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }

        is QuoteBlock -> Row(modifier.height(IntrinsicSize.Min)) {
            val childStyles = listOf(WrapperStyleSource(block.id, block.effectiveStyleAssignment())) + wrapperStyles
            Box(
                Modifier
                    .width(renderScale.pointToDp(3f))
                    .fillMaxHeight()
                    .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(renderScale.pointToDp(2f))),
            )
            Column(Modifier.padding(start = renderScale.pointToDp(8f))) {
                SceneStyledChildren(
                    block.children, objectId, editing, callbacks, stylesheet,
                    previewRole, previewPaint, previewTargetIds, childStyles, renderScale,
                )
            }
        }

        is CalloutBlock -> Column(
            modifier
                .clip(RoundedCornerShape(renderScale.pointToDp(8f)))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(renderScale.pointToDp(8f)),
        ) {
            val childStyles = listOf(WrapperStyleSource(block.id, block.effectiveStyleAssignment())) + wrapperStyles
            Text(
                block.title.ifBlank { "Callout" },
                style = resolvedSceneTextStyle(block, stylesheet, MaterialTheme.typography.labelLarge, wrapperStyles, previewRole, previewPaint, previewTargetIds, renderScale),
            )
            // A collapsed callout on a page shows only its title, exactly as it does in Flow. Expanding is a
            // content edit, so it belongs to the content-editing pass rather than to placement.
            if (!block.collapsed) {
                SceneStyledChildren(
                    block.children, objectId, editing, callbacks, stylesheet,
                    previewRole, previewPaint, previewTargetIds, childStyles, renderScale,
                )
            }
        }

        is CodeBlock -> SceneCode(
            block, objectId, editing, callbacks, stylesheet, wrapperStyles,
            previewRole, previewPaint, previewTargetIds, renderScale, modifier,
        )

        is DividerBlock -> Box(modifier.fillMaxWidth().padding(vertical = renderScale.pointToDp(6f))) {
            HorizontalDivider(thickness = renderScale.pointToDp(1f))
        }

        is ContainerBlock -> if (block.axis == ContainerAxis.Row) {
            Row(modifier, horizontalArrangement = Arrangement.spacedBy(renderScale.pointToDp(8f))) {
                block.children.forEach { child ->
                    Box(Modifier.weight(1f)) {
                        SceneObjectContent(
                            child, objectId, editing, callbacks,
                            stylesheet = stylesheet,
                            previewRole = previewRole,
                            previewPaint = previewPaint,
                            previewTargetIds = previewTargetIds,
                            wrapperStyles = wrapperStyles,
                            renderScale = renderScale,
                            activeTableCell = activeTableCell,
                            tableCellDraft = tableCellDraft,
                        )
                    }
                }
            }
        } else {
            Column(modifier) {
                SceneStyledChildren(
                    block.children, objectId, editing, callbacks, stylesheet,
                    previewRole, previewPaint, previewTargetIds, wrapperStyles, renderScale,
                    activeTableCell, tableCellDraft,
                )
            }
        }

        is TableBlock -> SceneTable(block, editing, callbacks, renderScale, modifier, activeTableCell, tableCellDraft)

        // A shape that has been built renders its own geometry; one that never has keeps the legacy
        // rounded-rectangle rendering byte for byte. That split is the non-destructive promise: opening
        // Shape Builder is what turns a legacy shape into a document, not merely having the flag on.
        is ShapeBlock -> if (editorFlags.isEnabled(EditorFeatureFlag.SHAPE_BUILDER)) {
            val loaded = remember(block) { ShapeBlockAdapter.load(block) }
            Box(modifier.fillMaxWidth().fillMaxHeight()) {
                if (loaded.origin == LoadedShapeDocument.Origin.CANONICAL) {
                    SceneShapeDocument(
                        loaded.document,
                        previewRole,
                        previewPaint,
                        block.id in previewTargetIds,
                        Modifier.fillMaxSize(),
                    )
                } else {
                    SceneShape(
                        block, stylesheet, Modifier.fillMaxSize(),
                        previewRole, previewPaint, previewTargetIds, renderScale,
                    )
                }
                if (editing && loaded.isEditable) {
                    TextButton(
                        onClick = { callbacks.onOpenShape(block.id) },
                        modifier = Modifier.align(Alignment.BottomEnd),
                    ) { Text("Edit shape") }
                }
            }
        } else {
            SceneShape(
                block,
                stylesheet,
                modifier,
                previewRole,
                previewPaint,
                previewTargetIds,
                renderScale,
            )
        }

        // Blocks a later phase owns render as an honest, labelled placeholder rather than a half-working
        // editor. Silently showing nothing would make placed content look lost and invite the user to
        // "fix" it by overwriting what is actually preserved intact.
        is ImageBlock -> if (editorFlags.isEnabled(EditorFeatureFlag.IMAGE_FOCUS)) {
            val loaded = remember(block.imageEditDocument) { ImageBlockAdapter.load(block) }
            ImageFocusRenderer(
                block,
                modifier.fillMaxSize().then(
                    if (editing && loaded.isEditable) {
                        Modifier.clickable(
                            role = Role.Button,
                            onClickLabel = "Open Image Focus",
                        ) { callbacks.onOpenImage(block.id) }
                    } else Modifier
                ),
            )
        } else {
            ScenePlaceholder("Image", block.caption.ifBlank { block.source }, renderScale, modifier)
        }
        is ChartBlock -> if (editorFlags.isEnabled(EditorFeatureFlag.CHART_STUDIO)) {
            val loaded = remember(block.chartDocument, block.vegaLiteSpec) { ChartBlockAdapter.load(block) }
            Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                ChartRenderer(
                    document = loaded.document,
                    resolution = remember(loaded.document, chartDataRevision) {
                        ChartDataResolver.resolve(loaded.document, chartCalculationProvider)
                    },
                    modifier = Modifier.fillMaxWidth().then(
                        if (editing && loaded.isEditable) {
                            Modifier.clickable(
                                role = Role.Button,
                                onClickLabel = "Open Chart Studio",
                            ) { callbacks.onOpenChart(block.id) }
                        } else Modifier
                    ),
                    heightDp = block.editorHeightDp.toInt().coerceIn(100, 560),
                )
            }
        } else {
            ScenePlaceholder("Chart", "Chart Studio is unavailable.", renderScale, modifier)
        }
        is MathBlock -> SceneSpecialSource("Equation", block.id, objectId, block.tex, editing, callbacks, renderScale, modifier)
        is MermaidBlock -> SceneSpecialSource("Diagram", block.id, objectId, block.code, editing, callbacks, renderScale, modifier)
        is EmbedBlock -> SceneCompactPreview(
            block.metadata.title.ifBlank { "Embedded link" },
            block.metadata.description.ifBlank { block.url }, renderScale, modifier,
        )
        is FileBlock -> SceneCompactPreview(
            block.name.ifBlank { "Attachment" },
            listOf(block.mimeType, sceneFileSize(block.sizeBytes)).filter(String::isNotBlank).joinToString(" · "),
            renderScale, modifier,
        )

        is UnknownBlock -> ScenePlaceholder(
            title = "Preserved content",
            detail = "Saved by another version. Kept exactly as stored.",
            renderScale = renderScale,
            modifier = modifier,
        )

        else -> ScenePlaceholder(
            title = block::class.simpleName?.removeSuffix("Block") ?: "Block",
            detail = block.plainText().take(80).ifBlank { "Editing arrives with a later phase." },
            renderScale = renderScale,
            modifier = modifier,
        )
    }
}

/**
 * A page-space Todo marker rather than a Material Checkbox.
 *
 * Material Checkbox enforces a screen-sized layout footprint, so it cannot live inside a uniformly scaled
 * page row. This custom visual measures at exactly its page-point size. In edit mode [toggleable] supplies
 * checkbox semantics and Compose's minimum-touch-target hit expansion without changing that measurement;
 * read mode exposes state but deliberately owns no click action, leaving selection/drag to the scene object.
 */
@Composable
private fun DocumentTodoMarker(
    checked: Boolean,
    editing: Boolean,
    renderScale: DocumentRenderScale,
    onToggle: () -> Unit,
) {
    val metrics = renderScale.todoMetrics()
    val primary = MaterialTheme.colorScheme.primary
    val outline = MaterialTheme.colorScheme.outline
    val onPrimary = MaterialTheme.colorScheme.onPrimary
    val interaction = if (editing) {
        Modifier.toggleable(
            value = checked,
            role = Role.Checkbox,
            onValueChange = { onToggle() },
        )
    } else {
        Modifier.semantics {
            role = Role.Checkbox
            toggleableState = if (checked) ToggleableState.On else ToggleableState.Off
        }
    }

    Canvas(
        interaction.size(metrics.contentFootprintDp.dp),
    ) {
        val edge = size.minDimension
        val border = (edge * 0.09f).coerceAtLeast(1f)
        val radius = CornerRadius(edge * 0.2f, edge * 0.2f)
        if (checked) {
            drawRoundRect(primary, cornerRadius = radius)
            val check = Path().apply {
                moveTo(edge * 0.22f, edge * 0.52f)
                lineTo(edge * 0.43f, edge * 0.72f)
                lineTo(edge * 0.8f, edge * 0.3f)
            }
            drawPath(
                path = check,
                color = onPrimary,
                style = Stroke(width = border * 1.35f, cap = StrokeCap.Round, join = StrokeJoin.Round),
            )
        } else {
            drawRoundRect(outline, cornerRadius = radius, style = Stroke(width = border))
        }
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
    renderScale: DocumentRenderScale,
    activeTableCell: Pair<Int, Int>? = null,
    tableCellDraft: String? = null,
) {
    val metrics = remember(children, stylesheet, wrapperStyles, renderScale) {
        children.map { renderScale.pointBlockMetrics(resolvedBlockMetrics(it, stylesheet, wrapperStyles)) }
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
            renderScale,
            activeTableCell,
            tableCellDraft,
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
    renderScale: DocumentRenderScale,
    modifier: Modifier = Modifier,
) {
    val text = nodes.plainText()
    val styled = sceneInlineSpans(nodes, renderScale)

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
private fun SceneCode(
    block: CodeBlock,
    objectId: String,
    editing: Boolean,
    callbacks: SceneContentCallbacks,
    stylesheet: Stylesheet,
    wrapperStyles: List<WrapperStyleSource>,
    previewRole: ColorRole?,
    previewPaint: ColorPaint?,
    previewTargetIds: Set<String>,
    renderScale: DocumentRenderScale,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(renderScale.pointToDp(6f)))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(renderScale.pointToDp(8f)),
    ) {
        if (block.language.isNotBlank()) {
            Text(
                block.language,
                style = renderScale.pointTextStyle(MaterialTheme.typography.labelSmall),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        SceneEditableLine(
            objectId = objectId,
            blockId = block.id,
            itemId = null,
            nodes = listOf(InlineText(block.code)),
            style = resolvedSceneTextStyle(
                block, stylesheet, MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                wrapperStyles, previewRole, previewPaint, previewTargetIds, renderScale,
            ),
            placeholder = "Empty code block",
            editing = editing,
            callbacks = callbacks,
            renderScale = renderScale,
        )
    }
}

@Composable
private fun SceneSpecialSource(
    title: String,
    blockId: String,
    objectId: String,
    source: String,
    editing: Boolean,
    callbacks: SceneContentCallbacks,
    renderScale: DocumentRenderScale,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(renderScale.pointToDp(6f)))
            .border(renderScale.pointToDp(1f), MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(renderScale.pointToDp(6f)))
            .padding(renderScale.pointToDp(8f)),
    ) {
        Text(title, style = renderScale.pointTextStyle(MaterialTheme.typography.labelSmall), color = MaterialTheme.colorScheme.onSurfaceVariant)
        SceneEditableLine(
            objectId, blockId, null, listOf(InlineText(source)),
            renderScale.pointTextStyle(
                if (title == "Equation") MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.Serif)
                else MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            ),
            if (title == "Equation") "Empty equation" else "Empty diagram",
            editing, callbacks, renderScale,
        )
    }
}

@Composable
private fun SceneCompactPreview(
    title: String,
    detail: String,
    renderScale: DocumentRenderScale,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(renderScale.pointToDp(6f)))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .55f))
            .padding(renderScale.pointToDp(8f)),
    ) {
        Text(title, style = renderScale.pointTextStyle(MaterialTheme.typography.titleSmall), maxLines = 1)
        Text(detail, style = renderScale.pointTextStyle(MaterialTheme.typography.bodySmall), maxLines = 2, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun sceneFileSize(bytes: Long): String = when {
    bytes <= 0L -> "Unknown size"
    bytes < 1_024L -> "$bytes B"
    bytes < 1_048_576L -> "${bytes / 1_024} KB"
    bytes < 1_073_741_824L -> "${bytes / 1_048_576} MB"
    else -> "${bytes / 1_073_741_824} GB"
}

/** A table placed on a page: readable at its real content. Cell editing belongs to the Phase-4 table work. */
@Composable
private fun SceneTable(
    block: TableBlock,
    editing: Boolean,
    callbacks: SceneContentCallbacks,
    renderScale: DocumentRenderScale,
    modifier: Modifier = Modifier,
    activeCell: Pair<Int, Int>? = null,
    cellDraft: String? = null,
) {
    Column(
        modifier
            .clip(RoundedCornerShape(renderScale.pointToDp(6f)))
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        // Read mode exposes no way into an editing host, and the flag governs this route exactly as it
        // governs the sidebar entry — otherwise a disabled host would still be one tap away.
        if (editing && editorFlags.isEnabled(EditorFeatureFlag.SPREADSHEET_HOST)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(
                    onClick = { callbacks.onOpenTableInSpreadsheet(block.id) },
                    modifier = Modifier.heightIn(min = renderScale.pointToDp(48f)),
                ) { Text("Open in Spreadsheet") }
            }
        }
        Row(Modifier.fillMaxWidth()) {
            block.headers.forEachIndexed { column, cell ->
                SceneTableCell(
                    text = cell.content.plainText(),
                    header = true,
                    editing = editing,
                    active = activeCell == (-1 to column),
                    editorText = cellDraft.orEmpty(),
                    onActivate = { callbacks.onTableCellActivate(-1, column) },
                    onDraft = { callbacks.onTableCellDraft(-1, column, it) },
                    onCommit = { callbacks.onTableCellCommit(-1, column, it) },
                    renderScale = renderScale,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        block.rows.forEachIndexed { rowIndex, row ->
            HorizontalDivider(thickness = renderScale.pointToDp(1f))
            Row(Modifier.fillMaxWidth()) {
                row.forEachIndexed { column, cell ->
                    SceneTableCell(
                        text = cell.content.plainText(),
                        header = false,
                        editing = editing,
                        active = activeCell == (rowIndex to column),
                        editorText = cellDraft.orEmpty(),
                        onActivate = { callbacks.onTableCellActivate(rowIndex, column) },
                        onDraft = { callbacks.onTableCellDraft(rowIndex, column, it) },
                        onCommit = { callbacks.onTableCellCommit(rowIndex, column, it) },
                        renderScale = renderScale,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun SceneTableCell(
    text: String,
    header: Boolean,
    editing: Boolean,
    active: Boolean,
    editorText: String,
    onActivate: () -> Unit,
    onDraft: (String) -> Unit,
    onCommit: (String) -> Unit,
    renderScale: DocumentRenderScale = DocumentRenderScale.At100Percent,
    modifier: Modifier = Modifier,
) {
    val style = renderScale.pointTextStyle(if (header) MaterialTheme.typography.labelMedium else MaterialTheme.typography.bodySmall)
    val rowHeight = renderScale.pointToDp(24f)
    val minimumHeight = if (editing) maxOf(48.dp, rowHeight) else rowHeight
    // Focus loss commits, matching every grid the user has met. The draft itself lives in the runtime.
    var hadFocus by remember { mutableStateOf(false) }
    Box(
        modifier
            // Read/Adaptive is a scaled physical page, not a grid of touch controls. A fixed 48dp
            // minimum there clips rows as the page shrinks. Edit mode retains the full touch target;
            // read mode retains the authored 24pt row footprint.
            .heightIn(min = minimumHeight)
            .clickable(enabled = editing && !active, onClick = onActivate)
            .semantics {
                if (editing) role = Role.Button
                contentDescription = "${if (header) "Header" else "Table"} cell, ${text.ifBlank { "blank" }}${if (active) ", selected and editable" else ""}"
            }
            .padding(horizontal = renderScale.pointToDp(6f), vertical = renderScale.pointToDp(4f)),
        contentAlignment = Alignment.CenterStart,
    ) {
        if (editing && active) {
            BasicTextField(
                value = editorText,
                onValueChange = onDraft,
                textStyle = style.copy(color = LocalContentColor.current),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onCommit(editorText) }),
                modifier = Modifier
                    .fillMaxWidth()
                    .onFocusChanged { focus ->
                        if (hadFocus && !focus.isFocused) onCommit(editorText)
                        hadFocus = focus.isFocused
                    },
            )
        } else {
            Text(text.ifBlank { " " }, style = style)
        }
    }
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
    renderScale: DocumentRenderScale,
) {
    val metrics = resolvedShapeMetrics(block, stylesheet)
    val shape = RoundedCornerShape(renderScale.pointToDp(metrics.cornerRadiusDp))
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
            .then(if (metrics.shadowDp > 0f) Modifier.shadow(renderScale.pointToDp(metrics.shadowDp), shape) else Modifier)
            .clip(shape)
            .backgroundPaint(fill)
            .border(renderScale.pointToDp(metrics.strokeWidthDp), Color(stroke.representative().toArgb()), shape),
        contentAlignment = Alignment.Center,
    ) {
        if (block.text.isNotBlank()) {
            Text(
                block.text,
                style = resolvedSceneTextStyle(
                    block, stylesheet, MaterialTheme.typography.bodyMedium,
                    emptyList(), previewRole, previewPaint, previewTargetIds, renderScale,
                ),
                modifier = Modifier.padding(renderScale.pointToDp(metrics.paddingDp)),
            )
        }
    }
}

/**
 * A built shape, drawn from its own document.
 *
 * The live colour preview is applied to the **top** layer only. A shape built from six layers has no single
 * fill, so recolouring every layer at once would misrepresent what committing the swatch actually does —
 * the picker writes one paint, and the preview has to show that one paint landing where it will land.
 */
@Composable
private fun SceneShapeDocument(
    document: ShapeDocument,
    previewRole: ColorRole?,
    previewPaint: ColorPaint?,
    previewApplies: Boolean,
    modifier: Modifier = Modifier,
) {
    val previewed = remember(document, previewRole, previewPaint, previewApplies) {
        val target = document.topLayer()?.id
        when {
            !previewApplies || previewPaint == null || target == null -> document
            previewRole == ColorRole.FILL -> ShapeLayerEditor.setFill(document, target, previewPaint)
            previewRole == ColorRole.STROKE -> ShapeLayerEditor.setStrokePaint(document, target, previewPaint)
            else -> document
        }
    }
    ShapeDocumentPreview(previewed, modifier)
}

@Composable
private fun SceneListRow(
    marker: String,
    renderScale: DocumentRenderScale,
    content: @Composable () -> Unit,
) {
    Row(verticalAlignment = Alignment.Top) {
        Text(
            marker,
            style = renderScale.pointTextStyle(MaterialTheme.typography.bodyMedium),
            modifier = Modifier.width(renderScale.pointToDp(20f)),
        )
        Box(Modifier.weight(1f)) { content() }
    }
}

@Composable
private fun ScenePlaceholder(
    title: String,
    detail: String,
    renderScale: DocumentRenderScale,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(renderScale.pointToDp(6f)))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(renderScale.pointToDp(8f)),
    ) {
        Text(title, style = renderScale.pointTextStyle(MaterialTheme.typography.labelMedium))
        Text(
            detail,
            style = renderScale.pointTextStyle(MaterialTheme.typography.labelSmall),
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
    renderScale: DocumentRenderScale,
): AnnotatedString {
    val colors = MaterialTheme.colorScheme
    return remember(nodes, colors, renderScale) {
        val text = nodes.plainText()
        val spans = FlowInlineText.runsOf(nodes).mapNotNull { run ->
            if (run.style.isPlain) return@mapNotNull null
            val start = run.start.coerceIn(0, text.length)
            val end = run.end.coerceIn(start, text.length)
            if (start == end) return@mapNotNull null
            AnnotatedString.Range(run.style.toSceneSpanStyle(colors, renderScale), start, end)
        }
        AnnotatedString(text, spans)
    }
}

private fun FlowInlineStyle.toSceneSpanStyle(
    colors: ColorScheme,
    renderScale: DocumentRenderScale,
): SpanStyle = SpanStyle(
    fontWeight = if (bold) FontWeight.Bold else null,
    fontStyle = if (italic) FontStyle.Italic else null,
    fontSize = fontSizeSp?.coerceIn(6f, 144f)?.let { renderScale.pointTextSize(it) } ?: TextUnit.Unspecified,
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
    renderScale: DocumentRenderScale,
): TextStyle = renderScale.pointTextStyle(resolvedTextStyle(
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
