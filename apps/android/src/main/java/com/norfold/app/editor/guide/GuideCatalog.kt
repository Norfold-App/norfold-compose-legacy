package com.norfold.app.editor.guide

import com.norfold.app.editor.command.Capability
import com.norfold.app.editor.command.CommandSurface
import com.norfold.app.editor.flags.EditorFeatureFlag
import com.norfold.app.editor.flow.FlowIds
import com.norfold.app.editor.relationship.RelationshipCommandIds
import com.norfold.app.editor.style.EditorStyleCommandIds
import com.norfold.app.editor.calculation.SpreadsheetCommandIds
import com.norfold.app.editor.interchange.InterchangeCommandIds

/** Indexed, offline catalog. No UI object, coordinate, network handle, or artifact content enters it. */
class GuideCatalog(topics: List<GuideTopic>) {
    private val ordered = topics.sortedWith(compareBy(GuideTopic::priority, { it.title }))
    private val byId = ordered.associateBy(GuideTopic::id)
    private val byDomain = GuideDomain.entries.associateWith { domain -> ordered.filter { it.domain == domain } }
    private val byHost = GuideHost.entries.associateWith { host -> ordered.filter { host in it.hosts } }

    init {
        require(byId.size == topics.size) { "Guide topic ids must be unique" }
        topics.forEach { topic ->
            require(topic.prerequisites.all(byId::containsKey)) { "Unknown prerequisite in ${topic.id.value}" }
        }
    }

    val size: Int get() = ordered.size

    fun topic(id: GuideTopicId): GuideTopic? = byId[id]

    fun topics(domain: GuideDomain): List<GuideTopic> = byDomain.getValue(domain)

    fun topics(host: GuideHost): List<GuideTopic> = byHost.getValue(host)

    fun search(
        query: String,
        host: GuideHost,
        category: GuideDomain? = null,
        limit: Int = MAX_SEARCH_RESULTS,
    ): List<GuideTopic> {
        val bounded = limit.coerceIn(1, MAX_SEARCH_RESULTS)
        val needle = query.trim().lowercase()
        return byHost.getValue(host).asSequence()
            .filter { category == null || it.domain == category }
            .filter { topic ->
                needle.isEmpty() || topic.title.lowercase().contains(needle) ||
                    topic.explanation.lowercase().contains(needle) ||
                    topic.steps.any { it.title.lowercase().contains(needle) || it.instruction.lowercase().contains(needle) }
            }
            .take(bounded)
            .toList()
    }

    companion object {
        const val MAX_SEARCH_RESULTS = 50
    }
}

/** The one central Phase-1–8 Guide catalog. Explanations are intentionally concise. */
object NorfoldGuideCatalog {
    val default: GuideCatalog by lazy { GuideCatalog(buildTopics()) }

