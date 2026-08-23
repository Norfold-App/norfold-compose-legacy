package com.norfold.app.ui.screens

import android.content.ClipData
import android.content.ClipDescription
import android.os.PersistableBundle
import android.app.Activity
import android.Manifest
import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.app.PictureInPictureParams
import android.text.InputType
import android.util.TypedValue
import android.util.Rational
import android.widget.EditText
import android.webkit.WebChromeClient
import android.webkit.WebView
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.widget.doAfterTextChanged
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.automirrored.outlined.Sort
import androidx.compose.material.icons.automirrored.outlined.Note
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Reply
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.Done
import androidx.compose.material.icons.outlined.DoneAll
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Difference
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DragIndicator
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Business
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.GifBox
import androidx.compose.material.icons.outlined.Tag
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.ShapeLine
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.outlined.VideoFile
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Checkbox
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.zIndex
import com.norfold.app.ui.components.ChoiceRow
import com.norfold.app.ui.components.EmbedMetadataResolver
import com.norfold.app.ui.components.NorfoldContentDialog
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.norfold.app.data.displayName
import com.norfold.app.data.ChatConversationPrefs
import com.norfold.app.domain.ChatChannel
import com.norfold.app.domain.ChatChannelKind
import com.norfold.app.domain.ChatReceiptStatus
import com.norfold.app.domain.ChatSecurityMode
import com.norfold.app.domain.ChatScope
import com.norfold.app.domain.ChatChannelVisibility
import com.norfold.app.domain.ChatMessageItem
import com.norfold.app.domain.ConflictSideSummary
import com.norfold.app.domain.Destination
import com.norfold.app.domain.style.ChromeGeometry
import com.norfold.app.domain.style.ChromeGeometry.Corner
import com.norfold.app.ui.ChatAudioState
import com.norfold.app.ui.focusableChrome
import com.norfold.app.ui.focusablePill
import com.norfold.app.ui.NorfoldChrome
import com.norfold.app.ui.rememberChromeFocus
import com.norfold.app.ui.ChatUploadItem
import com.norfold.app.ui.ChatUploadStatus
import com.norfold.app.ui.ChatAttachmentPreviewState
import com.norfold.app.ui.VoiceRecordingState
import com.norfold.app.ui.DocsUiState
import com.norfold.app.ui.SemanticColors
import com.norfold.app.ui.DocsViewModel
import com.norfold.app.ui.EditorChromeColors
import com.norfold.app.ui.SecureRelayConnection
import com.norfold.app.ui.PrivateChatArchiveUiState
import com.norfold.app.ui.toggleableChrome
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import org.json.JSONObject
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.AlternateEmail
import androidx.compose.material.icons.outlined.PermMedia
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.foundation.combinedClickable
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.withStyle
import com.norfold.app.domain.WorkspaceMember
import com.norfold.app.domain.Note
import com.norfold.app.cloud.NorfoldPresence
import com.norfold.app.cloud.ChatBackend
import com.norfold.app.editor.sticker.Sticker
import com.norfold.app.editor.sticker.StickerCatalog
import com.norfold.app.editor.sticker.StickerCategory
import com.norfold.app.ui.ChatComposerAction
import com.norfold.app.ui.components.NorfoldFullscreenDialog
import com.norfold.app.ui.components.NorfoldBottomSheet
import coil.compose.AsyncImage
import coil.request.ImageRequest
import coil.request.videoFrameMillis

