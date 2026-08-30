package com.norfold.app.ui.screens

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class OnlineVideoWebPolicyTest {
    private val videoId = "abcDEF_123-"

    @Test
    fun `canonical privacy-enhanced embed navigation remains allowed`() {
        val allowed = listOf(
            "https://www.youtube-nocookie.com/embed/$videoId",
            "https://WWW.YOUTUBE-NOCOOKIE.COM/embed/$videoId?autoplay=1&playsinline=1",
            "https://www.youtube-nocookie.com:443/embed/$videoId#player",
        )
        allowed.forEach { url ->
            assertTrue(url, isAllowedOnlineVideoNavigation(url, videoId))
        }
    }

    @Test
    fun `alternate origins schemes ports and embed routes are rejected`() {
        val rejected = listOf(
            "http://www.youtube-nocookie.com/embed/$videoId",
            "file:///embed/$videoId",
            "content://www.youtube-nocookie.com/embed/$videoId",
            "data:text/html,$videoId",
            "javascript:alert(1)",
            "intent://www.youtube-nocookie.com/embed/$videoId",
            "//www.youtube-nocookie.com/embed/$videoId",
            "https://trusted@evil.example/embed/$videoId",
            "https://www.youtube-nocookie.com@evil.example/embed/$videoId",
            "https://sub.www.youtube-nocookie.com/embed/$videoId",
            "https://www.youtube-nocookie.com.evil.example/embed/$videoId",
            "https://www.youtube-nocookie.com./embed/$videoId",
            "https://www.youtube-nocookie.com:444/embed/$videoId",
            "https://www.youtube-nocookie.com/watch?v=$videoId",
            "https://www.youtube-nocookie.com/redirect?video=$videoId",
            "https://www.youtube-nocookie.com/embed/anotherId",
            "https://www.youtube-nocookie.com/embed/../embed/$videoId",
            "https://www.youtube-nocookie.com/embed%2f$videoId",
            "https://evil.example/youtube-nocookie.com/embed/$videoId",
        )
        rejected.forEach { url ->
            assertFalse(url, isAllowedOnlineVideoNavigation(url, videoId))
        }
    }

    @Test
    fun `only get requests may load in the video main frame`() {
        val embed = "https://www.youtube-nocookie.com/embed/$videoId?autoplay=1"
        assertTrue(isAllowedOnlineVideoMainFrameRequest("GET", embed, videoId))
        assertFalse(isAllowedOnlineVideoMainFrameRequest("POST", embed, videoId))
        assertFalse(
            isAllowedOnlineVideoMainFrameRequest(
                "GET",
                "https://evil.example/embed/$videoId",
                videoId,
            ),
        )
    }

    @Test
    fun `only exact HTTPS YouTube origins qualify for external fallback`() {
        listOf(
            "https://youtube.com/watch?v=$videoId",
            "https://www.youtube.com/watch?v=$videoId",
            "https://m.youtube.com/watch?v=$videoId",
            "https://youtu.be/$videoId",
            "https://www.youtube.com:443/watch?v=$videoId",
        ).forEach { url -> assertTrue(url, isAllowedOnlineVideoFallback(url)) }

        listOf(
            "http://www.youtube.com/watch?v=$videoId",
            "https://youtube.com.evil.example/watch?v=$videoId",
            "https://youtube.com@evil.example/watch?v=$videoId",
            "https://www.youtube.com:444/watch?v=$videoId",
            "javascript:alert(1)",
        ).forEach { url -> assertFalse(url, isAllowedOnlineVideoFallback(url)) }
    }

    @Test
    fun `viewer installs navigation policy and hardened settings before loading`() {
        val source = repositoryFile(
            "apps/android/src/main/java/com/norfold/app/ui/screens/WorkspaceScreens.kt",
        ).readText()
        val client = source.indexOf("webViewClient = object : WebViewClient()")
        val load = source.indexOf("https://www.youtube-nocookie.com/embed/\$videoId?autoplay=1&playsinline=1")
        assertTrue(client >= 0 && load > client)
        listOf(
            "settings.allowFileAccess = false",
            "settings.allowContentAccess = false",
            "settings.allowFileAccessFromFileURLs = false",
            "settings.allowUniversalAccessFromFileURLs = false",
            "settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW",
            "settings.safeBrowsingEnabled = true",
            "settings.javaScriptCanOpenWindowsAutomatically = false",
            "settings.setSupportMultipleWindows(false)",
            "override fun shouldInterceptRequest(",
            "override fun onPageStarted(",
            "mapOf(\"Referer\" to \"https://norfold-app.github.io/Norfold/\")",
        ).forEach { setting -> assertTrue(setting, source.contains(setting)) }
    }

    private fun repositoryFile(path: String): File {
        val candidates = listOf(File(path), File("../../$path"))
        return candidates.firstOrNull(File::isFile) ?: error("Repository file not found: $path")
    }
}
