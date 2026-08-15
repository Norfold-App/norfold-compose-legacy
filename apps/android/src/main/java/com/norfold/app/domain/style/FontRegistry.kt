package com.norfold.app.domain.style

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.math.abs

/**
 * One family in the font catalogue — bundled, downloadable, or imported (roadmap Phase 3 §15–§21).
 *
 * Everything a decision needs is a field here, because the alternative is opening font binaries at
 * decision time. The Font Browser filters, the fallback chain, the license sheet and the portability
 * question are all answered from this record with no I/O, which is what lets the whole resolution path be
 * unit-tested and lets a 30-family list render without touching 65 files.
 *
 * [packagedScripts] is the *measured* coverage of the faces this app actually ships, not the upstream
 * family's advertised subsets. That distinction is load-bearing: upstream Noto Sans advertises dozens of
 * scripts, but the copy in this APK is subset to Latin, and a fallback chain that trusted the upstream
 * claim would confidently route Bengali text to a font with no Bengali glyphs.
 */
@Serializable
data class FontFamilyRecord(
    val id: String,
    val name: String,
    val source: FontSource,
    val category: FontCategory,
    val roles: Set<FontRole> = emptySet(),
    /** Scripts the packaged faces genuinely cover, measured at build time. */
    val packagedScripts: Set<FontScript> = setOf(FontScript.LATIN),
    val faces: List<FontFaceRecord> = emptyList(),
    val license: FontLicense = FontLicense.UNKNOWN,
    val portability: FontPortability = FontPortability.UNAVAILABLE,
    /** Where the family came from, for the attribution sheet. */
    val upstreamSource: String = "",
    val upstreamVersion: String = "",
    /** Asset-relative path of the license text, e.g. `licenses/inter.txt`. */
    val licenseFile: String? = null,
    /** Total glyphs across the packaged faces — an honesty figure for the licensing/size sheet. */
    val glyphCoverage: Int = 0,
) {
    val identity: FontIdentity get() = FontIdentity(source, id, name)

    val isVariable: Boolean get() = faces.any { it.variable }

    val hasItalic: Boolean get() = faces.any { it.italic }

    /** Weights this family can render exactly. A variable face reports its whole axis as available. */
    val availableWeights: List<Int>
        get() = if (isVariable) VARIABLE_WEIGHTS else faces.map { it.weight }.distinct().sorted()

    val totalBytes: Long get() = faces.sumOf { it.bytes }

    fun covers(script: FontScript): Boolean = script in packagedScripts

    fun covers(text: String): Boolean = FontScript.scriptsOf(text).all { it in packagedScripts }

    /**
     * The face that best satisfies [weight]/[italic].
     *
     * Italic is matched *first* and weight second, because a user who asked for bold italic and gets bold
     * upright has lost the more visible of the two properties. Within the matching slant, the nearest
     * weight wins; ties go to the heavier face, which reads as closer to the intent than going lighter.
     */
    fun bestFace(weight: Int, italic: Boolean): FontFaceRecord? {
        if (faces.isEmpty()) return null
        val target = weight.coerceIn(100, 900)
        val slantMatched = faces.filter { it.italic == italic }.ifEmpty { faces }
        return slantMatched.minWithOrNull(
            compareBy({ abs(it.weight - target) }, { -it.weight }),
        )
    }

    /** A readable summary for the browser row and its accessibility label (§31). */
    fun accessibleSummary(): String = buildString {
        append(name)
        append(", ")
        append(category.label)
        if (isVariable) append(", variable")
        if (FontScript.BENGALI in packagedScripts) append(", Bangla")
        append(", ")
        append(license.spdx)
    }

    companion object {
        /** The weights a variable `wght` axis is offered at in the UI. */
        val VARIABLE_WEIGHTS = listOf(100, 200, 300, 400, 500, 600, 700, 800, 900)
    }
}

/** One packaged face. [asset] is relative to the font asset root; [sha256] gates integrity checks. */
@Serializable
data class FontFaceRecord(
    val asset: String,
    val weight: Int = 400,
    val italic: Boolean = false,
    val variable: Boolean = false,
    val sha256: String = "",
    val bytes: Long = 0,
    /** The upstream filename this face was produced from, for the attribution record. */
    val upstreamFile: String = "",
)

/** License metadata, kept machine-readable for later export/embedding decisions (§17). */
@Serializable
enum class FontLicense(val spdx: String, val label: String, val embeddingPermitted: Boolean) {
    @SerialName("ofl") OFL("OFL-1.1", "SIL Open Font License 1.1", embeddingPermitted = true),
    @SerialName("apache") APACHE_2("Apache-2.0", "Apache License 2.0", embeddingPermitted = true),
    @SerialName("ufl") UFL("UFL-1.0", "Ubuntu Font Licence 1.0", embeddingPermitted = true),
    @SerialName("unknown") UNKNOWN("UNKNOWN", "License not recorded", embeddingPermitted = false),
    ;

