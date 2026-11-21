package com.qlink.link.service

import com.qlink.common.error.BusinessException
import com.qlink.common.error.ErrorCode
import com.qlink.common.error.requireFalse
import com.qlink.common.transaction.TransactionRunner
import com.qlink.folder.repository.FolderRepository
import com.qlink.folder.service.FolderAccessValidator
import com.qlink.image.service.PreviewImageUrlResolver
import com.qlink.link.domain.Link
import com.qlink.link.dto.UpdateLinkRequest
import com.qlink.link.dto.UpdateLinkResponse
import com.qlink.link.repository.LinkRepository
import com.qlink.linkcontent.domain.service.LinkContentPlanDomainService
import com.qlink.linkcontent.repository.LinkContentRepository
import com.qlink.linkcontent.worker.LinkContentSignal
import com.qlink.user.repository.UserRepository
import kotlin.time.Clock

class UpdateLinkService(
    private val tx: TransactionRunner,
    private val linkRepository: LinkRepository,
    private val userRepository: UserRepository,
    private val folderRepository: FolderRepository,
    private val folderAccessValidator: FolderAccessValidator,
    private val urlResolver: PreviewImageUrlResolver,
    private val linkContentRepository: LinkContentRepository,
    private val linkContentSignal: LinkContentSignal,
) {
    suspend fun updateLink(
        loginId: Long,
        linkId: Long,
        request: UpdateLinkRequest,
    ): UpdateLinkResponse {
        var response: UpdateLinkResponse?
        do {
            response =
                tx
                    .required {
                        userRepository.emptyById(loginId).requireFalse(ErrorCode.LINK_OWNER_NOT_FOUND)
                        val sourceFolderId = linkRepository.findById(linkId)?.folderId
                        val folders =
                            folderRepository
                                .findAllByIdsForKeyShare(
                                    listOfNotNull(sourceFolderId, request.folderId),
                                ).associateBy { it.id }

                        val link = linkRepository.findByIdForUpdate(linkId) ?: throw BusinessException(ErrorCode.LINK_NOT_FOUND)
                        link.validateOwner(loginId)
                        if (link.hasFolderChangedSince(sourceFolderId)) return@required null

                        val target = request.folderId?.let { folderAccessValidator.validateWritable(folders[it], loginId) }
                        val publicationId =
                            link.resolveUpdatedPublicationId(
                                sourcePublicId = folders[sourceFolderId]?.publicId,
                                targetPublicId = target?.publicId,
                                isPublic = request.isPublic,
                            )

                        val updatedLink =
                            link.update(
                                folderId = request.folderId,
                                url = request.url,
                                title = request.title,
                                summary = request.summary,
                                memo = request.memo,
                                tags = request.tags,
                                thumbnailUrl = request.thumbnailUrl,
                                sourceType = request.sourceType,
                                faviconUrl = request.faviconUrl,
                                isVideo = request.isVideo,
                                previewPublicBaseUrl = urlResolver.publicBaseUrl,
                                previewEnvironment = urlResolver.environment,
                                publicationId = publicationId,
                            )

                        val savedLink = linkRepository.update(updatedLink)
                        LinkContentPlanDomainService
                            .forUrlChange(
                                current = linkContentRepository.findByLinkId(linkId),
                                savedLink = savedLink,
                                now = Clock.System.now(),
                            )?.let { linkContentRepository.upsertForLink(it) }

                        savedLink.toResponse(isPublic = savedLink.isPublicIn(target?.publicId))
                    }
        } while (response == null)
        linkContentSignal.wake()
        return response
    }

    private fun Link.toResponse(isPublic: Boolean): UpdateLinkResponse =
        UpdateLinkResponse(
            folderId = folderId,
            url = url,
            title = title,
            summary = summary,
            memo = memo,
            tags = tags,
            thumbnailUrl = thumbnailUrl,
            sourceType = sourceType,
            isPublic = isPublic,
        )
}
