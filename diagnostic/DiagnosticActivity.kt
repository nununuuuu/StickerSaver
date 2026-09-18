package com.local.threadssticker

import android.annotation.SuppressLint
import android.os.Bundle
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

class DiagnosticActivity : ComponentActivity() {
    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                var log by remember { mutableStateOf("準備測試 DdYPqUdAXes\n") }
                var web by remember { mutableStateOf<WebView?>(null) }
                Column(Modifier.fillMaxSize().padding(16.dp)) {
                    Text("Sticker Saver · Threads Diagnostic", style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = {
                        log = "開始載入 Threads…\n"
                        web?.loadUrl(TEST_URL)
                    }, modifier = Modifier.fillMaxWidth()) { Text("測試 Threads") }
                    Spacer(Modifier.height(8.dp))
                    Text(log, Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()))
                    AndroidWebView(
                        onReady = { web = it },
                        onLog = { line -> log += line + "\n" }
                    )
                }
            }
        }
    }

    companion object {
        const val TEST_URL = "https://www.threads.com/@ethanzhang688/post/DdYPqUdAXes"
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun AndroidWebView(onReady: (WebView) -> Unit, onLog: (String) -> Unit) {
    androidx.compose.ui.viewinterop.AndroidView(
        modifier = Modifier.size(1.dp),
        factory = { context ->
            WebView(context).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.userAgentString = settings.userAgentString.replace("; wv", "")
                webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): android.webkit.WebResourceResponse? {
                        val u = request?.url?.toString().orEmpty()
                        val low = u.lowercase()
                        if (listOf("giphy","gif","webp","fbcdn","cdninstagram","scontent","image_versions","video_versions").any { low.contains(it) }) {
                            view?.post { onLog("NET  " + u.take(300)) }
                        }
                        return super.shouldInterceptRequest(view, request)
                    }
                    override fun onPageFinished(view: WebView, url: String) {
                        onLog("PAGE " + url)
                        val js = """
                            (function(){
                              const html=document.documentElement.outerHTML;
                              const urls=[];
                              document.querySelectorAll('img,video,source').forEach(e=>{
                                ['src','data-src','poster'].forEach(a=>{const v=e.getAttribute(a);if(v)urls.push(v)});
                                const s=e.getAttribute('srcset');if(s)urls.push(s);
                              });
                              const keys=['giphy_media_info','media_type','image_versions2','video_versions','carousel_media','media_overlay_info','giphy.com','media.giphy.com'];
                              const counts={};
                              keys.forEach(k=>counts[k]=(html.toLowerCase().match(new RegExp(k.toLowerCase().replace(/[.*+?^${}()|[\\]\\]/g,'\\$&'),'g'))||[]).length);
                              return JSON.stringify({title:document.title,htmlLength:html.length,mediaElements:urls.length,counts:counts,candidates:urls.filter(u=>/giphy|gif|webp|fbcdn|cdninstagram|scontent/i.test(u)).slice(0,80)});
                            })();
                        """.trimIndent()
                        view.evaluateJavascript(js) { result ->
                            onLog("DOM  " + result.take(12000))
                        }
                    }
                }
                onReady(this)
            }
        }
    )
}
