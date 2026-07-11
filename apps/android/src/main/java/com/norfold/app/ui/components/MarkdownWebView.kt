package com.norfold.app.ui.components

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Color as AndroidColor
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import org.json.JSONObject

/**
 * Self-sizes to its content height so the containing Compose lazy list is the sole vertical
 * scroll owner. Code and table overflow remains horizontal inside the document.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun MarkdownPreview(markdown: String, dark: Boolean, accentHex: String, modifier: Modifier = Modifier) {
    var heightDp by remember { mutableStateOf(0) }
    val text = if (dark) "#ECE9F5" else "#211E33"
    val muted = if (dark) "#A9A4B8" else "#6B677A"
    val codeBg = if (dark) "#211E2E" else "#F1EEF8"
    val border = if (dark) "#373345" else "#E2DEEC"
    val html = buildHtml(markdown, text, muted, codeBg, border, accentHex)
    val htmlKey = remember(html) { html.hashCode() }
    AndroidView(
        modifier = modifier.then(if (heightDp > 0) Modifier.height(heightDp.dp) else Modifier.heightIn(min = 24.dp)),
        factory = { ctx ->
            lateinit var instance: WebView
            instance = WebView(ctx).apply {
                settings.javaScriptEnabled = true
                settings.allowFileAccess = true
                settings.allowContentAccess = true
                setBackgroundColor(AndroidColor.TRANSPARENT)
                isVerticalScrollBarEnabled = false
                isHorizontalScrollBarEnabled = false
                isNestedScrollingEnabled = false
                overScrollMode = View.OVER_SCROLL_NEVER
                addJavascriptInterface(
                    HeightBridge { measured ->
                        instance.post {
                            if (measured > 0 && measured != heightDp) heightDp = measured
                        }
                    },
                    HeightBridgeName,
                )
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String?) {
                        view.evaluateJavascript("window.norfoldMeasure && window.norfoldMeasure()", null)
                    }

                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                        val uri = request.url ?: return true
                        if (uri.scheme !in AllowedLinkSchemes) return true
                        return runCatching {
                            ctx.startActivity(
                                Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                            )
                            true
                        }.getOrDefault(true)
                    }
                }
            }
            instance
        },
        update = { web ->
            if (web.tag != htmlKey) {
                web.tag = htmlKey
                heightDp = 0
                web.loadDataWithBaseURL("file:///android_asset/preview/", html, "text/html", "utf-8", null)
            }
        },
    )
}

private class HeightBridge(private val onHeight: (Int) -> Unit) {
    @JavascriptInterface
    fun reportHeight(height: Double) {
        onHeight(kotlin.math.ceil(height).toInt())
    }
}

private const val HeightBridgeName = "NorfoldBridge"
private val AllowedLinkSchemes = setOf("https", "http", "mailto", "content")

private fun buildHtml(
    markdown: String,
    text: String,
    muted: String,
    codeBg: String,
    border: String,
    accent: String,
): String {
    val md = JSONObject.quote(markdown) // safe JS string literal (escapes quotes, backslashes, newlines)
    val d = "$" // literal dollar for math delimiters, kept out of Kotlin templating
    return """
<!DOCTYPE html><html><head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1, maximum-scale=1">
<style>
 html,body{margin:0;padding:0;background:transparent;color:$text;
   font-family:-apple-system,Roboto,'Segoe UI',system-ui,sans-serif;font-size:16px;line-height:1.62;
   -webkit-text-size-adjust:100%;word-wrap:break-word;overflow-wrap:anywhere;}
 body{padding:2px 2px 48px;}
 h1,h2,h3,h4{font-weight:800;line-height:1.25;margin:1.05em 0 .45em;}
 h1{font-size:1.7em} h2{font-size:1.4em} h3{font-size:1.18em}
 p{margin:.5em 0}
 a{color:$accent;text-decoration:none}
 code{background:$codeBg;padding:.14em .36em;border-radius:6px;font-family:'Roboto Mono',monospace;font-size:.88em}
 pre{background:$codeBg;padding:14px;border-radius:14px;overflow:auto}
 pre code{background:none;padding:0}
 blockquote{margin:.6em 0;padding:.35em .9em;border-left:4px solid $accent;background:${accent}18;border-radius:0 12px 12px 0;color:$muted}
 table{border-collapse:collapse;width:100%;margin:.7em 0;font-size:.94em;display:block;overflow-x:auto}
 th,td{border:1px solid $border;padding:7px 11px;text-align:left}
 th{background:$codeBg}
 img{max-width:100%;border-radius:12px}
 ul,ol{padding-left:1.35em;margin:.4em 0}
 li{margin:.2em 0}
 hr{border:none;border-top:1px solid $border;margin:1.2em 0}
 input[type=checkbox]{margin-right:.45em;vertical-align:middle}
 mjx-container{overflow-x:auto;overflow-y:hidden;max-width:100%}
</style>
<script>
 window.MathJax={
   tex:{inlineMath:[['$d','$d'],['\(','\)']],displayMath:[['$d$d','$d$d'],['\[','\]']]},
   svg:{fontCache:'global'},
   options:{skipHtmlTags:['script','noscript','style','textarea','pre','code']},
   startup:{typeset:false}
 };
</script>
<script src="marked.min.js"></script>
<script src="tex-svg.js"></script>
</head>
<body>
<div id="content"></div>
<script>
 var raw = $md;
 try { document.getElementById('content').innerHTML = marked.parse(raw, {gfm:true, breaks:true}); }
 catch(e){ document.getElementById('content').textContent = raw; }
 window.norfoldMeasure=function(){
   var height=Math.max(document.body.scrollHeight,document.documentElement.scrollHeight);
   if(window.$HeightBridgeName&&window.$HeightBridgeName.reportHeight){window.$HeightBridgeName.reportHeight(height);}
 };
 new ResizeObserver(function(){window.norfoldMeasure();}).observe(document.documentElement);
 Array.from(document.images).forEach(function(image){image.addEventListener('load',window.norfoldMeasure);image.addEventListener('error',window.norfoldMeasure);});
 if (window.MathJax && MathJax.typesetPromise) { MathJax.typesetPromise().finally(window.norfoldMeasure); }
 window.requestAnimationFrame(window.norfoldMeasure);
</script>
</body></html>
    """.trimIndent()
}
