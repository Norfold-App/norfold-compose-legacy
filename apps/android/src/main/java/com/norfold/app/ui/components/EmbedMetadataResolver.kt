package com.norfold.app.ui.components

import android.content.Context
import android.text.Html
import com.norfold.app.domain.EmbedMetadata
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.net.URI
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

object EmbedMetadataResolver {
    private const val MaxHtmlBytes = 512 * 1024L
    private const val MaxIconBytes = 1024 * 1024L
    private const val UserAgent = "Norfold/0.1 (+https://sheikhti1205.github.io/Norfold/)"
    private const val HtmlAccept = "text/html,application/xhtml+xml;q=0.9,*/*;q=0.2"
    private const val ImageAccept = "image/png,image/webp,image/jpeg,image/gif,image/x-icon,image/*;q=0.8,*/*;q=0.1"

    private val client = OkHttpClient.Builder()
        .connectTimeout(7, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .callTimeout(12, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    suspend fun resolve(
        context: Context,
        rawUrl: String,
        existing: EmbedMetadata,
    ): EmbedMetadata = withContext(Dispatchers.IO) {
        val requestedUri = rawUrl.toWebUri() ?: return@withContext existing
        runCatching {
            val existingIcon = existing.faviconPath
                ?.let(::File)
                ?.takeIf { it.isUsableIconFile() }
            val requestedCache = cachedIcon(context, requestedUri.host.orEmpty())
            val initialIcon = existingIcon ?: requestedCache.takeIf { it.isUsableIconFile() }

            if (existing.title.isNotBlank() && initialIcon != null) {
                return@runCatching existing.copy(faviconPath = initialIcon.absolutePath)
            }

            val fetchedPage = fetchPage(requestedUri)
            val finalUri = fetchedPage?.finalUri ?: requestedUri
            val page = fetchedPage?.html
                ?.let { parsePageMetadata(finalUri, it) }
                ?: PageMetadata()
            val finalCache = cachedIcon(context, finalUri.host.orEmpty())
            val icon = initialIcon
                ?: finalCache.takeIf { it.isUsableIconFile() }
                ?: faviconCandidates(finalUri, page.iconCandidates)
                    .firstNotNullOfOrNull { candidate -> downloadIcon(candidate, finalCache) }

            val title = decode(page.title)
                .ifBlank { existing.title }
                .ifBlank { finalUri.host.orEmpty().removePrefix("www.") }
            val description = decode(page.description).ifBlank { existing.description }
            EmbedMetadata(
                title = title,
                description = description,
                faviconPath = icon?.absolutePath,
            )
        }.getOrElse {
            val validExistingIcon = existing.faviconPath
                ?.let(::File)
                ?.takeIf { file -> file.isUsableIconFile() }
            existing.copy(faviconPath = validExistingIcon?.absolutePath)
        }
    }

    private fun fetchPage(uri: URI): FetchedPage? = runCatching {
        val request = Request.Builder()
            .url(uri.toString())
            .header("Accept", HtmlAccept)
            .header("User-Agent", UserAgent)
            .build()
        client.newCall(request).execute().use { response ->
            val finalUri = response.request.url.toUri()
            if (!response.isSuccessful) return@use FetchedPage(finalUri, null)

            val body = response.body
            val contentType = body.contentType()
            val mimeType = contentType
                ?.let { "${it.type}/${it.subtype}" }
                ?.lowercase(Locale.ROOT)
            if (mimeType != null && mimeType !in HtmlMimeTypes) {
                return@use FetchedPage(finalUri, null)
            }
            if (body.contentLength() > MaxHtmlBytes) return@use FetchedPage(finalUri, null)

            val bytes = body.byteStream().readBounded(MaxHtmlBytes + 1)
            if (bytes.size > MaxHtmlBytes) return@use FetchedPage(finalUri, null)
            val charset = contentType?.charset(Charsets.UTF_8) ?: Charsets.UTF_8
            FetchedPage(finalUri, bytes.toString(charset))
        }
    }.getOrNull()

    private fun downloadIcon(uri: URI, target: File): File? {
        val request = Request.Builder()
            .url(uri.toString())
            .header("Accept", ImageAccept)
            .header("User-Agent", UserAgent)
            .build()
        return runCatching {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val body = response.body
                if (body.contentLength() > MaxIconBytes) return@use null
                val bytes = body.byteStream().readBounded(MaxIconBytes + 1)
                if (bytes.size > MaxIconBytes || !isSupportedIconBytes(bytes)) return@use null

                val directory = target.parentFile ?: return@use null
                if (!directory.isDirectory && !directory.mkdirs()) return@use null
                val temporary = File.createTempFile("${target.name}.", ".tmp", directory)
                try {
                    temporary.writeBytes(bytes)
                    if (!temporary.renameTo(target)) {
                        temporary.copyTo(target, overwrite = true)
                    }
                    target.takeIf { it.isUsableIconFile() }
                } finally {
                    temporary.delete()
                }
            }
        }.getOrNull()
    }

    private fun cachedIcon(context: Context, host: String): File {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(host.lowercase(Locale.ROOT).toByteArray())
        val key = digest.joinToString("") { "%02x".format(it) }
        return File(File(context.cacheDir, "embed-favicons"), "$key.icon")
    }

    internal fun parsePageMetadata(baseUri: URI, html: String): PageMetadata {
        val metaValues = linkedMapOf<String, String>()
        MetaTagPattern.findAll(html).forEach { match ->
            val attributes = parseAttributes(match.value)
            val key = (attributes["property"] ?: attributes["name"] ?: attributes["itemprop"])
                ?.trim()
                ?.lowercase(Locale.ROOT)
                .orEmpty()
            val content = attributes["content"].orEmpty().trim()
            if (key.isNotBlank() && content.isNotBlank()) metaValues.putIfAbsent(key, content)
        }

        val links = LinkTagPattern.findAll(html).mapIndexedNotNull { order, match ->
            val attributes = parseAttributes(match.value)
            val rel = attributes["rel"].orEmpty().trim().lowercase(Locale.ROOT)
            if (!isIconRel(rel)) return@mapIndexedNotNull null
            val href = attributes["href"].orEmpty().trim()
            val uri = resolveUrl(baseUri, decodeAttributeEntities(href))
                ?: return@mapIndexedNotNull null
            IconLink(
                uri = uri,
                rel = rel,
                type = attributes["type"].orEmpty().trim().lowercase(Locale.ROOT),
                sizes = attributes["sizes"].orEmpty().trim().lowercase(Locale.ROOT),
                order = order,
            )
        }.toList()

        val title = sequenceOf("og:title", "twitter:title", "twitter:text:title")
            .mapNotNull(metaValues::get)
            .firstOrNull(String::isNotBlank)
            .orEmpty()
            .ifBlank { TitlePattern.find(html)?.groupValues?.getOrNull(1).orEmpty().trim() }
        val description = sequenceOf("og:description", "description", "twitter:description")
            .mapNotNull(metaValues::get)
            .firstOrNull(String::isNotBlank)
            .orEmpty()
        val iconCandidates = links
            .sortedWith(compareByDescending<IconLink>(::iconRank).thenBy(IconLink::order))
            .map(IconLink::uri)
            .distinctBy(URI::toString)

        return PageMetadata(
            title = title,
            description = description,
            iconCandidates = iconCandidates,
        )
    }

    private fun faviconCandidates(baseUri: URI, declared: List<URI>): List<URI> = buildList {
        addAll(declared)
        resolveUrl(baseUri, "/favicon.ico")?.let(::add)
    }.distinctBy(URI::toString)

    private fun parseAttributes(tag: String): Map<String, String> {
        val tagName = TagNamePattern.find(tag) ?: return emptyMap()
        val attributes = linkedMapOf<String, String>()
        AttributePattern.findAll(tag, tagName.range.last + 1).forEach { match ->
            val name = match.groupValues[1].lowercase(Locale.ROOT)
            if (name == ">" || name == "/") return@forEach
            val value = match.groupValues.drop(2).firstOrNull(String::isNotEmpty).orEmpty()
            attributes.putIfAbsent(name, value)
        }
        return attributes
    }

    private fun isIconRel(rel: String): Boolean = rel
        .split(RelWhitespacePattern)
        .any { token -> token == "icon" || token in AlternateIconRels }

    private fun iconRank(link: IconLink): Int {
        val location = link.uri.path.orEmpty().lowercase(Locale.ROOT)
        val formatScore = when {
            link.type == "image/svg+xml" || location.endsWith(".svg") -> -100
            link.type == "image/png" || location.endsWith(".png") -> 70
            link.type == "image/webp" || location.endsWith(".webp") -> 65
            link.type in JpegMimeTypes || location.endsWith(".jpg") || location.endsWith(".jpeg") -> 60
            link.type in IconMimeTypes || location.endsWith(".ico") -> 55
            link.type == "image/gif" || location.endsWith(".gif") -> 45
            else -> 20
        }
        val relTokens = link.rel.split(RelWhitespacePattern)
        val relScore = when {
            "icon" in relTokens -> 30
            relTokens.any { it.startsWith("apple-touch-icon") } -> 25
            else -> 10
        }
        val sizeScore = SizePattern.findAll(link.sizes)
            .mapNotNull { match ->
                val width = match.groupValues[1].toIntOrNull() ?: return@mapNotNull null
                val height = match.groupValues[2].toIntOrNull() ?: return@mapNotNull null
                minOf(width, height, 512) / 16
            }
            .maxOrNull()
            ?: 0
        return formatScore + relScore + sizeScore
    }

    internal fun isSupportedIconBytes(bytes: ByteArray): Boolean =
        bytes.hasSignature(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) ||
            bytes.hasSignature(0xFF, 0xD8, 0xFF) ||
            bytes.hasSignature(0x47, 0x49, 0x46, 0x38) ||
            (bytes.hasSignature(0x52, 0x49, 0x46, 0x46) && bytes.hasSignatureAt(8, 0x57, 0x45, 0x42, 0x50)) ||
            bytes.hasSignature(0x42, 0x4D) ||
            bytes.hasSignature(0x00, 0x00, 0x01, 0x00)

    private fun ByteArray.hasSignature(vararg signature: Int): Boolean = hasSignatureAt(0, *signature)

    private fun ByteArray.hasSignatureAt(offset: Int, vararg signature: Int): Boolean =
        size >= offset + signature.size && signature.indices.all { index ->
            (this[offset + index].toInt() and 0xFF) == signature[index]
        }

    private fun File.isUsableIconFile(): Boolean {
        if (!isFile || length() !in 1..MaxIconBytes) return false
        return runCatching {
            inputStream().use { input ->
                val header = ByteArray(16)
                val count = input.read(header)
                count > 0 && isSupportedIconBytes(header.copyOf(count))
            }
        }.getOrDefault(false)
    }

    private fun decode(value: String): String = Html
        .fromHtml(value, Html.FROM_HTML_MODE_LEGACY)
        .toString()
        .trim()

    private fun String.toWebUri(): URI? = runCatching { URI(trim()) }
        .getOrNull()
        ?.takeIf { it.scheme?.lowercase(Locale.ROOT) in WebSchemes && !it.host.isNullOrBlank() }

    private fun resolveUrl(base: URI, candidate: String): URI? = runCatching {
        base.resolve(candidate.trim().replace(" ", "%20"))
    }.getOrNull()?.takeIf {
        it.scheme?.lowercase(Locale.ROOT) in WebSchemes && !it.host.isNullOrBlank()
    }

    private fun decodeAttributeEntities(value: String): String = value
        .replace("&amp;", "&", ignoreCase = true)
        .replace("&quot;", "\"", ignoreCase = true)
        .replace("&#39;", "'", ignoreCase = true)
        .replace("&apos;", "'", ignoreCase = true)

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

    internal data class PageMetadata(
        val title: String = "",
        val description: String = "",
        val iconCandidates: List<URI> = emptyList(),
    )

    private data class FetchedPage(val finalUri: URI, val html: String?)

    private data class IconLink(
        val uri: URI,
        val rel: String,
        val type: String,
        val sizes: String,
        val order: Int,
    )

    private val HtmlMimeTypes = setOf("text/html", "application/xhtml+xml")
    private val WebSchemes = setOf("http", "https")
    private val AlternateIconRels = setOf(
        "apple-touch-icon",
        "apple-touch-icon-precomposed",
        "mask-icon",
    )
    private val IconMimeTypes = setOf("image/x-icon", "image/vnd.microsoft.icon")
    private val JpegMimeTypes = setOf("image/jpeg", "image/jpg")
    private val MetaTagPattern = Regex("(?is)<meta\\b[^>]*>")
    private val LinkTagPattern = Regex("(?is)<link\\b[^>]*>")
    private val TitlePattern = Regex("(?is)<title\\b[^>]*>(.*?)</title\\s*>")
    private val TagNamePattern = Regex("(?is)^<\\s*[a-z][a-z0-9:-]*")
    private val AttributePattern = Regex(
        """(?is)([^\s=/>]+)(?:\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s"'=<>`]+)))?""",
    )
    private val RelWhitespacePattern = Regex("\\s+")
    private val SizePattern = Regex("(\\d+)\\s*x\\s*(\\d+)")
}
