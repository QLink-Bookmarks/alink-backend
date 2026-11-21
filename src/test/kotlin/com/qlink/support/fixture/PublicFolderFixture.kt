package com.qlink.support.fixture

import com.qlink.folder.repository.FolderRepository

object PublicFolderFixture {
    suspend fun createUnavailableIds(
        folders: FolderRepository,
        ownerId: Long,
    ): List<String> {
        val private = folders.insert(FolderFixture.createValidUnsharedFolder(ownerId))
        val revoked = folders.insert(FolderFixture.createValidUnsharedFolder(ownerId).setPublic(FolderFixture.createPublicId()))
        val deleted = folders.insert(FolderFixture.createValidUnsharedFolder(ownerId).setPublic(FolderFixture.createPublicId()))
        folders.update(revoked.setPublic(null))
        folders.deleteById(deleted.id!!)

        return listOf(
            private.id.toString(),
            FolderFixture.createPublicId(),
            revoked.publicId!!,
            deleted.publicId!!,
            RandomFixture.randomFixedSentence(20),
        )
    }
}
