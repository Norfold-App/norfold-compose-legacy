package com.norfold.app.domain.style

import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Stable font identity (roadmap Phase 3 §18, spec §9.5).
 *
 * The rule this type exists to enforce: **a font selection is never persisted as a display name.** Storing
 * `"Inter"` conflates four different facts — which family, where it came from, whether it is available, and
 * whether what is on screen is actually it. A catalogue reorder, a renamed family, a removed import, or a
 * substitution then all look identical in the stored document, and there is no way to tell "the user chose
 * Inter and got Inter" from "the user chose something else and we quietly rendered Inter".
 *
 * So identity is a *source plus a stable id*:
 *
 *  - [FontSource.BUILT_IN] ids come from the bundled manifest and never change;
 *  - [FontSource.DOWNLOADABLE] ids are the catalogue's stable family key;
 *  - [FontSource.IMPORTED] ids are derived from the file's content hash, so re-importing the same file is
 *    the same font and two different files with the same internal family name are not;
 *  - [FontSource.SYSTEM] ids are the generic families Android guarantees.
 *
 * [displayName] rides along purely so a document that references an unavailable font can still *say* what
 * it wanted. It is never used for resolution — that is what makes it safe to store.
 */
@Serializable
enum class FontSource {
    /** Packaged in the app. Always available, zero network. */
    @SerialName("builtin") BUILT_IN,

    /** Available from the extended catalogue; may or may not be installed locally yet. */
    @SerialName("downloadable") DOWNLOADABLE,

    /** A file the user imported. Private to this device unless the license permits otherwise. */
    @SerialName("imported") IMPORTED,

    /** A generic system family (sans-serif, serif, monospace). */
    @SerialName("system") SYSTEM,

    ;

    /** Stable wire key. Enum identifiers are implementation details and may contain underscores. */
    val persistenceKey: String
        get() = when (this) {
            BUILT_IN -> "builtin"
            DOWNLOADABLE -> "downloadable"
            IMPORTED -> "imported"
            SYSTEM -> "system"
        }

    companion object {
        fun fromPersistenceKey(key: String): FontSource? = when (key.lowercase()) {
            "builtin", "built_in" -> BUILT_IN
            "downloadable" -> DOWNLOADABLE
            "imported" -> IMPORTED
            "system" -> SYSTEM
            else -> null
        }
    }
}

/**
 * A font family reference as stored in a document or a style.
 *
 * Two of these are equal iff they name the same font, regardless of catalogue order or display name — which
 * is the property §37.33 tests.
 */
@Serializable
data class FontIdentity(
    val source: FontSource,
    val id: String,
    /** Last-known human name. Informational only; resolution never reads it. */
    val displayName: String = id,
) {
    val isGuaranteedOffline: Boolean get() = source == FontSource.BUILT_IN || source == FontSource.SYSTEM

    /** A stable string form for persistence in places that only hold a string (e.g. an inline node). */
    fun encode(): String {
        val stable = "${source.persistenceKey}:$id"
        // Imported/downloadable families can disappear. Persist their last-known human name so Missing and
        // Substituted states remain intelligible after reconstruction. Existing built-in/system wire values
        // stay byte-for-byte unchanged, and the decoder below still accepts every pre-correction value.
        if (source != FontSource.IMPORTED && source != FontSource.DOWNLOADABLE) return stable
        if (displayName == id) return stable
        return "$stable|${URLEncoder.encode(displayName, StandardCharsets.UTF_8.name())}"
    }

    companion object {
        val SYSTEM_SANS = FontIdentity(FontSource.SYSTEM, "sans-serif", "Sans Serif")
        val SYSTEM_SERIF = FontIdentity(FontSource.SYSTEM, "serif", "Serif")
        val SYSTEM_MONO = FontIdentity(FontSource.SYSTEM, "monospace", "Monospace")

        fun builtIn(id: String, displayName: String = id) = FontIdentity(FontSource.BUILT_IN, id, displayName)
        fun imported(id: String, displayName: String) = FontIdentity(FontSource.IMPORTED, id, displayName)
        fun downloadable(id: String, displayName: String = id) = FontIdentity(FontSource.DOWNLOADABLE, id, displayName)

        /**
         * Parses [encode]'s form. A bare string with no `source:` prefix is read as a **legacy display
         * name** and resolved by the registry, never silently promoted to a built-in id — that would make
         * a stale `"Inter"` claim to be the bundled Inter without checking.
         */
        fun decode(raw: String?, displayName: String? = null): FontIdentity? {
            val text = raw?.trim()?.takeIf(String::isNotEmpty) ?: return null
            val separator = text.indexOf(':')
            if (separator < 0) return FontIdentity(FontSource.SYSTEM, text, displayName ?: text)
            val source = FontSource.fromPersistenceKey(text.substring(0, separator)) ?: return null
            val payload = text.substringAfter(':')
            val id = payload.substringBefore('|').takeIf(String::isNotEmpty) ?: return null
            val persistedName = payload.substringAfter('|', "")
                .takeIf(String::isNotEmpty)
                ?.let { encoded -> runCatching { URLDecoder.decode(encoded, StandardCharsets.UTF_8.name()) }.getOrNull() }
            return FontIdentity(source, id, displayName ?: persistedName ?: id)
        }
    }
}

