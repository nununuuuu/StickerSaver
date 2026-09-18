package com.local.threadssticker

enum class MediaOriginType { POST, COMMENT }
enum class CommentLoadMode { TOP, ALL }

data class ParseOptions(
    val parsePost: Boolean = true,
    val parseComments: Boolean = false,
    val commentLoadMode: CommentLoadMode = CommentLoadMode.TOP,
    val topCommentCount: Int = 5,
)

data class MediaOccurrence(
    val type: MediaOriginType,
    val commentId: String? = null,
    val commentAuthor: String? = null,
    val commentText: String? = null,
)

data class StickerItem(
    val id: String,
    val sourceUrl: String,
    val mediaUrl: String,
    val mimeType: String,
    val localCachePath: String? = null,
    val useCount: Int = 0,
    val lastUsedAt: Long? = null,
    val occurrences: List<MediaOccurrence> = emptyList(),
)

data class SourceRecord(
    val id: String,
    val url: String,
    val note: String = "",
    val author: String? = null,
    val snapshotPath: String? = null,
    val postText: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val lastUsedAt: Long? = null,
    val stickerIds: List<String> = emptyList(),
)

data class ParseResult(
    val sourceUrl: String,
    val author: String?,
    val postText: String?,
    val media: List<ParsedMedia>,
    val completedTasks: Int,
    val plannedTasks: Int,
    val cancelled: Boolean,
) {
    val postMedia: List<ParsedMedia> get() = media.filter { it.occurrence.type == MediaOriginType.POST }
    val commentMedia: List<ParsedMedia> get() = media.filter { it.occurrence.type == MediaOriginType.COMMENT }
}

data class ParsedMedia(
    val url: String,
    val mimeType: String,
    val occurrence: MediaOccurrence,
)

data class ParseProgress(
    val completedTasks: Int,
    val plannedTasks: Int,
    val currentLabel: String,
    val postMediaCount: Int,
    val commentMediaCount: Int,
)
