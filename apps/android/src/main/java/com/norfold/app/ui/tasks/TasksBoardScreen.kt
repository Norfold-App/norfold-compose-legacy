@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.norfold.app.ui.tasks

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Sort
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.CheckBox
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DragIndicator
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Event
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.TableRows
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.material.icons.outlined.Title
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.zIndex
import com.norfold.app.ui.components.MarkdownPreview
import com.norfold.app.domain.TaskBoardItem
import com.norfold.app.domain.TaskChecklistItem
import com.norfold.app.domain.TaskColumnItem
import com.norfold.app.domain.TaskItem
import com.norfold.app.domain.TaskPriority
import com.norfold.app.domain.TaskPropertyDefinition
import com.norfold.app.domain.TaskPropertyType
import com.norfold.app.domain.TaskPropertyValue
import com.norfold.app.domain.TaskStatus
import com.norfold.app.domain.TaskDateRange
import com.norfold.app.domain.TaskDateRangeCodec
import com.norfold.app.domain.WorkspaceComment
import com.norfold.app.domain.WorkspaceObjectType
import com.norfold.app.domain.WorkspaceFileItem
import com.norfold.app.ui.NotesUiState
import com.norfold.app.ui.NotesViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun TasksBoardScreen(
    state: NotesUiState,
    viewModel: NotesViewModel,
    onPickTaskAttachment: (TaskItem) -> Unit = {},
) {
    val currentView = TaskWorkspaceView.fromKey(state.settings.taskViewMode)
    val sort = TaskWorkspaceSort.fromKey(state.settings.taskSortMode)
    val kanbanEngine = TaskKanbanEngine.BoardPointer
    var query by remember { mutableStateOf("") }
    var assigneeFilter by remember { mutableStateOf("") }
    var labelFilter by remember { mutableStateOf("") }
    var priorityFilter by remember { mutableStateOf<TaskPriority?>(null) }
    var activeRailAction by remember { mutableStateOf<TaskRailAction?>(null) }
    var editingTask by remember { mutableStateOf<TaskItem?>(null) }
    var boardName by remember { mutableStateOf("") }
    var newBoardName by remember { mutableStateOf("") }
    var newColumnName by remember { mutableStateOf("") }
    var interactionStatus by remember { mutableStateOf(TaskInteractionStatus(engine = kanbanEngine)) }

    val selectedBoard = state.taskBoards.firstOrNull { it.id == state.settings.taskSelectedBoardId }
        ?: state.taskBoards.firstOrNull()
    val selectedBoardId = selectedBoard?.id ?: 1L
    val columns = state.taskColumns
        .filter { it.boardId == selectedBoardId }
        .sortedBy { it.sortOrder }
    val taskProperties = state.taskPropertyDefinitions
        .filter { it.boardId == selectedBoardId }
        .sortedWith(compareBy<TaskPropertyDefinition> { it.sortOrder }.thenBy { it.id })
    val taskPropertyValues = state.taskPropertyValues
    val taskChecklistItems = state.taskChecklistItems
    val boardTasks = state.tasks
        .filter { it.taskBoardId == selectedBoardId || (selectedBoard == null && it.taskBoardId == 1L) }
    val filteredTasks = boardTasks
        .filterTasks(query, assigneeFilter, labelFilter, priorityFilter)
        .sortedFor(sort)
    val assignees = state.tasks.map { it.assignee.ifBlank { "@owner" } }.distinct().sorted()
    val labels = state.tasks.flatMap { it.labels.split(",").map(String::trim) }.filter(String::isNotBlank).distinct().sorted()

    LaunchedEffect(selectedBoard?.id) {
        selectedBoard?.let { board ->
            if (state.settings.taskSelectedBoardId != board.id) {
                viewModel.patchSettings { it.copy(taskSelectedBoardId = board.id) }
            }
            boardName = board.name
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(taskBackground())
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(start = 18.dp, top = 70.dp, end = 18.dp, bottom = 160.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                TaskHeader(
                    board = selectedBoard,
                    currentView = currentView,
                    query = query,
                    onQueryChange = { query = it },
                    activeAction = activeRailAction,
                    onActionToggle = { action -> activeRailAction = if (activeRailAction == action) null else action },
                    onViewChange = { next ->
                        viewModel.patchSettings { it.copy(taskViewMode = next.key) }
                    },
                )
            }
            item {
                when (currentView) {
                    TaskWorkspaceView.Table -> TaskDatabaseTable(
                        tasks = filteredTasks,
                        columns = columns,
                        properties = taskProperties,
                        propertyValues = taskPropertyValues,
                        checklistItems = taskChecklistItems,
                        viewModel = viewModel,
                        onPickAttachment = onPickTaskAttachment,
                        onTaskClick = { editingTask = it },
                    )
                    TaskWorkspaceView.Board -> TaskKanbanBoard(
                        columns = columns,
                        tasks = filteredTasks,
                        checklistItems = taskChecklistItems,
                        engine = kanbanEngine,
                        compact = state.settings.taskCompactLayout,
                        onTaskClick = { editingTask = it },
                        onAddTask = { column, title -> viewModel.addTaskToColumn(title, column) },
                        onMoveTask = { taskId, columnId, index -> viewModel.moveTaskToColumnAtIndex(taskId, columnId, index) },
                        onRenameColumn = { column, name -> viewModel.renameTaskColumn(column, name) },
                        onMoveColumn = { column, delta -> viewModel.moveTaskColumn(column, delta) },
                        onDeleteColumn = { column -> viewModel.deleteTaskColumn(column) },
                        onInteractionStatus = { interactionStatus = it },
                    )
                    TaskWorkspaceView.Matrix -> TaskMatrixView(
                        columns = columns,
                        tasks = filteredTasks,
                        onTaskClick = { editingTask = it },
                    )
                    TaskWorkspaceView.List -> TaskListView(filteredTasks, columns, taskProperties, taskPropertyValues, taskChecklistItems, onTaskClick = { editingTask = it })
                    TaskWorkspaceView.Feed -> TaskFeedView(filteredTasks, taskProperties, taskPropertyValues, taskChecklistItems, onTaskClick = { editingTask = it })
                    TaskWorkspaceView.Calendar -> TaskCalendarView(filteredTasks, taskChecklistItems, onTaskClick = { editingTask = it })
                    TaskWorkspaceView.Chart -> TaskChartView(filteredTasks)
                    TaskWorkspaceView.Gallery -> TaskGalleryView(filteredTasks, taskChecklistItems, onTaskClick = { editingTask = it })
                }
            }
        }

        TaskRailPanel(
            action = activeRailAction,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 116.dp, end = 18.dp, start = 18.dp)
                .widthIn(max = 300.dp),
            query = query,
            onQueryChange = { query = it },
            assignees = assignees,
            assigneeFilter = assigneeFilter,
            onAssigneeFilter = { assigneeFilter = it },
            labels = labels,
            labelFilter = labelFilter,
            onLabelFilter = { labelFilter = it },
            priorityFilter = priorityFilter,
            onPriorityFilter = { priorityFilter = it },
            sort = sort,
            onSortChange = { next -> viewModel.patchSettings { it.copy(taskSortMode = next.key) } },
            compact = state.settings.taskCompactLayout,
            onCompactChange = { next -> viewModel.patchSettings { it.copy(taskCompactLayout = next) } },
            boards = state.taskBoards,
            selectedBoardId = selectedBoardId,
            onSelectBoard = { boardId -> viewModel.patchSettings { it.copy(taskSelectedBoardId = boardId) } },
            boardName = boardName,
            onBoardNameChange = { boardName = it },
            onRenameBoard = { newName -> selectedBoard?.let { viewModel.renameTaskBoard(it.id, newName) } },
            newBoardName = newBoardName,
            onNewBoardNameChange = { newBoardName = it },
            onCreateBoard = {
                viewModel.createTaskBoard(newBoardName)
                newBoardName = ""
            },
            newColumnName = newColumnName,
            onNewColumnNameChange = { newColumnName = it },
            onCreateColumn = {
                viewModel.createTaskColumn(selectedBoardId, newColumnName)
                newColumnName = ""
            },
            onDismiss = { activeRailAction = null },
        )

        editingTask?.let { task ->
            AdaptiveTaskPage(
                task = state.tasks.firstOrNull { it.id == task.id } ?: task,
                properties = taskProperties,
                propertyValues = taskPropertyValues,
                checklistItems = taskChecklistItems,
                columns = columns,
                taskObjectId = state.workspaceObjects.firstOrNull { it.objectType == WorkspaceObjectType.Task && it.sourceId == task.id }?.id,
                comments = state.workspaceComments,
                files = state.workspaceFiles,
                viewModel = viewModel,
                onPickAttachment = { onPickTaskAttachment(task) },
                onDelete = {
                    viewModel.deleteTask(task)
                    editingTask = null
                },
                onDismiss = { editingTask = null },
            )
        }
    }
}

