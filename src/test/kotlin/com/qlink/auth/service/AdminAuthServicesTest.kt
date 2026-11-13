package com.qlink.auth.service

import com.qlink.auth.domain.AuthProvider
import com.qlink.auth.domain.AuthProviderType
import com.qlink.auth.domain.AuthSessionScope
import com.qlink.auth.domain.RefreshToken
import com.qlink.auth.domain.Role
import com.qlink.auth.dto.AdminSignInRequest
import com.qlink.auth.repository.AuthProviderRepository
import com.qlink.auth.repository.RefreshTokenRepository
import com.qlink.common.error.BusinessException
import com.qlink.common.error.ErrorCode
import com.qlink.config.GoogleConfig
import com.qlink.config.SecurityConfig
import com.qlink.folder.repository.FolderRepository
import com.qlink.support.BaseServiceTest
import com.qlink.support.GoogleTestKeys
import com.qlink.support.MockAuthHttpEngine
import com.qlink.support.fixture.RandomFixture
import com.qlink.support.fixture.StoredRefreshTokenFixture
import com.qlink.support.fixture.UserFixture
import com.qlink.support.fixture.UserRefreshTokenFixture
import com.qlink.support.fixture.copyForTestRole
import com.qlink.support.koinGet
import com.qlink.user.domain.User
import com.qlink.user.repository.UserRepository
import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.assertions.throwables.shouldThrowWithMessage
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes

