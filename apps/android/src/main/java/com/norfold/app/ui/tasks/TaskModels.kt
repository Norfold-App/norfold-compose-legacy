package com.norfold.app.ui.tasks

enum class TaskWorkspaceView(val key: String, val label: String) {
    Table("Table", "Table"),
    Board("Board", "Board"),
    Feed("Feed", "Feed"),
    Calendar("Calendar", "Calendar"),
    Chart("Chart", "Chart"),
    List("List", "List"),
    Timeline("Timeline", "Timeline"),
    Matrix("Matrix", "Matrix");

    companion object {
        fun fromKey(key: String?): TaskWorkspaceView {
            if (key.equals("Tasks", ignoreCase = true)) return Table
            // Legacy: the standalone Gallery view merged into Feed (grid display mode).
            if (key.equals("Gallery", ignoreCase = true)) return Feed
            return entries.firstOrNull { it.key.equals(key, ignoreCase = true) } ?: Table
        }
    }
}

enum class TaskEditorTarget {
    Color,
    Status,
    Assignee,
    Priority,
    Labels,
    Date,
    Checklist,
    Notes,
    Files,
    Comments,
    NewProperty,
    PropertySettings,
    ReadOnlyDetails,
}

enum class TaskWorkspaceSort(val key: String, val label: String) {
    Manual("Manual", "Manual"),
    Updated("Updated", "Updated"),
    DueDate("DueDate", "Due date"),
    Priority("Priority", "Priority"),
    Title("Title", "Title");

    companion object {
        fun fromKey(key: String?): TaskWorkspaceSort =
            entries.firstOrNull { it.key.equals(key, ignoreCase = true) } ?: Manual
    }
}

enum class TaskKanbanEngine(val key: String, val label: String) {
    BoardPointer("BoardPointer", "Kanban A");

    companion object {
        fun fromKey(@Suppress("UNUSED_PARAMETER") key: String?): TaskKanbanEngine = BoardPointer
    }
}

data class TaskInteractionStatus(
    val engine: TaskKanbanEngine = TaskKanbanEngine.BoardPointer,
    val draggingTask: String = "Idle",
    val target: String = "-",
    val lastDrop: String = "No drop yet",
)

enum class TaskRailAction {
    Search,
    Filter,
    Sort,
    New,
    Settings,
}
