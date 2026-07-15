package com.norfold.app.domain

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

enum class TaskChartGroupBy(val label: String) {
    Status("Status"),
    Priority("Priority"),
    Label("Label"),
    Assignee("Assignee"),
    DueBucket("Due date"),
}

enum class TaskChartMeasure(val label: String) {
    Count("Count of tasks"),
    SumNumeric("Sum of number property"),
}

enum class TaskChartKind(val label: String) {
    Bar("Bar"),
    StackedBar("Stacked bar"),
    Pie("Pie"),
    Donut("Donut"),
    Line("Line"),
}

/**
 * Pure task-list → Vega-Lite spec pipeline for the Tasks workspace Chart view.
 * No Compose/Android dependencies so aggregation and spec shape stay unit-testable.
 */
object TaskChartSpecBuilder {

    data class Config(
        val kind: TaskChartKind = TaskChartKind.Bar,
        val groupBy: TaskChartGroupBy = TaskChartGroupBy.Status,
        val measure: TaskChartMeasure = TaskChartMeasure.Count,
        val numericPropertyName: String? = null,
    )

    /** One aggregated datum. [stack] is only meaningful for [TaskChartKind.StackedBar]. */
    data class Slice(val group: String, val stack: String, val value: Double)

    private const val DAY_MS = 24L * 60 * 60 * 1000

    /** Returns a friendly hint when the combo can't be charted, or null when it is valid. */
    fun validate(config: Config): String? = when {
        config.kind == TaskChartKind.StackedBar && config.groupBy == TaskChartGroupBy.Status ->
            "Stacked bars split each group by status — pick a group other than Status, or use Bar."
        config.measure == TaskChartMeasure.SumNumeric && config.numericPropertyName.isNullOrBlank() ->
            "Pick a number property to sum, or switch the measure back to Count."
        else -> null
    }

    fun aggregate(
        tasks: List<TaskItem>,
        config: Config,
        now: Long,
        numericValue: (TaskItem) -> Double? = { null },
    ): List<Slice> {
        val stacked = config.kind == TaskChartKind.StackedBar
        // A task may land in several groups (one per label); every entry is (group, task).
        val entries = tasks.flatMap { task -> groupsFor(task, config.groupBy, now).map { it to task } }
        return entries
            .groupBy { (group, task) -> group to if (stacked) statusLabel(task.status) else "" }
            .map { (key, grouped) ->
                val value = when (config.measure) {
                    TaskChartMeasure.Count -> grouped.size.toDouble()
                    TaskChartMeasure.SumNumeric -> grouped.sumOf { (_, task) -> numericValue(task) ?: 0.0 }
                }
                Slice(group = key.first, stack = key.second, value = value)
            }
            .sortedWith(compareBy({ groupRank(it.group, config.groupBy) }, { it.group }, { it.stack }))
    }

    fun build(title: String, slices: List<Slice>, config: Config): String {
        val groupTitle = config.groupBy.label
        val valueTitle = when (config.measure) {
            TaskChartMeasure.Count -> "Tasks"
            TaskChartMeasure.SumNumeric -> config.numericPropertyName ?: "Sum"
        }
        return buildJsonObject {
            put("${'$'}schema", "https://vega.github.io/schema/vega-lite/v5.json")
            put("title", title)
            put("data", buildJsonObject {
                put("values", buildJsonArray {
                    slices.forEach { slice ->
                        add(buildJsonObject {
                            put("group", slice.group)
                            if (slice.stack.isNotBlank()) put("stack", slice.stack)
                            put("value", slice.value)
                        })
                    }
                })
            })
            when (config.kind) {
                TaskChartKind.Bar, TaskChartKind.StackedBar -> {
                    put("mark", buildJsonObject { put("type", "bar") })
                    put("encoding", buildJsonObject {
                        put("x", axis("group", "nominal", groupTitle))
                        put("y", axis("value", "quantitative", valueTitle))
                        if (config.kind == TaskChartKind.StackedBar) {
                            put("color", axis("stack", "nominal", "Status"))
                        } else {
                            put("color", buildJsonObject {
                                put("field", "group")
                                put("type", "nominal")
                                put("title", groupTitle)
                                put("legend", JsonNull)
                            })
                        }
                        put("tooltip", tooltip(groupTitle, valueTitle, config.kind == TaskChartKind.StackedBar))
                    })
                }
                TaskChartKind.Pie, TaskChartKind.Donut -> {
                    put("mark", buildJsonObject {
                        put("type", "arc")
                        put("outerRadius", 110.0)
                        if (config.kind == TaskChartKind.Donut) put("innerRadius", 55.0)
                    })
                    put("encoding", buildJsonObject {
                        put("theta", axis("value", "quantitative", valueTitle))
                        put("color", axis("group", "nominal", groupTitle))
                        put("tooltip", tooltip(groupTitle, valueTitle, stacked = false))
                    })
                }
                TaskChartKind.Line -> {
                    put("mark", buildJsonObject {
                        put("type", "line")
                        put("point", true)
                    })
                    put("encoding", buildJsonObject {
                        put("x", axis("group", "nominal", groupTitle))
                        put("y", axis("value", "quantitative", valueTitle))
                        put("tooltip", tooltip(groupTitle, valueTitle, stacked = false))
                    })
                }
            }
        }.toString()
    }

    private fun axis(field: String, type: String, title: String) = buildJsonObject {
        put("field", field)
        put("type", type)
        put("title", title)
    }

    private fun tooltip(groupTitle: String, valueTitle: String, stacked: Boolean) = buildJsonArray {
        add(axis("group", "nominal", groupTitle))
        if (stacked) add(axis("stack", "nominal", "Status"))
        add(axis("value", "quantitative", valueTitle))
    }

    private fun groupsFor(task: TaskItem, groupBy: TaskChartGroupBy, now: Long): List<String> = when (groupBy) {
        TaskChartGroupBy.Status -> listOf(statusLabel(task.status))
        TaskChartGroupBy.Priority -> listOf(task.priority.name)
        TaskChartGroupBy.Assignee -> listOf(task.assignee.ifBlank { "Unassigned" })
        TaskChartGroupBy.Label -> task.labels.split(",").map(String::trim).filter(String::isNotBlank)
            .ifEmpty { listOf("No label") }
        TaskChartGroupBy.DueBucket -> listOf(dueBucket(task.dueAt, now))
    }

    private fun statusLabel(status: TaskStatus): String = when (status) {
        TaskStatus.Todo -> "To do"
        TaskStatus.Doing -> "In progress"
        TaskStatus.Done -> "Done"
    }

    fun dueBucket(dueAt: Long?, now: Long): String = when {
        dueAt == null -> "No due date"
        dueAt < now -> "Overdue"
        dueAt < now + DAY_MS -> "Next 24h"
        dueAt < now + 7 * DAY_MS -> "This week"
        else -> "Later"
    }

    /** Stable ordering so bars/legend don't shuffle between renders. */
    private fun groupRank(group: String, groupBy: TaskChartGroupBy): Int = when (groupBy) {
        TaskChartGroupBy.Status -> listOf("To do", "In progress", "Done").indexOf(group).let { if (it < 0) 99 else it }
        TaskChartGroupBy.Priority -> TaskPriority.entries.indexOfFirst { it.name == group }.let { if (it < 0) 99 else it }
        TaskChartGroupBy.DueBucket -> listOf("Overdue", "Next 24h", "This week", "Later", "No due date").indexOf(group).let { if (it < 0) 99 else it }
        else -> 0
    }
}
