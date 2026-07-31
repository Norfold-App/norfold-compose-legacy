package com.norfold.app.domain.style

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Named Styles: the cascade, mixed state, and tokens (roadmap Phase 3 §10–§13, §37.17–§37.28).
 *
 * The model's defining choice is that styles are **sparse**: a property that is absent means "inherit",
 * which is a *different fact* from "holds the same value as the parent". Most of the tests below are that
 * distinction observed from the outside — a full-snapshot style model would pass a naive equality test and
 * fail every one of these.
 */
class NamedStylesTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val sheet = Stylesheet.defaults()

    private fun body() = StyleAssignment(styleId = Stylesheet.BODY)
    private fun heading1() = StyleAssignment(styleId = Stylesheet.headingId(1))

    // ── §37.17 the cascade ───────────────────────────────────────────────────────────────────────

    /**
     * Editing Body's font moves every derived style that does not assert its own.
     *
     * This is the property a snapshot model cannot have: if Heading 1 had been created by copying Body's
     * full resolved value set, it would carry its own frozen copy of the old font and stay behind.
     */
    @Test
    fun `changing the base font propagates to derived styles`() {
        val before = StyleResolver.resolve(sheet, heading1(), StyleProperty.FONT_FAMILY)
        val edited = sheet.withStyle(
            sheet.style(Stylesheet.BODY)!!.with(StyleProperty.FONT_FAMILY, StyleValue.Literal("builtin:lora")),
        )
        assertEquals(
            "Heading 1 must follow Body's font",
            "builtin:lora",
            StyleResolver.resolve(edited, heading1(), StyleProperty.FONT_FAMILY),
        )
        assertNotEquals(before, StyleResolver.resolve(edited, heading1(), StyleProperty.FONT_FAMILY))
    }

    /**
     * A derived style that asserts its own value is *not* moved by the base.
     *
     * Heading sizes are the obvious case: making Body 18 pt must not make every heading 18 pt.
     */
    @Test
    fun `a derived style's own assertions survive a base edit`() {
        val h1Size = StyleResolver.resolve(sheet, heading1(), StyleProperty.FONT_SIZE)
        assertNotNull("Heading 1 asserts its own size", h1Size)
        val edited = sheet.withStyle(
            sheet.style(Stylesheet.BODY)!!.with(StyleProperty.FONT_SIZE, StyleValue.Literal("18")),
        )
        assertEquals(h1Size, StyleResolver.resolve(edited, heading1(), StyleProperty.FONT_SIZE))
        assertEquals("18", StyleResolver.resolve(edited, body(), StyleProperty.FONT_SIZE))
    }

    @Test
    fun `derived styles store only their deltas`() {
        val h1 = sheet.style(Stylesheet.headingId(1))!!
        assertEquals(Stylesheet.BODY, h1.basedOn)
        assertFalse(
            "a delta-only style must not restate the base's font family",
            h1.asserts(StyleProperty.FONT_FAMILY),
        )
        assertTrue("but it does assert what makes it a heading", h1.asserts(StyleProperty.FONT_SIZE))
    }

    /** A malformed cycle in persisted data must render, not hang. */
    @Test
    fun `a cyclic basedOn chain is broken rather than followed`() {
        val a = StyleDefinition(id = "a", name = "A", category = StyleCategory.TEXT, basedOn = "b")
        val b = StyleDefinition(id = "b", name = "B", category = StyleCategory.TEXT, basedOn = "a")
        val cyclic = Stylesheet(styles = listOf(a, b))
        val chain = StyleResolver.chainOf(cyclic, "a")
        assertEquals(listOf("a", "b"), chain.map { it.id })
        assertNull(StyleResolver.resolve(cyclic, StyleAssignment(styleId = "a"), StyleProperty.FONT_SIZE))
    }

    // ── §37.18–§37.19 local overrides ────────────────────────────────────────────────────────────

    /**
     * A local bold survives a change to the style's font family.
     *
     * This is §10's headline requirement and the reason overrides are per-property. A whole-run override
     * would be replaced wholesale by the style edit, silently un-bolding the word.
     */
    @Test
    fun `a local bold survives the style's font changing`() {
        val assignment = body().override(StyleProperty.FONT_WEIGHT, StyleValue.Literal("700"))
        val edited = sheet.withStyle(
            sheet.style(Stylesheet.BODY)!!.with(StyleProperty.FONT_FAMILY, StyleValue.Literal("builtin:lora")),
        )
        assertEquals("700", StyleResolver.resolve(edited, assignment, StyleProperty.FONT_WEIGHT))
        assertEquals(
            "the font must still follow the style",
            "builtin:lora",
            StyleResolver.resolve(edited, assignment, StyleProperty.FONT_FAMILY),
        )
    }

    /** An override shadows the style; clearing it returns the target to inheritance. */
    @Test
    fun `clearing an override restores inheritance rather than freezing the value`() {
        val styleSize = StyleResolver.resolve(sheet, body(), StyleProperty.FONT_SIZE)
        val overridden = body().override(StyleProperty.FONT_SIZE, StyleValue.Literal("42"))
        assertEquals("42", StyleResolver.resolve(sheet, overridden, StyleProperty.FONT_SIZE))
        assertEquals(ValueOrigin.LOCAL, StyleResolver.originOf(sheet, overridden, StyleProperty.FONT_SIZE))

        val cleared = overridden.clearOverride(StyleProperty.FONT_SIZE)
        assertEquals(styleSize, StyleResolver.resolve(sheet, cleared, StyleProperty.FONT_SIZE))
        assertEquals(ValueOrigin.INHERITED, StyleResolver.originOf(sheet, cleared, StyleProperty.FONT_SIZE))
        // And it now follows subsequent style edits again — a "frozen" clear would not.
        val edited = sheet.withStyle(
            sheet.style(Stylesheet.BODY)!!.with(StyleProperty.FONT_SIZE, StyleValue.Literal("13")),
        )
        assertEquals("13", StyleResolver.resolve(edited, cleared, StyleProperty.FONT_SIZE))
    }

    /**
     * Origin is inspectable, so the UI can tell "overridden" from "inherited".
     *
     * §40 names "local override is visually indistinguishable from inheritance" as a defect; this is the
     * datum that makes the fix possible, and the accessible label must carry it too.
     */
    @Test
    fun `origin distinguishes local from inherited from default`() {
        val overridden = body().override(StyleProperty.FONT_SIZE, StyleValue.Literal("42"))
        assertEquals(ValueOrigin.LOCAL, StyleResolver.describe(sheet, overridden, StyleProperty.FONT_SIZE).origin)
        assertEquals(ValueOrigin.INHERITED, StyleResolver.describe(sheet, body(), StyleProperty.FONT_SIZE).origin)
        assertEquals(
            ValueOrigin.DEFAULT,
            StyleResolver.describe(sheet, StyleAssignment.NONE, StyleProperty.LETTER_SPACING).origin,
        )
        val label = StyleResolver.describe(sheet, overridden, StyleProperty.FONT_SIZE).accessibleLabel()
        assertTrue("a screen reader must hear that this is a local override: '$label'", label.contains("verride"))
        val inherited = StyleResolver.describe(sheet, body(), StyleProperty.FONT_SIZE).accessibleLabel()
        assertTrue("and that this one is inherited: '$inherited'", inherited.contains("nherit"))
    }

    @Test
    fun `describe names the style that provided an inherited value`() {
        val state = StyleResolver.describe(sheet, heading1(), StyleProperty.FONT_FAMILY)
        assertEquals(ValueOrigin.INHERITED, state.origin)
        assertEquals("Body", state.providedBy)
    }

    // ── §37.20–§37.21 mixed state ────────────────────────────────────────────────────────────────

    /** Two Body paragraphs and one Quote report a mixed style rather than picking a winner. */
    @Test
    fun `a mixed selection reports mixed rather than the first value`() {
        val shared = StyleResolver.sharedStyleId(
            listOf(body(), body(), StyleAssignment(styleId = Stylesheet.QUOTE)),
        )
        assertEquals(MixedState.MIXED, shared.state)
        assertNull("no style may be claimed for a mixed selection", shared.styleId)
        assertEquals(MixedState.UNIFORM, StyleResolver.sharedStyleId(listOf(body(), body())).state)
        assertEquals(MixedState.UNAVAILABLE, StyleResolver.sharedStyleId(emptyList()).state)
    }

    /**
     * Same value but different origin is **mixed**.
     *
     * The strict rule exists because the available *action* differs: "clear override" would change one
     * paragraph and not the other, so calling them uniform would offer a button whose effect the inspector
     * cannot describe.
     */
    @Test
    fun `same value with different origin is mixed`() {
        val inheritedSize = StyleResolver.resolve(sheet, body(), StyleProperty.FONT_SIZE)!!
        val overriddenToTheSameValue = body().override(StyleProperty.FONT_SIZE, StyleValue.Literal(inheritedSize))
        val state = StyleResolver.describeAcross(
            sheet,
            listOf(body(), overriddenToTheSameValue),
            StyleProperty.FONT_SIZE,
        )
        assertEquals(
            "identical values from different origins are still mixed",
            MixedState.MIXED,
            state.state,
        )
        assertEquals(2, state.targetCount)
        assertEquals("and the inspector can say how many are overridden", 1, state.overrideCount)
        assertTrue(state.displayValue().isNotBlank())
    }

    @Test
    fun `a uniform selection reports the value and its origin`() {
        val state = StyleResolver.describeAcross(sheet, listOf(body(), body(), body()), StyleProperty.FONT_SIZE)
        assertEquals(MixedState.UNIFORM, state.state)
        assertEquals(ValueOrigin.INHERITED, state.origin)
        assertNotNull(state.value)
        assertEquals(3, state.targetCount)
        assertEquals(0, state.overrideCount)
    }

    @Test
    fun `an empty selection is unavailable rather than default`() {
        val state = StyleResolver.describeAcross(sheet, emptyList(), StyleProperty.FONT_SIZE)
        assertEquals(MixedState.UNAVAILABLE, state.state)
        assertEquals(0, state.targetCount)
    }

    // ── §37.22 shadowing ─────────────────────────────────────────────────────────────────────────

    /**
     * An edit that would be invisible is detectable *before* it is made.
     *
     * Both shadowing shapes are covered: a local override, and a nearer style in the chain asserting the
     * property. Without this the user edits Body's size, sees the headings not move, and concludes the
     * editor is broken.
     */
    @Test
    fun `shadowed edits are detectable and unrelated targets are not flagged`() {
        val overridden = body().override(StyleProperty.FONT_SIZE, StyleValue.Literal("42"))
        assertTrue(
            "a local override shadows the style edit",
            StyleResolver.isShadowedBy(sheet, overridden, StyleProperty.FONT_SIZE, Stylesheet.BODY),
        )
        assertTrue(
            "Heading 1 asserts its own size, so a Body size edit is shadowed",
            StyleResolver.isShadowedBy(sheet, heading1(), StyleProperty.FONT_SIZE, Stylesheet.BODY),
        )
        assertFalse(
            "but Heading 1 does follow Body's font family",
            StyleResolver.isShadowedBy(sheet, heading1(), StyleProperty.FONT_FAMILY, Stylesheet.BODY),
        )
        assertFalse(
            "content that does not reference the edited style is unrelated, not shadowed",
            StyleResolver.isShadowedBy(
                sheet,
                StyleAssignment(styleId = Stylesheet.CODE),
                StyleProperty.FONT_SIZE,
                "style.nonexistent",
            ),
        )
    }

    @Test
    fun `affected consumer count excludes shadowed targets`() {
        val assignments = listOf(
            body(),
            body(),
            body().override(StyleProperty.FONT_SIZE, StyleValue.Literal("42")),
            heading1(),
            StyleAssignment(styleId = "style.unrelated"),
        )
        val affected = StyleResolver.consumersAffectedBy(
            sheet,
            assignments,
            StyleProperty.FONT_SIZE,
            Stylesheet.BODY,
        )
        assertEquals("only the two plain Body targets would visibly change", 2, affected)
    }

    // ── §37.23–§37.24 style lifecycle ────────────────────────────────────────────────────────────

    /** Renaming a style keeps every reference, because references are ids. */
    @Test
    fun `renaming a style does not break references`() {
        val renamed = sheet.renameStyle(Stylesheet.BODY, "Prose")
        assertEquals("Prose", renamed.style(Stylesheet.BODY)!!.name)
        assertNotNull(
            "content still resolves through the same id",
            StyleResolver.resolve(renamed, body(), StyleProperty.FONT_SIZE),
        )
        assertEquals(
            "and derived styles still find their base",
            "Prose",
            StyleResolver.describe(renamed, heading1(), StyleProperty.FONT_FAMILY).providedBy,
        )
    }

    @Test
    fun `built in styles cannot be removed out from under content`() {
        val definition = sheet.style(Stylesheet.BODY)!!
        assertTrue("Body is built in", definition.builtIn)
    }

    /** Deleting a style must not orphan its children into an unresolvable chain. */
    @Test
    fun `removing a style repoints its derived styles`() {
        val custom = StyleDefinition(id = "style.custom", name = "Custom", category = StyleCategory.TEXT, basedOn = Stylesheet.BODY)
        val child = StyleDefinition(id = "style.child", name = "Child", category = StyleCategory.TEXT, basedOn = "style.custom")
        val withCustom = sheet.withStyles(listOf(custom, child))
        val removed = withCustom.removeStyle("style.custom")
        assertNull(removed.style("style.custom"))
        val chain = StyleResolver.chainOf(removed, "style.child")
        assertTrue(
            "the child must still reach a real base, chain was ${chain.map { it.id }}",
            chain.any { it.id == Stylesheet.BODY },
        )
    }

    // ── §37.25–§37.28 tokens ─────────────────────────────────────────────────────────────────────

    /** A token reference resolves dynamically, so editing the token moves every consumer. */
    @Test
    fun `editing a token value updates every consumer without rewriting them`() {
        val tokenId = Stylesheet.TOKEN_ACCENT
        val styled = sheet.withStyle(
            sheet.style(Stylesheet.BODY)!!.with(StyleProperty.TEXT_COLOR, StyleValue.TokenRef(tokenId)),
        )
        val before = StyleResolver.resolveColor(styled, body(), StyleProperty.TEXT_COLOR)
        assertNotNull(before)

        val token = styled.token(tokenId)!!
        val edited = styled.withToken(token.copy(value = "#FF0000"))
        assertEquals(ColorValue(255, 0, 0), StyleResolver.resolveColor(edited, body(), StyleProperty.TEXT_COLOR))
        assertEquals(
            "the consuming style itself is untouched — it still holds a reference",
            styled.style(Stylesheet.BODY)!!.get(StyleProperty.TEXT_COLOR),
            edited.style(Stylesheet.BODY)!!.get(StyleProperty.TEXT_COLOR),
        )
    }

    /** Renaming a token keeps every binding, because bindings are ids. */
    @Test
    fun `renaming a token keeps its bindings`() {
        val tokenId = Stylesheet.TOKEN_ACCENT
        val styled = sheet.withStyle(
            sheet.style(Stylesheet.BODY)!!.with(StyleProperty.TEXT_COLOR, StyleValue.TokenRef(tokenId)),
        )
        val renamed = styled.renameToken(tokenId, "Brand")
        assertEquals("Brand", renamed.token(tokenId)!!.name)
        assertNotNull(StyleResolver.resolveColor(renamed, body(), StyleProperty.TEXT_COLOR))
        assertEquals("Brand", StyleResolver.describe(renamed, body(), StyleProperty.TEXT_COLOR).tokenName)
    }

    /**
     * Deleting a token detaches its consumers to the literal value — nothing silently loses its colour.
     */
    @Test
    fun `detaching on delete preserves the appearance`() {
        val tokenId = Stylesheet.TOKEN_ACCENT
        val styled = sheet.withStyle(
            sheet.style(Stylesheet.BODY)!!.with(StyleProperty.TEXT_COLOR, StyleValue.TokenRef(tokenId)),
        )
        val literal = styled.token(tokenId)!!.value
        val detached = styled.detachAndRemoveToken(tokenId)
        assertNull("the token is gone", detached.token(tokenId))
        assertEquals(
            "but the colour it provided is preserved as a literal",
            literal,
            StyleResolver.resolve(detached, body(), StyleProperty.TEXT_COLOR),
        )
        assertTrue(
            "and nothing still references the dead token",
            detached.stylesReferencing(tokenId).isEmpty(),
        )
    }

    @Test
    fun `replacing on delete repoints consumers at the replacement`() {
        val doomed = Stylesheet.TOKEN_ACCENT
        val survivor = Stylesheet.TOKEN_TEXT_PRIMARY
        val styled = sheet.withStyle(
            sheet.style(Stylesheet.BODY)!!.with(StyleProperty.TEXT_COLOR, StyleValue.TokenRef(doomed)),
        )
        val replaced = styled.replaceAndRemoveToken(doomed, survivor)
        assertNull(replaced.token(doomed))
        assertEquals(
            StyleValue.TokenRef(survivor),
            replaced.style(Stylesheet.BODY)!!.get(StyleProperty.TEXT_COLOR),
        )
        // The binding is live: editing the survivor still moves the consumer.
        val edited = replaced.withToken(replaced.token(survivor)!!.copy(value = "#00FF00"))
        assertEquals(ColorValue(0, 255, 0), StyleResolver.resolveColor(edited, body(), StyleProperty.TEXT_COLOR))
    }

    /** A dangling token reference resolves to nothing rather than to an invented default. */
    @Test
    fun `a dangling token reference resolves to null not to a guess`() {
        val styled = sheet.withStyle(
            sheet.style(Stylesheet.BODY)!!.with(StyleProperty.TEXT_COLOR, StyleValue.TokenRef("token.gone")),
        )
        assertNull(StyleResolver.resolve(styled, body(), StyleProperty.TEXT_COLOR))
        assertNull(StyleResolver.resolveColor(styled, body(), StyleProperty.TEXT_COLOR))
    }

    @Test
    fun `stylesReferencing finds every consumer of a token`() {
        val tokenId = Stylesheet.TOKEN_ACCENT
        val before = sheet.stylesReferencing(tokenId).size
        val styled = sheet
            .withStyle(sheet.style(Stylesheet.BODY)!!.with(StyleProperty.TEXT_COLOR, StyleValue.TokenRef(tokenId)))
            .let { s -> s.withStyle(s.style(Stylesheet.QUOTE)!!.with(StyleProperty.TEXT_COLOR, StyleValue.TokenRef(tokenId))) }
        assertEquals(
            "both new consumers must be found, on top of any built-in ones",
            before + 2,
            styled.stylesReferencing(tokenId).size,
        )
        assertTrue(styled.stylesReferencing("token.unused").isEmpty())
    }

    // ── Detachment and round-trip ────────────────────────────────────────────────────────────────

    /**
     * Detaching a target flattens the resolved values so the appearance survives losing the style.
     *
     * Note what detaching keeps: a token *reference* stays a reference. Detaching means "stop following
     * Body", not "stop following the document's colour variables" — those live in the same stylesheet and
     * are still there. So the comparison below drops the styles but keeps the tokens, which is the state
     * the content is actually left in.
     */
    @Test
    fun `detaching preserves every resolved property`() {
        val assignment = heading1()
        val resolved = StyleResolver.rawAll(sheet, assignment, StyleCategory.TEXT)
        val detached = assignment.detached(resolved)
        assertNull("a detached target has no style", detached.styleId)
        assertTrue("detaching must capture something", resolved.isNotEmpty())
        val withoutStyles = sheet.copy(styles = emptyList())
        for ((property, _) in resolved) {
            assertEquals(
                "$property was lost by detaching",
                StyleResolver.resolve(sheet, assignment, property),
                StyleResolver.resolve(withoutStyles, detached, property),
            )
        }
    }

    /** Unknown properties round-trip verbatim, so a newer document is not damaged by an older build. */
    @Test
    fun `unknown style properties survive a round trip`() {
        val definition = StyleDefinition(
            id = "style.future",
            name = "Future",
            category = StyleCategory.TEXT,
            unknownProperties = mapOf("text.shadow.blur" to "4", "vendor.thing" to "x"),
        )
        val decoded = json.decodeFromString<StyleDefinition>(json.encodeToString(definition))
        assertEquals(definition.unknownProperties, decoded.unknownProperties)
        assertEquals(definition, decoded)
    }

    @Test
    fun `stylesheet round trips through json`() {
        val decoded = json.decodeFromString<Stylesheet>(json.encodeToString(sheet))
        assertEquals(sheet, decoded)
        assertEquals(sheet.styles.map { it.id }, decoded.styles.map { it.id })
        assertEquals(sheet.tokens.map { it.id }, decoded.tokens.map { it.id })
    }

    @Test
    fun `style values are locale independent when formatted`() {
        val previous = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.forLanguageTag("de-DE"))
            val value = StyleValue.of(12.5f)
            assertEquals("12.5", (value as StyleValue.Literal).text)
            assertEquals(12.5f, value.text.toFloat(), 0.0001f)
        } finally {
            java.util.Locale.setDefault(previous)
        }
    }

    @Test
    fun `default stylesheet provides the documented built ins`() {
        val ids = sheet.styles.map { it.id }
        assertTrue(ids.contains(Stylesheet.BODY))
        assertTrue(ids.contains(Stylesheet.TITLE))
        assertTrue(ids.contains(Stylesheet.QUOTE))
        assertTrue(ids.contains(Stylesheet.CODE))
        for (level in 1..6) {
            assertTrue("heading $level must exist", ids.contains(Stylesheet.headingId(level)))
        }
        assertTrue("tokens must be seeded", sheet.tokens.isNotEmpty())
        assertTrue(sheet.tokensOf(TokenKind.COLOR).isNotEmpty())
    }

    /**
     * The semantic role a block reports must map to a real style.
     *
     * Roles come from `DocumentBlock.semanticStyleRole()` (`"heading:2"`, not `"heading2"`), which is why
     * this test spells them the way the blocks do — a mismatch here means every heading silently renders
     * unstyled while nothing throws.
     */
    @Test
    fun `semantic role maps to the right default style`() {
        assertEquals(Stylesheet.BODY, Stylesheet.defaultStyleIdFor("paragraph"))
        assertEquals(Stylesheet.QUOTE, Stylesheet.defaultStyleIdFor("quote"))
        assertEquals(Stylesheet.CODE, Stylesheet.defaultStyleIdFor("code"))
        for (level in 1..6) {
            assertEquals(
                "heading:$level must resolve to its own style",
                Stylesheet.headingId(level),
                Stylesheet.defaultStyleIdFor("heading:$level"),
            )
            assertNotNull(sheet.style(Stylesheet.defaultStyleIdFor("heading:$level")))
        }
        assertEquals("out-of-range levels clamp", Stylesheet.headingId(6), Stylesheet.defaultStyleIdFor("heading:9"))
        assertNull(Stylesheet.defaultStyleIdFor("unknown-thing"))
    }
}
