package com.norfold.app.editor.shape

import com.norfold.app.domain.ShapeBlock
import com.norfold.app.domain.style.ColorPaint
import com.norfold.app.domain.style.ColorValue
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The canonical, versioned Shape Builder document.
 *
 * A shape is a stack of [ShapeLayer]s, bottom to top, each contributing one closed region (or a stroke)
 * to the composite. Top to bottom in render terms means last-to-first in the list, so **index 0 is the
 * back of the stack**, exactly like `DocumentPageBlock.objects` and `SceneObject.z`. Every operation in
 * [ShapeLayerEditor] and the runtime is written against that one ordering.
 *
 * **Why a document at all, instead of one path.** A star is a parametric shape; a custom blob is a node
 * path; a Boolean result is a group. All three are "a shape" to the user, and all three must survive
 * saving and reopening, coexist in one shape, and each expose the controls that produced it. One [ShapeLayer]
 * per source of form is how those controls stay attached to what they created instead of being re-derived
 * and lost.
 *
 * The whole value lives in [ShapeBlock.shapeEditDocument], an additive field, so an unedited legacy shape
 * serializes byte-for-byte as it always did and every existing consumer of [ShapeBlock] keeps working
 * untouched. That is the §50 forward-compatibility contract: never reinterpret a payload this build does
 * not understand ([ShapeDocumentCodec.Origin.UNSUPPORTED_CANONICAL] preserves it verbatim for a later
 * build), and never silently lose one.
 */

/** A display-paint: the one color model the whole product shares (Phase 3). */
typealias ShapePaint = ColorPaint

/** Blending for an individual layer, resolved against the layer below. */
@Serializable
enum class ShapeBlendMode(val displayName: String) {
    NORMAL("Normal"),
    MULTIPLY("Multiply"),
    SCREEN("Screen"),
    OVERLAY("Overlay"),
    DARKEN("Darken"),
    LIGHTEN("Lighten"),
    ;

    companion object {
        fun fromName(name: String?): ShapeBlendMode = entries.firstOrNull { it.name == name } ?: NORMAL
    }
}

/**
 * A Boolean combination of two or more layers, which **owns** its children.
 *
 * The children are moved into the group rather than left in the stack and referenced by id. One
 * representation, so there is no way for the membership list and the actual layers to disagree — the
 * failure mode that would otherwise arrive the first time a child is deleted while its group still names
 * it. It stays non-destructive because the children are kept whole: [ShapeLayerEditor.ungroup] puts them
 * back exactly as they were, parameters and all, while [ShapeLayerEditor.flatten] is the separate,
 * explicit operation that gives up that ability in exchange for a plain editable path.
 *
 * Children are stored in their own stack order (bottom first), which is the order [ShapeBooleans] applies
 * the operation in — for [ShapeBooleanOperation.SUBTRACT] the bottom child is the one being cut.
 */
@Serializable
data class ShapeBooleanGroup(
    val id: String = shapeId("bool"),
    val operation: ShapeBooleanOperation = ShapeBooleanOperation.UNION,
    val children: List<ShapeLayer> = emptyList(),
) {
    /** A group needs two regions to combine; below that the operation has no meaning. */
    val isResolvable: Boolean get() = children.size >= 2

    fun normalized(): ShapeBooleanGroup = copy(
        id = id.trim().take(MAX_ID).ifEmpty { shapeId("bool") },
        children = normalizeLayerStack(children.take(MAX_CHILDREN)),
    )

    companion object {
        const val MAX_ID = 128
        const val MAX_CHILDREN = 32
    }
}

/** The four Boolean operations, applied to the flattened regions of the children. */
@Serializable
enum class ShapeBooleanOperation(val displayName: String) {
    UNION("Union"),
    SUBTRACT("Subtract"),
    INTERSECT("Intersect"),
    EXCLUDE("Exclude"),
    ;

    companion object {
        fun fromName(name: String?): ShapeBooleanOperation = entries.firstOrNull { it.name == name } ?: UNION
    }
}

/** Blur, shadow and glow are each optional, so an untouched layer serializes with nothing. */
@Serializable
data class ShapeEffects(
    val blurRadius: Float = 0f,
    val shadow: ShapeShadow = ShapeShadow(),
    val glow: ShapeGlow = ShapeGlow(),
) {
    fun normalized() = copy(
        blurRadius = blurRadius.finiteOr(0f).coerceIn(0f, 100f),
        shadow = shadow.normalized(),
        glow = glow.normalized(),
    )

    val isIdentity: Boolean get() = blurRadius == 0f && !shadow.enabled && !glow.enabled
}

