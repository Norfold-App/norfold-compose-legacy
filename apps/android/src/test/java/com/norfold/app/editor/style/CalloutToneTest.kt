package com.norfold.app.editor.style

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a callout tone has to guarantee, all of it decidable on the desktop JVM.
 *
 * A screenshot proves that one callout looks green. It cannot prove that `success` and `tip` mean the same
 * thing, that `[!SECURITY]` from a newer build degrades instead of crashing, that a tone survives a Markdown
 * round-trip, or that the five tints are actually different colours rather than four plus a duplicate. Those
 * are the claims that break silently, so they are the ones asserted here.
 */
class CalloutToneTest {

    @Test
    fun `a blank or missing tone is the neutral note`() {
        assertEquals(CalloutTone.NOTE, CalloutTone.of(null))
        assertEquals(CalloutTone.NOTE, CalloutTone.of(""))
        assertEquals(CalloutTone.NOTE, CalloutTone.of("   "))
    }

    @Test
    fun `every canonical stored value maps back to its own tone`() {
        CalloutTone.entries.forEach { tone ->
            assertEquals("stored value of $tone", tone, CalloutTone.of(tone.stored))
        }
    }

    @Test
    fun `the aliases other authoring tools use land on the right tone`() {
        // The point of the alias table: a document from another editor would otherwise render as five
        // identical grey boxes, which looks like the feature is broken rather than like the tone is unknown.
        assertEquals(CalloutTone.NOTE, CalloutTone.of("info"))
        assertEquals(CalloutTone.NOTE, CalloutTone.of("information"))
        assertEquals(CalloutTone.NOTE, CalloutTone.of("neutral"))
        assertEquals(CalloutTone.TIP, CalloutTone.of("success"))
        assertEquals(CalloutTone.TIP, CalloutTone.of("hint"))
        assertEquals(CalloutTone.TIP, CalloutTone.of("done"))
        assertEquals(CalloutTone.IMPORTANT, CalloutTone.of("attention"))
        assertEquals(CalloutTone.IMPORTANT, CalloutTone.of("highlight"))
        assertEquals(CalloutTone.WARNING, CalloutTone.of("warn"))
        assertEquals(CalloutTone.WARNING, CalloutTone.of("alert"))
        assertEquals(CalloutTone.CAUTION, CalloutTone.of("danger"))
        assertEquals(CalloutTone.CAUTION, CalloutTone.of("error"))
        assertEquals(CalloutTone.CAUTION, CalloutTone.of("critical"))
        assertEquals(CalloutTone.CAUTION, CalloutTone.of("stop"))
    }

    @Test
    fun `case and surrounding whitespace do not make a different tone`() {
        assertEquals(CalloutTone.WARNING, CalloutTone.of("WARNING"))
        assertEquals(CalloutTone.WARNING, CalloutTone.of("Warning"))
        assertEquals(CalloutTone.WARNING, CalloutTone.of("  warning\n"))
        assertEquals(CalloutTone.CAUTION, CalloutTone.of("\tCAUTION "))
    }

    @Test
    fun `an unrecognised tone degrades to note instead of throwing`() {
        // A hand-written `[!SECURITY]`, or a tone a newer build writes. The stored string is untouched by
        // this call, so it round-trips; only the *rendering* falls back.
        assertEquals(CalloutTone.NOTE, CalloutTone.of("security"))
        assertEquals(CalloutTone.NOTE, CalloutTone.of("[!WARNING]"))
    }

    @Test
    fun `a padded canonical value is still the tone, not an unknown`() {
        assertEquals(CalloutTone.TIP, CalloutTone.of("tip "))
    }

    @Test
    fun `every stored value uppercases into a github alert keyword`() {
        // The Markdown codec emits `> [!" + tone.uppercase() + "]`, so a tone chosen in the app has to come
        // back as the same tone after an export and a re-import. Asserted as a real round-trip rather than by
        // eyeballing the table.
        CalloutTone.entries.forEach { tone ->
            val emitted = "[!" + tone.stored.uppercase() + "]"
            val keyword = emitted.removePrefix("[!").removeSuffix("]")
            assertEquals("round-trip of $tone", tone, CalloutTone.of(keyword))
            assertTrue("$tone keyword is not upper case: $keyword", keyword == keyword.uppercase())
        }
    }

