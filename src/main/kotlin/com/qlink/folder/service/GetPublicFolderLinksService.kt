package com.qlink.folder.service

import com.qlink.common.error.BusinessException
import com.qlink.common.error.ErrorCode
import com.qlink.common.error.requireTrue
import com.qlink.common.scroll.ScrollRequest
import com.qlink.common.transaction.TransactionRunner
import com.qlink.folder.domain.PublicFolderSort
import com.qlink.folder.dto.PublicFolderCursor
import com.qlink.folder.dto.PublicFolderLinkResponse
import com.qlink.folder.dto.PublicFolderScrollResponse
import com.qlink.link.dto.PublicLinkCursor
import com.qlink.link.repository.LinkRepository

class GetPublicFolderLinksService(
    private val tx: TransactionRunner,
    private val publicFolderAccessValidator: PublicFolderAccessValidator,
    private val linkRepository: LinkRepository,
    private val cursorCodec: PublicFolderCursorCodec,
) {
    suspend fun getLinks(
        publicId: String,
        orderKey: String?,
        order: String?,
        scrollRequest: ScrollRequest,
    ): PublicFolderScrollResponse<PublicFolderLinkResponse> =
        tx.required {
            val folderId = publicFolderAccessValidator.requireFolderIdForShare(publicId)
            val size = scrollRequest.size
            (size > 0).requireTrue(ErrorCode.COMMON_BAD_REQUEST)
            val sort = PublicFolderSort.from(orderKey = orderKey, order = order)
            val cursor =
                scrollRequest.cursor?.let {
                    val decoded = cursorCodec.decode(encoded = it, publicId = publicId, collection = COLLECTION, sort = sort)
                    PublicLinkCursor(
                        id = decoded.id ?: throw BusinessException(ErrorCode.COMMON_CURSOR_FIELD_MISSING),
                        createdAt = decoded.createdAt ?: throw BusinessException(ErrorCode.COMMON_CURSOR_FIELD_MISSING),
                    )
                }
            val rows =
                linkRepository.findPublicByFolderId(
                    folderId = folderId,
                    publicId = publicId,
                    direction = sort.direction,
                    cursor = cursor,
                    limit = (size.toLong() + 1).coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                )
            val hasNext = rows.size > size
            val page = rows.take(size)
            val nextCursor =
                if (hasNext) {
                    val last = page.last()
                    cursorCodec.encode(
                        PublicFolderCursor(
                            publicId = publicId,
                            collection = COLLECTION,
                            direction = sort.direction,
                            id = last.id,
                            createdAt = last.createdAt,
                        ),
                    )
                } else {
                    null
                }

            PublicFolderScrollResponse(
                isEmpty = page.isEmpty(),
                contents = page.map { PublicFolderLinkResponse(title = it.title, url = it.url) },
                nextCursor = nextCursor,
                hasNext = hasNext,
            )
        }

    companion object {
        private const val COLLECTION = "links"
    }
}
