package com.norfold.app.editor.flow

import com.norfold.app.domain.BlockCursor
import com.norfold.app.domain.BlockDocument
import com.norfold.app.domain.DropTarget
import com.norfold.app.domain.TodoListBlock
import com.norfold.app.domain.findById
import com.norfold.app.editor.command.CommandRegistry
import com.norfold.app.editor.command.CommandResult
import com.norfold.app.editor.command.CommandState
import com.norfold.app.editor.command.CommandStatus
import com.norfold.app.editor.command.CommandSurface
import com.norfold.app.editor.command.EditorCommand
import com.norfold.app.editor.memory.InteractionMemory
import com.norfold.app.editor.memory.MemoryScope
import com.norfold.app.editor.presentation.AdaptivePresentationRuntime
import com.norfold.app.editor.presentation.CompactPreference
import com.norfold.app.editor.presentation.FormFactor
import com.norfold.app.editor.presentation.PresentationMode
import com.norfold.app.editor.presentation.PresentedTool
import com.norfold.app.editor.presentation.ToolPresentationState
import com.norfold.app.editor.selection.TextCaret
import com.norfold.app.editor.surface.BackOutcome
import com.norfold.app.editor.surface.DismissTrigger
import com.norfold.app.editor.surface.DispatchResult
import com.norfold.app.editor.surface.SurfaceClass
import com.norfold.app.editor.surface.SurfaceCoordinator
import com.norfold.app.editor.surface.ToolSurface

/** Ids of the tool surfaces FlowHost opens on the [SurfaceCoordinator] stack. */
object FlowSurfaces {
    /** The touch `+` palette. */
    const val INSERT_PALETTE = "flow.surface.insert"

    /** The slash menu. Distinct from the palette because its query lives in the block text, not the tool. */
    const val SLASH_MENU = "flow.surface.slash"

    /** The "Turn into" child surface opened from the dock or block menu. */
    const val CONVERT_MENU = "flow.surface.convert"

    /** The retarget domain every Flow surface is compatible with (§5.6). */
    const val DOMAIN = "flow"
}

/** Interaction-memory keys FlowHost writes, each in the one scope its lifetime actually matches (§5.7). */
object FlowMemoryKeys {
    /** TRANSIENT — the block being dragged right now. Gone the moment the gesture ends. */
    const val DRAG_SOURCE = "flow.drag.source"

    /** TOOL_SESSION — the block a palette was opened from; meaningless once the palette closes. */
    const val PALETTE_ANCHOR = "flow.palette.anchor"

    /** ARTIFACT_UI — scroll position of this note; per-note, restored when the note is reopened. */
    const val SCROLL_INDEX = "flow.scroll.index"

    /** CONTEXT_PREFERENCE — the kind this note's author reaches for; the palette offers it first. */
    const val LAST_INSERT = "flow.insert.last"

    /** GUIDE_DISCOVERY — whether the multi-select affordance has been pointed out yet. */
    const val MULTI_SELECT_HINT = "flow.hint.multiSelect"
}

/** One command as a surface should render it: the same status behind every route (§4.1, §4.2). */
data class FlowCommandItem(
    val id: String,
    val label: String,
    val description: String,
    val category: String,
    val status: CommandStatus,
    val shortcut: FlowShortcut?,
) {
    val enabled: Boolean get() = status.isInvokable
    val active: Boolean get() = status.state == CommandState.ACTIVE
    val mixed: Boolean get() = status.state == CommandState.MIXED

    /** The reason to show the user when this row is greyed out, or null when it is not disabled (§4.2). */
    val disabledReason: String?
        get() = if (status.state == CommandState.DISABLED) status.reason ?: "Not available here" else null

    /**
     * What the accessibility tree announces: label, state, reason and chord in one string, so a screen-reader
     * user learns *why* an action is unavailable instead of finding it silently missing.
     */
    fun accessibilityLabel(): String = buildString {
        append(label)
        if (active) append(", on")
        if (mixed) append(", mixed")
        disabledReason?.let { append(", unavailable: $it") }
        shortcut?.let { append(", ${it.display()}") }
    }
}

/** What one Back press did, after the coordinator's back hierarchy resolved it (§5.4). */
sealed interface FlowBackResult {
    data class ClosedSurface(val surfaceId: String, val nowActive: String?) : FlowBackResult
    data class Consumed(val surfaceId: String) : FlowBackResult
    data object ExitedSelectionMode : FlowBackResult
    data object LeftEditor : FlowBackResult
}

