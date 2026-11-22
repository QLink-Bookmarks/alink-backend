package com.qlink.link.service

import com.qlink.common.error.BusinessException
import com.qlink.common.error.ErrorCode
import com.qlink.common.error.requireFalse
import com.qlink.common.error.requireTrue
import com.qlink.common.response.ReadResult
import com.qlink.common.scroll.Base64CursorCodec
import com.qlink.common.scroll.DEFAULT_SCROLL_SIZE
import com.qlink.common.scroll.EncryptedCursorCodec
import com.qlink.common.scroll.ScrollRequest
import com.qlink.common.scroll.ScrollResponse
import com.qlink.common.transaction.TransactionRunner
import com.qlink.folder.domain.PublicFolderSort
import com.qlink.folder.dto.PublicFolderCursor
import com.qlink.folder.service.FolderAccessValidator
import com.qlink.folder.service.PublicFolderCursorCodec
import com.qlink.link.domain.LinkOrderKey
import com.qlink.link.domain.LinkSort
import com.qlink.link.domain.MatchedField
import com.qlink.link.domain.SearchKeyword
import com.qlink.link.domain.Snippet
import com.qlink.link.dto.GetLinksContentResponse
import com.qlink.link.dto.HighlightRange
import com.qlink.link.dto.LinkSearchCursor
import com.qlink.link.dto.LinkSearchCursorScope
import com.qlink.link.dto.LinkSearchCursorValue
import com.qlink.link.dto.PublicFolderLinkResponse
import com.qlink.link.dto.PublicLinkCursor
import com.qlink.link.dto.ScopedLinkSearchCursor
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
    private val cursorCodec: PublicFolderCursorCodec,
    private val encryptedCursorCodec: EncryptedCursorCodec,
) {
    suspend fun getLinks(
        viewerId: Long?,
        query: String?,
        folderId: Long?,
        isFavorite: Boolean?,
        orderKey: String?,
        order: String?,
        scrollRequest: ScrollRequest,
        isPublic: Boolean? = null,
    ): ReadResult<ScrollResponse<GetLinksContentResponse>, ScrollResponse<PublicFolderLinkResponse>> =
        tx.required {
            val folder = folderId?.takeUnless { it == 0L }?.let { folderAccessValidator.lockForRead(it) }
            if (viewerId != null && (folder == null || folderAccessValidator.canReadAsMember(folder, viewerId))) {
                ReadResult.Member(
                    readMemberLinks(
                        loginId = viewerId,
                        query = query,
                        folderId = folderId,
                        isFavorite = isFavorite,
                        orderKey = orderKey,
                        order = order,
                        scrollRequest = scrollRequest,
                        isPublic = isPublic,
                    ),
                )
            } else {
                val publicFolderId = folder?.id ?: throw BusinessException(ErrorCode.AUTH_ACCESS_TOKEN_MISSING)
                folder.isPublished.requireTrue(ErrorCode.FOLDER_NOT_FOUND)
                (query == null && isFavorite == null).requireTrue(ErrorCode.COMMON_INVALID_FILTER)
                ReadResult.Guest(readPublicLinks(publicFolderId, orderKey, order, scrollRequest, isPublic))
            }
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
        val sort = PublicFolderSort.from(orderKey = orderKey, order = order)
        val cursor =
            scrollRequest.cursor?.let {
                val decoded = cursorCodec.decode(encoded = it, folderId = folderId, collection = "links", sort = sort, isPublic = isPublic)
                PublicLinkCursor(
                    id = decoded.id ?: throw BusinessException(ErrorCode.COMMON_CURSOR_FIELD_MISSING),
                    createdAt = decoded.createdAt ?: throw BusinessException(ErrorCode.COMMON_CURSOR_FIELD_MISSING),
                )
            }
        val rows =
            if (isPublic == false) {
                emptyList()
            } else {
                linkRepository.findPublicByFolderId(
                    folderId = folderId,
                    direction = sort.direction,
                    cursor = cursor,
                    limit = (size.toLong() + 1).coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                )
            }
        val hasNext = rows.size > size
        val page = rows.take(size)
        val nextCursor =
            if (hasNext) {
                val last = page.last()
                cursorCodec.encode(
                    PublicFolderCursor(
                        folderId = folderId,
                        collection = "links",
                        direction = sort.direction,
                        id = last.id,
                        createdAt = last.createdAt,
                        isPublic = isPublic,
                    ),
                )
            } else {
                null
            }

        return ScrollResponse(
            isEmpty = page.isEmpty(),
            contents = page.map { PublicFolderLinkResponse(id = it.id, title = it.title, url = it.url) },
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
        val scope =
            LinkSearchCursorScope(
                viewerId = loginId,
                folderId = folderId,
                keyword = keyword?.value,
                isFavorite = isFavorite,
                isPublic = isPublic,
            )

        val cursor =
            scrollRequest.cursor?.let { decodeCursor(encoded = it, sort = sort, scope = scope, keyword = keyword) }
        val size = scrollRequest.size.takeIf { it > 0 } ?: DEFAULT_SCROLL_SIZE
        val queries = search(scope = scope, keyword = keyword, sort = sort, size = size, cursor = cursor)
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
                    ?.let { encodeCursor(query = it, sort = sort, scope = scope) },
            hasNext = hasNext,
        )
    }

    private suspend fun search(
        scope: LinkSearchCursorScope,
        keyword: SearchKeyword?,
        sort: LinkSort,
        size: Int,
        cursor: LinkSearchCursor? = null,
        linkId: Long? = null,
    ): List<SearchLinksQuery> =
        linkRepository.search(
            ownerId = scope.viewerId,
            keyword = keyword,
            folderId = scope.folderId,
            isFavorite = scope.isFavorite,
            sort = sort,
            cursor = cursor,
            size = size,
            isPublic = scope.isPublic,
            linkId = linkId,
        )

    private suspend fun decodeCursor(
        encoded: String,
        sort: LinkSort,
        scope: LinkSearchCursorScope,
        keyword: SearchKeyword?,
    ): LinkSearchCursor {
        val legacy = ':' !in encoded
        val cursor =
            if (legacy) {
                (scope.isPublic == null).requireTrue(ErrorCode.COMMON_CURSOR_MALFORMED)
                Base64CursorCodec.decode<LinkSearchCursor>(encoded)
            } else {
                val scoped =
                    encryptedCursorCodec.decode<ScopedLinkSearchCursor>(
                        encoded = encoded,
                        errorCode = ErrorCode.COMMON_CURSOR_MALFORMED,
                    )
                (scoped.scope == scope).requireTrue(ErrorCode.COMMON_CURSOR_MALFORMED)
                scoped.cursor
            }
        sort.validateCursor(orderKey = cursor.orderKey, direction = cursor.direction, order = cursor.order)
        validateCursorValue(value = cursor.value, orderKey = sort.orderKey, hasKeyword = scope.keyword != null)

        if (!legacy) return cursor
        val row =
            search(scope = scope, keyword = keyword, sort = sort, size = 1, linkId = cursor.value.id!!).singleOrNull()
                ?: throw BusinessException(ErrorCode.COMMON_CURSOR_MALFORMED)
        return if (sort.orderKey == LinkOrderKey.CREATED_AT && cursor.value.createdAt == null) {
            cursor.copy(value = cursor.value.copy(createdAt = row.createdAt))
        } else {
            cursor
        }
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
        scope: LinkSearchCursorScope,
    ): String =
        encryptedCursorCodec.encode(
            ScopedLinkSearchCursor(
                scope = scope,
                cursor =
                    LinkSearchCursor(
                        orderKey = sort.orderKey,
                        direction = sort.direction,
                        value =
                            LinkSearchCursorValue(
                                id = query.id,
                                createdAt = query.createdAt,
                                title = query.title,
                                score = query.score,
                                titleScore = query.titleScore,
                                urlScore = query.urlScore,
                                tagsScore = query.tagsScore,
                                summaryScore = query.summaryScore,
                                memoScore = query.memoScore,
                                isMetadataMatched = query.isMetadataMatched.takeIf { scope.keyword != null },
                                contentScore = query.contentScore.takeIf { scope.keyword != null },
                            ),
                    ),
            ),
        )

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
