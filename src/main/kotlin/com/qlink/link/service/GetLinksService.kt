package com.qlink.link.service

import com.qlink.common.error.BusinessException
import com.qlink.common.error.ErrorCode
import com.qlink.common.error.requireFalse
import com.qlink.common.error.requireTrue
import com.qlink.common.scroll.Base64CursorCodec
import com.qlink.common.scroll.DEFAULT_SCROLL_SIZE
import com.qlink.common.scroll.ScrollRequest
import com.qlink.common.scroll.ScrollResponse
import com.qlink.common.transaction.TransactionRunner
import com.qlink.folder.service.FolderAccessValidator
import com.qlink.link.domain.LinkOrderKey
import com.qlink.link.domain.LinkSort
import com.qlink.link.domain.MatchedField
import com.qlink.link.domain.SearchKeyword
import com.qlink.link.domain.Snippet
import com.qlink.link.dto.GetLinksContentResponse
import com.qlink.link.dto.HighlightRange
import com.qlink.link.dto.LinkSearchCursor
import com.qlink.link.dto.LinkSearchCursorValue
import com.qlink.link.dto.PublicFolderLinkResponse
import com.qlink.link.dto.SearchLinksQuery
import com.qlink.link.dto.SnippetResponse
import com.qlink.link.dto.TodoCountsResponse
import com.qlink.link.repository.LinkRepository
import com.qlink.todo.domain.Todo
import com.qlink.todo.repository.TodoRepository
import com.qlink.user.repository.UserRepository
import kotlin.time.Clock
import kotlin.time.Instant

