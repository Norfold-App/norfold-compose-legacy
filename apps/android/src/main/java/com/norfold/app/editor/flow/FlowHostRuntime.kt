package com.norfold.app.editor.flow

import com.norfold.app.domain.BlockCursor
import com.norfold.app.domain.BlockDocument
import com.norfold.app.domain.CalloutBlock
import com.norfold.app.domain.CodeBlock
import com.norfold.app.domain.DropTarget
import com.norfold.app.domain.TodoListBlock
import com.norfold.app.domain.TableBlock
import com.norfold.app.domain.ShapeBlock
import com.norfold.app.domain.supportsStyle
import com.norfold.app.domain.findById
import com.norfold.app.domain.plainText
import com.norfold.app.domain.walk
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
import com.norfold.app.domain.ChartBlock
import com.norfold.app.domain.ImageBlock
import com.norfold.app.editor.chart.ChartBlockAdapter
import com.norfold.app.editor.chart.ChartDocument
import com.norfold.app.editor.chart.ChartCalculationDataProvider
import com.norfold.app.editor.calculation.CalculationRangeRuntime
import com.norfold.app.editor.surface.BackOutcome
import com.norfold.app.editor.surface.DismissTrigger
import com.norfold.app.editor.surface.DispatchResult
import com.norfold.app.editor.surface.SurfaceClass
import com.norfold.app.editor.surface.SurfaceCoordinator
import com.norfold.app.editor.surface.ToolSurface
import com.norfold.app.editor.style.EditorStyleRuntime
import com.norfold.app.editor.calculation.CalculationEngine
import com.norfold.app.editor.calculation.CellCommitResult
import com.norfold.app.editor.calculation.CellInputParser
import com.norfold.app.editor.calculation.CalculationError
import com.norfold.app.editor.calculation.CalculationErrorKind
import com.norfold.app.editor.calculation.TableCalculationBridge
import com.norfold.app.editor.calculation.TableUpgradeResult
import com.norfold.app.editor.style.EditorStyleSelection
import com.norfold.app.editor.style.EditorStyleUiState
import com.norfold.app.editor.style.StyleController
import com.norfold.app.editor.style.EditorStyleCommands
import com.norfold.app.domain.style.FontRegistry
import com.norfold.app.domain.style.ColorRole
import com.norfold.app.editor.relationship.CommentAnchor
import com.norfold.app.editor.relationship.CommentAuthorRef
import com.norfold.app.editor.relationship.CommentMention
import com.norfold.app.editor.relationship.RelationshipAnchor
import com.norfold.app.editor.relationship.RelationshipCommandIds
import com.norfold.app.editor.relationship.RelationshipCommands
import com.norfold.app.editor.relationship.RelationshipEndpoint
import com.norfold.app.editor.relationship.RelationshipReviewRuntime
import com.norfold.app.editor.relationship.RelationshipReviewUiState
import com.norfold.app.editor.relationship.RelationshipSurfaces
import com.norfold.app.editor.relationship.TextEdit
import com.norfold.app.editor.interchange.InterchangeCommands
import com.norfold.app.editor.interchange.InterchangeRuntime

/** Ids of the tool surfaces FlowHost opens on the [SurfaceCoordinator] stack. */
object FlowSurfaces {
    /** The touch `+` palette. */
    const val INSERT_PALETTE = "flow.surface.insert"

    /** The slash menu. Distinct from the palette because its query lives in the block text, not the tool. */
    const val SLASH_MENU = "flow.surface.slash"

    /** The "Turn into" child surface opened from the dock or block menu. */
    const val CONVERT_MENU = "flow.surface.convert"
    const val COLOR_STUDIO = "editor.surface.colorStudio"
    const val FONT_BROWSER = "editor.surface.fontBrowser"
    const val NAMED_STYLES = "editor.surface.namedStyles"
    const val RELATIONSHIP_MANAGER = RelationshipSurfaces.MANAGER
    const val COMMENTS = RelationshipSurfaces.COMMENTS
    const val REVIEW = RelationshipSurfaces.REVIEW

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
    data object ExitedConnectMode : FlowBackResult
    data object LeftEditor : FlowBackResult
}

/**
 * A caret the *runtime* has placed and the surface must follow (correction item 1).
 *
 * Caret/focus sync has to be two-way, and the two directions are not symmetric. When the user taps or drags
 * the caret, the field already shows it and the runtime only records it. When a command moves it — split,
 * merge, insert, convert, slash, undo, redo — the field knows nothing, so the runtime must *ask*, and the
 * surface must move real focus and a real selection.
 *
 * [stamp] is what makes that safe. It advances only when the runtime originates a move, never when the field
 * reports one, so a field echoing its own caret back cannot re-trigger the request that produced it. The
 * surface applies a request once per stamp; without it, "runtime sets caret → field reports caret → runtime
 * sets caret" is an infinite recomposition loop.
 */
data class FlowCaretRequest(
    val blockId: String,
    val itemId: String?,
    val offset: Int,
    val stamp: Long,
)

