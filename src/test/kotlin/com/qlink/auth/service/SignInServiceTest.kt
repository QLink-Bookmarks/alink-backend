package com.qlink.auth.service

import com.qlink.auth.domain.AuthProvider
import com.qlink.auth.domain.AuthProviderType
import com.qlink.auth.dto.AuthPlatform
import com.qlink.auth.dto.SignInRequest
import com.qlink.auth.repository.AuthProviderRepository
import com.qlink.auth.repository.RefreshTokenRepository
import com.qlink.common.error.BusinessException
import com.qlink.common.error.ErrorCode
import com.qlink.config.SecurityConfig
import com.qlink.support.AppleTestKeys
import com.qlink.support.BaseServiceTest
import com.qlink.support.FakeAuthResourceClient
import com.qlink.support.GoogleTestKeys
import com.qlink.support.MockAuthHttpEngine
import com.qlink.support.fixture.AuthRequestFixture
import com.qlink.support.fixture.RandomFixture
import com.qlink.support.fixture.UserFixture
import com.qlink.support.koinGet
import com.qlink.user.repository.UserRepository
import io.kotest.assertions.throwables.shouldThrowWithMessage
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.ktor.http.HttpStatusCode

class SignInServiceTest :
    BaseServiceTest({
        val service = koinGet<SignInService>()
        val userRepository = koinGet<UserRepository>()
        val authProviderRepository = koinGet<AuthProviderRepository>()
        val refreshTokenRepository = koinGet<RefreshTokenRepository>()
        val authTokenService = koinGet<AuthTokenService>()
        val authResourceClient = koinGet<FakeAuthResourceClient>()
        val mockAuthHttpEngine = koinGet<MockAuthHttpEngine>()
        val securityConfig = koinGet<SecurityConfig>()

        suspend fun insertKakaoUser(): Long {
            val userId = requireNotNull(userRepository.insert(UserFixture.createRandomValidUser()).id)
            val providerId = "kakao-${RandomFixture.randomId()}"
            authProviderRepository.insert(
                AuthProvider(
                    userId = userId,
                    providerType = AuthProviderType.KAKAO,
                    providerId = providerId,
                ),
            )
            authResourceClient.reset()
            authResourceClient.providerId = providerId
            return userId
        }

        Given("등록된 사용자가 로그인할 때") {
            When("WEB 플랫폼이면") {
                val userId = insertKakaoUser()

                val result = service.signIn(AuthRequestFixture.createKakaoSignInRequest(platform = AuthPlatform.WEB))
                val persistedRefreshToken = result.cookie?.let { refreshTokenRepository.findByToken(it.token) }

                Then("응답 본문의 refresh token은 null이고 쿠키로 보낼 refresh token이 그 사용자의 token으로 저장된다") {
                    result.response.refreshToken.shouldBeNull()
                    persistedRefreshToken.shouldNotBeNull()
                    persistedRefreshToken.userId shouldBe userId
                }

                Then("쿠키 수명이 저장된 그 token의 만료 시각까지 남은 시간과 같고 refresh token 수명 설정과 같다") {
                    val cookie = result.cookie.shouldNotBeNull()
                    persistedRefreshToken.shouldNotBeNull()
                    cookie.maxAge.toLong() shouldBe (persistedRefreshToken.expiredAt - persistedRefreshToken.issuedAt).inWholeSeconds
                    cookie.maxAge shouldBe securityConfig.refreshDurationSeconds
                }
            }

            When("NATIVE 플랫폼이면") {
                val userId = insertKakaoUser()

                val result = service.signIn(AuthRequestFixture.createKakaoSignInRequest(platform = AuthPlatform.NATIVE))
                val persistedRefreshToken = result.response.refreshToken?.let { refreshTokenRepository.findByToken(it) }

                Then("응답 본문의 refresh token이 그 사용자의 token으로 저장된다") {
                    result.cookie.shouldBeNull()
                    persistedRefreshToken.shouldNotBeNull()
                    persistedRefreshToken.userId shouldBe userId
                }
            }
        }

        Given("등록되지 않은 사용자가 로그인할 때") {
            When("kakao WEB 플랫폼이면") {
                authResourceClient.reset()
                val providerId = "kakao-${RandomFixture.randomId()}"
                authResourceClient.providerId = providerId

                val result = service.signIn(AuthRequestFixture.createKakaoSignInRequest(platform = AuthPlatform.WEB))
                val authProvider =
                    authProviderRepository.findByProvider(
                        providerType = AuthProviderType.KAKAO,
                        providerId = providerId,
                    )
                val persistedRefreshToken = result.cookie?.let { refreshTokenRepository.findByToken(it.token) }

                Then(
                    "사용자와 인증 제공자를 저장하고, 응답 본문의 refresh token은 null이며 쿠키로 보낼 token이 그 사용자의 token으로 저장되고 쿠키 수명이 그 token의 만료 시각까지 남은 시간과 같다",
                ) {
                    result.response.accessToken.shouldNotBeNull()
                    result.response.refreshToken.shouldBeNull()
                    authProvider.shouldNotBeNull()
                    userRepository.findById(authProvider.userId).shouldNotBeNull()
                    persistedRefreshToken.shouldNotBeNull()
                    persistedRefreshToken.userId shouldBe authProvider.userId
                    result.cookie
                        .shouldNotBeNull()
                        .maxAge
                        .toLong() shouldBe
                        (persistedRefreshToken.expiredAt - persistedRefreshToken.issuedAt).inWholeSeconds
                }
            }

            When("kakao NATIVE 플랫폼이면") {
                authResourceClient.reset()
                val providerId = "kakao-${RandomFixture.randomId()}"
                authResourceClient.providerId = providerId
                val result =
                    service.signIn(
                        SignInRequest(
                            provider = "kakao",
                            token = "oauth-token",
                            platform = AuthPlatform.NATIVE,
                        ),
                    )
                val response = result.response
                val refreshToken = requireNotNull(response.refreshToken)
                val refreshTokenClaims = authTokenService.verifyRefreshToken(refreshToken)
                val authProvider =
                    authProviderRepository.findByProvider(
                        providerType = AuthProviderType.KAKAO,
                        providerId = providerId,
                    )
                val user = userRepository.findById(refreshTokenClaims.userId)
                val persistedRefreshToken = refreshTokenRepository.findByToken(refreshToken)

                Then("회원가입 후 응답 본문의 refresh token을 발급하고 저장한다") {
                    authResourceClient.requestedTokens shouldContain "oauth-token"
                    result.cookie.shouldBeNull()
                    response.accessToken.shouldNotBeNull()
                    response.allowsPrivacy shouldBe false
                    response.allowsAiUsage shouldBe false
                    authProvider.shouldNotBeNull()
                    authProvider.userId shouldBe refreshTokenClaims.userId
                    user.shouldNotBeNull()
                    persistedRefreshToken.shouldNotBeNull()
                    persistedRefreshToken.userId shouldBe refreshTokenClaims.userId
                    persistedRefreshToken.familyId shouldBe refreshTokenClaims.familyId
                }
            }

            When("외부 인증 client가 실패하면") {
                authResourceClient.reset()
                authResourceClient.failure = BusinessException(ErrorCode.AUTH_PROVIDER_TOKEN_INVALID)
                val signIn =
                    suspend {
                        service.signIn(
                            SignInRequest(
                                provider = "kakao",
                                token = "invalid-oauth-token",
                                platform = AuthPlatform.NATIVE,
                            ),
                        )
                    }

                Then("외부 client 실패 예외가 발생한다") {
                    shouldThrowWithMessage<BusinessException>(ErrorCode.AUTH_PROVIDER_TOKEN_INVALID.message) {
                        signIn()
                    }
                }
            }

            When("google WEB provider user면") {
                mockAuthHttpEngine.reset()
                val providerId = "google-${RandomFixture.randomId()}"
                mockAuthHttpEngine.respondJson("""{"sub":"$providerId","email":"user@example.com"}""")

                val result =
                    service.signIn(
                        SignInRequest(
                            provider = "google",
                            token = "google-access-token",
                            platform = AuthPlatform.WEB,
                        ),
                    )
                val refreshTokenClaims = authTokenService.verifyRefreshToken(requireNotNull(result.cookie).token)
                val authProvider =
                    authProviderRepository.findByProvider(
                        providerType = AuthProviderType.GOOGLE,
                        providerId = providerId,
                    )
                val user = userRepository.findById(refreshTokenClaims.userId)

                Then("access token userinfo의 sub로 회원가입 후 token을 발급한다") {
                    result.response.accessToken.shouldNotBeNull()
                    authProvider.shouldNotBeNull()
                    authProvider.providerType shouldBe AuthProviderType.GOOGLE
                    authProvider.userId shouldBe refreshTokenClaims.userId
                    user.shouldNotBeNull()
                }
            }

            When("google NATIVE provider user면") {
                mockAuthHttpEngine.reset()
                mockAuthHttpEngine.respondJson(GoogleTestKeys.jwks())
                val providerId = "google-native-${RandomFixture.randomId()}"
                val idToken = GoogleTestKeys.idToken(subject = providerId)

                val result =
                    service.signIn(
                        SignInRequest(
                            provider = "google",
                            token = idToken,
                            platform = AuthPlatform.NATIVE,
                        ),
                    )
                val refreshTokenClaims = authTokenService.verifyRefreshToken(requireNotNull(result.response.refreshToken))
                val authProvider =
                    authProviderRepository.findByProvider(
                        providerType = AuthProviderType.GOOGLE,
                        providerId = providerId,
                    )
                val user = userRepository.findById(refreshTokenClaims.userId)

                Then("id_token의 sub로 회원가입 후 token을 발급한다") {
                    result.response.accessToken.shouldNotBeNull()
                    authProvider.shouldNotBeNull()
                    authProvider.providerType shouldBe AuthProviderType.GOOGLE
                    authProvider.userId shouldBe refreshTokenClaims.userId
                    user.shouldNotBeNull()
                }
            }

            When("naver provider user면") {
                mockAuthHttpEngine.reset()
                val providerId = "naver-${RandomFixture.randomId()}"
                mockAuthHttpEngine.respondJson(
                    """{"resultcode":"00","message":"success","response":{"id":"$providerId"}}""",
                )

                val result =
                    service.signIn(
                        SignInRequest(
                            provider = "naver",
                            token = "naver-access-token",
                            platform = AuthPlatform.NATIVE,
                        ),
                    )
                val refreshTokenClaims = authTokenService.verifyRefreshToken(requireNotNull(result.response.refreshToken))
                val authProvider =
                    authProviderRepository.findByProvider(
                        providerType = AuthProviderType.NAVER,
                        providerId = providerId,
                    )
                val user = userRepository.findById(refreshTokenClaims.userId)

                Then("naver userinfo의 id로 회원가입 후 token을 발급한다") {
                    result.response.accessToken.shouldNotBeNull()
                    authProvider.shouldNotBeNull()
                    authProvider.providerType shouldBe AuthProviderType.NAVER
                    authProvider.userId shouldBe refreshTokenClaims.userId
                    user.shouldNotBeNull()
                }
            }

            When("google WEB userinfo 요청이 실패 응답을 주면") {
                mockAuthHttpEngine.reset()
                mockAuthHttpEngine.respondJson("""{"error":"invalid_token"}""", HttpStatusCode.Unauthorized)
                val signIn =
                    suspend {
                        service.signIn(
                            SignInRequest(
                                provider = "google",
                                token = "invalid-google-token",
                                platform = AuthPlatform.WEB,
                            ),
                        )
                    }

                Then("외부 client 실패 예외가 발생한다") {
                    shouldThrowWithMessage<BusinessException>(ErrorCode.AUTH_PROVIDER_TOKEN_INVALID.message) {
                        signIn()
                    }
                }
            }

            When("apple provider user면") {
                mockAuthHttpEngine.reset()
                mockAuthHttpEngine.respondJson(AppleTestKeys.jwks())
                val providerId = "apple-${RandomFixture.randomId()}"
                val idToken = AppleTestKeys.idToken(subject = providerId)

                val result =
                    service.signIn(
                        SignInRequest(
                            provider = "apple",
                            token = idToken,
                            platform = AuthPlatform.NATIVE,
                        ),
                    )
                val refreshTokenClaims = authTokenService.verifyRefreshToken(requireNotNull(result.response.refreshToken))
                val authProvider =
                    authProviderRepository.findByProvider(
                        providerType = AuthProviderType.APPLE,
                        providerId = providerId,
                    )
                val user = userRepository.findById(refreshTokenClaims.userId)

                Then("id_token의 sub로 회원가입 후 token을 발급한다") {
                    result.response.accessToken.shouldNotBeNull()
                    authProvider.shouldNotBeNull()
                    authProvider.providerType shouldBe AuthProviderType.APPLE
                    authProvider.userId shouldBe refreshTokenClaims.userId
                    user.shouldNotBeNull()
                }
            }

            When("apple id_token 검증에 실패하면") {
                mockAuthHttpEngine.reset()
                mockAuthHttpEngine.respondJson(AppleTestKeys.jwks())
                val idToken = AppleTestKeys.idToken(subject = "u", signWithWrongKey = true)
                val signIn =
                    suspend {
                        service.signIn(
                            SignInRequest(
                                provider = "apple",
                                token = idToken,
                                platform = AuthPlatform.NATIVE,
                            ),
                        )
                    }

                Then("외부 client 실패 예외가 발생한다") {
                    shouldThrowWithMessage<BusinessException>(ErrorCode.AUTH_PROVIDER_TOKEN_INVALID.message) {
                        signIn()
                    }
                }
            }
        }
    })
