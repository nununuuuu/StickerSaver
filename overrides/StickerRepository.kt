package com.local.threadssticker

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

class StickerRepository(private val context: Context) {
    private val store = AppStore(context)
    private val cache = StickerCache(context)
    private val parser = ThreadsParser()

    private val _stickers = MutableStateFlow(store.loadStickers().toList())
    val stickers: StateFlow<List<StickerItem>> = _stickers.asStateFlow()

    private val _sources = MutableStateFlow(store.loadSources().toList())
    val sources: StateFlow<List<SourceRecord>> = _sources.asStateFlow()

    suspend fun parseAndSave(
        url: String,
        options: ParseOptions = ParseOptions(),
        cancelRequested: AtomicBoolean = AtomicBoolean(false),
        onProgress: (ParseProgress) -> Unit = {},
    ): ParseResult {
        val result = parser.parse(
            inputUrl = url,
            options = options,
            isCancellationRequested = { cancelRequested.get() },
            onTaskCompleted = { partial, progress ->
                mergeParsedMedia(partial.sourceUrl, partial.author, partial.postText, partial.media)
                onProgress(progress)
            }
        )
        cacheParsedMedia(result.media)
        return result
    }

    private fun mergeParsedMedia(sourceUrl: String, author: String?, postText: String?, media: List<ParsedMedia>) {
        val stickerList = _stickers.value.toMutableList()
        val ids = mutableListOf<String>()

        media.forEach { parsed ->
            val canonicalKey = parsed.url.substringBefore('?').substringBefore('#')
            val id = AppStore.stableId(sourceUrl + "|" + canonicalKey)
            ids += id

            val index = stickerList.indexOfFirst { it.id == id }
            if (index < 0) {
                stickerList += StickerItem(
                    id = id,
                    sourceUrl = sourceUrl,
                    mediaUrl = parsed.url,
                    mimeType = parsed.mimeType,
                    occurrences = listOf(parsed.occurrence),
                )
            } else {
                val old = stickerList[index]
                val mergedOccurrences = (old.occurrences + parsed.occurrence).distinctBy {
                    it.type.name + "|" + it.commentId.orEmpty() + "|" + it.commentAuthor.orEmpty()
                }
                stickerList[index] = old.copy(
                    mediaUrl = parsed.url,
                    mimeType = parsed.mimeType,
                    occurrences = mergedOccurrences,
                )
            }
        }

        val sourceList = _sources.value.toMutableList()
        val sourceId = AppStore.stableId(sourceUrl)
        val old = sourceList.firstOrNull { it.id == sourceId }
        val preferredThumbnail = media.firstOrNull { it.occurrence.type == MediaOriginType.POST }?.url
            ?: media.firstOrNull()?.url
            ?: old?.thumbnailUrl
        val replacement = (old ?: SourceRecord(id = sourceId, url = sourceUrl)).copy(
            author = author ?: old?.author,
            postText = postText ?: old?.postText,
            thumbnailUrl = preferredThumbnail,
            stickerIds = (old?.stickerIds.orEmpty() + ids).distinct(),
        )

        sourceList.removeAll { it.id == sourceId }
        sourceList.add(0, replacement)
        publish(stickerList, sourceList)
    }

    private suspend fun cacheParsedMedia(media: List<ParsedMedia>) {
        if (media.isEmpty()) return
        val mediaIds = media.map { parsed ->
            val canonicalKey = parsed.url.substringBefore('?').substringBefore('#')
            AppStore.stableId(parsed.occurrence.let { media.firstOrNull()?.occurrence }; "")
        }
        val targetIds = media.map { parsed ->
            val canonicalKey = parsed.url.substringBefore('?').substringBefore('#')
            val sourceUrl = _stickers.value.firstOrNull { it.mediaUrl.substringBefore('?') == parsed.url.substringBefore('?') }?.sourceUrl
            if (sourceUrl == null) null else AppStore.stableId(sourceUrl + "|" + canonicalKey)
        }.filterNotNull().toSet()

        if (targetIds.isEmpty()) return
        val updated = _stickers.value.toMutableList()
        var changed = false
        updated.indices.forEach { index ->
            val sticker = updated[index]
            if (sticker.id !in targetIds) return@forEach
            val file = runCatching { cache.ensureCached(sticker) }.getOrNull() ?: return@forEach
            if (sticker.localCachePath != file.absolutePath) {
                updated[index] = sticker.copy(localCachePath = file.absolutePath)
                changed = true
            }
        }
        if (changed) publish(updated, _sources.value)
    }

    fun updateNote(sourceId: String, note: String) {
        val updated = _sources.value.map {
            if (it.id == sourceId) it.copy(note = note) else it
        }
        publish(_stickers.value, updated)
    }

    fun attachSnapshot(sourceId: String, path: String?) {
        val updated = _sources.value.map {
            if (it.id == sourceId) it.copy(snapshotPath = path) else it
        }
        publish(_stickers.value, updated)
    }

    suspend fun markUsedAndCache(stickerId: String): File {
        val current = _stickers.value.first { it.id == stickerId }
        val file = cache.ensureCached(current)
        val now = System.currentTimeMillis()

        val updatedStickers = _stickers.value.map {
            if (it.id == stickerId) {
                it.copy(
                    localCachePath = file.absolutePath,
                    useCount = it.useCount + 1,
                    lastUsedAt = now
                )
            } else it
        }

        val updatedSources = _sources.value.map {
            if (it.url == current.sourceUrl) it.copy(lastUsedAt = now) else it
        }

        publish(updatedStickers, updatedSources)
        return file
    }


    fun removeSticker(stickerId: String) {
        val target = _stickers.value.firstOrNull { it.id == stickerId } ?: return
        target.localCachePath?.let { runCatching { File(it).delete() } }
        val stickers = _stickers.value.filterNot { it.id == stickerId }
        val sources = _sources.value.map { source ->
            if (stickerId in source.stickerIds) source.copy(stickerIds = source.stickerIds - stickerId) else source
        }
        publish(stickers, sources)
    }

    fun clearStickerCache() {
        cache.clear()
        publish(_stickers.value.map { it.copy(localCachePath = null) }, _sources.value)
    }

    fun cacheSizeBytes(): Long = cache.sizeBytes()

    fun recentStickers(): List<StickerItem> =
        _stickers.value.sortedByDescending { it.lastUsedAt ?: 0L }

    fun sourceForSticker(id: String): SourceRecord? {
        val sticker = _stickers.value.firstOrNull { it.id == id } ?: return null
        return _sources.value.firstOrNull { it.url == sticker.sourceUrl }
    }

    private fun publish(stickers: List<StickerItem>, sources: List<SourceRecord>) {
        _stickers.value = stickers
        _sources.value = sources
        store.save(stickers, sources)
    }
}
