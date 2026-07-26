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

/** Keystore-wrapped recovery secret used by account-scoped background backup and sync work. */
class SyncSecretStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(Preferences, Context.MODE_PRIVATE)

    fun save(accountId: String, secret: CharArray) {
        require(accountId.isNotBlank()) { "An account is required to store a sync secret." }
        require(secret.isNotEmpty()) { "The recovery secret cannot be empty." }
        prefs.edit().putString(key(accountId), encrypt(secret.concatToString())).apply()
    }

    fun load(accountId: String): CharArray? = prefs.getString(key(accountId), null)
        ?.let { runCatching { decrypt(it).toCharArray() }.getOrNull() }

    fun clear(accountId: String) {
        prefs.edit().remove(key(accountId)).apply()
    }

    private fun key(accountId: String) = "account_${accountId.filter(Char::isLetterOrDigit)}"

    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance(Transform)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        return Base64.encodeToString(cipher.iv + cipher.doFinal(value.toByteArray()), Base64.NO_WRAP)
    }

    private fun decrypt(value: String): String {
        val payload = Base64.decode(value, Base64.NO_WRAP)
        require(payload.size > IvSizeBytes) { "Stored recovery secret is invalid." }
        val cipher = Cipher.getInstance(Transform)
        cipher.init(
            Cipher.DECRYPT_MODE,
            secretKey(),
            GCMParameterSpec(TagSizeBits, payload.copyOfRange(0, IvSizeBytes)),
        )
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

    private companion object {
        const val Preferences = "norfold_background_sync_secret"
        const val AndroidKeyStore = "AndroidKeyStore"
        const val KeyAlias = "norfold-background-sync-secret"
        const val Transform = "AES/GCM/NoPadding"
        const val IvSizeBytes = 12
        const val TagSizeBits = 128
    }
}
