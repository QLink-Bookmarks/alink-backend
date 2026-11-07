package com.qlink.folder.service

import com.qlink.common.error.BusinessException
import com.qlink.common.error.ErrorCode
import com.qlink.common.error.requireFalse
import com.qlink.common.scroll.Base64CursorCodec
import com.qlink.common.scroll.DEFAULT_SCROLL_SIZE
import com.qlink.common.scroll.ScrollRequest
import com.qlink.common.scroll.ScrollResponse
import com.qlink.common.transaction.TransactionRunner
import com.qlink.folder.domain.FolderOrderKey
import com.qlink.folder.domain.FolderSort
import com.qlink.folder.dto.FolderSearchCursor
import com.qlink.folder.dto.FolderSearchCursorValue
import com.qlink.folder.dto.GetFoldersContentResponse
import com.qlink.folder.dto.SearchFoldersQuery
import com.qlink.folder.repository.FolderRepository
import com.qlink.user.repository.UserRepository

class GetFoldersService(
    private val tx: TransactionRunner,
    private val folderRepository: FolderRepository,
    private val userRepository: UserRepository,
) {
    suspend fun getFolders(
        loginId: Long,
        query: String?,
        orderKey: String? = null,
        order: String? = null,
        scrollRequest: ScrollRequest,
    ): ScrollResponse<GetFoldersContentResponse> =
        tx.readOnly {
            userRepository.emptyById(loginId).requireFalse(ErrorCode.FOLDER_OWNER_NOT_FOUND)

            val sort = FolderSort.of(orderKey = orderKey, order = order)
            val cursor = scrollRequest.cursor?.let { decodeCursor(encoded = it, sort = sort) }
            val size = scrollRequest.size.takeIf { it > 0 } ?: DEFAULT_SCROLL_SIZE
            val queries =
                folderRepository.search(
                    ownerId = loginId,
                    query = query,
                    sort = sort,
                    cursor = cursor,
                    size = size,
                )
            val hasNext = queries.size > size
            val contents = queries.take(size)

            ScrollResponse(
                isEmpty = contents.isEmpty(),
                contents =
                    contents.map { folder ->
                        GetFoldersContentResponse(
                            id = folder.id,
                            ownerId = folder.ownerId,
                            ownerNickname = folder.ownerNickname,
                            name = folder.name,
                            emoji = folder.emoji,
                            isShared = folder.sharedAt != null,
                            shareCounts = folder.shareCounts,
                            linkCounts = folder.linkCounts,
                            myLinkCounts = folder.myLinkCounts,
                        )
                    },
                nextCursor = contents.lastOrNull()?.takeIf { hasNext }?.let { encodeCursor(query = it, sort = sort) },
                hasNext = hasNext,
            )
        }

    private suspend fun decodeCursor(
        encoded: String,
        sort: FolderSort,
    ): FolderSearchCursor {
        val cursor = Base64CursorCodec.decode<FolderSearchCursor>(encoded)
        sort.validateCursor(orderKey = cursor.orderKey, direction = cursor.direction, order = cursor.order)
        validateCursorValue(value = cursor.value, orderKey = sort.orderKey)

        return cursor.withCreatedAt(sort.orderKey)
    }

    private fun validateCursorValue(
        value: FolderSearchCursorValue,
        orderKey: FolderOrderKey,
    ) {
        when (orderKey) {
            FolderOrderKey.CREATED_AT -> {
                value.id ?: throw BusinessException(ErrorCode.COMMON_CURSOR_FIELD_MISSING)
            }

            FolderOrderKey.NAME -> {
                value.name ?: throw BusinessException(ErrorCode.COMMON_CURSOR_FIELD_MISSING)
                value.id ?: throw BusinessException(ErrorCode.COMMON_CURSOR_FIELD_MISSING)
            }

            FolderOrderKey.SIMILARITY -> {
                value.score ?: throw BusinessException(ErrorCode.COMMON_CURSOR_FIELD_MISSING)
                value.id ?: throw BusinessException(ErrorCode.COMMON_CURSOR_FIELD_MISSING)
            }
        }
    }

    private suspend fun FolderSearchCursor.withCreatedAt(orderKey: FolderOrderKey): FolderSearchCursor =
        when {
            orderKey != FolderOrderKey.CREATED_AT || value.createdAt != null -> {
                this
            }

            else -> {
                folderRepository
                    .findCreatedAtById(value.id!!)
                    ?.let { copy(value = value.copy(createdAt = it)) }
                    ?: throw BusinessException(ErrorCode.COMMON_CURSOR_MALFORMED)
            }
        }

    private fun encodeCursor(
        query: SearchFoldersQuery,
        sort: FolderSort,
    ): String =
        Base64CursorCodec.encode(
            FolderSearchCursor(
                orderKey = sort.orderKey,
                direction = sort.direction,
                value =
                    FolderSearchCursorValue(
                        id = query.id,
                        createdAt = query.createdAt,
                        name = query.name,
                        score = query.score,
                    ),
            ),
        )
}
