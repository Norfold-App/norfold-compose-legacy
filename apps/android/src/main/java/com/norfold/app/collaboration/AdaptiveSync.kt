package com.norfold.app.collaboration

/**
 * Contention-Aware Adaptive Sync modes. LOCKED per handoff — the set of modes is fixed;
 * only the exact rates/thresholds are benchmark-tuned (see [AdaptiveSyncProfile]).
 */
enum class CollabMode {
    /** Nobody else is present. No ephemeral preview traffic needs to be sent. */
    SOLO,

    /** Others present in the artifact but working on different regions/pages. Coarse awareness. */
    SHARED_ARTIFACT,

    /** Another actor is active in the same region/page as the local actor. */
    SHARED_REGION,

    /** Another actor is editing the SAME target object as the local actor. Highest QoS. */
    CONTENDED_TARGET,

    /** Weak device or weak network. Reduce preview richness/rate first; never drop durable ops. */
    DEGRADED,
}

/**
 * Per-mode synchronization profile. All values are benchmark hypotheses (see BENCHMARK_PROTOCOL),
 * NOT production constants. They are tuned from measurements and can be overridden at runtime.
 *
 * @param previewHz target send rate for EPHEMERAL_LATEST previews while the actor is moving
 * @param textOpBatchWindowMs coalescing window for batching local text ops before send
 * @param sendPreviews whether ephemeral previews are emitted at all in this mode
 */
data class AdaptiveSyncProfile(
    val mode: CollabMode,
    val previewHz: Int,
    val textOpBatchWindowMs: Long,
    val sendPreviews: Boolean,
) {
    val previewIntervalMs: Long get() = if (previewHz <= 0) Long.MAX_VALUE else (1000L / previewHz)

    companion object {
        /** Benchmark-hypothesis defaults from BENCHMARK_PROTOCOL.md §Adaptive QoS. */
        fun default(mode: CollabMode): AdaptiveSyncProfile = when (mode) {
            CollabMode.SOLO ->
                AdaptiveSyncProfile(mode, previewHz = 0, textOpBatchWindowMs = 120, sendPreviews = false)
            CollabMode.SHARED_ARTIFACT ->
                AdaptiveSyncProfile(mode, previewHz = 6, textOpBatchWindowMs = 100, sendPreviews = true)
            CollabMode.SHARED_REGION ->
                AdaptiveSyncProfile(mode, previewHz = 10, textOpBatchWindowMs = 80, sendPreviews = true)
            CollabMode.CONTENDED_TARGET ->
                AdaptiveSyncProfile(mode, previewHz = 16, textOpBatchWindowMs = 50, sendPreviews = true)
            CollabMode.DEGRADED ->
                AdaptiveSyncProfile(mode, previewHz = 3, textOpBatchWindowMs = 150, sendPreviews = true)
        }
    }
}

/**
 * Observable inputs the scheduler uses to derive the current mode.
 */
data class CollabSituation(
    val peerCount: Int,
    /** Peers whose active region overlaps the local actor's region. */
    val peersInSameRegion: Int,
    /** Peers editing the exact same target the local actor is editing. */
    val peersOnSameTarget: Int,
    val deviceIsWeak: Boolean,
    val networkIsWeak: Boolean,
)

/**
 * Pure mode-derivation function. Deterministic and unit-testable; holds no state.
 *
 * Precedence: DEGRADED (weak device/network) is orthogonal — it caps richness but the
 * contention tier still informs batching. We surface the contention tier as the mode and let
 * [AdaptiveSyncController] apply a DEGRADED overlay so both signals are preserved.
 */
object CollabModeResolver {
    fun contentionTier(s: CollabSituation): CollabMode = when {
        s.peerCount <= 0 -> CollabMode.SOLO
        s.peersOnSameTarget > 0 -> CollabMode.CONTENDED_TARGET
        s.peersInSameRegion > 0 -> CollabMode.SHARED_REGION
        else -> CollabMode.SHARED_ARTIFACT
    }

    fun isDegraded(s: CollabSituation): Boolean = s.deviceIsWeak || s.networkIsWeak
}
