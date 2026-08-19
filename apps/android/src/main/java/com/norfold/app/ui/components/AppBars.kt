package com.norfold.app.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Group
import androidx.compose.material.icons.outlined.MarkEmailUnread
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.norfold.app.domain.Destination
import com.norfold.app.domain.ChatSection
import com.norfold.app.domain.ControlLabelMode
import com.norfold.app.domain.SemanticActionId
import com.norfold.app.ui.DocsUiState
import com.norfold.app.ui.DocsViewModel
import com.norfold.app.ui.LocalControlLabelMode
import com.norfold.app.ui.screens.totalChatUnread
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import com.norfold.app.ui.rememberChromeFocus

@Composable
fun MobileBottomBar(state: DocsUiState, viewModel: DocsViewModel, modifier: Modifier = Modifier) {
    val showCreateLabel = LocalControlLabelMode.current != ControlLabelMode.IconsOnly
    // Unread total for the Chat tab badge; readSerial bumps when a conversation is read.
    val readSerial by viewModel.chatReadSerial.collectAsState()
    val currentUsername = state.settings.syncUserName.ifBlank { "you" }
    val chatUnread = remember(state.chatChannels, state.chatMessages, currentUsername, readSerial) {
        totalChatUnread(state.chatChannels, state.chatMessages, currentUsername, viewModel::chatLastReadAt)
    }
    // Tier 2 of the adaptive navbar: while on the Chat inbox (tab selected, no conversation open),
    // the app tabs give way to chat-context tabs (Chats/People/Requests/You). Opening a
    // conversation hides the whole bar (handled upstream), leaving the composer as Tier 3.
    val chatContext = state.destination == Destination.Chat && !state.chatConversationOpen
    val chatSection by viewModel.chatSection.collectAsState()
    Box(
        modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .height(88.dp)
            .padding(horizontal = 14.dp, vertical = 6.dp),
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth().height(68.dp).align(Alignment.BottomCenter),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
            shape = RoundedCornerShape(28.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)),
            shadowElevation = 6.dp,
            tonalElevation = 1.dp,
        ) {
            androidx.compose.animation.AnimatedContent(
                targetState = chatContext,
                transitionSpec = {
                    (fadeIn(tween(180)) + scaleIn(initialScale = 0.96f, animationSpec = tween(180)))
                        .togetherWith(fadeOut(tween(140)))
                },
                label = "navbar-tier",
            ) { inChat ->
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 7.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (inChat) {
                        // Tier 2 — chat-context tabs. Center gap keeps the shared FAB clear.
                        NavBarItem("Chats", Icons.Outlined.ChatBubbleOutline, chatSection == ChatSection.Chats, Modifier.weight(1f), badgeCount = chatUnread) { viewModel.selectChatSection(ChatSection.Chats) }
                        NavBarItem("People", Icons.Outlined.Group, chatSection == ChatSection.People, Modifier.weight(1f)) { viewModel.selectChatSection(ChatSection.People) }
                        Spacer(Modifier.weight(1f))
                        NavBarItem("Requests", Icons.Outlined.MarkEmailUnread, chatSection == ChatSection.Requests, Modifier.weight(1f)) { viewModel.selectChatSection(ChatSection.Requests) }
                        NavBarItem("You", Icons.Outlined.Person, chatSection == ChatSection.You, Modifier.weight(1f)) { viewModel.selectChatSection(ChatSection.You) }
                    } else {
                        NavBarItem("Home", SemanticActionId.AppHome, state.destination == Destination.WorkspaceHub, Modifier.weight(1f)) { viewModel.go(Destination.WorkspaceHub) }
                        NavBarItem("Notes", SemanticActionId.AppNotes, state.destination in setOf(Destination.NotesHome, Destination.NoteEditor, Destination.Notebooks, Destination.Tags), Modifier.weight(1f)) { viewModel.openNotesLibrary() }
                        Spacer(Modifier.weight(1f))
                        NavBarItem("Tasks", SemanticActionId.AppTasks, state.destination in setOf(Destination.Tasks, Destination.Calendar), Modifier.weight(1f)) { viewModel.go(Destination.Tasks) }
                        NavBarItem("Chat", SemanticActionId.AppChat, state.destination == Destination.Chat, Modifier.weight(1f), badgeCount = chatUnread) { viewModel.openChatInbox() }
                    }
                }
            }
        }
        Column(
            modifier = Modifier.align(Alignment.TopCenter).offset(y = (-4).dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val fabFocus = rememberChromeFocus()
            Surface(
                // The ring is measured on `primary`, the fill it is drawn on — not on the page. And the
                // 5 dp background border has to be cleared, or the ring lands inside the halo that
                // separates the FAB from the bar instead of inside the FAB.
                modifier = Modifier.size(62.dp).then(fabFocus.ringOnCircle(62f, MaterialTheme.colorScheme.primary, boundaryWidthDp = 5f)),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primary,
                border = BorderStroke(5.dp, MaterialTheme.colorScheme.background),
                shadowElevation = 16.dp,
                interactionSource = fabFocus.source,
                onClick = {
                    when {
                        // In the chat context the FAB is a "new conversation" affordance; land on
                        // the inbox where the create-menu lives (dedicated new-DM sheet is Phase 4).
                        chatContext -> viewModel.openChatInbox()
                        state.destination in setOf(Destination.Tasks, Destination.Calendar) -> viewModel.createTaskAndOpen()
                        state.destination in setOf(Destination.DocumentsHome, Destination.DocumentEditor) -> viewModel.createDocument()
                        else -> viewModel.createNote()
                    }
                },
            ) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Icon(Icons.Outlined.Add, "Create", tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(30.dp))
                }
            }
            if (showCreateLabel) Text("Create", fontSize = 10.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
