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

    // ── Quiet hours ──
    // The window says the small hours are the wrong moment to speak, not that the reminder should
    // stop existing. So a covered reminder gets a deliverAt at the end of the window while keeping
    // its own triggerAt, and every scheduling decision reads deliverAt.

    private val night = QuietHours.window(enabled = true, start = "22:00", end = "07:00")!!

    @Test
    fun aReminderOutsideQuietHoursIsNotHeld() {
        val moment = at(2026, 8, 21, 14, 30)
        val reminder = TaskReminderPlan.reminders(listOf(candidate(startAt = moment)), zone, night).single()
        assertEquals(moment, reminder.deliverAt)
        assertEquals(reminder.triggerAt, reminder.deliverAt)
    }

    @Test
    fun aReminderInsideQuietHoursIsHeldToTheEndOfTheWindow() {
        val moment = at(2026, 8, 22, 2, 0)
        val reminder = TaskReminderPlan.reminders(listOf(candidate(startAt = moment)), zone, night).single()
        // Held, not dropped, and not moved: the trigger it was for is still on the reminder, which is
        // what lets the notification say "Due Sat 22 Aug at 02:00" when it arrives at seven.
        assertEquals(moment, reminder.triggerAt)
        assertEquals(at(2026, 8, 22, 7, 0), reminder.deliverAt)
    }

    @Test
    fun noWindowMeansNothingIsEverHeld() {
        val moment = at(2026, 8, 22, 2, 0)
        val reminder = TaskReminderPlan.reminders(listOf(candidate(startAt = moment)), zone, null).single()
        assertEquals(moment, reminder.deliverAt)
    }

    @Test
    fun anAllDayReminderIsAnchoredBeforeTheWindowIsConsidered() {
        // 09:00 is outside 22:00–07:00, so an all-day task is not held even though the value stored
        // on the row — midnight — is inside it. Applying the window to the stored moment rather than
        // the anchored one would hold every all-day reminder for no reason.
        val reminder = TaskReminderPlan.reminders(
            listOf(candidate(startAt = at(2026, 8, 21), allDay = true)),
            zone,
            night,
        ).single()
        assertEquals(at(2026, 8, 21, TaskReminderPlan.AllDayAnchorHour, 0), reminder.deliverAt)
    }

    @Test
    fun aHeldReminderIsArmedForItsReleaseNotItsTrigger() {
        // The alarm has to be set for 07:00. Arming 02:00 would wake the device inside the window to
        // post nothing, and then nothing would be armed for the release — the hold would become a
        // silent drop.
        val reminders = TaskReminderPlan.reminders(
            listOf(candidate(startAt = at(2026, 8, 22, 2, 0))),
            zone,
            night,
        )
        assertEquals(at(2026, 8, 22, 7, 0), TaskReminderPlan.nextTriggerAfter(reminders, at(2026, 8, 21, 23, 0)))
    }

    @Test
    fun aHeldReminderIsNotDueDuringTheWindow() {
        val reminders = TaskReminderPlan.reminders(
            listOf(candidate(startAt = at(2026, 8, 22, 2, 0))),
            zone,
            night,
        )
        // 03:00: its trigger has passed, so a plan that filtered on triggerAt would post it here.
        val due = TaskReminderPlan.dueBetween(reminders, deliveredThrough = 0L, now = at(2026, 8, 22, 3, 0))
        assertTrue(due.isEmpty())
    }

    @Test
    fun aHeldReminderIsDueWhenTheWindowEnds() {
        val reminders = TaskReminderPlan.reminders(
            listOf(candidate(startAt = at(2026, 8, 22, 2, 0))),
            zone,
            night,
        )
        val due = TaskReminderPlan.dueBetween(
            reminders,
            deliveredThrough = at(2026, 8, 22, 6, 59),
            now = at(2026, 8, 22, 7, 0),
        )
        assertEquals(1, due.size)
    }

    @Test
    fun aHeldReminderSurvivesAWatermarkThatPassedItsTrigger() {
        // The failure this whole design exists to prevent. A pass at 03:00 — a task edit, a sync, an
        // unrelated alarm — advances the watermark past every delivery moment already gone. If the
        // watermark were built from trigger times it would step past 02:00, and the 07:00 pass would
        // then find the reminder already covered and post nothing.
        val reminders = TaskReminderPlan.reminders(
            listOf(candidate(startAt = at(2026, 8, 22, 2, 0))),
            zone,
            night,
        )
        val nightWatermark = reminders.asSequence()
            .map(TaskReminderPlan.Reminder::deliverAt)
            .filter { it <= at(2026, 8, 22, 3, 0) }
            .maxOrNull()
        // Nothing is deliverable yet at 03:00, so the watermark does not move at all.
        assertNull(nightWatermark)
        val due = TaskReminderPlan.dueBetween(reminders, deliveredThrough = 0L, now = at(2026, 8, 22, 7, 0))
        assertEquals(1, due.size)
    }

    @Test
    fun aHeldReminderIsNotDiscardedAsStaleWhenItArrives() {
        // The night is nine hours long and the grace window is two, so measuring staleness from the
        // trigger would drop every held reminder at the moment it became deliverable.
        val reminders = TaskReminderPlan.reminders(
            listOf(candidate(startAt = at(2026, 8, 21, 22, 30))),
            zone,
            night,
        )
        val release = at(2026, 8, 22, 7, 0)
        assertEquals(release, reminders.single().deliverAt)
        assertTrue(release - reminders.single().triggerAt > TaskReminderPlan.DeliveryGraceMillis)
        assertEquals(1, TaskReminderPlan.dueBetween(reminders, deliveredThrough = release - 1, now = release).size)
    }

    @Test
    fun remindersHeldToTheSameReleaseSortByIdAndAllArrive() {
        // Everything owed across the night lands at the window's end together. They are one batch,
        // so their order has to be stable rather than dependent on how the rows came back.
        val reminders = TaskReminderPlan.reminders(
            listOf(
                candidate(id = 3L, startAt = at(2026, 8, 21, 23, 0)),
                candidate(id = 1L, startAt = at(2026, 8, 22, 5, 0)),
                candidate(id = 2L, startAt = at(2026, 8, 22, 1, 0)),
            ),
            zone,
            night,
        )
        val release = at(2026, 8, 22, 7, 0)
        assertTrue(reminders.all { it.deliverAt == release })
        assertEquals(listOf(1L, 2L, 3L), reminders.map { it.taskId })
        assertEquals(3, TaskReminderPlan.dueBetween(reminders, deliveredThrough = release - 1, now = release).size)
    }

    @Test
    fun anAlreadyMissedReminderIsStillDiscardedWhenQuietHoursAreOn() {
        // Holding must not resurrect what the grace window already discarded. A reminder owed at
        // 14:30 is outside the window, so it is never held, and a device that comes back a day later
        // still drops it rather than announcing yesterday.
        val moment = at(2026, 8, 21, 14, 30)
        val reminders = TaskReminderPlan.reminders(listOf(candidate(startAt = moment)), zone, night)
        val now = moment + TaskReminderPlan.DeliveryGraceMillis + 1
        assertTrue(TaskReminderPlan.dueBetween(reminders, deliveredThrough = moment - 1, now = now).isEmpty())
    }
}
