package com.qlink.post.service

import com.qlink.common.error.BusinessException
import com.qlink.common.error.ErrorCode
import com.qlink.common.transaction.TransactionRunner
import com.qlink.post.domain.PostType
import com.qlink.post.dto.UpdatePostRequest
import com.qlink.post.dto.UpdatePostResponse
import com.qlink.post.repository.PostRepository
import kotlin.time.Clock

class UpdatePostService(
    private val tx: TransactionRunner,
    private val editorLoader: PostEditorLoader,
    private val postRepository: PostRepository,
) {
    suspend fun updatePost(
        loginId: Long,
        id: Long,
        request: UpdatePostRequest,
    ): UpdatePostResponse =
        tx.required {
            PostType.ANNOUNCEMENT.validateManageable(editorLoader.load(loginId).role.isAdmin)
            val post = postRepository.findById(id) ?: throw BusinessException(ErrorCode.POST_ANNOUNCEMENT_NOT_FOUND)
            post.validateEditable()
            val saved = postRepository.update(post.change(title = request.title, contents = request.contents, now = Clock.System.now()))
            UpdatePostResponse(id = saved.id!!, updatedAt = saved.updatedAt!!)
        }
}
