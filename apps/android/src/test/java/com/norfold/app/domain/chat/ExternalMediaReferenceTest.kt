package com.norfold.app.domain.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExternalMediaReferenceTest {
    private val klipyHosts = listOf("static.klipy.com", "static1.klipy.com", "static2.klipy.com")

    @Test
    fun `exact klipy hosts and gif paths are accepted with path and query preserved`() {
        val accepted = listOf(
            "https://static.klipy.com/media/abc/sm.gif",
            "https://static1.klipy.com/media/abc/hd.gif?token=x",
            "https://static2.klipy.com/abc.webp",
            "https://static1.klipy.com/a/b/c/sm.gif?t=1&u=2",
        )
        accepted.forEach { url ->
            val result = ExternalMediaReference.validate(
                provider = ExternalMediaReference.KLIPY_PROVIDER,
                kind = ExternalMediaReference.GIF_KIND,
                url = url,
                title = "Hello",
                slug = "hello",
                width = 240,
                height = 120,
            )
            assertTrue(url, result is ExternalMediaReference.Result.Valid)
            val reference = (result as ExternalMediaReference.Result.Valid).reference
            assertEquals(url, reference.url)
            assertEquals("Hello", reference.title)
            assertEquals("hello", reference.slug)
            assertEquals(240, reference.width)
            assertEquals(120, reference.height)
            assertEquals(ExternalMediaReference.KLIPY_PROVIDER, reference.provider)
            assertEquals(ExternalMediaReference.GIF_KIND, reference.kind)
        }
    }

    @Test
    fun `http userinfo lookalike hosts and wrong ports are rejected`() {
        val rejected = listOf(
            "http://static.klipy.com/media/abc.gif",
            "ftp://static.klipy.com/abc.gif",
            "https://user:pass@static.klipy.com/abc.gif",
            "https://user@static.klipy.com/abc.gif",
            "https://static.klipy.com@evil.example/abc.gif",
            "https://static.klipy.com/abc.gif@evil.example",
            "https://sub.static.klipy.com/abc.gif",
            "https://static.klipy.com.evil.example/abc.gif",
            "https://evil-static.klipy.com/abc.gif",
            "https://static.klipy.com./abc.gif",
            "https://static.klipy.com:443/abc.gif",
            "https://static.klipy.com:444/abc.gif",
            "https://static.klipy.com:8443/abc.gif",
            "https://notklipy.com/abc.gif",
            "https://static.klipy.org/abc.gif",
            "https://static.klipy.com/abc.png",
            "https://static.klipy.com/abc.jpg",
            "https://static.klipy.com/abc.mp4",
            "https://static.klipy.com/abc.html",
            "https://static.klipy.com/abc",
            "https://static.klipy.com/",
            "https://static.klipy.com",
            "https://static.klipy.com/abc.gif/../../etc/passwd",
            "javascript:alert(1)",
            "https://[::1]/abc.gif",
            "https://static.klipy.com/abc.gif\n",
            "https://static.klipy.com/abc.gif#fragment",
            "https://static.klipy.com/ab c.gif",
            "not a url",
            "",
        )
        rejected.forEach { url ->
            val result = ExternalMediaReference.validate(
                provider = ExternalMediaReference.KLIPY_PROVIDER,
                kind = ExternalMediaReference.GIF_KIND,
                url = url,
            )
            assertTrue(url, result is ExternalMediaReference.Result.Rejected)
        }
    }

    @Test
    fun `unknown provider or kind is rejected even with a valid url`() {
        val validUrl = "https://static.klipy.com/abc.gif"
        assertTrue(
            ExternalMediaReference.validate("other", ExternalMediaReference.GIF_KIND, validUrl)
                is ExternalMediaReference.Result.Rejected,
        )
        assertTrue(
            ExternalMediaReference.validate(ExternalMediaReference.KLIPY_PROVIDER, "image", validUrl)
                is ExternalMediaReference.Result.Rejected,
        )
    }

    @Test
    fun `each allowed klipy host parses on its own`() {
        klipyHosts.forEach { host ->
            val url = "https://$host/one/two.gif?q=1"
            val result = ExternalMediaReference.validate(
                ExternalMediaReference.KLIPY_PROVIDER,
                ExternalMediaReference.GIF_KIND,
                url,
            )
            assertTrue(url, result is ExternalMediaReference.Result.Valid)
            assertEquals(url, (result as ExternalMediaReference.Result.Valid).reference.url)
        }
    }

    @Test
    fun `uri round trip preserves every field`() {
        val reference = ExternalMediaReferenceV1(
            provider = ExternalMediaReference.KLIPY_PROVIDER,
            kind = ExternalMediaReference.GIF_KIND,
            url = "https://static2.klipy.com/media/slug/original.gif?size=full",
            title = "A happy cat",
            slug = "a-happy-cat",
            width = 640,
            height = 480,
        )
        val uri = ExternalMediaReference.toUri(reference)
        assertTrue(uri.startsWith(ExternalMediaReference.UriPrefix))
        val decoded = ExternalMediaReference.fromUri(uri)
        assertNotNull(decoded)
        assertEquals(reference, decoded)
    }

    @Test
    fun `legacy and non reference uris decode to null instead of crashing`() {
        listOf(
            "https://static.klipy.com/abc.gif",
            "content://media/abc.gif",
            "norfold-secure-media:abc",
            "",
        ).forEach { value ->
            assertNull(value, ExternalMediaReference.fromUri(value))
        }
    }

    @Test
    fun `decoded references are revalidated instead of trusting serialized fields`() {
        val valid = ExternalMediaReferenceV1(
            provider = ExternalMediaReference.KLIPY_PROVIDER,
            kind = ExternalMediaReference.GIF_KIND,
            url = "https://static.klipy.com/abc.gif",
        )
        val encoded = ExternalMediaReference.toUri(valid)
        assertEquals(valid, ExternalMediaReference.fromUri(encoded))

        val payload = encoded.removePrefix(ExternalMediaReference.UriPrefix)
        val decodedJson = String(java.util.Base64.getUrlDecoder().decode(payload))
        val tamperedJson = decodedJson.replace("static.klipy.com", "static.klipy.com.evil.example")
        val tampered = ExternalMediaReference.UriPrefix +
            java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(tamperedJson.encodeToByteArray())
        assertNull(ExternalMediaReference.fromUri(tampered))
    }

    @Test
    fun `unsupported versions and invalid dimensions are rejected`() {
        val invalidVersion = ExternalMediaReferenceV1(
            version = 2,
            provider = ExternalMediaReference.KLIPY_PROVIDER,
            kind = ExternalMediaReference.GIF_KIND,
            url = "https://static.klipy.com/abc.gif",
        )
        assertTrue(runCatching { ExternalMediaReference.toUri(invalidVersion) }.isFailure)
        assertTrue(
            ExternalMediaReference.validate(
                provider = ExternalMediaReference.KLIPY_PROVIDER,
                kind = ExternalMediaReference.GIF_KIND,
                url = "https://static.klipy.com/abc.gif",
                width = 0,
            ) is ExternalMediaReference.Result.Rejected,
        )
    }

    @Test
    fun `provider metadata and version survive a backup restore round trip`() {
        val original = ExternalMediaReferenceV1(
            provider = ExternalMediaReference.KLIPY_PROVIDER,
            kind = ExternalMediaReference.GIF_KIND,
            url = "https://static1.klipy.com/x/y.gif",
            title = "Backup me",
            slug = "backup-me",
            width = 320,
            height = 200,
        )
        val serialized = ExternalMediaReference.toUri(original)
        val restored = ExternalMediaReference.fromUri(serialized)
        assertNotNull(restored)
        assertEquals(1, restored?.version)
        assertEquals(original, restored)
        // The reference is small and self-contained: a single value that can travel inside the
        // existing encrypted message payload or an existing attachment column.
        assertTrue(serialized.length < 512)
    }
}
