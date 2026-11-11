package com.qlink.auth.service

import com.qlink.auth.client.GoogleAuthResourceClient
import com.qlink.auth.domain.AuthAdminPolicy
import com.qlink.auth.domain.AuthProviderType
import com.qlink.auth.domain.AuthSessionScope
import com.qlink.auth.domain.RefreshToken
import com.qlink.auth.dto.AdminSignInRequest
import com.qlink.auth.dto.IssuedRefreshToken
import com.qlink.auth.dto.RotatedAuthTokenResult
import com.qlink.auth.repository.AuthProviderRepository
import com.qlink.auth.repository.RefreshTokenRepository
import com.qlink.common.error.BusinessException
import com.qlink.common.error.ErrorCode
import com.qlink.common.transaction.TransactionRunner
import com.qlink.user.repository.UserRepository
import kotlin.time.Clock

class SignInAdminService(
    private val tx: TransactionRunner,
    private val userRepository: UserRepository,
    private val authProviderRepository: AuthProviderRepository,
    private val refreshTokenRepository: RefreshTokenRepository,
    private val googleClient: GoogleAuthResourceClient,
    private val authTokenService: AuthTokenService,
) {
    suspend fun signIn(request: AdminSignInRequest): RotatedAuthTokenResult {
        val type = AuthProviderType.fromRequestName(request.provider)
        type.validateAdminSupported()
        val subject = googleClient.getAdminSubject(request.token)
        return tx.required {
            val provider =
                authProviderRepository.findByProvider(providerType = type, providerId = subject)
                    ?: throw BusinessException(ErrorCode.AUTH_ADMIN_REQUIRED)
            val user = userRepository.findById(provider.userId) ?: throw BusinessException(ErrorCode.AUTH_ADMIN_REQUIRED)
            AuthAdminPolicy.validate(user.role.isAdmin)
            val now = Clock.System.now()
            val token = authTokenService.issueRefreshToken(userId = provider.userId, scope = AuthSessionScope.ADMIN)
            val expires = authTokenService.refreshTokenExpiredAt(now)
            refreshTokenRepository.insert(
                RefreshToken(
                    userId = provider.userId,
                    familyId = authTokenService.verifyRefreshToken(token).familyId,
                    token = token,
                    issuedAt = now,
                    expiredAt = expires,
                ),
            )
            RotatedAuthTokenResult(
                accessToken = authTokenService.issueAccessToken(userId = provider.userId, role = user.role),
                refreshToken = IssuedRefreshToken(token = token, maxAge = (expires - now).inWholeSeconds.toInt()),
            )
        }
    }
}
