package com.norfold.app.domain.chat

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class PrivateChatArchiveSnapshot(
    val version: Int = 1,
    val accountId: String,
    val generatedAt: Long,
    val conversations: List<ArchivedChatConversation>,
    val messages: List<ArchivedChatMessage>,
    val tombstones: List<ArchivedChatTombstone>,
    val hiddenMessages: List<ArchivedChatHidden> = emptyList(),
)

@Serializable
data class ArchivedChatConversation(
    val syncId: String,
    val accountConversationId: String,
    val kind: String,
    val name: String,
    val counterpartUsername: String,
    val icon: String,
    val createdAt: Long,
    val pinned: Boolean,
    val coverUri: String? = null,
    val description: String = "",
    val rules: String = "",
    val rulesReminderDays: Int = 7,
    val securityMode: String,
    val securityEpochId: String? = null,
)

@Serializable
data class ArchivedChatMessage(
    val clientSyncId: String,
    val remoteRelayId: String? = null,
    val conversationSyncId: String,
    val authorUsername: String,
    val authorDisplayName: String,
    val body: String,
    val color: Long,
    val createdAt: Long,
    val system: Boolean = false,
    val attachmentName: String? = null,
    val attachmentMimeType: String? = null,
    val attachmentSizeBytes: Long? = null,
    val receiptStatus: String? = null,
    val replyPreview: String? = null,
    val editedAt: Long? = null,
    val reactionsJson: String = "{}",
    val pinned: Boolean = false,
    val starred: Boolean = false,
    val securityMode: String? = null,
    val securityEpochId: String? = null,
    val originPayloadJson: String? = null,
)

@Serializable
data class ArchivedChatHidden(
    val messageSyncId: String,
    val conversationId: String,
    val securityEpochId: String,
    val hiddenAt: Long,
    val reason: String,
)

@Serializable
data class ArchivedChatTombstone(
    val messageSyncId: String,
    val conversationId: String,
    val destructiveAt: Long,
    val reason: String,
)

object PrivateChatArchiveCodec {
    private val json = Json {
        encodeDefaults = true
        explicitNulls = false
        ignoreUnknownKeys = true
    }

    fun encode(snapshot: PrivateChatArchiveSnapshot): ByteArray =
        json.encodeToString(snapshot).encodeToByteArray()

    fun decode(bytes: ByteArray): PrivateChatArchiveSnapshot =
        json.decodeFromString<PrivateChatArchiveSnapshot>(bytes.decodeToString()).also {
            require(it.version == 1) { "Unsupported private Chat archive version ${it.version}." }
            require(it.accountId.isNotBlank()) { "Private Chat archive has no account identity." }
        }
}
