package com.norfold.app.domain

data class TaskMoveRow(
    val id: Long,
    val sortOrder: Int,
)

object TaskMoveNormalizer {
    fun compact(rows: List<TaskMoveRow>, excludedId: Long? = null): List<TaskMoveRow> =
        rows
            .filterNot { it.id == excludedId }
            .sortedWith(compareBy<TaskMoveRow> { it.sortOrder }.thenBy { it.id })
            .mapIndexed { index, row -> row.copy(sortOrder = index) }

    fun insertAt(rows: List<TaskMoveRow>, movedId: Long, targetIndex: Int): List<TaskMoveRow> {
        val withoutMoved = compact(rows, excludedId = movedId).toMutableList()
        val insertionIndex = targetIndex.coerceIn(0, withoutMoved.size)
        withoutMoved.add(insertionIndex, TaskMoveRow(movedId, insertionIndex))
        return withoutMoved.mapIndexed { index, row -> row.copy(sortOrder = index) }
    }
}
