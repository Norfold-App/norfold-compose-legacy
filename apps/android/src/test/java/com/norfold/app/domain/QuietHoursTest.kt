package com.norfold.app.domain

import java.util.Calendar
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Quiet hours: the half that can be certified without a device.
 *
 * The stored window has had no reader at all, while the notification permission dialog claimed
 * "quiet-hours settings still apply". So there is no existing behaviour to regress against — every
 * one of these pins is establishing a contract rather than protecting one, and the two that matter
 * most are the ones a reasonable implementation gets wrong:
 *
 * - a window that wraps past midnight, which the 22:00–07:00 default does, so it is the normal case;
 * - a spring-forward that deletes the minute the window ends, which makes the obvious "set the
 *   calendar to 07:00" produce a moment still inside the window.
 */
class QuietHoursTest {
    private val london = TimeZone.getTimeZone("Europe/London")
    private val lisbon = TimeZone.getTimeZone("Europe/Lisbon")

    /** Shifts by 30 minutes rather than an hour, which is what makes it worth sweeping. */
    private val lordHowe = TimeZone.getTimeZone("Australia/Lord_Howe")

    private fun at(
        year: Int,
        month: Int,
        day: Int,
        hour: Int = 0,
        minute: Int = 0,
        zone: TimeZone = london,
    ): Long = Calendar.getInstance(zone).apply {
        clear()
        set(year, month - 1, day, hour, minute, 0)
    }.timeInMillis

    private fun localTime(instant: Long, zone: TimeZone = london): String =
        Calendar.getInstance(zone).apply { timeInMillis = instant }.let {
            "%04d-%02d-%02d %02d:%02d".format(
                it.get(Calendar.YEAR),
                it.get(Calendar.MONTH) + 1,
                it.get(Calendar.DAY_OF_MONTH),
                it.get(Calendar.HOUR_OF_DAY),
                it.get(Calendar.MINUTE),
            )
        }

    // ── Parsing ──
    // Total, and null-on-anything-odd. A corrupt window must mean "no quiet hours", never
    // "permanently quiet", because the latter silences every reminder the app owes.

    @Test
    fun parsesTheStoredDefaults() {
        assertEquals(22 * 60, QuietHours.parseMinuteOfDay("22:00"))
        assertEquals(7 * 60, QuietHours.parseMinuteOfDay("07:00"))
        assertEquals(0, QuietHours.parseMinuteOfDay("00:00"))
        assertEquals(23 * 60 + 59, QuietHours.parseMinuteOfDay("23:59"))
    }

    @Test
    fun rejectsEverythingThatIsNotATimeOfDay() {
        listOf("7:00", "07:0", "24:00", "07:60", "-1:00", "", "lunchtime", "07", "07:00:00", "0700")
            .forEach { assertNull("should not parse: $it", QuietHours.parseMinuteOfDay(it)) }
    }

    @Test
    fun aDisabledWindowIsNoWindow() {
        assertNull(QuietHours.window(enabled = false, start = "22:00", end = "07:00"))
    }

    @Test
    fun anUnparseableWindowIsNoWindowRatherThanAlwaysQuiet() {
        assertNull(QuietHours.window(enabled = true, start = "lunchtime", end = "07:00"))
        assertNull(QuietHours.window(enabled = true, start = "22:00", end = "25:00"))
    }

    @Test
    fun aZeroWidthWindowIsNoWindow() {
        // 22:00 to 22:00 is the shape a picker produces when someone sets both ends the same. Read
        // as a full day it would silence everything; read as empty it silences nothing. Empty is
        // the only reading that cannot lose a reminder.
        assertNull(QuietHours.window(enabled = true, start = "22:00", end = "22:00"))
    }

    // ── The wrapping window, which is the default ──

    @Test
    fun theDefaultWindowWrapsPastMidnight() {
        val window = QuietHours.window(enabled = true, start = "22:00", end = "07:00")!!
        assertTrue(window.crossesMidnight)
        assertEquals(9 * 60, QuietHours.widthMinutes(window))
    }

