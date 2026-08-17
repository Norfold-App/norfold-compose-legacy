package com.norfold.app.domain

/**
 * One independently addressable thing in a Flow note: its content, and how that content is framed.
 *
 * This is the Flow-side counterpart of [SceneObject], and it exists for the same reason: *what* a piece of
 * content is and *how it is presented* are different questions, and answering them in one type is what
 * produced seven separate height fields. A paragraph, a chart and a shape are three contents; each is
 * presented as an object, and object presentation is the same problem every time.
 *
 * It is deliberately **not** a [DocumentBlock]. Nothing is added to the wire format, no serializer changes,
 * and no stored document is rewritten. A block carries the seven fields it always carried, and
 * [DocumentBlock.deriveFlowObject] reads a frame out of them. That makes this a read-time derivation rather
 * than a data migration — the distinction that lets it ship without touching a single saved note.
 *
 * Three things live here that were previously scattered or absent:
 *
 * [height] replaces `CodeBlock.editorHeightDp`, `ImageBlock.displayHeightDp`, `EmbedBlock.displayHeightDp`,
 * `ChartBlock.editorHeightDp`, `MathBlock.editorHeightDp`, `MermaidBlock.editorHeightDp` and
 * `ShapeBlock.displayHeightDp` — seven fields with seven defaults and no shared meaning, which is why no
 * command could reach any of them.
 *
 * [align] unifies `ParagraphBlock.align` and `HeadingBlock.align`, the only two blocks that had alignment,
 * with every other kind previously unable to express it at all.
 *
 * [frame] is new, and it is the field the selection chrome has been missing. Selectability was a type test
 * (`block is ImageBlock || block is ChartBlock`) — so a table and a shape could not be selected however
 * obviously object-like they are. Framing is now a property of the object.
 *
 * Nothing here is serialized. When a later phase needs a *user-chosen* frame to persist, the field it adds is
 * additive on the block, and [deriveFlowObject] reads it in preference to the derivation — the same shape
 * `ShapeBlock.fillPaint` and `TableBlock.calculationPayload` already use, where null means "the legacy field
 * remains authoritative".
 */
data class FlowObject(
    val content: DocumentBlock,
    val height: FlowHeight = FlowHeight.Intrinsic,
    val align: BlockAlign = BlockAlign.Start,
    val frame: FlowFrame = FlowFrame.Auto,
    val style: com.norfold.app.domain.style.StyleAssignment = com.norfold.app.domain.style.StyleAssignment.NONE,
) {
    val id: String get() = content.id

    /**
     * Whether this object draws a frame, with [FlowFrame.Auto] resolved.
     *
     * `Auto` means "decide from the kind", and the answer is: content with intrinsic text height flows, and
     * content that occupies a box is framed. That is precisely the distinction [FlowHeight] already encodes,
     * so it is read from there rather than restated as a second list of block types that could disagree with
     * the first.
     */
    val framed: Boolean get() = when (frame) {
        FlowFrame.None -> false
        FlowFrame.Framed -> true
        FlowFrame.Auto -> height !is FlowHeight.Intrinsic
    }

    /**
     * Whether a selection frame may be drawn around this object.
     *
     * Identical to [framed] today, and kept as its own name because the two answer different questions: one
     * is about how the object is drawn at rest, the other about whether it can be picked up. A later frame
     * style that draws no resting edge but is still selectable would separate them, and the call sites should
     * already be asking the right question.
     */
    val selectable: Boolean get() = framed
}

/**
 * How tall an object is.
 *
 * A sealed interface rather than a `Float`, because the seven fields it replaces were all floats and that is
 * exactly what was wrong with them: a stored dp answers "how tall is this right now on this device", which is
 * not a durable property of the content. Each case below is a different *kind* of answer.
 */
sealed interface FlowHeight {

    /**
     * As tall as the content needs. The default, and what every text block wants.
     *
     * Costs nothing to give to text kinds that never had a height field, because "intrinsic" is what they
     * already did implicitly — so adding the concept changes no rendering.
     */
    data object Intrinsic : FlowHeight

    /**
     * A specific height in dp. What a chart or an embed wants, having no intrinsic height of its own.
     *
     * This is the case the seven legacy fields map onto, which is why the derivation is lossless: a document
     * saved before this type existed produces `Fixed(itsStoredValue)` and renders identically.
     */
    data class Fixed(val dp: Float) : FlowHeight

