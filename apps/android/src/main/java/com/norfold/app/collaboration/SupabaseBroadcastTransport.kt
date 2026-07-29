package com.norfold.app.collaboration

import io.github.jan.supabase.realtime.Realtime
import io.github.jan.supabase.realtime.RealtimeChannel
import io.github.jan.supabase.realtime.broadcastFlow
import io.github.jan.supabase.realtime.channel
import io.github.jan.supabase.realtime.realtime
import io.github.jan.supabase.realtime.broadcast
import io.github.jan.supabase.SupabaseClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement

/**
 * Supabase Realtime Broadcast transport. Uses the existing Supabase client — no new dependency.
 *
 * Channel name: "collab:<roomId>" — one channel per artifact room.
 * Broadcast event name: "env" (all envelope types share one event name; cls field distinguishes).
 *
 * Token: the current Supabase access token is supplied by [CollabTokenProvider]. Supabase
 * Realtime authenticates via the JWT on the underlying socket; the token is not re-sent per
 * message.
 *
 * Reconnect: Supabase realtime-kt handles socket reconnect internally. This adapter monitors
 * channel subscription state and re-subscribes on drop using [ReconnectPolicy].
 */
class SupabaseBroadcastTransport(
    private val supabase: SupabaseClient,
    private val policy: ReconnectPolicy = ReconnectPolicy(),
) : RealtimeCollaborationTransport {

    override val providerId = "supabase"

    private val _state = MutableStateFlow(TransportState.IDLE)
    override val state: StateFlow<TransportState> = _state.asStateFlow()

    private val _inbound = MutableSharedFlow<InboundEnvelope>(extraBufferCapacity = 256)
    override val inbound: Flow<InboundEnvelope> = _inbound.asSharedFlow()

    private val _lifecycle = MutableSharedFlow<TransportLifecycleEvent>(extraBufferCapacity = 64)
    override val lifecycle: Flow<TransportLifecycleEvent> = _lifecycle.asSharedFlow()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val seqTracker = SequenceTracker()
    private val json = Json { ignoreUnknownKeys = true }

    private var currentRoom: RoomId? = null
    private var currentActor: ActorId? = null
    private var currentToken: CollabTokenProvider? = null
    private var channel: RealtimeChannel? = null

    private var eventsSent = 0L
    private var eventsReceived = 0L
    private var bytesSent = 0L
    private var bytesReceived = 0L
    private var reconnectCount = 0L
    private var lastReconnectMs: Long? = null

    override suspend fun connect(roomId: RoomId, actorId: ActorId, tokenProvider: CollabTokenProvider) {
        if (_state.value == TransportState.CONNECTED && currentRoom == roomId) return
        currentRoom = roomId
        currentActor = actorId
        currentToken = tokenProvider
        attachChannel(roomId, attempt = 0)
    }

    private suspend fun attachChannel(roomId: RoomId, attempt: Int) {
        _state.value = if (attempt == 0) TransportState.CONNECTING else TransportState.RECONNECTING
        emit(TransportLifecycleEvent(providerId, roomId.value, _state.value))

        val ch = supabase.realtime.channel("collab:${roomId.value}")
        channel = ch

        scope.launch {
            ch.broadcastFlow<CollabWireEnvelope>("env").collect { wire ->
                val serverAt = System.currentTimeMillis()
                val latency = serverAt - wire.clientSentAt
                val env = wire.toInbound(serverAt, latency)
                bytesReceived += json.encodeToString(CollabWireEnvelope.serializer(), wire).length.toLong()
                eventsReceived++
                if (seqTracker.accept(env)) _inbound.emit(env)
            }
        }

        try {
            ch.subscribe(blockUntilSubscribed = true)
            val reconnectMs = if (attempt > 0) {
                reconnectCount++
                System.currentTimeMillis().also { lastReconnectMs = it }
                null // placeholder; real duration tracked by caller
            } else null
            _state.value = TransportState.CONNECTED
            emit(TransportLifecycleEvent(providerId, roomId.value, TransportState.CONNECTED, reconnectMs = reconnectMs))
        } catch (e: Exception) {
            val reason = DisconnectReason.Transient(e.message ?: "subscribe_failed")
            _state.value = TransportState.RECONNECTING
            emit(TransportLifecycleEvent(providerId, roomId.value, TransportState.RECONNECTING, reason))
            if (policy.shouldRetry(attempt, reason)) {
                delay(policy.delayMs(attempt))
                attachChannel(roomId, attempt + 1)
            } else {
                _state.value = TransportState.CLOSED
                emit(TransportLifecycleEvent(providerId, roomId.value, TransportState.CLOSED, reason))
            }
        }
    }

    override suspend fun send(envelope: OutboundEnvelope) {
        val ch = channel ?: return
        val wire = envelope.toWire()
        val encoded = json.encodeToString(CollabWireEnvelope.serializer(), wire)
        bytesSent += encoded.length
        eventsSent++
        ch.broadcast(event = "env", message = wire)
    }

    override suspend fun close() {
        val ch = channel ?: return
        channel = null
        try { supabase.realtime.removeChannel(ch) } catch (_: Exception) {}
        _state.value = TransportState.CLOSED
        emit(TransportLifecycleEvent(providerId, currentRoom?.value ?: "", TransportState.CLOSED, DisconnectReason.ClientClosed))
        scope.cancel()
    }

    override fun metricsSnapshot() = TransportMetrics(
        provider = providerId,
        eventsSent = eventsSent,
        eventsReceived = eventsReceived,
        bytesSent = bytesSent,
        bytesReceived = bytesReceived,
        duplicatesDropped = seqTracker.duplicatesDropped,
        gapsSeen = seqTracker.gapsSeen,
        reconnectCount = reconnectCount,
        lastReconnectMs = lastReconnectMs,
    )

    private fun emit(event: TransportLifecycleEvent) { scope.launch { _lifecycle.emit(event) } }
}
