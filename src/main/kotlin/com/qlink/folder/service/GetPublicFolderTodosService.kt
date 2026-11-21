package com.qlink.folder.service

import com.qlink.common.error.BusinessException
import com.qlink.common.error.ErrorCode
import com.qlink.common.error.requireTrue
import com.qlink.common.scroll.ScrollRequest
import com.qlink.common.transaction.TransactionRunner
import com.qlink.folder.domain.PublicFolderSort
import com.qlink.folder.dto.PublicFolderCursor
import com.qlink.folder.dto.PublicFolderScrollResponse
import com.qlink.folder.dto.PublicFolderTodoResponse
import com.qlink.todo.repository.TodoRepository

class GetPublicFolderTodosService(
    private val tx: TransactionRunner,
    private val publicFolderAccessValidator: PublicFolderAccessValidator,
    private val todoRepository: TodoRepository,
    private val cursorCodec: PublicFolderCursorCodec,
) {
    suspend fun getTodos(
        publicId: String,
        orderKey: String?,
        order: String?,
        scrollRequest: ScrollRequest,
    ): PublicFolderScrollResponse<PublicFolderTodoResponse> =
        tx.required {
            val folderId = publicFolderAccessValidator.requireFolderIdForShare(publicId)
            val size = scrollRequest.size
            (size > 0).requireTrue(ErrorCode.COMMON_BAD_REQUEST)
            val sort = PublicFolderSort.from(orderKey = orderKey, order = order)
            val cursor =
                scrollRequest.cursor?.let {
                    val decoded = cursorCodec.decode(encoded = it, publicId = publicId, collection = COLLECTION, sort = sort)
                    decoded.id ?: throw BusinessException(ErrorCode.COMMON_CURSOR_FIELD_MISSING)
                }
            val rows =
                todoRepository.findPublicByFolderId(
                    folderId = folderId,
                    publicId = publicId,
                    direction = sort.direction,
                    cursorId = cursor,
                    limit = (size.toLong() + 1).coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                )
            val hasNext = rows.size > size
            val page = rows.take(size)
            val nextCursor =
                if (hasNext) {
                    cursorCodec.encode(
                        PublicFolderCursor(
                            publicId = publicId,
                            collection = COLLECTION,
                            direction = sort.direction,
                            id = page.last().id,
                        ),
                    )
                } else {
                    null
                }

            PublicFolderScrollResponse(
                isEmpty = page.isEmpty(),
                contents = page.map { PublicFolderTodoResponse(title = it.title, isCompleted = it.isCompleted) },
                nextCursor = nextCursor,
                hasNext = hasNext,
            )
        }

    companion object {
        private const val COLLECTION = "todos"
    }
}
