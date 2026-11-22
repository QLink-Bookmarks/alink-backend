package com.qlink.link.service

import com.qlink.common.error.BusinessException
import com.qlink.common.error.ErrorCode
import com.qlink.common.error.requireFalse
import com.qlink.common.transaction.TransactionRunner
import com.qlink.folder.repository.FolderRepository
import com.qlink.link.dto.LinkPrivacyResponse
import com.qlink.link.repository.LinkRepository
import com.qlink.user.repository.UserRepository
import kotlin.time.Clock

class SetLinkPrivacyService(
    private val tx: TransactionRunner,
    private val linkRepository: LinkRepository,
    private val userRepository: UserRepository,
    private val folderRepository: FolderRepository,
) {
    suspend fun setPrivacy(
        loginId: Long,
        linkId: Long,
        isPublic: Boolean,
    ): LinkPrivacyResponse =
        tx.required {
            userRepository.emptyById(loginId).requireFalse(ErrorCode.LINK_OWNER_NOT_FOUND)
            val link = linkRepository.findByIdForUpdate(linkId) ?: throw BusinessException(ErrorCode.LINK_NOT_FOUND)
            link.validateOwner(loginId)

            val folderIsPublic = link.folderId?.let { folderRepository.findById(it)?.isPublished } == true
            val turnsPublicAt =
                link.resolveUpdatedVisibility(
                    sourceIsPublic = folderIsPublic,
                    targetIsPublic = folderIsPublic,
                    isPublic = isPublic,
                    now = Clock.System.now(),
                )
            if (turnsPublicAt != link.turnsPublicAt) {
                linkRepository.updateVisibility(linkId = linkId, turnsPublicAt = turnsPublicAt)
            }
            LinkPrivacyResponse(isPublic = turnsPublicAt != null)
        }
}
