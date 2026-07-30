package com.norfold.app.editor.document

import com.norfold.app.domain.BlockDocument
import com.norfold.app.domain.DocumentPageBlock
import com.norfold.app.domain.SceneGeometry
import com.norfold.app.domain.SceneObject
import com.norfold.app.domain.pages
import com.norfold.app.editor.command.CommandContext
import com.norfold.app.editor.command.CommandRegistry
import com.norfold.app.editor.command.CommandResult
import com.norfold.app.editor.command.CommandState
import com.norfold.app.editor.command.CommandStatus
import com.norfold.app.editor.command.CommandSurface
import com.norfold.app.editor.command.EditorCommand
import com.norfold.app.editor.memory.InteractionMemory
import com.norfold.app.editor.presentation.AdaptivePresentationRuntime
import com.norfold.app.editor.presentation.FormFactor
import com.norfold.app.editor.recovery.RecoveryReason
import com.norfold.app.editor.recovery.RecoverySnapshotManager
import com.norfold.app.editor.surface.SurfaceCoordinator
import com.norfold.app.editor.selection.SelectionEngine

/** One command as a Document surface should render it: the same status behind every route (§4.1, §4.2). */
data class DocumentCommandItem(
    val id: String,
    val label: String,
    val description: String,
    val category: String,
    val status: CommandStatus,
) {
    val enabled: Boolean get() = status.isInvokable
    val active: Boolean get() = status.state == CommandState.ACTIVE
    val mixed: Boolean get() = status.state == CommandState.MIXED

    /** The reason to show when this row is greyed out, or null when it is not disabled (§4.2). */
    val disabledReason: String?
        get() = if (status.state == CommandState.DISABLED) status.reason ?: "Not available here" else null
}

/**
 * The immutable snapshot the Compose shell renders. Rebuilt whenever [DocumentHostRuntime.revision] changes.
 *
 * A snapshot rather than the runtime itself, for the same reason FlowHost uses one: the runtime is pure-JVM
 * with plain mutable fields, so Compose cannot observe it directly. Publishing one value per revision is what
 * keeps the Compose layer from re-deriving eligibility, geometry or selection on its own.
 */
data class DocumentHostUiState(
    val revision: Long,
    val document: BlockDocument,
    val pages: List<DocumentPageBlock>,
    val selectedObjectIds: List<String>,
    val primaryObjectId: String?,
    val activePageId: String?,
    val selectedPageIds: List<String>,
    val representation: LayoutRepresentation,
    val intent: WorkingIntent,
    val zoom: Float,
    val manipulation: ActiveManipulation?,
    val formFactor: FormFactor,
    val presentationMode: com.norfold.app.editor.presentation.PresentationMode,
    val dock: List<DocumentCommandItem>,
    val pageControls: List<DocumentCommandItem>,
    /** The scope [pageControls] were resolved at, and that invoking one of them will apply. */
    val pageScope: PageScope,
    /** How many pages the current scope names — what "Apply settings to…" is actually promising. */
    val pageScopeCount: Int,
    val canUndo: Boolean,
    val canRedo: Boolean,
    val notice: String?,
    val lastFailure: String?,
)

/**
 * The authoritative runtime for one open bounded document (roadmap Phase 2).
 *
 * This is DocumentHost's counterpart to [com.norfold.app.editor.flow.FlowHostRuntime] and shares **no**
 * presentation logic with it. The two hosts mount the same Phase-0 engines — history via
 * [DocumentSceneController], selection, commands, surfaces, adaptive presentation, interaction memory — and
 * that is the whole extent of the sharing. Product identity is which host you are in, not a layout flag
 * inside one mega-host, which is the arrangement §2 rules out and the legacy `DocOverlapMode` branch was.
 *
 * Every user-visible route lands on [invoke], so a touch handle, a keyboard shortcut and a TalkBack action
 * are literally the same call — there is no second execution path for any of them to diverge along.
 *
 * Pure-JVM: no Compose, no Android, no persistence, no collaboration binding.
 */
