package com.norfold.app.domain

import java.util.Calendar
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The arithmetic half of task reminders, which is the half that can be certified without a device.
 *
 * Delivery itself — a posted notification, an alarm surviving a reboot, the exact-alarm permission
 * prompt — cannot be, and is deferred to a device pass. What is checked here is everything that
 * decides *which* moment a reminder is owed at, because that is where a reminder silently becomes
 * wrong rather than absent: an all-day task firing at midnight, a stale reminder announced hours
 * late, the same reminder posted twice.
 */
class TaskReminderPlanTest {
    private val zone = TimeZone.getTimeZone("Europe/London")

    private fun at(year: Int, month: Int, day: Int, hour: Int = 0, minute: Int = 0): Long =
        Calendar.getInstance(zone).apply {
            clear()
            set(year, month - 1, day, hour, minute, 0)
        }.timeInMillis

    private fun candidate(
        id: Long = 1L,
        startAt: Long? = null,
        dueAt: Long? = null,
        allDay: Boolean = false,
        minutesBefore: Int? = 0,
        done: Boolean = false,
        title: String = "Ship the beta",
    ) = TaskReminderPlan.Candidate(
        taskId = id,
        workspaceId = 1L,
        title = title,
        startAt = startAt,
        dueAt = dueAt,
        allDay = allDay,
        reminderMinutesBefore = minutesBefore,
        done = done,
    )

    // The four tiers the board actually offers: "At time", "1 hour", "1 day", and no reminder.

    @Test
    fun atTimeFiresAtTheSavedMoment() {
        val moment = at(2026, 8, 21, 14, 30)
        val reminders = TaskReminderPlan.reminders(listOf(candidate(startAt = moment, minutesBefore = 0)), zone)
        assertEquals(1, reminders.size)
        assertEquals(moment, reminders.single().triggerAt)
    }

    @Test
    fun oneHourTierFiresAnHourEarlier() {
        val moment = at(2026, 8, 21, 14, 30)
        val reminders = TaskReminderPlan.reminders(listOf(candidate(startAt = moment, minutesBefore = 60)), zone)
        assertEquals(at(2026, 8, 21, 13, 30), reminders.single().triggerAt)
    }

    @Test
    fun oneDayTierFiresTheDayBeforeAtTheSameTime() {
        val moment = at(2026, 8, 21, 14, 30)
        val reminders = TaskReminderPlan.reminders(listOf(candidate(startAt = moment, minutesBefore = 1440)), zone)
        assertEquals(at(2026, 8, 20, 14, 30), reminders.single().triggerAt)
    }

    @Test
    fun noReminderTierProducesNothing() {
        val reminders = TaskReminderPlan.reminders(
            listOf(candidate(startAt = at(2026, 8, 21, 14, 30), minutesBefore = null)),
            zone,
        )
        assertTrue(reminders.isEmpty())
    }

    // An all-day task stores midnight, because the board's Today/Tomorrow/Next-week chips zero the
    // time fields. Read literally, "At time" on such a task means 00:00 — a correct reading of the
    // stored value and a useless notification.

    @Test
    fun allDayTaskAnchorsToTheMorningRatherThanMidnight() {
        val storedMidnight = at(2026, 8, 21)
        val reminders = TaskReminderPlan.reminders(
            listOf(candidate(startAt = storedMidnight, allDay = true, minutesBefore = 0)),
            zone,
        )
        assertEquals(at(2026, 8, 21, TaskReminderPlan.AllDayAnchorHour, 0), reminders.single().triggerAt)
    }

    @Test
    fun allDayOneDayTierCountsBackFromTheAnchorNotFromMidnight() {
        val reminders = TaskReminderPlan.reminders(
            listOf(candidate(startAt = at(2026, 8, 21), allDay = true, minutesBefore = 1440)),
            zone,
        )
        assertEquals(at(2026, 8, 20, TaskReminderPlan.AllDayAnchorHour, 0), reminders.single().triggerAt)
    }

    @Test
    fun timedTaskIsNotAnchored() {
        // Only the all-day flag moves the hour. A task explicitly saved for 00:00 means 00:00.
        val midnight = at(2026, 8, 21)
        val reminders = TaskReminderPlan.reminders(
            listOf(candidate(startAt = midnight, allDay = false, minutesBefore = 0)),
            zone,
        )
        assertEquals(midnight, reminders.single().triggerAt)
    }

    // Which stored column the reminder hangs off, and what disqualifies a row entirely.

    @Test
    fun dueDateAnchorsTheReminderWhenThereIsNoStart() {
        val due = at(2026, 8, 21, 17, 0)
        val reminders = TaskReminderPlan.reminders(
            listOf(candidate(startAt = null, dueAt = due, minutesBefore = 0)),
            zone,
        )
        assertEquals(due, reminders.single().triggerAt)
    }

    @Test
    fun startWinsOverDueWhenBothExist() {
        val start = at(2026, 8, 21, 9, 0)
        val reminders = TaskReminderPlan.reminders(
            listOf(candidate(startAt = start, dueAt = at(2026, 8, 21, 17, 0), minutesBefore = 0)),
            zone,
        )
        assertEquals(start, reminders.single().triggerAt)
    }

    @Test
    fun aDatelessTaskOwesNothingEvenWithAnOffsetStored() {
        val reminders = TaskReminderPlan.reminders(listOf(candidate(startAt = null, dueAt = null, minutesBefore = 60)), zone)
        assertTrue(reminders.isEmpty())
    }