class GetLinksService(
    private val tx: TransactionRunner,
    private val linkRepository: LinkRepository,
    private val todoRepository: TodoRepository,
    private val userRepository: UserRepository,
    private val folderAccessValidator: FolderAccessValidator,
) {
    suspend fun getMemberLinksOrNull(
        viewerId: Long,
        query: String?,
        folderId: Long?,
        isFavorite: Boolean?,
        orderKey: String?,
        order: String?,
        scrollRequest: ScrollRequest,
        isPublic: Boolean? = null,
    ): ScrollResponse<GetLinksContentResponse>? =
        tx.required {
            val folder = folderId?.takeUnless { it == 0L }?.let { folderAccessValidator.lockForRead(it) }
            if (folder == null || folderAccessValidator.canReadAsMember(folder, viewerId)) {
                readMemberLinks(
                    loginId = viewerId,
                    query = query,
                    folderId = folderId,
                    isFavorite = isFavorite,
                    orderKey = orderKey,
                    order = order,
                    scrollRequest = scrollRequest,
                    isPublic = isPublic,
                )
            } else {
                null
            }
        }

    suspend fun getPublicLinks(
        folderId: Long,
        query: String?,
        isFavorite: Boolean?,
        orderKey: String?,
        order: String?,
        scrollRequest: ScrollRequest,
        isPublic: Boolean? = null,
    ): ScrollResponse<PublicFolderLinkResponse> =
        tx.required {
            folderAccessValidator.lockForRead(folderId).isPublished.requireTrue(ErrorCode.FOLDER_NOT_FOUND)
            (query == null && isFavorite == null).requireTrue(ErrorCode.COMMON_INVALID_FILTER)
            readPublicLinks(folderId, orderKey, order, scrollRequest, isPublic)
        }

    private suspend fun readPublicLinks(
        folderId: Long,
        orderKey: String?,
        order: String?,
        scrollRequest: ScrollRequest,
        isPublic: Boolean?,
    ): ScrollResponse<PublicFolderLinkResponse> {
        val size = scrollRequest.size
        (size > 0).requireTrue(ErrorCode.COMMON_BAD_REQUEST)
        val sort = LinkSort.forPublicFolder(orderKey = orderKey, order = order)
        val cursor =
            scrollRequest.cursor?.let {
                val decoded = decodeCursor(encoded = it, sort = sort, hasKeyword = false)
                if (sort.orderKey == LinkOrderKey.CREATED_AT) {
                    decoded.value.createdAt ?: throw BusinessException(ErrorCode.COMMON_CURSOR_FIELD_MISSING)
                }
                decoded
            }
        val rows =
            if (isPublic == false) {
                emptyList()
            } else {
                linkRepository.findPublicByFolderId(
                    folderId = folderId,
                    sort = sort,
                    cursor = cursor,
                    limit = (size.toLong() + 1).coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                )
            }
        val hasNext = rows.size > size
        val page = rows.take(size)
        val nextCursor =
            if (hasNext) {
                val last = page.last()
                Base64CursorCodec.encode(
                    LinkSearchCursor(
                        orderKey = sort.orderKey,
                        direction = sort.direction,
                        value =
                            LinkSearchCursorValue(
                                id = last.id,
                                createdAt = last.createdAt.takeIf { sort.orderKey == LinkOrderKey.CREATED_AT },
                                title = last.title.takeIf { sort.orderKey == LinkOrderKey.TITLE },
                            ),
                    ),
                )
            } else {
                null
            }

        return ScrollResponse(
            isEmpty = page.isEmpty(),
            contents =
                page.map {
                    PublicFolderLinkResponse(
                        id = it.id,
                        title = it.title,
                        url = it.url,
                        thumbnailUrl = it.thumbnailUrl,
                        faviconUrl = it.faviconUrl,
                        isVideo = it.isVideo,
                        contentExcerpt = it.contentExcerpt,
                    )
                },
            nextCursor = nextCursor,
            hasNext = hasNext,
        )
    }

    private suspend fun readMemberLinks(
        loginId: Long,
        query: String?,
        folderId: Long?,
        isFavorite: Boolean?,
        orderKey: String?,
        order: String?,
        scrollRequest: ScrollRequest,
        isPublic: Boolean?,
    ): ScrollResponse<GetLinksContentResponse> {
        userRepository.emptyById(loginId).requireFalse(ErrorCode.LINK_OWNER_NOT_FOUND)

        val sort = LinkSort.of(orderKey = orderKey, order = order)
        val keyword = SearchKeyword.of(query).takeUnless { it.isBlank }
        val cursor =
            scrollRequest.cursor?.let { encoded ->
                val decoded = decodeCursor(encoded = encoded, sort = sort, hasKeyword = keyword != null)
                if (sort.orderKey == LinkOrderKey.CREATED_AT && decoded.value.createdAt == null) {
                    val anchor =
                        linkRepository
                            .search(
                                ownerId = loginId,
                                keyword = keyword,
                                folderId = folderId,
                                isFavorite = isFavorite,
                                sort = sort,
                                cursor = null,
                                size = 1,
                                isPublic = isPublic,
                                linkId = decoded.value.id,
                            ).singleOrNull() ?: throw BusinessException(ErrorCode.COMMON_CURSOR_MALFORMED)
                    decoded.copy(value = decoded.value.copy(createdAt = anchor.createdAt))
                } else {
                    decoded
                }
            }
        val size = scrollRequest.size.takeIf { it > 0 } ?: DEFAULT_SCROLL_SIZE
        val queries =
            linkRepository.search(
                ownerId = loginId,
                keyword = keyword,
                folderId = folderId,
                isFavorite = isFavorite,
                sort = sort,
                cursor = cursor,
                size = size,
                isPublic = isPublic,
            )
        val hasNext = queries.size > size
        val contents = queries.take(size)
        val todosByLinkId = todoRepository.findAllByLinkIds(contents.map { it.id }).groupBy { it.linkId }
        val now = Clock.System.now()

        return ScrollResponse(
            isEmpty = contents.isEmpty(),
            contents =
                contents.map { queryRow ->
                    GetLinksContentResponse(
                        id = queryRow.id,
                        folderId = queryRow.folderId,
                        folderName = queryRow.folderName,
                        folderEmoji = queryRow.folderEmoji,
                        workModel = queryRow.workModel,
                        url = queryRow.url,
                        title = queryRow.title,
                        thumbnailUrl = queryRow.thumbnailUrl,
                        faviconUrl = queryRow.faviconUrl,
                        isVideo = queryRow.isVideo,
                        status = queryRow.status,
                        tags = queryRow.tags,
                        createdAt = queryRow.createdAt,
                        todoCounts = todosByLinkId[queryRow.id].orEmpty().toCounts(now),
                        isFavorite = queryRow.favoriteAt != null,
                        contentExcerpt = queryRow.contentExcerpt,
                        matchedFields = queryRow.matchedFields(),
                        contentSnippet = keyword?.let { queryRow.toSnippetResponse(it) },
                        isPublic = queryRow.turnsPublicAt != null,
                    )
                },
            nextCursor =
                contents
                    .lastOrNull()
                    ?.takeIf { hasNext }
                    ?.let { encodeCursor(query = it, sort = sort, hasKeyword = keyword != null) },
            hasNext = hasNext,
        )
    }

    private fun decodeCursor(
        encoded: String,
        sort: LinkSort,
        hasKeyword: Boolean,
    ): LinkSearchCursor {
        val decoded = Base64CursorCodec.decode<LinkSearchCursor>(encoded)
        sort.validateCursor(orderKey = decoded.orderKey, direction = decoded.direction, order = decoded.order)
        validateCursorValue(value = decoded.value, orderKey = sort.orderKey, hasKeyword = hasKeyword)
        return decoded
    }

    private fun validateCursorValue(
        value: LinkSearchCursorValue,
        orderKey: LinkOrderKey,
        hasKeyword: Boolean,
    ) {
        if (hasKeyword) {
            value.isMetadataMatched ?: throw BusinessException(ErrorCode.COMMON_CURSOR_FIELD_MISSING)
            if (orderKey == LinkOrderKey.SIMILARITY) {
                value.contentScore ?: throw BusinessException(ErrorCode.COMMON_CURSOR_FIELD_MISSING)
            }
        }

        when (orderKey) {
            LinkOrderKey.CREATED_AT -> {
                value.id ?: throw BusinessException(ErrorCode.COMMON_CURSOR_FIELD_MISSING)
            }

            LinkOrderKey.TITLE -> {
                value.title ?: throw BusinessException(ErrorCode.COMMON_CURSOR_FIELD_MISSING)
                value.id ?: throw BusinessException(ErrorCode.COMMON_CURSOR_FIELD_MISSING)
            }

            LinkOrderKey.SIMILARITY -> {
                value.score ?: throw BusinessException(ErrorCode.COMMON_CURSOR_FIELD_MISSING)
                value.id ?: throw BusinessException(ErrorCode.COMMON_CURSOR_FIELD_MISSING)
            }
        }
    }

    private fun encodeCursor(
        query: SearchLinksQuery,
        sort: LinkSort,
        hasKeyword: Boolean,
    ): String {
        val bySimilarity = sort.orderKey == LinkOrderKey.SIMILARITY
        return Base64CursorCodec.encode(
            LinkSearchCursor(
                orderKey = sort.orderKey,
                direction = sort.direction,
                value =
                    LinkSearchCursorValue(
                        id = query.id,
                        createdAt = query.createdAt.takeIf { sort.orderKey == LinkOrderKey.CREATED_AT },
                        title = query.title.takeIf { sort.orderKey == LinkOrderKey.TITLE },
                        score = query.score.takeIf { bySimilarity },
                        titleScore = query.titleScore.takeIf { bySimilarity },
                        urlScore = query.urlScore.takeIf { bySimilarity },
                        tagsScore = query.tagsScore.takeIf { bySimilarity },
                        summaryScore = query.summaryScore.takeIf { bySimilarity },
                        memoScore = query.memoScore.takeIf { bySimilarity },
                        isMetadataMatched = query.isMetadataMatched.takeIf { hasKeyword },
                        contentScore = query.contentScore.takeIf { hasKeyword && bySimilarity },
                    ),
            ),
        )
    }

    private fun SearchLinksQuery.matchedFields(): List<MatchedField> =
        listOfNotNull(
            MatchedField.TITLE.takeIf { titleMatched },
            MatchedField.URL.takeIf { urlMatched },
            MatchedField.TAGS.takeIf { tagsMatched },
            MatchedField.SUMMARY.takeIf { summaryMatched },
            MatchedField.MEMO.takeIf { memoMatched },
            MatchedField.CONTENT.takeIf { contentMatched },
        )

    private fun SearchLinksQuery.toSnippetResponse(keyword: SearchKeyword): SnippetResponse? =
        snippet?.let { query ->
            val snippet =
                Snippet.of(
                    source = query.source,
                    headCut = query.headCut,
                    tailCut = query.tailCut,
                    keyword = keyword.value,
                    bodyLength = query.bodyLength,
                )
            SnippetResponse(
                text = snippet.text,
                highlights = snippet.highlights.map { HighlightRange(start = it.start, end = it.end) },
            )
        }

    private fun List<Todo>.toCounts(now: Instant): TodoCountsResponse =
        TodoCountsResponse(
            open = count { !it.isCompleted },
            dueSoon = count { it.isDueSoon(now) },
            overdue = count { it.isOverdue(now) },
            completed = count { it.isCompleted },
        )
}
