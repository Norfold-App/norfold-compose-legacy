package com.norfold.app.collaboration

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Connection lifecycle states, provider-independent. Every transport reports through this.
 */
enum class TransportState {
    IDLE,
    CONNECTING,
    CONNECTED,
    RECONNECTING,
    /** Transport gave up after exhausting the backoff budget, or was closed by the caller. */
    CLOSED,
}

/**
 * Reason attached to a disconnect so the reconnect policy can decide whether to retry.
 */
sealed interface DisconnectReason {
    /** Network dropped / socket error. Retriable. */
    data class Transient(val message: String) : DisconnectReason

    /** Auth rejected (bad/expired token, unauthorized artifact). Not retriable without new token. */
    data class AuthRejected(val code: String) : DisconnectReason

    /** Caller requested close. Terminal. */
    data object ClientClosed : DisconnectReason

    /** Server closed the room (deleted artifact, kicked). Terminal. */
    data class ServerClosed(val code: String) : DisconnectReason
}

/**
 * Provider-independent realtime collaboration transport. One active transport per session.
 *
 * Implementations: SupabaseBroadcastTransport, CloudflareDurableObjectTransport, AblyPubSubTransport.
 *
 * Contract:
 * - [connect] attaches to exactly one room. Idempotent while CONNECTED to the same room.
 * - [send] publishes an envelope. EPHEMERAL_LATEST may be coalesced by the caller (scheduler)
 *   BEFORE reaching send; the transport itself never drops DURABLE or EPHEMERAL_EVENT.
 * - [inbound] emits every received envelope after dedup (dedup is the transport's responsibility
 *   using (actor, seq)).
 * - Reconnect/backoff is handled internally; [state] reflects it. On reconnect the transport
 *   re-attaches to the same room and resumes; durable-op replay is the merge engine's concern,
 *   not the transport's.
 */
interface RealtimeCollaborationTransport {
    /** Stable provider identifier: "supabase", "cloudflare", "ably". */
    val providerId: String

    val state: StateFlow<TransportState>

    /** Emits inbound envelopes for the currently attached room. Cold; collect to receive. */
    val inbound: Flow<InboundEnvelope>

    /** Emits connection lifecycle transitions with reasons for observability/benchmarking. */
    val lifecycle: Flow<TransportLifecycleEvent>

    /**
     * Attach to [roomId]. [tokenProvider] supplies a fresh short-lived credential on each
     * (re)connection attempt — never a long-lived secret held by the transport.
     */
    suspend fun connect(roomId: RoomId, actorId: ActorId, tokenProvider: CollabTokenProvider)

    /** Publish one envelope. Suspends only until handed to the underlying socket/channel. */
    suspend fun send(envelope: OutboundEnvelope)

    /** Detach from the current room and release resources. Idempotent. */
    suspend fun close()

    /** Snapshot of transport-level counters for benchmarking. */
    fun metricsSnapshot(): TransportMetrics
}

data class TransportLifecycleEvent(
    val provider: String,
    val room: String,
    val state: TransportState,
    val reason: DisconnectReason? = null,
    val at: Long = System.currentTimeMillis(),
    /** Reconnect duration in ms, populated when returning to CONNECTED after RECONNECTING. */
    val reconnectMs: Long? = null,
)

/**
 * Counters maintained by every transport, reset per benchmark run via [reset].
 */
data class TransportMetrics(
    val provider: String,
    val eventsSent: Long,
    val eventsReceived: Long,
    val bytesSent: Long,
    val bytesReceived: Long,
    val duplicatesDropped: Long,
    val gapsSeen: Long,
    val reconnectCount: Long,
    val lastReconnectMs: Long?,
)

/**
 * Supplies a fresh short-lived token per connection attempt. Never returns a static server key.
 *
 * - Supabase: the current Supabase access token (JWT).
 * - Cloudflare: the Supabase JWT (edge verifies it, then does artifact authorization).
 * - Ably: a short-lived scoped Ably token/JWT minted by the Norfold token issuer.
 * - Benchmark rooms may return a bench-only bearer that authorizes only bench:* rooms.
 */
fun interface CollabTokenProvider {
    suspend fun freshToken(roomId: RoomId): String
}
