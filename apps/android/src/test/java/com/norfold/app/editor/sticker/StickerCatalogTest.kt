package com.norfold.app.editor.sticker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StickerCatalogTest {
    @Test fun popularIsStableAndLocal() {
        val popular = StickerCatalog.items()
        assertTrue(popular.isNotEmpty())
        assertEquals(popular.map { it.popularRank }, popular.map { it.popularRank }.sorted())
        assertTrue(popular.all { it.assetUri.startsWith("file:///android_asset/noto-stickers/") })
        assertEquals("Apache-2.0", StickerCatalog.SOURCE_LICENSE)
        assertEquals("THIRD_PARTY_NOTICES.md", StickerCatalog.THIRD_PARTY_NOTICE_PATH)
    }

    @Test fun searchAndCategoriesNeverExposeUnapprovedContent() {
        val dogs = StickerCatalog.items(StickerCategory.NATURE, "dog")
        assertEquals(listOf("dog"), dogs.map { it.id })
        assertTrue(StickerCatalog.items(StickerCategory.EXPRESSIONS).all(StickerCatalog::isFamilySafe))
        assertFalse(StickerCatalog.items(query = "weapon").any())
        assertNotNull(StickerCatalog.find("thumbs_up"))
    }
}
