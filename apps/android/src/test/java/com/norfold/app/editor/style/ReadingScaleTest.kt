package com.norfold.app.editor.style

import com.norfold.app.domain.AppSettings
import com.norfold.app.domain.EditorLineWidth
import com.norfold.app.domain.ThemeMode
import com.norfold.app.domain.ThemeProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The reading-scale contract, asserted on the desktop JVM.
 *
 * These are the claims a device screenshot cannot settle: that the stored default still means "unchanged",
 * that a heading is still a heading at both ends of the slider, and that the measure cap is a cap. The
 * arithmetic is deliberately in pure Kotlin so all of it is checkable here.
 */
class ReadingScaleTest {

    /**
     * Defaults as shipped. Only the six parameters `AppSettings` requires are supplied, so every editor
     * preference below is the real default rather than one this test chose.
     */
    private fun shipped() = AppSettings(
        themeMode = ThemeMode.System,
        themeProfile = ThemeProfile.Norfold,
        accentColor = 0xFF6F36FF,
        backupFolderUri = null,
        vaultLockEnabled = false,
        vaultSecretHash = null,
    )

    private fun assertClose(expected: Float, actual: Float, message: String = "") {
        assertEquals(message, expected.toDouble(), actual.toDouble(), 0.001)
    }

    // ── The upgrade-safety property ───────────────────────────────────────────────────────────────────

    /**
     * The one test that must never be relaxed. `editorFontSize` has been persisted at 0.55f since long
     * before anything read it, so if that value does not map to exactly 1.0x, shipping this feature reflows
     * every existing note on upgrade — a data-visible change nobody asked for.
     */
    @Test
    fun theStoredDefault_meansExactlyOneX() {
        assertClose(1f, ReadingScale.multiplier(ReadingScale.DEFAULT_FRACTION), "the anchor is 1.0x")
        assertClose(
            ReadingScale.DEFAULT_FRACTION,
            shipped().editorFontSize,
            "and the anchor is the value AppSettings actually ships",
        )
        assertClose(ReadingScale.BASE_BODY_SP, ReadingScale.bodySp(ReadingScale.DEFAULT_FRACTION))
    }

    @Test
    fun theEndpoints_areTheDeclaredRange() {
        assertClose(ReadingScale.MIN, ReadingScale.multiplier(0f))
        assertClose(ReadingScale.MAX, ReadingScale.multiplier(1f))
    }

    /**
     * A linear map would put the untouched default at 1.325x. Asserted as a distinct claim because it is the
     * whole reason the map is piecewise: the two halves have different slopes, and that is intended.
     */
    @Test
    fun theMap_isPiecewiseAroundTheAnchor_notLinearAcrossTheRange() {
        val linearAtDefault = ReadingScale.MIN + (ReadingScale.MAX - ReadingScale.MIN) * ReadingScale.DEFAULT_FRACTION
        assertTrue("a linear map would have moved every note", linearAtDefault > 1.3f)

        val lowerSlope = (1f - ReadingScale.MIN) / ReadingScale.DEFAULT_FRACTION
        val upperSlope = (ReadingScale.MAX - 1f) / (1f - ReadingScale.DEFAULT_FRACTION)
        assertTrue("the halves genuinely differ", kotlin.math.abs(lowerSlope - upperSlope) > 0.1f)
    }

    // ── Well-behavedness ─────────────────────────────────────────────────────────────────────────────

    @Test
    fun theMap_risesWithoutStepsOrPlateaus() {
        var previous = ReadingScale.multiplier(0f)
        for (step in 1..100) {
            val next = ReadingScale.multiplier(step / 100f)
            assertTrue("step $step went backwards", next > previous)
            assertTrue("no step jumps", next - previous < 0.05f)
            previous = next
        }
    }

    @Test
    fun fraction_roundTripsThroughMultiplier() {
        for (step in 0..100) {
            val f = step / 100f
            assertClose(f, ReadingScale.fraction(ReadingScale.multiplier(f)), "round trip at $f")
        }
    }