class DocumentHostRuntime(
    val controller: DocumentSceneController,
    initialFormFactor: FormFactor,
    val instrumentation: DocumentHostInstrumentation = DocumentHostInstrumentation.None,
) {
    val selection = SelectionEngine()
    val scope = DocumentCommandScope(controller, selection)
    val commands: CommandRegistry = DocumentCommands.registerAll(scope)
    val surfaces = SurfaceCoordinator()
    val presentation = AdaptivePresentationRuntime(initialFormFactor)
    val memory = InteractionMemory()
    val view = DocumentViewState()

    /**
     * Bumped for anything that changes what should be drawn.
     *
     * The Compose shell rebuilds its snapshot from this rather than observing the runtime, which is pure-JVM
     * and deliberately has no Compose state in it. One counter is enough because the shell refreshes once
     * after each interaction; there is no observer plumbing to keep in sync.
     */
    var revision: Long = 0L
        private set

    /** A message to show the user once, e.g. after an automatic Adaptive → Original transition. */
    var notice: String? = null
        private set

    /** The most recent command failure reason, for a transient explanation in the UI. */
    var lastFailure: String? = null
        private set

    fun consumeNotice(): String? = notice.also { notice = null; revision++ }

    fun document(): BlockDocument = controller.document()

    fun pages(): List<DocumentPageBlock> = controller.pages()

    fun formFactor(): FormFactor = presentation.formFactor

    // --- Commands ------------------------------------------------------------------------------------

    /**
     * Executes [commandId] from [surface]. The single invocation path for every route.
     *
     * Instrumentation fires from here rather than from each caller, so "the same command reached four ways"
     * is a fact recorded once at the point of execution instead of four call sites that could drift.
     */
    fun invoke(commandId: String, surface: CommandSurface = CommandSurface.DOCK): CommandResult {
        val result = commands.execute(commandId, scope.commandContext(surface))
        instrumentation.onCommandInvoked(commandId, surface, result)
        lastFailure = (result as? CommandResult.Failure)?.reason
        // A no-op still ends here: the surface must be able to tell "nothing to do" from "done", and the
        // snapshot is what carries that difference to it.
        pruneSelection()
        revision++
        return result
    }

    fun canUndo(): Boolean = controller.canUndo()
    fun canRedo(): Boolean = controller.canRedo()

    fun undo(): Boolean = controller.undo().also { if (it) { pruneSelection(); revision++ } }
    fun redo(): Boolean = controller.redo().also { if (it) { pruneSelection(); revision++ } }

    /**
     * Drops selection entries whose objects no longer exist.
     *
     * Undo can remove an object that is currently selected, and a selection naming a missing object is how a
     * highlight ends up drawn around nothing while commands resolve against a target that cannot be found.
     */
    fun pruneSelection() {
        val live = document()
        val stale = selection.selection().filter { target ->
            controller.findObject(target.id) == null && controller.page(target.id) == null
        }
        stale.forEach { selection.deselect(it.id) }
        scope.activePageId?.let { pageId ->
            if (live.pages().none { it.id == pageId }) {
                scope.activePageId = live.pages().firstOrNull()?.id
            }
        }
        scope.selectedPageIds = scope.selectedPageIds.filter { id -> live.pages().any { it.id == id } }
    }

    // --- View axes -----------------------------------------------------------------------------------

    fun setIntent(intent: WorkingIntent) {
        view.setIntent(intent)
        instrumentation.onViewChanged(view.representation, intent)
        revision++
    }

    fun setRepresentation(representation: LayoutRepresentation) {
        view.setRepresentation(representation)
        instrumentation.onViewChanged(representation, view.intent)
        revision++
    }

    fun setZoom(zoom: Float) {
        view.setZoom(zoom)
        revision++
    }

    /**
     * Ensures the view can perform [action], switching representation if needed and recording the reason.
     *
     * Returns the transition so a caller can decide whether to proceed; nothing about selection, caret or
     * history changes either way.
     */
    fun prepareFor(action: LayoutSensitiveAction): ViewTransition {
        val transition = view.prepareFor(action)
        if (transition.switched) {
            notice = transition.message
            instrumentation.onViewChanged(transition.representation, view.intent)
            revision++
        }
        return transition
    }

    // --- Manipulation --------------------------------------------------------------------------------

    /**
     * Begins a direct manipulation of the current selection.
     *
     * Editing implies Edit intent and exact geometry, so both axes are settled here rather than leaving the
     * host to remember: a drag started in Adaptive Read would otherwise preview a position it cannot store.
     */
    fun beginManipulation(kind: ActiveManipulation.Kind): ActiveManipulation? {
        val action = when (kind) {
            ActiveManipulation.Kind.MOVE -> LayoutSensitiveAction.MOVE_OBJECT
            ActiveManipulation.Kind.RESIZE -> LayoutSensitiveAction.RESIZE_OBJECT
            ActiveManipulation.Kind.ROTATE -> LayoutSensitiveAction.ROTATE_OBJECT
        }
        prepareFor(action)
        if (view.intent != WorkingIntent.EDIT) setIntent(WorkingIntent.EDIT)
        val objects = scope.selectedObjects()
        if (objects.isEmpty()) return null
        return view.beginManipulation(objects, kind).also { revision++ }
    }

    /** The page the primary selected object lives on — the geometry snapping is resolved against. */
    fun manipulationPage(): DocumentPageBlock? =
        view.manipulation?.objectIds?.firstOrNull()?.let { controller.findObject(it)?.first }

    fun updateMove(dx: Float, dy: Float): ActiveManipulation? =
        view.updateMove(dx, dy, manipulationPage()).also { revision++ }

    fun updateResize(geometry: SceneGeometry, movingRight: Boolean, movingBottom: Boolean): ActiveManipulation? =
        view.updateResize(geometry, manipulationPage(), movingRight, movingBottom).also { revision++ }

    fun updateRotation(degrees: Float): ActiveManipulation? =
        view.updateRotation(degrees).also { revision++ }

    /**
     * Commits the completed manipulation as **one** canonical transaction, or nothing if it did not move.
     *
     * This is the only place a gesture reaches history: previewing per frame and committing once is what
     * makes a drag a single undo step instead of a hundred.
     */
    fun endManipulation(): Boolean {
        val kind = view.manipulation?.kind
        val geometries = view.endManipulation() ?: return false
        val label = when (kind) {
            ActiveManipulation.Kind.RESIZE -> if (geometries.size > 1) "Resize objects" else "Resize"
            ActiveManipulation.Kind.ROTATE -> if (geometries.size > 1) "Rotate objects" else "Rotate"
            else -> if (geometries.size > 1) "Move objects" else "Move"
        }
        val committed = controller.commitGeometries(geometries, label)
        instrumentation.onManipulationCommitted(label, geometries.size)
        revision++
        return committed
    }

    fun cancelManipulation() {
        view.cancelManipulation()
        revision++
    }

    fun renderGeometry(obj: SceneObject): SceneGeometry = view.renderGeometry(obj)

    // --- Selection -----------------------------------------------------------------------------------

    /** Selects at a page point, cycling through overlapping objects on repeat taps. */
    fun selectAt(pageId: String, px: Float, py: Float): String? {
        val current = scope.primaryObjectId()
        val hit = scope.hitSelect(pageId, px, py, current)
        if (hit == null) {
            // Tapping empty page area selects the page itself, which is how page-scoped controls become
            // reachable without a dedicated mode.
            scope.selectPage(pageId)
        }
        instrumentation.onSelectionChanged(selection.count, hit)
        revision++
        return hit
    }

    fun toggleAt(pageId: String, px: Float, py: Float): String? {
        val stack = scope.hitStack(pageId, px, py)
        val top = stack.firstOrNull() ?: return null
        scope.toggleObject(top.id)
        instrumentation.onSelectionChanged(selection.count, top.id)
        revision++
        return top.id
    }

    /** Replaces the object selection — the route a marquee, list row or keyboard select-all lands on. */
    fun selectObjects(ids: List<String>) {
        scope.selectObjects(ids)
        instrumentation.onSelectionChanged(selection.count, ids.firstOrNull())
        revision++
    }

    /** Selects a page, making it the active page page-scoped commands resolve against. */
    fun selectPage(pageId: String) {
        scope.selectPage(pageId)
        instrumentation.onSelectionChanged(selection.count, pageId)
        revision++
    }

    /** Sets the explicitly selected pages — the "Selected pages" Page Control scope. */
    fun setSelectedPages(pageIds: List<String>) {
        scope.selectedPageIds = pageIds.filter { id -> pages().any { it.id == id } }
        revision++
    }

    /** The accessibility actions for [obj], as registered command ids that are invokable right now. */
    fun accessibilityActions(obj: SceneObject): List<Pair<String, String>> =
        DocumentCommands.accessibilityActionsFor(obj).mapNotNull { id ->
            commands.get(id)?.let { command -> id to command.label }
        }

    // --- Presentation --------------------------------------------------------------------------------

    /**
     * Applies a form-factor change. Open tools keep their exact state; only their presentation mode moves.
     */
    fun onFormFactorChanged(next: FormFactor) {
        val presented = presentation.onFormFactorChanged(next)
        instrumentation.onPresentationChanged(presentation.modeFor("document"), next)
        // A reflow must not abandon an in-progress gesture silently mid-drag; the gesture owns its own
        // lifecycle and a rotation does not end it.
        if (presented.isEmpty()) return
    }

    /** The pages that must be composed for the current scroll position. */
    fun pageWindow(scrollOffset: Float, viewportHeight: Float): PageWindow {
        val window = PageVirtualizer.window(pages(), scrollOffset, viewportHeight)
        instrumentation.onPageWindowChanged(window.firstIndex, window.lastIndex, pages().size)
        return window
    }

    // --- Legacy migration ----------------------------------------------------------------------------

    /**
     * Migrates the open legacy document to a bounded scene, protected by a recovery snapshot.
     *
     * The order is the whole point (§9): snapshot the original **first**, then build the candidate,
     * then commit — so the pre-image exists before anything can go wrong, and a failure anywhere
     * downstream leaves a restorable copy rather than a half-migrated document. Nothing here rewrites
     * the legacy artifact; the candidate replaces the *workspace* document through the canonical
     * History Engine, so the migration is one undoable transaction like any other edit.
     *
     * Refuses rather than improvises when the migration would lose content that cannot be
     * reconstructed: a report that is not lossless is returned unapplied, so a caller can show the
     * user what would be dropped instead of dropping it and reporting success.
     *
     * @return the report, or null when the document is already migrated.
     */
    fun migrateLegacyDocument(
        layout: Map<String, com.norfold.app.domain.FreeformPlacement> = emptyMap(),
        canvas: com.norfold.app.domain.DocCanvasSpec = com.norfold.app.domain.DocCanvasSpec(),
        mode: com.norfold.app.domain.DocOverlapMode = com.norfold.app.domain.DocOverlapMode.Bounded,
        recovery: RecoverySnapshotManager? = null,
        allowLossy: Boolean = false,
    ): LegacyDocumentMigration.Report? {
        val original = document()
        if (LegacyDocumentMigration.isMigrated(original)) return null

        // Before anything else: the pre-image. A snapshot taken after the candidate is built protects
        // nothing, because the risky step is the commit and the risk begins the moment work starts.
        recovery?.snapshot(
            reason = RecoveryReason.MIGRATION,
            targets = listOf(controller.target),
            label = "Before bounded-document migration",
        )

        val report = LegacyDocumentMigration.migrate(original, layout, canvas, mode)
        if (!report.isLossless && !allowLossy) {
            lastFailure = "Migration would discard content — review before continuing"
            revision++
            return report
        }

        controller.replaceDocument(report.document, "Convert to bounded document")
        // The old selection named blocks that are now *inside* scene objects; keeping it would leave
        // commands resolving against ids that no longer identify anything selectable.
        selection.clear()
        scope.activePageId = pages().firstOrNull()?.id
        scope.selectedPageIds = emptyList()
        notice = report.summary()
        revision++
        return report
    }

    // --- Command views -------------------------------------------------------------------------------

    /** The context dock for the current selection: object actions when an object is selected, else pages. */
    fun dockCommands(): List<DocumentCommandItem> {
        val ids = if (scope.selectedObjectIds().isEmpty()) PAGE_DOCK_ORDER else OBJECT_DOCK_ORDER
        return itemsOf(ids, CommandSurface.DOCK)
    }

    /** Every page-setup control, for the Page Control surface. */
    fun pageControlCommands(): List<DocumentCommandItem> =
        itemsOf(commands.all().map { it.id }.filter { it.startsWith("document.page.") }, CommandSurface.DOCK)

    /**
     * The Page Control rows resolved at [pageScope] — the same controls, answering for the pages the user
     * chose to apply them to.
     *
     * Built on demand rather than read from the registry because the registry holds each control once, at
     * [PageScope.CurrentPage]. Asking a current-page command what it would do to the whole document would
     * report the current page's answer, so a locked first page would grey out a control that is perfectly
     * available for the other forty.
     */
    fun pageControlCommands(pageScope: PageScope): List<DocumentCommandItem> {
        val context = scope.commandContext(CommandSurface.INSPECTOR)
        val structure = listOf(
            SetPageStructureLockCommand(scope, locked = false, pageScope = pageScope),
            SetPageStructureLockCommand(scope, locked = true, pageScope = pageScope),
        )
        val setup = DocumentCommands.PAGE_SETUP_CONTROLS.map {
            DocumentCommands.pageSetupCommand(scope, it, pageScope)
        }
        val groupOf = DocumentCommands.PAGE_SETUP_CONTROLS.associate { it.id to it.group }
        return (structure + setup).map { command ->
            DocumentCommandItem(
                id = command.id,
                label = command.label,
                description = command.description,
                category = groupOf[command.id] ?: "Structure",
                status = command.status(context),
            )
        }.filter { it.status.isVisible }
    }

    /**
     * Invokes a Page Control row at [pageScope].
     *
     * A scoped command is constructed and executed here rather than looked up, for the same reason the rows
     * are: the registered instance is bound to the current page. It is still the *same* definition — one
     * change function, one status rule, one canonical transaction — so a scoped apply and a slash-menu apply
     * differ only in which pages they name.
     */
    fun invokePageControl(
        commandId: String,
        pageScope: PageScope,
        surface: CommandSurface = CommandSurface.INSPECTOR,
    ): CommandResult {
        val command = when (commandId) {
            "document.page.lockStructure" -> SetPageStructureLockCommand(scope, true, pageScope)
            "document.page.unlockStructure" -> SetPageStructureLockCommand(scope, false, pageScope)
            else -> DocumentCommands.PAGE_SETUP_CONTROLS.firstOrNull { it.id == commandId }
                ?.let { DocumentCommands.pageSetupCommand(scope, it, pageScope) }
        } ?: return invoke(commandId, surface)

        val result = command.execute(scope.commandContext(surface))
        instrumentation.onCommandInvoked(commandId, surface, result)
        lastFailure = (result as? CommandResult.Failure)?.reason
        // A scope change that only altered the new-page template says so, because nothing on screen moved.
        notice = (result as? CommandResult.Success)?.message ?: notice
        pruneSelection()
        revision++
        return result
    }

    /** The scope Page Control currently applies changes at. */
    var pageScope: PageScope = PageScope.CurrentPage
        private set

    fun setPageScope(next: PageScope) {
        pageScope = next
        revision++
    }

    /**
     * The accessibility action rows for [obj], resolved against [obj] itself rather than the live selection.
     *
     * Status depends on the target, so asking "what can TalkBack offer for this object" while a *different*
     * object is selected would report the wrong answer. It is resolved through a hypothetical context rather
     * than by selecting [obj] and restoring afterwards, because clearing the selection also drops the text
     * caret — building an action list must not move the user's insertion point.
     */
    fun accessibilityItemsFor(obj: SceneObject): List<DocumentCommandItem> =
        itemsIn(
            DocumentCommands.accessibilityActionsFor(obj),
            scope.contextForObject(obj.id, CommandSurface.ACCESSIBILITY),
        )

    private fun itemsOf(ids: List<String>, surface: CommandSurface): List<DocumentCommandItem> =
        itemsIn(ids, scope.commandContext(surface))

    private fun itemsIn(ids: List<String>, context: CommandContext): List<DocumentCommandItem> =
        ids.mapNotNull { id -> commands.get(id) }.map { command ->
            DocumentCommandItem(
                id = command.id,
                label = command.label,
                description = command.description,
                category = command.category,
                status = command.status(context),
            )
        }.filter { it.status.isVisible }

    // --- Snapshot ------------------------------------------------------------------------------------

    /** Everything the Compose shell needs, resolved once per [revision]. */
    fun uiState(): DocumentHostUiState = DocumentHostUiState(
        revision = revision,
        document = document(),
        pages = pages(),
        selectedObjectIds = scope.selectedObjectIds(),
        primaryObjectId = scope.primaryObjectId(),
        activePageId = scope.activePage()?.id,
        selectedPageIds = scope.selectedPageIds,
        representation = view.representation,
        intent = view.intent,
        zoom = view.zoom,
        manipulation = view.manipulation,
        formFactor = presentation.formFactor,
        presentationMode = presentation.modeFor("document"),
        dock = dockCommands(),
        pageControls = pageControlCommands(pageScope),
        pageScope = pageScope,
        pageScopeCount = pageScope.resolve(scope).size,
        canUndo = canUndo(),
        canRedo = canRedo(),
        notice = notice,
        lastFailure = lastFailure,
    )

    companion object {
        /** The touch dock when an object is selected: the actions a phone user reaches for directly. */
        val OBJECT_DOCK_ORDER = listOf(
            "document.object.bringForward",
            "document.object.sendBackward",
            "document.object.bringToFront",
            "document.object.sendToBack",
            "document.object.duplicate",
            "document.object.lock",
            "document.object.unlock",
            "document.object.delete",
        )

        /** The touch dock when nothing but a page is selected — page structure, not object actions. */
        val PAGE_DOCK_ORDER = listOf(
            "document.page.insertAfter",
            "document.page.insertBefore",
            "document.page.duplicate",
            "document.page.moveUp",
            "document.page.moveDown",
            "document.page.delete",
        )
    }
}

