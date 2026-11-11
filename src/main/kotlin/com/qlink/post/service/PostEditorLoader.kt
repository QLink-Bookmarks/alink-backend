package com.qlink.post.service

import com.qlink.common.error.BusinessException
import com.qlink.common.error.ErrorCode
import com.qlink.user.domain.User
import com.qlink.user.repository.UserRepository

class PostEditorLoader(
    private val userRepository: UserRepository,
) {
    suspend fun load(loginId: Long): User {
        val user = userRepository.findById(loginId) ?: throw BusinessException(ErrorCode.POST_EDITOR_NOT_FOUND)
        return user
    }
}
