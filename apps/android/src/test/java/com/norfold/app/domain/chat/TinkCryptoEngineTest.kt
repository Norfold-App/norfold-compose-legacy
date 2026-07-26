package com.norfold.app.domain.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TinkCryptoEngineTest {
    private val engine = TinkCryptoEngine()

    @Test
    fun encryptDecryptAndRestoreMaterialRoundTrip() {
        val alice = engine.generateDeviceMaterial("alice-phone", 1)
        val bob = engine.generateDeviceMaterial("bob-tablet", 1)
        val metadata = E2eeMessageMetadata(
            conversationId = "dm-alice-bob",
            messageId = "message-1",
            senderAccountId = "alice",
            senderDeviceId = alice.public.deviceId,
            senderDeviceGeneration = alice.public.generation,
            securityEpochId = "epoch-1",
            createdAt = 1234L,
        )

        val envelope = engine.seal(
            plaintext = "hello".encodeToByteArray(),
            metadata = metadata,
            sender = alice,
            recipients = listOf(alice.public, bob.public),
        )

        assertEquals("hello", engine.open(envelope, bob, alice.public).decodeToString())
        assertEquals("hello", engine.open(envelope, alice, alice.public).decodeToString())
        assertTrue(alice.public.securityCode.isNotBlank())
        assertNotEquals("hello", envelope.ciphertext)
    }

    @Test(expected = Exception::class)
    fun tamperedMetadataFailsClosed() {
        val alice = engine.generateDeviceMaterial("alice", 1)
        val bob = engine.generateDeviceMaterial("bob", 1)
        val metadata = E2eeMessageMetadata("dm", "m1", "alice", "alice", 1, "e1", 1L)
        val envelope = engine.seal("hello".encodeToByteArray(), metadata, alice, listOf(bob.public))
        engine.open(
            envelope.copy(metadata = envelope.metadata.copy(messageId = "tampered")),
            bob,
            alice.public,
        )
    }
}
