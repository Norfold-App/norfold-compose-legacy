package com.norfold.app.editor.document

import com.norfold.app.domain.BlockDocument
import com.norfold.app.domain.DocumentBlock
import com.norfold.app.domain.DocumentPageBlock
import com.norfold.app.domain.PageGeometry
import com.norfold.app.domain.SceneGeometry
import com.norfold.app.domain.SceneLock
import com.norfold.app.domain.SceneObject
import com.norfold.app.domain.blockIndexOfPage
import com.norfold.app.domain.locateObject
import com.norfold.app.domain.pageById
import com.norfold.app.domain.pages
import com.norfold.app.domain.withFreshIds
import com.norfold.app.editor.history.EditOperation
import com.norfold.app.editor.history.EditTarget
import com.norfold.app.editor.history.EditTransaction
import com.norfold.app.editor.history.EditWorkspace
import com.norfold.app.editor.history.HistoryEngine
import com.norfold.app.editor.history.HistoryListener
import com.norfold.app.editor.history.InMemoryEditWorkspace
import com.norfold.app.editor.history.PersistableEditController
import com.norfold.app.editor.history.TransactionOrigin

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
) : PersistableEditController {
    /**
     * The workspace this controller edits.
     *
     * Exposed so a [com.norfold.app.editor.recovery.RecoverySnapshotManager] can capture and restore the
     * *same* documents the controller mutates. A snapshot taken over a different workspace would restore
     * something the editor is not looking at, which is worse than no snapshot: it would report success.
     */
    val workspace: EditWorkspace = InMemoryEditWorkspace(mapOf(target to initialDocument.normalized()))

    private val history = HistoryEngine(workspace, historyListener)

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

    fun undo(): Boolean = history.undo() != null
    fun redo(): Boolean = history.redo() != null

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
     */
    fun editPageGeometry(
        pageIds: Collection<String>,
        label: String,
        force: Boolean = false,
        change: (PageGeometry) -> PageGeometry,
    ): Boolean {
        val document = document()
        val ops = pageIds.mapNotNull { pageId ->
            val page = document.pageById(pageId) ?: return@mapNotNull null
            if (page.geometry.structureLocked && !force) return@mapNotNull null
            val next = page.copy(geometry = change(page.geometry).normalized()).normalized()
            if (next == page) null else replacePage(document, page, next)
        }
        return commit(label, ops)
    }

    /** Locks or unlocks page structure. Always permitted — it is the control that governs the others. */
    fun setPageStructureLocked(pageIds: Collection<String>, locked: Boolean): Boolean =
        editPageGeometry(pageIds, if (locked) "Lock page structure" else "Unlock page structure", force = true) {
            it.copy(structureLocked = locked)
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
    ): Boolean {
        if (ops.isEmpty()) return false
        history.commit(
            EditTransaction(
                id = "doc-${transactionCounter++}",
                label = label,
                operations = ops,
                origin = origin,
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
