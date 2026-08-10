package com.norfold.app.collaboration

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.security.MessageDigest

enum class CollabEventClass { EPHEMERAL_LATEST, EPHEMERAL_EVENT, DURABLE }

@JvmInline value class CollabSeq(val value: Long)
@JvmInline value class ActorId(val value: String)
@JvmInline value class DeviceId(val value: String)
@JvmInline value class OperationId(val value: String)

/** Stable artifact authorization and ordering partition. Titles and screen state never enter it. */
@JvmInline
value class RoomId(val value: String) {
    val isBenchmark: Boolean get() = value.startsWith("bench:")
    val artifactScope: ArtifactRoom? get() = ArtifactRoom.parse(value)

    companion object {
        fun artifact(workspaceId: String, artifactId: String): RoomId =
            RoomId(ArtifactRoom(workspaceId, artifactId).value)
    }
}

data class ArtifactRoom(val workspaceId: String, val artifactId: String) {
    init {
        require(workspaceId.matches(UUID)) { "Invalid workspace id" }
        require(artifactId.matches(UUID)) { "Invalid artifact id" }
    }
    val value: String get() = "artifact:$workspaceId:$artifactId"

    companion object {
        private val UUID = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-5][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}")
        fun parse(value: String): ArtifactRoom? {
            val parts = value.split(':')
            if (parts.size != 3 || parts[0] != "artifact") return null
            return runCatching { ArtifactRoom(parts[1], parts[2]) }.getOrNull()
        }
    }
}

data class OutboundEnvelope(
    val roomId: RoomId,
    val actorId: ActorId,
    val eventClass: CollabEventClass,
    val eventType: String,
    val targetId: String? = null,
    val seq: CollabSeq,
    val clientSentAt: Long = System.currentTimeMillis(),
    val payload: JsonElement? = null,
    val deviceId: DeviceId = DeviceId("unknown-device"),
    val operationId: OperationId = OperationId("${actorId.value}:${deviceId.value}:${seq.value}"),
    val batchId: String? = null,
    val protocolVersion: Int = PROTOCOL_VERSION,
    val semanticVersion: Int = 1,
    val artifactSchemaVersion: Int = 1,
    val keyEpoch: Long = 0,
    val contentType: String = "application/vnd.norfold.semantic+json",
    val payloadHash: String = hashPayload(payload),
    val serverRevision: Long? = null,
    val revisionHash: String? = null,
) {
    init {
        require(protocolVersion > 0 && semanticVersion > 0 && artifactSchemaVersion > 0)
        require(keyEpoch >= 0)
        require(seq.value > 0) { "Sequence must be positive" }
        require(operationId.value.isNotBlank() && operationId.value.length <= 256)
        require(eventType.length in 1..64)
        require(payload.toString().toByteArray().size <= MAX_WIRE_PAYLOAD_BYTES)
        if (!roomId.isBenchmark) requireNotNull(roomId.artifactScope) { "Durable collaboration requires an artifact room" }
    }

    companion object {
        const val PROTOCOL_VERSION = 1
        const val MAX_WIRE_PAYLOAD_BYTES = 64 * 1024
    }
}

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
    val observedLatencyMs: Long? = null,
    val deviceId: DeviceId = DeviceId("unknown-device"),
    val operationId: OperationId = OperationId("${actorId.value}:${deviceId.value}:${seq.value}"),
    val batchId: String? = null,
    val protocolVersion: Int = OutboundEnvelope.PROTOCOL_VERSION,
    val semanticVersion: Int = 1,
    val artifactSchemaVersion: Int = 1,
    val keyEpoch: Long = 0,
    val contentType: String = "application/vnd.norfold.semantic+json",
    val payloadHash: String = hashPayload(payload),
    val serverRevision: Long? = null,
    val revisionHash: String? = null,
    /** Hash assigned to the durable revision; remains ciphertext-bound after local decryption. */
    val wirePayloadHash: String = payloadHash,
)

@Serializable
data class CollabWireEnvelope(
    val room: String,
    val actor: String,
    val device: String = "unknown-device",
    @SerialName("op_id") val operationId: String = "",
    @SerialName("batch_id") val batchId: String? = null,
    @SerialName("protocol") val protocolVersion: Int = OutboundEnvelope.PROTOCOL_VERSION,
    @SerialName("semantic") val semanticVersion: Int = 1,
    @SerialName("schema") val artifactSchemaVersion: Int = 1,
    @SerialName("key_epoch") val keyEpoch: Long = 0,
    @SerialName("content_type") val contentType: String = "application/vnd.norfold.semantic+json",
    @SerialName("payload_hash") val payloadHash: String = "",
    @SerialName("cls") val eventClass: String,
    @SerialName("type") val eventType: String,
    val target: String? = null,
    val seq: Long,
    @SerialName("sent_at") val clientSentAt: Long,
    @SerialName("server_revision") val serverRevision: Long? = null,
    @SerialName("revision_hash") val revisionHash: String? = null,
    val payload: JsonElement? = null,
)

