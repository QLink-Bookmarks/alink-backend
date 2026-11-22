@file:Suppress("ktlint:standard:filename")

package com.qlink.link.dto

import com.qlink.common.search.SortCursor
import com.qlink.link.domain.LinkOrderKey
import com.qlink.link.domain.LinkStatus
import com.qlink.link.domain.SourceType
import com.qlink.link.repository.table.Links
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.ResultRow
import kotlin.time.Instant
import kotlin.time.toKotlinInstant

data class PublicLinkDetailQuery(
    val id: Long,
    val folderId: Long,
    val title: String,
    val url: String,
    val folderName: String,
    val folderEmoji: String?,
    val todos: List<PublicLinkTodoQuery>,
)

data class PublicLinkTodoQuery(
    val title: String,
    val isCompleted: Boolean,
)

data class PublicLinkQuery(
    val id: Long,
    val createdAt: Instant,
    val title: String,
    val url: String,
)

data class PublicLinkCursor(
    val id: Long,
    val createdAt: Instant,
)

fun ResultRow.toPublicLinkQuery(): PublicLinkQuery =
    PublicLinkQuery(
        id = this[Links.id],
        createdAt = this[Links.createdAt].toKotlinInstant(),
        title = this[Links.title],
        url = this[Links.url],
    )

data class SearchLinksQuery(
    val id: Long,
    val folderId: Long?,
    val folderName: String?,
    val folderEmoji: String?,
    val workModel: String?,
    val url: String,
    val title: String,
    val thumbnailUrl: String?,
    val faviconUrl: String?,
    val isVideo: Boolean,
    val status: LinkStatus,
    val tags: List<String>,
    val createdAt: Instant,
    val favoriteAt: Instant?,
    val turnsPublicAt: Instant?,
    val score: Double,
    val titleScore: Double,
    val urlScore: Double,
    val tagsScore: Double,
    val summaryScore: Double,
    val memoScore: Double,
    val contentExcerpt: String?,
    val isMetadataMatched: Boolean,
    val titleMatched: Boolean,
    val urlMatched: Boolean,
    val tagsMatched: Boolean,
    val summaryMatched: Boolean,
    val memoMatched: Boolean,
    val contentMatched: Boolean,
    val contentScore: Double,
    val snippet: LinkSnippetQuery?,
)

data class LinkSnippetQuery(
    val linkId: Long,
    val source: String,
    val headCut: Boolean,
    val tailCut: Boolean,
    val bodyLength: Int,
)

data class LinkDetailQuery(
    val id: Long,
    val ownerId: Long,
    val folderId: Long?,
    val folderSharedAt: Instant?,
    val folderMemberUserId: Long?,
    val folderName: String?,
    val folderEmoji: String?,
    val url: String,
    val title: String,
    val thumbnailUrl: String?,
    val faviconUrl: String?,
    val isVideo: Boolean,
    val summary: String?,
    val memo: String?,
    val tags: List<String>,
    val sourceType: SourceType,
    val status: LinkStatus,
    val workModelId: Long?,
    val workModel: String?,
    val createdAt: Instant,
    val favoriteAt: Instant?,
    val hasContent: Boolean,
    val contentExcerpt: String?,
    val isContentPending: Boolean,
    val turnsPublicAt: Instant?,
)

typealias LinkSearchCursor = SortCursor<LinkOrderKey, LinkSearchCursorValue>

@Serializable
data class LinkSearchCursorScope(
    val viewerId: Long,
    val folderId: Long?,
    val keyword: String?,
    val isFavorite: Boolean?,
    val isPublic: Boolean?,
)

@Serializable
data class ScopedLinkSearchCursor(
    val scope: LinkSearchCursorScope,
    val cursor: LinkSearchCursor,
)

@Serializable
data class LinkSearchCursorValue(
    val id: Long? = null,
    val createdAt: Instant? = null,
    val title: String? = null,
    val score: Double? = null,
    val titleScore: Double? = null,
    val urlScore: Double? = null,
    val tagsScore: Double? = null,
    val summaryScore: Double? = null,
    val memoScore: Double? = null,
    val isMetadataMatched: Boolean? = null,
    val contentScore: Double? = null,
)
