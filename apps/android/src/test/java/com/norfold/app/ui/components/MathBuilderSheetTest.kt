package com.norfold.app.ui.components

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MathBuilderSheetTest {
    @Test
    fun `fraction template selects its numerator then advances to denominator`() {
        val blank = TextFieldValue(MathSlot, TextRange(0, MathSlot.length))
        val fraction = applyMathTemplate(
            blank,
            "\\frac{{{norfold-selection}}}{$MathSlot}",
        )

        assertEquals("\\frac{$MathSlot}{$MathSlot}", fraction.text)
        assertEquals(MathSlot, fraction.selectedText())

        val numerator = replaceMathActiveSelection(fraction, "x + 1")
        val denominator = selectMathSlot(numerator, forward = true)

        assertEquals("x + 1", activeMathSelectionText(numerator))
        assertEquals(MathSlot, denominator.selectedText())
        assertEquals(1, pendingMathSlotCount(numerator.text))
    }

    @Test
    fun `power template wraps selected visual value and selects exponent`() {
        val selectedBase = TextFieldValue("x", TextRange(0, 1))

        val result = applyMathTemplate(
            selectedBase,
            "{{{norfold-selection}}}^{$MathSlot}",
        )

        assertEquals("{x}^{$MathSlot}", result.text)
        assertEquals(MathSlot, result.selectedText())
    }

    @Test
    fun `atomic palette token fills a slot and advances to next slot`() {
        val expression = "\\frac{$MathSlot}{$MathSlot}"
        val firstSlot = expression.indexOf(MathSlot)
        val value = TextFieldValue(
            expression,
            TextRange(firstSlot, firstSlot + MathSlot.length),
        )

        val result = insertMathToken(value, "\\alpha ")

        assertEquals("\\frac{\\alpha }{$MathSlot}", result.text)
        assertEquals(MathSlot, result.selectedText())
    }

    @Test
    fun `visual preview replaces private slots and preserves valid square command`() {
        val markdown = mathPreviewMarkdown(
            "$MathSlot + \\square",
            MathInsertionKind.Block,
        )

        assertFalse(markdown.contains(MathSlot))
        assertTrue(markdown.contains("\\boxed{\\vphantom{0}\\phantom{0}}"))
        assertTrue(markdown.contains("\\square"))
        assertTrue(markdown.startsWith("$$\n"))
    }

    @Test
    fun `inline preview uses inline math delimiters`() {
        val markdown = mathPreviewMarkdown("E = mc^2", MathInsertionKind.Inline)

        assertEquals("Inline preview: \\(E = mc^2\\)", markdown)
    }

    @Test
    fun `selection helpers defensively clamp invalid raw source ranges`() {
        val invalidSelection = TextFieldValue("x", TextRange(50, 80))

        val appended = insertMathToken(invalidSelection, "+1")

        assertEquals("x+1", appended.text)
        assertEquals(TextRange(3), appended.selection)
    }

    @Test
    fun `math runtime detection includes simple inline expressions without commands`() {
        val dollar = '$'

        assertTrue(markdownNeedsMath("Energy is ${dollar}E = mc^2$dollar."))
        assertTrue(markdownNeedsMath("Display: ${dollar}${dollar}x^2$dollar$dollar"))
        assertTrue(markdownNeedsMath("Bracketed: \\(x + y\\)"))
    }

    @Test
    fun `math runtime detection ignores escaped prices and incomplete delimiters`() {
        val dollar = '$'

        assertFalse(markdownNeedsMath("Price is \\$dollar${5}, not \\$dollar${7}."))
        assertFalse(markdownNeedsMath("Currency ranges: ${dollar}5 and ${dollar}10"))
        assertFalse(markdownNeedsMath("An unfinished ${dollar}expression"))
        assertFalse(markdownNeedsMath("Inline math cannot cross ${dollar}a line\nb$dollar"))
    }

    private fun TextFieldValue.selectedText(): String {
        val start = minOf(selection.start, selection.end)
        val end = maxOf(selection.start, selection.end)
        return text.substring(start, end)
    }
}
