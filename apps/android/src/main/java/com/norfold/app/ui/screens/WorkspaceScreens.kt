package com.norfold.app.ui.screens

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.ActivityInfo
import android.net.Uri
import androidx.activity.compose.BackHandler

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.automirrored.outlined.Sort
import androidx.compose.material.icons.automirrored.outlined.Note
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.DoneAll
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.CloudDone
import androidx.compose.material.icons.outlined.Difference
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DragIndicator
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.ShapeLine
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.zIndex
import com.norfold.app.ui.components.NorfoldContentDialog
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.norfold.app.data.displayName
import com.norfold.app.domain.ChatMessageItem
import com.norfold.app.domain.ConflictSideSummary
import com.norfold.app.domain.Destination
import com.norfold.app.ui.DocsUiState
import com.norfold.app.ui.DocsViewModel
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

@Composable
fun ChatScreen(state: DocsUiState, viewModel: DocsViewModel, onPickAttachment: () -> Unit = {}) {
    var message by remember { mutableStateOf("") }
    var toolsExpanded by remember { mutableStateOf(false) }
    val imeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    Column(
        Modifier
            .fillMaxSize()
            .imePadding(),
    ) {
        ChatPersonaBar(onBack = viewModel::handleBack)
        // Message list grows to fill; input pinned at the bottom above the keyboard.
        val messages = state.chatMessages
        val listState = androidx.compose.foundation.lazy.rememberLazyListState()
        androidx.compose.runtime.LaunchedEffect(messages.size) {
            if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex)
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 10.dp),
        ) {
            if (messages.isEmpty()) {
                item {
                    ChatEmptyState(onPrompt = { message = it })
                }
            } else {
                item {
                    Box(Modifier.fillMaxWidth().padding(vertical = 6.dp), contentAlignment = Alignment.Center) {
                        Surface(shape = RoundedCornerShape(999.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                            Text("Today", Modifier.padding(horizontal = 14.dp, vertical = 5.dp), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            itemsIndexed(messages, key = { _, it -> it.id }) { index, chat ->
                val prev = messages.getOrNull(index - 1)
                val next = messages.getOrNull(index + 1)
                val firstOfGroup = prev?.authorUsername != chat.authorUsername
                val lastOfGroup = next?.authorUsername != chat.authorUsername
                ChatBubble(chat, firstOfGroup, lastOfGroup)
            }
        }
        val composerModifier = Modifier
            .fillMaxWidth()
            .then(if (imeVisible) Modifier.padding(bottom = 10.dp) else Modifier.navigationBarsPadding())
        Surface(
            composerModifier.padding(horizontal = 8.dp, vertical = 6.dp),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.98f),
            shape = RoundedCornerShape(26.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            tonalElevation = 3.dp,
            shadowElevation = 12.dp,
        ) {
            Column(Modifier.fillMaxWidth().animateContentSize().padding(horizontal = 8.dp, vertical = 7.dp)) {
                if (toolsExpanded) {
                    LazyRow(
                        modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        item { AssistChip(onClick = onPickAttachment, label = { Text("Attach") }, leadingIcon = { Icon(Icons.Outlined.AttachFile, null, Modifier.size(17.dp)) }) }
                        items(listOf("Share an update", "Ask a question", "Capture a decision")) { prompt ->
                            AssistChip(onClick = { message = "$prompt: "; toolsExpanded = false }, label = { Text(prompt) })
                        }
                    }
                }
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                IconButton(
                    onClick = { toolsExpanded = !toolsExpanded },
                    modifier = Modifier.size(44.dp).clip(CircleShape).background(if (toolsExpanded) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant),
                ) {
                    Icon(if (toolsExpanded) Icons.Outlined.Close else Icons.Outlined.Add, if (toolsExpanded) "Close chat tools" else "Open chat tools", tint = if (toolsExpanded) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant)
                }
                OutlinedTextField(
                    value = message,
                    onValueChange = { message = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Message workspace…", fontSize = 14.sp) },
                    maxLines = 4,
                    shape = RoundedCornerShape(22.dp),
                )
                val canSend = message.isNotBlank()
                IconButton(
                    onClick = { viewModel.sendChat(message); message = "" },
                    enabled = canSend,
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(if (canSend) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant),
                ) {
                    Icon(Icons.AutoMirrored.Outlined.Send, null, tint = if (canSend) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                }
                }
            }
        }
    }
}

@Composable
private fun ChatEmptyState(onPrompt: (String) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 42.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.primaryContainer,
        ) {
            Icon(
                Icons.Outlined.ChatBubbleOutline,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.padding(18.dp).size(34.dp),
            )
        }
        Text("Start the workspace conversation", fontWeight = FontWeight.Black, fontSize = 20.sp)
        Text(
            "Capture updates, questions, and decisions beside the work they belong to. Messages stay in this workspace and are included in encrypted backups.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 14.sp,
            modifier = Modifier.widthIn(max = 520.dp),
        )
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(top = 4.dp),
        ) {
            items(listOf("Share an update", "Ask a question", "Capture a decision")) { prompt ->
                AssistChip(onClick = { onPrompt("$prompt: ") }, label = { Text(prompt) })
            }
        }
    }
}

@Composable
private fun ChatPersonaBar(onBack: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.background) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            IconButton(
                onClick = onBack,
                modifier = Modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surface),
            ) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back", modifier = Modifier.size(20.dp))
            }
            Box(Modifier.size(44.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary), contentAlignment = Alignment.Center) {
                Text("W", color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            }
            Column(Modifier.weight(1f)) {
                Text("Workspace chat", fontWeight = FontWeight.Black, fontSize = 18.sp, maxLines = 1)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(Color(0xFF2FBF71)))
                    Text("Local collaboration", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                }
            }
        }
    }
}

