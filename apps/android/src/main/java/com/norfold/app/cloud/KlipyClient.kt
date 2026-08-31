package com.norfold.app.cloud

import android.content.Context
import com.norfold.app.BuildConfig
import com.norfold.app.domain.chat.ExternalMediaReference
import com.norfold.app.domain.chat.ExternalMediaReferenceV1
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import java.io.File
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

data class KlipyGif(
    val slug: String,
    val title: String,
    val previewUrl: String,
    val originalUrl: String,
    val width: Int,
    val height: Int,
    /** Validated provider-media reference for the original rendition; null when validation fails. */
    val reference: ExternalMediaReferenceV1? = null,
    /** Exact picker query that produced the result; blank means Trending. */
    val shareQuery: String = "",
)

/**
 * Small client for KLIPY's current v1 GIF API. The key is supplied through norfold.properties,
 * Gradle properties, or the environment; the public demo key is intentionally never embedded.
 */
object KlipyClient {
    private val http = HttpClient(OkHttp)

    val configured: Boolean get() = BuildConfig.KLIPY_API_KEY.isNotBlank()

    suspend fun search(
        query: String,
        customerId: String,
        page: Int = 1,
        perPage: Int = 24,
        locale: String? = KlipyRequestBuilder.deviceLocale(),
    ): List<KlipyGif> {
        check(configured) { "KLIPY isn't configured for this build." }
        val cleanQuery = query.trim()
        val url = KlipyRequestBuilder.searchUrl(
            apiKey = BuildConfig.KLIPY_API_KEY,
            query = cleanQuery,
            customerId = customerId,
            page = page,
            perPage = perPage,
            locale = locale,
        )
        val response = http.get(url)
        check(response.status.isSuccess()) { "KLIPY returned ${response.status.value}." }
        return KlipyResponseParser.parse(response.bodyAsText()).map { it.copy(shareQuery = cleanQuery) }
    }

    suspend fun registerShare(slug: String, customerId: String, query: String) {
        check(configured) { "KLIPY isn't configured for this build." }
        val response = http.post(
            KlipyRequestBuilder.shareUrl(BuildConfig.KLIPY_API_KEY, slug),
        ) {
            contentType(ContentType.Application.Json)
            setBody(KlipyRequestBuilder.shareBody(customerId, query))
        }
        check(response.status.isSuccess()) { "KLIPY share trigger returned ${response.status.value}." }
        KlipyResponseParser.requireActionSuccess(response.bodyAsText())
    }
}

/** Builds provider requests without ever logging or persisting the key-bearing URL. */
internal object KlipyRequestBuilder {
    private const val BaseUrl = "https://api.klipy.com/api/v1"
    private val CustomerIdPattern = Regex("[A-Za-z0-9_-]{8,128}")
    private val LocalePattern = Regex("[A-Za-z]{2}")
    private val SlugPattern = Regex("[A-Za-z0-9_-]{1,240}")

    fun searchUrl(
        apiKey: String,
        query: String,
        customerId: String,
        page: Int,
        perPage: Int,
        locale: String?,
    ): String {
        require(apiKey.isNotBlank()) { "KLIPY key is missing." }
        require(query.length <= 256) { "KLIPY query is too long." }
        require(CustomerIdPattern.matches(customerId)) { "KLIPY customer id is invalid." }
        require(page in 1..1_000) { "KLIPY page is out of range." }
        require(perPage in 8..50) { "KLIPY result count is out of range." }
        require(locale == null || LocalePattern.matches(locale)) { "KLIPY locale is invalid." }
        val action = if (query.isBlank()) "trending" else "search"
        return buildString {
            append(BaseUrl)
            append('/')
            append(encode(apiKey))
            append("/gifs/")
            append(action)
            append("?page=")
            append(page)
            append("&per_page=")
            append(perPage)
            append("&customer_id=")
            append(encode(customerId))
            locale?.lowercase(Locale.ROOT)?.let {
                append("&locale=")
                append(encode(it))
            }
            append("&content_filter=high")
            if (query.isNotBlank()) {
                append("&q=")
                append(encode(query))
            }
        }
    }

