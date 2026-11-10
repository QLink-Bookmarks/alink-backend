package com.qlink.auth.service

import com.qlink.auth.client.AuthResourceClientRouter
import com.qlink.auth.domain.AuthProvider
import com.qlink.auth.domain.AuthProviderType
import com.qlink.auth.domain.RefreshToken
import com.qlink.auth.dto.AuthPlatform
import com.qlink.auth.dto.RefreshTokenCookie
import com.qlink.auth.dto.SignInRequest
import com.qlink.auth.dto.SignInResponse
import com.qlink.auth.dto.SignInResult
import com.qlink.auth.repository.AuthProviderRepository
import com.qlink.auth.repository.RefreshTokenRepository
import com.qlink.common.error.ErrorCode
import com.qlink.common.error.requirePresent
import com.qlink.common.transaction.TransactionRunner
import com.qlink.folder.domain.Folder
import com.qlink.folder.repository.FolderRepository
import com.qlink.guide.domain.GuideDefinition
import com.qlink.guide.domain.GuidePage
import com.qlink.link.domain.Link
import com.qlink.link.repository.LinkRepository
import com.qlink.linkcontent.domain.CollectedPage
import com.qlink.linkcontent.domain.ContentPriority
import com.qlink.linkcontent.domain.LinkContent
import com.qlink.linkcontent.repository.LinkContentRepository
import com.qlink.user.domain.User
import com.qlink.user.repository.UserRepository
import kotlin.time.Clock
import kotlin.time.Instant

class SignInService(
    private val tx: TransactionRunner,
    private val userRepository: UserRepository,
    private val authProviderRepository: AuthProviderRepository,
    private val refreshTokenRepository: RefreshTokenRepository,
    private val authResourceClientRouter: AuthResourceClientRouter,
    private val authTokenService: AuthTokenService,
    private val randomUserNameGenerator: RandomUserNameGenerator,
    private val folderRepository: FolderRepository,
    private val linkRepository: LinkRepository,
    private val linkContentRepository: LinkContentRepository,
) {
    suspend fun signIn(request: SignInRequest): SignInResult {
        val providerType = AuthProviderType.fromRequestName(request.provider)
        val resource =
            authResourceClientRouter.getResource(
                providerType = providerType,
                token = request.token,
                platform = request.platform,
            )

        return tx.required {
            val authProvider =
                authProviderRepository.findByProvider(
                    providerType = resource.providerType,
                    providerId = resource.providerId,
                )
            val user =
                if (authProvider == null) {
                    signUp(
                        providerType = resource.providerType,
                        providerId = resource.providerId,
                    )
                } else {
                    userRepository.findById(authProvider.userId).requirePresent(ErrorCode.AUTH_SIGN_IN_USER_MISSING)
                }

            issueTokens(user = user, platform = request.platform)
        }
    }

    private suspend fun signUp(
        providerType: AuthProviderType,
        providerId: String,
    ): User {
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

        provisionGuideFolder(ownerId = userId, now = Clock.System.now())
        return user
    }

    private suspend fun provisionGuideFolder(
        ownerId: Long,
        now: Instant,
    ) {
        val folder = folderRepository.insert(Folder.createGuide(ownerId))
        val folderId = folder.id.requirePresent(ErrorCode.AUTH_SIGN_IN_FOLDER_ID_REQUIRED)
        for (definition in GuideDefinition.entries.reversed()) {
            val link =
                linkRepository.insert(
                    Link.createGuide(
                        ownerId = ownerId,
                        folderId = folderId,
                        url = definition.url,
                        title = definition.title,
                        tags = definition.tags,
                        thumbnailUrl = definition.preview.thumbnailUrl,
                        faviconUrl = definition.preview.faviconUrl,
                        isVideo = definition.preview.isVideo,
                        now = now,
                    ),
                )
            val linkId = link.id.requirePresent(ErrorCode.AUTH_SIGN_IN_LINK_ID_REQUIRED)
            val content =
                definition.page?.let { page ->
                    LinkContent.createCollected(linkId = linkId, sourceUrl = link.url, page = page.toCollectedPage(), now = now)
                } ?: LinkContent.create(linkId = linkId, sourceUrl = link.url, priority = ContentPriority.INTERACTIVE, now = now)
            linkContentRepository.insertAll(listOf(content))
        }
    }

    private fun GuidePage.toCollectedPage(): CollectedPage =
        CollectedPage(
            text = text,
            title = title,
            siteName = siteName,
            author = author,
            publishedAt = publishedAt,
            canonicalUrl = canonicalUrl,
            finalUrl = finalUrl,
            language = language,
            isTruncated = isTruncated,
        )

    private suspend fun issueTokens(
        user: User,
        platform: AuthPlatform,
    ): SignInResult {
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

        val isWeb = platform == AuthPlatform.WEB

        return SignInResult(
            response =
                SignInResponse(
                    accessToken =
                        authTokenService.issueAccessToken(
                            userId = userId,
                            role = user.role,
                        ),
                    refreshToken = refreshToken.takeUnless { isWeb },
                    allowsPrivacy = user.allowsPrivacy,
                    allowsAiUsage = user.allowsAiUsage,
                ),
            cookie =
                RefreshTokenCookie(
                    token = refreshToken,
                    maxAge = (expiredAt - now).inWholeSeconds.toInt(),
                ).takeIf { isWeb },
        )
    }
}
