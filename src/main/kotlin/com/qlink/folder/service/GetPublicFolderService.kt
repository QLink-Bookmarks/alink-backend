package com.qlink.folder.service

import com.qlink.common.error.BusinessException
import com.qlink.common.error.ErrorCode
import com.qlink.common.error.requireTrue
import com.qlink.common.transaction.TransactionRunner
import com.qlink.folder.domain.Folder
import com.qlink.folder.dto.PublicFolderResponse
import com.qlink.folder.repository.FolderRepository

class GetPublicFolderService(
    private val tx: TransactionRunner,
    private val folderRepository: FolderRepository,
) {
    suspend fun getFolder(publicId: String): PublicFolderResponse =
        tx.readOnly {
            Folder.matchesPublicIdFormat(publicId).requireTrue(ErrorCode.FOLDER_PUBLIC_NOT_FOUND)
            val folder = folderRepository.findPublicByPublicId(publicId) ?: throw BusinessException(ErrorCode.FOLDER_PUBLIC_NOT_FOUND)

            PublicFolderResponse(name = folder.name, emoji = folder.emoji)
        }
}
