package com.norfold.app.domain.style

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The record of every font the user has imported (roadmap Phase 3 §21, §37.37–§37.44).
 *
 * Pure data with no Android dependency, so the whole import *policy* — dedupe, removal, what a stale
 * reference resolves to — is unit-testable without a device or a content provider.
 *
 * **Why the index is authoritative and the directory is not.** The obvious implementation lists the files
 * on disk and derives the catalogue from them, which means every launch re-parses every imported binary and
 * a half-written file becomes a half-valid font. Instead the index is written *after* a file is in place and
 * validated, so an entry existing is proof that validation completed (§21's "never parse a font directly
 * into authoritative state before validation completes"). A file with no entry is debris the store deletes;
 * an entry with no file is a [FontAvailability.MISSING] reference, which is exactly what §37.42 requires —
 * removal degrades to Missing/Substituted and never to corruption.
 */
@Serializable
data class ImportedFontIndex(
    val entries: List<ImportedFontEntry> = emptyList(),
) {
    fun find(id: String): ImportedFontEntry? = entries.firstOrNull { it.id == id }

    /** Content-hash lookup — the dedupe question "have I already got these exact bytes?" (§37.41). */
    fun findByChecksum(sha256: String): ImportedFontEntry? =
        entries.firstOrNull { it.sha256.equals(sha256, ignoreCase = true) }

    /**
     * Adds [entry], or returns this index unchanged when the same bytes are already present.
     *
     * Re-importing the same file is a no-op rather than a second family, because a user who picks the same
     * file twice meant "make sure it's there", not "give me two identical fonts to tell apart".
     */
    fun withEntry(entry: ImportedFontEntry): ImportedFontIndex = when {
        findByChecksum(entry.sha256) != null -> this
        else -> copy(entries = entries.filterNot { it.id == entry.id } + entry)
    }

    fun without(id: String): ImportedFontIndex = copy(entries = entries.filterNot { it.id == id })

    /**
     * Groups entries into family records, resolving each entry's file through [pathOf].
     *
     * Faces are grouped by family *id*, so two files of the same family (Regular and Bold) become one
     * browsable family with two faces rather than two families that look like duplicates.
     */
    fun toRecords(pathOf: (ImportedFontEntry) -> String): List<FontFamilyRecord> =
        entries.groupBy { it.familyId }
            .map { (familyId, group) ->
                val first = group.first()
                FontFamilyRecord(
                    id = familyId,
                    name = first.family,
                    source = FontSource.IMPORTED,
                    category = first.category,
                    roles = emptySet(),
                    packagedScripts = group.flatMap { it.scripts }.toSet().ifEmpty { setOf(FontScript.LATIN) },
                    faces = group.map { entry ->
                        FontFaceRecord(
                            asset = pathOf(entry),
                            weight = entry.weight,
                            italic = entry.italic,
                            variable = entry.variable,
                            sha256 = entry.sha256,
                            bytes = entry.bytes,
                            upstreamFile = entry.fileName,
                        )
                    },
                    license = FontLicense.UNKNOWN,
                    portability = first.portability,
                    upstreamSource = "Imported by you",
                    glyphCoverage = group.sumOf { it.glyphCount },
                )
            }
            .sortedBy { it.name.lowercase() }

    fun encode(): String = json.encodeToString(this)

    companion object {
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = false }

        /** Reads a stored index. Unreadable content yields an empty index, never an exception. */
        fun decode(text: String?): ImportedFontIndex {
            val payload = text?.takeIf(String::isNotBlank) ?: return ImportedFontIndex()
            return runCatching { json.decodeFromString<ImportedFontIndex>(payload) }
                .getOrElse { ImportedFontIndex() }
        }
    }
}

/**
 * One imported face.
 *
 * [familyId] is derived from the *family name* while [id] is derived from the *content hash*, so two files
 * of one family group together while two genuinely different files never merge.
 */
@Serializable
data class ImportedFontEntry(
    val id: String,
    val familyId: String,
    val family: String,
    val subfamily: String,
    /** Storage filename inside the imported-font directory — never the user's original path. */
    val fileName: String,
    val sha256: String,
    val bytes: Long,
    val weight: Int = 400,
    val italic: Boolean = false,
    val variable: Boolean = false,
    val axes: List<FontAxis> = emptyList(),
    val scripts: Set<FontScript> = setOf(FontScript.LATIN),
    val category: FontCategory = FontCategory.SANS,
    val embedding: FontEmbedding = FontEmbedding.UNKNOWN,
    val portability: FontPortability = FontPortability.PRIVATE_LOCAL,
    val glyphCount: Int = 0,
    val importedAt: Long = 0L,
) {
    val identity: FontIdentity get() = FontIdentity.imported(familyId, family)

    companion object {
        /**
         * Builds an entry from a completed validation.
         *
         * Everything recorded here was *parsed from the file*; nothing is taken from the picked filename
         * except as a last-resort family name inside the validator. Embedding permission remains recorded
         * separately; the Phase-3 import itself is always private and never silently uploaded.
         */
        fun of(
            accepted: FontValidation.Accepted,
            fileName: String,
            importedAt: Long,
        ): ImportedFontEntry = ImportedFontEntry(
            id = accepted.importedId(),
            familyId = familyIdOf(accepted.family),
            family = accepted.family,
            subfamily = accepted.subfamily,
            fileName = fileName,
            sha256 = accepted.sha256,
            bytes = accepted.bytes,
            weight = accepted.weight,
            italic = accepted.italic,
            variable = accepted.variable,
            axes = accepted.axes,
            scripts = accepted.scripts,
            category = FontCategory.SANS,
            embedding = accepted.embedding,
            portability = accepted.portability(),
            glyphCount = accepted.glyphCoverage.size,
            importedAt = importedAt,
        )

        /** A slug of the family name, so faces of one family share an id across separate imports. */
        fun familyIdOf(family: String): String {
            val slug = family.lowercase().map { if (it.isLetterOrDigit()) it else '-' }
                .joinToString("").trim('-').replace(Regex("-+"), "-")
            return "user-" + slug.ifEmpty { "font" }
        }

        /** The on-disk filename for [sha256]: content-addressed, so a name collision is impossible. */
        fun storageNameFor(sha256: String, extension: String): String =
            "${sha256.take(32)}.${extension.ifBlank { "ttf" }}"
    }
}
