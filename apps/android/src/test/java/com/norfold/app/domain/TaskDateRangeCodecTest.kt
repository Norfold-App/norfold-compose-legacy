package com.norfold.app.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class TaskDateRangeCodecTest {
    @Test
    fun rangeRoundTripsAndNormalizes() {
        val encoded = TaskDateRangeCodec.encode(TaskDateRange(2_000L, 1_000L, allDay = false, reminderMinutesBefore = 60))
        assertEquals(TaskDateRange(1_000L, 2_000L, false, 60), TaskDateRangeCodec.decode(encoded))
    }

    @Test
    fun oldSingleTimestampBecomesSingleDayRange() {
        assertEquals(TaskDateRange(42L, 42L), TaskDateRangeCodec.decode("42"))
    }
}
