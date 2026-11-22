package com.qlink.folder.service

import com.qlink.common.error.BusinessException
import com.qlink.common.error.ErrorCode
import com.qlink.common.error.requireFalse
import com.qlink.common.error.requireTrue
import com.qlink.common.transaction.TransactionRunner
import com.qlink.folder.domain.Folder
import com.qlink.folder.dto.FolderPrivacyResponse
import com.qlink.folder.dto.UpdateFolderRequest
import com.qlink.folder.dto.UpdateFolderResponse
import com.qlink.folder.repository.FolderRepository
import com.qlink.foldermember.domain.FolderMember
import com.qlink.foldermember.repository.FolderMemberRepository
import com.qlink.link.repository.LinkRepository
import com.qlink.user.repository.UserRepository
import kotlin.time.Clock

class UpdateFolderService(
    private val tx: TransactionRunner,
    private val folderRepository: FolderRepository,
    private val folderMemberRepository: FolderMemberRepository,
    private val userRepository: UserRepository,
    private val linkRepository: LinkRepository,
) {
    suspend fun updateFolder(
        loginId: Long,
        folderId: Long,
        request: UpdateFolderRequest,
    ): UpdateFolderResponse =
        tx.required {
            val user = userRepository.findById(loginId) ?: throw BusinessException(ErrorCode.FOLDER_OWNER_NOT_FOUND)

            val folder = lockFolder(folderId)
            if (request.isPublic != null) {
                folder.isOwnedBy(loginId).requireTrue(ErrorCode.FOLDER_NOT_FOUND)
            } else {
                folder.validateOwner(loginId)
            }

            folderRepository
                .existsByOwnerIdAndNameAndIdNot(
                    ownerId = loginId,
                    name = request.name,
                    folderId = folderId,
                ).requireFalse(ErrorCode.FOLDER_DUPLICATE_NAME)

            val updated =
                folder.update(
                    name = request.name,
                    emoji = request.emoji,
                    sharedAt = request.sharedAtOrExisting(folder.sharedAt),
                )
            val publication =
                request.isPublic?.let {
                    updated.setPublic(it)
                } ?: updated
            val saved = saveFolder(previous = folder, updated = publication)

            if (saved.sharedAt != null && folderMemberRepository.existsByFolderId(folderId).not()) {
                folderMemberRepository.insertIfAbsent(
                    FolderMember.owner(
                        folderId = folderId,
                        userId = loginId,
                        userName = user.nickname,
                        joinedAt = Clock.System.now(),
                    ),
                )
            }

            UpdateFolderResponse(id = saved.id!!, turnsPublicAt = saved.turnsPublicAt)
        }

    suspend fun setPrivacy(
        loginId: Long,
        folderId: Long,
        isPublic: Boolean,
    ): FolderPrivacyResponse =
        tx.required {
            userRepository.emptyById(loginId).requireFalse(ErrorCode.FOLDER_OWNER_NOT_FOUND)
            val folder = lockFolder(folderId)
            folder.isOwnedBy(loginId).requireTrue(ErrorCode.FOLDER_NOT_FOUND)
            val updated = folder.setPublic(isPublic)
            val saved = if (updated === folder) folder else saveFolder(previous = folder, updated = updated)
            FolderPrivacyResponse(turnsPublicAt = saved.turnsPublicAt)
        }

    private suspend fun lockFolder(folderId: Long): Folder =
        folderRepository.findAllByIdsForUpdate(listOf(folderId)).singleOrNull()
            ?: throw BusinessException(ErrorCode.FOLDER_NOT_FOUND)

    private suspend fun saveFolder(
        previous: Folder,
        updated: Folder,
    ): Folder {
        if (previous.isPublished != updated.isPublished) {
            linkRepository.clearFolderVisibility(previous.id!!)
        }
        return folderRepository.update(updated)
    }

    private fun UpdateFolderRequest.sharedAtOrExisting(current: kotlin.time.Instant?): kotlin.time.Instant? =
        when (isShared) {
            true -> current ?: Clock.System.now()
            false -> null
            null -> current
        }
}
