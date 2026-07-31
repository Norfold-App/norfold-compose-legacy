package com.norfold.app.domain.style

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Validates the hand-rolled sfnt parser against the **real packaged font binaries** (§21, §37.37–§37.44).
 *
 * Testing a font parser with synthetic bytes only proves it accepts what the test author imagined. These
 * tests parse the 65 files that are actually in the APK and cross-check the results against facts recorded
 * independently in the manifest (produced by fontTools at packaging time): if the offset arithmetic were
 * wrong, family names, weights, variable axes and Bengali coverage would disagree with it.
 */
class FontFileValidatorTest {

    private val facesDir = File("src/main/assets/fonts/faces")
    private val manifestFile = File("src/main/assets/fonts/manifest.json")
    private val manifest by lazy { FontManifestCodec.decode(manifestFile.readText()) }

    private fun faces(): List<File> =
        facesDir.listFiles().orEmpty().filter { it.isFile && it.extension == "ttf" }.sortedBy { it.name }

    private fun accept(file: File): FontValidation.Accepted {
        val result = FontFileValidator.validate(file.readBytes(), file.name)
        assertTrue("${file.name} was rejected: $result", result is FontValidation.Accepted)
        return result as FontValidation.Accepted
    }

    @Test
    fun `every packaged face validates`() {
        val files = faces()
        assumeTrue("packaged faces must be present", files.isNotEmpty())
        assertEquals("expected 65 packaged faces", 65, files.size)
        val rejected = files.mapNotNull { file ->
            (FontFileValidator.validate(file.readBytes(), file.name) as? FontValidation.Rejected)
                ?.let { "${file.name}: ${it.reason}" }
        }
        assertTrue("packaged faces rejected by the validator: $rejected", rejected.isEmpty())
    }

    /**
     * The `name` table parse must agree with the family names fontTools recorded.
     *
     * This is the test that catches a NameRecord offset mistake: a wrong offset still yields *a* string
     * (font tables are dense), so only comparing against independently-derived truth exposes it.
     */
    @Test
    fun `parsed family names match the manifest`() {
        assumeTrue(faces().isNotEmpty())
        val mismatches = mutableListOf<String>()
        for (family in manifest) {
            val expected = family.name.lowercase().filter { it.isLetterOrDigit() }
            for (face in family.faces) {
                val file = File(facesDir, face.asset)
                if (!file.isFile) continue
                val parsed = accept(file).family.lowercase().filter { it.isLetterOrDigit() }
                // Subsetting can qualify a name (e.g. a width suffix), so the manifest name must be a
                // prefix of what the file declares — but it may never be a different family.
                if (!parsed.startsWith(expected) && !expected.startsWith(parsed)) {
                    mismatches += "${face.asset}: parsed '$parsed' vs manifest '$expected'"
                }
            }
        }
        assertTrue(mismatches.joinToString("\n"), mismatches.isEmpty())
    }

    /** Variable faces must expose a `wght` axis — the axis the offline library pins per weight. */
    @Test
    fun `variable faces expose a weight axis`() {
        assumeTrue(faces().isNotEmpty())
        val variableFaces = manifest.flatMap { fam -> fam.faces.filter { it.variable }.map { fam.name to it } }
        assertTrue("expected variable faces in the packaged set", variableFaces.isNotEmpty())
        for ((familyName, face) in variableFaces) {
            val file = File(facesDir, face.asset)
            if (!file.isFile) continue
            val accepted = accept(file)
            assertTrue("$familyName ${face.asset} lost its variable flag", accepted.variable)
            assertTrue(
                "$familyName ${face.asset} has no wght axis; axes=${accepted.axes.map { it.tag }}",
                accepted.axes.any { it.isWeight },
            )
            val wght = accepted.axes.first { it.isWeight }
            assertTrue(
                "$familyName wght axis is degenerate: ${wght.min}..${wght.max}",
                wght.max > wght.min && wght.min >= 1f && wght.max <= 1000f,
            )
        }
    }

