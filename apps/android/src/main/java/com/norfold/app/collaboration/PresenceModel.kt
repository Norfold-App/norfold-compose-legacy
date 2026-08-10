package com.norfold.app.collaboration

import java.util.LinkedHashMap

enum class PresencePrecision { COARSE, PRECISE }

data class SemanticSelectionAnchor(
    val artifactId: String,
    val targetId: String,
    val startAnchor: String? = null,
    val endAnchor: String? = null,
) {
    init {
        require(artifactId.isNotBlank() && artifactId.length <= 256)
        require(targetId.isNotBlank() && targetId.length <= 256)
        require((startAnchor?.length ?: 0) <= 256 && (endAnchor?.length ?: 0) <= 256)
    }
}

data class RemotePresence(
    val actorId: ActorId,
    val deviceId: DeviceId,
    val sessionId: String,
    val displayName: String,
    val anchor: SemanticSelectionAnchor?,
    val precision: PresencePrecision,
    val updatedAt: Long,
) {
    init {
        require(sessionId.isNotBlank() && sessionId.length <= 128)
        require(displayName.isNotBlank() && displayName.length <= 128)
    }
    val key: String get() = "${actorId.value}|${deviceId.value}|$sessionId"
    fun accessibleDescription(): String = when {
        anchor == null || precision == PresencePrecision.COARSE -> "$displayName is viewing this artifact"
        else -> "$displayName is editing ${anchor.targetId}"
    }
}

/** Ephemeral and bounded; this state is never exposed as artifact persistence or local selection. */
class RemotePresenceStore(
    private val expectedArtifactId: String? = null,
    private val staleAfterMs: Long = 30_000,
    private val maxFutureSkewMs: Long = 5_000,
    private val maxSessions: Int = 64,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val sessions = object : LinkedHashMap<String, RemotePresence>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, RemotePresence>?): Boolean = size > maxSessions
    }

    @Synchronized fun update(presence: RemotePresence) {
        val now = clock()
        if (presence.updatedAt < 0 || (presence.updatedAt > now && presence.updatedAt - now > maxFutureSkewMs)) return
        val anchor = presence.anchor
        if (expectedArtifactId != null && anchor != null && anchor.artifactId != expectedArtifactId) return
        val current = sessions[presence.key]
        if (current == null || presence.updatedAt >= current.updatedAt) sessions[presence.key] = presence
    }
    @Synchronized fun remove(actor: ActorId, device: DeviceId, sessionId: String) {
        sessions.remove("${actor.value}|${device.value}|$sessionId")
    }
    @Synchronized fun active(now: Long = clock()): List<RemotePresence> {
        sessions.entries.removeAll { now - it.value.updatedAt > staleAfterMs }
        return sessions.values.sortedWith(compareBy(RemotePresence::displayName, RemotePresence::sessionId))
    }
    @Synchronized fun clear() = sessions.clear()
}

data class CollaborationAccessibilityStatus(
    val stateDescription: String,
    val liveAnnouncement: String? = null,
    val retryAvailable: Boolean = false,
    val unsentChanges: Int = 0,
)

object CollaborationAccessibilityPresenter {
    fun present(health: CollaborationHealth, unsent: Int, collaborators: List<RemotePresence>): CollaborationAccessibilityStatus {
        val peers = when (collaborators.size) {
            0 -> "No other collaborators"
            1 -> "1 collaborator present"
            else -> "${collaborators.size} collaborators present"
        }
        val pending = if (unsent == 0) "All changes sent" else "$unsent unsent changes"
        val network = when (health) {
            CollaborationHealth.OFFLINE -> "Offline"
            CollaborationHealth.CONNECTING -> "Connecting"
            CollaborationHealth.CONNECTED -> "Connected"
            CollaborationHealth.RECONNECTING -> "Reconnecting"
            CollaborationHealth.AUTHORIZATION_DENIED -> "Permission denied"
            CollaborationHealth.PROTOCOL_INCOMPATIBLE -> "App upgrade required"
            CollaborationHealth.OPERATION_REJECTED -> "A change was rejected"
            CollaborationHealth.OUTBOX_BLOCKED -> "Pending change storage is full"
            CollaborationHealth.TRANSPORT_UNAVAILABLE -> "Collaboration service unavailable"
            CollaborationHealth.MESSAGE_AUTHENTICITY_FAILED -> "A collaboration message could not be verified"
            CollaborationHealth.ASSET_MISSING -> "A shared asset is unavailable on this device"
            CollaborationHealth.MERGE_CONFLICT -> "A change needs review"
        }
        return CollaborationAccessibilityStatus(
            stateDescription = "$network. $pending. $peers.",
            liveAnnouncement = if (health == CollaborationHealth.CONNECTED) null else "$network. $pending.",
            retryAvailable = health in setOf(
                CollaborationHealth.OFFLINE,
                CollaborationHealth.RECONNECTING,
                CollaborationHealth.TRANSPORT_UNAVAILABLE,
            ),
            unsentChanges = unsent,
        )
    }
}
