package com.norfold.app.editor.style

import com.norfold.app.domain.style.FontAvailability
import com.norfold.app.domain.style.FontCategory
import com.norfold.app.domain.style.FontFamilyRecord
import com.norfold.app.domain.style.FontIdentity
import com.norfold.app.domain.style.FontRegistry
import com.norfold.app.domain.style.FontRequest
import com.norfold.app.domain.style.FontRole
import com.norfold.app.domain.style.FontScript
import com.norfold.app.domain.style.FontSource
import com.norfold.app.domain.style.FontPortability

/**
 * The Font Browser's state and behaviour, with no Compose dependency (roadmap Phase 3 §19, §28, §30).
 *
 * §19's requirement is "a browser, NOT a 1,900-item dropdown", and the difference is not visual — it is
 * that a browser has *state*: tabs, filters, a query, favourites, a preview string, and a selection that
 * persists while you compare. All of that lives here so it survives a form-factor change (§28) and so the
 * filtering logic is testable without rendering a single row.
 *
 * §30's performance requirements are met structurally rather than by optimisation:
 *
 *  - the engine returns *records*, never loaded typefaces, so filtering 1,900 families touches no font
 *    binaries and cannot "reload bundled font binaries unnecessarily";
 *  - [sections] returns a small, bounded quick-pick list and a lazily-consumed full list, so the host's
 *    virtualized list decides how many previews exist — the engine never implies "compose all of them".
 *
 * Pure JVM, not thread-safe; the editor loop serializes access.
 */
