package com.local.threadssticker

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.time.LocalDate
import java.util.concurrent.TimeUnit

data class UpdateInfo(
    val version: String,
    val releaseUrl: String,
    val apkUrl: String?,
    val features: List<String>,
    val fixes: List<String>,
)

class UpdateChecker(private val context: Context) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val prefs = context.getSharedPreferences("update_settings", Context.MODE_PRIVATE)

    var autoCheckEnabled: Boolean
        get() = prefs.getBoolean("auto_check", false)
        set(value) { prefs.edit().putBoolean("auto_check", value).apply() }

    fun currentVersion(): String = runCatching {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "0.0.0"
    }.getOrDefault("0.0.0")

    fun shouldAutoCheck(now: Long = System.currentTimeMillis()): Boolean {
        if (!autoCheckEnabled) return false
        val last = prefs.getLong("last_auto_check_at", 0L)
        return last <= 0L || now - last >= AUTO_CHECK_INTERVAL_MS
    }

    fun markAutoCheckAttempt(now: Long = System.currentTimeMillis()) {
        prefs.edit().putLong("last_auto_check_at", now).apply()
    }

    fun dismissForToday(version: String) {
        prefs.edit()
            .putString("dismissed_version", version)
            .putString("dismissed_date", LocalDate.now().toString())
            .apply()
    }

    fun isDismissedToday(version: String): Boolean {
        return prefs.getString("dismissed_version", null) == version &&
            prefs.getString("dismissed_date", null) == LocalDate.now().toString()
    }

    suspend fun check(): UpdateInfo? = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("https://api.github.com/repos/nununuuuu/StickerSaver/releases/latest")
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "StickerSaver/${currentVersion()}")
            .header("Cache-Control", "no-cache")
            .build()
        val json = client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("檢查更新失敗：${response.code}")
            JSONObject(response.body.string())
        }

        val tag = json.optString("tag_name").removePrefix("v")
        if (tag.isBlank() || compareVersions(tag, currentVersion()) <= 0) return@withContext null

        val body = json.optString("body")
        val assets = json.optJSONArray("assets")
        var apkUrl: String? = null
        if (assets != null) {
            for (i in 0 until assets.length()) {
                val asset = assets.optJSONObject(i) ?: continue
                if (asset.optString("name").endsWith(".apk", ignoreCase = true)) {
                    apkUrl = asset.optString("browser_download_url").takeIf { it.isNotBlank() }
                    break
                }
            }
        }

        UpdateInfo(
            version = tag,
            releaseUrl = json.optString("html_url"),
            apkUrl = apkUrl,
            features = parseSection(body, listOf("新增功能", "新功能", "Features", "Added")),
            fixes = parseSection(body, listOf("修正項目", "修正", "修正功能", "Fixes", "Fixed")),
        )
    }

    suspend fun downloadApk(
        info: UpdateInfo,
        onProgress: (Int) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        val url = info.apkUrl ?: error("此版本沒有可下載的 APK")
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "StickerSaver/${currentVersion()}")
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("下載更新失敗：${response.code}")
            val body = response.body
            val total = body.contentLength()
            val dir = context.cacheDir.resolve("updates").apply { mkdirs() }
            val out = dir.resolve("StickerSaver-v${info.version}.apk")
            body.byteStream().use { input ->
                out.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var downloaded = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        output.write(buffer, 0, read)
                        downloaded += read
                        if (total > 0) {
                            onProgress(((downloaded * 100L) / total).toInt().coerceIn(0, 100))
                        }
                    }
                }
            }
            onProgress(100)
            out
        }
    }

    private fun parseSection(body: String, headings: List<String>): List<String> {
        val lines = body.lines()
        var active = false
        val result = mutableListOf<String>()
        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.startsWith("#")) {
                val heading = trimmed.trimStart('#').trim()
                active = headings.any { heading.equals(it, ignoreCase = true) }
                continue
            }
            if (active && (trimmed.startsWith("-") || trimmed.startsWith("*"))) {
                result += trimmed.drop(1).trim()
            }
        }
        return result.take(12)
    }

    private fun compareVersions(a: String, b: String): Int {
        val ap = a.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
        val bp = b.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
        val size = maxOf(ap.size, bp.size)
        for (i in 0 until size) {
            val av = ap.getOrElse(i) { 0 }
            val bv = bp.getOrElse(i) { 0 }
            if (av != bv) return av.compareTo(bv)
        }
        return 0
    }

    companion object {
        const val AUTO_CHECK_INTERVAL_MS = 6L * 60L * 60L * 1000L
    }
}
