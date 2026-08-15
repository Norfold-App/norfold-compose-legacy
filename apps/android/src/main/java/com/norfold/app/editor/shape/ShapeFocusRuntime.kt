package com.norfold.app.editor.shape

import com.norfold.app.domain.ShapeBlock
import com.norfold.app.editor.history.FocusHistoryOwner

fun interface ShapeFocusCommitHost {
    /** The host must record this as one canonical undo transaction. */
    fun commit(label: String, block: ShapeBlock): Boolean
}

/**
 * What the editor has hold of.
 *
 * Selection is session state, not document state, and deliberately not part of the undo snapshot: undoing
 * a node move should put the node back, not rearrange what the user has selected. It is still *validated*
 * against the document after every change, because a selection naming a node that no longer exists is a
 * live bug — the toolbar would offer Delete for something that is already gone.
 */
data class ShapeSelection(
    val layerId: String? = null,
    val nodes: Set<ShapeNodeRef> = emptySet(),
    val handle: ShapeHandleRef? = null,
) {
    val isEmpty: Boolean get() = nodes.isEmpty() && handle == null
    val single: ShapeNodeRef? get() = nodes.singleOrNull()
}

data class ShapeFocusRuntimeStats(
    val undoDepth: Int,
    val redoDepth: Int,
    val committedHostTransactions: Long,
    val cancelledGestures: Long,
    val previewChanges: Long,
)

/**
 * Focus-local history and previews for Shape Builder. Nothing reaches host history until [done].
 *
 * This mirrors `ImageFocusRuntime` deliberately, down to the method names: the two tools have the same
 * lifecycle obligation — many local steps, exactly one host transaction — and a second lifecycle that
 * merely resembles the first is how the two drift into disagreeing about what a cancelled gesture means.
 *
 * The runtime owns no geometry logic. [ShapeLayerEditor] and [ShapePathEditor] are pure functions from
 * document to document and path to path; everything here is about *when* one of those results becomes a
 * step, and when a run of steps becomes a commit.
 */
