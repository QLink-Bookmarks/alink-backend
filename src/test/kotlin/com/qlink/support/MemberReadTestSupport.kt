package com.qlink.support

import com.qlink.common.scroll.ScrollRequest
import com.qlink.link.service.GetLinkDetailService
import com.qlink.link.service.GetLinksService
import com.qlink.todo.service.GetTodosService
import io.kotest.matchers.nulls.shouldNotBeNull

suspend fun GetLinkDetailService.getMemberLinkDetail(
    loginId: Long,
    linkId: Long,
) = getMemberLinkDetailOrNull(viewerId = loginId, linkId = linkId).shouldNotBeNull()

suspend fun GetLinksService.getMemberLinks(
    loginId: Long,
    query: String?,
    folderId: Long?,
    isFavorite: Boolean? = null,
    orderKey: String? = null,
    order: String? = null,
    scrollRequest: ScrollRequest,
) = getMemberLinksOrNull(
    viewerId = loginId,
    query = query,
    folderId = folderId,
    isFavorite = isFavorite,
    orderKey = orderKey,
    order = order,
    scrollRequest = scrollRequest,
).shouldNotBeNull()

suspend fun GetTodosService.getMemberTodos(
    loginId: Long,
    status: String? = null,
    orderKey: String? = null,
    order: String? = null,
    scrollRequest: ScrollRequest,
    isCompleted: Boolean?,
    reminderAt: String?,
) = getTodos(
    loginId = loginId,
    status = status,
    orderKey = orderKey,
    order = order,
    scrollRequest = scrollRequest,
    isCompleted = isCompleted,
    reminderAt = reminderAt,
)
