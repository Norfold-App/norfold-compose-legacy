package com.norfold.app.editor.document

import com.norfold.app.domain.BlockDocument
import com.norfold.app.domain.DocumentPageBlock
import com.norfold.app.domain.SceneGeometry
import com.norfold.app.domain.SceneObject
import com.norfold.app.domain.pages
import com.norfold.app.editor.command.CommandRegistry
import com.norfold.app.editor.command.CommandResult
import com.norfold.app.editor.command.CommandSurface
import com.norfold.app.editor.memory.InteractionMemory
import com.norfold.app.editor.presentation.AdaptivePresentationRuntime
import com.norfold.app.editor.presentation.FormFactor
import com.norfold.app.editor.surface.SurfaceCoordinator
import com.norfold.app.editor.selection.SelectionEngine

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

    /** A message to show the user once, e.g. after an automatic Adaptive → Original transition. */
    var notice: String? = null
        private set

    fun consumeNotice(): String? = notice.also { notice = null }

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
        return result
    }

    fun canUndo(): Boolean = controller.canUndo()
    fun canRedo(): Boolean = controller.canRedo()

    fun undo(): Boolean = controller.undo().also { if (it) pruneSelection() }
    fun redo(): Boolean = controller.redo().also { if (it) pruneSelection() }

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
    }

    fun setRepresentation(representation: LayoutRepresentation) {
        view.setRepresentation(representation)
        instrumentation.onViewChanged(representation, view.intent)
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
        return view.beginManipulation(objects, kind)
    }

    /** The page the primary selected object lives on — the geometry snapping is resolved against. */
    fun manipulationPage(): DocumentPageBlock? =
        view.manipulation?.objectIds?.firstOrNull()?.let { controller.findObject(it)?.first }

    fun updateMove(dx: Float, dy: Float): ActiveManipulation? = view.updateMove(dx, dy, manipulationPage())

    fun updateResize(geometry: SceneGeometry, movingRight: Boolean, movingBottom: Boolean): ActiveManipulation? =
        view.updateResize(geometry, manipulationPage(), movingRight, movingBottom)

    fun updateRotation(degrees: Float): ActiveManipulation? = view.updateRotation(degrees)

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
        return committed
    }

    fun cancelManipulation() = view.cancelManipulation()

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
        return hit
    }

    fun toggleAt(pageId: String, px: Float, py: Float): String? {
        val stack = scope.hitStack(pageId, px, py)
        val top = stack.firstOrNull() ?: return null
        scope.toggleObject(top.id)
        instrumentation.onSelectionChanged(selection.count, top.id)
        return top.id
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