@Composable
fun ChatScreen(state: DocsUiState, viewModel: DocsViewModel, onPickAttachment: (ChatComposerAction) -> Unit = {}) {
    // TextFieldValue instead of String: mention detection needs the cursor position.
    var message by remember { mutableStateOf(TextFieldValue("")) }
    var toolsExpanded by remember { mutableStateOf(false) }
    var detailsOpen by remember { mutableStateOf(false) }
    var securityDetailsOpen by remember { mutableStateOf(false) }
    var archiveDetailsOpen by remember { mutableStateOf(false) }
    var rulesReviewChannelId by remember { mutableStateOf<Long?>(null) }
    // Profile viewer target: (username, displayName). Opened from the details roster or a DM header.
    var profileTarget by remember { mutableStateOf<Pair<String, String>?>(null) }
    // Phase 6: share-a-note picker (UI-only). Picking a note sends an inline note reference.
    var notePickerOpen by remember { mutableStateOf(false) }
    var gifPickerOpen by remember { mutableStateOf(false) }
    var stickerPickerOpen by remember { mutableStateOf(false) }
    var messageActions by remember { mutableStateOf<ChatMessageItem?>(null) }
    var reportTarget by remember { mutableStateOf<ChatMessageItem?>(null) }
    var whoReacted by remember { mutableStateOf<ChatMessageItem?>(null) }
    var forwardTarget by remember { mutableStateOf<ChatMessageItem?>(null) }
    // Multi-select mode: a non-null set enables selection UI; forwarding a batch reuses forwardTarget.
    var selectedIds by remember { mutableStateOf<Set<Long>?>(null) }
    var forwardBatch by remember { mutableStateOf<List<ChatMessageItem>?>(null) }
    var replyTarget by remember { mutableStateOf<ChatMessageItem?>(null) }
    var editingTarget by remember { mutableStateOf<ChatMessageItem?>(null) }
    var chatSearchOpen by remember { mutableStateOf(false) }
    var chatQuery by remember { mutableStateOf("") }
    var onlineVideoId by remember { mutableStateOf<String?>(null) }
    val imeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    val currentUsername = state.settings.syncUserName.ifBlank { "you" }
    val teamWorkspaceId by viewModel.teamWorkspaceRemoteId.collectAsState()
    val collaborationStatus by viewModel.teamCollaborationStatus.collectAsState()
    val workspaceSecurity by viewModel.workspaceChatSecurity.collectAsState()
    val serviceCapabilities by viewModel.chatServiceCapabilities.collectAsState()
    val privateArchive by viewModel.privateChatArchiveState.collectAsState()
    val secureRelayConnection by viewModel.secureRelayConnection.collectAsState()
    val recordingState by viewModel.voiceRecordingState.collectAsState()
    val chatUploads by viewModel.chatUploads.collectAsState()
    val chatAudio by viewModel.chatAudio.collectAsState()
    val attachmentPreview by viewModel.chatAttachmentPreview.collectAsState()
    val gifPicker by viewModel.gifPicker.collectAsState()
    val highlightedMessageId by viewModel.highlightedChatMessageId.collectAsState()
    val workspacePresence by viewModel.workspacePresence.collectAsState()
    val accountPresence by viewModel.accountConversationPresence.collectAsState()
    val socialContacts by viewModel.socialContacts.collectAsState()
    val blockedAccountIds by viewModel.blockedAccountIds.collectAsState()
    val activeChannel = state.chatChannels.firstOrNull { it.id == state.activeChatChannelId }
    val presence = if (activeChannel?.accountConversationId != null) accountPresence else workspacePresence
    val sharedConversation = activeChannel?.accountConversationId == null
    val visibleWorkspaceSecurity = workspaceSecurity.takeIf { sharedConversation }
    val canManageWorkspaceSecurity = state.workspaceMembers.firstOrNull {
        it.username.equals(currentUsername, ignoreCase = true)
    }?.role?.lowercase()?.let { it in setOf("owner", "admin") } == true
    LaunchedEffect(activeChannel?.id, activeChannel?.rules, activeChannel?.rulesReminderDays) {
        rulesReviewChannelId = activeChannel
            ?.takeIf(viewModel::shouldReviewChatGroupRules)
            ?.id
    }
    LaunchedEffect(activeChannel?.id, teamWorkspaceId) {
        if (sharedConversation && teamWorkspaceId != null) {
            viewModel.refreshWorkspaceChatSecurity(activeChannel?.id)
        }
    }
    LaunchedEffect(message.text, activeChannel?.id) {
        val typing = message.text.isNotBlank()
        viewModel.setChatTyping(typing)
        if (typing) {
            delay(2_500L)
            viewModel.setChatTyping(false)
        }
    }
    DisposableEffect(Unit) {
        onDispose { viewModel.setChatTyping(false) }
    }
    // Restore any saved draft when this conversation opens (and after process death). Switching
    // conversations reloads the target's draft; each keystroke below persists it, so nothing is
    // lost on a channel switch or app kill.
    LaunchedEffect(activeChannel?.id) {
        val saved = viewModel.chatDraft(activeChannel?.id)
        message = TextFieldValue(saved, TextRange(saved.length))
    }
    attachmentPreview?.let { preview ->
        ChatAttachmentViewer(
            preview = preview,
            onDismiss = viewModel::dismissChatAttachmentPreview,
            onSave = viewModel::saveChatAttachmentToDevice,
            onRequestPdfPage = viewModel::loadChatPdfPage,
        )
    }
    onlineVideoId?.let { videoId ->
        ChatOnlineVideoViewer(videoId = videoId, onDismiss = { onlineVideoId = null })
    }
    if (gifPickerOpen) {
        LaunchedEffect(Unit) { viewModel.searchChatGifs("") }
        ChatGifPicker(
            state = gifPicker,
            onQueryChange = viewModel::searchChatGifs,
            onPick = { gif -> viewModel.sendChatGif(gif); gifPickerOpen = false },
            onDismiss = { gifPickerOpen = false },
        )
    }
    if (stickerPickerOpen) {
        ChatStickerPicker(
            onDismiss = { stickerPickerOpen = false },
            onPick = { sticker ->
                viewModel.sendChat(stickerReferenceMarker(sticker.id))
                stickerPickerOpen = false
            },
        )
    }
    visibleWorkspaceSecurity?.takeIf { !it.acknowledged }?.let { security ->
        WorkspaceSecurityDisclosure(
            security = security,
            onAcknowledge = viewModel::acknowledgeWorkspaceChatSecurity,
            onNotNow = viewModel::handleBack,
        )
    }
    if (securityDetailsOpen && visibleWorkspaceSecurity != null) {
        WorkspaceSecurityDetails(
            security = visibleWorkspaceSecurity,
            canManage = canManageWorkspaceSecurity,
            onChangeMode = {
                viewModel.changeWorkspaceChatSecurityMode(it)
                securityDetailsOpen = false
            },
            onDismiss = { securityDetailsOpen = false },
        )
    }
    if (archiveDetailsOpen && activeChannel?.scope == ChatScope.Account) {
        PrivateChatArchiveDetails(
            state = privateArchive,
            onUpdate = viewModel::writePrivateChatArchiveNow,
            onRevealRecovery = viewModel::revealPrivateChatRecoveryMaterial,
            onHideRecovery = viewModel::hidePrivateChatRecoveryMaterial,
            onDismiss = {
                viewModel.hidePrivateChatRecoveryMaterial()
                archiveDetailsOpen = false
            },
        )
    }
    Column(
        Modifier
            .fillMaxSize()
            .imePadding(),
    ) {
        val channelTitle = activeChannel?.name ?: "General"
        // The composer placeholder should name where the message lands.
        val composerTarget = activeChannel?.name ?: "workspace"
        ChatPersonaBar(
            title = channelTitle,
            onGeneral = { viewModel.selectChatChannel(null) },
            showGeneralAction = state.activeChatChannelId != null,
            status = when (activeChannel?.kind) {
                null -> {
                    // Presence beats the generic sync status: "3 online" is what a chat
                    // header should say when the roster is live.
                    val online = presence.count { it.availability == "online" }
                    if (online > 1) "$online online · $collaborationStatus" else collaborationStatus
                }
                ChatChannelKind.Dm -> when (
                    presence.firstOrNull {
                        it.username.equals(activeChannel.counterpartUsername, ignoreCase = true)
                    }?.availability
                ) {
                    "online" -> "Online"
                    "away" -> "Away"
                    else -> "Direct message · $collaborationStatus"
                }
                ChatChannelKind.Group -> "Group · $collaborationStatus"
                ChatChannelKind.Topic -> "Channel · $collaborationStatus"
            },
            connected = teamWorkspaceId != null,
            // Back returns to the chat inbox (handleBack's Chat branch), not out of the tab.
            onBack = viewModel::handleBack,
            onStatusClick = viewModel::refreshTeamChat,
            onTitleClick = { detailsOpen = true },
            onSearch = { chatSearchOpen = !chatSearchOpen; if (!chatSearchOpen) chatQuery = "" },
        )
        if (serviceCapabilities.isConserving || serviceCapabilities.isDegraded) {
            val bannerColor = if (serviceCapabilities.isDegraded) {
                MaterialTheme.colorScheme.errorContainer
            } else {
                MaterialTheme.colorScheme.tertiaryContainer
            }
            Surface(
                color = bannerColor,
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp)
                    .focusableChrome(
                        radiusDp = 18f,
                        surface = bannerColor,
                        onClick = { viewModel.refreshChatServiceCapabilities() },
                    ),
            ) {
                Row(
                    Modifier.padding(horizontal = 13.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(9.dp),
                ) {
                    Icon(Icons.Outlined.Info, contentDescription = null)
                    Column(Modifier.weight(1f)) {
                        Text(
                            if (serviceCapabilities.isDegraded) "Reduced Chat service" else "Chat is conserving capacity",
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                        )
                        Text(
                            serviceCapabilities.explanation,
                            fontSize = 11.sp,
                            color = if (serviceCapabilities.isDegraded) {
                                MaterialTheme.colorScheme.onErrorContainer
                            } else {
                                MaterialTheme.colorScheme.onTertiaryContainer
                            },
                        )
                        Text("Tap to check again", fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
        visibleWorkspaceSecurity?.let { security ->
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp)
                    .focusableChrome(
                        radiusDp = 18f,
                        surface = MaterialTheme.colorScheme.surfaceContainerHigh,
                        onClick = { securityDetailsOpen = true },
                    ),
            ) {
                Row(
                    Modifier.padding(horizontal = 13.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(9.dp),
                ) {
                    Icon(
                        if (security.securityMode == ChatSecurityMode.EndToEndEncrypted) {
                            Icons.Outlined.Lock
                        } else {
                            Icons.Outlined.Business
                        },
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Column(Modifier.weight(1f)) {
                        Text(
                            if (security.securityMode == ChatSecurityMode.EndToEndEncrypted) {
                                "End-to-end encrypted"
                            } else {
                                "Managed workspace"
                            },
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                        )
                        Text(
                            "Security epoch ${security.epochNumber}${if (security.acknowledged) " · reviewed" else " · review required"}",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 11.sp,
                        )
                    }
                }
            }
        }
        if (activeChannel?.scope == ChatScope.Account) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp)
                    .focusableChrome(
                        radiusDp = 18f,
                        surface = MaterialTheme.colorScheme.surfaceContainerHigh,
                        onClick = { archiveDetailsOpen = true },
                    ),
            ) {
                Row(
                    Modifier.padding(horizontal = 13.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(9.dp),
                ) {
                    Icon(
                        Icons.Outlined.Lock,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Column(Modifier.weight(1f)) {
                        Text("End-to-end encrypted", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        Text(
                            privateArchive.status,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 11.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
        // Encrypted-delivery health (Phase 13/15): surface only when the single-owner
        // relay subscription is not healthy, so account conversations show when
        // catch-up is reconnecting or offline rather than looking silently stalled.
        if (activeChannel?.scope == ChatScope.Account) {
            val relayNotice: Pair<String, Boolean>? = when (secureRelayConnection) {
                SecureRelayConnection.Reconnecting -> "Reconnecting to encrypted delivery…" to false
                SecureRelayConnection.Connecting -> "Connecting to encrypted delivery…" to false
                SecureRelayConnection.Offline -> "Offline · messages send when you reconnect" to true
                SecureRelayConnection.Live, SecureRelayConnection.Idle -> null
            }
            relayNotice?.let { (label, isError) ->
                Surface(
                    color = if (isError) {
                        MaterialTheme.colorScheme.errorContainer
                    } else {
                        MaterialTheme.colorScheme.tertiaryContainer
                    },
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                ) {
                    Row(
                        Modifier.padding(horizontal = 13.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(9.dp),
                    ) {
                        Icon(
                            if (isError) Icons.Outlined.CloudOff else Icons.Outlined.Sync,
                            contentDescription = null,
                            tint = if (isError) {
                                MaterialTheme.colorScheme.onErrorContainer
                            } else {
                                MaterialTheme.colorScheme.onTertiaryContainer
                            },
                        )
                        Text(
                            label,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = if (isError) {
                                MaterialTheme.colorScheme.onErrorContainer
                            } else {
                                MaterialTheme.colorScheme.onTertiaryContainer
                            },
                        )
                    }
                }
            }
        }
        val blockedSharedHandles = remember(blockedAccountIds, state.workspaceMembers) {
            state.workspaceMembers
                .filter { it.userId in blockedAccountIds }
                .mapTo(hashSetOf()) { it.username.lowercase() }
        }
        val hasBlockedSharedMessages = sharedConversation && state.chatMessages.any { item ->
            item.channelId == activeChannel?.id && item.authorUsername.lowercase() in blockedSharedHandles
        }
        if (hasBlockedSharedMessages) {
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            ) {
                Text(
                    "A blocked person participates here. Blocking stops direct contact; shared workspace messages remain visible to preserve the team record.",
                    Modifier.padding(horizontal = 13.dp, vertical = 10.dp),
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    fontSize = 12.sp,
                )
            }
        }
        if (chatSearchOpen) {
            OutlinedTextField(
                value = chatQuery,
                onValueChange = { chatQuery = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                singleLine = true,
                leadingIcon = { Icon(Icons.Outlined.Search, null) },
                trailingIcon = { IconButton(onClick = { chatQuery = "" }) { Icon(Icons.Outlined.Close, "Clear search") } },
                placeholder = { Text("Search this conversation") },
                shape = RoundedCornerShape(18.dp),
            )
        }
        val sheetClipboard = LocalClipboardManager.current
        messageActions?.let { target ->
            MessageActionsSheet(
                message = target,
                canUnsend = viewModel.canUnsendChatMessage(target),
                canEdit = target.authorUsername.equals(currentUsername, true) && target.body.isNotBlank(),
                canReport = viewModel.canReportChatMessage(target),
                onReply = { replyTarget = target; editingTarget = null; messageActions = null },
                onEdit = { editingTarget = target; replyTarget = null; message = TextFieldValue(target.body, TextRange(target.body.length)); messageActions = null },
                onTogglePin = { viewModel.setChatMessagePinned(target, !target.pinned); messageActions = null },
                onToggleStar = { viewModel.setChatMessageStarred(target, !target.starred); messageActions = null },
                onReact = { emoji -> viewModel.toggleChatReaction(target, emoji); messageActions = null },
                onForward = { forwardTarget = target; messageActions = null },
                onSelect = { selectedIds = setOf(target.id); messageActions = null },
                onCopy = {
                    sheetClipboard.setText(AnnotatedString(target.body))
                    messageActions = null
                },
                onDeleteForMe = {
                    viewModel.deleteChatMessageForMe(target.id)
                    messageActions = null
                },
                onUnsend = {
                    viewModel.unsendChatMessage(target)
                    messageActions = null
                },
                onReport = {
                    reportTarget = target
                    messageActions = null
                },
                onDismiss = { messageActions = null },
            )
        }
        reportTarget?.let { target ->
            ReportMessageDialog(
                message = target,
                onSubmit = { category, note, alsoBlock ->
                    viewModel.reportChatMessage(target, category, note, alsoBlock)
                    reportTarget = null
                },
                onDismiss = { reportTarget = null },
            )
        }
        whoReacted?.let { target ->
            WhoReactedSheet(reactions = reactionUsers(target.reactionsJson), onDismiss = { whoReacted = null })
        }
        forwardTarget?.let { target ->
            ForwardTargetSheet(
                channels = state.chatChannels.filter { it.id != activeChannel?.id },
                onForward = { channel ->
                    viewModel.forwardChatMessage(target, channel.id)
                    forwardTarget = null
                },
                onDismiss = { forwardTarget = null },
            )
        }
        forwardBatch?.let { batch ->
            ForwardTargetSheet(
                channels = state.chatChannels.filter { it.id != activeChannel?.id },
                onForward = { channel ->
                    batch.forEach { viewModel.forwardChatMessage(it, channel.id) }
                    forwardBatch = null
                    selectedIds = null
                },
                onDismiss = { forwardBatch = null },
            )
        }
        if (detailsOpen) {
            ConversationDetailsSheet(
                state = state,
                channel = activeChannel,
                presence = presence,
                viewModel = viewModel,
                onDismiss = { detailsOpen = false },
                onTogglePin = { channel ->
                    viewModel.setChatChannelPinned(channel.id, !channel.pinned)
                    detailsOpen = false
                },
                onOpenProfile = { username, display -> profileTarget = username to display },
                onReviewRules = { channel -> rulesReviewChannelId = channel.id },
            )
        }
        activeChannel?.takeIf { it.id == rulesReviewChannelId }?.let { channel ->
            GroupRulesSheet(
                channel = channel,
                onAcknowledge = {
                    viewModel.markChatGroupRulesSeen(channel)
                    rulesReviewChannelId = null
                },
                onDismiss = { rulesReviewChannelId = null },
            )
        }
        profileTarget?.let { (username, display) ->
            ProfileViewerSheet(
                username = username,
                displayName = display,
                presence = presence,
                channel = activeChannel,
                viewModel = viewModel,
                onDismiss = { profileTarget = null },
                onMessage = { user ->
                    viewModel.openDirectMessage(user, display)
                    profileTarget = null
                    detailsOpen = false
                },
            )
        }
        if (notePickerOpen) {
            NotePickerSheet(
                notes = state.notes,
                onPick = { note ->
                    val title = note.title.ifBlank { "Untitled note" }
                    viewModel.sendChat(noteReferenceMarker(note.id, title))
                    notePickerOpen = false
                },
                onDismiss = { notePickerOpen = false },
            )
        }
        // Message list grows to fill; input pinned at the bottom above the keyboard.
        // Scope to the active channel; General is every message with no channel.
        val messages = state.chatMessages
            .filter { it.channelId == state.activeChatChannelId }
            .filter { chatQuery.isBlank() || it.body.contains(chatQuery, true) || it.attachmentName?.contains(chatQuery, true) == true || it.authorDisplayName.contains(chatQuery, true) }
        val messageGroups = remember(messages) { groupChatMessages(messages) }
        // Exiting multi-select via the system back gesture, before it closes the whole thread.
        BackHandler(enabled = selectedIds != null) { selectedIds = null }
        val listState = androidx.compose.foundation.lazy.rememberLazyListState()
        val chatScope = rememberCoroutineScope()
        // Snapshot the "read up to" watermark once per conversation open so the unread divider
        // stays anchored while the user reads, instead of sliding down message by message.
        val unreadWatermark = remember(activeChannel?.id) { viewModel.chatLastReadAt(activeChannel?.id) }
        // The first group that starts a run of unread, incoming messages — where the divider sits.
        val firstUnreadIndex = remember(messageGroups, unreadWatermark, currentUsername) {
            if (unreadWatermark <= 0L) -1 else messageGroups.indexOfFirst { group ->
                group.first().createdAt > unreadWatermark &&
                    !group.first().authorUsername.equals(currentUsername, ignoreCase = true)
            }
        }
        androidx.compose.runtime.LaunchedEffect(messageGroups.size, highlightedMessageId) {
            val highlightedIndex = highlightedMessageId?.let { id -> messageGroups.indexOfFirst { group -> group.any { it.id == id } }.takeIf { it >= 0 } }
            if (highlightedIndex != null) {
                listState.animateScrollToItem(highlightedIndex)
                viewModel.consumeHighlightedChatMessage()
            } else if (messageGroups.isNotEmpty()) {
                listState.animateScrollToItem(messageGroups.lastIndex)
            }
        }
        // A floating "jump to latest" affordance appears once the newest messages scroll out of
        // view, matching the big-three ergonomics; hidden when already near the bottom.
        val atBottom by remember {
            derivedStateOf {
                val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()
                last == null || last.index >= messageGroups.lastIndex - 1
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
        Column(Modifier.fillMaxSize()) {
        // Multi-select action bar: shown while a selection is active. Batch forward / delete-for-me,
        // with a live count. Selecting a single message from the actions sheet seeds the set.
        selectedIds?.let { ids ->
            val chosen = messages.filter { it.id in ids }
            Surface(color = MaterialTheme.colorScheme.primaryContainer, tonalElevation = 3.dp, modifier = Modifier.fillMaxWidth()) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    IconButton(onClick = { selectedIds = null }) {
                        Icon(Icons.Outlined.Close, "Exit selection", tint = MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                    Text(
                        "${chosen.size} selected",
                        Modifier.weight(1f),
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                    IconButton(
                        onClick = { if (chosen.isNotEmpty()) forwardBatch = chosen },
                        enabled = chosen.isNotEmpty(),
                    ) {
                        Icon(Icons.AutoMirrored.Outlined.Send, "Forward selected", tint = MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                    IconButton(
                        onClick = {
                            chosen.forEach { viewModel.deleteChatMessageForMe(it.id) }
                            selectedIds = null
                        },
                        enabled = chosen.isNotEmpty(),
                    ) {
                        Icon(Icons.Outlined.Delete, "Delete selected for me", tint = MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                }
            }
        }
        // Pinned-messages banner (WhatsApp/Telegram): a sticky strip above the thread. Tapping it
        // scrolls to the most-recent pinned message via the existing highlight mechanism.
        val pinned = remember(messages) { messages.filter { it.pinned } }
        if (pinned.isNotEmpty()) {
            val target = pinned.last()
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.96f),
                tonalElevation = 2.dp,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)
                    .focusableChrome(
                        radiusDp = 12f,
                        surface = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.96f),
                    ) {
                        val idx = messageGroups.indexOfFirst { g -> g.any { it.id == target.id } }
                        if (idx >= 0) {
                            viewModel.flashChatMessage(target.id)
                            chatScope.launch { listState.animateScrollToItem(idx) }
                        }
                    },
            ) {
                Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(Modifier.width(3.dp).height(30.dp).clip(RoundedCornerShape(99.dp)).background(MaterialTheme.colorScheme.primary))
                    Icon(Icons.Outlined.PushPin, "Pinned", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            if (pinned.size == 1) "Pinned message" else "${pinned.size} pinned messages",
                            fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            target.body.ifBlank { target.attachmentName.orEmpty() },
                            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(2.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 10.dp),
        ) {
            if (messages.isEmpty()) {
                item {
                    ChatEmptyState(kind = activeChannel?.kind, title = channelTitle, onPrompt = { message = TextFieldValue(it, TextRange(it.length)) })
                }
            }
            itemsIndexed(messageGroups, key = { _, group -> "${group.first().id}:${group.last().id}" }) { index, group ->
                val chat = group.last()
                val prev = messageGroups.getOrNull(index - 1)?.lastOrNull()
                val next = messageGroups.getOrNull(index + 1)?.firstOrNull()
                // A day boundary breaks the author run: the chip sits between the groups and the
                // first message after it gets its full header again.
                val newDay = prev == null || !sameDay(prev.createdAt, chat.createdAt)
                val firstOfGroup = newDay || prev.authorUsername != chat.authorUsername
                val lastOfGroup = next == null || next.authorUsername != chat.authorUsername || !sameDay(chat.createdAt, next.createdAt)
                if (index == firstUnreadIndex) {
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        androidx.compose.material3.HorizontalDivider(Modifier.weight(1f), color = MaterialTheme.colorScheme.primary.copy(alpha = 0.4f))
                        Text("New messages", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
                        androidx.compose.material3.HorizontalDivider(Modifier.weight(1f), color = MaterialTheme.colorScheme.primary.copy(alpha = 0.4f))
                    }
                }
                if (newDay) {
                    Box(Modifier.fillMaxWidth().padding(vertical = 6.dp), contentAlignment = Alignment.Center) {
                        Surface(shape = RoundedCornerShape(999.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                            Text(dayChipLabel(chat.createdAt), Modifier.padding(horizontal = 14.dp, vertical = 5.dp), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                val selecting = selectedIds != null
                val groupIds = group.map { it.id }.toSet()
                val groupSelected = selecting && groupIds.all { it in (selectedIds ?: emptySet()) }
                val toggleGroup = {
                    val cur = selectedIds ?: emptySet()
                    selectedIds = if (groupSelected) cur - groupIds else cur + groupIds
                }
                // `toggleableChrome` rather than `focusableChrome`, because while a selection is active this row
                // *is* a checkbox: it reports `Role.Checkbox` and its own checked state on one node, where before
                // the state lived only in a decorative `Icon` swap that a screen reader met as a second,
                // separate node with a name and no action.
                //
                // The row states its own name, and has to: the bubble inside it is a `combinedClickable`, which
                // merges its own descendants, and `SemanticsNode`'s merge walk stops at a merging child. So the
                // message text does not travel up into this node and a nameless row would announce as a bare
                // "checkbox, not ticked". The icon's description is dropped for the mirror-image reason — it was
                // a second named node for the state this node now carries.
                //
                // Only while selecting: outside selection mode the row is inert and takes no chrome at all, so it
                // neither rings nor claims a role it does not have.
                //
                // The wash moves *after* the substrate deliberately, against the usual order: `toggleableChrome`
                // clips the ripple itself, so a fill chained before it would be clipped too and the flat
                // 0.08-alpha tint would then be re-clipped to the same bounds for nothing. The ripple stays
                // bounded either way because the clip is inside the modifier, not at the call site.
                Row(
                    Modifier.fillMaxWidth()
                        .then(
                            if (selecting) {
                                Modifier
                                    .semantics {
                                        contentDescription = "Message from ${chat.authorDisplayName}"
                                    }
                                    .toggleableChrome(
                                        checked = groupSelected,
                                        radiusDp = 0f,
                                        role = androidx.compose.ui.semantics.Role.Checkbox,
                                        surface = MaterialTheme.colorScheme.background,
                                        onValueChange = { toggleGroup() },
                                    )
                            } else {
                                Modifier
                            },
                        )
                        .then(if (groupSelected) Modifier.background(MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)) else Modifier),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (selecting) {
                        Icon(
                            if (groupSelected) Icons.Outlined.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
                            contentDescription = null,
                            tint = if (groupSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                            modifier = Modifier.padding(start = 8.dp).size(22.dp),
                        )
                    }
                    Box(Modifier.weight(1f)) {
                        ChatBubble(
                    chat,
                    firstOfGroup,
                    lastOfGroup,
                    currentUsername,
                    audio = chatAudio,
                    onToggleAudio = viewModel::toggleChatAudio,
                    onCycleAudioSpeed = viewModel::cycleChatAudioSpeed,
                    onOpenAttachment = viewModel::openChatAttachment,
                    onResolveAttachment = viewModel::chatAttachmentPreviewSource,
                    attachmentGroup = group,
                    richPreviewsEnabled = serviceCapabilities.richPreviewsEnabled,
                    onPlayOnlineVideo = { onlineVideoId = it },
                    onReply = { if (!selecting) { replyTarget = chat; editingTarget = null } },
                    onLongPress = { if (!selecting) messageActions = it },
                    onTapReaction = { m, emoji -> if (!selecting) viewModel.toggleChatReaction(m, emoji) },
                    highlighted = highlightedMessageId != null && group.any { it.id == highlightedMessageId },
                    onTapReplyPreview = { preview ->
                        // Reply previews are stored as "Author: body"; find the newest earlier
                        // message whose author+body match, then scroll to and flash it.
                        val body = preview.substringAfter(": ", preview).trim()
                        val srcIdx = messageGroups.indexOfLast { g ->
                            g.any { m -> m.body.trim().startsWith(body.take(40)) && preview.startsWith(m.authorDisplayName) }
                        }
                        if (srcIdx >= 0) {
                            viewModel.flashChatMessage(messageGroups[srcIdx].last().id)
                            chatScope.launch { listState.animateScrollToItem(srcIdx) }
                        }
                    },
                    onLongPressReactions = { if (!selecting) whoReacted = it },
                    onOpenNote = { noteId -> state.notes.firstOrNull { it.id == noteId }?.let { viewModel.select(it) } },
                    onTapAuthor = { m -> if (!selecting) profileTarget = m.authorUsername to m.authorDisplayName },
                        )
                    }
                }
            }
        }
        androidx.compose.animation.AnimatedVisibility(
            visible = !atBottom && messageGroups.isNotEmpty(),
            enter = androidx.compose.animation.fadeIn() + androidx.compose.animation.scaleIn(initialScale = 0.8f),
            exit = androidx.compose.animation.fadeOut() + androidx.compose.animation.scaleOut(targetScale = 0.8f),
            modifier = Modifier.align(Alignment.BottomEnd).padding(end = 6.dp, bottom = 6.dp),
        ) {
            val jumpFocus = rememberChromeFocus()
            // Read once for the edge and the ring, so both measure against the same pixel.
            val jumpFill = MaterialTheme.colorScheme.surface
            Surface(
                onClick = { chatScope.launch { listState.animateScrollToItem(messageGroups.lastIndex) } },
                shape = CircleShape,
                color = jumpFill,
                shadowElevation = 6.dp,
                // Measured. This 40 dp circle floats over the message list, and its `surface` fill separates from
                // the page by 1.0613:1 light and 1.0664:1 dark — so nothing but the stroke says a button is there
                // rather than a stray glyph. `outlineVariant` drew 1.1402:1 and 1.1648:1 on top of that. The
                // 6 dp shadow helps in light mode and does almost nothing in dark, which is exactly where the
                // faint token was faintest relative to what it had to separate.
                border = androidx.compose.foundation.BorderStroke(
                    ChromeGeometry.RESTING_EDGE_WIDTH.dp,
                    with(NorfoldChrome) { restingEdge(jumpFill) },
                ),
                interactionSource = jumpFocus.source,
                // A 40 dp circle, so radius 20 — measured on its own `surface`, which is what the ring is
                // drawn on as the button floats over the message list.
                modifier = Modifier.size(40.dp).then(jumpFocus.ringOnCircle(40f, jumpFill)),
            ) {
                Icon(
                    Icons.Outlined.KeyboardArrowDown,
                    "Jump to latest messages",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(8.dp),
                )
            }
        }
        }
        }
        }
        val typingChannelId = activeChannel?.syncId ?: "general"
        val now = System.currentTimeMillis()
        val typingPeople = presence.filter {
            it.typing && it.typingChannelId == typingChannelId && it.typingUntil > now &&
                !it.username.equals(currentUsername, ignoreCase = true)
        }.map { it.displayName.ifBlank { it.username } }
        if (typingPeople.isNotEmpty()) {
            ChatTypingIndicator(typingPeople, state.settings.reduceMotion)
        }
        val composerModifier = Modifier
            .fillMaxWidth()
            .then(if (imeVisible) Modifier.padding(bottom = 10.dp) else Modifier.navigationBarsPadding())
        if (chatUploads.isNotEmpty()) {
            ChatUploadStrip(
                uploads = chatUploads,
                onRetry = viewModel::retryFailedChatUploads,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            )
        }
        // The composer's fill, hoisted for the edge below. Left translucent on purpose: the substrate composites
        // a declared alpha fill over the resolved container, and here there is no container to resolve — this
        // `Surface` publishes none — so the page is the right backdrop and passing the painted expression is what
        // lands the solve on the pixel. The 3 dp of tonal elevation contributes nothing: Material tints only a
        // fill that **is** `colorScheme.surface`, and an alpha copy is not, which [ChatRecordingRow] below
        // already relies on.
        val composerFill = MaterialTheme.colorScheme.surface.copy(alpha = 0.98f)
        Surface(
            composerModifier.padding(horizontal = 8.dp, vertical = 6.dp),
            color = composerFill,
            shape = RoundedCornerShape(26.dp),
            // Measured. This is the app's most-used control — the chat composer — and its fill separates from the
            // page by 1.0613:1 light and 1.0664:1 dark, so the stroke is what says where the text field ends and
            // the thread begins. `outlineVariant` drew 1.1402:1 and 1.1648:1: the fainter of the two tokens on the
            // one surface a user looks at for the whole session. The 12 dp shadow does real work in light mode
            // and very little in dark, which is where the token was weakest.
            border = androidx.compose.foundation.BorderStroke(
                ChromeGeometry.RESTING_EDGE_WIDTH.dp,
                with(NorfoldChrome) { restingEdge(composerFill) },
            ),
            tonalElevation = 3.dp,
            shadowElevation = 12.dp,
        ) {
            Column(Modifier.fillMaxWidth().animateContentSize().padding(horizontal = 8.dp, vertical = 7.dp)) {
                (editingTarget ?: replyTarget)?.let { target ->
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.width(3.dp).height(34.dp).clip(RoundedCornerShape(99.dp)).background(MaterialTheme.colorScheme.primary))
                        Column(Modifier.weight(1f).padding(horizontal = 9.dp)) {
                            Text(if (editingTarget != null) "Editing message" else "Replying to ${target.authorDisplayName}", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                            Text(target.body.ifBlank { target.attachmentName.orEmpty() }, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 12.sp)
                        }
                        IconButton(onClick = { editingTarget = null; replyTarget = null; if (message.text == target.body) message = TextFieldValue("") }, modifier = Modifier.size(34.dp)) {
                            Icon(Icons.Outlined.Close, "Cancel reply or edit")
                        }
                    }
                }
                if (recordingState != null) {
                    ChatRecordingRow(
                        state = requireNotNull(recordingState),
                        onCancel = viewModel::cancelVoiceRecording,
                        onTogglePause = viewModel::toggleVoiceRecordingPause,
                        onFinish = viewModel::finishVoiceRecording,
                    )
                } else {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                val clipboard = LocalClipboardManager.current
                val context = LocalContext.current
                // + button opens the premium bottom-sheet tray
                IconButton(
                    onClick = { toolsExpanded = true },
                    modifier = Modifier.size(44.dp).clip(CircleShape)
                        .background(if (toolsExpanded) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant),
                ) {
                    Icon(Icons.Outlined.Add, "Attach to message",
                        tint = if (toolsExpanded) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (toolsExpanded) {
                    NorfoldBottomSheet(onDismissRequest = { toolsExpanded = false }) {
                        ChatAttachmentTray(
                            onDismiss = { toolsExpanded = false },
                            onPickAttachment = onPickAttachment,
                            onGif = { toolsExpanded = false; gifPickerOpen = true },
                            onSticker = { toolsExpanded = false; stickerPickerOpen = true },
                            onShareNote = { toolsExpanded = false; notePickerOpen = true },
                            onPaste = {
                                toolsExpanded = false
                                val clip = context.getSystemService(ClipboardManager::class.java)?.primaryClip
                                val richUris = clip?.let { c -> buildList { for (i in 0 until c.itemCount) c.getItemAt(i).uri?.let(::add) } }.orEmpty()
                                if (richUris.isNotEmpty()) viewModel.sendChatAttachments(richUris.distinct())
                                else clipboard.getText()?.text?.takeIf { it.isNotBlank() }?.let { pasted ->
                                    val joined = if (message.text.isBlank()) pasted else "${message.text} $pasted"
                                    message = TextFieldValue(joined, TextRange(joined.length))
                                }
                            },
                        )
                    }
                }
                // Mentions work in every conversation. Workspace members cover shared channels;
                // account contacts cover personal DMs/groups. Dedupe by handle because a person
                // can belong to both directories.
                val allMentionMembers = remember(state.workspaceMembers, socialContacts) {
                    (state.workspaceMembers + socialContacts.mapNotNull { contact ->
                        val handle = contact.handle?.takeIf(String::isNotBlank) ?: return@mapNotNull null
                        WorkspaceMember(
                            userId = contact.contactId,
                            username = handle,
                            displayName = contact.displayName.ifBlank { handle },
                            avatarPath = contact.avatarPath,
                        )
                    }).distinctBy { it.username.lowercase() }
                }
                val mentionCandidates = mentionQuery(message)?.let { query ->
                    allMentionMembers
                        .filter { !it.username.equals(currentUsername, ignoreCase = true) }
                        .filter {
                            query.isEmpty() ||
                                it.username.contains(query, ignoreCase = true) ||
                                it.displayName.contains(query, ignoreCase = true)
                        }
                        .take(6)
                }.orEmpty()
                Column(Modifier.weight(1f)) {
                    if (mentionCandidates.isNotEmpty()) {
                        MentionSuggestions(
                            candidates = mentionCandidates,
                            onPick = { member -> message = applyMention(message, member.username); viewModel.setChatDraft(activeChannel?.id, message.text) },
                        )
                    }
                    RichChatMessageField(
                        value = message,
                        onValueChange = { message = it; viewModel.setChatDraft(activeChannel?.id, it.text) },
                        onReceiveAttachments = { uris -> viewModel.sendChatAttachments(uris) },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = "Message $composerTarget…",
                        maxLines = 4,
                    )
                }
                val canSend = message.text.isNotBlank()
                val micPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
                    if (granted) viewModel.startVoiceRecording()
                }
                if (canSend) {
                    IconButton(
                        onClick = {
                            val editing = editingTarget
                            if (editing != null) viewModel.editChatMessage(editing, message.text)
                            else viewModel.sendChat(message.text, replyTarget)
                            editingTarget = null
                            replyTarget = null
                            message = TextFieldValue("")
                            viewModel.setChatDraft(activeChannel?.id, "")
                        },
                        modifier = Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary),
                    ) {
                        Icon(Icons.AutoMirrored.Outlined.Send, "Send message", tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(20.dp))
                    }
                } else {
                    // Empty composer: the slot becomes a mic, the messenger-standard affordance.
                    IconButton(
                        onClick = {
                            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                                viewModel.startVoiceRecording()
                            } else {
                                micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            }
                        },
                        modifier = Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                    ) {
                        Icon(Icons.Outlined.Mic, "Record voice message", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                    }
                }
                }
                }
            }
        }
    }
}

@Composable
private fun PrivateChatArchiveDetails(
    state: PrivateChatArchiveUiState,
    onUpdate: () -> Unit,
    onRevealRecovery: () -> Unit,
    onHideRecovery: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    NorfoldContentDialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 4.dp,
        ) {
            Column(
                Modifier.fillMaxWidth().padding(22.dp),
                verticalArrangement = Arrangement.spacedBy(13.dp),
            ) {
                Text("Private Chat archive", fontWeight = FontWeight.Black, fontSize = 22.sp)
                Text(
                    "Direct messages and personal groups are encrypted before a provider receives them. Live device keys, outbox operations, and workspace Chat are not stored in this archive.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 20.sp,
                )
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    shape = RoundedCornerShape(16.dp),
                ) {
                    Column(
                        Modifier.fillMaxWidth().padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(5.dp),
                    ) {
                        Text(state.status, fontWeight = FontWeight.Bold)
                        Text(
                            if (state.providerIds.isEmpty()) "No completed provider write in this session"
                            else state.providerIds.sorted().joinToString(prefix = "Protected on "),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 12.sp,
                        )
                    }
                }
                FilledTonalButton(onClick = onUpdate, modifier = Modifier.fillMaxWidth()) {
                    Text("Update encrypted archive now")
                }
                Text(
                    "Portable recovery material can restore the archive when the original device is unavailable. Norfold support cannot recreate it.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                )
                val recovery = state.recoveryMaterial
                if (recovery == null) {
                    OutlinedButton(onClick = onRevealRecovery, modifier = Modifier.fillMaxWidth()) {
                        Text("Reveal recovery material")
                    }
                } else {
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        shape = RoundedCornerShape(14.dp),
                    ) {
                        Column(
                            Modifier.fillMaxWidth().padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                "Store this somewhere private. Anyone with it and your encrypted archive can read that archive.",
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                            )
                            SelectionContainer {
                                Text(recovery, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextButton(onClick = {
                                    val clip = ClipData.newPlainText("Norfold private Chat recovery", recovery)
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                        clip.description.extras = PersistableBundle().apply {
                                            putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
                                        }
                                    }
                                    (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                                        .setPrimaryClip(clip)
                                    onHideRecovery()
                                }) { Text("Copy and hide") }
                                TextButton(onClick = onHideRecovery) { Text("Hide") }
                            }
                        }
                    }
                }
                TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) { Text("Done") }
            }
        }
    }
}

@Composable
private fun WorkspaceSecurityDisclosure(
    security: ChatBackend.WorkspaceSecuritySnapshot,
    onAcknowledge: () -> Unit,
    onNotNow: () -> Unit,
) {
    val encrypted = security.securityMode == ChatSecurityMode.EndToEndEncrypted
    NorfoldContentDialog(onDismissRequest = onNotNow) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 4.dp,
        ) {
            Column(
                Modifier.fillMaxWidth().padding(22.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.size(52.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            if (encrypted) Icons.Outlined.Lock else Icons.Outlined.Business,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                }
                Text(
                    if (encrypted) "This workspace is end-to-end encrypted" else "This is a Managed workspace",
                    fontWeight = FontWeight.Black,
                    fontSize = 22.sp,
                )
                Text(
                    if (encrypted) {
                        "Message content is encrypted between authorized member devices. Norfold cannot normally read it, and older private history may depend on a trusted device or your private Chat archive."
                    } else {
                        "Access remains members-only and protected by authentication, TLS, authorization, and database policies. Content is not end-to-end encrypted, so authorized workspace or backend features may process it."
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 20.sp,
                )
                Text(
                    "Security epoch ${security.epochNumber}. Messages keep the security semantics they had when sent.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                )
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = onNotNow) { Text("Not now") }
                    Button(onClick = onAcknowledge) { Text("I understand") }
                }
            }
        }
    }
}

@Composable
private fun WorkspaceSecurityDetails(
    security: ChatBackend.WorkspaceSecuritySnapshot,
    canManage: Boolean,
    onChangeMode: (ChatSecurityMode) -> Unit,
    onDismiss: () -> Unit,
) {
    var confirming by remember(security.securityVersion) { mutableStateOf<ChatSecurityMode?>(null) }
    val encrypted = security.securityMode == ChatSecurityMode.EndToEndEncrypted
    NorfoldContentDialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 4.dp,
        ) {
            Column(
                Modifier.fillMaxWidth().padding(22.dp),
                verticalArrangement = Arrangement.spacedBy(13.dp),
            ) {
                Text("Workspace security", fontWeight = FontWeight.Black, fontSize = 22.sp)
                Text(
                    if (encrypted) "End-to-end encrypted" else "Managed",
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    if (encrypted) {
                        "Authorized member devices encrypt and decrypt message content. The backend relays ciphertext."
                    } else {
                        "Members-only access remains protected, while authorized backend and workspace features may process message content."
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "Epoch ${security.epochNumber} · ${if (security.acknowledged) "reviewed on this account" else "review required"}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                )
                if (canManage) {
                    val target = if (encrypted) ChatSecurityMode.Managed else ChatSecurityMode.EndToEndEncrypted
                    if (confirming == null) {
                        FilledTonalButton(
                            onClick = { confirming = target },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(if (encrypted) "Switch to Managed…" else "Switch to end-to-end encryption…")
                        }
                    } else {
                        Surface(
                            color = MaterialTheme.colorScheme.errorContainer,
                            shape = RoundedCornerShape(18.dp),
                        ) {
                            Column(
                                Modifier.padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                Text("Create a new security epoch?", fontWeight = FontWeight.Bold)
                                Text(
                                    if (confirming == ChatSecurityMode.Managed) {
                                        "Future messages will be Managed and not end-to-end encrypted. Existing encrypted history will not be decrypted or re-uploaded."
                                    } else {
                                        "The switch succeeds only after every active member has an authorized secure device. Existing Managed history will remain Managed."
                                    },
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                    fontSize = 13.sp,
                                )
                                Row(Modifier.align(Alignment.End)) {
                                    TextButton(onClick = { confirming = null }) { Text("Cancel") }
                                    Button(onClick = { onChangeMode(requireNotNull(confirming)) }) {
                                        Text("Change security")
                                    }
                                }
                            }
                        }
                    }
                }
                TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) {
                    Text("Close")
                }
            }
        }
    }
}

@Composable
private fun ChatTypingIndicator(names: List<String>, reduceMotion: Boolean) {
    var phase by remember { mutableStateOf(0) }
    LaunchedEffect(reduceMotion) {
        if (!reduceMotion) {
            while (true) {
                delay(180L)
                phase = (phase + 1) % 3
            }
        }
    }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Text(
            if (names.size == 1) "${names.first()} is typing" else "${names.first()} and ${names.size - 1} more are typing",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
            repeat(3) { index ->
                Box(
                    Modifier
                        .size(if (!reduceMotion && phase == index) 7.dp else 5.dp)
                        .clip(CircleShape)
                        .background(
                            MaterialTheme.colorScheme.primary.copy(
                                alpha = if (reduceMotion || phase == index) 1f else 0.38f,
                            ),
                        ),
                )
            }
        }
    }
}

/**
 * A Compose-hosted Android text editor for the chat composer.
 *
 * It deliberately opts into AndroidX receive-content so Gboard's `commitContent`, clipboard
 * image pastes, drag-and-drop, animated GIFs, and stickers arrive as the same URI attachments
 * that the picker uses. A normal Compose text field only receives its text representation on
 * the Compose version this app ships, which is why image paste previously did nothing.
 */
@Composable
private fun RichChatMessageField(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    onReceiveAttachments: (List<Uri>) -> Unit,
    placeholder: String,
    maxLines: Int,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    AndroidView(
        modifier = modifier,
        factory = { context ->
            EditText(context).apply {
                val density = resources.displayMetrics.density
                setPadding((14 * density).roundToInt(), (10 * density).roundToInt(), (14 * density).roundToInt(), (10 * density).roundToInt())
                minHeight = (48 * density).roundToInt()
                isSingleLine = false
                setLines(1)
                this.maxLines = maxLines
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                hint = placeholder
                contentDescription = placeholder
                doAfterTextChanged { editable ->
                    val text = editable?.toString().orEmpty()
                    val selection = selectionStart.coerceIn(0, text.length)
                    if (text != value.text || selection != value.selection.start) {
                        onValueChange(TextFieldValue(text, TextRange(selection)))
                    }
                }
                ViewCompat.setOnReceiveContentListener(this, arrayOf("image/*", "video/*")) { _, content ->
                    val attachmentUris = buildList {
                        for (index in 0 until content.clip.itemCount) {
                            content.clip.getItemAt(index).uri?.let(::add)
                        }
                    }.distinct()
                    if (attachmentUris.isEmpty()) content else {
                        onReceiveAttachments(attachmentUris)
                        null
                    }
                }
            }
        },
        update = { field ->
            field.hint = placeholder
            field.maxLines = maxLines
            field.setTextColor(colors.onSurface.toArgb())
            field.setHintTextColor(colors.onSurfaceVariant.toArgb())
            field.background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 22f * field.resources.displayMetrics.density
                setColor(colors.surface.toArgb())
                setStroke((1 * field.resources.displayMetrics.density).roundToInt(), colors.outlineVariant.toArgb())
            }
            if (field.text.toString() != value.text) {
                field.setText(value.text)
                field.setSelection(value.selection.end.coerceIn(0, value.text.length))
            }
        },
    )
}

@Composable
private fun ChatGifPicker(
    state: com.norfold.app.ui.GifPickerState,
    onQueryChange: (String) -> Unit,
    onPick: (com.norfold.app.cloud.KlipyGif) -> Unit,
    onDismiss: () -> Unit,
) {
    NorfoldBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().heightIn(min = 360.dp, max = 680.dp).padding(horizontal = 14.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            OutlinedTextField(
                value = state.query,
                onValueChange = onQueryChange,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                leadingIcon = { Icon(Icons.Outlined.Search, null) },
                placeholder = { Text("Search KLIPY") },
                shape = RoundedCornerShape(18.dp),
            )
            when {
                state.loading && state.results.isEmpty() -> Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                state.error != null -> Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(state.error, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        TextButton(onClick = { onQueryChange(state.query) }) { Text("Retry") }
                    }
                }
                else -> LazyVerticalGrid(
                    columns = GridCells.Adaptive(116.dp),
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    gridItems(state.results, key = { it.slug }) { gif ->
                        Surface(
                            modifier = Modifier.fillMaxWidth().height(112.dp).focusableChrome(
                                radiusDp = 12f,
                                surface = MaterialTheme.colorScheme.surfaceVariant,
                            ) { onPick(gif) },
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                        ) {
                            AsyncImage(
                                model = gif.previewUrl,
                                contentDescription = gif.title,
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop,
                            )
                        }
                    }
                }
            }
            Text("Powered by KLIPY", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.align(Alignment.End).padding(bottom = 6.dp))
        }
    }
}

/**
 * Premium `+` attachment tray (Telegram/WhatsApp grade): a bottom-sheet grid of large rounded
 * tinted icon tiles with labels, replacing the old flat anchored menu. Each tile springs on tap.
 */
@Composable
private fun ChatAttachmentTray(
    onDismiss: () -> Unit,
    onPickAttachment: (ChatComposerAction) -> Unit,
    onGif: () -> Unit,
    onSticker: () -> Unit,
    onPaste: () -> Unit,
    onShareNote: () -> Unit,
) {
    data class Tool(
        val label: String,
        val icon: androidx.compose.ui.graphics.vector.ImageVector,
        val tint: Color,
        val onClick: () -> Unit,
    )
    val tools = listOf(
        Tool("Gallery", Icons.Outlined.Image, Color(0xFF4C8DFF)) { onDismiss(); onPickAttachment(ChatComposerAction.Media) },
        Tool("Camera", Icons.Outlined.PhotoCamera, Color(0xFF22C07A)) { onDismiss(); onPickAttachment(ChatComposerAction.Camera) },
        Tool("File", Icons.Outlined.AttachFile, Color(0xFFF5B53D)) { onDismiss(); onPickAttachment(ChatComposerAction.File) },
        Tool("Audio", Icons.Outlined.Mic, Color(0xFF31D07A)) { onDismiss(); onPickAttachment(ChatComposerAction.Audio) },
        Tool("GIF", Icons.Outlined.GifBox, Color(0xFFFF7A59)) { onGif() },
        Tool("Stickers", Icons.Outlined.GridView, Color(0xFF8C67FF)) { onSticker() },
        Tool("Note", Icons.AutoMirrored.Outlined.Note, Color(0xFF3DB2A0)) { onDismiss(); onShareNote() },
        Tool("Paste", Icons.Outlined.ContentPaste, Color(0xFFA86BFF)) { onPaste() },
    )
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Text("Attach", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
        LazyVerticalGrid(
            columns = GridCells.Fixed(4),
            verticalArrangement = Arrangement.spacedBy(18.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp),
        ) {
            gridItems(tools) { tool ->
                // The tile is the focus stop, not the 56 dp tinted square inside it: the label is part of the
                // target and a ring around the square alone would exclude the word the user is reading. No
                // `surface` argument, uniquely among this file's conversions — `NorfoldBottomSheet` publishes
                // `LocalChromeContainer`, so the substrate's default already resolves to the sheet's own colour,
                // and naming a token here would be a second, staler opinion about it.
                Column(
                    Modifier
                        .focusableChrome(
                            corner = Corner.Card,
                            onClickLabel = tool.label,
                            onClick = tool.onClick,
                        )
                        .padding(vertical = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Box(
                        Modifier.size(56.dp).clip(RoundedCornerShape(18.dp)).background(tool.tint.copy(alpha = 0.18f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(tool.icon, contentDescription = null, tint = tool.tint, modifier = Modifier.size(24.dp))
                    }
                    Text(tool.label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface)
                }
            }
        }
        Spacer(Modifier.navigationBarsPadding())
    }
}

/** The same fixed sticker manifest used by Image Focus; no GIF/search provider is involved. */
@Composable
private fun ChatStickerPicker(onDismiss: () -> Unit, onPick: (Sticker) -> Unit) {
    var query by remember { mutableStateOf("") }
    var category by remember { mutableStateOf(StickerCategory.POPULAR) }
    val stickers = remember(category, query) { StickerCatalog.items(category, query) }
    NorfoldBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().heightIn(min = 360.dp, max = 680.dp).padding(16.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Stickers", style = MaterialTheme.typography.titleLarge)
                    Text("Family-safe local collection", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = onDismiss, modifier = Modifier.size(48.dp)) { Icon(Icons.Outlined.Close, "Close stickers") }
            }
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("Search stickers") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StickerCategory.entries.forEach { candidate ->
                    FilterChip(category == candidate, { category = candidate }, label = { Text(candidate.label) })
                }
            }
            LazyVerticalGrid(
                columns = GridCells.Adaptive(76.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth().weight(1f),
            ) {
                gridItems(stickers, key = { it.id }) { sticker ->
                    val stickerFocus = rememberChromeFocus()
                    Surface(
                        onClick = { onPick(sticker) },
                        shape = RoundedCornerShape(18.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        interactionSource = stickerFocus.source,
                        // The sticker image is padded 10 dp inside the tile, so the ring at 3 dp sits in the
                        // tile's own `surfaceVariant` gutter rather than over the artwork.
                        modifier = Modifier.size(76.dp).then(stickerFocus.ringOnShape(18f, MaterialTheme.colorScheme.surfaceVariant)),
                    ) {
                        AsyncImage(sticker.assetUri, sticker.label, Modifier.padding(10.dp).fillMaxSize())
                    }
                }
            }
        }
    }
}

/** Expanding voice composer with a live microphone waveform and explicit pause control. */
// Premium voice recorder (WhatsApp/Telegram grade):
// red circular trash · big elapsed timer · live scrolling waveform · pause pill · accent send FAB.
//
// All three controls take `focusablePill` rather than `focusableChrome`, because each is a fixed-size circle
// and `focusablePill` resolves its own radius from the measured box — half the smaller dimension, which for a
// square box *is* the circle's radius. Passing a number instead would mean restating 23f, 20f and 25f as
// literals that have to stay half of three sizes stated a line above.
//
// Each ring is drawn *inside* the circle it decorates, so the pixel under it is the control's own fill rather
// than the page — and that matters most on the send button, which is filled `primary`, the ring's own colour:
// a page-measured ring would land at 1.00:1 on the one control the user reaches for first. The two opaque fills
// go straight through; the discard fill is translucent, so it is composited here instead. The substrate would
// composite it too — `EditorChromeColors.opaque` is exactly that hop — but against `LocalChromeContainer` or the
// page, and neither is what this circle sits on: the composer is a `Surface` painted `surface.copy(alpha = 0.98f)`
// and publishes no container. It also takes no tonal tint at 3 dp, because Material applies that only to a fill
// `== colorScheme.surface` and this one carries an alpha. So `opaque` twice, page-upward, is the whole stack —
// the "caller that knows its real stack should pass the resolved backdrop" case that function names.
@Composable
private fun ChatRecordingRow(
    state: VoiceRecordingState,
    onCancel: () -> Unit,
    onTogglePause: () -> Unit,
    onFinish: () -> Unit,
) {
    val composer = EditorChromeColors.opaque(
        MaterialTheme.colorScheme.surface.copy(alpha = 0.98f),
        MaterialTheme.colorScheme.background,
    )
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // Red circular trash — cancel
        val discardFill = MaterialTheme.colorScheme.error.copy(alpha = 0.15f)
        Box(
            Modifier
                .size(46.dp)
                .background(discardFill, CircleShape)
                .focusablePill(
                    surface = EditorChromeColors.opaque(discardFill, composer),
                    role = androidx.compose.ui.semantics.Role.Button,
                    onClick = onCancel,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Outlined.Delete, "Discard recording", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(22.dp))
        }
        // Centre pill: rec dot · timer · waveform
        Row(
            Modifier
                .weight(1f)
                .clip(RoundedCornerShape(24.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(horizontal = 12.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(if (state.paused) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.error),
            )
            Text(
                formatVoiceTime(state.elapsedMs),
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
            )
            LiveVoiceWaveform(state.amplitudes, state.elapsedMs)
        }
        // Pause pill
        Box(
            Modifier
                .size(40.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant, CircleShape)
                .focusablePill(
                    surface = MaterialTheme.colorScheme.surfaceVariant,
                    role = androidx.compose.ui.semantics.Role.Button,
                    onClick = onTogglePause,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (state.paused) Icons.Outlined.PlayArrow else Icons.Outlined.Pause,
                if (state.paused) "Resume" else "Pause",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
        // Accent circular send FAB
        Box(
            Modifier
                .size(50.dp)
                .background(MaterialTheme.colorScheme.primary, CircleShape)
                .focusablePill(
                    surface = MaterialTheme.colorScheme.primary,
                    role = androidx.compose.ui.semantics.Role.Button,
                    onClick = onFinish,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.AutoMirrored.Outlined.Send, "Send voice message", tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(22.dp))
        }
    }
}

@Composable
private fun LiveVoiceWaveform(amplitudes: List<Float>, elapsedMs: Long) {
    BoxWithConstraints(Modifier.fillMaxWidth().height(28.dp), contentAlignment = Alignment.CenterStart) {
        val visibleBars = (maxWidth.value / 4.5f).toInt().coerceIn(12, 72)
        // The visual grows with the recording, then becomes a moving window. It never paints a
        // fake red progress fill: every bar is an actual sampled microphone peak.
        val grownBars = ((elapsedMs / 80L) + 1L).toInt().coerceIn(1, visibleBars)
        val samples = amplitudes.takeLast(grownBars)
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            samples.forEach { peak ->
                Box(
                    Modifier
                        .width(2.dp)
                        .height((4f + peak * 22f).dp)
                        .clip(RoundedCornerShape(99.dp))
                        .background(MaterialTheme.colorScheme.primary),
                )
            }
        }
    }
}

/** Delivery status for queued attachment sends, shown just above the composer. */
@Composable
private fun ChatUploadStrip(uploads: List<ChatUploadItem>, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    val failed = uploads.count { it.status == ChatUploadStatus.Failed }
    Row(
        modifier.padding(horizontal = 10.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (failed > 0) {
            Text(
                if (failed == 1) "1 file didn't send" else "$failed files didn't send",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onRetry) { Text("Retry", fontSize = 12.sp) }
        } else {
            CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
            val active = uploads.firstOrNull { it.status == ChatUploadStatus.Uploading } ?: uploads.first()
            Text(
                if (uploads.size == 1) "Sending ${active.displayName}…"
                else "Sending ${active.displayName} · ${uploads.size - 1} more",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

private fun formatVoiceTime(ms: Long): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}

@Composable
private fun ChatEmptyState(kind: ChatChannelKind?, title: String, onPrompt: (String) -> Unit) {
    val (heading, detail) = when (kind) {
        null -> "Start the workspace conversation" to
            "Capture updates, questions, and decisions beside the work they belong to. Messages stay in this workspace and are included in encrypted backups."
        ChatChannelKind.Dm -> "Say hi to $title" to
            "This is the start of your direct messages. It stays between the two of you."
        ChatChannelKind.Group -> "This group is brand new" to
            "Kick things off — everyone in the group sees what's posted here."
        ChatChannelKind.Topic -> "Welcome to #$title" to
            "This channel keeps one topic in one place. Start the thread."
    }
    com.norfold.app.ui.components.NorfoldEmptyState(
        icon = Icons.Outlined.ChatBubbleOutline,
        title = heading,
        detail = detail,
    ) {
        // Prompt chips only fit the workspace-feed voice; a fresh DM just needs the composer.
        if (kind == null) {
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
}

@Composable
private fun ChatPersonaBar(
    title: String,
    showGeneralAction: Boolean,
    onGeneral: () -> Unit,
    status: String,
    connected: Boolean,
    onBack: () -> Unit,
    onStatusClick: () -> Unit,
    onTitleClick: () -> Unit = {},
    onSearch: () -> Unit = {},
) {
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
                Text(title.take(1).uppercase().ifBlank { "W" }, color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            }
            // Two rings, not one, and they are genuinely two controls: the title opens conversation details,
            // the status line under it re-syncs the chat. `clickable` merges its descendants, but
            // `SemanticsNode.mergeConfig` stops at a child that merges too, so the inner row is *not* swallowed
            // into the outer one — it stays a separate named node with its own action, which is what makes both
            // reachable and both worth ringing. Nesting them is still the weaker arrangement: the whole title
            // block is the outer target, so the status row sits inside its own parent's tap area. That is a
            // layout question for the header's redesign, not something a focus ring should paper over.
            //
            // `Card` for the outer block because the ring wraps a two-line region, `Capsule` for the status row
            // because it is one short line and a card radius on a 16 dp-high strip would read as a lozenge with
            // flat sides. Both are measured against `background`: this `Surface` paints exactly that, and it
            // publishes no `LocalChromeContainer`, so nothing else would resolve it.
            Column(
                Modifier
                    .weight(1f)
                    .focusableChrome(
                        corner = Corner.Card,
                        surface = MaterialTheme.colorScheme.background,
                        onClickLabel = "Open conversation details",
                        onClick = onTitleClick,
                    ),
            ) {
                Text(title, fontWeight = FontWeight.Black, fontSize = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(
                    modifier = Modifier.focusableChrome(
                        corner = Corner.Capsule,
                        surface = MaterialTheme.colorScheme.background,
                        onClickLabel = "Refresh conversation",
                        onClick = onStatusClick,
                    ),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(if (connected) SemanticColors.signalGreen else MaterialTheme.colorScheme.outline))
                    Text(status, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (showGeneralAction) {
                IconButton(
                    onClick = onGeneral,
                    modifier = Modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surface),
                ) {
                    Icon(Icons.Outlined.Tag, "Back to General", modifier = Modifier.size(20.dp))
                }
            }
            IconButton(
                onClick = onSearch,
                modifier = Modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surface),
            ) {
                Icon(Icons.Outlined.Search, "Search conversation", modifier = Modifier.size(20.dp))
            }
        }
    }
}

/**
 * Conversation details, opened by tapping the persona-bar title. Premium viewer: identity header,
 * quick-action chips, a shared-media/link summary, and — for channels/groups — a tappable member
 * roster with live presence. UI-only: everything is derived from state already in memory (no new
 * backend calls); mute/block are local visual toggles until the membership backend ships.
 */
@Composable
private fun ConversationDetailsSheet(
    state: DocsUiState,
    channel: ChatChannel?,
    presence: List<NorfoldPresence.WorkspacePresenceState>,
    viewModel: DocsViewModel,
    onDismiss: () -> Unit,
    onTogglePin: (ChatChannel) -> Unit,
    onOpenProfile: (String, String) -> Unit,
    onReviewRules: (ChatChannel) -> Unit,
) {
    val title = channel?.name ?: "General"
    val isDm = channel?.kind == ChatChannelKind.Dm
    val groupMembersByConversation by viewModel.groupMembers.collectAsState()
    val workspaceMembersByChannel by viewModel.workspaceChannelMembers.collectAsState()
    val socialContacts by viewModel.socialContacts.collectAsState()
    val governanceHistoryByConversation by viewModel.chatGovernanceHistory.collectAsState()
    val governedMembers = channel?.accountConversationId
        ?.let { groupMembersByConversation[it] }
        .orEmpty()
    val governedWorkspaceMembers = channel?.takeIf { it.scope == ChatScope.Workspace }
        ?.let { workspaceMembersByChannel[it.syncId] }
        .orEmpty()
    val canAdminGroup = channel?.let(viewModel::isChatGroupAdmin) == true
    val ownsGroup = channel?.let(viewModel::isChatGroupOwner) == true
    val governanceHistory = channel?.let {
        governanceHistoryByConversation[it.accountConversationId ?: it.syncId]
    }.orEmpty()
    val canManageWorkspace = state.workspaceMembers.firstOrNull {
        it.username.equals(state.settings.syncUserName, ignoreCase = true)
    }?.role?.lowercase() in setOf("owner", "admin")
    LaunchedEffect(channel?.accountConversationId) {
        channel?.takeIf { it.kind == ChatChannelKind.Group }
            ?.let(viewModel::refreshChatGroupMembers)
    }
    LaunchedEffect(channel?.syncId, channel?.visibility) {
        channel?.takeIf { it.scope == ChatScope.Workspace && it.visibility == ChatChannelVisibility.Private }
            ?.let(viewModel::refreshWorkspaceChannelMembers)
    }
    LaunchedEffect(channel?.syncId, channel?.accountConversationId) {
        channel?.let(viewModel::refreshChatGovernanceHistory)
    }
    val kindLabel = when (channel?.kind) {
        null -> "Workspace channel · everyone is here"
        ChatChannelKind.Topic -> "Channel · synced"
        ChatChannelKind.Group -> "Group · synced"
        ChatChannelKind.Dm -> "Direct message"
    }
    // Messages scoped to this conversation drive the media/link summary and the derived roster.
    val convoMessages = remember(state.chatMessages, channel?.id) {
        state.chatMessages.filter { (channel == null && it.channelId == null) || it.channelId == channel?.id }
    }
    val mediaCount = convoMessages.count { it.attachmentUri != null && (it.attachmentMimeType?.startsWith("image/") == true || it.attachmentMimeType?.startsWith("video/") == true) }
    val fileCount = convoMessages.count { it.attachmentUri != null && it.attachmentMimeType?.startsWith("image/") != true && it.attachmentMimeType?.startsWith("video/") != true }
    val linkCount = convoMessages.count { it.body.contains("http://", true) || it.body.contains("https://", true) }
    // Roster: General/topic/group derive posters from the message stream (no membership model yet);
    // a DM is the two participants.
    val roster: List<Pair<String, String>> = remember(
        convoMessages, channel?.id, state.workspaceMembers, governedMembers, governedWorkspaceMembers,
    ) {
        when {
            isDm -> listOf(channel.counterpartUsername to channel.counterpartUsername)
            channel?.kind == ChatChannelKind.Group && governedMembers.isNotEmpty() ->
                governedMembers.map { it.handle.orEmpty() to it.displayName.ifBlank { it.handle.orEmpty() } }
            governedWorkspaceMembers.isNotEmpty() ->
                governedWorkspaceMembers.map { it.handle.orEmpty() to it.displayName.ifBlank { it.handle.orEmpty() } }
            else -> {
                val fromMembers = state.workspaceMembers.map { it.username to it.displayName.ifBlank { it.username } }
                val fromPosts = convoMessages.filter { !it.system }.map { it.authorUsername to it.authorDisplayName.ifBlank { it.authorUsername } }
                (fromMembers + fromPosts).distinctBy { it.first.lowercase() }
            }
        }
    }
    var muted by remember(channel?.id) {
        mutableStateOf(channel?.let(viewModel::isChatConversationMuted) ?: false)
    }
    var notificationLevel by remember(channel?.id) {
        mutableStateOf(
            channel?.let(viewModel::chatConversationNotificationLevel)
                ?: ChatConversationPrefs.NotificationLevel.All,
        )
    }
    var lifespanSeconds by remember(channel?.id, channel?.defaultLifespanSeconds) {
        mutableStateOf(channel?.defaultLifespanSeconds)
    }
    var editingGroup by remember(channel?.id) { mutableStateOf(false) }
    var confirmingLeave by remember(channel?.id) { mutableStateOf(false) }
    val scroll = rememberScrollState()
    if (editingGroup && channel != null) {
        GroupDetailsEditorSheet(
            channel = channel,
            onSave = { name, icon, cover, description, rules, reminderDays ->
                viewModel.updateChatGroupMetadata(
                    channel = channel,
                    name = name,
                    icon = icon,
                    coverPath = cover,
                    description = description,
                    rules = rules,
                    rulesReminderDays = reminderDays,
                )
                editingGroup = false
            },
            onDismiss = { editingGroup = false },
        )
        return
    }
    if (confirmingLeave && channel != null) {
        NorfoldContentDialog(onDismissRequest = { confirmingLeave = false }) {
            Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surface) {
                Column(
                    Modifier.fillMaxWidth().padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text("Leave ${channel.name}?", fontWeight = FontWeight.Bold, fontSize = 20.sp)
                    Text(
                        "Messages stay with the group. You will need a member to add you again.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(Modifier.align(Alignment.End), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { confirmingLeave = false }) { Text("Cancel") }
                        TextButton(onClick = {
                            confirmingLeave = false
                            onDismiss()
                            viewModel.leaveChatGroup(channel)
                        }) { Text("Leave", color = MaterialTheme.colorScheme.error) }
                    }
                }
            }
        }
        return
    }
    NorfoldContentDialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 3.dp,
        ) {
        Column(
            Modifier.fillMaxWidth().heightIn(max = 560.dp).verticalScroll(scroll).padding(bottom = 8.dp),
        ) {
            // Identity header
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 18.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (channel?.kind == ChatChannelKind.Group && !channel.coverUri.isNullOrBlank()) {
                    AsyncImage(
                        model = channel.coverUri,
                        contentDescription = "${channel.name} cover",
                        modifier = Modifier.fillMaxWidth().height(128.dp).clip(RoundedCornerShape(20.dp)),
                        contentScale = ContentScale.Crop,
                    )
                }
                Box(
                    Modifier.size(76.dp).clip(CircleShape).background(
                        if (isDm) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.primary
                    ),
                    contentAlignment = Alignment.Center,
                ) {
                    if (isDm) {
                        Text(
                            title.take(1).uppercase().ifBlank { "?" },
                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                            fontWeight = FontWeight.Bold,
                            fontSize = 30.sp,
                        )
                    } else if (channel?.kind == ChatChannelKind.Group &&
                        channel.icon.isNotBlank() && !channel.icon.startsWith("app.")
                    ) {
                        Text(channel.icon, fontSize = 30.sp)
                    } else {
                        Icon(
                            if (channel?.kind == ChatChannelKind.Group) Icons.Outlined.Groups else Icons.Outlined.Tag,
                            null,
                            Modifier.size(34.dp),
                            tint = MaterialTheme.colorScheme.onPrimary,
                        )
                    }
                }
                Text(title, fontWeight = FontWeight.Black, fontSize = 21.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(kindLabel, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                if (isDm) {
                    val status = presence.firstOrNull { it.username.equals(channel.counterpartUsername, true) }?.availability
                    PresenceLine(status)
                }
            }
            // Quick-action chips
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (isDm) {
                    DetailActionChip(Icons.Outlined.Person, "Profile", Modifier.weight(1f)) {
                        onOpenProfile(channel.counterpartUsername, title)
                    }
                }
                DetailActionChip(
                    if (muted) Icons.Outlined.NotificationsOff else Icons.Outlined.Notifications,
                    if (muted) "Muted" else "Mute",
                    Modifier.weight(1f),
                ) {
                    muted = !muted
                    channel?.let { viewModel.setChatConversationMuted(it, muted) }
                }
                if (channel != null) {
                    DetailActionChip(Icons.Outlined.PushPin, if (channel.pinned) "Unpin" else "Pin", Modifier.weight(1f)) {
                        onTogglePin(channel)
                    }
                }
            }
            if (channel != null) {
                Text(
                    "Notifications",
                    Modifier.padding(start = 20.dp, top = 18.dp, bottom = 6.dp),
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ChatConversationPrefs.NotificationLevel.entries.forEach { level ->
                        val label = when (level) {
                            ChatConversationPrefs.NotificationLevel.None -> "None"
                            ChatConversationPrefs.NotificationLevel.Mentions -> "Mentions"
                            ChatConversationPrefs.NotificationLevel.All -> "All messages"
                        }
                        AssistChip(
                            onClick = {
                                notificationLevel = level
                                viewModel.setChatConversationNotificationLevel(channel, level)
                            },
                            label = { Text(if (notificationLevel == level) "✓ $label" else label) },
                        )
                    }
                }
                Text(
                    "Your disappearing messages",
                    Modifier.padding(start = 20.dp, top = 18.dp, bottom = 6.dp),
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "Only messages you send after this change are affected.",
                    Modifier.padding(horizontal = 20.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                )
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    listOf(
                        null to "Off",
                        86_400L to "24 hours",
                        604_800L to "7 days",
                        2_592_000L to "30 days",
                    ).forEach { (seconds, label) ->
                        AssistChip(
                            onClick = {
                                lifespanSeconds = seconds
                                viewModel.setChatChannelDefaultLifespan(channel, seconds)
                            },
                            label = { Text(if (lifespanSeconds == seconds) "✓ $label" else label) },
                        )
                    }
                }
            }
            // Shared content summary
            if (mediaCount + fileCount + linkCount > 0) {
                Text(
                    "Shared in this conversation",
                    Modifier.padding(start = 20.dp, top = 18.dp, bottom = 6.dp),
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SharedStatCard(Icons.Outlined.PermMedia, mediaCount, "Media", Modifier.weight(1f))
                    SharedStatCard(Icons.Outlined.AttachFile, fileCount, "Files", Modifier.weight(1f))
                    SharedStatCard(Icons.Outlined.Link, linkCount, "Links", Modifier.weight(1f))
                }
            }
            // Member roster
            if (!isDm && roster.isNotEmpty()) {
                Text(
                    if (roster.size == 1) "1 member" else "${roster.size} members",
                    Modifier.padding(start = 20.dp, top = 18.dp, bottom = 4.dp),
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (channel?.kind == ChatChannelKind.Group && governedMembers.isNotEmpty()) {
                    governedMembers.forEach { member ->
                        val username = member.handle.orEmpty()
                        val display = member.displayName.ifBlank { username.ifBlank { "Member" } }
                        val status = presence.firstOrNull { it.username.equals(username, true) }?.availability
                        GroupGovernanceRow(
                            username = username,
                            display = display,
                            role = member.role,
                            status = status,
                            canChangeRole = ownsGroup && member.role != "owner",
                            canTransferOwnership = ownsGroup && member.role != "owner",
                            canRemove = member.role != "owner" && (ownsGroup || (canAdminGroup && member.role == "member")),
                            onOpenProfile = { onOpenProfile(username, display) },
                            onToggleAdmin = {
                                viewModel.setChatGroupMemberRole(
                                    requireNotNull(channel),
                                    member.userId,
                                    if (member.role == "admin") "member" else "admin",
                                )
                            },
                            onTransferOwnership = {
                                viewModel.transferChatGroupOwnership(requireNotNull(channel), member.userId)
                            },
                            onRemove = {
                                viewModel.removeChatGroupMember(requireNotNull(channel), member.userId)
                            },
                        )
                    }
                } else if (channel != null && governedWorkspaceMembers.isNotEmpty()) {
                    governedWorkspaceMembers.forEach { member ->
                        val username = member.handle.orEmpty()
                        val display = member.displayName.ifBlank { username.ifBlank { "Member" } }
                        val status = presence.firstOrNull { it.username.equals(username, true) }?.availability
                        GroupGovernanceRow(
                            username = username,
                            display = display,
                            role = member.role,
                            status = status,
                            canChangeRole = canManageWorkspace && member.role != "owner",
                            canTransferOwnership = canManageWorkspace && member.role != "owner",
                            canRemove = canManageWorkspace && member.role != "owner",
                            onOpenProfile = { onOpenProfile(username, display) },
                            onToggleAdmin = {
                                viewModel.setWorkspaceChannelMemberRole(
                                    channel,
                                    member.userId,
                                    if (member.role == "admin") "member" else "admin",
                                )
                            },
                            onTransferOwnership = {
                                viewModel.transferWorkspaceChannelOwnership(channel, member.userId)
                            },
                            onRemove = { viewModel.removeWorkspaceChannelMember(channel, member.userId) },
                        )
                    }
                } else {
                    roster.forEach { (username, display) ->
                        val status = presence.firstOrNull { it.username.equals(username, true) }?.availability
                        MemberRosterRow(display, username, status) { onOpenProfile(username, display) }
                    }
                }
                val existingMemberIds = when {
                    governedMembers.isNotEmpty() -> governedMembers.mapTo(hashSetOf()) { it.userId }
                    governedWorkspaceMembers.isNotEmpty() -> governedWorkspaceMembers.mapTo(hashSetOf()) { it.userId }
                    else -> emptySet()
                }
                val addableGroupContacts = if (channel?.kind == ChatChannelKind.Group && canAdminGroup) {
                    socialContacts.filter { it.contactId !in existingMemberIds }
                } else emptyList()
                val addableWorkspaceMembers = if (
                    channel != null && channel.scope == ChatScope.Workspace &&
                    channel.visibility == ChatChannelVisibility.Private && canManageWorkspace
                ) {
                    state.workspaceMembers.filter { it.userId !in existingMemberIds }
                } else emptyList()
                if (addableGroupContacts.isNotEmpty() || addableWorkspaceMembers.isNotEmpty()) {
                    Text(
                        "Add member",
                        Modifier.padding(start = 20.dp, top = 10.dp, bottom = 4.dp),
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        addableGroupContacts.forEach { contact ->
                            AssistChip(
                                onClick = { viewModel.addChatGroupMember(requireNotNull(channel), contact.contactId) },
                                label = { Text(contact.displayName.ifBlank { contact.handle ?: "Contact" }) },
                            )
                        }
                        addableWorkspaceMembers.forEach { member ->
                            AssistChip(
                                onClick = { viewModel.addWorkspaceChannelMember(requireNotNull(channel), member.userId) },
                                label = { Text(member.displayName.ifBlank { member.username }) },
                            )
                        }
                    }
                }
                if (governanceHistory.isNotEmpty()) {
                    Text(
                        "Recent access changes",
                        Modifier.padding(start = 20.dp, top = 14.dp, bottom = 4.dp),
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    governanceHistory.take(6).forEach { event ->
                        Text(
                            governanceEventLabel(event),
                            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            if (channel?.kind == ChatChannelKind.Group && channel.accountConversationId != null) {
                Text(
                    "Group details",
                    Modifier.padding(start = 20.dp, top = 18.dp, bottom = 6.dp),
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (channel.description.isNotBlank()) {
                    Text(
                        channel.description,
                        Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (channel.rules.isNotBlank()) {
                    TextButton(
                        onClick = { onDismiss(); onReviewRules(channel) },
                        modifier = Modifier.padding(horizontal = 8.dp),
                    ) { Text("Review group rules") }
                }
                if (viewModel.isChatGroupAdmin(channel)) {
                    TextButton(
                        onClick = { editingGroup = true },
                        modifier = Modifier.padding(horizontal = 8.dp),
                    ) { Text("Edit group details") }
                }
                TextButton(
                    onClick = { confirmingLeave = true },
                    modifier = Modifier.padding(horizontal = 8.dp),
                ) { Text("Leave group", color = MaterialTheme.colorScheme.error) }
            }
            TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End).padding(horizontal = 12.dp, vertical = 4.dp)) {
                Text("Close")
            }
        }
        }
    }
}

@Composable
private fun GroupDetailsEditorSheet(
    channel: ChatChannel,
    onSave: (String, String, String?, String, String, Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember(channel.id) { mutableStateOf(channel.name) }
    var icon by remember(channel.id) {
        mutableStateOf(channel.icon.takeUnless { it.startsWith("app.") }.orEmpty())
    }
    var cover by remember(channel.id) { mutableStateOf(channel.coverUri.orEmpty()) }
    var description by remember(channel.id) { mutableStateOf(channel.description) }
    var rules by remember(channel.id) { mutableStateOf(channel.rules) }
    var reminderDays by remember(channel.id) { mutableStateOf(channel.rulesReminderDays.toString()) }
    var validationMessage by remember(channel.id) { mutableStateOf<String?>(null) }
    val scroll = rememberScrollState()
    NorfoldContentDialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surface, tonalElevation = 3.dp) {
            Column(
                Modifier.fillMaxWidth().heightIn(max = 620.dp).verticalScroll(scroll).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("Edit group", fontWeight = FontWeight.Black, fontSize = 22.sp)
                Text(
                    "These details are shared with every member.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp,
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(80); validationMessage = null },
                    label = { Text("Group name") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                OutlinedTextField(
                    value = icon,
                    onValueChange = { icon = it.take(16) },
                    label = { Text("Icon or emoji") },
                    placeholder = { Text("Example: ✨") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                OutlinedTextField(
                    value = cover,
                    onValueChange = { cover = it.take(2_048); validationMessage = null },
                    label = { Text("Cover image link") },
                    supportingText = { Text("Use an https link, or leave blank to remove the cover.") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it.take(500) },
                    label = { Text("Description") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                    maxLines = 4,
                )
                OutlinedTextField(
                    value = rules,
                    onValueChange = { rules = it.take(4_000) },
                    label = { Text("Group rules") },
                    supportingText = { Text("New members review these when they first open the group.") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 4,
                    maxLines = 8,
                )
                OutlinedTextField(
                    value = reminderDays,
                    onValueChange = { value ->
                        if (value.all(Char::isDigit) && value.length <= 3) reminderDays = value
                        validationMessage = null
                    },
                    label = { Text("Rules reminder (days)") },
                    supportingText = { Text("From 1 to 365 days.") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                validationMessage?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
                }
                Row(Modifier.align(Alignment.End), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    Button(onClick = {
                        val parsedDays = reminderDays.toIntOrNull()
                        validationMessage = when {
                            name.isBlank() -> "Give the group a name."
                            cover.isNotBlank() && !cover.startsWith("https://", ignoreCase = true) ->
                                "Cover links must start with https://."
                            parsedDays == null || parsedDays !in 1..365 -> "Choose a reminder from 1 to 365 days."
                            else -> null
                        }
                        if (validationMessage == null) {
                            onSave(
                                name.trim(),
                                icon.trim(),
                                cover.trim().ifBlank { null },
                                description.trim(),
                                rules.trim(),
                                parsedDays!!,
                            )
                        }
                    }) { Text("Save") }
                }
            }
        }
    }
}

@Composable
private fun GroupRulesSheet(
    channel: ChatChannel,
    onAcknowledge: () -> Unit,
    onDismiss: () -> Unit,
) {
    NorfoldContentDialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surface, tonalElevation = 3.dp) {
            Column(
                Modifier.fillMaxWidth().heightIn(max = 560.dp).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(channel.name, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                Text("Group rules", fontWeight = FontWeight.Black, fontSize = 22.sp)
                Text(
                    channel.rules,
                    Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "You will see these again in ${channel.rulesReminderDays.coerceIn(1, 365)} days.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                )
                Row(Modifier.align(Alignment.End), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onDismiss) { Text("Later") }
                    Button(onClick = onAcknowledge) { Text("I understand") }
                }
            }
        }
    }
}

/** Small presence line ("● Active now" / "Away" / "Offline") shown under a DM header. */
/**
 * The presence hue. Green for online, amber for away, the theme's own outline for everything else.
 *
 * Literal rather than a theme token because these are status colours in the traffic-light sense — the same two
 * hues every chat app uses — not roles in Norfold's palette, and re-tinting "online" per accent would make the
 * dot mean something different in each theme. Held as a function so [PresenceLine] and [MemberRosterRow] cannot
 * end up drawing different greens, which they did.
 */
@Composable
private fun presenceDot(status: String?): Color = when (status) {
    "online" -> Color(0xFF2ECC71)
    "away" -> Color(0xFFF1C40F)
    else -> MaterialTheme.colorScheme.outline
}

/** What the dot means, in words. The non-visual half of [presenceDot], and paired with it for that reason. */
private fun presenceLabel(status: String?): String = when (status) {
    "online" -> "Active now"
    "away" -> "Away"
    else -> "Offline"
}

@Composable
private fun PresenceLine(status: String?) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(presenceDot(status)))
        Text(presenceLabel(status), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
    }
}

/** Pill quick-action used in the conversation/profile viewers. */
@Composable
private fun DetailActionChip(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val fill = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
    val focus = rememberChromeFocus()
    Surface(
        onClick = onClick,
        // 60% alpha over whatever page hosts the chip, so the substrate's `opaque` step decides what the
        // ring is measured against rather than the unpainted colour.
        modifier = modifier.then(focus.ringOnShape(16f, fill)),
        shape = RoundedCornerShape(16.dp),
        color = fill,
        interactionSource = focus.source,
    ) {
        Column(
            Modifier.padding(vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(icon, label, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
            Text(label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface)
        }
    }
}

/** Counter card for shared media/files/links. */
@Composable
private fun SharedStatCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    count: Int,
    label: String,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
    ) {
        Column(
            Modifier.padding(vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Icon(icon, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("$count", fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Text(label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * One tappable roster entry: avatar + presence dot, display name, @username.
 *
 * Flush — `radiusDp = 0f` — because the row spans the sheet's full inner width and has no corner of its own; a
 * tier here would round a ring against square content. No `surface` argument, because both callers are inside
 * [com.norfold.app.ui.components.NorfoldContentDialog], which publishes `LocalChromeContainer` as
 * `colorScheme.surface` and whose host `Surface` provides its own 3 dp as `LocalAbsoluteTonalElevation` — so the
 * substrate resolves the tinted colour this row is actually drawn on, which a named token here could not.
 *
 * The two `Text` children are plain and do not merge, so the display name and `@username` travel up into the
 * clickable's node and the row needs no name of its own.
 *
 * ## The presence dot said nothing
 *
 * The dot is 9 dp of green or amber and no text — availability by hue alone, which WCAG 1.4.1 excludes at
 * Level A and which a screen reader could not reach at all: it is a `Box` with a `background` and no semantics.
 * [PresenceLine] already carries the same three states *with* their words, so the labels exist; they were simply
 * not on this row. `stateDescription` puts them on the merged node — the idiom `RecoverySurface` and the task
 * checklist row already use — so the row announces "Ada Lovelace, @ada, Active now" rather than leaving the one
 * fact the dot was drawn to convey available only to someone who can see 9 dp of colour.
 */
@Composable
private fun MemberRosterRow(display: String, username: String, status: String?, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .semantics { stateDescription = presenceLabel(status) }
            .focusableChrome(
                radiusDp = 0f,
                onClickLabel = "Open profile",
                onClick = onClick,
            )
            .padding(horizontal = 20.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(contentAlignment = Alignment.BottomEnd) {
            Box(
                Modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                Text(display.take(1).uppercase().ifBlank { "?" }, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            }
            if (status == "online" || status == "away") {
                Box(Modifier.size(12.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surface), contentAlignment = Alignment.Center) {
                    Box(Modifier.size(9.dp).clip(CircleShape).background(presenceDot(status)))
                }
            }
        }
        Column(Modifier.weight(1f)) {
            Text(display, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("@$username", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.outline, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun GroupGovernanceRow(
    username: String,
    display: String,
    role: String,
    status: String?,
    canChangeRole: Boolean,
    canTransferOwnership: Boolean,
    canRemove: Boolean,
    onOpenProfile: () -> Unit,
    onToggleAdmin: () -> Unit,
    onTransferOwnership: () -> Unit,
    onRemove: () -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        MemberRosterRow(display, username, status, onOpenProfile)
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(start = 72.dp, end = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                role.replaceFirstChar { it.uppercase() },
                color = MaterialTheme.colorScheme.primary,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
            )
            if (canChangeRole) {
                TextButton(onClick = onToggleAdmin) {
                    Text(if (role == "admin") "Make member" else "Make admin")
                }
            }
            if (canTransferOwnership) {
                TextButton(onClick = onTransferOwnership) { Text("Make owner") }
            }
            if (canRemove) {
                TextButton(onClick = onRemove) {
                    Text("Remove", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

private fun governanceEventLabel(event: ChatBackend.GovernanceEvent): String {
    val actor = event.actorName.ifBlank { "Someone" }
    val target = event.targetName.ifBlank { "a member" }
    return when (event.eventType) {
        "member_added" -> "$actor added $target"
        "member_removed" -> "$actor removed $target"
        "role_changed" -> "$actor changed $target's role"
        "ownership_transferred" -> "$target became the owner"
        "metadata_changed" -> "$actor updated the group details"
        "channel_archived" -> "$actor archived the channel"
        else -> "$actor changed conversation access"
    }
}

/**
 * Messenger-grade profile viewer for a single person: large avatar, presence, quick actions
 * (message / mute / block), and identity details. UI-only — mute/block are local visual toggles
 * until the people/blocking backend ships.
 */
@Composable
private fun ProfileViewerSheet(
    username: String,
    displayName: String,
    presence: List<NorfoldPresence.WorkspacePresenceState>,
    channel: ChatChannel?,
    viewModel: DocsViewModel,
    onDismiss: () -> Unit,
    onMessage: (String) -> Unit,
) {
    val status = presence.firstOrNull { it.username.equals(username, true) }?.availability
    val directConversation = channel?.takeIf {
        it.kind == ChatChannelKind.Dm && it.counterpartUsername.equals(username, ignoreCase = true)
    }
    var muted by remember(username, directConversation?.id) {
        mutableStateOf(directConversation?.let(viewModel::isChatConversationMuted) ?: false)
    }
    var blocked by remember(username) { mutableStateOf(viewModel.isContactBlocked(username)) }
    NorfoldContentDialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surface, tonalElevation = 3.dp) {
            Column(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 22.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Box(
                        Modifier.size(96.dp).clip(CircleShape).background(MaterialTheme.colorScheme.tertiaryContainer),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            displayName.take(1).uppercase().ifBlank { "?" },
                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                            fontWeight = FontWeight.Bold,
                            fontSize = 40.sp,
                        )
                    }
                    Text(displayName, fontWeight = FontWeight.Black, fontSize = 22.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("@$username", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                    PresenceLine(status)
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DetailActionChip(Icons.Outlined.ChatBubbleOutline, "Message", Modifier.weight(1f)) {
                        onMessage(username)
                    }
                    if (directConversation != null) {
                        DetailActionChip(
                            if (muted) Icons.Outlined.NotificationsOff else Icons.Outlined.Notifications,
                            if (muted) "Muted" else "Mute",
                            Modifier.weight(1f),
                        ) {
                            muted = !muted
                            viewModel.setChatConversationMuted(directConversation, muted)
                        }
                    }
                    DetailActionChip(Icons.Outlined.Block, if (blocked) "Blocked" else "Block", Modifier.weight(1f)) {
                        blocked = !blocked
                        if (blocked) viewModel.blockContactByUsername(username)
                        else viewModel.unblockContactByUsername(username)
                    }
                }
                Text(
                    "About",
                    Modifier.padding(start = 20.dp, top = 20.dp, bottom = 6.dp),
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                ProfileInfoRow(Icons.Outlined.AlternateEmail, "Username", "@$username")
                ProfileInfoRow(Icons.Outlined.Person, "Display name", displayName)
                TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End).padding(horizontal = 12.dp, vertical = 4.dp)) {
                    Text("Close")
                }
            }
        }
    }
}

/** Labelled identity row inside the profile viewer. */
@Composable
private fun ProfileInfoRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    value: String,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(icon, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Column(Modifier.weight(1f)) {
            Text(label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
fun ConflictReviewScreen(state: DocsUiState, viewModel: DocsViewModel) {
    val localChanges = state.workspaceObjectHistory.sortedByDescending { it.createdAt }.take(8)
    val recentActivity = state.workspaceActivities.sortedByDescending { it.createdAt }.take(5)
    val report = state.conflictReport
    WorkspaceScreenFrame("Conflict review", "Review and resolve sync differences") {
        item {
            SectionCard {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(Icons.Outlined.Difference, null, tint = MaterialTheme.colorScheme.primary)
                    Column(Modifier.weight(1f)) {
                        Text("Sync conflict", fontWeight = FontWeight.Black, fontSize = 20.sp)
                        Text("${state.settings.syncProvider.displayName} · ${state.settings.syncDeviceName}", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                    }
                }
                Text("Status: ${state.settings.lastSyncStatus}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = viewModel::loadConflictReport, modifier = Modifier.weight(1f)) { Text("Load report") }
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
                DifferenceLine("- Remote snapshot changed outside this local base", SemanticColors.signalRed.copy(alpha = 0.20f))
                DifferenceLine("+ Local snapshot also has changes waiting to sync", SemanticColors.signalGreen.copy(alpha = 0.20f))
                DifferenceLine("+ New conflict files include object counts and changed item lists", SemanticColors.signalGreen.copy(alpha = 0.20f))
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
                        DifferenceLine("+ ${event.historyType.name}: ${event.summary}", SemanticColors.signalGreen.copy(alpha = 0.16f))
                    }
                }
            }
        }
        item {
            SectionCard {
                Text("Remote side", fontWeight = FontWeight.Black, fontSize = 18.sp)
                if (report == null) {
                    DifferenceLine("- Load the latest Norfold conflict JSON to show decrypted remote counts and changed item labels.", SemanticColors.signalRed.copy(alpha = 0.17f))
                    recentActivity.forEach { activity ->
                        Text("${activity.activityType.name} · ${activity.title} · ${relativeSyncTime(activity.createdAt)}", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                    }
                } else {
                    ConflictSideStats(report.remote)
                    ConflictReportList("Only on this device", report.diff.localOnly, "+", SemanticColors.signalGreen.copy(alpha = 0.16f))
                    ConflictReportList("Only on remote", report.diff.remoteOnly, "-", SemanticColors.signalRed.copy(alpha = 0.17f))
                    ConflictReportList("Newer locally", report.diff.localNewer, "+", SemanticColors.signalGreen.copy(alpha = 0.16f))
                    ConflictReportList("Newer remotely", report.diff.remoteNewer, "-", SemanticColors.signalRed.copy(alpha = 0.17f))
                    Text("${report.diff.changedCount} changed shared objects found in the conflict report.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                }
            }
        }
        item {
            SectionCard {
                Text("Resolution", fontWeight = FontWeight.Black, fontSize = 18.sp)
                Text("Choose local only if this device should overwrite the remote encrypted snapshot on the next manual sync. Use restore if the remote copy should win.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                Button(onClick = viewModel::useLocalForNextConflictSync, modifier = Modifier.fillMaxWidth()) { Text("Use local next sync") }
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

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
/** A parsed inline note reference carried in a chat body as [[note:<id>:<title>]]. */
private data class NoteReference(val id: Long, val title: String, val marker: String)

private val NOTE_REF_REGEX = Regex("""\[\[note:(\d+):([^\]]*)]]""")

/** Extracts the first [[note:id:title]] marker from [body], or null if none is present. */
private fun parseNoteReference(body: String): NoteReference? {
    val m = NOTE_REF_REGEX.find(body) ?: return null
    val id = m.groupValues[1].toLongOrNull() ?: return null
    return NoteReference(id, m.groupValues[2].ifBlank { "Untitled note" }, m.value)
}

private data class StickerReference(val id: String, val marker: String)
private val STICKER_REF_REGEX = Regex("""\[\[sticker:([a-z0-9_]+)]]""")
private fun stickerReferenceMarker(id: String): String = "[[sticker:$id]]"
private fun parseStickerReference(body: String): StickerReference? =
    STICKER_REF_REGEX.find(body)?.let { StickerReference(it.groupValues[1], it.value) }

/** Builds the inline marker for a shared note. Kept next to the parser so they stay in sync. */
private fun noteReferenceMarker(id: Long, title: String): String =
    "[[note:$id:${title.replace("]", " ").take(80)}]]"

/** Tappable card rendering a shared note reference inside a chat bubble; opens the note on tap. */
@Composable
private fun ChatNoteCard(ref: NoteReference, tint: Color, onOpen: () -> Unit) {
    val fill = MaterialTheme.colorScheme.surface.copy(alpha = 0.55f)
    val focus = rememberChromeFocus()
    Surface(
        onClick = onOpen,
        shape = RoundedCornerShape(14.dp),
        color = fill,
        border = androidx.compose.foundation.BorderStroke(1.dp, tint.copy(alpha = 0.18f)),
        interactionSource = focus.source,
        // The card sits inside a chat bubble, so the ring is measured on the card's own translucent fill —
        // what it composites to over that bubble — not on the page.
        modifier = Modifier.padding(top = 6.dp).widthIn(max = 260.dp).then(focus.ringOnShape(14f, fill)),
    ) {
        Row(
            Modifier.padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                Modifier.size(38.dp).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.AutoMirrored.Outlined.Note, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
            }
            Column(Modifier.weight(1f, fill = false)) {
                Text("Shared note", fontSize = 11.sp, color = tint.copy(alpha = 0.7f))
                Text(ref.title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = tint, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** Note picker for the "Share note" tray action: search + list of the workspace's notes. */
@Composable
private fun NotePickerSheet(notes: List<Note>, onPick: (Note) -> Unit, onDismiss: () -> Unit) {
    var query by remember { mutableStateOf("") }
    val filtered = remember(notes, query) {
        if (query.isBlank()) notes
        else notes.filter { it.title.contains(query, true) || it.bodyMarkdown.contains(query, true) }
    }
    NorfoldContentDialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surface, tonalElevation = 3.dp) {
            Column(Modifier.fillMaxWidth().heightIn(max = 520.dp).padding(vertical = 12.dp)) {
                Text("Share a note", Modifier.padding(horizontal = 20.dp, vertical = 4.dp), fontWeight = FontWeight.Bold, fontSize = 16.sp)
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("Search notes") },
                    leadingIcon = { Icon(Icons.Outlined.Search, null) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                )
                if (filtered.isEmpty()) {
                    Text("No notes found", Modifier.padding(horizontal = 20.dp, vertical = 14.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                }
                androidx.compose.foundation.lazy.LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false)) {
                    items(filtered, key = { it.id }) { note ->
                        // Flush and unnamed, like the two sibling picker rows in this file: the row spans the
                        // dialog's full inner width, and its two plain `Text` children merge upward so the title
                        // and the body preview are the announcement. `onClickLabel` names the *action*, which the
                        // content cannot — "Untitled note, Empty" says nothing about what a tap does. No `surface`
                        // argument: `NorfoldContentDialog` publishes `colorScheme.surface` as the container and the
                        // host `Surface` publishes its own 3 dp, so the substrate resolves the tinted pixel.
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .focusableChrome(
                                    radiusDp = 0f,
                                    onClickLabel = "Share this note",
                                    onClick = { onPick(note) },
                                )
                                .padding(horizontal = 20.dp, vertical = 11.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Box(
                                Modifier.size(38.dp).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(Icons.AutoMirrored.Outlined.Note, null, Modifier.size(19.dp), tint = MaterialTheme.colorScheme.primary)
                            }
                            Column(Modifier.weight(1f)) {
                                Text(note.title.ifBlank { "Untitled note" }, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(note.bodyMarkdown.take(60).ifBlank { "Empty" }, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
                TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End).padding(horizontal = 12.dp)) {
                    Text("Cancel")
                }
            }
        }
    }
}

@Composable
private fun ChatBubble(
    chat: ChatMessageItem,
    firstOfGroup: Boolean,
    lastOfGroup: Boolean,
    currentUsername: String,
    audio: ChatAudioState?,
    onToggleAudio: (ChatMessageItem) -> Unit,
    onCycleAudioSpeed: (ChatMessageItem) -> Unit,
    onOpenAttachment: (ChatMessageItem) -> Unit,
    onResolveAttachment: suspend (ChatMessageItem) -> String?,
    attachmentGroup: List<ChatMessageItem> = listOf(chat),
    richPreviewsEnabled: Boolean,
    onPlayOnlineVideo: (String) -> Unit,
    onReply: (ChatMessageItem) -> Unit,
    onLongPress: (ChatMessageItem) -> Unit = {},
    onTapReaction: (ChatMessageItem, String) -> Unit = { _, _ -> },
    highlighted: Boolean = false,
    onTapReplyPreview: (String) -> Unit = {},
    onLongPressReactions: (ChatMessageItem) -> Unit = {},
    onOpenNote: (Long) -> Unit = {},
    onTapAuthor: (ChatMessageItem) -> Unit = {},
) {
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
    val mine = chat.authorUsername.equals(currentUsername, true)
    // Phase 6: a shared-note reference is carried inline as [[note:<id>:<title>]] (UI-only, no
    // typed payload yet). Parse it out so it renders as a tappable card, not raw marker text.
    val noteRef = remember(chat.body) { parseNoteReference(chat.body) }
    val stickerRef = remember(chat.body) { parseStickerReference(chat.body) }
    val displayBody = chat.body
        .let { if (noteRef != null) it.replace(noteRef.marker, "").trim() else it }
        .let { if (stickerRef != null) it.replace(stickerRef.marker, "").trim() else it }
        .takeUnless { body ->
            body.isBlank() || chat.attachmentName?.let { body.equals("Shared $it", ignoreCase = true) } == true
        }
    val attachmentOnly = !chat.attachmentName.isNullOrBlank() && displayBody == null
    val stickerOnly = stickerRef != null && displayBody == null && chat.attachmentName.isNullOrBlank()
    // A message that is nothing but emoji reads best "bare" — no bubble, oversized glyphs — the
    // way WhatsApp/Telegram/Messenger all render a lone 😮. Only when there's no attachment.
    val emojiOnly = displayBody != null && chat.attachmentName.isNullOrBlank() && isEmojiOnlyMessage(displayBody)
    val chromeless = attachmentOnly || emojiOnly || stickerOnly
    // Outgoing bubble = muted accent tint (primaryContainer), not full accent (primary).
    // primaryContainer is defined in NorfoldTheme as accent.mix(white,0.88f) light /
    // accent.mix(surface,0.72f) dark — calm on long threads, accent still reads clearly.
    val bubbleColor = when {
        chromeless -> Color.Transparent
        mine -> MaterialTheme.colorScheme.primaryContainer
        else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f)
    }
    val textColor = when {
        emojiOnly -> MaterialTheme.colorScheme.onSurface
        attachmentOnly -> MaterialTheme.colorScheme.onSurfaceVariant
        mine -> MaterialTheme.colorScheme.onPrimaryContainer
        else -> MaterialTheme.colorScheme.onSurface
    }
    val tailBottom = 8.dp
    val roundTop = if (firstOfGroup) 24.dp else 10.dp
    val haptics = LocalHapticFeedback.current
    val swipeState = rememberSwipeToDismissBoxState(
        confirmValueChange = { direction ->
            if (direction != SwipeToDismissBoxValue.Settled) {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                onReply(chat)
            }
            false
        },
    )
    SwipeToDismissBox(
        state = swipeState,
        // Messenger-style direction: your right-aligned message moves right-to-left; an
        // incoming left-aligned message moves left-to-right.
        enableDismissFromStartToEnd = !mine,
        enableDismissFromEndToStart = mine,
        backgroundContent = {
            if (swipeState.dismissDirection != SwipeToDismissBoxValue.Settled) {
                Box(
                    Modifier.fillMaxSize().padding(horizontal = 18.dp),
                    contentAlignment = if (mine) Alignment.CenterEnd else Alignment.CenterStart,
                ) {
                    Icon(Icons.AutoMirrored.Outlined.Reply, "Swipe to reply", tint = MaterialTheme.colorScheme.primary)
                }
            }
        },
    ) {
      val highlightAlpha by androidx.compose.animation.core.animateFloatAsState(
          targetValue = if (highlighted) 1f else 0f,
          animationSpec = androidx.compose.animation.core.tween(400),
          label = "replyHighlight",
      )
      Row(
        Modifier.fillMaxWidth()
            .then(if (highlightAlpha > 0f) Modifier.background(MaterialTheme.colorScheme.primary.copy(alpha = 0.10f * highlightAlpha)) else Modifier)
            .padding(top = if (firstOfGroup) 8.dp else 1.dp),
        horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Bottom,
      ) {
        // Avatar gutter (incoming only), rendered on the last message of a run
        if (!mine) {
            if (lastOfGroup) {
                // A circle, so `focusablePill` — it takes the radius from the measured box rather than from a
                // literal that has to stay half of 28. Measured against its own fill, which is
                // `Color(chat.color)`: a per-author hue drawn from outside the theme, so it is the one backdrop
                // nothing else could resolve, and the ring is drawn inside the circle it decorates.
                //
                // It states a name because the initial inside it is one letter. `contentDescription` and the
                // merged `Text` populate different fields of the node — `setContentDescription` reads the
                // config's own key while `setText` reads `getInfoText`, and the merge policy for
                // `ContentDescription` prepends rather than replaces — so this reads as the author's name, not
                // as "A".
                Box(
                    Modifier.size(28.dp).clip(CircleShape).background(Color(chat.color))
                        .focusablePill(
                            surface = Color(chat.color),
                            role = androidx.compose.ui.semantics.Role.Button,
                            onClick = { onTapAuthor(chat) },
                        )
                        .semantics { contentDescription = "${chat.authorDisplayName}, open profile" },
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
                // The same action as the avatar above, reached from the name instead of the initial. Both rings
                // exist because both controls do — a run of messages shows the name once at the top and the
                // avatar once at the bottom, so on a single-message run they are the only two ways in, and on a
                // long run they are at opposite ends of it. 6f matches the `clip` this already had; the ring is
                // measured against the row's own backdrop, which is the page unless the reply-jump highlight is
                // painting over it, and that is a 400 ms animation rather than a state to solve against.
                Text(
                    chat.authorDisplayName,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 12.sp,
                    color = Color(chat.color),
                    modifier = Modifier
                        .focusableChrome(
                            radiusDp = 6f,
                            onClickLabel = "Open profile",
                            onClick = { onTapAuthor(chat) },
                        )
                        .padding(start = 10.dp, bottom = 2.dp),
                )
            }
            Surface(
                modifier = Modifier
                    .widthIn(max = 316.dp)
                    .clip(
                        RoundedCornerShape(
                            topStart = if (mine) 24.dp else roundTop,
                            topEnd = if (mine) roundTop else 24.dp,
                            bottomStart = if (mine || !lastOfGroup) 24.dp else tailBottom,
                            bottomEnd = if (!mine || !lastOfGroup) 24.dp else tailBottom,
                        ),
                    )
                    // No focus ring, deliberately. Two reasons, both structural rather than stylistic: the
                    // bubble's shape is four independent corner radii (a tail corner squares off to point at
                    // its author), so there is no single radius a concentric ring could hold; and its `onClick`
                    // is a no-op — the only real action is the long-press. Ringing it would advertise a
                    // focusable control that does nothing on activation. The long-press is labelled, which
                    // puts the message actions in TalkBack's actions menu; reaching them by keyboard or
                    // switch access is still a product gap, not a ring to draw here.
                    .combinedClickable(
                        onClick = {},
                        onLongClickLabel = "Message actions",
                        onLongClick = {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            onLongPress(chat)
                        },
                    ),
                shape = RoundedCornerShape(
                    topStart = if (mine) 24.dp else roundTop,
                    topEnd = if (mine) roundTop else 24.dp,
                    bottomStart = if (mine || !lastOfGroup) 24.dp else tailBottom,
                    bottomEnd = if (!mine || !lastOfGroup) 24.dp else tailBottom,
                ),
                color = bubbleColor,
                shadowElevation = if (chromeless) 0.dp else 1.dp,
            ) {
                Column(
                    Modifier.padding(
                        horizontal = if (chromeless) 0.dp else 12.dp,
                        vertical = if (chromeless) 0.dp else 7.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(if (chromeless) 2.dp else 4.dp),
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        chat.replyPreview?.let { reply ->
                            // Accent left-bar quote strip (WhatsApp/Telegram style). Tapping it
                            // jumps to the quoted source message (best-effort match by preview text).
                            //
                            // Its fill is 8% of the *text* colour over the bubble, so the ring's real backdrop is
                            // two translucent layers deep and neither of them is anything the substrate could
                            // resolve on its own: the bubble is a `Surface` painted `primaryContainer`,
                            // `surfaceVariant` at 72%, or fully transparent when the message is chromeless, and it
                            // publishes no `LocalChromeContainer`. So the stack is composited here, page upward —
                            // `opaque` returns its input untouched when already opaque, which is what makes the
                            // nesting safe for the `primaryContainer` case as well as the translucent ones.
                            //
                            // 6f matches the `clip` that was already here. The `background` stays ahead of the
                            // substrate: a fill chained after it would be a rounded tint over a square ripple.
                            val quoteFill = textColor.copy(alpha = 0.08f)
                            val onBubble = EditorChromeColors.opaque(
                                quoteFill,
                                EditorChromeColors.opaque(bubbleColor, MaterialTheme.colorScheme.background),
                            )
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .background(quoteFill, RoundedCornerShape(6.dp))
                                    .focusableChrome(
                                        radiusDp = 6f,
                                        surface = onBubble,
                                        onClickLabel = "Jump to quoted message",
                                        onClick = { onTapReplyPreview(reply) },
                                    ),
                            ) {
                                Box(Modifier.width(3.dp).fillMaxHeight().background(MaterialTheme.colorScheme.primary))
                                Text(
                                    reply,
                                    Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    fontSize = 11.sp,
                                    color = textColor.copy(alpha = 0.78f),
                                )
                            }
                        }
                        if (displayBody != null) {
                            Text(
                                highlightMentions(displayBody, textColor),
                                color = textColor,
                                fontSize = if (emojiOnly) 44.sp else 15.sp,
                                lineHeight = if (emojiOnly) 52.sp else 21.sp,
                            )
                            if (richPreviewsEnabled) {
                                youtubeVideoId(displayBody)?.let { id ->
                                    ChatOnlineVideoCard(videoId = id, onPlay = { onPlayOnlineVideo(id) })
                                }
                                // Generic OpenGraph unfurl for any other link (YouTube already has
                                // its own richer card above). Service conserve/degraded modes leave
                                // the link readable but pause remote preview work.
                                if (youtubeVideoId(displayBody) == null && chat.attachmentName.isNullOrBlank()) {
                                    firstLinkPreviewUrl(displayBody)?.let { url ->
                                        ChatLinkPreviewCard(url = url, tint = textColor)
                                    }
                                }
                            }
                        }
                        if (noteRef != null) {
                            ChatNoteCard(noteRef, textColor) { onOpenNote(noteRef.id) }
                        }
                        stickerRef?.let { reference ->
                            StickerCatalog.find(reference.id)?.let { sticker ->
                                AsyncImage(
                                    model = sticker.assetUri,
                                    contentDescription = sticker.label,
                                    modifier = Modifier.size(148.dp).padding(4.dp),
                                )
                            }
                        }
                        if (!chat.attachmentName.isNullOrBlank()) {
                            if (attachmentGroup.size > 1) {
                                ChatAttachmentMosaic(attachmentGroup, onOpenAttachment, onResolveAttachment)
                            } else {
                                ChatAttachmentCard(chat, mine, audio, onToggleAudio, onCycleAudioSpeed, onOpenAttachment, onResolveAttachment)
                            }
                        }
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(3.dp),
                        modifier = Modifier
                            .align(Alignment.End)
                            .padding(
                                top = if (attachmentOnly) 0.dp else if (emojiOnly) 2.dp else 1.dp,
                                end = if (attachmentOnly) 7.dp else if (emojiOnly) 4.dp else 0.dp,
                                bottom = if (attachmentOnly) 5.dp else 0.dp,
                            ),
                    ) {
                        Text(
                            relativeSyncTime(chat.createdAt) + if (chat.editedAt != null) " · edited" else "",
                            fontSize = 9.sp,
                            color = textColor.copy(alpha = 0.6f),
                        )
                        if (chat.pinned) Icon(Icons.Outlined.PushPin, "Pinned message", tint = textColor.copy(alpha = 0.7f), modifier = Modifier.size(11.dp))
                        if (chat.expiresAt != null) Icon(
                            Icons.Outlined.Timer,
                            "Disappearing message",
                            tint = textColor.copy(alpha = 0.7f),
                            modifier = Modifier.size(12.dp),
                        )
                        if (mine) {
                            // Receipt vocabulary (WhatsApp/Telegram hybrid): clock = local only,
                            // muted single tick = sent, muted double tick = delivered, and a bright
                            // blue double tick = read. The read colour is a fixed, high-contrast
                            // value (not a theme token) so it stays visible on any accent bubble —
                            // the old inversePrimary tint was undefined in our scheme and vanished.
                            val muted = textColor.copy(alpha = 0.65f)
                            when (chat.receiptStatus) {
                                null -> Icon(
                                    Icons.Outlined.Schedule,
                                    "Saved locally",
                                    tint = muted,
                                    modifier = Modifier.size(12.dp),
                                )
                                ChatReceiptStatus.Sent -> Icon(
                                    Icons.Outlined.Done,
                                    "Sent",
                                    tint = muted,
                                    modifier = Modifier.size(15.dp),
                                )
                                ChatReceiptStatus.Delivered -> Icon(
                                    Icons.Outlined.DoneAll,
                                    "Delivered",
                                    tint = muted,
                                    modifier = Modifier.size(15.dp),
                                )
                                ChatReceiptStatus.Viewed -> Icon(
                                    Icons.Outlined.DoneAll,
                                    "Read",
                                    tint = ChatReadTickBlue,
                                    modifier = Modifier.size(15.dp),
                                )
                            }
                        }
                    }
                }
            }
            val reactions = remember(chat.reactionsJson) { reactionCounts(chat.reactionsJson) }
            val myReactions = remember(chat.reactionsJson, currentUsername) { reactionsByUser(chat.reactionsJson, currentUsername) }
            if (reactions.isNotEmpty()) {
                val visible = reactions.take(20)
                val overflow = reactions.size - visible.size
                Row(
                    Modifier
                        .padding(top = 3.dp, start = 5.dp, end = 5.dp)
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    visible.forEach { (emoji, count) ->
                        val reactedByMe = emoji in myReactions
                        val pillFill = if (reactedByMe) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface
                        val reactionFocus = rememberChromeFocus()
                        Surface(
                            shape = RoundedCornerShape(99.dp),
                            color = pillFill,
                            // Measured, on whichever fill is painted. These pills hang *below* the bubble on the
                            // page, not inside it, so the un-reacted one is `surface` on `background`: 1.0613:1
                            // light and 1.0664:1 dark of separation, with an `outlineVariant` stroke drawing
                            // 1.1402:1 and 1.1648:1 over it. A reaction pill with no visible edge reads as loose
                            // emoji text under the message rather than a counter you can tap.
                            //
                            // The reacted case keeps `primary` at half alpha: that is state — "I'm in this
                            // reaction" — and a solved neutral would say "pill" where the user needs "my pill".
                            // Its `primaryContainer` fill is already an accent tint, so the state edge has
                            // something to sit on that the neutral one does not.
                            border = androidx.compose.foundation.BorderStroke(
                                ChromeGeometry.RESTING_EDGE_WIDTH.dp,
                                if (reactedByMe) {
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                                } else {
                                    with(NorfoldChrome) { restingEdge(pillFill) }
                                },
                            ),
                            // A pill, so the radius is resolved in the draw scope from the pill's own height
                            // rather than guessed here — `99.dp` is "fully round", not a measurement. The fill
                            // flips with whether I reacted, so the ring is measured on whichever one is painted.
                            modifier = Modifier
                                .then(reactionFocus.ringOnPill(pillFill))
                                .combinedClickable(
                                    interactionSource = reactionFocus.source,
                                    indication = androidx.compose.material3.ripple(),
                                    onClickLabel = if (reactedByMe) "Remove your $emoji reaction" else "React with $emoji",
                                    onLongClickLabel = "See who reacted",
                                    onClick = { onTapReaction(chat, emoji) },
                                    onLongClick = { onLongPressReactions(chat) },
                                ),
                        ) {
                            Text(
                                "$emoji $count",
                                Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
                                fontSize = 10.sp,
                                color = if (reactedByMe) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                    if (overflow > 0) {
                        val overflowFocus = rememberChromeFocus()
                        // Read once for the edge and the ring.
                        val overflowFill = MaterialTheme.colorScheme.surfaceVariant
                        Surface(
                            shape = RoundedCornerShape(99.dp),
                            color = overflowFill,
                            // Same population as the pills beside it, and the faintest fill of the three:
                            // `surfaceVariant` on the page is 1.0534:1 light, and `outlineVariant` over it drew
                            // **1.0199:1** — the single faintest edge in this file. "+3" with no boundary is not a
                            // control, it is a fragment of text.
                            border = androidx.compose.foundation.BorderStroke(
                                ChromeGeometry.RESTING_EDGE_WIDTH.dp,
                                with(NorfoldChrome) { restingEdge(overflowFill) },
                            ),
                            // Tap and long-press both open the full reaction list, so this is one action with
                            // two ways in — it still needs a ring, on its own `surfaceVariant` fill.
                            modifier = Modifier
                                .then(overflowFocus.ringOnPill(overflowFill))
                                .combinedClickable(
                                    interactionSource = overflowFocus.source,
                                    indication = androidx.compose.material3.ripple(),
                                    onClickLabel = "See all reactions",
                                    // Tap and long-press are the same action, so there is nothing extra to
                                    // announce for the gesture — the click label already covers it.
                                    onClick = { onLongPressReactions(chat) },
                                    onLongClick = { onLongPressReactions(chat) },
                                ),
                        ) {
                            Text("+$overflow", Modifier.padding(horizontal = 7.dp, vertical = 3.dp), fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
      }
    }
}

/**
 * Consecutive attachment sends by one author form a swipeable album while each gap stays under
 * one minute. A text/system message or a longer pause closes the album. This groups both a single
 * multi-picker batch and the common "one more photo" follow-up without merging unrelated media.
 */
private fun groupChatMessages(messages: List<ChatMessageItem>): List<List<ChatMessageItem>> {
    val result = mutableListOf<MutableList<ChatMessageItem>>()
    messages.forEach { message ->
        val previousGroup = result.lastOrNull()
        val previous = previousGroup?.lastOrNull()
        val isVisualMedia = message.attachmentMimeType?.startsWith("image/") == true ||
            message.attachmentMimeType?.startsWith("video/") == true
        val groupable = !message.system && !message.attachmentName.isNullOrBlank() && isVisualMedia
        val joinsPrevious = groupable && previous != null &&
            !previous.attachmentName.isNullOrBlank() &&
            (previous.attachmentMimeType?.startsWith("image/") == true || previous.attachmentMimeType?.startsWith("video/") == true) &&
            previous.authorUsername.equals(message.authorUsername, ignoreCase = true) &&
            previous.channelId == message.channelId && sameDay(previous.createdAt, message.createdAt) &&
            message.createdAt - previous.createdAt in 0..60_000L
        if (joinsPrevious) previousGroup.add(message) else result += mutableListOf(message)
    }
    return result
}

/** WhatsApp's read-receipt blue — a fixed, high-contrast tick colour that reads on any accent bubble. */
private val ChatReadTickBlue = Color(0xFF53BDEB)

private fun isEmojiOnlyMessage(text: String): Boolean {
    val codePoints = text.codePoints().toArray()
    if (codePoints.isEmpty() || codePoints.size > 12) return false
    if (codePoints.any { Character.isLetterOrDigit(it) }) return false
    return codePoints.any { point ->
        point in 0x1F000..0x1FAFF || point in 0x2600..0x27BF || point in 0x2300..0x23FF
    }
}

private val YouTubePatterns = listOf(
    Regex("(?:youtu\\.be/)([A-Za-z0-9_-]{6,15})", RegexOption.IGNORE_CASE),
    Regex("(?:youtube(?:-nocookie)?\\.com/(?:watch\\?v=|shorts/|embed/))([A-Za-z0-9_-]{6,15})", RegexOption.IGNORE_CASE),
)

private fun youtubeVideoId(text: String): String? = YouTubePatterns.firstNotNullOfOrNull { pattern ->
    pattern.find(text)?.groupValues?.getOrNull(1)
}

private val UrlPattern = Regex("""https?://[^\s<>()]+""", RegexOption.IGNORE_CASE)

/** First http(s) link in a message, trimming trailing punctuation so "see https://x.com." resolves. */
private fun firstLinkPreviewUrl(text: String): String? =
    UrlPattern.find(text)?.value?.trimEnd('.', ',', ')', ']', '!', '?', ';', ':')?.takeIf { it.length > 10 }

/**
 * Inline OpenGraph link card for chat. Resolves title/description/favicon off the main thread via
 * the shared [EmbedMetadataResolver] (also used by the note editor), tapping opens the link in the
 * browser. Renders nothing until a title resolves, so a dead link leaves no empty box.
 */
@Composable
private fun ChatLinkPreviewCard(url: String, tint: Color) {
    val context = LocalContext.current
    val metadata by produceState(initialValue = com.norfold.app.domain.EmbedMetadata(), url) {
        value = runCatching { EmbedMetadataResolver.resolve(context, url, com.norfold.app.domain.EmbedMetadata()) }
            .getOrDefault(com.norfold.app.domain.EmbedMetadata())
    }
    val host = remember(url) { runCatching { java.net.URI(url).host?.removePrefix("www.") }.getOrNull().orEmpty() }
    if (metadata.title.isBlank() && metadata.description.isBlank()) return
    val fill = tint.copy(alpha = 0.08f)
    val focus = rememberChromeFocus()
    Surface(
        onClick = {
            runCatching {
                context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)))
            }
        },
        shape = RoundedCornerShape(14.dp),
        color = fill,
        interactionSource = focus.source,
        // 8% of the bubble tint is nearly transparent, so measuring the ring against the unpainted colour
        // would grade a pair that is never on screen — the substrate composites it first.
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp).then(focus.ringOnShape(14f, fill)),
    ) {
        Row(Modifier.padding(10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            val favicon = metadata.faviconPath?.let { java.io.File(it) }?.takeIf { it.isFile }
            if (favicon != null) {
                AsyncImage(
                    model = favicon,
                    contentDescription = null,
                    modifier = Modifier.size(38.dp).clip(RoundedCornerShape(9.dp)),
                    contentScale = ContentScale.Crop,
                )
            } else {
                Box(
                    Modifier.size(38.dp).clip(RoundedCornerShape(9.dp)).background(tint.copy(alpha = 0.14f)),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Outlined.Link, null, tint = tint.copy(alpha = 0.7f), modifier = Modifier.size(20.dp)) }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text(metadata.title.ifBlank { host }, color = tint, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (metadata.description.isNotBlank()) {
                    Text(metadata.description, color = tint.copy(alpha = 0.75f), fontSize = 11.sp, lineHeight = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                if (host.isNotBlank()) Text(host, color = tint.copy(alpha = 0.55f), fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun ChatOnlineVideoCard(videoId: String, onPlay: () -> Unit) {
    val focus = rememberChromeFocus()
    Surface(
        onClick = onPlay,
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 2.dp,
        interactionSource = focus.source,
        // The thumbnail fills the card edge to edge, so the ring lands on the image rather than on
        // `surface`. Measured on `surface` anyway: a thumbnail is arbitrary photography and cannot be
        // graded, and `surface` is what the card is declared to be.
        modifier = Modifier.fillMaxWidth().then(focus.ringOnShape(22f, MaterialTheme.colorScheme.surface)),
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f), contentAlignment = Alignment.Center) {
            AsyncImage(
                model = "https://i.ytimg.com/vi/$videoId/hqdefault.jpg",
                contentDescription = "Online video preview",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
            Icon(
                Icons.Outlined.PlayArrow,
                "Play online video",
                tint = Color.White,
                modifier = Modifier.size(54.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.58f)).padding(12.dp),
            )
        }
    }
}

@Suppress("SetJavaScriptEnabled")
@Composable
private fun ChatOnlineVideoViewer(videoId: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val webView = remember(videoId) {
        WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            webChromeClient = WebChromeClient()
            loadUrl("https://www.youtube-nocookie.com/embed/$videoId?autoplay=1&playsinline=1")
        }
    }
    DisposableEffect(webView) {
        onDispose {
            webView.stopLoading()
            webView.loadUrl("about:blank")
            webView.destroy()
        }
    }
    NorfoldFullscreenDialog(onDismissRequest = onDismiss) {
        Surface(Modifier.fillMaxSize(), color = Color.Black) {
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onDismiss) { Icon(Icons.Outlined.Close, "Close video", tint = Color.White) }
                    Text("Online video", color = Color.White, fontWeight = FontWeight.SemiBold)
                }
                AndroidView(factory = { webView }, modifier = Modifier.weight(1f).fillMaxWidth())
            }
        }
    }
}

private fun reactionCounts(json: String): List<Pair<String, Int>> = runCatching {
    val root = JSONObject(json)
    root.keys().asSequence().mapNotNull { emoji ->
        root.optJSONArray(emoji)?.length()?.takeIf { it > 0 }?.let { emoji to it }
    }.toList()
}.getOrDefault(emptyList())

/** Returns the set of emoji the given user has already reacted with on this message. */
private fun reactionsByUser(json: String, username: String): Set<String> = runCatching {
    val root = JSONObject(json)
    root.keys().asSequence().filter { emoji ->
        val arr = root.optJSONArray(emoji) ?: return@filter false
        (0 until arr.length()).any { arr.optString(it).equals(username, ignoreCase = true) }
    }.toSet()
}.getOrDefault(emptySet())

/** Parses the reactions map `{emoji: [usernames]}` into an ordered list for the who-reacted sheet. */
private fun reactionUsers(json: String): List<Pair<String, List<String>>> = runCatching {
    val root = JSONObject(json)
    root.keys().asSequence().mapNotNull { emoji ->
        root.optJSONArray(emoji)?.let { arr ->
            val users = (0 until arr.length()).map { arr.optString(it) }.filter(String::isNotBlank)
            users.takeIf { it.isNotEmpty() }?.let { emoji to it }
        }
    }.toList()
}.getOrDefault(emptyList())

/** Matches `@handle` runs (letters, digits, underscore, dot) for both parsing and highlighting. */
private val MentionRegex = Regex("@([A-Za-z0-9_.]+)")

/**
 * The in-progress @mention the cursor sits inside, or null. Only fires when the caret is at the
 * end of an unbroken `@word` run — so an already-typed mention earlier in the message doesn't
 * keep the suggestion list open.
 */
private fun mentionQuery(value: TextFieldValue): String? {
    val caret = value.selection.end
    if (caret == 0 || value.selection.start != caret) return null
    val upToCaret = value.text.substring(0, caret)
    val at = upToCaret.lastIndexOf('@')
    if (at < 0) return null
    // A mention starts at message start or right after whitespace, never mid-word (e.g. an email).
    if (at > 0 && !upToCaret[at - 1].isWhitespace()) return null
    val token = upToCaret.substring(at + 1)
    if (token.any { !(it.isLetterOrDigit() || it == '_' || it == '.') }) return null
    return token
}

/** Replaces the in-progress @token with the picked handle plus a trailing space. */
private fun applyMention(value: TextFieldValue, handle: String): TextFieldValue {
    val caret = value.selection.end
    val upToCaret = value.text.substring(0, caret)
    val at = upToCaret.lastIndexOf('@')
    if (at < 0) return value
    val prefix = value.text.substring(0, at)
    val suffix = value.text.substring(caret)
    val insert = "@$handle "
    val newText = prefix + insert + suffix
    val newCaret = prefix.length + insert.length
    return TextFieldValue(newText, TextRange(newCaret))
}

/** Bold-tints every `@handle` in a chat body so mentions read as links, not plain text. */
private fun highlightMentions(body: String, baseColor: Color): AnnotatedString = buildAnnotatedString {
    var last = 0
    for (match in MentionRegex.findAll(body)) {
        append(body.substring(last, match.range.first))
        withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) {
            append(match.value)
        }
        last = match.range.last + 1
    }
    if (last < body.length) append(body.substring(last))
}

/** @mention picker shown above the composer while the caret is inside an `@` token. */
@Composable
private fun MentionSuggestions(candidates: List<WorkspaceMember>, onPick: (WorkspaceMember) -> Unit) {
    Surface(
        Modifier.fillMaxWidth().padding(bottom = 6.dp),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        // Measured against its own `surface`, which the rows below already name for the same reason. This popover
        // floats above the composer over the message list, so its fill separates from the page by 1.0613:1 light
        // and 1.0664:1 dark and `outlineVariant` over it drew 1.1402:1 / 1.1648:1. Without a visible edge a
        // mention list overlapping the thread looks like the thread — the rows read as messages.
        //
        // `restingEdge` is given the *named* colour rather than the default so the tonal-tint gate applies: this
        // `Surface` declares 2 dp and paints `colorScheme.surface`, which is exactly the equality Material tests
        // before tinting, so the stroke has to measure the tinted pixel and not the raw token.
        border = androidx.compose.foundation.BorderStroke(
            ChromeGeometry.RESTING_EDGE_WIDTH.dp,
            with(NorfoldChrome) { restingEdge(MaterialTheme.colorScheme.surface) },
        ),
        tonalElevation = 2.dp,
    ) {
        Column(Modifier.heightIn(max = 196.dp).verticalScroll(rememberScrollState())) {
            candidates.forEach { member ->
                // The third flush picker row in this file, and the one whose backdrop is *not* published by a
                // dialog: this `Surface` paints `colorScheme.surface` at 2 dp and provides no
                // `LocalChromeContainer`, so the substrate's fallback would resolve the page instead. Naming the
                // colour is what puts the tonal-tint gate back in play — it fires on
                // `declared == colorScheme.surface`, which `background` is not in dark mode — and `Surface` already
                // hands its own accumulated 2 dp down as `LocalAbsoluteTonalElevation`, so only the colour has to
                // be stated here.
                Row(
                    Modifier
                        .fillMaxWidth()
                        .focusableChrome(
                            radiusDp = 0f,
                            surface = MaterialTheme.colorScheme.surface,
                            onClickLabel = "Mention ${member.displayName}",
                            onClick = { onPick(member) },
                        )
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Box(
                        Modifier.size(30.dp).clip(CircleShape).background(MaterialTheme.colorScheme.tertiaryContainer),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(member.displayName.take(1).uppercase(), fontWeight = FontWeight.Bold, fontSize = 13.sp, color = MaterialTheme.colorScheme.onTertiaryContainer)
                    }
                    Column(Modifier.weight(1f)) {
                        Text(member.displayName, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("@${member.username}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

/** Long-press actions for a chat message — compact spring-animated popup. */
@Composable
private fun MessageActionsSheet(
    message: ChatMessageItem,
    canUnsend: Boolean,
    canEdit: Boolean,
    canReport: Boolean,
    onReply: () -> Unit,
    onEdit: () -> Unit,
    onTogglePin: () -> Unit,
    onToggleStar: () -> Unit,
    onReact: (String) -> Unit,
    onForward: () -> Unit,
    onSelect: () -> Unit,
    onCopy: () -> Unit,
    onDeleteForMe: () -> Unit,
    onUnsend: () -> Unit,
    onReport: () -> Unit,
    onDismiss: () -> Unit,
) {
    // Spring-pop entrance: scale from 0.85 → 1.0 with a snappy spring.
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { visible = true }
    val scale by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (visible) 1f else 0.85f,
        animationSpec = androidx.compose.animation.core.spring(
            dampingRatio = androidx.compose.animation.core.Spring.DampingRatioMediumBouncy,
            stiffness = androidx.compose.animation.core.Spring.StiffnessMediumLow,
        ),
        label = "actionsScale",
    )
    val alpha by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = androidx.compose.animation.core.tween(120),
        label = "actionsAlpha",
    )
    NorfoldContentDialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 4.dp,
            modifier = Modifier.graphicsLayer(scaleX = scale, scaleY = scale, alpha = alpha),
        ) {
            Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                // Message preview
                val preview = message.body.ifBlank { message.attachmentName ?: "Message" }
                Text(
                    preview,
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                androidx.compose.material3.HorizontalDivider(Modifier.padding(horizontal = 16.dp), thickness = 0.5.dp)
                // Single scrollable emoji row
                Row(
                    Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    listOf("👍", "❤️", "😂", "😮", "😢", "🙏", "🔥", "🎉", "👏", "💯", "😍", "🤔").forEach { emoji ->
                        val emojiFocus = rememberChromeFocus()
                        Surface(
                            onClick = { onReact(emoji); onDismiss() },
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            interactionSource = emojiFocus.source,
                            modifier = Modifier.then(emojiFocus.ringOnShape(10f, MaterialTheme.colorScheme.surfaceVariant)),
                        ) { Text(emoji, Modifier.padding(horizontal = 8.dp, vertical = 6.dp), fontSize = 20.sp) }
                    }
                }
                androidx.compose.material3.HorizontalDivider(Modifier.padding(horizontal = 16.dp), thickness = 0.5.dp)
                // Action rows — compact vertical padding
                MessageActionRow(Icons.AutoMirrored.Outlined.Reply, "Reply", onClick = onReply)
                if (message.body.isNotBlank()) MessageActionRow(Icons.Outlined.ContentCopy, "Copy text", onClick = onCopy)
                MessageActionRow(Icons.AutoMirrored.Outlined.Send, "Forward", onClick = onForward)
                MessageActionRow(Icons.Outlined.Checklist, "Select", onClick = onSelect)
                if (canEdit) MessageActionRow(Icons.Outlined.Edit, "Edit message", onClick = onEdit)
                MessageActionRow(Icons.Outlined.PushPin, if (message.pinned) "Unpin" else "Pin message", onClick = onTogglePin)
                MessageActionRow(
                    if (message.starred) Icons.Outlined.Star else Icons.Outlined.StarBorder,
                    if (message.starred) "Remove from Starred" else "Save to Starred",
                    onClick = onToggleStar,
                )
                MessageActionRow(Icons.Outlined.Delete, "Delete for me", onClick = onDeleteForMe)
                if (canReport) MessageActionRow(Icons.Outlined.Flag, "Report message", destructive = true, onClick = onReport)
                if (canUnsend) MessageActionRow(Icons.AutoMirrored.Outlined.Undo, "Unsend for everyone", destructive = true, onClick = onUnsend)
            }
        }
    }
}

@Composable
private fun ReportMessageDialog(
    message: ChatMessageItem,
    onSubmit: (ChatBackend.AbuseReportCategory, String, Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    var category by remember(message.id) { mutableStateOf<ChatBackend.AbuseReportCategory?>(null) }
    var note by remember(message.id) { mutableStateOf("") }
    var alsoBlock by remember(message.id) { mutableStateOf(false) }
    NorfoldContentDialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 620.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("Report message", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text(
                    "Norfold will receive the message reference, sender, timing, category, and the note you type below. End-to-end encrypted message text and attachments are not uploaded.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp,
                )
                Text(
                    "From ${message.authorDisplayName.ifBlank { "@${message.authorUsername}" }}",
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 13.sp,
                )
                ChatBackend.AbuseReportCategory.entries.forEach { option ->
                    val optionFill = if (category == option) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surfaceVariant
                    val optionFocus = rememberChromeFocus()
                    Surface(
                        onClick = { category = option },
                        shape = RoundedCornerShape(14.dp),
                        color = optionFill,
                        interactionSource = optionFocus.source,
                        // These are radio options, so focus and selection are two different signals on the
                        // same control: the fill says which is chosen, the ring says which is focused, and
                        // the ring is measured on whichever fill is under it.
                        modifier = Modifier.then(optionFocus.ringOnShape(14f, optionFill)),
                    ) {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 11.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Icon(
                                if (category == option) Icons.Outlined.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
                                null,
                                modifier = Modifier.size(19.dp),
                            )
                            Text(option.label, fontSize = 14.sp)
                        }
                    }
                }
                OutlinedTextField(
                    value = note,
                    onValueChange = { if (it.length <= 1_000) note = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Optional note to the safety team") },
                    supportingText = { Text("${note.length}/1000 · Only add details you choose to share") },
                    minLines = 3,
                    maxLines = 6,
                    shape = RoundedCornerShape(16.dp),
                )
                AlsoBlockRow(alsoBlock) { alsoBlock = it }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End)) {
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    Button(
                        enabled = category != null,
                        onClick = { category?.let { onSubmit(it, note, alsoBlock) } },
                    ) { Text(if (alsoBlock) "Report and block" else "Send report") }
                }
            }
        }
    }
}

/**
 * One action in the message long-press menu.
 *
 * Routed through [ChoiceRow] rather than carrying its own chain, because it is that population exactly: a
 * full-width band inside a container, one `onClick` over the whole width, interior padding, ten instances. It
 * had no `clip` at all, so a ring drawn on it would have had no radius to be concentric with — taking the
 * shared row supplies the clip and the ring together, and at the tier the rest of the app's rows use.
 *
 * The 20 dp horizontal padding is kept as content padding rather than moved to the card: it is wider than the
 * card's own inset, and moving it would shift all ten labels.
 *
 * **This row is why [NorfoldContentDialog] now provides `LocalChromeContainer`.** These ten sit on a `Surface`
 * at 4 dp of tonal elevation, which on this app is a wash of the user's accent, and the dialog hosting them
 * announced no container — so the ring was solved against the page *behind* the dialog and drawn on the tinted
 * card. At 4 dp that put **2 of the 10 shipped accents under WCAG 1.4.11's 3:1, floor 2.8816:1 (Sunset)**.
 * Passing an explicit backdrop here would have fixed these ten rows and left the dialog's other callers wrong,
 * so the fix is at the dialog. Nothing about that is visible at this call site, which is the point: the row
 * states its geometry and the host states its colour.
 *
 * **[destructive] does not change the ring.** The two destructive actions — reporting and unsending — tint
 * their icon and label with `error`, and the temptation is to tint the ring to match. That would be a mistake
 * of category: the label says what the action costs, and the ring says only where focus is. A user tabbing
 * through ten rows needs the *same* mark in all ten places, or the mark stops meaning "you are here" and
 * starts meaning something they have to interpret. `error` is also the one token whose contrast against this
 * card is not solved by the substrate, so a ring built from it would be the only unmeasured ring in the app.
 */
/**
 * The report dialog's "block this account too" line, as one accessibility node.
 *
 * The row was `clickable` and the [Checkbox] carried its own handler, which wrote `alsoBlock` from two places and
 * produced two semantics nodes for one decision: the row named the option and its explanation without stating
 * whether it was on, the checkbox stated it without naming it. Compose does not fold the second into the first —
 * its merge walk stops descending at any child that merges, and every Material selection control does — so a
 * screen reader met an unnamed checkbox immediately before the Report button.
 * [com.norfold.app.ui.toggleableChrome] with `Role.Checkbox` makes it a single node carrying the label, the role
 * and the state, and the indicator becomes decorative.
 * [com.norfold.app.ui.components.SettingsToggleRow] documents the mechanism and Material's null-handler gate.
 *
 * That modifier's 48 dp floor replaces the touch target the gate removes along with the interaction. It is
 * defensive here rather than load-bearing: two lines of text already measure past 48 dp. It matters because this
 * decision is consequential and adjacent to a submit button, so the target must not narrow if the subtitle is
 * ever shortened to one line.
 *
 * The ring is inward at [Corner.Media] against the container [NorfoldContentDialog] provides —
 * the same reason [MessageActionRow] gives for stating no colour of its own.
 */
@Composable
private fun AlsoBlockRow(checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .toggleableChrome(
                checked = checked,
                corner = Corner.Media,
                role = androidx.compose.ui.semantics.Role.Checkbox,
                onValueChange = onChange,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = null)
        Column(Modifier.weight(1f)) {
            Text("Block this account too", fontWeight = FontWeight.SemiBold)
            Text(
                "Stops direct contact. Shared team messages stay visible.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
            )
        }
    }
}

@Composable
private fun MessageActionRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    destructive: Boolean = false,
    onClick: () -> Unit,
) {
    val tint = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
    ChoiceRow(
        onClick = onClick,
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(icon, label, tint = tint, modifier = Modifier.size(20.dp))
        Text(label, color = tint, fontSize = 15.sp)
    }
}

/** Who-reacted sheet: lists each reaction emoji with the people who added it. */
@Composable
private fun WhoReactedSheet(reactions: List<Pair<String, List<String>>>, onDismiss: () -> Unit) {
    NorfoldContentDialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surface, tonalElevation = 3.dp) {
            Column(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
                Text(
                    "Reactions",
                    Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                )
                if (reactions.isEmpty()) {
                    Text("No reactions yet", Modifier.padding(horizontal = 20.dp, vertical = 10.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                }
                reactions.forEach { (emoji, users) ->
                    users.forEach { user ->
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                        ) {
                            Box(
                                Modifier.size(34.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant),
                                contentAlignment = Alignment.Center,
                            ) { Text(user.take(1).uppercase(), fontWeight = FontWeight.Bold, fontSize = 13.sp) }
                            Text(user, Modifier.weight(1f), fontSize = 15.sp)
                            Text(emoji, fontSize = 18.sp)
                        }
                    }
                }
                TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End).padding(horizontal = 12.dp)) {
                    Text("Close")
                }
            }
        }
    }
}

/** Forward picker: choose a channel/DM to forward the selected message into. */
@Composable
private fun ForwardTargetSheet(
    channels: List<com.norfold.app.domain.ChatChannel>,
    onForward: (com.norfold.app.domain.ChatChannel) -> Unit,
    onDismiss: () -> Unit,
) {
    NorfoldContentDialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surface, tonalElevation = 3.dp) {
            Column(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
                Text(
                    "Forward to",
                    Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                )
                if (channels.isEmpty()) {
                    Text("No other conversations yet", Modifier.padding(horizontal = 20.dp, vertical = 10.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                }
                channels.forEach { channel ->
                    // Same flush picker row as `NotePickerSheet`, same host, same reasoning — see there. The
                    // label names the destination because "Forward" alone, repeated down a list of six, does not
                    // say *where*, and the channel name arrives separately in the merged content.
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .focusableChrome(
                                radiusDp = 0f,
                                onClickLabel = "Forward to ${channel.name}",
                                onClick = { onForward(channel) },
                            )
                            .padding(horizontal = 20.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        Box(
                            Modifier.size(38.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
                            contentAlignment = Alignment.Center,
                        ) {
                            val glyph = when (channel.kind) {
                                ChatChannelKind.Dm -> channel.name.take(1).uppercase()
                                ChatChannelKind.Group -> "#"
                                ChatChannelKind.Topic -> "#"
                            }
                            Text(glyph, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
                        }
                        Column(Modifier.weight(1f)) {
                            Text(channel.name, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                when (channel.kind) {
                                    ChatChannelKind.Dm -> "Direct message"
                                    ChatChannelKind.Group -> "Group"
                                    ChatChannelKind.Topic -> "Topic"
                                },
                                fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Icon(Icons.AutoMirrored.Outlined.Send, "Forward here", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                    }
                }
                TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End).padding(horizontal = 12.dp)) {
                    Text("Cancel")
                }
            }
        }
    }
}

/**
 * Telegram-style mosaic album: 2–4 visible thumbnails tiled in mixed rows (not a horizontal
 * pager). A "+N" scrim on the last tile when the batch exceeds four. Tapping any tile opens it.
 */
@Composable
private fun ChatAttachmentMosaic(
    attachments: List<ChatMessageItem>,
    onOpenAttachment: (ChatMessageItem) -> Unit,
    onResolveAttachment: suspend (ChatMessageItem) -> String?,
) {
    val visible = attachments.take(4)
    val overflow = attachments.size - visible.size
    val gap = 3.dp
    Column(Modifier.width(286.dp).clip(RoundedCornerShape(16.dp)), verticalArrangement = Arrangement.spacedBy(gap)) {
        // Row layout: 1→full, 2→side-by-side, 3→one big + two stacked, 4→2×2.
        when (visible.size) {
            1 -> MosaicTile(visible[0], Modifier.fillMaxWidth().height(200.dp), 0, onOpenAttachment, onResolveAttachment)
            2 -> Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                MosaicTile(visible[0], Modifier.weight(1f).height(160.dp), 0, onOpenAttachment, onResolveAttachment)
                MosaicTile(visible[1], Modifier.weight(1f).height(160.dp), 0, onOpenAttachment, onResolveAttachment)
            }
            3 -> Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                MosaicTile(visible[0], Modifier.weight(1.5f).height(200.dp), 0, onOpenAttachment, onResolveAttachment)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(gap)) {
                    MosaicTile(visible[1], Modifier.fillMaxWidth().height(98.5.dp), 0, onOpenAttachment, onResolveAttachment)
                    MosaicTile(visible[2], Modifier.fillMaxWidth().height(98.5.dp), 0, onOpenAttachment, onResolveAttachment)
                }
            }
            else -> {
                Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                    MosaicTile(visible[0], Modifier.weight(1f).height(120.dp), 0, onOpenAttachment, onResolveAttachment)
                    MosaicTile(visible[1], Modifier.weight(1f).height(120.dp), 0, onOpenAttachment, onResolveAttachment)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                    MosaicTile(visible[2], Modifier.weight(1f).height(120.dp), 0, onOpenAttachment, onResolveAttachment)
                    MosaicTile(visible[3], Modifier.weight(1f).height(120.dp), overflow, onOpenAttachment, onResolveAttachment)
                }
            }
        }
    }
}

/**
 * One thumbnail in the mosaic.
 *
 * ## Focus
 *
 * The photograph fills the tile edge to edge, so the ring is drawn on arbitrary imagery. Measured against the
 * tile's declared fill anyway — `Color.Black` for a video frame, `surfaceVariant` for a still — for the reason
 * [ChatOnlineVideoCard] states: a thumbnail cannot be graded, and the fill is what the tile is declared to be.
 * A video tile is the honest case of the two, since a dark frame under a ring solved against black stays
 * legible; a light photograph on the `surfaceVariant` tile is where the claim is weakest, and only a device
 * pass over real attachments can settle it.
 *
 * @param overflow how many attachments this batch hides beyond the four shown, drawn as a "+N" scrim. Only
 *   the last tile ever passes a non-zero value.
 */
@Composable
private fun MosaicTile(
    chat: ChatMessageItem,
    modifier: Modifier,
    overflow: Int,
    onOpenAttachment: (ChatMessageItem) -> Unit,
    onResolveAttachment: suspend (ChatMessageItem) -> String?,
) {
    val source by produceState<String?>(initialValue = null, chat.attachmentUri) { value = onResolveAttachment(chat) }
    val isVideo = chat.attachmentMimeType?.startsWith("video/") == true
    val fill = if (isVideo) Color.Black else MaterialTheme.colorScheme.surfaceVariant
    val focus = rememberChromeFocus()
    Box(
        modifier.clip(RoundedCornerShape(4.dp))
            .background(fill)
            .clickable(
                interactionSource = focus.source,
                indication = androidx.compose.material3.ripple(),
                // The tile is a picture with no visible label, so what activation *does* was never stated.
                onClickLabel = if (isVideo) "Play attached video" else "Open attached image",
                role = androidx.compose.ui.semantics.Role.Button,
            ) { onOpenAttachment(chat) }
            .then(focus.ringOnShape(4f, fill)),
        contentAlignment = Alignment.Center,
    ) {
        AsyncImage(
            model = ImageRequest.Builder(LocalContext.current)
                .data(source)
                .apply { if (isVideo) videoFrameMillis(750) }
                .memoryCacheKey("chat-${chat.id}")
                .diskCacheKey("chat-${chat.id}")
                .crossfade(true)
                .build(),
            contentDescription = chat.attachmentName,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
        )
        if (isVideo) {
            Icon(Icons.Outlined.PlayArrow, "Play video", tint = Color.White,
                modifier = Modifier.size(38.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.5f)).padding(8.dp))
        }
        if (overflow > 0) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.5f)), contentAlignment = Alignment.Center) {
                Text("+$overflow", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun ChatAttachmentCard(
    chat: ChatMessageItem,
    mine: Boolean,
    audio: ChatAudioState?,
    onToggleAudio: (ChatMessageItem) -> Unit,
    onCycleAudioSpeed: (ChatMessageItem) -> Unit,
    onOpenAttachment: (ChatMessageItem) -> Unit,
    onResolveAttachment: suspend (ChatMessageItem) -> String?,
) {
    val fg = if (mine) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
    val bg = if (mine) MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.13f) else MaterialTheme.colorScheme.surface
    if (chat.attachmentMimeType?.startsWith("audio/") == true) {
        // Inline voice-message player; also covers picked audio files.
        val active = audio?.takeIf { it.messageId == chat.id }
        Surface(shape = RoundedCornerShape(20.dp), color = bg) {
            Row(
                Modifier.padding(horizontal = 8.dp, vertical = 6.dp).widthIn(min = 172.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                IconButton(
                    onClick = { onToggleAudio(chat) },
                    modifier = Modifier.size(34.dp).clip(CircleShape).background(fg.copy(alpha = 0.14f)),
                ) {
                    Icon(
                        if (active?.playing == true) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                        if (active?.playing == true) "Pause voice message" else "Play voice message",
                        tint = fg,
                        modifier = Modifier.size(20.dp),
                    )
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    VoicePlaybackWaveform(chat.id, active, fg)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            if (active != null) "${formatVoiceTime(active.positionMs.toLong())} / ${formatVoiceTime(active.durationMs.toLong())}"
                            else chat.attachmentName.takeUnless { it == "Voice message" } ?: "Voice message",
                            color = fg.copy(alpha = 0.75f),
                            fontSize = 10.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        if (active != null) {
                            // The last hand-rolled clickable in this file, and the smallest: two glyphs of 10 sp
                            // text, near 18 dp tall with its padding.
                            //
                            // It gets the ring and **not** a touch-target floor, which is the opposite of what the
                            // inspector's rows needed. `minimumInteractiveComponentSize` is the idiom for a small
                            // control here, but its node measures `max(placeable.height, minSize)` — it grows the
                            // *layout* box, and this chip sits in a `Row` under the waveform inside every voice
                            // bubble, so a 48 dp floor would add 30 dp to each one. That is a layout decision about
                            // the player, not a focus fix, and it belongs with the rest of the visual pass.
                            //
                            // Nothing is lost on touch in the meantime: `NodeCoordinator.hit` falls through to
                            // `distanceInMinimumTouchTarget` for a `PointerType.Touch` miss and records the hit
                            // anyway when no closer target claims it, using the platform's own
                            // `ViewConfiguration.minimumTouchTargetSize`. The thumb already reaches this; only the
                            // layout box is small.
                            //
                            // 99f is the pill radius that was already here. Measured against `bg`, which for
                            // `mine` is 13% `onPrimary` over the bubble — composited page-upward for the same
                            // reason the quote strip above is.
                            Text(
                                "${active.speed}×",
                                color = fg,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier
                                    .focusableChrome(
                                        radiusDp = 99f,
                                        surface = EditorChromeColors.opaque(bg, MaterialTheme.colorScheme.background),
                                        onClickLabel = "Change playback speed",
                                        onClick = { onCycleAudioSpeed(chat) },
                                    )
                                    .padding(horizontal = 6.dp, vertical = 3.dp),
                            )
                        }
                    }
                }
            }
        }
    } else {
        val source by produceState<String?>(initialValue = null, chat.attachmentUri) {
            value = onResolveAttachment(chat)
        }
        val isImage = chat.attachmentMimeType?.startsWith("image/") == true
        val isVideo = chat.attachmentMimeType?.startsWith("video/") == true
        val visualMedia = isImage || isVideo
        // "Encasing" frame for media: a subtle tinted card with a thin hairline border and a small
        // inset, so photos/videos read as framed cards (WhatsApp/Telegram) instead of raw bitmaps
        // floating on the wallpaper. Non-visual files keep their solid tonal chip.
        val frameColor = if (mine) MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.10f)
            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.9f)
        // Two different populations wearing one name, which is why only one half is solved.
        //
        // `mine` keeps `onPrimary` at 16%: this frame is drawn *inside* an accent bubble, so its edge is a tint of
        // the bubble's own foreground — reading it as a neutral boundary would put a grey line inside an accent
        // card and break the one thing outgoing media has going for it, that it belongs to the accent.
        //
        // `!mine` is solved. Its fill is `surfaceVariant` at 90%, and an attachment-only message is *chromeless* —
        // `bubbleColor` is `Color.Transparent` — so the frame sits straight on the page at 1.0448:1 light and
        // 1.1278:1 dark, with `outlineVariant` over it drawing 1.0283:1 and 1.1014:1. That is the case this frame
        // exists for: the comment above says photos should "read as framed cards instead of raw bitmaps floating
        // on the wallpaper", and at 1.0283:1 the frame was not doing it.
        val frameBorder = if (mine) MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.16f)
            else with(NorfoldChrome) { restingEdge(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.9f)) }
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = if (visualMedia) frameColor else bg,
            border = if (visualMedia) {
                androidx.compose.foundation.BorderStroke(ChromeGeometry.RESTING_EDGE_WIDTH.dp, frameBorder)
            } else {
                null
            },
            modifier = Modifier.focusableChrome(
                radiusDp = 20f,
                surface = if (visualMedia) frameColor else bg,
            ) { onOpenAttachment(chat) },
        ) {
            Column(Modifier.widthIn(min = 148.dp, max = 286.dp).padding(if (visualMedia) 3.dp else 0.dp)) {
                if ((isImage || isVideo) && source != null) {
                    Box(
                        Modifier.fillMaxWidth().heightIn(min = 112.dp, max = 260.dp).clip(RoundedCornerShape(16.dp))
                            .background(if (isVideo) Color.Black else MaterialTheme.colorScheme.surfaceVariant),
                        contentAlignment = Alignment.Center,
                    ) {
                        AsyncImage(
                            model = ImageRequest.Builder(LocalContext.current)
                                .data(source)
                                .apply { if (isVideo) videoFrameMillis(750) }
                                .memoryCacheKey("chat-${chat.id}")
                                .diskCacheKey("chat-${chat.id}")
                                .crossfade(!chat.attachmentMimeType.equals("image/gif", ignoreCase = true))
                                .build(),
                            contentDescription = chat.attachmentName,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 112.dp, max = 260.dp),
                            contentScale = ContentScale.Fit,
                        )
                        if (isVideo) {
                            Icon(
                                Icons.Outlined.PlayArrow,
                                "Play video",
                                tint = Color.White,
                                modifier = Modifier.size(48.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.55f)).padding(10.dp),
                            )
                        }
                    }
                }
                if (!visualMedia) {
                    // Telegram-style document card: rounded file-type badge + name + "SIZE · EXT".
                    val ext = chat.attachmentName.orEmpty().substringAfterLast('.', "").uppercase().take(4)
                    Row(Modifier.padding(horizontal = 12.dp, vertical = 11.dp).widthIn(min = 180.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(11.dp)) {
                        Box(
                            Modifier.size(42.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.primary),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (ext.isNotBlank()) {
                                Text(ext, color = MaterialTheme.colorScheme.onPrimary, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            } else {
                                Icon(Icons.Outlined.AttachFile, null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(20.dp))
                            }
                        }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                chat.attachmentName.orEmpty().substringBeforeLast('.').ifBlank { "File" },
                                color = fg,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                listOfNotNull(chat.attachmentSizeBytes?.let { formatBytes(it) }, ext.ifBlank { null }).joinToString(" · "),
                                color = fg.copy(alpha = 0.65f),
                                fontSize = 11.sp,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun VoicePlaybackWaveform(messageId: Long, active: ChatAudioState?, color: Color) {
    val progress = active?.let { if (it.durationMs > 0) it.positionMs.toFloat() / it.durationMs else 0f } ?: 0f
    val bars = remember(messageId) {
        // Stable pseudo-wave for recorded audio when no decoded peak sidecar exists. Unlike the
        // recording waveform it represents seek progress, not live microphone input.
        List(32) { index -> 0.2f + kotlin.math.abs(kotlin.math.sin((messageId % 31 + index) * 1.73)).toFloat() * 0.8f }
    }
    Row(Modifier.fillMaxWidth().height(22.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        bars.forEachIndexed { index, peak ->
            Box(
                Modifier.weight(1f).height((4f + 17f * peak).dp).clip(RoundedCornerShape(99.dp))
                    .background(if (index / bars.size.toFloat() <= progress) color else color.copy(alpha = 0.24f)),
            )
        }
    }
}

@Composable
private fun ChatVideoPlayer(source: String?, onSave: () -> Unit) {
    val context = LocalContext.current
    var playbackFailed by remember(source) { mutableStateOf(false) }
    val player = remember(source) {
        source?.let {
            ExoPlayer.Builder(context).build().apply {
                setMediaItem(MediaItem.fromUri(it))
                playWhenReady = true
                prepare()
            }
        }
    }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                playbackFailed = true
            }
        }
        player?.addListener(listener)
        onDispose {
            player?.removeListener(listener)
            player?.release()
        }
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        if (player != null && !playbackFailed) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { videoContext ->
                    PlayerView(videoContext).apply {
                        useController = true
                        this.player = player
                    }
                },
                update = { it.player = player },
            )
        } else if (playbackFailed) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.padding(24.dp),
            ) {
                Icon(Icons.Outlined.VideoFile, null, modifier = Modifier.size(64.dp), tint = MaterialTheme.colorScheme.primary)
                Text(
                    "This video's codec is not supported by the device player. Save it to open it in another installed video app.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                FilledTonalButton(onClick = onSave) { Text("Save video") }
            }
        }
    }
}

@Composable
private fun ChatAttachmentViewer(
    preview: ChatAttachmentPreviewState,
    onDismiss: () -> Unit,
    onSave: () -> Unit,
    onRequestPdfPage: (Int) -> Unit,
) {
    val mime = preview.message.attachmentMimeType.orEmpty()
    val context = LocalContext.current
    val providerOwnedGif = mime == "image/gif" && preview.source?.contains("klipy", ignoreCase = true) == true
    NorfoldFullscreenDialog(onDismissRequest = onDismiss) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    IconButton(onClick = onDismiss) { Icon(Icons.Outlined.Close, "Close preview") }
                    Text(
                        when {
                            mime.equals("image/gif", ignoreCase = true) -> "GIF"
                            mime.startsWith("image/") -> "Photo"
                            mime.startsWith("video/") -> "Video"
                            mime == "application/pdf" -> "Document"
                            else -> preview.message.attachmentName.orEmpty().substringBeforeLast('.').ifBlank { "File" }
                        },
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        fontWeight = FontWeight.SemiBold,
                    )
                    if (mime.startsWith("video/") && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        TextButton(onClick = {
                            context.findActivity()?.enterPictureInPictureMode(
                                PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9)).build(),
                            )
                        }) { Text("Float") }
                    }
                    TextButton(
                        onClick = onSave,
                        enabled = preview.source != null && preview.downloadProgress == null && !providerOwnedGif,
                    ) { Text(if (providerOwnedGif) "KLIPY" else "Save") }
                }
                Box(Modifier.weight(1f).fillMaxWidth().padding(8.dp), contentAlignment = Alignment.Center) {
                    when {
                        preview.loading -> CircularProgressIndicator()
                        preview.error != null && preview.source == null -> Text(preview.error, color = MaterialTheme.colorScheme.error)
                        mime.startsWith("image/") -> AsyncImage(
                            model = ImageRequest.Builder(context)
                                .data(preview.source)
                                .crossfade(!mime.equals("image/gif", ignoreCase = true))
                                .build(),
                            contentDescription = preview.message.attachmentName,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Fit,
                        )
                        mime.startsWith("video/") -> ChatVideoPlayer(preview.source, onSave)
                        mime == "application/pdf" && preview.pdfPageCount > 0 -> LazyColumn(
                            Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(bottom = 24.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            items(preview.pdfPageCount, key = { it }) { pageIndex ->
                                LaunchedEffect(pageIndex, preview.pdfPages.containsKey(pageIndex)) {
                                    if (pageIndex !in preview.pdfPages) onRequestPdfPage(pageIndex)
                                }
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = Color.White,
                                    shadowElevation = 2.dp,
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    val page = preview.pdfPages[pageIndex]
                                    if (page == null) {
                                        Box(Modifier.fillMaxWidth().aspectRatio(0.72f), contentAlignment = Alignment.Center) {
                                            CircularProgressIndicator()
                                        }
                                    } else {
                                        Image(
                                            bitmap = page.asImageBitmap(),
                                            contentDescription = "Page ${pageIndex + 1} of ${preview.message.attachmentName}",
                                            modifier = Modifier.fillMaxWidth(),
                                            contentScale = ContentScale.FillWidth,
                                        )
                                    }
                                }
                            }
                        }
                        else -> Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Icon(Icons.Outlined.AttachFile, null, modifier = Modifier.size(64.dp), tint = MaterialTheme.colorScheme.primary)
                            Text(preview.error ?: "Preview isn't available for this file type. Save it to open with another app.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                preview.downloadProgress?.let { progress ->
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                        Text("Saving ${(progress * 100).roundToInt()}%", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
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

private fun sameDay(a: Long, b: Long): Boolean {
    val calA = java.util.Calendar.getInstance().apply { timeInMillis = a }
    val calB = java.util.Calendar.getInstance().apply { timeInMillis = b }
    return calA.get(java.util.Calendar.YEAR) == calB.get(java.util.Calendar.YEAR) &&
        calA.get(java.util.Calendar.DAY_OF_YEAR) == calB.get(java.util.Calendar.DAY_OF_YEAR)
}

/** Date-separator chip inside a conversation: Today, Yesterday, else a readable date. */
private fun dayChipLabel(timestamp: Long): String {
    val now = System.currentTimeMillis()
    return when {
        sameDay(timestamp, now) -> "Today"
        sameDay(timestamp, now - 24L * 60 * 60 * 1000) -> "Yesterday"
        else -> java.text.SimpleDateFormat("EEE, d MMM", java.util.Locale.getDefault()).format(java.util.Date(timestamp))
    }
}
