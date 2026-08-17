package com.norfold.app.ui.editor.flow

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.norfold.app.domain.ChartBlock
import com.norfold.app.domain.CodeBlock
import com.norfold.app.domain.DocumentBlock
import com.norfold.app.domain.EmbedBlock
import com.norfold.app.domain.FileBlock
import com.norfold.app.domain.FlowObject
import com.norfold.app.domain.ImageBlock
import com.norfold.app.domain.MathBlock
import com.norfold.app.domain.MermaidBlock
import com.norfold.app.domain.ShapeBlock
import com.norfold.app.domain.TableBlock

/**
 * The edge an object block draws around itself, at rest and when selected.
 *
 * One composable for what was eight. The object paths in `FlowBlockContent.kt` used 10 dp for a code block,
 * 12 dp for HTML, an equation, a diagram and an embed, 14 dp for an image and a table, 16 dp for a chart, and
 * nothing at all for a file card — eight call sites, five radii, no rule. Each also made its own decision
 * about whether selection was visible, which is where the real defect was.
 *
 * [FlowObject.selectable] is true for nine kinds, and exactly two of them — an image and a chart — drew
 * anything when selected. A table, a shape, an equation, a diagram, a code block, an embed and a file card
 * could all be *made* the selection and would show the user nothing, which reads as a dead tap rather than as
 * a selection. [FlowEmbedPreview] and [FlowFilePreview] were not even passed the context that carries the
 * flag, so the omission was structural rather than an oversight at one call site.
 *
 * This does not decide *whether* an object is framed — [FlowObject.framed] already answers that from the
 * height, and re-deciding it here would be the second list of block kinds the domain model exists to avoid.
 * It decides how a frame *looks*.
 *
 * @param clipContent false for content whose silhouette is authored rather than rectangular. A shape stores
 *   its own corner radius, and clipping it to this frame's radius would crop a square shape's corners — the
 *   selection edge is chrome *around* the object and does not have to trace it.
 * @param resting false for a kind that paints its own permanent edge, so a frame does not double it.
 */
@Composable
fun FlowObjectFrame(
    block: DocumentBlock,
    selected: Boolean,
    modifier: Modifier = Modifier,
    resting: Boolean = true,
    clipContent: Boolean = true,
    content: @Composable BoxScope.() -> Unit,
) {
    val shape = FlowFrameMetrics.shapeOf(block)
    Box(
        with(FlowFrameMetrics) {
            modifier
                .fillMaxWidth()
                .then(if (clipContent) Modifier.clip(shape) else Modifier)
                .flowFrameEdge(shape, selected, resting)
        }
            .then(
                if (selected) Modifier.semantics {
                    contentDescription = "${flowObjectKindLabel(block)} selected"
                } else Modifier,
            ),
        content = content,
    )
}

/**
 * The frame's two measurements, and the one rule that picks between its two states.
 *
 * A separate object from the composable so the shape can be read by a renderer that needs to clip its own
 * content to the same curve — a chart canvas and a `WebView` both paint to their own bounds and would
 * otherwise square off the corner the frame just rounded.
 */
internal object FlowFrameMetrics {

    /**
     * The corner radius for [block]'s kind.
     *
     * Two values rather than one, and deliberately not the five the object paths used (10, 12, 14, 16, and no
     * frame at all for a file card).
     *
     * The split is not by size but by whether the content reaches the frame. An image is full-bleed — its
     * corner *is* the frame's corner, so a larger radius visibly crops the photograph, and 14 dp is what the
     * image path already used. A shape reaches the frame too, and takes the same tighter radius so a square
     * shape's selection edge hugs it rather than bowing away from it.
     *
     * Everything else is a card: it has its own internal padding, so the radius belongs to the card and not to
     * the content. 16 dp is what the chart card, the callout wrapper and the container wrapper already use,
     * which is what makes it the page's card radius rather than a number chosen here. A chart is media-sized
     * but card-framed — it insets its canvas by 6 dp and paints its own fill — so it keeps 16 dp and does not
     * follow the image.
     *
     * Everything that was 10 or 12 dp becomes 16 dp: those were all cards, and a 4-to-6 dp spread between
     * adjacent cards in one document is the kind of inconsistency a reader notices without being able to name
     * it. A table moves the other way, 14 dp to 16 dp, for the same reason — a table is a card with cell
     * padding, not a media box.
     */
    fun radiusOf(block: DocumentBlock): Dp = when (block) {
        is ImageBlock, is ShapeBlock -> MEDIA_RADIUS
        else -> CARD_RADIUS
    }

    /** [radiusOf] as a [Shape], which is what every call site actually wants. */
    fun shapeOf(block: DocumentBlock): Shape = RoundedCornerShape(radiusOf(block))

