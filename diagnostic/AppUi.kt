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
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import java.util.concurrent.TimeUnit

private const val TEST_URL = "https://www.threads.com/@ethanzhang688/post/DdYPqUdAXes"

private data class MediaRow(
    val pk: String,
    val username: String?,
    val isReply: Boolean,
    val text: String?,
    val stickerIds: List<String>,
    val stickerUrls: List<String>,
)

@Composable
fun StickerApp(activity: ComponentActivity, initialSharedText: String?) {
    var log by remember {
        mutableStateOf(
            "上一版已確認 HTML 中有 15 個 is_reply=true，但 regex 只切到 1 個 reply media。\n" +
                "這版改成真正解析所有 data-sjs JSON，遞迴尋找 text_post_app_info。\n"
        )
    }
    var running by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    MaterialTheme {
        Column(Modifier.fillMaxSize().padding(16.dp)) {
            Text("Sticker Saver · Reply JSON Diagnostic", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(8.dp))
            Text(
                "不再用 \"media\" regex。直接解析 Threads 的 <script type=application/json data-sjs>，遞迴找主貼文與 reply 物件。",
                style = MaterialTheme.typography.bodySmall
            )
            Spacer(Modifier.height(12.dp))

            Button(
                onClick = {
                    if (running) return@Button
                    running = true
                    log = "開始解析 data-sjs JSON…\n"
                    scope.launch {
                        runCatching { runJsonReplyDiagnostic(TEST_URL) }
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
                    Text("解析主貼文 + 留言 JSON")
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

private suspend fun runJsonReplyDiagnostic(url: String): String = withContext(Dispatchers.IO) {
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
        val doc = Jsoup.parse(body, url)
        val scripts = doc.select("script[type=application/json][data-sjs]")

        val rowsByPk = linkedMapOf<String, MediaRow>()
        var parsedScripts = 0
        var jsonErrors = 0

        scripts.forEach { script ->
            val text = script.data().ifBlank { script.html() }.trim()
            if (text.isBlank()) return@forEach

            try {
                val root: Any = when {
                    text.startsWith("{") -> JSONObject(text)
                    text.startsWith("[") -> JSONArray(text)
                    else -> return@forEach
                }
                parsedScripts++
                collectMediaRows(root, rowsByPk)
            } catch (_: Exception) {
                jsonErrors++
            }
        }

        val rows = rowsByPk.values.toList()
        val mains = rows.filter { !it.isReply }
        val replies = rows.filter { it.isReply }
        val repliesWithStickers = replies.filter { it.stickerUrls.isNotEmpty() }

        buildString {
            appendLine("HTTP: " + response.code)
            appendLine("HTML chars: " + body.length)
            appendLine("data-sjs scripts: " + scripts.size)
            appendLine("parsed JSON scripts: " + parsedScripts)
            appendLine("JSON parse errors: " + jsonErrors)
            appendLine("objects with text_post_app_info: " + rows.size)
            appendLine("non-reply objects: " + mains.size)
            appendLine("reply objects: " + replies.size)
            appendLine("replies with stickers: " + repliesWithStickers.size)
            appendLine("total reply sticker URLs: " + repliesWithStickers.sumOf { it.stickerUrls.size })
            appendLine()

            appendLine("=== MAIN ===")
            mains.take(3).forEach { row ->
                appendLine("pk=" + row.pk + " user=" + (row.username ?: "?") + " stickers=" + row.stickerUrls.size)
                row.text?.takeIf { it.isNotBlank() }?.let { appendLine("text=" + it.take(100)) }
            }
            appendLine()

            appendLine("=== FIRST 20 REPLIES ===")
            replies.take(20).forEachIndexed { index, row ->
                appendLine(
                    "#" + (index + 1) +
                        " pk=" + row.pk +
                        " user=" + (row.username ?: "?") +
                        " stickers=" + row.stickerUrls.size
                )
                row.text?.takeIf { it.isNotBlank() }?.let {
                    appendLine("text=" + it.replace("\n", " ").take(120))
                }
                row.stickerUrls.take(3).forEach { appendLine(it) }
                appendLine()
            }

            appendLine(
                if (replies.size > 1) "RESULT: MULTIPLE REPLIES FOUND"
                else "RESULT: STILL ONLY ONE REPLY"
            )
        }
    }
}

private fun collectMediaRows(node: Any?, rows: MutableMap<String, MediaRow>) {
    when (node) {
        is JSONObject -> {
            val info = node.optJSONObject("text_post_app_info")
            if (info != null && node.has("pk")) {
                val pk = node.optString("pk").ifBlank { node.optString("id") }
                if (pk.isNotBlank()) {
                    val user = node.optJSONObject("user")
                    val username = user?.optString("username")?.takeIf { it.isNotBlank() }
                    val isReply = info.optBoolean("is_reply", false)

                    val stickerIds = mutableListOf<String>()
                    val stickerUrls = mutableListOf<String>()
                    val textParts = mutableListOf<String>()

                    val textFragments = info.optJSONObject("text_fragments")
                    val fragments = textFragments?.optJSONArray("fragments")
                    if (fragments != null) {
                        for (i in 0 until fragments.length()) {
                            val fragment = fragments.optJSONObject(i) ?: continue
                            fragment.optString("plaintext")
                                .takeIf { it.isNotBlank() && it != "□" }
                                ?.let(textParts::add)

                            if (fragment.optString("fragment_type") == "inline_sticker") {
                                val sticker = fragment.optJSONObject("inline_sticker_fragment")
                                sticker?.optString("sticker_id")
                                    ?.takeIf { it.isNotBlank() }
                                    ?.let(stickerIds::add)
                                sticker?.optString("sticker_url")
                                    ?.takeIf { it.isNotBlank() }
                                    ?.let(stickerUrls::add)
                            }
                        }
                    }

                    rows.putIfAbsent(
                        pk,
                        MediaRow(
                            pk = pk,
                            username = username,
                            isReply = isReply,
                            text = textParts.joinToString("").takeIf { it.isNotBlank() },
                            stickerIds = stickerIds.distinct(),
                            stickerUrls = stickerUrls.distinct(),
                        )
                    )
                }
            }

            val keys = node.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val child = node.opt(key)
                if (child is JSONObject || child is JSONArray) {
                    collectMediaRows(child, rows)
                }
            }
        }

        is JSONArray -> {
            for (i in 0 until node.length()) {
                val child = node.opt(i)
                if (child is JSONObject || child is JSONArray) {
                    collectMediaRows(child, rows)
                }
            }
        }
    }
}
