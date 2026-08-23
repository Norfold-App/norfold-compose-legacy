package com.norfold.app.cloud

import io.github.jan.supabase.SupabaseClient
import com.norfold.app.domain.RemoteEditorResetOutcome
import com.norfold.app.domain.SyncedCalendarChange
import com.norfold.app.domain.classifyRemoteEditorResetFailure
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.OtpType
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.Google
import io.github.jan.supabase.auth.providers.builtin.Email
import io.github.jan.supabase.auth.providers.builtin.IDToken
import io.github.jan.supabase.auth.status.SessionStatus
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.functions.Functions
import io.github.jan.supabase.functions.functions
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.realtime.Realtime
import io.github.jan.supabase.realtime.channel
import io.github.jan.supabase.realtime.realtime
import io.github.jan.supabase.realtime.PostgresAction
import io.github.jan.supabase.realtime.decodeRecordOrNull
import io.github.jan.supabase.realtime.postgresChangeFlow
import io.github.jan.supabase.realtime.presenceDataFlow
import io.github.jan.supabase.realtime.track
import io.github.jan.supabase.storage.Storage
import io.github.jan.supabase.storage.storage
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.filter.FilterOperator
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.security.MessageDigest
import java.util.UUID
import kotlin.time.Duration.Companion.seconds

@Serializable
data class RemoteWorkspace(
    val id: String,
    val name: String,
    val privacy: String,
    @SerialName("server_version") val serverVersion: Long = 1,
)

@Serializable
data class RemoteWorkspaceObject(
    val id: String,
    @SerialName("workspace_id") val workspaceId: String,
    @SerialName("client_sync_id") val clientSyncId: String,
    @SerialName("object_type") val objectType: String,
    @SerialName("server_version") val serverVersion: Long,
    val content: JsonObject? = null,
    @SerialName("content_hash") val contentHash: String,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
    @SerialName("deleted_at") val deletedAt: String? = null,
)

/**
 * One workspace member joined with their profile. Decoded from the embedded PostgREST select
 * `profiles!inner(...)`; the nested [profile] is non-null in practice because of the inner join,
 * but kept nullable so a schema hiccup degrades gracefully instead of failing the whole decode.
 */
@Serializable
data class RemoteWorkspaceMember(
    @SerialName("user_id") val userId: String,
    val role: String = "member",
    @SerialName("joined_at") val joinedAt: String? = null,
    @SerialName("removed_at") val removedAt: String? = null,
    @SerialName("profiles") val profile: RemoteMemberProfile? = null,
)

@Serializable
data class RemoteMemberProfile(
    val id: String,
    val handle: String? = null,
    @SerialName("display_name") val displayName: String = "",
    @SerialName("avatar_path") val avatarPath: String? = null,
)

/**
 * Maps a decoded roster row to the domain model. [workspaceId] defaults to 1 (the single local
 * workspace today); callers with a real local id can override. The nested profile is assumed
 * present — the select uses `profiles!inner`, and [NorfoldSupabase.workspaceMembers] drops null
 * ones — so the elvis fallbacks here only guard against a malformed row.
 */
fun RemoteWorkspaceMember.toWorkspaceMember(workspaceId: Long = 1L): com.norfold.app.domain.WorkspaceMember {
    val fallbackHandle = "user-" + userId.take(8)
    return com.norfold.app.domain.WorkspaceMember(
        userId = userId,
        username = profile?.handle ?: fallbackHandle,
        displayName = profile?.displayName?.takeIf { it.isNotBlank() }
            ?: profile?.handle
            ?: fallbackHandle,
        avatarPath = profile?.avatarPath,
        role = role,
        joinedAt = joinedAt?.let { parsed ->
            runCatching { java.time.Instant.parse(parsed).toEpochMilli() }.getOrDefault(0L)
        } ?: 0L,
        workspaceId = workspaceId,
    )
}

data class TeamInvitation(
    val id: String,
    val token: String,
    val expiresAt: String,
)

data class TelegramVerificationStart(
    val verificationId: String,
    val telegramUrl: String,
    val expiresAt: String,
)

/**
 * A calendar provider Norfold can import from. The wire names match the `integration_provider`
 * Postgres enum exactly, because they are the primary key component the server upserts on.
 */
enum class CalendarProvider(val wireName: String, val label: String) {
    Google("google_calendar", "Google Calendar"),
    Outlook("outlook_calendar", "Outlook Calendar"),
    ;

    companion object {
        fun fromWireName(value: String?): CalendarProvider? = entries.firstOrNull { it.wireName == value }
    }
}

/** What a connected calendar looks like to the app: which provider, and whether it has ever synced. */
data class CalendarConnection(
    val provider: CalendarProvider,
    val hasCursor: Boolean,
    val updatedAt: String,
)

/**
 * The result of one sync round.
 *
 * [fullSync] matters to the caller rather than being diagnostic: on an incremental round the app
 * only hears about events that changed, so an event missing from [events] means "unchanged". On a
 * full round the same absence means "no longer exists", and the app has to reconcile deletions it
 * will never be told about explicitly.
 *
 * [events] carries the domain's own [SyncedCalendarChange] rather than a cloud-layer twin, so the
 * shape the parser fills and the shape the planner reads cannot drift apart.
 */
data class CalendarSyncResult(
    val provider: CalendarProvider,
    val fullSync: Boolean,
    val events: List<SyncedCalendarChange>,
)

