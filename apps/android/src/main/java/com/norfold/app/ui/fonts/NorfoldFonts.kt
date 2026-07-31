package com.norfold.app.ui.fonts

import android.content.Context
import androidx.compose.ui.text.font.FontFamily
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

data class FontCatalogEntry(val family: String, val category: String = "")

data class FontCatalogSnapshot(
    val all: List<FontCatalogEntry>,
    val recent: List<String>,
    val mostUsed: List<String>,
    val hidden: Set<String>,
    val rankingAvailable: Boolean,
)

/**
 * Device-local font discovery and preference store.
 *
 * Phase 3 deliberately exposes only fonts whose bytes are already available in Norfold. The earlier
 * implementation silently contacted a metadata endpoint and delegated rendering to the Play-services font
 * provider, which could make an "installed" choice fail offline and had none of the required download,
 * checksum, cancellation, licensing or atomic-install states. A future extended catalogue must arrive as a
 * separate explicit downloader; this compatibility store must remain offline.
 */
class FontCatalogStore(context: Context) {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences("norfold_font_catalog", Context.MODE_PRIVATE)

    suspend fun snapshot(refresh: Boolean = false): FontCatalogSnapshot = withContext(Dispatchers.IO) {
        val entries = loadEntries()
        val hidden = preferences.getStringSet(KEY_HIDDEN, emptySet()).orEmpty().toSet()
        val visibleNames = entries.asSequence().map(FontCatalogEntry::family).filterNot(hidden::contains).toSet()
        val recent = preferences.getString(KEY_RECENT, "").orEmpty().lineSequence()
            .filter { it in visibleNames }.distinct().take(20).toList()
        val firstEditAt = preferences.getLong(KEY_FIRST_EDIT, 0L)
        val rankingAvailable = firstEditAt > 0L && System.currentTimeMillis() - firstEditAt >= WEEK_MILLIS
        val mostUsed = if (rankingAvailable) readUsage()
            .asSequence()
            .filter { it.key in visibleNames }
            .sortedWith(compareByDescending<Map.Entry<String, Long>> { it.value }.thenBy { it.key.lowercase() })
            .take(20)
            .map(Map.Entry<String, Long>::key)
            .toList()
        else emptyList()
        FontCatalogSnapshot(entries, recent, mostUsed, hidden, rankingAvailable)
    }

    fun beginEditing(now: Long = System.currentTimeMillis()) {
        if (preferences.getLong(KEY_FIRST_EDIT, 0L) == 0L) preferences.edit().putLong(KEY_FIRST_EDIT, now).apply()
    }

    fun recordUse(family: String, authoredCharacters: Int = 0, explicitSelection: Boolean = false) {
        if (family.isBlank()) return
        beginEditing()
        val recent = sequenceOf(family) + preferences.getString(KEY_RECENT, "").orEmpty().lineSequence()
        val nextRecent = recent.filter(String::isNotBlank).distinct().take(20).joinToString("\n")
        val usage = readUsage().toMutableMap()
        val increment = authoredCharacters.coerceAtLeast(0).toLong() + if (explicitSelection) 64L else 0L
        if (increment > 0L) usage[family] = (usage[family] ?: 0L) + increment
        preferences.edit().putString(KEY_RECENT, nextRecent).putString(KEY_USAGE, JSONObject(usage).toString()).apply()
    }

    fun hide(family: String) {
        val hidden = preferences.getStringSet(KEY_HIDDEN, emptySet()).orEmpty().toMutableSet()
        hidden += family
        preferences.edit().putStringSet(KEY_HIDDEN, hidden).apply()
    }

    fun restore(families: Collection<String>) {
        val hidden = preferences.getStringSet(KEY_HIDDEN, emptySet()).orEmpty().toMutableSet()
        hidden.removeAll(families.toSet())
        preferences.edit().putStringSet(KEY_HIDDEN, hidden).apply()
    }

    private fun readUsage(): Map<String, Long> = runCatching {
        val json = JSONObject(preferences.getString(KEY_USAGE, "{}").orEmpty())
        buildMap { json.keys().forEach { family -> put(family, json.optLong(family, 0L)) } }
    }.getOrDefault(emptyMap())

    private fun loadEntries(): List<FontCatalogEntry> =
        OfflineFontLibrary.registry.all
            .map { FontCatalogEntry(it.name, it.category.label) }
            .distinctBy { it.family.lowercase() }
            .sortedBy { it.family.lowercase() }
            .ifEmpty { FALLBACK_FONTS.map(::FontCatalogEntry) }

    private companion object {
        const val KEY_HIDDEN = "hidden"
        const val KEY_RECENT = "recent"
        const val KEY_USAGE = "usage"
        const val KEY_FIRST_EDIT = "first_edit_at"
        const val WEEK_MILLIS = 7L * 24L * 60L * 60L * 1000L
        val FALLBACK_FONTS = listOf(
            "Roboto", "Open Sans", "Noto Sans", "Noto Serif", "Lato", "Montserrat", "Inter",
            "Source Sans 3", "Source Serif 4", "Merriweather", "Nunito", "Poppins", "Playfair Display",
            "Fira Sans", "Fira Code", "IBM Plex Sans", "IBM Plex Serif", "Atkinson Hyperlegible",
        )
    }
}

/**
 * Resolves a family *name* to a renderable [FontFamily].
 *
 * Name-keyed because that is what pre-Phase-3 documents stored; new writes carry a
 * [com.norfold.app.domain.style.FontIdentity] and should go through [OfflineFontLibrary] directly.
 *
 * The resolver is offline by construction. Unknown legacy names use a system generic and the stored name
 * remains untouched, so the editor can label the substitution instead of triggering an invisible download.
 */
object NorfoldFontFamilies {
    fun resolve(name: String?): FontFamily = when (name?.trim()?.lowercase()) {
        null, "", "sans", "sans serif", "sans-serif" -> FontFamily.SansSerif
        "default", "system" -> FontFamily.Default
        "serif" -> FontFamily.Serif
        "mono", "monospace" -> FontFamily.Monospace
        "cursive" -> FontFamily.Cursive
        else -> OfflineFontLibrary.fontForLegacyName(name) ?: FontFamily.SansSerif
    }
}
