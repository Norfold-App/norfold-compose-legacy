package com.norfold.app.ui.feedback

import com.norfold.app.data.DocsRepository
import com.norfold.app.domain.SyncProvider
import org.junit.Assert.assertEquals
import org.junit.Test

class SyncTruthTest {
    @Test
    fun unconfiguredSyncNeverClaimsAllCaughtUp() {
        val truth = syncTruth(DocsRepository.defaultSettings, syncing = false, online = true)

        assertEquals(SyncTruthKind.NotConfigured, truth.kind)
        assertEquals("Sync not configured", truth.title)
    }

    @Test
    fun offlineConfiguredWorkspaceReportsLocalSafety() {
        val settings = DocsRepository.defaultSettings.copy(
            syncProvider = SyncProvider.GoogleDrive,
            syncFolderUri = "appDataFolder://norfold",
            syncChainId = "chain",
        )

        val truth = syncTruth(settings, syncing = false, online = false)

        assertEquals(SyncTruthKind.Offline, truth.kind)
        assertEquals("Offline — saved locally", truth.title)
    }

    @Test
    fun conflictOutranksPreviousSuccess() {
        val settings = DocsRepository.defaultSettings.copy(
            syncProvider = SyncProvider.LocalFolder,
            syncFolderUri = "content://folder",
            syncChainId = "chain",
            lastSyncAt = 42L,
            lastSyncStatus = "Conflict detected; review before syncing",
            syncConflictCount = 2,
        )

        assertEquals(SyncTruthKind.Conflict, syncTruth(settings, syncing = false).kind)
    }

    @Test
    fun localFolderSyncRemainsAvailableWithoutInternet() {
        val settings = DocsRepository.defaultSettings.copy(
            syncProvider = SyncProvider.LocalFolder,
            syncFolderUri = "content://folder",
            syncChainId = "chain",
        )

        assertEquals(SyncTruthKind.Ready, syncTruth(settings, syncing = false, online = false).kind)
    }

    @Test
    fun feedbackWithActionDefaultsToLongDuration() {
        val feedback = UiFeedback(
            id = 1,
            message = "Deleted",
            actionLabel = "Undo",
            action = UiFeedbackAction.Resolve(2),
        )

        assertEquals(UiFeedbackDuration.Long, feedback.duration)
    }
}
