package com.norfold.app.ui.editor.style

import androidx.compose.ui.graphics.Color
import com.norfold.app.domain.CalloutBlock
import com.norfold.app.domain.CodeBlock
import com.norfold.app.domain.InlineText
import com.norfold.app.domain.ParagraphBlock
import com.norfold.app.domain.QuoteBlock
import com.norfold.app.domain.effectiveStyleAssignment
import com.norfold.app.domain.style.ColorValue
import com.norfold.app.domain.style.DocumentToken
import com.norfold.app.domain.style.FontIdentity
import com.norfold.app.domain.style.StyleAssignment
import com.norfold.app.domain.style.StyleProperty
import com.norfold.app.domain.style.StyleValue
import com.norfold.app.domain.style.Stylesheet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ResolvedRenderingClosureTest {
    private val fallback = Color(0xFF223344)
    private val resolved = Color(0xFFAA3377)

    @Test fun `Flow resolved text color remains in read mode`() {
        assertEquals(resolved, resolvedContentColor(resolved, fallback))
    }

    @Test fun `Flow resolved text color remains in edit mode`() {
        assertEquals(resolved, resolvedContentColor(resolved, fallback))
    }

    @Test fun `Document resolved text color remains in read mode`() {
        assertEquals(resolved, resolvedContentColor(resolved, fallback))
    }

    @Test fun `Document resolved text color remains in edit mode`() {
        assertEquals(resolved, resolvedContentColor(resolved, fallback))
    }

    @Test fun `unspecified text color alone falls back to host content color`() {
        assertEquals(fallback, resolvedContentColor(Color.Unspecified, fallback))
    }

    @Test fun `named style text color resolves for both hosts`() {
        val paragraph = ParagraphBlock(style = StyleAssignment(styleId = Stylesheet.SUBTITLE))
        assertEquals(
            Stylesheet.defaults().token(Stylesheet.TOKEN_TEXT_SECONDARY)!!.value,
            resolvedTextValues(paragraph, Stylesheet.defaults())[StyleProperty.TEXT_COLOR],
        )
    }

    @Test fun `text token resolves dynamically for both hosts`() {
        val token = DocumentToken.color("Brand", ColorValue(10, 20, 30), id = "token.brand")
        val paragraph = ParagraphBlock(
            style = StyleAssignment().override(StyleProperty.TEXT_COLOR, StyleValue.token(token.id)),
        )
        val first = Stylesheet.defaults().withToken(token)
        val second = first.withToken(token.copy(value = ColorValue(40, 50, 60).toHex()))
        assertEquals("#0A141E", resolvedTextValues(paragraph, first)[StyleProperty.TEXT_COLOR])
        assertEquals("#28323C", resolvedTextValues(paragraph, second)[StyleProperty.TEXT_COLOR])
        assertEquals(paragraph, paragraph)
    }

    @Test fun `Quote wrapper style reaches an unstyled child`() {
        val quote = QuoteBlock(style = StyleAssignment(styleId = Stylesheet.QUOTE))
        val child = ParagraphBlock(content = listOf(InlineText("Quoted")))
        val wrapper = WrapperStyleSource(quote.id, quote.effectiveStyleAssignment())
        val values = resolvedTextValues(child, Stylesheet.defaults(), listOf(wrapper))
        assertEquals("true", values[StyleProperty.ITALIC])
        assertEquals(
            Stylesheet.defaults().token(Stylesheet.TOKEN_TEXT_SECONDARY)!!.value,
            values[StyleProperty.TEXT_COLOR],
        )
    }

    @Test fun `child local override wins over Quote wrapper`() {
        val local = ColorValue(200, 30, 40)
        val quote = QuoteBlock(style = StyleAssignment(styleId = Stylesheet.QUOTE))
        val child = ParagraphBlock(
            style = StyleAssignment().override(StyleProperty.TEXT_COLOR, StyleValue.of(local)),
        )
        val values = resolvedTextValues(
            child,
            Stylesheet.defaults(),
            listOf(WrapperStyleSource(quote.id, quote.effectiveStyleAssignment())),
        )
        assertEquals(local.toHex(), values[StyleProperty.TEXT_COLOR])
    }

    @Test fun `child explicit style wins over wrapper style`() {
        val quote = QuoteBlock(style = StyleAssignment(styleId = Stylesheet.QUOTE))
        val child = ParagraphBlock(style = StyleAssignment(styleId = Stylesheet.TITLE))
        val values = resolvedTextValues(
            child,
            Stylesheet.defaults(),
            listOf(WrapperStyleSource(quote.id, quote.effectiveStyleAssignment())),
        )
        assertEquals("32", values[StyleProperty.FONT_SIZE])
    }

    @Test fun `Callout wrapper style reaches title and child content`() {
        val color = ColorValue(4, 80, 160)
        val callout = CalloutBlock(
            style = StyleAssignment().override(StyleProperty.TEXT_COLOR, StyleValue.of(color)),
        )
        val child = ParagraphBlock()
        val wrapper = WrapperStyleSource(callout.id, callout.effectiveStyleAssignment())
        assertEquals(color.toHex(), resolvedTextValues(callout, Stylesheet.defaults())[StyleProperty.TEXT_COLOR])
        assertEquals(color.toHex(), resolvedTextValues(child, Stylesheet.defaults(), listOf(wrapper))[StyleProperty.TEXT_COLOR])
    }

    @Test fun `Color Studio preview on wrapper reaches descendants in both hosts`() {
        val quote = QuoteBlock()
        val child = ParagraphBlock()
        val wrappers = listOf(WrapperStyleSource(quote.id, quote.effectiveStyleAssignment()))
        assertTrue(previewAffectsTextBlock(child.id, wrappers, setOf(quote.id)))
        assertFalse(previewAffectsTextBlock(child.id, wrappers, setOf("other")))
    }

    @Test fun `Document CodeBlock resolves shared bundled font and color`() {
        val code = CodeBlock(style = StyleAssignment(styleId = Stylesheet.CODE), code = "val answer = 42")
        val values = resolvedTextValues(code, Stylesheet.defaults())
        assertEquals(
            FontIdentity.builtIn("jetbrains-mono", "JetBrains Mono").encode(),
            values[StyleProperty.FONT_FAMILY],
        )
        assertEquals(
            Stylesheet.defaults().token(Stylesheet.TOKEN_TEXT_PRIMARY)!!.value,
            values[StyleProperty.TEXT_COLOR],
        )
    }
}
