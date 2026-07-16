package com.norfold.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskChartSpecBuilderTest {

    private val now = 1_000_000_000_000L
    private val day = 24L * 60 * 60 * 1000

    private fun task(
        id: Long,
        status: TaskStatus = TaskStatus.Todo,
        priority: TaskPriority = TaskPriority.Normal,
        assignee: String = "",
        labels: String = "",
        dueAt: Long? = null,
    ) = TaskItem(
        id = id,
        title = "Task $id",
        description = "",
        assignee = assignee,
        status = status,
        createdAt = now,
        updatedAt = now,
        priority = priority,
        dueAt = dueAt,
        labels = labels,
    )

    @Test
    fun aggregateCountsByStatusInStableOrder() {
        val tasks = listOf(
            task(1, status = TaskStatus.Done),
            task(2, status = TaskStatus.Todo),
            task(3, status = TaskStatus.Todo),
            task(4, status = TaskStatus.Doing),
        )
        val slices = TaskChartSpecBuilder.aggregate(
            tasks,
            TaskChartSpecBuilder.Config(groupBy = TaskChartGroupBy.Status),
            now,
        )
        assertEquals(listOf("To do", "In progress", "Done"), slices.map { it.group })
        assertEquals(listOf(2.0, 1.0, 1.0), slices.map { it.value })
    }

    @Test
    fun aggregateDueBucketsUseFixedNow() {
        val tasks = listOf(
            task(1, dueAt = null),
            task(2, dueAt = now - day),
            task(3, dueAt = now + day / 2),
            task(4, dueAt = now + 3 * day),
            task(5, dueAt = now + 30 * day),
        )
        val slices = TaskChartSpecBuilder.aggregate(
            tasks,
            TaskChartSpecBuilder.Config(groupBy = TaskChartGroupBy.DueBucket),
            now,
        )
        assertEquals(listOf("Overdue", "Next 24h", "This week", "Later", "No due date"), slices.map { it.group })
        assertTrue(slices.all { it.value == 1.0 })
    }

    @Test
    fun aggregateSplitsMultiLabelTasksAndFallsBackToNoLabel() {
        val tasks = listOf(
            task(1, labels = "home, work"),
            task(2, labels = "work"),
            task(3),
        )
        val slices = TaskChartSpecBuilder.aggregate(
            tasks,
            TaskChartSpecBuilder.Config(groupBy = TaskChartGroupBy.Label),
            now,
        )
        val byGroup = slices.associate { it.group to it.value }
        assertEquals(1.0, byGroup["home"])
        assertEquals(2.0, byGroup["work"])
        assertEquals(1.0, byGroup["No label"])
    }

    @Test
    fun aggregateSumsNumericPropertyViaLambda() {
        val tasks = listOf(
            task(1, assignee = "Amina"),
            task(2, assignee = "Amina"),
            task(3, assignee = ""),
        )
        val points = mapOf(1L to 3.0, 2L to 5.0)
        val slices = TaskChartSpecBuilder.aggregate(
            tasks,
            TaskChartSpecBuilder.Config(
                groupBy = TaskChartGroupBy.Assignee,
                measure = TaskChartMeasure.SumNumeric,
                numericPropertyName = "Points",
            ),
            now,
            numericValue = { points[it.id] },
        )
        val byGroup = slices.associate { it.group to it.value }
        assertEquals(8.0, byGroup["Amina"])
        assertEquals(0.0, byGroup["Unassigned"])
    }

    @Test
    fun aggregateStackedBarCarriesStatusStacks() {
        val tasks = listOf(
            task(1, priority = TaskPriority.High, status = TaskStatus.Todo),
            task(2, priority = TaskPriority.High, status = TaskStatus.Done),
            task(3, priority = TaskPriority.High, status = TaskStatus.Done),
        )
        val slices = TaskChartSpecBuilder.aggregate(
            tasks,
            TaskChartSpecBuilder.Config(kind = TaskChartKind.StackedBar, groupBy = TaskChartGroupBy.Priority),
            now,
        )
        assertEquals(2, slices.size)
        val done = slices.first { it.stack == "Done" }
        assertEquals("High", done.group)
        assertEquals(2.0, done.value, 0.0)
    }

    @Test
    fun validateFlagsBadCombosAndAcceptsGoodOnes() {
        assertNotNull(
            TaskChartSpecBuilder.validate(
                TaskChartSpecBuilder.Config(kind = TaskChartKind.StackedBar, groupBy = TaskChartGroupBy.Status),
            ),
        )
        assertNotNull(
            TaskChartSpecBuilder.validate(
                TaskChartSpecBuilder.Config(measure = TaskChartMeasure.SumNumeric, numericPropertyName = null),
            ),
        )
        assertNull(TaskChartSpecBuilder.validate(TaskChartSpecBuilder.Config()))
        assertNull(
            TaskChartSpecBuilder.validate(
                TaskChartSpecBuilder.Config(
                    kind = TaskChartKind.StackedBar,
                    groupBy = TaskChartGroupBy.Priority,
                    measure = TaskChartMeasure.SumNumeric,
                    numericPropertyName = "Points",
                ),
            ),
        )
    }

    @Test
    fun buildEmitsBarSpecWithDataValues() {
        val config = TaskChartSpecBuilder.Config(kind = TaskChartKind.Bar, groupBy = TaskChartGroupBy.Status)
        val spec = TaskChartSpecBuilder.build(
            "Tasks by Status",
            listOf(TaskChartSpecBuilder.Slice("To do", "", 2.0)),
            config,
        )
        assertTrue(spec.contains("\"\$schema\":\"https://vega.github.io/schema/vega-lite/v5.json\""))
        assertTrue(spec.contains("\"title\":\"Tasks by Status\""))
        assertTrue(spec.contains("\"type\":\"bar\""))
        assertTrue(spec.contains("\"group\":\"To do\""))
        assertTrue(spec.contains("\"value\":2.0"))
    }

    @Test
    fun buildDonutUsesArcWithInnerRadiusButPieDoesNot() {
        val slices = listOf(TaskChartSpecBuilder.Slice("Low", "", 1.0))
        val donut = TaskChartSpecBuilder.build(
            "t",
            slices,
            TaskChartSpecBuilder.Config(kind = TaskChartKind.Donut, groupBy = TaskChartGroupBy.Priority),
        )
        assertTrue(donut.contains("\"type\":\"arc\""))
        assertTrue(donut.contains("\"innerRadius\""))
        val pie = TaskChartSpecBuilder.build(
            "t",
            slices,
            TaskChartSpecBuilder.Config(kind = TaskChartKind.Pie, groupBy = TaskChartGroupBy.Priority),
        )
        assertTrue(pie.contains("\"type\":\"arc\""))
        assertTrue(!pie.contains("\"innerRadius\""))
    }

    @Test
    fun buildStackedBarEncodesStackColorField() {
        val spec = TaskChartSpecBuilder.build(
            "t",
            listOf(TaskChartSpecBuilder.Slice("High", "Done", 2.0)),
            TaskChartSpecBuilder.Config(kind = TaskChartKind.StackedBar, groupBy = TaskChartGroupBy.Priority),
        )
        assertTrue(spec.contains("\"field\":\"stack\""))
        assertTrue(spec.contains("\"stack\":\"Done\""))
    }
}
