package com.qlink.folder.service

import com.qlink.common.error.BusinessException
import com.qlink.common.error.ErrorCode
import com.qlink.common.error.requireTrue
import com.qlink.folder.domain.Folder
import com.qlink.folder.repository.FolderRepository

class PublicFolderAccessValidator(
    private val folderRepository: FolderRepository,
) {
    suspend fun requireFolderIdForShare(publicId: String): Long {
        Folder.matchesPublicIdFormat(publicId).requireTrue(ErrorCode.FOLDER_PUBLIC_NOT_FOUND)

        return folderRepository.findIdByPublicIdForShare(publicId) ?: throw BusinessException(ErrorCode.FOLDER_PUBLIC_NOT_FOUND)
    }
}
