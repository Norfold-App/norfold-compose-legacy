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
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import com.norfold.app.ui.components.NorfoldDialog
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
import com.norfold.app.domain.WeekHourBands
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
                    Text("Docs, tasks, projects, files, calendar, canvas and conversations stay organized in one local-first workspace.", color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
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
                        listOf("Personal" to "Docs, tasks and daily planning", "Work" to "Tasks, docs and collaboration", "Study" to "Research and assignments", "Creative" to "Ideas, drafts and inspiration").forEach { (name, detail) ->
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
    BoxWithConstraints(Modifier.fillMaxSize()) {
    // Same threshold as NorfoldAppRoot: on compact widths the floating sidebar
    // button sits over the top-start corner, so the page header shifts right.
    val headerInset = if (maxWidth < 720.dp) 56.dp else 0.dp
    Column(Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        NorfoldPageHeader("Calendar", "Plan work and never miss a deadline.", Icons.Outlined.CalendarMonth, modifier = Modifier.padding(start = headerInset))
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
            "Week" -> WeekCalendar(
                selectedDate = selectedDate,
                events = events,
                onWeekSettled = { weekStart -> month = YearMonth.from(weekStart.plusDays(3)) },
                onSelect = { selectedDate = it; month = YearMonth.from(it) },
                onEventClick = { event ->
                    val obj = event.taskId?.let { taskId ->
                        state.workspaceObjects.firstOrNull {
                            it.objectType == com.norfold.app.domain.WorkspaceObjectType.Task && it.sourceId == taskId
                        }
                    }
                    if (obj != null) viewModel.openWorkspaceObject(obj)
                },
                modifier = Modifier.weight(1f),
            )
            else -> LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (mode == "Agenda") {
                    item { AgendaPanel(events.filter { !it.date.isBefore(selectedDate) }) }
                } else {
                    item {
                        PagedMonthPanel(
                            month = month,
                            selected = selectedDate,
                            events = events,
                            onMonthSettled = { settled -> month = settled },
                            onSelect = {
                                selectedDate = it
                                month = YearMonth.from(it)
                                mode = "Day"
                                viewModel.patchSettings { settings -> settings.copy(calendarDefaultView = "Day") }
                            },
                        )
                    }
                }
                item { DayAgenda(selectedDate, events.filter { it.date == selectedDate }) }
                item { Spacer(Modifier.height(96.dp)) }
            }
        }
    }
    }
    if (createOpen) EventCreateDialog(selectedDate, { createOpen = false }) { title ->
        val start = selectedDate.atTime(10, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        viewModel.createCalendarEvent(title, start, start + 3_600_000L)
        createOpen = false
    }
}

private data class PlanningEvent(val id: String, val title: String, val detail: String, val date: LocalDate, val time: LocalTime?, val color: Color, val source: String, val taskId: Long? = null)

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
                    taskId = task.id,
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

private val WeekHourRailWidth = 46.dp

/**
 * Week view rebuild (codex step 6): pager of week panels — swipe = prev/next
 * week. Each panel = sticky 7-day header (tap selects) + sticky all-day/
 * multi-day capsule band + vertically scrolling adaptive timed grid whose
 * hour rail collapses empty stretches ([WeekHourBands]).
 */
