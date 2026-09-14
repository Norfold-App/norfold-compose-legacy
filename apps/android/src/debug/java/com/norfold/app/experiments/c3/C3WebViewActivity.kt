package com.norfold.app.experiments.c3

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import java.util.UUID

/**
 * FLOW-03 C3 experiment host. Loads the owner-review document by default or the
 * engineering harness when [EXTRA_DEBUG_HARNESS] is true. Both use the same
 * bundled ProseMirror/Tiptap editor with real atomic nodes in one persistent WebView.
 * No reload on edits. Kotlin is
 * driven through a broadcast so adb can call model commands without tapping the
 * page: `am broadcast -a com.norfold.app.C3_EVAL --es js "C3.report()"`.
 *
 * Stage-2A additions:
 * - Android clipboard bridge (Gate 7): structured ClipData with private MIME type
 *   + plain text fallback via ClipboardManager.
 * - JS bridge exposes onClipboardCopy(json, text) and onClipboardPaste().
 */
class C3WebViewActivity : ComponentActivity() {

    private lateinit var webView: WebView
    private lateinit var clipboardManager: C3ClipboardManager
    private val generation = UUID.randomUUID().toString()
    private val sessionPreferences by lazy { getSharedPreferences("c3-session", MODE_PRIVATE) }
    private var active = false

