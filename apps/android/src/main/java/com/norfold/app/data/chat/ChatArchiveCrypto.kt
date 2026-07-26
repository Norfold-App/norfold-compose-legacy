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
        val ciphertext = keyset().getPrimitive(config, Aead::class.java)
            .encrypt(plaintext, associatedData(archiveId))
        return json.encodeToString(
            ChatArchiveEnvelope(
                format = formatId,
                archiveId = archiveId,
                createdAt = createdAt,
                ciphertext = Base64.getEncoder().withoutPadding().encodeToString(ciphertext),
            ),
        )
    }

    override fun open(archiveId: String, envelope: String): ByteArray {
        val decoded = json.decodeFromString<ChatArchiveEnvelope>(envelope)
        require(decoded.format == formatId && decoded.archiveId == archiveId) {
            "Private Chat archive identity mismatch."
        }
        return keyset().getPrimitive(config, Aead::class.java).decrypt(
            Base64.getDecoder().decode(decoded.ciphertext),
            associatedData(archiveId),
        )
    }

    @Synchronized
    private fun keyset(): KeysetHandle {
        val wrapped = prefs.getString("wrapped_keyset", null)
        if (wrapped != null) {
            return TinkJsonProtoKeysetFormat.parseKeyset(
                protector.decrypt(wrapped).decodeToString(),
                InsecureSecretKeyAccess.get(),
            )
        }
        val generated = KeysetHandle.generateNew(PredefinedAeadParameters.AES256_GCM)
        val serialized = TinkJsonProtoKeysetFormat.serializeKeyset(
            generated,
            InsecureSecretKeyAccess.get(),
        )
        prefs.edit().putString("wrapped_keyset", protector.encrypt(serialized.encodeToByteArray())).commit()
        return generated
    }

    private fun associatedData(archiveId: String) = "$formatId\n$archiveId".encodeToByteArray()
}

@Serializable
private data class ChatArchiveEnvelope(
    val version: Int = 1,
    val format: String,
    val archiveId: String,
    val createdAt: Long,
    val ciphertext: String,
)
