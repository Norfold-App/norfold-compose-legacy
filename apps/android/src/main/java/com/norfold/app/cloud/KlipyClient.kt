package com.norfold.app.cloud

import com.norfold.app.BuildConfig
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

data class KlipyGif(
    val slug: String,
    val title: String,
    val previewUrl: String,
    val originalUrl: String,
    val width: Int,
    val height: Int,
)

/**
 * Small client for KLIPY's current v1 GIF API. The key is supplied through norfold.properties,
 * Gradle properties, or the environment; the public demo key is intentionally never embedded.
 */
object KlipyClient {
    private val http = HttpClient(OkHttp)
    private val json = Json { ignoreUnknownKeys = true }

    val configured: Boolean get() = BuildConfig.KLIPY_API_KEY.isNotBlank()

    suspend fun search(query: String, page: Int = 1, perPage: Int = 24): List<KlipyGif> {
        check(configured) { "KLIPY isn't configured for this build." }
        val action = if (query.isBlank()) "trending" else "search"
        val q = if (query.isBlank()) "" else "&q=${encode(query.trim())}"
        val url = "https://api.klipy.com/api/v1/${encode(BuildConfig.KLIPY_API_KEY)}/gifs/$action?page=$page&per_page=$perPage$q"
        val response = http.get(url)
        check(response.status.isSuccess()) { "KLIPY returned ${response.status.value}." }
        val root = json.parseToJsonElement(response.bodyAsText()).jsonObject
        return root["data"]?.jsonObject?.get("data")?.jsonArray.orEmpty().mapNotNull { element ->
            val item = element.jsonObject
            if (item["type"]?.jsonPrimitive?.contentOrNull == "ad") return@mapNotNull null
            val file = item["file"]?.jsonObject ?: return@mapNotNull null
            val preview = rendition(file, "sm") ?: rendition(file, "xs") ?: return@mapNotNull null
            val original = rendition(file, "md") ?: rendition(file, "hd") ?: preview
            KlipyGif(
                slug = item["slug"]?.jsonPrimitive?.contentOrNull ?: original.first,
                title = item["title"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                previewUrl = preview.first,
                originalUrl = original.first,
                width = original.second,
                height = original.third,
            )
        }
    }

    private fun rendition(file: kotlinx.serialization.json.JsonObject, size: String): Triple<String, Int, Int>? {
        val formats = file[size]?.jsonObject ?: return null
        // Prefer the real GIF rendition. Some devices decode provider WebP renditions as a
        // single frame even when the source is animated, which makes a sent GIF look frozen.
        val media = formats["gif"]?.jsonObject ?: formats["webp"]?.jsonObject ?: return null
        val url = media["url"]?.jsonPrimitive?.contentOrNull ?: return null
        return Triple(
            url,
            media["width"]?.jsonPrimitive?.intOrNull ?: 1,
            media["height"]?.jsonPrimitive?.intOrNull ?: 1,
        )
    }

    private fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8.toString())
}
