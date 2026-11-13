package com.qlink.post.service

import com.qlink.common.error.BusinessException
import com.qlink.common.error.ErrorCode
import com.qlink.common.transaction.TransactionRunner
import com.qlink.post.domain.PostType
import com.qlink.post.dto.UpdatePostRequest
import com.qlink.post.dto.UpdatePostResponse
import com.qlink.post.repository.PostRepository
import com.qlink.user.repository.UserRepository
import kotlin.time.Clock

class UpdatePostService(
    private val tx: TransactionRunner,
    private val userRepository: UserRepository,
    private val postRepository: PostRepository,
) {
    suspend fun updatePost(
        loginId: Long,
        id: Long,
        request: UpdatePostRequest,
    ): UpdatePostResponse =
        tx.required {
            val user = userRepository.findById(loginId) ?: throw BusinessException(ErrorCode.POST_AUTHOR_NOT_FOUND)
            PostType.ANNOUNCEMENT.validateManageable(user.role.isAdmin)
            val post = postRepository.findById(id) ?: throw BusinessException(ErrorCode.POST_ANNOUNCEMENT_NOT_FOUND)
            post.validateEditable()
            val saved = postRepository.update(post.change(title = request.title, contents = request.contents, now = Clock.System.now()))
            UpdatePostResponse(id = saved.id!!, updatedAt = saved.updatedAt!!)
        }
}
