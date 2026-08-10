package com.norfold.app.collaboration

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class CollaborationDeliveryTest {
    @Test fun `artifact session refuses process volatile outbox`() = runBlocking {
        val session = CollaborationSession(FakeTransport(TransportState.IDLE), outbox = OfflineOutbox())
        val room = RoomId.artifact(
            "11111111-1111-4111-8111-111111111111",
            "22222222-2222-4222-8222-222222222222",
        )
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { session.connect(room, ActorId("actor")) { "token" } }
        }
        session.close()
    }

    @Test fun `artifact session accepts fsynced outbox`() = runBlocking {
        val file = Files.createTempDirectory("collab-session").resolve("outbox.json").toFile()
        val session = CollaborationSession(
            FakeTransport(TransportState.IDLE),
            outbox = OfflineOutbox(persistence = FileOutboxPersistence(file)),
        )
        val room = RoomId.artifact(
            "11111111-1111-4111-8111-111111111111",
            "22222222-2222-4222-8222-222222222222",
        )
        session.connect(room, ActorId("actor")) { "token" }
        session.close()
    }

    @Test fun `benchmark only provider cannot open an artifact room`() = runBlocking {
        val file = Files.createTempDirectory("collab-benchmark-only").resolve("outbox.json").toFile()
        val session = CollaborationSession(
            FakeTransport(TransportState.IDLE),
            outbox = OfflineOutbox(persistence = FileOutboxPersistence(file)),
            artifactDeliverySupported = false,
        )
        val room = RoomId.artifact(
            "11111111-1111-4111-8111-111111111111",
            "22222222-2222-4222-8222-222222222222",
        )
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { session.connect(room, ActorId("actor")) { "token" } }
        }
        session.close()
    }

    @Test fun `socket handoff never removes operation without durable ack`() = runBlocking {
        val transport = FakeTransport(TransportState.CONNECTED)
        val outbox = OfflineOutbox()
        val session = CollaborationSession(
            transport = transport, outbox = outbox,
            durableGateway = FixedGateway(DurableAcceptance.Retryable("ack_lost")),
            deviceId = DeviceId("device"),
        )
        session.send(RoomId("bench:delivery"), ActorId("actor"), CollabEventClass.DURABLE, "semantic.batch", operationId = OperationId("op-1"))
        assertEquals(1, session.pendingOperations())
        assertTrue(transport.sent.isEmpty())
        session.close()
    }

    @Test fun `persistent ack removes outbox then realtime notifies peers`() = runBlocking {
        val transport = FakeTransport(TransportState.CONNECTED)
        val session = CollaborationSession(
            transport = transport, outbox = OfflineOutbox(),
            durableGateway = FixedGateway(DurableAcceptance.Accepted(OperationId("op-2"), 9, "a".repeat(64), false)),
            deviceId = DeviceId("device"),
        )
        session.send(RoomId("bench:delivery"), ActorId("actor"), CollabEventClass.DURABLE, "semantic.batch", operationId = OperationId("op-2"))
        assertEquals(0, session.pendingOperations())
        assertEquals(listOf(OperationId("op-2")), transport.sent.map { it.operationId })
        session.close()
    }

    @Test fun `duplicate persistent acceptance is still a valid ack`() = runBlocking {
        val transport = FakeTransport(TransportState.CONNECTED)
        val session = CollaborationSession(
            transport = transport, outbox = OfflineOutbox(),
            durableGateway = FixedGateway(DurableAcceptance.Accepted(OperationId("op-3"), 10, "b".repeat(64), true)),
            deviceId = DeviceId("device"),
        )
        session.send(RoomId("bench:delivery"), ActorId("actor"), CollabEventClass.DURABLE, "semantic.batch", operationId = OperationId("op-3"))
        assertEquals(0, session.pendingOperations())
        session.close()
    }

    @Test fun `malformed or mismatched durable ack never removes pending operation`() = runBlocking {
        val transport = FakeTransport(TransportState.CONNECTED)
        val session = CollaborationSession(
            transport = transport, outbox = OfflineOutbox(),
            durableGateway = FixedGateway(DurableAcceptance.Accepted(OperationId("wrong-op"), 0, "bad", false)),
            deviceId = DeviceId("device"),
        )
        session.send(
            RoomId("bench:delivery"), ActorId("actor"), CollabEventClass.DURABLE,
            "semantic.batch", operationId = OperationId("op-4"),
        )
        assertEquals(1, session.pendingOperations())
        assertTrue(transport.sent.isEmpty())
        session.close()
    }

    private class FixedGateway(private val result: DurableAcceptance) : DurableOperationGateway {
        override suspend fun accept(envelope: OutboundEnvelope) = result
        override suspend fun recover(request: RecoveryRequest) = RecoveryResult.Rejected("unused")
    }

    private class FakeTransport(initial: TransportState) : RealtimeCollaborationTransport {
        override val providerId = "fake"
        private val mutableState = MutableStateFlow(initial)
        override val state: StateFlow<TransportState> = mutableState
        override val inbound: Flow<InboundEnvelope> = MutableSharedFlow()
        override val lifecycle: Flow<TransportLifecycleEvent> = MutableSharedFlow()
        val sent = mutableListOf<OutboundEnvelope>()
        override suspend fun connect(roomId: RoomId, actorId: ActorId, tokenProvider: CollabTokenProvider) { mutableState.value = TransportState.CONNECTED }
        override suspend fun send(envelope: OutboundEnvelope) { sent += envelope }
        override suspend fun close() { mutableState.value = TransportState.CLOSED }
        override fun metricsSnapshot() = TransportMetrics(providerId, sent.size.toLong(), 0, 0, 0, 0, 0, 0, null)
    }
}