    @Test
    fun `next cycles through every tone and returns to the start`() {
        // The icon is the only tone control, so a cycle that skipped a tone would make that tone unreachable
        // for anything except an import.
        var tone = CalloutTone.NOTE
        val seen = mutableListOf(tone)
        repeat(CalloutTone.entries.size - 1) {
            tone = tone.next()
            seen += tone
        }
        assertEquals(CalloutTone.entries.toList(), seen)
        assertEquals(CalloutTone.NOTE, tone.next())
    }

    @Test
    fun `each tone has a distinct tint in both schemes`() {
        assertEquals(CalloutTone.entries.size, CalloutTone.entries.map { it.lightTint }.toSet().size)
        assertEquals(CalloutTone.entries.size, CalloutTone.entries.map { it.darkTint }.toSet().size)
        assertEquals(CalloutTone.entries.size, CalloutTone.entries.map { it.lightInk }.toSet().size)
        assertEquals(CalloutTone.entries.size, CalloutTone.entries.map { it.darkInk }.toSet().size)
    }

    @Test
    fun `a light tint is lighter than its ink and a dark tint is darker`() {
        // Not a style opinion: it is what makes the icon legible on the panel. Reversing a pair would produce
        // a callout whose icon vanishes, and nothing else in the build would notice.
        CalloutTone.entries.forEach { tone ->
            assertTrue("${tone.name} light tint must be lighter than its ink", luminance(tone.lightTint) > luminance(tone.lightInk))
            assertTrue("${tone.name} dark tint must be darker than its ink", luminance(tone.darkTint) < luminance(tone.darkInk))
        }
    }

    @Test
    fun `every colour is fully opaque`() {
        // A tint drawn over the page must be the colour it claims. An accidentally translucent value would
        // shift with whatever sits behind the callout, which is exactly what the palette exists to stop.
        CalloutTone.entries.forEach { tone ->
            listOf(tone.lightTint, tone.lightInk, tone.darkTint, tone.darkInk).forEach { argb ->
                assertEquals("${tone.name} has a translucent colour", 0xFFL, (argb ushr 24) and 0xFF)
            }
        }
    }

    @Test
    fun `the neutral note is not tinted towards any hue`() {
        // The default callout must not look like a status. Grey means red, green and blue agree.
        assertTrue("light note tint is not neutral", isNeutral(CalloutTone.NOTE.lightTint))
        assertTrue("dark note tint is not neutral", isNeutral(CalloutTone.NOTE.darkTint))
        // And the tones that *are* statuses must not be grey, or the palette says nothing.
        CalloutTone.entries.filter { it != CalloutTone.NOTE }.forEach { tone ->
            assertTrue("${tone.name} light tint is grey", !isNeutral(tone.lightTint))
        }
    }

    @Test
    fun `default titles are distinct and non blank`() {
        val titles = CalloutTone.entries.map { it.defaultTitle }
        assertEquals(CalloutTone.entries.size, titles.toSet().size)
        titles.forEach { assertNotEquals("", it.trim()) }
    }

    private fun luminance(argb: Long): Double {
        val r = ((argb shr 16) and 0xFF) / 255.0
        val g = ((argb shr 8) and 0xFF) / 255.0
        val b = (argb and 0xFF) / 255.0
        return 0.2126 * r + 0.7152 * g + 0.0722 * b
    }

    private fun isNeutral(argb: Long): Boolean {
        val r = ((argb shr 16) and 0xFF).toInt()
        val g = ((argb shr 8) and 0xFF).toInt()
        val b = (argb and 0xFF).toInt()
        return maxOf(r, g, b) - minOf(r, g, b) <= 4
    }
}