    @Test
    fun completedTaskOwesNothing() {
        val reminders = TaskReminderPlan.reminders(
            listOf(candidate(startAt = at(2026, 8, 21, 14, 30), minutesBefore = 60, done = true)),
            zone,
        )
        assertTrue(reminders.isEmpty())
    }

    @Test
    fun negativeOffsetIsRejectedRatherThanFiringAfterTheEvent() {
        val reminders = TaskReminderPlan.reminders(
            listOf(candidate(startAt = at(2026, 8, 21, 14, 30), minutesBefore = -30)),
            zone,
        )
        assertTrue(reminders.isEmpty())
    }

    // Ordering, because the scheduler arms exactly one alarm and reads the earliest from this list.

    @Test
    fun remindersSortByTriggerThenById() {
        val later = candidate(id = 1L, startAt = at(2026, 8, 22, 10, 0), minutesBefore = 0)
        val earlier = candidate(id = 2L, startAt = at(2026, 8, 21, 10, 0), minutesBefore = 0)
        val sameAsEarlier = candidate(id = 3L, startAt = at(2026, 8, 21, 10, 0), minutesBefore = 0)
        val reminders = TaskReminderPlan.reminders(listOf(later, sameAsEarlier, earlier), zone)
        assertEquals(listOf(2L, 3L, 1L), reminders.map { it.taskId })
    }

    @Test
    fun nextTriggerSkipsWhatHasAlreadyPassed() {
        val reminders = TaskReminderPlan.reminders(
            listOf(
                candidate(id = 1L, startAt = at(2026, 8, 20, 10, 0), minutesBefore = 0),
                candidate(id = 2L, startAt = at(2026, 8, 22, 10, 0), minutesBefore = 0),
            ),
            zone,
        )
        val now = at(2026, 8, 21, 12, 0)
        assertEquals(at(2026, 8, 22, 10, 0), TaskReminderPlan.nextTriggerAfter(reminders, now))
    }

    @Test
    fun nextTriggerIsNullWhenNothingRemains() {
        val reminders = TaskReminderPlan.reminders(
            listOf(candidate(startAt = at(2026, 8, 20, 10, 0), minutesBefore = 0)),
            zone,
        )
        assertNull(TaskReminderPlan.nextTriggerAfter(reminders, at(2026, 8, 21, 12, 0)))
    }

    // The delivery window. Both bounds matter: the watermark stops a re-post, and the grace window
    // stops a device that was off overnight from waking up to a digest of things already missed.

    @Test
    fun dueBetweenPostsAReminderThatHasJustComeDue() {
        val moment = at(2026, 8, 21, 14, 30)
        val reminders = TaskReminderPlan.reminders(listOf(candidate(startAt = moment, minutesBefore = 0)), zone)
        val due = TaskReminderPlan.dueBetween(reminders, deliveredThrough = moment - 60_000L, now = moment)
        assertEquals(1, due.size)
    }

    @Test
    fun dueBetweenSuppressesWhatTheWatermarkAlreadyCovers() {
        val moment = at(2026, 8, 21, 14, 30)
        val reminders = TaskReminderPlan.reminders(listOf(candidate(startAt = moment, minutesBefore = 0)), zone)
        val due = TaskReminderPlan.dueBetween(reminders, deliveredThrough = moment, now = moment + 60_000L)
        assertTrue(due.isEmpty())
    }

    @Test
    fun dueBetweenDoesNotAnnounceTheFuture() {
        val moment = at(2026, 8, 21, 14, 30)
        val reminders = TaskReminderPlan.reminders(listOf(candidate(startAt = moment, minutesBefore = 0)), zone)
        val due = TaskReminderPlan.dueBetween(reminders, deliveredThrough = 0L, now = moment - 1L)
        assertTrue(due.isEmpty())
    }

    @Test
    fun aRebootMissedReminderInsideTheGraceWindowStillArrives() {
        val moment = at(2026, 8, 21, 14, 30)
        val reminders = TaskReminderPlan.reminders(listOf(candidate(startAt = moment, minutesBefore = 0)), zone)
        val now = moment + TaskReminderPlan.DeliveryGraceMillis - 1
        assertEquals(1, TaskReminderPlan.dueBetween(reminders, deliveredThrough = moment - 1, now = now).size)
    }

    @Test
    fun aReminderStalerThanTheGraceWindowIsDropped() {
        val moment = at(2026, 8, 21, 14, 30)
        val reminders = TaskReminderPlan.reminders(listOf(candidate(startAt = moment, minutesBefore = 0)), zone)
        val now = moment + TaskReminderPlan.DeliveryGraceMillis + 1
        assertTrue(TaskReminderPlan.dueBetween(reminders, deliveredThrough = moment - 1, now = now).isEmpty())
    }

    // What the notification will say depends on these being carried through unchanged.

    @Test
    fun reminderCarriesTheAnchorAndOffsetTheNotificationDescribes() {
        val storedMidnight = at(2026, 8, 21)
        val reminder = TaskReminderPlan.reminders(
            listOf(candidate(startAt = storedMidnight, allDay = true, minutesBefore = 1440, title = "Renew the domain")),
            zone,
        ).single()
        // anchorAt is the anchored moment, not the raw stored midnight: the notification says "due
        // Fri 21 Aug", and for a timed task it says the hour, so it must read the same value the
        // trigger was computed from.
        assertEquals(at(2026, 8, 21, TaskReminderPlan.AllDayAnchorHour, 0), reminder.anchorAt)
        assertEquals(1440, reminder.minutesBefore)
        assertTrue(reminder.allDay)
        assertEquals("Renew the domain", reminder.title)
        assertEquals(reminder.anchorAt - 1440 * 60_000L, reminder.triggerAt)
    }
}
