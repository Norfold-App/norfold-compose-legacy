package com.norfold.app.domain.style

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * The packaged font library and its resolver (roadmap Phase 3 §15–§18, §22, §37.29–§37.36).
 *
 * These tests read the manifest that ships in the APK, so they assert facts about the *actual* packaged
 * library rather than about a fixture. The most important of them — [the guaranteed set is complete] — is
 * what makes §16's "do not silently cut the required family set to make the APK smaller" a build failure
 * instead of a code review someone has to remember to do.
 */
class FontRegistryTest {

    private val manifestFile = File("src/main/assets/fonts/manifest.json")
    private val facesDir = File("src/main/assets/fonts/faces")
    private val licensesDir = File("src/main/assets/fonts/licenses")

    private val packaged: List<FontFamilyRecord> by lazy {
        FontManifestCodec.decode(manifestFile.readText())
    }
    private val registry: FontRegistry by lazy {
        FontRegistry(packaged + GuaranteedFonts.SYSTEM_FAMILIES)
    }

    // ── §37.29 the packaging contract ────────────────────────────────────────────────────────────

    /**
     * All 30 guaranteed families are packaged, with files behind every declared face.
     *
     * The id list in [GuaranteedFonts.REQUIRED_FAMILY_IDS] is the contract; dropping a family to save
     * bytes breaks this test rather than quietly shrinking the library.
     */
    @Test
    fun `the guaranteed set is complete and every face has a file`() {
        assumeTrue("manifest must be packaged", manifestFile.isFile)
        val packagedIds = packaged.map { it.id }.toSet()
        val missing = GuaranteedFonts.REQUIRED_FAMILY_IDS.filterNot { it in packagedIds }
        assertTrue("guaranteed families missing from the manifest: $missing", missing.isEmpty())
        assertEquals("the manifest must contain exactly the guaranteed set", 30, packaged.size)

        val absent = packaged.flatMap { family ->
            family.faces.filterNot { File(facesDir, it.asset).isFile }.map { "${family.id}/${it.asset}" }
        }
        assertTrue("manifest declares faces with no packaged file: $absent", absent.isEmpty())
    }

    /** Every family must declare a redistributable license and ship its text (§17). */
    @Test
    fun `every packaged family has a permissive license and its text`() {
        assumeTrue(manifestFile.isFile)
        for (family in packaged) {
            assertTrue(
                "${family.id} has license ${family.license} which does not permit redistribution",
                family.license.embeddingPermitted,
            )
            assertTrue(
                "${family.id} has an unrecorded license",
                family.license == FontLicense.OFL || family.license == FontLicense.APACHE_2,
            )
            val licenseFile = family.licenseFile
            assertNotNull("${family.id} has no license file recorded", licenseFile)
            val file = File(facesDir.parentFile, licenseFile!!)
            assertTrue("${family.id}: license text ${licenseFile} is not packaged", file.isFile)
            assertTrue("${family.id}: license text is empty", file.length() > 200)
        }
        assertEquals(
            "one license text per family",
            30,
            licensesDir.listFiles().orEmpty().count { it.isFile },
        )
    }

    /** A bundled family is portable by reference and never needs its bytes shipped anywhere (§21). */
    @Test
    fun `bundled families are reference shareable and not redistributable`() {
        for (family in packaged) {
            assertEquals(FontSource.BUILT_IN, family.source)
            assertEquals(FontPortability.BUILT_IN, family.portability)
            assertTrue("a bundled family's reference may travel", family.portability.referenceShareable)
            assertFalse("but its bytes are never sent", family.portability.redistributable)
        }
    }

    // ── §37.30 Bangla coverage ───────────────────────────────────────────────────────────────────

    /** The four Bangla families are present and their *measured* coverage includes Bengali. */
    @Test
    fun `bangla is covered by four packaged families`() {
        assumeTrue(manifestFile.isFile)
        val bangla = registry.banglaFamilies()
        assertTrue(
            "expected at least the four Bangla families, found ${bangla.map { it.id }}",
            bangla.size >= 4,
        )
        for (id in GuaranteedFonts.BANGLA_FAMILY_IDS) {
            val family = packaged.firstOrNull { it.id == id }
            assertNotNull("Bangla family $id is missing", family)
            assertTrue("$id must cover Bengali", family!!.covers(FontScript.BENGALI))
        }
    }

