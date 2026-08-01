package com.norfold.app.editor.document

import com.norfold.app.domain.BlockDocument
import com.norfold.app.domain.DocumentPageBlock
import com.norfold.app.domain.ResizeCorner
import com.norfold.app.domain.SceneGeometry
import com.norfold.app.domain.SceneObject
import com.norfold.app.domain.SceneObjectKind
import com.norfold.app.domain.ShapeBlock
import com.norfold.app.domain.supportsStyle
import com.norfold.app.domain.pageNumberLabels
import com.norfold.app.domain.pages
import com.norfold.app.editor.command.CommandContext
import com.norfold.app.editor.command.CommandRegistry
import com.norfold.app.editor.command.CommandResult
import com.norfold.app.editor.command.CommandState
import com.norfold.app.editor.command.CommandStatus
import com.norfold.app.editor.command.CommandSurface
import com.norfold.app.editor.command.EditorCommand
import com.norfold.app.editor.flow.FlowShortcut
import com.norfold.app.editor.memory.InteractionMemory
import com.norfold.app.editor.presentation.AdaptivePresentationRuntime
import com.norfold.app.editor.presentation.FormFactor
import com.norfold.app.editor.recovery.RecoveryReason
import com.norfold.app.editor.recovery.RecoverySnapshotManager
import com.norfold.app.editor.surface.BackOutcome
import com.norfold.app.editor.surface.DismissTrigger
import com.norfold.app.editor.surface.DispatchResult
import com.norfold.app.editor.surface.SurfaceClass
import com.norfold.app.editor.surface.SurfaceCoordinator
import com.norfold.app.editor.surface.ToolSurface
import com.norfold.app.editor.selection.SelectionEngine
import com.norfold.app.editor.style.EditorStyleRuntime
import com.norfold.app.editor.style.EditorStyleSelection
import com.norfold.app.editor.style.EditorStyleUiState
import com.norfold.app.editor.style.StyleController
import com.norfold.app.editor.style.EditorStyleCommands
import com.norfold.app.domain.style.FontRegistry
import com.norfold.app.domain.style.ColorRole

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

