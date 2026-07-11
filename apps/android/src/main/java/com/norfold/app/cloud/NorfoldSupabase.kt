package com.norfold.app.cloud

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.Google
import io.github.jan.supabase.auth.providers.builtin.IDToken
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.functions.Functions
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.realtime.Realtime
import io.github.jan.supabase.storage.Storage

object NorfoldSupabase {
    val clientOrNull: SupabaseClient? by lazy {
        if (!ExternalServiceConfig.capabilities.supabase) return@lazy null
        createSupabaseClient(
            supabaseUrl = ExternalServiceConfig.supabaseUrl,
            supabaseKey = ExternalServiceConfig.supabasePublishableKey,
        ) {
            install(Auth)
            install(Postgrest)
            install(Realtime)
            install(Storage)
            install(Functions)
        }
    }

    val client: SupabaseClient
        get() = clientOrNull ?: error("Supabase is not configured for this build.")

    suspend fun signInWithGoogle(idToken: String, nonce: String) {
        ExternalServiceConfig.requireGoogleIdentity()
        client.auth.signInWith(IDToken) {
            this.idToken = idToken
            provider = Google
            this.nonce = nonce
        }
    }
}
