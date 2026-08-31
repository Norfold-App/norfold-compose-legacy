package com.norfold.app.cloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class KlipyResponseParserTest {
    @Test
    fun `unexpected ad result fails the response closed`() {
        val response = """
            {
              "result": true,
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

    @Test
    fun `request builder encodes the query and sends privacy bounded parameters`() {
        val url = KlipyRequestBuilder.searchUrl(
            apiKey = "test+key",
            query = "cats & dogs",
            customerId = "8f6439d9-16b1-42b2-8f6d-4b0be3d51441",
            page = 2,
            perPage = 24,
            locale = "BD",
        )

        assertEquals(
            "https://api.klipy.com/api/v1/test%2Bkey/gifs/search" +
                "?page=2&per_page=24&customer_id=8f6439d9-16b1-42b2-8f6d-4b0be3d51441" +
                "&locale=bd&content_filter=high&q=cats+%26+dogs",
            url,
        )
        assertEquals(
            "{\"customer_id\":\"8f6439d9-16b1-42b2-8f6d-4b0be3d51441\",\"q\":\"cats & dogs\"}",
            KlipyRequestBuilder.shareBody(
                customerId = "8f6439d9-16b1-42b2-8f6d-4b0be3d51441",
                query = "cats & dogs",
            ),
        )
        assertEquals(
            "https://api.klipy.com/api/v1/test%2Bkey/gifs/share/happy-cat",
            KlipyRequestBuilder.shareUrl("test+key", "happy-cat"),
        )
    }

    @Test
    fun `trending omits query and locale when unavailable`() {
        val url = KlipyRequestBuilder.searchUrl(
            apiKey = "key",
            query = "",
            customerId = "anonymous_123",
            page = 1,
            perPage = 24,
            locale = null,
        )

        assertEquals(
            "https://api.klipy.com/api/v1/key/gifs/trending" +
                "?page=1&per_page=24&customer_id=anonymous_123&content_filter=high",
            url,
        )
    }

    @Test
    fun `request builder enforces documented result bounds`() {
        assertThrows(IllegalArgumentException::class.java) {
            KlipyRequestBuilder.searchUrl(
                apiKey = "key",
                query = "",
                customerId = "anonymous_123",
                page = 1,
                perPage = 7,
                locale = null,
            )
        }
    }

    @Test
    fun `parser preserves provider order and exact urls`() {
        val response = """
            {
              "result": true,
              "data": {
                "data": [
                  {
                    "type": "gif",
                    "slug": "first",
                    "title": "First",
                    "file": {
                      "sm": { "gif": { "url": "https://static.klipy.com/first-sm.gif", "width": 120, "height": 90 } },
                      "md": { "gif": { "url": "https://static.klipy.com/first-md.gif?token=one", "width": 480, "height": 360 } }
                    }
                  },
                  {
                    "type": "gif",
                    "slug": "second",
                    "title": "Second",
                    "file": {
                      "sm": { "webp": { "url": "https://static1.klipy.com/second-sm.webp", "width": 100, "height": 100 } },
                      "hd": { "gif": { "url": "https://static2.klipy.com/second-hd.gif", "width": 800, "height": 800 } }
                    }
                  }
                ]
              }
            }
        """.trimIndent()

        val results = KlipyResponseParser.parse(response)

        assertEquals(listOf("first", "second"), results.map(KlipyGif::slug))
        assertEquals("https://static.klipy.com/first-md.gif?token=one", results[0].originalUrl)
        assertEquals("https://static2.klipy.com/second-hd.gif", results[1].originalUrl)
    }

    @Test
    fun `failed provider actions and malformed content fail closed`() {
        assertThrows(IllegalStateException::class.java) {
            KlipyResponseParser.requireActionSuccess("{\"result\":false}")
        }
        assertThrows(IllegalStateException::class.java) {
            KlipyResponseParser.parse(
                """
                    {
                      "result": true,
                      "data": {
                        "data": [
                          {
                            "type": "gif",
                            "slug": "outside",
                            "file": {
                              "sm": { "gif": { "url": "https://evil.example/preview.gif", "width": 1, "height": 1 } },
                              "md": { "gif": { "url": "https://static.klipy.com/original.gif", "width": 1, "height": 1 } }
                            }
                          }
                        ]
                      }
                    }
                """.trimIndent(),
            )
        }
    }

    @Test
    fun `chat integration keeps provider media direct attributed and outside upload paths`() {
        val client = repositoryFile(
            "apps/android/src/main/java/com/norfold/app/cloud/KlipyClient.kt",
        ).readText()
        val viewModel = repositoryFile(
            "apps/android/src/main/java/com/norfold/app/ui/NotesViewModel.kt",
        ).readText()
        val screens = repositoryFile(
            "apps/android/src/main/java/com/norfold/app/ui/screens/WorkspaceScreens.kt",
        ).readText()
        val sendGif = viewModel.substring(
            viewModel.indexOf("fun sendChatGif(gif: KlipyGif)"),
            viewModel.indexOf("private suspend fun klipyCustomerId()"),
        )

        assertTrue(client.contains("context.noBackupFilesDir"))
        assertTrue(client.contains("&content_filter=high"))
        assertTrue(sendGif.contains("KlipyClient.registerShare("))
        assertTrue(sendGif.contains("operation = \"provider_media\""))
        assertFalse(sendGif.contains("enqueueChatUpload("))
        assertFalse(sendGif.contains("ChatMediaBackend"))
        assertTrue(screens.contains("placeholder = { Text(\"Search KLIPY\") }"))
        assertTrue(screens.contains("Powered by KLIPY"))
        assertTrue(screens.split("diskCachePolicy(CachePolicy.DISABLED)").size - 1 >= 3)
    }

    private fun repositoryFile(path: String): File {
        val candidates = listOf(File(path), File("../../$path"))
        return candidates.firstOrNull(File::isFile) ?: error("Repository file not found: $path")
    }
}
