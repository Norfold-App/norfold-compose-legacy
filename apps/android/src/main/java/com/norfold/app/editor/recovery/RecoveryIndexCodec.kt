package com.norfold.app.editor.recovery

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Codec for the recovery directory's index file.
 *
 * The index exists so listing restore points costs one small read instead of decoding every captured
 * document. It holds only metadata — ids, reasons, names, sizes — and never a document payload, so the
 * cheap path stays cheap and the file a browser reads most often carries no content.
 *
 * Like the record codec it is explicitly versioned and forgiving: an index this build cannot read is
 * treated as "no index", which degrades to an empty list rather than to a crash. The payload files are the
 * durable truth; the index is a cache of their headers.
 */
internal object RecoveryIndexCodec {
    const val SCHEMA_VERSION = 1

    private val format = Json {
        encodeDefaults = true
        explicitNulls = false
        ignoreUnknownKeys = true
        prettyPrint = false
    }

    fun encode(summaries: List<RecoverySnapshotSummary>): String = format.encodeToString(
        RecoveryIndexEnvelope.serializer(),
        RecoveryIndexEnvelope(
            schemaVersion = SCHEMA_VERSION,
            entries = summaries.map {
                RecoveryIndexEntry(
                    id = it.id,
                    reason = it.reason.name,
                    createdAt = it.createdAt,
                    label = it.label,
                    name = it.name,
                    description = it.description,
                    targetIds = it.targetIds,
                    scopeBlockIds = it.scopeBlockIds,
                    sizeBytes = it.sizeBytes,
                )
            },
        ),
    )

    fun decode(raw: String): List<RecoverySnapshotSummary> {
        val envelope = runCatching { format.decodeFromString(RecoveryIndexEnvelope.serializer(), raw) }
            .getOrNull() ?: return emptyList()
        if (envelope.schemaVersion > SCHEMA_VERSION) return emptyList()
        return envelope.entries.map { entry ->
            RecoverySnapshotSummary(
                id = entry.id,
                reason = RecoveryReason.entries.firstOrNull { it.name == entry.reason }
                    ?: RecoveryReason.DESTRUCTIVE_EDIT,
                createdAt = entry.createdAt,
                label = entry.label,
                name = entry.name,
                description = entry.description,
                targetIds = entry.targetIds,
                scopeBlockIds = entry.scopeBlockIds,
                sizeBytes = entry.sizeBytes,
            )
        }
    }
}

@Serializable
@SerialName("recovery_index")
internal data class RecoveryIndexEnvelope(
    val schemaVersion: Int,
    val entries: List<RecoveryIndexEntry> = emptyList(),
)

@Serializable
internal data class RecoveryIndexEntry(
    val id: String,
    val reason: String,
    val createdAt: Long,
    val label: String = "",
    val name: String? = null,
    val description: String = "",
    val targetIds: List<String> = emptyList(),
    val scopeBlockIds: List<String> = emptyList(),
    val sizeBytes: Long = 0,
)
