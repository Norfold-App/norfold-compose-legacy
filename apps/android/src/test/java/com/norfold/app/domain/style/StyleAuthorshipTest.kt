package com.norfold.app.domain.style

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Authorship: "did anyone *choose* this value", as distinct from "what is the value".
 *
 * The built-in stylesheet is a **print** stylesheet — an 11 pt body on a modular scale — because a page has
 * to match paper. A phone screen has no paper to match, so the screen editor supplies its own scale from the
 * reader's preference (`com.norfold.app.editor.style.ReadingScale`). Those two only coexist because the
 * renderer can tell a shipped default apart from a decision: a default yields, a decision wins.
 *
 * Every case below is a way that distinction can be got wrong, and each one is silent in a screenshot. A
 * naive `!definition.builtIn` test passes the first test here and fails the next three: built-ins are
 * editable, and Body's size is a *token reference*, so the edit that re-bases the print model does not touch
 * the definition holding the reference at all.
 */
class StyleAuthorshipTest {

    private val sheet = Stylesheet.defaults()

    private fun body() = StyleAssignment(styleId = Stylesheet.BODY)
    private fun heading1() = StyleAssignment(styleId = Stylesheet.headingId(1))

    private fun authorship(
        sheet: Stylesheet,
        child: StyleAssignment,
        wrappers: List<StyleAssignment> = emptyList(),
        property: StyleProperty = StyleProperty.FONT_SIZE,
    ) = StyleResolver.authorshipCascaded(sheet, child, wrappers, property)

    private fun literal(
        sheet: Stylesheet,
        child: StyleAssignment,
        wrappers: List<StyleAssignment> = emptyList(),
        property: StyleProperty = StyleProperty.FONT_SIZE,
    ) = StyleResolver.authoredLiteralCascaded(sheet, child, wrappers, property)

    /**
     * The shipped Body size is not a choice, so the screen renderer keeps its own.
     *
     * This is the test the whole feature turns on: `resolve` returns 11 here, and if the renderer read that
     * the reading-size preference would do nothing at all for paragraphs while appearing to be wired.
     */
    @Test
    fun `a shipped built-in size is not authorship`() {
        assertEquals("the value is still there", "11", StyleResolver.resolve(sheet, body(), StyleProperty.FONT_SIZE))
        assertEquals(StyleResolver.Authorship.Shipped, authorship(sheet, body()))
        assertNull("so the renderer keeps its own base size", literal(sheet, body()))
    }

    /** Every built-in heading is shipped too — the print hierarchy must not out-vote the screen one. */
    @Test
    fun `shipped headings are not authorship either`() {
        for (level in 1..6) {
            val assignment = StyleAssignment(styleId = Stylesheet.headingId(level))
            assertEquals(
                "heading $level asserts a shipped print size",
                StyleResolver.Authorship.Shipped,
                authorship(sheet, assignment),
            )
            assertNull(literal(sheet, assignment))
        }
    }

    /** A size the user typed on this block wins, exactly as it did before authorship existed. */
    @Test
    fun `a local override is authorship`() {
        val overridden = body().override(StyleProperty.FONT_SIZE, StyleValue.Literal("42"))
        assertEquals(StyleResolver.Authorship.Authored("42"), authorship(sheet, overridden))
        assertEquals("42", literal(sheet, overridden))
    }

    /** A style the user wrote asserts nothing by accident, so its values are authorship by construction. */
    @Test
    fun `a user defined style is authorship`() {
        val mine = StyleDefinition(
            id = "style.mine",
            name = "Mine",
            category = StyleCategory.TEXT,
            basedOn = Stylesheet.BODY,
            properties = mapOf(StyleProperty.FONT_SIZE.key to StyleValue.Literal("19")),
        )
        val withMine = sheet.withStyle(mine)
        assertEquals(
            StyleResolver.Authorship.Authored("19"),
            authorship(withMine, StyleAssignment(styleId = "style.mine")),
        )
    }

    /**
     * Editing a built-in is authorship. `StyleDefinition.builtIn` says built-ins "may be edited but not
     * deleted", so `builtIn` alone cannot answer the question — the value has to be diffed against what this
     * build actually ships.
     */
    @Test
    fun `an edited built-in is authorship`() {
        val edited = sheet.withStyle(
            sheet.style(Stylesheet.headingId(1))!!.with(StyleProperty.FONT_SIZE, StyleValue.Literal("31")),
        )
        assertTrue("the definition is still flagged built-in", edited.style(Stylesheet.headingId(1))!!.builtIn)
        assertEquals(StyleResolver.Authorship.Authored("31"), authorship(edited, heading1()))
        assertEquals("31", literal(edited, heading1()))
        assertEquals(
            "and its untouched siblings are unaffected",
            StyleResolver.Authorship.Shipped,
            authorship(edited, StyleAssignment(styleId = Stylesheet.headingId(2))),
        )
    }

