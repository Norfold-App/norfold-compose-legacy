package com.norfold.app.collaboration

import io.ably.lib.realtime.AblyRealtime
import io.ably.lib.realtime.Channel
import io.ably.lib.realtime.CompletionListener
import io.ably.lib.realtime.ConnectionEvent
import io.ably.lib.realtime.ConnectionState
import io.ably.lib.types.ClientOptions
import io.ably.lib.types.ErrorInfo
import io.ably.lib.types.Message
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

/**
 * Ably Pub/Sub transport using the native ably-android SDK.
 *
 * Auth: NEVER an API key in the app. [CollabTokenProvider] supplies a short-lived scoped
 * Ably token/JWT minted by the Norfold token issuer. Wired through Ably's authCallback so
 * the SDK can renew automatically.
 *
 * Channel: "collab:<roomId>". A later optimization experiment may split a dedicated
 * preview namespace ("collab-live:<roomId>") and enable server-side conflation there for
 * EPHEMERAL_LATEST only — never for durable ops.
 *
 * Reconnect: Ably's connection layer manages continuity (resume). This adapter maps Ably
 * connection state to TransportState; it does not run its own backoff loop, since Ably's
 * is mature — but it reports reconnect timing for benchmarking.
 */
class AblyPubSubTransport(
    private val environment: String? = null,
    private val json: Json = Json { ignoreUnknownKeys = true },
) : RealtimeCollaborationTransport {

    override val providerId = "ably"

    private val _state = MutableStateFlow(TransportState.IDLE)
    override val state: StateFlow<TransportState> = _state.asStateFlow()

    private val _inbound = MutableSharedFlow<InboundEnvelope>(extraBufferCapacity = 256)
    override val inbound: Flow<InboundEnvelope> = _inbound.asSharedFlow()

    private val _lifecycle = MutableSharedFlow<TransportLifecycleEvent>(extraBufferCapacity = 64)
    override val lifecycle: Flow<TransportLifecycleEvent> = _lifecycle.asSharedFlow()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val seqTracker = SequenceTracker()

    private var ably: AblyRealtime? = null
    private var channel: Channel? = null
    private var currentRoom: RoomId? = null
    private var currentActor: ActorId? = null

    private var eventsSent = 0L
    private var eventsReceived = 0L
    private var bytesSent = 0L
    private var bytesReceived = 0L
    private var reconnectCount = 0L
    private var lastReconnectMs: Long? = null
    private var disconnectedAt = 0L

    override suspend fun connect(roomId: RoomId, actorId: ActorId, tokenProvider: CollabTokenProvider) {
        if (_state.value == TransportState.CONNECTED && currentRoom == roomId) return
        currentRoom = roomId
        currentActor = actorId
        _state.value = TransportState.CONNECTING
        emit(TransportLifecycleEvent(providerId, roomId.value, TransportState.CONNECTING))

        val options = ClientOptions().apply {
            clientId = actorId.value
            environment = this@AblyPubSubTransport.environment
            autoConnect = true
            // Short-lived scoped token via callback; SDK renews automatically.
            authCallback = io.ably.lib.rest.Auth.TokenCallback { _ ->
                runCatchingToken(roomId, tokenProvider)
            }
        }
        val client = AblyRealtime(options)
        ably = client

        client.connection.on { change ->
            val room = roomId.value
            when (change.current) {
                ConnectionState.connected -> {
                    val reconnectMs = if (disconnectedAt > 0) {
                        reconnectCount++
                        (System.currentTimeMillis() - disconnectedAt).also { lastReconnectMs = it; disconnectedAt = 0 }
                    } else null
                    _state.value = TransportState.CONNECTED
                    emit(TransportLifecycleEvent(providerId, room, TransportState.CONNECTED, reconnectMs = reconnectMs))
                }
                ConnectionState.disconnected -> {
                    if (disconnectedAt == 0L) disconnectedAt = System.currentTimeMillis()
                    _state.value = TransportState.RECONNECTING
                    emit(TransportLifecycleEvent(providerId, room, TransportState.RECONNECTING,
                        DisconnectReason.Transient(change.reason?.message ?: "disconnected")))
                }
                ConnectionState.suspended, ConnectionState.connecting -> {
                    if (disconnectedAt == 0L) disconnectedAt = System.currentTimeMillis()
                    _state.value = TransportState.RECONNECTING
                }
                ConnectionState.failed -> {
                    _state.value = TransportState.CLOSED
                    emit(TransportLifecycleEvent(providerId, room, TransportState.CLOSED,
                        DisconnectReason.AuthRejected(change.reason?.code?.toString() ?: "failed")))
                }
                ConnectionState.closed -> {
                    _state.value = TransportState.CLOSED
                    emit(TransportLifecycleEvent(providerId, room, TransportState.CLOSED, DisconnectReason.ClientClosed))
                }
                else -> {}
            }
        }

        val ch = client.channels.get("collab:${roomId.value}")
        channel = ch
        ch.subscribe("env") { message ->
            val text = message.data as? String ?: return@subscribe
            bytesReceived += text.length
            eventsReceived++
            scope.launch {
                try {
                    val wire = json.decodeFromString(CollabWireEnvelope.serializer(), text)
                    val serverAt = System.currentTimeMillis()
                    val env = wire.toInbound(serverAt, serverAt - wire.clientSentAt)
                    if (seqTracker.accept(env)) _inbound.emit(env)
                } catch (_: Exception) {}
            }
        }
    }

    /** Ably's TokenCallback is synchronous; bridge our suspend provider via runBlocking on IO. */
    private fun runCatchingToken(roomId: RoomId, tokenProvider: CollabTokenProvider): String =
        kotlinx.coroutines.runBlocking(Dispatchers.IO) { tokenProvider.freshToken(roomId) }

    override suspend fun send(envelope: OutboundEnvelope) {
        val ch = channel ?: return
        val encoded = json.encodeToString(CollabWireEnvelope.serializer(), envelope.toWire())
        bytesSent += encoded.length
        eventsSent++
        ch.publish("env", encoded, object : CompletionListener {
            override fun onSuccess() {}
            override fun onError(reason: ErrorInfo?) {}
        })
    }

    override suspend fun close() {
        try { channel?.unsubscribe() } catch (_: Exception) {}
        try { ably?.close() } catch (_: Exception) {}
        channel = null
        ably = null
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
