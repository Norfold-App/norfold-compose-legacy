package com.norfold.app.collaboration.ops

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Generic semantic-operation framework. Domain-semantic operations, NOT whole-document JSON LWW.
 *
 * IMPORTANT SCOPE: the final Norfold editor/interaction spec is still being designed. These are
 * REPRESENTATIVE, EXTENSIBLE operation families — enough to drive infrastructure and benchmarks —
 * not a permanent, exhaustive catalogue. Do not force the future editor to match a frozen op set.
 * Each family carries an open [attributes] bag so new intents can be added without new subclasses,
 * and unknown domains degrade rather than fail.
 */
@Serializable
sealed interface SemanticOperation {
    /** Domain this operation belongs to (text, scene, table, chart, order, …). */
    val domain: String

    /** Stable id of the object/region this operation targets. */
    val targetId: String
}

/**
 * Rich-text operations over a CRDT text sequence with marks/block markers. Positions are
 * expressed against stable anchors, not raw indices, so concurrent edits don't corrupt intent.
 */
@Serializable
sealed interface TextOperation : SemanticOperation {
    override val domain: String get() = "text"

    @Serializable
    data class Insert(
        override val targetId: String,
        val anchor: String,
        val text: String,
        val marks: Map<String, String> = emptyMap(),
    ) : TextOperation

    @Serializable
    data class Delete(
        override val targetId: String,
        val fromAnchor: String,
        val toAnchor: String,
    ) : TextOperation

    @Serializable
    data class Mark(
        override val targetId: String,
        val fromAnchor: String,
        val toAnchor: String,
        val mark: String,
        val value: String?, // null = remove mark
    ) : TextOperation
}

/**
 * Scene-object operations: stable IDs + property/transform operations (create, delete, transform,
 * set-property). Represents drag/resize/rotate FINAL states as durable ops; live previews go
 * through the ephemeral preview channel, not here.
 */
@Serializable
sealed interface SceneOperation : SemanticOperation {
    override val domain: String get() = "scene"

    @Serializable
    data class Create(
        override val targetId: String,
        val objectType: String,
        val attributes: Map<String, String> = emptyMap(),
    ) : SceneOperation

    @Serializable
    data class Delete(override val targetId: String) : SceneOperation

    @Serializable
    data class Transform(
        override val targetId: String,
        val x: Double? = null,
        val y: Double? = null,
        val rotation: Double? = null,
        val scaleX: Double? = null,
        val scaleY: Double? = null,
    ) : SceneOperation

    @Serializable
    data class SetProperty(
        override val targetId: String,
        val key: String,
        val value: String?, // null = clear
    ) : SceneOperation
}

/** Table operations over stable row/column/cell IDs. */
@Serializable
sealed interface TableOperation : SemanticOperation {
    override val domain: String get() = "table"

    @Serializable data class InsertRow(override val targetId: String, val rowId: String, val afterRowId: String?) : TableOperation
    @Serializable data class InsertColumn(override val targetId: String, val colId: String, val afterColId: String?) : TableOperation
    @Serializable data class DeleteRow(override val targetId: String, val rowId: String) : TableOperation
    @Serializable data class DeleteColumn(override val targetId: String, val colId: String) : TableOperation
    @Serializable data class SetCell(override val targetId: String, val rowId: String, val colId: String, val value: JsonElement?) : TableOperation
}

/** Chart operations: data/series/mark/annotation mutations. */
@Serializable
sealed interface ChartOperation : SemanticOperation {
    override val domain: String get() = "chart"

    @Serializable data class SetSeries(override val targetId: String, val seriesId: String, val data: JsonElement) : ChartOperation
    @Serializable data class RemoveSeries(override val targetId: String, val seriesId: String) : ChartOperation
    @Serializable data class SetMark(override val targetId: String, val markId: String, val spec: JsonElement) : ChartOperation
    @Serializable data class SetAnnotation(override val targetId: String, val annotationId: String, val spec: JsonElement?) : ChartOperation
}

/**
 * Ordered-structure operations for pages/lists: stable-ID ordered sequence operations
 * (insert relative to a neighbor, move, remove). Fractional/positional ordering avoids index
 * conflicts under concurrency.
 */
@Serializable
sealed interface OrderedStructureOperation : SemanticOperation {
    override val domain: String get() = "order"

    @Serializable data class Insert(override val targetId: String, val itemId: String, val afterItemId: String?) : OrderedStructureOperation
    @Serializable data class Move(override val targetId: String, val itemId: String, val afterItemId: String?) : OrderedStructureOperation
    @Serializable data class Remove(override val targetId: String, val itemId: String) : OrderedStructureOperation
}

/**
 * Escape hatch for domains not yet modeled. Keeps the framework extensible while the editor spec
 * is unfinished — a new domain can flow end-to-end as an opaque op before it gets a typed family.
 */
@Serializable
data class OpaqueOperation(
    override val domain: String,
    override val targetId: String,
    val opType: String,
    val body: JsonElement,
) : SemanticOperation
