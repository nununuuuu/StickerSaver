package com.local.threadssticker

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

class AppStore(context: Context) {
    private val file = context.filesDir.resolve("sticker_store.json")

    @Synchronized
    fun loadStickers(): MutableList<StickerItem> {
        val root = readRoot()
        val arr = root.optJSONArray("stickers") ?: JSONArray()
        return buildList {
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val occurrencesJson = o.optJSONArray("occurrences") ?: JSONArray()
                val occurrences = buildList {
                    for (j in 0 until occurrencesJson.length()) {
                        val item = occurrencesJson.optJSONObject(j) ?: continue
                        val type = runCatching { MediaOriginType.valueOf(item.optString("type", "POST")) }
                            .getOrDefault(MediaOriginType.POST)
                        add(
                            MediaOccurrence(
                                type = type,
                                commentId = item.optString("commentId").takeIf { it.isNotBlank() },
                                commentAuthor = item.optString("commentAuthor").takeIf { it.isNotBlank() },
                                commentText = item.optString("commentText").takeIf { it.isNotBlank() },
                            )
                        )
                    }
                }.ifEmpty { listOf(MediaOccurrence(MediaOriginType.POST)) }
                add(
                    StickerItem(
                        id = o.getString("id"),
                        sourceUrl = o.getString("sourceUrl"),
                        mediaUrl = o.getString("mediaUrl"),
                        mimeType = o.optString("mimeType", "image/webp"),
                        localCachePath = o.optString("localCachePath").takeIf { it.isNotBlank() },
                        useCount = o.optInt("useCount", 0),
                        lastUsedAt = o.optLong("lastUsedAt").takeIf { it > 0 },
                        occurrences = occurrences,
                    )
                )
            }
        }.toMutableList()
    }

    @Synchronized
    fun loadSources(): MutableList<SourceRecord> {
        val root = readRoot()
        val arr = root.optJSONArray("sources") ?: JSONArray()
        return buildList {
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val ids = o.optJSONArray("stickerIds") ?: JSONArray()
                add(
                    SourceRecord(
                        id = o.getString("id"),
                        url = o.getString("url"),
                        note = o.optString("note"),
                        author = o.optString("author").takeIf { it.isNotBlank() },
                        snapshotPath = o.optString("snapshotPath").takeIf { it.isNotBlank() },
                        createdAt = o.optLong("createdAt", System.currentTimeMillis()),
                        lastUsedAt = o.optLong("lastUsedAt").takeIf { it > 0 },
                        stickerIds = buildList { for (j in 0 until ids.length()) add(ids.getString(j)) },
                    )
                )
            }
        }.toMutableList()
    }

    @Synchronized
    fun save(stickers: List<StickerItem>, sources: List<SourceRecord>) {
        val root = JSONObject()
        root.put("stickers", JSONArray().apply {
            stickers.forEach { s ->
                put(JSONObject().apply {
                    put("id", s.id)
                    put("sourceUrl", s.sourceUrl)
                    put("mediaUrl", s.mediaUrl)
                    put("mimeType", s.mimeType)
                    put("localCachePath", s.localCachePath ?: "")
                    put("useCount", s.useCount)
                    put("lastUsedAt", s.lastUsedAt ?: 0)
                    put("occurrences", JSONArray().apply {
                        s.occurrences.forEach { occurrence ->
                            put(JSONObject().apply {
                                put("type", occurrence.type.name)
                                put("commentId", occurrence.commentId ?: "")
                                put("commentAuthor", occurrence.commentAuthor ?: "")
                                put("commentText", occurrence.commentText ?: "")
                            })
                        }
                    })
                })
            }
        })
        root.put("sources", JSONArray().apply {
            sources.forEach { s ->
                put(JSONObject().apply {
                    put("id", s.id)
                    put("url", s.url)
                    put("note", s.note)
                    put("author", s.author ?: "")
                    put("snapshotPath", s.snapshotPath ?: "")
                    put("createdAt", s.createdAt)
                    put("lastUsedAt", s.lastUsedAt ?: 0)
                    put("stickerIds", JSONArray(s.stickerIds))
                })
            }
        })
        file.writeText(root.toString())
    }

    private fun readRoot(): JSONObject = runCatching {
        if (!file.exists()) JSONObject() else JSONObject(file.readText())
    }.getOrDefault(JSONObject())

    companion object {
        fun stableId(value: String): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
            return digest.take(12).joinToString("") { "%02x".format(it) }
        }
    }
}