/**
 * Raised when the provider has revoked Norfold's access — the owner disconnected it on their side,
 * or changed their password. Distinguished from every other failure because it is the one the app
 * must act on by dropping the connection: a refresh token rejected with `invalid_grant` will never
 * work again, so retrying is a loop that cannot recover.
 */
class CalendarAccessRevokedException(provider: CalendarProvider) :
    IllegalStateException("${provider.label} access was revoked")

@Serializable
data class DocumentPresenceState(
    val userId: String,
    val displayName: String,
    val deviceId: String,
    val documentSyncId: String,
    val blockId: String? = null,
    val editing: Boolean = false,
    val color: Long = 0xFF8B5CF6,
    val updatedAt: Long = System.currentTimeMillis(),
)

object NorfoldSupabase {
    private val responseJson = Json { ignoreUnknownKeys = true }
    val clientOrNull: SupabaseClient? by lazy {
        if (!ExternalServiceConfig.capabilities.supabase) return@lazy null
        createSupabaseClient(
            supabaseUrl = ExternalServiceConfig.supabaseUrl,
            supabaseKey = ExternalServiceConfig.supabasePublishableKey,
        ) {
            install(Auth)
            install(Postgrest)
            install(Realtime)
            install(Storage)
            install(Functions)
        }
    }

    val client: SupabaseClient
        get() = clientOrNull ?: error("Supabase is not configured for this build.")

    fun currentAccountEmail(): String? = clientOrNull?.auth?.currentUserOrNull()?.email
    fun currentAccountId(): String? = clientOrNull?.auth?.currentUserOrNull()?.id

    fun isSignedIn(): Boolean = clientOrNull?.auth?.currentUserOrNull() != null

    /**
     * Emits whenever the auth session changes — including the asynchronous restore of a persisted
     * session after process start. Null when Supabase isn't configured for this build.
     */
    fun sessionStatusFlow(): Flow<SessionStatus>? = clientOrNull?.auth?.sessionStatus

    suspend fun signInWithGoogle(idToken: String, nonce: String) {
        ExternalServiceConfig.requireGoogleIdentity()
        client.auth.signInWith(IDToken) {
            this.idToken = idToken
            provider = Google
            this.nonce = nonce
        }
    }

    suspend fun signInWithEmail(email: String, password: String) {
        ExternalServiceConfig.requireSupabase()
        client.auth.signInWith(Email) {
            this.email = email
            this.password = password
        }
    }

    suspend fun signUpWithEmail(email: String, password: String) {
        ExternalServiceConfig.requireSupabase()
        client.auth.signUpWith(Email) {
            this.email = email
            this.password = password
        }
    }

    /** Confirms a signup with the 6-digit code from the "Confirm signup" email and starts the session. */
    suspend fun verifySignupCode(email: String, code: String) {
        ExternalServiceConfig.requireSupabase()
        client.auth.verifyEmailOtp(type = OtpType.Email.SIGNUP, email = email, token = code)
    }

    suspend fun resendSignupCode(email: String) {
        ExternalServiceConfig.requireSupabase()
        client.auth.resendEmail(OtpType.Email.SIGNUP, email)
    }

    suspend fun requestPasswordReset(email: String) {
        ExternalServiceConfig.requireSupabase()
        client.auth.resetPasswordForEmail(email)
    }

    /** Confirms a password reset with the 6-digit code from the "Reset password" email and starts a recovery session. */
    suspend fun verifyRecoveryCode(email: String, code: String) {
        ExternalServiceConfig.requireSupabase()
        client.auth.verifyEmailOtp(type = OtpType.Email.RECOVERY, email = email, token = code)
    }

    suspend fun updatePassword(newPassword: String) {
        ExternalServiceConfig.requireSupabase()
        client.auth.updateUser { password = newPassword }
    }

    suspend fun signOut() {
        clientOrNull?.auth?.signOut()
    }

    suspend fun createTeamWorkspace(name: String): RemoteWorkspace {
        ExternalServiceConfig.requireSupabase()
        require(isSignedIn()) { "Sign in to Norfold Cloud before enabling team collaboration." }
        // create_workspace returns a single composite row, which PostgREST serves as a bare
        // JSON object (not a one-element array) — decodeSingle() would fail on it.
        return client.postgrest.rpc(
            function = "create_workspace",
            parameters = buildJsonObject {
                put("workspace_name", name.trim().ifBlank { "Team workspace" })
                put("privacy_mode", "team_managed")
            },
        ).decodeAs()
    }

    suspend fun workspace(remoteWorkspaceId: String): RemoteWorkspace {
        ExternalServiceConfig.requireSupabase()
        return client.postgrest["workspaces"].select {
            filter { eq("id", remoteWorkspaceId) }
        }.decodeSingle()
    }

    suspend fun createTeamInvitation(
        remoteWorkspaceId: String,
        email: String,
        viewer: Boolean = false,
    ): TeamInvitation {
        ExternalServiceConfig.requireSupabase()
        require(isSignedIn()) { "Sign in to Norfold Cloud before inviting members." }
        val response = client.functions.invoke(
            function = "invitation",
            body = buildJsonObject {
                put("action", "create")
                put("workspaceId", remoteWorkspaceId)
                put("email", email.trim().lowercase())
                put("role", if (viewer) "viewer" else "member")
            },
        )
        val payload = responseJson.parseToJsonElement(response.bodyAsText()).jsonObject
        val invitation = payload["invitation"]?.jsonObject ?: error(payload["error"]?.jsonPrimitive?.contentOrNull ?: "Invitation failed")
        return TeamInvitation(
            id = invitation["id"]?.jsonPrimitive?.contentOrNull ?: error("Invitation response is missing its id."),
            token = payload["token"]?.jsonPrimitive?.contentOrNull ?: error("Invitation response is missing its token."),
            expiresAt = invitation["expires_at"]?.jsonPrimitive?.contentOrNull.orEmpty(),
        )
    }

