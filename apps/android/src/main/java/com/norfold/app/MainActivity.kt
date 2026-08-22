package com.norfold.app

import android.content.Intent
import android.util.Log
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModelProvider
import com.norfold.app.ui.NorfoldRoot
import com.norfold.app.ui.DocsViewModel
import com.norfold.app.cloud.NorfoldSupabase
import io.github.jan.supabase.auth.handleDeeplinks

class MainActivity : FragmentActivity() {
    private lateinit var docsViewModel: DocsViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        // Native splash covers cold start, then the Compose branded loader takes over.
        installSplashScreen()
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        docsViewModel = ViewModelProvider(this)[DocsViewModel::class.java]
        handleAuthDeepLink(intent)
        docsViewModel.handleTelegramReturn(intent)
        docsViewModel.handleNotificationIntent(intent)
        setContent { NorfoldRoot(docsViewModel) }
    }

    override fun onResume() {
        super.onResume()
        if (::docsViewModel.isInitialized) docsViewModel.onAppResumed()
    }

    override fun onPause() {
        if (::docsViewModel.isInitialized) docsViewModel.onAppPaused()
        super.onPause()
    }

    override fun onStart() {
        super.onStart()
        if (::docsViewModel.isInitialized) docsViewModel.onAppForegrounded()
    }

    override fun onStop() {
        // Rotation, locale changes, and other configuration recreation are not an app exit.
        // Treating them as backgrounding can unexpectedly lock the vault and start duplicate syncs.
        if (::docsViewModel.isInitialized && !isChangingConfigurations) {
            docsViewModel.onAppBackgrounded()
            docsViewModel.autoSyncIfPossible()
        }
        super.onStop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleAuthDeepLink(intent)
        if (::docsViewModel.isInitialized) {
            docsViewModel.handleTelegramReturn(intent)
            docsViewModel.handleNotificationIntent(intent)
        }
    }

    /**
     * Hands a `norfold://auth/...` return to Supabase, if one ever arrives.
     *
     * Nothing routes here today and that is deliberate rather than broken: the manifest
     * registers `norfold://integrations/calendar` and `norfold://verify/telegram` and no
     * `auth` host at all, email sign-in is verified with a 6-digit OTP, and Google goes
     * through CredentialManager — so no flow currently returns through the browser. The
     * host guard stays because `norfold://auth/callback` is still this project's declared
     * `site_url` in supabase/config.toml, which means a future provider or a magic-link
     * flow can start delivering here after only a manifest change. Deleting it would make
     * that change look complete while dropping every session on the floor.
     */
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