@Composable
private fun TaskHeader(
    board: TaskBoardItem?,
    currentView: TaskWorkspaceView,
    query: String,
    onQueryChange: (String) -> Unit,
    activeAction: TaskRailAction?,
    onActionToggle: (TaskRailAction) -> Unit,
    onViewChange: (TaskWorkspaceView) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = board?.name ?: "To-Do List",
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.Black,
            fontSize = 30.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            leadingIcon = { Icon(Icons.Outlined.Search, null) },
            trailingIcon = {
                Row {
                    IconButton(onClick = { onActionToggle(TaskRailAction.Filter) }) {
                        Icon(Icons.Outlined.GridView, null, tint = if (activeAction == TaskRailAction.Filter) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = { onActionToggle(TaskRailAction.Sort) }) {
                        Icon(Icons.AutoMirrored.Outlined.Sort, null, tint = if (activeAction == TaskRailAction.Sort) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = { onActionToggle(TaskRailAction.New) }) {
                        Icon(Icons.Outlined.Add, null, tint = if (activeAction == TaskRailAction.New) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = { onActionToggle(TaskRailAction.Settings) }) {
                        Icon(Icons.Outlined.Settings, null, tint = if (activeAction == TaskRailAction.Settings) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            },
            placeholder = { Text("Search tasks, assignees, labels") },
            shape = RoundedCornerShape(18.dp),
            colors = taskTextFieldColors(),
        )
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(TaskWorkspaceView.entries) { view ->
                val selected = view == currentView
                val bg by animateColorAsState(
                    targetValue = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
                    label = "task-tab",
                )
                Surface(
                    color = bg,
                    contentColor = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.clickable { onViewChange(view) },
                ) {
                    Text(view.label, modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp), fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun TaskKanbanBoard(
    columns: List<TaskColumnItem>,
    tasks: List<TaskItem>,
    checklistItems: List<TaskChecklistItem>,
    engine: TaskKanbanEngine,
    compact: Boolean,
    onTaskClick: (TaskItem) -> Unit,
    onAddTask: (TaskColumnItem, String) -> Unit,
    onMoveTask: (Long, Long, Int) -> Unit,
    onRenameColumn: (TaskColumnItem, String) -> Unit,
    onMoveColumn: (TaskColumnItem, Int) -> Unit,
    onDeleteColumn: (TaskColumnItem) -> Unit,
    onInteractionStatus: (TaskInteractionStatus) -> Unit,
) {
    TaskPointerKanbanBoard(
        columns = columns,
        tasks = tasks,
        checklistItems = checklistItems,
        engine = engine,
        compact = compact,
        onTaskClick = onTaskClick,
        onAddTask = onAddTask,
        onMoveTask = onMoveTask,
        onRenameColumn = onRenameColumn,
        onMoveColumn = onMoveColumn,
        onDeleteColumn = onDeleteColumn,
        onInteractionStatus = onInteractionStatus,
    )
}

@Composable
private fun TaskPointerKanbanBoard(
    columns: List<TaskColumnItem>,
    tasks: List<TaskItem>,
    checklistItems: List<TaskChecklistItem>,
    engine: TaskKanbanEngine,
    compact: Boolean,
    onTaskClick: (TaskItem) -> Unit,
    onAddTask: (TaskColumnItem, String) -> Unit,
    onMoveTask: (Long, Long, Int) -> Unit,
    onRenameColumn: (TaskColumnItem, String) -> Unit,
    onMoveColumn: (TaskColumnItem, Int) -> Unit,
    onDeleteColumn: (TaskColumnItem) -> Unit,
    onInteractionStatus: (TaskInteractionStatus) -> Unit,
) {
    val listState = rememberLazyListState()
    val dragState = remember { BoardDragState() }
    val density = LocalDensity.current
    val tasksByColumn = columns.associate { column ->
        column.id to tasks.filter { it.taskColumnId == column.id }.sortedBy { it.sortOrder }
    }
    dragState.tasksById = tasks.associateBy { it.id }

    LaunchedEffect(engine) {
        onInteractionStatus(TaskInteractionStatus(engine = engine))
    }
    LaunchedEffect(dragState.draggingTask?.id) {
        while (dragState.draggingTask != null) {
            val velocity = dragState.edgeScrollVelocity()
            if (velocity != 0f) listState.scrollBy(velocity)
            delay(16)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 530.dp)
            .onGloballyPositioned { dragState.boardBounds = it.boundsInRoot() }
            .pointerInput(columns, tasks, dragState.boardBounds) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val hit = dragState.hitCard(down.position) ?: return@awaitEachGesture
                    val longPress = awaitLongPressOrCancellation(down.id) ?: return@awaitEachGesture
                    val grabOffset = longPress.position - hit.bounds.topLeft
                    dragState.start(hit.task, longPress.position, grabOffset)
                    onInteractionStatus(dragState.status(engine, "Dragging ${hit.task.title.ifBlank { "Untitled task" }}"))
                    drag(longPress.id) { change ->
                        change.consume()
                        dragState.pointer = change.position
                        onInteractionStatus(dragState.status(engine, "Dragging ${hit.task.title.ifBlank { "Untitled task" }}"))
                    }
                    val target = dragState.currentTarget()
                    val task = dragState.draggingTask
                    val dropText = if (task != null && target != null) {
                        val columnName = columns.firstOrNull { it.id == target.columnId }?.name ?: "Column"
                        "Dropped ${task.title.ifBlank { "Untitled task" }} -> $columnName #${target.index + 1}"
                    } else {
                        "Drop cancelled"
                    }
                    dragState.clear(keepLastDrop = dropText)
                    onInteractionStatus(TaskInteractionStatus(engine = engine, lastDrop = dropText))
                    if (task != null && target != null) onMoveTask(task.id, target.columnId, target.index)
                }
            },
    ) {
        LazyRow(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            contentPadding = PaddingValues(end = 18.dp),
        ) {
            items(columns, key = { it.id }) { column ->
                TaskKanbanColumn(
                    column = column,
                    tasks = tasksByColumn[column.id].orEmpty(),
                    checklistItems = checklistItems,
                    compact = compact,
                    drag = dragState,
                    onTaskClick = onTaskClick,
                    onAddTask = { title -> onAddTask(column, title) },
                    onMoveColumn = { delta -> onMoveColumn(column, delta) },
                    onRenameColumn = { name -> onRenameColumn(column, name) },
                    onDeleteColumn = { onDeleteColumn(column) },
                    onDragMove = {},
                    onDrop = {},
                    enableCardDrag = false,
                )
            }
            item {
                Surface(
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f),
                    shape = RoundedCornerShape(24.dp),
                    modifier = Modifier.width(280.dp).heightIn(min = 170.dp),
                ) {
                    Text("+ New group", modifier = Modifier.padding(22.dp), fontWeight = FontWeight.Black, fontSize = 20.sp)
                }
            }
        }

        dragState.currentTarget()?.let { target ->
            dragState.dropIndicatorRect(target)?.let { rect ->
                Surface(
                    color = MaterialTheme.colorScheme.primary,
                    shape = RoundedCornerShape(50.dp),
                    modifier = Modifier
                        .offset { IntOffset(rect.left.roundToInt(), rect.top.roundToInt()) }
                        .width(with(density) { (rect.right - rect.left).toDp() })
                        .height(4.dp)
                        .zIndex(18f),
                ) {}
            }
        }

        dragState.draggingTask?.let { task ->
            val position = dragState.pointer - dragState.grabOffset
            TaskKanbanCard(
                task = task,
                checklistItems = checklistItems,
                compact = compact,
                dragging = true,
                modifier = Modifier
                    .width(284.dp)
                    .offset { IntOffset(position.x.roundToInt(), position.y.roundToInt()) }
                    .zIndex(20f),
                onClick = {},
                onDragStart = { _, _ -> },
                onDragMove = {},
                onDrop = {},
            )
        }
    }
}

@Composable
private fun TaskKanbanColumn(
    column: TaskColumnItem,
    tasks: List<TaskItem>,
    checklistItems: List<TaskChecklistItem>,
    compact: Boolean,
    drag: BoardDragState,
    onTaskClick: (TaskItem) -> Unit,
    onAddTask: (String) -> Unit,
    onMoveColumn: (Int) -> Unit,
    onRenameColumn: (String) -> Unit,
    onDeleteColumn: () -> Unit,
    onDragMove: (Offset) -> Unit,
    onDrop: () -> Unit,
    enableCardDrag: Boolean,
) {
    var newTitle by remember(column.id) { mutableStateOf("") }
    var menuOpen by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var renameValue by remember(column.id, column.name) { mutableStateOf(column.name) }
    val target = drag.currentTarget()
    Surface(
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.74f),
        shape = RoundedCornerShape(24.dp),
        tonalElevation = 2.dp,
        modifier = Modifier
            .width(292.dp)
            .fillMaxHeight()
            .onGloballyPositioned { drag.columnBounds[column.id] = TaskColumnBounds(column.id, drag.toBoardRect(it.boundsInRoot())) },
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (renaming) {
                    OutlinedTextField(
                        value = renameValue,
                        onValueChange = { renameValue = it },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                        colors = taskTextFieldColors(),
                    )
                    TextButton(onClick = {
                        onRenameColumn(renameValue)
                        renaming = false
                    }) { Text("Save") }
                } else {
                    Text(column.name, modifier = Modifier.weight(1f), fontWeight = FontWeight.Black, fontSize = 18.sp)
                    StatusCount(tasks.size)
                    Box {
                        IconButton(onClick = { menuOpen = true }) { Icon(Icons.Outlined.MoreVert, null) }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(text = { Text("Rename column") }, onClick = { menuOpen = false; renaming = true })
                            DropdownMenuItem(text = { Text("Move left") }, onClick = { menuOpen = false; onMoveColumn(-1) })
                            DropdownMenuItem(text = { Text("Move right") }, onClick = { menuOpen = false; onMoveColumn(1) })
                            DropdownMenuItem(
                                text = { Text("Delete empty column") },
                                enabled = tasks.isEmpty(),
                                onClick = { menuOpen = false; onDeleteColumn() },
                            )
                        }
                    }
                }
            }
            tasks.forEachIndexed { index, task ->
                if (target?.columnId == column.id && target.index == index) DropIndicator()
                if (drag.draggingTask?.id == task.id) {
                    Surface(
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.10f),
                        shape = RoundedCornerShape(18.dp),
                        modifier = Modifier.fillMaxWidth().height(if (compact) 86.dp else 116.dp),
                    ) {}
                } else {
                    TaskKanbanCard(
                        task = task,
                        checklistItems = checklistItems,
                        compact = compact,
                        onClick = { onTaskClick(task) },
                        modifier = Modifier.fillMaxWidth(),
                        onBoundsChanged = { bounds ->
                            drag.cardBounds[task.id] = TaskCardBounds(task.id, column.id, drag.toBoardRect(bounds))
                        },
                        onDragStart = { pointer, grabOffset -> drag.start(task, drag.toBoardOffset(pointer), grabOffset) },
                        onDragMove = onDragMove,
                        onDrop = onDrop,
                        enableCardDrag = enableCardDrag,
                    )
                }
            }
            if (target?.columnId == column.id && target.index >= tasks.size) DropIndicator()
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = newTitle,
                    onValueChange = { newTitle = it },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    placeholder = { Text("New task") },
                    colors = taskTextFieldColors(),
                    shape = RoundedCornerShape(16.dp),
                )
                IconButton(
                    onClick = {
                        val title = newTitle.trim()
                        if (title.isNotEmpty()) {
                            onAddTask(title)
                            newTitle = ""
                        }
                    },
                ) { Icon(Icons.Outlined.Add, null) }
            }
        }
    }
}

@Composable
private fun TaskKanbanCard(
    task: TaskItem,
    checklistItems: List<TaskChecklistItem>,
    compact: Boolean,
    dragging: Boolean = false,
    modifier: Modifier = Modifier,
    onBoundsChanged: (Rect) -> Unit = {},
    onClick: () -> Unit,
    onDragStart: (Offset, Offset) -> Unit,
    onDragMove: (Offset) -> Unit,
    onDrop: () -> Unit,
    enableCardDrag: Boolean = false,
) {
    var bounds by remember { mutableStateOf(Rect.Zero) }
    ElevatedCard(
        modifier = modifier
            .onGloballyPositioned {
                bounds = it.boundsInRoot()
                if (!dragging) onBoundsChanged(bounds)
            }
            .then(
                if (!dragging && enableCardDrag) {
                    Modifier.pointerInput(task.id) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = { offset -> onDragStart(bounds.topLeft + offset, offset) },
                            onDrag = { change, dragAmount ->
                                change.consume()
                                onDragMove(dragAmount)
                            },
                            onDragCancel = onDrop,
                            onDragEnd = onDrop,
                        )
                    }
                } else Modifier
            )
            .clickable(enabled = !dragging, onClick = onClick)
            .animateContentSize(),
        colors = CardDefaults.elevatedCardColors(
            containerColor = if (dragging) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
        ),
        shape = RoundedCornerShape(18.dp),
    ) {
        Column(
            modifier = Modifier.padding(if (compact) 12.dp else 14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(task.title.ifBlank { "Untitled task" }, modifier = Modifier.weight(1f), fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Icon(Icons.Outlined.DragIndicator, null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (task.description.isNotBlank()) {
                Text(task.description, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = if (compact) 2 else 4, overflow = TextOverflow.Ellipsis)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                TinyMeta(task.status.label())
                PriorityPill(task.priority)
            }
            task.progressFromChecklist(checklistItems)?.let { progress ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.weight(1f))
                    Text("$progress%", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                }
            }
            checklistItems.filter { it.taskId == task.id }.sortedBy { it.sortOrder }.take(if (compact) 1 else 2).forEach { item ->
                Text("${if (item.checked) "x" else " "} ${item.text}", color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 12.sp)
            }
            if (task.labels.isNotBlank()) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                    task.labels.split(',').map(String::trim).filter(String::isNotBlank).forEach { TinyMeta(it) }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (task.startAt != null || task.dueAt != null) TinyMeta(formatTaskTimeRange(task.startAt, task.dueAt, task.allDay))
                if (task.assignee.isNotBlank()) TinyMeta(task.assignee)
            }
            if (task.attachmentName != null) TinyMeta("File · ${task.attachmentName}")
            Text("Updated ${shortTime(task.updatedAt)}", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
        }
    }
}

@Composable
private fun TaskDatabaseTable(
    tasks: List<TaskItem>,
    columns: List<TaskColumnItem>,
    properties: List<TaskPropertyDefinition>,
    propertyValues: List<TaskPropertyValue>,
    checklistItems: List<TaskChecklistItem>,
    viewModel: NotesViewModel,
    onPickAttachment: (TaskItem) -> Unit,
    onTaskClick: (TaskItem) -> Unit,
) {
    var editor by remember { mutableStateOf<Pair<TaskItem, TaskPropertyDefinition>?>(null) }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        if (maxWidth < 720.dp) {
            CompactGroupedTaskTable(
                tasks = tasks,
                columns = columns,
                properties = properties,
                propertyValues = propertyValues,
                checklistItems = checklistItems,
                viewModel = viewModel,
                onPropertyClick = { task, property -> editor = task to property },
                onTaskClick = onTaskClick,
            )
        } else {
            val scroll = rememberScrollState()
            val border = MaterialTheme.colorScheme.outlineVariant
            var selectedCell by remember { mutableStateOf<Pair<Long, Int>?>(null) }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 260.dp)
                    .horizontalScroll(scroll)
                    .border(1.6.dp, border, RoundedCornerShape(12.dp))
                    .clip(RoundedCornerShape(12.dp)),
            ) {
                TaskTableRow(
                    cells = properties.map { it.headerLabel() } + "+ property",
                    header = true,
                    onClick = { viewModel.createTaskProperty(columns.firstOrNull()?.boardId ?: 1L, "Text", TaskPropertyType.Text) },
                )
                tasks.forEach { task ->
                    TaskTableRow(
                        rowId = task.id,
                        cells = properties.map { property -> task.propertyCell(property, columns, propertyValues, checklistItems) } + "Open",
                        progressColumn = properties.indexOfFirst { it.type == TaskPropertyType.Checklist },
                        progress = task.propertyProgress(properties, checklistItems),
                        selectedCell = selectedCell,
                        onCellClick = { index ->
                            selectedCell = task.id to index
                            properties.getOrNull(index)?.let { editor = task to it } ?: onTaskClick(task)
                        },
                        onClick = {},
                    )
                }
                if (tasks.isEmpty()) TaskTableRow(cells = listOf("No tasks", "Create one from New", "", ""), onClick = {})
            }
        }
    }
    editor?.let { (task, property) ->
        FocusedTaskPropertyDialog(
            task = task,
            property = property,
            value = propertyValues.firstOrNull { it.taskId == task.id && it.propertyId == property.id },
            checklistItems = checklistItems.filter { it.taskId == task.id && it.propertyId == property.id }.sortedBy { it.sortOrder },
            columns = columns,
            viewModel = viewModel,
            onPickAttachment = { onPickAttachment(task) },
            onDismiss = { editor = null },
        )
    }
}

