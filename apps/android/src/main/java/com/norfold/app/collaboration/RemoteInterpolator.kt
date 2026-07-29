package com.norfold.app.collaboration

/**
 * Local interpolation of remote geometry. Network frequency != render frequency: previews arrive
 * at ~6-20 Hz, but remote cursors/objects should render smoothly at the display refresh rate
 * (e.g. 60-120 Hz). This buffer interpolates between the last two received samples.
 *
 * Provider-independent and pure. The editor supplies wall-clock render timestamps; this returns
 * an interpolated value. It intentionally holds no editor-schema assumptions — geometry is a
 * generic vector of doubles (x, y, rotation, scale, …) so the future editor can define its own
 * channel meaning without changing this class.
 */
class RemoteInterpolator(
    /** Render-time offset behind the newest sample, to always have two samples to interpolate. */
    private val interpolationDelayMs: Long = 80,
) {
    private data class Sample(val at: Long, val values: DoubleArray)

    private val tracks = HashMap<String, ArrayDeque<Sample>>()

    /** Feed a newly received remote sample for [trackKey] (e.g. actorId+targetId+channel). */
    fun submit(trackKey: String, receivedAt: Long, values: DoubleArray) {
        val q = tracks.getOrPut(trackKey) { ArrayDeque() }
        q.addLast(Sample(receivedAt, values))
        // Keep a small window; we only need the two bracketing the render time.
        while (q.size > 8) q.removeFirst()
    }

    /**
     * Interpolated geometry for [trackKey] at wall-clock [renderNowMs]. Returns null if there is
     * not yet enough data. Renders at (renderNowMs - interpolationDelayMs) to stay between samples.
     */
    fun sample(trackKey: String, renderNowMs: Long): DoubleArray? {
        val q = tracks[trackKey] ?: return null
        if (q.isEmpty()) return null
        // Need at least two samples before we return anything
        if (q.size < 2) return null
        val target = renderNowMs - interpolationDelayMs

        var older: Sample? = null
        var newer: Sample? = null
        for (s in q) {
            if (s.at <= target) older = s
            if (s.at >= target && newer == null) newer = s
        }
        return when {
            older != null && newer != null && newer.at != older.at -> {
                val t = ((target - older.at).toDouble() / (newer.at - older.at)).coerceIn(0.0, 1.0)
                lerp(older.values, newer.values, t)
            }
            older != null && newer != null -> older.values // same timestamp
            older != null -> older.values                  // target beyond all samples: hold last
            else -> null                                   // target before all samples
        }
    }

    fun forget(trackKey: String) { tracks.remove(trackKey) }
    fun reset() = tracks.clear()

    private fun lerp(a: DoubleArray, b: DoubleArray, t: Double): DoubleArray {
        val n = minOf(a.size, b.size)
        return DoubleArray(n) { a[it] + (b[it] - a[it]) * t }
    }
}
