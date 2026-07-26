package com.norfold.app.domain.chat

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

@Serializable
data class SecureChatAttachmentV1(
    val version: Int = 1,
    val mediaId: String,
    val storagePath: String,
    val messageId: String,
    val securityEpochId: String,
    val displayName: String,
    val mimeType: String,
    val plaintextSize: Long,
    val ciphertextSize: Long,
    val key: String,
    val nonce: String,
    val ciphertextSha256: String,
    val plaintextSha256: String,
)

data class EncryptedChatMedia(
    val descriptor: SecureChatAttachmentV1,
    val ciphertext: ByteArray,
)

/** Independent byte-encryption boundary for Chat media stored outside message envelopes. */
object ChatMediaCrypto {
    const val UriPrefix = "norfold-secure-media:"
    private const val TagBits = 128
    private const val KeyBytes = 32
    private const val NonceBytes = 12
    private val random = SecureRandom()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun encryptedSize(plaintextSize: Long): Long = Math.addExact(plaintextSize, TagBits / 8L)

    fun encrypt(
        plaintext: ByteArray,
        mediaId: String,
        storagePath: String,
        messageId: String,
        securityEpochId: String,
        displayName: String,
        mimeType: String,
    ): EncryptedChatMedia {
        require(plaintext.isNotEmpty()) { "Empty attachments are not supported." }
        val key = ByteArray(KeyBytes).also(random::nextBytes)
        val nonce = ByteArray(NonceBytes).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TagBits, nonce))
        cipher.updateAAD(aad(mediaId, storagePath, messageId, securityEpochId, mimeType))
        val ciphertext = cipher.doFinal(plaintext)
        return EncryptedChatMedia(
            descriptor = SecureChatAttachmentV1(
                mediaId = mediaId,
                storagePath = storagePath,
                messageId = messageId,
                securityEpochId = securityEpochId,
                displayName = displayName,
                mimeType = mimeType,
                plaintextSize = plaintext.size.toLong(),
                ciphertextSize = ciphertext.size.toLong(),
                key = b64(key),
                nonce = b64(nonce),
                ciphertextSha256 = sha256(ciphertext),
                plaintextSha256 = sha256(plaintext),
            ),
            ciphertext = ciphertext,
        )
    }

    fun decrypt(ciphertext: ByteArray, descriptor: SecureChatAttachmentV1): ByteArray {
        require(descriptor.version == 1) { "Unsupported secure media version." }
        require(ciphertext.size.toLong() == descriptor.ciphertextSize) { "Secure media size mismatch." }
        require(sha256(ciphertext) == descriptor.ciphertextSha256) { "Secure media digest mismatch." }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(unb64(descriptor.key), "AES"),
            GCMParameterSpec(TagBits, unb64(descriptor.nonce)),
        )
        cipher.updateAAD(
            aad(
                descriptor.mediaId,
                descriptor.storagePath,
                descriptor.messageId,
                descriptor.securityEpochId,
                descriptor.mimeType,
            ),
        )
        val plaintext = cipher.doFinal(ciphertext)
        require(plaintext.size.toLong() == descriptor.plaintextSize) { "Secure media plaintext size mismatch." }
        require(sha256(plaintext) == descriptor.plaintextSha256) { "Secure media plaintext digest mismatch." }
        return plaintext
    }

    fun toUri(descriptor: SecureChatAttachmentV1): String =
        UriPrefix + b64(json.encodeToString(descriptor).encodeToByteArray())

    fun fromUri(value: String): SecureChatAttachmentV1? {
        if (!value.startsWith(UriPrefix)) return null
        return runCatching {
            json.decodeFromString<SecureChatAttachmentV1>(unb64(value.removePrefix(UriPrefix)).decodeToString())
        }.getOrNull()
    }

    private fun aad(
        mediaId: String,
        storagePath: String,
        messageId: String,
        securityEpochId: String,
        mimeType: String,
    ): ByteArray = "norfold-chat-media-v1\n$mediaId\n$storagePath\n$messageId\n$securityEpochId\n$mimeType"
        .encodeToByteArray()

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }

    private fun b64(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    private fun unb64(value: String): ByteArray = Base64.getUrlDecoder().decode(value)
}