@Composable
private fun CompactGroupedTaskTable(
    tasks: List<TaskItem>,
    columns: List<TaskColumnItem>,
    properties: List<TaskPropertyDefinition>,
    propertyValues: List<TaskPropertyValue>,
    checklistItems: List<TaskChecklistItem>,
    viewModel: NotesViewModel,
    onPropertyClick: (TaskItem, TaskPropertyDefinition) -> Unit,
    onTaskClick: (TaskItem) -> Unit,
) {
    val collapsed = remember { mutableStateMapOf<Long, Boolean>() }
    var expandedTaskId by remember { mutableStateOf<Long?>(null) }
    val drafts = remember { mutableStateMapOf<Long, String>() }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        columns.forEach { column ->
            val columnTasks = tasks.filter { it.taskColumnId == column.id }.sortedBy { it.sortOrder }
            Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surface, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Box(Modifier.size(14.dp).clip(CircleShape).background(Color(column.color)))
                        Text(column.name, fontWeight = FontWeight.Black, fontSize = 18.sp, modifier = Modifier.weight(1f))
                        StatusCount(columnTasks.size)
                        IconButton(onClick = { collapsed[column.id] = !(collapsed[column.id] ?: false) }) {
                            Icon(if (collapsed[column.id] == true) Icons.Outlined.Add else Icons.Outlined.TableRows, null)
                        }
                    }
                    if (collapsed[column.id] != true) {
                        columnTasks.forEachIndexed { index, task ->
                            Surface(
                                shape = RoundedCornerShape(14.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.24f),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                                modifier = Modifier.fillMaxWidth().animateContentSize(),
                            ) {
                                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        VerticalReorderHandle(enabled = columnTasks.size > 1, rowHeight = 76.dp) { delta ->
                                            viewModel.moveTaskToColumnAtIndex(task.id, column.id, (index + delta).coerceIn(0, columnTasks.lastIndex))
                                        }
                                        Column(Modifier.weight(1f).clickable { expandedTaskId = if (expandedTaskId == task.id) null else task.id }) {
                                            Text(task.title.ifBlank { "Untitled task" }, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                                TinyMeta(column.name)
                                                if (task.assignee.isNotBlank()) TinyMeta(task.assignee)
                                                if (task.dueAt != null) TinyMeta(formatTaskDue(task.dueAt))
                                            }
                                        }
                                        PriorityPill(task.priority)
                                        IconButton(onClick = { onTaskClick(task) }) { Icon(Icons.Outlined.MoreVert, null) }
                                    }
                                    if (expandedTaskId == task.id) {
                                        val populated = properties.filter { property ->
                                            val value = propertyValues.firstOrNull { it.taskId == task.id && it.propertyId == property.id }?.valueJson.orEmpty()
                                            property.type in setOf(TaskPropertyType.Name, TaskPropertyType.Status, TaskPropertyType.Priority) ||
                                                value.isNotBlank() || (property.type == TaskPropertyType.Checklist && checklistItems.any { it.taskId == task.id && it.propertyId == property.id })
                                        }
                                        populated.filterNot { it.type == TaskPropertyType.Name }.forEach { property ->
                                            Surface(
                                                color = MaterialTheme.colorScheme.surface,
                                                shape = RoundedCornerShape(10.dp),
                                                modifier = Modifier.fillMaxWidth().clickable { onPropertyClick(task, property) },
                                            ) {
                                                Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                                    Icon(property.type.icon(), null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                                                    Column(Modifier.weight(1f)) {
                                                        Text(property.name, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                                        Text(task.propertyCell(property, columns, propertyValues, checklistItems), maxLines = 4, overflow = TextOverflow.Ellipsis)
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(drafts[column.id].orEmpty(), { drafts[column.id] = it }, modifier = Modifier.weight(1f), singleLine = true, placeholder = { Text("Add task") }, colors = taskTextFieldColors())
                            IconButton(onClick = {
                                val title = drafts[column.id].orEmpty().trim()
                                if (title.isNotEmpty()) { viewModel.addTaskToColumn(title, column); drafts[column.id] = "" }
                            }) { Icon(Icons.Outlined.Add, null, tint = MaterialTheme.colorScheme.primary) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FocusedTaskPropertyDialog(
    task: TaskItem,
    property: TaskPropertyDefinition,
    value: TaskPropertyValue?,
    checklistItems: List<TaskChecklistItem>,
    columns: List<TaskColumnItem>,
    viewModel: NotesViewModel,
    onPickAttachment: () -> Unit,
    onDismiss: () -> Unit,
) {
    var newStatus by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(property.type.icon(), null, tint = MaterialTheme.colorScheme.primary)
                Text(property.name, fontWeight = FontWeight.Black)
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                when (property.type) {
                    TaskPropertyType.Status -> {
                        columns.forEach { column ->
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = if (column.id == task.taskColumnId) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.36f),
                                modifier = Modifier.fillMaxWidth().clickable { viewModel.moveTaskToColumn(task, column); onDismiss() },
                            ) {
                                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Box(Modifier.size(10.dp).clip(CircleShape).background(Color(column.color)))
                                    Text(column.name, modifier = Modifier.weight(1f), fontWeight = FontWeight.Bold)
                                    if (column.id == task.taskColumnId) Icon(Icons.Outlined.CheckCircle, null, tint = MaterialTheme.colorScheme.primary)
                                }
                            }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(newStatus, { newStatus = it }, modifier = Modifier.weight(1f), singleLine = true, placeholder = { Text("Create new status") }, colors = taskTextFieldColors())
                            IconButton(onClick = { if (newStatus.isNotBlank()) { viewModel.createTaskColumn(task.taskBoardId, newStatus.trim()); newStatus = "" } }) { Icon(Icons.Outlined.Add, null) }
                        }
                    }
                    TaskPropertyType.Checklist -> ChecklistPropertyEditor(task, property, checklistItems, viewModel)
                    TaskPropertyType.Checkbox -> CheckboxPropertyEditor(task, property, value?.valueJson.orEmpty(), viewModel)
                    TaskPropertyType.FilesMedia -> FilesPropertyEditor(task, property, value?.valueJson.orEmpty(), viewModel, onPickAttachment)
                    TaskPropertyType.CreatedAt -> ReadOnlyPropertyValue(shortTime(task.createdAt))
                    TaskPropertyType.LastModified -> ReadOnlyPropertyValue(shortTime(task.updatedAt))
                    TaskPropertyType.DueDate, TaskPropertyType.Date -> DatePropertyEditor(task, property, value?.valueJson.orEmpty(), viewModel)
                    TaskPropertyType.Priority -> ChoicePropertyEditor(task, property, task.priority.name, TaskPriority.entries.map { it.name }, viewModel)
                    else -> TextPropertyEditor(task, property, property.displayValue(task, value?.valueJson.orEmpty()), viewModel)
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}

@Composable
private fun TaskTableRow(
    cells: List<String>,
    rowId: Long = -1L,
    header: Boolean = false,
    progressColumn: Int = -1,
    progress: Int? = null,
    selectedCell: Pair<Long, Int>? = null,
    onCellClick: (Int) -> Unit = {},
    onClick: () -> Unit,
) {
    val border = MaterialTheme.colorScheme.outlineVariant
    // IntrinsicSize.Min makes the row exactly as tall as its tallest cell, and fillMaxHeight
    // stretches every cell to that height — so a row with a 10-line cell is 10 lines everywhere.
    Row(
        modifier = Modifier
            .height(IntrinsicSize.Min)
            .clickable(onClick = onClick),
    ) {
        cells.forEachIndexed { index, cell ->
            val selected = selectedCell == (rowId to index)
            Column(
                modifier = Modifier
                    .width(
                        when (index) {
                            0 -> 220.dp
                            progressColumn -> 190.dp
                            else -> 180.dp
                        },
                    )
                    .fillMaxHeight()
                    .heightIn(min = if (header) 48.dp else 72.dp)
                    .background(
                        when {
                            selected -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.28f)
                            header -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.52f)
                            else -> MaterialTheme.colorScheme.surface.copy(alpha = 0.20f)
                        },
                    )
                    .border(if (selected) 2.4.dp else 1.35.dp, if (selected) MaterialTheme.colorScheme.primary else border)
                    .clickable {
                        onCellClick(index)
                        if (!header) onClick()
                    }
                    .padding(horizontal = 12.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    cell,
                    fontWeight = if (header || index == 0) FontWeight.Bold else FontWeight.Medium,
                    color = if (header) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (index == progressColumn && progress != null) {
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth().height(4.dp))
                }
            }
        }
    }
}

@Composable
private fun TaskMatrixView(columns: List<TaskColumnItem>, tasks: List<TaskItem>, onTaskClick: (TaskItem) -> Unit) {
    // Eisenhower matrix: Importance (priority) on the Y axis, Urgency (due date) on the X axis.
    val now = remember { System.currentTimeMillis() }
    val soonWindow = 3L * 86_400_000L // due within 3 days (or overdue) counts as urgent
    fun important(t: TaskItem) = t.priority == TaskPriority.High || t.priority == TaskPriority.Urgent
    fun urgent(t: TaskItem) = t.dueAt?.let { it <= now + soonWindow } ?: false
    val doFirst = tasks.filter { important(it) && urgent(it) }
    val schedule = tasks.filter { important(it) && !urgent(it) }
    val delegate = tasks.filter { !important(it) && urgent(it) }
    val later = tasks.filter { !important(it) && !urgent(it) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Prioritize by importance and urgency", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            MatrixQuadrant("Do first", "Important · Urgent", doFirst, MaterialTheme.colorScheme.errorContainer, Modifier.weight(1f), onTaskClick)
            MatrixQuadrant("Schedule", "Important · Later", schedule, MaterialTheme.colorScheme.primaryContainer, Modifier.weight(1f), onTaskClick)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            MatrixQuadrant("Delegate", "Urgent · Lower priority", delegate, MaterialTheme.colorScheme.tertiaryContainer, Modifier.weight(1f), onTaskClick)
            MatrixQuadrant("Later", "Not urgent · Lower priority", later, MaterialTheme.colorScheme.surfaceVariant, Modifier.weight(1f), onTaskClick)
        }
    }
}

@Composable
private fun MatrixQuadrant(
    title: String,
    subtitle: String,
    tasks: List<TaskItem>,
    container: Color,
    modifier: Modifier = Modifier,
    onTaskClick: (TaskItem) -> Unit,
) {
    Surface(color = container.copy(alpha = 0.5f), shape = RoundedCornerShape(18.dp), modifier = modifier.height(268.dp).animateContentSize()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(title, fontWeight = FontWeight.Black)
                    Text(subtitle, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Text(tasks.size.toString(), fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Column(
                modifier = Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (tasks.isEmpty()) {
                    Text("Nothing here", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                }
                tasks.forEach { task ->
                    Surface(
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth().clickable { onTaskClick(task) },
                    ) {
                        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Box(Modifier.size(8.dp).clip(CircleShape).background(task.resolveAccent(MaterialTheme.colorScheme.primary)))
                            Text(task.title, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f), fontSize = 13.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TaskListView(
    tasks: List<TaskItem>,
    columns: List<TaskColumnItem>,
    properties: List<TaskPropertyDefinition>,
    propertyValues: List<TaskPropertyValue>,
    checklistItems: List<TaskChecklistItem>,
    onTaskClick: (TaskItem) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        tasks.forEach { task ->
            Surface(
                color = MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier.fillMaxWidth().clickable { onTaskClick(task) },
            ) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Icon(if (task.status == TaskStatus.Done) Icons.Outlined.CheckCircle else Icons.Outlined.RadioButtonUnchecked, null)
                        Column(Modifier.weight(1f)) {
                            Text(task.title, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(columns.firstOrNull { it.id == task.taskColumnId }?.name ?: task.status.label(), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        PriorityPill(task.priority)
                        Icon(Icons.Outlined.Edit, null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    val progress = task.propertyProgress(properties, checklistItems)
                    if (progress != null) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.weight(1f))
                            Text("$progress%", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    task.checklistPreview(properties, checklistItems).take(2).forEach {
                        Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (task.dueAt != null || task.attachmentName != null) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            if (task.dueAt != null) TinyMeta(formatTaskDue(task.dueAt))
                            if (task.attachmentName != null) TinyMeta(task.attachmentName)
                            properties.firstOrNull { it.type == TaskPropertyType.Text }?.let { property ->
                                propertyValues.firstOrNull { it.taskId == task.id && it.propertyId == property.id }?.valueJson?.takeIf { it.isNotBlank() }?.let { TinyMeta(it.take(32)) }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TaskFeedView(
    tasks: List<TaskItem>,
    properties: List<TaskPropertyDefinition>,
    propertyValues: List<TaskPropertyValue>,
    checklistItems: List<TaskChecklistItem>,
    onTaskClick: (TaskItem) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        tasks.sortedByDescending { it.updatedAt }.forEach { task ->
            Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth().clickable { onTaskClick(task) }) {
                Column(Modifier.padding(14.dp)) {
                    Text(task.title, fontWeight = FontWeight.Bold)
                    val progress = task.propertyProgress(properties, checklistItems)
                    Text("Updated ${shortTime(task.updatedAt)}${progress?.let { " • $it%" }.orEmpty()}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    properties.firstOrNull { it.type == TaskPropertyType.Text }?.let { property ->
                        propertyValues.firstOrNull { it.taskId == task.id && it.propertyId == property.id }?.valueJson?.takeIf { it.isNotBlank() }?.let {
                            Text(it, maxLines = 2, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TaskCalendarView(tasks: List<TaskItem>, checklistItems: List<TaskChecklistItem>, onTaskClick: (TaskItem) -> Unit) {
    val now = remember { System.currentTimeMillis() }
    var monthView by remember { mutableStateOf(false) }
    // Sunday-based week containing today.
    val week = remember(now) {
        val start = Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            add(Calendar.DAY_OF_YEAR, -(get(Calendar.DAY_OF_WEEK) - Calendar.SUNDAY))
        }.timeInMillis
        (0..6).map { i -> start + i * 86_400_000L }
    }
    val todayIndex = week.indexOfFirst { sameDay(it, now) }.coerceIn(0, 6)
    var selected by remember { mutableStateOf(todayIndex) }
    val monthLabel = remember(now) { SimpleDateFormat("MMMM yyyy", Locale.getDefault()).format(Date(now)) }
    val dayNames = listOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")

    Surface(
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = RoundedCornerShape(20.dp),
        tonalElevation = 3.dp,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp).animateContentSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Event, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text(monthLabel, fontWeight = FontWeight.Black, fontSize = 18.sp, modifier = Modifier.weight(1f))
                Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)) {
                    Row {
                        CalendarToggleChip("Week", !monthView) { monthView = false }
                        CalendarToggleChip("Month", monthView) { monthView = true }
                    }
                }
            }
            if (monthView) {
                CalendarMonthGrid(now = now, tasks = tasks, onTaskClick = onTaskClick)
            } else {
                CalendarWeekView(
                    week = week,
                    dayNames = dayNames,
                    selected = selected,
                    todayIndex = todayIndex,
                    tasks = tasks,
                    onSelect = { selected = it },
                    onTaskClick = onTaskClick,
                )
            }
        }
    }
}

@Composable
private fun CalendarToggleChip(label: String, active: Boolean, onClick: () -> Unit) {
    Surface(
        color = if (active) MaterialTheme.colorScheme.primary else Color.Transparent,
        contentColor = if (active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
        shape = RoundedCornerShape(50),
        modifier = Modifier.clickable(onClick = onClick),
    ) {
        Text(label, modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp), fontWeight = FontWeight.Bold, fontSize = 13.sp)
    }
}

@Composable
private fun CalendarWeekView(
    week: List<Long>,
    dayNames: List<String>,
    selected: Int,
    todayIndex: Int,
    tasks: List<TaskItem>,
    onSelect: (Int) -> Unit,
    onTaskClick: (TaskItem) -> Unit,
) {
    Row(Modifier.fillMaxWidth()) {
        week.forEachIndexed { i, dayMs ->
            val isToday = i == todayIndex
            val isSel = i == selected
            val dayCount = tasks.count { it.dueAt?.let { d -> sameDay(d, dayMs) } == true }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (isSel) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f) else Color.Transparent)
                    .clickable { onSelect(i) }
                    .padding(vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(dayNames[i], fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(4.dp))
                val dateNum = calField(dayMs, Calendar.DAY_OF_MONTH).toString()
                if (isToday) {
                    Surface(color = MaterialTheme.colorScheme.primary, shape = CircleShape, modifier = Modifier.size(30.dp)) {
                        Box(contentAlignment = Alignment.Center) { Text(dateNum, color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.Black) }
                    }
                } else {
                    Box(Modifier.size(30.dp), contentAlignment = Alignment.Center) {
                        Text(dateNum, fontWeight = if (isSel) FontWeight.Bold else FontWeight.Medium)
                    }
                }
                Spacer(Modifier.height(4.dp))
                if (dayCount > 0) {
                    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        repeat(dayCount.coerceAtMost(3)) {
                            Box(Modifier.size(4.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary))
                        }
                    }
                }
            }
        }
    }
    val weekStart = startOfDay(week.first())
    val weekEnd = startOfDay(week.last()) + 86_399_999L
    val visibleTasks = tasks.filter { task ->
        val start = task.startAt ?: task.dueAt ?: return@filter false
        val end = task.dueAt ?: start
        end >= weekStart && start <= weekEnd
    }.sortedBy { it.startAt ?: it.dueAt }
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .height(360.dp)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(14.dp))
            .clip(RoundedCornerShape(14.dp)),
    ) {
        val dayWidth = maxWidth / 7
        Row(Modifier.fillMaxSize()) {
            repeat(7) { index ->
                Box(
                    Modifier
                        .width(dayWidth)
                        .fillMaxHeight()
                        .background(if (index == selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.08f) else Color.Transparent)
                        .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant),
                )
            }
        }
        visibleTasks.forEachIndexed { eventIndex, task ->
            val rawStart = startOfDay(task.startAt ?: task.dueAt ?: weekStart)
            val rawEnd = startOfDay(task.dueAt ?: task.startAt ?: rawStart)
            val startIndex = ((rawStart - weekStart) / 86_400_000L).toInt().coerceIn(0, 6)
            val endIndex = ((rawEnd - weekStart) / 86_400_000L).toInt().coerceIn(startIndex, 6)
            val startTime = task.startAt ?: task.dueAt ?: weekStart
            val minutes = calField(startTime, Calendar.HOUR_OF_DAY) * 60 + calField(startTime, Calendar.MINUTE)
            val y = if (task.allDay) 12.dp + 62.dp * (eventIndex % 4) else 36.dp + ((minutes / 1440f) * 270f).dp
            val accent = task.resolveAccent(MaterialTheme.colorScheme.primary)
            Surface(
                color = accent.copy(alpha = 0.20f),
                shape = RoundedCornerShape(10.dp),
                border = BorderStroke(1.dp, accent.copy(alpha = 0.55f)),
                modifier = Modifier
                    .offset(x = dayWidth * startIndex + 4.dp, y = y)
                    .width(dayWidth * (endIndex - startIndex + 1) - 8.dp)
                    .heightIn(min = 52.dp)
                    .clickable { onTaskClick(task) },
            ) {
                Row(Modifier.height(IntrinsicSize.Min)) {
                    Box(Modifier.width(5.dp).fillMaxHeight().background(accent))
                    Column(Modifier.padding(horizontal = 10.dp, vertical = 7.dp)) {
                        Text(task.title, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(formatTaskTimeRange(task.startAt, task.dueAt, task.allDay), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
    Text("Tasks for ${dayNames[selected]}", fontWeight = FontWeight.Black, fontSize = 16.sp)
    val selectedStart = startOfDay(week[selected])
    val selectedEnd = selectedStart + 86_399_999L
    val dayTasks = tasks.filter { task ->
        val start = task.startAt ?: task.dueAt ?: return@filter false
        val end = task.dueAt ?: start
        end >= selectedStart && start <= selectedEnd
    }.sortedBy { it.startAt ?: it.dueAt }
    if (dayTasks.isEmpty()) {
        Surface(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f), shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
            Text("No tasks on ${dayNames[selected]}", Modifier.padding(18.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Bold)
        }
    } else {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            dayTasks.forEach { task -> CalendarTimeBar(task = task) { onTaskClick(task) } }
        }
    }
}

@Composable
private fun CalendarTimeBar(task: TaskItem, onClick: () -> Unit) {
    // Encode time-of-day as a horizontal indent (later in the day → indented right, like the reference).
    val minutes = task.dueAt?.let { calField(it, Calendar.HOUR_OF_DAY) * 60 + calField(it, Calendar.MINUTE) } ?: 0
    val indent = (minutes / 1440f) * 120f
    val accent = task.resolveAccent(MaterialTheme.colorScheme.primary)
    Row(Modifier.fillMaxWidth()) {
        if (indent > 0f) Spacer(Modifier.width(indent.dp))
        Surface(
            color = accent.copy(alpha = 0.18f),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.weight(1f).clickable(onClick = onClick),
        ) {
            Row(Modifier.height(IntrinsicSize.Min)) {
                Box(Modifier.width(5.dp).fillMaxHeight().background(accent))
                Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                    Text(task.title, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(formatTaskTimeRange(task.startAt, task.dueAt, task.allDay), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun CalendarMonthGrid(now: Long, tasks: List<TaskItem>, onTaskClick: (TaskItem) -> Unit) {
    val monthInfo = remember(now) {
        val c = Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.DAY_OF_MONTH, 1)
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        val firstMs = c.timeInMillis
        val lead = c.get(Calendar.DAY_OF_WEEK) - Calendar.SUNDAY
        val daysInMonth = c.getActualMaximum(Calendar.DAY_OF_MONTH)
        Triple(firstMs, lead, daysInMonth)
    }
    val (firstMs, lead, daysInMonth) = monthInfo
    val cells: List<Long?> = List(lead) { null } + (0 until daysInMonth).map { firstMs + it * 86_400_000L }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth()) {
            listOf("S", "M", "T", "W", "T", "F", "S").forEach {
                Text(it, Modifier.weight(1f), textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
            }
        }
        cells.chunked(7).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                row.forEach { dayMs ->
                    Box(Modifier.weight(1f).height(54.dp)) {
                        if (dayMs != null) {
                            val isToday = sameDay(dayMs, now)
                            val dayTasks = tasks.filter { it.dueAt?.let { d -> sameDay(d, dayMs) } == true }
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(if (isToday) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
                                    .clickable(enabled = dayTasks.isNotEmpty()) { dayTasks.firstOrNull()?.let(onTaskClick) }
                                    .padding(6.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                Text(calField(dayMs, Calendar.DAY_OF_MONTH).toString(), fontSize = 12.sp, fontWeight = if (isToday) FontWeight.Black else FontWeight.Medium)
                                Spacer(Modifier.height(3.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                                    dayTasks.take(3).forEach { t ->
                                        Box(Modifier.size(5.dp).clip(CircleShape).background(t.resolveAccent(MaterialTheme.colorScheme.primary)))
                                    }
                                }
                            }
                        }
                    }
                }
                repeat(7 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

private fun sameDay(a: Long, b: Long): Boolean {
    val ca = Calendar.getInstance().apply { timeInMillis = a }
    val cb = Calendar.getInstance().apply { timeInMillis = b }
    return ca.get(Calendar.YEAR) == cb.get(Calendar.YEAR) && ca.get(Calendar.DAY_OF_YEAR) == cb.get(Calendar.DAY_OF_YEAR)
}

private fun calField(ms: Long, field: Int): Int =
    Calendar.getInstance().apply { timeInMillis = ms }.get(field)

private fun startOfDay(ms: Long): Long = Calendar.getInstance().apply {
    timeInMillis = ms
    set(Calendar.HOUR_OF_DAY, 0)
    set(Calendar.MINUTE, 0)
    set(Calendar.SECOND, 0)
    set(Calendar.MILLISECOND, 0)
}.timeInMillis

private fun formatTaskTimeRange(startAt: Long?, dueAt: Long?, allDay: Boolean): String {
    if (startAt == null && dueAt == null) return "No date"
    if (allDay) {
        val start = startAt ?: dueAt!!
        val end = dueAt ?: start
        return if (sameDay(start, end)) formatCalendarDate(end) else "${formatCalendarDate(start)} → ${formatCalendarDate(end)}"
    }
    val fmt = SimpleDateFormat("h:mm a", Locale.getDefault())
    val start = startAt ?: dueAt!!
    val end = dueAt ?: start
    return "${fmt.format(Date(start))} – ${fmt.format(Date(end))}"
}

@Composable
private fun TaskChartView(tasks: List<TaskItem>) {
    val groups = TaskStatus.entries.associateWith { status -> tasks.count { it.status == status } }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Chart", fontWeight = FontWeight.Black, fontSize = 22.sp)
        groups.forEach { (status, count) ->
            Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp)) {
                    Row {
                        Text(status.label(), modifier = Modifier.weight(1f), fontWeight = FontWeight.Bold)
                        Text(count.toString())
                    }
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(progress = { if (tasks.isEmpty()) 0f else count / tasks.size.toFloat() }, modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }
}

@Composable
private fun TaskGalleryView(tasks: List<TaskItem>, checklistItems: List<TaskChecklistItem>, onTaskClick: (TaskItem) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        tasks.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                row.forEach { task ->
                    Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(18.dp), modifier = Modifier.weight(1f).height(142.dp).clickable { onTaskClick(task) }) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.SpaceBetween) {
                            Text(task.title, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            task.progressFromChecklist(checklistItems)?.let { progress ->
                                LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth())
                            }
                            PriorityPill(task.priority)
                        }
                    }
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun TaskRailPanel(
    action: TaskRailAction?,
    modifier: Modifier,
    query: String,
    onQueryChange: (String) -> Unit,
    assignees: List<String>,
    assigneeFilter: String,
    onAssigneeFilter: (String) -> Unit,
    labels: List<String>,
    labelFilter: String,
    onLabelFilter: (String) -> Unit,
    priorityFilter: TaskPriority?,
    onPriorityFilter: (TaskPriority?) -> Unit,
    sort: TaskWorkspaceSort,
    onSortChange: (TaskWorkspaceSort) -> Unit,
    compact: Boolean,
    onCompactChange: (Boolean) -> Unit,
    boards: List<TaskBoardItem>,
    selectedBoardId: Long,
    onSelectBoard: (Long) -> Unit,
    boardName: String,
    onBoardNameChange: (String) -> Unit,
    onRenameBoard: (String) -> Unit,
    newBoardName: String,
    onNewBoardNameChange: (String) -> Unit,
    onCreateBoard: () -> Unit,
    newColumnName: String,
    onNewColumnNameChange: (String) -> Unit,
    onCreateColumn: () -> Unit,
    onDismiss: () -> Unit,
) {
    AnimatedVisibility(visible = action != null, modifier = modifier) {
        Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(22.dp), tonalElevation = 6.dp) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(action?.name.orEmpty(), modifier = Modifier.weight(1f), fontWeight = FontWeight.Black)
                    TextButton(onClick = onDismiss) { Text("Close") }
                }
                when (action) {
                    TaskRailAction.Search -> OutlinedTextField(query, onQueryChange, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("Search") }, colors = taskTextFieldColors())
                    TaskRailAction.Filter -> {
                        FilterChips("Assignee", assignees, assigneeFilter, onAssigneeFilter)
                        FilterChips("Label", labels, labelFilter, onLabelFilter)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            AssistChip(onClick = { onPriorityFilter(null) }, label = { Text("All") })
                            TaskPriority.entries.forEach { priority ->
                                AssistChip(onClick = { onPriorityFilter(priority) }, label = { Text(priority.label()) })
                            }
                        }
                        Text("Active: ${assigneeFilter.ifBlank { "anyone" }} / ${labelFilter.ifBlank { "any label" }} / ${priorityFilter?.label() ?: "any priority"}")
                    }
                    TaskRailAction.Sort -> TaskWorkspaceSort.entries.forEach { mode ->
                        AssistChip(onClick = { onSortChange(mode) }, label = { Text(if (mode == sort) "${mode.label} ✓" else mode.label) })
                    }
                    TaskRailAction.New -> {
                        OutlinedTextField(newBoardName, onNewBoardNameChange, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("New board") }, colors = taskTextFieldColors())
                        Button(onClick = onCreateBoard, enabled = newBoardName.isNotBlank()) { Text("Create board") }
                        HorizontalDivider()
                        OutlinedTextField(newColumnName, onNewColumnNameChange, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("New column") }, colors = taskTextFieldColors())
                        Button(onClick = onCreateColumn, enabled = newColumnName.isNotBlank()) { Text("Create column") }
                    }
                    TaskRailAction.Settings -> {
                        var showRename by remember { mutableStateOf(false) }
                        TaskSettingsCard("Board") {
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                items(boards, key = { it.id }) { board ->
                                    AssistChip(onClick = { onSelectBoard(board.id) }, label = { Text(if (board.id == selectedBoardId) "${board.name} ✓" else board.name) })
                                }
                            }
                            Surface(
                                color = MaterialTheme.colorScheme.surface,
                                shape = RoundedCornerShape(12.dp),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text(boardName.ifBlank { "Untitled board" }, modifier = Modifier.weight(1f), fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    IconButton(onClick = { showRename = true }) { Icon(Icons.Outlined.Edit, null, tint = MaterialTheme.colorScheme.primary) }
                                }
                            }
                        }
                        if (showRename) {
                            BoardRenameDialog(
                                initial = boardName,
                                onDismiss = { showRename = false },
                                onConfirm = { next ->
                                    onBoardNameChange(next)
                                    onRenameBoard(next)
                                    showRename = false
                                },
                            )
                        }
                        TaskSettingsCard("Layout") {
                            AssistChip(onClick = { onCompactChange(!compact) }, label = { Text(if (compact) "Compact cards ✓" else "Comfortable cards") })
                            Text("Default view: Table", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    null -> Unit
                }
            }
        }
    }
}

@Composable
private fun TaskSettingsCard(title: String, content: @Composable () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f)),
        tonalElevation = 2.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title.uppercase(), fontWeight = FontWeight.Black, fontSize = 12.sp, color = MaterialTheme.colorScheme.primary, letterSpacing = 1.sp)
            content()
        }
    }
}

@Composable
private fun BoardRenameDialog(initial: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var draft by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename board", fontWeight = FontWeight.Black) },
        text = {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                placeholder = { Text("Board name") },
                colors = taskTextFieldColors(),
            )
        },
        confirmButton = { Button(onClick = { onConfirm(draft.trim()) }, enabled = draft.isNotBlank()) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun FilterChips(title: String, values: List<String>, selected: String, onSelect: (String) -> Unit) {
    Text(title, fontWeight = FontWeight.Bold)
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        item { AssistChip(onClick = { onSelect("") }, label = { Text(if (selected.isBlank()) "All ✓" else "All") }) }
        items(values) { value ->
            AssistChip(onClick = { onSelect(value) }, label = { Text(if (selected == value) "$value ✓" else value) })
        }
    }
}

@Composable
private fun AdaptiveTaskPage(
    task: TaskItem,
    properties: List<TaskPropertyDefinition>,
    propertyValues: List<TaskPropertyValue>,
    checklistItems: List<TaskChecklistItem>,
    columns: List<TaskColumnItem>,
    taskObjectId: Long?,
    comments: List<WorkspaceComment>,
    files: List<WorkspaceFileItem>,
    viewModel: NotesViewModel,
    onPickAttachment: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.35f))
            .zIndex(20f),
    ) {
        val sidePanel = maxWidth > 720.dp
        Surface(
            color = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
            tonalElevation = 10.dp,
            shape = if (sidePanel) RoundedCornerShape(topStart = 28.dp, bottomStart = 28.dp) else RoundedCornerShape(0.dp),
            modifier = Modifier
                .then(if (sidePanel) Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(540.dp) else Modifier.fillMaxSize())
                .navigationBarsPadding()
                .imePadding(),
        ) {
            var showPicker by remember { mutableStateOf(false) }
            var commentDraft by remember(task.id) { mutableStateOf("") }
            val sortedProperties = properties.sortedWith(compareBy<TaskPropertyDefinition> { it.sortOrder }.thenBy { it.id })
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 18.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val nameProperty = sortedProperties.firstOrNull { it.type == TaskPropertyType.Name }
                        var editingTitle by remember(task.id) { mutableStateOf(false) }
                        var titleDraft by remember(task.id, task.title) { mutableStateOf(task.title) }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (editingTitle) {
                                OutlinedTextField(
                                    value = titleDraft,
                                    onValueChange = { titleDraft = it },
                                    modifier = Modifier.fillMaxWidth(),
                                    textStyle = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Black),
                                    singleLine = true,
                                    colors = taskTextFieldColors(),
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    TextButton(onClick = {
                                        titleDraft = task.title
                                        editingTitle = false
                                    }) { Text("Cancel") }
                                    Button(
                                        onClick = {
                                            nameProperty?.let { property -> viewModel.setTaskPropertyValue(task, property, titleDraft) }
                                            editingTitle = false
                                        },
                                        enabled = titleDraft.isNotBlank(),
                                    ) { Text("Save") }
                                }
                            } else {
                                Text(task.title.ifBlank { "Untitled task" }, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Black, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text("Saved task page", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        IconButton(onClick = { editingTitle = !editingTitle }) { Icon(Icons.Outlined.Edit, null) }
                        IconButton(onClick = onDismiss) { Icon(Icons.Outlined.Close, null) }
                    }
                }
                item { TaskColorPalette(task = task, viewModel = viewModel) }
                val visibleProperties = sortedProperties.filterNot { it.type == TaskPropertyType.Name || it.type == TaskPropertyType.FilesMedia }
                itemsIndexed(visibleProperties, key = { _, property -> property.id }) { index, property ->
                    TaskPropertyBlock(
                        task = task,
                        property = property,
                        propertyIndex = index,
                        propertyCount = visibleProperties.size,
                        value = propertyValues.firstOrNull { it.taskId == task.id && it.propertyId == property.id },
                        checklistItems = checklistItems.filter { it.taskId == task.id && it.propertyId == property.id }.sortedBy { it.sortOrder },
                        columns = columns,
                        viewModel = viewModel,
                        onPickAttachment = onPickAttachment,
                    )
                }
                item {
                    TaskFilesSection(
                        files = files.filter { it.objectId == taskObjectId },
                        onAttach = onPickAttachment,
                        onRemove = { viewModel.removeTaskFile(task, it) },
                    )
                }
                item {
                    TextButton(onClick = { showPicker = true }) {
                        Icon(Icons.Outlined.Add, null)
                        Spacer(Modifier.width(8.dp))
                        Text("New property")
                    }
                }
                item {
                    HorizontalDivider()
                    Text("Comments", color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Bold)
                    comments.filter { it.objectId == taskObjectId }.sortedBy { it.createdAt }.forEach { comment ->
                        Surface(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.38f), shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(10.dp)) {
                                Text(comment.authorDisplayName.ifBlank { comment.authorUsername.ifBlank { "You" } }, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                Text(comment.body)
                            }
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(commentDraft, { commentDraft = it }, modifier = Modifier.weight(1f), placeholder = { Text("Add a reply...") }, colors = taskTextFieldColors())
                        IconButton(
                            enabled = taskObjectId != null && commentDraft.isNotBlank(),
                            onClick = { taskObjectId?.let { viewModel.addWorkspaceComment(it, commentDraft.trim()) }; commentDraft = "" },
                        ) { Icon(Icons.Outlined.Add, null) }
                    }
                }
                item {
                    Text("Enter a / to insert a block, or start typing", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 18.sp)
                }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        TextButton(onClick = onDelete) {
                            Icon(Icons.Outlined.Delete, null)
                            Spacer(Modifier.width(6.dp))
                            Text("Delete")
                        }
                        Spacer(Modifier.weight(1f))
                        Button(onClick = onDismiss) { Text("Done") }
                    }
                }
            }
            if (showPicker) {
                TaskPropertyPicker(
                    onDismiss = { showPicker = false },
                    onPick = { type ->
                        viewModel.createTaskProperty(task.taskBoardId, type.defaultPropertyName(), type)
                        showPicker = false
                    },
                )
            }
        }
    }
}

@Composable
private fun TaskFilesSection(files: List<WorkspaceFileItem>, onAttach: () -> Unit, onRemove: (WorkspaceFileItem) -> Unit) {
    val context = LocalContext.current
    Surface(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f), shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.AttachFile, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text("Files & media", modifier = Modifier.weight(1f), fontWeight = FontWeight.Bold)
                TextButton(onClick = onAttach) { Text("Attach") }
            }
            if (files.isEmpty()) Text("No files attached", color = MaterialTheme.colorScheme.onSurfaceVariant)
            files.forEach { file ->
                Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Outlined.AttachFile, null, tint = MaterialTheme.colorScheme.primary)
                        Column(Modifier.weight(1f)) {
                            Text(file.displayName, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(file.mimeType, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        TextButton(onClick = {
                            runCatching {
                                context.startActivity(Intent(Intent.ACTION_VIEW).apply {
                                    setDataAndType(Uri.parse(file.uri), file.mimeType.ifBlank { "*/*" })
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                })
                            }
                        }) { Text("Open") }
                        IconButton(onClick = { onRemove(file) }) { Icon(Icons.Outlined.Delete, null) }
                    }
                }
            }
        }
    }
}

/** Tasteful accent palette a user can assign per task; drives calendar/board/matrix tinting. */
private val TaskAccentPalette = listOf(
    0xFF7E5BEFL, // purple
    0xFF3B82F6L, // blue
    0xFF06B6D4L, // cyan
    0xFF10B981L, // green
    0xFFF59E0BL, // amber
    0xFFEF4444L, // red
    0xFFEC4899L, // pink
)

/** A task's chosen color, or [fallback] (usually a theme/priority accent) when none is set. */
private fun TaskItem.resolveAccent(fallback: Color): Color =
    colorArgb?.let { Color(it.toInt()) } ?: fallback

@Composable
private fun TaskColorPalette(task: TaskItem, viewModel: NotesViewModel) {
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Color", color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Bold)
        ColorSwatch(
            color = MaterialTheme.colorScheme.surfaceVariant,
            selected = task.colorArgb == null,
            onClick = { viewModel.setTaskColor(task, null) },
        )
        TaskAccentPalette.forEach { argb ->
            ColorSwatch(
                color = Color(argb.toInt()),
                selected = task.colorArgb == argb,
                onClick = { viewModel.setTaskColor(task, argb) },
            )
        }
    }
}

@Composable
private fun ColorSwatch(color: Color, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(26.dp)
            .clip(CircleShape)
            .background(color)
            .border(
                width = if (selected) 3.dp else 1.dp,
                color = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline.copy(alpha = 0.4f),
                shape = CircleShape,
            )
            .clickable(onClick = onClick),
    )
}

@Composable
private fun TaskPropertyBlock(
    task: TaskItem,
    property: TaskPropertyDefinition,
    propertyIndex: Int,
    propertyCount: Int,
    value: TaskPropertyValue?,
    checklistItems: List<TaskChecklistItem>,
    columns: List<TaskColumnItem>,
    viewModel: NotesViewModel,
    onPickAttachment: () -> Unit,
) {
    if (property.hiddenWhenEmpty && value?.valueJson.isNullOrBlank() && checklistItems.isEmpty()) return
    var menuOpen by remember { mutableStateOf(false) }
    Surface(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f), shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                VerticalReorderHandle(enabled = propertyCount > 1, rowHeight = 96.dp) { delta ->
                    viewModel.reorderTaskPropertyToIndex(property, (propertyIndex + delta).coerceIn(0, propertyCount - 1))
                }
                Icon(property.type.icon(), null, tint = MaterialTheme.colorScheme.primary)
                Text(property.name, modifier = Modifier.weight(1f), fontWeight = FontWeight.Bold)
                if (property.type == TaskPropertyType.Checklist) {
                    checklistItems.progressPercent()?.let { Text("$it%", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                IconButton(onClick = { menuOpen = true }) { Icon(Icons.Outlined.MoreVert, null) }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(text = { Text(if (property.hiddenWhenEmpty) "Always show" else "Hide when empty") }, leadingIcon = { Icon(Icons.Outlined.Visibility, null) }, onClick = { viewModel.updateTaskPropertyDefinition(property, property.name, property.type, !property.hiddenWhenEmpty); menuOpen = false })
                    DropdownMenuItem(text = { Text("Duplicate") }, leadingIcon = { Icon(Icons.Outlined.ContentCopy, null) }, onClick = { viewModel.duplicateTaskProperty(property); menuOpen = false })
                    DropdownMenuItem(text = { Text("Delete") }, leadingIcon = { Icon(Icons.Outlined.Delete, null) }, onClick = { viewModel.deleteTaskProperty(property); menuOpen = false })
                }
            }
            when (property.type) {
                TaskPropertyType.Checklist -> ChecklistPropertyEditor(task, property, checklistItems, viewModel)
                TaskPropertyType.Checkbox -> CheckboxPropertyEditor(task, property, value?.valueJson.orEmpty(), viewModel)
                TaskPropertyType.FilesMedia -> FilesPropertyEditor(task, property, value?.valueJson.orEmpty(), viewModel, onPickAttachment)
                TaskPropertyType.CreatedAt -> ReadOnlyPropertyValue(shortTime(task.createdAt))
                TaskPropertyType.LastModified -> ReadOnlyPropertyValue(shortTime(task.updatedAt))
                TaskPropertyType.DueDate, TaskPropertyType.Date -> DatePropertyEditor(task, property, value?.valueJson.orEmpty(), viewModel)
                TaskPropertyType.Status -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    columns.forEach { column ->
                        Surface(
                            color = if (task.taskColumnId == column.id) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth().clickable { viewModel.moveTaskToColumn(task, column) },
                        ) { Text(column.name, Modifier.padding(10.dp), fontWeight = FontWeight.Bold) }
                    }
                }
                TaskPropertyType.Priority -> ChoicePropertyEditor(task, property, task.priority.name, TaskPriority.entries.map { it.name }, viewModel)
                else -> TextPropertyEditor(task, property, property.displayValue(task, value?.valueJson.orEmpty()), viewModel)
            }
        }
    }
}

/**
 * A drag handle that live-reorders as you drag: each time the drag crosses one row height it emits
 * a single step (+1 down / -1 up), so the block moves continuously under the finger rather than only
 * snapping on release. [onStep] is expected to persist the move (which re-renders the list).
 */
@Composable
private fun VerticalReorderHandle(enabled: Boolean = true, rowHeight: Dp = 64.dp, onStep: (Int) -> Unit) {
    val density = LocalDensity.current
    val thresholdPx = with(density) { rowHeight.toPx() }
    var dragY by remember { mutableStateOf(0f) }
    var dragging by remember { mutableStateOf(false) }
    Icon(
        Icons.Outlined.DragIndicator,
        null,
        tint = if (dragging) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .size(28.dp)
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectDragGesturesAfterLongPress(
                    onDragStart = { dragY = 0f; dragging = true },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        dragY += dragAmount.y
                        while (dragY >= thresholdPx) { onStep(1); dragY -= thresholdPx }
                        while (dragY <= -thresholdPx) { onStep(-1); dragY += thresholdPx }
                    },
                    onDragEnd = { dragY = 0f; dragging = false },
                    onDragCancel = { dragY = 0f; dragging = false },
                )
            },
    )
}

@Composable
private fun PropertyPreviewCard(value: String, property: TaskPropertyDefinition) {
    val isMarkdown = property.type == TaskPropertyType.Text && value.isNotBlank()
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val accentHex = String.format("#%06X", 0xFFFFFF and MaterialTheme.colorScheme.primary.toArgb())
    Surface(
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.68f),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        if (isMarkdown) {
            // Saved text blocks preview as rendered Markdown, matching the polished note editor.
            MarkdownPreview(markdown = value, dark = dark, accentHex = accentHex, modifier = Modifier.fillMaxWidth().padding(10.dp))
        } else {
            Text(
                value.ifBlank { "Empty" },
                modifier = Modifier.padding(12.dp),
                color = if (value.isBlank()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun TextPropertyEditor(task: TaskItem, property: TaskPropertyDefinition, initial: String, viewModel: NotesViewModel) {
    var editing by remember(task.id, property.id, initial) { mutableStateOf(initial.isBlank()) }
    var text by remember(task.id, property.id, initial) { mutableStateOf(initial) }
    if (!editing) {
        PropertyPreviewCard(text, property)
        TextButton(onClick = { editing = true }) {
            Icon(Icons.Outlined.Edit, null)
            Spacer(Modifier.width(6.dp))
            Text("Edit")
        }
        return
    }
    OutlinedTextField(
        value = text,
        onValueChange = { text = it },
        modifier = Modifier.fillMaxWidth().heightIn(min = if (property.type == TaskPropertyType.Text) 96.dp else 54.dp),
        placeholder = { Text(property.type.defaultPropertyName()) },
        colors = taskTextFieldColors(),
    )
    if (property.type == TaskPropertyType.Text) {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            item { AssistChip(onClick = { text += "\n- [ ] " }, label = { Text("Checklist") }, leadingIcon = { Icon(Icons.Outlined.CheckBox, null) }) }
            item { AssistChip(onClick = { text += "\n| Column | Value |\n| --- | --- |\n" }, label = { Text("Table") }, leadingIcon = { Icon(Icons.Outlined.TableRows, null) }) }
            item { AssistChip(onClick = { text += "\n---\n" }, label = { Text("Divider") }, leadingIcon = { Icon(Icons.Outlined.Title, null) }) }
            item { AssistChip(onClick = { text += "[](url)" }, label = { Text("Link") }, leadingIcon = { Icon(Icons.Outlined.Link, null) }) }
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = {
            text = initial
            editing = false
        }) { Text("Cancel") }
        Button(onClick = {
            viewModel.setTaskPropertyValue(task, property, text)
            editing = false
        }) { Text("Save") }
    }
}

@Composable
private fun DatePropertyEditor(task: TaskItem, property: TaskPropertyDefinition, raw: String, viewModel: NotesViewModel) {
    val initial = remember(raw, task.startAt, task.dueAt) {
        TaskDateRangeCodec.decode(raw, task.dueAt).let { range ->
            range.copy(startAt = range.startAt ?: task.startAt ?: task.dueAt, endAt = range.endAt ?: task.dueAt)
        }
    }
    var draft by remember(task.id, property.id, initial) { mutableStateOf(initial) }
    var showPicker by remember { mutableStateOf(false) }
    var pickingStart by remember { mutableStateOf(true) }

    fun persist(next: TaskDateRange) {
        draft = next.normalized()
        viewModel.setTaskPropertyValue(task, property, TaskDateRangeCodec.encode(draft))
    }

    Surface(color = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f), shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(Icons.Outlined.Event, null, tint = MaterialTheme.colorScheme.primary)
            Text(
                when {
                    draft.startAt == null && draft.endAt == null -> "No date set"
                    draft.startAt == draft.endAt -> formatCalendarDate(draft.endAt ?: draft.startAt!!)
                    else -> "${formatCalendarDate(draft.startAt!!)} → ${formatCalendarDate(draft.endAt!!)}"
                },
                modifier = Modifier.weight(1f),
                color = if (draft.startAt == null && draft.endAt == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Bold,
            )
            if (draft.startAt != null || draft.endAt != null) {
                TextButton(onClick = {
                    persist(TaskDateRange(null, null, draft.allDay, draft.reminderMinutesBefore))
                }) { Text("Clear") }
            }
            FilledTonalButton(onClick = { pickingStart = true; showPicker = true }) {
                Icon(Icons.Outlined.Edit, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Start")
            }
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilledTonalButton(onClick = { pickingStart = false; showPicker = true }) { Text("End date") }
        Text("All day", modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Switch(checked = draft.allDay, onCheckedChange = { persist(draft.copy(allDay = it)) })
    }
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(listOf(null to "No reminder", 0 to "At time", 60 to "1 hour", 1440 to "1 day")) { (minutes, label) ->
            AssistChip(
                onClick = { persist(draft.copy(reminderMinutesBefore = minutes)) },
                label = { Text(if (draft.reminderMinutesBefore == minutes) "$label ✓" else label) },
            )
        }
    }

    if (showPicker) {
        val pickerState = rememberDatePickerState(initialSelectedDateMillis = (if (pickingStart) draft.startAt else draft.endAt) ?: System.currentTimeMillis())
        DatePickerDialog(
            onDismissRequest = { showPicker = false },
            confirmButton = {
                TextButton(onClick = {
                    pickerState.selectedDateMillis?.let { picked ->
                        persist(if (pickingStart) draft.copy(startAt = picked, endAt = draft.endAt ?: picked) else draft.copy(endAt = picked, startAt = draft.startAt ?: picked))
                    }
                    showPicker = false
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { showPicker = false }) { Text("Cancel") } },
        ) {
            DatePicker(state = pickerState)
        }
    }
}

@Composable
private fun ChoicePropertyEditor(task: TaskItem, property: TaskPropertyDefinition, selected: String, choices: List<String>, viewModel: NotesViewModel) {
    var editing by remember(task.id, property.id, selected) { mutableStateOf(selected.isBlank()) }
    var draft by remember(task.id, property.id, selected) { mutableStateOf(selected) }
    if (!editing) {
        PropertyPreviewCard(draft, property)
        TextButton(onClick = { editing = true }) {
            Icon(Icons.Outlined.Edit, null)
            Spacer(Modifier.width(6.dp))
            Text("Edit")
        }
        return
    }
    Surface(color = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f), shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(choices) { choice ->
                    AssistChip(onClick = { draft = choice }, label = { Text(if (choice == draft) "$choice ✓" else choice) })
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = {
                    draft = selected
                    editing = false
                }) { Text("Cancel") }
                Button(onClick = {
                    viewModel.setTaskPropertyValue(task, property, draft)
                    editing = false
                }) { Text("Save") }
            }
        }
    }
}

@Composable
private fun CheckboxPropertyEditor(task: TaskItem, property: TaskPropertyDefinition, raw: String, viewModel: NotesViewModel) {
    val checked = raw.toBoolean()
    var editing by remember(task.id, property.id, raw) { mutableStateOf(raw.isBlank()) }
    var draft by remember(task.id, property.id, raw) { mutableStateOf(checked) }
    if (!editing) {
        PropertyPreviewCard(if (draft) "Checked" else "Unchecked", property)
        TextButton(onClick = { editing = true }) {
            Icon(Icons.Outlined.Edit, null)
            Spacer(Modifier.width(6.dp))
            Text("Edit")
        }
        return
    }
    Surface(color = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f), shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = draft, onCheckedChange = { draft = it })
                Text(if (draft) "Checked" else "Unchecked")
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = {
                    draft = checked
                    editing = false
                }) { Text("Cancel") }
                Button(onClick = {
                    viewModel.setTaskPropertyValue(task, property, draft.toString())
                    editing = false
                }) { Text("Save") }
            }
        }
    }
}

