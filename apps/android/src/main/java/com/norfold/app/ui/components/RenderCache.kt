package com.norfold.app.ui.components

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue

data class CachedRender(val html: String, val heightDp: Int? = null)

/**
 * Bounded LRU for rendered engine previews, keyed by content + theme so Light and Dark artifacts
 * coexist. Values pair the generated HTML document with the last measured WebView height; a cached
 * height lets a remounting preview skip the "Rendering…" flash and mount at its final size. The
 * cache wraps the render pipeline — the per-renderer fallback logic inside the page is untouched.
 */
object RenderCache {
    private const val MAX_ENTRIES = 48

    private val entries = object : LinkedHashMap<String, CachedRender>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CachedRender>) = size > MAX_ENTRIES
    }

    /** Bumped by "Re-render all" so every mounted preview reloads its WebView. */
    var generation by mutableIntStateOf(0)
        private set

    fun key(markdown: String, dark: Boolean, accentHex: String, textHex: String = ""): String =
        "${if (dark) "d" else "l"}|$accentHex|$textHex|${markdown.hashCode()}|${markdown.length}"

    @Synchronized
    fun html(key: String): String? = entries[key]?.html

    @Synchronized
    fun heightFor(key: String): Int? = entries[key]?.heightDp

    @Synchronized
    fun putHtml(key: String, html: String) {
        entries[key] = CachedRender(html, entries[key]?.heightDp)
    }

    @Synchronized
    fun storeHeight(key: String, heightDp: Int) {
        val existing = entries[key] ?: return
        entries[key] = existing.copy(heightDp = heightDp)
    }

    @Synchronized
    fun evict(key: String) {
        entries.remove(key)
    }

    @Synchronized
    fun clear() {
        entries.clear()
    }

    @Synchronized
    fun size(): Int = entries.size

    @Synchronized
    fun keys(): List<String> = entries.keys.toList()

    fun rerenderAll() {
        clear()
        generation++
    }
}
