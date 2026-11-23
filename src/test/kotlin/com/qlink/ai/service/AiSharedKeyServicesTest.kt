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

        Given("개인 자격증명을 가진 관리자와 공용 키가 없는 제공자") {
            lateinit var admin: User
            lateinit var nextAdmin: User
            lateinit var provider: AiProvider
            lateinit var personal: UserProvider
            lateinit var validKey: String

            beforeTest {
                if (it.type != TestType.Test) {
                    return@beforeTest
                }
                vendor.reset()
                nextAdmin = users.insert(UserFixture.createRandomValidSuperAdmin())
                admin = users.insert(UserFixture.createRandomValidUser().copyForTestRole(Role.ADMIN))
                AiFixture.insertSharedKeyOwnerIfAbsent()
                provider = providers.insert(AiFixture.createRandomValidAiProvider(type = AiProviderType.OPENAI, isActive = false))
                personal = keys.insert(AiFixture.createUserProviderOf(userId = admin.id!!, providerId = provider.id!!))
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

                Then("개인 키를 보존하고 기존 운영 계정의 같은 공용 행을 사용한다") {
                    val providerId = provider.id!!
                    getSharedKey.getAiSharedKey(loginId = admin.id!!, providerId = providerId).lastFour shouldBe null
                    val created =
                        putSharedKey.putAiSharedKey(
                            loginId = admin.id!!,
                            providerId = providerId,
                            request = PutAiSharedKeyRequest(validKey),
                        )
                    created.lastFour shouldBe validKey.takeLast(4)
                    val shared = keys.findByUserIdAndProviderId(userId = UserProvider.SHARED_OWNER_ID, providerId = providerId)!!
                    shared.userId shouldBe UserProvider.SHARED_OWNER_ID
                    shared.isDefault shouldBe true
                    (shared.apiKey != validKey && cipher.decrypt(shared.apiKey) == validKey) shouldBe true

                    val rotated =
                        putSharedKey.putAiSharedKey(
                            loginId = nextAdmin.id!!,
                            providerId = providerId,
                            request = PutAiSharedKeyRequest(replacement),
                        )
                    val saved = keys.findByUserIdAndProviderId(userId = UserProvider.SHARED_OWNER_ID, providerId = providerId)!!
                    saved.id shouldBe shared.id
                    saved.createdAt shouldBe shared.createdAt
                    saved.apiKey shouldNotBe shared.apiKey
                    (cipher.decrypt(saved.apiKey) == replacement) shouldBe true
                    keys.findAllByRole(Role.SUPER_ADMIN).map { it.id } shouldBe listOf(shared.id)
                    keys.findById(personal.id!!)!!.apiKey shouldBe personal.apiKey
                    if (nextAdmin.id != UserProvider.SHARED_OWNER_ID) {
                        keys.findByUserIdAndProviderId(userId = nextAdmin.id!!, providerId = providerId) shouldBe null
                    }
                    rotated.lastFour shouldBe replacement.takeLast(4)
                    getSharedKey.getAiSharedKey(loginId = admin.id!!, providerId = providerId).lastFour shouldBe rotated.lastFour
                }
            }

            When("다른 계정의 SUPER_ADMIN 키만 저장되어 있으면") {
                lateinit var otherKey: UserProvider

                beforeTest {
                    if (it.type != TestType.Test) {
                        return@beforeTest
                    }
                    val other = users.insert(UserFixture.createRandomValidSuperAdmin())
                    otherKey =
                        keys.insert(
                            UserProvider(
                                userId = other.id!!,
                                providerId = provider.id!!,
                                userRole = Role.SUPER_ADMIN,
                                apiKey = cipher.encrypt(validKey),
                            ),
                        )
                }

                Then("운영 공용 키 상태에 다른 계정의 키를 표시하지 않는다") {
                    getSharedKey.getAiSharedKey(loginId = admin.id!!, providerId = provider.id!!).lastFour shouldBe null
                    keys.findById(otherKey.id!!)!!.apiKey shouldBe otherKey.apiKey
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
                                userId = UserProvider.SHARED_OWNER_ID,
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
                    keys.findByUserIdAndProviderId(userId = UserProvider.SHARED_OWNER_ID, providerId = providerId) shouldBe null
                    vendor.requestedApiKeys.isEmpty() shouldBe true
                }
            }
        }
    })