    /**
     * Bangla text asking for a Latin-only family substitutes to a Bangla family.
     *
     * This is the case that makes measured coverage matter: upstream Noto Sans advertises Bengali, but the
     * copy in this APK is subset to Latin, so a resolver trusting the upstream claim would route Bangla to
     * a font with no Bengali glyphs and render tofu.
     */
    @Test
    fun `bangla text substitutes away from a latin only family`() {
        assumeTrue(manifestFile.isFile)
        val latinOnly = packaged.first { !it.covers(FontScript.BENGALI) }
        val resolution = registry.resolve(FontRequest(latinOnly.identity), "আমার সোনার বাংলা")
        assertNotNull("Bangla text must resolve to something renderable", resolution.resolved)
        val resolved = registry.find(resolution.resolved!!)
        assertNotNull(resolved)
        assertTrue("the substitute must actually cover Bengali", resolved!!.covers(FontScript.BENGALI))
        assertNotNull("the substitution must be reported, not hidden", resolution.substitution)
        assertEquals(SubstitutionReason.SCRIPT_NOT_COVERED, resolution.substitution!!.reason)
        assertEquals(
            "the document's requested identity is never rewritten",
            latinOnly.identity,
            resolution.requested.identity,
        )
    }

    @Test
    fun `latin text in a latin family needs no substitution`() {
        assumeTrue(manifestFile.isFile)
        val inter = registry.findById(FontSource.BUILT_IN, "inter")
        assertNotNull(inter)
        val resolution = registry.resolve(FontRequest(inter!!.identity), "Hello world")
        assertNull("no substitution was needed", resolution.substitution)
        assertEquals(FontAvailability.OFFLINE_READY, resolution.availability)
        assertEquals(inter.identity, resolution.resolved)
        assertTrue("a bundled family resolves to assets", resolution.assets.isNotEmpty())
    }

    // ── §37.31 offline by construction ───────────────────────────────────────────────────────────

    /**
     * A variable family whose only face is italic still resolves to that file for an upright request.
     *
     * This is exactly what a user gets by importing one `*-Italic[wght].ttf`: a single-face family with no
     * upright face at all. Slant-filtering the faces and then falling back to "the upright ones" returns
     * nothing for it, and a resolution that reports OFFLINE_READY with an empty asset list renders no text
     * while claiming to be ready. The rule is the one [FontFamilyRecord.bestFace] already uses for static
     * families: with no face of the requested slant, load what the family has.
     */
    @Test
    fun `a variable family with only an italic face resolves upright requests to that face`() {
        val italicOnly = FontFamilyRecord(
            id = "user-italic-only",
            name = "Italic Only",
            source = FontSource.IMPORTED,
            category = FontCategory.SANS,
            faces = listOf(
                FontFaceRecord(asset = "/data/fonts/italic-only.ttf", italic = true, variable = true),
            ),
            portability = FontPortability.PRIVATE_LOCAL,
        )
        val registry = FontRegistry(listOf(italicOnly))
        for (italic in listOf(false, true)) {
            val resolution = registry.resolve(FontRequest(italicOnly.identity, italic = italic))
            assertEquals(italicOnly.identity, resolution.resolved)
            assertEquals(FontAvailability.OFFLINE_READY, resolution.availability)
            assertEquals(
                "italic=$italic must load the one packaged face",
                listOf("/data/fonts/italic-only.ttf"),
                resolution.assets,
            )
        }
    }

