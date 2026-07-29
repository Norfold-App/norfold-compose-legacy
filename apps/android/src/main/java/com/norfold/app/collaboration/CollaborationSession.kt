package com.norfold.app.collaboration

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

/**
 * Ties together one transport, the adaptive QoS controller, preview coalescer, and offline
 * outbox for a single artifact room. One session per open artifact.
 *
 * Responsibilities:
 * - Route outbound ops through the coalescer (EPHEMERAL_LATEST) or directly (DURABLE/EVENT).
 * - Drive the coalescer poll ticker at the active profile's preview rate.
 * - Drain the offline outbox on reconnect.
 * - Expose inbound envelopes and lifecycle events from the transport.
 *
 * Does NOT own editor schema, CRDT state, or key management — those live above this class.
 */
class CollaborationSession(
    val transport: RealtimeCollaborationTransport,
    val adaptiveSync: AdaptiveSyncController = AdaptiveSyncController(),
    private val crypto: CollabEnvelopeCrypto = ManagedCollabEnvelopeCrypto,
    private val capabilities: CollabCapabilities = CollabCapabilities.BETA,
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val coalescer = PreviewCoalescer()
    private val outbox = OfflineOutbox()
    private val seqCounter = AtomicLong(System.currentTimeMillis()) // monotonic per session
    private var tickerJob: Job? = null

    val state: StateFlow<TransportState> get() = transport.state
    val inbound: Flow<InboundEnvelope> get() = transport.inbound
    val lifecycle: Flow<TransportLifecycleEvent> get() = transport.lifecycle

    suspend fun connect(roomId: RoomId, actorId: ActorId, tokenProvider: CollabTokenProvider) {
        transport.connect(roomId, actorId, tokenProvider)
        startCoalescerTicker()
        scope.launch { drainOutboxOnConnect() }
    }

    /**
     * Submit an outbound envelope. DURABLE ops are queued in the offline outbox so they survive
     * a transient disconnect; EPHEMERAL_* are dropped if the transport is not connected.
     */
    suspend fun send(
        roomId: RoomId,
        actorId: ActorId,
        eventClass: CollabEventClass,
        eventType: String,
        targetId: String? = null,
        payload: kotlinx.serialization.json.JsonElement? = null,
    ) {
        if (!capabilities.enabled) return
        if (eventClass == CollabEventClass.EPHEMERAL_LATEST && !adaptiveSync.profile.value.sendPreviews) return

        val envelope = OutboundEnvelope(
            roomId = roomId,
            actorId = actorId,
            eventClass = eventClass,
            eventType = eventType,
            targetId = targetId,
            seq = CollabSeq(seqCounter.incrementAndGet()),
            payload = payload,
        )

        val toSend = coalescer.offer(envelope, System.currentTimeMillis())
        for (e in toSend) dispatchOrQueue(e)
    }

    private suspend fun dispatchOrQueue(envelope: OutboundEnvelope) {
        if (transport.state.value == TransportState.CONNECTED) {
            transport.send(envelope)
        } else if (envelope.eventClass == CollabEventClass.DURABLE) {
            outbox.enqueue(envelope)
        }
        // EPHEMERAL_* dropped when disconnected — by design
    }

    private fun startCoalescerTicker() {
        tickerJob?.cancel()
        tickerJob = scope.launch {
            while (true) {
                val profile = adaptiveSync.profile.value
                val interval = if (profile.sendPreviews && profile.previewHz > 0)
                    profile.previewIntervalMs else 100L
                delay(interval)
                val flushed = coalescer.poll(profile, System.currentTimeMillis())
                for (e in flushed) {
                    if (transport.state.value == TransportState.CONNECTED) transport.send(e)
                }
            }
        }
    }

    private suspend fun drainOutboxOnConnect() {
        transport.state.collect { s ->
            if (s == TransportState.CONNECTED) {
                val pending = outbox.drainAll()
                for (e in pending) transport.send(e)
            }
        }
    }

    fun updateSituation(situation: CollabSituation) = adaptiveSync.update(situation)

    fun metricsSnapshot() = transport.metricsSnapshot()
    val previewsDropped: Long get() = coalescer.previewsDropped

    suspend fun close() {
        tickerJob?.cancel()
        transport.close()
        scope.cancel()
    }
}
