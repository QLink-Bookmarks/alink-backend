package com.qlink.ai.service

import com.qlink.common.error.BusinessException
import com.qlink.common.error.ErrorCode
import com.qlink.common.error.requireTrue
import com.qlink.user.repository.UserRepository

class AiAdminValidator(
    private val userRepository: UserRepository,
) {
    suspend fun validate(loginId: Long?) {
        val user = loginId?.let { userRepository.findById(it) } ?: throw BusinessException(ErrorCode.AI_ADMIN_USER_NOT_FOUND)
        user.role.isAdmin.requireTrue(ErrorCode.AI_ADMIN_REQUIRED)
    }
}
