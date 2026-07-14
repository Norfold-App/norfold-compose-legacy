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
import androidx.compose.runtime.LaunchedEffect
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
fun MarkdownPreview(markdown: String, dark: Boolean, accentHex: String, modifier: Modifier = Modifier, refreshTick: Int = 0) {
    val colors = MaterialTheme.colorScheme
    val text = colors.onSurface.toCssHex()
    val muted = colors.onSurfaceVariant.toCssHex()
    val codeBg = colors.surfaceVariant.toCssHex()
    val border = colors.outlineVariant.toCssHex()
    val secondary = colors.secondary.toCssHex()
    val tertiary = colors.tertiary.toCssHex()
    val error = colors.error.toCssHex()
    val generation = RenderCache.generation
    val cacheKey = remember(markdown, dark, accentHex, text) { RenderCache.key(markdown, dark, accentHex, text) }
    val html = remember(cacheKey, muted, codeBg, border, secondary, tertiary, error) {
        RenderCache.html(cacheKey)
            ?: buildHtml(markdown, text, muted, codeBg, border, accentHex, secondary, tertiary, error, dark)
                .also { RenderCache.putHtml(cacheKey, it) }
    }
    // A cached height means this exact content already rendered at this size: mount at the final
    // height immediately instead of collapsing to the "Rendering…" min height.
    var heightDp by remember(cacheKey) { mutableStateOf(RenderCache.heightFor(cacheKey) ?: 0) }
    val htmlKey = remember(html, refreshTick, generation) { (html.hashCode() * 31 + refreshTick) * 31 + generation }
    LaunchedEffect(refreshTick, generation) {
        if (refreshTick > 0 || generation > 0) {
            // Forced re-render: drop the stale artifact but keep the entry populated so the
            // fresh measurement lands in the cache again.
            RenderCache.evict(cacheKey)
            RenderCache.putHtml(cacheKey, html)
        }
    }
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
                    web.post {
                        if (measured > 0 && measured != heightDp) heightDp = measured
                        if (measured > 0) RenderCache.storeHeight(cacheKey, measured)
                    }
                },
                HeightBridgeName,
            )
            if (web.tag != htmlKey) {
                web.tag = htmlKey
                if (RenderCache.heightFor(cacheKey) == null) heightDp = 0
                web.scrollTo(0, 0)
                web.loadDataWithBaseURL("file:///android_asset/preview/", html, "text/html", "utf-8", null)
            }
        },
    )
}

private fun Color.toCssHex(): String = "#%06X".format(toArgb() and 0x00FFFFFF)

