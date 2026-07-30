package com.norfold.app.editor.document

import com.norfold.app.domain.DocPagePreset
import com.norfold.app.domain.DocumentPageBlock
import com.norfold.app.domain.PageColumns
import com.norfold.app.domain.PageGeometry
import com.norfold.app.domain.PageMargins
import com.norfold.app.domain.PageOrientation
import com.norfold.app.domain.SceneLock
import com.norfold.app.domain.SceneObject
import com.norfold.app.domain.sectionIndices
import com.norfold.app.editor.command.ApplicabilityPolicy
import com.norfold.app.editor.command.Capability
import com.norfold.app.editor.command.CommandContext
import com.norfold.app.editor.command.CommandRegistry
import com.norfold.app.editor.command.CommandResult
import com.norfold.app.editor.command.CommandStatus
import com.norfold.app.editor.command.EditorCommand
import com.norfold.app.editor.selection.ApplicabilityResolution
import com.norfold.app.editor.selection.SelectionDomain
import com.norfold.app.editor.selection.SelectionGranularity
import com.norfold.app.editor.selection.SelectionTarget

/**
 * Base class for every document scene/page command (roadmap Phase 2 §5, §6, §15).
 *
 * Structurally identical to [com.norfold.app.editor.flow.FlowCommand], and deliberately so: eligibility is
 * resolved against the *actual* selection through the Selection Engine (not merely the union of offered
 * capabilities), counts are honest, and each command is exactly one canonical transaction. Because every
 * route executes this same object, a keyboard nudge, a drag, and a TalkBack "Move forward" action are the
 * same command with the same eligibility and the same undo entry — which is what §15 requires and what a
 * separate accessibility implementation could never guarantee.
 */
abstract class DocumentCommand(protected val scope: DocumentCommandScope) : EditorCommand {

    override val description: String get() = label
    override val icon: String? get() = null
    override val category: String get() = "Document"
    override val aliases: List<String> get() = emptyList()

    protected open fun unavailableReason(): String = "Not available for this selection"

    protected open fun refine(resolution: ApplicabilityResolution?): CommandStatus = CommandStatus.Available

    final override fun status(context: CommandContext): CommandStatus {
        if (requiredCapabilities.isEmpty()) return refine(null)
        val resolution = scope.selection.resolveApplicability(applicability, ::isEligible)
        if (resolution is ApplicabilityResolution.NotApplicable) {
            if (scope.selection.selection().none { it.domain == SelectionDomain.DOCUMENT }) {
                return CommandStatus.Hidden
            }
            return if (applicability == ApplicabilityPolicy.ELIGIBLE_SUBSET) {
                CommandStatus.disabled(unavailableReason())
            } else {
                CommandStatus.Hidden
            }
        }
        return refine(resolution)
    }

    final override fun perform(context: CommandContext): CommandResult = runOn(eligibleIds())

    protected abstract fun runOn(targetIds: List<String>): CommandResult

    protected open val granularity: SelectionGranularity get() = SelectionGranularity.DOC_OBJECT

    protected open fun isEligible(target: SelectionTarget): Boolean =
        target.granularity == granularity &&
            requiredCapabilities.all { it in scope.capabilitiesOf(target.id) }

    protected fun eligibleIds(): List<String> {
        val selected = scope.selection.selection().filter(::isEligible).map { it.id }
        return when (applicability) {
            ApplicabilityPolicy.PRIMARY_TARGET ->
                listOfNotNull(selected.firstOrNull { it == scope.selection.primary()?.id })
            else -> selected
        }
    }

    /** Multi-object selections carry the multi granularity, so eligibility must accept either form. */
    protected fun isObjectTarget(target: SelectionTarget): Boolean =
        target.granularity == SelectionGranularity.DOC_OBJECT ||
            target.granularity == SelectionGranularity.DOC_MULTIPLE_OBJECTS
}

/** An object command whose eligibility accepts both single and multi object granularities. */
abstract class SceneObjectCommand(scope: DocumentCommandScope) : DocumentCommand(scope) {
    override fun isEligible(target: SelectionTarget): Boolean =
        isObjectTarget(target) && requiredCapabilities.all { it in scope.capabilitiesOf(target.id) }
}

// ── Object manipulation ─────────────────────────────────────────────────────────────────────────────

