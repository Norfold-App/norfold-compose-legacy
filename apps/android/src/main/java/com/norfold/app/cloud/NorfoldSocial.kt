package com.norfold.app.cloud

import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.filter.FilterOperator
import io.github.jan.supabase.realtime.PostgresAction
import io.github.jan.supabase.realtime.channel
import io.github.jan.supabase.realtime.decodeRecordOrNull
import io.github.jan.supabase.realtime.postgresChangeFlow
import io.github.jan.supabase.realtime.realtime
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.UUID

/**
 * Account-level social graph client (chat overhaul Phase 2): contacts, friend
 * invites, blocks, and personal DM/group conversations that live outside any
 * workspace. Backed by supabase/migrations/202607200001_social_graph.sql —
 * every call here fails until that migration is deployed.
 *
 * Contact model: edges are one-directional; "friend" == both directions exist
 * (mutual). Mutuality only gates notifications on the receiving device (silent
 * stranger-DM rule); delivery is gated by nothing except an explicit block.
 */
object NorfoldSocial {

    @Serializable
    data class RemoteContact(
        @SerialName("contact_id") val contactId: String,
        val handle: String? = null,
        @SerialName("display_name") val displayName: String = "",
        @SerialName("avatar_path") val avatarPath: String? = null,
        val source: String = "handle",
        val mutual: Boolean = false,
        @SerialName("created_at") val createdAt: String? = null,
    )

    @Serializable
    data class RemoteDirectConversation(
        @SerialName("conversation_id") val conversationId: String,
        val kind: String,
        val name: String = "",
        @SerialName("peer_id") val peerId: String? = null,
        @SerialName("peer_handle") val peerHandle: String? = null,
        @SerialName("peer_display_name") val peerDisplayName: String? = null,
        @SerialName("peer_avatar_path") val peerAvatarPath: String? = null,
        @SerialName("member_count") val memberCount: Long = 0,
        @SerialName("last_message") val lastMessage: JsonObject? = null,
        @SerialName("last_sender") val lastSender: String? = null,
        @SerialName("last_at") val lastAt: String? = null,
        @SerialName("created_at") val createdAt: String? = null,
        val icon: String = "",
        @SerialName("cover_path") val coverPath: String? = null,
        val description: String = "",
        val rules: String = "",
        @SerialName("rules_reminder_days") val rulesReminderDays: Int = 7,
        @SerialName("my_role") val myRole: String = "member",
        @SerialName("inbound_request_status") val inboundRequestStatus: String? = null,
    )

    @Serializable
    data class RemoteContactRequest(
        @SerialName("request_id") val requestId: String,
        @SerialName("conversation_id") val conversationId: String,
        @SerialName("sender_id") val senderId: String,
        @SerialName("sender_handle") val senderHandle: String? = null,
        @SerialName("sender_display_name") val senderDisplayName: String = "",
        @SerialName("sender_avatar_path") val senderAvatarPath: String? = null,
        val preview: JsonObject? = null,
        @SerialName("created_at") val createdAt: String? = null,
    )

    @Serializable
    data class RemoteBlockedAccount(
        @SerialName("owner_id") val ownerId: String,
        @SerialName("blocked_id") val blockedId: String,
    )

    @Serializable
    data class RemoteGroupMember(
        @SerialName("user_id") val userId: String,
        val handle: String? = null,
        @SerialName("display_name") val displayName: String = "",
        @SerialName("avatar_path") val avatarPath: String? = null,
        val role: String = "member",
        @SerialName("joined_at") val joinedAt: String? = null,
    )

    @Serializable
    data class RemoteDirectMessage(
        val id: String,
        @SerialName("conversation_id") val conversationId: String,
        @SerialName("sender_id") val senderId: String,
        @SerialName("client_sync_id") val clientSyncId: String,
        val content: JsonObject,
        @SerialName("created_at") val createdAt: String? = null,
    )

    @Serializable
    data class DirectReceiptSummary(
        @SerialName("message_sync_id") val messageSyncId: String,
        @SerialName("delivered_count") val deliveredCount: Long = 0,
        @SerialName("viewed_count") val viewedCount: Long = 0,
    )

    private val client get() = NorfoldSupabase.client

    private fun requireAccount(): String {
        ExternalServiceConfig.requireSupabase()
        return client.auth.currentUserOrNull()?.id
            ?: error("Sign in to Norfold Cloud to use contacts and personal chats.")
    }

    // -- Contacts -----------------------------------------------------------

    suspend fun myContacts(): List<RemoteContact> {
        ExternalServiceConfig.requireSupabase()
        if (!NorfoldSupabase.isSignedIn()) return emptyList()
        return client.postgrest.rpc("my_contacts").decodeList()
    }

