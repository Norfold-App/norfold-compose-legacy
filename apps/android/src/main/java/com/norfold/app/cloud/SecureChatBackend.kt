package com.norfold.app.cloud

import android.content.Context
import android.os.Build
import com.norfold.app.data.chat.ChatDeviceIdentityStore
import com.norfold.app.domain.chat.DevicePublicMaterial
import com.norfold.app.domain.chat.E2eeMessageMetadata
import com.norfold.app.domain.chat.NorfoldE2eeEnvelopeV1
import com.norfold.app.domain.chat.SecureChatPayloadV1
import com.norfold.app.domain.chat.SecureChatAttachmentV1
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
import java.util.UUID

/**
 * Supabase control/relay adapter for opaque account-chat payloads. The backend
 * sees routing metadata and ciphertext only; plaintext conversion stays here.
 */
class SecureChatBackend(context: Context) {
    private val identityStore = ChatDeviceIdentityStore(context.applicationContext)
    private val crypto = TinkCryptoEngine()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }
    private val client get() = NorfoldSupabase.client

    data class SentPayload(
        val relayId: String,
        val securityEpochId: String,
        val originPayloadJson: String,
    )

    fun currentDevicePublic(): DevicePublicMaterial = identityStore.publicIdentity()

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
        @SerialName("origin_client_sync_id") val originClientSyncId: String? = null,
        @SerialName("origin_sender_id") val originSenderId: String? = null,
        @SerialName("origin_sender_device_id") val originSenderDeviceId: String? = null,
        @SerialName("origin_sender_crypto_generation") val originSenderCryptoGeneration: Int? = null,
        @SerialName("origin_sender_hpke_public_keyset") val originSenderHpkePublicKeyset: String? = null,
        @SerialName("origin_sender_signing_public_keyset") val originSenderSigningPublicKeyset: String? = null,
        @SerialName("recovery_request_id") val recoveryRequestId: String? = null,
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
        val originPayloadJson: String?,
    )

    @Serializable
    data class PendingRecoveryRequest(
        @SerialName("request_id") val requestId: String,
        @SerialName("object_id") val objectId: String,
        @SerialName("security_epoch_id") val securityEpochId: String,
        @SerialName("requester_id") val requesterId: String,
        @SerialName("requester_device_id") val requesterDeviceId: String,
        @SerialName("requester_crypto_generation") val requesterCryptoGeneration: Int,
        @SerialName("requester_hpke_public_keyset") val requesterHpkePublicKeyset: String,
        @SerialName("created_at") val createdAt: String,
        @SerialName("expires_at") val expiresAt: String,
    )

    @Serializable
    data class HiddenObject(
        @SerialName("object_id") val objectId: String,
        @SerialName("security_epoch_id") val securityEpochId: String,
        @SerialName("conversation_id") val conversationId: String,
        @SerialName("hidden_at") val hiddenAt: String,
        val reason: String,
    )

    @Serializable
    data class RecoverableObject(
        @SerialName("object_id") val objectId: String,
        @SerialName("security_epoch_id") val securityEpochId: String,
        @SerialName("created_at") val createdAt: String,
    )

    @Serializable
    data class RemoteTombstone(
        @SerialName("object_id") val objectId: String,
        @SerialName("security_epoch_id") val securityEpochId: String,
        @SerialName("conversation_id") val conversationId: String,
        @SerialName("destructive_at") val destructiveAt: String,
        val reason: String,
    )

    suspend fun registerCurrentDevice(
        friendlyName: String = Build.MODEL?.trim().orEmpty().ifBlank { "Android device" },
        // Every supported Android install has the encrypted local ArchiveProvider. Cloud
        // availability is separate; this flag only says the device can serve authorized
        // encrypted recovery while active.
        archiveCapable: Boolean = true,
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
        expiresAt: Long? = null,
    ): SentPayload = sendPayload(
        conversationId = conversationId,
        clientSyncId = clientSyncId,
        payload = SecureChatPayloadV1(
            body = body,
            createdAt = createdAt,
            replyPreview = replyPreview,
            expiresAt = expiresAt,
        ),
    )

    suspend fun prepareAccountSecurityEpoch(conversationId: String): String =
        ensureAccountEpoch(conversationId)

    suspend fun sendAccountMedia(
        conversationId: String,
        securityEpochId: String,
        clientSyncId: String,
        body: String,
        createdAt: Long,
        attachment: SecureChatAttachmentV1,
        expiresAt: Long? = null,
    ): SentPayload = sendPayloadForEpoch(
        conversationId = conversationId,
        epochId = securityEpochId,
        clientSyncId = clientSyncId,
        payload = SecureChatPayloadV1(
            type = "media",
            body = body,
            createdAt = createdAt,
            expiresAt = expiresAt,
            attachment = attachment,
        ),
    )

    suspend fun sendMutation(
        conversationId: String,
        clientSyncId: String,
        targetMessageId: String,
        type: String,
        body: String = "",
        editedAt: Long? = null,
        reactionsJson: String? = null,
        pinned: Boolean? = null,
    ): String {
        require(type in setOf("edit", "reactions", "pin", "delete_everyone"))
        val createdAt = System.currentTimeMillis()
        return sendPayload(
            conversationId = conversationId,
            clientSyncId = clientSyncId,
            payload = SecureChatPayloadV1(
                type = type,
                body = body,
                createdAt = createdAt,
                targetMessageId = targetMessageId,
                editedAt = editedAt,
                reactionsJson = reactionsJson,
                pinned = pinned,
            ),
        ).relayId
    }

    suspend fun sendWorkspaceText(
        workspaceId: String,
        channelId: String?,
        clientSyncId: String,
        body: String,
        createdAt: Long,
        replyPreview: String? = null,
        expiresAt: Long? = null,
    ): SentPayload = sendWorkspacePayload(
        workspaceId = workspaceId,
        channelId = channelId,
        clientSyncId = clientSyncId,
        payload = SecureChatPayloadV1(
            type = "text",
            body = body,
            createdAt = createdAt,
            replyPreview = replyPreview,
            expiresAt = expiresAt,
        ),
    )

    suspend fun sendWorkspaceMedia(
        workspaceId: String,
        channelId: String?,
        securityEpochId: String,
        clientSyncId: String,
        body: String,
        createdAt: Long,
        attachment: SecureChatAttachmentV1,
        expiresAt: Long? = null,
    ): SentPayload {
        val security = ChatBackend.ensureWorkspaceSecurityEpoch(workspaceId, channelId)
        require(security.securityMode == com.norfold.app.domain.ChatSecurityMode.EndToEndEncrypted)
        require(security.securityEpochId == securityEpochId) { "Workspace security epoch changed during upload." }
        require(security.acknowledged) { "Acknowledge this workspace's security disclosure before sending." }
        return sendPayloadForEpoch(
            conversationId = channelId ?: workspaceId,
            epochId = securityEpochId,
            clientSyncId = clientSyncId,
            payload = SecureChatPayloadV1(
                type = "media",
                body = body,
                createdAt = createdAt,
                expiresAt = expiresAt,
                attachment = attachment,
            ),
        )
    }

    suspend fun sendWorkspaceMutation(
        workspaceId: String,
        channelId: String?,
        clientSyncId: String,
        targetMessageId: String,
        type: String,
        body: String = "",
        editedAt: Long? = null,
        reactionsJson: String? = null,
        pinned: Boolean? = null,
    ): String = sendWorkspacePayload(
        workspaceId = workspaceId,
        channelId = channelId,
        clientSyncId = clientSyncId,
        payload = SecureChatPayloadV1(
            type = type,
            body = body,
            createdAt = System.currentTimeMillis(),
            targetMessageId = targetMessageId,
            editedAt = editedAt,
            reactionsJson = reactionsJson,
            pinned = pinned,
        ),
    ).relayId

    private suspend fun sendPayload(
        conversationId: String,
        clientSyncId: String,
        payload: SecureChatPayloadV1,
    ): SentPayload {
        val epochId = ensureAccountEpoch(conversationId)
        return sendPayloadForEpoch(conversationId, epochId, clientSyncId, payload)
    }

    private suspend fun sendWorkspacePayload(
        workspaceId: String,
        channelId: String?,
        clientSyncId: String,
        payload: SecureChatPayloadV1,
    ): SentPayload {
        registerCurrentDevice()
        renewLease()
        val security = ChatBackend.ensureWorkspaceSecurityEpoch(workspaceId, channelId)
        require(security.securityMode == com.norfold.app.domain.ChatSecurityMode.EndToEndEncrypted) {
            "Workspace E2EE transport cannot send Managed content."
        }
        require(security.acknowledged) {
            "Acknowledge this workspace's security disclosure before sending."
        }
        val epochId = requireNotNull(security.securityEpochId) {
            "Workspace E2EE epoch is unavailable."
        }
        return sendPayloadForEpoch(channelId ?: workspaceId, epochId, clientSyncId, payload)
    }

    private suspend fun sendPayloadForEpoch(
        conversationId: String,
        epochId: String,
        clientSyncId: String,
        payload: SecureChatPayloadV1,
    ): SentPayload {
        val accountId = NorfoldSupabase.currentAccountId()
            ?: error("Sign in before sending a secure message.")
        val sender = identityStore.getOrCreate()
        registerCurrentDevice()
        renewLease()
        val recipients = securityEpochCredentials(epochId).map { it.publicMaterial() }
        val metadata = E2eeMessageMetadata(
            conversationId = conversationId,
            messageId = clientSyncId,
            senderAccountId = accountId,
            senderDeviceId = sender.public.deviceId,
            senderDeviceGeneration = sender.public.generation,
            securityEpochId = epochId,
            createdAt = payload.createdAt,
            contentType = payload.type,
            targetMessageId = payload.targetMessageId,
            lifespanJson = payload.expiresAt?.toString(),
        )
        val signedPayload = payload.copy(
            originProof = crypto.createOriginProof(payload.copy(originProof = null), metadata, sender),
        )
        val plaintext = json.encodeToString(signedPayload).encodeToByteArray()
        val envelope = crypto.seal(plaintext, metadata, sender, recipients)
        val relayId = client.postgrest.rpc(
            function = "send_e2ee_relay_message",
            parameters = buildJsonObject {
                put("p_client_sync_id", clientSyncId)
                put("p_security_epoch", epochId)
                put("p_sender_device", sender.public.deviceId)
                put("p_envelope_version", envelope.version)
                put("p_envelope", json.encodeToJsonElement(envelope))
            },
        ).data.trim('"')
        return SentPayload(
            relayId = relayId,
            securityEpochId = epochId,
            originPayloadJson = json.encodeToString(signedPayload),
        )
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
        if (remote.recoveryRequestId == null) {
            require(payload.createdAt == envelope.metadata.createdAt) { "Secure payload timestamp mismatch." }
        }
        require(payload.expiresAt?.toString() == envelope.metadata.lifespanJson) {
            "Secure payload lifespan mismatch."
        }
        val originalSender = DevicePublicMaterial(
            deviceId = remote.originSenderDeviceId ?: remote.senderDeviceId,
            generation = remote.originSenderCryptoGeneration ?: remote.senderCryptoGeneration,
            hpkePublicKeyset = remote.originSenderHpkePublicKeyset ?: remote.senderHpkePublicKeyset,
            signingPublicKeyset = remote.originSenderSigningPublicKeyset ?: remote.senderSigningPublicKeyset,
        )
        val originMessageId = remote.originClientSyncId ?: remote.clientSyncId
        val originSenderId = remote.originSenderId ?: remote.senderId
        val proof = payload.originProof
        if (proof == null) {
            require(remote.recoveryRequestId == null) { "Recovered content has no original-author proof." }
        } else {
            require(proof.statement.messageId == originMessageId) { "Recovered message identity mismatch." }
            require(proof.statement.conversationId == envelope.metadata.conversationId) {
                "Recovered conversation identity mismatch."
            }
            require(proof.statement.securityEpochId == remote.securityEpochId) {
                "Recovered security epoch mismatch."
            }
            require(proof.statement.senderAccountId == originSenderId) {
                "Recovered sender identity mismatch."
            }
            crypto.verifyOriginProof(payload, proof, originalSender)
        }
        return DecryptedRelay(
            relayId = remote.relayId,
            clientSyncId = originMessageId,
            senderId = originSenderId,
            conversationId = envelope.metadata.conversationId,
            securityEpochId = remote.securityEpochId,
            payload = payload,
            envelopeCreatedAt = envelope.metadata.createdAt,
            originPayloadJson = payload.originProof?.let { json.encodeToString(payload) },
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

    suspend fun requestRecovery(objectId: String, securityEpochId: String): String {
        val device = identityStore.getOrCreate().public
        return client.postgrest.rpc(
            function = "request_encrypted_recovery",
            parameters = buildJsonObject {
                put("p_object", objectId)
                put("p_security_epoch", securityEpochId)
                put("p_requester_device", device.deviceId)
            },
        ).data.trim('"')
    }

    suspend fun pendingRecoveryRequests(limit: Int = 100): List<PendingRecoveryRequest> {
        val device = identityStore.getOrCreate().public
        return client.postgrest.rpc(
            function = "pending_encrypted_recovery_requests",
            parameters = buildJsonObject {
                put("p_device", device.deviceId)
                put("p_limit", limit.coerceIn(1, 250))
            },
        ).decodeList()
    }

    suspend fun fulfillRecovery(
        request: PendingRecoveryRequest,
        conversationId: String,
        originPayloadJson: String,
    ): String {
        val payload = json.decodeFromString<SecureChatPayloadV1>(originPayloadJson)
        val proof = requireNotNull(payload.originProof) { "Archived message has no original-author proof." }
        require(proof.statement.messageId == request.objectId)
        require(proof.statement.securityEpochId == request.securityEpochId)
        require(proof.statement.conversationId == conversationId)
        val sender = identityStore.getOrCreate()
        registerCurrentDevice()
        renewLease()
        val recoverySyncId = UUID.randomUUID().toString()
        val metadata = E2eeMessageMetadata(
            conversationId = conversationId,
            messageId = recoverySyncId,
            senderAccountId = NorfoldSupabase.currentAccountId()
                ?: error("Sign in before fulfilling secure recovery."),
            senderDeviceId = sender.public.deviceId,
            senderDeviceGeneration = sender.public.generation,
            securityEpochId = request.securityEpochId,
            createdAt = System.currentTimeMillis(),
            contentType = "recovery",
            targetMessageId = request.objectId,
            lifespanJson = payload.expiresAt?.toString(),
        )
        val envelope = crypto.seal(
            plaintext = originPayloadJson.encodeToByteArray(),
            metadata = metadata,
            sender = sender,
            recipients = securityEpochCredentials(request.securityEpochId).map { it.publicMaterial() },
        )
        return client.postgrest.rpc(
            function = "fulfill_encrypted_recovery",
            parameters = buildJsonObject {
                put("p_request", request.requestId)
                put("p_sender_device", sender.public.deviceId)
                put("p_client_sync_id", recoverySyncId)
                put("p_envelope", json.encodeToJsonElement(envelope))
            },
        ).data.trim('"')
    }

    suspend fun hideForCurrentUser(
        objectId: String,
        securityEpochId: String,
        hiddenAt: Long = System.currentTimeMillis(),
    ) {
        client.postgrest.rpc(
            function = "hide_chat_object_for_me",
            parameters = buildJsonObject {
                put("p_object", objectId)
                put("p_security_epoch", securityEpochId)
                put("p_hidden_at_millis", hiddenAt)
            },
        )
    }

    suspend fun unhideForCurrentUser(objectId: String) {
        client.postgrest.rpc(
            function = "unhide_chat_object_for_me",
            parameters = buildJsonObject { put("p_object", objectId) },
        )
    }

    suspend fun hiddenForCurrentUser(): List<HiddenObject> = client.postgrest.rpc(
        function = "hidden_chat_objects_for_me",
        parameters = buildJsonObject { },
    ).decodeList()

    suspend fun recoverableObjects(limit: Int = 250): List<RecoverableObject> =
        client.postgrest.rpc(
            function = "recoverable_chat_objects",
            parameters = buildJsonObject { put("p_limit", limit.coerceIn(1, 500)) },
        ).decodeList()

    suspend fun tombstones(limit: Int = 500): List<RemoteTombstone> =
        client.postgrest.rpc(
            function = "chat_tombstones_for_me",
            parameters = buildJsonObject { put("p_limit", limit.coerceIn(1, 2_000)) },
        ).decodeList()

    private suspend fun ensureAccountEpoch(conversationId: String): String =
        client.postgrest.rpc(
            function = "ensure_account_security_epoch",
            parameters = buildJsonObject {
                put("p_conversation", conversationId)
                put("p_protocol", crypto.suiteId)
            },
        ).data.trim('"')

    private suspend fun securityEpochCredentials(epochId: String): List<RemoteDeviceCredential> =
        client.postgrest.rpc(
            function = "security_epoch_device_credentials",
            parameters = buildJsonObject { put("p_security_epoch", epochId) },
        ).decodeList()
}
