package com.qlink.ai.service

import com.qlink.ai.domain.AiProvider
import com.qlink.ai.domain.AiProviderType
import com.qlink.ai.domain.UserProvider
import com.qlink.ai.dto.PutAiSharedKeyRequest
import com.qlink.ai.repository.AiProviderRepository
import com.qlink.ai.repository.UserProviderRepository
import com.qlink.auth.domain.Role
import com.qlink.common.crypto.ApiKeyCipher
import com.qlink.common.error.BusinessException
import com.qlink.common.error.ErrorCode
import com.qlink.support.BaseServiceTest
import com.qlink.support.FakeAiClient
import com.qlink.support.fixture.AiFixture
import com.qlink.support.fixture.RandomFixture
import com.qlink.support.fixture.UserFixture
import com.qlink.support.fixture.copyForTestRole
import com.qlink.support.koinGet
import com.qlink.user.domain.User
import com.qlink.user.repository.UserRepository
import io.kotest.assertions.throwables.shouldThrowWithMessage
import io.kotest.core.test.TestType
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

class AiSharedKeyServicesTest :
    BaseServiceTest({
        val users = koinGet<UserRepository>()
        val providers = koinGet<AiProviderRepository>()
        val keys = koinGet<UserProviderRepository>()
        val getSharedKey = koinGet<GetAiSharedKeyService>()
        val putSharedKey = koinGet<PutAiSharedKeyService>()
        val getDailyUsage = koinGet<GetAiDailyUsageService>()
        val cipher = koinGet<ApiKeyCipher>()
        val vendor = koinGet<FakeAiClient>()

        Given("두 관리자와 공용 키가 없는 제공자") {
            lateinit var admin: User
            lateinit var nextAdmin: User
            lateinit var provider: AiProvider
            lateinit var validKey: String

            beforeTest {
                if (it.type != TestType.Test) {
                    return@beforeTest
                }
                vendor.reset()
                nextAdmin = users.insert(UserFixture.createRandomValidSuperAdmin())
                admin = users.insert(UserFixture.createRandomValidUser().copyForTestRole(Role.ADMIN))
                provider = providers.insert(AiFixture.createRandomValidAiProvider(type = AiProviderType.OPENAI, isActive = false))
                validKey = "dummy-key-${RandomFixture.randomId()}"
            }

            When("공용 키를 등록한 뒤 다른 관리자가 같은 제공자의 키를 교체하면") {
                lateinit var replacement: String

                beforeTest {
                    if (it.type != TestType.Test) {
                        return@beforeTest
                    }
                    replacement = "dummy-key-${RandomFixture.randomId()}"
                }

                Then("최초 관리자 소유권과 교체 관리자의 개인 키를 유지한다") {
                    val personal = keys.insert(AiFixture.createUserProviderOf(userId = nextAdmin.id!!, providerId = provider.id!!))
                    val providerId = provider.id!!
                    getSharedKey.getAiSharedKey(loginId = admin.id!!, providerId = providerId).lastFour shouldBe null
                    val created =
                        putSharedKey.putAiSharedKey(
                            loginId = admin.id!!,
                            providerId = providerId,
                            request = PutAiSharedKeyRequest(validKey),
                        )
                    created.lastFour shouldBe validKey.takeLast(4)
                    val shared = keys.findByUserIdAndProviderId(userId = admin.id!!, providerId = providerId)!!
                    shared.userId shouldBe admin.id
                    shared.isDefault shouldBe true
                    (shared.apiKey != validKey && cipher.decrypt(shared.apiKey) == validKey) shouldBe true

                    val rotated =
                        putSharedKey.putAiSharedKey(
                            loginId = nextAdmin.id!!,
                            providerId = providerId,
                            request = PutAiSharedKeyRequest(replacement),
                        )
                    val saved = keys.findByUserIdAndProviderId(userId = admin.id!!, providerId = providerId)!!
                    saved.id shouldBe shared.id
                    saved.userId shouldBe shared.userId
                    saved.userRole shouldBe shared.userRole
                    saved.createdAt shouldBe shared.createdAt
                    saved.apiKey shouldNotBe shared.apiKey
                    (cipher.decrypt(saved.apiKey) == replacement) shouldBe true
                    keys.findAllByRole(Role.SUPER_ADMIN).map { it.id } shouldBe listOf(shared.id)
                    keys.findById(personal.id!!)!!.apiKey shouldBe personal.apiKey
                    keys.findByUserIdAndProviderId(userId = nextAdmin.id!!, providerId = providerId)?.id shouldBe personal.id
                    rotated.lastFour shouldBe replacement.takeLast(4)
                    getSharedKey.getAiSharedKey(loginId = admin.id!!, providerId = providerId).lastFour shouldBe rotated.lastFour
                }
            }

            When("공용 행이 여러 개면") {
                lateinit var first: UserProvider
                lateinit var later: UserProvider
                lateinit var incoming: String

                beforeTest {
                    if (it.type != TestType.Test) {
                        return@beforeTest
                    }
                    val providerId = provider.id!!
                    first =
                        keys.insert(
                            UserProvider(
                                userId = nextAdmin.id!!,
                                providerId = providerId,
                                userRole = Role.SUPER_ADMIN,
                                apiKey = cipher.encrypt(validKey),
                            ),
                        )
                    later =
                        keys.insert(
                            UserProvider(
                                userId = admin.id!!,
                                providerId = providerId,
                                userRole = Role.SUPER_ADMIN,
                                apiKey = cipher.encrypt("dummy-other-${RandomFixture.randomId()}"),
                            ),
                        )
                    incoming = "dummy-replacement-${RandomFixture.randomId()}"
                }

                Then("채널과 같은 가장 작은 ID의 행을 표시하고 교체한다") {
                    val providerId = provider.id!!
                    getSharedKey.getAiSharedKey(loginId = admin.id!!, providerId = providerId).lastFour shouldBe validKey.takeLast(4)
                    putSharedKey.putAiSharedKey(loginId = admin.id!!, providerId = providerId, request = PutAiSharedKeyRequest(incoming))

                    val updated = keys.findById(first.id!!)!!
                    updated.userId shouldBe first.userId
                    updated.userRole shouldBe first.userRole
                    (cipher.decrypt(updated.apiKey) == incoming) shouldBe true
                    (keys.findById(later.id!!)!!.apiKey == later.apiKey) shouldBe true
                }
            }

            When("최초 등록 관리자가 같은 제공자에 개인 키를 이미 가지고 있으면") {
                lateinit var personal: UserProvider

                beforeTest {
                    if (it.type != TestType.Test) {
                        return@beforeTest
                    }
                    personal = keys.insert(AiFixture.createUserProviderOf(userId = admin.id!!, providerId = provider.id!!))
                }

                val register =
                    suspend {
                        putSharedKey.putAiSharedKey(
                            loginId = admin.id!!,
                            providerId = provider.id!!,
                            request = PutAiSharedKeyRequest(validKey),
                        )
                    }

                Then("충돌 오류를 반환하고 개인 행과 공용 키 상태를 보존한다") {
                    shouldThrowWithMessage<BusinessException>(ErrorCode.AI_SHARED_KEY_PERSONAL_CONFLICT.message) { register() }
                    val saved = keys.findById(personal.id!!)!!
                    saved.userRole shouldBe personal.userRole
                    (saved.apiKey == personal.apiKey) shouldBe true
                    keys.findFirstSharedByProviderId(provider.id!!) shouldBe null
                }
            }

            When("제공자가 교체 키를 거절하면") {
                lateinit var existing: UserProvider

                beforeTest {
                    if (it.type != TestType.Test) {
                        return@beforeTest
                    }
                    existing =
                        keys.insert(
                            UserProvider(
                                userId = nextAdmin.id!!,
                                providerId = provider.id!!,
                                userRole = Role.SUPER_ADMIN,
                                apiKey = cipher.encrypt(validKey),
                            ),
                        )
                    vendor.validationStatusCode = 401
                }

                val rotate =
                    suspend {
                        putSharedKey.putAiSharedKey(
                            loginId = admin.id!!,
                            providerId = provider.id!!,
                            request = PutAiSharedKeyRequest("dummy-rejected-${RandomFixture.randomId()}"),
                        )
                    }
                Then("제공자 오류를 반환하고 기존 암호문을 보존한다") {
                    shouldThrowWithMessage<BusinessException>(ErrorCode.AI_API_KEY_INVALID.message) { rotate() }
                    keys.findById(existing.id!!)!!.apiKey shouldBe existing.apiKey
                }
            }

            When("일반 사용자가 어드민 AI API를 호출하면") {
                lateinit var normal: User
                lateinit var date: String

                beforeTest {
                    if (it.type != TestType.Test) {
                        return@beforeTest
                    }
                    normal = users.insert(UserFixture.createRandomValidUser())
                    date = RandomFixture.randomDateTime().toLocalDate().toString()
                }

                Then("조회와 등록에 관리자 권한이 필요하다") {
                    val loginId = normal.id!!
                    val providerId = provider.id!!
                    val code = ErrorCode.AI_ADMIN_REQUIRED
                    shouldThrowWithMessage<BusinessException>(code.message) {
                        getSharedKey.getAiSharedKey(loginId = loginId, providerId = providerId)
                    }
                    shouldThrowWithMessage<BusinessException>(code.message) {
                        putSharedKey.putAiSharedKey(loginId = loginId, providerId = providerId, request = PutAiSharedKeyRequest(validKey))
                    }
                    shouldThrowWithMessage<BusinessException>(code.message) {
                        getDailyUsage.getAiDailyUsage(loginId = loginId, date = date)
                    }
                    keys.findByUserIdAndProviderId(userId = admin.id!!, providerId = providerId) shouldBe null
                    vendor.requestedApiKeys.isEmpty() shouldBe true
                }
            }
        }
    })
