package com.norfold.app.collaboration.editor

import com.norfold.app.collaboration.ActorId
import com.norfold.app.collaboration.CollabEventClass
import com.norfold.app.collaboration.CollaborationReplayGuard
import com.norfold.app.collaboration.CollaborationSession
import com.norfold.app.collaboration.DeviceId
import com.norfold.app.collaboration.InboundEnvelope
import com.norfold.app.collaboration.OperationId
import com.norfold.app.collaboration.ReplayDecision
import com.norfold.app.collaboration.RoomId
import com.norfold.app.collaboration.ArtifactRevision
import com.norfold.app.collaboration.merge.LocalOperation
import com.norfold.app.collaboration.merge.MergeDocument
import com.norfold.app.collaboration.merge.RemoteOperation
import com.norfold.app.collaboration.merge.RemoteBatchResult
import com.norfold.app.collaboration.merge.MergeSnapshotCodec
import com.norfold.app.collaboration.CollaborationSnapshot
import com.norfold.app.collaboration.merge.SyncPayload
import com.norfold.app.collaboration.protocol.SemanticBatch
import com.norfold.app.collaboration.protocol.SemanticDecodeResult
import com.norfold.app.collaboration.protocol.SemanticOperationCodec
import com.norfold.app.collaboration.protocol.SemanticProjectionValidator
import com.norfold.app.editor.flags.EditorFeatureFlag
import com.norfold.app.editor.flags.EditorFeatureFlags
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import java.security.MessageDigest

sealed interface RemoteIntegrationResult {
    data object Applied : RemoteIntegrationResult
    data object Duplicate : RemoteIntegrationResult
    data class CompatibilityRequired(val semanticVersion: Int) : RemoteIntegrationResult
    data class Rejected(val reason: String) : RemoteIntegrationResult
}

