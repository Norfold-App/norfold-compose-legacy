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
 * SCOPE: this is an interface + a delegating prototype. The final key domain (which artifact key,
 * which device set) is defined by the future editor/workspace model; this class does not lock it.
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
 * The concrete device material / recipient set is injected by the future editor wiring.
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
)

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
 * E2EE prototype that delegates to the existing [CryptoEngine.seal]/[CryptoEngine.open]. Wraps the
 * collaboration payload as the E2EE envelope body. This is a working boundary, not the final editor
 * key-management design — epoch rotation / membership orchestration live above this class exactly as
 * they do for chat.
 */
class CryptoEngineCollabEnvelope(
    private val engine: CryptoEngine,
    private val json: Json = Json { encodeDefaults = true; explicitNulls = false },
) : CollabEnvelopeCrypto {
    override val isE2ee = true

    override fun sealPayload(plaintext: JsonElement, context: CollabCryptoContext): JsonElement {
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
        val plaintextBytes = engine.open(
            envelope = envelope,
            recipient = context.sender,
            sender = context.senderPublic,
        )
        return json.parseToJsonElement(plaintextBytes.toString(Charsets.UTF_8))
    }

    private fun metadataFor(context: CollabCryptoContext) = E2eeMessageMetadata(
        conversationId = context.roomId.value,
        messageId = context.opId,
        senderAccountId = context.actorId.value,
        senderDeviceId = context.sender.public.deviceId,
        senderDeviceGeneration = context.sender.public.generation,
        securityEpochId = context.keyDomainId,
        createdAt = context.createdAt,
        contentType = "collab-op",
    )
}
