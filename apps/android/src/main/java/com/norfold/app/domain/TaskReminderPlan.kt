package com.norfold.app.domain

import java.util.Calendar
import java.util.TimeZone

/**
 * When each task reminder is owed, decided without touching Android.
 *
 * The task editor has been storing `reminderMinutesBefore` since schema 33 and telling the owner
 * "Reminders use the exact saved time", but nothing ever read the field back to schedule anything.
 * This is the arithmetic half of closing that gap: given the stored rows and a clock, which
 * reminders are still ahead, which came due while nobody was listening, and which are stale enough
 * that announcing them now would be noise rather than a reminder.
 *
 * Keeping it here rather than inside the scheduler is what makes the trigger times testable on a
 * desktop JVM. The Android half only arms an alarm for [nextTriggerAfter] and posts whatever
 * [dueBetween] returns.
 *
 * Quiet hours are applied here for the same reason: whether a reminder is held, and until when, is
 * arithmetic over a stored window and a clock, so it belongs next to the arithmetic that decided the
 * moment in the first place. See [Reminder.deliverAt] for why a hold is derived rather than stored.
 */
object TaskReminderPlan {
    /**
     * How late a reminder may be delivered after its moment has passed.
     *
     * A device that was off overnight comes back with reminders that are hours stale. Firing all of
     * them at boot turns a reminder into a digest of things the owner already missed, so anything
     * older than this window is dropped silently instead. Two hours keeps a reminder that was
     * missed during a reboot or a short power-off, and discards yesterday's.
     */
    const val DeliveryGraceMillis: Long = 2 * 60 * 60 * 1000L

    /**
     * The hour an all-day task's reminder is measured back from.
     *
     * An all-day task stores midnight, so "At time" would literally mean 00:00 — a correct reading
     * of the stored value and a useless notification. Every calendar that offers all-day reminders
     * anchors them to a morning hour instead; 09:00 local is the common default. This is a
     * convention rather than something the stored data states, so it is named here rather than
     * buried in the arithmetic.
     */
    const val AllDayAnchorHour: Int = 9

    /**
     * A reminder that is owed for one task, and the two moments that describe it.
     *
     * [triggerAt] is when the reminder came due; [deliverAt] is the earliest moment it may actually
     * be announced. They differ only when quiet hours cover [triggerAt], in which case [deliverAt]
     * is the end of that window. Keeping both is what lets a held reminder stay legible: the
     * notification sorts and reads against the moment it was for, while every scheduling decision —
     * what to arm, what is due, what the delivery watermark has passed — reads [deliverAt].
     *
     * Deriving the hold here rather than recording it means there is no hold state to keep in step.
     * Changing the quiet-hours window, or switching it off, simply recomputes a different
     * [deliverAt] on the next pass; a stored "held until" would have had to be found and migrated.
     */
    data class Reminder(
        val taskId: Long,
        val workspaceId: Long,
        val title: String,
        val minutesBefore: Int,
        val anchorAt: Long,
        val allDay: Boolean,
        val triggerAt: Long,
        val deliverAt: Long = triggerAt,
    )

    /**
     * The reminder-bearing fields of a task row, so this file never depends on the Room entity or
     * on [TaskItem] growing another column.
     */
    data class Candidate(
        val taskId: Long,
        val workspaceId: Long,
        val title: String,
        val startAt: Long?,
        val dueAt: Long?,
        val allDay: Boolean,
        val reminderMinutesBefore: Int?,
        val done: Boolean,
    )

    /**
     * Every reminder the given rows describe, earliest first.
     *
     * A row contributes nothing when it has no reminder offset, no date to measure from, or is
     * already finished — a completed task reminding you about itself is a bug, not a feature.
     *
     * [quietHours] holds a reminder that comes due inside the window until it ends, rather than
     * dropping it: see [Reminder.deliverAt]. Passing null — the default — is "no quiet hours", so
     * every existing caller keeps its current behaviour.
     */
    fun reminders(
        candidates: List<Candidate>,
        timeZone: TimeZone = TimeZone.getDefault(),
        quietHours: QuietHours.Window? = null,
    ): List<Reminder> = candidates.mapNotNull { candidate ->
        val minutes = candidate.reminderMinutesBefore ?: return@mapNotNull null
        if (minutes < 0) return@mapNotNull null
        if (candidate.done) return@mapNotNull null
        val stored = candidate.startAt ?: candidate.dueAt ?: return@mapNotNull null
        val anchor = if (candidate.allDay) allDayAnchor(stored, timeZone) else stored
        val trigger = anchor - minutes * 60_000L
        Reminder(
            taskId = candidate.taskId,
            workspaceId = candidate.workspaceId,
            title = candidate.title,
            minutesBefore = minutes,
            anchorAt = anchor,
            allDay = candidate.allDay,
            triggerAt = trigger,
            deliverAt = QuietHours.releaseAt(quietHours, trigger, timeZone),
        )
    }.sortedWith(compareBy(Reminder::deliverAt, Reminder::taskId))

    /**
     * The earliest moment still to wait for after [after], or null when nothing is left.
     *
     * The scheduler holds one alarm rather than one per task, so this is the only time it needs. It
     * reads [Reminder.deliverAt] rather than [Reminder.triggerAt] because a reminder held to the end
     * of quiet hours needs the alarm armed for its *release*: arming the original moment would wake
     * the device inside the window to post nothing, and then nothing would be armed for the release.
     */
    fun nextTriggerAfter(reminders: List<Reminder>, after: Long): Long? =
        reminders.asSequence().map(Reminder::deliverAt).filter { it > after }.minOrNull()

    /**
     * Reminders to post right now: deliverable at or before [now], not already delivered by an
     * earlier pass, and not older than [graceMillis].
     *
     * [deliveredThrough] is a watermark rather than a per-task record. Any reminder whose delivery
     * moment is at or before it has already been announced, which is what stops a task edit — or a
     * second alarm arriving in the same second — from announcing the same reminder twice.
     *
     * The grace window is measured from [Reminder.deliverAt] too, so a reminder held across a
     * nine-hour night is not then discarded as nine hours stale. Its whole purpose is to arrive at
     * the end of the window, and the notification body still names the original time.
     */
    fun dueBetween(
        reminders: List<Reminder>,
        deliveredThrough: Long,
        now: Long,
        graceMillis: Long = DeliveryGraceMillis,
    ): List<Reminder> = reminders.filter { reminder ->
        reminder.deliverAt > deliveredThrough &&
            reminder.deliverAt <= now &&
            now - reminder.deliverAt <= graceMillis
    }

    /** The moment an all-day task's reminder counts back from: [AllDayAnchorHour] on its own day. */
    private fun allDayAnchor(stored: Long, timeZone: TimeZone): Long =
        Calendar.getInstance(timeZone).apply {
            timeInMillis = stored
            set(Calendar.HOUR_OF_DAY, AllDayAnchorHour)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
}
