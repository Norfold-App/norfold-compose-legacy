package com.norfold.app.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class TaskMoveNormalizerTest {
    @Test
    fun sameColumnMoveUpCompactsSortOrders() {
        val rows = listOf(
            TaskMoveRow(1, 0),
            TaskMoveRow(2, 1),
            TaskMoveRow(3, 2),
        )

        val normalized = TaskMoveNormalizer.insertAt(rows, movedId = 3, targetIndex = 0)

        assertEquals(listOf(3L, 1L, 2L), normalized.map { it.id })
        assertEquals(listOf(0, 1, 2), normalized.map { it.sortOrder })
    }

    @Test
    fun sameColumnMoveDownCompactsSortOrders() {
        val rows = listOf(
            TaskMoveRow(1, 0),
            TaskMoveRow(2, 1),
            TaskMoveRow(3, 2),
        )

        val normalized = TaskMoveNormalizer.insertAt(rows, movedId = 1, targetIndex = 2)

        assertEquals(listOf(2L, 3L, 1L), normalized.map { it.id })
        assertEquals(listOf(0, 1, 2), normalized.map { it.sortOrder })
    }

    @Test
    fun crossColumnInsertMiddleCreatesCompactTargetOrders() {
        val targetRows = listOf(
            TaskMoveRow(10, 3),
            TaskMoveRow(11, 9),
        )

        val normalized = TaskMoveNormalizer.insertAt(targetRows, movedId = 99, targetIndex = 1)

        assertEquals(listOf(10L, 99L, 11L), normalized.map { it.id })
        assertEquals(listOf(0, 1, 2), normalized.map { it.sortOrder })
    }

    @Test
    fun sourceColumnCompactionSkipsMovedTask() {
        val normalized = TaskMoveNormalizer.compact(
            rows = listOf(
                TaskMoveRow(1, 0),
                TaskMoveRow(2, 7),
                TaskMoveRow(3, 9),
            ),
            excludedId = 2,
        )

        assertEquals(listOf(1L, 3L), normalized.map { it.id })
        assertEquals(listOf(0, 1), normalized.map { it.sortOrder })
    }
}