/**
 * Nudges every eligible object by a fixed step — the keyboard and accessibility route to movement.
 *
 * It commits the same geometry a drag would, through the same controller call, so "moved with the arrow
 * keys" and "moved with a finger" are indistinguishable in history and in the saved document.
 */
class NudgeObjectsCommand(
    scope: DocumentCommandScope,
    private val dx: Float,
    private val dy: Float,
    override val id: String,
    override val label: String,
) : SceneObjectCommand(scope) {
    override val requiredCapabilities = setOf(Capability.MOVEMENT)
    override val applicability = ApplicabilityPolicy.ELIGIBLE_SUBSET
    override val aliases = listOf("move", "nudge")
    override fun unavailableReason() = "This object's position is locked"

    override fun runOn(targetIds: List<String>): CommandResult {
        if (targetIds.isEmpty()) return CommandResult.Failure(unavailableReason())
        val moved = scope.controller.moveObjects(targetIds, dx, dy)
        return if (moved) CommandResult.Success(targetIds.size) else CommandResult.NoOp
    }
}

class ReorderObjectCommand(
    scope: DocumentCommandScope,
    private val move: LayerMove,
    override val id: String,
) : SceneObjectCommand(scope) {
    override val label get() = move.label
    override val requiredCapabilities = setOf(Capability.ORDERING)
    override val applicability = ApplicabilityPolicy.ELIGIBLE_SUBSET
    override val aliases = listOf("layer", "order", "arrange")

    override fun runOn(targetIds: List<String>): CommandResult {
        if (targetIds.isEmpty()) return CommandResult.Failure(unavailableReason())
        // Front-ward moves are applied back-to-front (and vice versa) so a multi-object reorder preserves
        // the objects' relative order instead of inverting it as each one leapfrogs the last.
        val ordered = when (move) {
            LayerMove.FORWARD, LayerMove.TO_FRONT -> targetIds
            LayerMove.BACKWARD, LayerMove.TO_BACK -> targetIds.reversed()
        }
        val affected = ordered.count { scope.controller.reorderObject(it, move) }
        return if (affected > 0) CommandResult.Success(affected) else CommandResult.NoOp
    }
}

class DuplicateObjectsCommand(scope: DocumentCommandScope) : SceneObjectCommand(scope) {
    override val id = "document.object.duplicate"
    override val label = "Duplicate"
    override val requiredCapabilities = setOf(Capability.DUPLICATION)
    override val applicability = ApplicabilityPolicy.ELIGIBLE_SUBSET
    override val aliases = listOf("copy", "clone")

    override fun runOn(targetIds: List<String>): CommandResult {
        if (targetIds.isEmpty()) return CommandResult.Failure(unavailableReason())
        val created = targetIds.mapNotNull { scope.controller.duplicateObject(it) }
        if (created.isEmpty()) return CommandResult.NoOp
        // §4.14: the produced results become the selection, so the user manipulates the copy they just made.
        scope.applyOutcome(scope.selectResult(created))
        return CommandResult.Success(created.size)
    }
}

class DeleteObjectsCommand(scope: DocumentCommandScope) : SceneObjectCommand(scope) {
    override val id = "document.object.delete"
    override val label = "Delete"
    override val requiredCapabilities = setOf(Capability.SELECTION)
    override val applicability = ApplicabilityPolicy.ELIGIBLE_SUBSET
    override val aliases = listOf("remove")

    override fun isEligible(target: SelectionTarget): Boolean =
        isObjectTarget(target) && scope.controller.findObject(target.id)?.second?.canEditContent == true

    override fun unavailableReason() = "This object is locked"

    override fun runOn(targetIds: List<String>): CommandResult {
        if (targetIds.isEmpty()) return CommandResult.Failure(unavailableReason())
        val before = scope.document()
        val deleted = scope.controller.deleteObjects(targetIds)
        if (!deleted) return CommandResult.NoOp
        // Selection must follow the document, or the highlight outlives the object it described.
        scope.applyOutcome(scope.nearestAfterDelete(before, targetIds.first()))
        return CommandResult.Success(targetIds.size)
    }
}

