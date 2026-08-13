package com.norfold.app.ui.editor.flow

import org.junit.Assert.assertTrue
import org.junit.Test

class HtmlSandboxPolicyTest {
    @Test
    fun `sandbox document preserves source and denies ambient capabilities`() {
        val source = "<article data-value=\"exact\">Hello</article>"

        val document = htmlSandboxDocument(source)

        assertTrue(document.contains("default-src 'none'"))
        assertTrue(document.contains("img-src data:"))
        assertTrue(document.endsWith(source))
    }
}
