package com.norfold.app.collaboration

import com.norfold.app.domain.chat.CryptoEngine
import com.norfold.app.domain.chat.DeviceCryptoMaterial
import com.norfold.app.domain.chat.DevicePublicMaterial
import com.norfold.app.domain.chat.E2eeMessageMetadata
import com.norfold.app.domain.chat.NorfoldE2eeEnvelopeV1
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/**
 * E2EE collaboration envelope boundary. For E2EE artifacts, durable ops and ephemeral previews
 * must be client-encrypted before the transport provider sees them. This REUSES Norfold's vetted
 * [CryptoEngine] — no new cipher is invented here.
 *
 * Authorization is still mandatory even when the payload is ciphertext (see AUTHORIZATION_CONTRACT).
 * Providers may still see minimal routing/room/timing metadata.
 *
 * Key-domain and recipient membership are supplied by the authenticated workspace layer.
 */
interface CollabEnvelopeCrypto {
    /** True when this artifact requires client-side E2EE for collaboration payloads. */
    val isE2ee: Boolean

    /** Encrypt an outbound payload's [plaintext] JSON, returning ciphertext to place on the wire. */
    fun sealPayload(plaintext: JsonElement, context: CollabCryptoContext): JsonElement

    /** Decrypt an inbound ciphertext payload back to plaintext JSON. */
    fun openPayload(ciphertext: JsonElement, context: CollabCryptoContext): JsonElement
}

/**
 * Minimal context needed to seal/open a collaboration payload against the correct key domain.
 * The concrete device material and recipient set are injected by the workspace key manager.
 */
data class CollabCryptoContext(
    val roomId: RoomId,
    val actorId: ActorId,
    val opId: String,
    val createdAt: Long,
    val sender: DeviceCryptoMaterial,
    val recipients: List<DevicePublicMaterial>,
    val senderPublic: DevicePublicMaterial,
    /** Stable key-domain id (e.g. workspace-artifact epoch). Bound into associated data. */
    val keyDomainId: String,
    val keyEpoch: Long,
    val workspaceId: String,
    val artifactId: String,
    val deviceId: String,
    val sequence: Long,
    val protocolVersion: Int,
    val semanticVersion: Int,
    val artifactSchemaVersion: Int,
    val eventClass: CollabEventClass,
    val contentType: String,
) {
    fun requireMatches(envelope: OutboundEnvelope): CollabCryptoContext {
        val scope = requireNotNull(envelope.roomId.artifactScope) { "E2EE requires an artifact room" }
        require(roomId == envelope.roomId && actorId == envelope.actorId && opId == envelope.operationId.value)
        require(createdAt == envelope.clientSentAt && deviceId == envelope.deviceId.value)
        require(senderPublic.deviceId == deviceId) { "Sender device metadata mismatch" }
        require(workspaceId == scope.workspaceId && artifactId == scope.artifactId)
        require(sequence == envelope.seq.value && protocolVersion == envelope.protocolVersion)
        require(semanticVersion == envelope.semanticVersion && artifactSchemaVersion == envelope.artifactSchemaVersion)
        require(keyEpoch == envelope.keyEpoch)
        require(eventClass == envelope.eventClass && contentType == envelope.contentType)
        return this
    }
}

fun interface CollabCryptoContextProvider {
    fun contextFor(envelope: OutboundEnvelope): CollabCryptoContext
}

/**
 * Managed (non-E2EE) artifacts: server-readable per workspace policy. Passthrough — the transport
 * still applies transport-level TLS, but no client-side artifact encryption is added.
 */
object ManagedCollabEnvelopeCrypto : CollabEnvelopeCrypto {
    override val isE2ee = false
    override fun sealPayload(plaintext: JsonElement, context: CollabCryptoContext) = plaintext
    override fun openPayload(ciphertext: JsonElement, context: CollabCryptoContext) = ciphertext
}

/**
 * E2EE adapter that delegates to the existing [CryptoEngine.seal]/[CryptoEngine.open]. Epoch rotation
 * and membership orchestration remain owned by the workspace key manager, exactly as they are for chat.
 */
class CryptoEngineCollabEnvelope(
    private val engine: CryptoEngine,
    private val json: Json = Json { encodeDefaults = true; explicitNulls = false },
) : CollabEnvelopeCrypto {
    override val isE2ee = true

    override fun sealPayload(plaintext: JsonElement, context: CollabCryptoContext): JsonElement {
        require(context.sender.public == context.senderPublic) { "Sender public material mismatch" }
        val metadata = metadataFor(context)
        val envelope = engine.seal(
            plaintext = json.encodeToString(JsonElement.serializer(), plaintext).toByteArray(Charsets.UTF_8),
            metadata = metadata,
            sender = context.sender,
            recipients = context.recipients,
        )
        return json.encodeToJsonElement(NorfoldE2eeEnvelopeV1.serializer(), envelope)
    }

    override fun openPayload(ciphertext: JsonElement, context: CollabCryptoContext): JsonElement {
        val envelope = json.decodeFromJsonElement(NorfoldE2eeEnvelopeV1.serializer(), ciphertext)
        require(envelope.metadata == metadataFor(context)) { "Encrypted collaboration metadata mismatch" }
        val plaintextBytes = engine.open(
            envelope = envelope,
            recipient = context.sender,
            sender = context.senderPublic,
        )
        return json.parseToJsonElement(plaintextBytes.toString(Charsets.UTF_8))
    }

    private fun metadataFor(context: CollabCryptoContext) = E2eeMessageMetadata(
        conversationId = listOf(context.roomId.value, context.workspaceId, context.artifactId).joinToString("|"),
        messageId = context.opId,
        senderAccountId = context.actorId.value,
        senderDeviceId = context.senderPublic.deviceId,
        senderDeviceGeneration = context.senderPublic.generation,
        securityEpochId = "${context.keyDomainId}|${context.keyEpoch}|${context.sequence}",
        createdAt = context.createdAt,
        contentType = listOf(
            "collab-op", context.protocolVersion, context.semanticVersion,
            context.artifactSchemaVersion, context.eventClass.name, context.contentType,
        ).joinToString("|"),
    )
}
