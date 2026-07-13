package com.norfold.app.ui.components

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Color as AndroidColor
import android.view.MotionEvent
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import org.json.JSONObject
import kotlin.math.abs

/**
 * Self-sizes to its content height so the containing Compose lazy list is the sole vertical
 * scroll owner. Code and table overflow remains horizontal inside the document.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun MarkdownPreview(markdown: String, dark: Boolean, accentHex: String, modifier: Modifier = Modifier) {
    var heightDp by remember { mutableStateOf(0) }
    val colors = MaterialTheme.colorScheme
    val text = colors.onSurface.toCssHex()
    val muted = colors.onSurfaceVariant.toCssHex()
    val codeBg = colors.surfaceVariant.toCssHex()
    val border = colors.outlineVariant.toCssHex()
    val secondary = colors.secondary.toCssHex()
    val tertiary = colors.tertiary.toCssHex()
    val error = colors.error.toCssHex()
    val html = buildHtml(markdown, text, muted, codeBg, border, accentHex, secondary, tertiary, error, dark)
    val htmlKey = remember(html) { html.hashCode() }
    AndroidView(
        modifier = modifier.then(if (heightDp > 0) Modifier.height(heightDp.dp) else Modifier.heightIn(min = 24.dp)),
        factory = { ctx ->
            WebView(ctx).apply {
                var gestureStartX = 0f
                var gestureStartY = 0f
                settings.javaScriptEnabled = true
                settings.allowFileAccess = true
                settings.allowContentAccess = true
                setBackgroundColor(AndroidColor.TRANSPARENT)
                isVerticalScrollBarEnabled = false
                isHorizontalScrollBarEnabled = false
                isNestedScrollingEnabled = false
                overScrollMode = View.OVER_SCROLL_NEVER
                setOnTouchListener { view, event ->
                    when (event.actionMasked) {
                        MotionEvent.ACTION_DOWN -> {
                            gestureStartX = event.x
                            gestureStartY = event.y
                            view.parent?.requestDisallowInterceptTouchEvent(true)
                        }
                        MotionEvent.ACTION_MOVE -> {
                            val dx = abs(event.x - gestureStartX)
                            val dy = abs(event.y - gestureStartY)
                            if (dy > dx && dy > 8f) {
                                view.parent?.requestDisallowInterceptTouchEvent(false)
                            }
                        }
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                            view.parent?.requestDisallowInterceptTouchEvent(false)
                    }
                    false
                }
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
        },
        onReset = { web ->
            web.stopLoading()
            web.removeJavascriptInterface(HeightBridgeName)
            web.tag = null
            web.scrollTo(0, 0)
            web.loadUrl("about:blank")
        },
        onRelease = { web ->
            web.stopLoading()
            web.removeJavascriptInterface(HeightBridgeName)
            web.destroy()
        },
        update = { web ->
            web.removeJavascriptInterface(HeightBridgeName)
            web.addJavascriptInterface(
                HeightBridge { measured ->
                    web.post { if (measured > 0 && measured != heightDp) heightDp = measured }
                },
                HeightBridgeName,
            )
            if (web.tag != htmlKey) {
                web.tag = htmlKey
                heightDp = 0
                web.scrollTo(0, 0)
                web.loadDataWithBaseURL("file:///android_asset/preview/", html, "text/html", "utf-8", null)
            }
        },
    )
}

private fun Color.toCssHex(): String = "#%06X".format(toArgb() and 0x00FFFFFF)

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
    secondary: String,
    tertiary: String,
    error: String,
    dark: Boolean,
): String {
    val md = JSONObject.quote(markdown) // safe JS string literal (escapes quotes, backslashes, newlines)
    val d = "$" // literal dollar for math delimiters, kept out of Kotlin templating
    val needsMermaid = markdown.contains("```mermaid", ignoreCase = true)
    val needsChart = Regex("```(?:vega-lite|vegalite|vega)\\b", RegexOption.IGNORE_CASE).containsMatchIn(markdown)
    val needsMath = markdownNeedsMath(markdown)
    return """
<!DOCTYPE html><html><head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1, maximum-scale=1">
<style>
 html,body{margin:0;padding:0;background:transparent;color:$text;
   font-family:-apple-system,Roboto,'Segoe UI',system-ui,sans-serif;font-size:16px;line-height:1.62;
   -webkit-text-size-adjust:100%;word-wrap:break-word;overflow-wrap:anywhere;}
 body{padding:2px;}
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
 .mermaid{margin:.8em 0;padding:12px;border:1px solid $border;border-radius:12px;overflow-x:auto;text-align:center;background:$codeBg}
 .vega-chart{margin:.8em 0;padding:12px;border:1px solid $border;border-radius:12px;overflow-x:auto;background:$codeBg;min-height:260px;width:100%;max-width:100%;box-sizing:border-box}
 .vega-chart.vega-embed,.vega-chart .vega-embed{width:100%;min-width:0}
 .vega-chart .vega-embed summary{color:$muted}
 .footnotes{margin-top:1.4em;padding-top:.7em;border-top:1px solid $border;color:$muted;font-size:.9em}
 .footnotes ol{padding-left:1.35em}.footnote-backref{margin-left:.35em}
</style>
<script>
 window.MathJax={
   tex:{
     inlineMath:[['$d','$d'],['\\(','\\)']],
     displayMath:[['$d$d','$d$d'],['\\[','\\]']],
     processEscapes:true,
     processEnvironments:true
   },
   svg:{fontCache:'global'},
   options:{skipHtmlTags:['script','noscript','style','textarea','pre','code']},
   startup:{typeset:false}
 };
</script>
<script src="marked.min.js"></script>
${if (needsMermaid) "<script src=\"mermaid.min.js\"></script>" else ""}
${if (needsMath) "<script src=\"tex-svg.js\"></script>" else ""}
${if (needsChart) "<script src=\"vega.min.js\"></script><script src=\"vega-lite.min.js\"></script><script src=\"vega-embed.min.js\"></script>" else ""}
</head>
<body>
<div id="content"></div>
<script>
 var raw = $md;
 function escapeHtml(value){
   return value.replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;').replace(/"/g,'&quot;');
 }
 function extractFootnotes(source){
   var definitions={};
   var body=source.replace(/^\[\^([^\]]+)\]:[ \t]*(.+(?:\n(?:[ \t]{2,}|\t).+)*)$/gm,function(match,id,text){
     definitions[id]=text.replace(/\n[ \t]+/g,' ').trim();
     return '';
   });
   var order=[];
   body=body.replace(/\[\^([^\]]+)\]/g,function(match,id){
     if(!Object.prototype.hasOwnProperty.call(definitions,id)){return match;}
     if(order.indexOf(id)<0){order.push(id);}
     var number=order.indexOf(id)+1;
     return '<sup id="fnref-'+escapeHtml(id)+'"><a href="#fn-'+escapeHtml(id)+'">'+number+'</a></sup>';
   });
   var footer='';
   if(order.length){
     footer='<section class="footnotes"><ol>'+order.map(function(id){
       return '<li id="fn-'+escapeHtml(id)+'">'+marked.parseInline(definitions[id])+
         '<a class="footnote-backref" href="#fnref-'+escapeHtml(id)+'">&#8617;</a></li>';
     }).join('')+'</ol></section>';
   }
   return {body:body,footer:footer};
 }
 function expandEmojiShortcodes(root,emojiMap){
   var walker=document.createTreeWalker(root,NodeFilter.SHOW_TEXT,{
     acceptNode:function(node){
       var parent=node.parentElement;
       return parent&&parent.closest('pre,code,script,style,textarea')
         ? NodeFilter.FILTER_REJECT
         : NodeFilter.FILTER_ACCEPT;
     }
   });
   var nodes=[];
   while(walker.nextNode()){nodes.push(walker.currentNode);}
   nodes.forEach(function(node){
     node.nodeValue=node.nodeValue.replace(/:([a-z0-9_+\-]+):/gi,function(match,alias){
       return emojiMap[alias]||match;
     });
   });
 }
 try {
   var documentSource=extractFootnotes(raw);
   document.getElementById('content').innerHTML=marked.parse(documentSource.body,{gfm:true,breaks:true})+documentSource.footer;
 }
 catch(e){ document.getElementById('content').textContent = raw; }
 var mermaidBlocks=[];
 document.querySelectorAll('pre code.language-mermaid').forEach(function(code){
   var diagram=document.createElement('div');
   diagram.className='mermaid';
   diagram.dataset.source=code.textContent;
   diagram.textContent='Rendering diagram…';
   code.parentElement.replaceWith(diagram);
   mermaidBlocks.push(diagram);
 });
 var chartBlocks=[];
 document.querySelectorAll('pre code.language-vega-lite,pre code.language-vegalite,pre code.language-vega').forEach(function(code){
   var chart=document.createElement('div');
   chart.className='vega-chart';
   chart.dataset.spec=code.textContent;
   chart.textContent='Rendering chart…';
   code.parentElement.replaceWith(chart);
   chartBlocks.push(chart);
 });
 window.norfoldMeasure=function(){
   var root=document.getElementById('content');
   if(!root){return;}
   var bounds=root.getBoundingClientRect();
   var height=Math.max(24,Math.ceil(root.scrollHeight),Math.ceil(bounds.height))+20;
   if(window.$HeightBridgeName&&window.$HeightBridgeName.reportHeight){window.$HeightBridgeName.reportHeight(height);}
 };
 new ResizeObserver(function(){window.norfoldMeasure();}).observe(document.getElementById('content'));
 Array.from(document.images).forEach(function(image){image.addEventListener('load',window.norfoldMeasure);image.addEventListener('error',window.norfoldMeasure);});
 var emojiReady=fetch('emoji-shortcodes.json').then(function(response){
   if(!response.ok){throw new Error('emoji map unavailable');}
   return response.json();
 }).then(function(emojiMap){
   expandEmojiShortcodes(document.getElementById('content'),emojiMap);
 }).catch(function(){return null;});
 var diagrams=emojiReady.then(function(){
   if(!window.mermaid){return null;}
   mermaid.initialize({startOnLoad:false,securityLevel:'strict',theme:'${if (dark) "dark" else "default"}'});
   return Promise.all(mermaidBlocks.map(function(diagram,index){
     var source=diagram.dataset.source||'';
     return mermaid.render('norfold-mermaid-'+index+'-'+Date.now(),source).then(function(result){
       diagram.innerHTML=result.svg;
       if(result.bindFunctions){result.bindFunctions(diagram);}
     }).catch(function(){
       var fallback=document.createElement('pre');
       var code=document.createElement('code');
       code.textContent=source;
       fallback.appendChild(code);
       diagram.replaceWith(fallback);
     });
   }));
 });
 var charts=emojiReady.then(function(){
   if(!window.vegaEmbed){return null;}
   return Promise.all(chartBlocks.map(function(chart){
     var source=chart.dataset.spec||'{}';
     try {
       var spec=JSON.parse(source);
       var existingConfig=spec.config||{};
       var mark=typeof spec.mark==='string'?{type:spec.mark}:Object.assign({},spec.mark||{});
       if(mark.type==='line'||mark.type==='area'){
         mark.point=Object.assign({filled:true,size:72,strokeWidth:2},typeof mark.point==='object'?mark.point:{});
         mark.strokeWidth=mark.strokeWidth||3;
       } else if(mark.type==='point') {
         mark.filled=mark.filled!==false;
         mark.size=mark.size||96;
         mark.strokeWidth=mark.strokeWidth||2;
       } else if(mark.type==='bar') {
         mark.cornerRadiusEnd=mark.cornerRadiusEnd||7;
       }
       spec.mark=mark;
       spec.background=null;
       var hasExplicitWidth=spec.width!==undefined&&spec.width!==null;
       if(!hasExplicitWidth){
         spec.width=Math.max(180,Math.floor(chart.clientWidth-24));
         spec.autosize={type:'fit',contains:'padding',resize:true};
       }else{
         spec.autosize=spec.autosize||{type:'pad',contains:'padding',resize:true};
       }
       spec.height=spec.height||280;
       spec.padding=spec.padding||{left:8,right:14,top:12,bottom:8};
       spec.config=Object.assign({},existingConfig,{
         axis:Object.assign({},existingConfig.axis||{}, {
           domainColor:'$muted',domainWidth:1,tickColor:'$border',tickWidth:1,
           gridColor:'$border',gridOpacity:.55,labelColor:'$text',labelFontSize:12,
           labelPadding:7,titleColor:'$text',titleFontSize:13,titleFontWeight:600,titlePadding:12
         }),
         legend:Object.assign({},existingConfig.legend||{}, {
           labelColor:'$text',titleColor:'$text',labelFontSize:12,titleFontSize:13,
           symbolStrokeColor:'$border',padding:8
         }),
         title:Object.assign({},existingConfig.title||{}, {
           color:'$text',fontSize:18,fontWeight:700,anchor:'start',offset:16
         }),
         view:Object.assign({},existingConfig.view||{}, {stroke:'$border',strokeOpacity:.7,cornerRadius:10}),
         range:Object.assign({},existingConfig.range||{}, {category:['$accent','$secondary','$tertiary','$error']})
       });
       chart.textContent='';
       return vegaEmbed(chart,spec,{actions:false,renderer:'svg'}).catch(function(){
         chart.textContent=source;
       });
     } catch(e) {
       chart.textContent=source;
       return null;
     }
   }));
 });
 Promise.all([diagrams,charts]).then(function(){
   if(window.MathJax&&MathJax.typesetPromise){return MathJax.typesetPromise();}
 }).finally(window.norfoldMeasure);
 window.requestAnimationFrame(window.norfoldMeasure);
</script>
</body></html>
    """.trimIndent()
}