    suspend fun acceptTeamInvitation(token: String): String {
        ExternalServiceConfig.requireSupabase()
        require(isSignedIn()) { "Sign in to the invited Norfold Cloud account first." }
        val response = client.functions.invoke(
            function = "invitation",
            body = buildJsonObject {
                put("action", "accept")
                put("token", token.trim())
            },
        )
        val payload = responseJson.parseToJsonElement(response.bodyAsText()).jsonObject
        if (payload["accepted"]?.jsonPrimitive?.contentOrNull != "true") {
            error(payload["error"]?.jsonPrimitive?.contentOrNull ?: "Invitation was not accepted.")
        }
        return payload["workspaceId"]?.jsonPrimitive?.contentOrNull
            ?: error("Invitation response is missing its workspace id.")
    }

    suspend fun sendTeamChatMessage(
        remoteWorkspaceId: String,
        deviceId: String,
        username: String,
        displayName: String,
        body: String,
        color: Long,
        createdAt: Long,
        // Callers that may retry a failed send should generate these once and reuse them:
        // the server dedupes on (workspace_id, client_sync_id) and (workspace_id,
        // idempotency_key), so a retry with the same pair can't duplicate the message.
        syncId: String = UUID.randomUUID().toString(),
        idempotencyKey: String = UUID.randomUUID().toString(),
        // When the message carries a file, the storage path lets receiving devices resolve a
        // signed URL — their copy of the sender's local content uri would be meaningless.
        attachmentName: String? = null,
        attachmentMimeType: String? = null,
        attachmentSizeBytes: Long? = null,
        attachmentStoragePath: String? = null,
        attachmentExternalUrl: String? = null,
        replyPreview: String? = null,
        editedAt: Long? = null,
        reactionsJson: String? = null,
        pinned: Boolean? = null,
        baseVersion: Long? = null,
        channelSyncId: String? = null,
    ): RemoteWorkspaceObject {
        ExternalServiceConfig.requireSupabase()
        require(isSignedIn()) { "Sign in to Norfold Cloud before sending team messages." }
        val content = buildJsonObject {
            put("authorUsername", username)
            put("authorDisplayName", displayName)
            put("body", body)
            put("color", color)
            put("createdAt", createdAt)
            if (attachmentName != null) put("attachmentName", attachmentName)
            if (attachmentMimeType != null) put("attachmentMimeType", attachmentMimeType)
            if (attachmentSizeBytes != null) put("attachmentSizeBytes", attachmentSizeBytes)
            if (attachmentStoragePath != null) put("attachmentStoragePath", attachmentStoragePath)
            if (attachmentExternalUrl != null) put("attachmentExternalUrl", attachmentExternalUrl)
            if (replyPreview != null) put("replyPreview", replyPreview)
            if (editedAt != null) put("editedAt", editedAt)
            if (reactionsJson != null) put("reactionsJson", reactionsJson)
            if (pinned != null) put("pinned", pinned)
            if (channelSyncId != null) put("channelSyncId", channelSyncId)
        }
        val response = client.functions.invoke(
            function = "sync-operations",
            body = buildJsonObject {
                put("operations", buildJsonArray {
                    add(buildJsonObject {
                        put("workspaceId", remoteWorkspaceId)
                        put("syncId", syncId)
                        put("objectType", "chat_message")
                        put("operation", "upsert")
                        put("idempotencyKey", idempotencyKey)
                        put("deviceId", deviceId)
                        if (baseVersion != null) put("baseVersion", baseVersion)
                        put("contentHash", sha256(content.toString()))
                        put("content", content)
                    })
                })
            },
        )
        val payload = responseJson.parseToJsonElement(response.bodyAsText()).jsonObject
        val result = payload["results"]?.jsonArray?.firstOrNull()
            ?: error(payload["error"]?.jsonPrimitive?.contentOrNull ?: "Message sync failed")
        return responseJson.decodeFromJsonElement(RemoteWorkspaceObject.serializer(), result)
    }

    /** Creates or updates a channel definition before any message references it. */
    suspend fun upsertTeamChatChannel(
        remoteWorkspaceId: String,
        deviceId: String,
        syncId: String,
        kind: String,
        name: String,
        icon: String,
        createdAt: Long,
        participantUserIds: List<String> = emptyList(),
        participantUsernames: List<String> = emptyList(),
        participantDisplayNames: List<String> = emptyList(),
        baseVersion: Long? = null,
    ): RemoteWorkspaceObject {
        ExternalServiceConfig.requireSupabase()
        require(isSignedIn()) { "Sign in to Norfold Cloud before creating team conversations." }
        val content = buildJsonObject {
            put("kind", kind)
            put("name", name.trim())
            put("icon", icon)
            put("createdAt", createdAt)
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
        )
    }

    /** Returns the one deterministic private channel shared by this account and [otherUserId]. */
    suspend fun ensureDirectChatChannel(remoteWorkspaceId: String, otherUserId: String): String {
        ExternalServiceConfig.requireSupabase()
        require(isSignedIn()) { "Sign in to Norfold Cloud before starting a direct message." }
        return client.postgrest.rpc(
            function = "ensure_direct_chat_channel",
            parameters = buildJsonObject {
                put("p_workspace", remoteWorkspaceId)
                put("p_other_user", otherUserId)
            },
        ).decodeAs()
    }

