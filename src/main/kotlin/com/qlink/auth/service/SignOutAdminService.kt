package com.qlink.auth.service

import com.qlink.auth.domain.AuthSessionScope
import com.qlink.auth.repository.RefreshTokenRepository
import com.qlink.common.transaction.TransactionRunner

class SignOutAdminService(
    private val tx: TransactionRunner,
    private val refreshTokenRepository: RefreshTokenRepository,
    private val authTokenService: AuthTokenService,
) {
    suspend fun signOut(loginId: Long, refreshToken: String?) = tx.required {
        val claims = refreshToken?.let { runCatching { authTokenService.verifyRefreshToken(it) }.getOrNull() }
        if (claims?.scope == AuthSessionScope.ADMIN && claims.userId == loginId) {
            refreshTokenRepository.deleteByUserIdAndToken(userId = loginId, token = refreshToken)
        }
    }
}
