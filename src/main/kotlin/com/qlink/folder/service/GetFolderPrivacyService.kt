package com.qlink.folder.service

import com.qlink.common.error.BusinessException
import com.qlink.common.error.ErrorCode
import com.qlink.common.error.requireTrue
import com.qlink.common.transaction.TransactionRunner
import com.qlink.folder.dto.FolderPrivacyResponse
import com.qlink.folder.repository.FolderRepository

class GetFolderPrivacyService(
    private val tx: TransactionRunner,
    private val folderRepository: FolderRepository,
) {
    suspend fun getPrivacy(
        loginId: Long,
        folderId: Long,
    ): FolderPrivacyResponse =
        tx.readOnly {
            val folder = folderRepository.findById(folderId) ?: throw BusinessException(ErrorCode.FOLDER_NOT_FOUND)
            folder.isOwnedBy(loginId).requireTrue(ErrorCode.FOLDER_NOT_FOUND)

            FolderPrivacyResponse(isPublic = folder.isPublic, publicId = folder.publicId)
        }
}
