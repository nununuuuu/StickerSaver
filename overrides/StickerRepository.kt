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

    private val _categories = MutableStateFlow(store.loadCategories().toList())
    val categories: StateFlow<List<StickerCategory>> = _categories.asStateFlow()

    init {
        pruneUnusedCategories()
    }

    suspend fun parseAndSave(
        url: String,
        options: ParseOptions = ParseOptions(),
        categoryNames: List<String> = emptyList(),
        cancelRequested: AtomicBoolean = AtomicBoolean(false),
        onProgress: (ParseProgress) -> Unit = {},
    ): ParseResult {
        val categoryIds = ensureCategories(categoryNames)
        val result = try {
            parser.parse(
                inputUrl = url,
                options = options,
                isCancellationRequested = { cancelRequested.get() },
                onTaskCompleted = { partial, progress ->
                    mergeParsedMedia(partial.sourceUrl, partial.author, partial.postText, partial.media, categoryIds)
                    onProgress(progress)
                }
            )
        } catch (t: Throwable) {
            pruneUnusedCategories()
            throw t
        }
        cacheParsedMedia(result.media)
        pruneUnusedCategories()
        return result
    }

    private fun mergeParsedMedia(
        sourceUrl: String,
        author: String?,
        postText: String?,
        media: List<ParsedMedia>,
        categoryIds: List<String>,
    ) {
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
                    categoryIds = categoryIds,
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
                    categoryIds = (old.categoryIds + categoryIds).distinct(),
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
        val targetUrls = media
            .map { it.url.substringBefore('?').substringBefore('#') }
            .toSet()

        val updated = _stickers.value.toMutableList()
        var changed = false
        updated.indices.forEach { index ->
            val sticker = updated[index]
            val canonical = sticker.mediaUrl.substringBefore('?').substringBefore('#')
            if (canonical !in targetUrls) return@forEach
            val file = runCatching { cache.ensureCached(sticker) }.getOrNull() ?: return@forEach
            if (sticker.localCachePath != file.absolutePath) {
                updated[index] = sticker.copy(localCachePath = file.absolutePath)
                changed = true
            }
        }
        if (changed) publish(updated, _sources.value)
    }

    fun ensureCategories(names: List<String>): List<String> {
        val cleaned = names.map { it.trim() }.filter { it.isNotBlank() }.distinctBy { it.lowercase() }
        if (cleaned.isEmpty()) return emptyList()

        val updated = _categories.value.toMutableList()
        val ids = cleaned.map { name ->
            val existing = updated.firstOrNull { it.name.equals(name, ignoreCase = true) }
            if (existing != null) {
                existing.id
            } else {
                val category = StickerCategory(
                    id = AppStore.stableId("category|" + name.lowercase()),
                    name = name,
                )
                updated += category
                category.id
            }
        }
        if (updated != _categories.value) {
            _categories.value = updated
            persist()
        }
        return ids.distinct()
    }

    fun updateStickerCategories(stickerId: String, selectedCategoryIds: List<String>, newCategoryNames: List<String>) {
        val newIds = ensureCategories(newCategoryNames)
        val validIds = (_categories.value.map { it.id }.toSet())
        val finalIds = (selectedCategoryIds + newIds).filter { it in validIds }.distinct()
        val updated = _stickers.value.map {
            if (it.id == stickerId) it.copy(categoryIds = finalIds) else it
        }
        publish(updated, _sources.value)
    }

    fun deleteCategory(categoryId: String) {
        _categories.value = _categories.value.filterNot { it.id == categoryId }
        val updated = _stickers.value.map { sticker ->
            if (categoryId in sticker.categoryIds) sticker.copy(categoryIds = sticker.categoryIds - categoryId) else sticker
        }
        _stickers.value = updated
        persist()
    }

    fun renameCategory(categoryId: String, newName: String) {
        val clean = newName.trim()
        if (clean.isBlank()) return
        val conflict = _categories.value.firstOrNull {
            it.id != categoryId && it.name.equals(clean, ignoreCase = true)
        }
        if (conflict != null) {
            val updatedStickers = _stickers.value.map { sticker ->
                if (categoryId in sticker.categoryIds) {
                    sticker.copy(categoryIds = (sticker.categoryIds - categoryId + conflict.id).distinct())
                } else sticker
            }
            _categories.value = _categories.value.filterNot { it.id == categoryId }
            _stickers.value = updatedStickers
        } else {
            _categories.value = _categories.value.map {
                if (it.id == categoryId) it.copy(name = clean) else it
            }
        }
        persist()
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

    fun removeSource(sourceId: String) {
        val source = _sources.value.firstOrNull { it.id == sourceId } ?: return
        val removed = _stickers.value.filter { it.sourceUrl == source.url || it.id in source.stickerIds }
        removed.forEach { sticker ->
            sticker.localCachePath?.let { runCatching { File(it).delete() } }
        }
        source.snapshotPath?.let { runCatching { File(it).delete() } }

        val stickers = _stickers.value.filterNot { item ->
            item.sourceUrl == source.url || item.id in source.stickerIds
        }
        val sources = _sources.value.filterNot { it.id == sourceId }
        publish(stickers, sources)
    }

    fun recentStickers(): List<StickerItem> =
        _stickers.value.sortedByDescending { it.lastUsedAt ?: 0L }

    fun sourceForSticker(id: String): SourceRecord? {
        val sticker = _stickers.value.firstOrNull { it.id == id } ?: return null
        return _sources.value.firstOrNull { it.url == sticker.sourceUrl }
    }

    private fun publish(stickers: List<StickerItem>, sources: List<SourceRecord>) {
        _stickers.value = stickers
        _sources.value = sources
        pruneUnusedCategories(persistAfter = false)
        persist()
    }

    private fun pruneUnusedCategories(persistAfter: Boolean = true) {
        val usedIds = _stickers.value
            .flatMap { it.categoryIds }
            .toSet()
        val cleaned = _categories.value.filter { it.id in usedIds }
        if (cleaned != _categories.value) {
            _categories.value = cleaned
            if (persistAfter) persist()
        }
    }

    private fun persist() {
        store.save(_stickers.value, _sources.value, _categories.value)
    }
}