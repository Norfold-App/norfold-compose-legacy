package com.norfold.app.cloud

import com.norfold.app.domain.ChatChannelVisibility
import com.norfold.app.domain.ChatSecurityMode
import com.norfold.app.domain.chat.SecureChatAttachmentV1
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.filter.FilterOperator
import io.github.jan.supabase.realtime.PostgresAction
import io.github.jan.supabase.realtime.channel
import io.github.jan.supabase.realtime.postgresChangeFlow
import io.github.jan.supabase.realtime.realtime
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.security.MessageDigest
import java.util.UUID

/**
 * Replaceable Supabase implementation of Chat's authorization control plane.
 * Message/crypto orchestration does not depend on PostgREST directly; only this
 * adapter knows the current RPC/table shape.
 */
object ChatBackend {
    private val client get() = NorfoldSupabase.client

    @Serializable
    data class WorkspaceSecuritySnapshot(
        @SerialName("workspace_id") val workspaceId: String,
        val mode: String,
        @SerialName("security_version") val securityVersion: String,
        @SerialName("epoch_number") val epochNumber: Int,
        @SerialName("security_epoch_id") val securityEpochId: String? = null,
        val acknowledged: Boolean,
        @SerialName("changed_at") val changedAt: String? = null,
    ) {
        val securityMode: ChatSecurityMode
            get() = when (mode.lowercase()) {
                "e2ee" -> ChatSecurityMode.EndToEndEncrypted
                "managed" -> ChatSecurityMode.Managed
                else -> error("Unknown workspace Chat security mode: $mode")
            }
    }

    @Serializable
    data class WorkspaceSecurityEvent(
        val id: String,
        @SerialName("workspace_id") val workspaceId: String,
        @SerialName("security_version") val securityVersion: String,
        @SerialName("epoch_number") val epochNumber: Int,
        @SerialName("previous_mode") val previousMode: String? = null,
        val mode: String,
        val reason: String,
        @SerialName("changed_by") val changedBy: String,
        @SerialName("created_at") val createdAt: String,
    ) {
        val securityMode: ChatSecurityMode
            get() = when (mode.lowercase()) {
                "e2ee" -> ChatSecurityMode.EndToEndEncrypted
                "managed" -> ChatSecurityMode.Managed
                else -> error("Unknown workspace Chat security mode: $mode")
            }

        val dividerText: String
            get() = when (reason) {
                "mode_changed" -> when (securityMode) {
                    ChatSecurityMode.EndToEndEncrypted ->
                        "Security changed · New messages are end-to-end encrypted"
                    ChatSecurityMode.Managed ->
                        "Security changed · New messages use managed workspace protection"
                }
                "workspace_membership_changed" ->
                    "Security updated · Workspace membership changed"
                "channel_membership_changed" ->
                    "Security updated · Channel membership changed"
                "channel_visibility_changed" ->
                    "Security updated · Channel visibility changed"
                "channel_created" -> "Security updated · A channel was created"
                "channel_deleted" -> "Security updated · A channel was removed"
                else -> "Workspace security initialized"
            }
    }

    @Serializable
    private data class WorkspaceSecurityModeResult(
        @SerialName("workspace_id") val workspaceId: String,
        val mode: String,
        @SerialName("security_version") val securityVersion: String,
        @SerialName("epoch_number") val epochNumber: Int,
        @SerialName("changed_at") val changedAt: String? = null,
    )

    @Serializable
    data class RemoteWorkspaceChannelMember(
        @SerialName("user_id") val userId: String,
        val handle: String? = null,
        @SerialName("display_name") val displayName: String = "",
        @SerialName("avatar_path") val avatarPath: String? = null,
        val role: String = "member",
        @SerialName("joined_at") val joinedAt: String? = null,
    )

    @Serializable
    data class GovernanceEvent(
        @SerialName("event_id") val eventId: String,
        @SerialName("event_type") val eventType: String,
        @SerialName("actor_id") val actorId: String? = null,
        @SerialName("actor_name") val actorName: String = "System",
        @SerialName("target_id") val targetId: String? = null,
        @SerialName("target_name") val targetName: String = "",
        val detail: JsonObject = JsonObject(emptyMap()),
        @SerialName("created_at") val createdAt: String,
    )