    fun shareUrl(apiKey: String, slug: String): String {
        require(apiKey.isNotBlank()) { "KLIPY key is missing." }
        require(validSlug(slug)) { "KLIPY slug is invalid." }
        return "$BaseUrl/${encode(apiKey)}/gifs/share/${encode(slug)}"
    }

    fun shareBody(customerId: String, query: String): String {
        require(CustomerIdPattern.matches(customerId)) { "KLIPY customer id is invalid." }
        require(query.length <= 256) { "KLIPY share query is too long." }
        return buildJsonObject {
            put("customer_id", customerId)
            put("q", query)
        }.toString()
    }

    fun deviceLocale(): String? = Locale.getDefault().country
        .takeIf(LocalePattern::matches)
        ?.lowercase(Locale.ROOT)

    fun validSlug(slug: String): Boolean = SlugPattern.matches(slug)

    private fun encode(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8.toString())
}

/** A provider-scoped, install-local UUID kept outside Android backup and Norfold sync. */
class KlipyCustomerIdentityStore(context: Context) {
    private val identityFile = File(context.noBackupFilesDir, "providers/klipy/customer_id")

    @Synchronized
    fun getOrCreate(): String {
        runCatching { identityFile.takeIf(File::isFile)?.readText()?.trim() }
            .getOrNull()
            ?.let { existing ->
                if (runCatching { UUID.fromString(existing) }.isSuccess) return existing
            }
        val generated = UUID.randomUUID().toString()
        val parent = requireNotNull(identityFile.parentFile)
        check(parent.isDirectory || parent.mkdirs()) { "KLIPY identity directory could not be created." }
        identityFile.writeText(generated)
        return generated
    }
}

internal object KlipyResponseParser {
    private val json = Json { ignoreUnknownKeys = true }

    fun parse(body: String): List<KlipyGif> {
        val root = json.parseToJsonElement(body).jsonObject
        check(root["result"]?.jsonPrimitive?.booleanOrNull == true) { "KLIPY response was unsuccessful." }
        return root["data"]?.jsonObject?.get("data")?.jsonArray.orEmpty().map { element ->
            val item = element.jsonObject
            val type = item["type"]?.jsonPrimitive?.contentOrNull
            check(type != "ad") {
                "KLIPY returned an unexpected ad result."
            }
            check(type == "gif") { "KLIPY returned an unexpected result type." }
            val file = checkNotNull(item["file"]?.jsonObject) { "KLIPY result has no media files." }
            val preview = rendition(file, "sm") ?: rendition(file, "xs")
            checkNotNull(preview) { "KLIPY result has no supported preview rendition." }
            val original = rendition(file, "md") ?: rendition(file, "hd") ?: preview
            val previewReference = ExternalMediaReference.validate(
                provider = ExternalMediaReference.KLIPY_PROVIDER,
                kind = ExternalMediaReference.GIF_KIND,
                url = preview.first,
                width = preview.second,
                height = preview.third,
            )
            check(previewReference is ExternalMediaReference.Result.Valid) {
                "KLIPY preview URL is outside the provider boundary."
            }
            val slug = item["slug"]?.jsonPrimitive?.contentOrNull?.trim()
                ?.takeIf(KlipyRequestBuilder::validSlug)
            checkNotNull(slug) { "KLIPY result has no valid share slug." }
            val reference = ExternalMediaReference.validate(
                provider = ExternalMediaReference.KLIPY_PROVIDER,
                kind = ExternalMediaReference.GIF_KIND,
                url = original.first,
                title = item["title"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                slug = slug,
                width = original.second,
                height = original.third,
            )
            check(reference is ExternalMediaReference.Result.Valid) {
                "KLIPY media URL is outside the provider boundary."
            }
            KlipyGif(
                slug = slug,
                title = item["title"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                previewUrl = previewReference.reference.url,
                originalUrl = original.first,
                width = original.second,
                height = original.third,
                reference = reference.reference,
            )
        }
    }

    fun requireActionSuccess(body: String) {
        val root = json.parseToJsonElement(body).jsonObject
        check(root["result"]?.jsonPrimitive?.booleanOrNull == true) { "KLIPY action was unsuccessful." }
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
}
