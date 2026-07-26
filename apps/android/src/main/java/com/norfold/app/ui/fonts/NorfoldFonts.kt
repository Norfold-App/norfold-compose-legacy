package com.norfold.app.ui.fonts

import android.content.Context
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.googlefonts.Font
import androidx.compose.ui.text.googlefonts.GoogleFont
import com.norfold.app.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentHashMap

data class FontCatalogEntry(val family: String, val category: String = "")

data class FontCatalogSnapshot(
    val all: List<FontCatalogEntry>,
    val recent: List<String>,
    val mostUsed: List<String>,
    val hidden: Set<String>,
    val rankingAvailable: Boolean,
)

/**
 * Device-local font discovery and preference store. Font binaries are delivered and cached by the
 * verified Google Play services provider; only catalog metadata and user preference counters live
 * in Norfold. No authored document text is sent to the catalog endpoint or stored in these stats.
 */
class FontCatalogStore(context: Context) {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences("norfold_font_catalog", Context.MODE_PRIVATE)
    private val cacheFile get() = appContext.filesDir.resolve("google-fonts-catalog.json")

    suspend fun snapshot(refresh: Boolean = false): FontCatalogSnapshot = withContext(Dispatchers.IO) {
        val entries = loadEntries(refresh)
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

    private fun loadEntries(refresh: Boolean): List<FontCatalogEntry> {
        if (!refresh && cacheFile.isFile) parseCatalog(cacheFile.readText())?.let { return it }
        val downloaded = runCatching {
            val connection = (URL(CATALOG_URL).openConnection() as HttpURLConnection).apply {
                connectTimeout = 12_000
                readTimeout = 20_000
                requestMethod = "GET"
                setRequestProperty("Accept", "application/json")
            }
            try {
                require(connection.responseCode in 200..299) { "Font catalog HTTP ${connection.responseCode}" }
                connection.inputStream.use { input ->
                    val output = ByteArrayOutputStream()
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var total = 0
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        require(total <= MAX_CATALOG_BYTES) { "Font catalog response is too large" }
                        output.write(buffer, 0, read)
                    }
                    output.toString(Charsets.UTF_8.name())
                }
            } finally {
                connection.disconnect()
            }
        }.getOrNull()
        if (downloaded != null) {
            parseCatalog(downloaded)?.let { parsed ->
                runCatching { cacheFile.writeText(downloaded) }
                return parsed
            }
        }
        if (cacheFile.isFile) parseCatalog(cacheFile.readText())?.let { return it }
        return FALLBACK_FONTS.map { FontCatalogEntry(it) }
    }

    private fun parseCatalog(source: String): List<FontCatalogEntry>? = runCatching {
        val items = JSONObject(source).getJSONArray("familyMetadataList")
        buildList(items.length()) {
            repeat(items.length()) { index ->
                val item = items.getJSONObject(index)
                val family = item.optString("family").trim()
                if (family.isNotEmpty()) add(FontCatalogEntry(family, item.optString("category")))
            }
        }.distinctBy { it.family }.sortedBy { it.family.lowercase() }
    }.getOrNull()?.takeIf(List<FontCatalogEntry>::isNotEmpty)

    private companion object {
        const val CATALOG_URL = "https://fonts.google.com/metadata/fonts"
        const val KEY_HIDDEN = "hidden"
        const val KEY_RECENT = "recent"
        const val KEY_USAGE = "usage"
        const val KEY_FIRST_EDIT = "first_edit_at"
        const val WEEK_MILLIS = 7L * 24L * 60L * 60L * 1000L
        const val MAX_CATALOG_BYTES = 4 * 1024 * 1024
        val FALLBACK_FONTS = listOf(
            "Roboto", "Open Sans", "Noto Sans", "Noto Serif", "Lato", "Montserrat", "Inter",
            "Source Sans 3", "Source Serif 4", "Merriweather", "Nunito", "Poppins", "Playfair Display",
            "Fira Sans", "Fira Code", "IBM Plex Sans", "IBM Plex Serif", "Atkinson Hyperlegible",
        )
    }
}

object NorfoldFontFamilies {
    private val provider = GoogleFont.Provider(
        providerAuthority = "com.google.android.gms.fonts",
        providerPackage = "com.google.android.gms",
        certificates = R.array.com_google_android_gms_fonts_certs,
    )
    private val cache = ConcurrentHashMap<String, FontFamily>()

    fun resolve(name: String?): FontFamily = when (name?.trim()?.lowercase()) {
        null, "", "sans", "sans serif", "sans-serif" -> FontFamily.SansSerif
        "default", "system" -> FontFamily.Default
        "serif" -> FontFamily.Serif
        "mono", "monospace" -> FontFamily.Monospace
        "cursive" -> FontFamily.Cursive
        else -> cache.getOrPut(name.trim()) {
            val google = GoogleFont(name.trim())
            FontFamily(
                Font(googleFont = google, fontProvider = provider, weight = FontWeight.Normal),
                Font(googleFont = google, fontProvider = provider, weight = FontWeight.Bold),
                Font(googleFont = google, fontProvider = provider, weight = FontWeight.Normal, style = FontStyle.Italic),
                Font(googleFont = google, fontProvider = provider, weight = FontWeight.Bold, style = FontStyle.Italic),
            )
        }
    }
}