@Composable
private fun FilesPropertyEditor(task: TaskItem, property: TaskPropertyDefinition, raw: String, viewModel: NotesViewModel, onPickAttachment: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(Icons.Outlined.AttachFile, null)
        Text(task.attachmentName ?: raw.ifBlank { "Empty" }, modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton(onClick = {
            viewModel.setTaskPropertyValue(task, property, task.attachmentName.orEmpty())
            onPickAttachment()
        }) { Text("Attach") }
    }
}

@Composable
private fun ChecklistPropertyEditor(task: TaskItem, property: TaskPropertyDefinition, items: List<TaskChecklistItem>, viewModel: NotesViewModel) {
    var newItem by remember(task.id, property.id) { mutableStateOf("") }
    items.progressPercent()?.let {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            LinearProgressIndicator(progress = { it / 100f }, modifier = Modifier.weight(1f))
            Text("$it%")
        }
    }
    items.forEachIndexed { index, item ->
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            VerticalReorderHandle(enabled = items.size > 1, rowHeight = 56.dp) { delta ->
                viewModel.moveChecklistItemToIndex(item, (index + delta).coerceIn(0, items.lastIndex))
            }
            Checkbox(checked = item.checked, onCheckedChange = { viewModel.updateChecklistItem(item, item.text, it) })
            var text by remember(item.id, item.text) { mutableStateOf(item.text) }
            OutlinedTextField(text, { next -> text = next; viewModel.updateChecklistItem(item, next, item.checked) }, modifier = Modifier.weight(1f), singleLine = true, colors = taskTextFieldColors())
            IconButton(onClick = { viewModel.deleteChecklistItem(item) }) { Icon(Icons.Outlined.Delete, null) }
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(newItem, { newItem = it }, modifier = Modifier.weight(1f), singleLine = true, placeholder = { Text("Add a new task") }, colors = taskTextFieldColors())
        Button(onClick = {
            viewModel.addChecklistItem(task, property, newItem)
            newItem = ""
        }, enabled = newItem.isNotBlank()) { Text("Create") }
    }
}

