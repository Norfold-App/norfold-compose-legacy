package com.norfold.app.cloud

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.RemoteInput
import com.norfold.app.data.ChatReadMarkers
import com.norfold.app.data.DocsRepository
import com.norfold.app.data.NorfoldDatabase
import com.norfold.app.data.NorfoldWorkScheduler
import com.norfold.app.domain.ChatReceiptStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.UUID

/** Handles notification actions without opening the app or exposing cloud attachment URLs. */
class NorfoldNotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                when (intent.action) {
                    ActionMarkRead -> markRead(context, intent)
                    ActionReply -> reply(context, intent)
                }
            } finally {
                pendingResult.finish()
            }
        }
    }

    private suspend fun markRead(context: Context, intent: Intent) {
        val repository = DocsRepository(NorfoldDatabase.get(context))
        val remoteWorkspaceId = intent.getStringExtra(ExtraWorkspaceId).orEmpty()
        val localWorkspaceId = repository.localWorkspaceIdForRemote(remoteWorkspaceId) ?: return
        val remoteChannelId = intent.getStringExtra(ExtraChannelId).orEmpty().ifBlank { null }
        val localChannelId = remoteChannelId?.let { repository.localChatChannelIdBySyncId(localWorkspaceId, it) }
        ChatReadMarkers.markRead(context, localWorkspaceId, ChatReadMarkers.channelKey(localChannelId))
        notificationManager(context).cancel(intent.getIntExtra(ExtraNotificationId, 0))
        NorfoldWorkScheduler.enqueueReconciliation(context)
    }

    private suspend fun reply(context: Context, intent: Intent) {
        val body = RemoteInput.getResultsFromIntent(intent)
            ?.getCharSequence(RemoteInputReply)
            ?.toString()
            ?.trim()
            ?.takeIf(String::isNotBlank)
            ?: return
        val repository = DocsRepository(NorfoldDatabase.get(context))
        val settings = repository.settingsValue()
        val remoteWorkspaceId = intent.getStringExtra(ExtraWorkspaceId).orEmpty()
        val localWorkspaceId = repository.localWorkspaceIdForRemote(remoteWorkspaceId) ?: return
        val remoteChannelId = intent.getStringExtra(ExtraChannelId).orEmpty().ifBlank { null }
        val localChannelId = remoteChannelId?.let { repository.localChatChannelIdBySyncId(localWorkspaceId, it) }
        val syncId = UUID.randomUUID().toString()
        val createdAt = System.currentTimeMillis()
        val localId = if (remoteChannelId == null || localChannelId != null) {
            repository.queueNotificationChatReply(
                workspaceId = localWorkspaceId,
                channelId = localChannelId,
                username = settings.syncUserName.ifBlank { "you" },
                displayName = settings.syncPublicName.ifBlank { settings.syncUserName.ifBlank { "You" } },
                body = body,
                color = settings.accentColor,
                createdAt = createdAt,
                syncId = syncId,
            )
        } else {
            null
        }

        val sent = runCatching {
            NorfoldSupabase.sendTeamChatMessage(
                remoteWorkspaceId = remoteWorkspaceId,
                deviceId = settings.settingsDeviceId,
                username = settings.syncUserName.ifBlank { "you" },
                displayName = settings.syncPublicName.ifBlank { settings.syncUserName.ifBlank { "You" } },
                body = body,
                color = settings.accentColor,
                createdAt = createdAt,
                syncId = syncId,
                idempotencyKey = syncId,
                channelSyncId = remoteChannelId,
            )
        }.getOrNull()
        if (sent != null) {
            localId?.let {
                repository.bindRemoteChatMessage(localWorkspaceId, it, sent)
                repository.setChatMessageReceiptStatus(it, ChatReceiptStatus.Sent)
            }
            notificationManager(context).cancel(intent.getIntExtra(ExtraNotificationId, 0))
        }
        // On failure, a locally queued reply remains pending and this worker retries it. If the
        // channel had not reached this device yet, reconciliation imports it before a later retry.
        NorfoldWorkScheduler.enqueueReconciliation(context, replace = sent == null)
    }

    private fun notificationManager(context: Context): NotificationManager =
        context.getSystemService(NotificationManager::class.java)

    companion object {
        const val ActionMarkRead = "com.norfold.app.action.MARK_CHAT_READ"
        const val ActionReply = "com.norfold.app.action.REPLY_CHAT"
        const val RemoteInputReply = "norfold.reply"
        const val ExtraNotificationId = "norfold.notificationId"
        const val ExtraWorkspaceId = "norfold.remoteWorkspaceId"
        const val ExtraChannelId = "norfold.remoteChannelId"
    }
}
