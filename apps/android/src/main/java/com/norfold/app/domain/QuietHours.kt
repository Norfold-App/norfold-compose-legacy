package com.norfold.app.domain

import java.util.Calendar
import java.util.TimeZone

/**
 * The quiet-hours window, decided without touching Android.
 *
 * Settings has stored `quietHoursEnabled`, `quietHoursStart` and `quietHoursEnd` since schema 38,
 * the columns are migrated and carried through the repository, and until now nothing read any of
 * them. Worse, the notification permission dialog told the owner "quiet-hours settings still
 * apply" — a promise the app was not keeping. This is the arithmetic half of keeping it.
 *
 * ## Why a suppressed reminder is held rather than dropped
 *
 * Quiet hours mean *do not wake me*, not *cancel this*. A reminder for 02:00 exists because the
 * owner asked to be reminded; the window only says the small hours are the wrong moment to say so.
 * Dropping it would silently delete a thing they asked for, and would do it precisely for the
 * reminders set at the times they most wanted help remembering. So [releaseAt] moves the delivery
 * to the moment the window ends, and a held reminder announces itself at 07:00 with its own
 * original time still in the body — the notification already says "Due Fri 21 Aug at 02:00", so a
 * held reminder is legible as late rather than looking like it was for breakfast.
 *
 * ## Why the window is stored as text and parsed here
 *
 * `"22:00"` is what the settings row holds and what a time picker round-trips. Parsing is total: a
 * value that is not `HH:mm` in range yields null, and a null window means *not quiet* rather than
 * *always quiet*. A corrupt string must not be able to silence every reminder the app owes, which
 * is the failure mode of treating an unparseable window as covering everything.
 *
 * ## Why midnight-crossing is the normal case
 *
 * The default is 22:00–07:00, so the window that wraps past midnight is not an edge case to bolt on
 * — it is what nearly every owner will have. `start > end` is therefore read as "crosses midnight"
 * rather than as invalid input, and [contains] is a union of two spans on those days.
 */
object QuietHours {
    private const val MinutesPerDay: Int = 24 * 60

    /**
     * A parsed quiet-hours window.
     *
     * [startMinute] is inclusive and [endMinute] is exclusive, so 22:00–07:00 is quiet at exactly
     * 22:00 and awake at exactly 07:00. A half-open interval is what makes [releaseAt] land
     * *outside* the window rather than on its last quiet minute, and it is why an empty window
     * (`start == end`) means nothing is quiet rather than everything is.
     */
    data class Window(val startMinute: Int, val endMinute: Int) {
        /** True when this window wraps past midnight, which the 22:00–07:00 default does. */
        val crossesMidnight: Boolean get() = startMinute > endMinute

        /** True when a window covers no minutes at all, which is what `start == end` describes. */
        val isEmpty: Boolean get() = startMinute == endMinute
    }

    /**
     * How wide a quiet-hours window is, in minutes, counting a wrapping window across midnight.
     *
     * Exposed so a settings surface can say "9 hours quiet" from the same arithmetic that decides
     * whether a reminder is held, rather than recomputing the wrap rule in the UI and disagreeing
     * with it.
     */
    fun widthMinutes(window: Window?): Int = when {
        window == null -> 0
        window.crossesMidnight -> MinutesPerDay - window.startMinute + window.endMinute
        else -> window.endMinute - window.startMinute
    }

    /**
     * How wide a window is, in words: `"9 hours"`, `"30 minutes"`, `"8 hours 30 minutes"`.
     *
     * Here rather than in the settings screen for the same reason [widthMinutes] is: a surface that
     * describes the window should not be able to describe it differently from the code that applies
     * it. The empty string for a null window lets a caller concatenate without branching.
     */
    fun describeWidth(window: Window?): String {
        val total = widthMinutes(window)
        if (total <= 0) return ""
        val hours = total / 60
        val minutes = total % 60
        val hourText = if (hours > 0) "$hours ${if (hours == 1) "hour" else "hours"}" else ""
        val minuteText = if (minutes > 0) "$minutes ${if (minutes == 1) "minute" else "minutes"}" else ""
        return listOf(hourText, minuteText).filter { it.isNotEmpty() }.joinToString(" ")
    }

    /**
     * The `HH:mm` values a picker should offer, every [PickerStepMinutes] through the day.
     *
     * A list of formatted strings rather than a Material `TimePicker` because the stored columns are
     * text and the app's existing option-picker dialog already solves the accessibility of a list of
     * choices — one node per option carrying name, role and selection, inside a `selectableGroup`
     * that announces its position. A `TimePicker` would be the app's only one, and would have to
     * re-earn all of that.
     *
     * Half-hour granularity is what the setting is for: quiet hours are a bedtime, not an alarm. The
     * arithmetic accepts any `HH:mm` regardless, so a value restored from another device — or a
     * future picker with finer steps — is still parsed and honoured rather than snapped or rejected.
     */
    fun pickerOptions(): List<String> = (0 until MinutesPerDay step PickerStepMinutes).map(::formatMinuteOfDay)

    /**
     * `HH:mm` for a minute-of-day, always two digits so [parseMinuteOfDay] round-trips it.
     *
     * Not `SimpleDateFormat`: this is a time of day rather than an instant, so there is no zone and
     * no locale involved. Formatting it through a date formatter would introduce both, and a locale
     * that renders `7:00 am` would produce a string this file's own parser rejects.
     */
    fun formatMinuteOfDay(minuteOfDay: Int): String {
        val wrapped = ((minuteOfDay % MinutesPerDay) + MinutesPerDay) % MinutesPerDay
        val hour = wrapped / 60
        val minute = wrapped % 60
        return "${if (hour < 10) "0" else ""}$hour:${if (minute < 10) "0" else ""}$minute"
    }

