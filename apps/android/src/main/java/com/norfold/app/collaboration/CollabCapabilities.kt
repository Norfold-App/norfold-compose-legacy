package com.norfold.app.collaboration

/**
 * Collaboration capability descriptor. C9 (commercial model) is intentionally OPEN and must not
 * shape the engine architecture. There are NO scattered isPremium checks — the engine reads only
 * this capability object, and free/paid/funded policy is decided later by whatever fills it in.
 */
data class CollabCapabilities(
    val enabled: Boolean = true,
    val maxParticipants: Int = 4,
    val realtimeProfile: String = "standard",
    val liveCursors: Boolean = true,
    val comments: Boolean = false,
    val suggestionMode: Boolean = false,
    val versionHistoryDays: Int = 0,
) {
    companion object {
        /** Beta default: 4-person cap, cursors on, no comments/suggestions yet. Not hardcoded into the engine. */
        val BETA = CollabCapabilities()

        val DISABLED = CollabCapabilities(enabled = false, maxParticipants = 1, liveCursors = false)
    }
}

/** Supplies capabilities for a given artifact/actor. Implementation decides policy later. */
fun interface CollabCapabilityProvider {
    suspend fun capabilitiesFor(roomId: RoomId, actorId: ActorId): CollabCapabilities
}
