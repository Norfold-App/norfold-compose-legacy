package com.norfold.app.data.chat

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.norfold.app.domain.chat.DeviceCryptoMaterial
import com.norfold.app.domain.chat.DevicePublicMaterial
import com.norfold.app.domain.chat.TinkCryptoEngine
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.security.KeyStore
import java.util.Base64
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Device-local secure identity; private keysets never leave Keystore wrapping. */
class ChatDeviceIdentityStore(context: Context) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences(PrefsName, Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val protector = AndroidKeystoreProtector(KeyAlias)
    private val engine = TinkCryptoEngine()

    @Synchronized
    fun getOrCreate(): DeviceCryptoMaterial {
        val stored = prefs.getString(KeyIdentity, null)
        if (stored != null) {
            return runCatching { decode(stored) }.getOrElse {
                // Corrupt/unrecoverable wrapping is a security identity reset,
                // never a plaintext fallback or silent reuse of partial keys.
                resetIdentity(currentGeneration() + 1)
            }
        }
        return resetIdentity(1)
    }

    @Synchronized
    fun resetIdentity(generation: Int = currentGeneration() + 1): DeviceCryptoMaterial {
        val deviceId = prefs.getString(KeyDeviceId, null) ?: UUID.randomUUID().toString()
        val material = engine.generateDeviceMaterial(deviceId, generation.coerceAtLeast(1))
        val stored = StoredDeviceIdentity(
            public = material.public,
            wrappedHpkePrivate = protector.encrypt(material.hpkePrivateKeyset.toByteArray()),
            wrappedSigningPrivate = protector.encrypt(material.signingPrivateKeyset.toByteArray()),
        )
        prefs.edit()
            .putString(KeyDeviceId, deviceId)
            .putInt(KeyGeneration, material.public.generation)
            .putString(KeyIdentity, json.encodeToString(stored))
            .apply()
        return material
    }

    fun publicIdentity(): DevicePublicMaterial = getOrCreate().public

    private fun currentGeneration(): Int = prefs.getInt(KeyGeneration, 0)

    private fun decode(value: String): DeviceCryptoMaterial {
        val stored = json.decodeFromString<StoredDeviceIdentity>(value)
        return DeviceCryptoMaterial(
            public = stored.public,
            hpkePrivateKeyset = protector.decrypt(stored.wrappedHpkePrivate).decodeToString(),
            signingPrivateKeyset = protector.decrypt(stored.wrappedSigningPrivate).decodeToString(),
        )
    }

    companion object {
        private const val PrefsName = "norfold_chat_device_identity_v1"
        private const val KeyAlias = "norfold.chat.device.wrap.v1"
        private const val KeyDeviceId = "device_id"
        private const val KeyGeneration = "generation"
        private const val KeyIdentity = "identity"
    }
}

@Serializable
private data class StoredDeviceIdentity(
    val public: DevicePublicMaterial,
    val wrappedHpkePrivate: String,
    val wrappedSigningPrivate: String,
)

/** Small Android Keystore envelope. AES/GCM is provided by the platform. */
private class AndroidKeystoreProtector(private val alias: String) {
    private val keyStore: KeyStore
        get() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    fun encrypt(plaintext: ByteArray): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val payload = ProtectedPayload(
            version = 1,
            iv = Base64.getEncoder().withoutPadding().encodeToString(cipher.iv),
            ciphertext = Base64.getEncoder().withoutPadding().encodeToString(cipher.doFinal(plaintext)),
        )
        return Json.encodeToString(payload)
    }

    fun decrypt(serialized: String): ByteArray {
        val payload = Json.decodeFromString<ProtectedPayload>(serialized)
        require(payload.version == 1) { "Unsupported local key-wrap version." }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            key(),
            GCMParameterSpec(128, Base64.getDecoder().decode(payload.iv)),
        )
        return cipher.doFinal(Base64.getDecoder().decode(payload.ciphertext))
    }

    private fun key(): SecretKey {
        (keyStore.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(
                KeyGenParameterSpec.Builder(
                    alias,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .setRandomizedEncryptionRequired(true)
                    .build(),
            )
            generateKey()
        }
    }
}

@Serializable
private data class ProtectedPayload(
    val version: Int,
    val iv: String,
    val ciphertext: String,
)
