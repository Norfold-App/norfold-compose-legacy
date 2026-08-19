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

    /** A reminder that is owed for one task at one moment. */
    data class Reminder(
        val taskId: Long,
        val workspaceId: Long,
        val title: String,
        val minutesBefore: Int,
        val anchorAt: Long,
        val allDay: Boolean,
        val triggerAt: Long,
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
     */
    fun reminders(
        candidates: List<Candidate>,
        timeZone: TimeZone = TimeZone.getDefault(),
    ): List<Reminder> = candidates.mapNotNull { candidate ->
        val minutes = candidate.reminderMinutesBefore ?: return@mapNotNull null
        if (minutes < 0) return@mapNotNull null
        if (candidate.done) return@mapNotNull null
        val stored = candidate.startAt ?: candidate.dueAt ?: return@mapNotNull null
        val anchor = if (candidate.allDay) allDayAnchor(stored, timeZone) else stored
        Reminder(
            taskId = candidate.taskId,
            workspaceId = candidate.workspaceId,
            title = candidate.title,
            minutesBefore = minutes,
            anchorAt = anchor,
            allDay = candidate.allDay,
            triggerAt = anchor - minutes * 60_000L,
        )
    }.sortedWith(compareBy(Reminder::triggerAt, Reminder::taskId))

    /**
     * The earliest reminder strictly after [after], or null when nothing is left to wait for.
     *
     * The scheduler holds one alarm rather than one per task, so this is the only time it needs.
     */
    fun nextTriggerAfter(reminders: List<Reminder>, after: Long): Long? =
        reminders.asSequence().map(Reminder::triggerAt).filter { it > after }.minOrNull()

    /**
     * Reminders to post right now: due at or before [now], not already delivered by an earlier
     * pass, and not older than [graceMillis].
     *
     * [deliveredThrough] is a watermark rather than a per-task record. Any reminder whose moment is
     * at or before it has already been announced, which is what stops a task edit — or a second
     * alarm arriving in the same second — from announcing the same reminder twice.
     */
    fun dueBetween(
        reminders: List<Reminder>,
        deliveredThrough: Long,
        now: Long,
        graceMillis: Long = DeliveryGraceMillis,
    ): List<Reminder> = reminders.filter { reminder ->
        reminder.triggerAt > deliveredThrough &&
            reminder.triggerAt <= now &&
            now - reminder.triggerAt <= graceMillis
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
