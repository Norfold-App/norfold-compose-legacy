package com.norfold.app.cloud

import android.app.NotificationChannel
import android.app.NotificationManager

/**
 * The notification channels Norfold posts to, named in one place.
 *
 * A channel id is a durable contract with the system: once a channel exists on a device, its
 * importance and name are whatever the user last left them, and a second id spelled slightly
 * differently becomes a second row in Android's notification settings that the owner has to
 * configure separately. Push and local reminders both belong to "Tasks and reminders", so they
 * share the id from here rather than each carrying their own copy of the string.
 */
object NorfoldNotificationChannels {
    const val Workspace = "norfold_workspace"
    const val Chat = "norfold_chat"
    const val Tasks = "norfold_tasks"
    const val Sync = "norfold_sync"

    fun displayName(channelId: String): String = when (channelId) {
        Chat -> "Messages"
        Tasks -> "Tasks and reminders"
        Sync -> "Backup and sync"
        else -> "Workspace activity"
    }

    /** Creating an existing channel is a no-op, so callers may do this on every post. */
    fun ensure(manager: NotificationManager, channelId: String, importance: Int = NotificationManager.IMPORTANCE_DEFAULT) {
        manager.createNotificationChannel(NotificationChannel(channelId, displayName(channelId), importance))
    }
}