    suspend fun deleteTeamChatChannel(remoteWorkspaceId: String, channelSyncId: String) {
        ExternalServiceConfig.requireSupabase()
        require(isSignedIn()) { "Sign in to Norfold Cloud before deleting a team conversation." }
        client.postgrest.rpc(
            function = "delete_chat_channel",
            parameters = buildJsonObject {
                put("p_workspace", remoteWorkspaceId)
                put("p_channel", channelSyncId)
            },
        )
    }

    private suspend fun applyWorkspaceObject(
        remoteWorkspaceId: String,
        deviceId: String,
        syncId: String,
        objectType: String,
        content: JsonObject,
        baseVersion: Long? = null,
    ): RemoteWorkspaceObject {
        val response = client.functions.invoke(
            function = "sync-operations",
            body = buildJsonObject {
                put("operations", buildJsonArray {
                    add(buildJsonObject {
                        put("workspaceId", remoteWorkspaceId)
                        put("syncId", syncId)
                        put("objectType", objectType)
                        put("operation", "upsert")
                        put("idempotencyKey", UUID.randomUUID().toString())
                        put("deviceId", deviceId)
                        if (baseVersion != null) put("baseVersion", baseVersion)
                        put("contentHash", sha256(content.toString()))
                        put("content", content)
                    })
                })
            },
        )
        val payload = responseJson.parseToJsonElement(response.bodyAsText()).jsonObject
        val result = payload["results"]?.jsonArray?.firstOrNull()
            ?: error(payload["error"]?.jsonPrimitive?.contentOrNull ?: "Workspace sync failed")
        return responseJson.decodeFromJsonElement(RemoteWorkspaceObject.serializer(), result)
    }

    /**
     * Chat messages for the workspace. Pass [since] (an ISO `created_at` returned by a previous
     * call) to fetch only rows created at or after it — the 4 s chat poll must not refetch the
     * whole table. A null [since] (first load, workspace switch) keeps the full fetch.
     */
    suspend fun teamChatObjects(remoteWorkspaceId: String, since: String? = null): List<RemoteWorkspaceObject> {
        ExternalServiceConfig.requireSupabase()
        if (!isSignedIn()) return emptyList()
        return client.postgrest["workspace_objects"].select {
            filter {
                eq("workspace_id", remoteWorkspaceId)
                isIn("object_type", listOf("chat_channel", "chat_message"))
                // gte, not gt: rows sharing the boundary timestamp are re-fetched and deduped
                // by remote id on import rather than silently skipped.
                if (since != null) gte("created_at", since)
            }
        }.decodeList<RemoteWorkspaceObject>()
            .filter { it.objectType == "chat_channel" || it.objectType == "chat_message" }
    }

    suspend fun teamChatMessages(remoteWorkspaceId: String, since: String? = null): List<RemoteWorkspaceObject> =
        teamChatObjects(remoteWorkspaceId, since).filter { it.objectType == "chat_message" && it.deletedAt == null && it.content != null }

    /**
     * Authenticated live changes for a bound workspace. Postgres Realtime can filter one column,
     * so workspace membership is enforced server-side/RLS and object type is narrowed locally.
     * A caller still performs an initial/incremental pull to close the subscribe race window.
     */
    fun teamChatChanges(remoteWorkspaceId: String, since: String? = null): Flow<RemoteWorkspaceObject> = channelFlow {
        ExternalServiceConfig.requireSupabase()
        require(isSignedIn()) { "Sign in to Norfold Cloud before receiving team messages." }
        val realtime = client.realtime
        val channel = realtime.channel("team-chat:$remoteWorkspaceId") { }
        val changes = channel.postgresChangeFlow<PostgresAction>(schema = "public") {
            table = "workspace_objects"
            filter("workspace_id", FilterOperator.EQ, remoteWorkspaceId)
        }
        val collector = launch {
            changes.collect { action ->
                val remote = when (action) {
                    is PostgresAction.Insert -> action.decodeRecordOrNull<RemoteWorkspaceObject>()
                    is PostgresAction.Update -> action.decodeRecordOrNull<RemoteWorkspaceObject>()
                    else -> null
                }
                // Tombstones flow through too: an unsend elsewhere arrives as an Update with
                // deleted_at set, and the collector removes the local copy.
                if (remote != null && remote.objectType in setOf("chat_channel", "chat_message") && (remote.deletedAt != null || remote.content != null)) send(remote)
            }
        }
        try {
            channel.subscribe(blockUntilSubscribed = true)
            // Subscribe first, then catch up. Events arriving during the query are deduplicated by
            // remote object id when the caller imports them.
            teamChatObjects(remoteWorkspaceId, since)
                .sortedBy { if (it.objectType == "chat_channel") 0 else 1 }
                .forEach { send(it) }
            collector.join()
        } finally {
            collector.cancel()
            realtime.removeChannel(channel)
        }
    }

