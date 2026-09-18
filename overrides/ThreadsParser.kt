package com.local.threadssticker

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import java.util.concurrent.TimeUnit

class ThreadsParser {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private data class MediaPayload(
        val pk: String,
        val username: String?,
        val text: String?,
        val isReply: Boolean,
        val code: String?,
        val raw: String,
        val stickerUrls: List<String>,
    )

    suspend fun parse(
        inputUrl: String,
        options: ParseOptions,
        isCancellationRequested: () -> Boolean = { false },
        onTaskCompleted: (ParseResult, ParseProgress) -> Unit = { _, _ -> },
    ): ParseResult = withContext(Dispatchers.IO) {
        require(options.parsePost || options.parseComments) { "請至少選擇貼文或留言" }

        val url = normalizeThreadsUrl(inputUrl)
        val html = fetchHtml(url)
        val doc = Jsoup.parse(html, url)
        val payloads = extractMediaPayloads(html)

        val main = findMainPostPayload(payloads, url)
        val author = main?.username
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")
                ?.substringBefore(" on Threads")
                ?.takeIf { it.isNotBlank() }
        val postText = main?.text

        val postMedia = if (options.parsePost) {
            main?.stickerUrls.orEmpty().map { stickerUrl ->
                ParsedMedia(
                    url = stickerUrl,
                    mimeType = mimeFromUrl(stickerUrl),
                    occurrence = MediaOccurrence(MediaOriginType.POST),
                )
            }
        } else emptyList()

        val allReplies = if (options.parseComments) {
            payloads.filter { it.isReply }
        } else emptyList()

        val selectedReplies = when (options.commentLoadMode) {
            CommentLoadMode.ALL -> allReplies
            CommentLoadMode.TOP -> allReplies.take(options.topCommentCount.coerceIn(1, 500))
        }

        val plannedTasks = (if (options.parsePost) 1 else 0) + selectedReplies.size
        var completedTasks = 0
        var cancelled = false
        val allMedia = mutableListOf<ParsedMedia>()

        fun emitTask(media: List<ParsedMedia>, label: String) {
            completedTasks += 1
            allMedia += media
            val distinct = dedupe(allMedia)
            val partial = ParseResult(
                sourceUrl = url,
                author = author,
                postText = postText,
                media = distinct,
                completedTasks = completedTasks,
                plannedTasks = plannedTasks,
                cancelled = false,
                availableCommentCount = allReplies.size,
                selectedCommentCount = selectedReplies.size,
            )
            onTaskCompleted(
                partial,
                ParseProgress(
                    completedTasks = completedTasks,
                    plannedTasks = plannedTasks,
                    currentLabel = label,
                    postMediaCount = distinct.count { it.occurrence.type == MediaOriginType.POST },
                    commentMediaCount = distinct.count { it.occurrence.type == MediaOriginType.COMMENT },
                )
            )
        }

        if (options.parsePost) {
            if (isCancellationRequested()) cancelled = true
            else emitTask(postMedia, "主貼文")
        }

        if (!cancelled && options.parseComments) {
            selectedReplies.forEachIndexed { index, reply ->
                if (isCancellationRequested()) {
                    cancelled = true
                    return@forEachIndexed
                }
                val occurrence = MediaOccurrence(
                    type = MediaOriginType.COMMENT,
                    commentId = reply.pk,
                    commentAuthor = reply.username,
                    commentText = reply.text,
                )
                val media = reply.stickerUrls.map { stickerUrl ->
                    ParsedMedia(
                        url = stickerUrl,
                        mimeType = mimeFromUrl(stickerUrl),
                        occurrence = occurrence,
                    )
                }
                emitTask(media, "留言 " + (index + 1))
            }
        }

        val finalMedia = dedupe(allMedia)
        if (finalMedia.isEmpty() && completedTasks > 0) {
            error("已讀取這篇 Threads，但目前沒有解析到 Sticker / GIF")
        }

        ParseResult(
            sourceUrl = url,
            author = author,
            postText = postText,
            media = finalMedia,
            completedTasks = completedTasks,
            plannedTasks = plannedTasks,
            cancelled = cancelled,
            availableCommentCount = allReplies.size,
            selectedCommentCount = selectedReplies.size,
        )
    }

