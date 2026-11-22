package com.qlink.folder.service

import com.qlink.common.transaction.TransactionRunner
import com.qlink.folder.domain.Folder
import com.qlink.folder.repository.FolderRepository
import com.qlink.link.repository.LinkRepository

class SaveFolderPublicationService(
    private val tx: TransactionRunner,
    private val folderRepository: FolderRepository,
    private val linkRepository: LinkRepository,
) {
    suspend fun save(
        previous: Folder,
        updated: Folder,
    ): Folder =
        tx.required {
            if (previous.isPublished != updated.isPublished) {
                linkRepository.clearFolderVisibility(previous.id!!)
            }
            folderRepository.update(updated)
        }
}