class SetObjectLockCommand(
    scope: DocumentCommandScope,
    private val lock: SceneLock,
    override val id: String,
    override val label: String,
) : SceneObjectCommand(scope) {
    override val requiredCapabilities = setOf(Capability.LOCKING)
    override val applicability = ApplicabilityPolicy.ELIGIBLE_SUBSET
    override val aliases = listOf("lock", "unlock", "protect")

    /** ACTIVE when every selected object already carries this lock — the toggle shows its own state (§4.2). */
    override fun refine(resolution: ApplicabilityResolution?): CommandStatus {
        val objects = scope.selectedObjects()
        if (objects.isEmpty()) return CommandStatus.Available
        val locks = objects.mapTo(HashSet()) { it.lock }
        return when {
            locks.size > 1 -> CommandStatus.Mixed
            locks.single() == lock -> CommandStatus.Active
            else -> CommandStatus.Available
        }
    }

    override fun runOn(targetIds: List<String>): CommandResult {
        if (targetIds.isEmpty()) return CommandResult.Failure(unavailableReason())
        val changed = scope.controller.setLock(targetIds, lock)
        return if (changed) CommandResult.Success(targetIds.size) else CommandResult.NoOp
    }
}

// ── Page commands ───────────────────────────────────────────────────────────────────────────────────

/**
 * Base for page commands. These resolve against the page scope rather than the object selection, because
 * "insert a page after this one" is meaningful with no object selected at all.
 */
abstract class PageCommand(scope: DocumentCommandScope) : DocumentCommand(scope) {
    override val category = "Page"
    override val granularity = SelectionGranularity.DOC_PAGE
    override val requiredCapabilities: Set<Capability> = emptySet()
    override val applicability = ApplicabilityPolicy.PRIMARY_TARGET

    /**
     * Pages this command applies to, honouring the requested [PageScope]. Falls back to the active page so
     * every page command has a well-defined target even before the user has selected anything.
     */
    protected fun scopedPageIds(pageScope: PageScope): List<String> = pageScope.resolve(scope)

    protected fun activePageId(): String? = scope.activePage()?.id
}

class InsertPageCommand(
    scope: DocumentCommandScope,
    private val after: Boolean,
) : PageCommand(scope) {
    override val id = if (after) "document.page.insertAfter" else "document.page.insertBefore"
    override val label = if (after) "Insert page after" else "Insert page before"
    override val aliases = listOf("page", "new page", "add page")

    override fun runOn(targetIds: List<String>): CommandResult {
        val anchor = activePageId()
        // A new page inherits the explicit "New pages by default" template if the user set one, else the
        // anchor's geometry — a document of mixed sizes would otherwise silently revert to A4 halfway
        // through, which is never what the user meant by "insert a page here".
        val page = DocumentPageBlock(geometry = scope.newPageTemplate())
        val ok = scope.controller.insertPage(page, anchor, after)
        if (!ok) return CommandResult.Failure("Could not insert a page")
        scope.selectPage(page.id)
        return CommandResult.Success(1)
    }
}

class DuplicatePageCommand(scope: DocumentCommandScope) : PageCommand(scope) {
    override val id = "document.page.duplicate"
    override val label = "Duplicate page"
    override val aliases = listOf("copy page")

    override fun runOn(targetIds: List<String>): CommandResult {
        val pageId = activePageId() ?: return CommandResult.Failure("No page selected")
        val copy = scope.controller.duplicatePage(pageId) ?: return CommandResult.Failure("Could not duplicate")
        scope.selectPage(copy)
        return CommandResult.Success(1)
    }
}

class DeletePageCommand(scope: DocumentCommandScope) : PageCommand(scope) {
    override val id = "document.page.delete"
    override val label = "Delete page"
    override val aliases = listOf("remove page")

    /**
     * Disabled rather than hidden on the last page: an explanation is more useful than a control that
     * silently vanishes, and this is the one page command whose refusal a user is likely to test.
     */
    override fun refine(resolution: ApplicabilityResolution?): CommandStatus =
        if (scope.pages().size <= 1) CommandStatus.disabled("A document needs at least one page")
        else CommandStatus.Available

    override fun runOn(targetIds: List<String>): CommandResult {
        val pageId = activePageId() ?: return CommandResult.Failure("No page selected")
        val pages = scope.pages()
        val index = pages.indexOfFirst { it.id == pageId }
        val deleted = scope.controller.deletePage(pageId)
        if (!deleted) return CommandResult.Failure("A document needs at least one page")
        // Selection follows to a page that still exists, so the host never renders a deleted page.
        scope.pages().getOrNull(index.coerceAtMost(scope.pages().size - 1))?.let { scope.selectPage(it.id) }
        return CommandResult.Success(1)
    }
}

