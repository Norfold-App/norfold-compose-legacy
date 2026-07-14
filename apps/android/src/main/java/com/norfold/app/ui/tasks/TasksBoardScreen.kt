@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.norfold.app.ui.tasks

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DragIndicator
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Event
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.TableRows
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.material.icons.outlined.Title
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.ViewAgenda
import androidx.compose.material.icons.automirrored.outlined.Send
import com.norfold.app.ui.components.NorfoldDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.RadioButton
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import com.norfold.app.ui.components.NorfoldBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.norfold.app.ui.components.NorfoldContentDialog
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.zIndex
import com.norfold.app.ui.components.MarkdownPreview
import com.norfold.app.ui.dnd.DropSlot
import com.norfold.app.ui.dnd.animatePlacement
import com.norfold.app.ui.dnd.dragLift
import com.norfold.app.domain.TaskBoardItem
import com.norfold.app.domain.TaskChecklistItem
import com.norfold.app.domain.TaskColumnItem
import com.norfold.app.domain.TaskGestureAction
import com.norfold.app.domain.TaskItem
import com.norfold.app.domain.TaskPriority
import com.norfold.app.domain.TaskPropertyDefinition
import com.norfold.app.domain.TaskPropertyType
import com.norfold.app.domain.TaskPropertyValue
import com.norfold.app.domain.TaskStatus
import com.norfold.app.domain.Tag
import com.norfold.app.domain.TaskDateRange
import com.norfold.app.domain.TaskDateRangeCodec
import com.norfold.app.domain.WorkspaceComment
import com.norfold.app.domain.WorkspaceObjectType
import com.norfold.app.domain.WorkspaceFileItem
import com.norfold.app.ui.NotesUiState
import com.norfold.app.ui.NotesViewModel
import com.norfold.app.ui.LocalContextualMenuColor
import com.norfold.app.ui.LocalContextualMenuStyle
import com.norfold.app.domain.ContextualMenuColor
import com.norfold.app.domain.ContextualMenuStyle
import com.norfold.app.domain.Destination
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

private data class TaskCardCounts(val files: Int = 0, val comments: Int = 0, val links: Int = 0)

private enum class TaskTableColumn(val label: String, val width: Dp, val compactWidth: Dp) {
    Name("Name", 238.dp, 116.dp),
    StatusTags("Status / Tags", 190.dp, 92.dp),
    Checklist("Checklist", 210.dp, 104.dp),
    DueDate("Due Date", 170.dp, 88.dp),
    Notes("Notes / Text", 260.dp, 150.dp),
    Files("Files", 190.dp, 126.dp),
    PriorityAssignee("Priority / Assignee", 210.dp, 140.dp),
}

private data class TaskCreationPayload(
    val title: String,
    val column: TaskColumnItem,
    val propertyValues: Map<TaskPropertyType, String>,
    val checklistItems: List<String>,
)

private data class PendingTaskCreation(
    val existingTaskIds: Set<Long>,
    val payload: TaskCreationPayload,
)

private data class PendingTaskSwipe(
    val task: TaskItem,
    val action: TaskGestureAction,
    val priorStatus: TaskStatus,
)

