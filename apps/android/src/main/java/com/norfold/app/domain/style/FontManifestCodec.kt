package com.norfold.app.domain.style

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Decodes the bundled font manifest into [FontFamilyRecord]s (roadmap Phase 3 §15–§17).
 *
 * The manifest is produced at packaging time from the actual subsetted binaries, so every field it carries
 * — checksums, byte counts, measured script coverage — describes files that are genuinely in the APK. This
 * codec's job is only to read it; it deliberately cannot invent a family, because a registry entry with no
 * asset behind it would resolve "successfully" to nothing and render as tofu.
 *
 * Parsing is defensive in one direction only: a malformed *family* is skipped rather than crashing the app,
 * but [GuaranteedFonts.REQUIRED_FAMILY_IDS] is then checked against what survived, so a silently dropped
 * family fails a test instead of shipping.
 */
object FontManifestCodec {

    private val json = Json { ignoreUnknownKeys = true; isLenient = false }

    const val ASSET_ROOT = "fonts"
    const val MANIFEST_ASSET = "fonts/manifest.json"

    /** Decodes the manifest text. Returns an empty list if the payload is not a manifest at all. */
    fun decode(text: String): List<FontFamilyRecord> {
        val root = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return emptyList()
        val families = runCatching { root["families"]!!.jsonArray }.getOrNull() ?: return emptyList()
        return families.mapNotNull { element ->
            runCatching { decodeFamily(element.jsonObject) }.getOrNull()
        }
    }

    private fun decodeFamily(obj: JsonObject): FontFamilyRecord {
        val id = obj.str("id") ?: error("family without id")
        val name = obj.str("name") ?: id
        val faces = obj["faces"]?.jsonArray.orEmpty().mapNotNull { face ->
            runCatching { decodeFace(face.jsonObject) }.getOrNull()
        }
        require(faces.isNotEmpty()) { "family $id has no faces" }
        val license = FontLicense.fromMetadata(obj.str("license").orEmpty())
        val scripts = obj["packagedScripts"]?.jsonArray.orEmpty()
            .mapNotNull { FontScript.fromSubsetTag(it.jsonPrimitive.content) }
            .toSet()
            .ifEmpty { setOf(FontScript.LATIN) }
        return FontFamilyRecord(
            id = id,
            name = name,
            source = FontSource.BUILT_IN,
            category = FontCategory.fromMetadataCategory(obj.str("category").orEmpty()),
            roles = GuaranteedFonts.rolesOf(id),
            packagedScripts = scripts,
            faces = faces,
            license = license,
            // A bundled family ships with the app, so every recipient already has it: the reference travels
            // and the bytes never need to.
            portability = FontPortability.BUILT_IN,
            upstreamSource = obj.str("upstreamDir").orEmpty(),
            upstreamVersion = obj.str("upstreamVersion").orEmpty(),
            licenseFile = obj.str("licenseFile"),
            glyphCoverage = obj["glyphCoverage"]?.jsonPrimitive?.intOrNull ?: 0,
        )
    }

    private fun decodeFace(obj: JsonObject): FontFaceRecord {
        val asset = obj.str("asset") ?: error("face without asset")
        val style = obj.str("style").orEmpty()
        return FontFaceRecord(
            asset = asset,
            weight = obj["weight"]?.jsonPrimitive?.intOrNull ?: 400,
            italic = style.equals("italic", ignoreCase = true) ||
                asset.contains("italic", ignoreCase = true),
            variable = obj["variable"]?.jsonPrimitive?.booleanOrNull ?: false,
            sha256 = obj.str("sha256").orEmpty(),
            bytes = obj["bytes"]?.jsonPrimitive?.longOrNull ?: 0L,
            upstreamFile = obj.str("upstreamFile").orEmpty(),
        )
    }

    private fun JsonObject.str(key: String): String? =
        this[key]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotEmpty)
}

/**
 * The 30 guaranteed-offline families (spec §9.6) and their curated editorial metadata.
 *
 * The id list is a **contract**, not documentation: [REQUIRED_FAMILY_IDS] is what the packaging test
 * asserts against the manifest, so removing a family to save bytes — the thing §16 forbids — breaks the
 * build rather than quietly shrinking the library.
 *
 * Roles are curated here rather than derived from category, because "good for long reading" and "good for
 * a presentation" are editorial judgements about a typeface, not facts about its classification. Deriving
 * them would produce five filters that are all the category filter wearing a hat.
 */
object GuaranteedFonts {

