package com.norfold.app.ui.components

import android.content.Context
import android.text.Html
import com.norfold.app.domain.EmbedMetadata
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.net.URI
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

object EmbedMetadataResolver {
    private const val MaxHtmlBytes = 512 * 1024L
    private const val MaxIconBytes = 1024 * 1024L
    private val client = OkHttpClient.Builder()
        .connectTimeout(7, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .callTimeout(12, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    suspend fun resolve(context: Context, rawUrl: String, existing: EmbedMetadata): EmbedMetadata = withContext(Dispatchers.IO) {
        val uri = rawUrl.toWebUri() ?: return@withContext existing
        val cachedIcon = cachedIcon(context, uri.host.orEmpty())
        if (existing.title.isNotBlank() && (existing.faviconPath != null || cachedIcon.isFile)) {
            return@withContext existing.copy(faviconPath = existing.faviconPath ?: cachedIcon.absolutePath)
        }
        runCatching {
            val request = Request.Builder()
                .url(uri.toString())
                .header("User-Agent", "Norfold/0.1 (+https://sheikhti1205.github.io/Norfold/)")
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use existing
                val body = response.body
                if (body.contentLength() > MaxHtmlBytes) return@use existing
                val html = body.byteStream().readBounded(MaxHtmlBytes + 1)
                if (html.size > MaxHtmlBytes) return@use existing
                val source = html.toString(Charsets.UTF_8)
                val finalUri = response.request.url.toUri()
                val title = source.firstMeta(titlePattern).ifBlank { existing.title.ifBlank { finalUri.host.orEmpty() } }
                val description = source.firstMeta(descriptionPattern).ifBlank { existing.description }
                val iconHref = source.firstMeta(iconPattern)
                val iconUri = resolveUrl(finalUri, iconHref.ifBlank { "/favicon.ico" })
                val icon = if (cachedIcon.isFile) cachedIcon else iconUri?.let { downloadIcon(it, cachedIcon) }
                EmbedMetadata(title = decode(title), description = decode(description), faviconPath = icon?.absolutePath)
            }
        }.getOrElse { existing.copy(faviconPath = existing.faviconPath ?: cachedIcon.takeIf(File::isFile)?.absolutePath) }
    }

    private fun downloadIcon(uri: URI, target: File): File? {
        val request = Request.Builder().url(uri.toString()).header("User-Agent", "Norfold/0.1").build()
        return runCatching {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val body = response.body
                if (body.contentLength() > MaxIconBytes) return@use null
                val bytes = body.byteStream().readBounded(MaxIconBytes + 1)
                if (bytes.size > MaxIconBytes || bytes.isEmpty()) return@use null
                target.parentFile?.mkdirs()
                val temporary = File(target.parentFile, "${target.name}.tmp")
                temporary.writeBytes(bytes)
                if (!temporary.renameTo(target)) {
                    temporary.copyTo(target, overwrite = true)
                    temporary.delete()
                }
                target
            }
        }.getOrNull()
    }

    private fun cachedIcon(context: Context, host: String): File {
        val digest = MessageDigest.getInstance("SHA-256").digest(host.lowercase().toByteArray())
        val key = digest.joinToString("") { "%02x".format(it) }
        return File(File(context.cacheDir, "embed-favicons"), "$key.icon")
    }

    private fun String.firstMeta(pattern: Regex): String = pattern.find(this)
        ?.groupValues
        ?.drop(1)
        ?.firstOrNull(String::isNotBlank)
        .orEmpty()
        .trim()
    private fun decode(value: String): String = Html.fromHtml(value, Html.FROM_HTML_MODE_LEGACY).toString().trim()
    private fun String.toWebUri(): URI? = runCatching { URI(trim()) }.getOrNull()?.takeIf { it.scheme in setOf("http", "https") && !it.host.isNullOrBlank() }
    private fun resolveUrl(base: URI, candidate: String): URI? = runCatching { base.resolve(candidate) }.getOrNull()?.takeIf { it.scheme in setOf("http", "https") }

    private fun InputStream.readBounded(limit: Long): ByteArray {
        val output = ByteArrayOutputStream(minOf(limit, 16 * 1024L).toInt())
        val buffer = ByteArray(8 * 1024)
        var remaining = limit
        while (remaining > 0) {
            val count = read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (count < 0) break
            output.write(buffer, 0, count)
            remaining -= count
        }
        return output.toByteArray()
    }

    private val titlePattern = Regex("(?is)<title[^>]*>(.*?)</title>")
    private val descriptionPattern = Regex("(?is)<meta[^>]+(?:name|property)\\s*=\\s*['\"](?:description|og:description)['\"][^>]+content\\s*=\\s*['\"](.*?)['\"][^>]*>|<meta[^>]+content\\s*=\\s*['\"](.*?)['\"][^>]+(?:name|property)\\s*=\\s*['\"](?:description|og:description)['\"][^>]*>")
    private val iconPattern = Regex("(?is)<link[^>]+rel\\s*=\\s*['\"][^'\"]*(?:icon|shortcut)[^'\"]*['\"][^>]+href\\s*=\\s*['\"](.*?)['\"][^>]*>|<link[^>]+href\\s*=\\s*['\"](.*?)['\"][^>]+rel\\s*=\\s*['\"][^'\"]*(?:icon|shortcut)[^'\"]*['\"][^>]*>")
}
