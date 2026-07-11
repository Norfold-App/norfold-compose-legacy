package com.norfold.app.data

import androidx.room.withTransaction
import java.security.MessageDigest
import java.util.UUID

enum class CloudObjectOperation { Upsert, Delete }

class CloudSyncQueue(private val database: NorfoldDatabase) {
    private val dao = database.dao()

    suspend fun enqueue(
        workspaceId: Long,
        objectType: String,
        objectSyncId: String,
        operation: CloudObjectOperation,
        payload: String,
        baseVersion: Long?,
        now: Long = System.currentTimeMillis(),
    ): Long {
        require(workspaceId > 0) { "Workspace id must be positive." }
        require(objectType.isNotBlank()) { "Object type is required." }
        require(runCatching { UUID.fromString(objectSyncId) }.isSuccess) { "Object sync id must be a UUID." }
        require(operation == CloudObjectOperation.Delete || payload.isNotBlank()) { "Upsert payload is required." }
        val operationId = UUID.randomUUID().toString()
        return database.withTransaction {
            if (operation == CloudObjectOperation.Delete) {
                dao.insertSyncTombstone(
                    SyncTombstoneEntity(
                        workspaceId = workspaceId,
                        objectType = objectType,
                        objectSyncId = objectSyncId,
                        deletedAt = now,
                    ),
                )
            }
            dao.insertSyncOutbox(
                SyncOutboxEntity(
                    workspaceId = workspaceId,
                    operationId = operationId,
                    objectType = objectType,
                    objectSyncId = objectSyncId,
                    operation = operation.name,
                    payload = payload,
                    contentHash = sha256(payload),
                    baseVersion = baseVersion,
                    nextAttemptAt = now,
                    createdAt = now,
                    updatedAt = now,
                ),
            )
        }
    }

    suspend fun claimBatch(workspaceId: Long, limit: Int = 50, now: Long = System.currentTimeMillis()): List<SyncOutboxEntity> {
        require(limit in 1..200) { "Sync batch limit must be between 1 and 200." }
        return database.withTransaction {
            dao.pendingSyncOperations(workspaceId, now, limit).mapNotNull { item ->
                if (dao.claimSyncOperation(item.id, now) == 1) item.copy(state = "Sending", updatedAt = now) else null
            }
        }
    }

    suspend fun markSucceeded(
        item: SyncOutboxEntity,
        localId: Long,
        remoteId: String,
        remoteVersion: Long,
        now: Long = System.currentTimeMillis(),
    ) {
        require(remoteVersion > 0) { "Remote version must be positive." }
        database.withTransaction {
            dao.insertRemoteBinding(
                RemoteObjectBindingEntity(
                    workspaceId = item.workspaceId,
                    objectType = item.objectType,
                    localId = localId,
                    syncId = item.objectSyncId,
                    remoteId = remoteId,
                    remoteVersion = remoteVersion,
                    contentHash = item.contentHash,
                    updatedAt = now,
                ),
            )
            if (item.operation == CloudObjectOperation.Delete.name) {
                dao.acknowledgeTombstone(item.workspaceId, item.objectType, item.objectSyncId, now)
            }
            dao.deleteSyncOperation(item.id)
        }
    }

    suspend fun markFailed(item: SyncOutboxEntity, cause: Throwable, now: Long = System.currentTimeMillis()) {
        val nextAttempt = now + retryDelayMs(item.attemptCount + 1)
        val message = cause.message?.take(400)?.ifBlank { cause::class.java.simpleName } ?: cause::class.java.simpleName
        dao.failSyncOperation(item.id, message, nextAttempt, now)
    }

    companion object {
        private const val InitialRetryMs = 15_000L
        private const val MaximumRetryMs = 6L * 60L * 60L * 1_000L

        fun retryDelayMs(attempt: Int): Long {
            val exponent = attempt.coerceIn(0, 20)
            return (InitialRetryMs * (1L shl exponent)).coerceAtMost(MaximumRetryMs)
        }

        private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }
    }
}
