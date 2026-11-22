package com.qlink.folder.service

import com.qlink.common.error.BusinessException
import com.qlink.common.error.ErrorCode
import com.qlink.folder.domain.Folder
import com.qlink.folder.repository.FolderRepository
import com.qlink.link.repository.LinkRepository

class FolderPublicationWriter(
    private val folderRepository: FolderRepository,
    private val linkRepository: LinkRepository,
) {
    suspend fun lock(folderId: Long): Folder =
        folderRepository.findAllByIdsForUpdate(listOf(folderId)).singleOrNull()
            ?: throw BusinessException(ErrorCode.FOLDER_NOT_FOUND)

    suspend fun save(
        previous: Folder,
        updated: Folder,
    ): Folder {
        if (previous.isPublished != updated.isPublished) {
            linkRepository.clearFolderVisibility(previous.id!!)
        }
        return folderRepository.update(updated)
    }
}
