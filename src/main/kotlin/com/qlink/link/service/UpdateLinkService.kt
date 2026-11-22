package com.qlink.link.service

import com.qlink.common.error.BusinessException
import com.qlink.common.error.ErrorCode
import com.qlink.common.error.requireFalse
import com.qlink.common.retry.retryUntilNotNull
import com.qlink.common.transaction.TransactionRunner
import com.qlink.folder.domain.Folder
import com.qlink.folder.repository.FolderRepository
import com.qlink.folder.service.FolderAccessValidator
import com.qlink.image.service.PreviewImageUrlResolver
import com.qlink.link.domain.Link
import com.qlink.link.dto.LinkPrivacyResponse
import com.qlink.link.dto.UpdateLinkRequest
import com.qlink.link.dto.UpdateLinkResponse
import com.qlink.link.repository.LinkRepository
import com.qlink.linkcontent.domain.service.LinkContentPlanDomainService
import com.qlink.linkcontent.repository.LinkContentRepository
import com.qlink.linkcontent.worker.LinkContentSignal
import com.qlink.user.repository.UserRepository
import kotlin.time.Clock
import kotlin.time.Instant

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
        val response =
            withLockedLink(loginId = loginId, linkId = linkId, targetFolderId = request.folderId) { link, sourceFolder, targetFolder ->
                val target = request.folderId?.let { folderAccessValidator.validateWritable(targetFolder, loginId) }
                val turnsPublicAt =
                    link.resolveUpdatedVisibility(
                        sourceIsPublic = sourceFolder?.isPublished == true,
                        targetIsPublic = target?.isPublished == true,
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
                        turnsPublicAt = turnsPublicAt,
                    )

                val savedLink = linkRepository.update(updatedLink)
                LinkContentPlanDomainService
                    .forUrlChange(
                        current = linkContentRepository.findByLinkId(linkId),
                        savedLink = savedLink,
                        now = Clock.System.now(),
                    )?.let { linkContentRepository.upsertForLink(it) }

                savedLink.toResponse(turnsPublicAt = savedLink.publishedAtIn(target?.isPublished == true))
            }
        linkContentSignal.wake()
        return response
    }

    suspend fun setPrivacy(
        loginId: Long,
        linkId: Long,
        isPublic: Boolean,
    ): LinkPrivacyResponse =
        withLockedLink(loginId = loginId, linkId = linkId, targetFolderId = null) { link, folder, _ ->
            val folderIsPublic = folder?.isPublished == true
            val turnsPublicAt =
                link.resolveUpdatedVisibility(
                    sourceIsPublic = folderIsPublic,
                    targetIsPublic = folderIsPublic,
                    isPublic = isPublic,
                )
            val saved =
                if (turnsPublicAt == link.turnsPublicAt) {
                    turnsPublicAt
                } else {
                    linkRepository.updateVisibility(linkId = linkId, turnsPublicAt = turnsPublicAt)
                }
            LinkPrivacyResponse(turnsPublicAt = saved)
        }

    private suspend fun <T : Any> withLockedLink(
        loginId: Long,
        linkId: Long,
        targetFolderId: Long?,
        action: suspend (Link, Folder?, Folder?) -> T,
    ): T =
        retryUntilNotNull {
            tx.required {
                userRepository.emptyById(loginId).requireFalse(ErrorCode.LINK_OWNER_NOT_FOUND)
                val sourceFolderId = linkRepository.findById(linkId)?.folderId
                val folders =
                    folderRepository
                        .findAllByIdsForKeyShare(listOfNotNull(sourceFolderId, targetFolderId))
                        .associateBy { it.id }
                val link = linkRepository.findByIdForUpdate(linkId) ?: throw BusinessException(ErrorCode.LINK_NOT_FOUND)
                link.validateOwner(loginId)
                if (link.hasFolderChangedSince(sourceFolderId)) return@required null
                action(link, folders[sourceFolderId], folders[targetFolderId])
            }
        }

    private fun Link.toResponse(turnsPublicAt: Instant?): UpdateLinkResponse =
        UpdateLinkResponse(
            folderId = folderId,
            url = url,
            title = title,
            summary = summary,
            memo = memo,
            tags = tags,
            thumbnailUrl = thumbnailUrl,
            sourceType = sourceType,
            turnsPublicAt = turnsPublicAt,
        )
}
