package com.norfold.app.ui.tasks

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import kotlin.math.abs

data class TaskColumnBounds(
    val columnId: Long,
    val bounds: Rect,
)

data class TaskCardBounds(
    val taskId: Long,
    val columnId: Long,
    val bounds: Rect,
)

data class TaskDropTarget(
    val columnId: Long,
    val index: Int,
)

object TaskDragPlanner {
    private const val HYSTERESIS_PX = 18f

    fun resolveDropTarget(
        pointer: Offset,
        columns: List<TaskColumnBounds>,
        cards: List<TaskCardBounds>,
        draggingTaskId: Long,
        previousTarget: TaskDropTarget? = null,
    ): TaskDropTarget? {
        if (columns.isEmpty()) return null
        val targetColumn = columns.firstOrNull { pointer.x in it.bounds.left..it.bounds.right }
            ?: columns.minByOrNull { column ->
                val centerX = (column.bounds.left + column.bounds.right) / 2f
                abs(pointer.x - centerX)
            }
            ?: return null

        val visibleCards = cards
            .filter { it.columnId == targetColumn.columnId && it.taskId != draggingTaskId }
            .sortedWith(compareBy<TaskCardBounds> { it.bounds.top }.thenBy { it.bounds.left })
        val index = visibleCards.indexOfFirst { pointer.y < it.bounds.center.y }
            .let { if (it < 0) visibleCards.size else it }
        val next = TaskDropTarget(targetColumn.columnId, index)
        return stabilizeTarget(pointer, next, previousTarget, columns, cards, draggingTaskId)
    }

    private fun stabilizeTarget(
        pointer: Offset,
        next: TaskDropTarget,
        previous: TaskDropTarget?,
        columns: List<TaskColumnBounds>,
        cards: List<TaskCardBounds>,
        draggingTaskId: Long,
    ): TaskDropTarget {
        if (previous == null || previous == next) return next
        val previousColumn = columns.firstOrNull { it.columnId == previous.columnId } ?: return next
        val nextColumn = columns.firstOrNull { it.columnId == next.columnId } ?: return next

        val previousCenterX = previousColumn.bounds.center.x
        val nextCenterX = nextColumn.bounds.center.x
        val previousStillCompetitive = abs(pointer.x - previousCenterX) <= abs(pointer.x - nextCenterX) + HYSTERESIS_PX
        if (previous.columnId != next.columnId && previousStillCompetitive) return previous

        if (previous.columnId == next.columnId && abs(slotY(previous, cards, draggingTaskId, previousColumn) - pointer.y) < HYSTERESIS_PX) {
            return previous
        }
        return next
    }

    private fun slotY(
        target: TaskDropTarget,
        cards: List<TaskCardBounds>,
        draggingTaskId: Long,
        column: TaskColumnBounds,
    ): Float {
        val visibleCards = cards
            .filter { it.columnId == target.columnId && it.taskId != draggingTaskId }
            .sortedWith(compareBy<TaskCardBounds> { it.bounds.top }.thenBy { it.bounds.left })
        return when {
            visibleCards.isEmpty() -> column.bounds.top + HYSTERESIS_PX
            target.index <= 0 -> visibleCards.first().bounds.top
            target.index >= visibleCards.size -> visibleCards.last().bounds.bottom
            else -> (visibleCards[target.index - 1].bounds.bottom + visibleCards[target.index].bounds.top) / 2f
        }
    }
}
