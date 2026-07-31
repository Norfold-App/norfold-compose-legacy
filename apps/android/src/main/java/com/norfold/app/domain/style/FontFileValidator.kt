package com.norfold.app.domain.style

import java.io.InputStream
import java.security.MessageDigest

/**
 * Validates a user-supplied font file **before** it becomes state (roadmap Phase 3 §21).
 *
 * The rule §21 states is "never parse a font directly into authoritative state before validation
 * completes", and this class is the gate that makes it structural. It parses only the table directory and
 * the `name`/`OS/2`/`fvar`/`cmap` tables it needs — enough to answer "is this a font, what is it called,
 * what does it cover, may it be embedded" — and it does so from a bounded byte array, never from a live
 * content-provider stream.
 *
 * **Why hand-rolled rather than a library.** Two reasons that both matter here: the app has no font-parsing
 * dependency and adding one for this would put third-party code in the path of untrusted user files; and
 * the validation this needs is deliberately *shallow* — a full shaper-grade parse would accept files this
 * refuses and would itself be the attack surface. Everything below is bounds-checked, allocation-bounded,
 * and returns a rejection rather than throwing.
 *
 * Pure JVM, so a malformed-file test needs no device.
 */
object FontFileValidator {

    /** Hard ceiling on an imported file. A legitimate family face is far below this. */
    const val MAX_FILE_BYTES = 32 * 1024 * 1024

    /** Sanity ceiling on the table count, to bound allocation from a hostile header. */
    private const val MAX_TABLES = 512

    /** sfnt version tags this accepts. */
    private const val TAG_TRUE_TYPE = 0x00010000
    private const val TAG_OTTO = 0x4F54544F // 'OTTO' — CFF outlines
    private const val TAG_TRUE = 0x74727565 // 'true' — legacy Apple
    private const val TAG_TTCF = 0x74746366 // 'ttcf' — collection

    /**
     * Validates [bytes] and returns either a description of the font or a rejection.
     *
     * Never throws: every failure path is a [FontValidation.Rejected] with a reason a user can act on,
     * because "the file you picked could not be read" with no explanation is indistinguishable from a bug.
     */
    fun validate(bytes: ByteArray, fileName: String = ""): FontValidation {
        if (bytes.size < 12) return reject("This file is too small to be a font.")
        if (bytes.size > MAX_FILE_BYTES) return reject("Font files must be under 32 MB.")

        return runCatching { parse(bytes, fileName) }
            .getOrElse { reject("This file isn't a font Norfold can read.") }
    }

    private fun parse(bytes: ByteArray, fileName: String): FontValidation {
        val reader = SfntReader(bytes)
        var version = reader.uint32(0)

        // A collection (.ttc/.otc): validate its first face. Importing one face of a collection is the
        // honest behaviour — pretending to import all of them would install faces the user cannot see.
        var directoryOffset = 0L
        if (version == TAG_TTCF.toLong()) {
            if (bytes.size < 16) return reject("This font collection is incomplete.")
            val faceCount = reader.uint32(8)
            if (faceCount < 1) return reject("This font collection contains no fonts.")
            directoryOffset = reader.uint32(12)
            if (directoryOffset + 12 > bytes.size) return reject("This font collection is incomplete.")
            version = reader.uint32(directoryOffset.toInt())
        }

        val format = when (version) {
            TAG_TRUE_TYPE.toLong(), TAG_TRUE.toLong() -> FontFileFormat.TRUETYPE
            TAG_OTTO.toLong() -> FontFileFormat.OPENTYPE_CFF
            else -> return reject("This isn't a TrueType or OpenType font file.")
        }

        val base = directoryOffset.toInt()
        val tableCount = reader.uint16(base + 4)
        if (tableCount == 0) return reject("This font has no data tables.")
        if (tableCount > MAX_TABLES) return reject("This font's structure is not valid.")

        val tables = HashMap<String, IntRange>(tableCount)
        for (i in 0 until tableCount) {
            val entry = base + 12 + i * 16
            if (entry + 16 > bytes.size) return reject("This font's table list is truncated.")
            val tag = reader.tag(entry)
            val offset = reader.uint32(entry + 8)
            val length = reader.uint32(entry + 12)
            // A table pointing outside the file is the classic malformed/hostile case; refuse the file
            // rather than clamping, because a clamped table would be parsed as if it were complete.
            if (offset < 0 || length < 0 || offset + length > bytes.size) {
                return reject("This font's data is damaged or incomplete.")
            }
            tables[tag] = offset.toInt() until (offset + length).toInt()
        }

        // A font without outlines is not a font we can render, however well-formed its header is.
        val hasOutlines = "glyf" in tables || "CFF " in tables || "CFF2" in tables
        if (!hasOutlines) return reject("This font contains no glyph outlines.")
        val cmap = tables["cmap"] ?: return reject("This font has no character map.")
        val nameTable = tables["name"] ?: return reject("This font has no name table.")

        val names = readNames(reader, nameTable)
        val family = names[NAME_TYPOGRAPHIC_FAMILY]?.takeIf(String::isNotBlank)
            ?: names[NAME_FAMILY]?.takeIf(String::isNotBlank)
            ?: fileName.substringBeforeLast('.').replace('_', ' ').trim().takeIf(String::isNotBlank)
            ?: return reject("This font doesn't declare a family name.")
        val subfamily = names[NAME_TYPOGRAPHIC_SUBFAMILY] ?: names[NAME_SUBFAMILY] ?: "Regular"

        val axes = tables["fvar"]?.let { readAxes(reader, it) }.orEmpty()
        val codepoints = readCmapCodepoints(reader, cmap)
        val scripts = scriptsOf(codepoints)
        if (scripts.isEmpty()) return reject("This font covers no characters Norfold can use.")

        val os2 = tables["OS/2"]
        val embedding = os2?.let { readEmbedding(reader, it) } ?: FontEmbedding.UNKNOWN
        val weight = os2?.let { reader.uint16(it.first + 4).takeIf { w -> w in 1..1000 } }
            ?: weightFromSubfamily(subfamily)
        val italic = (os2?.let { reader.uint16(it.first + 62) and 0x01 } ?: 0) == 1 ||
            subfamily.contains("italic", ignoreCase = true)

        return FontValidation.Accepted(
            family = family.trim(),
            subfamily = subfamily.trim(),
            format = format,
            variable = axes.isNotEmpty(),
            axes = axes,
            weight = weight.coerceIn(100, 900),
            italic = italic,
            scripts = scripts,
            glyphCoverage = codepoints,
            embedding = embedding,
            sha256 = sha256(bytes),
            bytes = bytes.size.toLong(),
        )
    }