    @Serializable
    data class AbuseReportReceipt(
        @SerialName("report_id") val reportId: String,
        @SerialName("message_sync_id") val messageSyncId: String,
        @SerialName("reported_user_id") val reportedUserId: String,
        val category: String,
        val status: String,
        @SerialName("blocked_at_submission") val blockedAtSubmission: Boolean,
        @SerialName("created_at") val createdAt: String,
        @SerialName("updated_at") val updatedAt: String,
    )

    enum class AbuseReportCategory(val wireValue: String, val label: String) {
        Spam("spam", "Spam"),
        Harassment("harassment", "Harassment or bullying"),
        Hate("hate", "Hate speech"),
        SexualContent("sexual_content", "Sexual content"),
        Violence("violence", "Violence or threats"),
        Impersonation("impersonation", "Impersonation"),
        Scam("scam", "Scam or fraud"),
        SelfHarm("self_harm", "Self-harm concern"),
        Other("other", "Something else"),
    }

    suspend fun registerWorkspaceChannel(
        workspaceId: String,
        channelId: String,
        visibility: ChatChannelVisibility,
        general: Boolean = false,
    ) {
        require(NorfoldSupabase.isSignedIn()) { "Sign in before creating a team channel." }
        client.postgrest.rpc(
            function = "register_workspace_chat_channel",
            parameters = buildJsonObject {
                put("p_workspace", workspaceId)
                put("p_channel", channelId)
                put("p_visibility", visibility.name.lowercase())
                put("p_kind", if (general) "general" else "channel")
            },
        )
    }

    suspend fun workspaceSecuritySnapshot(
        workspaceId: String,
        channelId: String? = null,
    ): WorkspaceSecuritySnapshot = client.postgrest.rpc(
        function = "workspace_chat_security_snapshot",
        parameters = buildJsonObject {
            put("p_workspace", workspaceId)
            if (channelId == null) put("p_channel", JsonNull) else put("p_channel", channelId)
        },
    ).decodeList<WorkspaceSecuritySnapshot>().singleOrNull()
        ?: error("Workspace Chat security state is unavailable.")

    suspend fun workspaceSecurityEvents(workspaceId: String): List<WorkspaceSecurityEvent> =
        client.postgrest["workspace_chat_security_events"].select {
            filter { eq("workspace_id", workspaceId) }
        }.decodeList<WorkspaceSecurityEvent>()
            .filterNot { it.reason == "workspace_created" || it.reason == "migration_classification" }
            .sortedBy { it.createdAt }

    /**
     * Subscribe first and then fetch the current snapshot so a mode change cannot disappear in
     * the query/subscribe race. RLS still decides whether this workspace is visible.
     */
    fun workspaceSecurityChanges(
        workspaceId: String,
        channelId: String? = null,
    ): Flow<WorkspaceSecuritySnapshot> = channelFlow {
        val realtime = client.realtime
        val channel = realtime.channel("workspace-chat-security:$workspaceId") { }
        val changes = channel.postgresChangeFlow<PostgresAction>(schema = "public") {
            table = "workspace_chat_security_settings"
            filter("workspace_id", FilterOperator.EQ, workspaceId)
        }
        val collector = launch {
            changes.collect { send(workspaceSecuritySnapshot(workspaceId, channelId)) }
        }
        try {
            channel.subscribe(blockUntilSubscribed = true)
            send(workspaceSecuritySnapshot(workspaceId, channelId))
            collector.join()
        } finally {
            collector.cancel()
            realtime.removeChannel(channel)
        }
    }

    suspend fun ensureWorkspaceSecurityEpoch(
        workspaceId: String,
        channelId: String? = null,
    ): WorkspaceSecuritySnapshot = client.postgrest.rpc(
        function = "ensure_workspace_chat_security_epoch",
        parameters = buildJsonObject {
            put("p_workspace", workspaceId)
            if (channelId == null) put("p_channel", JsonNull) else put("p_channel", channelId)
        },
    ).decodeList<WorkspaceSecuritySnapshot>().singleOrNull()?.copy(workspaceId = workspaceId)
        ?: error("Workspace Chat security epoch is unavailable.")

