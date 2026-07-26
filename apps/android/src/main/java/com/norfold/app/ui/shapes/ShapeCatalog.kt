package com.norfold.app.ui.shapes

import com.norfold.app.domain.VectorShapeAsset
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

data class ShapeCatalogItem(
    val id: String,
    val prefix: String,
    val name: String,
    val title: String,
    val collection: String,
    val spdxLicense: String,
) {
    val sourceUrl: String get() = "https://icon-sets.iconify.design/$prefix/$name/"
    val svgUrl: String get() = "https://api.iconify.design/$prefix/$name.svg?height=256"
}

class ShapeCatalogException(message: String, cause: Throwable? = null) : Exception(message, cause)

object ShapeCatalog {
    private const val MaxSvgBytes = 128 * 1024
    private const val SearchLimit = 96
    private const val ApiBase = "https://api.iconify.design"

    /** Prefix -> expected SPDX. Both values must match before a result reaches the picker. */
    val approvedCollections: Map<String, String> = linkedMapOf(
        "ph" to "MIT",
        "material-symbols" to "Apache-2.0",
        "lucide" to "ISC",
        "tabler" to "MIT",
    )

    val featured: List<ShapeCatalogItem> = listOf(
        featured("ph:star-four", "Sparkle"),
        featured("ph:arrow-right", "Arrow right"),
        featured("ph:heart", "Heart"),
        featured("ph:cloud", "Cloud"),
        featured("ph:lightbulb", "Idea"),
        featured("ph:flag-banner", "Banner"),
        featured("ph:flow-arrow", "Flow arrow"),
        featured("ph:chat-circle-dots", "Conversation"),
        featured("ph:seal-check", "Verified badge"),
        featured("ph:map-pin", "Map pin"),
        featured("ph:tree-structure", "Hierarchy"),
        featured("ph:shapes", "Shapes"),
    )

    suspend fun search(query: String): List<ShapeCatalogItem> = withContext(Dispatchers.IO) {
        val normalized = query.trim().take(80)
        if (normalized.length < 2) return@withContext emptyList()
        val encoded = URLEncoder.encode(normalized, Charsets.UTF_8.name())
        val prefixes = approvedCollections.keys.joinToString(",")
        val json = JSONObject(readUrl("$ApiBase/search?query=$encoded&prefixes=$prefixes&limit=$SearchLimit", 512 * 1024))
        val collectionMetadata = json.optJSONObject("collections") ?: JSONObject()
        val verifiedPrefixes = approvedCollections.filter { (prefix, expectedSpdx) ->
            collectionMetadata.optJSONObject(prefix)
                ?.optJSONObject("license")
                ?.optString("spdx") == expectedSpdx
        }.keys
        val icons = json.optJSONArray("icons") ?: return@withContext emptyList()
        buildList {
            for (index in 0 until icons.length()) {
                val id = icons.optString(index)
                val prefix = id.substringBefore(':', missingDelimiterValue = "")
                val name = id.substringAfter(':', missingDelimiterValue = "")
                if (prefix !in verifiedPrefixes || name.isBlank() || !safeName.matches(name)) continue
                val meta = collectionMetadata.optJSONObject(prefix)
                add(
                    ShapeCatalogItem(
                        id = id,
                        prefix = prefix,
                        name = name,
                        title = humanize(name),
                        collection = meta?.optString("name")?.takeIf(String::isNotBlank) ?: prefix,
                        spdxLicense = approvedCollections.getValue(prefix),
                    ),
                )
            }
        }.distinctBy(ShapeCatalogItem::id)
    }

    suspend fun download(item: ShapeCatalogItem): VectorShapeAsset = withContext(Dispatchers.IO) {
        val expected = approvedCollections[item.prefix]
            ?: throw ShapeCatalogException("This collection is not approved for Norfold.")
        if (expected != item.spdxLicense || !safeName.matches(item.name)) {
            throw ShapeCatalogException("The shape license could not be verified.")
        }
        val svg = readUrl(item.svgUrl, MaxSvgBytes)
        validateSvg(svg)
        VectorShapeAsset(
            id = item.id,
            title = item.title,
            collection = item.collection,
            spdxLicense = item.spdxLicense,
            sourceUrl = item.sourceUrl,
            svg = svg,
        )
    }

    private fun featured(id: String, title: String): ShapeCatalogItem {
        val prefix = id.substringBefore(':')
        val name = id.substringAfter(':')
        return ShapeCatalogItem(id, prefix, name, title, "Phosphor", approvedCollections.getValue(prefix))
    }

    private fun readUrl(url: String, maxBytes: Int): String {
        val uri = runCatching { URI(url) }.getOrElse { throw ShapeCatalogException("Invalid catalog address.", it) }
        if (uri.scheme != "https" || uri.host != "api.iconify.design") {
            throw ShapeCatalogException("The catalog returned an untrusted address.")
        }
        val connection = (uri.toURL().openConnection() as HttpURLConnection).apply {
            connectTimeout = 8_000
            readTimeout = 12_000
            requestMethod = "GET"
            setRequestProperty("Accept", "application/json,image/svg+xml")
            setRequestProperty("User-Agent", "Norfold-Android/1.0")
        }
        try {
            val status = connection.responseCode
            if (status !in 200..299) throw ShapeCatalogException("Shape catalog request failed ($status).")
            connection.inputStream.use { input ->
                val output = ByteArrayOutputStream(minOf(connection.contentLength.coerceAtLeast(0), maxBytes))
                val buffer = ByteArray(8 * 1024)
                var total = 0
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    if (total > maxBytes) throw ShapeCatalogException("The catalog response was too large.")
                    output.write(buffer, 0, count)
                }
                return output.toString(Charsets.UTF_8.name())
            }
        } catch (error: ShapeCatalogException) {
            throw error
        } catch (error: Exception) {
            throw ShapeCatalogException("Couldn't reach the shape catalog. Check your connection and try again.", error)
        } finally {
            connection.disconnect()
        }
    }

    private fun validateSvg(svg: String) {
        val trimmed = svg.trimStart()
        if (!trimmed.startsWith("<svg", ignoreCase = true) || !trimmed.trimEnd().endsWith("</svg>", ignoreCase = true)) {
            throw ShapeCatalogException("The downloaded asset was not a valid SVG.")
        }
        val unsafe = unsafeSvgPatterns.firstOrNull { it.containsMatchIn(svg) }
        if (unsafe != null) throw ShapeCatalogException("The downloaded SVG contained unsupported active content.")
    }

    private fun humanize(name: String): String = name
        .replace('-', ' ')
        .split(' ')
        .filter(String::isNotBlank)
        .joinToString(" ") { token -> token.replaceFirstChar { it.titlecase() } }

    private val safeName = Regex("[a-z0-9][a-z0-9-]{0,120}")
    private val unsafeSvgPatterns = listOf(
        Regex("<\\s*script", RegexOption.IGNORE_CASE),
        Regex("<\\s*foreignObject", RegexOption.IGNORE_CASE),
        Regex("<\\s*(animate|animateTransform|set)(?:\\s|>)", RegexOption.IGNORE_CASE),
        Regex("<!\\s*(DOCTYPE|ENTITY)", RegexOption.IGNORE_CASE),
        Regex("\\bon[a-z]+\\s*=", RegexOption.IGNORE_CASE),
        Regex("\\b(?:href|xlink:href)\\s*=", RegexOption.IGNORE_CASE),
        Regex("url\\s*\\(", RegexOption.IGNORE_CASE),
        Regex("data\\s*:", RegexOption.IGNORE_CASE),
    )
}