    /**
     * Resolving the whole guaranteed set yields asset paths and never a download state.
     *
     * The structural half of this guarantee is that [FontRegistry] has no I/O at all — it is handed records
     * and cannot fetch. This test is the behavioural half: every guaranteed family reports OFFLINE_READY
     * with real asset paths, so nothing is left to be fetched at render time.
     */
    @Test
    fun `every guaranteed family resolves offline with packaged assets`() {
        assumeTrue(manifestFile.isFile)
        for (id in GuaranteedFonts.REQUIRED_FAMILY_IDS) {
            val record = registry.findById(FontSource.BUILT_IN, id)
            assertNotNull("$id is not in the registry", record)
            for (weight in listOf(100, 400, 700, 900)) {
                for (italic in listOf(false, true)) {
                    val resolution = registry.resolve(FontRequest(record!!.identity, weight, italic))
                    assertEquals(
                        "$id @$weight italic=$italic must be offline-ready",
                        FontAvailability.OFFLINE_READY,
                        resolution.availability,
                    )
                    assertFalse(
                        "$id @$weight must resolve to at least one packaged asset",
                        resolution.assets.isEmpty(),
                    )
                    for (asset in resolution.assets) {
                        assertTrue(
                            "$id resolved to $asset which is not packaged",
                            File(facesDir, asset).isFile,
                        )
                    }
                }
            }
        }
    }

    @Test
    fun `an unknown family falls back to a bundled one rather than reporting missing`() {
        assumeTrue(manifestFile.isFile)
        val resolution = registry.resolve(FontRequest(FontIdentity.builtIn("not-a-real-family", "Ghost")))
        assertEquals(FontAvailability.OFFLINE_READY, resolution.availability)
        assertNotNull(resolution.substitution)
        assertEquals(SubstitutionReason.FAMILY_UNAVAILABLE, resolution.substitution!!.reason)
        assertEquals(
            "the requested identity survives so the UI can say what was wanted",
            "not-a-real-family",
            resolution.requested.identity.id,
        )
    }

    /** A fallback must itself be guaranteed present, or the failure has only moved. */
    @Test
    fun `fallbacks are always bundled families`() {
        assumeTrue(manifestFile.isFile)
        for (script in listOf(FontScript.LATIN, FontScript.BENGALI)) {
            val fallback = registry.fallbackFor(setOf(script), FontRequest(FontIdentity.builtIn("ghost")))
            assertNotNull("no fallback for $script", fallback)
            assertEquals(FontSource.BUILT_IN, fallback!!.source)
            assertTrue(fallback.covers(script))
        }
    }

    /** A serif request that must be substituted lands on a serif, not on whatever sorts first. */
    @Test
    fun `substitution prefers the same category`() {
        assumeTrue(manifestFile.isFile)
        val serif = packaged.first { it.category == FontCategory.SERIF && it.covers(FontScript.LATIN) }
        val fallback = registry.fallbackFor(setOf(FontScript.LATIN), FontRequest(serif.identity))
        assertNotNull(fallback)
        assertEquals("a serif should be replaced by a serif", FontCategory.SERIF, fallback!!.category)
    }

    // ── §37.32 weight and slant matching ─────────────────────────────────────────────────────────

    /** Italic is matched before weight, because losing the slant is the more visible failure. */
    @Test
    fun `slant is matched before weight`() {
        val family = FontFamilyRecord(
            id = "test",
            name = "Test",
            source = FontSource.BUILT_IN,
            category = FontCategory.SANS,
            faces = listOf(
                FontFaceRecord(asset = "r.ttf", weight = 400, italic = false),
                FontFaceRecord(asset = "b.ttf", weight = 700, italic = false),
                FontFaceRecord(asset = "i.ttf", weight = 400, italic = true),
            ),
        )
        assertEquals("b.ttf", family.bestFace(700, italic = false)!!.asset)
        assertEquals(
            "bold italic with no bold italic face keeps the italic",
            "i.ttf",
            family.bestFace(700, italic = true)!!.asset,
        )
        assertEquals("r.ttf", family.bestFace(300, italic = false)!!.asset)
    }

    @Test
    fun `weight ties resolve to the heavier face`() {
        val family = FontFamilyRecord(
            id = "t",
            name = "T",
            source = FontSource.BUILT_IN,
            category = FontCategory.SANS,
            faces = listOf(
                FontFaceRecord(asset = "300.ttf", weight = 300),
                FontFaceRecord(asset = "500.ttf", weight = 500),
            ),
        )
        assertEquals("500.ttf", family.bestFace(400, italic = false)!!.asset)
    }

