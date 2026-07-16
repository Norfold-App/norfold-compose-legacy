package com.norfold.app.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class RenderCacheTest {

    @Before
    fun reset() {
        RenderCache.clear()
    }

    @Test
    fun `keys are stable for identical inputs and differ per theme`() {
        val light = RenderCache.key("# Hello", dark = false, accentHex = "#123456", textHex = "#000000")
        val lightAgain = RenderCache.key("# Hello", dark = false, accentHex = "#123456", textHex = "#000000")
        val dark = RenderCache.key("# Hello", dark = true, accentHex = "#123456", textHex = "#FFFFFF")

        assertEquals(light, lightAgain)
        assertNotEquals(light, dark)
    }

    @Test
    fun `light and dark artifacts coexist`() {
        val light = RenderCache.key("body", dark = false, accentHex = "#111111")
        val dark = RenderCache.key("body", dark = true, accentHex = "#111111")
        RenderCache.putHtml(light, "<light>")
        RenderCache.putHtml(dark, "<dark>")

        assertEquals("<light>", RenderCache.html(light))
        assertEquals("<dark>", RenderCache.html(dark))
    }

    @Test
    fun `height attaches to an existing entry and survives html refresh`() {
        val key = RenderCache.key("chart", dark = false, accentHex = "#222222")
        RenderCache.storeHeight(key, 300)
        assertNull(RenderCache.heightFor(key)) // no entry yet — height must not create one

        RenderCache.putHtml(key, "<html>")
        RenderCache.storeHeight(key, 300)
        assertEquals(300, RenderCache.heightFor(key))

        RenderCache.putHtml(key, "<html2>")
        assertEquals(300, RenderCache.heightFor(key))
    }

    @Test
    fun `least recently used entry is evicted first`() {
        repeat(48) { RenderCache.putHtml("key-$it", "html-$it") }
        assertEquals(48, RenderCache.size())

        RenderCache.html("key-0") // touch the eldest so key-1 becomes LRU
        RenderCache.putHtml("key-48", "html-48")

        assertEquals(48, RenderCache.size())
        assertEquals("html-0", RenderCache.html("key-0"))
        assertNull(RenderCache.html("key-1"))
    }

    @Test
    fun `evict and clear remove entries`() {
        val key = RenderCache.key("x", dark = false, accentHex = "#333333")
        RenderCache.putHtml(key, "<html>")
        RenderCache.evict(key)
        assertNull(RenderCache.html(key))

        RenderCache.putHtml(key, "<html>")
        RenderCache.clear()
        assertEquals(0, RenderCache.size())
    }

    @Test
    fun `rerenderAll clears entries and bumps the generation`() {
        val before = RenderCache.generation
        RenderCache.putHtml("a", "1")
        RenderCache.rerenderAll()

        assertEquals(0, RenderCache.size())
        assertTrue(RenderCache.generation > before)
    }
}
