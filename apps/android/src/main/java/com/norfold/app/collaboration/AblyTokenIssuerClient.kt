package com.norfold.app.collaboration

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Ably token issuer scaffold. In production this is a Supabase Edge Function or server endpoint
 * that validates the caller's Supabase JWT, checks artifact authorization, and mints a
 * short-lived scoped Ably token with clientId = stable Norfold account id and capabilities
 * covering only authorized artifact namespaces.
 *
 * This class is the Android-side client of that issuer. It never holds an Ably API key.
 *
 * For benchmark rooms (bench:*) it returns the BENCHMARK_SHARED_SECRET from the environment
 * (never shipped in release builds — see AUTHORIZATION_CONTRACT.md).
 */
class AblyTokenIssuerClient(
    private val issuerBaseUrl: String,
    private val supabaseTokenProvider: suspend () -> String,
    private val deviceId: DeviceId,
    private val okHttp: okhttp3.OkHttpClient = CloudflareDurableObjectTransport.defaultOkHttp(),
) : CollabTokenProvider {

    override suspend fun freshToken(roomId: RoomId): String {
        if (roomId.isBenchmark) {
            // Benchmark only — never in release builds.
            return System.getenv("NORFOLD_ABLY_BENCH_TOKEN")
                ?: error("NORFOLD_ABLY_BENCH_TOKEN not set; benchmark token unavailable")
        }
        val supabaseJwt = supabaseTokenProvider()
        requireNotNull(roomId.artifactScope) { "Ably tokens require an artifact room" }
        val url = "$issuerBaseUrl/collaboration-token"
        val body = buildJsonObject { put("room", roomId.value); put("deviceId", deviceId.value) }.toString()
        val request = okhttp3.Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $supabaseJwt")
            .header("Content-Type", "application/json")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        return okHttp.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "Token issuer returned ${response.code}" }
            val responseBody = response.body.string()
            kotlinx.serialization.json.Json.parseToJsonElement(responseBody)
                .jsonObject["token"]?.jsonPrimitive?.content
                ?: error("Token issuer response missing 'token' field")
        }
    }
}

private val kotlinx.serialization.json.JsonElement.jsonObject
    get() = this as kotlinx.serialization.json.JsonObject
private val kotlinx.serialization.json.JsonElement.jsonPrimitive
    get() = this as kotlinx.serialization.json.JsonPrimitive