fun OutboundEnvelope.toWire() = CollabWireEnvelope(
    room = roomId.value, actor = actorId.value, device = deviceId.value,
    operationId = operationId.value, batchId = batchId, protocolVersion = protocolVersion,
    semanticVersion = semanticVersion, artifactSchemaVersion = artifactSchemaVersion,
    keyEpoch = keyEpoch, contentType = contentType, payloadHash = payloadHash,
    eventClass = eventClass.name, eventType = eventType, target = targetId,
    seq = seq.value, clientSentAt = clientSentAt, serverRevision = serverRevision,
    revisionHash = revisionHash, payload = payload,
)

sealed interface WireDecodeResult {
    data class Accepted(val envelope: InboundEnvelope) : WireDecodeResult
    data class Incompatible(val protocolVersion: Int) : WireDecodeResult
    data class Rejected(val reason: String) : WireDecodeResult
}

fun CollabWireEnvelope.decodeInbound(serverAt: Long, observedLatencyMs: Long? = null): WireDecodeResult {
    if (protocolVersion > OutboundEnvelope.PROTOCOL_VERSION) return WireDecodeResult.Incompatible(protocolVersion)
    if (protocolVersion <= 0 || semanticVersion <= 0 || artifactSchemaVersion <= 0 || keyEpoch < 0) {
        return WireDecodeResult.Rejected("invalid_version_metadata")
    }
    if (payload.toString().toByteArray().size > OutboundEnvelope.MAX_WIRE_PAYLOAD_BYTES) {
        return WireDecodeResult.Rejected("payload_too_large")
    }
    if (actor.isBlank() || actor.length > 128 || device.isBlank() || device.length > 128) {
        return WireDecodeResult.Rejected("invalid_actor_or_device")
    }
    if (operationId.isBlank() || operationId.length > 256 || seq <= 0 ||
        !eventType.matches(Regex("[a-z][a-z0-9_.-]{0,63}")) ||
        (target?.length ?: 0) > 256 || serverRevision?.let { it <= 0 } == true ||
        (revisionHash != null && !revisionHash.matches(Regex("[0-9a-f]{64}")))
    ) return WireDecodeResult.Rejected("invalid_operation_metadata")
    val cls = runCatching { CollabEventClass.valueOf(eventClass) }.getOrNull()
        ?: return WireDecodeResult.Rejected("unknown_event_class")
    val roomId = RoomId(room)
    if (!roomId.isBenchmark && roomId.artifactScope == null) return WireDecodeResult.Rejected("invalid_room")
    val computed = hashPayload(payload)
    if (!payloadHash.matches(Regex("[0-9a-f]{64}")) || payloadHash != computed) {
        return WireDecodeResult.Rejected("payload_hash_mismatch")
    }
    return WireDecodeResult.Accepted(
        InboundEnvelope(
            roomId, ActorId(actor), cls, eventType, target, CollabSeq(seq), clientSentAt, serverAt,
            payload, observedLatencyMs, DeviceId(device),
            OperationId(operationId.ifBlank { "$actor:$device:$seq" }), batchId,
            protocolVersion, semanticVersion, artifactSchemaVersion, keyEpoch, contentType,
            payloadHash.ifBlank { computed }, serverRevision, revisionHash,
            payloadHash.ifBlank { computed },
        ),
    )
}

fun CollabWireEnvelope.toInbound(serverAt: Long, observedLatencyMs: Long? = null): InboundEnvelope =
    when (val result = decodeInbound(serverAt, observedLatencyMs)) {
        is WireDecodeResult.Accepted -> result.envelope
        is WireDecodeResult.Incompatible -> error("Unsupported collaboration protocol ${result.protocolVersion}")
        is WireDecodeResult.Rejected -> error("Rejected collaboration envelope: ${result.reason}")
    }

internal fun hashPayload(payload: JsonElement?): String = MessageDigest.getInstance("SHA-256")
    .digest((payload?.let(::canonicalPayload)?.toString() ?: "null").toByteArray(Charsets.UTF_8))
    .joinToString("") { "%02x".format(it) }

private fun canonicalPayload(value: JsonElement): JsonElement = when (value) {
    is JsonObject -> JsonObject(value.entries.sortedBy { it.key }.associate { it.key to canonicalPayload(it.value) })
    is JsonArray -> JsonArray(value.map(::canonicalPayload))
    is JsonPrimitive -> value
}