    companion object {
        fun fromMetadata(raw: String): FontLicense = when (raw.uppercase()) {
            "OFL" -> OFL
            "APACHE2", "APACHE-2.0", "APACHE" -> APACHE_2
            "UFL" -> UFL
            else -> UNKNOWN
        }
    }
}

/**
 * The catalogue and the resolver (roadmap Phase 3 §15, §18, §22).
 *
 * Pure JVM and **offline by construction**: the registry is handed its records and never fetches anything.
 * That is not merely a testing convenience — it is the mechanism by which §37.31 ("resolving the guaranteed
 * set makes zero network/provider request") is *structurally* true rather than merely observed to be true.
 * There is no code path from here to a socket, so no future edit can accidentally introduce one without
 * adding a dependency that review would see.
 *
 * The registry is constructed from the bundled manifest plus whatever imported/downloaded families the
 * device has. Ordering is stable and independent of resolution, so shuffling the display list cannot change
 * what a document resolves to (§37.33).
 */
class FontRegistry(
    families: List<FontFamilyRecord>,
) {
    private val byId: Map<String, FontFamilyRecord> =
        families.associateBy { "${it.source.name}:${it.id}" }

    /** Display-name lookup, for legacy payloads only. Case-insensitive, first match in registration order. */
    private val byDisplayName: Map<String, FontFamilyRecord> =
        families.reversed().associateBy { it.name.lowercase() }

    val all: List<FontFamilyRecord> = families

    val builtIns: List<FontFamilyRecord> = families.filter { it.source == FontSource.BUILT_IN }

    val size: Int get() = all.size

    fun find(identity: FontIdentity): FontFamilyRecord? = byId["${identity.source.name}:${identity.id}"]

    fun findById(source: FontSource, id: String): FontFamilyRecord? = byId["${source.name}:$id"]

    /**
     * Resolves a **legacy display name** (`"Inter"`) to a real identity, preferring a built-in.
     *
     * This is the one place a name is allowed to become an identity, and it exists only so documents
     * written before Phase 3 keep their fonts. Everything written from now on stores [FontIdentity.encode].
     */
    fun resolveLegacyName(name: String?): FontIdentity? {
        val key = name?.trim()?.lowercase()?.takeIf(String::isNotEmpty) ?: return null
        byDisplayName[key]?.let { return it.identity }
        return when (key) {
            "sans", "sans serif", "sans-serif" -> FontIdentity.SYSTEM_SANS
            "serif" -> FontIdentity.SYSTEM_SERIF
            "mono", "monospace" -> FontIdentity.SYSTEM_MONO
            else -> null
        }
    }

    // ── Resolution ───────────────────────────────────────────────────────────────────────────────

    /**
     * Resolves [request] against the catalogue, applying the fallback chain when needed.
     *
     * [text] lets the resolver honour script coverage: Bangla content asking for a Latin-only family must
     * fall back to a Bangla family, or the user gets tofu boxes and no explanation (§22). When [text] is
     * empty, only availability is considered.
     *
     * The returned [FontResolution] always carries the original request untouched, so a substitution is
     * visible to the UI and invisible to the document.
     */
    fun resolve(request: FontRequest, text: String = ""): FontResolution {
        val normalized = request.normalized()
        val record = find(normalized.identity)
        val needed = FontScript.scriptsOf(text)

        if (record == null) {
            // A system generic is always renderable — Android guarantees it — and needs no assets.
            if (normalized.identity.source == FontSource.SYSTEM) {
                return FontResolution(normalized, normalized.identity, FontAvailability.OFFLINE_READY)
            }
            val substitute = fallbackFor(needed, normalized)
            return FontResolution(
                requested = normalized,
                resolved = substitute?.identity,
                availability = if (substitute != null) FontAvailability.OFFLINE_READY else FontAvailability.MISSING,
                substitution = substitute?.let {
                    FontSubstitution(normalized.identity, it.identity, SubstitutionReason.FAMILY_UNAVAILABLE)
                },
                assets = substitute?.let { assetsOf(it, normalized) }.orEmpty(),
                resolvedWeight = substitute?.bestFace(normalized.weight, normalized.italic)?.weight,
            )
        }

        // Present, but does it cover the characters actually being rendered?
        val uncovered = needed.filterNot { record.covers(it) }
        if (uncovered.isNotEmpty()) {
            val substitute = fallbackFor(uncovered.toSet(), normalized)
            if (substitute != null && substitute.id != record.id) {
                return FontResolution(
                    requested = normalized,
                    resolved = substitute.identity,
                    availability = FontAvailability.OFFLINE_READY,
                    substitution = FontSubstitution(
                        normalized.identity,
                        substitute.identity,
                        SubstitutionReason.SCRIPT_NOT_COVERED,
                    ),
                    assets = assetsOf(substitute, normalized),
                    resolvedWeight = substitute.bestFace(normalized.weight, normalized.italic)?.weight,
                )
            }
        }

        return FontResolution(
            requested = normalized,
            resolved = record.identity,
            availability = FontAvailability.OFFLINE_READY,
            assets = assetsOf(record, normalized),
            resolvedWeight = record.bestFace(normalized.weight, normalized.italic)?.weight,
        )
    }

    /**
     * The best bundled family covering [scripts], preferring one of the same category as the request so a
     * serif request that must be substituted lands on a serif.
     *
     * Only [FontSource.BUILT_IN] families are eligible: a fallback must be *guaranteed* present, and a
     * downloadable or imported family is by definition not. Falling back to something that might also be
     * missing would just move the failure.
     */
    fun fallbackFor(scripts: Set<FontScript>, request: FontRequest): FontFamilyRecord? {
        val requestedCategory = find(request.identity)?.category
        val candidates = builtIns.filter { family -> scripts.all { family.covers(it) } }
        if (candidates.isEmpty()) return null
        return candidates.minWithOrNull(
            compareBy(
                // Same category first, then the curated preference order, then id for total determinism.
                { if (requestedCategory != null && it.category == requestedCategory) 0 else 1 },
                { FALLBACK_PREFERENCE.indexOf(it.id).takeIf { i -> i >= 0 } ?: Int.MAX_VALUE },
                { it.id },
            ),
        )
    }

    /** Asset paths for [record] at the requested weight/slant, in load order. */
    private fun assetsOf(record: FontFamilyRecord, request: FontRequest): List<String> {
        if (record.source == FontSource.SYSTEM) return emptyList()
        // A variable family loads its whole face; Compose applies the weight through the axis.
        if (record.isVariable) {
            // When no face carries the requested slant, load the faces the family *does* have and let
            // Compose synthesize — the same rule [FontFamilyRecord.bestFace] applies to static families.
            // Falling back to the upright faces specifically would return nothing for a family imported
            // from a single italic variable file, which reports OFFLINE_READY with no asset to render.
            return record.faces.filter { it.italic == request.italic }
                .ifEmpty { record.faces }
                .map { it.asset }
        }
        return listOfNotNull(record.bestFace(request.weight, request.italic)?.asset)
    }

    // ── Browsing ─────────────────────────────────────────────────────────────────────────────────

    /**
     * Families matching [query] and the given filters, in a deterministic order.
     *
     * Search is scored rather than merely filtered so that typing "sans" puts "Source Sans 3" above
     * "Comic Sans-alike" — but the score is a stable integer and ties break on name, so the same query
     * always yields the same list. A non-deterministic browser is one where the row a user was about to
     * tap moves.
     */
    fun search(
        query: String = "",
        category: FontCategory? = null,
        role: FontRole? = null,
        script: FontScript? = null,
        variableOnly: Boolean = false,
        sources: Set<FontSource> = FontSource.entries.toSet(),
    ): List<FontFamilyRecord> {
        val q = query.trim().lowercase()
        return all.asSequence()
            .filter { it.source in sources }
            .filter { category == null || it.category == category }
            .filter { role == null || role in it.roles }
            .filter { script == null || it.covers(script) }
            .filter { !variableOnly || it.isVariable }
            .filter { q.isEmpty() || it.name.lowercase().contains(q) }
            .sortedWith(
                compareBy(
                    { if (q.isEmpty()) 0 else scoreOf(it.name.lowercase(), q) },
                    { it.name.lowercase() },
                ),
            )
            .toList()
    }

    /** 0 = exact, 1 = prefix, 2 = word-start, 3 = substring. Lower sorts first. */
    private fun scoreOf(name: String, query: String): Int = when {
        name == query -> 0
        name.startsWith(query) -> 1
        name.split(' ').any { it.startsWith(query) } -> 2
        else -> 3
    }

    /** Families covering Bangla — the guarantee §41 asks about, answered from packaged coverage. */
    fun banglaFamilies(): List<FontFamilyRecord> = all.filter { it.covers(FontScript.BENGALI) }

    companion object {
        /**
         * Fallback preference among bundled families, most-preferred first.
         *
         * Curated deliberately: Noto is the widest-coverage neutral, the Bangla families lead for Bengali,
         * and the code family is last because falling back to a monospace changes the *texture* of prose
         * far more than falling back to another sans.
         */
        val FALLBACK_PREFERENCE = listOf(
            "noto-sans-bengali",
            "noto-serif-bengali",
            "hind-siliguri",
            "tiro-bangla",
            "inter",
            "noto-sans",
            "source-sans-3",
            "noto-serif",
            "source-serif-4",
            "jetbrains-mono",
        )
    }
}
