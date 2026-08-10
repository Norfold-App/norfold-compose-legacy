package com.norfold.app.collaboration

import io.github.jan.supabase.realtime.Realtime
import io.github.jan.supabase.realtime.RealtimeChannel
import io.github.jan.supabase.realtime.broadcastFlow
import io.github.jan.supabase.realtime.channel
import io.github.jan.supabase.realtime.realtime
import io.github.jan.supabase.realtime.broadcast
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest
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
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

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
    private var reconnectStartedAt = 0L

    override suspend fun connect(roomId: RoomId, actorId: ActorId, tokenProvider: CollabTokenProvider) {
        if (_state.value == TransportState.CONNECTED && currentRoom == roomId) return
        currentRoom = roomId
        currentActor = actorId
        currentToken = tokenProvider
        attachChannel(roomId, attempt = 0)
    }

    private suspend fun attachChannel(roomId: RoomId, attempt: Int) {
        var currentAttempt = attempt
        while (true) {
            if (currentAttempt > 0 && reconnectStartedAt == 0L) reconnectStartedAt = System.currentTimeMillis()
            _state.value = if (currentAttempt == 0) TransportState.CONNECTING else TransportState.RECONNECTING
            emit(TransportLifecycleEvent(providerId, roomId.value, _state.value))

            val token = try {
                requireNotNull(currentToken).freshToken(roomId)
            } catch (error: Exception) {
                val reason = DisconnectReason.AuthRejected(error.message ?: "token_refresh_failed")
                _state.value = TransportState.RECONNECTING
                emit(TransportLifecycleEvent(providerId, roomId.value, TransportState.RECONNECTING, reason))
                if (!policy.shouldRetry(currentAttempt, reason)) {
                    _state.value = TransportState.CLOSED
                    emit(TransportLifecycleEvent(providerId, roomId.value, TransportState.CLOSED, reason))
                    return
                }
                delay(policy.delayMs(currentAttempt))
                currentAttempt++
                continue
            }
            supabase.realtime.setAuth(token)
            val ch = supabase.realtime.channel(roomId.value) {
                isPrivate = !roomId.isBenchmark
                broadcast {
                    acknowledgeBroadcasts = true
                    receiveOwnBroadcasts = roomId.isBenchmark
                }
            }
            channel = ch
            val collector = scope.launch {
                ch.broadcastFlow<CollabWireEnvelope>("env").collect { wire ->
                    val serverAt = System.currentTimeMillis()
                    val latency = serverAt - wire.clientSentAt
                    val env = when (val decoded = wire.decodeInbound(serverAt, latency)) {
                        is WireDecodeResult.Accepted -> decoded.envelope
                        else -> return@collect
                    }
                    bytesReceived += json.encodeToString(CollabWireEnvelope.serializer(), wire).length.toLong()
                    eventsReceived++
                    if (seqTracker.accept(env)) _inbound.emit(env)
                }
            }

            try {
                ch.subscribe(blockUntilSubscribed = true)
                if (currentAttempt > 0) {
                    reconnectCount++
                    lastReconnectMs = (System.currentTimeMillis() - reconnectStartedAt).coerceAtLeast(0)
                    reconnectStartedAt = 0
                }
                _state.value = TransportState.CONNECTED
                emit(TransportLifecycleEvent(providerId, roomId.value, TransportState.CONNECTED))
                return
            } catch (e: Exception) {
                collector.cancel()
                runCatching { supabase.realtime.removeChannel(ch) }
                if (channel === ch) channel = null
                val reason = DisconnectReason.Transient(e.message ?: "subscribe_failed")
                _state.value = TransportState.RECONNECTING
                emit(TransportLifecycleEvent(providerId, roomId.value, TransportState.RECONNECTING, reason))
                if (!policy.shouldRetry(currentAttempt, reason)) {
                    _state.value = TransportState.CLOSED
                    emit(TransportLifecycleEvent(providerId, roomId.value, TransportState.CLOSED, reason))
                    return
                }
                delay(policy.delayMs(currentAttempt))
                currentAttempt++
            }
        }
    }

    override suspend fun send(envelope: OutboundEnvelope) {
        val activeChannel = channel ?: return
        val wire = envelope.toWire()
        val encoded = json.encodeToString(CollabWireEnvelope.serializer(), wire)
        bytesSent += encoded.length
        eventsSent++
        if (envelope.roomId.isBenchmark) {
            activeChannel.broadcast(event = "env", message = wire)
            return
        }
        // Durable envelopes are emitted by accept_collaboration_operation in the same
        // transaction that assigns the persistent revision. Never permit a client-only
        // broadcast to masquerade as durable truth.
        if (envelope.eventClass == CollabEventClass.DURABLE) return
        val scope = envelope.roomId.artifactScope ?: return
        supabase.postgrest.rpc(
            function = "send_collaboration_ephemeral",
            parameters = buildJsonObject {
                put("p_workspace", scope.workspaceId)
                put("p_artifact", scope.artifactId)
                put("p_device", envelope.deviceId.value)
                put("p_event_class", envelope.eventClass.name)
                put("p_event_type", envelope.eventType)
                envelope.targetId?.let { put("p_target", it) } ?: put("p_target", JsonNull)
                put("p_sequence", envelope.seq.value)
                put("p_payload_hash", envelope.payloadHash)
                envelope.payload?.let { put("p_payload", it) } ?: put("p_payload", JsonNull)
            },
        )
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
