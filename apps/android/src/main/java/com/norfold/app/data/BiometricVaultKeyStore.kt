package com.norfold.app.data

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.biometric.BiometricPrompt
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Device-local biometric proof for the Vault.
 *
 * A fixed, non-secret verifier is sealed with an auth-per-use Android Keystore key. Both setup and
 * unlock execute the cipher through [BiometricPrompt.CryptoObject], so a successful callback alone
 * can never unlock Norfold. Enrollment changes invalidate the key and require password recovery.
 */
class BiometricVaultKeyStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(PreferencesName, Context.MODE_PRIVATE)

    fun enrollmentCryptoObject(): BiometricPrompt.CryptoObject {
        clear()
        val cipher = newCipher()
        cipher.init(Cipher.ENCRYPT_MODE, createKey())
        return BiometricPrompt.CryptoObject(cipher)
    }

    fun completeEnrollment(cryptoObject: BiometricPrompt.CryptoObject?): Boolean {
        val cipher = cryptoObject?.cipher ?: return false
        val encrypted = cipher.doFinal(Verifier)
        preferences.edit()
            .putString(IvKey, encode(cipher.iv))
            .putString(PayloadKey, encode(encrypted))
            .apply()
        return true
    }

    fun unlockCryptoObject(): BiometricPrompt.CryptoObject? {
        val iv = preferences.getString(IvKey, null)?.let(::decode) ?: return null
        val key = loadKey() ?: return null
        return try {
            newCipher().apply { init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TagBits, iv)) }
                .let(BiometricPrompt::CryptoObject)
        } catch (_: KeyPermanentlyInvalidatedException) {
            clear()
            null
        } catch (_: Exception) {
            null
        }
    }

    fun completeUnlock(cryptoObject: BiometricPrompt.CryptoObject?): Boolean {
        val encrypted = preferences.getString(PayloadKey, null)?.let(::decode) ?: return false
        val cipher = cryptoObject?.cipher ?: return false
        return runCatching { MessageDigest.isEqual(Verifier, cipher.doFinal(encrypted)) }.getOrDefault(false)
    }

    fun isEnrolled(): Boolean =
        preferences.contains(IvKey) && preferences.contains(PayloadKey) && loadKey() != null

    fun clear() {
        preferences.edit().clear().apply()
        runCatching { keyStore().deleteEntry(KeyAlias) }
    }

    private fun createKey(): SecretKey {
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, AndroidKeyStore)
        val builder = KeyGenParameterSpec.Builder(
            KeyAlias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setUserAuthenticationRequired(true)
            .setInvalidatedByBiometricEnrollment(true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            builder.setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
        } else {
            @Suppress("DEPRECATION")
            builder.setUserAuthenticationValidityDurationSeconds(-1)
        }
        generator.init(builder.build())
        return generator.generateKey()
    }

    private fun loadKey(): SecretKey? = runCatching {
        keyStore().getKey(KeyAlias, null) as? SecretKey
    }.getOrNull()

    private fun keyStore(): KeyStore = KeyStore.getInstance(AndroidKeyStore).apply { load(null) }
    private fun newCipher(): Cipher = Cipher.getInstance("AES/GCM/NoPadding")
    private fun encode(value: ByteArray): String = Base64.encodeToString(value, Base64.NO_WRAP)
    private fun decode(value: String): ByteArray = Base64.decode(value, Base64.NO_WRAP)

    private companion object {
        const val AndroidKeyStore = "AndroidKeyStore"
        const val KeyAlias = "norfold.vault.biometric.v1"
        const val PreferencesName = "norfold.biometric.vault"
        const val IvKey = "iv"
        const val PayloadKey = "payload"
        const val TagBits = 128
        val Verifier = "NORFOLD-BIOMETRIC-VAULT-V1".toByteArray(Charsets.UTF_8)
    }
}
