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
    var log by remember { mutableStateOf("已確認 Profile C 可取得 Threads preload JSON。\n這版只驗證「主貼文」物件能否準確切出。\n") }
    var running by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    MaterialTheme {
        Column(Modifier.fillMaxSize().padding(16.dp)) {
            Text("Sticker Saver · Main Post Diagnostic", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(8.dp))
            Text(
                "使用已成功的 Desktop + Sec-Fetch headers，定位 BarcelonaPostPageTargetQueryRelayPreloader 的 result.data.media，只抓主貼文 inline stickers。",
                style = MaterialTheme.typography.bodySmall
            )
            Spacer(Modifier.height(12.dp))

            Button(
                onClick = {
                    if (running) return@Button
                    running = true
                    log = "開始抓取主貼文資料…\n"
                    scope.launch {
                        runCatching { runMainPostDiagnostic(TEST_URL) }
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
                    Text("測試主貼文 Sticker")
                }
            }

            Spacer(Modifier.height(12.dp))
            Text(
                text = log,
                modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

private suspend fun runMainPostDiagnostic(url: String): String = withContext(Dispatchers.IO) {
    val desktopUa =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36"

    val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    val request = Request.Builder()
        .url(url)
        .header("User-Agent", desktopUa)
        .header(
            "Accept",
            "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8"
        )
        .header("Accept-Language", "zh-TW,zh;q=0.9,en-US;q=0.8,en;q=0.7")
        .header("Cache-Control", "no-cache")
        .header("Pragma", "no-cache")
        .header("Sec-Fetch-Dest", "document")
        .header("Sec-Fetch-Mode", "navigate")
        .header("Sec-Fetch-Site", "none")
        .header("Sec-Fetch-User", "?1")
        .header("Upgrade-Insecure-Requests", "1")
        .build()

    client.newCall(request).execute().use { response ->
        val body = response.body.string()
        val preloaderKey = "BarcelonaPostPageTargetQueryRelayPreloader"
        val preloaderIndex = body.indexOf(preloaderKey, ignoreCase = true)

        val mediaObject = if (preloaderIndex >= 0) {
            extractFirstJsonObjectForKey(body, "media", preloaderIndex)
        } else null

        val rawUrls = mediaObject?.let {
            Regex("\\\"sticker_url\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"")
                .findAll(it)
                .map { m -> m.groupValues[1] }
                .toList()
        }.orEmpty()

        val rawIds = mediaObject?.let {
            Regex("\\\"sticker_id\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"")
                .findAll(it)
                .map { m -> m.groupValues[1] }
                .toList()
        }.orEmpty()

        fun decode(value: String): String = value
            .replace("\\\\/", "/")
            .replace("\\\\u002F", "/", ignoreCase = true)
            .replace("\\\\u003A", ":", ignoreCase = true)
            .replace("\\\\u003D", "=", ignoreCase = true)
            .replace("\\\\u0026", "&", ignoreCase = true)

        val urls = rawUrls.map(::decode).distinct()
        val ids = rawIds.distinct()

        val username = mediaObject?.let {
            Regex("\\\"username\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"")
                .find(it)?.groupValues?.getOrNull(1)
        }
        val mediaPk = mediaObject?.let {
            Regex("\\\"pk\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"")
                .find(it)?.groupValues?.getOrNull(1)
        }

        buildString {
            appendLine("HTTP: " + response.code)
            appendLine("HTML chars: " + body.length)
            appendLine("Preloader found: " + (preloaderIndex >= 0))
            appendLine("Main media object found: " + (mediaObject != null))
            appendLine("Main media chars: " + (mediaObject?.length ?: 0))
            appendLine("username: " + (username ?: "(not found)"))
            appendLine("media pk: " + (mediaPk ?: "(not found)"))
            appendLine("main sticker_id: " + ids.size)
            appendLine("main sticker_url: " + urls.size)
            appendLine()

            if (urls.isNotEmpty()) {
                appendLine("RESULT: MAIN POST SUCCESS")
                urls.take(40).forEachIndexed { index, stickerUrl ->
                    appendLine("#" + (index + 1) + (ids.getOrNull(index)?.let { " id=" + it } ?: ""))
                    appendLine(stickerUrl)
                }
            } else {
                appendLine("RESULT: MAIN POST NOT ISOLATED")
                appendLine("Need a different JSON-path selector.")
            }
        }
    }
}

private fun extractFirstJsonObjectForKey(text: String, key: String, startAt: Int): String? {
    val regex = Regex("\\\"" + Regex.escape(key) + "\\\"\\s*:\\s*\\{")
    val match = regex.find(text, startAt) ?: return null
    val objectStart = text.indexOf('{', match.range.first)
    if (objectStart < 0) return null

    var depth = 0
    var inString = false
    var escaped = false

    for (i in objectStart until text.length) {
        val ch = text[i]

        if (inString) {
            if (escaped) {
                escaped = false
            } else if (ch == '\\') {
                escaped = true
            } else if (ch == '"') {
                inString = false
            }
            continue
        }

        when (ch) {
            '"' -> inString = true
            '{' -> depth++
            '}' -> {
                depth--
                if (depth == 0) return text.substring(objectStart, i + 1)
            }
        }
    }
    return null
}