    @Test
    fun `a variable family reports the full weight axis`() {
        assumeTrue(manifestFile.isFile)
        val variable = packaged.first { it.isVariable }
        assertEquals(FontFamilyRecord.VARIABLE_WEIGHTS, variable.availableWeights)
        val resolution = registry.resolve(FontRequest(variable.identity, weight = 250))
        assertEquals("a variable face satisfies any weight exactly", 250, resolution.requested.weight)
        assertTrue(resolution.assets.isNotEmpty())
    }

    @Test
    fun `a static family reports which weight it actually gave`() {
        val family = FontFamilyRecord(
            id = "static",
            name = "Static",
            source = FontSource.BUILT_IN,
            category = FontCategory.SANS,
            faces = listOf(FontFaceRecord(asset = "r.ttf", weight = 400)),
        )
        val resolution = FontRegistry(listOf(family)).resolve(FontRequest(family.identity, weight = 700))
        assertEquals("you asked for 700 and got 400, inspectably", 400, resolution.resolvedWeight)
        assertEquals(700, resolution.requested.weight)
    }

    // ── §37.33 identity, not display name ────────────────────────────────────────────────────────

    /**
     * Font identity is source + id, so catalogue order cannot change what a document resolves to.
     *
     * Shuffling the registry and re-resolving must give the same answer; a name-keyed model would be
     * sensitive to which "Inter" was registered last.
     */
    @Test
    fun `resolution is independent of catalogue order`() {
        assumeTrue(manifestFile.isFile)
        val shuffled = FontRegistry(packaged.reversed() + GuaranteedFonts.SYSTEM_FAMILIES)
        for (id in GuaranteedFonts.REQUIRED_FAMILY_IDS) {
            val request = FontRequest(FontIdentity.builtIn(id))
            assertEquals(
                "$id resolved differently after reordering the catalogue",
                registry.resolve(request).resolved,
                shuffled.resolve(request).resolved,
            )
        }
    }

    @Test
    fun `identity encodes and decodes stably`() {
        val identity = FontIdentity.builtIn("inter", "Inter")
        assertEquals("builtin:inter", identity.encode())
        assertEquals(identity.copy(displayName = "inter"), FontIdentity.decode(identity.encode()))
        assertEquals(
            FontIdentity.imported("user-x", "X"),
            FontIdentity.decode("imported:user-x", "X"),
        )
        val imported = FontIdentity.imported("user-lato", "Lato Display & বাংলা")
        assertEquals(imported, FontIdentity.decode(imported.encode()))
        assertNull(FontIdentity.decode(null))
        assertNull(FontIdentity.decode("   "))
    }

    /**
     * A bare display name is treated as legacy and resolved through the registry — never promoted to a
     * built-in id, which would let a stale `"Inter"` claim to be the bundled Inter without checking.
     */
    @Test
    fun `a legacy display name resolves without being promoted`() {
        assumeTrue(manifestFile.isFile)
        val decoded = FontIdentity.decode("Inter")
        assertNotNull(decoded)
        assertFalse("a bare name must not become a built-in id", decoded!!.source == FontSource.BUILT_IN)

        val resolved = registry.resolveLegacyName("Inter")
        assertNotNull("but the registry can map it to a real family", resolved)
        assertEquals(FontSource.BUILT_IN, resolved!!.source)
        assertEquals("inter", resolved.id)
        assertEquals(FontIdentity.SYSTEM_SANS, registry.resolveLegacyName("sans-serif"))
        assertNull(registry.resolveLegacyName("Definitely Not A Font"))
        assertNull(registry.resolveLegacyName(null))
    }

    @Test
    fun `legacy name matching is case insensitive`() {
        assumeTrue(manifestFile.isFile)
        assertEquals(registry.resolveLegacyName("Inter"), registry.resolveLegacyName("  inter  "))
    }

    // ── §37.34 search determinism ────────────────────────────────────────────────────────────────

    @Test
    fun `search is deterministic and scores exact matches first`() {
        assumeTrue(manifestFile.isFile)
        val first = registry.search("lora")
        assertEquals("repeated searches must agree", first.map { it.id }, registry.search("lora").map { it.id })
        assertEquals("an exact name match sorts first", "lora", first.first().id)

        val sans = registry.search("sans")
        assertTrue(sans.isNotEmpty())
        assertEquals(sans.map { it.id }, registry.search("sans").map { it.id })
    }