    @Test
    fun theDefaultWindowIsQuietOnBothSidesOfMidnight() {
        val window = QuietHours.window(enabled = true, start = "22:00", end = "07:00")!!
        assertTrue(QuietHours.contains(window, at(2026, 8, 21, 23, 0), london))
        assertTrue(QuietHours.contains(window, at(2026, 8, 22, 2, 0), london))
        assertTrue(QuietHours.contains(window, at(2026, 8, 22, 6, 59), london))
    }

    @Test
    fun theDefaultWindowIsAwakeDuringTheDay() {
        val window = QuietHours.window(enabled = true, start = "22:00", end = "07:00")!!
        assertFalse(QuietHours.contains(window, at(2026, 8, 21, 14, 30), london))
        assertFalse(QuietHours.contains(window, at(2026, 8, 21, 21, 59), london))
    }

    @Test
    fun theWindowIsHalfOpenSoItsEndIsAlreadyAwake() {
        val window = QuietHours.window(enabled = true, start = "22:00", end = "07:00")!!
        // Quiet at exactly 22:00, awake at exactly 07:00. This is what lets releaseAt land on the
        // end rather than one minute past it.
        assertTrue(QuietHours.contains(window, at(2026, 8, 21, 22, 0), london))
        assertFalse(QuietHours.contains(window, at(2026, 8, 22, 7, 0), london))
    }

    @Test
    fun aSameDayWindowDoesNotWrap() {
        val window = QuietHours.window(enabled = true, start = "13:00", end = "14:00")!!
        assertFalse(window.crossesMidnight)
        assertEquals(60, QuietHours.widthMinutes(window))
        assertTrue(QuietHours.contains(window, at(2026, 8, 21, 13, 30), london))
        assertFalse(QuietHours.contains(window, at(2026, 8, 21, 12, 30), london))
        assertFalse(QuietHours.contains(window, at(2026, 8, 21, 14, 30), london))
    }

    @Test
    fun aNullWindowIsNeverQuiet() {
        assertFalse(QuietHours.contains(null, at(2026, 8, 21, 3, 0), london))
    }

    // ── Release: where a held reminder actually lands ──

    @Test
    fun anAwakeMomentIsReturnedUnchanged() {
        val window = QuietHours.window(enabled = true, start = "22:00", end = "07:00")!!
        val moment = at(2026, 8, 21, 14, 30)
        assertEquals(moment, QuietHours.releaseAt(window, moment, london))
    }

    @Test
    fun aNullWindowNeverMovesAnything() {
        val moment = at(2026, 8, 21, 3, 0)
        assertEquals(moment, QuietHours.releaseAt(null, moment, london))
    }

    @Test
    fun aReminderBeforeMidnightIsHeldToTheNextMorning() {
        val window = QuietHours.window(enabled = true, start = "22:00", end = "07:00")!!
        val released = QuietHours.releaseAt(window, at(2026, 8, 21, 23, 15), london)
        assertEquals("2026-08-22 07:00", localTime(released))
    }

    @Test
    fun aReminderAfterMidnightIsHeldToTheSameMorningNotTheNext() {
        val window = QuietHours.window(enabled = true, start = "22:00", end = "07:00")!!
        // 02:00 on the 22nd is quiet because of the window that opened on the 21st, so the morning
        // it is waiting for is the 22nd's — not the 23rd's. Getting this wrong delays a reminder by
        // a full day, which is the failure that would be hardest to notice.
        val released = QuietHours.releaseAt(window, at(2026, 8, 22, 2, 0), london)
        assertEquals("2026-08-22 07:00", localTime(released))
    }

    @Test
    fun aReminderAtTheWindowsOpeningEdgeIsHeld() {
        val window = QuietHours.window(enabled = true, start = "22:00", end = "07:00")!!
        val released = QuietHours.releaseAt(window, at(2026, 8, 21, 22, 0), london)
        assertEquals("2026-08-22 07:00", localTime(released))
    }

    @Test
    fun aSameDayWindowReleasesLaterTheSameDay() {
        val window = QuietHours.window(enabled = true, start = "13:00", end = "14:00")!!
        val released = QuietHours.releaseAt(window, at(2026, 8, 21, 13, 30), london)
        assertEquals("2026-08-21 14:00", localTime(released))
    }