/** Gated integration seam. Default-host cutover concerns stay outside this runtime. */
class EditorCollaborationRuntime(
    featureFlags: EditorFeatureFlags,
    private val roomId: RoomId,
    private val actorId: ActorId,
    private val deviceId: DeviceId,
    private val session: CollaborationSession,
    private val mergeDocument: MergeDocument,
    minimumKeyEpoch: () -> Long,
    private val reconcileDomainOperations: (List<com.norfold.app.collaboration.protocol.SemanticMutation>) -> Boolean,
    private val recoverBefore: suspend (RoomId, ArtifactRevision) -> ArtifactRevision? = { _, _ -> null },
) {
    private val enabled = featureFlags.isEnabled(EditorFeatureFlag.COLLABORATION)
    private val replayGuard = CollaborationReplayGuard(roomId, minimumKeyEpoch)
    private var logicalClock = 0L
    private var artifactRevision = ArtifactRevision()

    suspend fun publish(batch: SemanticBatch) {
        check(enabled) { "Collaboration is disabled" }
        val operationId = OperationId(java.util.UUID.randomUUID().toString())
        val clock = ++logicalClock
        val payload = JSON.parseToJsonElement(SemanticOperationCodec.encode(batch).toString(Charsets.UTF_8))
        session.send(
            roomId, actorId, CollabEventClass.DURABLE, "semantic.batch",
            targetId = batch.operations.first().targetId, payload = payload,
            operationId = operationId, batchId = batch.batchId,
            semanticVersion = batch.operations.maxOf { it.version },
        )
        batch.operations.forEachIndexed { index, mutation ->
            mergeDocument.applyLocal(LocalOperation(
                opId = "${operationId.value}:$index", actorId = actorId.value,
                commandId = batch.commandId, deviceId = deviceId.value,
                logicalClock = clock, op = mutation,
            ))
        }
    }

    fun historyBridge(scope: CoroutineScope, adapter: EditorDomainMutationAdapter): CollaborationHistoryBridge =
        CollaborationHistoryBridge(adapter) { batch -> scope.launch { publish(batch) } }

    fun recoverySnapshot(artifactSchemaVersion: Int): CollaborationSnapshot? {
        if (artifactRevision.serverRevision <= 0 || !artifactRevision.revisionHash.matches(Regex("[0-9a-f]{64}"))) return null
        return CollaborationSnapshot(
            roomId = roomId,
            serverRevision = artifactRevision.serverRevision,
            revisionHash = artifactRevision.revisionHash,
            protocolVersion = com.norfold.app.collaboration.OutboundEnvelope.PROTOCOL_VERSION,
            artifactSchemaVersion = artifactSchemaVersion,
            payload = MergeSnapshotCodec.encode(mergeDocument.snapshot()),
        )
    }

    fun restoreRecoverySnapshot(snapshot: CollaborationSnapshot): Boolean {
        if (snapshot.roomId != roomId || snapshot.protocolVersion != com.norfold.app.collaboration.OutboundEnvelope.PROTOCOL_VERSION) {
            return false
        }
        val decoded = runCatching { MergeSnapshotCodec.decode(snapshot.payload) }.getOrNull() ?: return false
        val reconcile: (List<com.norfold.app.collaboration.protocol.SemanticMutation>) -> Boolean = { projected ->
            SemanticProjectionValidator.rejectReason(projected) == null && reconcileDomainOperations(projected)
        }
        return when (mergeDocument.integrateSnapshot(SyncPayload(decoded.engineId, decoded.bytes), reconcile)) {
            is RemoteBatchResult.Rejected -> false
            is RemoteBatchResult.Applied -> {
                artifactRevision = ArtifactRevision(snapshot.serverRevision, snapshot.revisionHash)
                true
            }
        }
    }

    suspend fun integrate(envelope: InboundEnvelope): RemoteIntegrationResult {
        if (!enabled) return RemoteIntegrationResult.Rejected("collaboration_disabled")
        if (envelope.eventClass != CollabEventClass.DURABLE || envelope.eventType != "semantic.batch" ||
            envelope.serverRevision == null || !envelope.revisionHash.orEmpty().matches(Regex("[0-9a-f]{64}"))
        ) return RemoteIntegrationResult.Rejected("durable_revision_required")
        val incomingRevision = envelope.serverRevision
        if (incomingRevision <= artifactRevision.serverRevision) return RemoteIntegrationResult.Duplicate
        if (incomingRevision > artifactRevision.serverRevision + 1) {
            artifactRevision = recoverBefore(roomId, artifactRevision)
                ?: return RemoteIntegrationResult.Rejected("gap_recovery_required")
            if (incomingRevision <= artifactRevision.serverRevision) return RemoteIntegrationResult.Duplicate
            if (incomingRevision > artifactRevision.serverRevision + 1) {
                return RemoteIntegrationResult.Rejected("gap_recovery_incomplete")
            }
        }
        when (val replay = replayGuard.validate(envelope)) {
            ReplayDecision.Duplicate -> return RemoteIntegrationResult.Duplicate
            ReplayDecision.Accept -> Unit
            ReplayDecision.StaleKeyEpoch -> return RemoteIntegrationResult.Rejected("stale_key_epoch")
            ReplayDecision.WrongScope -> return RemoteIntegrationResult.Rejected("wrong_artifact")
            ReplayDecision.InvalidPayload -> return RemoteIntegrationResult.Rejected("payload_hash_mismatch")
        }
        val revision = requireNotNull(envelope.serverRevision)
        val previousHash = if (artifactRevision.serverRevision == 0L) ZERO_HASH else artifactRevision.revisionHash
        val expectedRevisionHash = chainHash(previousHash, envelope.wirePayloadHash, envelope.operationId.value, revision)
        if (envelope.revisionHash != expectedRevisionHash) {
            return RemoteIntegrationResult.Rejected("revision_hash_mismatch")
        }
        val bytes = envelope.payload?.toString()?.toByteArray() ?: return RemoteIntegrationResult.Rejected("missing_payload")
        return when (val decoded = SemanticOperationCodec.decode(bytes)) {
            is SemanticDecodeResult.Future -> {
                session.reportIntegrationFailure(com.norfold.app.collaboration.CollaborationHealth.PROTOCOL_INCOMPATIBLE)
                RemoteIntegrationResult.CompatibilityRequired(decoded.semanticVersion)
            }
            is SemanticDecodeResult.Rejected -> {
                session.reportIntegrationFailure(com.norfold.app.collaboration.CollaborationHealth.OPERATION_REJECTED)
                RemoteIntegrationResult.Rejected(decoded.reason)
            }
            is SemanticDecodeResult.Supported -> {
                val clock = incomingRevision
                val remoteOperations = decoded.batch.operations.mapIndexed { index, mutation ->
                    RemoteOperation(
                        opId = "${envelope.operationId.value}:$index",
                        actorId = envelope.actorId.value,
                        commandId = decoded.batch.commandId,
                        deviceId = envelope.deviceId.value,
                        logicalClock = clock,
                        op = mutation,
                    )
                }
                val reconcile: (List<com.norfold.app.collaboration.protocol.SemanticMutation>) -> Boolean = { projected ->
                    SemanticProjectionValidator.rejectReason(projected) == null && reconcileDomainOperations(projected)
                }
                when (val merged = mergeDocument.integrateRemoteBatch(remoteOperations, reconcile)) {
                    is RemoteBatchResult.Rejected -> {
                        session.reportIntegrationFailure(com.norfold.app.collaboration.CollaborationHealth.MERGE_CONFLICT)
                        return RemoteIntegrationResult.Rejected(merged.reason)
                    }
                    is RemoteBatchResult.Applied -> Unit
                }
                replayGuard.markApplied(envelope.operationId)
                logicalClock = maxOf(logicalClock, clock)
                artifactRevision = ArtifactRevision(incomingRevision, envelope.revisionHash.orEmpty())
                RemoteIntegrationResult.Applied
            }
        }
    }

    companion object {
        private val JSON = Json { ignoreUnknownKeys = false }
        private val ZERO_HASH = "0".repeat(64)

        private fun chainHash(previous: String, payload: String, operation: String, revision: Long): String =
            MessageDigest.getInstance("SHA-256")
                .digest((previous + payload + operation + revision).toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
    }
}