class MovePageCommand(
    scope: DocumentCommandScope,
    private val delta: Int,
    override val id: String,
    override val label: String,
) : PageCommand(scope) {
    override val aliases = listOf("reorder page", "move page")

    override fun runOn(targetIds: List<String>): CommandResult {
        val pageId = activePageId() ?: return CommandResult.Failure("No page selected")
        val index = scope.pages().indexOfFirst { it.id == pageId }
        if (index < 0) return CommandResult.Failure("No page selected")
        val moved = scope.controller.movePage(pageId, index + delta)
        return if (moved) CommandResult.Success(1) else CommandResult.NoOp
    }
}

/** Locks or unlocks page structure — the control that governs every other page-geometry command. */
class SetPageStructureLockCommand(
    scope: DocumentCommandScope,
    private val locked: Boolean,
    private val pageScope: PageScope = PageScope.CurrentPage,
) : PageCommand(scope) {
    override val id = if (locked) "document.page.lockStructure" else "document.page.unlockStructure"
    override val label = if (locked) "Lock page structure" else "Unlock page structure"
    override val aliases = listOf("page lock", "protect page")

    override fun refine(resolution: ApplicabilityResolution?): CommandStatus {
        if (pageScope == PageScope.NewPagesByDefault) {
            return if (scope.newPageTemplate().structureLocked == locked) CommandStatus.Active
            else CommandStatus.Available
        }
        val pages = scopedPageIds(pageScope).mapNotNull { scope.controller.page(it) }
        if (pages.isEmpty()) return CommandStatus.Available
        val states = pages.mapTo(HashSet()) { it.geometry.structureLocked }
        return when {
            states.size > 1 -> CommandStatus.Mixed
            states.single() == locked -> CommandStatus.Active
            else -> CommandStatus.Available
        }
    }

    override fun runOn(targetIds: List<String>): CommandResult {
        if (pageScope == PageScope.NewPagesByDefault) {
            scope.applyToNewPageTemplate { it.copy(structureLocked = locked) }
            return CommandResult.Success(0, PageScope.NEW_PAGE_CONFIRMATION)
        }
        val ids = scopedPageIds(pageScope)
        if (ids.isEmpty()) return CommandResult.Failure("No page selected")
        val changed = scope.controller.setPageStructureLocked(ids, locked)
        return if (changed) CommandResult.Success(ids.size) else CommandResult.NoOp
    }
}

/**
 * Every page-geometry change (size, orientation, margins, columns, background, border, grid, bleed,
 * header/footer, numbering, section start, restore defaults) as one parameterized command.
 *
 * One command rather than a dozen because they share every rule that matters: the same scope resolution,
 * the same structure-lock refusal, the same single canonical transaction. Splitting them would mean
 * re-deriving those three things a dozen times, and the structure-lock check is precisely the one a
 * hand-written variant would eventually omit.
 */
