package com.norfold.app.ui.components

private val ExplicitMathSignal = Regex(
    """(?:\$\$|\\\[|\\\(|\\(?:frac|sum|int|begin|mathbb|vec|tag|newcommand)\b)""",
)

/**
 * Decides whether the preview needs to load the bundled MathJax runtime.
 *
 * Inline dollar math needs a small scanner instead of a broad `contains('$')`: escaped currency
 * should not pay the startup cost, and an inline expression may not contain any LaTeX command.
 */
internal fun markdownNeedsMath(markdown: String): Boolean {
    if (ExplicitMathSignal.containsMatchIn(markdown)) return true

    var searchFrom = 0
    while (searchFrom < markdown.length) {
        val opening = markdown.indexOf('$', startIndex = searchFrom)
        if (opening < 0) return false
        val firstContent = markdown.getOrNull(opening + 1)
        if (
            !markdown.isEscaped(opening) &&
            firstContent != null &&
            firstContent != '$' &&
            !firstContent.isWhitespace()
        ) {
            var closing = markdown.indexOf('$', startIndex = opening + 1)
            while (closing >= 0 && !markdown.hasLineBreak(opening + 1, closing)) {
                if (!markdown.isEscaped(closing) && !markdown[closing - 1].isWhitespace()) return true
                closing = markdown.indexOf('$', startIndex = closing + 1)
            }
        }
        searchFrom = opening + 1
    }
    return false
}

private fun String.isEscaped(index: Int): Boolean {
    var slashCount = 0
    var cursor = index - 1
    while (cursor >= 0 && this[cursor] == '\\') {
        slashCount += 1
        cursor -= 1
    }
    return slashCount % 2 == 1
}

private fun String.hasLineBreak(start: Int, endExclusive: Int): Boolean {
    for (index in start until endExclusive) {
        if (this[index] == '\n' || this[index] == '\r') return true
    }
    return false
}
