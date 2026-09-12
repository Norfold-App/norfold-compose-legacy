package com.norfold.app.experiments.c3

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.activity.ComponentActivity

/**
 * FLOW-03 C3 Stage-1 host. Loads the bundled ProseMirror/Tiptap fixture editor
 * (real atomic nodes) into ONE persistent WebView. No reload on edits. Kotlin is
 * driven through a broadcast so adb can call model commands without tapping the
 * page: `am broadcast -a com.norfold.app.C3_EVAL --es js "C3.report()"`.
 * The JS side reports compact events (selection / step counts / composition),
 * never a whole-document JSON per keystroke.
 */
class C3WebViewActivity : ComponentActivity() {

    private lateinit var webView: WebView

    private val evalReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val js = intent?.getStringExtra("js") ?: return
            webView.post { webView.evaluateJavascript(js, null) }
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
    }

    companion object {
        const val TAG = "C3BRIDGE"
        const val ACTION_EVAL = "com.norfold.app.C3_EVAL"
    }
}
