package com.norfold.app.editor.flow

import com.norfold.app.domain.BlockDocument
import com.norfold.app.domain.BoldInline
import com.norfold.app.domain.CalloutBlock
import com.norfold.app.domain.CodeBlock
import com.norfold.app.domain.CodeInline
import com.norfold.app.domain.ContainerBlock
import com.norfold.app.domain.DocumentBlock
import com.norfold.app.domain.InlineNode
import com.norfold.app.domain.InlineText
import com.norfold.app.domain.ItalicInline
import com.norfold.app.domain.LinkInline
import com.norfold.app.domain.MathInline
import com.norfold.app.domain.ParagraphBlock
import com.norfold.app.domain.QuoteBlock
import com.norfold.app.editor.history.EditTarget
import com.norfold.app.editor.presentation.FormFactor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The slash regression matrix (cases A–O of the Phase-1 nested-context closure).
 *
 * Two defects are pinned here, and both are the same mistake in different places: asking a question of the
 * wrong node.
 *
 *  - **Block context.** Quotes, callouts and containers render their children recursively while reusing the
 *    *outer* row's callbacks, so classifying the block from the row's own type described a container when the
 *    caret was in a code block inside it — and the menu opened on a slash the user was typing into code.
 *    Authority now lives in [FlowHostRuntime.slashInputFor], which resolves the addressed id against the
 *    document, so the surface has no classification to get wrong.
 *  - **Inline literals.** The schema permits wrappers around literal spans, so ``**`a/b`**`` is
 *    `BoldInline(CodeInline)`. A one-level check saw only the bold and called it prose.
 */
class FlowSlashContextTest {

    private fun runtime(vararg blocks: DocumentBlock): FlowHostRuntime = FlowHostRuntime(
        controller = FlowEditController(EditTarget("note:slash"), BlockDocument(blocks.toList())),
        initialFormFactor = FormFactor(400, 900),
    )

    private fun para(id: String, text: String = "") =
        ParagraphBlock(id = id, content = if (text.isEmpty()) emptyList() else listOf(InlineText(text)))

    /** Types `/foo` at the end of [blockId]'s text and reports whether the resolver opened. */
    private fun FlowHostRuntime.slashOpensIn(blockId: String, text: String = "/foo"): Boolean {
        val input = slashInputFor(blockId = blockId, text = text, caret = text.length, itemId = null)
        return slashResolver.resolve(input) != null
    }

    // ── A–E: block context comes from the addressed block, at any nesting depth ─────────────────────────

    @Test
    fun caseA_paragraphAllowsTheResolver() {
        assertTrue(runtime(para("p0")).slashOpensIn("p0"))
    }

    @Test
    fun caseB_topLevelCodeBlockSuppresses() {
        assertFalse(runtime(CodeBlock(id = "c0", code = "")).slashOpensIn("c0"))
    }

    @Test
    fun caseC_containerNestedCodeBlockSuppresses() {
        val nested = runtime(ContainerBlock(id = "ct0", children = listOf(CodeBlock(id = "c0", code = ""))))
        assertEquals(
            "the addressed block is the code block, not its container",
            FlowSlashContext.CODE,
            nested.slashInputFor("c0", "/foo", 4, null).context,
        )
        assertFalse(nested.slashOpensIn("c0"))
    }

    @Test
    fun caseD_calloutNestedCodeBlockSuppresses() {
        val nested = runtime(CalloutBlock(id = "cl0", children = listOf(CodeBlock(id = "c0", code = ""))))
        assertFalse(nested.slashOpensIn("c0"))
    }

    @Test
    fun caseE_quoteNestedCodeBlockSuppresses() {
        val nested = runtime(QuoteBlock(id = "q0", children = listOf(CodeBlock(id = "c0", code = ""))))
        assertFalse(nested.slashOpensIn("c0"))
    }

    // ── F–H: nested prose still works. Suppressing all nested content would be the wrong fix ────────────

    @Test
    fun caseF_containerNestedParagraphAllows() {
        val nested = runtime(ContainerBlock(id = "ct0", children = listOf(para("p0"))))
        assertTrue(nested.slashOpensIn("p0"))
    }

    @Test
    fun caseG_calloutNestedParagraphAllows() {
        val nested = runtime(CalloutBlock(id = "cl0", children = listOf(para("p0"))))
        assertTrue(nested.slashOpensIn("p0"))
    }