@Serializable
data class ShapeShadow(
    val enabled: Boolean = false,
    val color: ColorValue = ColorValue(0, 0, 0, 96),
    val blurRadius: Float = 8f,
    val offsetX: Float = 0f,
    val offsetY: Float = 4f,
) {
    fun normalized() = copy(
        color = color,
        blurRadius = blurRadius.finiteOr(0f).coerceIn(0f, 100f),
        offsetX = offsetX.finiteOr(0f).coerceIn(-200f, 200f),
        offsetY = offsetY.finiteOr(0f).coerceIn(-200f, 200f),
    )
}

/** A soft outer glow: the layer's fill color, blurred and multiplied. */
@Serializable
data class ShapeGlow(
    val enabled: Boolean = false,
    val color: ColorValue = ColorValue(255, 255, 255, 140),
    val blurRadius: Float = 12f,
    val strength: Float = 1f,
) {
    fun normalized() = copy(
        color = color,
        blurRadius = blurRadius.finiteOr(0f).coerceIn(0f, 100f),
        strength = strength.finiteOr(1f).coerceIn(0f, 1f),
    )
}

/** How much of the layer stack participates in a clip mask. */
@Serializable
enum class ShapeMaskScope { LAYER, BELOW }

/** A path-shaped mask, painted like a layer but clipped instead of composited. */
@Serializable
data class ShapeMask(
    val enabled: Boolean = false,
    val scope: ShapeMaskScope = ShapeMaskScope.LAYER,
    val path: ShapePath = ShapePath.of(ShapeSubpath.polygon(defaultMaskPoints())),
    val inverted: Boolean = false,
) {
    fun normalized(): ShapeMask {
        val clean = copy(
            scope = ShapeMaskScope.entries.firstOrNull { it.name == scope.name } ?: ShapeMaskScope.LAYER,
            path = path.normalized(),
        )
        return if (clean.path.isEmpty) clean.copy(enabled = false) else clean
    }

    companion object {
        /** A mask appears with a centred rounded-rectangle cut-out ready to draw in, not an empty path. */
        fun defaultMaskPoints(): List<ShapePoint> = listOf(
            ShapePoint(20f, 20f), ShapePoint(80f, 20f), ShapePoint(80f, 80f), ShapePoint(20f, 80f),
        )
    }
}

/** What a layer contributes to the composite. */
@Serializable
sealed interface ShapeLayerContent {
    /** The outline geometry of this layer, in local canvas units. */
    fun toPath(): ShapePath

    /**
     * Whether this layer has geometry to contribute, answered structurally rather than by generating the
     * outline. Layer-list and render-order queries run this per layer per frame, and for a Boolean layer
     * [toPath] means resolving the whole group — far too much work to spend on deciding whether a row is
     * worth drawing. It may say yes where [toPath] would come back empty (a Boolean whose operation cancels
     * out); that costs one empty draw, where the reverse would cost a resolve on every frame.
     */
    val hasGeometry: Boolean

    fun normalized(): ShapeLayerContent
}

/**
 * A parametric preset. Its only stored state is which preset and its parameters — the outline is derived
 * on demand, so changing the point count re-renders the same star instead of editing a stale outline.
 */
@Serializable
data class ShapePrimitiveLayer(
    val kind: ShapePrimitiveKind = ShapePrimitiveKind.ROUNDED_RECTANGLE,
    val parameters: ShapeParameters = ShapeParameters(),
) : ShapeLayerContent {
    override fun toPath(): ShapePath = ShapePrimitives.generate(kind, parameters)
    override val hasGeometry: Boolean get() = true
    override fun normalized() = copy(parameters = parameters.normalized())
}

/** A node-edited path. The product of Convert to Path, and the layer type node editing always lands on. */
@Serializable
data class ShapePathLayer(
    val path: ShapePath = ShapePath(),
) : ShapeLayerContent {
    override fun toPath(): ShapePath = path.normalized()
    override val hasGeometry: Boolean get() = !path.isEmpty
    override fun normalized() = copy(path = path.normalized())
}

/** The flattened outline of a Boolean group — the region that is actually painted. */
@Serializable
data class ShapeBooleanLayer(
    val group: ShapeBooleanGroup = ShapeBooleanGroup(),
) : ShapeLayerContent {
    override fun toPath(): ShapePath = ShapeBooleans.compute(group).path
    override val hasGeometry: Boolean get() = group.children.any { it.visible && it.content.hasGeometry }
    override fun normalized() = copy(group = group.normalized())
}

