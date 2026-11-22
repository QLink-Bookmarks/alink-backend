package com.qlink.todo.service

import com.qlink.common.error.BusinessException
import com.qlink.common.error.ErrorCode
import com.qlink.common.error.requireFalse
import com.qlink.common.error.requireTrue
import com.qlink.common.response.ReadResult
import com.qlink.common.scroll.Base64CursorCodec
import com.qlink.common.scroll.ScrollRequest
import com.qlink.common.scroll.ScrollResponse
import com.qlink.common.transaction.TransactionRunner
import com.qlink.folder.domain.PublicFolderSort
import com.qlink.folder.dto.PublicFolderCursor
import com.qlink.folder.service.FolderAccessValidator
import com.qlink.folder.service.PublicFolderCursorCodec
import com.qlink.todo.domain.TodoSort
import com.qlink.todo.domain.TodoStatus
import com.qlink.todo.dto.DEFAULT_TODO_SCROLL_SIZE
import com.qlink.todo.dto.GetTodosContentResponse
import com.qlink.todo.dto.PublicFolderTodoResponse
import com.qlink.todo.dto.SearchTodosQuery
import com.qlink.todo.dto.TodoReminderFilter
import com.qlink.todo.dto.TodoSearchCursor
import com.qlink.todo.dto.TodoSearchCursorValue
import com.qlink.todo.repository.TodoRepository
import com.qlink.user.repository.UserRepository
import java.time.temporal.ChronoUnit
import kotlin.time.Clock
import kotlin.time.toJavaInstant
import kotlin.time.toKotlinInstant

