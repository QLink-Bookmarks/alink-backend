package com.qlink.post.service

import com.qlink.common.error.BusinessException
import com.qlink.common.error.ErrorCode
import com.qlink.common.transaction.TransactionRunner
import com.qlink.notification.domain.NotificationContext
import com.qlink.notification.repository.NotificationRepository
import com.qlink.post.domain.PostType
import com.qlink.post.repository.PostRepository

class DeletePostService(
    private val tx: TransactionRunner,
    private val editorLoader: PostEditorLoader,
    private val postRepository: PostRepository,
    private val notificationRepository: NotificationRepository,
) {
    suspend fun deletePost(
        loginId: Long,
        id: Long,
    ) = tx.required {
        PostType.ANNOUNCEMENT.validateManageable(editorLoader.load(loginId).role.isAdmin)
        val post = postRepository.findById(id) ?: throw BusinessException(ErrorCode.POST_ANNOUNCEMENT_NOT_FOUND)
        post.validateEditable()
        notificationRepository.deleteByContext(context = NotificationContext.ANNOUNCE, contextId = id)
        postRepository.delete(id)
    }
}