/**
 * One stack entry: geometry source plus the paint decisions over it.
 *
 * [name] is user-assigned for the layer list; an unnamed layer falls back to its kind. [fill]/[stroke]
 * mirror the legacy [ShapeBlock] pair, but each is stored as a [ShapePaint] so gradients are first-class
 * without any second color model (Phase 3's is the only one).
 */
@Serializable
data class ShapeLayer(
    val id: String = shapeId("lyr"),
    val name: String = "",
    val content: ShapeLayerContent = ShapePrimitiveLayer(),
    val fill: ShapePaint = ColorPaint.Flat(ColorValue(0xFF, 0x6F, 0x36)),
    val stroke: ShapeStroke = ShapeStroke(),
    val opacity: Float = 1f,
    val blendMode: ShapeBlendMode = ShapeBlendMode.NORMAL,
    val visible: Boolean = true,
    val locked: Boolean = false,
    val effects: ShapeEffects = ShapeEffects(),
    /** Clip mask over this layer, or over everything below it ([ShapeMaskScope.BELOW]). */
    val mask: ShapeMask = ShapeMask(),
) {
    val isVisibleLayer: Boolean get() = visible && content.hasGeometry

    fun normalized(): ShapeLayer = copy(
        id = id.trim().take(MAX_ID).ifEmpty { shapeId("lyr") },
        name = name.trim().take(MAX_NAME),
        content = content.normalized(),
        fill = fill,
        stroke = stroke.normalized(),
        opacity = opacity.finiteOr(1f).coerceIn(0f, 1f),
        blendMode = ShapeBlendMode.fromName(blendMode.name),
        effects = effects.normalized(),
        mask = mask.normalized(),
    )

    companion object {
        const val MAX_ID = 128
        const val MAX_NAME = 96
    }
}

/** Stroke of a layer: a paint (usually flat), a width, and how ends and corners are shaped. */
@Serializable
data class ShapeStroke(
    val enabled: Boolean = true,
    val paint: ShapePaint = ColorPaint.Flat(ColorValue(0xD8, 0xC8, 0xFF)),
    val width: Float = 1.5f,
    val cap: ShapeStrokeCap = ShapeStrokeCap.ROUND,
    val join: ShapeStrokeJoin = ShapeStrokeJoin.ROUND,
) {
    fun normalized() = copy(
        paint = paint,
        width = width.finiteOr(0f).coerceIn(0f, 100f),
        cap = ShapeStrokeCap.entries.firstOrNull { it.name == cap.name } ?: ShapeStrokeCap.ROUND,
        join = ShapeStrokeJoin.entries.firstOrNull { it.name == join.name } ?: ShapeStrokeJoin.ROUND,
    )
}

@Serializable
enum class ShapeStrokeCap { BUTT, ROUND, SQUARE }
@Serializable
enum class ShapeStrokeJoin { MITER, ROUND, BEVEL }

/**
 * The whole editable document, one level deep: a layer stack plus shared accessibility.
 *
 * "One level deep" is deliberate. A Boolean group's children are plain layers, never groups themselves,
 * so flattening a group never has to decide what a nested group means. If nested groups ever arrive they
 * arrive as a new schema version, not as a quietly expanded field.
 */
