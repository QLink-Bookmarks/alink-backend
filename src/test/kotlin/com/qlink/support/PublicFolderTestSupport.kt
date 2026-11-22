package com.qlink.support

import com.qlink.common.scroll.ScrollRequest
import com.qlink.common.scroll.ScrollResponse
import com.qlink.link.domain.Link
import com.qlink.link.dto.PublicFolderLinkResponse
import com.qlink.link.service.GetLinksService

suspend fun GetLinksService.getPublicLinks(
    folderId: Long,
    orderKey: String?,
    order: String?,
    scroll: ScrollRequest,
) = getLinks(
    viewerId = null,
    query = null,
    folderId = folderId,
    isFavorite = null,
    orderKey = orderKey,
    order = order,
    scrollRequest = scroll,
).withItems<PublicFolderLinkResponse>()

inline fun <reified T> ScrollResponse<*>.withItems(): ScrollResponse<T> =
    ScrollResponse(isEmpty = isEmpty, contents = contents.map { it as T }, nextCursor = nextCursor, hasNext = hasNext)

fun Link.toPublicListItem() =
    PublicFolderLinkResponse(
        id = id!!,
        title = title,
        url = url,
        thumbnailUrl = thumbnailUrl,
        faviconUrl = faviconUrl,
        isVideo = isVideo,
        contentExcerpt = null,
    )
