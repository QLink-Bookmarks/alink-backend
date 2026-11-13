package com.qlink.post.service

import com.qlink.common.error.BusinessException
import com.qlink.common.error.ErrorCode
import com.qlink.common.transaction.TransactionRunner
import com.qlink.notification.domain.NotificationContext
import com.qlink.notification.repository.NotificationRepository
import com.qlink.post.domain.PostType
import com.qlink.post.repository.PostRepository
import com.qlink.user.repository.UserRepository

class DeletePostService(
    private val tx: TransactionRunner,
    private val userRepository: UserRepository,
    private val postRepository: PostRepository,
    private val notificationRepository: NotificationRepository,
) {
    suspend fun deletePost(
        loginId: Long,
        id: Long,
    ) = tx.required {
        val user = userRepository.findById(loginId) ?: throw BusinessException(ErrorCode.POST_AUTHOR_NOT_FOUND)
        PostType.ANNOUNCEMENT.validateManageable(user.role.isAdmin)
        val post = postRepository.findById(id) ?: throw BusinessException(ErrorCode.POST_ANNOUNCEMENT_NOT_FOUND)
        post.validateEditable()
        notificationRepository.deleteByContext(context = NotificationContext.ANNOUNCE, contextId = id)
        postRepository.delete(id)
    }
}