@Serializable
data class ShapeDocument(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    /** Layer stack, bottom to top. Index 0 is the back. */
    val layers: List<ShapeLayer> = listOf(ShapeLayer()),
    val accessibility: ShapeAccessibility = ShapeAccessibility(),
) {
    fun normalized(): ShapeDocument = copy(
        schemaVersion = CURRENT_SCHEMA_VERSION,
        layers = normalizeLayerStack(layers),
        accessibility = accessibility.normalized(),
    )

    fun layerById(id: String): ShapeLayer? = layers.firstOrNull { it.id == id }

    fun indexOf(id: String): Int = layers.indexOfFirst { it.id == id }

    fun topLayer(): ShapeLayer? = layers.lastOrNull()

    /** All ids, in stack order. */
    fun layerIds(): List<String> = layers.map { it.id }

    /** Whether the stack has any visible, paintable geometry — an empty shape has nothing to render. */
    fun isRenderable(): Boolean = layers.any { it.isVisibleLayer }

    /** The stack's overall outline bounds, local canvas units; the empty box when nothing draws. */
    fun outlineBounds(): ShapeRect {
        val renderable = layers.filter { it.isVisibleLayer }
        if (renderable.isEmpty()) return ShapeRect(0f, 0f, 0f, 0f)
        return renderable.map { it.content.toPath().bounds() }.reduce { a, b -> a.union(b) }
    }

    fun renderOrder(): List<ShapeLayer> = layers.filter { it.visible }

    companion object {
        /**
         * The stored schema version.
         *
         * Bumped only when a payload written by this version can no longer be read correctly by the
         * previous one. Additive fields with defaults do not bump it — `ignoreUnknownKeys` means an older
         * build already skips them, and [ShapeDocumentCodec] refuses a *newer* version outright rather
         * than guessing, so the bump is what makes that refusal accurate.
         */
        const val CURRENT_SCHEMA_VERSION = 1

        /** Layer count ceiling: hostile or corrupted input cannot build an unusable document. */
        const val MAX_LAYERS = 64

        fun fromPreset(preset: ShapePreset): ShapeDocument =
            ShapeDocument(layers = listOf(ShapeLayer(content = ShapePrimitiveLayer(preset.kind, preset.parameters))))

        /** A blank node-edited path, ready for draw-mode. */
        fun emptyPathDocument(): ShapeDocument =
            ShapeDocument(layers = listOf(ShapeLayer(content = ShapePathLayer(ShapePath()))))
    }
}

/**
 * What the user reads and what screen readers announce.
 *
 * Alt text is deliberately never derived from the shape (there is no visible caption to fall back on),
 * and a document marked decorative is skipped entirely — the same two rules Image Focus uses, because the
 * two features share a user.
 */
@Serializable
data class ShapeAccessibility(
    val alternativeText: String = "",
    val decorative: Boolean = false,
) {
    fun normalized() = copy(alternativeText = alternativeText.trim().take(MAX_ALT_TEXT))
    companion object { const val MAX_ALT_TEXT = 2_048 }
}

/**
 * The normalization half of the layer stack invariant.
 *
 * Every mutation in [ShapeLayerEditor] runs through this, so the invariant "index 0 is the back" is one
 * function. Doing it centrally — rather than scattering `distinct()` and id-fixes through every edit — is
 * what makes the stack rules testable as one contract.
 *
 * A duplicate id is re-minted rather than deduplicated by removal: a document with two layers sharing an
 * id is corrupt bookkeeping, but both layers are still geometry the user drew, and dropping one to tidy
 * the ledger would silently delete part of their shape. Recursion into Boolean children is bounded by the
 * schema — a group's children are never themselves groups — so this terminates by construction.
 */
internal fun normalizeLayerStack(layers: List<ShapeLayer>): List<ShapeLayer> {
    val seen = HashSet<String>(layers.size)
    return layers.take(ShapeDocument.MAX_LAYERS)
        .map { it.normalized() }
        .map { layer ->
            if (seen.add(layer.id)) layer else layer.copy(id = shapeId("lyr")).also { seen.add(it.id) }
        }
}

/**
 * The one place Shape Builder edits a document.
 *
 * Every operation here is a pure function of the document and returns the edited document, so the runtime
 * can diff two documents instead of tracking edits. The result always passes [ShapeDocument.normalized],
 * and the few operations that need ids ([append]) mint them inside the edit — the runtime never sees a
 * pre-made id and never has to guess whether one was already used.
 *
 * Layer identity is the stack's one hard rule, repeated here because every operation depends on it:
 * **index 0 is the back**. `append` adds to the *top* ([layers] + new). `move` spells out its own
 * direction. All other operations take ids and resolve them through the same lookup, so a list is never
 * iterated with a raw index anywhere else.
 */
object ShapeLayerEditor {

    // ── Stack structure ─────────────────────────────────────────────────────────────────────────

    fun append(document: ShapeDocument, layer: ShapeLayer): ShapeDocument =
        document.copy(layers = normalizeLayerStack(document.layers + layer))

    /** Removing the last layer is refused: a shape with no layers has nothing to edit or render. */
    fun remove(document: ShapeDocument, layerId: String): ShapeDocument {
        if (document.layers.size <= 1) return document
        val kept = document.layers.filterNot { it.id == layerId }
        if (kept.size == document.layers.size) return document
        return document.copy(layers = normalizeLayerStack(kept))
    }

