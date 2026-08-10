package com.norfold.app.collaboration

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

sealed interface DurableAcceptance {
    data class Accepted(
        val operationId: OperationId,
        val serverRevision: Long,
        val revisionHash: String,
        val duplicate: Boolean,
    ) : DurableAcceptance
    data class Retryable(val code: String) : DurableAcceptance
    data class AuthExpired(val code: String) : DurableAcceptance
    data class Rejected(val code: String) : DurableAcceptance
}

data class RecoveryRequest(
    val roomId: RoomId,
    val afterRevision: Long,
    val expectedRevisionHash: String? = null,
    val limit: Int = 256,
) {
    init { require(afterRevision >= 0); require(limit in 1..256) }
}

sealed interface RecoveryResult {
    data class Operations(
        val envelopes: List<InboundEnvelope>,
        val serverRevision: Long,
        val revisionHash: String,
        val complete: Boolean,
    ) : RecoveryResult
    data class SnapshotRequired(val serverRevision: Long, val revisionHash: String) : RecoveryResult
    data class Rejected(val code: String) : RecoveryResult
}

data class CollaborationSnapshot(
    val roomId: RoomId,
    val serverRevision: Long,
    val revisionHash: String,
    val protocolVersion: Int,
    val artifactSchemaVersion: Int,
    val payload: kotlinx.serialization.json.JsonElement,
)

sealed interface SnapshotResult {
    data class Available(val snapshot: CollaborationSnapshot) : SnapshotResult
    data class Rejected(val code: String) : SnapshotResult
}

sealed interface SnapshotWriteResult {
    data object Stored : SnapshotWriteResult
    data class Rejected(val code: String) : SnapshotWriteResult
}

interface DurableOperationGateway {
    /** Success means the operation is durably accepted, not merely handed to a socket. */
    suspend fun accept(envelope: OutboundEnvelope): DurableAcceptance
    suspend fun recover(request: RecoveryRequest): RecoveryResult
    suspend fun fetchSnapshot(roomId: RoomId, minimumRevision: Long): SnapshotResult = SnapshotResult.Rejected("snapshot_unavailable")
    suspend fun saveSnapshot(snapshot: CollaborationSnapshot): SnapshotWriteResult =
        SnapshotWriteResult.Rejected("snapshot_write_unavailable")
}

/** Explicit offline gateway used when no authenticated durable service is available. */
object OfflineDurableOperationGateway : DurableOperationGateway {
    override suspend fun accept(envelope: OutboundEnvelope) = DurableAcceptance.Retryable("durable_service_offline")
    override suspend fun recover(request: RecoveryRequest) = RecoveryResult.Rejected("durable_service_offline")
}

@Serializable
private data class AcceptanceRow(
    @SerialName("operation_id") val operationId: String,
    @SerialName("server_revision") val serverRevision: Long,
    @SerialName("revision_hash") val revisionHash: String,
    val duplicate: Boolean,
)

@Serializable
private data class RecoveryRow(
    @SerialName("operation_id") val operationId: String? = null,
    @SerialName("server_revision") val serverRevision: Long,
    @SerialName("revision_hash") val revisionHash: String,
    @SerialName("has_more") val hasMore: Boolean,
    val envelope: CollabWireEnvelope? = null,
    @SerialName("snapshot_required") val snapshotRequired: Boolean = false,
)

@Serializable
private data class SnapshotRow(
    @SerialName("server_revision") val serverRevision: Long,
    @SerialName("revision_hash") val revisionHash: String,
    @SerialName("protocol_version") val protocolVersion: Int,
    @SerialName("artifact_schema_version") val artifactSchemaVersion: Int,
    val snapshot: kotlinx.serialization.json.JsonElement,
)