    private val evalReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (!active || !::webView.isInitialized) return
            val js = intent?.getStringExtra("js") ?: return
            webView.post {
                if (!active) return@post
                webView.evaluateJavascript(js) { result ->
                    if (!active) return@evaluateJavascript
                    Log.i(TAG, "eval result generation=$generation result=$result")
                }
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        val onCreateStart = System.nanoTime()
        super.onCreate(savedInstanceState)
        active = true
        Log.i(LIFECYCLE_TAG, "onCreate generation=$generation")
        clipboardManager = C3ClipboardManager(this)
        webView = WebView(this)
        Log.i(LIFECYCLE_TAG, "webViewConstructed generation=$generation elapsedMs=${elapsedMs(onCreateStart)}")
        webView.apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = true
            addJavascriptInterface(Bridge(), "Norfold")
            webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                    Log.i(LIFECYCLE_TAG, "pageStarted generation=$generation elapsedMs=${elapsedMs(onCreateStart)} origin=${origin(url)}")
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    Log.i(LIFECYCLE_TAG, "pageFinished generation=$generation elapsedMs=${elapsedMs(onCreateStart)} origin=${origin(url)}")
                }
            }
            webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(cm: ConsoleMessage?): Boolean {
                    cm?.let { Log.i(TAG, "console: ${it.message()} [${it.sourceId()}:${it.lineNumber()}]") }
                    return true
                }
            }
        }
        Log.i(LIFECYCLE_TAG, "webViewConfigured generation=$generation elapsedMs=${elapsedMs(onCreateStart)}")
        setContentView(webView)

        val filter = IntentFilter(ACTION_EVAL)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(evalReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(evalReceiver, filter)
        }

        val page = if (intent.getBooleanExtra(EXTRA_DEBUG_HARNESS, false)) "index.html" else "review.html"
        webView.loadUrl("file:///android_asset/flow-c3/$page")
        Log.i(LIFECYCLE_TAG, "loadUrl generation=$generation elapsedMs=${elapsedMs(onCreateStart)} origin=file://")
        Log.i(LIFECYCLE_TAG, "onCreateReady generation=$generation elapsedMs=${elapsedMs(onCreateStart)}")
    }

    override fun onStart() { super.onStart(); Log.i(LIFECYCLE_TAG, "onStart generation=$generation") }
    override fun onResume() { super.onResume(); Log.i(LIFECYCLE_TAG, "onResume generation=$generation") }
    override fun onPause() { Log.i(LIFECYCLE_TAG, "onPause generation=$generation"); super.onPause() }
    override fun onStop() { Log.i(LIFECYCLE_TAG, "onStop generation=$generation"); super.onStop() }

    override fun onDestroy() {
        active = false
        Log.i(LIFECYCLE_TAG, "onDestroy generation=$generation")
        if (::webView.isInitialized) webView.post { webView.evaluateJavascript("window.C3?.destroy?.()", null) }
        try {
            unregisterReceiver(evalReceiver)
        } catch (_: IllegalArgumentException) {
            // not registered
        }
        webView.destroy()
        super.onDestroy()
    }

    inner class Bridge {
        @JavascriptInterface
        fun onEvent(json: String) {
            Log.i(TAG, json)
            if (json.contains("\"t\":\"stage2c-ready\"")) Log.i(LIFECYCLE_TAG, "ready generation=$generation")
        }

        @JavascriptInterface
        fun getGeneration(): String = generation

        @JavascriptInterface
        fun shouldRestoreSession(): Boolean = intent.getBooleanExtra(EXTRA_RESTORE_SESSION, false)

        @JavascriptInterface
        fun onSessionSnapshot(json: String): Boolean {
            val accepted = sessionPreferences.edit().putString(SESSION_KEY, json).commit()
            Log.i(TAG, "session-save generation=$generation bytes=${json.toByteArray().size} accepted=$accepted")
            return accepted
        }

        @JavascriptInterface
        fun getSessionSnapshot(): String? = sessionPreferences.getString(SESSION_KEY, null)

        @JavascriptInterface
        fun clearSessionSnapshot(): Boolean {
            val cleared = sessionPreferences.edit().remove(SESSION_KEY).commit()
            Log.i(TAG, "session-clear generation=$generation accepted=$cleared")
            return cleared
        }

        /**
         * Gate 7: Called from JS when user copies via system copy.
         * Stores structured JSON in a private MIME ClipData + plain text fallback.
         */
        @JavascriptInterface
        fun onClipboardCopy(jsonStr: String, plainText: String) {
            clipboardManager.copy(jsonStr, plainText)
            Log.i(TAG, "clipboard-copy generation=$generation source=private-uri-write privateMimeAdvertised=true privateMimeRead=false textLen=${plainText.length} jsonLen=${jsonStr.length}")
        }

        /**
         * Gate 7: Called from JS when user pastes. Reads from Android clipboard
         * and sends data back to JS via C3.receiveClipboardData().
         */
        @JavascriptInterface
        fun onClipboardPaste() {
            if (!active || !::webView.isInitialized) return
            val payload = clipboardManager.paste()
            webView.post {
                if (!active) return@post
                val js = "C3.receiveClipboardData(${escapeJs(payload.json)}, ${escapeJs(payload.plainText)})"
                webView.evaluateJavascript(js, null)
            }
            Log.i(TAG, "clipboard-paste generation=$generation source=${payload.source} privateMimeAdvertised=${payload.privateMimeAdvertised} privateMimeRead=${payload.privateMimeRead} textLen=${payload.plainText?.length ?: 0} jsonLen=${payload.json?.length ?: 0}")
        }
    }

    companion object {
        const val TAG = "C3BRIDGE"
        const val ACTION_EVAL = "com.norfold.app.C3_EVAL"
        const val EXTRA_DEBUG_HARNESS = "debug_harness"
        const val EXTRA_RESTORE_SESSION = "restore_session"
        const val LIFECYCLE_TAG = "C3LIFECYCLE"
        private const val SESSION_KEY = "snapshot"

        private fun escapeJs(s: String?): String {
            if (s == null) return "null"
            return "\"" + s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r") + "\""
        }
    }

    private fun elapsedMs(start: Long): Long = (System.nanoTime() - start) / 1_000_000

    private fun origin(url: String?): String = runCatching {
        android.net.Uri.parse(url).let { "${it.scheme}://${it.host ?: ""}" }
    }.getOrDefault("unknown")
}
