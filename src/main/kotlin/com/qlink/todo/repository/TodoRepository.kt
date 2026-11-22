package com.qlink.todo.repository

import com.qlink.common.search.SortDirection
import com.qlink.todo.domain.Todo
import com.qlink.todo.domain.TodoSort
import com.qlink.todo.domain.TodoStatus
import com.qlink.todo.dto.LinkDetailTodoQuery
import com.qlink.todo.dto.PublicTodoQuery
import com.qlink.todo.dto.SearchTodosQuery
import com.qlink.todo.dto.TodoReminderFilter
import com.qlink.todo.dto.TodoSearchCursor
import kotlin.time.Instant

interface TodoRepository {
    suspend fun insert(todo: Todo): Todo

    suspend fun findById(todoId: Long): Todo?

    suspend fun findByIdForShare(todoId: Long): Todo?

    suspend fun findAllByLinkIdsForUpdate(linkIds: List<Long>): List<Todo>

    suspend fun findAllByIds(todoIds: List<Long>): List<Todo>

    suspend fun findAllByLinkId(linkId: Long): List<Todo>

    suspend fun hasOwnedTodoInFolder(
        ownerId: Long,
        folderId: Long,
    ): Boolean

    suspend fun findPublicByFolderId(
        folderId: Long,
        direction: SortDirection,
        cursorId: Long?,
        limit: Int,
    ): List<PublicTodoQuery>

    suspend fun findAllWithReminderBetween(
        startInclusive: Instant,
        endExclusive: Instant,
    ): List<Todo>

    suspend fun findAllByLinkIdForLinkDetail(linkId: Long): List<LinkDetailTodoQuery>

    suspend fun findAllByLinkIds(linkIds: List<Long>): List<Todo>

    suspend fun findAllByOwnerId(ownerId: Long): List<Todo>

    suspend fun search(
        ownerId: Long,
        sort: TodoSort,
        cursor: TodoSearchCursor?,
        size: Int,
        status: TodoStatus?,
        isCompleted: Boolean?,
        reminderAt: TodoReminderFilter?,
        now: Instant,
        folderId: Long? = null,
    ): List<SearchTodosQuery>

    suspend fun update(todo: Todo): Todo

    suspend fun deleteById(todoId: Long)
}
