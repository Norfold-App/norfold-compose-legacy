package com.norfold.app.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.norfold.app.domain.BackupCodec
import com.norfold.app.domain.BackupSnapshot
import com.norfold.app.domain.ParsedSyncConflictReport
import com.norfold.app.domain.SyncConflictReport
import com.norfold.app.domain.SyncProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.security.MessageDigest
import java.util.UUID

data class SyncWriteResult(
    val chainId: String,
    val snapshotHash: String,
    val status: String,
    val conflictCount: Int = 0,
)

data class SyncRestoreResult(
    val snapshot: BackupSnapshot,
    val writeResult: SyncWriteResult,
)

class CloudFolderSyncStore(private val context: Context) {
    suspend fun createChain(
        treeUri: Uri,
        provider: SyncProvider,
        secret: CharArray,
        username: String,
        publicName: String,
        deviceName: String,
        snapshot: BackupSnapshot,
    ): SyncWriteResult = withContext(Dispatchers.IO) {
        persistTreePermission(treeUri)
        val chainId = UUID.randomUUID().toString()
        val snapshotHash = snapshot.stableHash()
        val folder = appFolder(treeUri)
        folder.writeText(SnapshotFileName, BackupCodec.encrypt(snapshot, secret))
        folder.writeText(
            ManifestFileName,
            manifest(
                chainId = chainId,
                provider = provider,
                username = username,
                publicName = publicName,
                deviceName = deviceName,
                snapshotHash = snapshotHash,
            ),
        )
        folder.writeText(StatusFileName, "Created sync chain at ${System.currentTimeMillis()}")
        SyncWriteResult(chainId, snapshotHash, "Sync chain created")
    }

    suspend fun restoreChain(treeUri: Uri, provider: SyncProvider, secret: CharArray): SyncRestoreResult = withContext(Dispatchers.IO) {
        persistTreePermission(treeUri)
        val folder = appFolder(treeUri)
        val payload = folder.readText(SnapshotFileName) ?: error("No Norfold snapshot found in the selected folder.")
        val snapshot = BackupCodec.decrypt(payload, secret)
        val snapshotHash = snapshot.stableHash()
        val manifest = folder.readJson(ManifestFileName)
        val chainId = manifest?.optString("chainId")?.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString()
        SyncRestoreResult(
            snapshot = snapshot,
            writeResult = SyncWriteResult(chainId, snapshotHash, "Sync chain restored from ${provider.displayName}"),
        )
    }

    suspend fun syncNow(
        treeUri: Uri,
        provider: SyncProvider,
        chainId: String,
        secret: CharArray,
        username: String,
        publicName: String,
        deviceName: String,
        lastSyncHash: String?,
        localSnapshot: BackupSnapshot,
    ): Pair<BackupSnapshot?, SyncWriteResult> = withContext(Dispatchers.IO) {
        persistTreePermission(treeUri)
        val folder = appFolder(treeUri)
        val localHash = localSnapshot.stableHash()
        val remotePayload = folder.readText(SnapshotFileName)
        if (remotePayload == null) {
            folder.writeText(SnapshotFileName, BackupCodec.encrypt(localSnapshot, secret))
            folder.writeText(ManifestFileName, manifest(chainId, provider, username, publicName, deviceName, localHash))
            return@withContext null to SyncWriteResult(chainId, localHash, "Remote snapshot created")
        }

        val remoteSnapshot = BackupCodec.decrypt(remotePayload, secret)
        val remoteHash = remoteSnapshot.stableHash()
        val result = when {
            remoteHash == localHash -> {
                folder.writeText(ManifestFileName, manifest(chainId, provider, username, publicName, deviceName, localHash))
                null to SyncWriteResult(chainId, localHash, "Already in sync")
            }
            lastSyncHash == null || lastSyncHash == remoteHash -> {
                folder.writeText(SnapshotFileName, BackupCodec.encrypt(localSnapshot, secret))
                folder.writeText(ManifestFileName, manifest(chainId, provider, username, publicName, deviceName, localHash))
                null to SyncWriteResult(chainId, localHash, "Uploaded local changes")
            }
            lastSyncHash == localHash -> {
                remoteSnapshot to SyncWriteResult(chainId, remoteHash, "Downloaded remote changes")
            }
            else -> {
                folder.writeText(
                    ConflictFileName,
                    SyncConflictReport.build(
                        format = "norfold-conflict-v3",
                        localHash = localHash,
                        remoteHash = remoteHash,
                        deviceName = deviceName,
                        localSnapshot = localSnapshot,
                        remoteSnapshot = remoteSnapshot,
                    ),
                )
                null to SyncWriteResult(chainId, lastSyncHash, "Conflict detected; review before syncing", conflictCount = 1)
            }
        }
        folder.writeText(StatusFileName, result.second.status)
        result
    }

    suspend fun readConflictReport(treeUri: Uri): ParsedSyncConflictReport? = withContext(Dispatchers.IO) {
        persistTreePermission(treeUri)
        SyncConflictReport.parseOrNull(appFolder(treeUri).readText(ConflictFileName))
    }

    private fun persistTreePermission(treeUri: Uri) {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        runCatching { context.contentResolver.takePersistableUriPermission(treeUri, flags) }
    }

    private fun appFolder(treeUri: Uri): DocumentFile {
        val root = DocumentFile.fromTreeUri(context, treeUri) ?: error("Cannot open selected folder.")
        return root.findFile(AppFolderName)?.takeIf { it.isDirectory }
            ?: root.createDirectory(AppFolderName)
            ?: root
    }

    private fun DocumentFile.readText(fileName: String): String? {
        val file = findFile(fileName)?.takeIf { it.isFile } ?: return null
        return context.contentResolver.openInputStream(file.uri)?.bufferedReader()?.use { it.readText() }
    }

    private fun DocumentFile.readJson(fileName: String): JSONObject? = readText(fileName)?.let { runCatching { JSONObject(it) }.getOrNull() }

    private fun DocumentFile.writeText(fileName: String, value: String) {
        val file = findFile(fileName)?.takeIf { it.isFile } ?: createFile("application/json", fileName) ?: error("Cannot create $fileName")
        context.contentResolver.openOutputStream(file.uri, "wt")?.bufferedWriter()?.use { it.write(value) } ?: error("Cannot write $fileName")
    }

    private fun manifest(
        chainId: String,
        provider: SyncProvider,
        username: String,
        publicName: String,
        deviceName: String,
        snapshotHash: String,
    ): String = JSONObject()
        .put("app", "Norfold")
        .put("format", "norfold-sync-chain-v2")
        .put("chainId", chainId)
        .put("provider", provider.name)
        .put("providerLabel", provider.displayName)
        .put("username", username)
        .put("publicName", publicName)
        .put("lastWriterDevice", deviceName)
        .put("snapshotHash", snapshotHash)
        .put("updatedAt", System.currentTimeMillis())
        .toString(2)

    private fun BackupSnapshot.stableHash(): String = sha256(BackupCodec.encode(this))

    private fun sha256(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val AppFolderName = "Norfold"
        private const val ManifestFileName = "norfold.syncchain.json"
        private const val SnapshotFileName = "norfold.snapshot.enc"
        private const val StatusFileName = "norfold.status.txt"
        private const val ConflictFileName = "norfold.conflict.json"
    }
}

val SyncProvider.displayName: String
    get() = when (this) {
        SyncProvider.GoogleDrive -> "Google Drive folder"
        SyncProvider.OneDrive -> "OneDrive folder"
        SyncProvider.LocalFolder -> "Local folder"
        SyncProvider.None -> "None"
    }