class ApplyPageGeometryCommand(
    scope: DocumentCommandScope,
    override val id: String,
    override val label: String,
    private val pageScope: PageScope,
    /**
     * Whether a page is *already* in the state this command produces (§4.2 ACTIVE, §4.11 MIXED).
     *
     * Defaults to "applying the change would do nothing", which is the correct answer for every control
     * that sets a value. A toggle needs its own predicate: applying it always changes something, so the
     * derived answer would report every toggle as inactive and the sheet would never show what is on.
     */
    private val activeWhen: ((PageGeometry) -> Boolean)? = null,
    private val change: (PageGeometry) -> PageGeometry,
) : PageCommand(scope) {
    override val aliases = listOf("page setup", "page settings")

    private fun isActiveFor(geometry: PageGeometry): Boolean =
        activeWhen?.invoke(geometry) ?: (change(geometry).normalized() == geometry.normalized())

    override fun refine(resolution: ApplicabilityResolution?): CommandStatus {
        // The new-page template is not a page: it has no structure lock to respect and no selection to
        // resolve against, so it answers only "is this already the default".
        if (pageScope == PageScope.NewPagesByDefault) {
            return if (isActiveFor(scope.newPageTemplate())) CommandStatus.Active else CommandStatus.Available
        }
        val pages = scopedPageIds(pageScope).mapNotNull { scope.controller.page(it) }
        if (pages.isEmpty()) return CommandStatus.disabled("No page selected")
        // The refusal is stated, not silent: the user is told the page is protected and how to proceed.
        if (pages.all { it.geometry.structureLocked }) {
            return CommandStatus.disabled("Page structure is locked — unlock it to change page setup")
        }
        val states = pages.mapTo(HashSet()) { isActiveFor(it.geometry) }
        return when {
            states.size > 1 -> CommandStatus.Mixed
            states.single() -> CommandStatus.Active
            else -> CommandStatus.Available
        }
    }

    override fun runOn(targetIds: List<String>): CommandResult {
        if (pageScope == PageScope.NewPagesByDefault) {
            // Deliberately not a transaction: changing what *future* pages will look like did not change the
            // document, so it must not occupy an undo step that appears to have done nothing.
            scope.applyToNewPageTemplate(change)
            return CommandResult.Success(0, PageScope.NEW_PAGE_CONFIRMATION)
        }
        val ids = scopedPageIds(pageScope)
        if (ids.isEmpty()) return CommandResult.Failure("No page selected")
        val changed = scope.controller.editPageGeometry(ids, label, force = false, change = change)
        if (!changed) {
            val locked = ids.mapNotNull { scope.controller.page(it) }.any { it.geometry.structureLocked }
            return if (locked) {
                CommandResult.Failure("Page structure is locked — unlock it to change page setup")
            } else {
                CommandResult.NoOp
            }
        }
        return CommandResult.Success(ids.count { scope.controller.page(it) != null })
    }
}

/**
 * Which pages a Page Control change applies to (roadmap Phase 2 §5).
 *
 * "Apply settings to…" is a real scope selector rather than a checkbox, because the same change means
 * genuinely different things at different scopes and the user must be able to say which they meant.
 */
sealed interface PageScope {
    /** How this scope reads in the "Apply settings to…" selector. */
    val label: String

    fun resolve(scope: DocumentCommandScope): List<String>

    data object CurrentPage : PageScope {
        override val label = "Current page"
        override fun resolve(scope: DocumentCommandScope) = listOfNotNull(scope.activePage()?.id)
    }

    data object SelectedPages : PageScope {
        override val label = "Selected pages"
        override fun resolve(scope: DocumentCommandScope): List<String> =
            scope.selectedPageIds.ifEmpty { CurrentPage.resolve(scope) }
    }

    /** The section containing the active page — the run of pages between two section starts. */
    data object ThisSection : PageScope {
        override val label = "This section"
        override fun resolve(scope: DocumentCommandScope): List<String> {
            val pages = scope.pages()
            val active = scope.activePage()?.id ?: return emptyList()
            val index = pages.indexOfFirst { it.id == active }
            if (index < 0) return emptyList()
            val sections = pages.sectionIndices()
            val section = sections[index]
            return pages.filterIndexed { i, _ -> sections[i] == section }.map { it.id }
        }
    }

    /** The active page and every page after it — "from here on". */
    data object ThisPageOnward : PageScope {
        override val label = "This page onward"
        override fun resolve(scope: DocumentCommandScope): List<String> {
            val pages = scope.pages()
            val index = pages.indexOfFirst { it.id == scope.activePage()?.id }
            if (index < 0) return emptyList()
            return pages.drop(index).map { it.id }
        }
    }

    data object WholeDocument : PageScope {
        override val label = "Whole document"
        override fun resolve(scope: DocumentCommandScope) = scope.pages().map { it.id }
    }

    /**
     * The template future pages inherit — it deliberately resolves to **no** existing page.
     *
     * Setting a default is not an edit to the document, so it changes nothing that exists and enters no
     * transaction. A scope that quietly also applied to the current page would make "set this for new pages"
     * silently reformat the page the user is looking at.
     */
    data object NewPagesByDefault : PageScope {
        override val label = "New pages by default"
        override fun resolve(scope: DocumentCommandScope): List<String> = emptyList()
    }

    companion object {
        /** The order the "Apply settings to…" selector offers, narrowest first. */
        val ALL: List<PageScope> = listOf(
            CurrentPage,
            SelectedPages,
            ThisSection,
            ThisPageOnward,
            WholeDocument,
            NewPagesByDefault,
        )

        /** Said back to the user, because a default change has no visible effect on the current page. */
        const val NEW_PAGE_CONFIRMATION = "Saved as the default for new pages"
    }
}

