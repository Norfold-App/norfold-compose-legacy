package com.norfold.app.domain

/**
 * Pure layout math for the adaptive week-view hour rail.
 *
 * The week grid does not render all 24 hours at a fixed height; instead the day
 * is split into [Band]s: stretches without any timed task collapse into one
 * compact band, while busy stretches get full-height bands whose length is
 * bounded by a granularity derived from the busiest day
 * (≤2 tasks → 3h chunks, 3–5 → 2h, ≥6 → 1h). Band boundaries snap to task
 * start hours so a chip is never split across bands.
 */
object WeekHourBands {

    /**
     * One vertical slice of the week grid covering [startHour] until
     * [endHour] (exclusive). [busy] bands render tall (~56dp) and hold event
     * chips; non-busy bands render collapsed (~24dp). A week with no timed
     * tasks yields a single non-busy 0–24 band labeled "No timed tasks".
     */
    data class Band(val startHour: Int, val endHour: Int, val busy: Boolean, val label: String)

    const val NO_TIMED_TASKS_LABEL = "No timed tasks"

    /**
     * @param taskStartHoursPerDay start hours (0–23) of timed tasks for each
     *   of the 7 visible days; index = day offset in the week. Hours outside
     *   0–23 are ignored.
     */
    fun computeHourBands(taskStartHoursPerDay: List<List<Int>>): List<Band> {
        val starts = taskStartHoursPerDay.flatten().filter { it in 0..23 }.distinct().sorted()
        if (starts.isEmpty()) {
            return listOf(Band(0, 24, busy = false, label = NO_TIMED_TASKS_LABEL))
        }
        val maxPerDay = taskStartHoursPerDay.maxOf { day -> day.count { it in 0..23 } }
        val granularity = when {
            maxPerDay <= 2 -> 3
            maxPerDay <= 5 -> 2
            else -> 1
        }
        val bands = mutableListOf<Band>()
        var cursor = 0
        var i = 0
        while (i < starts.size) {
            val bandStart = starts[i]
            if (bandStart > cursor) {
                bands += Band(cursor, bandStart, busy = false, label = hourLabel(cursor))
            }
            // Swallow subsequent starts that fall inside this band's granularity window.
            var j = i
            while (j + 1 < starts.size && starts[j + 1] < bandStart + granularity) j++
            val bandEnd = (starts[j] + 1).coerceAtLeast(bandStart + 1).coerceAtMost(24)
            bands += Band(bandStart, bandEnd, busy = true, label = hourLabel(bandStart))
            cursor = bandEnd
            i = j + 1
        }
        if (cursor < 24) {
            bands += Band(cursor, 24, busy = false, label = hourLabel(cursor))
        }
        return bands
    }

    fun hourLabel(hour: Int): String = when {
        hour == 0 -> "12 AM"
        hour < 12 -> "$hour AM"
        hour == 12 -> "12 PM"
        else -> "${hour - 12} PM"
    }
}