@Composable
fun TasksBoardScreen(
    state: NotesUiState,
    viewModel: NotesViewModel,
    onPickTaskAttachment: (TaskItem) -> Unit = {},
    onTaskDetailOpenChange: (Boolean) -> Unit = {},
) {
    val currentView = TaskWorkspaceView.fromKey(state.settings.taskViewMode)
    val sort = TaskWorkspaceSort.fromKey(state.settings.taskSortMode)
    val kanbanEngine = TaskKanbanEngine.BoardPointer
    var query by remember { mutableStateOf("") }
    var assigneeFilter by remember { mutableStateOf("") }
    var labelFilter by remember { mutableStateOf("") }
    var priorityFilter by remember { mutableStateOf<TaskPriority?>(null) }
    var activeRailAction by remember { mutableStateOf<TaskRailAction?>(null) }
    // Feed display mode (Gallery merged into Feed): true = grid cards, false = list rows.
    var feedGridMode by rememberSaveable { mutableStateOf(true) }
    var editingTask by remember { mutableStateOf<TaskItem?>(null) }
    var pendingDeleteIds by remember { mutableStateOf(setOf<Long>()) }
    val swipeSnackbar = remember { SnackbarHostState() }
    val swipeScope = rememberCoroutineScope()
    var creatingTask by remember { mutableStateOf(false) }
    var createSeedColumn by remember { mutableStateOf<TaskColumnItem?>(null) }
    var pendingCreation by remember { mutableStateOf<PendingTaskCreation?>(null) }
    var boardName by remember { mutableStateOf("") }
    var newBoardName by remember { mutableStateOf("") }
    var newColumnName by remember { mutableStateOf("") }
    var interactionStatus by remember { mutableStateOf(TaskInteractionStatus(engine = kanbanEngine)) }
    LaunchedEffect(editingTask?.id, creatingTask) { onTaskDetailOpenChange(editingTask != null || creatingTask) }
    LaunchedEffect(currentView) {
        if (currentView == TaskWorkspaceView.Calendar) {
            viewModel.patchSettings { it.copy(taskViewMode = TaskWorkspaceView.Board.key) }
            viewModel.go(Destination.Calendar)
        }
    }

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
        .filterNot { it.id in pendingDeleteIds }
    val swipeStartAction = state.settings.taskSwipeStartAction
    val swipeEndAction = state.settings.taskSwipeEndAction
    val runSwipeAction: (TaskItem, TaskGestureAction) -> Unit = { task, action ->
        when (action) {
            TaskGestureAction.Complete -> {
                val prior = task.status
                viewModel.moveTask(task, if (prior == TaskStatus.Done) TaskStatus.Todo else TaskStatus.Done)
                if (prior != TaskStatus.Done) {
                    swipeScope.launch {
                        val result = swipeSnackbar.showSnackbar("Task completed", actionLabel = "Undo", duration = SnackbarDuration.Short)
                        if (result == SnackbarResult.ActionPerformed) viewModel.moveTask(task, prior)
                    }
                }
            }
            TaskGestureAction.Delete -> {
                pendingDeleteIds = pendingDeleteIds + task.id
                swipeScope.launch {
                    val result = swipeSnackbar.showSnackbar("Task deleted", actionLabel = "Undo", duration = SnackbarDuration.Long)
                    if (result != SnackbarResult.ActionPerformed) viewModel.deleteTask(task)
                    pendingDeleteIds = pendingDeleteIds - task.id
                }
            }
            TaskGestureAction.None -> Unit
        }
    }
    val assignees = state.tasks.map { it.assignee.ifBlank { "@owner" } }.distinct().sorted()
    val labels = state.tasks.flatMap { it.labels.split(",").map(String::trim) }.filter(String::isNotBlank).distinct().sorted()
    val taskCardCounts = boardTasks.associate { task ->
        val objectId = state.workspaceObjects.firstOrNull {
            it.objectType == WorkspaceObjectType.Task && it.sourceId == task.id
        }?.id
        task.id to if (objectId == null) {
            TaskCardCounts(files = if (task.attachmentName == null) 0 else 1)
        } else {
            TaskCardCounts(
                files = state.workspaceFiles.count { it.objectId == objectId } + if (task.attachmentName == null) 0 else 1,
                comments = state.workspaceComments.count { it.objectId == objectId && !it.resolved },
                links = state.workspaceObjectLinks.count { it.fromObjectId == objectId || it.toObjectId == objectId },
            )
        }
    }
    val taskFilesByTask = boardTasks.associate { task ->
        val objectId = state.workspaceObjects.firstOrNull {
            it.objectType == WorkspaceObjectType.Task && it.sourceId == task.id
        }?.id
        task.id to if (objectId == null) emptyList() else state.workspaceFiles.filter { it.objectId == objectId }
    }

    LaunchedEffect(selectedBoard?.id) {
        selectedBoard?.let { board ->
            if (state.settings.taskSelectedBoardId != board.id) {
                viewModel.patchSettings { it.copy(taskSelectedBoardId = board.id) }
            }
            boardName = board.name
        }
    }

    // After the ViewModel inserts the new task, find it and apply the drafted property values.
    LaunchedEffect(boardTasks, taskProperties, pendingCreation) {
        val pending = pendingCreation ?: return@LaunchedEffect
        val created = boardTasks
            .asSequence()
            .filter { it.id !in pending.existingTaskIds }
            .filter { it.taskColumnId == pending.payload.column.id }
            .filter { it.title == pending.payload.title }
            .maxByOrNull { it.id }
            ?: return@LaunchedEffect
        pending.payload.propertyValues.forEach { (type, raw) ->
            taskProperties.firstOrNull { it.type == type }?.let { property ->
                viewModel.setTaskPropertyValue(created, property, raw)
            }
        }
        taskProperties.firstOrNull { it.type == TaskPropertyType.Checklist }?.let { property ->
            pending.payload.checklistItems.forEach { text -> viewModel.addChecklistItem(created, property, text) }
        }
        pendingCreation = null
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(taskBackground()),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 1440.dp)
                .align(Alignment.TopCenter),
        ) {
            TaskHeader(
                board = selectedBoard,
                currentView = currentView,
                query = query,
                onQueryChange = { query = it },
                activeAction = activeRailAction,
                onActionToggle = { action -> activeRailAction = if (activeRailAction == action) null else action },
                feedGridMode = feedGridMode,
                onToggleFeedMode = { feedGridMode = !feedGridMode },
                onViewChange = { next ->
                    if (next == TaskWorkspaceView.Calendar) {
                        viewModel.go(Destination.Calendar)
                    } else {
                        viewModel.patchSettings { it.copy(taskViewMode = next.key) }
                    }
                },
                modifier = Modifier.padding(start = 18.dp, top = 8.dp, end = 18.dp),
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp),
            ) {
                if (currentView == TaskWorkspaceView.Board) {
                    TaskKanbanBoard(
                        columns = columns,
                        tasks = filteredTasks,
                        checklistItems = taskChecklistItems,
                        cardCounts = taskCardCounts,
                        engine = kanbanEngine,
                        compact = state.settings.taskCompactLayout,
                        onTaskClick = { editingTask = it },
                        onAddTask = { column ->
                            createSeedColumn = column
                            creatingTask = true
                        },
                        onMoveTask = { taskId, columnId, index -> viewModel.moveTaskToColumnAtIndex(taskId, columnId, index) },
                        onRenameColumn = { column, name -> viewModel.renameTaskColumn(column, name) },
                        onMoveColumn = { column, delta -> viewModel.moveTaskColumn(column, delta) },
                        onDeleteColumn = { column -> viewModel.deleteTaskColumn(column) },
                        onCreateColumn = { name -> viewModel.createTaskColumn(selectedBoardId, name) },
                        onInteractionStatus = { interactionStatus = it },
                    )
                } else if (currentView == TaskWorkspaceView.Table) {
                    TaskDatabaseTable(
                        tasks = filteredTasks,
                        columns = columns,
                        properties = taskProperties,
                        propertyValues = taskPropertyValues,
                        checklistItems = taskChecklistItems,
                        filesByTask = taskFilesByTask,
                        tags = state.tags,
                        viewModel = viewModel,
                        onPickAttachment = onPickTaskAttachment,
                        onTaskClick = { editingTask = it },
                        onNewTask = {
                            createSeedColumn = null
                            creatingTask = true
                        },
                        modifier = Modifier.fillMaxSize().padding(top = 14.dp, bottom = 112.dp),
                        swipeStartAction = swipeStartAction,
                        swipeEndAction = swipeEndAction,
                        onSwipeAction = runSwipeAction,
                    )
                } else {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(top = 14.dp, bottom = 140.dp),
                    ) {
                        when (currentView) {
                            TaskWorkspaceView.Board -> Unit
                            TaskWorkspaceView.Table -> Unit
                            TaskWorkspaceView.Matrix -> TaskMatrixView(
                                columns = columns,
                                tasks = filteredTasks,
                                onTaskClick = { editingTask = it },
                            )
                            TaskWorkspaceView.List -> BoxWithConstraints {
                                if (maxWidth < 720.dp) {
                                    CompactGroupedTaskTable(
                                        tasks = filteredTasks,
                                        columns = columns,
                                        properties = taskProperties,
                                        propertyValues = taskPropertyValues,
                                        checklistItems = taskChecklistItems,
                                        viewModel = viewModel,
                                        onPropertyClick = { task, property -> editingTask = task },
                                        onTaskClick = { editingTask = it },
                                        onNewTask = { column ->
                                            createSeedColumn = column
                                            creatingTask = true
                                        },
                                        swipeStartAction = swipeStartAction,
                                        swipeEndAction = swipeEndAction,
                                        onSwipeAction = runSwipeAction,
                                    )
                                } else {
                                    TaskListView(
                                        filteredTasks, columns, taskProperties, taskPropertyValues, taskChecklistItems,
                                        onTaskClick = { editingTask = it },
                                        swipeStartAction = swipeStartAction,
                                        swipeEndAction = swipeEndAction,
                                        onSwipeAction = runSwipeAction,
                                    )
                                }
                            }
                            TaskWorkspaceView.Timeline -> TaskTimelineView(filteredTasks, columns, onTaskClick = { editingTask = it })
                            TaskWorkspaceView.Feed -> if (feedGridMode) {
                                TaskGalleryView(
                                    filteredTasks, taskChecklistItems,
                                    onTaskClick = { editingTask = it },
                                    swipeStartAction = swipeStartAction,
                                    swipeEndAction = swipeEndAction,
                                    onSwipeAction = runSwipeAction,
                                )
                            } else {
                                TaskFeedView(
                                    filteredTasks, taskProperties, taskPropertyValues, taskChecklistItems,
                                    onTaskClick = { editingTask = it },
                                    swipeStartAction = swipeStartAction,
                                    swipeEndAction = swipeEndAction,
                                    onSwipeAction = runSwipeAction,
                                )
                            }
                            TaskWorkspaceView.Calendar -> Unit
                            TaskWorkspaceView.Chart -> TaskChartView(filteredTasks)
                        }
                    }
                }
            }
        }

        // The rail is hidden entirely while Feed shows its list display mode.
        if (currentView != TaskWorkspaceView.Feed || feedGridMode) {
        TaskRailPanel(
            action = activeRailAction,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 190.dp, end = 18.dp, start = 18.dp)
                .fillMaxWidth(0.78f)
                .widthIn(max = 680.dp),
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
        }

        editingTask?.let { task ->
            AdaptiveTaskPage(
                task = state.tasks.firstOrNull { it.id == task.id } ?: task,
                properties = taskProperties,
                propertyValues = taskPropertyValues,
                checklistItems = taskChecklistItems,
                columns = columns,
                tags = state.tags,
                boardName = selectedBoard?.name ?: "Default board",
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

        if (creatingTask && columns.isNotEmpty()) {
            AdaptiveTaskPage(
                task = null,
                properties = taskProperties,
                propertyValues = taskPropertyValues,
                checklistItems = taskChecklistItems,
                columns = columns,
                tags = state.tags,
                boardName = selectedBoard?.name ?: "Default board",
                taskObjectId = null,
                comments = state.workspaceComments,
                files = state.workspaceFiles,
                viewModel = viewModel,
                onPickAttachment = {},
                onDelete = {},
                onDismiss = {
                    creatingTask = false
                    createSeedColumn = null
                },
                initialColumn = createSeedColumn,
                onCreate = { payload ->
                    pendingCreation = PendingTaskCreation(boardTasks.mapTo(mutableSetOf()) { it.id }, payload)
                    viewModel.addTaskToColumn(payload.title, payload.column)
                    creatingTask = false
                    createSeedColumn = null
                },
            )
        }

        SnackbarHost(
            hostState = swipeSnackbar,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 96.dp).zIndex(30f),
        )
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
    feedGridMode: Boolean,
    onToggleFeedMode: () -> Unit,
    onViewChange: (TaskWorkspaceView) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = board?.name ?: "Default board",
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Black,
                fontSize = 30.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                text = "Capture, organize, and ship great work ✨",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            leadingIcon = { Icon(Icons.Outlined.Search, null) },
            trailingIcon = {
                // Shared adaptive header hook: Feed toggles list/grid; other views open the rail.
                when (currentView) {
                    TaskWorkspaceView.Feed -> IconButton(onClick = onToggleFeedMode) {
                        Icon(
                            imageVector = if (feedGridMode) Icons.Outlined.ViewAgenda else Icons.Outlined.GridView,
                            contentDescription = if (feedGridMode) "Switch to list view" else "Switch to grid view",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    else -> IconButton(onClick = { onActionToggle(TaskRailAction.Filter) }) {
                        Icon(Icons.Outlined.Tune, "Task controls", tint = if (activeAction != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
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
    cardCounts: Map<Long, TaskCardCounts>,
    engine: TaskKanbanEngine,
    compact: Boolean,
    onTaskClick: (TaskItem) -> Unit,
    onAddTask: (TaskColumnItem) -> Unit,
    onMoveTask: (Long, Long, Int) -> Unit,
    onRenameColumn: (TaskColumnItem, String) -> Unit,
    onMoveColumn: (TaskColumnItem, Int) -> Unit,
    onDeleteColumn: (TaskColumnItem) -> Unit,
    onCreateColumn: (String) -> Unit,
    onInteractionStatus: (TaskInteractionStatus) -> Unit,
) {
    TaskPointerKanbanBoard(
        columns = columns,
        tasks = tasks,
        checklistItems = checklistItems,
        cardCounts = cardCounts,
        engine = engine,
        compact = compact,
        onTaskClick = onTaskClick,
        onAddTask = onAddTask,
        onMoveTask = onMoveTask,
        onRenameColumn = onRenameColumn,
        onMoveColumn = onMoveColumn,
        onDeleteColumn = onDeleteColumn,
        onCreateColumn = onCreateColumn,
        onInteractionStatus = onInteractionStatus,
    )
}

@Composable
private fun TaskPointerKanbanBoard(
    columns: List<TaskColumnItem>,
    tasks: List<TaskItem>,
    checklistItems: List<TaskChecklistItem>,
    cardCounts: Map<Long, TaskCardCounts>,
    engine: TaskKanbanEngine,
    compact: Boolean,
    onTaskClick: (TaskItem) -> Unit,
    onAddTask: (TaskColumnItem) -> Unit,
    onMoveTask: (Long, Long, Int) -> Unit,
    onRenameColumn: (TaskColumnItem, String) -> Unit,
    onMoveColumn: (TaskColumnItem, Int) -> Unit,
    onDeleteColumn: (TaskColumnItem) -> Unit,
    onCreateColumn: (String) -> Unit,
    onInteractionStatus: (TaskInteractionStatus) -> Unit,
) {
    var showNewColumnDialog by remember { mutableStateOf(false) }
    var newColumnName by remember { mutableStateOf("") }
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

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
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
        val boardViewportHeight = maxHeight
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
                    cardCounts = cardCounts,
                    compact = compact,
                    drag = dragState,
                    onTaskClick = onTaskClick,
                    onAddTask = { onAddTask(column) },
                    onMoveColumn = { delta -> onMoveColumn(column, delta) },
                    onRenameColumn = { name -> onRenameColumn(column, name) },
                    onDeleteColumn = { onDeleteColumn(column) },
                    onDragMove = {},
                    onDrop = {},
                    enableCardDrag = false,
                    modifier = Modifier.height(boardViewportHeight),
                )
            }
            item {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.16f),
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier.width(280.dp).height(72.dp).clickable { showNewColumnDialog = true },
                ) {
                    Row(
                        Modifier.padding(horizontal = 18.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(Icons.Outlined.Add, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                        Text("New column", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }

        dragState.draggingTask?.let { task ->
            val position = dragState.pointer - dragState.grabOffset
            TaskKanbanCard(
                task = task,
                statusName = columns.firstOrNull { it.id == task.taskColumnId }?.name ?: task.status.label(),
                checklistItems = checklistItems,
                counts = cardCounts[task.id] ?: TaskCardCounts(),
                compact = compact,
                dragging = true,
                modifier = Modifier
                    .width(284.dp)
                    .offset { IntOffset(position.x.roundToInt(), position.y.roundToInt()) }
                    .zIndex(20f)
                    .dragLift(lifted = true, cornerRadius = 10.dp),
                onClick = {},
                onDragStart = { _, _ -> },
                onDragMove = {},
                onDrop = {},
            )
        }
    }
    if (showNewColumnDialog) {
        NorfoldDialog(
            onDismissRequest = { showNewColumnDialog = false },
            title = { Text("New column") },
            text = {
                OutlinedTextField(
                    value = newColumnName,
                    onValueChange = { newColumnName = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("Column name") },
                    colors = taskTextFieldColors(),
                )
            },
            dismissButton = { TextButton(onClick = { showNewColumnDialog = false }) { Text("Cancel") } },
            confirmButton = {
                Button(
                    enabled = newColumnName.isNotBlank(),
                    onClick = {
                        onCreateColumn(newColumnName.trim())
                        newColumnName = ""
                        showNewColumnDialog = false
                    },
                ) { Text("Create") }
            },
        )
    }
}

@Composable
private fun TaskKanbanColumn(
    column: TaskColumnItem,
    tasks: List<TaskItem>,
    checklistItems: List<TaskChecklistItem>,
    cardCounts: Map<Long, TaskCardCounts>,
    compact: Boolean,
    drag: BoardDragState,
    onTaskClick: (TaskItem) -> Unit,
    onAddTask: () -> Unit,
    onMoveColumn: (Int) -> Unit,
    onRenameColumn: (String) -> Unit,
    onDeleteColumn: () -> Unit,
    onDragMove: (Offset) -> Unit,
    onDrop: () -> Unit,
    enableCardDrag: Boolean,
    modifier: Modifier = Modifier,
) {
    var menuOpen by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var renameValue by remember(column.id, column.name) { mutableStateOf(column.name) }
    val columnColor = column.resolvedColor()
    val target = drag.currentTarget()
    Surface(
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.74f),
        shape = RoundedCornerShape(12.dp),
        tonalElevation = 2.dp,
        modifier = modifier
            .width(304.dp)
            .onGloballyPositioned { drag.columnBounds[column.id] = TaskColumnBounds(column.id, drag.toBoardRect(it.boundsInRoot())) },
    ) {
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .verticalScroll(rememberScrollState())
                .padding(12.dp),
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
                    Box(Modifier.size(12.dp).clip(CircleShape).background(columnColor))
                    Spacer(Modifier.width(8.dp))
                    Text(column.name, modifier = Modifier.weight(1f), fontWeight = FontWeight.Black, fontSize = 18.sp)
                    StatusCount(tasks.size)
                    Box {
                        IconButton(onClick = { menuOpen = true }) { Icon(Icons.Outlined.MoreVert, null) }
                        NorfoldTaskMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
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
            // While dragging, the source card leaves the list entirely (it floats as a clone) and a
            // dashed DropSlot placeholder occupies the live target index; TaskDropTarget.index is
            // already computed over the dragged-card-excluded list, so the indices line up 1:1 with
            // the onMoveTask commit. Neighbors slide around the slot via animatePlacement().
            val draggingId = drag.draggingTask?.id
            val visibleTasks = if (draggingId == null) tasks else tasks.filter { it.id != draggingId }
            val slotIndex = when {
                draggingId == null -> -1
                target?.columnId == column.id -> target.index.coerceIn(0, visibleTasks.size)
                target == null -> tasks.indexOfFirst { it.id == draggingId }
                else -> -1
            }
            val density = LocalDensity.current
            val slotHeight = draggingId
                ?.let { drag.cardBounds[it]?.bounds }
                ?.let { with(density) { it.height.toDp() } }
                ?: if (compact) 86.dp else 116.dp
            visibleTasks.forEachIndexed { index, task ->
                if (index == slotIndex) {
                    DropSlot(modifier = Modifier.fillMaxWidth(), height = slotHeight)
                }
                key(task.id) {
                    TaskKanbanCard(
                        task = task,
                        statusName = column.name,
                        checklistItems = checklistItems,
                        counts = cardCounts[task.id] ?: TaskCardCounts(),
                        compact = compact,
                        onClick = { onTaskClick(task) },
                        modifier = Modifier.fillMaxWidth().animatePlacement(),
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
            if (slotIndex >= visibleTasks.size && slotIndex >= 0) {
                DropSlot(modifier = Modifier.fillMaxWidth(), height = slotHeight)
            }
            Surface(
                modifier = Modifier.fillMaxWidth().clickable { onAddTask() },
                shape = RoundedCornerShape(9.dp),
                color = Color.Transparent,
                border = BorderStroke(1.dp, columnColor.copy(alpha = 0.55f)),
            ) {
                Row(Modifier.padding(vertical = 9.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Add, null, Modifier.size(17.dp), tint = columnColor)
                    Spacer(Modifier.width(5.dp))
                    Text("Add task", color = columnColor, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
private fun TaskKanbanCard(
    task: TaskItem,
    statusName: String,
    checklistItems: List<TaskChecklistItem>,
    counts: TaskCardCounts = TaskCardCounts(),
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
            .clickable(enabled = !dragging, onClick = onClick)
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
            .animateContentSize(),
        colors = CardDefaults.elevatedCardColors(
            containerColor = if (dragging) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
        ),
        shape = RoundedCornerShape(10.dp),
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
                TinyMeta(statusName)
                PriorityPill(task.priority)
            }
            val taskChecks = checklistItems.filter { it.taskId == task.id }.sortedBy { it.sortOrder }
            task.progressFromChecklist(taskChecks)?.let { progress ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Outlined.CheckCircle, null, Modifier.size(15.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("${taskChecks.count { it.checked }} / ${taskChecks.size}", fontWeight = FontWeight.SemiBold, fontSize = 11.sp)
                    LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.weight(1f).height(4.dp))
                }
            }
            taskChecks.take(if (compact) 1 else 3).forEach { item ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(if (item.checked) Icons.Outlined.CheckBox else Icons.Outlined.RadioButtonUnchecked, null, Modifier.size(14.dp), tint = if (item.checked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(item.text, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 11.sp, textDecoration = if (item.checked) TextDecoration.LineThrough else null)
                }
            }
            if (task.labels.isNotBlank()) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                    task.labels.split(',').map(String::trim).filter(String::isNotBlank).forEach { TinyMeta(it) }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (task.startAt != null || task.dueAt != null) TinyMeta(formatTaskTimeRange(task.startAt, task.dueAt, task.allDay))
                Spacer(Modifier.weight(1f))
                if (counts.files > 0) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                        Icon(Icons.Outlined.AttachFile, null, Modifier.size(13.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(counts.files.toString(), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (counts.comments > 0) {
                    Icon(Icons.Outlined.ChatBubbleOutline, null, Modifier.size(13.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(counts.comments.toString(), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (counts.links > 0) {
                    Icon(Icons.Outlined.Link, null, Modifier.size(13.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(counts.links.toString(), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (task.assignee.isNotBlank()) AssigneeAvatar(task.assignee)
            }
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
    filesByTask: Map<Long, List<WorkspaceFileItem>>,
    tags: List<Tag>,
    viewModel: NotesViewModel,
    onPickAttachment: (TaskItem) -> Unit,
    onTaskClick: (TaskItem) -> Unit,
    onNewTask: () -> Unit,
    modifier: Modifier = Modifier,
    swipeStartAction: TaskGestureAction = TaskGestureAction.None,
    swipeEndAction: TaskGestureAction = TaskGestureAction.None,
    onSwipeAction: (TaskItem, TaskGestureAction) -> Unit = { _, _ -> },
) {
    var editor by remember { mutableStateOf<Pair<TaskItem, TaskPropertyDefinition>?>(null) }
    var editorAnchor by remember { mutableStateOf<Rect?>(null) }
    var selectedCell by remember { mutableStateOf<Pair<Long, TaskTableColumn>?>(null) }
    var showPropertyPicker by remember { mutableStateOf(false) }
    val horizontal = rememberScrollState()
    val border = MaterialTheme.colorScheme.outlineVariant
    val taskIds = remember(tasks) { tasks.mapTo(mutableSetOf()) { it.id } }
    val visibleColumns = remember(tasks, properties, propertyValues, checklistItems, filesByTask) {
        TaskTableColumn.entries.filter { column ->
            if (column == TaskTableColumn.Name) return@filter true
            val property = properties.propertyFor(column)
            val explicit = property?.hiddenWhenEmpty == false
            val hasValue = property != null && propertyValues.any { value ->
                value.propertyId == property.id && value.taskId in taskIds && value.valueJson.isNotBlank()
            }
            val hasChecklist = column == TaskTableColumn.Checklist && property != null &&
                checklistItems.any { it.propertyId == property.id && it.taskId in taskIds }
            val hasFiles = column == TaskTableColumn.Files && tasks.any { task ->
                task.attachmentName != null || filesByTask[task.id].orEmpty().isNotEmpty()
            }
            explicit || hasValue || hasChecklist || hasFiles
        }
    }

    BoxWithConstraints(modifier) {
        val compact = maxWidth < 720.dp
        val indexWidth = if (compact) 42.dp else 72.dp
        val newPropertyWidth = if (compact) 128.dp else 164.dp
        val tableWidth = visibleColumns.fold(indexWidth + newPropertyWidth) { total, column -> total + column.width(compact) }
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(8.dp),
            border = BorderStroke(1.dp, border),
        ) {
            Box(Modifier.fillMaxSize().horizontalScroll(horizontal)) {
            LazyColumn(
                modifier = Modifier.width(tableWidth).fillMaxHeight(),
                contentPadding = PaddingValues(bottom = 20.dp),
            ) {
                stickyHeader {
                    Row(Modifier.background(MaterialTheme.colorScheme.surface)) {
                        Box(Modifier.width(indexWidth).height(48.dp).border(0.5.dp, border), contentAlignment = Alignment.Center) {
                            Text("#", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        visibleColumns.forEach { column ->
                            TaskTableHeaderCell(column, compact)
                        }
                        Surface(
                            modifier = Modifier.width(newPropertyWidth).height(48.dp).border(0.5.dp, border),
                            color = MaterialTheme.colorScheme.surface,
                            onClick = { showPropertyPicker = true },
                        ) {
                            Row(
                                Modifier.padding(horizontal = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(7.dp),
                            ) {
                                Icon(Icons.Outlined.Add, "Add property", Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text("New property", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
                itemsIndexed(tasks, key = { _, task -> task.id }) { index, task ->
                    val taskChecks = checklistItems.filter { it.taskId == task.id }.sortedBy { it.sortOrder }
                    val taskFiles = filesByTask[task.id].orEmpty()
                    val rowHeight = taskTableRowHeight(task, taskChecks, taskFiles)
                    TaskSwipeRow(
                        task = task,
                        startAction = swipeStartAction,
                        endAction = swipeEndAction,
                        onAction = onSwipeAction,
                        modifier = Modifier.animateItem(),
                    ) {
                    Row {
                        Box(Modifier.width(indexWidth).height(rowHeight).border(0.5.dp, border), contentAlignment = Alignment.Center) {
                            Text((index + 1).toString(), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        visibleColumns.forEach { column ->
                            val property = properties.propertyFor(column)
                            val alternateProperty = properties.alternatePropertyFor(column)
                            TaskTableDataCell(
                                task = task,
                                column = column,
                                compact = compact,
                                rowHeight = rowHeight,
                                selected = selectedCell == (task.id to column),
                                boardColumns = columns,
                                property = property,
                                alternateProperty = alternateProperty,
                                propertyValues = propertyValues,
                                checklistItems = taskChecks,
                                files = taskFiles,
                                onSelect = { bounds ->
                                    selectedCell = task.id to column
                                    editorAnchor = bounds
                                    property?.let { editor = task to it } ?: onTaskClick(task)
                                },
                                onAlternateSelect = { bounds ->
                                    selectedCell = task.id to column
                                    editorAnchor = bounds
                                    alternateProperty?.let { editor = task to it }
                                },
                                onOpenTask = { onTaskClick(task) },
                                onReorder = { delta ->
                                    val sameColumn = tasks.filter { it.taskColumnId == task.taskColumnId }
                                    val current = sameColumn.indexOfFirst { it.id == task.id }
                                    if (current >= 0) {
                                        val target = (current + delta).coerceIn(0, sameColumn.lastIndex)
                                        task.taskColumnId?.let { viewModel.moveTaskToColumnAtIndex(task.id, it, target) }
                                    }
                                },
                            )
                        }
                        Spacer(Modifier.width(newPropertyWidth).height(rowHeight).border(0.5.dp, border))
                    }
                    }
                }
                item {
                    Surface(
                        modifier = Modifier.width(tableWidth).height(48.dp).border(1.dp, border),
                        color = MaterialTheme.colorScheme.surface,
                        onClick = onNewTask,
                    ) {
                        Row(Modifier.padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Outlined.Add, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                            Text("New task", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                            Text("COUNT ${tasks.size}", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
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
            tags = tags,
            anchor = editorAnchor,
            viewModel = viewModel,
            onPickAttachment = { onPickAttachment(task) },
            onDismiss = { editor = null },
        )
    }
    if (showPropertyPicker) {
        MultiPropertyPickerDialog(
            activeTypes = properties.mapTo(mutableSetOf()) { it.type },
            onDismiss = { showPropertyPicker = false },
            onAdd = { selected ->
                selected.forEach { type ->
                    viewModel.createTaskProperty(columns.firstOrNull()?.boardId ?: 1L, type.defaultPropertyName(), type)
                }
                showPropertyPicker = false
            },
        )
    }
}

@Composable
private fun CompactTaskDraftField(label: String, value: String, onValueChange: (String) -> Unit, modifier: Modifier = Modifier) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        singleLine = true,
        label = { Text(label) },
        colors = taskTextFieldColors(),
    )
}

@Composable
private fun MultiPropertyPickerDialog(
    activeTypes: Set<TaskPropertyType>,
    onDismiss: () -> Unit,
    onAdd: (Set<TaskPropertyType>) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf(emptySet<TaskPropertyType>()) }
    var confirming by remember { mutableStateOf(false) }
    val available = remember(activeTypes, query) {
        TaskPropertyType.entries
            .filterNot { it == TaskPropertyType.Name || it in activeTypes }
            .filter { query.isBlank() || it.defaultPropertyName().contains(query, ignoreCase = true) }
    }
    NorfoldContentDialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier.fillMaxWidth().widthIn(max = 520.dp).heightIn(max = 640.dp),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            shadowElevation = 16.dp,
        ) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(if (confirming) "Confirm properties" else "Add properties", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Black)
                if (!confirming) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        leadingIcon = { Icon(Icons.Outlined.Search, null) },
                        placeholder = { Text("Search property types") },
                        colors = taskTextFieldColors(),
                    )
                    LazyColumn(Modifier.weight(1f, fill = false).heightIn(max = 430.dp)) {
                        items(available, key = { it.name }) { type ->
                            val checked = type in selected
                            Row(
                                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable {
                                    selected = if (checked) selected - type else selected + type
                                }.padding(horizontal = 8.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                Checkbox(checked = checked, onCheckedChange = { next -> selected = if (next) selected + type else selected - type })
                                Icon(type.icon(), null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(type.defaultPropertyName(), modifier = Modifier.weight(1f), fontWeight = FontWeight.Medium)
                            }
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End)) {
                        TextButton(onClick = onDismiss) { Text("Cancel") }
                        Button(onClick = { confirming = true }, enabled = selected.isNotEmpty()) { Text("Continue") }
                    }
                } else {
                    LazyColumn(Modifier.heightIn(max = 430.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(selected.toList(), key = { it.name }) { type ->
                            Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.36f)) {
                                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Icon(type.icon(), null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Spacer(Modifier.width(10.dp))
                                    Text(type.defaultPropertyName(), Modifier.weight(1f), fontWeight = FontWeight.Medium)
                                    IconButton(onClick = { selected = selected - type }) { Icon(Icons.Outlined.Close, "Remove") }
                                }
                            }
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End)) {
                        TextButton(onClick = onDismiss) { Text("Cancel") }
                        Button(onClick = { onAdd(selected) }, enabled = selected.isNotEmpty()) { Text("Add") }
                    }
                }
            }
        }
    }
}

private fun parseTaskDraftDate(value: String): Long? = runCatching {
    SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { isLenient = false }.parse(value.trim())?.time
}.getOrNull()

@Composable
private fun TaskTableHeaderCell(column: TaskTableColumn, compact: Boolean) {
    val icon = when (column) {
        TaskTableColumn.Name -> Icons.Outlined.Title
        TaskTableColumn.StatusTags -> Icons.Outlined.RadioButtonUnchecked
        TaskTableColumn.Checklist -> Icons.Outlined.CheckBox
        TaskTableColumn.DueDate -> Icons.Outlined.Event
        TaskTableColumn.Notes -> Icons.Outlined.TableRows
        TaskTableColumn.Files -> Icons.Outlined.AttachFile
        TaskTableColumn.PriorityAssignee -> Icons.Outlined.Person
    }
    Row(
        modifier = Modifier
            .width(column.width(compact))
            .height(48.dp)
            .background(MaterialTheme.colorScheme.surface)
            .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Icon(icon, null, Modifier.size(15.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(column.label, fontSize = if (compact) 9.sp else 12.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
    }
}

@Composable
private fun TaskTableDataCell(
    task: TaskItem,
    column: TaskTableColumn,
    compact: Boolean,
    rowHeight: Dp,
    selected: Boolean,
    boardColumns: List<TaskColumnItem>,
    property: TaskPropertyDefinition?,
    alternateProperty: TaskPropertyDefinition?,
    propertyValues: List<TaskPropertyValue>,
    checklistItems: List<TaskChecklistItem>,
    files: List<WorkspaceFileItem>,
    onSelect: (Rect) -> Unit,
    onAlternateSelect: (Rect) -> Unit,
    onOpenTask: () -> Unit,
    onReorder: (Int) -> Unit,
) {
    val raw = property?.let { definition ->
        propertyValues.firstOrNull { it.taskId == task.id && it.propertyId == definition.id }?.valueJson.orEmpty()
    }.orEmpty()
    val cellBorder = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
    val cellWidth = column.width(compact)
    var bounds by remember(task.id, column) { mutableStateOf(Rect.Zero) }
    Column(
        modifier = Modifier
            .width(cellWidth)
            .height(rowHeight)
            .background(MaterialTheme.colorScheme.surface)
            .border(if (selected) 2.dp else 0.5.dp, cellBorder)
            .onGloballyPositioned { bounds = it.boundsInRoot() }
            .clickable { onSelect(bounds) }
            .padding(horizontal = if (compact) 6.dp else 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        when (column) {
            TaskTableColumn.Name -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                VerticalReorderHandle(rowHeight = rowHeight, onStep = onReorder, size = if (compact) 18.dp else 28.dp)
                if (!compact) Icon(Icons.Outlined.Title, null, Modifier.size(15.dp), tint = MaterialTheme.colorScheme.primary)
                Text(task.title.ifBlank { "Untitled task" }, Modifier.weight(1f), maxLines = 3, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold, fontSize = if (compact) 9.sp else 12.sp)
                IconButton(onClick = onOpenTask, modifier = Modifier.size(if (compact) 20.dp else 28.dp)) {
                    Icon(Icons.Outlined.MoreVert, "Open task", Modifier.size(if (compact) 13.dp else 16.dp))
                }
            }
            TaskTableColumn.StatusTags -> {
                val status = boardColumns.firstOrNull { it.id == task.taskColumnId }
                Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(7.dp).clip(CircleShape).background(status?.resolvedColor() ?: MaterialTheme.colorScheme.primary))
                    TinyMeta(status?.name ?: task.status.label())
                }
                if (task.labels.isNotBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        task.labels.split(',').map(String::trim).filter(String::isNotBlank).forEach { label ->
                            Surface(
                                color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.72f),
                                shape = RoundedCornerShape(6.dp),
                                modifier = Modifier.clickable { onAlternateSelect(bounds) },
                            ) { Text("#$label", Modifier.padding(horizontal = 6.dp, vertical = 3.dp), fontSize = 9.sp, color = MaterialTheme.colorScheme.onSecondaryContainer) }
                        }
                    }
                } else if (alternateProperty != null) {
                    Text(
                        "+ tag",
                        modifier = Modifier.padding(top = 6.dp).clickable { onAlternateSelect(bounds) },
                        fontSize = 9.sp,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            TaskTableColumn.Checklist -> {
                val progress = checklistItems.progressPercent() ?: 0
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.weight(1f).height(4.dp))
                    Text("$progress%", fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                }
                checklistItems.take(4).forEach { item ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        Checkbox(checked = item.checked, onCheckedChange = null, modifier = Modifier.size(22.dp))
                        Text(item.text, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            TaskTableColumn.DueDate -> {
                val range = TaskDateRangeCodec.decode(raw, task.dueAt)
                Text(
                    when {
                        range.startAt == null && range.endAt == null -> "Add date"
                        range.startAt == range.endAt -> formatTaskDue(range.endAt ?: range.startAt!!)
                        else -> "${formatTaskDue(range.startAt!!)}\n→ ${formatTaskDue(range.endAt!!)}"
                    },
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TaskTableColumn.Notes -> Text(raw.ifBlank { task.description }.ifBlank { "Type your notes…" }, maxLines = 7, overflow = TextOverflow.Ellipsis, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TaskTableColumn.Files -> {
                val names = buildList {
                    addAll(files.map { it.displayName })
                    task.attachmentName?.takeIf { name -> none { it == name } }?.let(::add)
                    if (isEmpty() && raw.isNotBlank()) addAll(raw.lineSequence().filter(String::isNotBlank).toList())
                }
                if (names.isEmpty()) Text("+ Add file", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                names.take(4).forEach { name ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        Icon(Icons.Outlined.AttachFile, null, Modifier.size(13.dp), tint = MaterialTheme.colorScheme.primary)
                        Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 10.sp)
                    }
                }
            }
            TaskTableColumn.PriorityAssignee -> {
                PriorityPill(task.priority)
                Spacer(Modifier.height(6.dp))
                Row(
                    modifier = Modifier.clickable { onAlternateSelect(bounds) },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    AssigneeAvatar(task.assignee.ifBlank { "@owner" })
                    Text(task.assignee.ifBlank { "@owner" }, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

private fun TaskTableColumn.width(compact: Boolean): Dp = if (compact) compactWidth else width

private fun taskTableRowHeight(task: TaskItem, checklist: List<TaskChecklistItem>, files: List<WorkspaceFileItem>): Dp {
    val noteLines = task.description.lineSequence().count().coerceAtMost(6)
    val richRows = maxOf(noteLines, checklist.size.coerceAtMost(4), files.size.coerceAtMost(4))
    return (80 + richRows * 18).coerceIn(80, 188).dp
}

private fun List<TaskPropertyDefinition>.propertyFor(column: TaskTableColumn): TaskPropertyDefinition? = when (column) {
    TaskTableColumn.Name -> firstOrNull { it.type == TaskPropertyType.Name }
    TaskTableColumn.StatusTags -> firstOrNull { it.type == TaskPropertyType.Status }
        ?: firstOrNull { it.type == TaskPropertyType.Labels || it.type == TaskPropertyType.Multiselect }
    TaskTableColumn.Checklist -> firstOrNull { it.type == TaskPropertyType.Checklist }
    TaskTableColumn.DueDate -> firstOrNull { it.type == TaskPropertyType.DueDate || it.type == TaskPropertyType.Date }
    TaskTableColumn.Notes -> firstOrNull { it.type == TaskPropertyType.Text }
    TaskTableColumn.Files -> firstOrNull { it.type == TaskPropertyType.FilesMedia }
    TaskTableColumn.PriorityAssignee -> firstOrNull { it.type == TaskPropertyType.Priority }
        ?: firstOrNull { it.type == TaskPropertyType.Assignee || it.type == TaskPropertyType.Person }
}

private fun List<TaskPropertyDefinition>.alternatePropertyFor(column: TaskTableColumn): TaskPropertyDefinition? = when (column) {
    TaskTableColumn.StatusTags -> firstOrNull { it.type == TaskPropertyType.Labels || it.type == TaskPropertyType.Multiselect }
    TaskTableColumn.PriorityAssignee -> firstOrNull { it.type == TaskPropertyType.Assignee || it.type == TaskPropertyType.Person }
    else -> null
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
    onNewTask: (TaskColumnItem) -> Unit,
    swipeStartAction: TaskGestureAction = TaskGestureAction.None,
    swipeEndAction: TaskGestureAction = TaskGestureAction.None,
    onSwipeAction: (TaskItem, TaskGestureAction) -> Unit = { _, _ -> },
) {
    val collapsed = remember { mutableStateMapOf<Long, Boolean>() }
    var expandedTaskId by remember { mutableStateOf<Long?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        columns.forEach { column ->
            val columnTasks = tasks.filter { it.taskColumnId == column.id }.sortedBy { it.sortOrder }
            Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surface, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Box(Modifier.size(14.dp).clip(CircleShape).background(column.resolvedColor()))
                        Text(column.name, fontWeight = FontWeight.Black, fontSize = 18.sp, modifier = Modifier.weight(1f))
                        StatusCount(columnTasks.size)
                        IconButton(onClick = { collapsed[column.id] = !(collapsed[column.id] ?: false) }) {
                            Icon(if (collapsed[column.id] == true) Icons.Outlined.Add else Icons.Outlined.TableRows, null)
                        }
                    }
                    if (collapsed[column.id] != true) {
                        columnTasks.forEachIndexed { index, task ->
                            TaskSwipeRow(task = task, startAction = swipeStartAction, endAction = swipeEndAction, onAction = onSwipeAction) {
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
                        }
                        Surface(
                            modifier = Modifier.fillMaxWidth().clickable { onNewTask(column) },
                            shape = RoundedCornerShape(9.dp),
                            color = Color.Transparent,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        ) {
                            Row(Modifier.padding(vertical = 9.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Outlined.Add, null, Modifier.size(17.dp), tint = MaterialTheme.colorScheme.primary)
                                Spacer(Modifier.width(5.dp))
                                Text("Add task", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                            }
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
    tags: List<Tag>,
    anchor: Rect?,
    viewModel: NotesViewModel,
    onPickAttachment: () -> Unit,
    onDismiss: () -> Unit,
) {
    var newStatus by remember { mutableStateOf("") }
    val compact = LocalConfiguration.current.screenWidthDp < 720
    val editorContent: @Composable () -> Unit = {
        Column(
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxWidth().padding(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(property.type.icon(), null, tint = MaterialTheme.colorScheme.primary)
                Text(property.name, fontWeight = FontWeight.Black, modifier = Modifier.weight(1f))
                IconButton(onClick = onDismiss) { Icon(Icons.Outlined.Close, "Close") }
            }
            when (property.type) {
                TaskPropertyType.Status -> {
                    columns.forEach { column ->
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = if (column.id == task.taskColumnId) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.36f),
                            modifier = Modifier.fillMaxWidth().clickable { viewModel.moveTaskToColumn(task, column); onDismiss() },
                        ) {
                            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Box(Modifier.size(10.dp).clip(CircleShape).background(column.resolvedColor()))
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
                TaskPropertyType.Labels, TaskPropertyType.Multiselect -> TaskLabelsPropertyEditor(task, property, value?.valueJson.orEmpty(), tags, viewModel)
                else -> TextPropertyEditor(task, property, property.displayValue(task, value?.valueJson.orEmpty()), viewModel)
            }
            Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text("Done") }
        }
    }
    if (compact) {
        NorfoldBottomSheet(onDismissRequest = onDismiss) {
            Box(Modifier.fillMaxWidth().heightIn(max = 680.dp).verticalScroll(rememberScrollState())) { editorContent() }
            Spacer(Modifier.navigationBarsPadding())
        }
    } else {
        val density = LocalDensity.current
        val configuration = LocalConfiguration.current
        val popupWidth = 380.dp
        val maxHeight = 620.dp
        val offset = with(density) {
            val screenWidth = configuration.screenWidthDp.dp.roundToPx()
            val screenHeight = configuration.screenHeightDp.dp.roundToPx()
            val width = popupWidth.roundToPx()
            val height = maxHeight.roundToPx()
            IntOffset(
                x = (anchor?.left?.roundToInt() ?: (screenWidth - width) / 2).coerceIn(0, (screenWidth - width).coerceAtLeast(0)),
                y = (anchor?.bottom?.roundToInt() ?: 80).coerceIn(0, (screenHeight - height).coerceAtLeast(0)),
            )
        }
        Popup(alignment = Alignment.TopStart, offset = offset, onDismissRequest = onDismiss, properties = PopupProperties(focusable = true)) {
            Surface(
                modifier = Modifier.width(popupWidth).heightIn(max = maxHeight).verticalScroll(rememberScrollState()),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                shadowElevation = 12.dp,
            ) { editorContent() }
        }
    }
}

@Composable
private fun TaskLabelsPropertyEditor(
    task: TaskItem,
    property: TaskPropertyDefinition,
    raw: String,
    tags: List<Tag>,
    viewModel: NotesViewModel,
) {
    var query by remember(task.id, property.id) { mutableStateOf("") }
    val initial = raw.ifBlank { task.labels }
    var selected by remember(task.id, property.id, initial) {
        mutableStateOf(initial.split(',').map(::normalizeTaskTag).filter(String::isNotBlank).distinctBy(String::lowercase))
    }
    val boardTagScope = remember(task.taskBoardId) { "board:${task.taskBoardId}" }
    val boardTags = remember(tags, boardTagScope) {
        tags.filter { it.scope == boardTagScope }
    }
    val available = remember(boardTags, selected, query) {
        (boardTags.map(Tag::name) + selected)
            .map(::normalizeTaskTag)
            .filter(String::isNotBlank)
            .distinctBy(String::lowercase)
            .filter { query.isBlank() || it.contains(normalizeTaskTag(query), ignoreCase = true) }
    }
    fun persist(next: List<String>) {
        selected = next.distinctBy(String::lowercase)
        viewModel.setTaskPropertyValue(task, property, selected.joinToString(","))
    }

    OutlinedTextField(
        value = query,
        onValueChange = { query = it },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        leadingIcon = { Text("#", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Black) },
        placeholder = { Text("Search or create tag") },
        colors = taskTextFieldColors(),
    )
    if (selected.isNotEmpty()) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            selected.forEach { name ->
                AssistChip(
                    onClick = { persist(selected.filterNot { it.equals(name, ignoreCase = true) }) },
                    label = { Text("#$name") },
                    trailingIcon = { Icon(Icons.Outlined.Close, "Remove", Modifier.size(14.dp)) },
                )
            }
        }
    }
    available.take(12).forEach { name ->
        val tag = boardTags.firstOrNull { it.name.equals(name, ignoreCase = true) }
        val checked = selected.any { it.equals(name, ignoreCase = true) }
        Surface(
            modifier = Modifier.fillMaxWidth().clickable {
                persist(if (checked) selected.filterNot { it.equals(name, ignoreCase = true) } else selected + name)
            },
            color = if (checked) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
            shape = RoundedCornerShape(10.dp),
        ) {
            Row(Modifier.padding(horizontal = 12.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(tag?.let { Color(it.color) } ?: MaterialTheme.colorScheme.primary))
                Text("#$name", Modifier.weight(1f), fontWeight = FontWeight.Medium)
                if (checked) Icon(Icons.Outlined.CheckCircle, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
            }
        }
    }
    val candidate = normalizeTaskTag(query)
    if (candidate.isNotBlank() && available.none { it.equals(candidate, ignoreCase = true) }) {
        TextButton(
            onClick = {
                viewModel.addTaskTag(task.taskBoardId, candidate)
                persist(selected + candidate)
                query = ""
            },
        ) { Text("Create #$candidate") }
    }
}

private fun normalizeTaskTag(value: String): String = value.trim().trimStart('#').replace(Regex("\\s+"), " ")

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
private fun TaskTimelineView(tasks: List<TaskItem>, columns: List<TaskColumnItem>, onTaskClick: (TaskItem) -> Unit) {
    val dated = tasks.filter { it.startAt != null || it.dueAt != null }.sortedBy { it.startAt ?: it.dueAt }
    val origin = dated.minOfOrNull { startOfDay(it.startAt ?: it.dueAt!!) } ?: startOfDay(System.currentTimeMillis())
    val dayWidth = 112.dp
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Timeline", fontWeight = FontWeight.Black, fontSize = 22.sp)
        Text("Drag tasks on the board or edit their date range to update this schedule.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
        if (dated.isEmpty()) {
            Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                Text("Add a start or due date to place tasks on the timeline.", Modifier.padding(20.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            Column(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(14.dp))) {
                Row {
                    Spacer(Modifier.width(190.dp))
                    repeat(14) { day ->
                        val time = origin + day * 86_400_000L
                        Text(formatCalendarDate(time), Modifier.width(dayWidth).border(1.dp, MaterialTheme.colorScheme.outlineVariant).padding(10.dp), textAlign = TextAlign.Center, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                    }
                }
                dated.forEach { task ->
                    val start = startOfDay(task.startAt ?: task.dueAt!!)
                    val end = startOfDay(task.dueAt ?: task.startAt!!)
                    val offsetDays = ((start - origin) / 86_400_000L).coerceAtLeast(0).toInt()
                    val spanDays = (((end - start) / 86_400_000L).toInt() + 1).coerceAtLeast(1)
                    val column = columns.firstOrNull { it.id == task.taskColumnId }
                    Row(Modifier.height(IntrinsicSize.Min)) {
                        Text(task.title, Modifier.width(190.dp).fillMaxHeight().border(1.dp, MaterialTheme.colorScheme.outlineVariant).clickable { onTaskClick(task) }.padding(12.dp), maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Bold)
                        Box(Modifier.width(dayWidth * 14).heightIn(min = 58.dp).border(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                            Surface(
                                color = task.resolveAccent(column?.resolvedColor() ?: MaterialTheme.colorScheme.primary).copy(alpha = .22f),
                                contentColor = MaterialTheme.colorScheme.onSurface,
                                shape = RoundedCornerShape(9.dp),
                                modifier = Modifier.padding(top = 10.dp).width(dayWidth * spanDays).height(38.dp).offset(x = dayWidth * offsetDays).clickable { onTaskClick(task) },
                            ) { Text(task.title, Modifier.padding(horizontal = 10.dp, vertical = 9.dp), maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 11.sp, fontWeight = FontWeight.Bold) }
                        }
                    }
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

/**
 * Wraps a task row in a SwipeToDismissBox honoring the user's configured swipe actions.
 * The row is never actually dismissed by the box — actions run and the box snaps back,
 * matching the note-list swipe behavior.
 */
@Composable
private fun TaskSwipeRow(
    task: TaskItem,
    startAction: TaskGestureAction,
    endAction: TaskGestureAction,
    onAction: (TaskItem, TaskGestureAction) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val accent = MaterialTheme.colorScheme.primary
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            when (value) {
                SwipeToDismissBoxValue.StartToEnd -> onAction(task, startAction)
                SwipeToDismissBoxValue.EndToStart -> onAction(task, endAction)
                SwipeToDismissBoxValue.Settled -> Unit
            }
            false
        },
    )
    SwipeToDismissBox(
        modifier = modifier,
        state = dismissState,
        enableDismissFromStartToEnd = startAction != TaskGestureAction.None,
        enableDismissFromEndToStart = endAction != TaskGestureAction.None,
        backgroundContent = {
            val action = when (dismissState.dismissDirection) {
                SwipeToDismissBoxValue.StartToEnd -> startAction
                SwipeToDismissBoxValue.EndToStart -> endAction
                SwipeToDismissBoxValue.Settled -> TaskGestureAction.None
            }
            val tint = if (action == TaskGestureAction.Delete) MaterialTheme.colorScheme.error else accent
            Box(
                Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(16.dp))
                    .background(tint.copy(alpha = 0.18f))
                    .padding(16.dp),
                contentAlignment = if (dismissState.dismissDirection == SwipeToDismissBoxValue.EndToStart) Alignment.CenterEnd else Alignment.CenterStart,
            ) {
                Text(action.name, fontWeight = FontWeight.Bold, color = tint)
            }
        },
        content = { content() },
    )
}

@Composable
private fun TaskListView(
    tasks: List<TaskItem>,
    columns: List<TaskColumnItem>,
    properties: List<TaskPropertyDefinition>,
    propertyValues: List<TaskPropertyValue>,
    checklistItems: List<TaskChecklistItem>,
    onTaskClick: (TaskItem) -> Unit,
    swipeStartAction: TaskGestureAction = TaskGestureAction.None,
    swipeEndAction: TaskGestureAction = TaskGestureAction.None,
    onSwipeAction: (TaskItem, TaskGestureAction) -> Unit = { _, _ -> },
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        tasks.forEach { task ->
            TaskSwipeRow(task = task, startAction = swipeStartAction, endAction = swipeEndAction, onAction = onSwipeAction) {
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
}

@Composable
private fun TaskFeedView(
    tasks: List<TaskItem>,
    properties: List<TaskPropertyDefinition>,
    propertyValues: List<TaskPropertyValue>,
    checklistItems: List<TaskChecklistItem>,
    onTaskClick: (TaskItem) -> Unit,
    swipeStartAction: TaskGestureAction = TaskGestureAction.None,
    swipeEndAction: TaskGestureAction = TaskGestureAction.None,
    onSwipeAction: (TaskItem, TaskGestureAction) -> Unit = { _, _ -> },
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        tasks.sortedByDescending { it.updatedAt }.forEach { task ->
            TaskSwipeRow(task = task, startAction = swipeStartAction, endAction = swipeEndAction, onAction = onSwipeAction) {
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
private fun TaskGalleryView(
    tasks: List<TaskItem>,
    checklistItems: List<TaskChecklistItem>,
    onTaskClick: (TaskItem) -> Unit,
    swipeStartAction: TaskGestureAction = TaskGestureAction.None,
    swipeEndAction: TaskGestureAction = TaskGestureAction.None,
    onSwipeAction: (TaskItem, TaskGestureAction) -> Unit = { _, _ -> },
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        tasks.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                row.forEach { task ->
                    TaskSwipeRow(
                        task = task,
                        startAction = swipeStartAction,
                        endAction = swipeEndAction,
                        onAction = onSwipeAction,
                        modifier = Modifier.weight(1f),
                    ) {
                    Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth().height(142.dp).clickable { onTaskClick(task) }) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.SpaceBetween) {
                            Text(task.title, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            task.progressFromChecklist(checklistItems)?.let { progress ->
                                LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth())
                            }
                            PriorityPill(task.priority)
                        }
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
    var tab by remember(action) { mutableStateOf(TaskRailAction.Filter) }
    AnimatedVisibility(visible = action != null, modifier = modifier) {
        Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(22.dp), tonalElevation = 6.dp) {
            Column(
                Modifier.heightIn(max = 620.dp).verticalScroll(rememberScrollState()).padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf(TaskRailAction.Filter, TaskRailAction.Sort, TaskRailAction.New, TaskRailAction.Settings).forEach { item ->
                        TextButton(onClick = { tab = item }, modifier = Modifier.weight(1f)) {
                            Text(item.name, color = if (tab == item) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Bold)
                        }
                    }
                    IconButton(onClick = onDismiss) { Icon(Icons.Outlined.Close, "Close task controls") }
                }
                HorizontalDivider()
                when (tab) {
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
    NorfoldDialog(
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
    task: TaskItem?,
    properties: List<TaskPropertyDefinition>,
    propertyValues: List<TaskPropertyValue>,
    checklistItems: List<TaskChecklistItem>,
    columns: List<TaskColumnItem>,
    tags: List<Tag>,
    boardName: String,
    taskObjectId: Long?,
    comments: List<WorkspaceComment>,
    files: List<WorkspaceFileItem>,
    viewModel: NotesViewModel,
    onPickAttachment: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
    initialColumn: TaskColumnItem? = null,
    onCreate: (TaskCreationPayload) -> Unit = {},
) {
    BackHandler(onBack = onDismiss)
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.35f))
            .zIndex(20f),
    ) {
        val sidePanel = maxWidth > 720.dp
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurface,
            tonalElevation = 10.dp,
            shape = if (sidePanel) RoundedCornerShape(topStart = 28.dp, bottomStart = 28.dp) else RoundedCornerShape(0.dp),
            modifier = Modifier
                .then(if (sidePanel) Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(540.dp) else Modifier.fillMaxSize())
                .navigationBarsPadding()
                .imePadding(),
        ) {
            if (task == null) {
                NewTaskPageContent(
                    columns = columns,
                    properties = properties,
                    initialColumn = initialColumn,
                    boardName = boardName,
                    onDismiss = onDismiss,
                    onCreate = onCreate,
                )
                return@Surface
            }
            var showPicker by remember { mutableStateOf(false) }
            var commentDraft by remember(task.id) { mutableStateOf("") }
            val sortedProperties = properties.sortedWith(compareBy<TaskPropertyDefinition> { it.sortOrder }.thenBy { it.id })
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 18.dp),
                contentPadding = PaddingValues(bottom = if (sidePanel) 24.dp else 124.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                item {
                    val nameProperty = sortedProperties.firstOrNull { it.type == TaskPropertyType.Name }
                    var editingTitle by remember(task.id) { mutableStateOf(false) }
                    var titleDraft by remember(task.id, task.title) { mutableStateOf(task.title) }
                    var starred by remember(task.id) { mutableStateOf(false) }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        IconButton(
                            onClick = onDismiss,
                            modifier = Modifier
                                .size(46.dp)
                                .shadow(3.dp, RoundedCornerShape(14.dp))
                                .clip(RoundedCornerShape(14.dp))
                                .background(MaterialTheme.colorScheme.surface),
                        ) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back", modifier = Modifier.size(20.dp)) }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
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
                                Text(
                                    task.title.ifBlank { "Untitled task" },
                                    style = MaterialTheme.typography.headlineMedium,
                                    fontWeight = FontWeight.Black,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.clickable { editingTitle = true },
                                )
                                Row {
                                    Text("From ", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                                    Text(boardName, color = MaterialTheme.colorScheme.primary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                        IconButton(onClick = { starred = !starred }) {
                            Icon(
                                if (starred) Icons.Filled.Star else Icons.Outlined.StarBorder,
                                "Star",
                                tint = if (starred) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Button(
                            onClick = onDismiss,
                            shape = RoundedCornerShape(20.dp),
                            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 11.dp),
                        ) { Text("Save", fontWeight = FontWeight.Bold) }
                    }
                }
                item { TaskColorPalette(task = task, viewModel = viewModel) }
                val visibleProperties = sortedProperties.filterNot { it.type == TaskPropertyType.Name || it.type == TaskPropertyType.FilesMedia }
                val mainPropertyTypes = setOf(
                    TaskPropertyType.Status,
                    TaskPropertyType.Assignee,
                    TaskPropertyType.Person,
                    TaskPropertyType.Priority,
                    TaskPropertyType.Labels,
                    TaskPropertyType.Multiselect,
                    TaskPropertyType.DueDate,
                    TaskPropertyType.Date,
                )
                val mainPropertyOrder = mapOf(
                    TaskPropertyType.Status to 0,
                    TaskPropertyType.Assignee to 1,
                    TaskPropertyType.Person to 1,
                    TaskPropertyType.Priority to 2,
                    TaskPropertyType.Labels to 3,
                    TaskPropertyType.Multiselect to 3,
                    TaskPropertyType.DueDate to 4,
                    TaskPropertyType.Date to 4,
                )
                val mainProperties = visibleProperties
                    .filter { it.type in mainPropertyTypes }
                    .sortedBy { mainPropertyOrder[it.type] ?: Int.MAX_VALUE }
                val checklistProperties = visibleProperties.filter { it.type == TaskPropertyType.Checklist }
                val noteProperties = visibleProperties.filter { it.type == TaskPropertyType.Text }
                if (mainProperties.isNotEmpty()) {
                    item {
                        MainPropertiesCard(
                            task = task,
                            properties = mainProperties,
                            propertyValues = propertyValues,
                            columns = columns,
                            tags = tags,
                            viewModel = viewModel,
                        )
                    }
                }
                itemsIndexed(checklistProperties, key = { _, property -> property.id }) { index, property ->
                    TaskChecklistSummaryCard(
                        task = task,
                        property = property,
                        propertyIndex = index,
                        propertyCount = checklistProperties.size,
                        items = checklistItems.filter { it.taskId == task.id && it.propertyId == property.id }.sortedBy { it.sortOrder },
                        viewModel = viewModel,
                    )
                }
                items(noteProperties, key = { it.id }) { property ->
                    TaskNotesSection(
                        task = task,
                        property = property,
                        value = propertyValues.firstOrNull { it.taskId == task.id && it.propertyId == property.id },
                        columns = columns,
                        tags = tags,
                        viewModel = viewModel,
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
                    // Always-present Details card (mockup) — independent of user-added properties.
                    Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surface, tonalElevation = 1.dp, shadowElevation = 1.dp, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Icon(Icons.Outlined.Event, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                                Text("Details", fontWeight = FontWeight.Bold)
                            }
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text("Created at", Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                                Text(shortTime(task.createdAt), fontSize = 13.sp)
                            }
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text("Last modified", Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                                Text(shortTime(task.updatedAt), fontSize = 13.sp)
                            }
                        }
                    }
                }
                item {
                    DashedActionButton(text = "New property", onClick = { showPicker = true })
                }
                item {
                    Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surface, tonalElevation = 1.dp, shadowElevation = 1.dp, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Icon(Icons.Outlined.ChatBubbleOutline, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                                Text("Comments", fontWeight = FontWeight.Bold)
                            }
                            comments.filter { it.objectId == taskObjectId }.sortedBy { it.createdAt }.forEach { comment ->
                                Surface(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.38f), shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
                                    Column(Modifier.padding(10.dp)) {
                                        Text(comment.authorDisplayName.ifBlank { comment.authorUsername.ifBlank { "You" } }, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                        Text(comment.body)
                                    }
                                }
                            }
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedTextField(commentDraft, { commentDraft = it }, modifier = Modifier.weight(1f), placeholder = { Text("Add a reply...") }, colors = taskTextFieldColors(), shape = RoundedCornerShape(16.dp))
                                Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer) {
                                    IconButton(
                                        enabled = taskObjectId != null && commentDraft.isNotBlank(),
                                        onClick = { taskObjectId?.let { viewModel.addWorkspaceComment(it, commentDraft.trim()) }; commentDraft = "" },
                                    ) { Icon(Icons.AutoMirrored.Outlined.Send, "Send comment", tint = MaterialTheme.colorScheme.primary) }
                                }
                            }
                        }
                    }
                }
                item {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                        TextButton(onClick = onDelete, colors = androidx.compose.material3.ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                            Icon(Icons.Outlined.Delete, null, tint = MaterialTheme.colorScheme.error)
                            Spacer(Modifier.width(6.dp))
                            Text("Delete task", color = MaterialTheme.colorScheme.error)
                        }
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
private fun NewTaskPageContent(
    columns: List<TaskColumnItem>,
    properties: List<TaskPropertyDefinition>,
    initialColumn: TaskColumnItem?,
    boardName: String,
    onDismiss: () -> Unit,
    onCreate: (TaskCreationPayload) -> Unit,
) {
    var title by remember { mutableStateOf("") }
    var start by remember { mutableStateOf("") }
    var end by remember { mutableStateOf("") }
    var due by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var checklist by remember { mutableStateOf("") }
    var selectedColumn by remember(columns) { mutableStateOf(initialColumn ?: columns.first()) }
    var priority by remember { mutableStateOf(TaskPriority.Normal) }
    val canCreate = title.isNotBlank()
    val create = {
        val startAt = parseTaskDraftDate(start)
        val endAt = parseTaskDraftDate(end)
        val dueAt = parseTaskDraftDate(due)
        val values = buildMap {
            put(TaskPropertyType.Status, selectedColumn.name)
            put(TaskPropertyType.Priority, priority.name)
            if (note.isNotBlank()) put(TaskPropertyType.Text, note.trim())
            if (startAt != null || endAt != null) {
                val type = if (properties.any { it.type == TaskPropertyType.Date }) TaskPropertyType.Date else TaskPropertyType.DueDate
                put(type, TaskDateRangeCodec.encode(TaskDateRange(startAt, endAt, allDay = true)))
            }
            if (dueAt != null) {
                put(TaskPropertyType.DueDate, TaskDateRangeCodec.encode(TaskDateRange(startAt ?: dueAt, dueAt, allDay = true)))
            }
        }
        onCreate(
            TaskCreationPayload(
                title = title.trim(),
                column = selectedColumn,
                propertyValues = values,
                checklistItems = checklist.lineSequence().map(String::trim).filter(String::isNotBlank).toList(),
            ),
        )
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 18.dp),
        contentPadding = PaddingValues(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .size(46.dp)
                        .shadow(3.dp, RoundedCornerShape(14.dp))
                        .clip(RoundedCornerShape(14.dp))
                        .background(MaterialTheme.colorScheme.surface),
                ) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back", modifier = Modifier.size(20.dp)) }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("New task", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Black)
                    Row {
                        Text("In ", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                        Text(boardName, color = MaterialTheme.colorScheme.primary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
                Button(
                    onClick = create,
                    enabled = canCreate,
                    shape = RoundedCornerShape(20.dp),
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 11.dp),
                ) { Text("Create", fontWeight = FontWeight.Bold) }
            }
        }
        item {
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("Title") },
                colors = taskTextFieldColors(),
            )
        }
        item {
            Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surface, tonalElevation = 1.dp, shadowElevation = 1.dp, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Status", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(columns, key = { it.id }) { column ->
                            AssistChip(
                                onClick = { selectedColumn = column },
                                label = { Text(if (selectedColumn.id == column.id) "${column.name} ✓" else column.name) },
                            )
                        }
                    }
                    Text("Priority", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(TaskPriority.entries) { option ->
                            AssistChip(
                                onClick = { priority = option },
                                label = { Text(if (priority == option) "${option.label()} ✓" else option.label()) },
                            )
                        }
                    }
                    Text("Dates", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        CompactTaskDraftField("Start", start, { start = it }, Modifier.weight(1f))
                        CompactTaskDraftField("End", end, { end = it }, Modifier.weight(1f))
                        CompactTaskDraftField("Due", due, { due = it }, Modifier.weight(1f))
                    }
                    Text("Use YYYY-MM-DD", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        item {
            OutlinedTextField(
                value = note,
                onValueChange = { note = it },
                modifier = Modifier.fillMaxWidth().heightIn(min = 86.dp),
                label = { Text("Note") },
                colors = taskTextFieldColors(),
            )
        }
        item {
            OutlinedTextField(
                value = checklist,
                onValueChange = { checklist = it },
                modifier = Modifier.fillMaxWidth().heightIn(min = 92.dp),
                label = { Text("Checklist") },
                supportingText = { Text("One item per line") },
                colors = taskTextFieldColors(),
            )
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End)) {
                TextButton(onClick = onDismiss) { Text("Cancel") }
                Button(enabled = canCreate, onClick = create) { Text("Create") }
            }
        }
    }
}

private fun Modifier.dashedOutline(color: Color, radius: Dp): Modifier = drawBehind {
    val strokeWidth = 1.dp.toPx()
    drawRoundRect(
        color = color,
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(radius.toPx(), radius.toPx()),
        style = Stroke(
            width = strokeWidth,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(8.dp.toPx(), 6.dp.toPx())),
        ),
    )
}

@Composable
private fun DashedActionButton(text: String, onClick: () -> Unit) {
    val outline = MaterialTheme.colorScheme.outlineVariant
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .dashedOutline(outline, 16.dp)
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 15.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.Add, null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(8.dp))
        Text(text, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun TaskFilesSection(files: List<WorkspaceFileItem>, onAttach: () -> Unit, onRemove: (WorkspaceFileItem) -> Unit) {
    val context = LocalContext.current
    Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(18.dp), tonalElevation = 1.dp, shadowElevation = 1.dp, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.AttachFile, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text("Files & media", modifier = Modifier.weight(1f), fontWeight = FontWeight.Bold)
                Surface(shape = RoundedCornerShape(14.dp), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                    TextButton(onClick = onAttach) { Text("Attach") }
                }
            }
            if (files.isEmpty()) {
                val outline = MaterialTheme.colorScheme.outlineVariant
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .dashedOutline(outline, 16.dp)
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                        Icon(Icons.Outlined.AttachFile, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(10.dp))
                    }
                    Column(Modifier.weight(1f)) {
                        Text("No files attached", fontWeight = FontWeight.Bold)
                        Text("Attach files or images to this task", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                    }
                    FilledTonalButton(onClick = onAttach, shape = RoundedCornerShape(14.dp)) { Text("Attach") }
                }
            }
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
    Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(18.dp), tonalElevation = 1.dp, shadowElevation = 1.dp, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Color", fontWeight = FontWeight.Bold)
            ColorSwatch(
                color = MaterialTheme.colorScheme.primary,
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
        contentAlignment = Alignment.Center,
    ) {
        if (selected) Icon(Icons.Filled.Check, "Selected color", tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(16.dp))
    }
}

/**
 * The mockup's grouped "Main properties" card: each property is a compact row showing its current
 * value; tapping a row opens a popup editor. Persistence reuses the same view-model calls as the
 * standalone editors.
 */
@Composable
private fun MainPropertiesCard(
    task: TaskItem,
    properties: List<TaskPropertyDefinition>,
    propertyValues: List<TaskPropertyValue>,
    columns: List<TaskColumnItem>,
    tags: List<Tag>,
    viewModel: NotesViewModel,
) {
    var editing by remember(task.id) { mutableStateOf<TaskPropertyDefinition?>(null) }
    var expanded by remember(task.id) { mutableStateOf(true) }
    Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surface, tonalElevation = 1.dp, shadowElevation = 1.dp, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { expanded = !expanded }.padding(bottom = 4.dp),
            ) {
                Icon(Icons.Outlined.Tune, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                Text("Main properties", modifier = Modifier.weight(1f), fontWeight = FontWeight.Bold, fontSize = 15.sp)
                Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, if (expanded) "Collapse" else "Expand")
            }
            AnimatedVisibility(expanded) {
                Column {
                    properties.forEach { property ->
                        val value = propertyValues.firstOrNull { it.taskId == task.id && it.propertyId == property.id }
                        MainPropertyRow(
                            icon = property.type.icon(),
                            label = property.name,
                            value = mainPropertyDisplay(task, property, value, columns),
                            showLabelChips = property.type == TaskPropertyType.Labels || property.type == TaskPropertyType.Multiselect,
                            showCalendar = property.type == TaskPropertyType.Date || property.type == TaskPropertyType.DueDate,
                            onClick = { editing = property },
                        )
                    }
                }
            }
        }
    }
    editing?.let { property ->
        FocusedTaskPropertyDialog(
            task = task,
            property = property,
            value = propertyValues.firstOrNull { it.taskId == task.id && it.propertyId == property.id },
            checklistItems = emptyList(),
            columns = columns,
            tags = tags,
            anchor = null,
            viewModel = viewModel,
            onPickAttachment = {},
            onDismiss = { editing = null },
        )
    }
}

@Composable
private fun MainPropertyRow(
    icon: ImageVector,
    label: String,
    value: String,
    showLabelChips: Boolean,
    showCalendar: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
        Text(
            label,
            modifier = Modifier.widthIn(min = 86.dp, max = 116.dp),
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Surface(
            color = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(10.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier.weight(1f),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (showLabelChips && value.isNotBlank()) {
                    Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        value.split(',', ';').map { it.trim() }.filter { it.isNotBlank() }.forEach { labelValue ->
                            Surface(color = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.onPrimaryContainer, shape = RoundedCornerShape(8.dp)) {
                                Text(labelValue, Modifier.padding(horizontal = 7.dp, vertical = 3.dp), fontSize = 11.sp, maxLines = 1)
                            }
                        }
                    }
                } else {
                    Text(
                        value.ifBlank { if (showCalendar) "No date set" else "Empty" },
                        modifier = Modifier.weight(1f),
                        fontSize = 13.sp,
                        color = if (value.isBlank()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (showCalendar && value.isBlank()) Icon(Icons.Outlined.Event, null, modifier = Modifier.size(17.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                else Icon(Icons.Outlined.ExpandMore, null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun TaskChecklistSummaryCard(
    task: TaskItem,
    property: TaskPropertyDefinition,
    propertyIndex: Int,
    propertyCount: Int,
    items: List<TaskChecklistItem>,
    viewModel: NotesViewModel,
) {
    if (property.hiddenWhenEmpty && items.isEmpty()) return
    var newItem by remember(task.id, property.id) { mutableStateOf("") }
    var expanded by remember(task.id, property.id) { mutableStateOf(true) }
    val completed = items.count { it.checked }
    val progress = if (items.isEmpty()) 0f else completed.toFloat() / items.size
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(18.dp),
        tonalElevation = 1.dp,
        shadowElevation = 1.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(Icons.Outlined.CheckBox, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                Text(property.name, modifier = Modifier.weight(1f), fontWeight = FontWeight.Bold)
                Text("$completed/${items.size}", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.width(82.dp).height(5.dp).clip(CircleShape),
                )
                Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, if (expanded) "Collapse" else "Expand", modifier = Modifier.size(18.dp))
            }
            AnimatedVisibility(expanded) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items.forEachIndexed { index, item ->
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            VerticalReorderHandle(enabled = items.size > 1, rowHeight = 38.dp, size = 18.dp) { delta ->
                                viewModel.moveChecklistItemToIndex(item, (index + delta).coerceIn(0, items.lastIndex))
                            }
                            Checkbox(
                                checked = item.checked,
                                onCheckedChange = { checked -> viewModel.updateChecklistItem(item, item.text, checked) },
                                modifier = Modifier.size(32.dp),
                            )
                            Text(
                                item.text,
                                modifier = Modifier.weight(1f),
                                color = if (item.checked) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                                textDecoration = if (item.checked) TextDecoration.LineThrough else TextDecoration.None,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            AssigneeAvatar(task.assignee.ifBlank { "@owner" })
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = newItem,
                            onValueChange = { newItem = it },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            placeholder = { Text("Add a new item") },
                            colors = taskTextFieldColors(),
                            shape = RoundedCornerShape(14.dp),
                        )
                        FilledTonalButton(
                            onClick = {
                                viewModel.addChecklistItem(task, property, newItem.trim())
                                newItem = ""
                            },
                            enabled = newItem.isNotBlank(),
                            shape = RoundedCornerShape(14.dp),
                        ) { Text("Add") }
                    }
                }
            }
        }
    }
}

@Composable
private fun TaskNotesSection(
    task: TaskItem,
    property: TaskPropertyDefinition,
    value: TaskPropertyValue?,
    columns: List<TaskColumnItem>,
    tags: List<Tag>,
    viewModel: NotesViewModel,
) {
    val displayed = property.displayValue(task, value?.valueJson.orEmpty())
    if (property.hiddenWhenEmpty && displayed.isBlank()) return
    var editing by remember(task.id, property.id) { mutableStateOf(false) }
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(18.dp),
        tonalElevation = 1.dp,
        shadowElevation = 1.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Outlined.TextFields, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                Text("Notes", fontWeight = FontWeight.Bold)
            }
            Surface(
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier.fillMaxWidth().clickable { editing = true },
            ) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(displayed.ifBlank { "Add notes" }, modifier = Modifier.weight(1f), color = if (displayed.isBlank()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface, maxLines = 4, overflow = TextOverflow.Ellipsis)
                    Icon(Icons.Outlined.Edit, "Edit notes", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
    if (editing) {
        FocusedTaskPropertyDialog(
            task = task,
            property = property,
            value = value,
            checklistItems = emptyList(),
            columns = columns,
            tags = tags,
            anchor = null,
            viewModel = viewModel,
            onPickAttachment = {},
            onDismiss = { editing = false },
        )
    }
}

/** The value string shown in a [MainPropertyRow] for the given property. */
private fun mainPropertyDisplay(
    task: TaskItem,
    property: TaskPropertyDefinition,
    value: TaskPropertyValue?,
    columns: List<TaskColumnItem>,
): String = when (property.type) {
    TaskPropertyType.Status -> columns.firstOrNull { it.id == task.taskColumnId }?.name ?: "—"
    TaskPropertyType.Priority -> task.priority.name
    TaskPropertyType.DueDate, TaskPropertyType.Date -> {
        val range = TaskDateRangeCodec.decode(value?.valueJson.orEmpty(), task.dueAt)
        when {
            range.startAt == null && range.endAt == null -> "No date set"
            range.startAt == range.endAt -> formatCalendarDate(range.endAt ?: range.startAt!!)
            else -> "${formatCalendarDate(range.startAt!!)} → ${formatCalendarDate(range.endAt!!)}"
        }
    }
    TaskPropertyType.Checkbox -> if ((value?.valueJson ?: "").toBoolean()) "Checked" else "Unchecked"
    else -> property.displayValue(task, value?.valueJson.orEmpty())
}

/** Popup editor for a single main property, chosen by [property] type. */
@Composable
private fun MainPropertyEditDialog(
    task: TaskItem,
    property: TaskPropertyDefinition,
    value: TaskPropertyValue?,
    columns: List<TaskColumnItem>,
    viewModel: NotesViewModel,
    onDismiss: () -> Unit,
) {
    when (property.type) {
        TaskPropertyType.Status -> NorfoldDialog(
            onDismissRequest = onDismiss,
            confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
            title = { Text("Status") },
            text = {
                Column {
                    columns.forEach { column ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .clickable { viewModel.moveTaskToColumn(task, column); onDismiss() }
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = task.taskColumnId == column.id, onClick = { viewModel.moveTaskToColumn(task, column); onDismiss() })
                            Text(column.name)
                        }
                    }
                }
            },
        )
        TaskPropertyType.Priority -> {
            var draft by remember(task.id, property.id) { mutableStateOf(task.priority.name) }
            NorfoldDialog(
                onDismissRequest = onDismiss,
                confirmButton = { TextButton(onClick = { viewModel.setTaskPropertyValue(task, property, draft); onDismiss() }) { Text("Save") } },
                dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
                title = { Text("Priority") },
                text = {
                    Column {
                        TaskPriority.entries.forEach { priority ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .clickable { draft = priority.name }
                                    .padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                RadioButton(selected = draft == priority.name, onClick = { draft = priority.name })
                                Text(priority.name)
                            }
                        }
                    }
                },
            )
        }
        TaskPropertyType.DueDate, TaskPropertyType.Date -> NorfoldDialog(
            onDismissRequest = onDismiss,
            confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
            title = { Text(property.name) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    DatePropertyEditor(task, property, value?.valueJson.orEmpty(), viewModel)
                }
            },
        )
        TaskPropertyType.Checkbox -> {
            var draft by remember(task.id, property.id) { mutableStateOf((value?.valueJson ?: "").toBoolean()) }
            NorfoldDialog(
                onDismissRequest = onDismiss,
                confirmButton = { TextButton(onClick = { viewModel.setTaskPropertyValue(task, property, draft.toString()); onDismiss() }) { Text("Save") } },
                dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
                title = { Text(property.name) },
                text = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = draft, onCheckedChange = { draft = it })
                        Text(if (draft) "Checked" else "Unchecked")
                    }
                },
            )
        }
        else -> {
            var draft by remember(task.id, property.id) { mutableStateOf(value?.valueJson.orEmpty()) }
            NorfoldDialog(
                onDismissRequest = onDismiss,
                confirmButton = { TextButton(onClick = { viewModel.setTaskPropertyValue(task, property, draft); onDismiss() }) { Text("Save") } },
                dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
                title = { Text(property.name) },
                text = {
                    OutlinedTextField(
                        value = draft,
                        onValueChange = { draft = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text(property.type.defaultPropertyName()) },
                        colors = taskTextFieldColors(),
                    )
                },
            )
        }
    }
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
                NorfoldTaskMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
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

@Composable
private fun NorfoldTaskMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    content: @Composable () -> Unit,
) {
    val style = LocalContextualMenuStyle.current
    val colorMode = LocalContextualMenuColor.current
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        shape = when (style) {
            ContextualMenuStyle.Pill -> RoundedCornerShape(18.dp)
            ContextualMenuStyle.Block -> RoundedCornerShape(8.dp)
            ContextualMenuStyle.Minimal -> RoundedCornerShape(2.dp)
        },
        containerColor = if (colorMode == ContextualMenuColor.AppAccent) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) { content() }
}

/**
 * A drag handle that live-reorders as you drag: each time the drag crosses one row height it emits
 * a single step (+1 down / -1 up), so the block moves continuously under the finger rather than only
 * snapping on release. [onStep] is expected to persist the move (which re-renders the list).
 */
@Composable
private fun VerticalReorderHandle(enabled: Boolean = true, rowHeight: Dp = 64.dp, size: Dp = 28.dp, onStep: (Int) -> Unit) {
    val density = LocalDensity.current
    val thresholdPx = with(density) { rowHeight.toPx() }
    var dragY by remember { mutableStateOf(0f) }
    var dragging by remember { mutableStateOf(false) }
    Icon(
        Icons.Outlined.DragIndicator,
        null,
        tint = if (dragging) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .size(size)
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
    NorfoldDialog(
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
    NorfoldContentDialog(onDismissRequest = onDismiss) {
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
    // Neutralized: only the highest tiers get an accent tint; the rest are neutral chips.
    val emphasized = priority == TaskPriority.High || priority == TaskPriority.Urgent
    val bg = if (emphasized) MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)
    val fg = if (emphasized) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    Surface(color = bg, contentColor = fg, shape = RoundedCornerShape(10.dp)) {
        Text(priority.label(), modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp), fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun AssigneeAvatar(name: String) {
    val initial = name.trimStart('@', ' ').take(1).uppercase().ifBlank { "?" }
    Box(
        Modifier.size(24.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)),
        contentAlignment = Alignment.Center,
    ) {
        Text(initial, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
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
private fun TaskColumnItem.resolvedColor(): Color =
    if (color == 0L) MaterialTheme.colorScheme.primary else Color(color)

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
