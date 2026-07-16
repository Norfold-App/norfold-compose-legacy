package com.norfold.app.cloud

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.Google
import io.github.jan.supabase.auth.providers.builtin.Email
import io.github.jan.supabase.auth.providers.builtin.IDToken
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.functions.Functions
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.realtime.Realtime
import io.github.jan.supabase.storage.Storage
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

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

    suspend fun signInWithEmail(email: String, password: String) {
        ExternalServiceConfig.requireSupabase()
        client.auth.signInWith(Email) {
            this.email = email
            this.password = password
        }
    }

    suspend fun signUpWithEmail(email: String, password: String) {
        ExternalServiceConfig.requireSupabase()
        client.auth.signUpWith(Email) {
            this.email = email
            this.password = password
        }
    }

    /** Returns false when the app is operating locally without an authenticated cloud account. */
    suspend fun claimProfileHandleIfSignedIn(handle: String, displayName: String): Boolean {
        val configuredClient = clientOrNull ?: return false
        if (configuredClient.auth.currentUserOrNull() == null) return false
        configuredClient.postgrest.rpc(
            function = "claim_profile_handle",
            parameters = buildJsonObject {
                put("p_handle", handle)
                put("p_display_name", displayName)
            },
        )
        return true
    }
}