    /**
     * Unsend: tombstones a chat message on the server (sync-operations "delete" → deleted_at).
     * Other devices see the tombstone as a realtime Update and drop their local copy; catch-up
     * fetches already filter tombstoned rows out. The 1-hour/own-message window is enforced by
     * the caller — the server only checks workspace membership.
     */
    suspend fun deleteTeamChatMessage(remoteWorkspaceId: String, syncId: String, deviceId: String) {
        ExternalServiceConfig.requireSupabase()
        require(isSignedIn()) { "Sign in to Norfold Cloud before unsending messages." }
        val response = client.functions.invoke(
            function = "sync-operations",
            body = buildJsonObject {
                put("operations", buildJsonArray {
                    add(buildJsonObject {
                        put("workspaceId", remoteWorkspaceId)
                        put("syncId", syncId)
                        put("objectType", "chat_message")
                        put("operation", "delete")
                        put("idempotencyKey", UUID.randomUUID().toString())
                        put("deviceId", deviceId)
                        put("contentHash", sha256("deleted:$syncId"))
                    })
                })
            },
        )
        val payload = responseJson.parseToJsonElement(response.bodyAsText()).jsonObject
        if (payload["results"] == null) {
            error(payload["error"]?.jsonPrimitive?.contentOrNull ?: "Unsend failed")
        }
    }

    suspend fun upsertSharedDocument(
        remoteWorkspaceId: String,
        documentSyncId: String,
        deviceId: String,
        content: JsonObject,
        baseVersion: Long?,
    ): RemoteWorkspaceObject {
        ExternalServiceConfig.requireSupabase()
        require(isSignedIn()) { "Sign in to Norfold Cloud before sharing documents." }
        require(runCatching { UUID.fromString(remoteWorkspaceId) }.isSuccess) { "Remote workspace id must be a UUID." }
        require(runCatching { UUID.fromString(documentSyncId) }.isSuccess) { "Document sync id must be a UUID." }
        require(runCatching { UUID.fromString(deviceId) }.isSuccess) { "Device id must be a UUID." }
        val response = client.functions.invoke(
            function = "sync-operations",
            body = buildJsonObject {
                put("operations", buildJsonArray {
                    add(buildJsonObject {
                        put("workspaceId", remoteWorkspaceId)
                        put("syncId", documentSyncId)
                        put("objectType", SHARED_DOCUMENT_TYPE)
                        put("operation", "upsert")
                        put("idempotencyKey", UUID.randomUUID().toString())
                        put("deviceId", deviceId)
                        baseVersion?.let { put("baseVersion", it) }
                        put("contentHash", sha256(content.toString()))
                        put("content", content)
                    })
                })
            },
        )
        val payload = responseJson.parseToJsonElement(response.bodyAsText()).jsonObject
        val result = payload["results"]?.jsonArray?.firstOrNull()
            ?: error(payload["error"]?.jsonPrimitive?.contentOrNull ?: "Document sync failed")
        return responseJson.decodeFromJsonElement(RemoteWorkspaceObject.serializer(), result)
    }

    /**
     * Asks the server to erase the editor generation it still holds for this workspace.
     *
     * Authorized by the signed-in user's own token: the RPC is `security definer` so it can perform
     * the deletes, but its gate is `has_workspace_role` under `auth.uid()`, so no privileged
     * credential is needed here and none exists on the device. Errors are classified rather than
     * thrown, because the caller's only question is whether to ask again.
     */
    suspend fun resetWorkspaceEditorContent(remoteWorkspaceId: String, epoch: Int): RemoteEditorResetOutcome {
        if (!ExternalServiceConfig.capabilities.supabase) return RemoteEditorResetOutcome.Retryable("cloud_not_configured")
        if (!isSignedIn()) return RemoteEditorResetOutcome.Retryable("signed_out")
        if (runCatching { UUID.fromString(remoteWorkspaceId) }.isFailure) {
            return RemoteEditorResetOutcome.Refused("invalid_workspace")
        }
        return try {
            val row = client.postgrest.rpc(
                function = "reset_workspace_editor_content",
                parameters = buildJsonObject {
                    put("p_workspace", remoteWorkspaceId)
                    put("p_epoch", epoch)
                },
            ).decodeAs<JsonObject>()
            if (row["already_applied"]?.jsonPrimitive?.booleanOrNull == true) {
                RemoteEditorResetOutcome.AlreadyApplied
            } else {
                RemoteEditorResetOutcome.Applied(row["deleted"]?.jsonPrimitive?.intOrNull ?: 0)
            }
        } catch (error: Throwable) {
            classifyRemoteEditorResetFailure(error.message)
        }
    }

    suspend fun sharedDocuments(remoteWorkspaceId: String, documentSyncId: String? = null): List<RemoteWorkspaceObject> {
        ExternalServiceConfig.requireSupabase()
        if (!isSignedIn()) return emptyList()
        return client.postgrest["workspace_objects"].select {
            filter {
                eq("workspace_id", remoteWorkspaceId)
                eq("object_type", SHARED_DOCUMENT_TYPE)
                documentSyncId?.let { eq("client_sync_id", it) }
            }
        }.decodeList<RemoteWorkspaceObject>()
            .filter { it.deletedAt == null && it.content != null }
    }

    fun sharedDocumentChanges(remoteWorkspaceId: String, documentSyncId: String): Flow<RemoteWorkspaceObject> = channelFlow {
        ExternalServiceConfig.requireSupabase()
        require(isSignedIn()) { "Sign in to Norfold Cloud before receiving shared documents." }
        val realtime = client.realtime
        val channel = realtime.channel("shared-document:$remoteWorkspaceId:$documentSyncId") { }
        val changes = channel.postgresChangeFlow<PostgresAction>(schema = "public") {
            table = "workspace_objects"
            filter("workspace_id", FilterOperator.EQ, remoteWorkspaceId)
        }
        val collector = launch {
            changes.collect { action ->
                val remote = when (action) {
                    is PostgresAction.Insert -> action.decodeRecordOrNull<RemoteWorkspaceObject>()
                    is PostgresAction.Update -> action.decodeRecordOrNull<RemoteWorkspaceObject>()
                    else -> null
                }
                if (
                    remote?.objectType == SHARED_DOCUMENT_TYPE &&
                    remote.clientSyncId == documentSyncId &&
                    remote.deletedAt == null && remote.content != null
                ) send(remote)
            }
        }
        try {
            channel.subscribe(blockUntilSubscribed = true)
            sharedDocuments(remoteWorkspaceId, documentSyncId).forEach { send(it) }
            collector.join()
        } finally {
            collector.cancel()
            realtime.removeChannel(channel)
        }
    }

