package com.norfold.app.collaboration

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit

/**
 * Cloudflare Durable Object Hibernation WebSocket transport.
 *
 * Connects to: wss://<worker-host>/v1/collab/<roomId>
 * Auth: Bearer token in the Upgrade request Authorization header.
 *
 * Benchmark-only until fail-closed artifact authorization is wired in the Worker.
 * The Worker currently allows only bench:* rooms for non-Supabase tokens.
 *
 * Wire format: CollabWireEnvelope JSON, same as Supabase adapter.
 *
 * Reconnect: handled here using [ReconnectPolicy]. On reconnect the Worker re-attaches
 * to the same Durable Object (same room name → same DO id), so room state is preserved.
 */
class CloudflareDurableObjectTransport(
    private val workerBaseUrl: String,
    private val policy: ReconnectPolicy = ReconnectPolicy(),
    private val okHttp: OkHttpClient = defaultOkHttp(),
) : RealtimeCollaborationTransport {

    override val providerId = "cloudflare"

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
    @Volatile private var socket: WebSocket? = null
    private val connectSignal = Channel<Result<Unit>>(1)

    private var eventsSent = 0L
    private var eventsReceived = 0L
    private var bytesSent = 0L
    private var bytesReceived = 0L
    private var reconnectCount = 0L
    private var lastReconnectMs: Long? = null
    private var reconnectStartAt = 0L

    override suspend fun connect(roomId: RoomId, actorId: ActorId, tokenProvider: CollabTokenProvider) {
        if (_state.value == TransportState.CONNECTED && currentRoom == roomId) return
        currentRoom = roomId
        currentActor = actorId
        currentToken = tokenProvider
        openSocket(roomId, tokenProvider, attempt = 0)
    }

    private suspend fun openSocket(roomId: RoomId, tokenProvider: CollabTokenProvider, attempt: Int) {
        _state.value = if (attempt == 0) TransportState.CONNECTING else TransportState.RECONNECTING
        if (attempt > 0) reconnectStartAt = System.currentTimeMillis()
        emit(TransportLifecycleEvent(providerId, roomId.value, _state.value))

        val token = tokenProvider.freshToken(roomId)
        val wsUrl = "${workerBaseUrl.trimEnd('/')}/v1/collab/${roomId.value}"
            .replace("https://", "wss://")
            .replace("http://", "ws://")

        val request = Request.Builder()
            .url(wsUrl)
            .header("Authorization", "Bearer $token")
            .build()

        // Drain any stale signal
        while (connectSignal.tryReceive().isSuccess) {}

        socket = okHttp.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                scope.launch { connectSignal.send(Result.success(Unit)) }
            }

            override fun onMessage(ws: WebSocket, text: String) {
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

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                val code = response?.code?.toString() ?: "network_error"
                val reason = if (response?.code == 401 || response?.code == 403)
                    DisconnectReason.AuthRejected(code)
                else
                    DisconnectReason.Transient(t.message ?: code)
                scope.launch {
                    connectSignal.trySend(Result.failure(Exception(reason.toString())))
                    handleDisconnect(roomId, tokenProvider, reason, attempt)
                }
            }

            override fun onClosing(ws: WebSocket, code: Int, reason: String) {
                val dr = when {
                    code == 1000 || code == 1001 -> DisconnectReason.ClientClosed
                    code == 4001 || code == 4003 -> DisconnectReason.AuthRejected(code.toString())
                    code >= 4000 -> DisconnectReason.ServerClosed(code.toString())
                    else -> DisconnectReason.Transient("ws_close_$code")
                }
                scope.launch { handleDisconnect(roomId, tokenProvider, dr, attempt) }
            }
        })

        val result = connectSignal.receive()
        if (result.isSuccess) {
            val reconnectMs = if (attempt > 0) {
                reconnectCount++
                (System.currentTimeMillis() - reconnectStartAt).also { lastReconnectMs = it }
            } else null
            _state.value = TransportState.CONNECTED
            emit(TransportLifecycleEvent(providerId, roomId.value, TransportState.CONNECTED, reconnectMs = reconnectMs))
        }
        // failure path handled in onFailure callback
    }

    private suspend fun handleDisconnect(
        roomId: RoomId,
        tokenProvider: CollabTokenProvider,
        reason: DisconnectReason,
        attempt: Int,
    ) {
        if (_state.value == TransportState.CLOSED) return
        _state.value = TransportState.RECONNECTING
        emit(TransportLifecycleEvent(providerId, roomId.value, TransportState.RECONNECTING, reason))
        if (policy.shouldRetry(attempt, reason)) {
            delay(policy.delayMs(attempt))
            openSocket(roomId, tokenProvider, attempt + 1)
        } else {
            _state.value = TransportState.CLOSED
            emit(TransportLifecycleEvent(providerId, roomId.value, TransportState.CLOSED, reason))
        }
    }

    override suspend fun send(envelope: OutboundEnvelope) {
        val ws = socket ?: return
        val wire = envelope.toWire()
        val encoded = json.encodeToString(CollabWireEnvelope.serializer(), wire)
        bytesSent += encoded.length
        eventsSent++
        ws.send(encoded)
    }

    override suspend fun close() {
        socket?.close(1000, "client_closed")
        socket = null
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

    companion object {
        fun defaultOkHttp() = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.SECONDS) // WebSocket: no read timeout
            .writeTimeout(10, TimeUnit.SECONDS)
            .pingInterval(25, TimeUnit.SECONDS) // keep-alive for Hibernation WS
            .build()
    }
}
