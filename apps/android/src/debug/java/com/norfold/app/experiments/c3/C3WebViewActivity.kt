package com.norfold.app.experiments.c3

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
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
 * FLOW-03 C3 Stage-2A host. Loads the bundled ProseMirror/Tiptap fixture editor
 * (real atomic nodes) into ONE persistent WebView. No reload on edits. Kotlin is
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
    private var lastClipJson: String? = null

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

        webView.loadUrl("file:///android_asset/flow-c3/index.html")
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
            lastClipJson = jsonStr
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            // Primary: structured MIME with JSON payload
            val structuredItem = ClipData.Item(plainText)
            val clip = ClipData.newPlainText("norfold-structured", plainText)
            // Add the JSON as an additional HTML item if available
            if (jsonStr.isNotEmpty()) {
                clip.addItem(structuredItem)
            }
            cm.setPrimaryClip(clip)
            Log.i(TAG, "clipboard-copy: textLen=${plainText.length} jsonLen=${jsonStr.length}")
        }

        /**
         * Gate 7: Called from JS when user pastes. Reads from Android clipboard
         * and sends data back to JS via C3.receiveClipboardData().
         */
        @JavascriptInterface
        fun onClipboardPaste() {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = cm.primaryClip
            if (clip == null || clip.itemCount == 0) {
                webView.post {
                    webView.evaluateJavascript("C3.receiveClipboardData(null, null)", null)
                }
                return
            }
            val item = clip.getItemAt(0)
            val text = item.text?.toString() ?: ""
            // Try to recover structured data from lastClipJson if available
            val json = lastClipJson
            webView.post {
                val js = "C3.receiveClipboardData(${escapeJs(json)}, ${escapeJs(text)})"
                webView.evaluateJavascript(js, null)
            }
            Log.i(TAG, "clipboard-paste: textLen=${text.length} hasJson=${json != null}")
        }
    }

    companion object {
        const val TAG = "C3BRIDGE"
        const val ACTION_EVAL = "com.norfold.app.C3_EVAL"

        private fun escapeJs(s: String?): String {
            if (s == null) return "null"
            return "\"" + s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r") + "\""
        }
    }
}