/** One value-driven Page Control row: its current value, whether the scope disagrees, and its status. */
data class DocumentPageValueItem(
    val id: String,
    val label: String,
    val category: String,
    val kind: PageValueControl.Kind,
    /** The value to seed the field with; empty when the scope's pages disagree. */
    val value: String,
    val mixed: Boolean,
    val status: CommandStatus,
) {
    val enabled: Boolean get() = status.isInvokable
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
    val panX: Float,
    val manipulation: ActiveManipulation?,
    /** The object whose text content is being edited, or null. Never entered by a first tap. */
    val editingObjectId: String?,
    /** True while object multi-select is active — the count bar with Cancel/Done is showing. */
    val selectionMode: Boolean,
    val formFactor: FormFactor,
    val presentationMode: com.norfold.app.editor.presentation.PresentationMode,
    /** The tool surface the coordinator says is active, or null when only the editor is showing. */
    val activeSurfaceId: String?,
    val dock: List<DocumentCommandItem>,
    val pageControls: List<DocumentCommandItem>,
    /** The value-driven Page Control rows, each seeded with the scope's current value. */
    val pageValueControls: List<DocumentPageValueItem>,
    /** The scope [pageControls] were resolved at, and that invoking one of them will apply. */
    val pageScope: PageScope,
    /** How many pages the current scope names — what "Apply settings to…" is actually promising. */
    val pageScopeCount: Int,
    /** The durable new-page template, or null while new pages inherit from the page they follow. */
    val newPageTemplate: com.norfold.app.domain.PageGeometry?,
    /** Each page's printed number, honouring section restarts and per-page format. */
    val pageNumberLabels: List<String>,
    val canUndo: Boolean,
    val canRedo: Boolean,
    val notice: String?,
    val lastFailure: String?,
    val editorStyle: EditorStyleUiState,
) {
    /** The primary selected object, which is the only one that exposes individual manipulation handles. */
    val primaryObject: SceneObject?
        get() = primaryObjectId?.let { id -> pages.firstNotNullOfOrNull { it.objectById(id) } }
}

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
    fontRegistry: FontRegistry = EditorStyleRuntime.EMPTY_REGISTRY,
) {
    val selection = SelectionEngine()
    val scope = DocumentCommandScope(controller, selection)
    val commands: CommandRegistry = DocumentCommands.registerAll(scope)
    val surfaces = SurfaceCoordinator()
    val presentation = AdaptivePresentationRuntime(initialFormFactor)
    val memory = InteractionMemory()
    val view = DocumentViewState()
    val styleController = StyleController(
        mutate = controller::mutateDocument,
        documentProvider = controller::document,
    )
    val editorStyle = EditorStyleRuntime(
        host = "document",
        controller = styleController,
        registry = fontRegistry,
        selection = {
            val objects = scope.selectedObjectIds().mapNotNull(controller::findObject).map { it.second }
            val pageIds = scope.selectedPageIds.ifEmpty { listOfNotNull(scope.activePage()?.id) }
            EditorStyleSelection(
                textBlockIds = objects.map { it.content }.filter { it.supportsStyle() && it !is ShapeBlock }.map { it.id },
                shapeBlockIds = objects.mapNotNull { (it.content as? ShapeBlock)?.id },
                pageIds = pageIds,
                selectedText = objects.joinToString("\n") { it.content.plainText() }.take(256),
            )
        },
        onChanged = { revision++ },
    )

    init {
        EditorStyleCommands.register(
            registry = commands,
            textColor = { openStyleColor(ColorRole.TEXT) },
            highlight = { openStyleColor(ColorRole.HIGHLIGHT) },
            fill = { openStyleColor(ColorRole.FILL) },
            stroke = { openStyleColor(ColorRole.STROKE) },
            pageBackground = { openStyleColor(ColorRole.PAGE_BACKGROUND) },
            fontBrowser = { openStyleFont() },
            namedStyles = { openNamedStyles() },
            clearFormatting = {
                val count = editorStyle.clearFormatting()
                if (count > 0) CommandResult.Success(count) else CommandResult.NoOp
            },
        )
    }

    private fun openStyleColor(role: ColorRole): CommandResult {
        if (!editorStyle.openColor(role)) return CommandResult.Failure("Select compatible content first")
        openSurface(DocumentSurfaces.COLOR_STUDIO, SurfaceClass.CONTINUOUS_TOOL)
        return CommandResult.Success(scope.selectedObjectIds().size.coerceAtLeast(1))
    }

    private fun openStyleFont(): CommandResult {
        if (!editorStyle.openFont()) return CommandResult.Failure("Select text first")
        openSurface(DocumentSurfaces.FONT_BROWSER, SurfaceClass.PICKER_BROWSER)
        return CommandResult.Success(scope.selectedObjectIds().size.coerceAtLeast(1))
    }

    private fun openNamedStyles(): CommandResult {
        if (scope.selectedObjectIds().isEmpty()) return CommandResult.Failure("Select content first")
        openSurface(DocumentSurfaces.NAMED_STYLES, SurfaceClass.PICKER_BROWSER)
        return CommandResult.Success(scope.selectedObjectIds().size)
    }

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
        // A caret in a deleted object is a keyboard open over content that no longer exists, and every
        // keystroke it accepts would be discarded silently. Undo can remove the object being edited, so this
        // has to be checked here rather than only where a delete is issued.
        editingObjectId?.let { if (controller.findObject(it) == null) editingObjectId = null }
    }

    // --- View axes -----------------------------------------------------------------------------------

    /**
     * Switches the working intent.
     *
     * Read mode has to be *actually* read-only, not merely labelled so. Leaving a content edit open would
     * leave a live text field and a caret on screen in the mode whose entire promise is that nothing can be
     * changed — and the next keystroke would be committed to the document. Ending the edit here rather than
     * asking the host to remember is what makes that a property of the runtime instead of a convention.
     *
     * A manipulation in flight is **cancelled**, not committed: the user switched away from editing, so a
     * half-finished drag is something they abandoned rather than something they meant to keep.
     *
     * The selection survives. What is selected is a question about attention, not about editing, and dropping
     * it would make flicking to Read and back lose the user's place.
     */
    fun setIntent(intent: WorkingIntent) {
        if (intent != WorkingIntent.EDIT) {
            editingObjectId = null
            if (view.manipulation != null) view.cancelManipulation()
        }
        view.setIntent(intent)
        instrumentation.onViewChanged(view.representation, intent)
        revision++
    }

    /**
     * Switches the layout representation.
     *
     * Content editing ends on the way into Adaptive View for the same reason it ends on the way into Read:
     * the host only renders a live field in Original View, so a caret left set here would be state pointing at
     * a text field that is no longer on screen — the keyboard would stay up over an adapted layout, and Back
     * would spend a press dismissing an edit the user cannot see.
     */
    fun setRepresentation(representation: LayoutRepresentation) {
        if (representation != LayoutRepresentation.ORIGINAL) editingObjectId = null
        view.setRepresentation(representation)
        instrumentation.onViewChanged(representation, view.intent)
        revision++
    }

    fun setZoom(zoom: Float) {
        view.setZoom(zoom)
        revision++
    }

    fun setPan(panX: Float) {
        view.setPan(panX)
        revision++
    }

    /**
     * The measured viewport in page points.
     *
     * Reported by the host because only the composable knows how big it is, and remembered here because Fit
     * width and Fit page are commands that can be invoked from a chip, a keyboard chord or an accessibility
     * action — none of which are in a position to measure anything.
     */
    private var viewportWidthPt: Float = 0f
    private var viewportHeightPt: Float = 0f

    fun reportViewport(widthPt: Float, heightPt: Float) {
        viewportWidthPt = widthPt
        viewportHeightPt = heightPt
    }

    /** Zooms so the widest page fills the viewport. Stored page geometry is never touched. */
    fun fitWidth(): Float {
        setRepresentationIfNeeded()
        return view.fitWidth(pages(), viewportWidthPt).also { revision++ }
    }

    /** Zooms so the active page fits entirely. Stored page geometry is never touched. */
    fun fitPage(): Float {
        setRepresentationIfNeeded()
        return view.fitPage(scope.activePage(), viewportWidthPt, viewportHeightPt).also { revision++ }
    }

    /** Zoom is an Original-View concept; asking to fit while adapted is asking to see the real page. */
    private fun setRepresentationIfNeeded() {
        if (view.representation != LayoutRepresentation.ORIGINAL) {
            view.setRepresentation(LayoutRepresentation.ORIGINAL)
        }
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
        // Manipulating an object ends editing its text: the caret and the drag cannot both own it, and leaving
        // a field focused mid-drag keeps the keyboard up over the thing being moved. Typing already committed
        // through its own coalescing transaction, so nothing is lost by leaving.
        if (editingObjectId != null && objects.any { it.id == editingObjectId }) editingObjectId = null
        return view.beginManipulation(objects, kind).also { revision++ }
    }

    /** The page the primary selected object lives on — the geometry snapping is resolved against. */
    fun manipulationPage(): DocumentPageBlock? =
        view.manipulation?.objectIds?.firstOrNull()?.let { controller.findObject(it)?.first }

    fun updateMove(dx: Float, dy: Float): ActiveManipulation? =
        view.updateMove(dx, dy, manipulationPage()).also { revision++ }

    /** Drags [corner] to a page point. The opposite corner stays anchored; see [SceneGeometry.resizedTo]. */
    fun updateResize(corner: ResizeCorner, px: Float, py: Float): ActiveManipulation? =
        view.updateResize(corner, px, py, manipulationPage()).also { revision++ }

    fun updateRotation(degrees: Float): ActiveManipulation? =
        view.updateRotation(degrees).also { revision++ }

    /**
     * Applies one geometry field to the primary selected object as **one** transaction — the inspector route.
     *
     * A begin/preview/commit round trip rather than a direct write, so a typed value and a dragged corner
     * reach history through exactly the same path and produce exactly the same kind of entry. Returns false
     * when the value is unusable, and in that case nothing is begun, previewed or committed: a rejected value
     * must leave the object where it was, not clamp it somewhere.
     */
    fun applyGeometryField(field: GeometryInspector.Field, raw: String): Boolean {
        // A gesture in flight owns this object's geometry: committing a typed value underneath it would be
        // overwritten the moment the drag released, because the drag commits from the geometry it *started*
        // from. Ending the gesture first makes the two edits sequential rather than racing — and the object
        // is re-read afterwards, because ending it is exactly what changed the geometry being edited.
        if (view.manipulation != null) endManipulation()
        val obj = scope.primaryObject() ?: return false
        if (field.isLockedFor(obj)) {
            lastFailure = field.lockReasonFor(obj)
            revision++
            return false
        }
        val value = GeometryInspector.parse(field, raw)
        if (value == null) {
            lastFailure = "'$raw' is not a valid ${field.label}"
            revision++
            return false
        }
        val next = GeometryInspector.apply(obj.geometry, field, value)
        if (next == obj.geometry) return false
        val label = when (field) {
            GeometryInspector.Field.ROTATION -> "Rotate"
            GeometryInspector.Field.WIDTH, GeometryInspector.Field.HEIGHT -> "Resize"
            else -> "Move"
        }
        lastFailure = null
        val committed = controller.commitGeometry(obj.id, next, label)
        instrumentation.onManipulationCommitted(label, 1)
        revision++
        return committed
    }

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

    /**
     * Toggles [pageId] in/out of the explicit page selection — the visible route to "Selected pages".
     *
     * Without this, that Page Control scope resolved to nothing a user could ever populate: it existed in the
     * catalogue and silently fell back to the current page, which made choosing it look broken.
     */
    fun togglePageSelection(pageId: String) {
        if (pages().none { it.id == pageId }) return
        val current = scope.selectedPageIds
        scope.selectedPageIds = if (pageId in current) current - pageId else current + pageId
        // The page a multi-page change is anchored at should be one the user actually named.
        if (pageId in scope.selectedPageIds) scope.activePageId = pageId
        instrumentation.onSelectionChanged(scope.selectedPageIds.size, pageId)
        revision++
    }

    fun clearPageSelection() {
        scope.selectedPageIds = emptyList()
        revision++
    }

    // --- Object multi-select ------------------------------------------------------------------------

    /**
     * Whether object multi-select is active.
     *
     * A real mode rather than an implicit consequence of two things being selected, because the user needs a
     * visible count and an explicit Cancel/Done — and because Cancel must restore what was selected *before*
     * the mode, which is impossible to do without knowing when the mode began.
     */
    var selectionMode: Boolean = false
        private set

    private var selectionRestore: List<String>? = null

    val selectedObjectCount: Int get() = scope.selectedObjectIds().size

    /** Enters multi-select, optionally seeded with [anchorObjectId], capturing what to restore on Cancel. */
    fun enterSelectionMode(anchorObjectId: String? = null) {
        if (!selectionMode) {
            selectionRestore = scope.selectedObjectIds()
            selectionMode = true
        }
        anchorObjectId?.let { scope.selectObjects(listOf(it)) }
        instrumentation.onSelectionChanged(selectedObjectCount, anchorObjectId)
        revision++
    }

    /** Toggles an object in/out of the multi-selection, entering the mode if it is not already active. */
    fun toggleObjectSelection(objectId: String) {
        if (!selectionMode) enterSelectionMode(null)
        val current = scope.selectedObjectIds()
        scope.selectObjects(if (objectId in current) current - objectId else current + objectId)
        instrumentation.onSelectionChanged(selectedObjectCount, objectId)
        revision++
    }

    /** Cancel: discards the multi-selection and restores exactly what was selected before the mode began. */
    fun cancelSelectionMode() {
        val restore = selectionRestore
        selectionMode = false
        selectionRestore = null
        scope.selectObjects(restore.orEmpty())
        instrumentation.onSelectionChanged(selectedObjectCount, restore?.firstOrNull())
        revision++
    }

    /** Done: leaves the mode keeping the selection, so a follow-up command still has its targets. */
    fun finishSelectionMode() {
        selectionMode = false
        selectionRestore = null
        instrumentation.onSelectionChanged(selectedObjectCount, scope.primaryObjectId())
        revision++
    }

    /** Selects every object on the active page — the Ctrl+A route. */
    fun selectAllOnActivePage() {
        val page = scope.activePage() ?: return
        if (page.objects.isEmpty()) return
        if (!selectionMode) enterSelectionMode(null)
        scope.selectObjects(page.objects.map { it.id })
        instrumentation.onSelectionChanged(selectedObjectCount, page.objects.firstOrNull()?.id)
        revision++
    }

    // --- Surfaces and the back hierarchy -------------------------------------------------------------

    /**
     * Opens a tool surface through the shared [SurfaceCoordinator].
     *
     * Page Control and the geometry inspector go through the coordinator rather than a Compose `remember`,
     * which is what makes Back dismiss them by the coordinator's own policy and what lets a form-factor
     * change re-present them without their state being rebuilt from scratch.
     */
    fun openSurface(surfaceId: String, surfaceClass: SurfaceClass = SurfaceClass.PICKER_BROWSER): ToolSurface {
        val existing = surfaces.openSurfaces().firstOrNull { it.id == surfaceId }
        if (existing != null) return existing
        val surface = ToolSurface(
            id = surfaceId,
            surfaceClass = surfaceClass,
            compatibleDomains = setOf(DocumentSurfaces.DOMAIN),
        )
        if (surfaces.active() == null) surfaces.open(surface) else surfaces.openChild(surface)
        presentation.present(
            com.norfold.app.editor.presentation.ToolPresentationState(surfaceId),
            compactPreferenceFor(surfaceId),
        )
        revision++
        return surface
    }

    fun closeSurface(surfaceId: String, trigger: DismissTrigger = DismissTrigger.CANCEL) {
        if (surfaces.active()?.id == surfaceId) {
            dismiss(trigger)
        } else {
            presentation.close(surfaceId)
            revision++
        }
    }

    /** Applies [trigger] to the active surface **through its own policy** — never a blanket close (§5.3). */
    fun dismiss(trigger: DismissTrigger): DispatchResult {
        val result = surfaces.dispatch(trigger)
        if (result is DispatchResult.Dismissed) {
            presentation.close(result.closed.id)
            if (result.closed.id == DocumentSurfaces.COLOR_STUDIO) editorStyle.closeColor(cancelAdjustment = true)
            revision++
        }
        return result
    }

    fun activeSurfaceId(): String? = surfaces.active()?.id

    /**
     * One Back/Escape press against the whole hierarchy:
     * surface → multi-select → live gesture → **content editing** → selection → editor.
     *
     * Routed through the coordinator so DocumentHost unwinds in exactly the order FlowHost does. Two levels
     * would otherwise be missing. A live gesture owns Back before anything else, because cancelling the drag
     * is what the user is asking for. And **content editing is resolved before the selection is cleared**: a
     * user typing in a text frame who presses Back expects the caret to leave, not the object to be
     * deselected and the caret to be orphaned behind it. Clearing the selection first is how a second Back
     * ended up leaving the editor while a text field was still focused.
     */
    fun back(): DocumentBackResult {
        val outcome = surfaces.back(hasSelectionMode = selectionMode)
        revision++
        return when (outcome) {
            is BackOutcome.ClosedSurface -> {
                presentation.close(outcome.closed.id)
                if (outcome.closed.id == DocumentSurfaces.COLOR_STUDIO) editorStyle.closeColor(cancelAdjustment = true)
                DocumentBackResult.ClosedSurface(outcome.closed.id, outcome.nowActive?.id)
            }
            is BackOutcome.Consumed -> DocumentBackResult.Consumed(outcome.surface.id)
            BackOutcome.ExitedSelectionMode -> {
                cancelSelectionMode()
                DocumentBackResult.ExitedSelectionMode
            }
            BackOutcome.LeftEditor -> {
                // A live gesture owns Back before anything else does: cancelling the drag is what the user
                // is asking for, and leaving the editor mid-drag would commit nothing and lose the gesture.
                if (view.manipulation != null) {
                    cancelManipulation()
                    return DocumentBackResult.CancelledManipulation
                }
                // Text editing is an *inner* level of the same object's selection, so it unwinds first and
                // the selection is deliberately kept: the object stays selected, only the caret leaves.
                if (editingObjectId != null) {
                    endContentEdit()
                    return DocumentBackResult.ExitedContentEdit
                }
                if (scope.selectedObjectIds().isNotEmpty()) {
                    scope.selectObjects(emptyList())
                    revision++
                    return DocumentBackResult.ClearedSelection
                }
                DocumentBackResult.LeftEditor
            }
        }
    }

    private fun compactPreferenceFor(surfaceId: String?): com.norfold.app.editor.presentation.CompactPreference =
        when (surfaceId) {
            // Page Control is a browsable catalogue of dozens of controls; on a phone it needs a sheet, not
            // a popover it would overflow.
            DocumentSurfaces.PAGE_CONTROL -> com.norfold.app.editor.presentation.CompactPreference.SHEET
            DocumentSurfaces.COLOR_STUDIO, DocumentSurfaces.FONT_BROWSER, DocumentSurfaces.NAMED_STYLES ->
                com.norfold.app.editor.presentation.CompactPreference.SHEET
            else -> com.norfold.app.editor.presentation.CompactPreference.POPOVER
        }

    // --- Keyboard ------------------------------------------------------------------------------------

    /**
     * Handles one physical chord. Returns true when it was consumed.
     *
     * Every bound chord resolves to a **registered command id** and goes through [invoke], so there is no
     * keyboard-only behaviour anywhere: a chord whose command is disabled is inert for the same stated reason
     * the dock would give. Undo/redo and Escape are the only exceptions, and only because they are History
     * and surface-hierarchy operations rather than registry commands.
     *
     * [textEntryActive] decides who owns the key *before* any binding is consulted — see
     * [DocumentKeyboardMap.resolve]. The host passes what it knows about focus; the runtime contributes its
     * own content-editing state, so a caret inside a scene object protects that object's text even if the
     * host never reported anything.
     */
    fun handleShortcut(shortcut: FlowShortcut, textEntryActive: Boolean = false): Boolean {
        val owned = textEntryActive || editingObjectId != null
        return when (val action = DocumentKeyboardMap.resolve(shortcut, owned)) {
            DocumentKeyAction.Unhandled -> false
            DocumentKeyAction.Undo -> undo()
            DocumentKeyAction.Redo -> redo()
            DocumentKeyAction.Back -> {
                back()
                true
            }
            DocumentKeyAction.EnterSelectionMode -> {
                enterSelectionMode(scope.primaryObjectId())
                true
            }
            DocumentKeyAction.SelectAllObjects -> {
                selectAllOnActivePage()
                true
            }
            // Consumed even when the command refuses: the chord *is* bound, and letting it fall through would
            // send a Delete the editor decided not to act on to whatever is behind it.
            is DocumentKeyAction.Command -> {
                invoke(action.id, CommandSurface.KEYBOARD)
                true
            }
        }
    }

    // --- Object content ------------------------------------------------------------------------------

    /**
     * The object whose text is being edited, or null.
     *
     * Text editing is a deliberate second step, never the first tap: one tap selects the object so it can be
     * moved, resized and deleted, and only a second tap on an already-selected text frame enters its content.
     * Entering on the first tap would make every attempt to *move* a text frame drop a caret into it instead.
     */
    var editingObjectId: String? = null
        private set

    /**
     * Enters content editing for [objectId], or refuses.
     *
     * A content-locked object refuses and says so rather than silently doing nothing — "why can I not type in
     * this" is the question the lock badge exists to answer, and a no-op answers it badly.
     *
     * Entering deliberately **transitions to Edit intent** rather than refusing in Read mode: the user tapped
     * twice on a text frame, which is an unambiguous request to write in it, and bouncing that request while
     * silently staying in Read would read as the app being broken. What must never happen is the reverse — a
     * caret surviving *into* Read mode — and [setIntent] is where that is enforced.
     */
    fun beginContentEdit(objectId: String): Boolean {
        val obj = controller.findObject(objectId)?.second ?: return false
        if (!obj.canEditContent) {
            lastFailure = "This object's content is locked"
            revision++
            return false
        }
        if (obj.kind == SceneObjectKind.Preserved) {
            lastFailure = "This content was saved by another version and is kept exactly as stored"
            revision++
            return false
        }
        prepareFor(LayoutSensitiveAction.INSERT_AT_POSITION)
        if (view.intent != WorkingIntent.EDIT) setIntent(WorkingIntent.EDIT)
        // A drag and a caret cannot own the same object at once. Cancelling rather than committing is right:
        // the user's intent was to type, so a stray movement on the way to the tap must not be kept.
        if (view.manipulation != null) view.cancelManipulation()
        editingObjectId = objectId
        lastFailure = null
        revision++
        return true
    }

    fun endContentEdit() {
        if (editingObjectId == null) return
        editingObjectId = null
        revision++
    }

    /**
     * Applies a text edit to the object being edited, as a coalescing transaction.
     *
     * Routed through [DocumentSceneController.editObjectText] so typing inside a scene object produces the
     * same kind of history entry as typing in Flow: consecutive keystrokes in one field merge into one undo
     * step, and moving to a different field starts a new one.
     *
     * [blockId] is the block the caret is genuinely in, which for nested content — a paragraph inside a quote,
     * a callout or a container — is *not* the object's root block. Refused outright in Read mode: a keystroke
     * arriving while the document is nominally read-only means some field outlived the mode switch, and
     * committing it would write to a document the user believes is not being changed.
     */
    fun editObjectText(
        objectId: String,
        blockId: String,
        itemId: String?,
        before: String,
        after: String,
    ): Boolean {
        if (before == after) return false
        if (view.intent != WorkingIntent.EDIT) return false
        val edited = controller.editObjectText(objectId, blockId, itemId, before, after)
        if (edited) {
            lastFailure = null
            revision++
        }
        return edited
    }

    /** Toggles a to-do item inside a scene object's content, addressed by its own block id. */
    fun toggleObjectTodo(objectId: String, blockId: String, itemId: String): Boolean {
        if (view.intent != WorkingIntent.EDIT) return false
        return controller.toggleObjectTodo(objectId, blockId, itemId).also { if (it) revision++ }
    }

    /** The accessibility actions for [obj], as registered command ids that are invokable right now. */
    fun accessibilityActions(obj: SceneObject): List<Pair<String, String>> =
        DocumentCommands.accessibilityActionsFor(obj).mapNotNull { id ->
            commands.get(id)?.let { command -> id to command.label }
        }

    // --- Presentation --------------------------------------------------------------------------------

    /**
     * Applies a form-factor change. Open tools retain durable state; only their presentation mode moves.
     *
     * Nothing here rebuilds the session, the selection, the history, the representation/intent axes or an
     * scene gesture. Color Studio is intentionally stricter: an unfinished preview is exact-cancelled before
     * re-presentation, preventing a partial pointer gesture from being committed or committed twice while the
     * selected view, target, stop and previously committed History remain intact (§2.6, §5.1).
     */
    fun onFormFactorChanged(next: FormFactor) {
        if (presentation.formFactor == next) return
        editorStyle.onFormFactorChanged()
        presentation.onFormFactorChanged(next) { compactPreferenceFor(it) }
        instrumentation.onPresentationChanged(presentation.modeFor("document"), next)
        revision++
    }

    /** The pages that must be composed for the current scroll position. */
    fun pageWindow(scrollOffset: Float, viewportHeight: Float): PageWindow {
        val window = PageVirtualizer.window(pages(), scrollOffset, viewportHeight)
        instrumentation.onPageWindowChanged(window.firstIndex, window.lastIndex, pages().size)
        return window
    }

    // --- Legacy migration ----------------------------------------------------------------------------

    /**
     * The migration refusal that is currently blocking this document, or null.
     *
     * Held rather than merely returned, because the *host* has to act on it: a refused migration means this
     * runtime has no pages, and rendering it anyway would show the user a blank bounded document where their
     * content used to be. The host reads this and falls back to the legacy editor, which can still open the
     * original document unchanged.
     *
     * `allowLossy` is deliberately never set automatically anywhere in this class. Forcing a migration that
     * was refused is a decision only the owner of the content can make.
     */
    var migrationRefusal: LegacyDocumentMigration.Report? = null
        private set

    /** True when this runtime holds no usable bounded document because a migration was refused. */
    fun requiresLegacyFallback(): Boolean = migrationRefusal != null && pages().isEmpty()

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
            // Refused, and *recorded* as refused. The orphan information in the report is kept intact rather
            // than being summarized away, so the host can tell the user exactly what would be dropped — and
            // so nothing here can quietly retry with allowLossy on their behalf.
            migrationRefusal = report
            lastFailure = "Migration would discard content — review before continuing"
            revision++
            return report
        }

        controller.replaceDocument(report.document, "Convert to bounded document")
        migrationRefusal = null
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

    /**
     * The value-driven Page Control rows resolved at [pageScope], each carrying the value to seed its field.
     *
     * MIXED here is honest and load-bearing: with a multi-page scope "what is the top margin" often has no
     * single answer, and seeding the field with one page's value would invite the user to press Apply and
     * silently flatten the others to it.
     */
    fun pageValueControls(pageScope: PageScope): List<DocumentPageValueItem> {
        val context = scope.commandContext(CommandSurface.INSPECTOR)
        val pages = if (pageScope == PageScope.NewPagesByDefault) {
            listOf(scope.newPageTemplate())
        } else {
            pageScope.resolve(scope).mapNotNull { scope.controller.page(it)?.geometry }
        }
        return DocumentCommands.PAGE_VALUE_CONTROLS.map { control ->
            val values = pages.mapTo(LinkedHashSet()) { control.valueOf(it) }
            val command = DocumentCommands.pageValueCommand(scope, control, pageScope, raw = "")
            DocumentPageValueItem(
                id = control.id,
                label = control.label,
                category = control.group,
                kind = control.kind,
                value = values.singleOrNull() ?: "",
                mixed = values.size > 1,
                status = command.status(context),
            )
        }
    }

    /** Applies a value control at [pageScope] with the text the user typed. */
    fun invokePageValue(
        commandId: String,
        pageScope: PageScope,
        raw: String,
        surface: CommandSurface = CommandSurface.INSPECTOR,
    ): CommandResult {
        val control = DocumentCommands.PAGE_VALUE_CONTROLS.firstOrNull { it.id == commandId }
            ?: return CommandResult.Failure("Unknown page control '$commandId'")
        val command = DocumentCommands.pageValueCommand(scope, control, pageScope, raw)
        val result = command.execute(scope.commandContext(surface))
        instrumentation.onCommandInvoked(commandId, surface, result)
        lastFailure = (result as? CommandResult.Failure)?.reason
        notice = (result as? CommandResult.Success)?.message ?: notice
        pruneSelection()
        revision++
        return result
    }

    /** Clears the durable "New pages by default" template, restoring inherit-from-anchor. */
    fun clearNewPageTemplate(): Boolean {
        scope.clearNewPageTemplate()
        revision++
        return true
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
    fun uiState(): DocumentHostUiState {
        val pages = pages()
        return DocumentHostUiState(
            revision = revision,
            document = document(),
            pages = pages,
            selectedObjectIds = scope.selectedObjectIds(),
            primaryObjectId = scope.primaryObjectId(),
            activePageId = scope.activePage()?.id,
            selectedPageIds = scope.selectedPageIds,
            representation = view.representation,
            intent = view.intent,
            zoom = view.zoom,
            panX = view.panX,
            manipulation = view.manipulation,
            editingObjectId = editingObjectId,
            selectionMode = selectionMode,
            formFactor = presentation.formFactor,
            presentationMode = presentation.modeFor(
                activeSurfaceId().orEmpty(),
                compactPreferenceFor(activeSurfaceId()),
            ),
            activeSurfaceId = activeSurfaceId(),
            dock = dockCommands(),
            pageControls = pageControlCommands(pageScope),
            pageValueControls = pageValueControls(pageScope),
            pageScope = pageScope,
            pageScopeCount = pageScope.resolve(scope).size,
            newPageTemplate = controller.newPageTemplate(),
            pageNumberLabels = pages.pageNumberLabels(),
            canUndo = canUndo(),
            canRedo = canRedo(),
            notice = notice,
            lastFailure = lastFailure,
            editorStyle = editorStyle.uiState(),
        )
    }

    companion object {
        /**
         * The touch dock when an object is selected.
         *
         * Ordered by how often a phone user reaches for it, and **complete** — every entry here is rendered,
         * because a dock that silently drops its tail is a dock whose last actions (Unlock and Delete, the two
         * most consequential) are unreachable. The surface scrolls instead of truncating.
         */
        val OBJECT_DOCK_ORDER = listOf(
            DocumentIds.DUPLICATE,
            DocumentIds.BRING_FORWARD,
            DocumentIds.SEND_BACKWARD,
            DocumentIds.BRING_TO_FRONT,
            DocumentIds.SEND_TO_BACK,
            DocumentIds.LOCK,
            DocumentIds.UNLOCK,
            DocumentIds.DELETE,
        )

        /** The touch dock when nothing but a page is selected — insertion and page structure. */
        val PAGE_DOCK_ORDER = listOf(
            DocumentIds.INSERT_TEXT,
            DocumentIds.INSERT_SHAPE,
            DocumentIds.PAGE_INSERT_AFTER,
            DocumentIds.PAGE_INSERT_BEFORE,
            DocumentIds.PAGE_DUPLICATE,
            DocumentIds.PAGE_MOVE_UP,
            DocumentIds.PAGE_MOVE_DOWN,
            DocumentIds.PAGE_DELETE,
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
