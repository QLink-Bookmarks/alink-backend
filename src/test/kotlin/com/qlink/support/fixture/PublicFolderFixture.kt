package com.qlink.support.fixture

import com.qlink.folder.repository.FolderRepository

object PublicFolderFixture {
    suspend fun createUnavailableIds(
        folders: FolderRepository,
        ownerId: Long,
    ): List<Long> {
        val private = folders.insert(FolderFixture.createValidUnsharedFolder(ownerId))
        val revoked = folders.insert(FolderFixture.createValidUnsharedFolder(ownerId).setPublic(true))
        val deleted = folders.insert(FolderFixture.createValidUnsharedFolder(ownerId).setPublic(true))
        folders.update(revoked.setPublic(false))
        folders.deleteById(deleted.id!!)

        return listOf(private.id!!, RandomFixture.randomId(), revoked.id!!, deleted.id!!)
    }
}