    /**
     * Editing the token a built-in points at is authorship, and it reports the *token's* new value.
     *
     * Body's size is `token.type.base`, so re-basing the print scale leaves every definition byte-identical.
     * A diff of definitions alone therefore reports "shipped" for a document the user has deliberately
     * re-based, and the reader's screen preference would silently discard the whole edit.
     */
    @Test
    fun `an edited token behind a built-in is authorship`() {
        val reference = sheet.style(Stylesheet.BODY)!!.get(StyleProperty.FONT_SIZE)
        assertEquals(
            "this test is only meaningful while Body's size is a token reference",
            StyleValue.TokenRef(Stylesheet.TOKEN_TYPE_BASE),
            reference,
        )
        val rebased = sheet.withToken(sheet.token(Stylesheet.TOKEN_TYPE_BASE)!!.copy(value = "13"))
        assertEquals(StyleResolver.Authorship.Authored("13"), authorship(rebased, body()))
        assertEquals("13", literal(rebased, body()))
        assertEquals(
            "and an untouched token stays shipped",
            StyleResolver.Authorship.Shipped,
            authorship(sheet, body()),
        )
    }

    /**
     * The nearest assertion decides, and it decides *both* ways.
     *
     * A wrapper's authored size must not leak into a block whose own style already spoke, or a table with a
     * size on it would resize the headings inside it while the same headings outside stayed put. So a shipped
     * assertion stops the walk rather than being skipped over.
     */
    @Test
    fun `a shipped assertion stops an outer authored value from leaking in`() {
        val wrapper = StyleAssignment(styleId = Stylesheet.BODY)
            .override(StyleProperty.FONT_SIZE, StyleValue.Literal("30"))
        assertEquals(
            "the block's own style asserted a size, so the wrapper does not reach it",
            StyleResolver.Authorship.Shipped,
            authorship(sheet, heading1(), listOf(wrapper)),
        )
        assertNull(literal(sheet, heading1(), listOf(wrapper)))
    }

    /** With nothing in the way, a wrapper's authored value does reach the block. */
    @Test
    fun `an authored wrapper value reaches a block that asserts nothing`() {
        val wrapper = StyleAssignment.NONE
            .override(StyleProperty.LETTER_SPACING, StyleValue.Literal("0.4"))
        assertEquals(
            StyleResolver.Authorship.Authored("0.4"),
            authorship(sheet, StyleAssignment.NONE, listOf(wrapper), StyleProperty.LETTER_SPACING),
        )
    }

    /** Nothing asserted anywhere is `Absent`, which is a third answer and not a kind of zero. */
    @Test
    fun `an unasserted property is absent`() {
        assertEquals(
            StyleResolver.Authorship.Absent,
            authorship(sheet, StyleAssignment.NONE, property = StyleProperty.LETTER_SPACING),
        )
        assertNull(literal(sheet, StyleAssignment.NONE, property = StyleProperty.LETTER_SPACING))
    }

    /**
     * A built-in id this build does not ship is honoured rather than dropped.
     *
     * That is a newer version's default arriving through a round-trip. We cannot prove it is a default, and
     * discarding a value we cannot classify is worse than honouring one we cannot vouch for.
     */
    @Test
    fun `an unknown built-in id is treated as authorship`() {
        val future = StyleDefinition(
            id = "style.future.callout",
            name = "Future",
            category = StyleCategory.TEXT,
            builtIn = true,
            properties = mapOf(StyleProperty.FONT_SIZE.key to StyleValue.Literal("23")),
        )
        val withFuture = sheet.withStyle(future)
        assertEquals(
            StyleResolver.Authorship.Authored("23"),
            authorship(withFuture, StyleAssignment(styleId = "style.future.callout")),
        )
    }

    /** Authorship walks the `basedOn` chain, so an edited base is authorship for its children. */
    @Test
    fun `an edited base is authorship for a derived style that inherits it`() {
        val edited = sheet.withStyle(
            sheet.style(Stylesheet.BODY)!!.with(StyleProperty.FONT_FAMILY, StyleValue.Literal("builtin:lora")),
        )
        assertEquals(
            "Heading 1 does not assert a family, so it inherits Body's edited one",
            StyleResolver.Authorship.Authored("builtin:lora"),
            authorship(edited, heading1(), property = StyleProperty.FONT_FAMILY),
        )
        assertEquals(
            "while the shipped family stays shipped",
            StyleResolver.Authorship.Shipped,
            authorship(sheet, heading1(), property = StyleProperty.FONT_FAMILY),
        )
    }
}