    @Test
    fun multiplier_roundTripsThroughFraction() {
        for (step in 0..30) {
            val m = ReadingScale.MIN + (ReadingScale.MAX - ReadingScale.MIN) * (step / 30f)
            assertClose(m, ReadingScale.multiplier(ReadingScale.fraction(m)), "round trip at ${m}x")
        }
    }

    /**
     * Both directions survive garbage. A stored float arrives from Room, a backup CSV cell and a slider, so
     * "who validated this" has three answers and the safe one is "the function itself".
     *
     * An out-of-range but finite value clamps, because its intent is legible. A non-finite one falls back to
     * 1.0x instead: a NaN in the settings row means the stored value is corrupt, and guessing "the reader
     * wanted the smallest type" from it would be worse than showing them an unchanged document.
     */
    @Test
    fun garbageIn_clampsRatherThanPropagates() {
        assertClose(ReadingScale.MIN, ReadingScale.multiplier(-3f))
        assertClose(ReadingScale.MAX, ReadingScale.multiplier(9f))
        assertClose(1f, ReadingScale.multiplier(Float.NaN), "NaN falls back to the anchor")
        assertClose(1f, ReadingScale.multiplier(Float.NEGATIVE_INFINITY), "so does an infinity")
        assertClose(1f, ReadingScale.multiplier(Float.POSITIVE_INFINITY))

        assertClose(0f, ReadingScale.fraction(0.1f))
        assertClose(1f, ReadingScale.fraction(50f))
        assertClose(ReadingScale.DEFAULT_FRACTION, ReadingScale.fraction(Float.NaN))
    }

    // ── Hierarchy ────────────────────────────────────────────────────────────────────────────────────

    /**
     * The reason roles are ratios rather than an absolute table: at 2.0x an absolute table would leave body
     * text and an H3 the same size. Every role keeps its distance from the paragraph at every step.
     */
    @Test
    fun everyRole_keepsItsProportionAtEveryStep() {
        for (step in 0..20) {
            val f = step / 20f
            val body = ReadingScale.bodySp(f)
            for (role in ContentRole.entries) {
                assertClose(
                    body * role.sizeRatio,
                    ReadingScale.sizeSp(role, f),
                    "$role at $f",
                )
            }
        }
    }

    @Test
    fun headings_descendStrictlyAndOnlyH5MatchesTheParagraph() {
        val order = listOf(
            ContentRole.HEADING_1,
            ContentRole.HEADING_2,
            ContentRole.HEADING_3,
            ContentRole.HEADING_4,
            ContentRole.HEADING_5,
            ContentRole.HEADING_6,
        )
        order.zipWithNext { larger, smaller ->
            assertTrue("$larger must outrank $smaller", larger.sizeRatio > smaller.sizeRatio)
        }
        assertTrue("H1..H4 sit above the paragraph", order.take(4).all { it.sizeRatio > 1f })
        assertClose(1f, ContentRole.HEADING_5.sizeRatio, "H5 is paragraph-sized, distinguished by weight")
        assertTrue("H6 sits below it", ContentRole.HEADING_6.sizeRatio < 1f)
    }

    @Test
    fun headingRoles_mapFromLevel_andClampOutOfRange() {
        assertEquals(ContentRole.HEADING_1, ContentRole.heading(1))
        assertEquals(ContentRole.HEADING_6, ContentRole.heading(6))
        assertEquals("level 0 cannot exist but must not crash", ContentRole.HEADING_1, ContentRole.heading(0))
        assertEquals(ContentRole.HEADING_6, ContentRole.heading(99))
    }

