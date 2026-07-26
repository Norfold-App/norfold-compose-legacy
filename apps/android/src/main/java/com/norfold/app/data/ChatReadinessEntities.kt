package com.norfold.app.data

import androidx.room.Entity
import androidx.room.Index

@Entity(
    tableName = "secure_chat_outbox",
    primaryKeys = ["id"],
    indices = [Index("workspaceId"), Index("conversationId"), Index("nextAttemptAt")],
)
data class SecureChatOutboxEntity(
    val id: String,
    val workspaceId: Long,
    val conversationId: String,
    val operation: String,
    val targetMessageSyncId: String? = null,
    val body: String = "",
    val createdAt: Long,
    val replyPreview: String? = null,
    val editedAt: Long? = null,
    val reactionsJson: String? = null,
    val pinned: Boolean? = null,
    val attemptCount: Int = 0,
    val nextAttemptAt: Long = 0L,
    val lastError: String? = null,
)

@Entity(
    tableName = "secure_chat_relay_checkpoints",
    primaryKeys = ["deviceId", "cryptoGeneration"],
)
data class SecureChatRelayCheckpointEntity(
    val deviceId: String,
    val cryptoGeneration: Int,
    val lastRelayCreatedAt: String,
    val lastRelayId: String,
    val updatedAt: Long,
)

@Entity(
    tableName = "secure_chat_tombstones",
    primaryKeys = ["messageSyncId"],
    indices = [Index("conversationId"), Index("destructiveAt")],
)
data class SecureChatTombstoneEntity(
    val messageSyncId: String,
    val conversationId: String,
    val destructiveAt: Long,
    val reason: String,
)

@Entity(
    tableName = "private_chat_archive_manifests",
    primaryKeys = ["archiveId", "providerId"],
    indices = [Index("updatedAt")],
)
data class PrivateChatArchiveManifestEntity(
    val archiveId: String,
    val providerId: String,
    val updatedAt: Long,
    val lastMessageCreatedAt: Long,
    val messageCount: Long,
    val encryptedSizeBytes: Long,
)
