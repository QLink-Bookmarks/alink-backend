package com.qlink.folder.service

import com.qlink.common.error.BusinessException
import com.qlink.common.error.ErrorCode
import com.qlink.common.error.requireTrue
import com.qlink.common.transaction.TransactionRunner
import com.qlink.folder.domain.FolderSort
import com.qlink.folder.dto.GetFoldersContentResponse
import com.qlink.folder.dto.PublicFolderResponse
import com.qlink.folder.dto.toFoldersContentResponse
import com.qlink.folder.repository.FolderRepository

class GetFolderService(
    private val tx: TransactionRunner,
    private val folderRepository: FolderRepository,
    private val folderAccessValidator: FolderAccessValidator,
) {
    suspend fun getMemberFolderOrNull(
        folderId: Long,
        viewerId: Long,
    ): GetFoldersContentResponse? =
        tx.required {
            val folder = folderAccessValidator.lockForRead(folderId)
            if (!folderAccessValidator.canReadAsMember(folder, viewerId)) return@required null
            folderRepository
                .search(
                    ownerId = viewerId,
                    query = null,
                    sort = FolderSort.of(orderKey = null, order = null),
                    cursor = null,
                    size = 1,
                    folderId = folderId,
                ).singleOrNull()
                ?.toFoldersContentResponse() ?: throw BusinessException(ErrorCode.FOLDER_NOT_FOUND)
        }

    suspend fun getPublicFolder(folderId: Long): PublicFolderResponse =
        tx.required {
            val folder = folderAccessValidator.lockForRead(folderId)
            folder.isPublished.requireTrue(ErrorCode.FOLDER_NOT_FOUND)
            PublicFolderResponse(id = folderId, name = folder.name, emoji = folder.emoji, myLinkCounts = 0)
        }
}
