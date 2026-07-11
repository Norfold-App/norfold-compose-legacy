package com.norfold.app.domain

import java.security.MessageDigest
import java.security.SecureRandom
import java.security.spec.KeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

data class EncryptedVault(
    val format: String,
    val iterations: Int,
    val salt: String,
    val nonce: String,
    val cipherText: String,
)

object VaultCrypto {
    const val Format = "NORFOLD-VAULT-V1"
    private const val Iterations = 160_000
    private const val KeyBits = 256
    private const val NonceBytes = 12
    private const val SaltBytes = 16
    private const val TagBits = 128

    fun encrypt(plainText: String, secret: CharArray): String {
        require(secret.isNotEmpty()) { "Secret must not be empty." }
        val salt = randomBytes(SaltBytes)
        val nonce = randomBytes(NonceBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, deriveKey(secret, salt), GCMParameterSpec(TagBits, nonce))
        val cipherText = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
        return """{"format":"$Format","iterations":$Iterations,"salt":"${b64(salt)}","nonce":"${b64(nonce)}","cipherText":"${b64(cipherText)}"}"""
    }

    fun decrypt(payload: String, secret: CharArray): String {
        require(secret.isNotEmpty()) { "Secret must not be empty." }
        val decoded = parsePayload(payload)
        require(decoded.format == Format) { "Unsupported vault format." }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            deriveKey(secret, b64decode(decoded.salt)),
            GCMParameterSpec(TagBits, b64decode(decoded.nonce)),
        )
        return cipher.doFinal(b64decode(decoded.cipherText)).toString(Charsets.UTF_8)
    }

    fun hashSecret(secret: CharArray): String {
        require(secret.isNotEmpty()) { "Secret must not be empty." }
        val salt = randomBytes(SaltBytes)
        val digest = deriveKey(secret, salt).encoded
        return "pbkdf2:$Iterations:${b64(salt)}:${b64(digest)}"
    }

    fun verifySecret(secret: CharArray, stored: String?): Boolean {
        if (stored.isNullOrBlank()) return false
        val parts = stored.split(":")
        if (parts.size != 4 || parts[0] != "pbkdf2") return false
        val salt = b64decode(parts[2])
        val expected = b64decode(parts[3])
        val actual = deriveKey(secret, salt).encoded
        return MessageDigest.isEqual(expected, actual)
    }

    fun parsePayload(payload: String): EncryptedVault {
        val pairs = Regex("\"([^\"]+)\"\\s*:\\s*(\"([^\"]*)\"|\\d+)")
            .findAll(payload)
            .associate { it.groupValues[1] to it.groupValues[3].ifEmpty { it.groupValues[2] } }
        return EncryptedVault(
            format = pairs.getValue("format"),
            iterations = pairs["iterations"]?.toIntOrNull() ?: Iterations,
            salt = pairs.getValue("salt"),
            nonce = pairs.getValue("nonce"),
            cipherText = pairs.getValue("cipherText"),
        )
    }

    private fun deriveKey(secret: CharArray, salt: ByteArray): SecretKeySpec {
        val spec: KeySpec = PBEKeySpec(secret, salt, Iterations, KeyBits)
        return SecretKeySpec(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded, "AES")
    }

    private fun randomBytes(size: Int): ByteArray = ByteArray(size).also { SecureRandom().nextBytes(it) }
    private fun b64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)
    private fun b64decode(value: String): ByteArray = Base64.getDecoder().decode(value)
}
