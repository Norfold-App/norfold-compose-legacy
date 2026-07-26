package com.norfold.app.data.chat

import kotlinx.serialization.Serializable
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

@Serializable
internal data class RecoveryWrappedKeyset(
    val nonce: String,
    val ciphertext: String,
)

/** Pure-JCA recovery wrapper kept separate from Android Keystore state for deterministic testing. */
internal object PrivateChatRecoveryKeysetCipher {
    private const val AesGcm = "AES/GCM/NoPadding"
    private const val GcmTagBits = 128
    private const val NonceBytes = 12
    private const val RecoveryBytes = 32

    fun createRecoveryMaterial(): String {
        val bytes = ByteArray(RecoveryBytes).also(SecureRandom()::nextBytes)
        return try {
            Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        } finally {
            bytes.fill(0)
        }
    }

    fun wrap(
        formatId: String,
        archiveId: String,
        serializedKeyset: String,
        recoveryMaterial: String,
    ): RecoveryWrappedKeyset {
        require(formatId.isNotBlank() && archiveId.isNotBlank() && serializedKeyset.isNotBlank())
        val keyBytes = decodeRecoveryMaterial(recoveryMaterial)
        return try {
            val cipher = Cipher.getInstance(AesGcm)
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(keyBytes, "AES"))
            cipher.updateAAD(associatedData(formatId, archiveId))
            RecoveryWrappedKeyset(
                nonce = Base64.getEncoder().withoutPadding().encodeToString(cipher.iv),
                ciphertext = Base64.getEncoder().withoutPadding().encodeToString(
                    cipher.doFinal(serializedKeyset.encodeToByteArray()),
                ),
            )
        } finally {
            keyBytes.fill(0)
        }
    }

    fun unwrap(
        formatId: String,
        archiveId: String,
        wrapped: RecoveryWrappedKeyset,
        recoveryMaterial: String,
    ): String {
        require(formatId.isNotBlank() && archiveId.isNotBlank())
        val nonce = decodeStandard(wrapped.nonce, "Private Chat recovery nonce is invalid.")
        require(nonce.size == NonceBytes) { "Private Chat recovery nonce is invalid." }
        val ciphertext = decodeStandard(wrapped.ciphertext, "Private Chat recovery key is invalid.")
        require(ciphertext.size > GcmTagBits / Byte.SIZE_BITS) { "Private Chat recovery key is invalid." }
        val keyBytes = decodeRecoveryMaterial(recoveryMaterial)
        return try {
            val cipher = Cipher.getInstance(AesGcm)
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(keyBytes, "AES"),
                GCMParameterSpec(GcmTagBits, nonce),
            )
            cipher.updateAAD(associatedData(formatId, archiveId))
            cipher.doFinal(ciphertext).decodeToString()
        } finally {
            keyBytes.fill(0)
            nonce.fill(0)
            ciphertext.fill(0)
        }
    }

    fun canonicalRecoveryMaterial(value: String): String {
        val bytes = decodeRecoveryMaterial(value)
        return try {
            Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        } finally {
            bytes.fill(0)
        }
    }

    private fun decodeRecoveryMaterial(value: String): ByteArray = runCatching {
        Base64.getUrlDecoder().decode(value.trim())
    }.getOrElse { throw IllegalArgumentException("Private Chat recovery material is invalid.") }
        .also { require(it.size == RecoveryBytes) { "Private Chat recovery material is invalid." } }

    private fun decodeStandard(value: String, error: String): ByteArray = runCatching {
        Base64.getDecoder().decode(value)
    }.getOrElse { throw IllegalArgumentException(error) }

    private fun associatedData(formatId: String, archiveId: String) =
        "$formatId\n$archiveId\nrecovery-keyset-v1".encodeToByteArray()
}
