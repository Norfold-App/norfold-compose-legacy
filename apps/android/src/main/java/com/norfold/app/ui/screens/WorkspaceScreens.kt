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
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.norfold.app.data.displayName
import com.norfold.app.domain.CanvasEdgeItem
import com.norfold.app.domain.CanvasNodeItem
import com.norfold.app.domain.CanvasNodeType
import com.norfold.app.domain.ChatMessageItem
import com.norfold.app.domain.ConflictSideSummary
import com.norfold.app.domain.Destination
import com.norfold.app.ui.NotesUiState
import com.norfold.app.ui.NotesViewModel
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

@Composable
fun CanvasBoardScreen(state: NotesUiState, viewModel: NotesViewModel, onPickCanvasTarget: (CanvasNodeItem) -> Unit = {}) {
    var selectedNode by remember { mutableStateOf<CanvasNodeItem?>(null) }
    var viewportOffset by remember { mutableStateOf(Offset(18f, 18f)) }
    var viewportScale by remember { mutableStateOf(1f) }
    var focusMode by remember { mutableStateOf(false) }
    val activity = LocalContext.current.findActivity()
    BackHandler(enabled = focusMode) { focusMode = false }
    DisposableEffect(focusMode, activity) {
        val previous = activity?.requestedOrientation
        if (focusMode) activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        onDispose {
            if (focusMode && previous != null) activity.requestedOrientation = previous
        }
    }
    selectedNode?.let { node ->
        CanvasNodeDetailDialog(
            node = node,
            allNodes = state.canvasNodes,
            edges = state.canvasEdges,
            linkedNote = node.linkedNoteId?.let { id -> state.notes.firstOrNull { it.id == id } },
            viewModel = viewModel,
            onPickTarget = onPickCanvasTarget,
            onDismiss = { selectedNode = null },
        )
    }
    WorkspaceScreenFrame("Canvas", "") {
        item {
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val boardHeight = when {
                    focusMode -> 760.dp
                    maxWidth < 380.dp -> 520.dp
                    maxWidth < 620.dp -> 600.dp
                    else -> 680.dp
                }
                Card(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = boardHeight, max = boardHeight),
                    shape = RoundedCornerShape(28.dp),
                ) {
                    BoxWithConstraints(
                        Modifier
                            .fillMaxSize()
                            .padding(16.dp)
                            .pointerInput(state.canvasNodes, viewportScale) {
                                detectTransformGestures { _, pan, zoom, _ ->
                                    viewportScale = (viewportScale * zoom).coerceIn(0.55f, 2.25f)
                                    viewportOffset = constrainCanvasOffset(
                                        viewportOffset + pan,
                                        canvasWorldBounds(state.canvasNodes),
                                        size.width.toFloat(),
                                        size.height.toFloat(),
                                        viewportScale,
                                    )
                                }
                            },
                    ) {
                        val density = LocalDensity.current
                        val localPositions = remember { mutableStateMapOf<Long, Pair<Float, Float>>() }
                        val nodes = remember(state.canvasNodes) { adjustedCanvasNodes(state.canvasNodes) }
                        val effectiveNodes = nodes.map { node ->
                            localPositions[node.id]?.let { (x, y) -> node.copy(x = x, y = y) } ?: node
                        }
                        val nodeMap = effectiveNodes.associateBy { it.id }
                        val nodeWidth = when {
                            maxWidth < 420.dp -> maxWidth * 0.56f
                            maxWidth < 720.dp -> maxWidth * 0.40f
                            else -> 230.dp
                        }
                        val nodeHeight = 112.dp
                        val toolbarHeight = 84.dp
                        val boardWidthPx = with(density) { maxWidth.toPx() - 32.dp.toPx() }
                        val boardHeightPx = with(density) { boardHeight.toPx() - 32.dp.toPx() }
                        val nodeWidthPx = with(density) { nodeWidth.toPx() }
                        val nodeHeightPx = with(density) { nodeHeight.toPx() }
                        val toolbarHeightPx = with(density) { toolbarHeight.toPx() }
                        val worldUnitPx = min(boardWidthPx, boardHeightPx - toolbarHeightPx).coerceAtLeast(1f)
                        fun centerCanvas() {
                            viewportScale = 1f
                            viewportOffset = fitCanvasOffset(
                                canvasWorldBounds(effectiveNodes),
                                boardWidthPx,
                                boardHeightPx,
                                nodeWidthPx,
                                nodeHeightPx,
                                toolbarHeightPx,
                                worldUnitPx,
                            )
                        }
                        val worldToScreen: (CanvasNodeItem) -> Offset = { node ->
                            Offset(
                                x = viewportOffset.x + node.x * worldUnitPx * viewportScale,
                                y = viewportOffset.y + node.y * worldUnitPx * viewportScale,
                            )
                        }
                        val nodeRects = effectiveNodes.associate { node ->
                            val position = worldToScreen(node)
                            node.id to CanvasNodeRect(
                                position.x,
                                position.y,
                                position.x + nodeWidthPx,
                                position.y + nodeHeightPx,
                            )
                        }

                        val gridColor = MaterialTheme.colorScheme.outlineVariant
                        Canvas(Modifier.fillMaxSize()) {
                            val grid = (72.dp.toPx() * viewportScale).coerceIn(36.dp.toPx(), 144.dp.toPx())
                            val startX = viewportOffset.x % grid - grid
                            val startY = viewportOffset.y % grid - grid
                            var x = startX
                            while (x <= size.width + grid) {
                                drawLine(gridColor.copy(alpha = 0.55f), Offset(x, 0f), Offset(x, size.height), 1.dp.toPx())
                                x += grid
                            }
                            var y = startY
                            while (y <= size.height + grid) {
                                drawLine(gridColor.copy(alpha = 0.55f), Offset(0f, y), Offset(size.width, y), 1.dp.toPx())
                                y += grid
                            }
                        }

                        Canvas(Modifier.fillMaxSize()) {
                            state.canvasEdges.forEachIndexed { index, edge ->
                                val from = nodeMap[edge.fromNodeId] ?: return@forEachIndexed
                                val to = nodeMap[edge.toNodeId] ?: return@forEachIndexed
                                val fromRect = nodeRects[from.id] ?: return@forEachIndexed
                                val toRect = nodeRects[to.id] ?: return@forEachIndexed
                                val points = routeCanvasEdge(
                                    fromRect = fromRect,
                                    toRect = toRect,
                                    obstacles = nodeRects.filterKeys { it != from.id && it != to.id }.values.toList(),
                                    boardWidth = size.width,
                                    boardHeight = size.height,
                                    toolbarHeight = toolbarHeightPx,
                                    laneIndex = index,
                                )
                                val path = Path().apply {
                                    moveTo(points.first().x, points.first().y)
                                    points.drop(1).forEach { lineTo(it.x, it.y) }
                                }
                                drawPath(path, Color(edge.color).copy(alpha = 0.70f), style = Stroke(width = 4.dp.toPx(), cap = StrokeCap.Round))
                            }
                        }

                        effectiveNodes.forEach { node ->
                            val position = worldToScreen(node)
                            CanvasNodeCard(
                                node = node,
                                width = nodeWidth,
                                modifier = Modifier
                                    .offset { IntOffset(position.x.roundToInt(), position.y.roundToInt()) }
                                    .pointerInput(node.id, viewportScale, worldUnitPx) {
                                        detectDragGestures(
                                            onDragEnd = {
                                                val pos = localPositions[node.id]
                                                if (pos != null) {
                                                    viewModel.moveCanvasNode(node, pos.first, pos.second)
                                                    localPositions.remove(node.id)
                                                }
                                            },
                                            onDragCancel = { localPositions.remove(node.id) },
                                        ) { change, dragAmount ->
                                            change.consume()
                                            val current = localPositions[node.id] ?: (node.x to node.y)
                                            val dx = dragAmount.x / (worldUnitPx * viewportScale).coerceAtLeast(1f)
                                            val dy = dragAmount.y / (worldUnitPx * viewportScale).coerceAtLeast(1f)
                                            localPositions[node.id] = current.first + dx to current.second + dy
                                        }
                                    },
                                onClick = { selectedNode = node },
                            )
                        }

                        Surface(
                            Modifier
                                .align(Alignment.TopEnd)
                                .padding(4.dp),
                            shape = RoundedCornerShape(18.dp),
                            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                            shadowElevation = 4.dp,
                        ) {
                            Row(
                                Modifier
                                    .padding(6.dp)
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text("${(viewportScale * 100).roundToInt()}%", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                TextButton(onClick = { focusMode = !focusMode }) { Text(if (focusMode) "Exit" else "Landscape") }
                                TextButton(onClick = { viewportScale = (viewportScale * 0.85f).coerceIn(0.55f, 2.25f) }) { Text("-") }
                                TextButton(onClick = { viewportScale = (viewportScale * 1.15f).coerceIn(0.55f, 2.25f) }) { Text("+") }
                                TextButton(onClick = ::centerCanvas) { Text("Center") }
                                TextButton(onClick = { viewportScale = 1f; viewportOffset = Offset(18f, 18f) }) { Text("Origin") }
                            }
                        }

                        if (focusMode) {
                            Surface(
                                Modifier
                                    .align(Alignment.TopStart)
                                    .padding(4.dp),
                                shape = RoundedCornerShape(18.dp),
                                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                                shadowElevation = 4.dp,
                            ) {
                                TextButton(onClick = { focusMode = false }) { Text("Back") }
                            }
                        }

                    if (effectiveNodes.isEmpty()) {
                        Column(
                            Modifier.align(Alignment.Center).padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text("Canvas is ready", fontWeight = FontWeight.Black, fontSize = 20.sp)
                            Text("Add docs, files, media, links, or shapes.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                        }
                    }

                        // Bottom toolbar with icons
                        Surface(
                            Modifier
                                .align(Alignment.BottomCenter)
                                .fillMaxWidth()
                                .padding(horizontal = 8.dp),
                            shape = RoundedCornerShape(24.dp),
                            color = MaterialTheme.colorScheme.surface,
                            shadowElevation = 8.dp,
                        ) {
                            Row(
                                Modifier.padding(horizontal = 8.dp, vertical = 10.dp),
                                horizontalArrangement = Arrangement.SpaceEvenly,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                CanvasTool(Icons.Outlined.TextFields, "Text") { viewModel.addCanvasNode(CanvasNodeType.Text) }
                                CanvasTool(Icons.AutoMirrored.Outlined.Note, "Doc") { viewModel.addCanvasNode(CanvasNodeType.Note) }
                                CanvasTool(Icons.Outlined.AttachFile, "File") { viewModel.addCanvasNode(CanvasNodeType.File) }
                                CanvasTool(Icons.Outlined.Image, "Media") { viewModel.addCanvasNode(CanvasNodeType.Media) }
                                CanvasTool(Icons.Outlined.Link, "Link") { viewModel.addCanvasNode(CanvasNodeType.Link) }
                                CanvasTool(Icons.Outlined.ShapeLine, "Shape") { viewModel.addCanvasNode(CanvasNodeType.Shape) }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CanvasNodeDetailDialog(
    node: CanvasNodeItem,
    allNodes: List<CanvasNodeItem>,
    edges: List<CanvasEdgeItem>,
    linkedNote: com.norfold.app.domain.Note?,
    viewModel: NotesViewModel,
    onPickTarget: (CanvasNodeItem) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var title by remember(node.id) { mutableStateOf(node.title) }
    var subtitle by remember(node.id) { mutableStateOf(node.subtitle) }
    var target by remember(node.id) { mutableStateOf(node.targetUri.orEmpty()) }
    fun openTarget() {
        val uri = target.ifBlank { node.targetUri.orEmpty() }
        if (uri.isNotBlank()) {
            runCatching {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uri)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
            }.onFailure { viewModel.showMessage(it.localizedMessage ?: "Cannot open target") }
        }
    }
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            shadowElevation = 16.dp,
        ) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(Modifier.size(14.dp).clip(CircleShape).background(Color(node.color)))
                    Column(Modifier.weight(1f)) {
                        Text("${node.type.displayLabel()} block", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("Canvas item", fontWeight = FontWeight.Black, fontSize = 22.sp)
                    }
                    TextButton(
                        onClick = {
                            viewModel.openCanvasNodeObject(node)
                            onDismiss()
                        },
                    ) {
                        Text("Object")
                    }
                }
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Title") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                )
                OutlinedTextField(
                    value = subtitle,
                    onValueChange = { subtitle = it },
                    label = { Text("Content / description") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                    maxLines = 6,
                    shape = RoundedCornerShape(14.dp),
                )
                if (node.type == CanvasNodeType.Link || node.type == CanvasNodeType.File || node.type == CanvasNodeType.Media) {
                    OutlinedTextField(
                        value = target,
                        onValueChange = { target = it },
                        label = { Text(if (node.type == CanvasNodeType.Link) "URL" else "Target URI") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp),
                    )
                    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)) {
                        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Target", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            Text(
                                node.targetName ?: target.ifBlank { "No file or link attached" },
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 12.sp,
                                maxLines = 2,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            )
                            if (!node.targetMimeType.isNullOrBlank() || node.targetSizeBytes != null) {
                                Text(
                                    "${node.targetMimeType.orEmpty()} · ${formatBytes(node.targetSizeBytes ?: 0)}",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontSize = 11.sp,
                                    maxLines = 1,
                                )
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                                if (node.type != CanvasNodeType.Link) {
                                    ElevatedButton(onClick = { onPickTarget(node) }, modifier = Modifier.weight(1f)) {
                                        Text(if (node.targetUri.isNullOrBlank()) "Attach" else "Replace")
                                    }
                                }
                                ElevatedButton(
                                    onClick = ::openTarget,
                                    enabled = target.isNotBlank() || !node.targetUri.isNullOrBlank(),
                                    modifier = Modifier.weight(1f),
                                ) { Text("Open") }
                                TextButton(
                                    onClick = {
                                        target = ""
                                        viewModel.updateCanvasNodeTarget(node, null, null, null, null)
                                    },
                                    enabled = target.isNotBlank() || !node.targetUri.isNullOrBlank(),
                                    modifier = Modifier.weight(1f),
                                ) { Text("Clear") }
                            }
                        }
                    }
                }
                linkedNote?.let { note ->
                    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)) {
                        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("Linked doc", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            Text(note.title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                            Text(
                                note.document.plainText().replace("\n", " ").trim(),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 3,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                fontSize = 12.sp,
                            )
                            TextButton(onClick = { viewModel.select(note); onDismiss() }) { Text("Open doc") }
                        }
                    }
                }
                CanvasConnectionSection(
                    node = node,
                    allNodes = allNodes,
                    edges = edges,
                    viewModel = viewModel,
                )
                Text(
                    "Position ${(node.x * 100).roundToInt()}%, ${(node.y * 100).roundToInt()}% · ${node.type.displayLabel()}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.sp,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    TextButton(
                        onClick = {
                            viewModel.deleteCanvasNode(node)
                            onDismiss()
                        },
                        modifier = Modifier.weight(1f),
                    ) { Text("Delete", color = MaterialTheme.colorScheme.error) }
                    TextButton(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text("Cancel") }
                    Button(
                        onClick = {
                            viewModel.updateCanvasNodeContent(node, title, subtitle)
                            if (node.type == CanvasNodeType.Link) {
                                viewModel.updateCanvasNodeTarget(node, target.ifBlank { null }, "text/uri-list", target.ifBlank { null }, null)
                            }
                            onDismiss()
                        },
                        enabled = title.isNotBlank(),
                        modifier = Modifier.weight(1f),
                    ) { Text("Save") }
                }
            }
        }
    }
}

