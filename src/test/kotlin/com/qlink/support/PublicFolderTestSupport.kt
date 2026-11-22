package com.qlink.support

import com.qlink.common.scroll.ScrollRequest
import com.qlink.link.domain.Link
import com.qlink.link.dto.PublicFolderLinkResponse
import com.qlink.link.service.GetLinksService

suspend fun GetLinksService.getPublicLinks(
    folderId: Long,
    orderKey: String?,
    order: String?,
    scroll: ScrollRequest,
) = getPublicLinks(
    query = null,
    folderId = folderId,
    isFavorite = null,
    orderKey = orderKey,
    order = order,
    scrollRequest = scroll,
)

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