class GetTodosService(
    private val tx: TransactionRunner,
    private val todoRepository: TodoRepository,
    private val userRepository: UserRepository,
    private val folderAccessValidator: FolderAccessValidator,
    private val cursorCodec: PublicFolderCursorCodec,
) {
    suspend fun getVisibleTodos(
        viewerId: Long?,
        folderId: Long?,
        status: String?,
        orderKey: String?,
        order: String?,
        scrollRequest: ScrollRequest,
        isCompleted: Boolean?,
        reminderAt: String?,
    ): ReadResult<ScrollResponse<GetTodosContentResponse>, ScrollResponse<PublicFolderTodoResponse>> =
        tx.required {
            val folder = folderId?.let { folderAccessValidator.lockForRead(it) }
            if (viewerId != null && (folder == null || folderAccessValidator.canReadAsMember(folder, viewerId))) {
                ReadResult.Member(getTodos(viewerId, status, orderKey, order, scrollRequest, isCompleted, reminderAt, folderId))
            } else {
                val publicFolderId = folder?.id ?: throw BusinessException(ErrorCode.AUTH_ACCESS_TOKEN_MISSING)
                folder.isPublic.requireTrue(ErrorCode.FOLDER_NOT_FOUND)
                (status == null && isCompleted == null && reminderAt == null).requireTrue(ErrorCode.COMMON_INVALID_FILTER)
                ReadResult.Guest(readPublicTodos(publicFolderId, orderKey, order, scrollRequest))
            }
        }

    private suspend fun readPublicTodos(
        folderId: Long,
        orderKey: String?,
        order: String?,
        scrollRequest: ScrollRequest,
    ): ScrollResponse<PublicFolderTodoResponse> {
        val size = scrollRequest.size
        (size > 0).requireTrue(ErrorCode.COMMON_BAD_REQUEST)
        val sort = PublicFolderSort.from(orderKey = orderKey, order = order)
        val cursor =
            scrollRequest.cursor?.let {
                val decoded = cursorCodec.decode(encoded = it, folderId = folderId, collection = "todos", sort = sort)
                decoded.id ?: throw BusinessException(ErrorCode.COMMON_CURSOR_FIELD_MISSING)
            }
        val rows =
            todoRepository.findPublicByFolderId(
                folderId = folderId,
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
                        folderId = folderId,
                        collection = "todos",
                        direction = sort.direction,
                        id = page.last().id,
                    ),
                )
            } else {
                null
            }

        return ScrollResponse(
            isEmpty = page.isEmpty(),
            contents = page.map { PublicFolderTodoResponse(linkId = it.linkId, title = it.title, isCompleted = it.isCompleted) },
            nextCursor = nextCursor,
            hasNext = hasNext,
        )
    }

    suspend fun getTodos(
        loginId: Long,
        status: String? = null,
        orderKey: String? = null,
        order: String? = null,
        scrollRequest: ScrollRequest,
        isCompleted: Boolean?,
        reminderAt: String?,
        folderId: Long? = null,
    ): ScrollResponse<GetTodosContentResponse> =
        tx.readOnly {
            userRepository.emptyById(loginId).requireFalse(ErrorCode.TODO_OWNER_NOT_FOUND)

            val sort = TodoSort.of(orderKey = orderKey, order = order)
            val cursor = scrollRequest.cursor?.let { decodeCursor(encoded = it, sort = sort) }
            val size = scrollRequest.size.takeIf { it > 0 } ?: DEFAULT_TODO_SCROLL_SIZE
            val statusFilter = status?.let { parseStatus(status = it, isCompleted = isCompleted, reminderAt = reminderAt) }
            val reminderFilter = reminderAt?.let { TodoReminderFilter.from(it) ?: throw BusinessException(ErrorCode.COMMON_INVALID_FILTER) }
            val now =
                Clock.System
                    .now()
                    .toJavaInstant()
                    .truncatedTo(ChronoUnit.MICROS)
                    .toKotlinInstant()
            val queries =
                todoRepository.search(
                    ownerId = loginId,
                    folderId = folderId,
                    sort = sort,
                    cursor = cursor,
                    size = size,
                    status = statusFilter,
                    isCompleted = isCompleted,
                    reminderAt = reminderFilter,
                    now = now,
                )
            val hasNext = queries.size > size
            val contents = queries.take(size)
            val statusById = todoRepository.findAllByIds(contents.map { it.id }).associate { it.id!! to it.status(now) }
            val responses = contents.mapNotNull { query -> statusById[query.id]?.let { query.toResponse(it) } }

            ScrollResponse(
                isEmpty = responses.isEmpty(),
                contents = responses,
                nextCursor = contents.lastOrNull()?.takeIf { hasNext }?.let { encodeCursor(query = it, sort = sort) },
                hasNext = hasNext,
            )
        }

    private fun parseStatus(
        status: String,
        isCompleted: Boolean?,
        reminderAt: String?,
    ): TodoStatus {
        (isCompleted == null && reminderAt == null).requireTrue(ErrorCode.COMMON_INVALID_FILTER)

        return TodoStatus.from(status) ?: throw BusinessException(ErrorCode.COMMON_INVALID_FILTER)
    }

    private fun decodeCursor(
        encoded: String,
        sort: TodoSort,
    ): TodoSearchCursor {
        val cursor = Base64CursorCodec.decode<TodoSearchCursor>(encoded)
        sort.validateCursor(orderKey = cursor.orderKey, direction = cursor.direction, order = cursor.order)
        cursor.value.id ?: throw BusinessException(ErrorCode.COMMON_CURSOR_FIELD_MISSING)

        return cursor
    }

    private fun encodeCursor(
        query: SearchTodosQuery,
        sort: TodoSort,
    ): String =
        Base64CursorCodec.encode(
            TodoSearchCursor(
                orderKey = sort.orderKey,
                direction = sort.direction,
                value =
                    TodoSearchCursorValue(
                        id = query.id,
                        reminderAt = query.reminderAt,
                        completedAt = query.completedAt,
                    ),
            ),
        )

    private fun SearchTodosQuery.toResponse(status: TodoStatus): GetTodosContentResponse =
        GetTodosContentResponse(
            id = id,
            title = title,
            status = status,
            completedAt = completedAt,
            reminderAt = reminderAt,
            repeatUntil = repeatUntil,
            repeatDays = repeatDays,
            repeatTime = repeatTime,
            createdAt = createdAt,
            linkId = linkId,
            linkUrl = linkUrl,
            linkTitle = linkTitle,
            linkThumbnailUrl = linkThumbnailUrl,
            linkFaviconUrl = linkFaviconUrl,
        )
}