@Composable
private fun CanvasConnectionSection(
    node: CanvasNodeItem,
    allNodes: List<CanvasNodeItem>,
    edges: List<CanvasEdgeItem>,
    viewModel: NotesViewModel,
) {
    var menuOpen by remember(node.id, edges) { mutableStateOf(false) }
    val existingTargets = edges.filter { it.fromNodeId == node.id }.map { it.toNodeId }.toSet()
    val connectableNodes = allNodes.filter { it.id != node.id && it.id !in existingTargets }
    val nodeTitles = allNodes.associate { it.id to it.title }
    val relatedEdges = edges.filter { it.fromNodeId == node.id || it.toNodeId == node.id }

    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f)) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Outlined.Link, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                Text("Connections", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                Spacer(Modifier.weight(1f))
                Box {
                    TextButton(onClick = { menuOpen = true }, enabled = connectableNodes.isNotEmpty()) {
                        Text("Connect")
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        connectableNodes.forEach { target ->
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Text(target.title, fontWeight = FontWeight.SemiBold, maxLines = 1)
                                        Text(target.type.displayLabel(), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                },
                                onClick = {
                                    viewModel.addCanvasEdge(node.id, target.id, "link")
                                    menuOpen = false
                                },
                            )
                        }
                    }
                }
            }
            if (relatedEdges.isEmpty()) {
                Text("No connections yet. Link this block to another canvas item.", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                relatedEdges.forEach { edge ->
                    val outgoing = edge.fromNodeId == node.id
                    val otherId = if (outgoing) edge.toNodeId else edge.fromNodeId
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            if (outgoing) "To" else "From",
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier
                                .clip(RoundedCornerShape(999.dp))
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.10f))
                                .padding(horizontal = 7.dp, vertical = 3.dp),
                        )
                        Column(Modifier.weight(1f)) {
                            Text(nodeTitles[otherId] ?: "Missing block", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                            Text(edge.label.ifBlank { "link" }, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                        }
                        TextButton(onClick = { viewModel.deleteCanvasEdge(edge) }) {
                            Text("Remove")
                        }
                    }
                }
            }
        }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun CanvasTool(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
        Text(label, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun ChatScreen(state: NotesUiState, viewModel: NotesViewModel, onPickAttachment: () -> Unit = {}) {
    var message by remember { mutableStateOf("") }
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
            item {
                Box(Modifier.fillMaxWidth().padding(vertical = 6.dp), contentAlignment = Alignment.Center) {
                    Surface(shape = RoundedCornerShape(999.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                        Text("Today", Modifier.padding(horizontal = 14.dp, vertical = 5.dp), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
            composerModifier,
            color = MaterialTheme.colorScheme.surface,
            shadowElevation = 8.dp,
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = if (imeVisible) 6.dp else 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                OutlinedTextField(
                    value = message,
                    onValueChange = { message = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Message workspace…", fontSize = 14.sp) },
                    maxLines = 4,
                    shape = RoundedCornerShape(22.dp),
                )
                IconButton(
                    onClick = onPickAttachment,
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                ) {
                    Icon(Icons.Outlined.AttachFile, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                }
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
fun ConflictReviewScreen(state: NotesUiState, viewModel: NotesViewModel) {
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
                        ConflictStat("Canvas", state.canvasNodes.size.toString(), Modifier.weight(1f))
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
        ConflictStat("Canvas", summary.canvasNodes.toString(), Modifier.weight(1f))
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
fun SyncMonitorScreen(state: NotesUiState, viewModel: NotesViewModel) {
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
private fun CanvasNodeCard(
    node: CanvasNodeItem,
    width: androidx.compose.ui.unit.Dp,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Surface(
        modifier
            .width(width)
            .clip(RoundedCornerShape(18.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 5.dp,
        tonalElevation = 2.dp,
    ) {
        Column(
            Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(
                Modifier
                    .size(30.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .background(Color(node.color)),
                contentAlignment = Alignment.Center,
            ) {
                val icon = when (node.type) {
                    CanvasNodeType.Text -> Icons.Outlined.TextFields
                    CanvasNodeType.Note -> Icons.AutoMirrored.Outlined.Note
                    CanvasNodeType.File -> Icons.Outlined.Add
                    CanvasNodeType.Shape -> Icons.Outlined.ShapeLine
                    CanvasNodeType.Link -> Icons.Outlined.Link
                    CanvasNodeType.Media -> Icons.Outlined.PlayArrow
                }
                Icon(icon, null, tint = Color.White, modifier = Modifier.size(17.dp))
            }
            Text(node.title, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            Text(node.subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, lineHeight = 13.sp)
        }
    }
}

private fun adjustedCanvasNodes(nodes: List<CanvasNodeItem>): List<CanvasNodeItem> {
    if (nodes.isEmpty()) return emptyList()
    val minDistance = 0.18f
    val placed = mutableListOf<CanvasNodeItem>()
    nodes.sortedBy { it.id }.forEachIndexed { index, node ->
        var x = node.x
        var y = node.y
        var attempts = 0
        while (placed.any { kotlin.math.abs(it.x - x) < minDistance && kotlin.math.abs(it.y - y) < 0.12f } && attempts < 8) {
            x += 0.22f + ((index + attempts) % 3) * 0.08f
            y += 0.14f + ((index + attempts) / 3) * 0.06f
            attempts++
        }
        placed += node.copy(x = x, y = y)
    }
    return placed
}

private data class CanvasWorldBounds(val left: Float, val top: Float, val right: Float, val bottom: Float)

private fun canvasWorldBounds(nodes: List<CanvasNodeItem>): CanvasWorldBounds {
    if (nodes.isEmpty()) return CanvasWorldBounds(-0.25f, -0.25f, 1.25f, 1.25f)
    val padding = 0.55f
    val rawLeft = nodes.minOf { it.x } - padding
    val rawTop = nodes.minOf { it.y } - padding
    val rawRight = nodes.maxOf { it.x } + padding
    val rawBottom = nodes.maxOf { it.y } + padding
    val minSpan = 1.5f
    val centerX = (rawLeft + rawRight) / 2f
    val centerY = (rawTop + rawBottom) / 2f
    val halfWidth = max((rawRight - rawLeft) / 2f, minSpan / 2f)
    val halfHeight = max((rawBottom - rawTop) / 2f, minSpan / 2f)
    return CanvasWorldBounds(centerX - halfWidth, centerY - halfHeight, centerX + halfWidth, centerY + halfHeight)
}

private fun fitCanvasOffset(
    bounds: CanvasWorldBounds,
    boardWidth: Float,
    boardHeight: Float,
    nodeWidth: Float,
    nodeHeight: Float,
    toolbarHeight: Float,
    worldUnit: Float,
): Offset {
    val contentWidth = ((bounds.right - bounds.left).coerceAtLeast(0.2f) * worldUnit) + nodeWidth
    val contentHeight = ((bounds.bottom - bounds.top).coerceAtLeast(0.2f) * worldUnit) + nodeHeight
    val targetX = ((boardWidth - contentWidth) / 2f) - bounds.left * worldUnit
    val targetY = ((boardHeight - toolbarHeight - contentHeight) / 2f) - bounds.top * worldUnit
    return Offset(targetX.coerceIn(-3_600f, 3_600f), targetY.coerceIn(-3_600f, 3_600f))
}

private fun constrainCanvasOffset(
    offset: Offset,
    bounds: CanvasWorldBounds,
    boardWidth: Float,
    boardHeight: Float,
    scale: Float,
): Offset {
    val safeDrift = max(boardWidth, boardHeight) * 0.85f
    val unit = min(boardWidth, boardHeight).coerceAtLeast(1f)
    val leftPx = bounds.left * unit * scale
    val rightPx = bounds.right * unit * scale
    val topPx = bounds.top * unit * scale
    val bottomPx = bounds.bottom * unit * scale
    val minX = boardWidth - rightPx - safeDrift
    val maxX = -leftPx + safeDrift
    val minY = boardHeight - bottomPx - safeDrift
    val maxY = -topPx + safeDrift
    fun Float.coerceOrCenter(minValue: Float, maxValue: Float): Float =
        if (minValue <= maxValue) coerceIn(minValue, maxValue) else (minValue + maxValue) / 2f
    return Offset(
        x = offset.x.coerceOrCenter(minX, maxX),
        y = offset.y.coerceOrCenter(minY, maxY),
    )
}

private data class CanvasNodeRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val center: Offset get() = Offset((left + right) / 2f, (top + bottom) / 2f)
    fun expanded(padding: Float) = CanvasNodeRect(left - padding, top - padding, right + padding, bottom + padding)
}

private fun canvasNodeRect(
    node: CanvasNodeItem,
    boardWidth: Float,
    boardHeight: Float,
    nodeWidth: Float,
    nodeHeight: Float,
    toolbarHeight: Float,
): CanvasNodeRect {
    val x = node.x.coerceIn(0.02f, 0.86f) * (boardWidth - nodeWidth).coerceAtLeast(1f)
    val y = node.y.coerceIn(0.03f, 0.82f) * (boardHeight - nodeHeight - toolbarHeight).coerceAtLeast(1f)
    return CanvasNodeRect(x, y, x + nodeWidth, y + nodeHeight)
}

private fun routeCanvasEdge(
    fromRect: CanvasNodeRect,
    toRect: CanvasNodeRect,
    obstacles: List<CanvasNodeRect>,
    boardWidth: Float,
    boardHeight: Float,
    toolbarHeight: Float,
    laneIndex: Int,
): List<Offset> {
    val fromCenter = fromRect.center
    val toCenter = toRect.center
    val exitsRight = toCenter.x >= fromCenter.x
    val start = Offset(if (exitsRight) fromRect.right else fromRect.left, fromCenter.y)
    val end = Offset(if (exitsRight) toRect.left else toRect.right, toCenter.y)
    val lane = 36f + (laneIndex % 5) * 18f
    val minX = 18f
    val maxX = boardWidth - 18f
    val minY = 18f
    val maxY = boardHeight - toolbarHeight - 18f
    val midX = ((start.x + end.x) / 2f + if (laneIndex % 2 == 0) lane else -lane).coerceIn(minX, maxX)
    val midY = ((start.y + end.y) / 2f + if (laneIndex % 3 == 0) lane else -lane).coerceIn(minY, maxY)
    val sideX = (if (exitsRight) fromRect.right + lane else fromRect.left - lane).coerceIn(minX, maxX)
    val railY = (if (laneIndex % 2 == 0) minY else maxY).coerceIn(minY, maxY)
    val candidates = listOf(
        listOf(start, Offset(midX, start.y), Offset(midX, end.y), end),
        listOf(start, Offset(start.x, midY), Offset(end.x, midY), end),
        listOf(start, Offset(sideX, start.y), Offset(sideX, end.y), end),
        listOf(start, Offset(start.x, railY), Offset(end.x, railY), end),
        listOf(start, Offset(midX, start.y), Offset(midX, midY), Offset(end.x, midY), end),
    )
    return candidates.firstOrNull { path -> !pathIntersectsObstacles(path, obstacles) } ?: candidates.last()
}

private fun pathIntersectsObstacles(path: List<Offset>, obstacles: List<CanvasNodeRect>): Boolean {
    if (path.size < 2) return false
    val padded = obstacles.map { it.expanded(10f) }
    return path.zipWithNext().any { (a, b) -> padded.any { segmentIntersectsRect(a, b, it) } }
}

private fun segmentIntersectsRect(a: Offset, b: Offset, rect: CanvasNodeRect): Boolean {
    val horizontal = kotlin.math.abs(a.y - b.y) < 0.5f
    val vertical = kotlin.math.abs(a.x - b.x) < 0.5f
    return when {
        horizontal -> a.y in rect.top..rect.bottom && rangesOverlap(min(a.x, b.x), max(a.x, b.x), rect.left, rect.right)
        vertical -> a.x in rect.left..rect.right && rangesOverlap(min(a.y, b.y), max(a.y, b.y), rect.top, rect.bottom)
        else -> false
    }
}

private fun rangesOverlap(aStart: Float, aEnd: Float, bStart: Float, bEnd: Float): Boolean =
    max(aStart, bStart) <= min(aEnd, bEnd)

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

private fun CanvasNodeType.displayLabel(): String = if (this == CanvasNodeType.Note) "Doc" else name


private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
