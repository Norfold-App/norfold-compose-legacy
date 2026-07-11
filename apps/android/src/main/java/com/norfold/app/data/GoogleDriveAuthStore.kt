package com.norfold.app.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class GoogleDriveAuthStore(private val context: Context) {
    private val prefs = context.getSharedPreferences("norfold_google_drive_authorization", Context.MODE_PRIVATE)

    fun hasAuthState(): Boolean = loadAccessToken() != null

    fun loadAccessToken(): String? {
        val encrypted = prefs.getString(AccessTokenKey, null) ?: return null
        return runCatching { decrypt(encrypted) }.getOrNull()?.takeIf(String::isNotBlank)
    }

    fun saveAccessToken(accessToken: String) {
        require(accessToken.isNotBlank()) { "Google access token is empty" }
        prefs.edit().putString(AccessTokenKey, encrypt(accessToken)).apply()
    }

    fun clear() {
        prefs.edit().remove(AccessTokenKey).apply()
    }

    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance(Transform)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val cipherText = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(cipher.iv + cipherText, Base64.NO_WRAP)
    }

    private fun decrypt(value: String): String {
        val payload = Base64.decode(value, Base64.NO_WRAP)
        require(payload.size > IvSizeBytes) { "Stored Google authorization is invalid" }
        val iv = payload.copyOfRange(0, IvSizeBytes)
        val cipher = Cipher.getInstance(Transform)
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(TagSizeBits, iv))
        return cipher.doFinal(payload.copyOfRange(IvSizeBytes, payload.size)).toString(Charsets.UTF_8)
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(AndroidKeyStore).apply { load(null) }
        (keyStore.getEntry(KeyAlias, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, AndroidKeyStore).run {
            init(
                KeyGenParameterSpec.Builder(KeyAlias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .build(),
            )
            generateKey()
        }
    }

    companion object {
        private const val AccessTokenKey = "access_token"
        private const val AndroidKeyStore = "AndroidKeyStore"
        private const val KeyAlias = "norfold-google-drive-access-token"
        private const val Transform = "AES/GCM/NoPadding"
        private const val IvSizeBytes = 12
        private const val TagSizeBits = 128
    }
}