/**
 * Registers the Phase-2 document catalogue.
 *
 * Everything a user can do to a scene object or a page is here, which is what makes the keyboard map and the
 * accessibility action list *projections* of a single catalogue rather than parallel implementations that
 * drift.
 */
object DocumentCommands {

    /** Nudge step in page points; a coarse step for modifier-nudge. */
    const val NUDGE = 1f
    const val NUDGE_LARGE = 10f

    fun registerAll(scope: DocumentCommandScope, registry: CommandRegistry = CommandRegistry()): CommandRegistry {
        registry.registerAll(
            // Movement
            NudgeObjectsCommand(scope, -NUDGE, 0f, "document.object.nudgeLeft", "Nudge left"),
            NudgeObjectsCommand(scope, NUDGE, 0f, "document.object.nudgeRight", "Nudge right"),
            NudgeObjectsCommand(scope, 0f, -NUDGE, "document.object.nudgeUp", "Nudge up"),
            NudgeObjectsCommand(scope, 0f, NUDGE, "document.object.nudgeDown", "Nudge down"),
            NudgeObjectsCommand(scope, -NUDGE_LARGE, 0f, "document.object.moveLeft", "Move left"),
            NudgeObjectsCommand(scope, NUDGE_LARGE, 0f, "document.object.moveRight", "Move right"),
            NudgeObjectsCommand(scope, 0f, -NUDGE_LARGE, "document.object.moveUp", "Move up"),
            NudgeObjectsCommand(scope, 0f, NUDGE_LARGE, "document.object.moveDown", "Move down"),

            // Layering
            ReorderObjectCommand(scope, LayerMove.FORWARD, "document.object.bringForward"),
            ReorderObjectCommand(scope, LayerMove.BACKWARD, "document.object.sendBackward"),
            ReorderObjectCommand(scope, LayerMove.TO_FRONT, "document.object.bringToFront"),
            ReorderObjectCommand(scope, LayerMove.TO_BACK, "document.object.sendToBack"),

            // Lifecycle
            DuplicateObjectsCommand(scope),
            DeleteObjectsCommand(scope),
            SetObjectLockCommand(scope, SceneLock.Full, "document.object.lock", "Lock"),
            SetObjectLockCommand(scope, SceneLock.None, "document.object.unlock", "Unlock"),

            // Pages
            InsertPageCommand(scope, after = true),
            InsertPageCommand(scope, after = false),
            DuplicatePageCommand(scope),
            DeletePageCommand(scope),
            MovePageCommand(scope, -1, "document.page.moveUp", "Move page up"),
            MovePageCommand(scope, 1, "document.page.moveDown", "Move page down"),
            SetPageStructureLockCommand(scope, locked = true),
            SetPageStructureLockCommand(scope, locked = false),
        )
        registerPageSetup(scope, registry)
        return registry
    }

    /**
     * Page-setup commands at [PageScope.CurrentPage] — the ids the registry, slash menu and Command Explorer
     * know. The Page Control surface rebuilds the *same* control at the user's chosen scope via
     * [pageSetupCommand], because a scope is a choice made at invocation time, not a catalogue dimension:
     * registering all sixteen controls once per scope would sextuple the catalogue and give the slash menu
     * six near-identical rows for every one the user wanted.
     */
    private fun registerPageSetup(scope: DocumentCommandScope, registry: CommandRegistry) {
        PAGE_SETUP_CONTROLS.forEach { control ->
            registry.register(pageSetupCommand(scope, control, PageScope.CurrentPage))
        }
    }

    /**
     * One page-setup control, independent of which pages it is applied to.
     *
     * Separating the control from its scope is what makes "Apply settings to…" real: the same definition
     * resolves its own ACTIVE/MIXED state and performs its own change whether it is aimed at one page, a
     * section, the whole document, or the new-page template.
     */
    data class PageSetupControl(
        val key: String,
        val label: String,
        val group: String,
        /** Null means "already applied" is derived from the change being a no-op — correct for setters. */
        val activeWhen: ((PageGeometry) -> Boolean)? = null,
        val change: (PageGeometry) -> PageGeometry,
    ) {
        val id: String get() = "document.page.$key"
    }

