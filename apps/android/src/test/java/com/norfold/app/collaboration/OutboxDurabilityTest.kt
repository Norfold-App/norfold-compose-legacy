package com.norfold.app.collaboration

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class OutboxDurabilityTest {
    @Test fun `pending operation survives process reconstruction before ack`() = runBlocking {
        val file = Files.createTempDirectory("collab-outbox").resolve("pending.json").toFile()
        val first = OfflineOutbox(persistence = FileOutboxPersistence(file))
        assertEquals(OutboxEnqueueResult.Enqueued, first.enqueue(envelope(1)))
        val reconstructed = OfflineOutbox(persistence = FileOutboxPersistence(file))
        assertEquals(listOf(OperationId("op-1")), reconstructed.pending().map { it.operationId })
    }

    @Test fun `interrupted first atomic rename recovers the fsynced temp snapshot`() = runBlocking {
        val directory = Files.createTempDirectory("collab-outbox-interrupted").toFile()
        val file = File(directory, "pending.json")
        val original = OfflineOutbox(persistence = FileOutboxPersistence(file))
        original.enqueue(envelope(77))
        file.copyTo(File(directory, "pending.json.tmp"), overwrite = true)
        assertTrue(file.delete())
        val recovered = OfflineOutbox(persistence = FileOutboxPersistence(file))
        assertEquals(listOf("op-77"), recovered.pending().map { it.operationId.value })
    }

    @Test fun `server ack removes only named operation and duplicate ack is harmless`() = runBlocking {
        val box = OfflineOutbox()
        box.enqueue(envelope(1)); box.enqueue(envelope(2))
        assertEquals(1, box.acknowledge(listOf(OperationId("op-1"))))
        assertEquals(0, box.acknowledge(listOf(OperationId("op-1"))))
        assertEquals(listOf(OperationId("op-2")), box.pending().map { it.operationId })
    }

    @Test fun `lost ack keeps operation available for idempotent replay`() = runBlocking {
        val box = OfflineOutbox()
        box.enqueue(envelope(3))
        assertEquals(listOf(OperationId("op-3")), box.drainAll().map { it.operationId })
        assertEquals(listOf(OperationId("op-3")), box.drainAll().map { it.operationId })
    }

    @Test fun `permanent failure is retained but excluded from automatic retry`() = runBlocking {
        val box = OfflineOutbox()
        box.enqueue(envelope(4))
        box.recordFailure(OperationId("op-4"), OutboxFailureKind.PERMANENT, "authorization_denied")
        assertTrue(box.pending().isEmpty())
        assertEquals(1, box.size())
    }

    @Test fun `full outbox reports blocked and preserves every accepted operation`() = runBlocking {
        val box = OfflineOutbox(maxSize = 2)
        assertEquals(OutboxEnqueueResult.Enqueued, box.enqueue(envelope(1)))
        assertEquals(OutboxEnqueueResult.Enqueued, box.enqueue(envelope(2)))
        assertTrue(box.enqueue(envelope(3)) is OutboxEnqueueResult.Blocked)
        assertEquals(listOf("op-1", "op-2"), box.drainAll().map { it.operationId.value })
    }

    @Test fun `idempotent compaction keeps one copy without conflating different content operations`() = runBlocking {
        val box = OfflineOutbox()
        val operation = envelope(8)
        assertEquals(OutboxEnqueueResult.Enqueued, box.enqueue(operation))
        assertEquals(OutboxEnqueueResult.AlreadyPending, box.enqueue(operation))
        assertEquals(OutboxEnqueueResult.Enqueued, box.enqueue(envelope(9)))
        assertEquals(listOf("op-8", "op-9"), box.pending().map { it.operationId.value })
    }

    @Test fun `same operation id with different content is rejected instead of silently dropped`() = runBlocking {
        val box = OfflineOutbox()
        val original = envelope(8)
        assertEquals(OutboxEnqueueResult.Enqueued, box.enqueue(original))
        assertEquals(
            OutboxEnqueueResult.OperationIdCollision,
            box.enqueue(original.copy(eventType = "different.event")),
        )
        assertEquals(listOf("semantic.batch"), box.pending().map { it.eventType })
    }

    @Test fun `same account device outboxes never overwrite each other`() = runBlocking {
        val directory = Files.createTempDirectory("collab-device-outboxes")
        val phone = OfflineOutbox(persistence = FileOutboxPersistence(directory.resolve("phone.json").toFile()))
        val tablet = OfflineOutbox(persistence = FileOutboxPersistence(directory.resolve("tablet.json").toFile()))
        phone.enqueue(envelope(101).copy(deviceId = DeviceId("phone"), operationId = OperationId("phone-op")))
        tablet.enqueue(envelope(101).copy(deviceId = DeviceId("tablet"), operationId = OperationId("tablet-op")))
        assertEquals(listOf("phone-op"), phone.pending().map { it.operationId.value })
        assertEquals(listOf("tablet-op"), tablet.pending().map { it.operationId.value })
    }

    private fun envelope(sequence: Long) = OutboundEnvelope(
        roomId = RoomId("bench:outbox"), actorId = ActorId("actor"), deviceId = DeviceId("device"),
        operationId = OperationId("op-$sequence"), eventClass = CollabEventClass.DURABLE,
        eventType = "semantic.batch", seq = CollabSeq(sequence),
    )
}