    /** Streamed entry point with a hard byte cap, so a huge or endless stream cannot exhaust memory. */
    fun validate(stream: InputStream, fileName: String = ""): FontValidation {
        val buffer = ByteArray(64 * 1024)
        val out = java.io.ByteArrayOutputStream()
        var total = 0
        while (true) {
            val read = runCatching { stream.read(buffer) }.getOrElse {
                return reject("This file couldn't be read.")
            }
            if (read < 0) break
            total += read
            if (total > MAX_FILE_BYTES) return reject("Font files must be under 32 MB.")
            out.write(buffer, 0, read)
        }
        return validate(out.toByteArray(), fileName)
    }

    fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }

    private fun reject(reason: String) = FontValidation.Rejected(reason)

    // ── Table readers ────────────────────────────────────────────────────────────────────────────

    private const val NAME_FAMILY = 1
    private const val NAME_SUBFAMILY = 2
    private const val NAME_TYPOGRAPHIC_FAMILY = 16
    private const val NAME_TYPOGRAPHIC_SUBFAMILY = 17

    /**
     * Reads the `name` table, preferring Windows-Unicode records.
     *
     * Only the four name ids that matter are kept, and each string is length-capped — a name table is
     * attacker-controlled data and a family name is not allowed to be a megabyte.
     */
    private fun readNames(reader: SfntReader, table: IntRange): Map<Int, String> {
        val base = table.first
        val count = reader.uint16(base + 2)
        val stringOffset = base + reader.uint16(base + 4)
        val result = HashMap<Int, String>()
        for (i in 0 until minOf(count, 1024)) {
            // NameRecord layout: platformID, encodingID, languageID, nameID, length, offset — 6 uint16s.
            val record = base + 6 + i * 12
            if (record + 12 > table.last + 1) break
            val platform = reader.uint16(record)
            val encoding = reader.uint16(record + 2)
            val nameId = reader.uint16(record + 6)
            if (nameId !in NAME_IDS) continue
            val length = reader.uint16(record + 8).coerceAtMost(512)
            val start = stringOffset + reader.uint16(record + 10)
            if (length == 0 || start < 0 || start + length > reader.size) continue
            val text = when {
                platform == 3 || (platform == 0 && encoding >= 3) ->
                    String(reader.bytes, start, length, Charsets.UTF_16BE)
                platform == 0 -> String(reader.bytes, start, length, Charsets.UTF_16BE)
                platform == 1 -> String(reader.bytes, start, length, Charsets.ISO_8859_1)
                else -> continue
            }.trim().takeIf(String::isNotBlank) ?: continue
            // Windows-Unicode records win; a Mac record only fills a gap.
            if (platform == 3 || nameId !in result) result[nameId] = text
        }
        return result
    }

    private val NAME_IDS =
        setOf(NAME_FAMILY, NAME_SUBFAMILY, NAME_TYPOGRAPHIC_FAMILY, NAME_TYPOGRAPHIC_SUBFAMILY)

    /** Reads `fvar` axis tags and ranges. */
    private fun readAxes(reader: SfntReader, table: IntRange): List<FontAxis> {
        val base = table.first
        val axisOffset = base + reader.uint16(base + 4)
        val axisCount = reader.uint16(base + 8).coerceAtMost(32)
        val axisSize = reader.uint16(base + 10)
        if (axisSize < 20) return emptyList()
        return (0 until axisCount).mapNotNull { i ->
            val entry = axisOffset + i * axisSize
            if (entry + 20 > table.last + 1) return@mapNotNull null
            FontAxis(
                tag = reader.tag(entry),
                min = reader.fixed(entry + 4),
                default = reader.fixed(entry + 8),
                max = reader.fixed(entry + 12),
            )
        }
    }

    /**
     * Counts the codepoints the `cmap` covers, reading formats 4 and 12 only.
     *
     * Those two carry essentially every modern font's Unicode mapping; older formats are accepted as a
     * *font* but contribute no counted coverage, which is why the result is used as a coverage *signal*
     * rather than a precise glyph inventory.
     */
    private fun readCmapCodepoints(reader: SfntReader, table: IntRange): Set<Int> {
        val base = table.first
        val tableCount = reader.uint16(base + 2).coerceAtMost(64)
        val result = HashSet<Int>()
        for (i in 0 until tableCount) {
            val record = base + 4 + i * 8
            if (record + 8 > table.last + 1) continue
            val subtableOffset = base + reader.uint32(record + 4).toInt()
            if (subtableOffset < 0 || subtableOffset + 4 > reader.size) continue
            when (reader.uint16(subtableOffset)) {
                4 -> readCmap4(reader, subtableOffset, result)
                12 -> readCmap12(reader, subtableOffset, result)
                else -> Unit
            }
            if (result.size > MAX_COUNTED_CODEPOINTS) break
        }
        return result
    }

    private const val MAX_COUNTED_CODEPOINTS = 200_000

    private fun readCmap4(reader: SfntReader, offset: Int, sink: MutableSet<Int>) {
        val segCountX2 = reader.uint16(offset + 6)
        val segCount = segCountX2 / 2
        val endBase = offset + 14
        val startBase = endBase + segCountX2 + 2
        for (s in 0 until segCount) {
            if (startBase + s * 2 + 2 > reader.size) return
            val end = reader.uint16(endBase + s * 2)
            val start = reader.uint16(startBase + s * 2)
            if (start > end || start == 0xFFFF) continue
            for (c in start..minOf(end, 0xFFFE)) {
                sink += c
                if (sink.size > MAX_COUNTED_CODEPOINTS) return
            }
        }
    }

    private fun readCmap12(reader: SfntReader, offset: Int, sink: MutableSet<Int>) {
        val groupCount = reader.uint32(offset + 12).toInt().coerceAtMost(20_000)
        for (g in 0 until groupCount) {
            val entry = offset + 16 + g * 12
            if (entry + 12 > reader.size) return
            val start = reader.uint32(entry).toInt()
            val end = reader.uint32(entry + 4).toInt()
            if (start > end || start < 0 || end > 0x10FFFF) continue
            for (c in start..end) {
                sink += c
                if (sink.size > MAX_COUNTED_CODEPOINTS) return
            }
        }
    }

    /** `OS/2` fsType embedding permissions. */
    private fun readEmbedding(reader: SfntReader, table: IntRange): FontEmbedding {
        if (table.first + 10 > reader.size) return FontEmbedding.UNKNOWN
        val fsType = reader.uint16(table.first + 8)
        return when {
            // Bit 1 set alone means "restricted": the font may not be embedded at all.
            fsType and 0x0002 != 0 -> FontEmbedding.RESTRICTED
            fsType and 0x0004 != 0 -> FontEmbedding.PREVIEW_AND_PRINT
            fsType and 0x0008 != 0 -> FontEmbedding.EDITABLE
            fsType == 0 -> FontEmbedding.INSTALLABLE
            else -> FontEmbedding.UNKNOWN
        }
    }

    /** Which of the scripts this app models are meaningfully covered. */
    private fun scriptsOf(codepoints: Set<Int>): Set<FontScript> {
        fun covers(range: IntRange, minimum: Int = 24) = codepoints.count { it in range } >= minimum
        return buildSet {
            if (covers(0x0041..0x024F)) add(FontScript.LATIN)
            if (covers(0x0980..0x09FE)) add(FontScript.BENGALI)
            if (covers(0x0400..0x04FF)) add(FontScript.CYRILLIC)
            if (covers(0x0370..0x03FF)) add(FontScript.GREEK)
            if (covers(0x0900..0x097F)) add(FontScript.DEVANAGARI)
            if (covers(0x0600..0x06FF)) add(FontScript.ARABIC)
        }
    }

    private fun weightFromSubfamily(subfamily: String): Int = when {
        subfamily.contains("thin", true) -> 100
        subfamily.contains("extralight", true) || subfamily.contains("ultralight", true) -> 200
        subfamily.contains("light", true) -> 300
        subfamily.contains("medium", true) -> 500
        subfamily.contains("semibold", true) || subfamily.contains("demibold", true) -> 600
        subfamily.contains("extrabold", true) || subfamily.contains("ultrabold", true) -> 800
        subfamily.contains("black", true) || subfamily.contains("heavy", true) -> 900
        subfamily.contains("bold", true) -> 700
        else -> 400
    }

    /** Bounds-checked big-endian reader over an in-memory font file. */
    private class SfntReader(val bytes: ByteArray) {
        val size: Int get() = bytes.size

        fun uint8(at: Int): Int {
            require(at in bytes.indices) { "read out of bounds" }
            return bytes[at].toInt() and 0xFF
        }

        fun uint16(at: Int): Int = (uint8(at) shl 8) or uint8(at + 1)

        fun uint32(at: Int): Long =
            (uint16(at).toLong() shl 16) or uint16(at + 2).toLong()

        /** 16.16 fixed point, as `fvar` axis values use. */
        fun fixed(at: Int): Float {
            val whole = (uint16(at).toShort()).toInt()
            val frac = uint16(at + 2)
            return whole + frac / 65536f
        }

        fun tag(at: Int): String = String(bytes, at, 4, Charsets.ISO_8859_1)
    }
}

