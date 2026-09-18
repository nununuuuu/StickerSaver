package com.local.threadssticker

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

private const val TEST_URL = "https://www.threads.com/@ethanzhang688/post/DdYPqUdAXes"

@Composable
fun StickerApp(activity: ComponentActivity, initialSharedText: String?) {
    var log by remember { mutableStateOf("準備測試 DdYPqUdAXes\n") }
    var running by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    MaterialTheme {
        Column(Modifier.fillMaxSize().padding(16.dp)) {
            Text("Sticker Saver · HTTP Diagnostic", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(8.dp))
            Text(
                "不使用 Jina、不使用 WebView。直接用 Android OkHttp 抓 Threads 原始 HTML，檢查 inline_sticker_fragment。",
                style = MaterialTheme.typography.bodySmall
            )
            Spacer(Modifier.height(12.dp))

            Button(
                onClick = {
                    if (running) return@Button
                    running = true
                    log = "開始純 HTTP 測試…\n"
                    scope.launch {
                        runCatching { runHttpDiagnostic(TEST_URL) }
                            .onSuccess { log = it }
                            .onFailure { e ->
                                log = "HTTP 測試失敗\n" +
                                    (e::class.simpleName ?: "Exception") + ": " +
                                    (e.message ?: "unknown error")
                            }
                        running = false
                    }
                },
                enabled = !running,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (running) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("測試中…")
                } else {
                    Text("測試純 HTTP")
                }
            }

            Spacer(Modifier.height(12.dp))
            Text(
                text = log,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

private suspend fun runHttpDiagnostic(url: String): String = withContext(Dispatchers.IO) {
    val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    val request = Request.Builder()
        .url(url)
        .header(
            "User-Agent",
            "Mozilla/5.0 (Linux; Android 15; Pixel 9) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/140.0.0.0 Mobile Safari/537.36"
        )
        .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
        .header("Accept-Language", "zh-TW,zh;q=0.9,en;q=0.7")
        .header("Cache-Control", "no-cache")
        .build()

    client.newCall(request).execute().use { response ->
        val body = response.body.string()
        val inlineCount = Regex("inline_sticker_fragment", RegexOption.IGNORE_CASE)
            .findAll(body).count()
        val typeCount = Regex("\\\"fragment_type\\\"\\s*:\\s*\\\"inline_sticker\\\"")
            .findAll(body).count()

        val rawUrls = Regex("\\\"sticker_url\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"")
            .findAll(body)
            .map { it.groupValues[1] }
            .toList()

        val rawIds = Regex("\\\"sticker_id\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"")
            .findAll(body)
            .map { it.groupValues[1] }
            .toList()

        fun decode(value: String): String = value
            .replace("\\\\/", "/")
            .replace("\\\\u002F", "/", ignoreCase = true)
            .replace("\\\\u003A", ":", ignoreCase = true)
            .replace("\\\\u003D", "=", ignoreCase = true)
            .replace("\\\\u0026", "&", ignoreCase = true)

        val urls = rawUrls.map(::decode).distinct()
        val ids = rawIds.distinct()

        buildString {
            appendLine("HTTP status: " + response.code)
            appendLine("Final URL: " + response.request.url)
            appendLine("Content-Type: " + (response.header("Content-Type") ?: "(none)"))
            appendLine("HTML bytes/chars: " + body.length)
            appendLine()
            appendLine("inline_sticker_fragment: " + inlineCount)
            appendLine("fragment_type=inline_sticker: " + typeCount)
            appendLine("sticker_id: " + ids.size)
            appendLine("sticker_url: " + urls.size)
            appendLine()

            if (urls.isEmpty()) {
                appendLine("結果：原始 HTTP HTML 沒抓到 sticker_url")
                appendLine("contains giphy.com: " + body.contains("giphy.com", ignoreCase = true))
                appendLine("contains inline_sticker: " + body.contains("inline_sticker", ignoreCase = true))
            } else {
                appendLine("結果：成功從原始 HTTP HTML 抓到 Sticker")
                appendLine()
                urls.take(40).forEachIndexed { index, stickerUrl ->
                    val id = ids.getOrNull(index)
                    appendLine("#" + (index + 1) + (id?.let { "  id=" + it } ?: ""))
                    appendLine(stickerUrl)
                    appendLine()
                }
            }
        }
    }
}
