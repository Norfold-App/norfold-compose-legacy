package com.norfold.app.cloud

import com.norfold.app.domain.ChatChannelVisibility
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Replaceable Supabase implementation of Chat's authorization control plane.
 * Message/crypto orchestration does not depend on PostgREST directly; only this
 * adapter knows the current RPC/table shape.
 */
object ChatBackend {
    private val client get() = NorfoldSupabase.client

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
}
