package com.qlink.link.service

import com.qlink.common.retry.retryUntilNotNull
import com.qlink.common.transaction.TransactionRunner
import com.qlink.folder.service.FolderAccessValidator
import com.qlink.image.service.PreviewImageUrlResolver
import com.qlink.link.domain.Link
import com.qlink.link.dto.UpdateLinkRequest
import com.qlink.link.dto.UpdateLinkResponse
import com.qlink.link.repository.LinkRepository
import com.qlink.linkcontent.domain.service.LinkContentPlanDomainService
import com.qlink.linkcontent.repository.LinkContentRepository
import com.qlink.linkcontent.worker.LinkContentSignal
import kotlin.time.Clock
import kotlin.time.Instant

class UpdateLinkService(
    private val tx: TransactionRunner,
    private val linkRepository: LinkRepository,
    private val ownedLinkLoader: OwnedLinkLoader,
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
            retryUntilNotNull {
                tx.required {
                    val locked =
                        ownedLinkLoader.load(loginId = loginId, linkId = linkId, targetFolderId = request.folderId)
                            ?: return@required null
                    val link = locked.link
                    val target = request.folderId?.let { folderAccessValidator.validateWritable(locked.targetFolder, loginId) }
                    val turnsPublicAt =
                        link.resolveUpdatedVisibility(
                            sourceIsPublic = locked.sourceFolder?.isPublished == true,
                            targetIsPublic = target?.isPublished == true,
                            isPublic = request.isPublic,
                            now = Clock.System.now(),
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
            }
        linkContentSignal.wake()
        return response
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
