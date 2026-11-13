package com.qlink.post.service

import com.qlink.common.error.BusinessException
import com.qlink.common.error.ErrorCode
import com.qlink.common.scroll.Base64CursorCodec
import com.qlink.common.scroll.ScrollRequest
import com.qlink.common.scroll.ScrollResponse
import com.qlink.common.transaction.TransactionRunner
import com.qlink.post.domain.PostSort
import com.qlink.post.domain.PostType
import com.qlink.post.dto.DEFAULT_POST_SCROLL_SIZE
import com.qlink.post.dto.DEFAULT_POST_TYPE
import com.qlink.post.dto.GetPostsContentResponse
import com.qlink.post.dto.PostSearchCursor
import com.qlink.post.dto.PostSearchCursorValue
import com.qlink.post.dto.SearchPostsQuery
import com.qlink.post.repository.PostRepository
import com.qlink.user.repository.UserRepository

class GetPostsService(
    private val tx: TransactionRunner,
    private val postRepository: PostRepository,
    private val userRepository: UserRepository,
) {
    suspend fun getPosts(
        loginId: Long?,
        type: String?,
        query: String?,
        orderKey: String? = null,
        order: String? = null,
        scrollRequest: ScrollRequest,
    ): ScrollResponse<GetPostsContentResponse> =
        tx.readOnly {
            val postType = PostType.from(type ?: DEFAULT_POST_TYPE)
            val user = loginId?.let { userRepository.findById(it) }
            postType.validateReadable(user?.role?.isAdmin == true)

            val sort = PostSort.of(orderKey = orderKey, order = order)
            val cursor = scrollRequest.cursor?.let { decodeCursor(encoded = it, sort = sort) }
            val size = scrollRequest.size.takeIf { it > 0 } ?: DEFAULT_POST_SCROLL_SIZE
            val queries =
                postRepository.search(
                    type = postType,
                    query = query,
                    sort = sort,
                    cursor = cursor,
                    size = size,
                )
            val hasNext = queries.size > size
            val contents = queries.take(size)

            ScrollResponse(
                isEmpty = contents.isEmpty(),
                contents = contents.map { it.toResponse() },
                nextCursor = contents.lastOrNull()?.takeIf { hasNext }?.let { encodeCursor(query = it, type = postType, sort = sort) },
                hasNext = hasNext,
            )
        }

    private fun decodeCursor(
        encoded: String,
        sort: PostSort,
    ): PostSearchCursor {
        val cursor = Base64CursorCodec.decode<PostSearchCursor>(encoded)
        sort.validateCursor(orderKey = cursor.orderKey, direction = cursor.direction, order = cursor.order)
        cursor.value.id ?: throw BusinessException(ErrorCode.COMMON_CURSOR_FIELD_MISSING)

        return cursor
    }

    private fun encodeCursor(
        query: SearchPostsQuery,
        type: PostType,
        sort: PostSort,
    ): String =
        Base64CursorCodec.encode(
            PostSearchCursor(
                orderKey = sort.orderKey,
                direction = sort.direction,
                value =
                    PostSearchCursorValue(
                        id = query.id,
                        type = type,
                        title = query.title,
                    ),
            ),
        )

    private fun SearchPostsQuery.toResponse(): GetPostsContentResponse =
        GetPostsContentResponse(
            id = id,
            title = title,
            author = author,
            createdAt = createdAt,
        )
}
