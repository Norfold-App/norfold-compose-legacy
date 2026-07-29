package com.norfold.app.collaboration

/**
 * Coalesces EPHEMERAL_LATEST previews before they reach the transport.
 *
 * Rules:
 * - Only EPHEMERAL_LATEST is coalescible. DURABLE and EPHEMERAL_EVENT pass through untouched
 *   and immediately (they must never be dropped or delayed by coalescing).
 * - Latest-state previews are keyed by (actorId, eventType, targetId). A newer preview replaces
 *   an unsent older one for the same key.
 * - Emission is rate-limited by [AdaptiveSyncProfile.previewIntervalMs]. On each tick, the newest
 *   pending preview per key is flushed; intermediate obsolete previews are dropped.
 *
 * This class is transport-agnostic and holds only pending state; the caller drives [poll] on a
 * cadence (e.g. a coroutine ticker) and forwards results to the transport.
 */
class PreviewCoalescer {
    private data class Pending(val envelope: OutboundEnvelope, val enqueuedAt: Long)

    private val pending = LinkedHashMap<String, Pending>()
    private val lastFlushAtByKey = HashMap<String, Long>()

    var previewsDropped: Long = 0; private set
    var previewsFlushed: Long = 0; private set

    private fun key(env: OutboundEnvelope) =
        env.actorId.value + "|" + env.eventType + "|" + (env.targetId ?: "")

    /**
     * Offer an outbound envelope. Returns a list of envelopes to send RIGHT NOW:
     * - DURABLE / EPHEMERAL_EVENT: returned immediately (single-element list).
     * - EPHEMERAL_LATEST: buffered; returns empty. It will surface via [poll].
     */
    fun offer(env: OutboundEnvelope, nowMs: Long): List<OutboundEnvelope> {
        if (env.eventClass != CollabEventClass.EPHEMERAL_LATEST) {
            return listOf(env)
        }
        val k = key(env)
        val existing = pending.put(k, Pending(env, nowMs))
        if (existing != null) previewsDropped++ // replaced an unsent obsolete preview
        return emptyList()
    }

    /**
     * Flush previews whose per-key rate window has elapsed under [profile]. Returns the envelopes
     * to send now. Call this on a ticker at least as fast as the highest previewHz.
     */
    fun poll(profile: AdaptiveSyncProfile, nowMs: Long): List<OutboundEnvelope> {
        if (!profile.sendPreviews || pending.isEmpty()) {
            if (!profile.sendPreviews) {
                // Mode says no previews: drop everything buffered.
                previewsDropped += pending.size
                pending.clear()
            }
            return emptyList()
        }
        val interval = profile.previewIntervalMs
        val out = ArrayList<OutboundEnvelope>()
        val it = pending.entries.iterator()
        while (it.hasNext()) {
            val (k, p) = it.next()
            val lastFlush = lastFlushAtByKey[k] ?: 0L
            if (nowMs - lastFlush >= interval) {
                out.add(p.envelope)
                lastFlushAtByKey[k] = nowMs
                previewsFlushed++
                it.remove()
            }
        }
        return out
    }

    fun reset() {
        pending.clear()
        lastFlushAtByKey.clear()
        previewsDropped = 0
        previewsFlushed = 0
    }
}