/**
 * The style a caller asked for, kept separate from the identity it asked it of (§18: "keep font identity,
 * requested style/weight, and fallback/substitution state distinct").
 *
 * Weight is the CSS 100..900 scale. A variable font satisfies any weight in its axis range exactly; a
 * static family snaps to its nearest available face, and [FontResolution] records which — so "you asked for
 * 500 and got 400" is inspectable rather than invisible.
 */
@Serializable
data class FontRequest(
    val identity: FontIdentity,
    val weight: Int = 400,
    val italic: Boolean = false,
) {
    fun normalized() = copy(weight = weight.coerceIn(100, 900))
}

/**
 * What the user's font request actually resolved to.
 *
 * [requested] never changes — that is the whole point. If Bengali text asked for Poppins (Latin-only) and
 * was rendered in Noto Sans Bengali, [requested] is still Poppins and [substitution] explains the swap. A
 * design that rewrote the request would silently *edit the document* to match a temporary rendering
 * decision, so reopening it on a device where Poppins was available would no longer show Poppins.
 */
data class FontResolution(
    val requested: FontRequest,
    /** The family actually used. Equal to `requested.identity` when nothing was substituted. */
    val resolved: FontIdentity?,
    val availability: FontAvailability,
    val substitution: FontSubstitution? = null,
    /** The face weight actually available, when a static family could not match exactly. */
    val resolvedWeight: Int? = null,
    /** The asset paths the renderer should load, in weight order. Empty when unavailable. */
    val assets: List<String> = emptyList(),
) {
    val isSubstituted: Boolean get() = substitution != null
    val isAvailable: Boolean get() = availability == FontAvailability.OFFLINE_READY

    /** Whether the rendered weight differs from the requested one. */
    val weightApproximated: Boolean
        get() = resolvedWeight != null && resolvedWeight != requested.normalized().weight

    /**
     * The state string a UI shows and a screen reader reads — never conveyed by styling alone (§31).
     */
    fun stateLabel(): String = when {
        substitution != null -> "Substituted with ${substitution.substitute.displayName}"
        availability == FontAvailability.MISSING -> "Missing"
        availability == FontAvailability.DOWNLOADABLE -> "Available to download"
        availability == FontAvailability.DOWNLOADING -> "Downloading"
        weightApproximated -> "Offline ✓ · nearest weight $resolvedWeight"
        else -> "Offline ✓"
    }
}

/** Whether a font can be rendered right now, and if not, why. */
@Serializable
enum class FontAvailability {
    /** Present locally; renders with no network. */
    @SerialName("offline") OFFLINE_READY,

    /** Known to the extended catalogue but not installed. */
    @SerialName("downloadable") DOWNLOADABLE,

    /** A download is in flight. */
    @SerialName("downloading") DOWNLOADING,

    /** Referenced by content but not resolvable — an uninstalled import, or an unknown family. */
    @SerialName("missing") MISSING,
}

/** Why a substitution happened, so the UI can explain rather than merely flag. */
@Serializable
enum class SubstitutionReason(val label: String) {
    @SerialName("unavailable") FAMILY_UNAVAILABLE("The requested font isn't installed"),
    @SerialName("script") SCRIPT_NOT_COVERED("The requested font doesn't include these characters"),
    @SerialName("removed") IMPORT_REMOVED("The imported font file was removed"),
}

/** A recorded substitution: what was asked, what was used, and why. */
@Serializable
data class FontSubstitution(
    val requested: FontIdentity,
    val substitute: FontIdentity,
    val reason: SubstitutionReason,
) {
    fun summary(): String =
        "${requested.displayName} → ${substitute.displayName}: ${reason.label}"
}

/**
 * Whether a font could travel with a shared document (spec §9.10, Phase-12 groundwork).
 *
 * Modelled now, transported never: Phase 3 must not touch collaboration (§33). What this buys is that when
 * Phase 12 does implement sharing, the question "may this font leave the device?" already has a stored,
 * per-font answer instead of needing a migration — and, critically, [PRIVATE_LOCAL] is the *default* for
 * imports, so the safe answer is the one you get by doing nothing.
 */