    /**
     * A width-to-height ratio. What an image wants.
     *
     * "Keep my aspect" survives a rotation, a measure change and a font-scale change, where a stored dp does
     * not — an image pinned to 220dp is the wrong height on the next device it opens on. Nothing produces
     * `Ratio` yet; images derive `Fixed` from their stored height until the loader reports real dimensions,
     * at which point this becomes the honest answer without any stored value changing meaning.
     */
    data class Ratio(val widthOverHeight: Float) : FlowHeight

    companion object {
        /**
         * A ratio, or [Intrinsic] if the value could not describe one.
         *
         * Zero, negative, NaN and infinite all mean "the caller does not actually know the aspect" — an
         * image that has not loaded, or a decode that failed. Intrinsic is the truthful answer there, and a
         * total function means no call site needs its own guard. A ratio is clamped rather than rejected:
         * an absurd aspect is still a real image, and refusing to draw it is worse than drawing it badly.
         */
        fun ratioOrIntrinsic(widthOverHeight: Float): FlowHeight =
            if (!widthOverHeight.isFinite() || widthOverHeight <= 0f) Intrinsic
            else Ratio(widthOverHeight.coerceIn(MINIMUM_RATIO, MAXIMUM_RATIO))

        /**
         * A fixed height, or [Intrinsic] if the value could not describe one.
         *
         * Any finite positive value is kept **exactly**, and deliberately not clamped. A clamp here would be
         * the seventh height range in the codebase — the renderer already applies its own in six places
         * (`120f..560f` for an image, `72f..560f` for a shape, `156f..360f` for a diagram), and those ranges
         * are presentation limits that a shared frame composable is meant to unify. A domain model that
         * silently rounded a stored 10dp up to a floor of its own would make the count worse, and would
         * corrupt the stored value the first time a resize command round-tripped through
         * [DocumentBlock.withFlowHeight].
         *
         * So the only rejected values are the ones that are not heights at all: NaN and infinity (an
         * arithmetic accident upstream, and a crash or an invisible block if it reaches Compose), and zero or
         * negative (nothing ever legitimately stored one). For those, `Intrinsic` is the truthful answer —
         * "this content does not tell you how tall it is" — and being total means no call site needs a guard.
         */
        fun fixedOrIntrinsic(dp: Float): FlowHeight =
            if (!dp.isFinite() || dp <= 0f) Intrinsic else Fixed(dp)

        /**
         * Narrower than 1:20 is a hairline, and no real image is one.
         *
         * A ratio *is* clamped where a fixed height is not, and the asymmetry is the point: a fixed height is
         * a value a user chose and storage preserved, so changing it is data loss. A ratio is computed from
         * whatever an image loader reported, so an absurd one is a decode error rather than an intention —
         * and repairing it to draw something is better than refusing to draw.
         */
        const val MINIMUM_RATIO = 0.05f

        /** Wider than 20:1 likewise. */
        const val MAXIMUM_RATIO = 20f
    }
}

/**
 * The range a kind's height may be *drawn* at.
 *
 * The counterpart to [FlowHeight], and the reason it is a separate concept: [FlowHeight] answers "what height
 * does this content have", which is a durable property of the document, and this answers "what height may a
 * renderer put on screen", which is a presentation limit. Conflating them is what made
 * [FlowHeight.fixedOrIntrinsic] refuse to clamp — a model that rounded a stored 10dp up to a floor of its own
 * would corrupt that value the first time a resize command read and wrote it back.
 *
 * It exists because the renderer applied these inline, in six places, and two of them disagreed about the same
 * block. A [ShapeBlock] whose geometry has been built draws through one path and clamped to `120f..560f`,
 * while a shape that has not draws through the legacy path and clamped to `72f..560f` — so a shape stored at
 * 90dp grew by 30dp the first time someone opened it in the Shape Builder, with nothing about its height
 * having been edited. One home per kind is what makes that impossible to write again.
 *
 * Four of the ranges below are exactly what the renderer already used, so adopting them changes no pixel. The
 * two that are new ([MathBlock] and [EmbedBlock]) are floored at each kind's own stored default rather than at
 * a number chosen here, for the reason given on [of].
 */
data class FlowSizePolicy(val minHeightDp: Float, val maxHeightDp: Float) {

