package com.norfold.app.ui.editor.flow

import android.annotation.SuppressLint
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Campaign
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.geometry.Offset
import androidx.compose.foundation.Canvas
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.norfold.app.domain.BulletListBlock
import com.norfold.app.domain.CalloutBlock
import com.norfold.app.domain.ChartBlock
import com.norfold.app.domain.ImageBlock
import com.norfold.app.domain.ShapeBlock
import com.norfold.app.editor.shape.LoadedShapeDocument
import com.norfold.app.editor.shape.ShapeBlockAdapter
import com.norfold.app.ui.editor.shape.ShapeDocumentPreview
import com.norfold.app.domain.CodeBlock
import com.norfold.app.domain.ContainerAxis
import com.norfold.app.domain.ContainerBlock
import com.norfold.app.domain.DividerBlock
import com.norfold.app.domain.DocumentBlock
import com.norfold.app.domain.EmbedBlock
import com.norfold.app.domain.FileBlock
import com.norfold.app.domain.FlowSizePolicy
import com.norfold.app.domain.HeadingBlock
import com.norfold.app.domain.InlineNode
import com.norfold.app.domain.InlineText
import com.norfold.app.domain.MathBlock
import com.norfold.app.domain.MermaidBlock
import com.norfold.app.domain.NumberedListBlock
import com.norfold.app.domain.ParagraphBlock
import com.norfold.app.domain.QuoteBlock
import com.norfold.app.domain.effectiveStyleAssignment
import com.norfold.app.domain.TableAlignment
import com.norfold.app.domain.TableBlock
import com.norfold.app.domain.TodoListBlock
import com.norfold.app.domain.UnknownBlock
import com.norfold.app.domain.plainText
import com.norfold.app.editor.flow.FlowCaretRequest
import com.norfold.app.editor.flow.FlowInlineStyle
import com.norfold.app.editor.flow.FlowInlineText
import com.norfold.app.editor.flow.FlowTableCursor
import com.norfold.app.editor.flow.FlowTableLayout
import com.norfold.app.editor.flags.EditorFeatureFlag
import com.norfold.app.editor.style.CalloutTone
import com.norfold.app.ui.editor.editorFlags
import com.norfold.app.ui.fonts.NorfoldFontFamilies
import com.norfold.app.ui.screens.highlightColor
import com.norfold.app.domain.style.Stylesheet
import com.norfold.app.domain.style.ColorPaint
import com.norfold.app.domain.style.ColorRole
import com.norfold.app.domain.style.ColorValue
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
import com.norfold.app.ui.components.DiagramKind
import com.norfold.app.ui.components.DiagramNodeInput
import com.norfold.app.ui.components.DiagramEdgeInput
import com.norfold.app.ui.components.FlowDirection
import com.norfold.app.ui.components.MermaidDiagramCodec
import com.norfold.app.ui.components.NorfoldColorSwatch
import com.norfold.app.domain.style.resolvedShapeFill
import com.norfold.app.domain.style.resolvedShapeStroke
import com.norfold.app.ui.editor.style.backgroundPaint
import com.norfold.app.ui.editor.style.resolvedShapeMetrics
import com.norfold.app.editor.diagram.DiagramEditDocumentJson
import com.norfold.app.editor.diagram.DiagramNodePosition
import java.io.ByteArrayInputStream
import kotlin.math.roundToInt
import com.norfold.app.ui.asColor
import com.norfold.app.ui.rememberChromeFocus

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
    val onTextSelection: (blockId: String, itemId: String?, start: Int, end: Int) -> Unit = { _, _, _, _ -> },
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
    /** Step a callout to the next tone. The icon is the control, so this is how a tone gets chosen at all. */
    val onCycleCalloutTone: (blockId: String) -> Unit = {},
    val onOpenChart: (blockId: String) -> Unit = {},
    val onOpenImage: (blockId: String) -> Unit = {},
    val onOpenShape: (blockId: String) -> Unit = {},
    val onUpdateMermaid: (before: MermaidBlock, after: MermaidBlock) -> Unit = { _, _ -> },
    /** Reports real field focus so the host can keep editing chrome contextual. */
    val onEditorFocusChanged: (blockId: String, focused: Boolean) -> Unit = { _, _ -> },
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
    /** Phone layouts stack authored columns; expanded layouts preserve their authored axis. */
    val compact: Boolean = false,
    /** False renders a quiet reading surface. Tapping the owning row activates the editor. */
    val editing: Boolean = true,
    /**
     * True for an object block in an editable document, so media can expose its own way in.
     *
     * This is what [objectSelected] used to be, under a name that claimed more than it delivered: it was
     * `editing && (block is ImageBlock || block is ChartBlock)` when it was written and
     * `editing && flowObject.selectable` after the object model landed — a *kind* test either way, never once
     * fed the selection. It gates the image's Edit affordance and the chart's tap-into-Studio, both of which
     * should indeed be offered on every editable object rather than only on a selected one, so the behaviour
     * was right and only the name was wrong.
     */
    val objectActive: Boolean = false,
    /**
     * True when this object *is* the structural selection — the frame's accent edge, and nothing else.
     *
     * Structural selection, deliberately, and not mere membership of `selectedIds`: that set also holds the
     * ordinary caret owner, so a caret dropped into a code block or a table cell would put a 3 dp accent frame
     * around it on top of the caret and the keyboard. `FlowHostScreen` already settled this question for the
     * row background — structural selection is the only state that earns a selection treatment — and a frame
     * is the same kind of statement.
     */
    val objectSelected: Boolean = false,
    /** Nested container shells stay visually flat; only the authored outer group owns the card. */
    val insideContainer: Boolean = false,
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
            style = resolvedFlowTextStyle(block, context, FlowContent.type.bodyLarge),
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

        is BulletListBlock -> Column(modifier, verticalArrangement = Arrangement.spacedBy(7.dp)) {
            block.items.forEach { item ->
                FlowListRow(marker = "•") {
                    FlowEditableLine(
                        blockId = block.id,
                        itemId = item.id,
                        nodes = item.content,
                        style = resolvedFlowTextStyle(block, context, FlowContent.type.bodyLarge),
                        placeholder = "List item",
                        callbacks = callbacks,
                        context = context,
                    )
                }
            }
        }

        is NumberedListBlock -> Column(modifier, verticalArrangement = Arrangement.spacedBy(7.dp)) {
            block.items.forEachIndexed { index, item ->
                FlowListRow(marker = "${block.start + index}.") {
                    FlowEditableLine(
                        blockId = block.id,
                        itemId = item.id,
                        nodes = item.content,
                        style = resolvedFlowTextStyle(block, context, FlowContent.type.bodyLarge),
                        placeholder = "List item",
                        callbacks = callbacks,
                        context = context,
                    )
                }
            }
        }

        is TodoListBlock -> Column(modifier, verticalArrangement = Arrangement.spacedBy(7.dp)) {
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
                        style = resolvedFlowTextStyle(block, context, FlowContent.type.bodyLarge),
                        placeholder = "To-do",
                        callbacks = callbacks,
                        context = context,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }

        // A quote is ink on the page, not a panel on it. The accent tint and accent rule it used to wear made
        // every quotation carry the user's theme colour, and on a white canvas a 7%-alpha fill reads as a
        // rendering artefact rather than as a choice. What is left is the mark that has meant "quotation" in
        // print for four centuries: a rule in the page's own ink, and the indent it creates.
        is QuoteBlock -> Row(
            modifier
                .height(IntrinsicSize.Min)
                .padding(vertical = 2.dp),
        ) {
            val childContext = context.copy(
                wrapperStyles = listOf(WrapperStyleSource(block.id, block.effectiveStyleAssignment())) + context.wrapperStyles,
            )
            Box(
                Modifier
                    .width(3.dp)
                    .fillMaxHeight()
                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.24f), RoundedCornerShape(2.dp)),
            )
            Column(Modifier.padding(start = 15.dp)) {
                FlowStyledChildren(block.children, callbacks, childContext)
            }
        }

        is CalloutBlock -> {
            val tone = CalloutTone.of(block.tone)
            val palette = flowTonePalette(tone)
            Column(
                modifier
                    .clip(RoundedCornerShape(16.dp))
                    .background(palette.tint)
                    .border(1.dp, palette.edge, RoundedCornerShape(16.dp))
                    .padding(horizontal = 14.dp, vertical = 12.dp),
            ) {
                val childContext = context.copy(
                    wrapperStyles = listOf(WrapperStyleSource(block.id, block.effectiveStyleAssignment())) + context.wrapperStyles,
                )
                // A collapsed callout with no control is content the user cannot reach at all, and structural
                // commands would still operate on children nobody can see (correction item 16).
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Disabled in structural selection so a tap meant to select the callout block cannot instead
                    // expand/collapse it (correction item 6) — the same reason the Todo checkbox above is disabled.
                    if (context.editing) {
                        IconButton(
                            onClick = { callbacks.onToggleCollapsed(block.id) },
                            enabled = !context.selectionMode,
                        ) {
                            Icon(
                                imageVector = if (block.collapsed) Icons.Filled.ChevronRight else Icons.Filled.ExpandMore,
                                contentDescription = if (block.collapsed) "Expand callout" else "Collapse callout",
                            )
                        }
                    }
                    // The tone icon *is* the tone control while editing. A tone nothing can change would be a
                    // property only an imported Markdown alert could set — the palette would then exist for
                    // documents that came from somewhere else and for no document written here.
                    if (context.editing) {
                        IconButton(
                            onClick = { callbacks.onCycleCalloutTone(block.id) },
                            enabled = !context.selectionMode,
                        ) {
                            Icon(
                                imageVector = calloutToneIcon(tone),
                                contentDescription = "Callout tone ${tone.defaultTitle}, change to ${tone.next().defaultTitle}",
                                tint = palette.ink,
                            )
                        }
                    } else {
                        Icon(
                            imageVector = calloutToneIcon(tone),
                            contentDescription = tone.defaultTitle,
                            tint = palette.ink,
                            modifier = Modifier.padding(end = 10.dp),
                        )
                    }
                    FlowEditableLine(
                        blockId = block.id,
                        itemId = null,
                        nodes = listOf(InlineText(block.title)),
                        style = resolvedFlowTextStyle(
                            block,
                            context,
                            FlowContent.type.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                        ),
                        placeholder = "Callout title",
                        callbacks = callbacks,
                        context = context,
                        modifier = Modifier.weight(1f),
                    )
                }
                if (!block.collapsed) {
                    FlowStyledChildren(block.children, callbacks, childContext)
                }
            }
        }

        // A code block holds a plain string, not inline nodes. Wrapping it in a single [InlineText] is the
        // honest description of that: one run, no styling, same editing path as every other line.
        is CodeBlock -> FlowCodeBlock(block, callbacks, context, modifier)

        is DividerBlock -> HorizontalDivider(modifier.padding(vertical = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)

        is ContainerBlock -> if (context.insideContainer) {
            FlowStyledChildren(block.children, callbacks, context)
        } else if (block.axis == ContainerAxis.Row && !context.compact) {
            Row(
                modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainer)
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp))
                    .padding(14.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                block.children.forEach { child ->
                    Box(Modifier.weight(1f)) {
                        FlowBlockContent(child, callbacks, context = context.copy(insideContainer = true))
                    }
                }
            }
        } else {
            Column(
                modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainer)
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp))
                    .padding(horizontal = 16.dp, vertical = 14.dp),
            ) {
                block.children.forEachIndexed { index, child ->
                    if (index > 0 && block.axis == ContainerAxis.Row) {
                        HorizontalDivider(
                            Modifier.padding(vertical = 13.dp),
                            color = MaterialTheme.colorScheme.outlineVariant,
                        )
                    }
                    FlowBlockContent(child, callbacks, context = context.copy(insideContainer = true))
                }
            }
        }

        is TableBlock -> FlowTablePreview(block, callbacks, context, modifier)

        is ImageBlock -> if (editorFlags.isEnabled(EditorFeatureFlag.IMAGE_FOCUS)) {
            val loaded = remember(block.imageEditDocument) { ImageBlockAdapter.load(block) }
            Column(modifier, verticalArrangement = Arrangement.spacedBy(7.dp)) {
                // Media carries no resting edge: the photograph is its own boundary, and a hairline around a
                // full-bleed image reads as a frame the user did not ask for.
                FlowObjectFrame(block, selected = context.objectSelected, resting = false) {
                    ImageFocusRenderer(
                        block = block,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(FlowSizePolicy.MEDIA.clamp(block.displayHeightDp).dp)
                            .semantics {
                                contentDescription = "Image${block.caption.takeIf(String::isNotBlank)?.let { ": $it" }.orEmpty()}"
                            },
                    )
                    if (context.objectActive && loaded.isEditable) {
                        val editImageFocus = rememberChromeFocus()
                        Surface(
                            onClick = { callbacks.onOpenImage(block.id) },
                            modifier = Modifier.align(Alignment.TopEnd).padding(10.dp)
                                // Floating over the image, but the ring goes inward like the rest: measured on
                                // the button's own `surface`, since that is what it is drawn on, not the photo.
                                .then(editImageFocus.ringOnShape(12f, MaterialTheme.colorScheme.surface)),
                            color = MaterialTheme.colorScheme.surface,
                            contentColor = MaterialTheme.colorScheme.onSurface,
                            shape = RoundedCornerShape(12.dp),
                            shadowElevation = 5.dp,
                            interactionSource = editImageFocus.source,
                        ) {
                            Icon(Icons.Filled.Image, "Edit image", Modifier.padding(12.dp).size(20.dp))
                        }
                    }
                    // The badge belongs to selection, where the border is. It used to ride on the same flag as
                    // the Edit button above, which meant every image in an editable document announced itself
                    // as "Image selected" to a screen reader whether or not anything was selected.
                    if (context.objectSelected) {
                        Surface(
                            modifier = Modifier.align(Alignment.TopStart).padding(10.dp),
                            color = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary,
                            shape = CircleShape,
                        ) { Icon(Icons.Filled.Check, null, Modifier.padding(6.dp).size(16.dp)) }
                    }
                }
                if (block.caption.isNotBlank() && context.editing) {
                    Text(
                        block.caption,
                        style = FlowContent.type.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else {
            FlowPlaceholder("Image", block.caption.ifBlank { block.source }.take(120).ifBlank { "empty" }, modifier)
        }

        is ChartBlock -> if (editorFlags.isEnabled(EditorFeatureFlag.CHART_STUDIO)) {
            val loaded = remember(block.chartDocument, block.vegaLiteSpec) { ChartBlockAdapter.load(block) }
            FlowObjectFrame(block, selected = context.objectSelected, modifier = modifier) {
                Column(
                    Modifier
                        .background(MaterialTheme.colorScheme.surface)
                        .padding(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    ChartRenderer(
                        document = loaded.document,
                        resolution = remember(loaded.document, context.chartDataRevision) {
                            ChartDataResolver.resolve(loaded.document, context.chartCalculationProvider)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(if (context.objectActive && loaded.isEditable) Modifier.clickable(
                                role = Role.Button,
                                onClickLabel = "Open Chart Studio",
                            ) { callbacks.onOpenChart(block.id) } else Modifier),
                        heightDp = FlowSizePolicy.MEDIA.clamp(block.editorHeightDp).toInt(),
                    )
                }
            }
        } else {
            FlowPlaceholder("Chart", block.plainText().take(120).ifBlank { "empty" }, modifier)
        }

        is MathBlock -> FlowMathBlock(block, callbacks, context, modifier)
        is MermaidBlock -> FlowMermaidBlock(block, callbacks, context, modifier)

        // Flow has no shape insert surface, but a shape reaches a Flow document through interchange. Only a
        // shape that has actually been built draws its own geometry; anything else keeps the styled rendering
        // Flow already gives a shape, so turning the flag on cannot alter a single existing Flow document. The
        // Edit action is offered either way — it is what promotes a legacy shape into a document in the first
        // place.
        is ShapeBlock -> if (editorFlags.isEnabled(EditorFeatureFlag.SHAPE_BUILDER)) {
            val loaded = remember(block) { ShapeBlockAdapter.load(block) }
            Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                // No resting edge and no clip: the shape's authored stroke *is* its edge, and clipping a
                // square shape to this frame's radius would round corners the document says are sharp.
                FlowObjectFrame(
                    block = block,
                    selected = context.objectSelected,
                    resting = false,
                    clipContent = false,
                ) {
                    if (loaded.origin == LoadedShapeDocument.Origin.CANONICAL) {
                        ShapeDocumentPreview(
                            document = loaded.document,
                            // The shape policy, not the media policy this used to borrow: a shape that had been
                            // built clamped to a 120dp floor while the same shape unbuilt clamped to 72dp, so
                            // promoting a 90dp shape into a document silently made it taller.
                            modifier = Modifier.fillMaxWidth().height(FlowSizePolicy.SHAPE.clamp(block.displayHeightDp).dp),
                        )
                    } else {
                        FlowShapeBlock(block, context, Modifier.fillMaxWidth())
                    }
                }
                if (!context.selectionMode && loaded.isEditable) {
                    TextButton(onClick = { callbacks.onOpenShape(block.id) }) { Text("Edit shape") }
                }
            }
        } else {
            FlowObjectFrame(
                block = block,
                selected = context.objectSelected,
                modifier = modifier,
                resting = false,
                clipContent = false,
            ) {
                FlowShapeBlock(block, context, Modifier.fillMaxWidth())
            }
        }

        is EmbedBlock -> FlowEmbedPreview(block, context, modifier)
        is FileBlock -> FlowFilePreview(block, context, modifier)

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
private fun FlowShapeBlock(block: ShapeBlock, context: FlowBlockContext, modifier: Modifier = Modifier) {
    val metrics = remember(block, context.stylesheet) { resolvedShapeMetrics(block, context.stylesheet) }
    val shape = RoundedCornerShape(metrics.cornerRadiusDp.dp)
    val previewApplies = block.id in context.previewTargetIds
    val fill = context.previewPaint.takeIf { previewApplies && context.previewRole == ColorRole.FILL }
        ?: resolvedShapeFill(block, context.stylesheet)
    val stroke = context.previewPaint.takeIf { previewApplies && context.previewRole == ColorRole.STROKE }
        ?: resolvedShapeStroke(block, context.stylesheet)
    Box(
        modifier.fillMaxWidth().height(FlowSizePolicy.SHAPE.clamp(block.displayHeightDp).dp)
            .clip(shape).backgroundPaint(fill)
            .border(metrics.strokeWidthDp.dp, Color(stroke.representative().toArgb()), shape)
            .semantics { contentDescription = "${block.kind.name.replace('_', ' ')} shape${block.text.takeIf(String::isNotBlank)?.let { ": $it" }.orEmpty()}" },
        contentAlignment = Alignment.Center,
    ) {
        if (block.text.isNotBlank()) Text(block.text, style = FlowContent.type.bodyLarge)
    }
}

@Composable
private fun FlowEmbedPreview(block: EmbedBlock, context: FlowBlockContext, modifier: Modifier = Modifier) {
    FlowObjectFrame(block, selected = context.objectSelected, modifier = modifier) {
        Column(
            Modifier
                .padding(12.dp)
                .semantics { contentDescription = "Embedded link ${block.metadata.title.ifBlank { block.url }}" },
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Text(block.metadata.title.ifBlank { "Embedded link" }, style = MaterialTheme.typography.titleSmall)
            if (block.metadata.description.isNotBlank()) {
                Text(
                    block.metadata.description,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            // The URL is the card's caption, not its call to action — the whole card is the target. Accent here
            // put the theme colour on every embed in the document and competed with the title above it for the
            // eye, which is backwards: the title is what the reader is looking for.
            Text(
                block.url,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun FlowFilePreview(block: FileBlock, context: FlowBlockContext, modifier: Modifier = Modifier) {
    // A file card had no edge of any kind — only a 55%-alpha fill — so on the light canvas it was a faint
    // rectangle with no boundary, and being `FlowFrame.Framed` it could be selected with nothing to show.
    FlowObjectFrame(block, selected = context.objectSelected, modifier = modifier) {
        Row(
            Modifier
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .55f))
                .padding(12.dp)
                .semantics { contentDescription = "File ${block.name}, ${humanFileSize(block.sizeBytes)}" },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(42.dp).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.secondaryContainer),
                contentAlignment = Alignment.Center,
            ) { Text(block.name.substringAfterLast('.', "FILE").take(4).uppercase(), style = MaterialTheme.typography.labelSmall) }
            Column(Modifier.weight(1f).padding(start = 10.dp)) {
                Text(
                    block.name.ifBlank { "Attachment" },
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(listOf(block.mimeType, humanFileSize(block.sizeBytes)).filter(String::isNotBlank).joinToString(" · "), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

private fun humanFileSize(bytes: Long): String = when {
    bytes <= 0L -> "Unknown size"
    bytes < 1_024L -> "$bytes B"
    bytes < 1_048_576L -> "${bytes / 1_024} KB"
    bytes < 1_073_741_824L -> "${bytes / 1_048_576} MB"
    else -> "${bytes / 1_073_741_824} GB"
}

@Composable
private fun FlowMathBlock(
    block: MathBlock,
    callbacks: FlowBlockCallbacks,
    context: FlowBlockContext,
    modifier: Modifier = Modifier,
) {
    // Source is the writing surface.  A formula no longer looks editable while quietly forcing the user to
    // discover a hidden mode switch; Preview remains one tap away once the expression is written.
    var mode by rememberSaveable(block.id, context.editing) { mutableStateOf(if (context.editing) FlowRenderableMode.Source else FlowRenderableMode.Preview) }
    FlowObjectFrame(block, selected = context.objectSelected, modifier = modifier) {
        Column(
            Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (context.editing && !context.selectionMode) {
                FlowRenderableTabs(mode = mode, onSelect = { mode = it })
            }
            if (mode == FlowRenderableMode.Source && context.editing) {
                FlowEditableLine(
                    block.id, null, listOf(InlineText(block.tex)),
                    FlowContentMono,
                    "TeX source", callbacks, context, structuralEnter = false,
                )
            } else {
                Text(
                    block.tex.ifBlank { "Empty equation" },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 18.dp)
                        .semantics { contentDescription = "Equation ${block.tex}" },
                    style = FlowContent.type.bodyLarge.copy(fontFamily = FontFamily.Serif),
                )
            }
        }
    }
}

@Composable
private fun FlowCodeBlock(
    block: CodeBlock,
    callbacks: FlowBlockCallbacks,
    context: FlowBlockContext,
    modifier: Modifier = Modifier,
) {
    if (block.language.equals("html", ignoreCase = true) || block.language.equals("htm", ignoreCase = true)) {
        FlowHtmlBlock(block, callbacks, context, modifier)
        return
    }
    val scroll = rememberScrollState()
    // Opaque fill plus a hairline, replacing a 58%-alpha tint. The alpha was tuned against the old ivory
    // canvas; blended over a white page it lands two shades off white and the block loses its edge entirely,
    // which for code is not cosmetic — the boundary is what says where the literal text starts and stops.
    //
    // The scroll stays *inside* the frame, for the reason the table states below: scrolling the card itself
    // would drag its own border off screen and leave the block looking clipped.
    FlowObjectFrame(block, selected = context.objectSelected, modifier = modifier) {
        Column(
            Modifier
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .horizontalScroll(scroll)
                .padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            FlowEditableLine(
                blockId = block.id,
                itemId = null,
                nodes = listOf(InlineText(block.code)),
                style = resolvedFlowTextStyle(block, context, FlowContentMono),
                placeholder = "Code",
                callbacks = callbacks,
                context = context,
                structuralEnter = false,
                modifier = Modifier.widthIn(min = 360.dp),
            )
        }
    }
}

/** Exact HTML source with an offline, script-free rendering projection. */
@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun FlowHtmlBlock(
    block: CodeBlock,
    callbacks: FlowBlockCallbacks,
    context: FlowBlockContext,
    modifier: Modifier = Modifier,
) {
    var mode by rememberSaveable(block.id, context.editing) { mutableStateOf(if (context.editing) FlowRenderableMode.Source else FlowRenderableMode.Preview) }
    FlowObjectFrame(block, selected = context.objectSelected, modifier = modifier) {
        Column(
            Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            FlowRenderableTabs(mode = mode, onSelect = { mode = it })
            Box(
                Modifier.fillMaxWidth()
                    .heightIn(min = 180.dp, max = FlowSizePolicy.SOURCE_PANE.clamp(block.editorHeightDp).dp)
                    .clip(RoundedCornerShape(8.dp)),
            ) {
                when (mode) {
                    FlowRenderableMode.Source -> FlowEditableLine(
                        blockId = block.id,
                        itemId = null,
                        nodes = listOf(InlineText(block.code)),
                        style = FlowContentMono,
                        placeholder = "HTML source",
                        callbacks = callbacks,
                        context = context,
                        structuralEnter = false,
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(12.dp),
                    )
                    FlowRenderableMode.Preview -> AndroidView(
                        modifier = Modifier.fillMaxWidth().height(FlowSizePolicy.SOURCE_PANE.clamp(block.editorHeightDp).dp)
                            .semantics { contentDescription = "Sandboxed HTML preview" },
                        factory = { androidContext ->
                            // Three of these setters are deprecated as of API 36 and are kept
                            // deliberately. Each one closes a hole rather than opening a feature, and
                            // `minSdk` is 26 — on the older platforms in that range the framework
                            // default is not guaranteed to be the safe value, so dropping the call to
                            // silence a warning would widen the sandbox on exactly the devices least
                            // able to defend themselves. A deprecated *tightening* call is still a
                            // tightening call; it is removed when `minSdk` rises past the versions
                            // that need it, not when the warning appears.
                            @Suppress("DEPRECATION")
                            WebView(androidContext).apply {
                                settings.javaScriptEnabled = false
                                settings.javaScriptCanOpenWindowsAutomatically = false
                                settings.domStorageEnabled = false
                                settings.databaseEnabled = false
                                settings.allowFileAccess = false
                                settings.allowContentAccess = false
                                settings.allowFileAccessFromFileURLs = false
                                settings.allowUniversalAccessFromFileURLs = false
                                settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
                                settings.cacheMode = android.webkit.WebSettings.LOAD_NO_CACHE
                                isLongClickable = false
                                setNetworkAvailable(false)
                                webViewClient = object : WebViewClient() {
                                    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean = true
                                    override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                                        val scheme = request?.url?.scheme?.lowercase()
                                        return if (scheme in setOf("http", "https", "file", "content")) {
                                            WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream(ByteArray(0)))
                                        } else null
                                    }
                                }
                            }
                        },
                        update = { webView ->
                            webView.loadDataWithBaseURL(
                                "about:blank",
                                htmlSandboxDocument(block.code),
                                "text/html",
                                "UTF-8",
                                null,
                            )
                        },
                    )
                }
            }
            Text(
                "Offline preview · scripts, navigation, files, and network requests are blocked",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

internal fun htmlSandboxDocument(source: String): String = buildString(source.length + 256) {
    append("<meta http-equiv=\"Content-Security-Policy\" content=\"default-src 'none'; img-src data:; style-src 'unsafe-inline'; font-src data:; media-src data:;\">")
    append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">")
    append(source)
}

private enum class FlowRenderableMode { Source, Preview }

/**
 * One lightweight representation shell for renderable Mermaid source.
 *
 * Source and Preview occupy the same region; switching never duplicates the source below the rendering.
 * The preview is derived from the supported Mermaid codec used by Diagram Builder. Unsupported Mermaid is
 * preserved and stays editable in Source rather than being presented as a renderer success.
 */
@Composable
private fun FlowMermaidBlock(
    block: MermaidBlock,
    callbacks: FlowBlockCallbacks,
    context: FlowBlockContext,
    modifier: Modifier = Modifier,
) {
    var mode by rememberSaveable(block.id, context.editing) { mutableStateOf(if (context.editing) FlowRenderableMode.Source else FlowRenderableMode.Preview) }
    var selectedNodeId by rememberSaveable(block.id) { mutableStateOf<String?>(null) }
    val defaultColor = MaterialTheme.colorScheme.primary.toArgb().let { color -> "#%06X".format(color and 0xFFFFFF) }
    val model = remember(block.code, defaultColor) {
        MermaidDiagramCodec.decodeOrNull(block.code, defaultColor)
    }
    val editDocument = remember(block.diagramEditDocument, model) {
        DiagramEditDocumentJson.decodeOrDefault(block.diagramEditDocument)
            .normalized(model?.nodes?.mapTo(mutableSetOf()) { it.id }.orEmpty())
    }
    val effectiveMode = if (model == null) FlowRenderableMode.Source else mode

    // Composed rather than wrapped in [FlowObjectFrame]: this body is 130 lines of nested diagram editing, and
    // re-indenting all of it to gain one Box would make the change unreviewable. The frame's shape and edge
    // are the parts that must be shared, and those are exactly what [FlowFrameMetrics] exposes.
    val frameShape = FlowFrameMetrics.shapeOf(block)
    Column(
        with(FlowFrameMetrics) {
            modifier
                .clip(frameShape)
                .flowFrameEdge(frameShape, context.objectSelected, resting = true)
                .padding(12.dp)
        },
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        FlowRenderableTabs(
            mode = effectiveMode,
            onSelect = { mode = it },
            // Unsupported Mermaid has no model to render, so Preview would show a blank pane presented as a
            // renderer success. The tab states that it is unavailable rather than the pane implying failure.
            previewEnabled = model != null,
        )
        Box(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 156.dp, max = FlowSizePolicy.DIAGRAM.clamp(block.editorHeightDp).dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.28f))
                .padding(12.dp),
        ) {
            when (effectiveMode) {
                FlowRenderableMode.Source -> FlowEditableLine(
                    blockId = block.id,
                    itemId = null,
                    nodes = listOf(InlineText(block.code)),
                    style = FlowContentMono,
                    placeholder = "Mermaid source",
                    callbacks = callbacks,
                    context = context,
                    structuralEnter = false,
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                )
                FlowRenderableMode.Preview -> FlowMermaidPreview(
                    requireNotNull(model),
                    positions = editDocument.nodePositions,
                    selectedNodeId = selectedNodeId,
                    onSelectNode = if (context.editing && !context.selectionMode) ({ selectedNodeId = it }) else null,
                    onMoveNode = if (context.editing && !context.selectionMode) ({ id, position ->
                        val next = editDocument.copy(nodePositions = editDocument.nodePositions + (id to position))
                        callbacks.onUpdateMermaid(
                            block,
                            block.copy(diagramEditDocument = DiagramEditDocumentJson.encode(next)),
                        )
                    }) else null,
                )
            }
        }
        if (effectiveMode == FlowRenderableMode.Preview && model?.kind == DiagramKind.Flowchart && context.editing && !context.selectionMode) {
            model.nodes.firstOrNull { it.id == selectedNodeId }?.let { selected ->
                OutlinedTextField(
                    value = selected.label,
                    onValueChange = { label ->
                        val next = model.copy(nodes = model.nodes.map { if (it.id == selected.id) it.copy(label = label) else it })
                        val encoded = MermaidDiagramCodec.encode(next, defaultColor)
                        callbacks.onEdit(FlowTextEdit(block.id, null, block.code, encoded, encoded.length))
                    },
                    label = { Text("Node label") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                // Each colour paired with what it *is*, and the name travelling with the colour rather than
                // sitting in a parallel list: `distinctBy` drops entries, so on a theme whose `primary` is
                // already one of the three fixed hues, names indexed alongside would shift and every swatch
                // after the collision would announce the wrong one. Diagram Builder's node card, which offers
                // this same choice from a sheet, records the same reasoning.
                val nodePalette = listOf(
                    defaultColor to "Theme primary",
                    "#00897B" to "Teal",
                    "#1565C0" to "Blue",
                    "#EF6C00" to "Orange",
                ).distinctBy { (hex, _) -> hex.uppercase() }
                Text("Node color", style = MaterialTheme.typography.labelLarge)
                Row(
                    // `selectableGroup`, so a reader hears one exclusive choice rather than four independent
                    // toggles that happen to sit in a row.
                    Modifier.selectableGroup(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    nodePalette.forEach { (hex, name) ->
                        NorfoldColorSwatch(
                            // The domain parse, not `android.graphics.Color.parseColor`: that one throws on a
                            // malformed string, and this one returns null and is locale-independent. Nothing
                            // reaching it here is malformed — all four hexes are formed in this file — so this
                            // is the right parse rather than a crash being fixed.
                            color = ColorValue.parseHex(hex)?.asColor() ?: MaterialTheme.colorScheme.primary,
                            // The three things this row did not do before: state which colour is chosen, say
                            // what any of them are, and draw an edge so a swatch matching the surface behind it
                            // is still a swatch.
                            selected = selected.color.equals(hex, ignoreCase = true),
                            label = name,
                            onClick = {
                                val next = model.copy(nodes = model.nodes.map { if (it.id == selected.id) it.copy(color = hex) else it })
                                val encoded = MermaidDiagramCodec.encode(next, defaultColor)
                                callbacks.onEdit(FlowTextEdit(block.id, null, block.code, encoded, encoded.length))
                            },
                            // Diagram Builder draws 36 dp for this same palette. The row keeps the height it
                            // had either way: the swatch reserves 48 dp of layout whatever it draws inside it.
                            size = 36f,
                        )
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = {
                    val id = MermaidDiagramCodec.nextId(model.nodes.map { it.id }, "Node")
                    val next = model.copy(nodes = model.nodes + com.norfold.app.ui.components.DiagramNodeInput(id, "New node", defaultColor))
                    val encoded = MermaidDiagramCodec.encode(next, defaultColor)
                    callbacks.onEdit(FlowTextEdit(block.id, null, block.code, encoded, encoded.length))
                    selectedNodeId = id
                }) { Text("Add node") }
                val selected = selectedNodeId
                if (selected != null && model.nodes.size > 1) {
                    TextButton(onClick = {
                        val next = model.copy(
                            nodes = model.nodes.filterNot { it.id == selected },
                            edges = model.edges.filterNot { it.from == selected || it.to == selected },
                        )
                        val encoded = MermaidDiagramCodec.encode(next, defaultColor)
                        callbacks.onEdit(FlowTextEdit(block.id, null, block.code, encoded, encoded.length))
                        selectedNodeId = null
                    }) { Text("Remove") }
                }
            }
            val selected = selectedNodeId
            if (selected != null && model.nodes.size > 1) {
                val targets = model.nodes.filterNot { it.id == selected }
                var targetIndex by remember(selected, targets.size) { mutableStateOf(0) }
                val target = targets.getOrNull(targetIndex.coerceIn(0, targets.lastIndex.coerceAtLeast(0)))
                if (target != null) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("Connect to", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
                        TextButton(onClick = { targetIndex = (targetIndex - 1 + targets.size) % targets.size }) { Text("Previous") }
                        Text(target.label, style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = { targetIndex = (targetIndex + 1) % targets.size }) { Text("Next") }
                    }
                    val exists = model.edges.any { it.from == selected && it.to == target.id }
                    TextButton(onClick = {
                        val edges = if (exists) model.edges.filterNot { it.from == selected && it.to == target.id }
                        else model.edges + com.norfold.app.ui.components.DiagramEdgeInput(selected, target.id)
                        val encoded = MermaidDiagramCodec.encode(model.copy(edges = edges), defaultColor)
                        callbacks.onEdit(FlowTextEdit(block.id, null, block.code, encoded, encoded.length))
                    }) { Text(if (exists) "Remove connection" else "Add connection") }
                }
            }
        }
    }
}

/**
 * The Source/Preview switch, as one control rather than as three assemblies of two tabs.
 *
 * Three blocks offer this same choice — an equation, an HTML embed and a diagram — and each built the bar
 * itself. Two of the three clipped an 8 dp track and filled it with `surfaceVariant` at 62%; the equation's
 * bar was a bare `Row` with no track at all, so its unselected tab was a label on the page rather than the
 * unselected half of a switch. That is the difference between "Preview is available" and "Preview is a word
 * printed here", and it was invisible as a diff because the missing background was 40 lines from the other two.
 *
 * ## Focus
 *
 * Hoisting the bar is also what makes the ring measurable. A tab's ring is drawn either on the tab's own
 * `surface` fill when selected or on the track when not, and the track is a *translucent* colour — the one
 * case [com.norfold.app.ui.NorfoldChrome.focusRing] cannot measure directly. The bar owns the track, so it is
 * the only place that can hand the tab the colour it is actually drawn on; a tab reading
 * `MaterialTheme.colorScheme` for itself would have to guess at what its parent painted, and the equation's
 * bar proves the parents did not agree.
 */
@Composable
private fun FlowRenderableTabs(
    mode: FlowRenderableMode,
    onSelect: (FlowRenderableMode) -> Unit,
    modifier: Modifier = Modifier,
    previewEnabled: Boolean = true,
) {
    val track = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.62f)
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(track),
    ) {
        FlowRenderableTab(
            label = "Source",
            selected = mode == FlowRenderableMode.Source,
            track = track,
            onClick = { onSelect(FlowRenderableMode.Source) },
            modifier = Modifier.weight(1f),
        )
        FlowRenderableTab(
            label = "Preview",
            selected = mode == FlowRenderableMode.Preview,
            track = track,
            enabled = previewEnabled,
            onClick = { onSelect(FlowRenderableMode.Preview) },
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun FlowRenderableTab(
    label: String,
    selected: Boolean,
    track: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val focus = rememberChromeFocus()
    Box(
        modifier
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) MaterialTheme.colorScheme.surface else Color.Transparent)
            .clickable(
                interactionSource = focus.source,
                // `LocalIndication.current` rather than `ripple()`, matching every other ringed control in
                // this file: nothing here provides a `LocalIndication`, and the overload without an
                // `interactionSource` resolves indication from that composition local. Naming the ripple
                // would change these six tabs' press feedback as a side effect of adding a focus ring.
                indication = androidx.compose.foundation.LocalIndication.current,
                enabled = enabled,
                onClick = onClick,
            )
            // 8 dp is the tab's own radius, not one of the substrate's three tiers, so the ring is derived
            // from what this control actually draws. The backdrop is the pixel the ring lands on: the tab's
            // opaque `surface` fill when selected, and the bar's translucent track when not — the substrate
            // composites that onto the page before it measures, which is the whole reason the track has to
            // arrive as a parameter rather than be assumed.
            .then(focus.ringOnShape(8f, if (selected) MaterialTheme.colorScheme.surface else track))
            .semantics {
                role = Role.Button
                stateDescription = if (selected) "$label selected" else "$label not selected"
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = when {
                !enabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                selected -> MaterialTheme.colorScheme.primary
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}

@Composable
private fun FlowMermaidPreview(
    model: com.norfold.app.ui.components.DiagramBuilderModel,
    positions: Map<String, DiagramNodePosition> = emptyMap(),
    selectedNodeId: String? = null,
    onSelectNode: ((String) -> Unit)? = null,
    onMoveNode: ((String, DiagramNodePosition) -> Unit)? = null,
) {
    when (model.kind) {
        DiagramKind.Flowchart -> {
            if (positions.isNotEmpty() || onMoveNode != null) {
                FlowPositionedDiagram(model, positions, selectedNodeId, onSelectNode, onMoveNode)
                return
            }
            val nodesById = model.nodes.associateBy { it.id }
            val visibleIds = nodesById.keys.take(8).toSet()
            val visibleEdges = model.edges.filter { it.from in visibleIds && it.to in visibleIds }
            val targets = visibleEdges.mapTo(mutableSetOf()) { it.to }
            val roots = model.nodes.map { it.id }.filter { it in visibleIds && it !in targets }
            val levels = buildList {
                val visited = mutableSetOf<String>()
                var frontier = (roots.ifEmpty { visibleIds.take(1) }).distinct()
                while (frontier.isNotEmpty() && visited.size < visibleIds.size) {
                    val level = frontier.filter { it in visibleIds && visited.add(it) }
                    if (level.isEmpty()) break
                    add(level)
                    frontier = level
                        .flatMap { from -> visibleEdges.filter { it.from == from }.map { it.to } }
                        .filterNot(visited::contains)
                        .distinct()
                }
                val disconnected = model.nodes.map { it.id }.filter { it in visibleIds && it !in visited }
                disconnected.forEach { add(listOf(it)) }
            }
            FlowBranchedDiagram(
                levels = levels,
                nodesById = nodesById,
                edges = visibleEdges,
                selectedNodeId = selectedNodeId,
                onSelectNode = onSelectNode,
            )
        }
        DiagramKind.Sequence -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                model.participants.forEach { participant ->
                    Text(
                        participant.label,
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier
                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                    )
                }
            }
            model.messages.take(6).forEach { message ->
                Text(
                    "${message.from} → ${message.to}: ${message.text}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        else -> Text(
            "Preview is not available for this Mermaid type yet. Source remains preserved.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun FlowBranchedDiagram(
    levels: List<List<String>>,
    nodesById: Map<String, DiagramNodeInput>,
    edges: List<DiagramEdgeInput>,
    selectedNodeId: String?,
    onSelectNode: ((String) -> Unit)?,
) {
    BoxWithConstraints(Modifier.fillMaxWidth().height(184.dp)) {
        val density = androidx.compose.ui.platform.LocalDensity.current
        val widthPx = with(density) { maxWidth.toPx() }
        val heightPx = with(density) { 184.dp.toPx() }
        val nodeWidth = 112.dp
        val nodeHeight = 42.dp
        val nodeWidthPx = with(density) { nodeWidth.toPx() }
        val nodeHeightPx = with(density) { nodeHeight.toPx() }
        val positions = remember(levels, widthPx, heightPx) {
            buildMap<String, Offset> {
                levels.forEachIndexed { levelIndex, level ->
                    val y = if (levels.size == 1) .5f else .10f + (.80f * levelIndex / (levels.size - 1).coerceAtLeast(1))
                    level.take(3).forEachIndexed { itemIndex, id ->
                        val x = (itemIndex + 1f) / (level.take(3).size + 1f)
                        put(id, Offset(widthPx * x, heightPx * y))
                    }
                }
            }
        }
        // Connectors are the diagram's own ink. Drawing them in the accent made every diagram a themed
        // graphic and, worse, made an edge look selected — the accent is what selection uses two lines down.
        // Only the Canvas is affected, so nothing here reaches the stored diagram source.
        val connector = MaterialTheme.colorScheme.onSurfaceVariant
        Canvas(Modifier.fillMaxSize()) {
            edges.forEach { edge ->
                val from = positions[edge.from] ?: return@forEach
                val to = positions[edge.to] ?: return@forEach
                val start = Offset(from.x, from.y + nodeHeightPx / 2f)
                val end = Offset(to.x, to.y - nodeHeightPx / 2f)
                val elbowY = start.y + (end.y - start.y) * .48f
                drawLine(connector, start, Offset(start.x, elbowY), 2.dp.toPx())
                drawLine(connector, Offset(start.x, elbowY), Offset(end.x, elbowY), 2.dp.toPx())
                drawLine(connector, Offset(end.x, elbowY), end, 2.dp.toPx())
                drawLine(connector, end, Offset(end.x - 5.dp.toPx(), end.y - 6.dp.toPx()), 2.dp.toPx())
                drawLine(connector, end, Offset(end.x + 5.dp.toPx(), end.y - 6.dp.toPx()), 2.dp.toPx())
            }
        }
        positions.forEach { (id, center) ->
            val node = nodesById[id] ?: return@forEach
            Box(
                Modifier
                    .offset {
                        IntOffset(
                            (center.x - nodeWidthPx / 2f).roundToInt(),
                            (center.y - nodeHeightPx / 2f).roundToInt(),
                        )
                    }
                    .width(nodeWidth)
                    .height(nodeHeight)
                    .clip(RoundedCornerShape(if (node.shape.name == "Rounded") 10.dp else 6.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .border(
                        if (selectedNodeId == id) 3.dp else 1.dp,
                        if (selectedNodeId == id) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                        RoundedCornerShape(10.dp),
                    )
                    .clickable(enabled = onSelectNode != null) { onSelectNode?.invoke(id) }
                    .semantics {
                        role = Role.Button
                        stateDescription = if (selectedNodeId == id) "Selected node" else "Diagram node"
                    }
                    .padding(horizontal = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(node.label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            }
        }
    }
}

@Composable
private fun FlowPositionedDiagram(
    model: com.norfold.app.ui.components.DiagramBuilderModel,
    positions: Map<String, DiagramNodePosition>,
    selectedNodeId: String?,
    onSelectNode: ((String) -> Unit)?,
    onMoveNode: ((String, DiagramNodePosition) -> Unit)?,
) {
    BoxWithConstraints(Modifier.fillMaxWidth().heightIn(min = 156.dp)) {
        val density = androidx.compose.ui.platform.LocalDensity.current
        val widthPx = with(density) { maxWidth.toPx() }.coerceAtLeast(1f)
        val heightPx = with(density) { 156.dp.toPx() }.coerceAtLeast(1f)
        val halfNodeWidthPx = with(density) { 48.dp.toPx() }
        val halfNodeHeightPx = with(density) { 22.dp.toPx() }
        model.nodes.take(12).forEachIndexed { index, node ->
            val fallback = DiagramNodePosition(
                .14f + (index % 3) * .34f,
                .16f + (index / 3) * .28f,
            ).normalized()
            var preview by remember(node.id, positions[node.id]) { mutableStateOf(positions[node.id] ?: fallback) }
            Box(
                Modifier
                    .offset {
                        IntOffset(
                            (preview.x * widthPx - halfNodeWidthPx).toInt().coerceAtLeast(0),
                            (preview.y * heightPx - halfNodeHeightPx).toInt().coerceAtLeast(0),
                        )
                    }
                    .widthIn(min = 88.dp, max = 130.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .border(
                        if (selectedNodeId == node.id) 3.dp else 1.dp,
                        if (selectedNodeId == node.id) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                        RoundedCornerShape(8.dp),
                    )
                    .pointerInput(node.id, onMoveNode) {
                        detectDragGestures(
                            onDragStart = { onSelectNode?.invoke(node.id) },
                            onDrag = { change, amount ->
                                change.consume()
                                preview = DiagramNodePosition(
                                    preview.x + amount.x / widthPx,
                                    preview.y + amount.y / heightPx,
                                ).normalized()
                            },
                            onDragEnd = { onMoveNode?.invoke(node.id, preview) },
                            onDragCancel = { preview = positions[node.id] ?: fallback },
                        )
                    }
                    .clickable(enabled = onSelectNode != null) { onSelectNode?.invoke(node.id) }
                    .padding(horizontal = 10.dp, vertical = 8.dp)
                    .semantics {
                        role = Role.Button
                        contentDescription = "Diagram node ${node.label}"
                        stateDescription = if (selectedNodeId == node.id) "Selected node" else "Diagram node"
                    },
                contentAlignment = Alignment.Center,
            ) { Text(node.label, style = MaterialTheme.typography.bodySmall) }
        }
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
 *
 * **Columns have widths, and the table scrolls.** Every cell used to be `weight(1f)` inside a
 * `fillMaxWidth()` row, which divides the phone's ~360 dp of measure by the column count: a six-column table
 * gave each column 50 dp — under four characters — and `columnWidthsDp` was never read at all, so a width the
 * spreadsheet bridge had already computed from the workbook was discarded on the way to the screen. Fixed
 * widths plus one shared horizontal scroll is the only arrangement where a wide table stays a table on a
 * phone rather than becoming a column of truncated stubs.
 *
 * Scrolling is the fallback, not the first resort. [FlowTableLayout] scales the authored widths in proportion to
 * fit the card exactly — up for a narrow table, down for a wide one — and the grid only overflows when fitting
 * would push a column under [FlowContent.columnMinimum]. So a three-column table on a phone still renders
 * full-width and non-scrolling, exactly as before this change; a ten-column import scrolls, because the
 * alternative was ten 36 dp stubs. That rule is arithmetic about widths, so it lives in pure Kotlin next to
 * `FlowDragGeometry` and is asserted on the JVM rather than trusted here.
 */
@Composable
private fun FlowTablePreview(
    block: TableBlock,
    callbacks: FlowBlockCallbacks,
    context: FlowBlockContext,
    modifier: Modifier = Modifier,
) {
    val cursor = context.tableCursor?.takeIf { it.blockId == block.id }
    // One scroll state for the header row and every body row. Separate states would let the header drift out
    // of register with the data underneath it, which is worse than no scrolling: a column heading over the
    // wrong column is a table that lies.
    val scroll = rememberScrollState()
    val columns = maxOf(block.headers.size, block.rows.maxOfOrNull { it.size } ?: 0)
    val minimum = FlowContent.columnMinimum
    BoxWithConstraints(modifier.fillMaxWidth()) {
        // `hasBoundedWidth` rather than a test on `maxWidth`: an unbounded constraint arrives here as a *finite*
        // and enormous Dp, so a table nested inside another horizontally scrolling container would otherwise be
        // fitted against half a million dp of imaginary page. Zero is [FlowTableLayout]'s "unknown".
        val available = if (constraints.hasBoundedWidth) maxWidth else 0.dp
        val resolved = remember(block.columnWidthsDp, columns, minimum, available) {
            FlowTableLayout.resolve(
                storedWidthsDp = block.columnWidthsDp,
                columnCount = columns,
                minimumDp = minimum.value,
                availableDp = available.value,
            )
        }
        // Remembered against the resolved value rather than mapped inline, so a table whose widths cannot have
        // changed does not rebuild its width list on every keystroke in one of its cells.
        val widths = remember(resolved) { resolved.columnsDp.map { it.dp } }
        val gridWidth = resolved.totalDp.dp
        val frameShape = FlowFrameMetrics.shapeOf(block)
        Column(
            with(FlowFrameMetrics) {
                Modifier
                    .fillMaxWidth()
                    .clip(frameShape)
                    .background(MaterialTheme.colorScheme.surface)
                    .flowFrameEdge(frameShape, context.objectSelected, resting = true)
            },
        ) {
            // Gated on the same flag as the route itself: an escape hatch into a host the build has not
            // enabled would make the flag decorative. §25 — this opens the *same* table identity, so it is a
            // deeper surface, not a conversion.
            if (editorFlags.isEnabled(EditorFeatureFlag.SPREADSHEET_HOST)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(
                        onClick = { callbacks.onOpenTableInSpreadsheet(block.id) },
                        enabled = context.editing && !context.selectionMode,
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) { Text("Open in Spreadsheet") }
                }
            }
            // The scroll lives on an inner Column so the card's own edge, fill and the button above stay put
            // while the grid moves inside it. Scrolling the card itself would drag its rounded border off
            // screen and leave the block looking clipped.
            Column(Modifier.horizontalScroll(scroll)) {
                // The header tint lives on the row, not on each cell: an imported table can have fewer headers
                // than data columns, and per-cell fills would end the tint partway across and leave a white
                // notch above real columns. `width(gridWidth)` is what gives the row something to tint — inside
                // a scroll container it would otherwise shrink to its content.
                Row(
                    Modifier
                        .width(gridWidth)
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                ) {
                    block.headers.forEachIndexed { column, cell ->
                        FlowTableCell(
                            text = cell.content.plainText(),
                            header = true,
                            addressed = cursor?.row == -1 && cursor.column == column,
                            alignment = block.columnAlignments.getOrNull(column),
                            // In structural selection a cell tap must select the table block, not address a
                            // cell (correction item 6); the click is disabled so the row's own gesture sees it.
                            enabled = context.editing && !context.selectionMode,
                            modifier = Modifier.width(widths.getOrElse(column) { minimum }),
                            onClick = { callbacks.onTableCell(FlowTableCursor(block.id, row = -1, column = column)) },
                            editableText = if (cursor?.row == -1 && cursor.column == column) context.tableDraft else null,
                            onDraft = { callbacks.onTableCellDraft(FlowTableCursor(block.id, -1, column), it) },
                            onCommit = { callbacks.onTableCellCommit(FlowTableCursor(block.id, -1, column), it) },
                        )
                    }
                }
                block.rows.forEachIndexed { rowIndex, row ->
                    // An explicit width, because a divider is `fillMaxWidth()` inside and the scroll container
                    // hands its children an *unbounded* width constraint — under which fillMaxWidth resolves to
                    // the minimum, i.e. zero. Every row rule would have silently vanished.
                    HorizontalDivider(
                        modifier = Modifier.width(gridWidth),
                        color = MaterialTheme.colorScheme.outlineVariant,
                    )
                    // A row shorter than the header — an imported table can be ragged — is left short rather
                    // than padded with placeholder cells. A body cell draws no fill, so the gap is already
                    // invisible, and a tappable cell the model does not contain would hand the runtime a cursor
                    // it never agreed to.
                    Row {
                        row.forEachIndexed { column, cell ->
                            FlowTableCell(
                                text = cell.content.plainText(),
                                header = false,
                                addressed = cursor?.row == rowIndex && cursor.column == column,
                                alignment = block.columnAlignments.getOrNull(column),
                                enabled = context.editing && !context.selectionMode,
                                modifier = Modifier.width(widths.getOrElse(column) { minimum }),
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
    }
}

@Composable
private fun FlowTableCell(
    text: String,
    header: Boolean,
    addressed: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    alignment: TableAlignment? = null,
    enabled: Boolean = true,
    editableText: String? = null,
    onDraft: (String) -> Unit = {},
    onCommit: (String) -> Unit = {},
) {
    val cellStyle = if (header) FlowContent.type.labelLarge else FlowContent.type.bodyMedium
    // The header row needs a fill of its own: semibold alone stops separating header from body the moment a
    // body cell holds a short bold word, and on a white canvas with hairline rules the top row otherwise reads
    // as data. A tint of the page, never the accent — a coloured header row is a spreadsheet's chrome, and this
    // is a table inside a document. That tint is painted by the header *row* so it spans a ragged header too;
    // only the addressed accent is per-cell, drawn over whatever the row put down, so it still wins.
    val fill = if (addressed) MaterialTheme.colorScheme.primary.copy(alpha = 0.16f) else Color.Transparent
    // `columnAlignments` reached the renderer as dead data too: a column the user centred, or that the
    // calculation bridge derived a right alignment for from the workbook, was drawn start-aligned anyway.
    // Numbers in particular need the End case — a right-aligned column is how a reader compares magnitudes.
    // Applied as `TextAlign` on a full-width child rather than as the Box's `contentAlignment`, because both
    // children fill the cell: a Box alignment would have no slack to act on and the setting would look ignored.
    val textAlign = when (alignment) {
        TableAlignment.Center -> TextAlign.Center
        TableAlignment.End -> TextAlign.End
        else -> TextAlign.Start
    }
    // Focus loss is a commit route, not a discard: tapping the next cell has always meant "keep what I typed"
    // in every grid the user has met. Tracked here because Compose reports the transition, not the state.
    var hadFocus by remember { mutableStateOf(false) }
    Box(
        modifier = modifier
            .heightIn(min = 48.dp)
            .clickable(enabled = enabled && !addressed, onClick = onClick)
            .background(fill)
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
                textStyle = cellStyle.copy(color = LocalContentColor.current, textAlign = textAlign),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onCommit(editableText) }),
            )
        } else {
            // One line, matching `singleLine = true` above. The display path used to be unbounded, so a
            // two-word cell wrapped to two lines while idle and snapped back to one the instant it was
            // tapped — the row's height changed under the finger, and every row below it jumped. Same
            // shape in both states, and the ellipsis is what says the rest of the text is still there.
            Text(
                text = text.ifBlank { " " },
                style = cellStyle,
                textAlign = textAlign,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun FlowListRow(marker: String, content: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.Top) {
        Text(
            marker,
            style = FlowContent.type.bodyLarge,
            modifier = Modifier
                .width(FlowContent.gutter)
                .padding(top = 1.dp),
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
    structuralEnter: Boolean = true,
) {
    val text = nodes.plainText()
    val styled = flowInlineSpans(nodes)

    if (context.selectionMode || !context.editing) {
        Box(modifier.fillMaxWidth()) {
            Text(
                text = if (text.isEmpty()) AnnotatedString(placeholder) else styled,
                style = style.copy(
                    color = if (text.isEmpty()) {
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    } else {
                        resolvedContentColor(style.color, LocalContentColor.current)
                    },
                ),
                modifier = Modifier.fillMaxWidth().padding(start = 3.dp),
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
                    if (!next.selection.collapsed) {
                        callbacks.onTextSelection(blockId, itemId, next.selection.start, next.selection.end)
                    }
                } else if (!next.selection.collapsed) {
                    callbacks.onTextSelection(blockId, itemId, next.selection.start, next.selection.end)
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
                .onFocusChanged { focusState ->
                    // A tap can focus a field without changing its TextFieldValue. Report that focus
                    // transition explicitly so structural Enter/Backspace and list-item edits address
                    // the field the user actually entered rather than a stale or absent runtime caret.
                    callbacks.onEditorFocusChanged(blockId, focusState.isFocused)
                    if (focusState.isFocused) {
                        if (value.selection.collapsed) callbacks.onCaret(blockId, itemId, value.selection.start)
                        else callbacks.onTextSelection(blockId, itemId, value.selection.start, value.selection.end)
                    }
                }
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (event.key) {
                        Key.Enter, Key.NumPadEnter -> when {
                            event.isShiftPressed -> {
                                softBreak = true
                                false
                            }
                            else -> structuralEnter && callbacks.onNewLine()
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
 * Compose, and resolves colour through the shared [highlightColor] and [NorfoldFontFamilies] so the same
 * note looks the same in every host.
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
    // The canvas is neutral: a heading is a heading by size and weight, not by wearing the app accent.
    // Tinting H2/H3 with `primary` made every document carry the user's theme colour whether or not they
    // put it there, which is the one thing the writing surface is not supposed to do. Stored explicit
    // colour still wins inside resolvedTextStyle, so a heading the user coloured stays coloured.
    adaptThemeTextTokens = true,
    // …and the built-in *sizes* are a print stylesheet: an 11 pt body on a modular scale, correct on a page
    // and far too small on a phone. Left in charge they also made the reading-size preference do nothing at
    // all, since every paragraph resolved 11 pt before it ever looked at the base style. Here the shipped
    // defaults yield to [FlowContent.type]; an authored size, weight or face still wins. The page host does
    // *not* pass this, because paper has no reading preference.
    adaptBuiltInTypeScale = true,
)

/**
 * The glyph that says what kind of aside a callout is.
 *
 * Matched to the GitHub alert vocabulary the Markdown codec already imports from — info, lightbulb, megaphone,
 * triangle, octagon — so a warning written in a README arrives looking like the warning it was. The icon does
 * the work colour alone cannot: a tone that is only a tint is invisible to anyone who cannot separate a pale
 * green panel from a pale amber one.
 */
private fun calloutToneIcon(tone: CalloutTone): ImageVector = when (tone) {
    CalloutTone.NOTE -> Icons.Outlined.Info
    CalloutTone.TIP -> Icons.Outlined.Lightbulb
    CalloutTone.IMPORTANT -> Icons.Outlined.Campaign
    CalloutTone.WARNING -> Icons.Outlined.WarningAmber
    CalloutTone.CAUTION -> Icons.Outlined.ErrorOutline
}

/**
 * The base style for a heading at [level] — family, size and weight all from the content typography.
 *
 * No `copy(fontWeight = …)` afterwards: the weight belongs to the role, the role is already expressed in
 * [FlowContent.type], and re-asserting it here would beat a weight the user actually chose.
 */
@Composable
private fun headingStyle(level: Int): TextStyle = when (level) {
    1 -> FlowContent.type.headlineLarge
    2 -> FlowContent.type.headlineMedium
    3 -> FlowContent.type.headlineSmall
    4 -> FlowContent.type.titleLarge
    5 -> FlowContent.type.titleMedium
    else -> FlowContent.type.titleSmall
}
