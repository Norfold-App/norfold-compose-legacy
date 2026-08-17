package com.norfold.app.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.PersonAddAlt
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.Tag
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.norfold.app.cloud.NorfoldSocial
import com.norfold.app.cloud.ChatBackend
import com.norfold.app.domain.ChatChannel
import com.norfold.app.domain.ChatChannelKind
import com.norfold.app.domain.ChatMessageItem
import com.norfold.app.ui.DocsUiState
import com.norfold.app.ui.DocsViewModel
import com.norfold.app.ui.components.NorfoldContentDialog
import com.norfold.app.ui.components.NorfoldDialog
import com.norfold.app.ui.components.NorfoldEmptyState
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * The Chat tab landing surface: an inbox-first conversation list (WhatsApp/Telegram base with
 * Discord-style channels), replacing the old sidebar-driven "admin panel" chat. The bottom navbar
 * stays visible here; opening a row navigates into the conversation, where the composer takes the
 * navbar's place.
 *
 * Adaptive density: with nothing to show the screen is a hero empty state; with a handful of
 * conversations it's a plain list; once the list is busy, search, filter chips, and the Pinned
 * section appear.
 */
@Composable
fun ChatHomeScreen(state: DocsUiState, viewModel: DocsViewModel) {
    var query by remember { mutableStateOf("") }
    // The chat-context navbar (Tier 2) drives the inbox filter: Chats→All, People→People,
    // Requests→pending (falls back to People for now). The in-list chips still fine-tune below.
    val chatSection by viewModel.chatSection.collectAsState()
    var filterOverride by remember { mutableStateOf<ChatInboxFilter?>(null) }
    val filter = filterOverride ?: when (chatSection) {
        com.norfold.app.domain.ChatSection.People -> ChatInboxFilter.People
        else -> ChatInboxFilter.All
    }
    // Reset any manual chip override when the navbar section changes.
    androidx.compose.runtime.LaunchedEffect(chatSection) { filterOverride = null }
    var showCreateChannel by remember { mutableStateOf(false) }
    var showCreateGroup by remember { mutableStateOf(false) }
    var showNewDm by remember { mutableStateOf(false) }
    var showContacts by remember { mutableStateOf(false) }
    var showStarred by remember { mutableStateOf(false) }
    var createMenuOpen by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<ChatChannel?>(null) }
    var archiveRestoreProvider by remember { mutableStateOf<String?>(null) }
    var archiveRecoveryMaterial by remember { mutableStateOf("") }
    val requests by viewModel.contactRequests.collectAsState()
    val requestBusyIds by viewModel.contactRequestBusyIds.collectAsState()
    val requestPreferences by viewModel.messageRequestPreferences.collectAsState()
    val serviceCapabilities by viewModel.chatServiceCapabilities.collectAsState()
    val personalConversations by viewModel.personalConversations.collectAsState()
    val privateArchive by viewModel.privateChatArchiveState.collectAsState()
    val archiveRestorePending = privateArchive.status.startsWith("Earlier encrypted Chat archive found")
    LaunchedEffect(Unit) {
        viewModel.refreshSocialGraph()
        viewModel.refreshChatServiceCapabilities()
    }

    val currentUsername = state.settings.syncUserName.ifBlank { "you" }
    // chatReadSerial bumps whenever a conversation is opened/left, recomputing the badges.
    val readSerial by viewModel.chatReadSerial.collectAsState()
    val hiddenRequestConversationIds = remember(personalConversations) {
        personalConversations
            .filter { it.inboundRequestStatus == "pending" || it.inboundRequestStatus == "declined" }
            .mapTo(hashSetOf(), NorfoldSocial.RemoteDirectConversation::conversationId)
    }
    val conversations = remember(
        state.chatChannels,
        state.chatMessages,
        currentUsername,
        readSerial,
        hiddenRequestConversationIds,
    ) {
        buildInbox(state.chatChannels, state.chatMessages)
            .filterNot { it.channel?.accountConversationId in hiddenRequestConversationIds }
            .map { row ->
            val lastRead = viewModel.chatLastReadAt(row.channelId)
            row.copy(
                unreadCount = state.chatMessages.count {
                    it.channelId == row.channelId &&
                        !it.system &&
                        it.authorUsername != currentUsername &&
                        it.createdAt > lastRead
                },
            )
            }
    }
    // People who are in the roster or the message log but have no DM conversation yet. Self is
    // excluded — there's no "message yourself" row and no "(you)" entry in this design.
    val startableDms = remember(state.workspaceMembers, state.chatMessages, state.chatChannels, currentUsername) {
        val existingDms = state.chatChannels
            .filter { it.kind == ChatChannelKind.Dm }
            .map { it.counterpartUsername }
            .toSet()
        val byUsername = LinkedHashMap<String, String>()
        state.workspaceMembers
            .sortedBy { it.displayName.ifBlank { it.username }.lowercase() }
            .forEach { byUsername[it.username] = it.displayName.ifBlank { it.username } }
        state.chatMessages.asReversed().filter { !it.system }.forEach { msg ->
            byUsername.getOrPut(msg.authorUsername) { msg.authorDisplayName.ifBlank { msg.authorUsername } }
        }
        byUsername.remove(currentUsername)
        byUsername.filterKeys { it !in existingDms }.toList()
    }

    val busy = conversations.size >= BusyInboxThreshold
    val filtered = remember(conversations, query, filter) {
        conversations
            .filter { filter.matches(it) }
            .filter { query.isBlank() || it.title.contains(query.trim(), ignoreCase = true) }
    }
    val pinned = filtered.filter { it.pinned }
    val unpinned = filtered.filterNot { it.pinned }
    // Direct (account-scoped DMs + personal groups) follow the user across workspaces;
    // workspace channels belong to the current workspace only.
    val direct = unpinned.filter { it.scope == com.norfold.app.domain.ChatScope.Account }
    val workspaceRows = unpinned.filterNot { it.scope == com.norfold.app.domain.ChatScope.Account }
    val workspaceLabel = state.settings.workspaceName.ifBlank { "Workspace" }

    val listState = rememberLazyListState()
    // Hide the compose FAB while scrolling down the list; bring it back at the top.
    val fabVisible by remember {
        derivedStateOf { listState.firstVisibleItemIndex == 0 || !listState.canScrollForward }
    }

    if (showCreateChannel) {
        ChatInboxCreateDialog(
            title = "New channel",
            label = "Channel name",
            onDismiss = { showCreateChannel = false },
            onCreate = { viewModel.createChatTopic(it); showCreateChannel = false },
        )
    }
    if (showCreateGroup) {
        val contacts by viewModel.socialContacts.collectAsState()
        NewGroupDialog(
            contacts = contacts,
            maxSelectedMembers = (serviceCapabilities.personalGroupMembersMax - 1).coerceAtLeast(1),
            onDismiss = { showCreateGroup = false },
            onCreate = { name, memberIds ->
                viewModel.createChatGroup(name, memberIds)
                showCreateGroup = false
            },
        )
    }
    if (showNewDm) {
        NewDirectMessageDialog(
            people = startableDms,
            onDismiss = { showNewDm = false },
            onPick = { username, display ->
                showNewDm = false
                viewModel.openDirectMessage(username, display)
            },
        )
    }
    if (showContacts) {
        ContactsSheet(state, viewModel, onDismiss = { showContacts = false })
    }
    if (showStarred) {
        StarredMessagesSheet(
            messages = state.chatMessages.filter(ChatMessageItem::starred).sortedByDescending(ChatMessageItem::createdAt),
            channels = state.chatChannels,
            onOpen = { message ->
                showStarred = false
                viewModel.selectChatChannel(message.channelId)
                viewModel.flashChatMessage(message.id)
            },
            onDismiss = { showStarred = false },
        )
    }
    pendingDelete?.let { channel ->
        val kindLabel = when (channel.kind) {
            ChatChannelKind.Group -> "group"
            ChatChannelKind.Dm -> "conversation"
            ChatChannelKind.Topic -> "channel"
        }
        NorfoldDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete ${channel.name}?") },
            text = { Text("Deleting this $kindLabel also deletes its messages for everyone in the workspace. This can't be undone.") },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("Cancel") } },
            confirmButton = {
                Button(onClick = {
                    viewModel.deleteChatChannel(channel.id)
                    pendingDelete = null
                }) { Text("Delete") }
            },
        )
    }
    archiveRestoreProvider?.let { providerId ->
        NorfoldDialog(
            onDismissRequest = {
                archiveRestoreProvider = null
                archiveRecoveryMaterial = ""
            },
            title = { Text("Restore private Chat history?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        if (providerId == "local-device") {
                            "Norfold will first use this device's protected key. If Android can no longer unlock it, enter the recovery material you saved earlier."
                        } else {
                            "Enter the portable recovery material saved from a trusted device. It never goes to Norfold or the archive provider."
                        },
                    )
                    OutlinedTextField(
                        value = archiveRecoveryMaterial,
                        onValueChange = { archiveRecoveryMaterial = it.trim() },
                        label = {
                            Text(
                                if (providerId == "local-device") "Recovery material (optional)"
                                else "Recovery material",
                            )
                        },
                        singleLine = true,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    archiveRestoreProvider = null
                    archiveRecoveryMaterial = ""
                }) { Text("Cancel") }
            },
            confirmButton = {
                Button(
                    enabled = providerId == "local-device" || archiveRecoveryMaterial.isNotBlank(),
                    onClick = {
                        viewModel.restorePrivateChatArchive(
                            providerId = providerId,
                            recoveryMaterial = archiveRecoveryMaterial.takeIf(String::isNotBlank),
                        )
                        archiveRestoreProvider = null
                        archiveRecoveryMaterial = ""
                    },
                ) { Text("Restore") }
            },
        )
    }

    if (chatSection == com.norfold.app.domain.ChatSection.Requests) {
        ContactRequestsHome(
            requests = requests,
            busyIds = requestBusyIds,
            preferences = requestPreferences,
            onOpen = viewModel::openContactRequest,
            onAccept = { viewModel.respondToContactRequest(it, accept = true) },
            onDecline = { viewModel.respondToContactRequest(it, accept = false) },
            onBlock = viewModel::blockContactRequest,
            onSavePreferences = { sender, pending, files, followups ->
                viewModel.saveMessageRequestPreferences(sender, pending, files, followups)
            },
        )
        return
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            ChatInboxHeader(
                state = state,
                onOpenStarred = { showStarred = true },
                onOpenContacts = { showContacts = true },
            )
            if (busy || query.isNotBlank()) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    singleLine = true,
                    shape = RoundedCornerShape(22.dp),
                    placeholder = { Text("Search conversations") },
                    leadingIcon = { Icon(Icons.Outlined.Search, null, Modifier.size(18.dp)) },
                    trailingIcon = {
                        if (query.isNotBlank()) {
                            IconButton(onClick = { query = "" }) {
                                Icon(Icons.Outlined.Close, "Clear search", Modifier.size(16.dp))
                            }
                        }
                    },
                )
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(ChatInboxFilter.entries) { entry ->
                        FilterChip(
                            selected = filter == entry,
                            onClick = { filterOverride = entry },
                            label = { Text(entry.label) },
                            shape = RoundedCornerShape(20.dp),
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.primary,
                                selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                            ),
                        )
                    }
                }
            }
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(bottom = 148.dp),
            ) {
                if (archiveRestorePending) {
                    item(key = "private-chat-archive-restore") {
                        Surface(
                            shape = RoundedCornerShape(22.dp),
                            color = MaterialTheme.colorScheme.primaryContainer,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                        ) {
                            Column(
                                Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    Icon(Icons.Outlined.Lock, null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            "Earlier private Chat history found",
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                                        )
                                        Text(
                                            "Choose a provider to merge it, or keep this device's current history. Nothing is overwritten silently.",
                                            fontSize = 12.sp,
                                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                                        )
                                    }
                                }
                                privateArchive.providerIds.sorted().forEach { providerId ->
                                    FilledTonalButton(
                                        onClick = { archiveRestoreProvider = providerId },
                                        modifier = Modifier.fillMaxWidth(),
                                    ) {
                                        Text(
                                            if (providerId == "local-device") "Restore from this device"
                                            else "Restore from Google Drive app data",
                                        )
                                    }
                                }
                                TextButton(onClick = viewModel::keepCurrentPrivateChatHistory) {
                                    Text("Keep current device history")
                                }
                            }
                        }
                    }
                }
                if (filtered.isEmpty()) {
                    item {
                        if (query.isBlank() && filter == ChatInboxFilter.All) {
                            NorfoldEmptyState(
                                icon = Icons.Outlined.ChatBubbleOutline,
                                title = "Talk it through",
                                detail = "Message the whole workspace in General, create topic channels, or DM a teammate. Conversations sync live across signed-in workspace devices.",
                            ) {
                                Button(onClick = { viewModel.selectChatChannel(null) }) { Text("Open General") }
                            }
                        } else {
                            NorfoldEmptyState(
                                icon = Icons.Outlined.Search,
                                title = "No conversations match",
                                detail = "Try a different name or filter.",
                            )
                        }
                    }
                } else {
                    if (pinned.isNotEmpty()) {
                        item { ChatInboxSectionLabel("Pinned") }
                        items(pinned, key = { "pin-${it.key}" }) { row ->
                            ConversationRow(
                                row = row,
                                onOpen = { viewModel.selectChatChannel(row.channelId) },
                                onTogglePin = { channel -> viewModel.setChatChannelPinned(channel.id, !channel.pinned) },
                                onDelete = { pendingDelete = it },
                            )
                        }
                    }
                    if (direct.isNotEmpty()) {
                        item { ChatInboxSectionLabel("Direct") }
                        items(direct, key = { "dm-${it.key}" }) { row ->
                            ConversationRow(
                                row = row,
                                onOpen = { viewModel.selectChatChannel(row.channelId) },
                                onTogglePin = { channel -> viewModel.setChatChannelPinned(channel.id, !channel.pinned) },
                                onDelete = { pendingDelete = it },
                            )
                        }
                    }
                    if (workspaceRows.isNotEmpty()) {
                        item { ChatInboxSectionLabel(workspaceLabel) }
                        items(workspaceRows, key = { "ws-${it.key}" }) { row ->
                            ConversationRow(
                                row = row,
                                onOpen = { viewModel.selectChatChannel(row.channelId) },
                                onTogglePin = { channel -> viewModel.setChatChannelPinned(channel.id, !channel.pinned) },
                                onDelete = { pendingDelete = it },
                            )
                        }
                    }
                    // People without a conversation yet, so a DM is one tap away — under the
                    // People filter, or at the tail of a lightly-used All inbox.
                    if (startableDms.isNotEmpty() && query.isBlank() &&
                        (filter == ChatInboxFilter.People || (filter == ChatInboxFilter.All && !busy))
                    ) {
                        item { ChatInboxSectionLabel("People") }
                        items(startableDms, key = { "person-${it.first}" }) { (username, display) ->
                            PersonRow(display, presence = viewModel.presenceFor(username)) {
                                viewModel.openDirectMessage(username, display)
                            }
                        }
                    }
                }
            }
        }
        // Compose FAB above the navbar on the right; hides while scrolling through the list.
        AnimatedVisibility(
            visible = fabVisible && !createMenuOpen,
            enter = fadeIn() + scaleIn(initialScale = 0.7f),
            exit = fadeOut() + scaleOut(targetScale = 0.7f),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .navigationBarsPadding()
                .padding(end = 18.dp, bottom = 104.dp)
                .zIndex(5f),
        ) {
            Surface(
                onClick = { createMenuOpen = true },
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primary,
                shadowElevation = 12.dp,
                modifier = Modifier.size(56.dp),
            ) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Icon(Icons.Outlined.Add, "New conversation", tint = MaterialTheme.colorScheme.onPrimary)
                }
            }
        }
        if (createMenuOpen) {
            NewConversationMenu(
                onDismiss = { createMenuOpen = false },
                onNewChannel = { createMenuOpen = false; showCreateChannel = true },
                onNewGroup = { createMenuOpen = false; showCreateGroup = true },
                onNewDm = { createMenuOpen = false; showNewDm = true },
            )
        }
    }
}