    /**
     * Slow-changing document presence. Block changes are intentionally coalesced for 6.5 seconds:
     * Supabase Presence is for online/active-document state, not cursor telemetry, and its public
     * service limits each client to five presence calls per 30 seconds.
     */
    fun documentPresence(
        remoteWorkspaceId: String,
        documentSyncId: String,
        initial: DocumentPresenceState,
        updates: Flow<DocumentPresenceState>,
    ): Flow<List<DocumentPresenceState>> = channelFlow {
        ExternalServiceConfig.requireSupabase()
        require(isSignedIn()) { "Sign in to Norfold Cloud before joining document presence." }
        require(runCatching { UUID.fromString(remoteWorkspaceId) }.isSuccess) { "Remote workspace id must be a UUID." }
        require(runCatching { UUID.fromString(documentSyncId) }.isSuccess) { "Document sync id must be a UUID." }
        val realtime = client.realtime
        val channel = realtime.channel("document:$remoteWorkspaceId:$documentSyncId") {
            presence { key = "${initial.userId}:${initial.deviceId}" }
        }
        val presenceCollector = launch {
            channel.presenceDataFlow<DocumentPresenceState>().collect { states ->
                send(
                    states
                        .filter { it.documentSyncId == documentSyncId }
                        .distinctBy { "${it.userId}:${it.deviceId}" }
                        .sortedWith(compareByDescending<DocumentPresenceState> { it.editing }.thenByDescending { it.updatedAt }),
                )
            }
        }
        val updateCollector = launch {
            updates.distinctUntilChanged { old, new ->
                old.blockId == new.blockId && old.editing == new.editing && old.displayName == new.displayName
            }.collectLatest { next ->
                delay(6_500)
                channel.track(next.copy(updatedAt = System.currentTimeMillis()))
            }
        }
        try {
            channel.subscribe(blockUntilSubscribed = true)
            channel.track(initial.copy(updatedAt = System.currentTimeMillis()))
            presenceCollector.join()
        } finally {
            updateCollector.cancel()
            presenceCollector.cancel()
            runCatching { channel.untrack() }
            realtime.removeChannel(channel)
        }
    }

