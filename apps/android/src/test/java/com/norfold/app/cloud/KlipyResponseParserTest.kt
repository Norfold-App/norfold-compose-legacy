package com.norfold.app.cloud

import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class KlipyResponseParserTest {
    @Test
    fun `unexpected ad result fails the response closed`() {
        val response = """
            {
              "data": {
                "data": [
                  { "type": "ad" }
                ]
              }
            }
        """.trimIndent()

        val failure = assertThrows(IllegalStateException::class.java) {
            KlipyResponseParser.parse(response)
        }

        assertTrue(failure.message.orEmpty().contains("unexpected ad"))
    }
}
