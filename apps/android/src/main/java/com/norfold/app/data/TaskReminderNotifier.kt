package com.norfold.app.data

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.norfold.app.MainActivity
import com.norfold.app.R
import com.norfold.app.cloud.NorfoldNotificationChannels
import com.norfold.app.domain.TaskReminderPlan
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Composes the visible half of a task reminder. Local data only; nothing leaves the device. */
internal object TaskReminderNotifier {
    /**
     * False when the system would drop the post anyway.
     *
     * Checked before composing rather than after, so a device where the owner declined
     * notifications does not burn work building something that cannot be shown. On API 33+ this is
     * the runtime permission; below it, posting is always allowed.
     */
    fun canPost(context: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun build(context: Context, reminder: TaskReminderPlan.Reminder): Notification {
        val body = describe(reminder)
        return NotificationCompat.Builder(context, NorfoldNotificationChannels.Tasks)
            .setSmallIcon(R.drawable.ic_norfold_notification)
            .setContentTitle(reminder.title.ifBlank { "Task reminder" })
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            // The moment the reminder is about, not the moment it was posted — a reminder delivered
            // late after a reboot should still sort under the time it was for.
            .setWhen(reminder.triggerAt)
            .setShowWhen(true)
            .setContentIntent(openTasks(context, reminder))
            .setAutoCancel(true)
            .build()
    }

    /** "Due at 14:30" / "Due today" / "Starts in 1 hour" — what the offset actually meant. */
    private fun describe(reminder: TaskReminderPlan.Reminder): String {
        val whenText = if (reminder.allDay) {
            dateFormat.format(Date(reminder.anchorAt))
        } else {
            "${dateFormat.format(Date(reminder.anchorAt))} at ${timeFormat.format(Date(reminder.anchorAt))}"
        }
        return when (reminder.minutesBefore) {
            0 -> "Due $whenText"
            in 1..59 -> "Due in ${reminder.minutesBefore} min — $whenText"
            in 60..1439 -> {
                val hours = reminder.minutesBefore / 60
                "Due in $hours ${if (hours == 1) "hour" else "hours"} — $whenText"
            }
            else -> {
                val days = reminder.minutesBefore / 1440
                "Due in $days ${if (days == 1) "day" else "days"} — $whenText"
            }
        }
    }

    private fun openTasks(context: Context, reminder: TaskReminderPlan.Reminder): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra("norfold.destination", "Tasks")
            .putExtra("norfold.taskId", reminder.taskId)
            .putExtra("norfold.workspaceId", reminder.workspaceId.toString())
        return PendingIntent.getActivity(
            context,
            reminder.taskId.toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    // The task editor states 24-hour HH:mm; a reminder that reads the same value back in a
    // different clock format would look like a different time.
    private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
    private val dateFormat = SimpleDateFormat("EEE d MMM", Locale.getDefault())
}