@Composable
private fun ReadOnlyPropertyValue(value: String) {
    Text(value, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 8.dp))
}

@Composable
private fun TaskPropertyPicker(onDismiss: () -> Unit, onPick: (TaskPropertyType) -> Unit) {
    var query by remember { mutableStateOf("") }
    val types = remember(query) {
        TaskPropertyPickerTypes.filter { it.defaultPropertyName().contains(query, ignoreCase = true) || it.name.contains(query, ignoreCase = true) }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New property") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(query, { query = it }, modifier = Modifier.fillMaxWidth(), singleLine = true, leadingIcon = { Icon(Icons.Outlined.Search, null) }, placeholder = { Text("Search property types") }, colors = taskTextFieldColors())
                LazyColumn(modifier = Modifier.heightIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(types) { type ->
                        Row(
                            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { onPick(type) }.padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Icon(type.icon(), null, tint = MaterialTheme.colorScheme.primary)
                            Text(type.defaultPropertyName(), fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
private fun TaskDetailDialog(
    task: TaskItem,
    onPickAttachment: () -> Unit,
    onDelete: () -> Unit,
    onSave: (String, String, TaskStatus, TaskPriority, Long?, String, String) -> Unit,
    onDismiss: () -> Unit,
) {
    var title by remember(task.id) { mutableStateOf(task.title) }
    var description by remember(task.id) { mutableStateOf(task.description) }
    var status by remember(task.id) { mutableStateOf(task.status) }
    var priority by remember(task.id) { mutableStateOf(task.priority) }
    var assignee by remember(task.id) { mutableStateOf(task.assignee.ifBlank { "@owner" }) }
    var labels by remember(task.id) { mutableStateOf(task.labels) }
    var dueAt by remember(task.id) { mutableStateOf(task.dueAt) }
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surface, tonalElevation = 6.dp) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Task", fontWeight = FontWeight.Black, fontSize = 24.sp)
                OutlinedTextField(title, { title = it }, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("Name") }, colors = taskTextFieldColors())
                OutlinedTextField(description, { description = it }, modifier = Modifier.fillMaxWidth().heightIn(min = 110.dp), label = { Text("Notes") }, colors = taskTextFieldColors())
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(TaskStatus.entries) { value -> AssistChip(onClick = { status = value }, label = { Text(if (status == value) "${value.label()} ✓" else value.label()) }) }
                }
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(TaskPriority.entries) { value -> AssistChip(onClick = { priority = value }, label = { Text(if (priority == value) "${value.label()} ✓" else value.label()) }) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AssistChip(onClick = { dueAt = System.currentTimeMillis() }, label = { Text("Today") })
                    AssistChip(onClick = { dueAt = System.currentTimeMillis() + 86_400_000L }, label = { Text("Tomorrow") })
                    AssistChip(onClick = { dueAt = null }, label = { Text("No date") })
                }
                OutlinedTextField(assignee, { assignee = it }, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("Assignee") }, colors = taskTextFieldColors())
                OutlinedTextField(labels, { labels = it }, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("Labels") }, colors = taskTextFieldColors())
                if (task.attachmentName != null) TinyMeta("File: ${task.attachmentName}")
                FilledTonalButton(onClick = onPickAttachment) {
                    Icon(Icons.Outlined.AttachFile, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Attach file/media")
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    TextButton(onClick = onDelete) {
                        Icon(Icons.Outlined.Delete, null)
                        Spacer(Modifier.width(6.dp))
                        Text("Delete")
                    }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    Button(onClick = { onSave(title, description, status, priority, dueAt, assignee, labels) }) { Text("Save") }
                }
            }
        }
    }
}

