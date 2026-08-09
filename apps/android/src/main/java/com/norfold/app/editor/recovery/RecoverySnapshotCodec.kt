package com.norfold.app.editor.recovery

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.security.MessageDigest

/** Outcome of reading one stored recovery record. Corruption is a value, not an exception. */
sealed interface RecoveryDecodeResult {
    data class Success(val snapshot: RecoverySnapshot) : RecoveryDecodeResult

    /** Written by a newer build. Kept on disk untouched rather than deleted or guessed at. */
    data class UnsupportedVersion(val version: Int) : RecoveryDecodeResult

    /** Unreadable or failed integrity verification. [reason] is diagnostic text, never document content. */
    data class Corrupt(val reason: String) : RecoveryDecodeResult
}

/**
 * Versioned on-disk representation of a [RecoverySnapshot] (spec §13.7; Phase-10 durability).
 *
 * Three properties matter more than compactness here:
 *
 *  - **Explicit schema version.** [SCHEMA_VERSION] is read *before* the record is decoded, so a snapshot
 *    written by a newer build is reported as [RecoveryDecodeResult.UnsupportedVersion] instead of throwing
 *    or, worse, decoding partially into something plausible-looking.
 *  - **Integrity, not encryption.** Every record carries a SHA-256 [checksum] over its canonical content.
 *    A truncated write or a bit flip is detected at read time and the record is refused rather than
 *    restored. This is deliberately *not* crypto: recovery data lives in app-private storage and this file
 *    introduces no cipher, no key derivation and no new dependency.
 *  - **Unknown reasons degrade.** [reason] is stored as a string so a snapshot taken by a future build with
 *    a new [RecoveryReason] still lists and still restores, mapped to [RecoveryReason.DESTRUCTIVE_EDIT] —
 *    the conservative choice, since it is treated as automatic and therefore never displaces a named point.
 */
object RecoverySnapshotCodec {
    const val SCHEMA_VERSION = 1

    private val format = Json {
        encodeDefaults = true
        explicitNulls = false
        ignoreUnknownKeys = true
        prettyPrint = false
    }

    fun encode(snapshot: RecoverySnapshot): String = format.encodeToString(
        RecoverySnapshotEnvelope.serializer(),
        RecoverySnapshotEnvelope(
            schemaVersion = SCHEMA_VERSION,
            id = snapshot.id,
            reason = snapshot.reason.name,
            createdAt = snapshot.createdAt,
            label = snapshot.label,
            name = snapshot.name,
            description = snapshot.description,
            scopeBlockIds = snapshot.scopeBlockIds,
            documents = snapshot.documents,
            checksum = checksum(snapshot),
        ),
    )

    fun decode(raw: String): RecoveryDecodeResult {
        val version = runCatching {
            format.parseToJsonElement(raw).jsonObject["schemaVersion"]?.jsonPrimitive?.intOrNull
        }.getOrNull() ?: return RecoveryDecodeResult.Corrupt("Recovery record has no readable schema version")
        if (version > SCHEMA_VERSION) return RecoveryDecodeResult.UnsupportedVersion(version)

        val envelope = runCatching { format.decodeFromString(RecoverySnapshotEnvelope.serializer(), raw) }
            .getOrElse { return RecoveryDecodeResult.Corrupt("Recovery record could not be decoded") }
        if (envelope.documents.isEmpty()) return RecoveryDecodeResult.Corrupt("Recovery record captured no documents")

        val snapshot = RecoverySnapshot(
            id = envelope.id,
            reason = reasonOf(envelope.reason),
            createdAt = envelope.createdAt,
            documents = envelope.documents,
            label = envelope.label,
            name = envelope.name,
            description = envelope.description,
            scopeBlockIds = envelope.scopeBlockIds,
        )
        if (envelope.checksum.isNotEmpty() && envelope.checksum != checksum(snapshot)) {
            return RecoveryDecodeResult.Corrupt("Recovery record failed integrity verification")
        }
        return RecoveryDecodeResult.Success(snapshot)
    }

    /**
     * SHA-256 over a canonical rendering of the record's content.
     *
     * Target ids are sorted so map iteration order cannot change the digest, and every field that a restore
     * depends on is included — a checksum that covered only the payloads would not notice a scope list that
     * had been truncated on disk.
     */
    fun checksum(snapshot: RecoverySnapshot): String {
        val canonical = buildString {
            append(snapshot.id).append(RECORD_SEPARATOR)
            append(snapshot.reason.name).append(RECORD_SEPARATOR)
            append(snapshot.createdAt).append(RECORD_SEPARATOR)
            append(snapshot.scopeBlockIds.joinToString(",")).append(RECORD_SEPARATOR)
            snapshot.documents.entries.sortedBy { it.key }.forEach { (target, payload) ->
                append(target).append(FIELD_SEPARATOR).append(payload).append(RECORD_SEPARATOR)
            }
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    /** Separators that cannot occur inside a JSON payload string, so the digest input is unambiguous. */
    private const val RECORD_SEPARATOR = '\u0000'
    private const val FIELD_SEPARATOR = '\u0001'

    private fun reasonOf(raw: String): RecoveryReason =
        RecoveryReason.entries.firstOrNull { it.name == raw } ?: RecoveryReason.DESTRUCTIVE_EDIT
}

@Serializable
@SerialName("recovery_snapshot")
internal data class RecoverySnapshotEnvelope(
    val schemaVersion: Int,
    val id: String,
    val reason: String,
    val createdAt: Long,
    val label: String = "",
    val name: String? = null,
    val description: String = "",
    val scopeBlockIds: List<String> = emptyList(),
    val documents: Map<String, String> = emptyMap(),
    val checksum: String = "",
)
