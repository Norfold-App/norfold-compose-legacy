package com.norfold.app.data.chat

import androidx.room.withTransaction
import com.norfold.app.data.ChatChannelEntity
import com.norfold.app.data.ChatMessageEntity
import com.norfold.app.data.NorfoldDatabase
import com.norfold.app.data.PrivateChatArchiveManifestEntity
import com.norfold.app.data.RemoteObjectBindingEntity
import com.norfold.app.data.SecureChatTombstoneEntity
import com.norfold.app.data.SecureChatUserHiddenEntity
import com.norfold.app.domain.WorkspaceObjectType
import com.norfold.app.domain.chat.ArchiveCryptoEngine
import com.norfold.app.domain.chat.ArchiveObject
import com.norfold.app.domain.chat.ArchiveProvider
import com.norfold.app.domain.chat.ArchivedChatConversation
import com.norfold.app.domain.chat.ArchivedChatMessage
import com.norfold.app.domain.chat.ArchivedChatHidden
import com.norfold.app.domain.chat.ArchivedChatTombstone
import com.norfold.app.domain.chat.PrivateChatArchiveCodec
import com.norfold.app.domain.chat.PrivateChatArchiveSnapshot
import java.security.MessageDigest

data class PrivateChatArchiveWriteResult(
    val archiveId: String,
    val updatedAt: Long,
    val messageCount: Int,
    val providersWritten: Set<String>,
    val providerFailures: Map<String, String>,
)

data class PrivateChatArchiveRestoreResult(
    val providerId: String,
    val conversationsAdded: Int,
    val messagesAdded: Int,
    val tombstonesAdded: Int,
    val hiddenMessagesAdded: Int = 0,
)

/**
 * Owns the private-history boundary. It intentionally archives only account DM/group history and
 * anti-resurrection tombstones: operational outbox rows, relay cursors, workspace messages, and
 * live device credentials never enter this portable snapshot.
 */