    /** Moves [layerId] up the stack (toward the front) by one; false at the top, or if locked. */
    fun moveUp(document: ShapeDocument, layerId: String): ShapeDocument {
        val index = document.indexOf(layerId)
        if (index < 0 || index >= document.layers.size - 1 || document.layers[index].locked) return document
        return swap(document, index, index + 1)
    }

    /** Moves [layerId] down the stack (toward the back) by one; false at the bottom, or if locked. */
    fun moveDown(document: ShapeDocument, layerId: String): ShapeDocument {
        val index = document.indexOf(layerId)
        if (index <= 0 || document.layers[index].locked) return document
        return swap(document, index, index - 1)
    }

    fun duplicate(document: ShapeDocument, layerId: String): ShapeDocument {
        val index = document.indexOf(layerId)
        if (index < 0) return document
        val original = document.layers[index]
        val clone = original.copy(
            id = shapeId("lyr"),
            name = (original.name.trim().ifEmpty { original.id } + " copy"),
            content = duplicateContent(original.content),
        )
        return document.copy(layers = normalizeLayerStack(
            document.layers.toMutableList().apply { add(index + 1, clone) }.toList(),
        ))
    }

    fun rename(document: ShapeDocument, layerId: String, name: String): ShapeDocument =
        replaceLayer(document, layerId) { it.copy(name = name.trim().take(ShapeLayer.MAX_NAME)) }

    fun setVisibility(document: ShapeDocument, layerId: String, visible: Boolean): ShapeDocument =
        replaceLayer(document, layerId) { it.copy(visible = visible) }

    fun setLocked(document: ShapeDocument, layerId: String, locked: Boolean): ShapeDocument =
        replaceLayer(document, layerId) { it.copy(locked = locked) }

    // ── Content ──────────────────────────────────────────────────────────────────────────────────

    fun setKind(document: ShapeDocument, layerId: String, kind: ShapePrimitiveKind): ShapeDocument =
        replaceLayer(document, layerId) {
            val parameters = (it.content as? ShapePrimitiveLayer)?.parameters ?: ShapeParameters()
            it.copy(content = ShapePrimitiveLayer(kind, parameters))
        }

    /** Keeps a same-kind parameter when switching presets, so Star→Polygon retains the point count. */
    fun setParameters(document: ShapeDocument, layerId: String, parameters: ShapeParameters): ShapeDocument =
        replaceLayer(document, layerId) { layer ->
            when (val content = layer.content) {
                is ShapePrimitiveLayer -> layer.copy(content = content.copy(parameters = parameters.normalized()))
                is ShapePathLayer -> layer
                is ShapeBooleanLayer -> layer
            }
        }

    /** Set, or clear when null, the edited path of a path layer. */
    fun setPath(document: ShapeDocument, layerId: String, path: ShapePath?): ShapeDocument =
        replaceLayer(document, layerId) { layer ->
            when (val content = layer.content) {
                is ShapePathLayer -> if (path == null) layer else layer.copy(content = content.copy(path = path.normalized()))
                is ShapePrimitiveLayer -> layer
                is ShapeBooleanLayer -> layer
            }
        }

    fun replaceContent(document: ShapeDocument, layerId: String, content: ShapeLayerContent): ShapeDocument =
        replaceLayer(document, layerId) { it.copy(content = content.normalized()) }

    // ── Paint ────────────────────────────────────────────────────────────────────────────────────

    fun setFill(document: ShapeDocument, layerId: String, fill: ShapePaint?): ShapeDocument =
        replaceLayer(document, layerId) { if (fill == null) it else it.copy(fill = fill) }

    fun setStrokePaint(document: ShapeDocument, layerId: String, paint: ShapePaint?): ShapeDocument =
        replaceLayer(document, layerId) { layer ->
            if (paint == null) layer else layer.copy(stroke = layer.stroke.copy(paint = paint))
        }

    fun setStrokeWidth(document: ShapeDocument, layerId: String, width: Float): ShapeDocument =
        replaceLayer(document, layerId) { it.copy(stroke = it.stroke.copy(width = width)) }

    fun setStrokeEnabled(document: ShapeDocument, layerId: String, enabled: Boolean): ShapeDocument =
        replaceLayer(document, layerId) { it.copy(stroke = it.stroke.copy(enabled = enabled)) }

    fun setStrokeCap(document: ShapeDocument, layerId: String, cap: ShapeStrokeCap): ShapeDocument =
        replaceLayer(document, layerId) { it.copy(stroke = it.stroke.copy(cap = cap)) }

