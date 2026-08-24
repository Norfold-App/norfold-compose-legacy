package com.norfold.app.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.FormatListBulleted
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Difference
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.DragIndicator
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.UnfoldMore
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.NotificationsNone
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.Tag
import androidx.compose.material.icons.outlined.TrackChanges
import androidx.compose.material.icons.outlined.People
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import com.norfold.app.ui.components.NorfoldDropdownMenu
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.ripple
import com.norfold.app.ui.components.NorfoldDialog
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.norfold.app.branding.palette
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInParent
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.norfold.app.domain.ChatChannel
import com.norfold.app.domain.ChatChannelKind
import com.norfold.app.domain.Destination
import com.norfold.app.domain.DocumentArtifactKind
import com.norfold.app.editor.flags.EditorFeatureFlag
import com.norfold.app.ui.editor.editorFlags
import com.norfold.app.domain.BlockDocument
import com.norfold.app.domain.BlockDocumentJson
import com.norfold.app.domain.DocOutline
import com.norfold.app.domain.DocSectionAction
import com.norfold.app.domain.DocumentOwnerType
import com.norfold.app.domain.DocumentOwner
import com.norfold.app.domain.WorkspaceObjectType
import com.norfold.app.domain.WorkspaceObjectHistory
import com.norfold.app.domain.SemanticActionId
import com.norfold.app.domain.Note
import com.norfold.app.ui.DocsUiState
import com.norfold.app.ui.components.WorkspaceIconBadge
import com.norfold.app.ui.components.NorfoldBottomSheet
import com.norfold.app.ui.components.ChoiceRow
import com.norfold.app.ui.components.SemanticAppIcon
import com.norfold.app.ui.DocsViewModel
import com.norfold.app.domain.style.ChromeGeometry
import com.norfold.app.domain.style.ChromeGeometry.Corner
import com.norfold.app.ui.NorfoldChrome
import com.norfold.app.ui.rememberChromeFocus
import com.norfold.app.ui.focusableChrome
import androidx.compose.ui.semantics.Role

/**
 * How far the sidebar scrim dims what is behind the panel.
 *
 * Named because it is the one number that decides whether the scrim reads as "the thing behind is out of reach";
 * the value itself is what this overlay already drew.
 */
private const val ScrimAlpha = 0.44f

@Composable
fun SectionSidebarOverlay(state: DocsUiState, viewModel: DocsViewModel) {
    if (state.destination == Destination.DocumentEditor) {
        NorfoldBottomSheet(onDismissRequest = viewModel::closeSidebar) {
            SidebarContent(
                state,
                viewModel,
                Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.86f)
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
        return
    }
    val panelEnter = if (state.settings.reduceMotion) {
        fadeIn(tween(150))
    } else {
        slideInHorizontally(
            spring(dampingRatio = 0.82f, stiffness = Spring.StiffnessMediumLow),
        ) { -it } + fadeIn(tween(200))
    }
    val panelExit = if (state.settings.reduceMotion) {
        fadeOut(tween(120))
    } else {
        slideOutHorizontally(tween(240, easing = FastOutSlowInEasing)) { -it } + fadeOut(tween(200))
    }
    AnimatedVisibility(
        visible = state.sidebarOpen,
        enter = fadeIn(tween(if (state.settings.reduceMotion) 120 else 250)),
        exit = fadeOut(tween(if (state.settings.reduceMotion) 120 else 250)),
    ) {
        Box(Modifier.fillMaxSize()) {
            // **No focus ring here, and no `clickable` either.** A scrim is the one population the chrome
            // substrate must not touch: a ring around the entire screen indicates nothing, a ripple that fills
            // the screen on every dismiss is worse than none, and as a focus stop it is a node a keyboard user
            // must traverse to reach the panel it exists to dim. It was also announced, before this, as an
            // unlabelled button occupying the whole display and appearing *before* the drawer in traversal
            // order — so the first thing a reader met on opening the sidebar was an anonymous button.
            //
            // `detectTapGestures` keeps the tap-to-dismiss; `clearAndSetSemantics {}` withdraws the node so a
            // reader goes straight to the panel. The tap is a convenience and not the only exit — the sidebar is
            // one of the states `PredictiveBackHandler` in `NorfoldAppRoot` handles, so back closes it, and every
            // navigation row inside calls `closeSidebar` on activation. This is the standard "scrim is decoration
            // over a modal surface, back is the exit" arrangement.
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = ScrimAlpha))
                    .pointerInput(Unit) { detectTapGestures { viewModel.closeSidebar() } }
                    .clearAndSetSemantics { },
            )
            AnimatedVisibility(
                visible = state.sidebarOpen,
                enter = panelEnter,
                exit = panelExit,
                modifier = Modifier.align(Alignment.CenterStart),
            ) {
                Surface(
                    modifier = Modifier
                        .width(300.dp)
                        .fillMaxHeight(),
                    color = MaterialTheme.colorScheme.surface,
                    shadowElevation = 16.dp,
                ) {
                    // The overlay now sits above the Scaffold (over the navbar), so it must
                    // apply its own system-bar insets.
                    SidebarContent(
                        state,
                        viewModel,
                        Modifier
                            .statusBarsPadding()
                            .navigationBarsPadding()
                            .padding(horizontal = 16.dp, vertical = 20.dp),
                    )
                }
            }
        }
    }
}

@Composable
fun Sidebar(state: DocsUiState, viewModel: DocsViewModel, modifier: Modifier) {
    SidebarContent(state, viewModel, modifier)
}

