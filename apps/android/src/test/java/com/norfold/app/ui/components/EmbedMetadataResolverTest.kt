package com.norfold.app.ui.components

import java.net.URI
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EmbedMetadataResolverTest {
    @Test
    fun `metadata attributes are parsed independent of order and quoting`() {
        val metadata = EmbedMetadataResolver.parsePageMetadata(
            URI("https://example.com/articles/hello"),
            """
                <html>
                  <head>
                    <title>Fallback title</title>
                    <meta content="A useful page" name="description">
                    <meta content='Open Graph title' property='og:title'>
                    <link href=/assets/favicon.ico rel=icon type=image/x-icon>
                  </head>
                </html>
            """.trimIndent(),
        )

        assertEquals("Open Graph title", metadata.title)
        assertEquals("A useful page", metadata.description)
        assertEquals(URI("https://example.com/assets/favicon.ico"), metadata.iconCandidates.single())
    }

    @Test
    fun `raster icon is preferred over an svg candidate Coil cannot decode`() {
        val metadata = EmbedMetadataResolver.parsePageMetadata(
            URI("https://example.com/docs/index.html"),
            """
                <link rel="icon" type="image/svg+xml" href="/brand/icon.svg">
                <link sizes="180x180" href="//cdn.example.com/apple.png" rel="apple-touch-icon" type="image/png">
            """.trimIndent(),
        )

        assertEquals(URI("https://cdn.example.com/apple.png"), metadata.iconCandidates.first())
        assertEquals(URI("https://example.com/brand/icon.svg"), metadata.iconCandidates.last())
    }

    @Test
    fun `favicon query entities are decoded before resolving`() {
        val metadata = EmbedMetadataResolver.parsePageMetadata(
            URI("https://example.com/"),
            """<link rel="icon" href="/favicon.png?v=1&amp;theme=dark" type="image/png">""",
        )

        assertEquals(
            URI("https://example.com/favicon.png?v=1&theme=dark"),
            metadata.iconCandidates.single(),
        )
    }

    @Test
    fun `only Android-decodable image signatures are accepted`() {
        assertTrue(
            EmbedMetadataResolver.isSupportedIconBytes(
                byteArrayOf(
                    0x89.toByte(),
                    0x50,
                    0x4E,
                    0x47,
                    0x0D,
                    0x0A,
                    0x1A,
                    0x0A,
                ),
            ),
        )
        assertTrue(
            EmbedMetadataResolver.isSupportedIconBytes(
                byteArrayOf(0x00, 0x00, 0x01, 0x00, 0x01),
            ),
        )
        assertFalse(
            EmbedMetadataResolver.isSupportedIconBytes(
                "<html>not an icon</html>".toByteArray(),
            ),
        )
        assertFalse(
            EmbedMetadataResolver.isSupportedIconBytes(
                "<svg xmlns='http://www.w3.org/2000/svg'/>".toByteArray(),
            ),
        )
    }
}