    /**
     * [dp] brought into this policy's range. The one operation a renderer performs.
     *
     * Total, including for NaN and infinity, which `coerceIn` alone is not: every comparison against NaN is
     * false, so `NaN.coerceIn(120f, 560f)` returns NaN, and a NaN height reaching Compose is a crash or an
     * invisible block. The floor is the answer for a value that is not a height, because drawing the content
     * small is recoverable and drawing nothing is not.
     */
    fun clamp(dp: Float): Float = if (!dp.isFinite()) minHeightDp else dp.coerceIn(minHeightDp, maxHeightDp)

    /**
     * [height] resolved to a drawable dp, or null when the content has no height of its own.
     *
     * Null rather than a fallback number because "no height" and "this height" are different answers, and the
     * caller is the only thing that knows what to do with the first: a text block flows, and an image whose
     * ratio has not resolved yet must not be pinned to an invented default.
     */
    fun resolve(height: FlowHeight): Float? = when (height) {
        is FlowHeight.Fixed -> clamp(height.dp)
        is FlowHeight.Intrinsic, is FlowHeight.Ratio -> null
    }

    companion object {
        /**
         * The policy for the kind [block] is, or null if that kind stores no height.
         *
         * Null is the honest answer for a paragraph: it has no size policy because it has no size to constrain,
         * and returning a range would invite a caller to clamp something that should flow. The kinds listed
         * here are exactly the seven [DocumentBlock.supportsFlowHeight] reports, so the two cannot disagree
         * about which blocks have a height.
         *
         * Two of these ranges do not exist in the renderer, because the renderer reads neither field: a
         * [MathBlock] and an [EmbedBlock] each store a height that nothing draws. Their floors are therefore
         * each kind's **own default** — 140f and 112f — and not the 180f the neighbouring source panes use. A
         * floor above a kind's default is a floor that resizes every existing block of that kind the day
         * someone wires the field up, which is a migration disguised as a constant.
         */
        fun of(block: DocumentBlock): FlowSizePolicy? = when (block) {
            // A media box: tall enough to be a picture rather than a strip, and capped so one image cannot
            // take a whole phone screen.
            is ImageBlock -> MEDIA
            is ChartBlock -> MEDIA
            // A shape is allowed to be shorter than media, because a divider-like bar or a small badge is a
            // legitimate shape and 120dp would inflate it. This is the range the legacy path used; the built
            // path used MEDIA's floor, and that disagreement is the defect this type removes.
            is ShapeBlock -> SHAPE
            // A source-and-preview pane. The floor is a real minimum rather than a preference: below it the
            // tab strip plus one line of monospace source does not fit, so the pane clips its own chrome.
            is CodeBlock -> SOURCE_PANE
            // Floored at their own defaults — see the note above.
            is MathBlock -> MATH_PANE
            is EmbedBlock -> EMBED_CARD
            // A diagram's ceiling is lower than a source pane's because a Mermaid preview scales its content
            // to the box: past this height the graph is enlarged rather than more complete.
            is MermaidBlock -> DIAGRAM
            else -> null
        }

        /** Images and charts — `120f..560f` in the renderer today. */
        val MEDIA = FlowSizePolicy(120f, 560f)

        /** Shapes — `72f..560f`, the range the legacy shape path used. */
        val SHAPE = FlowSizePolicy(72f, 560f)

        /** A code block's HTML source/preview pane — `180f..560f`, which is also [CodeBlock]'s default. */
        val SOURCE_PANE = FlowSizePolicy(180f, 560f)

        /** Equations — floored at [MathBlock]'s own 140f default, not at a source pane's 180f. */
        val MATH_PANE = FlowSizePolicy(140f, 560f)

        /** Embed cards — floored at [EmbedBlock]'s own 112f default. A link card is a strip, not a pane. */
        val EMBED_CARD = FlowSizePolicy(112f, 560f)

        /** Mermaid diagrams — `156f..360f`. */
        val DIAGRAM = FlowSizePolicy(156f, 360f)
    }
}

/**
 * Whether an object draws an edge around itself.
 *
 * [Auto] is the default and exists so that adopting this type changes nothing: an existing note renders
 * exactly as it does today, because the renderer keeps deciding from the kind. Without `Auto` the derivation
 * would have to guess a user intention per block, and a wrong guess is visible on every reopen.
 *
 * [None] and [Framed] are user choices, and only a user may write them.
 */
enum class FlowFrame { Auto, None, Framed }