    /**
     * Uploads a team-shared file to the private workspace-files bucket. RLS keys access off the
     * first path segment (workspace id), so members can read it and owners/admins can delete it.
     * Returns the storage path and registers the file in public.files for the team file list.
     */
    suspend fun uploadWorkspaceFile(
        remoteWorkspaceId: String,
        displayName: String,
        mimeType: String,
        bytes: ByteArray,
    ): String {
        ExternalServiceConfig.requireSupabase()
        require(isSignedIn()) { "Sign in to Norfold Cloud before sharing team files." }
        val safeName = displayName.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "file" }
        // Content-addressed path: a retry of the same bytes overwrites the same object instead
        // of orphaning a fresh UUID-named copy when the files-table insert below fails.
        val contentHash = sha256Bytes(bytes)
        val storagePath = "$remoteWorkspaceId/$contentHash-$safeName"
        client.storage.from("workspace-files").upload(storagePath, bytes) {
            upsert = true
            contentType = ContentType.parse(mimeType.ifBlank { "application/octet-stream" })
        }
        try {
            client.postgrest["files"].insert(
                buildJsonObject {
                    put("workspace_id", remoteWorkspaceId)
                    put("storage_path", storagePath)
                    put("display_name", displayName.ifBlank { safeName })
                    put("mime_type", mimeType.ifBlank { "application/octet-stream" })
                    put("size_bytes", bytes.size.toLong())
                    put("content_hash", contentHash)
                    put("encrypted", false)
                    put("created_by", requireNotNull(client.auth.currentUserOrNull()).id)
                },
            )
        } catch (insertFailure: Exception) {
            // Best-effort cleanup so the bucket doesn't keep an object no files row points at;
            // the deterministic path means a retry reclaims it even if this delete also fails.
            runCatching { client.storage.from("workspace-files").delete(storagePath) }
            throw insertFailure
        }
        return storagePath
    }

    /** Time-limited download URL for a file in the private workspace-files bucket. */
    suspend fun workspaceFileUrl(storagePath: String, expiresInSeconds: Long = 60L * 60L * 24L * 7L): String {
        ExternalServiceConfig.requireSupabase()
        require(isSignedIn()) { "Sign in to Norfold Cloud to open team files." }
        return client.storage.from("workspace-files")
            .createSignedUrl(storagePath, expiresInSeconds.seconds)
    }

    /** Returns true if the handle is not yet taken (or we're offline — optimistic). */
    suspend fun isHandleAvailable(handle: String): Boolean {
        val configuredClient = clientOrNull ?: return true
        return runCatching {
            configuredClient.postgrest["profiles"]
                .select(Columns.raw("handle")) { filter { eq("handle", handle) } }
                .decodeList<kotlinx.serialization.json.JsonObject>()
                .isEmpty()
        }.getOrDefault(true)
    }

    /** Returns false when the app is operating locally without an authenticated cloud account. */
    suspend fun claimProfileHandleIfSignedIn(handle: String, displayName: String): Boolean {
        val configuredClient = clientOrNull ?: return false
        if (configuredClient.auth.currentUserOrNull() == null) return false
        configuredClient.postgrest.rpc(
            function = "claim_profile_handle",
            parameters = buildJsonObject {
                put("p_handle", handle)
                put("p_display_name", displayName)
            },
        )
        return true
    }

    /**
     * Writes the signed-in user's avatar storage path to their profile so peers can render it.
     * Handle/display name are claimed separately via [claimProfileHandleIfSignedIn]; this only
     * touches avatar_path, which the roster fetch reads. No-op when signed out.
     */
    suspend fun updateProfileAvatarIfSignedIn(avatarPath: String?): Boolean {
        val configuredClient = clientOrNull ?: return false
        val userId = configuredClient.auth.currentUserOrNull()?.id ?: return false
        configuredClient.postgrest["profiles"].update(
            buildJsonObject { put("avatar_path", avatarPath) },
        ) {
            filter { eq("id", userId) }
        }
        return true
    }

    /**
     * The workspace roster: every current member joined with their profile. Read-side RLS
     * (memberships_member_read + profiles_member_read) scopes this to workspaces the caller
     * belongs to. Returns empty when signed out. removed_at rows are excluded server-side by the
     * embedded filter below.
     */
    suspend fun workspaceMembers(remoteWorkspaceId: String): List<RemoteWorkspaceMember> {
        ExternalServiceConfig.requireSupabase()
        if (!isSignedIn()) return emptyList()
        return client.postgrest["workspace_memberships"].select(
            Columns.raw("user_id, role, joined_at, removed_at, profiles!inner(id, handle, display_name, avatar_path)"),
        ) {
            filter { eq("workspace_id", remoteWorkspaceId) }
        }.decodeList<RemoteWorkspaceMember>()
            // Removed members are soft-deleted; exclude them client-side to avoid depending on a
            // server-side IS NOT NULL filter signature.
            .filter { it.removedAt == null && it.profile != null }
    }

    /**
     * Telegram phone verification. Starts a server-side session and returns the t.me deep link
     * the app opens. The bot token and webhook secret live only in Supabase function secrets —
     * never on the device. The raw start token is generated server-side and never persisted.
     */
    suspend fun startTelegramVerification(): TelegramVerificationStart {
        ExternalServiceConfig.requireSupabase()
        require(isSignedIn()) { "Sign in before verifying your phone." }
        val response = client.functions.invoke(
            function = "telegram-verification-start",
            body = buildJsonObject { },
        )
        val payload = responseJson.parseToJsonElement(response.bodyAsText()).jsonObject
        val url = payload["telegramUrl"]?.jsonPrimitive?.contentOrNull
            ?: error(payload["error"]?.jsonPrimitive?.contentOrNull ?: "Could not start Telegram verification.")
        return TelegramVerificationStart(
            verificationId = payload["verificationId"]?.jsonPrimitive?.contentOrNull
                ?: error("Verification response is missing its id."),
            telegramUrl = url,
            expiresAt = payload["expiresAt"]?.jsonPrimitive?.contentOrNull.orEmpty(),
        )
    }

    /**
     * The current verification session status ("PENDING" | "STARTED" | "VERIFIED" | "EXPIRED" |
     * "CANCELLED"), read straight from the row the caller owns (RLS: self-read only). The app
     * NEVER treats the return deep link as proof — it always re-reads this before showing verified.
     */
    suspend fun telegramVerificationStatus(verificationId: String): String? {
        ExternalServiceConfig.requireSupabase()
        if (!isSignedIn()) return null
        return client.postgrest["telegram_verification_sessions"]
            .select(Columns.raw("status")) { filter { eq("id", verificationId) } }
            .decodeList<JsonObject>()
            .firstOrNull()
            ?.get("status")?.jsonPrimitive?.contentOrNull
    }

    /** The caller's verified phone in E.164, or null if none is verified yet. */
    suspend fun verifiedPhoneE164(): String? {
        ExternalServiceConfig.requireSupabase()
        val userId = clientOrNull?.auth?.currentUserOrNull()?.id ?: return null
        return client.postgrest["account_phone_identities"]
            .select(Columns.raw("phone_e164")) { filter { eq("user_id", userId) } }
            .decodeList<JsonObject>()
            .firstOrNull()
            ?.get("phone_e164")?.jsonPrimitive?.contentOrNull
    }

    /**
     * Starts a calendar connection and returns the provider URL to open in a browser.
     *
     * The URL is built server-side rather than here, and the reason is the `state` parameter: it is
     * signed with a function secret so that the user id the callback stores the token against comes
     * from a validated JWT. An app that could sign its own state could name any account.
     *
     * The returned URL is opened, the provider redirects to `calendar-callback`, and that stores the
     * encrypted refresh token and redirects to `norfold://integrations/calendar`. The app is not
     * told the outcome by the link — it re-reads [calendarConnections], for the same reason
     * [telegramVerificationStatus] exists.
     */
    suspend fun startCalendarAuthorization(provider: CalendarProvider): String {
        ExternalServiceConfig.requireSupabase()
        require(isSignedIn()) { "Sign in to Norfold Cloud before connecting a calendar." }
        val response = client.functions.invoke(
            function = "calendar-authorize",
            body = buildJsonObject { put("provider", provider.wireName) },
        )
        val payload = responseJson.parseToJsonElement(response.bodyAsText()).jsonObject
        return payload["authorizationUrl"]?.jsonPrimitive?.contentOrNull
            ?: error(payload["error"]?.jsonPrimitive?.contentOrNull ?: "Could not start the calendar connection.")
    }

    /**
     * The calendars currently connected to this account.
     *
     * Read directly through RLS (`integrations_self_all` is self-only) rather than through a
     * function, because there is nothing to decide: the row either exists or it does not. Revoked
     * rows are dropped so a disconnected calendar stops reporting as connected.
     */
    suspend fun calendarConnections(): List<CalendarConnection> {
        ExternalServiceConfig.requireSupabase()
        if (!isSignedIn()) return emptyList()
        return client.postgrest["integration_accounts"]
            .select(Columns.raw("provider, cursor, revoked_at, updated_at"))
            .decodeList<JsonObject>()
            // Revoked rows are excluded here rather than in the query, matching how the roster read
            // handles soft deletion: it avoids depending on a server-side IS NULL filter signature.
            // An unset column arrives as JsonNull, whose `contentOrNull` is null — reading it as a
            // primitive is safe, unwrapping it with `!!` would not be.
            .filter { (it["revoked_at"] as? JsonPrimitive)?.contentOrNull.isNullOrBlank() }
            .mapNotNull { row ->
                val provider = CalendarProvider.fromWireName(row["provider"]?.jsonPrimitive?.contentOrNull)
                    ?: return@mapNotNull null
                CalendarConnection(
                    provider = provider,
                    // A cursor exists only after a completed round, so this is "has synced at least
                    // once" without needing a second column to track it.
                    hasCursor = !(row["cursor"] as? JsonPrimitive)?.contentOrNull.isNullOrBlank(),
                    updatedAt = row["updated_at"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                )
            }
    }

    /**
     * Pulls one round of changes for a connected calendar.
     *
     * The provider conversation happens in the function, not here, and not for tidiness: the refresh
     * token is encrypted with a key that exists only in the function environment. A device that
     * could decrypt it would be a device holding standing access to the owner's entire calendar.
     *
     * Throws [CalendarAccessRevokedException] when the provider has rejected the stored token for
     * good, so the caller can drop the connection instead of retrying forever.
     */
    suspend fun syncCalendar(provider: CalendarProvider): CalendarSyncResult {
        ExternalServiceConfig.requireSupabase()
        require(isSignedIn()) { "Sign in to Norfold Cloud before syncing a calendar." }
        val response = client.functions.invoke(
            function = "calendar-sync",
            body = buildJsonObject { put("provider", provider.wireName) },
        )
        val payload = responseJson.parseToJsonElement(response.bodyAsText()).jsonObject
        val events = payload["events"]?.jsonArray
        if (events == null) {
            if (payload["error"]?.jsonPrimitive?.contentOrNull == "revoked") throw CalendarAccessRevokedException(provider)
            error(payload["error"]?.jsonPrimitive?.contentOrNull ?: "Could not sync ${provider.label}.")
        }
        return CalendarSyncResult(
            provider = provider,
            fullSync = payload["fullSync"]?.jsonPrimitive?.booleanOrNull ?: false,
            events = events.mapNotNull { element ->
                val event = element.jsonObject
                val externalId = event["externalId"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                    ?: return@mapNotNull null
                val deleted = event["deleted"]?.jsonPrimitive?.booleanOrNull ?: false
                val startAt = event["startAt"]?.jsonPrimitive?.longOrNull ?: 0L
                val endAt = event["endAt"]?.jsonPrimitive?.longOrNull ?: startAt
                // A live event with no start is not importable — it would land at the epoch and
                // appear in January 1970. A deletion legitimately has no times at all.
                if (!deleted && startAt <= 0L) return@mapNotNull null
                SyncedCalendarChange(
                    externalId = externalId,
                    title = event["title"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    description = event["description"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    startAt = startAt,
                    // The server already clamps an exclusive all-day end; this only guards against a
                    // reversed pair, which createCalendarEvent rejects outright.
                    endAt = if (endAt < startAt) startAt else endAt,
                    allDay = event["allDay"]?.jsonPrimitive?.booleanOrNull ?: false,
                    deleted = deleted,
                )
            },
        )
    }

    /**
     * Marks a calendar connection revoked.
     *
     * A soft revoke rather than a delete: `integration_accounts` is the row the callback upserts on
     * `(user_id, provider, provider_subject)`, and `revoked_at` is the column both this and the
     * server already read. Deleting instead would lose the provider_subject, so reconnecting the
     * same calendar would create a second row rather than reusing the first. The cursor is cleared
     * so a later reconnect starts from a full sync instead of resuming a token the provider has
     * already forgotten.
     */
    suspend fun disconnectCalendar(provider: CalendarProvider) {
        ExternalServiceConfig.requireSupabase()
        require(isSignedIn()) { "Sign in to Norfold Cloud first." }
        // One reading of the clock, so the two timestamps cannot land a millisecond apart and make
        // the row look as though it was touched after it was revoked.
        val now = java.time.Instant.now().toString()
        client.postgrest["integration_accounts"].update(
            buildJsonObject {
                put("revoked_at", now)
                put("cursor", JsonNull)
                put("updated_at", now)
            },
        ) {
            filter { eq("provider", provider.wireName) }
        }
    }

    private fun sha256(value: String): String = sha256Bytes(value.toByteArray(Charsets.UTF_8))
    private fun sha256Bytes(value: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(value)
        .joinToString("") { byte -> "%02x".format(byte) }

    private const val SHARED_DOCUMENT_TYPE = "shared_document"
}
