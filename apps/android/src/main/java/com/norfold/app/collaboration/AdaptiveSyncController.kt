package com.norfold.app.collaboration

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Stateful controller that turns a stream of [CollabSituation] updates into an effective
 * [AdaptiveSyncProfile]. Applies the DEGRADED overlay on top of the contention tier so that
 * weak device/network reduces preview richness while preserving the batching implied by
 * same-target contention (correctness-preserving degradation).
 *
 * Thresholds/rates are provided by [profileProvider] so benchmarks can inject tuned values.
 */
class AdaptiveSyncController(
    private val profileProvider: (CollabMode) -> AdaptiveSyncProfile = AdaptiveSyncProfile::default,
) {
    private val _profile = MutableStateFlow(AdaptiveSyncProfile.default(CollabMode.SOLO))
    val profile: StateFlow<AdaptiveSyncProfile> = _profile.asStateFlow()

    private val _mode = MutableStateFlow(CollabMode.SOLO)
    val mode: StateFlow<CollabMode> = _mode.asStateFlow()

    fun update(situation: CollabSituation) {
        val tier = CollabModeResolver.contentionTier(situation)
        val degraded = CollabModeResolver.isDegraded(situation)
        _mode.value = if (degraded) CollabMode.DEGRADED else tier

        val base = profileProvider(tier)
        _profile.value = if (!degraded) {
            base
        } else {
            // DEGRADED overlay: keep the tier's tighter text-op batching (correctness-adjacent),
            // but clamp preview rate to the degraded ceiling and preserve durable behavior.
            val degradedCeiling = profileProvider(CollabMode.DEGRADED)
            base.copy(
                mode = CollabMode.DEGRADED,
                previewHz = minOf(base.previewHz, degradedCeiling.previewHz),
                sendPreviews = base.sendPreviews && degradedCeiling.sendPreviews,
            )
        }
    }
}
