package com.local.threadssticker

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableStateOf

class MainActivity : ComponentActivity() {
    private val sharedText = mutableStateOf<String?>(null)
    private val clipboardText = mutableStateOf<String?>(null)
    private val resumeToken = mutableStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sharedText.value = extractSharedText(intent)
        setContent {
            StickerApp(
                activity = this,
                initialSharedText = sharedText.value,
                focusedClipboardText = clipboardText.value,
                resumeToken = resumeToken.value,
            )
        }
    }

    override fun onResume() {
        super.onResume()
        resumeToken.value += 1
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        sharedText.value = extractSharedText(intent)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            clipboardText.value = readThreadsUrlFromClipboard()
        }
    }

    private fun readThreadsUrlFromClipboard(): String? {
        val prefs = getSharedPreferences("app_settings", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("clipboard_monitor", false)) return null

        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val raw = runCatching {
            if (!clipboard.hasPrimaryClip()) return null
            clipboard.primaryClip
                ?.getItemAt(0)
                ?.coerceToText(this)
                ?.toString()
                .orEmpty()
        }.getOrNull().orEmpty()

        return extractThreadsUrl(raw)?.takeUnless { isInternalSourceClipboardCopy(this, it) }
    }

    private fun extractSharedText(intent: Intent?): String? {
        if (intent == null) return null
        val raw = when (intent.action) {
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)
            Intent.ACTION_VIEW -> intent.dataString
            else -> intent.getStringExtra(Intent.EXTRA_TEXT) ?: intent.dataString
        }.orEmpty()
        return extractThreadsUrl(raw)
    }

    private fun extractThreadsUrl(raw: String): String? =
        Regex(
            "https?://(?:www\\.)?(?:threads\\.com|threads\\.net)/[^\\s]+",
            RegexOption.IGNORE_CASE
        ).find(raw)?.value?.trimEnd('.', ',', ')', ']', '}')
}
