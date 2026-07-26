package com.norfold.app.data.chat

import com.norfold.app.data.GoogleDriveApiSyncStore
import com.norfold.app.domain.chat.ArchiveObject
import com.norfold.app.domain.chat.ArchiveProvider
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.security.MessageDigest

class GoogleDriveChatArchiveProvider(
    private val drive: GoogleDriveApiSyncStore,
) : ArchiveProvider {
    override val providerId = "google-drive-app-data"
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    override suspend fun put(value: ArchiveObject) {
        drive.putPrivateChatArchiveFile(
            fileName(value.id),
            json.encodeToString(DriveStoredArchive(value.id, value.encryptedEnvelope, value.updatedAt)),
        )
    }

    override suspend fun get(id: String): ArchiveObject? = drive.readPrivateChatArchiveFile(fileName(id))
        ?.let { json.decodeFromString<DriveStoredArchive>(it) }
        ?.also { require(it.id == id) }
        ?.toDomain()

    override suspend fun list(): List<ArchiveObject> = drive.listPrivateChatArchiveFiles()
        .mapNotNull { runCatching { json.decodeFromString<DriveStoredArchive>(it.body).toDomain() }.getOrNull() }
        .sortedByDescending { it.updatedAt }

    override suspend fun delete(id: String) = drive.deletePrivateChatArchiveFile(fileName(id))

    private fun fileName(id: String): String {
        require(id.isNotBlank())
        val hash = MessageDigest.getInstance("SHA-256").digest(id.encodeToByteArray())
            .joinToString("") { "%02x".format(it) }
        return "norfold-chat-archive-v1-$hash.json"
    }
}

@Serializable
private data class DriveStoredArchive(val id: String, val encryptedEnvelope: String, val updatedAt: Long) {
    fun toDomain() = ArchiveObject(id, encryptedEnvelope, updatedAt)
}