    /** The Page Control catalogue, in the order the surface presents it. */
    val PAGE_SETUP_CONTROLS: List<PageSetupControl> = listOf(
        PageSetupControl("sizeA4", "A4", "Size") {
            PageGeometry.a4().copy(structureLocked = it.structureLocked, margins = it.margins)
        },
        PageSetupControl("sizeLetter", "Letter", "Size") {
            PageGeometry.letter().copy(structureLocked = it.structureLocked, margins = it.margins)
        },
        PageSetupControl("sizeLegal", "Legal", "Size") {
            PageGeometry.legal().copy(structureLocked = it.structureLocked, margins = it.margins)
        },
        PageSetupControl(
            "portrait", "Portrait", "Orientation",
            activeWhen = { it.orientation == PageOrientation.Portrait },
        ) { it.withOrientation(PageOrientation.Portrait) },
        PageSetupControl(
            "landscape", "Landscape", "Orientation",
            activeWhen = { it.orientation == PageOrientation.Landscape },
        ) { it.withOrientation(PageOrientation.Landscape) },
        PageSetupControl("marginsNarrow", "Narrow margins", "Margins") {
            it.copy(margins = PageMargins(36f, 36f, 36f, 36f))
        },
        PageSetupControl("marginsNormal", "Normal margins", "Margins") {
            it.copy(margins = PageMargins())
        },
        PageSetupControl("marginsWide", "Wide margins", "Margins") {
            it.copy(margins = PageMargins(108f, 108f, 108f, 108f))
        },
        PageSetupControl("oneColumn", "One column", "Columns") { it.copy(columns = PageColumns(1)) },
        PageSetupControl("twoColumns", "Two columns", "Columns") { it.copy(columns = PageColumns(2)) },
        PageSetupControl("threeColumns", "Three columns", "Columns") { it.copy(columns = PageColumns(3)) },
        // Toggles need an explicit predicate: a toggle's change is never a no-op, so the derived answer
        // would report every one of them as inactive and the sheet would never show what is switched on.
        PageSetupControl("toggleGrid", "Show grid", "Grid", activeWhen = { it.grid.visible }) {
            it.copy(grid = it.grid.copy(visible = !it.grid.visible))
        },
        PageSetupControl("toggleSnapGrid", "Snap to grid", "Grid", activeWhen = { it.grid.snapToGrid }) {
            it.copy(grid = it.grid.copy(snapToGrid = !it.grid.snapToGrid))
        },
        PageSetupControl(
            "startSection", "Start section here", "Sections",
            activeWhen = { it.numbering.startsSection },
        ) { it.copy(numbering = it.numbering.copy(startsSection = !it.numbering.startsSection)) },
        PageSetupControl(
            "showNumbers", "Show page numbers", "Sections",
            activeWhen = { it.numbering.showNumber },
        ) { it.copy(numbering = it.numbering.copy(showNumber = !it.numbering.showNumber)) },
        // Restoring defaults deliberately keeps the lock state: it resets the page's setup, not the
        // user's decision about whether that setup may be changed at all.
        PageSetupControl("restoreDefaults", "Restore page defaults", "Reset") {
            PageGeometry.ofPreset(DocPagePreset.A4).copy(structureLocked = it.structureLocked)
        },
    )

    /** Builds [control] aimed at [pageScope] — how the Page Control surface applies a chosen scope. */
    fun pageSetupCommand(
        scope: DocumentCommandScope,
        control: PageSetupControl,
        pageScope: PageScope,
    ): EditorCommand = ApplyPageGeometryCommand(
        scope = scope,
        id = control.id,
        label = control.label,
        pageScope = pageScope,
        activeWhen = control.activeWhen,
        change = control.change,
    )

    /**
     * Accessibility actions for [obj]: the command ids TalkBack should expose for it.
     *
     * Ids, not new closures — the accessibility tree invokes exactly the registered commands, so an action
     * that is unavailable through the registry is unavailable through TalkBack too, automatically.
     */
    fun accessibilityActionsFor(obj: SceneObject): List<String> = buildList {
        if (obj.canMove) {
            add("document.object.moveLeft")
            add("document.object.moveRight")
            add("document.object.moveUp")
            add("document.object.moveDown")
        }
        add("document.object.bringForward")
        add("document.object.sendBackward")
        add("document.object.bringToFront")
        add("document.object.sendToBack")
        if (obj.canEditContent) {
            add("document.object.duplicate")
            add("document.object.delete")
        }
        add(if (obj.lock == SceneLock.None) "document.object.lock" else "document.object.unlock")
    }
}
