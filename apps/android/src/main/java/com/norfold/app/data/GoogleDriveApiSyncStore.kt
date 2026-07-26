package com.norfold.app.data

import android.content.Context
import com.norfold.app.domain.BackupCodec
import com.norfold.app.domain.BackupCategoryCounts
import com.norfold.app.domain.BackupSnapshot
import com.norfold.app.domain.categoryCounts
import com.norfold.app.domain.ParsedSyncConflictReport
import com.norfold.app.domain.SyncConflictReport
import com.norfold.app.domain.SyncProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.UUID

data class GoogleDriveBackupManifest(
    val deviceId: String,
    val deviceName: String,
    val chainId: String,
    val snapshotFileName: String,
    val snapshotHash: String,
    val updatedAt: Long,
    val encryptedSizeBytes: Long,
    val appVersion: String,
    val schemaVersion: Int,
    val counts: BackupCategoryCounts,
    val legacy: Boolean = false,
)

class GoogleDriveApiSyncStore(
    private val context: Context,
    private val authStore: GoogleDriveAuthStore,
) {
    data class OpaqueAppDataFile(val name: String, val body: String)

    suspend fun putPrivateChatArchiveFile(fileName: String, encryptedBody: String): Unit =
        withContext(Dispatchers.IO) {
            require(fileName.startsWith(ChatArchivePrefix) && fileName.endsWith(".json"))
            uploadOrCreateJson(accessToken(), fileName, encryptedBody)
        }

    suspend fun readPrivateChatArchiveFile(fileName: String): String? = withContext(Dispatchers.IO) {
        require(fileName.startsWith(ChatArchivePrefix) && fileName.endsWith(".json"))
        val accessToken = accessToken()
        val file = findFile(accessToken, fileName) ?: return@withContext null
        request(accessToken, "GET", "$DriveApi/files/${file.id}?alt=media")
    }

    suspend fun listPrivateChatArchiveFiles(): List<OpaqueAppDataFile> = withContext(Dispatchers.IO) {
        val accessToken = accessToken()
        val query = URLEncoder.encode("name contains '$ChatArchivePrefix' and trashed = false", "UTF-8")
        val url = "$DriveApi/files?spaces=$AppDataFolderName&q=$query&fields=files(id,name)&pageSize=1000"
        val rows = JSONObject(request(accessToken, "GET", url)).optJSONArray("files") ?: return@withContext emptyList()
        buildList {
            for (index in 0 until rows.length()) {
                val row = rows.optJSONObject(index) ?: continue
                val name = row.optString("name")
                if (!name.startsWith(ChatArchivePrefix) || !name.endsWith(".json")) continue
                val body = request(accessToken, "GET", "$DriveApi/files/${row.getString("id")}?alt=media")
                add(OpaqueAppDataFile(name, body))
            }
        }
    }

    suspend fun deletePrivateChatArchiveFile(fileName: String): Unit = withContext(Dispatchers.IO) {
        require(fileName.startsWith(ChatArchivePrefix) && fileName.endsWith(".json"))
        val accessToken = accessToken()
        findFile(accessToken, fileName)?.let { request(accessToken, "DELETE", "$DriveApi/files/${it.id}") }
    }

    suspend fun discoverBackups(): List<GoogleDriveBackupManifest> = withContext(Dispatchers.IO) {
        val accessToken = accessToken()
        val registryFile = findFile(accessToken, RegistryFileName)
        if (registryFile != null) {
            return@withContext decodeRegistry(request(accessToken, "GET", "$DriveApi/files/${registryFile.id}?alt=media"))
        }
        val legacy = readRemoteSync(accessToken) ?: return@withContext emptyList()
        listOf(
            GoogleDriveBackupManifest(
                deviceId = "legacy",
                deviceName = legacy.deviceName.ifBlank { "Earlier Norfold device" },
                chainId = legacy.chainId,
                snapshotFileName = SyncFileName,
                snapshotHash = legacy.snapshotHash,
                updatedAt = legacy.updatedAt,
                encryptedSizeBytes = legacy.snapshotPayload.length.toLong(),
                appVersion = "Earlier Norfold build",
                schemaVersion = 3,
                counts = BackupCategoryCounts(0, 0, 0),
                legacy = true,
            ),
        )
    }

    suspend fun writeDeviceBackup(
        secret: CharArray,
        chainId: String,
        username: String,
        publicName: String,
        deviceId: String,
        deviceName: String,
        snapshot: BackupSnapshot,
    ): SyncWriteResult = withContext(Dispatchers.IO) {
        writeDeviceBackupInternal(accessToken(), secret, chainId, username, publicName, deviceId, deviceName, snapshot)
    }

    suspend fun restoreDeviceBackup(secret: CharArray, manifest: GoogleDriveBackupManifest): SyncRestoreResult = withContext(Dispatchers.IO) {
        val accessToken = accessToken()
        val file = findFile(accessToken, manifest.snapshotFileName)
            ?: error("That Norfold backup is no longer available in Google Drive app data.")
        val remote = decodeRemoteSync(request(accessToken, "GET", "$DriveApi/files/${file.id}?alt=media"), file.id)
        val snapshot = BackupCodec.decrypt(remote.snapshotPayload, secret)
        SyncRestoreResult(snapshot, SyncWriteResult(remote.chainId, snapshot.stableHash(), "Backup decrypted and ready to restore"))
    }

    suspend fun createChain(
        secret: CharArray,
        username: String,
        publicName: String,
        deviceName: String,
        snapshot: BackupSnapshot,
    ): SyncWriteResult = withContext(Dispatchers.IO) {
        val chainId = UUID.randomUUID().toString()
        writeDeviceBackupInternal(
            accessToken = accessToken(),
            secret = secret,
            chainId = chainId,
            username = username,
            publicName = publicName,
            deviceId = InstallationIdentityStore(context).installationId,
            deviceName = deviceName,
            snapshot = snapshot,
            status = "Google Drive API sync chain created",
        )
    }

    suspend fun restoreChain(secret: CharArray): SyncRestoreResult = withContext(Dispatchers.IO) {
        val accessToken = accessToken()
        val remote = readRemoteSync(accessToken) ?: error("No Norfold sync file found in Google Drive app data.")
        val snapshot = BackupCodec.decrypt(remote.snapshotPayload, secret)
        val hash = snapshot.stableHash()
        SyncRestoreResult(
            snapshot = snapshot,
            writeResult = SyncWriteResult(remote.chainId, hash, "Restored from Google Drive app data"),
        )
    }

    suspend fun syncNow(
        chainId: String,
        secret: CharArray,
        username: String,
        publicName: String,
        deviceName: String,
        lastSyncHash: String?,
        localSnapshot: BackupSnapshot,
    ): Pair<BackupSnapshot?, SyncWriteResult> = withContext(Dispatchers.IO) {
        val accessToken = accessToken()
        val localHash = localSnapshot.stableHash()
        val remote = readRemoteSync(accessToken)
        if (remote == null) {
            uploadOrCreateJson(
                accessToken,
                SyncFileName,
                syncDocument(chainId, username, publicName, deviceName, localHash, BackupCodec.encrypt(localSnapshot, secret), "Remote snapshot created"),
            )
            return@withContext null to SyncWriteResult(chainId, localHash, "Remote snapshot created")
        }

        val remoteSnapshot = BackupCodec.decrypt(remote.snapshotPayload, secret)
        val remoteHash = remoteSnapshot.stableHash()
        when {
            remoteHash == localHash -> {
                uploadOrCreateJson(accessToken, SyncFileName, syncDocument(chainId, username, publicName, deviceName, localHash, remote.snapshotPayload, "Already in sync"))
                null to SyncWriteResult(chainId, localHash, "Already in sync")
            }
            lastSyncHash == null || lastSyncHash == remoteHash -> {
                uploadOrCreateJson(accessToken, SyncFileName, syncDocument(chainId, username, publicName, deviceName, localHash, BackupCodec.encrypt(localSnapshot, secret), "Uploaded local changes"))
                null to SyncWriteResult(chainId, localHash, "Uploaded local changes")
            }
            lastSyncHash == localHash -> {
                remoteSnapshot to SyncWriteResult(chainId, remoteHash, "Downloaded remote changes")
            }
            else -> {
                uploadOrCreateJson(
                    accessToken,
                    ConflictFileName,
                    SyncConflictReport.build(
                        format = "norfold-google-drive-conflict-v3",
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
    }

    suspend fun readConflictReport(): ParsedSyncConflictReport? = withContext(Dispatchers.IO) {
        val accessToken = accessToken()
        val file = findFile(accessToken, ConflictFileName) ?: return@withContext null
        SyncConflictReport.parseOrNull(request(accessToken, "GET", "$DriveApi/files/${file.id}?alt=media"))
    }

    private fun accessToken(): String = authStore.loadAccessToken()
        ?: error("Connect Google Drive before using app-data sync.")

    private fun readRemoteSync(accessToken: String): RemoteSync? {
        val file = findFile(accessToken, SyncFileName) ?: return null
        val body = request(accessToken, "GET", "$DriveApi/files/${file.id}?alt=media")
        return decodeRemoteSync(body, file.id)
    }

    private fun decodeRemoteSync(body: String, fileId: String): RemoteSync {
        val json = JSONObject(body)
        val format = json.optString("format")
        require(format == SyncFormat) { "Google Drive sync file is not a Norfold sync chain." }
        return RemoteSync(
            fileId = fileId,
            chainId = json.optString("chainId").ifBlank { UUID.randomUUID().toString() },
            snapshotHash = json.optString("snapshotHash"),
            snapshotPayload = json.getString("snapshotPayload"),
            deviceName = json.optString("lastWriterDevice"),
            updatedAt = json.optLong("updatedAt"),
        )
    }

    private fun writeDeviceBackupInternal(
        accessToken: String,
        secret: CharArray,
        chainId: String,
        username: String,
        publicName: String,
        deviceId: String,
        deviceName: String,
        snapshot: BackupSnapshot,
        status: String = "Automatic backup completed",
    ): SyncWriteResult {
        require(runCatching { UUID.fromString(deviceId) }.isSuccess) { "Installation id must be a UUID." }
        val snapshotHash = snapshot.stableHash()
        val encrypted = BackupCodec.encrypt(snapshot, secret)
        val body = syncDocument(chainId, username, publicName, deviceName, snapshotHash, encrypted, status)
        val snapshotFileName = "norfold-backup-$deviceId.json"
        uploadOrCreateJson(accessToken, snapshotFileName, body)
        // Keep the canonical file for current clients and conflict review. It is intentionally
        // overwritten by every successful backup, while the per-device file preserves device
        // discovery and selective restore metadata.
        uploadOrCreateJson(accessToken, SyncFileName, body)
        val previous = readRegistry(accessToken).filterNot { it.deviceId == deviceId }
        val manifest = GoogleDriveBackupManifest(
            deviceId = deviceId,
            deviceName = deviceName,
            chainId = chainId,
            snapshotFileName = snapshotFileName,
            snapshotHash = snapshotHash,
            updatedAt = System.currentTimeMillis(),
            encryptedSizeBytes = encrypted.toByteArray(Charsets.UTF_8).size.toLong(),
            appVersion = "${com.norfold.app.BuildConfig.VERSION_NAME} (${com.norfold.app.BuildConfig.VERSION_CODE})",
            schemaVersion = 3,
            counts = snapshot.categoryCounts(),
        )
        uploadOrCreateJson(accessToken, RegistryFileName, encodeRegistry((previous + manifest).sortedByDescending { it.updatedAt }))
        return SyncWriteResult(chainId, snapshotHash, status)
    }

    private fun readRegistry(accessToken: String): List<GoogleDriveBackupManifest> {
        val file = findFile(accessToken, RegistryFileName) ?: return emptyList()
        return decodeRegistry(request(accessToken, "GET", "$DriveApi/files/${file.id}?alt=media"))
    }

    private fun encodeRegistry(manifests: List<GoogleDriveBackupManifest>): String = JSONObject()
        .put("app", "Norfold")
        .put("format", RegistryFormat)
        .put("updatedAt", System.currentTimeMillis())
        .put("backups", JSONArray().apply {
            manifests.forEach { manifest ->
                put(JSONObject()
                    .put("deviceId", manifest.deviceId)
                    .put("deviceName", manifest.deviceName)
                    .put("chainId", manifest.chainId)
                    .put("snapshotFileName", manifest.snapshotFileName)
                    .put("snapshotHash", manifest.snapshotHash)
                    .put("updatedAt", manifest.updatedAt)
                    .put("encryptedSizeBytes", manifest.encryptedSizeBytes)
                    .put("appVersion", manifest.appVersion)
                    .put("schemaVersion", manifest.schemaVersion)
                    .put("workspaceContentCount", manifest.counts.workspaceContent)
                    .put("conversationAndFileCount", manifest.counts.conversationsAndFiles)
                    .put("deviceSettingsCount", manifest.counts.deviceSettings))
            }
        })
        .toString(2)

    private fun decodeRegistry(payload: String): List<GoogleDriveBackupManifest> {
        val json = JSONObject(payload)
        require(json.optString("format") == RegistryFormat) { "Google Drive backup registry has an unsupported format." }
        val backups = json.optJSONArray("backups") ?: return emptyList()
        return buildList {
            for (index in 0 until backups.length()) {
                val row = backups.optJSONObject(index) ?: continue
                val deviceId = row.optString("deviceId")
                val fileName = row.optString("snapshotFileName")
                if (deviceId.isBlank() || fileName.isBlank()) continue
                add(
                    GoogleDriveBackupManifest(
                        deviceId = deviceId,
                        deviceName = row.optString("deviceName").ifBlank { "Android device" },
                        chainId = row.optString("chainId"),
                        snapshotFileName = fileName,
                        snapshotHash = row.optString("snapshotHash"),
                        updatedAt = row.optLong("updatedAt"),
                        encryptedSizeBytes = row.optLong("encryptedSizeBytes"),
                        appVersion = row.optString("appVersion"),
                        schemaVersion = row.optInt("schemaVersion", 3),
                        counts = BackupCategoryCounts(
                            workspaceContent = row.optInt("workspaceContentCount"),
                            conversationsAndFiles = row.optInt("conversationAndFileCount"),
                            deviceSettings = row.optInt("deviceSettingsCount"),
                        ),
                    ),
                )
            }
        }.sortedByDescending { it.updatedAt }
    }

    private fun uploadOrCreateJson(accessToken: String, fileName: String, body: String): String {
        val existing = findFile(accessToken, fileName)
        val method = if (existing == null) "POST" else "PATCH"
        val target = if (existing == null) "$DriveUpload/files?uploadType=multipart&fields=id" else "$DriveUpload/files/${existing.id}?uploadType=multipart&fields=id"
        val metadata = JSONObject()
            .put("name", fileName)
            .apply { if (existing == null) put("parents", JSONArray().put(AppDataFolderName)) }
            .toString()
        val boundary = "norfold-${UUID.randomUUID()}"
        val payload = buildString {
            append("--$boundary\r\n")
            append("Content-Type: application/json; charset=UTF-8\r\n\r\n")
            append(metadata)
            append("\r\n--$boundary\r\n")
            append("Content-Type: application/json; charset=UTF-8\r\n\r\n")
            append(body)
            append("\r\n--$boundary--\r\n")
        }.toByteArray(Charsets.UTF_8)
        val response = requestBytes(accessToken, method, target, payload, "multipart/related; boundary=$boundary")
        return JSONObject(response).getString("id")
    }

    private fun findFile(accessToken: String, fileName: String): DriveFile? {
        val query = URLEncoder.encode("name = '$fileName' and trashed = false", "UTF-8")
        val url = "$DriveApi/files?spaces=$AppDataFolderName&q=$query&fields=files(id,name,modifiedTime,size)&pageSize=1"
        val files = JSONObject(request(accessToken, "GET", url)).optJSONArray("files") ?: return null
        if (files.length() == 0) return null
        val row = files.getJSONObject(0)
        return DriveFile(row.getString("id"), row.getString("name"))
    }

    private fun request(accessToken: String, method: String, url: String): String =
        requestBytes(accessToken, method, url, null, null)

    private fun requestBytes(accessToken: String, method: String, url: String, body: ByteArray?, contentType: String?): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            useCaches = false
            doInput = true
            setRequestProperty("Authorization", "Bearer $accessToken")
            setRequestProperty("Accept", "application/json")
            connectTimeout = 20_000
            readTimeout = 30_000
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", contentType ?: "application/json; charset=UTF-8")
                setRequestProperty("Content-Length", body.size.toString())
            }
        }
        return try {
            body?.let { connection.outputStream.use { out -> out.write(it) } }
            val responseCode = connection.responseCode
            val stream = if (responseCode in 200..299) connection.inputStream else connection.errorStream
            val response = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (responseCode !in 200..299) {
                throw IOException("Google Drive $method failed (${responseCode}): ${driveErrorMessage(response)}")
            }
            response
        } finally {
            connection.disconnect()
        }
    }

    private fun driveErrorMessage(response: String): String {
        if (response.isBlank()) return "No error body returned"
        val fallback = response.take(320)
        return runCatching {
            val error = JSONObject(response).optJSONObject("error") ?: return@runCatching fallback
            error.optString("message").takeIf { it.isNotBlank() } ?: fallback
        }.getOrDefault(fallback)
    }

    private fun syncDocument(
        chainId: String,
        username: String,
        publicName: String,
        deviceName: String,
        snapshotHash: String,
        snapshotPayload: String,
        status: String,
    ): String = JSONObject()
        .put("app", "Norfold")
        .put("format", SyncFormat)
        .put("chainId", chainId)
        .put("provider", SyncProvider.GoogleDrive.name)
        .put("providerLabel", "Google Drive API appDataFolder")
        .put("username", username)
        .put("publicName", publicName)
        .put("lastWriterDevice", deviceName)
        .put("snapshotHash", snapshotHash)
        .put("snapshotPayload", snapshotPayload)
        .put("status", status)
        .put("updatedAt", System.currentTimeMillis())
        .toString(2)

    private fun BackupSnapshot.stableHash(): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(BackupCodec.encode(this).toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    suspend fun wipeAllBackups(): Unit = withContext(Dispatchers.IO) {
        val accessToken = accessToken()
        val url = "$DriveApi/files?spaces=$AppDataFolderName&fields=files(id,name)&pageSize=100"
        val files = JSONObject(request(accessToken, "GET", url)).optJSONArray("files") ?: return@withContext
        for (i in 0 until files.length()) {
            val id = files.optJSONObject(i)?.optString("id") ?: continue
            request(accessToken, "DELETE", "$DriveApi/files/$id")
        }
    }

    private data class DriveFile(val id: String, val name: String)
    private data class RemoteSync(
        val fileId: String,
        val chainId: String,
        val snapshotHash: String,
        val snapshotPayload: String,
        val deviceName: String,
        val updatedAt: Long,
    )

    companion object {
        const val AppDataFolderUri = "google-drive://appDataFolder/norfold-sync.json"
        private const val SyncFileName = "norfold-sync.json"
        private const val RegistryFileName = "norfold-backup-registry-v1.json"
        private const val ConflictFileName = "norfold-conflict.json"
        private const val SyncFormat = "norfold-google-drive-sync-v2"
        private const val RegistryFormat = "norfold-google-drive-backup-registry-v1"
        private const val ChatArchivePrefix = "norfold-chat-archive-v1-"
        private const val AppDataFolderName = "appDataFolder"
        private const val DriveApi = "https://www.googleapis.com/drive/v3"
        private const val DriveUpload = "https://www.googleapis.com/upload/drive/v3"
    }
}