    /** Adds a contact by their unique @handle. Returns the contact's account id. */
    suspend fun addContactByHandle(handle: String): String {
        requireAccount()
        return client.postgrest.rpc(
            function = "add_contact_by_handle",
            parameters = buildJsonObject { put("p_handle", handle.trim().removePrefix("@")) },
        ).data.trim('"')
    }

    suspend fun removeContact(contactId: String) {
        val account = requireAccount()
        client.postgrest["contacts"].delete {
            filter {
                eq("owner_id", account)
                eq("contact_id", contactId)
            }
        }
    }

    /** Creates a single-use invite token; the caller wraps it into a link or QR. */
    suspend fun createFriendInvite(): String {
        requireAccount()
        return client.postgrest.rpc("create_friend_invite").data.trim('"')
    }

    /** Redeems an invite token; both sides become mutual contacts. Returns the inviter's id. */
    suspend fun redeemFriendInvite(token: String): String {
        requireAccount()
        return client.postgrest.rpc(
            function = "redeem_friend_invite",
            parameters = buildJsonObject { put("p_token", token.trim()) },
        ).data.trim('"')
    }

    // -- Blocks -------------------------------------------------------------

    suspend fun blockUser(userId: String) {
        val account = requireAccount()
        client.postgrest["blocked_users"].upsert(
            buildJsonObject {
                put("owner_id", account)
                put("blocked_id", userId)
            },
        )
    }

    suspend fun unblockUser(userId: String) {
        val account = requireAccount()
        client.postgrest["blocked_users"].delete {
            filter {
                eq("owner_id", account)
                eq("blocked_id", userId)
            }
        }
    }

    suspend fun myBlockedAccounts(): List<RemoteBlockedAccount> {
        val account = requireAccount()
        return client.postgrest["blocked_users"].select {
            filter { eq("owner_id", account) }
        }.decodeList()
    }

    suspend fun myContactRequests(): List<RemoteContactRequest> {
        ExternalServiceConfig.requireSupabase()
        if (!NorfoldSupabase.isSignedIn()) return emptyList()
        return client.postgrest.rpc("my_contact_requests").decodeList()
    }

    suspend fun respondContactRequest(requestId: String, accept: Boolean) {
        requireAccount()
        client.postgrest.rpc(
            function = "respond_contact_request",
            parameters = buildJsonObject {
                put("p_request", requestId)
                put("p_accept", accept)
            },
        )
    }

    // -- Personal conversations --------------------------------------------

    suspend fun myDirectConversations(): List<RemoteDirectConversation> {
        ExternalServiceConfig.requireSupabase()
        if (!NorfoldSupabase.isSignedIn()) return emptyList()
        return client.postgrest.rpc("my_direct_conversations").decodeList()
    }

    /** Opens (or returns) the 1:1 conversation with a peer account. */
    suspend fun openDirectConversation(peerId: String): String {
        requireAccount()
        return client.postgrest.rpc(
            function = "open_direct_conversation",
            parameters = buildJsonObject { put("p_peer", peerId) },
        ).data.trim('"')
    }

