package com.norfold.app.ui.feedback

import com.norfold.app.domain.AppSettings
import com.norfold.app.domain.SyncProvider

enum class SyncTruthKind {
    NotConfigured,
    Ready,
    Syncing,
    Synced,
    Offline,
    Conflict,
    Error,
}

data class SyncTruth(
    val kind: SyncTruthKind,
    val title: String,
    val detail: String,
)

fun syncTruth(settings: AppSettings, syncing: Boolean, online: Boolean = true): SyncTruth {
    val status = settings.lastSyncStatus.trim()
    val normalized = status.lowercase()
    val requiresNetwork = settings.syncProvider == SyncProvider.GoogleDrive ||
        settings.syncProvider == SyncProvider.OneDrive
    return when {
        syncing -> SyncTruth(SyncTruthKind.Syncing, "Syncing…", "Encrypting and reconciling workspace changes")
        settings.syncProvider == SyncProvider.None -> SyncTruth(
            SyncTruthKind.NotConfigured,
            "Sync not configured",
            "Changes are saved on this device",
        )
        !online && requiresNetwork -> SyncTruth(
            SyncTruthKind.Offline,
            "Offline — saved locally",
            "Reconnect to update the encrypted cloud snapshot",
        )
        settings.syncConflictCount > 0 || "conflict" in normalized -> SyncTruth(
            SyncTruthKind.Conflict,
            "Sync needs attention",
            "${settings.syncConflictCount.coerceAtLeast(1)} conflict${if (settings.syncConflictCount == 1) "" else "s"} to review",
        )
        "offline" in normalized || "network" in normalized -> SyncTruth(
            SyncTruthKind.Offline,
            "Offline — saved locally",
            "Reconnect to update the encrypted cloud snapshot",
        )
        listOf("failed", "couldn't", "expired", "full", "corrupt", "wrong").any(normalized::contains) -> SyncTruth(
            SyncTruthKind.Error,
            "Sync error",
            status.ifBlank { "Open Sync Settings to retry" },
        )
        settings.lastSyncAt == null -> SyncTruth(
            SyncTruthKind.Ready,
            "Ready to sync",
            "No encrypted cloud snapshot has been completed yet",
        )
        else -> SyncTruth(
            SyncTruthKind.Synced,
            "Synced",
            status.ifBlank { "Encrypted cloud snapshot is current" },
        )
    }
}
