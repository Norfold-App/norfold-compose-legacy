package com.norfold.app.domain

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MarkdownCodecAndroidCompatibilityTest {
    @Test
    fun latexDetectionCompilesAndImportsOnAndroidIcu() {
        val document = MarkdownBlockCodec.import(
            """
            Inline ${'$'}x^2${'$'} and display math:

            ${'$'}${'$'}\frac{1}{3} + y_{2}${'$'}${'$'}
            """.trimIndent(),
        )

        assertTrue(document.blocks.isNotEmpty())
        assertTrue(MarkdownBlockCodec.export(document).contains("frac"))
    }
}
