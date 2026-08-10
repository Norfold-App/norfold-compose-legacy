package com.norfold.app.collaboration

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import com.norfold.app.domain.chat.TinkCryptoEngine
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonObject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking

class CollaborationSecurityTest {
    private val workspace = "11111111-1111-4111-8111-111111111111"
    private val artifact = "22222222-2222-4222-8222-222222222222"
    private val room = RoomId.artifact(workspace, artifact)

    @Test fun `artifact room identity is deterministic and rename independent`() {
        assertEquals("artifact:$workspace:$artifact", room.value)
        assertEquals(room, RoomId.artifact(workspace, artifact))
        assertEquals(ArtifactRoom(workspace, artifact), room.artifactScope)
    }

    @Test fun `invalid or wrong artifact room is rejected`() {
        assertNull(RoomId("artifact:wrong:ids").artifactScope)
        val wire = envelope().toWire().copy(room = "artifact:wrong:ids")
        assertEquals(WireDecodeResult.Rejected("invalid_room"), wire.decodeInbound(0))
    }

    @Test fun `unsupported protocol is compatibility state not a crash`() {
        val wire = envelope().toWire().copy(protocolVersion = 2)
        assertEquals(WireDecodeResult.Incompatible(2), wire.decodeInbound(0))
    }

    @Test fun `payload tampering is rejected`() {
        val wire = envelope().toWire().copy(payloadHash = "0".repeat(64))
        assertEquals(WireDecodeResult.Rejected("payload_hash_mismatch"), wire.decodeInbound(0))
    }

    @Test fun `payload hash is stable across database object key reordering`() {
        val first = JsonObject(linkedMapOf("z" to JsonPrimitive(1), "a" to JsonPrimitive(2)))
        val reordered = JsonObject(linkedMapOf("a" to JsonPrimitive(2), "z" to JsonPrimitive(1)))
        assertEquals(hashPayload(first), hashPayload(reordered))
    }

    @Test fun `replay guard rejects duplicate stale epoch and wrong room`() {
        val guard = CollaborationReplayGuard(room, minimumKeyEpoch = { 4 })
        val accepted = envelope(keyEpoch = 4).toWire().toInbound(0)
        assertEquals(ReplayDecision.Accept, guard.inspect(accepted))
        assertEquals(ReplayDecision.Duplicate, guard.inspect(accepted))
        assertEquals(ReplayDecision.StaleKeyEpoch, guard.inspect(envelope(keyEpoch = 3).copy(operationId = OperationId("op-stale")).toWire().toInbound(0)))
        val otherRoom = RoomId.artifact(workspace, "33333333-3333-4333-8333-333333333333")
        assertEquals(ReplayDecision.WrongScope, guard.inspect(envelope(keyEpoch = 4).copy(roomId = otherRoom, operationId = OperationId("op-other")).toWire().toInbound(0)))
    }

    @Test fun `database contract is deny by default for viewer and wrong artifact`() {
        val migration = locateMigration().readText()
        assertTrue(migration.contains("m.role in ('owner', 'admin', 'member')"))
        assertTrue(migration.contains("o.id = p_artifact"))
        assertTrue(migration.contains("o.workspace_id = p_workspace"))
        assertTrue(migration.contains("device_not_owned"))
        assertTrue(migration.contains("enable row level security"))
        assertFalse(migration.contains("service_role"))
    }

    @Test fun `encrypted operation is bound to artifact epoch and protocol metadata`() = runBlocking {
        val engine = TinkCryptoEngine()
        val sender = engine.generateDeviceMaterial("sender-device", 1)
        val recipient = engine.generateDeviceMaterial("recipient-device", 1)
        val crypto = CryptoEngineCollabEnvelope(engine)
        val context = CollabCryptoContext(
            roomId = room, actorId = ActorId("actor"), opId = "op-encrypted", createdAt = 7,
            sender = sender, recipients = listOf(recipient.public), senderPublic = sender.public,
            keyDomainId = "key-domain", keyEpoch = 4,
            workspaceId = workspace, artifactId = artifact, deviceId = "sender-device",
            sequence = 8, protocolVersion = 1, semanticVersion = 1, artifactSchemaVersion = 1,
            eventClass = CollabEventClass.DURABLE,
            contentType = "application/vnd.norfold.semantic+json",
        )
        val sealed = crypto.sealPayload(JsonPrimitive("secret"), context)
        val recipientContext = context.copy(sender = recipient, recipients = emptyList())
        assertEquals(JsonPrimitive("secret"), crypto.openPayload(sealed, recipientContext))
        assertThrows(IllegalArgumentException::class.java) {
            crypto.openPayload(sealed, recipientContext.copy(keyEpoch = 5))
        }
        val wireHash = hashPayload(sealed)
        val session = CollaborationSession(
            SecurityTransport(), crypto = crypto,
            cryptoContextProvider = CollabCryptoContextProvider { recipientContext },
        )
        val recovered = session.decodeRecovered(InboundEnvelope(
            roomId = room, actorId = ActorId("actor"), deviceId = DeviceId("sender-device"),
            operationId = OperationId("op-encrypted"), eventClass = CollabEventClass.DURABLE,
            eventType = "semantic.batch", seq = CollabSeq(8), clientSentAt = 7, serverAt = 9,
            payload = sealed, payloadHash = wireHash, keyEpoch = 4,
        ))
        assertEquals(JsonPrimitive("secret"), recovered?.payload)
        assertEquals(wireHash, recovered?.wirePayloadHash)
        assertEquals(hashPayload(JsonPrimitive("secret")), recovered?.payloadHash)
        session.close()
    }

    private fun envelope(keyEpoch: Long = 4) = OutboundEnvelope(
        roomId = room, actorId = ActorId("actor"), deviceId = DeviceId("device"),
        operationId = OperationId("op-1"), eventClass = CollabEventClass.DURABLE,
        eventType = "semantic.batch", seq = CollabSeq(1), keyEpoch = keyEpoch,
    )

    private fun locateMigration(): File {
        val candidates = listOf(
            File("supabase/migrations/202608100001_collaboration_durability.sql"),
            File("../../supabase/migrations/202608100001_collaboration_durability.sql"),
        )
        return candidates.firstOrNull(File::isFile) ?: error("Collaboration migration not found")
    }

    private class SecurityTransport : RealtimeCollaborationTransport {
        override val providerId = "security-test"
        override val state: StateFlow<TransportState> = MutableStateFlow(TransportState.IDLE)
        override val inbound: Flow<InboundEnvelope> = MutableSharedFlow()
        override val lifecycle: Flow<TransportLifecycleEvent> = MutableSharedFlow()
        override suspend fun connect(roomId: RoomId, actorId: ActorId, tokenProvider: CollabTokenProvider) = Unit
        override suspend fun send(envelope: OutboundEnvelope) = Unit
        override suspend fun close() = Unit
        override fun metricsSnapshot() = TransportMetrics(providerId, 0, 0, 0, 0, 0, 0, 0, null)
    }
}
