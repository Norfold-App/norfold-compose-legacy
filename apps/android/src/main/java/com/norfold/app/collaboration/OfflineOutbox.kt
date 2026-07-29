package com.norfold.app.collaboration

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Crash-safe local outbox for DURABLE operations that could not be sent while disconnected.
 * Durable ops enter here when the transport is not CONNECTED; they are drained in order on
 * reconnect. EPHEMERAL_* are never queued here — they are dropped when offline by design.
 *
 * This is an in-memory implementation. A production version would persist to Room so ops survive
 * process death; the interface is the same.
 */
class OfflineOutbox(private val maxSize: Int = 1024) {
    private val queue = ArrayDeque<OutboundEnvelope>()
    private val mutex = Mutex()

    suspend fun enqueue(envelope: OutboundEnvelope) {
        require(envelope.eventClass == CollabEventClass.DURABLE) {
            "Only DURABLE ops may be queued in the offline outbox."
        }
        mutex.withLock {
            if (queue.size >= maxSize) {
                // Oldest op dropped — this is a degraded state; the merge engine must reconcile.
                queue.removeFirst()
            }
            queue.addLast(envelope)
        }
    }

    /** Atomically drain and return all pending ops in insertion order. */
    suspend fun drainAll(): List<OutboundEnvelope> = mutex.withLock {
        val out = queue.toList()
        queue.clear()
        out
    }

    suspend fun size(): Int = mutex.withLock { queue.size }
}