    @Test
    fun `filters compose without losing determinism`() {
        assumeTrue(manifestFile.isFile)
        val mono = registry.search(category = FontCategory.MONO)
        assertTrue("code families exist", mono.isNotEmpty())
        assertTrue(mono.all { it.category == FontCategory.MONO })

        val bangla = registry.search(script = FontScript.BENGALI)
        assertTrue(bangla.all { it.covers(FontScript.BENGALI) })
        assertTrue(bangla.size >= 4)

        val variable = registry.search(variableOnly = true)
        assertTrue(variable.all { it.isVariable })

        val accessible = registry.search(role = FontRole.ACCESSIBLE)
        assertTrue("accessible role must be curated onto something", accessible.isNotEmpty())
        assertTrue(accessible.all { FontRole.ACCESSIBLE in it.roles })
    }

    @Test
    fun `an empty query returns the whole catalogue in name order`() {
        assumeTrue(manifestFile.isFile)
        val all = registry.search()
        assertEquals(registry.size, all.size)
        assertEquals(all.map { it.name }, all.map { it.name }.sortedBy { it.lowercase() })
    }

    // ── §37.35 system families ───────────────────────────────────────────────────────────────────

    @Test
    fun `system families resolve without assets and are never a script fallback`() {
        val resolution = registry.resolve(FontRequest(FontIdentity.SYSTEM_SERIF))
        assertEquals(FontAvailability.OFFLINE_READY, resolution.availability)
        assertTrue("a system family needs no packaged file", resolution.assets.isEmpty())

        val fallback = registry.fallbackFor(setOf(FontScript.BENGALI), FontRequest(FontIdentity.builtIn("ghost")))
        assertNotNull(fallback)
        assertEquals(
            "a fallback must be a family whose coverage we measured, not the device's",
            FontSource.BUILT_IN,
            fallback!!.source,
        )
    }

    // ── §37.36 catalogue metadata ────────────────────────────────────────────────────────────────

    @Test
    fun `every family reports a usable accessible summary`() {
        assumeTrue(manifestFile.isFile)
        for (family in packaged) {
            val summary = family.accessibleSummary()
            assertTrue("${family.id} summary is blank", summary.isNotBlank())
            assertTrue("${family.id} summary must name the family", summary.contains(family.name))
            assertTrue("${family.id} summary must state the license", summary.contains(family.license.spdx))
        }
    }

    @Test
    fun `roles are curated onto every editorial group`() {
        assumeTrue(manifestFile.isFile)
        for (role in FontRole.entries) {
            val members = packaged.filter { role in it.roles }
            assertTrue("no family is curated for $role", members.isNotEmpty())
        }
    }

    @Test
    fun `total packaged size is recorded and within the phase budget`() {
        assumeTrue(manifestFile.isFile)
        val declared = packaged.sumOf { it.totalBytes }
        val onDisk = facesDir.listFiles().orEmpty().filter { it.isFile }.sumOf { it.length() }
        assertEquals("the manifest's byte counts must match the files", onDisk, declared)
        val megabytes = declared / (1024.0 * 1024.0)
        assertTrue(
            "packaged fonts are %.2f MB, which exceeds the phase budget".format(megabytes),
            megabytes < 40.0,
        )
    }

    @Test
    fun `a malformed manifest yields no families rather than throwing`() {
        assertTrue(FontManifestCodec.decode("").isEmpty())
        assertTrue(FontManifestCodec.decode("not json").isEmpty())
        assertTrue(FontManifestCodec.decode("""{"families":"nope"}""").isEmpty())
        assertTrue(FontManifestCodec.decode("""{"other":[]}""").isEmpty())
        // A malformed *family* is skipped, but valid siblings survive.
        val partial = FontManifestCodec.decode(
            """{"families":[{"no-id":true},{"id":"ok","name":"Ok","faces":[{"asset":"a.ttf"}]}]}""",
        )
        assertEquals(1, partial.size)
        assertEquals("ok", partial.first().id)
    }
}
