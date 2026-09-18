package com.local.threadssticker

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

class ThreadsParser {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

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
        val mainPayload = extractMainPostPayload(html)
        val author = mainPayload?.username
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")
                ?.substringBefore(" on Threads")
                ?.takeIf { it.isNotBlank() }
        val postText = mainPayload?.text

        val postMedia = if (options.parsePost) {
            extractInlineStickers(mainPayload?.json.orEmpty(), MediaOccurrence(MediaOriginType.POST))
        } else emptyList()

        val stickerReplies = if (options.parseComments) {
            extractStickerReplies(html)
        } else emptyList()

        // TOP means the first N replies that actually contain at least one inline sticker.
        // This deliberately does not claim to match Threads' opaque "熱門" ranking.
        val selectedComments = when (options.commentLoadMode) {
            CommentLoadMode.ALL -> stickerReplies
            CommentLoadMode.TOP -> stickerReplies.take(options.topCommentCount.coerceIn(1, 200))
        }

        val plannedTasks = (if (options.parsePost) 1 else 0) + selectedComments.size
        var completedTasks = 0
        var cancelled = false
        val allMedia = mutableListOf<ParsedMedia>()

        fun emitTask(media: List<ParsedMedia>, label: String) {
            completedTasks += 1
            allMedia += media
            val distinct = dedupe(allMedia)
            onTaskCompleted(
                ParseResult(
                    sourceUrl = url,
                    author = author,
                    postText = postText,
                    media = distinct,
                    completedTasks = completedTasks,
                    plannedTasks = plannedTasks,
                    cancelled = false,
                ),
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
            selectedComments.forEachIndexed { index, reply ->
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
                emitTask(media, "有貼圖留言 " + (index + 1))
            }
        }

        val finalMedia = dedupe(allMedia)
        if (finalMedia.isEmpty() && completedTasks > 0) {
            error("已讀取這篇 Threads，但目前沒有解析到可用媒體")
        }

        ParseResult(
            sourceUrl = url,
            author = author,
            postText = postText,
            media = finalMedia,
            completedTasks = completedTasks,
            plannedTasks = plannedTasks,
            cancelled = cancelled,
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

    private data class MainPostPayload(
        val json: String,
        val username: String?,
        val text: String?,
    )

    private fun extractMainPostPayload(html: String): MainPostPayload? {
        val preloader = "BarcelonaPostPageTargetQueryRelayPreloader"
        val start = html.indexOf(preloader, ignoreCase = true)
        if (start < 0) return null

        val mediaJson = extractFirstJsonObjectForKey(html, "media", start) ?: return null
        val username = Regex("\\\"username\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"")
            .find(mediaJson)
            ?.groupValues
            ?.getOrNull(1)
            ?.takeIf { it.isNotBlank() }

        val text = runCatching {
            val media = JSONObject(mediaJson)
            val fragments = media.optJSONObject("text_post_app_info")
                ?.optJSONObject("text_fragments")
                ?.optJSONArray("fragments")
            buildList {
                if (fragments != null) {
                    for (i in 0 until fragments.length()) {
                        fragments.optJSONObject(i)
                            ?.optString("plaintext")
                            ?.takeIf { it.isNotBlank() && it != "□" }
                            ?.let(::add)
                    }
                }
            }.joinToString("").trim().take(500).takeIf { it.isNotBlank() }
        }.getOrNull()

        return MainPostPayload(
            json = mediaJson,
            username = username,
            text = text,
        )
    }

    private fun extractInlineStickers(
        text: String,
        occurrence: MediaOccurrence,
    ): List<ParsedMedia> {
        if (text.isBlank()) return emptyList()

        val urls = Regex("\\\"sticker_url\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"")
            .findAll(text)
            .map { it.groupValues[1] }
            .map(::decodeStickerJsonValue)
            .filter(::looksLikeMedia)
            .distinctBy(::canonicalMediaKey)
            .toList()

        return urls.map { url ->
            ParsedMedia(
                url = url,
                mimeType = mimeFromUrl(url),
                occurrence = occurrence,
            )
        }
    }

    private fun decodeStickerJsonValue(value: String): String =
        value
            .replace("\\\\/", "/")
            .replace("\\\\u002F", "/", ignoreCase = true)
            .replace("\\\\u003A", ":", ignoreCase = true)
            .replace("\\\\u003D", "=", ignoreCase = true)
            .replace("\\\\u0026", "&", ignoreCase = true)
            .replace("\\\\u003F", "?", ignoreCase = true)

    private fun extractFirstJsonObjectForKey(
        text: String,
        key: String,
        startAt: Int,
    ): String? {
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

    private data class StickerReply(
        val pk: String,
        val username: String?,
        val text: String?,
        val stickerUrls: List<String>,
    )

    private fun extractStickerReplies(html: String): List<StickerReply> {
        val doc = Jsoup.parse(html)
        val replies = linkedMapOf<String, StickerReply>()

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

            collectStickerReplies(root, replies)
        }

        return replies.values.filter { it.stickerUrls.isNotEmpty() }
    }

    private fun collectStickerReplies(
        node: Any?,
        replies: MutableMap<String, StickerReply>,
    ) {
        when (node) {
            is JSONObject -> {
                val info = node.optJSONObject("text_post_app_info")
                if (info != null && info.optBoolean("is_reply", false) && node.has("pk")) {
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

                        if (stickerUrls.isNotEmpty()) {
                            replies.putIfAbsent(
                                pk,
                                StickerReply(
                                    pk = pk,
                                    username = username,
                                    text = textParts.joinToString("")
                                        .take(300)
                                        .takeIf { it.isNotBlank() },
                                    stickerUrls = stickerUrls
                                        .map(::decodeStickerJsonValue)
                                        .filter(::looksLikeMedia)
                                        .distinctBy(::canonicalMediaKey),
                                )
                            )
                        }
                    }
                }

                val keys = node.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val child = node.opt(key)
                    if (child is JSONObject || child is JSONArray) {
                        collectStickerReplies(child, replies)
                    }
                }
            }

            is JSONArray -> {
                for (i in 0 until node.length()) {
                    val child = node.opt(i)
                    if (child is JSONObject || child is JSONArray) {
                        collectStickerReplies(child, replies)
                    }
                }
            }
        }
    }

    private fun extractMediaFromElement(
        element: Element?,
        occurrence: MediaOccurrence,
    ): List<ParsedMedia> {
        if (element == null) return emptyList()
        val urls = linkedSetOf<String>()

        fun add(raw: String?) {
            if (raw.isNullOrBlank()) return
            normalizeCandidate(raw).forEach { if (looksLikeMedia(it)) urls += it }
        }

        element.select("img,video,source").forEach { media ->
            add(media.attr("src"))
            add(media.attr("data-src"))
            add(media.attr("data-original"))
            add(media.attr("poster"))
            addSrcSet(media.attr("srcset"), ::add)
            addSrcSet(media.attr("data-srcset"), ::add)
        }

        return urls.distinctBy(::canonicalMediaKey).map {
            ParsedMedia(
                url = it,
                mimeType = mimeFromUrl(it),
                occurrence = occurrence,
            )
        }
    }

    private fun extractPageMedia(
        text: String,
        occurrence: MediaOccurrence,
    ): List<ParsedMedia> {
        val urls = linkedSetOf<String>()
        val decoded = decodeEscapedDocument(text)
        val regex = Regex("https?://[^\\\"'<>\\s\\\\]+", RegexOption.IGNORE_CASE)

        regex.findAll(decoded).forEach { match ->
            normalizeCandidate(match.value.trimEnd(')', ']', '}', ',', ';')).forEach {
                if (looksLikeMedia(it)) urls += it
            }
        }

        return urls.distinctBy(::canonicalMediaKey).map {
            ParsedMedia(
                url = it,
                mimeType = mimeFromUrl(it),
                occurrence = occurrence,
            )
        }
    }

    private fun addSrcSet(value: String, add: (String?) -> Unit) {
        if (value.isBlank()) return
        value.split(',').forEach { entry ->
            add(entry.trim().substringBefore(' ').trim())
        }
    }

    private fun normalizeCandidate(raw: String): List<String> {
        val value = raw.trim()
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("\\/", "/")
            .replace("\\u002F", "/", ignoreCase = true)
            .replace("\\u003A", ":", ignoreCase = true)
            .replace("\\u003D", "=", ignoreCase = true)
            .replace("\\u0026", "&", ignoreCase = true)
            .replace("\\u003F", "?", ignoreCase = true)

        val values = linkedSetOf(value)

        runCatching {
            val decoded = URLDecoder.decode(value, StandardCharsets.UTF_8.name())
            if (decoded.startsWith("http://") || decoded.startsWith("https://")) {
                values += decoded
            }
        }

        value.toHttpUrlOrNull()?.let { parsed ->
            parsed.queryParameterNames.forEach { name ->
                parsed.queryParameterValues(name).forEach { nested ->
                    runCatching {
                        val decoded = URLDecoder.decode(nested, StandardCharsets.UTF_8.name())
                        if (decoded.startsWith("http://") || decoded.startsWith("https://")) {
                            values += decoded
                        }
                    }
                }
            }
        }

        return values.map { it.trim().trim('"', '\'', '(', ')', '[', ']', '{', '}') }
    }

    private fun decodeEscapedDocument(html: String): String {
        var out = html
        repeat(2) {
            out = out
                .replace("\\/", "/")
                .replace("\\u002F", "/", ignoreCase = true)
                .replace("\\u003A", ":", ignoreCase = true)
                .replace("\\u003D", "=", ignoreCase = true)
                .replace("\\u0026", "&", ignoreCase = true)
                .replace("\\u003F", "?", ignoreCase = true)
                .replace("&amp;", "&")
        }
        return out
    }

    private fun normalizeThreadsUrl(value: String): String {
        val match = Regex(
            "https?://(?:www\\.)?(?:threads\\.net|threads\\.com)/[^\\s]+",
            RegexOption.IGNORE_CASE
        ).find(value)?.value ?: error("請貼上有效的 Threads 貼文連結")
        return match.trimEnd('.', ',', ')', ']', '}')
    }

    private fun looksLikeMedia(url: String): Boolean {
        val l = url.lowercase()
        if (!(l.startsWith("http://") || l.startsWith("https://"))) return false

        if (listOf(
                "profile_pic", "profilepic", "avatar", "favicon", "emoji",
                "static.cdninstagram.com/rsrc", "/rsrc.php/", "sprite", "icon"
            ).any { l.contains(it) }
        ) return false

        val mediaHost = listOf(
            "giphy", "tenor", "fbcdn", "cdninstagram", "instagram", "threads",
            "scontent", "fbsbx", "lookaside"
        ).any { l.contains(it) }

        val mediaShape = listOf(
            ".gif", ".webp", ".png", ".jpg", ".jpeg", ".avif", ".mp4", ".webm",
            "format=gif", "format=webp", "format=png", "format=jpg",
            "mime_type=image", "image_versions", "video_versions", "/media/", "/v/t"
        ).any { l.contains(it) }

        return mediaHost && mediaShape
    }

    private fun mimeFromUrl(url: String): String {
        val l = url.lowercase()
        return when {
            ".gif" in l || "format=gif" in l -> "image/gif"
            ".png" in l || "format=png" in l -> "image/png"
            ".jpg" in l || ".jpeg" in l || "format=jpg" in l || "format=jpeg" in l -> "image/jpeg"
            ".avif" in l || "format=avif" in l -> "image/avif"
            ".mp4" in l -> "video/mp4"
            ".webm" in l -> "video/webm"
            else -> "image/webp"
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