/** True when this exact content+theme already has a measured render in [RenderCache]. */
fun markdownRenderCached(markdown: String, dark: Boolean, accentHex: String, textColor: Color): Boolean =
    RenderCache.heightFor(RenderCache.key(markdown, dark, accentHex, textColor.toCssHex())) != null

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
 mark{background:${accent}33;color:inherit;padding:.02em .22em;border-radius:4px}
 sub,sup{font-size:.75em;line-height:0}
 .engine-fallback{color:$muted;font-size:.9em;margin:.5em 0 .3em}
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
 dl{margin:.6em 0} dt{font-weight:700;margin-top:.5em} dd{margin:.15em 0 .15em 1.2em;color:$muted}
 .wikilink{border-bottom:1px dashed $accent}
 .norfold-toc{list-style:none;padding:10px 14px;margin:.7em 0;border:1px solid $border;border-radius:12px;background:$codeBg}
 .norfold-toc li{margin:.18em 0}
 abbr[title]{text-decoration:underline dotted;cursor:help}
 kbd{background:$codeBg;border:1px solid $border;border-bottom-width:2px;border-radius:6px;padding:.08em .4em;font-family:'Roboto Mono',monospace;font-size:.85em}
 details{border:1px solid $border;border-radius:10px;padding:.5em .8em;margin:.6em 0}
 summary{cursor:pointer;font-weight:600}
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
 function engineFallback(target,source,message){
   target.innerHTML='';
   var note=document.createElement('div');
   note.className='engine-fallback';
   note.textContent=message;
   var pre=document.createElement('pre');
   var code=document.createElement('code');
   code.textContent=source;
   pre.appendChild(code);
   target.appendChild(note);
   target.appendChild(pre);
 }
 function extractFootnotes(source){
   var definitions={};
   var body=source.replace(/^\[\^([^\]]+)\]:[ \t]*(.+(?:\n(?:[ \t]{2,}|\t).+)*)$/gm,function(match,id,text){
     definitions[id]=text.replace(/\n[ \t]+/g,' ').trim();
     return '';
   });
   var inlineIndex=0;
   body=body.replace(/\^\[([^\]]+)\]/g,function(match,text){
     inlineIndex+=1;
     var id='inline-'+inlineIndex;
     definitions[id]=text.trim();
     return '[^'+id+']';
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
 if(window.marked&&marked.use){
   marked.use({extensions:[
     {name:'highlight',level:'inline',
      start:function(src){return src.indexOf('==');},
      tokenizer:function(src){
        var m=/^==(?=\S)([\s\S]*?\S)==/.exec(src);
        if(m){return {type:'highlight',raw:m[0],tokens:this.lexer.inlineTokens(m[1])};}
      },
      renderer:function(token){return '<mark>'+this.parser.parseInline(token.tokens)+'</mark>';}},
     {name:'sup',level:'inline',
      start:function(src){return src.indexOf('^');},
      tokenizer:function(src){
        var m=/^\^([^\^\s]+?)\^/.exec(src);
        if(m){return {type:'sup',raw:m[0],tokens:this.lexer.inlineTokens(m[1])};}
      },
      renderer:function(token){return '<sup>'+this.parser.parseInline(token.tokens)+'</sup>';}},
     {name:'sub',level:'inline',
      start:function(src){return src.indexOf('~');},
      tokenizer:function(src){
        if(src.slice(0,2)==='~~'){return;}
        var m=/^~([^~\s]+?)~/.exec(src);
        if(m){return {type:'sub',raw:m[0],tokens:this.lexer.inlineTokens(m[1])};}
      },
      renderer:function(token){return '<sub>'+this.parser.parseInline(token.tokens)+'</sub>';}},
     {name:'wikilink',level:'inline',
      start:function(src){return src.indexOf('[[');},
      tokenizer:function(src){
        var m=/^\[\[([^\]|]+)(?:\|([^\]]+))?\]\]/.exec(src);
        if(m){return {type:'wikilink',raw:m[0],page:m[1].trim(),alias:(m[2]||m[1]).trim()};}
      },
      renderer:function(token){
        return '<a class="wikilink" href="norfold://page/'+encodeURIComponent(token.page)+'">'+escapeHtml(token.alias)+'</a>';
      }},
     {name:'obsidianComment',level:'inline',
      start:function(src){return src.indexOf('%%');},
      tokenizer:function(src){
        var m=/^%%[\s\S]*?%%/.exec(src);
        if(m){return {type:'obsidianComment',raw:m[0]};}
      },
      renderer:function(){return '';}},
     {name:'obsidianCommentBlock',level:'block',
      start:function(src){return src.indexOf('%%');},
      tokenizer:function(src){
        var m=/^%%[\s\S]*?%%(?:\n+|$)/.exec(src);
        if(m){return {type:'obsidianCommentBlock',raw:m[0]};}
      },
      renderer:function(){return '';}},
     {name:'defList',level:'block',
      start:function(src){var m=src.match(/[^\n]\n:[ \t]/);return m?m.index:undefined;},
      tokenizer:function(src){
        var m=/^(?![-*+>#\s:])([^\n]+)\n((?::[ \t][^\n]*(?:\n|$))+)/.exec(src);
        if(m){
          var lexer=this.lexer;
          var defs=m[2].split('\n').filter(function(l){return l.trim();}).map(function(l){
            return lexer.inlineTokens(l.replace(/^:[ \t]+/,''));
          });
          return {type:'defList',raw:m[0],term:lexer.inlineTokens(m[1].trim()),defs:defs};
        }
      },
      renderer:function(token){
        var parser=this.parser;
        return '<dl><dt>'+parser.parseInline(token.term)+'</dt>'+
          token.defs.map(function(d){return '<dd>'+parser.parseInline(d)+'</dd>';}).join('')+'</dl>';
      }}
   ]});
 }
 var allowedTags={A:1,ABBR:1,B:1,BLOCKQUOTE:1,BR:1,CODE:1,DD:1,DEL:1,DETAILS:1,DIV:1,DL:1,DT:1,EM:1,
   H1:1,H2:1,H3:1,H4:1,H5:1,H6:1,HR:1,I:1,IMG:1,INPUT:1,KBD:1,LI:1,MARK:1,OL:1,P:1,PRE:1,S:1,
   SECTION:1,SPAN:1,STRONG:1,SUB:1,SUMMARY:1,SUP:1,TABLE:1,TBODY:1,TD:1,TH:1,THEAD:1,TR:1,U:1,UL:1};
 function sanitizeContent(root){
   Array.prototype.slice.call(root.querySelectorAll('*')).forEach(function(el){
     var tag=el.tagName;
     if(tag==='SCRIPT'||tag==='STYLE'||tag==='IFRAME'||tag==='OBJECT'||tag==='EMBED'||tag==='FORM'||tag==='LINK'||tag==='META'){
       el.remove();return;
     }
     if(!allowedTags[tag]){
       var parent=el.parentNode;
       if(!parent){return;}
       while(el.firstChild){parent.insertBefore(el.firstChild,el);}
       parent.removeChild(el);
       return;
     }
     Array.prototype.slice.call(el.attributes).forEach(function(attr){
       var name=attr.name.toLowerCase();
       if(name.indexOf('on')===0){el.removeAttribute(attr.name);return;}
       if((name==='href'||name==='src')&&/^\s*javascript:/i.test(attr.value)){el.removeAttribute(attr.name);}
     });
     if(tag==='INPUT'&&el.type!=='checkbox'){el.remove();}
   });
 }
 function injectTableOfContents(root){
   var markers=Array.prototype.filter.call(root.querySelectorAll('p'),function(p){
     return p.textContent.trim().toUpperCase()==='[TOC]';
   });
   if(!markers.length){return;}
   var headings=root.querySelectorAll('h1,h2,h3,h4,h5,h6');
   markers.forEach(function(marker){
     if(!headings.length){
       var empty=document.createElement('div');
       empty.className='engine-fallback';
       empty.textContent='No headings yet.';
       marker.replaceWith(empty);
       return;
     }
     var list=document.createElement('ul');
     list.className='norfold-toc';
     Array.prototype.forEach.call(headings,function(heading,index){
       if(!heading.id){heading.id='norfold-heading-'+index;}
       var item=document.createElement('li');
       item.style.marginLeft=((parseInt(heading.tagName.slice(1),10)-1)*14)+'px';
       var link=document.createElement('a');
       link.href='#'+heading.id;
       link.textContent=heading.textContent;
       item.appendChild(link);
       list.appendChild(item);
     });
     marker.replaceWith(list);
   });
 }
 function applyAbbreviations(root,defs){
   var names=Object.keys(defs);
   if(!names.length){return;}
   var pattern=new RegExp('\\b('+names.map(function(n){
     return n.replace(/[.*+?^$&{}()|[\]\\]/g,'\\$&');
   }).join('|')+')\\b','g');
   var walker=document.createTreeWalker(root,NodeFilter.SHOW_TEXT,{
     acceptNode:function(node){
       var parent=node.parentElement;
       return parent&&parent.closest('pre,code,script,style,textarea,abbr')
         ? NodeFilter.FILTER_REJECT
         : NodeFilter.FILTER_ACCEPT;
     }
   });
   var nodes=[];
   while(walker.nextNode()){nodes.push(walker.currentNode);}
   nodes.forEach(function(node){
     var value=node.nodeValue;
     pattern.lastIndex=0;
     if(!pattern.test(value)){return;}
     pattern.lastIndex=0;
     var frag=document.createDocumentFragment();
     var cursor=0;
     var match;
     while((match=pattern.exec(value))){
       if(match.index>cursor){frag.appendChild(document.createTextNode(value.slice(cursor,match.index)));}
       var abbr=document.createElement('abbr');
       abbr.title=defs[match[1]];
       abbr.textContent=match[1];
       frag.appendChild(abbr);
       cursor=match.index+match[1].length;
     }
     if(cursor<value.length){frag.appendChild(document.createTextNode(value.slice(cursor)));}
     node.parentNode.replaceChild(frag,node);
   });
 }
 try {
   var abbrDefinitions={};
   var withoutAbbr=raw.replace(/^\*\[([^\]]+)\]:[ \t]*(.+)$/gm,function(match,name,def){
     abbrDefinitions[name]=def.trim();
     return '';
   });
   var documentSource=extractFootnotes(withoutAbbr);
   var contentRoot=document.getElementById('content');
   contentRoot.innerHTML=marked.parse(documentSource.body,{gfm:true,breaks:true})+documentSource.footer;
   sanitizeContent(contentRoot);
   injectTableOfContents(contentRoot);
   applyAbbreviations(contentRoot,abbrDefinitions);
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
   if(!window.mermaid){
     mermaidBlocks.forEach(function(diagram){
       engineFallback(diagram,diagram.dataset.source||'','Couldn\'t render this diagram — showing its source.');
     });
     return null;
   }
   mermaid.initialize({startOnLoad:false,securityLevel:'strict',theme:'${if (dark) "dark" else "default"}'});
   return Promise.all(mermaidBlocks.map(function(diagram,index){
     var source=diagram.dataset.source||'';
     return mermaid.render('norfold-mermaid-'+index+'-'+Date.now(),source).then(function(result){
       diagram.innerHTML=result.svg;
       if(result.bindFunctions){result.bindFunctions(diagram);}
     }).catch(function(){
       engineFallback(diagram,source,'Couldn\'t render this diagram — showing its source.');
     });
   }));
 });
 var charts=emojiReady.then(function(){
   if(!window.vegaEmbed){
     chartBlocks.forEach(function(chart){
       engineFallback(chart,chart.dataset.spec||'','Couldn\'t render this chart — showing its source.');
     });
     return null;
   }
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
         engineFallback(chart,source,'Couldn\'t render this chart — showing its source.');
       });
     } catch(e) {
       engineFallback(chart,source,'Couldn\'t render this chart — showing its source.');
       return null;
     }
   }));
 });
 Promise.all([diagrams,charts]).then(function(){
   if(window.MathJax&&MathJax.typesetPromise){
     return MathJax.typesetPromise().catch(function(){
       var note=document.createElement('div');
       note.className='engine-fallback';
       note.textContent='Couldn\'t typeset the math on this page — showing raw TeX.';
       document.getElementById('content').prepend(note);
     });
   }
 }).finally(window.norfoldMeasure);
 window.requestAnimationFrame(window.norfoldMeasure);
</script>
</body></html>
    """.trimIndent()
}
