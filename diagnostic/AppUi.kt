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

private data class HeaderProfile(
    val name: String,
    val userAgent: String,
    val extraHeaders: Map<String, String> = emptyMap(),
)

@Composable
fun StickerApp(activity: ComponentActivity, initialSharedText: String?) {
    var log by remember { mutableStateOf("準備測試 DdYPqUdAXes\n") }
    var running by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    MaterialTheme {
        Column(Modifier.fillMaxSize().padding(16.dp)) {
            Text("Sticker Saver · HTTP A/B Diagnostic", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(8.dp))
            Text(
                "不使用 Jina、不使用 WebView。一次比較 4 組 HTTP headers，找出哪組能拿到 Threads 的 inline_sticker preload JSON。",
                style = MaterialTheme.typography.bodySmall
            )
            Spacer(Modifier.height(12.dp))

            Button(
                onClick = {
                    if (running) return@Button
                    running = true
                    log = "開始 A/B 測試…\n"
                    scope.launch {
                        runCatching { runHeaderAbDiagnostic(TEST_URL) }
                            .onSuccess { log = it }
                            .onFailure { e ->
                                log = "診斷失敗\n" +
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
                    Text("開始 4 組 HTTP 測試")
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

private suspend fun runHeaderAbDiagnostic(url: String): String = withContext(Dispatchers.IO) {
    val mobileUa =
        "Mozilla/5.0 (Linux; Android 15; Pixel 9) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/140.0.0.0 Mobile Safari/537.36"

    val desktopUa =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36"

    val profiles = listOf(
        HeaderProfile(
            name = "A. Android Mobile Chrome",
            userAgent = mobileUa,
        ),
        HeaderProfile(
            name = "B. Windows Desktop Chrome",
            userAgent = desktopUa,
        ),
        HeaderProfile(
            name = "C. Desktop + Sec-Fetch",
            userAgent = desktopUa,
            extraHeaders = mapOf(
                "Sec-Fetch-Dest" to "document",
                "Sec-Fetch-Mode" to "navigate",
                "Sec-Fetch-Site" to "none",
                "Sec-Fetch-User" to "?1",
                "Upgrade-Insecure-Requests" to "1",
            )
        ),
        HeaderProfile(
            name = "D. Desktop + Browser-like headers",
            userAgent = desktopUa,
            extraHeaders = mapOf(
                "Sec-Fetch-Dest" to "document",
                "Sec-Fetch-Mode" to "navigate",
                "Sec-Fetch-Site" to "none",
                "Sec-Fetch-User" to "?1",
                "Upgrade-Insecure-Requests" to "1",
                "Sec-CH-UA" to "\"Chromium\";v=\"140\", \"Google Chrome\";v=\"140\", \"Not=A?Brand\";v=\"24\"",
                "Sec-CH-UA-Mobile" to "?0",
                "Sec-CH-UA-Platform" to "\"Windows\"",
                "Accept-Encoding" to "gzip, deflate, br",
            )
        )
    )

    val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    buildString {
        appendLine("Target: " + url)
        appendLine()

        profiles.forEach { profile ->
            appendLine("===== " + profile.name + " =====")

            try {
                val builder = Request.Builder()
                    .url(url)
                    .header("User-Agent", profile.userAgent)
                    .header(
                        "Accept",
                        "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8"
                    )
                    .header("Accept-Language", "zh-TW,zh;q=0.9,en-US;q=0.8,en;q=0.7")
                    .header("Cache-Control", "no-cache")
                    .header("Pragma", "no-cache")

                profile.extraHeaders.forEach { (k, v) -> builder.header(k, v) }

                client.newCall(builder.build()).execute().use { response ->
                    val body = response.body.string()

                    val relayCount = Regex("RelayPrefetchedStreamCache", RegexOption.IGNORE_CASE)
                        .findAll(body).count()
                    val preloaderCount = Regex(
                        "BarcelonaPostPageTargetQueryRelayPreloader",
                        RegexOption.IGNORE_CASE
                    ).findAll(body).count()
                    val textFragmentsCount = Regex("text_fragments", RegexOption.IGNORE_CASE)
                        .findAll(body).count()
                    val inlineCount = Regex("inline_sticker_fragment", RegexOption.IGNORE_CASE)
                        .findAll(body).count()
                    val typeCount = Regex(
                        "\\\"fragment_type\\\"\\s*:\\s*\\\"inline_sticker\\\""
                    ).findAll(body).count()

                    val rawUrls = Regex(
                        "\\\"sticker_url\\\"\\s*:\\s*\\\"([^\\\"]+)\\\""
                    ).findAll(body).map { it.groupValues[1] }.toList()

                    val rawIds = Regex(
                        "\\\"sticker_id\\\"\\s*:\\s*\\\"([^\\\"]+)\\\""
                    ).findAll(body).map { it.groupValues[1] }.toList()

                    fun decode(value: String): String = value
                        .replace("\\\\/", "/")
                        .replace("\\\\u002F", "/", ignoreCase = true)
                        .replace("\\\\u003A", ":", ignoreCase = true)
                        .replace("\\\\u003D", "=", ignoreCase = true)
                        .replace("\\\\u0026", "&", ignoreCase = true)

                    val urls = rawUrls.map(::decode).distinct()
                    val ids = rawIds.distinct()

                    appendLine("HTTP: " + response.code)
                    appendLine("Final URL: " + response.request.url)
                    appendLine("Content-Type: " + (response.header("Content-Type") ?: "(none)"))
                    appendLine("HTML chars: " + body.length)
                    appendLine("RelayPrefetchedStreamCache: " + relayCount)
                    appendLine("Barcelona preloader: " + preloaderCount)
                    appendLine("text_fragments: " + textFragmentsCount)
                    appendLine("inline_sticker_fragment: " + inlineCount)
                    appendLine("fragment_type=inline_sticker: " + typeCount)
                    appendLine("sticker_id: " + ids.size)
                    appendLine("sticker_url: " + urls.size)
                    appendLine("contains giphy.com: " + body.contains("giphy.com", ignoreCase = true))

                    if (urls.isNotEmpty()) {
                        appendLine("RESULT: SUCCESS")
                        urls.take(6).forEachIndexed { index, stickerUrl ->
                            val id = ids.getOrNull(index)
                            appendLine(
                                "#" + (index + 1) +
                                    (id?.let { " id=" + it } ?: "")
                            )
                            appendLine(stickerUrl)
                        }
                    } else {
                        appendLine("RESULT: NO STICKER DATA")
                    }
                }
            } catch (e: Exception) {
                appendLine("ERROR: " + (e.message ?: e::class.simpleName ?: "unknown"))
            }

            appendLine()
        }
    }
}
