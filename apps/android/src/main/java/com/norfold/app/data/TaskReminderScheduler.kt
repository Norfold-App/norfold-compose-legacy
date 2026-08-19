package com.norfold.app.data

import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.norfold.app.cloud.NorfoldNotificationChannels
import com.norfold.app.domain.TaskReminderPlan
import java.util.TimeZone
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * Delivers the task reminders the app has been storing but never firing.
 *
 * ## Why one alarm rather than one per task
 *
 * A reminder per task means N alarms to cancel and re-arm on every edit, and an alarm whose
 * PendingIntent was built from a task id that has since been deleted. Instead this keeps a single
 * alarm armed for the earliest reminder still ahead. When it fires, [deliverDue] posts everything
 * that has come due and then arms the next one. Reconciliation is therefore idempotent: recomputing
 * from the current rows always converges on the correct single alarm, whatever changed in between.
 *
 * ## Why exact alarms are requested but never required
 *
 * Norfold is a productivity app, not a clock or calendar app, so Play policy reserves
 * `USE_EXACT_ALARM` — the pre-granted one — for apps it does not cover. `SCHEDULE_EXACT_ALARM` is
 * the permission that fits, and on Android 13+ it is a special permission the user grants in
 * system settings; from Android 14 it is not pre-granted at all. So the capability is checked, not
 * assumed, and its absence downgrades precision rather than dropping the reminder:
 * [AlarmManager.setWindow] still delivers, within a window instead of on the minute. A reminder
 * that arrives a few minutes late is a working feature; a reminder that throws
 * `SecurityException` because the permission was assumed is a crash in the owner's face.
 *
 * ## What is deliberately not here
 *
 * Nothing about a reminder is sent anywhere. The notification carries the task's own title because
 * a reminder that will not say what it is about is not a reminder, but it is composed on-device,
 * from a local Room row, and posted to the local notification manager.
 */
object TaskReminderScheduler {
    private const val Prefs = "norfold_task_reminders"
    private const val KeyDeliveredThrough = "deliveredThrough"
    private const val KeyArmedFor = "armedFor"
    private const val AlarmRequestCode = 0x7A5C
    private const val NotificationIdBase = 0x7A50_0000

    /**
     * The window an inexact alarm may drift within when exact alarms are unavailable.
     *
     * Five minutes is short enough that "1 hour before" still reads as an hour, and long enough
     * that the system can batch the wake-up with others instead of holding one for us alone.
     */
    private const val InexactWindowMillis = 5 * 60 * 1000L

    /**
     * Recomputes the reminder state from the current rows and arms the next alarm.
     *
     * Safe to call on any task change, on launch, after a restore, and after a reboot. It reads
     * every workspace's tasks rather than only the active one: a reminder set in a workspace the
     * owner has since switched away from is still a reminder they asked for.
     */
    suspend fun reconcile(context: Context) {
        val applicationContext = context.applicationContext
        val reminders = plannedReminders(applicationContext)
        val now = System.currentTimeMillis()
        // Reminders that came due while nothing was listening — during a reboot, or before this
        // build existed — are delivered on this pass if they are still inside the grace window.
        deliver(applicationContext, reminders, now)
        armNext(applicationContext, reminders, now)
    }

    /**
     * Handles a fired alarm: posts what is due and arms the next one.
     *
     * Separate from [reconcile] only so the receiver reads as what it is. The work is the same,
     * because an alarm firing and a task being edited leave the same question to answer.
     */
    suspend fun deliverDue(context: Context) = reconcile(context)

    /**
     * Keeps the armed alarm in step with the task rows for as long as [scope] lives.
     *
     * Driven by the table rather than by the ~20 mutation functions that write to it. Hooking each
     * call site would mean a reminder silently stops being armed the day someone adds the
     * twenty-first — and would still miss the writes that do not go through the view model at all:
     * a restore, and background sync pulling a task edited on another device. Observing the rows
     * covers every one of those, including the ones that do not exist yet.
     *
     * [distinctUntilChanged] is what makes this cheap: Room re-emits on any write to `tasks`, so
     * without it, dragging a card between columns would re-arm the alarm. The projection carries
     * only the eight reminder-relevant columns, so an unrelated edit emits an equal list and stops
     * here.
     */
    fun observe(context: Context, scope: CoroutineScope) {
        val applicationContext = context.applicationContext
        scope.launch {
            NorfoldDatabase.get(applicationContext).dao()
                .observeTaskReminderRows()
                .distinctUntilChanged()
                .collect { rows ->
                    // A single failed pass must not tear down the collector: the next task edit
                    // should still get a chance to arm the alarm.
                    runCatching {
                        val reminders = plan(rows)
                        val now = System.currentTimeMillis()
                        deliver(applicationContext, reminders, now)
                        armNext(applicationContext, reminders, now)
                    }
                }
        }
    }