    suspend fun acknowledgeWorkspaceSecurity(workspaceId: String, securityVersion: String) {
        client.postgrest.rpc(
            function = "acknowledge_workspace_chat_security",
            parameters = buildJsonObject {
                put("p_workspace", workspaceId)
                put("p_security_version", securityVersion)
            },
        )
    }

    suspend fun setWorkspaceSecurityMode(
        workspaceId: String,
        mode: ChatSecurityMode,
        expectedSecurityVersion: String,
    ): WorkspaceSecuritySnapshot {
        val result = client.postgrest.rpc(
            function = "set_workspace_chat_security_mode",
            parameters = buildJsonObject {
                put("p_workspace", workspaceId)
                put("p_mode", mode.wireValue())
                put("p_expected_security_version", expectedSecurityVersion)
            },
        ).decodeList<WorkspaceSecurityModeResult>().singleOrNull()
            ?: error("Workspace Chat security mode was not changed.")
        return workspaceSecuritySnapshot(result.workspaceId)
    }

    suspend fun upsertWorkspaceChannel(
        remoteWorkspaceId: String,
        deviceId: String,
        syncId: String,
        kind: String,
        name: String,
        icon: String,
        createdAt: Long,
        visibility: ChatChannelVisibility,
        security: WorkspaceSecuritySnapshot,
        participantUserIds: List<String> = emptyList(),
        participantUsernames: List<String> = emptyList(),
        participantDisplayNames: List<String> = emptyList(),
        baseVersion: Long? = null,
    ): RemoteWorkspaceObject {
        val content = buildJsonObject {
            put("kind", kind)
            put("name", name.trim())
            put("icon", icon)
            put("createdAt", createdAt)
            put("visibility", visibility.name.lowercase())
            put("securityMode", security.mode)
            security.securityEpochId?.let { put("securityEpochId", it) }
            put("securityVersion", security.securityVersion)
            put("participantUserIds", buildJsonArray { participantUserIds.forEach { add(JsonPrimitive(it)) } })
            put("participantUsernames", buildJsonArray { participantUsernames.forEach { add(JsonPrimitive(it)) } })
            put("participantDisplayNames", buildJsonArray { participantDisplayNames.forEach { add(JsonPrimitive(it)) } })
        }
        return applyWorkspaceObject(
            remoteWorkspaceId = remoteWorkspaceId,
            deviceId = deviceId,
            syncId = syncId,
            objectType = "chat_channel",
            content = content,
            baseVersion = baseVersion,
            idempotencyKey = UUID.randomUUID().toString(),
        )
    }

    suspend fun sendManagedWorkspaceMessage(
        remoteWorkspaceId: String,
        deviceId: String,
        username: String,
        displayName: String,
        body: String,
        color: Long,
        createdAt: Long,
        syncId: String,
        idempotencyKey: String,
        security: WorkspaceSecuritySnapshot,
        channelSyncId: String? = null,
        replyPreview: String? = null,
        attachmentName: String? = null,
        attachmentMimeType: String? = null,
        attachmentSizeBytes: Long? = null,
        attachmentStoragePath: String? = null,
        attachmentExternalUrl: String? = null,
        secureAttachment: SecureChatAttachmentV1? = null,
        editedAt: Long? = null,
        reactionsJson: String? = null,
        pinned: Boolean? = null,
        expiresAt: Long? = null,
        baseVersion: Long? = null,
    ): RemoteWorkspaceObject {
        require(security.securityMode == ChatSecurityMode.Managed) {
            "Managed transport cannot send an end-to-end encrypted workspace message."
        }
        require(security.acknowledged) {
            "Acknowledge this workspace's security disclosure before sending."
        }
        val epochId = requireNotNull(security.securityEpochId) {
            "Managed workspace security epoch is unavailable."
        }
        val content = buildJsonObject {
            put("authorUsername", username)
            put("authorDisplayName", displayName)
            put("body", body)
            put("color", color)
            put("createdAt", createdAt)
            put("securityMode", "managed")
            put("securityEpochId", epochId)
            put("securityVersion", security.securityVersion)
            if (channelSyncId != null) put("channelSyncId", channelSyncId)
            if (replyPreview != null) put("replyPreview", replyPreview)
            if (attachmentName != null) put("attachmentName", attachmentName)
            if (attachmentMimeType != null) put("attachmentMimeType", attachmentMimeType)
            if (attachmentSizeBytes != null) put("attachmentSizeBytes", attachmentSizeBytes)
            if (attachmentStoragePath != null) put("attachmentStoragePath", attachmentStoragePath)
            if (attachmentExternalUrl != null) put("attachmentExternalUrl", attachmentExternalUrl)
            if (secureAttachment != null) put("secureAttachment", secureAttachment.toJson())
            if (editedAt != null) put("editedAt", editedAt)
            if (reactionsJson != null) put("reactionsJson", reactionsJson)
            if (pinned != null) put("pinned", pinned)
            if (expiresAt != null) put("expiresAt", expiresAt)
        }
        return applyWorkspaceObject(
            remoteWorkspaceId = remoteWorkspaceId,
            deviceId = deviceId,
            syncId = syncId,
            objectType = "chat_message",
            content = content,
            baseVersion = baseVersion,
            idempotencyKey = idempotencyKey,
        )
    }