    /**
     * A large heading needs proportionally *less* leading than a paragraph, not more. Stated as a test
     * because the tempting shortcut — one line ratio for everything — is what makes big headings look loose.
     */
    @Test
    fun lineHeight_tightensAsTypeGrows() {
        assertTrue(ContentRole.HEADING_1.lineRatio < ContentRole.BODY.lineRatio)
        assertTrue(ContentRole.HEADING_1.lineRatio < ContentRole.HEADING_3.lineRatio)
        for (role in ContentRole.entries) {
            assertTrue("$role must not overlap its own ascenders", role.lineRatio > 1.15f)
            assertClose(
                ReadingScale.sizeSp(role, 0.7f) * role.lineRatio,
                ReadingScale.lineHeightSp(role, 0.7f),
                "$role line height is a ratio of its own size",
            )
        }
    }

    @Test
    fun code_isSlightlySmallerThanProse_butNotSubordinate() {
        assertTrue(ContentRole.CODE.sizeRatio < ContentRole.BODY.sizeRatio)
        assertTrue("and not as small as a caption", ContentRole.CODE.sizeRatio > ContentRole.SMALL.sizeRatio)
        assertTrue("mono needs room for wrapped lines", ContentRole.CODE.lineRatio > ContentRole.BODY.lineRatio)
    }

    // ── The measure cap ──────────────────────────────────────────────────────────────────────────────

    @Test
    fun theCharacterBudget_bracketsTheConventionalBand() {
        assertTrue(
            "Comfortable sits inside 65–75 characters",
            EditorLineWidth.Comfortable.contentCharacters in 65..75,
        )
        assertTrue(
            EditorLineWidth.Narrow.contentCharacters < EditorLineWidth.Comfortable.contentCharacters,
        )
        assertTrue(
            EditorLineWidth.Comfortable.contentCharacters < EditorLineWidth.Wide.contentCharacters,
        )
        assertEquals(
            "and the default preference is the comfortable one",
            EditorLineWidth.Comfortable,
            shipped().editorLineWidth,
        )
    }

    /**
     * The measure tracks the type. A cap frozen in dp would hold ~70 characters at 1.0x and ~35 at 2.0x,
     * which is the opposite of what a reader enlarging text is asking for.
     */
    @Test
    fun theMeasure_growsWithTheReadingScale() {
        val characters = EditorLineWidth.Comfortable.contentCharacters
        val small = ReadingScale.measureSp(0f, characters)
        val default = ReadingScale.measureSp(ReadingScale.DEFAULT_FRACTION, characters)
        val large = ReadingScale.measureSp(1f, characters)

        assertTrue(small < default)
        assertTrue(default < large)
        assertClose(
            ReadingScale.BASE_BODY_SP * ReadingScale.AVERAGE_GLYPH_EM * characters,
            default,
            "at 1.0x the measure is exactly the character budget",
        )
        assertClose(2f, large / default, "and it scales with the multiplier, not with something else")
    }

    @Test
    fun theMeasure_ordersTheThreeWidthPreferences() {
        val f = ReadingScale.DEFAULT_FRACTION
        val widths = EditorLineWidth.entries.map { ReadingScale.measureSp(f, it.contentCharacters) }
        assertEquals("no two preferences collapse onto one width", widths.size, widths.distinct().size)
        assertEquals(widths.sorted(), widths)
    }

    @Test
    fun anAbsurdCharacterBudget_clampsToSomethingRenderable() {
        val f = ReadingScale.DEFAULT_FRACTION
        assertClose(ReadingScale.measureSp(f, 20), ReadingScale.measureSp(f, -5))
        assertClose(ReadingScale.measureSp(f, 200), ReadingScale.measureSp(f, 10_000))
    }

    // ── The label ────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun theLabel_readsAsAPercentage() {
        assertEquals("100%", ReadingScale.percentLabel(ReadingScale.DEFAULT_FRACTION))
        assertEquals("50%", ReadingScale.percentLabel(0f))
        assertEquals("200%", ReadingScale.percentLabel(1f))
        assertEquals("a garbage fraction still labels the anchor", "100%", ReadingScale.percentLabel(Float.NaN))
    }
}