    private fun buildTopics(): List<GuideTopic> = listOf(
        topic(
            "flow.touch-insert", "Insert with +", "Use + near the lower dock to add a block at the current position.",
            GuideDomain.FLOW, setOf(GuideHost.FLOW),
            target = command(FlowIds.insert(com.norfold.app.editor.flow.FlowBlockKind.PARAGRAPH), "Open the Insert surface from the lower dock."),
            completion = GuideCompletionRule.Commands(idPrefixes = setOf(FlowIds.INSERT_PREFIX)), priority = 10,
        ),
        topic(
            "flow.slash", "Slash commands", "Type / in editable text, then keep typing to narrow commands. The typed query is the search.",
            GuideDomain.FLOW, setOf(GuideHost.FLOW),
            target = GuideTarget.ContextTarget("flow.slash", "Place the caret in editable Flow text and type slash."),
            completion = GuideCompletionRule.Commands(idPrefixes = setOf(FlowIds.INSERT_PREFIX, FlowIds.CONVERT_PREFIX), requiredSurface = CommandSurface.SLASH),
            priority = 11,
        ),
        topic(
            "flow.structure", "Move and nest blocks", "Select a block to move, nest, unnest, wrap, or reorder it without losing its content.",
            GuideDomain.FLOW, setOf(GuideHost.FLOW),
            target = command(FlowIds.NEST, "Select a block, then use the structural actions in the context dock."),
            completion = GuideCompletionRule.Commands(exactIds = setOf(FlowIds.NEST, FlowIds.UNNEST, FlowIds.MOVE_UP, FlowIds.MOVE_DOWN, FlowIds.DROP)),
            priority = 20,
        ),
        topic(
            "flow.multi-select", "Select several blocks", "Use the visible Select action to work with several blocks. Long press is only a shortcut.",
            GuideDomain.FLOW, setOf(GuideHost.FLOW),
            target = GuideTarget.ContextTarget("flow.multi-select", "Use Select in the Flow top bar."),
            completion = signals(GuideSignal.FLOW_MULTI_SELECT), priority = 30,
        ),
        topic(
            "document.adaptive-original", "Adaptive and Original", "Adaptive fits content for comfortable reading; Original preserves exact page geometry.",
            GuideDomain.DOCUMENT, setOf(GuideHost.DOCUMENT),
            target = GuideTarget.ContextTarget("document.representation", "Use the Adaptive or Original control in the document top bar."),
            completion = signals(GuideSignal.DOCUMENT_REPRESENTATION_CHANGED), priority = 10,
        ),
        topic(
            "document.read-edit", "Read and Edit", "Read/Edit is independent from Adaptive/Original, so you can inspect or edit in either representation.",
            GuideDomain.DOCUMENT, setOf(GuideHost.DOCUMENT),
            target = GuideTarget.ContextTarget("document.intent", "Use the Read or Edit control in the document top bar."),
            completion = signals(GuideSignal.DOCUMENT_INTENT_CHANGED), priority = 11,
        ),
        topic(
            "document.page-control", "Page Control", "Enter Page Control deliberately to change page size, margins, columns, guides, and page structure.",
            GuideDomain.DOCUMENT, setOf(GuideHost.DOCUMENT),
            target = GuideTarget.ContextTarget("document.page-control", "Open Page Control from the document top bar."),
            completion = signals(GuideSignal.PAGE_CONTROL_OPENED), priority = 15,
        ),
        topic(
            "document.scene-selection", "Select scene objects", "Tap once to select an object. Use Select for multiple objects and exact controls for precise changes.",
            GuideDomain.DOCUMENT, setOf(GuideHost.DOCUMENT),
            target = GuideTarget.CapabilityTarget(Capability.SELECTION, "Select a scene object on the current page."),
            completion = signals(GuideSignal.SCENE_OBJECT_SELECTED), priority = 20,
        ),
        topic(
            "document.transform", "Resize, rotate, and snap", "Drag the visible handles for direct changes, or use exact geometry controls. Snap guides appear only when relevant.",
            GuideDomain.DOCUMENT, setOf(GuideHost.DOCUMENT),
            target = GuideTarget.CapabilityTarget(Capability.RESIZE, "Select an unlocked object to reveal transform controls."),
            completion = signals(GuideSignal.SCENE_OBJECT_TRANSFORMED, GuideSignal.SNAP_GUIDE_USED), priority = 21,
        ),
        topic(
            "styles.color-studio", "Color Studio", "Choose content color from one shared studio with swatches, exact values, gradients, and contrast tools.",
            GuideDomain.STYLES, setOf(GuideHost.FLOW, GuideHost.DOCUMENT, GuideHost.SPREADSHEET),
            target = command(EditorStyleCommandIds.TEXT_COLOR, "Select compatible content, then open a color action."),
            requiredFeatures = setOf(EditorFeatureFlag.COLOR_STUDIO),
            completion = GuideCompletionRule.Commands(exactIds = setOf(EditorStyleCommandIds.TEXT_COLOR, EditorStyleCommandIds.HIGHLIGHT, EditorStyleCommandIds.FILL, EditorStyleCommandIds.STROKE, SpreadsheetCommandIds.OPEN_FILL_STUDIO)),
            priority = 30,
        ),
        topic(
            "styles.named", "Named Styles", "Named styles keep repeated typography and object appearance consistent while preserving explicit local overrides.",
            GuideDomain.STYLES, setOf(GuideHost.FLOW, GuideHost.DOCUMENT),
            target = command(EditorStyleCommandIds.NAMED_STYLES, "Select styled content, then open Styles."),
            requiredFeatures = setOf(EditorFeatureFlag.NAMED_STYLES),
            completion = GuideCompletionRule.Commands(exactIds = setOf(EditorStyleCommandIds.NAMED_STYLES)), priority = 31,
        ),
        topic(
            "styles.fonts", "Offline and imported fonts", "Browse guaranteed-offline families, installed fonts, and validated fonts you imported yourself.",
            GuideDomain.STYLES, setOf(GuideHost.FLOW, GuideHost.DOCUMENT),
            target = command(EditorStyleCommandIds.FONT_BROWSER, "Select text, then open Font."),
            requiredFeatures = setOf(EditorFeatureFlag.OFFLINE_FONTS),
            completion = GuideCompletionRule.Commands(exactIds = setOf(EditorStyleCommandIds.FONT_BROWSER)), priority = 32,
        ),
        topic(
            "spreadsheet.formulas", "Enter formulas", "Start with = in a cell or the formula bar. References recalculate locally and offline.",
            GuideDomain.SPREADSHEET, setOf(GuideHost.SPREADSHEET),
            target = command(SpreadsheetCommandIds.BEGIN_EDIT, "Select a cell and begin editing."),
            requiredFeatures = setOf(EditorFeatureFlag.CALC_ENGINE), completion = signals(GuideSignal.FORMULA_COMMITTED), priority = 10,
        ),
        topic(
            "spreadsheet.ranges", "Select a range", "Drag or extend from the active cell to select the cells a formula, format, or chart should use.",
            GuideDomain.SPREADSHEET, setOf(GuideHost.SPREADSHEET),
            target = command(SpreadsheetCommandIds.EXTEND_SELECTION_RIGHT, "Select a cell, then extend the selection."),
            requiredFeatures = setOf(EditorFeatureFlag.CALC_ENGINE), completion = signals(GuideSignal.RANGE_SELECTED), priority = 11,
        ),
        topic(
            "spreadsheet.named-ranges", "Named ranges", "Give a stable name to a range so formulas remain readable and easier to manage.",
            GuideDomain.SPREADSHEET, setOf(GuideHost.SPREADSHEET), requiredFeatures = setOf(EditorFeatureFlag.CALC_ENGINE),
            target = GuideTarget.ContextTarget("spreadsheet.named-ranges", "Open Advanced tools and use the Named ranges manager."),
            completion = signals(GuideSignal.NAMED_RANGE_USED), priority = 20,
        ),
        topic(
            "spreadsheet.rules", "Validation and conditional formatting", "Validation protects input; conditional formatting explains patterns without changing stored values.",
            GuideDomain.SPREADSHEET, setOf(GuideHost.SPREADSHEET), requiredFeatures = setOf(EditorFeatureFlag.CALC_ENGINE),
            target = GuideTarget.ContextTarget("spreadsheet.rules", "Open Advanced tools and use the rules manager."),
            completion = signals(GuideSignal.VALIDATION_USED, GuideSignal.CONDITIONAL_FORMATTING_USED), priority = 21,
        ),
        topic(
            "chart.studio", "Chart Studio", "Edit data binding, layers, axes, labels, legends, and appearance without changing source data.",
            GuideDomain.CHART, setOf(GuideHost.FLOW, GuideHost.DOCUMENT, GuideHost.SPREADSHEET),
            requiredFeatures = setOf(EditorFeatureFlag.CHART_STUDIO),
            target = GuideTarget.ContextTarget("chart.studio", "Open an existing chart to enter Chart Studio."),
            completion = signals(GuideSignal.CHART_STUDIO_OPENED), priority = 10,
        ),
        topic(
            "chart.binding", "Bind chart data", "Bind a chart to a table or spreadsheet range so the visualization follows the source truth.",
            GuideDomain.CHART, setOf(GuideHost.FLOW, GuideHost.DOCUMENT, GuideHost.SPREADSHEET),
            requiredFeatures = setOf(EditorFeatureFlag.CHART_STUDIO, EditorFeatureFlag.CALC_ENGINE),
            target = GuideTarget.ContextTarget("chart.data-binding", "Use the Data section in Chart Studio."),
            completion = signals(GuideSignal.CHART_DATA_BOUND), priority = 20,
        ),
        topic(
            "chart.direct-edit", "Direct chart editing", "Select a mark and drag where supported; exact values remain available and source data stays inspectable.",
            GuideDomain.CHART, setOf(GuideHost.FLOW, GuideHost.DOCUMENT, GuideHost.SPREADSHEET),
            requiredFeatures = setOf(EditorFeatureFlag.CHART_STUDIO),
            target = GuideTarget.ContextTarget("chart.direct-manipulation", "Use an editable mark in Chart Studio."),
            completion = signals(GuideSignal.CHART_DIRECT_MANIPULATION), priority = 21,
        ),
        topic(
            "image.focus", "Image Focus", "Open Image Focus for non-destructive crop, geometry, adjustments, effects, and accessibility details.",
            GuideDomain.IMAGE, setOf(GuideHost.FLOW, GuideHost.DOCUMENT), requiredFeatures = setOf(EditorFeatureFlag.IMAGE_FOCUS),
            target = GuideTarget.ContextTarget("image.focus", "Open an image's edit action."),
            completion = signals(GuideSignal.IMAGE_FOCUS_OPENED), priority = 10,
        ),
        topic(
            "image.done-cancel", "Done and Cancel", "Done commits one higher-level edit. Cancel restores the exact state from before Image Focus opened.",
            GuideDomain.IMAGE, setOf(GuideHost.FLOW, GuideHost.DOCUMENT), requiredFeatures = setOf(EditorFeatureFlag.IMAGE_FOCUS),
            target = GuideTarget.ContextTarget("image.focus.done-cancel", "Use Done to keep changes or Cancel to restore the original session state."),
            completion = signals(GuideSignal.IMAGE_FOCUS_COMMITTED), priority = 20,
        ),
        topic(
            "shape.builder", "Shape Builder", "Build custom vector shapes: move nodes and Bézier handles, combine layers with Union, Subtract, Intersect and Exclude, then style the result.",
            GuideDomain.SHAPE, setOf(GuideHost.FLOW, GuideHost.DOCUMENT), requiredFeatures = setOf(EditorFeatureFlag.SHAPE_BUILDER),
            target = GuideTarget.ContextTarget("shape.builder", "Open a shape's edit action."),
            completion = signals(GuideSignal.SHAPE_BUILDER_OPENED), priority = 10,
        ),
        topic(
            "shape.done-cancel", "Done and Cancel", "Done records the whole session as one undo step. Cancel restores the exact shape from before Shape Builder opened.",
            GuideDomain.SHAPE, setOf(GuideHost.FLOW, GuideHost.DOCUMENT), requiredFeatures = setOf(EditorFeatureFlag.SHAPE_BUILDER),
            target = GuideTarget.ContextTarget("shape.builder.done-cancel", "Use Done to keep changes or Cancel to restore the original shape."),
            completion = signals(GuideSignal.SHAPE_BUILDER_COMMITTED), priority = 20,
        ),
        topic(
            "relationships.connect", "Connect mode", "Enter Connect mode deliberately, then choose a compatible target. Ordinary objects do not show permanent ports.",
            GuideDomain.RELATIONSHIPS, setOf(GuideHost.FLOW, GuideHost.DOCUMENT), requiredFeatures = setOf(EditorFeatureFlag.RELATIONSHIPS),
            target = command(RelationshipCommandIds.ENTER_CONNECT, "Select an eligible object or block, then choose Connect."),
            completion = GuideCompletionRule.Commands(exactIds = setOf(RelationshipCommandIds.ENTER_CONNECT)), priority = 10,
        ),
        topic(
            "relationships.review", "Relationships, comments, and review", "Use the manager to inspect links and anchored discussions, then navigate back to their targets.",
            GuideDomain.RELATIONSHIPS, setOf(GuideHost.FLOW, GuideHost.DOCUMENT), requiredFeatures = setOf(EditorFeatureFlag.RELATIONSHIPS),
            target = command(RelationshipCommandIds.OPEN_MANAGER, "Open Relationships or Review from the current selection."),
            completion = GuideCompletionRule.Commands(exactIds = setOf(RelationshipCommandIds.OPEN_MANAGER, RelationshipCommandIds.OPEN_REVIEW, RelationshipCommandIds.ADD_COMMENT)), priority = 20,
        ),
        topic(
            "interchange.modes", "Native and Compatibility", "Native mode unlocks Norfold features; Compatibility mode protects safer round-trip to the source format.",
            GuideDomain.INTERCHANGE, setOf(GuideHost.FLOW, GuideHost.DOCUMENT), requiredFeatures = setOf(EditorFeatureFlag.IMPORT_EXPORT_RAW),
            target = command(InterchangeCommandIds.PREFLIGHT, "Choose a file to import, then review its mode in preflight."),
            completion = GuideCompletionRule.Commands(exactIds = setOf(InterchangeCommandIds.PREFLIGHT)), priority = 10,
        ),
        topic(
            "interchange.fidelity", "Fidelity Inspector", "Review what was preserved, approximated, substituted, flattened, or left unsupported before export.",
            GuideDomain.INTERCHANGE, setOf(GuideHost.FLOW, GuideHost.DOCUMENT), requiredFeatures = setOf(EditorFeatureFlag.IMPORT_EXPORT_RAW),
            target = command(InterchangeCommandIds.FIDELITY, "Open Fidelity Inspector when a fidelity report is available."),
            completion = GuideCompletionRule.Commands(exactIds = setOf(InterchangeCommandIds.FIDELITY)), priority = 20,
        ),
        topic(
            "interchange.raw-source", "Raw Source safety", "Raw Source is versioned Norfold product source. It validates a draft and protects the document before Apply.",
            GuideDomain.INTERCHANGE, setOf(GuideHost.FLOW, GuideHost.DOCUMENT), requiredFeatures = setOf(EditorFeatureFlag.IMPORT_EXPORT_RAW),
            target = command(InterchangeCommandIds.RAW_OPEN, "Open Raw Source from the interchange tools."),
            completion = GuideCompletionRule.Commands(exactIds = setOf(InterchangeCommandIds.RAW_OPEN)), priority = 21,
        ),
    )

    private fun topic(
        id: String,
        title: String,
        explanation: String,
        domain: GuideDomain,
        hosts: Set<GuideHost>,
        target: GuideTarget? = null,
        requiredFeatures: Set<EditorFeatureFlag> = emptySet(),
        completion: GuideCompletionRule = GuideCompletionRule.Manual,
        priority: Int,
    ) = GuideTopic(
        id = GuideTopicId(id),
        title = title,
        explanation = explanation,
        domain = domain,
        hosts = hosts,
        target = target,
        steps = listOf(GuideStep("start", title, explanation, target = target)),
        requiredFeatures = requiredFeatures,
        completion = completion,
        priority = priority,
    )

    private fun command(id: String, fallback: String) = GuideTarget.Command(id, fallback)
    private fun signals(vararg values: GuideSignal) = GuideCompletionRule.Signals(values.toSet())
}