class ShapeFocusRuntime(
    private val baseline: ShapeBlock,
    private val host: ShapeFocusCommitHost,
    readOnly: Boolean = false,
    private val historyLimit: Int = DEFAULT_HISTORY_LIMIT,
) {
    private val loaded = ShapeBlockAdapter.load(baseline)

    /**
     * Locked when the caller says so, or when the stored payload could not be understood.
     *
     * The second case is the one that matters: a payload from a newer schema, or a corrupt one, loads as a
     * *substitute* document built from the legacy fields. Letting that be edited and saved would overwrite
     * the real payload with the substitute — silently destroying a shape this build merely failed to read.
     */
    private val locked = readOnly || !loaded.isEditable

    private val undo = ArrayDeque<ShapeDocument>()
    private val redo = ArrayDeque<ShapeDocument>()
    private val initial = loaded.document
    private var working = initial
    private var gestureStart: ShapeDocument? = null
    private var selection = ShapeSelection(layerId = initial.topLayer()?.id)
    private var comparingOriginal = false
    private var closed = false
    private var commits = 0L
    private var cancelledGestures = 0L
    private var previewChanges = 0L

    /** The document as edited. [document] is what to draw; this is what to save. */
    fun snapshot(): ShapeDocument = working

    /** What to render right now — the original while comparing, the working document otherwise. */
    fun document(): ShapeDocument = if (comparingOriginal) initial else working

    fun selection(): ShapeSelection = selection
    fun origin(): LoadedShapeDocument.Origin = loaded.origin

    /** The exact stored payload when it could not be read, so a caller can show what is being preserved. */
    fun preservedPayload(): String? = loaded.preservedPayload

    fun isReadOnly(): Boolean = locked || closed
    fun canUndo(): Boolean = !isReadOnly() && !comparingOriginal && gestureStart == null && undo.isNotEmpty()
    fun canRedo(): Boolean = !isReadOnly() && !comparingOriginal && gestureStart == null && redo.isNotEmpty()
    fun isComparingOriginal(): Boolean = comparingOriginal
    fun hasGesture(): Boolean = gestureStart != null
    fun isDirty(): Boolean = working != initial

    fun stats() = ShapeFocusRuntimeStats(undo.size, redo.size, commits, cancelledGestures, previewChanges)

    /** True until [done] or [cancel] closes the session. */
    fun isOpen(): Boolean = !closed

    /**
     * This session as the owner of the undo chord while it is open (spec §13.5).
     *
     * The predicates are the session's own rather than a host's copy, for the same reason Image Focus gives:
     * a host that re-derived "can undo here" would eventually disagree about a gesture in progress, and the
     * chord would either run a step the tool refuses or refuse one it would have run.
     *
     * A locked session is not an owner. It can never accumulate a local step, so claiming the chord would
     * make undo silently dead for as long as the shape is open.
     */
    fun historyOwner(focusId: String): FocusHistoryOwner? {
        if (locked) return null
        return FocusHistoryOwner.of(
            focusId = focusId,
            canUndo = ::canUndo,
            canRedo = ::canRedo,
            undo = ::undo,
            redo = ::redo,
            isActive = ::isOpen,
        )
    }

    // ── Selection ────────────────────────────────────────────────────────────────────────────────────

    /** Selection changes are not history: selecting a node is not an edit and must not be undoable. */
    fun select(selection: ShapeSelection): Boolean {
        val next = validated(selection, working)
        if (next == this.selection) return false
        this.selection = next
        return true
    }

    fun selectLayer(layerId: String?): Boolean =
        select(ShapeSelection(layerId = layerId))

    fun selectNode(ref: ShapeNodeRef?): Boolean =
        select(selection.copy(nodes = setOfNotNull(ref), handle = null))

    fun toggleNode(ref: ShapeNodeRef): Boolean =
        select(selection.copy(
            nodes = if (ref in selection.nodes) selection.nodes - ref else selection.nodes + ref,
            handle = null,
        ))

    fun selectHandle(ref: ShapeHandleRef?): Boolean =
        select(selection.copy(handle = ref, nodes = setOfNotNull(ref?.node)))

    fun clearSelection(): Boolean = select(ShapeSelection(layerId = selection.layerId))

    /** Resolves a tap into a selection, so tap-to-select behaves the same everywhere. */
    fun hitTest(point: ShapePoint, radius: Float = ShapePathEditor.DEFAULT_HIT_RADIUS): ShapeHit =
        activePath()?.let { ShapePathEditor.hitTest(it, point, radius) } ?: ShapeHit.None

    // ── Editing ──────────────────────────────────────────────────────────────────────────────────────

    /** One document edit, one local step. Refused mid-gesture: a gesture is already one step. */
    fun edit(transform: (ShapeDocument) -> ShapeDocument): Boolean {
        if (isReadOnly() || comparingOriginal || gestureStart != null) return false
        return replaceWorking(transform(working).normalized())
    }

    /** The layer being edited, or null when the selection does not name one that exists. */
    fun activeLayer(): ShapeLayer? = selection.layerId?.let { working.layerById(it) }

    /** The active layer's geometry, whether it is stored as a path or generated from parameters. */
    fun activePath(): ShapePath? = activeLayer()?.content?.toPath()

    /**
     * Edits the active layer's nodes.
     *
     * A parametric layer is converted to a path first, and that conversion is the honest cost of node
     * editing: a shape defined by "hexagon, corner radius 0.2" has no nodes to move, so moving one means it
     * is no longer that description. Doing the conversion here — as part of the same step — is what makes it
     * undoable in one press instead of leaving the user with a converted shape after undoing their drag.
     *
     * A locked layer is refused rather than silently converted; that is what the lock is for.
     */
    fun editPath(transform: (ShapePath) -> ShapePath): Boolean {
        val layerId = selection.layerId ?: return false
        val layer = working.layerById(layerId) ?: return false
        if (layer.locked) return false
        return edit { document ->
            val converted = ShapeLayerEditor.convertToPath(document, layerId)
            val path = converted.layerById(layerId)?.content?.toPath() ?: return@edit document
            ShapeLayerEditor.setPath(converted, layerId, transform(path))
        }
    }

    // Node operations. Each is one step, named for the toolbar action it backs.

    fun moveSelectedNodes(delta: ShapePoint): Boolean {
        if (selection.nodes.isEmpty()) return false
        return editPath { ShapePathEditor.moveNodesBy(it, selection.nodes, delta) }
    }

    fun moveNode(ref: ShapeNodeRef, to: ShapePoint): Boolean =
        editPath { ShapePathEditor.moveNode(it, ref, to) }

    fun moveHandle(ref: ShapeNodeRef, side: ShapeHandleSide, to: ShapePoint): Boolean =
        editPath { ShapePathEditor.moveHandle(it, ref, side, to) }

    fun addPointAt(point: ShapePoint, radius: Float = ShapePathEditor.DEFAULT_HIT_RADIUS): Boolean =
        editPath { ShapePathEditor.insertNodeNear(it, point, radius) }

    /**
     * Delete Point.
     *
     * Selection is cleared first, before the document changes: the nodes are about to stop existing, and
     * clearing afterwards would mean a moment where the selection names deleted geometry.
     */
    fun deleteSelectedNodes(): Boolean {
        val targets = selection.nodes
        if (targets.isEmpty()) return false
        val changed = editPath { ShapePathEditor.deleteNodes(it, targets) }
        if (changed) clearSelection()
        return changed
    }

    fun setSelectedNodeType(type: ShapeNodeType): Boolean =
        forEachSelectedNode { path, ref -> ShapePathEditor.setNodeType(path, ref, type) }

    fun smoothSelectedNodes(): Boolean =
        forEachSelectedNode { path, ref -> ShapePathEditor.smoothNode(path, ref) }

    fun mirrorSelectedNodes(): Boolean =
        forEachSelectedNode { path, ref -> ShapePathEditor.mirrorNode(path, ref) }

    fun sharpenSelectedNodes(): Boolean =
        forEachSelectedNode { path, ref -> ShapePathEditor.sharpenNode(path, ref) }

    fun roundSelectedNodes(): Boolean =
        forEachSelectedNode { path, ref -> ShapePathEditor.roundNode(path, ref) }

    fun closeActiveSubpath(subpathId: String): Boolean =
        editPath { ShapePathEditor.closeSubpath(it, subpathId) }

    fun openActiveSubpath(subpathId: String, afterNodeId: String): Boolean =
        editPath { ShapePathEditor.openSubpath(it, subpathId, afterNodeId) }

    fun reverseActiveSubpath(subpathId: String): Boolean =
        editPath { ShapePathEditor.reverseSubpath(it, subpathId) }

    fun setFillRule(rule: ShapeFillRule): Boolean =
        editPath { ShapePathEditor.setFillRule(it, rule) }

    // Layer operations, each one step. These are thin by design — the editor already decides what they mean.

    fun addLayer(layer: ShapeLayer = ShapeLayer()): Boolean {
        val added = edit { ShapeLayerEditor.append(it, layer) }
        if (added) selectLayer(working.topLayer()?.id)
        return added
    }

    fun addPrimitive(kind: ShapePrimitiveKind, parameters: ShapeParameters = ShapeParameters()): Boolean =
        addLayer(ShapeLayer(content = ShapePrimitiveLayer(kind = kind, parameters = parameters)))

    fun addPreset(preset: ShapePreset): Boolean =
        addLayer(ShapeLayer(name = preset.displayName, content = ShapePrimitiveLayer(preset.kind, preset.parameters)))

    fun removeLayer(layerId: String): Boolean {
        val removed = edit { ShapeLayerEditor.remove(it, layerId) }
        if (removed && selection.layerId == layerId) selectLayer(working.topLayer()?.id)
        return removed
    }

    fun duplicateLayer(layerId: String): Boolean = edit { ShapeLayerEditor.duplicate(it, layerId) }
    fun moveLayerUp(layerId: String): Boolean = edit { ShapeLayerEditor.moveUp(it, layerId) }
    fun moveLayerDown(layerId: String): Boolean = edit { ShapeLayerEditor.moveDown(it, layerId) }
    fun renameLayer(layerId: String, name: String): Boolean = edit { ShapeLayerEditor.rename(it, layerId, name) }
    fun setLayerVisible(layerId: String, visible: Boolean): Boolean = edit { ShapeLayerEditor.setVisibility(it, layerId, visible) }
    fun setLayerLocked(layerId: String, locked: Boolean): Boolean = edit { ShapeLayerEditor.setLocked(it, layerId, locked) }
    fun setKind(layerId: String, kind: ShapePrimitiveKind): Boolean = edit { ShapeLayerEditor.setKind(it, layerId, kind) }
    fun setParameters(layerId: String, parameters: ShapeParameters): Boolean = edit { ShapeLayerEditor.setParameters(it, layerId, parameters) }
    fun setFill(layerId: String, fill: ShapePaint?): Boolean = edit { ShapeLayerEditor.setFill(it, layerId, fill) }
    fun setStrokePaint(layerId: String, paint: ShapePaint?): Boolean = edit { ShapeLayerEditor.setStrokePaint(it, layerId, paint) }
    fun setStrokeWidth(layerId: String, width: Float): Boolean = edit { ShapeLayerEditor.setStrokeWidth(it, layerId, width) }
    fun setStrokeEnabled(layerId: String, enabled: Boolean): Boolean = edit { ShapeLayerEditor.setStrokeEnabled(it, layerId, enabled) }
    fun setStrokeCap(layerId: String, cap: ShapeStrokeCap): Boolean = edit { ShapeLayerEditor.setStrokeCap(it, layerId, cap) }
    fun setStrokeJoin(layerId: String, join: ShapeStrokeJoin): Boolean = edit { ShapeLayerEditor.setStrokeJoin(it, layerId, join) }
    fun setOpacity(layerId: String, opacity: Float): Boolean = edit { ShapeLayerEditor.setOpacity(it, layerId, opacity) }
    fun setBlendMode(layerId: String, mode: ShapeBlendMode): Boolean = edit { ShapeLayerEditor.setBlendMode(it, layerId, mode) }
    fun setEffects(layerId: String, effects: ShapeEffects): Boolean = edit { ShapeLayerEditor.setEffects(it, layerId, effects) }
    fun setMask(layerId: String, mask: ShapeMask?): Boolean = edit { ShapeLayerEditor.setMask(it, layerId, mask) }
    fun convertToPath(layerId: String): Boolean = edit { ShapeLayerEditor.convertToPath(it, layerId) }

    fun groupAsBoolean(layerIds: List<String>, operation: ShapeBooleanOperation): Boolean {
        val grouped = edit { ShapeLayerEditor.groupAsBoolean(it, layerIds, operation) }
        if (grouped) selectLayer(working.layers.lastOrNull { it.content is ShapeBooleanLayer }?.id)
        return grouped
    }

    fun ungroup(layerId: String): Boolean = edit { ShapeLayerEditor.ungroup(it, layerId) }
    fun flattenGroup(layerId: String): Boolean = edit { ShapeLayerEditor.flatten(it, layerId) }
    fun setBooleanOperation(layerId: String, operation: ShapeBooleanOperation): Boolean =
        edit { ShapeLayerEditor.setBooleanOperation(it, layerId, operation) }

    fun setAccessibility(accessibility: ShapeAccessibility): Boolean =
        edit { it.copy(accessibility = accessibility) }

    /** Reset keeps accessibility, which is content rather than a visual effect. */
    fun resetToLegacy(): Boolean = edit {
        ShapeBlockAdapter.legacyDocument(baseline).copy(accessibility = it.accessibility)
    }

    // ── Gestures ─────────────────────────────────────────────────────────────────────────────────────

    /**
     * Opens a gesture. A drag is one undo step no matter how many frames it spans.
     *
     * Without this, dragging a node across the canvas would push a step per touch sample and bury every
     * earlier edit under a hundred entries the user cannot tell apart.
     */
    fun beginGesture(): Boolean {
        if (isReadOnly() || comparingOriginal || gestureStart != null) return false
        gestureStart = working
        return true
    }

    fun previewGesture(transform: (ShapeDocument) -> ShapeDocument): Boolean {
        if (isReadOnly() || gestureStart == null) return false
        val next = transform(working).normalized()
        if (next == working) return false
        working = next
        selection = validated(selection, working)
        previewChanges++
        return true
    }

    /** The gesture equivalent of [editPath] — the same conversion rule, applied per frame. */
    fun previewPath(transform: (ShapePath) -> ShapePath): Boolean {
        val layerId = selection.layerId ?: return false
        if (working.layerById(layerId)?.locked != false) return false
        return previewGesture { document ->
            val converted = ShapeLayerEditor.convertToPath(document, layerId)
            val path = converted.layerById(layerId)?.content?.toPath() ?: return@previewGesture document
            ShapeLayerEditor.setPath(converted, layerId, transform(path))
        }
    }

    /** Closes the gesture into one step. A gesture that changed nothing leaves no step behind. */
    fun endGesture(): Boolean {
        val start = gestureStart ?: return false
        gestureStart = null
        if (start == working) return false
        pushBounded(undo, start)
        redo.clear()
        return true
    }

    fun cancelGesture(): Boolean {
        val start = gestureStart ?: return false
        working = start
        gestureStart = null
        selection = validated(selection, working)
        cancelledGestures++
        return true
    }

    // ── History ──────────────────────────────────────────────────────────────────────────────────────

    fun undo(): Boolean {
        if (!canUndo()) return false
        pushBounded(redo, working)
        working = undo.removeLast()
        selection = validated(selection, working)
        return true
    }

    fun redo(): Boolean {
        if (!canRedo()) return false
        pushBounded(undo, working)
        working = redo.removeLast()
        selection = validated(selection, working)
        return true
    }

    /** Hold-to-compare. Refused mid-gesture, where "the original" would mean a half-finished drag. */
    fun setCompareOriginal(active: Boolean): Boolean {
        if (closed || gestureStart != null) return false
        if (comparingOriginal == active) return false
        comparingOriginal = active
        return true
    }

    // ── Lifecycle ────────────────────────────────────────────────────────────────────────────────────

    /**
     * Exactly one host transaction, even when the session contains many local steps.
     *
     * A session that changed nothing closes without touching the host at all — including the case where
     * the edits cancelled out and the saved block is byte-identical to the one that was opened. An undo
     * entry for an edit with no effect is an entry the user cannot explain.
     */
    fun done(): Boolean {
        if (isReadOnly() || gestureStart != null) return false
        if (working == initial) {
            closed = true
            return true
        }
        val next = ShapeBlockAdapter.save(baseline, working)
        if (next == baseline) {
            closed = true
            return true
        }
        if (!host.commit(COMMIT_LABEL, next)) return false
        commits++
        closed = true
        return true
    }

    /** Cancelling never calls the host and exposes the exact original block for verification. */
    fun cancel(): ShapeBlock {
        comparingOriginal = false
        gestureStart = null
        closed = true
        working = initial
        selection = validated(selection, working)
        undo.clear()
        redo.clear()
        return baseline
    }

    // ── Internals ────────────────────────────────────────────────────────────────────────────────────

    private fun forEachSelectedNode(transform: (ShapePath, ShapeNodeRef) -> ShapePath): Boolean {
        val targets = selection.nodes
        if (targets.isEmpty()) return false
        return editPath { path -> targets.fold(path) { acc, ref -> transform(acc, ref) } }
    }

    private fun replaceWorking(next: ShapeDocument): Boolean {
        if (next == working) return false
        pushBounded(undo, working)
        working = next
        redo.clear()
        selection = validated(selection, working)
        return true
    }

    /**
     * Drops any part of a selection the document no longer contains.
     *
     * Converting a primitive to a path re-mints node ids, and undo can restore a document in which a
     * selected node never existed. Either way the selection has to be re-checked against what is actually
     * there rather than trusted.
     */
    private fun validated(selection: ShapeSelection, document: ShapeDocument): ShapeSelection {
        val layerId = selection.layerId?.takeIf { document.layerById(it) != null }
        val path = layerId?.let { document.layerById(it)?.content?.toPath() }
        val nodes = if (path == null) emptySet() else selection.nodes.filterTo(HashSet()) { ref ->
            path.subpathById(ref.subpathId)?.nodeById(ref.nodeId) != null
        }
        val handle = selection.handle?.takeIf { it.node in nodes }
        return ShapeSelection(layerId, nodes, handle)
    }

    private fun pushBounded(stack: ArrayDeque<ShapeDocument>, value: ShapeDocument) {
        if (historyLimit <= 0) return
        while (stack.size >= historyLimit) stack.removeFirst()
        stack.addLast(value)
    }

    companion object {
        const val DEFAULT_HISTORY_LIMIT = 100

        /** The host history label. One string, so every route records the same transaction name. */
        const val COMMIT_LABEL = "Edit shape"
    }
}
