package com.norfold.app.ui.tasks

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TaskDragPlannerTest {
    private val columns = listOf(
        TaskColumnBounds(1, Rect(0f, 0f, 200f, 600f)),
        TaskColumnBounds(2, Rect(240f, 0f, 440f, 600f)),
    )

    @Test
    fun sameColumnReorderToMiddle() {
        val target = TaskDragPlanner.resolveDropTarget(
            pointer = Offset(80f, 170f),
            columns = columns,
            cards = listOf(
                TaskCardBounds(10, 1, Rect(0f, 20f, 200f, 100f)),
                TaskCardBounds(11, 1, Rect(0f, 112f, 200f, 192f)),
                TaskCardBounds(12, 1, Rect(0f, 204f, 200f, 284f)),
            ),
            draggingTaskId = 10,
        )

        assertEquals(TaskDropTarget(1, 1), target)
    }

    @Test
    fun sameColumnReorderToTopAndEnd() {
        val cards = listOf(
            TaskCardBounds(10, 1, Rect(0f, 20f, 200f, 100f)),
            TaskCardBounds(11, 1, Rect(0f, 112f, 200f, 192f)),
            TaskCardBounds(12, 1, Rect(0f, 204f, 200f, 284f)),
        )

        assertEquals(
            TaskDropTarget(1, 0),
            TaskDragPlanner.resolveDropTarget(Offset(80f, 24f), columns, cards, draggingTaskId = 12),
        )
        assertEquals(
            TaskDropTarget(1, 2),
            TaskDragPlanner.resolveDropTarget(Offset(80f, 540f), columns, cards, draggingTaskId = 12),
        )
    }

    @Test
    fun crossColumnMoveToEnd() {
        val target = TaskDragPlanner.resolveDropTarget(
            pointer = Offset(320f, 520f),
            columns = columns,
            cards = listOf(
                TaskCardBounds(20, 2, Rect(240f, 20f, 440f, 100f)),
                TaskCardBounds(21, 2, Rect(240f, 112f, 440f, 192f)),
            ),
            draggingTaskId = 10,
        )

        assertEquals(TaskDropTarget(2, 2), target)
    }

    @Test
    fun gapChoosesNearestColumn() {
        val target = TaskDragPlanner.resolveDropTarget(
            pointer = Offset(222f, 40f),
            columns = columns,
            cards = emptyList(),
            draggingTaskId = 10,
        )

        assertEquals(TaskDropTarget(2, 0), target)
    }

    @Test
    fun emptyColumnAcceptsDrop() {
        val target = TaskDragPlanner.resolveDropTarget(
            pointer = Offset(320f, 240f),
            columns = columns,
            cards = listOf(TaskCardBounds(10, 1, Rect(0f, 20f, 200f, 100f))),
            draggingTaskId = 10,
        )

        assertEquals(TaskDropTarget(2, 0), target)
    }

    @Test
    fun hysteresisKeepsPreviousTargetNearBoundary() {
        val target = TaskDragPlanner.resolveDropTarget(
            pointer = Offset(224f, 40f),
            columns = columns,
            cards = emptyList(),
            draggingTaskId = 10,
            previousTarget = TaskDropTarget(1, 0),
        )

        assertEquals(TaskDropTarget(1, 0), target)
    }

    @Test
    fun emptyColumnsReturnNull() {
        val target = TaskDragPlanner.resolveDropTarget(
            pointer = Offset.Zero,
            columns = emptyList(),
            cards = emptyList(),
            draggingTaskId = 10,
        )

        assertNull(target)
    }
}