    /** How far apart the offered picker values are. See [pickerOptions] for why this is coarse. */
    const val PickerStepMinutes: Int = 30

    /**
     * Parses `HH:mm`, returning null for anything that is not a real time of day.
     *
     * Deliberately strict about shape and lenient about nothing: `"7:00"`, `"07:0"`, `"24:00"`,
     * `"07:60"`, `""` and `"lunchtime"` are all null. The stored values come from a time picker, so
     * a malformed one means the row was hand-edited or corrupted, and the safe reading of corrupt
     * quiet hours is that there are none.
     */
    fun parseMinuteOfDay(text: String): Int? {
        val parts = text.split(':')
        if (parts.size != 2) return null
        if (parts[0].length != 2 || parts[1].length != 2) return null
        val hour = parts[0].toIntOrNull() ?: return null
        val minute = parts[1].toIntOrNull() ?: return null
        if (hour !in 0..23 || minute !in 0..59) return null
        return hour * 60 + minute
    }

    /**
     * The window the settings row describes, or null when reminders should never be held.
     *
     * Null covers all three ways quiet hours can be absent: switched off, unparseable, or a window
     * of zero width. Callers get one thing to check rather than three.
     */
    fun window(enabled: Boolean, start: String, end: String): Window? {
        if (!enabled) return null
        val startMinute = parseMinuteOfDay(start) ?: return null
        val endMinute = parseMinuteOfDay(end) ?: return null
        val window = Window(startMinute, endMinute)
        return if (window.isEmpty) null else window
    }

    /** True when [instant] falls inside [window] in [timeZone]. A null window is never quiet. */
    fun contains(window: Window?, instant: Long, timeZone: TimeZone = TimeZone.getDefault()): Boolean {
        if (window == null) return false
        val minute = minuteOfDay(instant, timeZone)
        return if (window.crossesMidnight) {
            minute >= window.startMinute || minute < window.endMinute
        } else {
            minute >= window.startMinute && minute < window.endMinute
        }
    }

    /**
     * The first moment at or after [instant] that is not quiet.
     *
     * Returns [instant] itself when it is already awake, so callers can apply this unconditionally
     * rather than branching. The result is found by walking the local calendar to the window's end
     * rather than by adding a duration, because a duration is wrong across a DST boundary: on the
     * night the clocks change, "nine hours after 22:00" is 06:00 or 08:00, and what the owner asked
     * for is 07:00 by whatever the clock says then.
     *
     * ## Why the nominal end is checked rather than trusted
     *
     * A spring-forward can delete the very minute the window ends. In `Europe/Lisbon` the clocks
     * jump 01:00 → 02:00 on 2026-03-29, so a 22:00–01:00 window has no 01:00 that night at all, and
     * asking `Calendar` for it yields 00:00 — a moment still inside the window. Releasing there
     * would hand the reminder back into the silence it was held out of, so the nominal end is a
     * first guess that is then advanced until it is genuinely outside the window and not behind
     * [instant].
     *
     * The stepping loop is a generalisation rather than a fix for an observed failure: a single
     * one-hour correction clears every case found by sweeping 2026 at quarter-hour granularity
     * across nine zones — including Lord Howe's 30-minute DST shift and Chatham's 45-minute offset
     * — against seven windows. It is written as a loop anyway because the bound is free and the
     * alternative is a fixed correction that happens to be large enough, which is a fact about the
     * current tz database rather than about this arithmetic. The loop terminates by construction: a
     * window is under 24 hours wide, so 48 half-hour steps cross it even if every one is quiet.
     */
    fun releaseAt(window: Window?, instant: Long, timeZone: TimeZone = TimeZone.getDefault()): Long {
        if (!contains(window, instant, timeZone)) return instant
        val end = requireNotNull(window).endMinute
        val calendar = Calendar.getInstance(timeZone).apply { timeInMillis = instant }
        // The end lands on tomorrow whenever it is not still ahead of us today. For a wrapping
        // window that is the usual case — at 23:00 inside 22:00–07:00, the 07:00 wanted is
        // tomorrow's — and for a same-day window it does not happen, because being inside the
        // window means the end is by definition still ahead.
        val endIsTomorrow = end <= minuteOfDay(instant, timeZone)
        calendar.set(Calendar.HOUR_OF_DAY, end / 60)
        calendar.set(Calendar.MINUTE, end % 60)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)
        if (endIsTomorrow) calendar.add(Calendar.DAY_OF_MONTH, 1)
        var released = calendar.timeInMillis
        var steps = 0
        while ((released < instant || contains(window, released, timeZone)) && steps < MaxReleaseSteps) {
            released += ReleaseStepMillis
            steps++
        }
        return released
    }

    /**
     * How far each correction step moves when the nominal end is unusable.
     *
     * Half an hour rather than an hour because some zones shift by 30 or 45 minutes, not 60, and a
     * step coarser than the shift can overshoot past the moment the owner asked for.
     */
    private const val ReleaseStepMillis: Long = 30 * 60 * 1000L

    /**
     * Enough steps to cross any window, since a window is under 24 hours wide.
     *
     * This is a termination guarantee, not a tuning knob. If it were ever reached the result would
     * be a moment still inside the window, so the bound is set far past any plausible need.
     */
    private const val MaxReleaseSteps: Int = 48

    /** Local minutes since midnight for an absolute instant. */
    private fun minuteOfDay(instant: Long, timeZone: TimeZone): Int =
        Calendar.getInstance(timeZone).apply { timeInMillis = instant }.let { calendar ->
            calendar.get(Calendar.HOUR_OF_DAY) * 60 + calendar.get(Calendar.MINUTE)
        }
}
