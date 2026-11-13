package com.qlink.post.service

import com.qlink.common.error.BusinessException
import com.qlink.common.error.ErrorCode
import com.qlink.common.transaction.TransactionRunner
import com.qlink.post.dto.GetPostResponse
import com.qlink.post.repository.PostRepository
import com.qlink.user.repository.UserRepository

class GetPostService(
    private val tx: TransactionRunner,
    private val postRepository: PostRepository,
    private val userRepository: UserRepository,
) {
    suspend fun getPost(
        postId: Long,
        loginId: Long?,
    ): GetPostResponse =
        tx.readOnly {
            val post = postRepository.findById(postId) ?: throw BusinessException(ErrorCode.POST_NOT_FOUND)
            val user = loginId?.let { userRepository.findById(it) }
            post.validateReadable(user?.role?.isAdmin == true)

            val author = userRepository.findById(post.authorId)
            val images = if (post.type.hasImage) postRepository.findImagesByPostId(postId) else emptyList()

            GetPostResponse(
                id = post.id!!,
                title = post.title,
                contents = post.contents,
                type = post.type,
                authorId = post.authorId,
                author = author?.nickname ?: "",
                imageUrls = images.map { it.url },
                createdAt = post.createdAt!!,
                updatedAt = post.updatedAt!!,
            )
        }
}
