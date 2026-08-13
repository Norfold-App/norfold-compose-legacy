package com.norfold.app.editor.chart

import com.norfold.app.editor.command.ApplicabilityPolicy
import com.norfold.app.editor.command.Capability
import com.norfold.app.editor.command.CommandContext
import com.norfold.app.editor.command.CommandRegistry
import com.norfold.app.editor.command.CommandResult
import com.norfold.app.editor.command.CommandStatus
import com.norfold.app.editor.command.CommandSurface
import com.norfold.app.editor.command.EditorCommand
import com.norfold.app.editor.selection.SelectionEngine
import com.norfold.app.editor.selection.SelectionGranularity
import com.norfold.app.editor.selection.SelectionTarget

data class ChartRuntimeStats(
    val resolutions: Long = 0,
    val layouts: Long = 0,
    val committedEdits: Long = 0,
    val cancelledGestures: Long = 0,
)

fun interface ChartCommitHost {
    /** Commits one complete user action to the owning host's canonical history. */
    fun commit(label: String, document: ChartDocument): Boolean
}

/**
 * Shared Chart Studio behavior. It owns preview/gesture state and commands, but persistence and undo remain
 * with the actual Flow/Document/Spreadsheet host through [ChartCommitHost].
 */
class ChartStudioRuntime(
    initial: ChartDocument,
    private val host: ChartCommitHost,
    private val readOnly: Boolean = false,
    private val calculationProvider: ChartCalculationDataProvider? = null,
) {
    private var committed = initial.normalized()
    private var gestureStart: ChartDocument? = null
    private var preview: ChartDocument? = null
    private var counters = ChartRuntimeStats()

    val selection = SelectionEngine().apply {
        select(SelectionTarget(committed.id, SelectionGranularity.CHART_WHOLE))
    }
    val commands = CommandRegistry().also(::registerCommands)

    fun document(): ChartDocument = preview ?: committed
    fun isReadOnly(): Boolean = readOnly
    fun hasGesture(): Boolean = gestureStart != null
    fun stats(): ChartRuntimeStats = counters

    fun selectSeries(seriesId: String): Boolean {
        if (gestureStart != null) return false
        if (document().series.none { it.id == seriesId }) return false
        committed = committed.copy(interaction = committed.interaction.copy(
            selectedSeriesId = seriesId,
            selectedSourceRowIndex = null,
            selectedSourceRowId = null,
        ))
        preview = preview?.copy(interaction = committed.interaction)
        selection.select(SelectionTarget("${committed.id}:$seriesId", SelectionGranularity.CHART_SERIES))
        return true
    }

    fun selectMark(seriesId: String, sourceRowIndex: Int, sourceRowId: String? = null): Boolean {
        if (gestureStart != null) return false
        if (sourceRowIndex < 0 || document().series.none { it.id == seriesId }) return false
        committed = committed.copy(interaction = committed.interaction.copy(
            selectedSeriesId = seriesId,
            selectedSourceRowIndex = sourceRowIndex,
            selectedSourceRowId = sourceRowId,
        ))
        preview = preview?.copy(interaction = committed.interaction)
        selection.select(SelectionTarget("${committed.id}:$seriesId:$sourceRowIndex", SelectionGranularity.CHART_MARK))
        return true
    }

    fun context(surface: CommandSurface = CommandSurface.DOCK): CommandContext = CommandContext(
        offeredCapabilities = if (readOnly) setOf(Capability.SELECTION) else setOf(
            Capability.SELECTION,
            Capability.DATA_BINDING,
            Capability.CHART_MARK_EDITING,
            Capability.FILL,
        ),
        targetCount = 1,
        hasPrimary = true,
        surface = surface,
    )

    fun invoke(id: String, surface: CommandSurface = CommandSurface.DOCK): CommandResult =
        commands.execute(id, context(surface))

    fun resolve(): ChartDataResolution = ChartDataResolver.resolve(document(), calculationProvider).also {
        counters = counters.copy(resolutions = counters.resolutions + 1)
    }

    fun layout(width: Float, height: Float, maxRenderedPoints: Int = 2_000): ChartLayout? {
        val ready = resolve() as? ChartDataResolution.Ready ?: return null
        return ChartLayoutEngine.layout(document(), ready.data, width, height, maxRenderedPoints).also {
            counters = counters.copy(layouts = counters.layouts + 1)
        }
    }

    fun commit(label: String, transform: (ChartDocument) -> ChartDocument): Boolean {
        if (readOnly || gestureStart != null) return false
        val next = transform(committed).normalized()
        if (next == committed || !host.commit(label, next)) return false
        committed = next
        counters = counters.copy(committedEdits = counters.committedEdits + 1)
        return true
    }

    fun beginGesture(): Boolean {
        if (readOnly || gestureStart != null) return false
        gestureStart = committed
        preview = committed
        return true
    }

    fun previewGesture(transform: (ChartDocument) -> ChartDocument): Boolean {
        val baseline = gestureStart ?: return false
        preview = transform(preview ?: baseline).normalized()
        return true
    }

    fun endGesture(label: String): Boolean {
        val before = gestureStart ?: return false
        val after = preview ?: before
        gestureStart = null
        preview = null
        if (after == before || !host.commit(label, after)) return false
        committed = after
        counters = counters.copy(committedEdits = counters.committedEdits + 1)
        return true
    }

    fun cancelGesture(): Boolean {
        if (gestureStart == null) return false
        gestureStart = null
        preview = null
        counters = counters.copy(cancelledGestures = counters.cancelledGestures + 1)
        return true
    }

    fun replaceFromOwner(next: ChartDocument) {
        gestureStart = null
        preview = null
        committed = next.normalized()
        selection.select(SelectionTarget(committed.id, SelectionGranularity.CHART_WHOLE))
    }

    private fun registerCommands(registry: CommandRegistry) {
        ChartType.entries.forEach { type ->
            registry.register(ChartRuntimeCommand(
                id = "chart.type.${type.name.lowercase()}",
                label = type.name.lowercase().replace('_', ' ').replaceFirstChar(Char::uppercase),
                description = "Use this chart type",
                runtime = this,
                active = { document().type == type },
                action = { commit("Change chart type") { it.copy(type = type) } },
            ))
        }
        registry.register(ChartRuntimeCommand(
            id = "chart.legend.toggle",
            label = "Legend",
            description = "Show or hide the legend",
            runtime = this,
            active = { document().legend.visible },
            action = { commit("Toggle chart legend") { it.copy(legend = it.legend.copy(visible = !it.legend.visible)) } },
        ))
        registry.register(ChartRuntimeCommand(
            id = "chart.labels.values.toggle",
            label = "Value labels",
            description = "Show or hide values on marks",
            runtime = this,
            active = { document().labels.showValue },
            action = { commit("Toggle chart value labels") { it.copy(labels = it.labels.copy(showValue = !it.labels.showValue)) } },
        ))
        registry.register(ChartRuntimeCommand(
            id = "chart.series.add",
            label = "Add series",
            description = "Add another series using an available value column",
            runtime = this,
            active = { false },
            available = { addableColumnId() != null && document().series.size < 64 },
            unavailableReason = "No additional value column is available",
            action = {
                val column = addableColumnId()
                if (column == null) false else commit("Add chart series") { current ->
                        val index = current.series.size
                        current.copy(series = current.series + ChartSeries(
                            id = "series-${java.util.UUID.randomUUID()}",
                            name = "Series ${index + 1}",
                            categoryColumnId = current.encoding.categoryColumnId,
                            valueColumnId = column,
                            color = current.style.palette[index % current.style.palette.size],
                            linePattern = ChartLinePattern.entries[index % ChartLinePattern.entries.size],
                        ))
                    }
            },
        ))
        registry.register(ChartRuntimeCommand(
            id = "chart.series.remove",
            label = "Remove series",
            description = "Remove the selected series",
            runtime = this,
            active = { false },
            available = { document().series.size > 1 },
            unavailableReason = "A chart must keep at least one series",
            action = {
                commit("Remove chart series") { current ->
                    val id = current.interaction.selectedSeriesId ?: current.series.last().id
                    current.copy(series = current.series.filterNot { it.id == id })
                }
            },
        ))
        listOf(-1 to "up", 1 to "down").forEach { (delta, direction) ->
            registry.register(ChartRuntimeCommand(
                id = "chart.series.move.$direction",
                label = "Move series $direction",
                description = "Move the selected series $direction in the chart order",
                runtime = this,
                active = { false },
                available = {
                    val current = document()
                    val index = current.series.indexOfFirst { it.id == current.interaction.selectedSeriesId }
                    index >= 0 && index + delta in current.series.indices
                },
                unavailableReason = "Select a series that can move $direction",
                action = {
                    commit("Move chart series $direction") { current ->
                        val index = current.series.indexOfFirst { it.id == current.interaction.selectedSeriesId }
                        if (index < 0 || index + delta !in current.series.indices) current
                        else current.copy(series = current.series.toMutableList().apply {
                            add(index + delta, removeAt(index))
                        })
                    }
                },
            ))
        }
        registry.register(ChartRuntimeCommand(
            id = "chart.style.reset",
            label = "Reset chart style",
            description = "Restore the chart theme defaults",
            runtime = this,
            active = { document().style == ChartStyle() },
            action = { commit("Reset chart style") { it.copy(style = ChartStyle()) } },
        ))
    }

    private fun addableColumnId(): String? {
        val source = document().source as? ChartDataSource.Manual ?: return null
        return source.columns.firstOrNull { column ->
            column.id != document().encoding.categoryColumnId &&
                column.id != document().encoding.xColumnId &&
                column.kind != ChartColumnKind.TEXT &&
                document().series.none { it.valueColumnId == column.id }
        }?.id
    }
}

private class ChartRuntimeCommand(
    override val id: String,
    override val label: String,
    override val description: String,
    private val runtime: ChartStudioRuntime,
    private val active: () -> Boolean,
    private val available: () -> Boolean = { true },
    private val unavailableReason: String = "This chart action is unavailable",
    private val action: () -> Boolean,
) : EditorCommand {
    override val icon: String? = null
    override val category: String = "Chart"
    override val aliases: List<String> = emptyList()
    override val requiredCapabilities: Set<Capability> = setOf(Capability.CHART_MARK_EDITING)
    override val applicability: ApplicabilityPolicy = ApplicabilityPolicy.PRIMARY_TARGET

    override fun status(context: CommandContext): CommandStatus = when {
        runtime.isReadOnly() -> CommandStatus.disabled("Switch to Edit mode to change this chart")
        Capability.CHART_MARK_EDITING !in context.offeredCapabilities -> CommandStatus.Hidden
        !available() -> CommandStatus.disabled(unavailableReason)
        active() -> CommandStatus.Active
        else -> CommandStatus.Available
    }

    override fun perform(context: CommandContext): CommandResult =
        if (action()) CommandResult.Success() else CommandResult.NoOp
}
