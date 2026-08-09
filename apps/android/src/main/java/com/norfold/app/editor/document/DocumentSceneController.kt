package com.norfold.app.editor.document

import com.norfold.app.domain.BlockDocument
import com.norfold.app.domain.BlockEditorSession
import com.norfold.app.domain.DocumentBlock
import com.norfold.app.domain.DocumentPageBlock
import com.norfold.app.domain.DocumentSettingsBlock
import com.norfold.app.domain.PageGeometry
import com.norfold.app.domain.SceneGeometry
import com.norfold.app.domain.SceneLock
import com.norfold.app.domain.SceneObject
import com.norfold.app.domain.TodoListBlock
import com.norfold.app.domain.blockIndexOfPage
import com.norfold.app.domain.blockIndexOfSettings
import com.norfold.app.domain.documentSettings
import com.norfold.app.domain.findById
import com.norfold.app.domain.locateObject
import com.norfold.app.domain.pageById
import com.norfold.app.domain.pages
import com.norfold.app.domain.updateBlock
import com.norfold.app.domain.withFreshIds
import com.norfold.app.editor.history.EditOperation
import com.norfold.app.editor.history.BlockDocumentDiff
import com.norfold.app.editor.history.EditTarget
import com.norfold.app.editor.history.EditTransaction
import com.norfold.app.editor.history.EditWorkspace
import com.norfold.app.editor.history.HistoryEngine
import com.norfold.app.editor.history.HistoryHost
import com.norfold.app.editor.history.HistoryListener
import com.norfold.app.editor.history.InMemoryEditWorkspace
import com.norfold.app.editor.history.PersistableEditController
import com.norfold.app.editor.history.TransactionOrigin
import com.norfold.app.editor.recovery.RecoveryReason
import com.norfold.app.editor.recovery.RecoverySnapshot
import com.norfold.app.editor.recovery.RecoverySnapshotManager
import com.norfold.app.editor.recovery.RecoverySnapshotStore
import com.norfold.app.editor.recovery.InMemoryRecoverySnapshotStore
import com.norfold.app.editor.interchange.updateInterchangeFidelity

/**
 * What a page-geometry operation actually did, page by page.
 *
 * A Boolean cannot answer the question Page Control has to ask. "Apply to whole document" over five pages
 * where two are structure-locked changes three; reporting five is a false success, and reporting a bare
 * `true` leaves the surface to re-derive what happened from state that has already moved on. Every requested
 * id lands in exactly one bucket, so the counts always sum back to what was asked for.
 */
data class PageEditOutcome(
    /** Pages whose geometry genuinely changed and are in the committed transaction. */
    val changedIds: List<String> = emptyList(),
    /** Pages refused because their structure is locked. */
    val skippedLockedIds: List<String> = emptyList(),
    /** Pages that were editable but already in the requested state. */
    val unchangedIds: List<String> = emptyList(),
    /** Requested ids that name no page — a stale scope after a delete or an undo. */
    val missingIds: List<String> = emptyList(),
) {
    val changedCount: Int get() = changedIds.size
    val skippedLockedCount: Int get() = skippedLockedIds.size

    /** True when something was actually committed. */
    val didChange: Boolean get() = changedIds.isNotEmpty()

    /** True when nothing changed *because* every candidate was locked — a refusal, not a no-op. */
    val blockedByLock: Boolean get() = changedIds.isEmpty() && skippedLockedIds.isNotEmpty()

    /** The part of a success message that names what was skipped, or null when nothing was. */
    fun skippedNote(): String? = when (skippedLockedIds.size) {
        0 -> null
        1 -> "1 page skipped — its structure is locked"
        else -> "${skippedLockedIds.size} pages skipped — their structure is locked"
    }
}

/**
 * The authoritative edit controller for one bounded document (roadmap Phase 2).
 *
 * **Why this is not a second undo stack.** Pages are top-level [DocumentPageBlock]s and scene objects live
 * inside them, so every scene mutation — moving an object, resizing it, reordering a layer, retitling a
 * page — is a [EditOperation.ReplaceBlock] of the owning page, and page insert/delete/reorder are
 * [EditOperation.InsertBlock] / [EditOperation.RemoveBlock] / [EditOperation.MoveBlock]. The canonical
 * [HistoryEngine] therefore inverts scene edits with the machinery it already has: atomically, across
 * multiple pages, with recorded before/after state and no bespoke inverse logic. A parallel scene history
 * would have had to re-derive all of that and could drift out of order with the block history beside it.
 *
 * **One user action is one transaction.** Multi-object operations collect their operations and commit once,
 * so a five-object nudge undoes as a single step and can never half-apply. Continuous manipulation — a drag,
 * a resize — is deliberately *not* committed per frame: the host previews locally and calls the commit once
 * on release, passing the geometry the gesture started from so the inverse is exact.
 *
 * Pure-JVM: no Compose, no Android, no persistence, no collaboration. The host wires those in, exactly as
 * [com.norfold.app.editor.flow.FlowEditController] does for Flow.
 */
