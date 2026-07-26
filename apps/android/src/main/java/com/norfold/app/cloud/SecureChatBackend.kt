package com.norfold.app.cloud

import android.content.Context
import android.os.Build
import com.norfold.app.data.chat.ChatDeviceIdentityStore
import com.norfold.app.domain.chat.DevicePublicMaterial
import com.norfold.app.domain.chat.E2eeMessageMetadata
import com.norfold.app.domain.chat.NorfoldE2eeEnvelopeV1
import com.norfold.app.domain.chat.SecureChatPayloadV1
import com.norfold.app.domain.chat.TinkCryptoEngine
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put

/**
 * Supabase control/relay adapter for opaque account-chat payloads. The backend
 * sees routing metadata and ciphertext only; plaintext conversion stays here.
 */
class SecureChatBackend(context: Context) {
    private val identityStore = ChatDeviceIdentityStore(context.applicationContext)
    private val crypto = TinkCryptoEngine()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }
    private val client get() = NorfoldSupabase.client

    @Serializable
    data class RemoteDeviceCredential(
        @SerialName("device_id") val deviceId: String,
        @SerialName("user_id") val userId: String,
        @SerialName("crypto_generation") val cryptoGeneration: Int,
        @SerialName("hpke_public_keyset") val hpkePublicKeyset: String,
        @SerialName("signing_public_keyset") val signingPublicKeyset: String,
        @SerialName("credential_fingerprint") val credentialFingerprint: String,
    ) {
        fun publicMaterial() = DevicePublicMaterial(
            deviceId = deviceId,
            generation = cryptoGeneration,
            hpkePublicKeyset = hpkePublicKeyset,
            signingPublicKeyset = signingPublicKeyset,
        )
    }

    @Serializable
    data class PendingRelay(
        @SerialName("relay_id") val relayId: String,
        @SerialName("client_sync_id") val clientSyncId: String,
        @SerialName("security_epoch_id") val securityEpochId: String,
        @SerialName("sender_id") val senderId: String,
        @SerialName("sender_device_id") val senderDeviceId: String,
        @SerialName("sender_crypto_generation") val senderCryptoGeneration: Int,
        @SerialName("sender_hpke_public_keyset") val senderHpkePublicKeyset: String,
        @SerialName("sender_signing_public_keyset") val senderSigningPublicKeyset: String,
        val envelope: JsonObject,
        @SerialName("created_at") val createdAt: String,
    )

    data class DecryptedRelay(
        val relayId: String,
        val clientSyncId: String,
        val senderId: String,
        val conversationId: String,
        val securityEpochId: String,
        val payload: SecureChatPayloadV1,
        val envelopeCreatedAt: Long,
    )

    suspend fun registerCurrentDevice(
        friendlyName: String = Build.MODEL?.trim().orEmpty().ifBlank { "Android device" },
        archiveCapable: Boolean = false,
    ): DevicePublicMaterial {
        val identity = identityStore.getOrCreate()
        client.postgrest.rpc(
            function = "register_chat_device",
            parameters = buildJsonObject {
                put("p_device", identity.public.deviceId)
                put("p_friendly_name", friendlyName.take(80))
                put("p_crypto_generation", identity.public.generation)
                put("p_hpke_public_keyset", identity.public.hpkePublicKeyset)
                put("p_signing_public_keyset", identity.public.signingPublicKeyset)
                put("p_credential_fingerprint", identity.public.credentialFingerprint)
                put("p_archive_capable", archiveCapable)
            },
        )
        return identity.public
    }

    suspend fun renewLease(seconds: Int = 120): String {
        val identity = identityStore.getOrCreate().public
        return client.postgrest.rpc(
            function = "renew_chat_device_lease",
            parameters = buildJsonObject {
                put("p_device", identity.deviceId)
                put("p_lease_seconds", seconds.coerceIn(30, 300))
            },
        ).data.trim('"')
    }

    suspend fun sendText(
        conversationId: String,
        clientSyncId: String,
        body: String,
        createdAt: Long,
        replyPreview: String? = null,
    ): String {
        val accountId = NorfoldSupabase.currentAccountId()
            ?: error("Sign in before sending a secure message.")
        val sender = identityStore.getOrCreate()
        registerCurrentDevice()
        renewLease()
        val epochId = ensureAccountEpoch(conversationId)
        val recipients = conversationCredentials(conversationId).map { it.publicMaterial() }
        val metadata = E2eeMessageMetadata(
            conversationId = conversationId,
            messageId = clientSyncId,
            senderAccountId = accountId,
            senderDeviceId = sender.public.deviceId,
            senderDeviceGeneration = sender.public.generation,
            securityEpochId = epochId,
            createdAt = createdAt,
        )
        val plaintext = json.encodeToString(
            SecureChatPayloadV1(
                body = body,
                createdAt = createdAt,
                replyPreview = replyPreview,
            ),
        ).encodeToByteArray()
        val envelope = crypto.seal(plaintext, metadata, sender, recipients)
        return client.postgrest.rpc(
            function = "send_e2ee_relay_message",
            parameters = buildJsonObject {
                put("p_client_sync_id", clientSyncId)
                put("p_security_epoch", epochId)
                put("p_sender_device", sender.public.deviceId)
                put("p_envelope_version", envelope.version)
                put("p_envelope", json.encodeToJsonElement(envelope))
            },
        ).data.trim('"')
    }

    suspend fun pending(limit: Int = 250): List<PendingRelay> {
        val identity = identityStore.getOrCreate().public
        return client.postgrest.rpc(
            function = "pending_e2ee_relay_messages",
            parameters = buildJsonObject {
                put("p_device", identity.deviceId)
                put("p_crypto_generation", identity.generation)
                put("p_limit", limit.coerceIn(1, 500))
            },
        ).decodeList()
    }

    fun decrypt(remote: PendingRelay): DecryptedRelay {
        val recipient = identityStore.getOrCreate()
        val sender = DevicePublicMaterial(
            deviceId = remote.senderDeviceId,
            generation = remote.senderCryptoGeneration,
            hpkePublicKeyset = remote.senderHpkePublicKeyset,
            signingPublicKeyset = remote.senderSigningPublicKeyset,
        )
        val envelope = json.decodeFromJsonElement<NorfoldE2eeEnvelopeV1>(remote.envelope)
        val plaintext = crypto.open(envelope, recipient, sender).decodeToString()
        val payload = json.decodeFromString<SecureChatPayloadV1>(plaintext)
        require(payload.version == 1) { "Unsupported secure payload version." }
        require(payload.createdAt == envelope.metadata.createdAt) { "Secure payload timestamp mismatch." }
        return DecryptedRelay(
            relayId = remote.relayId,
            clientSyncId = remote.clientSyncId,
            senderId = remote.senderId,
            conversationId = envelope.metadata.conversationId,
            securityEpochId = remote.securityEpochId,
            payload = payload,
            envelopeCreatedAt = envelope.metadata.createdAt,
        )
    }

    suspend fun acknowledge(relayId: String, read: Boolean) {
        val identity = identityStore.getOrCreate().public
        client.postgrest.rpc(
            function = "ack_e2ee_relay_message",
            parameters = buildJsonObject {
                put("p_relay", relayId)
                put("p_device", identity.deviceId)
                put("p_crypto_generation", identity.generation)
                put("p_read", read)
            },
        )
    }

    private suspend fun ensureAccountEpoch(conversationId: String): String =
        client.postgrest.rpc(
            function = "ensure_account_security_epoch",
            parameters = buildJsonObject {
                put("p_conversation", conversationId)
                put("p_protocol", crypto.suiteId)
            },
        ).data.trim('"')

    private suspend fun conversationCredentials(conversationId: String): List<RemoteDeviceCredential> =
        client.postgrest.rpc(
            function = "account_conversation_device_credentials",
            parameters = buildJsonObject { put("p_conversation", conversationId) },
        ).decodeList()
}
