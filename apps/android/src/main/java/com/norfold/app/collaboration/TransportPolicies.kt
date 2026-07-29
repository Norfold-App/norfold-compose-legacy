package com.norfold.app.collaboration

import kotlin.math.min
import kotlin.random.Random

/**
 * Deterministic exponential backoff with full jitter, shared by all transports so reconnect
 * behavior is identical across providers and does not bias the benchmark.
 *
 * delay(n) = random(0, min(cap, base * 2^n))
 */
class ReconnectPolicy(
    private val baseMs: Long = 250,
    private val capMs: Long = 15_000,
    private val maxAttempts: Int = Int.MAX_VALUE,
    private val random: Random = Random.Default,
) {
    fun shouldRetry(attempt: Int, reason: DisconnectReason): Boolean {
        if (attempt >= maxAttempts) return false
        return when (reason) {
            is DisconnectReason.Transient -> true
            is DisconnectReason.AuthRejected -> attempt == 0 // one retry: token may just need refresh
            is DisconnectReason.ClientClosed -> false
            is DisconnectReason.ServerClosed -> false
        }
    }

    fun delayMs(attempt: Int): Long {
        val exp = if (attempt >= 30) capMs else min(capMs, baseMs shl attempt)
        return random.nextLong(0, exp.coerceAtLeast(1))
    }
}

/**
 * Per-actor sequence tracking for deduplication and gap detection. Shared logic so every
 * transport dedups identically.
 *
 * DURABLE gaps are reported (the merge engine must reconcile). EPHEMERAL_LATEST gaps are
 * expected and ignored (older previews are meant to be dropped).
 */
class SequenceTracker {
    private val lastSeqByActor = HashMap<String, Long>()
    var duplicatesDropped: Long = 0; private set
    var gapsSeen: Long = 0; private set

    /**
     * @return true if the envelope is fresh and should be delivered; false if it is a duplicate
     *   or an obsolete EPHEMERAL_LATEST that a newer seq already superseded.
     */
    fun accept(env: InboundEnvelope): Boolean {
        val key = env.actorId.value + "|" + env.eventType + "|" + (env.targetId ?: "")
        val last = lastSeqByActor[key]
        val seq = env.seq.value

        if (last != null) {
            if (seq <= last) {
                // For EPHEMERAL_LATEST an out-of-order/older event is simply obsolete.
                // For DURABLE/EPHEMERAL_EVENT a repeat is a duplicate.
                duplicatesDropped++
                return false
            }
            if (seq > last + 1 && env.eventClass == CollabEventClass.DURABLE) {
                gapsSeen++
            }
        }
        lastSeqByActor[key] = seq
        return true
    }

    fun reset() {
        lastSeqByActor.clear()
        duplicatesDropped = 0
        gapsSeen = 0
    }
}
