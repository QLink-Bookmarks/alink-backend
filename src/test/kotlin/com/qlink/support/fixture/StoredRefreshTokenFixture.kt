package com.qlink.support.fixture

import com.qlink.auth.domain.RefreshToken
import com.qlink.auth.repository.RefreshTokenRepository
import com.qlink.config.SecurityConfig
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

object StoredRefreshTokenFixture {
    fun randomFamilyId(): String = "family-${RandomFixture.randomId()}"

    suspend fun insertRotatable(
        refreshTokenRepository: RefreshTokenRepository,
        securityConfig: SecurityConfig,
        userId: Long,
        familyId: String,
    ): RefreshToken =
        insertIssuedAt(
            refreshTokenRepository = refreshTokenRepository,
            securityConfig = securityConfig,
            userId = userId,
            familyId = familyId,
            issuedAt = Clock.System.now() - 2.minutes,
        )

    suspend fun insertLatest(
        refreshTokenRepository: RefreshTokenRepository,
        securityConfig: SecurityConfig,
        userId: Long,
        familyId: String,
    ): RefreshToken =
        insertIssuedAt(
            refreshTokenRepository = refreshTokenRepository,
            securityConfig = securityConfig,
            userId = userId,
            familyId = familyId,
            issuedAt = Clock.System.now(),
        )

    fun issueAlreadyRotated(
        securityConfig: SecurityConfig,
        userId: Long,
        familyId: String,
    ): String =
        UserRefreshTokenFixture.issueSignedTokenAt(
            securityConfig = securityConfig,
            userId = userId,
            familyId = familyId,
            issuedAt = Clock.System.now() - 1.minutes,
        )

    private suspend fun insertIssuedAt(
        refreshTokenRepository: RefreshTokenRepository,
        securityConfig: SecurityConfig,
        userId: Long,
        familyId: String,
        issuedAt: Instant,
    ): RefreshToken =
        refreshTokenRepository.insert(
            UserRefreshTokenFixture.createForUser(
                userId = userId,
                familyId = familyId,
                token =
                    UserRefreshTokenFixture.issueSignedTokenAt(
                        securityConfig = securityConfig,
                        userId = userId,
                        familyId = familyId,
                        issuedAt = issuedAt,
                    ),
                issuedAt = issuedAt,
                expiredAt = Clock.System.now() + 10.minutes,
            ),
        )
}
