package com.norfold.app.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.TrackChanges
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.norfold.app.branding.AnimatedNorfoldLogo
import com.norfold.app.branding.palette
import com.norfold.app.domain.CalendarEventSource
import com.norfold.app.domain.GoalItem
import com.norfold.app.domain.GoalStatus
import com.norfold.app.domain.TaskStatus
import com.norfold.app.domain.ThemeMode
import com.norfold.app.ui.NotesUiState
import com.norfold.app.ui.NotesViewModel
import com.norfold.app.ui.components.NorfoldCard
import com.norfold.app.ui.components.NorfoldMetric
import com.norfold.app.ui.components.NorfoldPageHeader
import com.norfold.app.ui.components.NorfoldSegmentedControl
import com.norfold.app.ui.components.NorfoldStatusPill
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlin.math.roundToInt

@Composable
private fun LegacyNorfoldOnboardingScreen(state: NotesUiState, viewModel: NotesViewModel) {
    var step by remember { mutableIntStateOf(0) }
    var workspaceName by remember { mutableStateOf("My Workspace") }
    var purpose by remember { mutableStateOf("Personal") }
    var theme by remember { mutableStateOf(ThemeMode.System) }

    Box(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(Modifier.fillMaxWidth().width(440.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(22.dp)) {
            AnimatedNorfoldLogo(size = if (step == 0) 126.dp else 74.dp, palette = state.settings.themeProfile.palette(), animate = step == 0)
            when (step) {
                0 -> {
                    Text("Norfold", style = MaterialTheme.typography.headlineLarge)
                    Text("Your own private workspace.", color = MaterialTheme.colorScheme.primary, fontSize = 15.sp)
                    Text("Notes, tasks, projects, files, calendar, canvas and conversations stay organized in one local-first workspace.", color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                    Button(onClick = { step = 1 }, modifier = Modifier.fillMaxWidth().height(50.dp)) { Text("Get started") }
                    Text("Works offline. Connect an account only when you need sync or collaboration.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp, textAlign = TextAlign.Center)
                }
                1 -> {
                    Text("Private by default", style = MaterialTheme.typography.headlineMedium)
                    Text("Local workspaces never leave this device unless you explicitly export or connect sync.", color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                    NorfoldCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            OnboardingFact(Icons.Outlined.Lock, "Encrypted backups", "Your backup password stays on your device.")
                            OnboardingFact(Icons.Outlined.CheckCircle, "Guest workspace", "No account is required to use the complete local app.")
                            OnboardingFact(Icons.Outlined.Schedule, "Connect later", "Cloud sync and teams can be enabled from Settings.")
                        }
                    }
                    Button(onClick = { step = 2 }, Modifier.fillMaxWidth()) { Text("Continue offline") }
                }
                2 -> {
                    Text("Create your workspace", style = MaterialTheme.typography.headlineMedium)
                    OutlinedTextField(workspaceName, { workspaceName = it.take(40) }, Modifier.fillMaxWidth(), label = { Text("Workspace name") }, singleLine = true, shape = RoundedCornerShape(10.dp))
                    Text("What will you use Norfold for?", Modifier.fillMaxWidth(), fontWeight = FontWeight.SemiBold)
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("Personal" to "Notes, tasks and daily planning", "Work" to "Tasks, docs and collaboration", "Study" to "Research and assignments", "Creative" to "Ideas, drafts and inspiration").forEach { (name, detail) ->
                            Surface(
                                Modifier.fillMaxWidth().clickable { purpose = name },
                                shape = RoundedCornerShape(10.dp),
                                border = BorderStroke(1.dp, if (purpose == name) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline),
                                color = if (purpose == name) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                            ) {
                                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Outlined.Folder, null, tint = MaterialTheme.colorScheme.primary)
                                    Column(Modifier.weight(1f).padding(horizontal = 12.dp)) { Text(name, fontWeight = FontWeight.SemiBold); Text(detail, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                                    if (purpose == name) Icon(Icons.Outlined.CheckCircle, null, tint = MaterialTheme.colorScheme.primary)
                                }
                            }
                        }
                    }
                    Button(onClick = { step = 3 }, enabled = workspaceName.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text("Continue") }
                }
                else -> {
                    Text("Choose an appearance", style = MaterialTheme.typography.headlineMedium)
                    NorfoldSegmentedControl(ThemeMode.entries.map { it.name }, theme.name, { theme = ThemeMode.valueOf(it) }, Modifier.fillMaxWidth())
                    NorfoldCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(workspaceName.ifBlank { "My Workspace" }, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                            Text("$purpose workspace", color = MaterialTheme.colorScheme.primary, fontSize = 12.sp)
                            Text("You can change colors, density and typography from Appearance at any time.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                        }
                    }
                    Button(onClick = { viewModel.finishOnboarding(workspaceName, purpose, theme) }, modifier = Modifier.fillMaxWidth().height(50.dp)) { Text("Enter workspace") }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { repeat(4) { index -> Box(Modifier.size(if (index == step) 18.dp else 6.dp, 6.dp).background(if (index == step) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, CircleShape)) } }
        }
    }
}

@Composable
private fun OnboardingFact(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, detail: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Surface(shape = RoundedCornerShape(9.dp), color = MaterialTheme.colorScheme.primaryContainer) { Icon(icon, null, Modifier.padding(8.dp), tint = MaterialTheme.colorScheme.primary) }
        Column { Text(title, fontWeight = FontWeight.SemiBold); Text(detail, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
fun GoalsScreen(state: NotesUiState, viewModel: NotesViewModel) {
    var createOpen by remember { mutableStateOf(false) }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { NorfoldPageHeader("Goals", "Turn outcomes into measurable progress.", Icons.Outlined.TrackChanges, actions = { Button(onClick = { createOpen = true }) { Icon(Icons.Outlined.Add, null); Spacer(Modifier.width(6.dp)); Text("New goal") } }) }
        item {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                NorfoldMetric("Goals", state.goals.size.toString(), Icons.Outlined.TrackChanges, MaterialTheme.colorScheme.primary, Modifier.width(150.dp))
                NorfoldMetric("In progress", state.goals.count { it.status == GoalStatus.InProgress }.toString(), Icons.Outlined.Schedule, MaterialTheme.colorScheme.onSurfaceVariant, Modifier.width(150.dp))
                NorfoldMetric("Achieved", state.goals.count { it.status == GoalStatus.Achieved }.toString(), Icons.Outlined.CheckCircle, MaterialTheme.colorScheme.primary, Modifier.width(150.dp))
            }
        }
        items(state.goals, key = { it.id }) { goal -> GoalRow(goal, viewModel) }
        if (state.goals.isEmpty()) item { EmptyPlanningCard("No goals yet", "Add a measurable outcome to track your progress.") { createOpen = true } }
    }
    if (createOpen) GoalCreateDialog({ createOpen = false }) { title -> viewModel.createGoal(title); createOpen = false }
}

@Composable
private fun GoalRow(goal: GoalItem, viewModel: NotesViewModel) {
    val fraction = if (goal.target <= 0) 0f else (goal.progress / goal.target).coerceIn(0.0, 1.0).toFloat()
    NorfoldCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { Text(goal.title, fontWeight = FontWeight.Bold); Text(goal.description.ifBlank { "Independent goal" }, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                NorfoldStatusPill(goal.status.name.replace("NotStarted", "Not started").replace("InProgress", "In progress"), MaterialTheme.colorScheme.primary)
                IconButton(onClick = { viewModel.deleteGoal(goal) }) { Icon(Icons.Outlined.Delete, "Delete goal", tint = MaterialTheme.colorScheme.error) }
            }
            LinearProgressIndicator({ fraction }, Modifier.fillMaxWidth().height(7.dp), color = MaterialTheme.colorScheme.primary, trackColor = MaterialTheme.colorScheme.surfaceVariant)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${goal.progress.roundToInt()} / ${goal.target.roundToInt()} ${goal.unit}", Modifier.weight(1f), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedButton(onClick = { viewModel.updateGoal(goal.copy(progress = (goal.progress + maxOf(goal.target * 0.1, 1.0)).coerceAtMost(goal.target), status = GoalStatus.InProgress)) }) { Text("Update") }
            }
        }
    }
}

@Composable
fun CalendarWorkspaceScreen(state: NotesUiState, viewModel: NotesViewModel) {
    var month by remember { mutableStateOf(YearMonth.now()) }
    var selectedDate by remember { mutableStateOf(LocalDate.now()) }
    var mode by remember(state.settings.calendarDefaultView) { mutableStateOf(state.settings.calendarDefaultView) }
    var createOpen by remember { mutableStateOf(false) }
    val accent = MaterialTheme.colorScheme.primary
    val mutedAccent = MaterialTheme.colorScheme.onSurfaceVariant
    val events = remember(state.calendarEvents, state.tasks, state.goals, accent, mutedAccent) { planningEvents(state, accent, mutedAccent) }
    Column(Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        NorfoldPageHeader("Calendar", "Plan work and never miss a deadline.", Icons.Outlined.CalendarMonth)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(month.format(DateTimeFormatter.ofPattern("MMMM yyyy")), fontWeight = FontWeight.Bold, fontSize = 18.sp, modifier = Modifier.weight(1f))
            IconButton(onClick = { createOpen = true }) { Icon(Icons.Outlined.Add, "New event") }
            IconButton(onClick = {
                selectedDate = when (mode) {
                    "Month" -> selectedDate.minusMonths(1)
                    "Week" -> selectedDate.minusWeeks(1)
                    else -> selectedDate.minusDays(1)
                }
                month = YearMonth.from(selectedDate)
            }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Previous period") }
            IconButton(onClick = {
                selectedDate = when (mode) {
                    "Month" -> selectedDate.plusMonths(1)
                    "Week" -> selectedDate.plusWeeks(1)
                    else -> selectedDate.plusDays(1)
                }
                month = YearMonth.from(selectedDate)
            }) { Icon(Icons.AutoMirrored.Outlined.ArrowForward, "Next period") }
        }
        Box(Modifier.fillMaxWidth()) {
            NorfoldSegmentedControl(
                listOf("Month", "Week", "Day", "Agenda"),
                mode,
                {
                    mode = it
                    viewModel.patchSettings { settings -> settings.copy(calendarDefaultView = it) }
                },
                Modifier.horizontalScroll(rememberScrollState()),
            )
        }
        when (mode) {
            "Day" -> ContinuousDayCalendar(
                selectedDate = selectedDate,
                events = events,
                onVisibleDate = { selectedDate = it; month = YearMonth.from(it) },
                modifier = Modifier.weight(1f),
            )
            "Week" -> ContinuousWeekCalendar(
                selectedDate = selectedDate,
                events = events,
                onVisibleDate = { month = YearMonth.from(it) },
                onSelect = { selectedDate = it; month = YearMonth.from(it) },
                modifier = Modifier.weight(1f),
            )
            else -> LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (mode == "Agenda") {
                    item { AgendaPanel(events.filter { !it.date.isBefore(selectedDate) }) }
                } else {
                    item {
                        MonthPanel(month, selectedDate, events) {
                        selectedDate = it
                        month = YearMonth.from(it)
                        mode = "Day"
                        viewModel.patchSettings { settings -> settings.copy(calendarDefaultView = "Day") }
                    }
                    }
                }
                item { DayAgenda(selectedDate, events.filter { it.date == selectedDate }) }
                item { Spacer(Modifier.height(96.dp)) }
            }
        }
    }
    if (createOpen) EventCreateDialog(selectedDate, { createOpen = false }) { title ->
        val start = selectedDate.atTime(10, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        viewModel.createCalendarEvent(title, start, start + 3_600_000L)
        createOpen = false
    }
}

private data class PlanningEvent(val id: String, val title: String, val detail: String, val date: LocalDate, val time: LocalTime?, val color: Color, val source: String)

private fun planningEvents(state: NotesUiState, accent: Color, mutedAccent: Color): List<PlanningEvent> = buildList {
    state.calendarEvents.forEach { event ->
        val instant = Instant.ofEpochMilli(event.startAt).atZone(ZoneId.systemDefault())
        add(PlanningEvent("event-${event.id}", event.title, event.description, instant.toLocalDate(), if (event.allDay) null else instant.toLocalTime(), Color(event.color), event.source.name))
    }
    state.tasks.filter { it.startAt != null || it.dueAt != null }.forEach { task ->
        val zone = ZoneId.systemDefault()
        val start = Instant.ofEpochMilli(task.startAt ?: task.dueAt!!).atZone(zone)
        val end = Instant.ofEpochMilli(task.dueAt ?: task.startAt!!).atZone(zone)
        var date = start.toLocalDate()
        while (!date.isAfter(end.toLocalDate())) {
            add(
                PlanningEvent(
                    "task-${task.id}-$date",
                    task.title,
                    task.status.name,
                    date,
                    if (task.allDay) null else if (date == start.toLocalDate()) start.toLocalTime() else LocalTime.MIDNIGHT,
                    if (task.status == TaskStatus.Done) mutedAccent else task.colorArgb?.let { Color(it.toInt()) } ?: accent,
                    "Task",
                ),
            )
            date = date.plusDays(1)
        }
    }
}.sortedWith(compareBy<PlanningEvent> { it.date }.thenBy { it.time })

@Composable
private fun ContinuousDayCalendar(
    selectedDate: LocalDate,
    events: List<PlanningEvent>,
    onVisibleDate: (LocalDate) -> Unit,
    modifier: Modifier = Modifier,
) {
    val origin = remember { LocalDate.now().minusDays(365) }
    val dates = remember(origin) { List(731) { origin.plusDays(it.toLong()) } }
    val initialIndex = remember(origin) { ChronoUnit.DAYS.between(origin, selectedDate).toInt().coerceIn(dates.indices) }
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = initialIndex)
    LaunchedEffect(listState) {
        snapshotFlow { listState.firstVisibleItemIndex }
            .distinctUntilChanged()
            .collect { index -> dates.getOrNull(index)?.let(onVisibleDate) }
    }
    LaunchedEffect(selectedDate) {
        val target = ChronoUnit.DAYS.between(origin, selectedDate).toInt().coerceIn(dates.indices)
        if (target != listState.firstVisibleItemIndex) listState.animateScrollToItem(target)
    }
    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(dates, key = { it.toEpochDay() }) { date ->
            DayTimeline(date, events.filter { it.date == date })
        }
        item { Spacer(Modifier.height(96.dp)) }
    }
}

@Composable
private fun ContinuousWeekCalendar(
    selectedDate: LocalDate,
    events: List<PlanningEvent>,
    onVisibleDate: (LocalDate) -> Unit,
    onSelect: (LocalDate) -> Unit,
    modifier: Modifier = Modifier,
) {
    val origin = remember { LocalDate.now() }
    val centerIndex = Int.MAX_VALUE / 2
    val dateAt = remember(origin) {
        { index: Int -> origin.plusDays(index.toLong() - centerIndex.toLong()) }
    }
    val indexFor = remember(origin) {
        { date: LocalDate ->
            (centerIndex.toLong() + ChronoUnit.DAYS.between(origin, date))
            .coerceIn(0L, Int.MAX_VALUE.toLong() - 1L)
            .toInt()
        }
    }
    val initialIndex = remember(origin) { (indexFor(selectedDate) - 3).coerceAtLeast(0) }
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = initialIndex)
    LaunchedEffect(listState) {
        snapshotFlow { listState.firstVisibleItemIndex }
            .distinctUntilChanged()
            .collect { firstIndex -> onVisibleDate(dateAt((firstIndex + 3).coerceAtMost(Int.MAX_VALUE - 1))) }
    }
    LaunchedEffect(selectedDate) {
        val selectedIndex = indexFor(selectedDate)
        val isVisible = listState.layoutInfo.visibleItemsInfo.any { it.index == selectedIndex }
        if (!isVisible) listState.animateScrollToItem((selectedIndex - 3).coerceAtLeast(0))
    }
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val dayWidth = maxWidth / 7
        Surface(
            modifier = Modifier.fillMaxSize(),
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        ) {
            Column {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Outlined.CalendarMonth, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        dateAt((listState.firstVisibleItemIndex + 3).coerceAtMost(Int.MAX_VALUE - 1))
                            .format(DateTimeFormatter.ofPattern("MMMM yyyy")),
                        Modifier.weight(1f).padding(horizontal = 10.dp),
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                    )
                    NorfoldStatusPill("Week", MaterialTheme.colorScheme.primary)
                }
                LazyRow(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(
                        count = Int.MAX_VALUE,
                        key = { index -> dateAt(index).toEpochDay() },
                    ) { index ->
                        val date = dateAt(index)
                        ContinuousWeekDayColumn(
                            date = date,
                            selected = date == selectedDate,
                            events = events.filter { it.date == date },
                            onSelect = { onSelect(date) },
                            modifier = Modifier.width(dayWidth),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ContinuousWeekDayColumn(
    date: LocalDate,
    selected: Boolean,
    events: List<PlanningEvent>,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.08f) else Color.Transparent)
            .border(BorderStroke(0.5.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.55f)))
            .clickable(onClick = onSelect)
            .padding(horizontal = 3.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            date.dayOfWeek.name.take(3).lowercase().replaceFirstChar { it.uppercase() },
            Modifier.padding(top = 12.dp),
            fontSize = 10.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Box(
            Modifier.padding(top = 5.dp).size(34.dp)
                .background(if (selected) MaterialTheme.colorScheme.primary else Color.Transparent, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                date.dayOfMonth.toString(),
                fontWeight = FontWeight.Bold,
                color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
            )
        }
        if (events.isNotEmpty()) {
            Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                repeat(events.size.coerceAtMost(3)) {
                    Box(Modifier.size(4.dp).background(events[it].color, CircleShape))
                }
            }
        } else {
            Spacer(Modifier.height(8.dp))
        }
        Column(
            Modifier.fillMaxWidth().padding(top = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            events.take(5).forEach { event ->
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(6.dp),
                    color = event.color.copy(alpha = 0.16f),
                    border = BorderStroke(1.dp, event.color.copy(alpha = 0.36f)),
                ) {
                    Column(Modifier.padding(horizontal = 5.dp, vertical = 6.dp)) {
                        Text(
                            event.title,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        event.time?.let { time ->
                            Text(
                                time.format(DateTimeFormatter.ofPattern("h:mm a")),
                                fontSize = 8.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
            if (events.size > 5) {
                Text(
                    "+${events.size - 5} more",
                    Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.primary,
                    fontSize = 9.sp,
                )
            }
        }
    }
}

@Composable
private fun MonthPanel(month: YearMonth, selected: LocalDate, events: List<PlanningEvent>, onSelect: (LocalDate) -> Unit) {
    NorfoldCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(8.dp)) {
            Row(Modifier.fillMaxWidth()) { DayOfWeek.entries.forEach { day -> Text(day.name.take(3).lowercase().replaceFirstChar { it.uppercase() }, Modifier.weight(1f).padding(vertical = 8.dp), textAlign = TextAlign.Center, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
            val first = month.atDay(1)
            val offset = first.dayOfWeek.value - 1
            val cells = (0 until 42).map { first.minusDays(offset.toLong()).plusDays(it.toLong()) }
            cells.chunked(7).forEach { week ->
                Row(Modifier.fillMaxWidth()) {
                    week.forEach { date ->
                        val count = events.count { it.date == date }
                        val active = date == selected
                        Column(
                            Modifier.weight(1f).height(72.dp).padding(2.dp).background(if (active) MaterialTheme.colorScheme.primaryContainer else Color.Transparent, RoundedCornerShape(8.dp)).clickable { onSelect(date) }.padding(6.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(date.dayOfMonth.toString(), color = when { active -> MaterialTheme.colorScheme.primary; date.month != month.month -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f); else -> MaterialTheme.colorScheme.onSurface }, fontWeight = if (active) FontWeight.Bold else FontWeight.Normal, fontSize = 12.sp)
                            if (count > 0) { Spacer(Modifier.height(8.dp)); Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) { events.filter { it.date == date }.take(3).forEach { Box(Modifier.size(5.dp).background(it.color, CircleShape)) } } }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DayTimeline(date: LocalDate, events: List<PlanningEvent>) {
    val allDay = events.filter { it.time == null }
    val timed = events.filter { it.time != null }.groupBy { it.time!!.hour }
    NorfoldCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(date.format(DateTimeFormatter.ofPattern("EEEE, MMMM d")), fontWeight = FontWeight.Bold, fontSize = 17.sp)
            if (allDay.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("All day", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    allDay.forEach { event -> EventRow(event) }
                }
            }
            Column(Modifier.fillMaxWidth()) {
                repeat(24) { hour ->
                    val hourEvents = timed[hour].orEmpty()
                    Row(
                        Modifier.fillMaxWidth().height(if (hourEvents.size > 1) 104.dp else 68.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Text(
                            LocalTime.of(hour, 0).format(DateTimeFormatter.ofPattern("h a")),
                            modifier = Modifier.width(52.dp).padding(top = 8.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 10.sp,
                            textAlign = TextAlign.End,
                        )
                        Column(
                            Modifier.weight(1f).padding(start = 10.dp).border(
                                width = 0.5.dp,
                                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.45f),
                                shape = RoundedCornerShape(1.dp),
                            ),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            if (hourEvents.isEmpty()) {
                                Spacer(Modifier.height(66.dp))
                            } else {
                                hourEvents.forEach { event ->
                                    Row(
                                        Modifier.fillMaxWidth().height(46.dp)
                                            .background(event.color.copy(alpha = 0.16f), RoundedCornerShape(7.dp)),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Box(Modifier.width(4.dp).height(46.dp).background(event.color, RoundedCornerShape(7.dp)))
                                        Column(Modifier.padding(horizontal = 9.dp)) {
                                            Text(event.title, fontWeight = FontWeight.Bold, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            Text(event.time?.format(DateTimeFormatter.ofPattern("h:mm a")) ?: "All day", fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    }
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
private fun AgendaPanel(events: List<PlanningEvent>) {
    NorfoldCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(horizontal = 14.dp)) { if (events.isEmpty()) Text("No scheduled work", Modifier.padding(vertical = 20.dp), color = MaterialTheme.colorScheme.onSurfaceVariant); events.forEach { EventRow(it) } } }
}

@Composable
private fun DayAgenda(date: LocalDate, events: List<PlanningEvent>) {
    NorfoldCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { Text(date.format(DateTimeFormatter.ofPattern("EEEE, MMMM d")), fontWeight = FontWeight.Bold); if (events.isEmpty()) Text("Nothing scheduled", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp); events.forEach { EventRow(it) } } }
}

@Composable
private fun EventRow(event: PlanningEvent) {
    Row(Modifier.fillMaxWidth().padding(vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(4.dp).height(38.dp).background(event.color, RoundedCornerShape(999.dp)))
        Column(Modifier.weight(1f).padding(horizontal = 10.dp)) { Text(event.title, fontWeight = FontWeight.SemiBold, fontSize = 13.sp); Text(event.detail.ifBlank { event.source }, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        Text(event.time?.format(DateTimeFormatter.ofPattern("h:mm a")) ?: "All day", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun EmptyPlanningCard(title: String, detail: String, onCreate: () -> Unit) {
    NorfoldCard(Modifier.fillMaxWidth()) { Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) { Text(title, fontWeight = FontWeight.Bold); Text(detail, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, textAlign = TextAlign.Center); Button(onClick = onCreate) { Text("Create") } } }
}

@Composable
private fun GoalCreateDialog(onDismiss: () -> Unit, onCreate: (String) -> Unit) {
    var title by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("New goal") }, text = { Column(verticalArrangement = Arrangement.spacedBy(10.dp)) { OutlinedTextField(title, { title = it }, label = { Text("Goal") }, singleLine = true) } }, confirmButton = { Button(onClick = { onCreate(title) }, enabled = title.isNotBlank()) { Text("Create") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

@Composable
private fun EventCreateDialog(date: LocalDate, onDismiss: () -> Unit, onCreate: (String) -> Unit) {
    var title by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("New event") }, text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { Text(date.format(DateTimeFormatter.ofPattern("EEEE, MMMM d")), color = MaterialTheme.colorScheme.onSurfaceVariant); OutlinedTextField(title, { title = it }, label = { Text("Event name") }, singleLine = true) } }, confirmButton = { Button(onClick = { onCreate(title) }, enabled = title.isNotBlank()) { Text("Create at 10:00 AM") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}
