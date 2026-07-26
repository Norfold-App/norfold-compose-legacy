package com.norfold.app.data.chat

import android.content.Context
import com.norfold.app.domain.chat.ArchiveObject
import com.norfold.app.domain.chat.ArchiveProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest

class LocalChatArchiveProvider(context: Context) : ArchiveProvider {
    override val providerId = "local-device"
    private val root = File(context.applicationContext.filesDir, "private-chat-archive-v1")
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    override suspend fun put(value: ArchiveObject) = withContext(Dispatchers.IO) {
        root.mkdirs()
        val target = file(value.id)
        val temporary = File(target.parentFile, "${target.name}.tmp")
        temporary.writeText(json.encodeToString(LocalStoredArchive(value.id, value.encryptedEnvelope, value.updatedAt)))
        check(temporary.renameTo(target) || run { temporary.copyTo(target, overwrite = true); temporary.delete() })
    }

    override suspend fun get(id: String): ArchiveObject? = withContext(Dispatchers.IO) {
        file(id).takeIf(File::isFile)?.readText()?.let { encoded ->
            json.decodeFromString<LocalStoredArchive>(encoded).also { require(it.id == id) }.toDomain()
        }
    }

    override suspend fun list(): List<ArchiveObject> = withContext(Dispatchers.IO) {
        root.listFiles { file -> file.extension == "json" }.orEmpty().mapNotNull { file ->
            runCatching { json.decodeFromString<LocalStoredArchive>(file.readText()).toDomain() }.getOrNull()
        }.sortedByDescending { it.updatedAt }
    }

    override suspend fun delete(id: String) = withContext(Dispatchers.IO) { file(id).delete(); Unit }

    private fun file(id: String): File {
        require(id.isNotBlank())
        val hash = MessageDigest.getInstance("SHA-256").digest(id.encodeToByteArray())
            .joinToString("") { "%02x".format(it) }
        return File(root, "$hash.json")
    }
}

@Serializable
private data class LocalStoredArchive(val id: String, val encryptedEnvelope: String, val updatedAt: Long) {
    fun toDomain() = ArchiveObject(id, encryptedEnvelope, updatedAt)
}