    fun setStrokeJoin(document: ShapeDocument, layerId: String, join: ShapeStrokeJoin): ShapeDocument =
        replaceLayer(document, layerId) { it.copy(stroke = it.stroke.copy(join = join)) }

    fun setOpacity(document: ShapeDocument, layerId: String, opacity: Float): ShapeDocument =
        replaceLayer(document, layerId) { it.copy(opacity = opacity) }

    fun setBlendMode(document: ShapeDocument, layerId: String, mode: ShapeBlendMode): ShapeDocument =
        replaceLayer(document, layerId) { it.copy(blendMode = mode) }

    fun setEffects(document: ShapeDocument, layerId: String, effects: ShapeEffects): ShapeDocument =
        replaceLayer(document, layerId) { it.copy(effects = effects.normalized()) }

    fun setMask(document: ShapeDocument, layerId: String, mask: ShapeMask?): ShapeDocument =
        replaceLayer(document, layerId) {
            if (mask == null) it.copy(mask = ShapeMask(enabled = false)) else it.copy(mask = mask.normalized())
        }

    // ── Boolean groups ───────────────────────────────────────────────────────────────────────────

    /**
     * Combines two or more layers into one Boolean group layer.
     *
     * The children are *moved* into the group, in their existing stack order, and the group takes the
     * position of the topmost of them — so the result sits where the user was looking rather than jumping
     * to the front of the stack. The group inherits the bottom child's paint, because for Subtract that is
     * the layer being cut and therefore the one whose appearance survives.
     */
    fun groupAsBoolean(document: ShapeDocument, layerIds: List<String>, operation: ShapeBooleanOperation): ShapeDocument {
        val members = layerIds.distinct().mapNotNull { document.layerById(it) }
        if (members.size < 2) return document
        val ordered = document.layers.filter { layer -> members.any { it.id == layer.id } }
        val topIndex = ordered.maxOf { document.indexOf(it.id) }
        val remaining = document.layers.filterNot { layer -> ordered.any { it.id == layer.id } }
        // Where the group lands: how many surviving layers sat below the topmost member.
        val insertAt = document.layers.take(topIndex + 1)
            .count { layer -> remaining.any { it.id == layer.id } }
        val groupLayer = ShapeLayer(
            content = ShapeBooleanLayer(ShapeBooleanGroup(operation = operation, children = ordered)),
            fill = ordered.first().fill,
            stroke = ordered.first().stroke,
        )
        return document.copy(layers = normalizeLayerStack(
            remaining.toMutableList().apply { add(insertAt, groupLayer) }.toList(),
        ))
    }

    /** Explodes the group back into its children, in place; [flatten] collapses to one path layer instead. */
    fun ungroup(document: ShapeDocument, layerId: String): ShapeDocument {
        val index = document.indexOf(layerId)
        if (index < 0) return document
        val group = (document.layers[index].content as? ShapeBooleanLayer)?.group ?: return document
        val layers = document.layers.toMutableList()
        layers.removeAt(index)
        layers.addAll(index, group.children)
        return document.copy(layers = normalizeLayerStack(layers))
    }

    /**
     * Replaces the group with the plain path its operation produces.
     *
     * Unlike [ungroup] this is lossy — the children and the operation are gone, and what remains is an
     * ordinary editable outline. It is offered as its own command precisely so that losing them is a
     * choice the user makes rather than something that happens to them when they node-edit a group.
     */
    fun flatten(document: ShapeDocument, layerId: String): ShapeDocument =
        replaceLayer(document, layerId) { layer ->
            val group = (layer.content as? ShapeBooleanLayer)?.group ?: return@replaceLayer layer
            layer.copy(content = ShapePathLayer(ShapeBooleans.compute(group).path))
        }

    fun setBooleanOperation(document: ShapeDocument, layerId: String, operation: ShapeBooleanOperation): ShapeDocument =
        replaceLayer(document, layerId) { layer ->
            val content = layer.content as? ShapeBooleanLayer ?: return@replaceLayer layer
            layer.copy(content = content.copy(group = content.group.copy(operation = operation)))
        }

    // ── Conversion ───────────────────────────────────────────────────────────────────────────────

