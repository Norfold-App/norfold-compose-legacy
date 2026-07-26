package com.norfold.app.domain.chat

import com.google.crypto.tink.Aead
import com.google.crypto.tink.HybridDecrypt
import com.google.crypto.tink.HybridEncrypt
import com.google.crypto.tink.InsecureSecretKeyAccess
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.PublicKeySign
import com.google.crypto.tink.PublicKeyVerify
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.TinkJsonProtoKeysetFormat
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.aead.PredefinedAeadParameters
import com.google.crypto.tink.hybrid.HpkeParameters
import com.google.crypto.tink.hybrid.HybridConfig
import com.google.crypto.tink.signature.PredefinedSignatureParameters
import com.google.crypto.tink.signature.SignatureConfig
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64

/**
 * Replaceable secure-messaging primitive boundary. Product/domain code never
 * depends on Tink keysets or algorithms directly.
 */
interface CryptoEngine {
    val suiteId: String

    fun generateDeviceMaterial(deviceId: String, generation: Int): DeviceCryptoMaterial

    fun seal(
        plaintext: ByteArray,
        metadata: E2eeMessageMetadata,
        sender: DeviceCryptoMaterial,
        recipients: List<DevicePublicMaterial>,
    ): NorfoldE2eeEnvelopeV1

    fun open(
        envelope: NorfoldE2eeEnvelopeV1,
        recipient: DeviceCryptoMaterial,
        sender: DevicePublicMaterial,
    ): ByteArray
}

@Serializable
data class DevicePublicMaterial(
    val deviceId: String,
    val generation: Int,
    val hpkePublicKeyset: String,
    val signingPublicKeyset: String,
) {
    val credentialFingerprint: String
        get() = MessageDigest.getInstance("SHA-256")
            .digest((hpkePublicKeyset + "\n" + signingPublicKeyset).toByteArray())
            .joinToString("") { "%02x".format(it) }

    val securityCode: String
        get() {
            val digest = credentialFingerprint.chunked(2).map { it.toInt(16).toByte() }
            return digest.take(12).joinToString("") { "%02X".format(it) }
                .chunked(4).joinToString(" ")
        }
}

/** Private keysets are stored only after device-local Keystore wrapping. */
data class DeviceCryptoMaterial(
    val public: DevicePublicMaterial,
    val hpkePrivateKeyset: String,
    val signingPrivateKeyset: String,
)

@Serializable
data class E2eeMessageMetadata(
    val conversationId: String,
    val messageId: String,
    val senderAccountId: String,
    val senderDeviceId: String,
    val senderDeviceGeneration: Int,
    val securityEpochId: String,
    val createdAt: Long,
    val contentType: String = "message",
    val lifespanJson: String? = null,
)

@Serializable
data class RecipientWrappedKey(
    val deviceId: String,
    val generation: Int,
    val wrappedKey: String,
)

@Serializable
data class NorfoldE2eeEnvelopeV1(
    val version: Int = 1,
    val suite: String,
    val metadata: E2eeMessageMetadata,
    val ciphertext: String,
    val recipientKeys: List<RecipientWrappedKey>,
    val signature: String,
)

/**
 * Tink provider using RFC 9180 HPKE (X25519/HKDF-SHA256/AES-256-GCM),
 * AES-256-GCM payload encryption, and Ed25519 sender authentication.
 *
 * This is intentionally described as a versioned HPKE envelope, not MLS. The
 * boundary can be replaced by an RFC 9420 provider without changing Room or UI
 * models. Epoch rotation and membership orchestration live above this class.
 */
class TinkCryptoEngine : CryptoEngine {
    override val suiteId: String = "norfold-hpke-x25519-aes256gcm-ed25519-v1"
    private val json = Json { encodeDefaults = true; explicitNulls = false }
    private val config = RegistryConfiguration.get()

    init {
        AeadConfig.register()
        HybridConfig.register()
        SignatureConfig.register()
    }

    override fun generateDeviceMaterial(deviceId: String, generation: Int): DeviceCryptoMaterial {
        require(deviceId.isNotBlank())
        require(generation > 0)
        val hpke = KeysetHandle.generateNew(
            HpkeParameters.builder()
                .setKemId(HpkeParameters.KemId.DHKEM_X25519_HKDF_SHA256)
                .setKdfId(HpkeParameters.KdfId.HKDF_SHA256)
                .setAeadId(HpkeParameters.AeadId.AES_256_GCM)
                .setVariant(HpkeParameters.Variant.NO_PREFIX)
                .build(),
        )
        val signing = KeysetHandle.generateNew(PredefinedSignatureParameters.ED25519WithRawOutput)
        val public = DevicePublicMaterial(
            deviceId = deviceId,
            generation = generation,
            hpkePublicKeyset = TinkJsonProtoKeysetFormat.serializeKeysetWithoutSecret(
                hpke.publicKeysetHandle,
            ),
            signingPublicKeyset = TinkJsonProtoKeysetFormat.serializeKeysetWithoutSecret(
                signing.publicKeysetHandle,
            ),
        )
        return DeviceCryptoMaterial(
            public = public,
            hpkePrivateKeyset = serializeSecret(hpke),
            signingPrivateKeyset = serializeSecret(signing),
        )
    }

