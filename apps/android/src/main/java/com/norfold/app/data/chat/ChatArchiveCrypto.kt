package com.norfold.app.data.chat

import android.content.Context
import com.google.crypto.tink.Aead
import com.google.crypto.tink.InsecureSecretKeyAccess
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.TinkJsonProtoKeysetFormat
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.aead.PredefinedAeadParameters
import com.norfold.app.domain.chat.ArchiveCryptoEngine
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.Base64

/** Separate archive master state; live E2EE device keys are never reused here. */
class TinkChatArchiveCrypto(context: Context) : ArchiveCryptoEngine {
    override val formatId = "norfold-private-chat-archive-aes256gcm-v1"
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("norfold_chat_archive_key_v1", Context.MODE_PRIVATE)
    private val protector = AndroidKeystoreProtector("norfold.chat.archive.wrap.v1")
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
    private val config = RegistryConfiguration.get()

    init { AeadConfig.register() }

    override fun seal(archiveId: String, plaintext: ByteArray, createdAt: Long): String {
        require(archiveId.isNotBlank() && plaintext.isNotEmpty())
        val handle = keyset(archiveId)
        val ciphertext = handle.getPrimitive(config, Aead::class.java)
            .encrypt(plaintext, associatedData(archiveId))
        return json.encodeToString(
            ChatArchiveEnvelope(
                version = 2,
                format = formatId,
                archiveId = archiveId,
                createdAt = createdAt,
                ciphertext = Base64.getEncoder().withoutPadding().encodeToString(ciphertext),
                recoveryKeyset = wrapForRecovery(
                    archiveId = archiveId,
                    serializedKeyset = serialize(handle),
                    recoveryMaterial = getOrCreateRecoveryMaterial(archiveId),
                ),
            ),
        )
    }

    override fun open(archiveId: String, envelope: String): ByteArray {
        val decoded = json.decodeFromString<ChatArchiveEnvelope>(envelope)
        require(decoded.version in 1..2) { "Unsupported private Chat archive envelope version." }
        require(decoded.format == formatId && decoded.archiveId == archiveId) {
            "Private Chat archive identity mismatch."
        }
        return keyset(archiveId).getPrimitive(config, Aead::class.java).decrypt(
            Base64.getDecoder().decode(decoded.ciphertext),
            associatedData(archiveId),
        )
    }

    override fun getOrCreateRecoveryMaterial(archiveId: String): String {
        require(archiveId.isNotBlank())
        val preferenceKey = recoveryMaterialKey(archiveId)
        prefs.getString(preferenceKey, null)?.let { wrapped ->
            return protector.decrypt(wrapped).decodeToString()
        }
        val encoded = PrivateChatRecoveryKeysetCipher.createRecoveryMaterial()
        check(
            prefs.edit().putString(
                preferenceKey,
                protector.encrypt(encoded.encodeToByteArray()),
            ).commit(),
        ) { "Could not persist private Chat recovery material." }
        return encoded
    }

    override fun openWithRecovery(
        archiveId: String,
        envelope: String,
        recoveryMaterial: String,
    ): ByteArray {
        val decoded = json.decodeFromString<ChatArchiveEnvelope>(envelope)
        require(decoded.version == 2) { "This private Chat archive does not support portable recovery." }
        require(decoded.format == formatId && decoded.archiveId == archiveId) {
            "Private Chat archive identity mismatch."
        }
        val wrapped = requireNotNull(decoded.recoveryKeyset) {
            "This older private Chat archive requires one of its original trusted devices."
        }
        val canonicalRecoveryMaterial =
            PrivateChatRecoveryKeysetCipher.canonicalRecoveryMaterial(recoveryMaterial)
        val serialized = PrivateChatRecoveryKeysetCipher.unwrap(
            formatId,
            archiveId,
            wrapped,
            canonicalRecoveryMaterial,
        )
        val recovered = try {
            TinkJsonProtoKeysetFormat.parseKeyset(serialized, InsecureSecretKeyAccess.get())
        } finally {
            // String memory cannot be zeroed; the byte-level recovery material and key are kept
            // out of logs and persisted only through the Keystore wrapper below.
        }
        // Authenticate/decrypt before replacing this device's current archive key. A typo or a
        // malicious archive must not destroy the only working local key.
        val plaintext = recovered.getPrimitive(config, Aead::class.java).decrypt(
            Base64.getDecoder().decode(decoded.ciphertext),
            associatedData(archiveId),
        )
        persistRecoveredState(archiveId, recovered, canonicalRecoveryMaterial)
        return plaintext
    }

    @Synchronized
    private fun keyset(archiveId: String): KeysetHandle {
        val wrapped = prefs.getString(keysetKey(archiveId), null)
        if (wrapped != null) {
            return TinkJsonProtoKeysetFormat.parseKeyset(
                protector.decrypt(wrapped).decodeToString(),
                InsecureSecretKeyAccess.get(),
            )
        }
        val generated = KeysetHandle.generateNew(PredefinedAeadParameters.AES256_GCM)
        persistKeyset(archiveId, generated)
        return generated
    }

    private fun persistKeyset(archiveId: String, handle: KeysetHandle) {
        val serialized = serialize(handle)
        check(
            prefs.edit().putString(
                keysetKey(archiveId),
                protector.encrypt(serialized.encodeToByteArray()),
            ).commit(),
        ) { "Could not persist private Chat archive key." }
    }

    private fun persistRecoveredState(
        archiveId: String,
        handle: KeysetHandle,
        recoveryMaterial: String,
    ) {
        val wrappedKeyset = protector.encrypt(serialize(handle).encodeToByteArray())
        val wrappedRecoveryMaterial = protector.encrypt(recoveryMaterial.encodeToByteArray())
        check(
            prefs.edit()
                .putString(keysetKey(archiveId), wrappedKeyset)
                .putString(recoveryMaterialKey(archiveId), wrappedRecoveryMaterial)
                .commit(),
        ) { "Could not persist recovered private Chat archive state." }
    }

    private fun serialize(handle: KeysetHandle): String = TinkJsonProtoKeysetFormat.serializeKeyset(
        handle,
        InsecureSecretKeyAccess.get(),
    )

    private fun wrapForRecovery(
        archiveId: String,
        serializedKeyset: String,
        recoveryMaterial: String,
    ): RecoveryWrappedKeyset = PrivateChatRecoveryKeysetCipher.wrap(
        formatId,
        archiveId,
        serializedKeyset,
        recoveryMaterial,
    )

    private fun associatedData(archiveId: String) = "$formatId\n$archiveId".encodeToByteArray()
    private fun keysetKey(archiveId: String) = "wrapped_keyset_$archiveId"
    private fun recoveryMaterialKey(archiveId: String) = "wrapped_recovery_material_$archiveId"
}

@Serializable
private data class ChatArchiveEnvelope(
    val version: Int = 1,
    val format: String,
    val archiveId: String,
    val createdAt: Long,
    val ciphertext: String,
    val recoveryKeyset: RecoveryWrappedKeyset? = null,
)
