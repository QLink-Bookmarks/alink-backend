package com.qlink.folder.service

import com.qlink.common.error.ErrorCode
import com.qlink.common.error.requireFalse
import com.qlink.common.error.requireTrue
import com.qlink.common.transaction.TransactionRunner
import com.qlink.folder.dto.FolderPrivacyResponse
import com.qlink.user.repository.UserRepository
import kotlin.time.Clock

class SetFolderPrivacyService(
    private val tx: TransactionRunner,
    private val userRepository: UserRepository,
    private val publicationWriter: FolderPublicationWriter,
) {
    suspend fun setPrivacy(
        loginId: Long,
        folderId: Long,
        isPublic: Boolean,
    ): FolderPrivacyResponse =
        tx.required {
            userRepository.emptyById(loginId).requireFalse(ErrorCode.FOLDER_OWNER_NOT_FOUND)
            val folder = publicationWriter.lock(folderId)
            folder.isOwnedBy(loginId).requireTrue(ErrorCode.FOLDER_NOT_FOUND)
            val updated = folder.setPublic(isPublic = isPublic, now = Clock.System.now())
            val saved = if (updated === folder) folder else publicationWriter.save(previous = folder, updated = updated)
            FolderPrivacyResponse(turnsPublicAt = saved.turnsPublicAt)
        }
}
