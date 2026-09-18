package com.local.threadssticker

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

class ThreadsParser {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    suspend fun parse(inputUrl: String): ParseResult = withContext(Dispatchers.IO) {
        val url = normalizeThreadsUrl(inputUrl)
        val request = Request.Builder()
            .url(url)
            .header(
                "User-Agent",
                "Mozilla/5.0 (Linux; Android 15; Pixel 9) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/140.0.0.0 Mobile Safari/537.36"
            )
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
            .header("Accept-Language", "zh-TW,zh;q=0.9,en;q=0.7")
            .build()

        val html = client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("Threads 回應 ${response.code}")
            response.body.string()
        }

        val doc = Jsoup.parse(html, url)
        val author = doc.selectFirst("meta[property=og:title]")?.attr("content")
            ?.substringBefore(" on Threads")
            ?.takeIf { it.isNotBlank() }

        val candidates = linkedSetOf<String>()
        val primaryCandidates = linkedSetOf<String>()

        fun add(raw: String?, primary: Boolean = false) {
            if (raw.isNullOrBlank()) return
            normalizeCandidate(raw).forEach { clean ->
                if (clean.startsWith("http://") || clean.startsWith("https://")) {
                    candidates += clean
                    if (primary) primaryCandidates += clean
                }
            }
        }

        doc.select(
            "meta[property=og:image], meta[property=og:image:secure_url], " +
                "meta[property=og:video], meta[property=og:video:secure_url], " +
                "meta[name=twitter:image], meta[name=twitter:player:stream]"
        ).forEach { add(it.attr("content"), primary = true) }

        doc.select("img, video, source").forEach { element ->
            add(element.attr("src"))
            add(element.attr("data-src"))
            add(element.attr("data-original"))
            add(element.attr("poster"))
            addSrcSet(element.attr("srcset")) { add(it) }
            addSrcSet(element.attr("data-srcset")) { add(it) }
        }

        val styleUrlRegex = Regex("url\\((?:['\\\"]?)(https?://.*?)(?:['\\\"]?)\\)", RegexOption.IGNORE_CASE)
        doc.select("[style]").forEach { element ->
            styleUrlRegex.findAll(element.attr("style")).forEach { add(it.groupValues[1]) }
        }

        val normalizedHtml = decodeEscapedDocument(html)
        val urlRegex = Regex("https?://[^\\\"'<>\\s\\\\]+", RegexOption.IGNORE_CASE)
        urlRegex.findAll(normalizedHtml).forEach { match ->
            add(match.value.trimEnd(')', ']', '}', ',', ';'))
        }

        val filtered = candidates.filter(::looksLikeMedia)
        val selected = if (filtered.isNotEmpty()) {
            filtered
        } else {
            primaryCandidates.filterNot(::looksLikeNonPostAsset)
        }

        val media = selected
            .distinctBy(::canonicalMediaKey)
            .map { ParsedMedia(it, mimeFromUrl(it)) }

        if (media.isEmpty()) {
            error("這篇 Threads 貼文有內容，但目前沒有解析到可用的主貼文媒體。請稍後重試或回報這個連結。")
        }

        ParseResult(url, author, media)
    }

    private fun addSrcSet(value: String, add: (String) -> Unit) {
        if (value.isBlank()) return
        value.split(',').forEach { entry ->
            val candidate = entry.trim().substringBefore(' ').trim()
            if (candidate.isNotBlank()) add(candidate)
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

        val values = linkedSetOf<String>()
        values += value

        runCatching {
            val decoded = URLDecoder.decode(value, StandardCharsets.UTF_8.name())
            if (decoded.startsWith("http://") || decoded.startsWith("https://")) values += decoded
        }

        value.toHttpUrlOrNull()?.let { parsed ->
            parsed.queryParameterNames.forEach { name ->
                parsed.queryParameterValues(name).forEach { nested ->
                    runCatching {
                        val decoded = URLDecoder.decode(nested, StandardCharsets.UTF_8.name())
                        if (decoded.startsWith("http://") || decoded.startsWith("https://")) values += decoded
                    }
                }
            }
        }

        return values.map { it.trim().trim('\"', '\'', '(', ')', '[', ']', '{', '}') }
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
        if (looksLikeNonPostAsset(l)) return false

        val mediaHost = listOf(
            "giphy", "tenor", "fbcdn", "cdninstagram", "instagram", "threads",
            "scontent", "fbsbx", "lookaside"
        ).any { l.contains(it) }

        val mediaShape = listOf(
            ".gif", ".webp", ".png", ".jpg", ".jpeg", ".avif", ".mp4", ".webm",
            "format=gif", "format=webp", "format=png", "format=jpg", "mime_type=image",
            "image_versions", "video_versions", "/media/", "/v/t"
        ).any { l.contains(it) }

        return mediaHost && mediaShape
    }

    private fun looksLikeNonPostAsset(url: String): Boolean {
        val l = url.lowercase()
        return listOf(
            "profile_pic", "profilepic", "avatar", "favicon", "emoji",
            "static.cdninstagram.com/rsrc", "/rsrc.php/", "sprite", "icon"
        ).any { l.contains(it) }
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
        if (parsed != null) return "${parsed.scheme}://${parsed.host}${parsed.encodedPath}"
        return url.substringBefore('?')
    }
}