@Composable
private fun ContactRequestsHome(
    requests: List<NorfoldSocial.RemoteContactRequest>,
    busyIds: Set<String>,
    preferences: ChatBackend.MessageRequestPreferences,
    onOpen: (NorfoldSocial.RemoteContactRequest) -> Unit,
    onAccept: (NorfoldSocial.RemoteContactRequest) -> Unit,
    onDecline: (NorfoldSocial.RemoteContactRequest) -> Unit,
    onBlock: (NorfoldSocial.RemoteContactRequest) -> Unit,
    onSavePreferences: (String, String, String, Int) -> Unit,
) {
    var privacyOpen by remember { mutableStateOf(false) }
    if (privacyOpen) {
        MessageRequestPrivacyDialog(
            current = preferences,
            onSave = { sender, pending, files, followups ->
                onSavePreferences(sender, pending, files, followups)
                privacyOpen = false
            },
            onDismiss = { privacyOpen = false },
        )
    }
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Requests", fontWeight = FontWeight.Black, fontSize = 26.sp)
                Text(
                    "Messages from people you have not connected with yet",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp,
                )
            }
            FilledTonalButton(onClick = { privacyOpen = true }) {
                Icon(Icons.Outlined.Tune, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Privacy")
            }
        }
        if (requests.isEmpty()) {
            NorfoldEmptyState(
                icon = Icons.Outlined.PersonAddAlt,
                title = "No message requests",
                detail = "New requests stay separate from Direct until you accept or reply.",
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            ) {
                items(requests, key = NorfoldSocial.RemoteContactRequest::requestId) { request ->
                    val display = request.senderDisplayName.ifBlank {
                        request.senderHandle ?: "Norfold user"
                    }
                    val preview = request.preview?.get("body")?.jsonPrimitive?.contentOrNull
                        .orEmpty()
                    Surface(
                        onClick = { onOpen(request) },
                        shape = RoundedCornerShape(22.dp),
                        color = MaterialTheme.colorScheme.surface,
                        tonalElevation = 2.dp,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp),
                    ) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(11.dp),
                            ) {
                                Box(
                                    Modifier.size(42.dp).clip(CircleShape)
                                        .background(MaterialTheme.colorScheme.tertiaryContainer),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(
                                        display.take(1).uppercase(),
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                                    )
                                }
                                Column(Modifier.weight(1f)) {
                                    Text(display, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    request.senderHandle?.let {
                                        Text("@$it", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                                    }
                                }
                            }
                            Text(
                                preview.ifBlank { "Sent you a message request" },
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                val busy = request.requestId in busyIds
                                Button(
                                    onClick = { onAccept(request) },
                                    enabled = !busy,
                                    modifier = Modifier.weight(1f),
                                ) {
                                    if (busy) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(16.dp),
                                            strokeWidth = 2.dp,
                                            color = MaterialTheme.colorScheme.onPrimary,
                                        )
                                    } else {
                                        Text("Accept")
                                    }
                                }
                                FilledTonalButton(
                                    onClick = { onDecline(request) },
                                    enabled = !busy,
                                    modifier = Modifier.weight(1f),
                                ) { Text("Decline") }
                                TextButton(onClick = { onBlock(request) }, enabled = !busy) {
                                    Text("Block", color = MaterialTheme.colorScheme.error)
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
private fun MessageRequestPrivacyDialog(
    current: ChatBackend.MessageRequestPreferences,
    onSave: (String, String, String, Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var senderPolicy by remember(current) { mutableStateOf(current.senderPolicy) }
    var pendingBehavior by remember(current) { mutableStateOf(current.pendingBehavior) }
    var filePolicy by remember(current) { mutableStateOf(current.requestFilePolicy) }
    var followups by remember(current) { mutableStateOf(current.limitedFollowups.coerceIn(0, 5)) }
    NorfoldDialog(
        onDismissRequest = onDismiss,
        title = { Text("Message-request privacy") },
        text = {
            Column(
                Modifier.fillMaxWidth().heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                RequestPreferenceChoice(
                    title = "Who may request",
                    selected = senderPolicy,
                    choices = listOf(
                        "shared_workspace" to "Shared workspaces",
                        "anyone" to "Anyone",
                        "nobody" to "Nobody",
                    ),
                    onSelect = { senderPolicy = it },
                )
                RequestPreferenceChoice(
                    title = "While waiting for your decision",
                    selected = pendingBehavior,
                    choices = listOf(
                        "first_only" to "First message only",
                        "limited_followups" to "Limited follow-ups",
                        "until_decided" to "Allow until decided",
                    ),
                    onSelect = { pendingBehavior = it },
                )
                if (pendingBehavior == "limited_followups") {
                    Text("Follow-up messages: $followups", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        items((0..5).toList()) { count ->
                            FilterChip(
                                selected = followups == count,
                                onClick = { followups = count },
                                label = { Text(count.toString()) },
                            )
                        }
                    }
                }
                RequestPreferenceChoice(
                    title = "Files in pending requests",
                    selected = filePolicy,
                    choices = listOf(
                        "none" to "No files",
                        "images" to "Images only",
                        "files" to "All supported files",
                    ),
                    onSelect = { filePolicy = it },
                )
                Text(
                    "Blocking always overrides these choices. Requests stay separate until you accept or reply.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                )
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        confirmButton = {
            Button(onClick = { onSave(senderPolicy, pendingBehavior, filePolicy, followups) }) {
                Text("Save")
            }
        },
    )
}

@Composable
private fun RequestPreferenceChoice(
    title: String,
    selected: String,
    choices: List<Pair<String, String>>,
    onSelect: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Text(title, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            items(choices, key = { it.first }) { (value, label) ->
                FilterChip(
                    selected = selected == value,
                    onClick = { onSelect(value) },
                    label = { Text(label) },
                )
            }
        }
    }
}

/** Conversation count at which the inbox turns on search, filter chips, and the Pinned split. */
private const val BusyInboxThreshold = 6

internal enum class ChatInboxFilter(val label: String) {
    All("All"),
    Channels("Channels"),
    People("People"),
    Groups("Groups"),
    ;

    fun matches(row: ConversationRowData): Boolean = when (this) {
        All -> true
        Channels -> row.kind == null || row.kind == ChatChannelKind.Topic
        People -> row.kind == ChatChannelKind.Dm
        Groups -> row.kind == ChatChannelKind.Group
    }
}

/**
 * One row of the unified inbox. General is the synthetic row with [channelId] == null and
 * [kind] == null; every other row wraps a [ChatChannel].
 */
internal data class ConversationRowData(
    val channelId: Long?,
    val kind: ChatChannelKind?,
    val title: String,
    val pinned: Boolean,
    val lastMessage: ChatMessageItem?,
    val lastActivityAt: Long,
    val channel: ChatChannel?,
    val scope: com.norfold.app.domain.ChatScope = com.norfold.app.domain.ChatScope.Workspace,
    val unreadCount: Int = 0,
) {
    val key: String get() = channelId?.toString() ?: "general"
}

/** Builds the inbox: General first-run at top, then everything sorted by last activity. */
internal fun buildInbox(
    channels: List<ChatChannel>,
    messages: List<ChatMessageItem>,
): List<ConversationRowData> {
    val lastByChannel = HashMap<Long?, ChatMessageItem>()
    messages.forEach { msg ->
        val current = lastByChannel[msg.channelId]
        if (current == null || msg.createdAt > current.createdAt) lastByChannel[msg.channelId] = msg
    }
    val generalLast = lastByChannel[null]
    val general = ConversationRowData(
        channelId = null,
        kind = null,
        title = "General",
        pinned = false,
        lastMessage = generalLast,
        // General always exists; without messages it still leads the list.
        lastActivityAt = generalLast?.createdAt ?: Long.MAX_VALUE,
        channel = null,
    )
    val rest = channels.map { channel ->
        val last = lastByChannel[channel.id]
        ConversationRowData(
            channelId = channel.id,
            kind = channel.kind,
            title = channel.name,
            pinned = channel.pinned,
            lastMessage = last,
            lastActivityAt = last?.createdAt ?: channel.createdAt,
            channel = channel,
            scope = channel.scope,
        )
    }
    return (listOf(general) + rest).sortedByDescending { it.lastActivityAt }
}

/**
 * Total unread messages across every conversation in the workspace — feeds the navbar Chat
 * badge. Same rules as the per-row badges: system messages and own messages never count.
 */
internal fun totalChatUnread(
    channels: List<ChatChannel>,
    messages: List<ChatMessageItem>,
    currentUsername: String,
    lastReadAt: (Long?) -> Long,
): Int {
    val validIds = HashSet<Long?>(channels.size + 1)
    validIds.add(null) // General
    channels.forEach { validIds.add(it.id) }
    val watermarks = HashMap<Long?, Long>()
    return messages.count { msg ->
        msg.channelId in validIds &&
            !msg.system &&
            msg.authorUsername != currentUsername &&
            msg.createdAt > watermarks.getOrPut(msg.channelId) { lastReadAt(msg.channelId) }
    }
}

@Composable
private fun ChatInboxHeader(
    state: DocsUiState,
    onOpenStarred: () -> Unit,
    onOpenContacts: () -> Unit,
) {
    val workspaceName = state.workspaces
        .firstOrNull { it.id == state.settings.activeWorkspaceId }?.name
        ?: state.settings.workspaceName
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("Chat", fontWeight = FontWeight.Black, fontSize = 26.sp)
            Text(
                workspaceName,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = onOpenStarred) {
            Icon(Icons.Outlined.Star, "Starred messages")
        }
        IconButton(onClick = onOpenContacts) {
            Icon(Icons.Outlined.PersonAddAlt, "Contacts")
        }
    }
}

@Composable
private fun StarredMessagesSheet(
    messages: List<ChatMessageItem>,
    channels: List<ChatChannel>,
    onOpen: (ChatMessageItem) -> Unit,
    onDismiss: () -> Unit,
) {
    NorfoldContentDialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surface, tonalElevation = 3.dp) {
            Column(Modifier.fillMaxWidth().heightIn(max = 620.dp).padding(vertical = 12.dp)) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Starred", fontWeight = FontWeight.Black, fontSize = 22.sp)
                        Text(
                            "Private to this device",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 12.sp,
                        )
                    }
                    IconButton(onClick = onDismiss) { Icon(Icons.Outlined.Close, "Close") }
                }
                if (messages.isEmpty()) {
                    NorfoldEmptyState(
                        icon = Icons.Outlined.Star,
                        title = "Nothing starred yet",
                        detail = "Long-press a message and choose Save to Starred.",
                    )
                } else {
                    LazyColumn(contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)) {
                        items(messages, key = { "star-${it.id}" }) { message ->
                            val conversation = message.channelId?.let { id ->
                                channels.firstOrNull { it.id == id }?.name
                            } ?: "General"
                            Surface(
                                onClick = { onOpen(message) },
                                shape = RoundedCornerShape(18.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            ) {
                                Column(Modifier.padding(horizontal = 14.dp, vertical = 11.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            conversation,
                                            Modifier.weight(1f),
                                            color = MaterialTheme.colorScheme.primary,
                                            fontWeight = FontWeight.SemiBold,
                                            fontSize = 12.sp,
                                        )
                                        Icon(
                                            Icons.Outlined.Star,
                                            null,
                                            Modifier.size(15.dp),
                                            tint = MaterialTheme.colorScheme.primary,
                                        )
                                    }
                                    Text(
                                        message.body.ifBlank { message.attachmentName ?: "Attachment" },
                                        maxLines = 3,
                                        overflow = TextOverflow.Ellipsis,
                                        fontSize = 14.sp,
                                    )
                                    Text(
                                        message.authorDisplayName,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        fontSize = 11.sp,
                                    )
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
private fun ChatInboxSectionLabel(label: String) {
    Text(
        label,
        Modifier.padding(start = 18.dp, top = 10.dp, bottom = 4.dp),
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
internal fun ConversationRow(
    row: ConversationRowData,
    onOpen: () -> Unit,
    onTogglePin: (ChatChannel) -> Unit,
    onDelete: (ChatChannel) -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val swipeState = rememberSwipeToDismissBoxState(
        confirmValueChange = { direction ->
            val channel = row.channel ?: return@rememberSwipeToDismissBoxState false
            when (direction) {
                SwipeToDismissBoxValue.StartToEnd -> onTogglePin(channel)
                SwipeToDismissBoxValue.EndToStart -> onDelete(channel)
                SwipeToDismissBoxValue.Settled -> Unit
            }
            false
        },
    )
    SwipeToDismissBox(
        state = swipeState,
        enableDismissFromStartToEnd = row.channel != null,
        enableDismissFromEndToStart = row.channel != null,
        backgroundContent = {
            val deleting = swipeState.dismissDirection == SwipeToDismissBoxValue.EndToStart
            Box(
                Modifier
                    .fillMaxSize()
                    .background(if (deleting) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.primaryContainer)
                    .padding(horizontal = 22.dp),
                contentAlignment = if (deleting) Alignment.CenterEnd else Alignment.CenterStart,
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        if (deleting) Icons.Outlined.Delete else Icons.Outlined.PushPin,
                        null,
                        tint = if (deleting) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                    Text(
                        if (deleting) "Delete" else if (row.pinned) "Unpin" else "Pin",
                        color = if (deleting) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onPrimaryContainer,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        },
    ) {
      Row(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .combinedClickable(
                onClick = onOpen,
                // General has no pin/delete; its long-press is a no-op instead of a dead menu.
                onLongClick = { if (row.channel != null) menuOpen = true },
            )
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ConversationAvatar(row)
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    row.title,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (row.pinned) {
                    Icon(
                        Icons.Outlined.PushPin,
                        null,
                        Modifier.size(13.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Text(
                row.lastMessage?.let { previewLine(it) } ?: emptyPreview(row),
                color = if (row.unreadCount > 0) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 13.sp,
                fontWeight = if (row.unreadCount > 0) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            row.lastMessage?.let {
                Text(
                    inboxTime(it.createdAt),
                    color = if (row.unreadCount > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.sp,
                )
            }
            if (row.unreadCount > 0) {
                Box(
                    Modifier
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary)
                        .padding(horizontal = 7.dp, vertical = 2.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        if (row.unreadCount > 99) "99+" else row.unreadCount.toString(),
                        color = MaterialTheme.colorScheme.onPrimary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
      }
    }
    if (menuOpen && row.channel != null) {
        val channel = row.channel
        NorfoldDialog(
            onDismissRequest = { menuOpen = false },
            title = { Text(channel.name) },
            text = { Text(if (channel.pinned) "Unpin this conversation, or delete it and its messages." else "Pin this conversation to the top of the inbox, or delete it and its messages.") },
            dismissButton = {
                TextButton(onClick = {
                    menuOpen = false
                    onTogglePin(channel)
                }) { Text(if (channel.pinned) "Unpin" else "Pin") }
            },
            confirmButton = {
                TextButton(onClick = {
                    menuOpen = false
                    onDelete(channel)
                }) { Text("Delete") }
            },
        )
    }
}

@Composable
private fun ConversationAvatar(row: ConversationRowData) {
    val (icon, container, content) = when (row.kind) {
        null -> Triple(Icons.Outlined.Tag, MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.onPrimary)
        ChatChannelKind.Topic -> Triple(Icons.Outlined.Tag, MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer)
        ChatChannelKind.Group -> Triple(Icons.Outlined.Groups, MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.onSecondaryContainer)
        ChatChannelKind.Dm -> Triple(Icons.Outlined.Person, MaterialTheme.colorScheme.tertiaryContainer, MaterialTheme.colorScheme.onTertiaryContainer)
    }
    Box(
        Modifier.size(46.dp).clip(CircleShape).background(container),
        contentAlignment = Alignment.Center,
    ) {
        if (row.kind == ChatChannelKind.Dm) {
            Text(
                row.title.take(1).uppercase().ifBlank { "?" },
                color = content,
                fontWeight = FontWeight.Bold,
                fontSize = 17.sp,
            )
        } else if (row.kind == ChatChannelKind.Group &&
            row.channel?.icon?.isNotBlank() == true &&
            row.channel.icon.startsWith("app.").not()
        ) {
            Text(row.channel.icon, fontSize = 21.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        } else {
            Icon(icon, null, Modifier.size(20.dp), tint = content)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PersonRow(display: String, presence: String? = null, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = null)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(contentAlignment = Alignment.BottomEnd) {
            Box(
                Modifier.size(38.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    display.take(1).uppercase().ifBlank { "?" },
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // Presence dot: green online, amber away, absent when offline/unknown.
            if (presence == "online" || presence == "away") {
                Box(
                    Modifier
                        .size(11.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surface)
                        .padding(1.5.dp)
                        .clip(CircleShape)
                        .background(if (presence == "online") Color(0xFF34C759) else Color(0xFFFFB020)),
                )
            }
        }
        Column(Modifier.weight(1f)) {
            Text(display, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                when (presence) {
                    "online" -> "Online"
                    "away" -> "Away"
                    else -> "Start a conversation"
                },
                color = if (presence == "online") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
            )
        }
        Icon(
            Icons.Outlined.ChatBubbleOutline,
            null,
            Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun NewConversationMenu(
    onDismiss: () -> Unit,
    onNewChannel: () -> Unit,
    onNewGroup: () -> Unit,
    onNewDm: () -> Unit,
) {
    NorfoldDialog(
        onDismissRequest = onDismiss,
        title = { Text("Start something") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                NewConversationOption(Icons.Outlined.Tag, "New channel", "A shared space for one topic", onNewChannel)
                NewConversationOption(Icons.Outlined.Groups, "New group", "A private conversation for a few people", onNewGroup)
                NewConversationOption(Icons.Outlined.Person, "New direct message", "One-on-one with a teammate", onNewDm)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NewConversationOption(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    detail: String,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(icon, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
            Column {
                Text(title, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                Text(detail, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NewDirectMessageDialog(
    people: List<Pair<String, String>>,
    onDismiss: () -> Unit,
    onPick: (username: String, display: String) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val filtered = people.filter {
        query.isBlank() || it.second.contains(query.trim(), true) || it.first.contains(query.trim(), true)
    }
    NorfoldDialog(
        onDismissRequest = onDismiss,
        title = { Text("New direct message") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = { Text("Search people") },
                    leadingIcon = { Icon(Icons.Outlined.Search, null, Modifier.size(18.dp)) },
                )
                if (filtered.isEmpty()) {
                    Text(
                        if (people.isEmpty()) "Everyone in this workspace already has a conversation with you, or there's no one else here yet. Invite people from workspace settings." else "No one matches.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                }
                // A dialog, not a lazy list: the roster at this stage is small by definition.
                filtered.take(12).forEach { (username, display) ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .combinedClickable(onClick = { onPick(username, display) }, onLongClick = null)
                            .padding(horizontal = 8.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Box(
                            Modifier.size(34.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(display.take(1).uppercase().ifBlank { "?" }, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        }
                        Column {
                            Text(display, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                            Text("@$username", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * New personal group: a name plus a multi-select of contacts. Members are account-level
 * contacts (create_personal_group enforces membership server-side), so the picker draws
 * from the social contact list rather than the workspace roster.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun NewGroupDialog(
    contacts: List<com.norfold.app.cloud.NorfoldSocial.RemoteContact>,
    maxSelectedMembers: Int = 7,
    onDismiss: () -> Unit,
    onCreate: (name: String, memberIds: List<String>) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var query by remember { mutableStateOf("") }
    val selected = remember { mutableStateListOf<String>() }
    val filtered = contacts.filter {
        query.isBlank() ||
            it.displayName.contains(query.trim(), true) ||
            (it.handle?.contains(query.trim(), true) == true)
    }
    NorfoldDialog(
        onDismissRequest = onDismiss,
        title = { Text("New group") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = { Text("Group name") },
                )
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = { Text("Search contacts") },
                    leadingIcon = { Icon(Icons.Outlined.Search, null, Modifier.size(18.dp)) },
                )
                Text(
                    "${selected.size}/$maxSelectedMembers people selected · current Chat beta limit",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.sp,
                )
                if (contacts.isEmpty()) {
                    Text(
                        "You have no contacts yet. Add people from the People tab, then create a group.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                } else if (filtered.isEmpty()) {
                    Text(
                        "No contacts match.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                }
                filtered.take(24).forEach { contact ->
                    val isSelected = contact.contactId in selected
                    val display = contact.displayName.ifBlank { contact.handle ?: "Contact" }
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (isSelected) MaterialTheme.colorScheme.primaryContainer else androidx.compose.ui.graphics.Color.Transparent)
                            .combinedClickable(
                                onClick = {
                                    if (isSelected) {
                                        selected.remove(contact.contactId)
                                    } else if (selected.size < maxSelectedMembers) {
                                        selected.add(contact.contactId)
                                    }
                                },
                                onLongClick = null,
                            )
                            .padding(horizontal = 8.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Box(
                            Modifier.size(34.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(display.take(1).uppercase().ifBlank { "?" }, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        }
                        Column(Modifier.weight(1f)) {
                            Text(display, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                            contact.handle?.let { Text("@$it", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp) }
                        }
                        if (isSelected) Icon(Icons.Outlined.Check, "Selected", Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onCreate(name, selected.toList()) },
                enabled = name.isNotBlank() && selected.isNotEmpty(),
            ) { Text("Create") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun ChatInboxCreateDialog(
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

private fun previewLine(message: ChatMessageItem): String {
    val body = when {
        message.system -> message.body
        message.attachmentName != null && message.body.isBlank() -> "📎 ${message.attachmentName}"
        else -> message.body
    }.replace('\n', ' ').trim()
    return if (message.system) body else "${message.authorDisplayName.ifBlank { message.authorUsername }}: $body"
}

private fun emptyPreview(row: ConversationRowData): String = when (row.kind) {
    null -> "The whole workspace is here"
    ChatChannelKind.Topic -> "No messages yet"
    ChatChannelKind.Group -> "No messages yet"
    ChatChannelKind.Dm -> "Say hi"
}

/** Compact inbox timestamp: time today, weekday within a week, else a short date. */
private fun inboxTime(timestamp: Long): String {
    val now = System.currentTimeMillis()
    val cal = java.util.Calendar.getInstance()
    cal.timeInMillis = now
    cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
    cal.set(java.util.Calendar.MINUTE, 0)
    cal.set(java.util.Calendar.SECOND, 0)
    cal.set(java.util.Calendar.MILLISECOND, 0)
    val startOfToday = cal.timeInMillis
    return when {
        timestamp >= startOfToday ->
            java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date(timestamp))
        timestamp >= startOfToday - 6L * 24 * 60 * 60 * 1000 ->
            java.text.SimpleDateFormat("EEE", java.util.Locale.getDefault()).format(java.util.Date(timestamp))
        else ->
            java.text.SimpleDateFormat("d MMM", java.util.Locale.getDefault()).format(java.util.Date(timestamp))
    }
}

/**
 * Account-level contacts (chat overhaul Phase 2): your invite-only social graph,
 * independent of any workspace. Adding someone is one-directional; you become
 * "Friends" (mutual) when they add you back, redeem your invite, or reply to
 * your DM. Requires the social-graph backend migration to be deployed.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ContactsSheet(state: DocsUiState, viewModel: DocsViewModel, onDismiss: () -> Unit) {
    val contacts by viewModel.socialContacts.collectAsState()
    val inviteToken by viewModel.pendingFriendInvite.collectAsState()
    val clipboard = LocalClipboardManager.current
    var addHandle by remember { mutableStateOf("") }
    var redeemToken by remember { mutableStateOf("") }
    var contactMenu by remember { mutableStateOf<NorfoldSocial.RemoteContact?>(null) }
    var isCreatingInvite by remember { mutableStateOf(false) }
    var isRedeemingInvite by remember { mutableStateOf(false) }
    LaunchedEffect(inviteToken) { if (inviteToken != null) isCreatingInvite = false }
    LaunchedEffect(isCreatingInvite) {
        if (isCreatingInvite) { kotlinx.coroutines.delay(8000); isCreatingInvite = false }
    }
    LaunchedEffect(isRedeemingInvite) {
        if (isRedeemingInvite) { kotlinx.coroutines.delay(8000); isRedeemingInvite = false }
    }
    LaunchedEffect(Unit) { viewModel.refreshSocialGraph() }

    contactMenu?.let { contact ->
        val name = contact.displayName.ifBlank { contact.handle ?: "This contact" }
        NorfoldDialog(
            onDismissRequest = { contactMenu = null },
            title = { Text(name) },
            text = { Text("Remove $name from your contacts, or block them so they can't reach you at all.") },
            dismissButton = {
                TextButton(onClick = {
                    viewModel.blockContact(contact.contactId)
                    contactMenu = null
                }) { Text("Block") }
            },
            confirmButton = {
                Button(onClick = {
                    viewModel.removeContact(contact.contactId)
                    contactMenu = null
                }) { Text("Remove") }
            },
        )
    }

    NorfoldContentDialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 3.dp,
        ) {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("Contacts", fontWeight = FontWeight.Black, fontSize = 22.sp)
                val ownHandle = state.settings.syncUserName
                if (ownHandle.isNotBlank()) {
                    Text(
                        "You're @$ownHandle",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 13.sp,
                    )
                }

                // Add by unique @handle — no request inbox; they see you once mutual.
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = addHandle,
                        onValueChange = { addHandle = it },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        shape = RoundedCornerShape(18.dp),
                        placeholder = { Text("Add by @handle") },
                    )
                    Button(
                        onClick = {
                            viewModel.addContactByHandle(addHandle)
                            addHandle = ""
                        },
                        enabled = addHandle.trim().removePrefix("@").isNotBlank(),
                    ) { Text("Add") }
                }

                // Invite code: single-use, 7 days. Both sides become friends on redeem.
                if (inviteToken == null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        FilledTonalButton(
                            onClick = { isCreatingInvite = true; viewModel.createFriendInvite() },
                            enabled = !isCreatingInvite,
                            modifier = Modifier.weight(1f),
                        ) {
                            if (isCreatingInvite) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                )
                                Spacer(Modifier.width(8.dp))
                                Text("Creating…")
                            } else {
                                Text("Create invite code")
                            }
                        }
                    }
                } else {
                    Surface(
                        shape = RoundedCornerShape(18.dp),
                        color = MaterialTheme.colorScheme.primaryContainer,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("Invite code — share it with one person", fontSize = 12.sp, color = MaterialTheme.colorScheme.onPrimaryContainer)
                            Text(
                                inviteToken.orEmpty(),
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 14.sp,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextButton(onClick = {
                                    clipboard.setText(AnnotatedString(inviteToken.orEmpty()))
                                    viewModel.showMessage("Invite code copied.")
                                }) { Text("Copy") }
                                TextButton(onClick = { viewModel.dismissFriendInvite() }) { Text("Done") }
                            }
                        }
                    }
                }

                // Redeem a code someone shared with you.
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = redeemToken,
                        onValueChange = { redeemToken = it },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        shape = RoundedCornerShape(18.dp),
                        placeholder = { Text("Paste an invite code") },
                    )
                    Button(
                        onClick = {
                            isRedeemingInvite = true
                            viewModel.redeemFriendInvite(redeemToken.trim())
                            redeemToken = ""
                        },
                        enabled = redeemToken.isNotBlank() && !isRedeemingInvite,
                    ) {
                        if (isRedeemingInvite) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary,
                            )
                        } else {
                            Text("Join")
                        }
                    }
                }

                if (contacts.isEmpty()) {
                    Text(
                        "No contacts yet. Add someone by their @handle or share an invite code.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().heightIn(max = 280.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        items(contacts, key = { it.contactId }) { contact ->
                            ContactRow(contact, onLongPress = { contactMenu = contact })
                        }
                    }
                }
                TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) { Text("Close") }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ContactRow(contact: NorfoldSocial.RemoteContact, onLongPress: () -> Unit) {
    val name = contact.displayName.ifBlank { contact.handle ?: "Unknown" }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .combinedClickable(onClick = {}, onLongClick = onLongPress)
            .padding(horizontal = 6.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            Modifier.size(38.dp).clip(CircleShape).background(MaterialTheme.colorScheme.tertiaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                name.take(1).uppercase(),
                color = MaterialTheme.colorScheme.onTertiaryContainer,
                fontWeight = FontWeight.Bold,
            )
        }
        Column(Modifier.weight(1f)) {
            Text(name, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            contact.handle?.let {
                Text("@$it", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = if (contact.mutual) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceVariant,
        ) {
            Text(
                if (contact.mutual) "Friends" else "Added",
                fontSize = 11.sp,
                color = if (contact.mutual) MaterialTheme.colorScheme.onPrimaryContainer
                else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            )
        }
    }
}