/**
 * The [FlowObject] this block presents as, derived from what the block already stores.
 *
 * Every branch reads a field that exists today, so this function cannot lose information and cannot fail.
 * It is the whole of the "no migration" claim: a note saved by any previous build derives the frame that
 * describes how that build drew it.
 *
 * `TableBlock` is deliberately absent from the height branch. Its `columnWidthsDp` are internal table
 * structure rather than object presentation, and its height is the sum of its rows — so `Intrinsic` is
 * correct, and the table still becomes selectable through [FlowFrame.Framed] below.
 */
fun DocumentBlock.deriveFlowObject(): FlowObject = FlowObject(
    content = this,
    height = when (this) {
        is CodeBlock -> FlowHeight.fixedOrIntrinsic(editorHeightDp)
        is ImageBlock -> FlowHeight.fixedOrIntrinsic(displayHeightDp)
        is EmbedBlock -> FlowHeight.fixedOrIntrinsic(displayHeightDp)
        is ChartBlock -> FlowHeight.fixedOrIntrinsic(editorHeightDp)
        is MathBlock -> FlowHeight.fixedOrIntrinsic(editorHeightDp)
        is MermaidBlock -> FlowHeight.fixedOrIntrinsic(displayHeightDpOfShapeOrDiagram())
        is ShapeBlock -> FlowHeight.fixedOrIntrinsic(displayHeightDp)
        else -> FlowHeight.Intrinsic
    },
    align = when (this) {
        is ParagraphBlock -> align
        is HeadingBlock -> align
        else -> BlockAlign.Start
    },
    frame = when (this) {
        // Object-like content that has no stored height, so `Auto` would flow it. A table and a file card are
        // boxes on the page however tall they happen to be, and this is what makes them selectable — the
        // defect the old `block is ImageBlock || block is ChartBlock` test produced.
        is TableBlock, is FileBlock -> FlowFrame.Framed
        else -> FlowFrame.Auto
    },
    style = styleAssignment(),
)

/** [MermaidBlock] stores its height under the editor name; read through one place so the mapping is stated once. */
private fun MermaidBlock.displayHeightDpOfShapeOrDiagram(): Float = editorHeightDp

/**
 * This block with [height] written back into whichever field it came from, or unchanged if it has none.
 *
 * The inverse of the height branch of [deriveFlowObject], and the reason a resize command can exist at all:
 * the object model is what the command speaks, and this is the single place that turns it back into storage.
 * [FlowHeight.Intrinsic] and [FlowHeight.Ratio] write nothing — neither can be expressed in a `Float` field,
 * and silently flattening a ratio to whatever height it currently resolves to would turn a durable property
 * into a stale one.
 */
fun DocumentBlock.withFlowHeight(height: FlowHeight): DocumentBlock {
    val dp = (height as? FlowHeight.Fixed)?.dp ?: return this
    return when (this) {
        is CodeBlock -> copy(editorHeightDp = dp)
        is ImageBlock -> copy(displayHeightDp = dp)
        is EmbedBlock -> copy(displayHeightDp = dp)
        is ChartBlock -> copy(editorHeightDp = dp)
        is MathBlock -> copy(editorHeightDp = dp)
        is MermaidBlock -> copy(editorHeightDp = dp)
        is ShapeBlock -> copy(displayHeightDp = dp)
        else -> this
    }
}

/**
 * Whether this block kind stores a height at all — what a resize command checks before offering itself.
 *
 * Derived by asking [withFlowHeight] to write a height and seeing whether it came back, rather than by
 * restating the list of block kinds, because two lists of block kinds are two lists that can disagree. The
 * probe value is deliberately one no block defaults to, so a kind that *does* store a height reports true
 * even when the block already happens to be that tall.
 */
fun DocumentBlock.supportsFlowHeight(): Boolean =
    withFlowHeight(FlowHeight.Fixed(HEIGHT_SUPPORT_PROBE_DP)).deriveFlowObject().height ==
        FlowHeight.Fixed(HEIGHT_SUPPORT_PROBE_DP)

/**
 * An arbitrary height used only to probe whether a block kind stores one.
 *
 * Not a round number and not any block's default (180/220/112/140/72), so the probe cannot be satisfied by
 * coincidence — if [supportsFlowHeight] sees this value come back, the write genuinely landed in a field.
 */
private const val HEIGHT_SUPPORT_PROBE_DP = 137f
