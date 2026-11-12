package com.qlink.ai.service

import com.qlink.common.error.BusinessException
import com.qlink.common.error.ErrorCode
import com.qlink.user.domain.User
import com.qlink.user.repository.UserRepository

class AiAdminUserLoader(
    private val userRepository: UserRepository,
) {
    suspend fun load(loginId: Long?): User {
        val user = loginId?.let { userRepository.findById(it) } ?: throw BusinessException(ErrorCode.AI_ADMIN_USER_NOT_FOUND)
        return user
    }
}
