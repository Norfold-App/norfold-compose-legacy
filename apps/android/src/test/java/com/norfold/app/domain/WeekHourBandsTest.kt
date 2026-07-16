package com.norfold.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WeekHourBandsTest {

    private fun week(vararg days: List<Int>): List<List<Int>> =
        (days.toList() + List(7 - days.size) { emptyList<Int>() }).take(7)

    @Test
    fun noTimedTasksCollapsesToSingleLabeledBand() {
        val bands = WeekHourBands.computeHourBands(week())
        assertEquals(1, bands.size)
        assertEquals(0, bands[0].startHour)
        assertEquals(24, bands[0].endHour)
        assertFalse(bands[0].busy)
        assertEquals(WeekHourBands.NO_TIMED_TASKS_LABEL, bands[0].label)
    }

    @Test
    fun bandsCoverFullDayWithoutGapsOrOverlaps() {
        val bands = WeekHourBands.computeHourBands(week(listOf(9), listOf(14, 15), listOf(20)))
        assertEquals(0, bands.first().startHour)
        assertEquals(24, bands.last().endHour)
        bands.zipWithNext().forEach { (a, b) -> assertEquals(a.endHour, b.startHour) }
        bands.forEach { assertTrue(it.startHour < it.endHour) }
    }

    @Test
    fun busyBandBoundariesSnapToTaskStarts() {
        val bands = WeekHourBands.computeHourBands(week(listOf(9)))
        val busy = bands.filter { it.busy }
        assertEquals(1, busy.size)
        assertEquals(9, busy[0].startHour)
        // Empty stretches before and after collapse into single non-busy bands.
        assertEquals(3, bands.size)
        assertFalse(bands[0].busy)
        assertEquals(0, bands[0].startHour)
        assertEquals(9, bands[0].endHour)
        assertFalse(bands[2].busy)
        assertEquals(24, bands[2].endHour)
    }

    @Test
    fun granularityCoarseWhenAtMostTwoTasksPerDay() {
        // maxPerDay = 2 → 3h granularity: starts 9 and 11 merge into one band.
        val bands = WeekHourBands.computeHourBands(week(listOf(9, 11)))
        val busy = bands.filter { it.busy }
        assertEquals(1, busy.size)
        assertEquals(9, busy[0].startHour)
        assertEquals(12, busy[0].endHour)
    }

    @Test
    fun granularityMediumForThreeToFiveTasksPerDay() {
        // maxPerDay = 3 → 2h granularity: 9 and 11 no longer merge (11 !in 9..<11).
        val bands = WeekHourBands.computeHourBands(week(listOf(9, 11, 16)))
        val busyStarts = bands.filter { it.busy }.map { it.startHour }
        assertEquals(listOf(9, 11, 16), busyStarts)
    }

    @Test
    fun granularityFineForSixOrMoreTasksPerDay() {
        // maxPerDay = 6 → 1h granularity: adjacent hours each get their own band.
        val bands = WeekHourBands.computeHourBands(week(listOf(8, 9, 10, 11, 12, 13)))
        val busy = bands.filter { it.busy }
        assertEquals(6, busy.size)
        busy.forEach { assertEquals(it.startHour + 1, it.endHour) }
    }

    @Test
    fun outOfRangeHoursIgnored() {
        val bands = WeekHourBands.computeHourBands(week(listOf(-1, 24, 25)))
        assertEquals(1, bands.size)
        assertEquals(WeekHourBands.NO_TIMED_TASKS_LABEL, bands[0].label)
    }

    @Test
    fun lateNightTaskClampsBandToMidnight() {
        val bands = WeekHourBands.computeHourBands(week(listOf(23)))
        val busy = bands.filter { it.busy }.single()
        assertEquals(23, busy.startHour)
        assertEquals(24, busy.endHour)
        assertEquals(24, bands.last().endHour)
    }

    @Test
    fun hourLabelsUseTwelveHourClock() {
        assertEquals("12 AM", WeekHourBands.hourLabel(0))
        assertEquals("9 AM", WeekHourBands.hourLabel(9))
        assertEquals("12 PM", WeekHourBands.hourLabel(12))
        assertEquals("11 PM", WeekHourBands.hourLabel(23))
    }
}