class DocumentSceneController(
    override val target: EditTarget,
    initialDocument: BlockDocument,
    historyListener: HistoryListener? = null,
    recoveryStore: RecoverySnapshotStore = InMemoryRecoverySnapshotStore(),
) : PersistableEditController {
    private var documentInvariant: (BlockDocument) -> BlockDocument = { it }

    fun setDocumentInvariant(transform: (BlockDocument) -> BlockDocument) { documentInvariant = transform }

    /**
     * The workspace this controller edits.
     *
     * Exposed so a [com.norfold.app.editor.recovery.RecoverySnapshotManager] can capture and restore the
     * *same* documents the controller mutates. A snapshot taken over a different workspace would restore
     * something the editor is not looking at, which is worse than no snapshot: it would report success.
     */
    val workspace: EditWorkspace = InMemoryEditWorkspace(mapOf(target to initialDocument.normalized()))

    private val history = HistoryEngine(workspace, historyListener)

    /**
     * Restores commit through the canonical history rather than writing the workspace underneath it — see
     * [commit], which applies this host's page invariant to the restored content on the way through.
     */
    private val recovery = RecoverySnapshotManager(
        workspace = workspace,
        store = recoveryStore,
        historySink = { label, documents ->
            // This controller's workspace holds exactly one target, and a snapshot naming any other is
            // refused by validation before it reaches here, so only that document can be restored. Reading
            // it by key rather than iterating keeps [commit] — which resolves against `target` — honest.
            val restored = documents[target]
            val ops = if (restored == null) emptyList()
            else BlockDocumentDiff.diff(target, document(), restored.normalized())
            commit(label, ops, origin = TransactionOrigin.RECOVERY_RESTORE)
        },
    )

    private var lastSavedDocument: BlockDocument = workspace.documentOf(target)
    private var baselineGeneration: Long = 0L
    private var transactionCounter: Long = 0L

    override fun document(): BlockDocument = workspace.documentOf(target)

    fun pages(): List<DocumentPageBlock> = document().pages()

    fun page(pageId: String): DocumentPageBlock? = document().pageById(pageId)

    fun findObject(objectId: String): Pair<DocumentPageBlock, SceneObject>? =
        document().locateObject(objectId)

    fun canUndo(): Boolean = history.canUndo()
    fun canRedo(): Boolean = history.canRedo()
    fun undoDepth(): Int = history.undoDepth()
    fun history(): List<EditTransaction> = history.history()

    fun snapshotBeforeMigration(label: String): RecoverySnapshot =
        recovery.snapshot(RecoveryReason.MIGRATION, listOf(target), label)

    fun recoverySnapshots(): List<RecoverySnapshot> = recovery.snapshots()

    fun snapshotBeforeDestructiveEdit(label: String): RecoverySnapshot =
        recovery.snapshot(RecoveryReason.DESTRUCTIVE_EDIT, listOf(target), label)

    fun snapshotBeforeImport(label: String): RecoverySnapshot =
        recovery.snapshot(RecoveryReason.IMPORT, listOf(target), label)

    fun snapshotBeforeRawSource(label: String): RecoverySnapshot =
        recovery.snapshot(RecoveryReason.RAW_SOURCE, listOf(target), label)

    fun undo(): Boolean = history.undo() != null
    fun redo(): Boolean = history.redo() != null

    /** Read-only undone history; the **last** element is the next redo (§13.6 projection source). */
    fun redoHistory(): List<EditTransaction> = history.redoHistory()

    /** The seam the Phase-10 History UI reads and moves this artifact's one authoritative history through. */
    fun historyHost(): HistoryHost = HistoryHost.of(history)

    /** The recovery manager for this artifact, for named restore points and the recovery browser (§13.7). */
    fun recovery(): RecoverySnapshotManager = recovery

    /** Creates a user-named restore point over this artifact. Null when [name] is blank. */
    fun createNamedRestorePoint(
        name: String,
        description: String = "",
        scopeBlockIds: List<String> = emptyList(),
    ): RecoverySnapshot? = recovery.createNamedSnapshot(name, description, listOf(target), scopeBlockIds)

    // --- Scene object mutation -----------------------------------------------------------------------

    /**
     * Replaces [objectId] with the result of [edit], as one transaction labelled [label].
     *
     * Returns false when the object no longer exists, which is the ordinary outcome of acting on a stale
     * selection after an undo or a remote merge — a failed lookup must not throw at the host.
     */
    fun editObject(objectId: String, label: String, edit: (SceneObject) -> SceneObject): Boolean =
        editObjects(listOf(objectId), label) { _, obj -> edit(obj) }

    /**
     * Applies [edit] to every id in [objectIds] as **one atomic transaction**.
     *
     * Objects on different pages produce one operation per page, all committed together, so a selection
     * spanning pages undoes in a single step rather than page by page. [edit] returning the object
     * unchanged contributes nothing, so a partially-applicable command commits only what actually moved.
     */
    fun editObjects(
        objectIds: Collection<String>,
        label: String,
        edit: (DocumentPageBlock, SceneObject) -> SceneObject,
    ): Boolean {
        val document = document()
        // Group by page so each page is replaced exactly once even when several of its objects change;
        // two ReplaceBlock ops on one page would make the second one's recorded `before` already stale.
        val byPage = LinkedHashMap<String, MutableList<SceneObject>>()
        for (id in objectIds) {
            val (page, obj) = document.locateObject(id) ?: continue
            byPage.getOrPut(page.id) { mutableListOf() } += obj
        }
        if (byPage.isEmpty()) return false

        val ops = mutableListOf<EditOperation>()
        for ((pageId, objects) in byPage) {
            val page = document.pageById(pageId) ?: continue
            var next = page
            for (obj in objects) {
                val edited = edit(page, obj).normalized()
                if (edited != obj) next = next.withObject(edited)
            }
            if (next != page) ops += replacePage(document, page, next)
        }
        return commit(label, ops)
    }

    /** Moves objects by a delta in page points. Objects whose lock forbids movement are skipped. */
    fun moveObjects(objectIds: Collection<String>, dx: Float, dy: Float): Boolean =
        editObjects(objectIds, if (objectIds.size > 1) "Move objects" else "Move") { _, obj ->
            if (!obj.canMove) obj else obj.copy(geometry = obj.geometry.translatedBy(dx, dy))
        }

    /**
     * Commits the exact geometry a completed drag/resize/rotate produced.
     *
     * The host previews the manipulation locally and calls this once on release, so history records one
     * transaction rather than one per frame — and a cancelled gesture simply never calls it, which is why
     * cancelling restores the pre-manipulation state without needing an undo.
     */
    fun commitGeometry(objectId: String, geometry: SceneGeometry, label: String = "Move"): Boolean =
        editObject(objectId, label) { obj ->
            val locked = obj.geometry
            // Honour each lock axis independently: a position-locked object may still be resized.
            val next = geometry.normalized()
            obj.copy(
                geometry = next.copy(
                    x = if (obj.canMove) next.x else locked.x,
                    y = if (obj.canMove) next.y else locked.y,
                    width = if (obj.canResize) next.width else locked.width,
                    height = if (obj.canResize) next.height else locked.height,
                    rotation = if (obj.canRotate) next.rotation else locked.rotation,
                ),
            )
        }

    /** Commits several objects' geometry as one transaction — the multi-object drag case. */
    fun commitGeometries(geometries: Map<String, SceneGeometry>, label: String = "Move objects"): Boolean =
        editObjects(geometries.keys, label) { _, obj ->
            val next = geometries[obj.id]?.normalized() ?: return@editObjects obj
            obj.copy(
                geometry = next.copy(
                    x = if (obj.canMove) next.x else obj.geometry.x,
                    y = if (obj.canMove) next.y else obj.geometry.y,
                    width = if (obj.canResize) next.width else obj.geometry.width,
                    height = if (obj.canResize) next.height else obj.geometry.height,
                    rotation = if (obj.canRotate) next.rotation else obj.geometry.rotation,
                ),
            )
        }

    fun setLock(objectIds: Collection<String>, lock: SceneLock): Boolean =
        editObjects(objectIds, if (lock == SceneLock.None) "Unlock" else "Lock") { _, obj ->
            obj.copy(lock = lock)
        }

    /** Replaces an object's content. Refused for content-locked objects. */
    fun setContent(objectId: String, content: DocumentBlock, label: String = "Edit content"): Boolean =
        editObject(objectId, label) { obj -> if (obj.canEditContent) obj.copy(content = content) else obj }

    /**
     * Applies a text edit inside an object's content, as a **coalescing** transaction.
     *
     * Typing is the one thing that must not be one undo step per keystroke, so this is the only path in this
     * controller that passes a coalescing key. The key names the *editable field* — object plus list item —
     * not the object, because two items of one list are separate writing runs and merging them would make a
     * single undo swallow edits in both.
     *
     * The edit itself goes through [BlockEditorSession], the same vocabulary the Flow editor uses, so inline
     * styling around the changed range survives rather than being flattened by a plain-string replacement.
     * A to-do item holds `TodoItem` rather than `ListItem`, so the item type decides which call addresses it;
     * using the list call on a checklist would report a cursor while changing nothing.
     */
    /**
     * Applies a text edit inside an object's content, as a **coalescing** transaction.
     *
     * Typing is the one thing that must not be one undo step per keystroke, so this is the only path in this
     * controller that passes a coalescing key. The key names the *editable field* — the addressed block plus
     * list item — not the object, because two blocks of one object are separate writing runs and merging them
     * would make a single undo swallow edits in both.
     *
     * [blockId] addresses the block **actually** being edited, which is not always `obj.content`: a quote, a
     * callout or a container holds children, and each of those children is independently editable. Addressing
     * the object's root block instead would send every child's keystrokes to the first paragraph — editing the
     * second paragraph of a quote would rewrite the first. [BlockEditorSession] resolves ids through the whole
     * nested tree, so a child id works exactly as a root id does.
     *
     * Fails closed on an id that is not in this object's content. A stale block id is what a race between a
     * recomposition and an undo produces, and the alternative to refusing is writing the user's keystrokes
     * into whatever block the fallback happened to pick.
     *
     * The edit itself goes through [BlockEditorSession], the same vocabulary the Flow editor uses, so inline
     * styling around the changed range survives rather than being flattened by a plain-string replacement.
     * A to-do item holds `TodoItem` rather than `ListItem`, so the item type decides which call addresses it;
     * using the list call on a checklist would report a cursor while changing nothing.
     */
    fun editObjectText(
        objectId: String,
        blockId: String,
        itemId: String?,
        before: String,
        after: String,
        afterTransform: (BlockDocument) -> BlockDocument = { it },
    ): Boolean {
        if (before == after) return false
        val document = document()
        val (page, obj) = document.locateObject(objectId) ?: return false
        if (!obj.canEditContent) return false

        val session = BlockEditorSession(BlockDocument(listOf(obj.content)))
        // Fail closed rather than falling back to the root block: an unresolvable address means the caller and
        // the model disagree about what is on screen, and guessing a target writes into the wrong content.
        val addressed = session.document.findById(blockId) ?: return false
        when {
            itemId == null -> session.editText(blockId, before, after)
            addressed is TodoListBlock -> session.editTodoItem(blockId, itemId, before, after)
            else -> session.editListItem(blockId, itemId, before, after)
        }
        val edited = session.document.blocks.firstOrNull() ?: return false
        if (edited == obj.content) return false

        val next = page.withObject(obj.copy(content = edited))
        val contentDocument = document.copy(blocks = document.blocks.map { if (it.id == page.id) next else it })
        val transformed = documentInvariant(afterTransform(contentDocument).normalized()).normalized()
        val key = if (itemId != null) "block:$blockId/item:$itemId" else "block:$blockId"
        return commit("Typing", BlockDocumentDiff.diff(target, document, transformed), coalescingKey = key)
    }

    /**
     * Toggles a to-do item inside an object's content. Never coalesces: a tick is a discrete action.
     *
     * [blockId] addresses the to-do list itself, which may be nested inside a container, quote or callout —
     * casting the object's root content would silently do nothing for every nested checklist.
     */
    fun toggleObjectTodo(objectId: String, blockId: String, itemId: String): Boolean {
        val document = document()
        val (page, obj) = document.locateObject(objectId) ?: return false
        if (!obj.canEditContent) return false
        val list = listOf(obj.content).findById(blockId) as? TodoListBlock ?: return false
        val index = list.items.indexOfFirst { it.id == itemId }
        if (index < 0) return false
        val items = list.items.toMutableList()
        items[index] = items[index].copy(checked = !items[index].checked)
        val content = listOf(obj.content).updateBlock(blockId) { list.copy(items = items) }.firstOrNull()
            ?: return false
        val next = page.withObject(obj.copy(content = content))
        return commit("Toggle to-do", listOf(replacePage(document, page, next)))
    }

    // --- Layer ordering ------------------------------------------------------------------------------

    /**
     * Reorders [objectId] within its page's z-stack.
     *
     * Ordering is expressed as a list permutation and renormalized by [DocumentPageBlock.normalized], so
     * z stays contiguous from 0 and two objects can never claim the same layer — the condition that makes
     * overlap hit-testing non-deterministic.
     */
    fun reorderObject(objectId: String, move: LayerMove): Boolean {
        val document = document()
        val (page, _) = document.locateObject(objectId) ?: return false
        val order = page.objects.map { it.id }.toMutableList()
        val index = order.indexOf(objectId)
        if (index < 0) return false
        val destination = when (move) {
            LayerMove.FORWARD -> index + 1
            LayerMove.BACKWARD -> index - 1
            LayerMove.TO_FRONT -> order.lastIndex
            LayerMove.TO_BACK -> 0
        }
        if (destination == index || destination !in order.indices) return false
        order.removeAt(index)
        order.add(destination, objectId)
        val reordered = page.copy(
            objects = order.mapIndexedNotNull { z, id -> page.objectById(id)?.copy(z = z) },
        ).normalized()
        return commit(move.label, listOf(replacePage(document, page, reordered)))
    }

    /**
     * Reorders every object in [objectIds] by [move], as **one** transaction.
     *
     * Not a loop over [reorderObject]: that would commit once per object, so a four-object "bring to front"
     * would need four Undos to reverse one thing the user did. Objects are grouped by their page and each
     * page's ordering is recomputed once, so a selection spanning two pages is still a single transaction.
     *
     * The selection's own relative order is preserved. For the one-step moves that means a selected object
     * never leapfrogs another selected object — the block moves together and stops as a block when it reaches
     * the end, instead of the group inverting itself as each member passes the last.
     */
    fun reorderObjects(objectIds: Collection<String>, move: LayerMove): Boolean {
        val document = document()
        val byPage = LinkedHashMap<String, MutableList<String>>()
        for (id in objectIds) {
            val (page, _) = document.locateObject(id) ?: continue
            byPage.getOrPut(page.id) { mutableListOf() } += id
        }
        if (byPage.isEmpty()) return false
        val ops = mutableListOf<EditOperation>()
        for ((pageId, ids) in byPage) {
            val page = document.pageById(pageId) ?: continue
            val order = page.objects.map { it.id }.toMutableList()
            val selected = ids.toSet()
            when (move) {
                // Absolute moves: lift the selection out and re-insert it at the end it is heading for,
                // keeping the order the objects already had relative to each other.
                LayerMove.TO_FRONT -> {
                    val moving = order.filter { it in selected }
                    order.removeAll(selected)
                    order.addAll(moving)
                }
                LayerMove.TO_BACK -> {
                    val moving = order.filter { it in selected }
                    order.removeAll(selected)
                    order.addAll(0, moving)
                }
                // One-step moves: walk from the edge being moved toward, so each object's destination has
                // already been vacated. A neighbour that is itself selected blocks the swap, which is what
                // keeps the group together rather than letting its members trade places.
                LayerMove.FORWARD -> for (i in order.indices.reversed()) {
                    if (order[i] !in selected) continue
                    val above = i + 1
                    if (above > order.lastIndex || order[above] in selected) continue
                    order[i] = order[above].also { order[above] = order[i] }
                }
                LayerMove.BACKWARD -> for (i in order.indices) {
                    if (order[i] !in selected) continue
                    val below = i - 1
                    if (below < 0 || order[below] in selected) continue
                    order[i] = order[below].also { order[below] = order[i] }
                }
            }
            val reordered = page.copy(
                objects = order.mapIndexedNotNull { z, id -> page.objectById(id)?.copy(z = z) },
            ).normalized()
            if (reordered != page) ops += replacePage(document, page, reordered)
        }
        return commit(move.label, ops)
    }

    // --- Object lifecycle ----------------------------------------------------------------------------

    /**
     * Adds [obj] to [pageId] at an explicit page-relative position.
     *
     * Free placement is a distinct entry point from structural insertion on purpose: an API that took both
     * "put this after that block" and "put this at x,y" through one ambiguous `insert` would have to guess
     * which the caller meant, and guessing wrong silently misplaces content.
     */
    fun addObject(pageId: String, obj: SceneObject, label: String = "Insert object"): Boolean {
        val document = document()
        val page = document.pageById(pageId) ?: return false
        if (page.objectById(obj.id) != null) return false
        // A new object always goes to the front; anything else buries it behind whatever is already there.
        val placed = obj.normalized().copy(z = page.objects.size)
        return commit(label, listOf(replacePage(document, page, page.withObject(placed))))
    }

    /** Duplicates [objectId] with a fresh identity, offset so the copy is visibly distinct from its source. */
    fun duplicateObject(objectId: String, offset: Float = DUPLICATE_OFFSET): String? {
        val document = document()
        val (page, obj) = document.locateObject(objectId) ?: return null
        val copy = obj.copy(
            id = com.norfold.app.domain.sceneId("obj"),
            // The content keeps its own identity rules: duplicating must not produce two blocks sharing an id.
            content = obj.content.withFreshIds(),
            geometry = obj.geometry.translatedBy(offset, offset),
            z = page.objects.size,
        ).normalized()
        val ok = commit("Duplicate", listOf(replacePage(document, page, page.withObject(copy))))
        return if (ok) copy.id else null
    }

    /**
     * Duplicates every object in [objectIds] as **one** transaction, returning the new ids in source order.
     *
     * Not a loop over [duplicateObject]: that commits per object, so duplicating a three-object selection
     * would take three Undos to reverse. Copies are accumulated into each owning page before a single commit,
     * which also means the z values they receive account for the other copies rather than every copy claiming
     * the same layer.
     */
    fun duplicateObjects(
        objectIds: Collection<String>,
        offset: Float = DUPLICATE_OFFSET,
    ): List<String> {
        val document = document()
        val byPage = LinkedHashMap<String, MutableList<String>>()
        for (id in objectIds) {
            val (page, _) = document.locateObject(id) ?: continue
            byPage.getOrPut(page.id) { mutableListOf() } += id
        }
        if (byPage.isEmpty()) return emptyList()
        val ops = mutableListOf<EditOperation>()
        val created = mutableListOf<String>()
        for ((pageId, ids) in byPage) {
            val page = document.pageById(pageId) ?: continue
            var next = page
            for (id in ids) {
                val obj = page.objectById(id) ?: continue
                val copy = obj.copy(
                    id = com.norfold.app.domain.sceneId("obj"),
                    // Content keeps its own identity rules: a duplicate must not share block ids with its source.
                    content = obj.content.withFreshIds(),
                    geometry = obj.geometry.translatedBy(offset, offset),
                    z = next.objects.size,
                ).normalized()
                next = next.withObject(copy)
                created += copy.id
            }
            if (next != page) ops += replacePage(document, page, next)
        }
        if (ops.isEmpty()) return emptyList()
        val label = if (created.size > 1) "Duplicate objects" else "Duplicate"
        return if (commit(label, ops)) created else emptyList()
    }

    /** Deletes objects as one transaction. Deleting objects never deletes their page. */
    fun deleteObjects(objectIds: Collection<String>): Boolean {
        val document = document()
        val byPage = LinkedHashMap<String, MutableList<String>>()
        for (id in objectIds) {
            val (page, _) = document.locateObject(id) ?: continue
            byPage.getOrPut(page.id) { mutableListOf() } += id
        }
        if (byPage.isEmpty()) return false
        val ops = byPage.mapNotNull { (pageId, ids) ->
            val page = document.pageById(pageId) ?: return@mapNotNull null
            val next = ids.fold(page) { acc, id -> acc.withoutObject(id) }
            if (next == page) null else replacePage(document, page, next)
        }
        return commit(if (objectIds.size > 1) "Delete objects" else "Delete", ops)
    }

    /**
     * Moves [objectId] to [targetPageId], keeping its geometry relative to the new page.
     *
     * Two pages change, so this is a single transaction containing two operations — the case that would
     * leave an object either duplicated or lost if the two halves could be undone independently.
     */
    fun moveObjectToPage(objectId: String, targetPageId: String): Boolean {
        val document = document()
        val (source, obj) = document.locateObject(objectId) ?: return false
        if (source.id == targetPageId) return false
        val destination = document.pageById(targetPageId) ?: return false
        val landed = obj.copy(z = destination.objects.size).normalized()
        return commit(
            "Move to page",
            listOf(
                replacePage(document, source, source.withoutObject(objectId)),
                replacePage(document, destination, destination.withObject(landed)),
            ),
        )
    }

    // --- Page lifecycle ------------------------------------------------------------------------------

    /** Inserts [page] before or after [relativeToPageId]; at the end when that id is unknown. */
    fun insertPage(page: DocumentPageBlock, relativeToPageId: String?, after: Boolean): Boolean {
        val document = document()
        val anchor = relativeToPageId?.let { document.blockIndexOfPage(it) } ?: -1
        val index = when {
            anchor < 0 -> document.blocks.size
            after -> anchor + 1
            else -> anchor
        }
        return commit(
            "Insert page",
            listOf(EditOperation.InsertBlock(target, index, page.normalized())),
        )
    }

    fun duplicatePage(pageId: String): String? {
        val document = document()
        val page = document.pageById(pageId) ?: return null
        val index = document.blockIndexOfPage(pageId)
        val copy = page.withFreshIds() as? DocumentPageBlock ?: return null
        val ok = commit("Duplicate page", listOf(EditOperation.InsertBlock(target, index + 1, copy)))
        return if (ok) copy.id else null
    }

    /**
     * Deletes a page **and everything on it**, as one transaction.
     *
     * Refused for the last remaining page: a bounded document with no page has nothing to render, no place
     * to put new content, and no way back except undo.
     */
    fun deletePage(pageId: String): Boolean {
        val document = document()
        if (document.pages().size <= 1) return false
        val page = document.pageById(pageId) ?: return false
        val index = document.blockIndexOfPage(pageId)
        return commit("Delete page", listOf(EditOperation.RemoveBlock(target, index, page)))
    }

    /** Reorders a page within the document. Objects travel with their page, because they are inside it. */
    fun movePage(pageId: String, toPageIndex: Int): Boolean {
        val document = document()
        val pages = document.pages()
        val from = document.blockIndexOfPage(pageId)
        if (from < 0) return false
        val clamped = toPageIndex.coerceIn(0, pages.size - 1)
        // The caller thinks in page ordinals; History operates on top-level block indices, and a document
        // may hold preserved non-page blocks between pages. Translate rather than assume they coincide.
        val to = document.blockIndexOfPage(pages[clamped].id)
        if (to < 0 || to == from) return false
        return commit("Reorder pages", listOf(EditOperation.MoveBlock(target, from, to)))
    }

    // --- Page geometry -------------------------------------------------------------------------------

    /**
     * Applies [change] to the geometry of every page in [pageIds], as one transaction.
     *
     * Refused for a page whose structure is locked unless [force] — this is where "page structure locked;
     * content editable" is actually enforced, rather than being a convention the UI is trusted to honour.
     *
     * Returns a [PageEditOutcome] rather than a bare Boolean because "did anything happen" and "what happened
     * to the five pages you named" are different questions, and Page Control has to answer the second one.
     * Reporting the requested count as the affected count is how a scope containing two locked pages came to
     * claim it had changed all five.
     */
    fun editPageGeometry(
        pageIds: Collection<String>,
        label: String,
        force: Boolean = false,
        change: (PageGeometry) -> PageGeometry,
    ): PageEditOutcome {
        val document = document()
        val changed = mutableListOf<String>()
        val skippedLocked = mutableListOf<String>()
        val unchanged = mutableListOf<String>()
        val missing = mutableListOf<String>()
        val ops = mutableListOf<EditOperation>()
        for (pageId in pageIds) {
            val page = document.pageById(pageId)
            if (page == null) {
                missing += pageId
                continue
            }
            if (page.geometry.structureLocked && !force) {
                skippedLocked += pageId
                continue
            }
            val next = page.copy(geometry = change(page.geometry).normalized()).normalized()
            if (next == page) {
                unchanged += pageId
                continue
            }
            changed += pageId
            ops += replacePage(document, page, next)
        }
        // A transaction is committed only when something actually changed; committing an empty operation list
        // would put an entry in history that undoes to the state it already was in.
        val committed = ops.isNotEmpty() && commit(label, ops)
        return PageEditOutcome(
            changedIds = if (committed) changed else emptyList(),
            skippedLockedIds = skippedLocked,
            unchangedIds = if (committed) unchanged else unchanged + changed,
            missingIds = missing,
        )
    }

    /** Locks or unlocks page structure. Always permitted — it is the control that governs the others. */
    fun setPageStructureLocked(pageIds: Collection<String>, locked: Boolean): PageEditOutcome =
        editPageGeometry(pageIds, if (locked) "Lock page structure" else "Unlock page structure", force = true) {
            it.copy(structureLocked = locked)
        }

    // --- Document settings ---------------------------------------------------------------------------

    /**
     * The explicit template new pages inherit, or null while they inherit from the page they follow.
     *
     * Read from the document rather than from a field on this controller, so it survives the controller being
     * rebuilt — which is what a session recreation does. A controller-local copy would look identical for the
     * length of one session and then silently be gone.
     */
    fun newPageTemplate(): PageGeometry? = document().documentSettings()?.newPageTemplate

    /**
     * Sets the explicit new-page template, as one transaction.
     *
     * Committed through History like any other change because it *is* a durable change to the document: it
     * outlives the session, so it must be undoable and it must be saved. The settings block is created on
     * first use rather than existing in every document, so a document nobody has set a default on carries no
     * settings block at all and round-trips exactly as before.
     */
    fun setNewPageTemplate(template: PageGeometry?, label: String = "New page default"): Boolean {
        val document = document()
        val existing = document.documentSettings()
        val normalized = template?.normalized()
        if (existing?.newPageTemplate == normalized) return false
        val op = if (existing == null) {
            if (normalized == null) return false
            // Appended last: the settings block is not content, and putting it first would make it the block
            // every "first block" fallback in the app lands on.
            EditOperation.InsertBlock(
                target,
                document.blocks.size,
                DocumentSettingsBlock(newPageTemplate = normalized),
            )
        } else {
            EditOperation.ReplaceBlock(
                target = target,
                index = document.blockIndexOfSettings(),
                before = existing,
                after = existing.copy(newPageTemplate = normalized).normalized(),
            )
        }
        return commit(label, listOf(op))
    }

    // --- Persistence hand-off (mirrors FlowEditController) --------------------------------------------

    override fun dirtyBlockIds(): Set<String> {
        val baseline = lastSavedDocument.blocks.associateBy { it.id }
        return document().blocks.mapNotNullTo(linkedSetOf()) { block ->
            block.id.takeIf { baseline[block.id] != block }
        }
    }

    override fun deletedBlockIds(): Set<String> {
        val current = document().blocks.mapTo(HashSet()) { it.id }
        return lastSavedDocument.blocks.mapNotNullTo(linkedSetOf()) { it.id.takeIf { id -> id !in current } }
    }

    override fun hasUnsavedChanges(): Boolean = document() != lastSavedDocument

    override fun baselineGeneration(): Long = baselineGeneration

    fun markSaved() = setBaseline(document())

    fun markSaved(persisted: BlockDocument) = setBaseline(persisted)

    /** See [com.norfold.app.editor.flow.FlowEditController.markSavedIfCurrent] — the same stale-write guard. */
    override fun markSavedIfCurrent(persisted: BlockDocument, expectedGeneration: Long): Boolean {
        if (expectedGeneration != baselineGeneration) return false
        setBaseline(persisted)
        return true
    }

    private fun setBaseline(next: BlockDocument) {
        lastSavedDocument = next
        baselineGeneration++
    }

    /**
     * Replaces the whole document — used by migration, which produces a new artifact rather than a diff.
     *
     * Committed through history with [TransactionOrigin.MIGRATION] so it is undoable like anything else and
     * is identifiable in a future history UI.
     */
    fun replaceDocument(next: BlockDocument, label: String): Boolean {
        val before = document()
        val normalized = next.normalized()
        if (normalized == before) return false
        val ops = buildList {
            before.blocks.asReversed().forEachIndexed { offset, block ->
                add(EditOperation.RemoveBlock(target, before.blocks.lastIndex - offset, block))
            }
            normalized.blocks.forEachIndexed { index, block ->
                add(EditOperation.InsertBlock(target, index, block))
            }
        }
        return commit(label, ops, TransactionOrigin.MIGRATION)
    }

    /**
     * Runs a block mutation as one ordinary user transaction.
     *
     * Document styling changes block content, not scene geometry. This seam gives shared editor features
     * the same BlockEditorSession vocabulary Flow uses while still committing through this controller's
     * canonical History Engine. A no-op creates no undo entry.
     */
    fun mutateDocument(label: String, mutation: (BlockEditorSession) -> Unit): Boolean {
        val before = document()
        val session = BlockEditorSession(before)
        mutation(session)
        val after = session.document
        return commit(label, BlockDocumentDiff.diff(target, before, after))
    }

    /** Whole-artifact transform used by durable editor metadata and guarded review restoration. */
    fun transformDocument(label: String, transform: (BlockDocument) -> BlockDocument): Boolean {
        val before = document()
        val after = documentInvariant(transform(before).normalized()).normalized()
        return commit(label, BlockDocumentDiff.diff(target, before, after))
    }

    // --- Internals -----------------------------------------------------------------------------------

    /** The ReplaceBlock that swaps [page] for [next], resolving the page's top-level index. */
    private fun replacePage(
        document: BlockDocument,
        page: DocumentPageBlock,
        next: DocumentPageBlock,
    ): EditOperation = EditOperation.ReplaceBlock(
        target = target,
        index = document.blockIndexOfPage(page.id),
        before = page,
        after = next,
    )

    /**
     * Commits [ops] as one transaction, or does nothing when there is nothing to do.
     *
     * `coalescingKey` is deliberately null for every scene operation: two consecutive nudges are two things
     * the user did and must undo separately. Typing inside an object's content coalesces through the text
     * path that owns it, not here.
     */
    private fun commit(
        label: String,
        ops: List<EditOperation>,
        origin: TransactionOrigin = TransactionOrigin.LOCAL_USER,
        coalescingKey: String? = null,
    ): Boolean {
        if (ops.isEmpty()) return false
        val before = document()
        val afterOperations = ops.fold(before) { current, operation -> operation.applyTo(current) }
        val after = documentInvariant(afterOperations.normalized()).normalized().updateInterchangeFidelity(before)
        val canonicalOps = BlockDocumentDiff.diff(target, before, after)
        if (canonicalOps.isEmpty()) return false
        history.commit(
            EditTransaction(
                id = "doc-${transactionCounter++}",
                label = label,
                operations = canonicalOps,
                origin = origin,
                coalescingKey = coalescingKey,
            ),
        )
        return true
    }

    companion object {
        /** Offset applied to a duplicate so it is visibly a separate object rather than hidden behind its source. */
        const val DUPLICATE_OFFSET = 18f
    }
}

/** Layer reorder intents. */
enum class LayerMove(val label: String) {
    FORWARD("Bring forward"),
    BACKWARD("Send backward"),
    TO_FRONT("Bring to front"),
    TO_BACK("Send to back"),
}
