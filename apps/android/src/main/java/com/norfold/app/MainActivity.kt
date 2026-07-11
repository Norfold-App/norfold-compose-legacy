package com.norfold.app

import android.content.Intent
import android.util.Log
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.fragment.app.FragmentActivity
import com.norfold.app.ui.NorfoldRoot
import com.norfold.app.cloud.NorfoldSupabase
import io.github.jan.supabase.auth.handleDeeplinks

class MainActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // Native splash covers cold start, then the Compose branded loader takes over.
        installSplashScreen()
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        handleAuthDeepLink(intent)
        setContent { NorfoldRoot() }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleAuthDeepLink(intent)
    }

    private fun handleAuthDeepLink(intent: Intent) {
        if (intent.data?.host != "auth") return
        NorfoldSupabase.clientOrNull?.handleDeeplinks(
            intent,
            onSessionSuccess = { Log.i(LogTag, "Supabase session established") },
            onError = { error -> Log.e(LogTag, "Supabase auth callback failed", error) },
        )
    }

    private companion object {
        const val LogTag = "NorfoldAuth"
    }
}
