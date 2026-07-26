package com.norfold.app.domain.chat

data class ArchiveObject(
    val id: String,
    val encryptedEnvelope: String,
    val updatedAt: Long,
)

/** Provider-neutral storage for already encrypted private Chat archives. */
interface ArchiveProvider {
    val providerId: String
    suspend fun put(value: ArchiveObject)
    suspend fun get(id: String): ArchiveObject?
    suspend fun list(): List<ArchiveObject>
    suspend fun delete(id: String)
}

interface ArchiveCryptoEngine {
    val formatId: String
    fun seal(archiveId: String, plaintext: ByteArray, createdAt: Long): String
    fun open(archiveId: String, envelope: String): ByteArray
    fun getOrCreateRecoveryMaterial(archiveId: String): String =
        error("Portable archive recovery is unavailable.")
    fun openWithRecovery(archiveId: String, envelope: String, recoveryMaterial: String): ByteArray =
        error("Portable archive recovery is unavailable.")
}