    override fun seal(
        plaintext: ByteArray,
        metadata: E2eeMessageMetadata,
        sender: DeviceCryptoMaterial,
        recipients: List<DevicePublicMaterial>,
    ): NorfoldE2eeEnvelopeV1 {
        require(plaintext.isNotEmpty()) { "Secure payload cannot be empty." }
        require(metadata.senderDeviceId == sender.public.deviceId) { "Sender device mismatch." }
        require(metadata.senderDeviceGeneration == sender.public.generation) {
            "Sender credential generation mismatch."
        }
        val uniqueRecipients = recipients.distinctBy { it.deviceId to it.generation }
        require(uniqueRecipients.isNotEmpty()) { "No authorized recipient device." }

        val payloadKeyset = KeysetHandle.generateNew(PredefinedAeadParameters.AES256_GCM)
        val associatedData = associatedData(metadata)
        val ciphertext = payloadKeyset.getPrimitive(config, Aead::class.java)
            .encrypt(plaintext, associatedData)
        val serializedPayloadKey = serializeSecret(payloadKeyset).toByteArray(StandardCharsets.UTF_8)
        val wrapped = uniqueRecipients.map { recipient ->
            val publicHandle = TinkJsonProtoKeysetFormat.parseKeysetWithoutSecret(
                recipient.hpkePublicKeyset,
            )
            val encryptedKey = publicHandle.getPrimitive(config, HybridEncrypt::class.java)
                .encrypt(serializedPayloadKey, associatedData)
            RecipientWrappedKey(
                deviceId = recipient.deviceId,
                generation = recipient.generation,
                wrappedKey = b64(encryptedKey),
            )
        }
        val unsigned = NorfoldE2eeEnvelopeV1(
            suite = suiteId,
            metadata = metadata,
            ciphertext = b64(ciphertext),
            recipientKeys = wrapped,
            signature = "",
        )
        val signer = parseSecret(sender.signingPrivateKeyset)
            .getPrimitive(config, PublicKeySign::class.java)
        return unsigned.copy(signature = b64(signer.sign(signingBytes(unsigned))))
    }

    override fun open(
        envelope: NorfoldE2eeEnvelopeV1,
        recipient: DeviceCryptoMaterial,
        sender: DevicePublicMaterial,
    ): ByteArray {
        require(envelope.version == 1) { "Unsupported secure envelope version." }
        require(envelope.suite == suiteId) { "Unsupported secure envelope suite." }
        require(envelope.metadata.senderDeviceId == sender.deviceId) { "Sender identity mismatch." }
        require(envelope.metadata.senderDeviceGeneration == sender.generation) {
            "Sender credential generation mismatch."
        }

        val unsigned = envelope.copy(signature = "")
        val verifier = TinkJsonProtoKeysetFormat.parseKeysetWithoutSecret(sender.signingPublicKeyset)
            .getPrimitive(config, PublicKeyVerify::class.java)
        verifier.verify(Base64.getDecoder().decode(envelope.signature), signingBytes(unsigned))

        val wrapped = envelope.recipientKeys.firstOrNull {
            it.deviceId == recipient.public.deviceId && it.generation == recipient.public.generation
        } ?: error("Envelope is not addressed to this authorized device.")
        val associatedData = associatedData(envelope.metadata)
        val serializedPayloadKey = parseSecret(recipient.hpkePrivateKeyset)
            .getPrimitive(config, HybridDecrypt::class.java)
            .decrypt(Base64.getDecoder().decode(wrapped.wrappedKey), associatedData)
        val payloadKeyset = parseSecret(serializedPayloadKey.toString(StandardCharsets.UTF_8))
        return payloadKeyset.getPrimitive(config, Aead::class.java)
            .decrypt(Base64.getDecoder().decode(envelope.ciphertext), associatedData)
    }

    private fun associatedData(metadata: E2eeMessageMetadata): ByteArray =
        json.encodeToString(metadata).toByteArray(StandardCharsets.UTF_8)

    private fun signingBytes(envelope: NorfoldE2eeEnvelopeV1): ByteArray =
        json.encodeToString(envelope).toByteArray(StandardCharsets.UTF_8)

    private fun serializeSecret(handle: KeysetHandle): String =
        TinkJsonProtoKeysetFormat.serializeKeyset(handle, InsecureSecretKeyAccess.get())

    private fun parseSecret(serialized: String): KeysetHandle =
        TinkJsonProtoKeysetFormat.parseKeyset(serialized, InsecureSecretKeyAccess.get())

    private fun b64(value: ByteArray): String = Base64.getEncoder().withoutPadding().encodeToString(value)
}
