package com.qlink.support

import com.qlink.common.response.ReadResult
import com.qlink.common.scroll.ScrollRequest
import com.qlink.link.service.GetLinkDetailService
import com.qlink.link.service.GetLinksService
import com.qlink.todo.service.GetTodosService

suspend fun GetLinkDetailService.getMemberLinkDetail(
    loginId: Long,
    linkId: Long,
) = (getLinkDetail(viewerId = loginId, linkId = linkId) as ReadResult.Member).data

suspend fun GetLinksService.getMemberLinks(
    loginId: Long,
    query: String?,
    folderId: Long?,
    isFavorite: Boolean? = null,
    orderKey: String? = null,
    order: String? = null,
    scrollRequest: ScrollRequest,
) = (
    getLinks(
        viewerId = loginId,
        query = query,
        folderId = folderId,
        isFavorite = isFavorite,
        orderKey = orderKey,
        order = order,
        scrollRequest = scrollRequest,
    ) as ReadResult.Member
).data

suspend fun GetTodosService.getMemberTodos(
    loginId: Long,
    status: String? = null,
    orderKey: String? = null,
    order: String? = null,
    scrollRequest: ScrollRequest,
    isCompleted: Boolean?,
    reminderAt: String?,
) = (
    getTodos(
        viewerId = loginId,
        folderId = null,
        status = status,
        orderKey = orderKey,
        order = order,
        scrollRequest = scrollRequest,
        isCompleted = isCompleted,
        reminderAt = reminderAt,
    ) as ReadResult.Member
).data
