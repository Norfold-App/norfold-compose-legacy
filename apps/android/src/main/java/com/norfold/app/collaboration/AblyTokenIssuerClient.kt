package com.norfold.app.collaboration

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

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
    private val okHttp: okhttp3.OkHttpClient = CloudflareDurableObjectTransport.defaultOkHttp(),
) : CollabTokenProvider {

    override suspend fun freshToken(roomId: RoomId): String {
        if (roomId.isBenchmark) {
            // Benchmark only — never in release builds.
            return System.getenv("NORFOLD_ABLY_BENCH_TOKEN")
                ?: error("NORFOLD_ABLY_BENCH_TOKEN not set; benchmark token unavailable")
        }
        val supabaseJwt = supabaseTokenProvider()
        val url = "$issuerBaseUrl/collab-token?room=${roomId.value}"
        val request = okhttp3.Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $supabaseJwt")
            .build()
        val response = okHttp.newCall(request).execute()
        check(response.isSuccessful) { "Token issuer returned ${response.code}" }
        val body = response.body?.string() ?: error("Empty token issuer response")
        // Issuer returns {"token":"<ably-token>"} or a raw token string.
        return if (body.trimStart().startsWith("{")) {
            kotlinx.serialization.json.Json.parseToJsonElement(body)
                .jsonObject["token"]?.jsonPrimitive?.content
                ?: error("Token issuer response missing 'token' field")
        } else {
            body.trim()
        }
    }
}

private val kotlinx.serialization.json.JsonElement.jsonObject
    get() = this as kotlinx.serialization.json.JsonObject
private val kotlinx.serialization.json.JsonElement.jsonPrimitive
    get() = this as kotlinx.serialization.json.JsonPrimitive
