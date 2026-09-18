package com.local.threadssticker

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
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
        val author = doc.selectFirst("meta[property=og:title]")?.attr("content")
            ?.substringBefore(" on Threads")
            ?.takeIf { it.isNotBlank() }

        val postMedia = if (options.parsePost) {
            extractMediaFromElement(
                doc.select("article,[role=article],[data-pressable-container=true]").firstOrNull(),
                MediaOccurrence(MediaOriginType.POST)
            ).ifEmpty { extractPageMedia(doc.html(), MediaOccurrence(MediaOriginType.POST)) }
        } else emptyList()

        val commentElements = if (options.parseComments) {
            doc.select("article,[role=article],[data-pressable-container=true]").drop(1)
        } else emptyList()

        val selectedComments = when (options.commentLoadMode) {
            CommentLoadMode.ALL -> commentElements
            CommentLoadMode.TOP -> commentElements.take(options.topCommentCount.coerceIn(1, 200))
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
            selectedComments.forEachIndexed { index, element ->
                if (isCancellationRequested()) {
                    cancelled = true
                    return@forEachIndexed
                }
                val commentAuthor = element.selectFirst("a[href^=/@],a[href*='threads.com/@'],a[href*='threads.net/@']")
                    ?.text()?.takeIf { it.isNotBlank() }
                val commentText = element.text().take(300).takeIf { it.isNotBlank() }
                val commentId = element.id().takeIf { it.isNotBlank() }
                    ?: AppStore.stableId("comment-" + index + "-" + commentText.orEmpty())
                val occurrence = MediaOccurrence(
                    type = MediaOriginType.COMMENT,
                    commentId = commentId,
                    commentAuthor = commentAuthor,
                    commentText = commentText,
                )
                val media = extractMediaFromElement(element, occurrence)
                    .ifEmpty { extractPageMedia(element.outerHtml(), occurrence) }
                emitTask(media, "留言 " + (index + 1))
            }
        }

        val finalMedia = dedupe(allMedia)
        if (finalMedia.isEmpty() && completedTasks > 0) {
            error("已讀取這篇 Threads，但目前沒有解析到可用媒體")
        }

        ParseResult(
            sourceUrl = url,
            author = author,
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
                "Mozilla/5.0 (Linux; Android 15; Pixel 9) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/140.0.0.0 Mobile Safari/537.36"
            )
            .header("Accept-Language", "zh-TW,zh;q=0.9,en;q=0.7")
            .build()

        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("Threads 回應 " + response.code)
            response.body.string()
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