@Composable
fun ConflictReviewScreen(state: DocsUiState, viewModel: DocsViewModel) {
    val localChanges = state.workspaceObjectHistory.sortedByDescending { it.createdAt }.take(8)
    val recentActivity = state.workspaceActivities.sortedByDescending { it.createdAt }.take(5)
    val report = state.conflictReport
    WorkspaceScreenFrame("Conflict review", "Resolve sync-chain merge differences") {
        item {
            SectionCard {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(Icons.Outlined.Difference, null, tint = MaterialTheme.colorScheme.primary)
                    Column(Modifier.weight(1f)) {
                        Text("Sync chain conflict", fontWeight = FontWeight.Black, fontSize = 20.sp)
                        Text("${state.settings.syncProvider.displayName} · ${state.settings.syncDeviceName}", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                    }
                }
                Text("Status: ${state.settings.lastSyncStatus}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = viewModel::loadConflictReport, modifier = Modifier.weight(1f)) { Text("Load report") }
                    FilledTonalButton(onClick = { viewModel.go(Destination.SyncMonitor) }, modifier = Modifier.weight(1f)) { Text("Sync monitor") }
                }
                if (report != null) {
                    Text(
                        "${report.format} · ${report.deviceName.ifBlank { "Unknown device" }} · ${relativeSyncTime(report.createdAt)}",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp,
                    )
                    Text(
                        "Local ${report.localHash.take(8)} · Remote ${report.remoteHash.take(8)}",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp,
                    )
                }
                DifferenceLine("- Remote snapshot changed outside this local base", Color(0xFFFF6780).copy(alpha = 0.20f))
                DifferenceLine("+ Local snapshot also has changes waiting to sync", Color(0xFF56CC98).copy(alpha = 0.20f))
                DifferenceLine("+ New conflict files include object counts and changed item lists", Color(0xFF56CC98).copy(alpha = 0.20f))
            }
        }
        item {
            SectionCard {
                Text("Local snapshot", fontWeight = FontWeight.Black, fontSize = 18.sp)
                if (report != null) {
                    ConflictSideStats(report.local)
                } else {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ConflictStat("Docs", state.notes.size.toString(), Modifier.weight(1f))
                        ConflictStat("Tasks", state.tasks.size.toString(), Modifier.weight(1f))
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ConflictStat("Files", state.workspaceFiles.size.toString(), Modifier.weight(1f))
                        ConflictStat("Links", state.workspaceObjectLinks.size.toString(), Modifier.weight(1f))
                        ConflictStat("History", state.workspaceObjectHistory.size.toString(), Modifier.weight(1f))
                    }
                }
            }
        }
        item {
            SectionCard {
                Text("Recent local changes", fontWeight = FontWeight.Black, fontSize = 18.sp)
                if (localChanges.isEmpty()) {
                    Text("No local object history is available yet.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                } else {
                    localChanges.forEach { event ->
                        DifferenceLine("+ ${event.historyType.name}: ${event.summary}", Color(0xFF56CC98).copy(alpha = 0.16f))
                    }
                }
            }
        }
        item {
            SectionCard {
                Text("Remote side", fontWeight = FontWeight.Black, fontSize = 18.sp)
                if (report == null) {
                    DifferenceLine("- Load the latest Norfold conflict JSON to show decrypted remote counts and changed item labels.", Color(0xFFFF6780).copy(alpha = 0.17f))
                    recentActivity.forEach { activity ->
                        Text("${activity.activityType.name} · ${activity.title} · ${relativeSyncTime(activity.createdAt)}", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                    }
                } else {
                    ConflictSideStats(report.remote)
                    ConflictReportList("Only on this device", report.diff.localOnly, "+", Color(0xFF56CC98).copy(alpha = 0.16f))
                    ConflictReportList("Only on remote", report.diff.remoteOnly, "-", Color(0xFFFF6780).copy(alpha = 0.17f))
                    ConflictReportList("Newer locally", report.diff.localNewer, "+", Color(0xFF56CC98).copy(alpha = 0.16f))
                    ConflictReportList("Newer remotely", report.diff.remoteNewer, "-", Color(0xFFFF6780).copy(alpha = 0.17f))
                    Text("${report.diff.changedCount} changed shared objects found in the conflict report.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                }
            }
        }
        item {
            SectionCard {
                Text("Resolution", fontWeight = FontWeight.Black, fontSize = 18.sp)
                Text("Choose local only if this device should overwrite the remote encrypted snapshot on the next manual sync. Use restore if the remote copy should win.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = viewModel::useLocalForNextConflictSync, modifier = Modifier.weight(1f)) { Text("Use local next sync") }
                    FilledTonalButton(onClick = { viewModel.go(Destination.SyncMonitor) }, modifier = Modifier.weight(1f)) { Text("Monitor") }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = { viewModel.go(Destination.Settings) }) { Text("Sync settings") }
                    FilledTonalButton(onClick = { viewModel.go(Destination.ImportExport) }) { Text("Backup / import") }
                }
            }
        }
    }
}

@Composable
private fun ConflictStat(label: String, value: String, modifier: Modifier = Modifier) {
    Surface(modifier, shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.46f)) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(value, fontWeight = FontWeight.Black, fontSize = 18.sp)
            Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
        }
    }
}

