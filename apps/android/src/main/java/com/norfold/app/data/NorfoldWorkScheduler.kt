package com.norfold.app.data

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.norfold.app.cloud.NorfoldSupabase
import com.norfold.app.cloud.PushRegistrationClient
import com.norfold.app.domain.AppSettings
import com.norfold.app.domain.SyncProvider
import java.util.concurrent.TimeUnit

object NorfoldWorkScheduler {
    private const val BackupWork = "norfold.account.backup"
    private const val SyncWork = "norfold.account.sync"
    private const val PushHealthWork = "norfold.account.push-health"
    private const val ReconcileWork = "norfold.account.reconcile"

    fun reconcile(context: Context, settings: AppSettings) {
        val manager = WorkManager.getInstance(context.applicationContext)
        if (settings.autoBackup && settings.syncProvider == SyncProvider.GoogleDrive) {
            val days = when (settings.backupFrequency.trim().lowercase()) {
                "weekly" -> 7L
                "monthly" -> 30L
                else -> 1L
            }
            val request = PeriodicWorkRequestBuilder<NorfoldBackupWorker>(days, TimeUnit.DAYS)
                .setConstraints(networkConstraints(settings))
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            manager.enqueueUniquePeriodicWork(BackupWork, ExistingPeriodicWorkPolicy.UPDATE, request)
        } else {
            manager.cancelUniqueWork(BackupWork)
        }
        if (settings.autoSync && settings.backgroundSync && settings.syncProvider != SyncProvider.None) {
            val minutes = settings.syncIntervalMinutes.coerceIn(15, 24 * 60).toLong()
            val request = PeriodicWorkRequestBuilder<NorfoldSyncWorker>(minutes, TimeUnit.MINUTES)
                .setConstraints(networkConstraints(settings))
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            manager.enqueueUniquePeriodicWork(SyncWork, ExistingPeriodicWorkPolicy.UPDATE, request)
        } else {
            manager.cancelUniqueWork(SyncWork)
        }
        val pushHealth = PeriodicWorkRequestBuilder<NorfoldPushHealthWorker>(1, TimeUnit.DAYS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        manager.enqueueUniquePeriodicWork(PushHealthWork, ExistingPeriodicWorkPolicy.UPDATE, pushHealth)
    }

    fun enqueueReconciliation(context: Context, replace: Boolean = false) {
        val request = OneTimeWorkRequestBuilder<NorfoldReconciliationWorker>()
            .setExpedited(androidx.work.OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            ReconcileWork,
            if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
            request,
        )
    }

    fun cancelAccountWork(context: Context) {
        WorkManager.getInstance(context.applicationContext).run {
            cancelUniqueWork(BackupWork)
            cancelUniqueWork(SyncWork)
            cancelUniqueWork(PushHealthWork)
            cancelUniqueWork(ReconcileWork)
        }
    }

    private fun networkConstraints(settings: AppSettings): Constraints = Constraints.Builder()
        .setRequiredNetworkType(if (settings.syncOnMobileData) NetworkType.CONNECTED else NetworkType.UNMETERED)
        .setRequiresBatteryNotLow(!settings.syncOnBatterySaver)
        .build()
}

class NorfoldBackupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val repository = DocsRepository(NorfoldDatabase.get(applicationContext))
        val settings = repository.settingsValue()
        if (!settings.autoBackup || settings.syncProvider != SyncProvider.GoogleDrive) return Result.success()
        val accountId = NorfoldSupabase.currentAccountId() ?: return updateStatus(repository, "Backup paused — sign in again")
        val secret = SyncSecretStore(applicationContext).load(accountId)
            ?: return updateStatus(repository, "Backup paused — confirm the recovery secret in Backup settings")
        return try {
            val identity = InstallationIdentityStore(applicationContext)
            val chainId = settings.syncChainId ?: return updateStatus(repository, "Backup setup is incomplete")
            val result = GoogleDriveApiSyncStore(applicationContext, GoogleDriveAuthStore(applicationContext)).writeDeviceBackup(
                secret = secret,
                chainId = chainId,
                username = settings.syncUserName,
                publicName = settings.syncPublicName,
                deviceId = identity.installationId,
                deviceName = settings.syncDeviceName.ifBlank { identity.suggestedDeviceName },
                snapshot = repository.snapshot(),
            )
            repository.updateSettings(repository.settingsValue().copy(
                lastSyncAt = System.currentTimeMillis(),
                lastSyncHash = result.snapshotHash,
                lastSyncStatus = result.status,
            ))
            Result.success()
        } catch (error: Throwable) {
            repository.updateSettings(repository.settingsValue().copy(lastSyncStatus = "Automatic backup failed — ${safeMessage(error)}"))
            if (runAttemptCount < 4) Result.retry() else Result.failure()
        } finally {
            secret.fill('\u0000')
        }
    }
}

class NorfoldSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val repository = DocsRepository(NorfoldDatabase.get(applicationContext))
        val settings = repository.settingsValue()
        if (!settings.autoSync || !settings.backgroundSync || settings.syncProvider != SyncProvider.GoogleDrive) return Result.success()
        val accountId = NorfoldSupabase.currentAccountId() ?: return Result.success()
        val secret = SyncSecretStore(applicationContext).load(accountId) ?: return Result.success()
        return try {
            val chainId = settings.syncChainId ?: return Result.success()
            val (remote, result) = GoogleDriveApiSyncStore(applicationContext, GoogleDriveAuthStore(applicationContext)).syncNow(
                chainId = chainId,
                secret = secret,
                username = settings.syncUserName,
                publicName = settings.syncPublicName,
                deviceName = settings.syncDeviceName,
                lastSyncHash = settings.lastSyncHash,
                localSnapshot = repository.snapshot(),
            )
            if (remote != null) repository.restore(remote)
            repository.updateSettings(repository.settingsValue().copy(
                lastSyncAt = System.currentTimeMillis(),
                lastSyncHash = result.snapshotHash,
                lastSyncStatus = result.status,
                syncConflictCount = result.conflictCount,
            ))
            Result.success()
        } catch (error: Throwable) {
            repository.updateSettings(repository.settingsValue().copy(lastSyncStatus = "Background sync failed — ${safeMessage(error)}"))
            if (runAttemptCount < 4) Result.retry() else Result.failure()
        } finally {
            secret.fill('\u0000')
        }
    }
}

class NorfoldPushHealthWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = runCatching {
        if (NorfoldSupabase.isSignedIn()) PushRegistrationClient(applicationContext).registerCurrentToken()
    }.fold(onSuccess = { Result.success() }, onFailure = { if (runAttemptCount < 4) Result.retry() else Result.failure() })
}

class NorfoldReconciliationWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = runCatching {
        if (NorfoldSupabase.isSignedIn()) PushRegistrationClient(applicationContext).registerCurrentToken()
        // Realtime reconnect and object-level catch-up run from the active ViewModel. This durable
        // worker guarantees token health after process death; missed object operations are pulled
        // on the next foreground initialization instead of trusting FCM as a data transport.
    }.fold(onSuccess = { Result.success() }, onFailure = { if (runAttemptCount < 4) Result.retry() else Result.failure() })
}

private suspend fun updateStatus(repository: DocsRepository, status: String): androidx.work.ListenableWorker.Result {
    repository.updateSettings(repository.settingsValue().copy(lastSyncStatus = status))
    return androidx.work.ListenableWorker.Result.success()
}

private fun safeMessage(error: Throwable): String = error.message
    ?.replace(Regex("(?i)(bearer|token|secret|password)\\s+[^\\s,;]+"), "${'$'}1 [redacted]")
    ?.take(180)
    ?.takeIf(String::isNotBlank)
    ?: "retry when the connection is available"