    @Test
    fun aReleasedMomentIsItselfNeverQuiet() {
        // The property that matters more than any single figure: whatever releaseAt returns must be
        // awake, or a held reminder would be released into the silence it was held out of. Swept
        // across every half hour of a day for the default window.
        val window = QuietHours.window(enabled = true, start = "22:00", end = "07:00")!!
        var minute = 0
        while (minute < 24 * 60) {
            val moment = at(2026, 8, 21, minute / 60, minute % 60)
            val released = QuietHours.releaseAt(window, moment, london)
            assertFalse(
                "released into quiet hours from ${localTime(moment)} -> ${localTime(released)}",
                QuietHours.contains(window, released, london),
            )
            assertTrue(
                "released before the moment it was held from: ${localTime(moment)} -> ${localTime(released)}",
                released >= moment,
            )
            minute += 30
        }
    }

    // ── Daylight saving, where the obvious implementation is wrong ──

    @Test
    fun springForwardDoesNotStrandAReminderInsideTheWindow() {
        // Europe/Lisbon jumps 01:00 to 02:00 on 2026-03-29, so a 22:00-01:00 window has no 01:00
        // that night. Asking Calendar for HOUR_OF_DAY=1 yields 00:00 — still inside the window — so
        // a release that trusted the nominal end would hand back a quiet moment. This is the case
        // that motivates checking it.
        val window = QuietHours.window(enabled = true, start = "22:00", end = "01:00")!!
        val moment = at(2026, 3, 28, 23, 30, lisbon)
        assertTrue(QuietHours.contains(window, moment, lisbon))
        val released = QuietHours.releaseAt(window, moment, lisbon)
        assertFalse(
            "released to ${localTime(released, lisbon)}, still inside quiet hours",
            QuietHours.contains(window, released, lisbon),
        )
        assertTrue(released > moment)
        // 01:00 does not exist, so the first awake minute is the moment the clocks land on.
        assertEquals("2026-03-29 02:00", localTime(released, lisbon))
    }

    @Test
    fun springForwardInsideTheWindowStillReleasesAtTheAskedHour() {
        // London jumps 01:00 to 02:00 the same night, but its 07:00 end is untouched, so the owner
        // gets exactly the hour they asked for even though the night was an hour shorter.
        val window = QuietHours.window(enabled = true, start = "22:00", end = "07:00")!!
        val released = QuietHours.releaseAt(window, at(2026, 3, 28, 23, 0, london), london)
        assertEquals("2026-03-29 07:00", localTime(released, london))
    }

    @Test
    fun fallBackReleasesAtTheAskedHourNotAnHourEarly() {
        // London falls back 02:00 to 01:00 on 2026-10-25. The night is 25 hours long; adding a
        // fixed nine-hour duration to 22:00 would release at 06:00 local. Walking the calendar
        // gives 07:00, which is what the owner set.
        val window = QuietHours.window(enabled = true, start = "22:00", end = "07:00")!!
        val released = QuietHours.releaseAt(window, at(2026, 10, 24, 23, 0, london), london)
        assertEquals("2026-10-25 07:00", localTime(released, london))
    }

    @Test
    fun releaseIsAwakeAcrossEveryHalfHourOfBothTransitionNights() {
        // The invariant, held across both transition directions and a 30-minute-shift zone, rather
        // than a reproduction of a known failure: the nominal-end check already clears every case
        // here. It is pinned because the arithmetic is easy to "simplify" into adding a fixed
        // duration, and that version does fail these.
        val cases = listOf(
            Triple(lisbon, QuietHours.window(true, "22:00", "01:00")!!, at(2026, 3, 28, 12, 0, lisbon)),
            Triple(london, QuietHours.window(true, "22:00", "07:00")!!, at(2026, 3, 28, 12, 0, london)),
            Triple(london, QuietHours.window(true, "22:00", "07:00")!!, at(2026, 10, 24, 12, 0, london)),
            Triple(london, QuietHours.window(true, "01:30", "02:30")!!, at(2026, 3, 28, 12, 0, london)),
            Triple(london, QuietHours.window(true, "01:30", "02:30")!!, at(2026, 10, 24, 12, 0, london)),
            Triple(lordHowe, QuietHours.window(true, "01:30", "02:30")!!, at(2026, 10, 3, 12, 0, lordHowe)),
            Triple(lordHowe, QuietHours.window(true, "22:00", "07:00")!!, at(2026, 4, 4, 12, 0, lordHowe)),
        )
        cases.forEach { (zone, window, dayStart) ->
            var offset = 0L
            while (offset < 36 * 60 * 60 * 1000L) {
                val moment = dayStart + offset
                val released = QuietHours.releaseAt(window, moment, zone)
                assertFalse(
                    "${zone.id} $window: ${localTime(moment, zone)} -> ${localTime(released, zone)} is quiet",
                    QuietHours.contains(window, released, zone),
                )
                assertTrue(
                    "${zone.id} $window: ${localTime(moment, zone)} -> ${localTime(released, zone)} went backwards",
                    released >= moment,
                )
                offset += 30 * 60 * 1000L
            }
        }
    }

