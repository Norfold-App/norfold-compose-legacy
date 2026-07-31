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
    /** Sanitized SVG bundled in the APK/code path; null means extended on-demand catalogue. */
    val offlineSvg: String? = null,
) {
    val sourceUrl: String get() = if (offlineSvg != null) "builtin://shapes/$id" else "https://icon-sets.iconify.design/$prefix/$name/"
    val svgUrl: String get() = "https://api.iconify.design/$prefix/$name.svg?height=256"
    val isOffline: Boolean get() = offlineSvg != null
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
        "norfold-core" to "CC0-1.0",
    )

    val featured: List<ShapeCatalogItem> = listOf(
        offline("sparkle", "Sparkle", "<path d='M128 12l22 82 82 22-82 22-22 82-22-82-82-22 82-22z'/>") ,
        offline("arrow-right", "Arrow right", "<path d='M24 116h160l-52-52 16-16 80 80-80 80-16-16 52-52H24z'/>") ,
        offline("heart", "Heart", "<path d='M128 222L30 128C-28 66 58 0 128 62 198 0 284 66 226 128z'/>") ,
        offline("cloud", "Cloud", "<path d='M62 202h132c74 0 78-102 14-116C183 18 82 26 68 96 6 100 0 192 62 202z'/>") ,
        offline("lightbulb", "Idea", "<path d='M88 184h80v20H88zm8 32h64v20H96zM128 20c-60 0-92 70-54 116 14 16 22 24 22 36h64c0-12 8-20 22-36 38-46 6-116-54-116z'/>") ,
        offline("flag", "Flag", "<path d='M48 20h18v216H48zm18 12h142l-34 44 34 44H66z'/>") ,
        offline("flow-arrow", "Flow arrow", "<path d='M24 48h86v18H42v62h148V96l42 42-42 42v-34H24z'/>") ,
        offline("conversation", "Conversation", "<path d='M24 32h208v150H96l-52 42v-42H24zm48 58h112v18H72zm0 40h80v18H72z'/>") ,
        offline("badge-check", "Verified badge", "<path d='M128 14l28 22 36-2 10 34 30 20-14 34 14 34-30 20-10 34-36-2-28 22-28-22-36 2-10-34-30-20 14-34-14-34 30-20 10-34 36 2zm-18 144l66-66-16-16-50 50-24-24-16 16z'/>") ,
        offline("map-pin", "Map pin", "<path d='M128 14c-52 0-94 42-94 94 0 68 94 134 94 134s94-66 94-134c0-52-42-94-94-94zm0 132a38 38 0 110-76 38 38 0 010 76z'/>") ,
        offline("hierarchy", "Hierarchy", "<path d='M104 18h48v48h-48zM24 190h48v48H24zm80 0h48v48h-48zm80 0h48v48h-48zM120 66h16v46h72v62h-16v-46h-56v46h-16v-46H64v46H48v-62h72z'/>") ,
        offline("shapes", "Shapes", "<path d='M20 20h88v88H20zm136 0l76 88h-152zm-90 122a46 46 0 110 92 46 46 0 010-92zm90 0h76v76h-76z'/>") ,
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
        val svg = item.offlineSvg?.let(::svgDocument) ?: readUrl(item.svgUrl, MaxSvgBytes)
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

    private fun offline(name: String, title: String, body: String): ShapeCatalogItem = ShapeCatalogItem(
        id = "norfold-core:$name",
        prefix = "norfold-core",
        name = name,
        title = title,
        collection = "Norfold Core",
        spdxLicense = approvedCollections.getValue("norfold-core"),
        offlineSvg = body,
    )

    private fun svgDocument(body: String): String =
        "<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 256 256'>$body</svg>"

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

    internal fun validateSvg(svg: String) {
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