    @Test
    fun caseH_quoteNestedParagraphAllows() {
        val nested = runtime(QuoteBlock(id = "q0", children = listOf(para("p0"))))
        assertTrue(nested.slashOpensIn("p0"))
    }

    @Test
    fun unknownBlockIdFailsClosed() {
        // Inventing PROSE for a block that no longer exists would open a menu against nothing.
        val input = runtime(para("p0")).slashInputFor("gone", "/foo", 4, null)
        assertEquals(FlowSlashContext.CODE, input.context)
        assertFalse(runtime(para("p0")).slashOpensIn("gone"))
    }

    // ── I–M: literal inline detection follows the inline tree ───────────────────────────────────────────

    private fun literal(nodes: List<InlineNode>, offset: Int): Boolean =
        FlowInlineText.literalInlineAt(ParagraphBlock(id = "p0", content = nodes), itemId = null, offset = offset)

    @Test
    fun caseI_bareCodeInlineSuppresses() {
        assertTrue(literal(listOf(CodeInline("a/b")), offset = 2))
    }

    @Test
    fun caseJ_boldWrappedCodeInlineSuppresses() {
        assertTrue(literal(listOf(BoldInline(listOf(CodeInline("a/b")))), offset = 2))
    }

    @Test
    fun caseK_linkItalicWrappedCodeInlineSuppresses() {
        val nodes = listOf(LinkInline("https://x", listOf(ItalicInline(listOf(CodeInline("a/b"))))))
        assertTrue("three wrapper levels deep is still literal", literal(nodes, offset = 2))
    }

    @Test
    fun caseL_boldWrappedMathInlineSuppresses() {
        assertTrue(literal(listOf(BoldInline(listOf(MathInline("a/b")))), offset = 2))
    }

    @Test
    fun caseM_boldWrappedPlainTextRemainsProse() {
        assertFalse("bold prose is prose — a slash there must still open the menu", literal(listOf(BoldInline(listOf(InlineText("a/b")))), offset = 2))
    }

    /** The slash resolver must actually act on the recursive fact, not just compute it. */
    @Test
    fun wrappedCodeInlineSuppressesTheResolverEndToEnd() {
        val block = ParagraphBlock(id = "p0", content = listOf(BoldInline(listOf(CodeInline(" /h")))))
        val rt = runtime(block)
        val input = rt.slashInputFor("p0", " /h", caret = 3, itemId = null)
        assertTrue(input.literalInline)
        assertNull("a slash inside bold-wrapped code is content", rt.slashResolver.resolve(input))
    }

    // ── Boundary correctness around a literal span ──────────────────────────────────────────────────────

    @Test
    fun literalBoundaries_areExactAtEveryProbe() {
        // Flattened text: "ab" + "c/d" + "ef"  →  offsets 0..6, literal payload occupies 2..4.
        val nodes = listOf(InlineText("ab"), BoldInline(listOf(CodeInline("c/d"))), InlineText("ef"))

        assertFalse("caret 0 has no preceding character", literal(nodes, 0))
        assertFalse("prose immediately before the span", literal(nodes, 1))
        assertFalse("caret 2 still probes the last prose character", literal(nodes, 2))
        assertTrue("first character inside the literal", literal(nodes, 3))
        assertTrue("middle of the literal", literal(nodes, 4))
        assertTrue("caret just past the literal still joins it", literal(nodes, 5))
        assertFalse("prose after the literal", literal(nodes, 6))
        assertFalse("end of the text, in prose", literal(nodes, 7))
    }

    // ── N–O: the existing suppression rules stay green ──────────────────────────────────────────────────

    @Test
    fun caseN_urlPathDateAndFractionRemainSuppressed() {
        val rt = runtime(para("p0"))
        assertFalse("a URL", rt.slashOpensIn("p0", "https://x"))
        assertFalse("a path", rt.slashOpensIn("p0", "path/to"))
        assertFalse("a date", rt.slashOpensIn("p0", "12/07"))
        assertFalse("a fraction", rt.slashOpensIn("p0", "3/4"))
    }

    @Test
    fun caseO_doubledSlashRemainsSuppressed() {
        val rt = runtime(para("p0"))
        assertFalse(rt.slashOpensIn("p0", "//foo"))
        assertFalse(
            "a command trigger belongs at the start of a block, not after prose",
            rt.slashInputFor("p0", "x /f", 4, null).let { rt.slashResolver.resolve(it) != null },
        )
    }
}
