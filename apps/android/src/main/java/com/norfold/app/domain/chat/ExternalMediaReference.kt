package com.norfold.app.domain.chat

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.net.URI
import java.util.Base64

/**
 * Versioned reference to provider-owned external media (a KLIPY GIF rendition). Norfold never
 * downloads and re-uploads provider bytes into its own storage; a message carries this reference
 * and renders the provider URL directly.
 */
@Serializable
data class ExternalMediaReferenceV1(
    val version: Int = 1,
    val provider: String,
    val kind: String,
    /** Exact HTTPS provider URL. Path and query are preserved exactly as accepted. */
    val url: String,
    val title: String = "",
    val slug: String? = null,
    val width: Int? = null,
    val height: Int? = null,
)

/**
 * Parsing and validation boundary for external provider media. Accepts only exact HTTPS hosts on a
 * per-provider allowlist, rejects userinfo, lookalike hosts, malformed URLs, and unsupported
 * content types, and preserves an accepted path/query exactly. The sealed result makes every
 * rejection reason visible to callers and tests instead of collapsing into a boolean.
 */
object ExternalMediaReference {
    const val UriPrefix = "norfold-ext-media:"

    const val KLIPY_PROVIDER = "klipy"
    const val GIF_KIND = "gif"

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val allowedHostsByProvider = mapOf(
        KLIPY_PROVIDER to setOf("static.klipy.com", "static1.klipy.com", "static2.klipy.com"),
    )

    private val allowedExtensionsByKind = mapOf(
        GIF_KIND to setOf("gif", "webp", "mp4"),
    )

    sealed interface Result {
        data class Valid(val reference: ExternalMediaReferenceV1) : Result
        data class Rejected(val reason: String) : Result
    }

    fun validate(
        provider: String,
        kind: String,
        url: String,
        title: String = "",
        slug: String? = null,
        width: Int? = null,
        height: Int? = null,
    ): Result {
        val hosts = allowedHostsByProvider[provider] ?: return Result.Rejected("Unknown provider \"$provider\".")
        val extensions = allowedExtensionsByKind[kind] ?: return Result.Rejected("Unknown kind \"$kind\".")
        if (url.isBlank()) return Result.Rejected("Media URL is empty.")
        if (url.any { it.isWhitespace() || it.code < 0x20 }) return Result.Rejected("Media URL contains control characters.")
        val parsed = runCatching { URI(url) }.getOrNull() ?: return Result.Rejected("Malformed URL.")
        if (!parsed.scheme.equals("https", ignoreCase = true)) return Result.Rejected("Provider media must be HTTPS.")
        if (parsed.rawUserInfo != null) return Result.Rejected("Userinfo is not allowed in a provider URL.")
        val host = parsed.host ?: return Result.Rejected("Provider URL has no host.")
        val port = parsed.port
        if (port != -1 && port != 443) return Result.Rejected("Only the default HTTPS port is allowed.")
        val rawAuthority = parsed.rawAuthority ?: ""
        if (rawAuthority.contains('%')) return Result.Rejected("Encoded hosts are not allowed.")
        if (rawAuthority.any { it.code > 0x7E }) return Result.Rejected("Non-ASCII hosts are not allowed.")
        if (!rawAuthority.substringBefore(':').equals(host, ignoreCase = true)) {
            return Result.Rejected("Host identity is ambiguous.")
        }
        if (host.lowercase() !in hosts) return Result.Rejected("Host \"$host\" is not an allowed provider host.")
        val path = parsed.rawPath ?: return Result.Rejected("Provider URL has no path.")
        if (path == "/" || path.isEmpty()) return Result.Rejected("Provider media needs a real path.")
        val extension = path.substringAfterLast('.').lowercase()
        if (extension !in extensions) return Result.Rejected("Unsupported content type \".$extension\".")
        return Result.Valid(
            ExternalMediaReferenceV1(
                provider = provider,
                kind = kind,
                url = url,
                title = title,
                slug = slug,
                width = width,
                height = height,
            ),
        )
    }

    /** Encodes a reference into the `norfold-ext-media:` URI form stored in an existing attachment field. */
    fun toUri(reference: ExternalMediaReferenceV1): String =
        UriPrefix + b64(json.encodeToString(reference).encodeToByteArray())

    /** Decodes a `norfold-ext-media:` URI. Returns null for any other value (legacy messages). */
    fun fromUri(value: String): ExternalMediaReferenceV1? {
        if (!value.startsWith(UriPrefix)) return null
        return runCatching {
            json.decodeFromString<ExternalMediaReferenceV1>(unb64(value.removePrefix(UriPrefix)).decodeToString())
        }.getOrNull()
    }

    private fun b64(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    private fun unb64(value: String): ByteArray = Base64.getUrlDecoder().decode(value)
}