/** The immutable snapshot the Compose shell renders. Rebuilt whenever [FlowHostRuntime.revision] changes. */
data class FlowHostUiState(
    val revision: Long,
    val document: BlockDocument,
    val selectedIds: List<String>,
    val primaryId: String?,
    val caret: TextCaret?,
    val caretRequest: FlowCaretRequest?,
    val tableCursor: FlowTableCursor?,
    /** The uncommitted text of the addressed cell, or null when no cell is mid-edit. */
    val tableDraft: String?,
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
    val editorStyle: EditorStyleUiState,
    val relationshipReview: RelationshipReviewUiState,
    val interchange: com.norfold.app.editor.interchange.InterchangeUiState,
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
    fontRegistry: FontRegistry = EditorStyleRuntime.EMPTY_REGISTRY,
    chartStudioEnabled: Boolean = false,
    relationshipsEnabled: Boolean = false,
    importExportRawEnabled: Boolean = false,
) {
    private val chartRanges = CalculationRangeRuntime()

    val scope: FlowCommandScope = FlowCommandScope(controller)
    val interchange = InterchangeRuntime(controller::document)
    val registry: CommandRegistry = FlowCommands.registry(scope, chartStudioEnabled).also {
        if (importExportRawEnabled) InterchangeCommands.register(it, interchange)
    }
    val slashResolver: FlowSlashResolver = FlowSlashResolver(scope, registry)
    val surfaces: SurfaceCoordinator = SurfaceCoordinator()
    val presentation: AdaptivePresentationRuntime = AdaptivePresentationRuntime(initialFormFactor)
    val relationshipReview = RelationshipReviewRuntime(
        artifactId = controller.target.id,
        enabled = relationshipsEnabled,
        documentProvider = controller::document,
        mutateDocument = controller::mutateDocument,
        protectBeforeRestore = { controller.snapshotBeforeDestructiveEdit(it) },
    )
    val styleController = StyleController(
        mutate = { label, mutation ->
            controller.mutate(label) { session ->
                mutation(session)
                val id = scope.primaryBlockId()
                    ?.takeIf { session.document.findById(it) != null }
                    ?: session.document.blocks.first().id
                BlockCursor(id, 0)
            } != null
        },
        documentProvider = controller::document,
    )
    val editorStyle = EditorStyleRuntime(
        host = "flow",
        controller = styleController,
        registry = fontRegistry,
        selection = {
            val document = controller.document()
            val selected = scope.selectedBlockIds().ifEmpty { listOfNotNull(scope.primaryBlockId()) }
            EditorStyleSelection(
                textBlockIds = selected.filter { id ->
                    document.findById(id)?.let { it.supportsStyle() && it !is ShapeBlock } == true
                },
                shapeBlockIds = selected.filter { document.findById(it) is ShapeBlock },
                selectedText = selected.mapNotNull { document.findById(it)?.plainText() }
                    .joinToString("\n").take(256),
            )
        },
        onChanged = { revision++ },
    )

    /** Replaces one chart through the same canonical History Engine as every other Flow edit. */
    fun updateChart(blockId: String, document: ChartDocument): Boolean {
        val before = controller.document().findById(blockId) as? ChartBlock ?: return false
        val after = ChartBlockAdapter.save(before, document)
        val changed = controller.mutate("Edit chart") { session ->
            session.replaceBlock(after)
            BlockCursor(blockId, 0)
        } != null
        if (changed) revision++
        return changed
    }

    /** Replaces one image through the canonical Flow history as the single Image Focus transaction. */
    fun updateImage(blockId: String, after: ImageBlock): Boolean {
        val before = controller.document().findById(blockId) as? ImageBlock ?: return false
        if (after.id != before.id || after == before) return false
        val changed = controller.mutate("Edit image") { session ->
            session.replaceBlock(after)
            BlockCursor(blockId, 0)
        } != null
        if (changed) revision++
        return changed
    }

    fun chartCalculationProvider(): ChartCalculationDataProvider = ChartCalculationDataProvider { binding ->
        var workbook: com.norfold.app.editor.calculation.CalculationWorkbook? = null
        controller.document().walk { block ->
            if (workbook == null && block is TableBlock) {
                val candidate = TableCalculationBridge.view(block).workbook
                if (candidate.id == binding.workbookId) workbook = candidate
            }
        }
        chartRanges.read(workbook ?: error("The bound workbook is not in this document"), binding)
    }

    init {
        controller.setDocumentInvariant(relationshipReview::reconcileEndpointsIn)
        EditorStyleCommands.register(
            registry = registry,
            textColor = { openStyleColor(ColorRole.TEXT) },
            highlight = { openStyleColor(ColorRole.HIGHLIGHT) },
            fill = { openStyleColor(ColorRole.FILL) },
            stroke = { openStyleColor(ColorRole.STROKE) },
            pageBackground = { CommandResult.Failure("Page backgrounds are available in Document mode") },
            fontBrowser = { openStyleFont() },
            namedStyles = { openNamedStyles() },
            clearFormatting = {
                val count = editorStyle.clearFormatting()
                if (count > 0) CommandResult.Success(count) else CommandResult.NoOp
            },
        )
        if (relationshipsEnabled) {
            RelationshipCommands.register(
                registry = registry,
                enterConnect = {
                    sourceRelationshipEndpoint()?.let { source ->
                        if (relationshipReview.enterConnectMode(source)) CommandResult.Success() else CommandResult.Failure(relationshipReview.lastFailure ?: "Cannot enter Connect mode")
                    } ?: CommandResult.Failure("Select a block first")
                },
                exitConnect = { relationshipReview.exitConnectMode(); CommandResult.Success() },
                openManager = { openSurface(FlowSurfaces.RELATIONSHIP_MANAGER); CommandResult.Success() },
                addComment = { openSurface(FlowSurfaces.COMMENTS); CommandResult.Success() },
                nextComment = { navigateDiscussion(forward = true) },
                previousComment = { navigateDiscussion(forward = false) },
                openReview = { relationshipReview.openReviewMode(); openSurface(FlowSurfaces.REVIEW); CommandResult.Success() },
                createSnapshot = { if (relationshipReview.createReviewSnapshot() != null) CommandResult.Success() else CommandResult.Failure(relationshipReview.lastFailure ?: "Snapshot failed") },
            )
        }
    }

    private fun sourceRelationshipEndpoint(blockId: String? = scope.primaryBlockId()): RelationshipEndpoint? {
        val block = blockId?.let { controller.document().findById(it) } ?: return null
        val anchor = when (block) {
            is ImageBlock -> RelationshipAnchor.Image(artifactId, block.id)
            is ChartBlock -> RelationshipAnchor.Chart(artifactId, block.id)
            is TableBlock -> RelationshipAnchor.TableRange(artifactId, block.id, "whole")
            else -> RelationshipAnchor.Block(artifactId, block.id)
        }
        return RelationshipEndpoint(anchor)
    }

    fun addComment(author: CommentAuthorRef, body: String, mentions: List<CommentMention> = emptyList()): Boolean {
        val endpoint = sourceRelationshipEndpoint() ?: return false
        val range = scope.selection.textRange()?.takeIf { it.targetId == scope.primaryBlockId() && !it.isCollapsed }
        val anchor = if (range != null) {
            val text = controller.document().findById(range.targetId)?.plainText().orEmpty()
            val start = range.start.coerceIn(0, text.length)
            val end = range.end.coerceIn(start, text.length)
            CommentAnchor.Text(
                artifactId = artifactId,
                blockId = range.targetId,
                start = start,
                end = end,
                quote = text.substring(start, end),
                prefix = text.substring(maxOf(0, start - 24), start),
                suffix = text.substring(end, minOf(text.length, end + 24)),
            )
        } else when (val value = endpoint.anchor) {
            is RelationshipAnchor.Block -> CommentAnchor.Block(value.artifactId, value.blockId)
            is RelationshipAnchor.Image -> CommentAnchor.Image(value.artifactId, value.imageId)
            is RelationshipAnchor.Chart -> CommentAnchor.Chart(value.artifactId, value.chartId, value.seriesId)
            is RelationshipAnchor.TableRange -> CommentAnchor.TableRange(value.artifactId, value.tableId, value.rangeId)
            else -> CommentAnchor.Artifact(artifactId)
        }
        val created = relationshipReview.addThread(anchor, author, body, mentions) != null
        if (created) revision++
        return created
    }

    private fun navigateDiscussion(forward: Boolean): CommandResult {
        val thread = relationshipReview.nextThread(forward) ?: return CommandResult.NoOp
        navigateTo(thread.anchor)
        openSurface(FlowSurfaces.COMMENTS)
        return CommandResult.Success()
    }

    fun navigateTo(anchor: CommentAnchor): Boolean {
        val target = when (anchor) {
            is CommentAnchor.Block -> anchor.blockId
            is CommentAnchor.Text -> anchor.blockId
            is CommentAnchor.TableRange -> anchor.tableId
            is CommentAnchor.Chart -> anchor.chartId
            is CommentAnchor.Image -> anchor.imageId
            else -> null
        } ?: return false
        if (controller.document().findById(target) == null) return false
        if (anchor is CommentAnchor.Text && anchor.state == com.norfold.app.editor.relationship.AnchorState.ATTACHED) {
            scope.selectBlocks(listOf(target))
            scope.selection.setTextRange(com.norfold.app.editor.selection.TextRange(target, anchor.start, anchor.end))
            caretRequest = FlowCaretRequest(target, null, anchor.end, ++caretStamp)
        } else {
            requestCaret(target, null, 0)
        }
        revision++
        return true
    }

    fun navigateTo(endpoint: RelationshipEndpoint): Boolean {
        val target = when (val anchor = endpoint.anchor) {
            is RelationshipAnchor.Block -> anchor.blockId
            is RelationshipAnchor.TableRange -> anchor.tableId
            is RelationshipAnchor.Chart -> anchor.chartId
            is RelationshipAnchor.Image -> anchor.imageId
            else -> null
        } ?: return false
        if (controller.document().findById(target) == null) return false
        requestCaret(target, null, 0)
        revision++
        return true
    }

    private fun openStyleColor(role: ColorRole): CommandResult {
        if (!editorStyle.openColor(role)) return CommandResult.Failure("Select compatible content first")
        openSurface(FlowSurfaces.COLOR_STUDIO, SurfaceClass.CONTINUOUS_TOOL, scope.primaryBlockId())
        return CommandResult.Success(scope.selectedBlockIds().size.coerceAtLeast(1))
    }

    private fun openStyleFont(): CommandResult {
        if (!editorStyle.openFont()) return CommandResult.Failure("Select text first")
        openSurface(FlowSurfaces.FONT_BROWSER, SurfaceClass.PICKER_BROWSER, scope.primaryBlockId())
        return CommandResult.Success(scope.selectedBlockIds().size.coerceAtLeast(1))
    }

    private fun openNamedStyles(): CommandResult {
        if (scope.selectedBlockIds().isEmpty()) return CommandResult.Failure("Select content first")
        openSurface(FlowSurfaces.NAMED_STYLES, SurfaceClass.PICKER_BROWSER, scope.primaryBlockId())
        return CommandResult.Success(scope.selectedBlockIds().size)
    }

    /** This note, for [MemoryScope.ARTIFACT_UI] keying. */
    val artifactId: String get() = controller.target.id

    /** Bumped by anything that changes what the shell should draw. Compose recomposes off this. */
    var revision: Long = 0L
        private set
    private var pendingTableWarning: Pair<FlowTableCursor, String>? = null

    /**
     * The live calculation engine for the table block currently being edited, keyed by block id.
     *
     * Without this, every keystroke in an embedded table constructed a fresh engine, which rebuilds the whole
     * dependency graph and recalculates every formula in the workbook — a full recalculation for a one-cell
     * edit. The cache is a single entry because only one table is edited at a time, and it is only reused when
     * the engine's workbook still matches what is stored, so a change from anywhere else (undo, another host,
     * a reload) falls back to a rebuild rather than rendering stale values.
     */
    private var tableEngineBlockId: String? = null
    private var tableEngine: CalculationEngine? = null

    private fun engineFor(blockId: String, workbook: com.norfold.app.editor.calculation.CalculationWorkbook): CalculationEngine {
        val cached = tableEngine?.takeIf { tableEngineBlockId == blockId && it.workbook() == workbook }
        if (cached != null) return cached
        val fresh = CalculationEngine(workbook)
        tableEngineBlockId = blockId
        tableEngine = fresh
        return fresh
    }

    private var selectionModeActive = false
    private var selectionRestore: SelectionSnapshot? = null

    private var slashBlockId: String? = null
    private var slashInput: FlowSlashInput? = null
    private var slashSession: FlowSlashSession? = null

    private var lastFailure: String? = null

    /**
     * The caret the runtime last asked the surface to show, and the stamp that makes the request idempotent.
     *
     * Held separately from the Selection Engine's caret on purpose. The engine's caret is *state* — where the
     * caret is, whoever put it there. This is an *event* — "the runtime moved it, go follow". Merging them
     * would make every field-originated caret report look like a fresh instruction to move focus.
     */
    private var caretRequest: FlowCaretRequest? = null
    private var caretStamp: Long = 0L

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
        val caretBefore = scope.caret()
        val result = registry.execute(commandId, context)
        when (result) {
            is CommandResult.Success -> {
                lastFailure = null
                // A command that moved the caret (§4.14) has to move real focus too, or the field the user is
                // looking at keeps its own stale cursor and the next keystroke lands in the wrong place. This
                // is the single place every route passes through, so no surface can forget to do it.
                publishCaretRequest(caretBefore)
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

    /**
     * Undo/redo go to the canonical history, not the registry — they are not per-selection commands.
     *
     * A history step rewrites block text behind the field's back, so the caret request is republished even
     * when the caret offset is unchanged: the *text* moved under it, and a field holding a stale
     * `TextFieldValue` would otherwise keep showing the pre-undo string until something else touched it.
     */
    fun undo(): Boolean = controller.undo().also { if (it) afterHistoryStep() }

    fun redo(): Boolean = controller.redo().also { if (it) afterHistoryStep() }

    private fun afterHistoryStep() {
        syncSelectionToDocument()
        scope.caret()?.let { caret ->
            val length = textLengthOf(caret.targetId, scope.caretItemId)
            // The document the caret pointed into may now be shorter; an offset past the end would make the
            // field clamp silently and disagree with the engine about where the caret is.
            requestCaret(caret.targetId, scope.caretItemId, caret.offset.coerceIn(0, length))
        }
        revision++
    }

    /**
     * Length of the plain text the caret addresses, for clamping. Zero when the target no longer exists, which
     * is the honest answer after a history step removed it.
     */
    private fun textLengthOf(blockId: String, itemId: String?): Int {
        val block = controller.document().findById(blockId) ?: return 0
        if (itemId != null) {
            return FlowInlineText.itemsOf(block)?.firstOrNull { it.id == itemId }?.content?.plainText()?.length ?: 0
        }
        return when (block) {
            is CodeBlock -> block.code.length
            else -> FlowInlineText.nodesOf(block)?.plainText()?.length ?: block.plainText().length
        }
    }

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
        itemsOf(
            (FlowIds.DOCK_ORDER + RelationshipCommandIds.contextual).distinct().mapNotNull(registry::get),
            CommandSurface.DOCK,
        )
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
        // The coalescing key names the *editable field*, not the block: two items of one list are separate
        // writing runs, and merging them would make one undo step swallow edits in both.
        val key = if (itemId != null) "block:$blockId/item:$itemId" else "block:$blockId"
        // A to-do holds `TodoItem`, not `ListItem`, so `editListItem` cannot see its items at all and would
        // return a cursor while changing nothing — typing into a checklist that silently discards every
        // keystroke. The item type decides which vocabulary call addresses it.
        val todo = itemId != null && controller.document().findById(blockId) is TodoListBlock
        val previous = controller.document()
        controller.mutate(
            "Typing",
            coalescingKey = key,
            afterTransform = { document -> relationshipReview.repairTextAnchors(document, blockId, singleTextEdit(before, after), after) },
        ) { session ->
            when {
                itemId == null -> session.editText(blockId, before, after)
                todo -> session.editTodoItem(blockId, itemId, before, after)
                else -> session.editListItem(blockId, itemId, before, after)
            }
        } ?: return false
        if (controller.document() == previous) return false
        revision++
        return true
    }

    private fun singleTextEdit(before: String, after: String): TextEdit {
        val prefix = before.commonPrefixWith(after).length
        val suffixLimit = minOf(before.length - prefix, after.length - prefix)
        var suffix = 0
        while (suffix < suffixLimit && before[before.lastIndex - suffix] == after[after.lastIndex - suffix]) suffix++
        return TextEdit(prefix, before.length - suffix, after.substring(prefix, after.length - suffix))
    }

    /**
     * Enter at [offset] in the caret's block (correction item 2).
     *
     * Writing semantics, not a structural command in disguise. Three cases, and the differences are the whole
     * point of handling this here rather than letting the field insert a `\n`:
     *
     *  - **empty list item** → leave the list and continue as a paragraph. Pressing Enter twice is how every
     *    editor ends a list; inserting a blank item instead is the behavior users read as "stuck".
     *  - **inside a list item** → split that item into two, so the caret lands on a fresh bullet.
     *  - **anywhere else** → `flow.split` from the registry, so Enter in prose is *literally* the same
     *    transaction, undo unit and post-command selection as the Split command from the dock.
     *
     * A newline inside a [CodeBlock] is content, so it is deliberately not handled: the field keeps it.
     */
    fun newLine(): Boolean {
        val caret = scope.caret() ?: return false
        val blockId = caret.targetId
        val block = controller.document().findById(blockId) ?: return false
        if (block is CodeBlock) return false
        val itemId = scope.caretItemId

        val items = FlowInlineText.itemsOf(block)
        if (itemId != null && items != null) {
            val item = items.firstOrNull { it.id == itemId } ?: return false
            val empty = item.content.plainText().isBlank()
            val before = controller.document()
            val cursor = controller.mutate(
                if (empty) "Leave list" else "Split list item",
                coalescingKey = null,
            ) { session ->
                if (empty) session.exitEmptyListItem(blockId, itemId)
                else session.splitListItem(blockId, itemId, caret.offset)
            } ?: return false
            // The vocabulary returns a cursor even when it declined to change anything. Publishing a caret
            // request for a move that never happened would yank focus for nothing, so the document is the
            // arbiter of whether this keystroke did something.
            if (controller.document() == before) return false
            requestCaret(cursor.blockId, cursor.itemId, cursor.offset)
            revision++
            return true
        }

        return invoke(FlowIds.SPLIT, CommandSurface.KEYBOARD) is CommandResult.Success
    }

    /**
     * Backspace with the caret at offset 0 (correction item 2).
     *
     * Only the boundary case belongs to the runtime — a Backspace anywhere else is an ordinary text edit the
     * field already reports through [editText]. At offset 0 there is no character to delete, so the keystroke
     * means "join this line to the one above", which is a document change no field can make on its own.
     *
     * Returns false when there is nothing above to join, letting the keystroke fall through unhandled rather
     * than being silently swallowed.
     */
    fun backspaceAtStart(): Boolean {
        val caret = scope.caret() ?: return false
        val blockId = caret.targetId
        val block = controller.document().findById(blockId) ?: return false
        val itemId = scope.caretItemId
        val items = FlowInlineText.itemsOf(block)

        if (itemId != null && items != null) {
            val index = items.indexOfFirst { it.id == itemId }
            if (index < 0) return false
            // The first item has no previous item to merge into; leaving the list is the meaningful move, and
            // it is the same operation Enter-on-empty performs, so both keys agree on what "exit" means.
            val before = controller.document()
            val cursor = controller.mutate(
                if (index == 0) "Leave list" else "Merge list item",
                coalescingKey = null,
            ) { session ->
                if (index == 0) session.exitEmptyListItem(blockId, itemId)
                else session.mergeListItemWithPrevious(blockId, itemId)
            } ?: return false
            // `exitEmptyListItem` declines on a *non-empty* first item, and the vocabulary still hands back a
            // cursor. Falling through unhandled is right there: the keystroke did nothing, so it should not
            // consume the key or move focus.
            if (controller.document() == before) return false
            requestCaret(cursor.blockId, cursor.itemId, cursor.offset)
            revision++
            return true
        }

        if (!statusOf(FlowIds.MERGE_PREVIOUS, CommandSurface.KEYBOARD).isInvokable) return false
        return invoke(FlowIds.MERGE_PREVIOUS, CommandSurface.KEYBOARD) is CommandResult.Success
    }

    /**
     * Builds the slash input for an edit the surface just reported (correction item 6).
     *
     * Both suppression facts are resolved here, against the **post-edit** document, which is why this is called
     * after [editText] rather than from the field's own state:
     *
     *  - **block context** — whether this is code, an equation or a diagram, where `/` is content. The surface
     *    used to pass this, computed from the *top-level row* it was rendering. Quotes, callouts and containers
     *    render their children recursively while reusing the outer row's callbacks, so a code block nested in a
     *    container was described as PROSE and the menu opened on a slash inside code. Only the addressed block
     *    can answer, so only the runtime asks.
     *  - **literalInline** — whether the caret sits inside a verbatim inline span. A plain string cannot say,
     *    and the resolver deliberately does not read documents.
     *
     * Fails closed when [blockId] no longer exists: an unknown target is reported as CODE, the most restrictive
     * context, so a menu never opens against a block that is gone.
     */
    fun slashInputFor(
        blockId: String,
        text: String,
        caret: Int,
        itemId: String? = null,
    ): FlowSlashInput {
        val block = controller.document().findById(blockId)
        return FlowSlashInput(
            text = text,
            caret = caret,
            context = if (block == null) FlowSlashContext.CODE else FlowSlashContext.of(block),
            literalInline = block != null && FlowInlineText.literalInlineAt(block, itemId, caret),
            itemId = itemId,
        )
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
    fun setCaret(blockId: String, offset: Int, itemId: String? = null) {
        if (relationshipReview.connectMode != null) {
            sourceRelationshipEndpoint(blockId)?.let(relationshipReview::commitConnectTarget)
            revision++
            return
        }
        val current = scope.caret()
        if (current?.targetId != blockId) controller.breakTypingGroup()
        // An item id is only meaningful if that item exists in that block. Storing one the document does not
        // have would make the next Enter/Backspace resolve against nothing and silently do the wrong thing,
        // so the boundary validates it here rather than trusting whatever the surface last saw.
        val block = controller.document().findById(blockId)
        scope.caretItemId = itemId?.takeIf { candidate ->
            block != null && FlowInlineText.itemsOf(block)?.any { it.id == candidate } == true
        }
        // Selection first, caret second: replacing the selection resets the engine's text state, so setting
        // the caret before selecting would silently discard it.
        if (!selectionModeActive) scope.selectBlocks(listOf(blockId))
        scope.selection.setCaret(TextCaret(blockId, offset))
        revision++
    }

    /**
     * Asks the surface to move real focus and the IME cursor to [blockId]/[itemId] at [offset].
     *
     * Called by commands that move the caret programmatically — split, merge, insert, undo, redo — so the
     * field the user is looking at actually follows. The stamp advances on every call so the surface can
     * apply the request exactly once and ignore echoes of its own caret reports.
     */
    private fun requestCaret(blockId: String, itemId: String?, offset: Int) {
        caretRequest = FlowCaretRequest(blockId, itemId, offset, ++caretStamp)
        setCaret(blockId, offset, itemId)
    }

    /**
     * Publishes a caret request when a command's §4.14 outcome actually moved the caret.
     *
     * Compares against [before] rather than requesting unconditionally: a command that only changes structure
     * (move up, nest, duplicate) leaves the caret where it was, and asking the surface to re-seed focus there
     * would steal it back from wherever the user had put it. Only a genuine move is published.
     */
    private fun publishCaretRequest(before: TextCaret?) {
        val after = scope.caret() ?: return
        if (after == before) return
        caretRequest = FlowCaretRequest(after.targetId, scope.caretItemId, after.offset, ++caretStamp)
    }

    /** Addresses a table cell so the directional row/column commands become eligible (§4.15). */
    fun setTableCursor(cursor: FlowTableCursor?) {
        // Addressing a cell must also make the table the primary structural target, or the cell looks selected
        // while every table command still resolves against whatever was selected before (correction item 7).
        if (cursor != null && controller.document().findById(cursor.blockId) == null) return
        // Moving the cursor abandons whatever was being typed into the previous cell. Committing it here
        // instead would turn "I tapped away" into a silent write; discarding it is the reversible choice, and
        // the deliberate commit routes below are the ones that write.
        if (cursor != scope.tableCursor) scope.tableDraft = null
        scope.tableCursor = cursor
        if (cursor != null) {
            controller.breakTypingGroup()
            scope.caretItemId = null
            if (!selectionModeActive) scope.selectBlocks(listOf(cursor.blockId))
            scope.selection.setCaret(TextCaret(cursor.blockId, 0))
            scope.tableDraft = editorTextAt(cursor)
        }
        revision++
    }

    /** The addressed cell, for the surface to highlight. Never row-local state — this is the command context. */
    fun tableCursor(): FlowTableCursor? = scope.tableCursor

    /** The in-flight cell text, or null when no cell is being typed into. */
    fun tableDraft(): String? = scope.tableDraft

    /**
     * Records a keystroke in the addressed cell without writing anything.
     *
     * The defect this replaces committed on every keystroke, so `=SUM(A1:A2)` reached the engine as eight
     * successive inputs — six of them unparseable — each one a History transaction, a full recalculation, and
     * a chance to raise a validation warning about text the user had not finished typing.
     */
    fun updateTableDraft(value: String): Boolean {
        if (scope.tableCursor == null) return false
        scope.tableDraft = value
        revision++
        return true
    }

    /** Commits the in-flight draft. The single write point for every in-cell commit route. */
    fun commitTableDraft(): CellCommitResult {
        val cursor = scope.tableCursor
            ?: return CellCommitResult(false, error = CalculationError(CalculationErrorKind.REFERENCE, "No cell is being edited"))
        val draft = scope.tableDraft
            ?: return CellCommitResult(false, error = CalculationError(CalculationErrorKind.REFERENCE, "No cell is being edited"))
        // A refused commit leaves the draft alone so the user can correct it; a confirmation prompt keeps it
        // for the same reason. `commitCalculatedCell` re-seeds it from storage when the write succeeds.
        return editTableCell(cursor, draft)
    }

    /** Abandons the in-flight draft, restoring the committed text. Back and Escape route here. */
    fun cancelTableDraft(): Boolean {
        if (scope.tableDraft == null) return false
        scope.tableDraft = scope.tableCursor?.let(::editorTextAt)
        pendingTableWarning = null
        revision++
        return true
    }

    /** The committed editor text of a cell — the formula when there is one, else the literal. */
    private fun editorTextAt(cursor: FlowTableCursor): String {
        val block = controller.document().findById(cursor.blockId) as? TableBlock ?: return ""
        return TableCalculationBridge.editorText(block, cursor.row, cursor.column)
    }

    /** Commits a visible table-cell edit through the shared calculation engine and canonical History. */
    fun editTableCell(cursor: FlowTableCursor, raw: String): CellCommitResult {
        val original = controller.document().findById(cursor.blockId) as? TableBlock
            ?: return CellCommitResult(false, error = CalculationError(CalculationErrorKind.REFERENCE, "Table no longer exists"))
        val upgraded = if (original.calculationPayload == null) {
            controller.snapshotBeforeMigration("Upgrade table ${original.id} before cell edit")
            (TableCalculationBridge.upgrade(original) as? TableUpgradeResult.Success)?.block
                ?: return CellCommitResult(false, error = CalculationError(CalculationErrorKind.INTERNAL, "Table could not be upgraded safely"))
        } else original
        val view = TableCalculationBridge.view(upgraded)
        val definition = view.workbook.embeddedTables.firstOrNull { it.id == view.tableId }
            ?: return CellCommitResult(false, error = CalculationError(CalculationErrorKind.REFERENCE, "Embedded table identity is missing"))
        val sheet = view.workbook.sheet(definition.range.start.sheetId)
            ?: return CellCommitResult(false, error = CalculationError(CalculationErrorKind.REFERENCE, "Table sheet is missing"))
        val row = sheet.rowIndex(definition.range.start.rowId) + cursor.row + if (definition.headerRow) 1 else 0
        val column = sheet.columnIndex(definition.range.start.columnId) + cursor.column
        if (cursor.row == -1 && definition.headerRow) {
            // Header cursor is represented as -1 in Flow but is the first typed row in the calculation model.
            return commitCalculatedCell(original, upgraded, view, sheet, sheet.rowIndex(definition.range.start.rowId), column, raw, cursor)
        }
        return commitCalculatedCell(original, upgraded, view, sheet, row, column, raw, cursor)
    }

    private fun commitCalculatedCell(
        original: TableBlock,
        upgraded: TableBlock,
        view: com.norfold.app.editor.calculation.TableWorkbookView,
        sheet: com.norfold.app.editor.calculation.CalculationSheet,
        row: Int,
        column: Int,
        raw: String,
        cursor: FlowTableCursor,
    ): CellCommitResult {
        val engine = engineFor(cursor.blockId, view.workbook)
        val input = CellInputParser.parse(raw)
        val confirmWarning = pendingTableWarning == (cursor to raw)
        val result = engine.commitInput(sheet.id, row, column, input, confirmWarning = confirmWarning)
        if (!result.accepted) {
            pendingTableWarning = if (result.requiresConfirmation) cursor to raw else null
            return if (result.requiresConfirmation) result.copy(warning = "${result.warning.orEmpty()} Submit the same value again to confirm.") else result
        }
        pendingTableWarning = null
        val after = TableCalculationBridge.persist(upgraded, engine.workbook(), view.tableId)
        controller.mutate("Edit table cell", coalescingKey = "table:${cursor.blockId}:${cursor.row}:${cursor.column}") { session ->
            session.replaceBlock(after)
            BlockCursor(cursor.blockId, 0)
        } ?: return CellCommitResult(false, error = CalculationError(CalculationErrorKind.INTERNAL, "Table edit was not committed"))
        scope.tableCursor = cursor
        // Re-seed the draft from what was actually stored. A formula commits as `=5+5` and renders as `10`;
        // leaving the draft alone would show the raw text against a cell that now holds a calculated value,
        // and re-seeding from the rendered value would destroy the formula on the next commit.
        scope.tableDraft = TableCalculationBridge.editorText(
            controller.document().findById(cursor.blockId) as? TableBlock ?: after,
            cursor.row,
            cursor.column,
        )
        revision++
        return result
    }

    /**
     * Expands or collapses a callout (correction item 16).
     *
     * Through the canonical history like every other change: a collapsed callout hides content, and a state
     * change the user can perform but not undo is exactly the asymmetry §13 exists to prevent.
     */
    fun toggleCollapsed(blockId: String): Boolean {
        val block = controller.document().findById(blockId) as? CalloutBlock ?: return false
        controller.mutate(
            if (block.collapsed) "Expand callout" else "Collapse callout",
            coalescingKey = null,
        ) { session ->
            session.replaceBlock(block.copy(collapsed = !block.collapsed))
            BlockCursor(blockId, 0, null)
        } ?: return false
        revision++
        return true
    }

    /**
     * The accessibility actions for [blockId] specifically (correction item 14).
     *
     * The defect this replaces resolved the catalogue once against the *global* selection, and the action
     * callback then re-pointed the selection at whichever row was activated. Availability and reason therefore
     * described a different block than the one that would be operated on — a screen-reader user could be told
     * "Unnest, unavailable: not nested" for a row that is in fact nested.
     *
     * Resolving against a temporarily-selected [blockId] means the enabled state, the reason and the execution
     * all describe the same row. The selection is restored afterwards so merely *reading* the action list never
     * changes what the user had selected; execution goes through [invokeAccessibilityOn], which re-selects
     * deliberately.
     */
    fun accessibilityActionsFor(blockId: String): List<FlowCommandItem> =
        withTemporarySelection(blockId) { accessibilityActions() }

    /**
     * Runs an accessibility action against [blockId]. Selects the row *first* and leaves it selected, because
     * the command genuinely operated on it and the post-command selection rule (§4.14) applies to it.
     */
    fun invokeAccessibilityOn(blockId: String, commandId: String): CommandResult {
        if (!selectionModeActive) scope.selectBlocks(listOf(blockId))
        return invoke(commandId, CommandSurface.ACCESSIBILITY)
    }

    private fun <T> withTemporarySelection(blockId: String, body: () -> T): T {
        if (controller.document().findById(blockId) == null) return body()
        val ids = scope.selectedBlockIds()
        val primary = scope.primaryBlockId()
        val caret = scope.caret()
        val tableCursorBefore = scope.tableCursor
        return try {
            scope.selectBlocks(listOf(blockId))
            scope.tableCursor = tableCursorBefore?.takeIf { it.blockId == blockId }
            body()
        } finally {
            scope.selection.clear()
            scope.selectBlocks(ids)
            primary?.takeIf { it in ids }?.let { scope.selection.promote(it) }
            caret?.let { scope.selection.setCaret(it) }
            scope.tableCursor = tableCursorBefore
        }
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
        if (relationshipReview.connectMode != null) {
            relationshipReview.exitConnectMode()
            revision++
            return FlowBackResult.ExitedConnectMode
        }
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
        if (surfaceId == FlowSurfaces.COLOR_STUDIO) editorStyle.closeColor(cancelAdjustment = true)
        if (surfaceId == FlowSurfaces.REVIEW) relationshipReview.closeReviewMode()
    }

    // ── Adaptive presentation (deliverable 7) ─────────────────────────────────────────────────────────

    /**
     * Applies a window change. Open tools retain durable presentation-independent state such as the selected
     * view and query. Color Studio uses the stricter pointer policy: an unfinished preview is restored to its
     * exact baseline before re-presentation, so rotation cannot silently commit or double-commit a drag.
     */
    fun onFormFactorChanged(next: FormFactor): Map<String, PresentedTool> {
        if (presentation.formFactor == next) {
            return presentation.onFormFactorChanged(next) { compactPreferenceFor(it) }
        }
        editorStyle.onFormFactorChanged()
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
        FlowSurfaces.COLOR_STUDIO, FlowSurfaces.FONT_BROWSER, FlowSurfaces.NAMED_STYLES,
        FlowSurfaces.RELATIONSHIP_MANAGER, FlowSurfaces.COMMENTS, FlowSurfaces.REVIEW -> CompactPreference.SHEET
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
            caretRequest = caretRequest,
            tableCursor = scope.tableCursor,
            tableDraft = scope.tableDraft,
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
            editorStyle = editorStyle.uiState(),
            relationshipReview = relationshipReview.uiState(sourceRelationshipEndpoint()?.anchor),
            interchange = interchange.state,
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
            val block = document.findById(caret.targetId)
            if (block == null) {
                controller.breakTypingGroup()
                // Focus must not stay in a block the runtime no longer treats as active (correction item 1).
                // Retarget to the nearest surviving block rather than leaving a caret pointing at a phantom.
                val fallback = surviving.firstOrNull() ?: document.blocks.firstOrNull()?.id
                if (fallback == null) {
                    scope.caretItemId = null
                } else {
                    scope.caretItemId = null
                    if (!selectionModeActive) scope.selectBlocks(listOf(fallback))
                    scope.selection.setCaret(TextCaret(fallback, 0))
                }
            } else if (scope.caretItemId != null && FlowInlineText.itemsOf(block)
                    ?.none { it.id == scope.caretItemId } != false
            ) {
                // The block survived but the item did not — a merge or a list exit consumed it. Keeping the
                // stale item id would make the next Enter/Backspace resolve against an item that is gone.
                scope.caretItemId = null
            }
        }
    }
}