    /**
     * This modifier with the border for the frame's current state, or unchanged if the state draws none.
     *
     * [selected] wins over [resting] because a selection that merely thickened an already-visible edge is
     * not legible on a phone: the two states differ by one dp at arm's length. Selection changes the colour
     * as well as the weight, and the colour is the part that carries.
     *
     * [resting] false is how a kind opts out of a permanent edge while keeping its selection edge — a shape
     * paints its own authored stroke, and a second frame outside it would double every shape's outline.
     *
     * An extension on [Modifier] rather than a factory returning one, because a `@Composable` function that
     * takes no receiver and returns a `Modifier` is what lint's `ModifierFactoryExtensionFunction` exists to
     * catch — it was the only such function in the tree, so it was a mistake rather than a local convention.
     * The cost is that a caller outside this file opens `with(FlowFrameMetrics) { ... }` to bring the receiver
     * into scope; the gain is that inside that block the edge chains like every other modifier instead of
     * arriving through a `.then(...)`.
     */
    @Composable
    @ReadOnlyComposable
    fun Modifier.flowFrameEdge(shape: Shape, selected: Boolean, resting: Boolean): Modifier = when {
        selected -> border(SELECTED_WIDTH, MaterialTheme.colorScheme.primary, shape)
        resting -> border(RESTING_WIDTH, restingEdgeColor(), shape)
        else -> this
    }

    /**
     * The resting edge colour.
     *
     * Neither existing outline token can do this job, which is the finding the object-frame record predicted
     * and left to the implementation. Measured against this canvas: `outlineVariant` is **1.22:1** on the
     * light page and `outline` is **1.34:1** — both under the 1.5:1 a non-text boundary needs to be seen at
     * all, so "this content is a framed object" was a claim the six old borders never actually made. Dark is
     * no better: 1.31:1 and 1.67:1.
     *
     * So the edge is derived rather than picked: the theme's `outline` carried a measured fraction of the way
     * toward `onSurface`. That keeps it a theme-following value — the user's accent and mode still drive it,
     * and nothing here is a hardcoded colour — while landing both modes on the same *measured* result,
     * **2.20:1** light and **2.22:1** dark. Deriving from two tokens is also why one factor cannot serve
     * both: the light page starts 1.34:1 from its outline and the dark page 1.67:1, so equal factors would
     * give visibly unequal edges.
     *
     * 2.2:1 is a deliberate target, not a maximum. A resting edge states a boundary and then gets out of the
     * way; at 3:1 and above it reads as a rule competing with the content it contains, which is how a
     * document of framed objects starts looking like a spreadsheet. The one place it stays honest about its
     * limits: on `surfaceContainerHighest` — a card inside a container — the same value measures 1.72:1, so a
     * frame nested on the darkest light surface is weaker than a frame on the page. Framed objects nested
     * that deep are the container work's problem, not this composable's.
     */
    @Composable
    @ReadOnlyComposable
    private fun restingEdgeColor(): Color {
        val scheme = MaterialTheme.colorScheme
        val dark = scheme.background.luminance() < 0.35f
        return lerp(scheme.outline, scheme.onSurface, if (dark) DARK_EDGE_MIX else LIGHT_EDGE_MIX)
    }

    /** Media corners: what the image path used, kept because a larger radius crops the content. */
    val MEDIA_RADIUS = 14.dp

    /** Card corners: the chart card's radius, and the callout and container wrappers' radius. */
    val CARD_RADIUS = 16.dp

    /** A resting edge states a boundary; it is not a rule. */
    val RESTING_WIDTH = 1.dp

    /** A selection edge has to read at arm's length, which one dp of extra weight does not. */
    val SELECTED_WIDTH = 3.dp

    /**
     * How far the light resting edge travels from `outline` toward `onSurface` — measured to land 2.20:1.
     *
     * Named constants rather than literals at the call site because the two numbers look arbitrary and are
     * not: each is the solution for one canvas, and changing either without re-measuring silently unbalances
     * the two modes.
     */
    private const val LIGHT_EDGE_MIX = 0.285f

    /** The dark counterpart — 2.22:1, and a much smaller factor because the dark outline starts closer. */
    private const val DARK_EDGE_MIX = 0.105f
}

/**
 * What to call [block] in a sentence a user reads.
 *
 * Deliberately not [com.norfold.app.editor.flow.FlowBlockKind.label]: that enum is the *insert and convert*
 * vocabulary, and it has no entry for an image, a shape, an embed or a file card precisely because none of
 * them can be produced by typing a name. Those four are exactly the kinds a selection badge most needs to
 * name, so reading the label from there would return null for the cases that matter and force a fallback
 * that reads worse than this list.
 *
 * The fallback derives a name from the class rather than saying "Block", because a kind added later should
 * announce something approximately right rather than something uselessly generic.
 */
internal fun flowObjectKindLabel(block: DocumentBlock): String = when (block) {
    is ImageBlock -> "Image"
    is ChartBlock -> "Chart"
    is TableBlock -> "Table"
    is ShapeBlock -> "Shape"
    is CodeBlock -> "Code block"
    is MathBlock -> "Equation"
    is MermaidBlock -> "Diagram"
    is EmbedBlock -> "Embedded link"
    is FileBlock -> "File"
    else -> block::class.simpleName?.removeSuffix("Block") ?: "Object"
}