@Stable
private class BoardDragState {
    var boardBounds by mutableStateOf(Rect.Zero)
    val columnBounds = mutableStateMapOf<Long, TaskColumnBounds>()
    val cardBounds = mutableStateMapOf<Long, TaskCardBounds>()
    var tasksById: Map<Long, TaskItem> = emptyMap()
    var draggingTask by mutableStateOf<TaskItem?>(null)
    var pointer by mutableStateOf(Offset.Zero)
    var grabOffset by mutableStateOf(Offset.Zero)
    var lastTarget by mutableStateOf<TaskDropTarget?>(null)
    var lastDrop by mutableStateOf("No drop yet")

    fun toBoardOffset(offsetInRoot: Offset): Offset =
        offsetInRoot - boardBounds.topLeft

    fun toBoardRect(rectInRoot: Rect): Rect =
        Rect(
            left = rectInRoot.left - boardBounds.left,
            top = rectInRoot.top - boardBounds.top,
            right = rectInRoot.right - boardBounds.left,
            bottom = rectInRoot.bottom - boardBounds.top,
        )

    fun hitCard(pointerInBoard: Offset): TaskCardHit? =
        cardBounds.values
            .filter { it.bounds.contains(pointerInBoard) }
            .minByOrNull { it.bounds.height * it.bounds.width }
            ?.let { bounds -> tasksById[bounds.taskId]?.let { TaskCardHit(it, bounds.bounds) } }