    /**
     * Convert to Path: replaces a primitive layer with the node-edited path of its current outline.
     *
     * This is the one-way door. The path is the shape's outline at conversion time, so afterwards every
     * edit is a node edit — the point count slider is gone by design, and the only way back to parametric
     * editing is to recreate the primitive. That is the trade the user is making when they choose this.
     *
     * The generated path is taken exactly as generated, subpaths and open/closed flags intact. Collapsing
     * it into a single closed ring would change the shape at the moment of conversion: a Line would come
     * back as a closed loop, and a speech bubble's tail would be stitched to its body. Convert to Path
     * gives up the parameters, not the outline.
     */
    fun convertToPath(document: ShapeDocument, layerId: String): ShapeDocument =
        replaceLayer(document, layerId) { layer ->
            when (val content = layer.content) {
                is ShapePrimitiveLayer ->
                    layer.copy(content = ShapePathLayer(ShapePrimitives.generate(content.kind, content.parameters)))
                is ShapePathLayer -> layer
                is ShapeBooleanLayer -> layer
            }
        }

    // ── Internals ─────────────────────────────────────────────────────────────────────────────────

    private fun replaceLayer(document: ShapeDocument, layerId: String, edit: (ShapeLayer) -> ShapeLayer): ShapeDocument {
        val index = document.indexOf(layerId)
        if (index < 0) return document
        return replaceLayerAt(document, index, edit)
    }

    private fun replaceLayerAt(document: ShapeDocument, index: Int, edit: (ShapeLayer) -> ShapeLayer): ShapeDocument {
        val layers = document.layers.toMutableList()
        layers[index] = edit(layers[index])
        return document.copy(layers = normalizeLayerStack(layers))
    }

    private fun swap(document: ShapeDocument, from: Int, to: Int): ShapeDocument {
        val layers = document.layers.toMutableList()
        val moved = layers[from]
        layers[from] = layers[to]
        layers[to] = moved
        return document.copy(layers = normalizeLayerStack(layers))
    }

    /**
     * Fresh ids throughout a duplicated layer.
     *
     * Subpath and child ids are re-minted as well as the layer's own, because a duplicate that shared
     * subpath ids with its original would let a node edit addressed to one silently resolve against the
     * other.
     */
    private fun duplicateContent(content: ShapeLayerContent): ShapeLayerContent = when (content) {
        is ShapePrimitiveLayer -> content
        is ShapePathLayer -> content.copy(
            path = content.path.copy(subpaths = content.path.subpaths.map { it.copy(id = shapeId("sp")) }),
        )
        is ShapeBooleanLayer -> content.copy(
            group = content.group.copy(
                id = shapeId("bool"),
                children = content.group.children.map {
                    it.copy(id = shapeId("lyr"), content = duplicateContent(it.content))
                },
            ),
        )
    }
}

/**
 * The envelope for persisted Shape Builder documents.
 *
 * Versioned like Image Edit's envelope: a later schema bumps the version and this build stops reading —
 * but because the payload is preserved verbatim in the block, a later build that understands version 2
 * finds it exactly as this build left it. That is the forward-compatibility contract, and it is why the
 * codec never drops a payload it does not understand.
 */
@Serializable
private data class ShapeEnvelope(val schemaVersion: Int, val document: ShapeDocument)

sealed interface ShapeDecodeResult {
    data class Success(val document: ShapeDocument) : ShapeDecodeResult
    data class UnsupportedVersion(val version: Int, val raw: String) : ShapeDecodeResult
    data class Malformed(val raw: String) : ShapeDecodeResult
}

data class LoadedShapeDocument(
    val document: ShapeDocument,
    val origin: Origin,
    val preservedPayload: String?,
) {
    enum class Origin { CANONICAL, LEGACY_EMPTY, UNSUPPORTED_CANONICAL, MALFORMED_CANONICAL }
    val isEditable: Boolean get() = origin != Origin.UNSUPPORTED_CANONICAL && origin != Origin.MALFORMED_CANONICAL
}

object ShapeDocumentCodec {
    private val json = Json { encodeDefaults = true; explicitNulls = false; ignoreUnknownKeys = true }

    fun encode(document: ShapeDocument): String = json.encodeToString(
        ShapeEnvelope(ShapeDocument.CURRENT_SCHEMA_VERSION, document.normalized()),
    )