@Serializable
enum class FontPortability(val label: String, val description: String) {
    @SerialName("builtin") BUILT_IN("Built in", "Everyone with Norfold already has this font"),
    @SerialName("shareable") DOWNLOADABLE_SHAREABLE("Downloadable", "Recipients can fetch this from the catalogue"),
    @SerialName("embeddable") EMBEDDING_PERMITTED("Embeddable", "This font's license permits embedding in documents"),
    @SerialName("private") PRIVATE_LOCAL("Private to this device", "This font is never shared or uploaded"),
    @SerialName("unavailable") UNAVAILABLE("Unavailable", "This font can't be resolved"),
    @SerialName("substituted") SUBSTITUTED("Substituted", "Rendered with a different font"),
    ;

    /** Whether font *bytes* may ever leave the device. Only ever consulted by Phase 12. */
    val redistributable: Boolean
        get() = this == EMBEDDING_PERMITTED

    /** Whether the *reference* may travel (the recipient resolves it themselves). */
    val referenceShareable: Boolean
        get() = this == BUILT_IN || this == DOWNLOADABLE_SHAREABLE
}

/** Script coverage a family advertises. Drives Bangla fallback (§22) without parsing every cmap at runtime. */
@Serializable
enum class FontScript(val label: String) {
    @SerialName("latin") LATIN("Latin"),
    @SerialName("bengali") BENGALI("Bangla"),
    @SerialName("cyrillic") CYRILLIC("Cyrillic"),
    @SerialName("greek") GREEK("Greek"),
    @SerialName("devanagari") DEVANAGARI("Devanagari"),
    @SerialName("arabic") ARABIC("Arabic"),
    ;

    companion object {
        /**
         * Which scripts [text] actually needs. Used to pick a fallback that can render the characters in
         * front of the user rather than one that merely looks similar.
         *
         * Only the scripts this app has a guaranteed answer for are detected; anything else is left to the
         * system fallback, because claiming coverage the bundle does not have is worse than deferring.
         */
        fun scriptsOf(text: String): Set<FontScript> = buildSet {
            for (ch in text) {
                when (ch.code) {
                    in 0x0980..0x09FE -> add(BENGALI)
                    in 0x0900..0x097F -> add(DEVANAGARI)
                    in 0x0400..0x04FF -> add(CYRILLIC)
                    in 0x0370..0x03FF -> add(GREEK)
                    in 0x0600..0x06FF -> add(ARABIC)
                    in 0x0041..0x024F -> add(LATIN)
                }
            }
        }

        /** Maps a Google-Fonts subset tag to a script, or null when it is not one we model. */
        fun fromSubsetTag(tag: String): FontScript? = when (tag.lowercase()) {
            "latin", "latin-ext" -> LATIN
            "bengali" -> BENGALI
            "cyrillic", "cyrillic-ext" -> CYRILLIC
            "greek", "greek-ext" -> GREEK
            "devanagari" -> DEVANAGARI
            "arabic" -> ARABIC
            else -> null
        }
    }
}

/** Broad typographic classification, used by the Font Browser's category filter. */
@Serializable
enum class FontCategory(val label: String) {
    @SerialName("sans") SANS("Sans"),
    @SerialName("serif") SERIF("Serif"),
    @SerialName("mono") MONO("Mono"),
    @SerialName("display") DISPLAY("Display"),
    @SerialName("handwriting") HANDWRITING("Handwriting"),
    ;

    companion object {
        fun fromMetadataCategory(raw: String): FontCategory = when (raw.uppercase()) {
            "SERIF" -> SERIF
            "MONOSPACE" -> MONO
            "DISPLAY" -> DISPLAY
            "HANDWRITING" -> HANDWRITING
            else -> SANS
        }
    }
}

/**
 * Editorial role tags — the Font Browser's "what am I making?" axis (§19).
 *
 * These are curated, not derived: a font's suitability for a report or a presentation is an editorial
 * judgement, and deriving it from the category would just give five copies of the category filter.
 */
@Serializable
enum class FontRole(val label: String) {
    @SerialName("professional") PROFESSIONAL("Professional"),
    @SerialName("reports") REPORTS("Reports"),
    @SerialName("reading") LONG_READING("Long reading"),
    @SerialName("presentation") PRESENTATION("Presentation"),
    @SerialName("code") CODE("Code"),
    @SerialName("accessible") ACCESSIBLE("Accessible"),
    @SerialName("bangla") BANGLA("Bangla"),
}
