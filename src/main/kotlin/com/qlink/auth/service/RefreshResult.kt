package com.qlink.auth.service

import com.qlink.auth.domain.RefreshToken
import com.qlink.user.domain.User
import com.qlink.common.error.ErrorCode

internal sealed interface RefreshResult {
    data class Success(
        val user: User,
        val refreshToken: RefreshToken,
    ) : RefreshResult

    data class Failure(
        val errorCode: ErrorCode,
    ) : RefreshResult
}
