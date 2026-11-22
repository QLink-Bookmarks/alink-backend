package com.qlink.folder.service

import com.qlink.common.error.BusinessException
import com.qlink.common.error.ErrorCode
import com.qlink.common.error.requireTrue
import com.qlink.folder.domain.Folder
import com.qlink.folder.repository.FolderRepository
import com.qlink.foldermember.repository.FolderMemberRepository

class FolderAccessValidator(
    private val folderRepository: FolderRepository,
    private val folderMemberRepository: FolderMemberRepository,
) {
    suspend fun lockForRead(folderId: Long): Folder =
        folderRepository.findByIdForShare(folderId) ?: throw BusinessException(ErrorCode.FOLDER_NOT_FOUND)

    suspend fun canReadAsMember(
        folder: Folder,
        userId: Long,
    ): Boolean =
        folder.isOwnedBy(userId) ||
            (folder.isShared && folderMemberRepository.findByFolderIdAndUserId(folder.id!!, userId) != null)

    suspend fun validateWritable(
        folder: Folder?,
        userId: Long,
    ): Folder =
        folder
            ?.also {
                it
                    .isLinkWritableBy(userId = userId, memberCanWrite = canMemberWrite(it, userId))
                    .requireTrue(ErrorCode.LINK_FOLDER_ACCESS_DENIED)
            }
            ?: throw BusinessException(ErrorCode.LINK_FOLDER_NOT_FOUND)

    private suspend fun canMemberWrite(
        folder: Folder,
        userId: Long,
    ): Boolean =
        folder.isShared && !folder.isOwnedBy(userId) &&
            folderMemberRepository.findByFolderIdAndUserId(folder.id!!, userId)?.canWriteLink() == true
}
