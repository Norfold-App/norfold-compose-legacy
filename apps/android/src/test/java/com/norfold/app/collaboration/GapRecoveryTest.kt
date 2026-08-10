package com.norfold.app.collaboration

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.security.MessageDigest

class GapRecoveryTest {
    @Test fun `durable tracker reports actor device gap independent of target`() {
        val tracker = DurableSequenceTracker()
        assertEquals(SequenceDecision.Accept, tracker.inspect(inbound(1, "target-a")))
        assertEquals(SequenceDecision.Gap(2, 3), tracker.inspect(inbound(3, "target-b")))
    }

    @Test fun `missing ranges are applied in server revision order and verified`() = runBlocking {
        val applied = mutableListOf<Long>()
        val initialHash = "1".repeat(64)
        val payloadHash = hashPayload(null)
        val hash2 = chainHash(initialHash, payloadHash, "op-2", 2)
        val hash3 = chainHash(hash2, payloadHash, "op-3", 3)
        val gateway = object : DurableOperationGateway {
            override suspend fun accept(envelope: OutboundEnvelope) = DurableAcceptance.Retryable("unused")
            override suspend fun recover(request: RecoveryRequest) = RecoveryResult.Operations(
                envelopes = listOf(inbound(3, "b", 3, hash3), inbound(2, "a", 2, hash2)),
                serverRevision = 3, revisionHash = hash3, complete = true,
            )
        }
        val coordinator = GapRecoveryCoordinator(
            gateway = gateway,
            applyRecovered = { applied += requireNotNull(it.serverRevision) },
        )
        val (state, revision) = coordinator.recover(RoomId("bench:recovery"), ArtifactRevision(1, initialHash))
        assertEquals(RecoveryState.Synchronized, state)
        assertEquals(listOf(2L, 3L), applied)
        assertEquals(ArtifactRevision(3, hash3), revision)
    }

    @Test fun `tampered recovery chain is rejected before applying any operation`() = runBlocking {
        val applied = mutableListOf<Long>()
        val gateway = object : DurableOperationGateway {
            override suspend fun accept(envelope: OutboundEnvelope) = DurableAcceptance.Retryable("unused")
            override suspend fun recover(request: RecoveryRequest) = RecoveryResult.Operations(
                listOf(inbound(1, "a", 1, "f".repeat(64))), 1, "f".repeat(64), true,
            )
        }
        val coordinator = GapRecoveryCoordinator(
            gateway = gateway,
            applyRecovered = { applied += requireNotNull(it.serverRevision) },
        )
        val (state, revision) = coordinator.recover(RoomId("bench:recovery"), ArtifactRevision())
        assertEquals(RecoveryState.Incompatible("recovery_revision_hash_mismatch"), state)
        assertEquals(ArtifactRevision(), revision)
        assertTrue(applied.isEmpty())
    }

    @Test fun `large missing range requests snapshot without mutating local state`() = runBlocking {
        val gateway = object : DurableOperationGateway {
            override suspend fun accept(envelope: OutboundEnvelope) = DurableAcceptance.Retryable("unused")
            override suspend fun recover(request: RecoveryRequest) = RecoveryResult.SnapshotRequired(9000, "snapshot-hash")
        }
        val coordinator = GapRecoveryCoordinator(
            gateway = gateway,
            applyRecovered = { fail("operation should not apply") },
        )
        val (state, revision) = coordinator.recover(RoomId("bench:recovery"), ArtifactRevision(4, "local"))
        assertEquals(RecoveryState.SnapshotNeeded(9000, "snapshot-hash"), state)
        assertEquals(ArtifactRevision(4, "local"), revision)
    }

    private fun inbound(sequence: Long, target: String, revision: Long? = null, revisionHash: String? = null) = InboundEnvelope(
        RoomId("bench:recovery"), ActorId("actor"), CollabEventClass.DURABLE,
        "semantic.batch", target, CollabSeq(sequence), 0, 0,
        deviceId = DeviceId("device"), operationId = OperationId("op-$sequence"),
        serverRevision = revision, revisionHash = revisionHash,
    )

    private fun chainHash(previous: String, payload: String, operation: String, revision: Long): String =
        MessageDigest.getInstance("SHA-256")
            .digest((previous + payload + operation + revision).toByteArray())
            .joinToString("") { "%02x".format(it) }
}
