package com.norfold.app.data.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PrivateChatRecoveryKeysetCipherTest {
    @Test
    fun roundTripIsBoundToFormatAndArchive() {
        val material = PrivateChatRecoveryKeysetCipher.createRecoveryMaterial()
        val wrapped = PrivateChatRecoveryKeysetCipher.wrap(
            formatId = "format-v1",
            archiveId = "archive-a",
            serializedKeyset = "secret-keyset",
            recoveryMaterial = material,
        )

        assertEquals(
            "secret-keyset",
            PrivateChatRecoveryKeysetCipher.unwrap("format-v1", "archive-a", wrapped, material),
        )
        assertThrows(Exception::class.java) {
            PrivateChatRecoveryKeysetCipher.unwrap("format-v1", "archive-b", wrapped, material)
        }
    }

    @Test
    fun wrongMaterialAndTamperingFailClosed() {
        val material = PrivateChatRecoveryKeysetCipher.createRecoveryMaterial()
        val other = PrivateChatRecoveryKeysetCipher.createRecoveryMaterial()
        assertNotEquals(material, other)
        val wrapped = PrivateChatRecoveryKeysetCipher.wrap("format-v1", "archive-a", "keyset", material)

        assertThrows(Exception::class.java) {
            PrivateChatRecoveryKeysetCipher.unwrap("format-v1", "archive-a", wrapped, other)
        }
        val bytes = java.util.Base64.getDecoder().decode(wrapped.ciphertext)
        bytes[0] = (bytes[0].toInt() xor 1).toByte()
        val tampered = wrapped.copy(
            ciphertext = java.util.Base64.getEncoder().withoutPadding().encodeToString(bytes),
        )
        assertThrows(Exception::class.java) {
            PrivateChatRecoveryKeysetCipher.unwrap("format-v1", "archive-a", tampered, material)
        }
    }

    @Test
    fun recoveryMaterialIsCanonicalAndStrictlySized() {
        val material = PrivateChatRecoveryKeysetCipher.createRecoveryMaterial()
        assertEquals(material, PrivateChatRecoveryKeysetCipher.canonicalRecoveryMaterial("  $material  "))
        assertThrows(IllegalArgumentException::class.java) {
            PrivateChatRecoveryKeysetCipher.canonicalRecoveryMaterial("not-a-key")
        }
    }
}
