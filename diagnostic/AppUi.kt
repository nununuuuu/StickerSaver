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
    var log by remember {
        mutableStateOf(
            "已確認主貼文可從 Barcelona preload JSON 精準切出。\n" +
                "這版測試留言 media object 分組。\n"
        )
    }
    var running by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    MaterialTheme {
        Column(Modifier.fillMaxSize().padding(16.dp)) {
            Text("Sticker Saver · Reply Diagnostic", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(8.dp))
            Text(
                "使用成功的 Desktop + Sec-Fetch headers，掃描 Threads preload JSON 中的 media objects，區分主貼文與 reply，並統計每則留言的 inline stickers。",
                style = MaterialTheme.typography.bodySmall
            )
            Spacer(Modifier.height(12.dp))

            Button(
                onClick = {
                    if (running) return@Button
                    running = true
                    log = "開始分析留言 payload…\n"
                    scope.launch {
                        runCatching { runReplyDiagnostic(TEST_URL) }
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
                    Text("測試留言 Sticker 分組")
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

private suspend fun runReplyDiagnostic(url: String): String = withContext(Dispatchers.IO) {
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
        val preloadPositions = Regex("BarcelonaPostPageTargetQueryRelayPreloader", RegexOption.IGNORE_CASE)
            .findAll(body)
            .map { it.range.first }
            .toList()

        val mediaObjects = linkedMapOf<String, String>()

        preloadPositions.forEach { start ->
            extractJsonObjectsForKey(body, "media", start, 200_000).forEach { obj ->
                val pk = firstString(obj, "pk")
                    ?: firstString(obj, "id")
                    ?: return@forEach
                mediaObjects.putIfAbsent(pk, obj)
            }
        }

        data class Row(
            val pk: String,
            val username: String?,
            val isReply: Boolean,
            val stickerIds: List<String>,
            val stickerUrls: List<String>,
            val plainTextRaw: String?,
        )

        val rows = mediaObjects.map { (pk, obj) ->
            val ids = allStrings(obj, "sticker_id").distinct()
            val urls = allStrings(obj, "sticker_url").map(::decodeJsonValue).distinct()
            Row(
                pk = pk,
                username = firstString(obj, "username"),
                isReply = Regex("\\\"is_reply\\\"\\s*:\\s*true").containsMatchIn(obj),
                stickerIds = ids,
                stickerUrls = urls,
                plainTextRaw = firstString(obj, "plaintext"),
            )
        }

        val mainRows = rows.filter { !it.isReply }
        val replyRows = rows.filter { it.isReply }
        val replyWithStickers = replyRows.filter { it.stickerUrls.isNotEmpty() }

        buildString {
            appendLine("HTTP: " + response.code)
            appendLine("HTML chars: " + body.length)
            appendLine("Barcelona preloaders: " + preloadPositions.size)
            appendLine("Unique media objects: " + rows.size)
            appendLine("Non-reply media: " + mainRows.size)
            appendLine("Reply media: " + replyRows.size)
            appendLine("Replies with stickers: " + replyWithStickers.size)
            appendLine("Total reply sticker URLs: " + replyWithStickers.sumOf { it.stickerUrls.size })
            appendLine()

            appendLine("=== First 15 replies ===")
            replyRows.take(15).forEachIndexed { index, row ->
                appendLine(
                    "#" + (index + 1) +
                        " pk=" + row.pk +
                        " user=" + (row.username ?: "?") +
                        " stickers=" + row.stickerUrls.size
                )
                row.plainTextRaw?.let {
                    appendLine("text(raw): " + it.take(120))
                }
                row.stickerUrls.take(3).forEach { stickerUrl ->
                    appendLine(stickerUrl)
                }
                appendLine()
            }

            if (replyRows.isEmpty()) {
                appendLine("RESULT: NO REPLY MEDIA OBJECTS")
            } else {
                appendLine("RESULT: REPLY GROUPING FOUND")
            }
        }
    }
}

private fun firstString(json: String, key: String): String? =
    Regex("\\\"" + Regex.escape(key) + "\\\"\\s*:\\s*\\\"([^\\\"]*)\\\"")
        .find(json)
        ?.groupValues
        ?.getOrNull(1)

private fun allStrings(json: String, key: String): List<String> =
    Regex("\\\"" + Regex.escape(key) + "\\\"\\s*:\\s*\\\"([^\\\"]*)\\\"")
        .findAll(json)
        .map { it.groupValues[1] }
        .toList()

private fun decodeJsonValue(value: String): String =
    value
        .replace("\\\\/", "/")
        .replace("\\\\u002F", "/", ignoreCase = true)
        .replace("\\\\u003A", ":", ignoreCase = true)
        .replace("\\\\u003D", "=", ignoreCase = true)
        .replace("\\\\u0026", "&", ignoreCase = true)
        .replace("\\\\u003F", "?", ignoreCase = true)

private fun extractJsonObjectsForKey(
    text: String,
    key: String,
    startAt: Int,
    maxChars: Int,
): List<String> {
    val end = (startAt + maxChars).coerceAtMost(text.length)
    val region = text.substring(startAt, end)
    val regex = Regex("\\\"" + Regex.escape(key) + "\\\"\\s*:\\s*\\{")
    val results = mutableListOf<String>()

    regex.findAll(region).forEach { match ->
        val absoluteMatch = startAt + match.range.first
        val objectStart = text.indexOf('{', absoluteMatch)
        if (objectStart < 0 || objectStart >= end) return@forEach
        extractBalancedObject(text, objectStart, end)?.let(results::add)
    }
    return results
}

private fun extractBalancedObject(text: String, objectStart: Int, endExclusive: Int): String? {
    var depth = 0
    var inString = false
    var escaped = false

    for (i in objectStart until endExclusive) {
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