    /** Creates a personal group; only the creator's contacts can be initial members. */
    suspend fun createPersonalGroup(name: String, memberIds: List<String>): String {
        requireAccount()
        return client.postgrest.rpc(
            function = "create_personal_group",
            parameters = buildJsonObject {
                put("p_name", name.trim())
                put("p_member_ids", buildJsonArray { memberIds.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } })
            },
        ).data.trim('"')
    }

    suspend fun setGroupMetadata(
        conversationId: String,
        name: String,
        icon: String,
        coverPath: String?,
        description: String,
        rules: String,
        rulesReminderDays: Int,
    ) {
        requireAccount()
        client.postgrest.rpc(
            function = "set_group_meta",
            parameters = buildJsonObject {
                put("p_conversation", conversationId)
                put("p_name", name.trim())
                put("p_icon", icon.trim())
                if (coverPath == null) put("p_cover_path", kotlinx.serialization.json.JsonNull)
                else put("p_cover_path", coverPath.trim())
                put("p_description", description.trim())
                put("p_rules", rules.trim())
                put("p_rules_reminder_days", rulesReminderDays.coerceIn(1, 365))
            },
        )
    }

    suspend fun addGroupMember(conversationId: String, memberId: String) {
        requireAccount()
        client.postgrest.rpc(
            function = "add_group_member",
            parameters = buildJsonObject {
                put("p_conversation", conversationId)
                put("p_member", memberId)
            },
        )
    }

    suspend fun leaveGroup(conversationId: String) {
        requireAccount()
        client.postgrest.rpc(
            function = "leave_group",
            parameters = buildJsonObject { put("p_conversation", conversationId) },
        )
    }

    suspend fun groupMembers(conversationId: String): List<RemoteGroupMember> {
        requireAccount()
        return client.postgrest.rpc(
            function = "group_members",
            parameters = buildJsonObject { put("p_conversation", conversationId) },
        ).decodeList()
    }

    suspend fun setGroupMemberRole(conversationId: String, memberId: String, role: String) {
        require(role in setOf("admin", "member"))
        requireAccount()
        client.postgrest.rpc(
            function = "set_group_member_role",
            parameters = buildJsonObject {
                put("p_conversation", conversationId)
                put("p_member", memberId)
                put("p_role", role)
            },
        )
    }

    suspend fun transferGroupOwnership(conversationId: String, memberId: String) {
        requireAccount()
        client.postgrest.rpc(
            function = "transfer_group_ownership",
            parameters = buildJsonObject {
                put("p_conversation", conversationId)
                put("p_new_owner", memberId)
            },
        )
    }

    suspend fun removeGroupMember(conversationId: String, memberId: String) {
        requireAccount()
        client.postgrest.rpc(
            function = "remove_group_member",
            parameters = buildJsonObject {
                put("p_conversation", conversationId)
                put("p_member", memberId)
            },
        )
    }

    /**
     * Messages of one personal conversation. Pass [since] (ISO created_at from a
     * previous call) for incremental fetches; gte + client-side dedupe by id.
     */
    suspend fun directMessages(conversationId: String, since: String? = null): List<RemoteDirectMessage> {
        ExternalServiceConfig.requireSupabase()
        if (!NorfoldSupabase.isSignedIn()) return emptyList()
        return client.postgrest["direct_messages"].select {
            filter {
                eq("conversation_id", conversationId)
                if (since != null) gte("created_at", since)
            }
        }.decodeList()
    }

    /**
     * Sends a message into a personal conversation. [syncId] should be generated
     * once per logical message and reused on retries: the server dedupes on
     * (conversation_id, client_sync_id).
     */
    suspend fun sendDirectMessage(
        conversationId: String,
        body: String,
        createdAt: Long,
        syncId: String = UUID.randomUUID().toString(),
    ): String {
        val account = requireAccount()
        val content = buildJsonObject {
            put("body", body)
            put("createdAt", createdAt)
        }
        client.postgrest["direct_messages"].insert(
            buildJsonObject {
                put("conversation_id", conversationId)
                put("sender_id", account)
                put("client_sync_id", syncId)
                put("content", content)
            },
        )
        return syncId
    }

    /**
     * Live message stream for one personal conversation: subscribes first, then
     * replays the catch-up query. Callers dedupe by message id on import.
     */
    fun directMessageChanges(conversationId: String, since: String? = null): Flow<RemoteDirectMessage> = channelFlow {
        requireAccount()
        val realtime = client.realtime
        val channel = realtime.channel("direct:$conversationId") { }
        val changes = channel.postgresChangeFlow<PostgresAction>(schema = "public") {
            table = "direct_messages"
            filter("conversation_id", FilterOperator.EQ, conversationId)
        }
        val collector = launch {
            changes.collect { action ->
                val remote = when (action) {
                    is PostgresAction.Insert -> action.decodeRecordOrNull<RemoteDirectMessage>()
                    is PostgresAction.Update -> action.decodeRecordOrNull<RemoteDirectMessage>()
                    else -> null
                }
                if (remote != null) send(remote)
            }
        }
        try {
            channel.subscribe(blockUntilSubscribed = true)
            directMessages(conversationId, since).forEach { send(it) }
            collector.join()
        } finally {
            collector.cancel()
            realtime.removeChannel(channel)
        }
    }

    suspend fun recordDirectMessageReceipts(messageSyncIds: List<String>, viewed: Boolean) {
        if (messageSyncIds.isEmpty()) return
        requireAccount()
        client.postgrest.rpc(
            function = "record_direct_message_receipts",
            parameters = buildJsonObject {
                put("p_message_sync_ids", buildJsonArray {
                    messageSyncIds.distinct().take(500).forEach {
                        add(kotlinx.serialization.json.JsonPrimitive(it))
                    }
                })
                put("p_viewed", viewed)
            },
        )
    }

    suspend fun directMessageReceiptSummaries(messageSyncIds: List<String>): List<DirectReceiptSummary> {
        if (messageSyncIds.isEmpty()) return emptyList()
        requireAccount()
        return client.postgrest.rpc(
            function = "direct_message_receipt_summary",
            parameters = buildJsonObject {
                put("p_message_sync_ids", buildJsonArray {
                    messageSyncIds.distinct().take(500).forEach {
                        add(kotlinx.serialization.json.JsonPrimitive(it))
                    }
                })
            },
        ).decodeList()
    }
}
