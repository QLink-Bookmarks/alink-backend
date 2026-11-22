package com.qlink.link.service

import com.qlink.common.error.BusinessException
import com.qlink.common.error.ErrorCode
import com.qlink.common.error.requireFalse
import com.qlink.folder.domain.Folder
import com.qlink.folder.repository.FolderRepository
import com.qlink.link.domain.Link
import com.qlink.link.repository.LinkRepository
import com.qlink.user.repository.UserRepository

class OwnedLinkLoader(
    private val linkRepository: LinkRepository,
    private val folderRepository: FolderRepository,
    private val userRepository: UserRepository,
) {
    suspend fun load(
        loginId: Long,
        linkId: Long,
        targetFolderId: Long?,
    ): LockedLink? {
        userRepository.emptyById(loginId).requireFalse(ErrorCode.LINK_OWNER_NOT_FOUND)
        val sourceFolderId = linkRepository.findById(linkId)?.folderId
        val folders =
            folderRepository
                .findAllByIdsForKeyShare(listOfNotNull(sourceFolderId, targetFolderId))
                .associateBy { it.id }
        val link = linkRepository.findByIdForUpdate(linkId) ?: throw BusinessException(ErrorCode.LINK_NOT_FOUND)
        link.validateOwner(loginId)
        if (link.hasFolderChangedSince(sourceFolderId)) return null
        return LockedLink(link = link, sourceFolder = folders[sourceFolderId], targetFolder = folders[targetFolderId])
    }

    class LockedLink(
        val link: Link,
        val sourceFolder: Folder?,
        val targetFolder: Folder?,
    )
}