@Composable
private fun ColumnScope.ConflictSideStats(summary: ConflictSideSummary) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ConflictStat("Docs", summary.notes.toString(), Modifier.weight(1f))
        ConflictStat("Tasks", summary.tasks.toString(), Modifier.weight(1f))
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ConflictStat("Files", summary.files.toString(), Modifier.weight(1f))
        ConflictStat("Links", summary.objectLinks.toString(), Modifier.weight(1f))
        ConflictStat("History", summary.history.toString(), Modifier.weight(1f))
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ConflictStat("Goals", summary.goals.toString(), Modifier.weight(1f))
        ConflictStat("Events", summary.calendarEvents.toString(), Modifier.weight(1f))
    }
    if (summary.recent.isNotEmpty()) {
        Text("Recent", fontWeight = FontWeight.Bold, fontSize = 13.sp)
        summary.recent.take(5).forEach { label ->
            Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
        }
    }
}

@Composable
private fun ColumnScope.ConflictReportList(title: String, items: List<String>, prefix: String, color: Color) {
    if (items.isEmpty()) return
    Text(title, fontWeight = FontWeight.Bold, fontSize = 13.sp)
    items.forEach { item ->
        DifferenceLine("$prefix $item", color)
    }
}

@Composable
fun SyncMonitorScreen(state: DocsUiState, viewModel: DocsViewModel) {
    val connectedDevices = listOf(
        state.settings.syncDeviceName.ifBlank { "This Android device" } to "Local workspace · active now",
    )
    WorkspaceScreenFrame("Sync Center", "") {
        item {
            SectionCard {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                    SyncStatusRing(syncing = state.syncing, conflicts = state.settings.syncConflictCount)
                    Text(
                        if (state.settings.syncConflictCount > 0) "Needs attention" else if (state.syncing) "Syncing" else "All synced",
                        fontWeight = FontWeight.Black,
                        fontSize = 22.sp,
                        color = if (state.settings.syncConflictCount > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.tertiary,
                    )
                    Text(
                        state.settings.lastSyncAt?.let { "Last sync: ${relativeSyncTime(it)}" } ?: "No sync yet",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp,
                    )
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SyncStatCard("Objects", state.workspaceObjects.size.toString(), Modifier.weight(1f))
                    SyncStatCard("Files", state.workspaceFiles.size.toString(), Modifier.weight(1f))
                    SyncStatCard("Conflicts", state.settings.syncConflictCount.toString(), Modifier.weight(1f))
                    SyncStatCard("Devices", connectedDevices.size.toString(), Modifier.weight(1f))
                }
            }
        }
        item {
            SectionCard {
                Text("Registered devices", fontWeight = FontWeight.Black, fontSize = 18.sp)
                connectedDevices.forEach { (name, status) ->
                    ConnectedDeviceRow(name, status)
                }
            }
        }
        item {
            SectionCard {
                Text("Sync history", fontWeight = FontWeight.Black, fontSize = 18.sp)
                val latest = state.workspaceActivities.take(4)
                if (latest.isEmpty()) {
                    Text("No sync activity recorded yet.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                } else {
                    latest.forEach { activity ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Box(Modifier.size(10.dp).clip(CircleShape).background(MaterialTheme.colorScheme.tertiary))
                            Column(Modifier.weight(1f)) {
                                Text(activity.title, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                                Text("${activity.actor} · ${relativeSyncTime(activity.createdAt)}", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
                            }
                        }
                    }
                }
                if (state.settings.syncConflictCount > 0) {
                    Button(onClick = { viewModel.go(Destination.ConflictReview) }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) { Text("Open conflict review") }
                } else {
                    FilledTonalButton(onClick = { viewModel.go(Destination.Settings) }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) { Text("Sync settings") }
                }
            }
        }
    }
}

@Composable
private fun SyncStatusRing(syncing: Boolean, conflicts: Int) {
    val accent = MaterialTheme.colorScheme.primary
    Box(Modifier.size(168.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = size.minDimension * 0.085f
            val inset = stroke / 2
            val arcSize = androidx.compose.ui.geometry.Size(size.width - stroke, size.height - stroke)
            drawArc(
                Color.White.copy(alpha = 0.10f),
                0f,
                360f,
                false,
                topLeft = Offset(inset, inset),
                size = arcSize,
                style = Stroke(stroke, cap = StrokeCap.Round),
            )
            drawArc(
                accent,
                -90f,
                if (syncing) 230f else 292f,
                false,
                topLeft = Offset(inset, inset),
                size = arcSize,
                style = Stroke(stroke, cap = StrokeCap.Round),
            )
            drawArc(
                if (conflicts > 0) Color(0xFFFF6780) else Color(0xFF56CC98),
                if (syncing) 158f else 220f,
                if (conflicts > 0) 58f else 128f,
                false,
                topLeft = Offset(inset, inset),
                size = arcSize,
                style = Stroke(stroke, cap = StrokeCap.Round),
            )
        }
        Box(
            Modifier
                .size(92.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.32f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Outlined.CloudDone, null, tint = if (conflicts > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.tertiary, modifier = Modifier.size(42.dp))
        }
    }
}

@Composable
private fun SyncStatCard(label: String, value: String, modifier: Modifier = Modifier) {
    Surface(modifier, shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.28f)) {
        Column(Modifier.padding(horizontal = 8.dp, vertical = 10.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(value, fontWeight = FontWeight.Black, fontSize = 17.sp)
            Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp, maxLines = 1)
        }
    }
}

@Composable
private fun ConnectedDeviceRow(name: String, status: String) {
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.22f)) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.size(34.dp).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.CloudDone, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(name, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                Text(status, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
            }
        }
    }
}