/**
 * Observation points for DocumentHost (roadmap Phase 2 §14).
 *
 * Every hook fires from the runtime, never a Compose callback, so what is measured is what happened to the
 * shared engines. [onPageWindowChanged] is the one that matters most for §14: a host that quietly composed
 * every page would report the full range on every scroll, and no screenshot would reveal it.
 */
interface DocumentHostInstrumentation {
    fun onCommandInvoked(commandId: String, surface: CommandSurface, result: CommandResult) {}
    fun onSelectionChanged(count: Int, primaryId: String?) {}
    fun onManipulationCommitted(label: String, objectCount: Int) {}
    fun onViewChanged(representation: LayoutRepresentation, intent: WorkingIntent) {}
    fun onPresentationChanged(
        mode: com.norfold.app.editor.presentation.PresentationMode,
        formFactor: FormFactor,
    ) {}

    /** The page range actually being composed. Proves rendering stays lazy for large documents. */
    fun onPageWindowChanged(firstIndex: Int, lastIndex: Int, totalPages: Int) {}

    companion object {
        val None: DocumentHostInstrumentation = object : DocumentHostInstrumentation {}
    }
}

/** Records every hook in order, for tests and a future diagnostics screen. */
class RecordingDocumentHostInstrumentation : DocumentHostInstrumentation {
    private val entries = mutableListOf<String>()

    fun events(): List<String> = entries.toList()
    fun clear() = entries.clear()

    override fun onCommandInvoked(commandId: String, surface: CommandSurface, result: CommandResult) {
        val outcome = when (result) {
            is CommandResult.Success -> "success(${result.affectedCount})"
            is CommandResult.Failure -> "failure"
            CommandResult.NoOp -> "noop"
        }
        entries += "command:$commandId:$surface:$outcome"
    }

    override fun onSelectionChanged(count: Int, primaryId: String?) {
        entries += "selection:$count:${primaryId ?: "-"}"
    }

    override fun onManipulationCommitted(label: String, objectCount: Int) {
        entries += "manipulation:$label:$objectCount"
    }

    override fun onViewChanged(representation: LayoutRepresentation, intent: WorkingIntent) {
        entries += "view:$representation:$intent"
    }

    override fun onPageWindowChanged(firstIndex: Int, lastIndex: Int, totalPages: Int) {
        entries += "pages:$firstIndex-$lastIndex/$totalPages"
    }
}
