package com.norfold.app.cloud

import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.realtime.channel
import io.github.jan.supabase.realtime.presenceDataFlow
import io.github.jan.supabase.realtime.realtime
import io.github.jan.supabase.realtime.track
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Workspace-level presence + 3-stage message receipts (chat overhaul Phase 3).
 *
 * Presence is a Supabase Realtime presence channel per workspace — ephemeral
 * by design; leaving the socket removes you from the roster. "Away" is claimed
 * by the client when the app backgrounds (a grace tick keeps brief app switches
 * from flapping); "offline" is simply absence from the channel.
 *
 * Receipts are durable rows (message_receipts): sent → delivered → viewed,
 * forward-only. Delivery is recorded on import; viewed on conversation open.
 * Backed by supabase/migrations/202607200002_presence_receipts.sql.
 */
object NorfoldPresence {

    enum class Availability { Online, Away }

    @Serializable
    data class WorkspacePresenceState(
        val userId: String,
        val username: String,
        val displayName: String,
        val deviceId: String,
        val availability: String = "online",
        val typing: Boolean = false,
        val typingChannelId: String? = null,
        val typingUntil: Long = 0L,
        val updatedAt: Long = System.currentTimeMillis(),
    )

    @Serializable
    data class ReceiptSummary(
        @SerialName("message_sync_id") val messageSyncId: String,
        @SerialName("delivered_count") val deliveredCount: Long = 0,
        @SerialName("viewed_count") val viewedCount: Long = 0,
    )

    private val client get() = NorfoldSupabase.client

    /**
     * Joins the workspace presence channel and emits the deduped member roster
     * on every join/leave/update. [availabilityUpdates] lets the caller flip
     * online/away as the app moves between foreground and background; updates
     * are debounced 3 s so quick app switches don't flap the roster.
     */
    fun workspacePresence(
        remoteWorkspaceId: String,
        initial: WorkspacePresenceState,
        availabilityUpdates: Flow<Availability>,
        typingChannelUpdates: Flow<String?>,
    ): Flow<List<WorkspacePresenceState>> = presenceChannel(
        topic = "workspace-presence:$remoteWorkspaceId",
        initial = initial,
        availabilityUpdates = availabilityUpdates,
        typingChannelUpdates = typingChannelUpdates,
    )

    /** Account-scoped presence for a personal DM/group, independent of workspace selection. */
    fun accountConversationPresence(
        conversationId: String,
        initial: WorkspacePresenceState,
        availabilityUpdates: Flow<Availability>,
        typingUpdates: Flow<String?>,
    ): Flow<List<WorkspacePresenceState>> = presenceChannel(
        topic = "account-presence:$conversationId",
        initial = initial,
        availabilityUpdates = availabilityUpdates,
        typingChannelUpdates = typingUpdates,
    )

    private fun presenceChannel(
        topic: String,
        initial: WorkspacePresenceState,
        availabilityUpdates: Flow<Availability>,
        typingChannelUpdates: Flow<String?>,
    ): Flow<List<WorkspacePresenceState>> = channelFlow {
        ExternalServiceConfig.requireSupabase()
        require(NorfoldSupabase.isSignedIn()) { "Sign in to Norfold Cloud before joining presence." }
        val realtime = client.realtime
        val channel = realtime.channel(topic) {
            presence { key = "${initial.userId}:${initial.deviceId}" }
        }
        val rosterCollector = launch {
            channel.presenceDataFlow<WorkspacePresenceState>().collect { states ->
                send(
                    states
                        // One row per person: their freshest device wins, and any online
                        // device outranks an away one.
                        .groupBy { it.userId }
                        .map { (_, sessions) ->
                            sessions.maxWith(
                                compareBy<WorkspacePresenceState> { it.availability == "online" }
                                    .thenBy { it.updatedAt },
                            )
                        }
                        .sortedBy { it.displayName.ifBlank { it.username }.lowercase() },
                )
            }
        }
        var availabilityCollector: Job? = null
        var typingCollector: Job? = null
        var currentAvailability = Availability.Online
        var typingChannelId: String? = null
        suspend fun trackCurrentState() {
            val now = System.currentTimeMillis()
            channel.track(
                initial.copy(
                    availability = if (currentAvailability == Availability.Away) "away" else "online",
                    typing = typingChannelId != null,
                    typingChannelId = typingChannelId,
                    typingUntil = if (typingChannelId != null) now + 4_000L else 0L,
                    updatedAt = now,
                ),
            )
        }
        try {
            channel.subscribe(blockUntilSubscribed = true)
            trackCurrentState()
            availabilityCollector = launch {
                availabilityUpdates.distinctUntilChanged().collect { next ->
                    currentAvailability = next
                    // Avoid presence flapping on a very short app switch, while typing remains
                    // latency-sensitive and is tracked immediately by the collector below.
                    if (next == Availability.Away) delay(1_500)
                    trackCurrentState()
                }
            }
            typingCollector = launch {
                typingChannelUpdates.distinctUntilChanged().collect { next ->
                    typingChannelId = next
                    trackCurrentState()
                }
            }
            rosterCollector.join()
        } finally {
            availabilityCollector?.cancel()
            typingCollector?.cancel()
            rosterCollector.cancel()
            runCatching { channel.untrack() }
            realtime.removeChannel(channel)
        }
    }

    /**
     * Records delivered/viewed receipts for a batch of message sync ids.
     * Forward-only server-side: marking viewed upgrades delivered rows and
     * never regresses. Quiet no-op on empty input.
     */
    suspend fun recordReceipts(
        remoteWorkspaceId: String,
        messageSyncIds: List<String>,
        viewed: Boolean,
    ) {
        if (messageSyncIds.isEmpty()) return
        ExternalServiceConfig.requireSupabase()
        if (!NorfoldSupabase.isSignedIn()) return
        client.postgrest.rpc(
            function = "record_message_receipts",
            parameters = buildJsonObject {
                put("p_workspace", remoteWorkspaceId)
                put("p_message_ids", buildJsonArray { messageSyncIds.forEach { add(JsonPrimitive(it)) } })
                put("p_status", if (viewed) "viewed" else "delivered")
            },
        )
    }

    /** Aggregate delivered/viewed counts for the sender's tick rendering. */
    suspend fun receiptSummaries(
        remoteWorkspaceId: String,
        messageSyncIds: List<String>,
    ): List<ReceiptSummary> {
        if (messageSyncIds.isEmpty()) return emptyList()
        ExternalServiceConfig.requireSupabase()
        if (!NorfoldSupabase.isSignedIn()) return emptyList()
        return client.postgrest.rpc(
            function = "message_receipt_summary",
            parameters = buildJsonObject {
                put("p_workspace", remoteWorkspaceId)
                put("p_message_ids", buildJsonArray { messageSyncIds.forEach { add(JsonPrimitive(it)) } })
            },
        ).decodeList()
    }
}