@Composable
private fun WorkspaceSwitcher(state: DocsUiState, viewModel: DocsViewModel) {
    var expanded by remember { mutableStateOf(false) }
    var creating by remember { mutableStateOf(false) }
    if (creating) {
        CreateWorkspaceVisualDialog(state, viewModel) {
            creating = false
            expanded = false
        }
    }
    val active = state.workspaces.firstOrNull { it.id == state.settings.activeWorkspaceId }
    val activeColor = (active?.palette ?: state.settings.themeProfile).palette().accent
    val groupFill = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
    val toggleFocus = rememberChromeFocus()

    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Corner.Card.radiusDp.dp))
            .background(groupFill),
    ) {
        // Current workspace / toggle
        //
        // The ring is measured against the *group's* fill rather than the page, because that translucent
        // card is what this row is drawn on; it paints nothing of its own. Its radius is the group's too:
        // collapsed, this row is the whole clipped card, so 16 dp is exactly concentric. Expanded, the row
        // is the card's top band and its bottom corners are square — the ring still closes with a curve
        // there, which reads as "this row is the target" rather than as a mismatched edge, because the
        // divider below is the only thing at that boundary and it is not a shape the ring could echo.
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(
                    interactionSource = toggleFocus.source,
                    indication = ripple(),
                ) { expanded = !expanded }
                .then(toggleFocus.ring(Corner.Card, backdrop = groupFill))
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            WorkspaceIconBadge(
                icon = active?.icon ?: "N",
                iconKind = active?.iconKind ?: state.settings.workspaceIconKind,
                iconUri = active?.iconUri ?: state.settings.workspaceIconUri,
                size = 40.dp,
                corner = 12.dp,
                background = activeColor,
                contentColor = Color.White,
                fontSize = 18.sp,
                maxGlyphs = 1,
            )
            Column(Modifier.weight(1f)) {
                Text(active?.name ?: state.settings.workspaceName.ifBlank { "Workspace" }, fontWeight = FontWeight.Bold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val noteCount = state.notes.count { it.artifactKind == DocumentArtifactKind.Flow }
                val documentCount = state.notes.count { it.artifactKind == DocumentArtifactKind.Document }
                Text("$noteCount notes · $documentCount documents", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = { viewModel.go(Destination.Inbox) }, modifier = Modifier.size(40.dp)) {
                Icon(Icons.Outlined.NotificationsNone, "Inbox", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
            }
            Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.UnfoldMore, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
        }

        AnimatedVisibility(expanded) {
            Column(Modifier.padding(horizontal = 8.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
                state.workspaces.forEach { ws ->
                    val selected = ws.id == state.settings.activeWorkspaceId
                    ChoiceRow(onClick = { viewModel.switchWorkspace(ws.id) }) {
                        WorkspaceIconBadge(
                            icon = ws.icon,
                            iconKind = ws.iconKind,
                            iconUri = ws.iconUri,
                            size = 26.dp,
                            corner = 8.dp,
                            background = ws.palette.palette().accent,
                            contentColor = Color.White,
                            fontSize = 12.sp,
                            maxGlyphs = 1,
                        )
                        Text(ws.name, Modifier.weight(1f), fontSize = 14.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        // Deleting a workspace cascades every table it owns, so it is offered only on
                        // the deliberate Settings path. A switcher row switches; it does not destroy.
                        if (selected) Icon(Icons.Outlined.Check, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                    }
                }
                ChoiceRow(onClick = { creating = true }) {
                    Box(Modifier.size(26.dp).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)), contentAlignment = Alignment.Center) {
                        Icon(Icons.Outlined.Add, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                    }
                    Text("New workspace", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}

/** Sticky workspace identity and cross-object search share one visual region in every sidebar. */
@Composable
private fun UnifiedSidebarHeader(
    state: DocsUiState,
    viewModel: DocsViewModel,
    query: String,
    onQueryChange: (String) -> Unit,
) {
    // The header region's fill, hoisted so its edge measures against it. Left translucent: the substrate
    // composites a declared alpha fill over the resolved container, which for the sidebar is the sheet.
    val headerFill = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.32f)
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = headerFill,
        // Measured, because in dark mode this region has *no* other boundary: `surfaceVariant` at 32% over the
        // sheet composites to within 1.0011:1 of the sheet itself — the same colour to any eye — and the
        // `outlineVariant` edge on top of it drew 1.0785:1. Light is barely better at 1.0276 and 1.0816. This is
        // the region that groups the workspace identity with cross-object search, so with neither cue the
        // sidebar opens as one undifferentiated column of controls.
        border = androidx.compose.foundation.BorderStroke(
            ChromeGeometry.RESTING_EDGE_WIDTH.dp,
            with(NorfoldChrome) { restingEdge(headerFill) },
        ),
    ) {
        Column(Modifier.padding(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            WorkspaceSwitcher(state, viewModel)
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.78f))
                    .padding(horizontal = 10.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.Search, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(8.dp))
                Box(Modifier.weight(1f)) {
                    if (query.isBlank()) {
                        Text("Search workspace", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                    }
                    BasicTextField(
                        value = query,
                        onValueChange = onQueryChange,
                        singleLine = true,
                        textStyle = TextStyle(fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (query.isNotEmpty()) {
                    val clearFocus = rememberChromeFocus()
                    Icon(
                        Icons.Outlined.Close,
                        "Clear search",
                        // The glyph stays 18 dp; `minimumInteractiveComponentSize` grows the *layout* box to
                        // 48 dp without moving it. Two things depend on that: an 18 dp circle leaves only
                        // 10 dp inside the ring's inset, too cramped to read as a ring, and the visual box
                        // was the only thing under 48 dp — `clickable` already gets touch expansion from the
                        // system, but the system does not enlarge the box a ring is drawn in.
                        //
                        // Order matters and is not cosmetic. The ring sits *outside*
                        // `minimumInteractiveComponentSize` so its draw scope measures the expanded 48 dp
                        // box; inside, it would measure the 18 dp glyph and draw a ring far too small. And
                        // `size` comes last, per the modifier's own contract, so it cannot fight the
                        // expansion.
                        Modifier
                            .clip(CircleShape)
                            .clickable(
                                interactionSource = clearFocus.source,
                                indication = ripple(),
                                onClickLabel = "Clear search",
                                onClick = { onQueryChange("") },
                            )
                            .then(clearFocus.ringOnCircle(48f, MaterialTheme.colorScheme.surface))
                            .minimumInteractiveComponentSize()
                            .size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Text("Ctrl K", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp)
                }
            }
        }
    }
}

@Composable
private fun SidebarSearchResults(
    state: DocsUiState,
    viewModel: DocsViewModel,
    query: String,
    modifier: Modifier = Modifier,
) {
    val results = buildSearchResults(state, viewModel, query).take(30)
    Column(
        modifier.verticalScroll(rememberScrollState()).padding(top = 6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            "${results.size} result${if (results.size == 1) "" else "s"}",
            Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (results.isEmpty()) {
            Text(
                "Nothing in this workspace matches “${query.trim()}”.",
                Modifier.padding(12.dp),
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            results.forEach { result ->
                SearchResultRow(result, onActivated = viewModel::closeSidebar)
            }
        }
    }
}

@Composable
private fun SidebarContent(state: DocsUiState, viewModel: DocsViewModel, modifier: Modifier) {
    var sidebarQuery by remember { mutableStateOf("") }
    // While a doc is open, the sidebar *becomes* the document's table of contents —
    // the workspace nav is reachable again via the editor's back button.
    val activeDocument by viewModel.activeDocument.collectAsState()
    val editorDocument = activeDocument
    if (state.destination in setOf(Destination.NoteEditor, Destination.DocumentEditor) && editorDocument != null) {
        val title = when (editorDocument.owner.type) {
            DocumentOwnerType.Note -> state.notes.firstOrNull { it.id == editorDocument.owner.id }?.title
            DocumentOwnerType.Task -> state.tasks.firstOrNull { it.id == editorDocument.owner.id }?.title
            DocumentOwnerType.CalendarEvent -> state.calendarEvents.firstOrNull { it.id == editorDocument.owner.id }?.title
        }.orEmpty().ifBlank { "Untitled" }
        val locked = editorDocument.owner.type == DocumentOwnerType.Note &&
            state.notes.firstOrNull { it.id == editorDocument.owner.id }?.locked == true
        DocSidebarContent(editorDocument.owner, title, editorDocument.document, locked, state, viewModel, sidebarQuery, { sidebarQuery = it }, modifier)
        return
    }
    // Chat gets its own sidebar — people, groups, and topics — the same way the
    // editor swaps in its table of contents.
    if (state.destination == Destination.Chat) {
        ChatSidebarContent(state, viewModel, sidebarQuery, { sidebarQuery = it }, modifier)
        return
    }
    var notesExpanded by remember { mutableStateOf(state.destination in setOf(Destination.NotesHome, Destination.NoteEditor, Destination.Notebooks, Destination.Tags)) }
    var notesShowAll by remember { mutableStateOf(false) }
    var documentsExpanded by remember { mutableStateOf(state.destination in setOf(Destination.DocumentsHome, Destination.DocumentEditor)) }
    var documentsShowAll by remember { mutableStateOf(false) }
    var tasksExpanded by remember { mutableStateOf(state.destination in setOf(Destination.Tasks, Destination.Calendar)) }
    var creatingFolderScope by remember { mutableStateOf<String?>(null) }
    var folderName by remember { mutableStateOf("") }
    var folderIcon by remember { mutableStateOf(SemanticActionId.AppFolder) }
    val publicName = state.settings.syncPublicName.ifBlank { state.settings.syncUserName.ifBlank { "Local owner" } }
    creatingFolderScope?.let { scope ->
        val documentFolder = scope == "documents"
        val iconChoices = listOf(
            SemanticActionId.AppFolder,
            if (documentFolder) SemanticActionId.AppDocuments else SemanticActionId.AppNotes,
            SemanticActionId.AppFavorites,
            SemanticActionId.AppTags,
            SemanticActionId.AppFiles,
            SemanticActionId.AppTemplates,
        )
        NorfoldDialog(
            onDismissRequest = { creatingFolderScope = null },
            title = { Text(if (documentFolder) "New document folder" else "New notes folder") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = folderName,
                        onValueChange = { folderName = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text("Folder name") },
                    )
                    Text("Folder icon", fontWeight = FontWeight.SemiBold)
                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        iconChoices.forEach { choice ->
                            val choiceFill = if (folderIcon == choice) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
                            val choiceFocus = rememberChromeFocus()
                            Surface(
                                onClick = { folderIcon = choice },
                                shape = RoundedCornerShape(12.dp),
                                color = choiceFill,
                                interactionSource = choiceFocus.source,
                                modifier = Modifier.then(choiceFocus.ringOnShape(12f, choiceFill)),
                            ) {
                                SemanticAppIcon(
                                    choice,
                                    choice.defaultLabel,
                                    Modifier.padding(12.dp).size(22.dp),
                                    if (folderIcon == choice) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            },
            dismissButton = { TextButton(onClick = { creatingFolderScope = null }) { Text("Cancel") } },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.addNotebook(folderName, scope, folderIcon.stableId)
                        folderName = ""
                        folderIcon = SemanticActionId.AppFolder
                        creatingFolderScope = null
                    },
                    enabled = folderName.isNotBlank(),
                ) { Text("Create") }
            },
        )
    }
    Column(
        modifier.fillMaxHeight(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        UnifiedSidebarHeader(state, viewModel, sidebarQuery, { sidebarQuery = it })

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SidebarQuickCreate("Note", Modifier.weight(1f), onClick = viewModel::createNote)
            SidebarQuickCreate("Doc", Modifier.weight(1f), onClick = viewModel::createDocument)
            SidebarQuickCreate("Task", Modifier.weight(1f), onClick = viewModel::createTaskAndOpen)
        }

        if (sidebarQuery.isNotBlank()) {
            SidebarSearchResults(state, viewModel, sidebarQuery, Modifier.weight(1f))
        } else Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Spacer(Modifier.height(4.dp))
            SideNavItem("Dashboard", SemanticActionId.AppHome, state.destination == Destination.WorkspaceHub) { viewModel.go(Destination.WorkspaceHub) }
            SideNavItem("Today", SemanticActionId.AppToday, state.destination == Destination.Tasks && state.settings.taskViewMode == "Calendar" && state.settings.calendarDefaultView == "Day") {
                viewModel.openTaskCalendar("Day")
            }
            SideNavItem("Upcoming", SemanticActionId.AppUpcoming, state.destination == Destination.Tasks && state.settings.taskViewMode == "Calendar" && state.settings.calendarDefaultView == "Agenda") {
                viewModel.openTaskCalendar("Agenda")
            }
            SideNavItem("Favorites", SemanticActionId.AppFavorites, state.destination == Destination.NotesHome && state.tab == com.norfold.app.domain.HomeTab.Pinned) {
                viewModel.selectTab(com.norfold.app.domain.HomeTab.Pinned)
                viewModel.openNotesLibrary()
            }

            SidebarDropdownHeader(
                label = "Notes",
                icon = SemanticActionId.AppNotes,
                expanded = notesExpanded,
                selected = state.destination in setOf(Destination.NotesHome, Destination.NoteEditor, Destination.Notebooks, Destination.Tags),
                onToggle = { notesExpanded = !notesExpanded },
                onClick = viewModel::openNotesLibrary,
            )
            AnimatedVisibility(notesExpanded) {
                Column(Modifier.padding(start = 22.dp)) {
                    SideNavItem("All notes", SemanticActionId.AppNotes, state.destination == Destination.NotesHome && state.selectedNotebookId == null && state.selectedTagId == null) { viewModel.filterByNotebook(null) }
                    SideNavItem("Manage folders", SemanticActionId.AppFolder, state.destination == Destination.Notebooks) { viewModel.go(Destination.Notebooks) }
                    SideNavItem("New notes folder", SemanticActionId.Insert, false) {
                        creatingFolderScope = "notes"
                    }
                    state.notebooks.filter { it.scope == "notes" }.forEach { notebook ->
                        val icon = SemanticActionId.entries.firstOrNull { it.stableId == notebook.iconId } ?: SemanticActionId.AppFolder
                        SideNavItem(notebook.name, icon, state.selectedNotebookId == notebook.id) {
                            viewModel.filterByNotebook(notebook.id)
                        }
                    }
                    SideNavItem("Tags", SemanticActionId.AppTags, state.destination == Destination.Tags) { viewModel.go(Destination.Tags) }
                    val recents = state.notes.filter { it.artifactKind == DocumentArtifactKind.Flow }.sortedByDescending { it.updatedAt }
                    val shown = if (notesShowAll) recents else recents.take(3)
                    shown.forEach { note ->
                        SideNavItem(note.title.ifBlank { "Untitled note" }, SemanticActionId.AppNotes, state.destination == Destination.NoteEditor && state.selectedNote?.id == note.id) {
                            viewModel.select(note)
                        }
                    }
                    if (recents.size > 3) {
                        SideNavItem(if (notesShowAll) "Show less" else "…", Icons.Outlined.UnfoldMore, false) { notesShowAll = !notesShowAll }
                    }
                }
            }

            SidebarDropdownHeader(
                label = "Documents",
                icon = SemanticActionId.AppDocuments,
                expanded = documentsExpanded,
                selected = state.destination in setOf(Destination.DocumentsHome, Destination.DocumentEditor),
                onToggle = { documentsExpanded = !documentsExpanded },
                onClick = viewModel::openDocumentsLibrary,
            )
            AnimatedVisibility(documentsExpanded) {
                Column(Modifier.padding(start = 22.dp)) {
                    SideNavItem("All documents", SemanticActionId.AppDocuments, state.destination == Destination.DocumentsHome) { viewModel.openDocumentsLibrary() }
                    SideNavItem("New document folder", SemanticActionId.Insert, false) {
                        creatingFolderScope = "documents"
                    }
                    state.notebooks.filter { it.scope == "documents" }.forEach { notebook ->
                        val icon = SemanticActionId.entries.firstOrNull { it.stableId == notebook.iconId } ?: SemanticActionId.AppFolder
                        SideNavItem(notebook.name, icon, state.selectedNotebookId == notebook.id) {
                            viewModel.filterByNotebook(notebook.id)
                        }
                    }
                    val recents = state.notes.filter { it.artifactKind == DocumentArtifactKind.Document }.sortedByDescending { it.updatedAt }
                    val shown = if (documentsShowAll) recents else recents.take(3)
                    shown.forEach { note ->
                        SideNavItem(note.title.ifBlank { "Untitled document" }, SemanticActionId.AppDocuments, state.destination == Destination.DocumentEditor && state.selectedNote?.id == note.id) {
                            viewModel.select(note)
                        }
                    }
                    if (recents.size > 3) {
                        SideNavItem(if (documentsShowAll) "Show less" else "…", Icons.Outlined.UnfoldMore, false) { documentsShowAll = !documentsShowAll }
                    }
                }
            }

            SidebarDropdownHeader(
                label = "Tasks",
                icon = SemanticActionId.AppTasks,
                expanded = tasksExpanded,
                selected = state.destination == Destination.Tasks,
                onToggle = { tasksExpanded = !tasksExpanded },
                onClick = { viewModel.go(Destination.Tasks) },
            )
            AnimatedVisibility(tasksExpanded) {
                Column(Modifier.padding(start = 22.dp)) {
                    SideNavItem("Board", Icons.Outlined.GridView, state.destination == Destination.Tasks && state.settings.taskViewMode == "Board") {
                        viewModel.patchSettings { it.copy(taskViewMode = "Board") }; viewModel.go(Destination.Tasks)
                    }
                    SideNavItem("Table", Icons.Outlined.Check, state.destination == Destination.Tasks && state.settings.taskViewMode == "Table") {
                        viewModel.patchSettings { it.copy(taskViewMode = "Table") }; viewModel.go(Destination.Tasks)
                    }
                    SideNavItem("Calendar", Icons.Outlined.CalendarMonth, state.destination == Destination.Tasks && state.settings.taskViewMode == "Calendar" && state.settings.calendarDefaultView !in setOf("Day", "Agenda")) { viewModel.openTaskCalendar() }
                    SideNavItem("Agenda", Icons.Outlined.CalendarMonth, state.destination == Destination.Tasks && state.settings.taskViewMode == "Calendar" && state.settings.calendarDefaultView == "Agenda") {
                        viewModel.openTaskCalendar("Agenda")
                    }
                }
            }

            SideNavItem("Files", SemanticActionId.AppFiles, state.destination == Destination.Files) { viewModel.go(Destination.Files) }
            SideNavItem("Chat", SemanticActionId.AppChat, state.destination == Destination.Chat) { viewModel.openChatInbox() }
            SideNavItem("Inbox", SemanticActionId.AppInbox, state.destination == Destination.Inbox) { viewModel.go(Destination.Inbox) }

            Spacer(Modifier.height(4.dp))
            Text(
                "WORKSPACE",
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.8.sp,
            )
            SideNavItem("Database", SemanticActionId.AppDatabase, state.destination == Destination.Database) { viewModel.go(Destination.Database) }
            if (editorFlags.isEnabled(EditorFeatureFlag.SPREADSHEET_HOST)) {
                SideNavItem("Spreadsheet", Icons.Outlined.GridView, state.destination == Destination.SpreadsheetEditor) {
                    viewModel.openOrCreateSpreadsheet()
                }
            }
            SideNavItem("Graph", SemanticActionId.AppGraph, state.destination == Destination.Graph) { viewModel.go(Destination.Graph) }
            SideNavItem("Activity", SemanticActionId.AppActivity, state.destination == Destination.Activity) { viewModel.go(Destination.Activity) }
            SideNavItem("Templates", SemanticActionId.AppTemplates, state.destination == Destination.Templates) { viewModel.go(Destination.Templates) }

            Spacer(Modifier.height(6.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
            SideNavItem("Settings", SemanticActionId.AppSettings, state.destination == Destination.Settings) { viewModel.go(Destination.Settings) }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
        ChoiceRow(onClick = { viewModel.go(Destination.Settings) }, contentPadding = PaddingValues(10.dp)) {
            Box(Modifier.size(34.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary), contentAlignment = Alignment.Center) {
                Text(publicName.take(1).uppercase(), color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.Bold)
            }
            Column(Modifier.weight(1f)) {
                Text(publicName, fontWeight = FontWeight.Bold, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("@${state.settings.syncUserName.ifBlank { "owner" }}", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Icon(Icons.Outlined.UnfoldMore, null, Modifier.size(17.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * One of the sidebar's three quick-create buttons. Note, Doc and Task were three byte-identical
 * copies of this chrome differing only in label and callback, so the focus ring is written once
 * here rather than three times at the call sites.
 */
@Composable
private fun SidebarQuickCreate(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val focus = rememberChromeFocus()
    // Read once and handed to the ring and the edge alike — two measurements against the same pixel, which is
    // what keeps a 2 dp focus ring at 3:1 distinguishable from a 1 dp resting edge at 2.2:1.
    val fill = MaterialTheme.colorScheme.surface
    Surface(
        modifier = modifier.height(40.dp).then(focus.ringOnShape(9f, fill)),
        shape = RoundedCornerShape(9.dp),
        color = fill,
        contentColor = MaterialTheme.colorScheme.primary,
        // Three buttons sitting on the sheet with 1.0259:1 light / 1.0812:1 dark of fill separation from it,
        // edged in `outlineVariant` at 1.1402 / 1.1648. Nothing said these were buttons rather than three
        // labels; the accent `contentColor` is the only surviving hint, and colour alone is not an affordance.
        border = androidx.compose.foundation.BorderStroke(
            ChromeGeometry.RESTING_EDGE_WIDTH.dp,
            with(NorfoldChrome) { restingEdge(fill) },
        ),
        interactionSource = focus.source,
        onClick = onClick,
    ) {
        Row(
            Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.Add, null, Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp))
            Text(label, maxLines = 1, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

/**
 * Sidebar body while a Doc is open: workspace switcher + the doc's table of contents,
 * replacing the workspace nav entirely. The editor's back button restores the nav.
 */
private enum class DocSidebarTab { Outline, People, History }

@Composable
private fun ChatSidebarContent(
    state: DocsUiState,
    viewModel: DocsViewModel,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    modifier: Modifier,
) {
    var topicsExpanded by remember { mutableStateOf(true) }
    var peopleExpanded by remember { mutableStateOf(true) }
    var groupsExpanded by remember { mutableStateOf(true) }
    var showCreateTopic by remember { mutableStateOf(false) }
    var showCreateGroup by remember { mutableStateOf(false) }
    var pendingChannelDelete by remember { mutableStateOf<ChatChannel?>(null) }
    val currentUsername = state.settings.syncUserName.ifBlank { "you" }
    val currentDisplay = state.settings.syncPublicName.ifBlank { currentUsername }
    val activeChannelId = state.activeChatChannelId
    val topics = state.chatChannels.filter { it.kind == ChatChannelKind.Topic }
    val groups = state.chatChannels.filter { it.kind == ChatChannelKind.Group }
    // People come from the synced roster (everyone in the workspace, whether or not they've
    // spoken), unioned with message authors so anyone who appears in the log but is missing from
    // a stale roster still shows. Self is pinned on top; the rest sort by display name.
    val people = remember(state.workspaceMembers, state.chatMessages, currentUsername, currentDisplay) {
        val byUsername = LinkedHashMap<String, String>()
        state.workspaceMembers
            .sortedBy { it.displayName.ifBlank { it.username }.lowercase() }
            .forEach { member ->
                byUsername[member.username] = member.displayName.ifBlank { member.username }
            }
        state.chatMessages
            .asReversed()
            .filter { !it.system }
            .forEach { msg ->
                byUsername.getOrPut(msg.authorUsername) {
                    msg.authorDisplayName.ifBlank { msg.authorUsername }
                }
            }
        // No self row: People lists teammates you can message, and you can't DM yourself.
        byUsername.remove(currentUsername)
        byUsername.toList()
    }

    if (showCreateTopic) {
        ChatChannelCreateDialog(
            title = "New channel",
            label = "Channel name",
            onDismiss = { showCreateTopic = false },
            onCreate = { viewModel.createChatTopic(it); showCreateTopic = false },
        )
    }
    if (showCreateGroup) {
        val contacts by viewModel.socialContacts.collectAsState()
        NewGroupDialog(
            contacts = contacts,
            onDismiss = { showCreateGroup = false },
            onCreate = { name, memberIds ->
                viewModel.createChatGroup(name, memberIds)
                showCreateGroup = false
            },
        )
    }
    pendingChannelDelete?.let { channel ->
        val kindLabel = if (channel.kind == ChatChannelKind.Group) "group" else "channel"
        NorfoldDialog(
            onDismissRequest = { pendingChannelDelete = null },
            title = { Text("Delete ${channel.name}?") },
            text = { Text("Deleting this $kindLabel also deletes all of its messages. This can't be undone.") },
            dismissButton = { TextButton(onClick = { pendingChannelDelete = null }) { Text("Cancel") } },
            confirmButton = {
                Button(onClick = {
                    viewModel.deleteChatChannel(channel.id)
                    pendingChannelDelete = null
                }) { Text("Delete") }
            },
        )
    }

    Column(
        modifier.fillMaxHeight(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        UnifiedSidebarHeader(state, viewModel, searchQuery, onSearchQueryChange)
        Text(
            "Chat",
            Modifier.padding(start = 14.dp, end = 14.dp, top = 10.dp, bottom = 2.dp),
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
        )
        if (searchQuery.isNotBlank()) {
            SidebarSearchResults(state, viewModel, searchQuery, Modifier.weight(1f))
        } else Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Spacer(Modifier.height(4.dp))
            SidebarDropdownHeader(
                label = "Channels",
                icon = SemanticActionId.AppTopics,
                expanded = topicsExpanded,
                selected = false,
                onToggle = { topicsExpanded = !topicsExpanded },
                onClick = { topicsExpanded = !topicsExpanded },
            )
            AnimatedVisibility(topicsExpanded) {
                Column(Modifier.padding(start = 22.dp)) {
                    // General is the shared, team-synced feed; it's always present and can't be removed.
                    SideNavItem("General", SemanticActionId.AppTopics, activeChannelId == null) {
                        viewModel.selectChatChannel(null)
                    }
                    topics.forEach { topic ->
                        ChatChannelRow(
                            label = topic.name,
                            icon = SemanticActionId.AppTopics,
                            selected = activeChannelId == topic.id,
                            onClick = { viewModel.selectChatChannel(topic.id) },
                            onDelete = { pendingChannelDelete = topic },
                        )
                    }
                    SideNavItem("New channel", SemanticActionId.Insert, false) { showCreateTopic = true }
                }
            }
            SidebarDropdownHeader(
                label = "People",
                icon = SemanticActionId.AppPeople,
                expanded = peopleExpanded,
                selected = false,
                onToggle = { peopleExpanded = !peopleExpanded },
                onClick = { peopleExpanded = !peopleExpanded },
            )
            AnimatedVisibility(peopleExpanded) {
                Column(Modifier.padding(start = 22.dp)) {
                    people.forEach { (username, display) ->
                        val dmChannel = state.chatChannels.firstOrNull {
                            it.kind == ChatChannelKind.Dm && it.counterpartUsername == username
                        }
                        SideNavItem(
                            display,
                            SemanticActionId.AppPeople,
                            dmChannel != null && activeChannelId == dmChannel.id,
                        ) {
                            viewModel.openDirectMessage(username, display)
                        }
                    }
                    SideNavItem("Invite people", SemanticActionId.Insert, false) {
                        viewModel.closeSidebar()
                        viewModel.goToWorkspaceMembers()
                    }
                }
            }
            // Groups only earn a section once one exists; "New group" lives at the tail of the
            // list so an empty section never renders.
            if (groups.isNotEmpty()) {
                SidebarDropdownHeader(
                    label = "Groups",
                    icon = SemanticActionId.AppGroups,
                    expanded = groupsExpanded,
                    selected = false,
                    onToggle = { groupsExpanded = !groupsExpanded },
                    onClick = { groupsExpanded = !groupsExpanded },
                )
                AnimatedVisibility(groupsExpanded) {
                    Column(Modifier.padding(start = 22.dp)) {
                        groups.forEach { group ->
                            ChatChannelRow(
                                label = group.name,
                                icon = SemanticActionId.AppGroups,
                                selected = activeChannelId == group.id,
                                onClick = { viewModel.selectChatChannel(group.id) },
                                onDelete = { pendingChannelDelete = group },
                            )
                        }
                        SideNavItem("New group", SemanticActionId.Insert, false) { showCreateGroup = true }
                    }
                }
            } else {
                SideNavItem("New group", SemanticActionId.AppGroups, false) { showCreateGroup = true }
            }
        }
    }
}

/** A channel row with a trailing delete affordance. Used for user-created topics and groups. */
@Composable
private fun ChatChannelRow(
    label: String,
    icon: SemanticActionId,
    selected: Boolean,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f)) {
            SideNavItem(label, icon, selected, onClick)
        }
        IconButton(onClick = onDelete, modifier = Modifier.size(34.dp)) {
            Icon(
                Icons.Outlined.Close,
                contentDescription = "Delete $label",
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ChatChannelCreateDialog(
    title: String,
    label: String,
    onDismiss: () -> Unit,
    onCreate: (String) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    NorfoldDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text(label) },
            )
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        confirmButton = {
            Button(onClick = { onCreate(name) }, enabled = name.isNotBlank()) { Text("Create") }
        },
    )
}

@Composable
private fun DocSidebarContent(
    owner: DocumentOwner,
    title: String,
    document: BlockDocument,
    locked: Boolean,
    state: DocsUiState,
    viewModel: DocsViewModel,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    modifier: Modifier,
) {
    val documentKey = owner.documentId
    val documentOnly = state.destination == Destination.DocumentEditor
    var selectedTab by remember(documentKey) { mutableStateOf(DocSidebarTab.Outline) }
    val presence by viewModel.documentPresence.collectAsState()
    val presenceStatus by viewModel.documentPresenceStatus.collectAsState()
    val activeBlockId by viewModel.activeDocumentBlockId.collectAsState()
    val objectId = remember(state.workspaceObjects, owner) {
        if (owner.type == DocumentOwnerType.Note) {
            state.workspaceObjects.firstOrNull { it.objectType == WorkspaceObjectType.Note && it.sourceId == owner.id }?.id
        } else null
    }
    val histories = remember(state.workspaceObjectHistory, objectId, activeBlockId) {
        state.workspaceObjectHistory
            .filter { objectId != null && it.objectId == objectId }
            .let { entries -> if (activeBlockId == null) entries else entries.filter { it.blockId == activeBlockId } }
            .sortedByDescending(WorkspaceObjectHistory::createdAt)
    }
    Column(
        modifier.fillMaxHeight(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        UnifiedSidebarHeader(state, viewModel, searchQuery, onSearchQueryChange)
        Text(
            title,
            Modifier.padding(start = 14.dp, end = 14.dp, top = 10.dp, bottom = 2.dp),
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (documentOnly) {
            DocumentPresenceStrip(presence, presenceStatus, activeBlockId) { viewModel.enterDocumentPresence(owner) }
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                DocSidebarTab.entries.forEach { tab ->
                    val selected = selectedTab == tab
                    Surface(
                        modifier = Modifier.weight(1f).focusableChrome(
                            radiusDp = 10f,
                            // The unselected tab is transparent, so its ring lands on the sidebar's own
                            // surface — not on the app background the default would assume.
                            surface = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                            role = Role.Tab,
                        ) { selectedTab = tab },
                        shape = RoundedCornerShape(10.dp),
                        color = if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                        contentColor = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                    ) {
                        Row(Modifier.padding(horizontal = 6.dp, vertical = 8.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                when (tab) {
                                    DocSidebarTab.Outline -> Icons.AutoMirrored.Outlined.FormatListBulleted
                                    DocSidebarTab.People -> Icons.Outlined.People
                                    DocSidebarTab.History -> Icons.Outlined.History
                                },
                                null,
                                Modifier.size(15.dp),
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(tab.name, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
        if (searchQuery.isNotBlank()) {
            SidebarSearchResults(state, viewModel, searchQuery, Modifier.weight(1f))
        } else Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Spacer(Modifier.height(2.dp))
            when (if (documentOnly) selectedTab else DocSidebarTab.Outline) {
                DocSidebarTab.Outline -> DocTableOfContents(documentKey, document, locked, viewModel)
                DocSidebarTab.People -> DocumentPeoplePanel(presence, histories, activeBlockId, presenceStatus)
                DocSidebarTab.History -> DocumentHistoryPanel(histories, activeBlockId, viewModel)
            }
        }
    }
}

/**
 * Who else is in this document, and the sync status.
 *
 * ## The status line is also a button, some of the time
 *
 * When presence fails to connect, [NotesViewModel] sets the status to `"Couldn't connect presence · tap to
 * retry"` and the strip's own status text becomes the retry control. That is discovered here by a substring
 * match on the status, which is worth naming as fragile rather than leaving implicit: the producer sets eight
 * different strings and only that one is retryable, so if its wording ever loses the word "retry" the
 * affordance disappears silently — no compile error, no test failure, just a dead control. The match is kept
 * because the alternative is a second state flow for one boolean, but it belongs in the same commit as any
 * change to that string.
 *
 * The match is also now gated on `activeBlockId == null`, which fixes a real mismatch rather than tightening
 * a condition for its own sake: with a block selected the strip shows `"N on selected block"` instead of the
 * status, but the old guard still read the *hidden* status, so a failed connection made the block-count line
 * silently retry-on-tap. The control is offered only when the text the user can read is the one that asked
 * for it.
 *
 * When it *is* a button it now says so: an `onClickLabel` and `Role.Button`, so a screen reader announces an
 * action instead of reading a sentence, and a ring, so a keyboard user can see where the action is. The
 * padding exists for the ring — a bare 11 sp line is ~14 dp tall, and an inward ring at the substrate's 3 dp
 * inset would leave 4 dp of text between its two strokes. Six dp of padding makes the box ~26 dp, still under
 * the 30 dp avatars beside it, so the strip does not change height.
 */
@Composable
private fun DocumentPresenceStrip(
    presence: List<com.norfold.app.cloud.DocumentPresenceState>,
    status: String,
    activeBlockId: String?,
    onRetry: () -> Unit,
) {
    val relevant = if (activeBlockId == null) presence else presence.filter { it.blockId == activeBlockId || it.blockId == null }
    val canRetry = activeBlockId == null && status.contains("retry", ignoreCase = true)
    val retryFocus = rememberChromeFocus()
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            relevant.take(4).forEach { person -> CollaboratorAvatar(person.displayName, person.color, person.editing) }
            if (relevant.size > 4) {
                Surface(Modifier.size(30.dp), shape = CircleShape, color = MaterialTheme.colorScheme.surfaceVariant) {
                    Box(contentAlignment = Alignment.Center) { Text("…", fontWeight = FontWeight.Bold) }
                }
            }
        }
        Text(
            if (activeBlockId == null) status else "${relevant.size} on selected block",
            Modifier
                .weight(1f)
                .then(
                    if (canRetry) {
                        Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable(
                                interactionSource = retryFocus.source,
                                indication = ripple(),
                                onClickLabel = "Retry connecting presence",
                                role = Role.Button,
                                onClick = onRetry,
                            )
                            .then(retryFocus.ringOnShape(8f, MaterialTheme.colorScheme.surface))
                            .padding(horizontal = 6.dp, vertical = 6.dp)
                    } else {
                        Modifier
                    },
                ),
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun CollaboratorAvatar(name: String, color: Long, active: Boolean) {
    Surface(
        Modifier.size(30.dp),
        shape = CircleShape,
        color = Color(color),
        // Neither of these is a resting edge, so neither routes through the substrate. The `surface` ring is a
        // *cutout*: four avatars are laid out overlapping, and it is what keeps the one in front from merging
        // into the one behind — it is deliberately the page's colour, not a contrast against it. The `primary`
        // ring at 2 dp is state ("this person is editing right now"), which is the same reason a selected
        // picker edge stays a literal. The fill under both is the collaborator's identity colour, so a solved
        // neutral here would discard exactly the information the avatar exists to carry.
        border = androidx.compose.foundation.BorderStroke(if (active) 2.dp else 1.dp, if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(name.trim().firstOrNull()?.uppercase() ?: "?", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun DocumentPeoplePanel(
    presence: List<com.norfold.app.cloud.DocumentPresenceState>,
    histories: List<WorkspaceObjectHistory>,
    activeBlockId: String?,
    status: String,
) {
    val active = if (activeBlockId == null) presence else presence.filter { it.blockId == activeBlockId }
    val past = histories.map(WorkspaceObjectHistory::actor).filter(String::isNotBlank).distinct()
    Column(Modifier.fillMaxWidth().padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(if (activeBlockId == null) "Active now" else "Active on selected block", fontSize = 12.sp, fontWeight = FontWeight.Bold)
        if (active.isEmpty()) {
            Text(status, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else active.forEach { person ->
            PersonRow(person.displayName, if (person.editing) "Editing now" else "Viewing", person.color)
        }
        HorizontalDivider()
        Text(if (activeBlockId == null) "Past editors" else "Worked on this block", fontSize = 12.sp, fontWeight = FontWeight.Bold)
        if (past.isEmpty()) {
            Text("No recorded editors yet", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else past.forEach { actor -> PersonRow(actor, "Recorded in history", collaboratorColor(actor)) }
    }
}

@Composable
private fun PersonRow(name: String, detail: String, color: Long) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        CollaboratorAvatar(name, color, active = false)
        Column(Modifier.weight(1f)) {
            Text(name, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(detail, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun DocumentHistoryPanel(histories: List<WorkspaceObjectHistory>, activeBlockId: String?, viewModel: DocsViewModel) {
    Column(Modifier.fillMaxWidth().padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(if (activeBlockId == null) "Document history" else "Selected block history", fontSize = 12.sp, fontWeight = FontWeight.Bold)
        if (histories.isEmpty()) {
            Text("No changes recorded yet", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else histories.forEach { entry -> DocumentHistoryRow(entry) { viewModel.requestBlockRestore(entry) } }
    }
}

@Composable
private fun DocumentHistoryRow(entry: WorkspaceObjectHistory, onRestore: () -> Unit) {
    var expanded by remember(entry.id) { mutableStateOf(false) }
    val fill = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.38f)
    Surface(
        Modifier.fillMaxWidth().focusableChrome(radiusDp = 12f, surface = fill) { expanded = !expanded },
        shape = RoundedCornerShape(12.dp),
        color = fill,
    ) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CollaboratorAvatar(entry.actor, collaboratorColor(entry.actor), active = false)
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(entry.summary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    Text("${entry.actor} · ${historyRelativeTime(entry.createdAt)}", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, if (expanded) "Collapse change" else "Expand change", Modifier.size(18.dp))
            }
            if (expanded) {
                if (entry.beforeValue.isNotBlank()) Text("Before · ${historyValuePreview(entry.beforeValue)}", fontSize = 10.sp, color = MaterialTheme.colorScheme.error)
                if (entry.afterValue.isNotBlank()) Text("After · ${historyValuePreview(entry.afterValue)}", fontSize = 10.sp, color = MaterialTheme.colorScheme.primary)
                if (entry.blockId != null) {
                    Surface(
                        // Nested inside the expandable row, but a distinct destructive-ish action rather than
                        // a sub-region of it, so it takes its own stop. Measured on `primaryContainer`.
                        modifier = Modifier.focusableChrome(
                            radiusDp = 8f,
                            surface = MaterialTheme.colorScheme.primaryContainer,
                            onClick = onRestore,
                        ),
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    ) {
                        Text("Restore this version", Modifier.padding(horizontal = 10.dp, vertical = 7.dp), fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}

private fun collaboratorColor(name: String): Long {
    val colors = longArrayOf(0xFF8B5CF6, 0xFF2563EB, 0xFF0D9488, 0xFFDB2777, 0xFFEA580C, 0xFF7C3AED)
    return colors[(name.hashCode() and Int.MAX_VALUE) % colors.size]
}

private fun historyValuePreview(value: String): String =
    runCatching { BlockDocumentJson.decodeBlock(value).plainText().ifBlank { "Structured block" } }
        .getOrElse { value }
        .replace('\n', ' ')
        .take(180)

private fun historyRelativeTime(createdAt: Long): String {
    val delta = (System.currentTimeMillis() - createdAt).coerceAtLeast(0L)
    return when {
        delta < 60_000L -> "just now"
        delta < 3_600_000L -> "${delta / 60_000L}m ago"
        delta < 86_400_000L -> "${delta / 3_600_000L}h ago"
        else -> "${delta / 86_400_000L}d ago"
    }
}

/**
 * The open Doc's table of contents — the sidebar's whole body while inside a Doc (viewing or
 * editing). Tapping a heading fires [DocsViewModel.scrollToBlock]; the editor observes the
 * request and scrolls (or, in free-canvas mode, pans) to the heading's top-level block.
 *
 * Interactions: a caret collapses/expands a heading's run of deeper headings (UI-only state);
 * long-pressing a section heading (top-level, unlocked note) opens a move/duplicate/delete menu;
 * the trailing drag handle reorders whole sections. Mutations go through
 * [DocsViewModel.requestSectionAction] so the editor applies them to its live session — the
 * sidebar only ever sees the persisted note, which trails the session by the autosave debounce.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DocTableOfContents(documentKey: String, document: BlockDocument, locked: Boolean, viewModel: DocsViewModel) {
    val headings = remember(documentKey, document) { DocOutline.extract(document) }
    // Heading ids that own deeper headings right after them — only these get a caret.
    val hasChildren = remember(headings) {
        headings.mapIndexedNotNull { i, h ->
            h.blockId.takeIf { i + 1 < headings.size && headings[i + 1].level > h.level }
        }.toSet()
    }
    var collapsed by remember(documentKey) { mutableStateOf(setOf<String>()) }
    var query by remember(documentKey) { mutableStateOf("") }
    val filtering = query.isNotBlank()
    var menuFor by remember(documentKey) { mutableStateOf<String?>(null) }
    // Section drag state: row bounds in the ToC column's space + the dragged row's finger offset.
    val rowBounds = remember(documentKey) { mutableStateMapOf<String, Rect>() }
    var draggedId by remember(documentKey) { mutableStateOf<String?>(null) }
    var dragOffsetY by remember(documentKey) { mutableFloatStateOf(0f) }

    // While filtering: matches plus their ancestors (so context survives), collapse ignored.
    // Otherwise: walk the flat list skipping runs hidden under a collapsed heading.
    val visibleHeadings = remember(headings, query, collapsed) {
        if (query.isNotBlank()) {
            val q = query.trim()
            val keep = BooleanArray(headings.size)
            val ancestors = ArrayDeque<Int>()
            headings.forEachIndexed { i, h ->
                while (ancestors.isNotEmpty() && headings[ancestors.last()].level >= h.level) ancestors.removeLast()
                if (h.label.contains(q, ignoreCase = true)) {
                    keep[i] = true
                    ancestors.forEach { keep[it] = true }
                }
                ancestors.addLast(i)
            }
            headings.filterIndexed { i, _ -> keep[i] }
        } else {
            buildList {
                var skipDeeper: Int? = null
                for (h in headings) {
                    if (skipDeeper != null && h.level > skipDeeper) continue
                    skipDeeper = null
                    add(h)
                    if (h.blockId in collapsed) skipDeeper = h.level
                }
            }
        }
    }

    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
            .padding(vertical = 6.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.AutoMirrored.Outlined.FormatListBulleted, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(8.dp))
            Text(
                "On this page",
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (headings.size > 4) {
            Row(
                Modifier
                    .padding(horizontal = 10.dp, vertical = 4.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.6f))
                    .padding(horizontal = 10.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.Search, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(6.dp))
                Box(Modifier.weight(1f)) {
                    if (query.isEmpty()) {
                        Text("Filter headings", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    BasicTextField(
                        value = query,
                        onValueChange = { query = it },
                        singleLine = true,
                        textStyle = TextStyle(fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (query.isNotEmpty()) {
                    val clearFilterFocus = rememberChromeFocus()
                    Icon(
                        Icons.Outlined.Close,
                        "Clear filter",
                        // Same treatment as the search field's clear button above, and for the same reason:
                        // 16 dp leaves 8 dp inside the ring inset, which does not read as a ring. The ring
                        // sits outside `minimumInteractiveComponentSize` so it measures the expanded box,
                        // and `size` comes last so it does not fight the expansion.
                        Modifier
                            .clip(CircleShape)
                            .clickable(
                                interactionSource = clearFilterFocus.source,
                                indication = ripple(),
                                onClickLabel = "Clear filter",
                                onClick = { query = "" },
                            )
                            .then(clearFilterFocus.ringOnCircle(48f, MaterialTheme.colorScheme.surface))
                            .minimumInteractiveComponentSize()
                            .size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        if (headings.isEmpty()) {
            Text(
                "No headings yet",
                Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else if (visibleHeadings.isEmpty()) {
            Text(
                "No matching headings",
                Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            visibleHeadings.forEach { heading ->
                val id = heading.blockId
                // Only top-level headings own a contiguous section (nested ones live inside a
                // quote/callout/container and share their ancestor's range) — only they get
                // the long-press actions and the reorder handle.
                val isSection = id == heading.topLevelId
                val isDragged = draggedId == id
                Box(
                    Modifier
                        .fillMaxWidth()
                        .onGloballyPositioned { rowBounds[id] = it.boundsInParent() }
                        .zIndex(if (isDragged) 1f else 0f)
                        .graphicsLayer { translationY = if (isDragged) dragOffsetY else 0f },
                ) {
                    val tocFocus = rememberChromeFocus()
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (isDragged) MaterialTheme.colorScheme.surface else Color.Transparent)
                            // Transparent unless dragged, so the ring is measured on the sidebar panel's own
                            // `surface` — the colour the row actually shows through to.
                            .then(tocFocus.ringOnShape(8f, MaterialTheme.colorScheme.surface))
                            .combinedClickable(
                                interactionSource = tocFocus.source,
                                indication = androidx.compose.material3.ripple(),
                                onClickLabel = "Jump to heading",
                                onLongClickLabel = if (isSection && !locked) "Section actions" else null,
                                onClick = {
                                    viewModel.scrollToBlock(id)
                                    viewModel.closeSidebar()
                                },
                                onLongClick = if (isSection && !locked) ({ menuFor = id }) else null,
                            )
                            .padding(start = (6 + (heading.level.coerceAtMost(4) - 1) * 12).dp, end = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (id in hasChildren) {
                            val twistFocus = rememberChromeFocus()
                            Icon(
                                if (id in collapsed) Icons.Outlined.ChevronRight else Icons.Outlined.ExpandMore,
                                if (id in collapsed) "Expand" else "Collapse",
                                // A second focus stop *inside* a row that is itself a focus stop, so when
                                // this holds focus the row's ring is not drawn and this is the only
                                // indication there is. Ringed at its own 22 dp rather than expanded to 48 by
                                // `minimumInteractiveComponentSize`: the two clear buttons in this file can
                                // expand because each is alone in its trailing slot, whereas this sits in a
                                // ~33 dp outline row, and growing its layout box would grow every row in the
                                // panel by half again. The undersized *target* is real and is a target-size
                                // question, not a focus one; the ring does not need the extra space, because
                                // a 22 dp circle leaves 8 dp of radius inside the inset.
                                Modifier
                                    .size(22.dp)
                                    .clip(CircleShape)
                                    .clickable(
                                        interactionSource = twistFocus.source,
                                        indication = ripple(),
                                        enabled = !filtering,
                                    ) {
                                        collapsed = if (id in collapsed) collapsed - id else collapsed + id
                                    }
                                    .then(twistFocus.ringOnCircle(22f, MaterialTheme.colorScheme.surface))
                                    .padding(3.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            Spacer(Modifier.width(22.dp))
                        }
                        Text(
                            heading.label,
                            Modifier.weight(1f).padding(horizontal = 4.dp, vertical = 7.dp),
                            fontSize = 13.sp,
                            fontWeight = if (heading.level <= 1) FontWeight.SemiBold else FontWeight.Normal,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (isSection && !locked && !filtering) {
                            Icon(
                                Icons.Outlined.DragIndicator,
                                "Reorder section",
                                Modifier
                                    .size(20.dp)
                                    .pointerInput(id) {
                                        detectDragGestures(
                                            onDragStart = {
                                                draggedId = id
                                                dragOffsetY = 0f
                                            },
                                            onDrag = { change, amount ->
                                                change.consume()
                                                dragOffsetY += amount.y
                                            },
                                            onDragCancel = {
                                                draggedId = null
                                                dragOffsetY = 0f
                                            },
                                            onDragEnd = {
                                                val bounds = rowBounds[id]
                                                if (bounds != null) {
                                                    val center = bounds.center.y + dragOffsetY
                                                    // Drop before the first visible section row whose center
                                                    // sits below the dragged row's center; none = move to end.
                                                    // The editor resolves ids against its live session, so a
                                                    // stale sidebar document can't corrupt indices.
                                                    val target = visibleHeadings.firstOrNull { h ->
                                                        h.blockId != id && h.blockId == h.topLevelId &&
                                                            (rowBounds[h.blockId]?.center?.y ?: Float.NEGATIVE_INFINITY) > center
                                                    }
                                                    viewModel.requestSectionAction(id, DocSectionAction.MoveBefore(target?.blockId))
                                                }
                                                draggedId = null
                                                dragOffsetY = 0f
                                            },
                                        )
                                    },
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (isDragged) 1f else 0.55f),
                            )
                        }
                        // The untimed route to the section menu. Dragging the handle beside this reorders,
                        // but it is touch-only and reaches only one of the four actions — Duplicate and
                        // Delete had no route at all that did not involve holding a key for
                        // `longPressTimeoutMillis`, which WCAG 2.1.1 rules out. Guarded by the same
                        // `isSection && !locked` as the long-press and its label, so a nested heading or a
                        // locked document grows no control for a menu it cannot open. Unlike the drag
                        // handle it is *not* also gated on `!filtering`: a filtered list can still reorder
                        // and delete through the menu, and only the drag needs the unfiltered geometry.
                        if (isSection && !locked) {
                            IconButton(onClick = { menuFor = id }, modifier = Modifier.size(28.dp)) {
                                Icon(Icons.Outlined.MoreVert, "Section actions", Modifier.size(16.dp))
                            }
                        }
                    }
                    NorfoldDropdownMenu(expanded = menuFor == id, onDismissRequest = { menuFor = null }) {
                        DropdownMenuItem(text = { Text("Move up") }, onClick = {
                            menuFor = null
                            viewModel.requestSectionAction(id, DocSectionAction.MoveUp)
                        })
                        DropdownMenuItem(text = { Text("Move down") }, onClick = {
                            menuFor = null
                            viewModel.requestSectionAction(id, DocSectionAction.MoveDown)
                        })
                        DropdownMenuItem(text = { Text("Duplicate") }, onClick = {
                            menuFor = null
                            viewModel.requestSectionAction(id, DocSectionAction.Duplicate)
                        })
                        DropdownMenuItem(text = { Text("Delete") }, onClick = {
                            menuFor = null
                            viewModel.requestSectionAction(id, DocSectionAction.Delete)
                        })
                    }
                }
            }
        }
    }
}

/**
 * Top-level nav row with an expandable child list: tapping the row navigates,
 * tapping the trailing chevron only toggles the dropdown.
 *
 * ## Focus
 *
 * Eight call sites, and the sibling of [SideNavItem] in every way that matters to a keyboard user: same
 * width, same 14 dp radius, same flush packing, stacked directly among the plain nav items. It got no ring
 * while the plain items did, which is the worst arrangement of the two — tabbing down the sidebar, the
 * indicator would appear on "All notes" and vanish on "Notes" directly above it, so the section headers read
 * as unreachable rather than as focused.
 *
 * The ring is measured against the row's **own fill**, not the page, because this row paints one: a selected
 * header sits under `primary` at 12%. That is the case [ChromeFocus.ringOnCircle] documents at length — a
 * ring solved against a colour it is not drawn on is solved against a pixel that is never painted — and the
 * unselected branch costs nothing, because `Color.Transparent` composited onto the page *is* the page.
 */
@Composable
private fun SidebarDropdownHeader(
    label: String,
    icon: ImageVector,
    expanded: Boolean,
    selected: Boolean,
    onToggle: () -> Unit,
    onClick: () -> Unit,
) = SidebarDropdownHeader(label, expanded, selected, onToggle, onClick) { tint ->
    Icon(icon, null, Modifier.size(22.dp), tint = tint)
}

@Composable
private fun SidebarDropdownHeader(
    label: String,
    icon: SemanticActionId,
    expanded: Boolean,
    selected: Boolean,
    onToggle: () -> Unit,
    onClick: () -> Unit,
) = SidebarDropdownHeader(label, expanded, selected, onToggle, onClick) { tint ->
    SemanticAppIcon(icon, null, Modifier.size(22.dp), tint)
}

@Composable
private fun SidebarDropdownHeader(
    label: String,
    expanded: Boolean,
    selected: Boolean,
    onToggle: () -> Unit,
    onClick: () -> Unit,
    iconContent: @Composable (Color) -> Unit,
) {
    val focus = rememberChromeFocus()
    val fill =
        if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f) else Color.Transparent
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Corner.Media.radiusDp.dp))
            .background(fill)
            .clickable(interactionSource = focus.source, indication = ripple(), onClick = onClick)
            .then(focus.ring(Corner.Media, backdrop = fill))
            .padding(start = 14.dp, end = 4.dp, top = 3.dp, bottom = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        iconContent(if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(12.dp))
        Text(
            label,
            Modifier.weight(1f),
            fontSize = 14.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
        )
        IconButton(onClick = onToggle, modifier = Modifier.size(40.dp)) {
            Icon(
                if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.UnfoldMore,
                if (expanded) "Collapse $label" else "Expand $label",
                Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SideNavItem(label: String, icon: ImageVector, selected: Boolean, onClick: () -> Unit) =
    SideNavItem(label, selected, onClick) { tint -> Icon(icon, null, tint = tint, modifier = Modifier.size(22.dp)) }

@Composable
private fun SideNavItem(label: String, icon: SemanticActionId, selected: Boolean, onClick: () -> Unit) =
    SideNavItem(label, selected, onClick) { tint -> SemanticAppIcon(icon, null, Modifier.size(22.dp), tint) }

/**
 * One sidebar destination.
 *
 * ## Selection and focus are two different signals, drawn differently on purpose
 *
 * This row already says a great deal about selection — an animated 12%-tint pill behind it, a 4 dp accent
 * bar at its left edge, a heavier label, an accent tint on both icon and text. None of that is focus.
 * Selection means "this is where you are"; focus means "this is what Enter will activate", and on a sidebar
 * they are routinely different rows: a keyboard user tabbing down the nav is focused on rows they have not
 * selected. Reusing any part of the selection vocabulary for focus would make the two indistinguishable and
 * would light the pill on a destination the user has not opened.
 *
 * So focus is a ring, and it is drawn *inward*: these rows are packed flush with 2-4 dp between them, so a
 * ring outside the bounds would overlap the neighbour above and below. Inward also costs no layout space,
 * which matters here because the selection pill is `matchParentSize` — a ring that resized the row would
 * resize the pill with it.
 *
 * ## The ring is measured against the pill, not against the page
 *
 * Being right about the *difference* between selection and focus is not the same as being right about the
 * contrast of either. This row draws its focus ring **on top of** the `matchParentSize` selection pill, and
 * the pill is a 12% wash of `primary` — while the ring's own default colour is `primary` too. Solved against
 * the page and drawn on the pill, the ring is measured against a colour it is never drawn on, and it is
 * measured against the one colour guaranteed to be closest to itself.
 *
 * Measured over the ten shipped accents on the light page, that cost **3 of 10 accents** WCAG 1.4.11's 3:1
 * whenever the row was selected — Sunset 2.64:1, Gold 2.70:1, Forest 2.79:1 — which is a focus indicator that
 * disappears on the destination the user is currently in. Solved against the pill instead, the page and the
 * 300 dp overlay drawer both pass **10 of 10 in both states**: worst on the page is Gold 3.0629:1 selected and
 * Gold 3.0037:1 resting, worst in the drawer is Sunset 3.0172:1 selected and Gold 3.0485:1 resting. Dark mode
 * passed either way.
 *
 * **The alpha passed is the settled 0.12, not the animating `bgAlpha`.** Two reasons, and the first is the
 * substantive one: sweeping the whole fade shows the ratio is monotone in the pill's alpha for every accent in
 * every host, so the settled pill is the *hardest* case and solving against it covers every frame on the way
 * there. Threading the animated value through would also re-solve the ring on every frame of a 200 ms tween
 * and make the ring's own colour visibly drift, which is a second signal the user did not ask for.
 *
 * **The third host needed the other half of the fix.** [SidebarContent] appears on the expanded page, in a
 * 300 dp overlay drawer, and inside [NorfoldBottomSheet] — and the backdrop argument above reaches only the
 * first two. In the sheet the pill is a *translucent* wash composited onto whatever the sheet paints, which is
 * Material's `surfaceContainerLow`; the substrate was compositing it onto the page instead, and the selected
 * ring still failed **4 of 10** there — Gold 2.9430:1, Sunset 2.9747:1, Forest 2.9936:1 and Candy
 * **2.9999964:1**, all within 0.06 of the bar and one of them within four millionths of it. That is not
 * something this row can state, because a row cannot know its own host. It is
 * [com.norfold.app.ui.LocalChromeContainer], which [NorfoldBottomSheet] provides and the substrate composites
 * onto; with it the sheet passes **10 of 10 in both states** (worst Midnight 3.0039:1 selected, Gold 3.0233:1
 * resting), the same one root that fixed the dialog rows and the popup rows.
 *
 * **Every figure here is quoted to four decimals, and Candy is why.** This population was documented as 3 of 10
 * until `ChromeGeometryTest` was run against `ColorValue`'s own arithmetic instead of a hand-computed model.
 * `Color.copy(alpha = 0.12f)` does not composite at 0.12: sRGB `Color` holds 8-bit channels, so the alpha
 * quantizes to 31/255 = 0.121569. That 1.3% moves one channel by a single bit on three accents — Candy's green
 * lands on 225 rather than 226 — and on the narrowest population a single bit is the whole finding. Rounded to
 * two decimals Candy reads `3.00`, which is indistinguishable from passing.
 *
 * What remains is not an accessibility question, and the theme has since answered it: `surfaceContainerLow` was
 * Material's baseline `#F7F2FA` when the figures above were measured, and is now a derived `#FBFCFF` in Norfold's
 * own hue. This row needed no edit for that, which is the point of measuring the painted colour — though it is
 * worth recording that the new value is *lighter* than the page, so for this host the old page-guess is no longer
 * the pessimistic one. The dialog rows are the counter-example: their container moved the other way.
 */
@Composable
private fun SideNavItem(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    iconContent: @Composable (Color) -> Unit,
) {
    val bgAlpha by animateFloatAsState(
        targetValue = if (selected) 1f else 0f,
        animationSpec = tween(200, easing = FastOutSlowInEasing),
        label = "bg",
    )
    val indicatorWidth by animateDpAsState(
        targetValue = if (selected) 4.dp else 0.dp,
        animationSpec = tween(250, easing = FastOutSlowInEasing),
        label = "indicator",
    )
    val focus = rememberChromeFocus()

    Box(Modifier.fillMaxWidth()) {
        // Animated selection pill background
        if (bgAlpha > 0.01f) {
            Box(
                Modifier
                    .matchParentSize()
                    .padding(vertical = 2.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f * bgAlpha)),
            )
        }
        // Left indicator bar
        if (indicatorWidth > 0.dp) {
            Box(
                Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 4.dp)
                    .width(indicatorWidth)
                    .height(20.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(MaterialTheme.colorScheme.primary),
            )
        }
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .clickable(interactionSource = focus.source, indication = ripple(), onClick = onClick)
                // The settled pill, translucent — the substrate composites it over the page itself, so this
                // stays one expression of "the wash this row draws" rather than a second copy of the maths.
                .then(focus.ring(Corner.Media, MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)))
                .padding(horizontal = 14.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            iconContent(if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(12.dp))
            Text(
                label,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                fontSize = 14.sp,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}
