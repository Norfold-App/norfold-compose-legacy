package com.norfold.app.cloud

import com.norfold.app.BuildConfig

data class ServiceCapabilities(
    val supabase: Boolean,
    val googleIdentity: Boolean,
    val googleDrive: Boolean,
    val pushNotifications: Boolean,
) {
    val anyCloud: Boolean get() = supabase || googleDrive || pushNotifications
}

object ExternalServiceConfig {
    val supabaseUrl: String get() = BuildConfig.SUPABASE_URL.trim().removeSuffix("/")
    val supabasePublishableKey: String get() = BuildConfig.SUPABASE_PUBLISHABLE_KEY.trim()
    val googleAndroidClientId: String get() = BuildConfig.GOOGLE_ANDROID_CLIENT_ID.trim()
    val googleServerClientId: String get() = BuildConfig.GOOGLE_SERVER_CLIENT_ID.trim()
    val googleCloudProjectId: String get() = BuildConfig.GOOGLE_CLOUD_PROJECT_ID.trim()
    val firebaseProjectId: String get() = BuildConfig.FIREBASE_PROJECT_ID.trim()

    val capabilities: ServiceCapabilities
        get() {
            val supportedUrl = supabaseUrl.startsWith("https://") || (BuildConfig.DEBUG && supabaseUrl.startsWith("http://"))
            val supabaseReady = supportedUrl && supabasePublishableKey.isNotBlank()
            val androidClientReady = googleAndroidClientId.endsWith(".apps.googleusercontent.com")
            val serverClientReady = googleServerClientId.endsWith(".apps.googleusercontent.com")
            return ServiceCapabilities(
                supabase = supabaseReady,
                googleIdentity = supabaseReady && androidClientReady && serverClientReady,
                googleDrive = androidClientReady && googleCloudProjectId.isNotBlank(),
                pushNotifications = supabaseReady && firebaseProjectId.isNotBlank(),
            )
        }

    fun requireSupabase() {
        check(capabilities.supabase) {
            "Supabase is not configured. Add SUPABASE_URL and SUPABASE_PUBLISHABLE_KEY to norfold.properties."
        }
    }

    fun requireGoogleIdentity() {
        check(capabilities.googleIdentity) {
            "Google identity is not configured. Add GOOGLE_SERVER_CLIENT_ID and Supabase public configuration."
        }
    }
}