    fun start(task: TaskItem, pointerInBoard: Offset, localGrabOffset: Offset) {
        draggingTask = task
        pointer = pointerInBoard
        grabOffset = localGrabOffset
        lastTarget = null
    }

    fun currentTarget(): TaskDropTarget? {
        val task = draggingTask ?: return null
        val target = TaskDragPlanner.resolveDropTarget(
            pointer = pointer,
            columns = columnBounds.values.toList(),
            cards = cardBounds.values.toList(),
            draggingTaskId = task.id,
            previousTarget = lastTarget,
        )
        lastTarget = target
        return target
    }

    fun dropIndicatorRect(target: TaskDropTarget): Rect? {
        val column = columnBounds[target.columnId]?.bounds ?: return null
        val cards = cardBounds.values
            .filter { it.columnId == target.columnId && it.taskId != draggingTask?.id }
            .sortedWith(compareBy<TaskCardBounds> { it.bounds.top }.thenBy { it.bounds.left })
        val y = when {
            cards.isEmpty() -> column.top + 76f
            target.index <= 0 -> cards.first().bounds.top - 7f
            target.index >= cards.size -> cards.last().bounds.bottom + 7f
            else -> (cards[target.index - 1].bounds.bottom + cards[target.index].bounds.top) / 2f
        }
        return Rect(column.left + 18f, y - 2f, column.right - 18f, y + 2f)
    }

    fun edgeScrollVelocity(): Float {
        if (draggingTask == null || boardBounds == Rect.Zero) return 0f
        val edge = 96f
        val maxSpeed = 34f
        val leftDistance = pointer.x
        val rightDistance = boardBounds.width - pointer.x
        return when {
            leftDistance in 0f..edge -> -maxSpeed * ((edge - leftDistance) / edge).coerceIn(0.25f, 1f)
            rightDistance in 0f..edge -> maxSpeed * ((edge - rightDistance) / edge).coerceIn(0.25f, 1f)
            else -> 0f
        }
    }

    fun status(engine: TaskKanbanEngine, dragging: String): TaskInteractionStatus {
        val target = currentTarget()
        val targetText = target?.let { "${it.columnId} / ${it.index + 1}" } ?: "-"
        return TaskInteractionStatus(engine = engine, draggingTask = dragging, target = targetText, lastDrop = lastDrop)
    }

    fun clear(keepLastDrop: String = lastDrop) {
        lastDrop = keepLastDrop
        draggingTask = null
        pointer = Offset.Zero
        grabOffset = Offset.Zero
        lastTarget = null
    }
}

private suspend fun autoScrollBoard(state: LazyListState, pointer: Offset, boardBounds: Rect) {
    if (boardBounds == Rect.Zero) return
    val edge = 72f
    val leftDistance = pointer.x
    val rightDistance = boardBounds.width - pointer.x
    when {
        leftDistance < edge -> state.scrollBy(-26f)
        rightDistance < edge -> state.scrollBy(26f)
    }
}

private data class TaskCardHit(
    val task: TaskItem,
    val bounds: Rect,
)