/** Supabase RPC adapter. RLS and the RPC both re-check workspace, artifact, role and device. */
class SupabaseDurableOperationGateway(private val client: SupabaseClient) : DurableOperationGateway {
    override suspend fun accept(envelope: OutboundEnvelope): DurableAcceptance {
        if (envelope.eventClass != CollabEventClass.DURABLE) return DurableAcceptance.Rejected("not_durable")
        val scope = envelope.roomId.artifactScope ?: return DurableAcceptance.Rejected("invalid_room")
        return try {
            val row = client.postgrest.rpc(
                function = "accept_collaboration_operation",
                parameters = buildJsonObject {
                    put("p_workspace", scope.workspaceId)
                    put("p_artifact", scope.artifactId)
                    put("p_device", envelope.deviceId.value)
                    put("p_operation", envelope.operationId.value)
                    envelope.batchId?.let { put("p_batch", it) } ?: put("p_batch", JsonNull)
                    put("p_sequence", envelope.seq.value)
                    put("p_protocol", envelope.protocolVersion)
                    put("p_semantic", envelope.semanticVersion)
                    put("p_schema", envelope.artifactSchemaVersion)
                    put("p_key_epoch", envelope.keyEpoch)
                    put("p_content_type", envelope.contentType)
                    put("p_payload_hash", envelope.payloadHash)
                    envelope.payload?.let { put("p_payload", it) } ?: put("p_payload", JsonNull)
                },
            ).decodeAs<AcceptanceRow>()
            DurableAcceptance.Accepted(OperationId(row.operationId), row.serverRevision, row.revisionHash, row.duplicate)
        } catch (error: Throwable) {
            val message = error.message.orEmpty().lowercase()
            when {
                "jwt" in message || "401" in message || "expired" in message -> DurableAcceptance.AuthExpired("authentication_expired")
                "403" in message || "denied" in message || "viewer" in message -> DurableAcceptance.Rejected("authorization_denied")
                "payload" in message || "protocol" in message || "operation" in message -> DurableAcceptance.Rejected("operation_rejected")
                else -> DurableAcceptance.Retryable("durable_service_unavailable")
            }
        }
    }

    override suspend fun recover(request: RecoveryRequest): RecoveryResult {
        val scope = request.roomId.artifactScope ?: return RecoveryResult.Rejected("invalid_room")
        return try {
            val rows = client.postgrest.rpc(
                function = "read_collaboration_operations",
                parameters = buildJsonObject {
                    put("p_workspace", scope.workspaceId)
                    put("p_artifact", scope.artifactId)
                    put("p_after_revision", request.afterRevision)
                    put("p_limit", request.limit)
                },
            ).decodeList<RecoveryRow>()
            val marker = rows.lastOrNull()
                ?: return RecoveryResult.Operations(emptyList(), request.afterRevision, request.expectedRevisionHash.orEmpty(), true)
            if (marker.snapshotRequired) return RecoveryResult.SnapshotRequired(marker.serverRevision, marker.revisionHash)
            val envelopes = rows.mapNotNull { row ->
                row.envelope?.copy(serverRevision = row.serverRevision)
                    ?.decodeInbound(serverAt = System.currentTimeMillis())
                    ?.let { it as? WireDecodeResult.Accepted }
                    ?.envelope
            }
            RecoveryResult.Operations(envelopes, marker.serverRevision, marker.revisionHash, !marker.hasMore)
        } catch (_: Throwable) {
            RecoveryResult.Rejected("recovery_unavailable")
        }
    }

    override suspend fun fetchSnapshot(roomId: RoomId, minimumRevision: Long): SnapshotResult {
        val scope = roomId.artifactScope ?: return SnapshotResult.Rejected("invalid_room")
        return try {
            val row = client.postgrest.rpc(
                function = "read_collaboration_snapshot",
                parameters = buildJsonObject {
                    put("p_workspace", scope.workspaceId)
                    put("p_artifact", scope.artifactId)
                    put("p_minimum_revision", minimumRevision)
                },
            ).decodeAs<SnapshotRow>()
            SnapshotResult.Available(CollaborationSnapshot(
                roomId, row.serverRevision, row.revisionHash, row.protocolVersion,
                row.artifactSchemaVersion, row.snapshot,
            ))
        } catch (_: Throwable) {
            SnapshotResult.Rejected("snapshot_unavailable")
        }
    }

    override suspend fun saveSnapshot(snapshot: CollaborationSnapshot): SnapshotWriteResult {
        val scope = snapshot.roomId.artifactScope ?: return SnapshotWriteResult.Rejected("invalid_room")
        return try {
            client.postgrest.rpc(
                function = "save_collaboration_snapshot",
                parameters = buildJsonObject {
                    put("p_workspace", scope.workspaceId)
                    put("p_artifact", scope.artifactId)
                    put("p_server_revision", snapshot.serverRevision)
                    put("p_revision_hash", snapshot.revisionHash)
                    put("p_protocol", snapshot.protocolVersion)
                    put("p_schema", snapshot.artifactSchemaVersion)
                    put("p_snapshot", snapshot.payload)
                },
            ).decodeAs<Boolean>()
            SnapshotWriteResult.Stored
        } catch (error: Throwable) {
            val message = error.message.orEmpty().lowercase()
            val code = if ("denied" in message || "403" in message) "snapshot_write_denied"
            else "snapshot_write_unavailable"
            SnapshotWriteResult.Rejected(code)
        }
    }
}