    // ── What a settings surface reads ──
    // These exist so the screen that shows the window and the code that applies it cannot disagree.
    // A UI that formatted or measured the window itself would be free to drift from the arithmetic,
    // and the drift would be invisible: the screen would say "9 hours" while the scheduler held for
    // eight.

    @Test
    fun widthCountsAWrappingWindowAcrossMidnight() {
        assertEquals(9 * 60, QuietHours.widthMinutes(QuietHours.window(true, "22:00", "07:00")))
        assertEquals(60, QuietHours.widthMinutes(QuietHours.window(true, "13:00", "14:00")))
        assertEquals(0, QuietHours.widthMinutes(null))
    }

    @Test
    fun widthIsDescribedInHoursAndMinutes() {
        assertEquals("9 hours", QuietHours.describeWidth(QuietHours.window(true, "22:00", "07:00")))
        assertEquals("1 hour", QuietHours.describeWidth(QuietHours.window(true, "13:00", "14:00")))
        assertEquals("30 minutes", QuietHours.describeWidth(QuietHours.window(true, "13:00", "13:30")))
        assertEquals("8 hours 30 minutes", QuietHours.describeWidth(QuietHours.window(true, "22:30", "07:00")))
    }

    @Test
    fun noWindowIsDescribedAsNothingRatherThanZeroHours() {
        // Empty so a caller can concatenate it without first checking, and so the screen never shows
        // the words "0 minutes quiet" — which reads as a broken setting rather than an absent one.
        assertEquals("", QuietHours.describeWidth(null))
        assertEquals("", QuietHours.describeWidth(QuietHours.window(true, "07:00", "07:00")))
    }

    @Test
    fun everyPickerOptionParsesBackToTheMinuteItNames() {
        // The round trip is the whole contract: the picker writes one of these strings into the
        // settings row, and the scheduler parses it back. A formatter that emitted "7:00" or "07:00 am"
        // would render a picker that silently disables quiet hours on selection.
        val options = QuietHours.pickerOptions()
        assertEquals(48, options.size)
        assertEquals("00:00", options.first())
        assertEquals("23:30", options.last())
        options.forEachIndexed { index, option ->
            assertEquals(option, index * QuietHours.PickerStepMinutes, QuietHours.parseMinuteOfDay(option))
        }
    }

    @Test
    fun theStoredDefaultsAreOfferedByThePicker() {
        // 22:00 and 07:00 are the column defaults. If the picker did not offer them, a first visit
        // would show a window the list cannot represent, and any selection would move it.
        assertTrue(QuietHours.pickerOptions().contains("22:00"))
        assertTrue(QuietHours.pickerOptions().contains("07:00"))
    }

    @Test
    fun formattingPadsBothFieldsToTwoDigits() {
        assertEquals("00:00", QuietHours.formatMinuteOfDay(0))
        assertEquals("07:05", QuietHours.formatMinuteOfDay(7 * 60 + 5))
        assertEquals("23:59", QuietHours.formatMinuteOfDay(23 * 60 + 59))
    }

    @Test
    fun aWindowNotOnTheHalfHourIsStillHonoured() {
        // The picker is coarse; the arithmetic is not. A window restored from another device, or set
        // before the picker's step changed, must be applied as written rather than snapped.
        val window = QuietHours.window(true, "22:17", "06:43")!!
        assertTrue(QuietHours.contains(window, at(2026, 8, 22, 2, 0), london))
        assertEquals(at(2026, 8, 22, 6, 43), QuietHours.releaseAt(window, at(2026, 8, 22, 2, 0), london))
    }
}