/** The immutable snapshot the Compose shell renders. Rebuilt whenever [FlowHostRuntime.revision] changes. */
data class FlowHostUiState(
    val revision: Long,
    val document: BlockDocument,
    val selectedIds: List<String>,
    val primaryId: String?,
    val caret: TextCaret?,
    val selectionMode: Boolean,
    val selectedCount: Int,
    val formFactor: FormFactor,
    val presentationMode: PresentationMode,
    val activeSurfaceId: String?,
    val slash: FlowSlashSession?,
    val slashBlockId: String?,
    val dock: List<FlowCommandItem>,
    val insertPalette: List<FlowCommandItem>,
    val convertPalette: List<FlowCommandItem>,
    val tableActions: List<FlowCommandItem>,
    val accessibilityActions: List<FlowCommandItem>,
    val recentInsertId: String?,
    val canUndo: Boolean,
    val canRedo: Boolean,
    val showMultiSelectHint: Boolean,
    val lastFailure: String?,
)

/**
 * The FlowHost runtime (roadmap Phase 1 deliverables 1, 4–10).
 *
 * This is FlowHost's actual behavior. The Compose shell above it renders [uiState] and forwards gestures; it
 * owns nothing. That split is not stylistic — it is the only way the slice's central claim can be true. "The
 * same semantic command from `+`, slash, keyboard and accessibility" is a testable property here because all
 * four routes are literally [invoke] with a different [CommandSurface], resolved against one
 * [CommandRegistry], executed through one [FlowEditController], and recorded in one canonical history. Had the
 * `+` button run an insert in a Compose lambda, parity would be a claim to re-check by hand after every UI
 * change; here it is the only code path that exists.
 *
 * It composes the Phase-0 engines rather than reimplementing them:
 *  - [FlowCommandScope] + [CommandRegistry] — eligibility, state and execution (§4);
 *  - [FlowSlashResolver] — the typed `/query` *is* the search field, so there is no second input (§4.4);
 *  - [SurfaceCoordinator] — which gesture closes what, and the Back hierarchy (§5.2–§5.4);
 *  - [AdaptivePresentationRuntime] — a reflow re-presents open tools without resetting their state (§2.6);
 *  - [InteractionMemory] — each remembered value in the one scope whose lifetime matches it (§5.7);
 *  - [FlowEditController] — every user-facing edit as a canonical transaction (§13).
 *
 * Pure-JVM. Not thread-safe; the editor loop serializes access.
 */