    suspend fun deleteManagedWorkspaceMessage(
        remoteWorkspaceId: String,
        deviceId: String,
        targetSyncId: String,
        idempotencyKey: String,
        baseVersion: Long?,
    ): RemoteWorkspaceObject = client.postgrest.rpc(
        function = "apply_workspace_object",
        parameters = buildJsonObject {
            put("target_workspace", remoteWorkspaceId)
            put("target_sync_id", targetSyncId)
            put("target_type", "chat_message")
            put("op", "delete")
            put("operation_id", idempotencyKey)
            put("source_device", deviceId)
            if (baseVersion == null) put("expected_version", JsonNull) else put("expected_version", baseVersion)
            put("body", JsonNull)
            put("body_hash", "")
        },
    ).decodeSingle()

    private suspend fun applyWorkspaceObject(
        remoteWorkspaceId: String,
        deviceId: String,
        syncId: String,
        objectType: String,
        content: JsonObject,
        baseVersion: Long?,
        idempotencyKey: String,
    ): RemoteWorkspaceObject = client.postgrest.rpc(
        function = "apply_workspace_object",
        parameters = buildJsonObject {
            put("target_workspace", remoteWorkspaceId)
            put("target_sync_id", syncId)
            put("target_type", objectType)
            put("op", "upsert")
            put("operation_id", idempotencyKey)
            put("source_device", deviceId)
            if (baseVersion == null) put("expected_version", JsonNull) else put("expected_version", baseVersion)
            put("body", content)
            put("body_hash", sha256(content.toString()))
        },
    ).decodeSingle()

    private fun ChatSecurityMode.wireValue(): String = when (this) {
        ChatSecurityMode.EndToEndEncrypted -> "e2ee"
        ChatSecurityMode.Managed -> "managed"
    }

    private fun SecureChatAttachmentV1.toJson(): JsonObject = buildJsonObject {
        put("version", version)
        put("mediaId", mediaId)
        put("storagePath", storagePath)
        put("messageId", messageId)
        put("securityEpochId", securityEpochId)
        put("displayName", displayName)
        put("mimeType", mimeType)
        put("plaintextSize", plaintextSize)
        put("ciphertextSize", ciphertextSize)
        put("key", key)
        put("nonce", nonce)
        put("ciphertextSha256", ciphertextSha256)
        put("plaintextSha256", plaintextSha256)
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray())
        .joinToString("") { "%02x".format(it) }

    suspend fun updateMessageRequestPreferences(
        senderPolicy: String,
        pendingBehavior: String,
        filePolicy: String,
        limitedFollowups: Int,
    ) {
        val accountId = NorfoldSupabase.currentAccountId()
            ?: error("Sign in before changing message-request privacy.")
        client.postgrest["message_request_preferences"].upsert(
            buildJsonObject {
                put("owner_id", accountId)
                put("sender_policy", senderPolicy)
                put("pending_behavior", pendingBehavior)
                put("request_file_policy", filePolicy)
                put("limited_followups", limitedFollowups.coerceIn(0, 5))
            },
        )
    }