    /** The exact 30 ids that must be packaged. Order is the spec's grouping order. */
    val REQUIRED_FAMILY_IDS: List<String> = listOf(
        // Professional / general sans
        "inter", "source-sans-3", "ibm-plex-sans", "noto-sans", "manrope",
        "lato", "montserrat", "carlito", "arimo", "atkinson-hyperlegible-next",
        // Serif / publishing
        "source-serif-4", "noto-serif", "lora", "merriweather", "eb-garamond",
        "literata", "tinos", "roboto-slab",
        // Code / technical
        "jetbrains-mono", "source-code-pro", "fira-code", "cousine",
        // Expressive / presentation
        "playfair-display", "fraunces", "poppins", "lexend",
        // Bangla
        "noto-sans-bengali", "noto-serif-bengali", "hind-siliguri", "tiro-bangla",
    )

    /** The four families that must cover Bangla (§41). */
    val BANGLA_FAMILY_IDS: List<String> =
        listOf("noto-sans-bengali", "noto-serif-bengali", "hind-siliguri", "tiro-bangla")

    private val ROLES: Map<String, Set<FontRole>> = mapOf(
        "inter" to setOf(FontRole.PROFESSIONAL, FontRole.REPORTS),
        "source-sans-3" to setOf(FontRole.PROFESSIONAL, FontRole.REPORTS, FontRole.LONG_READING),
        "ibm-plex-sans" to setOf(FontRole.PROFESSIONAL, FontRole.REPORTS),
        "noto-sans" to setOf(FontRole.PROFESSIONAL, FontRole.REPORTS, FontRole.LONG_READING),
        "manrope" to setOf(FontRole.PROFESSIONAL, FontRole.PRESENTATION),
        "lato" to setOf(FontRole.PROFESSIONAL, FontRole.REPORTS),
        "montserrat" to setOf(FontRole.PRESENTATION),
        // Metric-compatible with Calibri/Arial/Times: the families that make an imported .docx keep its layout.
        "carlito" to setOf(FontRole.PROFESSIONAL, FontRole.REPORTS),
        "arimo" to setOf(FontRole.PROFESSIONAL, FontRole.REPORTS),
        "atkinson-hyperlegible-next" to setOf(FontRole.ACCESSIBLE, FontRole.LONG_READING),
        "source-serif-4" to setOf(FontRole.LONG_READING, FontRole.REPORTS),
        "noto-serif" to setOf(FontRole.LONG_READING, FontRole.REPORTS),
        "lora" to setOf(FontRole.LONG_READING),
        "merriweather" to setOf(FontRole.LONG_READING),
        "eb-garamond" to setOf(FontRole.LONG_READING),
        "literata" to setOf(FontRole.LONG_READING, FontRole.REPORTS),
        "tinos" to setOf(FontRole.REPORTS, FontRole.PROFESSIONAL),
        "roboto-slab" to setOf(FontRole.PRESENTATION, FontRole.REPORTS),
        "jetbrains-mono" to setOf(FontRole.CODE),
        "source-code-pro" to setOf(FontRole.CODE),
        "fira-code" to setOf(FontRole.CODE),
        "cousine" to setOf(FontRole.CODE, FontRole.REPORTS),
        "playfair-display" to setOf(FontRole.PRESENTATION),
        "fraunces" to setOf(FontRole.PRESENTATION),
        "poppins" to setOf(FontRole.PRESENTATION),
        "lexend" to setOf(FontRole.ACCESSIBLE, FontRole.LONG_READING, FontRole.PRESENTATION),
        "noto-sans-bengali" to setOf(FontRole.BANGLA, FontRole.PROFESSIONAL, FontRole.REPORTS),
        "noto-serif-bengali" to setOf(FontRole.BANGLA, FontRole.LONG_READING),
        "hind-siliguri" to setOf(FontRole.BANGLA, FontRole.PROFESSIONAL),
        "tiro-bangla" to setOf(FontRole.BANGLA, FontRole.LONG_READING),
    )

    fun rolesOf(id: String): Set<FontRole> = ROLES[id].orEmpty()

    /** The system generics, which are always available and cost nothing to package. */
    val SYSTEM_FAMILIES: List<FontFamilyRecord> = listOf(
        systemFamily("sans-serif", "Sans Serif", FontCategory.SANS),
        systemFamily("serif", "Serif", FontCategory.SERIF),
        systemFamily("monospace", "Monospace", FontCategory.MONO),
    )

    private fun systemFamily(id: String, name: String, category: FontCategory) = FontFamilyRecord(
        id = id,
        name = name,
        source = FontSource.SYSTEM,
        category = category,
        // The device's own fonts cover whatever the device covers; claiming a specific set would be a
        // guess, so system families advertise only Latin and are never chosen as a script fallback.
        packagedScripts = setOf(FontScript.LATIN),
        faces = listOf(FontFaceRecord(asset = "", weight = 400)),
        license = FontLicense.UNKNOWN,
        portability = FontPortability.BUILT_IN,
        upstreamSource = "Android system",
    )
}
