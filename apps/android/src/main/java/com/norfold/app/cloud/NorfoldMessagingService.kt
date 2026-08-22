package com.norfold.app.cloud

import android.Manifest
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
import kotlinx.coroutines.runBlocking
import com.norfold.app.data.ChatConversationPrefs
import com.norfold.app.data.NorfoldDatabase
import com.norfold.app.data.NorfoldWorkScheduler
import com.norfold.app.data.ChatForegroundState
import com.norfold.app.domain.PushDelivery
import com.norfold.app.domain.QuietHours

/**
 * Receives pushes and decides whether each one may interrupt.
 *
 * ## What the owner's preferences can and cannot do to a push
 *
 * A push is not a reminder. A reminder is Norfold's own idea, computed on-device from a task row,
 * so quiet hours can *hold* it: the alarm is simply re-armed for the end of the window and nothing
 * is lost. A push is a thing that has already happened somewhere else — FCM has delivered it, the
 * message exists, and there is no second delivery to schedule. Suppressing the post does not defer
 * it, it discards it.
 *
 * So quiet hours are honoured here in the only way that keeps the message: the notification is
 * still posted, silently. [NotificationCompat.Builder.setSilent] is not a no-op on the versions
 * Norfold supports — from API 26 it puts the notification in a group with no summary and sets
 * `GROUP_ALERT_SUMMARY`, so the channel's sound and vibration have nothing to alert for while the
 * notification itself still appears in the shade. The owner wakes up to it rather than being woken
 * by it, and nothing is dropped.
 *
 * [AppSettingsEntity.notificationPush] is a different matter, and is read the way onboarding
 * presents it: "Task reminders". That is what it names on the onboarding step and what it now gates
 * in Settings, so it suppresses `task*` pushes and leaves chat alone. Switching off task reminders
 * must not silently stop delivering the messages people send you.
 *
 * ## Why per-conversation mute is applied here as well as on the server
 *
 * The server filters account chat by `conversation_notification_prefs`, and since
 * [com.norfold.app.data.ConversationNotificationMirror] that table is actually written — for an
 * account conversation the preference now suppresses the push before it is ever enqueued, which is
 * stronger than anything this class can do. So the check below is no longer the only thing keeping
 * the promise the permission dialog makes; it is the fallback for the cases the server cannot cover.
 *
 * Those cases are not edge cases. A workspace channel has no row in that table at all and is
 * device-local by design. A preference changed while offline or signed out has not reached the
 * server yet. And 'mentions' depends on this check entirely: the server says so in the migration —
 * it cannot read ciphertext to find a mention, so it deliberately still delivers and leaves the
 * decision here.
 *
 * Mute silences rather than suppresses, for the same reason quiet hours do: a muted conversation is
 * one the owner does not want to be interrupted by, not one whose messages they want deleted before
 * they can see them. The server suppressing instead is not a contradiction — it prevents a
 * notification rather than discarding a message, because messages arrive over the encrypted relay
 * and not over FCM. [onMessageReceived] reconciles before consulting any preference for the same
 * reason: a push is a hint to catch up, and catching up must not depend on being allowed to alert.
 */
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
        // Always reconcile so encrypted catch-up runs even when we suppress the
        // visible notification below.
        NorfoldWorkScheduler.enqueueReconciliation(this)
        // Foreground dedup (spec §149): if the user is already looking at the
        // exact conversation this push targets, skip the redundant system
        // notification — the in-app stream already shows the message.
        if (isRedundantForForeground(message.data)) return
        val preferences = deliveryPreferences(message.data)
        if (preferences.suppressed) return
        showNotification(title, body, message.data, silent = preferences.silent)
    }

    override fun onDeletedMessages() {
        NorfoldWorkScheduler.enqueueReconciliation(this, replace = true)
    }

    /**
     * True when this push targets the exact conversation the user is already
     * viewing, so the visible system notification would duplicate what the
     * in-app stream is already showing (spec §149). Only chat kinds qualify;
     * everything else always surfaces. Routing metadata only — never content.
     */
    private fun isRedundantForForeground(data: Map<String, String>): Boolean {
        if (!PushDelivery.isChatKind(data["kind"])) return false
        return when (data["scope"]) {
            "account" -> ChatForegroundState.isAccountConversationActive(data["conversationId"])
            else -> ChatForegroundState.isWorkspaceChannelActive(data["channelId"])
        }
    }

    /**
     * Resolves the owner's preferences for one push.
     *
     * Only the reads live here; the rule itself is [PushDelivery.decide], which is pure and is
     * certified without a device. Blocking on the settings read is deliberate: `onMessageReceived`
     * is documented as running on a background thread with roughly twenty seconds before FCM
     * considers the message handled, and the answer has to be known before [showNotification] builds
     * anything — launching into [serviceScope] and posting anyway would post first and learn the
     * preference afterwards. The same bridge is used for Ably's synchronous token callback.
     *
     * A failed read falls back to "post it, audibly". Every fallback in this method points the same
     * way, because a broken database must not become a reason to silence a message.
     */
    private fun deliveryPreferences(data: Map<String, String>): PushDelivery.Decision {
        val kind = data["kind"]
        val settings = runCatching {
            runBlocking(Dispatchers.IO) { NorfoldDatabase.get(this@NorfoldMessagingService).dao().settings() }
        }.getOrNull()
        return PushDelivery.decide(
            kind = kind,
            taskNotificationsAllowed = settings?.notificationPush ?: true,
            withinQuietHours = settings?.let {
                QuietHours.contains(
                    QuietHours.window(it.quietHoursEnabled, it.quietHoursStart, it.quietHoursEnd),
                    System.currentTimeMillis(),
                )
            } ?: false,
            conversationSilenced = PushDelivery.isChatKind(kind) && isConversationSilenced(data),
        )
    }

    /**
     * True when the owner has said not to interrupt them about this conversation.
     *
     * The preference is stored against the account conversation id for a DM or personal group, and
     * against the *local* channel row id for a workspace channel — while the push carries the
     * channel's sync id, because local ids never cross the wire. So the workspace case needs one
     * lookup to turn the sync id back into the row id the preference is filed under. Failing to
     * find the channel means this device has never seen it, which means there is no mute to honour.
     */
    private fun isConversationSilenced(data: Map<String, String>): Boolean {
        val key = when (data["scope"]) {
            "account" -> ChatConversationPrefs.conversationKey(
                data["conversationId"]?.takeIf { it.isNotBlank() } ?: return false,
                null,
            )
            else -> {
                val syncId = data["channelId"]?.takeIf { it.isNotBlank() } ?: return false
                val channel = runCatching {
                    runBlocking(Dispatchers.IO) {
                        NorfoldDatabase.get(this@NorfoldMessagingService).dao().chatChannelBySyncIdAnyWorkspace(syncId)
                    }
                }.getOrNull() ?: return false
                // An account conversation reached through a workspace row is still filed under its
                // portable id, so the key is built the same way the UI builds it.
                ChatConversationPrefs.conversationKey(channel.accountConversationId, channel.id)
            }
        }
        if (ChatConversationPrefs.isMuted(this, key)) return true
        // "Mentions only" cannot be decided from routing metadata — the server says so explicitly,
        // and the ciphertext is not readable here. Treating it as silent is the honest reading:
        // the message still arrives, and the owner is not interrupted by one they may not be in.
        return ChatConversationPrefs.notificationLevel(this, key) != ChatConversationPrefs.NotificationLevel.All
    }

    private fun showNotification(title: String, body: String, data: Map<String, String>, silent: Boolean) {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val manager = getSystemService(NotificationManager::class.java)
        val channelId = channelFor(data["kind"])
        NorfoldNotificationChannels.ensure(manager, channelId)
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
                .putExtra("norfold.messageId", data["messageId"])
                .putExtra("norfold.conversationId", data["conversationId"])
                .putExtra("norfold.scope", data["scope"])
                .putExtra("norfold.requestId", data["requestId"]),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = NotificationCompat.Builder(this, channelId)
                .setSmallIcon(R.drawable.ic_norfold_notification)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                // Quiet hours and a muted conversation both land here: the notification is posted
                // and visible, but has nothing to alert with. See the class KDoc for why silencing
                // rather than dropping is the only reading that keeps the message.
                .setSilent(silent)
        if (PushDelivery.isChatKind(data["kind"])) {
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
        PushDelivery.isChatKind(kind) -> NorfoldNotificationChannels.Chat
        PushDelivery.isTaskKind(kind) -> NorfoldNotificationChannels.Tasks
        kind == "backup" || kind == "sync" -> NorfoldNotificationChannels.Sync
        else -> NorfoldNotificationChannels.Workspace
    }
}
