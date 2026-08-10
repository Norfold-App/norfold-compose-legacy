package com.norfold.app.collaboration

import com.norfold.app.collaboration.editor.EditorCollaborationRuntime
import com.norfold.app.collaboration.editor.RemoteIntegrationResult
import com.norfold.app.collaboration.merge.SemanticOpSetMergeEngine
import com.norfold.app.collaboration.protocol.SemanticBatch
import com.norfold.app.collaboration.protocol.SemanticMutation
import com.norfold.app.collaboration.protocol.SemanticOperationCodec
import com.norfold.app.editor.flags.EditorFeatureFlag
import com.norfold.app.editor.flags.EditorFeatureFlags
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest

class EditorCollaborationRuntimeTest {
    private val room = RoomId.artifact(
        "11111111-1111-4111-8111-111111111111",
        "22222222-2222-4222-8222-222222222222",
    )

    @Test fun `ephemeral semantic batch cannot enter editor merge`() = runBlocking {
        var reconciliations = 0
        val runtime = runtime { reconciliations++; true }
        val result = runtime.integrate(envelope(eventClass = CollabEventClass.EPHEMERAL_EVENT))
        assertEquals(RemoteIntegrationResult.Rejected("durable_revision_required"), result)
        assertEquals(0, reconciliations)
    }

    @Test fun `rejected domain projection does not poison replay id`() = runBlocking {
        var accept = false
        val runtime = runtime { accept }
        val incoming = envelope()
        assertEquals(RemoteIntegrationResult.Rejected("domain_invariant_rejected"), runtime.integrate(incoming))
        accept = true
        assertEquals(RemoteIntegrationResult.Applied, runtime.integrate(incoming))
        assertEquals(RemoteIntegrationResult.Duplicate, runtime.integrate(incoming))
    }

    @Test fun `collaboration remains disabled in production defaults`() = runBlocking {
        val runtime = runtime(flags = EditorFeatureFlags.productionDefaults()) { true }
        assertEquals(RemoteIntegrationResult.Rejected("collaboration_disabled"), runtime.integrate(envelope()))
        assertTrue(!EditorFeatureFlag.COLLABORATION.defaultEnabled)
    }

    @Test fun `sequential operation with forged revision chain is rejected`() = runBlocking {
        val runtime = runtime { true }
        assertEquals(
            RemoteIntegrationResult.Rejected("revision_hash_mismatch"),
            runtime.integrate(envelope().copy(revisionHash = "f".repeat(64))),
        )
    }

    private fun runtime(
        flags: EditorFeatureFlags = EditorFeatureFlags.withOverrides(EditorFeatureFlag.COLLABORATION to true),
        reconcile: (List<SemanticMutation>) -> Boolean,
    ): EditorCollaborationRuntime {
        val transport = FakeTransport()
        val session = CollaborationSession(transport)
        return EditorCollaborationRuntime(
            featureFlags = flags,
            roomId = room,
            actorId = ActorId("local"),
            deviceId = DeviceId("local-device"),
            session = session,
            mergeDocument = SemanticOpSetMergeEngine().createDocument("artifact", "local"),
            minimumKeyEpoch = { 0 },
            reconcileDomainOperations = reconcile,
        )
    }

    private fun envelope(eventClass: CollabEventClass = CollabEventClass.DURABLE): InboundEnvelope {
        val batch = SemanticBatch(
            batchId = "batch", commandId = "command",
            operations = listOf(SemanticMutation(domain = "flow", action = "block.insert", targetId = "doc")),
        )
        val payload = Json.parseToJsonElement(SemanticOperationCodec.encode(batch).toString(Charsets.UTF_8))
        val payloadHash = hashPayload(payload)
        val revisionHash = MessageDigest.getInstance("SHA-256")
            .digest(("0".repeat(64) + payloadHash + "operation" + 1).toByteArray())
            .joinToString("") { "%02x".format(it) }
        return InboundEnvelope(
            roomId = room, actorId = ActorId("remote"), deviceId = DeviceId("remote-device"),
            operationId = OperationId("operation"), eventClass = eventClass,
            eventType = "semantic.batch", seq = CollabSeq(1), clientSentAt = 1, serverAt = 2,
            payload = payload, payloadHash = payloadHash, serverRevision = 1,
            revisionHash = revisionHash,
        )
    }

    private class FakeTransport : RealtimeCollaborationTransport {
        override val providerId = "fake"
        override val state: StateFlow<TransportState> = MutableStateFlow(TransportState.IDLE)
        override val inbound: Flow<InboundEnvelope> = MutableSharedFlow()
        override val lifecycle: Flow<TransportLifecycleEvent> = MutableSharedFlow()
        override suspend fun connect(roomId: RoomId, actorId: ActorId, tokenProvider: CollabTokenProvider) = Unit
        override suspend fun send(envelope: OutboundEnvelope) = Unit
        override suspend fun close() = Unit
        override fun metricsSnapshot() = TransportMetrics(providerId, 0, 0, 0, 0, 0, 0, 0, null)
    }
}
