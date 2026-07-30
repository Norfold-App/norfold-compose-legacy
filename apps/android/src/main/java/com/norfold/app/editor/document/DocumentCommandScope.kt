package com.norfold.app.editor.document

import com.norfold.app.domain.BlockDocument
import com.norfold.app.domain.DocumentPageBlock
import com.norfold.app.domain.SceneObject
import com.norfold.app.domain.pages
import com.norfold.app.editor.command.Capability
import com.norfold.app.editor.command.CommandContext
import com.norfold.app.editor.command.CommandSurface
import com.norfold.app.editor.selection.PostCommandOutcome
import com.norfold.app.editor.selection.SelectionDomain
import com.norfold.app.editor.selection.SelectionEngine
import com.norfold.app.editor.selection.SelectionGranularity
import com.norfold.app.editor.selection.SelectionTarget

/**
 * The live environment a document command resolves and executes against (roadmap Phase 2).
 *
 * The mirror of [com.norfold.app.editor.flow.FlowCommandScope], for the same reason: one object binds the
 * controller, the shared [SelectionEngine] and the capability model, so a command reached from a touch
 * handle, a keyboard shortcut, the Page Control surface or the accessibility tree carries no host state of
 * its own and cannot drift between routes.
 *
 * Selection lives in the shared engine rather than a private list, so §4.9 primary/secondary and §4.14
 * post-command selection are the engine's rules, not this host's re-implementation of them.
 *
 * Pure-JVM. Not thread-safe; the editor loop serializes access.
 */
class DocumentCommandScope(
    val controller: DocumentSceneController,
    val selection: SelectionEngine = SelectionEngine(),
) {

    /**
     * The page new content lands on and page-scoped commands act on when nothing is selected.
     *
     * Explicit rather than inferred from the scroll position: "the page you can mostly see" is ambiguous
     * exactly when two pages are half-visible, and inserting onto the wrong page is a silent misplacement
     * the user only notices later.
     */
    var activePageId: String? = null

    /** Pages the user has explicitly selected — the "Selected pages" Page Control scope. */
    var selectedPageIds: List<String> = emptyList()

    fun document(): BlockDocument = controller.document()

    fun pages(): List<DocumentPageBlock> = document().pages()

    /** The page commands default to: the explicit active page, else the first page. */
    fun activePage(): DocumentPageBlock? =
        activePageId?.let { controller.page(it) } ?: pages().firstOrNull()

    // --- Selection projection -----------------------------------------------------------------------

    /** Ids of the selected scene objects, primary first. Pages are a separate granularity. */
    fun selectedObjectIds(): List<String> = selection.selection()
        .filter { it.granularity == SelectionGranularity.DOC_OBJECT }
        .map { it.id }

    fun selectedObjects(): List<SceneObject> =
        selectedObjectIds().mapNotNull { controller.findObject(it)?.second }

    fun primaryObjectId(): String? =
        selection.primary()?.takeIf { it.granularity == SelectionGranularity.DOC_OBJECT }?.id

    fun primaryObject(): SceneObject? = primaryObjectId()?.let { controller.findObject(it)?.second }

    /** Replaces the object selection with [ids] (first becomes primary). */
    fun selectObjects(ids: List<String>) {
        selection.clear()
        ids.forEachIndexed { index, id ->
            val obj = controller.findObject(id)?.second
            val target = obj?.let { DocumentCapabilities.targetOf(it) }
                ?: SelectionTarget(id, granularityFor(ids.size))
            val sized = if (ids.size > 1) target.copy(granularity = SelectionGranularity.DOC_MULTIPLE_OBJECTS) else target
            if (index == 0) selection.select(sized) else selection.addToSelection(sized)
        }
    }

    /** Toggles [id] in/out of the selection — modifier-click and multi-select on phone. */
    fun toggleObject(id: String) {
        val obj = controller.findObject(id)?.second ?: return
        selection.toggle(DocumentCapabilities.targetOf(obj))
    }

    fun selectPage(pageId: String) {
        val page = controller.page(pageId) ?: return
        selection.select(DocumentCapabilities.targetOfPage(page))
        activePageId = pageId
    }

    /**
     * Deep-selects at a page point: the front-most object under the point, or — when [current] is already
     * one of the objects there — the next one *behind* it.
     *
     * Cycling on repeat tap is what makes an object underneath a larger one reachable at all. Without it the
     * front object captures every tap and the one beneath can only be selected by moving the top one away.
     */
    fun hitSelect(pageId: String, px: Float, py: Float, current: String? = null): String? {
        val page = controller.page(pageId) ?: return null
        val stack = page.hitTest(px, py)
        if (stack.isEmpty()) return null
        val index = stack.indexOfFirst { it.id == current }
        val next = if (index < 0) stack.first() else stack[(index + 1) % stack.size]
        selectObjects(listOf(next.id))
        activePageId = pageId
        return next.id
    }

    /** The full front-to-back stack at a point, for a disambiguation menu when objects overlap. */
    fun hitStack(pageId: String, px: Float, py: Float): List<SceneObject> =
        controller.page(pageId)?.hitTest(px, py).orEmpty()

    fun applyOutcome(outcome: PostCommandOutcome): List<SelectionTarget> = selection.selectionAfter(outcome)

    fun selectResult(ids: List<String>): PostCommandOutcome =
        PostCommandOutcome.SelectResult(ids.map { id ->
            controller.findObject(id)?.second?.let { DocumentCapabilities.targetOf(it) }
                ?: SelectionTarget(id, granularityFor(ids.size))
        })

    /**
     * Post-delete selection (§4.14): the nearest surviving object on the same page, preferring the one after
     * the deleted object in z-order, else the one before it. Returning a target that no longer exists is
     * what leaves a selection highlight pointing at nothing.
     */
    fun nearestAfterDelete(before: BlockDocument, removedId: String): PostCommandOutcome {
        val page = before.pages().firstOrNull { it.objectById(removedId) != null }
        val order = page?.objects?.map { it.id }.orEmpty()
        val index = order.indexOf(removedId)
        val live = document()
        val nearest = when {
            index < 0 -> null
            else -> (order.drop(index + 1) + order.take(index).asReversed())
                .firstOrNull { live.locateObjectExists(it) }
        }
        return PostCommandOutcome.SelectNearestSibling(
            nearest?.let { id ->
                controller.findObject(id)?.second?.let { DocumentCapabilities.targetOf(it) }
            },
        )
    }

    // --- Capability projection ----------------------------------------------------------------------

    fun capabilitiesOf(targetId: String): Set<Capability> {
        controller.findObject(targetId)?.let { (_, obj) -> return DocumentCapabilities.of(obj) }
        controller.page(targetId)?.let { return DocumentCapabilities.ofPage(it) }
        return emptySet()
    }

    /** The Phase-0 [CommandContext] for the current scope state — the union across the selection. */
    fun commandContext(surface: CommandSurface = CommandSurface.DOCK): CommandContext {
        val targets = selection.selection().filter { it.domain == SelectionDomain.DOCUMENT }
        return CommandContext(
            offeredCapabilities = targets.flatMapTo(linkedSetOf()) { capabilitiesOf(it.id) },
            targetCount = targets.size,
            hasPrimary = selection.primary() != null,
            surface = surface,
        )
    }

    private fun granularityFor(count: Int): SelectionGranularity =
        if (count > 1) SelectionGranularity.DOC_MULTIPLE_OBJECTS else SelectionGranularity.DOC_OBJECT

    private fun BlockDocument.locateObjectExists(id: String): Boolean =
        pages().any { it.objectById(id) != null }
}
