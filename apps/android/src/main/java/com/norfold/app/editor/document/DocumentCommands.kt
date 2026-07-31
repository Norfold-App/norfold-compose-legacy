package com.norfold.app.editor.document

import com.norfold.app.domain.DocPagePreset
import com.norfold.app.domain.DocumentBlock
import com.norfold.app.domain.DocumentPageBlock
import com.norfold.app.domain.PageColumns
import com.norfold.app.domain.PageGeometry
import com.norfold.app.domain.PageMargins
import com.norfold.app.domain.PageNumberFormat
import com.norfold.app.domain.PageOrientation
import com.norfold.app.domain.POINTS_PER_MM
import com.norfold.app.domain.ParagraphBlock
import com.norfold.app.domain.SceneGeometry
import com.norfold.app.domain.SceneLock
import com.norfold.app.domain.SceneObject
import com.norfold.app.domain.SceneObjectKind
import com.norfold.app.domain.ShapeBlock
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
        // One transaction for the whole selection. Reordering each target separately would put four Undos
        // behind one "bring to front", and the controller already preserves the selection's relative order.
        val moved = scope.controller.reorderObjects(targetIds, move)
        return if (moved) CommandResult.Success(targetIds.size) else CommandResult.NoOp
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
        // One transaction for the whole selection: duplicating per object would put N Undos behind one action.
        val created = scope.controller.duplicateObjects(targetIds)
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

/**
 * Inserts a new object on the active page (roadmap Phase 2 §7).
 *
 * Three things this deliberately does, because each one is a way insertion usually goes wrong:
 *
 *  - it names the page **explicitly** (the active page), rather than inferring one from a scroll position.
 *    "The page you can mostly see" is ambiguous exactly when two are half-visible, and landing content on
 *    the wrong page is a silent misplacement the user finds much later;
 *  - the object and its content both get fresh ids, so inserting twice can never produce two objects that
 *    anything resolving by id would confuse for each other;
 *  - the new object becomes the selection (§4.14), so the very next gesture manipulates the thing that was
 *    just created instead of whatever was selected before.
 *
 * Placement is the page's content origin plus a per-object cascade, so repeated inserts stair-step instead of
 * stacking invisibly at one point.
 */
