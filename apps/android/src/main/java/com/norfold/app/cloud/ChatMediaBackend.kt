package com.norfold.app.cloud

import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.storage.storage
import io.ktor.http.ContentType
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant
import kotlin.time.Duration.Companion.minutes

/** Supabase reservation/storage adapter for opaque encrypted Chat media bytes. */
object ChatMediaBackend {
    private const val Bucket = "chat-media"
    private val client get() = NorfoldSupabase.client

    @Serializable
    data class Reservation(
        @SerialName("media_id") val mediaId: String,
        @SerialName("storage_path") val storagePath: String,
    )

    suspend fun reserve(
        securityEpochId: String,
        messageSyncId: String,
        plaintextBytes: Long,
        ciphertextBytes: Long,
        mediaCategory: String,
        contentExpiresAt: Long?,
    ): Reservation = client.postgrest.rpc(
        function = "reserve_chat_media_upload",
        parameters = buildJsonObject {
            put("p_security_epoch", securityEpochId)
            put("p_message_sync_id", messageSyncId)
            put("p_plaintext_bytes", plaintextBytes)
            put("p_ciphertext_bytes", ciphertextBytes)
            put("p_media_category", mediaCategory)
            if (contentExpiresAt == null) put("p_content_expires_at", JsonNull)
            else put("p_content_expires_at", Instant.ofEpochMilli(contentExpiresAt).toString())
        },
    ).decodeList<Reservation>().singleOrNull()
        ?: error("Chat media reservation was not created.")

    suspend fun upload(reservation: Reservation, ciphertext: ByteArray, ciphertextSha256: String) {
        client.storage.from(Bucket).upload(reservation.storagePath, ciphertext) {
            upsert = true
            contentType = ContentType.Application.OctetStream
        }
        client.postgrest.rpc(
            function = "finalize_chat_media_upload",
            parameters = buildJsonObject {
                put("p_media", reservation.mediaId)
                put("p_ciphertext_sha256", ciphertextSha256)
            },
        )
    }

    suspend fun signedDownloadUrl(mediaId: String): String {
        val path = client.postgrest.rpc(
            function = "chat_media_download_path",
            parameters = buildJsonObject { put("p_media", mediaId) },
        ).data.trim('"')
        return client.storage.from(Bucket).createSignedUrl(path, 15.minutes)
    }
}
