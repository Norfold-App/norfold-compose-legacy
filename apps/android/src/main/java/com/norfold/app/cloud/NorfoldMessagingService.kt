package com.norfold.app.cloud

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.norfold.app.MainActivity
import com.norfold.app.R
import io.github.jan.supabase.auth.auth
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONObject

class NorfoldMessagingService : FirebaseMessagingService() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onNewToken(token: String) {
        if (!ExternalServiceConfig.capabilities.pushNotifications) return
        serviceScope.launch { registerToken(token) }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val title = message.notification?.title ?: message.data["title"] ?: "Norfold"
        val body = message.notification?.body ?: message.data["body"] ?: return
        showNotification(title, body)
    }

    private fun registerToken(token: String) {
        val accessToken = NorfoldSupabase.clientOrNull?.auth?.currentAccessTokenOrNull() ?: return
        val preferences = getSharedPreferences(DevicePreferences, MODE_PRIVATE)
        val deviceId = preferences.getString(DeviceIdKey, null) ?: UUID.randomUUID().toString().also {
            preferences.edit().putString(DeviceIdKey, it).apply()
        }
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
                .put("deviceId", deviceId)
                .put("deviceName", "${Build.MANUFACTURER} ${Build.MODEL}".trim())
                .put("enabled", true)
                .toString()
            connection.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
            check(connection.responseCode in 200..299) { "Push-token registration failed (${connection.responseCode})" }
        } finally {
            connection.disconnect()
        }
    }

    private fun showNotification(title: String, body: String) {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(ChannelId, "Workspace reminders", NotificationManager.IMPORTANCE_DEFAULT))
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        manager.notify(
            body.hashCode(),
            NotificationCompat.Builder(this, ChannelId)
                .setSmallIcon(R.drawable.ic_norfold_notification)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .build(),
        )
    }

    private companion object {
        const val ChannelId = "norfold_workspace"
        const val DevicePreferences = "norfold_device"
        const val DeviceIdKey = "sync_device_id"
    }
}