class InsertSceneObjectCommand(
    scope: DocumentCommandScope,
    private val kind: SceneObjectKind,
    override val id: String,
    override val label: String,
    private val size: SceneGeometry,
    private val content: () -> DocumentBlock,
) : DocumentCommand(scope) {
    override val category = "Insert"
    override val granularity = SelectionGranularity.DOC_PAGE
    override val requiredCapabilities: Set<Capability> = emptySet()
    override val applicability = ApplicabilityPolicy.PRIMARY_TARGET
    override val aliases = listOf("insert", "add", "new")

    override fun refine(resolution: ApplicabilityResolution?): CommandStatus =
        if (scope.activePage() == null) CommandStatus.disabled("No page to insert onto")
        else CommandStatus.Available

    override fun runOn(targetIds: List<String>): CommandResult {
        val page = scope.activePage() ?: return CommandResult.Failure("No page to insert onto")
        val geometry = size.copy(
            x = page.geometry.contentLeft + cascade(page.objects.size),
            y = page.geometry.contentTop + cascade(page.objects.size),
        ).normalized()
        val obj = SceneObject(kind = kind, geometry = geometry, content = content())
        if (!scope.controller.addObject(page.id, obj, "Insert ${label.lowercase()}")) {
            return CommandResult.Failure("Could not insert")
        }
        scope.applyOutcome(scope.selectResult(listOf(obj.id)))
        return CommandResult.Success(1)
    }

    private fun cascade(index: Int): Float = (index % CASCADE_WRAP) * CASCADE_STEP

    private companion object {
        const val CASCADE_STEP = 18f

        /** After this many, the cascade restarts rather than walking off the sheet. */
        const val CASCADE_WRAP = 8
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
 * A page-setup control whose change needs a **value the user supplies** — a custom width in millimetres, a
 * background colour, a numbering restart, a guide position.
 *
 * Separate from [DocumentCommands.PageSetupControl] because the two answer different questions. A fixed
 * control ("A4", "Two columns") is fully described by its change function and can be a catalogue entry
 * outright. A value control cannot: "custom width" is not a command until there is a width. Modelling them as
 * one type would mean every fixed control carrying an unused value slot, and every value control pretending
 * to have a default that the ACTIVE/MIXED logic would then report on.
 *
 * The value arrives as a string because that is what a text field holds, and [parse] is the single place it
 * becomes a number. A control that took a `Float` would push parsing — and therefore the `NaN`/blank/overflow
 * decisions — out to each call site.
 */
data class PageValueControl(
    val key: String,
    val label: String,
    val group: String,
    val kind: Kind,
    /** The value to show in the field for a page currently in this state. */
    val valueOf: (PageGeometry) -> String,
    /** The geometry [raw] produces, or null when [raw] is not a usable value for this control. */
    val apply: (PageGeometry, String) -> PageGeometry?,
) {
    /** What kind of input the surface should present. Presentation only — parsing lives in [apply]. */
    enum class Kind { LENGTH_MM, LENGTH_PT, COLOR, INTEGER, TEXT, GUIDES, NUMBER_FORMAT }

    val id: String get() = "document.page.$key"
}

/**
 * One page-setup control driven by a user-supplied value, at a chosen [PageScope].
 *
 * Shares every rule with [ApplyPageGeometryCommand] — the same scope resolution, the same structure-lock
 * refusal, one canonical transaction — because they are the same operation differing only in where the new
 * geometry comes from. Duplicating those three rules for value controls is precisely how the structure lock
 * would end up enforced for presets and forgotten for custom sizes.
 */
class ApplyPageValueCommand(
    scope: DocumentCommandScope,
    private val control: PageValueControl,
    private val pageScope: PageScope,
    private val raw: String,
) : PageCommand(scope) {
    override val id = control.id
    override val label = control.label
    override val aliases = listOf("page setup", "page settings")

    override fun refine(resolution: ApplicabilityResolution?): CommandStatus {
        if (pageScope == PageScope.NewPagesByDefault) return CommandStatus.Available
        val pages = scopedPageIds(pageScope).mapNotNull { scope.controller.page(it) }
        if (pages.isEmpty()) return CommandStatus.disabled("No page selected")
        if (pages.all { it.geometry.structureLocked }) {
            return CommandStatus.disabled("Page structure is locked — unlock it to change page setup")
        }
        // MIXED is a real answer here: with a multi-page scope the honest report for "what is the width" is
        // often "different on different pages", and picking one of them would be a lie the field then shows.
        val values = pages.mapTo(LinkedHashSet()) { control.valueOf(it.geometry) }
        return if (values.size > 1) CommandStatus.Mixed else CommandStatus.Available
    }

    override fun runOn(targetIds: List<String>): CommandResult {
        // Rejected before anything is touched: a value that does not parse must leave the document exactly as
        // it was, not clamp itself to a default the user never asked for.
        if (control.apply(scope.newPageTemplate(), raw) == null) {
            return CommandResult.Failure("'$raw' is not a valid ${control.label.lowercase()}")
        }
        if (pageScope == PageScope.NewPagesByDefault) {
            scope.applyToNewPageTemplate { geometry -> control.apply(geometry, raw) ?: geometry }
            return CommandResult.Success(0, PageScope.NEW_PAGE_CONFIRMATION)
        }
        val ids = scopedPageIds(pageScope)
        if (ids.isEmpty()) return CommandResult.Failure("No page selected")
        val changed = scope.controller.editPageGeometry(ids, label, force = false) { geometry ->
            control.apply(geometry, raw) ?: geometry
        }
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

            // Insertion
            InsertSceneObjectCommand(
                scope = scope,
                kind = SceneObjectKind.TextFrame,
                id = DocumentIds.INSERT_TEXT,
                label = "Text frame",
                size = SceneGeometry(width = 240f, height = 96f),
                content = { ParagraphBlock() },
            ),
            InsertSceneObjectCommand(
                scope = scope,
                kind = SceneObjectKind.Shape,
                id = DocumentIds.INSERT_SHAPE,
                label = "Shape",
                size = SceneGeometry(width = 160f, height = 160f),
                content = { ShapeBlock() },
            ),

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
        PageSetupControl("noMargins", "No margins", "Margins") { it.copy(margins = PageMargins.NONE) },
        // Background and border are presented as clear/set pairs rather than only as value fields, because
        // "remove the background" is a distinct intent from "type a colour" and a field cannot express it.
        PageSetupControl(
            "clearBackground", "No background", "Background",
            activeWhen = { it.background == null },
        ) { it.copy(background = null) },
        PageSetupControl(
            "clearBorder", "No border", "Border",
            activeWhen = { !it.border.isVisible },
        ) { it.copy(border = it.border.copy(color = null)) },
        PageSetupControl(
            "showBorder", "Show border", "Border",
            activeWhen = { it.border.isVisible },
        ) { it.copy(border = it.border.copy(color = it.border.color ?: DEFAULT_BORDER_COLOR)) },
        // Toggles need an explicit predicate: a toggle's change is never a no-op, so the derived answer
        // would report every one of them as inactive and the sheet would never show what is switched on.
        PageSetupControl("toggleGrid", "Show grid", "Grid", activeWhen = { it.grid.visible }) {
            it.copy(grid = it.grid.copy(visible = !it.grid.visible))
        },
        PageSetupControl("toggleSnapGrid", "Snap to grid", "Grid", activeWhen = { it.grid.snapToGrid }) {
            it.copy(grid = it.grid.copy(snapToGrid = !it.grid.snapToGrid))
        },
        PageSetupControl("clearGuides", "Clear custom guides", "Grid") {
            it.copy(grid = it.grid.copy(verticalGuides = emptyList(), horizontalGuides = emptyList()))
        },
        PageSetupControl(
            "differentFirstPage", "Different first page", "Header & footer",
            activeWhen = { it.headerFooter.differentFirstPage },
        ) {
            it.copy(headerFooter = it.headerFooter.copy(differentFirstPage = !it.headerFooter.differentFirstPage))
        },
        PageSetupControl(
            "startSection", "Start section here", "Sections",
            activeWhen = { it.numbering.startsSection },
        ) { it.copy(numbering = it.numbering.copy(startsSection = !it.numbering.startsSection)) },
        PageSetupControl(
            "showNumbers", "Show page numbers", "Sections",
            activeWhen = { it.numbering.showNumber },
        ) { it.copy(numbering = it.numbering.copy(showNumber = !it.numbering.showNumber)) },
        PageSetupControl(
            "continueNumbering", "Continue numbering", "Sections",
            activeWhen = { it.numbering.restartAt == null },
        ) { it.copy(numbering = it.numbering.copy(restartAt = null)) },
        // Restoring defaults deliberately keeps the lock state: it resets the page's setup, not the
        // user's decision about whether that setup may be changed at all.
        PageSetupControl("restoreDefaults", "Restore page defaults", "Reset") {
            PageGeometry.ofPreset(DocPagePreset.A4).copy(structureLocked = it.structureLocked)
        },
    ) + PageNumberFormat.entries.map { format ->
        // One control per format rather than a cycling toggle: a cycle makes "set Roman numerals" take an
        // unpredictable number of taps and gives the sheet no way to show which format is actually on.
        PageSetupControl(
            key = "numberFormat${format.name}",
            label = format.displayLabel(),
            group = "Numbering format",
            activeWhen = { it.numbering.format == format },
        ) { it.copy(numbering = it.numbering.copy(format = format)) }
    }

    /** Used when the user asks for a border without naming a colour. */
    private const val DEFAULT_BORDER_COLOR: Long = 0xFF9E9E9E

    /**
     * The value-driven Page Control catalogue: everything that needs a number, a colour or a word from the
     * user rather than a preset.
     *
     * Each entry parses its own input and returns null for anything unusable, so a blank field or a typo
     * leaves the page exactly as it was instead of clamping to a default the user never chose.
     */
    val PAGE_VALUE_CONTROLS: List<PageValueControl> = listOf(
        PageValueControl(
            key = "customWidthMm",
            label = "Width (mm)",
            group = "Size",
            kind = PageValueControl.Kind.LENGTH_MM,
            valueOf = { millimetres(it.width) },
        ) { geometry, raw ->
            val mm = raw.toLengthOrNull() ?: return@PageValueControl null
            // Through `withSizeMm`, so both canonical limits — 2000 mm per side *and* 2 m² of area — are
            // applied by the one function that composes them in the right order.
            geometry.withSizeMm(mm, geometry.height / POINTS_PER_MM)
        },
        PageValueControl(
            key = "customHeightMm",
            label = "Height (mm)",
            group = "Size",
            kind = PageValueControl.Kind.LENGTH_MM,
            valueOf = { millimetres(it.height) },
        ) { geometry, raw ->
            val mm = raw.toLengthOrNull() ?: return@PageValueControl null
            geometry.withSizeMm(geometry.width / POINTS_PER_MM, mm)
        },
        PageValueControl(
            key = "marginTop", label = "Top margin (pt)", group = "Margins",
            kind = PageValueControl.Kind.LENGTH_PT,
            valueOf = { points(it.margins.top) },
        ) { g, raw -> raw.toLengthOrNull()?.let { g.copy(margins = g.margins.copy(top = it)) } },
        PageValueControl(
            key = "marginRight", label = "Right margin (pt)", group = "Margins",
            kind = PageValueControl.Kind.LENGTH_PT,
            valueOf = { points(it.margins.right) },
        ) { g, raw -> raw.toLengthOrNull()?.let { g.copy(margins = g.margins.copy(right = it)) } },
        PageValueControl(
            key = "marginBottom", label = "Bottom margin (pt)", group = "Margins",
            kind = PageValueControl.Kind.LENGTH_PT,
            valueOf = { points(it.margins.bottom) },
        ) { g, raw -> raw.toLengthOrNull()?.let { g.copy(margins = g.margins.copy(bottom = it)) } },
        PageValueControl(
            key = "marginLeft", label = "Left margin (pt)", group = "Margins",
            kind = PageValueControl.Kind.LENGTH_PT,
            valueOf = { points(it.margins.left) },
        ) { g, raw -> raw.toLengthOrNull()?.let { g.copy(margins = g.margins.copy(left = it)) } },
        PageValueControl(
            key = "columnCount", label = "Columns", group = "Columns",
            kind = PageValueControl.Kind.INTEGER,
            valueOf = { it.columns.count.toString() },
        ) { g, raw -> raw.toCountOrNull()?.let { g.copy(columns = g.columns.copy(count = it)) } },
        PageValueControl(
            key = "columnGutter", label = "Gutter (pt)", group = "Columns",
            kind = PageValueControl.Kind.LENGTH_PT,
            valueOf = { points(it.columns.gutter) },
        ) { g, raw -> raw.toLengthOrNull()?.let { g.copy(columns = g.columns.copy(gutter = it)) } },
        PageValueControl(
            key = "backgroundColor", label = "Background colour", group = "Background",
            kind = PageValueControl.Kind.COLOR,
            valueOf = { it.background?.toColorHex() ?: "" },
        ) { g, raw -> raw.toColorOrNull()?.let { g.copy(background = it) } },
        PageValueControl(
            key = "borderColor", label = "Border colour", group = "Border",
            kind = PageValueControl.Kind.COLOR,
            valueOf = { it.border.color?.toColorHex() ?: "" },
        ) { g, raw -> raw.toColorOrNull()?.let { g.copy(border = g.border.copy(color = it)) } },
        PageValueControl(
            key = "borderWidth", label = "Border width (pt)", group = "Border",
            kind = PageValueControl.Kind.LENGTH_PT,
            valueOf = { points(it.border.width) },
        ) { g, raw -> raw.toLengthOrNull()?.let { g.copy(border = g.border.copy(width = it)) } },
        PageValueControl(
            key = "borderInset", label = "Border inset (pt)", group = "Border",
            kind = PageValueControl.Kind.LENGTH_PT,
            valueOf = { points(it.border.inset) },
        ) { g, raw -> raw.toLengthOrNull()?.let { g.copy(border = g.border.copy(inset = it)) } },
        PageValueControl(
            key = "gridSpacing", label = "Grid spacing (pt)", group = "Grid",
            kind = PageValueControl.Kind.LENGTH_PT,
            valueOf = { points(it.grid.spacing) },
        ) { g, raw -> raw.toLengthOrNull()?.let { g.copy(grid = g.grid.copy(spacing = it)) } },
        PageValueControl(
            key = "verticalGuides", label = "Vertical guides (pt)", group = "Grid",
            kind = PageValueControl.Kind.GUIDES,
            valueOf = { it.grid.verticalGuides.joinToString(", ") { g -> points(g) } },
        ) { g, raw -> raw.toGuidesOrNull()?.let { g.copy(grid = g.grid.copy(verticalGuides = it)) } },
        PageValueControl(
            key = "horizontalGuides", label = "Horizontal guides (pt)", group = "Grid",
            kind = PageValueControl.Kind.GUIDES,
            valueOf = { it.grid.horizontalGuides.joinToString(", ") { g -> points(g) } },
        ) { g, raw -> raw.toGuidesOrNull()?.let { g.copy(grid = g.grid.copy(horizontalGuides = it)) } },
        PageValueControl(
            key = "bleed", label = "Bleed (pt)", group = "Print",
            kind = PageValueControl.Kind.LENGTH_PT,
            valueOf = { points(it.bleed.bleed) },
        ) { g, raw -> raw.toLengthOrNull()?.let { g.copy(bleed = g.bleed.copy(bleed = it)) } },
        PageValueControl(
            key = "safeArea", label = "Safe area (pt)", group = "Print",
            kind = PageValueControl.Kind.LENGTH_PT,
            valueOf = { points(it.bleed.safeArea) },
        ) { g, raw -> raw.toLengthOrNull()?.let { g.copy(bleed = g.bleed.copy(safeArea = it)) } },
        PageValueControl(
            key = "headerHeight", label = "Header height (pt)", group = "Header & footer",
            kind = PageValueControl.Kind.LENGTH_PT,
            valueOf = { points(it.headerFooter.headerHeight) },
        ) { g, raw -> raw.toLengthOrNull()?.let { g.copy(headerFooter = g.headerFooter.copy(headerHeight = it)) } },
        PageValueControl(
            key = "footerHeight", label = "Footer height (pt)", group = "Header & footer",
            kind = PageValueControl.Kind.LENGTH_PT,
            valueOf = { points(it.headerFooter.footerHeight) },
        ) { g, raw -> raw.toLengthOrNull()?.let { g.copy(headerFooter = g.headerFooter.copy(footerHeight = it)) } },
        PageValueControl(
            key = "headerText", label = "Header text", group = "Header & footer",
            kind = PageValueControl.Kind.TEXT,
            valueOf = { it.headerFooter.headerText },
            // Text has no invalid value — blank means "no running head", which is a legitimate choice and
            // must not be rejected the way a blank number would be.
        ) { g, raw -> g.copy(headerFooter = g.headerFooter.copy(headerText = raw.trim())) },
        PageValueControl(
            key = "footerText", label = "Footer text", group = "Header & footer",
            kind = PageValueControl.Kind.TEXT,
            valueOf = { it.headerFooter.footerText },
        ) { g, raw -> g.copy(headerFooter = g.headerFooter.copy(footerText = raw.trim())) },
        PageValueControl(
            key = "sectionName", label = "Section name", group = "Sections",
            kind = PageValueControl.Kind.TEXT,
            valueOf = { it.numbering.sectionName },
        ) { g, raw -> g.copy(numbering = g.numbering.copy(sectionName = raw.trim())) },
        PageValueControl(
            key = "restartAt", label = "Restart numbering at", group = "Sections",
            kind = PageValueControl.Kind.INTEGER,
            valueOf = { it.numbering.restartAt?.toString() ?: "" },
        ) { g, raw ->
            val start = raw.trim().toIntOrNull() ?: return@PageValueControl null
            g.copy(numbering = g.numbering.copy(restartAt = start.coerceIn(0, 100_000), startsSection = true))
        },
    )

    /** Builds [control] aimed at [pageScope] with the value the user supplied. */
    fun pageValueCommand(
        scope: DocumentCommandScope,
        control: PageValueControl,
        pageScope: PageScope,
        raw: String,
    ): EditorCommand = ApplyPageValueCommand(scope, control, pageScope, raw)

    private fun millimetres(points: Float): String = points(points / POINTS_PER_MM)

    private fun points(value: Float): String {
        val rounded = Math.round(value * 10f) / 10f
        return if (rounded == rounded.toInt().toFloat()) rounded.toInt().toString() else rounded.toString()
    }

    /**
     * A length the user typed, or null.
     *
     * Non-finite and negative values are rejected rather than clamped: `"-40"` for a margin is a mistake, and
     * silently reading it as 0 would apply a change the user did not ask for while appearing to accept what
     * they typed.
     */
    private fun String.toLengthOrNull(): Float? =
        trim().replace(',', '.').toFloatOrNull()?.takeIf { it.isFinite() && it >= 0f }

    private fun String.toCountOrNull(): Int? = trim().toIntOrNull()?.takeIf { it > 0 }

    /**
     * A colour as `#RRGGBB` or `#AARRGGBB`, or null.
     *
     * A six-digit value gains full opacity, because a page background the user cannot see is indistinguishable
     * from the control not having worked.
     */
    private fun String.toColorOrNull(): Long? {
        val hex = trim().removePrefix("#")
        if (hex.length != 6 && hex.length != 8) return null
        val value = hex.toLongOrNull(16) ?: return null
        return if (hex.length == 6) value or 0xFF000000L else value
    }

    private fun Long.toColorHex(): String = "#%08X".format(this)

    /** A comma- or space-separated guide list. An empty string clears the guides, which is a real intent. */
    private fun String.toGuidesOrNull(): List<Float>? {
        val trimmed = trim()
        if (trimmed.isEmpty()) return emptyList()
        return trimmed.split(',', ' ', ';')
            .filter { it.isNotBlank() }
            .map { it.trim().replace(',', '.').toFloatOrNull()?.takeIf(Float::isFinite) ?: return null }
    }

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
