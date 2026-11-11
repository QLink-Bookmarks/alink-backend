package com.qlink.auth.service

import com.qlink.auth.domain.AuthSessionScope
import com.qlink.auth.dto.RotatedAuthTokenResult
import com.qlink.auth.repository.RefreshTokenRepository
import com.qlink.common.transaction.TransactionRunner
import com.qlink.user.repository.UserRepository

class RefreshAdminAuthTokenService(
    private val tx: TransactionRunner,
    private val userRepository: UserRepository,
    private val refreshTokenRepository: RefreshTokenRepository,
    private val authTokenService: AuthTokenService,
) {
    suspend fun refresh(refreshToken: String?): RotatedAuthTokenResult =
        RefreshTokenRotator(
            tx = tx,
            userRepository = userRepository,
            refreshTokenRepository = refreshTokenRepository,
            authTokenService = authTokenService,
        ).refresh(refreshToken = refreshToken, scope = AuthSessionScope.ADMIN)
}
