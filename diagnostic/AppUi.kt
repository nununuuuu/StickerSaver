package com.local.threadssticker

import android.annotation.SuppressLint
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

private const val TEST_URL = "https://www.threads.com/@ethanzhang688/post/DdYPqUdAXes"

@Composable
fun StickerApp(activity: ComponentActivity, initialSharedText: String?) {
    var log by remember { mutableStateOf("準備測試 DdYPqUdAXes\n") }
    var web by remember { mutableStateOf<WebView?>(null) }

    MaterialTheme {
        Column(Modifier.fillMaxSize().padding(16.dp)) {
            Text("Sticker Saver · Threads Diagnostic", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(8.dp))
            Text("不使用 Jina，只測試手機 WebView / Threads 本身。", style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = {
                    log = "開始載入 Threads…\n"
                    web?.loadUrl(TEST_URL)
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("測試 Threads") }

            Spacer(Modifier.height(12.dp))
            Text(
                text = log,
                modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
                style = MaterialTheme.typography.bodySmall
            )

            DiagnosticWebView(
                onReady = { web = it },
                onLog = { line -> log += line + "\n" }
            )
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun DiagnosticWebView(
    onReady: (WebView) -> Unit,
    onLog: (String) -> Unit
) {
    AndroidView(
        modifier = Modifier.size(1.dp),
        factory = { context ->
            WebView(context).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.userAgentString = settings.userAgentString.replace("; wv", "")
                webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(
                        view: WebView?,
                        request: WebResourceRequest?
                    ): android.webkit.WebResourceResponse? {
                        val u = request?.url?.toString().orEmpty()
                        val low = u.lowercase()
                        val interesting = listOf(
                            "giphy", ".gif", ".webp", "fbcdn",
                            "cdninstagram", "scontent", "image_versions",
                            "video_versions", "carousel_media"
                        ).any { low.contains(it) }
                        if (interesting) {
                            view?.post { onLog("NET  " + u.take(500)) }
                        }
                        return super.shouldInterceptRequest(view, request)
                    }

                    override fun onPageFinished(view: WebView, url: String) {
                        onLog("PAGE " + url)
                        val js = """
                            (function() {
                              const html = document.documentElement.outerHTML || "";
                              const low = html.toLowerCase();
                              const keys = [
                                "giphy_media_info",
                                "media_type",
                                "image_versions2",
                                "video_versions",
                                "carousel_media",
                                "media_overlay_info",
                                "giphy.com",
                                "media.giphy.com"
                              ];
                              const counts = {};
                              keys.forEach(function(k) {
                                counts[k] = low.split(k.toLowerCase()).length - 1;
                              });

                              const urls = [];
                              document.querySelectorAll("img,video,source").forEach(function(e) {
                                ["src","data-src","poster"].forEach(function(a) {
                                  const v = e.getAttribute(a);
                                  if (v) urls.push(v);
                                });
                                const s = e.getAttribute("srcset");
                                if (s) urls.push(s);
                              });

                              return JSON.stringify({
                                title: document.title,
                                htmlLength: html.length,
                                mediaElements: urls.length,
                                counts: counts,
                                candidates: urls.filter(function(u) {
                                  return /giphy|gif|webp|fbcdn|cdninstagram|scontent/i.test(u);
                                }).slice(0, 100)
                              });
                            })();
                        """.trimIndent()
                        view.evaluateJavascript(js) { result ->
                            onLog("DOM  " + result.take(20000))
                        }
                    }
                }
                onReady(this)
            }
        }
    )
}
