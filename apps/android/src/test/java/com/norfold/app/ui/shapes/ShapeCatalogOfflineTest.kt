package com.norfold.app.ui.shapes

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ShapeCatalogOfflineTest {
    @Test
    fun `bundled subset resolves without touching extended URL path`() = runBlocking {
        assertTrue(ShapeCatalog.featured.size >= 12)
        ShapeCatalog.featured.forEach { item ->
            assertTrue(item.isOffline)
            assertTrue(item.id.startsWith("norfold-core:"))
            assertEquals("CC0-1.0", item.spdxLicense)
            val asset = ShapeCatalog.download(item)
            assertEquals(item.id, asset.id)
            assertTrue(asset.svg.startsWith("<svg"))
            assertTrue(asset.sourceUrl.startsWith("builtin://"))
        }
    }

    @Test(expected = ShapeCatalogException::class)
    fun `active SVG content remains rejected by authoritative sanitizer`() {
        ShapeCatalog.validateSvg("<svg><script>alert(1)</script></svg>")
    }

    @Test
    fun `extended catalogue remains a distinct on demand path`() {
        val remote = ShapeCatalogItem("ph:star", "ph", "star", "Star", "Phosphor", "MIT")
        assertTrue(!remote.isOffline)
        assertTrue(remote.svgUrl.startsWith("https://api.iconify.design/"))
    }
}