/** The outcome of validating a candidate font file. */
sealed interface FontValidation {

    /** A readable font. Everything here was parsed from the file, not assumed. */
    data class Accepted(
        val family: String,
        val subfamily: String,
        val format: FontFileFormat,
        val variable: Boolean,
        val axes: List<FontAxis>,
        val weight: Int,
        val italic: Boolean,
        val scripts: Set<FontScript>,
        val glyphCoverage: Set<Int>,
        val embedding: FontEmbedding,
        val sha256: String,
        val bytes: Long,
    ) : FontValidation {
        /**
         * A stable id derived from the content hash.
         *
         * Content-derived, so re-importing the same file is recognized as the same font (§37.41) and two
         * genuinely different files that happen to share a family name stay distinct — a name-derived id
         * would silently overwrite one with the other.
         */
        fun importedId(): String = "imported-${sha256.take(16)}"

        fun identity(): FontIdentity = FontIdentity.imported(importedId(), family)

        /**
         * Import transport state is always [FontPortability.PRIVATE_LOCAL] in Phase 3.
         *
         * The parsed [embedding] field is retained independently for a future explicit export decision, but
         * an OS/2 `fsType` permission is not user consent to upload or redistribute a private import. Keeping
         * those facts separate prevents a technically embeddable file from silently entering collaboration.
         */
        fun portability(): FontPortability = FontPortability.PRIVATE_LOCAL

        fun accessibleSummary(): String = buildString {
            append(family)
            if (subfamily.isNotBlank() && !subfamily.equals("regular", true)) append(" $subfamily")
            append(", ")
            append(format.label)
            if (variable) append(", variable (${axes.joinToString(", ") { it.tag }})")
            append(", ")
            append(scripts.joinToString(", ") { it.label })
            append(", ")
            append(embedding.label)
        }
    }

    /** Not a usable font. [reason] is user-facing. */
    data class Rejected(val reason: String) : FontValidation

    val isAccepted: Boolean get() = this is Accepted
}

@kotlinx.serialization.Serializable
enum class FontFileFormat(val label: String, val extension: String) {
    TRUETYPE("TrueType", "ttf"),
    OPENTYPE_CFF("OpenType", "otf"),
}

/** One variable-font axis. */
@kotlinx.serialization.Serializable
data class FontAxis(val tag: String, val min: Float, val default: Float, val max: Float) {
    val isWeight: Boolean get() = tag == "wght"
}

/** `OS/2` fsType embedding permission, recorded so a later export can honour it. */
@kotlinx.serialization.Serializable
enum class FontEmbedding(val label: String) {
    INSTALLABLE("Embedding allowed"),
    EDITABLE("Editable embedding allowed"),
    PREVIEW_AND_PRINT("Preview and print only"),
    RESTRICTED("Embedding not permitted"),
    UNKNOWN("Embedding permissions unknown"),
}