class FontBrowserEngine(
    private val registry: FontRegistry,
    private val recentLimit: Int = 8,
) {
    private var state: FontBrowserState = FontBrowserState()
    private val recent = ArrayDeque<String>()
    private val favorites = LinkedHashSet<String>()

    /** Availability overrides for downloadable/imported families, keyed by [FontIdentity.encode]. */
    private val availability = HashMap<String, FontAvailability>()

    fun state(): FontBrowserState = state

    // ── Tool state ───────────────────────────────────────────────────────────────────────────────

    /**
     * Opens the browser for a selection.
     *
     * [selectedText] becomes the preview string, which is §19's "each font preview should use the USER'S
     * SELECTED TEXT where possible" — the point being that "Aa Bb" tells you nothing about how a font sets
     * *your* sentence, and choosing a font is a judgement about your sentence.
     */
    fun open(current: FontIdentity?, selectedText: String = "", mixed: Boolean = false): FontBrowserState {
        state = state.copy(
            selected = current.takeUnless { mixed },
            mixedFonts = mixed,
            previewText = previewFor(selectedText),
            usingSelectedText = previewSourceIsSelection(selectedText),
        )
        return state
    }

    /** Preview text: the user's selection when it is usable, else a neutral pangram. */
    private fun previewFor(selectedText: String): String {
        val trimmed = selectedText.trim()
        if (!previewSourceIsSelection(selectedText)) return DEFAULT_PREVIEW
        // A very long selection would make every row a paragraph; one line is the useful unit.
        return trimmed.lineSequence().first { it.isNotBlank() }.take(PREVIEW_MAX_CHARS)
    }

    /**
     * Whether the selection is worth previewing.
     *
     * A one- or two-character selection is *worse* than the pangram: it shows almost no letterforms while
     * displacing a string chosen to show many. So short selections fall back rather than being honoured
     * literally.
     */
    private fun previewSourceIsSelection(selectedText: String): Boolean =
        selectedText.trim().let { it.length >= PREVIEW_MIN_CHARS && it.any(Char::isLetterOrDigit) }

    fun setQuery(query: String): FontBrowserState {
        state = state.copy(query = query)
        return state
    }

    fun setTab(tab: FontBrowserTab): FontBrowserState {
        state = state.copy(tab = tab)
        return state
    }

    fun setCategory(category: FontCategory?): FontBrowserState {
        state = state.copy(category = category)
        return state
    }

    fun setRole(role: FontRole?): FontBrowserState {
        state = state.copy(role = role)
        return state
    }

    fun setVariableOnly(enabled: Boolean): FontBrowserState {
        state = state.copy(variableOnly = enabled)
        return state
    }

    fun setScript(script: FontScript?): FontBrowserState {
        state = state.copy(script = script)
        return state
    }

    fun setPreviewText(text: String, fromSelection: Boolean = false): FontBrowserState {
        state = state.copy(previewText = text.ifBlank { DEFAULT_PREVIEW }, usingSelectedText = fromSelection)
        return state
    }

    /**
     * Selects a family **without closing the browser** (§19, §28).
     *
     * Applying a font must leave the browser open, because choosing type is comparative: the user applies
     * one, looks at it, and applies the next. Closing after each pick turns comparison into a dozen
     * reopenings — the §40 defect "Color Studio closes after every slider change", in its typographic form.
     */
    fun select(identity: FontIdentity): FontBrowserState {
        state = state.copy(selected = identity, mixedFonts = false)
        rememberRecent(identity)
        return state
    }

    fun clearFilters(): FontBrowserState {
        state = state.copy(query = "", category = null, role = null, script = null, variableOnly = false)
        return state
    }

    // ── Favourites and recents ───────────────────────────────────────────────────────────────────

    fun toggleFavorite(identity: FontIdentity): Boolean {
        val key = identity.encode()
        return if (!favorites.remove(key)) favorites.add(key) else false
    }

    fun isFavorite(identity: FontIdentity): Boolean = identity.encode() in favorites

    fun favoriteKeys(): List<String> = favorites.toList()

    fun restoreFavorites(keys: List<String>) {
        favorites.clear()
        favorites.addAll(keys)
    }

    fun rememberRecent(identity: FontIdentity) {
        val key = identity.encode()
        recent.removeAll { it == key }
        recent.addFirst(key)
        while (recent.size > recentLimit) recent.removeLast()
    }

    fun recentKeys(): List<String> = recent.toList()

    fun restoreRecent(keys: List<String>) {
        recent.clear()
        keys.take(recentLimit).forEach { recent.addLast(it) }
    }

    // ── Availability ─────────────────────────────────────────────────────────────────────────────

    /**
     * Records the live availability of a non-bundled family (downloading, installed, removed).
     *
     * Bundled families are deliberately *not* representable here: their availability is a fact about the
     * APK, and letting runtime state override it would allow a bug to mark a packaged font "missing" and
     * send it down a download path that does not exist for it.
     */
    fun setAvailability(identity: FontIdentity, value: FontAvailability) {
        if (identity.isGuaranteedOffline) return
        availability[identity.encode()] = value
    }

    fun availabilityOf(identity: FontIdentity): FontAvailability = when {
        identity.isGuaranteedOffline -> FontAvailability.OFFLINE_READY
        else -> availability[identity.encode()]
            ?: registry.find(identity)?.let { FontAvailability.OFFLINE_READY }
            ?: FontAvailability.MISSING
    }

    // ── Listing ──────────────────────────────────────────────────────────────────────────────────

    /**
     * The families matching the current state, deterministically ordered.
     *
     * The tab filters *which* families are eligible and the query/category/role/script filters narrow it;
     * they compose rather than override, so "Favorites + Mono + 'code'" means what it reads as.
     */
    fun results(documentFontKeys: List<String> = emptyList()): List<FontFamilyRecord> {
        val base = registry.search(
            query = state.query,
            category = state.category,
            role = state.role,
            script = state.script,
            variableOnly = state.variableOnly,
            sources = state.tab.sources(),
        )
        val filtered = when (state.tab) {
            FontBrowserTab.INSTALLED, FontBrowserTab.POPULAR -> base
            FontBrowserTab.FAVORITES -> base.filter { it.identity.encode() in favorites }
            FontBrowserTab.RECENT -> {
                // Recency order, not alphabetical: the tab's whole purpose is "what I just used".
                val order = recent.withIndex().associate { (index, key) -> key to index }
                base.filter { it.identity.encode() in order }
                    .sortedBy { order[it.identity.encode()] ?: Int.MAX_VALUE }
            }
            FontBrowserTab.DOCUMENT -> {
                val order = documentFontKeys.withIndex().associate { (index, key) -> key to index }
                base.filter { it.identity.encode() in order }
                    .sortedBy { order[it.identity.encode()] ?: Int.MAX_VALUE }
            }
        }
        val selected = state.selected
        if (selected == null || registry.find(selected) != null || filtered.any { it.identity == selected }) return filtered
        val missing = FontFamilyRecord(
            id = selected.id,
            name = selected.displayName,
            source = selected.source,
            category = FontCategory.SANS,
            portability = FontPortability.UNAVAILABLE,
        )
        return listOf(missing) + filtered
    }

    /**
     * The quick picker's sections (§19): Recent, Document fonts, Favorites — each bounded.
     *
     * Bounded because the quick picker is a shortcut, not a second browser: if it can grow to 40 rows it
     * stops being quicker than the browser it is trying to save you from opening.
     */
    fun sections(documentFontKeys: List<String> = emptyList(), perSection: Int = 4): List<FontBrowserSection> {
        fun resolve(keys: List<String>) = keys.mapNotNull { key ->
            FontIdentity.decode(key)?.let { registry.find(it) }
        }.take(perSection)

        return buildList {
            resolve(recent.toList()).takeIf { it.isNotEmpty() }
                ?.let { add(FontBrowserSection("Recent", it)) }
            resolve(documentFontKeys).takeIf { it.isNotEmpty() }
                ?.let { add(FontBrowserSection("In this document", it)) }
            resolve(favorites.toList()).takeIf { it.isNotEmpty() }
                ?.let { add(FontBrowserSection("Favorites", it)) }
            // A first-run user has no history at all, so the picker must still offer something useful.
            if (isEmpty()) {
                add(FontBrowserSection("Suggested", registry.builtIns.take(perSection)))
            }
        }
    }

    /** A row's full display state, computed without loading the font. */
    fun rowState(record: FontFamilyRecord): FontRowState {
        val identity = record.identity
        val resolution = registry.resolve(FontRequest(identity), state.previewText)
        return FontRowState(
            record = record,
            availability = availabilityOf(identity),
            favorite = isFavorite(identity),
            selected = state.selected == identity,
            previewText = state.previewText,
            // A Latin-only family cannot preview Bangla text; showing tofu would misrepresent the font.
            previewRenderable = FontScript.scriptsOf(state.previewText).all { record.covers(it) },
            substitutionLabel = resolution.substitution?.let { "Substituted with ${it.substitute.displayName}" },
        )
    }

    companion object {
        /** A pangram that exercises ascenders, descenders, digits and punctuation. */
        const val DEFAULT_PREVIEW = "Sphinx of black quartz, judge my vow — 0123"
        const val PREVIEW_MIN_CHARS = 3
        const val PREVIEW_MAX_CHARS = 64
    }
}