@Composable
private fun DropIndicator() {
    Surface(
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.20f),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().height(42.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text("Drop here", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun StatusCount(count: Int) {
    Surface(color = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f), contentColor = MaterialTheme.colorScheme.primary, shape = CircleShape) {
        Text(count.toString(), modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp), fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun PriorityPill(priority: TaskPriority) {
    val color = when (priority) {
        TaskPriority.High -> Color(0xFFE25D6A)
        TaskPriority.Urgent -> Color(0xFFFF4D7D)
        TaskPriority.Low -> Color(0xFF5FCB8F)
        TaskPriority.Normal -> MaterialTheme.colorScheme.primary
    }
    Surface(color = color.copy(alpha = 0.16f), contentColor = color, shape = RoundedCornerShape(10.dp)) {
        Text(priority.label(), modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp), fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun TinyMeta(text: String) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f), shape = RoundedCornerShape(10.dp)) {
        Text(text, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
    }
}

@Composable
private fun EmptyTaskState(text: String) {
    Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
        Text(text, modifier = Modifier.padding(20.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun taskBackground(): Brush = Brush.verticalGradient(
    listOf(
        MaterialTheme.colorScheme.background,
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
    ),
)

@Composable
private fun taskTextFieldColors() = TextFieldDefaults.colors(
    focusedContainerColor = MaterialTheme.colorScheme.surface,
    unfocusedContainerColor = MaterialTheme.colorScheme.surface,
    focusedIndicatorColor = MaterialTheme.colorScheme.primary,
    unfocusedIndicatorColor = MaterialTheme.colorScheme.outlineVariant,
)

private fun List<TaskItem>.filterTasks(query: String, assignee: String, label: String, priority: TaskPriority?): List<TaskItem> =
    filter { task ->
        val needle = query.trim()
        val queryMatch = needle.isBlank() ||
            task.title.contains(needle, ignoreCase = true) ||
            task.description.contains(needle, ignoreCase = true) ||
            task.labels.contains(needle, ignoreCase = true)
        val assigneeMatch = assignee.isBlank() || task.assignee.equals(assignee, ignoreCase = true)
        val labelMatch = label.isBlank() || task.labels.split(",").map(String::trim).any { it.equals(label, ignoreCase = true) }
        val priorityMatch = priority == null || task.priority == priority
        queryMatch && assigneeMatch && labelMatch && priorityMatch
    }

private fun List<TaskItem>.sortedFor(sort: TaskWorkspaceSort): List<TaskItem> = when (sort) {
    TaskWorkspaceSort.Manual -> sortedWith(compareBy<TaskItem> { it.taskColumnId ?: Long.MAX_VALUE }.thenBy { it.sortOrder }.thenByDescending { it.updatedAt })
    TaskWorkspaceSort.Updated -> sortedByDescending { it.updatedAt }
    TaskWorkspaceSort.DueDate -> sortedWith(compareBy<TaskItem> { it.dueAt ?: Long.MAX_VALUE }.thenBy { it.sortOrder })
    TaskWorkspaceSort.Priority -> sortedWith(compareBy<TaskItem> { it.priority.rank() }.thenBy { it.sortOrder })
    TaskWorkspaceSort.Title -> sortedBy { it.title.lowercase() }
}

private fun TaskStatus.label(): String = when (this) {
    TaskStatus.Todo -> "To do"
    TaskStatus.Doing -> "In progress"
    TaskStatus.Done -> "Done"
}

private fun TaskPriority.label(): String = when (this) {
    TaskPriority.Low -> "Low"
    TaskPriority.Normal -> "Normal"
    TaskPriority.High -> "High"
    TaskPriority.Urgent -> "Urgent"
}

private val TaskPropertyPickerTypes = listOf(
    TaskPropertyType.Text,
    TaskPropertyType.Numbers,
    TaskPropertyType.Select,
    TaskPropertyType.Multiselect,
    TaskPropertyType.Date,
    TaskPropertyType.Person,
    TaskPropertyType.FilesMedia,
    TaskPropertyType.Url,
    TaskPropertyType.Checkbox,
    TaskPropertyType.Checklist,
    TaskPropertyType.CreatedAt,
    TaskPropertyType.LastModified,
    TaskPropertyType.Relation,
    TaskPropertyType.Rollup,
    TaskPropertyType.AiSummary,
    TaskPropertyType.AiTranslate,
)

private fun TaskPropertyDefinition.headerLabel(): String = "${type.symbol()} $name"

private fun TaskPropertyType.defaultPropertyName(): String = when (this) {
    TaskPropertyType.Name -> "Name"
    TaskPropertyType.Status -> "Status"
    TaskPropertyType.Checklist -> "Checklist"
    TaskPropertyType.DueDate -> "Due Date"
    TaskPropertyType.Text -> "Text"
    TaskPropertyType.FilesMedia -> "Files & media"
    TaskPropertyType.Assignee -> "Assignee"
    TaskPropertyType.Labels -> "Labels"
    TaskPropertyType.Priority -> "Priority"
    TaskPropertyType.CreatedAt -> "Created at"
    TaskPropertyType.LastModified -> "Last modified"
    TaskPropertyType.Numbers -> "Numbers"
    TaskPropertyType.Select -> "Select"
    TaskPropertyType.Multiselect -> "Multiselect"
    TaskPropertyType.Date -> "Date"
    TaskPropertyType.Person -> "Person"
    TaskPropertyType.Url -> "URL"
    TaskPropertyType.Checkbox -> "Checkbox"
    TaskPropertyType.Relation -> "Relation"
    TaskPropertyType.Rollup -> "Rollup"
    TaskPropertyType.AiSummary -> "AI Summary"
    TaskPropertyType.AiTranslate -> "AI Translate"
}

private fun TaskPropertyType.symbol(): String = when (this) {
    TaskPropertyType.Text, TaskPropertyType.Name -> "T"
    TaskPropertyType.Numbers -> "#"
    TaskPropertyType.Checklist -> "+"
    TaskPropertyType.DueDate, TaskPropertyType.Date, TaskPropertyType.CreatedAt, TaskPropertyType.LastModified -> "@"
    TaskPropertyType.FilesMedia -> "~"
    TaskPropertyType.Checkbox -> "[]"
    TaskPropertyType.Url, TaskPropertyType.Relation, TaskPropertyType.Rollup -> "->"
    else -> "*"
}

private fun TaskPropertyType.icon(): ImageVector = when (this) {
    TaskPropertyType.Name -> Icons.Outlined.Title
    TaskPropertyType.Text -> Icons.Outlined.TextFields
    TaskPropertyType.Checklist, TaskPropertyType.Checkbox -> Icons.Outlined.CheckBox
    TaskPropertyType.DueDate, TaskPropertyType.Date, TaskPropertyType.CreatedAt, TaskPropertyType.LastModified -> Icons.Outlined.Event
    TaskPropertyType.FilesMedia -> Icons.Outlined.AttachFile
    TaskPropertyType.Assignee, TaskPropertyType.Person -> Icons.Outlined.Person
    TaskPropertyType.Url, TaskPropertyType.Relation, TaskPropertyType.Rollup -> Icons.Outlined.Link
    TaskPropertyType.AiSummary, TaskPropertyType.AiTranslate -> Icons.Outlined.GridView
    else -> Icons.Outlined.TableRows
}

private fun TaskPropertyDefinition.displayValue(task: TaskItem, raw: String): String = when (type) {
    TaskPropertyType.Name -> task.title
    TaskPropertyType.Status -> task.status.name
    TaskPropertyType.DueDate, TaskPropertyType.Date -> raw.ifBlank { task.dueAt?.toString().orEmpty() }
    TaskPropertyType.Text -> raw.ifBlank { task.description }
    TaskPropertyType.FilesMedia -> raw.ifBlank { task.attachmentName.orEmpty() }
    TaskPropertyType.Assignee, TaskPropertyType.Person -> raw.ifBlank { task.assignee }
    TaskPropertyType.Labels, TaskPropertyType.Multiselect -> raw.ifBlank { task.labels }
    TaskPropertyType.Priority -> task.priority.name
    TaskPropertyType.CreatedAt -> task.createdAt.toString()
    TaskPropertyType.LastModified -> task.updatedAt.toString()
    else -> raw
}

private fun TaskItem.propertyCell(
    property: TaskPropertyDefinition,
    columns: List<TaskColumnItem>,
    values: List<TaskPropertyValue>,
    checklistItems: List<TaskChecklistItem>,
): String {
    val raw = values.firstOrNull { it.taskId == id && it.propertyId == property.id }?.valueJson.orEmpty()
    return when (property.type) {
        TaskPropertyType.Name -> title.ifBlank { "Untitled task" }
        TaskPropertyType.Status -> columns.firstOrNull { it.id == taskColumnId }?.name ?: status.label()
        TaskPropertyType.Checklist -> progressFromChecklist(checklistItems.filter { it.propertyId == property.id })?.let { "$it%" } ?: "Empty"
        TaskPropertyType.DueDate, TaskPropertyType.Date -> TaskDateRangeCodec.decode(raw, dueAt).let { range ->
            when {
                range.startAt == null && range.endAt == null -> ""
                range.startAt == range.endAt -> formatTaskDue(range.endAt ?: range.startAt!!)
                else -> "${formatTaskDue(range.startAt!!)} → ${formatTaskDue(range.endAt!!)}"
            }
        }
        TaskPropertyType.Text -> raw.ifBlank { description }.lineSequence().take(8).joinToString("\n")
        TaskPropertyType.FilesMedia -> raw.ifBlank { attachmentName ?: "Empty" }
        TaskPropertyType.Assignee, TaskPropertyType.Person -> raw.ifBlank { assignee.ifBlank { "@owner" } }
        TaskPropertyType.Labels, TaskPropertyType.Multiselect -> raw.ifBlank { labels.ifBlank { "None" } }
        TaskPropertyType.Priority -> raw.ifBlank { priority.label() }
        TaskPropertyType.CreatedAt -> shortTime(createdAt)
        TaskPropertyType.LastModified -> shortTime(updatedAt)
        TaskPropertyType.Checkbox -> if (raw.toBoolean()) "Checked" else "Empty"
        else -> raw.ifBlank { "Empty" }
    }
}

private fun TaskItem.propertyProgress(properties: List<TaskPropertyDefinition>, checklistItems: List<TaskChecklistItem>): Int? {
    val checklistPropertyIds = properties.filter { it.type == TaskPropertyType.Checklist }.map { it.id }.toSet()
    return progressFromChecklist(checklistItems.filter { it.taskId == id && it.propertyId in checklistPropertyIds })
}

private fun TaskItem.progressFromChecklist(checklistItems: List<TaskChecklistItem>): Int? {
    val items = checklistItems.filter { it.taskId == id }
    if (items.isEmpty()) return null
    return ((items.count { it.checked } * 100f) / items.size).roundToInt()
}

private fun List<TaskChecklistItem>.progressPercent(): Int? =
    if (isEmpty()) null else ((count { it.checked } * 100f) / size).roundToInt()

private fun TaskItem.checklistPreview(properties: List<TaskPropertyDefinition>, checklistItems: List<TaskChecklistItem>): List<String> {
    val checklistPropertyIds = properties.filter { it.type == TaskPropertyType.Checklist }.map { it.id }.toSet()
    return checklistItems
        .filter { it.taskId == id && it.propertyId in checklistPropertyIds }
        .sortedBy { it.sortOrder }
        .map { "${if (it.checked) "x" else " "} ${it.text}" }
}

private fun TaskPriority.rank(): Int = when (this) {
    TaskPriority.Urgent -> 0
    TaskPriority.High -> 1
    TaskPriority.Normal -> 2
    TaskPriority.Low -> 3
}

private fun TaskItem.progressPercent(): Int = when (status) {
    TaskStatus.Todo -> 0
    TaskStatus.Doing -> 50
    TaskStatus.Done -> 100
}

private fun formatTaskDue(dueAt: Long?): String {
    if (dueAt == null) return ""
    val days = ((dueAt - System.currentTimeMillis()) / 86_400_000L).toInt()
    return when {
        days < -1 -> "${-days}d ago"
        days == -1 -> "Yesterday"
        days == 0 -> "Today"
        days == 1 -> "Tomorrow"
        else -> "In ${days}d"
    }
}

private fun formatCalendarDate(ms: Long): String =
    SimpleDateFormat("EEE, MMM d, yyyy", Locale.getDefault()).format(Date(ms))

private fun shortTime(value: Long): String {
    val minutes = ((System.currentTimeMillis() - value) / 60_000L).coerceAtLeast(0)
    return when {
        minutes < 1 -> "Just now"
        minutes < 60 -> "${minutes}m ago"
        minutes < 1440 -> "${minutes / 60}h ago"
        else -> "${minutes / 1440}d ago"
    }
}