    /**
     * The four Bangla families must genuinely carry Bengali glyphs.
     *
     * This verifies the cmap parse against a real requirement rather than against itself: if
     * [FontFileValidator] read cmap subtable offsets incorrectly it would report no Bengali coverage here,
     * and the §22 fallback that depends on measured coverage would route Bangla to a Latin-only font.
     */
    @Test
    fun `bangla families cover bengali`() {
        assumeTrue(faces().isNotEmpty())
        for (id in GuaranteedFonts.BANGLA_FAMILY_IDS) {
            val family = manifest.firstOrNull { it.id == id }
            assertNotNull("manifest is missing Bangla family $id", family)
            val face = family!!.faces.first()
            val file = File(facesDir, face.asset)
            assumeTrue(file.isFile)
            val accepted = accept(file)
            assertTrue(
                "$id does not cover Bengali; scripts=${accepted.scripts}",
                FontScript.BENGALI in accepted.scripts,
            )
            // Spot-check specific codepoints, not just the range heuristic: KA, MA, the vowel sign AA.
            listOf(0x0995, 0x09AE, 0x09BE).forEach { cp ->
                assertTrue("$id is missing U+%04X".format(cp), cp in accepted.glyphCoverage)
            }
        }
    }

    /** Measured coverage must agree with what the manifest claims is packaged. */
    @Test
    fun `measured coverage matches manifest packaged scripts`() {
        assumeTrue(faces().isNotEmpty())
        for (family in manifest) {
            val file = File(facesDir, family.faces.first().asset)
            if (!file.isFile) continue
            val accepted = accept(file)
            assertEquals(
                "${family.id}: manifest Bengali claim disagrees with the file",
                FontScript.BENGALI in family.packagedScripts,
                FontScript.BENGALI in accepted.scripts,
            )
            assertTrue("${family.id} lost Latin coverage", FontScript.LATIN in accepted.scripts)
        }
    }

    /** Checksums recorded in the manifest must match the bytes on disk (§17 attribution integrity). */
    @Test
    fun `manifest checksums match packaged bytes`() {
        assumeTrue(faces().isNotEmpty())
        val bad = mutableListOf<String>()
        for (family in manifest) {
            for (face in family.faces) {
                val file = File(facesDir, face.asset)
                if (!file.isFile) continue
                val bytes = file.readBytes()
                if (FontFileValidator.sha256(bytes) != face.sha256) bad += face.asset
                if (bytes.size.toLong() != face.bytes) bad += "${face.asset} (size)"
            }
        }
        assertTrue("checksum/size mismatch: $bad", bad.isEmpty())
    }

