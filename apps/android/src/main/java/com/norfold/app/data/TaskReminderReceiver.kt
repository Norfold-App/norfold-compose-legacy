package com.norfold.app.data

import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Every way a task reminder can become owed while no part of the app is running.
 *
 * All four actions do the same thing — recompute from the rows and arm the next alarm — because all
 * four leave the same question open. They are separate filters only because Android delivers them
 * as separate broadcasts:
 *
 * - the alarm firing, which is a reminder actually coming due;
 * - **boot**, because `AlarmManager` keeps nothing across a restart. Without this a single reboot
 *   silently disarms every future reminder, which is the same failure this whole change exists to
 *   fix, only harder to notice;
 * - **the exact-alarm permission changing**, so granting it in system settings upgrades the pending
 *   alarm from a window to an exact time instead of waiting for the next app launch;
 * - **the clock or time zone moving**, because a reminder is owed at a wall-clock moment. Flying to
 *   another zone must move the reminder with the calendar, not keep the old absolute instant.
 */
class TaskReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        // ACTION_TIME_CHANGED is the constant whose string value is "android.intent.action.TIME_SET",
        // which is what the manifest filter spells. They read as a mismatch and are the same
        // broadcast; TaskReminderActionsTest asserts it so nobody "fixes" it into a real bug.
        val recognized = action == ActionDeliver ||
            action == Intent.ACTION_BOOT_COMPLETED ||
            action == Intent.ACTION_MY_PACKAGE_REPLACED ||
            action == Intent.ACTION_TIME_CHANGED ||
            action == Intent.ACTION_TIMEZONE_CHANGED ||
            action == AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED
        if (!recognized) return
        val applicationContext = context.applicationContext
        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                // A receiver runs before anything else in the process, so a Room read here may be
                // the first touch of the database. Failure must not become a crash on boot.
                runCatching { TaskReminderScheduler.deliverDue(applicationContext) }
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        const val ActionDeliver = "com.norfold.app.action.DELIVER_TASK_REMINDERS"
    }
}