    suspend fun extendMessageRequest(
        requestId: String,
        extraMessages: Int = 0,
        allowUntilIso: String? = null,
        untilDecided: Boolean = false,
    ) {
        val accountId = NorfoldSupabase.currentAccountId()
            ?: error("Sign in before extending a message request.")
        client.postgrest["message_request_extensions"].upsert(
            buildJsonObject {
                put("request_id", requestId)
                put("recipient_id", accountId)
                put("extra_messages", extraMessages.coerceIn(0, 5))
                if (allowUntilIso != null) put("allow_until", allowUntilIso)
                put("allow_until_decided", untilDecided)
            },
        )
    }

    suspend fun workspaceChannelMembers(
        workspaceId: String,
        channelId: String,
    ): List<RemoteWorkspaceChannelMember> = client.postgrest.rpc(
        function = "workspace_channel_members",
        parameters = buildJsonObject {
            put("p_workspace", workspaceId)
            put("p_channel", channelId)
        },
    ).decodeList()

    suspend fun governanceHistory(
        accountConversationId: String? = null,
        workspaceId: String? = null,
        channelId: String? = null,
        limit: Int = 50,
    ): List<GovernanceEvent> = client.postgrest.rpc(
        function = "chat_governance_history",
        parameters = buildJsonObject {
            if (accountConversationId == null) put("p_account_conversation", JsonNull)
            else put("p_account_conversation", accountConversationId)
            if (workspaceId == null) put("p_workspace", JsonNull) else put("p_workspace", workspaceId)
            if (channelId == null) put("p_channel", JsonNull) else put("p_channel", channelId)
            put("p_limit", limit.coerceIn(1, 200))
        },
    ).decodeList()

    /**
     * Submit only a server-verifiable message reference plus the reporter's explicit note.
     * The server derives the author/scope and never receives decrypted E2EE message content.
     */
    suspend fun submitAbuseReport(
        messageSyncId: String,
        securityEpochId: String,
        category: AbuseReportCategory,
        reporterNote: String,
        alsoBlock: Boolean,
    ): String {
        require(reporterNote.length <= 1_000) { "Report notes are limited to 1,000 characters." }
        return client.postgrest.rpc(
            function = "submit_chat_abuse_report",
            parameters = buildJsonObject {
                put("p_message_sync_id", messageSyncId)
                put("p_security_epoch", securityEpochId)
                put("p_category", category.wireValue)
                put("p_reporter_note", reporterNote.trim())
                put("p_also_block", alsoBlock)
            },
        ).data.trim('"')
    }

    suspend fun myAbuseReportReceipts(limit: Int = 50): List<AbuseReportReceipt> =
        client.postgrest.rpc(
            function = "my_chat_abuse_report_receipts",
            parameters = buildJsonObject { put("p_limit", limit.coerceIn(1, 200)) },
        ).decodeList()

    suspend fun setWorkspaceChannelMemberRole(
        workspaceId: String,
        channelId: String,
        memberId: String,
        role: String,
    ) {
        require(role in setOf("admin", "member"))
        client.postgrest.rpc(
            function = "set_workspace_channel_member_role",
            parameters = buildJsonObject {
                put("p_workspace", workspaceId)
                put("p_channel", channelId)
                put("p_member", memberId)
                put("p_role", role)
            },
        )
    }

    suspend fun addWorkspaceChannelMember(
        workspaceId: String,
        channelId: String,
        memberId: String,
    ) {
        client.postgrest.rpc(
            function = "add_workspace_channel_member",
            parameters = buildJsonObject {
                put("p_workspace", workspaceId)
                put("p_channel", channelId)
                put("p_member", memberId)
            },
        )
    }

    suspend fun transferWorkspaceChannelOwnership(
        workspaceId: String,
        channelId: String,
        memberId: String,
    ) {
        client.postgrest.rpc(
            function = "transfer_workspace_channel_ownership",
            parameters = buildJsonObject {
                put("p_workspace", workspaceId)
                put("p_channel", channelId)
                put("p_new_owner", memberId)
            },
        )
    }

    suspend fun removeWorkspaceChannelMember(
        workspaceId: String,
        channelId: String,
        memberId: String,
    ) {
        client.postgrest.rpc(
            function = "remove_workspace_channel_member",
            parameters = buildJsonObject {
                put("p_workspace", workspaceId)
                put("p_channel", channelId)
                put("p_member", memberId)
            },
        )
    }
}
