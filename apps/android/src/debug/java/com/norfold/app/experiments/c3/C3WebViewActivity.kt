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
import androidx.activity.ComponentActivity

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
 *   + plain text/HTML fallback via ClipboardManager.
 * - JS bridge exposes onClipboardCopy(json, text) and onClipboardPaste().
 */
class C3WebViewActivity : ComponentActivity() {

    private lateinit var webView: WebView
    private lateinit var clipboardManager: C3ClipboardManager

    private val evalReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val js = intent?.getStringExtra("js") ?: return
            webView.post {
                webView.evaluateJavascript(js) { result ->
                    Log.i(TAG, "eval result: $result")
                }
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        clipboardManager = C3ClipboardManager(this)
        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = true
            addJavascriptInterface(Bridge(), "Norfold")
            webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(cm: ConsoleMessage?): Boolean {
                    cm?.let { Log.i(TAG, "console: ${it.message()} [${it.sourceId()}:${it.lineNumber()}]") }
                    return true
                }
            }
        }
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
    }

    override fun onDestroy() {
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
        }

        /**
         * Gate 7: Called from JS when user copies via system copy.
         * Stores structured JSON in a private MIME ClipData + plain text fallback.
         */
        @JavascriptInterface
        fun onClipboardCopy(jsonStr: String, plainText: String) {
            clipboardManager.copy(jsonStr, plainText)
            Log.i(TAG, "clipboard-copy: textLen=${plainText.length} jsonLen=${jsonStr.length}")
        }

        /**
         * Gate 7: Called from JS when user pastes. Reads from Android clipboard
         * and sends data back to JS via C3.receiveClipboardData().
         */
        @JavascriptInterface
        fun onClipboardPaste() {
            val payload = clipboardManager.paste()
            webView.post {
                val js = "C3.receiveClipboardData(${escapeJs(payload.json)}, ${escapeJs(payload.plainText)})"
                webView.evaluateJavascript(js, null)
            }
            Log.i(TAG, "clipboard-paste: textLen=${payload.plainText?.length ?: 0} hasJson=${payload.json != null}")
        }
    }

    companion object {
        const val TAG = "C3BRIDGE"
        const val ACTION_EVAL = "com.norfold.app.C3_EVAL"
        const val EXTRA_DEBUG_HARNESS = "debug_harness"

        private fun escapeJs(s: String?): String {
            if (s == null) return "null"
            return "\"" + s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r") + "\""
        }
    }
}