@Composable
private fun WorkspaceScreenFrame(
    title: String,
    subtitle: String,
    content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit,
) {
    LazyColumn(
        Modifier
            .fillMaxSize()
            .padding(start = 18.dp, end = 18.dp, top = 58.dp, bottom = 10.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (subtitle.isNotBlank()) {
            item {
                Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp, modifier = Modifier.padding(start = 4.dp))
            }
        }
        content()
    }
}

@Composable
private fun SectionCard(spacing: androidx.compose.ui.unit.Dp = 12.dp, content: @Composable ColumnScope.() -> Unit) {
    Card(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
    ) {
        Column(
            Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(spacing),
            content = content,
        )
    }
}

@Composable
private fun ChatBubble(chat: ChatMessageItem, firstOfGroup: Boolean, lastOfGroup: Boolean) {
    if (chat.system) {
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.Center) {
            Text(
                chat.body,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                    .padding(horizontal = 12.dp, vertical = 5.dp),
            )
        }
        return
    }
    val mine = chat.authorUsername.equals("you", true)
    val bubbleColor = if (mine) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
    val textColor = if (mine) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
    val tailBottom = 5.dp
    val roundTop = if (firstOfGroup) 18.dp else 6.dp
    Row(
        Modifier.fillMaxWidth().padding(top = if (firstOfGroup) 8.dp else 1.dp),
        horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Bottom,
    ) {
        // Avatar gutter (incoming only), rendered on the last message of a run
        if (!mine) {
            if (lastOfGroup) {
                Box(
                    Modifier.size(28.dp).clip(CircleShape).background(Color(chat.color)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(chat.authorDisplayName.take(1).uppercase(), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }
            } else {
                Spacer(Modifier.width(28.dp))
            }
            Spacer(Modifier.width(6.dp))
        }
        Column(horizontalAlignment = if (mine) Alignment.End else Alignment.Start) {
            if (firstOfGroup && !mine) {
                Text(
                    chat.authorDisplayName,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 12.sp,
                    color = Color(chat.color),
                    modifier = Modifier.padding(start = 10.dp, bottom = 2.dp),
                )
            }
            Surface(
                modifier = Modifier.widthIn(max = 300.dp),
                shape = RoundedCornerShape(
                    topStart = if (mine) 18.dp else roundTop,
                    topEnd = if (mine) roundTop else 18.dp,
                    bottomStart = if (mine || !lastOfGroup) 18.dp else tailBottom,
                    bottomEnd = if (!mine || !lastOfGroup) 18.dp else tailBottom,
                ),
                color = bubbleColor,
            ) {
                Row(
                    Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Column(Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (chat.body.isNotBlank()) Text(chat.body, color = textColor, fontSize = 14.sp, lineHeight = 19.sp)
                        if (!chat.attachmentName.isNullOrBlank()) {
                            ChatAttachmentCard(chat, mine)
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp), modifier = Modifier.padding(top = 4.dp)) {
                        Text(
                            relativeSyncTime(chat.createdAt),
                            fontSize = 9.sp,
                            color = textColor.copy(alpha = 0.6f),
                        )
                        if (mine) {
                            Icon(Icons.Outlined.DoneAll, "Read", tint = textColor.copy(alpha = 0.7f), modifier = Modifier.size(12.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ChatAttachmentCard(chat: ChatMessageItem, mine: Boolean) {
    val fg = if (mine) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
    val bg = if (mine) MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.16f) else MaterialTheme.colorScheme.surface
    Surface(shape = RoundedCornerShape(12.dp), color = bg) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Outlined.AttachFile, null, tint = fg, modifier = Modifier.size(18.dp))
            Column {
                Text(chat.attachmentName.orEmpty(), color = fg, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                Text(formatBytes(chat.attachmentSizeBytes ?: 0), color = fg.copy(alpha = 0.7f), fontSize = 10.sp)
            }
        }
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes <= 0 -> "File"
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    else -> "${bytes / (1024 * 1024)} MB"
}

@Composable
private fun DifferenceLine(text: String, color: Color) {
    Text(
        text,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(color)
            .border(1.dp, color.copy(alpha = 0.45f), RoundedCornerShape(12.dp))
            .padding(12.dp),
        fontWeight = FontWeight.SemiBold,
        fontSize = 13.sp,
    )
}

private fun relativeSyncTime(timestamp: Long): String {
    val minutes = ((System.currentTimeMillis() - timestamp).coerceAtLeast(0) / 60_000).toInt()
    return when {
        minutes < 1 -> "Just now"
        minutes < 60 -> "${minutes}m"
        minutes < 1440 -> "${minutes / 60}h"
        else -> "${minutes / 1440}d"
    }
}



private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