    private fun fetchHtml(url: String): String {
        val request = Request.Builder()
            .url(url)
            .header(
                "User-Agent",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36"
            )
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

        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("Threads 回應 " + response.code)
            response.body.string()
        }
    }

    private fun extractMediaPayloads(html: String): List<MediaPayload> {
        val doc = Jsoup.parse(html)
        val out = linkedMapOf<String, MediaPayload>()

        doc.select("script[type=application/json][data-sjs]").forEach { script ->
            val raw = script.data().ifBlank { script.html() }.trim()
            if (raw.isBlank()) return@forEach

            val root: Any = runCatching {
                when {
                    raw.startsWith("{") -> JSONObject(raw)
                    raw.startsWith("[") -> JSONArray(raw)
                    else -> return@forEach
                }
            }.getOrNull() ?: return@forEach

            collectPayloads(root, out)
        }
        return out.values.toList()
    }

    private fun collectPayloads(node: Any?, out: MutableMap<String, MediaPayload>) {
        when (node) {
            is JSONObject -> {
                val info = node.optJSONObject("text_post_app_info")
                if (info != null && (node.has("pk") || node.has("id"))) {
                    val pk = node.optString("pk").ifBlank { node.optString("id") }
                    if (pk.isNotBlank()) {
                        val username = node.optJSONObject("user")
                            ?.optString("username")
                            ?.takeIf { it.isNotBlank() }

                        val textParts = mutableListOf<String>()
                        val stickerUrls = mutableListOf<String>()
                        val fragments = info.optJSONObject("text_fragments")
                            ?.optJSONArray("fragments")

                        if (fragments != null) {
                            for (i in 0 until fragments.length()) {
                                val fragment = fragments.optJSONObject(i) ?: continue
                                fragment.optString("plaintext")
                                    .takeIf { it.isNotBlank() && it != "□" }
                                    ?.let(textParts::add)

                                if (fragment.optString("fragment_type") == "inline_sticker") {
                                    fragment.optJSONObject("inline_sticker_fragment")
                                        ?.optString("sticker_url")
                                        ?.takeIf { it.isNotBlank() }
                                        ?.let(stickerUrls::add)
                                }
                            }
                        }

                        val stickers = stickerUrls
                            .map(::normalizeStickerUrl)
                            .filter(::isStickerUrl)
                            .distinctBy(::canonicalMediaKey)

                        val code = sequenceOf(
                            node.optString("code"),
                            node.optString("shortcode"),
                            node.optString("media_code")
                        ).firstOrNull { it.isNotBlank() }

                        out.putIfAbsent(
                            pk,
                            MediaPayload(
                                pk = pk,
                                username = username,
                                text = textParts.joinToString("")
                                    .trim()
                                    .take(500)
                                    .takeIf { it.isNotBlank() },
                                isReply = info.optBoolean("is_reply", false),
                                code = code,
                                raw = node.toString(),
                                stickerUrls = stickers,
                            )
                        )
                    }
                }

                val keys = node.keys()
                while (keys.hasNext()) {
                    val child = node.opt(keys.next())
                    if (child is JSONObject || child is JSONArray) {
                        collectPayloads(child, out)
                    }
                }
            }

            is JSONArray -> {
                for (i in 0 until node.length()) {
                    val child = node.opt(i)
                    if (child is JSONObject || child is JSONArray) {
                        collectPayloads(child, out)
                    }
                }
            }
        }
    }

    private fun findMainPostPayload(payloads: List<MediaPayload>, url: String): MediaPayload? {
        val handle = Regex("/@([^/]+)/", RegexOption.IGNORE_CASE)
            .find(url)?.groupValues?.getOrNull(1)
        val shortcode = url.substringBefore('?').trimEnd('/').substringAfterLast('/')

        // A Threads comment/reply permalink is also /@user/post/<shortcode>.
        // Resolve the exact permalink payload first, even when text_post_app_info.is_reply = true.
        // Otherwise filtering to non-replies can accidentally select the parent post and import
        // every sticker from the parent instead of only the selected comment.
        if (shortcode.isNotBlank()) {
            payloads.firstOrNull {
                it.code.equals(shortcode, ignoreCase = true)
            }?.let { return it }
        }

        val candidates = payloads.filter { !it.isReply }
        if (candidates.isEmpty()) return null

        return candidates.maxByOrNull { payload ->
            var score = 0
            if (!shortcode.isBlank() && payload.raw.contains(shortcode, ignoreCase = true)) {
                score += 500
            }
            if (!handle.isNullOrBlank() && payload.username.equals(handle, ignoreCase = true)) {
                score += 200
            }
            if (payload.stickerUrls.isNotEmpty()) score += 50
            score += payload.stickerUrls.size.coerceAtMost(40)
            score
        }
    }

    private fun normalizeStickerUrl(value: String): String =
        value
            .replace("\\/", "/")
            .replace("\\u002F", "/", ignoreCase = true)
            .replace("\\u003A", ":", ignoreCase = true)
            .replace("\\u003D", "=", ignoreCase = true)
            .replace("\\u0026", "&", ignoreCase = true)
            .replace("\\u003F", "?", ignoreCase = true)

    private fun isStickerUrl(url: String): Boolean {
        val l = url.lowercase()
        if (!(l.startsWith("http://") || l.startsWith("https://"))) return false
        if (listOf("profile_pic", "avatar", "favicon", "emoji", "sprite", "icon")
                .any { l.contains(it) }) return false

        return l.contains("giphy.com/") ||
            l.contains("tenor.com/") ||
            l.substringBefore('?').endsWith(".gif")
    }

    private fun normalizeThreadsUrl(value: String): String {
        val match = Regex(
            "https?://(?:www\\.)?(?:threads\\.net|threads\\.com)/[^\\s]+",
            RegexOption.IGNORE_CASE
        ).find(value)?.value ?: error("請貼上有效的 Threads 貼文連結")
        return match.trimEnd('.', ',', ')', ']', '}')
    }

    private fun mimeFromUrl(url: String): String {
        val l = url.lowercase()
        return when {
            ".gif" in l || "giphy.com/" in l || "tenor.com/" in l -> "image/gif"
            else -> "image/gif"
        }
    }

    private fun canonicalMediaKey(url: String): String {
        val parsed = url.toHttpUrlOrNull()
        return if (parsed != null) {
            parsed.scheme + "://" + parsed.host + parsed.encodedPath
        } else {
            url.substringBefore('?')
        }
    }

    private fun dedupe(media: List<ParsedMedia>): List<ParsedMedia> =
        media.distinctBy {
            canonicalMediaKey(it.url) + "|" + it.occurrence.type.name + "|" + it.occurrence.commentId.orEmpty()
        }
}
