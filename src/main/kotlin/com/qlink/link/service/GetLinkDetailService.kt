package com.qlink.link.service

import com.qlink.common.error.BusinessException
import com.qlink.common.error.ErrorCode
import com.qlink.common.transaction.TransactionRunner
import com.qlink.link.dto.GetLinkDetailResponse
import com.qlink.link.dto.LinkContentResponse
import com.qlink.link.dto.LinkDetailQuery
import com.qlink.link.repository.LinkRepository
import com.qlink.todo.dto.LinkDetailTodoQuery
import com.qlink.todo.repository.TodoRepository

class GetLinkDetailService(
    private val tx: TransactionRunner,
    private val linkRepository: LinkRepository,
    private val todoRepository: TodoRepository,
) {
    suspend fun getLinkDetail(
        loginId: Long,
        linkId: Long,
    ): GetLinkDetailResponse =
        tx.readOnly {
            val link =
                linkRepository.findDetailById(
                    linkId = linkId,
                    loginId = loginId,
                ) ?: throw BusinessException(ErrorCode.LINK_NOT_FOUND)

            val isOwner = link.ownerId == loginId
            validateAccess(link = link, loginId = loginId, isOwner = isOwner)

            val todos = todoRepository.findAllByLinkIdForLinkDetail(link.id)

            link.toResponse(
                todos = todos,
                isOwner = isOwner,
            )
        }

    private fun LinkDetailQuery.toResponse(
        todos: List<LinkDetailTodoQuery>,
        isOwner: Boolean,
    ): GetLinkDetailResponse =
        GetLinkDetailResponse(
            id = id,
            url = url,
            title = title,
            thumbnailUrl = thumbnailUrl,
            faviconUrl = faviconUrl,
            isVideo = isVideo,
            summary = summary,
            tags = tags,
            memo = memo,
            sourceType = sourceType,
            status = status,
            createdAt = createdAt,
            folderId = folderId,
            folderName = folderName,
            folderEmoji = folderEmoji,
            todos = todos,
            workModel = workModel,
            isFavorite = favoriteAt != null,
            isPublic = isPublic,
            isOwner = isOwner,
            content =
                if (hasContent) {
                    LinkContentResponse(excerpt = contentExcerpt, isPending = isContentPending)
                } else {
                    null
                },
        )

    private fun validateAccess(
        link: LinkDetailQuery,
        loginId: Long,
        isOwner: Boolean,
    ) {
        if (isOwner) {
            return
        }

        if (link.folderSharedAt != null) {
            if (link.folderMemberUserId == loginId) {
                return
            }

            throw BusinessException(ErrorCode.LINK_SHARED_FOLDER_ACCESS_DENIED)
        }

        throw BusinessException(ErrorCode.LINK_DIFFERENT_OWNER)
    }
}