class FlowHostRuntime(
    val controller: FlowEditController,
    initialFormFactor: FormFactor,
    val memory: InteractionMemory = InteractionMemory(),
    private val instrumentation: FlowHostInstrumentation = FlowHostInstrumentation.None,
) {

    val scope: FlowCommandScope = FlowCommandScope(controller)
    val registry: CommandRegistry = FlowCommands.registry(scope)
    val slashResolver: FlowSlashResolver = FlowSlashResolver(scope, registry)
    val surfaces: SurfaceCoordinator = SurfaceCoordinator()
    val presentation: AdaptivePresentationRuntime = AdaptivePresentationRuntime(initialFormFactor)

    /** This note, for [MemoryScope.ARTIFACT_UI] keying. */
    val artifactId: String get() = controller.target.id

    /** Bumped by anything that changes what the shell should draw. Compose recomposes off this. */
    var revision: Long = 0L
        private set

    private var selectionModeActive = false
    private var selectionRestore: SelectionSnapshot? = null

    private var slashBlockId: String? = null
    private var slashInput: FlowSlashInput? = null
    private var slashSession: FlowSlashSession? = null

    private var lastFailure: String? = null

    private data class SelectionSnapshot(
        val ids: List<String>,
        val primary: String?,
        val caret: TextCaret?,
    )

    // ── Single invocation path ────────────────────────────────────────────────────────────────────────

    /** The resolved status of [commandId] as seen from [surface]. Identical for every surface by design. */
    fun statusOf(commandId: String, surface: CommandSurface = CommandSurface.DOCK): CommandStatus =
        registry.get(commandId)?.status(scope.commandContext(surface)) ?: CommandStatus.Hidden

    /**
     * Runs [commandId] as if invoked from [surface]. **Every** route — the `+` palette, a slash row, a chord,
     * an accessibility action, a dock button, a context menu — lands here, so eligibility, execution, undo
     * and post-command selection cannot differ between them.
     *
     * Execution goes through [CommandRegistry.execute], which applies the command's own eligibility guard, so
     * an ineligible invocation fails with the command's stated reason rather than silently doing nothing.
     */
    fun invoke(commandId: String, surface: CommandSurface): CommandResult {
        val context = scope.commandContext(surface)
        val result = registry.execute(commandId, context)
        when (result) {
            is CommandResult.Success -> {
                lastFailure = null
                if (commandId.startsWith(FlowIds.INSERT_PREFIX)) {
                    // Which block kind this author reaches for is a preference *for this context*, not a
                    // device-wide setting and not transient interaction state.
                    memory.put(MemoryScope.CONTEXT_PREFERENCE, FlowMemoryKeys.LAST_INSERT, commandId)
                }
                revision++
            }
            is CommandResult.Failure -> lastFailure = result.reason
            CommandResult.NoOp -> lastFailure = null
        }
        instrumentation.onCommandInvoked(commandId, surface, result)
        if (result is CommandResult.Success) {
            // A command-menu surface closes once its command ran; a continuous tool does not (§5.3).
            dismiss(DismissTrigger.COMMAND_EXECUTED)
        }
        return result
    }

    /** Undo/redo go to the canonical history, not the registry — they are not per-selection commands. */
    fun undo(): Boolean = controller.undo().also { if (it) { syncSelectionToDocument(); revision++ } }

    fun redo(): Boolean = controller.redo().also { if (it) { syncSelectionToDocument(); revision++ } }

    // ── Keyboard (deliverable 6) ──────────────────────────────────────────────────────────────────────

    /**
     * Handles a chord. Returns true if FlowHost consumed it, so the host can let anything unbound fall
     * through to the focused text field and the IME untouched.
     */
    fun handleShortcut(shortcut: FlowShortcut): Boolean {
        when (shortcut) {
            FlowKeyboardMap.UNDO -> { undo(); return true }
            FlowKeyboardMap.REDO, FlowKeyboardMap.REDO_ALT -> { redo(); return true }
            FlowKeyboardMap.SELECT_MODE -> { enterSelectionMode(scope.anchorBlockId()); return true }
        }
        val commandId = FlowKeyboardMap.commandFor(shortcut) ?: return false
        // An inert chord still consumes the key: silently typing a "d" because Duplicate happened to be
        // disabled would be worse than nothing happening.
        invoke(commandId, CommandSurface.KEYBOARD)
        return true
    }

    // ── Accessibility (deliverable 6) ─────────────────────────────────────────────────────────────────

    /**
     * Every command the current context can show, as accessibility actions. This is the whole catalogue, not
     * a hand-picked subset: a screen-reader user reaches exactly what a sighted user reaches, including the
     * disabled entries and their reasons.
     */
    fun accessibilityActions(): List<FlowCommandItem> =
        itemsOf(registry.all(), CommandSurface.ACCESSIBILITY).filter { it.status.isVisible }

    /** Runs an accessibility action. Same id, same engine, same undo unit as the dock button beside it. */
    fun invokeAccessibility(commandId: String): CommandResult =
        invoke(commandId, CommandSurface.ACCESSIBILITY)

    // ── Command views (deliverable 4, 5, 8) ───────────────────────────────────────────────────────────

    /** The context dock: the curated structural row, minus anything the current selection hides. */
    fun dockCommands(): List<FlowCommandItem> =
        itemsOf(FlowIds.DOCK_ORDER.mapNotNull(registry::get), CommandSurface.DOCK)
            .filter { it.status.isVisible }

    /**
     * The touch `+` palette: the block *kinds* that can be inserted. `flow.insert.above` is deliberately
     * excluded — it is a positional command about where a paragraph goes, not a kind to choose, and it
     * belongs to the dock and the block menu. Insertion commands are context-free, so this list is stable
     * and never empty; the `+` button is never a dead control. [recentInsertId] tells the surface which row
     * to feature.
     */
    fun insertPalette(): List<FlowCommandItem> =
        itemsOf(
            registry.all().filter { it.id.startsWith(FlowIds.INSERT_PREFIX) && it.id != FlowIds.INSERT_ABOVE },
            CommandSurface.DOCK,
        )

    /** The "Turn into" child surface. */
    fun convertPalette(): List<FlowCommandItem> =
        itemsOf(registry.all().filter { it.id.startsWith(FlowIds.CONVERT_PREFIX) }, CommandSurface.DOCK)
            .filter { it.status.isVisible }

    /** Table row/column hooks; disabled with a reason when the caret addresses no cell. */
    fun tableActions(): List<FlowCommandItem> =
        itemsOf(registry.all().filter { it.id.startsWith(FlowIds.TABLE_PREFIX) }, CommandSurface.DOCK)
            .filter { it.status.isVisible }

    /** The insertion the author last used in this note, for the palette's featured row. */
    fun recentInsertId(): String? = memory.get(MemoryScope.CONTEXT_PREFERENCE, FlowMemoryKeys.LAST_INSERT)

    private fun itemsOf(commands: List<EditorCommand>, surface: CommandSurface): List<FlowCommandItem> {
        val context = scope.commandContext(surface)
        return commands.map { command ->
            FlowCommandItem(
                id = command.id,
                label = command.label,
                description = command.description,
                category = command.category,
                status = command.status(context),
                shortcut = FlowKeyboardMap.shortcutFor(command.id),
            )
        }
    }

    // ── Text editing (deliverable 9: canonical history for user-facing edits) ─────────────────────────

    /**
     * Commits a text change through the canonical history with a per-block coalescing key, so a typing run is
     * one undo unit and a caret move ends it. The Compose text field calls this; it never mutates a document.
     *
     * @return true if the document changed.
     */
    fun editText(blockId: String, before: String, after: String, itemId: String? = null): Boolean {
        if (before == after) return false
        controller.mutate("Typing", coalescingKey = "block:$blockId") { session ->
            if (itemId != null) session.editListItem(blockId, itemId, before, after)
            else session.editText(blockId, before, after)
        } ?: return false
        revision++
        return true
    }

    /**
     * Flips a to-do's checkbox through the canonical history, so it undoes like any other edit.
     *
     * It lives here rather than in the catalogue because Phase 1's commands are *structural*; checking a box
     * is content. Keeping it in the runtime still keeps it out of the Compose layer, which is the boundary
     * that matters — the surface reports the tap, the runtime owns what a tap means.
     */
    fun toggleTodo(blockId: String, itemId: String): Boolean {
        val block = controller.document().findById(blockId) as? TodoListBlock ?: return false
        val index = block.items.indexOfFirst { it.id == itemId }
        if (index < 0) return false
        val updated = block.items.toMutableList()
        updated[index] = updated[index].copy(checked = !updated[index].checked)
        controller.mutate("Toggle to-do", coalescingKey = null) { session ->
            session.replaceBlock(block.copy(items = updated))
            BlockCursor(blockId, 0, itemId)
        } ?: return false
        revision++
        return true
    }

    /** Moves the caret. Leaving a block breaks the typing run so the next edit opens a new undo unit. */
    fun setCaret(blockId: String, offset: Int) {
        val current = scope.caret()
        if (current?.targetId != blockId) controller.breakTypingGroup()
        // Selection first, caret second: replacing the selection resets the engine's text state, so setting
        // the caret before selecting would silently discard it.
        if (!selectionModeActive) scope.selectBlocks(listOf(blockId))
        scope.selection.setCaret(TextCaret(blockId, offset))
        revision++
    }

    /** Addresses a table cell so the directional row/column commands become eligible (§4.15). */
    fun setTableCursor(cursor: FlowTableCursor?) {
        scope.tableCursor = cursor
        revision++
    }

    // ── Slash (deliverable 3 + 5) ─────────────────────────────────────────────────────────────────────

    /**
     * Re-resolves the slash menu from the text the user has typed. The query lives in the block, so this is
     * the *only* input the menu has — there is deliberately no second search field to keep in sync.
     */
    fun onTextInput(blockId: String, input: FlowSlashInput): FlowSlashSession? {
        val session = slashResolver.resolve(input)
        slashBlockId = blockId
        slashInput = input
        val wasOpen = slashSession != null
        slashSession = session
        if (session != null && !wasOpen) {
            openSurface(FlowSurfaces.SLASH_MENU, SurfaceClass.COMMAND_MENU, blockId)
        } else if (session == null && wasOpen) {
            closeSurface(FlowSurfaces.SLASH_MENU, DismissTrigger.CANCEL)
        }
        instrumentation.onSlashSessionChanged(session?.query?.query, session?.results?.size ?: -1)
        revision++
        return session
    }

    /**
     * Accepts a slash row: erases the typed `/query` and runs the command as **one** undo unit. The command
     * is the same registry entry the dock and the keyboard reach, so an ineligible row fails here with the
     * same reason it would state anywhere else.
     */
    fun acceptSlash(commandId: String): CommandResult {
        val blockId = slashBlockId ?: return CommandResult.Failure("No slash query at the caret")
        val input = slashInput ?: return CommandResult.Failure("No slash query at the caret")
        val command = registry.get(commandId)
            ?: return CommandResult.Failure("Unknown command '$commandId'")
        val result = slashResolver.accept(blockId, input, command)
        when (result) {
            is CommandResult.Success -> { lastFailure = null; revision++ }
            is CommandResult.Failure -> lastFailure = result.reason
            CommandResult.NoOp -> lastFailure = null
        }
        instrumentation.onCommandInvoked(commandId, CommandSurface.SLASH, result)
        if (result is CommandResult.Success) {
            if (commandId.startsWith(FlowIds.INSERT_PREFIX)) {
                memory.put(MemoryScope.CONTEXT_PREFERENCE, FlowMemoryKeys.LAST_INSERT, commandId)
            }
            closeSlash(DismissTrigger.COMMAND_EXECUTED)
        }
        return result
    }

    fun closeSlash(trigger: DismissTrigger = DismissTrigger.CANCEL) {
        if (slashSession != null) closeSurface(FlowSurfaces.SLASH_MENU, trigger)
        slashSession = null
        slashInput = null
        slashBlockId = null
        instrumentation.onSlashSessionChanged(null, -1)
        revision++
    }

    fun slashSession(): FlowSlashSession? = slashSession

    // ── Multi-select (deliverable 5) ──────────────────────────────────────────────────────────────────

    val selectionMode: Boolean get() = selectionModeActive
    val selectedCount: Int get() = scope.selectedBlockIds().size

    /**
     * Enters multi-select, seeded with [anchorBlockId]. The pre-mode selection and caret are captured so
     * [cancelSelectionMode] can restore exactly what the user was doing — Cancel means *cancel*, not "clear".
     */
    fun enterSelectionMode(anchorBlockId: String?) {
        if (!selectionModeActive) {
            selectionRestore = SelectionSnapshot(
                ids = scope.selectedBlockIds(),
                primary = scope.primaryBlockId(),
                caret = scope.caret(),
            )
            selectionModeActive = true
        }
        anchorBlockId?.let { scope.selectBlocks(listOf(it)) }
        markMultiSelectHintShown()
        instrumentation.onSelectionModeChanged(true, selectedCount)
        revision++
    }

    /** Toggles a block in/out of the multi-selection, preserving the explicit primary where it survives (§4.9). */
    fun toggleSelection(blockId: String) {
        if (!selectionModeActive) enterSelectionMode(null)
        val current = scope.selectedBlockIds()
        val next = if (blockId in current) current - blockId else current + blockId
        val primary = scope.primaryBlockId()
        scope.selectBlocks(next)
        if (primary != null && primary != next.firstOrNull() && primary in next) scope.selection.promote(primary)
        instrumentation.onSelectionModeChanged(true, next.size)
        revision++
    }

    /** Cancel: discards the multi-selection and restores the selection and caret from before the mode. */
    fun cancelSelectionMode() {
        val restore = selectionRestore
        selectionModeActive = false
        selectionRestore = null
        scope.selection.clear()
        if (restore != null) {
            scope.selectBlocks(restore.ids)
            restore.primary?.takeIf { it in restore.ids }?.let { scope.selection.promote(it) }
            restore.caret?.let { scope.selection.setCaret(it) }
        }
        instrumentation.onSelectionModeChanged(false, selectedCount)
        revision++
    }

    /** Done: leaves multi-select keeping what was selected, so a follow-up command still has its targets. */
    fun finishSelectionMode() {
        selectionModeActive = false
        selectionRestore = null
        instrumentation.onSelectionModeChanged(false, selectedCount)
        revision++
    }

    /** Whether the multi-select affordance still needs pointing out (GUIDE_DISCOVERY, never a user setting). */
    fun showMultiSelectHint(): Boolean =
        !memory.getOrDefault(MemoryScope.GUIDE_DISCOVERY, FlowMemoryKeys.MULTI_SELECT_HINT, false)

    fun markMultiSelectHintShown() {
        memory.put(MemoryScope.GUIDE_DISCOVERY, FlowMemoryKeys.MULTI_SELECT_HINT, true)
    }

    // ── Surfaces & back (deliverable 7) ───────────────────────────────────────────────────────────────

    /** Opens a surface and registers its presentation state. Re-opening keeps the state it already had. */
    fun openSurface(
        surfaceId: String,
        surfaceClass: SurfaceClass = SurfaceClass.COMMAND_MENU,
        anchorBlockId: String? = null,
    ): PresentedTool {
        val surface = ToolSurface(
            id = surfaceId,
            surfaceClass = surfaceClass,
            compatibleDomains = setOf(FlowSurfaces.DOMAIN),
        )
        if (surfaces.active() == null) surfaces.open(surface) else surfaces.openChild(surface)
        anchorBlockId?.let {
            // Which block a palette was opened from matters only while that palette is open.
            memory.put(MemoryScope.TOOL_SESSION, FlowMemoryKeys.PALETTE_ANCHOR, it, owner = surfaceId)
        }
        val presented = presentation.present(ToolPresentationState(surfaceId), compactPreferenceFor(surfaceId))
        instrumentation.onSurfaceOpened(surfaceId)
        revision++
        return presented
    }

    /** Opens the `+` palette anchored to [anchorBlockId], which is where the inserted block will land. */
    fun openInsertPalette(anchorBlockId: String?): PresentedTool {
        anchorBlockId?.takeIf { !selectionModeActive }?.let { scope.selectBlocks(listOf(it)) }
        return openSurface(FlowSurfaces.INSERT_PALETTE, SurfaceClass.PICKER_BROWSER, anchorBlockId)
    }

    fun closeSurface(surfaceId: String, trigger: DismissTrigger) {
        val active = surfaces.active()
        if (active?.id == surfaceId) dismiss(trigger) else forgetSurface(surfaceId, trigger)
    }

    /**
     * Applies [trigger] to the active surface through the coordinator's policy. A universal close-on-any-tap
     * handler is exactly what §5.3 rejects, so this asks the surface's own policy rather than assuming.
     */
    fun dismiss(trigger: DismissTrigger): DispatchResult {
        val result = surfaces.dispatch(trigger)
        if (result is DispatchResult.Dismissed) {
            forgetSurfaceState(result.closed.id)
            instrumentation.onSurfaceDismissed(result.closed.id, trigger)
            if (result.closed.id == FlowSurfaces.SLASH_MENU) {
                slashSession = null
                slashInput = null
                slashBlockId = null
            }
            revision++
        }
        return result
    }

    /** One Back press against the whole hierarchy: child tool → parent tool → selection mode → editor (§5.4). */
    fun back(): FlowBackResult {
        val outcome = surfaces.back(hasSelectionMode = selectionModeActive)
        revision++
        return when (outcome) {
            is BackOutcome.ClosedSurface -> {
                forgetSurfaceState(outcome.closed.id)
                instrumentation.onSurfaceDismissed(outcome.closed.id, DismissTrigger.BACK)
                if (outcome.closed.id == FlowSurfaces.SLASH_MENU) {
                    slashSession = null
                    slashInput = null
                    slashBlockId = null
                }
                FlowBackResult.ClosedSurface(outcome.closed.id, outcome.nowActive?.id)
            }
            is BackOutcome.Consumed -> FlowBackResult.Consumed(outcome.surface.id)
            BackOutcome.ExitedSelectionMode -> {
                cancelSelectionMode()
                FlowBackResult.ExitedSelectionMode
            }
            BackOutcome.LeftEditor -> FlowBackResult.LeftEditor
        }
    }

    private fun forgetSurface(surfaceId: String, trigger: DismissTrigger) {
        forgetSurfaceState(surfaceId)
        instrumentation.onSurfaceDismissed(surfaceId, trigger)
        revision++
    }

    private fun forgetSurfaceState(surfaceId: String) {
        // A closed tool's session state is finished; its presentation state is not "reflowed away" but done.
        memory.endToolSession(surfaceId)
        presentation.close(surfaceId)
    }

    // ── Adaptive presentation (deliverable 7) ─────────────────────────────────────────────────────────

    /**
     * Applies a window change. Open tools are re-presented with their state carried forward **verbatim** —
     * a rotation must never reset a palette's scroll or an in-progress query (§2.6, §5.1).
     */
    fun onFormFactorChanged(next: FormFactor): Map<String, PresentedTool> {
        val presented = presentation.onFormFactorChanged(next) { compactPreferenceFor(it) }
        instrumentation.onPresentationChanged(currentPresentationMode(), next)
        revision++
        return presented
    }

    fun formFactor(): FormFactor = presentation.formFactor

    /** How the *active* surface should be presented; with nothing open this is the ambient mode for the window. */
    fun currentPresentationMode(): PresentationMode {
        val active = surfaces.active()?.id
        return presentation.modeFor(active.orEmpty(), compactPreferenceFor(active))
    }

    private fun compactPreferenceFor(surfaceId: String?): CompactPreference = when (surfaceId) {
        // The palette is a browsable catalogue; on a phone it deserves a sheet, not a cramped popover (§2.8).
        FlowSurfaces.INSERT_PALETTE, FlowSurfaces.CONVERT_MENU -> CompactPreference.SHEET
        else -> CompactPreference.POPOVER
    }

    // ── Drag / drop (routed through the registry like every other move) ──────────────────────────────

    fun beginDrag(blockId: String) {
        memory.put(MemoryScope.TRANSIENT, FlowMemoryKeys.DRAG_SOURCE, blockId)
        scope.selectBlocks(listOf(blockId))
        revision++
    }

    /** Commits a drag as `flow.move.drop` — the same command, undo unit and selection rule as Move up/down. */
    fun completeDrag(target: DropTarget): CommandResult {
        val source: String? = memory.get(MemoryScope.TRANSIENT, FlowMemoryKeys.DRAG_SOURCE)
        if (source == null) return CommandResult.Failure("No drag in progress")
        scope.pendingDrop = target
        val result = invoke(FlowIds.DROP, CommandSurface.POINTER_CONTEXT_MENU)
        memory.endInteraction()
        return result
    }

    fun cancelDrag() {
        scope.pendingDrop = null
        memory.endInteraction()
        revision++
    }

    // ── Virtualization / scroll (deliverable 10) ──────────────────────────────────────────────────────

    /** The lazy list reports what it actually composed; per-note scroll is remembered as artifact UI state. */
    fun reportVisibleRange(firstIndex: Int, lastIndex: Int) {
        memory.put(MemoryScope.ARTIFACT_UI, FlowMemoryKeys.SCROLL_INDEX, firstIndex, owner = artifactId)
        instrumentation.onVisibleRangeChanged(firstIndex, lastIndex, controller.document().blocks.size)
    }

    fun restoredScrollIndex(): Int =
        memory.getOrDefault(MemoryScope.ARTIFACT_UI, FlowMemoryKeys.SCROLL_INDEX, 0, owner = artifactId)

    // ── Snapshot ─────────────────────────────────────────────────────────────────────────────────────

    /** Everything the Compose shell needs, resolved once per revision. */
    fun uiState(): FlowHostUiState {
        val document = controller.document()
        return FlowHostUiState(
            revision = revision,
            document = document,
            selectedIds = scope.selectedBlockIds(),
            primaryId = scope.primaryBlockId(),
            caret = scope.caret(),
            selectionMode = selectionModeActive,
            selectedCount = selectedCount,
            formFactor = presentation.formFactor,
            presentationMode = currentPresentationMode(),
            activeSurfaceId = surfaces.active()?.id,
            slash = slashSession,
            slashBlockId = slashBlockId,
            dock = dockCommands(),
            insertPalette = insertPalette(),
            convertPalette = convertPalette(),
            tableActions = tableActions(),
            accessibilityActions = accessibilityActions(),
            recentInsertId = recentInsertId(),
            canUndo = controller.canUndo(),
            canRedo = controller.canRedo(),
            showMultiSelectHint = showMultiSelectHint(),
            lastFailure = lastFailure,
        )
    }

    /**
     * Drops selection entries whose blocks no longer exist. Undo/redo rewrites the document behind the
     * Selection Engine's back, and a selection pointing at a removed id would make commands resolve against
     * phantoms.
     */
    private fun syncSelectionToDocument() {
        val document = controller.document()
        val surviving = scope.selectedBlockIds().filter { document.findById(it) != null }
        if (surviving.size != scope.selectedBlockIds().size) scope.selectBlocks(surviving)
        scope.caret()?.let { caret ->
            if (document.findById(caret.targetId) == null) controller.breakTypingGroup()
        }
    }
}