    // ── Rejection paths ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `random bytes are rejected`() {
        val junk = ByteArray(4096) { (it * 31 % 251).toByte() }
        val result = FontFileValidator.validate(junk, "notes.txt")
        assertTrue("junk must be rejected", result is FontValidation.Rejected)
        assertTrue((result as FontValidation.Rejected).reason.isNotBlank())
    }

    @Test
    fun `empty and tiny files are rejected without throwing`() {
        assertTrue(FontFileValidator.validate(ByteArray(0), "a.ttf") is FontValidation.Rejected)
        assertTrue(FontFileValidator.validate(ByteArray(11), "a.ttf") is FontValidation.Rejected)
    }

    /**
     * A truncated font is rejected, not partially accepted.
     *
     * The failure mode this guards is the dangerous one: a table directory survives truncation and still
     * parses, so a parser that clamped out-of-range tables would read adjacent bytes as glyph data.
     */
    @Test
    fun `truncated font is rejected`() {
        val files = faces()
        assumeTrue(files.isNotEmpty())
        val whole = files.first().readBytes()
        val half = whole.copyOfRange(0, whole.size / 2)
        assertTrue(
            "a half-truncated font must be rejected",
            FontFileValidator.validate(half, files.first().name) is FontValidation.Rejected,
        )
    }

    /** A hostile table offset pointing past EOF must be refused rather than clamped. */
    @Test
    fun `table pointing outside the file is rejected`() {
        val files = faces()
        assumeTrue(files.isNotEmpty())
        val bytes = files.first().readBytes()
        // The first table record's offset field sits at 12 + 8; make it enormous.
        val at = 12 + 8
        bytes[at] = 0x7F
        bytes[at + 1] = 0x00
        bytes[at + 2] = 0x00
        bytes[at + 3] = 0x00
        assertTrue(
            "an out-of-range table offset must be rejected",
            FontFileValidator.validate(bytes, files.first().name) is FontValidation.Rejected,
        )
    }

    @Test
    fun `a zip or image masquerading as a font is rejected`() {
        val zip = byteArrayOf(0x50, 0x4B, 0x03, 0x04) + ByteArray(2048)
        val png = byteArrayOf(-119, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) + ByteArray(2048)
        assertTrue(FontFileValidator.validate(zip, "fonts.zip") is FontValidation.Rejected)
        assertTrue(FontFileValidator.validate(png, "sample.png") is FontValidation.Rejected)
    }

    // ── Identity and portability ─────────────────────────────────────────────────────────────────

    /** The same bytes always produce the same imported id, so re-import dedupes (§37.41). */
    @Test
    fun `imported id is content derived and stable`() {
        val files = faces()
        assumeTrue(files.isNotEmpty())
        val bytes = files.first().readBytes()
        val first = FontFileValidator.validate(bytes, "one.ttf") as FontValidation.Accepted
        val second = FontFileValidator.validate(bytes.copyOf(), "renamed.ttf") as FontValidation.Accepted
        assertEquals("id must not depend on the filename", first.importedId(), second.importedId())
        assertEquals(first.sha256, second.sha256)
        // Two different fonts must not collide.
        assumeTrue(files.size > 1)
        val other = accept(files[1])
        assertFalse(first.importedId() == other.importedId())
    }

    /**
     * An imported font defaults to private (§21).
     *
     * Asserted against the real binaries' own `fsType`, and then against the unknown/restricted cases,
     * because the guarantee that matters is "not knowing yields the safe answer".
     */
    @Test
    fun `imported portability is never silently shareable`() {
        val files = faces()
        assumeTrue(files.isNotEmpty())
        val results = files.take(12).map(::accept)
        for (accepted in results) {
            assertEquals("${accepted.family} imports remain private", FontPortability.PRIVATE_LOCAL, accepted.portability())
        }
        val unknown = results.first().copy(embedding = FontEmbedding.UNKNOWN)
        assertEquals(FontPortability.PRIVATE_LOCAL, unknown.portability())
        assertFalse(unknown.portability().redistributable)
        val restricted = results.first().copy(embedding = FontEmbedding.RESTRICTED)
        assertFalse("a restricted font must never be redistributable", restricted.portability().redistributable)
        val preview = results.first().copy(embedding = FontEmbedding.PREVIEW_AND_PRINT)
        assertEquals(FontPortability.PRIVATE_LOCAL, preview.portability())
    }

    @Test
    fun `oversized input is rejected before parsing`() {
        val huge = ByteArray(FontFileValidator.MAX_FILE_BYTES + 1)
        assertTrue(FontFileValidator.validate(huge, "huge.ttf") is FontValidation.Rejected)
    }

    @Test
    fun `stream validation enforces the same cap`() {
        val endless = object : java.io.InputStream() {
            override fun read(): Int = 0
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                b.fill(0, off, off + len)
                return len
            }
        }
        assertTrue(
            "an endless stream must be capped, not buffered forever",
            FontFileValidator.validate(endless, "endless.ttf") is FontValidation.Rejected,
        )
    }

    /** A real font read through the stream path must produce the same verdict as the byte path. */
    @Test
    fun `stream and byte paths agree`() {
        val files = faces()
        assumeTrue(files.isNotEmpty())
        val file = files.first()
        val fromBytes = accept(file)
        val fromStream = file.inputStream().use { FontFileValidator.validate(it, file.name) }
        assertTrue(fromStream is FontValidation.Accepted)
        assertEquals(fromBytes.sha256, (fromStream as FontValidation.Accepted).sha256)
        assertEquals(fromBytes.family, fromStream.family)
    }
}
