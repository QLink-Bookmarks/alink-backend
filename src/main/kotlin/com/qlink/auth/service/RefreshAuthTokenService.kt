package com.qlink.auth.service

import com.qlink.auth.domain.RefreshToken
import com.qlink.auth.domain.AuthSessionScope
import com.qlink.auth.dto.IssuedRefreshToken
import com.qlink.auth.dto.RotatedAuthTokenResult
import com.qlink.auth.repository.RefreshTokenRepository
import com.qlink.common.error.BusinessException
import com.qlink.common.error.ErrorCode
import com.qlink.common.error.requirePresent
import com.qlink.common.transaction.TransactionRunner
import com.qlink.user.domain.User
import com.qlink.user.repository.UserRepository
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

class RefreshAuthTokenService(
    private val tx: TransactionRunner,
    private val userRepository: UserRepository,
    private val refreshTokenRepository: RefreshTokenRepository,
    private val authTokenService: AuthTokenService,
) {
    suspend fun refresh(refreshToken: String?): RotatedAuthTokenResult =
        RefreshTokenRotator(tx = tx, userRepository = userRepository, refreshTokenRepository = refreshTokenRepository, authTokenService = authTokenService)
            .refresh(refreshToken = refreshToken, scope = AuthSessionScope.CHANNEL)
}