class AdminAuthServicesTest :
    BaseServiceTest({
        val users = koinGet<UserRepository>()
        val providers = koinGet<AuthProviderRepository>()
        val tokens = koinGet<RefreshTokenRepository>()
        val tokenService = koinGet<AuthTokenService>()
        val signIn = koinGet<SignInAdminService>()
        val refresh = koinGet<RefreshAuthTokenService>()
        val signOut = koinGet<SignOutService>()
        val googleConfig = koinGet<GoogleConfig>()
        val engine = koinGet<MockAuthHttpEngine>()
        Given("관리자 Google 연동 계정") {
            lateinit var admin: User
            lateinit var normal: User
            lateinit var subject: String
            lateinit var adminToken: RefreshToken
            lateinit var channelToken: RefreshToken
            beforeTest {
                if (it.type != io.kotest.core.test.TestType.Test) return@beforeTest
                (googleConfig.adminClientIds as MutableList<String>).apply {
                    clear()
                    add("admin.apps.googleusercontent.com")
                }
                engine.respondJson(GoogleTestKeys.jwks())
                admin = users.insert(UserFixture.createRandomValidSuperAdmin())
                normal = users.insert(UserFixture.createRandomValidUser())
                subject = RandomFixture.randomId().toString()
                providers.insert(AuthProvider(userId = admin.id!!, providerType = AuthProviderType.GOOGLE, providerId = subject))
                val now = Clock.System.now()
                val adminValue = tokenService.issueRefreshToken(userId = admin.id!!, scope = AuthSessionScope.ADMIN)
                adminToken =
                    tokens.insert(
                        UserRefreshTokenFixture.createForUser(
                            userId = admin.id!!,
                            familyId = tokenService.verifyRefreshToken(adminValue).familyId,
                            token = adminValue,
                            issuedAt = now,
                        ),
                    )
                val channelValue =
                    UserRefreshTokenFixture.issueSignedTokenAt(
                        securityConfig = koinGet<SecurityConfig>(),
                        userId = admin.id!!,
                        familyId = StoredRefreshTokenFixture.randomFamilyId(),
                        issuedAt = now,
                    )
                channelToken =
                    tokens.insert(
                        UserRefreshTokenFixture.createForUser(
                            userId = admin.id!!,
                            familyId = tokenService.verifyRefreshToken(channelValue).familyId,
                            token = channelValue,
                            issuedAt = now,
                        ),
                    )
            }
            When("관리자 로그인은 ADMIN 세션만 저장하고 가입하지 않는다") {
                val action =
                    suspend {
                        signIn.signIn(
                            AdminSignInRequest(
                                provider = "GOOGLE",
                                token =
                                    GoogleTestKeys.idToken(
                                        subject = subject,
                                        audience = googleConfig.adminClientIds.single(),
                                    ),
                            ),
                        )
                    }
                Then("저장소 상태와 응답을 확인한다") {
                    val userIds = users.findAllIds()
                    val folders = koinGet<FolderRepository>().findAllByOwnerId(admin.id!!)
                    val result = action()
                    tokenService.verifyRefreshToken(result.refreshToken.token).scope shouldBe AuthSessionScope.ADMIN
                    tokens.findByToken(result.refreshToken.token).shouldNotBeNull()
                    users.findAllIds() shouldBe userIds
                    providers.findAllByUserId(admin.id!!).size shouldBe 1
                    koinGet<FolderRepository>().findAllByOwnerId(admin.id!!) shouldBe folders
                }
            }
            When("현재 DB 역할이 ADMIN인 계정도 로그인한다") {
                beforeTest {
                    if (it.type != io.kotest.core.test.TestType.Test) return@beforeTest
                    users.update(admin.copyForTestRole(Role.ADMIN))
                }
                Then("관리자 범위 토큰이 저장된다") {
                    val result =
                        signIn.signIn(
                            AdminSignInRequest(
                                provider = "GOOGLE",
                                token = GoogleTestKeys.idToken(subject = subject, audience = googleConfig.adminClientIds.single()),
                            ),
                        )
                    tokenService.verifyRefreshToken(result.refreshToken.token).scope shouldBe AuthSessionScope.ADMIN
                    tokens.findByToken(result.refreshToken.token).shouldNotBeNull()
                }
            }
            When("연동되지 않은 계정은 가입·토큰 없이 거절") {
                val action =
                    suspend {
                        signIn.signIn(
                            AdminSignInRequest(
                                provider = "GOOGLE",
                                token =
                                    GoogleTestKeys.idToken(
                                        subject = RandomFixture.randomId().toString(),
                                        audience = googleConfig.adminClientIds.single(),
                                    ),
                            ),
                        )
                    }
                Then("저장소 상태와 응답을 확인한다") {
                    val before = users.findAllIds()
                    shouldThrowWithMessage<BusinessException>(ErrorCode.AUTH_ADMIN_REQUIRED.message) { action() }
                    users.findAllIds() shouldBe before
                    providers.findAllByUserId(admin.id!!).size shouldBe 1
                }
            }
            When("연동된 일반 역할은 거절") {
                beforeTest {
                    if (it.type != io.kotest.core.test.TestType.Test) return@beforeTest
                    users.update(admin.copyForTestRole(Role.NORMAL))
                }
                val action =
                    suspend {
                        signIn.signIn(
                            AdminSignInRequest(
                                provider = "GOOGLE",
                                token =
                                    GoogleTestKeys.idToken(
                                        subject = subject,
                                        audience = googleConfig.adminClientIds.single(),
                                    ),
                            ),
                        )
                    }
                Then("저장소 상태와 응답을 확인한다") {
                    shouldThrowWithMessage<BusinessException>(ErrorCode.AUTH_ADMIN_REQUIRED.message) { action() }
                    tokens.findLatestByUserIdAndFamilyId(userId = admin.id!!, familyId = adminToken.familyId) shouldBe adminToken
                }
            }
            When("Google 외 제공자 거절") {
                val action =
                    suspend {
                        signIn.signIn(AdminSignInRequest(provider = "KAKAO", token = RandomFixture.randomFixedSentence(30)))
                    }
                Then("저장소 상태와 응답을 확인한다") {
                    shouldThrowWithMessage<BusinessException>(ErrorCode.AUTH_ADMIN_PROVIDER_NOT_SUPPORTED.message) { action() }
                }
            }
            When("다른 audience ID 토큰 거절") {
                val action =
                    suspend {
                        signIn.signIn(AdminSignInRequest(provider = "GOOGLE", token = GoogleTestKeys.idToken(subject = subject)))
                    }
                Then("저장소 상태와 응답을 확인한다") {
                    shouldThrowWithMessage<BusinessException>(ErrorCode.AUTH_PROVIDER_TOKEN_INVALID.message) { action() }
                    tokens.findByToken(adminToken.token) shouldBe adminToken
                }
            }
            When("관리자 audience 설정이 비어 있으면 거절") {
                beforeTest {
                    if (it.type != io.kotest.core.test.TestType.Test) return@beforeTest
                    (googleConfig.adminClientIds as MutableList<String>).clear()
                }
                val action =
                    suspend {
                        signIn.signIn(AdminSignInRequest(provider = "GOOGLE", token = GoogleTestKeys.idToken(subject = subject)))
                    }
                Then("저장소 상태와 응답을 확인한다") {
                    shouldThrowWithMessage<BusinessException>(ErrorCode.AUTH_PROVIDER_TOKEN_INVALID.message) { action() }
                }
            }
            When("관리자 토큰 회전·유예 재사용은 최신 토큰 유지") {
                val action = suspend { refresh.refresh(refreshToken = adminToken.token, scope = AuthSessionScope.ADMIN) }
                Then("저장소 상태와 응답을 확인한다") {
                    val first = action()
                    first.refreshToken.token shouldNotBe adminToken.token
                    val second = action()
                    second.refreshToken.token shouldBe first.refreshToken.token
                    tokenService.verifyRefreshToken(first.refreshToken.token).scope shouldBe AuthSessionScope.ADMIN
                    tokens.findByToken(adminToken.token).shouldBeNull()
                    tokens.findByToken(first.refreshToken.token).shouldNotBeNull()
                }
            }
            When("유예 이후 재사용은 family 제거") {
                beforeTest {
                    if (it.type != io.kotest.core.test.TestType.Test) return@beforeTest
                    tokens.deleteByUserIdAndToken(userId = admin.id!!, token = adminToken.token)
                    val latest =
                        tokenService.issueRefreshToken(
                            userId = admin.id!!,
                            familyId = adminToken.familyId,
                            scope = AuthSessionScope.ADMIN,
                        )
                    tokens.insert(
                        UserRefreshTokenFixture.createForUser(
                            userId = admin.id!!,
                            familyId = adminToken.familyId,
                            token = latest,
                            issuedAt = Clock.System.now() - 2.minutes,
                        ),
                    )
                }
                val action = suspend { refresh.refresh(refreshToken = adminToken.token, scope = AuthSessionScope.ADMIN) }
                Then("저장소 상태와 응답을 확인한다") {
                    shouldThrowWithMessage<BusinessException>(ErrorCode.AUTH_REFRESH_TOKEN_REUSED.message) { action() }
                    tokens.findLatestByUserIdAndFamilyId(userId = admin.id!!, familyId = adminToken.familyId).shouldBeNull()
                    tokens.findByToken(channelToken.token) shouldBe channelToken
                }
            }
            When("관리자 강등은 family 제거 후 거절") {
                beforeTest {
                    if (it.type != io.kotest.core.test.TestType.Test) return@beforeTest
                    users.update(admin.copyForTestRole(Role.NORMAL))
                }
                val action = suspend { refresh.refresh(refreshToken = adminToken.token, scope = AuthSessionScope.ADMIN) }
                Then("저장소 상태와 응답을 확인한다") {
                    shouldThrowWithMessage<BusinessException>(ErrorCode.AUTH_ADMIN_REQUIRED.message) { action() }
                    tokens.findLatestByUserIdAndFamilyId(userId = admin.id!!, familyId = adminToken.familyId).shouldBeNull()
                    tokens.findByToken(channelToken.token) shouldBe channelToken
                }
            }
            When("채널 토큰으로 관리자 갱신 거절·보존") {
                val action = suspend { refresh.refresh(refreshToken = channelToken.token, scope = AuthSessionScope.ADMIN) }
                Then("저장소 상태와 응답을 확인한다") {
                    shouldThrowWithMessage<BusinessException>(ErrorCode.AUTH_REFRESH_TOKEN_INVALID.message) { action() }
                    tokens.findByToken(channelToken.token) shouldBe channelToken
                }
            }
            When("관리자 토큰으로 채널 갱신 거절·보존") {
                val action = suspend { refresh.refresh(refreshToken = adminToken.token) }
                Then("저장소 상태와 응답을 확인한다") {
                    shouldThrowWithMessage<BusinessException>(ErrorCode.AUTH_REFRESH_TOKEN_INVALID.message) { action() }
                    tokens.findByToken(adminToken.token) shouldBe adminToken
                }
            }
            When("scope 없는 기존 채널 토큰 회전") {
                val action = suspend { refresh.refresh(refreshToken = channelToken.token) }
                Then("저장소 상태와 응답을 확인한다") {
                    val result = action()
                    tokenService.verifyRefreshToken(result.refreshToken.token).scope shouldBe AuthSessionScope.CHANNEL
                    tokens.findByToken(result.refreshToken.token).shouldNotBeNull()
                    tokens.findByToken(channelToken.token).shouldBeNull()
                }
            }
            When("쿠키 토큰 없음") {
                val action = suspend { refresh.refresh(refreshToken = null, scope = AuthSessionScope.ADMIN) }
                Then("저장소 상태와 응답을 확인한다") {
                    shouldThrowWithMessage<BusinessException>(ErrorCode.AUTH_REFRESH_TOKEN_MISSING.message) { action() }
                }
            }
            When("관리자 로그아웃은 채널 유지") {
                val action = suspend { signOut.signOut(loginId = admin.id!!, refreshToken = adminToken.token) }
                Then("저장소 상태와 응답을 확인한다") {
                    shouldNotThrowAny { action() }
                    tokens.findByToken(adminToken.token).shouldBeNull()
                    tokens.findByToken(channelToken.token) shouldBe channelToken
                }
            }
        }
    })