    /**
     * True when this device will honour an exact alarm.
     *
     * Exposed so a settings surface can tell the owner the truth — "reminders may arrive a few
     * minutes late until you allow exact alarms" — instead of the app quietly being imprecise.
     */
    fun canDeliverExactly(context: Context): Boolean {
        val manager = context.getSystemService(AlarmManager::class.java) ?: return false
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) manager.canScheduleExactAlarms() else true
    }

    private suspend fun plannedReminders(context: Context): List<TaskReminderPlan.Reminder> {
        val dao = NorfoldDatabase.get(context).dao()
        return plan(dao.taskReminderRows())
    }

    /** Shared by the stored-row read and by [observe], so both paths interpret a row identically. */
    private fun plan(rows: List<TaskReminderRow>): List<TaskReminderPlan.Reminder> {
        val candidates = rows.map { row ->
            TaskReminderPlan.Candidate(
                taskId = row.id,
                workspaceId = row.workspaceId,
                title = row.title,
                startAt = row.startAt,
                dueAt = row.dueAt,
                allDay = row.allDay,
                reminderMinutesBefore = row.reminderMinutesBefore,
                done = row.status.equals("Done", ignoreCase = true),
            )
        }
        return TaskReminderPlan.reminders(candidates, TimeZone.getDefault())
    }

    private fun deliver(context: Context, reminders: List<TaskReminderPlan.Reminder>, now: Long) {
        val prefs = context.getSharedPreferences(Prefs, Context.MODE_PRIVATE)
        // A fresh install has no watermark. Starting it at "now" rather than 0 means the first
        // reconcile does not announce every reminder in the restored corpus at once.
        val deliveredThrough = prefs.getLong(KeyDeliveredThrough, now)
        val due = TaskReminderPlan.dueBetween(reminders, deliveredThrough, now)
        // The watermark advances past everything whose moment has passed, including reminders that
        // fell outside the grace window. They were missed; re-asking about them later is worse.
        val watermark = reminders.asSequence()
            .map(TaskReminderPlan.Reminder::triggerAt)
            .filter { it <= now }
            .maxOrNull()
            ?.coerceAtLeast(deliveredThrough)
            ?: deliveredThrough
        prefs.edit().putLong(KeyDeliveredThrough, maxOf(watermark, deliveredThrough)).apply()
        if (due.isEmpty()) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (!TaskReminderNotifier.canPost(context)) return
        NorfoldNotificationChannels.ensure(manager, NorfoldNotificationChannels.Tasks)
        due.forEach { reminder ->
            manager.notify(
                notificationId(reminder.taskId),
                TaskReminderNotifier.build(context, reminder),
            )
        }
    }

    private fun armNext(context: Context, reminders: List<TaskReminderPlan.Reminder>, now: Long) {
        val manager = context.getSystemService(AlarmManager::class.java) ?: return
        val prefs = context.getSharedPreferences(Prefs, Context.MODE_PRIVATE)
        val next = TaskReminderPlan.nextTriggerAfter(reminders, now)
        if (next == null) {
            // FLAG_NO_CREATE so that "nothing is scheduled" does not itself create a PendingIntent
            // just to cancel it. Null here means there was never an alarm to cancel.
            existingAlarmIntent(context)?.let { pending ->
                manager.cancel(pending)
                pending.cancel()
            }
            prefs.edit().remove(KeyArmedFor).apply()
            return
        }
        val pending = alarmIntent(context)
        // An alarm is set unconditionally rather than skipped when `armedFor` already matches:
        // AlarmManager forgets everything across a reboot, and this same call is what re-arms it.
        if (canDeliverExactly(context)) {
            // AllowWhileIdle is what makes a reminder survive Doze. The system rate-limits these,
            // which is fine — there is only ever one armed.
            manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pending)
        } else {
            manager.setWindow(AlarmManager.RTC_WAKEUP, next, InexactWindowMillis, pending)
        }
        prefs.edit().putLong(KeyArmedFor, next).apply()
    }

    private fun alarmIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context,
        AlarmRequestCode,
        deliverIntent(context),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /** The alarm's PendingIntent only if one already exists, so cancelling never creates one. */
    private fun existingAlarmIntent(context: Context): PendingIntent? = PendingIntent.getBroadcast(
        context,
        AlarmRequestCode,
        deliverIntent(context),
        PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun deliverIntent(context: Context): Intent =
        Intent(context, TaskReminderReceiver::class.java).setAction(TaskReminderReceiver.ActionDeliver)

    /**
     * A stable per-task notification id, so re-posting a reminder for the same task replaces the
     * previous one instead of stacking a second copy in the shade.
     */
    private fun notificationId(taskId: Long): Int = NotificationIdBase or (taskId.toInt() and 0xFFFF)
}
