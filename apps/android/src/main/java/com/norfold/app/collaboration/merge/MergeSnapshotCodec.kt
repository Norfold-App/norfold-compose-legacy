package com.norfold.app.collaboration.merge

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import java.util.Base64

@Serializable
private data class MergeSnapshotPayload(
    val formatVersion: Int = 1,
    val engineId: String,
    val bytesBase64: String,
)

/** Bounded, versioned representation used inside an authorized collaboration snapshot row. */
object MergeSnapshotCodec {
    private val json = Json { encodeDefaults = true; explicitNulls = false; ignoreUnknownKeys = false }

    fun encode(snapshot: Snapshot): JsonElement = json.encodeToJsonElement(
        MergeSnapshotPayload.serializer(),
        MergeSnapshotPayload(
            engineId = snapshot.engineId,
            bytesBase64 = Base64.getEncoder().encodeToString(snapshot.bytes),
        ),
    )

    fun decode(payload: JsonElement, maxBytes: Int = 1024 * 1024): Snapshot {
        val value = json.decodeFromJsonElement(MergeSnapshotPayload.serializer(), payload)
        require(value.formatVersion == 1 && value.engineId.length in 1..128) { "Unsupported merge snapshot" }
        require(value.bytesBase64.length <= ((maxBytes + 2) / 3) * 4 + 4) { "Merge snapshot exceeds bound" }
        val bytes = Base64.getDecoder().decode(value.bytesBase64)
        require(bytes.size <= maxBytes) { "Merge snapshot exceeds bound" }
        return Snapshot(value.engineId, bytes)
    }
}