    fun decode(raw: String): ShapeDecodeResult {
        if (raw.isBlank()) return ShapeDecodeResult.Malformed(raw)
        val version = runCatching {
            json.parseToJsonElement(raw).jsonObject["schemaVersion"]?.jsonPrimitive?.intOrNull
        }.getOrNull() ?: return ShapeDecodeResult.Malformed(raw)
        if (version != ShapeDocument.CURRENT_SCHEMA_VERSION) {
            return ShapeDecodeResult.UnsupportedVersion(version, raw)
        }
        return runCatching { json.decodeFromString<ShapeEnvelope>(raw).document.normalized() }
            .fold(ShapeDecodeResult::Success) { ShapeDecodeResult.Malformed(raw) }
    }
}

/**
 * The bridge between a persisted [ShapeBlock] and a canonical [ShapeDocument].
 *
 * **Loading is side-effect free; saving changes only the additive canonical field.** A legacy shape (no
 * canonical payload) loads as an empty document whose first layer is the legacy kind converted through
 * [ShapePrimitiveKind.fromLegacy], so the first thing a user sees in Shape Builder is the shape they
 * actually drew. A malformed or unknown payload is preserved verbatim and reported as not editable, so a
 * later build (or a fixed payload) can always recover it.
 */
object ShapeBlockAdapter {
    /** Builds the initial document for a legacy shape, capturing its legacy presentation faithfully. */
    fun legacyDocument(block: ShapeBlock): ShapeDocument {
        val kind = ShapePrimitiveKind.fromLegacy(block.kind)
        val layer = ShapeLayer(
            content = ShapePrimitiveLayer(kind = kind, parameters = parametersForLegacy(kind)),
            fill = block.fillPaint ?: ColorPaint.Flat(ColorValue.fromArgb(block.fillColor)),
            stroke = ShapeStroke(
                enabled = block.strokeWidthDp > 0f,
                paint = block.strokePaint ?: ColorPaint.Flat(ColorValue.fromArgb(block.strokeColor)),
                width = block.strokeWidthDp,
            ),
            opacity = 1f,
        )
        return ShapeDocument(layers = listOf(layer)).normalized()
    }

    /** Legacy defaults matched per kind, so a Triangle opens with corners and a Star with five points. */
    private fun parametersForLegacy(kind: ShapePrimitiveKind): ShapeParameters = when (kind) {
        ShapePrimitiveKind.ROUNDED_RECTANGLE -> ShapeParameters(cornerRadius = 0.18f)
        ShapePrimitiveKind.TRIANGLE, ShapePrimitiveKind.DIAMOND -> ShapeParameters(cornerRadius = 0f)
        ShapePrimitiveKind.STAR -> ShapeParameters(pointCount = 5, innerRadius = 0.42f, cornerRadius = 0f)
        ShapePrimitiveKind.POLYGON -> ShapeParameters(pointCount = 6, cornerRadius = 0f)
        else -> ShapeParameters()
    }

    fun load(block: ShapeBlock): LoadedShapeDocument {
        val raw = block.shapeEditDocument
            ?: return LoadedShapeDocument(legacyDocument(block), LoadedShapeDocument.Origin.LEGACY_EMPTY, null)
        return when (val decoded = ShapeDocumentCodec.decode(raw)) {
            is ShapeDecodeResult.Success -> LoadedShapeDocument(
                decoded.document, LoadedShapeDocument.Origin.CANONICAL, raw,
            )
            is ShapeDecodeResult.UnsupportedVersion -> LoadedShapeDocument(
                legacyDocument(block), LoadedShapeDocument.Origin.UNSUPPORTED_CANONICAL, decoded.raw,
            )
            is ShapeDecodeResult.Malformed -> LoadedShapeDocument(
                legacyDocument(block), LoadedShapeDocument.Origin.MALFORMED_CANONICAL, decoded.raw,
            )
        }
    }

    /**
     * Saves the document, mirroring the legacy word fields so pre-canonical consumers never see a gap.
     *
     * [ShapeBlock.fillColor]/[strokeColor] are kept in sync with the canonical paints — the same "one
     * accessor resolves the pair" rule [com.norfold.app.domain.effectiveFill] already establishes for
     * reading, applied here to writing.
     */
    fun save(block: ShapeBlock, document: ShapeDocument): ShapeBlock {
        val clean = document.normalized()
        val top = clean.topLayer()
        return block.copy(
            shapeEditDocument = ShapeDocumentCodec.encode(clean),
            fillColor = top?.fill?.representative()?.toArgb() ?: block.fillColor,
            strokeColor = top?.stroke?.paint?.representative()?.toArgb() ?: block.strokeColor,
            strokeWidthDp = top?.stroke?.width ?: block.strokeWidthDp,
        )
    }
}