/** The browser's presentation-independent state; carried verbatim across form-factor changes. */
data class FontBrowserState(
    val tab: FontBrowserTab = FontBrowserTab.INSTALLED,
    val query: String = "",
    val category: FontCategory? = null,
    val role: FontRole? = null,
    val script: FontScript? = null,
    val variableOnly: Boolean = false,
    val selected: FontIdentity? = null,
    val mixedFonts: Boolean = false,
    val previewText: String = FontBrowserEngine.DEFAULT_PREVIEW,
    /** True when [previewText] came from the user's selection rather than the default pangram. */
    val usingSelectedText: Boolean = false,
) {
    val hasFilters: Boolean
        get() = query.isNotBlank() || category != null || role != null || script != null || variableOnly
}

/** The browser's top-level tabs (§19). */
enum class FontBrowserTab(val label: String) {
    INSTALLED("Installed"),
    POPULAR("Popular"),
    RECENT("Recent"),
    FAVORITES("Favorites"),
    DOCUMENT("In document"),
    ;

    /**
     * Which sources this tab draws from. "Installed" deliberately excludes downloadable-but-not-installed
     * families: a tab called Installed that listed things you have not installed would be a lie, and it is
     * the tab a user opens *to work offline*.
     */
    fun sources(): Set<FontSource> = when (this) {
        INSTALLED -> setOf(FontSource.BUILT_IN, FontSource.IMPORTED, FontSource.SYSTEM)
        POPULAR -> FontSource.entries.toSet()
        RECENT, FAVORITES, DOCUMENT -> FontSource.entries.toSet()
    }
}

/** One quick-picker section. */
data class FontBrowserSection(val title: String, val families: List<FontFamilyRecord>)

/** Everything a browser row needs, with no font loaded. */
data class FontRowState(
    val record: FontFamilyRecord,
    val availability: FontAvailability,
    val favorite: Boolean,
    val selected: Boolean,
    val previewText: String,
    val previewRenderable: Boolean,
    val substitutionLabel: String? = null,
) {
    /**
     * The state text — always textual, never colour or icon alone (§19, §31).
     *
     * Availability, and only availability. A substitution is a *different* fact — "this family is here, and it
     * cannot draw your text" — and it has its own line in the row, so returning it from here made the row say it
     * twice while saying nothing about whether the font was downloaded. The two are not alternatives: the case
     * that most needs both is a family that is offline-ready and still cannot render Bangla, where the old
     * `substitutionLabel ?:` reported the substitution and silently dropped "Offline ✓".
     *
     * [accessibleLabel] reads this and then appends the substitution separately, so the duplication was in the
     * announcement as well as on screen.
     */
    fun stateLabel(): String = when (availability) {
        FontAvailability.OFFLINE_READY -> "Offline ✓"
        FontAvailability.DOWNLOADABLE -> "Download"
        FontAvailability.DOWNLOADING -> "Downloading…"
        FontAvailability.MISSING -> "Missing"
    }

    /** The full accessibility label for the row. */
    fun accessibleLabel(): String = buildString {
        append(record.accessibleSummary())
        append(", ")
        append(stateLabel())
        if (favorite) append(", favorite")
        if (selected) append(", selected")
        substitutionLabel?.let { append(", $it") }
        if (!previewRenderable && substitutionLabel == null) append(", preview shown in a fallback font")
    }
}
