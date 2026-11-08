package com.qlink.auth.service

import com.qlink.auth.client.AuthResourceClientRouter
import com.qlink.auth.domain.AuthProvider
import com.qlink.auth.domain.AuthProviderType
import com.qlink.auth.domain.RefreshToken
import com.qlink.auth.dto.SignInRequest
import com.qlink.auth.dto.SignInResponse
import com.qlink.auth.dto.SignInResult
import com.qlink.auth.repository.AuthProviderRepository
import com.qlink.auth.repository.RefreshTokenRepository
import com.qlink.common.error.ErrorCode
import com.qlink.common.error.requirePresent
import com.qlink.common.transaction.TransactionRunner
import com.qlink.folder.service.ProvisionGuideFolderService
import com.qlink.link.worker.LinkPreviewFillSignal
import com.qlink.linkcontent.worker.LinkContentSignal
import com.qlink.user.domain.User
import com.qlink.user.repository.UserRepository
import kotlin.time.Clock

class SignInService(
    private val tx: TransactionRunner,
    private val userRepository: UserRepository,
    private val authProviderRepository: AuthProviderRepository,
    private val refreshTokenRepository: RefreshTokenRepository,
    private val authResourceClientRouter: AuthResourceClientRouter,
    private val authTokenService: AuthTokenService,
    private val randomUserNameGenerator: RandomUserNameGenerator,
    private val provisionGuideFolderService: ProvisionGuideFolderService,
    private val linkPreviewFillSignal: LinkPreviewFillSignal,
    private val linkContentSignal: LinkContentSignal,
) {
    suspend fun signIn(request: SignInRequest): SignInResponse {
        val providerType = AuthProviderType.fromRequestName(request.provider)
        val resource =
            authResourceClientRouter.getResource(
                providerType = providerType,
                token = request.token,
                platform = request.platform,
            )

        val result =
            tx.required {
                val authProvider =
                    authProviderRepository.findByProvider(
                        providerType = resource.providerType,
                        providerId = resource.providerId,
                    )
                if (authProvider == null) {
                    signUp(
                        providerType = resource.providerType,
                        providerId = resource.providerId,
                    )
                } else {
                    val user = userRepository.findById(authProvider.userId).requirePresent(ErrorCode.AUTH_SIGN_IN_USER_MISSING)
                    SignInResult(response = issueTokenResponse(user))
                }
            }
        result.previewFillLinkIds.forEach(linkPreviewFillSignal::wake)
        if (result.hasPendingContent) linkContentSignal.wake()
        return result.response
    }

    private suspend fun signUp(
        providerType: AuthProviderType,
        providerId: String,
    ): SignInResult {
        val generatedName = randomUserNameGenerator.generate()
        val user =
            userRepository.insert(
                User(
                    username = generatedName.username,
                    nickname = generatedName.nickname,
                ),
            )
        val userId = user.id.requirePresent(ErrorCode.AUTH_SIGN_IN_USER_ID_REQUIRED)

        authProviderRepository.insert(
            AuthProvider(
                userId = userId,
                providerType = providerType,
                providerId = providerId,
            ),
        )

        val provision = provisionGuideFolderService.provision(ownerId = userId, now = Clock.System.now())
        return SignInResult(
            response = issueTokenResponse(user),
            previewFillLinkIds = provision.previewFillLinkIds,
            hasPendingContent = provision.hasPendingContent,
        )
    }

    private suspend fun issueTokenResponse(user: User): SignInResponse {
        val userId = user.id.requirePresent(ErrorCode.AUTH_SIGN_IN_USER_ID_REQUIRED)
        val now = Clock.System.now()
        val expiredAt = authTokenService.refreshTokenExpiredAt(now)
        val refreshToken = authTokenService.issueRefreshToken(userId = userId)

        refreshTokenRepository.insert(
            RefreshToken(
                userId = userId,
                familyId = authTokenService.verifyRefreshToken(refreshToken).familyId,
                token = refreshToken,
                issuedAt = now,
                expiredAt = expiredAt,
            ),
        )

        return SignInResponse(
            accessToken =
                authTokenService.issueAccessToken(
                    userId = userId,
                    role = user.role,
                ),
            refreshToken = refreshToken,
            allowsPrivacy = user.allowsPrivacy,
            allowsAiUsage = user.allowsAiUsage,
        )
    }
}