class PrivateChatArchiveCoordinator(
    private val database: NorfoldDatabase,
    private val crypto: ArchiveCryptoEngine,
    private val localProvider: ArchiveProvider,
    private val cloudProvider: ArchiveProvider? = null,
) {
    private val dao = database.dao()

    suspend fun write(accountId: String, includeCloud: Boolean): PrivateChatArchiveWriteResult {
        require(accountId.isNotBlank()) { "Sign in before writing a private Chat archive." }
        val archiveId = archiveId(accountId)
        val now = System.currentTimeMillis()
        val snapshot = buildSnapshot(accountId, now)
        val plaintext = PrivateChatArchiveCodec.encode(snapshot)
        val envelope = try {
            crypto.seal(archiveId, plaintext, now)
        } finally {
            plaintext.fill(0)
        }
        val value = ArchiveObject(archiveId, envelope, now)
        val providers = buildList {
            add(localProvider)
            if (includeCloud && cloudProvider != null) add(cloudProvider)
        }.distinctBy(ArchiveProvider::providerId)
        val written = linkedSetOf<String>()
        val failures = linkedMapOf<String, String>()
        providers.forEach { provider ->
            runCatching { provider.put(value) }
                .onSuccess {
                    written += provider.providerId
                    dao.upsertPrivateChatArchiveManifest(
                        PrivateChatArchiveManifestEntity(
                            archiveId = archiveId,
                            providerId = provider.providerId,
                            updatedAt = now,
                            lastMessageCreatedAt = snapshot.messages.maxOfOrNull { it.createdAt } ?: 0L,
                            messageCount = snapshot.messages.size.toLong(),
                            encryptedSizeBytes = envelope.encodeToByteArray().size.toLong(),
                        ),
                    )
                }
                .onFailure { failures[provider.providerId] = it.message ?: it::class.java.simpleName }
        }
        check(written.isNotEmpty()) { "No private Chat archive provider accepted the encrypted snapshot." }
        return PrivateChatArchiveWriteResult(archiveId, now, snapshot.messages.size, written, failures)
    }

    suspend fun restore(
        accountId: String,
        providerId: String,
        recoveryMaterial: String? = null,
    ): PrivateChatArchiveRestoreResult {
        require(accountId.isNotBlank()) { "Sign in before restoring private Chat history." }
        val provider = providers().firstOrNull { it.providerId == providerId }
            ?: error("Private Chat archive provider is unavailable: $providerId")
        val archiveId = archiveId(accountId)
        val stored = provider.get(archiveId) ?: error("No private Chat archive exists for this account.")
        val plaintext = if (recoveryMaterial.isNullOrBlank()) {
            crypto.open(archiveId, stored.encryptedEnvelope)
        } else {
            crypto.openWithRecovery(archiveId, stored.encryptedEnvelope, recoveryMaterial)
        }
        val snapshot = try {
            PrivateChatArchiveCodec.decode(plaintext)
        } finally {
            plaintext.fill(0)
        }
        require(snapshot.accountId == accountId) { "Private Chat archive belongs to another account." }
        return merge(snapshot, provider.providerId)
    }

    fun getOrCreateRecoveryMaterial(accountId: String): String =
        crypto.getOrCreateRecoveryMaterial(archiveId(accountId))

    suspend fun availableProviders(accountId: String): Set<String> {
        val id = archiveId(accountId)
        return providers().mapNotNull { provider ->
            runCatching { provider.get(id) }.getOrNull()?.let { provider.providerId }
        }.toSet()
    }

    suspend fun hasLocalAccountHistory(accountId: String): Boolean =
        dao.accountChatMessages(accountId).isNotEmpty()

    private suspend fun buildSnapshot(accountId: String, generatedAt: Long): PrivateChatArchiveSnapshot =
        database.withTransaction {
            val channels = dao.accountChatChannels(accountId)
            val channelById = channels.associateBy { it.id }
            val messageBindings = dao.remoteBindingsByType(WorkspaceObjectType.ChatMessage.name)
                .associateBy { it.localId }
            val messages = dao.allChatMessages().filter { it.channelId in channelById }
            val conversationIds = channels.mapNotNull { it.accountConversationId }.toSet()
            val tombstones = dao.secureChatTombstones().filter { it.conversationId in conversationIds }
            val hiddenMessages = dao.secureChatUserHidden(accountId)
                .filter { it.conversationId in conversationIds }
            PrivateChatArchiveSnapshot(
                accountId = accountId,
                generatedAt = generatedAt,
                conversations = channels.map { it.toArchive() }.sortedBy { it.createdAt },
                messages = messages.map { message ->
                    val channel = requireNotNull(message.channelId?.let(channelById::get))
                    message.toArchive(
                        conversationSyncId = channel.syncId,
                        binding = messageBindings[message.id],
                        accountId = accountId,
                    )
                }.sortedBy { it.createdAt },
                tombstones = tombstones.map {
                    ArchivedChatTombstone(it.messageSyncId, it.conversationId, it.destructiveAt, it.reason)
                },
                hiddenMessages = hiddenMessages.map {
                    ArchivedChatHidden(
                        it.messageSyncId,
                        it.conversationId,
                        it.securityEpochId,
                        it.hiddenAt,
                        it.reason,
                    )
                },
            )
        }

    private suspend fun merge(
        snapshot: PrivateChatArchiveSnapshot,
        providerId: String,
    ): PrivateChatArchiveRestoreResult = database.withTransaction {
        val workspaceId = dao.settings()?.activeWorkspaceId ?: 1L
        var channelsAdded = 0
        var messagesAdded = 0
        var tombstonesAdded = 0
        var hiddenMessagesAdded = 0
        snapshot.tombstones.forEach { tombstone ->
            if (dao.secureChatTombstone(tombstone.messageSyncId) == null) tombstonesAdded += 1
            dao.upsertSecureChatTombstone(
                SecureChatTombstoneEntity(
                    tombstone.messageSyncId,
                    tombstone.conversationId,
                    tombstone.destructiveAt,
                    tombstone.reason,
                ),
            )
        }
        snapshot.hiddenMessages.forEach { hidden ->
            if (dao.secureChatUserHidden(snapshot.accountId, hidden.messageSyncId) == null) {
                hiddenMessagesAdded += 1
            }
            dao.upsertSecureChatUserHidden(
                SecureChatUserHiddenEntity(
                    snapshot.accountId,
                    hidden.messageSyncId,
                    hidden.conversationId,
                    hidden.securityEpochId,
                    hidden.hiddenAt,
                    hidden.reason,
                ),
            )
        }
        val channelIds = linkedMapOf<String, Long>()
        snapshot.conversations.forEach { archived ->
            val existing = dao.chatChannelByAccountConversation(snapshot.accountId, archived.accountConversationId)
            val id = existing?.id ?: dao.insertChatChannel(
                archived.toEntity(workspaceId, snapshot.accountId),
            ).also { channelsAdded += 1 }
            channelIds[archived.syncId] = id
        }
        snapshot.messages.forEach { archived ->
            if (dao.secureChatTombstone(archived.clientSyncId) != null) return@forEach
            if (dao.secureChatUserHidden(snapshot.accountId, archived.clientSyncId) != null) return@forEach
            if (dao.bindingBySyncIdAnyWorkspace(WorkspaceObjectType.ChatMessage.name, archived.clientSyncId) != null) {
                return@forEach
            }
            val channelId = channelIds[archived.conversationSyncId] ?: return@forEach
            val localId = dao.insertChatMessage(archived.toEntity(workspaceId, channelId))
            dao.insertRemoteBinding(
                RemoteObjectBindingEntity(
                    workspaceId = workspaceId,
                    objectType = WorkspaceObjectType.ChatMessage.name,
                    localId = localId,
                    syncId = archived.clientSyncId,
                    remoteId = archived.remoteRelayId,
                    remoteVersion = 1,
                    updatedAt = snapshot.generatedAt,
                ),
            )
            messagesAdded += 1
        }
        PrivateChatArchiveRestoreResult(providerId, channelsAdded, messagesAdded, tombstonesAdded, hiddenMessagesAdded)
    }

    private fun providers(): List<ArchiveProvider> = listOfNotNull(localProvider, cloudProvider)
        .distinctBy(ArchiveProvider::providerId)

    private fun archiveId(accountId: String): String = sha256("norfold-private-chat-archive-v1\n$accountId")

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.encodeToByteArray()).joinToString("") { "%02x".format(it) }

    private fun ChatChannelEntity.toArchive() = ArchivedChatConversation(
        syncId = syncId,
        accountConversationId = requireNotNull(accountConversationId),
        kind = kind,
        name = name,
        counterpartUsername = counterpartUsername,
        icon = icon,
        createdAt = createdAt,
        pinned = pinned,
        coverUri = coverUri,
        description = description,
        rules = rules,
        rulesReminderDays = rulesReminderDays,
        securityMode = securityMode,
        securityEpochId = securityEpochId,
        defaultLifespanSeconds = defaultLifespanSeconds,
    )

    private fun ChatMessageEntity.toArchive(
        conversationSyncId: String,
        binding: RemoteObjectBindingEntity?,
        accountId: String,
    ) = ArchivedChatMessage(
        clientSyncId = binding?.syncId ?: sha256(
            // The local row id is immutable on the source device. After first restore the same
            // value is persisted as a binding, so edits never manufacture a second archive id.
            "$accountId\n$conversationSyncId\nlocal:$id\n$createdAt",
        ),
        remoteRelayId = binding?.remoteId,
        conversationSyncId = conversationSyncId,
        authorUsername = authorUsername,
        authorDisplayName = authorDisplayName,
        body = body,
        color = color,
        createdAt = createdAt,
        system = system,
        attachmentName = attachmentName,
        attachmentMimeType = attachmentMimeType,
        attachmentSizeBytes = attachmentSizeBytes,
        receiptStatus = receiptStatus,
        replyPreview = replyPreview,
        editedAt = editedAt,
        reactionsJson = reactionsJson,
        pinned = pinned,
        starred = starred,
        securityMode = securityMode,
        securityEpochId = securityEpochId,
        originPayloadJson = originPayloadJson,
        expiresAt = expiresAt,
    )

    private fun ArchivedChatConversation.toEntity(workspaceId: Long, accountId: String) = ChatChannelEntity(
        syncId = syncId,
        kind = kind,
        name = name,
        counterpartUsername = counterpartUsername,
        icon = icon,
        createdAt = createdAt,
        workspaceId = workspaceId,
        pinned = pinned,
        accountConversationId = accountConversationId,
        accountOwnerId = accountId,
        coverUri = coverUri,
        description = description,
        rules = rules,
        rulesReminderDays = rulesReminderDays,
        securityMode = securityMode,
        securityEpochId = securityEpochId,
        defaultLifespanSeconds = defaultLifespanSeconds,
    )

    private fun ArchivedChatMessage.toEntity(workspaceId: Long, channelId: Long) = ChatMessageEntity(
        authorUsername = authorUsername,
        authorDisplayName = authorDisplayName,
        body = body,
        color = color,
        createdAt = createdAt,
        system = system,
        attachmentName = attachmentName,
        attachmentMimeType = attachmentMimeType,
        attachmentSizeBytes = attachmentSizeBytes,
        workspaceId = workspaceId,
        channelId = channelId,
        receiptStatus = receiptStatus,
        replyPreview = replyPreview,
        editedAt = editedAt,
        reactionsJson = reactionsJson,
        pinned = pinned,
        starred = starred,
        securityMode = securityMode,
        securityEpochId = securityEpochId,
        originPayloadJson = originPayloadJson,
        expiresAt = expiresAt,
    )
}