@Composable
private fun WeekCalendar(
    selectedDate: LocalDate,
    events: List<PlanningEvent>,
    onWeekSettled: (LocalDate) -> Unit,
    onSelect: (LocalDate) -> Unit,
    onEventClick: (PlanningEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val origin = remember { LocalDate.now().with(DayOfWeek.MONDAY) }
    CalendarPeriodPager(
        current = selectedDate.with(DayOfWeek.MONDAY),
        periodAt = { page -> origin.plusWeeks((page - CALENDAR_PAGER_CENTER).toLong()) },
        indexOf = { weekStart ->
            (CALENDAR_PAGER_CENTER + ChronoUnit.WEEKS.between(origin, weekStart))
                .coerceIn(0L, CALENDAR_PAGER_PAGES.toLong() - 1L)
                .toInt()
        },
        onSettled = onWeekSettled,
        modifier = modifier,
    ) { weekStart ->
        WeekPanel(weekStart, selectedDate, events, onSelect, onEventClick)
    }
}

@Composable
private fun WeekPanel(
    weekStart: LocalDate,
    selectedDate: LocalDate,
    events: List<PlanningEvent>,
    onSelect: (LocalDate) -> Unit,
    onEventClick: (PlanningEvent) -> Unit,
) {
    val weekDays = remember(weekStart) { (0L..6L).map { weekStart.plusDays(it) } }
    val weekEvents = remember(events, weekStart) { events.filter { it.date in weekDays } }
    val allDay = weekEvents.filter { it.time == null }
    val timed = weekEvents.filter { it.time != null }
    val bands = remember(weekEvents) {
        WeekHourBands.computeHourBands(weekDays.map { day -> timed.filter { it.date == day }.map { it.time!!.hour } })
    }
    Surface(
        modifier = Modifier.fillMaxSize(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Column {
            Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
                Spacer(Modifier.width(WeekHourRailWidth))
                weekDays.forEach { date ->
                    val selected = date == selectedDate
                    Column(
                        Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).clickable { onSelect(date) }.padding(vertical = 5.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            date.dayOfWeek.name.take(3).lowercase().replaceFirstChar { it.uppercase() },
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Box(
                            Modifier.padding(top = 3.dp).size(30.dp)
                                .background(if (selected) MaterialTheme.colorScheme.primary else Color.Transparent, CircleShape),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                date.dayOfMonth.toString(),
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp,
                                color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                }
            }
            WeekAllDayBand(weekDays, allDay, onEventClick)
            val gridScroll = rememberScrollState()
            Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(gridScroll)) {
                if (bands.size == 1 && !bands[0].busy && bands[0].label == WeekHourBands.NO_TIMED_TASKS_LABEL) {
                    Text(
                        WeekHourBands.NO_TIMED_TASKS_LABEL,
                        Modifier.fillMaxWidth().padding(vertical = 26.dp),
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp,
                    )
                } else {
                    bands.forEach { band ->
                        val bandHeight = if (band.busy) (56 * (band.endHour - band.startHour)).dp else 24.dp
                        Row(Modifier.fillMaxWidth().height(bandHeight)) {
                            Text(
                                band.label,
                                Modifier.width(WeekHourRailWidth).padding(top = 3.dp, end = 6.dp),
                                fontSize = 9.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.End,
                                maxLines = 1,
                            )
                            weekDays.forEach { day ->
                                Column(
                                    Modifier.weight(1f).fillMaxHeight()
                                        .border(BorderStroke(0.25.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)))
                                        .padding(1.dp),
                                    verticalArrangement = Arrangement.spacedBy(2.dp),
                                ) {
                                    if (band.busy) {
                                        timed
                                            .filter { it.date == day && it.time!!.hour >= band.startHour && it.time!!.hour < band.endHour }
                                            .forEach { event -> WeekEventChip(event, onEventClick) }
                                    }
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

/** Range/all-day tasks as Gantt-style capsules; lanes capped at 3 with "+N more". */
@Composable
private fun WeekAllDayBand(
    weekDays: List<LocalDate>,
    allDay: List<PlanningEvent>,
    onEventClick: (PlanningEvent) -> Unit,
) {
    if (allDay.isEmpty()) return
    // Multi-day tasks arrive exploded into one event per day sharing taskId —
    // regroup them so a range renders as a single capsule spanning its columns.
    val capsules = allDay
        .groupBy { it.taskId?.let { id -> "task-$id" } ?: it.id }
        .map { (_, dayEvents) ->
            val columns = dayEvents.map { weekDays.indexOf(it.date) }.filter { it >= 0 }
            WeekCapsule(dayEvents.first(), columns.min(), columns.max())
        }
        .sortedWith(compareBy({ it.start }, { it.start - it.end }))
    val lanes = mutableListOf<MutableList<WeekCapsule>>()
    var hidden = 0
    capsules.forEach { capsule ->
        val lane = lanes.firstOrNull { lane -> lane.none { it.start <= capsule.end && capsule.start <= it.end } }
        when {
            lane != null -> lane += capsule
            lanes.size < 3 -> lanes += mutableListOf(capsule)
            else -> hidden++
        }
    }
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        lanes.forEach { lane ->
            Row(Modifier.fillMaxWidth().height(22.dp)) {
                Spacer(Modifier.width(WeekHourRailWidth))
                var column = 0
                lane.sortedBy { it.start }.forEach { capsule ->
                    if (capsule.start > column) Spacer(Modifier.weight((capsule.start - column).toFloat()))
                    Surface(
                        onClick = { onEventClick(capsule.event) },
                        modifier = Modifier.weight((capsule.end - capsule.start + 1).toFloat()).fillMaxHeight().padding(horizontal = 1.dp),
                        shape = RoundedCornerShape(6.dp),
                        color = capsule.event.color.copy(alpha = 0.2f),
                        border = BorderStroke(1.dp, capsule.event.color.copy(alpha = 0.4f)),
                    ) {
                        Text(
                            capsule.event.title,
                            Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            fontSize = 9.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    column = capsule.end + 1
                }
                if (column < 7) Spacer(Modifier.weight((7 - column).toFloat()))
            }
        }
        if (hidden > 0) {
            Text(
                "+$hidden more",
                Modifier.padding(start = WeekHourRailWidth + 4.dp),
                fontSize = 9.sp,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

private data class WeekCapsule(val event: PlanningEvent, val start: Int, val end: Int)

@Composable
private fun WeekEventChip(event: PlanningEvent, onEventClick: (PlanningEvent) -> Unit) {
    Surface(
        onClick = { onEventClick(event) },
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(6.dp),
        color = event.color.copy(alpha = 0.16f),
        border = BorderStroke(1.dp, event.color.copy(alpha = 0.36f)),
    ) {
        Column(Modifier.padding(horizontal = 4.dp, vertical = 3.dp)) {
            Text(event.title, fontSize = 9.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
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

/**
 * Shared infinite-feel horizontal pager for calendar panels (month/week).
 * Pages are indexed around a large center so both directions swipe freely;
 * [periodAt] maps an index offset to the period value, [indexOf] the reverse.
 * When [current] changes externally (chevrons, day taps) the pager animates
 * to it; when the user settles on a page, [onSettled] reports the new period.
 */
@Composable
private fun <T> CalendarPeriodPager(
    current: T,
    periodAt: (Int) -> T,
    indexOf: (T) -> Int,
    onSettled: (T) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable (T) -> Unit,
) {
    val pagerState = rememberPagerState(initialPage = indexOf(current), pageCount = { CALENDAR_PAGER_PAGES })
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.settledPage }
            .distinctUntilChanged()
            .collect { page -> onSettled(periodAt(page)) }
    }
    LaunchedEffect(current) {
        val target = indexOf(current)
        if (target != pagerState.currentPage && !pagerState.isScrollInProgress) {
            pagerState.animateScrollToPage(target)
        }
    }
    HorizontalPager(state = pagerState, modifier = modifier, beyondViewportPageCount = 1) { page ->
        content(periodAt(page))
    }
}

private const val CALENDAR_PAGER_PAGES = 24_000
private const val CALENDAR_PAGER_CENTER = CALENDAR_PAGER_PAGES / 2

/** MonthPanel wrapped in the shared pager: swipe = prev/next month, header follows the settled page. */
@Composable
private fun PagedMonthPanel(
    month: YearMonth,
    selected: LocalDate,
    events: List<PlanningEvent>,
    onMonthSettled: (YearMonth) -> Unit,
    onSelect: (LocalDate) -> Unit,
) {
    val origin = remember { YearMonth.now() }
    CalendarPeriodPager(
        current = month,
        periodAt = { page -> origin.plusMonths((page - CALENDAR_PAGER_CENTER).toLong()) },
        indexOf = { m ->
            (CALENDAR_PAGER_CENTER + ChronoUnit.MONTHS.between(origin, m))
                .coerceIn(0L, CALENDAR_PAGER_PAGES.toLong() - 1L)
                .toInt()
        },
        onSettled = onMonthSettled,
    ) { pageMonth ->
        MonthPanel(pageMonth, selected, events, onSelect)
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
    NorfoldDialog(onDismissRequest = onDismiss, title = { Text("New goal") }, text = { Column(verticalArrangement = Arrangement.spacedBy(10.dp)) { OutlinedTextField(title, { title = it }, label = { Text("Goal") }, singleLine = true) } }, confirmButton = { Button(onClick = { onCreate(title) }, enabled = title.isNotBlank()) { Text("Create") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

@Composable
private fun EventCreateDialog(date: LocalDate, onDismiss: () -> Unit, onCreate: (String) -> Unit) {
    var title by remember { mutableStateOf("") }
    NorfoldDialog(onDismissRequest = onDismiss, title = { Text("New event") }, text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { Text(date.format(DateTimeFormatter.ofPattern("EEEE, MMMM d")), color = MaterialTheme.colorScheme.onSurfaceVariant); OutlinedTextField(title, { title = it }, label = { Text("Event name") }, singleLine = true) } }, confirmButton = { Button(onClick = { onCreate(title) }, enabled = title.isNotBlank()) { Text("Create at 10:00 AM") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}
