package com.norfold.app.collaboration

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Three-tier event classification. Only EPHEMERAL_LATEST may be coalesced or dropped.
 * EPHEMERAL_EVENT and DURABLE must never be lost or conflated.
 */
enum class CollabEventClass {
    /** Latest-state previews: cursor, caret, drag/resize/rotate, viewport, selection preview.
     *  Superseded by a newer event with the same (actorId, targetId, eventType) key. */
    EPHEMERAL_LATEST,

    /** Discrete ephemeral signals: peer join/leave, editing-intent acquire/release, contention state.
     *  Must be delivered; must not be conflated. */
    EPHEMERAL_EVENT,

    /** Semantic operations that mutate the artifact: text ops, object create/delete, transforms,
     *  style commands, table/chart mutations, relationship changes.
     *  Must never be dropped, reordered relative to their logical predecessor, or conflated. */
    DURABLE,
}

/**
 * Monotonically increasing per-actor sequence number carried on every envelope.
 * Receivers use this for deduplication and gap detection.
 */
@JvmInline
value class CollabSeq(val value: Long)

/**
 * Stable identifier for a collaboration room. Scoped to one artifact (document/page/canvas).
 * Format: "artifact:<artifactId>" for production; "bench:<name>" for benchmark-only rooms.
 */
@JvmInline
value class RoomId(val value: String) {
    val isBenchmark: Boolean get() = value.startsWith("bench:")
}

/**
 * Opaque actor identifier. In production this is the stable Norfold account id.
 * In benchmark runs it is a synthetic label.
 */
@JvmInline
value class ActorId(val value: String)

/**
 * Outbound envelope produced by the local client before transport encoding.
 */
data class OutboundEnvelope(
    val roomId: RoomId,
    val actorId: ActorId,
    val eventClass: CollabEventClass,
    val eventType: String,
    /** Stable target object/region id — used as coalescing key for EPHEMERAL_LATEST. */
    val targetId: String? = null,
    val seq: CollabSeq,
    val clientSentAt: Long = System.currentTimeMillis(),
    /** Serialized payload. For E2EE artifacts this is already ciphertext. */
    val payload: JsonElement? = null,
)

/**
 * Inbound envelope received from the transport layer.
 */
data class InboundEnvelope(
    val roomId: RoomId,
    val actorId: ActorId,
    val eventClass: CollabEventClass,
    val eventType: String,
    val targetId: String? = null,
    val seq: CollabSeq,
    val clientSentAt: Long,
    val serverAt: Long,
    val payload: JsonElement? = null,
    /** Transport-level round-trip latency in ms, if measurable. */
    val observedLatencyMs: Long? = null,
)

/**
 * Wire format shared by all three transport adapters. Adapters serialize/deserialize this.
 */
@Serializable
data class CollabWireEnvelope(
    val room: String,
    val actor: String,
    @SerialName("cls") val eventClass: String,
    @SerialName("type") val eventType: String,
    val target: String? = null,
    val seq: Long,
    @SerialName("sent_at") val clientSentAt: Long,
    val payload: JsonElement? = null,
)

fun OutboundEnvelope.toWire() = CollabWireEnvelope(
    room = roomId.value,
    actor = actorId.value,
    eventClass = eventClass.name,
    eventType = eventType,
    target = targetId,
    seq = seq.value,
    clientSentAt = clientSentAt,
    payload = payload,
)

fun CollabWireEnvelope.toInbound(serverAt: Long, observedLatencyMs: Long? = null) = InboundEnvelope(
    roomId = RoomId(room),
    actorId = ActorId(actor),
    eventClass = CollabEventClass.valueOf(eventClass),
    eventType = eventType,
    targetId = target,
    seq = CollabSeq(seq),
    clientSentAt = clientSentAt,
    serverAt = serverAt,
    payload = payload,
    observedLatencyMs = observedLatencyMs,
)
