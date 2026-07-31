package com.norfold.app.editor.style

import com.norfold.app.domain.style.FontAvailability
import com.norfold.app.domain.style.FontCategory
import com.norfold.app.domain.style.FontFamilyRecord
import com.norfold.app.domain.style.FontIdentity
import com.norfold.app.domain.style.FontPortability
import com.norfold.app.domain.style.FontRegistry
import com.norfold.app.domain.style.FontRole
import com.norfold.app.domain.style.FontScript
import com.norfold.app.domain.style.FontSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FontBrowserEngineTest {
    private val sans = family("sans", "Alpha Sans", roles = setOf(FontRole.PROFESSIONAL))
    private val mono = family("mono", "Beta Mono", category = FontCategory.MONO, roles = setOf(FontRole.CODE))
    private val bangla = family("bangla", "Bangla Serif", scripts = setOf(FontScript.LATIN, FontScript.BENGALI))
    private val registry = FontRegistry(listOf(sans, mono, bangla))

    @Test fun `search and composed filters are deterministic`() {
        val browser = FontBrowserEngine(registry)
        browser.setQuery("beta")
        browser.setCategory(FontCategory.MONO)
        browser.setRole(FontRole.CODE)
        val first = browser.results()
        assertEquals(listOf(mono.identity), first.map { it.identity })
        assertEquals(first, browser.results())
    }

    @Test fun `quick picker includes recent document and favorite sections`() {
        val browser = FontBrowserEngine(registry)
        browser.rememberRecent(sans.identity)
        browser.toggleFavorite(mono.identity)
        val sections = browser.sections(listOf(bangla.identity.encode()))
        assertEquals(listOf("Recent", "In this document", "Favorites"), sections.map { it.title })
        assertEquals(sans.identity, sections[0].families.single().identity)
        assertEquals(bangla.identity, sections[1].families.single().identity)
        assertEquals(mono.identity, sections[2].families.single().identity)
    }

    @Test fun `selected text drives preview and missing selection remains visible`() {
        val browser = FontBrowserEngine(registry)
        val missing = FontIdentity.imported("gone", "Removed Font")
        browser.open(missing, "My selected sentence")
        assertTrue(browser.state().usingSelectedText)
        val row = browser.results().first()
        assertEquals(missing, row.identity)
        val state = browser.rowState(row)
        assertEquals(FontAvailability.MISSING, state.availability)
        assertNotNull(state.substitutionLabel)
        assertTrue(state.accessibleLabel().contains("Substituted"))
    }

    @Test fun `download availability is explicit and independent from built ins`() {
        val remote = family("remote", "Remote", source = FontSource.DOWNLOADABLE)
        val browser = FontBrowserEngine(FontRegistry(listOf(sans, remote)))
        browser.setAvailability(remote.identity, FontAvailability.DOWNLOADABLE)
        assertEquals("Download", browser.rowState(remote).stateLabel())
        browser.setAvailability(remote.identity, FontAvailability.DOWNLOADING)
        assertEquals("Downloading…", browser.rowState(remote).stateLabel())
        assertEquals("Offline ✓", browser.rowState(sans).stateLabel())
    }

    private fun family(
        id: String,
        name: String,
        source: FontSource = FontSource.BUILT_IN,
        category: FontCategory = FontCategory.SANS,
        roles: Set<FontRole> = emptySet(),
        scripts: Set<FontScript> = setOf(FontScript.LATIN),
    ) = FontFamilyRecord(
        id = id,
        name = name,
        source = source,
        category = category,
        roles = roles,
        packagedScripts = scripts,
        portability = if (source == FontSource.BUILT_IN) FontPortability.BUILT_IN else FontPortability.DOWNLOADABLE_SHAREABLE,
    )
}
