package com.qlink.link.service

import com.qlink.common.retry.retryUntilNotNull
import com.qlink.common.transaction.TransactionRunner
import com.qlink.link.dto.LinkPrivacyResponse
import com.qlink.link.repository.LinkRepository
import kotlin.time.Clock

class SetLinkPrivacyService(
    private val tx: TransactionRunner,
    private val linkRepository: LinkRepository,
    private val ownedLinkLoader: OwnedLinkLoader,
) {
    suspend fun setPrivacy(
        loginId: Long,
        linkId: Long,
        isPublic: Boolean,
    ): LinkPrivacyResponse =
        retryUntilNotNull {
            tx.required {
                val locked = ownedLinkLoader.load(loginId = loginId, linkId = linkId, targetFolderId = null) ?: return@required null
                val link = locked.link
                val folderIsPublic = locked.sourceFolder?.isPublished == true
                val turnsPublicAt =
                    link.resolveUpdatedVisibility(
                        sourceIsPublic = folderIsPublic,
                        targetIsPublic = folderIsPublic,
                        isPublic = isPublic,
                        now = Clock.System.now(),
                    )
                val saved =
                    if (turnsPublicAt == link.turnsPublicAt) {
                        turnsPublicAt
                    } else {
                        linkRepository.updateVisibility(linkId = linkId, turnsPublicAt = turnsPublicAt)
                    }
                LinkPrivacyResponse(turnsPublicAt = saved)
            }
        }
}
