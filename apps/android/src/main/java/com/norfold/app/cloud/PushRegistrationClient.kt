package com.norfold.app.cloud

import android.content.Context
import com.google.firebase.messaging.FirebaseMessaging
import com.norfold.app.data.InstallationIdentityStore
import io.github.jan.supabase.auth.auth
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Registers the current FCM token explicitly after sign-in as well as after token rotation. */
class PushRegistrationClient(context: Context) {
    private val appContext = context.applicationContext
    private val identity = InstallationIdentityStore(appContext)

    suspend fun registerCurrentToken() {
        if (!ExternalServiceConfig.capabilities.pushNotifications || !NorfoldSupabase.isSignedIn()) return
        registerToken(currentFirebaseToken(), enabled = true)
    }

    suspend fun disableCurrentToken() {
        if (!ExternalServiceConfig.capabilities.pushNotifications || !NorfoldSupabase.isSignedIn()) return
        registerToken(currentFirebaseToken(), enabled = false)
    }

    suspend fun registerToken(token: String, enabled: Boolean) = withContext(Dispatchers.IO) {
        val accessToken = NorfoldSupabase.clientOrNull?.auth?.currentAccessTokenOrNull()
            ?: error("Sign in before registering notifications.")
        val connection = (URL("${ExternalServiceConfig.supabaseUrl}/functions/v1/notification-token").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 15_000
            doOutput = true
            setRequestProperty("authorization", "Bearer $accessToken")
            setRequestProperty("apikey", ExternalServiceConfig.supabasePublishableKey)
            setRequestProperty("content-type", "application/json")
        }
        try {
            val payload = JSONObject()
                .put("token", token)
                .put("deviceId", identity.installationId)
                .put("deviceName", identity.suggestedDeviceName)
                .put("enabled", enabled)
                .toString()
            connection.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            if (code !in 200..299) {
                val response = connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                val reason = runCatching { JSONObject(response).optString("error") }.getOrNull().orEmpty()
                throw IOException("Push-token registration failed ($code)${reason.takeIf(String::isNotBlank)?.let { ": $it" }.orEmpty()}")
            }
            identity.bindAccount(NorfoldSupabase.currentAccountId())
        } finally {
            connection.disconnect()
        }
    }

    private suspend fun currentFirebaseToken(): String = suspendCancellableCoroutine { continuation ->
        FirebaseMessaging.getInstance().token
            .addOnSuccessListener { token ->
                if (token.isBlank()) continuation.resumeWithException(IllegalStateException("Firebase returned an empty notification token."))
                else continuation.resume(token)
            }
            .addOnFailureListener(continuation::resumeWithException)
    }
}
