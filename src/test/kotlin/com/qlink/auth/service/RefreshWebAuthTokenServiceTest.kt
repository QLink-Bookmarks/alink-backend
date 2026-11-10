package com.qlink.auth.service

import com.qlink.auth.domain.RefreshToken
import com.qlink.auth.repository.RefreshTokenRepository
import com.qlink.config.SecurityConfig
import com.qlink.support.BaseServiceTest
import com.qlink.support.fixture.StoredRefreshTokenFixture
import com.qlink.support.fixture.UserFixture
import com.qlink.support.koinGet
import com.qlink.user.domain.User
import com.qlink.user.repository.UserRepository
import io.kotest.matchers.longs.shouldBeInRange
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotBeBlank
import kotlin.time.Clock

class RefreshWebAuthTokenServiceTest :
    BaseServiceTest({
        val service = koinGet<RefreshWebAuthTokenService>()
        val userRepository = koinGet<UserRepository>()
        val refreshTokenRepository = koinGet<RefreshTokenRepository>()
        val authTokenService = koinGet<AuthTokenService>()
        val securityConfig = koinGet<SecurityConfig>()

        Given("저장된 refresh token으로 갱신할 때") {
            lateinit var user: User
            lateinit var familyId: String
            lateinit var currentToken: RefreshToken

            beforeTest {
                user = userRepository.insert(UserFixture.createRandomValidUser())
                familyId = StoredRefreshTokenFixture.randomFamilyId()
                currentToken =
                    StoredRefreshTokenFixture.insertRotatable(
                        refreshTokenRepository = refreshTokenRepository,
                        securityConfig = securityConfig,
                        userId = requireNotNull(user.id),
                        familyId = familyId,
                    )
            }

            When("웹에서 갱신하면") {
                val refresh = suspend { service.refresh(currentToken.token) }

                Then("응답 본문에 refresh token이 없고 쿠키로 보낼 새 token으로 회전되어 저장된다") {
                    val result = refresh()
                    val claims = authTokenService.verifyRefreshToken(result.cookie.token)
                    val persistedRefreshToken = refreshTokenRepository.findByToken(result.cookie.token)

                    result.response.accessToken.shouldNotBeBlank()
                    claims.userId shouldBe user.id
                    claims.familyId shouldBe familyId
                    persistedRefreshToken.shouldNotBeNull()
                    persistedRefreshToken.id shouldBe currentToken.id
                    refreshTokenRepository.findByToken(currentToken.token).shouldBeNull()
                }

                Then("쿠키 수명이 회전된 token의 저장된 만료 시각까지 남은 시간과 같고 refresh token 수명 설정과 같다") {
                    val result = refresh()
                    val persistedRefreshToken = refreshTokenRepository.findByToken(result.cookie.token).shouldNotBeNull()

                    result.cookie.maxAge.toLong() shouldBe (persistedRefreshToken.expiredAt - persistedRefreshToken.issuedAt).inWholeSeconds
                    result.cookie.maxAge shouldBe securityConfig.refreshDurationSeconds
                }
            }
        }

        Given("동시 갱신으로 기존 token이 이미 회전됐고 최신 token이 1분 이내일 때") {
            lateinit var requestedToken: String
            lateinit var latestToken: RefreshToken

            beforeTest {
                val userId = requireNotNull(userRepository.insert(UserFixture.createRandomValidUser()).id)
                val familyId = StoredRefreshTokenFixture.randomFamilyId()
                requestedToken =
                    StoredRefreshTokenFixture.issueAlreadyRotated(
                        securityConfig = securityConfig,
                        userId = userId,
                        familyId = familyId,
                    )
                latestToken =
                    StoredRefreshTokenFixture.insertLatest(
                        refreshTokenRepository = refreshTokenRepository,
                        securityConfig = securityConfig,
                        userId = userId,
                        familyId = familyId,
                    )
            }

            When("웹에서 갱신하면") {
                val refresh = suspend { service.refresh(requestedToken) }

                Then("응답 본문에 refresh token이 없고 쿠키로 보낼 token이 저장된 최신 token과 같으며 쿠키 수명이 그 최신 token의 만료 시각까지 남은 시간과 같다") {
                    val remainingBefore = (latestToken.expiredAt - Clock.System.now()).inWholeSeconds
                    val result = refresh()
                    val remainingAfter = (latestToken.expiredAt - Clock.System.now()).inWholeSeconds

                    result.response.accessToken.shouldNotBeBlank()
                    result.cookie.token shouldBe latestToken.token
                    result.cookie.maxAge.toLong() shouldBeInRange remainingAfter..remainingBefore
                    refreshTokenRepository.findByToken(latestToken.token) shouldBe latestToken
                }
            }
        }
    })
