package com.norfold.app.cloud

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import androidx.core.content.ContextCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.norfold.app.MainActivity
import com.norfold.app.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import com.norfold.app.data.NorfoldWorkScheduler

class NorfoldMessagingService : FirebaseMessagingService() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onNewToken(token: String) {
        if (!ExternalServiceConfig.capabilities.pushNotifications) return
        serviceScope.launch { runCatching { PushRegistrationClient(this@NorfoldMessagingService).registerToken(token, enabled = true) } }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val title = message.notification?.title ?: message.data["title"] ?: "Norfold"
        val body = message.notification?.body ?: message.data["body"] ?: return
        showNotification(title, body, message.data)
        NorfoldWorkScheduler.enqueueReconciliation(this)
    }

    override fun onDeletedMessages() {
        NorfoldWorkScheduler.enqueueReconciliation(this, replace = true)
    }

    private fun showNotification(title: String, body: String, data: Map<String, String>) {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val manager = getSystemService(NotificationManager::class.java)
        val channelId = channelFor(data["kind"])
        manager.createNotificationChannel(NotificationChannel(channelId, channelName(channelId), NotificationManager.IMPORTANCE_DEFAULT))
        val destination = data["destination"] ?: when {
            data["channelId"] != null || data["messageId"] != null -> "Chat"
            data["taskId"] != null -> "Tasks"
            data["objectId"] != null -> "ObjectDetail"
            else -> "WorkspaceHub"
        }
        val notificationId = (data["eventId"] ?: "$title:$body").hashCode()
        val pendingIntent = PendingIntent.getActivity(
            this,
            notificationId,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra("norfold.destination", destination)
                .putExtra("norfold.workspaceId", data["workspaceId"])
                .putExtra("norfold.objectId", data["objectId"])
                .putExtra("norfold.channelId", data["channelId"])
                .putExtra("norfold.messageId", data["messageId"]),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = NotificationCompat.Builder(this, channelId)
                .setSmallIcon(R.drawable.ic_norfold_notification)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
        if (data["kind"]?.startsWith("chat") == true) {
            val actionExtras = { action: String ->
                Intent(this, NorfoldNotificationActionReceiver::class.java)
                    .setAction(action)
                    .putExtra(NorfoldNotificationActionReceiver.ExtraNotificationId, notificationId)
                    .putExtra(NorfoldNotificationActionReceiver.ExtraWorkspaceId, data["workspaceId"])
                    .putExtra(NorfoldNotificationActionReceiver.ExtraChannelId, data["channelId"])
            }
            val markReadIntent = PendingIntent.getBroadcast(
                this,
                notificationId xor 0x51EA,
                actionExtras(NorfoldNotificationActionReceiver.ActionMarkRead),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val replyIntent = PendingIntent.getBroadcast(
                this,
                notificationId xor 0x7E91,
                actionExtras(NorfoldNotificationActionReceiver.ActionReply),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            )
            val remoteInput = RemoteInput.Builder(NorfoldNotificationActionReceiver.RemoteInputReply)
                .setLabel("Reply")
                .build()
            builder
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                .addAction(R.drawable.ic_norfold_notification, "Mark as read", markReadIntent)
                .addAction(
                    NotificationCompat.Action.Builder(R.drawable.ic_norfold_notification, "Reply", replyIntent)
                        .addRemoteInput(remoteInput)
                        .setAllowGeneratedReplies(true)
                        .build(),
                )
        }
        manager.notify(notificationId, builder.build())
    }

    private fun channelFor(kind: String?): String = when {
        kind?.startsWith("chat") == true -> ChatChannelId
        kind?.startsWith("task") == true -> TaskChannelId
        kind == "backup" || kind == "sync" -> SyncChannelId
        else -> WorkspaceChannelId
    }

    private fun channelName(channelId: String): String = when (channelId) {
        ChatChannelId -> "Messages"
        TaskChannelId -> "Tasks and reminders"
        SyncChannelId -> "Backup and sync"
        else -> "Workspace activity"
    }

    private companion object {
        const val WorkspaceChannelId = "norfold_workspace"
        const val ChatChannelId = "norfold_chat"
        const val TaskChannelId = "norfold_tasks"
        const val SyncChannelId = "norfold_sync"
    }
}
