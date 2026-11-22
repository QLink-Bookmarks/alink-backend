package com.qlink.link.service

import com.qlink.common.error.BusinessException
import com.qlink.common.error.ErrorCode
import com.qlink.common.response.ReadResult
import com.qlink.common.transaction.TransactionRunner
import com.qlink.link.dto.GetLinkDetailResponse
import com.qlink.link.dto.LinkContentResponse
import com.qlink.link.dto.LinkDetailQuery
import com.qlink.link.dto.PublicLinkDetailResponse
import com.qlink.link.repository.LinkRepository
import com.qlink.todo.dto.LinkDetailTodoQuery
import com.qlink.todo.dto.PublicLinkTodoResponse
import com.qlink.todo.repository.TodoRepository

class GetLinkDetailService(
    private val tx: TransactionRunner,
    private val linkRepository: LinkRepository,
    private val todoRepository: TodoRepository,
) {
    suspend fun getLinkDetail(
        viewerId: Long?,
        linkId: Long,
    ): ReadResult<GetLinkDetailResponse, PublicLinkDetailResponse> =
        tx.readOnly {
            val link = viewerId?.let { linkRepository.findDetailById(linkId = linkId, loginId = it) }
            if (viewerId != null && link != null && link.canRead(viewerId)) {
                ReadResult.Member(link.toMemberResponse(viewerId))
            } else {
                val detail = linkRepository.findPublicDetailById(linkId) ?: throw BusinessException(ErrorCode.LINK_NOT_FOUND)
                ReadResult.Guest(
                    PublicLinkDetailResponse(
                        id = detail.id,
                        folderId = detail.folderId,
                        title = detail.title,
                        url = detail.url,
                        folderName = detail.folderName,
                        folderEmoji = detail.folderEmoji,
                        todos = detail.todos.map { PublicLinkTodoResponse(title = it.title, isCompleted = it.isCompleted) },
                    ),
                )
            }
        }

    private fun LinkDetailQuery.canRead(loginId: Long): Boolean =
        ownerId == loginId || (folderSharedAt != null && folderMemberUserId == loginId)

    private suspend fun LinkDetailQuery.toMemberResponse(loginId: Long): GetLinkDetailResponse =
        toResponse(todos = todoRepository.findAllByLinkIdForLinkDetail(id), isOwner = ownerId == loginId)

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
            turnsPublicAt = turnsPublicAt,
            isOwner = isOwner,
            content =
                if (hasContent) {
                    LinkContentResponse(excerpt = contentExcerpt, isPending = isContentPending)
                } else {
                    null
                },
        )
}
