package com.qlink.folder.service

import com.qlink.common.error.BusinessException
import com.qlink.common.error.ErrorCode
import com.qlink.common.error.requireFalse
import com.qlink.common.error.requireTrue
import com.qlink.common.transaction.TransactionRunner
import com.qlink.folder.dto.FolderPrivacyResponse
import com.qlink.folder.repository.FolderRepository
import com.qlink.link.repository.LinkRepository
import com.qlink.user.repository.UserRepository
import kotlin.time.Clock

class SetFolderPrivacyService(
    private val tx: TransactionRunner,
    private val userRepository: UserRepository,
    private val folderRepository: FolderRepository,
    private val linkRepository: LinkRepository,
) {
    suspend fun setPrivacy(
        loginId: Long,
        folderId: Long,
        isPublic: Boolean,
    ): FolderPrivacyResponse =
        tx.required {
            userRepository.emptyById(loginId).requireFalse(ErrorCode.FOLDER_OWNER_NOT_FOUND)
            val folder =
                folderRepository.findAllByIdsForUpdate(listOf(folderId)).singleOrNull()
                    ?: throw BusinessException(ErrorCode.FOLDER_NOT_FOUND)
            folder.isOwnedBy(loginId).requireTrue(ErrorCode.FOLDER_NOT_FOUND)
            val updated = folder.setPublic(isPublic = isPublic, now = Clock.System.now())
            if (updated === folder) return@required FolderPrivacyResponse(isPublic = folder.isPublished)

            linkRepository.clearFolderVisibility(folderId)
            val saved = folderRepository.update(updated)
            FolderPrivacyResponse(isPublic = saved.isPublished)
        }
}
